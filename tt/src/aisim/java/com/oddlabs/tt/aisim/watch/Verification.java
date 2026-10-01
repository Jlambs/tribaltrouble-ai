package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.GameTime;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Whether the watched game is the recorded one: every census second the world first plays (each 30 s) has the world
 * checksum the game file recorded then, and the world after its last tick has the end line's checksum. A rewind that
 * plays a second differently from its first pass is the {@link Track}'s to find (REWIND MISMATCH).
 */
final class Verification {
    /** The recorder's census period in game seconds: the seconds with a checksum to compare. */
    private static final int CENSUS_SECONDS = 30;

    private final @Nullable Recording recording;
    private final @NonNull Track track;
    /** The next second to compare once the track's first pass reaches it. */
    private int next_second = CENSUS_SECONDS;
    private int compared;
    /** The last compared second that matched, or -1. */
    private int verified_to = -1;
    /** The first compared second that differed, or -1. */
    private int diverged_at = -1;
    /** Whether the end checksum was compared, and whether it matched. */
    private boolean end_compared;
    private boolean end_matched;

    Verification(@Nullable Recording recording, @NonNull Track track) {
        this.recording = recording;
        this.track = track;
    }

    /** Compares the census seconds the track's first pass reached since the last call. */
    void check() {
        if (recording == null) {
            return;
        }
        int frontier = track.frontier();
        for (; next_second <= frontier; next_second += CENSUS_SECONDS) {
            Integer recorded = recording.censusChecksum(next_second);
            if (recorded == null) {
                continue;
            }
            compared++;
            if (recorded == track.checksum(next_second)) {
                if (diverged_at < 0) {
                    verified_to = next_second;
                }
            } else if (diverged_at < 0) {
                diverged_at = next_second;
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
            diverged_at = recording.endTick() / GameTime.TICKS_PER_SECOND;
        }
    }

    /** Checksums compared so far (census seconds and the end). */
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

    /** The game second the watched game first differed from the recording, or -1. */
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
            text.append("DIVERGED at ").append(Times.clock(diverged_at * GameTime.TICKS_PER_SECOND));
        } else if (end_compared) {
            text.append("VERIFIED to the end");
        } else if (verified_to >= 0) {
            text.append("VERIFIED to ").append(Times.clock(verified_to * GameTime.TICKS_PER_SECOND));
        } else {
            text.append("verifying (first census at 0:30)");
        }
        int mismatch = track.firstMismatch();
        if (mismatch >= 0) {
            text.append(", REWIND MISMATCH at ").append(Times.clock(mismatch * GameTime.TICKS_PER_SECOND)).append(
                    " (").append(track.mismatches()).append(" s)");
        }
        return text.toString();
    }
}
