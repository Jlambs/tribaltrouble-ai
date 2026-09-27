package com.oddlabs.tt.aisim;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static com.oddlabs.tt.aisim.Runs.num;

/**
 * The text reports: {@code summary} of a run, {@code compare} of runs game by game, and {@code show} of one game.
 * Numbers print with Locale.ROOT. "A" is the AI under test, "B" its opponents' team.
 */
final class Report {
    /** The paired metrics of compare, all from the result rows. */
    private static final String[] METRICS = {"score", "elim", "kd30", "w15", "margin"};
    /** compare warns that the variant is inert when at least this share of the games played identically. */
    private static final double INERT_SHARE = 0.8;
    /** How many of A's worst games and of the failed games summary lists. */
    private static final int WORST_GAMES = 5;
    private static final int FAILED_GAMES = 10;
    /** show: a census row every this many game seconds. */
    private static final int CENSUS_EVERY = 300;
    /** show: deaths are summed per slot over windows this long, and a window is a key event from this many. */
    private static final int DEATH_WINDOW = 10;
    private static final int DEATHS_SHOWN = 10;
    /** show: a stun of at least this many units is a key event. */
    private static final int STUNS_SHOWN = 5;
    /** The events {@code show} lists as key events, besides death windows and big stuns. */
    private static final Set<String> KEY_EVENTS = Set.of("built", "razed", "chief", "chief_died", "cast", "collapse",
            "out", "end", "speed", "recorder_error");

    private Report() {
    }

    // ---------------------------------------------------------------- summary

    /**
     * Prints the summary of {@code run} and saves it as summary.txt; exit code 1 if games failed or none counted.
     */
    static int summary(@NonNull String run) throws IOException {
        Path dir = Runs.dir(run);
        Map<?, ?> meta = Runs.meta(run);
        List<Map<String, Object>> rows = Runs.rows(run);
        List<Map<String, Object>> valid = rows.stream().filter(r -> r.get("winner") != null).toList();
        List<Map<String, Object>> failed = rows.stream().filter(r -> r.get("winner") == null).toList();
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        out.printf(Locale.ROOT, "aisim run %s: A=%s vs B=%s | %s | snapshot %s%n", run, meta.get("a"), meta.get("b"),
                meta.get("config"), meta.get("snap"));
        out.printf(Locale.ROOT, "games %d/%s done, %d counted, %d failed%n", rows.size(), meta.get("expected"),
                valid.size(), failed.size());
        String result = "RESULT " + run + " a=" + meta.get("a") + " b=" + meta.get(
                "b") + " n=" + valid.size() + "/" + meta.get("expected");
        if (!valid.isEmpty()) {
            Headline headline = Headline.of(valid);
            headline.print(out, valid);
            printMeans(out, valid);
            printHealth(out, valid);
            List<Game> games = Game.counted(run);
            out.println("curves (mean over games still running; A / B):");
            List<Curves.Series> sides = List.of(new Curves.Series("A", games, true), new Curves.Series("B", games,
                    false));
            Curves.table(sides, Curves.FIELDS, Curves.MINUTES).forEach(out::println);
            out.println(milestones(games));
            printWorstGames(out, run, valid);
            result += headline.resultFields();
        }
        printFailed(out, failed);
        out.println(result + " fail=" + failed.size());
        out.println("next: ./aisim.sh compare BASE " + run + "   (paired, per game)");
        out.flush();
        System.out.print(text);
        Files.writeString(dir.resolve("summary.txt"), text.toString());
        return failed.isEmpty() && !valid.isEmpty() ? 0 : 1;
    }

    /** A's wins, losses and draws over some games. */
    private record Wld(int won, int lost, int drawn) {
        /** W/L/D of A over {@code rows}, only those that ended by {@code end} unless it is null. */
        static @NonNull Wld of(@NonNull List<Map<String, Object>> rows, @Nullable End end) {
            int won = 0;
            int lost = 0;
            int drawn = 0;
            for (Map<String, Object> r : rows) {
                if (end == null || end.name().equals(r.get("end"))) {
                    switch (Runs.resultOfA(r.get("winner"))) {
                        case "win" -> won++;
                        case "loss" -> lost++;
                        default -> drawn++;
                    }
                }
            }
            return new Wld(won, lost, drawn);
        }

