package com.oddlabs.tt.aisim.watch;

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
 * The recorded game file of a watched game (read with the lab API, analysis.Game): the world checksum of each census,
 * the end, and the events worth a mark on a timeline, each at its world tick. Game reads every t as game seconds at the
 * speed the game was played (converting files from before the harness counted game time), so a line's tick is its t
 * over the game time of a tick at that speed.
 */
final class Recording {
    /** The events a timeline marks, besides stuns of many units and windows of many deaths. */
    private static final Set<String> KEY_EVENTS = Set.of("out", "team_out", "collapse", "razed", "built", "chief",
            "chief_died", "cast", "end");
    /** Stuns of at least this many units are key events. */
    private static final int STUNS_SHOWN = 5;
    /** Deaths are summed per slot over windows of this many world ticks (20 game seconds at ludicrous)... */
    static final int DEATH_WINDOW_TICKS = 250;
    /** ...and a window with at least this many is a key event. */
    private static final int DEATHS_SHOWN = 10;

    /**
     * One event of the game file.
     *
     * @param tick  when: the world tick of the event's t
     * @param ev    its kind ({@code out}, {@code razed}, {@code deaths}...)
     * @param slot  the player it is about ({@code s}: for razed and deaths the loser), or -1 for none (team_out, end)
     * @param text  a one-line description, such as {@code s3 razed quarters at 120,88}
     * @param event the whole line as read
     */
    record Event(int tick, @NonNull String ev, int slot, @NonNull String text, @NonNull Map<String, Object> event) {
    }

    private final @NonNull Game game;
    /** The game time of a world tick at the speed the game was played, in ms. */
    private final int millis_per_tick;
    /** The world checksum of each periodic census tick (every 30 game seconds): its first census line's. */
    private final @NonNull TreeMap<Integer, Integer> census_checksums = new TreeMap<>();
    private final int end_tick;
    private final @Nullable Integer end_checksum;
    private final @NonNull List<Event> key_events;

    private Recording(@NonNull Game game) {
        this.game = game;
        this.millis_per_tick = Times.gameMillisPerTick(game.header());
        // the grid the recorder took its census on: every 30 game seconds, or every 1500 ticks in older files
        int census_millis_per_tick = Times.recordedMillisPerTick(game.header());
        Integer end_sum = null;
        int end = -1;
        List<Event> keys = new ArrayList<>();
        TreeMap<Integer, Map<Integer, Integer>> deaths = new TreeMap<>(); // window start tick -> slot -> units
        for (Map<String, Object> line : game.events()) {
            String ev = String.valueOf(line.get("ev"));
            double t = Game.num(line, "t");
            int tick = Times.tickOf(t, millis_per_tick);
            int slot = line.get("s") instanceof Number n ? n.intValue() : -1;
            switch (ev) {
                case "census" -> {
                    // the periodic ones: the end's census follows the last tick, so its checksum is not the in-tick one
                    if (line.get("checksum") instanceof Number sum && Times.isCensusTick(tick,
                            census_millis_per_tick)) {
                        census_checksums.putIfAbsent(tick, sum.intValue());
                    }
                }
                case "end" -> {
                    end = tick;
                    end_sum = line.get("checksum") instanceof Number sum ? sum.intValue() : null;
                }
                case "deaths" -> {
                    int window = tick / DEATH_WINDOW_TICKS * DEATH_WINDOW_TICKS;
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
                String text = "s" + slot + " lost " + units + " units in " + DEATH_WINDOW_TICKS + " ticks";
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("ev", "deaths");
                line.put("t", window * (double) millis_per_tick / 1000);
                line.put("s", slot);
                line.put("n", units);
                keys.add(new Event(window, "deaths", slot, text, line));
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

    /** The game time of a world tick in this game, in ms. */
    int millisPerTick() {
        return millis_per_tick;
    }

    private static @NonNull String describe(@NonNull String ev, int slot, @NonNull Map<String, Object> line) {
        StringBuilder text = new StringBuilder();
        if (slot >= 0) {
            text.append('s').append(slot).append(' ');
        }
        text.append(ev);
        switch (ev) {
            case "razed", "built" -> text.append(' ').append(line.get("b")).append(line.get(
                    "site") != null ? " (site)" : "").append(" at ").append(at(line)).append(insideText(line));
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

    /**
     * A razed building's units lost with it ({@code inside}, in files since the recorder counts them), such as
     * {@code " with 12 inside"}; empty for none or an older file.
     */
    static @NonNull String insideText(@NonNull Map<String, Object> line) {
        return line.get("inside") instanceof Number n && n.intValue() > 0 ? " with " + n.intValue() + " inside" : "";
    }

    private static @NonNull String at(@NonNull Map<String, Object> line) {
        return String.format(Locale.ROOT, "%d,%d", (int) Game.num(line, "x"), (int) Game.num(line, "y"));
    }

    /** The game file, for everything else it holds (census samples, header, players). */
    @NonNull
    Game game() {
        return game;
    }

    /** The recorded checksum of the census at {@code tick}, or null if the file has no census then. */
    @Nullable
    Integer censusChecksum(int tick) {
        return census_checksums.get(tick);
    }

    /** The ticks of the censuses with a recorded checksum, ascending. */
    @NonNull
    Set<Integer> censusTicks() {
        return Collections.unmodifiableSet(census_checksums.keySet());
    }

    /** The tick the game ended at (the end line's t as a tick), or -1 if the file has no end line. */
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
