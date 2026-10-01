package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.GameTime;
import org.jspecify.annotations.NonNull;

import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The AI log lines of a watched game, kept in memory by slot and indexed by tick: each slot's AiLog handle writes to
 * this book's writer of that slot ({@code AiLog.beginWriters}). A line counts only when the world plays its tick for
 * the first time: after a rewind the AIs write the same lines again, which are dropped, so every tick up to the
 * frontier has exactly the lines of the first pass. The writers are outside the world (a checkpoint shares them, never
 * copies them), and all calls come from the simulation thread.
 */
final class AiLogBook {
    /** One slot's lines in tick order. */
    private static final class Lines {
        int[] ticks = new int[256];
        final List<String> texts = new ArrayList<>();

        void add(int tick, @NonNull String text) {
            int n = texts.size();
            if (n == ticks.length) {
                ticks = Arrays.copyOf(ticks, n * 2);
            }
            ticks[n] = tick;
            texts.add(text);
        }
    }

    private final Lines[] lines;
    private final LineWriter[] writers;
    /** The highest tick the world played so far: lines of this tick or earlier are re-plays. -1 before the first. */
    private int frontier = -1;
    private int malformed;

    AiLogBook(int slots) {
        lines = new Lines[slots];
        writers = new LineWriter[slots];
        for (int slot = 0; slot < slots; slot++) {
            lines[slot] = new Lines();
            writers[slot] = new LineWriter(slot);
        }
    }

    /** The writer slot {@code slot}'s AiLog handle writes to. */
    @NonNull
    Writer writer(int slot) {
        return writers[slot];
    }

    /** Every slot's writer, for the rewinder's shared set. */
    @NonNull
    List<Writer> writers() {
        return List.of(writers);
    }

    /** The world played {@code tick} for the first time: from now on its lines are re-plays. */
    void frontier(int tick) {
        frontier = Math.max(frontier, tick);
    }

    /** The last tick whose lines are all here (the highest tick played so far), or -1 before the first. */
    int frontier() {
        return frontier;
    }

    int slots() {
        return lines.length;
    }

    /** How many lines slot {@code slot} wrote (0 for an AI that writes no log, such as the stock AIs). */
    int count(int slot) {
        return lines[slot].texts.size();
    }

    /** The tick of line {@code index} of slot {@code slot}. */
    int tick(int slot, int index) {
        return lines[slot].ticks[index];
    }

    /** Line {@code index} of slot {@code slot}, as the log file has it (seconds, slot, topic, text). */
    @NonNull
    String line(int slot, int index) {
        return lines[slot].texts.get(index);
    }

    /** The number of lines of slot {@code slot} at or before {@code tick}: the index after the last such line. */
    int countUpTo(int slot, int tick) {
        Lines slot_lines = lines[slot];
        int lo = 0;
        int hi = slot_lines.texts.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (slot_lines.ticks[mid] <= tick) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Lines that did not start with a time (none expected; for the view's diagnostics). */
    int malformed() {
        return malformed;
    }

    /** A complete line from slot {@code slot}'s writer; the header line ("# ai-log v1 ...") is not kept. */
    private void accept(int slot, @NonNull String line) {
        if (line.startsWith("#")) {
            return;
        }
        int tick = tickOf(line);
        if (tick < 0) {
            malformed++;
            return;
        }
        if (tick > frontier) {
            lines[slot].add(tick, line);
        }
    }

    /** The tick of a line that starts with its game seconds ({@code "%8.2f s%d ..."}), or -1. */
    static int tickOf(@NonNull String line) {
        int start = 0;
        while (start < line.length() && line.charAt(start) == ' ') {
            start++;
        }
        int end = line.indexOf(' ', start);
        if (end <= start) {
            return -1;
        }
        try {
            return (int) Math.round(Double.parseDouble(line.substring(start, end)) * GameTime.TICKS_PER_SECOND);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Collects one slot's characters into lines; AiLog writes each line (header or log line) with its newline. */
    private final class LineWriter extends Writer {
        private final int slot;
        private final StringBuilder pending = new StringBuilder();

        LineWriter(int slot) {
            this.slot = slot;
        }

        @Override
        public void write(char @NonNull [] buffer, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                char c = buffer[i];
                if (c == '\n') {
                    accept(slot, pending.toString());
                    pending.setLength(0);
                } else {
                    pending.append(c);
                }
            }
        }

        @Override
        public void write(@NonNull String text, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    accept(slot, pending.toString());
                    pending.setLength(0);
                } else {
                    pending.append(c);
                }
            }
        }

        @Override
        public void flush() {
        }

        /** AiLog closes its sinks when its handles end; the lines stay readable. */
        @Override
        public void close() {
        }
    }
}
