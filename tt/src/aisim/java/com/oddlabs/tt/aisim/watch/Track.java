package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.animation.Animated;
import com.oddlabs.tt.landscape.World;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;

/**
 * The world's checksum at every tick, as the world first played it, and every later pass over the same ticks (after a
 * rewind) checked against it: a rewind that does not continue exactly shows up here at the first tick it differs. The
 * recorded census checksums are compared with it at their ticks ({@link Verification}). Kept outside the world and
 * shared by every checkpoint of it (the copier must share it, never copy it).
 */
final class Track {
    /** An hour of world ticks to start with: 4 bytes a tick. */
    private int[] checksums = new int[180_000];
    /** Ticks 0..frontier have a first-pass checksum. */
    private int frontier = -1;
    private int first_mismatch = -1;
    private int mismatches;

    /** Records {@code checksum} at {@code tick}: the first pass stores it, a later pass compares with it. */
    void sample(int tick, int checksum) {
        if (tick > frontier) {
            if (tick >= checksums.length) {
                checksums = Arrays.copyOf(checksums, Math.max(tick + 1, checksums.length * 2));
            }
            checksums[tick] = checksum;
            frontier = tick;
        } else if (checksums[tick] != checksum) {
            mismatches++;
            if (first_mismatch < 0 || tick < first_mismatch) {
                first_mismatch = tick;
            }
        }
    }

    /** The last tick with a first-pass checksum, or -1. */
    int frontier() {
        return frontier;
    }

    /** The first-pass checksum at {@code tick}, which must be at most {@link #frontier()}. */
    int checksum(int tick) {
        assert tick >= 0 && tick <= frontier;
        return checksums[tick];
    }

    /** The earliest tick a later pass differed from the first, or -1 if none ever did. */
    int firstMismatch() {
        return first_mismatch;
    }

    /** How many later-pass ticks differed from the first pass. */
    int mismatches() {
        return mismatches;
    }

    /**
     * Samples the world's checksum into a track on every tick, where the harness's GameRecorder takes its census:
     * register it on the world's real-time animation manager right after the world is made and before the AIs, so it
     * runs first in each tick and its values equal the checksums of the recorded census lines at the same ticks. It is
     * part of the world (a checkpoint copies it along with its world), its track is not. Like the GameRecorder it only
     * reads, and keeps the default no-op checksum.
     */
    static final class Probe implements Animated {
        private final @NonNull World world;
        private final @NonNull Track track;

        Probe(@NonNull World world, @NonNull Track track) {
            this.world = world;
            this.track = track;
        }

        @Override
        public void animate(float t) {
            track.sample(world.getTick(), world.getChecksum());
        }
    }
}
