package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployContainer;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.TransferUnitController;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Peon economy: quarters population flow, routing every free peon to work, armory staffing versus gatherers,
 * weapon production, warrior deploys, chicken hunting, the post-opening quarters boom and armory recovery.
 */
final class EconomyManager {
    enum Phase {
        OPENING,
        BOOM,
        PRODUCE,
        CAP,
        RECOVER
    }

    private static final Class<?>[] GATHER_TYPES = {TreeSupply.class, IronSupply.class, RockSupply.class};
    private static final DeployType[] HARVEST = {DeployType.PEON_HARVEST_TREE, DeployType.PEON_HARVEST_IRON, DeployType.PEON_HARVEST_ROCK};

    private final @NonNull Context ctx;
    private @Nullable Building armory;
    private Phase phase = Phase.OPENING;
    private boolean armory_ever;
    private BuildManager.@Nullable Task recover_task;
    // Expansion: a second armory by fresh trees and ore once the first one's surroundings are mined out.
    private final boolean expansion;
    private final boolean surplus_out;
    /** Unfreeze the armory: gatherer deploys despite a few free peons, fallback gather types, iron-first surplus. */
    private final boolean unfreeze;
    /** Only the deploy-gate part of unfreeze: a few free peons do not hold a large gatherer deficit inside. */
    private final boolean deploy_gate;
    private final int deploy_threat_r;
    /** How much further toward the enemy (frontness) an expansion armory may stand; iron sits toward the centre. */
    private final float expand_front;
    private boolean surplus_rock;
    /** Iron nodes counted as reachable however far they are, so iron flow never collapses. */
    private final int iron_min_nodes;
    private final int rock_min_nodes;
    private BuildManager.@Nullable Task expand_task;
    private float last_expand_check = Float.NEGATIVE_INFINITY;
    /** Former primary armories: their stock becomes warriors, then their workers leave. */
    private final List<Building> draining = new ArrayList<>();
    /** Game time since which no complete armory has been known, or -1. */
    private float armory_missing_since = -1f;
    /** Per gatherer: {carried amount, time it last changed}; no change for STUCK_TIME means stuck. */
    private final java.util.Map<Unit, float[]> gather_probe = new java.util.LinkedHashMap<>();
    private float last_probe = Float.NEGATIVE_INFINITY;
    static final float STUCK_TIME = 120f;
    private BuildManager.@Nullable Task boom_task;
    private float last_recover = Float.NEGATIVE_INFINITY;
    /** While there is no armory and the sites are fully staffed, quarters keep surplus peons breeding inside. */
    private float surplus_until = Float.NEGATIVE_INFINITY;
    private static final int SURPLUS_KEEP = 10;

    // Controller outputs (recomputed every economy tick).
    private final int[] gather_target = new int[3];
    private final int[] gather_now = new int[3];
    private int workers_target;
    private float last_chickens = Float.NEGATIVE_INFINITY;
    /** Set by the military when the base is threatened: deploy everything, keep no production reserve. */
    boolean emergency;
    boolean rock_emergency;
    private boolean rock_weapons;
    /** Resources in stock can feed the weapon makers; then armory workers are the bottleneck. */
    private boolean stocks_ok;
    private int worker_deficit;

    private final int quarters_max;
    private final int n_keep_opening;
    private final int n_keep_boom;
    private final int n_keep_cap;
    private final int cap_margin;
    private final float builder_frac_boom;
    private final float stock_wood;
    private final float stock_iron;
    private final float stock_rock;
    private final float stock_tau;
    private final int hunter_max;
    private final int chicken_dist;
    private final int chicken_dist_far;
    private final float workers_keep_frac;
    private final int builders_boom_site;
    private final int wood_reserve;
    private final float wood_reserve_time;
    private final int iron_reach;
    private final float rock_mix;
    /** Share of iron labour moved to rock warriors while rock gives more strength per labour (0 = off). */
    private final float rock_eff;
    /** Expand for iron alone when trips are long and a site cuts the iron cost by this fraction (0 = off). */
    private final float expand_iron;
    private boolean siege_evac;
    /** Workers left inside a besieged armory when it empties. */
    private final int evac_keep;
    /** Rock axes while under attack with iron out and rock and wood in stock. */
    private boolean rock_fallback;
    private boolean drain_rally;
    private int last_s_wood;
    private int last_s_iron;
    private int last_s_rock;
    /** Boom quarters: always the full builder crew; priority while fewer than four quarters stand. */
    private final boolean qb_fixed;
    private final int qb_prio;
    /** Rock warriors from idle workers when iron has been dry at the cap for iron_dry_delay seconds. */
    private final boolean iron_dry_rock;
    private final float iron_dry_delay;
    private float iron_dry_since = -1f;
    private float iron_wet_since = -1f;
    private boolean iron_dry_active;
    private final float builder_cap;

    EconomyManager(@NonNull Context ctx) {
        this.ctx = ctx;
        Strategy s = ctx.strategy;
        quarters_max = s.i("quartersMax");
        n_keep_opening = s.i("nKeepOpening");
        n_keep_boom = s.i("nKeepBoom");
        n_keep_cap = s.i("nKeepCap");
        cap_margin = s.i("capMargin");
        builder_frac_boom = s.f("builderFracBoom");
        stock_wood = s.f("stockWood");
        stock_iron = s.f("stockIron");
        stock_rock = s.f("stockRock");
        stock_tau = s.f("stockTau");
        hunter_max = s.i("hunterMax");
        chicken_dist = s.i("chickenDist");
        chicken_dist_far = s.i("chickenDistFar");
        workers_keep_frac = s.f("workersKeepFrac");
        builders_boom_site = s.i("buildersBoomSite");
        wood_reserve = s.i("woodReserve");
        expansion = s.b("expansion");
        surplus_out = s.b("surplusOut");
        unfreeze = s.b("unfreeze");
        deploy_gate = s.b("deployGate");
        deploy_threat_r = s.i("deployThreatR");
        expand_front = s.f("expandFront");
        iron_min_nodes = s.i("ironMinNodes");
        rock_min_nodes = s.i("rockMinNodes");
        wood_reserve_time = s.f("woodReserveTime");
        iron_reach = s.i("ironReach");
        rock_mix = s.f("rockMix");
        rock_eff = s.f("rockEff");
        expand_iron = s.f("expandIron");
        siege_evac = s.b("siegeEvac");
        evac_keep = s.i("evacKeep");
        rock_fallback = s.b("rockFallback");
        qb_fixed = s.b("qbFixed");
        qb_prio = s.i("qbPrio");
        iron_dry_rock = s.b("ironDryRock");
        iron_dry_delay = s.f("ironDryDelay");
        builder_cap = s.f("builderCap");
        ctx.resources.reach_limit = iron_reach + 40;
    }

