package com.oddlabs.tt.aisim;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.oddlabs.tt.aisim.Game.num;

/**
 * Fights: deaths and razed buildings grouped by time and place. {@code fights RUN KEY} (or {@code FILE.jsonl}) lists
 * the fights of one game; {@code fights RUN} sums a run's fights by where they were, in the games A won and lost, and
 * lists A's worst ones.
 */
final class Fights {
    static final int DEFAULT_MIN_DEATHS = 8;
    /** An event joins the nearest fight within RADIUS cells of its centre that had an event in the last GAP seconds. */
    private static final double GAP = 20;
    private static final double RADIUS = 40;
    /** The unit kinds of a deaths event, in their file order (the recorder's Census.UNIT_KINDS). */
    private static final String[] KINDS = {"rock", "iron", "rubber", "peon", "chief"};
    /** Short names of KINDS: rock, iron and chicken (rubber) warriors, peons, chieftain. */
    private static final String[] SHORT = {"r", "i", "c", "p", "C"};
    /** Where a fight was, by its share of the way from A's start to the nearest start of B's. */
    private static final String[] PLACES = {"A's base", "middle", "B's base"};
    private static final int WORST = 8;
    private static final String LEGEND = """
            (where: share of the way from A's start to B's nearest; lost: r i c rock, iron, chicken warriors, p peons, \
            C chieftain; net: B's losses minus A's)""";

    private Fights() {
    }

    /** One fight; side 0 is A, side 1 is B. */
    private static final class Fight {
        private final double start;
        private double end;
        private double wx;
        private double wy;
        private double weight;
        private final int @NonNull [] @NonNull [] lost = new int[2][KINDS.length];
        private final @NonNull List<List<String>> razed = List.of(new ArrayList<>(), new ArrayList<>());
        /** An index into PLACES, and the share it comes from; set by {@link #locate}. */
        private int place;
        private double share = Double.NaN;

        Fight(double start) {
            this.start = start;
        }

        /** The centre of the fight's events so far, weighted by deaths. */
        double x() {
            return wx / weight;
        }

        double y() {
            return wy / weight;
        }

        /** Adds a deaths event (weighing its deaths) or a razed building (weighing one) of {@code side}. */
        void add(@NonNull Map<String, Object> e, int side) {
            boolean deaths = "deaths".equals(e.get("ev"));
            double n = deaths ? num(e, "n") : 1;
            end = Math.max(end, num(e, "t"));
            wx += num(e, "x") * n;
            wy += num(e, "y") * n;
            weight += n;
            if (deaths) {
                for (int k = 0; k < KINDS.length; k++) {
                    lost[side][k] += (int) num(e, KINDS[k]);
                }
            } else {
                razed.get(side).add(e.get("b") + (e.get("site") != null ? " site" : ""));
            }
        }

        int lost(int side) {
            int n = 0;
            for (int k : lost[side]) {
                n += k;
            }
            return n;
        }

        /** B's losses minus A's. */
        int net() {
            return lost(1) - lost(0);
        }

        /** Places the fight by its share of the way from A's start to the nearest start of B's. */
        void locate(@NonNull Game game) {
            List<Map<String, Object>> players = game.players();
            if (game.a() >= players.size()) {
                return; // a header without players: place stays A's base, share NaN
            }
            Map<String, Object> a = players.get(game.a());
            double to_a = Math.hypot(x() - num(a, "x"), y() - num(a, "y"));
            double to_b = Double.MAX_VALUE;
            for (int slot : game.b()) {
                Map<String, Object> b = players.get(slot);
                to_b = Math.min(to_b, Math.hypot(x() - num(b, "x"), y() - num(b, "y")));
            }
            share = to_a / (to_a + to_b);
            place = share < 0.35 ? 0 : share > 0.65 ? 2 : 1;
        }

        @NonNull
        String where() {
            return Double.isNaN(share) ? "?" : PLACES[place] + String.format(Locale.ROOT, " %.2f", share);
        }

        @NonNull
        String time() {
            return Report.clock(start).trim() + "-" + Report.clock(end).trim();
        }

        /** A side's losses: the total, then each kind that died, such as "12 (3r 9p)". */
        @NonNull
        String losses(int side) {
            List<String> parts = new ArrayList<>();
            for (int k = 0; k < KINDS.length; k++) {
                if (lost[side][k] > 0) {
                    parts.add(lost[side][k] + SHORT[k]);
                }
            }
            return parts.isEmpty() ? "0" : lost(side) + " (" + String.join(" ", parts) + ")";
        }

        /** The buildings each side lost, such as "A: tower; B: quarters, armory site". */
        @NonNull
        String razedText() {
            List<String> parts = new ArrayList<>();
            if (!razed.get(0).isEmpty()) {
                parts.add("A: " + String.join(", ", razed.get(0)));
            }
            if (!razed.get(1).isEmpty()) {
                parts.add("B: " + String.join(", ", razed.get(1)));
            }
            return String.join("; ", parts);
        }
    }

