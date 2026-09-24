package com.oddlabs.tt.player.fable;

import org.jspecify.annotations.NonNull;

import java.util.function.Supplier;

/** Decision log for one AI player, printed only when {@link AiDevOptions#isDebug()} is on. */
public final class AiLog {
    private final @NonNull String prefix;
    private final @NonNull Supplier<Float> clock;

    public AiLog(@NonNull String prefix, @NonNull Supplier<Float> game_seconds) {
        this.prefix = prefix;
        this.clock = game_seconds;
    }

    public boolean enabled() {
        return AiDevOptions.isDebug();
    }

    public void info(@NonNull String message) {
        if (enabled())
            IO.println(String.format("[AI %s %6.1fs] %s", prefix, clock.get(), message));
    }

    /** Lazily formatted message (the supplier only runs when logging is on). */
    public void info(@NonNull Supplier<String> message) {
        if (enabled())
            info(message.get());
    }
}
