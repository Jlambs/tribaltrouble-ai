package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.pathfinder.PathFinder;
import com.oddlabs.tt.pathfinder.Region;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Geometry of the base: where the start is, where the enemy is, and where the armory, quarters and towers should
 * go. The armory site is chosen once (it decides the game); quarters and tower sites are found on demand because
 * earlier buildings change what is legal.
 *
 * <p>All coordinates are grid cells. The "approach" vector points from our start toward the enemy start.
 */
public final class BasePlan {
    private final @NonNull Player player;
    private final @NonNull ResourceMap resources;
    private final @NonNull SiteFinder sites;
    private final @NonNull Params params;
    private final @NonNull AiLog log;
    private final @NonNull HeightMap map;
    private final int grid_size;

    public int start_x;
    public int start_y;
    public int enemy_x;
    public int enemy_y;
    public int island;
    /** Unit vector start -> enemy. */
    public float ux;
    public float uy;
    /** Chosen armory site (may be re-chosen if it becomes illegal before placement). */
    public int armory_x;
    public int armory_y;
    public float armory_score;
    /** Fallback armory site behind the first quarters, for a rebuild. */
    public int fallback_x = -1;
    public int fallback_y = -1;
    /** First quarters site (next to the start). */
    public int q1_x;
    public int q1_y;

    public BasePlan(@NonNull Player player, @NonNull ResourceMap resources, @NonNull SiteFinder sites,
            @NonNull Params params, @NonNull AiLog log) {
        this.player = player;
        this.resources = resources;
        this.sites = sites;
        this.params = params;
        this.log = log;
        this.map = player.getWorld().getHeightMap();
        this.grid_size = player.getWorld().getUnitGrid().getGridSize();
    }

    /** Compute the fixed geometry. Call once, after the resource map exists. */
    public void init() {
        start_x = UnitGrid.toGridCoordinate(player.getStartX());
        start_y = UnitGrid.toGridCoordinate(player.getStartY());
        island = map.getIslandId(start_x, start_y);
        // enemy start: the nearest enemy player's start (1v1: the only one); fall back to the map centre
        int cx = grid_size / 2;
        int cy = grid_size / 2;
        enemy_x = cx;
        enemy_y = cy;
        int best_d2 = Integer.MAX_VALUE;
        for (Player p : player.getWorld().getPlayers()) {
            if (!player.isEnemy(p))
                continue;
            int ex = UnitGrid.toGridCoordinate(p.getStartX());
            int ey = UnitGrid.toGridCoordinate(p.getStartY());
            int d2 = (ex - start_x) * (ex - start_x) + (ey - start_y) * (ey - start_y);
            if (d2 < best_d2) {
                best_d2 = d2;
                enemy_x = ex;
                enemy_y = ey;
            }
        }
        float dx = enemy_x - start_x;
        float dy = enemy_y - start_y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) {
            dx = cx - start_x;
            dy = cy - start_y;
            len = (float) Math.sqrt(dx * dx + dy * dy);
        }
        ux = len < 1f ? 1f : dx / len;
        uy = len < 1f ? 0f : dy / len;

