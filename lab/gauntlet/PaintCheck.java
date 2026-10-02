/// PaintCheck: proves that the gauntlet AI's watch view (AiView: GauntletAI.paint and describe) only reads, and shows
/// what it paints.
///
///     ./aisim.sh lab lab/gauntlet/PaintCheck.java RUN KEY [--every N] [--dump-every SEC] [--limit SEC]
///
/// It plays a recorded harness game again exactly as Match played it: ClientWorld.boot/create at the recorded game
/// speed, the rng seed, a probe on the real-time manager where the GameRecorder sat, AiLog.begin (with decision logs
/// only if the run kept logs, as Match; they go to aisim/paintcheck/RUN-KEY/), then the AIs in slot order with
/// Match.createAI, and world.tick(ANIMATION_SECONDS_PER_TICK) to the recorded end.
/// Every N world ticks (default 1, or 10 for games over 20 game minutes) it calls paint on a recording canvas and
/// describe on every unit and building of each AI that implements AiView, as the watch viewer would between two ticks.
/// Then it requires
///   - every census checksum (each 30 game seconds) and the end checksum as recorded,
///   - each slot's decision log line for line as recorded (header lines aside), where the run kept it: a batch
///     without --logs plays with logging off (and so does this), --logs lost deletes the logs of the games team A
///     won (ours is then written, not compared),
///   - the AI counters and swallowed errors of each team as the result row has them (paint must count nothing),
///   - a clean canvas: no "paint error" status, no exception, cells on the map, colours 0..0xFFFFFF, positive radii,
///     no line breaks, no status key painted twice in one paint,
///   - texts the viewer shows whole: a status line (key: value) and a mark label of at most MAX_LINE characters, a
///     describe text of at most MAX_DESCRIBE, at most MAX_STATUS status lines and MAX_SHAPES shapes in one paint.
/// It also times paint and describe, and writes what was painted (statuses, shapes by layer, the describe texts) every
/// --dump-every game seconds (default 600) and at the end to aisim/paintcheck/RUN-KEY/paint-sSLOT-HHMMSS.txt
/// (RUN-KEY-toSEC with --limit), so it can be read next to the AI log lines of the same moment. Exit 0 when all of it
/// holds, 1 otherwise, 2 on usage errors.
///
/// Time: it counts world ticks, the steps it plays (50 a real second at every speed), and turns game time into world
/// ticks at the game's speed, which the game file's header gives (secondsPerTick; normal speed without one): a census
/// or end t is game seconds in a file with clock "game" (world tick t * 1000 / ms per tick: 80 ms at ludicrous, 35
/// fast, 20 normal, 10 slow), else world ticks / 50 (older files). --limit and --dump-every are game seconds; every
/// time it prints is game time (clock), with the world tick where it helps. The world plays at the job's speed
/// (ClientWorld.parameters uses job.gameSpeed()); a job without one whose file was recorded at another speed plays at
/// the file's, and a world whose speed differs from the recording's is refused.
import com.fasterxml.jackson.databind.JsonNode;
import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.AiView;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.analysis.Game;
import com.oddlabs.tt.aisim.analysis.Runs;
import com.oddlabs.tt.aisim.play.ClientWorld;
import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.aisim.play.Match;
import com.oddlabs.tt.animation.Animated;
import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;

static final int TPS = GameTime.TICKS_PER_SECOND;
/// The census period of the GameRecorder, in game ms.
static final long CENSUS_MILLIS = 30_000;
/// The game time a world tick covers, in ms, at the speed of the game being checked: set from its game file before
/// anything is played or printed (clock).
static int millis_per_tick = 20;
/// Game speed names (Job.SPEEDS) by the game time of a world tick in ms (World.GAME_MILLIS_PER_TICK).
static final Map<Integer, String> SPEED_BY_MILLIS = Map.of(10, "slow", 20, "normal", 35, "fast", 80, "ludicrous");
// The viewer's limits (aisim/watch OverlayPaint and AiOverlay, package-private there): what it cuts or leaves out.
/// A status line as the AI panel shows it ("key: value"), and a mark label: short enough to read whole at a glance
/// (the viewer cuts a key, a value or a label longer than OverlayPaint.MAX_TEXT).
static final int MAX_LINE = 100;
/// A describe text: the inspector cuts "AI: " + text at AiOverlay.MAX_LINE, 90 characters.
static final int MAX_DESCRIBE = 86;
/// Status lines and shapes in one paint (OverlayPaint.MAX_STATUS, MAX_SHAPES): the viewer leaves out the rest.
static final int MAX_STATUS = 20;
static final int MAX_SHAPES = 5_000;

