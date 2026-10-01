package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * The rewind checkpoints of one live world: deep copies of it ({@link Copier}) taken as it first plays through its
 * ticks, each never ticked, and restored by copying one back into the same live World, Players and HeightMap.
 *
 * <p>A checkpoint holds the world and the AI log handles bound to it (AiLog's table: the AIs hold their handle, and
 * the harness and the viewer find it through {@link AiLog#peek}); a restore binds the restored handles in their place.
 * The handles' writers are shared, so name them in {@code shared}.
 *
 * <p>Memory: each checkpoint's size is estimated from the shallow sizes of the objects it made (RewindCheck measured
 * the estimate against the heap). When the checkpoints together would take more than the budget, every other one is
 * dropped (never the first) and the interval doubles, so they stay evenly spread over the game at any length.
 *
 * <p>When a checkpoint cannot be taken (an object the copier has no rule for: {@link Copier.CopyException}), the first
 * one throws (no rewind at all), and a later one stops taking checkpoints: those taken so far stay usable, and
 * {@link #describe()} and {@link #failure()} say why.
 */
final class Checkpoints implements Rewinder {
    private static final Logger logger = Logger.getLogger(Checkpoints.class.getName());
    private static final String[] ROOTS = {"world"};
    private static final String[] ROOTS_WITH_LOGS = {"world", "aiLogHandles"};
    private static final long MEGABYTE = 1024 * 1024;

    /** AiLog's table of handles, bound to one world at a time. */
    private static final Field AI_LOG_HANDLES;

    static {
        try {
            AI_LOG_HANDLES = AiLog.class.getDeclaredField("handles");
            AI_LOG_HANDLES.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The world after {@code tick} was played, and the AI log handles bound to it then (null if none were), with what
     * taking it cost.
     */
    private record Checkpoint(int tick, @NonNull World world, AiLog @Nullable [] handles, long bytes, int objects,
                              long nanos) {
    }

    private final @NonNull World live;
    private final Copier.@NonNull Rules rules;
    private final long budget;
    /** Ascending ticks; the first is never dropped. */
    private final @NonNull List<Checkpoint> checkpoints = new ArrayList<>();
    private int interval;
    private long bytes;
    private @Nullable String failure;
    private long copy_nanos;
    private int copies;
    private long last_restore_nanos = -1;

    /**
     * Takes the first checkpoint at once: construct it right after the AIs are made, before the first tick. A
     * checkpoint is then due every {@code interval_ticks}; together they may take {@code budget_megabytes}.
     * {@code shared} are objects outside the world that it holds and every checkpoint must share as they are (the
     * Track, the AI log writers): each element itself, not what it refers to, so pass every writer, not a list of
     * them. Throws {@link Copier.CopyException} if the world cannot be copied, and IllegalStateException if this JVM
     * lacks the copier's flags.
     */
    Checkpoints(@NonNull World live, int interval_ticks, int budget_megabytes, @NonNull Collection<?> shared) {
        if (interval_ticks <= 0 || budget_megabytes <= 0) {
            throw new IllegalArgumentException("interval and budget must be positive");
        }
        this.live = live;
        this.interval = interval_ticks;
        this.budget = budget_megabytes * MEGABYTE;
        this.rules = new Copier.Rules(live, shared);
        Checkpoint first = take();
        checkpoints.add(first);
        bytes = first.bytes();
    }

    @Override
    public void atFrontier(@NonNull World world) {
        requireLive(world);
        int tick = world.getTick();
        Checkpoint last = checkpoints.getLast();
        if (failure != null || tick <= last.tick() || tick % interval != 0) {
            return;
        }
        // the next one will be about as big as the last: thin first rather than copy what is dropped at once
        while (bytes + last.bytes() > budget && thin()) {
            // thinned
        }
        if (tick % interval != 0) {
            return;
        }
        Checkpoint checkpoint;
        try {
            checkpoint = take();
        } catch (Copier.CopyException e) {
            failure = "no checkpoints after " + clock(tick) + ": " + e.getMessage();
            logger.warning(failure);
            return;
        }
        checkpoints.add(checkpoint);
        bytes += checkpoint.bytes();
        while (bytes > budget && thin()) {
            // thinned
        }
    }

    @Override
    public int restoreAtOrBefore(@NonNull World world, int tick) {
        requireLive(world);
        Checkpoint checkpoint = checkpoints.getFirst();
        for (Checkpoint c : checkpoints) {
            if (c.tick() <= tick) {
                checkpoint = c;
            }
        }
        rules.checkFixed();
        long start = System.nanoTime();
        IdentityHashMap<Object, Object> anchors = new IdentityHashMap<>();
        anchors.put(checkpoint.world(), live);
        anchors.put(checkpoint.world().getHeightMap(), live.getHeightMap());
        Player[] from = checkpoint.world().getPlayers();
        Player[] to = live.getPlayers();
        if (from.length != to.length) {
            throw new IllegalStateException("the checkpoint has " + from.length + " players, the world " + to.length);
        }
        for (int slot = 0; slot < from.length; slot++) {
            anchors.put(from[slot], to[slot]);
        }
        AiLog[] handles = checkpoint.handles();
        Object[] roots = handles == null ? new Object[]{checkpoint.world()} : new Object[]{checkpoint.world(), handles};
        Copier.Result result = Copier.restore(rules, anchors, handles == null ? ROOTS : ROOTS_WITH_LOGS, roots);
        if (handles != null && AiLog.peek(live, 0) != null) {
            bindHandles((AiLog[]) result.roots()[1]);
        }
        if (live.getTick() != checkpoint.tick()) {
            throw new IllegalStateException("restored tick " + live.getTick() + ", not " + checkpoint.tick());
        }
        last_restore_nanos = System.nanoTime() - start;
        return checkpoint.tick();
    }

    @Override
    public int @NonNull [] ticks() {
        return checkpoints.stream().mapToInt(Checkpoint::tick).toArray();
    }

    /** Such as "42 checkpoints every 60 s, 1.1 GB of 2.7 GB", and why there are no more if one failed. */
    @Override
    public @NonNull String describe() {
        int count = checkpoints.size();
        String how_many = count + (count == 1 ? " checkpoint" : " checkpoints");
        String text = how_many + " every " + seconds(interval) + ", " + size(bytes) + " of " + size(budget);
        return failure == null ? text : text + "; " + shorten(failure, 120);
    }

    // ---------------------------------------------------------------- for RewindCheck and the view

    /** Why checkpoints stopped, or null while they go on. */
    @Nullable
    String failure() {
        return failure;
    }

    /** The estimated size of all checkpoints, in bytes. */
    long bytes() {
        return bytes;
    }

    /** The ticks between checkpoints now. */
    int interval() {
        return interval;
    }

    /** The checkpoints taken so far, dropped ones included. */
    int taken() {
        return copies;
    }

    /** The mean time a checkpoint took to copy, in milliseconds. */
    double meanCopyMillis() {
        return copies == 0 ? 0 : copy_nanos / 1e6 / copies;
    }

    /** How long the last restore took, in milliseconds, or -1. */
    double lastRestoreMillis() {
        return last_restore_nanos < 0 ? -1 : last_restore_nanos / 1e6;
    }

    /** The estimated size and object count of the checkpoint at {@code tick}, or null if there is none. */
    long @Nullable [] sizeAt(int tick) {
        for (Checkpoint c : checkpoints) {
            if (c.tick() == tick) {
                return new long[]{c.bytes(), c.objects(), c.nanos()};
            }
        }
        return null;
    }

    /**
     * A copy of the live world as a checkpoint would take it, not kept: for measuring the size estimate against the
     * heap. {@code by_class} adds objects and bytes by class to the result.
     */
    Copier.@NonNull Result measure(@NonNull World world, boolean by_class) {
        requireLive(world);
        AiLog[] handles = boundHandles();
        Object[] roots = handles == null ? new Object[]{live} : new Object[]{live, handles};
        return Copier.snapshot(rules, handles == null ? ROOTS : ROOTS_WITH_LOGS, roots, by_class);
    }

    // ---------------------------------------------------------------- inside

    private @NonNull Checkpoint take() {
        AiLog[] handles = boundHandles();
        Object[] roots = handles == null ? new Object[]{live} : new Object[]{live, handles};
        Copier.Result result = Copier.snapshot(rules, handles == null ? ROOTS : ROOTS_WITH_LOGS, roots, false);
        copy_nanos += result.nanos();
        copies++;
        return new Checkpoint(live.getTick(), (World) result.roots()[0],
                handles == null ? null : (AiLog[]) result.roots()[1], result.bytes(), result.objects(),
                result.nanos());
    }

    /**
     * Drops every other checkpoint but the first and doubles the interval; false when only the first is left. The
     * checkpoints after the first are at multiples of the interval, so those left are at multiples of the new one.
     */
    private boolean thin() {
        if (checkpoints.size() <= 1 || interval > Integer.MAX_VALUE / 2) {
            return false;
        }
        int doubled = interval * 2;
        for (int i = checkpoints.size() - 1; i > 0; i--) {
            if (checkpoints.get(i).tick() % doubled != 0) {
                checkpoints.remove(i);
            }
        }
        interval = doubled;
        bytes = 0;
        for (Checkpoint c : checkpoints) {
            bytes += c.bytes();
        }
        logger.info("rewind: " + describe());
        return true;
    }

    /** AiLog's handles if they are bound to the live world, else null. */
    private AiLog @Nullable [] boundHandles() {
        if (AiLog.peek(live, 0) == null) {
            return null;
        }
        try {
            return (AiLog[]) AI_LOG_HANDLES.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replaces AiLog's handles: on the simulation thread, the only one that uses AiLog while a world plays. */
    private static void bindHandles(AiLog @NonNull [] handles) {
        try {
            AI_LOG_HANDLES.set(null, handles);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireLive(@NonNull World world) {
        if (world != live) {
            throw new IllegalArgumentException("checkpoints of another world");
        }
    }

    private static @NonNull String seconds(int ticks) {
        return ticks % GameTime.TICKS_PER_SECOND == 0 ? ticks / GameTime.TICKS_PER_SECOND + " s" : ticks + " ticks";
    }

    private static @NonNull String clock(int tick) {
        int s = tick / GameTime.TICKS_PER_SECOND;
        if (s >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static @NonNull String size(long bytes) {
        if (bytes >= 1000 * MEGABYTE) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * MEGABYTE));
        }
        return (bytes + MEGABYTE / 2) / MEGABYTE + " MB";
    }

    private static @NonNull String shorten(@NonNull String text, int length) {
        return text.length() <= length ? text : text.substring(0, length - 3) + "...";
    }
}
