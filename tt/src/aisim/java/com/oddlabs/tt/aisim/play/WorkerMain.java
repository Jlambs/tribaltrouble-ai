package com.oddlabs.tt.aisim.play;

import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.analysis.End;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The inside of a worker JVM ({@link WorkerProcess}): boots the engine once, then plays one {@link Job} per stdin
 * line and answers each with exactly one {@code @@} row line on the original stdout. Everything else it prints goes to
 * stderr, the worker log. A watchdog thread ends a game that hangs and the JVM once its parent is gone.
 *
 * <p>Exit codes: 0 at the end of its input, 3 after a game that did not end normally (its static engine state may be
 * half updated, so the parent starts a fresh JVM), 3 on a hang and 4 when the parent died.
 */
public final class WorkerMain {
    /**
     * A game is hung when the simulation does not advance a single tick in this much CPU or wall time. Set them with
     * -Daisim.hangCpu and -Daisim.hangWall in AISIM_JAVA_OPTS.
     */
    private static final long HANG_CPU_SECONDS = Long.getLong("aisim.hangCpu", 120);
    private static final long HANG_WALL_SECONDS = Long.getLong("aisim.hangWall", 600);
    /** How often the watchdog looks. */
    private static final long WATCH_MILLIS = 5000;

    /**
     * The game the simulation thread is playing. {@code answered} makes sure the parent gets exactly one row for it,
     * from the game or from the watchdog, whichever comes first.
     */
    private record CurrentGame(@NonNull Job job, @NonNull AtomicBoolean answered) {
    }

    private static volatile @Nullable CurrentGame current;

    private WorkerMain() {
    }

    public static void run(@NonNull String snap) throws IOException {
        PrintStream protocol = System.out; // rows are ASCII JSON, so its encoding does not matter
        System.setOut(System.err); // engine chatter goes to the worker log
        Logger.getLogger("").setLevel(Level.WARNING); // keeps the engine's INFO logging out of the worker log
        Match.boot();
        Thread simulation = Thread.currentThread();
        Batch.startDaemon("aisim-watchdog", () -> watchForHang(simulation, protocol, snap));
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            Job job = Aisim.JSON.readValue(line, Job.class);
            CurrentGame game = new CurrentGame(job, new AtomicBoolean());
            current = game;
            Map<String, Object> row;
            try {
                row = Match.run(job, snap);
            } catch (IOException | RuntimeException | Error e) {
                e.printStackTrace();
                row = Match.errorRow(job, snap, End.error, "harness: " + e, 0);
            }
            current = null;
            answer(protocol, game, row);
            if (!End.endedNormally(row)) {
                System.exit(3);
            }
        }
        System.exit(0);
    }

    /** Sends {@code row} to the parent, unless the game was already answered (by the game or by the watchdog). */
    private static void answer(@NonNull PrintStream protocol, @NonNull CurrentGame game,
            @NonNull Map<String, Object> row) throws IOException {
        if (game.answered().compareAndSet(false, true)) {
            protocol.println(WorkerProcess.ROW_PREFIX + Aisim.JSON.writeValueAsString(row));
            protocol.flush();
        }
    }

    /**
     * Reports a hang (no simulation tick for {@link #HANG_CPU_SECONDS} of CPU or {@link #HANG_WALL_SECONDS} of wall
     * time) with the stuck stack, and dies with the parent.
     */
    @SuppressWarnings("ReferenceEquality") // a new game is a new CurrentGame object
    private static void watchForHang(@NonNull Thread simulation, @NonNull PrintStream protocol, @NonNull String snap) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        CurrentGame watched = null;
        int tick = -1;
        long cpu_at_tick = 0;
        long wall_at_tick = 0;
        while (true) {
            Batch.sleep(WATCH_MILLIS);
            haltIfOrphaned();
            CurrentGame game = current;
            long cpu = threads.getThreadCpuTime(simulation.threadId());
            long wall = System.nanoTime();
            if (game == null || game != watched || Match.progress != tick) {
                // idle, a new game, or the game advanced: restart both clocks
                watched = game;
                tick = Match.progress;
                cpu_at_tick = cpu;
                wall_at_tick = wall;
                continue;
            }
            long stuck_cpu = (cpu - cpu_at_tick) / 1_000_000_000L;
            long stuck_wall = (wall - wall_at_tick) / 1_000_000_000L;
            if (stuck_cpu > HANG_CPU_SECONDS || stuck_wall > HANG_WALL_SECONDS) {
                StackTraceElement[] stack = simulation.getStackTrace();
                String stuck = stuck_cpu + " s CPU / " + stuck_wall + " s wall";
                String symptom = "no tick for " + stuck + " at game second " + tick / GameTime.TICKS_PER_SECOND;
                reportHang(protocol, snap, game, tick, symptom, stack);
                Runtime.getRuntime().halt(3);
            }
        }
    }

    /**
     * Writes the hung game's stack to its .err file and answers the parent with a hang row. A failure is only printed:
     * without a row, the parent records the worker's exit code instead.
     */
    private static void reportHang(@NonNull PrintStream protocol, @NonNull String snap, @NonNull CurrentGame game,
            int tick, @NonNull String symptom, StackTraceElement @NonNull [] stack) {
        StringBuilder text = new StringBuilder();
        for (StackTraceElement frame : stack) {
            text.append("\tat ").append(frame).append('\n');
        }
        try {
            Files.writeString(Path.of(game.job().errFile()), symptom + "\n" + text);
            double t = tick / (double) GameTime.TICKS_PER_SECOND;
            String problem = symptom + ", in " + topFrames(stack);
            answer(protocol, game, Match.errorRow(game.job(), snap, End.hang, problem, t));
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
        }
    }

    /** Halts the worker JVM with 4 once its parent is gone, since nobody would read its rows. */
    private static void haltIfOrphaned() {
        boolean parent_alive = ProcessHandle.current().parent().map(ProcessHandle::isAlive).orElse(false);
        if (!parent_alive) {
            Runtime.getRuntime().halt(4);
        }
    }

    /** The top frame of a stack and its caller, as "top < caller"; "?" for an empty stack. */
    private static @NonNull String topFrames(StackTraceElement @NonNull [] stack) {
        if (stack.length == 0) {
            return "?";
        }
        if (stack.length == 1) {
            return stack[0].toString();
        }
        return stack[0] + " < " + stack[1];
    }
}
