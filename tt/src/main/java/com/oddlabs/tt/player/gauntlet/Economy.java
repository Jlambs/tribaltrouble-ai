package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.BuildProductionContainer;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.UnitSupplyContainer;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.gui.BuildSpinner;
import com.oddlabs.tt.pathfinder.Occupant;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.gauntlet.Intel.PeonState;
import com.oddlabs.tt.player.gauntlet.SitePlanner.Site;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the base: where and when to build, who builds, how many peons stay in each quarters, how the armory's peons
 * split between making weapons and gathering, and which supply each gatherer works so they do not crowd one tree.
 */
final class Economy {
    /** Man-ticks of armory work per iron weapon: game ticks of one peon's work. */
    private static final float IRON_WORK_TICKS = 4000f; // 80 man-s
    private static final int MAX_BUILDERS = 20;
    /** Peons per supply before another supply is preferred, for trees and for ore. */
    private static final int TREE_LOAD = 2;

    private final @NonNull GauntletAI ai;
    private final List<@NonNull Project> projects = new ArrayList<>();
    private final Map<@NonNull Unit, @NonNull Supply> gather_targets = new LinkedHashMap<>();
    private final Map<@NonNull Supply, Integer> supply_load = new LinkedHashMap<>();
    private final List<@NonNull RubberSupply> chickens = new ArrayList<>();

    private @Nullable Unit scout;
    private @Nullable Site armory_site;
    private @Nullable DistanceField armory_field;
    private @Nullable Building armory_field_owner;
    private float armory_field_time;
    private float chickens_time = -5000f;

    private int want_tree;
    private int want_iron;
    private int want_rock;
    private int want_chicken;
    private int want_workers;
    /** Game ticks of peon time per unit of wood and of iron at the main armory (SitePlanner.gatherTicks). */
    private float tree_cycle = 700f; // 14 s
    private float iron_cycle = 1100f; // 22 s
    /** reloc: the armory iron_cycle was last worked out for (considerRelocation skips a stale one). */
    private @Nullable Building iron_cycle_armory;
    private boolean rock_weapons;
    private boolean rock_filler;
    private @Nullable Project expansion_project;
    private @Nullable Building expansion;
    private float last_expansion_check = -5000f;
    private float last_old_recall = -5000f;
    private int project_counter;
    private final List<@NonNull Building> forward_towers = new ArrayList<>();
    private final List<@NonNull Building> sniper_towers = new ArrayList<>();
    /** Quarters and armories being emptied because they are about to fall (evacuate), and since when. */
    private final Map<@NonNull Building, Float> evacuating = new LinkedHashMap<>();
    private float last_sniper = -5000f;
    /** Per gatherer: the load it carried and since when, to catch peons stuck walking to a supply. */
    private final Map<@NonNull Unit, float @NonNull []> gather_progress = new LinkedHashMap<>();
    /** Supplies a gatherer got stuck on, avoided until the time given. */
    private final Map<@NonNull Supply, Float> bad_supplies = new LinkedHashMap<>();
    private int unstuck;
    /** unstick_builders: each builder's cell and when it got there. */
    private final Map<@NonNull Unit, float @NonNull []> builder_cells = new LinkedHashMap<>();
    private float last_unstuck_log;
    private boolean rush_alert;
    private float rush_alert_time;
    private boolean had_armory;
    /** retire: razings of our own buildings that free a slot for a flagged armory project, and the doomed set. */
    private final @NonNull Retire retire;

