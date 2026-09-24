package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.LandBuilding;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the best legal building site in a square around a point. Candidates are pre-filtered with the O(1)
 * build-grid test and scored; the occupancy legality check only runs on cells that would improve the result.
 */
final class SiteFinder {
    interface Scorer {
        /** Higher is better; return Float.NEGATIVE_INFINITY to reject the cell. */
        float score(int x, int y);
    }

    /** A footprint that is planned (ordered) but not yet placed on the grid. */
    record Reservation(int x, int y, int size) {
    }

    private final @NonNull MapAnalysis map;
    private final List<Reservation> reservations = new ArrayList<>();

    SiteFinder(@NonNull MapAnalysis map) {
        this.map = map;
    }

    void clearReservations() {
        reservations.clear();
    }

    void reserve(int x, int y, int size) {
        reservations.add(new Reservation(x, y, size));
    }

    void unreserve(int x, int y) {
        reservations.removeIf(r -> r.x() == x && r.y() == y);
    }

    boolean isReserved(int x, int y, int size) {
        for (Reservation r : reservations) {
            if (MapAnalysis.chebyshev(r.x(), r.y(), x, y) < r.size() + size - 1)
                return true;
        }
        return false;
    }

    /** Strict (order-time) legality plus our own reservations. */
    boolean isLegal(@NonNull BuildingTemplate template, int x, int y) {
        int size = template.getPlacingSize();
        if (!map.inside(x, y) || !map.heightmap.canBuild(x, y, size))
            return false;
        if (isReserved(x, y, size))
            return false;
        return LandBuilding.isPlacingLegal(map.grid, template, x, y);
    }

    /**
     * Two-pass search for large radii: score every stride-th cell, then search around the best coarse cells at
     * full resolution. Scorers are expensive for armory sites, so this keeps a 170-cell search cheap.
     */
    int @Nullable [] bestCoarse(@NonNull BuildingTemplate template, int cx, int cy, int radius, int stride,
            @NonNull Scorer scorer) {
        return bestCoarse(template, cx, cy, radius, stride, scorer, scorer);
    }

    /**
     * As above; the finalists (one local best per coarse candidate) are ranked by refine, an expensive but more
     * accurate scorer called at most 12 times.
     */
    int @Nullable [] bestCoarse(@NonNull BuildingTemplate template, int cx, int cy, int radius, int stride,
            @NonNull Scorer scorer, @NonNull Scorer refine) {
        int size = template.getPlacingSize();
        final int keep = 12;
        float[] scores = new float[keep];
        int[] xs = new int[keep];
        int[] ys = new int[keep];
        java.util.Arrays.fill(scores, Float.NEGATIVE_INFINITY);
        int x0 = Math.max(size, cx - radius);
        int y0 = Math.max(size, cy - radius);
        int x1 = Math.min(map.n - 1 - size, cx + radius);
        int y1 = Math.min(map.n - 1 - size, cy + radius);
        for (int y = y0; y <= y1; y += stride) {
            for (int x = x0; x <= x1; x += stride) {
                if (!map.heightmap.canBuild(x, y, size))
                    continue;
                float s = scorer.score(x, y);
                if (s == Float.NEGATIVE_INFINITY || Float.isNaN(s) || s <= scores[keep - 1])
                    continue;
                int pos = keep - 1;
                while (pos > 0 && scores[pos - 1] < s) {
                    scores[pos] = scores[pos - 1];
                    xs[pos] = xs[pos - 1];
                    ys[pos] = ys[pos - 1];
                    pos--;
                }
                scores[pos] = s;
                xs[pos] = x;
                ys[pos] = y;
            }
        }
        int[] result = null;
        float result_score = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < keep; i++) {
            if (scores[i] == Float.NEGATIVE_INFINITY)
                break;
            int[] local = best(template, xs[i], ys[i], stride + 2, scorer);
            if (local == null)
                continue;
            float s = refine.score(local[0], local[1]);
            if (s > result_score) {
                result_score = s;
                result = local;
            }
        }
        if (result == null)
            result = best(template, cx, cy, radius, scorer);
        return result;
    }

    /**
     * The best-scoring legal site in the square. Legality (81 occupancy reads for quarters/armory) is only
     * checked for cells that would beat the best legal site so far, so the search never comes back empty while a
     * legal site exists, even when every high-scoring cell hugs trees that make it illegal.
     */
    int @Nullable [] best(@NonNull BuildingTemplate template, int cx, int cy, int radius, @NonNull Scorer scorer) {
        int size = template.getPlacingSize();
        int x0 = Math.max(size, cx - radius);
        int y0 = Math.max(size, cy - radius);
        int x1 = Math.min(map.n - 1 - size, cx + radius);
        int y1 = Math.min(map.n - 1 - size, cy + radius);
        float best_score = Float.NEGATIVE_INFINITY;
        int best_x = -1;
        int best_y = -1;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (!map.heightmap.canBuild(x, y, size))
                    continue;
                float s = scorer.score(x, y);
                if (s == Float.NEGATIVE_INFINITY || Float.isNaN(s) || s <= best_score)
                    continue;
                if (isReserved(x, y, size) || !LandBuilding.isPlacingLegal(map.grid, template, x, y))
                    continue;
                best_score = s;
                best_x = x;
                best_y = y;
            }
        }
        return best_x < 0 ? null : new int[]{best_x, best_y};
    }

    /**
     * The legal site with the best refine score among the k best legal sites by scorer, spaced at least 3 cells
     * apart so the finalists are not all one spot; for scorers too expensive to call on every cell.
     */
    int @Nullable [] bestRefined(@NonNull BuildingTemplate template, int cx, int cy, int radius,
            @NonNull Scorer scorer, @NonNull Scorer refine, int k) {
        int size = template.getPlacingSize();
        int x0 = Math.max(size, cx - radius);
        int y0 = Math.max(size, cy - radius);
        int x1 = Math.min(map.n - 1 - size, cx + radius);
        int y1 = Math.min(map.n - 1 - size, cy + radius);
        float[] scores = new float[k];
        int[] xs = new int[k];
        int[] ys = new int[k];
        java.util.Arrays.fill(scores, Float.NEGATIVE_INFINITY);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (!map.heightmap.canBuild(x, y, size))
                    continue;
                float s = scorer.score(x, y);
                if (s == Float.NEGATIVE_INFINITY || Float.isNaN(s) || s <= scores[k - 1])
                    continue;
                // A better finalist within 3 cells makes this one redundant; a worse one is replaced.
                int near = -1;
                for (int i = 0; i < k && scores[i] != Float.NEGATIVE_INFINITY; i++) {
                    if (MapAnalysis.chebyshev(xs[i], ys[i], x, y) < 3) {
                        near = i;
                        break;
                    }
                }
                if (near >= 0 && scores[near] >= s)
                    continue;
                if (isReserved(x, y, size) || !LandBuilding.isPlacingLegal(map.grid, template, x, y))
                    continue;
                int pos = near >= 0 ? near : k - 1;
                while (pos > 0 && scores[pos - 1] < s) {
                    scores[pos] = scores[pos - 1];
                    xs[pos] = xs[pos - 1];
                    ys[pos] = ys[pos - 1];
                    pos--;
                }
                scores[pos] = s;
                xs[pos] = x;
                ys[pos] = y;
            }
        }
        int[] result = null;
        float result_score = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < k && scores[i] != Float.NEGATIVE_INFINITY; i++) {
            float r = refine.score(xs[i], ys[i]);
            if (r > result_score || result == null) {
                result_score = r;
                result = new int[]{xs[i], ys[i]};
            }
        }
        return result;
    }
}
