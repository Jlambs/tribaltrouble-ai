package com.oddlabs.tt.util;

import com.oddlabs.tt.landscape.HeightMap;
import org.jspecify.annotations.NonNull;

/**
 * Cubic Bezier curve path for smooth unit movement.
 * Uses four control points to generate smooth curves between waypoints.
 * Debug visualization shows the curve as a white line (enable with UNIT_GRID mode).
 */
public final class BezierPath {
    // The four control points, PREVIOUS, START, END and NEXT, and the current point and direction, are fields rather
    // than arrays: every moving unit computes a curve point every tick, and one object is cheaper to reach than seven.
    private float previous_x;
    private float previous_y;
    private float start_x;
    private float start_y;
    private float end_x;
    private float end_y;
    private float next_x;
    private float next_y;
    private float current_x;
    private float current_y;
    private float current_dir_x;
    private float current_dir_y;
    private float dt;
    private float t;

    public BezierPath() {
        initState();
    }

    private void initState() {
        t = 1f;
    }

    public boolean isDone() {
        return t >= 1f;
    }

    public void computeCurvePoint(float speed) {
        assert !isDone();
        computeCurvePointFromTime(t);
        t += dt * speed;
    }

    public void dumpPoints() {
        float[][] points = {{previous_x, previous_y}, {start_x, start_y}, {end_x, end_y}, {next_x, next_y}};
        for (float[] point : points) {
            System.out.println("points[i][0] = " + point[0] + " | points[i][1] = " + point[1]);
        }
    }

    /** Sets the current point and direction to the curve's at {@code t}. */
    private void computeCurvePointFromTime(float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        float b0 = 1 - 3 * t + 3 * t2 - t3;
        float b1 = 3 * t3 - 6 * t2 + 4;
        float b2 = -3 * t3 + 3 * t2 + 3 * t + 1;
        float b3 = t3;
        current_x = (1f / 6f) * (previous_x * b0 + start_x * b1 + end_x * b2 + next_x * b3);
        current_y = (1f / 6f) * (previous_y * b0 + start_y * b1 + end_y * b2 + next_y * b3);

        float db0 = -3 + 6 * t - 3 * t2;
        float db1 = 9 * t2 - 12 * t;
        float db2 = -9 * t2 + 6 * t + 3;
        float db3 = 3 * t2;
        float dx = (1f / 6f) * (previous_x * db0 + start_x * db1 + end_x * db2 + next_x * db3);
        float dy = (1f / 6f) * (previous_y * db0 + start_y * db1 + end_y * db2 + next_y * db3);
        float dir_len_inv = 1f / (float) Math.sqrt(dx * dx + dy * dy);
        current_dir_x = dx * dir_len_inv;
        current_dir_y = dy * dir_len_inv;
        if (Float.isNaN(current_dir_x) || Float.isNaN(current_dir_y)) {
            current_dir_x = 1f;
            current_dir_y = 0f;
        }
    }

    public float getCurrentDirectionX() {
        return current_dir_x;
    }

    public float getCurrentDirectionY() {
        return current_dir_y;
    }

    public float getCurrentX() {
        return current_x;
    }

    public float getCurrentY() {
        return current_y;
    }

    public void init(float inv_length, float x1, float y1, float x2, float y2) {
        assert x1 != x2 || y1 != y2 : x1 + " " + y1;
        float extrapolated_x = x1 + (x1 - x2);
        float extrapolated_y = y1 + (y1 - y2);
        previous_x = extrapolated_x;
        previous_y = extrapolated_y;
        start_x = extrapolated_x;
        start_y = extrapolated_y;
        end_x = x1;
        end_y = y1;
        next_x = x2;
        next_y = y2;
        initState();
        dt = inv_length;
    }

    public void nextPoint(float inv_length, float x, float y) {
        assert x != next_x || y != next_y : x + " " + y;
        cyclePoints();
        next_x = x;
        next_y = y;
        t -= 1f;
        dt = inv_length;
    }

    public float getNextX() {
        return next_x;
    }

    public float getNextY() {
        return next_y;
    }

    private void cyclePoints() {
        previous_x = start_x;
        previous_y = start_y;
        start_x = end_x;
        start_y = end_y;
        end_x = next_x;
        end_y = next_y;
    }

    public void endPath() {
        float next_x = this.next_x + (this.next_x - end_x);
        float next_y = this.next_y + (this.next_y - end_y);
        nextPoint(dt, next_x, next_y);
    }

    public void debugRender(@NonNull HeightMap heightmap) {
        // drawing samples the curve through the current point and direction, so keep them
        float saved_x = current_x;
        float saved_y = current_y;
        float saved_dir_x = current_dir_x;
        float saved_dir_y = current_dir_y;
        float prev_x = 0, prev_y = 0, prev_z = 0;
        boolean first = true;
        for (float t = 0f; t < 1f; t += .01f) {
            computeCurvePointFromTime(t);
            float x = current_x;
            float y = current_y;
            float z = heightmap.getNearestHeight(x, y) + 0.5f;
            if (!first) {
                DebugRender.drawLine(prev_x, prev_y, prev_z, x, y, z, 1f, 1f, 1f);
            }
            prev_x = x;
            prev_y = y;
            prev_z = z;
            first = false;
        }
        current_x = saved_x;
        current_y = saved_y;
        current_dir_x = saved_dir_x;
        current_dir_y = saved_dir_y;
    }
}
