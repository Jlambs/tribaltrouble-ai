package com.oddlabs.tt.aisim.analysis;

import com.oddlabs.tt.aisim.UsageException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Curve tables: census values at chosen game minutes, as means over the games still running then. {@code summary}
 * prints every team (A / B / ...), {@code compare} team A of each run, and {@code curves} whichever fields, minutes
 * and games you ask for.
 */
public final class Curves {
    public static final List<String> FIELDS = List.of("units", "warriors", "workers", "quarters", "armories", "towers",
            "kills", "strength");
    public static final List<Integer> MINUTES = List.of(5, 10, 15, 20, 30, 45, 60, 90);
    /** Appended to a field, the field's gain over the minute before. */
    private static final String PER_MINUTE = "/min";
    /** A curve table shows each team up to this many teams; with more, team A and the others' mean per team. */
    static final int TEAMS_SHOWN = 4;
    /** {@link Series#team} of team A, whatever its number. */
    static final int A_TEAM = -1;
    /** {@link Series#team} of every team but A's, averaged per team. */
    static final int OTHER_TEAMS = -2;

    private Curves() {
    }

    /**
     * One number per cell: the census of one team, summed over its slots, over games. {@code team} is a team number,
     * {@link #A_TEAM} or {@link #OTHER_TEAMS} (every team but A's, the sum divided by their number).
     */
    record Series(@NonNull String label, @NonNull List<Game> games, int team) {
    }

    /**
     * The series of a run's teams: each team by its name (A / B / ...), or with more than {@link #TEAMS_SHOWN} teams,
     * team A and the other teams' mean per team. The first game names the teams.
     */
    static @NonNull List<Series> teamSeries(@NonNull List<Game> games) {
        List<Series> series = new ArrayList<>();
        List<Integer> teams = games.isEmpty() ? List.of() : games.get(0).teams();
        if (teams.size() > TEAMS_SHOWN) {
            series.add(new Series("A", games, A_TEAM));
            series.add(new Series("others", games, OTHER_TEAMS));
            return series;
        }
        for (int team : teams) {
            series.add(new Series(games.get(0).teamName(team), games, team));
        }
        return series;
    }

    /** Whether player {@code slot} of {@code game} counts in a series of {@code team}. */
    static boolean plays(@NonNull Game game, int team, int slot) {
        return switch (team) {
            case A_TEAM -> game.isA(slot);
            case OTHER_TEAMS -> !game.isA(slot);
            default -> game.teamOf(slot) == team;
        };
    }

    /** The labels of some series joined with " / ", such as "A / B / C". */
    static @NonNull String labels(@NonNull List<Series> series) {
        return String.join(" / ", series.stream().map(Series::label).toList());
    }

