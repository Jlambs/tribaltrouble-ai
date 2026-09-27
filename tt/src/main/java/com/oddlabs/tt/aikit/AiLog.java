package com.oddlabs.tt.aikit;

import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decision log, named counters and swallowed-error count of one AI player, usable by any AI.
 *
 * <p>Get the handle once, in the AI's constructor, with {@link #of(Player) AiLog.of(getOwner())}. Counters and errors
 * are always recorded and end up in every harness result row, so "this ability never fired" or "this AI swallowed 98
 * exceptions" shows up in batch summaries without reading logs. Log lines are only written when a sink is installed:
 * by the aisim harness for {@code play} and {@code replay}, and by {@link PlayTest} in games started with
 * {@code ./aisim.sh gui}. Without a sink a log call costs one field check and never evaluates its {@link Supplier}.
 *
 * <p>Line format: {@code <seconds> s<slot> <TOPIC> <message>}, where seconds are {@link GameTime game seconds}, so
 * {@code awk '$1>=600 && $1<=900'} selects a time window and {@code grep ' ARMY '} a topic.
 *
 * <p>Logging must not change decisions; the harness verifies that by replaying games with logs on against the
 * logs-off batch checksums. Nothing here throws into the simulation or touches the world's random generator.
 *
 * <p>The static state refers to the world only weakly: a finished world must not stay reachable from here after the
 * player returns to the menu. In the game, {@link PlayTest#leave} also closes the handles when the world is closed;
 * the weak references cover every way of leaving a game that does not pass through it.
 */
public final class AiLog {
    private static final Logger logger = Logger.getLogger(AiLog.class.getName());
    /** Errors after this many are counted but not logged. */
    private static final int MAX_ERROR_LINES = 20;
    /** The first this many errors are logged with this many stack frames. */
    private static final int STACK_ERRORS = 3;
    private static final int STACK_FRAMES = 12;

    // The world the handles belong to. Compared with ==, never hashed, so no identity hash is computed here.
    private static @NonNull WeakReference<World> bound_world = new WeakReference<>(null);
    private static AiLog @NonNull [] handles = new AiLog[0];

    /** Weak for the same reason as bound_world: the handles are reachable from static state. */
    private final @NonNull WeakReference<World> world;
    private final int slot;
    private final @Nullable Path file;
    private final @NonNull String header;
    private @Nullable Writer sink;
    /** Set when the handle is closed or its file failed: it writes no more lines. */
    private boolean off;
    private int errors;
    private @Nullable String first_error;
    private final @NonNull SortedMap<String, Integer> counters = new TreeMap<>();

    private AiLog(@NonNull World world, int slot, @Nullable Path file, @NonNull String header) {
        this.world = new WeakReference<>(world);
        this.slot = slot;
        this.file = file;
        this.header = header;
    }

    // ---------------------------------------------------------------- for AIs

    /** The handle of {@code player}'s AI. Never null; counting-only unless a sink was installed for its world. */
    public static @NonNull AiLog of(@NonNull Player player) {
        World world = player.getWorld();
        if (world != bound_world.get()) {
            begin(world, null, "");
        }
        return handles[slotOf(world, player)];
    }

    public void log(@NonNull String topic, @NonNull String message) {
        if (on()) {
            write(topic, message);
        }
    }

    /** Logs a message that is only built when the log is on. */
    public void log(@NonNull String topic, @NonNull Supplier<String> message) {
        if (on()) {
            String text;
            try {
                text = message.get();
            } catch (RuntimeException | AssertionError | LinkageError e) {
                text = "LOG-FAILED " + e; // e.g. a getter asserting on a building that just died
            }
            write(topic, text);
        }
    }

    /** Counts one occurrence of {@code key}, e.g. an ability use or a decision. Always on. */
    public void count(@NonNull String key) {
        counters.merge(key, 1, Integer::sum);
    }

    /** Records an exception the AI caught and survived. Always counted; logged (first ones with a stack) if on. */
    public void error(@NonNull String where, @NonNull Throwable t) {
        errors++;
        if (first_error == null) {
            first_error = where + ": " + t;
        }
        if (on() && errors <= MAX_ERROR_LINES) {
            StringBuilder text = new StringBuilder("#" + errors + " " + where + ": " + t);
            if (errors <= STACK_ERRORS) {
                StackTraceElement[] stack = t.getStackTrace();
                for (int i = 0; i < Math.min(STACK_FRAMES, stack.length); i++) {
                    text.append(" | at ").append(stack[i]);
                }
            }
            write("ERROR", text.toString());
        }
    }

    // ---------------------------------------------------------------- for the harness and the recorder

    public int errors() {
        return errors;
    }

    public @Nullable String firstError() {
        return first_error;
    }

    public @NonNull SortedMap<String, Integer> counters() {
        return Collections.unmodifiableSortedMap(counters);
    }

    /**
     * Binds fresh handles to every player of {@code world}. {@code file_of_slot} gives each slot's log file, or is
     * null for counting-only handles; files are created on the first line. {@code header} goes on the first line of
     * each file. Closes the handles of the previous world.
     */
    public static synchronized void begin(@NonNull World world, @Nullable IntFunction<Path> file_of_slot,
            @NonNull String header) {
        end();
        bound_world = new WeakReference<>(world);
        Player[] players = world.getPlayers();
        handles = new AiLog[players.length];
        for (int i = 0; i < players.length; i++) {
            handles[i] = new AiLog(world, i, file_of_slot == null ? null : file_of_slot.apply(i), header);
        }
    }

    /** The handle of {@code slot} if handles are bound to {@code world}, else null. */
    public static synchronized @Nullable AiLog peek(@NonNull World world, int slot) {
        return world == bound_world.get() && slot < handles.length ? handles[slot] : null;
    }

    public static synchronized void flushAll() {
        for (AiLog handle : handles) {
            handle.flush();
        }
    }

    /** Flushes and closes all sinks and unbinds the world. */
    public static synchronized void end() {
        for (AiLog handle : handles) {
            handle.close();
        }
        handles = new AiLog[0];
        bound_world = new WeakReference<>(null);
    }

    /** {@link #end()} if the handles are bound to {@code world}; handles of any other world stay open. */
    static synchronized void endFor(@NonNull World world) {
        if (world == bound_world.get()) {
            end();
        }
    }

    /** The slot of {@code player} in its world. */
    static int slotOf(@NonNull World world, @NonNull Player player) {
        Player[] players = world.getPlayers();
        for (int i = 0; i < players.length; i++) {
            if (players[i] == player) {
                return i;
            }
        }
        throw new IllegalArgumentException("player is not part of its world");
    }

    // ---------------------------------------------------------------- the file

    private boolean on() {
        return file != null && !off;
    }

    private synchronized void write(@NonNull String topic, @Nullable String message) {
        Path path = file;
        // The AI calling this keeps its world alive, so the world is only gone for a handle kept past its game.
        World game = world.get();
        if (path == null || off || game == null) {
            return;
        }
        try {
            Writer out = sink;
            if (out == null) {
                out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                sink = out;
                out.write(headerLine(game));
            }
            String text = String.valueOf(message).replace('\n', ' ');
            out.write(String.format(Locale.ROOT, "%8.2f s%d %-5s %s\n", GameTime.seconds(game), slot, topic, text));
        } catch (IOException e) {
            disable("write", e);
        }
    }

    /** First line: format version, slot, player name and the caller's header. */
    private @NonNull String headerLine(@NonNull World game) {
        String name = game.getPlayers()[slot].getPlayerInfo().getName();
        return "# ai-log v1 slot=" + slot + " name=" + name + (header.isEmpty() ? "" : " " + header) + "\n";
    }

    private synchronized void flush() {
        Writer out = sink;
        if (out == null) {
            return;
        }
        try {
            out.flush();
        } catch (IOException e) {
            disable("flush", e);
        }
    }

    /** Closes for good: off is set first, so a stale handle never reopens (and truncates) its file. */
    private synchronized void close() {
        off = true;
        Writer out = sink;
        if (out == null) {
            return;
        }
        try {
            out.close();
            sink = null;
        } catch (IOException e) {
            disable("close", e);
        }
    }

    /** Stops logging after an I/O error and warns with {@code method} as the source method the log format prints. */
    private void disable(@NonNull String method, @NonNull IOException e) {
        off = true;
        logger.logp(Level.WARNING, AiLog.class.getName(), method, "AI log " + file + " disabled", e);
    }
}
