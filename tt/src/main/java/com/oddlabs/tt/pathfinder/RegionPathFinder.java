package com.oddlabs.tt.pathfinder;

import org.jspecify.annotations.NonNull;

final class RegionPathFinder extends AStarAlgorithm {
    private final @NonNull Node dst_region;

    public RegionPathFinder(@NonNull UnitGrid unit_grid, @NonNull Node dst_region) {
        this(unit_grid, dst_region, RegionBuilder.MAX_EXAMINED_NODES_PER_PATH);
    }

    public RegionPathFinder(@NonNull UnitGrid unit_grid, @NonNull Node dst_region, int max_examined_nodes) {
        super(unit_grid, dst_region.getGridX(), dst_region.getGridY(), false, max_examined_nodes);
        this.dst_region = dst_region;
    }

    @Override
    public boolean touchNeighbour(Occupant occ) {
        return false;
    }

    @Override
    protected boolean isPathComplete(int dist_squared, Node node) {
        return node == dst_region;
    }
}
