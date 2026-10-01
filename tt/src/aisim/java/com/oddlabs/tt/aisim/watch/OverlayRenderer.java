package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.camera.CameraState;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.render.MatrixStack;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.render.shader.DebugMeshShader;
import com.oddlabs.tt.render.shader.DebugShaderRenderer;
import com.oddlabs.tt.render.state.CullMode;
import com.oddlabs.tt.render.state.RenderContext;
import com.oddlabs.tt.render.state.ScopedState;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.util.function.Predicate;

/**
 * Draws an AI's painted shapes into the 3D scene, from the view's {@code render3D} (WatchControls): lines and circles
 * that follow the ground, and marks as pins standing on it. Like the engine's debug drawing it draws with the depth
 * test off, so terrain, trees and units never hide a shape; unlike it, lines are ribbons a few pixels wide that face
 * the camera, readable at any zoom (GL core profiles draw 1-pixel lines only). Only drawing: it reads the height map,
 * nothing else of the world.
 */
final class OverlayRenderer {
    /** Lines and circles float this far above the ground (meters), so they never dip into a slope between samples. */
    private static final float LIFT = .4f;
    /** The height of a mark's pin (meters); its label sits at the top. */
    static final float PIN = 5f;
    /** The radius of the ring at the foot of a mark (meters). */
    private static final float MARK_RING = 1.2f;
    /** A line's width on screen, in pixels, and at least this many meters close up. */
    private static final float LINE_PIXELS = 3f;
    private static final float MIN_WIDTH = .12f;
    /** Ground lines and circles are sampled this often (meters): one grid cell. */
    private static final float STEP = HeightMap.METERS_PER_UNIT_GRID;
    private static final int MAX_LINE_STEPS = 512;
    private static final int MIN_CIRCLE_STEPS = 16;
    private static final int MAX_CIRCLE_STEPS = 256;
    /**
     * Segments drawn per frame at most, so a painter with thousands of long lines costs a bounded frame: 20,000 took
     * 1.9 ms of CPU a frame on the development machine (chaos:paint=heavy, 2026).
     */
    private static final int MAX_SEGMENTS = 20_000;
    /** Triangles go out in batches below ShaderRenderer's 1024 vertices, whole quads only (6 vertices each). */
    private static final int BATCH_VERTICES = 1020;
    private static final float ALPHA = .9f;

    private @Nullable DebugShaderRenderer shader;
    private @Nullable MatrixStack shader_model_view;
    private @Nullable MatrixStack shader_projection;
    private int vertices;
    private int segments;
    // the camera of the current frame
    private float cam_x;
    private float cam_y;
    private float cam_z;
    /** Meters per pixel at a distance of one meter. */
    private float pixel_size;
    private @Nullable HeightMap heights;
    private float red;
    private float green;
    private float blue;

    /** Segments the last frame drew; and how many shapes it left out, over its budget. */
    private int drawn;
    private int skipped;
    /** The CPU time of a frame's drawing (ms: computing and submitting the shapes), smoothed over recent frames. */
    private double draw_millis;

