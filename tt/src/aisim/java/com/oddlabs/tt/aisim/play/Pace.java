package com.oddlabs.tt.aisim.play;

import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.aisim.UsageException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * How many worker JVMs a run keeps: a fixed count ({@code --workers N}), or by default as many as the machine has room
 * for. Then a pacing thread looks every {@value #TICK_MILLIS} ms at the machine's hardware threads, CPU load and
 * available memory, and at the other runs on it, and aims the run at its share:
 * <ul>
 * <li>every run registers itself in a folder of the temp directory ({@link #LANES}) with its live workers and the
 * threads they keep busy, so runs of any checkout or agent see each other;</li>
 * <li>the threads that neither aisim workers nor fixed runs use, less a {@linkplain #reserve reserve} for the rest of
 * the machine, are split evenly among the automatic runs (what does not divide evenly goes one thread each to the runs
 * that started first, so the shares add up to the free threads), and a run takes what another leaves unused;</li>
 * <li>a worker needs its {@linkplain WorkerProcess#footprint footprint} of available memory, above a margin;</li>
 * <li>{@code --cpus} and {@code --memory} cap it.</li>
 * </ul>
 * The target grows by at most a quarter of the machine per look, so runs starting together share rather than both grab
 * everything, and shrinks at once; a worker beyond it retires after its game, never during one. Workers run at a lower
 * priority than the rest of the machine ({@link WorkerProcess}), so the desktop stays responsive however many there
 * are.
 */
public final class Pace {
    /** The most workers a run may have. */
    public static final int MAX_WORKERS = 256;
    private static final long TICK_MILLIS = 5000;
    /** A registry entry older than this is from a run that died without cleaning up. */
    private static final long STALE_MILLIS = 60_000;
    /** Where runs register, one file per parent JVM (by pid). */
    private static final Path LANES = Path.of(System.getProperty("java.io.tmpdir"), "aisim-runs");
    private static final long MB = 1L << 20;
    private static final com.sun.management.OperatingSystemMXBean OS = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    /**
     * A run's worker limits as given: {@code fixed} workers, or null for automatic; at most {@code cpus} hardware
     * threads and {@code memory} bytes, each null for no cap.
     */
    public record Limits(@Nullable Integer fixed, @Nullable Integer cpus, @Nullable Long memory) {
        public static @NonNull Limits fixed(int workers) {
            return new Limits(workers, null, null);
        }

        /**
         * The limits of {@code --workers N|auto}, {@code --cpus N|P%} and {@code --memory SIZE|P%} (each null when
         * absent); SIZE is bytes or a number with k, m or g, P% a share of the machine.
         */
        public static @NonNull Limits parse(@Nullable String workers, @Nullable String cpus, @Nullable String memory) {
            Integer fixed = workers == null || workers.equals("auto") ? null : (int) number("workers", workers, 1,
                    MAX_WORKERS);
            Integer threads = null;
            if (cpus != null) {
                threads = cpus.endsWith("%") ? share("cpus", cpus, cores()) : (int) number("cpus", cpus, 1,
                        MAX_WORKERS);
            }
            Long bytes = null;
            if (memory != null) {
                bytes = memory.endsWith("%") ? (long) share("memory", memory, OS.getTotalMemorySize()) : size(memory);
            }
            return new Limits(fixed, threads, bytes);
        }

        /** As run.json keeps them: workers ("auto" or the count), cpus and memory (MB), null when not given. */
        @NonNull
        Map<String, Object> json() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("workers", fixed == null ? "auto" : fixed);
            json.put("cpus", cpus);
            json.put("memoryMb", memory == null ? null : memory / MB);
            return json;
        }

        private static long number(@NonNull String key, @NonNull String value, long min, long max) {
            long number;
            try {
                number = Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new UsageException("--" + key + " needs a number" + (key.equals(
                        "workers") ? " or auto" : "") + ", not '" + value + "'");
            }
            if (number < min || number > max) {
                throw new UsageException("--" + key + " must be " + min + ".." + max);
            }
            return number;
        }

        /** P% of {@code whole}, rounded up, at least 1. */
        private static int share(@NonNull String key, @NonNull String value, long whole) {
            long percent;
            try {
                percent = Long.parseLong(value.substring(0, value.length() - 1));
            } catch (NumberFormatException e) {
                percent = -1;
            }
            if (percent < 1 || percent > 100) {
                throw new UsageException("--" + key + " as a share must be 1%..100%, not '" + value + "'");
            }
            return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (whole * percent + 99) / 100));
        }

        private static long size(@NonNull String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            long unit = switch (lower.isEmpty() ? ' ' : lower.charAt(lower.length() - 1)) {
                case 'k' -> 1L << 10;
                case 'm' -> MB;
                case 'g' -> 1L << 30;
                default -> 1;
            };
            String digits = unit == 1 ? lower : lower.substring(0, lower.length() - 1);
            try {
                long bytes = Long.parseLong(digits) * unit;
                if (bytes >= 64 * MB) {
                    return bytes;
                }
            } catch (NumberFormatException e) {
                // below
            }
            throw new UsageException(
                    "--memory needs a size of 64m or more, such as 800m or 6g, or a share such as " + "50%, not '" + value + "'");
        }
    }

    private final @NonNull Limits limits;
    private final @NonNull String run;
    private final long footprint;
    private final int slots;
    private final @NonNull IntSupplier waiting;
    private final @NonNull Supplier<List<ProcessHandle>> workers;
    private final @NonNull AtomicInteger target = new AtomicInteger();
    private final @NonNull Path lane = LANES.resolve(ProcessHandle.current().pid() + ".json");
    /** When the run started (epoch ms), which ranks it among the automatic runs for the threads left over. */
    private final long started = System.currentTimeMillis();
    /** Per live worker process, its CPU time at the last look, for the threads it keeps busy. */
    private final @NonNull Map<Long, Long> cpu_seen = new HashMap<>();
    private long last_look = System.nanoTime();
    private double busy;
    private volatile boolean stopped;

    /**
     * The pace of run {@code run} with {@code limits}, whose workers take {@code footprint} bytes each, with
     * {@code jobs}
     * games; {@code waiting} counts the games not started yet and {@code workers} lists the live worker processes.
     */
    Pace(@NonNull Limits limits, @NonNull String run, long footprint, int jobs, @NonNull IntSupplier waiting,
            @NonNull Supplier<List<ProcessHandle>> workers) {
        this.limits = limits;
        this.run = run;
        this.footprint = footprint;
        this.waiting = waiting;
        this.workers = workers;
        int most = limits.fixed() != null ? limits.fixed() : limits.cpus() != null ? limits.cpus() : cores();
        if (limits.fixed() != null && limits.cpus() != null) {
            most = Math.min(most, limits.cpus());
        }
        if (limits.memory() != null) {
            most = (int) Math.min(most, Math.max(1, limits.memory() / footprint));
        }
        slots = Math.max(1, Math.min(Math.min(most, MAX_WORKERS), jobs));
    }

    /** The most workers the run can have: its worker slots. */
    int slots() {
        return slots;
    }

    /** How many workers the run should have now; slots from this index up retire after their game. */
    int target() {
        return target.get();
    }

    boolean automatic() {
        return limits.fixed() == null;
    }

    /**
     * Sets the first target, registers the run and starts the pacing thread, which keeps the registry entry current
     * and, for an automatic run, the target.
     */
    void start() {
        if (automatic()) {
            cpuLoad(); // primes the system's CPU load, whose first reading means nothing
            Batch.sleep(1000);
            target.set(aim(0));
            System.out.println("workers: " + target.get() + " to start, up to " + slots + " (" + machine() + ")");
        } else {
            target.set(slots);
        }
        register();
        Batch.startDaemon("aisim-pace", this::pace);
    }

    /** Stops pacing and takes the run out of the registry. */
    void stop() {
        stopped = true;
        try {
            Files.deleteIfExists(lane);
        } catch (IOException e) {
            // the next reader drops it once the process is gone
        }
    }

    /** The pacing loop: looks, sets the target, and says so when it changes. */
    private void pace() {
        while (!stopped) {
            Batch.sleep(TICK_MILLIS);
            if (stopped) {
                return;
            }
            if (!automatic()) {
                measureBusy();
                register();
                continue;
            }
            int before = target.get();
            int now = aim(before);
            target.set(now);
            register();
            if (now != before && waiting.getAsInt() > 0) { // at the end, workers only run out of games
                System.out.println("workers " + before + " -> " + now + " (" + machine() + ")");
            }
        }
    }

    /** The target after {@code current}: the run's share, within its caps, grown by at most a quarter machine. */
    private int aim(int current) {
        int cores = cores();
        measureBusy();
        Others others = others();
        // Threads busy with anything but aisim workers: the machine's load less every registered run's workers.
        double load = cpuLoad();
        double foreign = load < 0 ? 0 : Math.max(0, load * cores - busy - others.busy);
        int free = Math.max(0, (int) Math.floor(cores - foreign + .25) - others.fixed_workers - reserve(cores));
        // An even split, and what is left over one thread each to the runs that started first: every run claiming
        // the leftover itself made 4 runs keep 36 workers on 28 threads.
        int runs = others.automatic_runs + 1;
        int fair = free / runs;
        int left_over = free % runs;
        int others_due = fair * others.automatic_runs + left_over - (others.earlier_runs < left_over ? 1 : 0);
        int share = Math.max(1, free - Math.min(others.automatic_workers, others_due));
        int running = workers.get().size();
        long room = availableMemory() - Math.max(512 * MB, OS.getTotalMemorySize() / 20);
        int by_memory = running + (int) Math.max(0, room / footprint);
        int aim = Math.min(Math.min(share, by_memory), slots);
        aim = Math.min(aim, running + waiting.getAsInt()); // no more workers than games to play
        int step = Math.max(2, cores / 4);
        return Math.max(1, Math.min(aim, current + step));
    }

    /** The threads this run's workers kept busy since the last look. */
    private void measureBusy() {
        long now = System.nanoTime();
        double seconds = Math.max(1e-3, (now - last_look) / 1e9);
        last_look = now;
        long cpu = 0;
        Map<Long, Long> seen = new HashMap<>();
        for (ProcessHandle worker : workers.get()) {
            long total = worker.info().totalCpuDuration().map(Duration::toNanos).orElse(0L);
            cpu += Math.max(0, total - cpu_seen.getOrDefault(worker.pid(), 0L));
            seen.put(worker.pid(), total);
        }
        cpu_seen.clear();
        cpu_seen.putAll(seen);
        busy = cpu / 1e9 / seconds;
    }

    /**
     * Threads the automatic runs leave to the rest of the machine on top of what it uses at the moment, so the desktop
     * and builds have room at once: one in sixteen (1 of 28).
     */
    static int reserve(int cores) {
        return cores / 16;
    }

    /**
     * What the other registered runs use: workers of fixed runs, and automatic runs with their workers;
     * {@code earlier_runs} of the automatic runs started before this one.
     */
    private record Others(int fixed_workers, int automatic_runs, int automatic_workers, int earlier_runs,
                          double busy) {
    }

    private @NonNull Others others() {
        int fixed = 0;
        int runs = 0;
        int automatic = 0;
        int earlier = 0;
        double threads = 0;
        long my_pid = ProcessHandle.current().pid();
        long now = System.currentTimeMillis();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(LANES, "*.json")) {
            for (Path file : files) {
                if (file.equals(lane)) {
                    continue;
                }
                Map<String, Object> entry;
                try {
                    entry = Aisim.JSON.readValue(file.toFile(), Aisim.JSON_OBJECT);
                } catch (IOException e) {
                    continue; // being rewritten
                }
                long pid = ((Number) entry.getOrDefault("pid", -1L)).longValue();
                long updated = ((Number) entry.getOrDefault("updated", 0L)).longValue();
                boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                if (!alive || now - updated > STALE_MILLIS) {
                    Files.deleteIfExists(file);
                    continue;
                }
                int count = ((Number) entry.getOrDefault("workers", 0)).intValue();
                threads += ((Number) entry.getOrDefault("busy", 0.0)).doubleValue();
                if ("auto".equals(entry.get("mode"))) {
                    runs++;
                    automatic += count;
                    // an entry without a start (an older harness) counts as the earliest; the pid breaks ties
                    long other_started = ((Number) entry.getOrDefault("started", 0L)).longValue();
                    if (other_started < started || (other_started == started && pid < my_pid)) {
                        earlier++;
                    }
                } else {
                    fixed += count;
                }
            }
        } catch (IOException e) {
            // no registry yet: no other runs
        }
        return new Others(fixed, runs, automatic, earlier, threads);
    }

    /** Writes the run's registry entry: its mode, live workers and the threads they keep busy. */
    private void register() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("pid", ProcessHandle.current().pid());
        entry.put("run", run);
        entry.put("dir", Path.of("").toAbsolutePath().toString());
        entry.put("mode", automatic() ? "auto" : "fixed");
        entry.put("started", started);
        entry.put("workers", workers.get().size());
        entry.put("busy", Math.round(busy * 100) / 100.0);
        entry.put("updated", System.currentTimeMillis());
        try {
            Files.createDirectories(LANES);
            Path temp = LANES.resolve(lane.getFileName() + ".tmp");
            Aisim.JSON.writeValue(temp.toFile(), entry);
            try {
                Files.move(temp, lane, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, lane, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // the registry is advice: a run that cannot write it is only invisible to the others
        }
    }

    /** The machine as pacing sees it, for the workers lines. */
    private @NonNull String machine() {
        Others others = others();
        double load = cpuLoad();
        String line = String.format(Locale.ROOT, "%d threads, %.0f%% busy, %.1f GB available", cores(),
                load < 0 ? 0 : load * 100, availableMemory() / (double) (1L << 30));
        int runs = others.automatic_runs + (others.fixed_workers > 0 ? 1 : 0);
        if (runs > 0) {
            line += ", other runs " + (others.automatic_workers + others.fixed_workers) + " workers";
        }
        return line;
    }

    // ---------------------------------------------------------------- the machine

    /** The hardware threads this JVM may use (container limits included). */
    static int cores() {
        return Runtime.getRuntime().availableProcessors();
    }

    /** The machine's recent CPU load, 0..1 (container limits included), or negative when unknown. */
    private static double cpuLoad() {
        return OS.getCpuLoad();
    }

    /**
     * The memory a new process could take: on Linux MemAvailable (the JDK's free memory there leaves out the page
     * cache, which the kernel gives back), within a container's limit; elsewhere the JDK's free memory.
     */
    static long availableMemory() {
        long free = OS.getFreeMemorySize();
        Path meminfo = Path.of("/proc/meminfo");
        if (!Files.isReadable(meminfo)) {
            return free;
        }
        try {
            long total = -1;
            long available = -1;
            for (String line : Files.readAllLines(meminfo)) {
                if (line.startsWith("MemTotal:")) {
                    total = kilobytes(line) * 1024;
                } else if (line.startsWith("MemAvailable:")) {
                    available = kilobytes(line) * 1024;
                }
            }
            if (available < 0) {
                return free;
            }
            // in a container the JDK counts against its limit, which /proc/meminfo does not show
            return OS.getTotalMemorySize() < total ? Math.min(available, free) : available;
        } catch (IOException | NumberFormatException e) {
            return free;
        }
    }

    private static long kilobytes(@NonNull String meminfo_line) {
        return Long.parseLong(meminfo_line.replaceAll("[^0-9]", ""));
    }
}
