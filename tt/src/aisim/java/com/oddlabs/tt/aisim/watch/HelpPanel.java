package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.font.TextLineRenderer;
import com.oddlabs.tt.gui.GUIObject;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.render.GUIRenderer;
import com.oddlabs.util.Color;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The help overlay (F1 or H): every key and mouse action of the watch view, in sections (time, the camera and the
 * players, the panels and the menu, the mouse), each under a heading band; in two columns when the window is wide
 * enough, so it stays clear of the status panel and the jump box, else in one (closer set when the window is short
 * too). Keys are drawn as key caps, mouse actions as words, what they do in white and its notes and examples dimmer.
 * WatchHud's key handling and this table must agree; docs/aisim.md "Watching a game" lists the same, in the same
 * sections.
 */
final class HelpPanel extends HudPanel {
    /**
     * A section's heading (its title, no keys), or a row: its keys (each a key cap) or a mouse action's words (no key
     * caps), what it does, and its notes (dimmer; empty for none, else starting with a space).
     */
    private record Row(@NonNull String @NonNull [] keys, boolean caps, @NonNull String action,
                       @NonNull String details) {
        boolean heading() {
            return keys.length == 0;
        }
    }

    /**
     * Where the rows go in one arrangement: per row its column's left edge, where what it does starts, the bottom of
     * its line (down from the panel's top) and its column's width; the room between two rows; and the panel's size.
     */
    private record Layout(int width, int height, int @NonNull [] x, int @NonNull [] action_x, int @NonNull [] depth,
                          int @NonNull [] column_width, int row_gap) {
    }

    /**
     * The table: {@code # Section} headings, {@code keys | action} rows with the keys apart by two spaces, and
     * {@code * words | action} rows of the mouse (or anything else that is not a key); either may end in
     * {@code | notes}, drawn dimmer after what it does.
     */
    private static final String TABLE = """
            # Time
            P | play / pause
            +  - | faster / slower: | 1/8x ... 1x (50 ticks a second) ... 128x, MAX
            .  , | one tick on / back; with Shift 50 ticks, with Ctrl 500
            J | jump box: | 12:30, 1:02:03, 90s, t4500, +30, -10, +t500, end (Enter jumps, Esc leaves it)
            T  Shift+T | clock speed: | the speed times are shown and typed at (ludicrous, normal, fast, slow)
            N  B | next / previous event of the timeline
            E | simulate to the recorded end, then come back here | (E again stops)
            # Camera and players
            ]  [ | next / previous player: | the camera goes there (a little zoomed out), the AI log follows
            C  Shift+C | camera to the picked unit or building / keep it in view as it moves | (Shift+C stops)
            Space | overview map and back
            * camera | as in the game: | arrow keys, screen edges, Home/End, Insert/Delete
            # Panels and the menu
            L | AI log panel: show / hide
            Shift+L | AI log: show / hide the 'flee of' lines
            O | scoreboard: show / hide
            I | inspector: show / hide
            S | status panel and jump box: show / hide | (J still jumps; messages show at the top)
            R | timeline: show / hide | (with the compass off, the AI log takes its room)
            A  Shift+A | the followed AI's overlay: all layers, none, each layer alone
            F1  H | this help | (Esc closes it too)
            Ctrl+H | hide / show the whole HUD
            Esc | menu: Resume, Options, Quit | (Quit quits the program; the game pauses meanwhile)
            # Mouse
            * left click | inspect a unit or building of any player | (and ring it)
            * scoreboard row | follow that player | (camera and AI log)
            * timeline | click or drag, let go to jump there; hover a mark to read its event
            * wheel | zoom; over the AI log: older lines | (it zooms three times as far out as in a game)
            """;
    private static final List<Row> ROWS = TABLE.lines().map(HelpPanel::row).toList();
    private static final String TITLE = "Watching a recorded game: the keys and the mouse";
    private static final String CLOSE = "F1, H or Esc closes";
    private static final int PADDING = 12;
    /** The room between the keys and what they do, and between two columns. */
    private static final int GAP = 18;
    private static final int COLUMN_GAP = 30;
    /** The room between two key caps, and a key cap's room around its text, left and right. */
    private static final int CAP_GAP = 6;
    private static final int CAP_PADDING = 6;
    /** The room above a section (none in a short window). */
    private static final int SECTION_GAP = 6;
    /** A row is this much higher than a line of text (none in a short window): room between the key caps of rows. */
    private static final int ROW_GAP = 2;
    /** The room the help leaves at the window's sides and top at least, or it takes a narrower or lower layout. */
    private static final int MARGIN = 8;
    private static final Vector4fc BACKGROUND = new Vector4f(.03f, .03f, .05f, .98f);
    private static final Vector4fc HEADING = TextPanel.GOOD;
    private static final Vector4fc CAP_EDGE = Color.argb4v(0xFF_70_74_80);
    private static final Vector4fc CAP_FACE = Color.argb4v(0xFF_26_28_2E);
    private static final Vector4fc KEY = TextPanel.WARN;

