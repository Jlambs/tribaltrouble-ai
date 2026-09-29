package com.oddlabs.tt.aisim.analysis;

import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.UsageException;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code profile RUN | FILE.jfr}: where the CPU of profiled games went, from the Flight Recorder files that
 * {@code --profile} writes (one per worker JVM in the run's {@code prof/}, or one per replayed game). It reads the
 * simulation thread's samples: the share of the engine, of each AI, of map generation and of the recorder; the
 * methods by inclusive share (the method and everything it calls) and own share (its own code); and, with
 * {@code --callers}, the call paths into some methods. Besides, the time the JIT compiled and the GC paused.
 *
 * <p>A profile says where time may go, not what a change saves: attribution is approximate, and small methods can look
 * bigger than they are. Measure a change with compare's cpu line, on two runs played at the same time.
 */
public final class Profile {
    /** The thread that plays the games in a worker JVM. */
    private static final String SIMULATION_THREAD = "main";
    private static final String ODDLABS = "com.oddlabs.";
    private static final String AI_PACKAGE = "tt.player.";

    private Profile() {
    }

    /** What one or more recordings hold, summed. */
    private static final class Samples {
        int samples;
        final Map<String, Integer> categories = new LinkedHashMap<>();
        final Map<String, Integer> inclusive = new HashMap<>();
        final Map<String, Integer> own = new HashMap<>();
        final Map<String, Integer> callers = new HashMap<>();
        Duration compiling = Duration.ZERO;
        Duration gc = Duration.ZERO;
        int recordings;
    }

    /**
     * Prints the profile of {@code target} (a run or a .jfr file): the {@code top} methods, only those whose name
     * contains {@code focus} when given, and the callers of the methods whose name contains {@code callers}.
     */
    public static int run(@NonNull String target, @Nullable String focus, @Nullable String callers,
            int top) throws IOException {
        List<Path> files = recordings(target);
        Samples all = new Samples();
        for (Path file : files) {
            read(file, callers, all);
        }
        if (all.samples == 0) {
            throw new UsageException(
                    "no samples of the simulation thread in " + files.size() + " recording(s) of " + target + " (a worker killed by STOP or Ctrl+C writes none)");
        }
        String games = target.endsWith(".jfr") ? "" : gamesLine(target);
        System.out.printf(Locale.ROOT, "profile %s: %d recording(s)%s, %d samples of the simulation thread%n", target,
                all.recordings, games, all.samples);
        System.out.println("where the simulation's CPU went:");
        all.categories.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).forEach(
                e -> System.out.printf(Locale.ROOT, "  %-18s %5.1f%%%n", e.getKey(),
                        100.0 * e.getValue() / all.samples));
        System.out.printf(Locale.ROOT, "besides, on other threads: JIT compiling %.1f s, GC pauses %.1f s%n",
                seconds(all.compiling), seconds(all.gc));
        String title = focus == null ? "methods" : "methods matching '" + focus + "'";
        System.out.println(title + " by inclusive share (incl: with what it calls; own: its own code):");
        System.out.println("   incl    own  method");
        all.inclusive.entrySet().stream().filter(e -> focus == null ? interesting(e.getKey()) : e.getKey().contains(
                focus)).sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(
                        Map.Entry.comparingByKey())).limit(top).forEach(e -> System.out.printf(Locale.ROOT,
                                "  %5.1f%% %5.1f%%  %s%n", 100.0 * e.getValue() / all.samples,
                                100.0 * all.own.getOrDefault(e.getKey(), 0) / all.samples, e.getKey()));
        if (callers != null) {
            System.out.println("call paths into methods matching '" + callers + "' (share of all samples):");
            all.callers.entrySet().stream().sorted(
                    Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(
                            Map.Entry.comparingByKey())).limit(top).forEach(e -> System.out.printf(Locale.ROOT,
                                    "  %5.1f%%  %s%n", 100.0 * e.getValue() / all.samples,
                                    e.getKey()));
        }
        System.out.println("""
                (a profile says where time may go; measure a change with compare's cpu line on runs played at the same \
                time)""");
        return 0;
    }

    /** The recordings of a run (its prof/ folder) or the one file given. */
    private static @NonNull List<Path> recordings(@NonNull String target) throws IOException {
        if (target.endsWith(".jfr")) {
            Path file = Path.of(target);
            if (!Files.isRegularFile(file)) {
                throw new UsageException("no recording " + target);
            }
            return List.of(file);
        }
        Path prof = Runs.dir(target).resolve("prof");
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(prof)) {
            try (Stream<Path> list = Files.list(prof)) {
                list.filter(p -> p.toString().endsWith(".jfr")).sorted().forEach(files::add);
            }
        }
        if (files.isEmpty()) {
            throw new UsageException(
                    "run " + target + " has no profile: play it with --profile (recordings are " + "written when a worker exits, so wait for the run to end)");
        }
        return files;
    }

    /** ", N games, S s CPU": the games of the run and their simulation CPU. */
    private static @NonNull String gamesLine(@NonNull String run) throws IOException {
        List<Map<String, Object>> rows = Runs.rows(run);
        double cpu = rows.stream().mapToDouble(r -> Game.num(r, "cpu")).filter(Double::isFinite).sum();
        return String.format(Locale.ROOT, ", %d games, %.0f s of simulation CPU", rows.size(), cpu);
    }

    /** Adds one recording's simulation samples, compile time and GC pauses to {@code into}. */
    private static void read(@NonNull Path file, @Nullable String callers, @NonNull Samples into) throws IOException {
        Duration compiling = Duration.ZERO;
        try (RecordingFile recording = new RecordingFile(file)) {
            into.recordings++;
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                switch (event.getEventType().getName()) {
                    case "jdk.ExecutionSample" -> sample(event, callers, into);
                    case "jdk.CompilerStatistics" -> {
                        Duration total = event.getDuration("totalTimeSpent");
                        compiling = total.compareTo(compiling) > 0 ? total : compiling; // cumulative in the JVM
                    }
                    case "jdk.GarbageCollection" -> into.gc = into.gc.plus(event.getDuration("sumOfPauses"));
                    default -> {
                    }
                }
            }
        } catch (IOException e) {
            throw new IOException("cannot read " + Aisim.slash(file) + " (still being written?): " + e.getMessage(), e);
        }
        into.compiling = into.compiling.plus(compiling);
    }

    /** Counts one sample: its category, and each method on its stack once (inclusive) and the top one (own). */
    private static void sample(@NonNull RecordedEvent event, @Nullable String callers, @NonNull Samples into) {
        RecordedThread thread = event.getThread("sampledThread");
        RecordedStackTrace stack = event.getStackTrace();
        if (thread == null || stack == null || !SIMULATION_THREAD.equals(thread.getJavaName())) {
            return;
        }
        List<RecordedFrame> frames = stack.getFrames();
        if (frames.isEmpty()) {
            return;
        }
        into.samples++;
        into.categories.merge(category(frames), 1, Integer::sum);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < frames.size(); i++) {
            String name = name(frames.get(i).getMethod());
            if (i == 0) {
                into.own.merge(name, 1, Integer::sum);
            }
            if (seen.add(name)) {
                into.inclusive.merge(name, 1, Integer::sum);
            }
            if (callers != null && name.contains(callers) && (i == 0 || !name(frames.get(i - 1).getMethod()).contains(
                    callers))) {
                StringBuilder path = new StringBuilder(name);
                for (int j = i + 1, n = 0; j < frames.size() && n < 3; j++) {
                    String caller = name(frames.get(j).getMethod());
                    if (interesting(caller)) {
                        path.append(" < ").append(caller);
                        n++;
                    }
                }
                into.callers.merge(path.toString(), 1, Integer::sum);
            }
        }
    }

    /**
     * What a sample was busy with: an AI (the outermost AI frame, so the engine calls an AI makes count as the AI's),
     * map generation, the recorder, the engine (the world's tick), or boot and the harness.
     */
    private static @NonNull String category(@NonNull List<RecordedFrame> frames) {
        boolean engine = false;
        boolean map = false;
        boolean recorder = false;
        for (int i = frames.size() - 1; i >= 0; i--) {
            String name = name(frames.get(i).getMethod());
            String ai = aiOf(name);
            if (ai != null) {
                return "AI " + ai;
            }
            recorder |= name.startsWith("tt.aikit.harness.");
            map |= name.startsWith("tt.procedural.") || name.startsWith("tt.aisim.play.ClientWorld.create");
            engine |= name.startsWith("tt.landscape.World.tick");
        }
        if (recorder) {
            return "recorder";
        }
        if (map) {
            return "map generation";
        }
        return engine ? "engine" : "boot and harness";
    }

    /**
     * The AI a method belongs to: the package of an AI under tt.player (such as gauntlet), "stock" for the game's own
     * (AdvancedAI, PassiveAI and the campaign's); null for anything else, the engine's Player and chieftain AIs among
     * it.
     */
    private static @Nullable String aiOf(@NonNull String name) {
        if (!name.startsWith(AI_PACKAGE)) {
            return null;
        }
        String rest = name.substring(AI_PACKAGE.length());
        int dot = rest.indexOf('.');
        if (dot > 0 && Character.isLowerCase(rest.charAt(0)) && rest.indexOf('.', dot + 1) > 0) {
            String ai = rest.substring(0, dot);
            return ai.equals("campaign") ? "stock" : ai;
        }
        return rest.startsWith("AdvancedAI.") || rest.startsWith("PassiveAI.") ? "stock" : null;
    }

    /** A method as Class.method, without the com.oddlabs. prefix. */
    private static @NonNull String name(@NonNull RecordedMethod method) {
        String type = method.getType().getName();
        if (type.startsWith(ODDLABS)) {
            type = type.substring(ODDLABS.length());
        }
        return type + "." + method.getName();
    }

    /** Whether a method is worth a line: not the JDK's plumbing, lambdas or the harness around the game. */
    private static boolean interesting(@NonNull String name) {
        return !name.startsWith("java.") && !name.startsWith("jdk.") && !name.startsWith("sun.")
                && !name.contains("$$Lambda") && !name.startsWith("tt.aisim.");
    }

    private static double seconds(@NonNull Duration duration) {
        return duration.toNanos() / 1e9;
    }
}
