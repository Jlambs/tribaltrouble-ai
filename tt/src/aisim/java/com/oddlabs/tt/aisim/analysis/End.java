package com.oddlabs.tt.aisim.analysis;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * How a game ended: the {@code end} field of its result row and of its game file's end line. Only elim and timeout
 * games have a winner and count; the others are listed as failed.
 */
public enum End {
    /** One team has no player left standing, by the engine's rule or by the harness's collapse rule. */
    elim,
    /**
     * The time limit came first: a draw. (Runs before timeouts were draws gave the game to a team with a strength
     * margin of 0.10 or more; their rows keep that winner.)
     */
    timeout,
    /** An exception escaped an AI or the engine while the game ran. */
    crash,
    /** The simulation stopped advancing, and the worker's watchdog ended the game. */
    hang,
    /**
     * The game could not be played as set up: an AI failed to start or to link against the engine, the worker JVM
     * died, or the harness failed. When the first games of a run all end this way, the rest would too.
     */
    error;

    /** Whether the game played to its end, so it counts and its JVM's static engine state is intact. */
    public boolean normal() {
        return this == elim || this == timeout;
    }

    /** The end of a result row's {@code end} value; null for a value that names none. */
    public static @Nullable End of(@Nullable Object row_end) {
        for (End end : values()) {
            if (end.name().equals(row_end)) {
                return end;
            }
        }
        return null;
    }

    /** Whether the game of result row {@code row} played to its end, so its worker JVM can play another. */
    public static boolean endedNormally(@NonNull Map<String, Object> row) {
        End end = of(row.get("end"));
        return end != null && end.normal();
    }
}