    /**
     * {@code curves RUN [RUN...]}: every team of one run, team A in the games it won / lost ({@code split}), or A's
     * team of each run over the games they all counted.
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
            series.add(new Series("won", won, A_TEAM));
            series.add(new Series("lost", lost, A_TEAM));
            what = "team A in the games it won / lost";
        } else if (runs.size() == 1) {
            series.addAll(teamSeries(Game.counted(runs.get(0))));
            what = labels(series);
        } else {
            series.addAll(onCommonGames(runs));
            int common = series.get(0).games().size();
            what = "team A in " + String.join(" / ", runs) + ", over the " + common + " games every run counted";
        }
        String names = String.join(", ", runs);
        System.out.println("curves of " + names + ": mean over the games still running (" + what + ")");
        table(series, fields, minutes).forEach(System.out::println);
        return 0;
    }

    /** team A of each run, over the games every run counted. */
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
            series.add(new Series(runs.get(i), games, A_TEAM));
        }
        return series;
    }

    /**
     * The table: a line per minute while every series has a game running, with how many are, then a column per
     * field. Each cell holds the series' means over the games still running then, joined with "/".
     *
     * <p>It reads each game once, adds it to every cell, and forgets it ({@link Game#forget}), so a run of any size
     * fits in memory: holding every game's file at once ran out of heap past ~150 MB of game files.
     */
    static @NonNull List<String> table(@NonNull List<Series> series, @NonNull List<String> fields,
            @NonNull List<Integer> minutes) {
        int[][] running = new int[series.size()][minutes.size()];
        double[][][] sums = new double[series.size()][minutes.size()][fields.size()];
        int[][][] counts = new int[series.size()][minutes.size()][fields.size()];
        // each game once, with every series that holds it; per series the games are added in the series' order
        IdentityHashMap<Game, List<Integer>> series_of = new IdentityHashMap<>();
        List<Game> games = new ArrayList<>();
        for (int s = 0; s < series.size(); s++) {
            for (Game game : series.get(s).games()) {
                List<Integer> holders = series_of.computeIfAbsent(game, g -> {
                    games.add(g);
                    return new ArrayList<>();
                });
                holders.add(s);
            }
        }
        for (Game game : games) {
            for (int s : series_of.get(game)) {
                int team = series.get(s).team();
                for (int m = 0; m < minutes.size(); m++) {
                    double t = minutes.get(m) * 60.0;
                    if (t <= game.lastSample()) {
                        running[s][m]++;
                    }
                    for (int f = 0; f < fields.size(); f++) {
                        Double value = value(game, team, fields.get(f), t);
                        if (value != null) {
                            sums[s][m][f] += value;
                            counts[s][m][f]++;
                        }
                    }
                }
            }
            game.forget();
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> header = new ArrayList<>(List.of("min", "games"));
        header.addAll(fields);
        rows.add(header);
        for (int m = 0; m < minutes.size(); m++) {
            int minute = m;
            List<Integer> running_now = new ArrayList<>();
            for (int[] by_minute : running) {
                running_now.add(by_minute[m]);
            }
            if (running_now.contains(0)) {
                break;
            }
            List<String> row = new ArrayList<>(List.of(String.valueOf(minutes.get(m))));
            boolean all_equal = running_now.stream().distinct().count() == 1;
            row.add(all_equal ? String.valueOf(running_now.get(0)) : joined(running_now, String::valueOf));
            for (int f = 0; f < fields.size(); f++) {
                int field = f;
                List<Integer> indices = new ArrayList<>();
                for (int s = 0; s < series.size(); s++) {
                    indices.add(s);
                }
                row.add(joined(indices, s -> Table.number(
                        counts[s][minute][field] == 0 ? 0 : sums[s][minute][field] / counts[s][minute][field])));
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
     * {@code field} of {@code team} (a team number, {@link #A_TEAM} or {@link #OTHER_TEAMS}) in {@code game} at game
     * second {@code t}; null when the game is over. {@code F/min} is the gain of F over the minute before.
     */
    static @Nullable Double value(@NonNull Game game, int team, @NonNull String field, double t) {
        if (!field.endsWith(PER_MINUTE)) {
            return teamSum(game, team, field, t);
        }
        String name = withoutPerMinute(field);
        Double now = teamSum(game, team, name, t);
        Double before = teamSum(game, team, name, t - 60);
        return now == null ? null : now - (before == null ? 0 : before);
    }

    /** {@code field} summed over the team's slots at {@code t} (the other teams' per team); null when it is over. */
    private static @Nullable Double teamSum(@NonNull Game game, int team, @NonNull String field, double t) {
        if (t > game.lastSample()) {
            return null;
        }
        List<Integer> slots = switch (team) {
            case A_TEAM -> game.aSlots();
            case OTHER_TEAMS -> game.bSlots();
            default -> game.slotsOf(team);
        };
        double sum = 0;
        for (int slot : slots) {
            var census = game.census(slot, t);
            if (census != null) {
                sum += Game.value(census, field);
            }
        }
        return team == OTHER_TEAMS ? sum / Math.max(1, game.teams().size() - 1) : sum;
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