    /** The primary complete armory, or null. Validated on every read: it can die between ticks. */
    @Nullable
    Building armory() {
        if (armory != null && (armory.isDead() || !armory.isComplete()))
            armory = null;
        return armory;
    }

    @NonNull
    Phase phase() {
        return phase;
    }

    int nKeep() {
        // At the cap births stop anyway: peons waiting inside quarters are better off working.
        if (ctx.owner.getUnitCountContainer().getNumSupplies() >= ctx.world.getMaxUnitCount() - 3)
            return Math.min(1, n_keep_cap);
        return switch (phase) {
            case OPENING, RECOVER -> n_keep_opening;
            case BOOM, PRODUCE -> n_keep_boom;
            case CAP -> n_keep_cap;
        };
    }

    /** Economy peons: armory workers plus field peons that are not building, hunting or borrowed. */
    int econPeons() {
        int n = armory != null ? armory.getUnitContainer().getNumSupplies() : 0;
        for (Unit u : ctx.model.me.peons) {
            if (!ctx.hasRole(u))
                n++;
        }
        return n;
    }

    /** True if the peon can be given a new job right now. */
    boolean isFree(@NonNull Unit u) {
        if (ctx.hasRole(u))
            return false;
        WorldModel.Activity a = WorldModel.activityOf(u);
        return switch (a) {
            case IDLE, HUNT -> true;
            case ENTER -> WorldModel.primary(u) instanceof TransferUnitController;
            case BUILD, PLACE -> {
                Building b = WorldModel.buildTarget(u);
                yield b == null || b.isDead() || (b.isComplete() && !b.isDamaged());
            }
            default -> false;
        };
    }

    // -----------------------------------------------------------------------------------------------------

    void tick() {
        updateArmory();
        if (armory != null)
            armory_missing_since = -1f;
        else if (armory_missing_since < 0f)
            armory_missing_since = ctx.now;
        updatePhase();
        quartersFlow();
        if (armory != null) {
            gathererControl();
            weapons();
            deployWarriors();
            armoryDrain();
            if (ctx.now - last_chickens >= 2f) {
                last_chickens = ctx.now;
                chickens();
            }
        }
        cleanupHunters();
        expandTick();
        drainOld();
        unstickGatherers();
        planQuarters();
        dispatch();
    }

    /**
     * A gatherer whose load has not changed for STUCK_TIME is stuck (an unreachable tree the engine keeps
     * walking to, a jam behind our own buildings). The spot is marked bad and the peon gets another job.
     */
    private void unstickGatherers() {
        if (ctx.now - last_probe < 5f)
            return;
        last_probe = ctx.now;
        gather_probe.keySet().removeIf(u -> u.isDead() || ctx.hasRole(u)
                || !(WorldModel.primary(u) instanceof GatherController<?>));
        Building a = armory;
        int respread = 0;
        for (Unit u : ctx.model.me.peons) {
            if (ctx.hasRole(u) || !(WorldModel.primary(u) instanceof GatherController<?> gc))
                continue;
            int carry = u.getSupplyContainer().getNumSupplies();
            // A gatherer whose node ran out falls back on the engine's nearest-supply search, which piles everyone
            // onto the same few trees (or an unreachable one). Give it a node of our choosing instead. Gatherers
            // still delivering to a drained former armory move to the new one.
            Supply cur = gc.getSupply();
            Building drop = gc.getAssignedBuilding();
            boolean old_drop = drop != null && draining.contains(drop);
            if (a != null && carry == 0 && respread < 6
                    && (cur == null || cur.isDead() || cur.isEmpty() || old_drop)
                    && !ctx.orders.recently(u, 4f)) {
                int type = typeIndex(gc.getSupplyType());
                if (type >= 0 && sendGather(u, type, false)) {
                    respread++;
                    gather_probe.put(u, new float[]{carry, ctx.now});
                    continue;
                }
            }
            float[] p = gather_probe.get(u);
            if (p == null || p[0] != carry) {
                gather_probe.put(u, new float[]{carry, ctx.now});
                continue;
            }
            if (ctx.now - p[1] < STUCK_TIME)
                continue;
            gather_probe.remove(u);
            ctx.resources.markBad(u.getGridX(), u.getGridY());
            Supply s = gc.getSupply();
            if (s != null && !s.isDead())
                ctx.resources.markBad(s.getGridX(), s.getGridY());
            int type = typeIndex(gc.getSupplyType());
            ctx.log(() -> "stuck gatherer at " + u.getGridX() + "," + u.getGridY());
            if (a == null) {
                ctx.orders.stop(u);
            } else if (carry > 0 || type < 0 || !sendGather(u, type, false)) {
                ctx.orders.enter(u, a);
            }
        }
    }

    /**
     * A hunter whose chicken is gone and who carries nothing is done. Its rubber GatherController would otherwise
     * send it to the nearest chicken anywhere on the map, so it is explicitly sent into the armory.
     */
    private void cleanupHunters() {
        for (Unit u : ctx.model.me.peons) {
            if (u.isDead() || ctx.roleOf(u) != Context.Role.HUNTER)
                continue;
            Supply target = null;
            boolean gathering = WorldModel.primary(u) instanceof GatherController<?> gc
                    && gc.getSupplyType() == RubberSupply.class;
            if (gathering)
                target = ((GatherController<?>) WorldModel.primary(u)).getSupply();
            boolean carrying = BuildManager.carriesAnything(u);
            if (gathering && (carrying || (target != null && !target.isDead())))
                continue;
            ctx.clearRole(u);
            if (armory() != null && !carrying)
                ctx.orders.enter(u, armory);
            else if (armory() == null)
                ctx.orders.stop(u);
        }
    }

