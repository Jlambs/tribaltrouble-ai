package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.MountUnitContainer;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackBehaviour;
import com.oddlabs.tt.model.behaviour.EnterController;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The army brain: posture (build up / defend / attack / all in), tower garrisons, the home guard, the main army's
 * march in legs so it arrives as a clump, engagement and retreat by local strength ratios, sieges of tower
 * clusters with the chieftain's stun, reinforcement batches and the chieftain's position.
 */
public final class Military {
    public enum Posture {
        BUILD_UP,
        DEFEND,
        ATTACK,
        ALL_IN
    }

    public enum ArmyState {
        STAGING,
        MARCH,
        ENGAGE,
        ASSAULT,
        SIEGE_PREP,
        SIEGE_CYCLE,
        RETREAT
    }

    static final int TAG_HOME = 1;
    static final int TAG_ARMY = 2;
    static final int TAG_REINF = 3;
    static final int TAG_GARRISON = 4;
    static final int TAG_FOCUS = 5;
    static final int TAG_CORPS = 6;

    private final @NonNull Player player;
    private final @NonNull Orders orders;
    private final @NonNull Jobs jobs;
    private final @NonNull Params params;
    private final @NonNull AiLog log;
    private final @NonNull BasePlan plan;
    private final @NonNull Chieftain chief_brain;
    private final @NonNull UnitGrid grid;
    private final @NonNull Micro micro;

    public @NonNull Posture posture = Posture.BUILD_UP;
    public @NonNull ArmyState army_state = ArmyState.STAGING;
    private float posture_since;
    private float army_state_since;

    /** Main army members (alive, unmounted; pruned each tick). */
    private final Set<Unit> army = new LinkedHashSet<>();
    private final Set<Unit> reinforcements = new LinkedHashSet<>();
    private final Set<Unit> home_guard = new LinkedHashSet<>();

    // march bookkeeping
    private int objective_x = -1;
    private int objective_y = -1;
    private @Nullable Selectable<?> objective;
    private int leg_x = -1;
    private int leg_y = -1;
    private int prev_leg_x = -1;
    private int prev_leg_y = -1;
    private float leg_ordered_at;
    private int leg_skips;
    private float last_engage_order = -100f;
    private int last_engage_x = -1;
    private int last_engage_y = -1;
    private float engage_start_value;
    private float hold_since = -1f;
    // siege
    private @Nullable LandBuilding siege_tower;
    private int stand_x = -1;
    private int stand_y = -1;
    private int army_pos_x = -1;
    private int army_pos_y = -1;
    private float siege_cast_at = -100f;
    private float siege_prep_since;
    // staging/rally
    private int staging_x = -1;
    private int regroup_x = -1;
    private int regroup_y = -1;
    /** Sticky siege hold point (the centroid drifts a cell per tick; re-ordering the whole army for that is spam). */
    private int hold_x = -1;
    private int hold_y = -1;
    private int staging_y = -1;
    private int rally_x = -1;
    private int rally_armories = -1;
    /** The economy moved an armory rally (worker transfer): re-apply ours on the next tick. */
    boolean economy_rally_dirty;
    private int rally_y = -1;
    private float last_defend_order = -100f;
    private int last_defend_x = -1;
    private int last_defend_y = -1;
    private float last_garrison_swap = -100f;
    private float last_focus_at = -100f;
    private float last_leash = -100f;
    private final List<Selectable<?>> scratch_targets = new ArrayList<>();

    public Military(@NonNull Player player, @NonNull Orders orders, @NonNull Jobs jobs, @NonNull Params params,
            @NonNull AiLog log, @NonNull BasePlan plan, @NonNull Chieftain chief_brain) {
        this.player = player;
        this.orders = orders;
        this.jobs = jobs;
        this.params = params;
        this.log = log;
        this.plan = plan;
        this.chief_brain = chief_brain;
        this.grid = player.getWorld().getUnitGrid();
        this.micro = new Micro(player, orders, jobs, params);
    }

    // ------------------------------------------------------------------ values

    public float unitValue(@NonNull Unit u) {
        Class<?> type = u.getWeaponFactory().getType();
        if (type == RockAxeWeapon.class)
            return params.value_rock;
        if (type == RubberAxeWeapon.class)
            return params.value_rubber;
        if (type == IronAxeWeapon.class)
            return params.value_iron;
        return 0.1f;
    }

    public float towerValue(@NonNull LandBuilding t) {
        if (t.isDead() || !t.isComplete() || t.getUnitContainer() == null)
            return 0f;
        if (!(t.getUnitContainer() instanceof MountUnitContainer m) || m.getUnit() == null)
            return 0f;
        Unit u = m.getUnit();
        if (Chieftain.isStunned(u))
            return 0f;
        Class<?> type = u.getWeaponFactory().getType();
        if (type == RubberAxeWeapon.class)
            return params.value_tower_rubber;
        if (type == RockAxeWeapon.class)
            return params.value_tower_rock;
        return params.value_tower_iron;
    }

    /** Our fighting value within radius of a cell (warriors + chieftain + our manned towers whose reach covers it). */
    public float ownValueNear(@NonNull Roster roster, int gx, int gy, int radius) {
        int r2 = radius * radius;
        float v = 0f;
        for (Unit u : roster.warriors)
            if (!Chieftain.isFrozen(u) && BasePlan.dist2(u.getGridX(), u.getGridY(), gx, gy) <= r2)
                v += unitValue(u);
        if (roster.chieftain != null && !roster.chieftain.isDead()
                && BasePlan.dist2(roster.chieftain.getGridX(), roster.chieftain.getGridY(), gx, gy) <= r2)
            v += params.value_chief;
        for (LandBuilding t : roster.towers)
            if (BasePlan.dist2(t.getGridX(), t.getGridY(), gx, gy) <= params.tower_reach2)
                v += towerValue(t);
        return v;
    }

    /** Enemy fighting value near a cell: warriors, chieftain, and manned towers covering the cell. */
    public float enemyValueNear(@NonNull Intel intel, int gx, int gy, int radius) {
        int r2 = radius * radius;
        float v = 0f;
        for (Unit u : intel.warriors)
            if (!Chieftain.isFrozen(u) && BasePlan.dist2(u.getGridX(), u.getGridY(), gx, gy) <= r2)
                v += unitValue(u);
        for (Unit u : intel.chieftains)
            if (!Chieftain.isFrozen(u) && BasePlan.dist2(u.getGridX(), u.getGridY(), gx, gy) <= r2)
                v += params.value_chief;
        for (LandBuilding t : intel.manned_towers)
            if (BasePlan.dist2(t.getGridX(), t.getGridY(), gx, gy) <= params.tower_reach2)
                v += towerValue(t);
        return v;
    }

    public float enemyTotalValue(@NonNull Intel intel) {
        float v = 0f;
        for (Unit u : intel.warriors)
            v += unitValue(u);
        for (Unit u : intel.chieftains)
            v += params.value_chief;
        return v;
    }

    private float groupValue(@NonNull Set<Unit> group) {
        float v = 0f;
        for (Unit u : group)
            if (orders.usable(u))
                v += unitValue(u);
        return v;
    }

    private int @Nullable [] centroid(@NonNull Set<Unit> group) {
        long sx = 0, sy = 0;
        int n = 0;
        for (Unit u : group) {
            if (!orders.usable(u))
                continue;
            sx += u.getGridX();
            sy += u.getGridY();
            n++;
        }
        return n == 0 ? null : new int[]{(int) (sx / n), (int) (sy / n)};
    }

    // ------------------------------------------------------------------ main tick (0.5 s)

    public void tick(float now, float dt, @NonNull Roster roster, @NonNull Intel intel, @NonNull Threat threat,
            @NonNull Economy economy) {
        prune(roster);
        LandBuilding armory = roster.mainArmory();
        int[] home = homeCell(roster);
        if (economy.rally_dirty) {
            economy.rally_dirty = false;
            economy_rally_dirty = true;
        }
        updateStaging(now, roster, armory, home);
        adoptNewWarriors(roster, home);
        garrisonTowers(now, roster, intel, threat, home);
        updatePosture(now, roster, intel, threat, economy, home);
        economy.demands.emergency = posture == Posture.DEFEND && (threat.level == Threat.Level.ENGAGED
                || (threat.approaching && threat.eta < 30f));
        economy.demands.armory_threat = armory != null && intel.warriorsNear(armory.getGridX(), armory.getGridY(),
                20) >= 3;
        economy.demands.want_rock_warriors = wantRockWarriors(now, roster, intel, threat, armory);
        threat.hotCells(intel, economy.demands.hot_cells);
        economy.demands.hot_radius = params.hot_node_radius;
        economy.demands.cap_assault = posture == Posture.ALL_IN;
        switch (posture) {
            case DEFEND -> {
                // a group camping just outside the ring for a long time starves the fields; when it is weaker
                // than what we can put in the field, go and break it up instead of waiting it out
                float field = groupValue(army) + groupValue(home_guard);
                boolean camping = now - posture_since > params.defend_sweep_after && threat.approach_value > 0f
                        && field >= params.engage_ratio * threat.approach_value;
                if (!(camping && fieldSweep(now, roster, intel, home)))
                    defend(now, roster, intel, threat, home, allFieldUnits());
            }
            case BUILD_UP -> holdHome(now, roster, intel, home);
            case ATTACK, ALL_IN -> {
                if (threat_serious) {
                    // the army is away: guard and staged reinforcements defend
                    List<Unit> defenders = new ArrayList<>(home_guard);
                    defenders.addAll(reinforcements);
                    defend(now, roster, intel, threat, home, defenders);
                } else {
                    homeGuardStep(now, roster, intel, home);
                }
                armyStep(now, dt, roster, intel, threat, home);
            }
        }
        reinforcementsStep(now, roster, home);
        corpsStep(now, roster, intel, economy, home);
        focusStunned(now, roster, intel);
        leash(now, roster, intel);
        positionChieftain(now, roster, intel, threat, home);
        chief_brain.hold_for_siege = army_state == ArmyState.SIEGE_PREP && siege_tower != null;
    }

    // ------------------------------------------------------------------ demolition corps

    /**
     * Peons marching with the army: a peon swing does 6 flat damage to a tower (100 hp), so ten of them tear one
     * down in seconds, and a tower with warriors in reach shoots those first.
     */
    private final Set<Unit> corps = new LinkedHashSet<>();
    private float last_corps_order = -100f;
    private float last_corps_recruit = -100f;
    private @Nullable LandBuilding corps_target;

    private void corpsStep(float now, @NonNull Roster roster, @NonNull Intel intel, @NonNull Economy economy,
            int @NonNull [] home) {
        corps.removeIf(p -> !orders.usable(p));
        economy.reserved.removeIf(p -> !corps.contains(p));
        boolean attacking = (posture == Posture.ATTACK || posture == Posture.ALL_IN)
                && army.size() >= params.corps_min_army;
        if (!attacking || !params.corps) {
            if (!corps.isEmpty()) {
                for (Unit p : corps)
                    jobs.clear(p);
                log.info("corps released (" + corps.size() + ")");
                corps.clear();
                economy.reserved.clear();
            }
            corps_target = null;
            return;
        }
        int[] ac0 = centroid(army);
        boolean army_near_home = army_state == ArmyState.STAGING
                || (ac0 != null && BasePlan.dist2(ac0[0], ac0[1], staging_x, staging_y) <= 60 * 60);
        if (corps.size() < params.corps_size && now - last_corps_recruit > 10f && army_near_home) {
            // recruits walk from home: only while the army is near it (a lone peon crossing the map dies)
            last_corps_recruit = now;
            int[] at = army_state == ArmyState.STAGING ? new int[]{staging_x, staging_y} : ac0;
            if (at != null) {
                List<Unit> crew = economy.requestCrew(params.corps_size - corps.size(), at[0], at[1], roster, now,
                        false);
                for (Unit p : crew) {
                    corps.add(p);
                    economy.reserved.add(p);
                    orders.move(p, at[0], at[1]);
                    jobs.set(p, Jobs.Kind.HOLD, at[0], at[1], null, null, now, TAG_CORPS);
                }
                if (!crew.isEmpty())
                    log.info(() -> "corps recruited " + crew.size() + " peons (" + corps.size() + ")");
            }
        }
        if (corps.isEmpty())
            return;
        if (corps_target != null && (corps_target.isDead() || (!intel.towers.contains(corps_target)
                && !intel.construction_sites.contains(corps_target))))
            corps_target = null;
        if (corps_target != null)
            return; // demolishing (orders issued by corpsDemolish)
        // follow: a few cells behind the clump, away from the objective; run from warriors that get close
        int[] c = centroid(army);
        if (c == null)
            return;
        int[] p = objective_x >= 0 ? plan.toward(c[0], c[1], objective_x, objective_y, -params.corps_follow_dist) : c;
        int[] cc = centroid(corps);
        boolean threatened = cc != null && intel.warriorsNear(cc[0], cc[1], 8) >= 2;
        if (threatened)
            p = plan.toward(c[0], c[1], objective_x >= 0 ? objective_x : cc[0], objective_y >= 0 ? objective_y : cc[1],
                    -3);
        if (now - last_corps_order < 3f && !threatened)
            return;
        last_corps_order = now;
        for (Unit u : corps) {
            Jobs.Job j = jobs.get(u);
            boolean there = BasePlan.dist2(u.getGridX(), u.getGridY(), p[0], p[1]) <= 36;
            if (there && !threatened)
                continue;
            if (j != null && j.kind == Jobs.Kind.MOVE && BasePlan.dist2(j.gx, j.gy, p[0], p[1]) <= 16
                    && !Roster.activity(u).equals(Roster.Activity.IDLE))
                continue;
            orders.move(u, p[0], p[1]);
            jobs.set(u, Jobs.Kind.MOVE, p[0], p[1], null, null, now, TAG_CORPS);
        }
    }

