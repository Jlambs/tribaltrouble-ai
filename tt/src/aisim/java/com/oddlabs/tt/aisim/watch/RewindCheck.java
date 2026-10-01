package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.aisim.UsageException;
import com.oddlabs.tt.aisim.analysis.Game;
import com.oddlabs.tt.aisim.play.ClientWorld;
import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.aisim.play.Match;
import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.global.Headless;
import com.oddlabs.tt.landscape.World;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The rewind's fidelity test ({@code ./aisim.sh rewindcheck}): plays a recorded harness game exactly as the harness
 * played it, with rewind checkpoints ({@link Checkpoints}), and checks
 * <ol>
 * <li>the first pass: every census checksum of the game file (every 30 s) and the end line's checksum;</li>
 * <li>restores: for each {@code --at} second in ascending order, restores the checkpoint at or before it and replays
 * {@code --span} seconds, then restores the second point again (an earlier checkpoint after later ones, and one
 * checkpoint twice). Each replay must give the first pass's checksum at every second (the {@link Track}), the same AI
 * log lines at every tick, and the first pass's checksum right after the restore and at its end;</li>
 * <li>the checkpoints' size estimate against the heap, once at the middle of the game (with System.gc()).</li>
 * </ol>
 * It prints what each step cost and exits 0 when everything matched, 1 otherwise, 2 on usage errors.
 *
 * <p>Usage: {@code RewindCheck RUN KEY | FILE.jsonl [--ai-from-run] [--at SEC,SEC..] [--span SEC] [--gl]
 * [--checkpoint SEC] [--memory MB] [--limit SEC] [--no-logs] [--classes]}. The game is found as watch finds it
 * ({@link WatchCommand#resolve}: a run name or any checkout's run folder, merged runs, frozen {@code @TAG} AIs and
 * {@code --ai-from-run}), so it checks the very game watch plays; the world is built like {@link Match} builds it, the
 * AIs as watch makes them ({@link SeatAis}). Headless unless {@code --gl}, which needs
 * {@code -Dcom.oddlabs.tt.headless=false} and a desktop: it
 * simulates in a hidden GL window, as the viewer does (the element tree filled, a height texture). The JVM needs the
 * flags of {@link Copier}.
 */
public final class RewindCheck {
    private static final String HEADLESS_PROPERTY = "com.oddlabs.tt.headless";
    private static final int TPS = GameTime.TICKS_PER_SECOND;
    /** Recorded census seconds are every 30 s. */
    private static final int CENSUS_TICKS = 30 * TPS;

    private static final String USAGE_OPTIONS = String.join(" ", "[--ai-from-run] [--at SEC,SEC..] [--span SEC]",
            "[--gl] [--checkpoint SEC] [--memory MB] [--limit SEC] [--no-logs] [--classes]");

    private RewindCheck() {
    }

    public static void main(@NonNull String @NonNull [] args) {
        int code;
        try {
            code = run(args);
        } catch (UsageException e) {
            System.err.println("rewindcheck: " + e.getMessage());
            code = 2;
        } catch (IOException | RuntimeException | Error e) {
            System.err.println("rewindcheck: FAILED with an exception");
            e.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }

    /** What the command line asks for. */
    private record Options(@NonNull List<String> game, boolean ai_from_run, @Nullable List<Integer> at, int span,
                           boolean gl, int checkpoint_seconds, int megabytes, int limit, boolean logs,
                           boolean classes) {
    }

    private static @NonNull Options parse(@NonNull String @NonNull [] args) {
        List<String> positional = new ArrayList<>();
        List<Integer> at = null;
        int span = 120;
        boolean gl = false;
        int checkpoint = 60;
        int megabytes = (int) (Runtime.getRuntime().maxMemory() * 0.45 / (1024 * 1024));
        int limit = -1;
        boolean logs = true;
        boolean classes = false;
        boolean ai_from_run = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--at" -> {
                    at = new ArrayList<>();
                    for (String s : value(args, ++i, arg).split(",")) {
                        at.add(number(s, arg));
                    }
                }
                case "--span" -> span = number(value(args, ++i, arg), arg);
                case "--gl" -> gl = true;
                case "--checkpoint" -> checkpoint = number(value(args, ++i, arg), arg);
                case "--memory" -> megabytes = number(value(args, ++i, arg), arg);
                case "--limit" -> limit = number(value(args, ++i, arg), arg);
                case "--no-logs" -> logs = false;
                case "--classes" -> classes = true;
                case "--ai-from-run" -> ai_from_run = true;
                default -> {
                    if (arg.startsWith("--")) {
                        throw new UsageException("unknown option " + arg);
                    }
                    positional.add(arg);
                }
            }
        }
        if (positional.size() != 2 && !(positional.size() == 1 && positional.getFirst().endsWith(".jsonl"))) {
            throw new UsageException("usage: rewindcheck RUN KEY | FILE.jsonl " + USAGE_OPTIONS);
        }
        if (checkpoint <= 0 || megabytes <= 0 || span < 0) {
            throw new UsageException("--checkpoint and --memory must be positive, --span not negative");
        }
        return new Options(positional, ai_from_run, at, span, gl, checkpoint, megabytes, limit, logs, classes);
    }

    private static @NonNull String value(@NonNull String @NonNull [] args, int i, @NonNull String option) {
        if (i >= args.length) {
            throw new UsageException(option + " needs a value");
        }
        return args[i];
    }

    private static int number(@NonNull String text, @NonNull String option) {
        try {
            int n = Integer.parseInt(text.trim());
            if (n < 0) {
                throw new UsageException(option + " takes numbers of 0 or more: " + text);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new UsageException(option + " takes whole numbers: " + text);
        }
    }

    // ---------------------------------------------------------------- the check

    private static int run(@NonNull String @NonNull [] args) throws IOException {
        Options options = parse(args);
        // before anything reads Headless: headless unless --gl (aisim.sh gives the flag; a bare java call may not)
        if (!options.gl() && System.getProperty(HEADLESS_PROPERTY) == null) {
            System.setProperty(HEADLESS_PROPERTY, "true");
        }
        if (options.gl() == Headless.ENABLED) {
            String flag = "-D" + HEADLESS_PROPERTY + "=false";
            throw new UsageException(options.gl() ? "--gl needs " + flag : flag + " needs --gl");
        }
        WatchCommand.Resolved game = WatchCommand.resolve(options.game(), options.ai_from_run());
        Job job = game.job();
        Path game_file = game.gameFile();
        if (game_file == null) {
            throw new UsageException("no game file to check the replays against");
        }
        Recording recording = Recording.read(game_file);
        int end_tick = recording.end_tick;
        if (end_tick < 0) {
            throw new UsageException("the game file has no end line");
        }
        boolean limited = options.limit() >= 0 && options.limit() * TPS < end_tick;
        if (limited) {
            end_tick = options.limit() * TPS;
        }
        System.out.printf(Locale.ROOT, "rewindcheck %s %s: %s, %s, %s, recorded end %s (tick %d)%s%n",
                job.run(), job.key(), job.players(), job.map(), options.gl() ? "GL" : "headless",
                clock(recording.end_tick), recording.end_tick, limited ? ", played to " + clock(end_tick) : "");
        List<String> jvm = new ArrayList<>(ManagementFactory.getRuntimeMXBean().getInputArguments());
        jvm.removeIf(argument -> argument.startsWith("-Dorg.lwjgl") || argument.startsWith("-Daisim"));
        System.out.println("jvm: " + String.join(" ", jvm));

        ClientWorld.boot();
        Problems problems = new Problems();
        try (ClientWorld client = ClientWorld.create(job)) {
            World world = client.world();
            if (job.rng() != null) {
                world.getRandom().setSeed(job.rng() * 1000 + job.side()); // before anything draws from it
            }
            problems.list.addAll(RewindCheckRules.check(new Copier.Rules(world, List.of())));
            Track track = new Track();
            // where the harness's recorder sits: on the real-time manager, before the AIs
            world.getAnimationManagerRealTime().registerAnimation(new Track.Probe(world, track));
            AiLines lines = new AiLines(world);
            if (options.logs()) {
                System.out.println("ai logs: " + beginAiLogs(world, lines, "key=" + job.key() + " rewindcheck"));
            } else {
                AiLog.begin(world, null, "");
            }
            try {
                try {
                    SeatAis.create(job, world, game.loaders());
                } catch (IllegalArgumentException e) { // an AI this build cannot make: not a rewind failure
                    throw new UsageException(
                            "cannot make the AIs: " + e.getMessage() + (options.ai_from_run() ? "" : " (--ai-from-run plays the recording's own AI package)"));
                }
                System.gc(); // as Match: the map's garbage
                check(options, world, track, lines, recording, end_tick, limited, problems);
            } finally {
                AiLog.end();
            }
        }
        if (problems.list.isEmpty()) {
            System.out.println("RESULT: OK");
            return 0;
        }
        System.out.println("RESULT: FAILED");
        for (String problem : problems.list) {
            System.out.println("  " + problem);
        }
        return 1;
    }

    private static void check(@NonNull Options options, @NonNull World world, @NonNull Track track,
            @NonNull AiLines lines, @NonNull Recording recording, int end_tick, boolean limited,
            @NonNull Problems problems) {
        long heap_before = usedHeap();
        Checkpoints checkpoints = new Checkpoints(world, options.checkpoint_seconds() * TPS, options.megabytes(),
                List.of(track, lines));
        long heap_after = usedHeap();
        long[] first = checkpoints.sizeAt(world.getTick());
        System.out.printf(Locale.ROOT, "checkpoint at tick 0: %,d objects, estimate %s, heap +%s, %.0f ms%n",
                first[1], mb(first[0]), mb(heap_after - heap_before), first[2] / 1e6);

        // ------------------------------------------------ first pass
        Globals.run_ai = true;
        int[] post = new int[end_tick / TPS + 2]; // the checksum after each second's tick
        int measure_tick = end_tick / 2 / TPS * TPS;
        long start = System.nanoTime();
        long measuring = 0;
        while (world.getTick() < end_tick) {
            world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
            int tick = world.getTick();
            checkpoints.atFrontier(world);
            if (tick % TPS == 0) {
                post[tick / TPS] = world.getChecksum();
                if (tick % (600 * TPS) == 0) {
                    System.out.printf(Locale.ROOT, "  %s: %.1f s, %s%n", clock(tick),
                            (System.nanoTime() - start - measuring) / 1e9, checkpoints.describe());
                }
            }
            if (tick == measure_tick) {
                long m = System.nanoTime();
                measureHeap(checkpoints, world, options.classes());
                measuring += System.nanoTime() - m;
            }
        }
        double pass_seconds = (System.nanoTime() - start - measuring) / 1e9;
        String pass = "first pass: %s in %.1f s (%.0f game s per s), %d checkpoints taken in %.1f s (%.0f ms each)";
        System.out.printf(Locale.ROOT, pass + ", %s%n", clock(end_tick), pass_seconds,
                end_tick / (double) TPS / pass_seconds, checkpoints.taken(),
                checkpoints.taken() * checkpoints.meanCopyMillis() / 1000, checkpoints.meanCopyMillis(),
                checkpoints.describe());
        if (checkpoints.failure() != null) {
            problems.add("checkpoints stopped: " + checkpoints.failure());
        }
        checkRecording(world, track, recording, end_tick, limited, problems);
        lines.firstPassDone();
        System.out.printf(Locale.ROOT, "ai log lines in the first pass: %,d%n", lines.first_lines);

        // ------------------------------------------------ restores
        List<Integer> at = new ArrayList<>(options.at() != null ? options.at() : defaultPoints(end_tick));
        at.replaceAll(s -> Math.min(s, end_tick / TPS));
        at.sort(null);
        List<Integer> schedule = new ArrayList<>(at);
        schedule.add(at.get(Math.min(1, at.size() - 1))); // an earlier checkpoint after later ones, and one again
        System.out.println("restores (span " + options.span() + " s):");
        String columns = "checkpoint  restore ms  replayed    s  game s/s  after restore  track  ai log  at end";
        System.out.println("     at  " + columns);
        for (int second : schedule) {
            restoreAndReplay(checkpoints, world, track, lines, post, second, options.span(), end_tick, problems);
        }
        System.out.println("checkpoints: " + checkpoints.describe() + "; ticks " + ticksText(checkpoints.ticks()));
        System.out.printf(Locale.ROOT, "track: %d mismatches%s; ai log: %,d lines replayed, %d mismatching ticks%s%n",
                track.mismatches(), track.firstMismatch() < 0 ? "" : " (first at " + track.firstMismatch() + " s)",
                lines.replayed_lines, lines.mismatches,
                lines.first_mismatch < 0 ? "" : " (first at tick " + lines.first_mismatch + ")");
    }

    /** One restore and its replay, as one row of the table. */
    private static void restoreAndReplay(@NonNull Checkpoints checkpoints, @NonNull World world,
            @NonNull Track track, @NonNull AiLines lines, int @NonNull [] post, int second, int span, int end_tick,
            @NonNull Problems problems) {
        AiLog handle = AiLog.peek(world, 0);
        int restored = checkpoints.restoreAtOrBefore(world, second * TPS);
        double restore_ms = checkpoints.lastRestoreMillis();
        lines.startReplay(restored);
        // the AIs' handles are the restored ones: AiLog must hand out those, not the handles of before
        boolean handles_ok = handle == null || AiLog.peek(world, 0) != handle;
        boolean restored_ok = world.getTick() == restored && handles_ok
                && (restored % TPS != 0 || world.getChecksum() == post[restored / TPS]);
        int target = Math.min(end_tick, (second + span) * TPS);
        int track_before = track.mismatches();
        int lines_before = lines.mismatches;
        long start = System.nanoTime();
        Globals.run_ai = true;
        while (world.getTick() < target) {
            world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
            lines.checkBefore(world.getTick());
        }
        double replay_seconds = (System.nanoTime() - start) / 1e9;
        boolean track_ok = track.mismatches() == track_before;
        boolean lines_ok = lines.mismatches == lines_before;
        boolean end_ok = target % TPS != 0 || world.getChecksum() == post[target / TPS];
        double played = (target - restored) / (double) TPS;
        System.out.printf(Locale.ROOT, "%7s  %10s  %10.0f  %8s  %4.1f  %8.0f", clock(second * TPS),
                clock(restored), restore_ms, clock(target - restored), replay_seconds,
                replay_seconds > 0 ? played / replay_seconds : 0);
        System.out.printf(Locale.ROOT, "  %13s  %5s  %6s  %6s%n", ok(restored_ok), ok(track_ok), ok(lines_ok),
                ok(end_ok));
        String where = "restore for " + clock(second * TPS) + " (checkpoint " + clock(restored) + ")";
        if (!handles_ok) {
            problems.add(where + ": the restore did not bind the restored AI log handles");
        } else if (!restored_ok) {
            problems.add(
                    where + ": the world after the restore differs from the first pass (tick " + world.getTick() + ")");
        }
        if (!track_ok) {
            problems.add(where + ": REWIND MISMATCH, first at " + track.firstMismatch() + " s");
        }
        if (!lines_ok) {
            problems.add(where + ": the AI log differs from the first pass, first at tick " + lines.first_mismatch);
        }
        if (!end_ok) {
            problems.add(where + ": the checksum at " + clock(target) + " differs from the first pass");
        }
    }

    /** Census checksums (in-tick, every 30 s) against the Track, and the end line against the world after its tick. */
    private static void checkRecording(@NonNull World world, @NonNull Track track, @NonNull Recording recording,
            int end_tick, boolean limited, @NonNull Problems problems) {
        int compared = 0;
        int first_bad = -1;
        int bad = 0;
        for (Map.Entry<Integer, Integer> census : recording.census.entrySet()) {
            int tick = census.getKey();
            if (tick % CENSUS_TICKS != 0 || tick > end_tick || tick / TPS > track.frontier()) {
                continue;
            }
            compared++;
            if (track.checksum(tick / TPS) != census.getValue()) {
                bad++;
                first_bad = first_bad < 0 ? tick : first_bad;
            }
        }
        System.out.printf(Locale.ROOT, "census: %d of %d recorded checksums match%s%n", compared - bad, compared,
                bad == 0 ? "" : ", first difference at " + clock(first_bad));
        if (bad > 0 || compared == 0) {
            String count = bad + " of " + compared + " census checksums";
            String first = bad == 0 ? "" : ", first at " + clock(first_bad);
            problems.add("the first pass differs from the recorded game: " + count + first);
        }
        if (recording.inconsistent > 0) {
            problems.add("the game file's slots disagree on " + recording.inconsistent + " census checksums");
        }
        if (!limited) {
            boolean same = world.getChecksum() == recording.end_checksum;
            System.out.printf(Locale.ROOT, "end checksum: %d, recorded %d: %s%n", world.getChecksum(),
                    recording.end_checksum, same ? "same" : "DIFFERENT");
            if (!same) {
                problems.add("the end checksum differs from the recorded game");
            }
        }
    }

    /** The estimate of one checkpoint's size against what it takes on the heap (all garbage collected). */
    private static void measureHeap(@NonNull Checkpoints checkpoints, @NonNull World world, boolean classes) {
        long before = usedHeap();
        Copier.Result copy = checkpoints.measure(world, classes);
        long after = usedHeap();
        System.out.printf(Locale.ROOT, "  heap check at %s: %,d objects, estimate %s, ", clock(world.getTick()),
                copy.objects(), mb(copy.bytes()));
        System.out.printf(Locale.ROOT, "measured %s (%+.0f%%), copied in %.0f ms%n", mb(after - before),
                (after - before - copy.bytes()) * 100.0 / copy.bytes(), copy.nanos() / 1e6);
        Map<String, long[]> by_class = copy.classes();
        if (by_class != null) {
            List<Map.Entry<String, long[]>> top = new ArrayList<>();
            StringBuilder shared = new StringBuilder();
            for (Map.Entry<String, long[]> entry : by_class.entrySet()) {
                if (entry.getKey().startsWith("shared ")) {
                    shared.append(String.format(Locale.ROOT, "%n    %9d %s", entry.getValue()[0], entry.getKey()));
                } else {
                    top.add(entry);
                }
            }
            top.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
            System.out.println("    the largest classes copied:");
            for (Map.Entry<String, long[]> entry : top.subList(0, Math.min(30, top.size()))) {
                System.out.printf(Locale.ROOT, "    %9d objects %9s  %s%n", entry.getValue()[0],
                        mb(entry.getValue()[1]), entry.getKey());
            }
            System.out.println("    shared by a rule (immutable values left out):" + shared);
            System.out.println("    JDK classes copied:");
            for (Map.Entry<String, long[]> entry : top) {
                if (entry.getKey().startsWith("java.")) {
                    System.out.printf(Locale.ROOT, "    %9d %s%n", entry.getValue()[0], entry.getKey());
                }
            }
        }
    }

    // ---------------------------------------------------------------- the game and its recording

    /** What the game file says: census checksums by tick (every slot's line at a tick has the same), the end. */
    private static final class Recording {
        final @NonNull Map<Integer, Integer> census = new TreeMap<>();
        int inconsistent;
        int end_tick = -1;
        int end_checksum;

        static @NonNull Recording read(@NonNull Path file) {
            Game game = Game.file(file);
            Recording recording = new Recording();
            for (Map<String, Object> player : game.players()) {
                int slot = (int) Game.num(player, "s");
                for (Map<String, Object> census : game.census(slot)) {
                    int tick = (int) Math.round(Game.num(census, "t") * TPS);
                    int checksum = (int) Game.num(census, "checksum");
                    Integer before = recording.census.putIfAbsent(tick, checksum);
                    if (before != null && before != checksum && tick % CENSUS_TICKS == 0) {
                        recording.inconsistent++;
                    }
                }
            }
            for (Map<String, Object> end : game.events("end")) {
                recording.end_tick = (int) Math.round(Game.num(end, "t") * TPS);
                recording.end_checksum = (int) Game.num(end, "checksum");
            }
            return recording;
        }
    }

    /** Binds the AI log handles of every slot to {@code lines}, the viewer's way ({@code AiLog.beginWriters}). */
    private static @NonNull String beginAiLogs(@NonNull World world, @NonNull Writer lines, @NonNull String header) {
        AiLog.beginWriters(world, _ -> lines, header);
        return "AiLog.beginWriters";
    }

    /**
     * The AI log writer of every slot. The first pass keeps a hash and a count of the lines written at each tick
     * (the world's tick when written); a replay compares each tick's lines with them once the world has played the
     * next tick, from the tick after the restored one.
     */
    static final class AiLines extends Writer {
        private final @NonNull World world;
        private final @NonNull StringBuilder line = new StringBuilder();
        private long[] hashes = new long[1 << 16];
        private int[] counts = new int[1 << 16];
        private boolean replay;
        private int replay_from;
        private final @NonNull Map<Integer, long[]> pending = new TreeMap<>();
        long first_lines;
        long replayed_lines;
        int mismatches;
        int first_mismatch = -1;

        AiLines(@NonNull World world) {
            this.world = world;
        }

        @Override
        public void write(char @NonNull [] buffer, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                put(buffer[i]);
            }
        }

        @Override
        public void write(@NonNull String text, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                put(text.charAt(i));
            }
        }

        private void put(char c) {
            if (c != '\n') {
                line.append(c);
                return;
            }
            int tick = world.getTick();
            long hash = 1125899906842597L;
            for (int i = 0; i < line.length(); i++) {
                hash = 31 * hash + line.charAt(i);
            }
            line.setLength(0);
            if (!replay) {
                if (tick >= hashes.length) {
                    hashes = Arrays.copyOf(hashes, Math.max(tick + 1, hashes.length * 2));
                    counts = Arrays.copyOf(counts, hashes.length);
                }
                hashes[tick] = hashes[tick] * 1_000_003 + hash;
                counts[tick]++;
                first_lines++;
            } else {
                long[] tick_lines = pending.computeIfAbsent(tick, _ -> new long[2]);
                tick_lines[0] = tick_lines[0] * 1_000_003 + hash;
                tick_lines[1]++;
                replayed_lines++;
            }
        }

        void firstPassDone() {
            replay = true;
        }

        /** A replay starts after {@code restored_tick}: lines written at it or before are not compared. */
        void startReplay(int restored_tick) {
            replay_from = restored_tick;
            pending.clear();
            line.setLength(0);
        }

        /** The replay just played {@code tick}: the lines of the tick before it are complete. */
        void checkBefore(int tick) {
            int done = tick - 1;
            if (done <= replay_from) {
                return;
            }
            long[] replayed = pending.remove(done);
            long hash = replayed == null ? 0 : replayed[0];
            long count = replayed == null ? 0 : replayed[1];
            boolean known = done < hashes.length;
            if (hash != (known ? hashes[done] : 0) || count != (known ? counts[done] : 0)) {
                mismatches++;
                first_mismatch = first_mismatch < 0 ? done : first_mismatch;
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    // ---------------------------------------------------------------- small helpers

    private static final class Problems {
        final @NonNull List<String> list = new ArrayList<>();

        void add(@NonNull String problem) {
            list.add(problem);
        }
    }

    /** Early, at a quarter, half and three quarters, and a minute before the end. */
    private static @NonNull List<Integer> defaultPoints(int end_tick) {
        int end = end_tick / TPS;
        return List.of(0, end / 4, end / 2, end * 3 / 4, Math.max(0, end - 60));
    }

    private static long usedHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static @NonNull String ticksText(int @NonNull [] ticks) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < ticks.length; i++) {
            if (ticks.length > 12 && i == 6) {
                text.append(" ... (").append(ticks.length - 12).append(" more)");
                i = ticks.length - 6;
            }
            text.append(text.isEmpty() ? "" : " ").append(clock(ticks[i]));
        }
        return text.toString();
    }

    private static @NonNull String ok(boolean ok) {
        return ok ? "ok" : "BAD";
    }

    private static @NonNull String clock(int tick) {
        int s = tick / TPS;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(
                Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static @NonNull String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
