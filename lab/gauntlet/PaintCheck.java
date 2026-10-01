/// PaintCheck: proves that the gauntlet AI's watch view (AiView: GauntletAI.paint and describe) only reads, and shows
/// what it paints.
///
///     ./aisim.sh lab lab/gauntlet/PaintCheck.java RUN KEY [--every N] [--dump-every SEC] [--limit SEC]
///
/// It plays a recorded harness game again exactly as Match played it: ClientWorld.boot/create, the rng seed, a probe
/// on the real-time manager where the GameRecorder sat, AiLog.begin (the decision logs go to aisim/paintcheck/RUN-KEY/),
/// then the AIs in slot order with Match.createAI, and world.tick(ANIMATION_SECONDS_PER_TICK) to the recorded end.
/// Every N ticks (default 1, or 10 for games over 20 minutes) it calls paint on a recording canvas and describe on
/// every unit and building of each AI that implements AiView, as the watch viewer would between two ticks. Then it
/// requires
///   - every 30-s census checksum and the end checksum as recorded,
///   - each slot's decision log line for line as recorded (header lines aside),
///   - the AI counters and swallowed errors of each team as the result row has them (paint must count nothing),
///   - a clean canvas: no "paint error" status, no exception, cells on the map, colours 0..0xFFFFFF, positive radii,
///     no line breaks, no status key painted twice in one paint.
/// It also times paint and describe, and writes what was painted (statuses, shapes by layer, the describe texts) every
/// --dump-every seconds (default 600) and at the end to aisim/paintcheck/RUN-KEY/paint-sSLOT-HHMMSS.txt (RUN-KEY-toSEC
/// with --limit), so it can be read next to the AI log lines of the same moment. Exit 0 when all of it holds, 1 otherwise, 2 on usage errors.
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
static final int CENSUS_TICKS = 30 * TPS;

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
    Job job = job(dir, key);
    Recorded recorded = Recorded.read(Runs.gameFile(dir, key));
    if (recorded.end_tick < 0) {
        IO.println("paintcheck: the game file has no end line");
        System.exit(2);
    }
    int end_tick = limit >= 0 ? Math.min(recorded.end_tick, limit * TPS) : recorded.end_tick;
    boolean limited = end_tick < recorded.end_tick;
    if (every < 0)
        every = recorded.end_tick <= 20 * 60 * TPS ? 1 : 10;
    // a run to a limit writes apart, so a short look and a whole check of the same game can run side by side
    Path out = Aisim.ROOT.resolve("paintcheck").resolve(dir.getFileName() + "-" + key + (limited ? "-to" + limit : ""));
    Files.createDirectories(out);
    IO.println("paintcheck %s %s: %s, %s, recorded end %s (tick %d)%s, paint and describe every %d ticks".formatted(
            run, key, job.players(), job.map(), clock(recorded.end_tick), recorded.end_tick,
            limited ? ", played to " + clock(end_tick) : "", every));

    List<String> problems = new ArrayList<>();
    ClientWorld.boot();
    try (ClientWorld client = ClientWorld.create(job)) {
        World world = client.world();
        if (job.rng() != null)
            world.getRandom().setSeed(job.rng() * 1000 + job.side()); // before anything draws from it
        Map<Integer, Integer> sampled = new TreeMap<>();
        // where the harness's GameRecorder sits: on the real-time manager, before the AIs
        world.getAnimationManagerRealTime().registerAnimation(new Probe(world, sampled));
        AiLog.begin(world, slot -> out.resolve(key + "-ai-s" + slot + ".log"), "key=" + key + " paintcheck");
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
            while (world.getTick() < end_tick) {
                world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
                int tick = world.getTick();
                boolean dump = tick % (dump_every * TPS) == 0 || tick == end_tick;
                if (tick % every == 0 || dump)
                    watcher.watch(tick, dump);
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            IO.println("played %s in %.1f s (paint and describe %.1f s of it)".formatted(clock(end_tick), seconds,
                    watcher.nanos() / 1e9));
            watcher.report();
            checkCensus(recorded, sampled, end_tick, problems);
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

static String clock(int tick) {
    int s = tick / TPS;
    return s >= 3600 ? "%d:%02d:%02d".formatted(s / 3600, s / 60 % 60, s % 60) : "%d:%02d".formatted(s / 60, s % 60);
}

/// What the game file says: the census checksum at each census tick (every slot's line has the same), and the end.
static final class Recorded {
    final Map<Integer, Integer> census = new TreeMap<>();
    int end_tick = -1;
    int end_checksum;

    static Recorded read(Path file) {
        Game game = Game.file(file);
        Recorded recorded = new Recorded();
        for (Map<String, Object> player : game.players()) {
            int slot = (int) Game.num(player, "s");
            for (Map<String, Object> census : game.census(slot)) {
                int tick = (int) Math.round(Game.num(census, "t") * TPS);
                if (tick % CENSUS_TICKS == 0)
                    recorded.census.putIfAbsent(tick, (int) Game.num(census, "checksum"));
            }
        }
        for (Map<String, Object> end : game.events("end")) {
            recorded.end_tick = (int) Math.round(Game.num(end, "t") * TPS);
            recorded.end_checksum = (int) Game.num(end, "checksum");
        }
        return recorded;
    }
}

/// The world's checksum at every census tick, sampled where the GameRecorder samples it (it only reads).
record Probe(World world, Map<Integer, Integer> sampled) implements Animated {
    @Override
    public void animate(float t) {
        int tick = world.getTick();
        if (tick % CENSUS_TICKS == 0)
            sampled.put(tick, world.getChecksum());
    }
}

static void checkCensus(Recorded recorded, Map<Integer, Integer> sampled, int end_tick, List<String> problems) {
    int compared = 0;
    int bad = 0;
    int first_bad = -1;
    for (Map.Entry<Integer, Integer> census : recorded.census.entrySet()) {
        int tick = census.getKey();
        if (tick > end_tick)
            continue;
        compared++;
        Integer ours = sampled.get(tick);
        if (ours == null || !ours.equals(census.getValue())) {
            bad++;
            first_bad = first_bad < 0 ? tick : first_bad;
        }
    }
    IO.println("census: %d of %d recorded checksums match%s".formatted(compared - bad, compared,
            bad == 0 ? "" : ", first difference at " + clock(first_bad)));
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

/// Each slot's decision log against the recorded one, line for line, header lines (# ...) aside.
static void checkLogs(Path dir, String key, Job job, Path out, List<String> problems) throws IOException {
    for (int slot = 0; slot < job.slots(); slot++) {
        Path recorded = Runs.gameFile(dir, key).resolveSibling(key + "-ai-s" + slot + ".log");
        Path ours = out.resolve(key + "-ai-s" + slot + ".log");
        if (!Files.exists(recorded) && !Files.exists(ours))
            continue;
        if (!Files.exists(recorded) || !Files.exists(ours)) {
            problems.add("s" + slot + " decision log: only " + (Files.exists(ours) ? "ours" : "the recorded one")
                    + " exists");
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
        int seconds = tick / TPS;
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
    }

    @Override
    public void mark(String layer, int x, int y, int rgb, String label) {
        add(layer, "mark   %d,%d #%06X %s".formatted(x, y, rgb, label), rgb);
        cell(layer, x, y);
        text(label);
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