    Economy(@NonNull GauntletAI ai) {
        this.ai = ai;
        retire = new Retire(ai, this);
        openingPlan();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Opening

    private void openingPlan() {
        Intel intel = ai.intel();
        SitePlanner planner = ai.planner();
        Strategy strategy = ai.strategy();
        List<Site> reserved = new ArrayList<>();
        armory_site = planner.findArmorySite(reserved);
        int sx = planner.getStartX();
        int sy = planner.getStartY();
        int ax = armory_site != null ? armory_site.x : sx;
        int ay = armory_site != null ? armory_site.y : sy;
        if (armory_site != null)
            reserved.add(armory_site.withHalf(SitePlanner.RaceSizes.ARMORY));

        // The freeze squad (Freeze) walks off at the start: it builds nothing until the strike ends.
        int first_builders = Math.max(1, intel.peons.size() - strategy.scouts - intel.strikers.size());
        // The walk weights are in game ticks per meter (.2 and .06 s here, .25 and .02 s below).
        Site q1 = planner.findQuartersSite(reserved, sx, sy, 110, planner.getStartField(), ax, ay, first_builders,
                10f, 3f);
        // The score is minus the game ticks until the quarters stands; when that is poor nearby, a walk to better
        // ground pays off, and the walk is already part of the score.
        if (q1 == null || -q1.score > 5500f) { // 110 s
            Site further = planner.findQuartersSite(reserved, sx, sy, 400, planner.getStartField(), ax, ay,
                    first_builders, 10f, 3f);
            if (further != null && (q1 == null || further.score > q1.score))
                q1 = further;
        }
        if (q1 == null) {
            // Cramped start: give the quarters the armory's spot rather than go without peons.
            q1 = planner.findQuartersSite(List.of(), sx, sy, 400, planner.getStartField(), ax, ay, first_builders,
                    10f, 0f);
            if (q1 != null && armory_site != null && SitePlanner.conflicts(List.of(armory_site), q1.x, q1.y,
                    SitePlanner.RaceSizes.QUARTERS)) {
                reserved.remove(armory_site);
                armory_site = null;
            }
        }
        if (q1 != null) {
            reserved.add(q1);
            Project p = addProject(Race.BUILDING_QUARTERS, q1, 0);
            p.first = true;
        }
        // Before the armory come the quarters_before_armory first quarters; builders go to one site at a time.
        int armory_priority = 1 + 2 * Math.max(0, strategy.quarters_before_armory - 1);
        if (armory_site != null) {
            Project p = addProject(Race.BUILDING_ARMORY, armory_site, armory_priority);
            p.use_scout = true;
        }
        DistanceField a_field = armory_site != null ? ai.map().computeField(ax, ay, 240) : null;
        for (int i = 1; i < strategy.initial_quarters; i++) {
            Site q;
            if (strategy.opening_near_start && q1 != null)
                q = planner.findQuartersSite(reserved, q1.x, q1.y, 80, planner.getStartField(), ax, ay,
                        strategy.quarters_builders, 12.5f, 1f);
            else if (a_field != null)
                q = planner.findQuartersSite(reserved, ax, ay, 80, a_field, sx, sy, strategy.quarters_builders, 12.5f,
                        1f);
            else
                q = planner.findQuartersSite(reserved, sx, sy, 110, planner.getStartField(), sx, sy,
                        strategy.quarters_builders, 12.5f, 0f);
            if (q == null)
                break;
            reserved.add(q);
            Project p = addProject(Race.BUILDING_QUARTERS, q, 2 * i);
            p.use_scout = true;
        }

        // One peon walks ahead to lay out the armory and later quarters; the rest raise the first quarters at once.
        Unit best_scout = null;
        int best_d = Integer.MAX_VALUE;
        for (Unit peon : intel.peons) {
            if (intel.strikers.contains(peon))
                continue;
            int d = MapAnalysis.dist2(peon.getGridX(), peon.getGridY(), ax, ay);
            if (d < best_d) {
                best_d = d;
                best_scout = peon;
            }
        }
        scout = strategy.scouts > 0 ? best_scout : null;
        Project first = projects.isEmpty() ? null : projects.getFirst();
        if (first != null && first.first) {
            List<Unit> builders = new ArrayList<>();
            for (Unit peon : intel.peons)
                if (peon != scout && !intel.strikers.contains(peon))
                    builders.add(peon);
            if (!builders.isEmpty())
                place(first, builders);
        }
    }

    /**
     * freeze_armory_push (Freeze, after a path-(a) out): our first armory goes right after the first quarters in the
     * build order (priority 1), so free builders reach it before the later quarters (buildersWanted already asks
     * MAX_BUILDERS of it once the first quarters stands). Returns its placed site for the returning squad to build, or
     * null (no armory project, one already finished, or the site not placed yet).
     */
    @Nullable
    Building pushArmory() {
        if (!ai.intel().armories.isEmpty())
            return null;
        Project armory = null;
        for (Project p : projects)
            if (p.type == Race.BUILDING_ARMORY) {
                armory = p;
                break;
            }
        if (armory == null)
            return null;
        if (armory.priority > 1) {
            projects.remove(armory);
            armory.priority = 1;
            int i = 0;
            while (i < projects.size() && projects.get(i).priority <= armory.priority)
                i++;
            projects.add(i, armory);
            ai.aiLog().count("freeze_push_priority");
            ai.log("freeze push: " + armory.describe() + " moves up to priority 1");
        }
        Building site = armory.building;
        return armory.isPlaced() && site != null && !site.isComplete() ? site : null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Periodic work

    void tick() {
        Intel intel = ai.intel();
        choosePrimaryArmory();
        refreshArmoryField();
        Strategy st = ai.strategy();
        if (st.tower_cooldown || st.reloc || st.rearm_reach > 0 || st.reloc_lock_ticks > 0 || st.retire)
            trackRazings();
        manageProjects();
        if (st.reloc || st.reloc_lock_ticks > 0)
            watchReloc();
        if (st.retire)
            retire.tick();
        escortForward();
        evacuate();
        measureYield();
        manageQuarters();
        manageArmory();
        guardBank();
        allocatePeons();
        manageRepairs();
    }

    /**
     * Whether a periodic check that re-arms as {@code next = now + period} on the tick it runs is due again: the test
     * of {@link GauntletAI#periodDue} for a next time, which may come up to {@link GauntletAI#slack} early.
     */
    private boolean nextDue(float next) {
        return ai.now() >= next - ai.slack();
    }

    /** Whether the building is being emptied because it is about to fall. */
    boolean isEvacuating(@NonNull Building b) {
        return evacuating.containsKey(b);
    }

    /**
     * Units inside a razed building die with it, uncounted (LandBuilding.removeDying): ~123 per game at N=10, 39 per
     * armory. A quarters or armory below evac_hp of its hit points with at least evac_min enemy warriors within 10
     * cells is emptied: the armory's weapons leave as warriors, everyone else as peons, towards a rally point away from
     * the attackers (for a quarters, into our quarters farthest from them). For 60 s nothing is sent into it.
     */
    private void evacuate() {
        Strategy strategy = ai.strategy();
        if (!raid_evacs.isEmpty())
            endRaidEvacs();
        evacuating.entrySet().removeIf(e -> e.getKey().isDead() || ai.now() - e.getValue() > 3000f); // 60 s
        // raid_evac runs before the early return: evacuate is off by default.
        if (strategy.raid_evac)
            raidEvacuate();
        if (!strategy.evacuate)
            return;
        Intel intel = ai.intel();
        List<Building> homes = intel.homes();
        for (Building b : homes) {
            if (b.isDead() || !b.isComplete() || b.getUnitContainer() == null)
                continue;
            int inside = b.getUnitContainer().getNumSupplies();
            if (inside == 0 && !evacuating.containsKey(b))
                continue;
            if (b.getHitPoints() > strategy.evac_hp * b.getTemplate().getMaxHitPoints())
                continue;
            int n = 0;
            long ex = 0;
            long ey = 0;
            for (Unit e : intel.enemy_warriors) {
                if (e.isDead() || MapAnalysis.dist2(e.getGridX(), e.getGridY(), b.getGridX(), b.getGridY()) > 10 * 10)
                    continue;
                n++;
                ex += e.getGridX();
                ey += e.getGridY();
            }
            if (n < strategy.evac_min)
                continue;
            Player owner = ai.owner();
            if (!evacuating.containsKey(b)) {
                int cx = (int) (ex / n);
                int cy = (int) (ey / n);
                boolean quarters = b.getTemplate().getTemplateID() == Race.BUILDING_QUARTERS;
                Building refuge = null;
                int far = -1;
                if (quarters)
                    for (Building q : intel.quarters) {
                        if (q == b || q.isDead() || evacuating.containsKey(q) || retire.isDoomed(q))
                            continue;
                        int d = MapAnalysis.dist2(q.getGridX(), q.getGridY(), cx, cy);
                        if (d > far && !ai.military().threatNear(q.getGridX(), q.getGridY(), 16)) {
                            far = d;
                            refuge = q;
                        }
                    }
                if (refuge != null) {
                    owner.setRallyPoint(b, refuge);
                } else {
                    float dx = b.getGridX() - cx;
                    float dy = b.getGridY() - cy;
                    float len = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
                    int size = ai.map().getSize();
                    int rx = Math.max(3, Math.min(size - 4, b.getGridX() + Math.round(18 * dx / len)));
                    int ry = Math.max(3, Math.min(size - 4, b.getGridY() + Math.round(18 * dy / len)));
                    owner.setRallyPoint(b, rx, ry);
                }
                evacuating.put(b, ai.now());
                ai.aiLog().count(quarters ? "evac_quarters" : "evac_armory");
                ai.log("evacuating " + (quarters ? "quarters" : "armory") + " at " + b.getGridX() + "," + b.getGridY() + ": " + inside + " inside, hp " + b.getHitPoints() + ", " + n + " enemy warriors by it");
            }
            continueEvacuation(b, inside);
        }
    }

    /**
     * An evacuated building with `inside` units in it lets them out: an armory's weapons as warriors, the rest as
     * peons.
     */
    private void continueEvacuation(@NonNull Building b, int inside) {
        if (inside == 0)
            return;
        int deployed = 0;
        if (b.getTemplate().getTemplateID() == Race.BUILDING_ARMORY)
            deployed = deployWarriors(b, inside, stock(b, RubberAxeWeapon.class), stock(b, IronAxeWeapon.class),
                    stock(b, RockAxeWeapon.class));
        int pending = b.getDeployContainer(DeployType.PEON).getNumSupplies();
        int peons = inside - deployed - pending;
        if (peons > 0)
            ai.owner().deployUnits(b, DeployType.PEON, peons);
    }

    /**
     * sortie_ratio (Military.sorties): everyone inside the armory comes out to fight, its weapons as warriors and the
     * rest as peons. A deploy order takes its workers at once, so those still inside are the ones not yet ordered out.
     */
    void sortieDeploy(@NonNull Building a) {
        int inside = a.getUnitContainer().getNumSupplies();
        if (inside == 0)
            return;
        deployWarriors(a, inside, stock(a, RubberAxeWeapon.class), stock(a, IronAxeWeapon.class),
                stock(a, RockAxeWeapon.class));
        int peons = a.getUnitContainer().getNumSupplies();
        if (peons > 0)
            ai.owner().deployUnits(a, DeployType.PEON, peons);
    }

    // ------------------------------------------------------------------------------------------------------------
    // raid_evac

    /** raid_evac: a wave seen launched at one of our armories, followed until its front is RAID_LEAD_TICKS out. */
    private static final class Raid {
        final @NonNull Building armory;
        final @NonNull Player copy;
        final @NonNull List<@NonNull Unit> wave;
        final int tx;
        final int ty;

        Raid(@NonNull Building armory, @NonNull Player copy, @NonNull List<@NonNull Unit> wave, int tx, int ty) {
            this.armory = armory;
            this.copy = copy;
            this.wave = wave;
            this.tx = tx;
            this.ty = ty;
        }
    }

    /** raid_evac: an armory emptied ahead of a wave, and where its peons went (the home armory, or a quarters). */
    private static final class RaidEvac {
        @Nullable
        Building refuge;
        @Nullable
        Building shelter;
    }

    private final List<@NonNull Raid> raids = new ArrayList<>();
    private final Map<@NonNull Building, @NonNull RaidEvac> raid_evacs = new LinkedHashMap<>();
    /** raid_evac: peons a raid evacuation sent into each quarters, kept there above its hold until the time given. */
    private final Map<@NonNull Building, float @NonNull []> raid_hold = new LinkedHashMap<>();
    /**
     * raid_evac: a wave's pace (cells per game tick), how long before its front arrives the armory empties, and too
     * late to (game ticks).
     */
    private static final float WAVE_CELLS_PER_TICK = .05f; // 2.5 cells/s
    private static final float RAID_LEAD_TICKS = 2250f; // 45 s
    private static final float RAID_LATE_TICKS = 500f; // 10 s
    /** raid_evac: a launch aimed within this many cells of an armory goes for it. */
    private static final int RAID_CELLS = 20;
    /** raid_evac: the least wave followed (the copy's warriors walking to within 12 cells of the launch's cell). */
    private static final int RAID_WAVE_MIN = 12;

    /**
     * raid_evac (Shepherd, on a launch it saw): the copy's wave walks to (tx, ty). Aimed within RAID_CELLS of a
     * complete armory of ours with at least RAID_WAVE_MIN warriors (the copy's warriors walking aggressively to within
     * 12 cells of that cell), it is followed (raidEvacuate) until its front is RAID_LEAD_TICKS from the target.
     */
    void waveLaunched(@NonNull Player copy, int tx, int ty) {
        Strategy st = ai.strategy();
        if (!st.raid_evac)
            return;
        Intel intel = ai.intel();
        Building target = null;
        int best = RAID_CELLS * RAID_CELLS + 1;
        for (Building a : intel.armories) {
            if (a.isDead() || !a.isComplete())
                continue;
            int d = MapAnalysis.dist2(a.getGridX(), a.getGridY(), tx, ty);
            if (d < best) {
                best = d;
                target = a;
            }
        }
        if (target == null)
            return;
        List<Unit> wave = new ArrayList<>();
        for (Unit e : intel.enemy_warriors)
            if (e.getOwner() == copy && !e.isDead() && e.getPrimaryController() instanceof WalkController w
                    && w.isAgressive() && MapAnalysis.dist2(w.getTarget().getGridX(), w.getTarget().getGridY(), tx,
                            ty) <= 12 * 12)
                wave.add(e);
        ai.aiLog().count("raid_wave_seen");
        if (wave.size() < RAID_WAVE_MIN) {
            ai.aiLog().count("raid_wave_small");
            return;
        }
        if (evacuating.containsKey(target)) {
            ai.aiLog().count("raid_wave_emptying");
            return;
        }
        Building a = target;
        raids.removeIf(r -> r.armory == a && r.copy == copy);
        raids.add(new Raid(a, copy, wave, tx, ty));
        if (ai.logging()) {
            int inside = a.getUnitContainer().getNumSupplies();
            float strength = Combat.total(wave);
            ai.aiLog().log("RAID", () -> String.format(
                    "wave of %s (%d warriors, strength %.0f) to %d,%d goes for the armory at %d,%d (%d inside)",
                    copy.getPlayerInfo().getName(), wave.size(), strength, tx, ty, a.getGridX(), a.getGridY(),
                    inside));
        }
    }

    /**
     * raid_evac, every economy tick: follows the waves launched at our armories. A wave whose front (its nearest
     * warrior still walking, or fighting on the way, to within 12 cells of the launch's cell: a warrior sent elsewhere
     * since no longer counts) is within RAID_LEAD_TICKS of its target empties the armory when it holds at least
     * raid_evac_min units, it is raid_evac_ticks or later, and the wave outweighs the armory's defence (manned towers
     * within 16 cells and our warriors within 20) by raid_evac_ratio; under RAID_LATE_TICKS it is too late (the
     * evacuees would walk into it). Then every armory being emptied lets its units out.
     */
    private void raidEvacuate() {
        Strategy st = ai.strategy();
        Intel intel = ai.intel();
        for (Iterator<Raid> it = raids.iterator(); it.hasNext();) {
            Raid r = it.next();
            Building a = r.armory;
            if (a.isDead() || evacuating.containsKey(a)) {
                it.remove();
                continue;
            }
            List<Unit> moving = new ArrayList<>();
            int front = Integer.MAX_VALUE;
            for (Unit u : r.wave) {
                // Still on its way there, fighting on the way included; a warrior sent elsewhere since drops out.
                if (u.isDead() || !(u.getPrimaryController() instanceof WalkController w))
                    continue;
                int tx = w.getTarget().getGridX();
                int ty = w.getTarget().getGridY();
                if (MapAnalysis.dist2(tx, ty, r.tx, r.ty) > 12 * 12)
                    continue;
                moving.add(u);
                front = Math.min(front, MapAnalysis.dist2(u.getGridX(), u.getGridY(), r.tx, r.ty));
            }
            if (moving.isEmpty() || 2 * moving.size() < RAID_WAVE_MIN) {
                it.remove();
                ai.aiLog().count("raid_evac_gone"); // the wave stopped or died on its way
                continue;
            }
            float eta = (float) Math.sqrt(front) / WAVE_CELLS_PER_TICK;
            if (eta > RAID_LEAD_TICKS)
                continue;
            it.remove();
            if (eta < RAID_LATE_TICKS) {
                ai.aiLog().count("raid_evac_late");
                continue;
            }
            // Nothing much to save: a minute of forging and gathering lost for a few units (the verify smoke emptied
            // armories holding 0-11 in 9 of 24 evacuations).
            if (a.getUnitContainer().getNumSupplies() < st.raid_evac_min) {
                ai.aiLog().count("raid_evac_empty");
                continue;
            }
            if (ai.now() < st.raid_evac_ticks) {
                ai.aiLog().count("raid_evac_early");
                continue;
            }
            int ax = a.getGridX();
            int ay = a.getGridY();
            float defence = Combat.strengthNear(intel.towers, ax, ay, 16) + Combat.strengthNear(intel.warriors, ax, ay,
                    20);
            float strength = Combat.total(moving);
            if (strength < st.raid_evac_ratio * defence) {
                ai.aiLog().count("raid_evac_defended");
                ai.aiLog().log("RAID", () -> String.format(
                        "armory at %d,%d stays: the wave of %s (%d, strength %.0f, %.0f s out) against a defence of %.0f",
                        ax, ay, r.copy.getPlayerInfo().getName(), moving.size(), strength, GauntletAI.seconds(eta),
                        defence));
                continue;
            }
            startRaidEvac(a, r, moving, eta, strength, defence);
        }
        for (Building b : raid_evacs.keySet())
            if (!b.isDead() && evacuating.containsKey(b))
                continueEvacuation(b, b.getUnitContainer().getNumSupplies());
    }

    /**
     * raid_evac: empties the armory ahead of the wave. Its units go to the home armory's cell when that is another
     * armory with no threat within 16 cells (an armory takes in any unit, and a warrior going in gives up its axe: so
     * the cell, and allocatePeons sends the peons in), else into the quarters farthest from the threat (quarters turn
     * warriors away: they wait at its door), else 18 cells away from the wave.
     */
    private void startRaidEvac(@NonNull Building a, @NonNull Raid r, @NonNull List<@NonNull Unit> wave, float eta,
            float strength, float defence) {
        Player owner = ai.owner();
        float now = ai.now();
        int inside = a.getUnitContainer().getNumSupplies();
        int weapons = stock(a, IronAxeWeapon.class) + stock(a, RubberAxeWeapon.class) + stock(a, RockAxeWeapon.class);
        RaidEvac ev = new RaidEvac();
        Building home = homeArmory(a);
        String to;
        if (home != a && !evacuating.containsKey(home) && !retire.isDoomed(home)
                && !ai.military().threatNear(home.getGridX(), home.getGridY(), 16)) {
            owner.setRallyPoint(a, home.getGridX(), home.getGridY());
            ev.refuge = home;
            to = "home";
        } else {
            Building q = reserveQuarters();
            if (q != null) {
                owner.setRallyPoint(a, q);
                ev.shelter = q;
                holdEvacuees(q, Math.max(0, inside - weapons));
                to = "quarters";
            } else {
                long wx = 0;
                long wy = 0;
                for (Unit u : wave) {
                    wx += u.getGridX();
                    wy += u.getGridY();
                }
                float dx = a.getGridX() - wx / (float) wave.size();
                float dy = a.getGridY() - wy / (float) wave.size();
                float len = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
                int size = ai.map().getSize();
                int rx = Math.max(3, Math.min(size - 4, a.getGridX() + Math.round(18 * dx / len)));
                int ry = Math.max(3, Math.min(size - 4, a.getGridY() + Math.round(18 * dy / len)));
                owner.setRallyPoint(a, rx, ry);
                to = "away";
            }
        }
        evacuating.put(a, now);
        raid_evacs.put(a, ev);
        ai.aiLog().count("raid_evac_start");
        ai.aiLog().count("raid_evac_to_" + to);
        ai.aiLog().count(home == a ? "raid_evac_homearmory" : "raid_evac_forward");
        for (int i = 0; i < inside; i++)
            ai.aiLog().count("raid_evac_units");
        ai.aiLog().log("RAID", () -> String.format(
                "emptying the armory at %d,%d (%d inside, %d weapons) to %s: wave of %s (%d, strength %.0f) %.0f s out, defence %.0f",
                a.getGridX(), a.getGridY(), inside, weapons, to, r.copy.getPlayerInfo().getName(), wave.size(),
                strength, GauntletAI.seconds(eta), defence));
    }

    /** raid_evac: n more peons stay in quarters q above its hold for the next 60 s. */
    private void holdEvacuees(@NonNull Building q, int n) {
        float[] h = raid_hold.get(q);
        if (h == null)
            raid_hold.put(q, new float[]{n, ai.now() + 3000f}); // 60 s
        else {
            h[0] += n;
            h[1] = ai.now() + 3000f;
        }
    }

    /** raid_evac: peons quarters q keeps above its hold for a raid evacuation now. */
    private int evacueesHeld(@NonNull Building q) {
        float[] h = raid_hold.get(q);
        return h == null || ai.now() >= h[1] ? 0 : (int) h[0];
    }

    /** raid_evac: whether the armory is being emptied ahead of a wave. */
    boolean raidEvacuating(@Nullable Building a) {
        return a != null && raid_evacs.containsKey(a) && evacuating.containsKey(a);
    }

    /** raid_evac: whether evacuees of a raid evacuation wait in this armory (drainSecondary keeps them). */
    private boolean raidRefuge(@NonNull Building a) {
        for (RaidEvac ev : raid_evacs.values())
            if (ev.refuge == a)
                return true;
        return false;
    }

    /**
     * raid_evac: an evacuation ahead of a wave ends with its 60-s window (evacuate's) or its armory: the armory's rally
     * point is cleared again (units it lets out then enter the nearest armory or stand by it, as before).
     */
    private void endRaidEvacs() {
        float now = ai.now();
        for (Iterator<Map.Entry<Building, RaidEvac>> it = raid_evacs.entrySet().iterator(); it.hasNext();) {
            Building b = it.next().getKey();
            Float since = evacuating.get(b);
            if (!b.isDead() && since != null && now - since <= 3000f) // 60 s
                continue;
            it.remove();
            boolean razed = b.isDead();
            ai.aiLog().count(razed ? "raid_evac_razed" : "raid_evac_stood");
            if (!razed)
                ai.owner().setRallyPoint(b, b);
            ai.aiLog().log("RAID",
                    () -> "the armory emptied at " + b.getGridX() + "," + b.getGridY() + (razed ? " fell" : " stood") + (since != null ? " (" + (int) GauntletAI.seconds(
                            now - since) + " s)" : ""));
        }
        raid_hold.entrySet().removeIf(e -> e.getKey().isDead() || now >= e.getValue()[1]);
    }

    void plan() {
        checkRush();
        planBuildings();
        computeGatherTargets();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Construction

    static final class Project {
        final int type;
        final int id;
        @NonNull
        Site site;
        @Nullable
        Building building;
        int priority;
        boolean first;
        boolean use_scout;
        /** A tower out by the enemy's gatherers, built under the army's cover. */
        boolean forward;
        /** A sniper tower next to idle enemies parked by our base (planSniper). */
        boolean sniper;
        int failures;
        float placed_time = -1f;
        /** veto_resite: since when projectMayStart has vetoed it for a threat near its site (-1: not vetoed). */
        float veto_since = -1f;
        /** tower_wood_drop: pieces of wood ordered out of an armory for this site. */
        int wood_sent;
        /** tower_wood_drop: when the last deploy for this project was ordered. */
        float wood_last = -5000f;
        /** reloc: an armory hop (considerRelocation), and when it was planned. */
        boolean reloc;
        float added = -1f;
        /**
         * reloc_lock: an armory moved to trees out of the wood lock (lockRelocate); it and a hop under reloc_slot:
         * since when its slot stands open.
         */
        boolean lock;
        float open_since = -1f;
        /** rearm_placer: the peon last sent to place this armory, and whether evacuatePeons ever left it be. */
        @Nullable
        Unit placer;
        boolean evac_skipped;
        /** rearm_placer: since when this armory project has found no placer at all (-1: it has one). */
        float noplacer_since = -1f;

        Project(int type, int id, @NonNull Site site, int priority) {
            this.type = type;
            this.id = id;
            this.site = site;
            this.priority = priority;
        }

        boolean isPlaced() {
            return building != null && !building.isDead() && building.isPlaced();
        }

        @NonNull
        String describe() {
            String name = switch (type) {
                case Race.BUILDING_QUARTERS -> "quarters";
                case Race.BUILDING_ARMORY -> "armory";
                default -> sniper ? "sniper tower" : forward ? "forward tower" : "tower";
            };
            return name + "#" + id + " at " + site.x + "," + site.y;
        }
    }

    private @NonNull Project addProject(int type, @NonNull Site site, int priority) {
        site.withHalf(SitePlanner.RaceSizes.of(type));
        Project p = new Project(type, project_counter++, site, priority);
        ai.log("plan " + p.describe() + " trees=" + ai.map().treesAround(site.x, site.y,
                7) + " from start=" + ai.planner().getStartField().get(site.x, site.y) + "m");
        int i = 0;
        while (i < projects.size() && projects.get(i).priority <= priority)
            i++;
        projects.add(i, p);
        return p;
    }

    /** Sends the builders to place the project's site, and keeps the site they carry. */
    private void place(@NonNull Project p, @NonNull List<@NonNull Unit> builders) {
        p.building = ai.placeSite(builders, p.type, p.site.x, p.site.y);
    }

    private void order(@NonNull List<@NonNull Unit> units, @Nullable Building target, @NonNull Action action) {
        if (target == null || units.isEmpty())
            return;
        ai.owner().setTarget(units.toArray(new Selectable<?>[0]), target, action, false);
    }

    private void order(@NonNull Unit unit, @NonNull Building target, @NonNull Action action) {
        ai.owner().setTarget(Selectable.newArray(unit), target, action, false);
    }

    private int builderCount(@NonNull Building building) {
        int n = 0;
        for (Building b : ai.intel().builder_sites.values())
            if (b == building)
                n++;
        return n;
    }

    private @Nullable Unit placerOf(@NonNull Building building) {
        for (Map.Entry<Unit, Building> e : ai.intel().builder_sites.entrySet())
            if (e.getValue() == building)
                return e.getKey();
        return null;
    }

    private void manageProjects() {
        Intel intel = ai.intel();
        for (Iterator<Project> it = projects.iterator(); it.hasNext();) {
            Project p = it.next();
            if (p.building != null && p.building.isDead()) {
                it.remove();
                continue;
            }
            if (p.isPlaced()) {
                if (p.placed_time < 0) {
                    p.placed_time = ai.now();
                    ai.log("placed " + p.describe());
                    if (p.lock)
                        ai.aiLog().count("lock_placed");
                    if ((p.reloc || p.lock) && ai.strategy().retire)
                        retire.flaggedPlaced();
                }
                if (p.building.isComplete()) {
                    it.remove();
                    ai.log("completed " + p.describe() + " after " + (int) GauntletAI.seconds(
                            ai.now() - p.placed_time) + "s");
                    if (p.wood_sent > 0) {
                        Project q = p;
                        ai.aiLog().log("WOOD",
                                () -> "completed " + q.describe() + " after " + (int) GauntletAI.seconds(
                                        ai.now() - q.placed_time) + "s with " + q.wood_sent + " wood sent");
                    }
                    onCompleted(p);
                }
                continue;
            }
            if (p.building != null && placerOf(p.building) != null)
                continue; // placer on its way
            if (p.building != null) {
                // Nobody is placing it any more: the site was blocked or the placer died. Try somewhere close by.
                if (p.placer != null)
                    placerFailed(p);
                p.failures++;
                p.building = null;
                if (p.failures > 6 || p.sniper) {
                    it.remove();
                    continue;
                }
                Site moved = resite(p);
                if (moved == null) {
                    it.remove();
                    continue;
                }
                p.site = moved;
            }
            if (!projectMayStart(p)) {
                if (last_veto == VETO_THREAT && resiteEligible(p))
                    vetoResite(p, it);
                continue;
            }
            p.veto_since = -1f;
            Unit placer = choosePlacer(p);
            if (placer == null) {
                if (p.noplacer_since >= 0f && ai.now() - p.noplacer_since > REARM_WAIT_TICKS && isRebuild(p)) {
                    // rearm_placer: a lost armory's site with no safe way to it is planned afresh.
                    it.remove();
                    ai.aiLog().count("rearm_placer_timeout");
                    ai.log("drop " + p.describe() + ": no safe placer for " + (int) GauntletAI.seconds(
                            REARM_WAIT_TICKS) + "s");
                }
                continue;
            }
            place(p, List.of(placer));
            if (p.building != null)
                intel.builder_sites.put(placer, p.building);
            intel.peon_states.put(placer, PeonState.BUILD);
            if ((ai.strategy().rearm_placer || p.lock) && p.type == Race.BUILDING_ARMORY && p.building != null)
                p.placer = placer;
        }
    }

    /**
     * rearm_placer (counters only): an armory placer that no longer carries its site, by cause: killed (and whether
     * evacuatePeons had left it be), or re-ordered.
     */
    private void placerFailed(@NonNull Project p) {
        Unit u = p.placer;
        p.placer = null;
        if (u == null)
            return;
        if (u.isDead()) {
            ai.aiLog().count("rearm_placer_died");
            if (p.evac_skipped)
                ai.aiLog().count("rearm_placer_died_exempt");
        } else {
            ai.aiLog().count("rearm_placer_lost");
        }
        if (ai.logging())
            ai.log("armory placer for " + p.describe() + (u.isDead() ? " died" : " re-ordered: " + ai.intel().peon_states.get(
                    u)));
    }

    private boolean projectMayStart(@NonNull Project p) {
        last_veto = VETO_NONE;
        if (p.use_scout)
            return true;
        // reloc: a hop's placer walks out only once the site can take a building (reloc_slot plans it at the cap);
        // reloc_lock: so does a lock move's.
        if ((p.reloc || p.lock) && !ai.owner().canBuild(Race.BUILDING_ARMORY)) {
            ai.aiLog().count(p.lock ? "lock_slot_wait" : "reloc_slot_wait"); // economy ticks (1 s)
            last_veto = VETO_SITES;
            return false;
        }
        // reloc_slot, reloc_lock: the next freed slot is the waiting armory's, not a tower's, a quarters' or a sniper
        // tower's.
        Project waiter = !p.reloc && !p.lock ? slotWaiter() : null;
        if (waiter != null) {
            String what = p.type == Race.BUILDING_TOWER ? "tower" : "other";
            ai.aiLog().count((waiter.lock ? "lock_slot_" : "reloc_slot_") + what + "_wait"); // economy ticks (1 s)
            last_veto = VETO_SITES;
            return false;
        }
        if (p.sniper)
            return sniperSafe(p.site.x, p.site.y);
        // A placer sent into a fight only dies there.
        if (ai.military().threatNearEcon(p.site.x, p.site.y, 16)) {
            last_veto = VETO_THREAT;
            return false;
        }
        if (ai.strategy().tower_cooldown && p.type == Race.BUILDING_TOWER && recentlyRazedNear(p.site.x, p.site.y)) {
            ai.aiLog().count("tower_cooldown_skip");
            last_veto = VETO_COOLDOWN;
            return false;
        }
        // Builders only walk out once the army stands guard.
        if (p.forward) {
            boolean arrived = ai.military().escortArrived(p.site.x, p.site.y);
            if (!arrived)
                last_veto = VETO_ESCORT;
            return arrived;
        }
        // Keep the number of simultaneous sites small so builders are not spread thin.
        int placed_incomplete = 0;
        for (Project q : projects)
            if (q != p && q.isPlaced() && q.type != Race.BUILDING_ARMORY)
                placed_incomplete++;
        int sites = ai.now() >= ai.strategy().tower_parallel_late_ticks ? ai.strategy().sites_parallel_late : ai.strategy().sites_parallel;
        boolean may = p.type == Race.BUILDING_ARMORY || placed_incomplete < sites;
        if (may && ai.strategy().site_towers_first && p.type == Race.BUILDING_QUARTERS
                && placed_incomplete == sites - 1 && ai.now() >= ai.strategy().tower_parallel_late_ticks
                && ai.intel().quarters.size() >= ai.strategy().tower_min_quarters) {
            Project tower = waitingTower();
            if (tower != null) {
                may = false;
                ai.aiLog().count("site_towers_first_used"); // economy ticks (1 s)
                ai.aiLog().log("SITES", () -> p.describe() + " leaves the last site slot to " + tower.describe());
            }
        }
        if (!may)
            last_veto = VETO_SITES;
        return may;
    }

    /**
     * site_towers_first: an unplaced tower project that could start now (not forward, sniper or scout-placed, and no
     * threat by its site), or null.
     */
    private @Nullable Project waitingTower() {
        for (Project q : projects)
            if (q.type == Race.BUILDING_TOWER && !q.isPlaced() && !q.forward && !q.sniper && !q.use_scout
                    && !ai.military().threatNearEcon(q.site.x, q.site.y, 16))
                return q;
        return null;
    }

    /** Why projectMayStart last said no (bookkeeping for veto_resite): none, threat, cooldown, escort, sites. */
    private static final int VETO_NONE = 0;
    private static final int VETO_THREAT = 1;
    private static final int VETO_COOLDOWN = 2;
    private static final int VETO_ESCORT = 3;
    private static final int VETO_SITES = 4;
    private int last_veto;
    /**
     * veto_resite: tower (quarters) planning pauses until then after a vetoed project with no clear site is dropped.
     */
    private float tower_hold_until = -1f;
    private float quarters_hold_until = -1f;

    /** veto_resite: a project a threat keeps from starting may be moved or dropped once it has waited long enough. */
    private boolean resiteEligible(@NonNull Project p) {
        Strategy st = ai.strategy();
        return st.veto_resite_ticks > 0f && ai.now() >= st.veto_resite_from_ticks && p.building == null && !p.forward
                && !p.sniper && !p.use_scout && (p.type == Race.BUILDING_TOWER
                        || (p.type == Race.BUILDING_QUARTERS && st.veto_resite_quarters));
    }

    /**
     * veto_resite: a project vetoed for a threat within 16 cells of its site for veto_resite_ticks moves to the nearest
     * site with no threat within veto_resite_clear cells, or is dropped and its type's planning pauses for
     * veto_resite_ticks. Unplaced tower projects count against tower_parallel, so one vetoed project otherwise stops
     * all tower planning until the threat by its site leaves (late/spec S1: 225-794 s in 14 of 16 logged N=12 games).
     */
    private void vetoResite(@NonNull Project p, @NonNull Iterator<Project> it) {
        float now = ai.now();
        Strategy st = ai.strategy();
        boolean tower = p.type == Race.BUILDING_TOWER;
        if (p.veto_since < 0f)
            p.veto_since = now;
        ai.aiLog().count(tower ? "tower_veto_ticks" : "quarters_veto_ticks"); // economy ticks (1 s)
        if (now - p.veto_since < st.veto_resite_ticks)
            return;
        int vetoed = (int) GauntletAI.seconds(now - p.veto_since);
        Site s = clearSite(p.type, p.site.x, p.site.y, p);
        if (s != null) {
            ai.log("re-site " + p.describe() + " to " + s.x + "," + s.y + " (vetoed " + vetoed + "s)");
            p.site = s.withHalf(SitePlanner.RaceSizes.of(p.type));
            p.veto_since = -1f;
            ai.aiLog().count(tower ? "veto_resite" : "veto_resite_quarters");
        } else {
            ai.log("drop " + p.describe() + ": vetoed, no clear site");
            it.remove();
            if (tower)
                tower_hold_until = now + st.veto_resite_ticks;
            else
                quarters_hold_until = now + st.veto_resite_ticks;
            ai.aiLog().count("veto_resite_drop");
        }
    }

    /**
     * veto_resite: the nearest legal site for a tower or quarters with no threat within veto_resite_clear cells (and,
     * with tower_cooldown, no recent razing by it): first around (ox, oy), then around the home and main armories and
     * every complete quarters, nearest to (ox, oy) first; null when there is none or the building cap is reached.
     */
    private @Nullable Site clearSite(int type, int ox, int oy, @Nullable Project except) {
        if (!ai.owner().canBuild(type))
            return null;
        Strategy st = ai.strategy();
        Military m = ai.military();
        SitePlanner planner = ai.planner();
        Intel intel = ai.intel();
        boolean tower = type == Race.BUILDING_TOWER;
        SitePlanner.CellOk ok = (x, y) -> !m.threatNearEcon(x, y, st.veto_resite_clear)
                && !(st.tower_cooldown && tower && recentlyRazedNear(x, y));
        List<Site> reserved = reservedSites(except);
        List<int[]> anchors = new ArrayList<>();
        anchors.add(new int[]{ox, oy});
        Building primary = intel.armory();
        if (primary != null) {
            Building home = homeArmory(primary);
            anchors.add(new int[]{home.getGridX(), home.getGridY()});
            if (home != primary)
                anchors.add(new int[]{primary.getGridX(), primary.getGridY()});
        }
        List<Building> quarters = new ArrayList<>();
        for (Building q : intel.quarters)
            if (!q.isDead() && q.isComplete())
                quarters.add(q);
        // A stable sort: ties stay in list order.
        quarters.sort(Comparator.comparingInt(q -> MapAnalysis.dist2(q.getGridX(), q.getGridY(), ox, oy)));
        for (Building q : quarters)
            anchors.add(new int[]{q.getGridX(), q.getGridY()});
        boolean threat = m.baseThreatLevel() > 0;
        int face_x = threat ? m.threatX() : planner.getEnemyX();
        int face_y = threat ? m.threatY() : planner.getEnemyY();
        List<int[]> existing = tower ? existingTowers() : List.of();
        for (int k = 0; k < anchors.size(); k++) {
            int[] a = anchors.get(k);
            Site s = tower ? planner.findTowerSite(reserved, a[0], a[1], k == 0 ? 4 : 7, k == 0 ? 16 : 15, existing,
                    face_x, face_y, ok) : planner.findQuartersSiteLike(reserved, a[0], a[1], k == 0 ? 14 : 20, type,
                            ok);
            if (s != null)
                return s;
        }
        return null;
    }

    /** tower_site_fallback: no fallback search before this time (after a miss). */
    private float fallback_next;

    /**
     * tower_site_fallback: a tower site around another anchor when the planned one's ring is full: the same center at
     * 4-20 cells, then the home and primary armory and each finished quarters (nearest the center first) at 7-15 cells,
     * then those again at 4-20. Null when none has a legal site.
     */
    private @Nullable Site fallbackTowerSite(int @NonNull [] center, int @NonNull [] face,
            @NonNull List<int @NonNull []> existing) {
        SitePlanner planner = ai.planner();
        Intel intel = ai.intel();
        List<Site> reserved = reservedSites(null);
        Site s = planner.findTowerSite(reserved, center[0], center[1], 4, 20, existing, face[0], face[1]);
        if (s != null)
            return s;
        List<int[]> anchors = new ArrayList<>();
        Building primary = intel.armory();
        if (primary != null) {
            Building home = homeArmory(primary);
            anchors.add(new int[]{home.getGridX(), home.getGridY()});
            if (home != primary)
                anchors.add(new int[]{primary.getGridX(), primary.getGridY()});
        }
        List<Building> quarters = new ArrayList<>();
        for (Building q : intel.quarters)
            if (!q.isDead() && q.isComplete())
                quarters.add(q);
        // A stable sort: ties stay in list order.
        quarters.sort(Comparator.comparingInt(q -> MapAnalysis.dist2(q.getGridX(), q.getGridY(), center[0],
                center[1])));
        for (Building q : quarters)
            anchors.add(new int[]{q.getGridX(), q.getGridY()});
        for (int pass = 0; pass < 2; pass++)
            for (int[] a : anchors) {
                s = planner.findTowerSite(reserved, a[0], a[1], pass == 0 ? 7 : 4, pass == 0 ? 15 : 20, existing,
                        face[0], face[1]);
                if (s != null)
                    return s;
            }
        return null;
    }

    /** Our towers and tower sites, as {x, y}. */
    private @NonNull List<int @NonNull []> existingTowers() {
        Intel intel = ai.intel();
        List<int[]> existing = new ArrayList<>();
        for (Building t : intel.towers)
            existing.add(new int[]{t.getGridX(), t.getGridY()});
        for (Building t : intel.tower_sites)
            existing.add(new int[]{t.getGridX(), t.getGridY()});
        return existing;
    }

    /** tower_cooldown: our buildings (and sites) seen standing, and where and when one of them fell. */
    private final java.util.Set<@NonNull Building> standing = new java.util.LinkedHashSet<>();
    private final List<float @NonNull []> razings = new ArrayList<>();
    /** reloc, rearm_reach: the same razings kept for 180 s (quietOk). */
    private final List<float @NonNull []> razings_long = new ArrayList<>();

    private void trackRazings() {
        Intel intel = ai.intel();
        for (java.util.Iterator<Building> it = standing.iterator(); it.hasNext();) {
            Building b = it.next();
            if (b.isDead()) {
                float[] r = {b.getGridX(), b.getGridY(), ai.now()};
                razings.add(r);
                razings_long.add(r);
                it.remove();
            }
        }
        razings.removeIf(r -> ai.now() - r[2] > 4500f); // 90 s
        razings_long.removeIf(r -> ai.now() - r[2] > 9000f); // 180 s
        for (List<Building> group : List.of(intel.quarters, intel.armories, intel.towers, intel.quarters_sites,
                intel.armory_sites, intel.tower_sites))
            for (Building b : group)
                if (!b.isDead())
                    standing.add(b);
    }

    private boolean recentlyRazedNear(int x, int y) {
        for (float[] r : razings)
            if (MapAnalysis.dist2(x, y, (int) r[0], (int) r[1]) <= 25 * 25)
                return true;
        return false;
    }

    /**
     * reloc, rearm_reach: an armory site is quiet with no threat and no enemy warrior or chieftain within 30 cells, and
     * no building of ours razed within 25 cells in the last 180 s: sites near recent razings were razed 54 % of the
     * time against 26 % (tower audit, design.md).
     */
    private boolean quietOk(int x, int y) {
        Military m = ai.military();
        return !m.threatNear(x, y, 30) && m.enemyStrengthNear(x, y, 30) == 0f && !razedNear(x, y);
    }

    /** reloc, rearm_reach: a building of ours razed within 25 cells in the last 180 s. */
    private boolean razedNear(int x, int y) {
        for (float[] r : razings_long)
            if (MapAnalysis.dist2(x, y, (int) r[0], (int) r[1]) <= 25 * 25)
                return true;
        return false;
    }

    /** tower_cooldown: an enemy warrior that is not parked (idle and on its default controller) within r cells. */
    private boolean awakeEnemyNear(int x, int y, int r) {
        for (Unit e : ai.intel().enemy_warriors) {
            if (e.isDead() || MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) > r * r)
                continue;
            if (!Intel.isParked(e))
                return true;
        }
        return false;
    }

    private void onCompleted(@NonNull Project p) {
        if (p == expansion_project)
            expansion = p.building;
        if (p.forward && p.building != null)
            forward_towers.add(p.building);
        if (p.sniper && p.building != null)
            sniper_towers.add(p.building);
        if (p.type == Race.BUILDING_ARMORY && p.building != null) {
            had_armory = true;
            Building armory = p.building;
            ai.owner().buildIronWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
            if (ai.owner().canUseRubber())
                ai.owner().buildRubberWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        }
    }

    private @Nullable Site resite(@NonNull Project p) {
        SitePlanner planner = ai.planner();
        List<Site> reserved = reservedSites(p);
        return switch (p.type) {
            case Race.BUILDING_ARMORY -> {
                Site s = planner.findQuartersSiteLike(reserved, p.site.x, p.site.y, 12, Race.BUILDING_ARMORY);
                yield s != null ? s : planner.findArmorySite(reserved);
            }
            case Race.BUILDING_QUARTERS -> planner.findQuartersSiteLike(reserved, p.site.x, p.site.y, 14,
                    Race.BUILDING_QUARTERS);
            default -> planner.findQuartersSiteLike(reserved, p.site.x, p.site.y, 10, Race.BUILDING_TOWER);
        };
    }

    private @NonNull List<@NonNull Site> reservedSites(@Nullable Project except) {
        List<Site> reserved = new ArrayList<>();
        for (Project q : projects)
            if (q != except)
                reserved.add(q.site);
        Intel intel = ai.intel();
        addBuildings(reserved, intel.quarters);
        addBuildings(reserved, intel.armories);
        addBuildings(reserved, intel.towers);
        addBuildings(reserved, intel.quarters_sites);
        addBuildings(reserved, intel.armory_sites);
        addBuildings(reserved, intel.tower_sites);
        addBuildings(reserved, intel.decoy_sites);
        return reserved;
    }

    private static void addBuildings(@NonNull List<@NonNull Site> reserved,
            @NonNull List<@NonNull Building> buildings) {
        for (Building b : buildings)
            reserved.add(new Site(b.getGridX(), b.getGridY(), 0).withHalf(
                    SitePlanner.RaceSizes.of(b.getTemplate().getTemplateID())));
    }

    private @Nullable Unit choosePlacer(@NonNull Project p) {
        if (p.use_scout && scout != null && !scout.isDead()) {
            // The scout places sites in priority order; it only takes the next once the previous one stands.
            for (Project q : projects) {
                if (q == p)
                    break;
                if (q.use_scout && !q.isPlaced() && q.building != null && placerOf(q.building) == scout)
                    return null;
            }
            return scout;
        }
        // reloc_lock: a lock move's placer is chosen by the same rule (lock moves come only late, so it stays late).
        if (p.type == Race.BUILDING_ARMORY && (ai.strategy().rearm_placer || p.lock))
            return safePlacer(p);
        return plainPlacer(p);
    }

    /**
     * The nearest idle, walking or tree-gathering peon (tree gatherers count 40 cells further), or one walking into a
     * building; for an armory with none of those, any builder.
     */
    private @Nullable Unit plainPlacer(@NonNull Project p) {
        Intel intel = ai.intel();
        Unit best = null;
        int best_d = Integer.MAX_VALUE;
        for (Unit peon : intel.peons) {
            PeonState s = intel.peon_states.get(peon);
            if (s != PeonState.IDLE && s != PeonState.TRANSIT && s != PeonState.GATHER_TREE && s != PeonState.MOVE)
                continue;
            int d = MapAnalysis.dist2(peon.getGridX(), peon.getGridY(), p.site.x, p.site.y);
            if (s == PeonState.GATHER_TREE)
                d += 40 * 40;
            if (d < best_d) {
                best_d = d;
                best = peon;
            }
        }
        if (best == null && p.type == Race.BUILDING_ARMORY) {
            for (Unit peon : intel.peons) {
                if (intel.peon_states.get(peon) == PeonState.BUILD) {
                    best = peon;
                    break;
                }
            }
        }
        return best;
    }

    /**
     * rearm_placer: the quarters that deployed a placer, until when that peon is reserved, the next deploy, and the
     * peons already by that quarters then (the deployed one is the peon by it that is not among them).
     */
    private @Nullable Building reserve_quarters;
    private float reserve_until = -1f;
    private float rearm_deploy_next = -1f;
    private final List<@NonNull Unit> reserve_known = new ArrayList<>();
    /**
     * Game ticks a peon deployed for a placer stays reserved, and the cells around its quarters it is looked for in.
     */
    private static final float RESERVE_TICKS = 150f; // 3 s
    private static final int RESERVE_CELLS = 10;

    /**
     * rearm_placer: the placer for an armory project, in this order: the peon a quarters deployed for it (while
     * reserved); the nearest idle, walking or tree-gathering peon (tree gatherers count 40 cells further, as in the old
     * rule); the nearest one walking into a building; each with no threat within 11 cells (evacuatePeons would pull it
     * at once) and no enemy warrior within 12 cells of its straight way to the site. With none, the complete quarters
     * nearest the site with a peon inside, no threat within 12 cells and a clear way deploys one (at most every 5 s),
     * reserved for RESERVE_TICKS, and null is returned meanwhile. With no such quarters: the nearest builder of
     * another site passing the same tests, else the nearest idle, walking, gathering, transit or building peon with no
     * threat within 11 cells and no enemy within 8 cells (an idle enemy's scan) of the way, else (no armory standing,
     * a quarters left) the nearest safe shepherd, which Shepherd.giveUp lets go, else null: the project waits rather
     * than send a placer that is pulled back or killed within seconds (s6001 smoke: 7 in 7 s).
     */
    private @Nullable Unit safePlacer(@NonNull Project p) {
        float now = ai.now();
        Unit u = safePlacerOf(p, now);
        // A lost armory's project (no armory standing, not a hop) starts its REARM_WAIT_TICKS clock while nothing
        // qualifies, and so does any other armory project but a hop or a lock move (those have RELOC_WAIT_TICKS).
        boolean timed = !p.reloc && !p.lock;
        if (u != null || !timed || !nextDue(rearm_deploy_next))
            p.noplacer_since = -1f;
        else if (p.noplacer_since < 0f)
            p.noplacer_since = now;
        if (u == null && !isRebuild(p) && p.noplacer_since >= 0f && now - p.noplacer_since > REARM_WAIT_TICKS) {
            // The first armory or an expansion (considerExpansion) is not left waiting for good: with it unplaced no
            // other expansion is planned. After REARM_WAIT_TICKS it takes a placer by the old rule.
            u = plainPlacer(p);
            if (u != null) {
                p.noplacer_since = -1f;
                ai.aiLog().count("rearm_placer_fallback");
            }
        }
        return u;
    }

    /** rearm_placer: a lost armory's project (no armory standing, not a hop or a lock move). */
    private boolean isRebuild(@NonNull Project p) {
        return had_armory && ai.intel().armories.isEmpty() && !p.reloc && !p.lock;
    }

    /**
     * rearm_placer: game ticks an armory project may find no placer before a lost armory's is planned afresh and any
     * other's (not a hop or a lock move) takes one by the old rule.
     */
    private static final float REARM_WAIT_TICKS = 1500f; // 30 s

    private @Nullable Unit safePlacerOf(@NonNull Project p, float now) {
        Intel intel = ai.intel();
        int sx = p.site.x;
        int sy = p.site.y;
        Building rq = reserve_quarters;
        if (rq != null && (rq.isDead() || now > reserve_until)) {
            reserve_quarters = null;
            rq = null;
        }
        if (rq != null) {
            Unit mine = null;
            int best_d = RESERVE_CELLS * RESERVE_CELLS + 1;
            for (Unit peon : intel.peons) {
                PeonState s = intel.peon_states.get(peon);
                if ((s != PeonState.IDLE && s != PeonState.TRANSIT && s != PeonState.MOVE)
                        || reserve_known.contains(peon))
                    continue;
                int d = MapAnalysis.dist2(peon.getGridX(), peon.getGridY(), rq.getGridX(), rq.getGridY());
                if (d < best_d && safeWay(peon, sx, sy, 12)) {
                    best_d = d;
                    mine = peon;
                }
            }
            if (mine != null) {
                reserve_quarters = null;
                ai.aiLog().count("rearm_placer_reserved");
                return mine;
            }
        }
        // The nearest safe peon: idle, walking or gathering wood first, then those walking into a building.
        Unit safe = nearestSafe(p, EnumSet.of(PeonState.IDLE, PeonState.MOVE, PeonState.GATHER_TREE), 12);
        if (safe == null)
            safe = nearestSafe(p, EnumSet.of(PeonState.TRANSIT), 12);
        if (safe != null) {
            boolean transit = intel.peon_states.get(safe) == PeonState.TRANSIT;
            ai.aiLog().count(transit ? "rearm_placer_transit" : "rearm_placer_safe");
            return safe;
        }
        if (rq != null || !nextDue(rearm_deploy_next)) {
            ai.aiLog().count("rearm_placer_wait"); // economy ticks (1 s)
            return null;
        }
        Building from = deployQuarters(sx, sy);
        if (from != null) {
            ai.owner().deployUnits(from, DeployType.PEON, 1);
            reserve_known.clear();
            for (Unit peon : intel.peons)
                if (MapAnalysis.dist2(peon.getGridX(), peon.getGridY(), from.getGridX(),
                        from.getGridY()) <= RESERVE_CELLS * RESERVE_CELLS)
                    reserve_known.add(peon);
            reserve_quarters = from;
            reserve_until = now + RESERVE_TICKS;
            rearm_deploy_next = now + 250f; // 5 s
            ai.aiLog().count("rearm_placer_deploy");
            if (ai.logging())
                ai.log("armory placer for " + p.describe() + ": one peon out of the quarters at " + from.getGridX() + "," + from.getGridY());
            return null;
        }
        safe = nearestSafe(p, EnumSet.of(PeonState.BUILD), 12);
        if (safe != null) {
            ai.aiLog().count("rearm_placer_build");
            return safe;
        }
        safe = nearestSafe(p, EnumSet.of(PeonState.IDLE, PeonState.MOVE, PeonState.GATHER_TREE, PeonState.GATHER_IRON,
                PeonState.GATHER_ROCK, PeonState.TRANSIT, PeonState.BUILD), 8);
        if (safe != null) {
            ai.aiLog().count("rearm_placer_relaxed");
            return safe;
        }
        // The last resort with no armory standing but a quarters to recover with: a shepherd (stall.md: in every
        // no-placer episode all the peons outside were shepherds, lures or dodgers). Its copy goes unleashed a while.
        if (intel.armories.isEmpty() && !intel.quarters.isEmpty()) {
            Unit shepherd = null;
            int best_d = Integer.MAX_VALUE;
            for (Unit u : intel.shepherds) {
                if (u.isDead() || u.isMounted() || Intel.isStunned(u))
                    continue;
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), sx, sy);
                if (d < best_d && safeWay(u, sx, sy, 12)) {
                    best_d = d;
                    shepherd = u;
                }
            }
            if (shepherd != null && ai.shepherd().giveUp(shepherd)) {
                ai.aiLog().count("rearm_placer_shepherd");
                return shepherd;
            }
        }
        if (!intel.peons.isEmpty()) {
            ai.aiLog().count("rearm_placer_none"); // economy ticks (1 s) with peons but no placer
            if (ai.logging() && nextDue(rearm_none_log)) {
                rearm_none_log = now + 750f; // 15 s
                Map<PeonState, Integer> states = new java.util.EnumMap<>(PeonState.class);
                for (PeonState s : intel.peon_states.values())
                    states.merge(s, 1, Integer::sum);
                StringBuilder q = new StringBuilder();
                for (Building b : intel.quarters)
                    q.append(' ').append(b.getUnitContainer().getNumSupplies()).append(
                            ai.military().threatNearEcon(b.getGridX(), b.getGridY(), 12) ? "t" : "").append(
                                    pathClear(b.getGridX(), b.getGridY(), sx, sy, 12) ? "" : "p");
                ai.aiLog().log("REARM",
                        () -> "no placer for " + p.describe() + ": peons " + states + ", quarters inside" + q + ", shepherds " + intel.shepherds.size());
            }
        }
        return null;
    }

    /** rearm_placer (log only): the next "no placer" log line. */
    private float rearm_none_log;

    /**
     * rearm_placer: the peon in one of the states nearest the project's site (tree gatherers 40 cells further) with no
     * threat within 11 cells and no enemy warrior within way_clear cells of its straight way there, or null.
     */
    private @Nullable Unit nearestSafe(@NonNull Project p, @NonNull EnumSet<PeonState> states, int way_clear) {
        Intel intel = ai.intel();
        int sx = p.site.x;
        int sy = p.site.y;
        List<Unit> candidates = new ArrayList<>();
        List<Integer> dists = new ArrayList<>();
        for (Unit peon : intel.peons) {
            PeonState s = intel.peon_states.get(peon);
            if (s == null || !states.contains(s))
                continue;
            int d = MapAnalysis.dist2(peon.getGridX(), peon.getGridY(), sx, sy);
            if (s == PeonState.GATHER_TREE)
                d += 40 * 40;
            // Insertion by distance: ties keep the list order.
            int i = 0;
            while (i < dists.size() && dists.get(i) <= d)
                i++;
            candidates.add(i, peon);
            dists.add(i, d);
        }
        for (Unit peon : candidates)
            if (safeWay(peon, sx, sy, way_clear))
                return peon;
        return null;
    }