void main(String[] args) throws Exception {
    // before anything reads Headless: the check needs no window (a bare java call may not give the flag)
    if (System.getProperty("com.oddlabs.tt.headless") == null)
        System.setProperty("com.oddlabs.tt.headless", "true");
    List<String> positional = new ArrayList<>();
    int every = -1;
    int dump_every = 600;
    int limit = -1;
    for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
            case "--every" -> every = Integer.parseInt(args[++i]);
            case "--dump-every" -> dump_every = Integer.parseInt(args[++i]);
            case "--limit" -> limit = Integer.parseInt(args[++i]);
            default -> positional.add(args[i]);
        }
    }
    if (positional.size() != 2 || every == 0 || dump_every <= 0) {
        IO.println("usage: ./aisim.sh lab lab/gauntlet/PaintCheck.java RUN KEY [--every N] [--dump-every SEC] [--limit SEC]");
        System.exit(2);
    }
    String run = positional.get(0);
    String key = positional.get(1);
    Path dir = Files.exists(Path.of(run).resolve("run.json")) ? Path.of(run) : Runs.dir(run);
    Job listed = job(dir, key);
    Recorded recorded = Recorded.read(Runs.gameFile(dir, key));
    if (recorded.end_tick < 0) {
        IO.println("paintcheck: the game file has no end line");
        System.exit(2);
    }
    if (recorded.speed_changed) {
        IO.println("paintcheck: the game speed changed during the game (a speed line): not supported");
        System.exit(2);
    }
    millis_per_tick = recorded.millis_per_tick;
    String speed = SPEED_BY_MILLIS.get(millis_per_tick);
    if (speed == null) {
        IO.println("paintcheck: the game file's secondsPerTick is no game speed's (" + millis_per_tick + " ms a tick)");
        System.exit(2);
    }
    // a job from before speeds were written down plays at the recorded one
    Job job = listed.speed() == null && !speed.equals("normal") ? atSpeed(listed, speed) : listed;
    int end_tick = limit >= 0 ? (int) Math.min(recorded.end_tick, limit * 1000L / millis_per_tick)
            : recorded.end_tick;
    boolean limited = end_tick < recorded.end_tick;
    if (every < 0)
        every = (long) recorded.end_tick * millis_per_tick <= 20 * 60_000L ? 1 : 10;
    // a run to a limit writes apart, so a short look and a whole check of the same game can run side by side
    Path out = Aisim.ROOT.resolve("paintcheck").resolve(dir.getFileName() + "-" + key + (limited ? "-to" + limit : ""));
    Files.createDirectories(out);
    for (int slot = 0; slot < job.slots(); slot++) // an earlier check's logs, which a check without logs would leave
        Files.deleteIfExists(out.resolve(key + "-ai-s" + slot + ".log"));
    IO.println(("paintcheck %s %s: %s, %s, %s speed (%d ms of game time a world tick), recorded end %s (world tick"
            + " %d)%s, paint and describe every %d world ticks%s").formatted(run, key, job.players(), job.map(), speed,
            millis_per_tick, clock(recorded.end_tick), recorded.end_tick, limited ? ", played to " + clock(end_tick)
                    : "", every, job.logs() == null ? ", no decision logs (as the run)" : ""));

    List<String> problems = new ArrayList<>();
    ClientWorld.boot();
    try (ClientWorld client = ClientWorld.create(job)) {
        World world = client.world();
        if (world.getGameMillisPerTick() != millis_per_tick) {
            IO.println("paintcheck: the world plays %d ms a tick, the recording %d: not the recorded speed".formatted(
                    world.getGameMillisPerTick(), millis_per_tick));
            System.exit(2);
        }
        if (job.rng() != null)
            world.getRandom().setSeed(job.rng() * 1000 + job.side()); // before anything draws from it
        Probe probe = new Probe(world);
        // where the harness's GameRecorder sits: on the real-time manager, before the AIs
        world.getAnimationManagerRealTime().registerAnimation(probe);
        // logging on only if the game was played with it, as Match does: an AI may count some things only in logged
        // games (the gauntlet's shepherd_rej_*), and the counters must come out as the result row has them
        AiLog.begin(world, job.logs() == null ? null : slot -> out.resolve(key + "-ai-s" + slot + ".log"), "key="
                + key + " paintcheck");
        try {
            UnitInfo unit_info = ClientWorld.startingUnits();
            for (int slot = 0; slot < job.slots(); slot++) {
                Player player = world.getPlayers()[slot];
                player.setAI(Match.createAI(job.spec(slot), player, unit_info));
            }
            System.gc(); // as Match: the map's garbage
            List<Integer> views = new ArrayList<>();
            for (int slot = 0; slot < job.slots(); slot++)
                if (world.getPlayers()[slot].getAI() instanceof AiView)
                    views.add(slot);
            IO.println("AIs with a watch view (AiView): " + (views.isEmpty() ? "none" : views.stream().map(
                    s -> "s" + s + ":" + job.spec(s)).collect(Collectors.joining(", "))));
            if (views.isEmpty())
                problems.add("no AI of the game implements AiView: nothing was painted");
            Watcher watcher = new Watcher(world, views, problems, out);
            long start = System.nanoTime();
            Globals.run_ai = true;
            watcher.watch(0, false); // the viewer shows tick 0 too, before the AIs' first think
            GameTime.Every dump_clock = new GameTime.Every(dump_every * 1000L);
            while (world.getTick() < end_tick) {
                world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
                int tick = world.getTick();
                boolean dump = dump_clock.due(world) || tick == end_tick;
                if (tick % every == 0 || dump)
                    watcher.watch(tick, dump);
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            IO.println("played %s in %.1f s (paint and describe %.1f s of it)".formatted(clock(end_tick), seconds,
                    watcher.nanos() / 1e9));
            watcher.report();
            checkCensus(recorded, probe, end_tick, problems);
            if (!limited) {
                boolean same = world.getChecksum() == recorded.end_checksum;
                IO.println("end checksum: %d, recorded %d: %s".formatted(world.getChecksum(), recorded.end_checksum,
                        same ? "same" : "DIFFERENT"));
                if (!same)
                    problems.add("the end checksum differs from the recorded game");
                checkCounters(dir, key, job, world, problems);
            }
        } finally {
            AiLog.end();
        }
        if (!limited)
            checkLogs(dir, key, job, out, problems);
    }
    if (problems.isEmpty()) {
        IO.println("RESULT: OK (0 differences)");
        System.exit(0);
    }
    IO.println("RESULT: FAILED");
    for (String problem : problems)
        IO.println("  " + problem);
    System.exit(1);
}