    void updateArmory() {
        if (armory != null && (armory.isDead() || !armory.isComplete()))
            armory = null;
        // A finished expansion armory takes over as the primary one.
        BuildManager.Task et = expand_task;
        if (et != null && et.state == BuildManager.State.DONE) {
            Building nb = et.building;
            expand_task = null;
            if (nb != null && !nb.isDead() && nb.isComplete() && nb != armory) {
                Building old = armory;
                if (old != null)
                    draining.add(old);
                armory = nb;
                armory_ever = true;
                ctx.log(() -> "armory switched to " + nb.getGridX() + "," + nb.getGridY());
            }
        }
        if (armory == null) {
            Building found = null;
            for (Building b : ctx.model.me.armories) {
                if (!b.isDead() && b.isComplete()) {
                    found = b;
                    break;
                }
            }
            // The world model is refreshed every 0.25 s; a site that completed since is known to the build manager.
            if (found == null)
                found = ctx.build.completedBuilding(Race.BUILDING_ARMORY);
            if (found != null) {
                Building b = found;
                armory = b;
                armory_ever = true;
                ctx.log(() -> "armory ready at " + b.getGridX() + "," + b.getGridY());
            }
        }
    }

    private void updatePhase() {
        Phase old = phase;
        if (armory == null) {
            phase = armory_ever ? Phase.RECOVER : Phase.OPENING;
        } else {
            int pop = ctx.owner.getUnitCountContainer().getNumSupplies();
            int cap = ctx.world.getMaxUnitCount();
            if (pop >= cap - cap_margin)
                phase = Phase.CAP;
            else if (ctx.model.me.quarters.size() + ctx.build.openCount(Race.BUILDING_QUARTERS) < quarters_max)
                phase = Phase.BOOM;
            else
                phase = Phase.PRODUCE;
        }
        if (phase != old)
            ctx.log(() -> "econ phase " + old + " -> " + phase);
        // No armory and none planned (lost, or its opening task failed): request one now.
        if (armory == null && ctx.build.openCount(Race.BUILDING_ARMORY) == 0 && !ctx.build.inOpeningPlan())
            recover();
        // A rebuild that is not yet placed is pointless once an armory stands again.
        BuildManager.Task rt = recover_task;
        if (armory != null && rt != null && rt.open() && rt.state != BuildManager.State.PLACED) {
            ctx.build.cancel(rt);
            recover_task = null;
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // Quarters

    private void quartersFlow() {
        Building chief_q = ctx.chief.trainingQuarters();
        int n_keep = nKeep();
        for (Building q : ctx.model.me.quarters) {
            if (q.isDead())
                continue;
            int inside = q.getUnitContainer().getNumSupplies();
            int keep = q == chief_q ? ctx.chief.keepInside() : n_keep;
            if (armory == null && ctx.now < surplus_until)
                keep = Math.max(keep, SURPLUS_KEEP);
            if (ctx.military.quartersThreatened(q))
                keep = 0;
            if (inside > keep)
                ctx.orders.deploy(q, DeployType.PEON, inside - keep);
        }
    }

    private void planQuarters() {
        if (boom_task != null && !boom_task.open())
            boom_task = null;
        // A raised boom priority yields once four quarters stand or the base is raided.
        if (boom_task != null && boom_task.priority > 500
                && (emergency || ctx.towers.hot() || ctx.model.me.quarters.size() >= 4))
            ctx.build.reprioritize(boom_task, 500);
        if (armory == null || boom_task != null)
            return;
        if (phase != Phase.BOOM)
            return;
        if (ctx.build.openCount(Race.BUILDING_QUARTERS) > 0)
            return;
        if (!ctx.owner.canBuild(Race.BUILDING_QUARTERS) || !ctx.towers.slotAvailableForQuarters())
            return;
        int[] site = ctx.build.planBoomQuartersSite(armory);
        if (site == null)
            return;
        int builders = qb_fixed ? builders_boom_site : Math.max(4, Math.min(builders_boom_site, Math.round(
                builder_frac_boom * econPeons())));
        int prio = ctx.model.me.quarters.size() < 4 && !emergency ? qb_prio : 500;
        boom_task = ctx.build.request(Race.BUILDING_QUARTERS, site[0], site[1], prio, builders, "Qb");
        boom_task.full_crew = qb_fixed;
    }

    /**
     * Every 30 s after 6 minutes: when gathering around the armory has become slow (ore or trees mined out), look
     * for a site whose walking-distance cost is at most 0.75x the current one and build a second armory there.
     */
    private void expandTick() {
        if (!expansion || armory == null || ctx.now < 360f || ctx.now - last_expand_check < 30f)
            return;
        last_expand_check = ctx.now;
        BuildManager.Task et = expand_task;
        if (et != null && et.open())
            return;
        if (ctx.model.me.armories.size() != 1 || ctx.build.openCount(Race.BUILDING_ARMORY) > 0
                || ctx.military.isDefending() || !ctx.owner.canBuild(Race.BUILDING_ARMORY)
                || ctx.owner.getBuildingCountContainer().getNumSupplies() + ctx.build.pendingSlots() >= ctx.world.getMaxBuildingCount() - 1)
            return;
        Building a = armory;
        float ti = ctx.resources.tripTime(IronSupply.class, a, 12);
        float tw = ctx.resources.tripTime(TreeSupply.class, a, 20);
        if (ti < 70f && tw < 45f)
            return;
        float cur = ctx.build.armoryCostPath(a.getGridX(), a.getGridY());
        // Near the current base (quarters and towers stay defensible) and not much further toward the enemy.
        int[] site = ctx.build.planArmorySite(a.getGridX(), a.getGridY(), 70);
        if (site == null) {
            last_expand_check = ctx.now + 60f;
            return;
        }
        float cost = ctx.build.armoryCostPath(site[0], site[1]);
        ctx.log(() -> String.format("expansion check: trips iron %.0f wood %.0f, cost here %.0f, best %.0f at %d,%d",
                ti, tw, cur, cost, site[0], site[1]));
        // Far iron is the bottleneck when its trips are long: a site with much cheaper iron (and no worse overall)
        // is worth the move even if the blended cost gains less than a quarter.
        boolean iron_case = false;
        if (expand_iron > 0f && ti >= 100f && cost <= cur) {
            float ci_here = ctx.build.armoryIronCost(a.getGridX(), a.getGridY());
            float ci_site = ctx.build.armoryIronCost(site[0], site[1]);
            iron_case = ci_site <= (1f - expand_iron) * ci_here;
            ctx.log(() -> String.format("expansion iron check: iron cost here %.0f, there %.0f", ci_here, ci_site));
        }
        if ((cost > 0.75f * cur && !iron_case)
                || MapAnalysis.chebyshev(site[0], site[1], a.getGridX(), a.getGridY()) < 20
                || ctx.map.frontness(site[0], site[1]) > Math.min(0.47f,
                        ctx.map.frontness(a.getGridX(), a.getGridY()) + expand_front)) {
            last_expand_check = ctx.now + 60f;
            return;
        }
        expand_task = ctx.build.request(Race.BUILDING_ARMORY, site[0], site[1], 700, 14, "A2");
    }

    /** A former armory turns its stock into warriors, then sends its workers out to the new one. */
    /** Re-reads the parameters that have multi-enemy defaults (see Strategy.setEnemyCount). */
    void readMultiParams() {
        siege_evac = ctx.strategy.b("siegeEvac");
        rock_fallback = ctx.strategy.b("rockFallback");
    }

    /** Peons do not shelter in a besieged armory: it would take them down with it. */
    boolean shelterBlocked(@NonNull Unit u, @NonNull Building b) {
        return siege_evac && b == armory && WorldModel.kindOf(u) == WorldModel.Kind.PEON && ctx.military.armorySieged();
    }

    /**
     * A besieged armory empties toward the rear point: warriors first (deployWarriors, rallying at the defence), then
     * every peon but a few workers. Once out they are dispatched to safe nodes like any free peon.
     */
    private void armoryDrain() {
        Building a = armory;
        if (!siege_evac || a == null)
            return;
        boolean sieged = ctx.military.armorySieged();
        if (!sieged) {
            if (drain_rally) {
                drain_rally = false;
                int[] rally = ctx.military.rallyPoint();
                if (rally != null)
                    ctx.orders.rally(a, rally[0], rally[1]);
                else
                    ctx.orders.clearRally(a);
            }
            return;
        }
        if (ctx.now - ctx.military.armorySiegedSince() < 3f)
            return;
        int[] rear = ctx.military.rearPoint();
        if (rear == null)
            return;
        int queued = 0;
        for (DeployType t : DeployType.values()) {
            DeployContainer c = a.getDeployContainer(t);
            if (c != null)
                queued += c.getNumSupplies();
        }
        if (queued > 0)
            return;
        int weapons = a.getSupplyContainer(RubberAxeWeapon.class).getNumSupplies() + a.getSupplyContainer(
                IronAxeWeapon.class).getNumSupplies() + a.getSupplyContainer(RockAxeWeapon.class).getNumSupplies();
        int inside = a.getUnitContainer().getNumSupplies();
        if (weapons > 0 && inside > 0)
            return;
        int n = inside - evac_keep;
        if (n <= 0)
            return;
        ctx.orders.rally(a, rear[0], rear[1]);
        drain_rally = true;
        ctx.orders.deploy(a, DeployType.PEON, n);
        int k = n;
        ctx.log(() -> "armory drain: " + k + " peons out to " + rear[0] + "," + rear[1]);
    }

    private void drainOld() {
        draining.removeIf(b -> b.isDead() || b == armory);
        ctx.resources.setAvoidDrops(draining.isEmpty() ? List.of() : List.copyOf(draining));
        int[] rally = ctx.military.rallyPoint();
        for (Building old : draining) {
            if (!old.isComplete())
                continue;
            ctx.orders.weapons(old, IronAxeWeapon.class, false);
            ctx.orders.weapons(old, RubberAxeWeapon.class, false);
            ctx.orders.weapons(old, RockAxeWeapon.class, false);
            if (rally != null)
                ctx.orders.rally(old, rally[0], rally[1]);
            int inside = old.getUnitContainer().getNumSupplies();
            int queued = 0;
            for (DeployType t : DeployType.values()) {
                DeployContainer c = old.getDeployContainer(t);
                if (c != null)
                    queued += c.getNumSupplies();
            }
            if (inside <= 0 || queued > 0)
                continue;
            int rub = old.getSupplyContainer(RubberAxeWeapon.class).getNumSupplies();
            int iron = old.getSupplyContainer(IronAxeWeapon.class).getNumSupplies();
            int rock = old.getSupplyContainer(RockAxeWeapon.class).getNumSupplies();
            if (rub > 0) {
                ctx.orders.deploy(old, DeployType.RUBBER_WARRIOR, Math.min(rub, inside));
            } else if (iron > 0) {
                ctx.orders.deploy(old, DeployType.IRON_WARRIOR, Math.min(iron, inside));
            } else if (rock > 0) {
                ctx.orders.deploy(old, DeployType.ROCK_WARRIOR, Math.min(rock, inside));
            } else {
                // Without a rally the peons stand by the old armory and get dispatched from there.
                ctx.orders.clearRally(old);
                ctx.orders.deploy(old, DeployType.PEON, inside);
            }
        }
    }

    private void recover() {
        // The world model is refreshed every 0.25 s and can miss an armory that completed this tick.
        if (armory_missing_since < 0f || ctx.now - armory_missing_since < 2f)
            return;
        if ((recover_task != null && recover_task.open()) || ctx.now - last_recover < 5f)
            return;
        last_recover = ctx.now;
        int[] site = null;
        Building q = ctx.model.me.quarters.isEmpty() ? null : ctx.model.me.quarters.get(0);
        int cx = q != null ? q.getGridX() : ctx.map.home_x;
        int cy = q != null ? q.getGridY() : ctx.map.home_y;
        site = ctx.build.planArmorySite(cx, cy, 40);
        if (site == null)
            site = ctx.build.planArmorySite(ctx.map.home_x, ctx.map.home_y, 80);
        if (site == null)
            return;
        recover_task = ctx.build.request(Race.BUILDING_ARMORY, site[0], site[1], 2000, -1, "A-rebuild");
        ctx.log(() -> "no armory: (re)building");
    }

    // -----------------------------------------------------------------------------------------------------
    // Armory staffing and gatherers

    private int typeIndex(@Nullable Class<?> type) {
        if (type == TreeSupply.class)
            return 0;
        if (type == IronSupply.class)
            return 1;
        if (type == RockSupply.class)
            return 2;
        return -1;
    }

    private void gathererControl() {
        Building a = armory;
        java.util.Arrays.fill(gather_now, 0);
        int free = 0;
        int hunters = 0;
        for (Unit u : ctx.model.me.peons) {
            Context.Role role = ctx.roleOf(u);
            if (role == Context.Role.HUNTER) {
                hunters++;
                continue;
            }
            if (role != null)
                continue;
            if (WorldModel.primary(u) instanceof GatherController<?> gc) {
                int i = typeIndex(gc.getSupplyType());
                if (i >= 0)
                    gather_now[i]++;
            } else if (isFree(u)) {
                free++;
            }
        }
        int inside = a.getUnitContainer().getNumSupplies();
        int e = inside + free + gather_now[0] + gather_now[1] + gather_now[2];

        // Gatherer slots on nodes within reach of the armory limit how much iron and rock can flow; walking
        // farther is slow and exposes gatherers, so surplus labour makes rock warriors instead.
        int rock_slots = ctx.resources.slotsWithin(RockSupply.class, a, iron_reach, rock_min_nodes);
        int iron_slots = ctx.resources.slotsWithin(IronSupply.class, a, iron_reach, iron_min_nodes);
        float tw = ctx.resources.tripTime(TreeSupply.class, a, Math.max(4, gather_now[0]));
        float ti = ctx.resources.tripTime(IronSupply.class, a, Math.max(2, Math.min(gather_now[1], iron_slots)));
        float tr = ctx.resources.tripTime(RockSupply.class, a, Math.max(1, Math.min(gather_now[2], rock_slots)));
        int s_wood = a.getSupplyContainer(TreeSupply.class).getNumSupplies();
        int s_iron = a.getSupplyContainer(IronSupply.class).getNumSupplies();
        int s_rock = a.getSupplyContainer(RockSupply.class).getNumSupplies();
        int s_rub = a.getSupplyContainer(RubberSupply.class).getNumSupplies();

        float lam_i = 80f + 2f * tw + ti;
        float lam_u = 120f + 2f * tw + ti + tr;
        float lam_r = 40f + 2f * tw + tr;
        float w_u = Math.min(e / lam_u, (s_rub + 0.5f * hunters) / 40f);
        float w_i_labor = Math.max(0f, e - w_u * lam_u) / lam_i;
        float w_i = Math.min(w_i_labor, (iron_slots + s_iron / 20f) / ti);
        if (rock_emergency)
            w_i = 0f;
        // Far iron makes an iron warrior cost more labour than its strength is worth next to a rock warrior
        // (0.63 of its strength): move a share of the iron labour to rock while rock is the better buy.
        else if (rock_eff > 0f && CombatModel.weight(WorldModel.Kind.ROCK) / lam_r > 1f / lam_i
                && rock_slots / tr > w_u)
            w_i *= 1f - rock_eff;
        float labor_left = Math.max(0f, e - w_u * lam_u - w_i * lam_i);
        float w_r = 0f;
        if (rock_mix > 0f && (w_i < 0.8f * w_i_labor || rock_emergency))
            w_r = Math.min(rock_mix * labor_left / lam_r, Math.max(0f, rock_slots / tr - w_u));
        // At the cap with iron dry and workers idle inside, the idle labour makes rock warriors (rubber first).
        if (iron_dry_rock) {
            boolean dry = phase == Phase.CAP && s_iron <= 1;
            if (dry) {
                iron_wet_since = -1f;
                if (iron_dry_since < 0f)
                    iron_dry_since = ctx.now;
                if (ctx.now - iron_dry_since > iron_dry_delay)
                    iron_dry_active = true;
            } else {
                if (iron_wet_since < 0f)
                    iron_wet_since = ctx.now;
                // Single wet ticks (a delivery that is spent at once) neither disarm nor restart the timer.
                if (ctx.now - iron_wet_since > 5f)
                    iron_dry_since = -1f;
                if (ctx.now - iron_wet_since > 10f)
                    iron_dry_active = false;
            }
            if (iron_dry_active) {
                float idle = inside - (80f * w_i + 120f * w_u) + Math.max(0f, gather_now[2] - w_u * tr);
                w_r = Math.max(w_r, Math.min(Math.max(0f, rock_slots / tr - w_u), Math.max(0f, idle) / lam_r));
            }
        }
        rock_weapons = w_r > 0.01f;
        float wood_target = stock_wood + (ctx.now >= wood_reserve_time ? wood_reserve : 0) + ctx.build.woodDemand();
        // Stock refill terms are capped so a low stock never pulls a crowd of gatherers away from production.
        float g_w = 2f * (w_i + w_u + w_r) * tw + refill(wood_target - s_wood, tw);
        float g_i = (w_i + w_u) * ti + refill(stock_iron - s_iron, ti);
        float g_r = (w_u + w_r) * tr + refill(stock_rock - s_rock, tr);
        stocks_ok = s_wood >= 4 && (s_iron >= 2 || (s_rock >= 2 && rock_weapons));
        last_s_wood = s_wood;
        last_s_iron = s_iron;
        last_s_rock = s_rock;
        worker_deficit = workers_target - inside;
        gather_target[0] = clampTarget(g_w, s_wood);
        gather_target[1] = Math.min(iron_slots, clampTarget(g_i, s_iron));
        gather_target[2] = Math.min(rock_slots, clampTarget(g_r, s_rock));
        workers_target = Math.round(80f * w_i + 120f * w_u + 40f * w_r);
        // Workers far beyond what the stocks can feed stand idle: they gather wood (two per weapon) while it is
        // short, and turn rock into warriors when wood is plentiful but iron is not.
        surplus_rock = false;
        if (surplus_out && !emergency && inside - workers_target > 20) {
            int extra = inside - workers_target - 10;
            if (unfreeze) {
                if (s_iron < 3 && gather_target[1] < iron_slots) {
                    int add = Math.min(extra, iron_slots - gather_target[1]);
                    gather_target[1] += add;
                    extra -= add;
                }
                int hyst = Math.max(2, Math.round(0.15f * gather_target[0]));
                if (extra > 0 && s_wood < 60 && gather_now[0] >= gather_target[0] - hyst) {
                    gather_target[0] += extra;
                    extra = 0;
                }
                if (extra > 0 && s_iron < 3 && rock_slots > gather_target[2]) {
                    gather_target[2] = Math.min(rock_slots, gather_target[2] + extra);
                    surplus_rock = true;
                }
            } else if (s_wood < 60) {
                gather_target[0] += extra;
            } else if (s_iron < 3 && rock_slots > gather_target[2]) {
                gather_target[2] = Math.min(rock_slots, gather_target[2] + extra);
                surplus_rock = true;
            }
        }

        // Surplus gatherers go back inside (they deposit their cargo); deficits pull workers out.
        int spare_inside = inside - workers_target;
        char gate = '-';
        boolean raid_near = ctx.resources.threatened(a.getGridX(), a.getGridY(), unfreeze ? deploy_threat_r : 30);
        for (int i = 0; i < 3; i++) {
            int diff = gather_target[i] - gather_now[i];
            int hysteresis = Math.max(2, Math.round(0.15f * gather_target[i]));
            if (diff <= -hysteresis) {
                int k = Math.min(3, -diff);
                ctx.orders.recallGatherers(a, GATHER_TYPES[i], k);
            } else if (diff >= hysteresis) {
                // Free peons are dispatched to the neediest type anyway; only a real crowd of them should hold
                // workers inside (gatherers that gave up and walk home count as free).
                boolean free_ok = (unfreeze || deploy_gate) ? free < Math.max(1, diff / 3) : free == 0;
                if (!free_ok) {
                    gate = 'F';
                } else if (spare_inside <= 0) {
                    gate = gate == '-' ? 'W' : gate;
                } else if (emergency) {
                    gate = 'E';
                } else if (raid_near) {
                    gate = 'T';
                } else {
                    int k = Math.min(Math.min(3, diff), spare_inside);
                    DeployContainer c = a.getDeployContainer(HARVEST[i]);
                    if (c != null && c.getNumSupplies() == 0) {
                        ctx.orders.deploy(a, HARVEST[i], k);
                        spare_inside -= k;
                    } else {
                        gate = 'Q';
                    }
                }
            }
        }
        int ew = e;
        int fr = free;
        char g = gate;
        float wr = w_r;
        ctx.log(() -> String.format(
                "econ E=%d in=%d/%d free=%d G=%d/%d %d/%d %d/%d T=%.0f/%.0f/%.0f S=%d/%d/%d/%d gate=%c" + " slots i/r=%d/%d w_r=%.3f",
                ew, inside, workers_target, fr, gather_now[0], gather_target[0],
                gather_now[1], gather_target[1], gather_now[2], gather_target[2], tw, ti, tr, s_wood, s_iron, s_rock,
                s_rub, g, iron_slots, rock_slots, wr));
    }

    private float refill(float missing, float trip) {
        return Math.max(-6f, Math.min(6f, missing * trip / stock_tau));
    }

    private static int clampTarget(float g, int stock) {
        if (stock >= 150)
            return 0;
        return Math.max(0, Math.round(g));
    }

    /** When the neediest type has no reachable node: the other short types, by relative shortfall. */
    private boolean sendOtherGather(@NonNull Unit u, int skip) {
        boolean[] tried = new boolean[3];
        if (skip >= 0)
            tried[skip] = true;
        for (int round = 0; round < 2; round++) {
            int best = -1;
            float best_short = 0f;
            for (int i = 0; i < 3; i++) {
                int shortfall = gather_target[i] - gather_now[i];
                if (tried[i] || shortfall <= 0)
                    continue;
                float rel = shortfall / (float) Math.max(1, gather_target[i]);
                if (rel > best_short) {
                    best_short = rel;
                    best = i;
                }
            }
            if (best < 0)
                return false;
            tried[best] = true;
            if (sendGather(u, best))
                return true;
        }
        return false;
    }

    /** The gather type (0 tree, 1 iron, 2 rock) most short of its target, or -1. */
    private int neediestType() {
        int best = -1;
        float best_short = 0f;
        for (int i = 0; i < 3; i++) {
            int shortfall = gather_target[i] - gather_now[i];
            if (shortfall <= 0)
                continue;
            float rel = shortfall / (float) Math.max(1, gather_target[i]);
            if (rel > best_short) {
                best_short = rel;
                best = i;
            }
        }
        return best;
    }

    private boolean sendGather(@NonNull Unit u, int type) {
        return sendGather(u, type, true);
    }

    /** Orders a gather at the best node of the type; count=false for peons gathererControl already counted. */
    private boolean sendGather(@NonNull Unit u, int type, boolean count) {
        Building a = armory;
        if (a == null)
            return false;
        Supply s;
        if (type == 0)
            s = ctx.resources.bestTreeFrom(a, 4, 30, ResourceTracker.TREE_CAP);
        else
            s = ctx.resources.bestNode(GATHER_TYPES[type], a);
        if (s == null)
            return false;
        ctx.orders.gather(u, s);
        ctx.resources.noteAssigned(s);
        if (count)
            gather_now[type]++;
        return true;
    }

    // -----------------------------------------------------------------------------------------------------
    // Weapons and deploys

    private void weapons() {
        Building a = armory;
        boolean iron = !ctx.resources.iron().isEmpty() && !rock_emergency;
        ctx.orders.weapons(a, IronAxeWeapon.class, iron);
        ctx.orders.weapons(a, RubberAxeWeapon.class, true);
        // Under attack with no iron to work, rock in stock becomes defenders (rock axes would share the workers'
        // time with iron axes, so only while iron is out).
        boolean rock_now = rock_fallback && emergency && last_s_iron < 2 && last_s_rock >= 1 && last_s_wood >= 2;
        ctx.orders.weapons(a, RockAxeWeapon.class, rock_emergency || rock_weapons || surplus_rock || rock_now);
    }

    private void deployWarriors() {
        Building a = armory;
        int inside = a.getUnitContainer().getNumSupplies();
        // Finished weapons are wasted capital until a warrior carries them: deploy them all, keeping only a couple
        // of workers inside so production never stops (births refill the armory).
        int keep = emergency ? 0 : Math.max(2, Math.round(workers_keep_frac * workers_target));
        int avail = inside - keep;
        if (avail <= 0)
            return;
        // A siege drain is still letting peons out: keep its rear rally until the last one has left.
        DeployContainer pc = a.getDeployContainer(DeployType.PEON);
        if (drain_rally && pc != null && pc.getNumSupplies() > 0)
            return;
        int reserve = ctx.military.latentReserve();
        int queued = 0;
        for (DeployType t : new DeployType[]{DeployType.RUBBER_WARRIOR, DeployType.IRON_WARRIOR, DeployType.ROCK_WARRIOR}) {
            DeployContainer c = a.getDeployContainer(t);
            if (c != null)
                queued += c.getNumSupplies();
        }
        if (queued >= 6)
            return;
        int[] rally = ctx.military.rallyPoint();
        if (rally != null)
            ctx.orders.rally(a, rally[0], rally[1]);
        int rub = a.getSupplyContainer(RubberAxeWeapon.class).getNumSupplies();
        int n = Math.min(rub, avail);
        if (n > 0) {
            ctx.orders.deploy(a, DeployType.RUBBER_WARRIOR, n);
            avail -= n;
        }
        int iron = a.getSupplyContainer(IronAxeWeapon.class).getNumSupplies() - reserve;
        n = Math.min(iron, avail);
        if (n > 0) {
            ctx.orders.deploy(a, DeployType.IRON_WARRIOR, n);
            avail -= n;
        }
        int rock = a.getSupplyContainer(RockAxeWeapon.class).getNumSupplies();
        n = Math.min(rock, avail);
        if (n > 0)
            ctx.orders.deploy(a, DeployType.ROCK_WARRIOR, n);
    }

    // -----------------------------------------------------------------------------------------------------
    // Chickens

    private void chickens() {
        Building a = armory;
        List<Unit> hunters = new ArrayList<>();
        List<Supply> hunted = new ArrayList<>();
        for (Unit u : ctx.model.me.peons) {
            if (ctx.roleOf(u) != Context.Role.HUNTER)
                continue;
            Supply target = null;
            if (WorldModel.primary(u) instanceof GatherController<?> gc && gc.getSupplyType() == RubberSupply.class)
                target = gc.getSupply();
            hunters.add(u);
            if (target != null)
                hunted.add(target);
        }
        for (Unit u : ctx.model.me.peons) {
            if (!ctx.hasRole(u) && WorldModel.primary(u) instanceof GatherController<?> gc
                    && gc.getSupplyType() == RubberSupply.class) {
                hunters.add(u);
                if (gc.getSupply() != null)
                    hunted.add(gc.getSupply());
            }
        }
        if (hunters.size() >= hunter_max)
            return;
        int pop = ctx.owner.getUnitCountContainer().getNumSupplies();
        int dmax = pop > ctx.world.getMaxUnitCount() * 0.7f ? chicken_dist_far : chicken_dist;
        List<RubberSupply> chickens = ctx.map.freeChickens();
        List<RubberSupply> candidates = new ArrayList<>();
        for (RubberSupply c : chickens) {
            if (hunted.contains(c))
                continue;
            int d = MapAnalysis.chebyshev(c.getGridX(), c.getGridY(), a.getGridX(), a.getGridY());
            if (d > dmax || ctx.resources.threatened(c.getGridX(), c.getGridY(), 20))
                continue;
            if (ctx.map.frontness(c.getGridX(), c.getGridY()) > 0.55f)
                continue;
            if (ctx.resources.nearerToAvoided(a.getGridX(), a.getGridY(), c.getGridX(), c.getGridY()))
                continue;
            candidates.add(c);
        }
        candidates.sort((x, y) -> {
            int dx = MapAnalysis.dist2(x.getGridX(), x.getGridY(), a.getGridX(), a.getGridY());
            int dy = MapAnalysis.dist2(y.getGridX(), y.getGridY(), a.getGridX(), a.getGridY());
            if (dx != dy)
                return Integer.compare(dx, dy);
            if (x.getGridX() != y.getGridX())
                return Integer.compare(x.getGridX(), y.getGridX());
            return Integer.compare(x.getGridY(), y.getGridY());
        });
        int slots = hunter_max - hunters.size();
        for (RubberSupply c : candidates) {
            if (slots <= 0)
                break;
            Unit best = null;
            int best_d = Integer.MAX_VALUE;
            for (Unit u : ctx.model.me.peons) {
                if (BuildManager.carriesAnything(u))
                    continue;
                boolean ok = isFree(u) || (!ctx.hasRole(u) && WorldModel.primary(u) instanceof GatherController<?> gc
                        && gc.getSupplyType() == TreeSupply.class);
                if (!ok)
                    continue;
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), c.getGridX(), c.getGridY());
                if (d < best_d) {
                    best_d = d;
                    best = u;
                }
            }
            if (best == null)
                break;
            ctx.orders.gather(best, c);
            ctx.setRole(best, Context.Role.HUNTER);
            slots--;
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // Dispatch free peons

    private void dispatch() {
        List<Unit> free = new ArrayList<>();
        for (Unit u : ctx.model.me.peons) {
            if (isFree(u))
                free.add(u);
        }
        if (free.isEmpty())
            return;
        int builders_wanted = buildersWanted();
        Building chief_q = ctx.chief.trainingQuarters();
        int chief_wanted = chief_q != null && !ctx.military.quartersThreatened(chief_q)
                && !ctx.resources.threatened(chief_q.getGridX(), chief_q.getGridY(), 14) ? ctx.chief.peonsWanted() : 0;
        if (chief_wanted > 0) {
            for (Unit p : ctx.model.me.peons) {
                Orders.Last l = ctx.orders.lastOrder(p);
                if (!ctx.hasRole(p) && l != null && l.kind() == Orders.Kind.ENTER && l.target() == chief_q
                        && WorldModel.primary(p) instanceof com.oddlabs.tt.model.behaviour.EnterController
                        && ctx.now - l.time() < 30f)
                    chief_wanted--;
            }
            chief_wanted = Math.max(0, chief_wanted);
        }
        for (Unit u : free) {
            if (ctx.military.inDanger(u.getGridX(), u.getGridY())) {
                refuge(u);
                continue;
            }
            BuildManager.Task site = builders_wanted > 0 ? ctx.build.neediest() : null;
            if (site != null) {
                ctx.build.assign(u, site);
                builders_wanted--;
                continue;
            }
            if (armory == null) {
                // No site needs this peon and there is no armory to work in: breed inside a quarters instead of
                // crowding the construction site.
                Building q = surplusQuarters(u);
                if (q != null && !BuildManager.carriesAnything(u)) {
                    surplus_until = ctx.now + 10f;
                    ctx.orders.enter(u, q);
                } else {
                    prechop(u);
                }
                continue;
            }
            if (BuildManager.carriesAnything(u)) {
                ctx.orders.enter(u, armory);
                continue;
            }
            if (chief_wanted > 0 && !ctx.orders.recently(u, 3f)) {
                ctx.orders.enter(u, chief_q);
                chief_wanted--;
                continue;
            }
            // Workers inside the armory turn stock into weapons; when stock is there, they come first.
            if (stocks_ok && worker_deficit > 0 && !ctx.military.inDanger(armory.getGridX(), armory.getGridY())) {
                ctx.orders.enter(u, armory);
                worker_deficit--;
                continue;
            }
            int type = neediestType();
            if (type >= 0 && sendGather(u, type))
                continue;
            if (unfreeze && sendOtherGather(u, type))
                continue;
            if (WorldModel.primary(u) instanceof TransferUnitController && !shelterBlocked(u, armory))
                continue;
            ctx.orders.enter(u, armory);
        }
    }

    /** The least-filled safe quarters for a surplus peon while there is no armory, or null. */
    private @Nullable Building surplusQuarters(@NonNull Unit u) {
        Building best = null;
        int best_inside = SURPLUS_KEEP;
        for (Building q : ctx.model.me.quarters) {
            if (q.isDead() || ctx.resources.threatened(q.getGridX(), q.getGridY(), 14))
                continue;
            int inside = q.getUnitContainer().getNumSupplies();
            if (inside < best_inside) {
                best_inside = inside;
                best = q;
            }
        }
        return best;
    }

    /** How many more peons the build manager may take this tick. */
    private int buildersWanted() {
        if (phase == Phase.OPENING || phase == Phase.RECOVER)
            return Integer.MAX_VALUE;
        int builders = 0;
        for (Unit u : ctx.model.me.peons) {
            if (ctx.roleOf(u) == Context.Role.BUILDER)
                builders++;
        }
        int cap = Math.round(builder_cap * (econPeons() + builders));
        return Math.max(0, cap - builders);
    }

    /** Before the armory exists: chop one wood near the next site so builders arrive carrying it. */
    private void prechop(@NonNull Unit u) {
        if (BuildManager.carriesWood(u)) {
            BuildManager.Task site = ctx.build.neediest();
            if (site != null)
                ctx.build.assign(u, site);
            return;
        }
        int cx = ctx.map.home_x;
        int cy = ctx.map.home_y;
        for (BuildManager.Task t : ctx.build.tasks()) {
            if (t.open()) {
                cx = t.x;
                cy = t.y;
                break;
            }
        }
        if (ctx.orders.recently(u, 4f))
            return;
        TreeSupply tree = ctx.resources.bestTree(cx, cy, 5, 16, ctx.strategy.i("prechopPerTree"));
        if (tree != null) {
            ctx.orders.gather(u, tree);
            ctx.resources.noteAssigned(tree);
        }
    }

    private void refuge(@NonNull Unit u) {
        Building a = armory;
        if (a != null) {
            ctx.orders.enter(u, a);
            return;
        }
        if (!BuildManager.carriesAnything(u)) {
            for (Building q : ctx.model.me.quarters) {
                if (!q.isDead() && !ctx.military.quartersThreatened(q)
                        && !ctx.resources.threatened(q.getGridX(), q.getGridY(), 14)) {
                    ctx.orders.enter(u, q);
                    return;
                }
            }
        }
        if (!ctx.orders.recently(u, 2.5f)) {
            int[] away = ctx.military.awayFromEnemy(u.getGridX(), u.getGridY(), 14);
            ctx.orders.move(u, away[0], away[1]);
        }
    }

    int workersTarget() {
        return workers_target;
    }
}
