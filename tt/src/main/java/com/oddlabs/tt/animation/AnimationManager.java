package com.oddlabs.tt.animation;

import com.oddlabs.event.Deterministic;
import com.oddlabs.net.MonotoneTimeManager;
import com.oddlabs.net.NetworkSelector;
import com.oddlabs.tt.event.LocalEventQueue;
import com.oddlabs.tt.steam.SteamManager;
import com.oddlabs.tt.form.QuitForm;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.global.Headless;
import com.oddlabs.tt.global.Settings;
import com.oddlabs.tt.gui.GUI;
import com.oddlabs.tt.pathfinder.PathFinder;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.util.StatCounter;
import com.oddlabs.tt.util.StateChecksum;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Logger;

public final class AnimationManager {
    private static final Logger logger = Logger.getLogger(AnimationManager.class.getName());
    public static final long ANIMATION_MILLISECONDS_PER_TICK = 20L;
    public static final long ANIMATION_MILLISECONDS_PER_PRECISION_TICK = ANIMATION_MILLISECONDS_PER_TICK / 5;
    public static final float ANIMATION_SECONDS_PER_TICK = ANIMATION_MILLISECONDS_PER_TICK / 1000f;
    public static final float ANIMATION_SECONDS_PER_PRECISION_TICK = ANIMATION_MILLISECONDS_PER_PRECISION_TICK / 1000f;
    private static final long ANIMATION_MILLISECONDS_PER_CHECKSUM = TimeUnit.SECONDS.toMillis(2);
    public static final long MAX_STEP_MILLIS = TimeUnit.SECONDS.toMillis(30);

    public static final StatCounter pathfindsPerTick = new StatCounter(100);

