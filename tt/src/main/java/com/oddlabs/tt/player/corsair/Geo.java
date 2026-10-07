package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.landscape.IslandInfo;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.pathfinder.Occupant;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The map: islands with their resources, and where the resources lie (density tables in bins of {@value #BIN}
 * cells, rebuilt by {@link #scanSupplies}). All coordinates in grid cells.
 */
final class Geo {
    /** One island, with what we keep of it. */
    static final class Isle {
        final @NonNull IslandInfo info;
        final int id;
        final int area;
        final int trees;
        final int rocks;
        final int iron;
        final int cx;
        final int cy;
        /** The shore cells ships can dock at, as {gx, gy}. */
        final @NonNull List<int @NonNull []> shore;
        /** The best base point found by {@link #rateIsles}, and its score; score 0 without one. */
        int ax = -1;
        int ay = -1;
        float score;

        Isle(@NonNull IslandInfo info) {
            this.info = info;
            id = info.id();
            area = info.area();
            trees = info.trees();
            rocks = info.rocks();
            iron = info.iron();
            cx = info.centerX();
            cy = info.centerY();
            shore = info.contourPoints();
        }

        long shoreDist2(int gx, int gy) {
            long best = Long.MAX_VALUE;
            for (int[] c : shore) {
                long d2 = dist2(c[0], c[1], gx, gy);
                if (d2 < best)
                    best = d2;
            }
            return best;
        }

        int @Nullable [] nearestShore(int gx, int gy) {
            long best = Long.MAX_VALUE;
            int[] cell = null;
            for (int[] c : shore) {
                long d2 = dist2(c[0], c[1], gx, gy);
                if (d2 < best) {
                    best = d2;
                    cell = c;
                }
            }
            return cell;
        }

        @Override
        public String toString() {
            return "isle" + id + "(a" + area + " t" + trees + " r" + rocks + " i" + iron + " @" + cx + "," + cy + ")";
        }
    }

    static final int BIN = 8;

    final @NonNull UnitGrid grid;
    final int size;
    final @NonNull List<@NonNull Isle> isles = new ArrayList<>();
    private final Isle @NonNull [] by_id;
    final boolean archipelago;
    /** Score bases on wood and iron (params.wood_weight), set by the AI after construction. */
    boolean wood_weight;

    final int bins;
    final int @NonNull [] bin_tree;
    final int @NonNull [] bin_rock;
    final int @NonNull [] bin_iron;
    final List<@NonNull IronSupply> iron_nodes = new ArrayList<>();
    /**
     * Resource counts per (bin, island): key bin * 4096 + island, value {trees, rock, iron}. A bin can hold the coasts
     * of two islands; a base only ever uses its own island's resources. Looked up, never iterated.
     */
    private final java.util.HashMap<Integer, int @NonNull []> by_bin_isle = new java.util.HashMap<>();
    final List<@NonNull RockSupply> rock_nodes = new ArrayList<>();

    Geo(@NonNull Player me) {
        grid = me.getWorld().getUnitGrid();
        size = grid.getGridSize();
        int max_id = 0;
        List<IslandInfo> infos = new ArrayList<>(me.getWorld().getHeightMap().getIslandInfos());
        for (IslandInfo info : infos)
            max_id = Math.max(max_id, info.id());
        by_id = new Isle[max_id + 1];
        for (IslandInfo info : infos) {
            Isle isle = new Isle(info);
            isles.add(isle);
            by_id[isle.id] = isle;
        }
        isles.sort((a, b) -> Integer.compare(a.id, b.id));
        archipelago = isles.size() > 1;
        bins = (size + BIN - 1) / BIN;
        bin_tree = new int[bins * bins];
        bin_rock = new int[bins * bins];
        bin_iron = new int[bins * bins];
    }

    @Nullable
    Isle isle(int id) {
        return id >= 0 && id < by_id.length ? by_id[id] : null;
    }

    int islandAt(int gx, int gy) {
        if (gx < 0 || gy < 0 || gx >= size || gy >= size)
            return 0;
        return grid.getIslandId(gx, gy);
    }

    static int gridOf(float world) {
        return UnitGrid.toGridCoordinate(world);
    }

    static long dist2(int x0, int y0, int x1, int y1) {
        long dx = x1 - x0, dy = y1 - y0;
        return dx * dx + dy * dy;
    }

    static int dist(int x0, int y0, int x1, int y1) {
        return (int) Math.round(Math.sqrt((double) dist2(x0, y0, x1, y1)));
    }

    /** Rebuilds the resource lists and density bins from the unit grid (a full scan: call rarely). */
    void scanSupplies() {
        java.util.Arrays.fill(bin_tree, 0);
        java.util.Arrays.fill(bin_rock, 0);
        java.util.Arrays.fill(bin_iron, 0);
        iron_nodes.clear();
        rock_nodes.clear();
        by_bin_isle.clear();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Occupant occ = grid.getOccupant(x, y);
                if (occ == null)
                    continue;
                int b = (y / BIN) * bins + x / BIN;
                int kind = -1;
                if (occ instanceof TreeSupply tree) {
                    if (!tree.isEmpty() && tree.getGridX() == x && tree.getGridY() == y) {
                        bin_tree[b]++;
                        kind = 0;
                    }
                } else if (occ instanceof IronSupply s) {
                    if (!s.isEmpty() && s.getGridX() == x && s.getGridY() == y) {
                        bin_iron[b]++;
                        iron_nodes.add(s);
                        kind = 2;
                    }
                } else if (occ instanceof RockSupply s) {
                    if (!s.isEmpty() && s.getGridX() == x && s.getGridY() == y) {
                        bin_rock[b]++;
                        rock_nodes.add(s);
                        kind = 1;
                    }
                }
                if (kind >= 0) {
                    int isl = islandOfSupply(x, y);
                    by_bin_isle.computeIfAbsent(b * 4096 + isl, k -> new int[3])[kind]++;
                }
            }
        }
    }

    /**
     * The island a supply at (x, y) belongs to: supplies block their own cell, so the nearest cell of land around it
     * names the island.
     */
    private int islandOfSupply(int x, int y) {
        for (int r = 0; r <= 3; r++)
            for (int dy = -r; dy <= r; dy++)
                for (int dx = -r; dx <= r; dx++) {
                    int id = islandAt(x + dx, y + dy);
                    if (id >= 2)
                        return id;
                }
        return 0;
    }

    /** {trees, rock, iron} of one island within r bins of bin (bx, by). */
    int @NonNull [] windowOn(int bx, int by, int r, int island) {
        int[] sum = new int[3];
        for (int y = Math.max(0, by - r); y <= Math.min(bins - 1, by + r); y++)
            for (int x = Math.max(0, bx - r); x <= Math.min(bins - 1, bx + r); x++)
                if ((x - bx) * (x - bx) + (y - by) * (y - by) <= r * r) {
                    int[] c = by_bin_isle.get((y * bins + x) * 4096 + island);
                    if (c != null) {
                        sum[0] += c[0];
                        sum[1] += c[1];
                        sum[2] += c[2];
                    }
                }
        return sum;
    }

    /** Sum of a bin table over the bins within r bins of bin (bx, by). */
    int window(int @NonNull [] table, int bx, int by, int r) {
        int sum = 0;
        for (int y = Math.max(0, by - r); y <= Math.min(bins - 1, by + r); y++)
            for (int x = Math.max(0, bx - r); x <= Math.min(bins - 1, bx + r); x++)
                if ((x - bx) * (x - bx) + (y - by) * (y - by) <= r * r)
                    sum += table[y * bins + x];
        return sum;
    }

    /**
     * How good a base at the centre of bin (bx, by) on an island would be: that island's iron, rock and wood within ~3
     * bins (resources across the water count for nothing).
     */
    float baseScore(int bx, int by, int island) {
        int[] c = windowOn(bx, by, 3, island);
        int t = c[0], r = c[1], i = c[2];
        if (wood_weight) {
            // iron axes only: wood (two per axe, and every building) and iron make the base; rock only a tiebreak
            return 3f * Math.min(i, 8) + .4f * Math.min(t, 60) + .3f * Math.min(r, 6);
        }
        return 3f * Math.min(i, 8) + 1.5f * Math.min(r, 6) + .25f * Math.min(t, 60);
    }

    /** Bin centres on the island scoring at least {@code share} of its best, the best first, at most {@code max}. */
    List<int @NonNull []> candidates(@NonNull Isle isle, float share, int max) {
        List<int[]> out = new ArrayList<>();
        int bx0 = isle.info.minX() / BIN, bx1 = isle.info.maxX() / BIN;
        int by0 = isle.info.minY() / BIN, by1 = isle.info.maxY() / BIN;
        for (int by = by0; by <= by1; by++)
            for (int bx = bx0; bx <= bx1; bx++) {
                int cx = bx * BIN + BIN / 2, cy = by * BIN + BIN / 2;
                if (islandAt(cx, cy) != isle.id)
                    continue;
                float s = baseScore(bx, by, isle.id);
                if (s >= isle.score * share)
                    out.add(new int[]{cx, cy, (int) (s * 100)});
            }
        out.sort((a, b) -> a[2] != b[2] ? Integer.compare(b[2], a[2]) : a[1] != b[1] ? Integer.compare(a[1],
                b[1]) : Integer.compare(a[0], b[0]));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Finds each island's best base point (needs {@link #scanSupplies} first). */
    void rateIsles() {
        for (Isle isle : isles) {
            isle.score = 0;
            isle.ax = isle.ay = -1;
            if (isle.area < 400 || isle.trees <= (wood_weight ? 50 : 0) || isle.rocks <= 0 || isle.iron <= 0)
                continue;
            int bx0 = isle.info.minX() / BIN, bx1 = isle.info.maxX() / BIN;
            int by0 = isle.info.minY() / BIN, by1 = isle.info.maxY() / BIN;
            for (int by = by0; by <= by1; by++) {
                for (int bx = bx0; bx <= bx1; bx++) {
                    int cx = bx * BIN + BIN / 2, cy = by * BIN + BIN / 2;
                    if (islandAt(cx, cy) != isle.id)
                        continue;
                    float s = baseScore(bx, by, isle.id);
                    if (s > isle.score) {
                        isle.score = s;
                        isle.ax = cx;
                        isle.ay = cy;
                    }
                }
            }
        }
    }
}
