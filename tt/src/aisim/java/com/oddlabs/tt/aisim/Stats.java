package com.oddlabs.tt.aisim;

import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** The statistics of the reports: means, the score's confidence interval, and paired differences between runs. */
final class Stats {
    /** The normal quantile of a two-sided 95% interval. */
    private static final double Z95 = 1.959964;

    private Stats() {
    }

    /** The mean of {@code key} over {@code rows}; 0 when there are none. */
    static double mean(@NonNull Collection<Map<String, Object>> rows, @NonNull String key) {
        return rows.stream().mapToDouble(r -> Runs.num(r, key)).average().orElse(0);
    }

    /** The upper median, as whole seconds; "-" for none. */
    static @NonNull String median(@NonNull List<Double> values) {
        if (values.isEmpty()) {
            return "-";
        }
        double median = values.stream().sorted().toList().get(values.size() / 2);
        return String.format(Locale.ROOT, "%.0f", median);
    }

    /**
     * The Wilson 95% interval of a score {@code p} over {@code n} games (draws count half): unlike p +- 2 SE, it stays
     * inside 0..1 and is honest near 0 and 1.
     */
    static double @NonNull [] wilson(double p, int n) {
        double z = Z95;
        double center = (p + z * z / (2 * n)) / (1 + z * z / n);
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / (1 + z * z / n);
        return new double[]{center - half, center + half};
    }

    /**
     * A paired difference: base mean, variant mean, delta, its standard error, and how many games got better and
     * worse.
     */
    record Paired(double base, double variant, double delta, double se, int better, int worse) {
        /** The delta lies beyond two standard errors (a NaN standard error is never significant). */
        boolean significant() {
            return Math.abs(delta) > 2 * se;
        }
    }

    /**
     * The paired difference of {@code metric} over the games {@code keys} of two runs. The games of each map (world
     * seed) are averaged first, since a map's start positions are not independent; the standard error is over maps.
     */
    static @NonNull Paired paired(@NonNull Map<String, Map<String, Object>> base,
            @NonNull Map<String, Map<String, Object>> variant, @NonNull List<String> keys, @NonNull String metric) {
        Map<Integer, double[]> maps = new TreeMap<>(); // per seed: {base sum, variant sum, games}
        int better = 0;
        int worse = 0;
        for (String key : keys) {
            double x0 = Runs.num(base.get(key), metric);
            double x1 = Runs.num(variant.get(key), metric);
            better += x1 > x0 ? 1 : 0;
            worse += x1 < x0 ? 1 : 0;
            double[] m = maps.computeIfAbsent((int) Runs.num(base.get(key), "seed"), s -> new double[3]);
            m[0] += x0;
            m[1] += x1;
            m[2]++;
        }
        double b = maps.values().stream().mapToDouble(m -> m[0] / m[2]).average().orElse(0);
        double v = maps.values().stream().mapToDouble(m -> m[1] / m[2]).average().orElse(0);
        double delta = v - b;
        double se = Double.NaN;
        if (maps.size() >= 2) {
            double squares = maps.values().stream().mapToDouble(m -> Math.pow((m[1] - m[0]) / m[2] - delta, 2)).sum();
            se = Math.sqrt(squares / (maps.size() - 1) / maps.size());
        }
        return new Paired(b, v, delta, se, better, worse);
    }
}
