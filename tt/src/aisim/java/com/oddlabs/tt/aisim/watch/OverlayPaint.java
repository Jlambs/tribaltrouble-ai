package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.AiView;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One call of an AI's {@link AiView#paint}: the canvas the overlay hands it, and afterwards what it painted, which the
 * overlay shows until the next paint. A painter is the AI author's code, so nothing it passes is taken on trust: a
 * null name fails the paint (the overlay reports it), a circle without a usable radius is left out, long texts are cut
 * and the counts are capped, so a bad painter costs a frame of its overlay, never the viewer. It keeps only numbers and
 * strings: nothing of the world, so a rewind cannot leave it pointing into an old state.
 */
final class OverlayPaint implements AiView.Canvas {
    /** Shapes beyond this many in one paint are left out: the overlay draws every frame, and must not slow it. */
    static final int MAX_SHAPES = 5_000;
    /** Status lines beyond this many are left out: the AI panel is meant to stay small. */
    static final int MAX_STATUS = 20;
    /** Longer status keys and values and mark labels are cut to this many characters. */
    static final int MAX_TEXT = 72;
    /** Longer layer names are cut to this many characters. */
    static final int MAX_LAYER = 24;

    /** What a shape is. */
    enum Kind {
        MARK,
        LINE,
        CIRCLE
    }

    /**
     * One shape, in grid cells: a mark at (x0, y0) with its label, a line from (x0, y0) to (x1, y1), or a circle of
     * {@code radius} cells around (x0, y0). {@code rgb} is 0xRRGGBB.
     */
    record Shape(@NonNull Kind kind, @NonNull String layer, int x0, int y0, int x1, int y1, float radius, int rgb,
                 @NonNull String label) {
    }

    private final List<Shape> shapes = new ArrayList<>();
    private final Map<String, String> status = new LinkedHashMap<>();
    private final Set<String> layers = new LinkedHashSet<>();
    private int over_limit;
    private int invalid;
    /** After paint returned: a painter that kept the canvas cannot change what is shown, nor fail anything. */
    private boolean closed;

    // ---------------------------------------------------------------- AiView.Canvas

    @Override
    public void status(@NonNull String key, @NonNull String value) {
        if (closed) {
            return;
        }
        String k = clip(Objects.requireNonNull(key, "status key"), MAX_TEXT);
        String v = clip(Objects.requireNonNull(value, "status value"), MAX_TEXT);
        if (!status.containsKey(k) && status.size() >= MAX_STATUS) {
            over_limit++;
            return;
        }
        status.put(k, v);
    }

    @Override
    public void mark(@NonNull String layer, int x, int y, int rgb, @NonNull String label) {
        if (closed) {
            return;
        }
        add(new Shape(Kind.MARK, layer(layer), x, y, x, y, 0f, rgb & 0xFF_FF_FF, clip(Objects.requireNonNull(label,
                "label"), MAX_TEXT)));
    }

    @Override
    public void line(@NonNull String layer, int x0, int y0, int x1, int y1, int rgb) {
        if (closed) {
            return;
        }
        add(new Shape(Kind.LINE, layer(layer), x0, y0, x1, y1, 0f, rgb & 0xFF_FF_FF, ""));
    }

    @Override
    public void circle(@NonNull String layer, int x, int y, float radius, int rgb) {
        if (closed) {
            return;
        }
        String name = layer(layer);
        if (!(radius > 0f) || Float.isInfinite(radius)) {
            invalid++;
            return;
        }
        add(new Shape(Kind.CIRCLE, name, x, y, x, y, radius, rgb & 0xFF_FF_FF, ""));
    }

    // ---------------------------------------------------------------- what was painted

    /** Ends the paint: later calls (from a painter that kept the canvas) do nothing. */
    void close() {
        closed = true;
    }

    @NonNull
    List<Shape> shapes() {
        return Collections.unmodifiableList(shapes);
    }

    /** The status lines, in the order their keys were first painted. */
    @NonNull
    Map<String, String> status() {
        return Collections.unmodifiableMap(status);
    }

    /** The layers this paint used, in the order first painted (also for shapes over the limit). */
    @NonNull
    Set<String> layers() {
        return Collections.unmodifiableSet(layers);
    }

    /** Shapes and status lines left out because the paint had too many. */
    int overLimit() {
        return over_limit;
    }

    /** Circles left out for a radius that is not a positive number. */
    int invalid() {
        return invalid;
    }

    private void add(@NonNull Shape shape) {
        if (shapes.size() >= MAX_SHAPES) {
            over_limit++;
            return;
        }
        shapes.add(shape);
    }

    private @NonNull String layer(@NonNull String layer) {
        String name = clip(Objects.requireNonNull(layer, "layer"), MAX_LAYER);
        layers.add(name);
        return name;
    }

    static @NonNull String clip(@NonNull String text, int max) {
        String line = text.indexOf('\n') < 0 ? text : text.replace('\n', ' ');
        return line.length() <= max ? line : line.substring(0, max - 3) + "...";
    }
}
