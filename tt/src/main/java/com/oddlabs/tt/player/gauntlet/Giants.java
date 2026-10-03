package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.WalkController;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Giants: blocks of a Hard copy's attack-walkers that can never reach their cells. A Hard walks each warrior of a wave
 * to one exact cell by its target and never gives up: a walker whose cell is taken, or whose route other stuck walkers
 * wall off, retries forever and is a wall itself (WalkBehaviour BLOCKED), and its copy launches only idle warriors. So
 * waves at the same building set into solid blocks of thousands (s7125: 2,800 at 30 min) that move only when poked: a
 * unit of ours within their 8-cell scan, a death that opens the seal, or a stun that drops a member's walk (it goes
 * idle,
 * and its copy launches it again).
 * <p>
 * Every 10 s from giants_from_ticks (one snapshot earlier, so the first test has two), the enemy warriors are
 * snapshotted: an attack-walker (aggressive WalkController on top) with the same target at two snapshots that moved
 * fewer than 2 cells is stalled. Inert means parked (Intel.isParked), or stalled, still walking and still within a cell
 * of its snapshot cell (a live test: the snapshot is up to 10 s old). The stalled and parked units of the last snapshot
 * sit in 8-cell buckets for the queries. Reads state only; its users (giant_keepout, giant_stun_hold, giant_shred) give
 * fewer or other orders.
 */
final class Giants {
    static final float SNAP_TICKS = 500f; // 10 s
    private static final int SHIFT = 3;

    private final @NonNull GauntletAI ai;
    /** Each enemy warrior's cell and walk target (-1, -1: not attack-walking) at the last snapshot. */
    private final Map<@NonNull Unit, int @NonNull []> cells = new LinkedHashMap<>();
    /** The stalled walkers of the last snapshot: cell, walk target. */
    private final Map<@NonNull Unit, int @NonNull []> stalled = new LinkedHashMap<>();
    private final int side;
    private final List<@NonNull Unit>[] buckets;
    private final List<Integer> used = new ArrayList<>();
    private float last_snap = -5000f;

    @SuppressWarnings("unchecked")
    Giants(@NonNull GauntletAI ai) {
        this.ai = ai;
        side = (ai.map().getSize() >> SHIFT) + 1;
        buckets = new List[side * side];
    }

    /** Whether any user of the classifier is switched on (chief_refresh: its calm test skips stalled walkers). */
    static boolean wanted(@NonNull Strategy st) {
        return st.giant_keepout > 0 || st.giant_stun_hold || st.giant_shred || st.chief_refresh;
    }

    /** Whether the users act now: switched on, and from giants_from_ticks. */
    boolean on() {
        return wanted(ai.strategy()) && ai.now() >= ai.strategy().giants_from_ticks;
    }

    void tick() {
        Strategy st = ai.strategy();
        if (!wanted(st) || ai.now() < st.giants_from_ticks - SNAP_TICKS || !ai.periodDue(last_snap, SNAP_TICKS))
            return;
        last_snap = ai.now();
        for (int b : used)
            buckets[b].clear();
        used.clear();
        stalled.clear();
        Map<Unit, int[]> next = new LinkedHashMap<>();
        int parked = 0;
        for (Unit e : ai.intel().enemy_warriors) {
            if (e.isDead())
                continue;
            int x = e.getGridX();
            int y = e.getGridY();
            int tx = -1;
            int ty = -1;
            if (e.getCurrentController() == e.getPrimaryController()
                    && e.getPrimaryController() instanceof WalkController w
                    && w.isAgressive()) {
                tx = w.getTarget().getGridX();
                ty = w.getTarget().getGridY();
            }
            int[] was = cells.get(e);
            int[] now = {x, y, tx, ty};
            next.put(e, now);
            boolean stuck = was != null && tx >= 0 && was[2] == tx && was[3] == ty
                    && Math.max(Math.abs(x - was[0]), Math.abs(y - was[1])) < 2;
            if (stuck)
                stalled.put(e, now);
            else if (Intel.isParked(e))
                parked++;
            else
                continue;
            int b = bucket(x, y);
            if (buckets[b] == null)
                buckets[b] = new ArrayList<>();
            if (buckets[b].isEmpty())
                used.add(b);
            buckets[b].add(e);
        }
        cells.clear();
        cells.putAll(next);
        if (ai.logging() && ai.periodDue(last_log, 3000f)) {
            last_log = ai.now();
            ai.log(String.format("GIANTS stalled %d parked %d of %d enemy warriors", stalled.size(), parked,
                    next.size()));
        }
    }