static Job job(Path dir, String key) throws IOException {
    JsonNode jobs = Aisim.JSON.readTree(dir.resolve("run.json").toFile()).get("jobs");
    if (jobs != null)
        for (JsonNode job : jobs)
            if (key.equals(job.path("key").asText()))
                return Aisim.JSON.treeToValue(job, Job.class);
    IO.println("paintcheck: run " + dir + " has no job " + key);
    System.exit(2);
    throw new IllegalStateException();
}

/// A job as {@code job}, at game speed {@code speed} (a Job.SPEEDS name).
static Job atSpeed(Job job, String speed) {
    return new Job(job.run(), job.key(), job.seed(), job.side(), job.players(), job.seats(), job.size(), job.terrain(),
            job.hills(), job.trees(), job.supplies(), job.minutes(), job.rng(), job.collapse(), job.stopWhenAOut(),
            job.game(), job.logs(), speed);
}

/// The whole game seconds played by world tick {@code tick}, cut (as the viewer's clock), not rounded: every time
/// PaintCheck prints, and the dump files' names. A census or dump clock strikes on the last world tick at or before
/// its moment, so a strike between two world ticks reads a second early (--dump-every 45 at ludicrous strikes at
/// world tick 562, 44.96 game seconds: 0:44, paint-s0-000044.txt).
static long gameSeconds(int tick) {
    return (long) tick * millis_per_tick / 1000;
}

