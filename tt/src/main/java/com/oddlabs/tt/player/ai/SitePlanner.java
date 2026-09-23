package com.oddlabs.tt.player.ai;

import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Picks building sites. The armory goes where iron and trees can be gathered fastest, weighed against the walk from
 * the start and how exposed the spot is; quarters go into dense trees so they are built quickly; towers cover the
 * armory from the enemy's side.
 */
final class SitePlanner {
    /** Seconds a peon spends harvesting one unit: ten hits, one per second. */
    private static final float HARVEST_SECONDS = 10f;
    /** Seconds of walking per meter of distance for a round trip: out at 5 m/s, back loaded at 4 m/s. */
    private static final float ROUND_TRIP_SECONDS_PER_METER = 1f / 5f + 1f / 4f;
    private static final int MISSING_DISTANCE = 140;
    /** Free cells to keep between buildings so peons and warriors can walk between them. */
    private static final int BUILDING_GAP = 3;

    static boolean debug_sites = false;

    private final @NonNull MapAnalysis map;
    private final @NonNull Player owner;
    private final @NonNull Strategy strategy;
    private final @NonNull DistanceField start_field;
    private final @NonNull DistanceField enemy_field;
    private final int start_x;
    private final int start_y;
    private final int enemy_x;
    private final int enemy_y;

    SitePlanner(@NonNull MapAnalysis map, @NonNull Player owner, @NonNull Strategy strategy, int start_x, int start_y,
            int enemy_x, int enemy_y, @NonNull DistanceField start_field, @NonNull DistanceField enemy_field) {
        this.map = map;
        this.owner = owner;
        this.strategy = strategy;
        this.start_x = start_x;
        this.start_y = start_y;
        this.enemy_x = enemy_x;
        this.enemy_y = enemy_y;
        this.start_field = start_field;
        this.enemy_field = enemy_field;
    }

    private @NonNull BuildingTemplate template(int type) {
        return owner.getRace().getBuildingTemplate(type);
    }

    /** Fraction of the way from our start to the enemy's, measured by walking distance. 0.5 is the middle. */
    float exposure(int x, int y) {
        int ours = start_field.get(x, y);
        int theirs = enemy_field.get(x, y);
        if (ours == DistanceField.UNREACHABLE)
            return 1f;
        if (theirs == DistanceField.UNREACHABLE)
            return 0f;
        return ours / (float) Math.max(1, ours + theirs);
    }

    /**
     * Seconds of peon time per unit gathered for the cheapest `units` units of the supplies around a site.
     */
    static float gatherSeconds(@NonNull DistanceField field, @NonNull List<? extends Supply> supplies, int units,
            int per_node, int max_meters) {
        int sx = field.getSourceX();
        int sy = field.getSourceY();
        int limit2 = (max_meters / 2) * (max_meters / 2);
        int[] dists = new int[supplies.size()];
        int n = 0;
        for (Supply s : supplies) {
            if (s.isEmpty())
                continue;
            if (MapAnalysis.dist2(sx, sy, s.getGridX(), s.getGridY()) > limit2)
                continue;
            int d = field.getAround(s.getGridX(), s.getGridY(), 1);
            if (d != DistanceField.UNREACHABLE)
                dists[n++] = d;
        }
        Arrays.sort(dists, 0, n);
        int remaining = units;
        float total = 0f;
        for (int i = 0; i < n && remaining > 0; i++) {
            int take = Math.min(per_node, remaining);
            // Peons drop off at the building's edge, a few meters from its center.
            total += take * Math.max(0, dists[i] - 5);
            remaining -= take;
        }
        // Whatever is not found within the window is further away than that, however far.
        total += remaining * (float) Math.max(MISSING_DISTANCE, max_meters + 60);
        float avg = total / units;
        return HARVEST_SECONDS + ROUND_TRIP_SECONDS_PER_METER * avg;
    }

    /**
     * Seconds of gathering per iron warrior (two wood and one iron) if the armory stood at the field's source.
     */
    float warriorGatherCost(@NonNull DistanceField field) {
        // Look far enough ahead to feed the armory well into the middle game, not just the first warriors.
        float tree = gatherSeconds(field, map.getTrees(), 300, 10, 120);
        float iron = gatherSeconds(field, map.getIron(), 150, 10, 200);
        return 2 * tree + iron;
    }

