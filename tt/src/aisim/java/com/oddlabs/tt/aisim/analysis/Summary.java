package com.oddlabs.tt.aisim.analysis;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import static com.oddlabs.tt.aisim.analysis.Game.num;

/**
 * {@code summary RUN}: a run's results, printed at the end of every run and saved as its summary.txt. Numbers print
 * with Locale.ROOT. The headline is team A's, the first team listed; then every team's record, named A, B, C, ...
 */
public final class Summary {
    /** How many of team A's worst games and of the failed games the summary lists. */
    private static final int WORST_GAMES = 5;
    private static final int FAILED_GAMES = 10;
    /** A's record from each start slot, this many slots to a line. */
    private static final int SLOTS_PER_LINE = 4;
    /** Map sizes and terrains in the skirmish menu's order, the order of the by-map lines. */
    private static final List<String> MAP_ORDER = List.of("small", "medium", "large", "huge", "tropical",
            "northern");

    private Summary() {
    }

    /** Prints the summary of {@code run} and saves it as summary.txt; exit code 1 if games failed or none counted. */
    public static int run(@NonNull String run) throws IOException {
        Map<?, ?> meta = Runs.meta(run);
        List<Map<String, Object>> rows = Runs.rows(run);
        List<Map<String, Object>> counted = where(rows, Runs::counts);
        List<Map<String, Object>> failed = where(rows, row -> !Runs.counts(row));
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        out.printf(Locale.ROOT, "aisim run %s: %s | %s | snapshot %s%n", run, meta.get("players"),
                meta.get("config"), meta.get("snap"));
        out.printf(Locale.ROOT, "games %d/%s done, %d counted, %d failed%n", rows.size(), meta.get("expected"),
                counted.size(), failed.size());
        String a = String.valueOf(meta.get("a"));
        String a_field = a.contains(" ") ? "\"" + a + "\"" : a; // one token, so the line splits on spaces
        String result_line = "RESULT " + run + " a=" + a_field + " n=" + counted.size() + "/" + meta.get("expected");
        if (!counted.isEmpty()) {
            Headline headline = Headline.of(counted);
            headline.print(out, counted);
            List<Game> games = Game.counted(run);
            printTeams(out, counted, games.get(0));
            printMeans(out, counted);
            printHealth(out, counted, games.get(0));
            List<Curves.Series> series = Curves.teamSeries(games);
            out.println("curves (mean over games still running; " + Curves.labels(series) + "):");
            Curves.table(series, Curves.FIELDS, Curves.MINUTES).forEach(out::println);
            out.println(milestones(games, series));
            printWorstGames(out, run, counted);
            result_line += headline.resultFields();
        }
        printFailed(out, failed);
        out.println(result_line + " fail=" + failed.size());
        out.println("next: ./aisim.sh compare BASE " + run + "   (paired, per game)");
        out.flush();
        System.out.print(text);
        Files.writeString(Runs.dir(run).resolve("summary.txt"), text.toString());
        return failed.isEmpty() && !counted.isEmpty() ? 0 : 1;
    }

    /** team A's wins, losses and draws over some games. */
    private record WinLossDraw(int won, int lost, int drawn) {
        /** team A's record over {@code rows}, only those that ended by {@code end} unless it is null. */
        static @NonNull WinLossDraw of(@NonNull List<Map<String, Object>> rows, @Nullable End end) {
            int won = 0;
            int lost = 0;
            int drawn = 0;
            for (Map<String, Object> row : rows) {
                if (end == null || end.name().equals(row.get("end"))) {
                    switch (String.valueOf(row.get("result"))) {
                        case "win" -> won++;
                        case "loss" -> lost++;
                        default -> drawn++;
                    }
                }
            }
            return new WinLossDraw(won, lost, drawn);
        }

        int games() {
            return won + lost + drawn;
        }
    }

