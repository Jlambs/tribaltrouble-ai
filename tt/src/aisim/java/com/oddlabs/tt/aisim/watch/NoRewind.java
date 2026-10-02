package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.landscape.World;
import org.jspecify.annotations.NonNull;

/**
 * A rewinder that cannot rewind: it has only the start (tick 0) and restores nothing, so the watch view seeks forward
 * only. Used until the checkpoint store is available, and when it cannot be made for a world ({@code reason} says
 * why).
 */
final class NoRewind implements Rewinder {
    private final @NonNull String reason;

    NoRewind(@NonNull String reason) {
        this.reason = reason;
    }

    @Override
    public void atFrontier(@NonNull World live) {
    }

    @Override
    public int restoreAtOrBefore(@NonNull World live, int tick) {
        throw new UnsupportedOperationException("rewind is off (" + reason + ")");
    }

    @Override
    public int @NonNull [] ticks() {
        return new int[]{0};
    }

    @Override
    public @NonNull String describe(@NonNull Times times) {
        return "off (" + reason + ")";
    }
}
