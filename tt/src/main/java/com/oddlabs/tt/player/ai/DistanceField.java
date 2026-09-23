package com.oddlabs.tt.player.ai;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Walking distances in meters from a source cell to every reachable cell of the unit grid, as computed by
 * {@link MapAnalysis#computeField}. Straight grid steps cost 2 and diagonal steps 3, so the values are close to meters.
 */
final class DistanceField {
    static final int UNREACHABLE = Integer.MAX_VALUE;

    private final int size;
    private final int @NonNull [] cost;
    private final int source_x;
    private final int source_y;

    DistanceField(int size, int source_x, int source_y) {
        this.size = size;
        this.source_x = source_x;
        this.source_y = source_y;
        this.cost = new int[size * size];
        Arrays.fill(cost, UNREACHABLE);
    }

    int getSize() {
        return size;
    }

    int getSourceX() {
        return source_x;
    }

    int getSourceY() {
        return source_y;
    }

    int[] raw() {
        return cost;
    }

    int get(int x, int y) {
        if (x < 0 || y < 0 || x >= size || y >= size)
            return UNREACHABLE;
        return cost[y * size + x];
    }

    boolean reachable(int x, int y) {
        return get(x, y) != UNREACHABLE;
    }

    /**
     * Distance to a blocked cell such as a supply or building: the cheapest of the cells around it.
     */
    int getAround(int x, int y, int radius) {
        int best = get(x, y);
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dy == 0)
                    continue;
                int c = get(x + dx, y + dy);
                if (c < best)
                    best = c;
            }
        }
        return best;
    }

    /**
     * Follows the steepest descent from (x, y) towards the source for up to max_meters, returning the grid cell
     * reached as {x, y}. Used to walk an army along the real path instead of a straight line.
     */
    int @NonNull [] stepTowardsSource(int x, int y, int max_meters) {
        int cx = x;
        int cy = y;
        int start = get(cx, cy);
        if (start == UNREACHABLE) {
            int[] near = nearestReachable(cx, cy, 12);
            if (near == null)
                return new int[]{x, y};
            cx = near[0];
            cy = near[1];
            start = get(cx, cy);
        }
        int target = Math.max(0, start - max_meters);
        while (get(cx, cy) > target) {
            int best = get(cx, cy);
            int bx = cx;
            int by = cy;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int c = get(cx + dx, cy + dy);
                    if (c < best) {
                        best = c;
                        bx = cx + dx;
                        by = cy + dy;
                    }
                }
            }
            if (bx == cx && by == cy)
                break;
            cx = bx;
            cy = by;
        }
        return new int[]{cx, cy};
    }

    int @Nullable [] nearestReachable(int x, int y, int max_radius) {
        if (reachable(x, y))
            return new int[]{x, y};
        for (int r = 1; r <= max_radius; r++) {
            int best = UNREACHABLE;
            int[] result = null;
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.abs(dx) != r && Math.abs(dy) != r)
                        continue;
                    int c = get(x + dx, y + dy);
                    if (c < best) {
                        best = c;
                        result = new int[]{x + dx, y + dy};
                    }
                }
            }
            if (result != null)
                return result;
        }
        return null;
    }
}
