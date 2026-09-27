package com.oddlabs.tt.aisim.analysis;

import org.jspecify.annotations.NonNull;

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