        int games() {
            return won + lost + drawn;
        }
    }

    /** A's headline numbers over the counted games: its score with interval, W/L/D, kd30 and margin. */
    private record Headline(double score, double @NonNull [] ci, @NonNull Wld all, @NonNull Wld elim,
                            @NonNull Wld timeout, double kd30, double margin) {
        static @NonNull Headline of(@NonNull List<Map<String, Object>> valid) {
            double score = Stats.mean(valid, "score");
            return new Headline(score, Stats.wilson(score, valid.size()), Wld.of(valid, null), Wld.of(valid, End.elim),
                    Wld.of(valid, End.timeout), Stats.mean(valid, "kd30"), Stats.mean(valid, "margin"));
        }

        /** The score with its interval, how the games ended, and A's record from each start slot. */
        void print(@NonNull PrintWriter out, @NonNull List<Map<String, Object>> valid) {
            int n = valid.size();
            Wld collapse = Wld.of(valid.stream().filter(r -> "collapse".equals(r.get("via"))).toList(), null);
            out.printf(Locale.ROOT, "score %.3f [%.3f, %.3f]   W %d  L %d  D %d%n", score, ci[0], ci[1], all.won(),
                    all.lost(), all.drawn());
            out.printf(Locale.ROOT,
                    "  by elimination W %d L %d (by collapse W %d L %d) | by timeout W %d L %d D %d (%.0f%% of games)%n",
                    elim.won(), elim.lost(), collapse.won(), collapse.lost(), timeout.won(), timeout.lost(),
                    timeout.drawn(), 100.0 * timeout.games() / n);
            int vs = (int) num(valid.get(0), "vs"); // a 1 vs N game has the start slots 0..N
            List<String> slots = new ArrayList<>();
            for (int slot = 0; slot <= vs; slot++) {
                Wld w = Wld.of(inSlot(valid, slot), null);
                slots.add(String.format(Locale.ROOT, "A in slot %d: W %d L %d D %d", slot, w.won(), w.lost(),
                        w.drawn()));
            }
            out.println("  " + String.join(" | ", slots));
        }

        /** The RESULT line's fields after n=. */
        @NonNull
        String resultFields() {
            return String.format(Locale.ROOT,
                    " score=%.3f [%.3f,%.3f] W%d L%d D%d elim=%d-%d timeout=%d-%d-%d kd30=%+.1f margin=%+.3f", score,
                    ci[0], ci[1], all.won(), all.lost(), all.drawn(), elim.won(), elim.lost(), timeout.won(),
                    timeout.lost(), timeout.drawn(), kd30, margin);
        }
    }

    /** The rows where A started in {@code slot}. */
    private static @NonNull List<Map<String, Object>> inSlot(@NonNull List<Map<String, Object>> rows, int slot) {
        return rows.stream().filter(r -> (int) num(r, "side") == slot).toList();
    }

    /** The means line (with the cost per game when the rows have it), and a warning when most games timed out. */
    private static void printMeans(@NonNull PrintWriter out, @NonNull List<Map<String, Object>> valid) {
        String cost = "";
        if (valid.get(0).get("cpu") != null) {
            double cpu = Stats.mean(valid, "cpu");
            double wall = Stats.mean(valid, "wall");
            cost = String.format(Locale.ROOT, " | cost %.1f s CPU (%.1f s wall) per game", cpu, wall);
        }
        double kd30 = Stats.mean(valid, "kd30");
        double w15 = Stats.mean(valid, "w15");
        double margin = Stats.mean(valid, "margin");
        double minutes = Stats.mean(valid, "t") / 60;
        out.printf(Locale.ROOT, "means: kd30 %+.1f | w15 %.1f | margin %+.3f | length %.1f min%s%n", kd30, w15, margin,
                minutes, cost);
        if (Wld.of(valid, End.timeout).games() > valid.size() / 2) {
            out.println(
                    "!! most games timed out: judge variants by elim, kd30 and margin in ./aisim.sh compare, not by score");
        }
    }

