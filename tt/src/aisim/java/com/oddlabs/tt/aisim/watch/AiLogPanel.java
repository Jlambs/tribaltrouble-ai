package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.harness.AiSpec;
import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.util.Color;
import com.oddlabs.util.Quad;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The AI log panel: the last lines (25 rows under a title; fewer when the window has less room) that the followed
 * player's AI wrote at or before the moment on view. They come from the AI log book, which keeps each tick's lines of
 * the first pass, so a rewind shows them again exactly as they were written. Each line shows its time, its topic as a
 * tag and its text, in three columns that stay put as lines come and go. The tag says what kind of line it is: errors
 * red, the lines of the last {@value #RECENT_TICKS} ticks yellow, statistics and the topic most lines have grey, other
 * topics tan. A line too long for the panel goes on in indented rows under its text, {@value #MAX_ROWS} rows at most
 * (statistics and parameter dumps one). {@code flee of} lines (most of the gauntlet AI's log: one per fleeing peon)
 * are hidden until Shift+L shows them; the wheel over the panel goes back to older lines. A stock AI writes no log,
 * and the panel says so instead.
 */
final class AiLogPanel extends TextPanel {
    /** The most rows: the title and 25 rows of the log. */
    static final int MAX_LINES = 26;
    /** The fewest rows the layout gives the panel, however small the window. */
    static final int MIN_LINES = 4;
    /** The rows one log line takes at most: a longer line ends its last row in "...". */
    static final int MAX_ROWS = 3;
    /** Lines the wheel moves per notch. */
    private static final int WHEEL_LINES = 3;
    private static final String FLEE = "flee of ";
    /** Lines this many ticks old or newer stand out: a real second of play at 1x. */
    private static final int RECENT_TICKS = 50;
    /** The tag column is at least as wide as this topic: AiLog pads topics to five letters. */
    private static final String TAG_FLOOR = "SWARM";
    /**
     * The room after the time column and after the tag column (and between the title's player and team), and between
     * the title's parts.
     */
    private static final int COLUMN_SPACE = 8;
    private static final int TITLE_SPACE = 16;
    /** The tag of statistics and of the topic most lines have (grey), and of the other topics (a quiet tan). */
    private static final Vector4fc QUIET_TAG = Color.argb4v(0xFF_88_88_88);
    private static final Vector4fc TAG = Color.argb4v(0xFF_C8_B8_98);
    private static final String ELLIPSIS = "...";

    private final @NonNull WatchGame game;
    private boolean show_flee;
    /** How many shown log lines the view is scrolled back from the newest; 0 follows the game. */
    private int scroll;
    private int scroll_slot = -1;
    /**
     * The tag column's width: the widest topic the followed player's shown lines had so far. It only grows (until
     * another player is followed), so the text column does not move when a rare topic comes into view or leaves it.
     */
    private int tag_width;
    private int max_lines = MAX_LINES;

    AiLogPanel(@NonNull WatchGame game) {
        super(360, 760);
        this.game = game;
        setBackground(new Vector4f(0f, 0f, 0f, .7f));
    }

    /** The most rows the panel may show (the layout's room for it), title included. */
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

    /** A log line to show: its tick, its time as the time column shows it, and its parts. */
    private record Entry(int tick, @NonNull String time, @NonNull Parsed parsed) {
    }

    /**
     * The columns of this refresh: the times' width (they are right-aligned in it), the tags' width, the text's room,
     * and the topic most of the lines have.
     */
    private record Columns(int time, int tag, int text, @NonNull String common) {
        /** Where a line's text starts: the indent of its further rows. */
        int textX() {
            return time + COLUMN_SPACE + tag + COLUMN_SPACE;
        }
    }

    private @NonNull List<Line> lines() {
        int slot = game.followedSlot();
        if (slot != scroll_slot) {
            scroll = 0;
            scroll_slot = slot;
            tag_width = 0;
        }
        AiLogBook book = game.aiLog();
        int tick = game.clock().tick();
        String spec = game.job().spec(slot);
        Font font = Skin.getSkin().getEditFont();
        List<Line> lines = new ArrayList<>();
        Builder title = line().heading().swatch(game.world().getPlayers()[slot].getColor()).text("AI log").gap(
                WatchHud.HINT_GAP).text(game.playerName(slot)).gap(COLUMN_SPACE).text(
                        "team " + (char) ('A' + game.job().team(slot)), TextPanel.DIM);
        if (book.count(slot) == 0) {
            lines.add(title.build());
            String why = AiSpec.isStock(AiSpec.nameOf(
                    spec)) ? "the stock AI (" + spec + ") writes no log: follow another player with ] [ or a scoreboard row" : "no lines yet: this AI has written nothing so far";
            for (String row : wrap(font, why, getWidth() - 2 * PADDING, 2)) {
                lines.add(new Line(row, TextPanel.DIM));
            }
            return lines;
        }
        List<Line> notes = new ArrayList<>();
        if (tick > book.frontier()) {
            notes.add(line().icon(HudIcons.warning()).text("the log has lines only to " + game.times().precise(
                    book.frontier()), TextPanel.WARN).build());
        }
        int room = max_lines - 1 - notes.size(); // rows under the title
        // the newest lines first, as many as the scroll skips and the room shows (a row each at least)
        List<Entry> newest = new ArrayList<>();
        Map<String, Integer> topics = new LinkedHashMap<>();
        tag_width = Math.max(tag_width, font.getWidth(TAG_FLOOR));
        for (int index = book.countUpTo(slot, tick) - 1; index >= 0 && newest.size() < scroll + room; index--) {
            Parsed parsed = parse(book.line(slot, index));
            if (!show_flee && parsed.text().startsWith(FLEE)) {
                continue;
            }
            int line_tick = book.tick(slot, index);
            newest.add(new Entry(line_tick, game.times().hundredths(line_tick), parsed));
            tag_width = Math.max(tag_width, font.getWidth(parsed.topic()));
            topics.merge(parsed.topic(), 1, Integer::sum);
        }
        // the times are no longer than the moment on view's; the text has the room right of the times and the tags
        int time_width = timeWidth(font, game.times().hundredths(tick));
        Columns columns = new Columns(time_width, tag_width,
                getWidth() - 2 * PADDING - time_width - 2 * COLUMN_SPACE - tag_width, common(topics));
        if (newest.size() < scroll + room) {
            // the oldest line is in reach: never further back than a full panel of the oldest lines (one at least)
            int rows = 0;
            int oldest = newest.size();
            while (oldest > 0) {
                int more = rowCount(font, newest.get(oldest - 1), columns.text());
                if (rows + more > room) {
                    break;
                }
                rows += more;
                oldest--;
            }
            scroll = Math.min(scroll, Math.max(0, Math.min(oldest, newest.size() - 1)));
        }
        // the shown lines, newest first, each in its rows; the oldest of them takes what room is left
        List<List<Line>> shown = new ArrayList<>();
        int left = room;
        for (int i = scroll; i < newest.size() && left > 0; i++) {
            List<Line> rows = rows(font, newest.get(i), columns, Math.min(MAX_ROWS, left), tick);
            shown.add(rows);
            left -= rows.size();
        }
        if (shown.isEmpty()) {
            String hidden = show_flee ? "" : " (flee of lines hidden)";
            notes.add(new Line("no lines up to " + game.times().precise(tick) + " yet" + hidden, TextPanel.DIM));
        }
        title.gap(TITLE_SPACE).text(show_flee ? "flee of: shown" : "flee of: hidden", TextPanel.DIM).gap(
                WatchHud.HINT_GAP).text("Shift+L", TextPanel.WARN).text(show_flee ? "hides" : "shows", TextPanel.DIM);
        if (scroll > 0) {
            title.gap(TITLE_SPACE).text(scroll + " lines back", TextPanel.WARN).text("(wheel)", TextPanel.DIM);
        }
        lines.add(title.build());
        Collections.reverse(shown);
        for (List<Line> rows : shown) {
            lines.addAll(rows);
        }
        lines.addAll(notes);
        return lines;
    }

    /**
     * The width of the time column for times as long as {@code time}: each digit counted as the widest, so the column
     * does not move as the times change (only when they get a digit more).
     */
    private static int timeWidth(@NonNull Font font, @NonNull String time) {
        char widest = '0';
        for (char digit = '1'; digit <= '9'; digit++) {
            if (font.getWidth(String.valueOf(digit)) > font.getWidth(String.valueOf(widest))) {
                widest = digit;
            }
        }
        return font.getWidth(time.replaceAll("[0-9]", String.valueOf(widest)));
    }

    /** The topic most of {@code topics} (counts by topic, the newest line's first) have; the newest wins a tie. */
    private static @NonNull String common(@NonNull Map<String, Integer> topics) {
        String common = "";
        int most = 0;
        for (Map.Entry<String, Integer> topic : topics.entrySet()) {
            if (topic.getValue() > most) {
                most = topic.getValue();
                common = topic.getKey();
            }
        }
        return common;
    }

    /** How many rows {@code entry} takes, its text being {@code text_width} wide. */
    private static int rowCount(@NonNull Font font, @NonNull Entry entry, int text_width) {
        return wrap(font, entry.parsed().text(), text_width, rowsOf(entry.parsed())).size();
    }

    /**
     * {@code entry}'s rows, at most {@code max_rows}: the first with its time (right-aligned in the time column), its
     * tag and its text, the others with the rest of its text under the first's.
     */
    private static @NonNull List<Line> rows(@NonNull Font font, @NonNull Entry entry, @NonNull Columns columns,
            int max_rows, int now) {
        Parsed parsed = entry.parsed();
        Vector4fc color = colorOf(parsed);
        List<Line> rows = new ArrayList<>();
        for (String row : wrap(font, parsed.text(), columns.text(), Math.min(max_rows, rowsOf(parsed)))) {
            Builder line = line();
            if (rows.isEmpty()) {
                line.gap(columns.time() - font.getWidth(entry.time())).text(entry.time(), TextPanel.LABEL).gap(
                        COLUMN_SPACE).text(parsed.topic(), tagColorOf(parsed, entry.tick(), now, columns.common())).gap(
                                columns.tag() - font.getWidth(parsed.topic()) + COLUMN_SPACE);
            } else {
                line.gap(columns.textX());
            }
            rows.add(line.text(row, color).build());
        }
        return rows;
    }

    /**
     * {@code text} in rows no wider than {@code width} pixels in {@code font}, each broken after the last word that
     * fits (inside a word only when a row has no space), at most {@code max_rows}: a longer text ends its last row in
     * "...". The AI panel breaks its long status values the same way.
     */
    static @NonNull List<String> wrap(@NonNull Font font, @NonNull String text, int width, int max_rows) {
        if (width <= 0 || max_rows < 1 || font.getWidth(text) <= width) {
            return List.of(text);
        }
        List<String> rows = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = fit(font, text, start, width);
            if (end >= text.length()) {
                rows.add(text.substring(start));
                break;
            }
            if (rows.size() == max_rows - 1) {
                int cut = fit(font, text, start, width - font.getWidth(ELLIPSIS));
                rows.add(text.substring(start, cut).stripTrailing() + ELLIPSIS);
                break;
            }
            int space = text.lastIndexOf(' ', end); // the character at end is the first that does not fit
            if (space > start) {
                end = space;
            }
            rows.add(text.substring(start, end));
            start = end;
            while (start < text.length() && text.charAt(start) == ' ') {
                start++;
            }
        }
        return rows;
    }

    /**
     * The end of the longest part of {@code text} from {@code start} that is at most {@code width} wide in {@code
     * font} (as Font.getWidth measures), at least one character.
     */
    private static int fit(@NonNull Font font, @NonNull String text, int start, int width) {
        int x = font.getXBorder();
        int end = start;
        while (end < text.length()) {
            int codepoint = text.codePointAt(end);
            Quad quad = font.getQuad(codepoint);
            int advance = quad == null ? 0 : quad.getWidth() - font.getXBorder();
            if (x + advance > width) {
                break;
            }
            x += advance;
            end += Character.charCount(codepoint);
        }
        return end > start ? end : start + Character.charCount(text.codePointAt(start));
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

    private static boolean isError(@NonNull Parsed parsed) {
        String topic = parsed.topic().toUpperCase(Locale.ROOT);
        return topic.startsWith("ERR") || topic.equals("FAIL");
    }

    /** Whether the line is a dump of statistics or of the AI's parameters: long, and seldom what one reads for. */
    private static boolean isDump(@NonNull Parsed parsed) {
        return parsed.topic().equalsIgnoreCase("STAT") || parsed.topic().equalsIgnoreCase("PARAM");
    }

    /** The rows a line may take: a dump one, others {@value #MAX_ROWS}. */
    private static int rowsOf(@NonNull Parsed parsed) {
        return isDump(parsed) ? 1 : MAX_ROWS;
    }

    /** A line's text: errors red, dumps dim, the others white. */
    private static @NonNull Vector4fc colorOf(@NonNull Parsed parsed) {
        if (isError(parsed)) {
            return TextPanel.BAD;
        }
        return isDump(parsed) ? TextPanel.DIM : Color.WHITE;
    }

    /**
     * A line's tag: errors red, recent lines (of the last {@value #RECENT_TICKS} ticks) yellow, dumps and the topic
     * most lines have ({@code common}) grey, other topics tan.
     */
    private static @NonNull Vector4fc tagColorOf(@NonNull Parsed parsed, int line_tick, int now,
            @NonNull String common) {
        if (isError(parsed)) {
            return TextPanel.BAD;
        }
        if (line_tick > now - RECENT_TICKS) {
            return TextPanel.WARN;
        }
        return isDump(parsed) || parsed.topic().equals(common) ? QUIET_TAG : TAG;
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
