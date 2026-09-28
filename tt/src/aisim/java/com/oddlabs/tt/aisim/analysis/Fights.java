package com.oddlabs.tt.aisim.analysis;

import com.oddlabs.tt.aisim.UsageException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static com.oddlabs.tt.aisim.analysis.Game.num;

/**
 * Fights: deaths and razed buildings grouped by time and place. {@code fights RUN KEY} (or {@code FILE.jsonl}) lists
 * the fights of one game with every team's losses; {@code fights RUN} sums a run's fights from team A's side, by
 * where they were, in the games it won and lost, and lists its worst ones.
 */
public final class Fights {
    public static final int DEFAULT_MIN_DEATHS = 8;
    /**
     * An event joins the nearest fight whose centre is within JOIN_RADIUS cells of it and whose last event was at most
     * JOIN_GAP_SECONDS before it; otherwise it starts a new fight.
     */
    private static final double JOIN_RADIUS = 40;
    private static final double JOIN_GAP_SECONDS = 20;
    /** The unit kinds of a deaths event, in the recorder's order (Census.UNIT_KINDS). */
    private static final String[] KINDS = {"rock", "iron", "rubber", "peon", "chief"};
    /** The letter of each of KINDS in a loss: rock, iron and chicken (rubber) warriors, peons, chieftain. */
    private static final String[] KIND_LETTERS = {"r", "i", "c", "p", "C"};
    /**
     * A fight is at a team's base when its share of the way from that team's nearest start to the next team's nearest
     * start is below this; otherwise it is between the two.
     */
    private static final double AT_BASE_BELOW = 0.35;
    /** Where a fight was from team A's side, in the run table: the index of a fight's {@link Fight#side}. */
    private static final String[] SIDES = {"A's base", "between", "an enemy base"};
    private static final int AT_A = 0;
    private static final int BETWEEN = 1;
    private static final int AT_ENEMY = 2;
    private static final int WORST_SHOWN = 8;
    private static final String LEGEND = """
            (where: a team's base when the fight's share of the way from the nearest team's start to the next team's \
            is below 0.35, else between the two; lost: r i c rock, iron, chicken warriors, p peons, C chieftain; net: \
            the enemies' losses minus team A's, - where team A had no part)""";

    private Fights() {
    }

    /** One fight: its time span, its centre, and each team's losses and razed buildings, by team number. */
    private static final class Fight {
        private final double start;
        private double end;
        /** The events' positions summed, weighted by deaths (a razed building weighs one). */
        private double weighted_x;
        private double weighted_y;
        private double weight;
        private final @NonNull Map<Integer, int[]> lost = new TreeMap<>();
        private final @NonNull Map<Integer, List<String>> razed = new TreeMap<>();
        /** The nearest team's base, or between it and the next one ({@link #near}, {@link #next}); set by locate. */
        private int near = -1;
        private int next = -1;
        private double share = Double.NaN;

        Fight(double start) {
            this.start = start;
        }

        double x() {
            return weighted_x / weight;
        }

        double y() {
            return weighted_y / weight;
        }

        /** Adds a deaths event or a razed building of {@code team}. */
        void add(@NonNull Map<String, Object> event, int team) {
            boolean deaths = "deaths".equals(event.get("ev"));
            double event_weight = deaths ? num(event, "n") : 1;
            end = Math.max(end, num(event, "t"));
            weighted_x += num(event, "x") * event_weight;
            weighted_y += num(event, "y") * event_weight;
            weight += event_weight;
            if (deaths) {
                int[] kinds = lost.computeIfAbsent(team, t -> new int[KINDS.length]);
                for (int kind = 0; kind < KINDS.length; kind++) {
                    kinds[kind] += (int) num(event, KINDS[kind]);
                }
            } else {
                razed.computeIfAbsent(team, t -> new ArrayList<>()).add(event.get("b") + (event.get(
                        "site") != null ? " site" : ""));
            }
        }

        int lost(int team) {
            int total = 0;
            for (int n : lost.getOrDefault(team, new int[0])) {
                total += n;
            }
            return total;
        }

        int deaths() {
            return lost.keySet().stream().mapToInt(this::lost).sum();
        }

        /** The losses of every team but A's. */
        int enemiesLost(@NonNull Game game) {
            return deaths() - lost(game.aTeam());
        }

        /** The enemies' losses minus team A's. */
        int net(@NonNull Game game) {
            return enemiesLost(game) - lost(game.aTeam());
        }

        /**
         * Places the fight by the two teams whose nearest starts are nearest to it: at the nearest one's base when the
         * fight's share of the way from it to the next is below {@link #AT_BASE_BELOW}, else between the two.
         */
        void locate(@NonNull Game game) {
            List<Map<String, Object>> players = game.players();
            Map<Integer, Double> distance = new TreeMap<>();
            for (int slot = 0; slot < players.size(); slot++) {
                double to_start = Math.hypot(x() - num(players.get(slot), "x"), y() - num(players.get(slot), "y"));
                distance.merge(game.teamOf(slot), to_start, Math::min);
            }
            List<Integer> by_distance = distance.keySet().stream().sorted(Comparator.comparingDouble(
                    distance::get)).toList();
            if (by_distance.size() < 2) {
                return; // a header without players: nowhere, share NaN
            }
            near = by_distance.get(0);
            next = by_distance.get(1);
            share = distance.get(near) / (distance.get(near) + distance.get(next));
        }

