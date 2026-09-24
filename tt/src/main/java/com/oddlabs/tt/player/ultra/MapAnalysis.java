package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.pathfinder.Occupant;
import com.oddlabs.tt.pathfinder.Region;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Static and slowly changing knowledge about the map: walking distance fields, tree density, supply lists.
 * All arrays are indexed y * n + x. Walking cost is 2 per straight step and 3 per diagonal step (about 1 cost
 * unit per metre), over the static terrain access grid.
 */
final class MapAnalysis {
    static final int UNREACHABLE = Integer.MAX_VALUE;

    final int n;
    final @NonNull UnitGrid grid;
    final @NonNull HeightMap heightmap;
    private final boolean @NonNull [] @NonNull [] access;
    private final @NonNull List<Region> regions;

    final int home_x;
    final int home_y;
    final int enemy_x;
    final int enemy_y;
    final int @NonNull [] dist_home;
    final int @NonNull [] dist_enemy;

    private final int @NonNull [] tree_sat;
    private float tree_sat_time = Float.NEGATIVE_INFINITY;

    MapAnalysis(@NonNull Player owner) {
        this.grid = owner.getWorld().getUnitGrid();
        this.heightmap = grid.getHeightMap();
        this.n = grid.getGridSize();
        this.access = heightmap.getAccessGrid();
        this.home_x = clamp(UnitGrid.toGridCoordinate(owner.getStartX()));
        this.home_y = clamp(UnitGrid.toGridCoordinate(owner.getStartY()));
        Player enemy = nearestEnemy(owner);
        if (enemy != null) {
            this.enemy_x = clamp(UnitGrid.toGridCoordinate(enemy.getStartX()));
            this.enemy_y = clamp(UnitGrid.toGridCoordinate(enemy.getStartY()));
        } else {
            this.enemy_x = n - 1 - home_x;
            this.enemy_y = n - 1 - home_y;
        }
        this.dist_home = distanceField(home_x, home_y);
        this.dist_enemy = distanceField(enemy_x, enemy_y);
        this.tree_sat = new int[(n + 1) * (n + 1)];

        LinkedHashSet<Region> region_set = new LinkedHashSet<>();
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                Region r = grid.getRegion(x, y);
                if (r != null)
                    region_set.add(r);
            }
        }
        this.regions = new ArrayList<>(region_set);
    }

    private static @Nullable Player nearestEnemy(@NonNull Player owner) {
        Player best = null;
        float best_d = Float.MAX_VALUE;
        for (Player p : owner.getWorld().getPlayers()) {
            if (!owner.isEnemy(p))
                continue;
            float dx = p.getStartX() - owner.getStartX();
            float dy = p.getStartY() - owner.getStartY();
            float d = dx * dx + dy * dy;
            if (d < best_d) {
                best_d = d;
                best = p;
            }
        }
        return best;
    }

    int clamp(int g) {
        return Math.max(1, Math.min(n - 2, g));
    }

    boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < n && y < n;
    }

    boolean isAccessible(int x, int y) {
        return inside(x, y) && access[y][x];
    }

    int homeDist(int x, int y) {
        return inside(x, y) ? dist_home[y * n + x] : UNREACHABLE;
    }

    int enemyDist(int x, int y) {
        return inside(x, y) ? dist_enemy[y * n + x] : UNREACHABLE;
    }

    /**
     * How far toward the enemy a cell is, 0 at home and 1 at the enemy start, by walking distance. Cells that are
     * unreachable report 0.5.
     */
    float frontness(int x, int y) {
        int h = homeDist(x, y);
        int e = enemyDist(x, y);
        if (h == UNREACHABLE || e == UNREACHABLE || h + e == 0)
            return 0.5f;
        return h / (float) (h + e);
    }

    /** Dijkstra (Dial's buckets) over the terrain access grid, 2 per straight and 3 per diagonal step. */
    int @NonNull [] distanceField(int src_x, int src_y) {
        return distanceField(src_x, src_y, Integer.MAX_VALUE, null);
    }

    /**
     * As above, but stops once every cell up to max_cost is settled (cells beyond stay UNREACHABLE) and reuses
     * the given buffer when it has the right size.
     */
    int @NonNull [] distanceField(int src_x, int src_y, int max_cost, int @Nullable [] reuse) {
        int[] dist = reuse != null && reuse.length == n * n ? reuse : new int[n * n];
        java.util.Arrays.fill(dist, UNREACHABLE);
        int[] start = nearestAccessible(src_x, src_y);
        if (start == null)
            return dist;
        IntBuckets buckets = new IntBuckets(4);
        int s = start[1] * n + start[0];
        dist[s] = 0;
        buckets.add(0, s);
        int cur = 0;
        int pending = 1;
        while (pending > 0) {
            int idx = buckets.poll(cur);
            if (idx < 0) {
                cur++;
                continue;
            }
            pending--;
            int d = dist[idx];
            if (d != cur)
                continue;
            if (d > max_cost)
                break;
            int x = idx % n;
            int y = idx / n;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0)
                        continue;
                    int nx = x + dx;
                    int ny = y + dy;
                    if (!isAccessible(nx, ny))
                        continue;
                    int nd = d + (dx != 0 && dy != 0 ? 3 : 2);
                    int nidx = ny * n + nx;
                    if (nd < dist[nidx]) {
                        dist[nidx] = nd;
                        buckets.add(nd, nidx);
                        pending++;
                    }
                }
            }
        }
        return dist;
    }

    int @Nullable [] nearestAccessible(int x, int y) {
        for (int r = 0; r < n; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r)
                        continue;
                    if (isAccessible(x + dx, y + dy))
                        return new int[]{x + dx, y + dy};
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Trees

    /** Rebuilds the tree summed-area table if it is older than max_age game seconds. */
    void refreshTrees(float now, float max_age) {
        if (now - tree_sat_time < max_age)
            return;
        tree_sat_time = now;
        int w = n + 1;
        for (int y = 0; y < n; y++) {
            int row = 0;
            for (int x = 0; x < n; x++) {
                if (grid.getOccupant(x, y) instanceof TreeSupply)
                    row++;
                tree_sat[(y + 1) * w + (x + 1)] = tree_sat[y * w + (x + 1)] + row;
            }
        }
    }

    /** Number of standing trees in the square [x-r, x+r] x [y-r, y+r]. */
    int treesAround(int x, int y, int r) {
        int x0 = Math.max(0, x - r);
        int y0 = Math.max(0, y - r);
        int x1 = Math.min(n - 1, x + r);
        int y1 = Math.min(n - 1, y + r);
        if (x0 > x1 || y0 > y1)
            return 0;
        int w = n + 1;
        return tree_sat[(y1 + 1) * w + (x1 + 1)] - tree_sat[y0 * w + (x1 + 1)] - tree_sat[(y1 + 1) * w + x0] + tree_sat[y0 * w + x0];
    }

    // ---------------------------------------------------------------------------------------------------------
    // Supplies

    @NonNull
    List<IronSupply> ironSupplies() {
        return collect(IronSupply.class);
    }

    @NonNull
    List<RockSupply> rockSupplies() {
        return collect(RockSupply.class);
    }

    /** Chickens that have not been hit yet (hit chickens are already being harvested). */
    @NonNull
    List<RubberSupply> freeChickens() {
        List<RubberSupply> all = collect(RubberSupply.class);
        List<RubberSupply> result = new ArrayList<>(all.size());
        for (RubberSupply c : all) {
            if (!c.isDead() && !c.isHit())
                result.add(c);
        }
        return result;
    }

    private <S> @NonNull List<S> collect(@NonNull Class<S> type) {
        List<S> result = new ArrayList<>();
        for (Region r : regions) {
            for (Object o : r.getObjects(type)) {
                if (type.isInstance(o) && !((Occupant) o).isDead())
                    result.add(type.cast(o));
            }
        }
        return result;
    }

    /** The static height in metres at a grid cell (bounds checked). */
    float height(int x, int y) {
        if (!inside(x, y))
            return 0f;
        return heightmap.getHeight(x, y);
    }

    static int octile(int dx, int dy) {
        int ax = Math.abs(dx);
        int ay = Math.abs(dy);
        return 2 * Math.max(ax, ay) + Math.min(ax, ay);
    }

    static int dist2(int ax, int ay, int bx, int by) {
        int dx = ax - bx;
        int dy = ay - by;
        return dx * dx + dy * dy;
    }

    static int chebyshev(int ax, int ay, int bx, int by) {
        return Math.max(Math.abs(ax - bx), Math.abs(ay - by));
    }

    /** Minimal growable int buckets for Dial's algorithm (costs modulo the bucket count). */
    private static final class IntBuckets {
        private final int[][] data;
        private final int[] size;

        IntBuckets(int count) {
            data = new int[count][64];
            size = new int[count];
        }

        void add(int cost, int value) {
            int b = cost % data.length;
            if (size[b] == data[b].length)
                data[b] = java.util.Arrays.copyOf(data[b], size[b] * 2);
            data[b][size[b]++] = value;
        }

        int poll(int cost) {
            int b = cost % data.length;
            if (size[b] == 0)
                return -1;
            return data[b][--size[b]];
        }
    }
}
