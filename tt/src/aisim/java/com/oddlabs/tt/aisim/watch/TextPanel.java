package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.font.TextLineRenderer;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.render.GUIRenderer;
import com.oddlabs.util.Color;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lines of text on a dark translucent box, sized to its lines (between a minimum and a maximum width, longer lines are
 * cut): the building block of the watch HUD. Its owner sets the lines (at most a few times a second) and places it;
 * the text is drawn every frame.
 */
class TextPanel extends HudPanel {
    /**
     * One line: its text, its colour, and an optional colour swatch drawn in front of it (a player's colour).
     */
    record Line(@NonNull String text, @NonNull Vector4fc color, @Nullable Vector4fc swatch) {
        Line(@NonNull String text) {
            this(text, Color.WHITE, null);
        }

        Line(@NonNull String text, @NonNull Vector4fc color) {
            this(text, color, null);
        }
    }

    static final Vector4fc DIM = Color.argb4v(0xFF_B8_B8_B8);
    static final Vector4fc GOOD = Color.argb4v(0xFF_7C_F0_7C);
    static final Vector4fc BAD = Color.argb4v(0xFF_FF_60_60);
    static final Vector4fc WARN = Color.argb4v(0xFF_FF_D0_40);
    static final Vector4fc BACKGROUND = new Vector4f(0f, 0f, 0f, .62f);
    static final int PADDING = 4;

    private final @NonNull Font font;
    private final List<Line> lines = new ArrayList<>();
    private int min_width;
    private int max_width;
    private @NonNull Vector4fc background = BACKGROUND;

    TextPanel(int min_width) {
        this(min_width, Integer.MAX_VALUE);
    }

    TextPanel(int min_width, int max_width) {
        this.font = Skin.getSkin().getEditFont();
        this.min_width = min_width;
        this.max_width = max_width;
        setDim(min_width, 0);
    }

    /** Replaces the lines and resizes the panel to them (within its widths); no lines hide it. */
    void setLines(@NonNull List<Line> new_lines) {
        lines.clear();
        lines.addAll(new_lines);
        int width = min_width;
        for (Line line : lines) {
            width = Math.max(width, textX(line) + font.getWidth(line.text()) + PADDING);
        }
        setDim(Math.min(width, Math.max(max_width, min_width)),
                lines.isEmpty() ? 0 : lines.size() * font.getHeight() + 2 * PADDING);
    }

    /** Sets the widths the panel keeps to (the layout's, for a panel whose lines can be any length); next setLines. */
    void setWidths(int min, int max) {
        min_width = min;
        max_width = max;
    }

    /** A darker box for a panel of many lines that the 3D view behind would make hard to read. */
    void setBackground(@NonNull Vector4fc color) {
        background = color;
    }

    int lineHeight() {
        return font.getHeight();
    }

    private int textX(@NonNull Line line) {
        return line.swatch() == null ? PADDING : PADDING + font.getHeight() + PADDING;
    }

    @Override
    protected void renderGeometry(@NonNull GUIRenderer renderer) {
        if (lines.isEmpty()) {
            return;
        }
        renderer.drawColoredQuad(0, 0, getWidth(), getHeight(), background);
        int height = font.getHeight();
        int y = getHeight() - PADDING - height;
        for (Line line : lines) {
            Vector4fc swatch = line.swatch();
            if (swatch != null) {
                renderer.drawColoredQuad(PADDING, y + 2, height - 4, height - 4, swatch);
            }
            TextLineRenderer.render(renderer, font, line.text(), textX(line), y, 0, getWidth() - PADDING,
                    line.color());
            y -= height;
        }
    }
}
