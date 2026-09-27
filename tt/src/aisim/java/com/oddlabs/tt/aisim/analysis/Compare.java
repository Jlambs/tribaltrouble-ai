package com.oddlabs.tt.aisim.analysis;

import com.oddlabs.tt.aisim.UsageException;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code compare BASE VARIANT [VARIANT...]}: runs compared over the same games (same key = same map, start position and
 * world seed), one variant with its base in detail, or several variants as one table.
 */
public final class Compare {
    /** The paired metrics, all from the result rows. */
    private static final List<String> METRICS = List.of("score", "elim", "kd30", "w15", "margin");
    /** The variant is called inert when at least this share of the games played identically. */
    private static final double INERT_SHARE = 0.8;

    private Compare() {
    }

    public static int run(@NonNull String base, @NonNull List<String> variants, boolean force) throws IOException {
        Map<?, ?> base_meta = Runs.meta(base);
        List<String> refusals = new ArrayList<>();
        for (String variant : variants) {
            for (String problem : comparabilityProblems(base_meta, Runs.meta(variant))) {
                refusals.add(variants.size() == 1 ? problem : variant + ": " + problem);
            }
        }
        String forced = String.join("; ", refusals);
        if (!refusals.isEmpty() && !force) {
            String why = "the runs are not comparable game by game (" + forced + ")";
            throw new UsageException(why + "; --force compares anyway");
        }
        if (variants.size() == 1) {
            return compareOne(base, base_meta, variants.get(0), forced);
        }
        return compareMany(base, base_meta, variants, forced);
    }

    /** One variant in detail: the paired metrics, warnings, and A's curves in both runs. */
    private static int compareOne(@NonNull String base, @NonNull Map<?, ?> base_meta, @NonNull String variant,
            @NonNull String forced) throws IOException {
        Map<?, ?> variant_meta = Runs.meta(variant);
        List<Map<String, Object>> base_rows = Runs.rows(base);
        List<Map<String, Object>> variant_rows = Runs.rows(variant);
        Map<String, Map<String, Object>> base_counted = countedByKey(base_rows);
        Map<String, Map<String, Object>> variant_counted = countedByKey(variant_rows);
        int base_failed = base_rows.size() - base_counted.size();
        int variant_failed = variant_rows.size() - variant_counted.size();
        List<String> keys = commonKeys(base_counted, variant_counted);
        int identical = identicalGames(base_counted, variant_counted, keys);
        System.out.printf(Locale.ROOT, "compare %s (A=%s) -> %s (A=%s) | B=%s | %s%n", base, base_meta.get("a"),
                variant, variant_meta.get("a"), variant_meta.get("b"), variant_meta.get("config"));
        System.out.printf(Locale.ROOT,
                "pairs %d (base %d/%s, variant %d/%s counted) | identical games %d | failed %d -> %d | snapshot %s -> %s%n",
                keys.size(), base_counted.size(), base_meta.get("expected"), variant_counted.size(),
                variant_meta.get("expected"), identical, base_failed, variant_failed, base_meta.get("snap"),
                variant_meta.get("snap"));
        if (keys.isEmpty()) {
            System.out.println("!!! no common games");
            return 1;
        }
        if (!forced.isEmpty()) {
            System.out.println("!! forced: " + forced);
        }
        if (identical >= INERT_SHARE * keys.size()) {
            System.out.printf(Locale.ROOT, """
                    !! %d of %d games are identical: the variant changes almost nothing (inert, or the change never \
                    triggers)%n""", identical, keys.size());
        }
        if (base_failed != variant_failed) {
            System.out.println("!! failed games differ; failed games are not counted and can flatter a run");
        }
        printPairedMetrics(base_counted, variant_counted, keys);
        System.out.println("A's curves over the paired games (base / variant):");
        Curves.Series base_series = new Curves.Series(base, gamesOf(base, keys), true);
        Curves.Series variant_series = new Curves.Series(variant, gamesOf(variant, keys), true);
        Curves.table(List.of(base_series, variant_series), Curves.FIELDS, Curves.MINUTES).forEach(System.out::println);
        return 0;
    }

    /** The paired table of every metric, with its footnote. */
    private static void printPairedMetrics(@NonNull Map<String, Map<String, Object>> base_counted,
            @NonNull Map<String, Map<String, Object>> variant_counted, @NonNull List<String> keys) {
        System.out.println(
                "metric       base   variant     delta +- SE (paired, maps weighted equally)  better/worse/tied games");
        for (String metric : METRICS) {
            Stats.Paired paired = Stats.paired(base_counted, variant_counted, keys, metric);
            String star = paired.significant() ? "*" : "";
            int tied = keys.size() - paired.better() - paired.worse();
            System.out.printf(Locale.ROOT, "%-10s %8.3f  %8.3f  %+9.3f +- %-8.3f %-3s  %d/%d/%d%n", metric,
                    paired.base(), paired.variant(), paired.delta(), paired.se(), star, paired.better(), paired.worse(),
                    tied);
        }
        System.out.println("""
                (* = |delta| > 2 SE. Decide on elim, kd30 and margin when most games time out; confirm on --seeds \
                holdout.)""");
    }