    /** A's headline numbers over the counted games: its score with interval, W/L/D, kd30 and margin. */
    private record Headline(double score, double @NonNull [] interval, @NonNull WinLossDraw all,
                            @NonNull WinLossDraw elim, @NonNull WinLossDraw timeout, double kd30, double margin) {
        static @NonNull Headline of(@NonNull List<Map<String, Object>> counted) {
            double score = Stats.mean(counted, "score");
            return new Headline(score, Stats.wilson(score, counted.size()), WinLossDraw.of(counted, null),
                    WinLossDraw.of(counted, End.elim), WinLossDraw.of(counted, End.timeout),
                    Stats.mean(counted, "kd30"), Stats.mean(counted, "margin"));
        }

        /** The score with its interval, how the games ended, and A's record from each start slot and on each map. */
        void print(@NonNull PrintWriter out, @NonNull List<Map<String, Object>> counted) {
            WinLossDraw collapse = WinLossDraw.of(where(counted, row -> "collapse".equals(row.get("via"))), null);
            out.printf(Locale.ROOT, "score %.3f [%.3f, %.3f]   W %d  L %d  D %d%n", score, interval[0], interval[1],
                    all.won(), all.lost(), all.drawn());
            out.printf(Locale.ROOT,
                    "  by elimination W %d L %d (by collapse W %d L %d) | by timeout W %d L %d D %d (%.0f%% of games)%n",
                    elim.won(), elim.lost(), collapse.won(), collapse.lost(), timeout.won(), timeout.lost(),
                    timeout.drawn(), 100.0 * timeout.games() / counted.size());
            int players = (int) num(counted.get(0), "slots");
            List<String> slots = new ArrayList<>();
            for (int slot = 0; slot < players; slot++) {
                int start = slot;
                WinLossDraw record = WinLossDraw.of(where(counted, row -> num(row, "side") == start), null);
                slots.add(String.format(Locale.ROOT, "slot %d: W %d L %d D %d", slot, record.won(), record.lost(),
                        record.drawn()));
            }
            out.println("  by start (the first player's slot):");
            for (int i = 0; i < slots.size(); i += SLOTS_PER_LINE) {
                out.println("    " + String.join(" | ", slots.subList(i, Math.min(i + SLOTS_PER_LINE, slots.size()))));
            }
            printByMap(out, counted, "size", 0);
            printByMap(out, counted, "terrain", 1);
        }

        /**
         * A's record on each value of one map setting, word {@code word} of the rows' map ("large tropical h2 t10
         * s10"); nothing when every game had the same.
         */
        private static void printByMap(@NonNull PrintWriter out, @NonNull List<Map<String, Object>> counted,
                @NonNull String setting, int word) {
            Comparator<String> menu_order = Comparator.comparingInt(Summary::mapOrder);
            Map<String, List<Map<String, Object>>> by_value = new TreeMap<>(
                    menu_order.thenComparing(Comparator.naturalOrder()));
            for (Map<String, Object> row : counted) {
                String value = String.valueOf(row.get("map")).split(" ")[word];
                by_value.computeIfAbsent(value, v -> new ArrayList<>()).add(row);
            }
            if (by_value.size() < 2) {
                return;
            }
            List<String> records = new ArrayList<>();
            by_value.forEach((value, rows) -> {
                WinLossDraw record = WinLossDraw.of(rows, null);
                records.add(String.format(Locale.ROOT, "%s W %d L %d D %d", value, record.won(), record.lost(),
                        record.drawn()));
            });
            out.println("  by map " + setting + ": " + String.join(" | ", records));
        }

        /** The RESULT line's fields after n=. */
        @NonNull
        String resultFields() {
            return String.format(Locale.ROOT,
                    " score=%.3f [%.3f,%.3f] W%d L%d D%d elim=%d-%d timeout=%d-%d-%d kd30=%+.1f margin=%+.3f", score,
                    interval[0], interval[1], all.won(), all.lost(), all.drawn(), elim.won(), elim.lost(),
                    timeout.won(), timeout.lost(), timeout.drawn(), kd30, margin);
        }
    }

    /** The means line (with the cost per game when the rows have it), and a warning when most games timed out. */
    private static void printMeans(@NonNull PrintWriter out, @NonNull List<Map<String, Object>> counted) {
        String cost = "";
        if (counted.get(0).get("cpu") != null) {
            double cpu = Stats.mean(counted, "cpu");
            double wall = Stats.mean(counted, "wall");
            cost = String.format(Locale.ROOT, " | cost %.1f s CPU (%.1f s wall) per game", cpu, wall);
        }
        double kd30 = Stats.mean(counted, "kd30");
        double w15 = Stats.mean(counted, "w15");
        double margin = Stats.mean(counted, "margin");
        double minutes = Stats.mean(counted, "t") / 60;
        out.printf(Locale.ROOT, "means: kd30 %+.1f | w15 %.1f | margin %+.3f | length %.1f min%s%n", kd30, w15, margin,
                minutes, cost);
        if (WinLossDraw.of(counted, End.timeout).games() > counted.size() / 2) {
            out.println("""
                    !! most games timed out: judge variants by elim, kd30 and margin in ./aisim.sh compare, not by \
                    score""");
        }
    }

