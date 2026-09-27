package com.oddlabs.tt.aisim;

import org.jspecify.annotations.Nullable;

/**
 * How a game ended: the {@code end} field of its result row and of its game file's end line. Only elim and timeout
 * games have a winner and count; the others are listed as failed.
 */
enum End {
    /** One team has no player left standing, by the engine's rule or by the harness's collapse rule. */
    elim,
    /** The time limit came first; the teams' strength decides the winner (Match.DECISIVE_MARGIN). */
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
    boolean normal() {
        return this == elim || this == timeout;
    }

    /** The end of a result row's {@code end} value; null for a value that names none. */
    static @Nullable End of(@Nullable Object row_end) {
        for (End end : values()) {
            if (end.name().equals(row_end)) {
                return end;
            }
        }
        return null;
    }
}
