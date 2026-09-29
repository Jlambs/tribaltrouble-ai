package com.oddlabs.tt.pathfinder;

public interface ScanFilter {
    int getMinRadius();

    int getMaxRadius();

    boolean filter(int grid_x, int grid_y, Occupant occ);

    /**
     * The cell tags ({@link UnitGrid#TAG_EMPTY} and the others) whose cells {@link #filter} may act on, as a bit mask:
     * bit {@code t} for tag {@code t}. For the cells of every other tag, filter must return false and change nothing,
     * so a headless {@link UnitGrid#scan} may skip them. The default is every tag: filter sees every cell.
     */
    default long tagMask() {
        return -1L;
    }
}
