package com.oddlabs.tt.aisim.analysis;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The statistics of the reports: means, the score's confidence interval, and paired differences between runs. */
public final class Stats {
    /** The normal quantile of a two-sided 95% interval. */
    private static final double Z95 = 1.959964;

    private Stats() {
    }

    /** The mean of {@code key} over {@code rows}; 0 when there are none. */
    public static double mean(@NonNull Collection<Map<String, Object>> rows, @NonNull String key) {
        return rows.stream().mapToDouble(row -> Game.num(row, key)).average().orElse(0);
    }

    /**
     * The Wilson 95% interval of a score {@code p} over {@code n} games (draws count half): unlike p +- 2 SE, it stays
     * inside 0..1 and is honest near 0 and 1.
     */
    public static double @NonNull [] wilson(double p, int n) {
        double z = Z95;
        double center = (p + z * z / (2 * n)) / (1 + z * z / n);
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / (1 + z * z / n);
        // clamped: at a score of 0 or 1, rounding leaves the bound a hair outside, which prints as -0.000
        return new double[]{Math.max(0, center - half), Math.min(1, center + half)};
    }

    /**
     * A paired difference: base mean, variant mean, delta, its standard error, and how many games got better and
     * worse.
     */
    public record Paired(double base, double variant, double delta, double se, int better, int worse) {
        /** The delta lies beyond two standard errors (a NaN standard error is never significant). */
        public boolean significant() {
            return Math.abs(delta) > 2 * se;
        }
    }

    /**
     * The paired difference of {@code metric} over the games {@code keys} of two runs. The games of each map (world
     * seed) are averaged first, since a map's start positions are not independent; the standard error is over maps.
     */
    public static @NonNull Paired paired(@NonNull Map<String, Map<String, Object>> base,
            @NonNull Map<String, Map<String, Object>> variant, @NonNull List<String> keys, @NonNull String metric) {
        Map<Integer, MapSums> maps = new TreeMap<>(); // by seed
        int better = 0;
        int worse = 0;
        for (String key : keys) {
            double before = Game.num(base.get(key), metric);
            double after = Game.num(variant.get(key), metric);
            better += after > before ? 1 : 0;
            worse += after < before ? 1 : 0;
            MapSums sums = maps.computeIfAbsent((int) Game.num(base.get(key), "seed"), seed -> new MapSums());
            sums.base += before;
            sums.variant += after;
            sums.games++;
        }
        double base_mean = maps.values().stream().mapToDouble(m -> m.base / m.games).average().orElse(0);
        double variant_mean = maps.values().stream().mapToDouble(m -> m.variant / m.games).average().orElse(0);
        double delta = variant_mean - base_mean;
        double se = Double.NaN;
        if (maps.size() >= 2) {
            double squares = maps.values().stream().mapToDouble(m -> Math.pow(m.delta() - delta, 2)).sum();
            se = Math.sqrt(squares / (maps.size() - 1) / maps.size());
        }
        return new Paired(base_mean, variant_mean, delta, se, better, worse);
    }

    /**
     * A paired ratio of a cost such as cpu: the base and variant means, the geometric mean of variant / base over the
     * games, and the standard error of its logarithm, over {@code games} games.
     */
    public record Ratio(double base, double variant, double ratio, double se, int games) {
        /** The change in percent (negative: the variant costs less). */
        public double percent() {
            return (ratio - 1) * 100;
        }

        /** The standard error in percent (of the log ratio, which is close for small changes). */
        public double sePercent() {
            return se * 100;
        }

        /** The change lies beyond two standard errors (a NaN standard error is never significant). */
        public boolean significant() {
            return Math.abs(Math.log(ratio)) > 2 * se;
        }
    }

    /**
     * The paired ratio of {@code metric} over the games {@code keys} that have it above 0 in both runs; null when fewer
     * than two do. Every game counts alike: a cost belongs to the game, not the map.
     */
    public static @Nullable Ratio pairedRatio(@NonNull Map<String, Map<String, Object>> base,
            @NonNull Map<String, Map<String, Object>> variant, @NonNull List<String> keys, @NonNull String metric) {
        double base_sum = 0;
        double variant_sum = 0;
        double log_sum = 0;
        double log_squares = 0;
        int games = 0;
        for (String key : keys) {
            double before = Game.num(base.get(key), metric);
            double after = Game.num(variant.get(key), metric);
            if (!(before > 0) || !(after > 0)) {
                continue;
            }
            double log = Math.log(after / before);
            base_sum += before;
            variant_sum += after;
            log_sum += log;
            log_squares += log * log;
            games++;
        }
        if (games < 2) {
            return null;
        }
        double mean = log_sum / games;
        double variance = Math.max(0, (log_squares - games * mean * mean) / (games - 1));
        return new Ratio(base_sum / games, variant_sum / games, Math.exp(mean), Math.sqrt(variance / games), games);
    }

    /** One map's sums of a metric over its games in both runs. */
    private static final class MapSums {
        double base;
        double variant;
        int games;

        /** The map's mean difference, variant minus base. */
        double delta() {
            return (variant - base) / games;
        }
    }
}
