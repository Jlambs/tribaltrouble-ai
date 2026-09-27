package com.oddlabs.tt.aisim.analysis;

import com.oddlabs.tt.aisim.Aisim;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.oddlabs.tt.aisim.analysis.Game.num;

/**
 * {@code show RUN KEY | FILE.jsonl}: one game as its players, a census table every 5 minutes and its key events. Works
 * on harness and play-test game files.
 */
public final class Show {
    /** A census row every this many game seconds. */
    private static final int CENSUS_EVERY = 300;
    /** The census fields of the table, after its time and slot columns. */
    private static final List<String> CENSUS_COLUMNS = List.of("units", "warriors", "workers", "quarters", "armories",
            "towers", "sites", "kills", "lost", "strength", "casts", "stunned", "errors");
    /** Deaths are summed per slot over windows this long, and a window is a key event from this many. */
    private static final int DEATH_WINDOW = 10;
    private static final int DEATHS_SHOWN = 10;
    /** A stun of at least this many units is a key event. */
    private static final int STUNS_SHOWN = 5;
    /** The events listed as key events, besides death windows and big stuns. */
    private static final Set<String> KEY_EVENTS = Set.of("built", "razed", "chief", "chief_died", "cast", "collapse",
            "out", "end", "speed", "recorder_error");

    private Show() {
    }

    public static int run(@NonNull List<String> args) {
        Game game = Game.named(args);
        printPlayers(game);
        printCensusTable(game);
        printKeyEvents(game.events());
        if (args.size() == 2) {
            String next = "next: ./aisim.sh replay " + args.get(0) + " " + args.get(1);
            System.out.println(next + "   (the same game again, with the log of every AI that uses AiLog)");
        }
        return 0;
    }

    /** The game file's map line and its players, from the header. */
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
    private static void printCensusTable(@NonNull Game game) {
        List<List<String>> rows = new ArrayList<>();
        List<String> header = new ArrayList<>(List.of("time", "slot"));
        header.addAll(CENSUS_COLUMNS);
        rows.add(header);
        for (Map<String, Object> sample : game.events("census")) {
            double t = num(sample, "t");
            if (Math.round(t) % CENSUS_EVERY == 0 || t == game.lastSample()) {
                List<String> row = new ArrayList<>(List.of(Table.clock(t).trim(), "s" + Game.slot(sample)));
                for (String field : CENSUS_COLUMNS) {
                    row.add(Table.number(Game.value(sample, field)));
                }
                rows.add(row);
            }
        }
        Table.align(rows, "rlr").forEach(System.out::println);
    }

    /** One line of the key events; lines sort by time, then by {@code order} (file order, death windows last). */
    private record KeyEvent(double t, int order, @NonNull String text) {
    }

    /** Key events in time order; deaths are summed per slot over short windows and shown from a few units up. */
    private static void printKeyEvents(@NonNull List<Map<String, Object>> events) {
        List<KeyEvent> lines = new ArrayList<>();
        TreeMap<Double, Map<String, Double>> deaths = new TreeMap<>(); // window start -> slot label -> units
        for (Map<String, Object> event : events) {
            String ev = String.valueOf(event.get("ev"));
            double t = num(event, "t");
            String slot = event.get("s") == null ? "  " : "s" + event.get("s");
            if (ev.equals("deaths")) {
                double window = Math.floor(t / DEATH_WINDOW) * DEATH_WINDOW;
                deaths.computeIfAbsent(window, w -> new TreeMap<>()).merge(slot, num(event, "n"), Double::sum);
            } else if (KEY_EVENTS.contains(ev) || (ev.equals("stunned") && num(event, "n") >= STUNS_SHOWN)) {
                Map<String, Object> details = new LinkedHashMap<>(event);
                details.keySet().removeAll(List.of("ev", "t", "s"));
                String text = String.format(Locale.ROOT, "  %s  %s %-10s %s", Table.clock(t), slot, ev,
                        details.isEmpty() ? "" : details);
                lines.add(new KeyEvent(t, lines.size(), text));
            }
        }
        deaths.forEach((window, units_by_slot) -> units_by_slot.forEach((slot, units) -> {
            if (units >= DEATHS_SHOWN) {
                String text = String.format(Locale.ROOT, "  %s  %s deaths     %.0f units in %d s", Table.clock(window),
                        slot, units, DEATH_WINDOW);
                lines.add(new KeyEvent(window, Integer.MAX_VALUE, text));
            }
        }));
        System.out.println("  key events:");
        lines.sort(Comparator.comparingDouble(KeyEvent::t).thenComparingInt(KeyEvent::order));
        for (KeyEvent line : lines) {
            System.out.println(line.text());
        }
    }
}
