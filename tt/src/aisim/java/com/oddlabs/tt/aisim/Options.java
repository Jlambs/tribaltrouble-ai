package com.oddlabs.tt.aisim;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One command's arguments: {@code --key value} options and positional arguments, in any order. The {@link #FLAGS}
 * never take a value; any other option followed by another option or by nothing is a flag with the value "true". An
 * option given twice keeps its last value. {@link #check} rejects unknown options and a wrong argument count.
 */
final class Options {
    /** Options that never take a value, so {@code --force RUN} does not swallow RUN. */
    private static final Set<String> FLAGS = Set.of("force", "stale-ok", "no-collapse", "stop-when-a-out", "split");

    /** The positional arguments, in order. */
    final @NonNull List<String> args = new ArrayList<>();
    /** Each option's last value, in the order the options first appear (so check reports the first unknown one). */
    private final @NonNull Map<String, String> values = new LinkedHashMap<>();

    /** Parses {@code argv} from index {@code from} on. */
    Options(@NonNull String @NonNull [] argv, int from) {
        for (int i = from; i < argv.length; i++) {
            if (!argv[i].startsWith("--")) {
                args.add(argv[i]);
                continue;
            }
            String key = argv[i].substring(2);
            boolean has_value = !FLAGS.contains(key) && i + 1 < argv.length && !argv[i + 1].startsWith("--");
            String value = "true";
            if (has_value) {
                i++;
                value = argv[i];
            }
            values.put(key, value);
        }
    }

    /** Rejects unknown options, then a wrong argument count; {@code positional} is the count its error names. */
    void check(@NonNull Set<String> known, int positional) {
        for (String key : values.keySet()) {
            if (!known.contains(key)) {
                throw new UsageException("unknown option --" + key);
            }
        }
        if (args.size() != positional) {
            throw new UsageException("expected " + positional + " arguments, got " + args);
        }
    }

    @Nullable
    String get(@NonNull String key) {
        return values.get(key);
    }

    @NonNull
    String get(@NonNull String key, @NonNull String fallback) {
        return values.getOrDefault(key, fallback);
    }

    /** The option as a number in min..max, or {@code fallback} when it is absent. */
    int integer(@NonNull String key, int fallback, int min, int max) {
        String value = get(key);
        int number;
        try {
            number = value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new UsageException("--" + key + " needs a number, not '" + value + "'");
        }
        if (number < min || number > max) {
            throw new UsageException("--" + key + " must be " + min + ".." + max);
        }
        return number;
    }

    /** The option checked as in {@link #integer}, or null when it is absent. */
    @Nullable
    Integer optionalInteger(@NonNull String key, int min, int max) {
        return values.containsKey(key) ? integer(key, min, min, max) : null;
    }

    boolean flag(@NonNull String key) {
        return values.containsKey(key);
    }
}
