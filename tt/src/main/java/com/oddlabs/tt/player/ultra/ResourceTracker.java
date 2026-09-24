package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.SupplyModel;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.pathfinder.Occupant;
import com.oddlabs.tt.pathfinder.ScanFilter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Live supply lists, how crowded each node is with our gatherers, and trip-time estimates. */
final class ResourceTracker {
    /** Gatherers per ore node before it counts as crowded. */
    private final int node_cap;
    static final int TREE_CAP = 2;

    private final @NonNull Context ctx;
    private List<IronSupply> iron = new ArrayList<>();
    private List<RockSupply> rock = new ArrayList<>();
    /** Our gatherers per supply node (lookup only). */
    private final Map<Supply, Integer> crowd = new HashMap<>();
    private float last_scan = Float.NEGATIVE_INFINITY;

    private final boolean path_aware;

    ResourceTracker(@NonNull Context ctx) {
        this.ctx = ctx;
        this.path_aware = ctx.strategy.b("pathAware");
        this.node_cap = ctx.strategy.i("nodeCap");
    }

    void update() {
        ctx.map.refreshTrees(ctx.now, 15f);
        if (ctx.now - last_scan >= 10f) {
            last_scan = ctx.now;
            iron = ctx.map.ironSupplies();
            rock = ctx.map.rockSupplies();
        }
        crowd.clear();
        for (Unit u : ctx.model.me.peons) {
            if (WorldModel.primary(u) instanceof GatherController<?> gc) {
                Supply s = gc.getSupply();
                if (s != null && !s.isDead())
                    crowd.merge(s, 1, Integer::sum);
            }
        }
    }

    int crowdOf(@NonNull Supply s) {
        Integer c = crowd.get(s);
        return c != null ? c : 0;
    }

    void noteAssigned(@NonNull Supply s) {
        crowd.merge(s, 1, Integer::sum);
    }

    @NonNull
    List<IronSupply> iron() {
        return iron;
    }

    @NonNull
    List<RockSupply> rock() {
        return rock;
    }

    /**
     * Units a node is assumed to hold. The exact amount left is not visible (nodes do not shrink), so every live
     * tree, rock and iron node counts as full (10 units, the engine's starting amount).
     */
    static int remaining(@NonNull Supply s) {
        if (s.isEmpty())
            return 0;
        return s instanceof TreeSupply || s instanceof SupplyModel ? 10 : 1;
    }

    /** One-way walking metres between the drop ring of a 7x7 building centred at (bx,by) and a node cell. */
    static int dropDistance(int bx, int by, int nx, int ny) {
        int dx = Math.max(0, Math.abs(nx - bx) - 5);
        int dy = Math.max(0, Math.abs(ny - by) - 5);
        return MapAnalysis.octile(dx, dy);
    }

    // Walking distances. The terrain access grid (slopes, water) can force long detours around cliffs that a
    // straight-line estimate misses entirely, so every drop-building distance goes through a Dijkstra field.

    /** Field reach in metres (2 per straight cell); anything farther counts as this far. */
    static final int FIELD_MAX = 2 * 160;
    private int @Nullable [] drop_field;
    private @Nullable Building field_building;
    private int @NonNull [] tree_walks = new int[0];
    private float tree_walks_time = Float.NEGATIVE_INFINITY;

    private int @NonNull [] fieldFor(@NonNull Building drop) {
        int[] f = drop_field;
        if (drop != field_building || f == null) {
            field_building = drop;
            f = ctx.map.distanceField(drop.getGridX(), drop.getGridY(), FIELD_MAX, drop_field);
            drop_field = f;
            tree_walks_time = Float.NEGATIVE_INFINITY;
        }
        return f;
    }

