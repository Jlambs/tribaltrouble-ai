package com.oddlabs.tt.player.gauntlet;

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
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.gauntlet.Intel.PeonState;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Decoy tower sites that steer the stock Hard AI's waves into reach of our towers.
 *
 * <p>A Hard copy sends each wave at our building nearest its oldest idle warrior, placed but unbuilt sites included
 * (AdvancedAI.findTarget and nodeAttackWithWarriorsAndChieftain: the target is findNearestEnemyBuilding from
 * warriors[0], or one of our units if that unit is closer than 0.707 of that distance). The wave attack-moves to the
 * target's cell, razes a 1-HP site with one throw, and stands idle there. Idle units scan 8 cells and never answer
 * being hit, while a tower garrison throws 15.9 cells with three times its hit chance. So a site 11-14 cells in front
 * of our towers, nearer to a copy than any other building of ours, puts that copy's waves where the towers shoot them
 * one by one (sweep's fort, lab/sweep; outnumbered's lure, lab/outnumbered).
 *
 * <p>For every living enemy player this keeps one decoy that is nearer to the copy's wave origin (its oldest idle
 * warrior, else its armory) than any real building of ours, placed by a peon that walks back once the site stands.
 */
final class Decoys {
    /** Cells from a tower within which a decoy may stand: outside an idle wave's 8-cell scan, inside the reach. */
    private static final int MIN_TOWER_CELLS = 11;
    private static final int MAX_TOWER_CELLS = 14;
    /** Tower reach against units, in cells (weapon 6 + unit size 1.9 + mount 8, MountUnitContainer). */
    private static final float TOWER_REACH = 15.5f;

    private final @NonNull GauntletAI ai;
    private final List<@NonNull Decoy> decoys = new ArrayList<>();
    private float last_tick = -10f;

    private static final class Decoy {
        final @NonNull Player target;
        final int x;
        final int y;
        @Nullable
        Building site;
        @Nullable
        Unit runner;
        final float ordered;

        Decoy(@NonNull Player target, int x, int y, float ordered) {
            this.target = target;
            this.x = x;
            this.y = y;
            this.ordered = ordered;
        }
    }

    Decoys(@NonNull GauntletAI ai) {
        this.ai = ai;
    }

    /** Whether the building is one of our decoys (the economy and the military leave those alone). */
    boolean isDecoy(@NonNull Building b) {
        for (Decoy d : decoys)
            if (d.site == b)
                return true;
        return false;
    }

    /** Whether the peon is walking a decoy out. */
    boolean isRunner(@NonNull Unit u) {
        for (Decoy d : decoys)
            if (d.runner == u)
                return true;
        return false;
    }

    int count() {
        return decoys.size();
    }

    void tick() {
        Strategy strategy = ai.strategy();
        if (!strategy.decoys || ai.time() - last_tick < 1f)
            return;
        last_tick = ai.time();
        Intel intel = ai.intel();
        release();
        if (ai.time() < strategy.decoy_time)
            return;
        List<Building> active = new ArrayList<>();
        for (Building t : intel.towers)
            if (Intel.isTowerActive(t))
                active.add(t);
        if (active.isEmpty())
            return;
        for (Player p : ai.owner().getWorld().getPlayers()) {
            if (!ai.owner().isEnemy(p) || !p.isAlive())
                continue;
            int[] origin = waveOrigin(p);
            if (origin == null)
                continue;
            steer(p, origin, active);
        }
    }

    /** Drops razed decoys and sends runners back once their site stands. */
    private void release() {
        for (Iterator<Decoy> it = decoys.iterator(); it.hasNext();) {
            Decoy d = it.next();
            if (d.site != null && d.site.isDead()) {
                ai.aiLog().count("decoy_razed");
                it.remove();
                continue;
            }
            if (d.runner != null && d.runner.isDead())
                d.runner = null;
            if (d.site == null || (!d.site.isPlaced() && d.runner == null)) {
                // Never placed: the runner died or dropped an illegal site.
                if (d.runner == null || ai.time() - d.ordered > 90f) {
                    if (d.runner != null && !d.runner.isDead())
                        sendHome(d.runner);
                    it.remove();
                }
                continue;
            }
            if (d.site.isPlaced() && d.runner != null) {
                sendHome(d.runner);
                d.runner = null;
            }
            if (!d.target.isAlive()) {
                it.remove();
            }
        }
    }

