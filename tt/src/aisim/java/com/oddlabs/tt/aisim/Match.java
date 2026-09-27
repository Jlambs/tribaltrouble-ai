package com.oddlabs.tt.aisim;

import com.oddlabs.matchmaking.Game;
import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.AiSpec;
import com.oddlabs.tt.aikit.Census.Field;
import com.oddlabs.tt.aikit.GameRecorder;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.audio.AbstractAudioPlayer;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.global.GlobalsInit;
import com.oddlabs.tt.global.Settings;
import com.oddlabs.tt.landscape.AudioImplementation;
import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.landscape.LandscapeResources;
import com.oddlabs.tt.landscape.NotificationListener;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.landscape.WorldParameters;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.PlayerInfo;
import com.oddlabs.tt.player.UnitInfo;
import com.oddlabs.tt.procedural.Landscape;
import com.oddlabs.tt.render.RenderQueues;
import com.oddlabs.tt.resource.BlendInfo;
import com.oddlabs.tt.resource.NativeResource;
import com.oddlabs.tt.resource.StructureBlend;
import com.oddlabs.tt.resource.WorldInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

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
 * Plays one game headlessly, exactly as the client simulates it: a hidden OpenGL window (resources and height maps
 * create GL objects), the GUI's map for the same menu settings, AIs created in slot order, then
 * {@code world.tick(0.02f)} ({@link AnimationManager#ANIMATION_SECONDS_PER_TICK}) in a loop, which is what the client
 * does each lockstep tick minus human commands.
 * One game at a time per JVM, always on the thread that called {@link #boot()}: the simulation keeps static scratch
 * buffers and the GL context belongs to that thread.
 */
final class Match {
    /** A player with at most this many units and no chieftain or real building is collapsing, see collapsing(). */
    private static final int COLLAPSE_UNITS = 8;
    /** Collapsing this long in a row puts a player out, when the job allows collapse. */
    private static final int COLLAPSE_SECONDS = 60;
    /**
     * A game that times out is a win only when the margin, (A - B) / (A + B) of the teams' strength, is at least this
     * big either way: one team has at least 55% of the strength on the map. Closer games are draws.
     */
    private static final double DECISIVE_MARGIN = 0.10;
    /** The tick of the w15 milestone: A's warriors at 15:00. */
    private static final int W15_TICK = 15 * 60 * GameTime.TICKS_PER_SECOND;
    /** The tick of the kd30 milestone: A's kills minus B's at 30:00. */
    private static final int KD30_TICK = 30 * 60 * GameTime.TICKS_PER_SECOND;
    /** Each player's starting units: the skirmish menu's default. */
    private static final int STARTING_UNITS = Game.DEFAULT_INITIAL_UNIT_COUNT;

    // Worker-wide state: one game at a time per JVM, see the class comment.
    private static @Nullable LandscapeResources landscape_resources;
    private static @Nullable RacesResources races_resources;
    /**
     * How many identity hashes boot() draws on the simulation thread before the first game (pid % 1000). The thread's
     * identity-hash sequence then starts somewhere else in every worker JVM, so an AI whose play depends on
     * identity-hash order (HashMap/HashSet of engine objects) plays differently on replay and shows up as a MISMATCH.
     */
    static int perturb;
    /** Written so the JIT cannot drop the hashing loop as dead code. */
    private static volatile int hash_sink;
    /** Ticks played in the current game, for the worker's hang watchdog. */
    static volatile int progress;

    private Match() {
    }

    /** Readies the calling thread for games: silent settings, a GL context, the shared resources, the hash offset. */
    static void boot() {
        Settings settings = new Settings();
        settings.play_sfx = false;
        settings.play_music = false;
        Settings.setSettings(settings);
        openHiddenGlWindow();
        GlobalsInit.init();
        RenderQueues queues = new RenderQueues();
        landscape_resources = World.loadCommon(queues); // also opens OpenAL: sounds are loaded with the resources
        races_resources = World.loadInGame(queues);
        perturbIdentityHashes();
    }

    /** Makes a hidden 64x64 OpenGL 4.1 core window's context current on this thread. */
    private static void openHiddenGlWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("glfwInit failed: aisim needs a desktop session with OpenGL 4.1");
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(64, 64, "aisim", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            throw new IllegalStateException("cannot create a hidden OpenGL 4.1 core window");
        }
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

    /** Draws {@link #perturb} identity hashes on this thread (see there for why). */
    private static void perturbIdentityHashes() {
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
        Landscape landscape = newLandscape(job);
        World world = newWorld(job, landscape);
        if (job.rng() != null) {
            world.getRandom().setSeed(job.rng() * 1000 + job.side()); // before anything draws from it
        }
        // registered before the AIs, as in the GUI, so each tick the recorder samples before any AI acts
        GameRecorder recorder = startRecorder(job, snapshot, world);
        // before the AIs, which fetch their log handles when created
        AiLog.begin(world, aiLogFiles(job), "key=" + job.key() + " snap=" + snapshot);
        List<URLClassLoader> loaders = new ArrayList<>();
        Map<String, Object> row;
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
            dispose(world, landscape);
            for (URLClassLoader loader : loaders) {
                loader.close();
            }
        }
        row.put("wall", seconds(System.nanoTime() - wall_start));
        // CPU seconds of the simulation thread: the cost of the game itself, unlike wall time not slowed by the load
        row.put("cpu", seconds(threadCpuNanos() - cpu_start));
        return row;
    }

    /** Creates the AIs in slot order, as the game does; an AI that fails to start fails the game with an error. */
    private static void createAIs(@NonNull Job job, @NonNull World world, @NonNull Outcome outcome,
            @NonNull List<URLClassLoader> loaders) {
        UnitInfo unit_info = new UnitInfo(false, false, 0, false, STARTING_UNITS, 0, 0, 0); // as Client creates it
        for (int slot = 0; slot < job.slots() && outcome.end == null; slot++) {
            Player player = world.getPlayers()[slot];
            try {
                player.setAI(createAI(job.spec(slot), player, unit_info, loaders));
            } catch (ExceptionInInitializerError e) {
                // a LinkageError too, so it is caught first: a failing static initializer is the AI's own error
                Throwable cause = e.getCause() == null ? e : e.getCause();
                outcome.fail(End.error, aiInitPrefix(job, slot) + "static initializer failed: ", cause, job);
            } catch (LinkageError e) {
                throw e; // an engine mismatch, not the AI's error: run() reports it as a link error
            } catch (RuntimeException | Error e) {
                outcome.fail(End.error, aiInitPrefix(job, slot), e, job);
            }
        }
    }

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

    /** Each slot's AI log file, or null when the job keeps no AI logs. */
    private static @Nullable IntFunction<Path> aiLogFiles(@NonNull Job job) {
        String logs = job.logs();
        if (logs == null) {
            return null;
        }
        return slot -> Path.of(logs + "-ai-s" + slot + ".log");
    }

    /** The problem prefix of an AI that failed while being created. */
    private static @NonNull String aiInitPrefix(@NonNull Job job, int slot) {
        return "ai_init: slot " + slot + " " + job.spec(slot) + ": ";
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

    /** The game time in seconds, rounded to tenths. */
    private static double gameSeconds(@NonNull World world) {
        return Math.round(GameTime.seconds(world) * 10) / 10.0;
    }

    /** Nanoseconds as seconds, rounded to tenths. */
    private static double seconds(long nanos) {
        return Math.round(nanos / 1e8) / 10.0;
    }

    /** The CPU time of the calling thread. */
    private static long threadCpuNanos() {
        return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    /** What happened; {@code end} is never hang (hang rows come from the worker's watchdog). */
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

        /** A team has nobody standing: the other team wins, or it is a draw when neither has anyone left. */
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

        /** The time limit: the margin compares the teams' strengths, and only a decisive margin wins. */
        void timedOut(double a_strength, double b_strength) {
            end = End.timeout;
            if (a_strength + b_strength == 0) {
                margin = 0;
            } else {
                margin = Math.round((a_strength - b_strength) / (a_strength + b_strength) * 1000) / 1000.0;
            }
            if (margin >= DECISIVE_MARGIN) {
                winner = "a";
            } else if (margin <= -DECISIVE_MARGIN) {
                winner = "b";
            } else {
                winner = "draw";
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
    }

    /** Ticks the world until one team is out or the time is up, taking the milestones on the way. */
    private static void play(@NonNull Job job, @NonNull World world, @NonNull GameRecorder recorder,
            @NonNull Outcome outcome) {
        Player[] players = world.getPlayers();
        int[] collapse_seconds = new int[players.length];
        boolean[] out = new boolean[players.length];
        int last_tick = job.minutes() * 60 * GameTime.TICKS_PER_SECOND;
        while (true) {
            world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
            int tick = world.getTick();
            progress = tick;
            if (tick == W15_TICK) {
                outcome.w15 = warriors(teamCensus(job, recorder, true));
            } else if (tick == KD30_TICK) {
                int[] a = teamCensus(job, recorder, true);
                int[] b = teamCensus(job, recorder, false);
                outcome.kd30 = Field.kills.of(a) - Field.kills.of(b);
            }
            if (tick % GameTime.TICKS_PER_SECOND != 0) {
                continue; // everything below happens once per game second
            }
            boolean collapsed = updateOut(job, players, recorder, collapse_seconds, out);
            boolean a_standing = standing(job, out, true);
            boolean b_standing = standing(job, out, false);
            if (!a_standing || !b_standing) {
                outcome.eliminated(a_standing, b_standing, collapsed);
                return;
            }
            if (tick >= last_tick) {
                double a = Field.strength.of(teamCensus(job, recorder, true));
                double b = Field.strength.of(teamCensus(job, recorder, false));
                outcome.timedOut(a, b);
                return;
            }
        }
    }

    /**
     * One game second for every player not yet out: advances their collapse clock (the game file gets a collapse
     * event when it reaches {@value #COLLAPSE_SECONDS} s) and marks them out once the engine has them dead or, when
     * the job allows collapse, once they have collapsed that long. Returns whether a collapse put a living player out.
     */
    private static boolean updateOut(@NonNull Job job, Player @NonNull [] players, @NonNull GameRecorder recorder,
            int @NonNull [] collapse_seconds, boolean @NonNull [] out) {
        boolean collapsed = false;
        for (int slot = 0; slot < players.length; slot++) {
            if (!out[slot]) {
                collapse_seconds[slot] = collapsing(players[slot]) ? collapse_seconds[slot] + 1 : 0;
                if (collapse_seconds[slot] == COLLAPSE_SECONDS) {
                    recorder.event("collapse", slot, "");
                }
                boolean gone = job.collapse() && collapse_seconds[slot] >= COLLAPSE_SECONDS;
                collapsed |= gone && players[slot].isAlive();
                out[slot] = gone || !players[slot].isAlive();
            }
        }
        return collapsed;
    }

    /** Whether a player of the team is not out yet. */
    private static boolean standing(@NonNull Job job, boolean @NonNull [] out, boolean team_a) {
        for (int slot = 0; slot < out.length; slot++) {
            if (onTeam(job, slot, team_a) && !out[slot]) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code slot} plays for team A ({@code team_a}) or for team B (not {@code team_a}). */
    private static boolean onTeam(@NonNull Job job, int slot, boolean team_a) {
        return (slot == job.side()) == team_a;
    }

    /**
     * A player is collapsing while they have at most {@value #COLLAPSE_UNITS} units, no chieftain and no land building
     * other than a tower (quarters, armory, built or under construction): a lost game the engine's own rule (any unit
     * left) would drag on to the time limit. When the job allows collapse, {@value #COLLAPSE_SECONDS} s of collapsing
     * in a row puts the player out.
     */
    private static boolean collapsing(@NonNull Player player) {
        if (player.getUnitCountContainer().getNumSupplies() > COLLAPSE_UNITS || player.hasActiveChieftain()) {
            return false;
        }
        for (Selectable<?> s : player.getUnits().getSet()) {
            if (s instanceof LandBuilding b && b.getTemplate().getTemplateID() != Race.BUILDING_TOWER) {
                return false;
            }
        }
        return true;
    }

    /**
     * The census of A's slot, or of B's team summed over its slots. ax/ay are the warrior-weighted centre of the
     * team's armies (-1 without warriors), which for B's several slots is a weighted mean rather than a sum.
     */
    private static int @NonNull [] teamCensus(@NonNull Job job, @NonNull GameRecorder recorder, boolean team_a) {
        int[] sum = new int[Field.values().length];
        long x = 0;
        long y = 0;
        long warriors = 0;
        for (int slot = 0; slot < job.slots(); slot++) {
            if (onTeam(job, slot, team_a)) {
                int[] c = recorder.census(slot);
                for (int i = 0; i < sum.length; i++) {
                    sum[i] += c[i];
                }
                int w = fieldWarriors(c);
                x += (long) w * Field.ax.of(c);
                y += (long) w * Field.ay.of(c);
                warriors += w;
            }
        }
        sum[Field.ax.ordinal()] = warriors == 0 ? -1 : (int) (x / warriors);
        sum[Field.ay.ordinal()] = warriors == 0 ? -1 : (int) (y / warriors);
        return sum;
    }

    /** The warriors of a census, towers included, as the reports count them. */
    private static int warriors(int @NonNull [] census) {
        return fieldWarriors(census) + Field.tower.of(census);
    }

    /** The warriors of a census that can be out in the field (no towers): the weight of the army position. */
    private static int fieldWarriors(int @NonNull [] census) {
        return Field.rock.of(census) + Field.iron.of(census) + Field.rubber.of(census);
    }

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

    /** The map the GUI generates for the same menu settings. */
    private static @NonNull Landscape newLandscape(@NonNull Job job) {
        float hills = job.hills() / 10f;
        float trees = job.trees() / 10f;
        float supplies = job.supplies() / 10f;
        int landscape_seed = job.seed() * job.seed();
        // as TerrainMenu passes them: the seed squared, the starting units, fixed start positions, no archipelago
        return new Landscape(job.slots(), job.meters(), job.terrainType(), detailAlpha(), hills, trees, supplies,
                landscape_seed, STARTING_UNITS, 0f, false);
    }

    /** Frees a finished world's GL textures (only the client's render loop would). */
    private static void dispose(@NonNull World world, @NonNull Landscape landscape) {
        world.getHeightMap().getHeightTexture().close();
        for (BlendInfo blend : landscape.getBlendInfos()) {
            blend.getAlphaMap().close();
            if (blend instanceof StructureBlend structure) {
                structure.getStructureMap().close();
                structure.getNormalMap().close();
            }
        }
        NativeResource.processGLCleanupTasks();
    }

    /** A new world for {@code job} with its players and their starting units, but no AIs yet. */
    private static @NonNull World newWorld(@NonNull Job job, @NonNull Landscape landscape) {
        int meters = job.meters();
        // IslandGenerator.generate without the GPU texture bake (the world never reads the textures):
        int colormap = colormapSize(meters);
        int chunks = colormap / 512; // IslandGenerator.TEXELS_PER_CHUNK
        WorldInfo info = new WorldInfo(meters, landscape.getSeaLevelMeters(), colormap, chunks, null, null, null,
                landscape.getHeight(), landscape.getTrees(), landscape.getPalmtrees(), landscape.getRock(),
                landscape.getIron(), landscape.getPlants(), landscape.getAccessGrid(), landscape.getDockGrid(),
                landscape.getWaterGrid(), landscape.getBuildGrid(), landscape.getIslandIds(),
                landscape.getIslandInfos(), landscape.getStartingLocations(), landscape.getBlendInfos());
        // spotless:off
        WorldParameters parameters = WorldParameters.builder()
                .initialGameSpeed(Game.GAMESPEED_NORMAL)
                .mapcode(job.mapcode())
                .initialUnitCount(STARTING_UNITS)
                .maxUnitCount(Game.DEFAULT_MAX_UNIT_COUNT)
                .mapSize(job.size())
                .maxBuildingCount(Game.DEFAULT_MAX_BUILDING_COUNT)
                .ships(false)
                .build();
        // spotless:on
        PlayerInfo[] infos = new PlayerInfo[job.slots()];
        for (int slot = 0; slot < infos.length; slot++) {
            infos[slot] = playerInfo(job, slot);
        }
        AudioImplementation silent_audio = params -> new AbstractAudioPlayer(null, params) {
        };
        NotificationListener no_notifications = new NotificationListener() {
        };
        return World.newWorld(silent_audio, landscape_resources, races_resources, no_notifications, parameters, info,
                job.terrainType(), infos, Landscape.getFogInfo(job.terrainType(), meters));
    }

    /** The colormap size IslandGenerator computes: its grid units times getTexelsPerGridUnit(). */
    private static int colormapSize(int meters) {
        int grid_units = meters / HeightMap.METERS_PER_UNIT_GRID;
        int mip_shift = Globals.TEXTURE_MIP_SHIFT[Settings.getSettings().graphic_detail];
        int texels_per_grid_unit = Globals.TEXELS_PER_GRID_UNIT / (int) Math.pow(2, mip_shift);
        return grid_units * texels_per_grid_unit;
    }

    /** The player of {@code slot}: team 0 for A and 1 for B, named as the game file and the AI logs show it. */
    private static @NonNull PlayerInfo playerInfo(@NonNull Job job, int slot) {
        int team = slot == job.side() ? 0 : 1;
        int race = job.vikings(slot) ? RacesResources.RACE_VIKINGS : RacesResources.RACE_NATIVES;
        String name = "s" + slot + ":" + job.spec(slot);
        return new PlayerInfo(team, race, name);
    }

    /** The detail alpha IslandGenerator computes; it only shapes the detail texture, but stays identical. */
    private static float detailAlpha() {
        // 256 and .15f are IslandGenerator's IDEAL_TEXELS_PER_DETAIL and IDEAL_DETAIL_ALPHA
        int detail_mip_level = 256 / Globals.DETAIL_SIZE - 1;
        return .15f * (float) Math.pow(Globals.LANDSCAPE_DETAIL_FADEOUT_FACTOR,
                Math.max(detail_mip_level - Globals.LANDSCAPE_DETAIL_FADEOUT_BASE_LEVEL, 0));
    }

    /**
     * The game file's header members. Only run, a and b go through GameRecorder.quote; the rest are written raw. Not
     * built with Aisim.JSON, which escapes non-ASCII characters where GameRecorder.quote does not.
     */
    private static @NonNull String header(@NonNull Job job, @NonNull String snapshot) {
        StringBuilder text = new StringBuilder("\"ctx\":\"aisim\"");
        text.append(",\"run\":").append(GameRecorder.quote(job.run()));
        text.append(",\"key\":\"").append(job.key()).append('"');
        text.append(",\"a\":").append(GameRecorder.quote(job.a()));
        text.append(",\"b\":").append(GameRecorder.quote(job.b()));
        text.append(",\"map\":\"").append(job.map()).append('"');
        text.append(",\"seed\":").append(job.seed());
        text.append(",\"mapcode\":\"").append(job.mapcode()).append('"');
        text.append(",\"rng\":").append(job.rng());
        text.append(",\"snap\":\"").append(snapshot).append('"');
        return text.toString();
    }

    /** The fields every result row starts with, whatever happened (also used for hang and dead-worker rows). */
    private static @NonNull Map<String, Object> baseRow(@NonNull Job job, @NonNull String snapshot) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", 1);
        row.put("run", job.run());
        row.put("key", job.key());
        row.put("seed", job.seed());
        row.put("side", job.side());
        row.put("vs", job.vs());
        row.put("a", job.a());
        row.put("b", job.b());
        row.put("races", job.raceA().charAt(0) + "," + job.raceB().charAt(0));
        row.put("map", job.map());
        row.put("mapcode", job.mapcode());
        row.put("minutes", job.minutes());
        row.put("rng", job.rng());
        row.put("collapse", job.collapse());
        row.put("snap", snapshot);
        row.put("perturb", perturb);
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

    /** The command that replays {@code job}, as printed in every row. */
    private static @NonNull String replayCommand(@NonNull Job job) {
        return "./aisim.sh replay " + job.run() + " " + job.key();
    }

    /**
     * A played game's row: {@link #baseRow}, how the game ended, A's scores (score, elim, kd30, w15 and margin, null
     * when the game does not count), each team's final block, then the recorder's health, the problem and the replay
     * command.
     */
    private static @NonNull Map<String, Object> row(@NonNull Job job, @NonNull String snapshot, @NonNull World world,
            @NonNull GameRecorder recorder, @NonNull Outcome outcome) {
        boolean counts = outcome.winner != null;
        int[] a = teamCensus(job, recorder, true);
        int[] b = teamCensus(job, recorder, false);
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
        row.put("A", teamBlock(job, world, a, true));
        row.put("B", teamBlock(job, world, b, false));
        row.put("recErr", recorder.hasFailed() ? 1 : 0);
        row.put("problem", outcome.problem);
        row.put("replay", replayCommand(job));
        return row;
    }

    /** A team's final census plus its AIs' log counters summed over its slots and its first AI error in slot order. */
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
}
