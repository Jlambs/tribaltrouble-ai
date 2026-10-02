package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.render.state.GLRenderContext;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * The playback clock of a watched game, run once per rendered frame on the GL thread (Renderer's frame task). It plays
 * the world tick by tick, always {@code world.tick(ANIMATION_SECONDS_PER_TICK)}, at the world's recorded game speed,
 * which the job's parameters set and playback never changes: another engine speed would scale the game time of each
 * tick and simulate another game. Playback speed is world ticks per real second instead, 1x being 50 of them, which is
 * the game at its recorded speed (normal unless the run used --speed); the times the view shows are the game time of
 * those ticks at the view's clock speed ({@link Times}). Each frame gets a time budget for its ticks, so the window
 * stays responsive and a fast-forward
 * shows as it goes; when the budget caps a speed, {@link #effectiveSpeed()} says how fast it really runs.
 *
 * <p>Seeking forward plays on to the target; seeking backward, or forward past a later checkpoint, restores the latest
 * checkpoint at or before the target through the {@link Rewinder} and plays on from there. The world never plays past
 * the recorded end. The clock stops while the Esc menu pauses the viewer.
 */
final class Clock {
    /** What the clock does. */
    enum State {
        /** Playing at the chosen speed. */
        PLAYING,
        /** Not playing. */
        PAUSED,
        /** Playing as fast as the budget allows to a target tick, then back to playing or paused. */
        SEEKING,
        /** At the recorded end: nothing more to play (seeking back still works). */
        ENDED
    }

    /** What the clock tells its session. */
    interface Listener {
        /** The world just played {@code tick} for the first time: no tick after it was played yet. */
        void frontier(int tick);

        /** A restore is about to replace the world's state: remember what should survive it. */
        void beforeRestore();

        /** The world's state was restored to {@code tick}: drop every reference into the old state. */
        void restored(int tick);

        /**
         * The restore {@link #beforeRestore} announced failed: the world is as it was, and a seek under way goes on
         * ({@link #busy()} says whether one does).
         */
        void restoreFailed();

        /**
         * A presimulation starts from where the view is, and comes back there: remember what should be there again
         * when it is back (what the view sees on its way out is not what it comes back to).
         */
        void presimulationStarts();

        /**
         * A presimulation turns back (at the recorded end, or stopped) to where it started; the way back restores
         * first. The seek out never gets a {@link #seekDone}: the presimulation is one seek for the view.
         */
        void presimulationTurns();

        /**
         * A seek reached its target and nothing is queued after it; {@code restored} if it, or a seek it took over
         * from, started with a restore.
         */
        void seekDone(boolean restored);
    }

    /** Playback speeds: world ticks per real second divided by 50. The last is as fast as possible. */
    static final double[] SPEEDS = {1 / 8d, 1 / 4d, 1 / 2d, 1, 2, 4, 8, 16, 32, 64, 128, Double.POSITIVE_INFINITY};
    static final int SPEED_1X = 3;
    static final int SPEED_MAX = SPEEDS.length - 1;

    /** Real time a frame may spend on ticks while playing: the rest of a 60 Hz frame goes to drawing. */
    private static final long PLAY_BUDGET_NANOS = 30_000_000L;
    /** Real time a frame may spend on ticks at MAX and while seeking: about 6 frames per second, still responsive. */
    private static final long FAST_BUDGET_NANOS = 150_000_000L;
    /** The longest frame the clock counts: a stall (a window drag) is not made up for. */
    private static final double MAX_FRAME_SECONDS = .25;
    /** The effective speed is the ticks of about this much recent real time. */
    private static final long SPEED_WINDOW_NANOS = 1_000_000_000L;
    private static final int TPS = GameTime.TICKS_PER_SECOND;

    private final @NonNull World world;
    private final @NonNull Rewinder rewinder;
    private final @NonNull Listener listener;
    private final @NonNull BooleanSupplier menu_paused;
    private final @NonNull Times times;
    /** The recorded end tick, or -1 when unknown. */
    private final int end_tick;
    /** The world never plays past this tick. */
    private final int limit;

    private @NonNull State state = State.PLAYING;
    private int speed_index = SPEED_1X;
    /** Ticks owed to real time at the chosen speed, below 1 most of the time. */
    private double debt;
    /** The highest tick the world played so far. */
    private int frontier;
    private long last_frame_nanos;

    // the current seek
    private int seek_target;
    private int seek_from;
    private boolean seek_restored;
    private long seek_start_nanos;
    /** Whether to play after the seek (else pause). */
    private boolean resume_playing = true;
    /** Seeks queued after the current one (presimulation). */
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private boolean presimulating;
    /** Where a presimulation comes back to, and whether it plays on from there. */
    private int presim_back_to;
    private boolean presim_resume;

    // what the clock did, for reports: every tick played (first passes and re-plays) and every restore
    private long ticks_played;
    private int restores;
    private int last_restore_tick = -1;

    // the effective speed: ticks played in recent frames
    private final long[] frame_nanos = new long[256];
    private final int[] frame_ticks = new int[256];
    private int frame_head;
    private double effective_speed;

    private @Nullable String message;
    private long message_nanos;
    private @Nullable String last_seek_report;

    Clock(@NonNull World world, @NonNull Rewinder rewinder, @NonNull Listener listener,
            @NonNull BooleanSupplier menu_paused, @NonNull Times times, int end_tick) {
        this.world = world;
        this.times = times;
        this.rewinder = rewinder;
        this.listener = listener;
        this.menu_paused = menu_paused;
        this.end_tick = end_tick;
        this.limit = end_tick >= 0 ? end_tick : Integer.MAX_VALUE;
        this.frontier = world.getTick();
    }

    // ---------------------------------------------------------------- queries

    @NonNull
    State state() {
        return state;
    }

    /** The world's tick: the ticks played since the start. */
    int tick() {
        return world.getTick();
    }

    /** The recorded end tick, or -1 when unknown. */
    int endTick() {
        return end_tick;
    }

    /** The highest tick the world played so far: every tick up to it was simulated once. */
    int frontier() {
        return frontier;
    }

    int speedIndex() {
        return speed_index;
    }

    /** The chosen speed as a factor of 1x (infinite for MAX). */
    double speed() {
        return SPEEDS[speed_index];
    }

    /** Ticks per real second over the last second, as a factor of 1x (50). */
    double effectiveSpeed() {
        return effective_speed;
    }

    /** Whether the Esc menu pauses the viewer (the clock then stands still). */
    boolean menuPaused() {
        return menu_paused.getAsBoolean();
    }

    /** Whether a seek (or presimulation) is under way. */
    boolean busy() {
        return state == State.SEEKING || !pending.isEmpty();
    }

    /**
     * Where the user is heading: the tick a presimulation comes back to, else the current seek's target, else the
     * world's tick. Steps and relative jumps count from here, not from a seek's half-replayed tick, so that a second
     * step back during a replay lands one tick before the first, not somewhere near the restored checkpoint.
     */
    int position() {
        if (presimulating) {
            return presim_back_to;
        }
        return state == State.SEEKING ? seek_target : world.getTick();
    }

    /** The target of the current seek, or -1 when not seeking. */
    int seekTarget() {
        return state == State.SEEKING ? seek_target : -1;
    }

    /** How far the current seek got, 0 to 1 (1 when not seeking). */
    double seekProgress() {
        if (state != State.SEEKING || seek_target == seek_from) {
            return 1;
        }
        return (world.getTick() - seek_from) / (double) (seek_target - seek_from);
    }

    boolean presimulating() {
        return presimulating;
    }

    /**
     * Whether the clock plays once it is where it is going: it plays, or a seek (or presimulation) under way ends in
     * playing. P toggles this.
     */
    boolean willPlay() {
        return state == State.PLAYING || (state == State.SEEKING && (presimulating ? presim_resume : resume_playing));
    }

    /** Ticks the world played since the start, first passes and re-plays alike: how much was simulated. */
    long ticksPlayed() {
        return ticks_played;
    }

    /** How many restores the rewinder did so far. */
    int restores() {
        return restores;
    }

    /** The tick the last restore went back to, or -1 before the first. */
    int lastRestoreTick() {
        return last_restore_tick;
    }

    /** The speed as the view writes it: x1/8 ... x128, MAX. */
    static @NonNull String speedText(int index) {
        double speed = SPEEDS[index];
        if (Double.isInfinite(speed)) {
            return "MAX";
        }
        return speed < 1 ? "x1/" + Math.round(1 / speed) : "x" + Math.round(speed);
    }

    /** The state as the view writes it, such as PLAYING x16, SEEKING 45%, PAUSED, ENDED, MENU. */
    @NonNull
    String stateText() {
        if (menuPaused()) {
            return "MENU (paused)";
        }
        return switch (state) {
            case PLAYING -> "PLAYING " + speedText(speed_index);
            case PAUSED -> "PAUSED";
            case SEEKING -> String.format(Locale.ROOT, "%s %d%% to %s%s%s", presimulating ? "PRESIM" : "SEEKING",
                    (int) (seekProgress() * 100), times.clock(seek_target),
                    presimulating && !pending.isEmpty() ? ", back to " + times.clock(presim_back_to) : "",
                    willPlay() ? ", then plays" : "");
            case ENDED -> "ENDED";
        };
    }

    /** The last message for the user (an error such as a failed rewind), or null once it is older than 8 s. */
    @Nullable
    String message() {
        return message != null && System.nanoTime() - message_nanos < 8_000_000_000L ? message : null;
    }

    /** How the last finished seek went: ticks, real time and ticks per second; null before the first. */
    @Nullable
    String lastSeekReport() {
        return last_seek_report;
    }

    void say(@NonNull String text) {
        message = text;
        message_nanos = System.nanoTime();
    }

    // ---------------------------------------------------------------- commands

    void play() {
        switch (state) {
            case PAUSED -> {
                state = State.PLAYING;
                debt = 0;
            }
            case SEEKING -> resumeAfterSeek(true);
            case ENDED -> say("at the recorded end: seek back to play again");
            case PLAYING -> {
            }
        }
    }

    void pause() {
        switch (state) {
            case PLAYING -> state = State.PAUSED;
            case SEEKING -> resumeAfterSeek(false);
            case PAUSED, ENDED -> {
            }
        }
    }

    void togglePause() {
        if (willPlay()) {
            pause();
        } else {
            play();
        }
    }

    /** Whether to play after the seek under way; for a presimulation, once it is back where it started. */
    private void resumeAfterSeek(boolean play) {
        resume_playing = play;
        if (presimulating) {
            presim_resume = play;
        }
    }

    void setSpeedIndex(int index) {
        speed_index = Math.clamp(index, 0, SPEEDS.length - 1);
        debt = 0;
    }

    /** The speed keys: +1 faster, -1 slower. */
    void changeSpeed(int delta) {
        setSpeedIndex(speed_index + delta);
        if (state == State.PAUSED && delta > 0) {
            play();
        }
    }

    /** Plays {@code ticks} ticks on (or back, negative), pausing first. */
    void step(int ticks) {
        int from = position(); // before pause() and seek(), which end a presimulation and a seek
        pause();
        resume_playing = false;
        seek(from + ticks);
    }

    /**
     * Seeks to {@code target} (clamped to the start and the recorded end), keeping the play or pause state. Returns
     * null, or why it cannot (a rewind the rewinder cannot do); the message is also {@link #say said}.
     */
    @Nullable
    String seek(int target) {
        pending.clear();
        presimulating = false;
        return startSeek(target);
    }

    /**
     * Simulates the whole game (to the recorded end, then back to the start, paused), so every later jump is quick:
     * --presim and the selftest's presim step.
     */
    @Nullable
    String presimulate() {
        return presimulate(0, false);
    }

    /**
     * Simulates the rest of the game to the recorded end, then comes back to {@code back_to}, playing on from there if
     * {@code resume}: the HUD's command, after which every jump is quick. A seek meanwhile cancels the way back.
     */
    @Nullable
    String presimulate(int back_to, boolean resume) {
        if (end_tick < 0) {
            say("cannot presimulate: the recorded end is unknown");
            return message;
        }
        if (rewinder instanceof NoRewind) {
            // it could get to the end, but never back: the rest of the game would be lost to the view
            say("cannot presimulate: rewind is " + rewinder.describe(times));
            return message;
        }
        pending.clear();
        listener.presimulationStarts();
        // presimulating already: the seek out's restore, if it needs one, belongs to it (and ends it if it fails)
        presimulating = true;
        presim_back_to = back_to;
        presim_resume = resume;
        String error = startSeek(end_tick);
        if (error == null) {
            pending.add(back_to);
            resume_playing = false;
        }
        return error;
    }

    /**
     * Stops a presimulation on its way out and goes back to where it started from, as it would have at the end; false
     * when there is none to stop (none under way, or on its way back already).
     */
    boolean stopPresimulation() {
        if (!presimulating || pending.isEmpty()) {
            return false;
        }
        pending.clear();
        listener.presimulationTurns();
        // still presimulating: the way back, as from the end
        if (startSeek(presim_back_to) == null) {
            resume_playing = presim_resume;
        }
        return true;
    }

    private @Nullable String startSeek(int target) {
        target = Math.clamp(target, 0, limit);
        int tick = world.getTick();
        if (state != State.SEEKING) {
            resume_playing = state == State.PLAYING;
        }
        boolean restore = target < tick || latestCheckpointAtOrBefore(target) > tick;
        // a seek that takes over from one that restored still ends a restore: the view re-picks after it
        seek_restored = state == State.SEEKING && seek_restored;
        if (restore) {
            listener.beforeRestore();
            try {
                int at = rewinder.restoreAtOrBefore(world, target);
                seek_restored = true;
                restores++;
                last_restore_tick = at;
                listener.restored(at);
            } catch (RuntimeException e) {
                // the rewinder left the world as it was: a seek under way goes on
                String why = e.getMessage() == null ? e.toString() : e.getMessage();
                say("cannot seek to " + times.clock(target) + ": " + why);
                pending.clear();
                presimulating = false;
                listener.restoreFailed();
                return message;
            }
        }
        seek_target = target;
        seek_from = world.getTick();
        seek_start_nanos = System.nanoTime();
        state = State.SEEKING;
        return null;
    }

    private int latestCheckpointAtOrBefore(int tick) {
        int best = -1;
        for (int checkpoint : rewinder.ticks()) {
            if (checkpoint <= tick) {
                best = Math.max(best, checkpoint);
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- the frame

    /** One rendered frame: plays the ticks this frame owes, within its budget. */
    void frame(long now_nanos) {
        double dt = last_frame_nanos == 0 ? 0 : Math.min((now_nanos - last_frame_nanos) / 1e9, MAX_FRAME_SECONDS);
        last_frame_nanos = now_nanos;
        int ran = 0;
        if (!menuPaused()) {
            switch (state) {
                case PLAYING -> ran = playFrame(dt);
                case SEEKING -> {
                    ran = runTicks(seek_target - world.getTick(), FAST_BUDGET_NANOS);
                    if (world.getTick() >= seek_target) {
                        finishSeek();
                    }
                }
                case PAUSED, ENDED -> debt = 0;
            }
        }
        if (ran > 0) {
            // ticks can bind textures (HeightMap.editHeight): the render context's cache is stale now
            resetRenderContext();
        }
        measure(now_nanos, ran);
    }

    /**
     * Forgets the GL state the render context caches (bound textures and the like), as the renderer does after
     * loading: after ticks, which may bind textures, and after anything else that makes GL calls outside drawing.
     */
    static void resetRenderContext() {
        ((GLRenderContext) Renderer.getRenderer().getRenderContext()).reset();
    }

    private int playFrame(double dt) {
        double speed = SPEEDS[speed_index];
        if (Double.isInfinite(speed)) {
            debt = 0;
            return endIfAtLimit(runTicks(Integer.MAX_VALUE, FAST_BUDGET_NANOS));
        }
        double owed = dt * TPS * speed;
        // at most about two frames' worth: what the budget could not play is dropped, not made up for later
        debt = Math.min(debt + owed, Math.max(2 * owed, 1));
        int want = (int) debt;
        int ran = runTicks(want, PLAY_BUDGET_NANOS);
        debt -= ran;
        return endIfAtLimit(ran);
    }

    private int endIfAtLimit(int ran) {
        if (world.getTick() >= limit) {
            state = State.ENDED;
            debt = 0;
        }
        return ran;
    }

    /** Plays up to {@code count} ticks, stopping at the limit or when the budget is spent; returns how many. */
    private int runTicks(int count, long budget_nanos) {
        if (count <= 0) {
            return 0;
        }
        long deadline = System.nanoTime() + budget_nanos;
        int ran = 0;
        while (ran < count && world.getTick() < limit) {
            // every AI reads it, and the recorded game had its AIs on (GUIRoot's developer key DEBUG_TOGGLE_AI flips
            // it in other games; it ignores developer keys while watching)
            Globals.run_ai = true;
            world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
            ran++;
            ticks_played++;
            int tick = world.getTick();
            if (tick > frontier) {
                frontier = tick;
                listener.frontier(tick);
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        return ran;
    }

    private void finishSeek() {
        int ticks = world.getTick() - seek_from;
        double seconds = (System.nanoTime() - seek_start_nanos) / 1e9;
        if (ticks > 0) {
            last_seek_report = String.format(Locale.ROOT, "%s to %s: %d ticks in %.1f s = %.0f ticks/s (x%.1f)",
                    times.clock(seek_from), times.clock(world.getTick()), ticks, seconds, ticks / Math.max(seconds,
                            1e-9), ticks / Math.max(seconds, 1e-9) / TPS);
        }
        boolean restored = seek_restored;
        seek_restored = false;
        debt = 0;
        state = world.getTick() >= limit ? State.ENDED : resume_playing ? State.PLAYING : State.PAUSED;
        Integer next = pending.poll();
        if (next == null) {
            presimulating = false;
            listener.seekDone(restored);
            return;
        }
        // a presimulation at the recorded end: the way back, which plays on as before it (or not)
        listener.presimulationTurns();
        if (startSeek(next) == null && pending.isEmpty()) {
            resume_playing = presim_resume;
        }
    }

    private void measure(long now_nanos, int ran) {
        frame_head = (frame_head + 1) % frame_nanos.length;
        frame_nanos[frame_head] = now_nanos;
        frame_ticks[frame_head] = ran;
        long ticks = 0;
        long oldest = now_nanos;
        for (int i = 0; i < frame_nanos.length; i++) {
            int index = (frame_head - i + frame_nanos.length) % frame_nanos.length;
            if (frame_nanos[index] == 0 || now_nanos - frame_nanos[index] > SPEED_WINDOW_NANOS) {
                break;
            }
            oldest = frame_nanos[index];
            if (i > 0) {
                ticks += frame_ticks[(index + 1) % frame_nanos.length];
            }
        }
        double seconds = (now_nanos - oldest) / 1e9;
        effective_speed = seconds > .2 ? ticks / seconds / TPS : effective_speed;
    }
}