/// World tick {@code tick} as game time, m:ss or h:mm:ss (gameSeconds).
static String clock(int tick) {
    long s = gameSeconds(tick);
    return s >= 3600 ? "%d:%02d:%02d".formatted(s / 3600, s / 60 % 60, s % 60) : "%d:%02d".formatted(s / 60, s % 60);
}

/// What the game file says: the census checksum at each world tick a census was written (every slot's line has the
/// same), the end, and the speed it was played at. Read from the raw lines, so t is as the recorder wrote it.
static final class Recorded {
    final Map<Integer, Integer> census = new TreeMap<>();
    int end_tick = -1;
    int end_checksum;
    /// The game time of a world tick in ms, from the header's secondsPerTick (20, normal speed, without one).
    int millis_per_tick = 20;
    /// Whether t is game seconds (clock "game" in the header), not world ticks / 50 (files from before).
    boolean game_clock;
    /// Whether the speed changed during the game (a speed line), which the harness never does.
    boolean speed_changed;

    static Recorded read(Path file) throws IOException {
        Recorded recorded = new Recorded();
        List<Map<String, Object>> lines = Runs.readJsonl(file);
        if (!lines.isEmpty()) {
            Map<String, Object> header = lines.get(0);
            if (header.get("secondsPerTick") instanceof Number seconds)
                recorded.millis_per_tick = (int) Math.round(seconds.doubleValue() * 1000);
            recorded.game_clock = "game".equals(header.get("clock"));
        }
        for (Map<String, Object> line : lines) {
            Object ev = line.get("ev");
            if ("census".equals(ev)) {
                recorded.census.putIfAbsent(recorded.tick(Game.num(line, "t")), (int) Game.num(line, "checksum"));
            } else if ("end".equals(ev)) {
                recorded.end_tick = recorded.tick(Game.num(line, "t"));
                recorded.end_checksum = (int) Game.num(line, "checksum");
            } else if ("speed".equals(ev)) {
                recorded.speed_changed = true;
            }
        }
        return recorded;
    }

    /// The world tick of a recorded t: game seconds at this file's speed, or world ticks / 50 in an older file.
    int tick(double t) {
        return (int) Math.round(game_clock ? t * 1000 / millis_per_tick : t * TPS);
    }
}

/// The world's checksum at every census, sampled where and when the GameRecorder samples it: on the real-time manager
/// before the AIs, on the tick its 30-game-second clock strikes (a GameTime.Every of its own). It only reads.
static final class Probe implements Animated {
    private final World world;
    private final GameTime.Every census_clock = new GameTime.Every(CENSUS_MILLIS);
    /// The checksum at each census, by world tick.
    final Map<Integer, Integer> sampled = new TreeMap<>();
    /// Census ticks whose game time was not the world tick's (game ms != tick * ms per tick): none at a fixed speed.
    int clock_slips;

    Probe(World world) {
        this.world = world;
    }

    @Override
    public void animate(float t) {
        if (!census_clock.due(world))
            return;
        int tick = world.getTick();
        if (world.getGameMillis() != (long) tick * millis_per_tick)
            clock_slips++;
        sampled.put(tick, world.getChecksum());
    }
}

