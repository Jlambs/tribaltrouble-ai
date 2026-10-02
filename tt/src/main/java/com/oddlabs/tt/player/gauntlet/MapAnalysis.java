package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.pathfinder.Movable;
import com.oddlabs.tt.pathfinder.Occupant;
import com.oddlabs.tt.pathfinder.UnitGrid;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Terrain and resource knowledge of the map: which cells can be walked, walking distance fields, the supplies still
 * standing and quick density lookups used when choosing where to build.
 */
final class MapAnalysis {
    private final @NonNull World world;
    private final @NonNull UnitGrid grid;
    private final @NonNull HeightMap heightmap;
    private final boolean @NonNull [] @NonNull [] access;
    private final int size;

    private final List<@NonNull IronSupply> iron = new ArrayList<>();
    private final List<@NonNull RockSupply> rocks = new ArrayList<>();
    private final List<@NonNull TreeSupply> trees = new ArrayList<>();
    private static final int TREE_BUCKET = 8;
    private final int tree_buckets_side;
    private final List<@NonNull List<@NonNull TreeSupply>> tree_buckets = new ArrayList<>();
    /** Summed-area table of standing trees, (size + 1)^2 entries. */
    private int @NonNull [] tree_sums;

    MapAnalysis(@NonNull World world) {
        this.world = world;
        this.grid = world.getUnitGrid();
        this.heightmap = world.getHeightMap();
        this.access = heightmap.getAccessGrid();
        this.size = grid.getGridSize();
        this.tree_sums = new int[(size + 1) * (size + 1)];
        this.pass_gen = new int[size * size];
        this.pass_ok = new boolean[size * size];
        this.tree_buckets_side = (size + TREE_BUCKET - 1) / TREE_BUCKET;
        for (int i = 0; i < tree_buckets_side * tree_buckets_side; i++)
            tree_buckets.add(new ArrayList<>());
        scanSupplies();
    }

    int getSize() {
        return size;
    }

    @NonNull
    World getWorld() {
        return world;
    }

    @NonNull
    UnitGrid getGrid() {
        return grid;
    }

    List<@NonNull IronSupply> getIron() {
        return iron;
    }

    List<@NonNull RockSupply> getRocks() {
        return rocks;
    }

    List<@NonNull TreeSupply> getTrees() {
        return trees;
    }

    boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size;
    }

    float height(int x, int y) {
        return heightmap.getHeight(Math.clamp(x, 0, size - 1), Math.clamp(y, 0, size - 1));
    }

    /**
     * Rebuilds the supply lists and the tree density table from the unit grid.
     */
    void scanSupplies() {
        iron.clear();
        rocks.clear();
        trees.clear();
        for (List<TreeSupply> bucket : tree_buckets)
            bucket.clear();
        int stride = size + 1;
        int[] sums = new int[stride * stride];
        for (int y = 0; y < size; y++) {
            int row = 0;
            for (int x = 0; x < size; x++) {
                Occupant occ = grid.getOccupant(x, y);
                if (occ instanceof TreeSupply tree) {
                    if (!tree.isEmpty() && tree.getGridX() == x && tree.getGridY() == y) {
                        trees.add(tree);
                        tree_buckets.get((y / TREE_BUCKET) * tree_buckets_side + x / TREE_BUCKET).add(tree);
                        row++;
                    }
                } else if (occ instanceof IronSupply supply) {
                    if (!supply.isEmpty() && supply.getGridX() == x && supply.getGridY() == y)
                        iron.add(supply);
                } else if (occ instanceof RockSupply supply) {
                    if (!supply.isEmpty() && supply.getGridX() == x && supply.getGridY() == y)
                        rocks.add(supply);
                }
                sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + row;
            }
        }
        tree_sums = sums;
    }

    /**
     * Meters to the nearest `count` standing trees around a cell, as the crow flies, looking at most `radius` cells
     * away. Trees missing within the radius count as `missing` meters. Returns the average.
     */
    float averageTreeDistance(int x, int y, int count, int radius, float missing) {
        return averageTreeDistance(x, y, count, radius, missing, null);
    }

    /**
     * Like {@link #averageTreeDistance(int, int, int, int, float)}, but with a walking distance field (from anywhere)
     * to tell trees across a cliff: walking between two cells takes at least the difference of their distances from
     * the field's source.
     */
    float averageTreeDistance(int x, int y, int count, int radius, float missing, @Nullable DistanceField field) {
        int site = field != null ? field.getAround(x, y, 1) : DistanceField.UNREACHABLE;
        if (site == DistanceField.UNREACHABLE)
            field = null;
        float[] best = new float[count];
        java.util.Arrays.fill(best, Float.MAX_VALUE);
        int bx0 = Math.max(0, (x - radius) / TREE_BUCKET);
        int by0 = Math.max(0, (y - radius) / TREE_BUCKET);
        int bx1 = Math.min(tree_buckets_side - 1, (x + radius) / TREE_BUCKET);
        int by1 = Math.min(tree_buckets_side - 1, (y + radius) / TREE_BUCKET);
        int r2 = radius * radius;
        for (int by = by0; by <= by1; by++) {
            for (int bx = bx0; bx <= bx1; bx++) {
                List<TreeSupply> bucket = tree_buckets.get(by * tree_buckets_side + bx);
                for (TreeSupply t : bucket) {
                    if (t.isEmpty())
                        continue;
                    int d2 = dist2(x, y, t.getGridX(), t.getGridY());
                    if (d2 > r2)
                        continue;
                    float d = (float) Math.sqrt(d2) * HeightMap.METERS_PER_UNIT_GRID;
                    if (field != null && d < best[count - 1]) {
                        int walk = field.getAround(t.getGridX(), t.getGridY(), 1);
                        d = walk == DistanceField.UNREACHABLE ? Float.MAX_VALUE : Math.max(d, Math.abs(walk - site));
                    }
                    if (d < best[count - 1]) {
                        int i = count - 1;
                        while (i > 0 && best[i - 1] > d) {
                            best[i] = best[i - 1];
                            i--;
                        }
                        best[i] = d;
                    }
                }
            }
        }
        float sum = 0f;
        for (float d : best)
            sum += d == Float.MAX_VALUE ? missing : d;
        return sum / count;
    }

    /**
     * Number of trees (as of the last scan) in the square of the given radius around a cell.
     */
    int treesAround(int x, int y, int radius) {
        int stride = size + 1;
        int x0 = Math.clamp(x - radius, 0, size);
        int y0 = Math.clamp(y - radius, 0, size);
        int x1 = Math.clamp(x + radius + 1, 0, size);
        int y1 = Math.clamp(y + radius + 1, 0, size);
        return tree_sums[y1 * stride + x1] - tree_sums[y0 * stride + x1] - tree_sums[y1 * stride + x0] + tree_sums[y0 * stride + x0];
    }

    /**
     * Whether units can walk through the cell. Trees, supplies and buildings block it; units do not.
     */
    boolean passable(int x, int y) {
        return passable(x, y, null);
    }

    /** Whether the terrain itself can be walked (the height map's access grid), whatever stands there. */
    boolean walkable(int x, int y) {
        return inside(x, y) && access[y][x];
    }

    /** Units and chickens move out of the way (an idle unit counts as a static occupant, but not for long). */
    private boolean passable(int x, int y, Occupant ignore) {
        if (!inside(x, y) || !access[y][x])
            return false;
        Occupant occ = grid.getOccupant(x, y);
        return occ == null || occ == ignore || occ instanceof Movable || occ.getPenalty() != Occupant.STATIC;
    }

    /** The highest walkable cell within radius of (x, y), or (x, y) itself when nothing is noticeably higher. */
    int @NonNull [] highGround(int x, int y, int radius) {
        int bx = x;
        int by = y;
        float best = height(x, y) + 1f;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy > radius * radius || !passable(x + dx, y + dy))
                    continue;
                float h = height(x + dx, y + dy);
                if (h > best) {
                    best = h;
                    bx = x + dx;
                    by = y + dy;
                }
            }
        }
        return new int[]{bx, by};
    }

    boolean canPlace(@NonNull BuildingTemplate template, int x, int y) {
        return inside(x, y) && template.isPlacingLegal(grid, x, y);
    }

    /**
     * Dijkstra over the walkable cells from (sx, sy), stopping at max_cost meters. The source itself may be blocked
     * (a building or supply), in which case the search starts from the cells around it.
     */
    @NonNull
    DistanceField computeField(int sx, int sy, int max_cost) {
        return computeField(new int[]{sx}, new int[]{sy}, max_cost);
    }

    /**
     * Distances to the nearest of several sources, such as every enemy start. A single source may be blocked, as in
     * {@link #computeField(int, int, int)}; several sources must stand on open ground.
     */
    @NonNull
    DistanceField computeField(int @NonNull [] xs, int @NonNull [] ys, int max_cost) {
        return computeField(xs, ys, max_cost, false);
    }

    /** {@link #computeField(int, int, int)}, with corner cuts if {@code corner} (see the four-argument one). */
    @NonNull
    DistanceField computeField(int sx, int sy, int max_cost, boolean corner) {
        return computeField(new int[]{sx}, new int[]{sy}, max_cost, corner);
    }

    /**
     * {@link #computeField(int[], int[], int)}; with {@code corner}, a diagonal step needs only the diagonal cell
     * open, as in the engine's pathfinder (GridNode.addNeighbours) and region graph (RegionBuilder.addNeighbours),
     * which walk units through such corner cuts. Without it, both straight cells beside the step must be open too, so
     * a pocket joined to the map only by a corner cut looks sealed (corner_fields; s6206 N=6: a 449-cell valley whose
     * two exits are corner cuts, entered by enemy units and our own shepherd).
     */
    @NonNull
    DistanceField computeField(int @NonNull [] xs, int @NonNull [] ys, int max_cost, boolean corner) {
        DistanceField field = new DistanceField(size, xs[0], ys[0]);
        int[] cost = field.raw();
        // Dial's algorithm: costs grow in steps of 2 or 3, so four rotating buckets suffice.
        int[][] buckets = new int[4][];
        int[] counts = new int[4];
        for (int i = 0; i < 4; i++)
            buckets[i] = new int[Math.max(256, xs.length)];
        // A building's own footprint may be crossed, so fields can start from the middle of a building.
        Occupant source_occupant = xs.length == 1 && inside(xs[0], ys[0]) ? grid.getOccupant(xs[0], ys[0]) : null;
        if (source_occupant != null && source_occupant.getPenalty() != Occupant.STATIC)
            source_occupant = null;
        for (int i = 0; i < xs.length; i++) {
            if (!inside(xs[i], ys[i]) || cost[ys[i] * size + xs[i]] == 0)
                continue;
            cost[ys[i] * size + xs[i]] = 0;
            buckets[0][counts[0]++] = ys[i] * size + xs[i];
        }
        if (counts[0] == 0)
            return field;
        int call = ++pass_call;
        int current = 0;
        int empty_rounds = 0;
        while (empty_rounds < 4) {
            int b = current & 3;
            if (counts[b] == 0) {
                empty_rounds++;
                current++;
                continue;
            }
            empty_rounds = 0;
            if (current > max_cost)
                break;
            int[] bucket = buckets[b];
            // Entries appended to this bucket during the loop belong to later costs (current + 4 or more).
            int n = counts[b];
            counts[b] = 0;
            if (field_work.length < n)
                field_work = new int[Math.max(n, 2 * field_work.length)];
            int[] work = field_work;
            System.arraycopy(bucket, 0, work, 0, n);
            // The four straight neighbours first: a diagonal step needs both straight cells beside it open (with
            // corner, only the diagonal cell itself), so each cell is looked up at most eight times per expansion
            // instead of sixteen. The order in which neighbours are relaxed changes nothing: every cell up to
            // max_cost ends at its least cost either way, and every cell beyond at the least over its expanded
            // neighbours.
            int straight = current + 2;
            int diagonal = current + 3;
            for (int i = 0; i < n; i++) {
                int index = work[i];
                if (cost[index] != current)
                    continue;
                int x = index % size;
                int y = index / size;
                boolean west = passableIn(x - 1, y, source_occupant, call);
                boolean east = passableIn(x + 1, y, source_occupant, call);
                boolean north = passableIn(x, y - 1, source_occupant, call);
                boolean south = passableIn(x, y + 1, source_occupant, call);
                if (west)
                    relax(cost, buckets, counts, index - 1, straight);
                if (east)
                    relax(cost, buckets, counts, index + 1, straight);
                if (north)
                    relax(cost, buckets, counts, index - size, straight);
                if (south)
                    relax(cost, buckets, counts, index + size, straight);
                if ((corner || (north && west)) && passableIn(x - 1, y - 1, source_occupant, call))
                    relax(cost, buckets, counts, index - size - 1, diagonal);
                if ((corner || (north && east)) && passableIn(x + 1, y - 1, source_occupant, call))
                    relax(cost, buckets, counts, index - size + 1, diagonal);
                if ((corner || (south && west)) && passableIn(x - 1, y + 1, source_occupant, call))
                    relax(cost, buckets, counts, index + size - 1, diagonal);
                if ((corner || (south && east)) && passableIn(x + 1, y + 1, source_occupant, call))
                    relax(cost, buckets, counts, index + size + 1, diagonal);
            }
            current++;
        }
        return field;
    }

    /**
     * computeField's cache of passable(x, y, ignore): the grid does not change while a field is computed, so each cell
     * is looked up once per call (pass_gen holds the call that filled pass_ok) instead of up to 16 times.
     */
    private final int @NonNull [] pass_gen;
    private final boolean @NonNull [] pass_ok;
    private int pass_call;
    /** computeField's copy of the bucket it expands, reused across calls. */
    private int @NonNull [] field_work = new int[256];

    /** computeField: lowers the cell's cost to next if that is less, and queues it in next's bucket. */
    private static void relax(int @NonNull [] cost, int @NonNull [] @NonNull [] buckets, int @NonNull [] counts,
            int index, int next) {
        if (next >= cost[index])
            return;
        cost[index] = next;
        int b = next & 3;
        if (counts[b] == buckets[b].length)
            buckets[b] = java.util.Arrays.copyOf(buckets[b], counts[b] * 2);
        buckets[b][counts[b]++] = index;
    }

    private boolean passableIn(int x, int y, @Nullable Occupant ignore, int call) {
        if (!inside(x, y))
            return false;
        int i = y * size + x;
        if (pass_gen[i] == call)
            return pass_ok[i];
        boolean ok = passable(x, y, ignore);
        pass_gen[i] = call;
        pass_ok[i] = ok;
        return ok;
    }

    static int dist2(int x0, int y0, int x1, int y1) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        return dx * dx + dy * dy;
    }

    /** The cell `cells` away from (x, y) in the direction of (to_x, to_y) (truncated), or (to_x, to_y) if nearer. */
    static int @NonNull [] towards(int x, int y, int to_x, int to_y, int cells) {
        float dx = to_x - x;
        float dy = to_y - y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= cells)
            return new int[]{to_x, to_y};
        return new int[]{x + (int) (dx / len * cells), y + (int) (dy / len * cells)};
    }

    /** Euclidean distance in meters between two grid cells. */
    static float meters(int x0, int y0, int x1, int y1) {
        return (float) Math.sqrt(dist2(x0, y0, x1, y1)) * HeightMap.METERS_PER_UNIT_GRID;
    }

    /** The mean grid cell of the units (truncated), {0, 0} for none. */
    static int @NonNull [] centroid(@NonNull List<? extends Selectable<?>> units) {
        long sx = 0;
        long sy = 0;
        for (Selectable<?> u : units) {
            sx += u.getGridX();
            sy += u.getGridY();
        }
        int n = Math.max(1, units.size());
        return new int[]{(int) (sx / n), (int) (sy / n)};
    }

    /** The living building nearest (x, y), the first of equals in list order, or null. */
    static @Nullable Building nearest(@NonNull List<@NonNull Building> buildings, int x, int y) {
        Building best = null;
        int best_d = Integer.MAX_VALUE;
        for (Building b : buildings) {
            if (b.isDead())
                continue;
            int d = dist2(x, y, b.getGridX(), b.getGridY());
            if (d < best_d) {
                best_d = d;
                best = b;
            }
        }
        return best;
    }
}
