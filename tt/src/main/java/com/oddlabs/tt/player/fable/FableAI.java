package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The skirmish "Hard AI": a Viking-style macro/tower/stun player for the large island 1v1 (the strategy constants
 * live in {@link Params}; a {@link Strategy} picks them per map).
 *
 * <p>Runs on the game-time animation manager, so it thinks in game seconds at every game speed. A fast lane
 * (every 0.15 s) handles the chieftain's spells and warrior deployment; a slow lane (every 0.5 s) refreshes the
 * world view and runs construction, the army and the threat model; the economy allocator runs every second.
 * All engine calls go through {@link Orders}, which guards against the engine's assertions. Decisions use only
 * deterministic inputs, so every peer of a multiplayer game computes the same orders.
 */
public final class FableAI extends AI implements AiStatus {
    /** When true (developer match runner), an internal error propagates instead of being logged and swallowed. */
    public static boolean strict = false;

    private final @NonNull Params params;
    private final @NonNull AiLog log;
    private final @NonNull Strategy strategy;
    private @Nullable Orders orders;
    private @Nullable Jobs jobs;
    private @Nullable ResourceMap resources;
    private @Nullable SiteFinder sites;
    private @Nullable BasePlan plan;
    private @Nullable Roster roster;
    private @Nullable Intel intel;
    private @Nullable Threat threat;
    private @Nullable Construction construction;
    private @Nullable Economy economy;
    private @Nullable BuildPlanner planner;
    private @Nullable Chieftain chieftain;
    private @Nullable Military military;

    private float clock;
    private float fast_acc;
    private float slow_acc;
    private float econ_acc;
    private float last_status = -100f;
    private boolean initialised;
    private boolean dead;
    private int errors;
    private @Nullable LandBuilding training_quarters;
    private float training_started = -1f;

    public FableAI(@NonNull Player owner, @Nullable UnitInfo unit_info) {
        this(owner, unit_info, "");
    }

    public FableAI(@NonNull Player owner, @Nullable UnitInfo unit_info, @NonNull String overrides) {
        super(owner, unit_info);
        World world = owner.getWorld();
        // think in game time
        world.getAnimationManagerRealTime().removeAnimation(this);
        world.getAnimationManagerGameTime().registerAnimation(this);
        this.strategy = Strategy.forWorld(world, owner);
        this.params = strategy.params();
        if (!overrides.isBlank())
            params.override(overrides);
        this.log = new AiLog(owner.getPlayerInfo().getName(), () -> clock);
        log.info(
                "FableAI created, strategy " + strategy.name() + (overrides.isBlank() ? "" : " overrides " + overrides));
    }

    @Override
    public boolean isHardDifficulty() {
        return true;
    }

    @Override
    public void animate(float t) {
        if (!Globals.run_ai || dead)
            return;
        clock += t;
        try {
            if (!initialised) {
                init();
                return;
            }
            if (!getOwner().isAlive()) {
                dead = true;
                log.info("player dead, AI stops");
                return;
            }
            fast_acc += t;
            slow_acc += t;
            econ_acc += t;
            if (slow_acc >= params.slow_period) {
                float dt = slow_acc;
                slow_acc = 0f;
                slowLane(dt);
            }
            if (fast_acc >= params.fast_period) {
                fast_acc = 0f;
                fastLane();
            }
            if (econ_acc >= params.econ_period) {
                econ_acc = 0f;
                economyLane();
            }
        } catch (RuntimeException | AssertionError e) {
            errors++;
            IO.println("FableAI(" + getOwner() + ") error #" + errors + " at t=" + clock + ": " + e);
            if (strict || errors <= 3)
                e.printStackTrace();
            if (strict)
                throw e;
            if (errors > 200) {
                dead = true;
                IO.println("FableAI(" + getOwner() + ") disabled after repeated errors");
            }
        }
    }

