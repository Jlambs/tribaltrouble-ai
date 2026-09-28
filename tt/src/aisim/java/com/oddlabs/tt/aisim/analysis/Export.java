package com.oddlabs.tt.aisim.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.oddlabs.tt.aikit.harness.Census;
import com.oddlabs.tt.aisim.Aisim;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code export RUN}: a run's counted games as two CSV tables for spreadsheets and scripts, next to its results:
 * census.csv (one line per census sample) and events.csv (one line per event). Each line starts with its game's run,
 * key, seed, side (A's slot) and team A's result, so the tables of several runs can be concatenated.
 */
public final class Export {
    /** The columns every line starts with. */
    private static final List<String> GAME_COLUMNS = List.of("run", "key", "seed", "side", "result");

    private Export() {
    }

    public static int run(@NonNull List<String> runs) throws IOException {
        for (String run : runs) {
            List<Game> games = Game.counted(run);
            Path dir = Runs.dir(run);
            int samples = writeCensus(games, dir.resolve("census.csv"));
            int events = writeEvents(games, dir.resolve("events.csv"));
            System.out.printf(Locale.ROOT, "%s: %d games -> %s (%d census samples), %s (%d events)%n", run,
                    games.size(),
                    Aisim.slash(dir.resolve("census.csv")), samples, Aisim.slash(dir.resolve("events.csv")), events);
        }
        return 0;
    }

    /**
     * census.csv: the game columns, the game's end, then per sample its slot, team (A, B, ... as the reports name
     * them), t and every census field in docs/aisim.md order. A field an older game file lacks is empty.
     */
    private static int writeCensus(@NonNull List<Game> games, @NonNull Path file) throws IOException {
        List<String> fields = new ArrayList<>();
        for (Census.Field field : Census.Field.values()) {
            fields.add(field.name());
        }
        int lines = 0;
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            List<String> header = new ArrayList<>(GAME_COLUMNS);
            header.addAll(List.of("end", "slot", "team", "t"));
            header.addAll(fields);
            out.println(String.join(",", header));
            for (Game game : games) {
                for (Map<String, Object> sample : game.events("census")) {
                    List<String> line = gameCells(game);
                    line.add(cell(game.row().get("end")));
                    line.addAll(slotCells(game, sample));
                    line.add(cell(sample.get("t")));
                    for (String field : fields) {
                        line.add(cell(sample.get(field)));
                    }
                    out.println(String.join(",", line));
                    lines++;
                }
            }
        }
        return lines;
    }

    /**
     * events.csv: the game columns, then per event its t, slot and team (empty for events of no player), ev, and every
     * other member any event of the run has, in the order they first appear. The header and census lines are left out.
     */
    private static int writeEvents(@NonNull List<Game> games, @NonNull Path file) throws IOException {
        Set<String> members = new LinkedHashSet<>();
        for (Game game : games) {
            for (Map<String, Object> event : exportedEvents(game)) {
                members.addAll(event.keySet());
            }
        }
        members.removeAll(List.of("ev", "t", "s"));
        int lines = 0;
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            List<String> header = new ArrayList<>(GAME_COLUMNS);
            header.addAll(List.of("t", "slot", "team", "ev"));
            header.addAll(members);
            out.println(String.join(",", header));
            for (Game game : games) {
                for (Map<String, Object> event : exportedEvents(game)) {
                    List<String> line = gameCells(game);
                    line.add(cell(event.get("t")));
                    line.addAll(event.get("s") == null ? List.of("", "") : slotCells(game, event));
                    line.add(cell(event.get("ev")));
                    for (String member : members) {
                        line.add(cell(event.get(member)));
                    }
                    out.println(String.join(",", line));
                    lines++;
                }
            }
        }
        return lines;
    }

    /** The events of a game that events.csv lists: all but the header and the census samples. */
    private static @NonNull List<Map<String, Object>> exportedEvents(@NonNull Game game) {
        Set<String> left_out = Set.of("game", "census");
        return game.events().stream().filter(event -> !left_out.contains(String.valueOf(event.get("ev")))).toList();
    }

    private static @NonNull List<String> gameCells(@NonNull Game game) {
        List<String> cells = new ArrayList<>();
        cells.add(cell(game.run()));
        cells.add(cell(game.key()));
        cells.add(String.valueOf(game.seed()));
        cells.add(String.valueOf(game.aSlot()));
        cells.add(game.result());
        return cells;
    }

    /** The slot of an event or sample, and its team's name (A for team A, then B, C, ...). */
    private static @NonNull List<String> slotCells(@NonNull Game game, @NonNull Map<String, Object> event) {
        int slot = Game.slot(event);
        return List.of(String.valueOf(slot), game.teamName(game.teamOf(slot)));
    }

    /**
     * A CSV cell: empty for null, numbers and plain words as they are, other text quoted, and lists and maps as quoted
     * JSON.
     */
    private static @NonNull String cell(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        String text;
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                text = Aisim.JSON.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        } else {
            text = String.valueOf(value);
        }
        if (text.matches("[A-Za-z0-9._+-]*")) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