    /** The fights of {@code game} with at least {@code min} deaths, in the order they started. */
    private static @NonNull List<Fight> of(@NonNull Game game, int min) {
        List<Fight> fights = new ArrayList<>();
        for (Map<String, Object> e : game.events()) {
            if (!"deaths".equals(e.get("ev")) && !"razed".equals(e.get("ev"))) {
                continue;
            }
            Fight fight = nearestOpen(fights, e);
            if (fight == null) {
                fight = new Fight(num(e, "t"));
                fights.add(fight);
            }
            fight.add(e, game.isA(Game.slot(e)) ? 0 : 1);
        }
        fights.removeIf(f -> f.lost(0) + f.lost(1) < min);
        fights.forEach(f -> f.locate(game));
        return fights;
    }

    /**
     * The fight {@code e} joins: the nearest within RADIUS cells that had an event in the last GAP seconds, or null.
     */
    private static @Nullable Fight nearestOpen(@NonNull List<Fight> fights, @NonNull Map<String, Object> e) {
        Fight best = null;
        double best_distance = RADIUS;
        for (Fight f : fights) {
            double d = Math.hypot(num(e, "x") - f.x(), num(e, "y") - f.y());
            if (num(e, "t") - f.end <= GAP && d <= best_distance) {
                best = f;
                best_distance = d;
            }
        }
        return best;
    }

    /** {@code fights RUN} (a whole run), or {@code fights RUN KEY} / {@code fights FILE.jsonl} (one game). */
    static int run(@NonNull List<String> args, int min) {
        if (args.size() == 1 && !args.get(0).endsWith(".jsonl")) {
            return ofRun(args.get(0), min);
        }
        Game game = Game.named(args);
        List<Fight> fights = of(game, min);
        System.out.printf(Locale.ROOT, "%s: %d fights with %d+ deaths, A = s%d%n", game, fights.size(), min, game.a());
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("time", "where", "A lost", "B lost", "net", "razed"));
        for (Fight f : fights) {
            rows.add(List.of(f.time(), f.where(), f.losses(0), f.losses(1), String.format(Locale.ROOT, "%+d", f.net()),
                    f.razedText()));
        }
        Table.align(rows, "llrrrl").forEach(System.out::println);
        System.out.println(LEGEND);
        return 0;
    }

    /** One run's fights at one of the PLACES. */
    private static final class Totals {
        int fights;
        int a_lost;
        int b_lost;
        int net_in_won;
        int net_in_lost;
    }

    private record GameFight(@NonNull Game game, @NonNull Fight fight) {
    }

    /** A run's fights: per place, the fights and losses per game, and the net in the games A won and lost. */
    private static int ofRun(@NonNull String run, int min) {
        List<Game> games = Game.counted(run);
        if (games.isEmpty()) {
            throw new UsageException("run " + run + " has no counted game");
        }
        Totals[] totals = {new Totals(), new Totals(), new Totals()};
        List<GameFight> all = new ArrayList<>();
        for (Game game : games) {
            for (Fight f : of(game, min)) {
                Totals t = totals[f.place];
                t.fights++;
                t.a_lost += f.lost(0);
                t.b_lost += f.lost(1);
                switch (game.result()) {
                    case "win" -> t.net_in_won += f.net();
                    case "loss" -> t.net_in_lost += f.net();
                    default -> {
                    }
                }
                all.add(new GameFight(game, f));
            }
        }
        int n = games.size();
        long won = games.stream().filter(g -> g.result().equals("win")).count();
        long lost = games.stream().filter(g -> g.result().equals("loss")).count();
        System.out.printf(Locale.ROOT, "fights of %s: %d with %d+ deaths in %d games, per game:%n", run, all.size(),
                min, n);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("where", "fights", "A lost", "B lost", "net", "net when A won (" + won + ")",
                "net when A lost (" + lost + ")"));
        for (int p = 0; p < PLACES.length; p++) {
            Totals t = totals[p];
            rows.add(List.of(PLACES[p], perGame(t.fights, n), perGame(t.a_lost, n), perGame(t.b_lost, n),
                    signed(t.b_lost - t.a_lost, n), signed(t.net_in_won, won), signed(t.net_in_lost, lost)));
        }
        Table.align(rows).forEach(System.out::println);
        System.out.println("A's worst fights:");
        all.sort(Comparator.comparingInt(w -> w.fight().net())); // stable: equal nets stay in game order
        rows.clear();
        rows.add(List.of("game", "time", "where", "A lost", "B lost", "net"));
        for (GameFight w : all.subList(0, Math.min(WORST, all.size()))) {
            Fight f = w.fight();
            rows.add(List.of(w.game().key(), f.time(), f.where(), f.losses(0), f.losses(1), String.format(Locale.ROOT,
                    "%+d", f.net())));
        }
        Table.align(rows, "lllr").forEach(System.out::println);
        System.out.println(LEGEND);
        System.out.println("next: ./aisim.sh fights " + run + " KEY   (every fight of one game)");
        return 0;
    }

    private static @NonNull String perGame(int total, long games) {
        return String.format(Locale.ROOT, "%.1f", games == 0 ? 0 : total / (double) games);
    }

    private static @NonNull String signed(int total, long games) {
        return String.format(Locale.ROOT, "%+.1f", games == 0 ? 0 : total / (double) games);
    }
}
