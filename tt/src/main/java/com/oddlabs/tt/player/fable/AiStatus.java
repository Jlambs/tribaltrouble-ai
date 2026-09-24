package com.oddlabs.tt.player.fable;

import org.jspecify.annotations.NonNull;

/** Implemented by AIs that can describe their state in one line for the developer match runner. */
public interface AiStatus {
    @NonNull
    String debugStatus();
}