/// Every recorded census up to {@code end_tick} against the probe's: each one the recorder's clock wrote (all but a
/// last census at the recorded end that falls between two census times: the recorder writes that one after the last
/// tick's AIs, and the end checksum checks it).
static void checkCensus(Recorded recorded, Probe probe, int end_tick, List<String> problems) {
    int compared = 0;
    int bad = 0;
    int first_bad = -1;
    for (Map.Entry<Integer, Integer> census : recorded.census.entrySet()) {
        int tick = census.getKey();
        Integer ours = probe.sampled.get(tick);
        if (tick > end_tick || ours == null && tick >= recorded.end_tick)
            continue;
        compared++;
        if (ours == null || !ours.equals(census.getValue())) {
            bad++;
            first_bad = first_bad < 0 ? tick : first_bad;
        }
    }
    long struck = probe.sampled.keySet().stream().filter(tick -> tick <= end_tick).count();
    IO.println("census: %d of %d recorded checksums match%s (the probe sampled %d census ticks, each 30 game seconds)"
            .formatted(compared - bad, compared, bad == 0 ? "" : ", first difference at " + clock(first_bad)
                    + " (world tick " + first_bad + ")", struck));
    if (probe.clock_slips > 0)
        problems.add("census: at " + probe.clock_slips + " census ticks the game time was not world tick * "
                + millis_per_tick + " ms");
    if (bad > 0 || compared == 0)
        problems.add("census: " + bad + " of " + compared + " checksums differ" + (bad > 0 ? ", first at " + clock(
                first_bad) : ""));
}

/// The counters and swallowed errors of each team, as the result row has them, against the live AI log handles.
static void checkCounters(Path dir, String key, Job job, World world, List<String> problems) throws IOException {
    Map<String, Object> row = null;
    for (Map<String, Object> r : Runs.readJsonl(dir.resolve("results.jsonl")))
        if (key.equals(r.get("key")))
            row = r;
    if (row == null || !(row.get("teams") instanceof List<?> teams)) {
        IO.println("counters: no result row to compare with");
        return;
    }
    int compared = 0;
    for (Object t : teams) {
        if (!(t instanceof Map<?, ?> team) || !(team.get("counters") instanceof Map<?, ?> counters))
            continue;
        int number = ((Number) team.get("team")).intValue();
        Map<String, Integer> ours = new TreeMap<>();
        int errors = 0;
        for (int slot = 0; slot < job.slots(); slot++) {
            if (job.team(slot) != number)
                continue;
            AiLog handle = AiLog.peek(world, slot);
            if (handle == null)
                continue;
            handle.counters().forEach((k, v) -> ours.merge(k, v, Integer::sum));
            errors += handle.errors();
        }
        Map<String, Integer> theirs = new TreeMap<>();
        counters.forEach((k, v) -> theirs.put((String) k, ((Number) v).intValue()));
        int recorded_errors = team.get("errors") instanceof Number n ? n.intValue() : 0;
        compared++;
        if (!ours.equals(theirs) || errors != recorded_errors) {
            Set<String> keys = new TreeSet<>(ours.keySet());
            keys.addAll(theirs.keySet());
            List<String> differ = new ArrayList<>();
            for (String k : keys)
                if (!Objects.equals(ours.get(k), theirs.get(k)))
                    differ.add(k + " " + ours.get(k) + " vs " + theirs.get(k));
            problems.add("team " + number + " counters differ from the result row: " + differ.subList(0,
                    Math.min(8, differ.size())) + (errors != recorded_errors ? ", errors " + errors + " vs "
                            + recorded_errors : ""));
        } else {
            IO.println("counters of team %d: %d keys, %d errors, same as the result row".formatted(number,
                    ours.size(), errors));
        }
    }
    if (compared == 0)
        IO.println("counters: the result row has none");
}