        chooseArmorySite();
        chooseFirstQuartersSite();
        chooseFallbackSite();
        log.info(
                () -> "plan: start " + start_x + "," + start_y + " enemy " + enemy_x + "," + enemy_y + " armory " + armory_x + "," + armory_y + " (score " + (int) armory_score + ") q1 " + q1_x + "," + q1_y + " fallback " + fallback_x + "," + fallback_y + " armory walk=" + (int) walk(
                        start_x, start_y, armory_x, armory_y) + "/" + (int) dist(start_x, start_y, armory_x,
                                armory_y) + " q1 walk=" + (int) walk(start_x, start_y, q1_x,
                                        q1_y) + " iron " + nodesReport(ResourceMap.Kind.IRON) + " rock " + nodesReport(
                                                ResourceMap.Kind.ROCK));
    }

    /** Region-path legs cache: source region -> destination region -> {length to last via centre, via x, via y}. */
    private final Map<Region, Map<Region, float[]>> walk_cache = new HashMap<>();

    /**
     * Estimated walking distance in cells from (ax, ay) to (bx, by): straight inside a region, through the centres
     * of the regions between along the engine's own region graph (the graph units path on, so a ridge or lake the
     * pathfinder walks around shows up here). {@code Float.POSITIVE_INFINITY} when no land path exists.
     * Cached per region pair; the map is never iterated, so identity keys keep it deterministic.
     */
    public float walk(int ax, int ay, int bx, int by) {
        UnitGrid grid = player.getWorld().getUnitGrid();
        Region src = grid.getRegion(ax, ay);
        Region dst = grid.getRegion(bx, by);
        if (src == null || dst == null)
            return Float.POSITIVE_INFINITY;
        float direct = dist(ax, ay, bx, by);
        if (src == dst)
            return direct;
        Map<Region, float[]> from = walk_cache.computeIfAbsent(src, k -> new HashMap<>());
        float[] via = from.get(dst);
        if (via == null) {
            via = regionLegs(grid, src, dst, ax, ay);
            from.put(dst, via);
        }
        if (via[0] < 0f)
            return Float.POSITIVE_INFINITY;
        return Math.max(direct, via[0] + dist(Math.round(via[1]), Math.round(via[2]), bx, by));
    }

    /** Straight-line distance plus the detour a site may add before it is rejected. */
    public static float detourLimit(float direct) {
        return 1.5f * direct + 40f;
    }

    /** True when the walk from the start to (gx, gy) is not a long way round. */
    public boolean walkable(int gx, int gy) {
        return walk(start_x, start_y, gx, gy) <= detourLimit(dist(start_x, start_y, gx, gy));
    }

    private static float[] regionLegs(@NonNull UnitGrid grid, @NonNull Region src, @NonNull Region dst, int ax,
            int ay) {
        Region end = PathFinder.findPathRegion(grid, src, dst);
        if (end == null)
            return new float[]{-1f, 0f, 0f};
        // parent chain dst -> ... -> src: the intermediate centres are the way points a unit walks through
        List<int[]> centres = new ArrayList<>();
        for (Region r = (Region) end.getParent(); r != null && r != src; r = (Region) r.getParent())
            centres.add(new int[]{r.getGridX(), r.getGridY()});
        float len = 0f;
        int px = ax;
        int py = ay;
        for (int i = centres.size() - 1; i >= 0; i--) {
            int[] c = centres.get(i);
            len += dist(px, py, c[0], c[1]);
            px = c[0];
            py = c[1];
        }
        return new float[]{len, px, py};
    }

    /** The three nearest nodes of a kind to the armory site as "walk/straight" cell distances (diagnostics). */
    private String nodesReport(ResourceMap.@NonNull Kind kind) {
        StringBuilder sb = new StringBuilder();
        for (ResourceMap.Node n : resources.nearestAlive(kind, armory_x, armory_y, 150, 3)) {
            if (sb.length() > 0)
                sb.append(' ');
            float w = walk(armory_x, armory_y, n.grid_x, n.grid_y);
            sb.append(w == Float.POSITIVE_INFINITY ? "inf" : String.valueOf((int) w)).append('/').append((int) dist(
                    armory_x, armory_y, n.grid_x, n.grid_y));
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    // ------------------------------------------------------------------ armory

    /**
     * Armory site: a legal size-5 site within the search sector toward the enemy/centre that minimises the
     * peon-seconds per iron warrior: 0.9 x wood distance + 0.45 x iron distance (metres), plus a small
     * start-distance term so the opening does not wander, and penalties for sites with little iron nearby.
     */
    public void chooseArmorySite() {
        // direction of the search sector: toward the map centre (which in a 1v1 is also toward the enemy)
        int cx = grid_size / 2;
        int cy = grid_size / 2;
        float sdx = cx - start_x;
        float sdy = cy - start_y;
        float slen = (float) Math.sqrt(sdx * sdx + sdy * sdy);
        final float sx = slen < 1f ? ux : sdx / slen;
        final float sy = slen < 1f ? uy : sdy / slen;
        final float cos_limit = (float) StrictMath.cos(StrictMath.toRadians(params.armory_sector_deg));
        final int min_d = params.armory_min_dist;
        final int max_d = params.armory_max_dist;
        SiteFinder.Site best = sites.best(Race.BUILDING_ARMORY, start_x, start_y, max_d, island, (gx, gy) -> {
            float dx = gx - start_x;
            float dy = gy - start_y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < min_d || d > max_d)
                return Float.NEGATIVE_INFINITY;
            if (d > 3f && (dx * sx + dy * sy) / d < cos_limit)
                return Float.NEGATIVE_INFINITY;
            // the crews walk there: a site across a ridge or lake costs the opening minutes, not the straight
            // line. The detour is priced into the cost; only a walk no opening survives is rejected outright.
            float w = walk(start_x, start_y, gx, gy);
            if (w > params.armory_max_walk || w > 2.5f * d + 60f)
                return Float.NEGATIVE_INFINITY;
            return -(armoryCost(gx, gy, d) + params.site_w_detour * (w - d));
        });
        if (best == null) {
            // nothing in the sector: take anything legal near the start
            best = sites.best(Race.BUILDING_ARMORY, start_x, start_y, max_d, island, (gx, gy) -> {
                float w = walk(start_x, start_y, gx, gy);
                return w > detourLimit(dist(gx, gy, start_x, start_y)) ? Float.NEGATIVE_INFINITY : -w;
            });
        }
        if (best != null) {
            armory_x = best.gx();
            armory_y = best.gy();
            armory_score = -best.score();
        } else {
            armory_x = start_x;
            armory_y = start_y;
            armory_score = Float.MAX_VALUE;
        }
    }

    /**
     * Site for an expansion armory: next to the richest live iron cluster on our half of the map, at least 28 cells
     * from the main armory and within {@code max_dist} of it, with trees nearby. Iron nodes hold 10 units each, so
     * the field by the main armory is mined out in minutes and the walk to fresh iron decides the long game.
     */
    public SiteFinder.@Nullable Site chooseExpansionSite(int main_x, int main_y, int max_dist) {
        List<ResourceMap.Node> iron = resources.within(ResourceMap.Kind.IRON, main_x, main_y, max_dist, true);
        List<float[]> cands = new ArrayList<>();
        for (ResourceMap.Node n : iron) {
            float d_main = dist(n.grid_x, n.grid_y, main_x, main_y);
            if (d_main < 28f || walk(main_x, main_y, n.grid_x, n.grid_y) > detourLimit(d_main))
                continue;
            // our half only: nearer to our start than to theirs
            if (dist(n.grid_x, n.grid_y, enemy_x, enemy_y) < dist(n.grid_x, n.grid_y, start_x, start_y))
                continue;
            int cluster = resources.countAlive(ResourceMap.Kind.IRON, n.grid_x, n.grid_y, 30);
            cands.add(new float[]{n.grid_x, n.grid_y, cluster * 10f - 0.15f * d_main});
        }
        if (cands.isEmpty())
            return null;
        cands.sort((a, b) -> {
            int c = Float.compare(b[2], a[2]);
            if (c != 0)
                return c;
            c = Float.compare(a[0], b[0]);
            return c != 0 ? c : Float.compare(a[1], b[1]);
        });
        SiteFinder.Site best = null;
        for (int i = 0; i < Math.min(4, cands.size()); i++) {
            int cx = (int) cands.get(i)[0];
            int cy = (int) cands.get(i)[1];
            SiteFinder.Site s = sites.best(Race.BUILDING_ARMORY, cx, cy, 14, island, (gx, gy) -> {
                float d_m = dist(gx, gy, main_x, main_y);
                float w_m = walk(main_x, main_y, gx, gy);
                if (d_m < 28f || w_m > detourLimit(d_m))
                    return Float.NEGATIVE_INFINITY;
                float d_i = ironDistanceMetres(gx, gy, 8, 40);
                float d_w = meanDistanceMetres(ResourceMap.Kind.TREE, gx, gy, 20, 30);
                return -(0.6f * d_i + 0.9f * d_w + 0.08f * w_m) + (nearWater(gx, gy, 8) ? -15f : 0f);
            });
            if (s != null && (best == null || s.score() > best.score()))
                best = s;
        }
        return best;
    }

    /** Peon-seconds per iron warrior at a site (lower is better). */
    public float armoryCost(int gx, int gy, float dist_start) {
        float d_w = meanDistanceMetres(ResourceMap.Kind.TREE, gx, gy, 40, 40);
        float d_i = ironDistanceMetres(gx, gy, 15, (int) (params.site_iron_radius * 2));
        int iron_near = resources.countAlive(ResourceMap.Kind.IRON, gx, gy, (int) params.site_iron_radius);
        float cost = 116f + params.site_w_wood * d_w + params.site_w_iron * d_i + params.site_w_start * dist_start;
        if (iron_near < params.site_min_iron_nodes)
            cost += params.site_penalty_few_iron * (params.site_min_iron_nodes - iron_near) / (float) params.site_min_iron_nodes;
        if (nearWater(gx, gy, 8))
            cost += 15f;
        // prefer a little height (towers around it get the terrain bonus)
        cost -= 0.2f * Math.clamp(sites.height(gx, gy) - sites.height(start_x, start_y), -20f, 20f);
        return cost;
    }

    /** Mean distance in metres to the n nearest live nodes of a kind (search radius in cells); large when few. */
    public float meanDistanceMetres(ResourceMap.@NonNull Kind kind, int gx, int gy, int n, int radius) {
        List<ResourceMap.Node> nodes = resources.nearestAlive(kind, gx, gy, radius, n);
        if (nodes.isEmpty())
            return radius * 2f * 2f;
        float sum = 0f;
        for (ResourceMap.Node node : nodes)
            sum += node.distanceTo(gx, gy) * 2f;
        // missing nodes count as being at the edge of the search radius
        sum += (n - nodes.size()) * radius * 2f;
        return sum / n;
    }

    /** Like {@link #meanDistanceMetres} for iron, counting nodes nearer the enemy than us at double distance. */
    public float ironDistanceMetres(int gx, int gy, int n, int radius) {
        List<ResourceMap.Node> nodes = resources.nearestAlive(ResourceMap.Kind.IRON, gx, gy, radius, n);
        if (nodes.isEmpty())
            return radius * 2f * 2f;
        float sum = 0f;
        for (ResourceMap.Node node : nodes) {
            float d = node.distanceTo(gx, gy) * 2f;
            if (ownership(node.grid_x, node.grid_y) < 0.5f)
                d *= params.site_enemy_iron_factor;
            sum += d;
        }
        sum += (n - nodes.size()) * radius * 2f;
        return sum / n;
    }

    /** 1 = next to us, 0 = next to the enemy, 0.5 = midway (by start distances). */
    public float ownership(int gx, int gy) {
        float ds = dist(gx, gy, start_x, start_y);
        float de = dist(gx, gy, enemy_x, enemy_y);
        return de / Math.max(1f, ds + de);
    }

    private boolean nearWater(int gx, int gy, int r) {
        UnitGrid grid = player.getWorld().getUnitGrid();
        for (int y = gy - r; y <= gy + r; y += 2)
            for (int x = gx - r; x <= gx + r; x += 2)
                if (x >= 0 && y >= 0 && x < grid_size && y < grid_size && grid.isWater(x, y))
                    return true;
        return false;
    }

    // ------------------------------------------------------------------ quarters

    private void chooseFirstQuartersSite() {
        SiteFinder.Site best = sites.best(Race.BUILDING_QUARTERS, start_x, start_y, 24, island, (gx, gy) -> {
            float d = dist(gx, gy, start_x, start_y);
            // keep out of the armory site's way
            if (dist(gx, gy, armory_x, armory_y) < params.building_spacing)
                return Float.NEGATIVE_INFINITY;
            int trees = resources.countAlive(ResourceMap.Kind.TREE, gx, gy, 10);
            if (trees < 3 || !walkable(gx, gy))
                return Float.NEGATIVE_INFINITY; // the builders need wood within reach and a direct walk
            return -d + 0.4f * Math.min(trees, 12);
        });
        if (best == null) {
            q1_x = start_x;
            q1_y = start_y;
        } else {
            q1_x = best.gx();
            q1_y = best.gy();
        }
    }

    private void chooseFallbackSite() {
        // 15-30 cells behind the first quarters, away from the enemy
        int ax = Math.round(q1_x - ux * 22);
        int ay = Math.round(q1_y - uy * 22);
        SiteFinder.Site best = sites.best(Race.BUILDING_ARMORY, ax, ay, 16, island, (gx, gy) -> {
            if (dist(gx, gy, q1_x, q1_y) < params.building_spacing || !walkable(gx, gy))
                return Float.NEGATIVE_INFINITY;
            return -dist(gx, gy, ax, ay) - 0.3f * meanDistanceMetres(ResourceMap.Kind.TREE, gx, gy, 20, 25);
        });
        if (best != null) {
            fallback_x = best.gx();
            fallback_y = best.gy();
        }
    }

    /**
     * Next quarters site: 11-26 cells from the armory anchor, on the start side if possible, tucked against
     * trees, at least {@code building_spacing} from every existing/planned building centre.
     */
    public SiteFinder.@Nullable Site nextQuartersSite(int anchor_x, int anchor_y, @NonNull List<int[]> reserved) {
        final int min_d = params.quarters_min_dist;
        final int max_d = params.quarters_max_dist;
        SiteFinder.Site best = sites.best(Race.BUILDING_QUARTERS, anchor_x, anchor_y, max_d + 4, island, (gx, gy) -> {
            float d = dist(gx, gy, anchor_x, anchor_y);
            if (d < min_d)
                return Float.NEGATIVE_INFINITY;
            if (tooClose(gx, gy, reserved))
                return Float.NEGATIVE_INFINITY;
            float dot = (gx - anchor_x) * ux + (gy - anchor_y) * uy;
            int live = resources.countAlive(ResourceMap.Kind.TREE, gx, gy, 10);
            if (live < 3 || !walkable(gx, gy))
                return Float.NEGATIVE_INFINITY; // builders fetch wood from the nearest tree: none nearby, no site
            float trees = Math.min(live, 12);
            float score = -d + 0.5f * trees - (dot > 0 ? 0.5f * dot : 0f);
            if (d > max_d)
                score -= (d - max_d) * 2f;
            return score;
        });
        if (best == null) {
            // widen: anywhere on our island near the start, still next to trees
            best = sites.best(Race.BUILDING_QUARTERS, start_x, start_y, 40, island, (gx, gy) -> tooClose(gx, gy,
                    reserved) || resources.countAlive(ResourceMap.Kind.TREE, gx, gy, 10) < 3 || !walkable(gx,
                            gy) ? Float.NEGATIVE_INFINITY : -dist(gx, gy, anchor_x, anchor_y));
        }
        if (best == null) {
            best = sites.best(Race.BUILDING_QUARTERS, start_x, start_y, 40, island, (gx, gy) -> tooClose(gx, gy,
                    reserved) || !walkable(gx, gy) ? Float.NEGATIVE_INFINITY : -dist(gx, gy, anchor_x, anchor_y));
        }
        // a cramped start (beach, cliffs, the first buildings) can leave nothing within 40 cells: a quarters
        // further out still breeds, no quarters at all halves the peon stream for the whole game
        if (best == null) {
            best = sites.best(Race.BUILDING_QUARTERS, anchor_x, anchor_y, 75, island, (gx, gy) -> tooClose(gx, gy,
                    reserved) || resources.countAlive(ResourceMap.Kind.TREE, gx, gy, 10) < 3
                    || walk(anchor_x, anchor_y, gx, gy) > detourLimit(dist(gx, gy, anchor_x,
                            anchor_y)) ? Float.NEGATIVE_INFINITY : -walk(anchor_x, anchor_y, gx, gy));
        }
        if (best == null) {
            best = sites.best(Race.BUILDING_QUARTERS, anchor_x, anchor_y, 75, island, (gx, gy) -> tooClose(gx, gy,
                    reserved) || walk(anchor_x, anchor_y, gx, gy) > detourLimit(dist(gx, gy, anchor_x,
                            anchor_y)) ? Float.NEGATIVE_INFINITY : -walk(anchor_x, anchor_y, gx, gy));
        }
        return best;
    }

    // ------------------------------------------------------------------ towers

    /** Bearings (degrees, relative to the approach direction) of the defensive tower ring, in build order. */
    private static final float[] RING_BEARINGS = {0f, 40f, -40f, 180f, 90f, -90f, 135f, -135f, 20f, -20f, 160f, -160f};

    /**
     * Site for the n-th defensive tower around an anchor (the armory): ring of radius {@code tower_ring_radius},
     * on the enemy bearing first, at least {@code tower_spacing_min} from other towers and clear of the anchor's
     * footprint. Higher ground is preferred.
     */
    public SiteFinder.@Nullable Site nextTowerSite(int anchor_x, int anchor_y, int index,
            @NonNull List<int[]> existing_towers, @NonNull List<int[]> reserved) {
        float bearing = RING_BEARINGS[index % RING_BEARINGS.length];
        float base = (float) StrictMath.atan2(uy, ux);
        float a = base + (float) StrictMath.toRadians(bearing);
        int r = params.tower_ring_radius + (index / RING_BEARINGS.length) * 6;
        int ix = Math.round(anchor_x + r * (float) StrictMath.cos(a));
        int iy = Math.round(anchor_y + r * (float) StrictMath.sin(a));
        float h0 = sites.height(anchor_x, anchor_y);
        return sites.best(Race.BUILDING_TOWER, ix, iy, 7, island, (gx, gy) -> {
            if (dist(gx, gy, anchor_x, anchor_y) < 7f)
                return Float.NEGATIVE_INFINITY;
            for (int[] t : existing_towers)
                if (dist(gx, gy, t[0], t[1]) < params.tower_spacing_min)
                    return Float.NEGATIVE_INFINITY;
            for (int[] b : reserved)
                if (dist(gx, gy, b[0], b[1]) < 7f)
                    return Float.NEGATIVE_INFINITY;
            float d = dist(gx, gy, ix, iy);
            if (walk(anchor_x, anchor_y, gx, gy) > detourLimit(dist(gx, gy, anchor_x, anchor_y)))
                return Float.NEGATIVE_INFINITY;
            float height = Math.clamp(sites.height(gx, gy) - h0, -20f, 20f);
            return -d + 0.4f * height;
        });
    }

    /** A tower site near a point (iron field, forward position), at least 7 from other towers/buildings. */
    public SiteFinder.@Nullable Site towerSiteNear(int px, int py, int radius, @NonNull List<int[]> reserved,
            int min_dist_from_point) {
        return sites.best(Race.BUILDING_TOWER, px, py, radius, island, (gx, gy) -> {
            float d = dist(gx, gy, px, py);
            if (d < min_dist_from_point)
                return Float.NEGATIVE_INFINITY;
            for (int[] b : reserved)
                if (dist(gx, gy, b[0], b[1]) < params.tower_spacing_min)
                    return Float.NEGATIVE_INFINITY;
            if (walk(px, py, gx, gy) > detourLimit(d))
                return Float.NEGATIVE_INFINITY;
            return -d + 0.3f * Math.clamp(sites.height(gx, gy) - sites.height(px, py), -20f, 20f);
        });
    }

    // ------------------------------------------------------------------ helpers

    private boolean tooClose(int gx, int gy, @NonNull List<int[]> reserved) {
        for (int[] b : reserved)
            if (dist(gx, gy, b[0], b[1]) < params.building_spacing)
                return true;
        return false;
    }

    public static float dist(int ax, int ay, int bx, int by) {
        float dx = ax - bx;
        float dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public static int dist2(int ax, int ay, int bx, int by) {
        int dx = ax - bx;
        int dy = ay - by;
        return dx * dx + dy * dy;
    }

    /** Cell at a distance along the approach vector from a point (clamped to the grid). */
    public int[] along(int x, int y, float cells) {
        return new int[]{Math.clamp(Math.round(x + ux * cells), 0, grid_size - 1), Math.clamp(Math.round(
                y + uy * cells), 0, grid_size - 1)};
    }

    /** Cell at a distance from (x,y) toward (tx,ty). */
    public int[] toward(int x, int y, int tx, int ty, float cells) {
        float dx = tx - x;
        float dy = ty - y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f)
            return new int[]{x, y};
        return new int[]{Math.clamp(Math.round(x + dx / len * cells), 0, grid_size - 1), Math.clamp(Math.round(
                y + dy / len * cells), 0, grid_size - 1)};
    }

    public boolean sameIsland(int gx, int gy) {
        return gx >= 0 && gy >= 0 && gx < grid_size && gy < grid_size && map.getIslandId(gx, gy) == island;
    }

    public static int[] centre(@NonNull LandBuilding b) {
        return new int[]{b.getGridX(), b.getGridY()};
    }
}
