package com.oddlabs.tt.aikit;

import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.landscape.World;
import org.jspecify.annotations.NonNull;

/**
 * Game time as AIs, AI logs and game recordings count it: world ticks, {@value #TICKS_PER_SECOND} to a game second.
 * An AI's {@code animate} runs once per tick. At the normal game speed a game second takes a real second.
 */
public final class GameTime {
    public static final int TICKS_PER_SECOND = (int) (1000 / AnimationManager.ANIMATION_MILLISECONDS_PER_TICK);

    private GameTime() {
    }

    /** The game time of {@code world} in seconds. */
    public static double seconds(@NonNull World world) {
        return world.getTick() / (double) TICKS_PER_SECOND;
    }
}