/// Each slot's decision log against the recorded one, line for line, header lines (# ...) aside. A batch without
/// --logs played with logging off, and so did this; --logs lost deletes the logs of the games team A won: ours is then
/// written, not compared.
static void checkLogs(Path dir, String key, Job job, Path out, List<String> problems) throws IOException {
    if (job.logs() == null) {
        IO.println("decision logs: the run kept none (played with logging off, as recorded), not compared");
        return;
    }
    for (int slot = 0; slot < job.slots(); slot++) {
        Path recorded = Runs.gameFile(dir, key).resolveSibling(key + "-ai-s" + slot + ".log");
        Path ours = out.resolve(key + "-ai-s" + slot + ".log");
        if (!Files.exists(recorded) && !Files.exists(ours))
            continue;
        if (!Files.exists(recorded)) {
            IO.println("s%d decision log: the run deleted it (--logs lost), not compared; ours is %s".formatted(slot,
                    Aisim.slash(ours)));
            continue;
        }
        if (!Files.exists(ours)) {
            problems.add("s" + slot + " decision log: only the recorded one exists");
            continue;
        }
        List<String> a = lines(recorded);
        List<String> b = lines(ours);
        int n = Math.min(a.size(), b.size());
        int first = -1;
        for (int i = 0; i < n && first < 0; i++)
            if (!a.get(i).equals(b.get(i)))
                first = i;
        if (first < 0 && a.size() == b.size()) {
            IO.println("s%d decision log: %,d lines, same as recorded".formatted(slot, a.size()));
        } else {
            int at = first < 0 ? n : first;
            problems.add("s%d decision log differs at line %d (recorded %d lines, ours %d): %s | %s".formatted(slot,
                    at + 1, a.size(), b.size(), at < a.size() ? a.get(at) : "-", at < b.size() ? b.get(at) : "-"));
        }
    }
}

static List<String> lines(Path file) throws IOException {
    List<String> lines = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
        if (!line.startsWith("#"))
            lines.add(line);
    return lines;
}

/// Calls the watch view of each AI as the viewer would, and keeps the statistics, problems and dumps.
static final class Watcher {
    private final World world;
    private final List<Integer> slots;
    private final List<String> problems;
    private final Path out;
    private final Canvas canvas;
    private final Map<String, Integer> problem_counts = new TreeMap<>();
    private final Map<String, Integer> layers_seen = new TreeMap<>();
    private final Map<String, Integer> describe_kinds = new TreeMap<>();
    private long paint_nanos;
    private long paint_max;
    private long describe_nanos;
    private long describe_max;
    private int paints;
    private int describe_rounds;
    private long describes;
    private long described;
    private int max_shapes;
    private int longest_describe;

    Watcher(World world, List<Integer> slots, List<String> problems, Path out) {
        this.world = world;
        this.slots = slots;
        this.problems = problems;
        this.out = out;
        this.canvas = new Canvas(world.getUnitGrid().getGridSize(), this::problem);
    }

    long nanos() {
        return paint_nanos + describe_nanos;
    }

    void watch(int tick, boolean dump) throws IOException {
        for (int slot : slots) {
            Player player = world.getPlayers()[slot];
            AiView view = (AiView) player.getAI();
            canvas.reset(tick);
            long t0 = System.nanoTime();
            try {
                view.paint(canvas);
            } catch (RuntimeException | AssertionError e) {
                problem("paint threw", "s" + slot + " at " + clock(tick) + ": " + e);
            }
            long t1 = System.nanoTime();
            paint_nanos += t1 - t0;
            paint_max = Math.max(paint_max, t1 - t0);
            paints++;
            max_shapes = Math.max(max_shapes, canvas.shapeCount());
            canvas.shapes.forEach((layer, shapes) -> layers_seen.merge(layer, shapes.size(), Integer::sum));
            String error = canvas.status.get("paint error");
            if (error != null)
                problem("paint error status", "s" + slot + " at " + clock(tick) + ": " + error);
            if (canvas.status.size() > MAX_STATUS)
                problem("over " + MAX_STATUS + " status lines", canvas.status.size() + " at " + clock(tick));
            if (canvas.shapeCount() > MAX_SHAPES)
                problem("over " + MAX_SHAPES + " shapes", canvas.shapeCount() + " at " + clock(tick));
            // the engine's own set, walked as it stands: describe must not change it
            Map<String, Integer> texts = dump ? new TreeMap<>() : null;
            long t2 = System.nanoTime();
            int units = 0;
            for (Selectable<?> s : player.getUnits().getSet()) {
                units++;
                String text;
                try {
                    text = view.describe(s);
                } catch (RuntimeException | AssertionError e) {
                    problem("describe threw", "s" + slot + " at " + clock(tick) + ": " + e);
                    continue;
                }
                describes++;
                if (text == null)
                    continue;
                described++;
                if (text.startsWith("describe failed"))
                    problem("describe failed", "s" + slot + " at " + clock(tick) + ": " + text);
                if (text.indexOf('\n') >= 0)
                    problem("line break in a describe text", text);
                String kind = text.replaceAll("[0-9]+", "#");
                if (text.length() > MAX_DESCRIBE) {
                    problem("describe text over " + MAX_DESCRIBE + " characters", text.length() + " at " + clock(tick)
                            + ": " + text);
                    canvas.too_long.merge("describe " + kind, 1, Integer::sum);
                }
                longest_describe = Math.max(longest_describe, text.length());
                describe_kinds.merge(kindKey(kind), 1, Integer::sum);
                if (texts != null)
                    texts.merge(kind, 1, Integer::sum);
            }
            long t3 = System.nanoTime();
            describe_nanos += t3 - t2;
            describe_max = Math.max(describe_max, t3 - t2);
            describe_rounds++;
            if (dump)
                dump(tick, slot, player, units, texts);
        }
    }

