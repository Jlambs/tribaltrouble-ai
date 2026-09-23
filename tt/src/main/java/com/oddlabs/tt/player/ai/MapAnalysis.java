package com.oddlabs.tt.player.ai;

import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.Supply;
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

    static int countStanding(List<? extends Supply> supplies) {
        int n = 0;
        for (Supply s : supplies)
            if (!s.isEmpty())
                n++;
        return n;
    }

    /**
     * Whether units can walk through the cell. Trees, supplies and buildings block it; units do not.
     */
    boolean passable(int x, int y) {
        return passable(x, y, null);
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
        DistanceField field = new DistanceField(size, sx, sy);
        int[] cost = field.raw();
        // Dial's algorithm: costs grow in steps of 2 or 3, so four rotating buckets suffice.
        int[][] buckets = new int[4][];
        int[] counts = new int[4];
        for (int i = 0; i < 4; i++)
            buckets[i] = new int[256];
        if (!inside(sx, sy))
            return field;
        // A building's own footprint may be crossed, so fields can start from the middle of a building.
        Occupant source_occupant = grid.getOccupant(sx, sy);
        if (source_occupant != null && source_occupant.getPenalty() != Occupant.STATIC)
            source_occupant = null;
        cost[sy * size + sx] = 0;
        buckets[0][counts[0]++] = sy * size + sx;
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
            int[] work = new int[n];
            System.arraycopy(bucket, 0, work, 0, n);
            for (int i = 0; i < n; i++) {
                int index = work[i];
                if (cost[index] != current)
                    continue;
                int x = index % size;
                int y = index / size;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0)
                            continue;
                        int nx = x + dx;
                        int ny = y + dy;
                        if (!passable(nx, ny, source_occupant))
                            continue;
                        if (dx != 0 && dy != 0 && (!passable(x + dx, y, source_occupant)
                                || !passable(x, y + dy, source_occupant)))
                            continue;
                        int step = (dx != 0 && dy != 0) ? 3 : 2;
                        int next = current + step;
                        int nindex = ny * size + nx;
                        if (next < cost[nindex]) {
                            cost[nindex] = next;
                            int nb = next & 3;
                            if (counts[nb] == buckets[nb].length) {
                                int[] grown = new int[buckets[nb].length * 2];
                                System.arraycopy(buckets[nb], 0, grown, 0, counts[nb]);
                                buckets[nb] = grown;
                            }
                            buckets[nb][counts[nb]++] = nindex;
                        }
                    }
                }
            }
            current++;
        }
        return field;
    }

    static int dist2(int x0, int y0, int x1, int y1) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        return dx * dx + dy * dy;
    }

    /** Euclidean distance in meters between two grid cells. */
    static float meters(int x0, int y0, int x1, int y1) {
        return (float) Math.sqrt(dist2(x0, y0, x1, y1)) * HeightMap.METERS_PER_UNIT_GRID;
    }
}