    private void sendHome(@NonNull Unit runner) {
        Building home = ai.intel().armory();
        if (home == null && !ai.intel().quarters.isEmpty())
            home = ai.intel().quarters.getFirst();
        if (home != null && !home.isDead())
            ai.owner().setTarget(Selectable.newArray(runner), home, Action.DEFAULT, false);
    }

    /** Where the copy's next wave starts from: its oldest idle warrior, else its armory, else its start. */
    private static int @Nullable [] waveOrigin(@NonNull Player p) {
        Building armory = null;
        for (Selectable<?> s : p.getUnits().getSet()) {
            if (s.isDead())
                continue;
            if (s instanceof Unit u) {
                if (u.isMounted() || !(u.getPrimaryController() instanceof IdleController))
                    continue;
                if (u.getAbilities().hasAbilities(Abilities.BUILD) || u.getAbilities().hasAbilities(Abilities.MAGIC))
                    continue;
                if (u.getAbilities().hasAbilities(Abilities.ATTACK))
                    return new int[]{u.getGridX(), u.getGridY()};
            } else if (armory == null && s instanceof Building b && b.isComplete()
                    && b.getAbilities().hasAbilities(Abilities.BUILD_ARMIES)) {
                        armory = b;
                    }
        }
        if (armory != null)
            return new int[]{armory.getGridX(), armory.getGridY()};
        return null;
    }

    private void steer(@NonNull Player p, int @NonNull [] origin, @NonNull List<@NonNull Building> active) {
        int ox = origin[0];
        int oy = origin[1];
        int real = nearestReal(ox, oy);
        // A decoy already nearer than every real building serves this copy (or will, once placed).
        for (Decoy d : decoys) {
            int d2 = MapAnalysis.dist2(d.x, d.y, ox, oy);
            if (d2 < real)
                return;
        }
        Strategy strategy = ai.strategy();
        if (decoys.size() >= strategy.decoy_max)
            return;
        int cap = ai.owner().getWorld().getMaxBuildingCount();
        if (ai.owner().getBuildingCountContainer().getNumSupplies() + strategy.decoy_free_slots >= cap)
            return;
        int[] spot = findSpot(ox, oy, real, active);
        if (spot == null) {
            ai.aiLog().count("decoy_nospot");
            return;
        }
        Unit runner = chooseRunner(spot[0], spot[1]);
        if (runner == null)
            return;
        Decoy d = new Decoy(p, spot[0], spot[1], ai.time());
        d.runner = runner;
        d.site = ai.placeSite(List.of(runner), Race.BUILDING_TOWER, spot[0], spot[1]);
        if (d.site == null)
            return;
        decoys.add(d);
        ai.intel().peon_states.put(runner, PeonState.BUILD);
        ai.aiLog().count("decoy_placed");
        ai.log("decoy for " + p.getPlayerInfo().getName() + " at " + spot[0] + "," + spot[1] + " (origin " + ox + "," + oy + ")");
    }