        boolean atBase() {
            return share < AT_BASE_BELOW;
        }

        /** Where the fight was from team A's side: an index into {@link #SIDES}. */
        int side(@NonNull Game game) {
            if (!atBase()) {
                return BETWEEN;
            }
            return near == game.aTeam() ? AT_A : AT_ENEMY;
        }

        /**
         * Whether team A took part, as far as the recording tells: it lost units or buildings, or the fight was at
         * its
         * base. In a game of two teams every fight is A's, since only its enemies and it can die.
         */
        boolean involvesA(@NonNull Game game) {
            int a = game.aTeam();
            return game.teams().size() <= 2 || lost(a) > 0 || razed.containsKey(a) || (atBase() && near == a);
        }

        @NonNull
        String where(@NonNull Game game) {
            if (Double.isNaN(share)) {
                return "?";
            }
            String place = atBase() ? game.teamName(near) + "'s base" : game.teamName(near) + "-" + game.teamName(next);
            return place + String.format(Locale.ROOT, " %.2f", share);
        }

        @NonNull
        String time() {
            return Table.clock(start).trim() + "-" + Table.clock(end).trim();
        }

        /** A team's losses: the total, then each kind that died, such as "12 (3r 9p)". */
        @NonNull
        String losses(int team) {
            int[] kinds = lost.getOrDefault(team, new int[KINDS.length]);
            List<String> parts = new ArrayList<>();
            for (int kind = 0; kind < KINDS.length; kind++) {
                if (kinds[kind] > 0) {
                    parts.add(kinds[kind] + KIND_LETTERS[kind]);
                }
            }
            return parts.isEmpty() ? "0" : lost(team) + " (" + String.join(" ", parts) + ")";
        }

        /** Every team's losses that has any, such as "A 12 (3r 9p), C 4 (4i)". */
        @NonNull
        String allLosses(@NonNull Game game) {
            List<String> parts = new ArrayList<>();
            for (int team : game.teams()) {
                if (lost(team) > 0) {
                    parts.add(game.teamName(team) + " " + losses(team));
                }
            }
            return String.join(", ", parts);
        }

        /** The buildings each team lost, such as "A: tower; B: quarters, armory site". */
        @NonNull
        String razedText(@NonNull Game game) {
            List<String> parts = new ArrayList<>();
            for (int team : game.teams()) {
                if (razed.containsKey(team)) {
                    parts.add(game.teamName(team) + ": " + String.join(", ", razed.get(team)));
                }
            }
            return String.join("; ", parts);
        }
    }

    /** {@code fights RUN} (a whole run), or {@code fights RUN KEY} / {@code fights FILE.jsonl} (one game). */
    public static int run(@NonNull List<String> args, int min_deaths) {
        if (args.size() == 1 && !args.get(0).endsWith(".jsonl")) {
            printRun(args.get(0), min_deaths);
        } else {
            printGame(Game.named(args), min_deaths);
        }
        return 0;
    }

    /** The fights of {@code game} with at least {@code min_deaths} deaths, in the order they started. */
    private static @NonNull List<Fight> fightsOf(@NonNull Game game, int min_deaths) {
        List<Fight> fights = new ArrayList<>();
        for (Map<String, Object> event : game.events()) {
            if (!"deaths".equals(event.get("ev")) && !"razed".equals(event.get("ev"))) {
                continue;
            }
            Fight fight = fightToJoin(fights, event);
            if (fight == null) {
                fight = new Fight(num(event, "t"));
                fights.add(fight);
            }
            fight.add(event, game.teamOf(Game.slot(event)));
        }
        fights.removeIf(fight -> fight.deaths() < min_deaths);
        fights.forEach(fight -> fight.locate(game));
        return fights;
    }

    /** The fight {@code event} joins, or null for a new one. */
    private static @Nullable Fight fightToJoin(@NonNull List<Fight> fights, @NonNull Map<String, Object> event) {
        Fight nearest = null;
        double nearest_distance = JOIN_RADIUS;
        for (Fight fight : fights) {
            double distance = Math.hypot(num(event, "x") - fight.x(), num(event, "y") - fight.y());
            if (num(event, "t") - fight.end <= JOIN_GAP_SECONDS && distance <= nearest_distance) {
                nearest = fight;
                nearest_distance = distance;
            }
        }
        return nearest;
    }

