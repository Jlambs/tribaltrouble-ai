package com.oddlabs.tt.aisim.watch;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.aikit.harness.AiSpec;
import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.UsageException;
import com.oddlabs.tt.aisim.analysis.Runs;
import com.oddlabs.tt.aisim.build.Pool;
import com.oddlabs.tt.aisim.build.Snapshot;
import com.oddlabs.tt.aisim.play.Job;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * The parent's side of {@code ./aisim.sh watch RUN KEY | FILE.jsonl} (the internal command {@code watchfile}, run from
 * the repository root before the game starts): finds the game exactly as the harness played it, the AIs of it that
 * come from a jar and the recording to check it against, and writes them as a {@link WatchFile} with absolute paths,
 * because the game runs in {@code tt/} and may watch a run of another checkout. It prints what it found, warnings
 * about what may make the watched game differ from the recorded one, and, as its last line, the watch file's path,
 * which aisim.sh hands to the game.
 *
 * <p>The game is {@code RUN KEY}, RUN a run name ({@code aisim/runs/RUN}) or a run folder of any checkout, or a game
 * file alone. A run's job comes from its run.json; a merged run's run.json may lack some of its games' jobs, and a
 * game file has no run.json at all: then the job is rebuilt from the game file's header, which records everything
 * that decides the game (seats, map code, first player's slot, world seed).
 */
public final class WatchCommand {
    /** Where the watch files and the sessions' output folders go: this checkout's, whichever run is watched. */
    public static final Path WATCH = Aisim.ROOT.resolve("watch");
    /** The environment variable with the watch game's heap; aisim.sh gives the game {@code -Xmx} of it. */
    public static final String HEAP_VARIABLE = "AISIM_WATCH_HEAP";
    /** The watch game's heap without {@link #HEAP_VARIABLE}: aisim.sh's default, keep the two in sync. */
    public static final String DEFAULT_HEAP = "6g";
    /** Game seconds between rewind checkpoints by default (the store thins them out to stay within its memory). */
    public static final int DEFAULT_CHECKPOINT_SECONDS = 60;
    /** The share of the heap in percent that the checkpoints may take by default, and at most. */
    public static final int DEFAULT_CHECKPOINT_PERCENT = 45;
    public static final int MAX_CHECKPOINT_PERCENT = 80;
    /** The smallest heap watch accepts: the client alone runs in 512 MB, and the checkpoints need room besides. */
    private static final int MIN_HEAP_MEGABYTES = 1024;
    /** A game's key: s, the map seed, -, the first player's slot. */
    private static final Pattern KEY = Pattern.compile("s(\\d+)-(\\d+)");
    /** A heap size as -Xmx takes it. */
    private static final Pattern HEAP = Pattern.compile("([0-9]{1,9})([kKmMgG]?)");
    /** How GameRecorder starts the lines watch reads besides the header (it writes ev first, always). */
    private static final String END_LINE = "{\"ev\":\"end\"";
    private static final String CENSUS_LINE = "{\"ev\":\"census\"";
    /** A census line's time, the member right after ev. */
    private static final Pattern CENSUS_TIME = Pattern.compile("^\\{\"ev\":\"census\",\"t\":([0-9.]+),");
    /** What run.json's config says of a run with --stop-when-a-out (Aisim.run). */
    private static final String STOP_WHEN_A_OUT = "stop when A is out";

    private WatchCommand() {
    }

    /**
     * What watch was asked for: the game ({@code RUN KEY} or {@code FILE.jsonl}) and the options that go into the
     * watch file, as {@link WatchFile} describes them; the options are checked by Aisim.
     */
    public record Request(@NonNull List<String> game, boolean aiFromRun, int checkpointSeconds,
                          int checkpointMegabytes, boolean presim, @Nullable String selftest) {
    }

    /**
     * A game resolved for watching: its job exactly as the harness played it, its recording (null without one), the
     * tick it ended at (-1 if unknown), the snapshot that recorded it, the AIs that come from jars, a name for its
     * files ({@code <run>-<key>}), and notes on what was found, warnings (starting with "!! ") among them.
     */
    public record Resolved(@NonNull Job job, @Nullable Path gameFile, int endTick, @NonNull String runSnap,
                           @NonNull List<WatchFile.Loader> loaders, @NonNull String name,
                           @NonNull List<String> notes) {
    }

    /**
     * watchfile: resolves the game, writes its watch file to {@code aisim/watch/<run>-<key>.json}, prints the notes
     * and then the file's absolute path, as the last line.
     */
    public static int run(@NonNull Request request) throws IOException {
        Resolved game = resolve(request.game(), request.aiFromRun());
        String snap = thisSnapshot();
        Path file = WATCH.resolve(game.name() + ".json").toAbsolutePath().normalize();
        Path out = WATCH.resolve(game.name()).toAbsolutePath().normalize();
        Files.createDirectories(out);
        String game_file = game.gameFile() == null ? null : slash(game.gameFile());
        WatchFile watch = new WatchFile(game.job(), game_file, game.endTick(), snap, game.runSnap(), game.loaders(),
                slash(out), request.checkpointSeconds(), request.checkpointMegabytes(), request.presim(),
                request.selftest());
        Aisim.JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), watch);
        Job job = game.job();
        System.out.printf(Locale.ROOT, "watch %s %s: %s, %s (seed %d, first player in slot %d)%n", job.run(),
                job.key(), job.players(), job.map(), job.seed(), job.side());
        for (String note : game.notes()) {
            System.out.println("  " + note);
        }
        if (!snap.equals(game.runSnap())) {
            System.out.println("  " + snapshotNote(game, snap, request.aiFromRun()));
        }
        System.out.printf(Locale.ROOT,
                "  rewind: a checkpoint every %d game seconds, within %d MB of the %s heap (%s)%n",
                request.checkpointSeconds(), request.checkpointMegabytes(), heapText(System.getenv(HEAP_VARIABLE)),
                HEAP_VARIABLE);
        System.out.println("  watch file " + shown(file) + ", the session's output in " + shown(out) + "/");
        System.out.println(slash(file)); // last: aisim.sh starts the game with it
        return 0;
    }

    /**
     * Resolves {@code RUN KEY} or {@code FILE.jsonl} ({@code args}) into the game to watch; with {@code ai_from_run},
     * the seats that name an AI package by its short name play the recording snapshot's own classes of it. Usage
     * errors for what cannot be watched: an unknown run, game or AI, a play-test, a snapshot that is gone.
     */
    public static @NonNull Resolved resolve(@NonNull List<String> args, boolean ai_from_run) throws IOException {
        if (args.size() == 2) {
            return runGame(args.get(0), args.get(1), ai_from_run);
        }
        if (args.size() == 1 && args.get(0).endsWith(".jsonl")) {
            return fileGame(args.get(0), ai_from_run);
        }
        throw new UsageException("watch takes RUN KEY, or a game file FILE.jsonl; not " + args);
    }

    // ---------------------------------------------------------------- finding the game

    /** RUN KEY: the job from run.json (or rebuilt from the game file's header), snap and end from the result row. */
    private static @NonNull Resolved runGame(@NonNull String run, @NonNull String key,
            boolean ai_from_run) throws IOException {
        Path dir = runDir(run);
        String run_name = dir.getFileName().toString();
        Map<String, Object> row = row(dir, run_name, key);
        JsonNode run_json = Aisim.JSON.readTree(dir.resolve("run.json").toFile());
        Path game_file = Runs.gameFile(dir, key).toAbsolutePath().normalize();
        Recording recording = Files.exists(game_file) ? Recording.read(game_file) : null;
        List<String> notes = new ArrayList<>();
        Job job = runJsonJob(run_json, key);
        if (job == null) {
            if (recording == null) {
                String missing = "and no game file " + shown(game_file) + " to rebuild it from";
                throw new UsageException("run " + run_name + " has no job " + key + " in run.json, " + missing);
            }
            job = rebuild(recording, row, run_json);
            notes.add("run.json has no job " + key + " (a merged run?): rebuilt it from the game file's header");
        } else if (recording != null) {
            // a merged run may have replaced the game its run.json job describes: the recording is what gets checked
            Job recorded = rebuild(recording, row, run_json);
            String differs = decidingDifference(job, recorded);
            if (differs != null) {
                String watching = "watching the recorded game, its job rebuilt from the game file's header";
                notes.add("!! run.json's job " + key + " is not the recorded game (" + differs + "): " + watching);
                job = recorded;
            }
        }
        if (!job.run().equals(run_name)) {
            String merged = run_name + " is a merged or renamed run";
            notes.add("played as game " + key + " of run " + job.run() + " (" + merged + ")");
        }
        String run_snap = row.get("snap") instanceof String snap ? snap : run_json.path("snap").asText();
        Object header_snap = recording == null ? null : recording.header().get("snap");
        if (header_snap != null && !header_snap.equals(run_snap)) {
            notes.add("!! the result row says snapshot " + run_snap + " played the game, its game file " + header_snap);
        }
        String name = safeName(run_name) + "-" + safeName(key);
        return finish(job, recording, endTick(recording, row), run_snap, aisimFolder(dir), recordedPools(run_json),
                ai_from_run, name, row, notes);
    }

    /**
     * FILE.jsonl: the job rebuilt from the game file's header, which also names the snapshot that recorded it. A game
     * file of a run (in its g/ or replay/) is two folders below the run: its result row and run.json then fill in what
     * the header lacks, and give the frozen AIs' hashes.
     */
    private static @NonNull Resolved fileGame(@NonNull String name, boolean ai_from_run) throws IOException {
        Path file;
        try {
            file = Path.of(name).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new UsageException("not a file name: " + name);
        }
        if (!Files.isRegularFile(file)) {
            throw new UsageException("no game file " + name);
        }
        Recording recording = Recording.read(file);
        if (!(recording.header().get("snap") instanceof String run_snap) || run_snap.isEmpty()) {
            requireHarnessGame(recording.header(), shown(file)); // a play-test's refusal says more
            throw new UsageException(name + " does not say which snapshot recorded it (no snap in its header)");
        }
        Path run_dir = file.getParent().getParent();
        Path run_aisim = run_dir == null ? null : aisimFolder(run_dir);
        JsonNode run_json = null;
        Map<String, Object> row = null;
        if (run_dir != null && Files.exists(run_dir.resolve("run.json"))) {
            run_json = Aisim.JSON.readTree(run_dir.resolve("run.json").toFile());
            Object key = recording.header().get("key");
            for (Map<String, Object> line : Runs.readJsonl(run_dir.resolve("results.jsonl"))) {
                if (row == null && key != null && key.equals(line.get("key"))) {
                    row = line;
                }
            }
        }
        Job job = rebuild(recording, row, run_json);
        Map<String, String> pools = run_json == null ? Map.of() : recordedPools(run_json);
        String files = safeName(job.run()) + "-" + safeName(job.key());
        return finish(job, recording, endTick(recording, null), run_snap, run_aisim, pools, ai_from_run, files, row,
                new ArrayList<>());
    }

    /** The common part: the recording's notes, the AIs from jars, and a check that this build has every other AI. */
    private static @NonNull Resolved finish(@NonNull Job job, @Nullable Recording recording, int end_tick,
            @NonNull String run_snap, @Nullable Path run_aisim, @NonNull Map<String, String> recorded_pools,
            boolean ai_from_run, @NonNull String name, @Nullable Map<String, Object> row,
            @NonNull List<String> notes) throws IOException {
        noteRecording(recording, end_tick, run_snap, row, notes);
        Set<String> names = new LinkedHashSet<>();
        for (Job.Seat seat : job.seats()) {
            names.add(AiSpec.nameOf(seat.spec()));
        }
        List<WatchFile.Loader> loaders = new ArrayList<>();
        boolean swapped = false;
        for (String ai : names) {
            if (ai.startsWith("@")) {
                loaders.add(frozen(ai, run_aisim, recorded_pools, notes));
            } else if (ai_from_run && byPackage(ai)) {
                loaders.add(fromRunSnapshot(ai, run_snap, run_aisim, notes));
                swapped = true;
            } else {
                requirePlayable(job, ai, ai_from_run, run_snap);
            }
        }
        if (ai_from_run && !swapped) {
            notes.add("""
                    --ai-from-run changes nothing: no seat names an AI package by its short name (the stock AI and \
                    frozen @TAG AIs play as they are)""");
        }
        Path game_file = recording == null ? null : recording.file();
        return new Resolved(job, game_file, end_tick, run_snap, List.copyOf(loaders), name, List.copyOf(notes));
    }

    /** The folder of run {@code run}: {@code aisim/runs/RUN}, or RUN itself as a path to a run folder. */
    private static @NonNull Path runDir(@NonNull String run) {
        try {
            Path named = Runs.RUNS.resolve(run);
            if (Files.exists(named.resolve("run.json"))) {
                return named.toAbsolutePath().normalize();
            }
            Path path = Path.of(run);
            if (Files.exists(path.resolve("run.json"))) {
                return path.toAbsolutePath().normalize();
            }
        } catch (InvalidPathException e) {
            // neither a run name nor a path: the error below
        }
        if (run.contains("/") || run.contains("\\")) {
            throw new UsageException("no run " + run + ": that folder has no run.json");
        }
        throw new UsageException("no run " + run + " in " + slash(Runs.RUNS));
    }

    /** Game {@code key}'s result row in the run folder {@code dir}; a usage error, with some of its keys, if none. */
    private static @NonNull Map<String, Object> row(@NonNull Path dir, @NonNull String run,
            @NonNull String key) throws IOException {
        List<Map<String, Object>> rows = Runs.readJsonl(dir.resolve("results.jsonl"));
        for (Map<String, Object> row : rows) {
            if (key.equals(row.get("key"))) {
                return row;
            }
        }
        List<String> keys = rows.stream().map(row -> String.valueOf(row.get("key"))).limit(5).toList();
        String some = keys.isEmpty() ? "it has no finished game yet" : "its games are like " + String.join(", ", keys);
        throw new UsageException("run " + run + " has no game " + key + " (" + some + ")");
    }

    /** Game {@code key}'s job in run.json, exactly as the harness played it; null when run.json lacks it. */
    private static @Nullable Job runJsonJob(@NonNull JsonNode run_json, @NonNull String key) throws IOException {
        for (JsonNode job : run_json.path("jobs")) {
            if (key.equals(job.path("key").asText())) {
                return Aisim.JSON.treeToValue(job, Job.class);
            }
        }
        return null;
    }

    /** The jar hashes of the frozen AIs the run played, by name (@TAG): run.json's aPools and pools. */
    private static @NonNull Map<String, String> recordedPools(@NonNull JsonNode run_json) {
        Map<String, String> pools = new LinkedHashMap<>();
        for (String field : List.of("aPools", "pools")) {
            run_json.path(field).properties().forEach(pool -> pools.put(pool.getKey(), pool.getValue().asText()));
        }
        return pools;
    }

    /** The tick the recorded game ended at: its end line's time, else the row's; -1 when neither says. */
    private static int endTick(@Nullable Recording recording, @Nullable Map<String, Object> row) {
        Object t = null;
        if (recording != null && recording.end() != null) {
            t = recording.end().get("t");
        } else if (row != null) {
            t = row.get("t");
        }
        if (!(t instanceof Number seconds)) {
            return -1;
        }
        return (int) Math.round(seconds.doubleValue() * GameTime.TICKS_PER_SECOND);
    }

    /** The {@code aisim} folder of the checkout a run folder is in ({@code <aisim>/runs/<run>}), or null. */
    private static @Nullable Path aisimFolder(@NonNull Path run_dir) {
        Path runs = run_dir.toAbsolutePath().normalize().getParent();
        if (runs == null || runs.getFileName() == null || !runs.getFileName().toString().equals("runs")) {
            return null;
        }
        return runs.getParent();
    }

    // ---------------------------------------------------------------- the job from a game file's header

    /**
     * What watch reads of a game file: its header, the lineup text (the header's first {@code players}, which the
     * players array after it hides from a JSON map), the end line (null if the game has none) and the number of game
     * seconds with a census checksum.
     */
    record Recording(@NonNull Path file, @NonNull Map<String, Object> header, @Nullable String lineup,
                     @Nullable Map<String, Object> end, int censusSeconds) {

        static @NonNull Recording read(@NonNull Path file) throws IOException {
            Map<String, Object> header = null;
            String lineup = null;
            Map<String, Object> end = null;
            Set<String> census_times = new HashSet<>();
            try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line = lines.readLine();
                if (line != null) {
                    try {
                        header = Aisim.JSON.readValue(line, Aisim.JSON_OBJECT);
                        lineup = lineupText(line);
                    } catch (IOException e) {
                        header = null; // the error below
                    }
                }
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith(CENSUS_LINE) && line.contains("\"checksum\":")) {
                        Matcher time = CENSUS_TIME.matcher(line);
                        if (time.find()) {
                            census_times.add(time.group(1));
                        }
                    } else if (line.startsWith(END_LINE)) {
                        try {
                            end = Aisim.JSON.readValue(line, Aisim.JSON_OBJECT);
                        } catch (IOException e) {
                            end = null; // torn: the game is still being written
                        }
                    }
                }
            }
            if (header == null || !"game".equals(header.get("ev"))) {
                throw new UsageException(slash(file) + " is not a game file: its first line is not a game header");
            }
            return new Recording(file, header, lineup, end, census_times.size());
        }

        /** The header's first {@code players} member when it is text (the lineup), or null. */
        private static @Nullable String lineupText(@NonNull String header) throws IOException {
            try (JsonParser parser = Aisim.JSON.createParser(header)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return null;
                }
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String field = parser.currentName();
                    JsonToken value = parser.nextToken();
                    if (field.equals("players") && value == JsonToken.VALUE_STRING) {
                        return parser.getText();
                    }
                    parser.skipChildren();
                }
            }
            return null;
        }
    }

    /**
     * The job of a recorded harness game, from its header: the seats from {@code players[]} (ai, race, team), the map
     * from the map code, the first player's slot from the key, and the world seed ({@code rng}); what the header lacks
     * and only the harness's end rules read comes from the result row and run.json when there are ones, else the
     * defaults. The job's game file is the recording; it keeps no AI logs.
     */
    static @NonNull Job rebuild(@NonNull Recording recording, @Nullable Map<String, Object> row,
            @Nullable JsonNode run_json) {
        Map<String, Object> header = recording.header();
        String file = shown(recording.file());
        requireHarnessGame(header, file);
        String file_key = recording.file().getFileName().toString().replace(".jsonl", "");
        String key = header.get("key") instanceof String text ? text : file_key;
        Matcher key_parts = KEY.matcher(key);
        if (!key_parts.matches()) {
            throw new UsageException(file + ": its key '" + key + "' is not s<seed>-<slot>");
        }
        int side = Integer.parseInt(key_parts.group(2));
        Job.MapCode map = Job.MapCode.decode(String.valueOf(header.get("mapcode")));
        if (!(header.get("seed") instanceof Number seed) || seed.intValue() != map.seed()) {
            Object recorded = header.get("seed");
            throw new UsageException(file + ": its seed " + recorded + " is not its map code's " + map.seed());
        }
        List<Job.Seat> seats = seats(header, file);
        if (side >= seats.size()) {
            throw new UsageException(file + ": its key " + key + " names slot " + side + " of " + seats.size());
        }
        Long rng = header.get("rng") instanceof Number number ? number.longValue() : null;
        int minutes = Aisim.DEFAULT_MINUTES;
        boolean collapse = true;
        if (row != null) {
            minutes = row.get("minutes") instanceof Number number ? number.intValue() : minutes;
            collapse = !(row.get("collapse") instanceof Boolean on) || on;
        }
        boolean stop_when_a_out = run_json != null && run_json.path("config").asText().contains(STOP_WHEN_A_OUT);
        String run = header.get("run") instanceof String text ? text : "file";
        String lineup = recording.lineup();
        if (lineup == null) {
            lineup = row != null && row.get("players") instanceof String text ? text : lineupOf(seats);
        }
        return new Job(run, key, map.seed(), side, lineup, seats, map.size(), map.terrain(), map.hills(), map.trees(),
                map.supplies(), minutes, rng, collapse, stop_when_a_out, jobPath(recording.file()), null);
    }

    /**
     * The fields that decide the game (seats, map, first player's slot, world seed) in which two jobs differ, such as
     * "seats, rng"; null when they are the same game. The others only label a game or end it by the harness's rules.
     */
    static @Nullable String decidingDifference(@NonNull Job a, @NonNull Job b) {
        Map<String, Boolean> same = new LinkedHashMap<>();
        same.put("seats", a.seats().equals(b.seats()));
        same.put("seed", a.seed() == b.seed());
        same.put("side", a.side() == b.side());
        same.put("map", a.size() == b.size() && a.terrain() == b.terrain() && a.hills() == b.hills()
                && a.trees() == b.trees() && a.supplies() == b.supplies());
        same.put("rng", Objects.equals(a.rng(), b.rng()));
        List<String> differ = same.entrySet().stream().filter(field -> !field.getValue()).map(
                Map.Entry::getKey).toList();
        return differ.isEmpty() ? null : String.join(", ", differ);
    }

    /** Fails with a usage error unless the header is a harness game's: a play-test has human orders watch lacks. */
    private static void requireHarnessGame(@NonNull Map<String, Object> header, @NonNull String file) {
        Object source = header.get("source");
        if ("aisim".equals(source)) {
            return;
        }
        String what = "not a harness game (source " + source + ")";
        if ("gui".equals(source)) {
            what = "a play-test: its human player's orders are not in it (docs/aisim.md#play-tests re-simulates one)";
        }
        String plays = "watch plays the games of play and batch, where every player is an AI";
        throw new UsageException(file + " is " + what + "; " + plays);
    }

    /** The seats of the header's {@code players}, in slot order. */
    private static @NonNull List<Job.Seat> seats(@NonNull Map<String, Object> header, @NonNull String file) {
        List<Job.Seat> seats = new ArrayList<>();
        if (header.get("players") instanceof List<?> players) {
            for (Object entry : players) {
                Job.Seat seat = seat(entry, seats.size());
                if (seat == null) {
                    String expected = "is not {s, ai, race, team} in slot order: " + entry;
                    throw new UsageException(file + ": player " + seats.size() + " of its header " + expected);
                }
                seats.add(seat);
            }
        }
        if (seats.size() < 2) {
            throw new UsageException(file + ": its header lists " + seats.size() + " players");
        }
        return seats;
    }

    /** The seat of one player of a header, {@code {s, ai, race, team, ...}}, if it is in slot {@code slot}; or null. */
    private static Job.@Nullable Seat seat(@Nullable Object entry, int slot) {
        if (!(entry instanceof Map<?, ?> player) || !(player.get("s") instanceof Number s) || s.intValue() != slot) {
            return null;
        }
        if (!(player.get("ai") instanceof String spec) || !(player.get("team") instanceof Number team)) {
            return null;
        }
        Object race = player.get("race");
        if (!Job.VIKINGS.equals(race) && !Job.NATIVES.equals(race)) {
            return null;
        }
        return new Job.Seat(spec, (String) race, team.intValue());
    }

    /** A lineup text for seats whose run's text is unknown: each team's specs, teams separated by vs. */
    private static @NonNull String lineupOf(@NonNull List<Job.Seat> seats) {
        int teams = seats.stream().mapToInt(Job.Seat::team).max().orElse(0) + 1;
        List<String> parts = new ArrayList<>();
        for (int team = 0; team < teams; team++) {
            List<String> specs = new ArrayList<>();
            for (Job.Seat seat : seats) {
                if (seat.team() == team) {
                    specs.add(seat.spec());
                }
            }
            parts.add(String.join(" ", specs));
        }
        return String.join(" vs ", parts);
    }

    /** A job's game path: relative to the repository root when the file is in this checkout, else absolute. */
    private static @NonNull String jobPath(@NonNull Path file) {
        return shown(file);
    }

    // ---------------------------------------------------------------- the AIs

    /** Whether AI {@code name} is a package by its short name, which --ai-from-run takes from the run's snapshot. */
    private static boolean byPackage(@NonNull String name) {
        return !AiSpec.isStock(name) && AiSpec.isShortName(name);
    }

    /**
     * Frozen AI {@code name} (@TAG): from this checkout's pool, or from the pool of the run's checkout; the one whose
     * jar the run played when run.json recorded its hash, with a warning when neither is.
     */
    private static WatchFile.@NonNull Loader frozen(@NonNull String name, @Nullable Path run_aisim,
            @NonNull Map<String, String> recorded, @NonNull List<String> notes) throws IOException {
        String tag = name.substring(1);
        List<Path> folders = distinctFolders(Pool.POOL, run_aisim == null ? null : run_aisim.resolve("pool"));
        List<Pool> found = new ArrayList<>();
        for (Path folder : folders) {
            Pool pool = Pool.find(folder, tag);
            if (pool != null) {
                found.add(pool);
            }
        }
        if (found.isEmpty()) {
            String looked = String.join(" or ", folders.stream().map(WatchCommand::shown).toList());
            throw new UsageException("no frozen AI " + name + " in " + looked);
        }
        String sha = recorded.get(name);
        Pool pool = found.stream().filter(p -> p.sha().equals(sha)).findFirst().orElse(found.get(0));
        Path jar = pool.jar().toAbsolutePath().normalize();
        if (!Files.exists(jar)) {
            throw new UsageException("frozen AI " + name + " has no jar " + shown(jar));
        }
        if (sha == null) {
            notes.add(name + ": frozen AI " + shown(jar));
        } else if (sha.equals(pool.sha())) {
            notes.add(name + ": frozen AI " + shown(jar) + ", the jar the run played");
        } else {
            String hashes = "(sha " + shortSha(pool.sha()) + ", the run's " + shortSha(sha) + ")";
            String differs = "the game may differ from the recording";
            notes.add("!! " + name + ": " + shown(jar) + " is not the jar the run played " + hashes + ": " + differs);
        }
        return new WatchFile.Loader(name, pool.entry(), pool.packageName(), slash(jar));
    }

    /**
     * --ai-from-run: AI {@code name}'s package from the classes of snapshot {@code run_snap}, in the run's checkout or
     * this one; a usage error when that snapshot or its classes are gone.
     */
    private static WatchFile.@NonNull Loader fromRunSnapshot(@NonNull String name, @NonNull String run_snap,
            @Nullable Path run_aisim, @NonNull List<String> notes) throws IOException {
        String entry = AiSpec.className(name);
        String package_name = entry.substring(0, entry.lastIndexOf('.'));
        String class_file = entry.replace('.', '/') + ".class";
        List<Path> folders = distinctFolders(run_aisim == null ? null : run_aisim.resolve("snap"), Snapshot.SNAP);
        Path missing_jar = null;
        boolean has_snapshot = false;
        for (Path folder : folders) {
            for (Path jar : Snapshot.classJars(folder, run_snap)) {
                has_snapshot = true;
                if (!Files.exists(jar)) {
                    missing_jar = jar;
                } else if (holds(jar, class_file)) {
                    notes.add(name + ": the run's own, from snapshot " + run_snap + " (--ai-from-run): " + shown(jar));
                    return new WatchFile.Loader(name, entry, package_name, slash(jar));
                }
            }
        }
        if (!has_snapshot) {
            String looked = String.join(" and ", folders.stream().map(WatchCommand::shown).toList());
            throw new UsageException("""
                    --ai-from-run: the run's snapshot %s is gone (not in %s); without --ai-from-run, watch plays this \
                    build's %s""".formatted(run_snap, looked, name));
        }
        if (missing_jar != null) {
            String gone = shown(missing_jar);
            throw new UsageException(
                    "--ai-from-run: the classes of the run's snapshot " + run_snap + " are gone: " + gone);
        }
        throw new UsageException("--ai-from-run: the run's snapshot " + run_snap + " has no class " + entry);
    }

    /** Fails with a usage error unless this build can play AI {@code name} itself. */
    private static void requirePlayable(@NonNull Job job, @NonNull String name, boolean ai_from_run,
            @NonNull String run_snap) {
        for (Job.Seat seat : job.seats()) {
            if (!AiSpec.nameOf(seat.spec()).equals(name)) {
                continue;
            }
            try {
                AiSpec.check(seat.spec());
            } catch (IllegalArgumentException e) {
                String advice = "";
                if (!ai_from_run && byPackage(name)) {
                    advice = "; --ai-from-run plays the run's own " + name + ", from snapshot " + run_snap;
                }
                throw new UsageException("this build cannot play " + seat.spec() + ": " + e.getMessage() + advice);
            }
            return;
        }
    }

    /** Whether the jar {@code jar} holds the file {@code name}. */
    private static boolean holds(@NonNull Path jar, @NonNull String name) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(name) != null;
        }
    }

    /** The folders that are given, absolute, each once. */
    private static @NonNull List<Path> distinctFolders(@Nullable Path first, @Nullable Path second) {
        Set<Path> folders = new LinkedHashSet<>();
        for (Path folder : new Path[]{first, second}) {
            if (folder != null) {
                folders.add(folder.toAbsolutePath().normalize());
            }
        }
        return List.copyOf(folders);
    }

    // ---------------------------------------------------------------- notes and names

    /**
     * Notes what the recording offers: who recorded it, when and how it ended, and how much of it the viewer can
     * check the game against.
     */
    private static void noteRecording(@Nullable Recording recording, int end_tick, @NonNull String run_snap,
            @Nullable Map<String, Object> row, @NonNull List<String> notes) {
        if (recording == null) {
            notes.add("!! no game file: the viewer has nothing to check the game against");
            return;
        }
        Map<String, Object> end_line = recording.end();
        String end = "no end line (still being played?), so the viewer plays on until you stop it";
        if (end_line != null) {
            end = "ended at " + clock(end_tick) + " (" + end_line.get("end") + ")";
        }
        String census = recording.censusSeconds() + " census checksums to check the game against";
        notes.add("recorded by snapshot " + run_snap + ": " + end + "; " + census);
        Object row_end = row == null ? null : row.get("end");
        if ("crash".equals(row_end) || "hang".equals(row_end) || "error".equals(row_end)) {
            notes.add("!! the game ended in a " + row_end + ": the viewer may fail the same way when it gets there");
        }
    }

    /** The warning or note when the game was recorded by another snapshot than the one the viewer runs. */
    private static @NonNull String snapshotNote(@NonNull Resolved game, @NonNull String snap, boolean ai_from_run) {
        List<String> swapped = new ArrayList<>();
        for (WatchFile.Loader loader : game.loaders()) {
            if (!loader.name().startsWith("@")) {
                swapped.add(loader.name());
            }
        }
        if (ai_from_run && !swapped.isEmpty()) {
            return """
                    the engine is this build's (snapshot %s), %s the run's (%s): the viewer checks every \
                    census""".formatted(snap, String.join(", ", swapped), game.runSnap());
        }
        Set<String> by_package = new LinkedHashSet<>();
        for (Job.Seat seat : game.job().seats()) {
            if (byPackage(AiSpec.nameOf(seat.spec()))) {
                by_package.add(AiSpec.nameOf(seat.spec()));
            }
        }
        String advice = "";
        if (!by_package.isEmpty()) {
            advice = "; --ai-from-run plays the run's own " + String.join(", ", by_package);
        }
        return """
                !! recorded by snapshot %s, but this build is %s: if the engine or an AI changed in between, the game \
                differs from the recording (the viewer checks every census and shows where it diverged)\
                """.formatted(game.runSnap(), snap) + advice;
    }

    /** The snapshot the viewer runs: the one aisim.sh started this JVM from, else the latest build. */
    private static @NonNull String thisSnapshot() {
        String snap = System.getProperty("aisim.snap");
        return snap != null && !snap.isEmpty() ? snap : Snapshot.latest();
    }

    /**
     * The watch game's heap in MB: {@code value} ({@link #HEAP_VARIABLE}) as {@code -Xmx} takes it, such as 6g or
     * 8192m, or {@link #DEFAULT_HEAP} when it is null or empty; a usage error when malformed or below 1 GB.
     */
    public static int heapMegabytes(@Nullable String value) {
        String heap = heapText(value);
        Matcher parts = HEAP.matcher(heap);
        if (!parts.matches()) {
            throw new UsageException(
                    HEAP_VARIABLE + " is a heap size as -Xmx takes it, such as 6g or 8192m; not '" + heap + "'");
        }
        int shift = switch (parts.group(2).toLowerCase(Locale.ROOT)) {
            case "g" -> 30;
            case "m" -> 20;
            case "k" -> 10;
            default -> 0;
        };
        long megabytes = (Long.parseLong(parts.group(1)) << shift) >> 20;
        if (megabytes < MIN_HEAP_MEGABYTES) {
            throw new UsageException(HEAP_VARIABLE + "=" + heap + " is too small: watch needs at least 1g");
        }
        return (int) Math.min(megabytes, Integer.MAX_VALUE);
    }

    private static @NonNull String heapText(@Nullable String value) {
        return value == null || value.isEmpty() ? DEFAULT_HEAP : value;
    }

    /** A file name part: {@code text} with every character but A-Z a-z 0-9 . _ - replaced by _. */
    private static @NonNull String safeName(@NonNull String text) {
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Game ticks as h:mm:ss. */
    private static @NonNull String clock(int ticks) {
        int seconds = ticks / GameTime.TICKS_PER_SECOND;
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private static @NonNull String shortSha(@NonNull String sha) {
        return sha.substring(0, Math.min(12, sha.length()));
    }

    /** A path as printed: relative to the repository root when it is in this checkout, else absolute. */
    private static @NonNull String shown(@NonNull Path path) {
        Path root = Path.of("").toAbsolutePath();
        Path absolute = path.toAbsolutePath().normalize();
        return slash(absolute.startsWith(root) ? root.relativize(absolute) : absolute);
    }

    private static @NonNull String slash(@NonNull Path path) {
        return Aisim.slash(path);
    }
}
