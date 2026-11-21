package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairBehaviour;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.behaviour.WalkBehaviour;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * bait: a tower site of ours for an enemy tower's garrison to throw at instead of the army.
 *
 * <p>A garrison looks only while it has no target (IdleController: every 1-2 s, the square of 14 cells around the
 * tower, a warrior before a peon before a building), and then throws at what it found until that is dead or out of its
 * reach (AttackController), without looking again. A building site is found like any building, and a peon with wood
 * mends it 1 hit point a second where an iron axe from the tower takes 2 every 2 s. So a site with one row of cells
 * inside that square, its builders outside it, found while no unit of ours is in the square, holds the garrison for
 * as long as it stands, and the army walks in under a tower that throws at a pile of wood (bt2log-vs16: in the eight
 * contacts with the garrison on a site, 0 to 3 warriors dead in six of them, where a razed tower costs nine).
 *
 * <p>A job is one enemy tower: a crew of the sappers that carry wood (a peon carries one, five strokes; without it
 * the site has one hit point when the first throw lands) gathers behind the spot and places the site together. Until
 * the garrison is on it the attack's warriors are kept out of the square, once and for a few seconds. With the tower
 * down the sappers pull the site down again (a swing takes 6 from a tower's site too) and its building slot is free.
 */
final class Bait {
    /** AttackScanFilter.TOWER_RANGE: the square a garrison scans. */
    private static final int SCAN = 14;
    /** A tower site is 3 x 3: centred here it has one row inside the square. */
    private static final int RING = SCAN + 1;
    /** Along the square's side, so that the site stays within the garrison's throw at a building (16.9 cells). */
    private static final int SIDE = 7;
    /** The crew gathers this far behind the site's centre, clear of the 5 x 5 cells its placing needs. */
    private static final int STAGE = 5;
    private static final float PREP_TICKS = 1500f; // 30 s
    private static final float RETRY_TICKS = 1500f; // 30 s
    private static final int THROW_CELLS = 9;
    private static final int WOOD_CELLS = 40;

    private static final class Job {
        final @NonNull Building tower;
        final int[] spot;
        final int[] stage;
        final float since;
        /** The crew's time to get to the stage: its walk at 3 m a second (it makes 1.5 cells), and ten seconds. */
        float prep_ticks;
        final List<@NonNull Unit> crew = new ArrayList<>();
        @Nullable
        Building site;
        float site_ordered;
        boolean was_locked;

        Job(@NonNull Building tower, int[] spot, int[] stage, float since) {
            this.tower = tower;
            this.spot = spot;
            this.stage = stage;
            this.since = since;
        }
    }

    private final @NonNull FableAI ai;
    private final List<@NonNull Job> jobs = new ArrayList<>();
    private final List<@NonNull Building> old = new ArrayList<>();
    private final Map<@NonNull Unit, Float> orders = new LinkedHashMap<>();
    private final List<@NonNull Unit> helpers = new ArrayList<>();
    /** Per tower: when a job on it last ended, and when warriors were first held out of its square. */
    private final Map<@NonNull Building, Float> tried = new LinkedHashMap<>();
    private final Map<@NonNull Building, Float> held = new LinkedHashMap<>();
    private float last_pick = -5000f;
    private float last_trace = -5000f;

    Bait(@NonNull FableAI ai) {
        this.ai = ai;
    }

    /** Whether the sapper is on bait work this round: the military's own sapper orders leave it alone. */
    boolean busy(@NonNull Unit p) {
        if (helpers.contains(p))
            return true;
        for (Job j : jobs)
            if (j.crew.contains(p))
                return true;
        return false;
    }

    /** Whether the building is a bait: nothing else of ours counts or mans it. */
    boolean isBait(@NonNull Building b) {
        if (old.contains(b))
            return true;
        for (Job j : jobs)
            if (j.site == b)
                return true;
        return false;
    }

    /** The baits that hold a building slot. */
    int standing() {
        int n = old.size();
        for (Job j : jobs) {
            Building s = j.site;
            if (s != null && !s.isDead() && s.isPlaced())
                n++;
        }
        return n;
    }

    /** Whether the enemy tower's garrison is throwing at a bait. */
    boolean locked(@NonNull Building tower) {
        for (Job j : jobs)
            if (j.tower == tower)
                return locked(j);
        return false;
    }

    private static int cheb(int x0, int y0, int x1, int y1) {
        return Math.max(Math.abs(x0 - x1), Math.abs(y0 - y1));
    }

    static boolean hasWood(@NonNull Unit p) {
        return p.getSupplyContainer() != null && p.getSupplyContainer().getSupplyType() == TreeSupply.class
                && p.getSupplyContainer().getNumSupplies() > 0;
    }

    private boolean due(@NonNull Unit u, float ticks) {
        Float last = orders.get(u);
        if (last != null && !ai.periodDue(last, ticks))
            return false;
        orders.put(u, ai.now());
        return true;
    }

    private static boolean locked(@NonNull Job j) {
        Building s = j.site;
        if (s == null || s.isDead() || !s.isPlaced() || !Intel.isTowerManned(j.tower))
            return false;
        Unit g = Intel.gunner(j.tower);
        return g != null && !g.isDead() && g.getCurrentController() instanceof AttackController a && a.getTarget() == s;
    }

    void tick() {
        Strategy strategy = ai.strategy();
        if (!strategy.bait)
            return;
        Intel intel = ai.intel();
        helpers.clear();
        orders.keySet().removeIf(Unit::isDead);
        old.removeIf(Building::isDead);
        tried.keySet().removeIf(Building::isDead);
        held.keySet().removeIf(Building::isDead);
        for (Job j : jobs)
            j.crew.removeIf(p -> p.isDead() || !intel.sappers.contains(p));
        int[] head = ai.military().attackHead();
        if (head == null) {
            for (Job j : new ArrayList<>(jobs))
                end(j, "the attack is over");
            return;
        }
        for (Job j : new ArrayList<>(jobs)) {
            Building s = j.site;
            if (j.tower.isDead() || !Intel.isTowerManned(j.tower)) {
                if (j.was_locked)
                    ai.aiLog().count("bait_tower_down");
                end(j, "its tower is down");
            } else if (s != null && s.isDead()) {
                ai.aiLog().count(j.was_locked ? "bait_lost_locked" : "bait_lost");
                j.site = null;
                end(j, "the site fell");
            } else if (j.crew.isEmpty()) {
                ai.aiLog().count("bait_crew_lost");
                end(j, "its crew is gone");
            }
        }
        if (jobs.size() < strategy.bait_jobs && ai.periodDue(last_pick, 50f)) {
            last_pick = ai.now();
            pick(head);
        }
        for (Job j : new ArrayList<>(jobs)) {
            if (j.site == null)
                prepare(j);
            else
                tend(j);
        }
        for (Job j : jobs) {
            boolean locked = locked(j);
            if (locked) {
                ai.aiLog().count("bait_lock_ticks");
                if (!j.was_locked) {
                    j.was_locked = true;
                    ai.aiLog().count("bait_locked");
                    ai.log(String.format("bait: the garrison at %d,%d is on the site %.1f s after the start",
                            j.tower.getGridX(), j.tower.getGridY(), (ai.now() - j.since) / 50f));
                }
                mend(j);
                onBuilding(j.tower, 40);
            } else {
                if (j.was_locked && j.site != null)
                    ai.aiLog().count("bait_unlock_ticks");
                if (!j.was_locked)
                    hold(j);
            }
        }
        // baits left from towers that are down: pulled down for their building slots
        for (Building b : old)
            onBuilding(b, 30);
        wood();
        trace();
    }

    private void pick(int @NonNull [] head) {
        Intel intel = ai.intel();
        Player owner = ai.owner();
        if (owner.getBuildingCountContainer().getNumSupplies() >= owner.getWorld().getMaxBuildingCount()) {
            ai.aiLog().count("bait_cap");
            return;
        }
        int[] focus = ai.military().attackFocus();
        Building best = null;
        int best_d = Integer.MAX_VALUE;
        int start = ai.strategy().bait_start;
        for (Building t : intel.enemy_towers) {
            if (!Intel.isTowerManned(t))
                continue;
            Float last = tried.get(t);
            if (last != null && !ai.periodDue(last, RETRY_TICKS))
                continue;
            boolean taken = false;
            for (Job j : jobs)
                taken |= j.tower == t;
            if (taken)
                continue;
            int d = MapAnalysis.dist2(t.getGridX(), t.getGridY(), head[0], head[1]);
            boolean by_target = focus != null
                    && MapAnalysis.dist2(t.getGridX(), t.getGridY(), focus[0], focus[1]) <= 24 * 24;
            if (d > start * start && !(by_target && d <= 4 * start * start))
                continue;
            if (d < best_d) {
                best_d = d;
                best = t;
            }
        }
        if (best == null)
            return;
        int tx = best.getGridX();
        int ty = best.getGridY();
        // one of ours in its square: it has its target, and looks again only when that is dead
        for (Unit u : ai.military().attackArmy())
            if (cheb(u.getGridX(), u.getGridY(), tx, ty) <= SCAN) {
                ai.aiLog().count("bait_late");
                tried.put(best, ai.now());
                return;
            }
        // the crew: the sappers with wood nearest the tower; one not with the army yet does not get there before it
        List<Unit> pool = new ArrayList<>();
        for (Unit p : intel.sappers)
            if (!p.isDead() && hasWood(p) && !busy(p))
                pool.add(p);
        if (pool.size() < 2) {
            ai.aiLog().count("bait_nowood");
            return;
        }
        pool.sort((a, b) -> Integer.compare(MapAnalysis.dist2(a.getGridX(), a.getGridY(), tx, ty), MapAnalysis.dist2(
                b.getGridX(), b.getGridY(), tx, ty)));
        Unit second = pool.get(1);
        int reach = ai.strategy().bait_start + 45;
        if (MapAnalysis.dist2(second.getGridX(), second.getGridY(), tx, ty) > reach * reach) {
            ai.aiLog().count("bait_far");
            return;
        }
        BuildingTemplate template = owner.getRace().getBuildingTemplate(Race.BUILDING_TOWER);
        MapAnalysis map = ai.map();
        // the side of the square the crew can walk to soonest, not the one nearest as the crow flies
        DistanceField walk = map.computeField(second.getGridX(), second.getGridY(), 6 * reach);
        int best_s = Integer.MAX_VALUE;
        int[] spot = null;
        int[] stage = null;
        for (int side = 0; side < 4; side++)
            for (int k = -SIDE; k <= SIDE; k++) {
                int x = tx + (side == 0 ? RING : side == 1 ? -RING : k);
                int y = ty + (side == 2 ? RING : side == 3 ? -RING : k);
                if (!map.canPlace(template, x, y))
                    continue;
                // inside another garrison's square its builders would be that one's targets
                boolean seen = false;
                for (Building o : intel.enemy_towers)
                    if (o != best && Intel.isTowerManned(o) && cheb(x, y, o.getGridX(), o.getGridY()) < RING + 2) {
                        seen = true;
                        break;
                    }
                if (seen)
                    continue;
                int sx = x + (side == 0 ? STAGE : side == 1 ? -STAGE : 0);
                int sy = y + (side == 2 ? STAGE : side == 3 ? -STAGE : 0);
                if (!map.walkable(sx, sy))
                    continue;
                int d = walk.get(sx, sy);
                if (d == DistanceField.UNREACHABLE)
                    continue;
                if (d < best_s) {
                    best_s = d;
                    spot = new int[]{x, y};
                    stage = new int[]{sx, sy};
                }
            }
        if (spot == null || stage == null) {
            ai.aiLog().count("bait_nospot");
            tried.put(best, ai.now());
            return;
        }
        Job job = new Job(best, spot, stage, ai.now());
        job.prep_ticks = Math.max(PREP_TICKS, best_s / 3f * 50f + 500f);
        job.crew.addAll(pool.subList(0, Math.min(ai.strategy().bait_crew, pool.size())));
        jobs.add(job);
        ai.aiLog().count("bait_begun");
        ai.log(String.format("bait: a site at %d,%d for the tower at %d,%d, %d sappers %d m of walk off, head %d cells",
                spot[0], spot[1], tx, ty, job.crew.size(), best_s, (int) Math.sqrt(best_d)));
    }

    /** The crew gathers behind the spot; with two there, the site is placed. */
    private void prepare(@NonNull Job j) {
        Player owner = ai.owner();
        List<Unit> ready = new ArrayList<>();
        for (Unit p : j.crew) {
            if (cheb(p.getGridX(), p.getGridY(), j.stage[0], j.stage[1]) <= 2)
                ready.add(p);
            else if (due(p, p.getCurrentBehaviour() instanceof WalkBehaviour ? 150f : 25f))
                ai.landscapeOrder(Selectable.newArray(p), j.stage[0], j.stage[1], Action.MOVE, false);
        }
        if (ready.size() >= Math.min(2, j.crew.size()) && !ready.isEmpty()) {
            BuildingTemplate template = owner.getRace().getBuildingTemplate(Race.BUILDING_TOWER);
            if (!owner.canBuild(Race.BUILDING_TOWER)) {
                ai.aiLog().count("bait_cap_late");
                end(j, "no building slot left");
                return;
            }
            if (!ai.map().canPlace(template, j.spot[0], j.spot[1])) {
                ai.aiLog().count("bait_spot_taken");
                end(j, "the spot is taken");
                return;
            }
            j.site = ai.placeSite(ready, Race.BUILDING_TOWER, j.spot[0], j.spot[1]);
            j.site_ordered = ai.now();
            ai.aiLog().count("bait_placed");
            ai.log(String.format("bait: %d of %d place the site %.1f s after the start", ready.size(), j.crew.size(),
                    (ai.now() - j.since) / 50f));
        } else if (ai.now() - j.since > j.prep_ticks) {
            ai.aiLog().count("bait_giveup");
            end(j, "the crew did not get there");
        }
    }

    /** The crew keeps building the site: a builder out of wood fetches its own (RepairController). */
    private void tend(@NonNull Job j) {
        Building s = j.site;
        if (s == null)
            return;
        if (!s.isPlaced() && ai.now() - j.site_ordered > 250f) {
            ai.aiLog().count("bait_unplaced");
            j.site = null;
            end(j, "the site was not placed");
            return;
        }
        for (Unit p : j.crew) {
            Controller c = p.getPrimaryController();
            boolean on = (c instanceof PlaceBuildingController pb && pb.getBuilding() == s)
                    || (c instanceof RepairController rc && rc.getBuilding() == s);
            if (!on && due(p, 50f))
                ai.owner().setTarget(Selectable.newArray(p), s, Action.DEFAULT, false);
        }
    }

    /** Until the garrison is on the site the attack's warriors stay out of its square: once a tower, HOLD_TICKS. */
    private void hold(@NonNull Job j) {
        Building t = j.tower;
        Float first = held.get(t);
        if (first != null && ai.now() - first > ai.strategy().bait_hold_ticks)
            return;
        Intel intel = ai.intel();
        int tx = t.getGridX();
        int ty = t.getGridY();
        List<Unit> foes = new ArrayList<>();
        for (Unit e : intel.enemy_warriors)
            if (!e.isDead() && cheb(e.getGridX(), e.getGridY(), tx, ty) <= SCAN + 4 + THROW_CELLS)
                foes.add(e);
        for (Unit u : ai.military().attackArmy()) {
            int ux = u.getGridX();
            int uy = u.getGridY();
            int c = cheb(ux, uy, tx, ty);
            if (c > SCAN + 4 || Intel.isStunned(u))
                continue;
            boolean foe = false;
            for (Unit e : foes)
                if (MapAnalysis.dist2(ux, uy, e.getGridX(), e.getGridY()) <= THROW_CELLS * THROW_CELLS) {
                    foe = true;
                    break;
                }
            if (foe)
                continue;
            if (first == null) {
                first = ai.now();
                held.put(t, first);
                ai.aiLog().count("bait_held");
            }
            ai.military().dropOrder(u);
            ai.aiLog().count("bait_hold");
            if (c <= SCAN + 1) {
                if (due(u, 50f)) {
                    float f = (SCAN + 4f) / Math.max(c, 1);
                    ai.landscapeOrder(Selectable.newArray(u), tx + Math.round((ux - tx) * f), ty + Math.round(
                            (uy - ty) * f), Action.MOVE, false);
                }
            } else if (u.getCurrentBehaviour() instanceof WalkBehaviour && due(u, 50f))
                ai.landscapeOrder(Selectable.newArray(u), ux, uy, Action.MOVE, false);
        }
    }

    /** With the garrison on the site, every sapper near that carries wood puts it in: the site is what holds it. */
    private void mend(@NonNull Job j) {
        Building s = j.site;
        if (s == null || s.isDead())
            return;
        for (Unit p : ai.intel().sappers) {
            if (p.isDead() || busy(p) || !hasWood(p)
                    || MapAnalysis.dist2(p.getGridX(), p.getGridY(), s.getGridX(), s.getGridY()) > 40 * 40)
                continue;
            helpers.add(p);
            if (!(p.getPrimaryController() instanceof RepairController rc && rc.getBuilding() == s) && due(p, 50f))
                ai.owner().setTarget(Selectable.newArray(p), s, Action.DEFAULT, false);
        }
    }

    /** The sappers outside the crews within cells of the building swing at it: a locked tower, or an old bait. */
    private void onBuilding(@NonNull Building b, int cells) {
        List<Unit> squad = new ArrayList<>();
        for (Unit p : ai.intel().sappers) {
            if (p.isDead() || busy(p)
                    || MapAnalysis.dist2(p.getGridX(), p.getGridY(), b.getGridX(), b.getGridY()) > cells * cells)
                continue;
            helpers.add(p);
            if (!(p.getCurrentController() instanceof HuntController h && h.getTarget() == b) && due(p, 75f))
                squad.add(p);
        }
        if (!squad.isEmpty())
            ai.owner().setTarget(squad.toArray(new Selectable<?>[0]), b, Action.ATTACK, false);
    }

    /** A sapper carries its wood before it is needed: one without wood and a tree near chops it. */
    private void wood() {
        for (Unit p : ai.intel().sappers) {
            if (p.isDead() || busy(p) || hasWood(p))
                continue;
            TreeSupply tree = ai.map().nearestTree(p.getGridX(), p.getGridY(), WOOD_CELLS);
            if (tree == null) {
                ai.aiLog().count("bait_notree");
                continue;
            }
            helpers.add(p);
            if (due(p, 250f)) {
                ai.owner().setTarget(Selectable.newArray(p), tree, Action.GATHER_REPAIR, false);
                ai.aiLog().count("bait_wood");
            }
        }
    }

    private void end(@NonNull Job j, @NonNull String why) {
        ai.log("bait: over at " + j.tower.getGridX() + "," + j.tower.getGridY() + ", " + why + (j.was_locked ? " (the garrison was on it)" : ""));
        Building s = j.site;
        if (s != null && !s.isDead() && s.isPlaced())
            old.add(s);
        if (!j.tower.isDead())
            tried.put(j.tower, ai.now());
        jobs.remove(j);
    }

    /** Log only: a line a second for each job. */
    private void trace() {
        if (jobs.isEmpty() || !ai.logging() || !ai.periodDue(last_trace, 50f))
            return;
        last_trace = ai.now();
        for (Job j : jobs) {
            Building t = j.tower;
            Building s = j.site;
            Unit g = Intel.isTowerManned(t) ? Intel.gunner(t) : null;
            String gun = g == null
                    || g.isDead() ? "-" : g.getCurrentController() instanceof AttackController a ? (a.getTarget() == s ? "site" : a.getTarget() instanceof Building ? "building" : a.getTarget() instanceof Unit u
                            && u.getAbilities().hasAbilities(Abilities.BUILD) ? "peon" : "warrior") : "idle";
            int wood = 0;
            int at_stage = 0;
            int in_square = 0;
            int building = 0;
            for (Unit p : j.crew) {
                if (hasWood(p))
                    wood++;
                if (cheb(p.getGridX(), p.getGridY(), j.stage[0], j.stage[1]) <= 2)
                    at_stage++;
                if (cheb(p.getGridX(), p.getGridY(), t.getGridX(), t.getGridY()) <= SCAN)
                    in_square++;
                if (p.getCurrentBehaviour() instanceof RepairBehaviour)
                    building++;
            }
            int warriors = 0;
            for (Unit u : ai.military().attackArmy())
                if (cheb(u.getGridX(), u.getGridY(), t.getGridX(), t.getGridY()) <= SCAN)
                    warriors++;
            StringBuilder where = new StringBuilder();
            for (Unit p : j.crew)
                where.append(' ').append((int) Math.sqrt(MapAnalysis.dist2(p.getGridX(), p.getGridY(), j.stage[0],
                        j.stage[1]))).append(
                                p.getCurrentBehaviour() instanceof WalkBehaviour w ? (w.isBlocking() ? "b" : "w") : "s");
            ai.log(String.format(
                    "bait s: tower %d,%d %.1f s site %s gun %s locked %b crew %d wood %d staged %d hammering %d in square %d, warriors in square %d, crew to stage%s",
                    t.getGridX(), t.getGridY(), (ai.now() - j.since) / 50f,
                    s == null ? "-" : !s.isPlaced() ? "unplaced" : s.isDead() ? "dead" : s.getHitPoints() + " hp", gun,
                    locked(j), j.crew.size(), wood, at_stage, building, in_square, warriors, where));
        }
    }
}