    /**
     * Every fight of one game: a column of losses per team (with more than {@link Curves#TEAMS_SHOWN} teams, one column
     * that lists the teams that lost any) and the buildings razed.
     */
    private static void printGame(@NonNull Game game, int min_deaths) {
        List<Fight> fights = fightsOf(game, min_deaths);
        List<String> teams = game.teams().stream().map(team -> game.teamName(team) + " = " + String.join(" ",
                game.slotsOf(team).stream().map(slot -> "s" + slot).toList())).toList();
        System.out.printf(Locale.ROOT, "%s: %d fights with %d+ deaths | %s%n", game, fights.size(), min_deaths,
                String.join(" | ", teams));
        boolean column_per_team = game.teams().size() <= Curves.TEAMS_SHOWN;
        List<List<String>> rows = new ArrayList<>();
        List<String> header = new ArrayList<>(List.of("time", "where"));
        if (column_per_team) {
            game.teams().forEach(team -> header.add(game.teamName(team) + " lost"));
        } else {
            header.add("lost");
        }
        header.add("net");
        header.add("razed");
        rows.add(header);
        for (Fight fight : fights) {
            List<String> row = new ArrayList<>(List.of(fight.time(), fight.where(game)));
            if (column_per_team) {
                game.teams().forEach(team -> row.add(fight.losses(team)));
            } else {
                row.add(fight.allLosses(game));
            }
            row.add(fight.involvesA(game) ? String.format(Locale.ROOT, "%+d", fight.net(game)) : "-");
            row.add(fight.razedText(game));
            rows.add(row);
        }
        Table.align(rows, column_per_team ? "ll" + "r".repeat(game.teams().size() + 1) + "l" : "lllrl").forEach(
                System.out::println);
        System.out.println(LEGEND);
    }

    /** A run's fights at one of the {@link #SIDES}, summed over its games. */
    private static final class SideTotals {
        int fights;
        int a_lost;
        int enemies_lost;
        int net_in_won;
        int net_in_lost;
    }

    private record FightInGame(@NonNull Game game, @NonNull Fight fight) {
    }

    /**
     * A run's fights from team A's side: per place, the fights and losses per game, and the net in the games it won
     * and lost. In games of three teams or more, only the fights team A took part in count.
     */
    private static void printRun(@NonNull String run, int min_deaths) {
        List<Game> games = Game.counted(run);
        if (games.isEmpty()) {
            throw new UsageException("run " + run + " has no counted game");
        }
        SideTotals[] totals = {new SideTotals(), new SideTotals(), new SideTotals()};
        List<FightInGame> all = new ArrayList<>();
        int without_a = 0;
        for (Game game : games) {
            for (Fight fight : fightsOf(game, min_deaths)) {
                if (!fight.involvesA(game)) {
                    without_a++;
                    continue;
                }
                SideTotals side = totals[fight.side(game)];
                side.fights++;
                side.a_lost += fight.lost(game.aTeam());
                side.enemies_lost += fight.enemiesLost(game);
                switch (game.result()) {
                    case "win" -> side.net_in_won += fight.net(game);
                    case "loss" -> side.net_in_lost += fight.net(game);
                    default -> {
                    }
                }
                all.add(new FightInGame(game, fight));
            }
        }
        int n = games.size();
        long won = games.stream().filter(game -> game.result().equals("win")).count();
        long lost = games.stream().filter(game -> game.result().equals("loss")).count();
        System.out.printf(Locale.ROOT, "fights of %s: %d of team A with %d+ deaths in %d games, per game:%n", run,
                all.size(), min_deaths, n);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("where", "fights", "A lost", "enemies lost", "net", "net when A won (" + won + ")",
                "net when A lost (" + lost + ")"));
        for (int p = 0; p < SIDES.length; p++) {
            SideTotals side = totals[p];
            rows.add(List.of(SIDES[p], perGame(side.fights, n), perGame(side.a_lost, n), perGame(side.enemies_lost, n),
                    signedPerGame(side.enemies_lost - side.a_lost, n), signedPerGame(side.net_in_won, won),
                    signedPerGame(side.net_in_lost, lost)));
        }
        Table.align(rows).forEach(System.out::println);
        if (without_a > 0) {
            System.out.println(
                    "  (" + without_a + " fights left out: team A lost nothing in them and they were not " + "at its base, so they were most likely among other teams)");
        }

        System.out.println("team A's worst fights:");
        all.sort(Comparator.comparingInt(found -> found.fight().net(found.game()))); // stable: ties stay in order
        rows.clear();
        rows.add(List.of("game", "time", "where", "A lost", "enemies lost", "net"));
        for (FightInGame found : all.subList(0, Math.min(WORST_SHOWN, all.size()))) {
            Fight fight = found.fight();
            Game game = found.game();
            rows.add(List.of(game.key(), fight.time(), fight.where(game), fight.losses(game.aTeam()),
                    String.valueOf(fight.enemiesLost(game)), String.format(Locale.ROOT, "%+d", fight.net(game))));
        }
        Table.align(rows, "lllr").forEach(System.out::println);
        System.out.println(LEGEND);
        System.out.println("next: ./aisim.sh fights " + run + " KEY   (every fight of one game)");
    }

    private static @NonNull String perGame(int total, long games) {
        return String.format(Locale.ROOT, "%.1f", games == 0 ? 0 : total / (double) games);
    }

    private static @NonNull String signedPerGame(int total, long games) {
        return String.format(Locale.ROOT, "%+.1f", games == 0 ? 0 : total / (double) games);
    }
}
