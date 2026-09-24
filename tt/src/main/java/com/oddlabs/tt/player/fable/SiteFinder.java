package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Finds legal building sites near a desired cell and ranks them with a caller supplied score. Legality mirrors what
 * the human placement cursor allows ({@link LandBuilding#doIsPlacingLegal} + {@link HeightMap#canBuild}), so the AI
 * never gets a site the player could not use.
 */
public final class SiteFinder {
    /** Scores a legal site; higher is better, {@code Float.NEGATIVE_INFINITY} rejects it. */
    @FunctionalInterface
    public interface Scorer {
        float score(int gx, int gy);
    }

    public record Site(int gx, int gy, float score) {
    }

    private final @NonNull Player player;
    private final @NonNull UnitGrid grid;
    private final @NonNull HeightMap map;

    public SiteFinder(@NonNull Player player) {
        this.player = player;
        this.grid = player.getWorld().getUnitGrid();
        this.map = player.getWorld().getHeightMap();
    }

    public @NonNull BuildingTemplate template(int template_id) {
        return player.getRace().getBuildingTemplate(template_id);
    }

    /** True when a building of this type could be placed with its centre on (gx, gy) right now. */
    public boolean isLegal(int template_id, int gx, int gy) {
        if (gx < 0 || gy < 0 || gx >= grid.getGridSize() || gy >= grid.getGridSize())
            return false;
        BuildingTemplate template = template(template_id);
        return map.canBuild(gx, gy, template.getPlacingSize()) && template.isPlacingLegal(grid, gx, gy)
                && grid.getRegion(gx, gy) != null;
    }

    /**
     * Cheap pre-check used to skip cells before the full legality test: the build grid value must allow the size and
     * the cell must be on the home island.
     */
    public boolean mayBuild(int template_id, int gx, int gy, int island) {
        if (gx < 0 || gy < 0 || gx >= grid.getGridSize() || gy >= grid.getGridSize())
            return false;
        return map.canBuild(gx, gy, template(template_id).getPlacingSize()) && map.getIslandId(gx, gy) == island;
    }

    /**
     * Best legal site within {@code radius} cells of (cx, cy) according to {@code scorer}. Cells are visited in a
     * fixed order and ties keep the first (closest to the top-left), so the result is deterministic.
     * Cost: O(radius^2) cheap checks plus a full legality check for the cells that pass, so keep radius <= ~30.
     */
    public @Nullable Site best(int template_id, int cx, int cy, int radius, int island, @NonNull Scorer scorer) {
        Site best = null;
        int step = template_id == Race.BUILDING_TOWER ? 1 : 2; // big buildings: every other cell is plenty
        for (int gy = cy - radius; gy <= cy + radius; gy += step) {
            for (int gx = cx - radius; gx <= cx + radius; gx += step) {
                if (!mayBuild(template_id, gx, gy, island))
                    continue;
                float s = scorer.score(gx, gy);
                if (s == Float.NEGATIVE_INFINITY || (best != null && s <= best.score()))
                    continue;
                if (!isLegal(template_id, gx, gy))
                    continue;
                best = new Site(gx, gy, s);
            }
        }
        return best;
    }

    /** Terrain height at a cell (metres), for high-ground scoring. */
    public float height(int gx, int gy) {
        return map.getWrappedHeight(gx, gy);
    }

    public @NonNull HeightMap getHeightMap() {
        return map;
    }

    public @NonNull UnitGrid getGrid() {
        return grid;
    }

    /** Footprint half-size in cells (a building of placing size s occupies (2s-3)x(2s-3) cells around its centre). */
    public static int halfFootprint(@NonNull BuildingTemplate template) {
        return template.getPlacingSize() - 2;
    }
}
