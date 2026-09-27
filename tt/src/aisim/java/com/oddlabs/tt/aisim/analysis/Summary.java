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
 * with Locale.ROOT. "A" is the AI under test, "B" its opponents' team.
 */
public final class Summary {
    /** How many of A's worst games and of the failed games the summary lists. */
    private static final int WORST_GAMES = 5;
    private static final int FAILED_GAMES = 10;

    private Summary() {
    }

    /** Prints the summary of {@code run} and saves it as summary.txt; exit code 1 if games failed or none counted. */
    public static int run(@NonNull String run) throws IOException {
        Map<?, ?> meta = Runs.meta(run);
        List<Map<String, Object>> rows = Runs.rows(run);
        List<Map<String, Object>> counted = where(rows, row -> row.get("winner") != null);
        List<Map<String, Object>> failed = where(rows, row -> row.get("winner") == null);
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        out.printf(Locale.ROOT, "aisim run %s: A=%s vs B=%s | %s | snapshot %s%n", run, meta.get("a"), meta.get("b"),
                meta.get("config"), meta.get("snap"));
        out.printf(Locale.ROOT, "games %d/%s done, %d counted, %d failed%n", rows.size(), meta.get("expected"),
                counted.size(), failed.size());
        String players = "a=" + meta.get("a") + " b=" + meta.get("b");
        String result_line = "RESULT " + run + " " + players + " n=" + counted.size() + "/" + meta.get("expected");
        if (!counted.isEmpty()) {
            Headline headline = Headline.of(counted);
            headline.print(out, counted);
            printMeans(out, counted);
            printHealth(out, counted);
            List<Game> games = Game.counted(run);
            out.println("curves (mean over games still running; A / B):");
            List<Curves.Series> teams = List.of(new Curves.Series("A", games, true),
                    new Curves.Series("B", games, false));
            Curves.table(teams, Curves.FIELDS, Curves.MINUTES).forEach(out::println);
            out.println(milestones(games));
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

    /** A's wins, losses and draws over some games. */
    private record WinLossDraw(int won, int lost, int drawn) {
        /** A's record over {@code rows}, only those that ended by {@code end} unless it is null. */
        static @NonNull WinLossDraw of(@NonNull List<Map<String, Object>> rows, @Nullable End end) {
            int won = 0;
            int lost = 0;
            int drawn = 0;
            for (Map<String, Object> row : rows) {
                if (end == null || end.name().equals(row.get("end"))) {
                    switch (Runs.resultOfA(row.get("winner"))) {
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

        /** The score with its interval, how the games ended, and A's record from each start slot. */
        void print(@NonNull PrintWriter out, @NonNull List<Map<String, Object>> counted) {
            WinLossDraw collapse = WinLossDraw.of(where(counted, row -> "collapse".equals(row.get("via"))), null);
            out.printf(Locale.ROOT, "score %.3f [%.3f, %.3f]   W %d  L %d  D %d%n", score, interval[0], interval[1],
                    all.won(), all.lost(), all.drawn());
            out.printf(Locale.ROOT,
                    "  by elimination W %d L %d (by collapse W %d L %d) | by timeout W %d L %d D %d (%.0f%% of games)%n",
                    elim.won(), elim.lost(), collapse.won(), collapse.lost(), timeout.won(), timeout.lost(),
                    timeout.drawn(), 100.0 * timeout.games() / counted.size());
            int vs = (int) num(counted.get(0), "vs"); // a 1 vs N game has the start slots 0..N
            List<String> slots = new ArrayList<>();
            for (int slot = 0; slot <= vs; slot++) {
                int start = slot;
                WinLossDraw record = WinLossDraw.of(where(counted, row -> num(row, "side") == start), null);
                slots.add(String.format(Locale.ROOT, "A in slot %d: W %d L %d D %d", slot, record.won(), record.lost(),
                        record.drawn()));
            }
            out.println("  " + String.join(" | ", slots));
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

    /** Swallowed AI errors and AI counters of both sides, and a warning when the recorder stopped early. */
    private static void printHealth(@NonNull PrintWriter out, @NonNull List<Map<String, Object>> counted) {
        out.println(swallowedErrors(counted, "A") + " | " + swallowedErrors(counted, "B"));
        out.println(counters(counted, "A") + " | " + counters(counted, "B"));
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

    /** A result row's block of team {@code team} ("A" or "B"): final census, AI counters, first AI error. */
    private static @NonNull Map<?, ?> block(@NonNull Map<String, Object> row, @NonNull String team) {
        return (Map<?, ?>) row.get(team);
    }

    /** How many errors a team's AIs threw and survived, in how many games, and the first one. */
    private static @NonNull String swallowedErrors(@NonNull List<Map<String, Object>> rows, @NonNull String team) {
        int errors = 0;
        int games = 0;
        Object first = null;
        for (Map<String, Object> row : rows) {
            Map<?, ?> block = block(row, team);
            int in_game = (int) num(block, "errors");
            errors += in_game;
            games += in_game > 0 ? 1 : 0;
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
        for (Map<String, Object> row : rows) {
            Map<?, ?> counters = (Map<?, ?>) block(row, team).get("counters");
            for (Map.Entry<?, ?> counter : counters.entrySet()) {
                sums.merge(String.valueOf(counter.getKey()), ((Number) counter.getValue()).doubleValue(), Double::sum);
            }
        }
        if (sums.isEmpty()) {
            return team + " counters: none";
        }
        StringBuilder text = new StringBuilder(team + " counters per game:");
        sums.forEach((key, sum) -> text.append(String.format(Locale.ROOT, " %s %.1f", key, sum / rows.size())));
        return text.toString();
    }

    /** A's worst games that were not wins (lowest score, then lowest margin), with commands to open them. */
    private static void printWorstGames(@NonNull PrintWriter out, @NonNull String run,
            @NonNull List<Map<String, Object>> counted) {
        Comparator<Map<String, Object>> by_score = Comparator.comparingDouble(row -> num(row, "score"));
        Comparator<Map<String, Object>> worst_first = by_score.thenComparingDouble(row -> num(row, "margin"));
        List<Map<String, Object>> not_won = where(counted, row -> num(row, "score") < 1);
        List<Map<String, Object>> worst = not_won.stream().sorted(worst_first).limit(WORST_GAMES).toList();
        if (worst.isEmpty()) {
            return;
        }
        out.println("worst games for A:");
        for (Map<String, Object> row : worst) {
            Object key = row.get("key");
            out.printf(Locale.ROOT,
                    "  %-9s %-4s %-7s %5.1fm margin %+.2f  ./aisim.sh show %s %s | ./aisim.sh replay %s %s%n",
                    key, Runs.resultOfA(row.get("winner")), row.get("end"), num(row, "t") / 60, num(row, "margin"),
                    run, key, run, key);
        }
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

    /** Median time of each milestone for A and for B's team (its first player to reach it), over the games. */
    private static @NonNull String milestones(@NonNull List<Game> games) {
        Map<Milestone, List<Double>> a_times = new EnumMap<>(Milestone.class);
        Map<Milestone, List<Double>> b_times = new EnumMap<>(Milestone.class);
        for (Milestone milestone : Milestone.values()) {
            a_times.put(milestone, new ArrayList<>());
            b_times.put(milestone, new ArrayList<>());
        }
        for (Game game : games) {
            // the first time each milestone was reached in this game; events come in time order
            Map<Milestone, Double> a_first = new EnumMap<>(Milestone.class);
            Map<Milestone, Double> b_first = new EnumMap<>(Milestone.class);
            Map<Integer, Integer> quarters_built = new TreeMap<>(); // by slot
            for (Map<String, Object> event : game.events()) {
                int slot = Game.slot(event);
                Milestone reached = milestoneOf(event, slot, quarters_built);
                if (reached != null) {
                    (slot == game.aSlot() ? a_first : b_first).putIfAbsent(reached, num(event, "t"));
                }
            }
            a_first.forEach((milestone, t) -> a_times.get(milestone).add(t));
            b_first.forEach((milestone, t) -> b_times.get(milestone).add(t));
        }
        StringBuilder line = new StringBuilder("milestones (median seconds A / B, and in how many games):");
        for (Milestone milestone : Milestone.values()) {
            List<Double> a = a_times.get(milestone);
            List<Double> b = b_times.get(milestone);
            line.append(String.format(Locale.ROOT, " %s %s/%s (%d/%d)", milestone, median(a), median(b), a.size(),
                    b.size()));
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
                // Q4 is one player's fourth quarters, so each slot counts on its own even within B's team
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