    /**
     * rearm_placer: the complete quarters nearest (x, y) with a peon inside, not being evacuated, no threat within 12
     * cells and no enemy warrior within 12 cells of the way, or null.
     */
    private @Nullable Building deployQuarters(int sx, int sy) {
        Intel intel = ai.intel();
        Building from = null;
        int best_d = Integer.MAX_VALUE;
        for (Building q : intel.quarters) {
            if (q.isDead() || !q.isComplete() || evacuating.containsKey(q) || retire.isDoomed(q)
                    || q.getUnitContainer().getNumSupplies() == 0)
                continue;
            int d = MapAnalysis.dist2(q.getGridX(), q.getGridY(), sx, sy);
            if (d >= best_d || ai.military().threatNearEcon(q.getGridX(), q.getGridY(), 12)
                    || !pathClear(q.getGridX(), q.getGridY(), sx, sy, 12))
                continue;
            best_d = d;
            from = q;
        }
        return from;
    }

    /** rearm_placer: no threat within 11 cells of the peon and no enemy warrior within r cells of its way to (x, y). */
    private boolean safeWay(@NonNull Unit peon, int x, int y, int r) {
        return !ai.military().threatNear(peon.getGridX(), peon.getGridY(), 11)
                && pathClear(peon.getGridX(), peon.getGridY(), x, y, r);
    }

    /**
     * rearm_placer: whether the peon is the one a quarters just deployed as an armory placer (Shepherd, Lures, Dodges,
     * Decoys and the sappers, which run before the economy, leave it alone): within RESERVE_CELLS of that quarters
     * while the reservation lasts, and not one of the peons already by it at the deploy.
     */
    boolean reservedPlacer(@NonNull Unit u) {
        Building q = reserve_quarters;
        return q != null && ai.now() <= reserve_until && !q.isDead()
                && MapAnalysis.dist2(u.getGridX(), u.getGridY(), q.getGridX(),
                        q.getGridY()) <= RESERVE_CELLS * RESERVE_CELLS
                && !reserve_known.contains(u);
    }

    /**
     * rearm_placer: whether Military.evacuatePeons leaves this peon where it is: it carries an armory site it has not
     * placed yet (reloc_lock: a lock move's, with rearm_placer off too), and no threat is within 6 cells of it.
     */
    boolean evacExempt(@NonNull Unit u) {
        Strategy st = ai.strategy();
        if (!st.rearm_placer && st.reloc_lock_ticks <= 0)
            return false;
        Building b = ai.intel().builder_sites.get(u);
        if (b == null || b.isDead() || b.isPlaced() || b.getTemplate().getTemplateID() != Race.BUILDING_ARMORY)
            return false;
        boolean lock = false;
        for (Project p : projects)
            lock |= p.building == b && p.lock;
        if (!st.rearm_placer && !lock)
            return false;
        if (ai.military().threatNear(u.getGridX(), u.getGridY(), 6))
            return false;
        for (Project p : projects)
            if (p.building == b)
                p.evac_skipped = true;
        ai.aiLog().count("rearm_evac_skip"); // military ticks (0.5 s)
        return true;
    }

    /**
     * Whether no enemy warrior stands within r cells of the straight line from (x0, y0) to (x1, y1), looked at every 6
     * cells from the (x1, y1) end, where the enemies that block most ways stand (rearm_placer, reloc).
     */
    private boolean pathClear(int x0, int y0, int x1, int y1, int r) {
        float len = (float) Math.sqrt(MapAnalysis.dist2(x0, y0, x1, y1));
        int steps = Math.max(1, (int) (len / 6f));
        EnemyIndex index = ai.intel().enemyIndex(ai.worldTicks());
        for (int i = steps; i >= 0; i--) {
            int px = x0 + Math.round((x1 - x0) * i / (float) steps);
            int py = y0 + Math.round((y1 - y0) * i / (float) steps);
            int[] found = index.queryUnordered(px, py, r * r);
            for (int k = 0; k < index.count(); k++) {
                int e = found[k];
                if (index.group(e) == EnemyIndex.WARRIOR && !index.unit(e).isDead())
                    return false;
            }
        }
        return true;
    }

    /** How many builders a placed site should have right now. */
    private int buildersWanted(@NonNull Project p) {
        Intel intel = ai.intel();
        Strategy strategy = ai.strategy();
        boolean have_quarters = !intel.quarters.isEmpty();
        boolean have_armory = !intel.armories.isEmpty();
        if (p.first)
            return MAX_BUILDERS;
        if (!have_quarters && hasFirstQuarters())
            return 0; // nothing may take the first quarters' builders
        // More builders than the trees nearby can feed only get in each other's way.
        int trees = ai.map().treesAround(p.site.x, p.site.y, 12);
        int feedable = Math.max(6, 4 * trees);
        int cap = switch (p.type) {
            case Race.BUILDING_ARMORY -> have_armory ? 12 : MAX_BUILDERS;
            case Race.BUILDING_QUARTERS -> armsRace() ? 3 : have_armory ? strategy.quarters_builders : MAX_BUILDERS;
            default -> strategy.tower_builders;
        };
        return Math.min(cap, feedable);
    }

