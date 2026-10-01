package com.oddlabs.tt.aisim.analysis;

import com.oddlabs.tt.aikit.harness.Census;
import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.UsageException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One recorded game: its result row and its game file, as docs/aisim.md (Files) describes them. The analysis commands
 * read games through this class, and so do the tools in {@code lab/} (docs/aisim.md, Your own tools), which is why its
 * methods are public.
 *
 * <p>Rows, headers, census samples and events are JSON objects as read: numbers, strings, lists and maps; {@link #num}
 * reads a number. Team A is the first team listed, the one the reports focus on; the other teams are named B, C, ...
 * in the order of their team numbers ({@link #teamName}), and "B's slots" are every player not on team A. A's slot is
 * the first player's. Nothing
 * here throws a checked exception: an unreadable file throws {@link UncheckedIOException}.
 */
public final class Game {
    /** The sums {@link #value} knows besides the census fields, and the fields each adds up. */
    private static final Map<String, List<String>> SUMS = Map.of(
            "warriors", List.of("rock", "iron", "rubber", "garrison"),
            "workers", List.of("peons", "inside"),
            "harvested", List.of("harvestedTree", "harvestedRock", "harvestedIron", "harvestedRubber"),
            "stock", List.of("stockRock", "stockIron", "stockRubber"));

    private final @Nullable String run;
    private final @NonNull Map<String, Object> row;
    private final @NonNull Path file;
    /** The game file's lines, read on first use. */
    private @Nullable List<Map<String, Object>> events;
    /** Each slot's census samples in time order, indexed with {@link #events}. */
    private final @NonNull Map<Integer, List<Map<String, Object>>> census_by_slot = new TreeMap<>();
    private double last_sample = -1;

    private Game(@Nullable String run, @NonNull Map<String, Object> row, @NonNull Path file) {
        this.run = run;
        this.row = row;
        this.file = file;
    }

    /** The counted games of {@code run} (ended by elimination or at the time limit), in results.jsonl order. */
    public static @NonNull List<Game> counted(@NonNull String run) {
        Path dir = Runs.dir(run);
        List<Game> games = new ArrayList<>();
        for (Map<String, Object> row : read(() -> Runs.rows(run))) {
            if (Runs.counts(row)) {
                games.add(new Game(run, row, Runs.gameFile(dir, row.get("key"))));
            }
        }
        return games;
    }

    /** Game {@code key} of {@code run}, counted or not. */
    public static @NonNull Game of(@NonNull String run, @NonNull String key) {
        return new Game(run, read(() -> Runs.row(run, key)), Runs.gameFile(Runs.dir(run), key));
    }

    /** A game file alone, such as a play-test's game-N.jsonl. It has no run and an empty result row. */
    public static @NonNull Game file(@NonNull Path file) {
        if (!Files.exists(file)) {
            throw new UsageException("no game file " + Aisim.slash(file));
        }
        return new Game(null, Map.of(), file);
    }

    /** The game named on a command line by RUN KEY or FILE.jsonl. */
    static @NonNull Game named(@NonNull List<String> args) {
        return args.size() == 2 ? of(args.get(0), args.get(1)) : file(Path.of(args.get(0)));
    }

    /** The run of this game; null for a game file read alone. */
    public @Nullable String run() {
        return run;
    }

    /** The result row (docs/aisim.md, Result row); empty for a game file read alone. */
    public @NonNull Map<String, Object> row() {
        return row;
    }

    public @NonNull Path path() {
        return file;
    }

    /** The key, such as s19-1: map seed 19, A in slot 1. */
    public @NonNull String key() {
        return String.valueOf(row.isEmpty() ? header().get("key") : row.get("key"));
    }

    /** The map seed, or -1 when the game file does not say (a play-test). */
    public int seed() {
        Object seed = row.isEmpty() ? header().get("seed") : row.get("seed");
        return seed instanceof Number n ? n.intValue() : -1;
    }

    /**
     * A's slot: the row's {@code side}. In a game file read alone, the slot its key names, and in a play-test the first
     * player that is not human.
     */
    public int aSlot() {
        if (!row.isEmpty()) {
            return (int) num(row, "side");
        }
        String key = String.valueOf(header().get("key"));
        if (key.matches("s\\d+-\\d+")) {
            return Integer.parseInt(key.substring(key.indexOf('-') + 1));
        }
        for (Map<String, Object> player : players()) {
            if (!"human".equals(player.get("ai"))) {
                return (int) num(player, "s");
            }
        }
        return 0;
    }

    /** Team A's slots: A and its allies, in slot order. */
    public @NonNull List<Integer> aSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < Math.max(players().size(), aSlot() + 1); slot++) {
            if (isA(slot)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** B's slots: every player not on team A. */
    public @NonNull List<Integer> bSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < players().size(); slot++) {
            if (!isA(slot)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** True when {@code slot} plays on team A. */
    public boolean isA(int slot) {
        int a = aSlot();
        return slot == a || (teamOf(slot) >= 0 && teamOf(slot) == teamOf(a));
    }

    /** The team number of player {@code slot}, as the header lists it; -1 when it does not. */
    public int teamOf(int slot) {
        List<Map<String, Object>> players = players();
        if (slot < 0 || slot >= players.size() || !(players.get(slot).get("team") instanceof Number team)) {
            return -1;
        }
        return team.intValue();
    }

    /** team A's number; -1 when the header does not list it. */
    public int aTeam() {
        return teamOf(aSlot());
    }

    /** The team numbers of the game, team A first, then the others in rising order. */
    public @NonNull List<Integer> teams() {
        TreeSet<Integer> others = new TreeSet<>();
        for (int slot = 0; slot < players().size(); slot++) {
            others.add(teamOf(slot));
        }
        others.remove(aTeam());
        List<Integer> teams = new ArrayList<>(List.of(aTeam()));
        teams.addAll(others);
        return teams;
    }

    /**
     * The name of {@code team} in reports: A for team A, then B, C, ... for the others in the order of
     * {@link #teams}, and T26, T27, ... past Z. In a harness game, teams are numbered in the order they were given, so
     * B
     * is team 1 and so on; in a play-test, team A need not be team 0.
     */
    public @NonNull String teamName(int team) {
        int index = teams().indexOf(team);
        if (index < 0) {
            return "T" + team;
        }
        return index < 26 ? String.valueOf((char) ('A' + index)) : "T" + index;
    }

    /** The slots of {@code team}, in slot order. */
    public @NonNull List<Integer> slotsOf(int team) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < players().size(); slot++) {
            if (teamOf(slot) == team) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /**
     * team A's result: win (first place alone), draw (first place shared) or loss; unknown for a play-test that has
     * no winner.
     */
    public @NonNull String result() {
        if (!row.isEmpty()) {
            Object result = row.get("result");
            return result == null ? "unknown" : String.valueOf(result);
        }
        List<Map<String, Object>> end = events("end");
        if (end.isEmpty()) {
            return "unknown";
        }
        if (end.get(0).get("places") instanceof List<?> places && aTeam() >= 0 && aTeam() < places.size()) {
            double a = ((Number) places.get(aTeam())).doubleValue();
            double best = places.stream().mapToDouble(place -> ((Number) place).doubleValue()).min().orElse(a);
            return a == 1 ? "win" : a == best ? "draw" : "loss";
        }
        if (end.get(0).get("winnerTeam") instanceof Number winner) {
            return winner.intValue() == aTeam() ? "win" : "loss";
        }
        return "unknown";
    }

    /**
     * The place of {@code team}: 1 plus the number of teams that outlasted it, shared places averaged (two teams
     * sharing
     * first both have 1.5); null when the game has none (it did not count, or is a play-test).
     */
    public @Nullable Double place(int team) {
        if (row.get("teams") instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map && num(map, "team") == team && map.get("place") != null) {
                    return num(map, "place");
                }
            }
            return null;
        }
        List<Map<String, Object>> end = events("end");
        if (!end.isEmpty() && end.get(0).get("places") instanceof List<?> places && team >= 0
                && team < places.size() && places.get(team) instanceof Number place) {
            return place.doubleValue();
        }
        return null;
    }

    /** Every line of the game file in order, the header first; empty when the game never started. */
    public @NonNull List<Map<String, Object>> events() {
        if (events == null) {
            List<Map<String, Object>> lines = read(() -> Runs.readJsonl(file));
            toGameTime(lines);
            for (Map<String, Object> line : lines) {
                if ("census".equals(line.get("ev"))) {
                    census_by_slot.computeIfAbsent(slot(line), slot -> new ArrayList<>()).add(line);
                    last_sample = Math.max(last_sample, num(line, "t"));
                }
            }
            events = lines;
        }
        return events;
    }

    /**
     * A game file from before the recorder counted game time stamps t in world ticks / 50 at every game speed: at a
     * speed other than normal (the header's secondsPerTick, then each speed event's) this turns those into game
     * seconds, as newer files give them. A newer file has clock "game" in its header or in its speed events, unless
     * it played at normal speed throughout, where the two agree.
     */
    private static void toGameTime(@NonNull List<Map<String, Object>> lines) {
        if (lines.isEmpty() || lines.stream().anyMatch(line -> "game".equals(line.get("clock")))) {
            return;
        }
        double factor = tickFactor(lines.get(0));
        if (factor == 1 && lines.stream().noneMatch(line -> "speed".equals(line.get("ev")))) {
            return;
        }
        double world_start = 0;
        double game_start = 0;
        for (Map<String, Object> line : lines) {
            if (!(line.get("t") instanceof Number t)) {
                continue; // the header
            }
            double game = game_start + (t.doubleValue() - world_start) * factor;
            line.put("t", Math.round(game * 100) / 100.0);
            if ("speed".equals(line.get("ev"))) {
                world_start = t.doubleValue();
                game_start = game;
                factor = tickFactor(line);
            }
        }
    }

    /**
     * The game time of a world tick at the speed a header or speed event gives, in normal ticks; 1 when it has none.
     */
    private static double tickFactor(@NonNull Map<String, Object> line) {
        return line.get("secondsPerTick") instanceof Number seconds ? Math.round(
                seconds.doubleValue() * 1000) / 20.0 : 1;
    }

    /**
     * Drops the game file's lines read so far; the next call that needs them reads the file again. A game file holds
     * up to a few MB of parsed lines, so going through a big run's games, forget each one when done with it.
     */
    public void forget() {
        events = null;
        census_by_slot.clear();
        last_sample = -1;
    }

    /** The events named {@code ev} (such as deaths or built), in time order. */
    public @NonNull List<Map<String, Object>> events(@NonNull String ev) {
        return events().stream().filter(event -> ev.equals(event.get("ev"))).toList();
    }

    /** The header, the game file's first line; empty when the game never started. */
    public @NonNull Map<String, Object> header() {
        List<Map<String, Object>> lines = events();
        return lines.isEmpty() ? Map.of() : lines.get(0);
    }

    /** The players, by slot: s name team race ai x y (x, y is the start). */
    @SuppressWarnings("unchecked")
    public @NonNull List<Map<String, Object>> players() {
        return header().get("players") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** The census samples of {@code slot}, every 30 game seconds and at the end. */
    public @NonNull List<Map<String, Object>> census(int slot) {
        events();
        return census_by_slot.getOrDefault(slot, List.of());
    }

    /**
     * The census of {@code slot} at game second {@code t}: its last sample at or before t. Null before the first sample
     * and after the game's last one, when the game is over.
     */
    public @Nullable Map<String, Object> census(int slot, double t) {
        List<Map<String, Object>> samples = census(slot);
        if (t > last_sample) {
            return null;
        }
        Map<String, Object> at = null;
        for (Map<String, Object> sample : samples) {
            if (num(sample, "t") > t) {
                break;
            }
            at = sample;
        }
        return at;
    }

    /** The time of the game's last census sample: when it ended, unless the recorder stopped early; -1 without one. */
    public double lastSample() {
        events();
        return last_sample;
    }

    /** The number at {@code key}; 0 when it is missing, null or not a number. */
    public static double num(@NonNull Map<?, ?> map, @NonNull String key) {
        return map.get(key) instanceof Number n ? n.doubleValue() : 0;
    }

    /** The slot of an event or census sample (its {@code s}). */
    public static int slot(@NonNull Map<?, ?> event) {
        return (int) num(event, "s");
    }

    /**
     * A census field of {@code census} (docs/aisim.md), or one of the sums {@code warriors} (rock iron rubber
     * garrison), {@code workers} (peons inside), {@code harvested} (the four harvested fields) and {@code stock} (the
     * three stock fields). An unknown name throws IllegalArgumentException; check it with {@link #isField}.
     */
    public static double value(@NonNull Map<?, ?> census, @NonNull String field) {
        List<String> summed = SUMS.get(field);
        if (summed != null) {
            return summed.stream().mapToDouble(name -> num(census, name)).sum();
        }
        if (!isField(field)) {
            throw new IllegalArgumentException("unknown census field " + field);
        }
        return num(census, field);
    }

    /** True for the names {@link #value} knows. */
    public static boolean isField(@NonNull String name) {
        if (SUMS.containsKey(name)) {
            return true;
        }
        for (Census.Field field : Census.Field.values()) {
            if (field.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** A read that may throw IOException. */
    private interface IoRead<T> {
        T read() throws IOException;
    }

    private static <T> @NonNull T read(@NonNull IoRead<T> reader) {
        try {
            return Objects.requireNonNull(reader.read());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public @NonNull String toString() {
        return run == null ? Aisim.slash(file) : run + " " + key();
    }
}