    /**
     * Every team's record, from the row's team blocks: its players, mean score and place, how often it won alone,
     * shared first place (drew) or lost, and in how many games it went out.
     */
    private static void printTeams(@NonNull PrintWriter out, @NonNull List<Map<String, Object>> counted,
            @NonNull Game names) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("team", "players", "score", "place", "won", "drew", "lost", "out"));
        for (int team = 0; team < blocks(counted.get(0)).size(); team++) {
            double score = 0;
            double place = 0;
            int won = 0;
            int drew = 0;
            int out_count = 0;
            for (Map<String, Object> row : counted) {
                Map<?, ?> block = blocks(row).get(team);
                score += num(block, "score");
                place += num(block, "place");
                double best = blocks(row).stream().mapToDouble(other -> num(other, "place")).min().orElse(1);
                won += num(block, "place") == 1 ? 1 : 0;
                drew += num(block, "place") == best && best > 1 ? 1 : 0;
                out_count += block.get("out") != null ? 1 : 0;
            }
            int n = counted.size();
            rows.add(List.of(names.teamName(team), String.valueOf(blocks(counted.get(0)).get(team).get("players")),
                    String.format(Locale.ROOT, "%.3f", score / n), String.format(Locale.ROOT, "%.2f", place / n),
                    String.valueOf(won), String.valueOf(drew), String.valueOf(n - won - drew),
                    String.valueOf(out_count)));
        }
        out.println("teams (place: 1 + the teams that outlasted it; won = first alone, drew = first shared):");
        Table.align(rows, "llr").forEach(out::println);
    }

    /** A result row's team blocks, by team number. */
    @SuppressWarnings("unchecked")
    private static @NonNull List<Map<String, Object>> blocks(@NonNull Map<String, Object> row) {
        return (List<Map<String, Object>>) row.get("teams");
    }

    /**
     * Swallowed AI errors and AI counters of each team whose AIs reported any, and a warning when the recorder stopped
     * early.
     */
    private static void printHealth(@NonNull PrintWriter out, @NonNull List<Map<String, Object>> counted,
            @NonNull Game names) {
        boolean any = false;
        for (int team = 0; team < blocks(counted.get(0)).size(); team++) {
            String errors = swallowedErrors(counted, team);
            String counters = counters(counted, team);
            if (!errors.isEmpty() || !counters.isEmpty()) {
                any = true;
                String what = String.join(" | ", errors.isEmpty() ? "no swallowed errors" : errors,
                        counters.isEmpty() ? "no counters" : counters);
                out.println(names.teamName(team) + ": " + what);
            }
        }
        if (!any) {
            out.println("swallowed errors (AiLog.error): none | counters (AiLog.count): none");
        }
        List<Object> broken = new ArrayList<>();
        for (Map<String, Object> row : where(counted, row -> Boolean.TRUE.equals(row.get("recorderFailed")))) {
            broken.add(row.get("key"));
        }
        if (!broken.isEmpty()) {
            String stopped = "!! the recorder stopped in " + broken.size() + " games";
            out.println(stopped + " (curves and milestones miss their later parts): " + broken);
        }
    }

    /** The rows that {@code keep} accepts. */
    private static @NonNull List<Map<String, Object>> where(@NonNull List<Map<String, Object>> rows,
            @NonNull Predicate<Map<String, Object>> keep) {
        return rows.stream().filter(keep).toList();
    }

    /** How many errors a team's AIs threw and survived, in how many games, and the first one; empty for none. */
    private static @NonNull String swallowedErrors(@NonNull List<Map<String, Object>> rows, int team) {
        int errors = 0;
        int games = 0;
        Object first = null;
        for (Map<String, Object> row : rows) {
            Map<?, ?> block = blocks(row).get(team);
            int in_game = (int) num(block, "errors");
            errors += in_game;
            games += in_game > 0 ? 1 : 0;
            if (first == null) {
                first = block.get("aiError");
            }
        }
        if (errors == 0) {
            return "";
        }
        String text = "swallowed errors " + errors + " in " + games + " games";
        return first == null ? text : text + " (first: " + first + ")";
    }

    /** The mean per game of each counter a team's AIs reported (AiLog.count); empty for none. */
    private static @NonNull String counters(@NonNull List<Map<String, Object>> rows, int team) {
        Map<String, Double> sums = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            Map<?, ?> counters = (Map<?, ?>) blocks(row).get(team).get("counters");
            for (Map.Entry<?, ?> counter : counters.entrySet()) {
                sums.merge(String.valueOf(counter.getKey()), ((Number) counter.getValue()).doubleValue(), Double::sum);
            }
        }
        if (sums.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("counters per game:");
        sums.forEach((key, sum) -> text.append(String.format(Locale.ROOT, " %s %.1f", key, sum / rows.size())));
        return text.toString();
    }

    /** team A's worst games that were not wins (lowest score, then lowest margin), with commands to open them. */
    private static void printWorstGames(@NonNull PrintWriter out, @NonNull String run,
            @NonNull List<Map<String, Object>> counted) {
        Comparator<Map<String, Object>> by_score = Comparator.comparingDouble(row -> num(row, "score"));
        Comparator<Map<String, Object>> worst_first = by_score.thenComparingDouble(row -> num(row, "margin"));
        List<Map<String, Object>> not_won = where(counted, row -> num(row, "score") < 1);
        List<Map<String, Object>> worst = not_won.stream().sorted(worst_first).limit(WORST_GAMES).toList();
        if (worst.isEmpty()) {
            return;
        }
        out.println("worst games for team A:");
        for (Map<String, Object> row : worst) {
            Object key = row.get("key");
            out.printf(Locale.ROOT,
                    "  %-9s %-4s %-7s %5.1fm margin %+.2f  ./aisim.sh show %s %s | ./aisim.sh replay %s %s%n",
                    key, row.get("result"), row.get("end"), num(row, "t") / 60, num(row, "margin"),
                    run, key, run, key);
        }
    }

    /** A map size's or terrain's place in {@link #MAP_ORDER}; after them all for any other value. */
    private static int mapOrder(@NonNull String value) {
        int index = MAP_ORDER.indexOf(value);
        return index < 0 ? MAP_ORDER.size() : index;
    }

    /** The first failed games with their problem. */
    private static void printFailed(@NonNull PrintWriter out, @NonNull List<Map<String, Object>> failed) {
        if (failed.isEmpty()) {
            return;
        }
        out.printf(Locale.ROOT, "!!! %d games failed (not counted):%n", failed.size());
        for (Map<String, Object> row : failed.stream().limit(FAILED_GAMES).toList()) {
            out.printf(Locale.ROOT, "  %s %s: %s%n", row.get("key"), row.get("end"), row.get("problem"));
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

    /**
     * Median time of each milestone for the teams of {@code series} (a team's first player to reach it), over the
     * games.
     */
    private static @NonNull String milestones(@NonNull List<Game> games, @NonNull List<Curves.Series> series) {
        List<Map<Milestone, List<Double>>> times = new ArrayList<>();
        for (int i = 0; i < series.size(); i++) {
            Map<Milestone, List<Double>> by_milestone = new EnumMap<>(Milestone.class);
            for (Milestone milestone : Milestone.values()) {
                by_milestone.put(milestone, new ArrayList<>());
            }
            times.add(by_milestone);
        }
        for (Game game : games) {
            // the first time each milestone was reached in this game by each series; events come in time order
            List<Map<Milestone, Double>> first = new ArrayList<>();
            series.forEach(s -> first.add(new EnumMap<>(Milestone.class)));
            Map<Integer, Integer> quarters_built = new TreeMap<>(); // by slot
            for (Map<String, Object> event : game.events()) {
                int slot = Game.slot(event);
                Milestone reached = milestoneOf(event, slot, quarters_built);
                for (int i = 0; reached != null && i < series.size(); i++) {
                    if (Curves.plays(game, series.get(i).team(), slot)) {
                        first.get(i).putIfAbsent(reached, num(event, "t"));
                    }
                }
            }
            for (int i = 0; i < series.size(); i++) {
                Map<Milestone, List<Double>> by_milestone = times.get(i);
                first.get(i).forEach((milestone, t) -> by_milestone.get(milestone).add(t));
            }
        }
        String labels = Curves.labels(series);
        StringBuilder line = new StringBuilder("milestones (median seconds " + labels + ", and in how many games):");
        for (Milestone milestone : Milestone.values()) {
            List<String> medians = times.stream().map(t -> median(t.get(milestone))).toList();
            List<String> counts = times.stream().map(t -> String.valueOf(t.get(milestone).size())).toList();
            line.append(String.format(Locale.ROOT, " %s %s (%s)", milestone, String.join("/", medians),
                    String.join("/", counts)));
        }
        return line.toString();
    }

    /** The milestone {@code event} of {@code slot} reaches, if any; counts each slot's quarters. */
    private static @Nullable Milestone milestoneOf(@NonNull Map<String, Object> event, int slot,
            @NonNull Map<Integer, Integer> quarters_built) {
        if ("chief".equals(event.get("ev"))) {
            return Milestone.chief;
        }
        if (!"built".equals(event.get("ev"))) {
            return null;
        }
        return switch (String.valueOf(event.get("b"))) {
            case "quarters" -> {
                // Q4 is one player's fourth quarters, so each slot counts on its own even within a team
                int count = quarters_built.merge(slot, 1, Integer::sum);
                yield count == 1 ? Milestone.Q1 : count == 4 ? Milestone.Q4 : null;
            }
            case "armory" -> Milestone.A1;
            case "tower" -> Milestone.T1;
            default -> null;
        };
    }

    /** The upper median in whole seconds; "-" for none. */
    private static @NonNull String median(@NonNull List<Double> seconds) {
        if (seconds.isEmpty()) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.0f", seconds.stream().sorted().toList().get(seconds.size() / 2));
    }
}
