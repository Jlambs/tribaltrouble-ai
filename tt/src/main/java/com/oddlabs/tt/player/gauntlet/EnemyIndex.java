package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.List;

/**
 * The enemy warriors, chieftains and peons of one world tick, bucketed by 16-cell squares, so that towers and shepherds
 * look only at the enemies near them instead of every enemy unit (most of the AI's CPU went to that). A query returns
 * the units in range in their list order (warriors, chieftains, peons, each in Intel's order), so a scan over the
 * result picks exactly what a scan over the full lists would. Dead units stay in the index, as they stay in Intel's
 * lists until its next update: queries leave the isDead test to the caller.
 */
final class EnemyIndex {
    static final byte WARRIOR = 0;
    static final byte CHIEFTAIN = 1;
    static final byte PEON = 2;

    private static final int SHIFT = 4;

    private final int side;
    private Unit[] units = new Unit[256];
    private int size;
    private int[] xs = new int[256];
    private int[] ys = new int[256];
    private byte[] groups = new byte[256];
    /** Per unit, its bucket and its slot in that bucket's list. */
    private int[] bucket_of = new int[256];
    private int[] slot = new int[256];
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
        Arrays.fill(units, 0, size, null);
        size = 0;
        Arrays.fill(bucket_sizes, 0);
        add(warriors, WARRIOR);
        add(chieftains, CHIEFTAIN);
        add(peons, PEON);
    }

    /**
     * Brings the index up to where the same units stand now: only the units whose cell changed since the last rebuild
     * or refresh move to their new buckets (most units keep their cell from one world tick to the next). Queries then
     * find what they would after a rebuild from the same lists.
     */
    void refresh() {
        for (int i = 0; i < size; i++) {
            Unit u = units[i];
            int x = u.getGridX();
            int y = u.getGridY();
            if (x == xs[i] && y == ys[i])
                continue;
            xs[i] = x;
            ys[i] = y;
            int b = bucket(x, y);
            int old = bucket_of[i];
            if (b == old)
                continue;
            int[] list = buckets[old];
            int last = list[--bucket_sizes[old]];
            list[slot[i]] = last;
            slot[last] = slot[i];
            append(b, i);
        }
    }

    private void add(@NonNull List<@NonNull Unit> group, byte kind) {
        for (Unit u : group) {
            int i = size;
            if (i == xs.length) {
                units = Arrays.copyOf(units, i * 2);
                xs = Arrays.copyOf(xs, i * 2);
                ys = Arrays.copyOf(ys, i * 2);
                groups = Arrays.copyOf(groups, i * 2);
                bucket_of = Arrays.copyOf(bucket_of, i * 2);
                slot = Arrays.copyOf(slot, i * 2);
            }
            units[i] = u;
            size++;
            int x = u.getGridX();
            int y = u.getGridY();
            xs[i] = x;
            ys[i] = y;
            groups[i] = kind;
            append(bucket(x, y), i);
        }
    }

    private void append(int b, int i) {
        int[] list = buckets[b];
        if (list == null) {
            list = new int[8];
            buckets[b] = list;
        } else if (bucket_sizes[b] == list.length) {
            list = Arrays.copyOf(list, list.length * 2);
            buckets[b] = list;
        }
        bucket_of[i] = b;
        slot[i] = bucket_sizes[b];
        list[bucket_sizes[b]++] = i;
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
        return collect(x, y, r, r2, true);
    }

    /** As {@link #query}, in no particular order: for callers whose result does not depend on the order. */
    int @NonNull [] queryUnordered(int x, int y, int r2) {
        int r = (int) Math.ceil(Math.sqrt(r2));
        return collect(x, y, r, r2, false);
    }

    /**
     * The groups with a unit (dead ones included) at most c cells from (x, y) along both axes (a Chebyshev square), as
     * bits 1 << WARRIOR, 1 << CHIEFTAIN, 1 << PEON. It stops at the first warrior, so with the warrior bit set the
     * other bits are incomplete.
     */
    int groupsInBox(int x, int y, int c) {
        int bx0 = Math.clamp((x - c) >> SHIFT, 0, side - 1);
        int bx1 = Math.clamp((x + c) >> SHIFT, 0, side - 1);
        int by0 = Math.clamp((y - c) >> SHIFT, 0, side - 1);
        int by1 = Math.clamp((y + c) >> SHIFT, 0, side - 1);
        int mask = 0;
        for (int by = by0; by <= by1; by++)
            for (int bx = bx0; bx <= bx1; bx++) {
                int b = by * side + bx;
                int[] list = buckets[b];
                for (int k = 0; k < bucket_sizes[b]; k++) {
                    int i = list[k];
                    if (Math.abs(xs[i] - x) > c || Math.abs(ys[i] - y) > c)
                        continue;
                    if (groups[i] == WARRIOR)
                        return mask | 1 << WARRIOR;
                    mask |= 1 << groups[i];
                }
            }
        return mask;
    }

    private int @NonNull [] collect(int x, int y, int r, int r2, boolean ordered) {
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
        if (ordered)
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
        return units[i];
    }

    /** WARRIOR, CHIEFTAIN or PEON: the list unit i came from. */
    byte group(int i) {
        return groups[i];
    }

    boolean isPeon(int i) {
        return groups[i] == PEON;
    }
}
