package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.pathfinder.Occupant;
import com.oddlabs.tt.pathfinder.UnitGrid;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Static map of every tree, rock and iron node on the island, built once from the unit grid. Supplies respawn on
 * the same cell, so only the cell coordinates are stored; the live {@link Supply} object is looked up on demand.
 * Chickens move around, so they are always looked up live via {@link #findChickens}.
 *
 * <p>Queries are bucketed on a coarse grid so radius searches are cheap enough to run every AI tick.
 */
public final class ResourceMap {
    public enum Kind {
        TREE(TreeSupply.class),
        ROCK(RockSupply.class),
        IRON(IronSupply.class);

        public final @NonNull Class<? extends Supply> supply_class;

        Kind(@NonNull Class<? extends Supply> supply_class) {
            this.supply_class = supply_class;
        }

        public static @Nullable Kind of(@NonNull Class<?> supply_class) {
            for (Kind k : values())
                if (k.supply_class == supply_class)
                    return k;
            return null;
        }
    }

    /** A resource node (cell) with a live lookup of the supply currently standing there. */
    public static final class Node {
        public final @NonNull Kind kind;
        public final int grid_x;
        public final int grid_y;
        public final int island;

        Node(@NonNull Kind kind, int grid_x, int grid_y, int island) {
            this.kind = kind;
            this.grid_x = grid_x;
            this.grid_y = grid_y;
            this.island = island;
        }

        /** The supply on this cell if it exists and is not empty, else null. */
        public @Nullable Supply live(@NonNull UnitGrid grid) {
            Occupant occ = grid.getOccupant(grid_x, grid_y);
            if (kind.supply_class.isInstance(occ)) {
                Supply s = (Supply) occ;
                return s.isEmpty() ? null : s;
            }
            return null;
        }

        public boolean isAlive(@NonNull UnitGrid grid) {
            return live(grid) != null;
        }

        public float distanceTo(int gx, int gy) {
            float dx = grid_x - gx;
            float dy = grid_y - gy;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        public int distanceSquaredTo(int gx, int gy) {
            int dx = grid_x - gx;
            int dy = grid_y - gy;
            return dx * dx + dy * dy;
        }

        @Override
        public @NonNull String toString() {
            return kind + "@" + grid_x + "," + grid_y;
        }
    }

    private static final int BUCKET_SHIFT = 4; // 16x16 cell buckets

    private final @NonNull World world;
    private final @NonNull UnitGrid grid;
    private final int grid_size;
    private final int buckets_per_side;
    private final @NonNull List<Node>[][] buckets;
    private final @NonNull List<Node>[] by_kind;
    private final int home_island;

    @SuppressWarnings("unchecked")
    public ResourceMap(@NonNull World world, int home_island) {
        this.world = world;
        this.grid = world.getUnitGrid();
        this.grid_size = grid.getGridSize();
        this.home_island = home_island;
        this.buckets_per_side = (grid_size + (1 << BUCKET_SHIFT) - 1) >> BUCKET_SHIFT;
        this.buckets = new List[buckets_per_side][buckets_per_side];
        this.by_kind = new List[Kind.values().length];
        for (int i = 0; i < by_kind.length; i++)
            by_kind[i] = new ArrayList<>();
        HeightMap map = world.getHeightMap();
        for (int y = 0; y < grid_size; y++) {
            for (int x = 0; x < grid_size; x++) {
                Occupant occ = grid.getOccupant(x, y);
                Kind kind = null;
                if (occ instanceof TreeSupply)
                    kind = Kind.TREE;
                else if (occ instanceof RockSupply)
                    kind = Kind.ROCK;
                else if (occ instanceof IronSupply)
                    kind = Kind.IRON;
                if (kind == null)
                    continue;
                int island = map.getIslandId(x, y);
                if (home_island >= 0 && island != home_island)
                    continue;
                Node node = new Node(kind, x, y, island);
                by_kind[kind.ordinal()].add(node);
                bucket(x, y).add(node);
            }
        }
    }

    private @NonNull List<Node> bucket(int gx, int gy) {
        int bx = gx >> BUCKET_SHIFT;
        int by = gy >> BUCKET_SHIFT;
        List<Node> list = buckets[by][bx];
        if (list == null) {
            list = new ArrayList<>();
            buckets[by][bx] = list;
        }
        return list;
    }

    public int getHomeIsland() {
        return home_island;
    }

    /** Every node of a kind on the home island (dead or alive). Do not modify. */
    public @NonNull List<Node> all(@NonNull Kind kind) {
        return by_kind[kind.ordinal()];
    }

    public int countTotal(@NonNull Kind kind) {
        return by_kind[kind.ordinal()].size();
    }

    /** Nodes of a kind within {@code radius} cells of (gx, gy); {@code alive_only} skips exhausted nodes. */
    public @NonNull List<Node> within(@NonNull Kind kind, int gx, int gy, int radius, boolean alive_only) {
        List<Node> result = new ArrayList<>();
        int r2 = radius * radius;
        int bx0 = Math.max(0, (gx - radius) >> BUCKET_SHIFT);
        int bx1 = Math.min(buckets_per_side - 1, (gx + radius) >> BUCKET_SHIFT);
        int by0 = Math.max(0, (gy - radius) >> BUCKET_SHIFT);
        int by1 = Math.min(buckets_per_side - 1, (gy + radius) >> BUCKET_SHIFT);
        for (int by = by0; by <= by1; by++) {
            for (int bx = bx0; bx <= bx1; bx++) {
                List<Node> list = buckets[by][bx];
                if (list == null)
                    continue;
                for (Node n : list) {
                    if (n.kind != kind || n.distanceSquaredTo(gx, gy) > r2)
                        continue;
                    if (alive_only && !n.isAlive(grid))
                        continue;
                    result.add(n);
                }
            }
        }
        return result;
    }

    /** Number of live nodes of a kind within radius cells. */
    public int countAlive(@NonNull Kind kind, int gx, int gy, int radius) {
        return within(kind, gx, gy, radius, true).size();
    }

    /**
     * Remaining supply units of a kind within radius (each live node holds up to 10). Cheap approximation: 10 per live
     * node.
     */
    public int estimateSupply(@NonNull Kind kind, int gx, int gy, int radius) {
        return countAlive(kind, gx, gy, radius) * 10;
    }

    /**
     * The nearest live node of a kind to (gx, gy) searching outward up to {@code max_radius} cells, or null.
     * Nodes are compared by squared distance, ties by grid order, so the result is deterministic.
     */
    public @Nullable Node nearestAlive(@NonNull Kind kind, int gx, int gy, int max_radius) {
        Node best = null;
        int best_d2 = Integer.MAX_VALUE;
        // grow the search ring by bucket size so we do not scan the whole island for a nearby hit
        int step = 1 << BUCKET_SHIFT;
        for (int radius = step; radius <= max_radius + step; radius += step) {
            List<Node> candidates = within(kind, gx, gy, Math.min(radius, max_radius), true);
            for (Node n : candidates) {
                int d2 = n.distanceSquaredTo(gx, gy);
                if (d2 < best_d2 || (d2 == best_d2 && best != null && (n.grid_y < best.grid_y
                        || (n.grid_y == best.grid_y
                                && n.grid_x < best.grid_x)))) {
                    best_d2 = d2;
                    best = n;
                }
            }
            if (best != null)
                return best;
        }
        return null;
    }

    /**
     * The {@code n} nearest live nodes of a kind to (gx, gy) within {@code max_radius}, closest first. Deterministic.
     */
    public @NonNull List<Node> nearestAlive(@NonNull Kind kind, int gx, int gy, int max_radius, int n) {
        List<Node> candidates = within(kind, gx, gy, max_radius, true);
        candidates.sort((a, b) -> {
            int c = Integer.compare(a.distanceSquaredTo(gx, gy), b.distanceSquaredTo(gx, gy));
            if (c != 0)
                return c;
            c = Integer.compare(a.grid_y, b.grid_y);
            return c != 0 ? c : Integer.compare(a.grid_x, b.grid_x);
        });
        return candidates.size() > n ? new ArrayList<>(candidates.subList(0, n)) : candidates;
    }

    /** Live chickens (rubber supplies) anywhere on the home island, in a deterministic order. */
    public @NonNull List<RubberSupply> findChickens() {
        List<RubberSupply> result = new ArrayList<>();
        HeightMap map = world.getHeightMap();
        // Chickens live in at most 3 small groups; scanning the whole grid (262k cells) every call is too much, so
        // the caller should cache this per slow tick. The scan itself is a plain array walk and takes < 1 ms.
        for (int y = 0; y < grid_size; y++) {
            for (int x = 0; x < grid_size; x++) {
                Occupant occ = grid.getOccupant(x, y);
                if (occ instanceof RubberSupply chicken && !chicken.isEmpty() && !chicken.isHit()) {
                    if (home_island < 0 || map.getIslandId(x, y) == home_island)
                        result.add(chicken);
                }
            }
        }
        return result;
    }

    public int getGridSize() {
        return grid_size;
    }
}
