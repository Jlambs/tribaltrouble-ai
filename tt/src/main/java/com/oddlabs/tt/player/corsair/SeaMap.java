package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.pathfinder.UnitGrid;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;

/**
 * Sailing distances over the sea, on bins of {@value #BIN} cells: a bin is open water when its centre cell is water.
 * Distance fields from a point give the length (in cells) of the shortest sea route, round islands, to every bin.
 */
final class SeaMap {
    static final int BIN = 4;
    static final int FAR = Integer.MAX_VALUE / 4;
    private final int n;
    private final boolean @NonNull [] open;

    SeaMap(@NonNull UnitGrid grid) {
        int size = grid.getGridSize();
        n = (size + BIN - 1) / BIN;
        open = new boolean[n * n];
        for (int by = 0; by < n; by++)
            for (int bx = 0; bx < n; bx++) {
                int x = Math.min(size - 1, bx * BIN + BIN / 2), y = Math.min(size - 1, by * BIN + BIN / 2);
                open[by * n + bx] = grid.isWater(x, y);
            }
    }

    /** Sea distances in cells from the open bin nearest (gx, gy), by Dijkstra with 8 neighbours. */
    int @NonNull [] field(int gx, int gy) {
        int[] dist = new int[n * n];
        Arrays.fill(dist, FAR);
        int start = nearestOpen(gx / BIN, gy / BIN);
        if (start < 0)
            return dist;
        // bucket queue on integer costs: 4 per straight step, 6 per diagonal (cells, rounded)
        int[] heap = new int[n * n * 4 + 16];
        int[] keys = new int[heap.length];
        int size = 0;
        dist[start] = 0;
        heap[size] = start;
        keys[size++] = 0;
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dy = {0, 0, 1, -1, 1, -1, 1, -1};
        int[] cost = {4, 4, 4, 4, 6, 6, 6, 6};
        while (size > 0) {
            // pop min
            int node = heap[0], key = keys[0];
            size--;
            heap[0] = heap[size];
            keys[0] = keys[size];
            siftDown(heap, keys, size, 0);
            if (key > dist[node])
                continue;
            int bx = node % n, by = node / n;
            for (int k = 0; k < 8; k++) {
                int x = bx + dx[k], y = by + dy[k];
                if (x < 0 || y < 0 || x >= n || y >= n)
                    continue;
                int m = y * n + x;
                if (!open[m])
                    continue;
                int nd = key + cost[k];
                if (nd < dist[m]) {
                    dist[m] = nd;
                    if (size >= heap.length)
                        continue;
                    heap[size] = m;
                    keys[size] = nd;
                    siftUp(heap, keys, size);
                    size++;
                }
            }
        }
        return dist;
    }

    private static void siftUp(int[] heap, int[] keys, int i) {
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (keys[parent] <= keys[i])
                break;
            swap(heap, keys, i, parent);
            i = parent;
        }
    }

    private static void siftDown(int[] heap, int[] keys, int size, int i) {
        while (true) {
            int l = 2 * i + 1, r = l + 1, m = i;
            if (l < size && keys[l] < keys[m])
                m = l;
            if (r < size && keys[r] < keys[m])
                m = r;
            if (m == i)
                return;
            swap(heap, keys, i, m);
            i = m;
        }
    }

    private static void swap(int[] heap, int[] keys, int a, int b) {
        int t = heap[a];
        heap[a] = heap[b];
        heap[b] = t;
        t = keys[a];
        keys[a] = keys[b];
        keys[b] = t;
    }

    private int nearestOpen(int bx, int by) {
        for (int r = 0; r < n; r++)
            for (int y = by - r; y <= by + r; y++)
                for (int x = bx - r; x <= bx + r; x++) {
                    if (Math.max(Math.abs(x - bx), Math.abs(y - by)) != r || x < 0 || y < 0 || x >= n || y >= n)
                        continue;
                    if (open[y * n + x])
                        return y * n + x;
                }
        return -1;
    }

    /** Sea distance from the field's source to the water next to the cell (a shore cell, say), or FAR. */
    int to(int @NonNull [] field, int gx, int gy) {
        int bx = gx / BIN, by = gy / BIN;
        int best = FAR;
        for (int y = by - 2; y <= by + 2; y++)
            for (int x = bx - 2; x <= bx + 2; x++) {
                if (x < 0 || y < 0 || x >= n || y >= n)
                    continue;
                int d = field[y * n + x];
                if (d >= FAR)
                    continue;
                d += BIN * Math.max(Math.abs(x - bx), Math.abs(y - by));
                if (d < best)
                    best = d;
            }
        return best;
    }
}
