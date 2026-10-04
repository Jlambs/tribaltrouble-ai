package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.font.Font;
import com.oddlabs.tt.font.TextLineRenderer;
import com.oddlabs.tt.gui.GUIObject;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.gui.Skin;
import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.render.GUIRenderer;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * The labels of an AI's marks, as text on screen at the top of each mark's pin, projected from the world every
 * frame (as SpectatorCursor places the watched player's pointer). It covers the whole view, takes no focus (clicks go
 * through to the 3D view) and sits below the HUD's panels. Labels that would overlap one drawn already are left out,
 * nearest the camera first, so a crowd of marks stays readable. A label is a flag on its pin: its mark's text on a dark
 * box over the pin's top, whose left edge, in the mark's own colour, goes on up from the pin. A label whose text had
 * to be lightened so still shows its mark's colour, and the edge of such a dark colour has a light rim, which keeps it
 * visible on the dark box.
 */
final class OverlayLabels extends GUIObject {
    /** Labels drawn per frame at most. */
    private static final int MAX_LABELS = 160;
    private static final int PADDING = 3;
    /** The coloured edge on a label's left, over the pin's top (pixels). */
    private static final int EDGE = 2;
    private static final Vector4fc BACKGROUND = new Vector4f(0f, 0f, 0f, .7f);
    /** The rim around the edge of a dark colour. */
    private static final Vector4fc EDGE_RIM = new Vector4f(1f, 1f, 1f, .45f);
    /** A label's colour is its mark's, lightened to at least this luminance so it reads on the dark box. */
    private static final float MIN_LUMINANCE = .55f;

    private final @NonNull AiOverlay overlay;
    private final @NonNull GUIRoot gui_root;
    private final @NonNull Font font;
    private final @NonNull Vector4f point = new Vector4f();
    private final @NonNull Vector4f color = new Vector4f();
    private final @NonNull Vector4f edge = new Vector4f();
    // the boxes of the labels drawn this frame: x, y, width, height
    private final float[] boxes = new float[MAX_LABELS * 4];
    // the labels in view this frame: index into the paint's shapes, and screen position
    private final int[] shape_index = new int[OverlayPaint.MAX_SHAPES];
    private final float[] screen_x = new float[OverlayPaint.MAX_SHAPES];
    private final float[] screen_y = new float[OverlayPaint.MAX_SHAPES];
    /** The same labels by depth, nearest first: the depth's bits above, the label's index below. */
    private final long[] by_depth = new long[OverlayPaint.MAX_SHAPES];

    OverlayLabels(@NonNull AiOverlay overlay, @NonNull GUIRoot gui_root) {
        this.overlay = overlay;
        this.gui_root = gui_root;
        this.font = Skin.getSkin().getEditFont();
        displayChangedNotify(gui_root.getWidth(), gui_root.getHeight());
    }

    @Override
    protected void displayChangedNotify(int width, int height) {
        setDim(width, height);
    }

    @Override
    protected void renderGeometry(@NonNull GUIRenderer renderer) {
        OverlayPaint paint = overlay.shownPaint();
        if (paint == null) {
            return;
        }
        Predicate<String> shows = overlay::shows;
        HeightMap heights = overlay.heightMap();
        int count = 0;
        List<OverlayPaint.Shape> shapes = paint.shapes();
        for (int i = 0; i < shapes.size(); i++) {
            OverlayPaint.Shape shape = shapes.get(i);
            if (shape.kind() != OverlayPaint.Kind.MARK || shape.label().isEmpty() || !shows.test(shape.layer())) {
                continue;
            }
            float x = UnitGrid.coordinateFromGrid(shape.x0());
            float y = UnitGrid.coordinateFromGrid(shape.y0());
            float z = OverlayRenderer.surface(heights, x, y) + OverlayRenderer.PIN;
            gui_root.projectToScreen(x, y, z, point);
            if (point.w < GUIRoot.MIN_PROJECTED_W || point.x < -getWidth() || point.x > getWidth()
                    || point.y < -font.getHeight() || point.y > getHeight() + font.getHeight()) {
                continue;
            }
            shape_index[count] = i;
            screen_x[count] = point.x;
            screen_y[count] = point.y;
            // a positive float's bits order as the float does
            by_depth[count] = (long) Float.floatToIntBits(point.w) << 32 | count;
            count++;
        }
        Arrays.sort(by_depth, 0, count);
        int drawn = 0;
        for (int k = 0; k < count && drawn < MAX_LABELS; k++) {
            int n = (int) by_depth[k];
            OverlayPaint.Shape shape = shapes.get(shape_index[n]);
            String text = shape.label();
            float width = EDGE + font.getWidth(text) + 2 * PADDING;
            float height = font.getHeight();
            float left = Math.round(screen_x[n] - EDGE * .5f);
            float bottom = Math.round(screen_y[n]);
            if (overlaps(drawn, left, bottom, width, height)) {
                continue;
            }
            boxes[drawn * 4] = left;
            boxes[drawn * 4 + 1] = bottom;
            boxes[drawn * 4 + 2] = width;
            boxes[drawn * 4 + 3] = height;
            drawn++;
            renderer.drawColoredQuad(left, bottom, width, height, BACKGROUND);
            int rgb = shape.rgb();
            boolean dark = luminance(rgb) < MIN_LUMINANCE;
            if (dark) {
                renderer.drawColoredQuad(left - 1, bottom, EDGE + 2, height, EDGE_RIM);
            }
            renderer.drawColoredQuad(left, bottom + (dark ? 1 : 0), EDGE, height - (dark ? 2 : 0), edge.set(
                    (rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f));
            TextLineRenderer.render(renderer, font, text, left + EDGE + PADDING, bottom, Float.NEGATIVE_INFINITY,
                    Float.POSITIVE_INFINITY, readable(rgb));
        }
    }

    private boolean overlaps(int drawn, float left, float bottom, float width, float height) {
        for (int k = 0; k < drawn; k++) {
            float bx = boxes[k * 4];
            float by = boxes[k * 4 + 1];
            if (left < bx + boxes[k * 4 + 2] && bx < left + width && bottom < by + boxes[k * 4 + 3]
                    && by < bottom + height) {
                return true;
            }
        }
        return false;
    }

    /** {@code rgb} (0xRRGGBB) as a text colour: lightened towards white until it reads on the dark box. */
    private @NonNull Vector4fc readable(int rgb) {
        float r = (rgb >> 16 & 0xFF) / 255f;
        float g = (rgb >> 8 & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float luminance = luminance(rgb);
        if (luminance < MIN_LUMINANCE) {
            float k = (MIN_LUMINANCE - luminance) / (1f - luminance);
            r += (1f - r) * k;
            g += (1f - g) * k;
            b += (1f - b) * k;
        }
        return color.set(r, g, b, 1f);
    }

    /** The luminance of {@code rgb} (0xRRGGBB), 0 to 1. */
    private static float luminance(int rgb) {
        return .2126f * (rgb >> 16 & 0xFF) / 255f + .7152f * (rgb >> 8 & 0xFF) / 255f + .0722f * (rgb & 0xFF) / 255f;
    }
}
