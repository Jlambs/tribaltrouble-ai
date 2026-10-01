package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.aikit.harness.AiSpec;
import com.oddlabs.util.Color;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The AI log panel: the last lines (25, under a title; fewer when the window has less room) that the followed
 * player's AI wrote at or before the moment on view. They come from the AI log book, which keeps each tick's lines of
 * the first pass, so a rewind shows them again exactly as they were written. {@code flee of} lines (most of the
 * gauntlet AI's log: one per fleeing peon) are hidden until Shift+L shows them; the wheel over the panel goes back to
 * older lines. A stock AI writes no log, and the panel says so instead.
 */
final class AiLogPanel extends TextPanel {
    /** The most lines: the title and 25 lines of the log. */
    static final int MAX_LINES = 26;
    /** The fewest lines the layout gives the panel, however small the window. */
    static final int MIN_LINES = 4;
    /** Lines the wheel moves per notch. */
    private static final int WHEEL_LINES = 3;
    private static final String FLEE = "flee of ";

    private final @NonNull WatchGame game;
    private boolean show_flee;
    /** How many shown lines the view is scrolled back from the newest; 0 follows the game. */
    private int scroll;
    private int scroll_slot = -1;
    private int max_lines = MAX_LINES;

    AiLogPanel(@NonNull WatchGame game) {
        super(360, 760);
        this.game = game;
        setBackground(new Vector4f(0f, 0f, 0f, .7f));
    }

    /** The most lines the panel may show (the layout's room for it), header included. */
    void setMaxLines(int lines) {
        max_lines = Math.clamp(lines, MIN_LINES, MAX_LINES);
    }

    int maxLines() {
        return max_lines;
    }

    boolean fleeShown() {
        return show_flee;
    }

    void toggleFlee() {
        show_flee = !show_flee;
        scroll = 0;
    }

    /** Rebuilds the lines for the followed player and the moment on view. */
    void refresh() {
        setLines(lines());
    }

    private @NonNull List<Line> lines() {
        int slot = game.followedSlot();
        if (slot != scroll_slot) {
            scroll = 0;
            scroll_slot = slot;
        }
        AiLogBook book = game.aiLog();
        int tick = game.clock().tick();
        String spec = game.job().spec(slot);
        List<Line> lines = new ArrayList<>();
        Vector4fc swatch = game.world().getPlayers()[slot].getColor();
        String title = "AI log  s" + slot + " " + spec + "  team " + (char) ('A' + game.job().team(slot));
        if (book.count(slot) == 0) {
            lines.add(new Line(title, Color.WHITE, swatch));
            String why = AiSpec.isStock(AiSpec.nameOf(
                    spec)) ? "the stock AI (" + spec + ") writes no log: follow another player with ] [ or a scoreboard row" : "no lines yet: this AI has written nothing so far";
            lines.add(new Line(why, TextPanel.DIM));
            return lines;
        }
        List<Line> notes = new ArrayList<>();
        if (tick > book.frontier()) {
            notes.add(new Line("the log has lines only to " + Times.clockTicks(book.frontier()), TextPanel.WARN));
        }
        int room = max_lines - 1 - notes.size(); // under the title line
        // the newest lines first, as many as the scroll skips and the room shows
        List<Line> newest = new ArrayList<>();
        for (int index = book.countUpTo(slot, tick) - 1; index >= 0 && newest.size() < scroll + room; index--) {
            Parsed parsed = parse(book.line(slot, index));
            if (!show_flee && parsed.text().startsWith(FLEE)) {
                continue;
            }
            int line_tick = book.tick(slot, index);
            newest.add(new Line(time(line_tick) + "  " + parsed.topic() + "  " + parsed.text(), colorOf(parsed,
                    line_tick, tick)));
        }
        scroll = Math.clamp(newest.size() - room, 0, scroll); // never further back than a full panel of the oldest
        List<Line> body = new ArrayList<>(newest.subList(scroll, Math.min(newest.size(), scroll + room)));
        if (body.isEmpty()) {
            String hidden = show_flee ? "" : " (flee of lines hidden)";
            notes.add(new Line("no lines up to " + Times.clockTicks(tick) + " yet" + hidden, TextPanel.DIM));
        }
        String flee = show_flee ? "flee of: shown (Shift+L)" : "flee of: hidden (Shift+L)";
        lines.add(new Line(title + "    " + flee + (scroll > 0 ? "    " + scroll + " lines back (wheel)" : ""),
                Color.WHITE, swatch));
        Collections.reverse(body);
        lines.addAll(body);
        lines.addAll(notes);
        return lines;
    }

    /**
     * One AI log line split up: its topic and its text. The line is {@code "%8.2f s%d %-5s %s"} (AiLog), its time is
     * the book's tick.
     */
    record Parsed(@NonNull String topic, @NonNull String text) {
    }

    static @NonNull Parsed parse(@NonNull String line) {
        String rest = line.stripLeading();
        for (int field = 0; field < 2; field++) { // the seconds, then the slot
            int space = rest.indexOf(' ');
            rest = space < 0 ? "" : rest.substring(space + 1);
        }
        int space = rest.indexOf(' ');
        if (space < 0) {
            return new Parsed(rest, "");
        }
        return new Parsed(rest.substring(0, space), rest.substring(space + 1).stripLeading());
    }

    /** {@code tick} as m:ss.hh, the log's own precision. */
    private static @NonNull String time(int tick) {
        int hundredths = tick % GameTime.TICKS_PER_SECOND * 100 / GameTime.TICKS_PER_SECOND;
        return Times.clock(tick) + String.format(Locale.ROOT, ".%02d", hundredths);
    }

    /** Recent lines (of the last game second) stand out; statistics and errors are dim and red. */
    private static @NonNull Vector4fc colorOf(@NonNull Parsed parsed, int line_tick, int now) {
        String topic = parsed.topic().toUpperCase(Locale.ROOT);
        if (topic.startsWith("ERR") || topic.equals("FAIL")) {
            return TextPanel.BAD;
        }
        if (line_tick > now - GameTime.TICKS_PER_SECOND) {
            return TextPanel.WARN;
        }
        return topic.equals("STAT") ? TextPanel.DIM : Color.WHITE;
    }

    /** The wheel: up goes back to older lines, down towards the newest. */
    @Override
    protected void mouseScrolled(int amount) {
        if (amount != 0) {
            scroll = Math.max(0, scroll + amount * WHEEL_LINES);
            game.hudRefreshSoon();
        }
    }
}