    /** Swallowed AI errors and AI counters of both sides, and a warning when the recorder stopped early. */
    private static void printHealth(@NonNull PrintWriter out, @NonNull List<Map<String, Object>> valid) {
        out.println(swallowedErrors(valid, "A") + " | " + swallowedErrors(valid, "B"));
        out.println(counters(valid, "A") + " | " + counters(valid, "B"));
        List<Object> broken = valid.stream().filter(r -> num(r, "recErr") > 0).map(r -> r.get("key")).toList();
        if (!broken.isEmpty()) {
            String stopped = "!! the recorder stopped in " + broken.size() + " games";
            out.println(stopped + " (curves and milestones miss their later parts): " + broken);
        }
    }

    /** A result row's block of team {@code team} ("A" or "B"): final census, AI counters, first AI error. */
    private static @NonNull Map<?, ?> block(@NonNull Map<String, Object> row, @NonNull String team) {
        return (Map<?, ?>) row.get(team);
    }

    /** How many errors a team's AIs threw and survived, in how many games, and the first one. */
    private static @NonNull String swallowedErrors(@NonNull List<Map<String, Object>> rows, @NonNull String team) {
        int errors = 0;
        int games = 0;
        Object first = null;
        for (Map<String, Object> r : rows) {
            Map<?, ?> block = block(r, team);
            int e = (int) num(block, "err");
            errors += e;
            games += e > 0 ? 1 : 0;
            if (first == null) {
                first = block.get("aiError");
            }
        }
        String text = team + " swallowed errors " + errors + " in " + games + " games";
        return first == null ? text : text + " (first: " + first + ")";
    }

