package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The enemy warriors, chieftains and peons of one tick, bucketed by 16-cell squares, so that towers look only at the
 * enemies near them instead of every enemy unit (most of the AI's CPU went to that). A query returns the units in
 * range in their list order (warriors, chieftains, peons, each in Intel's order), so a scan over the result picks
 * exactly what a scan over the full lists would.
 */
final class EnemyIndex {
    private static final int SHIFT = 4;

    private final int side;
    private final List<@NonNull Unit> units = new ArrayList<>();
    private int[] xs = new int[256];
    private int[] ys = new int[256];
    private boolean[] peon = new boolean[256];
    private final int[][] buckets;
    private final int[] bucket_sizes;
    private int[] result = new int[64];

    EnemyIndex(int map_size) {
        side = (map_size >> SHIFT) + 1;
        buckets = new int[side * side][];
        bucket_sizes = new int[side * side];
    }

    /** Rebuilds the index from the lists as they stand now. */
    void rebuild(@NonNull List<@NonNull Unit> warriors, @NonNull List<@NonNull Unit> chieftains,
            @NonNull List<@NonNull Unit> peons) {
        units.clear();
        Arrays.fill(bucket_sizes, 0);
        add(warriors, false);
        add(chieftains, false);
        add(peons, true);
    }

    private void add(@NonNull List<@NonNull Unit> group, boolean is_peon) {
        for (Unit u : group) {
            int i = units.size();
            if (i == xs.length) {
                xs = Arrays.copyOf(xs, i * 2);
                ys = Arrays.copyOf(ys, i * 2);
                peon = Arrays.copyOf(peon, i * 2);
            }
            units.add(u);
            int x = u.getGridX();
            int y = u.getGridY();
            xs[i] = x;
            ys[i] = y;
            peon[i] = is_peon;
            int b = bucket(x, y);
            int[] list = buckets[b];
            if (list == null) {
                list = new int[8];
                buckets[b] = list;
            } else if (bucket_sizes[b] == list.length) {
                list = Arrays.copyOf(list, list.length * 2);
                buckets[b] = list;
            }
            list[bucket_sizes[b]++] = i;
        }
    }

    private int bucket(int x, int y) {
        int bx = Math.clamp(x >> SHIFT, 0, side - 1);
        int by = Math.clamp(y >> SHIFT, 0, side - 1);
        return by * side + bx;
    }

    /**
     * The indices of the units with dist2 from (x, y) at most r2, in list order. The array is reused by the next query;
     * the count is returned by {@link #count()}.
     */
    int @NonNull [] query(int x, int y, int r2) {
        int r = (int) Math.ceil(Math.sqrt(r2));
        int bx0 = Math.clamp((x - r) >> SHIFT, 0, side - 1);
        int bx1 = Math.clamp((x + r) >> SHIFT, 0, side - 1);
        int by0 = Math.clamp((y - r) >> SHIFT, 0, side - 1);
        int by1 = Math.clamp((y + r) >> SHIFT, 0, side - 1);
        int n = 0;
        for (int by = by0; by <= by1; by++)
            for (int bx = bx0; bx <= bx1; bx++) {
                int b = by * side + bx;
                int[] list = buckets[b];
                for (int k = 0; k < bucket_sizes[b]; k++) {
                    int i = list[k];
                    int dx = xs[i] - x;
                    int dy = ys[i] - y;
                    if (dx * dx + dy * dy > r2)
                        continue;
                    if (n == result.length)
                        result = Arrays.copyOf(result, n * 2);
                    result[n++] = i;
                }
            }
        Arrays.sort(result, 0, n);
        count = n;
        return result;
    }

    private int count;

    int count() {
        return count;
    }

    @NonNull
    Unit unit(int i) {
        return units.get(i);
    }

    boolean isPeon(int i) {
        return peon[i];
    }
}
