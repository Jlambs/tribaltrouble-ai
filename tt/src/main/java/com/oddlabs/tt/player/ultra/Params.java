package com.oddlabs.tt.player.ultra;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tunable AI parameters. Defaults are the tuned values; the headless harness can override any of them with a
 * spec string such as {@code "quartersEarly=4,towerStart=180"} to run parameter tournaments.
 */
final class Params {
    private final Map<String, Float> values = new LinkedHashMap<>();

    Params(@Nullable String spec) {
        if (spec == null || spec.isBlank())
            return;
        for (String part : spec.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0)
                continue;
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            try {
                values.put(key, Float.parseFloat(value));
            } catch (NumberFormatException e) {
                // Ignore malformed overrides; defaults stay in effect.
            }
        }
    }

    float get(@NonNull String key, float def) {
        Float v = values.get(key);
        return v != null ? v : def;
    }

    int getInt(@NonNull String key, int def) {
        Float v = values.get(key);
        return v != null ? Math.round(v) : def;
    }

    boolean getBool(@NonNull String key, boolean def) {
        Float v = values.get(key);
        return v != null ? v != 0f : def;
    }

    @Override
    public @NonNull String toString() {
        return values.toString();
    }
}