    /**
     * Several variants against one base, a line each: the paired difference of every metric over the games both
     * counted. The base's line holds its means over all its counted games.
     */
    private static int compareMany(@NonNull String base, @NonNull Map<?, ?> base_meta, @NonNull List<String> variants,
            @NonNull String forced) throws IOException {
        Map<String, Map<String, Object>> base_counted = countedByKey(Runs.rows(base));
        System.out.printf(Locale.ROOT, "compare %s (A=%s) with %d variants | B=%s | %s%n", base, base_meta.get("a"),
                variants.size(), base_meta.get("b"), base_meta.get("config"));
        if (!forced.isEmpty()) {
            System.out.println("!! forced: " + forced);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> header = new ArrayList<>(List.of("run", "A", "pairs", "identical"));
        header.addAll(METRICS);
        rows.add(header);
        List<String> base_row = new ArrayList<>(List.of(base, String.valueOf(base_meta.get("a")),
                String.valueOf(base_counted.size()), ""));
        for (String metric : METRICS) {
            base_row.add(String.format(Locale.ROOT, "%.3f", Stats.mean(base_counted.values(), metric)));
        }
        rows.add(base_row);
        int status = 0;
        for (String variant : variants) {
            Map<String, Map<String, Object>> variant_counted = countedByKey(Runs.rows(variant));
            List<String> keys = commonKeys(base_counted, variant_counted);
            int identical = identicalGames(base_counted, variant_counted, keys);
            List<String> row = new ArrayList<>(List.of(variant, String.valueOf(Runs.meta(variant).get("a")),
                    String.valueOf(keys.size()), String.valueOf(identical)));
            for (String metric : METRICS) {
                if (keys.isEmpty()) {
                    row.add("-");
                    continue;
                }
                Stats.Paired paired = Stats.paired(base_counted, variant_counted, keys, metric);
                String star = paired.significant() ? "*" : " ";
                row.add(String.format(Locale.ROOT, "%+.3f+-%.3f%s", paired.delta(), paired.se(), star));
            }
            rows.add(row);
            if (keys.isEmpty()) {
                status = 1;
            }
        }
        Table.align(rows, "llr").forEach(System.out::println);
        System.out.println("""
                (base: means over its counted games; variants: delta +- SE over the games both runs counted, paired, \
                maps weighted equally; * = |delta| > 2 SE)""");
        String all = base + " " + String.join(" ", variants);
        System.out.println("next: ./aisim.sh compare " + base + " VARIANT (in detail) | ./aisim.sh curves " + all);
        return status;
    }

    /**
     * Why two runs do not play the same games: their config, B or B's frozen jar differ. A frozen tag never changes,
     * so its sha differs only when a tag was deleted and frozen again.
     */
    private static @NonNull List<String> comparabilityProblems(@NonNull Map<?, ?> base_meta,
            @NonNull Map<?, ?> variant_meta) {
        List<String> problems = new ArrayList<>();
        for (String key : List.of("config", "b", "bPool")) {
            if (!Objects.equals(base_meta.get(key), variant_meta.get(key))) {
                problems.add(key + " differs: " + base_meta.get(key) + " vs " + variant_meta.get(key));
            }
        }
        return problems;
    }

    /** The counted games of a run by key, in results order. */
    private static @NonNull Map<String, Map<String, Object>> countedByKey(@NonNull List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> counted = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.get("winner") != null) {
                counted.put((String) row.get("key"), row);
            }
        }
        return counted;
    }

    /** The keys of the games both runs counted, sorted. */
    private static @NonNull List<String> commonKeys(@NonNull Map<String, Map<String, Object>> base,
            @NonNull Map<String, Map<String, Object>> variant) {
        return base.keySet().stream().filter(variant::containsKey).sorted().toList();
    }

    /** How many of the games {@code keys} the two runs played identically: the same end and final checksum. */
    private static int identicalGames(@NonNull Map<String, Map<String, Object>> base,
            @NonNull Map<String, Map<String, Object>> variant, @NonNull List<String> keys) {
        int identical = 0;
        for (String key : keys) {
            Map<String, Object> before = base.get(key);
            Map<String, Object> after = variant.get(key);
            if (Objects.equals(before.get("checksum"), after.get("checksum"))
                    && Objects.equals(before.get("end"), after.get("end"))) {
                identical++;
            }
        }
        return identical;
    }

    /** The counted games of {@code run} whose key is one of {@code keys}. */
    private static @NonNull List<Game> gamesOf(@NonNull String run, @NonNull List<String> keys) {
        Set<String> wanted = Set.copyOf(keys);
        return Game.counted(run).stream().filter(game -> wanted.contains(game.key())).toList();
    }
}
