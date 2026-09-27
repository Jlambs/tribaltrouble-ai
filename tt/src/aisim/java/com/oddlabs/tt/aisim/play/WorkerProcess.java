package com.oddlabs.tt.aisim.play;

import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.build.Snapshot;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One worker JVM as its parent sees it: started from a snapshot, it plays one job per line written to its stdin
 * ({@link WorkerMain} is the inside) and answers each with one {@value #ROW_PREFIX} row line on its stdout. Its
 * stderr, the engine's chatter among it, is appended to a log file.
 */
final class WorkerProcess {
    /** Starts the one line per game on a worker's stdout that carries the game's row. */
    static final String ROW_PREFIX = "@@";

    private final @NonNull Process process;
    private final @NonNull BufferedReader stdout;
    private final @NonNull Writer stdin;
    /** Games this JVM has played. */
    int games;

    /**
     * Starts a worker JVM from snapshot {@code snap}, with a heap of {@code heap} and its stderr appended to
     * {@code log}. LWJGL unpacks its native libraries into {@code natives}: JVMs starting at the same time must not
     * share one, and Windows keeps the files of a running JVM locked.
     */
    WorkerProcess(@NonNull Path log, @NonNull Path natives, @NonNull String snap,
            @NonNull String heap) throws IOException {
        Files.createDirectories(log.getParent());
        ProcessBuilder builder = new ProcessBuilder(command(natives, snap, heap));
        builder.redirectError(Redirect.appendTo(log.toFile()));
        process = builder.start();
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
    }

    /**
     * The worker heap for a game. A 1v1 game keeps about 60 MB live even at the unit cap; a larger heap only fills with
     * garbage between collections (memory taken from the other workers), and garbage collection is under 1% of the
     * time. Games of 1 vs 3 and more, and games on huge maps, get more.
     */
    static @NonNull String heap(int vs, int size) {
        return vs >= 3 || Job.SIZES[size].equals("huge") ? "512m" : "256m";
    }

    /**
     * The command line: the options every worker shares (aisim.sh gives the parent the same, keep them in sync), then
     * {@code AISIM_JAVA_OPTS}, split at spaces (so an option cannot contain one), then the snapshot's class path.
     */
    private static @NonNull List<String> command(@NonNull Path natives, @NonNull String snap, @NonNull String heap) {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>(List.of(java, "-ea", "--enable-native-access=ALL-UNNAMED",
                "-Xmx" + heap, "-XX:+UseSerialGC", "-Djava.awt.headless=true"));
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
                    return Aisim.JSON.readValue(line.substring(ROW_PREFIX.length()), Aisim.JSON_OBJECT);
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
