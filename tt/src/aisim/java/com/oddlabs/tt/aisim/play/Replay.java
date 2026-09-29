package com.oddlabs.tt.aisim.play;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.UsageException;
import com.oddlabs.tt.aisim.analysis.Runs;
import com.oddlabs.tt.aisim.analysis.Table;
import com.oddlabs.tt.aisim.build.Snapshot;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.oddlabs.tt.aisim.analysis.Game.num;

/**
 * replay RUN KEY: plays one game of a run again, with AI logs on, and compares it with the original sample by sample.
 * On the run's own snapshot the game must replay identically (VERIFIED), which checks that the AIs are deterministic
 * and that logging does not change their decisions; on another snapshot it shows whether that build plays the game
 * the same way.
 */
public final class Replay {
    /** Printed under a replay MISMATCH: the usual ways an AI loses determinism. */
    private static final String NONDETERMINISM_HINT = """
              the AI is not deterministic: check for wall-clock reads, unseeded Random, iterating classifyUnits() or \
            identity-hashed collections, static state kept across games, or decisions that depend on logging""";

    private Replay() {
    }

    /**
     * Replays game {@code key} of {@code run} with AI logs on, on the run's snapshot or on {@code snap_option}, for
     * {@code until} game minutes or to the end, and verifies it; with {@code profile}, under Flight Recorder, next to
     * the replayed game. Returns 1 when a finished game replayed on its own snapshot differs.
     */
    public static int run(@NonNull String run, @NonNull String key, @Nullable String snap_option,
            @Nullable Integer until, boolean stale_ok, boolean profile) throws IOException {
        Path dir = Runs.dir(run);
        Map<String, Object> original = Runs.row(run, key);
        ObjectNode job = originalJob(dir, run, key);
        String run_snap = (String) original.get("snap");
        String snap_id = requestedSnapshot(snap_option, stale_ok);
        boolean same_build = snap_id == null || snap_id.equals(run_snap);
        String snap = same_build ? run_snap : snap_id;
        Path out = dir.resolve(same_build ? "replay" : "replay-" + snap_id);
        Path original_game = Runs.gameFile(dir, key);
        Path replay_game = out.resolve(key + ".jsonl");
        Path worker_log = out.resolve(key + ".worker.log");
        Files.createDirectories(out);
        job.put("game", Aisim.slash(replay_game));
        job.put("logs", Aisim.slash(out.resolve(key)));
        if (until != null) {
            job.put("minutes", until);
        }
        String matchup = original.get("players") + ", " + original.get("map");
        String setup = "seed " + original.get("seed") + ", first player in slot " + original.get("side");
        String game = run + " " + key + " (" + matchup + ", " + setup + ")";
        System.out.println("replaying " + game + " on snapshot " + snap + " with AI logs on ...");
        Path natives = dir.resolve("n").resolve("r");
        String heap = WorkerProcess.heap(job.get("seats").size(), job.get("size").asInt());
        Path recording = profile ? out.resolve(key + ".jfr") : null;
        if (recording != null) {
            Files.deleteIfExists(recording);
        }
        WorkerProcess worker = new WorkerProcess(worker_log, natives, snap, heap, recording);
        Map<String, Object> row = worker.play(job);
        worker.close();
        if (recording != null) {
            System.out.println("profile: ./aisim.sh profile " + Aisim.slash(recording) + " [--focus TEXT]");
        }
        if (row == null) {
            System.out.println("!!! the replay worker died; see " + Aisim.slash(worker_log));
            return 1;
        }
        Aisim.JSON.writeValue(out.resolve(key + ".row.json").toFile(), row);
        return verify(original_game, replay_game, original, row, same_build, until != null);
    }

    /**
     * Game {@code key}'s job as the run's run.json holds it. It goes to a worker of the run's own snapshot exactly as
     * that harness wrote it (only its output files and length change), so replays keep working when later harness
     * versions add fields.
     */
    private static @NonNull ObjectNode originalJob(@NonNull Path dir, @NonNull String run,
            @NonNull String key) throws IOException {
        for (JsonNode job : Aisim.JSON.readTree(dir.resolve("run.json").toFile()).get("jobs")) {
            if (key.equals(job.get("key").asText())) {
                return (ObjectNode) job;
            }
        }
        throw new UsageException("run " + run + " has no job " + key);
    }

