package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.font.TextLineRenderer;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.render.GUIRenderer;
import com.oddlabs.util.Color;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * The help overlay (F1 or H): every key and mouse action of the watch view, in two columns. WatchHud's key handling
 * and this table must agree; docs/aisim.md "Watching a game" lists the same.
 */
final class HelpPanel extends HudPanel {
    /** A heading (no key) or a key and what it does. */
    private record Row(@NonNull String key, @NonNull String action) {
    }

    /** The table: {@code key | action} lines, and headings without a bar. */
    private static final String TABLE = """
            Keys
            P | play / pause
            +  - | faster / slower: 1/8x ... 1x (50 ticks a second) ... 128x, MAX
            .  , | one tick on / back; with Shift 50 ticks, with Ctrl 500
            J | jump box: 12:30, 1:02:03, 90s, t4500, +30, -10, +t500, end (Enter jumps, Esc leaves it)
            T  Shift+T | clock speed: the speed times are shown and typed at (ludicrous, normal, fast, slow)
            N  B | next / previous event of the timeline
            ]  [ | next / previous player: the camera goes there (a little zoomed out), the AI log follows
            C  Shift+C | camera to the picked unit or building / keep it in view as it moves (Shift+C stops)
            E | simulate to the recorded end, then come back here (E again stops)
            L | AI log panel: show / hide
            Shift+L | AI log: show / hide the 'flee of' lines
            O | scoreboard: show / hide
            I | inspector: show / hide
            A  Shift+A | the followed AI's overlay: all layers, none, each layer alone
            F1  H | this help (Esc closes it too)
            Space | overview map and back
            Ctrl+H | hide / show the whole HUD
            Esc | menu: Resume, Options, Quit (quits the program; the game pauses meanwhile)
            Mouse
            left click | inspect a unit or building of any player (and ring it)
            scoreboard row | follow that player (camera and AI log)
            timeline | click or drag, let go to jump there; hover a mark to read its event
            wheel | zoom (three times as far out as in a game); over the AI log: older lines
            camera | as in the game: arrow keys, screen edges, Home/End, Insert/Delete
            """;
    private static final List<Row> ROWS = TABLE.lines().map(line -> {
        int bar = line.indexOf(" | ");
        return bar < 0 ? new Row("", line) : new Row(line.substring(0, bar), line.substring(bar + 3));
    }).toList();
    private static final int PADDING = 10;
    private static final int GAP = 18;
    private static final Vector4fc BACKGROUND = new Vector4f(.03f, .03f, .05f, .94f);

    private final @NonNull Font font;
    private final int key_width;

    HelpPanel() {
        this.font = Skin.getSkin().getEditFont();
        int keys = 0;
        int actions = 0;
        for (Row row : ROWS) {
            keys = Math.max(keys, font.getWidth(row.key()));
            actions = Math.max(actions, font.getWidth(row.action()));
        }
        this.key_width = keys;
        setDim(2 * PADDING + keys + GAP + actions, 2 * PADDING + (ROWS.size() + 1) * font.getHeight());
    }

    @Override
    protected void renderGeometry(@NonNull GUIRenderer renderer) {
        renderer.drawColoredQuad(0, 0, getWidth(), getHeight(), BACKGROUND);
        int height = font.getHeight();
        int y = getHeight() - PADDING - height;
        TextLineRenderer.render(renderer, font, "Watching a recorded game: the keys and the mouse (F1 or H closes)",
                PADDING, y, 0, getWidth(), TextPanel.WARN);
        for (Row row : ROWS) {
            y -= height;
            if (row.key().isEmpty()) {
                TextLineRenderer.render(renderer, font, row.action(), PADDING, y, 0, getWidth(), TextPanel.GOOD);
            } else {
                TextLineRenderer.render(renderer, font, row.key(), PADDING, y, 0, getWidth(), TextPanel.WARN);
                TextLineRenderer.render(renderer, font, row.action(), PADDING + key_width + GAP, y, 0, getWidth(),
                        Color.WHITE);
            }
        }
    }
}