    /// The start of a describe text, up to its first comma, colon or digit, to count the kinds of jobs.
    private static String kindKey(String text) {
        int end = text.length();
        for (char c : new char[]{',', ':', '#', ';', '('}) {
            int i = text.indexOf(c);
            if (i >= 0)
                end = Math.min(end, i);
        }
        return text.substring(0, end).trim();
    }

    private void dump(int tick, int slot, Player player, int units, Map<String, Integer> texts) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("== %s (tick %d) s%d %s%n".formatted(clock(tick), tick, slot, player.getPlayerInfo().getName()));
        sb.append("status:%n".formatted());
        canvas.status.forEach((k, v) -> sb.append("  %s: %s%n".formatted(k, v)));
        canvas.shapes.forEach((layer, shapes) -> {
            sb.append("layer %s (%d):%n".formatted(layer, shapes.size()));
            for (String shape : shapes)
                sb.append("  ").append(shape).append(System.lineSeparator());
        });
        sb.append("describe: %d units and buildings in the set, %d texts:%n".formatted(units,
                texts.values().stream().mapToInt(Integer::intValue).sum()));
        texts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> sb.append(
                "  %5d  %s%n".formatted(e.getValue(), e.getKey())));
        long seconds = gameSeconds(tick); // the header's clock
        Path file = out.resolve("paint-s%d-%02d%02d%02d.txt".formatted(slot, seconds / 3600, seconds / 60 % 60,
                seconds % 60));
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        IO.println("-- %s s%d: %s".formatted(clock(tick), slot, Aisim.slash(file)));
        canvas.status.forEach((k, v) -> IO.println("   %s: %s".formatted(k, v)));
    }

    void problem(String kind, String detail) {
        int n = problem_counts.merge(kind, 1, Integer::sum);
        if (n == 1)
            problems.add(kind + ": " + detail + " (first of them)");
    }

    void report() {
        IO.println("paint: %d calls, mean %.1f us, max %.1f ms; at most %d shapes in one paint".formatted(paints,
                paints == 0 ? 0 : paint_nanos / 1e3 / paints, paint_max / 1e6, max_shapes));
        IO.println("describe: %d rounds over every unit and building, mean %.1f us per round, max %.1f ms; %,d calls, %,d with a text"
                .formatted(describe_rounds, describe_rounds == 0 ? 0 : describe_nanos / 1e3 / describe_rounds,
                        describe_max / 1e6, describes, described));
        IO.println("shapes painted by layer (all paints): " + layers_seen);
        IO.println("longest texts (characters; at most %d for a status line or label, %d for a describe text):"
                .formatted(MAX_LINE, MAX_DESCRIBE));
        IO.println("  status lines: " + canvas.longest_status);
        IO.println("  label %d, describe text %d".formatted(canvas.longest_label, longest_describe));
        if (!canvas.too_long.isEmpty()) {
            IO.println("texts over the limits, by kind (digits as #), the most frequent first:");
            canvas.too_long.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(30).forEach(
                    e -> IO.println("  %9d  %s".formatted(e.getValue(), e.getKey())));
        }
        IO.println("describe texts by kind (all rounds):");
        describe_kinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(30).forEach(
                e -> IO.println("  %9d  %s".formatted(e.getValue(), e.getKey())));
        if (!problem_counts.isEmpty())
            IO.println("canvas problems: " + problem_counts);
    }
}