    /**
     * Draws {@code paint}'s shapes of the layers {@code shows} accepts. Called for every pass of the scene: the water's
     * reflection pass (its camera is mirrored below the sea level, where the game camera never goes) is skipped, so
     * each shape is drawn once and never shows up as a reflection.
     */
    void render(@NonNull OverlayPaint paint, @NonNull Predicate<String> shows, @NonNull HeightMap height_map,
            @NonNull CameraState state, @NonNull MatrixStack model_view, @NonNull MatrixStack projection) {
        if (state.getCurrentZ() < height_map.getSeaLevelMeters() || state.getHeight() <= 0) {
            return;
        }
        DebugShaderRenderer renderer = shaderFor(model_view, projection);
        long start = System.nanoTime(); // after the shader, which the first frame compiles
        heights = height_map;
        cam_x = state.getCurrentX();
        cam_y = state.getCurrentY();
        cam_z = state.getCurrentZ();
        pixel_size = 2f * (float) Math.tan(Math.toRadians(Globals.FOV) / 2) / state.getHeight();
        vertices = 0;
        segments = 0;
        skipped = 0;
        RenderContext context = Renderer.getRenderer().getRenderContext();
        // the scene's second draw buffer is the post-processor's mask, which the debug shader does not write: keep
        // it out of the overlay, as the scene's own unmasked passes do
        int first_buffer = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0);
        int second_buffer = GL11.glGetInteger(GL20.GL_DRAW_BUFFER1);
        boolean masked = second_buffer != GL11.GL_NONE;
        if (masked) {
            GL20.glDrawBuffers(first_buffer);
        }
        try (ScopedState _ = context.withCullMode(CullMode.NONE)) {
            renderer.begin(GL11.GL_TRIANGLES);
            for (OverlayPaint.Shape shape : paint.shapes()) {
                if (!shows.test(shape.layer())) {
                    continue;
                }
                if (segments >= MAX_SEGMENTS) {
                    skipped++; // before its ground is sampled: a shape over the budget costs nothing
                    continue;
                }
                draw(renderer, shape);
            }
            renderer.end();
        } finally {
            if (masked) {
                GL20.glDrawBuffers(new int[]{first_buffer, second_buffer});
            }
            heights = null;
        }
        drawn = segments;
        double millis = (System.nanoTime() - start) / 1e6;
        draw_millis = draw_millis == 0 ? millis : draw_millis * .9 + millis * .1;
    }

    /** A frame with nothing to draw. */
    void idle() {
        drawn = 0;
        skipped = 0;
    }

    /** Segments drawn in the last frame. */
    int drawn() {
        return drawn;
    }

    /** The CPU time of drawing a frame's shapes (ms), smoothed. */
    double drawMillis() {
        return draw_millis;
    }

    /** Shapes the last frame left out, over its budget (the last one drawn may be cut short). */
    int skipped() {
        return skipped;
    }

    /** The debug shader renderer, made on first use (on the GL thread) for the scene's matrix stacks. */
    private @NonNull DebugShaderRenderer shaderFor(@NonNull MatrixStack model_view, @NonNull MatrixStack projection) {
        DebugShaderRenderer current = shader;
        if (current != null && shader_model_view == model_view && shader_projection == projection) {
            return current;
        }
        if (current != null) {
            current.close();
        }
        DebugShaderRenderer made = new DebugShaderRenderer(new DebugMeshShader(), model_view, projection);
        shader = made;
        shader_model_view = model_view;
        shader_projection = projection;
        return made;
    }

    private void draw(@NonNull DebugShaderRenderer renderer, OverlayPaint.@NonNull Shape shape) {
        red = (shape.rgb() >> 16 & 0xFF) / 255f;
        green = (shape.rgb() >> 8 & 0xFF) / 255f;
        blue = (shape.rgb() & 0xFF) / 255f;
        float x0 = UnitGrid.coordinateFromGrid(shape.x0());
        float y0 = UnitGrid.coordinateFromGrid(shape.y0());
        switch (shape.kind()) {
            case MARK -> {
                float ground = height(x0, y0);
                segment(renderer, x0, y0, ground, x0, y0, ground + PIN);
                circle(renderer, x0, y0, MARK_RING);
            }
            case LINE -> {
                float x1 = UnitGrid.coordinateFromGrid(shape.x1());
                float y1 = UnitGrid.coordinateFromGrid(shape.y1());
                float length = (float) Math.hypot(x1 - x0, y1 - y0);
                int steps = Math.clamp((int) Math.ceil(length / STEP), 1, MAX_LINE_STEPS);
                float px = x0;
                float py = y0;
                float pz = height(px, py) + LIFT;
                for (int i = 1; i <= steps; i++) {
                    float x = x0 + (x1 - x0) * i / steps;
                    float y = y0 + (y1 - y0) * i / steps;
                    float z = height(x, y) + LIFT;
                    segment(renderer, px, py, pz, x, y, z);
                    px = x;
                    py = y;
                    pz = z;
                }
            }
            case CIRCLE -> circle(renderer, x0, y0, shape.radius() * HeightMap.METERS_PER_UNIT_GRID);
        }
    }

    /** A circle of {@code radius} meters on the ground around (x, y). */
    private void circle(@NonNull DebugShaderRenderer renderer, float x, float y, float radius) {
        int steps = Math.clamp((int) Math.ceil(2 * Math.PI * radius / STEP), MIN_CIRCLE_STEPS, MAX_CIRCLE_STEPS);
        float px = x + radius;
        float py = y;
        float pz = height(px, py) + LIFT;
        for (int i = 1; i <= steps; i++) {
            double angle = 2 * Math.PI * i / steps;
            float cx = x + radius * (float) Math.cos(angle);
            float cy = y + radius * (float) Math.sin(angle);
            float cz = height(cx, cy) + LIFT;
            segment(renderer, px, py, pz, cx, cy, cz);
            px = cx;
            py = cy;
            pz = cz;
        }
    }

    private float height(float x, float y) {
        HeightMap map = heights;
        return map == null ? 0f : surface(map, x, y);
    }

    /**
     * The height of the surface one sees at (x, y) meters: the ground, or the sea over it (a shape over water lies on
     * the water, not on the sea bottom); outside the map, at the nearest point on it.
     */
    static float surface(@NonNull HeightMap map, float x, float y) {
        float max = map.getMetersPerWorld() - .01f;
        float ground = map.getNearestHeight(Math.clamp(x, 0f, max), Math.clamp(y, 0f, max));
        return Math.max(ground, map.getSeaLevelMeters());
    }

    /**
     * A segment as a quad that faces the camera: its sides are offset across both the segment and the line of sight,
     * by half the width the segment's distance needs to look {@value #LINE_PIXELS} pixels wide.
     */
    private void segment(@NonNull DebugShaderRenderer renderer, float x0, float y0, float z0, float x1, float y1,
            float z1) {
        if (segments >= MAX_SEGMENTS) {
            return;
        }
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float vx = (x0 + x1) * .5f - cam_x;
        float vy = (y0 + y1) * .5f - cam_y;
        float vz = (z0 + z1) * .5f - cam_z;
        float sx = dy * vz - dz * vy;
        float sy = dz * vx - dx * vz;
        float sz = dx * vy - dy * vx;
        float side = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (side < 1e-6f) {
            return; // seen end on: a point
        }
        float distance = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        float half = Math.max(MIN_WIDTH, LINE_PIXELS * pixel_size * distance) * .5f / side;
        sx *= half;
        sy *= half;
        sz *= half;
        if (vertices + 6 > BATCH_VERTICES) {
            renderer.end();
            renderer.begin(GL11.GL_TRIANGLES);
            vertices = 0;
        }
        vertex(renderer, x0 - sx, y0 - sy, z0 - sz);
        vertex(renderer, x0 + sx, y0 + sy, z0 + sz);
        vertex(renderer, x1 + sx, y1 + sy, z1 + sz);
        vertex(renderer, x0 - sx, y0 - sy, z0 - sz);
        vertex(renderer, x1 + sx, y1 + sy, z1 + sz);
        vertex(renderer, x1 - sx, y1 - sy, z1 - sz);
        vertices += 6;
        segments++;
    }

    private void vertex(@NonNull DebugShaderRenderer renderer, float x, float y, float z) {
        renderer.vertex(x, y, z, red, green, blue, ALPHA);
    }
}
