package com.oddlabs.tt.aikit;

import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.landscape.World;
import org.jspecify.annotations.NonNull;

/**
 * Game time as AIs, AI logs and game recordings count it. The world ticks {@value #TICKS_PER_SECOND} times a real
 * second at every game speed, and an AI's {@code animate} runs once per tick; the game speed sets how much game time a
 * tick covers: 0.02 s at normal speed, 0.01 slow, 0.035 fast and 0.08 ludicrous. Units move, build and fight in game
 * time, so at ludicrous a game second is 12.5 ticks, and an AI that counts ticks as time acts four times less often per
 * game second. {@link World#getGameMillis()} counts the game time itself, following every speed change.
 */
public final class GameTime {
    /** World ticks per real second at every speed; per game second only at the normal speed. */
    public static final int TICKS_PER_SECOND = (int) (1000 / AnimationManager.ANIMATION_MILLISECONDS_PER_TICK);

    private GameTime() {
    }

    /** The game time of {@code world} in seconds; at normal speed world ticks / 50. */
    public static double seconds(@NonNull World world) {
        return world.getGameMillis() / 1000.0;
    }

    /** The game time of {@code world} in milliseconds. */
    public static long millis(@NonNull World world) {
        return world.getGameMillis();
    }

    /**
     * Whether the game time of {@code world} has reached {@code millis}, counting a moment that falls between two ticks
     * as reached on the tick before it: from the last tick at or before it on.
     */
    public static boolean reached(@NonNull World world, long millis) {
        long now = world.getGameMillis();
        return now >= millis || now + world.getGameMillisPerTick() > millis;
    }

    /**
     * A clock that strikes once every {@code period} of game time: on the last tick at or before each multiple of it,
     * so a multiple that falls between two ticks strikes on the earlier one (at ludicrous speed every odd game second,
     * 40 ms early; at fast speed most). Ask {@link #due} once per tick; for example {@code new GameTime.Every(1000)}
     * strikes once a game second: on every 50th tick at normal speed, and 12 or 13 ticks apart at ludicrous.
     */
    public static final class Every {
        private final long period;
        /** The multiple of the period to strike next, in ms; 0 before the first call. */
        private long next;
        private long struck;

        /** A clock with a period of {@code period_millis} ms of game time. */
        public Every(long period_millis) {
            if (period_millis <= 0) {
                throw new IllegalArgumentException("period " + period_millis + " ms");
            }
            period = period_millis;
        }

        /**
         * Whether the clock strikes on this tick. A speed change that skips a multiple (in the game, not the harness)
         * makes it strike on the next tick.
         */
        public boolean due(@NonNull World world) {
            long now = world.getGameMillis();
            if (next == 0) {
                next = Math.max(period, (now + period - 1) / period * period);
            }
            if (!reached(world, next)) {
                return false;
            }
            struck = next;
            next += period;
            if (next <= now) {
                next = (now / period + 1) * period;
            }
            return true;
        }

        /** The multiple of the period the clock last struck for, in ms: 1000 * N for the strike of game second N. */
        public long struck() {
            return struck;
        }
    }
}
