package com.oddlabs.tt.aisim.play;

import com.oddlabs.tt.aikit.harness.AiSpec;
import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.analysis.End;
import com.oddlabs.tt.aisim.analysis.Game;
import com.oddlabs.tt.aisim.analysis.Runs;
import com.oddlabs.tt.aisim.analysis.Summary;
import com.oddlabs.tt.aisim.analysis.Table;
import com.oddlabs.tt.aisim.build.Pool;
import com.oddlabs.tt.aisim.build.Snapshot;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays a run's games in worker JVMs ({@link WorkerProcess}): the simulation keeps static state, so parallel games need
 * separate processes. Everything a worker prints besides its rows (engine noise) goes
 * to {@code log/w<i>.log}. A worker is replaced after any game that did not end normally and every
 * {@value #GAMES_PER_WORKER} games. Workers stop when their stdin closes or the parent dies, so no orphans survive; a
 * {@code STOP} file in the run folder cancels the run.
 */
public final class Batch {
    /**
     * Games a worker JVM plays before a fresh one takes its place, so whatever the engine's static state gathers from
     * game to game stays bounded. A fresh worker costs about a minute of CPU, mostly the JIT compiling the game again,
     * so it should be rare.
     */
    private static final int GAMES_PER_WORKER = 100;
    /** Setup.logs: every game keeps its AI logs. */
    public static final String LOGS_ALL = "all";
    /** Setup.logs: only the games team A did not win keep their AI logs; the others' are deleted once recorded. */
    public static final String LOGS_LOST = "lost";
    /** A run is aborted once this many games in a row, from its first game on, ended with {@link End#error}. */
    private static final int ERRORS_TO_ABORT = 3;

    private Batch() {
    }

    /**
     * What a run shares besides its jobs, as run.json and the summary show it. {@code players} is the lineup as given,
     * {@code lineup} the same with each player of team A written as A, and {@code config} the maps and how the games
     * run: runs that
     * share lineup and config (and the frozen AIs besides A) play the same games, and compare pairs them game by game.
     * {@code logs} is which games keep their AI logs: {@link #LOGS_ALL}, {@link #LOGS_LOST} or null for none.
     */
    public record Setup(@NonNull String players, @NonNull String lineup, @NonNull String config,
                        @Nullable String logs) {
    }

    /**
     * Plays {@code jobs} as run {@code name} on the latest snapshot with worker JVMs within {@code limits} (see
     * {@link Pace}), then prints the summary; with {@code profile}, every worker writes a Flight Recorder profile to
     * the run's prof/ folder. Returns 2 if the run was cancelled or aborted, else the summary's exit code.
     */
    public static int run(@NonNull String name, @NonNull Setup setup, @NonNull List<Job> jobs,
            Pace.@NonNull Limits limits, boolean profile) throws IOException {
        String snap = Snapshot.latest();
        Path dir = Runs.RUNS.resolve(name);
        Files.createDirectories(dir.resolve("log"));
        String heap = WorkerProcess.heap(jobs);
        Path profile_dir = profile ? dir.resolve("prof") : null;
        RunInProgress progress = new RunInProgress(dir, name, snap, jobs, limits, heap, LOGS_LOST.equals(setup.logs()),
                profile_dir);
        int slots = progress.pace.slots();
        writeRunJson(dir, name, setup, jobs, slots, limits, profile, snap);
        String counts = jobs.size() + " games | " + (progress.pace.automatic() ? "workers auto, up to " + slots : slots + " workers");
        if (setup.logs() != null) {
            counts += setup.logs().equals(LOGS_LOST) ? " | AI logs of the games team A did not win" : " | AI logs";
        }
        if (profile) {
            counts += " | profiled";
        }
        System.out.println(
                "aisim " + name + ": " + setup.players() + " | " + setup.config() + " | " + counts + " | snapshot " + snap);
        boolean completed = progress.playAll();
        int status = Summary.run(name);
        if (profile) {
            System.out.println("profile: ./aisim.sh profile " + name + " [--focus TEXT] [--callers TEXT]");
        }
        return completed ? status : 2;
    }

    /**
     * Writes the run's run.json. {@code workers} is the most workers the run may have, {@code limits} what was asked
     * for, and {@code jobs} is what a replay later forwards to the run's own (possibly older) snapshot.
     */
    private static void writeRunJson(@NonNull Path dir, @NonNull String name, @NonNull Setup setup,
            @NonNull List<Job> jobs, int workers, Pace.@NonNull Limits limits, boolean profile,
            @NonNull String snap) throws IOException {
        Job first = jobs.get(0);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("v", 1);
        meta.put("name", name);
        meta.put("snap", snap);
        meta.put("java", System.getProperty("java.version"));
        meta.put("created", Instant.now().toString());
        meta.put("workers", workers);
        meta.put("workerLimits", limits.json());
        meta.put("profile", profile);
        meta.put("players", setup.players());
        meta.put("a", first.teamPlayers(first.team(first.side())));
        meta.put("lineup", setup.lineup());
        meta.put("config", setup.config());
        meta.put("logs", setup.logs());
        meta.put("aPools", pools(first, true));
        meta.put("pools", pools(first, false));
        meta.put("expected", jobs.size());
        meta.put("jobs", jobs);
        Aisim.JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("run.json").toFile(), meta);
    }

    /** The jar hash of every frozen AI (@TAG) on team A ({@code team_a}) or on the other teams, by its name. */
    private static @NonNull Map<String, String> pools(@NonNull Job job, boolean team_a) {
        Map<String, String> pools = new TreeMap<>();
        for (int slot = 0; slot < job.slots(); slot++) {
            String name = AiSpec.nameOf(job.spec(slot));
            if (job.onTeamA(slot) == team_a && name.startsWith("@")) {
                pools.put(name, Pool.of(name.substring(1)).sha());
            }
        }
        return pools;
    }

    /**
     * One run in progress: one driver thread per worker slot takes jobs from the shared queue, and each row is
     * appended to results.jsonl as it arrives.
     */
    private static final class RunInProgress {
        private final @NonNull Path dir;
        private final @NonNull String snap;
        private final @NonNull String heap;
        private final int expected;
        private final @NonNull Pace pace;
        /** Where workers write their profiles (--profile), else null. */
        private final @Nullable Path profile;
        /** Delete the AI logs of every game team A won (--logs lost). */
        private final boolean lost_only;
        private final long start_nanos = System.nanoTime();

        // shared by the driver threads
        private final @NonNull Queue<Job> queue;
        /** Set by cancel() and at the end of playAll(). */
        private final @NonNull AtomicBoolean stop = new AtomicBoolean();
        /**
         * Every worker JVM started, guarded by itself so cancel() can kill them; stop is checked under the same lock,
         * so no worker starts after a cancel.
         */
        private final @NonNull List<WorkerProcess> live = new ArrayList<>();
        /** Why the run was aborted; the first reason wins. */
        private final @NonNull AtomicReference<String> abort_reason = new AtomicReference<>();

        // the progress counts, guarded by this (record)
        private int done;
        private int failed;
        private int errors;
        private int wins;
        private int losses;
        private int draws;

        RunInProgress(@NonNull Path dir, @NonNull String name, @NonNull String snap, @NonNull List<Job> jobs,
                Pace.@NonNull Limits limits, @NonNull String heap, boolean lost_only, @Nullable Path profile) {
            this.lost_only = lost_only;
            this.profile = profile;
            this.dir = dir;
            this.snap = snap;
            this.heap = heap;
            this.expected = jobs.size();
            this.queue = new ConcurrentLinkedQueue<>(jobs);
            this.pace = new Pace(limits, name, WorkerProcess.footprint(heap), jobs.size(), queue::size,
                    this::liveWorkers);
        }

        /** The processes of the workers running now. */
        private @NonNull List<ProcessHandle> liveWorkers() {
            synchronized (live) {
                live.removeIf(worker -> !worker.handle().isAlive());
                return live.stream().map(WorkerProcess::handle).toList();
            }
        }

        /** Plays every job; false if the run was cancelled (STOP file, Ctrl+C) or aborted. */
        boolean playAll() throws IOException {
            Thread hook = new Thread(this::cancel, "aisim-cancel");
            Runtime.getRuntime().addShutdownHook(hook); // Ctrl+C: kill the workers at once
            startDaemon("aisim-stop-watcher", this::watchStopFile);
            pace.start();
            try {
                driveWorkers();
            } finally {
                pace.stop();
            }
            boolean cancelled = stop.getAndSet(true); // setting it also ends the watcher
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // the JVM is already shutting down (Ctrl+C): the hook runs anyway
            }
            String reason = abort_reason.get();
            if (reason != null) {
                System.out.println("!!! run aborted: " + reason);
            }
            return reason == null && !cancelled;
        }

        /** Cancels the run when a STOP file appears in its folder; returns once the run is stopped. */
        private void watchStopFile() {
            while (!stop.get()) {
                if (Files.exists(dir.resolve("STOP"))) {
                    System.out.println("STOP file found: cancelling");
                    cancel();
                }
                sleep(2000);
            }
        }

        /** Plays the queue on one driver thread per worker slot, appending every row to results.jsonl. */
        private void driveWorkers() throws IOException {
            try (Writer results = Files.newBufferedWriter(dir.resolve("results.jsonl"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                List<Thread> threads = new ArrayList<>();
                for (int i = 0; i < pace.slots(); i++) {
                    int index = i;
                    Thread thread = new Thread(() -> drive(index, results), "aisim-worker-" + i);
                    thread.start();
                    threads.add(thread);
                }
                for (Thread thread : threads) {
                    join(thread);
                }
            }
        }

        /**
         * Plays queued jobs on worker slot {@code index}, starting a fresh JVM whenever the last one was retired. While
         * the slot is at or above the pace's target, it retires its worker between games and waits.
         */
        private void drive(int index, @NonNull Writer results) {
            WorkerProcess worker = null;
            try {
                while (!stop.get()) {
                    if (index >= pace.target()) {
                        if (worker != null) {
                            worker.close();
                            worker = null;
                        }
                        if (queue.isEmpty()) {
                            break;
                        }
                        sleep(500);
                        continue;
                    }
                    Job job = queue.poll();
                    if (job == null) {
                        break;
                    }
                    if (worker == null) {
                        worker = start(index);
                        if (worker == null) {
                            break; // cancelled
                        }
                    }
                    Map<String, Object> row = worker.play(job);
                    if (stop.get()) {
                        break; // cancelled: the killed worker's game is not recorded
                    }
                    if (row == null) {
                        row = deadWorkerRow(job, worker, index);
                    }
                    record(row, results);
                    if (lost_only && "win".equals(row.get("result"))) {
                        deleteAiLogs(job);
                    }
                    worker.games++;
                    if (!End.endedNormally(row) || worker.games >= GAMES_PER_WORKER) {
                        worker.close();
                        worker = null;
                    }
                }
            } catch (IOException | RuntimeException e) {
                Logger.getLogger(Batch.class.getName()).log(Level.SEVERE, "worker thread " + index + " failed", e);
                abort("harness failure: " + e);
            } finally {
                if (worker != null) {
                    worker.close();
                }
            }
        }

        /** Deletes the AI logs a game wrote. */
        private static void deleteAiLogs(@NonNull Job job) throws IOException {
            for (int slot = 0; slot < job.slots(); slot++) {
                Path log = job.aiLogFile(slot);
                if (log != null) {
                    Files.deleteIfExists(log);
                }
            }
        }

        /** Starts a worker JVM for slot {@code index}; null if the run was cancelled. */
        private @Nullable WorkerProcess start(int index) throws IOException {
            synchronized (live) {
                if (stop.get()) {
                    return null;
                }
                Path natives = dir.resolve("n").resolve("w" + index);
                WorkerProcess worker = new WorkerProcess(workerLog(index), natives, snap, heap,
                        profile == null ? null : profile.resolve("%p.jfr"));
                live.add(worker);
                return worker;
            }
        }

        /** The log of slot {@code index}'s worker JVMs. */
        private @NonNull Path workerLog(int index) {
            return dir.resolve("log").resolve("w" + index + ".log");
        }

        /** The row of a game whose worker JVM died without answering. */
        private @NonNull Map<String, Object> deadWorkerRow(@NonNull Job job, @NonNull WorkerProcess worker,
                int index) {
            String problem = "worker exited with " + worker.exitCode() + " (see " + Aisim.slash(workerLog(index)) + ")";
            return Match.errorRow(job, snap, End.error, problem, 0);
        }

        /** Appends a game's row to results.jsonl, counts it and prints the progress line. */
        private synchronized void record(@NonNull Map<String, Object> row, @NonNull Writer results) throws IOException {
            results.write(Aisim.JSON.writeValueAsString(row) + "\n");
            results.flush();
            done++;
            Object result = row.get("result");
            if (result == null) {
                countFailure(row);
            } else {
                switch (String.valueOf(result)) {
                    case "win" -> wins++;
                    case "loss" -> losses++;
                    default -> draws++;
                }
            }
            printProgress(row, result);
        }

        /** Counts a failed game (under record's lock); aborts once the run's first games all ended with an error. */
        private void countFailure(@NonNull Map<String, Object> row) {
            failed++;
            if (End.of(row.get("end")) == End.error) {
                errors++;
                if (errors == done && done >= ERRORS_TO_ABORT) {
                    abort("the first " + done + " games could not be played: " + row.get("problem"));
                }
            }
        }

        /** Prints the progress line after {@code row} (under record's lock); with 3 teams or more, team A's place. */
        private void printProgress(@NonNull Map<String, Object> row, @Nullable Object result) {
            double minutes = (System.nanoTime() - start_nanos) / 60e9;
            double rate = done / minutes;
            double eta = (expected - done) / Math.max(rate, 1e-9);
            double game_minutes = Game.num(row, "t") / 60;
            String kd30 = formatOrDash(row.get("kd30"), "%+.0f");
            String margin = formatOrDash(row.get("margin"), "%+.2f");
            String outcome = result == null ? "-" : String.valueOf(result);
            if (result != null && row.get("teams") instanceof List<?> teams && teams.size() > 2) {
                outcome += " " + Table.number(Game.num(row, "place")) + "/" + teams.size();
            }
            System.out.printf(Locale.ROOT,
                    "[%3d/%d] %-9s %-4s %-7s %5.1fm kd30 %5s margin %6s | A %d-%d-%d | fail %d | %.1f games/min eta %.0fm%n",
                    done, expected, row.get("key"), outcome, row.get("end"), game_minutes, kd30, margin, wins, losses,
                    draws, failed, rate, eta);
        }

        /** {@code value} formatted with {@code format}, or "-" if it is not a number. */
        private static @NonNull String formatOrDash(@Nullable Object value, @NonNull String format) {
            return value instanceof Number n ? String.format(Locale.ROOT, format, n.doubleValue()) : "-";
        }

        /** Aborts the run for {@code reason}, unless it was already aborted for another. */
        private void abort(@NonNull String reason) {
            abort_reason.compareAndSet(null, reason);
            cancel();
        }

        /** Stops handing out jobs and kills every worker JVM (STOP file, Ctrl+C, abort). */
        private void cancel() {
            stop.set(true);
            synchronized (live) {
                for (WorkerProcess worker : live) {
                    worker.kill();
                }
            }
        }
    }

    // ---------------------------------------------------------------- threads

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(@NonNull Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Starts {@code body} on a daemon thread, which does not keep the JVM alive. */
    static void startDaemon(@NonNull String name, @NonNull Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
    }
}
