package com.oddlabs.tt.aikit;

import com.oddlabs.tt.event.LocalEventQueue;
import com.oddlabs.tt.global.Settings;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.landscape.WorldParameters;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.AdvancedAI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import com.oddlabs.tt.viewer.DefaultInGameInfo;
import com.oddlabs.tt.viewer.InGameInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Play-testing an AI in the real game. {@code ./aisim.sh gui SPEC} starts the game in developer mode with the system
 * property {@value #HARD_AI_PROPERTY} set to SPEC; then that AI plays every "Hard" slot of a single-player skirmish,
 * and the game is recorded next to the session logs in the same format as harness games ({@link GameRecorder}), with
 * the AI logs beside it.
 *
 * <p>The game calls in twice, from WorldViewer: {@link #hardAi} for each Hard slot, and {@link #leave} when the world
 * closes. For every other game both change nothing. Multiplayer games are never affected, so lockstep peers cannot
 * diverge.
 */
public final class PlayTest {
    /** The system property naming the AI for the Hard slots; aisim.sh gui sets it. */
    private static final String HARD_AI_PROPERTY = "com.oddlabs.tt.hard_ai";
    /** The system property naming the aisim snapshot the game runs from; aisim.sh gui sets it. */
    private static final String SNAP_PROPERTY = "aisim.snap";
    private static final Logger logger = Logger.getLogger(PlayTest.class.getName());

    // Guarded by the class: the game's thread and the shutdown hook both use them.
    /**
     * The recording in progress; cleared when it finishes, so it does not keep a finished world reachable. A game left
     * before it ended is finished with a quit line when the game closes its world ({@link #leave}).
     */
    private static @Nullable GameRecorder recorder;
    /**
     * The world the last recording was opened (or tried) for; weak so it never keeps that world reachable. It makes
     * sure each world gets one recording, even after the recording finished or could not be opened.
     */
    private static @NonNull WeakReference<World> recorded_world = new WeakReference<>(null);
    /** During --eventload playback, all replayed games of the session go to one folder. */
    private static @Nullable Path playback_dir;
    private static boolean shutdown_hook;

    private PlayTest() {
    }

    /**
     * The AI of a skirmish "Hard" slot. The stock Hard AI, unless {@value #HARD_AI_PROPERTY} is set, the game runs in
     * developer mode ({@code -Dcom.oddlabs.tt.developer=true}, which {@code ./aisim.sh gui} passes) and this is a
     * single-player skirmish: then that AI plays the slot and the game is recorded. Requiring developer mode too means
     * a stray property in a normal launch cannot swap the Hard AI.
     */
    public static @NonNull AI hardAi(@NonNull Player player, @NonNull UnitInfo unit_info, @NonNull InGameInfo game,
            @NonNull WorldParameters params) {
        String spec = System.getProperty(HARD_AI_PROPERTY);
        if (spec == null || !Settings.getSettings().inDeveloperMode() || game.isMultiplayer()
                || !(game instanceof DefaultInGameInfo)) {
            return stockHard(player, unit_info);
        }
        try {
            AiSpec.check(spec); // ./aisim.sh gui checks it before starting the game; this is the fallback
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.SEVERE, "Bad AI '" + spec + "' for the Hard slot; the stock Hard AI plays", e);
            return stockHard(player, unit_info);
        }
        attach(player, spec, params);
        // A constructor that throws propagates: its units may already exist, and a broken AI must not pass for Hard.
        return AiSpec.create(spec, player, unit_info);
    }

    private static @NonNull AI stockHard(@NonNull Player player, @NonNull UnitInfo unit_info) {
        return new AdvancedAI(player, unit_info, AdvancedAI.DIFFICULTY_HARD);
    }

    /**
     * Called when the game closes {@code world} (WorldViewer.close): if its game is still being recorded, finishes it
     * with a quit line (the world is still intact, so the final census is real), then closes the AI logs bound to it.
     * Neither the recorder nor the AI logs then keep the left world reachable or their files open. Any other world,
     * and a game that already ended, is left alone.
     */
    public static synchronized void leave(@NonNull World world) {
        GameRecorder current = recorder;
        if (current != null && current.records(world)) {
            current.finish("\"end\":\"quit\""); // clears recorder
        }
        AiLog.endFor(world);
    }

    /**
     * Records the game of {@code player} into its session log folder as {@code game-<n>.jsonl}, with the AI logs as
     * {@code game-<n>-ai-s<slot>.log}, and labels {@code player}'s slot with {@code label}. Once per world; later
     * calls (more Hard slots) only label.
     */
    private static synchronized void attach(@NonNull Player player, @NonNull String label,
            @NonNull WorldParameters params) {
        World world = player.getWorld();
        if (world != recorded_world.get()) {
            GameRecorder previous = recorder;
            if (previous != null) {
                // a fallback: leave() normally finished the previous game when it was left; clears recorder
                previous.finish("\"end\":\"quit\"");
            }
            recorded_world = new WeakReference<>(world);
            recorder = open(world, label, params);
        }
        GameRecorder current = recorder;
        if (current != null) { // null: this world is not recorded, or its game already ended
            current.label(AiLog.slotOf(world, player), label);
        }
    }

    /**
     * Opens {@code game-<n>.jsonl} in the game's folder and binds the AI logs beside it. Returns null (after logging
     * why) when the game cannot be recorded.
     */
    private static @Nullable GameRecorder open(@NonNull World world, @NonNull String spec,
            @NonNull WorldParameters params) {
        Path session_dir = Settings.getSettings().last_event_log_dir;
        if (session_dir.toString().isEmpty()) {
            logger.warning("No session log folder; the game is not recorded");
            return null;
        }
        boolean playback = LocalEventQueue.getQueue().getDeterministic().isPlayback();
        Path dir = folder(session_dir, playback);
        try {
            Files.createDirectories(dir);
            String game = "game-" + nextGameNumber(dir);
            String snap = System.getProperty(SNAP_PROPERTY, "");
            Writer out = Files.newBufferedWriter(dir.resolve(game + ".jsonl"), StandardCharsets.UTF_8);
            AiLog.begin(world, slot -> dir.resolve(game + "-ai-s" + slot + ".log"),
                    "ctx=gui spec=" + spec + " snap=" + snap);
            GameRecorder started = GameRecorder.startGui(world, out, header(params, snap, playback), PlayTest::release);
            addFlushHookOnce();
            logger.info("Recording this game to " + dir);
            return started;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot record the game in " + dir, e);
            return null;
        }
    }

    /**
     * The session log folder, or during {@code --eventload} playback a fresh {@code replay-<millis>} subfolder (never
     * over the originals), shared by every replayed game of the session.
     */
    private static @NonNull Path folder(@NonNull Path session_dir, boolean playback) {
        if (!playback) {
            return session_dir;
        }
        Path replay_dir = playback_dir;
        if (replay_dir == null) {
            replay_dir = session_dir.resolve("replay-" + System.currentTimeMillis());
            playback_dir = replay_dir;
        }
        return replay_dir;
    }

    /** The first n without a {@code game-<n>.jsonl} in {@code dir}. */
    private static int nextGameNumber(@NonNull Path dir) {
        int n = 1;
        while (Files.exists(dir.resolve("game-" + n + ".jsonl"))) {
            n++;
        }
        return n;
    }

    /** The game file's extra header members. */
    private static @NonNull String header(@NonNull WorldParameters params, @NonNull String snap, boolean playback) {
        StringBuilder text = new StringBuilder("\"ctx\":\"gui\"");
        text.append(",\"mapcode\":").append(GameRecorder.quote(params.getMapcode()));
        text.append(",\"units\":").append(params.getInitialUnitCount());
        text.append(",\"maxUnits\":").append(params.getMaxUnitCount());
        text.append(",\"maxBuildings\":").append(params.getMaxBuildingCount());
        text.append(",\"ships\":").append(params.isShipsEnabled());
        text.append(",\"snap\":").append(GameRecorder.quote(snap));
        text.append(",\"playback\":").append(playback);
        return text.toString();
    }

    /**
     * Flushes the current game's buffered lines when the JVM exits, or only the AI logs once that game has ended (its
     * AIs may log after the end line); installed once per JVM.
     */
    private static void addFlushHookOnce() {
        if (shutdown_hook) {
            return;
        }
        shutdown_hook = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            GameRecorder last = current();
            if (last != null) {
                last.flush();
            } else {
                AiLog.flushAll();
            }
        }, "play-test-flush"));
    }

    private static synchronized @Nullable GameRecorder current() {
        return recorder;
    }

    /** Forgets {@code finished} if it is the recording in progress, so its finished world is not kept reachable. */
    private static synchronized void release(@NonNull GameRecorder finished) {
        if (recorder == finished) {
            recorder = null;
        }
    }
}