    @Nullable
    Site findArmorySite(@NonNull List<@NonNull Site> reserved) {
        Site best = findArmorySite(reserved, strategy.max_armory_distance, start_field);
        // A start boxed in by cliffs may have nothing worth building on nearby; look further before settling.
        if (best == null || -best.score > 170f) {
            Site further = findArmorySite(reserved, strategy.max_armory_distance * 2, start_field);
            if (further != null && (best == null || further.score > best.score))
                best = further;
        }
        return best;
    }

    /**
     * A site for a second armory once the supplies around the first run low, judged by the walk from the base
     * (from_field) instead of from the start.
     */
    @Nullable
    Site findExpansionSite(@NonNull List<@NonNull Site> reserved, @NonNull DistanceField from_field) {
        return findArmorySite(reserved, strategy.max_armory_distance, from_field);
    }

    private @Nullable Site findArmorySite(@NonNull List<@NonNull Site> reserved, int max_distance,
            @NonNull DistanceField from_field) {
        BuildingTemplate armory = template(Race.BUILDING_ARMORY);
        int size = map.getSize();
        List<Site> candidates = new ArrayList<>();
        List<IronSupply> iron = map.getIron();
        for (int y = 2; y < size - 2; y += 2) {
            for (int x = 2; x < size - 2; x += 2) {
                int d = from_field.get(x, y);
                if (d > max_distance)
                    continue;
                if (!map.canPlace(armory, x, y) || conflicts(reserved, x, y, RaceSizes.ARMORY))
                    continue;
                // A rough version of the full cost below, with distances as the crow flies, to pick which sites are
                // worth the exact evaluation.
                float iron_cycle = HARVEST_SECONDS + ROUND_TRIP_SECONDS_PER_METER * Math.max(0f,
                        1.25f * averageDistance(iron, x, y, 15) - 5f);
                float tree_cycle = HARVEST_SECONDS + ROUND_TRIP_SECONDS_PER_METER * Math.max(0f,
                        1.25f * map.averageTreeDistance(x, y, 30, 60, 150f) - 5f);
                float quick = 2 * tree_cycle + iron_cycle + strategy.armory_delay_weight * d / 5f + strategy.armory_distance_weight * d + strategy.armory_threat_weight * Math.max(
                        0f, exposure(x, y) - .42f);
                candidates.add(new Site(x, y, -quick));
            }
        }
        if (candidates.isEmpty())
            return null;
        candidates.sort((a, b) -> Float.compare(b.score, a.score));
        Site best = null;
        float best_cost = Float.MAX_VALUE;
        List<Site> evaluated = new ArrayList<>();
        for (Site c : candidates) {
            if (evaluated.size() >= 24)
                break;
            // Skip candidates next to one already evaluated; they would score about the same.
            boolean near = false;
            for (Site e : evaluated)
                near |= MapAnalysis.dist2(e.x, e.y, c.x, c.y) < 5 * 5;
            if (near)
                continue;
            evaluated.add(c);
            DistanceField field = map.computeField(c.x, c.y, 220);
            int d = from_field.get(c.x, c.y);
            float gather = warriorGatherCost(field);
            float build = constructionSeconds(c.x, c.y, QUARTERS_WOOD, strategy.armory_builders);
            // Delays to the armory hold back the whole economy; weigh them against gathering speed, which pays off
            // on every warrior of the game.
            float delay = strategy.armory_delay_weight * (build + d / 5f);
            float threat = strategy.armory_threat_weight * Math.max(0f, exposure(c.x, c.y) - .42f);
            float cost = gather + delay + strategy.armory_distance_weight * d + threat + (hasNear(map.getRocks(), c.x,
                    c.y, 45) ? 0f : 4f);
            if (debug_sites)
                System.out.println(String.format(
                        "armory candidate %d,%d: gather %.1f (tree %.1f iron %.1f) build %.0fs" + " dist %dm threat %.1f -> %.1f",
                        c.x, c.y, gather,
                        gatherSeconds(field, map.getTrees(), 300, 10, 120),
                        gatherSeconds(field, map.getIron(), 150, 10, 200), build, d, threat, cost));
            if (cost < best_cost) {
                best_cost = cost;
                best = new Site(c.x, c.y, -cost);
            }
        }
        return best;
    }