    /** Field value (metres) at the cell or its 8 neighbours, or UNREACHABLE. */
    int fieldAt(int @NonNull [] field, int x, int y) {
        int best = MapAnalysis.UNREACHABLE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (ctx.map.inside(x + dx, y + dy))
                    best = Math.min(best, field[(y + dy) * ctx.map.n + x + dx]);
            }
        }
        return best;
    }

    /** One-way walking metres from the drop ring of a 7x7 building centred at (bx,by) whose field is given. */
    int walkFrom(int @NonNull [] field, int bx, int by, int x, int y) {
        return walkFrom(field, FIELD_MAX, bx, by, x, y);
    }

    /** As above for a field computed up to max_cost metres; cells beyond count as that far. */
    int walkFrom(int @NonNull [] field, int max_cost, int bx, int by, int x, int y) {
        int straight = dropDistance(bx, by, x, y);
        if (!path_aware)
            return straight;
        int f = fieldAt(field, x, y);
        if (f == MapAnalysis.UNREACHABLE)
            return Math.max(straight, max_cost);
        return Math.max(straight, f - 10);
    }

    /** One-way walking metres between the drop building's ring and a cell, around cliffs and water. */
    int walkDistance(@NonNull Building drop, int x, int y) {
        return walkFrom(fieldFor(drop), drop.getGridX(), drop.getGridY(), x, y);
    }

    /**
     * Mean one-way walking metres from a building's drop ring to its k nearest tree cells by path (field from
     * the building centre). Missing trees count as 80 m.
     */
    float treeWalkPath(int @NonNull [] field, int cx, int cy, int k, int max_r) {
        int[] walks = treeWalks(field, cx, cy, max_r);
        return meanOfFirst(walks, walks.length, k);
    }

    private static float meanOfFirst(int @NonNull [] sorted, int n, int k) {
        float sum = 0f;
        int got = Math.min(n, k);
        for (int i = 0; i < got; i++)
            sum += sorted[i];
        sum += (k - got) * 80f;
        return sum / k;
    }

    private int @NonNull [] treeWalks(int @NonNull [] field, int cx, int cy, int max_r) {
        int[] buf = new int[64];
        int n = 0;
        for (int y = Math.max(0, cy - max_r); y <= Math.min(ctx.map.n - 1, cy + max_r); y++) {
            for (int x = Math.max(0, cx - max_r); x <= Math.min(ctx.map.n - 1, cx + max_r); x++) {
                if (ctx.map.treesAround(x, y, 0) == 0)
                    continue;
                if (n == buf.length)
                    buf = java.util.Arrays.copyOf(buf, n * 2);
                buf[n++] = Math.min(80, walkFrom(field, cx, cy, x, y));
            }
        }
        int[] out = java.util.Arrays.copyOf(buf, n);
        java.util.Arrays.sort(out);
        return out;
    }

    /** Tree walks around the armory by path, refreshed with the tree table. */
    private int @NonNull [] armoryTreeWalks(@NonNull Building drop) {
        int[] f = fieldFor(drop);
        if (ctx.now - tree_walks_time >= 15f) {
            tree_walks_time = ctx.now;
            tree_walks = treeWalks(f, drop.getGridX(), drop.getGridY(), 45);
        }
        return tree_walks;
    }

    /** Drained former armories: supplies nearer to one of them than to the drop building are not handed out. */
    private List<Building> avoid_drops = List.of();

    void setAvoidDrops(@NonNull List<Building> drops) {
        avoid_drops = drops;
    }

    /** True if the cell is nearer (straight line) to a drained armory than to the drop building at (bx,by). */
    boolean nearerToAvoided(int bx, int by, int x, int y) {
        if (avoid_drops.isEmpty())
            return false;
        int own = dropDistance(bx, by, x, y);
        for (Building b : avoid_drops) {
            if (!b.isDead() && dropDistance(b.getGridX(), b.getGridY(), x, y) <= own)
                return true;
        }
        return false;
    }

    // Bad spots: places where gatherers or builders got stuck (unreachable trees, jams behind our own
    // buildings). Supplies near one are skipped for a while.

    private static final int BAD_RADIUS = 5;
    private static final float BAD_TIME = 180f;
    private final List<float[]> bad_spots = new ArrayList<>();

    void markBad(int x, int y) {
        bad_spots.removeIf(b -> ctx.now >= b[2]);
        for (float[] b : bad_spots) {
            if (MapAnalysis.chebyshev((int) b[0], (int) b[1], x, y) <= 1) {
                b[2] = ctx.now + BAD_TIME;
                return;
            }
        }
        bad_spots.add(new float[]{x, y, ctx.now + BAD_TIME});
    }

    boolean isBad(int x, int y) {
        for (float[] b : bad_spots) {
            if (ctx.now < b[2] && MapAnalysis.chebyshev((int) b[0], (int) b[1], x, y) <= BAD_RADIUS)
                return true;
        }
        return false;
    }

    /** True if an enemy warrior or chieftain is within r cells of the cell. */
    boolean threatened(int x, int y, int r) {
        int r2 = r * r;
        Unit chief = ctx.model.enemy.chieftain;
        if (chief != null && MapAnalysis.dist2(chief.getGridX(), chief.getGridY(), x, y) <= r2)
            return true;
        // Enemy warriors bucketed on a coarse grid, rebuilt once per world-model refresh: only the buckets
        // overlapping the query square are scanned (the answer is the same as scanning every warrior).
        List<Unit> ws = ctx.model.enemy.warriors;
        if (threat_version != ctx.model.version || threat_list != ws || threat_size != ws.size())
            buildThreatGrid(ws);
        // Warriors may have moved since the grid was built (it is rebuilt on each world-model refresh): the query is
        // padded by more than they can walk in between, and the distance test reads their live positions.
        int pad = 2;
        int cx0 = Math.max(0, (x - r - pad) >> THREAT_SHIFT);
        int cy0 = Math.max(0, (y - r - pad) >> THREAT_SHIFT);
        int cx1 = Math.min(threat_dim - 1, (x + r + pad) >> THREAT_SHIFT);
        int cy1 = Math.min(threat_dim - 1, (y + r + pad) >> THREAT_SHIFT);
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int i = threat_head[cy * threat_dim + cx]; i >= 0; i = threat_next[i]) {
                    Unit e = ws.get(i);
                    if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), x, y) <= r2)
                        return true;
                }
            }
        }
        return false;
    }

    private static final int THREAT_SHIFT = 4;
    private int threat_dim;
    private int[] threat_head = new int[0];
    private int[] threat_next = new int[0];
    private int threat_version = -1;
    private @Nullable List<Unit> threat_list;
    private int threat_size = -1;

    private void buildThreatGrid(@NonNull List<Unit> ws) {
        threat_dim = (ctx.map.n >> THREAT_SHIFT) + 1;
        int cells = threat_dim * threat_dim;
        if (threat_head.length != cells)
            threat_head = new int[cells];
        java.util.Arrays.fill(threat_head, -1);
        if (threat_next.length < ws.size())
            threat_next = new int[Math.max(16, ws.size() * 2)];
        for (int i = 0; i < ws.size(); i++) {
            Unit e = ws.get(i);
            int cx = Math.max(0, Math.min(threat_dim - 1, e.getGridX() >> THREAT_SHIFT));
            int cy = Math.max(0, Math.min(threat_dim - 1, e.getGridY() >> THREAT_SHIFT));
            int c = cy * threat_dim + cx;
            threat_next[i] = threat_head[c];
            threat_head[c] = i;
        }
        threat_version = ctx.model.version;
        threat_list = ws;
        threat_size = ws.size();
    }

    /**
     * Gatherer slots (node_cap per node) on safe iron or rock nodes within reach metres of the drop building.
     * The reach grows to include at least min_nodes nodes, so production never stops when nearby ore runs out.
     */
    int slotsWithin(@NonNull Class<?> type, @NonNull Building drop, int reach, int min_nodes) {
        List<? extends Supply> list = type == IronSupply.class ? iron : rock;
        int[] dist = new int[list.size()];
        int n = 0;
        for (Supply s : list) {
            if (s.isDead() || threatened(s.getGridX(), s.getGridY(), 12))
                continue;
            dist[n++] = walkDistance(drop, s.getGridX(), s.getGridY());
        }
        java.util.Arrays.sort(dist, 0, n);
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (dist[i] <= reach || i < min_nodes)
                count++;
        }
        if (n > 0)
            effective_reach[type == IronSupply.class ? 0 : 1] = Math.max(reach, dist[Math.min(n, min_nodes) - 1]);
        return count * node_cap;
    }

    /** The reach used by the last slotsWithin call per ore type (iron, rock), for node choice; -1 = unset. */
    private final int[] effective_reach = {-1, -1};

    int reach_limit = Integer.MAX_VALUE;

    @Nullable
    Supply bestNode(@NonNull Class<?> type, @NonNull Building drop) {
        List<? extends Supply> list = type == IronSupply.class ? iron : rock;
        Supply best = null;
        int best_cost = Integer.MAX_VALUE;
        for (Supply s : list) {
            if (s.isDead())
                continue;
            int c = crowdOf(s);
            if (c >= node_cap)
                continue;
            int cost = walkDistance(drop, s.getGridX(), s.getGridY()) + 6 * c;
            int eff = effective_reach[type == IronSupply.class ? 0 : 1];
            if (cost > (eff < 0 ? reach_limit : Math.max(reach_limit, eff + 20)))
                continue;
            if (isBad(s.getGridX(), s.getGridY())
                    || nearerToAvoided(drop.getGridX(), drop.getGridY(), s.getGridX(), s.getGridY()))
                continue;
            if (cost < best_cost && !threatened(s.getGridX(), s.getGridY(), 12)) {
                best_cost = cost;
                best = s;
            }
        }
        return best;
    }

    /** The nearest tree around a point with room for one more of our gatherers, searching rings up to max_r. */
    @Nullable
    TreeSupply bestTree(int cx, int cy, int min_r, int max_r, int cap) {
        TreeFilter f = new TreeFilter(min_r, max_r, cap);
        ctx.grid.scan(f, cx, cy);
        return f.result;
    }

    /**
     * The nearest tree around a drop building with room for one more gatherer whose walk is not a detour around
     * a cliff (path at most 1.5x the straight line plus 12 m); searches rings up to max_r, then up to 45.
     */
    @Nullable
    TreeSupply bestTreeFrom(@NonNull Building drop, int min_r, int max_r, int cap) {
        int[] field = fieldFor(drop);
        TreeFilter f = new TreeFilter(min_r, max_r, cap);
        f.field = field;
        f.bx = drop.getGridX();
        f.by = drop.getGridY();
        ctx.grid.scan(f, f.bx, f.by);
        if (f.result == null && max_r < 45) {
            TreeFilter wide = new TreeFilter(max_r, 45, cap);
            wide.field = field;
            wide.bx = f.bx;
            wide.by = f.by;
            ctx.grid.scan(wide, f.bx, f.by);
            return wide.result;
        }
        return f.result;
    }

    private final class TreeFilter implements ScanFilter {
        private final int min_r;
        private final int max_r;
        private final int cap;
        @Nullable
        TreeSupply result;
        int @Nullable [] field;
        int bx;
        int by;

        TreeFilter(int min_r, int max_r, int cap) {
            this.min_r = min_r;
            this.max_r = max_r;
            this.cap = cap;
        }

        private boolean direct(int x, int y) {
            int[] f = field;
            if (f == null)
                return true;
            int straight = dropDistance(bx, by, x, y);
            return walkFrom(f, bx, by, x, y) <= straight + straight / 2 + 12;
        }

        @Override
        public int getMinRadius() {
            return min_r;
        }

        @Override
        public int getMaxRadius() {
            return max_r;
        }

        @Override
        public boolean filter(int grid_x, int grid_y, Occupant occ) {
            if (occ instanceof TreeSupply t && !t.isDead() && crowdOf(t) < cap
                    && !threatened(grid_x, grid_y, 10) && !isBad(grid_x, grid_y)
                    && (field == null || !nearerToAvoided(bx, by, grid_x, grid_y)) && direct(grid_x, grid_y)) {
                result = t;
                return true;
            }
            return false;
        }
    }

    /**
     * Estimated seconds per delivered unit for gatherers of a type at a drop building, when g gatherers are
     * spread over the nearest nodes at the node cap.
     */
    float tripTime(@NonNull Class<?> type, @NonNull Building drop, int g) {
        int bx = drop.getGridX();
        int by = drop.getGridY();
        float walk;
        if (type == TreeSupply.class) {
            int[] walks = armoryTreeWalks(drop);
            walk = meanOfFirst(walks, walks.length, Math.max(4, g / TREE_CAP + 1));
        } else if (type == RubberSupply.class) {
            walk = 60f;
        } else {
            List<? extends Supply> list = type == IronSupply.class ? iron : rock;
            int need = Math.max(1, (g + node_cap - 1) / node_cap);
            int[] best = new int[need];
            java.util.Arrays.fill(best, Integer.MAX_VALUE);
            for (Supply s : list) {
                if (s.isDead())
                    continue;
                int d = walkDistance(drop, s.getGridX(), s.getGridY());
                for (int k = 0; k < need; k++) {
                    if (d < best[k]) {
                        System.arraycopy(best, k, best, k + 1, need - k - 1);
                        best[k] = d;
                        break;
                    }
                }
            }
            long sum = 0;
            int n = 0;
            for (int d : best) {
                if (d != Integer.MAX_VALUE) {
                    sum += d;
                    n++;
                }
            }
            walk = n == 0 ? 200f : sum / (float) n;
        }
        return 10.2f + 0.45f * walk;
    }

    /**
     * Mean one-way metres from a building's work ring to its k nearest trees, using the tree integral image.
     * adjacent_r is the Chebyshev ring from which trees are reachable without walking (5 for quarters/armory,
     * 3 for towers).
     */
    float treeWalk(int cx, int cy, int k, int adjacent_r) {
        int prev = ctx.map.treesAround(cx, cy, adjacent_r - 2);
        int got = 0;
        float sum = 0;
        for (int r = adjacent_r - 1; r <= 40 && got < k; r++) {
            int total = ctx.map.treesAround(cx, cy, r);
            int ring = total - prev;
            prev = total;
            if (ring <= 0)
                continue;
            int take = Math.min(ring, k - got);
            sum += take * 2f * Math.max(0, r - adjacent_r);
            got += take;
        }
        if (got < k)
            sum += (k - got) * 80f;
        return sum / k;
    }
}