    /** Squared distance from (x, y) to our nearest building that is not a decoy (placed sites included). */
    private int nearestReal(int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Selectable<?> s : ai.owner().getUnits().getSet()) {
            if (!(s instanceof Building b) || b.isDead() || isDecoy(b))
                continue;
            if (b.getTemplate().getType() != BuildingTemplate.TYPE_BUILDING)
                continue;
            best = Math.min(best, MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y));
        }
        return best;
    }

    /**
     * A legal tower site 11-14 cells from one of our manned towers, nearer to the wave origin than our nearest real
     * building by a margin, clear of our buildings by 10 cells and of enemy warriors by 12, in reach of as many active
     * towers as possible.
     */
    private int @Nullable [] findSpot(int ox, int oy, int real, @NonNull List<@NonNull Building> active) {
        BuildingTemplate template = ai.owner().getRace().getBuildingTemplate(Race.BUILDING_TOWER);
        Intel intel = ai.intel();
        List<Building> own = new ArrayList<>();
        for (Selectable<?> s : ai.owner().getUnits().getSet())
            if (s instanceof Building b && !b.isDead() && b.getTemplate().getType() == BuildingTemplate.TYPE_BUILDING)
                own.add(b);
        float limit = ai.strategy().decoy_margin * ai.strategy().decoy_margin * real;
        int[] best = null;
        float best_score = -Float.MAX_VALUE;
        for (Building t : active) {
            for (int r = MIN_TOWER_CELLS; r <= MAX_TOWER_CELLS; r++) {
                for (int a = 0; a < 24; a++) {
                    double ang = a * Math.PI / 12;
                    int x = t.getGridX() + (int) Math.round(r * Math.cos(ang));
                    int y = t.getGridY() + (int) Math.round(r * Math.sin(ang));
                    int d2 = MapAnalysis.dist2(x, y, ox, oy);
                    if (d2 >= limit)
                        continue;
                    if (!clearOf(own, x, y, 10) || !clearOfDecoys(x, y, 4))
                        continue;
                    if (enemyNear(intel, x, y, 12))
                        continue;
                    if (!ai.map().canPlace(template, x, y))
                        continue;
                    int reach = 0;
                    for (Building o : active)
                        if (MapAnalysis.dist2(o.getGridX(), o.getGridY(), x, y) <= TOWER_REACH * TOWER_REACH)
                            reach++;
                    float score = reach * 100f - (float) Math.sqrt(d2);
                    if (score > best_score) {
                        best_score = score;
                        best = new int[]{x, y};
                    }
                }
            }
        }
        return best;
    }

    private static boolean clearOf(@NonNull List<@NonNull Building> own, int x, int y, int cells) {
        for (Building b : own)
            if (Math.abs(b.getGridX() - x) <= cells && Math.abs(b.getGridY() - y) <= cells)
                return false;
        return true;
    }

    private boolean clearOfDecoys(int x, int y, int cells) {
        for (Decoy d : decoys)
            if (Math.abs(d.x - x) <= cells && Math.abs(d.y - y) <= cells)
                return false;
        return true;
    }

    private static boolean enemyNear(@NonNull Intel intel, int x, int y, int cells) {
        for (Unit e : intel.enemy_warriors)
            if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), x, y) <= cells * cells)
                return true;
        for (Unit e : intel.enemy_chieftains)
            if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), x, y) <= cells * cells)
                return true;
        return false;
    }

    /** The nearest peon free to walk a decoy out: idle, in transit, gathering or walking, and not near enemies. */
    private @Nullable Unit chooseRunner(int x, int y) {
        Intel intel = ai.intel();
        Unit best = null;
        int best_d = 70 * 70;
        for (Unit p : intel.peons) {
            PeonState s = intel.peon_states.get(p);
            if (s != PeonState.IDLE && s != PeonState.TRANSIT && s != PeonState.GATHER_TREE && s != PeonState.MOVE
                    && s != PeonState.GATHER_ROCK && s != PeonState.GATHER_IRON)
                continue;
            if (isRunner(p) || enemyNear(intel, p.getGridX(), p.getGridY(), 10))
                continue;
            int d = MapAnalysis.dist2(p.getGridX(), p.getGridY(), x, y);
            if (d < best_d) {
                best_d = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Whether an enemy warrior stands caged: not fighting, in reach of one of our active towers and more than 9 cells
     * from our towers, quarters and armories, where the towers shoot it and, idle at a decoy, it never answers. The
     * defense leaves such enemies to the towers instead of waking them.
     */
    boolean caged(@NonNull Unit e) {
        if (!ai.strategy().decoys || !ai.strategy().decoy_cage || e.isDead())
            return false;
        Controller current = e.getCurrentController();
        if (current instanceof HuntController || current instanceof AttackController)
            return false;
        int x = e.getGridX();
        int y = e.getGridY();
        boolean reach = false;
        Intel intel = ai.intel();
        for (Building t : intel.towers) {
            int d2 = MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y);
            if (Math.abs(t.getGridX() - x) <= 9 && Math.abs(t.getGridY() - y) <= 9)
                return false;
            if (d2 <= TOWER_REACH * TOWER_REACH && Intel.isTowerActive(t))
                reach = true;
        }
        if (!reach)
            return false;
        for (Building b : intel.quarters)
            if (Math.abs(b.getGridX() - x) <= 9 && Math.abs(b.getGridY() - y) <= 9)
                return false;
        for (Building b : intel.armories)
            if (Math.abs(b.getGridX() - x) <= 9 && Math.abs(b.getGridY() - y) <= 9)
                return false;
        return true;
    }
}