    private final @NonNull Font font;
    private final @NonNull Layout two_columns;
    private final @NonNull Layout one_column;
    /** One column, its rows and sections closer: for a window too narrow for two columns and too low for one. */
    private final @NonNull Layout one_column_close;
    private @NonNull Layout layout;

    HelpPanel() {
        this.font = Skin.getSkin().getEditFont();
        // the sections, as the rows they start at; the two columns split them where the taller column is lowest
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < ROWS.size(); i++) {
            if (ROWS.get(i).heading()) {
                starts.add(i);
            }
        }
        starts.add(ROWS.size());
        this.one_column = arrange(List.of(0, ROWS.size()), ROW_GAP, SECTION_GAP);
        this.one_column_close = arrange(List.of(0, ROWS.size()), 0, 0);
        Layout best = null;
        for (int split = 1; split < starts.size() - 1; split++) {
            Layout two = arrange(List.of(0, starts.get(split), ROWS.size()), ROW_GAP, SECTION_GAP);
            if (best == null || two.height() < best.height()) {
                best = two;
            }
        }
        this.two_columns = best != null ? best : one_column;
        this.layout = one_column;
        setDim(layout.width(), layout.height());
    }

    private static @NonNull Row row(@NonNull String line) {
        if (line.startsWith("# ")) {
            return new Row(new String[0], false, line.substring(2), "");
        }
        String[] fields = line.split(" \\| ");
        String keys = fields[0];
        String action = fields[1];
        String details = fields.length > 2 ? " " + fields[2] : "";
        if (keys.startsWith("* ")) {
            return new Row(new String[]{keys.substring(2)}, false, action, details);
        }
        return new Row(Arrays.stream(keys.split(" {2}")).map(String::strip).toArray(String[]::new), true, action,
                details);
    }

    /**
     * The rows in columns, {@code row_gap} apart and sections {@code section_gap} more: column {@code c} has the rows
     * from {@code bounds[c]} to {@code bounds[c + 1]}.
     */
    private @NonNull Layout arrange(@NonNull List<Integer> bounds, int row_gap, int section_gap) {
        int n = ROWS.size();
        int[] x = new int[n];
        int[] action_x = new int[n];
        int[] depth = new int[n];
        int[] column_width = new int[n];
        int line = font.getHeight() + row_gap;
        int left = PADDING;
        int bottom = 0;
        for (int column = 0; column + 1 < bounds.size(); column++) {
            int keys = 0;
            int actions = 0;
            for (int i = bounds.get(column); i < bounds.get(column + 1); i++) {
                Row row = ROWS.get(i);
                if (!row.heading()) {
                    keys = Math.max(keys, keysWidth(row));
                    actions = Math.max(actions, font.getWidth(row.action() + row.details()));
                }
            }
            int width = keys + GAP + actions;
            int y = PADDING + font.getHeight();
            for (int i = bounds.get(column); i < bounds.get(column + 1); i++) {
                y += (ROWS.get(i).heading() ? section_gap : 0) + line;
                x[i] = left;
                action_x[i] = left + keys + GAP;
                depth[i] = y;
                column_width[i] = width;
            }
            bottom = Math.max(bottom, y);
            left += width + COLUMN_GAP;
        }
        int width = Math.max(left - COLUMN_GAP, PADDING + font.getWidth(TITLE) + GAP + font.getWidth(CLOSE));
        return new Layout(width + PADDING, bottom + PADDING, x, action_x, depth, column_width, row_gap);
    }

    private int keysWidth(@NonNull Row row) {
        if (!row.caps()) {
            return font.getWidth(row.keys()[0]);
        }
        int width = -CAP_GAP;
        for (String key : row.keys()) {
            width += capWidth(key) + CAP_GAP;
        }
        return width;
    }

    private int capWidth(@NonNull String key) {
        return Math.max(font.getWidth(key) + 2 * CAP_PADDING, font.getHeight());
    }

    /** Shown: the layout the window has room for. */
    @Override
    protected void doAdd() {
        super.doAdd();
        fit();
    }

    /**
     * Takes the layout the window (the HUD's layer, the help's parent) has room for: two columns if it is wide enough
     * for them, else one, set closer if the window is too low for the usual one.
     */
    private void fit() {
        GUIObject parent = getParent();
        Layout fits = one_column;
        if (parent != null && parent.getWidth() >= two_columns.width() + 2 * MARGIN) {
            fits = two_columns;
        } else if (parent != null && parent.getHeight() < one_column.height() + 2 * MARGIN) {
            fits = one_column_close;
        }
        if (!fits.equals(layout)) {
            layout = fits;
            setDim(layout.width(), layout.height());
        }
    }

    @Override
    protected void renderGeometry(@NonNull GUIRenderer renderer) {
        fit(); // the window changed size: placed for its new size from the next frame on
        renderer.drawColoredQuad(0, 0, getWidth(), getHeight(), BACKGROUND);
        int height = font.getHeight();
        int row_gap = layout.row_gap();
        int right = getWidth() - PADDING;
        int title_y = getHeight() - PADDING - height;
        TextLineRenderer.render(renderer, font, TITLE, PADDING, title_y, 0, right, Color.WHITE);
        TextLineRenderer.render(renderer, font, CLOSE, right - font.getWidth(CLOSE), title_y, 0, right,
                TextPanel.DIM);
        for (int i = 0; i < ROWS.size(); i++) {
            Row row = ROWS.get(i);
            int x = layout.x()[i];
            int y = getHeight() - layout.depth()[i];
            if (row.heading()) {
                renderer.drawColoredQuad(x - PADDING / 2f, y - row_gap / 2f, layout.column_width()[i] + PADDING,
                        height + row_gap, TextPanel.HEADING_BAND);
                TextLineRenderer.render(renderer, font, row.action(), x, y, 0, right, HEADING);
                continue;
            }
            if (row.caps()) {
                float cap_x = x;
                for (String key : row.keys()) {
                    int width = capWidth(key);
                    // as high as the row but for a pixel above and below, so the caps of two rows never touch
                    drawCap(renderer, cap_x, y - row_gap / 2f + 1, width, height + row_gap - 2);
                    TextLineRenderer.render(renderer, font, key, cap_x + (width - font.getWidth(key)) / 2f, y, 0,
                            right, KEY);
                    cap_x += width + CAP_GAP;
                }
            } else {
                TextLineRenderer.render(renderer, font, row.keys()[0], x, y, 0, right, KEY);
            }
            float end = TextLineRenderer.render(renderer, font, row.action(), layout.action_x()[i], y, 0, right,
                    Color.WHITE);
            TextLineRenderer.render(renderer, font, row.details(), end, y, 0, right, TextPanel.DIM);
        }
    }

    /** A key cap: a dark face in a lighter edge, its corners cut, its lower edge thicker (the key's side). */
    private static void drawCap(@NonNull GUIRenderer renderer, float x, float y, float width, float height) {
        renderer.drawColoredQuad(x + 1, y, width - 2, height, CAP_EDGE);
        renderer.drawColoredQuad(x, y + 1, width, height - 2, CAP_EDGE);
        renderer.drawColoredQuad(x + 1, y + 3, width - 2, height - 4, CAP_FACE);
    }
}
