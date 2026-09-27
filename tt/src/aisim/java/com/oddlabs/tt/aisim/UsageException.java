package com.oddlabs.tt.aisim;

import org.jspecify.annotations.NonNull;

/** A mistake in the command line or a refused request: {@link Aisim#main} prints "aisim: " + message and exits 2. */
public final class UsageException extends RuntimeException {
    public UsageException(@NonNull String message) {
        super(message);
    }
}