    /** Average straight-line meters to the nearest count standing supplies; missing ones count as 400 m. */
    private static float averageDistance(@NonNull List<? extends Supply> supplies, int x, int y, int count) {
        int[] best = new int[count];
        java.util.Arrays.fill(best, Integer.MAX_VALUE);
        for (Supply s : supplies) {
            if (s.isEmpty())
                continue;
            int d2 = MapAnalysis.dist2(x, y, s.getGridX(), s.getGridY());
            if (d2 >= best[count - 1])
                continue;
            int i = count - 1;
            while (i > 0 && best[i - 1] > d2) {
                best[i] = best[i - 1];
                i--;
            }
            best[i] = d2;
        }
        float sum = 0f;
        for (int d2 : best)
            sum += d2 == Integer.MAX_VALUE ? 400f : (float) Math.sqrt(d2) * 2f;
        return sum / count;
    }

    private static boolean hasNear(@NonNull List<? extends Supply> supplies, int x, int y, int radius) {
        int r2 = radius * radius;
        for (Supply s : supplies)
            if (!s.isEmpty() && MapAnalysis.dist2(x, y, s.getGridX(), s.getGridY()) <= r2)
                return true;
        return false;
    }

    /**
     * Quarters site: the spot that goes up fastest, counting the walk there (anchor_weight seconds per meter from the
     * anchor) and the builders' trips for wood, plus a pull towards (toward_x, toward_y) where the peons will work.
     */
    @Nullable
    Site findQuartersSite(@NonNull List<@NonNull Site> reserved, int anchor_x, int anchor_y,
            int max_anchor_dist, @Nullable DistanceField anchor_field, int toward_x, int toward_y, int builders,
            float anchor_weight, float toward_weight) {
        BuildingTemplate quarters = template(Race.BUILDING_QUARTERS);
        Site best = null;
        int r = max_anchor_dist / 2 + 2;
        for (int y = anchor_y - r; y <= anchor_y + r; y++) {
            for (int x = anchor_x - r; x <= anchor_x + r; x++) {
                if (!map.inside(x, y))
                    continue;
                int d_anchor = anchor_field != null ? anchor_field.get(x, y) : (int) MapAnalysis.meters(anchor_x,
                        anchor_y, x, y);
                if (d_anchor > max_anchor_dist)
                    continue;
                if (!start_field.reachable(x, y))
                    continue;
                if (!map.canPlace(quarters, x, y) || conflicts(reserved, x, y, RaceSizes.QUARTERS))
                    continue;
                float cost = constructionSeconds(x, y, QUARTERS_WOOD,
                        builders) + anchor_weight * d_anchor + toward_weight * MapAnalysis.meters(x, y, toward_x,
                                toward_y) + 120f * Math.max(0f, exposure(x, y) - .45f);
                if (best == null || -cost > best.score)
                    best = new Site(x, y, -cost);
            }
        }
        return best;
    }

    /** Wood needed for a quarters or armory: 200 hit points at five per piece. */
    static final int QUARTERS_WOOD = 40;
    static final int TOWER_WOOD = 20;
    /** Seconds a builder spends putting one piece of wood into a building. */
    private static final float REPAIR_SECONDS = 5f;

    /**
     * Seconds for the given number of builders to raise a building needing `wood` pieces at (x, y): each piece is
     * chopped (10 s), carried from one of the nearest trees and hammered in (5 s). Builders spread over the nearest
     * trees, a handful per tree.
     */
    float constructionSeconds(int x, int y, int wood, int builders) {
        builders = Math.max(1, builders);
        int trees = Math.max(3, (builders + 2) / 3);
        // Distances from the start expose trees behind cliffs that look close as the crow flies.
        float avg = map.averageTreeDistance(x, y, trees, 35, 90f, start_field);
        float walk = Math.max(0f, avg * 1.25f - 5f);
        float cycle = HARVEST_SECONDS + REPAIR_SECONDS + ROUND_TRIP_SECONDS_PER_METER * walk;
        return wood * cycle / builders;
    }