/// The canvas paint draws on: one paint's statuses and shapes, every one checked.
static final class Canvas implements AiView.Canvas {
    private final int size;
    private final BiConsumer<String, String> problem;
    final Map<String, String> status = new LinkedHashMap<>();
    final Map<String, List<String>> shapes = new TreeMap<>();
    /// The longest "key: value" of each status key over all paints, in the order first painted.
    final Map<String, Integer> longest_status = new LinkedHashMap<>();
    int longest_label;
    /// Texts over MAX_LINE or MAX_DESCRIBE over all paints, by kind ("status KEY: ...", "label ...", "describe ...",
    /// digits as #): how often each was painted.
    final Map<String, Integer> too_long = new TreeMap<>();
    private int tick;

    Canvas(int size, BiConsumer<String, String> problem) {
        this.size = size;
        this.problem = problem;
    }

    void reset(int tick) {
        this.tick = tick;
        status.clear();
        shapes.clear();
    }

    int shapeCount() {
        return shapes.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public void status(String key, String value) {
        text(key);
        text(value);
        if (status.put(key, value) != null)
            problem.accept("status key painted twice", key + " at " + clock(tick));
        String line = key + ": " + value;
        longest_status.merge(key, line.length(), Math::max);
        if (line.length() > MAX_LINE) {
            problem.accept("status line over " + MAX_LINE + " characters", line.length() + " at " + clock(tick)
                    + ": " + line);
            too_long.merge("status " + line.replaceAll("[0-9]+", "#"), 1, Integer::sum);
        }
    }

    @Override
    public void mark(String layer, int x, int y, int rgb, String label) {
        add(layer, "mark   %d,%d #%06X %s".formatted(x, y, rgb, label), rgb);
        cell(layer, x, y);
        text(label);
        longest_label = Math.max(longest_label, label.length());
        if (label.length() > MAX_LINE) {
            problem.accept("label over " + MAX_LINE + " characters", label.length() + " at " + clock(tick) + ": "
                    + label);
            too_long.merge("label " + label.replaceAll("[0-9]+", "#"), 1, Integer::sum);
        }
    }

    @Override
    public void line(String layer, int x0, int y0, int x1, int y1, int rgb) {
        add(layer, "line   %d,%d -> %d,%d #%06X".formatted(x0, y0, x1, y1, rgb), rgb);
        cell(layer, x0, y0);
        cell(layer, x1, y1);
    }

    @Override
    public void circle(String layer, int x, int y, float radius, int rgb) {
        add(layer, "circle %d,%d r%.0f #%06X".formatted(x, y, radius, rgb), rgb);
        cell(layer, x, y);
        if (!(radius > 0f) || Float.isInfinite(radius))
            problem.accept("bad radius", radius + " in " + layer + " at " + clock(tick));
    }

    private void add(String layer, String shape, int rgb) {
        if (layer.isBlank())
            problem.accept("blank layer", shape + " at " + clock(tick));
        if (rgb < 0 || rgb > 0xFFFFFF)
            problem.accept("colour out of range", shape + " at " + clock(tick));
        shapes.computeIfAbsent(layer, _ -> new ArrayList<>()).add(shape);
    }

    private void cell(String layer, int x, int y) {
        if (x < 0 || y < 0 || x >= size || y >= size)
            problem.accept("cell outside the map", x + "," + y + " in " + layer + " at " + clock(tick));
    }

    private void text(String text) {
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
            problem.accept("line break in a text", text);
    }
}