    private float last_log = -50000f;

    private int bucket(int x, int y) {
        return Math.clamp(y >> SHIFT, 0, side - 1) * side + Math.clamp(x >> SHIFT, 0, side - 1);
    }

    /** Parked, or a walker stalled at the last snapshot that is still walking within a cell of where it stood. */
    boolean isInert(@NonNull Unit e) {
        if (e.isDead())
            return false;
        return Intel.isParked(e) || isStalled(e);
    }

    /** A walker stalled at the last snapshot that is still walking within a cell of where it stood. */
    boolean isStalled(@NonNull Unit e) {
        int[] s = stalled.get(e);
        return s != null && !e.isDead() && e.getCurrentController() == e.getPrimaryController()
                && e.getPrimaryController() instanceof WalkController w && w.isAgressive()
                && w.getTarget().getGridX() == s[2] && w.getTarget().getGridY() == s[3]
                && Math.max(Math.abs(e.getGridX() - s[0]), Math.abs(e.getGridY() - s[1])) <= 1;
    }

    /** A stalled walker's target at the last snapshot, or null. */
    int @org.jspecify.annotations.Nullable [] stalledTarget(@NonNull Unit e) {
        int[] s = stalled.get(e);
        return s == null ? null : new int[]{s[2], s[3]};
    }

    /**
     * The inert units of the last snapshot's candidates within r cells of (x, y), in bucket order; stops once it has
     * found cap of them (0: no cap).
     */
    @NonNull
    List<@NonNull Unit> inertNear(int x, int y, int r, int cap) {
        List<Unit> out = new ArrayList<>();
        int r2 = r * r;
        int bx0 = Math.clamp((x - r) >> SHIFT, 0, side - 1);
        int bx1 = Math.clamp((x + r) >> SHIFT, 0, side - 1);
        int by0 = Math.clamp((y - r) >> SHIFT, 0, side - 1);
        int by1 = Math.clamp((y + r) >> SHIFT, 0, side - 1);
        for (int by = by0; by <= by1; by++)
            for (int bx = bx0; bx <= bx1; bx++) {
                List<Unit> list = buckets[by * side + bx];
                if (list == null)
                    continue;
                for (Unit e : list) {
                    if (MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) > r2 || !isInert(e))
                        continue;
                    out.add(e);
                    if (cap > 0 && out.size() >= cap)
                        return out;
                }
            }
        return out;
    }

    /** The stalled walkers of the last snapshot that still are (isStalled), in snapshot order. */
    @NonNull
    List<@NonNull Unit> stalledNow() {
        List<Unit> out = new ArrayList<>();
        for (Unit e : stalled.keySet())
            if (isStalled(e))
                out.add(e);
        return out;
    }

    /**
     * giant_keepout: whether a building of ours at (x, y), or the builders walking there, would poke a giant: at least
     * giant_min stalled walkers within giant_keepout cells, parked enemies counting only within 10 cells (Chebyshev:
     * they scan 8).
     */
    boolean keepOff(int x, int y) {
        Strategy st = ai.strategy();
        if (st.giant_keepout <= 0 || !on())
            return false;
        int min = Math.max(1, st.giant_min);
        int n = 0;
        for (Unit e : inertNear(x, y, st.giant_keepout, 0))
            if (isStalled(e) || Math.max(Math.abs(e.getGridX() - x), Math.abs(e.getGridY() - y)) <= 10)
                if (++n >= min)
                    return true;
        return false;
    }

    /**
     * giant_stun_hold: a stalled walker that no manned tower of ours reaches (16 cells) is left out of stun counts and
     * is no target for the chieftain to walk up to.
     */
    boolean stunHold(@NonNull Unit e) {
        if (!ai.strategy().giant_stun_hold || !on() || !isStalled(e))
            return false;
        for (Building t : ai.intel().towers)
            if (Intel.isTowerActive(t)
                    && MapAnalysis.dist2(e.getGridX(), e.getGridY(), t.getGridX(), t.getGridY()) <= 16 * 16)
                return false;
        return true;
    }
}
