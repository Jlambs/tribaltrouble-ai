package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.aisim.analysis.Game;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The recorded game file of a watched game (read with the lab API, analysis.Game): the world checksum of each census
 * second, the end, and the events worth a mark on a timeline.
 */
final class Recording {
    /** The events a timeline marks, besides stuns of many units and windows of many deaths. */
    private static final Set<String> KEY_EVENTS = Set.of("out", "team_out", "collapse", "razed", "built", "chief",
            "chief_died", "cast", "end");
    /** Stuns of at least this many units are key events. */
    private static final int STUNS_SHOWN = 5;
    /** Deaths are summed per slot over windows of this many game seconds... */
    static final int DEATH_WINDOW = 10;
    /** ...and a window with at least this many is a key event. */
    private static final int DEATHS_SHOWN = 10;

    /**
     * One event of the game file.
     *
     * @param tick  when (the event's t times 50)
     * @param ev    its kind ({@code out}, {@code razed}, {@code deaths}...)
     * @param slot  the player it is about ({@code s}: for razed and deaths the loser), or -1 for none (team_out, end)
     * @param text  a one-line description, such as {@code s3 razed quarters at 120,88}
     * @param event the whole line as read
     */
    record Event(int tick, @NonNull String ev, int slot, @NonNull String text, @NonNull Map<String, Object> event) {
    }

    private final @NonNull Game game;
    /** The world checksum of each census second: the first census line at that t (the end's census can follow). */
    private final @NonNull TreeMap<Integer, Integer> census_checksums = new TreeMap<>();
    private final int end_tick;
    private final @Nullable Integer end_checksum;
    private final @NonNull List<Event> key_events;

    private Recording(@NonNull Game game) {
        this.game = game;
        Integer end_sum = null;
        int end = -1;
        List<Event> keys = new ArrayList<>();
        TreeMap<Integer, Map<Integer, Integer>> deaths = new TreeMap<>(); // window start second -> slot -> units
        for (Map<String, Object> line : game.events()) {
            String ev = String.valueOf(line.get("ev"));
            double t = Game.num(line, "t");
            int tick = (int) Math.round(t * GameTime.TICKS_PER_SECOND);
            int slot = line.get("s") instanceof Number n ? n.intValue() : -1;
            switch (ev) {
                case "census" -> {
                    if (line.get("checksum") instanceof Number sum) {
                        census_checksums.putIfAbsent((int) Math.round(t), sum.intValue());
                    }
                }
                case "end" -> {
                    end = tick;
                    end_sum = line.get("checksum") instanceof Number sum ? sum.intValue() : null;
                }
                case "deaths" -> {
                    int window = (int) Math.floor(t / DEATH_WINDOW) * DEATH_WINDOW;
                    deaths.computeIfAbsent(window, _ -> new TreeMap<>()).merge(slot, (int) Game.num(line, "n"),
                            Integer::sum);
                }
                default -> {
                }
            }
            if (KEY_EVENTS.contains(ev) || (ev.equals("stunned") && Game.num(line, "n") >= STUNS_SHOWN)) {
                keys.add(new Event(tick, ev, slot, describe(ev, slot, line), line));
            }
        }
        deaths.forEach((window, by_slot) -> by_slot.forEach((slot, units) -> {
            if (units >= DEATHS_SHOWN) {
                String text = "s" + slot + " lost " + units + " units in " + DEATH_WINDOW + " s";
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("ev", "deaths");
                line.put("t", (double) window);
                line.put("s", slot);
                line.put("n", units);
                keys.add(new Event(window * GameTime.TICKS_PER_SECOND, "deaths", slot, text, line));
            }
        }));
        keys.sort((a, b) -> Integer.compare(a.tick(), b.tick())); // stable: file order within a tick
        this.key_events = Collections.unmodifiableList(keys);
        this.end_tick = end;
        this.end_checksum = end_sum;
    }

    /** Reads the game file {@code file}. */
    static @NonNull Recording read(@NonNull Path file) {
        return new Recording(Game.file(file));
    }

    private static @NonNull String describe(@NonNull String ev, int slot, @NonNull Map<String, Object> line) {
        StringBuilder text = new StringBuilder();
        if (slot >= 0) {
            text.append('s').append(slot).append(' ');
        }
        text.append(ev);
        switch (ev) {
            case "razed", "built" -> text.append(' ').append(line.get("b")).append(line.get(
                    "site") != null ? " (site)" : "").append(" at ").append(at(line));
            case "cast" -> text.append(' ').append(line.get("magic")).append(" at ").append(at(line));
            case "stunned" -> text.append(' ').append(line.get("n")).append(" units at ").append(at(line));
            case "team_out" -> text.append(" team ").append(line.get("team")).append(" (").append(line.get(
                    "via")).append(')');
            case "end" -> text.append(' ').append(line.get("end")).append(", winner team ").append(line.get(
                    "winnerTeam"));
            default -> {
            }
        }
        return text.toString();
    }

    private static @NonNull String at(@NonNull Map<String, Object> line) {
        return String.format(Locale.ROOT, "%d,%d", (int) Game.num(line, "x"), (int) Game.num(line, "y"));
    }

    /** The game file, for everything else it holds (census samples, header, players). */
    @NonNull
    Game game() {
        return game;
    }

    /** The recorded checksum of census second {@code second}, or null if the file has no census then. */
    @Nullable
    Integer censusChecksum(int second) {
        return census_checksums.get(second);
    }

    /** The census seconds with a recorded checksum, ascending. */
    @NonNull
    Set<Integer> censusSeconds() {
        return Collections.unmodifiableSet(census_checksums.keySet());
    }

    /** The tick the game ended at (the end line's t times 50), or -1 if the file has no end line. */
    int endTick() {
        return end_tick;
    }

    /** The world checksum after the last tick, from the end line; null without one. */
    @Nullable
    Integer endChecksum() {
        return end_checksum;
    }

    /** The events a timeline marks, in time order. */
    @NonNull
    List<Event> keyEvents() {
        return key_events;
    }

    /** Every line of the game file, the header first. */
    @NonNull
    List<Map<String, Object>> events() {
        return game.events();
    }
}