    private boolean hasFirstQuarters() {
        for (Project p : projects)
            if (p.first)
                return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Building plan

    private void planBuildings() {
        if (!planArmory())
            return;
        Building armory = ai.intel().armory();
        planQuarters(armory);
        planTowers(armory);
    }

    /**
     * An armory project when we have none (placed, planned or standing), else the expansion check. Returns false, and
     * nothing else is planned this round, when a lost armory's new site has a threat within 25 cells.
     */
    private boolean planArmory() {
        Intel intel = ai.intel();
        int armory_count = intel.armories.size() + intel.armory_sites.size() + countProjects(Race.BUILDING_ARMORY,
                false);
        if (armory_count == 0) {
            Strategy st = ai.strategy();
            Site site = null;
            boolean quiet = false;
            if (had_armory && st.rearm_reach > 0 && nextDue(rearm_next)) {
                // rearm_reach: the best quiet armory site within reach of the start, by iron and trees.
                rearm_next = ai.now() + 500f; // 10 s
                site = ai.planner().findArmorySite(reservedSites(null), st.rearm_reach, ai.planner().getStartField(),
                        this::quietOk, rearm_search, ai.now());
                quiet = site != null;
                ai.aiLog().count(quiet ? "rearm_site_quiet" : "rearm_site_none");
                if (quiet && ai.logging())
                    ai.log(String.format("rearm: quiet armory site %d,%d, cost %.0f, %d m from the start", site.x,
                            site.y, GauntletAI.seconds(-site.score), ai.planner().getStartField().get(site.x, site.y)));
            }
            // A lost armory goes up again next to the quarters furthest from the fighting, not back where it fell.
            if (site == null)
                site = had_armory ? safeArmorySite() : ai.planner().findArmorySite(reservedSites(null));
            if (!quiet && site != null && ai.military().threatNear(site.x, site.y, 25)) {
                // rearm_placer: a site by another quarters with no threat within 25 cells, rather than none.
                Site alt = had_armory && st.rearm_placer && nextDue(rearm_alt_next) ? calmArmorySite() : null;
                if (alt == null)
                    return false;
                ai.aiLog().count("rearm_site_alt");
                site = alt;
            }
            if (site == null)
                site = ai.planner().findArmorySite(reservedSites(null));
            if (site == null && !intel.peons.isEmpty()) {
                Unit p = intel.peons.getFirst();
                site = ai.planner().findQuartersSiteLike(reservedSites(null), p.getGridX(), p.getGridY(), 40,
                        Race.BUILDING_ARMORY);
            }
            if (site != null) {
                armory_site = site;
                addProject(Race.BUILDING_ARMORY, site, 0);
            }
        } else if (lock_armed && lockGate()) {
            lockRelocate();
        } else if (ai.strategy().reloc && ai.now() >= ai.strategy().reloc_ticks && relocGate()) {
            considerRelocation();
        } else if (armory_count == 1 && intel.armories.size() == 1) {
            considerExpansion();
        }
        return true;
    }

    /** rearm_reach: no quiet rebuild search before this time; rearm_placer: no calm alternative before this time. */
    private float rearm_next = -1f;
    /** rearm_reach, reloc, reloc_lock: each one's armory site search, reused for a minute (SitePlanner.Search). */
    private final SitePlanner.@NonNull Search rearm_search = new SitePlanner.Search();
    private final SitePlanner.@NonNull Search reloc_search = new SitePlanner.Search();
    private final SitePlanner.@NonNull Search lock_search = new SitePlanner.Search();
    private float rearm_alt_next = -1f;

    /**
     * rearm_placer: when the rebuild site has a threat within 25 cells, an armory site within 24 cells of another
     * quarters (the least enemy strength within 40 cells first) with no threat within 25 cells, or null (at most every
     * 10 s after a miss).
     */
    private @Nullable Site calmArmorySite() {
        Military military = ai.military();
        List<Building> quarters = new ArrayList<>();
        for (Building q : ai.intel().quarters)
            if (!q.isDead() && q.isComplete())
                quarters.add(q);
        List<Float> danger = new ArrayList<>();
        for (Building q : quarters)
            danger.add(military.enemyStrengthNear(q.getGridX(), q.getGridY(), 40));
        List<Site> reserved = reservedSites(null);
        SitePlanner.CellOk ok = (x, y) -> !military.threatNear(x, y, 25);
        boolean[] tried = new boolean[quarters.size()];
        for (int n = 0; n < quarters.size(); n++) {
            // The least dangerous quarters not tried yet; ties in list order.
            int k = -1;
            for (int i = 0; i < quarters.size(); i++)
                if (!tried[i] && (k < 0 || danger.get(i) < danger.get(k)))
                    k = i;
            tried[k] = true;
            Building q = quarters.get(k);
            Site s = ai.planner().findQuartersSiteLike(reserved, q.getGridX(), q.getGridY(), 24, Race.BUILDING_ARMORY,
                    ok);
            if (s != null)
                return s;
        }
        rearm_alt_next = ai.now() + 500f; // 10 s
        ai.aiLog().count("rearm_site_wait");
        return null;
    }

    /** A quarters project while we have fewer than the target (initial_quarters, then max_quarters) and none waits. */
    private void planQuarters(@Nullable Building armory) {
        Intel intel = ai.intel();
        Strategy strategy = ai.strategy();
        float time = ai.now();
        int quarters_count = intel.quarters.size() + intel.quarters_sites.size() + countProjects(
                Race.BUILDING_QUARTERS, false);
        int target_quarters = strategy.initial_quarters;
        if (armory != null && time >= strategy.expand_ticks)
            target_quarters = strategy.max_quarters;
        int pop = ai.owner().getUnitCountContainer().getNumSupplies();
        if (pop > ai.owner().getWorld().getMaxUnitCount() * 3 / 4)
            target_quarters = Math.min(target_quarters, intel.quarters.size());
        if (quarters_count < target_quarters && countProjects(Race.BUILDING_QUARTERS, true) == 0
                && (armory != null || intel.quarters.isEmpty()) && time >= quarters_hold_until) {
            int ax = armory != null ? armory.getGridX() : ai.planner().getStartX();
            int ay = armory != null ? armory.getGridY() : ai.planner().getStartY();
            DistanceField field = armory != null ? armoryField() : ai.planner().getStartField();
            Site site = ai.planner().findQuartersSite(reservedSites(null), ax, ay, 90, field,
                    ai.planner().getStartX(), ai.planner().getStartY(), ai.strategy().quarters_builders, 12.5f, 1f);
            if (site != null && strategy.veto_resite_ticks > 0f && strategy.veto_resite_quarters
                    && time >= strategy.veto_resite_from_ticks)
                site = clearAtBirth(site, Race.BUILDING_QUARTERS, "veto_resite_born_quarters");
            if (site != null)
                addProject(Race.BUILDING_QUARTERS, site, 5);
        }
    }

    /**
     * Tower projects up to the target of the hour (towers_early, _mid, _late, plus the front bonus), once the armory
     * and tower_min_quarters finished quarters stand; then the forward and sniper towers.
     */
    private void planTowers(@Nullable Building armory) {
        Intel intel = ai.intel();
        Strategy strategy = ai.strategy();
        float time = ai.now();
        if (armory == null)
            return;
        if (intel.quarters.size() < strategy.tower_min_quarters) {
            ai.aiLog().count("tower_gate_quarters"); // plan ticks the quarters gate keeps towers off (A3)
            return;
        }
        int target_towers = 0;
        if (time >= strategy.towers_early_ticks)
            target_towers = strategy.towers_early;
        if (time >= strategy.towers_mid_ticks)
            target_towers = strategy.towers_mid;
        if (time >= strategy.towers_late_ticks)
            target_towers = strategy.towers_late;
        if (ai.military().baseThreatLevel() > 0 && target_towers < 2)
            target_towers = Math.max(target_towers, 1);
        int enemies = ai.enemiesAlive();
        boolean fronts = enemies > 1 && strategy.multi_front_towers;
        if (fronts && target_towers > 0)
            target_towers += Math.min(enemies - 1, strategy.front_tower_bonus_max);
        if (strategy.tower_cap)
            // Leave room under the building cap for every quarters, two armories and a spare site.
            target_towers = Math.min(target_towers,
                    ai.owner().getWorld().getMaxBuildingCount() - strategy.max_quarters - 3);
        forward_towers.removeIf(Building::isDead);
        sniper_towers.removeIf(Building::isDead);
        int tower_count = intel.towers.size() + intel.tower_sites.size() + countProjects(Race.BUILDING_TOWER,
                false) - forward_towers.size() - countForward() - ai.military().creepTowerCount() - sniper_towers.size() - countSniper();
        int tower_parallel = time >= strategy.tower_parallel_late_ticks ? strategy.tower_parallel_late : strategy.tower_parallel;
        boolean may = tower_count < target_towers && countProjects(Race.BUILDING_TOWER, true) < tower_parallel
                && ai.owner().canBuild(Race.BUILDING_TOWER) && time >= tower_hold_until;
        Project waiter = may ? slotWaiter() : null;
        if (waiter != null) {
            may = false;
            // plan ticks (3 s) a tower would have been planned
            ai.aiLog().count(waiter.lock ? "lock_slot_held" : "reloc_slot_tower_held");
        }
        if (may) {
            List<int[]> existing = existingTowers();
            int[] center = towerAnchor(tower_count);
            int[] face = {ai.planner().getEnemyX(), ai.planner().getEnemyY()};
            float[] live = strategy.tower_face_place ? ai.liveEnemyCenter() : null;
            if (live != null)
                face = new int[]{Math.round(live[0]), Math.round(live[1])};
            int min_cells = 7;
            int max_cells = 15;
            if (fronts && tower_count % 2 == 1) {
                int[][] front = enemyFront(tower_count / 2);
                if (front != null) {
                    center = front[0];
                    face = front[1];
                    live = null;
                    // Front towers further out leave room for decoys in front of them (Decoys).
                    min_cells = ai.strategy().front_tower_min;
                    max_cells = ai.strategy().front_tower_max;
                }
            }
            if (live != null)
                ai.aiLog().count("tower_face_place");
            Site site = ai.planner().findTowerSite(reservedSites(null), center[0], center[1], min_cells, max_cells,
                    existing, face[0], face[1]);
            if (site == null) {
                ai.aiLog().count("tower_nosite"); // plan ticks with room for a tower and no site on its ring
                if (strategy.tower_site_fallback && nextDue(fallback_next)) {
                    site = fallbackTowerSite(center, face, existing);
                    if (site != null) {
                        ai.aiLog().count("tower_site_fallback");
                    } else {
                        ai.aiLog().count("tower_site_fallback_none");
                        fallback_next = time + 750f; // 15 s
                    }
                }
            }
            if (site != null && strategy.veto_resite_ticks > 0f && time >= strategy.veto_resite_from_ticks)
                site = clearAtBirth(site, Race.BUILDING_TOWER, "veto_resite_born");
            if (site != null)
                addProject(Race.BUILDING_TOWER, site, 8);
        }
        planForwardTower();
        planSniper();
    }

    /**
     * veto_resite: a new site that projectMayStart would veto at once (a threat within 16 cells) goes to the nearest
     * clear site, else it stays for the veto path; counts counter or counter_none.
     */
    private @NonNull Site clearAtBirth(@NonNull Site site, int type, @NonNull String counter) {
        if (!ai.military().threatNearEcon(site.x, site.y, 16))
            return site;
        Site clear = clearSite(type, site.x, site.y, null);
        ai.aiLog().count(clear != null ? counter : counter + "_none");
        return clear != null ? clear : site;
    }

    /** Sniper tower projects, placed or not (their sites are in intel.tower_sites until they stand). */
    private int countSniper() {
        int n = 0;
        for (Project q : projects)
            if (q.sniper)
                n++;
        return n;
    }

    /**
     * Sniper towers: waves that razed a building of ours stand idle where it was, 16-45 cells from the rest of the
     * base and out of our towers' reach (STAT pb/pt, play-park9b-s11). Idle units see 8 cells and never answer being
     * hit, while a tower garrison reaches 15.9: a tower 11-15 cells from such a blob, out of every parked enemy's
     * scan, shoots it for free until its owner sends the blob on again. One project at a time.
     */
    private void planSniper() {
        Strategy strategy = ai.strategy();
        if (!strategy.snipers || !ai.periodDue(last_sniper, 100f) || countSniper() > 0) // every 2 s
            return;
        last_sniper = ai.now();
        Player owner = ai.owner();
        if (!owner.canBuild(Race.BUILDING_TOWER)
                || owner.getBuildingCountContainer().getNumSupplies() + 2 >= owner.getWorld().getMaxBuildingCount())
            return;
        Intel intel = ai.intel();
        List<Building> own = intel.finishedBuildings();
        int range2 = strategy.snipe_range * strategy.snipe_range;
        List<Unit> parked = new ArrayList<>();
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead() || !Intel.isParked(e))
                continue;
            for (Building b : own)
                if (!b.isDead() && MapAnalysis.dist2(b.getGridX(), b.getGridY(), e.getGridX(),
                        e.getGridY()) <= range2) {
                            parked.add(e);
                            break;
                        }
        }
        Unit seed = null;
        int best_n = 0;
        for (Unit s : parked) {
            int n = 0;
            for (Unit e : parked)
                if (MapAnalysis.dist2(s.getGridX(), s.getGridY(), e.getGridX(), e.getGridY()) <= 8 * 8)
                    n++;
            if (n > best_n) {
                best_n = n;
                seed = s;
            }
        }
        if (seed == null || best_n < strategy.snipe_min)
            return;
        com.oddlabs.tt.model.BuildingTemplate template = owner.getRace().getBuildingTemplate(Race.BUILDING_TOWER);
        List<Site> reserved = reservedSites(null);
        Building armory = intel.armory();
        int hx = armory != null ? armory.getGridX() : ai.planner().getStartX();
        int hy = armory != null ? armory.getGridY() : ai.planner().getStartY();
        Site best = null;
        float best_score = -Float.MAX_VALUE;
        int[] rejects = new int[4];
        for (int r = 11; r <= 15; r++) {
            for (int a = 0; a < 24; a++) {
                double ang = a * Math.PI / 12;
                int x = seed.getGridX() + (int) Math.round(r * Math.cos(ang));
                int y = seed.getGridY() + (int) Math.round(r * Math.sin(ang));
                if (!ai.map().inside(x, y) || !ai.planner().getStartField().reachable(x, y))
                    continue;
                int reach = 0;
                for (Unit e : parked)
                    if (MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 15 * 15)
                        reach++;
                if (reach < strategy.snipe_min) {
                    rejects[0]++;
                    continue;
                }
                if (!sniperSafe(x, y)) {
                    rejects[1]++;
                    continue;
                }
                if (!ai.map().canPlace(template, x, y)) {
                    rejects[2]++;
                    continue;
                }
                if (SitePlanner.conflicts(reserved, x, y, SitePlanner.RaceSizes.TOWER)) {
                    rejects[3]++;
                    continue;
                }
                float score = reach * 10f - .05f * (float) Math.sqrt(MapAnalysis.dist2(x, y, hx, hy));
                if (score > best_score) {
                    best_score = score;
                    best = new Site(x, y, score);
                }
            }
        }
        if (best == null) {
            int m = 0;
            for (int i = 1; i < 4; i++)
                if (rejects[i] > rejects[m])
                    m = i;
            ai.aiLog().count("sniper_nospot_" + new String[]{"reach", "unsafe", "illegal", "taken"}[m]);
            return;
        }
        Project p = addProject(Race.BUILDING_TOWER, best, 1);
        p.sniper = true;
        ai.aiLog().count("sniper_planned");
        Unit s = seed;
        int n = best_n;
        ai.log("sniper tower for " + n + " parked enemies at " + s.getGridX() + "," + s.getGridY());
    }

    /** No parked enemy within 9 cells (their scan sees 8), no other enemy within 12, no enemy tower within 20. */
    private boolean sniperSafe(int x, int y) {
        Intel intel = ai.intel();
        for (List<Unit> group : List.of(intel.enemy_warriors, intel.enemy_chieftains, intel.enemy_peons))
            for (Unit e : group) {
                if (e.isDead())
                    continue;
                int dx = Math.abs(e.getGridX() - x);
                int dy = Math.abs(e.getGridY() - y);
                if (Intel.isParked(e) ? Math.max(dx, dy) <= 9 : dx * dx + dy * dy <= 12 * 12)
                    return false;
            }
        for (Building t : intel.enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= 20 * 20)
                return false;
        return true;
    }

    /** Keeps the army over the forward tower going up, as long as it can hold the ground. */
    private void escortForward() {
        Military military = ai.military();
        for (Project p : projects) {
            if (p.forward && military.canEscort()) {
                military.escort(p.site.x, p.site.y);
                return;
            }
        }
    }

    /** Forward tower projects, placed or not. */
    private int countForward() {
        int n = 0;
        for (Project p : projects)
            if (p.forward)
                n++;
        return n;
    }

    /** Escorts builders of forward towers, drops them when the army cannot cover them, and plans the next one. */
    private void planForwardTower() {
        Strategy strategy = ai.strategy();
        Military military = ai.military();
        for (Iterator<Project> it = projects.iterator(); it.hasNext();) {
            Project p = it.next();
            if (!p.forward || p.building != null || military.canEscort())
                continue;
            ai.log("dropping " + p.describe() + ": no cover");
            it.remove();
        }
        if (strategy.forward_towers <= 0 || ai.now() < strategy.forward_tower_ticks || countForward() > 0
                || forward_towers.size() >= strategy.forward_towers || !military.canEscort()
                || !ai.owner().canBuild(Race.BUILDING_TOWER))
            return;
        int[] spot = military.forwardTarget();
        if (spot == null)
            return;
        List<int[]> existing = new ArrayList<>();
        for (Building t : ai.intel().towers)
            existing.add(new int[]{t.getGridX(), t.getGridY()});
        Site site = ai.planner().findTowerSite(reservedSites(null), spot[0], spot[1], 3, 6, existing,
                ai.planner().getStartX(), ai.planner().getStartY());
        if (site == null)
            return;
        Project p = addProject(Race.BUILDING_TOWER, site, 3);
        p.forward = true;
    }

    /** An armory site next to the quarters with the fewest enemy warriors around, or null. */
    private @Nullable Site safeArmorySite() {
        Military military = ai.military();
        Building safest = null;
        float least = Float.MAX_VALUE;
        for (Building q : ai.intel().quarters) {
            float danger = military.enemyStrengthNear(q.getGridX(), q.getGridY(), 40);
            if (danger < least) {
                least = danger;
                safest = q;
            }
        }
        if (safest == null)
            return null;
        return ai.planner().findQuartersSiteLike(reservedSites(null), safest.getGridX(), safest.getGridY(), 24,
                Race.BUILDING_ARMORY);
    }

    /**
     * An enemy arming early means an attack is coming before the opening quarters would pay off: once seen, the
     * armory moves ahead of the quarters still waiting for builders.
     */
    private void checkRush() {
        Intel intel = ai.intel();
        if (rush_alert || !ai.strategy().rush_response || !intel.armories.isEmpty()
                || (ai.strategy().rush_opening_only && had_armory))
            return;
        int enemy_quarters = 0;
        boolean enemy_armory = false;
        for (Building b : intel.enemy_buildings) {
            int id = b.getTemplate().getTemplateID();
            if (id == Race.BUILDING_QUARTERS)
                enemy_quarters++;
            // A foundation is no commitment (a booming opening lays one out early too): only a standing armory is.
            else if (id == Race.BUILDING_ARMORY && b.isComplete())
                enemy_armory = true;
        }
        if (intel.enemy_warriors.size() < 6 && !(enemy_armory && enemy_quarters < ai.strategy().rush_quarters))
            return;
        rush_alert = true;
        rush_alert_time = ai.now();
        ai.log("enemy arming early (" + intel.enemy_warriors.size() + " warriors, armory " + enemy_armory + ", " + enemy_quarters + " quarters): armory first");
        for (Project p : projects)
            if (p.type == Race.BUILDING_ARMORY)
                p.priority = 1;
        projects.sort(Comparator.comparingInt(p -> p.priority));
    }

    /**
     * After an early-arming alarm, weapons come before more quarters until our warriors match the enemy's: only a few
     * builders stay on quarters and the quarters let their peons out to gather and arm.
     */
    private boolean armsRace() {
        Strategy strategy = ai.strategy();
        if (!rush_alert || ai.now() > rush_alert_time + strategy.rush_ticks)
            return false;
        return Combat.total(ai.intel().warriors) < 1.2f * Combat.total(ai.intel().enemy_warriors) + 4f;
    }

    /** Early in the game, enemies in the base that our warriors cannot handle. */
    private boolean underPressure() {
        Strategy strategy = ai.strategy();
        Military military = ai.military();
        if (!strategy.pressure_response || ai.now() >= strategy.pressure_ticks || military.baseThreatLevel() == 0)
            return false;
        return Combat.total(ai.intel().warriors) < 1.2f * military.threatStrength() + 4f;
    }

    /**
     * Towers mostly guard the armory (tower_home_anchor: the home armory); every third one covers the quarters nearest
     * the enemy (not tower_q_anchor: the home armory).
     */
    private int @NonNull [] towerAnchor(int tower_count) {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        assert armory != null;
        Strategy strategy = ai.strategy();
        if (tower_count % 3 == 2 && !intel.quarters.isEmpty() && !strategy.tower_q_anchor) {
            Building home = homeArmory(armory);
            ai.aiLog().count("tower_q_home");
            return new int[]{home.getGridX(), home.getGridY()};
        }
        if (tower_count % 3 == 2 && !intel.quarters.isEmpty()) {
            Building exposed = null;
            float best = -1f;
            for (Building q : intel.quarters) {
                float e = ai.planner().exposure(q.getGridX(), q.getGridY());
                if (e > best && MapAnalysis.dist2(q.getGridX(), q.getGridY(), armory.getGridX(),
                        armory.getGridY()) > 20 * 20) {
                    best = e;
                    exposed = q;
                }
            }
            if (exposed != null)
                return new int[]{exposed.getGridX(), exposed.getGridY()};
        }
        if (strategy.tower_home_anchor) {
            Building home = homeArmory(armory);
            if (home != armory) {
                ai.aiLog().count("tower_home_anchor");
                return new int[]{home.getGridX(), home.getGridY()};
            }
        }
        return new int[]{armory.getGridX(), armory.getGridY()};
    }

    /**
     * The home armory (tower_home_anchor, tower_q_anchor): the finished armory nearest our start, which stays the
     * core's armory once a finished expansion becomes the primary one (choosePrimaryArmory).
     */
    private @NonNull Building homeArmory(@NonNull Building primary) {
        Building home = primary;
        int best = Integer.MAX_VALUE;
        int sx = ai.planner().getStartX();
        int sy = ai.planner().getStartY();
        for (Building a : ai.intel().armories) {
            if (a.isDead())
                continue;
            int d = MapAnalysis.dist2(sx, sy, a.getGridX(), a.getGridY());
            if (d < best) {
                best = d;
                home = a;
            }
        }
        return home;
    }

    /**
     * The k-th living enemy in turn (front_order: in slot order, farthest start first, or most base-bound waves
     * first), as {our building nearest to his start, his start}: the building his attacks go for first.
     */
    private int @Nullable [] @Nullable [] enemyFront(int k) {
        List<Player> enemies = new ArrayList<>();
        for (Player p : ai.owner().getWorld().getPlayers())
            if (ai.owner().isEnemy(p) && p.isAlive())
                enemies.add(p);
        if (enemies.isEmpty())
            return null;
        Player enemy = enemies.get(k % enemies.size());
        int order = ai.strategy().front_order;
        if (order != 0 && enemies.size() > 1) {
            int sx = ai.planner().getStartX();
            int sy = ai.planner().getStartY();
            java.util.Comparator<Player> farthest = java.util.Comparator.comparingInt(
                    p -> -MapAnalysis.dist2(sx, sy, UnitGrid.toGridCoordinate(p.getStartX()),
                            UnitGrid.toGridCoordinate(p.getStartY())));
            Shepherd shepherd = ai.shepherd();
            List<Player> sorted = new ArrayList<>(enemies);
            // A stable sort: ties stay in slot order.
            sorted.sort(order == 2 ? java.util.Comparator.<Player>comparingInt(p -> -shepherd.baseWaves(
                    p)).thenComparing(farthest) : farthest);
            Player chosen = sorted.get(k % sorted.size());
            if (chosen != enemy) {
                ai.aiLog().count("front_order");
                if (ai.logging())
                    ai.log("front tower " + k + " faces " + chosen.getPlayerInfo().getName() + " (" + shepherd.baseWaves(
                            chosen) + " base waves), not " + enemy.getPlayerInfo().getName() + " (" + shepherd.baseWaves(
                                    enemy) + ")");
            }
            enemy = chosen;
        }
        int ex = UnitGrid.toGridCoordinate(enemy.getStartX());
        int ey = UnitGrid.toGridCoordinate(enemy.getStartY());
        List<Building> own = ai.intel().homes();
        Building nearest = MapAnalysis.nearest(own, ex, ey);
        if (nearest == null)
            return null;
        return new int[][]{{nearest.getGridX(), nearest.getGridY()}, {ex, ey}};
    }

    /** Projects of a type, optionally only those still unplaced. */
    private int countProjects(int type, boolean unplaced_only) {
        int n = 0;
        for (Project p : projects) {
            if (p.type != type)
                continue;
            if (unplaced_only && p.isPlaced())
                continue;
            if (!unplaced_only && p.isPlaced())
                continue; // placed ones are counted from the intel as sites
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Quarters

    /** Peons a quarters should keep inside right now. */
    int holdFor(@NonNull Building quarters) {
        Strategy strategy = ai.strategy();
        Player owner = ai.owner();
        int pop = owner.getUnitCountContainer().getNumSupplies();
        int max = owner.getWorld().getMaxUnitCount();
        boolean training = quarters.getChieftainContainer() != null && quarters.getChieftainContainer().isTraining();
        // Training takes 40 breed ticks of the trainer, 440 / n^(1/3) s with n inside, and goes on at the unit cap.
        if (training && strategy.chief_topup_any)
            return strategy.hold_chieftain;
        if (pop >= max - 2)
            return 0;
        if (training)
            return strategy.hold_chieftain;
        if (ai.intel().armories.isEmpty() && !ai.intel().quarters.isEmpty() && needsBuilders())
            return Math.min(2, strategy.hold_early);
        int hold;
        if (pop > max * 7 / 10)
            hold = strategy.hold_late;
        else {
            hold = ai.now() < strategy.hold_mid_ticks ? strategy.hold_early : strategy.hold_mid;
            // rush_hold_armory: the cut sends peons out to gather and arm, which needs a finished armory; without one
            // they would stand at the door.
            if (armsRace()) {
                if (!strategy.rush_hold_armory || !ai.intel().armories.isEmpty())
                    hold = Math.min(2, hold);
                else if (hold > 2)
                    rush_hold_waived = true; // counted by manageQuarters
            }
        }
        if (strategy.hold_backlog > 0 && backlog_on && hold > strategy.hold_early) {
            hold = strategy.hold_early;
            ai.aiLog().count("hold_backlog_ticks");
        }
        return hold;
    }

    /**
     * rush_hold_armory: whether a holdFor since manageQuarters began kept a hold the alarm's cut would have lowered.
     */
    private boolean rush_hold_waived;

    /** hold_backlog: ore waits in the main armory for workers; since when. */
    private boolean backlog_on;
    private float backlog_since;

    private boolean needsBuilders() {
        for (Project p : projects)
            if (p.isPlaced() && builderCount(p.building) < buildersWanted(p))
                return true;
        return false;
    }

    /** Against a single enemy, threats pass and hiding is cheap; against several the base is never quiet. */
    private boolean gatherUnderThreat() {
        return ai.strategy().gather_under_threat && (ai.enemiesAlive() > 1 || armsRace() || underPressure()
                || ai.strategy().gather_threat_1v1);
    }

    private void manageQuarters() {
        Intel intel = ai.intel();
        boolean threatened = ai.military().baseThreatLevel() > 1;
        // bank_guard (last tick's state): normal holds release everyone once it is off; raid_evac: not while the main
        // armory is emptied ahead of a wave (its cap is off then), so the reserve does not walk out towards it.
        boolean keep_reserve = !bank_active && !bank_reserve.isEmpty() && raidEvacuating(intel.armory());
        if (!bank_active && !bank_reserve.isEmpty() && !keep_reserve)
            bank_reserve.clear();
        int builders_short = 0;
        if (bank_active)
            for (Project p : projects)
                if (p.isPlaced())
                    builders_short += Math.max(0, buildersWanted(p) - builderCount(p.building));
        rush_hold_waived = false;
        for (Building q : intel.quarters) {
            // retire: a quarters being razed is emptied by Retire.
            if (evacuating.containsKey(q) || retire.isDoomed(q))
                continue;
            int inside = q.getUnitContainer().getNumSupplies();
            int hold = holdFor(q);
            // Peons are safe inside while enemies roam next to the quarters.
            if (threatened && ai.military().threatNearEcon(q.getGridX(), q.getGridY(), gatherUnderThreat() ? 12 : 20))
                continue;
            // bank_guard: the reserve parked here stays, less what the armory has room for again and what placed sites
            // are short of builders.
            int r = bank_active || keep_reserve ? bank_reserve.getOrDefault(q, 0) : 0;
            if (r > 0 && bank_active) {
                int rel = Math.min(r, Math.max(0, bank_room - 4));
                r -= rel;
                bank_room -= rel;
                for (int i = 0; i < rel; i++)
                    ai.aiLog().count("bank_release_room");
                int relb = Math.min(r, builders_short);
                r -= relb;
                builders_short -= relb;
                for (int i = 0; i < relb; i++)
                    ai.aiLog().count("bank_release_builders");
                bank_reserve.put(q, r);
            }
            hold += r;
            // raid_evac: the evacuees an evacuation sent in stay for its window, not straight back out.
            int held = raid_hold.isEmpty() ? 0 : evacueesHeld(q);
            if (held > 0) {
                hold += held;
                if (inside > hold - held)
                    ai.aiLog().count("raid_evac_held"); // economy ticks a quarters kept evacuees above its hold
            }
            if (danger_idle && inside > hold && !needsBuilders()) {
                ai.aiLog().count("hold_danger");
                continue;
            }
            if (inside > hold) {
                ai.owner().deployUnits(q, DeployType.PEON, inside - hold);
                if (backlog_on)
                    for (int i = 0; i < inside - hold; i++)
                        ai.aiLog().count("hold_backlog_deployed");
            }
        }
        if (rush_hold_waived)
            ai.aiLog().count("rush_hold_armory"); // economy ticks (1 s) the alarm's hold cut waited for an armory
    }

    // ------------------------------------------------------------------------------------------------------------
    // Armory

    /** ore_reach: the main armory's walking field out to 2 x ore_reach m, made when the 400 m field has no ore. */
    private @Nullable DistanceField ore_field;
    private @Nullable Building ore_field_owner;
    private float ore_field_time = -5000f;
    /** ore_reach: this plan tick's ore is beyond the 400 m field, so ore gatherers use {@link #ore_field}. */
    private boolean ore_far;
    /** ore_iron_first: when its decision log line was last written (once a minute at most). */
    private float iron_first_log = -5000f;
    /** Game ticks per unit of rock, as the gatherer targets see it (for the stuck check of rock gatherers). */
    private float rock_cycle;

    private @NonNull DistanceField oreField(@NonNull Building armory) {
        if (ore_field == null || ore_field_owner != armory || ai.now() - ore_field_time > 3000f) { // 60 s
            ore_field = ai.map().computeField(armory.getGridX(), armory.getGridY(), 2 * ai.strategy().ore_reach);
            ore_field_owner = armory;
            ore_field_time = ai.now();
        }
        return ore_field;
    }

    private void refreshArmoryField() {
        Building armory = ai.intel().armory();
        if (armory == null)
            return;
        if (armory_field == null || armory_field_owner != armory || ai.now() - armory_field_time > 3000f) { // 60 s
            armory_field = ai.map().computeField(armory.getGridX(), armory.getGridY(), 400);
            armory_field_owner = armory;
            armory_field_time = ai.now();
        }
    }

    /**
     * With several armories, new peons and gatherers go to the newest finished one: it was opened because the
     * supplies around the old one ran low.
     */
    private void choosePrimaryArmory() {
        Intel intel = ai.intel();
        Building primary = null;
        if (expansion != null && !expansion.isDead() && expansion.isComplete()) {
            primary = expansion;
            reloc_primary = null; // reloc: chosen afresh once this one is gone
        } else if (ai.strategy().reloc && ai.now() >= ai.strategy().reloc_ticks)
            primary = relocPrimary();
        // retire: never an armory being razed (Intel.armory falls back to the first one).
        if (primary == null && ai.strategy().retire && !intel.armories.isEmpty()
                && retire.isDoomed(intel.armories.getFirst()))
            for (Building a : intel.armories)
                if (!a.isDead() && !retire.isDoomed(a)) {
                    primary = a;
                    break;
                }
        intel.setPrimaryArmory(primary);
        // quarters_rally: peons a quarters sends out walk into the armory its rally point names; without one they
        // enter the nearest armory, often the drained old one once the expansion is primary.
        Building armory = intel.armory();
        if (ai.strategy().quarters_rally && armory != null && armory != rally_armory) {
            rally_armory = armory;
            for (Building q : intel.quarters)
                if (!q.isDead() && q.isComplete() && !evacuating.containsKey(q))
                    ai.owner().setRallyPoint(q, armory);
        }
    }

    private @Nullable Building rally_armory;

    /** reloc: the primary chosen once the last expansion is gone, since when it is besieged, and the last switch. */
    private @Nullable Building reloc_primary;
    private float reloc_besieged_since = -1f;
    private float reloc_switch_time = -5000f;
    /** reloc: each armory's gathering cost (warriorGatherCost) and when it was worked out, refreshed every 60 s. */
    private final Map<@NonNull Building, float @NonNull []> armory_costs = new LinkedHashMap<>();
    /**
     * reloc: game ticks with a threat within 16 cells before the primary may switch, and the least gap between
     * switches.
     */
    private static final float RELOC_SWITCH_SIEGE_TICKS = 1000f; // 20 s
    private static final float RELOC_SWITCH_GAP_TICKS = 3000f; // 60 s

    /**
     * reloc: with no standing expansion, the primary armory. It is chosen once (pickPrimary) and kept: every switch
     * makes drainSecondary recall all gatherers of the old one (recall_old_gatherers), and threats come and go within
     * seconds. It switches only after it has had a threat within 16 cells for RELOC_SWITCH_SIEGE_TICKS and a candidate
     * has none, at most every RELOC_SWITCH_GAP_TICKS.
     */
    private @Nullable Building relocPrimary() {
        Intel intel = ai.intel();
        float now = ai.now();
        armory_costs.keySet().removeIf(Building::isDead);
        if (intel.armories.isEmpty()) {
            reloc_primary = null;
            return null;
        }
        Building cur = reloc_primary;
        if (cur == null || cur.isDead() || !intel.armories.contains(cur)) {
            reloc_primary = pickPrimary(false);
            reloc_besieged_since = -1f;
            reloc_switch_time = now;
            if (intel.armories.size() > 1) {
                ai.aiLog().count("reloc_primary_pick");
                Building b = reloc_primary;
                if (b != null && ai.logging())
                    ai.log("reloc: primary armory now " + b.getGridX() + "," + b.getGridY() + " of " + intel.armories.size());
            }
            return reloc_primary;
        }
        Military m = ai.military();
        boolean besieged = m.threatNear(cur.getGridX(), cur.getGridY(), 16);
        reloc_besieged_since = !besieged ? -1f : reloc_besieged_since < 0f ? now : reloc_besieged_since;
        if (besieged && intel.armories.size() > 1 && now - reloc_besieged_since >= RELOC_SWITCH_SIEGE_TICKS
                && ai.periodDue(reloc_switch_time, RELOC_SWITCH_GAP_TICKS)) {
            Building alt = pickPrimary(true);
            if (alt != null && alt != cur) {
                reloc_primary = alt;
                reloc_besieged_since = -1f;
                reloc_switch_time = now;
                ai.aiLog().count("reloc_primary_switch");
                if (ai.logging())
                    ai.log("reloc: primary armory switches from " + cur.getGridX() + "," + cur.getGridY() + " (besieged) to " + alt.getGridX() + "," + alt.getGridY());
            }
        }
        return reloc_primary;
    }

    /**
     * reloc: the finished armory with no threat within 16 cells and the lowest gathering cost, the newest on ties; with
     * none unthreatened, the cheapest of all unless quiet_only (then null).
     */
    private @Nullable Building pickPrimary(boolean quiet_only) {
        Military m = ai.military();
        if (ai.intel().armories.size() == 1 && !quiet_only)
            return ai.intel().armories.getFirst();
        Building best = null;
        float best_cost = Float.MAX_VALUE;
        Building any = null;
        float any_cost = Float.MAX_VALUE;
        for (Building a : ai.intel().armories) {
            if (a.isDead() || retire.isDoomed(a))
                continue;
            float cost = armoryCost(a);
            if (cost <= any_cost) {
                any_cost = cost;
                any = a;
            }
            if (m.threatNear(a.getGridX(), a.getGridY(), 16))
                continue;
            if (cost <= best_cost) {
                best_cost = cost;
                best = a;
            }
        }
        return best != null || quiet_only ? best : any;
    }

    /** reloc: an armory's warriorGatherCost, worked out again when older than 60 s. */
    private float armoryCost(@NonNull Building a) {
        float[] c = armory_costs.get(a);
        if (c == null || ai.now() - c[1] > 3000f) { // 60 s
            c = new float[]{ai.planner().warriorGatherCost(ai.map().computeField(a.getGridX(), a.getGridY(),
                    220)), ai.now()};
            armory_costs.put(a, c);
        }
        return c[0];
    }

    // rock_stream: measured gatherer-ticks per unit of iron and rock over the last 30 s.
    private float meas_start = -1f;
    /** The game tick of the last sample (-1: none yet). */
    private float meas_last = -1f;
    private int meas_iron0;
    private int meas_rock0;
    private float meas_iron_gt;
    private float meas_rock_gt;
    private float iron_ticks = 0f;
    private float rock_ticks = 0f;
    private boolean rock_stream_on;

    /**
     * Every economy tick: gatherer-ticks on iron and rock, turned into game ticks per unit every 30 s. Each sample
     * counts the game ticks since the last one (50 at normal speed, the first one an economy tick's 50), so that the
     * sum does not depend on how the economy ticks fall on world ticks.
     */
    private void measureYield() {
        Player owner = ai.owner();
        float now = ai.now();
        if (meas_start < 0f) {
            meas_start = now;
            meas_iron0 = owner.getIronHarvested();
            meas_rock0 = owner.getRockHarvested();
        }
        float dt = meas_last < 0f ? 50f : now - meas_last;
        meas_last = now;
        Intel intel = ai.intel();
        meas_iron_gt += dt * intel.countPeons(PeonState.GATHER_IRON);
        meas_rock_gt += dt * intel.countPeons(PeonState.GATHER_ROCK);
        if (!ai.periodDue(meas_start, 1500f)) // 30 s
            return;
        int di = owner.getIronHarvested() - meas_iron0;
        iron_rate = .5f * iron_rate + .5f * di / Math.max(50f, now - meas_start);
        int dr = owner.getRockHarvested() - meas_rock0;
        if (meas_iron_gt >= 3000f) // 60 gatherer-seconds
            iron_ticks = meas_iron_gt / Math.max(.5f, di);
        if (meas_rock_gt >= 3000f)
            rock_ticks = meas_rock_gt / Math.max(.5f, dr);
        meas_start = now;
        meas_iron0 = owner.getIronHarvested();
        meas_rock0 = owner.getRockHarvested();
        meas_iron_gt = 0f;
        meas_rock_gt = 0f;
    }

    /**
     * Opens a second armory next to fresh iron once the first one's surroundings are mined out, when the walk saved
     * on every future warrior is worth the forty pieces of wood. Only one expansion at a time; the old armory keeps
     * turning its remaining stock into weapons and then sends its peons over.
     */
    private void considerExpansion() {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        // from 300 s, every 30 s
        if (armory == null || armory_field == null || ai.now() < 15000f || !ai.periodDue(last_expansion_check, 1500f)
                || !ai.strategy().expansion)
            return;
        boolean under_threat = ai.strategy().expand_under_threat;
        if (under_threat)
            last_expansion_check = ai.now();
        if ((!under_threat && ai.military().baseThreatLevel() > 0) || countProjects(Race.BUILDING_ARMORY, false) > 0)
            return;
        if (!ai.owner().canBuild(Race.BUILDING_ARMORY))
            return;
        last_expansion_check = ai.now();
        float current = ai.planner().warriorGatherCost(armory_field);
        if (iron_cycle < 3500f && current < 5500f) // 70 s, 110 s
            return;
        Site site = ai.planner().findExpansionSite(reservedSites(null), armory_field);
        float better = Float.MAX_VALUE;
        if (site != null)
            better = ai.planner().warriorGatherCost(ai.map().computeField(site.x, site.y, 220));
        if (ai.strategy().far_expansion && better > .75f * current) {
            // The whole neighbourhood is mined out: fresh iron further away pays for the walk.
            Site far = ai.planner().findFarExpansionSite(reservedSites(null));
            if (far != null && -far.score < Math.min(better, .75f * current)) {
                site = far;
                better = -far.score;
            }
        }
        if (site == null)
            return;
        // retire: not where a building of ours was just razed to free a slot.
        if (retire.retiredNear(site.x, site.y)) {
            ai.aiLog().count("retire_site_skip");
            return;
        }
        ai.log(String.format("expansion check: current armory %.0f (iron %.0fs), best site %d,%d %.0f",
                GauntletAI.seconds(current), GauntletAI.seconds(iron_cycle), site.x, site.y,
                GauntletAI.seconds(better)));
        float gain = iron_cycle >= ai.strategy().desperate_iron_cycle_ticks ? ai.strategy().desperate_expansion : .75f;
        if (better > gain * current)
            return;
        if (under_threat && ai.military().baseThreatLevel() > 0) {
            if (!quietSite(armory, site.x, site.y)) {
                ai.aiLog().count("exp_blocked_site");
                return;
            }
            ai.aiLog().count("exp_under_threat");
        }
        Project p = addProject(Race.BUILDING_ARMORY, site, 1);
        expansion_project = p;
    }

    /** expand_under_threat: no threat or enemy warrior within 30 cells of the site, none within 12 of the way there. */
    private boolean quietSite(@NonNull Building armory, int x, int y) {
        Military military = ai.military();
        if (military.threatNear(x, y, 30) || military.enemyStrengthNear(x, y, 30) > 0f)
            return false;
        int ax = armory.getGridX();
        int ay = armory.getGridY();
        float len = (float) Math.sqrt(MapAnalysis.dist2(ax, ay, x, y));
        int steps = Math.max(1, (int) (len / 6f));
        for (int i = 0; i <= steps; i++) {
            int px = ax + Math.round((x - ax) * i / (float) steps);
            int py = ay + Math.round((y - ay) * i / (float) steps);
            for (Unit e : ai.intel().enemy_warriors)
                if (!e.isDead() && MapAnalysis.dist2(px, py, e.getGridX(), e.getGridY()) <= 12 * 12)
                    return false;
        }
        return true;
    }

    /** reloc: no relocation check before this time (every 30 s once one runs). */
    private float reloc_next = -1f;
    /** reloc: when the last expansion project ended (completed or dropped), and the one that did. */
    private float last_reloc_end = -50000f;
    private @Nullable Project reloc_ended;
    /** reloc: the last hop armory completed, when, and our iron harvested by then (for its log line). */
    private @Nullable Building reloc_armory;
    private float reloc_armory_done;
    private int reloc_armory_iron;
    /** reloc: whether each other armory was drained at the last gate test (drained() flicker counter). */
    private final Map<@NonNull Building, Boolean> was_drained = new LinkedHashMap<>();
    /**
     * reloc, reloc_lock: game ticks a hop project may wait unplaced with no placer out before it is dropped (with
     * reloc_slot, and a lock move: game ticks with a slot open).
     */
    private static final float RELOC_WAIT_TICKS = 4500f; // 90 s

    /**
     * reloc: whether planArmory may run the relocation check: no armory site or armory project, a primary armory, and
     * every other armory drained. A drained home armory kept the two-armory gate shut for 66 % of the 8-13-min plan
     * ticks (stall.md).
     */
    private boolean relocGate() {
        Intel intel = ai.intel();
        Building primary = intel.armory();
        if (primary == null || !intel.armory_sites.isEmpty() || hasArmoryProject())
            return false;
        was_drained.keySet().removeIf(Building::isDead);
        boolean all = true;
        for (Building a : intel.armories) {
            if (a == primary || a.isDead())
                continue;
            boolean d = drained(a);
            Boolean before = was_drained.put(a, d);
            if (before != null && before && !d)
                ai.aiLog().count("reloc_drained_flicker");
            all &= d;
        }
        if (!all)
            ai.aiLog().count("reloc_gate_undrained"); // plan ticks (3 s)
        return all;
    }

    /**
     * reloc: an armory that is not the primary and has nothing left to give: nobody inside, at most one iron or rock in
     * stock, no gatherers linked to it, and not being evacuated.
     */
    private boolean drained(@NonNull Building a) {
        Intel intel = ai.intel();
        if (a == intel.armory() || evacuating.containsKey(a) || a.getUnitContainer().getNumSupplies() > 0
                || stock(a, IronSupply.class) + stock(a, RockSupply.class) > 1)
            return false;
        for (PeonState s : new PeonState[]{PeonState.GATHER_TREE, PeonState.GATHER_IRON, PeonState.GATHER_ROCK, PeonState.GATHER_CHICKEN})
            if (intel.countLinkedGatherers(s, a) > 0)
                return false;
        return true;
    }

    /** Live iron nodes within r cells of (x, y) (reloc_nodes: the AI sees only whether a node is empty). */
    private int liveIronNear(int x, int y, int r) {
        int n = 0;
        for (IronSupply s : ai.map().getIron())
            if (!s.isEmpty() && MapAnalysis.dist2(x, y, s.getGridX(), s.getGridY()) <= r * r)
                n++;
        return n;
    }

    /**
     * reloc: the hop. Every 30 s and reloc_gap_ticks after the last expansion ended, when the primary is poor (the
     * expansion rule: iron cycle >= 70 s or cost >= 110 s) or has fewer than reloc_nodes live iron nodes within 30
     * cells, a new armory goes to the best site within reloc_reach m of the primary that is at least 40 cells from it
     * (tested in SitePlanner's pass over every cell, so the candidates by the primary, which the walk term ranks first,
     * take none of its 200 rejections), quiet (quietOk), with at least reloc_nodes live iron nodes within 30 cells (a
     * hop is for a fresh pile: the verify smokes accepted sites with 0 nodes against 0 on the cost ratio alone) and no
     * enemy warrior within 12 cells of the straight way there, if it costs at most 0.75 of the current armory
     * (desperate_expansion from desperate_iron_cycle_ticks). No global threat gate: it blocked 100 % of the checks
     * after 13 min at N=14 while the cost test passed in 92-95 % (stall.md). At the building cap it waits, or with
     * reloc_slot is planned anyway and the next slot kept for it.
     */
    private void considerRelocation() {
        Strategy st = ai.strategy();
        float now = ai.now();
        Building primary = ai.intel().armory();
        if (primary == null || armory_field == null || !nextDue(reloc_next)
                || now - last_reloc_end < st.reloc_gap_ticks)
            return;
        boolean capped = !ai.owner().canBuild(Race.BUILDING_ARMORY);
        if (capped && !st.reloc_slot) {
            ai.aiLog().count("reloc_cap_blocked"); // plan ticks (3 s)
            return;
        }
        // The iron cycle is worked out after planBuildings: right after a primary switch it is still the old one's.
        if (iron_cycle_armory != primary) {
            ai.aiLog().count("reloc_cycle_stale"); // plan ticks (3 s) the check waits for the new primary's cycle
            return;
        }
        reloc_next = now + 1500f; // 30 s
        ai.aiLog().count("reloc_check");
        int ax = primary.getGridX();
        int ay = primary.getGridY();
        float current = ai.planner().warriorGatherCost(armory_field);
        int nodes = liveIronNear(ax, ay, 30);
        boolean econ = iron_cycle >= 3500f || current >= 5500f; // 70 s, 110 s
        boolean few = st.reloc_nodes > 0 && nodes < st.reloc_nodes;
        if (!econ && !few) {
            ai.aiLog().count("reloc_not_bad");
            return;
        }
        DistanceField from = st.reloc_reach <= 400 ? armory_field : ai.map().computeField(ax, ay, st.reloc_reach);
        Military m = ai.military();
        // reloc_draw: where each copy's next wave starts from, and how far our nearest building is from there.
        List<int[]> origins = drawOrigins();
        // Why candidates were turned down: enemies by the site, a razing by it, too few live iron nodes by it, the way
        // there, and (reloc_draw) the waves it would draw.
        int[] why = new int[5];
        SitePlanner.CellOk ok = (x, y) -> {
            if (m.threatNear(x, y, 30) || m.enemyStrengthNear(x, y, 30) > 0f) {
                why[0]++;
                return false;
            }
            if (razedNear(x, y) || retire.retiredNear(x, y)) {
                why[1]++;
                return false;
            }
            if (st.reloc_nodes > 0 && liveIronNear(x, y, 30) < st.reloc_nodes) {
                why[2]++;
                return false;
            }
            if (st.reloc_draw > 0 && drawOf(origins, x, y) >= st.reloc_draw) {
                why[4]++;
                return false;
            }
            if (!pathClear(ax, ay, x, y, 12)) {
                why[3]++;
                return false;
            }
            return true;
        };
        Site site = ai.planner().findArmorySite(reservedSites(null), st.reloc_reach, from, 40, ok, 0f, reloc_search,
                now);
        if (ai.planner().lastSearchGaveUp())
            ai.aiLog().count("reloc_search_gaveup"); // searches that stopped at 200 rejected candidates
        if (site == null) {
            int k = 0;
            for (int i = 1; i < why.length; i++)
                if (why[i] > why[k])
                    k = i;
            ai.aiLog().count("reloc_nosite_" + new String[]{"enemy", "razed", "nodes", "path", "draw"}[k]);
            if (why[4] > 0)
                ai.aiLog().count("reloc_draw_nosite"); // checks with no site where draw turned some down
            if (ai.logging())
                ai.log(String.format(
                        "reloc check: armory %d,%d, no quiet site (turned down: enemy %d, razed %d, nodes %d, path %d, draw %d; %d copies with an idle warrior)",
                        ax, ay, why[0], why[1], why[2], why[3], why[4], origins.size()));
            return;
        }
        if (why[4] > 0)
            ai.aiLog().count("reloc_draw_filtered"); // checks where draw turned down a better site
        float[] known = ai.planner().searched(reloc_search, site.x, site.y);
        float better = known != null ? known[0] : ai.planner().warriorGatherCost(ai.map().computeField(site.x, site.y,
                220));
        float gain = iron_cycle >= st.desperate_iron_cycle_ticks ? st.desperate_expansion : .75f;
        int dist = (int) Math.sqrt(MapAnalysis.dist2(ax, ay, site.x, site.y));
        int site_nodes = liveIronNear(site.x, site.y, 30);
        int draw = drawOf(origins, site.x, site.y);
        if (ai.logging())
            ai.log(String.format(
                    "reloc check: armory %d,%d cost %.0f (iron %.0fs, %d nodes within 30), quiet site %d,%d cost %.0f (%d nodes, %d cells, draw %d of %d copies), gain %.2f%s",
                    ax, ay, GauntletAI.seconds(current), GauntletAI.seconds(iron_cycle), nodes, site.x, site.y,
                    GauntletAI.seconds(better), site_nodes, dist, draw, origins.size(), gain,
                    capped ? ", capped" : ""));
        if (better > gain * current) {
            ai.aiLog().count("reloc_not_better");
            return;
        }
        Project p = addProject(Race.BUILDING_ARMORY, site, 1);
        p.reloc = true;
        p.added = now;
        expansion_project = p;
        ai.aiLog().count("reloc_placed");
        if (econ)
            ai.aiLog().count("reloc_trig_econ");
        if (few)
            ai.aiLog().count("reloc_trig_nodes");
        if (capped)
            ai.aiLog().count("reloc_slot_project");
        // reloc_draw (log and counters even when off): copies whose next wave this hop would draw.
        for (int i = 0; i < draw; i++)
            ai.aiLog().count("reloc_placed_draw");
        if (draw == 0)
            ai.aiLog().count("reloc_placed_draw0");
        ai.aiLog().log("RELOC", () -> String.format(
                "hop to %d,%d: cost %.0f vs %.0f, %d vs %d nodes within 30, %d cells, draw %d of %d copies%s", site.x,
                site.y, GauntletAI.seconds(better), GauntletAI.seconds(current), site_nodes, nodes, dist, draw,
                origins.size(), capped ? ", waiting for a slot" : ""));
    }

    /**
     * reloc_draw: {x, y, squared cells to our nearest building} of each copy's oldest idle warrior, where its next
     * wave is aimed from: at our building nearest that warrior, or one of our units nearer than 0.707 of it
     * (AdvancedAI.findTarget). intel.enemy_warriors runs copy by copy in each one's unit order, so a copy's first idle
     * warrior there is its oldest (as Shepherd.oldestIdleWarrior). Copies with no idle warrior are left out.
     */
    private @NonNull List<int @NonNull []> drawOrigins() {
        Intel intel = ai.intel();
        List<int[]> out = new ArrayList<>();
        List<Player> seen = new ArrayList<>();
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead() || !(e.getPrimaryController() instanceof IdleController) || seen.contains(e.getOwner()))
                continue;
            seen.add(e.getOwner());
            int x = e.getGridX();
            int y = e.getGridY();
            int best = Integer.MAX_VALUE;
            for (List<Building> group : List.of(intel.quarters, intel.armories, intel.towers, intel.quarters_sites,
                    intel.armory_sites, intel.tower_sites, intel.decoy_sites))
                for (Building b : group)
                    if (!b.isDead())
                        best = Math.min(best, MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y));
            out.add(new int[]{x, y, best});
        }
        return out;
    }

    /** reloc_draw: the copies whose next wave a building at (x, y) would draw, being nearer than all of ours. */
    private static int drawOf(@NonNull List<int @NonNull []> origins, int x, int y) {
        int n = 0;
        for (int[] o : origins)
            if (MapAnalysis.dist2(x, y, o[0], o[1]) < o[2])
                n++;
        return n;
    }

    /**
     * reloc, reloc_lock, every economy tick after manageProjects: notes when the expansion project ends (completed or
     * dropped: reloc_gap_ticks counts from then), drops a lock move, or a hop under reloc_slot, whose slot has stood
     * open RELOC_WAIT_TICKS with no placer out (its quiet site went bad; at the cap it waits for a slot, the reserve's
     * or retire's: a clock running from planning dropped hops while retire razed a building for them), a hop without
     * reloc_slot RELOC_WAIT_TICKS after planning, and follows the last hop and lock armories to their razing.
     */
    private void watchReloc() {
        float now = ai.now();
        Project p = expansion_project;
        if (p != null && p.reloc && !ai.strategy().reloc_slot && projects.contains(p) && p.building == null
                && now - p.added > RELOC_WAIT_TICKS) {
            projects.remove(p);
            ai.aiLog().count("reloc_stale_drop");
            ai.log("reloc: drop " + p.describe() + ", unplaced for " + (int) GauntletAI.seconds(now - p.added) + "s");
        }
        if (p != null && (p.lock || (p.reloc && ai.strategy().reloc_slot)) && projects.contains(p)
                && p.building == null) {
            p.open_since = !ai.owner().canBuild(Race.BUILDING_ARMORY) ? -1f : p.open_since < 0f ? now : p.open_since;
            if (p.open_since >= 0f && now - p.open_since > RELOC_WAIT_TICKS) {
                projects.remove(p);
                ai.aiLog().count(p.lock ? "lock_stale_drop" : "reloc_stale_drop");
                ai.aiLog().log(p.lock ? "LOCK" : "RELOC",
                        () -> "drop " + p.describe() + ": no placer out with a slot open for " + (int) GauntletAI.seconds(
                                now - p.open_since) + "s, " + (int) GauntletAI.seconds(
                                        now - p.added) + "s after planning");
            }
        }
        if (p != null && p != reloc_ended && !projects.contains(p) && p.lock) {
            reloc_ended = p;
            last_reloc_end = now;
            Building b = p.building;
            if (b != null && !b.isDead() && b.isComplete()) {
                // The lock is over: the new armory, now primary, stands by trees.
                lock_armed = false;
                lock_since = -1f;
                lock_at = null;
                lock_new = b;
                lock_armory = b;
                lock_armory_done = now;
                lock_armory_iron = ai.owner().getIronHarvested();
                ai.aiLog().count("lock_completed");
                Building old = lock_old;
                ai.aiLog().log("LOCK",
                        () -> "lock armory at " + b.getGridX() + "," + b.getGridY() + " completed " + (int) GauntletAI.seconds(
                                now - p.added) + "s after planning" + (old != null
                                        && !old.isDead() ? ", " + old.getUnitContainer().getNumSupplies() + " workers in the locked armory" : ""));
            } else {
                String why = b != null && b.isDead() ? "razed" : p.failures > 6 ? "placer" : "other";
                lock_old = null;
                ai.aiLog().count("lock_dropped");
                ai.aiLog().count("lock_dropped_" + why);
                ai.aiLog().log("LOCK",
                        () -> "lock project " + p.describe() + " dropped (" + why + ", " + p.failures + " placer failures, " + (int) GauntletAI.seconds(
                                now - p.added) + "s after planning)");
            }
        }
        Building la = lock_armory;
        if (la != null && la.isDead()) {
            lock_armory = null;
            float life = now - lock_armory_done;
            int iron = ai.owner().getIronHarvested() - lock_armory_iron;
            ai.aiLog().count("lock_lost");
            if (life < 15000f) // 300 s
                ai.aiLog().count("lock_lost_early");
            ai.aiLog().log("LOCK",
                    () -> "lock armory at " + la.getGridX() + "," + la.getGridY() + " razed after " + (int) GauntletAI.seconds(
                            life) + "s, iron +" + iron + " meanwhile");
        }
        if (p != null && p != reloc_ended && !projects.contains(p)) {
            reloc_ended = p;
            last_reloc_end = now;
            Building b = p.building;
            boolean done = b != null && !b.isDead() && b.isComplete();
            if (p.reloc && done) {
                reloc_armory = b;
                reloc_armory_done = now;
                reloc_armory_iron = ai.owner().getIronHarvested();
                ai.aiLog().count("reloc_completed");
                ai.log("reloc: hop armory at " + b.getGridX() + "," + b.getGridY() + " completed");
            } else if (p.reloc) {
                // Why: its site razed, its placers lost (manageProjects gives up after 6), or waited too long.
                String why = b != null && b.isDead() ? "razed" : p.failures > 6 ? "placer" : "other";
                ai.aiLog().count("reloc_dropped");
                ai.aiLog().count("reloc_dropped_" + why);
                ai.aiLog().log("RELOC",
                        () -> "hop project " + p.describe() + " dropped (" + why + ", " + p.failures + " placer failures, " + (int) GauntletAI.seconds(
                                now - p.added) + "s after planning)");
            }
        }
        Building a = reloc_armory;
        if (a != null && a.isDead()) {
            reloc_armory = null;
            float life = now - reloc_armory_done;
            int iron = ai.owner().getIronHarvested() - reloc_armory_iron;
            ai.aiLog().count("reloc_lost");
            if (life < 9000f) // 180 s
                ai.aiLog().count("reloc_lost_early");
            ai.aiLog().log("RELOC",
                    () -> "hop armory at " + a.getGridX() + "," + a.getGridY() + " razed after " + (int) GauntletAI.seconds(
                            life) + "s, iron +" + iron + " meanwhile");
        }
    }

    /** reloc_lock: since when the lock has held at which armory, and whether it has held reloc_lock_ticks (armed). */
    private float lock_since = -1f;
    private @Nullable Building lock_at;
    private boolean lock_armed;
    /** reloc_lock: no lock site search before this time (after a miss). */
    private float lock_next = -1f;
    /** reloc_lock: the locked armory whose workers wait for the new one, and the new one once complete. */
    private @Nullable Building lock_old;
    private @Nullable Building lock_new;
    /** reloc_lock: the last lock armory completed, when, and our iron harvested by then (for its log line). */
    private @Nullable Building lock_armory;
    private float lock_armory_done;
    private int lock_armory_iron;
    /**
     * reloc_lock: a lock site's own tree cycle must be under this (game ticks); the lock's is at the 60-cell ceiling,
     * ~91 s.
     */
    private static final float LOCK_SITE_TREE_TICKS = 3000f; // 60 s
    private static final float LOCK_TREE_TICKS = 4500f; // 90 s
    /** reloc_lock: the most game ticks the locked armory keeps its workers once the new one stands (lockHold). */
    private static final float LOCK_HOLD_TICKS = 6000f; // 120 s
    /** reloc_lock: game ticks between lock site searches after a miss (each evaluates up to 24 sites). */
    private static final float LOCK_RETRY_TICKS = 500f; // 10 s

    /**
     * reloc_lock, every plan tick after the gather targets (armory null: none standing): the wood lock of judge fix 3
     * holds while, from reloc_lock_from_ticks, the main armory's tree cycle is at least LOCK_TREE_TICKS (no usable tree
     * in its 60-cell ring: cut, or filtered out by threats and stuck bans), its wood below 2 and its workers at least
     * want_workers + 20; it is armed once it has held reloc_lock_ticks at the same armory.
     */
    private void trackLock(@Nullable Building armory) {
        Strategy st = ai.strategy();
        float now = ai.now();
        boolean locked = armory != null && now >= st.reloc_lock_from_ticks && armory.isComplete()
                && tree_cycle >= LOCK_TREE_TICKS && stock(armory, TreeSupply.class) < 2
                && armory.getUnitContainer().getNumSupplies() >= want_workers + 20;
        if (!locked || armory != lock_at) {
            if (lock_armed) {
                float held = now - lock_since;
                ai.aiLog().log("LOCK", () -> String.format("wood lock over after %.0f s", GauntletAI.seconds(held)));
            }
            lock_armed = false;
            lock_since = locked ? now : -1f;
            lock_at = locked ? armory : null;
            return;
        }
        if (!lock_armed && now - lock_since >= st.reloc_lock_ticks) {
            lock_armed = true;
            ai.aiLog().count("lock_armed");
            int workers = armory.getUnitContainer().getNumSupplies();
            int wood = stock(armory, TreeSupply.class);
            int iron = stock(armory, IronSupply.class);
            ai.aiLog().log("LOCK", () -> String.format(
                    "wood lock at the armory at %d,%d for %.0f s: tree cycle %.0f s, wood %d, iron %d, workers %d (want %d)",
                    armory.getGridX(), armory.getGridY(), GauntletAI.seconds(now - lock_since),
                    GauntletAI.seconds(tree_cycle), wood, iron, workers, want_workers));
        }
        if (lock_armed)
            ai.aiLog().count("lock_ticks"); // plan ticks (3 s) armed
    }

    /**
     * reloc_lock: a lock move may be planned: the main armory is the locked one (not a lock armory just completed,
     * before the next trackLock sees it), and no armory site or armory project stands.
     */
    private boolean lockGate() {
        Intel intel = ai.intel();
        return intel.armory() != null && intel.armory() == lock_at && intel.armory_sites.isEmpty()
                && !hasArmoryProject();
    }

    /**
     * reloc, reloc_lock: an armory project in any state. A site that has just finished leaves armory_sites on the plan
     * tick before manageProjects (an economy tick) takes its project off and makes it primary, so neither
     * armory_sites nor the unplaced projects show it then (s6415 smoke: a second lock move planned that very tick).
     */
    private boolean hasArmoryProject() {
        for (Project q : projects)
            if (q.type == Race.BUILDING_ARMORY)
                return true;
        return false;
    }

    /**
     * reloc_lock: while the lock is armed, the armory moves to trees: to the best armory site by gathering cost within
     * reloc_reach m of the locked armory (SitePlanner.findArmorySite; the cost is 2 x tree + iron: the banked iron
     * stays behind, so the new armory needs fresh iron too) that is quiet (quietOk), not by a building retire just
     * razed and has no enemy warrior within 12 cells of the straight way there, tested lazily down the ranked
     * candidates (testing only the 5 cheapest sites, which lie within ~15 cells of each other, found all 5 not quiet in
     * 20 of 26 checks of an s6415 lock game), and whose own tree cycle is under LOCK_SITE_TREE_TICKS. No global threat
     * gate and no 30-s cadence (LOCK_RETRY_TICKS after a miss; the search is reused for a minute). The project is added
     * at the cap too (its placer waits in projectMayStart, the slot is kept from towers by slotWaiter, and retire may
     * free one).
     */
    private void lockRelocate() {
        Strategy st = ai.strategy();
        float now = ai.now();
        Building primary = ai.intel().armory();
        if (primary == null || armory_field == null || !nextDue(lock_next))
            return;
        lock_next = now + LOCK_RETRY_TICKS;
        ai.aiLog().count("lock_check");
        int ax = primary.getGridX();
        int ay = primary.getGridY();
        DistanceField from = st.reloc_reach <= 400 ? armory_field : ai.map().computeField(ax, ay, st.reloc_reach);
        // Why candidates were turned down: enemies or a razing by it, the way there (and, from the search, trees).
        int[] why = new int[2];
        SitePlanner.CellOk ok = (x, y) -> {
            if (!quietOk(x, y) || retire.retiredNear(x, y)) {
                why[0]++;
                return false;
            }
            if (!pathClear(ax, ay, x, y, 12)) {
                why[1]++;
                return false;
            }
            return true;
        };
        SitePlanner planner = ai.planner();
        Site site = planner.findArmorySite(reservedSites(null), st.reloc_reach, from, 0, ok, LOCK_SITE_TREE_TICKS,
                lock_search, now);
        if (planner.lastSearchGaveUp())
            ai.aiLog().count("lock_search_gaveup"); // searches that stopped at 200 rejected candidates
        if (site == null) {
            int trees = planner.lastTreeFailed();
            ai.aiLog().count("lock_nosite");
            int k = trees > why[0] && trees > why[1] ? 2 : why[0] >= why[1] ? 0 : 1;
            ai.aiLog().count("lock_nosite_" + new String[]{"quiet", "path", "trees"}[k]);
            ai.aiLog().log("LOCK", () -> String.format(
                    "no lock site for the armory at %d,%d: %d candidates not quiet, %d with enemies on the way, %d quiet ones short of trees",
                    ax, ay, why[0], why[1], trees));
            return;
        }
        float[] known = planner.searched(lock_search, site.x, site.y);
        float site_tree = known != null ? known[2] : -1f;
        boolean capped = !ai.owner().canBuild(Race.BUILDING_ARMORY);
        Project p = addProject(Race.BUILDING_ARMORY, site, 1);
        p.lock = true;
        p.added = now;
        expansion_project = p;
        lock_old = primary;
        lock_new = null;
        ai.aiLog().count("lock_project");
        if (capped)
            ai.aiLog().count("lock_project_capped");
        Site s = site;
        float tree = site_tree;
        int dist = (int) Math.sqrt(MapAnalysis.dist2(ax, ay, s.x, s.y));
        int workers = primary.getUnitContainer().getNumSupplies();
        ai.aiLog().log("LOCK", () -> String.format(
                "lock move to %d,%d: cost %.0f, tree cycle %.0f s against %.0f, %d cells from the armory at %d,%d (%d workers)%s",
                s.x, s.y, GauntletAI.seconds(-s.score), tree < 0f ? tree : GauntletAI.seconds(tree),
                GauntletAI.seconds(tree_cycle), dist, ax, ay, workers, capped ? ", waiting for a slot" : ""));
    }

    /**
     * reloc_lock: whether the locked armory keeps its workers inside (drainSecondary): while the lock move goes up, and
     * once it stands until it holds 2 wood (a weapon's) and has no threat within 16 cells, LOCK_HOLD_TICKS at most
     * (then 150-200 workers would sit in a secondary armory for good, lost with it if it is razed). Else they would
     * walk out to an armory with nothing to forge from, and be what a wave on the way finds.
     */
    private boolean lockHold(@NonNull Building old) {
        Building nu = lock_new;
        boolean building = false;
        for (Project q : projects)
            building |= q.lock;
        if (old.isDead() || (nu == null && !building) || (nu != null && nu.isDead())) {
            lock_old = null;
            lock_new = null;
            return false;
        }
        boolean timeout = nu != null && ai.now() - lock_armory_done > LOCK_HOLD_TICKS;
        if (nu != null && (timeout || (stock(nu, TreeSupply.class) >= 2
                && !ai.military().threatNear(nu.getGridX(), nu.getGridY(), 16)))) {
            lock_old = null;
            lock_new = null;
            ai.aiLog().count(timeout ? "lock_bank_timeout" : "lock_bank_released");
            int workers = old.getUnitContainer().getNumSupplies();
            ai.aiLog().log("LOCK",
                    () -> "the locked armory at " + old.getGridX() + "," + old.getGridY() + " lets its " + workers + " workers go to the new one");
            return false;
        }
        ai.aiLog().count("lock_bank_held"); // economy ticks the locked armory keeps its workers as secondary
        return true;
    }

    /**
     * reloc_slot, reloc_lock: the flagged armory project (a hop with reloc_slot, a lock move) that waits unplaced
     * while the engine's building count (buildings and placed sites), with the sites other placers are carrying, is at
     * the cap less one, so no other project may take the next slot; else null. Carried sites count because each takes
     * a slot when placed: in a forced retire smoke (s6012, N=13) two towers, one already on its way, took the two slots
     * a razing and an enemy had freed, and the hop's placer arrived at the cap.
     */
    private @Nullable Project slotWaiter() {
        Strategy st = ai.strategy();
        if (!st.reloc_slot && st.reloc_lock_ticks <= 0)
            return null;
        Project waiting = null;
        for (Project q : projects)
            if (((st.reloc_slot && q.reloc) || q.lock) && !q.isPlaced()) {
                waiting = q;
                break;
            }
        if (waiting == null)
            return null;
        int carried = 0;
        for (Project q : projects)
            if (q != waiting && q.building != null && !q.building.isDead() && !q.isPlaced())
                carried++;
        Player owner = ai.owner();
        return owner.getBuildingCountContainer().getNumSupplies() + carried >= owner.getWorld().getMaxBuildingCount() - 1 ? waiting : null;
    }

    /** retire: the flagged armory project (a hop or a lock move) waiting unplaced, or null. */
    @Nullable
    Project flaggedWaiting() {
        for (Project q : projects)
            if ((q.reloc || q.lock) && !q.isPlaced())
                return q;
        return null;
    }

    /** retire: builders on the building (Intel.builder_sites). */
    int buildersOn(@NonNull Building b) {
        return builderCount(b);
    }

    /** retire: whether the armory is drained (not primary, nobody inside, no stock or gatherers, not evacuating). */
    boolean isDrained(@NonNull Building a) {
        return drained(a);
    }

    /** retire: whether the building is being emptied or razed to free a slot (Retire's doomed set). */
    boolean isDoomed(@Nullable Building b) {
        return retire.isDoomed(b);
    }

    @Nullable
    DistanceField armoryField() {
        return ai.intel().armory() != null ? armory_field : null;
    }

    private void manageArmory() {
        Intel intel = ai.intel();
        Building primary = intel.armory();
        if (ai.strategy().weapon_reserve > 0)
            settleReserve(primary);
        if (primary == null)
            return;
        for (Building armory : intel.armories) {
            orderWeapons(armory);
            if (armory == primary)
                deployFromPrimary(armory);
            else
                drainSecondary(armory);
        }
    }

    /** Iron harvested per game tick, an average over measureYield's 30-s windows (bookkeeping, always on). */
    private float iron_rate;
    /** bank_guard: whether it caps the main armory this tick, the cap, and the room left under it. */
    private boolean bank_active;
    /** raid_bank: whether that cap is a forward armory's (counters only). */
    private boolean bank_raid;
    private int bank_cap = Integer.MAX_VALUE;
    private int bank_room;
    private float noforge_since = -1f;
    private float last_bank_unload = -5000f;
    /** bank_guard: peons parked in each quarters above its hold. */
    private final Map<@NonNull Building, Integer> bank_reserve = new LinkedHashMap<>();

    private static int stock(@NonNull Building armory, @NonNull Class<?> type) {
        return armory.getSupplyContainer(type).getNumSupplies();
    }

    /**
     * bank_guard: from bank_guard_ticks the main armory keeps only the workers that its measured iron income and its
     * stock can keep forging, bank_min once it has been unable to forge for bank_noforge_ticks. Units inside a razed
     * building vanish with it (105 per game by 20 min at N=12, 42 per armory razing; late/spec S2), and allocatePeons
     * step 4 fills the armory with every free peon whatever it can forge. The surplus comes out (no rally point) and
     * waits in the quarters farthest from the threat (reserveQuarters). Runs after deployFromPrimary, so weapons in
     * stock leave as warriors first. raid_bank puts the same cap on a forward primary (not the armory nearest our
     * start), with raid_bank_extra as the backlog's bound.
     */
    private void guardBank() {
        Strategy st = ai.strategy();
        float now = ai.now();
        Building a = ai.intel().armory();
        bank_reserve.keySet().removeIf(Building::isDead);
        // raid_bank: a forward armory is a raid on a fresh pile, razed a median 6 min after completion.
        boolean raid = st.raid_bank && now >= st.raid_bank_ticks && a != null && !a.isDead() && a.isComplete()
                && homeArmory(a) != a;
        bank_active = ((st.bank_guard && now >= st.bank_guard_ticks) || raid) && a != null && !a.isDead()
                && a.isComplete() && !evacuating.containsKey(a);
        bank_raid = bank_active && raid;
        if (!bank_active || a == null) {
            bank_cap = Integer.MAX_VALUE;
            noforge_since = -1f;
            return;
        }
        if (raid)
            ai.aiLog().count("raid_bank_ticks"); // economy ticks (1 s) with the cap on a forward armory
        int iron = stock(a, IronSupply.class);
        int rock = stock(a, RockSupply.class);
        boolean rockw = rock_weapons || rock_filler;
        boolean can_make = canForge(a);
        noforge_since = can_make ? -1f : noforge_since < 0f ? now : noforge_since;
        if (noforge_since >= 0f && now - noforge_since >= st.bank_noforge_ticks) {
            bank_cap = st.bank_min; // the forge_release clause (K11a-2)
            ai.aiLog().count("bank_noforge");
        } else {
            int flow = (int) Math.ceil(st.bank_margin * iron_rate * IRON_WORK_TICKS);
            int backlog = Math.min(raid ? st.raid_bank_extra : 12, iron + (rockw ? rock / 2 : 0));
            bank_cap = Math.max(st.bank_min, flow + backlog);
        }
        int workers = a.getUnitContainer().getNumSupplies();
        bank_room = bank_cap - workers - countHeadingTo(a);
        if (!ai.periodDue(last_bank_unload, 500f)) // an unload every 10 s at most
            return;
        int weapons = stock(a, IronAxeWeapon.class) + stock(a, RubberAxeWeapon.class) + stock(a, RockAxeWeapon.class);
        int pending = a.getDeployContainer(DeployType.PEON).getNumSupplies();
        int surplus = workers - weapons - bank_cap;
        if (surplus <= 4 || pending > 0 || reserveQuarters() == null)
            return;
        last_bank_unload = now;
        ai.owner().deployUnits(a, DeployType.PEON, surplus);
        for (int i = 0; i < surplus; i++) {
            ai.aiLog().count("bank_unload");
            if (raid)
                ai.aiLog().count("raid_bank_unload");
        }
        if (ai.logging())
            ai.log(String.format("%s: unloading %d of %d workers at %d,%d (cap %d, iron %.1f/min)",
                    raid ? "raid bank" : "bank guard", surplus, workers, a.getGridX(), a.getGridY(), bank_cap,
                    60 * GauntletAI.TICKS_PER_SECOND * iron_rate));
    }

    /**
     * bank_guard: the complete quarters with no threat within 16 cells and room in the reserve that is farthest from
     * the threat (with none, nearest our start), or null.
     */
    private @Nullable Building reserveQuarters() {
        Military m = ai.military();
        boolean thr = m.baseThreatLevel() > 0;
        int sx = ai.planner().getStartX();
        int sy = ai.planner().getStartY();
        Building best = null;
        int best_score = Integer.MIN_VALUE;
        for (Building q : ai.intel().quarters) {
            if (q.isDead() || !q.isComplete() || evacuating.containsKey(q)
                    || m.threatNearEcon(q.getGridX(), q.getGridY(), 16) || retire.isDoomed(q))
                continue;
            if (bank_reserve.getOrDefault(q, 0) >= ai.strategy().bank_reserve_max)
                continue;
            int score = thr ? MapAnalysis.dist2(q.getGridX(), q.getGridY(), m.threatX(),
                    m.threatY()) : -MapAnalysis.dist2(q.getGridX(), q.getGridY(), sx, sy);
            if (best == null || score > best_score) {
                best_score = score;
                best = q;
            }
        }
        return best;
    }

    /** bank_guard: whether the main armory holds (or has walking in) as many workers as its cap allows. */
    boolean bankFull(@Nullable Building armory) {
        return bank_active && armory != null && armory == ai.intel().armory() && !armory.isDead()
                && armory.getUnitContainer().getNumSupplies() + countHeadingTo(armory) >= bank_cap;
    }

    /** A project waiting for choosePlacer to find it a placer. */
    private boolean hasUnplacedProject() {
        for (Project p : projects)
            if (!p.isPlaced() && p.building == null)
                return true;
        return false;
    }

    /**
     * An armory that is no longer the main one turns what it has into warriors and then sends its peons over to the
     * main armory.
     */
    private void drainSecondary(@NonNull Building armory) {
        Player owner = ai.owner();
        boolean relink_guard = ai.strategy().relink_guard;
        if (relink_guard)
            syncRelinkPrimary();
        if (ai.strategy().recall_old_gatherers && ai.periodDue(last_old_recall, 500f)) { // every 10 s
            last_old_recall = ai.now();
            // relink_guard: since this armory's last recall under the main armory, and in the last 30 s, the engine
            // linked gatherers we sent for the main armory to it (a load goes to the supply's nearest armory).
            // Recalled, they would be sent to the same supplies again.
            boolean guarded = relink_guard && relinkedRecently(armory);
            PeonState[] states = {PeonState.GATHER_TREE, PeonState.GATHER_IRON, PeonState.GATHER_ROCK, PeonState.GATHER_CHICKEN};
            Class<?>[] types = {TreeSupply.class, IronSupply.class, RockSupply.class, RubberSupply.class};
            int recalled = 0;
            int kept = 0;
            for (int t = 0; t < states.length; t++) {
                int n = ai.intel().countLinkedGatherers(states[t], armory);
                if (n > 0 && guarded) {
                    kept += n;
                } else if (n > 0) {
                    owner.recallGatherers(armory, supplyClass(types[t]), n);
                    recalled += n;
                }
            }
            if (recalled > 0)
                ai.log("recalling " + recalled + " gatherers of the old armory at " + armory.getGridX() + "," + armory.getGridY());
            // relink_guard: an unguarded pass (the first under this main armory, or one after the guard lapsed)
            // recalls everyone, the old armory's own workforce included, so only re-links after it start the guard.
            if (relink_guard && !guarded) {
                if (!relink_recalled.contains(armory))
                    relink_recalled.add(armory);
                relinked.remove(armory);
            }
            if (kept > 0) {
                for (int i = 0; i < kept; i++)
                    ai.aiLog().count("recall_skipped_relink");
                ai.log("leaving " + kept + " re-linked gatherers to the old armory at " + armory.getGridX() + "," + armory.getGridY());
            }
        }
        int workers = armory.getUnitContainer().getNumSupplies();
        if (workers == 0)
            return;
        int iron = armory.getSupplyContainer(IronAxeWeapon.class).getNumSupplies();
        int chicken = armory.getSupplyContainer(RubberAxeWeapon.class).getNumSupplies();
        int rock = armory.getSupplyContainer(RockAxeWeapon.class).getNumSupplies();
        int left = workers - deployWarriors(armory, workers, chicken, iron, rock);
        boolean can_make = canForge(armory);
        int pending = armory.getDeployContainer(DeployType.PEON).getNumSupplies();
        // danger_refuge and raid_evac: peons sheltering here stay in.
        boolean refuge = armory == refuge_armory && ai.now() < refuge_until;
        if (!raid_evacs.isEmpty() && raidRefuge(armory))
            refuge = true;
        // reloc_lock: the locked armory's workers wait inside until the new one can forge.
        if (lock_old == armory && lockHold(armory))
            refuge = true;
        if (!can_make && left > 0 && pending == 0 && !refuge)
            owner.deployUnits(armory, DeployType.PEON, left);
    }

    /** Whether an armory's stock pays for a weapon: two wood and an iron, or a rock while rock axes are made. */
    private boolean canForge(@NonNull Building armory) {
        return stock(armory, TreeSupply.class) >= 2 && (stock(armory, IronSupply.class) >= 1
                || ((rock_weapons || rock_filler) && stock(armory, RockSupply.class) >= 1));
    }

    private void orderWeapons(@NonNull Building armory) {
        Player owner = ai.owner();
        // weapon_sync holds a queue by cancelling its orders: leave those to it
        boolean synced = armory == sync_armory;
        if (!(synced && sync_paused[SYNC_IRON])
                && armory.getBuildSupplyContainer(IronAxeWeapon.class).getNumSupplies() == 0)
            owner.buildIronWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        if (!(synced && sync_paused[SYNC_RUBBER]) && owner.canUseRubber()
                && armory.getBuildSupplyContainer(RubberAxeWeapon.class).getNumSupplies() == 0)
            owner.buildRubberWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        int rock_orders = armory.getBuildSupplyContainer(RockAxeWeapon.class).getNumSupplies();
        boolean make_rock = wantsRockWeapons();
        if (make_rock && rock_orders == 0 && !(synced && sync_paused[SYNC_ROCK]))
            owner.buildRockWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        else if (!make_rock && rock_orders > 0 && !(synced && (sync_helping & 1 << SYNC_ROCK) != 0))
            owner.buildRockWeapons(armory, -rock_orders, false);
    }

    private boolean wantsRockWeapons() {
        return rock_weapons || rock_filler || ai.strategy().rock_share > 0f || rock_stream_on;
    }

    /**
     * weapon_reserve: the main armory whose weapons were held back on the last economy tick, the chicken, iron and rock
     * axes held, and their sum (the workers kept in for them).
     */
    private @Nullable Building reserve_armory;
    private int reserve_chicken;
    private int reserve_iron;
    private int reserve_rock;
    private int reserve_held;
    /** weapon_reserve: when its holding log line was last written (once a minute at most). */
    private float reserve_log = -5000f;

    /**
     * weapon_reserve: what became of the last economy tick's reserve, before this tick's is set: released at threat 2
     * (deployFromPrimary then lets everything out), lost with its armory, or left to drainSecondary in an armory that
     * is no longer the main one.
     */
    private void settleReserve(@Nullable Building primary) {
        Building a = reserve_armory;
        int held = reserve_held;
        reserve_armory = null;
        reserve_chicken = reserve_iron = reserve_rock = reserve_held = 0;
        if (a == null || held == 0)
            return;
        String what;
        if (a.isDead()) {
            for (int i = 0; i < held; i++)
                ai.aiLog().count("weapon_reserve_lost"); // weapons held in an armory razed since
            what = "lost with the armory";
        } else if (a != primary) {
            what = "left to the drain of the old main armory";
        } else if (ai.military().baseThreatLevel() >= 2) {
            for (int i = 0; i < held; i++)
                ai.aiLog().count("weapon_reserve_released"); // weapons let out at threat 2
            what = "released at threat 2";
        } else {
            return;
        }
        if (ai.logging())
            ai.log(String.format("weapon reserve: %d weapons %s at %d,%d", held, what, a.getGridX(), a.getGridY()));
    }

    /**
     * weapon_reserve: the workers kept in the armory for the weapons it holds back (0 for any other armory), a floor
     * for everything that lets workers out of it. Set by this economy tick's deployFromPrimary; before manageArmory
     * (Retire runs first), the last tick's.
     */
    int weaponReserveHeld(@NonNull Building armory) {
        return armory == reserve_armory ? reserve_held : 0;
    }

    /**
     * weapon_reserve: the chicken, iron and rock axes the armory holds back, which an attack does not get, or null
     * (none, another armory, or the base threat at 2, which releases them).
     */
    int @Nullable [] weaponReserve(@NonNull Building armory) {
        if (armory != reserve_armory || reserve_held == 0 || ai.military().baseThreatLevel() >= 2)
            return null;
        return new int[]{reserve_chicken, reserve_iron, reserve_rock};
    }

    private void deployFromPrimary(@NonNull Building armory) {
        Player owner = ai.owner();
        int workers = armory.getUnitContainer().getNumSupplies();
        int iron = armory.getSupplyContainer(IronAxeWeapon.class).getNumSupplies();
        int chicken = armory.getSupplyContainer(RubberAxeWeapon.class).getNumSupplies();
        int rock = armory.getSupplyContainer(RockAxeWeapon.class).getNumSupplies();
        int stock = iron + chicken + rock;
        if (stock == 0 || workers == 0)
            return;

        Military military = ai.military();
        int pop = owner.getUnitCountContainer().getNumSupplies();
        boolean capped = pop >= owner.getWorld().getMaxUnitCount() - 3;
        // weapon_reserve: from weapon_reserve_ticks, while the base threat is below 2 (an attack mustering and the unit
        // cap included), up to weapon_reserve weapons stay in stock for the collapse, of those that are not chicken
        // axes for free tower seats (those still go out). Threat 2 lets them out with the rest (wantsEverything).
        Strategy st = ai.strategy();
        int seats = 0;
        int hold = 0;
        if (st.weapon_reserve > 0 && ai.now() >= st.weapon_reserve_ticks && military.baseThreatLevel() < 2
                && !evacuating.containsKey(armory)) {
            seats = Math.min(chicken, military.towerSeatsFree());
            hold = Math.min(st.weapon_reserve, stock - seats);
        }
        int deploy;
        if (military.wantsEverything() || capped) {
            deploy = stock;
        } else {
            deploy = 0;
            // Chicken warriors go straight into towers.
            deploy = Math.max(deploy, Math.min(chicken, military.towerSeatsFree()));
            // Keep a standing army able to handle raids; beyond that weapons wait in the armory while its peons
            // keep working, and come out when the army needs them or production outgrows gathering.
            float deficit = military.armyStrengthWanted() - military.armyStrength();
            if (deficit > 0)
                deploy = Math.max(deploy, (int) Math.ceil(deficit));
            if (workers > want_workers + 2)
                deploy = Math.max(deploy, workers - want_workers);
            if (stock > 12)
                deploy = Math.max(deploy, stock - 12);
        }
        int keep = military.wantsEverything() ? 0 : Math.min(2, workers);
        // weapon_reserve: iron axes held first, then rock, then chicken; workers to arm them stay in, after those for
        // the free tower seats.
        int hold_iron = Math.min(hold, iron);
        int hold_rock = Math.min(hold - hold_iron, rock);
        int hold_chicken = hold - hold_iron - hold_rock;
        if (hold > 0) {
            deploy = Math.min(deploy, stock - hold);
            keep = Math.max(keep, Math.min(hold, workers - seats));
            holdReserve(armory, hold_chicken, hold_iron, hold_rock, workers);
        }
        deploy = Math.min(deploy, Math.min(stock, workers - keep));
        if (deploy <= 0)
            return;
        deployWarriors(armory, deploy, chicken - hold_chicken, iron - hold_iron, rock - hold_rock);
    }

    /** weapon_reserve: records this economy tick's reserve in the main armory, counts it, logs it once a minute. */
    private void holdReserve(@NonNull Building armory, int chicken, int iron, int rock, int workers) {
        reserve_armory = armory;
        reserve_chicken = chicken;
        reserve_iron = iron;
        reserve_rock = rock;
        reserve_held = chicken + iron + rock;
        ai.aiLog().count("weapon_reserve_held"); // economy ticks (1 s)
        if (ai.logging() && ai.periodDue(reserve_log, 3000f)) { // 60 s
            reserve_log = ai.now();
            ai.log(String.format(
                    "weapon reserve: holding %d weapons (iron %d, rock %d, chicken %d) at %d,%d with %d" + " workers inside, threat %d",
                    reserve_held, iron, rock, chicken, armory.getGridX(),
                    armory.getGridY(), workers, ai.military().baseThreatLevel()));
        }
    }

    /**
     * Deploys up to n warriors from an armory's weapon stock (chicken, iron and rock axes, as read before), chicken
     * warriors first, then iron, then rock, and returns how many.
     */
    private int deployWarriors(@NonNull Building armory, int n, int chicken, int iron, int rock) {
        Player owner = ai.owner();
        int c = Math.min(chicken, n);
        if (c > 0)
            owner.deployUnits(armory, DeployType.RUBBER_WARRIOR, c);
        int i = Math.min(iron, n - c);
        if (i > 0)
            owner.deployUnits(armory, DeployType.IRON_WARRIOR, i);
        int r = Math.min(rock, n - c - i);
        if (r > 0)
            owner.deployUnits(armory, DeployType.ROCK_WARRIOR, r);
        return c + i + r;
    }

    // ------------------------------------------------------------------------------------------------------------
    // weapon_sync
    //
    // Every tick, WeaponsProducer.animate gives each weapon queue that has orders and the resources for one weapon an
    // equal share of the armory's man-seconds (peons inside x tick / queues). A queue takes its weapon's cost only on
    // the tick the weapon is done (BuildProductionContainer.build), clamped at zero, and keeps its progress while it
    // has no orders. So weapons done on the same tick share the resources they have in common: an iron axe and a
    // rubber axe one iron, a rock axe and a rubber axe one rock, all three one iron, one rock and two wood (when the
    // stock holds just that). While the main armory can forge a rubber axe and the iron (or rock, weapon_sync_three)
    // it shares with the iron (rock) axe is short, the queues that would finish first are paused by cancelling their
    // orders until the rubber axe catches up, and ordered again so that all finish on the same tick. The world ticks
    // buildings before the AI (World.tick: game-time pass, then the real-time pass the AI is on), so an order given
    // in tick T counts from the producer's tick T+1; weaponSync plans after all our other orders of the tick, from the
    // queues' man-seconds added up exactly as the engine does in floats.
    //
    // In this section a tick is a world tick (a call of weaponSync), the step the engine produces in, and man-seconds
    // are the engine's game seconds: the planner adds them up exactly as the engine does, so it keeps the engine's
    // units (as Reflexes does for the swings). Only its prediction window is game time (SYNC_HORIZON_TICKS), and a
    // paused tick (no game time) plans nothing.

    /** The armory's weapon queues in the order WeaponsProducer builds them (LandBuilding): rock, iron, rubber axes. */
    private static final int SYNC_ROCK = 0;
    private static final int SYNC_IRON = 1;
    private static final int SYNC_RUBBER = 2;
    private static final String[] SYNC_NAMES = {"rock", "iron", "rubber"};
    private static final Class<?>[] SYNC_WEAPONS = {RockAxeWeapon.class, IronAxeWeapon.class, RubberAxeWeapon.class};
    /** Man-seconds per weapon of each queue (LandBuilding). */
    private static final float[] SYNC_WORK = {40f, 80f, 120f};
    /** The resources, and what a weapon of each queue costs in them (LandBuilding.COST_*_WEAPON). */
    private static final Class<?>[] SYNC_SUPPLIES = {TreeSupply.class, RockSupply.class, IronSupply.class, RubberSupply.class};
    private static final String[] SYNC_SUPPLY_NAMES = {"tree", "rock", "iron", "rubber"};
    private static final int[][] SYNC_COST = {{2, 1, 0, 0}, {2, 0, 1, 0}, {2, 1, 1, 1}};
    /**
     * Game ticks ahead within which the queues' finishing ticks are worked out exactly (and a plan searched for): the
     * world ticks of syncHorizon.
     */
    private static final float SYNC_HORIZON_TICKS = 250f; // 5 s
    /** Most ticks of pauses a plan may take before all the queues run together (a search depth, not a time). */
    private static final int SYNC_DEPTH = 5;
    /** Cells around the armory within which a carried load could reach its stock in one tick. */
    private static final int SYNC_REACH = 12;

    /** The armory whose queues weapon_sync runs, and the queues it holds by having cancelled their orders. */
    private @Nullable Building sync_armory;
    private final boolean[] sync_paused = new boolean[3];
    /** What the armory held after our last orders of world tick sync_tick (GauntletAI.worldTicks). */
    private int sync_tick = -100;
    private final float[] sync_progress = new float[3];
    private final float[] sync_ms = new float[3];
    private final int[] sync_weapons = new int[3];
    private final int[] sync_stock = new int[4];
    /** Each queue's man-seconds after the next tick, if the world does what the orders mean. */
    private final float[] sync_expect = new float[3];
    /** Loads carried next to the armory at the snapshot ({supply, amount}): they may reach its stock next tick. */
    private final Map<@NonNull Unit, int @NonNull []> sync_carried = new LinkedHashMap<>();
    /**
     * The alignment under way: its queues (a bit each, 0 = none), its number, and the world tick due (0 = not
     * aligned).
     */
    private int sync_targets;
    /** A queue the alignment under way holds back because the rubber axe meets only its partner (a bit, 0 = none). */
    private int sync_held;
    /**
     * The rock queue ordered for a tick or two although the weapon policy wants no rock axes, only to change the
     * others' shares (a bit, 0 = none): with the iron and rubber axes alone every tick moves them by one or two
     * half-shares, which cannot line up remainders an odd number of half-shares apart.
     */
    private int sync_helping;
    private int sync_attempts;
    private int sync_due;
    /**
     * The planner's inputs (the step among them) when a search last found nothing while every queue ran: not searched
     * again meanwhile.
     */
    private long sync_failed = -1;
    /** Queue ticks whose man-seconds came out as the orders meant, and those that did not (the order latency). */
    private int sync_expect_ok;
    private int sync_expect_off;
    private int sync_rubber;

    /**
     * weapon_sync, first thing in a tick: what the world's tick did in the main armory, against the snapshot taken
     * after our last orders. A queue whose progress went back to zero while its weapon stock grew finished a weapon.
     * Weapons finished together duplicated a shared resource when its stock fell by less than their costs and could
     * not have paid them even with every load that could have come in during the tick.
     */
    void weaponSyncObserve() {
        Building armory = sync_armory;
        if (armory == null || armory.isDead() || sync_tick != ai.worldTicks() - 1)
            return;
        int done = 0;
        for (int c = 0; c < 3; c++) {
            float progress = syncQueue(armory, c).getBuildProgress();
            int weapons = armory.getSupplyContainer(SYNC_WEAPONS[c]).getNumSupplies();
            if (sync_progress[c] > 0f && progress == 0f && weapons > sync_weapons[c])
                done |= 1 << c;
        }
        if (done == 0)
            return;
        int[] stock = syncStock(armory);
        int[] delivered = new int[4];
        for (Map.Entry<Unit, int[]> e : sync_carried.entrySet()) {
            UnitSupplyContainer load = e.getKey().getSupplyContainer();
            int[] was = e.getValue();
            int now = load != null && syncSupplyIndex(load.getSupplyType()) == was[0] ? load.getNumSupplies() : 0;
            delivered[was[0]] += Math.max(0, was[1] - now);
        }
        boolean dup = false;
        if (Integer.bitCount(done) >= 2) {
            for (int s = 0; s < 4; s++) {
                int users = 0;
                int cost = 0;
                for (int c = 0; c < 3; c++) {
                    if ((done & 1 << c) != 0 && SYNC_COST[c][s] > 0) {
                        users++;
                        cost += SYNC_COST[c][s];
                    }
                }
                if (users >= 2 && sync_stock[s] - stock[s] < cost && sync_stock[s] + delivered[s] < cost)
                    dup = true;
            }
        }
        if ((done & 1 << SYNC_RUBBER) != 0)
            sync_rubber++;
        if (dup)
            ai.aiLog().count("weapon_sync_dup");
        int targets = sync_targets;
        String outcome = "";
        if (targets != 0 && (done & targets) != 0) {
            boolean hit = (done & targets) == targets;
            if (!hit)
                ai.aiLog().count("weapon_sync_missed");
            outcome = String.format("#%d %s (%s, due %s)", sync_attempts, hit ? "hit" : "missed",
                    syncNames(targets), sync_due > 0 ? Integer.toString(sync_due - ai.worldTicks()) : "-");
            sync_targets = 0;
            sync_due = 0;
            sync_held = 0;
        }
        if (targets != 0 || dup || Integer.bitCount(done) >= 2 || (done & 1 << SYNC_RUBBER) != 0) {
            String what = outcome;
            boolean duplicated = dup;
            int finished = done;
            ai.aiLog().log("WSYNC", () -> {
                StringBuilder sb = new StringBuilder();
                sb.append(what.isEmpty() ? "done" : what).append(": ").append(syncNames(finished));
                if ((finished & 1 << SYNC_RUBBER) != 0)
                    sb.append(" (rubber axe ").append(sync_rubber).append(')');
                sb.append(duplicated ? ", dup" : "").append(", stock");
                for (int s = 0; s < 4; s++) {
                    sb.append(' ').append(SYNC_SUPPLY_NAMES[s]).append(' ').append(sync_stock[s]).append("->").append(
                            stock[s]);
                    if (delivered[s] > 0)
                        sb.append(" (<=").append(delivered[s]).append(" in)");
                }
                sb.append(", predicted ").append(sync_expect_ok).append('/').append(sync_expect_ok + sync_expect_off);
                return sb.toString();
            });
        }
    }

    /**
     * weapon_sync, last thing in a tick, after all our other orders: chooses the main armory's queues that run in the
     * next tick and takes the snapshot the next tick's weaponSyncObserve compares with.
     */
    void weaponSync(float t) {
        Player owner = ai.owner();
        Building armory = ai.intel().armory();
        if (armory != null && (armory.isDead() || !armory.isComplete()))
            armory = null;
        if (armory != sync_armory) {
            syncRelease("main armory changed");
            sync_armory = armory;
            sync_tick = -100;
        }
        if (armory == null || !owner.isAlive())
            return;
        boolean checked = sync_tick == ai.worldTicks() - 1;
        BuildProductionContainer[] queues = new BuildProductionContainer[3];
        float[] ms = new float[3];
        for (int c = 0; c < 3; c++) {
            queues[c] = syncQueue(armory, c);
            ms[c] = syncManSeconds(queues[c].getBuildProgress(), SYNC_WORK[c], checked ? sync_expect[c] : Float.NaN,
                    checked ? sync_ms[c] : Float.NaN);
            if (checked && ms[c] == sync_expect[c])
                sync_expect_ok++;
            else if (checked)
                sync_expect_off++;
        }
        int workers = armory.getUnitContainer().getNumSupplies();
        int[] stock = syncStock(armory);
        // the game-time step, as World.tick gives it to the buildings
        float step = owner.getWorld().getSecondsPerTick() * t / AnimationManager.ANIMATION_SECONDS_PER_TICK;
        // paused: no game time passes and the producer adds nothing, so there is nothing to plan; the snapshot (share
        // 0) expects the queues as they are
        if (step <= 0f) {
            syncSnapshot(armory, queues, ms, stock, workers, step);
            return;
        }
        boolean three = ai.strategy().weapon_sync_three;
        boolean[] wanted = {wantsRockWeapons(), true, owner.canUseRubber()};
        // avail: the queues that can run next tick; forced: those among them we leave as the weapon policy has them;
        // helpers: the rock queue when the policy wants no rock axes, which a plan may run for a tick or two
        int avail = 0;
        int forced = 0;
        int helpers = 0;
        for (int c = 0; c < 3; c++) {
            boolean pausable = c != SYNC_ROCK || three;
            if (wanted[c] || !pausable)
                sync_helping &= ~(1 << c);
            if (!syncAffordable(stock, c))
                continue;
            if (wanted[c] && pausable)
                avail |= 1 << c;
            else if ((sync_helping & 1 << c) != 0 || (pausable && queues[c].getNumSupplies() == 0))
                helpers |= 1 << c;
            else if (queues[c].getNumSupplies() > 0) {
                avail |= 1 << c;
                forced |= 1 << c;
            }
        }
        int free = avail & ~forced;
        int targets = 0;
        if ((free & 1 << SYNC_RUBBER) != 0) {
            for (int c = SYNC_ROCK; c <= SYNC_IRON; c++)
                if ((free & 1 << c) != 0 && syncShortShared(stock, c))
                    targets |= 1 << c;
            if (targets != 0)
                targets |= 1 << SYNC_RUBBER;
        }
        if (targets == 0) {
            if (sync_targets != 0)
                syncRelease((free & 1 << SYNC_RUBBER) == 0 ? "no rubber axe to forge" : "nothing short");
            else
                syncRelease("");
            syncSnapshot(armory, queues, ms, stock, workers, step);
            return;
        }
        // an alignment narrowed to a pair holds the third queue while that one is still short
        int held = sync_held;
        if (sync_targets != 0 && held != 0 && (targets & held) == held
                && Integer.bitCount(targets & ~held) >= 2) {
            targets &= ~held;
            avail &= ~held;
        } else
            sync_held = 0;
        if (sync_targets == 0) {
            sync_attempts++;
            sync_failed = -1;
            int shown = targets;
            ai.aiLog().log("WSYNC", () -> String.format("#%d start %s: %s, %d peons", sync_attempts,
                    syncNames(shown), syncState(ms, stock), workers));
        }
        sync_targets = targets;
        if (workers > 0) {
            int run = syncPlan(ms, avail, forced, helpers, workers, step);
            for (int c = 0; c < 3; c++) {
                if ((forced & 1 << c) != 0 || (c == SYNC_ROCK && !three))
                    continue;
                int orders = queues[c].getNumSupplies();
                boolean helper = (helpers & 1 << c) != 0;
                int queue = c;
                if ((run & 1 << c) != 0 && orders == 0) {
                    syncOrder(armory, c, BuildSpinner.INFINITE_LIMIT, true);
                    sync_paused[c] = false;
                    if (helper)
                        sync_helping |= 1 << c;
                    ai.aiLog().log("WSYNC", () -> String.format("#%d %s %s: %s", sync_attempts,
                            helper ? "helper on" : "resume", SYNC_NAMES[queue], syncState(ms, stock)));
                } else if ((run & 1 << c) == 0 && ((avail | sync_held | helpers) & 1 << c) != 0 && orders > 0) {
                    syncOrder(armory, c, -orders, false);
                    if (helper) {
                        sync_helping &= ~(1 << c);
                        ai.aiLog().log("WSYNC", () -> String.format("#%d helper off %s: %s", sync_attempts,
                                SYNC_NAMES[queue], syncState(ms, stock)));
                    } else {
                        sync_paused[c] = true;
                        ai.aiLog().count("weapon_sync_pause");
                        ai.aiLog().log("WSYNC", () -> String.format("#%d pause %s: %s", sync_attempts,
                                SYNC_NAMES[queue], syncState(ms, stock)));
                    }
                }
            }
        }
        syncSnapshot(armory, queues, ms, stock, workers, step);
    }

    /**
     * The queues to run in the next tick: all of avail once the targets would finish on the same tick that way; else,
     * once they are within SYNC_DEPTH ticks of each other, the first tick of the shortest plan of pauses (which may
     * run the helpers) after which they would; else the targets furthest behind catch up while the others wait. When
     * three have caught up and cannot meet, the rubber axe meets the iron axe, else the rock axe, and the third one
     * waits. Sets sync_targets to the queues the plan aligns, and sync_due to the tick they finish (0: not aligned).
     */
    private int syncPlan(float @NonNull [] ms, int avail, int forced, int helpers, int workers, float step) {
        int targets = sync_targets;
        int horizon = syncHorizon();
        int k = syncAlignedIn(ms, avail, targets, workers, step, horizon);
        if (k > 0)
            return syncAligned(avail, targets, k);
        sync_due = 0;
        int catch_up = syncCatchUp(ms, avail, targets, workers, step);
        boolean close = k == 0 && syncSpread(ms, targets) <= SYNC_DEPTH * (workers * step);
        // the step too: at another game speed a search may find a plan
        long key = ((long) Float.floatToIntBits(
                step) << 32) | ((long) workers << 16) | ((long) helpers << 12) | ((long) avail << 8) | ((long) forced << 4) | targets;
        if (close && key != sync_failed) {
            int run = syncSearch(ms, avail, forced, helpers, targets, workers, step, horizon);
            if (run != 0)
                return run;
            if (catch_up == avail && Integer.bitCount(targets) == 3) {
                for (int drop = SYNC_ROCK; drop <= SYNC_IRON; drop++) {
                    int pair = targets & ~(1 << drop);
                    int rest = avail & ~(1 << drop);
                    int kp = syncAlignedIn(ms, rest, pair, workers, step, horizon);
                    if (kp > 0) {
                        syncHold(drop, pair);
                        return syncAligned(rest, pair, kp);
                    }
                    if (kp == 0) {
                        run = syncSearch(ms, rest, forced, helpers, pair, workers, step, horizon);
                        if (run != 0) {
                            syncHold(drop, pair);
                            return run;
                        }
                    }
                }
            }
        }
        // Caught up and still apart: the queues run as they are until something changes (the peons inside, the
        // queues that can run), which is when a new search may find a plan.
        sync_failed = close && catch_up == avail ? key : -1;
        return catch_up;
    }

    /** How far apart the targets' remaining man-seconds are. */
    private static float syncSpread(float @NonNull [] ms, int targets) {
        float lo = Float.MAX_VALUE;
        float hi = 0f;
        for (int c = 0; c < 3; c++) {
            if ((targets & 1 << c) != 0) {
                lo = Math.min(lo, SYNC_WORK[c] - ms[c]);
                hi = Math.max(hi, SYNC_WORK[c] - ms[c]);
            }
        }
        return hi - lo;
    }

    /** The world ticks within SYNC_HORIZON_TICKS at this tick's game speed: 250 at normal speed, 62 at ludicrous. */
    private int syncHorizon() {
        return (int) (SYNC_HORIZON_TICKS / ai.tickStep());
    }

    /** Runs all of run, which makes the targets finish together in k ticks. */
    private int syncAligned(int run, int targets, int k) {
        int due = ai.worldTicks() + k;
        if (due != sync_due)
            ai.aiLog().log("WSYNC", () -> String.format("#%d aligned %s: due in %d ticks", sync_attempts,
                    syncNames(targets), k));
        sync_due = due;
        return run;
    }

    /** Narrows the alignment under way to the pair, holding queue drop until it is over. */
    private void syncHold(int drop, int pair) {
        sync_targets = pair;
        sync_held = 1 << drop;
        ai.aiLog().log("WSYNC", () -> String.format("#%d three cannot meet: %s, holding %s", sync_attempts,
                syncNames(pair), SYNC_NAMES[drop]));
    }

    /**
     * Ticks until the targets finish, running all of run from man-seconds ms, when they finish on the same tick; 0
     * when they finish apart, -1 when one of them is more than horizon ticks away (syncHorizon).
     */
    private static int syncAlignedIn(float @NonNull [] ms, int run, int targets, int workers, float step,
            int horizon) {
        float share = workers * step / Integer.bitCount(run);
        int k = 0;
        boolean apart = false;
        for (int c = 0; c < 3; c++) {
            if ((targets & 1 << c) == 0)
                continue;
            int kc = syncTicksToFinish(ms[c], SYNC_WORK[c], share, horizon);
            if (kc > horizon)
                return -1;
            if (k == 0)
                k = kc;
            else if (kc != k)
                apart = true;
        }
        return apart ? 0 : k;
    }

    /**
     * Ticks until a queue at man-seconds ms finishes, adding share each tick as BuildProductionContainer.build does,
     * horizon + 1 beyond horizon ticks.
     */
    private static int syncTicksToFinish(float ms, float work, float share, int horizon) {
        if (share <= 0f)
            return horizon + 1;
        float m = ms;
        for (int k = 1; k <= horizon; k++) {
            m += share;
            if (m >= work)
                return k;
        }
        return horizon + 1;
    }

    /**
     * The first tick of the plan with the fewest ticks, then the fewest paused and helping queue-ticks, of up to
     * SYNC_DEPTH ticks that run subsets of avail and helpers (never pausing forced) after which the targets finish
     * together running all of avail, or finish together within it; 0 when there is none. A plan is a multiset of
     * ticks tried in one order, so the rest of it is found again in the next tick.
     */
    private static int syncSearch(float @NonNull [] ms, int avail, int forced, int helpers, int targets, int workers,
            float step, int horizon) {
        int[] moves = new int[7];
        int m = 0;
        for (int s = 1; s < 8; s++)
            if ((s & ~(avail | helpers)) == 0 && (s & forced) == forced && s != avail)
                moves[m++] = s;
        if (m == 0)
            return 0;
        int[] seq = new int[SYNC_DEPTH];
        float[] sim = new float[3];
        for (int depth = 1; depth <= SYNC_DEPTH; depth++) {
            Arrays.fill(seq, 0);
            int best = 0;
            int best_pauses = Integer.MAX_VALUE;
            while (true) {
                int pauses = 0;
                for (int i = 0; i < depth; i++)
                    pauses += Integer.bitCount(avail & ~moves[seq[i]]) + Integer.bitCount(helpers & moves[seq[i]]);
                if (pauses < best_pauses
                        && syncPlanWorks(ms, sim, moves, seq, depth, avail, helpers, targets, workers, step, horizon)) {
                    best = moves[seq[0]];
                    best_pauses = pauses;
                }
                int i = depth - 1;
                while (i >= 0 && seq[i] == m - 1)
                    i--;
                if (i < 0)
                    break;
                seq[i]++;
                for (int j = i + 1; j < depth; j++)
                    seq[j] = seq[i];
            }
            if (best != 0)
                return best;
        }
        return 0;
    }

    private static boolean syncPlanWorks(float @NonNull [] ms, float @NonNull [] sim, int @NonNull [] moves,
            int @NonNull [] seq, int depth, int avail, int helpers, int targets, int workers, float step, int horizon) {
        System.arraycopy(ms, 0, sim, 0, 3);
        for (int i = 0; i < depth; i++) {
            int run = moves[seq[i]];
            float share = workers * step / Integer.bitCount(run);
            int finished = 0;
            for (int c = 0; c < 3; c++) {
                if ((run & 1 << c) == 0)
                    continue;
                float v = sim[c] + share;
                if (v >= SYNC_WORK[c]) {
                    finished |= 1 << c;
                    v = 0f;
                }
                sim[c] = v;
            }
            // a helper must not finish a weapon the policy did not want (it would take the rock the rubber axe needs)
            if ((finished & helpers) != 0)
                return false;
            if ((finished & targets) != 0)
                return (finished & targets) == targets;
        }
        return syncAlignedIn(sim, avail, targets, workers, step, horizon) > 0;
    }

    /** The targets with the most man-seconds left run, those more than a tick of all the peons' work ahead wait. */
    private static int syncCatchUp(float @NonNull [] ms, int avail, int targets, int workers, float step) {
        float top = 0f;
        for (int c = 0; c < 3; c++)
            if ((targets & 1 << c) != 0)
                top = Math.max(top, SYNC_WORK[c] - ms[c]);
        float slack = workers * step;
        int run = avail;
        for (int c = 0; c < 3; c++)
            if ((targets & 1 << c) != 0 && top - (SYNC_WORK[c] - ms[c]) > slack)
                run &= ~(1 << c);
        return run;
    }

    /** Ends the alignment under way (logging why, when there was one) and orders the queues it held again. */
    private void syncRelease(@NonNull String why) {
        if (sync_targets != 0) {
            int n = sync_attempts;
            ai.aiLog().log("WSYNC", () -> String.format("#%d released: %s", n, why));
            sync_targets = 0;
            sync_due = 0;
        }
        sync_held = 0;
        Building armory = sync_armory;
        boolean alive = armory != null && !armory.isDead() && armory.isComplete();
        if (sync_helping != 0 && alive && !wantsRockWeapons()) {
            int orders = syncQueue(armory, SYNC_ROCK).getNumSupplies();
            if (orders > 0)
                syncOrder(armory, SYNC_ROCK, -orders, false);
        }
        sync_helping = 0;
        for (int c = 0; c < 3; c++) {
            if (!sync_paused[c])
                continue;
            sync_paused[c] = false;
            boolean wanted = c == SYNC_ROCK ? wantsRockWeapons() : c == SYNC_IRON || ai.owner().canUseRubber();
            if (alive && wanted && syncQueue(armory, c).getNumSupplies() == 0)
                syncOrder(armory, c, BuildSpinner.INFINITE_LIMIT, true);
        }
    }

    /** Remembers what the armory holds after our orders, and what the next tick should make of its queues. */
    private void syncSnapshot(@NonNull Building armory, @NonNull BuildProductionContainer @NonNull [] queues,
            float @NonNull [] ms, int @NonNull [] stock, int workers, float step) {
        int run = 0;
        for (int c = 0; c < 3; c++)
            if (queues[c].getNumSupplies() > 0 && syncAffordable(stock, c))
                run |= 1 << c;
        float share = run != 0 ? workers * step / Integer.bitCount(run) : 0f;
        for (int c = 0; c < 3; c++) {
            float next = ms[c];
            if ((run & 1 << c) != 0) {
                next += share;
                if (next >= SYNC_WORK[c])
                    next = 0f;
            }
            sync_expect[c] = next;
            sync_ms[c] = ms[c];
            sync_progress[c] = queues[c].getBuildProgress();
            sync_weapons[c] = armory.getSupplyContainer(SYNC_WEAPONS[c]).getNumSupplies();
        }
        System.arraycopy(stock, 0, sync_stock, 0, 4);
        sync_carried.clear();
        int ax = armory.getGridX();
        int ay = armory.getGridY();
        for (Selectable<?> s : ai.owner().getUnits().getSet()) {
            if (!(s instanceof Unit u) || u.isDead() || Math.abs(u.getGridX() - ax) > SYNC_REACH
                    || Math.abs(u.getGridY() - ay) > SYNC_REACH)
                continue;
            UnitSupplyContainer load = u.getSupplyContainer();
            if (load == null || load.getNumSupplies() == 0)
                continue;
            int supply = syncSupplyIndex(load.getSupplyType());
            if (supply >= 0)
                sync_carried.put(u, new int[]{supply, load.getNumSupplies()});
        }
        sync_tick = ai.worldTicks();
    }

    /**
     * The man-seconds behind a queue's progress (man_seconds / work in floats): a guess that gives the same progress
     * (the planned next value, then the unchanged one), else the float nearest progress x work that does.
     */
    private static float syncManSeconds(float progress, float work, float planned, float unchanged) {
        if (progress == 0f)
            return 0f;
        if (!Float.isNaN(planned) && planned / work == progress)
            return planned;
        if (!Float.isNaN(unchanged) && unchanged / work == progress)
            return unchanged;
        float guess = progress * work;
        float best = guess;
        float best_d = Float.MAX_VALUE;
        float f = Math.nextDown(Math.nextDown(Math.nextDown(guess)));
        for (int i = 0; i < 7; i++, f = Math.nextUp(f)) {
            if (f / work == progress && Math.abs(f - guess) < best_d) {
                best = f;
                best_d = Math.abs(f - guess);
            }
        }
        return best;
    }

    /** Whether the stock holds a weapon of queue c, as BuildProductionContainer.hasEnoughSupplies checks. */
    private static boolean syncAffordable(int @NonNull [] stock, int c) {
        for (int s = 0; s < 4; s++)
            if (stock[s] < SYNC_COST[c][s])
                return false;
        return true;
    }

    /** Whether queue c and the rubber axe share a resource the stock holds too little of to pay for both. */
    private static boolean syncShortShared(int @NonNull [] stock, int c) {
        for (int s = 0; s < 4; s++)
            if (SYNC_COST[c][s] > 0 && SYNC_COST[SYNC_RUBBER][s] > 0
                    && stock[s] < SYNC_COST[c][s] + SYNC_COST[SYNC_RUBBER][s])
                return true;
        return false;
    }

    private static int syncSupplyIndex(@Nullable Class<?> type) {
        for (int s = 0; s < 4; s++)
            if (SYNC_SUPPLIES[s] == type)
                return s;
        return -1;
    }

    private static @NonNull BuildProductionContainer syncQueue(@NonNull Building armory, int c) {
        return (BuildProductionContainer) armory.getBuildSupplyContainer(SYNC_WEAPONS[c]);
    }

    private static int @NonNull [] syncStock(@NonNull Building armory) {
        int[] stock = new int[4];
        for (int s = 0; s < 4; s++)
            stock[s] = armory.getSupplyContainer(SYNC_SUPPLIES[s]).getNumSupplies();
        return stock;
    }

    private void syncOrder(@NonNull Building armory, int c, int count, boolean infinite) {
        Player owner = ai.owner();
        switch (c) {
            case SYNC_ROCK -> owner.buildRockWeapons(armory, count, infinite);
            case SYNC_IRON -> owner.buildIronWeapons(armory, count, infinite);
            default -> owner.buildRubberWeapons(armory, count, infinite);
        }
    }

    private static @NonNull String syncNames(int queues) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < 3; c++)
            if ((queues & 1 << c) != 0)
                sb.append(sb.isEmpty() ? "" : "+").append(SYNC_NAMES[c]);
        return sb.toString();
    }

    private static @NonNull String syncState(float @NonNull [] ms, int @NonNull [] stock) {
        return String.format("left rock %.2f iron %.2f rubber %.2f, stock %d/%d/%d/%d", SYNC_WORK[0] - ms[0],
                SYNC_WORK[1] - ms[1], SYNC_WORK[2] - ms[2], stock[0], stock[1], stock[2], stock[3]);
    }

    private void computeGatherTargets() {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        if (armory == null || armory_field == null) {
            want_tree = want_iron = want_rock = want_chicken = want_workers = 0;
            if (ai.strategy().reloc_lock_ticks > 0)
                trackLock(null);
            return;
        }
        MapAnalysis map = ai.map();
        float harvest = ai.strategy().harvest_ticks;
        tree_cycle = SitePlanner.gatherTicks(armory_field, map.getTrees(), 60, 10, 120, harvest);
        int reach = ai.strategy().wood_reach;
        wood_starved = reach > 0 && ai.now() >= ai.strategy().wood_reach_ticks
                && (tree_cycle >= 4500f || ai.now() < tree_null_until); // 90 s
        if (wood_starved) {
            // wood_reach: the 60-cell ring is cut out; the cycle, and with it want_tree, per_weapon and the stuck
            // gatherers' trip allowance, see the trip to trees up to wood_reach cells out (the window is max_meters / 2).
            tree_cycle = SitePlanner.gatherTicks(armory_field, map.getTrees(), 60, 10, 2 * reach, harvest);
            ai.aiLog().count("wood_starved"); // plan ticks (3 s)
        }
        iron_cycle = SitePlanner.gatherTicks(armory_field, map.getIron(), 30, 10, 400, harvest);
        iron_cycle_armory = armory;
        int iron_left = countReachable(map.getIron(), 400);
        // Rock warriors are a poor use of a peon; make them only once iron is out of reach.
        rock_weapons = (iron_left == 0 || iron_cycle > 10000f) // 200 s
                && armory.getSupplyContainer(IronSupply.class).getNumSupplies() < 2;
        // With iron far away, the armory's peons often sit waiting for ore. Let them make rock weapons meanwhile:
        // iron weapons still take every piece of iron that comes in, since both are made side by side.
        int iron_stock = armory.getSupplyContainer(IronSupply.class).getNumSupplies();
        int armory_workers = armory.getUnitContainer().getNumSupplies();
        if (ai.strategy().hold_backlog > 0 && armory.isComplete()) {
            int forge = Math.min(iron_stock, armory.getSupplyContainer(TreeSupply.class).getNumSupplies() / 2);
            float until = ai.strategy().hold_backlog_until_ticks;
            if (!backlog_on && forge >= ai.strategy().hold_backlog && (until <= 0f || ai.now() < until)) {
                backlog_on = true;
                backlog_since = ai.now();
                ai.aiLog().count("backlog_on");
            } else if (backlog_on && ((forge <= 1 && ai.now() - backlog_since >= 1500f) // 30 s
                    || (until > 0f && ai.now() >= until)))
                backlog_on = false;
        }
        if (!rock_filler && iron_stock <= 1 && armory_workers >= ai.strategy().rock_filler_min_workers
                && iron_cycle > 2250f) // 45 s
            rock_filler = true;
        else if (rock_filler && (iron_stock >= 5 || armory_workers < 8))
            rock_filler = false;
        ore_far = false;
        int ore_reach = ai.strategy().ore_reach;
        // ore_iron_first: with no iron left in the 400 m field, rock weapons wait for a look at the iron out to
        // 2 x ore_reach m even while rock remains within it (else rock mode holds until that rock is gone too).
        boolean near_rock = ore_reach > 0 && rock_weapons && countReachable(map.getRocks(), 400) > 0;
        boolean iron_first = near_rock && iron_left == 0 && ai.strategy().ore_iron_first;
        if (ore_reach > 0 && (rock_weapons ? !near_rock || iron_first : iron_left == 0)) {
            // ore_reach: the ore these weapons need is beyond the 400 m field. Iron within it comes first, then iron
            // and then rock out to 2 x ore_reach m.
            if (rock_weapons && iron_left > 0) {
                rock_weapons = false;
                ai.aiLog().count("ore_iron_fallback"); // plan ticks (3 s)
            } else {
                DistanceField far = oreField(armory);
                int far_iron = countReachable(far, map.getIron(), 2 * ore_reach);
                if (far_iron > 0) {
                    rock_weapons = false;
                    ore_far = true;
                    iron_cycle = SitePlanner.gatherTicks(far, map.getIron(), 30, 10, 2 * ore_reach, harvest);
                    ai.aiLog().count("ore_far_iron"); // plan ticks (3 s)
                    if (iron_first) {
                        ai.aiLog().count("ore_iron_first"); // plan ticks (3 s) far iron came before near rock
                        if (ai.logging() && ai.periodDue(iron_first_log, 3000f)) { // 60 s
                            iron_first_log = ai.now();
                            ai.log(String.format(
                                    "ore_iron_first: no iron within 400 m; %d iron supplies out to %d m" + " (cycle %.0f s) before the rock within it",
                                    far_iron, 2 * ore_reach,
                                    GauntletAI.seconds(iron_cycle)));
                        }
                    }
                } else if (iron_first) {
                    // ore_iron_first: no iron out there either; rock from within the 400 m field, as without it
                    ai.aiLog().count("ore_iron_first_none"); // plan ticks (3 s)
                } else if (countReachable(far, map.getRocks(), 2 * ore_reach) > 0) {
                    rock_weapons = true;
                    ore_far = true;
                    ai.aiLog().count("ore_far_rock"); // plan ticks (3 s)
                } else {
                    ai.aiLog().count("ore_none"); // plan ticks (3 s)
                }
            }
        }
        float ore_cycle = rock_weapons ? SitePlanner.gatherTicks(ore_far ? oreField(armory) : armory_field,
                map.getRocks(), 30, 10, ore_far ? 2 * ore_reach : 240, harvest) : iron_cycle;
        rock_cycle = rock_weapons ? ore_cycle : 0f;
        float work = rock_weapons ? IRON_WORK_TICKS / 2 : IRON_WORK_TICKS;

        int workers = armory.getUnitContainer().getNumSupplies();
        int g_tree = intel.countGatherers(PeonState.GATHER_TREE, armory);
        int g_iron = intel.countGatherers(PeonState.GATHER_IRON, armory);
        int g_rock = intel.countGatherers(PeonState.GATHER_ROCK, armory);
        int g_chicken = intel.countGatherers(PeonState.GATHER_CHICKEN, armory);
        int transit = intel.countPeons(PeonState.TRANSIT);
        int pool = workers + g_tree + g_iron + g_rock + g_chicken + transit;

        // Chicken warriors need one chicken, one rock and more work; hunt chickens once the base runs.
        // A chicken warrior is worth two iron ones, so chickens are hunted as soon as there are peons to spare.
        want_chicken = 0;
        int chickens_left = countChickens();
        Strategy strategy = ai.strategy();
        if (ai.owner().canUseRubber() && ai.now() > strategy.chicken_ticks && pool > 10 && chickens_left > 0)
            want_chicken = Math.min(Math.min(strategy.chicken_hunters, chickens_left),
                    2 + pool / strategy.chicken_pool_div);
        int rock_stock = armory.getSupplyContainer(RockSupply.class).getNumSupplies();
        int chicken_stock = armory.getSupplyContainer(RubberSupply.class).getNumSupplies();
        want_rock = (want_chicken > 0 || chicken_stock > 0) && rock_stock < 6 ? 1 + want_chicken / 4 : 0;
        Strategy st = ai.strategy();
        // rock_surge: the filler's gatherers come out of the armory's idle workers below, not out of the iron share.
        if (!st.rock_surge && rock_filler && !rock_weapons && rock_stock < st.rock_filler_stock)
            want_rock += Math.max(2, armory_workers / st.rock_filler_div);
        pool -= want_chicken + want_rock;

        float per_weapon = work + 2 * tree_cycle + ore_cycle;
        float x = Math.max(0, pool) / per_weapon;
        int tree_stock = armory.getSupplyContainer(TreeSupply.class).getNumSupplies();
        int ore_stock = armory.getSupplyContainer(rock_weapons ? RockSupply.class : IronSupply.class).getNumSupplies();
        float tree_adj = tree_stock > 40 ? .35f : tree_stock > 20 ? .7f : tree_stock < 6 ? 1.15f : 1f;
        float ore_adj = ore_stock > 20 ? .35f : ore_stock > 10 ? .7f : ore_stock < 3 ? 1.15f : 1f;
        want_tree = Math.round(2 * tree_cycle * x * tree_adj);
        int want_ore = Math.round(ore_cycle * x * ore_adj);
        if (pool >= 3) {
            want_tree = Math.max(1, want_tree);
            want_ore = Math.max(1, want_ore);
        }
        float rock_share = ai.strategy().rock_share;
        if (rock_weapons) {
            want_rock += want_ore;
            want_iron = 0;
        } else if (rock_share > 0f && want_ore > 0) {
            // A second stream: rock axes cost half the work of iron ones and rock is twice as plentiful (sweep's
            // ironfrac 0.9 beat 1.0 at N=3, 56 vs 45 of 200).
            int rock = Math.round(want_ore * rock_share);
            want_rock += rock;
            want_iron = want_ore - rock;
        } else {
            want_iron = want_ore;
        }
        want_workers = Math.max(2, pool - want_tree - want_ore);
        // rock_stream: once a unit of iron costs more gatherer-ticks than rock_stream_iron_ticks (measured, not
        // modelled: the model's iron cycle is 1.5-5 times too optimistic after 10 min), the armory's waiting workers
        // go for rock and the wood it needs (a rock axe: 2 wood, 1 rock, 40 man-s, 0.6 of an iron warrior); beyond
        // 150 s per iron unit the iron gatherers are capped and sent for rock too.
        rock_stream_on = st.rock_stream && ai.now() >= st.rock_stream_ticks
                && iron_ticks > st.rock_stream_iron_ticks && !rock_weapons;
        if (rock_stream_on) {
            int add = Math.min(st.rock_stream_max, Math.max(0, Math.min(want_workers - 4, armory_workers / 2)));
            if (iron_ticks > 7500f && want_iron > 6) { // 150 s
                add += want_iron - 6;
                want_iron = 6;
            }
            int wood = add / 3;
            want_rock += add - wood;
            want_tree += wood;
            want_workers = Math.max(2, want_workers - add);
            ai.aiLog().count("rock_stream");
        }
        if (st.rock_surge && rock_filler && !rock_weapons && rock_stock < st.rock_filler_stock) {
            // The armory's workers wait for iron: send some of them for rock, the plentiful ore (rock axes take half
            // the work of iron ones).
            int filler = Math.min(want_workers - 2, Math.max(2, armory_workers / st.rock_filler_div));
            if (filler > 0) {
                want_rock += filler;
                want_workers -= filler;
            }
        }
        if (st.reloc_lock_ticks > 0)
            trackLock(armory);
    }

    private int countReachable(@NonNull List<? extends Supply> supplies, int max_meters) {
        return armory_field == null ? 0 : countReachable(armory_field, supplies, max_meters);
    }

    private static int countReachable(@NonNull DistanceField field, @NonNull List<? extends Supply> supplies,
            int max_meters) {
        int n = 0;
        for (Supply s : supplies) {
            if (s.isEmpty())
                continue;
            if (field.getAround(s.getGridX(), s.getGridY(), 1) <= max_meters)
                n++;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Peon allocation

    private void allocatePeons() {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        List<Unit> free = new ArrayList<>();
        List<Unit> transit = new ArrayList<>();
        boolean sortie = ai.strategy().sortie_ratio > 0f;
        for (Unit peon : intel.peons) {
            PeonState s = intel.peon_states.get(peon);
            // sortie_ratio: peons out fighting by their armory are the military's until the sortie ends
            if (sortie && ai.military().inSortie(peon))
                continue;
            if (s == PeonState.IDLE)
                free.add(peon);
            else if (s == PeonState.TRANSIT)
                transit.add(peon);
        }
        if (scout != null && (scout.isDead() || !scoutHasWork()))
            scout = scout.isDead() ? null : scout;

        // 1. Construction.
        boolean wood_drop = ai.strategy().tower_wood_drop && ai.now() >= ai.strategy().tower_wood_ticks;
        List<Unit> carriers = new ArrayList<>();
        if (wood_drop)
            // tower_wood_drop: idle peons carrying wood are kept for tower sites with no trees by them (the quarters
            // projects come first in the loop below)
            for (Iterator<Unit> it = free.iterator(); it.hasNext();) {
                Unit u = it.next();
                if (carriesWood(u)) {
                    it.remove();
                    carriers.add(u);
                }
            }
        for (Project p : projects) {
            // retire: no builders for a stalled site being razed.
            if (!p.isPlaced() || retire.isDoomed(p.building))
                continue;
            if (wood_drop)
                dropWood(p, carriers);
            int need = buildersWanted(p) - builderCount(p.building);
            if (need <= 0)
                continue;
            if (ai.strategy().tower_cooldown && p.type == Race.BUILDING_TOWER && awakeEnemyNear(p.site.x, p.site.y,
                    12)) {
                ai.aiLog().count("tower_builders_held");
                continue;
            }
            List<Unit> chosen = new ArrayList<>();
            takeNearest(free, chosen, need, p.site.x, p.site.y);
            takeNearest(transit, chosen, need - chosen.size(), p.site.x, p.site.y);
            if (chosen.size() < need && (p.type == Race.BUILDING_ARMORY || p.first))
                takeGatherers(chosen, need - chosen.size(), p.site.x, p.site.y);
            // reloc: a hop's builders may come out of the primary too, as expand_under_threat's do; reloc_lock: a lock
            // move's out of the locked armory.
            if ((ai.strategy().expand_under_threat || p.reloc || p.lock) && p == expansion_project
                    && chosen.size() < need && armory != null && armory.isComplete() && armory != p.building) {
                int workers = armory.getUnitContainer().getNumSupplies();
                int pending = armory.getDeployContainer(DeployType.PEON).getNumSupplies();
                // weapon_reserve: the workers for the weapons held back stay in too
                int stay = Math.max(want_workers, weaponReserveHeld(armory));
                if (workers > stay + 5 && pending == 0) {
                    ai.owner().deployUnits(armory, DeployType.PEON, Math.min(need - chosen.size(), workers - stay));
                    ai.aiLog().count(
                            p.lock ? "lock_builders_deployed" : p.reloc ? "reloc_builders_deployed" : "exp_builders_deployed");
                }
            }
            for (Unit u : chosen) {
                if (u == scout && scoutHasWork())
                    continue;
                order(u, p.building, Action.DEFAULT);
                intel.peon_states.put(u, PeonState.BUILD);
                intel.builder_sites.put(u, p.building);
            }
        }
        free.addAll(carriers);

        // 2. Chieftain training quarters top-up.
        Building trainer = ai.chieftain().trainingQuarters();
        boolean topup_ok = ai.military().baseThreatLevel() == 0 || (ai.strategy().chief_topup_any && trainer != null
                && !ai.military().threatNear(trainer.getGridX(), trainer.getGridY(), 20));
        if (trainer != null && topup_ok && !evacuating.containsKey(trainer)) {
            boolean near = ai.strategy().chief_trainer_near;
            int heading = near ? countSentTo(trainer) : countHeadingTo(trainer);
            int need = ai.strategy().hold_chieftain - trainer.getUnitContainer().getNumSupplies() - heading;
            if (need > 0) {
                List<Unit> chosen = new ArrayList<>();
                takeNearest(free, chosen, need, trainer.getGridX(), trainer.getGridY());
                List<Unit> walkers = transit;
                if (near) {
                    walkers = new ArrayList<>();
                    for (Unit u : transit)
                        if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), trainer.getGridX(),
                                trainer.getGridY()) <= 40 * 40)
                            walkers.add(u);
                }
                takeNearest(walkers, chosen, need - chosen.size(), trainer.getGridX(), trainer.getGridY());
                if (walkers != transit)
                    transit.removeAll(chosen);
                order(chosen, trainer, ai.enterAction(trainer));
                if (near)
                    for (Unit u : chosen) {
                        topup_sent.put(u, trainer);
                        ai.aiLog().count("topup_sent");
                    }
            }
        }

        if (ai.strategy().seed_quarters_ticks > 0f)
            seedQuarters(free, armory != null);

        if (armory == null) {
            // Nothing to gather for yet: spare peons speed up a quarters that is below its reserve.
            for (Unit u : free) {
                Building q = MapAnalysis.nearest(intel.quarters, u.getGridX(), u.getGridY());
                if (q != null && !evacuating.containsKey(q) && !retire.isDoomed(q)
                        && q.getUnitContainer().getNumSupplies() + countHeadingTo(q) < holdFor(q))
                    order(u, q, ai.enterAction(q));
            }
            return;
        }

        // 3. Gatherers.
        boolean danger = ai.military().baseThreatLevel() > 1 && (!gatherUnderThreat()
                || ai.military().threatNearEcon(armory.getGridX(), armory.getGridY(), 16));
        danger_idle = ai.strategy().danger_refuge && danger
                && armory.getSupplyContainer(IronSupply.class).getNumSupplies() + armory.getSupplyContainer(
                        RockSupply.class).getNumSupplies() < 2;
        int[] have = {intel.countGatherers(PeonState.GATHER_TREE, armory), intel.countGatherers(PeonState.GATHER_IRON,
                armory), intel.countGatherers(PeonState.GATHER_ROCK, armory), intel.countGatherers(
                        PeonState.GATHER_CHICKEN, armory)};
        // relink_guard: the gatherers of an old armory guarded against recalls (relinkedRecently) stay there, so they
        // count towards want too: else more are sent for as long as the engine keeps re-linking. Its pre-switch
        // workforce is gone by then (the guard starts after a first recall). The recall above want still goes by the
        // main armory's own.
        int[] relinked_have = new int[4];
        if (ai.strategy().relink_guard) {
            PeonState[] kinds = {PeonState.GATHER_TREE, PeonState.GATHER_IRON, PeonState.GATHER_ROCK, PeonState.GATHER_CHICKEN};
            for (Building b : relinked.keySet())
                if (b != armory && !b.isDead() && relinkedRecently(b))
                    for (int t = 0; t < 4; t++)
                        relinked_have[t] += intel.countLinkedGatherers(kinds[t], b);
        }
        int[] want = {want_tree, want_iron, want_rock, want_chicken};
        Class<?>[] types = {TreeSupply.class, IronSupply.class, RockSupply.class, RubberSupply.class};
        rebuildSupplyLoad();
        int deploy_for_gathering = 0;
        // raid_evac: no gatherer goes out for an armory emptied ahead of a wave (its evacuees would walk back to it).
        boolean raid_out = raidEvacuating(armory);
        boolean probe = ai.strategy().gather_probe;
        for (int t = 0; t < 4; t++) {
            int need = danger || raid_out ? 0 : want[t] - have[t] - relinked_have[t];
            while (need > 0) {
                boolean from_transit = false;
                Unit u = free.isEmpty() ? null : free.removeFirst();
                if (u == null && !transit.isEmpty()) {
                    u = transit.removeFirst();
                    from_transit = true;
                }
                if (u == null) {
                    // gather_probe: workers come out of the armory only for a supply there is to gather; else they
                    // walk out, find nothing and walk back in, every economy tick.
                    if (!probe || pickSupply(types[t], armory, null) != null)
                        deploy_for_gathering += need;
                    else
                        ai.aiLog().count("deploy_gather_skipped"); // economy ticks (1 s)
                    break;
                }
                if (!sendGatherer(u, types[t], armory)) {
                    ai.aiLog().count("gather_pick_failed"); // economy ticks (1 s) with a peon left unordered
                    // gather_probe: the peon goes on to the next use (another supply or the armory) instead of
                    // standing where it is
                    if (probe) {
                        if (from_transit)
                            transit.addFirst(u);
                        else
                            free.addFirst(u);
                    }
                    break;
                }
                need--;
            }
            if (!danger && have[t] > want[t] + 1)
                ai.owner().recallGatherers(armory, supplyClass(types[t]), have[t] - want[t]);
        }
        retargetGatherers(armory);
        unstickGatherers(armory);
        if (ai.strategy().unstick_builders_ticks > 0f)
            unstickBuilders();
        int workers = armory.getUnitContainer().getNumSupplies();
        int pending = armory.getDeployContainer(DeployType.PEON).getNumSupplies();
        // weapon_reserve: the workers for the weapons held back stay in too
        int stay = Math.max(3, weaponReserveHeld(armory));
        if (deploy_for_gathering > 0 && workers > stay && pending == 0) {
            ai.owner().deployUnits(armory, DeployType.PEON, Math.min(deploy_for_gathering, workers - stay));
            ai.aiLog().count("deploy_gather"); // deploy orders for gatherers
        }

        // 4. Everyone else works in the armory, unless it is being emptied: then in another armory, or they wait.
        Building work = armory;
        if (danger_idle && !free.isEmpty()) {
            refuge(free, armory);
            return;
        }
        // sortie_ratio: likewise while its peons are out fighting by it (whoever went in would come straight out).
        if (evacuating.containsKey(armory) || (sortie && ai.military().isSortie(armory))) {
            work = null;
            for (Building a : intel.armories)
                if (a != armory && !a.isDead() && a.isComplete() && !evacuating.containsKey(a) && !retire.isDoomed(a)
                        && !(sortie && ai.military().isSortie(a)))
                    work = a;
            // raid_evac: the evacuees (and every other free peon) go where the evacuation sent them.
            RaidEvac ev = raid_out ? raid_evacs.get(armory) : null;
            Building refuge = ev != null ? ev.refuge : null;
            Building shelter = ev != null ? ev.shelter : null;
            boolean sheltered = shelter != null && !shelter.isDead() && !evacuating.containsKey(shelter);
            if (refuge != null && !refuge.isDead() && !evacuating.containsKey(refuge)) {
                work = refuge;
                for (int i = 0; i < free.size(); i++)
                    ai.aiLog().count("raid_evac_refuged");
            } else if (shelter != null && sheltered && !free.isEmpty()) {
                order(free, shelter, ai.enterAction(shelter));
                holdEvacuees(shelter, free.size());
                for (int i = 0; i < free.size(); i++)
                    ai.aiLog().count("raid_evac_sheltered");
                return;
            }
        }
        if (bank_active && work == armory && !free.isEmpty()) {
            // bank_guard: the armory takes what its cap has room for, the rest waits in the reserve quarters.
            List<Unit> in = new ArrayList<>();
            takeNearest(free, in, Math.max(0, bank_room), armory.getGridX(), armory.getGridY());
            order(in, armory, ai.enterAction(armory));
            bank_room -= in.size();
            if (hasUnplacedProject())
                // choosePlacer needs an idle, walking or tree-gathering peon: two stay unordered
                for (int k = 0; k < 2 && !free.isEmpty(); k++)
                    free.removeFirst();
            Building q = reserveQuarters();
            if (q == null) {
                order(free, armory, ai.enterAction(armory));
                for (int i = 0; i < free.size(); i++)
                    ai.aiLog().count("bank_reserve_none");
            } else {
                order(free, q, ai.enterAction(q));
                bank_reserve.merge(q, free.size(), Integer::sum);
                for (int i = 0; i < free.size(); i++) {
                    ai.aiLog().count("bank_reserve");
                    if (bank_raid)
                        ai.aiLog().count("raid_bank_reserve");
                }
            }
            return;
        }
        if (work != null)
            for (Unit u : free)
                order(u, work, Action.DEFAULT);
    }

    /** Whether the peon carries wood: a transporter out of an armory, or a builder whose site stands or fell. */
    private static boolean carriesWood(@NonNull Unit u) {
        UnitSupplyContainer c = u.getSupplyContainer();
        return c != null && c.getSupplyType() == TreeSupply.class && c.getNumSupplies() > 0;
    }

    /**
     * tower_wood_drop: a placed tower site with at most tower_wood_trees trees within 7 cells gets the idle peons
     * carrying wood nearest it, one per missing piece (5 HP each) its builders do not carry yet. When none is left and
     * no threat is within 16 cells of the site, the nearest complete armory within tower_wood_reach cells that can
     * spare wood now sends it with its transport-wood deploy: DeployContainer.orderSupply takes the wood and workers
     * when ordered, createTransporters gives each peon one piece, they come out idle by the armory (no rally point) and
     * the next round sends them to the site, where RepairController builds with the carried piece before it walks for
     * more. An armory keeps tower_wood_reserve wood and half its workers (at least 4, and at least those kept in for
     * weapon_reserve) and orders nothing while a peon deploy of its own is pending; a project gets at most
     * tower_wood_max pieces.
     */
    private void dropWood(@NonNull Project p, @NonNull List<@NonNull Unit> carriers) {
        Building site = p.building;
        if (site == null || p.type != Race.BUILDING_TOWER || p.forward || p.sniper)
            return;
        Strategy st = ai.strategy();
        if (ai.map().treesAround(p.site.x, p.site.y, 7) > st.tower_wood_trees)
            return;
        Intel intel = ai.intel();
        int carried = 0;
        for (Map.Entry<Unit, Building> e : intel.builder_sites.entrySet())
            if (e.getValue() == site && carriesWood(e.getKey()))
                carried++;
        int missing = (site.getTemplate().getMaxHitPoints() - site.getHitPoints() + 4) / 5 - carried;
        if (missing <= 0)
            return;
        List<Unit> chosen = new ArrayList<>();
        takeNearest(carriers, chosen, missing, p.site.x, p.site.y);
        for (Unit u : chosen) {
            order(u, site, Action.DEFAULT);
            intel.peon_states.put(u, PeonState.BUILD);
            intel.builder_sites.put(u, site);
            ai.aiLog().count("tower_wood_taken");
        }
        missing -= chosen.size();
        if (!chosen.isEmpty()) {
            int n = chosen.size();
            int left = missing;
            ai.aiLog().log("WOOD", () -> n + " carriers to " + p.describe() + ", " + left + " pieces still missing");
        }
        // A builder that has just put its piece in holds nothing while its 5 HP still come in over 5 s, so the count
        // above runs high for a moment: no new deploy within 6 s of the last one for this project.
        if (missing <= 0 || !carriers.isEmpty() || p.wood_sent >= st.tower_wood_max
                || !ai.periodDue(p.wood_last, 300f) || ai.military().threatNearEcon(p.site.x, p.site.y, 16))
            return;
        orderWood(p, missing);
    }

    /**
     * tower_wood_drop, with no idle carrier left for the project: the nearest armory within tower_wood_reach that can
     * spare wood and workers now deploys up to `missing` wood transporters (the next round sends them to the site),
     * else the reason none could is counted.
     */
    private void orderWood(@NonNull Project p, int missing) {
        Strategy st = ai.strategy();
        // The nearest armory within reach that can spare wood and workers now: the nearest one may be an old armory
        // the economy has moved its workers out of, with a primary one close behind (smoke-wood s2001).
        Building armory = null;
        int reach2 = st.tower_wood_reach * st.tower_wood_reach;
        int best = Integer.MAX_VALUE;
        int k = 0;
        int wood = 0;
        int workers = 0;
        String why = "tower_wood_noarmory";
        int why_d = Integer.MAX_VALUE;
        for (Building a : ai.intel().armories) {
            if (a.isDead() || !a.isComplete() || evacuating.containsKey(a))
                continue;
            int d = MapAnalysis.dist2(a.getGridX(), a.getGridY(), p.site.x, p.site.y);
            if (d > reach2 || d >= best)
                continue;
            String no = null;
            int a_workers = a.getUnitContainer().getNumSupplies();
            int a_wood = a.getSupplyContainer(TreeSupply.class).getNumSupplies();
            // weapon_reserve: the workers for the weapons held back stay in too
            int n = Math.min(Math.min(missing, st.tower_wood_max - p.wood_sent), Math.min(
                    a_wood - st.tower_wood_reserve,
                    a_workers - Math.max(Math.max(4, a_workers / 2), weaponReserveHeld(a))));
            if (a.getDeployContainer(DeployType.PEON).getNumSupplies() + a.getDeployContainer(
                    DeployType.PEON_TRANSPORT_TREE).getNumSupplies() > 0)
                no = "tower_wood_pending"; // its queue runs: the carriers it sends change what is missing
            else if (a_wood <= st.tower_wood_reserve)
                no = "tower_wood_short_wood";
            else if (n <= 0)
                no = "tower_wood_short_workers";
            if (no != null) {
                if (d < why_d) {
                    why_d = d;
                    why = no;
                }
                continue;
            }
            best = d;
            armory = a;
            k = n;
            wood = a_wood;
            workers = a_workers;
        }
        if (armory == null) {
            ai.aiLog().count(why); // economy ticks (1 s)
            return;
        }
        ai.owner().deployUnits(armory, DeployType.PEON_TRANSPORT_TREE, k);
        p.wood_sent += k;
        p.wood_last = ai.now();
        ai.aiLog().count("tower_wood_drop");
        for (int i = 0; i < k; i++)
            ai.aiLog().count("tower_wood_units");
        int d = (int) Math.sqrt(best);
        int m = missing;
        Building from = armory;
        int out = k;
        int had_wood = wood;
        int had_workers = workers;
        ai.aiLog().log("WOOD",
                () -> out + " wood out of the armory at " + from.getGridX() + "," + from.getGridY() + " (" + d + " cells, " + had_wood + " wood, " + had_workers + " workers) for " + p.describe() + ", " + m + " missing, " + p.wood_sent + " sent");
    }

    /** danger_refuge: the main armory is threatened and has no ore to forge (set each economy tick). */
    private boolean danger_idle;
    private @Nullable Building refuge_armory;
    private float refuge_until = -1f;

    /**
     * danger_refuge: spare peons go to the complete armory farthest from the threat with no threat within 25 cells,
     * else each to its nearest quarters with no threat within 16 cells, else they stay where they are.
     */
    private void refuge(@NonNull List<@NonNull Unit> free, @NonNull Building armory) {
        Intel intel = ai.intel();
        Military military = ai.military();
        int tx = military.threatX();
        int ty = military.threatY();
        Building work = null;
        int best = -1;
        for (Building a : intel.armories) {
            if (a == armory || a.isDead() || !a.isComplete() || evacuating.containsKey(a)
                    || military.threatNear(a.getGridX(), a.getGridY(), 25) || retire.isDoomed(a))
                continue;
            int d = MapAnalysis.dist2(a.getGridX(), a.getGridY(), tx, ty);
            if (d > best) {
                best = d;
                work = a;
            }
        }
        if (work != null) {
            refuge_armory = work;
            refuge_until = ai.now() + 1500f; // 30 s
            for (Unit u : free) {
                order(u, work, Action.DEFAULT);
                ai.aiLog().count("refuge_armory");
            }
            return;
        }
        for (Unit u : free) {
            Building shelter = null;
            int best_d = Integer.MAX_VALUE;
            for (Building q : intel.quarters) {
                if (q.isDead() || evacuating.containsKey(q) || military.threatNearEcon(q.getGridX(), q.getGridY(), 16)
                        || retire.isDoomed(q))
                    continue;
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), q.getGridX(), q.getGridY());
                if (d < best_d) {
                    best_d = d;
                    shelter = q;
                }
            }
            if (shelter != null) {
                order(u, shelter, ai.enterAction(shelter));
                ai.aiLog().count("refuge_quarters");
            }
        }
    }

    private boolean scoutHasWork() {
        for (Project p : projects)
            if (p.use_scout && !p.isPlaced())
                return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Class<? extends Supply> supplyClass(@NonNull Class<?> c) {
        return (Class<? extends Supply>) c;
    }

    /** chief_trainer_near: peons ordered into the chieftain's training quarters, and which one. */
    private final Map<@NonNull Unit, @NonNull Building> topup_sent = new LinkedHashMap<>();

    /** seed_quarters: when each quarters was first seen complete, and the idle peons sent into one (and when). */
    private final Map<@NonNull Building, Float> quarters_done = new LinkedHashMap<>();
    private final Map<@NonNull Unit, @NonNull Building> seed_sent = new LinkedHashMap<>();
    private final Map<@NonNull Unit, Float> seed_sent_time = new LinkedHashMap<>();

    /**
     * seed_quarters: a quarters finished less than seed_quarters_ticks ago that holds fewer than its hold (counting
     * peons already sent) takes idle peons within seed_quarters_reach cells, nearest first. Completion times are
     * tracked from the start; seeding waits for the first armory (before it, idle peons fill quarters anyway).
     */
    private void seedQuarters(@NonNull List<@NonNull Unit> free, boolean seed) {
        Strategy st = ai.strategy();
        float now = ai.now();
        seed_sent.keySet().removeIf(u -> u.isDead() || now - seed_sent_time.getOrDefault(u, now) > 1500f); // 30 s
        seed_sent_time.keySet().retainAll(seed_sent.keySet());
        int reach2 = st.seed_quarters_reach * st.seed_quarters_reach;
        for (Building q : ai.intel().quarters) {
            if (q.isDead() || !q.isComplete() || evacuating.containsKey(q))
                continue;
            float done = quarters_done.computeIfAbsent(q, b -> now);
            if (!seed || now - done > st.seed_quarters_ticks || retire.isDoomed(q))
                continue;
            int sent = 0;
            for (Building b : seed_sent.values())
                if (b == q)
                    sent++;
            int need = holdFor(q) - q.getUnitContainer().getNumSupplies() - sent;
            if (need <= 0)
                continue;
            List<Unit> near = new ArrayList<>();
            for (Unit u : free)
                if (u != scout && MapAnalysis.dist2(u.getGridX(), u.getGridY(), q.getGridX(), q.getGridY()) <= reach2)
                    near.add(u);
            List<Unit> chosen = new ArrayList<>();
            takeNearest(near, chosen, need, q.getGridX(), q.getGridY());
            if (chosen.isEmpty())
                continue;
            free.removeAll(chosen);
            order(chosen, q, ai.enterAction(q));
            for (Unit u : chosen) {
                seed_sent.put(u, q);
                seed_sent_time.put(u, now);
                ai.aiLog().count("seed_quarters_sent");
            }
        }
    }

    /** chief_trainer_near: peons we sent to this trainer that are still walking there. */
    private int countSentTo(@NonNull Building trainer) {
        topup_sent.entrySet().removeIf(e -> e.getKey().isDead() || e.getValue() != trainer
                || ai.intel().peon_states.get(e.getKey()) != PeonState.TRANSIT);
        return topup_sent.size();
    }

    private int countHeadingTo(@NonNull Building building) {
        int n = 0;
        for (Unit peon : ai.intel().peons) {
            if (ai.intel().peon_states.get(peon) != PeonState.TRANSIT)
                continue;
            if (MapAnalysis.dist2(peon.getGridX(), peon.getGridY(), building.getGridX(), building.getGridY()) < 900)
                n++;
        }
        return n;
    }

    private void takeNearest(@NonNull List<@NonNull Unit> from, @NonNull List<@NonNull Unit> into, int n, int x,
            int y) {
        for (int k = 0; k < n && !from.isEmpty(); k++) {
            Unit best = null;
            int best_d = Integer.MAX_VALUE;
            for (Unit u : from) {
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y);
                if (d < best_d) {
                    best_d = d;
                    best = u;
                }
            }
            if (best == null)
                return;
            from.remove(best);
            into.add(best);
        }
    }

    private void takeGatherers(@NonNull List<@NonNull Unit> into, int n, int x, int y) {
        List<Unit> gatherers = new ArrayList<>();
        for (Unit peon : ai.intel().peons) {
            PeonState s = ai.intel().peon_states.get(peon);
            if (s == PeonState.GATHER_TREE || s == PeonState.GATHER_IRON || s == PeonState.GATHER_ROCK)
                gatherers.add(peon);
        }
        if (ai.strategy().walk_select > 0 && n > 0 && !gatherers.isEmpty()) {
            takeWalkNearest(gatherers, into, n, x, y, ai.strategy().walk_select);
            return;
        }
        takeNearest(gatherers, into, n, x, y);
    }

    /**
     * walk_select: the n units with the shortest walk to (x, y), within max_walk meters; a gatherer across a cliff is
     * near in a straight line but may walk into a dead end or a pass it deadlocks in (jam-logs s9).
     */
    private void takeWalkNearest(@NonNull List<@NonNull Unit> from, @NonNull List<@NonNull Unit> into, int n, int x,
            int y, int max_walk) {
        DistanceField field = ai.map().computeField(x, y, max_walk);
        for (int k = 0; k < n && !from.isEmpty(); k++) {
            Unit best = null;
            int best_d = Integer.MAX_VALUE;
            for (Unit u : from) {
                int d = field.getAround(u.getGridX(), u.getGridY(), 1);
                if (d != DistanceField.UNREACHABLE && d < best_d) {
                    best_d = d;
                    best = u;
                }
            }
            if (best == null)
                return;
            from.remove(best);
            into.add(best);
        }
    }

    private void rebuildSupplyLoad() {
        supply_load.clear();
        Intel intel = ai.intel();
        for (Iterator<Map.Entry<Unit, Supply>> it = gather_targets.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Unit, Supply> e = it.next();
            Unit u = e.getKey();
            PeonState s = intel.peon_states.get(u);
            if (u.isDead() || s == null || !isGathering(s)) {
                it.remove();
                continue;
            }
            Supply supply = e.getValue();
            if (!supply.isEmpty())
                supply_load.merge(supply, 1, Integer::sum);
        }
    }

    private static boolean isGathering(@NonNull PeonState s) {
        return s == PeonState.GATHER_TREE || s == PeonState.GATHER_IRON || s == PeonState.GATHER_ROCK
                || s == PeonState.GATHER_CHICKEN;
    }

    private boolean sendGatherer(@NonNull Unit peon, @NonNull Class<?> type, @NonNull Building armory) {
        boolean home = ai.strategy().gather_home && type != RubberSupply.class;
        // relink_guard: the armory the peon gathered for before this order. The engine links a re-sent gatherer of an
        // old armory straight back to it when that is the supply's nearest, which is no new re-link.
        Building was = ai.strategy().relink_guard
                && peon.getPrimaryController() instanceof GatherController<?> g0 ? g0.getAssignedBuilding() : null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Supply supply = pickSupply(type, armory, peon);
            if (supply == null)
                return false;
            ai.owner().setTarget(Selectable.newArray(peon), supply, Action.DEFAULT, false);
            // gather_home: the engine delivers to the supply's nearest armory (Unit.nearestSupplyBuilding), not to the
            // one we gather for. A supply nearer an old armory sends its load there, drainSecondary recalls the
            // gatherer every 10 s and sends it back to the main armory, which picks the same supply: peons walk to
            // and fro between the armories. Such a supply is left to the old armory for a minute.
            if (home && peon.getPrimaryController() instanceof GatherController<?> g && g.getAssignedBuilding() != null
                    && g.getAssignedBuilding() != armory && attempt < 2) {
                other_armory_supplies.put(supply, ai.now() + 3000f); // 60 s
                ai.aiLog().count("gather_other_armory");
                continue;
            }
            // relink_guard: the gatherer stays where the engine linked it; drainSecondary leaves that armory's
            // gatherers alone for a while.
            if (ai.strategy().relink_guard && peon.getPrimaryController() instanceof GatherController<?> g) {
                Building linked = g.getAssignedBuilding();
                if (linked != null && linked != armory && linked != was && !linked.isDead()
                        && ai.intel().armories.contains(linked))
                    markRelinked(linked);
            }
            gather_targets.put(peon, supply);
            supply_load.merge(supply, 1, Integer::sum);
            return true;
        }
        return false;
    }

    /** gather_home: supplies whose loads would go to another armory than the main one, until the time given. */
    private final Map<@NonNull Supply, Float> other_armory_supplies = new LinkedHashMap<>();

    /**
     * relink_guard: when the engine last linked a gatherer we sent for the main armory (relink_primary) to each other
     * finished armory, from idle, transit or another armory, since that armory's last unguarded recall.
     */
    private final Map<@NonNull Building, Float> relinked = new LinkedHashMap<>();
    /**
     * relink_guard: the other armories drainSecondary has recalled all gatherers of once under relink_primary. Until
     * then an armory is never guarded: the first recall after a switch empties it of its own pre-switch workforce
     * (the switch's tick drains the new armory as a secondary, as choosePrimaryArmory runs before onCompleted, and
     * takes the 10-s recall slot, so the old one's first pass comes after 10 s of re-links).
     */
    private final List<@NonNull Building> relink_recalled = new ArrayList<>();
    private @Nullable Building relink_primary;
    private static final float RELINK_GUARD_TICKS = 1500f; // 30 s

    /** relink_guard: a new main armory starts the marks and the first recalls afresh (also after a switch back). */
    private void syncRelinkPrimary() {
        Building primary = ai.intel().armory();
        if (primary != relink_primary) {
            relinked.clear();
            relink_recalled.clear();
            relink_primary = primary;
        }
    }

    private void markRelinked(@NonNull Building linked) {
        syncRelinkPrimary();
        relinked.keySet().removeIf(Building::isDead);
        relink_recalled.removeIf(Building::isDead);
        relinked.put(linked, ai.now());
        ai.aiLog().count("relink_sent");
    }

    /**
     * relink_guard: whether this armory was recalled once under the current main armory and a gatherer we sent was
     * linked to it since, in the last 30 s.
     */
    private boolean relinkedRecently(@NonNull Building armory) {
        Float since = relinked.get(armory);
        return since != null && relink_primary == ai.intel().armory() && relink_recalled.contains(armory)
                && ai.now() - since < RELINK_GUARD_TICKS;
    }

    /**
     * A gatherer whose load has not changed for 70 seconds is stuck, most often walking to a tree it cannot reach: it
     * is sent to another supply and the old one is avoided for two minutes.
     */
    private void unstickGatherers(@NonNull Building armory) {
        if (!ai.strategy().unstick)
            return;
        Intel intel = ai.intel();
        float now = ai.now();
        gather_progress.keySet().removeIf(Unit::isDead);
        bad_supplies.values().removeIf(until -> until < now);
        other_armory_supplies.values().removeIf(until -> until < now);
        for (Unit peon : intel.peons) {
            PeonState s = intel.peon_states.get(peon);
            Class<?> type = s == PeonState.GATHER_TREE ? TreeSupply.class : s == PeonState.GATHER_IRON ? IronSupply.class : s == PeonState.GATHER_ROCK ? RockSupply.class : null;
            if (type == null) {
                gather_progress.remove(peon);
                continue;
            }
            int amount = peon.getSupplyContainer() != null ? peon.getSupplyContainer().getNumSupplies() : 0;
            float[] seen = gather_progress.get(peon);
            if (seen == null || seen[0] != amount) {
                gather_progress.put(peon, new float[]{amount, now});
                continue;
            }
            // A gatherer on a long walk carries nothing new for a whole trip; only a stall well past it is stuck.
            float trip = type == IronSupply.class ? iron_cycle : type == TreeSupply.class ? tree_cycle : rock_cycle;
            if (now - seen[1] < Math.max(3500f, ai.strategy().stuck_trip_factor * trip)) // 70 s
                continue;
            Supply old = gather_targets.get(peon);
            // ore_reach_fail: a far pick made while the plan saw ore within the 400 m field (no ore_far) walks a longer
            // trip than that field's cycle; a stuck_trip_factor allowance counts its own (else 800 m trips are flagged
            // stuck by the near cycle, their ore avoided and the gatherer re-sent).
            if (old != null && type != TreeSupply.class && ai.strategy().ore_reach_fail
                    && ai.strategy().stuck_trip_factor > 0f
                    && now - seen[1] < ai.strategy().stuck_trip_factor * farTrip(armory, old)) {
                ai.aiLog().count("ore_fail_trip_wait"); // economy ticks (1 s) a far gatherer was not yet stuck
                continue;
            }
            if (old != null)
                bad_supplies.put(old, now + 6000f); // 120 s
            gather_progress.put(peon, new float[]{amount, now});
            unstuck++;
            sendGatherer(peon, type, armory);
        }
        if (unstuck > 0 && now - last_unstuck_log > 3000f) { // 60 s
            last_unstuck_log = now;
            ai.log(unstuck + " stuck gatherers re-sent so far");
        }
    }

    /**
     * ore_reach_fail: game ticks of one trip to ore beyond the main armory's 400 m field (a far pick), by its walk in
     * the 2 x ore_reach m field, or 0 for ore within the 400 m field or beyond both. A per-supply trip, not a far
     * cycle: the nearest ore in the far field is often the near ore the stuck check avoids.
     */
    private float farTrip(@NonNull Building armory, @NonNull Supply s) {
        if (armory_field == null || ai.strategy().ore_reach <= 0
                || armory_field.getAround(s.getGridX(), s.getGridY(), 1) != DistanceField.UNREACHABLE)
            return 0f;
        int d = oreField(armory).getAround(s.getGridX(), s.getGridY(), 1);
        return d == DistanceField.UNREACHABLE ? 0f : SitePlanner.tripTicks(d, ai.strategy().harvest_ticks);
    }

    /** unstick_builders: builders wedged away from their building walk into the nearest armory instead. */
    private void unstickBuilders() {
        Intel intel = ai.intel();
        float now = ai.now();
        builder_cells.keySet().removeIf(u -> u.isDead() || !intel.builder_sites.containsKey(u));
        List<Unit> wedged = new ArrayList<>();
        for (Map.Entry<Unit, Building> e : intel.builder_sites.entrySet()) {
            Unit u = e.getKey();
            Building b = e.getValue();
            float[] seen = builder_cells.get(u);
            if (seen == null || seen[0] != u.getGridX() || seen[1] != u.getGridY()) {
                builder_cells.put(u, new float[]{u.getGridX(), u.getGridY(), now});
                continue;
            }
            if (now - seen[2] < ai.strategy().unstick_builders_ticks || b.isDead()
                    || MapAnalysis.dist2(u.getGridX(), u.getGridY(), b.getGridX(), b.getGridY()) <= 3 * 3)
                continue;
            wedged.add(u);
            builder_cells.remove(u);
        }
        if (wedged.isEmpty())
            return;
        for (Unit u : wedged) {
            Building home = MapAnalysis.nearest(intel.armories, u.getGridX(), u.getGridY());
            if (home != null)
                order(u, home, ai.enterAction(home));
            ai.aiLog().count("builder_unstuck");
        }
        Unit first = wedged.getFirst();
        ai.log(String.format("%d wedged builders around %d,%d sent into the armory", wedged.size(), first.getGridX(),
                first.getGridY()));
    }

    /**
     * Gatherers whose supply ran out walk to whatever is nearest the armory, which piles them onto the same tree.
     * Spread them over the supplies around instead.
     */
    private void retargetGatherers(@NonNull Building armory) {
        Intel intel = ai.intel();
        int moved = 0;
        for (Unit peon : intel.peons) {
            if (moved >= 4)
                return;
            PeonState s = intel.peon_states.get(peon);
            if (s == null || !isGathering(s) || s == PeonState.GATHER_CHICKEN)
                continue;
            // Gatherers still working for an armory that is no longer the main one move over to the new one.
            Building works_for = intel.gather_buildings.get(peon);
            boolean moving_over = works_for != null && works_for != armory && !works_for.isDead()
                    && intel.armories.contains(works_for);
            if (works_for != armory && works_for != null && !moving_over)
                continue;
            // relink_guard: those of a guarded old armory stay with it (the engine would link most back to it, and
            // each re-send spends this tick's four).
            if (moving_over && ai.strategy().relink_guard && relinkedRecently(works_for))
                continue;
            Supply current = gather_targets.get(peon);
            if (!moving_over && current != null && !current.isEmpty())
                continue;
            if (peon.getSupplyContainer() != null && peon.getSupplyContainer().getNumSupplies() > 0)
                continue; // let it drop off first
            Class<?> type = switch (s) {
                case GATHER_TREE -> TreeSupply.class;
                case GATHER_IRON -> IronSupply.class;
                default -> RockSupply.class;
            };
            if (sendGatherer(peon, type, armory))
                moved++;
        }
    }

    /**
     * wood_reach: the main armory's 60-cell tree ring is cut out (set every plan tick), and a failed 60-cell search.
     */
    private boolean wood_starved;
    private float tree_null_until = -1f;
    /** ore_reach_fail: when its decision log line was last written (once a minute at most). */
    private float fail_pick_log = -5000f;

    private @Nullable Supply pickSupply(@NonNull Class<?> type, @NonNull Building armory, @Nullable Unit peon) {
        if (type == RubberSupply.class)
            return pickChicken(armory);
        Strategy st = ai.strategy();
        boolean tree = type == TreeSupply.class;
        int radius = tree ? (wood_starved ? st.wood_reach : 60) : 200;
        Supply best = scanSupplies(type, armory, radius, armory_field);
        // ore_reach_fail: a near pick that found nothing, with no threat or parked blob by any of the ore (ore more
        // than 400 m of walk away, across water or cliffs, or avoided after stuck gatherers), looks farther too.
        boolean fail_far = best == null && !tree && !ore_far && st.ore_reach_fail && !scan_hostile;
        if (best == null && !tree && (ore_far || fail_far) && st.ore_reach > 0) {
            // ore_reach: nothing within the 400 m field; walk to ore up to 2 x ore_reach m away
            int near_why = scan_why;
            best = scanSupplies(type, armory, st.ore_reach, oreField(armory));
            if (best != null && ore_far) {
                ai.aiLog().count("ore_far_pick");
            } else if (best != null) {
                ai.aiLog().count("ore_fail_pick");
                if (ai.logging() && ai.periodDue(fail_pick_log, 3000f)) { // 60 s
                    fail_pick_log = ai.now();
                    ai.log(String.format("ore_reach_fail: near %s pick failed (%s); %d,%d in the %d m field instead",
                            type == IronSupply.class ? "iron" : "rock", SCAN_WHY_NAMES[near_why], best.getGridX(),
                            best.getGridY(), 2 * st.ore_reach));
                }
            }
        }
        if (best == null && tree && !wood_starved && st.wood_reach > 0 && ai.now() >= st.wood_reach_ticks) {
            // wood_reach: nothing within 60 cells; the next plan tick widens the tree cycle too
            tree_null_until = ai.now() + 1500f; // 30 s
            best = scanSupplies(type, armory, st.wood_reach, armory_field);
        }
        if (best != null && tree && MapAnalysis.dist2(armory.getGridX(), armory.getGridY(), best.getGridX(),
                best.getGridY()) > 60 * 60)
            ai.aiLog().count("wood_far");
        return best;
    }

    /**
     * The cheapest supply of the type within radius cells of the armory for a gatherer to walk to, by the walking
     * distances of field, or null.
     */
    private @Nullable Supply scanSupplies(@NonNull Class<?> type, @NonNull Building armory, int radius,
            @Nullable DistanceField field) {
        List<? extends Supply> supplies = type == TreeSupply.class ? ai.map().getTrees() : type == IronSupply.class ? ai.map().getIron() : ai.map().getRocks();
        int max_load = type == TreeSupply.class ? TREE_LOAD : ai.strategy().ore_load;
        float load_penalty = type == TreeSupply.class ? 9f : ai.strategy().ore_load_penalty;
        Supply best = null;
        float best_cost = Float.MAX_VALUE;
        int ax = armory.getGridX();
        int ay = armory.getGridY();
        // Why no supply comes back (counters, and ore_reach_fail): none in radius, all unreachable, near a threat, seen
        // by a parked blob, or avoided after a stuck gatherer.
        int in_radius = 0;
        int unreachable = 0;
        int threat = 0;
        int parked = 0;
        int avoided = 0;
        for (Supply s : supplies) {
            if (s.isEmpty())
                continue;
            int d2 = MapAnalysis.dist2(ax, ay, s.getGridX(), s.getGridY());
            if (d2 > radius * radius)
                continue;
            in_radius++;
            int d = field != null ? field.getAround(s.getGridX(), s.getGridY(), 1) : (int) (Math.sqrt(d2) * 2);
            if (d == DistanceField.UNREACHABLE) {
                unreachable++;
                continue;
            }
            if (ai.military().threatNearEcon(s.getGridX(), s.getGridY(), 14)) {
                threat++;
                continue;
            }
            if (ai.strategy().gather_avoid_parked && seenByParked(s.getGridX(), s.getGridY())) {
                parked++;
                continue;
            }
            Float bad = bad_supplies.get(s);
            if (bad != null && bad > ai.now()) {
                avoided++;
                continue;
            }
            Float other = other_armory_supplies.get(s);
            if (other != null && other > ai.now())
                continue;
            int load = supply_load.getOrDefault(s, 0);
            float cost = d + load * load_penalty + (load >= max_load ? 60f : 0f);
            if (cost < best_cost) {
                best_cost = cost;
                best = s;
            }
        }
        if (best == null) {
            String kind = type == TreeSupply.class ? "tree" : type == IronSupply.class ? "iron" : "rock";
            scan_why = in_radius == 0 ? SCAN_NONE : unreachable == in_radius ? SCAN_UNREACHABLE : avoided > 0 ? SCAN_AVOIDED : threat > 0 ? SCAN_THREAT : parked > 0 ? SCAN_PARKED : SCAN_OTHER;
            scan_hostile = threat > 0 || parked > 0;
            ai.aiLog().count("pick_null_" + kind + "_" + SCAN_WHY_NAMES[scan_why]);
        }
        return best;
    }

    /**
     * ore_reach_fail: whether a threat or a parked blob kept the last empty scanSupplies off any supply, whatever the
     * counter's single reason (avoided outranks both).
     */
    private boolean scan_hostile;
    /** Why the last scanSupplies that came back empty found nothing (ore_reach_fail), with the counters' names. */
    private static final int SCAN_NONE = 0;
    private static final int SCAN_UNREACHABLE = 1;
    private static final int SCAN_AVOIDED = 2;
    private static final int SCAN_THREAT = 3;
    private static final int SCAN_PARKED = 4;
    private static final int SCAN_OTHER = 5;
    private static final String[] SCAN_WHY_NAMES = {"none", "unreachable", "avoided", "threat", "parked", "other"};
    private int scan_why;

    private final List<int @NonNull []> parked_cells = new ArrayList<>();
    private float parked_time = -5000f;

    /**
     * Whether an idle enemy warrior stands within 10 cells (Chebyshev) of (x, y): idle units scan an 8-cell square and
     * hunt what they see, and parked blobs 28-45 cells from our buildings are not base threats (gather_avoid_parked).
     */
    private boolean seenByParked(int x, int y) {
        if (ai.periodDue(parked_time, 50f)) { // once a game second
            parked_time = ai.now();
            parked_cells.clear();
            for (Unit e : ai.intel().enemy_warriors)
                if (!e.isDead() && Intel.isParked(e))
                    parked_cells.add(new int[]{e.getGridX(), e.getGridY()});
        }
        for (int[] c : parked_cells)
            if (Math.abs(c[0] - x) <= 10 && Math.abs(c[1] - y) <= 10)
                return true;
        return false;
    }

    private int countChickens() {
        if (ai.now() - chickens_time > 500f) { // 10 s
            chickens_time = ai.now();
            chickens.clear();
            UnitGrid grid = ai.map().getGrid();
            int size = grid.getGridSize();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    Occupant occ = grid.getOccupant(x, y);
                    if (occ instanceof RubberSupply chicken && !chicken.isEmpty() && !chicken.isHit()
                            && !chickens.contains(chicken))
                        chickens.add(chicken);
                }
            }
        }
        int n = 0;
        for (RubberSupply c : chickens)
            if (!c.isEmpty())
                n++;
        return n;
    }

    private @Nullable Supply pickChicken(@NonNull Building armory) {
        countChickens();
        RubberSupply best = null;
        int best_d = Integer.MAX_VALUE;
        // Why no chicken comes back (counters only): none left, all taken, all by enemies, the nearest too far.
        int left = 0;
        int free = 0;
        for (RubberSupply c : chickens) {
            if (c.isEmpty() || c.isHit())
                continue;
            left++;
            if (supply_load.getOrDefault(c, 0) > 0)
                continue;
            free++;
            if (ai.military().enemyStrengthNear(c.getGridX(), c.getGridY(), 20) > 0)
                continue;
            int d = MapAnalysis.dist2(armory.getGridX(), armory.getGridY(), c.getGridX(), c.getGridY());
            if (d < best_d) {
                best_d = d;
                best = c;
            }
        }
        if (best == null)
            ai.aiLog().count(
                    left == 0 ? "chicken_null_left" : free == 0 ? "chicken_null_taken" : "chicken_null_enemy20");
        int range = ai.strategy().chicken_range;
        if (best != null && best_d > range * range) {
            ai.aiLog().count("chicken_null_range");
            return null;
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Repairs

    private void manageRepairs() {
        Intel intel = ai.intel();
        if (intel.armory() == null)
            return;
        List<Building> damaged = new ArrayList<>();
        for (Building b : intel.armories)
            if (b.isDamaged())
                damaged.add(b);
        for (Building b : intel.towers)
            if (b.isDamaged())
                damaged.add(b);
        for (Building b : intel.quarters)
            if (b.isDamaged())
                damaged.add(b);
        for (Building b : damaged) {
            // retire: nothing we raze on purpose is repaired.
            if (ai.military().threatNearEcon(b.getGridX(), b.getGridY(), 12) || retire.isDoomed(b))
                continue;
            int missing = b.getTemplate().getMaxHitPoints() - b.getHitPoints();
            int want = Math.min(4, 1 + missing / 40);
            int have = builderCount(b);
            if (have >= want)
                continue;
            List<Unit> chosen = new ArrayList<>();
            takeGatherers(chosen, want - have, b.getGridX(), b.getGridY());
            order(chosen, b, Action.GATHER_REPAIR);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    @NonNull
    String debugStatus() {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        StringBuilder sb = new StringBuilder();
        sb.append("Q").append(intel.quarters.size()).append('+').append(intel.quarters_sites.size());
        sb.append(" A").append(intel.armories.size()).append('+').append(intel.armory_sites.size());
        sb.append(" T").append(intel.towers.size()).append('+').append(intel.tower_sites.size());
        sb.append(" proj=").append(projects.size());
        sb.append(String.format(" Is=%.0f Rs=%.0f%s", GauntletAI.seconds(iron_ticks), GauntletAI.seconds(rock_ticks),
                rock_stream_on ? " RS" : ""));
        int held = 0;
        for (Building q : intel.quarters)
            if (!q.isDead())
                held += q.getUnitContainer().getNumSupplies();
        sb.append(" held=").append(held);
        sb.append(" peons=").append(intel.peons.size());
        sb.append(" (idle ").append(intel.countPeons(PeonState.IDLE));
        sb.append(" bld ").append(intel.countPeons(PeonState.BUILD));
        sb.append(" tr ").append(intel.countPeons(PeonState.TRANSIT));
        sb.append(" g ").append(intel.countPeons(PeonState.GATHER_TREE)).append('/').append(want_tree);
        sb.append(',').append(intel.countPeons(PeonState.GATHER_IRON)).append('/').append(want_iron);
        sb.append(',').append(intel.countPeons(PeonState.GATHER_ROCK)).append('/').append(want_rock);
        sb.append(',').append(intel.countPeons(PeonState.GATHER_CHICKEN)).append('/').append(want_chicken);
        sb.append(')');
        if (armory != null && !armory.isDead()) {
            sb.append(" W=").append(armory.getUnitContainer().getNumSupplies()).append('/').append(want_workers);
            sb.append(" res=").append(armory.getSupplyContainer(TreeSupply.class).getNumSupplies());
            sb.append(',').append(armory.getSupplyContainer(RockSupply.class).getNumSupplies());
            sb.append(',').append(armory.getSupplyContainer(IronSupply.class).getNumSupplies());
            sb.append(',').append(armory.getSupplyContainer(RubberSupply.class).getNumSupplies());
            sb.append(" wpn=").append(armory.getSupplyContainer(IronAxeWeapon.class).getNumSupplies());
            sb.append(',').append(armory.getSupplyContainer(RubberAxeWeapon.class).getNumSupplies());
            sb.append(String.format(" cyc=%.0f/%.0f", GauntletAI.seconds(tree_cycle), GauntletAI.seconds(iron_cycle)));
        }
        // Peons in transit: entering near the primary armory, more than 60 cells from it, or transferring.
        int near = 0;
        int far = 0;
        int transfer = 0;
        for (Unit p : intel.peons) {
            if (p.isDead() || intel.peon_states.get(p) != PeonState.TRANSIT)
                continue;
            if (p.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.TransferUnitController)
                transfer++;
            else if (armory != null && MapAnalysis.dist2(armory.getGridX(), armory.getGridY(), p.getGridX(),
                    p.getGridY()) <= 8 * 8)
                near++;
            else if (armory != null && MapAnalysis.dist2(armory.getGridX(), armory.getGridY(), p.getGridX(),
                    p.getGridY()) > 60 * 60)
                far++;
        }
        sb.append(" trx=").append(near).append('/').append(far).append('/').append(transfer);
        return sb.toString();
    }
}
