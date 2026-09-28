package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.player.gauntlet.Intel.PeonState;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Lure-kiting: a peon pulls idle enemy warriors into the reach of our towers.
 *
 * <p>An idle Hard warrior scans an 8-cell square every 1-2 s and hunts the first unit of ours it sees
 * (IdleController). While hunting it walks without scanning (HuntController: WalkBehaviour without scan), never
 * answers being hit (Unit.hit changes no controller), and at 4 m/s is slower than a peon at 5 m/s; its throws reach
 * 7.9 cells. Waves that razed a building of ours stand idle 15-45 cells from our manned towers (STAT pt histograms at
 * N=9-11), out of reach. So a peon standing where the nearest of such a blob sees it but no member can throw at it,
 * on the side of a manned tower, runs past that tower into a quarters or armory behind it when the blob starts to
 * hunt: the tower (15.9 cells, three times the hit chance) throws at the blind column, and our warriors at home join.
 * When the peon is inside, the hunt ends and the survivors walk back, scanning, through the same towers.
 */
final class Lures {
    private enum Phase {
        APPROACH,
        BAIT,
        RUN
    }

    private static final class Lure {
        final @NonNull Unit peon;
        final @NonNull Building tower;
        final @NonNull Building refuge;
        final int bait_x;
        final int bait_y;
        final int way_x;
        final int way_y;
        @NonNull
        Phase phase = Phase.APPROACH;
        float since;
        boolean entering;
        int hunters;

        Lure(@NonNull Unit peon, @NonNull Building tower, @NonNull Building refuge, int bait_x, int bait_y, int way_x,
                int way_y, float since) {
            this.peon = peon;
            this.tower = tower;
            this.refuge = refuge;
            this.bait_x = bait_x;
            this.bait_y = bait_y;
            this.way_x = way_x;
            this.way_y = way_y;
            this.since = since;
        }
    }

    private final @NonNull GauntletAI ai;
    private final List<@NonNull Lure> lures = new ArrayList<>();
    /** Recent bait spots {x, y, time}: a spot is not baited again for 30 s. */
    private final List<float @NonNull []> recent = new ArrayList<>();
    private float last_plan = -100f;

    Lures(@NonNull GauntletAI ai) {
        this.ai = ai;
    }

    boolean isLure(@NonNull Unit u) {
        for (Lure l : lures)
            if (l.peon == u)
                return true;
        return false;
    }

    /** Every 5 ticks: runs the lures and, once a second, looks for a blob to bait. */
    void guard() {
        Strategy strategy = ai.strategy();
        if (!strategy.lure)
            return;
        for (Iterator<Lure> it = lures.iterator(); it.hasNext();) {
            Lure l = it.next();
            if (l.peon.isDead()) {
                boolean home = l.entering && MapAnalysis.dist2(l.peon.getGridX(), l.peon.getGridY(),
                        l.refuge.getGridX(), l.refuge.getGridY()) <= 6 * 6;
                ai.aiLog().count(home ? "lure_home" : "lure_lost");
                ai.log("lure " + (home ? "home" : "lost") + " after drawing " + l.hunters + " (" + l.phase + ")");
                ai.intel().lures.remove(l.peon);
                it.remove();
                continue;
            }
            if (!step(l)) {
                release(l);
                it.remove();
            }
        }
        if (ai.time() - last_plan >= 1f) {
            last_plan = ai.time();
            plan();
        }
    }

    private void release(@NonNull Lure l) {
        ai.intel().lures.remove(l.peon);
        if (!l.peon.isDead() && !l.refuge.isDead())
            ai.owner().setTarget(Selectable.newArray(l.peon), l.refuge, Action.MOVE, false);
    }

    /** Advances one lure; false when it is over. */
    private boolean step(@NonNull Lure l) {
        Unit p = l.peon;
        int px = p.getGridX();
        int py = p.getGridY();
        if (l.tower.isDead() || l.refuge.isDead())
            return false;
        int hunters = huntersOf(p);
        switch (l.phase) {
            case APPROACH -> {
                if (hunters > 0 || nearestEnemy(px, py) <= 9 * 9) {
                    run(l, hunters);
                } else if (MapAnalysis.dist2(px, py, l.bait_x, l.bait_y) <= 2 * 2) {
                    l.phase = Phase.BAIT;
                    l.since = ai.time();
                } else if (ai.time() - l.since > 60f) {
                    ai.aiLog().count("lure_timeout");
                    return false;
                }
            }
            case BAIT -> {
                if (hunters > 0 || nearestEnemy(px, py) <= 8 * 8)
                    run(l, hunters);
                else if (ai.time() - l.since > 8f) {
                    ai.aiLog().count("lure_ignored");
                    return false;
                }
            }
            case RUN -> {
                l.hunters = Math.max(l.hunters, hunters);
                if (!l.entering && MapAnalysis.dist2(px, py, l.way_x, l.way_y) <= 3 * 3) {
                    l.entering = true;
                    ai.owner().setTarget(Selectable.newArray(p), l.refuge, Action.MOVE, false);
                }
                if (ai.time() - l.since > 90f)
                    return false;
            }
        }
        return true;
    }