    /**
     * Tower site covering the armory (or another building) on the side facing the enemy, on high ground if any.
     */
    @Nullable
    Site findTowerSite(@NonNull List<@NonNull Site> reserved, int cx, int cy, int min_r, int max_r,
            @NonNull List<int @NonNull []> existing_towers, int face_x, int face_y) {
        BuildingTemplate tower = template(Race.BUILDING_TOWER);
        float fx = face_x - cx;
        float fy = face_y - cy;
        float flen = (float) Math.sqrt(fx * fx + fy * fy);
        if (flen > 0) {
            fx /= flen;
            fy /= flen;
        }
        float base_height = map.height(cx, cy);
        Site best = null;
        for (int y = cy - max_r; y <= cy + max_r; y++) {
            for (int x = cx - max_r; x <= cx + max_r; x++) {
                int d2 = MapAnalysis.dist2(cx, cy, x, y);
                if (d2 < min_r * min_r || d2 > max_r * max_r)
                    continue;
                if (!map.inside(x, y) || (!start_field.reachable(x, y) && !reachableNear(x, y)))
                    continue;
                if (!map.canPlace(tower, x, y) || conflicts(reserved, x, y, RaceSizes.TOWER))
                    continue;
                float d = (float) Math.sqrt(d2);
                float align = ((x - cx) * fx + (y - cy) * fy) / Math.max(1f, d);
                float spread = 0f;
                for (int[] t : existing_towers) {
                    int td2 = MapAnalysis.dist2(t[0], t[1], x, y);
                    if (td2 < 10 * 10)
                        spread -= 20f;
                    else if (td2 < 16 * 16)
                        spread -= 6f;
                }
                float score = 6f * align + .6f * Math.min(8f, map.height(x,
                        y) - base_height) - .15f * constructionSeconds(x, y, TOWER_WOOD,
                                strategy.tower_builders) + spread;
                if (best == null || score > best.score)
                    best = new Site(x, y, score);
            }
        }
        return best;
    }

    /**
     * Any legal site for a building of the given type near (x, y), preferring close spots with trees around, for when
     * the planned site turned out to be blocked.
     */
    @Nullable
    Site findQuartersSiteLike(@NonNull List<@NonNull Site> reserved, int cx, int cy, int radius, int type) {
        BuildingTemplate t = template(type);
        int half = RaceSizes.of(type);
        Site best = null;
        for (int y = cy - radius; y <= cy + radius; y++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (!map.inside(x, y) || !reachableNear(x, y))
                    continue;
                if (!map.canPlace(t, x, y) || conflicts(reserved, x, y, half))
                    continue;
                float score = .5f * Math.min(map.treesAround(x, y, 6), 12) - (float) Math.sqrt(
                        MapAnalysis.dist2(cx, cy, x, y));
                if (best == null || score > best.score)
                    best = new Site(x, y, score);
            }
        }
        return best;
    }

    private boolean reachableNear(int x, int y) {
        return start_field.getAround(x, y, 3) != DistanceField.UNREACHABLE;
    }

    /**
     * Whether a building of the given half size at (x, y) would crowd a reserved site.
     */
    static boolean conflicts(@NonNull List<@NonNull Site> reserved, int x, int y, int half) {
        for (Site s : reserved) {
            int gap = half + s.half + BUILDING_GAP;
            if (Math.abs(s.x - x) < gap && Math.abs(s.y - y) < gap)
                return true;
        }
        return false;
    }

    /** Nearest standing tree to a cell, or null. */
    static @Nullable TreeSupply nearestTree(@NonNull List<@NonNull TreeSupply> trees, int x, int y) {
        TreeSupply best = null;
        int best_d = Integer.MAX_VALUE;
        for (TreeSupply t : trees) {
            if (t.isEmpty())
                continue;
            int d = MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY());
            if (d < best_d) {
                best_d = d;
                best = t;
            }
        }
        return best;
    }

    int getStartX() {
        return start_x;
    }

    int getStartY() {
        return start_y;
    }

    int getEnemyX() {
        return enemy_x;
    }

    int getEnemyY() {
        return enemy_y;
    }

    @NonNull
    DistanceField getStartField() {
        return start_field;
    }

    @NonNull
    DistanceField getEnemyField() {
        return enemy_field;
    }

    /** Half the footprint, in grid cells, of each building kind. */
    static final class RaceSizes {
        static final int QUARTERS = 4;
        static final int ARMORY = 4;
        static final int TOWER = 2;

        static int of(int type) {
            return switch (type) {
                case Race.BUILDING_QUARTERS -> QUARTERS;
                case Race.BUILDING_ARMORY -> ARMORY;
                default -> TOWER;
            };
        }

        private RaceSizes() {
        }
    }

    /** A candidate or chosen building site with its score. */
    static final class Site {
        final int x;
        final int y;
        final float score;
        int half = RaceSizes.QUARTERS;

        Site(int x, int y, float score) {
            this.x = x;
            this.y = y;
            this.score = score;
        }

        @NonNull
        Site withHalf(int half) {
            this.half = half;
            return this;
        }
    }
}
