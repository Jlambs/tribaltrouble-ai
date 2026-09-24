package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Optional debug trace for {@link UltraAI}. The sink is null in the game; the headless harness installs one to
 * record AI decisions. Logging never feeds back into decisions, so it cannot affect determinism.
 */
public final class UltraLog {
    private static volatile @Nullable Consumer<String> sink;
    private static volatile boolean strict;

    private UltraLog() {
    }

    public static void setSink(@Nullable Consumer<String> new_sink) {
        sink = new_sink;
    }

    /** In strict mode AI exceptions are rethrown instead of swallowed, so the harness can surface bugs. */
    public static void setStrict(boolean new_strict) {
        strict = new_strict;
    }

    static boolean isStrict() {
        return strict;
    }

    static boolean enabled() {
        return sink != null;
    }

    static void log(@NonNull Player player, @NonNull Supplier<String> message) {
        Consumer<String> s = sink;
        if (s != null)
            s.accept("[" + player.getPlayerInfo().getName() + "] " + message.get());
    }
}
