package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.landscape.World;
import org.jspecify.annotations.NonNull;

/**
 * Rewinding a world: checkpoints taken as the world first plays through its ticks, and restored into the same live
 * World object, so everything that shows the world (the viewer, the renderer, the cameras) stays bound to it. A
 * restore replaces all of the world's state below its anchors (the World, its Players, its HeightMap), so references
 * the view kept to units, buildings or other world objects point into the old state afterwards and must be dropped.
 *
 * <p>Every call on the simulation thread, between two {@code world.tick} calls, never during one.
 */
interface Rewinder {
    /**
     * Called after each tick the live world plays for the first time (the frontier: no tick after it was played yet);
     * takes a checkpoint when one is due. {@code world.getTick()} is the tick just played.
     */
    void atFrontier(@NonNull World live);

    /**
     * Restores into {@code live} the latest checkpoint at or before {@code tick} and returns its tick; the caller then
     * plays on to {@code tick}. There is always the checkpoint of tick 0, taken before the first tick.
     */
    int restoreAtOrBefore(@NonNull World live, int tick);

    /** The ticks that have a checkpoint, ascending. */
    int @NonNull [] ticks();

    /** A short status for the view, such as "42 checkpoints every 4:00, 1.1 GB", its times quoted by {@code times}. */
    @NonNull
    String describe(@NonNull Times times);
}