    /**
     * Send the corps at a tower (or foundation). Returns true while the demolition is on (the caller keeps the
     * warriors holding outside the tower's reach so the garrison has nothing better than peons to shoot at).
     */
    private boolean corpsDemolish(float now, @NonNull LandBuilding tower, int @NonNull [] c) {
        if (corps.size() < params.corps_min_demolish || tower.isDead())
            return false;
        int tx = tower.getGridX();
        int ty = tower.getGridY();
        int near = 0;
        for (Unit p : corps)
            if (BasePlan.dist2(p.getGridX(), p.getGridY(), tx, ty) <= 45 * 45)
                near++;
        if (near < params.corps_min_demolish)
            return false;
        if (corps_target != tower) {
            corps_target = tower;
            log.info(() -> "corps: demolishing tower at " + tx + "," + ty + " with " + corps.size() + " peons");
        }
        if (now - last_corps_order < 2f)
            return true;
        last_corps_order = now;
        for (Unit p : corps) {
            if (Chieftain.isFrozen(p))
                continue;
            if (p.getCurrentController() instanceof HuntController hc && hc.getTarget() == tower)
                continue;
            orders.attack(p, tower);
            jobs.set(p, Jobs.Kind.HUNT, tx, ty, tower, null, now, TAG_CORPS);
        }
        return true;
    }

    private void prune(@NonNull Roster roster) {
        army.removeIf(u -> !orders.usable(u));
        reinforcements.removeIf(u -> !orders.usable(u));
        home_guard.removeIf(u -> !orders.usable(u));
        Set<Unit> alive = new LinkedHashSet<>(roster.warriors);
        army.retainAll(alive);
        reinforcements.retainAll(alive);
        home_guard.retainAll(alive);
    }

    private int @NonNull [] homeCell(@NonNull Roster roster) {
        LandBuilding a = roster.mainArmory();
        if (a != null)
            return new int[]{a.getGridX(), a.getGridY()};
        if (!roster.quarters.isEmpty())
            return new int[]{roster.quarters.getFirst().getGridX(), roster.quarters.getFirst().getGridY()};
        return new int[]{plan.start_x, plan.start_y};
    }

    private void updateStaging(float now, @NonNull Roster roster, @Nullable LandBuilding armory, int @NonNull [] home) {
        int[] s = plan.along(home[0], home[1], params.staging_dist);
        if (!plan.sameIsland(s[0], s[1]) || grid.getRegion(s[0], s[1]) == null)
            s = plan.along(home[0], home[1], 8);
        // the armory rally stays at home (new recruits must not walk 60+ cells to a forward regroup on their own);
        // every armory (the expansion too) rallies its warriors to the same staging point
        if (armory != null && (rally_x != s[0] || rally_y != s[1] || rally_armories != roster.armories.size()
                || economy_rally_dirty)) {
            for (LandBuilding a : roster.armories)
                if (orders.complete(a))
                    orders.setRally(a, s[0], s[1]);
            rally_x = s[0];
            rally_y = s[1];
            rally_armories = roster.armories.size();
            economy_rally_dirty = false;
        }
        // a retreat far from home regroups where the army stands; that point holds while the army stages there
        boolean regrouping = regroup_x >= 0 && army_state == ArmyState.STAGING
                && (posture == Posture.ATTACK || posture == Posture.ALL_IN);
        if (regrouping) {
            staging_x = regroup_x;
            staging_y = regroup_y;
        } else {
            regroup_x = -1;
            regroup_y = -1;
            staging_x = s[0];
            staging_y = s[1];
        }
    }

    /** Warriors that belong to no group join the home guard first, then the army / reinforcement pool. */
    private void adoptNewWarriors(@NonNull Roster roster, int @NonNull [] home) {
        int guard_target = guardTarget();
        for (Unit u : roster.warriors) {
            if (army.contains(u) || reinforcements.contains(u) || home_guard.contains(u))
                continue;
            if (jobs.kindOf(u) == Jobs.Kind.GARRISON)
                continue;
            if (home_guard.size() < guard_target) {
                home_guard.add(u);
            } else if (posture == Posture.ATTACK || posture == Posture.ALL_IN) {
                if (army_state == ArmyState.STAGING)
                    army.add(u);
                else
                    reinforcements.add(u);
            } else {
                army.add(u);
            }
        }
        // the home guard gives up members when it is over strength and the army wants them
        while (home_guard.size() > guard_target + 2) {
            Unit u = home_guard.iterator().next();
            home_guard.remove(u);
            army.add(u);
        }
    }

    private int guardTarget() {
        float t = posture_since; // not used; guard by game time is tracked by the caller via params
        return guard_target;
    }

    private int guard_target = 3;

    public void setGameTime(float now) {
        int g = now < params.guard_mid_time ? params.home_guard_early : now < params.guard_late_time ? params.home_guard_mid : params.home_guard_late;
        if (posture == Posture.DEFEND)
            g += params.home_guard_defend_bonus;
        if (posture == Posture.ALL_IN)
            g = 2;
        guard_target = g;
    }

    // ------------------------------------------------------------------ towers

