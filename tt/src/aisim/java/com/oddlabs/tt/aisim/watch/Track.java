package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.animation.Animated;
import com.oddlabs.tt.landscape.World;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;

/**
 * The world's checksum at every game second, as the world first played it, and every later pass over the same seconds
 * (after a rewind) checked against it: a rewind that does not continue exactly shows up here at the first second it
 * differs. Kept outside the world and shared by every checkpoint of it (the copier must share it, never copy it).
 */
final class Track {
    private int[] checksums = new int[3600];
    /** Seconds 0..frontier have a first-pass checksum. */
    private int frontier = -1;
    private int first_mismatch = -1;
    private int mismatches;

    /** Records {@code checksum} at {@code second}: the first pass stores it, a later pass compares with it. */
    void sample(int second, int checksum) {
        if (second > frontier) {
            if (second >= checksums.length) {
                checksums = Arrays.copyOf(checksums, Math.max(second + 1, checksums.length * 2));
            }
            checksums[second] = checksum;
            frontier = second;
        } else if (checksums[second] != checksum) {
            mismatches++;
            if (first_mismatch < 0 || second < first_mismatch) {
                first_mismatch = second;
            }
        }
    }

    /** The last second with a first-pass checksum, or -1. */
    int frontier() {
        return frontier;
    }

    /** The first-pass checksum at {@code second}, which must be at most {@link #frontier()}. */
    int checksum(int second) {
        assert second >= 0 && second <= frontier;
        return checksums[second];
    }

    /** The earliest second a later pass differed from the first, or -1 if none ever did. */
    int firstMismatch() {
        return first_mismatch;
    }

    /** How many later-pass samples differed from the first pass. */
    int mismatches() {
        return mismatches;
    }

    /**
     * Samples the world's checksum into a track once per game second, where the harness's GameRecorder samples it:
     * register it on the world's real-time animation manager right after the world is made and before the AIs, so it
     * runs first in each tick and its values equal the checksums of the recorded census lines at the same seconds.
     * It is part of the world (a checkpoint copies it along with its world), its track is not. Like the GameRecorder
     * it only reads, and keeps the default no-op checksum.
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
            int tick = world.getTick();
            if (tick % GameTime.TICKS_PER_SECOND == 0) {
                track.sample(tick / GameTime.TICKS_PER_SECOND, world.getChecksum());
            }
        }
    }
}
