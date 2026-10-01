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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * Plays one game headlessly, exactly as the client simulates it: the {@link ClientWorld}, AIs created in slot order,
 * then {@code world.tick(0.02f)} ({@link AnimationManager#ANIMATION_SECONDS_PER_TICK}) in a loop, which is what the
 * client does each lockstep tick minus human commands. The game goes on until one team is left (or none), until
 * team A is out when the job says so, or to the time limit; then it builds the game's result row, with every team's
 * place and team A's measures.
 *
 * <p>One game at a time per JVM, always on the thread that called {@link #boot()}: the simulation keeps static
 * scratch buffers, and without {@link com.oddlabs.tt.global.Headless} the GL context belongs to that thread.
 */
final class Match {
    /** A player with at most this many units and no chieftain or real building is collapsing, see collapsing(). */
    private static final int COLLAPSE_UNITS = 8;
    /** Collapsing this many game seconds in a row puts a player out, when the job allows collapse. */
    private static final int COLLAPSE_SECONDS = 60;
    /** The game time of the w15 milestone, in ms: team A's warriors at 15:00. */
    private static final long W15_MILLIS = 15 * 60_000L;
    /** The game time of the kd30 milestone, in ms: team A's kills minus its losses at 30:00. */
    private static final long KD30_MILLIS = 30 * 60_000L;

    /**
     * How many identity hashes boot() draws on the simulation thread before the first game (pid % 1000). The thread's
     * identity-hash sequence then starts somewhere else in every worker JVM, so an AI whose play depends on
     * identity-hash order (HashMap/HashSet of engine objects) plays differently on replay and shows up as a MISMATCH.
     */
    private static int perturb;
    /** Written so the JIT cannot drop the hashing loop as dead code. */
    private static volatile int hash_sink;
    /**
     * One class loader per frozen AI tag, which every slot and game of this worker JVM shares, as they share the
     * classes of the build's own AIs. Loading a frozen AI afresh for each slot of each game made the JIT compile its
     * code again every time, which cost about as much CPU as the games. Like any AI, a frozen one must keep no state
     * in statics (the AI guide's rule); one that does plays its later games in a worker differently from its replay,
     * and replay reports the MISMATCH.
     */
    private static final Map<String, URLClassLoader> frozen_loaders = new HashMap<>();
    /** Ticks played in the current game, for the worker's hang watchdog. */
    static volatile int progress;
    /** The game time of {@link #progress}, in ms. */
    static volatile long progress_millis;

    private Match() {
    }

    /** Readies the calling thread for games: the resources ({@link ClientWorld#boot()}), then the hash offset. */
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
        progress_millis = 0;
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
            try {
                Outcome outcome = new Outcome();
                try {
                    createAIs(job, world, outcome);
                    if (outcome.end == null) {
                        // Generating the map leaves garbage in the old generation, which the serial collector grows
                        // rather than collects, game after game, up to -Xmx. One full collection here (tens of
                        // milliseconds) shrinks the heap back to the world instead.
                        System.gc();
                        play(job, world, recorder, outcome);
                    }
                } catch (LinkageError e) {
                    outcome.fail(End.error, "link error (compiled against an engine with other methods?): ", e, job);
                } catch (RuntimeException | Error e) {
                    outcome.fail(End.crash, "", e, job);
                }
                recorder.finish(endMembers(job, outcome));
                row = finalRow(job, snapshot, world, recorder, outcome); // before AiLog.end(): it reads the AI counters
            } finally {
                AiLog.end();
            }
        }
        row.put("wall", seconds(System.nanoTime() - wall_start));
        // CPU seconds of the simulation thread: the cost of the game itself, unlike wall time not slowed by the load
        row.put("cpu", seconds(threadCpuNanos() - cpu_start));
        return row;
    }

    // ---------------------------------------------------------------- the AIs

    /** Creates the AIs in slot order, as the game does; an AI that fails to start fails the game with an error. */
    private static void createAIs(@NonNull Job job, @NonNull World world, @NonNull Outcome outcome) {
        // as Client creates it
        UnitInfo unit_info = new UnitInfo(false, false, 0, false, ClientWorld.STARTING_UNITS, 0, 0, 0);
        for (int slot = 0; slot < job.slots() && outcome.end == null; slot++) {
            Player player = world.getPlayers()[slot];
            String failed_start = "ai_init: slot " + slot + " " + job.spec(slot) + ": ";
            try {
                player.setAI(createAI(job.spec(slot), player, unit_info));
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

    /** The AI of {@code spec}; a frozen AI comes from its tag's class loader ({@link #frozen_loaders}). */
    private static @NonNull AI createAI(@NonNull String spec, @NonNull Player player,
            @NonNull UnitInfo unit_info) {
        String name = AiSpec.nameOf(spec);
        if (!name.startsWith("@")) {
            return AiSpec.create(spec, player, unit_info);
        }
        Pool pool = Pool.of(name.substring(1));
        URLClassLoader loader = frozen_loaders.computeIfAbsent(pool.tag(), _ -> pool.newLoader());
        try {
            return AiSpec.create(Class.forName(pool.entry(), true, loader), AiSpec.paramsOf(spec), player, unit_info);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("pool " + name + " has no class " + pool.entry(), e);
        }
    }

    // ---------------------------------------------------------------- playing and the end rules

    /**
     * Ticks the world until at most one team is standing or the time is up, taking the milestones on the way. A team
     * that goes out gets a team_out event, and the others play on without it. Every rule counts game time, whatever
     * the game speed: the end rules run once a game second ({@link GameTime.Every}), on every 50th tick at normal
     * speed, and the time limit and the milestones are in game minutes.
     */
    private static void play(@NonNull Job job, @NonNull World world, @NonNull GameRecorder recorder,
            @NonNull Outcome outcome) {
        Player[] players = world.getPlayers();
        int[] collapse_seconds = new int[players.length];
        boolean[] is_out = new boolean[players.length];
        boolean[] collapsed = new boolean[players.length];
        long limit_millis = job.minutes() * 60_000L;
        GameTime.Every every_second = new GameTime.Every(1000);
        while (true) {
            world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
            progress = world.getTick();
            progress_millis = world.getGameMillis();
            if (outcome.w15 == null && GameTime.reached(world, W15_MILLIS)) {
                outcome.w15 = warriors(teamCensus(job, recorder, job.team(job.side())));
            } else if (outcome.kd30 == null && GameTime.reached(world, KD30_MILLIS)) {
                outcome.kd30 = killsMinusLosses(teamCensus(job, recorder, job.team(job.side())));
            }
            if (!every_second.due(world)) {
                continue; // everything below happens once per game second
            }
            updatePlayersOut(job, players, recorder, collapse_seconds, is_out, collapsed);
            double t = every_second.struck() / 1000.0; // the whole game second this strike is for
            int standing = 0;
            for (int team = 0; team < job.teamCount(); team++) {
                if (outcome.isOut(team)) {
                    continue;
                }
                if (standing(job, is_out, team)) {
                    standing++;
                } else {
                    String via = collapsedTeam(job, collapsed, team) ? "collapse" : "engine";
                    outcome.teamOut(team, t, via);
                    recorder.event("team_out", "\"team\":" + team + ",\"via\":\"" + via + "\"");
                }
            }
            if (standing <= 1 || (job.stopWhenAOut() && outcome.isOut(job.team(job.side())))) {
                outcome.end = End.elim; // with stopWhenAOut, the teams still standing share first place
                return;
            }
            if (every_second.struck() >= limit_millis) {
                outcome.timedOut(job, recorder);
                return;
            }
        }
    }

    /**
     * One game second for every player not yet out: advances their collapse clock (the game file gets a collapse
     * event when it reaches {@value #COLLAPSE_SECONDS} s) and marks them out once the engine has them dead or, when
     * the job allows collapse, once they have collapsed that long. {@code collapsed} marks the living players the
     * collapse rule put out this second.
     */
    private static void updatePlayersOut(@NonNull Job job, Player @NonNull [] players, @NonNull GameRecorder recorder,
            int @NonNull [] collapse_seconds, boolean @NonNull [] is_out, boolean @NonNull [] collapsed) {
        for (int slot = 0; slot < players.length; slot++) {
            collapsed[slot] = false;
            if (!is_out[slot]) {
                collapse_seconds[slot] = collapsing(players[slot]) ? collapse_seconds[slot] + 1 : 0;
                if (collapse_seconds[slot] == COLLAPSE_SECONDS) {
                    recorder.event("collapse", slot, "");
                }
                boolean gone = job.collapse() && collapse_seconds[slot] >= COLLAPSE_SECONDS;
                collapsed[slot] = gone && players[slot].isAlive();
                is_out[slot] = gone || !players[slot].isAlive();
            }
        }
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

    /** Whether a player of {@code team} is not out yet. */
    private static boolean standing(@NonNull Job job, boolean @NonNull [] is_out, int team) {
        for (int slot = 0; slot < is_out.length; slot++) {
            if (job.team(slot) == team && !is_out[slot]) {
                return true;
            }
        }
        return false;
    }

    /** Whether the collapse rule put a living player of {@code team} out this second. */
    private static boolean collapsedTeam(@NonNull Job job, boolean @NonNull [] collapsed, int team) {
        for (int slot = 0; slot < collapsed.length; slot++) {
            if (job.team(slot) == team && collapsed[slot]) {
                return true;
            }
        }
        return false;
    }

    /**
     * How the game went, for every team: when each went out and how, which gives the places. A team's place is 1 plus
     * the number of teams that outlasted it; teams that went out in the same second, or were standing at the time
     * limit, share their places (two teams sharing first both have 1.5). {@code end} is never hang (hang rows come
     * from the worker's watchdog).
     */
    private static final class Outcome {
        /** Stack frames the row's problem text shows; the .err file has the full stack. */
        private static final int PROBLEM_FRAMES = 3;

        @Nullable
        End end;
        /** The exception text of a crash or error row. */
        @Nullable
        String problem;
        /** The kd30 metric at 30:00; null when the game ended earlier. */
        @Nullable
        Integer kd30;
        /** The w15 metric at 15:00; null when the game ended earlier. */
        @Nullable
        Integer w15;
        /** The game second each team went out, by team; missing while it stands. */
        private final @NonNull Map<Integer, Double> out_at = new TreeMap<>();
        /** How each team that went out did: engine or collapse. */
        private final @NonNull Map<Integer, String> out_via = new TreeMap<>();
        /** At the time limit, team A's strength margin over its strongest standing enemy. */
        private double timeout_margin;

        boolean isOut(int team) {
            return out_at.containsKey(team);
        }

        void teamOut(int team, double t, @NonNull String via) {
            out_at.put(team, t);
            out_via.put(team, via);
        }

        /**
         * The time limit: every team still standing shares first place, whoever is ahead, so that only beating every
         * opponent wins. The margin, (A - B) / (A + B) of the strength of team A and of its strongest standing
         * enemy, still records who was ahead.
         */
        void timedOut(@NonNull Job job, @NonNull GameRecorder recorder) {
            end = End.timeout;
            int team_a = job.team(job.side());
            double a = Field.strength.of(teamCensus(job, recorder, team_a));
            double b = 0;
            for (int team = 0; team < job.teamCount(); team++) {
                if (team != team_a && !isOut(team)) {
                    b = Math.max(b, Field.strength.of(teamCensus(job, recorder, team)));
                }
            }
            timeout_margin = a + b == 0 ? 0 : Math.round((a - b) / (a + b) * 1000) / 1000.0;
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

        /** Whether the game played to its end (elimination or time limit), so it has places and counts. */
        boolean counts() {
            return end != null && end.normal();
        }

        /** When {@code team} went out, as a number that grows with how long it lasted; standing teams last forever. */
        private double lasted(int team) {
            return out_at.getOrDefault(team, Double.POSITIVE_INFINITY);
        }

        /** The place of {@code team} of {@code teams}: 1 plus the teams that outlasted it, shared ties averaged. */
        double place(int team, int teams) {
            int better = 0;
            int same = 0;
            for (int other = 0; other < teams; other++) {
                if (lasted(other) > lasted(team)) {
                    better++;
                } else if (lasted(other) == lasted(team)) {
                    same++; // team itself among them
                }
            }
            return better + (same + 1) / 2.0;
        }

        /** The one team that outlasted every other; null when first place is shared. */
        @Nullable
        Integer winnerTeam(int teams) {
            for (int team = 0; team < teams; team++) {
                if (place(team, teams) == 1) {
                    return team;
                }
            }
            return null;
        }

        /** A team's result: win (first place alone), draw (first place shared) or loss. */
        @NonNull
        String result(int team, int teams) {
            double place = place(team, teams);
            if (place == 1) {
                return "win";
            }
            int best = 0;
            for (int other = 0; other < teams; other++) {
                best = lasted(other) > lasted(best) ? other : best;
            }
            return lasted(team) == lasted(best) ? "draw" : "loss";
        }

        /** A team's score, its place scaled so that first is 1 and last 0: 1 / 0.5 / 0 for win / draw / loss of two. */
        double score(int team, int teams) {
            return (teams - place(team, teams)) / (teams - 1);
        }

        /** 1 when the team won by eliminating every other, -1 when it was eliminated and lost, else 0. */
        int elim(int team, int teams) {
            String result = result(team, teams);
            if (result.equals("win") && end == End.elim) {
                return 1;
            }
            return result.equals("loss") && isOut(team) ? -1 : 0;
        }

        /** A team's margin: 1 for a win, -1 for a loss, 0 for a shared elimination, the strength margin at a draw. */
        double margin(int team, int teams) {
            return switch (result(team, teams)) {
                case "win" -> 1;
                case "loss" -> -1;
                default -> end == End.timeout ? timeout_margin : 0;
            };
        }

        /** How the last teams to go out went: collapse or engine; null unless the game ended by elimination. */
        @Nullable
        String via() {
            if (end != End.elim) {
                return null;
            }
            double last = out_at.values().stream().mapToDouble(Double::doubleValue).max().orElse(-1);
            boolean collapse = out_at.entrySet().stream().anyMatch(e -> e.getValue() == last && "collapse".equals(
                    out_via.get(e.getKey())));
            return collapse ? "collapse" : "engine";
        }
    }

    // ---------------------------------------------------------------- the teams' census

    /**
     * The census of {@code team}, summed over its slots. armyX/armyY are the warrior-weighted centre of the team's
     * armies (-1 without warriors), a weighted mean rather than a sum.
     */
    private static int @NonNull [] teamCensus(@NonNull Job job, @NonNull GameRecorder recorder, int team) {
        int[] sum = new int[Field.values().length];
        long sum_x = 0;
        long sum_y = 0;
        long warriors = 0;
        for (int slot = 0; slot < job.slots(); slot++) {
            if (job.team(slot) == team) {
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

    /**
     * The kd30 metric's value: a team's kills minus its losses. Units killed by their own side or an ally count in
     * both, so friendly fire cancels out.
     */
    private static int killsMinusLosses(int @NonNull [] census) {
        return Field.kills.of(census) - Field.lost.of(census);
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
     * The game file's header members. Only run, a and players go through GameRecorder.quote; the rest are written
     * raw. Not built with Aisim.JSON, which escapes non-ASCII characters where GameRecorder.quote does not.
     */
    private static @NonNull String header(@NonNull Job job, @NonNull String snapshot) {
        StringBuilder text = new StringBuilder("\"source\":\"aisim\"");
        text.append(",\"run\":").append(GameRecorder.quote(job.run()));
        text.append(",\"key\":\"").append(job.key()).append('"');
        text.append(",\"a\":").append(GameRecorder.quote(job.teamPlayers(job.team(job.side()))));
        text.append(",\"players\":").append(GameRecorder.quote(job.players()));
        text.append(",\"map\":\"").append(job.map()).append('"');
        text.append(",\"seed\":").append(job.seed());
        text.append(",\"mapcode\":\"").append(job.mapcode()).append('"');
        text.append(",\"rng\":").append(job.rng());
        text.append(",\"snap\":\"").append(snapshot).append('"');
        return text.toString();
    }

    /** Each slot's AI log file, or null when the job keeps no AI logs. */
    private static @Nullable IntFunction<Path> aiLogFiles(@NonNull Job job) {
        return job.logs() == null ? null : job::aiLogFile;
    }

    /**
     * The game file's end members: how the game ended, how its last elimination happened, the winning team and every
     * team's place (null for a game that did not count).
     */
    private static @NonNull String endMembers(@NonNull Job job, @NonNull Outcome outcome) {
        String end = outcome.end == null ? "null" : "\"" + outcome.end + "\"";
        String via = outcome.via() == null ? "null" : "\"" + outcome.via() + "\"";
        String places = "null";
        Integer winner = null;
        if (outcome.counts()) {
            List<String> each = new ArrayList<>();
            for (int team = 0; team < job.teamCount(); team++) {
                each.add(String.valueOf(outcome.place(team, job.teamCount())));
            }
            places = "[" + String.join(",", each) + "]";
            winner = outcome.winnerTeam(job.teamCount());
        }
        return "\"end\":" + end + ",\"via\":" + via + ",\"winnerTeam\":" + winner + ",\"places\":" + places;
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
            return errorRow(job, snapshot, outcome.end, outcome.problem, gameSeconds(world), world.getTick());
        }
    }

    /**
     * A played game's row: {@link #baseRow}, how the game ended and who won, team A's result and measures (null when
     * the game does not count), every team's block, then the recorder's health, the problem and the replay command.
     */
    private static @NonNull Map<String, Object> row(@NonNull Job job, @NonNull String snapshot, @NonNull World world,
            @NonNull GameRecorder recorder, @NonNull Outcome outcome) {
        boolean counts = outcome.counts();
        int teams = job.teamCount();
        int team_a = job.team(job.side());
        int[] a = teamCensus(job, recorder, team_a);
        // a game that ended before a milestone takes it from the final census
        int kd30 = outcome.kd30 != null ? outcome.kd30 : killsMinusLosses(a);
        int w15 = outcome.w15 != null ? outcome.w15 : warriors(a);
        Map<String, Object> row = baseRow(job, snapshot);
        row.put("end", outcome.end == null ? null : outcome.end.name());
        row.put("via", outcome.via());
        row.put("winnerTeam", counts ? outcome.winnerTeam(teams) : null);
        row.put("t", gameSeconds(world));
        putTicks(row, job, world.getTick());
        row.put("checksum", world.getChecksum());
        row.put("result", counts ? outcome.result(team_a, teams) : null);
        row.put("place", counts ? outcome.place(team_a, teams) : null);
        row.put("score", counts ? outcome.score(team_a, teams) : null);
        row.put("elim", counts ? outcome.elim(team_a, teams) : null);
        row.put("kd30", counts ? kd30 : null);
        row.put("w15", counts ? w15 : null);
        row.put("margin", counts ? outcome.margin(team_a, teams) : null);
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (int team = 0; team < teams; team++) {
            blocks.add(teamBlock(job, world, recorder, outcome, team));
        }
        row.put("teams", blocks);
        row.put("recorderFailed", recorder.hasFailed());
        row.put("problem", outcome.problem);
        row.put("replay", replayCommand(job));
        return row;
    }

    /**
     * The row of a game that did not count and has no result of its own (hang, dead worker, harness error), which
     * stopped at game second {@code t}, world tick {@code ticks}.
     */
    static @NonNull Map<String, Object> errorRow(@NonNull Job job, @NonNull String snapshot, @NonNull End end,
            @NonNull String problem, double t, int ticks) {
        Map<String, Object> row = baseRow(job, snapshot);
        row.put("end", end.name());
        row.put("winnerTeam", null);
        row.put("t", t);
        putTicks(row, job, ticks);
        row.put("result", null);
        row.put("problem", problem);
        row.put("replay", replayCommand(job));
        return row;
    }

    /**
     * The world ticks played, next to t, at a game speed other than normal, where t (game seconds) is not ticks / 50.
     * Rows of such runs from before the harness counted game time have a speed but no ticks, and t in ticks / 50.
     */
    private static void putTicks(@NonNull Map<String, Object> row, @NonNull Job job, int ticks) {
        if (job.speed() != null) {
            row.put("ticks", ticks);
        }
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
        row.put("players", job.players());
        row.put("a", job.teamPlayers(job.team(job.side())));
        row.put("map", job.map());
        row.put("mapcode", job.mapcode());
        row.put("minutes", job.minutes());
        row.put("rng", job.rng());
        row.put("speed", job.speed());
        row.put("collapse", job.collapse());
        row.put("snap", snapshot);
        row.put("perturb", perturb);
        return row;
    }

    /**
     * A team's block: its number, players and slots; its place, when it went out and how (null when it did not, or
     * when the game does not count); its final census, summed over its slots; its AIs' log counters, summed, and its
     * first AI error in slot order.
     */
    private static @NonNull Map<String, Object> teamBlock(@NonNull Job job, @NonNull World world,
            @NonNull GameRecorder recorder, @NonNull Outcome outcome, int team) {
        boolean counts = outcome.counts();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("team", team);
        block.put("players", job.teamPlayers(team));
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < job.slots(); slot++) {
            if (job.team(slot) == team) {
                slots.add(slot);
            }
        }
        block.put("slots", slots);
        block.put("place", counts ? outcome.place(team, job.teamCount()) : null);
        block.put("score", counts ? outcome.score(team, job.teamCount()) : null);
        block.put("out", outcome.out_at.get(team));
        block.put("via", outcome.out_via.get(team));
        int[] census = teamCensus(job, recorder, team);
        for (Field field : Field.values()) {
            block.put(field.name(), field.of(census));
        }
        SortedMap<String, Integer> counters = new TreeMap<>();
        String first_error = null;
        for (int slot : slots) {
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