    private void run(@NonNull Lure l, int hunters) {
        l.phase = Phase.RUN;
        l.since = ai.time();
        l.hunters = hunters;
        ai.aiLog().count("lure_run");
        ai.landscapeOrder(Selectable.newArray(l.peon), l.way_x, l.way_y, Action.MOVE, false);
    }

    /** Enemy units hunting this unit of ours. */
    private int huntersOf(@NonNull Unit u) {
        int n = 0;
        for (Unit e : ai.intel().enemy_warriors)
            if (!e.isDead() && e.getCurrentController() instanceof HuntController h && h.getTarget() == u)
                n++;
        return n;
    }

    /** Squared distance to the nearest enemy warrior or chieftain. */
    private int nearestEnemy(int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Unit e : ai.intel().enemy_warriors)
            if (!e.isDead())
                best = Math.min(best, MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()));
        for (Unit e : ai.intel().enemy_chieftains)
            if (!e.isDead())
                best = Math.min(best, MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()));
        return best;
    }

    private static boolean isParked(@NonNull Unit e) {
        return e.getPrimaryController() instanceof IdleController
                && e.getCurrentController() == e.getPrimaryController();
    }

    private void plan() {
        Strategy strategy = ai.strategy();
        if (lures.size() >= strategy.lure_max || ai.time() < strategy.lure_time)
            return;
        Intel intel = ai.intel();
        recent.removeIf(r -> ai.time() - r[2] > 30f);
        List<Building> active = new ArrayList<>();
        for (Building t : intel.towers)
            if (Intel.isTowerActive(t))
                active.add(t);
        if (active.isEmpty())
            return;
        List<Building> own = new ArrayList<>(intel.armories);
        own.addAll(intel.quarters);
        own.addAll(intel.towers);
        int range2 = strategy.lure_range * strategy.lure_range;
        List<Unit> parked = new ArrayList<>();
        List<Unit> awake = new ArrayList<>();
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead())
                continue;
            if (!isParked(e)) {
                awake.add(e);
                continue;
            }
            for (Building b : own)
                if (!b.isDead() && MapAnalysis.dist2(b.getGridX(), b.getGridY(), e.getGridX(),
                        e.getGridY()) <= range2) {
                            parked.add(e);
                            break;
                        }
        }
        awake.addAll(intel.enemy_chieftains);
        Unit best_seed = null;
        int best_n = 0;
        Building best_tower = null;
        for (Unit s : parked) {
            int sx = s.getGridX();
            int sy = s.getGridY();
            boolean fresh = true;
            for (float[] r : recent)
                if (MapAnalysis.dist2(sx, sy, (int) r[0], (int) r[1]) <= 15 * 15)
                    fresh = false;
            if (!fresh)
                continue;
            Building tower = null;
            int td = Integer.MAX_VALUE;
            for (Building t : active) {
                int d = MapAnalysis.dist2(sx, sy, t.getGridX(), t.getGridY());
                if (d < td) {
                    td = d;
                    tower = t;
                }
            }
            // Already in reach of a tower (it is shooting them), or too far to drag.
            if (tower == null || td <= 16 * 16 || td > 45 * 45)
                continue;
            int n = 0;
            for (Unit e : parked)
                if (MapAnalysis.dist2(sx, sy, e.getGridX(), e.getGridY()) <= 8 * 8)
                    n++;
            if (n > best_n) {
                best_n = n;
                best_seed = s;
                best_tower = tower;
            }
        }
        if (best_seed == null || best_n < strategy.lure_min)
            return;
        Unit seed = best_seed;
        Building tower = best_tower;
        int sx = seed.getGridX();
        int sy = seed.getGridY();
        int td2 = MapAnalysis.dist2(sx, sy, tower.getGridX(), tower.getGridY());
        // Refuge: a quarters or armory behind the tower, seen from the blob.
        Building refuge = null;
        int rbest = Integer.MAX_VALUE;
        for (Building b : ai.intel().quarters.isEmpty() ? intel.armories : concat(intel.quarters, intel.armories)) {
            if (b.isDead() || !b.isComplete() || ai.economy().isEvacuating(b))
                continue;
            int bd2 = MapAnalysis.dist2(sx, sy, b.getGridX(), b.getGridY());
            int tb2 = MapAnalysis.dist2(tower.getGridX(), tower.getGridY(), b.getGridX(), b.getGridY());
            if (bd2 <= td2 || tb2 > 60 * 60)
                continue;
            if (tb2 < rbest) {
                rbest = tb2;
                refuge = b;
            }
        }
        if (refuge == null) {
            ai.aiLog().count("lure_norefuge");
            return;
        }
        // The blob's members near the seed; the bait cell is seen by one of them and out of every throw.
        List<Unit> blob = new ArrayList<>();
        for (Unit e : parked)
            if (MapAnalysis.dist2(sx, sy, e.getGridX(), e.getGridY()) <= 12 * 12)
                blob.add(e);
        int[] bait = null;
        float bait_score = -Float.MAX_VALUE;
        for (int r = 8; r <= 13; r++) {
            for (int a = 0; a < 32; a++) {
                double ang = a * Math.PI / 16;
                int x = sx + (int) Math.round(r * Math.cos(ang));
                int y = sy + (int) Math.round(r * Math.sin(ang));
                if (!ai.map().passable(x, y))
                    continue;
                boolean seen = false;
                boolean safe = true;
                for (Unit e : blob) {
                    int dx = Math.abs(e.getGridX() - x);
                    int dy = Math.abs(e.getGridY() - y);
                    if (dx <= 8 && dy <= 8)
                        seen = true;
                    if (dx * dx + dy * dy < 73) { // 8.5 cells: throws reach 7.9
                        safe = false;
                        break;
                    }
                }
                if (!seen || !safe)
                    continue;
                for (Unit e : awake)
                    if (!e.isDead() && MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 10 * 10) {
                        safe = false;
                        break;
                    }
                if (!safe)
                    continue;
                // On the tower's side of the blob, the nearer the better.
                int d2t = MapAnalysis.dist2(x, y, tower.getGridX(), tower.getGridY());
                if (d2t >= td2)
                    continue;
                float score = -(float) Math.sqrt(d2t);
                if (score > bait_score) {
                    bait_score = score;
                    bait = new int[]{x, y};
                }
            }
        }
        if (bait == null) {
            ai.aiLog().count("lure_nobait");
            return;
        }
        Unit peon = recruit(bait[0], bait[1], awake, parked);
        if (peon == null) {
            ai.aiLog().count("lure_nopeon");
            return;
        }
        // Waypoint: just past the tower on the way to the refuge.
        int wx = tower.getGridX();
        int wy = tower.getGridY();
        float dx = refuge.getGridX() - wx;
        float dy = refuge.getGridY() - wy;
        float len = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
        int way_x = wx + Math.round(4 * dx / len);
        int way_y = wy + Math.round(4 * dy / len);
        Lure l = new Lure(peon, tower, refuge, bait[0], bait[1], way_x, way_y, ai.time());
        lures.add(l);
        intel.lures.add(peon);
        recent.add(new float[]{sx, sy, ai.time()});
        ai.landscapeOrder(Selectable.newArray(peon), bait[0], bait[1], Action.MOVE, false);
        ai.aiLog().count("lure_start");
        ai.log(String.format("lure for %d parked at %d,%d: bait %d,%d, tower %d,%d, refuge %d,%d", best_n, sx, sy,
                bait[0], bait[1], tower.getGridX(), tower.getGridY(), refuge.getGridX(), refuge.getGridY()));
    }

    private static @NonNull List<@NonNull Building> concat(@NonNull List<@NonNull Building> a,
            @NonNull List<@NonNull Building> b) {
        List<Building> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** The nearest free peon within 60 cells, with no enemy within 12 cells of it. */
    private @Nullable Unit recruit(int x, int y, @NonNull List<@NonNull Unit> awake,
            @NonNull List<@NonNull Unit> parked) {
        Intel intel = ai.intel();
        Unit best = null;
        int best_d = 60 * 60;
        for (Unit p : intel.peons) {
            PeonState st = intel.peon_states.get(p);
            if (st != PeonState.IDLE && st != PeonState.GATHER_TREE && st != PeonState.GATHER_ROCK
                    && st != PeonState.GATHER_IRON && st != PeonState.TRANSIT && st != PeonState.MOVE)
                continue;
            if (intel.shepherds.contains(p) || intel.lures.contains(p) || p.isDead())
                continue;
            int d = MapAnalysis.dist2(p.getGridX(), p.getGridY(), x, y);
            if (d >= best_d)
                continue;
            boolean safe = true;
            for (List<Unit> group : List.of(awake, parked))
                for (Unit e : group)
                    if (!e.isDead() && MapAnalysis.dist2(p.getGridX(), p.getGridY(), e.getGridX(),
                            e.getGridY()) <= 12 * 12) {
                                safe = false;
                                break;
                            }
            if (!safe)
                continue;
            best_d = d;
            best = p;
        }
        return best;
    }

    /** For the STAT line. */
    int active() {
        return lures.size();
    }

    static boolean isQuarters(@NonNull Building b) {
        return b.getTemplate().getTemplateID() == Race.BUILDING_QUARTERS;
    }
}
