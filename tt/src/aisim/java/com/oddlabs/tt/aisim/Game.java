package com.oddlabs.tt.aisim;

import com.oddlabs.tt.aikit.Census;
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

/**
 * One recorded game: its result row and its game file, as docs/aisim.md (Files) describes them. The analysis commands
 * read games through this class, and so can your own tools in {@code lab/} (docs/aisim.md, Your own tools). Lab tools
 * on AI branches call its public methods, so keep them stable.
 *
 * <p>Rows, headers, census samples and events are JSON objects as read: numbers, strings, lists and maps; {@link #num}
 * reads a number. A is the AI under test, B every player not on A's team. Nothing here throws a checked exception:
 * an unreadable file throws {@link UncheckedIOException}.
 */
public final class Game {
    private static final String[] WARRIORS = {"rock", "iron", "rubber", "tower"};
    private static final String[] WORKERS = {"peons", "inside"};
    private static final String[] HARVEST = {"hTree", "hRock", "hIron", "hRubber"};
    private static final String[] STOCK = {"sRock", "sIron", "sRubber"};

    private final @Nullable String run;
    private final @NonNull Map<String, Object> row;
    private final @NonNull Path file;
    /** The game file's lines, read on first use. */
    private @Nullable List<Map<String, Object>> events;
    /** Each slot's census samples in time order, built with {@link #events}. */
    private final @NonNull Map<Integer, List<Map<String, Object>>> census = new TreeMap<>();
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
        for (Map<String, Object> r : read(() -> Runs.rows(run))) {
            if (r.get("winner") != null) {
                games.add(new Game(run, r, Runs.gameFile(dir, r.get("key"))));
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

    /** The game file. */
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
    public int a() {
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

    /** True when {@code slot} plays on A's team. */
    public boolean isA(int slot) {
        int a = a();
        Object team = teamOf(slot);
        return slot == a || (team != null && team.equals(teamOf(a)));
    }

    /** B's slots: every player not on A's team. */
    public @NonNull List<Integer> b() {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < players().size(); slot++) {
            if (!isA(slot)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** A's result: win, loss or draw; unknown for a play-test that has no winner. */
    public @NonNull String result() {
        if (!row.isEmpty()) {
            return Runs.resultOfA(row.get("winner"));
        }
        List<Map<String, Object>> end = events("end");
        Object winner = end.isEmpty() ? null : end.get(0).get("winner");
        Object winner_team = end.isEmpty() ? null : end.get(0).get("winnerTeam");
        if (winner != null) {
            return Runs.resultOfA(winner);
        }
        if (winner_team != null) {
            return winner_team.equals(teamOf(a())) ? "win" : "loss";
        }
        return "unknown";
    }

    /** Every line of the game file in order, the header first; empty when the game never started. */
    public @NonNull List<Map<String, Object>> events() {
        if (events == null) {
            List<Map<String, Object>> lines = read(() -> Runs.readJsonl(file));
            for (Map<String, Object> e : lines) {
                if ("tl".equals(e.get("ev"))) {
                    census.computeIfAbsent(slot(e), s -> new ArrayList<>()).add(e);
                    last_sample = Math.max(last_sample, num(e, "t"));
                }
            }
            events = lines;
        }
        return events;
    }

    /** The events named {@code ev} (such as deaths or built), in time order. */
    public @NonNull List<Map<String, Object>> events(@NonNull String ev) {
        return events().stream().filter(e -> ev.equals(e.get("ev"))).toList();
    }

    /** The header, the game file's first line; empty when the game never started. */
    public @NonNull Map<String, Object> header() {
        List<Map<String, Object>> all = events();
        return all.isEmpty() ? Map.of() : all.get(0);
    }

    /** The players, by slot: s name team race ai x y (x, y is the start). */
    @SuppressWarnings("unchecked")
    public @NonNull List<Map<String, Object>> players() {
        return header().get("players") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** The census samples of {@code slot}, every 30 game seconds and at the end. */
    public @NonNull List<Map<String, Object>> census(int slot) {
        events();
        return census.getOrDefault(slot, List.of());
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
        return Runs.num(map, key);
    }

    /** The slot of an event or census sample (its {@code s}). */
    public static int slot(@NonNull Map<?, ?> event) {
        return (int) num(event, "s");
    }

    /**
     * A census field of {@code census} (docs/aisim.md), or one of the sums {@code warriors} (rock iron rubber tower),
     * {@code workers} (peons inside), {@code harvest} (hTree hRock hIron hRubber) and {@code stock} (sRock sIron
     * sRubber). An unknown name throws IllegalArgumentException; check it with {@link #isField}.
     */
    public static double value(@NonNull Map<?, ?> census, @NonNull String field) {
        return switch (field) {
            case "warriors" -> sum(census, WARRIORS);
            case "workers" -> sum(census, WORKERS);
            case "harvest" -> sum(census, HARVEST);
            case "stock" -> sum(census, STOCK);
            default -> {
                if (!isField(field)) {
                    throw new IllegalArgumentException("unknown census field " + field);
                }
                yield num(census, field);
            }
        };
    }

    /** True for the names {@link #value} knows. */
    public static boolean isField(@NonNull String name) {
        if (List.of("warriors", "workers", "harvest", "stock").contains(name)) {
            return true;
        }
        for (Census.Field field : Census.Field.values()) {
            if (field.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static double sum(@NonNull Map<?, ?> census, @NonNull String @NonNull [] fields) {
        double sum = 0;
        for (String field : fields) {
            sum += num(census, field);
        }
        return sum;
    }

    /** The team of player {@code slot}, or null when the header does not list it. */
    private @Nullable Object teamOf(int slot) {
        List<Map<String, Object>> players = players();
        return slot >= 0 && slot < players.size() ? players.get(slot).get("team") : null;
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
