package com.oddlabs.tt.aisim.play;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.analysis.Runs;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The order a run plays its games in: the longest first. A run's games are as long as the maps and players make them,
 * from a few seconds of CPU to minutes, and the longest ones started last would run on alone at the end while every
 * other worker idles. So a job's cost is its mean simulation CPU in the earlier runs that played the same games (the
 * same lineup but for team A, maps and settings: what compare pairs), and the costliest go first. A job no such run
 * played counts at their median; without any, the order stays as given. Only the order changes, never a game.
 */
final class Order {
    private static final Pattern KEY = Pattern.compile("\"key\":\"([^\"]+)\"");
    private static final Pattern CPU = Pattern.compile("\"cpu\":([0-9.eE+-]+)");

    private Order() {
    }

    /** Jobs in the order to play them, and what the order came from (null when it is as given). */
    record Ordered(@NonNull List<Job> jobs, @Nullable String note) {
    }

    /**
     * {@code jobs} longest first, from the earlier runs with this {@code lineup} and {@code config} than {@code run}.
     */
    static @NonNull Ordered longestFirst(@NonNull List<Job> jobs, @NonNull String lineup, @NonNull String config,
            @NonNull String run) {
        Map<String, double[]> costs = new HashMap<>(); // key -> {cpu sum, games}
        int runs = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(Runs.RUNS)) {
            for (Path dir : dirs) {
                if (dir.getFileName().toString().equals(run) || !playsTheSameGames(dir, lineup, config)) {
                    continue;
                }
                if (addCosts(dir.resolve("results.jsonl"), costs)) {
                    runs++;
                }
            }
        } catch (IOException e) {
            return new Ordered(jobs, null); // no history: as given
        }
        if (costs.isEmpty()) {
            return new Ordered(jobs, null);
        }
        double[] means = costs.values().stream().mapToDouble(c -> c[0] / c[1]).sorted().toArray();
        double median = means[means.length / 2];
        int known = 0;
        Map<String, Double> cost = new HashMap<>();
        for (Job job : jobs) {
            double[] c = costs.get(job.key());
            known += c == null ? 0 : 1;
            cost.put(job.key(), c == null ? median : c[0] / c[1]);
        }
        List<Job> ordered = new ArrayList<>(jobs);
        ordered.sort(Comparator.comparingDouble((Job job) -> cost.get(job.key())).reversed()); // stable: ties as given
        String note = String.format("order: the longest games first, from %d of the %d games in %d earlier run%s",
                known, jobs.size(), runs, runs == 1 ? "" : "s");
        return new Ordered(ordered, note);
    }

    /** Whether the run in {@code dir} has this lineup and config (read from the head of its run.json). */
    private static boolean playsTheSameGames(@NonNull Path dir, @NonNull String lineup, @NonNull String config) {
        Path meta = dir.resolve("run.json");
        if (!Files.isRegularFile(meta)) {
            return false;
        }
        String found_lineup = null;
        String found_config = null;
        try (JsonParser parser = Aisim.JSON.getFactory().createParser(meta.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME && (found_lineup == null || found_config == null)) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (field.equals("jobs")) {
                    break; // lineup and config come before the jobs
                }
                if (value == JsonToken.VALUE_STRING && field.equals("lineup")) {
                    found_lineup = parser.getText();
                } else if (value == JsonToken.VALUE_STRING && field.equals("config")) {
                    found_config = parser.getText();
                } else {
                    parser.skipChildren();
                }
            }
        } catch (IOException e) {
            return false;
        }
        return lineup.equals(found_lineup) && config.equals(found_config);
    }

    /** Adds the CPU of every game in {@code results} to {@code costs}; whether it had any. */
    private static boolean addCosts(@NonNull Path results, @NonNull Map<String, double[]> costs) {
        boolean any = false;
        try (BufferedReader reader = Files.newBufferedReader(results, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String key = group(KEY.matcher(line));
                String cpu = last(CPU.matcher(line)); // the row's own cpu comes last, after any AI counter of that name
                if (key == null || cpu == null) {
                    continue;
                }
                double seconds = Double.parseDouble(cpu);
                if (seconds > 0) {
                    double[] c = costs.computeIfAbsent(key, _ -> new double[2]);
                    c[0] += seconds;
                    c[1]++;
                    any = true;
                }
            }
        } catch (IOException | NumberFormatException e) {
            // a run being written, or no results: what was read counts
        }
        return any;
    }

    private static @Nullable String group(@NonNull Matcher matcher) {
        return matcher.find() ? matcher.group(1) : null;
    }

    private static @Nullable String last(@NonNull Matcher matcher) {
        String found = null;
        while (matcher.find()) {
            found = matcher.group(1);
        }
        return found;
    }
}
