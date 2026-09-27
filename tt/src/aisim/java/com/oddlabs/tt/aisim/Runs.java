package com.oddlabs.tt.aisim;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads run folders, {@code aisim/runs/<run>/}: run.json (the run's settings and jobs), results.jsonl (one result
 * row per finished game, in completion order) and {@code g/<key>.jsonl} (each game's recording). docs/aisim.md
 * describes the formats.
 */
final class Runs {
    static final Path RUNS = Aisim.ROOT.resolve("runs");

    private Runs() {
    }

    /** The folder of an existing run; a usage error if it has no run.json. */
    static @NonNull Path dir(@NonNull String run) {
        Path dir = RUNS.resolve(run);
        if (!Files.exists(dir.resolve("run.json"))) {
            throw new UsageException("no run " + run + " in " + Aisim.slash(RUNS));
        }
        return dir;
    }

    /** A run's run.json: specs, config, snapshot, expected games and every job. */
    static @NonNull Map<?, ?> meta(@NonNull String run) throws IOException {
        return Aisim.JSON.readValue(dir(run).resolve("run.json").toFile(), Map.class);
    }

    /** Every result row of a run in completion order, failed games included. */
    static @NonNull List<Map<String, Object>> rows(@NonNull String run) throws IOException {
        return readJsonl(dir(run).resolve("results.jsonl"));
    }

    /** The result row of game {@code key} in {@code run}; a usage error if the run has none. */
    static @NonNull Map<String, Object> row(@NonNull String run, @NonNull String key) throws IOException {
        for (Map<String, Object> row : rows(run)) {
            if (key.equals(row.get("key"))) {
                return row;
            }
        }
        throw new UsageException("run " + run + " has no game " + key);
    }

    /** The game file of game {@code key} in the run folder {@code run_dir}. */
    static @NonNull Path gameFile(@NonNull Path run_dir, @NonNull Object key) {
        return run_dir.resolve("g").resolve(key + ".jsonl");
    }

    /** The game file named by RUN KEY or FILE.jsonl; a usage error if it does not exist. */
    static @NonNull Path requireGameFile(@NonNull List<String> args) {
        Path file = args.size() == 2 ? gameFile(dir(args.get(0)), args.get(1)) : Path.of(args.get(0));
        if (!Files.exists(file)) {
            throw new UsageException("no game file " + Aisim.slash(file));
        }
        return file;
    }

    /** The events of the game of each row, in row order. */
    static @NonNull List<List<Map<String, Object>>> gameEvents(@NonNull Path run_dir,
            @NonNull List<Map<String, Object>> rows) throws IOException {
        List<List<Map<String, Object>>> games = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            games.add(readJsonl(gameFile(run_dir, r.get("key"))));
        }
        return games;
    }

    /**
     * One JSON object per line; a missing file reads as empty. A last line that does not parse is skipped quietly: a
     * run that is still writing has a torn last line. Any other such line is skipped with a warning.
     */
    static @NonNull List<Map<String, Object>> readJsonl(@NonNull Path file) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        if (!Files.exists(file)) {
            return list;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            try {
                @SuppressWarnings("unchecked") Map<String, Object> row = Aisim.JSON.readValue(lines.get(i), Map.class);
                list.add(row);
            } catch (IOException e) {
                if (i < lines.size() - 1) {
                    System.err.println("aisim: !! skipped line " + (i + 1) + " of " + Aisim.slash(file) + ": not JSON");
                }
            }
        }
        return list;
    }

    /** The AI log files next to a game file, as printed for the user. */
    static @NonNull String aiLogs(@NonNull Path game_file) throws IOException {
        String prefix = game_file.getFileName().toString().replace(".jsonl", "-ai-s");
        List<String> logs = List.of();
        if (Files.isDirectory(game_file.getParent())) { // no folder: the game never started
            try (Stream<Path> files = Files.list(game_file.getParent())) {
                logs = files.filter(p -> p.getFileName().toString().startsWith(prefix)).map(
                        Aisim::slash).sorted().toList();
            }
        }
        if (logs.isEmpty()) {
            return "no AI log (only AIs that use AiLog write one)";
        }
        return "AI logs " + String.join(" ", logs);
    }

    /** The number at {@code key}; 0 when missing, null (failed games) or not a number. */
    static double num(@NonNull Map<?, ?> map, @NonNull String key) {
        Object value = map.get(key);
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    /** A's result for a row's winner: win, loss or draw ("a", "b" or "draw"). */
    static @NonNull String resultOfA(@Nullable Object winner) {
        if ("a".equals(winner)) {
            return "win";
        }
        if ("b".equals(winner)) {
            return "loss";
        }
        return "draw";
    }
}
