package com.oddlabs.tt.aikit;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Optional helper for an AI's {@code k=v,k=v} params string: the part after ':' in an AI spec such as
 * {@code myai:rush=1,wave=12}.
 *
 * <p>Read every tunable with its default, then call {@link #done()}: it throws on keys nobody read, so a typo in a
 * variant fails the run instead of silently playing the defaults, and it returns the values in effect (defaults
 * included) for the AI's first log line. Unknown keys are only caught by {@code done()}.
 */
public final class AiParams {
    private final @NonNull Map<String, String> given = new LinkedHashMap<>();
    private final @NonNull Map<String, String> effective = new TreeMap<>();

    private AiParams() {
    }

    /** Parses {@code k=v,k=v}; null or empty gives no params. Malformed input throws IllegalArgumentException. */
    public static @NonNull AiParams parse(@Nullable String text) {
        AiParams params = new AiParams();
        if (text != null && !text.isBlank()) {
            for (String pair : text.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0 || params.given.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim()) != null) {
                    throw new IllegalArgumentException("bad AI param '" + pair + "' in '" + text + "'");
                }
            }
        }
        return params;
    }

    public int getInt(@NonNull String key, int def) {
        String value = get(key, Integer.toString(def));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("AI param " + key + " must be a whole number, not '" + value + "'", e);
        }
    }

    public double getDouble(@NonNull String key, double def) {
        String value = get(key, Double.toString(def));
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("AI param " + key + " must be a number, not '" + value + "'", e);
        }
    }

    /** true/false or 1/0. */
    public boolean getBoolean(@NonNull String key, boolean def) {
        String value = get(key, Boolean.toString(def));
        return switch (value) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new IllegalArgumentException(
                    "AI param " + key + " must be true/false/1/0, not '" + value + "'");
        };
    }

    public @NonNull String getString(@NonNull String key, @NonNull String def) {
        return get(key, def);
    }

    /** Throws IllegalArgumentException naming unknown keys; returns the values in effect as {@code k=v k=v}. */
    public @NonNull String done() {
        List<String> unknown = new ArrayList<>();
        for (String key : given.keySet()) {
            if (!effective.containsKey(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown AI params " + unknown + "; known: " + effective.keySet());
        }
        List<String> pairs = new ArrayList<>();
        effective.forEach((key, value) -> pairs.add(key + "=" + value));
        return String.join(" ", pairs);
    }

    private @NonNull String get(@NonNull String key, @NonNull String def) {
        String value = given.getOrDefault(key, def);
        effective.put(key, value);
        return value;
    }
}
