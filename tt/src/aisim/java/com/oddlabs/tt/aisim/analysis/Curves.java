package com.oddlabs.tt.aisim.analysis;

import com.oddlabs.tt.aisim.UsageException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Curve tables: census values at chosen game minutes, as means over the games still running then. {@code summary}
 * prints A / B, {@code compare} A of each run, and {@code curves} whichever fields, minutes and games you ask for.
 */
public final class Curves {
    public static final List<String> FIELDS = List.of("units", "warriors", "workers", "quarters", "armories", "towers",
            "kills", "strength");
    public static final List<Integer> MINUTES = List.of(5, 10, 15, 20, 30, 45, 60, 90);
    /** Appended to a field, the field's gain over the minute before. */
    private static final String PER_MINUTE = "/min";

    private Curves() {
    }

    /** One number per cell: the census of team A ({@code teamA}) or of team B, summed over its slots, over games. */
    record Series(@NonNull String label, @NonNull List<Game> games, boolean teamA) {
        /** How many of the games are still running at game second {@code t}. */
        int running(double t) {
            return (int) games.stream().filter(game -> t <= game.lastSample()).count();
        }

        /** The mean of {@code field} over the games still running at {@code t}; 0 when none is. */
        double mean(@NonNull String field, double t) {
            double sum = 0;
            int running = 0;
            for (Game game : games) {
                Double value = value(game, teamA, field, t);
                if (value != null) {
                    sum += value;
                    running++;
                }
            }
            return running == 0 ? 0 : sum / running;
        }
    }

    /**
     * {@code curves RUN [RUN...]}: A / B of one run, A in the games it won / lost ({@code split}), or A of each run
     * over the games they all counted.
     */
    public static int run(@NonNull List<String> runs, @NonNull List<String> fields, @NonNull List<Integer> minutes,
            boolean split) {
        checkFields(fields);
        List<Series> series = new ArrayList<>();
        String what;
        if (split) {
            if (runs.size() > 1) {
                throw new UsageException("--split works on one run");
            }
            String run = runs.get(0);
            List<Game> games = Game.counted(run);
            List<Game> won = games.stream().filter(game -> game.result().equals("win")).toList();
            List<Game> lost = games.stream().filter(game -> game.result().equals("loss")).toList();
            if (won.isEmpty() || lost.isEmpty()) {
                String has = run + " has " + won.size() + " won and " + lost.size() + " lost";
                throw new UsageException("--split needs games A won and games it lost; " + has);
            }
            series.add(new Series("won", won, true));
            series.add(new Series("lost", lost, true));
            what = "A in the games it won / lost";
        } else if (runs.size() == 1) {
            List<Game> games = Game.counted(runs.get(0));
            series.add(new Series("A", games, true));
            series.add(new Series("B", games, false));
            what = "A / B";
        } else {
            series.addAll(onCommonGames(runs));
            int common = series.get(0).games().size();
            what = "A in " + String.join(" / ", runs) + ", over the " + common + " games every run counted";
        }
        String names = String.join(", ", runs);
        System.out.println("curves of " + names + ": mean over the games still running (" + what + ")");
        table(series, fields, minutes).forEach(System.out::println);
        return 0;
    }

    /** A of each run, over the games every run counted. */
    private static @NonNull List<Series> onCommonGames(@NonNull List<String> runs) {
        List<List<Game>> games_by_run = new ArrayList<>();
        Set<String> common = null;
        for (String run : runs) {
            List<Game> games = Game.counted(run);
            games_by_run.add(games);
            Set<String> keys = new HashSet<>(games.stream().map(Game::key).toList());
            if (common == null) {
                common = keys;
            } else {
                common.retainAll(keys);
            }
        }
        if (common == null || common.isEmpty()) {
            throw new UsageException("the runs have no counted game in common");
        }
        List<Series> series = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            Set<String> shared = common;
            List<Game> games = games_by_run.get(i).stream().filter(game -> shared.contains(game.key())).toList();
            series.add(new Series(runs.get(i), games, true));
        }
        return series;
    }

    /**
     * The table: a line per minute while every series has a game running, with how many are, then a column per
     * field. Each cell holds the series' means, joined with "/".
     */
    static @NonNull List<String> table(@NonNull List<Series> series, @NonNull List<String> fields,
            @NonNull List<Integer> minutes) {
        List<List<String>> rows = new ArrayList<>();
        List<String> header = new ArrayList<>(List.of("min", "games"));
        header.addAll(fields);
        rows.add(header);
        for (int minute : minutes) {
            double t = minute * 60.0;
            List<Integer> running = series.stream().map(s -> s.running(t)).toList();
            if (running.contains(0)) {
                break;
            }
            List<String> row = new ArrayList<>(List.of(String.valueOf(minute)));
            boolean all_equal = running.stream().distinct().count() == 1;
            row.add(all_equal ? String.valueOf(running.get(0)) : joined(running, String::valueOf));
            for (String field : fields) {
                row.add(joined(series, s -> Table.number(s.mean(field, t))));
            }
            rows.add(row);
        }
        if (rows.size() == 1) {
            return List.of("  (no curves: not every series has a game that ran to minute " + minutes.get(0) + ")");
        }
        return Table.align(rows, "r");
    }

    /** One text per element, joined with "/". */
    private static <T> @NonNull String joined(@NonNull List<T> list, @NonNull Function<T, String> text) {
        return String.join("/", list.stream().map(text).toList());
    }

    /**
     * {@code field} of A ({@code team_a}) or of B's team in {@code game} at game second {@code t}; null when the game
     * is over. {@code F/min} is the gain of F over the minute before.
     */
    static @Nullable Double value(@NonNull Game game, boolean team_a, @NonNull String field, double t) {
        if (!field.endsWith(PER_MINUTE)) {
            return teamSum(game, team_a, field, t);
        }
        String name = withoutPerMinute(field);
        Double now = teamSum(game, team_a, name, t);
        Double before = teamSum(game, team_a, name, t - 60);
        return now == null ? null : now - (before == null ? 0 : before);
    }

    /** {@code field} summed over team A's or team B's slots at {@code t}; null when the game is over. */
    private static @Nullable Double teamSum(@NonNull Game game, boolean team_a, @NonNull String field, double t) {
        if (t > game.lastSample()) {
            return null;
        }
        double sum = 0;
        for (int slot : team_a ? game.aSlots() : game.bSlots()) {
            var census = game.census(slot, t);
            if (census != null) {
                sum += Game.value(census, field);
            }
        }
        return sum;
    }

    /** Rejects unknown field names before anything is printed. */
    private static void checkFields(@NonNull List<String> fields) {
        for (String field : fields) {
            if (!Game.isField(withoutPerMinute(field))) {
                throw new UsageException("""
                        unknown field %s: use a census field (docs/aisim.md, Files), warriors, workers, harvested or \
                        stock, optionally with /min""".formatted(field));
            }
        }
    }

    private static @NonNull String withoutPerMinute(@NonNull String field) {
        return field.endsWith(PER_MINUTE) ? field.substring(0, field.length() - PER_MINUTE.length()) : field;
    }
}
