package com.oddlabs.tt.aisim.watch;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Whether the watched game is the recorded one: every census tick the world first plays (each 30 game seconds) has the
 * world checksum the game file recorded then, and the world after its last tick has the end line's checksum. A rewind
 * that plays a tick differently from its first pass is the {@link Track}'s to find (REWIND MISMATCH).
 */
final class Verification {
    private final @Nullable Recording recording;
    private final @NonNull Track track;
    private final @NonNull Times times;
    /** The census ticks of the recording, ascending. */
    private final int @NonNull [] census_ticks;
    /** The index in census_ticks of the next to compare once the track's first pass reaches it. */
    private int next;
    private int compared;
    /** The last compared tick that matched, or -1. */
    private int verified_to = -1;
    /** The first compared tick that differed, or -1. */
    private int diverged_at = -1;
    /** Whether the end checksum was compared, and whether it matched. */
    private boolean end_compared;
    private boolean end_matched;

    /** Checks the track against {@code recording}; {@code times} quotes the ticks of the status line. */
    Verification(@Nullable Recording recording, @NonNull Track track, @NonNull Times times) {
        this.recording = recording;
        this.track = track;
        this.times = times;
        this.census_ticks = recording == null ? new int[0] : recording.censusTicks().stream().mapToInt(
                Integer::intValue).toArray();
    }

    /** Compares the census ticks the track's first pass reached since the last call: cheap, call it every tick. */
    void check() {
        if (recording == null) {
            return;
        }
        int frontier = track.frontier();
        for (; next < census_ticks.length && census_ticks[next] <= frontier; next++) {
            int tick = census_ticks[next];
            Integer recorded = recording.censusChecksum(tick);
            if (recorded == null) {
                continue;
            }
            compared++;
            if (recorded == track.checksum(tick)) {
                if (diverged_at < 0) {
                    verified_to = tick;
                }
            } else if (diverged_at < 0) {
                diverged_at = tick;
            }
        }
    }

    /** The world played its last recorded tick for the first time: compares its checksum with the end line's. */
    void checkEnd(int world_checksum) {
        if (recording == null || end_compared || recording.endChecksum() == null) {
            return;
        }
        end_compared = true;
        end_matched = recording.endChecksum() == world_checksum;
        compared++;
        if (!end_matched && diverged_at < 0) {
            diverged_at = recording.endTick();
        }
    }

    /** Checksums compared so far (census ticks and the end). */
    int compared() {
        return compared;
    }

    /** Whether the watched game is known to differ from the recording. */
    boolean diverged() {
        return diverged_at >= 0;
    }

    /** Whether a re-play after a rewind differed from the first pass. */
    boolean rewindMismatch() {
        return track.firstMismatch() >= 0;
    }

    /** The tick the watched game first differed from the recording at, or -1. */
    int divergedAt() {
        return diverged_at;
    }

    /** The status line: VERIFIED to 51:30, DIVERGED at 12:00, REWIND MISMATCH at ..., or why nothing is checked. */
    @NonNull
    String status() {
        if (recording == null) {
            return "not verified (no game file)";
        }
        StringBuilder text = new StringBuilder();
        if (diverged_at >= 0) {
            text.append("DIVERGED at ").append(times.precise(diverged_at));
        } else if (end_compared) {
            text.append("VERIFIED to the end");
        } else if (verified_to >= 0) {
            text.append("VERIFIED to ").append(times.precise(verified_to));
        } else if (census_ticks.length > 0) {
            text.append("verifying (first census at ").append(times.precise(census_ticks[0])).append(')');
        } else {
            text.append("verifying (the game file has no census checksums)");
        }
        int mismatch = track.firstMismatch();
        if (mismatch >= 0) {
            text.append(", REWIND MISMATCH at ").append(times.precise(mismatch)).append(" (").append(
                    track.mismatches()).append(track.mismatches() == 1 ? " tick)" : " ticks)");
        }
        return text.toString();
    }
}