    private static final StatCounter frameTime = new StatCounter(10);
    private static final MonotoneTimeManager timeSource = new MonotoneTimeManager(() -> TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime()));

    private static long current_time;
    private static long last_frame_time;
    private static long execution_time = 0;
    private static float execution_time_precision = 0;
    private static long time_warp;
    private static boolean time_stopped;
    private static boolean time_frozen;
    private static long frozen_start_time;
    private static long frozen_start_time_warped;
    private static long checksum_millisecond_counter;
    private static boolean checksum_complain = true;

    private final Set<@NonNull Animated> animations = new CopyOnWriteArraySet<>();
    private final Set<@NonNull Animated> deleted_animations = new CopyOnWriteArraySet<>();

    // Headless (Headless.ENABLED) the animations are kept without copy-on-write, which copies the whole array on every
    // register and remove: in the same order and with the same rules (see HeadlessAnimations), so the same
    // animations run in the same order.
    private final @Nullable HeadlessAnimations headless = Headless.ENABLED ? new HeadlessAnimations() : null;

    private int tick;

    /*
        private static int[] medium = new int[]{};
        private static int[] big = new int[]{
            11910,
                31936,
                61978,
                82012,
                112072,
                152166,
                202388,
                262714,
                333179,
                403776,
                484491,
                575347,
                666317,
                768888,
                873315,
                979754,
                1088180,
                1198760,
                1311452,
                1426295,
                1542397,
                1658489,
                1776352,
                1894373,
                2012758,
                2132205,
                2253154,
                2374883
        };
    */
    static {
        current_time = getSystemTime();
        last_frame_time = current_time;
        freezeTime();
    }

    public static void warpTime(long warp_delta) {
        time_warp += warp_delta;
    }

    public static long getSystemTime() {
        return time_frozen ? frozen_start_time_warped : timeSource.getMillis() + time_warp;
    }

    public static void toggleTimeStop() {
        if (time_frozen)
            unfreezeTime();
        else
            freezeTime();
        time_stopped = time_frozen;
        logger.config("time_stopped = " + time_stopped);
    }

    private static void unfreezeTime() {
        if (!time_frozen)
            return;
        time_frozen = false;
        time_warp -= timeSource.getMillis() - frozen_start_time;
    }

    public static void freezeTime() {
        if (time_frozen)
            return;
        frozen_start_time_warped = getSystemTime();
        time_frozen = true;
        frozen_start_time = timeSource.getMillis();
    }

    public static void runGameLoop(@NonNull NetworkSelector network, @NonNull GUI gui, boolean grab_frames) {
        Renderer.getLocalInput().checkMagicKeys();
        if (time_frozen && !time_stopped)
            unfreezeTime();
        if (grab_frames) {
            current_time += Settings.getSettings().frame_grab_milliseconds_per_frame;
        } else {
            current_time = getSystemTime();
        }
        long time_diff = current_time - last_frame_time;
        last_frame_time = current_time;
        Deterministic deterministic = LocalEventQueue.getQueue().getDeterministic();
        if (time_diff > MAX_STEP_MILLIS && !Objects.requireNonNull(deterministic).isPlayback()) {
            logger.warning("Skipping large time diff: " + time_diff + " ms.");
            time_diff = 0;
        }

        frameTime.updateAbsolute(time_diff);
        execution_time_precision += frameTime.getAveragePerUpdate();
        Objects.requireNonNull(deterministic).setEnabled(true);
        while (execution_time_precision >= ANIMATION_MILLISECONDS_PER_PRECISION_TICK && !Renderer.isFinished()) {
            /*
            // Used for replaying warp control in clientload
            int tick = LocalEventQueue.getQueue().getHighPrecisionManager().getTick();
            for (int i = 0; i < big.length; i++) {
                if (big[i] == tick)
                    warpTime(com.oddlabs.tt.input.KeyboardInput.LARGE_WARP);
            }
            for (int i = 0; i < medium.length; i++) {
                if (medium[i] == tick)
                    warpTime(com.oddlabs.tt.input.KeyboardInput.MEDIUM_WARP);
            }
            */
            execution_time_precision -= ANIMATION_MILLISECONDS_PER_PRECISION_TICK;
            execution_time += ANIMATION_MILLISECONDS_PER_PRECISION_TICK;
            LocalEventQueue.getQueue().tickHighPrecision(ANIMATION_SECONDS_PER_PRECISION_TICK);
            while (execution_time >= ANIMATION_MILLISECONDS_PER_TICK && !Renderer.isFinished()) {
                network.tick();

                Renderer.getLocalInput().poll(gui.getGUIRoot());
                if (deterministic.log(Renderer.getRenderer().getWindow().isOpen()
                        && Renderer.getRenderer().getWindow().isCloseRequested())) {
                    Renderer.getRenderer().getWindow().setCloseRequested(false);
                    if (gui.getGUIRoot().isShowingQuitForm()) {
                        Renderer.shutdown();
                    } else {
                        gui.getGUIRoot().addModalForm(new QuitForm(gui.getGUIRoot()));
                    }
                }
                pathfindsPerTick.updateAbsolute(PathFinder.stat_pathfinder_per_frame);
                PathFinder.stat_pathfinder_per_frame = 0;
                LocalEventQueue.getQueue().tickLowPrecision(ANIMATION_SECONDS_PER_TICK);
                execution_time -= ANIMATION_MILLISECONDS_PER_TICK;
                checksum_millisecond_counter += ANIMATION_MILLISECONDS_PER_TICK;
                if (checksum_millisecond_counter >= ANIMATION_MILLISECONDS_PER_CHECKSUM) {
                    SteamManager.runCallbacks();
                    checksum_millisecond_counter -= ANIMATION_MILLISECONDS_PER_CHECKSUM;
                    int checksum = LocalEventQueue.getQueue().computeChecksum();
                    int logged_checksum = deterministic.log(checksum);
                    if (checksum != logged_checksum && checksum_complain) {
                        logger.severe(
                                "********** ERROR: Checksum mismatch at tick " + LocalEventQueue.getQueue().getHighPrecisionManager().getTick() + " | checksum = " + checksum + " | logged_checksum = " + logged_checksum + " **********");
                        checksum_complain = false;
                    }
                }
                if (!Globals.frustum_freeze) {
                    gui.pickHover();
                }
            }
            // Only for debugging
            /*
            if (LocalEventQueue.getQueue().getHighPrecisionManager().getTick() < 2467619 + 10000)
            {
            	execution_time_precision += ANIMATION_MILLISECONDS_PER_PRECISION_TICK;
            	freezeTime();
            }

            if (LocalEventQueue.getQueue().getHighPrecisionManager().getTick() > 2529461 + 2000) {
            	logger.severe("FORCE QUIT: getHighPrecisionManager().getTick() = " + LocalEventQueue.getQueue().getHighPrecisionManager().getTick());
            	com.oddlabs.tt.Main.shutdown();
            }*/
        }
        deterministic.setEnabled(false);
    }

    public int getTick() {
        return tick;
    }

    public void registerAnimation(@NonNull Animated anim) {
        if (headless != null) {
            headless.register(anim);
            return;
        }
        deleted_animations.remove(anim);
        animations.add(anim);
    }

    public void removeAnimation(@NonNull Animated anim) {
        if (headless != null) {
            headless.remove(anim);
            return;
        }
        if (animations.contains(anim)) {
            deleted_animations.add(anim);
        }
    }

    private void flushAnimations() {
        animations.removeAll(deleted_animations);
        deleted_animations.clear();
    }

    public void updateChecksum(@NonNull StateChecksum checksum) {
        if (headless != null) {
            headless.flush();
            for (Animated anim : headless.snapshot()) {
                anim.updateChecksum(checksum);
            }
            return;
        }
        flushAnimations();
        animations.forEach(anim -> anim.updateChecksum(checksum));
    }

    public void runAnimations(float t) {
        tick++;
        if (headless != null) {
            headless.run(t);
            return;
        }
        flushAnimations();
        Predicate<Animated> notDeleted = ((Predicate<Animated>) deleted_animations::contains).negate();
        animations.stream().filter(notDeleted).forEach(a -> a.animate(t));
    }

    public void debugPrintAnimations() {
        if (headless != null) {
            headless.flush();
            for (Animated anim : headless.snapshot()) {
                logger.fine("anim = " + anim);
            }
            return;
        }
        flushAnimations();
        animations.forEach(anim -> logger.fine("anim = " + anim));
    }

    /**
     * The animations and deleted_animations sets without copy-on-write, with the same rules:
     * <ul>
     * <li>animations keep the order they were first registered in, and registering one that is there changes nothing
     * (CopyOnWriteArraySet.add);</li>
     * <li>a removed animation stays in place, marked deleted, until the next flush takes out every marked one; a
     * marked one registered again loses its mark and keeps its place;</li>
     * <li>a run animates the animations there when it starts (the copy-on-write snapshot), in order, each unless it is
     * marked deleted when its turn comes; those registered during the run wait for the next.</li>
     * </ul>
     * Membership is by equals, as in the copy-on-write sets.
     */
    private static final class HeadlessAnimations {
        private final List<@NonNull Animated> order = new ArrayList<>();
        private final Set<@NonNull Animated> members = new HashSet<>();
        private final Set<@NonNull Animated> deleted = new HashSet<>();
        /** The array run takes its snapshot into, reused from run to run; null while a run uses it. */
        private @Nullable Animated @Nullable [] run_snapshot = new Animated[0];

        void register(@NonNull Animated anim) {
            deleted.remove(anim);
            if (members.add(anim)) {
                order.add(anim);
            }
        }

        void remove(@NonNull Animated anim) {
            if (members.contains(anim)) {
                deleted.add(anim);
            }
        }

        void flush() {
            if (!deleted.isEmpty()) {
                order.removeIf(deleted::contains);
                members.removeAll(deleted);
                deleted.clear();
            }
        }

        @NonNull
        Animated @NonNull [] snapshot() {
            return order.toArray(new Animated[0]);
        }

        void run(float t) {
            flush();
            int count = order.size();
            Animated[] reusable = run_snapshot;
            run_snapshot = null; // a run inside this one (none known) takes a fresh array
            Animated[] snapshot = order.toArray(reusable != null ? reusable : new Animated[0]);
            for (int i = 0; i < count; i++) {
                Animated anim = snapshot[i];
                snapshot[i] = null; // no reference to a finished animation outlives the run
                if (deleted.isEmpty() || !deleted.contains(anim)) {
                    anim.animate(t);
                }
            }
            run_snapshot = snapshot;
        }
    }
}