    private void init() {
        Player owner = getOwner();
        int sx = UnitGrid.toGridCoordinate(owner.getStartX());
        int sy = UnitGrid.toGridCoordinate(owner.getStartY());
        int island = owner.getWorld().getHeightMap().getIslandId(sx, sy);
        resources = new ResourceMap(owner.getWorld(), island);
        sites = new SiteFinder(owner);
        orders = new Orders(owner, log);
        jobs = new Jobs();
        plan = new BasePlan(owner, resources, sites, params, log);
        plan.init();
        roster = new Roster(owner);
        intel = new Intel(owner);
        threat = new Threat();
        construction = new Construction(orders, jobs, sites, params, log);
        economy = new Economy(owner, orders, jobs, resources, plan, params, log);
        planner = new BuildPlanner(orders, jobs, construction, plan, economy, params, log);
        planner.setResources(resources);
        planner.setSiteFinder(sites);
        chieftain = new Chieftain(orders, params, log);
        military = new Military(owner, orders, jobs, params, log, plan, chieftain);
        initialised = true;
        roster.refresh();
        intel.refresh();
        log.info(() -> "init: trees " + resources.countTotal(ResourceMap.Kind.TREE) + " iron " + resources.countTotal(
                ResourceMap.Kind.IRON) + " rock " + resources.countTotal(
                        ResourceMap.Kind.ROCK) + " peons " + roster.peons.size());
        // the opening starts on the first slow tick
        slow_acc = params.slow_period;
    }

    private void slowLane(float dt) {
        roster.refresh();
        intel.refresh();
        jobs.prune();
        threat.update(clock, dt, roster, intel, plan, params);
        military.tick(clock, dt, roster, intel, threat, economy);
        planner.tick(clock, roster, intel, threat, military);
        manageChieftainTraining();
        if (clock - last_status >= 30f) {
            last_status = clock;
            log.info(this::debugStatus);
        }
    }

    private void fastLane() {
        // roster/intel are at most 0.5 s old: fine for deploys; refresh cheaply for the chieftain if it exists
        Unit chief = roster.chieftain;
        if (chief != null && !chief.isDead() && !chief.isMounted()) {
            intel.refresh();
            int cx = chief.getGridX();
            int cy = chief.getGridY();
            boolean fighting = military.fightingNear(cx, cy, 25) || intel.warriorsNear(cx, cy, 12) > 0;
            int own_near = Chieftain.ownWarriorsNear(roster.warriors, cx, cy, 15);
            int own18 = Chieftain.ownUnitsNear(roster, cx, cy, 18);
            boolean defending = military.posture == Military.Posture.DEFEND;
            chieftain.decideCast(clock, chief, intel, threat, fighting, own_near, own18, defending, roster.warriors);
        }
        military.recoverStunned(clock, roster);
        economy.deployWarriors(roster);
    }

    private void economyLane() {
        roster.refresh();
        economy.tick(clock, roster, intel, training_quarters, roster.totalPeons());
        if (roster.mainArmory() == null && !planner.openingDone()) {
            // opening: peons without a job breed
            economy.parkIdlePeons(clock, roster);
        }
    }

    /** Start (and keep boosted) chieftain training once the economy can afford it. */
    private void manageChieftainTraining() {
        Player owner = getOwner();
        if (training_quarters != null && (training_quarters.isDead() || !owner.isTrainingChieftain())) {
            training_quarters = null;
        }
        if (owner.hasActiveChieftain() || owner.isTrainingChieftain() || roster.quarters.isEmpty())
            return;
        if (roster.quarters.size() < params.chieftain_min_quarters || clock < params.chieftain_min_time
                || roster.totalPeons() < params.chieftain_min_peons)
            return;
        // train in the quarters with the most peons inside (the rear one is safest, but speed matters more)
        LandBuilding best = null;
        int best_inside = -1;
        for (LandBuilding q : roster.quarters) {
            int inside = Roster.unitsInside(q);
            if (inside > best_inside) {
                best_inside = inside;
                best = q;
            }
        }
        if (best == null)
            return;
        orders.trainChieftain(best, true);
        if (owner.isTrainingChieftain()) {
            training_quarters = best;
            training_started = clock;
            final LandBuilding q = best;
            log.info(() -> "chieftain training started in quarters " + q.getGridX() + "," + q.getGridY());
        }
    }

    @Override
    public @NonNull String debugStatus() {
        if (!initialised)
            return "init";
        return String.format("t=%.0f %s | %s | %s | %s | peons=%d(q%d a%d) warriors=%d(t%d) chief=%s",
                clock, military.status(), threat, economy.status(roster), planner.status(), roster.totalPeons(),
                roster.peons_in_quarters, roster.peons_in_armory, roster.totalWarriors(), roster.warriors_in_towers,
                roster.chieftain == null ? (getOwner().isTrainingChieftain() ? "training" : "none") : roster.chieftain.getHitPoints() + "hp/" + (int) (100 * Chieftain.stunProgress(
                        roster.chieftain)) + "%");
    }
}
