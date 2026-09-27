package com.oddlabs.tt.aisim.analysis;

import com.oddlabs.tt.aisim.UsageException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.oddlabs.tt.aisim.analysis.Game.num;

/**
 * Fights: deaths and razed buildings grouped by time and place. {@code fights RUN KEY} (or {@code FILE.jsonl}) lists
 * the fights of one game; {@code fights RUN} sums a run's fights by where they were, in the games A won and lost, and
 * lists A's worst ones.
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
    /** Where a fight was, by its share of the way from A's start to the nearest start of B's. */
    private static final String[] PLACES = {"A's base", "middle", "B's base"};
    private static final double A_BASE_BELOW = 0.35;
    private static final double B_BASE_ABOVE = 0.65;
    private static final int WORST_SHOWN = 8;
    private static final String LEGEND = """
            (where: share of the way from A's start to B's nearest; lost: r i c rock, iron, chicken warriors, p peons, \
            C chieftain; net: B's losses minus A's)""";
    private static final int A = 0;
    private static final int B = 1;

    private Fights() {
    }

    /** One fight: its time span, its centre, and each side's losses (index {@link #A} or {@link #B}). */
    private static final class Fight {
        private final double start;
        private double end;
        /** The events' positions summed, weighted by deaths (a razed building weighs one). */
        private double weighted_x;
        private double weighted_y;
        private double weight;
        private final int @NonNull [] @NonNull [] lost = new int[2][KINDS.length];
        private final @NonNull List<List<String>> razed = List.of(new ArrayList<>(), new ArrayList<>());
        /** The index into PLACES, and the share it comes from; set by {@link #locate}. */
        private int place;
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

        /** Adds a deaths event or a razed building of {@code side}. */
        void add(@NonNull Map<String, Object> event, int side) {
            boolean deaths = "deaths".equals(event.get("ev"));
            double event_weight = deaths ? num(event, "n") : 1;
            end = Math.max(end, num(event, "t"));
            weighted_x += num(event, "x") * event_weight;
            weighted_y += num(event, "y") * event_weight;
            weight += event_weight;
            if (deaths) {
                for (int kind = 0; kind < KINDS.length; kind++) {
                    lost[side][kind] += (int) num(event, KINDS[kind]);
                }
            } else {
                razed.get(side).add(event.get("b") + (event.get("site") != null ? " site" : ""));
            }
        }

        int lost(int side) {
            int total = 0;
            for (int n : lost[side]) {
                total += n;
            }
            return total;
        }

        /** B's losses minus A's. */
        int net() {
            return lost(B) - lost(A);
        }

        /** Places the fight by its share of the way from A's start to the nearest start of B's. */
        void locate(@NonNull Game game) {
            List<Map<String, Object>> players = game.players();
            if (game.aSlot() >= players.size()) {
                return; // a header without players: place stays A's base, share NaN
            }
            double to_a = distanceTo(players.get(game.aSlot()));
            double to_b = Double.MAX_VALUE;
            for (int slot : game.bSlots()) {
                to_b = Math.min(to_b, distanceTo(players.get(slot)));
            }
            share = to_a / (to_a + to_b);
            place = share < A_BASE_BELOW ? 0 : share > B_BASE_ABOVE ? 2 : 1;
        }

        /** The distance from the fight's centre to a player's start. */
        private double distanceTo(@NonNull Map<String, Object> player) {
            return Math.hypot(x() - num(player, "x"), y() - num(player, "y"));
        }

        @NonNull
        String where() {
            return Double.isNaN(share) ? "?" : PLACES[place] + String.format(Locale.ROOT, " %.2f", share);
        }

        @NonNull
        String time() {
            return Table.clock(start).trim() + "-" + Table.clock(end).trim();
        }

        /** A side's losses: the total, then each kind that died, such as "12 (3r 9p)". */
        @NonNull
        String losses(int side) {
            List<String> parts = new ArrayList<>();
            for (int kind = 0; kind < KINDS.length; kind++) {
                if (lost[side][kind] > 0) {
                    parts.add(lost[side][kind] + KIND_LETTERS[kind]);
                }
            }
            return parts.isEmpty() ? "0" : lost(side) + " (" + String.join(" ", parts) + ")";
        }

        @NonNull
        String signedNet() {
            return String.format(Locale.ROOT, "%+d", net());
        }

        /** The buildings each side lost, such as "A: tower; B: quarters, armory site". */
        @NonNull
        String razedText() {
            List<String> parts = new ArrayList<>();
            if (!razed.get(A).isEmpty()) {
                parts.add("A: " + String.join(", ", razed.get(A)));
            }
            if (!razed.get(B).isEmpty()) {
                parts.add("B: " + String.join(", ", razed.get(B)));
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
            fight.add(event, game.isA(Game.slot(event)) ? A : B);
        }
        fights.removeIf(fight -> fight.lost(A) + fight.lost(B) < min_deaths);
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

    private static void printGame(@NonNull Game game, int min_deaths) {
        List<Fight> fights = fightsOf(game, min_deaths);
        System.out.printf(Locale.ROOT, "%s: %d fights with %d+ deaths, A = s%d%n", game, fights.size(), min_deaths,
                game.aSlot());
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("time", "where", "A lost", "B lost", "net", "razed"));
        for (Fight fight : fights) {
            rows.add(List.of(fight.time(), fight.where(), fight.losses(A), fight.losses(B), fight.signedNet(),
                    fight.razedText()));
        }
        Table.align(rows, "llrrrl").forEach(System.out::println);
        System.out.println(LEGEND);
    }

    /** A run's fights at one of the PLACES, summed over its games. */
    private static final class PlaceTotals {
        int fights;
        int a_lost;
        int b_lost;
        int net_in_won;
        int net_in_lost;
    }

    private record FightInGame(@NonNull Game game, @NonNull Fight fight) {
    }

    /** A run's fights: per place, the fights and losses per game, and the net in the games A won and lost. */
    private static void printRun(@NonNull String run, int min_deaths) {
        List<Game> games = Game.counted(run);
        if (games.isEmpty()) {
            throw new UsageException("run " + run + " has no counted game");
        }
        PlaceTotals[] totals = {new PlaceTotals(), new PlaceTotals(), new PlaceTotals()};
        List<FightInGame> all = new ArrayList<>();
        for (Game game : games) {
            for (Fight fight : fightsOf(game, min_deaths)) {
                PlaceTotals place = totals[fight.place];
                place.fights++;
                place.a_lost += fight.lost(A);
                place.b_lost += fight.lost(B);
                switch (game.result()) {
                    case "win" -> place.net_in_won += fight.net();
                    case "loss" -> place.net_in_lost += fight.net();
                    default -> {
                    }
                }
                all.add(new FightInGame(game, fight));
            }
        }
        int n = games.size();
        long won = games.stream().filter(game -> game.result().equals("win")).count();
        long lost = games.stream().filter(game -> game.result().equals("loss")).count();
        System.out.printf(Locale.ROOT, "fights of %s: %d with %d+ deaths in %d games, per game:%n", run, all.size(),
                min_deaths, n);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("where", "fights", "A lost", "B lost", "net", "net when A won (" + won + ")",
                "net when A lost (" + lost + ")"));
        for (int p = 0; p < PLACES.length; p++) {
            PlaceTotals place = totals[p];
            rows.add(List.of(PLACES[p], perGame(place.fights, n), perGame(place.a_lost, n), perGame(place.b_lost, n),
                    signedPerGame(place.b_lost - place.a_lost, n), signedPerGame(place.net_in_won, won),
                    signedPerGame(place.net_in_lost, lost)));
        }
        Table.align(rows).forEach(System.out::println);

        System.out.println("A's worst fights:");
        all.sort(Comparator.comparingInt(found -> found.fight().net())); // stable: equal nets stay in game order
        rows.clear();
        rows.add(List.of("game", "time", "where", "A lost", "B lost", "net"));
        for (FightInGame found : all.subList(0, Math.min(WORST_SHOWN, all.size()))) {
            Fight fight = found.fight();
            rows.add(List.of(found.game().key(), fight.time(), fight.where(), fight.losses(A), fight.losses(B),
                    fight.signedNet()));
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
