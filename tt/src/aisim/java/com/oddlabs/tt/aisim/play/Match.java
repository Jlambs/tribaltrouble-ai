package com.oddlabs.tt.aisim.play;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.aikit.harness.AiSpec;
import com.oddlabs.tt.aikit.harness.Census.Field;
import com.oddlabs.tt.aikit.harness.GameRecorder;
import com.oddlabs.tt.aisim.analysis.End;
import com.oddlabs.tt.aisim.build.Pool;
import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * Plays one game headlessly, exactly as the client simulates it: the {@link ClientWorld}, AIs created in slot order,
 * then {@code world.tick(0.02f)} ({@link AnimationManager#ANIMATION_SECONDS_PER_TICK}) in a loop, which is what the
 * client does each lockstep tick minus human commands. Then it builds the game's result row.
 *
 * <p>Everything is from A's point of view: team A is A and its allies, team B every other player, which in a game of
 * three teams or more is several teams.
 *
 * <p>One game at a time per JVM, always on the thread that called {@link #boot()}: the simulation keeps static
 * scratch buffers and the GL context belongs to that thread.
 */
final class Match {
    /** A player with at most this many units and no chieftain or real building is collapsing, see collapsing(). */
    private static final int COLLAPSE_UNITS = 8;
    /** Collapsing this long in a row puts a player out, when the job allows collapse. */
    private static final int COLLAPSE_SECONDS = 60;
    /** The tick of the w15 milestone: A's warriors at 15:00. */
    private static final int W15_TICK = 15 * 60 * GameTime.TICKS_PER_SECOND;
    /** The tick of the kd30 milestone: A's kills minus B's at 30:00. */
    private static final int KD30_TICK = 30 * 60 * GameTime.TICKS_PER_SECOND;
    private static final boolean TEAM_A = true;
    private static final boolean TEAM_B = false;

    /**
     * How many identity hashes boot() draws on the simulation thread before the first game (pid % 1000). The thread's
     * identity-hash sequence then starts somewhere else in every worker JVM, so an AI whose play depends on
     * identity-hash order (HashMap/HashSet of engine objects) plays differently on replay and shows up as a MISMATCH.
     */
    private static int perturb;
    /** Written so the JIT cannot drop the hashing loop as dead code. */
    private static volatile int hash_sink;
    /** Ticks played in the current game, for the worker's hang watchdog. */
    static volatile int progress;

    private Match() {
    }

    /** Readies the calling thread for games: the client's resources and GL context, then the hash offset. */
    static void boot() {
        ClientWorld.boot();
        perturb = (int) (ProcessHandle.current().pid() % 1000);
        int sink = 0;
        for (int i = 0; i < perturb; i++) {
            sink += new Object().hashCode();
        }
        hash_sink = sink;
    }

    /** Plays {@code job} to the end and returns its result row. Only harness failures throw. */
    static @NonNull Map<String, Object> run(@NonNull Job job, @NonNull String snapshot) throws IOException {
        long wall_start = System.nanoTime();
        long cpu_start = threadCpuNanos();
        progress = 0;
        Map<String, Object> row;
        try (ClientWorld client = ClientWorld.create(job)) {
            World world = client.world();
            if (job.rng() != null) {
                world.getRandom().setSeed(job.rng() * 1000 + job.side()); // before anything draws from it
            }
            // registered before the AIs, as in the GUI, so each tick the recorder samples before any AI acts
            GameRecorder recorder = startRecorder(job, snapshot, world);
            // before the AIs, which fetch their log handles when created
            AiLog.begin(world, aiLogFiles(job), "key=" + job.key() + " snap=" + snapshot);
            List<URLClassLoader> loaders = new ArrayList<>();
            try {
                Outcome outcome = new Outcome();
                try {
                    createAIs(job, world, outcome, loaders);
                    if (outcome.end == null) {
                        play(job, world, recorder, outcome);
                    }
                } catch (LinkageError e) {
                    outcome.fail(End.error, "link error (compiled against an engine with other methods?): ", e, job);
                } catch (RuntimeException | Error e) {
                    outcome.fail(End.crash, "", e, job);
                }
                recorder.finish(endMembers(outcome));
                row = finalRow(job, snapshot, world, recorder, outcome); // before AiLog.end(): it reads the AI counters
            } finally {
                AiLog.end();
                for (URLClassLoader loader : loaders) {
                    loader.close();
                }
            }
        }
        row.put("wall", seconds(System.nanoTime() - wall_start));
        // CPU seconds of the simulation thread: the cost of the game itself, unlike wall time not slowed by the load
        row.put("cpu", seconds(threadCpuNanos() - cpu_start));
        return row;
    }

    // ---------------------------------------------------------------- the AIs

    /** Creates the AIs in slot order, as the game does; an AI that fails to start fails the game with an error. */
    private static void createAIs(@NonNull Job job, @NonNull World world, @NonNull Outcome outcome,
            @NonNull List<URLClassLoader> loaders) {
        // as Client creates it
        UnitInfo unit_info = new UnitInfo(false, false, 0, false, ClientWorld.STARTING_UNITS, 0, 0, 0);
        for (int slot = 0; slot < job.slots() && outcome.end == null; slot++) {
            Player player = world.getPlayers()[slot];
            String failed_start = "ai_init: slot " + slot + " " + job.spec(slot) + ": ";
            try {
                player.setAI(createAI(job.spec(slot), player, unit_info, loaders));
            } catch (ExceptionInInitializerError e) {
                // a LinkageError too, so it is caught first: a failing static initializer is the AI's own error
                Throwable cause = e.getCause() == null ? e : e.getCause();
                outcome.fail(End.error, failed_start + "static initializer failed: ", cause, job);
            } catch (LinkageError e) {
                throw e; // an engine mismatch, not the AI's error: run() reports it as a link error
            } catch (RuntimeException | Error e) {
                outcome.fail(End.error, failed_start, e, job);
            }
        }
    }

    /** The AI of {@code spec}; a frozen AI gets a class loader of its own, which run() closes after the game. */
    private static @NonNull AI createAI(@NonNull String spec, @NonNull Player player, @NonNull UnitInfo unit_info,
            @NonNull List<URLClassLoader> loaders) {
        String name = AiSpec.nameOf(spec);
        if (!name.startsWith("@")) {
            return AiSpec.create(spec, player, unit_info);
        }
        Pool pool = Pool.of(name.substring(1));
        URLClassLoader loader = pool.newLoader();
        loaders.add(loader);
        try {
            return AiSpec.create(Class.forName(pool.entry(), true, loader), AiSpec.paramsOf(spec), player, unit_info);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("pool " + name + " has no class " + pool.entry(), e);
        }
    }

    // ---------------------------------------------------------------- playing and the end rules

    /** Ticks the world until one team is out or the time is up, taking the milestones on the way. */
    private static void play(@NonNull Job job, @NonNull World world, @NonNull GameRecorder recorder,
            @NonNull Outcome outcome) {
        Player[] players = world.getPlayers();
        int[] collapse_seconds = new int[players.length];
        boolean[] is_out = new boolean[players.length];
        int last_tick = job.minutes() * 60 * GameTime.TICKS_PER_SECOND;
        while (true) {
            world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
            int tick = world.getTick();
            progress = tick;
            if (tick == W15_TICK) {
                outcome.w15 = warriors(teamCensus(job, recorder, TEAM_A));
            } else if (tick == KD30_TICK) {
                outcome.kd30 = killsOfAMinusB(job, recorder);
            }
            if (tick % GameTime.TICKS_PER_SECOND != 0) {
                continue; // everything below happens once per game second
            }
            boolean collapsed = updatePlayersOut(job, players, recorder, collapse_seconds, is_out);
            boolean a_standing = standing(job, is_out, TEAM_A);
            boolean b_standing = standing(job, is_out, TEAM_B);
            if (!a_standing || !b_standing) {
                outcome.eliminated(a_standing, b_standing, collapsed);
                return;
            }
            if (tick >= last_tick) {
                double a = Field.strength.of(teamCensus(job, recorder, TEAM_A));
                outcome.timedOut(a, strongestOpponent(job, recorder));
                return;
            }
        }
    }

    /**
     * One game second for every player not yet out: advances their collapse clock (the game file gets a collapse
     * event when it reaches {@value #COLLAPSE_SECONDS} s) and marks them out once the engine has them dead or, when
     * the job allows collapse, once they have collapsed that long. Returns whether a collapse put a living player out.
     */
    private static boolean updatePlayersOut(@NonNull Job job, Player @NonNull [] players,
            @NonNull GameRecorder recorder, int @NonNull [] collapse_seconds, boolean @NonNull [] is_out) {
        boolean collapsed = false;
        for (int slot = 0; slot < players.length; slot++) {
            if (!is_out[slot]) {
                collapse_seconds[slot] = collapsing(players[slot]) ? collapse_seconds[slot] + 1 : 0;
                if (collapse_seconds[slot] == COLLAPSE_SECONDS) {
                    recorder.event("collapse", slot, "");
                }
                boolean gone = job.collapse() && collapse_seconds[slot] >= COLLAPSE_SECONDS;
                collapsed |= gone && players[slot].isAlive();
                is_out[slot] = gone || !players[slot].isAlive();
            }
        }
        return collapsed;
    }

    /**
     * A player is collapsing while they have at most {@value #COLLAPSE_UNITS} units, no chieftain and no land building
     * other than a tower (quarters, armory, built or under construction): a lost game the engine's own rule (any unit
     * left) would drag on to the time limit.
     */
    private static boolean collapsing(@NonNull Player player) {
        if (player.getUnitCountContainer().getNumSupplies() > COLLAPSE_UNITS || player.hasActiveChieftain()) {
            return false;
        }
        for (Selectable<?> s : player.getUnits().getSet()) {
            if (s instanceof LandBuilding building && building.getTemplate().getTemplateID() != Race.BUILDING_TOWER) {
                return false;
            }
        }
        return true;
    }

    /** Whether a player of the team is not out yet. */
    private static boolean standing(@NonNull Job job, boolean @NonNull [] is_out, boolean team_a) {
        for (int slot = 0; slot < is_out.length; slot++) {
            if (onTeam(job, slot, team_a) && !is_out[slot]) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code slot} plays for team A ({@code team_a}) or for team B (not {@code team_a}). */
    private static boolean onTeam(@NonNull Job job, int slot, boolean team_a) {
        return job.onTeamA(slot) == team_a;
    }

    /** The strength of the strongest team against A: team B's, or in a game of three teams or more, its best one's. */
    private static double strongestOpponent(@NonNull Job job, @NonNull GameRecorder recorder) {
        Map<Integer, Double> strength_by_team = new TreeMap<>();
        for (int slot = 0; slot < job.slots(); slot++) {
            if (!job.onTeamA(slot)) {
                double strength = Field.strength.of(recorder.census(slot));
                strength_by_team.merge(job.team(slot), strength, Double::sum);
            }
        }
        return strength_by_team.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    /** How the game went for A's team; {@code end} is never hang (hang rows come from the worker's watchdog). */
    private static final class Outcome {
        /** Stack frames the row's problem text shows; the .err file has the full stack. */
        private static final int PROBLEM_FRAMES = 3;

        @Nullable
        End end;
        /** How an elimination happened: collapse or engine; null for every other end. */
        @Nullable
        String via;
        /** a, b or draw; null when the game does not count. */
        @Nullable
        String winner;
        /** The exception text of a crash or error row. */
        @Nullable
        String problem;
        /** The timeout margin, or 1/-1/0 on elimination. */
        double margin;
        /** A's kills minus B's at 30:00; null when the game ended earlier. */
        @Nullable
        Integer kd30;
        /** A's warriors at 15:00; null when the game ended earlier. */
        @Nullable
        Integer w15;

        /** A team has nobody standing: the other one wins, or it is a draw when neither has anyone left. */
        void eliminated(boolean a_standing, boolean b_standing, boolean collapsed) {
            end = End.elim;
            via = collapsed ? "collapse" : "engine";
            if (a_standing) {
                winner = "a";
                margin = 1;
            } else if (b_standing) {
                winner = "b";
                margin = -1;
            } else {
                winner = "draw";
                margin = 0;
            }
        }

        /**
         * The time limit: a draw, whoever is ahead, so that only beating every opponent wins. The margin, (A - B) /
         * (A + B) of the strength of A's team and of its strongest enemy, still records who was ahead.
         */
        void timedOut(double a_strength, double b_strength) {
            end = End.timeout;
            winner = "draw";
            if (a_strength + b_strength == 0) {
                margin = 0;
            } else {
                margin = Math.round((a_strength - b_strength) / (a_strength + b_strength) * 1000) / 1000.0;
            }
        }

        /** A game that does not count: the exception and its top frames in the row, the full stack in .err. */
        void fail(@NonNull End end_reason, @NonNull String prefix, @NonNull Throwable t, @NonNull Job job) {
            end = end_reason;
            StringBuilder text = new StringBuilder(prefix).append(t);
            StackTraceElement[] stack = t.getStackTrace();
            for (int i = 0; i < Math.min(PROBLEM_FRAMES, stack.length); i++) {
                text.append(" @ ").append(stack[i]);
            }
            problem = text.toString();
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(job.errFile()),
                    StandardCharsets.UTF_8))) {
                t.printStackTrace(out);
            } catch (IOException ignored) {
                // the row still carries the exception
            }
        }

        /** A's score: 1.0 for a win, 0.0 for a loss, 0.5 for a draw; null when the game does not count. */
        @Nullable
        Double score() {
            if (winner == null) {
                return null;
            }
            return switch (winner) {
                case "a" -> 1.0;
                case "b" -> 0.0;
                default -> 0.5;
            };
        }

        /** 1 when A eliminated B, -1 when B eliminated A, 0 for any other game that counts; null otherwise. */
        @Nullable
        Integer elimination() {
            if (winner == null) {
                return null;
            }
            if (end != End.elim || winner.equals("draw")) {
                return 0;
            }
            return winner.equals("a") ? 1 : -1;
        }
    }

    // ---------------------------------------------------------------- the teams' census

    /**
     * The census of team A or team B, summed over its slots. armyX/armyY are the warrior-weighted centre of the team's
     * armies (-1 without warriors), a weighted mean rather than a sum.
     */
    private static int @NonNull [] teamCensus(@NonNull Job job, @NonNull GameRecorder recorder, boolean team_a) {
        int[] sum = new int[Field.values().length];
        long sum_x = 0;
        long sum_y = 0;
        long warriors = 0;
        for (int slot = 0; slot < job.slots(); slot++) {
            if (onTeam(job, slot, team_a)) {
                int[] census = recorder.census(slot);
                for (int i = 0; i < sum.length; i++) {
                    sum[i] += census[i];
                }
                int in_field = fieldWarriors(census);
                sum_x += (long) in_field * Field.armyX.of(census);
                sum_y += (long) in_field * Field.armyY.of(census);
                warriors += in_field;
            }
        }
        sum[Field.armyX.ordinal()] = warriors == 0 ? -1 : (int) (sum_x / warriors);
        sum[Field.armyY.ordinal()] = warriors == 0 ? -1 : (int) (sum_y / warriors);
        return sum;
    }

    /** The kd30 metric's value now: A's kills minus B's. */
    private static int killsOfAMinusB(@NonNull Job job, @NonNull GameRecorder recorder) {
        return Field.kills.of(teamCensus(job, recorder, TEAM_A)) - Field.kills.of(teamCensus(job, recorder, TEAM_B));
    }

    /** The warriors of a census, tower garrisons included, as the reports count them. */
    private static int warriors(int @NonNull [] census) {
        return fieldWarriors(census) + Field.garrison.of(census);
    }

    /** The warriors of a census that can be out in the field (no garrisons): the weight of the army position. */
    private static int fieldWarriors(int @NonNull [] census) {
        return Field.rock.of(census) + Field.iron.of(census) + Field.rubber.of(census);
    }

    // ---------------------------------------------------------------- the game file and the AI logs

    /** Creates the game file's folder and starts recording {@code world} into it, one label per slot. */
    private static @NonNull GameRecorder startRecorder(@NonNull Job job, @NonNull String snapshot,
            @NonNull World world) throws IOException {
        Path game_file = Path.of(job.game());
        Files.createDirectories(game_file.getParent());
        String[] labels = new String[job.slots()];
        for (int slot = 0; slot < labels.length; slot++) {
            labels[slot] = job.spec(slot);
        }
        BufferedWriter out = Files.newBufferedWriter(game_file, StandardCharsets.UTF_8);
        return GameRecorder.start(world, out, header(job, snapshot), labels);
    }

    /**
     * The game file's header members. Only run, a and teams go through GameRecorder.quote; the rest are written raw.
     * Not
     * built with Aisim.JSON, which escapes non-ASCII characters where GameRecorder.quote does not.
     */
    private static @NonNull String header(@NonNull Job job, @NonNull String snapshot) {
        StringBuilder text = new StringBuilder("\"source\":\"aisim\"");
        text.append(",\"run\":").append(GameRecorder.quote(job.run()));
        text.append(",\"key\":\"").append(job.key()).append('"');
        text.append(",\"a\":").append(GameRecorder.quote(job.spec(job.side())));
        text.append(",\"teams\":").append(GameRecorder.quote(job.teams()));
        text.append(",\"map\":\"").append(job.map()).append('"');
        text.append(",\"seed\":").append(job.seed());
        text.append(",\"mapcode\":\"").append(job.mapcode()).append('"');
        text.append(",\"rng\":").append(job.rng());
        text.append(",\"snap\":\"").append(snapshot).append('"');
        return text.toString();
    }

    /** Each slot's AI log file, or null when the job keeps no AI logs. */
    private static @Nullable IntFunction<Path> aiLogFiles(@NonNull Job job) {
        String logs = job.logs();
        if (logs == null) {
            return null;
        }
        return slot -> Path.of(logs + "-ai-s" + slot + ".log");
    }

    /** The game file's end members: how the game ended, how an elimination happened, and who won. */
    private static @NonNull String endMembers(@NonNull Outcome outcome) {
        String via = jsonOrNull(outcome.via);
        String winner = jsonOrNull(outcome.winner);
        return "\"end\":\"" + outcome.end + "\",\"via\":" + via + ",\"winner\":" + winner;
    }

    /** {@code s} as a JSON string, or null; only for the fixed tokens of via and winner, which need no escaping. */
    private static @NonNull String jsonOrNull(@Nullable String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    // ---------------------------------------------------------------- the result row

    /**
     * The game's row. When the game crashed, reading its world for the row may fail too (the crash can leave it half
     * updated); then the row keeps the crash and leaves out the teams' final blocks.
     */
    private static @NonNull Map<String, Object> finalRow(@NonNull Job job, @NonNull String snapshot,
            @NonNull World world, @NonNull GameRecorder recorder, @NonNull Outcome outcome) {
        try {
            return row(job, snapshot, world, recorder, outcome);
        } catch (RuntimeException | Error e) {
            if (outcome.end == null || outcome.end.normal() || outcome.problem == null) {
                throw e; // a harness bug: WorkerMain reports it
            }
            return errorRow(job, snapshot, outcome.end, outcome.problem, gameSeconds(world));
        }
    }

    /**
     * A played game's row: {@link #baseRow}, how the game ended, A's scores (score, elim, kd30, w15 and margin, null
     * when the game does not count), each team's final block, then the recorder's health, the problem and the replay
     * command.
     */
    private static @NonNull Map<String, Object> row(@NonNull Job job, @NonNull String snapshot, @NonNull World world,
            @NonNull GameRecorder recorder, @NonNull Outcome outcome) {
        boolean counts = outcome.winner != null;
        int[] a = teamCensus(job, recorder, TEAM_A);
        int[] b = teamCensus(job, recorder, TEAM_B);
        // a game that ended before a milestone takes it from the final census
        int kd30 = outcome.kd30 != null ? outcome.kd30 : Field.kills.of(a) - Field.kills.of(b);
        int w15 = outcome.w15 != null ? outcome.w15 : warriors(a);
        Map<String, Object> row = baseRow(job, snapshot);
        row.put("end", outcome.end == null ? null : outcome.end.name());
        row.put("via", outcome.via);
        row.put("winner", outcome.winner);
        row.put("t", gameSeconds(world));
        row.put("checksum", world.getChecksum());
        row.put("score", outcome.score());
        row.put("elim", outcome.elimination());
        row.put("kd30", counts ? kd30 : null);
        row.put("w15", counts ? w15 : null);
        row.put("margin", counts ? outcome.margin : null);
        row.put("A", teamBlock(job, world, a, TEAM_A));
        row.put("B", teamBlock(job, world, b, TEAM_B));
        row.put("recorderFailed", recorder.hasFailed());
        row.put("problem", outcome.problem);
        row.put("replay", replayCommand(job));
        return row;
    }

    /** The row of a game that did not count and has no result of its own (hang, dead worker, harness error). */
    static @NonNull Map<String, Object> errorRow(@NonNull Job job, @NonNull String snapshot, @NonNull End end,
            @NonNull String problem, double t) {
        Map<String, Object> row = baseRow(job, snapshot);
        row.put("end", end.name());
        row.put("winner", null);
        row.put("t", t);
        row.put("problem", problem);
        row.put("replay", replayCommand(job));
        return row;
    }

    /** The fields every result row starts with, whatever happened. */
    private static @NonNull Map<String, Object> baseRow(@NonNull Job job, @NonNull String snapshot) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", 1);
        row.put("run", job.run());
        row.put("key", job.key());
        row.put("seed", job.seed());
        row.put("side", job.side());
        row.put("slots", job.slots());
        row.put("teams", job.teams());
        row.put("a", job.spec(job.side()));
        row.put("map", job.map());
        row.put("mapcode", job.mapcode());
        row.put("minutes", job.minutes());
        row.put("rng", job.rng());
        row.put("collapse", job.collapse());
        row.put("snap", snapshot);
        row.put("perturb", perturb);
        return row;
    }

    /** A team's final census plus its AIs' log counters summed over its slots, and its first AI error in slot order. */
    private static @NonNull Map<String, Object> teamBlock(@NonNull Job job, @NonNull World world,
            int @NonNull [] census, boolean team_a) {
        Map<String, Object> block = new LinkedHashMap<>();
        for (Field field : Field.values()) {
            block.put(field.name(), field.of(census));
        }
        SortedMap<String, Integer> counters = new TreeMap<>();
        String first_error = null;
        for (int slot = 0; slot < job.slots(); slot++) {
            if (!onTeam(job, slot, team_a)) {
                continue;
            }
            AiLog log = AiLog.peek(world, slot);
            if (log != null) {
                log.counters().forEach((key, n) -> counters.merge(key, n, Integer::sum));
                if (first_error == null) {
                    first_error = log.firstError();
                }
            }
        }
        block.put("aiError", first_error);
        block.put("counters", counters);
        return block;
    }

    private static @NonNull String replayCommand(@NonNull Job job) {
        return "./aisim.sh replay " + job.run() + " " + job.key();
    }

    // ---------------------------------------------------------------- time

    /** The game time in seconds, rounded to tenths. */
    private static double gameSeconds(@NonNull World world) {
        return Math.round(GameTime.seconds(world) * 10) / 10.0;
    }

    /** Nanoseconds as seconds, rounded to tenths. */
    private static double seconds(long nanos) {
        return Math.round(nanos / 1e8) / 10.0;
    }

    private static long threadCpuNanos() {
        return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }
}