    /** The snapshot that --snap asks for, checked to exist ("latest" also to be fresh); null without --snap. */
    private static @Nullable String requestedSnapshot(@Nullable String snap_option,
            boolean stale_ok) throws IOException {
        if (snap_option == null) {
            return null;
        }
        String id = snap_option;
        if (snap_option.equals("latest")) {
            Snapshot.requireFresh(stale_ok);
            id = Snapshot.latest();
        }
        Snapshot.cpArgs(id); // throws "no snapshot ID" for an unknown id
        return id;
    }

    /**
     * Compares a replay with the original game, sample by sample, and prints the verdict. {@code partial}: --until
     * stopped the replay early, so the final checksum is not compared. Returns 1 only when a finished game replayed
     * on the same build differs or shares no sample with it.
     */
    private static int verify(@NonNull Path original_file, @NonNull Path replay_file,
            @NonNull Map<String, Object> original, @NonNull Map<String, Object> replay, boolean same_build,
            boolean partial) throws IOException {
        Map<String, Map<String, Object>> original_samples = samples(original_file);
        Map<String, Map<String, Object>> replay_samples = samples(replay_file);
        String difference = null;
        int compared = 0;
        for (Map.Entry<String, Map<String, Object>> sample : replay_samples.entrySet()) {
            Map<String, Object> before = original_samples.get(sample.getKey());
            if (before == null) {
                continue;
            }
            compared++;
            if (!before.equals(sample.getValue())) {
                difference = "t=" + sample.getKey() + ": " + changedFields(before, sample.getValue());
                break;
            }
        }
        Object original_checksum = original.get("checksum");
        Object replay_checksum = replay.get("checksum");
        boolean finished = original_checksum != null; // hangs and dead workers left no final checksum
        if (difference == null && !partial && finished && !Objects.equals(original_checksum, replay_checksum)) {
            difference = "final checksum " + original_checksum + " -> " + replay_checksum;
        }
        boolean failed = false;
        if (!finished) {
            String why = original.get("end") + ": " + original.get("problem");
            String samples = difference == null ? "match" : "differ from " + difference;
            System.out.println("the original game did not finish (" + why + "); " + compared + " samples " + samples);
        } else if (compared == 0) {
            System.out.println("NOT VERIFIED: the replay has no census sample in common with the game");
            failed = same_build;
        } else if (same_build && difference == null) {
            String checksum = "checksum " + replay_checksum;
            System.out.println("VERIFIED: the replay reproduced the game (" + compared + " samples, " + checksum + ")");
        } else if (same_build) {
            System.out.println("MISMATCH: first difference at " + difference + "\n" + NONDETERMINISM_HINT);
            failed = true;
        } else if (difference == null) {
            System.out.println("SAME: the other build plays this game identically");
        } else {
            System.out.println("DIFFERENT from " + difference);
        }
        String outcome = replay.get("end") + ", team A " + replay.get("result") + " at " + Table.clock(num(replay,
                "t"));
        String files = "game " + Aisim.slash(replay_file) + " | " + Runs.aiLogs(replay_file);
        System.out.println("replay " + outcome + " | " + files);
        return failed ? 1 : 0;
    }

    /** The fields of a sample that changed, as {field=old -> new, ...} over the original's fields. */
    private static @NonNull String changedFields(@NonNull Map<String, Object> before,
            @NonNull Map<String, Object> after) {
        Map<String, String> fields = new TreeMap<>();
        before.forEach((k, v) -> {
            if (!Objects.equals(v, after.get(k))) {
                fields.put(k, v + " -> " + after.get(k));
            }
        });
        return fields.toString();
    }

    /** A game file's census samples keyed by time and slot, in file order. */
    private static @NonNull Map<String, Map<String, Object>> samples(@NonNull Path file) throws IOException {
        Map<String, Map<String, Object>> samples = new LinkedHashMap<>();
        for (Map<String, Object> line : Runs.readJsonl(file)) {
            if ("census".equals(line.get("ev"))) {
                samples.put(String.format(Locale.ROOT, "%.2f s%s", num(line, "t"), line.get("s")), line);
            }
        }
        return samples;
    }
}