    private void garrisonTowers(float now, @NonNull Roster roster, @NonNull Intel intel, @NonNull Threat threat,
            int @NonNull [] home) {
        // a garrison order the engine dropped (tower filled meanwhile, path failed, stun recovery re-ordered the
        // unit) must not keep the unit out of every group forever: the job follows the engine's controller
        List<Unit> stale = new ArrayList<>();
        for (Map.Entry<Unit, Jobs.Job> e : jobs.entries())
            if (e.getValue().kind == Jobs.Kind.GARRISON && orders.usable(e.getKey())
                    && !(e.getKey().getPrimaryController() instanceof EnterController))
                stale.add(e.getKey());
        for (Unit u : stale)
            jobs.clear(u);
        for (LandBuilding t : roster.towers) {
            if (t.getUnitContainer() == null || t.getUnitContainer().getNumSupplies() > 0)
                continue;
            // already someone on the way?
            List<Unit> on_way = jobs.unitsOn(t);
            boolean coming = false;
            for (Unit u : on_way)
                if (orders.usable(u) && jobs.kindOf(u) == Jobs.Kind.GARRISON) {
                    coming = true;
                    break;
                }
            if (coming)
                continue;
            Unit best = null;
            int best_score = Integer.MIN_VALUE;
            for (Unit u : roster.warriors) {
                if (!orders.usable(u) || Chieftain.isStunned(u))
                    continue;
                if (army.contains(u) && army_state != ArmyState.STAGING)
                    continue;
                if (jobs.kindOf(u) == Jobs.Kind.GARRISON)
                    continue;
                int d2 = BasePlan.dist2(u.getGridX(), u.getGridY(), t.getGridX(), t.getGridY());
                if (d2 > 60 * 60)
                    continue;
                int material = Roster.isRubber(u) ? 3 : u.getWeaponFactory().getType() == IronAxeWeapon.class ? 2 : 1;
                int score = material * 100000 - d2;
                if (score > best_score) {
                    best_score = score;
                    best = u;
                }
            }
            if (best != null) {
                orders.enter(best, t);
                jobs.set(best, Jobs.Kind.GARRISON, t.getGridX(), t.getGridY(), t, null, now, TAG_GARRISON);
                army.remove(best);
                reinforcements.remove(best);
                home_guard.remove(best);
                final Unit chosen = best;
                log.info(() -> "garrison tower " + t.getGridX() + "," + t.getGridY() + " with " + (Roster.isRubber(
                        chosen) ? "chicken" : "iron/rock") + " warrior");
            }
        }
        // swap an iron garrison for an idle chicken warrior when quiet
        if (threat.level == Threat.Level.NONE && now - last_garrison_swap > 10f && roster.rubber_warriors > 0) {
            for (LandBuilding t : roster.towers) {
                if (t.getUnitContainer() instanceof MountUnitContainer m && m.getUnit() != null
                        && !Roster.isRubber(m.getUnit()) && t.canExitTower()) {
                    Unit chicken = null;
                    for (Unit u : roster.warriors)
                        if (orders.usable(u) && Roster.isRubber(u) && jobs.kindOf(u) != Jobs.Kind.GARRISON
                                && !(army.contains(u) && army_state != ArmyState.STAGING)
                                && BasePlan.dist2(u.getGridX(), u.getGridY(), t.getGridX(), t.getGridY()) <= 25 * 25) {
                                    chicken = u;
                                    break;
                                }
                    if (chicken != null && intel.warriorsNear(t.getGridX(), t.getGridY(), 30) == 0) {
                        orders.exitTower(t);
                        last_garrison_swap = now;
                        log.info("garrison swap: chicken warrior takes tower " + t.getGridX() + "," + t.getGridY());
                        break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ posture

    private void updatePosture(float now, @NonNull Roster roster, @NonNull Intel intel, @NonNull Threat threat,
            @NonNull Economy economy, int @NonNull [] home) {
        posture_since += 0f;
        Posture next = posture;
        float dwell = now - posture_since;
        boolean threatened = threat.level == Threat.Level.ALERT || threat.level == Threat.Level.ENGAGED;
        float A = groupValue(army) + groupValue(reinforcements);
        float H = groupValue(home_guard);
        float E_total = enemyTotalValue(intel);
        boolean chief_ready = roster.chieftain != null && Chieftain.stunReady(roster.chieftain);
        boolean enemy_chief = !intel.chieftains.isEmpty();
        LandBuilding e_armory = intel.armories.isEmpty() ? null : intel.armories.getFirst();
        int ex = e_armory != null ? e_armory.getGridX() : plan.enemy_x;
        int ey = e_armory != null ? e_armory.getGridY() : plan.enemy_y;
        float E_home = enemyValueNear(intel, ex, ey, 40);
        float k = roster.chieftain == null ? params.attack_ratio_no_chief : !enemy_chief ? params.attack_ratio * 0.8f : params.attack_ratio;
        if (chief_ready)
            k = Math.min(k, params.attack_ratio_stun + 0.2f);
        // what the enemy produces while we walk over joins their defence at once; ours trails by the same walk
        float march_s = BasePlan.dist(staging_x >= 0 ? staging_x : home[0], staging_y >= 0 ? staging_y : home[1], ex,
                ey) / 2f;
        float margin = params.attack_margin + params.attack_margin_per_march_s * march_s;
        // at the cap losses are replaced for free, so attack against weaker odds, but never into a larger total
        // force: the army just dies piecemeal at their towers and the counter-attack follows. The longer both
        // sides sit at the cap, the more a stalemate favours whoever keeps converting: the ratio relaxes.
        boolean at_cap = roster.unitCount() >= params.all_in_units;
        if (at_cap && cap_since < 0f)
            cap_since = now;
        else if (!at_cap)
            cap_since = -1f;
        float total_ratio = at_cap
                && now - cap_since > params.cap_relax_time ? params.capped_total_ratio_relaxed : params.capped_total_ratio;

        // a threat is serious when the approaching force outguns what the home guard and towers can handle
        float home_defence = H + towersValue(roster) + (roster.chieftain != null && Chieftain.stunReady(
                roster.chieftain)
                && (posture != Posture.ATTACK || army_state == ArmyState.STAGING) ? 6f : 0f);
        boolean serious = threatened && threat.approach_value >= 2f && (threat.approach_value >= 0.7f * home_defence
                || (threat.level == Threat.Level.ENGAGED && threat.enemy_units_near_buildings >= 3
                        && threat.approach_value >= 0.5f * home_defence)
                || threat.enemy_units_near_buildings >= 6);
        threat_serious = serious;
        if (serious && posture != Posture.ALL_IN) {
            // an attack in progress continues when the army is nearer the enemy base than home and clearly stronger
            // there (a base trade favours us); the guard, towers and staged reinforcements defend meanwhile
            int[] ac = centroid(army);
            boolean far_out = ac != null && posture == Posture.ATTACK && army_state != ArmyState.STAGING
                    && BasePlan.dist(ac[0], ac[1], ex, ey) < BasePlan.dist(ac[0], ac[1], home[0], home[1])
                    && A >= 1.3f * E_home && threat.approach_value < A;
            boolean far_fight = posture == Posture.ATTACK && (army_state == ArmyState.ASSAULT
                    || army_state == ArmyState.ENGAGE || army_state == ArmyState.SIEGE_CYCLE) && A >= 1.5f * E_home;
            if (!far_fight && !far_out)
                next = Posture.DEFEND;
        } else if (serious && posture == Posture.ALL_IN && threat.approach_value >= 1.5f * home_defence
                && roster.unitCount() < params.all_in_units - 40) {
                    next = Posture.DEFEND;
                } else if (posture == Posture.DEFEND) {
                    if (threat.level == Threat.Level.NONE || (threat.level == Threat.Level.WATCH
                            && threat.since_level_change > 20f)) {
                        // counter-push after a repelled attack
                        if (threat.recent_enemy_losses_near_base >= 6 && E_total <= 0.7f * (A + H)
                                && A + H >= params.attack_min_army)
                            next = Posture.ATTACK;
                        else
                            next = Posture.BUILD_UP;
                    }
                } else if (posture == Posture.BUILD_UP) {
                    boolean cap = at_cap && A >= params.attack_min_army
                            && A >= params.capped_ratio * E_home + margin && A >= total_ratio * E_total;
                    boolean enemy_dead_armory = e_armory == null && intel.hasBuildings() && now > 300f;
                    boolean rested = now - last_retreat_at >= params.retreat_cooldown;
                    boolean all_in_odds = cap || (E_total <= 0.5f * (A + H) && now > 600f
                            && A >= params.attack_min_army)
                            || (enemy_dead_armory && A >= 8f);
                    boolean attack_odds = A >= params.attack_min_army
                            && ((A >= params.attack_max_army && A >= total_ratio * E_total && A >= E_home + margin)
                                    || (A >= k * Math.max(E_home, 0.8f * E_total) + margin
                                            && (H + towersValue(roster) >= 0.8f * (E_total - E_home)
                                                    || E_total - E_home < 4f)));
                    attack_odds_ok = all_in_odds || attack_odds;
                    if (rested && all_in_odds) {
                        next = Posture.ALL_IN;
                    } else if (rested && dwell >= params.posture_dwell && now >= params.attack_min_time
                            && attack_odds) {
                                next = Posture.ATTACK;
                            }
                } else if ((posture == Posture.ATTACK || posture == Posture.ALL_IN) && army_state == ArmyState.STAGING
                        && now - army_state_since > 5f && now - posture_since > 15f) {
                            // about to launch (again): the odds must still hold, or the remnants just die piecemeal
                            boolean odds = at_cap ? (A >= params.capped_ratio * E_home + margin
                                    && A >= total_ratio * E_total) : A >= k * Math.max(E_home, 0.8f * E_total) + margin;
                            boolean weak_enemy = E_total <= 0.5f * (A + H) || (e_armory == null
                                    && intel.hasBuildings());
                            if (!odds && !weak_enemy && A >= 4f) {
                                log.info(() -> String.format(
                                        "attack called off at staging: A=%.0f E_home=%.0f E_total=%.0f", A, E_home,
                                        E_total));
                                next = Posture.BUILD_UP;
                            } else if (posture == Posture.ATTACK) {
                                if ((roster.unitCount() >= params.all_in_units && A >= params.attack_min_army)
                                        || (e_armory == null && intel.hasBuildings() && now > 300f))
                                    next = Posture.ALL_IN;
                            } else if (A < params.attack_min_army * 0.5f) {
                                next = Posture.BUILD_UP;
                            }
                        } else if (posture == Posture.ATTACK) {
                            if ((roster.unitCount() >= params.all_in_units && A >= params.attack_min_army)
                                    || (e_armory == null && intel.hasBuildings() && now > 300f))
                                next = Posture.ALL_IN;
                            else if (A < 4f && army_state != ArmyState.STAGING)
                                next = Posture.BUILD_UP;
                        } else if (posture == Posture.ALL_IN) {
                            if (A < params.attack_min_army * 0.5f && army_state == ArmyState.STAGING)
                                next = Posture.BUILD_UP;
                            else if (roster.unitCount() < 200 && e_armory != null && A < params.attack_min_army)
                                next = Posture.ATTACK;
                        }
        if (next != posture) {
            final Posture from = posture;
            final Posture to = next;
            log.info(
                    () -> "posture " + from + " -> " + to + " A=" + (int) A + " H=" + (int) H + " E_home=" + (int) E_home + " E_total=" + (int) E_total + " threat=" + threat);
            if (next == Posture.ATTACK || next == Posture.ALL_IN) {
                if (posture == Posture.BUILD_UP || posture == Posture.DEFEND) {
                    army.addAll(reinforcements);
                    reinforcements.clear();
                    setArmyState(now, ArmyState.STAGING);
                }
            } else if (next == Posture.DEFEND || next == Posture.BUILD_UP) {
                // everyone comes home
                army.addAll(reinforcements);
                reinforcements.clear();
                setArmyState(now, ArmyState.STAGING);
                if (next == Posture.DEFEND)
                    recallArmy(now, home);
            }
            posture = next;
            posture_since = now;
        }
        setGameTime(now);
    }

    private float towersValue(@NonNull Roster roster) {
        float v = 0f;
        for (LandBuilding t : roster.towers)
            v += towerValue(t);
        return v;
    }

    private boolean wantRockWarriors(float now, @NonNull Roster roster, @NonNull Intel intel, @NonNull Threat threat,
            @Nullable LandBuilding armory) {
        if (armory == null)
            return false;
        // early peon rush / tower rush with no iron weapons yet
        if (now < 420f && threat.enemy_peons_near_buildings >= 4 && Economy.stock(armory, IronAxeWeapon.class) == 0)
            return true;
        return posture == Posture.DEFEND && threat.level == Threat.Level.ENGAGED
                && Economy.stock(armory, IronAxeWeapon.class) == 0 && Economy.stock(armory, RubberAxeWeapon.class) == 0;
    }

    private void setArmyState(float now, @NonNull ArmyState s) {
        if (army_state != s) {
            log.info(() -> "army " + army_state + " -> " + s + " (" + army.size() + " units)");
            hold_since = -1f; // both holds (MARCH holdAt, ENGAGE hold) are per-state waits: never inherit a timestamp
            hold_x = -1;
            hold_y = -1;
        }
        army_state = s;
        army_state_since = now;
    }

    // ------------------------------------------------------------------ defend / hold

    private void recallArmy(float now, int @NonNull [] home) {
        List<Unit> all = new ArrayList<>(army);
        all.addAll(reinforcements);
        int[] p = defendCell(home);
        orders.move(all, p[0], p[1]);
        for (Unit u : all)
            jobs.set(u, Jobs.Kind.MOVE, p[0], p[1], null, null, now, TAG_ARMY);
        last_defend_x = -1;
    }

    private int @NonNull [] defendCell(int @NonNull [] home) {
        return plan.along(home[0], home[1], params.tower_ring_radius - 4);
    }

    private boolean threat_serious;

    private @NonNull List<Unit> allFieldUnits() {
        List<Unit> all = new ArrayList<>(home_guard);
        all.addAll(army);
        all.addAll(reinforcements);
        return all;
    }

    private void defend(float now, @NonNull Roster roster, @NonNull Intel intel, @NonNull Threat threat,
            int @NonNull [] home, @NonNull List<Unit> defenders) {
        // where to stand: between the armory and the approaching group, inside the tower ring
        int px, py;
        if (threat.approach_centroid != null) {
            int[] p = plan.toward(home[0], home[1], threat.approach_centroid[0], threat.approach_centroid[1],
                    params.tower_ring_radius - 4);
            px = p[0];
            py = p[1];
        } else {
            int[] p = defendCell(home);
            px = p[0];
            py = p[1];
        }
        int[] ec = threat.approach_centroid;
        // our strength is judged at the fight point: towers only count if their reach covers the enemy clump
        float ours = ec == null ? ownValueNear(roster, px, py, 25) : ownValueNear(roster, ec[0], ec[1], 25);
        float theirs = ec == null ? 0f : enemyValueNear(intel, ec[0], ec[1], 20);
        boolean stunned_enemy = now - chief_brain.last_stun_at < params.stun_window
                && chief_brain.last_stun_cell != null && ec != null
                && BasePlan.dist2(chief_brain.last_stun_cell[0], chief_brain.last_stun_cell[1], ec[0], ec[1]) < 20 * 20;
        int[] target = new int[]{px, py};
        if (ec != null && (ours >= params.engage_ratio * Math.max(0.5f, theirs) || stunned_enemy)) {
            // strong enough: hit the clump, but not beyond the tower line + pursuit range
            float d = BasePlan.dist(home[0], home[1], ec[0], ec[1]);
            if (d <= params.tower_ring_radius + params.defend_pursuit)
                target = ec;
        }
        if (target == ec) {
            // engaging: per-unit targets, spread fire
            micro.fight(now, defenders, intel, ec[0], ec[1], false, true, null, TAG_HOME);
            last_defend_order = now;
            last_defend_x = ec[0];
            last_defend_y = ec[1];
            return;
        }
        // standing at the line: whatever comes into reach gets spread fire as well
        micro.fight(now, defenders, intel, px, py, false, false, null, TAG_HOME);
        boolean changed = BasePlan.dist2(target[0], target[1], last_defend_x, last_defend_y) > 36
                || now - last_defend_order > 8f;
        if (!changed)
            return;
        List<Unit> to_order = new ArrayList<>();
        for (Unit u : defenders) {
            if (!orders.usable(u) || Chieftain.isStunned(u))
                continue;
            if (u.getCurrentBehaviour() instanceof AttackBehaviour)
                continue;
            to_order.add(u);
        }
        if (!to_order.isEmpty()) {
            orders.defend(to_order, target[0], target[1]);
            for (Unit u : to_order)
                jobs.set(u, Jobs.Kind.DEFEND, target[0], target[1], null, null, now, TAG_HOME);
        }
        last_defend_order = now;
        last_defend_x = target[0];
        last_defend_y = target[1];
    }

    /** The home guard stands at the ring and, with the towers, punishes small groups near our buildings. */
    private void homeGuardStep(float now, @NonNull Roster roster, @NonNull Intel intel, int @NonNull [] home) {
        int[] g = defendCell(home);
        for (Unit u : home_guard) {
            if (!orders.usable(u) || Chieftain.isStunned(u))
                continue;
            Jobs.Job j = jobs.get(u);
            boolean idle = Roster.activity(u) == Roster.Activity.IDLE;
            if (j == null || j.kind != Jobs.Kind.DEFEND || (idle && BasePlan.dist2(u.getGridX(), u.getGridY(), g[0],
                    g[1]) > 64)) {
                orders.defend(List.of(u), g[0], g[1]);
                jobs.set(u, Jobs.Kind.DEFEND, g[0], g[1], null, null, now, TAG_HOME);
            }
        }
        int[] c = intel.armyCentroid(home[0], home[1], 45);
        if (c != null && !home_guard.isEmpty()) {
            float theirs = enemyValueNear(intel, c[0], c[1], 15);
            float ours = groupValue(home_guard) + ownTowerCover(roster, c);
            if (theirs > 0f && ours >= params.engage_ratio * theirs) {
                micro.fight(now, home_guard, intel, c[0], c[1], false, true, null, TAG_HOME);
                last_defend_order = now;
            }
        }
    }

    /**
     * Sweep enemy warriors off the gathering fields: the enemy clump nearest to one of our gatherers (or to the
     * armory) within {@code sweep_radius} cells of home, when the army outweighs it by {@code engage_ratio} and it
     * is not under an enemy tower. Returns true while a sweep is on.
     */
    private boolean fieldSweep(float now, @NonNull Roster roster, @NonNull Intel intel, int @NonNull [] home) {
        if (army.size() < 6 || !params.field_sweep)
            return false;
        int r = params.sweep_radius;
        int[] target = null;
        int best_d2 = Integer.MAX_VALUE;
        // a sweep that is on follows its clump until it is gone: the "near a gatherer" test below flaps as the
        // gatherers run home and come back, and re-deciding every tick had the army marching in place
        boolean ongoing = sweep_x >= 0;
        for (Unit e : intel.warriors) {
            if (e.isDead() || Chieftain.isFrozen(e))
                continue;
            int d2 = BasePlan.dist2(e.getGridX(), e.getGridY(), home[0], home[1]);
            if (d2 > r * r)
                continue;
            if (ongoing) {
                int ds = BasePlan.dist2(e.getGridX(), e.getGridY(), sweep_x, sweep_y);
                if (ds <= 25 * 25 && ds < best_d2) {
                    best_d2 = ds;
                    target = new int[]{e.getGridX(), e.getGridY()};
                }
                continue;
            }
            // near one of our gatherers?
            boolean near_peon = false;
            for (Unit p : roster.peons) {
                Roster.Activity a = Roster.activity(p);
                if ((a == Roster.Activity.GATHER_TREE || a == Roster.Activity.GATHER_IRON
                        || a == Roster.Activity.GATHER_ROCK)
                        && BasePlan.dist2(p.getGridX(), p.getGridY(), e.getGridX(), e.getGridY()) <= 20 * 20) {
                    near_peon = true;
                    break;
                }
            }
            if (!near_peon && d2 > 45 * 45)
                continue;
            // a fresh sweep far from home waits out the cooldown after the last one (no march-return-march churn)
            if (d2 > 45 * 45 && now < sweep_cooldown_until)
                continue;
            if (d2 < best_d2) {
                best_d2 = d2;
                target = new int[]{e.getGridX(), e.getGridY()};
            }
        }
        // an enemy tower (or foundation) planted on our fields shoots every gatherer that walks past: it is a
        // sweep target too, taken down by the army's throws (a lone tower dies to thirty warriors in seconds)
        LandBuilding building = null;
        if (target == null) {
            int best_b = Integer.MAX_VALUE;
            List<LandBuilding> cands = new ArrayList<>(intel.towers);
            cands.addAll(intel.construction_sites);
            for (LandBuilding t : cands) {
                if (t.isDead())
                    continue;
                int d2 = BasePlan.dist2(t.getGridX(), t.getGridY(), home[0], home[1]);
                if (d2 > r * r)
                    continue;
                boolean ours_side = BasePlan.dist2(t.getGridX(), t.getGridY(), plan.start_x,
                        plan.start_y) < BasePlan.dist2(t.getGridX(), t.getGridY(), plan.enemy_x, plan.enemy_y);
                if (!ours_side)
                    continue;
                if (d2 < best_b) {
                    best_b = d2;
                    building = t;
                }
            }
            if (building != null)
                target = new int[]{building.getGridX(), building.getGridY()};
        }
        if (target == null) {
            if (sweep_x >= 0) {
                log.info("field sweep done");
                sweep_x = -1;
                sweep_y = -1;
                sweep_cooldown_until = now + 12f;
            }
            return false;
        }
        int[] ec = building != null ? target : intel.armyCentroid(target[0], target[1], 15);
        if (ec == null)
            return false;
        float theirs = enemyValueNear(intel, ec[0], ec[1], 15);
        float ours = groupValue(army);
        boolean covered = building == null && coveringTower(intel, ec[0], ec[1], ec) != null;
        if ((theirs <= 0f && building == null) || ours < params.engage_ratio * theirs || covered) {
            if (sweep_x >= 0) {
                log.info("field sweep called off");
                sweep_x = -1;
                sweep_y = -1;
                sweep_cooldown_until = now + 12f;
            }
            return false;
        }
        if (sweep_x < 0) {
            final float fo = ours, ft = theirs;
            final String what = building != null ? "tower/foundation" : "enemy";
            log.info(() -> String.format("field sweep: %s %.0f at %d,%d vs army %.0f", what, ft, ec[0], ec[1], fo));
            sweep_since = now;
        }
        sweep_x = ec[0];
        sweep_y = ec[1];
        micro.fight(now, army, intel, ec[0], ec[1], building != null, true, building, TAG_ARMY);
        return true;
    }

    private float ownTowerCover(@NonNull Roster roster, int @NonNull [] c) {
        float v = 0f;
        for (LandBuilding t : roster.towers)
            if (BasePlan.dist2(t.getGridX(), t.getGridY(), c[0], c[1]) <= params.tower_reach2)
                v += towerValue(t);
        return v;
    }

    /** BUILD_UP: guard at the ring, army idles at the staging cell; small hostile groups get punished. */
    private int sweep_x = -1;
    private int sweep_y = -1;
    private float sweep_since = -100f;
    private float sweep_cooldown_until = -100f;

    private void holdHome(float now, @NonNull Roster roster, @NonNull Intel intel, int @NonNull [] home) {
        homeGuardStep(now, roster, intel, home);
        // enemy groups squatting on our gathering fields starve the armory (every gatherer near them is recalled):
        // a small one gets swept off by the staged army when it is clearly stronger, and the army walks back after
        if (fieldSweep(now, roster, intel, home))
            return;
        for (Unit u : army) {
            if (!orders.usable(u) || Chieftain.isStunned(u))
                continue;
            Jobs.Job j = jobs.get(u);
            boolean idle = Roster.activity(u) == Roster.Activity.IDLE;
            boolean far = BasePlan.dist2(u.getGridX(), u.getGridY(), staging_x, staging_y) > 100;
            if (j == null || j.kind != Jobs.Kind.HOLD || (idle && far)) {
                orders.attackMove(u, staging_x, staging_y);
                jobs.set(u, Jobs.Kind.HOLD, staging_x, staging_y, null, null, now, TAG_ARMY);
            }
        }
        // punish raiding parties near our buildings with the staged army as well
        int[] c = intel.armyCentroid(home[0], home[1], 45);
        if (c != null) {
            float theirs = enemyValueNear(intel, c[0], c[1], 15);
            float ours = groupValue(home_guard) + groupValue(army) + ownTowerCover(roster, c);
            if (theirs > 0f && ours >= params.engage_ratio * theirs) {
                List<Unit> to = new ArrayList<>(home_guard);
                to.addAll(army);
                micro.fight(now, to, intel, c[0], c[1], false, true, null, TAG_HOME);
                last_defend_order = now;
            }
        }
    }

    // ------------------------------------------------------------------ attack

    private void armyStep(float now, float dt, @NonNull Roster roster, @NonNull Intel intel, @NonNull Threat threat,
            int @NonNull [] home) {
        int[] c = centroid(army);
        if (c == null) {
            setArmyState(now, ArmyState.STAGING);
            return;
        }
        chooseObjective(now, intel, roster, c);
        if (objective_x < 0) {
            // nothing left to attack: hold
            setArmyState(now, ArmyState.STAGING);
            return;
        }
        switch (army_state) {
            case STAGING -> {
                // gather at the staging cell, then go
                for (Unit u : army) {
                    if (!orders.usable(u))
                        continue;
                    Jobs.Job j = jobs.get(u);
                    if (j == null || j.kind != Jobs.Kind.HOLD || (Roster.activity(u) == Roster.Activity.IDLE
                            && BasePlan.dist2(u.getGridX(), u.getGridY(), staging_x, staging_y) > 144)) {
                        orders.attackMove(u, staging_x, staging_y);
                        jobs.set(u, Jobs.Kind.HOLD, staging_x, staging_y, null, null, now, TAG_ARMY);
                    }
                }
                int near = 0;
                for (Unit u : army)
                    if (BasePlan.dist2(u.getGridX(), u.getGridY(), staging_x, staging_y) <= 144)
                        near++;
                boolean chief_ok = roster.chieftain == null || BasePlan.dist2(roster.chieftain.getGridX(),
                        roster.chieftain.getGridY(), staging_x, staging_y) <= 400 || now - army_state_since > 40f;
                if (army.size() >= 3 && (near >= params.leg_arrival_fraction * army.size()
                        || now - army_state_since > 30f)
                        && chief_ok) {
                    leg_x = -1;
                    leg_y = -1;
                    leg_skips = 0;
                    nextLeg(now, c);
                    setArmyState(now, ArmyState.MARCH);
                }
            }
            case MARCH -> march(now, roster, intel, c);
            case ENGAGE -> engage(now, roster, intel, c);
            case ASSAULT -> assault(now, roster, intel, c);
            case SIEGE_PREP -> siegePrep(now, roster, intel, c);
            case SIEGE_CYCLE -> siegeCycle(now, roster, intel, c);
            case RETREAT -> retreat(now, roster, intel, c, home);
        }
    }

    private void chooseObjective(float now, @NonNull Intel intel, @NonNull Roster roster, int @NonNull [] c) {
        Selectable<?> target = null;
        // 1. an enemy foundation near the army is free to kill
        for (LandBuilding s : intel.construction_sites)
            if (BasePlan.dist2(s.getGridX(), s.getGridY(), c[0], c[1]) <= 30 * 30) {
                target = s;
                break;
            }
        if (target == null && !intel.armories.isEmpty())
            target = Intel.nearest(intel.armories, c[0], c[1]);
        if (target == null && !intel.quarters.isEmpty())
            target = Intel.nearest(intel.quarters, c[0], c[1]);
        if (target == null && !intel.towers.isEmpty())
            target = Intel.nearest(intel.towers, c[0], c[1]);
        if (target == null) {
            // only units left: keep the current unit objective while it lives and stays near where it was
            if (objective instanceof Unit ou && !ou.isDead() && !ou.isMounted()
                    && BasePlan.dist2(ou.getGridX(), ou.getGridY(), objective_x, objective_y) <= 400) {
                objective_x = ou.getGridX();
                objective_y = ou.getGridY();
                return;
            }
            if (!intel.warriors.isEmpty())
                target = Intel.nearest(intel.warriors, c[0], c[1]);
            else if (!intel.peons.isEmpty())
                target = Intel.nearest(intel.peons, c[0], c[1]);
            else if (!intel.chieftains.isEmpty())
                target = intel.chieftains.getFirst();
        }
        if (target == null) {
            objective = null;
            objective_x = -1;
            objective_y = -1;
            return;
        }
        if (objective != target) {
            final Selectable<?> t = target;
            log.info(
                    () -> "objective: " + (t instanceof LandBuilding ? t.toString() : "unit") + " at " + t.getGridX() + "," + t.getGridY());
        }
        objective = target;
        objective_x = target.getGridX();
        objective_y = target.getGridY();
    }

    /**
     * Order the next leg of the march. Legs advance monotonically from the previous leg point toward the objective
     * (never from the centroid, which lags behind the front of a big army), so the clump keeps moving forward.
     */
    private void nextLeg(float now, int @NonNull [] c) {
        int from_x = leg_x >= 0 ? leg_x : c[0];
        int from_y = leg_y >= 0 ? leg_y : c[1];
        // if the army is far behind the last leg point (regrouped, retreated), restart from the centroid
        if (leg_x >= 0 && BasePlan.dist(c[0], c[1], leg_x, leg_y) > params.leg_length + 15) {
            from_x = c[0];
            from_y = c[1];
        }
        float d = BasePlan.dist(from_x, from_y, objective_x, objective_y);
        int[] p = d <= params.leg_length + 4 ? new int[]{objective_x, objective_y} : plan.toward(from_x, from_y,
                objective_x, objective_y, params.leg_length);
        // keep legs on land
        if (!plan.sameIsland(p[0], p[1]) || grid.getRegion(p[0], p[1]) == null) {
            p = plan.toward(from_x, from_y, objective_x, objective_y, params.leg_length / 2f);
            if (!plan.sameIsland(p[0], p[1]) || grid.getRegion(p[0], p[1]) == null)
                p = new int[]{objective_x, objective_y};
        }
        prev_leg_x = leg_x >= 0 ? leg_x : c[0];
        prev_leg_y = leg_y >= 0 ? leg_y : c[1];
        leg_x = p[0];
        leg_y = p[1];
        leg_ordered_at = now;
        orderLeg(now);
    }

    private void orderLeg(float now) {
        List<Unit> to = new ArrayList<>();
        for (Unit u : army)
            if (orders.usable(u) && !Chieftain.isStunned(u) && !(u.getCurrentBehaviour() instanceof AttackBehaviour)
                    && !(u.getPrimaryController() instanceof HuntController))
                to.add(u);
        orders.attackMove(to, leg_x, leg_y);
        for (Unit u : to)
            jobs.set(u, Jobs.Kind.ATTACK_MOVE, leg_x, leg_y, null, null, now, TAG_ARMY);
    }

    /** Radius within which a unit counts as having arrived at a leg point; grows with the army's footprint. */
    private int arrivalRadius() {
        return Math.max(params.leg_arrival_radius, (int) (1.6 * Math.sqrt(army.size())) + 4);
    }

    /**
     * Move units that fell far behind the clump into the reinforcement pool so the gate is not blocked. Measured
     * from the centroid (a leg point far ahead would flag everyone), and never more than a fifth at a time.
     */
    private void dropStragglers(float now, int @NonNull [] c) {
        if (leg_x < 0 || army.size() < 8)
            return;
        List<Unit> behind = new ArrayList<>();
        for (Unit u : army) {
            if (!orders.usable(u))
                continue;
            float d = BasePlan.dist(u.getGridX(), u.getGridY(), c[0], c[1]);
            if (d > 50f)
                behind.add(u);
        }
        int max = Math.max(1, army.size() / 5);
        if (behind.size() > max)
            behind = behind.subList(0, max);
        for (Unit u : behind) {
            army.remove(u);
            reinforcements.add(u);
            // stamped as en route so the reinforcement step steers it after the army instead of parking it home
            orders.attackMove(u, c[0], c[1]);
            jobs.set(u, Jobs.Kind.ATTACK_MOVE, c[0], c[1], null, null, now, TAG_REINF);
        }
    }

    /**
     * Contact test shared by MARCH and ENGAGE so the two states agree: enemy warriors close to our centroid, or
     * a real enemy value near it. A trickle (a few units against a big army) is not contact: the march goes on
     * and the micro pass deals with whatever comes into reach.
     */
    private boolean contact(@NonNull Intel intel, int @NonNull [] c, float ours_all) {
        float theirs = enemyValueNear(intel, c[0], c[1], 20);
        int near_enemies = intel.warriorsNear(c[0], c[1], 12);
        boolean trickle = theirs < 8f && theirs < 0.12f * ours_all && near_enemies < 4;
        return (near_enemies > 0 || theirs >= 3f) && !trickle;
    }

    private void march(float now, @NonNull Roster roster, @NonNull Intel intel, int @NonNull [] c) {
        int[] ec0 = intel.armyCentroid(c[0], c[1], 30);
        if (ec0 != null && enemyChiefResponse(now, roster, intel, c, ec0))
            return;
        // contact?
        float theirs = enemyValueNear(intel, c[0], c[1], 20);
        float ours_all = groupValue(army);
        if (contact(intel, c, ours_all)) {
            engage_start_value = theirs;
            setArmyState(now, ArmyState.ENGAGE);
            return;
        }
        // a trickle: whoever has something in reach throws, the rest keeps walking
        if (theirs > 0f && leg_x >= 0)
            micro.fight(now, army, intel, leg_x, leg_y, false, false, null, TAG_ARMY);
        // an armed enemy chieftain ahead: do not walk the clump into its disc before our own chieftain is up
        // with the clump (whoever toots first wins the exchange); wait for it a few seconds
        Unit echief = Chieftain.nearestEnemyChief(intel, c[0], c[1]);
        Unit chief = roster.chieftain;
        if (echief != null && chief != null && orders.usable(chief) && Chieftain.stunReady(chief)
                && !Chieftain.isFrozen(echief) && Chieftain.stunProgress(echief) >= 0.6f
                && BasePlan.dist2(echief.getGridX(), echief.getGridY(), c[0], c[1]) <= 32 * 32
                && BasePlan.dist2(chief.getGridX(), chief.getGridY(), c[0], c[1]) > 12 * 12) {
            if (chief_wait_since < 0f) {
                chief_wait_since = now;
                log.info("march: armed enemy chieftain ahead, waiting for ours to close up");
            }
            if (now - chief_wait_since < 12f) {
                leg_ordered_at = now;
                return;
            }
        } else {
            chief_wait_since = -1f;
        }
        // walking into a manned tower's reach away from the objective (creep towers): treat it like the objective
        LandBuilding creep = coveringTower(intel, c[0], c[1], c);
        // objective in tower cover?
        float d_obj = BasePlan.dist(c[0], c[1], objective_x, objective_y);
        if (d_obj <= 26f || creep != null) {
            LandBuilding covering = d_obj <= 26f ? coveringTower(intel, objective_x, objective_y, c) : creep;
            int tx = covering != null ? covering.getGridX() : objective_x;
            int ty = covering != null ? covering.getGridY() : objective_y;
            float t_val = enemyValueNear(intel, tx, ty, 20);
            float ours = ours_all;
            float crush = posture == Posture.ALL_IN ? 1.6f : 2.5f;
            if (covering != null && d_obj > 26f) {
                // the creep tower becomes the objective for the assault logic
                objective = covering;
                objective_x = tx;
                objective_y = ty;
            }
            // a manned tower shoots at 3x: every covering tower is taken down first (corps demolition or the
            // stun cycle); an assault under towers only when crushing them with the corps at hand
            int corps_near = corpsNear(tx, ty, 45);
            boolean crushing = ours >= crush * Math.max(1f, t_val) && corps_near >= params.corps_min_demolish
                    && ours >= 3f * Math.max(1f, t_val);
            if (covering != null && !crushing) {
                boolean chief_ok = roster.chieftain != null && orders.usable(roster.chieftain)
                        && Chieftain.stunProgress(roster.chieftain) >= 0.5f;
                if (chief_ok || corps_near >= params.corps_min_demolish) {
                    siege_tower = covering;
                    siege_prep_since = now;
                    stand_x = -1;
                    stand_fails = 0;
                    setArmyState(now, ArmyState.SIEGE_PREP);
                    return;
                }
                // no usable chieftain and no corps: only assault if clearly stronger than the tower cluster
                if (ours < 2.5f * Math.max(1f, t_val)) {
                    holdAt(now, c, "waiting for strength vs towers");
                    return;
                }
            }
            setArmyState(now, ArmyState.ASSAULT);
            return;
        }
        dropStragglers(now, c);
        int r = arrivalRadius();
        int arrived = 0;
        int within_leg = 0;
        for (Unit u : army) {
            int d2 = BasePlan.dist2(u.getGridX(), u.getGridY(), leg_x, leg_y);
            if (d2 <= r * r)
                arrived++;
            if (d2 <= (r + params.leg_length) * (r + params.leg_length))
                within_leg++;
        }
        boolean timeout = now - leg_ordered_at > params.leg_timeout;
        // most have arrived and nobody is more than a leg behind: the next leg does not string the army out
        boolean gate = arrived >= params.leg_arrival_fraction * army.size() && within_leg >= 0.95f * army.size();
        // a big batch of reinforcements on its way: wait for it at the leg point (both walk at the same speed,
        // so it never catches a moving army and would arrive piecemeal after the fight has started)
        int en_route = 0;
        for (Unit u : reinforcements) {
            Jobs.Job j = jobs.get(u);
            if (j != null && j.kind == Jobs.Kind.ATTACK_MOVE && j.tag == TAG_REINF
                    && BasePlan.dist2(u.getGridX(), u.getGridY(), c[0], c[1]) <= 60 * 60)
                en_route++;
        }
        if (en_route >= Math.max(4, (int) (0.25f * army.size())) && gate) {
            if (reinf_wait_since < 0f) {
                reinf_wait_since = now;
                final int n = en_route;
                log.info(() -> "march: waiting for " + n + " reinforcements");
            }
            if (now - reinf_wait_since < 30f) {
                leg_ordered_at = now; // no timeout while waiting
                return;
            }
        } else {
            reinf_wait_since = -1f;
        }
        if (gate || timeout) {
            if (timeout && arrived < 0.3f * army.size()) {
                // slow going (fights on the way) or an unreachable leg point: restart the leg from the clump;
                // after three such timeouts with nobody arriving give the objective directly
                leg_skips++;
                if (leg_skips >= 3 && arrived < 0.1f * army.size()) {
                    leg_x = objective_x;
                    leg_y = objective_y;
                    leg_ordered_at = now;
                    orderLeg(now);
                    return;
                }
                leg_x = -1;
                leg_y = -1;
            } else {
                leg_skips = 0;
            }
            nextLeg(now, c);
        } else if (now - leg_ordered_at > 6f) {
            // idle units that stopped short: nudge them toward the leg
            for (Unit u : army) {
                if (!orders.usable(u) || Chieftain.isStunned(u))
                    continue;
                if (Roster.activity(u) == Roster.Activity.IDLE && BasePlan.dist2(u.getGridX(), u.getGridY(), leg_x,
                        leg_y) > r * r) {
                    orders.attackMove(u, leg_x, leg_y);
                    jobs.set(u, Jobs.Kind.ATTACK_MOVE, leg_x, leg_y, null, null, now, TAG_ARMY);
                }
            }
        }
    }

    private void holdAt(float now, int @NonNull [] c, @NonNull String why) {
        if (hold_since < 0f) {
            hold_since = now;
            log.info("army holds: " + why);
        }
        for (Unit u : army) {
            if (!orders.usable(u) || Chieftain.isStunned(u))
                continue;
            if (Roster.activity(u) == Roster.Activity.IDLE && BasePlan.dist2(u.getGridX(), u.getGridY(), c[0],
                    c[1]) > 100) {
                orders.attackMove(u, c[0], c[1]);
                jobs.set(u, Jobs.Kind.HOLD, c[0], c[1], null, null, now, TAG_ARMY);
            }
        }
        if (now - hold_since > 90f) {
            // waited long enough: retreat home and rebuild strength
            hold_since = -1f;
            setArmyState(now, ArmyState.RETREAT);
        }
    }

    /** A manned enemy tower whose reach covers the cell, nearest to the army, or null. */
    private @Nullable LandBuilding coveringTower(@NonNull Intel intel, int gx, int gy, int @NonNull [] c) {
        LandBuilding best = null;
        int best_d2 = Integer.MAX_VALUE;
        for (LandBuilding t : intel.manned_towers) {
            if (Chieftain.towerStunned(t))
                continue;
            if (BasePlan.dist2(t.getGridX(), t.getGridY(), gx, gy) <= (params.tower_reach2 + 100)) {
                int d2 = BasePlan.dist2(t.getGridX(), t.getGridY(), c[0], c[1]);
                if (d2 < best_d2) {
                    best_d2 = d2;
                    best = t;
                }
            }
        }
        return best;
    }

    private float last_engage_log = -100f;
    /** Enemy value measured at the last engagement (undiscounted), for the retreat re-evaluation. */
    private float last_fight_theirs;
    private float last_retreat_at = -1000f;
    private float cap_since = -1f;
    private float reinf_wait_since = -1f;
    private float chief_wait_since = -1f;
    private boolean attack_odds_ok;
    private float last_siege_log = -100f;
    /** Where the army last fought (for the chieftain's approach). */
    private int fight_x = -1;
    private int fight_y = -1;
    private float fight_at = -100f;

    /**
     * Compare the two sides around the point where they meet: our value within 25 cells of the midpoint between
     * the centroids (the part of the army that reaches the fight in the next ~10 s) against the larger of the
     * enemy's value around its own centroid and around that midpoint.
     */
    private float @NonNull [] sidesAt(@NonNull Roster roster, @NonNull Intel intel, int @NonNull [] c,
            int @NonNull [] ec) {
        int mx = (c[0] + ec[0]) / 2;
        int my = (c[1] + ec[1]) / 2;
        float ours = ownValueNear(roster, mx, my, 25);
        float theirs = Math.max(enemyValueNear(intel, ec[0], ec[1], 20), enemyValueNear(intel, mx, my, 25));
        return new float[]{ours, theirs};
    }

    private void engage(float now, @NonNull Roster roster, @NonNull Intel intel, int @NonNull [] c) {
        int[] ec = intel.armyCentroid(c[0], c[1], 25);
        int present = ec == null ? 0 : intel.warriorsNear(ec[0], ec[1], 20) + intel.unitsNear(ec[0], ec[1], 12);
        float ours = 0f;
        float theirs = 0f;
        if (ec != null) {
            float[] s = sidesAt(roster, intel, c, ec);
            ours = s[0];
            theirs = s[1];
            last_fight_theirs = theirs;
            fight_x = ec[0];
            fight_y = ec[1];
            fight_at = now;
        }
        boolean stun_landing = now - chief_brain.last_stun_at < params.stun_window;
        if (stun_landing)
            theirs *= 0.35f;
        if (ec != null && enemyChiefResponse(now, roster, intel, c, ec))
            return;
        if (now - last_engage_log >= 4f && ec != null) {
            last_engage_log = now;
            int stunned_ours = 0;
            for (Unit u : roster.warriors)
                if (Chieftain.isFrozen(u))
                    stunned_ours++;
            Unit echief = Chieftain.nearestEnemyChief(intel, ec[0], ec[1]);
            Unit chief = roster.chieftain;
            final float fo = ours, ft = theirs;
            final int so = stunned_ours;
            final int[] fec = ec;
            final String ech = echief == null ? "none" : (int) BasePlan.dist(echief.getGridX(), echief.getGridY(),
                    ec[0], ec[1]) + "c/" + (int) (100 * Chieftain.stunProgress(echief)) + "%" + (Chieftain.isCasting(
                            echief) ? " CASTING" : "");
            final String och = chief == null || chief.isDead() ? "none" : (int) BasePlan.dist(chief.getGridX(),
                    chief.getGridY(), ec[0], ec[1]) + "c/" + (int) (100 * Chieftain.stunProgress(chief)) + "%";
            log.info(() -> String.format(
                    "engage: ours=%.1f theirs=%.1f R=%.2f army=%d stunned=%d echief=%s chief=%s ours@fp=%d theirs@fp=%d %s",
                    fo, ft, fo / Math.max(0.1f, ft), army.size(), so, ech, och,
                    Chieftain.ownWarriorsNear(roster.warriors, fec[0], fec[1], 20), intel.warriorsNear(fec[0], fec[1],
                            20), micro));
        }
        if ((ec == null || present == 0 || !contact(intel, c, groupValue(army))) && now - army_state_since > 3f) {
            // won or they left: press on from where the army stands
            hold_since = -1f;
            leg_x = -1;
            leg_y = -1;
            nextLeg(now, c);
            setArmyState(now, ArmyState.MARCH);
            return;
        }
        if (ec == null)
            return;
        float R = ours / Math.max(0.1f, theirs);
        if (R < params.retreat_ratio && !stun_landing) {
            setArmyState(now, ArmyState.RETREAT);
            return;
        }
        // fighting inside a manned tower's reach bleeds the army (a tower hits at 3x): unless the stun is on or
        // we crush them, pull out and run the stun cycle on that tower
        LandBuilding cover = coveringTower(intel, ec[0], ec[1], c);
        if (cover != null && !stun_landing && !Chieftain.towerStunned(cover)) {
            float t_val = enemyValueNear(intel, cover.getGridX(), cover.getGridY(), 20);
            if (ours >= 2.5f * Math.max(1f, t_val)) {
                objective = cover;
                objective_x = cover.getGridX();
                objective_y = cover.getGridY();
                log.info("engage: under a tower but crushing: assault it");
                setArmyState(now, ArmyState.ASSAULT);
            } else {
                siege_tower = cover;
                siege_prep_since = now;
                stand_x = -1;
                stand_fails = 0;
                log.info(
                        () -> "engage: under tower cover at " + cover.getGridX() + "," + cover.getGridY() + ": pulling out for the stun cycle");
                setArmyState(now, ArmyState.SIEGE_PREP);
            }
            return;
        }
        if (R >= params.engage_ratio || stun_landing) {
            hold_since = -1f;
            micro.fight(now, army, intel, ec[0], ec[1], false, true, objective, TAG_ARMY);
            last_engage_order = now;
            last_engage_x = ec[0];
            last_engage_y = ec[1];
        } else {
            // hold: stop advancing, fight what comes into reach, wait for the stun / reinforcements
            if (hold_since < 0f)
                hold_since = now;
            micro.fight(now, army, intel, c[0], c[1], false, false, null, TAG_ARMY);
            if (now - hold_since > 45f && R < 1f) {
                hold_since = -1f;
                setArmyState(now, ArmyState.RETREAT);
            }
        }
    }

    private float last_scatter = -100f;
    private float last_snipe = -100f;

    /**
     * Enemy chieftain counterplay. Returns true when it took over the army's orders this tick:
     * <ul>
     * <li>casting within reach of our clump: the units in the outer part of its disc (11-19 cells, the only ones
     * that can leave the 18-cell radius before the +2.15 s snapshot) move radially outward; the inner ones keep
     * fighting, they are frozen either way;</li>
     * <li>armed (stun nearly ready), close and exposed (we are clearly stronger next to it): snipe it.</li>
     * </ul>
     */
    private boolean enemyChiefResponse(float now, @NonNull Roster roster, @NonNull Intel intel, int @NonNull [] c,
            int @NonNull [] ec) {
        Unit echief = Chieftain.nearestEnemyChief(intel, c[0], c[1]);
        if (echief == null || Chieftain.isStunned(echief))
            return false;
        int ex = echief.getGridX();
        int ey = echief.getGridY();
        float d_army = BasePlan.dist(ex, ey, c[0], c[1]);
        boolean casting = Chieftain.isCasting(echief);
        boolean armed = Chieftain.stunProgress(echief) >= params.counter_toot_progress;
        if (casting && d_army <= 26f && now - last_scatter > 5f) {
            last_scatter = now;
            int n = 0;
            for (Unit u : army) {
                if (!orders.usable(u) || Chieftain.isStunned(u))
                    continue;
                int dx = u.getGridX() - ex;
                int dy = u.getGridY() - ey;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > 19.5f || d < 14f)
                    continue;
                int tx = Math.round(ex + dx / d * 23f);
                int ty = Math.round(ey + dy / d * 23f);
                orders.move(u, tx, ty);
                jobs.set(u, Jobs.Kind.MOVE, tx, ty, null, null, now, TAG_ARMY);
                n++;
            }
            final int fn = n;
            log.info(() -> "enemy chieftain casting: scattering " + fn + " outer units");
            return n > 0;
        }
        if (d_army > 26f)
            return false;
        // exposed? our warriors near it vs enemies near it, and no manned tower covering it
        int ours_near = Chieftain.ownWarriorsNear(roster.warriors, ex, ey, 12);
        float theirs_near = enemyValueNear(intel, ex, ey, 12);
        float ours_val = ownValueNear(roster, ex, ey, 14);
        boolean covered = coveringTower(intel, ex, ey, c) != null;
        int need = armed ? 8 : 10;
        float edge = armed ? 1.3f : 1.6f;
        if (ours_near >= need && ours_val >= edge * Math.max(1f, theirs_near) && !covered && now - last_snipe > 6f) {
            last_snipe = now;
            List<Unit> hunters = new ArrayList<>();
            for (Unit u : army)
                if (orders.usable(u) && !Chieftain.isStunned(u)
                        && BasePlan.dist2(u.getGridX(), u.getGridY(), ex, ey) <= 16 * 16)
                    hunters.add(u);
            if (!hunters.isEmpty()) {
                orders.attack(hunters, echief);
                for (Unit u : hunters)
                    jobs.set(u, Jobs.Kind.HUNT, ex, ey, echief, null, now, TAG_ARMY);
                log.info(() -> "sniping the enemy chieftain with " + hunters.size() + " warriors");
                return true;
            }
        }
        return false;
    }

    /** Corps peons within a radius of a cell. */
    private int corpsNear(int gx, int gy, int radius) {
        int n = 0;
        for (Unit p : corps)
            if (BasePlan.dist2(p.getGridX(), p.getGridY(), gx, gy) <= radius * radius)
                n++;
        return n;
    }

    private void assault(float now, @NonNull Roster roster, @NonNull Intel intel, int @NonNull [] c) {
        if (objective == null || objective.isDead()) {
            setArmyState(now, ArmyState.MARCH);
            nextLeg(now, c);
            return;
        }
        // a manned tower covering the objective comes back to life (or a new one): back to the siege logic
        // unless the corps is already tearing it down or a stun is on
        LandBuilding cover = coveringTower(intel, objective_x, objective_y, c);
        boolean stun_on = now - chief_brain.last_stun_at < params.stun_window;
        if (cover != null && !stun_on && corps_target != cover && groupValue(army) < 3f * Math.max(1f,
                enemyValueNear(intel, cover.getGridX(), cover.getGridY(), 20))) {
            siege_tower = cover;
            siege_prep_since = now;
            stand_x = -1;
            stand_fails = 0;
            log.info("assault: a manned tower covers the objective, back to the siege");
            setArmyState(now, ArmyState.SIEGE_PREP);
            return;
        }
        int[] ec = intel.armyCentroid(c[0], c[1], 25);
        float theirs = enemyValueNear(intel, c[0], c[1], 20);
        float ours = ownValueNear(roster, c[0], c[1], 20);
        if (ec != null) {
            float[] s = sidesAt(roster, intel, c, ec);
            ours = s[0];
            theirs = Math.max(theirs, s[1]);
            last_fight_theirs = theirs;
        }
        fight_x = objective_x;
        fight_y = objective_y;
        fight_at = now;
        boolean stun_landing = now - chief_brain.last_stun_at < params.stun_window;
        if (!stun_landing && theirs > 0f && ours / theirs < params.retreat_ratio) {
            setArmyState(now, ArmyState.RETREAT);
            return;
        }
        int near_enemies = intel.warriorsNear(c[0], c[1], 12);
        if (near_enemies >= 3 && ours / Math.max(0.1f, theirs) < params.engage_ratio && !stun_landing) {
            engage_start_value = theirs;
            setArmyState(now, ArmyState.ENGAGE);
            return;
        }
        if (ec != null && enemyChiefResponse(now, roster, intel, c, ec))
            return;
        // the corps tears down the nearest tower or foundation by the objective (or the objective itself)
        LandBuilding demolish = null;
        int best_d2 = 24 * 24;
        for (LandBuilding t : intel.towers) {
            int d2 = BasePlan.dist2(t.getGridX(), t.getGridY(), objective_x, objective_y);
            if (d2 < best_d2) {
                best_d2 = d2;
                demolish = t;
            }
        }
        if (demolish == null && objective instanceof LandBuilding ob && !ob.isDead())
            demolish = ob;
        if (demolish != null)
            corpsDemolish(now, demolish, c);
        // per-unit targets; units without one throw at the towers and the objective, or walk up to it
        micro.fight(now, army, intel, objective_x, objective_y, true, true, objective, TAG_ARMY);
        last_engage_order = now;
        last_engage_x = objective_x;
        last_engage_y = objective_y;
    }

    // ------------------------------------------------------------------ siege (stun cycle vs towers)

    private int stand_fails;
    private float stand_since;
    private int stand_best_d2;
    private boolean stand_pre_reached;
    private final List<int[]> stand_blacklist = new ArrayList<>();

    private void siegePrep(float now, @NonNull Roster roster, @NonNull Intel intel, int @NonNull [] c) {
        Unit chief = roster.chieftain;
        if (siege_tower == null || siege_tower.isDead() || !intel.manned_towers.contains(siege_tower)) {
            siege_tower = null;
            stand_x = -1;
            setArmyState(now, ArmyState.MARCH);
            nextLeg(now, c);
            return;
        }
        float t_val = enemyValueNear(intel, siege_tower.getGridX(), siege_tower.getGridY(), 20);
        float ours = groupValue(army);
        // a stun that just landed next to the tower is the siege stun: use it now
        if (now - chief_brain.last_stun_at < 4f && chief_brain.last_stun_cell != null
                && BasePlan.dist2(chief_brain.last_stun_cell[0], chief_brain.last_stun_cell[1], siege_tower.getGridX(),
                        siege_tower.getGridY()) <= 20 * 20) {
            siege_cast_at = chief_brain.last_stun_at;
            stand_blacklist.clear();
            stand_fails = 0;
            log.info("siege: using the stun that just landed");
            setArmyState(now, ArmyState.SIEGE_CYCLE);
            return;
        }
        // their field army came out to us (outside the tower's umbrella): that is a field battle we want
        int[] ec = intel.armyCentroid(c[0], c[1], 25);
        if (ec != null && now - last_siege_log >= 4f) {
            last_siege_log = now;
            float[] s = sidesAt(roster, intel, c, ec);
            int frozen = 0;
            for (Unit u : army)
                if (Chieftain.isFrozen(u))
                    frozen++;
            final int ff = frozen;
            final int[] fec = ec;
            log.info(() -> String.format(
                    "siege line: ours=%.1f theirs=%.1f army=%d frozen=%d chief=%s ec=%d,%d tower=%d,%d",
                    s[0], s[1], army.size(), ff, chief == null ? "none" : (int) (100 * Chieftain.stunProgress(
                            chief)) + "%",
                    fec[0], fec[1], siege_tower.getGridX(), siege_tower.getGridY()));
        }
        if (ec != null) {
            float[] s = sidesAt(roster, intel, c, ec);
            // stricter than the test engage() uses (no manned tower within 21 cells of their clump, and a short
            // dwell), so the two states cannot disagree and flap
            int nearest_tower_d2 = Integer.MAX_VALUE;
            for (LandBuilding t : intel.manned_towers)
                if (!Chieftain.towerStunned(t))
                    nearest_tower_d2 = Math.min(nearest_tower_d2, BasePlan.dist2(t.getGridX(), t.getGridY(), ec[0],
                            ec[1]));
            boolean out = nearest_tower_d2 > 21 * 21 && now - army_state_since > 4f;
            boolean stun_landing = now - chief_brain.last_stun_at < params.stun_window;
            boolean about_to_cast = chief != null && orders.usable(chief) && Chieftain.stunReady(chief)
                    && BasePlan.dist2(chief.getGridX(), chief.getGridY(), ec[0], ec[1]) <= 15 * 15;
            if (s[1] > 0f && s[0] / s[1] < params.retreat_ratio && !stun_landing && !about_to_cast) {
                log.info("siege: outgunned at the line, retreating");
                siege_tower = null;
                stand_x = -1;
                setArmyState(now, ArmyState.RETREAT);
                return;
            }
            if (out && s[0] >= params.engage_ratio * Math.max(1f, s[1])) {
                log.info("siege: their army is outside the towers, engaging it");
                engage_start_value = s[1];
                setArmyState(now, ArmyState.ENGAGE);
                return;
            }
        }
        // the demolition corps tears the tower down while the warriors hold outside its reach (it shoots peons
        // only when no warrior is in range, and a peon swing does 6 flat damage)
        // their field army camps under the tower: a line at the edge of the umbrella just bleeds (they throw from
        // inside it, we cannot answer without walking into the tower), the corps and the chieftain would die at
        // the stand. Hold well outside (30 cells: beyond a throw from anywhere inside the umbrella) so they have to
        // come out to fight, and give up after a while: a camping army is beaten at our towers, not at theirs.
        boolean camping = t_val >= params.siege_camp_ratio * Math.max(1f, ours);
        if (camping) {
            if (now - siege_prep_since > params.siege_camp_patience) {
                log.info(() -> String.format("siege abandoned: their army (%.0f) camps under the towers vs ours %.0f",
                        t_val, ours));
                siege_tower = null;
                stand_x = -1;
                last_retreat_at = now;
                setArmyState(now, ArmyState.STAGING);
                posture = Posture.BUILD_UP;
                posture_since = now;
                recallArmy(now, homeCell(roster));
                return;
            }
            int[] far0 = plan.toward(siege_tower.getGridX(), siege_tower.getGridY(), c[0], c[1], 30);
            int[] far = stickyHold(safePoint(intel, far0[0], far0[1]));
            holdOutsideReach(now, intel, far);
            if (chief != null && orders.usable(chief) && BasePlan.dist2(chief.getGridX(), chief.getGridY(), far[0],
                    far[1]) > 64)
                chief_brain.moveTo(now, chief, far[0], far[1], false);
            return;
        }
        if (corpsDemolish(now, siege_tower, c))
            return;
        if (chief == null || !orders.usable(chief) || now - siege_prep_since > 150f) {
            // no chieftain / not worth waiting: assault if strong enough, else fall back
            if (ours >= 2f * Math.max(1f, t_val))
                setArmyState(now, ArmyState.ASSAULT);
            else
                setArmyState(now, ArmyState.RETREAT);
            siege_tower = null;
            stand_x = -1;
            return;
        }
        if (!Chieftain.stunReady(chief)) {
            // wait outside tower reach for the energy
            int[] wait0 = plan.toward(siege_tower.getGridX(), siege_tower.getGridY(), c[0], c[1], 24);
            int[] wait = stickyHold(safePoint(intel, wait0[0], wait0[1]));
            holdOutsideReach(now, intel, wait);
            if (BasePlan.dist2(chief.getGridX(), chief.getGridY(), wait[0], wait[1]) > 100)
                chief_brain.moveTo(now, chief, wait[0], wait[1], false);
            return;
        }
        if (stand_x < 0) {
            int[] stand = findStand(intel, siege_tower, c);
            if (stand == null) {
                if (ours >= 2f * Math.max(1f, t_val))
                    setArmyState(now, ArmyState.ASSAULT);
                else
                    setArmyState(now, ArmyState.RETREAT);
                siege_tower = null;
                return;
            }
            stand_x = stand[0];
            stand_y = stand[1];
            stand_since = now;
            stand_pre_reached = false;
            stand_best_d2 = Integer.MAX_VALUE;
            int[] ap0 = plan.toward(siege_tower.getGridX(), siege_tower.getGridY(), stand_x, stand_y, 21);
            int[] ap = safePoint(intel, ap0[0], ap0[1]);
            army_pos_x = ap[0];
            army_pos_y = ap[1];
            log.info(
                    () -> "siege: stand " + stand_x + "," + stand_y + " vs tower " + siege_tower.getGridX() + "," + siege_tower.getGridY());
        }
        // move the chieftain to the stand and the army to its position (outside reach). The stun disc is centred
        // 1.3 cells ahead of the chieftain in its facing direction, so it walks to a point behind the stand first
        // and covers the last cells toward the tower: it then faces the tower and the disc reaches it.
        int[] pre = plan.toward(stand_x, stand_y, siege_tower.getGridX(), siege_tower.getGridY(), -2.5f);
        if (!stand_pre_reached && BasePlan.dist2(chief.getGridX(), chief.getGridY(), pre[0], pre[1]) <= 2)
            stand_pre_reached = true;
        if (stand_pre_reached)
            chief_brain.moveTo(now, chief, stand_x, stand_y, true);
        else
            chief_brain.moveTo(now, chief, pre[0], pre[1], false);
        holdOutsideReach(now, intel, new int[]{army_pos_x, army_pos_y});
        int d2 = BasePlan.dist2(chief.getGridX(), chief.getGridY(), stand_x, stand_y);
        if (d2 < stand_best_d2) {
            stand_best_d2 = d2;
            stand_since = now;
        }
        boolean chief_there = d2 <= 2;
        if (!chief_there && now - stand_since > 15f) {
            // no progress toward the stand (unreachable?): blacklist it and try another
            stand_blacklist.add(new int[]{stand_x, stand_y});
            stand_fails++;
            stand_x = -1;
            log.info("siege: stand unreachable, retrying (" + stand_fails + ")");
            if (stand_fails >= 3) {
                if (ours >= 1.8f * Math.max(1f, t_val))
                    setArmyState(now, ArmyState.ASSAULT);
                else
                    setArmyState(now, ArmyState.RETREAT);
                siege_tower = null;
                stand_fails = 0;
                stand_blacklist.clear();
            }
            return;
        }
        int near = 0;
        for (Unit u : army)
            if (BasePlan.dist2(u.getGridX(), u.getGridY(), army_pos_x, army_pos_y) <= 100)
                near++;
        if (chief_there && (near >= 0.7f * army.size() || now - siege_prep_since > 60f)) {
            chief_brain.siege_cast_requested = true;
            siege_cast_at = now;
            stand_blacklist.clear();
            stand_fails = 0;
            setArmyState(now, ArmyState.SIEGE_CYCLE);
        }
    }

    /**
     * Push a hold point out of every manned tower's reach (a point 21 cells from the siege tower can still sit
     * inside a neighbouring tower's 16 cells, which is where a siege line bleeds).
     */
    private int @NonNull [] safePoint(@NonNull Intel intel, int gx, int gy) {
        int x = gx;
        int y = gy;
        for (int i = 0; i < 6; i++) {
            LandBuilding offending = null;
            int best_d2 = Integer.MAX_VALUE;
            for (LandBuilding t : intel.manned_towers) {
                int d2 = BasePlan.dist2(t.getGridX(), t.getGridY(), x, y);
                if (d2 <= 18 * 18 && d2 < best_d2) {
                    best_d2 = d2;
                    offending = t;
                }
            }
            if (offending == null)
                break;
            int[] p = plan.toward(x, y, offending.getGridX(), offending.getGridY(), -(19f - (float) Math.sqrt(
                    best_d2)));
            if (!plan.sameIsland(p[0], p[1]) || grid.getRegion(p[0], p[1]) == null)
                break;
            x = p[0];
            y = p[1];
        }
        return new int[]{x, y};
    }

    /**
     * Hold the army at a point outside tower reach: attack-move there (a plain move would let the enemy's field
     * army hit units that do not fight back), and spread fire on whatever comes into reach without advancing.
     */
    private void holdOutsideReach(float now, @NonNull Intel intel, int @NonNull [] p) {
        for (Unit u : army) {
            if (!orders.usable(u) || Chieftain.isStunned(u) || Chieftain.isFrozen(u))
                continue;
            Jobs.Job j = jobs.get(u);
            // the engine's auto-hunt drags units after any enemy within 8 cells, straight into the tower's
            // reach: a unit inside a manned tower's reach walks back to the line with a plain move
            boolean covered = false;
            for (LandBuilding t : intel.manned_towers) {
                if (!Chieftain.towerStunned(t) && BasePlan.dist2(t.getGridX(), t.getGridY(), u.getGridX(),
                        u.getGridY()) <= 17 * 17) {
                    covered = true;
                    break;
                }
            }
            if (covered) {
                if (j == null || j.kind != Jobs.Kind.MOVE || now - j.issued_at > 3f) {
                    orders.move(u, p[0], p[1]);
                    jobs.set(u, Jobs.Kind.MOVE, p[0], p[1], null, null, now, TAG_ARMY);
                }
                continue;
            }
            if (u.getCurrentBehaviour() instanceof AttackBehaviour
                    || u.getCurrentController() instanceof HuntController)
                continue;
            boolean there = BasePlan.dist2(u.getGridX(), u.getGridY(), p[0], p[1]) <= 64;
            boolean same = j != null && j.kind == Jobs.Kind.HOLD && BasePlan.dist2(j.gx, j.gy, p[0], p[1]) <= 36;
            boolean idle_far = Roster.activity(u) == Roster.Activity.IDLE && !there && (j == null
                    || now - j.issued_at > 3f);
            if (!same || idle_far) {
                orders.attackMove(u, p[0], p[1]);
                jobs.set(u, Jobs.Kind.HOLD, p[0], p[1], null, null, now, TAG_ARMY);
            }
        }
        micro.fight(now, army, intel, p[0], p[1], false, false, null, TAG_ARMY);
    }

    /**
     * Keep the previous hold point unless the new one moved more than 6 cells (centroid jitter otherwise re-orders
     * everyone).
     */
    private int @NonNull [] stickyHold(int @NonNull [] p) {
        if (hold_x >= 0 && BasePlan.dist2(p[0], p[1], hold_x, hold_y) <= 36)
            return new int[]{hold_x, hold_y};
        hold_x = p[0];
        hold_y = p[1];
        return p;
    }

    /**
     * Stand for the chieftain: 15.8-16.6 cells from the tower (outside its 15.9-cell reach, inside the 18-cell disc
     * once it faces the tower), outside every other manned tower's reach.
     */
    private int @Nullable [] findStand(@NonNull Intel intel, @NonNull LandBuilding tower, int @NonNull [] c) {
        int tx = tower.getGridX();
        int ty = tower.getGridY();
        int[] best = null;
        float best_score = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 36; i++) {
            double a = Math.toRadians(i * 10);
            int gx = (int) Math.round(tx + 16.25 * StrictMath.cos(a));
            int gy = (int) Math.round(ty + 16.25 * StrictMath.sin(a));
            if (!plan.sameIsland(gx, gy) || grid.getRegion(gx, gy) == null)
                continue;
            boolean listed = false;
            for (int[] b : stand_blacklist)
                if (BasePlan.dist2(b[0], b[1], gx, gy) <= 16) {
                    listed = true;
                    break;
                }
            if (listed)
                continue;
            // the stand must be in the army's connected region set: same region as the army centroid is the cheap test
            if (grid.getRegion(gx, gy) != grid.getRegion(c[0], c[1]) && grid.getRegion(c[0], c[1]) != null
                    && !regionsAdjacent(gx, gy, c))
                continue;
            int d2 = BasePlan.dist2(gx, gy, tx, ty);
            if (d2 < params.stand_min2 || d2 > params.stand_max2)
                continue;
            boolean safe = true;
            for (LandBuilding other : intel.manned_towers) {
                if (other == tower)
                    continue;
                if (BasePlan.dist2(gx, gy, other.getGridX(), other.getGridY()) < 256) {
                    safe = false;
                    break;
                }
            }
            if (!safe)
                continue;
            float score = -0.02f * BasePlan.dist2(gx, gy, c[0], c[1]) - enemyValueNear(intel, gx, gy,
                    12) + 5f * countTowersWithin(intel, gx, gy, 17);
            if (score > best_score) {
                best_score = score;
                best = new int[]{gx, gy};
            }
        }
        return best;
    }

    /** Cheap reachability estimate: a region path of at most a few hops exists between the two cells. */
    private boolean regionsAdjacent(int gx, int gy, int @NonNull [] c) {
        var from = grid.getRegion(c[0], c[1]);
        var to = grid.getRegion(gx, gy);
        if (from == null || to == null)
            return false;
        return com.oddlabs.tt.pathfinder.PathFinder.findPathRegion(grid, from, to) != null;
    }

    private int countTowersWithin(@NonNull Intel intel, int gx, int gy, int r) {
        int n = 0;
        for (LandBuilding t : intel.manned_towers)
            if (BasePlan.dist2(t.getGridX(), t.getGridY(), gx, gy) <= r * r)
                n++;
        return n;
    }

    private void siegeCycle(float now, @NonNull Roster roster, @NonNull Intel intel, int @NonNull [] c) {
        if (siege_tower == null || siege_tower.isDead()) {
            log.info("siege: tower down");
            siege_tower = null;
            stand_x = -1;
            setArmyState(now, ArmyState.MARCH);
            nextLeg(now, c);
            return;
        }
        float since = now - siege_cast_at;
        if (since < 1.5f) {
            // the cast is in progress (6 s animation, lands at +3.8 s): start moving now so the army arrives as it lands
            return;
        }
        if (since < params.stun_window + 4f) {
            if (Chieftain.towerStunned(siege_tower) || since < 5f) {
                corpsDemolish(now, siege_tower, c);
                if (now - last_engage_order > 3f) {
                    List<Unit> to = new ArrayList<>();
                    for (Unit u : army)
                        if (orders.usable(u) && !Chieftain.isStunned(u))
                            to.add(u);
                    orders.attack(to, siege_tower);
                    for (Unit u : to)
                        jobs.set(u, Jobs.Kind.HUNT, siege_tower.getGridX(), siege_tower.getGridY(), siege_tower, null,
                                now, TAG_ARMY);
                    last_engage_order = now;
                }
            } else if (since > 6f) {
                // the stun did not land on the tower: pull back out of reach
                log.info("siege: stun missed the tower, pulling back");
                int[] back0 = plan.toward(siege_tower.getGridX(), siege_tower.getGridY(), army_pos_x, army_pos_y, 21);
                int[] back = safePoint(intel, back0[0], back0[1]);
                orders.move(new ArrayList<>(army), back[0], back[1]);
                for (Unit u : army)
                    jobs.set(u, Jobs.Kind.MOVE, back[0], back[1], null, null, now, TAG_ARMY);
                stand_x = -1;
                siege_prep_since = now;
                setArmyState(now, ArmyState.SIEGE_PREP);
            }
            return;
        }
        // window over: tower still alive -> pull back and wait for the next cast
        int[] back0 = plan.toward(siege_tower.getGridX(), siege_tower.getGridY(), army_pos_x, army_pos_y, 21);
        int[] back = safePoint(intel, back0[0], back0[1]);
        orders.move(new ArrayList<>(army), back[0], back[1]);
        for (Unit u : army)
            jobs.set(u, Jobs.Kind.MOVE, back[0], back[1], null, null, now, TAG_ARMY);
        stand_x = -1;
        siege_prep_since = now;
        setArmyState(now, ArmyState.SIEGE_PREP);
    }

    private void retreat(float now, @NonNull Roster roster, @NonNull Intel intel, int @NonNull [] c,
            int @NonNull [] home) {
        // our stun just landed on them: that is the moment to fight, not to run
        if (now - chief_brain.last_stun_at < 3f && chief_brain.last_stun_cell != null && now - army_state_since < 4f
                && BasePlan.dist2(chief_brain.last_stun_cell[0], chief_brain.last_stun_cell[1], c[0], c[1]) <= 25 * 25
                && groupValue(army) >= 0.5f * last_fight_theirs) {
            log.info("retreat cancelled: our stun landed");
            hold_since = -1f;
            setArmyState(now, ArmyState.ENGAGE);
            return;
        }
        if (now - army_state_since < 0.1f || ((leg_x != prev_leg_x || leg_y != prev_leg_y)
                && now - army_state_since < 1f)) {
            int[] back = prev_leg_x >= 0 ? new int[]{prev_leg_x, prev_leg_y} : new int[]{staging_x, staging_y};
            List<Unit> all = new ArrayList<>(army);
            orders.move(all, back[0], back[1]);
            for (Unit u : all)
                jobs.set(u, Jobs.Kind.MOVE, back[0], back[1], null, null, now, TAG_ARMY);
            log.info(() -> "army retreats to " + back[0] + "," + back[1]);
            leg_x = back[0];
            leg_y = back[1];
        }
        // back at the waypoint: merge reinforcements and re-evaluate
        int arrived = 0;
        for (Unit u : army)
            if (BasePlan.dist2(u.getGridX(), u.getGridY(), leg_x, leg_y) <= 100)
                arrived++;
        float theirs = enemyValueNear(intel, c[0], c[1], 25);
        if (arrived >= 0.7f * army.size() || now - army_state_since > 30f) {
            army.addAll(reinforcements);
            reinforcements.clear();
            float ours = groupValue(army);
            // the force we ran from is still there: going back with less than a real edge repeats the loss
            float ref = Math.max(theirs, 0.8f * last_fight_theirs);
            last_retreat_at = now;
            if (ref > 0f && ours / ref < params.engage_ratio) {
                // still weaker: all the way home
                setArmyState(now, ArmyState.STAGING);
                posture = Posture.BUILD_UP;
                posture_since = now;
                recallArmy(now, home);
                final float fo = ours, fr = ref;
                log.info(() -> String.format("army retreat -> home (BUILD_UP) ours=%.1f vs %.1f", fo, fr));
            } else {
                setArmyState(now, ArmyState.STAGING);
                if (BasePlan.dist(c[0], c[1], staging_x, staging_y) > 60f) {
                    // regroup where we are, then resume (updateStaging keeps this point while we stage)
                    regroup_x = leg_x;
                    regroup_y = leg_y;
                    staging_x = leg_x;
                    staging_y = leg_y;
                }
            }
        }
    }

    // ------------------------------------------------------------------ reinforcements

    private void reinforcementsStep(float now, @NonNull Roster roster, int @NonNull [] home) {
        if (reinforcements.isEmpty())
            return;
        if (posture != Posture.ATTACK && posture != Posture.ALL_IN) {
            army.addAll(reinforcements);
            reinforcements.clear();
            return;
        }
        if (threat_serious)
            return; // defend() owns the guard and the staged reinforcements this tick: do not send them away
        int[] ac = centroid(army);
        if (ac == null) {
            army.addAll(reinforcements);
            reinforcements.clear();
            return;
        }
        // hold at staging until the batch is big enough, then attack-move to the army
        float d_army = BasePlan.dist(ac[0], ac[1], home[0], home[1]);
        int batch = d_army < 60f ? params.reinforce_batch_near : params.reinforce_batch;
        if (posture == Posture.ALL_IN)
            batch = Math.min(batch, 4);
        List<Unit> merged = new ArrayList<>();
        for (Unit u : reinforcements) {
            if (BasePlan.dist2(u.getGridX(), u.getGridY(), ac[0], ac[1]) <= 20 * 20)
                merged.add(u);
        }
        for (Unit u : merged) {
            reinforcements.remove(u);
            army.add(u);
        }
        if (reinforcements.size() >= batch) {
            // issue the batch once; units already on their way keep their order (re-aimed below every 8 s)
            List<Unit> batch_units = new ArrayList<>();
            for (Unit u : reinforcements) {
                Jobs.Job j = jobs.get(u);
                if (j == null || j.kind != Jobs.Kind.ATTACK_MOVE || j.tag != TAG_REINF)
                    batch_units.add(u);
            }
            if (!batch_units.isEmpty()) {
                orders.attackMove(batch_units, ac[0], ac[1]);
                for (Unit u : batch_units)
                    jobs.set(u, Jobs.Kind.ATTACK_MOVE, ac[0], ac[1], null, null, now, TAG_REINF);
            }
        } else {
            for (Unit u : reinforcements) {
                Jobs.Job j = jobs.get(u);
                // units already sent after the army keep their attack-move (the re-aim loop below steers them)
                boolean en_route = j != null && j.kind == Jobs.Kind.ATTACK_MOVE && j.tag == TAG_REINF;
                if (j == null || (j.kind != Jobs.Kind.HOLD && !en_route)) {
                    orders.attackMove(u, staging_x, staging_y);
                    jobs.set(u, Jobs.Kind.HOLD, staging_x, staging_y, null, null, now, TAG_REINF);
                }
            }
        }
        // a batch already sent keeps its attack-move; re-aim every 8 s toward the army's new position
        for (Unit u : reinforcements) {
            Jobs.Job j = jobs.get(u);
            if (j != null && j.kind == Jobs.Kind.ATTACK_MOVE && now - j.issued_at > 8f
                    && BasePlan.dist2(j.gx, j.gy, ac[0], ac[1]) > 100) {
                orders.attackMove(u, ac[0], ac[1]);
                jobs.set(u, Jobs.Kind.ATTACK_MOVE, ac[0], ac[1], null, null, now, TAG_REINF);
            }
        }
    }

    // ------------------------------------------------------------------ micro

    /**
     * Stun recovery (fast lane): a stunned unit's controller stack is replaced by any new order, which ends the
     * stun. Every own stunned warrior is re-issued its current job (or attack-moves in place) at once.
     */
    private final java.util.Map<Unit, Float> recovery_orders = new java.util.LinkedHashMap<>();

    public void recoverStunned(float now, @NonNull Roster roster) {
        if (!params.stun_recovery)
            return;
        recovery_orders.entrySet().removeIf(e -> e.getKey().isDead() || now - e.getValue() > 60f);
        for (Unit u : roster.warriors) {
            if (!orders.usable(u) || !Chieftain.isStunned(u))
                continue;
            // the new order replaces the stun controller (defense comes back) but the stun behaviour still freezes
            // the unit until it ends, so one order per stun is enough
            Float last = recovery_orders.get(u);
            if (last != null && now - last < 20f)
                continue;
            recovery_orders.put(u, now);
            Jobs.Job j = jobs.get(u);
            if (j != null && (j.kind == Jobs.Kind.HUNT) && j.target != null && !j.target.isDead()) {
                orders.attack(u, j.target);
            } else if (j != null && (j.kind == Jobs.Kind.MOVE)) {
                orders.move(u, j.gx, j.gy);
            } else if (j != null && j.gx >= 0 && (j.kind == Jobs.Kind.ATTACK_MOVE || j.kind == Jobs.Kind.DEFEND
                    || j.kind == Jobs.Kind.HOLD)) {
                        orders.attackMove(u, j.gx, j.gy);
                    } else {
                        orders.attackMove(u, u.getGridX(), u.getGridY());
                    }
        }
        Unit chief = roster.chieftain;
        if (chief != null && orders.usable(chief) && Chieftain.isStunned(chief)) {
            Float last = recovery_orders.get(chief);
            if (last == null || now - last >= 20f) {
                recovery_orders.put(chief, now);
                orders.move(chief, chief.getGridX(), chief.getGridY());
            }
        }
        for (Unit p : roster.peons) {
            if (!orders.usable(p) || !Chieftain.isStunned(p))
                continue;
            Float last = recovery_orders.get(p);
            if (last != null && now - last < 20f)
                continue;
            recovery_orders.put(p, now);
            Jobs.Job j = jobs.get(p);
            if (j != null && j.kind == Jobs.Kind.BUILD && j.target instanceof LandBuilding b && !b.isDead())
                orders.build(p, b);
            else
                orders.move(p, p.getGridX(), p.getGridY());
        }
    }

    /** After our stun lands: 2 warriors per stunned enemy, chieftain first, nearest first. */
    private void focusStunned(float now, @NonNull Roster roster, @NonNull Intel intel) {
        float since = now - chief_brain.last_stun_at;
        if (since < 4.0f || since > params.stun_window + 2f || chief_brain.last_stun_cell == null)
            return;
        if (now - last_focus_at < 2f)
            return;
        int cx = chief_brain.last_stun_cell[0];
        int cy = chief_brain.last_stun_cell[1];
        Chieftain.stunnedEnemies(intel, cx, cy, 20, scratch_targets);
        if (scratch_targets.isEmpty())
            return;
        List<Unit> shooters = new ArrayList<>();
        for (Unit u : roster.warriors)
            if (orders.usable(u) && !Chieftain.isStunned(u) && !(u.getCurrentBehaviour() instanceof AttackBehaviour)
                    && BasePlan.dist2(u.getGridX(), u.getGridY(), cx, cy) <= 22 * 22)
                shooters.add(u);
        if (shooters.isEmpty())
            return;
        int idx = 0;
        for (Selectable<?> t : scratch_targets) {
            int per = t instanceof Unit tu && tu.getAbilities().hasAbilities(
                    com.oddlabs.tt.model.Abilities.MAGIC) ? 8 : t instanceof LandBuilding ? 6 : 2;
            List<Unit> group = new ArrayList<>();
            for (int i = 0; i < per && idx < shooters.size(); i++, idx++)
                group.add(shooters.get(idx));
            if (group.isEmpty())
                break;
            orders.attack(group, t);
            for (Unit u : group)
                jobs.set(u, Jobs.Kind.HUNT, t.getGridX(), t.getGridY(), t, null, now, TAG_FOCUS);
        }
        last_focus_at = now;
    }

    /** Warriors chasing bait far from their group get a non-aggressive move back. */
    private void leash(float now, @NonNull Roster roster, @NonNull Intel intel) {
        if (now - last_leash < 2f)
            return;
        last_leash = now;
        int[] ac = centroid(army);
        int[] hc = centroid(home_guard);
        for (Unit u : roster.warriors) {
            if (!orders.usable(u) || !(u.getPrimaryController() instanceof HuntController)
                    || u.getCurrentBehaviour() instanceof AttackBehaviour)
                continue;
            Jobs.Job j = jobs.get(u);
            if (j != null && (j.kind == Jobs.Kind.HUNT) && now - j.issued_at < params.stun_window + 4f)
                continue; // deliberate focus fire
            int[] c = army.contains(u) ? ac : home_guard.contains(u) ? hc : null;
            if (c == null)
                continue;
            int d2 = BasePlan.dist2(u.getGridX(), u.getGridY(), c[0], c[1]);
            if (d2 > params.leash_dist * params.leash_dist) {
                orders.move(u, c[0], c[1]);
                jobs.set(u, Jobs.Kind.MOVE, c[0], c[1], null, null, now, TAG_ARMY);
            }
        }
    }

    // ------------------------------------------------------------------ chieftain positioning

    private void positionChieftain(float now, @NonNull Roster roster, @NonNull Intel intel, @NonNull Threat threat,
            int @NonNull [] home) {
        Unit chief = roster.chieftain;
        if (chief == null || !orders.usable(chief) || Chieftain.isCasting(chief) || Chieftain.isStunned(chief))
            return;
        if (chief_brain.shouldEscape(chief, intel)) {
            int[] c = centroid(army);
            int[] to = c != null && army.size() >= 6 && BasePlan.dist2(chief.getGridX(), chief.getGridY(), c[0],
                    c[1]) < BasePlan.dist2(
                            chief.getGridX(), chief.getGridY(), home[0], home[1]) ? c : plan.along(home[0], home[1],
                                    -6);
            chief_brain.moveTo(now, chief, to[0], to[1], true);
            return;
        }
        if (army_state == ArmyState.SIEGE_PREP || army_state == ArmyState.SIEGE_CYCLE)
            return; // siegePrep positions it
        boolean with_army = (posture == Posture.ATTACK || posture == Posture.ALL_IN) && army_state != ArmyState.STAGING
                && !army.isEmpty();
        if (with_army) {
            int[] c = centroid(army);
            if (c == null)
                return;
            boolean fighting = (army_state == ArmyState.ENGAGE || army_state == ArmyState.ASSAULT) && fight_x >= 0
                    && now - fight_at < 3f;
            int screen = fighting ? Chieftain.ownWarriorsNear(roster.warriors, fight_x, fight_y, 20) : 0;
            if (fighting && screen >= 6 && Chieftain.stunReady(chief)) {
                // armed and screened: walk up to casting distance of the enemy clump (inside 15 cells of it)
                int[] p = plan.toward(fight_x, fight_y, c[0], c[1], params.stun_cast_dist - 7);
                int d2 = BasePlan.dist2(chief.getGridX(), chief.getGridY(), p[0], p[1]);
                if (d2 > 9)
                    chief_brain.moveTo(now, chief, p[0], p[1], true);
                return;
            }
            // on the march: with the clump (a chieftain that trails the legs arrives after the fight is decided);
            // in a fight without energy: a few cells behind the centroid
            int follow = army_state == ArmyState.MARCH ? 1 : params.chief_follow_dist;
            int[] behind = objective_x >= 0 ? plan.toward(c[0], c[1], objective_x, objective_y, -follow) : c;
            int d2 = BasePlan.dist2(chief.getGridX(), chief.getGridY(), behind[0], behind[1]);
            if (d2 > 144 || (d2 < 9 && army_state == ArmyState.ENGAGE))
                chief_brain.moveTo(now, chief, behind[0], behind[1], false);
        } else if (posture == Posture.DEFEND && threat.approach_centroid != null) {
            // between the armory and the ring, sideways of the approach line
            int[] p = plan.toward(home[0], home[1], threat.approach_centroid[0], threat.approach_centroid[1],
                    params.tower_ring_radius - 2);
            chief_brain.moveTo(now, chief, p[0], p[1], false);
        } else {
            // at home: just inside the staging point, so a toot covers the ring
            int[] p = plan.along(home[0], home[1], 9);
            if (posture == Posture.ATTACK && army_state == ArmyState.STAGING)
                p = new int[]{staging_x, staging_y};
            chief_brain.moveTo(now, chief, p[0], p[1], false);
        }
    }

    // ------------------------------------------------------------------ status

    public int armySize() {
        return army.size();
    }

    public int guardSize() {
        return home_guard.size();
    }

    public int reinforcementSize() {
        return reinforcements.size();
    }

    public boolean fightingNear(int gx, int gy, int radius) {
        if (army_state != ArmyState.ENGAGE && army_state != ArmyState.ASSAULT && army_state != ArmyState.SIEGE_CYCLE)
            return false;
        int[] c = centroid(army);
        return c != null && BasePlan.dist2(c[0], c[1], gx, gy) <= radius * radius;
    }

    public @NonNull String status() {
        return posture + "/" + army_state + " army=" + army.size() + " guard=" + home_guard.size() + " reinf=" + reinforcements.size() + (objective != null ? " obj=" + objective_x + "," + objective_y : "");
    }
}