    /** The mean per game of each counter a team's AIs reported (AiLog.count). */
    private static @NonNull String counters(@NonNull List<Map<String, Object>> rows, @NonNull String team) {
        Map<String, Double> sums = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            Map<?, ?> counters = (Map<?, ?>) block(r, team).get("counters");
            for (Map.Entry<?, ?> counter : counters.entrySet()) {
                sums.merge(String.valueOf(counter.getKey()), ((Number) counter.getValue()).doubleValue(), Double::sum);
            }
        }
        if (sums.isEmpty()) {
            return team + " counters: none";
        }
        StringBuilder text = new StringBuilder(team + " counters per game:");
        sums.forEach((k, v) -> text.append(String.format(Locale.ROOT, " %s %.1f", k, v / rows.size())));
        return text.toString();
    }

    /** A's worst games that were not wins (lowest score, then lowest margin), with commands to open them. */
    private static void printWorstGames(@NonNull PrintWriter out, @NonNull String run,
            @NonNull List<Map<String, Object>> valid) {
        Comparator<Map<String, Object>> by_score = Comparator.comparingDouble(r -> num(r, "score"));
        Comparator<Map<String, Object>> worst_first = by_score.thenComparingDouble(r -> num(r, "margin"));
        Stream<Map<String, Object>> not_won = valid.stream().filter(r -> num(r, "score") < 1);
        List<Map<String, Object>> worst = not_won.sorted(worst_first).limit(WORST_GAMES).toList();
        if (worst.isEmpty()) {
            return;
        }
        out.println("worst games for A:");
        for (Map<String, Object> r : worst) {
            Object key = r.get("key");
            out.printf(Locale.ROOT,
                    "  %-9s %-4s %-7s %5.1fm margin %+.2f  ./aisim.sh show %s %s | ./aisim.sh replay %s %s%n",
                    key, Runs.resultOfA(r.get("winner")), r.get("end"), num(r, "t") / 60, num(r, "margin"), run, key,
                    run, key);
        }
    }

    /** The first failed games with their problem. */
    private static void printFailed(@NonNull PrintWriter out, @NonNull List<Map<String, Object>> failed) {
        if (failed.isEmpty()) {
            return;
        }
        out.printf(Locale.ROOT, "!!! %d games failed (not counted):%n", failed.size());
        for (Map<String, Object> r : failed.stream().limit(FAILED_GAMES).toList()) {
            out.printf(Locale.ROOT, "  %s %s: %s%n", r.get("key"), r.get("end"), r.get("problem"));
        }
    }

    // ---------------------------------------------------------------- milestones

    /** The build-order milestones, in print order; name() is the printed label. */
    private enum Milestone {
        Q1,
        Q4,
        A1,
        T1,
        chief
    }

    /** Median time of each milestone for A and for B's team (its first player to reach it), per game. */
    private static @NonNull String milestones(@NonNull List<Game> games) {
        int count = Milestone.values().length;
        List<List<Double>> a_times = new ArrayList<>();
        List<List<Double>> b_times = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            a_times.add(new ArrayList<>());
            b_times.add(new ArrayList<>());
        }
        for (Game game : games) {
            // Per team (0 = A, 1 = B's team) the first time of each milestone; -1 until reached.
            double[][] first = new double[2][count];
            Arrays.fill(first[0], -1);
            Arrays.fill(first[1], -1);
            Map<Integer, Integer> quarters_built = new TreeMap<>(); // by slot
            for (Map<String, Object> e : game.events()) {
                int slot = Game.slot(e);
                Milestone hit = milestoneOf(e, slot, quarters_built);
                if (hit != null) {
                    int team = slot == game.a() ? 0 : 1;
                    reach(first[team], hit.ordinal(), num(e, "t"));
                }
            }
            for (int i = 0; i < count; i++) {
                if (first[0][i] >= 0) {
                    a_times.get(i).add(first[0][i]);
                }
                if (first[1][i] >= 0) {
                    b_times.get(i).add(first[1][i]);
                }
            }
        }
        StringBuilder line = new StringBuilder("milestones (median seconds A / B, and in how many games):");
        for (Milestone milestone : Milestone.values()) {
            List<Double> a = a_times.get(milestone.ordinal());
            List<Double> b = b_times.get(milestone.ordinal());
            line.append(String.format(Locale.ROOT, " %s %s/%s (%d/%d)", milestone.name(), Stats.median(a),
                    Stats.median(b), a.size(), b.size()));
        }
        return line.toString();
    }

    /** The milestone event {@code e} of {@code slot} reaches, if any; counts each slot's quarters. */
    private static @Nullable Milestone milestoneOf(@NonNull Map<String, Object> e, int slot,
            @NonNull Map<Integer, Integer> quarters_built) {
        if ("chief".equals(e.get("ev"))) {
            return Milestone.chief;
        }
        if (!"built".equals(e.get("ev"))) {
            return null;
        }
        Object kind = e.get("b");
        if ("quarters".equals(kind)) {
            // Q4 is one player's fourth quarters, so each slot counts on its own even within B's team.
            int count = quarters_built.merge(slot, 1, Integer::sum);
            if (count == 1) {
                return Milestone.Q1;
            }
            return count == 4 ? Milestone.Q4 : null;
        }
        if ("armory".equals(kind)) {
            return Milestone.A1;
        }
        return "tower".equals(kind) ? Milestone.T1 : null;
    }

    private static void reach(double @NonNull [] first, int i, double t) {
        if (first[i] < 0 || t < first[i]) {
            first[i] = t;
        }
    }

    // ---------------------------------------------------------------- compare

    /**
     * Paired comparison of runs over the same games (same key = same map, start position and world seed): one variant
     * with its base in detail, or several variants as one table.
     */
    static int compare(@NonNull String base, @NonNull List<String> variants, boolean force) throws IOException {
        Map<?, ?> base_meta = Runs.meta(base);
        List<String> refusals = new ArrayList<>();
        for (String variant : variants) {
            for (String problem : comparabilityProblems(base_meta, Runs.meta(variant))) {
                refusals.add(variants.size() == 1 ? problem : variant + ": " + problem);
            }
        }
        String reasons = String.join("; ", refusals);
        if (!refusals.isEmpty() && !force) {
            throw new UsageException(
                    "the runs are not comparable game by game (" + reasons + "); --force compares anyway");
        }
        if (variants.size() > 1) {
            return compareMany(base, base_meta, variants, reasons);
        }
        String variant = variants.get(0);
        Map<?, ?> variant_meta = Runs.meta(variant);
        List<Map<String, Object>> base_all = Runs.rows(base);
        List<Map<String, Object>> variant_all = Runs.rows(variant);
        Map<String, Map<String, Object>> base_counted = counted(base_all);
        Map<String, Map<String, Object>> variant_counted = counted(variant_all);
        int failed_base = base_all.size() - base_counted.size();
        int failed_variant = variant_all.size() - variant_counted.size();
        List<String> keys = base_counted.keySet().stream().filter(variant_counted::containsKey).sorted().toList();
        System.out.printf(Locale.ROOT, "compare %s (A=%s) -> %s (A=%s) | B=%s | %s%n", base, base_meta.get("a"),
                variant, variant_meta.get("a"), variant_meta.get("b"), variant_meta.get("config"));
        int same = identical(base_counted, variant_counted, keys);
        System.out.printf(Locale.ROOT,
                "pairs %d (base %d/%s, variant %d/%s counted) | identical games %d | failed %d -> %d | snapshot %s -> %s%n",
                keys.size(), base_counted.size(), base_meta.get("expected"), variant_counted.size(),
                variant_meta.get("expected"), same, failed_base, failed_variant, base_meta.get("snap"),
                variant_meta.get("snap"));
        if (keys.isEmpty()) {
            System.out.println("!!! no common games");
            return 1;
        }
        if (!refusals.isEmpty()) {
            System.out.println("!! forced: " + reasons);
        }
        if (same >= INERT_SHARE * keys.size()) {
            System.out.println("""
                    !! %d of %d games are identical: the variant changes almost nothing (inert, or the change never \
                    triggers)""".formatted(same, keys.size()));
        }
        if (failed_base != failed_variant) {
            System.out.println("!! failed games differ; failed games are not counted and can flatter a run");
        }
        printPairedMetrics(base_counted, variant_counted, keys);
        System.out.println("A's curves over the paired games (base / variant):");
        Curves.Series base_series = new Curves.Series(base, paired(base, keys), true);
        Curves.Series variant_series = new Curves.Series(variant, paired(variant, keys), true);
        Curves.table(List.of(base_series, variant_series), Curves.FIELDS, Curves.MINUTES).forEach(System.out::println);
        return 0;
    }

    /** The counted games of {@code run} whose key is one of {@code keys}. */
    private static @NonNull List<Game> paired(@NonNull String run, @NonNull List<String> keys) {
        Set<String> wanted = Set.copyOf(keys);
        return Game.counted(run).stream().filter(g -> wanted.contains(g.key())).toList();
    }

    /**
     * Several variants against one base, a line each: the paired difference of every metric over the games both
     * counted. The base's line holds its means over all its counted games.
     */
    private static int compareMany(@NonNull String base, @NonNull Map<?, ?> base_meta, @NonNull List<String> variants,
            @NonNull String forced) throws IOException {
        Map<String, Map<String, Object>> base_counted = counted(Runs.rows(base));
        System.out.printf(Locale.ROOT, "compare %s (A=%s) with %d variants | B=%s | %s%n", base, base_meta.get("a"),
                variants.size(), base_meta.get("b"), base_meta.get("config"));
        if (!forced.isEmpty()) {
            System.out.println("!! forced: " + forced);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> header = new ArrayList<>(List.of("run", "A", "pairs", "identical"));
        header.addAll(List.of(METRICS));
        rows.add(header);
        String base_games = String.valueOf(base_counted.size());
        List<String> base_row = new ArrayList<>(List.of(base, String.valueOf(base_meta.get("a")), base_games, ""));
        for (String metric : METRICS) {
            base_row.add(String.format(Locale.ROOT, "%.3f", Stats.mean(base_counted.values(), metric)));
        }
        rows.add(base_row);
        int status = 0;
        for (String variant : variants) {
            Map<String, Map<String, Object>> variant_counted = counted(Runs.rows(variant));
            List<String> keys = base_counted.keySet().stream().filter(variant_counted::containsKey).sorted().toList();
            List<String> row = new ArrayList<>(List.of(variant, String.valueOf(Runs.meta(variant).get("a")),
                    String.valueOf(keys.size()), String.valueOf(identical(base_counted, variant_counted, keys))));
            for (String metric : METRICS) {
                if (keys.isEmpty()) {
                    row.add("-");
                    continue;
                }
                Stats.Paired p = Stats.paired(base_counted, variant_counted, keys, metric);
                row.add(String.format(Locale.ROOT, "%+.3f+-%.3f%s", p.delta(), p.se(), p.significant() ? "*" : " "));
            }
            rows.add(row);
            status = keys.isEmpty() ? 1 : status;
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
    private static @NonNull Map<String, Map<String, Object>> counted(@NonNull List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            if (r.get("winner") != null) {
                map.put((String) r.get("key"), r);
            }
        }
        return map;
    }

    /** How many of the games {@code keys} the two runs played identically: the same end and final checksum. */
    private static int identical(@NonNull Map<String, Map<String, Object>> base,
            @NonNull Map<String, Map<String, Object>> variant, @NonNull List<String> keys) {
        int same = 0;
        for (String key : keys) {
            if (Objects.equals(base.get(key).get("checksum"), variant.get(key).get("checksum"))
                    && Objects.equals(base.get(key).get("end"), variant.get(key).get("end"))) {
                same++;
            }
        }
        return same;
    }

    /** The paired table of every metric, with its footnote. */
    private static void printPairedMetrics(@NonNull Map<String, Map<String, Object>> base_counted,
            @NonNull Map<String, Map<String, Object>> variant_counted, @NonNull List<String> keys) {
        System.out.println(
                "metric       base   variant     delta +- SE (paired, maps weighted equally)  better/worse/tied games");
        for (String metric : METRICS) {
            Stats.Paired p = Stats.paired(base_counted, variant_counted, keys, metric);
            String star = p.significant() ? "*" : "";
            int tied = keys.size() - p.better() - p.worse();
            System.out.printf(Locale.ROOT, "%-10s %8.3f  %8.3f  %+9.3f +- %-8.3f %-3s  %d/%d/%d%n", metric, p.base(),
                    p.variant(), p.delta(), p.se(), star, p.better(), p.worse(), tied);
        }
        System.out.println(
                "(* = |delta| > 2 SE. Decide on elim, kd30 and margin when most games time out; confirm on --seeds holdout.)");
    }

    // ---------------------------------------------------------------- show

    /** Prints one game: players, a census table every 5 minutes, key events. Works on harness and GUI game files. */
    static int show(@NonNull List<String> args) {
        Game game = Game.named(args);
        printPlayers(game);
        printCensusTable(game.events());
        printKeyEvents(game.events());
        if (args.size() == 2) {
            String next = "next: ./aisim.sh replay " + args.get(0) + " " + args.get(1);
            System.out.println(next + "   (the same game again, with the log of every AI that uses AiLog)");
        }
        return 0;
    }

    /** The game file's map line and its players, from the header event. */
    private static void printPlayers(@NonNull Game game) {
        Map<String, Object> header = game.header();
        String place = header.get("map") == null ? "" : header.get("map") + " seed " + header.get("seed") + " ";
        System.out.println(Aisim.slash(game.path()) + ": " + place + "map code \"" + header.get("mapcode") + "\"");
        for (Map<String, Object> player : game.players()) {
            System.out.printf(Locale.ROOT, "  s%s %-28s team %s %-7s start %s,%s%n", player.get("s"), player.get("ai"),
                    player.get("team"), player.get("race"), player.get("x"), player.get("y"));
        }
    }

    /** Every player's census every {@link #CENSUS_EVERY} seconds and at the last sample. */
    private static void printCensusTable(@NonNull List<Map<String, Object>> events) {
        System.out.println("  time slot units warriors  workers  Q  A  T sites kills lost strength casts stunned err");
        double end = events.stream().filter(e -> "tl".equals(e.get("ev"))).mapToDouble(e -> num(e, "t")).max().orElse(
                0);
        for (Map<String, Object> e : events) {
            double t = num(e, "t");
            if ("tl".equals(e.get("ev")) && (Math.round(t) % CENSUS_EVERY == 0 || t == end)) {
                System.out.printf(Locale.ROOT,
                        "  %s  s%-2d %5.0f %8.0f %8.0f %3.0f%3.0f%3.0f %5.0f %5.0f %4.0f %8.0f %5.0f %7.0f %3.0f%n",
                        clock(t), (int) num(e, "s"), num(e, "units"), Game.value(e, "warriors"),
                        Game.value(e, "workers"), num(e, "Q"), num(e, "A"), num(e, "T"), num(e, "sites"),
                        num(e, "kills"), num(e, "lost"), num(e, "strength"), num(e, "magics"), num(e, "stunned"),
                        num(e, "err"));
            }
        }
    }

    /** One line of show's key events; lines sort by time, then by {@code order} (file order, death windows last). */
    private record KeyEvent(double t, int order, @NonNull String text) {
    }

    /** Key events in time order; deaths are summed per slot over short windows and shown from a few units up. */
    private static void printKeyEvents(@NonNull List<Map<String, Object>> events) {
        List<KeyEvent> lines = new ArrayList<>();
        TreeMap<Double, Map<String, Double>> deaths = new TreeMap<>(); // window start -> slot label -> units
        for (Map<String, Object> e : events) {
            String ev = String.valueOf(e.get("ev"));
            double t = num(e, "t");
            String slot = e.get("s") == null ? "  " : "s" + e.get("s");
            if (ev.equals("deaths")) {
                double window = Math.floor(t / DEATH_WINDOW) * DEATH_WINDOW;
                deaths.computeIfAbsent(window, w -> new TreeMap<>()).merge(slot, num(e, "n"), Double::sum);
            } else if (KEY_EVENTS.contains(ev) || (ev.equals("stunned") && num(e, "n") >= STUNS_SHOWN)) {
                Map<String, Object> rest = new LinkedHashMap<>(e);
                rest.keySet().removeAll(List.of("ev", "t", "s"));
                Object details = rest.isEmpty() ? "" : rest;
                String text = String.format(Locale.ROOT, "  %s  %s %-10s %s", clock(t), slot, ev, details);
                lines.add(new KeyEvent(t, lines.size(), text));
            }
        }
        deaths.forEach((window, units_by_slot) -> units_by_slot.forEach((slot, units) -> {
            if (units >= DEATHS_SHOWN) {
                String text = String.format(Locale.ROOT, "  %s  %s deaths     %.0f units in %d s", clock(window), slot,
                        units, DEATH_WINDOW);
                lines.add(new KeyEvent(window, Integer.MAX_VALUE, text));
            }
        }));
        System.out.println("  key events:");
        lines.stream().sorted(Comparator.comparingDouble(KeyEvent::t).thenComparingInt(KeyEvent::order)).forEach(
                line -> System.out.println(line.text()));
    }

    /** M:SS game clock, minutes padded to 2. */
    static @NonNull String clock(double seconds) {
        return String.format(Locale.ROOT, "%2d:%02d", (int) seconds / 60, (int) seconds % 60);
    }
}
