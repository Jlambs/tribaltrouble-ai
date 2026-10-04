package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.font.TextLineRenderer;
import com.oddlabs.tt.gui.IconQuad;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.render.GUIRenderer;
import com.oddlabs.util.Color;
import com.oddlabs.util.Quad;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lines on a dark translucent box, sized to its lines (between a minimum and a maximum width, longer lines are cut):
 * the building block of the watch HUD. Its owner sets the lines (at most a few times a second) and places it; they are
 * drawn every frame.
 *
 * <p>The HUD must not wiggle as text changes: a panel that the layout places by its edges is given one width (the
 * least and the widest alike) and, where its lines come and go, a least height ({@link #setMinHeight}); text after a
 * number that changes stays put when the number is in a slot ({@link Builder#slot}).
 *
 * <p>A line is a row of parts drawn left to right: text (in slots of a fixed width, too), the game's own icons
 * ({@link HudIcons}), bars and gaps. It may have a label, which goes in the panel's label column, dim, so that the
 * values of labelled lines start in one column; a colour swatch in front (a player's colour); a band behind it (a
 * heading); and twice the height (a heading with a big icon). {@link #line()} builds one.
 */
class TextPanel extends HudPanel {
    /** A piece of a line. */
    sealed interface Part permits Text, Icon, Bar, Gap {
    }

    /**
     * Text in a colour. With a {@code sample}, in a slot as wide as the sample (or the text, if wider), each digit
     * counted as wide as the font's widest ({@link #steadyWidth(String)}), the text left-aligned in it, or
     * right-aligned when
     * {@code right}: a number that changes, such as a time, so the parts after it (right-aligned, its own right end
     * too) stay put while its digits change. The edit font's digits are not all one width.
     */
    record Text(@NonNull String text, @NonNull Vector4fc color, @Nullable String sample,
                boolean right) implements Part {
        /** Plain text: as wide as it is. */
        Text(@NonNull String text, @NonNull Vector4fc color) {
            this(text, color, null, false);
        }
    }

    /** An icon, as high as the line, tinted ({@link Color#WHITE}: as it is). */
    record Icon(@NonNull IconQuad icon, @NonNull Vector4fc tint) implements Part {
    }

    /** A bar {@code width} pixels long, filled to {@code fraction} (0 to 1) in {@code color} over a dark track. */
    record Bar(float fraction, int width, @NonNull Vector4fc color) implements Part {
    }

    /** Empty room, {@code width} pixels. */
    record Gap(int width) implements Part {
    }

    /**
     * One line: its parts; its label (null: none), drawn dim in the label column; its colour swatch in front (null:
     * none); whether a band marks it as a heading; and its height in lines of text (1, or 2 for a big icon).
     */
    record Line(@NonNull List<Part> parts, @Nullable String label, @Nullable Vector4fc swatch, boolean heading,
                int size) {
        Line {
            parts = List.copyOf(parts);
            size = Math.max(1, size);
        }

        Line(@NonNull String text, @NonNull Vector4fc color) {
            this(text, color, null);
        }

        Line(@NonNull String text, @NonNull Vector4fc color, @Nullable Vector4fc swatch) {
            this(List.of(new Text(text, color)), null, swatch, false, 1);
        }
    }

    /** Builds a {@link Line}, part by part. */
    static final class Builder {
        private final List<Part> parts = new ArrayList<>();
        private @Nullable String label;
        private @Nullable Vector4fc swatch;
        private boolean heading;
        private int size = 1;

        private Builder() {
        }

        /** Text in white. */
        @NonNull
        Builder text(@NonNull String text) {
            return text(text, Color.WHITE);
        }

        @NonNull
        Builder text(@NonNull String text, @NonNull Vector4fc color) {
            if (!text.isEmpty()) {
                parts.add(new Text(text, color));
            }
            return this;
        }

        /**
         * Text in a slot as wide as {@code sample} with the font's widest digits ({@link Text}), left-aligned: a
         * time or a count that changes, such as {@code slot(times.precise(tick), WHITE, times.hundredths(end))}.
         */
        @NonNull
        Builder slot(@NonNull String text, @NonNull Vector4fc color, @NonNull String sample) {
            parts.add(new Text(text, color, sample, false));
            return this;
        }

        /** As {@link #slot}, the text right-aligned in it: a count in a column of counts. */
        @NonNull
        Builder slotRight(@NonNull String text, @NonNull Vector4fc color, @NonNull String sample) {
            parts.add(new Text(text, color, sample, true));
            return this;
        }

        /** An icon as it is; null adds nothing (an icon the game has not loaded). */
        @NonNull
        Builder icon(@Nullable IconQuad icon) {
            return icon(icon, Color.WHITE);
        }

        @NonNull
        Builder icon(@Nullable IconQuad icon, @NonNull Vector4fc tint) {
            if (icon != null) {
                parts.add(new Icon(icon, tint));
            }
            return this;
        }

        /** A bar {@code width} pixels long, filled to {@code fraction} (clamped to 0..1). */
        @NonNull
        Builder bar(float fraction, int width, @NonNull Vector4fc color) {
            parts.add(new Bar(Float.isNaN(fraction) ? 0f : Math.clamp(fraction, 0f, 1f), width, color));
            return this;
        }

        @NonNull
        Builder gap(int width) {
            parts.add(new Gap(width));
            return this;
        }

        /** The label, drawn dim in the panel's label column. */
        @NonNull
        Builder label(@NonNull String text) {
            label = text;
            return this;
        }

        /** A colour swatch in front of the line (a player's colour). */
        @NonNull
        Builder swatch(@Nullable Vector4fc color) {
            swatch = color;
            return this;
        }

        /** A band behind the line: a heading. */
        @NonNull
        Builder heading() {
            heading = true;
            return this;
        }

        /** The line's height in lines of text: 2 makes its icons twice as big. */
        @NonNull
        Builder size(int lines) {
            size = lines;
            return this;
        }

        @NonNull
        Line build() {
            return new Line(parts, label, swatch, heading, size);
        }
    }

    /** A new line, part by part: {@code TextPanel.line().label("hit points").bar(...).text(" 90 / 200").build()}. */
    static @NonNull Builder line() {
        return new Builder();
    }

    static final Vector4fc DIM = Color.argb4v(0xFF_B8_B8_B8);
    static final Vector4fc GOOD = Color.argb4v(0xFF_7C_F0_7C);
    static final Vector4fc BAD = Color.argb4v(0xFF_FF_60_60);
    static final Vector4fc WARN = Color.argb4v(0xFF_FF_D0_40);
    /** Labels: dim, a little blue, so values stand out from them. */
    static final Vector4fc LABEL = Color.argb4v(0xFF_9C_B0_C4);
    static final Vector4fc BACKGROUND = new Vector4f(0f, 0f, 0f, .62f);
    /** The band behind a heading line. */
    static final Vector4fc HEADING_BAND = new Vector4f(1f, 1f, 1f, .09f);
    /** A bar's empty part. */
    static final Vector4fc BAR_TRACK = new Vector4f(1f, 1f, 1f, .16f);
    static final int PADDING = 4;
    /** The room after an icon or a bar. */
    static final int ICON_GAP = 3;
    /** The room between the label column and the values. */
    static final int LABEL_GAP = 10;

    private final @NonNull Font font;
    /** The font's widest digit, which every digit of a slot ({@link Builder#slot}) is counted as. */
    private final char widest_digit;
    private final List<Line> lines = new ArrayList<>();
    private int min_width;
    private int max_width;
    /** The least height while the panel has lines ({@link #setMinHeight}); 0: as high as its lines. */
    private int min_height;
    private int label_width;
    private @NonNull Vector4fc background = BACKGROUND;

    TextPanel(int min_width) {
        this(min_width, Integer.MAX_VALUE);
    }

    TextPanel(int min_width, int max_width) {
        this.font = Skin.getSkin().getEditFont();
        this.widest_digit = widestDigit(font);
        this.min_width = min_width;
        this.max_width = max_width;
        setDim(min_width, 0);
    }

    /** Replaces the lines and resizes the panel to them (within its widths); no lines hide it. */
    void setLines(@NonNull List<Line> new_lines) {
        lines.clear();
        lines.addAll(new_lines);
        resize();
    }

    /** Sets the widths the panel keeps to (the layout's, for a panel whose lines can be any length), at once. */
    void setWidths(int min, int max) {
        if (min != min_width || max != max_width) {
            min_width = min;
            max_width = max;
            resize();
        }
    }

    /** Sets the widest the panel gets, keeping its least width. */
    void setMaxWidth(int max) {
        setWidths(min_width, max);
    }

    /**
     * Keeps the panel at least {@code height} high while it has lines, the room under them blank (0: as high as its
     * lines): a panel whose lines come and go holds its tallest height, so the panels placed against it stay put.
     */
    void setMinHeight(int height) {
        if (height != min_height) {
            min_height = height;
            resize();
        }
    }

    private void resize() {
        label_width = 0;
        for (Line line : lines) {
            String label = line.label();
            if (label != null) {
                label_width = Math.max(label_width, font.getWidth(label) + LABEL_GAP);
            }
        }
        int width = min_width;
        int height = 0;
        for (Line line : lines) {
            width = Math.max(width, valueX(line) + partsWidth(line) + PADDING);
            height += lineHeight(line);
        }
        setDim(Math.min(width, Math.max(max_width, min_width)), lines.isEmpty() ? 0 : Math.max(min_height,
                height + 2 * PADDING));
    }

    /** A darker box for a panel of many lines that the 3D view behind would make hard to read. */
    void setBackground(@NonNull Vector4fc color) {
        background = color;
    }

    /** How wide {@code line} is in this panel, padding included: wider than the panel's widest, it is cut. */
    int lineWidth(@NonNull Line line) {
        return valueX(line) + partsWidth(line) + PADDING;
    }

    /**
     * How wide {@code text} is with each of its digits as wide as the font's widest (as Font.getWidth measures): the
     * room a number needs for all values of its length, which a slot ({@link Builder#slot}) keeps.
     */
    int steadyWidth(@NonNull String text) {
        return steadyWidth(font, widest_digit, text);
    }

    /**
     * {@link #steadyWidth(String)} in {@code font}, for what draws text of its own (the timeline's legend, the AI log's
     * time column): a column or a right-aligned text as wide as this stays put while its digits change.
     */
    static int steadyWidth(@NonNull Font font, @NonNull String text) {
        return steadyWidth(font, widestDigit(font), text);
    }

    private static int steadyWidth(@NonNull Font font, char widest_digit, @NonNull String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int width = font.getXBorder();
        for (int i = 0; i < text.length();) {
            int codepoint = text.codePointAt(i);
            Quad quad = font.getQuad(codepoint >= '0' && codepoint <= '9' ? widest_digit : codepoint);
            width += quad == null ? 0 : quad.getWidth() - font.getXBorder();
            i += Character.charCount(codepoint);
        }
        return width;
    }

    /** The widest of {@code font}'s digits (the edit font's are 14 and 15 units wide). */
    private static char widestDigit(@NonNull Font font) {
        char widest = '0';
        for (char digit = '1'; digit <= '9'; digit++) {
            if (font.getWidth(String.valueOf(digit)) > font.getWidth(String.valueOf(widest))) {
                widest = digit;
            }
        }
        return widest;
    }

    /**
     * The room for the value of a line labelled {@code label} in this panel at {@code width}, its other lines being
     * {@code lines}: right of the label column they make together, inside the padding (what a value wraps to).
     */
    int valueRoom(int width, @NonNull String label, @NonNull List<Line> lines) {
        int labels = font.getWidth(label) + LABEL_GAP;
        for (Line line : lines) {
            String other = line.label();
            if (other != null) {
                labels = Math.max(labels, font.getWidth(other) + LABEL_GAP);
            }
        }
        return width - 2 * PADDING - labels;
    }

    /** The room an icon takes in a plain line, the gap after it included: the indent of text under a line's icon. */
    int iconWidth() {
        return iconSize(font.getHeight()) + ICON_GAP;
    }

    /** The height of a plain line. */
    int lineHeight() {
        return font.getHeight();
    }

    private int lineHeight(@NonNull Line line) {
        return font.getHeight() * line.size();
    }

    /** Where the line's swatch, then its label, then its parts start. */
    private int swatchX() {
        return PADDING;
    }

    private int labelX(@NonNull Line line) {
        return line.swatch() == null ? PADDING : PADDING + font.getHeight() + PADDING;
    }

    private int valueX(@NonNull Line line) {
        return labelX(line) + (line.label() == null ? 0 : label_width);
    }

    private int partsWidth(@NonNull Line line) {
        int width = 0;
        for (Part part : line.parts()) {
            width += partWidth(part, lineHeight(line));
        }
        return width;
    }

    private int partWidth(@NonNull Part part, int line_height) {
        return switch (part) {
            case Text text -> {
                String sample = text.sample();
                yield sample == null ? font.getWidth(text.text()) : Math.max(steadyWidth(sample), steadyWidth(
                        text.text()));
            }
            case Icon icon -> iconSize(line_height) + ICON_GAP;
            case Bar bar -> bar.width() + ICON_GAP;
            case Gap gap -> gap.width();
        };
    }

    /**
     * An icon's side in a line {@code line_height} high: a little smaller, so icons of lines one above another part.
     */
    private static int iconSize(int line_height) {
        return line_height - 2;
    }

    @Override
    protected void renderGeometry(@NonNull GUIRenderer renderer) {
        if (lines.isEmpty()) {
            return;
        }
        renderer.drawColoredQuad(0, 0, getWidth(), getHeight(), background);
        float right = getWidth() - PADDING;
        int top = getHeight() - PADDING;
        for (Line line : lines) {
            int height = lineHeight(line);
            int y = top - height;
            top = y;
            if (line.heading()) {
                renderer.drawColoredQuad(PADDING / 2f, y, getWidth() - PADDING, height, HEADING_BAND);
            }
            int text_y = y + (height - font.getHeight()) / 2;
            Vector4fc swatch = line.swatch();
            if (swatch != null) {
                int side = font.getHeight() - 4;
                renderer.drawColoredQuad(swatchX(), text_y + 2, side, side, swatch);
            }
            String label = line.label();
            if (label != null) {
                TextLineRenderer.render(renderer, font, label, labelX(line), text_y, 0, right, LABEL);
            }
            float x = valueX(line);
            for (Part part : line.parts()) {
                if (x >= right) {
                    break;
                }
                switch (part) {
                    case Text text -> {
                        // right-aligned in its slot, or where the slot starts
                        float text_x = text.right() ? x + partWidth(part, height) - font.getWidth(text.text()) : x;
                        TextLineRenderer.render(renderer, font, text.text(), text_x, text_y, 0, right, text.color());
                    }
                    case Icon icon -> {
                        int side = iconSize(height);
                        if (x + side <= right) {
                            renderer.drawTexture(icon.icon().getTexture(), x, y + (height - side) / 2f, side, side,
                                    icon.icon().getU1(), icon.icon().getV1(), icon.icon().getU2(), icon.icon().getV2(),
                                    icon.tint());
                        }
                    }
                    case Bar bar -> {
                        float bar_height = Math.max(4, font.getHeight() * .45f);
                        float bar_y = y + (height - bar_height) / 2f;
                        float width = Math.min(bar.width(), right - x);
                        renderer.drawColoredQuad(x, bar_y, width, bar_height, BAR_TRACK);
                        renderer.drawColoredQuad(x, bar_y, Math.min(width, bar.width() * bar.fraction()), bar_height,
                                bar.color());
                    }
                    case Gap gap -> {
                    }
                }
                x += partWidth(part, height);
            }
        }
    }
}
