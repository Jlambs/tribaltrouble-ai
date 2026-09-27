package com.oddlabs.tt.aisim;

import com.fasterxml.jackson.core.type.TypeReference;
import com.oddlabs.tt.aikit.AiSpec;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.ProcessBuilder.Redirect;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays a run's games in worker JVMs ({@link WorkerMain}): the simulation keeps static state and needs its own GL
 * context, so parallel games need separate processes. The parent hands each worker one job per stdin line and reads
 * one {@code @@{row}} line back; everything else a worker prints (engine noise) goes to {@code log/w<i>.log}. A worker
 * is replaced after any game that did not end normally and every {@value #GAMES_PER_WORKER} games. Workers stop when
 * their stdin closes or the parent dies, so no orphans survive; a {@code STOP} file in the run folder cancels the run.
 */
final class Batch {
    /**
     * Games a worker JVM plays before a fresh one takes its place, so whatever the engine's static state gathers from
     * game to game stays bounded.
     */
    private static final int GAMES_PER_WORKER = 25;
    /** A run is aborted once this many games in a row, from its first game on, ended with {@link End#error}. */
    private static final int ERRORS_TO_ABORT = 3;
    /** Starts the one line per game on a worker's stdout that carries the game's row. */
    static final String ROW_PREFIX = "@@";
    /** A row as Jackson reads it: a LinkedHashMap of JSON values. */
    private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {
    };

    private Batch() {
    }

    /**
     * Plays {@code jobs} as run {@code name} on the latest snapshot with up to {@code workers} JVMs, then prints the
     * summary. Returns 2 if the run was cancelled or aborted, else the summary's exit code.
     */
    static int run(@NonNull String name, @NonNull List<Job> jobs, int workers) throws IOException {
        String snap = Snapshot.latest();
        Path dir = Runs.RUNS.resolve(name);
        Files.createDirectories(dir.resolve("log"));
        writeRunJson(dir, name, jobs, workers, snap);
        Job first = jobs.get(0);
        String players = "A=" + first.a() + " vs B=" + first.b();
        String counts = jobs.size() + " games | " + workers + " workers";
        System.out.println(
                "aisim " + name + ": " + players + " | " + first.config() + " | " + counts + " | snapshot " + snap);
        Parent parent = new Parent(dir, snap, jobs, workers, heap(first.vs(), first.size()));
        boolean completed = parent.run();
        int status = Report.summary(name);
        return completed ? status : 2;
    }

    /**
     * Writes the run's run.json. {@code workers} is the requested count, and {@code jobs} is what a replay later
     * forwards to the run's own (possibly older) snapshot.
     */
    private static void writeRunJson(@NonNull Path dir, @NonNull String name, @NonNull List<Job> jobs, int workers,
            @NonNull String snap) throws IOException {
        Job first = jobs.get(0);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("v", 1);
        meta.put("name", name);
        meta.put("snap", snap);
        meta.put("java", System.getProperty("java.version"));
        meta.put("created", Instant.now().toString());
        meta.put("workers", workers);
        meta.put("a", first.a());
        meta.put("b", first.b());
        meta.put("aPool", poolSha(first.a()));
        meta.put("bPool", poolSha(first.b()));
        meta.put("config", first.config());
        meta.put("expected", jobs.size());
        meta.put("jobs", jobs);
        Aisim.JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("run.json").toFile(), meta);
    }

    /** The frozen AI's jar hash for run.json, or null for a live AI. */
    private static @Nullable String poolSha(@NonNull String spec) {
        String name = AiSpec.nameOf(spec);
        return name.startsWith("@") ? Pool.of(name.substring(1)).sha() : null;
    }

    /**
     * One run in progress: one driver thread per worker slot takes jobs from the shared queue, and each row is
     * appended to results.jsonl as it arrives.
     */
    private static final class Parent {
        // configuration
        private final @NonNull Path dir;
        private final @NonNull String snap;
        private final @NonNull String heap;
        private final int expected;
        private final int workers;
        private final long start_nanos = System.nanoTime();

        // shared by the driver threads
        private final @NonNull Queue<Job> queue;
        /** Set by cancel() and at the end of run(). */
        private final @NonNull AtomicBoolean stop = new AtomicBoolean();
        /**
         * Every worker JVM started, guarded by itself so cancel() can kill them; stop is checked under the same lock,
         * so no worker starts after a cancel.
         */
        private final @NonNull List<Worker> live = new ArrayList<>();
        /** Why the run was aborted; the first reason wins. */
        private final @NonNull AtomicReference<String> abort = new AtomicReference<>();

        // guarded by this (record)
        private int done;
        private int failed;
        private int errors;
        private int wins;
        private int losses;
        private int draws;

        Parent(@NonNull Path dir, @NonNull String snap, @NonNull List<Job> jobs, int workers, @NonNull String heap) {
            this.dir = dir;
            this.snap = snap;
            this.heap = heap;
            this.expected = jobs.size();
            this.workers = Math.min(workers, jobs.size());
            this.queue = new ConcurrentLinkedQueue<>(jobs);
        }

        /** Plays every job; false if the run was cancelled (STOP file, Ctrl+C) or aborted. */
        boolean run() throws IOException {
            Thread hook = new Thread(this::cancel, "aisim-cancel");
            Runtime.getRuntime().addShutdownHook(hook); // Ctrl+C: kill the workers at once
            startDaemon("aisim-stop-watcher", this::watchStopFile);
            playAll();
            boolean cancelled = stop.getAndSet(true); // setting it also ends the watcher
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // the JVM is already shutting down (Ctrl+C): the hook runs anyway
            }
            String reason = abort.get();
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
        private void playAll() throws IOException {
            try (Writer results = Files.newBufferedWriter(dir.resolve("results.jsonl"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                List<Thread> threads = new ArrayList<>();
                for (int i = 0; i < workers; i++) {
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

        /** Plays queued jobs on worker slot {@code index}, starting a fresh JVM whenever the last one was retired. */
        private void drive(int index, @NonNull Writer results) {
            Worker worker = null;
            try {
                Job job;
                while (!stop.get() && (job = queue.poll()) != null) {
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
                    worker.games++;
                    if (!endedNormally(row) || worker.games >= GAMES_PER_WORKER) {
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

        /** Starts a worker JVM for slot {@code index}; null if the run was cancelled. */
        private @Nullable Worker start(int index) throws IOException {
            synchronized (live) {
                if (stop.get()) {
                    return null;
                }
                // LWJGL unpacks its native libraries into this folder as the JVM starts: JVMs starting at the same
                // time must not share one, and Windows keeps the files of a running JVM locked
                Path natives = dir.resolve("n").resolve("w" + index);
                Worker worker = new Worker(workerLog(index), natives, snap, heap);
                live.add(worker);
                return worker;
            }
        }

        /** The log of slot {@code index}'s worker JVMs. */
        private @NonNull Path workerLog(int index) {
            return dir.resolve("log").resolve("w" + index + ".log");
        }

        /** The row of a game whose worker JVM died without answering. */
        private @NonNull Map<String, Object> deadWorkerRow(@NonNull Job job, @NonNull Worker worker, int index) {
            String problem = "worker exited with " + worker.exitCode() + " (see " + Aisim.slash(workerLog(index)) + ")";
            return Match.errorRow(job, snap, End.error, problem, 0);
        }

        /** Appends a game's row to results.jsonl, counts it and prints the progress line. */
        private synchronized void record(@NonNull Map<String, Object> row, @NonNull Writer results) throws IOException {
            results.write(Aisim.JSON.writeValueAsString(row) + "\n");
            results.flush();
            done++;
            Object winner = row.get("winner");
            if (winner == null) {
                countFailure(row);
            } else {
                switch (Runs.resultOfA(winner)) {
                    case "win" -> wins++;
                    case "loss" -> losses++;
                    default -> draws++;
                }
            }
            printProgress(row, winner);
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

        /** Prints the progress line after {@code row} (under record's lock). */
        private void printProgress(@NonNull Map<String, Object> row, @Nullable Object winner) {
            double minutes = (System.nanoTime() - start_nanos) / 60e9;
            double rate = done / minutes;
            double eta = (expected - done) / Math.max(rate, 1e-9);
            double game_minutes = Runs.num(row, "t") / 60;
            String kd30 = formatOrDash(row.get("kd30"), "%+.0f");
            String margin = formatOrDash(row.get("margin"), "%+.2f");
            String result = winner == null ? "-" : Runs.resultOfA(winner);
            System.out.printf(Locale.ROOT,
                    "[%3d/%d] %-9s %-4s %-7s %5.1fm kd30 %5s margin %6s | A %d-%d-%d | fail %d | %.1f games/min eta %.0fm%n",
                    done, expected, row.get("key"), result, row.get("end"), game_minutes, kd30, margin, wins, losses,
                    draws, failed, rate, eta);
        }

        /** {@code value} formatted with {@code format}, or "-" if it is not a number. */
        private static @NonNull String formatOrDash(@Nullable Object value, @NonNull String format) {
            return value instanceof Number n ? String.format(Locale.ROOT, format, n.doubleValue()) : "-";
        }

        /** Aborts the run for {@code reason}, unless it was already aborted for another. */
        private void abort(@NonNull String reason) {
            abort.compareAndSet(null, reason);
            cancel();
        }

        /** Stops handing out jobs and kills every worker JVM (STOP file, Ctrl+C, abort). */
        private void cancel() {
            stop.set(true);
            synchronized (live) {
                for (Worker worker : live) {
                    worker.kill();
                }
            }
        }
    }

    /** Whether the game of {@code row} played to its end, so its worker JVM can play another. */
    static boolean endedNormally(@NonNull Map<String, Object> row) {
        End end = End.of(row.get("end"));
        return end != null && end.normal();
    }

    // ---------------------------------------------------------------- worker JVMs

    /**
     * Worker heap. A 1v1 game keeps about 60 MB live even at the unit cap; a larger heap only fills with garbage
     * between collections (memory taken from the other workers), and garbage collection is under 1% of the time.
     * Games of 1 vs 3 and more, and games on huge maps, get more.
     */
    static @NonNull String heap(int vs, int size) {
        return vs >= 3 || Job.SIZES[size].equals("huge") ? "512m" : "256m";
    }

    /**
     * A worker JVM's command line: the options every worker shares (aisim.sh gives the parent the same, keep them in
     * sync), then {@code AISIM_JAVA_OPTS}, split at spaces (so an option cannot contain one), then the snapshot's
     * class path.
     */
    private static @NonNull List<String> workerCommand(@NonNull Path natives, @NonNull String snap,
            @NonNull String heap) {
        List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), "-ea",
                "--enable-native-access=ALL-UNNAMED", "-Xmx" + heap, "-XX:+UseSerialGC", "-Djava.awt.headless=true"));
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
            command.add("-XstartOnFirstThread"); // GLFW must own the first thread on macOS
        }
        command.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + natives.toAbsolutePath());
        String extra = System.getenv("AISIM_JAVA_OPTS");
        if (extra != null && !extra.isBlank()) {
            command.addAll(List.of(extra.trim().split("\\s+")));
        }
        command.addAll(List.of("@" + Snapshot.cpArgs(snap), Aisim.class.getName(), "worker", snap));
        return command;
    }

    /** One worker JVM, started from snapshot {@code snap}. */
    static final class Worker {
        private final @NonNull Process process;
        private final @NonNull BufferedReader stdout;
        private final @NonNull Writer stdin;
        /** Games this JVM has played. */
        int games;

        /** Starts a worker JVM; its stderr is appended to {@code log}. */
        Worker(@NonNull Path log, @NonNull Path natives, @NonNull String snap,
                @NonNull String heap) throws IOException {
            List<String> command = workerCommand(natives, snap, heap);
            Files.createDirectories(log.getParent());
            process = new ProcessBuilder(command).redirectError(Redirect.appendTo(log.toFile())).start();
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        }

        /** Plays one job (a {@link Job} or its JSON); null if the worker died without answering. */
        @Nullable
        Map<String, Object> play(@NonNull Object job) {
            try {
                stdin.write(Aisim.JSON.writeValueAsString(job) + "\n");
                stdin.flush();
                String line;
                while ((line = stdout.readLine()) != null) {
                    // other lines are the JVM's own messages, which bypass the System.out that WorkerMain redirects
                    if (line.startsWith(ROW_PREFIX)) {
                        return Aisim.JSON.readValue(line.substring(ROW_PREFIX.length()), ROW);
                    }
                }
            } catch (IOException e) {
                // the worker is gone
            }
            return null;
        }

        /** The exit code of this dead JVM; waits up to 10 s for it to exit, and returns -1 if it has not. */
        int exitCode() {
            try {
                return process.waitFor(10, TimeUnit.SECONDS) ? process.exitValue() : -1;
            } catch (InterruptedException e) {
                return -1;
            }
        }

        /** Ends the worker: it exits at the end of its input; one that does not within 60 s is killed. */
        void close() {
            try {
                stdin.close();
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (IOException | InterruptedException e) {
                process.destroyForcibly();
            }
        }

        void kill() {
            process.destroyForcibly();
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
