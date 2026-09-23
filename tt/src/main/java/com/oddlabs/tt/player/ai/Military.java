package com.oddlabs.tt.player.ai;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.ai.Intel.PeonState;
import com.oddlabs.tt.player.ai.Intel.WarriorState;
import com.oddlabs.tt.player.ai.Intel.WarriorType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Warriors: manning towers, holding a clumped army at a staging point in front of the armory, answering attacks on
 * the base, raiding enemy gatherers, and marching on the enemy together when the numbers favor it. Orders are always
 * given as attacks on the ground so warriors fight whatever they meet instead of walking past it.
 */
final class Military {
    enum Mode {
        HOME,
        MUSTER,
        ATTACK,
        RETREAT
    }

    private enum Role {
        ARMY,
        TOWER,
        ATTACK,
        RAID
    }

    private static final int ENGAGE_RADIUS = 22;
    private static final float REORDER_PERIOD = 2.5f;

    private final @NonNull ExpertAI ai;
    private final Map<@NonNull Unit, @NonNull Role> roles = new LinkedHashMap<>();
    private final Map<@NonNull Unit, @NonNull Building> tower_assignments = new LinkedHashMap<>();
    private final Map<@NonNull Unit, Float> last_order = new LinkedHashMap<>();
    private final Map<@NonNull Unit, Float> chief_orders = new LinkedHashMap<>();
    private final Map<@NonNull Unit, int @NonNull []> last_spots = new LinkedHashMap<>();
    private final Map<Long, List<@NonNull Unit>> pending_orders = new LinkedHashMap<>();
    private final java.util.ArrayDeque<float @NonNull []> enemy_history = new java.util.ArrayDeque<>();

    private @NonNull Mode mode = Mode.HOME;
    private int staging_x;
    private int staging_y;
    private float staging_time = -100f;

    // Threat to the base, recomputed every tick.
    private final List<@NonNull Unit> threats = new ArrayList<>();
    private float threat_strength;
    private float base_threat_strength;
    private int threat_x;
    private int threat_y;
    private int threat_level;
    private float last_threat_time = -100f;
    private int last_logged_threat;
    private boolean last_logged_engage;
    /** 1 while defenders are engaged with the current threat, -1 while they have fallen back, 0 for a new threat. */
    private int engage_state;

    // Attack.
    private @Nullable Selectable<?> target;
    private int target_x;
    private int target_y;
    private @Nullable DistanceField target_field;
    private float attack_start;
    private float attack_initial_strength;
    private float muster_start;
    private float next_wave_time;
    private float last_trace;
    private float hold_until = -1f;
    private float last_progress_time;
    private int best_target_dist = Integer.MAX_VALUE;
    private int @NonNull [] hold_spot = new int[2];
    private int last_enemy_d2 = Integer.MAX_VALUE;
    private float last_charge_log = -100f;
    /** The attack is a short strike on an enemy building in or next to our base, and ends when it falls. */
    private boolean strike;

    // Escort of forward tower builders.
    private int escort_x;
    private int escort_y;
    private float escort_time = -100f;

    // Raid.
    private int raid_x;
    private int raid_y;
    private float raid_start = -1000f;
    private float last_raid_end = -1000f;

    Military(@NonNull ExpertAI ai) {
        this.ai = ai;
        staging_x = ai.planner().getStartX();
        staging_y = ai.planner().getStartY();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Queries used by the economy

    int baseThreatLevel() {
        return threat_level;
    }

    boolean threatNear(int x, int y, int radius) {
        int r2 = radius * radius;
        for (Unit u : threats)
            if (!u.isDead() && MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()) <= r2)
                return true;
        return false;
    }

    float enemyStrengthNear(int x, int y, int radius) {
        return Combat.strengthNear(ai.intel().enemy_warriors, x, y, radius) + Combat.strengthNear(
                ai.intel().enemy_chieftains, x, y, radius);
    }

    /** Enemy fighting strength around a spot including peons, which join fights near their own base. */
    private float enemyFightersNear(int x, int y, int radius) {
        return enemyStrengthNear(x, y, radius) + Combat.strengthNear(ai.intel().enemy_peons, x, y, radius);
    }

    /** True while every weapon should come out of the armory at once: the base is under attack or an attack musters. */
    boolean wantsEverything() {
        return threat_level >= 2 || mode == Mode.MUSTER;
    }

    int towerSeatsFree() {
        int n = 0;
        for (Building t : ai.intel().towers)
            if (!Intel.isTowerManned(t) && !tower_assignments.containsValue(t))
                n++;
        return n;
    }

    float armyStrength() {
        float s = 0f;
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.ARMY || e.getValue() == Role.RAID)
                s += Combat.value(e.getKey());
        return s;
    }

    /** Strength of warriors wanted outside the armory while the base is quiet. */
    float armyStrengthWanted() {
        float enemy = enemyFieldStrength();
        float wanted = Math.max(5f + ai.time() / 90f, .75f * enemy);
        return Math.min(wanted, 45f);
    }

    private float enemyFieldStrength() {
        float s = 0f;
        for (Unit u : ai.intel().enemy_warriors)
            s += Combat.value(u);
        for (Unit u : ai.intel().enemy_chieftains)
            s += Combat.value(u);
        return s;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Tick

    void tick() {
        updateRoles();
        updateStaging();
        updateThreat();
        manTowers();
        if (threat_level > 0)
            defend();
        switch (mode) {
            case HOME -> holdStaging();
            case MUSTER -> muster();
            case ATTACK -> attack();
            case RETREAT -> retreat();
        }
        raid();
        flushOrders();
    }

    void plan() {
        updateRoles();
        recordEnemyStrength();
        if (mode == Mode.HOME && threat_level < 2 && ai.strategy().strikes)
            considerStrike();
        if (mode == Mode.HOME && threat_level < 2)
            considerAttack();
        if (mode == Mode.HOME && threat_level == 0)
            considerRaid();
    }

    private void updateRoles() {
        Intel intel = ai.intel();
        for (Iterator<Map.Entry<Unit, Role>> it = roles.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Unit, Role> e = it.next();
            Unit u = e.getKey();
            if (u.isDead() || u.isMounted()) {
                it.remove();
                tower_assignments.remove(u);
                last_order.remove(u);
                last_spots.remove(u);
                chief_orders.remove(u);
            }
        }
        for (Iterator<Map.Entry<Unit, Building>> it = tower_assignments.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Unit, Building> e = it.next();
            Unit u = e.getKey();
            Building t = e.getValue();
            if (u.isDead() || u.isMounted() || t.isDead() || Intel.isTowerManned(t)
                    || intel.warrior_states.get(u) != WarriorState.ENTER) {
                if (!u.isDead() && !u.isMounted() && roles.get(u) == Role.TOWER)
                    roles.put(u, Role.ARMY);
                it.remove();
            }
        }
        for (Unit w : intel.warriors) {
            if (!roles.containsKey(w))
                roles.put(w, Role.ARMY);
        }
    }

    private void updateStaging() {
        Building armory = ai.intel().armory();
        if (armory == null) {
            if (!ai.intel().quarters.isEmpty()) {
                Building q = ai.intel().quarters.getFirst();
                staging_x = q.getGridX();
                staging_y = q.getGridY();
            }
            return;
        }
        if (ai.time() - staging_time < 30f)
            return;
        staging_time = ai.time();
        int[] p = ai.planner().getEnemyField().stepTowardsSource(armory.getGridX(), armory.getGridY(), 26);
        staging_x = p[0];
        staging_y = p[1];
    }

    // ------------------------------------------------------------------------------------------------------------
    // Threat detection and defense

    private void updateThreat() {
        Intel intel = ai.intel();
        threats.clear();
        int radius = ai.strategy().base_radius;
        int r2 = radius * radius;
        List<Building> own = new ArrayList<>();
        own.addAll(intel.armories);
        own.addAll(intel.quarters);
        own.addAll(intel.towers);
        own.addAll(intel.armory_sites);
        own.addAll(intel.quarters_sites);
        own.addAll(intel.tower_sites);
        List<Unit> enemies = new ArrayList<>(intel.enemy_warriors);
        enemies.addAll(intel.enemy_chieftains);
        List<Unit> at_base = new ArrayList<>();
        for (Unit e : enemies) {
            boolean near_base = false;
            for (Building b : own) {
                if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), b.getGridX(), b.getGridY()) <= r2) {
                    near_base = true;
                    break;
                }
            }
            boolean near_peons = false;
            if (!near_base) {
                for (Unit p : intel.peons) {
                    PeonState s = intel.peon_states.get(p);
                    if (s == PeonState.GATHER_CHICKEN)
                        continue;
                    if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), p.getGridX(), p.getGridY()) <= 12 * 12) {
                        near_peons = true;
                        break;
                    }
                }
            }
            if (near_base) {
                threats.add(e);
                at_base.add(e);
            } else if (near_peons) {
                threats.add(e);
            }
        }
        // Enemy peons tearing down towers count too.
        for (Unit e : intel.enemy_peons) {
            for (Building t : intel.towers) {
                if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), t.getGridX(), t.getGridY()) <= 5 * 5) {
                    threats.add(e);
                    at_base.add(e);
                    break;
                }
            }
        }
        if (threats.isEmpty()) {
            if (threat_level != 0)
                ai.log("threat over");
            threat_level = 0;
            engage_state = 0;
            threat_strength = 0f;
            base_threat_strength = 0f;
            last_logged_threat = 0;
            return;
        }
        last_threat_time = ai.time();
        base_threat_strength = 0f;
        for (Unit e : at_base)
            base_threat_strength += Combat.value(e);
        // Answer the most dangerous group: the strongest cluster, doubled when it is in the base and more so at the
        // armory.
        Building armory = intel.armory();
        float best = -1f;
        for (Unit seed : threats) {
            long sx = 0;
            long sy = 0;
            int n = 0;
            float strength = 0f;
            boolean base = false;
            for (Unit e : threats) {
                if (MapAnalysis.dist2(seed.getGridX(), seed.getGridY(), e.getGridX(), e.getGridY()) <= 15 * 15) {
                    sx += e.getGridX();
                    sy += e.getGridY();
                    n++;
                    strength += Math.max(.2f, Combat.value(e));
                    base |= at_base.contains(e);
                }
            }
            int cx = (int) (sx / n);
            int cy = (int) (sy / n);
            float weight = strength * (base ? 2f : 1f);
            if (armory != null && MapAnalysis.dist2(cx, cy, armory.getGridX(), armory.getGridY()) <= 25 * 25)
                weight *= 1.5f;
            if (weight > best) {
                best = weight;
                threat_x = cx;
                threat_y = cy;
                threat_strength = strength;
            }
        }
        boolean at_armory = armory != null && MapAnalysis.dist2(threat_x, threat_y, armory.getGridX(),
                armory.getGridY()) <= 25 * 25;
        threat_level = base_threat_strength >= 3f || at_armory ? 2 : 1;
    }

    private void defend() {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        // A real attack on the base beats anything the army could achieve abroad; harassment of far gatherers does not.
        float home = armyStrength() + towersStrength();
        if ((mode == Mode.ATTACK || mode == Mode.MUSTER) && base_threat_strength > home
                && base_threat_strength > .35f * attackStrength()) {
            ai.log(String.format("calling the army home: %.1f in the base against %.1f", base_threat_strength, home));
            endAttack();
        }
        List<Unit> defenders = new ArrayList<>();
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            Role r = e.getValue();
            Unit u = e.getKey();
            if (r == Role.ARMY || (r == Role.RAID && threat_level >= 2))
                defenders.add(u);
        }
        float response = ai.strategy().response_ratio;
        if (threat_level < 2 && response > 0f) {
            // Harassment of gatherers away from the base: send the nearest warriors, enough to win, so that a feint
            // cannot draw the whole army out of position.
            defenders.sort((a, b) -> Integer.compare(
                    MapAnalysis.dist2(a.getGridX(), a.getGridY(), threat_x, threat_y),
                    MapAnalysis.dist2(b.getGridX(), b.getGridY(), threat_x, threat_y)));
            float needed = response * threat_strength + 2f;
            float picked = 0f;
            int n = 0;
            while (n < defenders.size() && picked < needed)
                picked += Combat.value(defenders.get(n++));
            defenders = new ArrayList<>(defenders.subList(0, n));
        }
        float ours = 0f;
        for (Unit u : defenders)
            ours += Combat.value(u);
        float towers = 0f;
        for (Building t : intel.towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), threat_x, threat_y) <= 16 * 16)
                towers += Combat.towerValue(t);
        Unit chief = intel.chieftain;
        boolean toot_ready = chief != null && ai.chieftain().stunReady();
        float effective = ours + towers + (toot_ready ? .6f * threat_strength : 0f);
        float enemy = threat_strength * (enemyStunReadyNear(threat_x, threat_y, 25) ? 1.5f : 1f);
        float engage_ratio = .8f - engage_state * ai.strategy().defend_hysteresis;
        boolean engage = effective >= engage_ratio * enemy
                || (armory != null && MapAnalysis.dist2(threat_x, threat_y, armory.getGridX(),
                        armory.getGridY()) <= 14 * 14);
        if (threat_level != last_logged_threat || engage != last_logged_engage) {
            last_logged_threat = threat_level;
            last_logged_engage = engage;
            ai.log(String.format("threat %d: %.1f at %d,%d; defenders %.1f towers %.1f -> %s", threat_level,
                    threat_strength, threat_x, threat_y, ours, towers, engage ? "engage" : "fall back"));
        }
        engage_state = engage ? 1 : -1;
        if (engage) {
            for (Unit u : defenders) {
                if (roles.get(u) == Role.RAID)
                    roles.put(u, Role.ARMY);
            }
            engageSpread(defenders, threats, threat_x, threat_y, false);
            huntChieftains(defenders);
        } else if (armory != null) {
            // Too many: fall back under the towers and wait for the armory to empty out.
            for (Unit u : defenders)
                attackGround(u, armory.getGridX(), armory.getGridY(), false);
        }
        evacuatePeons();
    }

    private float towersStrength() {
        float s = 0f;
        for (Building t : ai.intel().towers)
            s += Combat.towerValue(t);
        return s;
    }

    /** Whether an enemy chieftain with his spell ready stands within radius cells. */
    boolean enemyStunReadyNear(int x, int y, int radius) {
        for (Unit c : ai.intel().enemy_chieftains) {
            if (c.isDead() || Intel.isStunned(c))
                continue;
            if (MapAnalysis.dist2(x, y, c.getGridX(), c.getGridY()) > radius * radius)
                continue;
            if (c.getMagicProgress(0) >= 1f || c.getMagicProgress(1) >= 1f)
                return true;
        }
        return false;
    }

    /**
     * An enemy chieftain close to our warriors is worth going straight for: his stun wins fights, and he dies to a
     * handful of axes. The nearest warriors hunt him down while the rest keep attacking the ground.
     */
    private void huntChieftains(@NonNull List<@NonNull Unit> units) {
        for (Unit chief : ai.intel().enemy_chieftains) {
            if (chief.isDead())
                continue;
            int[] c = centroid(units);
            if (MapAnalysis.dist2(c[0], c[1], chief.getGridX(), chief.getGridY()) > 20 * 20)
                continue;
            int wanted = Math.min(8, Math.max(3, units.size() / 3));
            List<Unit> hunters = new ArrayList<>();
            for (int k = 0; k < wanted; k++) {
                Unit best = null;
                int best_d = Integer.MAX_VALUE;
                for (Unit u : units) {
                    if (hunters.contains(u))
                        continue;
                    WarriorState s = ai.intel().warrior_states.get(u);
                    if (s == WarriorState.STUNNED || s == WarriorState.ENTER)
                        continue;
                    int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), chief.getGridX(), chief.getGridY());
                    if (d < best_d) {
                        best_d = d;
                        best = u;
                    }
                }
                if (best == null)
                    break;
                hunters.add(best);
            }
            for (Unit u : hunters) {
                Float last = chief_orders.get(u);
                if (last != null && ai.time() - last < 4f)
                    continue;
                chief_orders.put(u, ai.time());
                last_order.put(u, ai.time());
                ai.owner().setTarget(Selectable.newArray(u), chief, Action.ATTACK, true);
            }
        }
    }

    /** Gatherers with enemy warriors close by go inside before they are cut down. */
    private void evacuatePeons() {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        List<Unit> evacuate = new ArrayList<>();
        for (Unit p : intel.peons) {
            PeonState s = intel.peon_states.get(p);
            if (s == PeonState.TRANSIT || s == PeonState.STUNNED)
                continue;
            if (!threatNear(p.getGridX(), p.getGridY(), 11))
                continue;
            evacuate.add(p);
        }
        for (Unit p : evacuate) {
            Building shelter = null;
            int best = Integer.MAX_VALUE;
            if (armory != null && !threatNear(armory.getGridX(), armory.getGridY(), 6))
                shelter = armory;
            for (Building q : intel.quarters) {
                if (threatNear(q.getGridX(), q.getGridY(), 6))
                    continue;
                int d = MapAnalysis.dist2(q.getGridX(), q.getGridY(), p.getGridX(), p.getGridY());
                int da = shelter == armory && armory != null ? MapAnalysis.dist2(armory.getGridX(),
                        armory.getGridY(), p.getGridX(), p.getGridY()) : Integer.MAX_VALUE;
                if (d < best && d < da) {
                    best = d;
                    shelter = q;
                }
            }
            if (shelter != null && shelter.getUnitContainer() != null)
                ai.owner().setTarget(Selectable.newArray(p), shelter, Action.DEFAULT, false);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Towers

    private void manTowers() {
        Intel intel = ai.intel();
        for (Building tower : intel.towers) {
            if (Intel.isTowerManned(tower) || tower_assignments.containsValue(tower))
                continue;
            Unit best = null;
            int best_score = Integer.MAX_VALUE;
            for (Map.Entry<Unit, Role> e : roles.entrySet()) {
                if (e.getValue() != Role.ARMY)
                    continue;
                Unit u = e.getKey();
                WarriorState s = intel.warrior_states.get(u);
                if (s == WarriorState.FIGHT || s == WarriorState.STUNNED || s == null)
                    continue;
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), tower.getGridX(), tower.getGridY());
                if (d > 70 * 70)
                    continue;
                int score = d;
                WarriorType type = Intel.warriorType(u);
                if (type == WarriorType.CHICKEN)
                    score -= 60 * 60;
                else if (type == WarriorType.ROCK)
                    score += 30 * 30;
                if (score < best_score) {
                    best_score = score;
                    best = u;
                }
            }
            if (best != null) {
                roles.put(best, Role.TOWER);
                tower_assignments.put(best, tower);
                ai.owner().setTarget(Selectable.newArray(best), tower, Action.DEFAULT, false);
            }
        }
        // Swap iron warriors in towers for chicken warriors when the base is quiet.
        if (threat_level > 0)
            return;
        for (Building tower : intel.towers) {
            if (!Intel.isTowerManned(tower))
                continue;
            Unit inside = ((com.oddlabs.tt.model.MountUnitContainer) tower.getUnitContainer()).getUnit();
            if (inside == null || Intel.warriorType(inside) == WarriorType.CHICKEN)
                continue;
            if (enemyStrengthNear(tower.getGridX(), tower.getGridY(), 30) > 0)
                continue;
            for (Map.Entry<Unit, Role> e : roles.entrySet()) {
                Unit u = e.getKey();
                if (e.getValue() == Role.ARMY && Intel.warriorType(u) == WarriorType.CHICKEN
                        && intel.warrior_states.get(u) == WarriorState.IDLE
                        && MapAnalysis.dist2(u.getGridX(), u.getGridY(), tower.getGridX(),
                                tower.getGridY()) < 50 * 50) {
                    ai.owner().exitTower(tower);
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Home

    private void holdStaging() {
        boolean escorting = mode == Mode.HOME && ai.time() - escort_time < 3f;
        int x = escorting ? escort_x : staging_x;
        int y = escorting ? escort_y : staging_y;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() != Role.ARMY)
                continue;
            Unit u = e.getKey();
            if (threat_level > 0)
                continue;
            WarriorState s = ai.intel().warrior_states.get(u);
            if (s != WarriorState.IDLE)
                continue;
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y) > 7 * 7)
                attackGround(u, x, y, false);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Forward towers

    /** Whether the army is free and strong enough to stand guard out in the field. */
    boolean canEscort() {
        return mode == Mode.HOME && threat_level == 0
                && armyStrength() >= Math.max(12f, ai.strategy().forward_ratio * enemyFieldStrength());
    }

    /** Keeps the home army at a spot for the next few seconds, to cover builders there. */
    void escort(int x, int y) {
        escort_x = x;
        escort_y = y;
        escort_time = ai.time();
    }

    /** Whether most of the home army stands around a spot. */
    boolean escortArrived(int x, int y) {
        int n = 0;
        int near = 0;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() != Role.ARMY)
                continue;
            n++;
            Unit u = e.getKey();
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y) <= 14 * 14)
                near++;
        }
        return n > 0 && near * 10 >= n * 6;
    }

    /**
     * Where a tower would hurt the enemy most: over the busiest group of his peons working iron, away from his
     * towers and armory. Null when nothing is worth it.
     */
    int @Nullable [] forwardTarget() {
        Intel intel = ai.intel();
        DistanceField ours = ai.planner().getStartField();
        int[] best = null;
        int best_count = 3;
        for (Unit p : intel.enemy_peons) {
            int px = p.getGridX();
            int py = p.getGridY();
            boolean at_iron = false;
            for (IronSupply s : ai.map().getIron()) {
                if (!s.isEmpty() && MapAnalysis.dist2(px, py, s.getGridX(), s.getGridY()) <= 3 * 3) {
                    at_iron = true;
                    break;
                }
            }
            if (!at_iron || ours.getAround(px, py, 2) == DistanceField.UNREACHABLE)
                continue;
            boolean guarded = false;
            for (Building t : intel.enemy_towers)
                guarded |= MapAnalysis.dist2(t.getGridX(), t.getGridY(), px, py) <= 16 * 16;
            for (Building a : intel.enemy_armories)
                guarded |= MapAnalysis.dist2(a.getGridX(), a.getGridY(), px, py) <= 20 * 20;
            if (guarded)
                continue;
            int count = Combat.countNear(intel.enemy_peons, px, py, 8);
            if (count > best_count) {
                best_count = count;
                best = new int[]{px, py};
            }
        }
        return best;
    }

    private float attackStrength() {
        float s = 0f;
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.ATTACK)
                s += Combat.value(e.getKey());
        return s;
    }

    /** What would meet an army attacking the given spot: towers there plus most of the enemy's field army. */
    private float defenseAt(int x, int y) {
        Intel intel = ai.intel();
        // Defenders waiting for an attacker win even fights about three times in four.
        float s = 1.1f * enemyFieldStrength();
        for (Building t : intel.enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= 22 * 22)
                s += Combat.towerValue(t);
        // Peons near their base pile onto attackers.
        s += .5f * Combat.strengthNear(intel.enemy_peons, x, y, 40);
        // Weapons stocked in an armory nearby come out as soon as the attack shows up.
        for (Building a : intel.enemy_armories)
            if (!a.isDead() && MapAnalysis.dist2(a.getGridX(), a.getGridY(), x, y) <= 40 * 40)
                s += stockStrength(a);
        return s;
    }

    private @Nullable Selectable<?> chooseTarget(int from_x, int from_y) {
        Intel intel = ai.intel();
        Selectable<?> best = null;
        float best_score = Float.MAX_VALUE;
        List<Building> candidates = new ArrayList<>(intel.enemy_armories);
        candidates.addAll(intel.enemy_quarters);
        candidates.addAll(intel.enemy_towers);
        if (candidates.isEmpty())
            candidates.addAll(intel.enemy_buildings);
        for (Building b : candidates) {
            if (b.isDead())
                continue;
            float d = MapAnalysis.meters(from_x, from_y, b.getGridX(), b.getGridY());
            float priority = switch (b.getTemplate().getTemplateID()) {
                case com.oddlabs.tt.model.Race.BUILDING_ARMORY -> 0f;
                case com.oddlabs.tt.model.Race.BUILDING_QUARTERS -> 60f;
                default -> 120f;
            };
            float score = d + priority + 8f * defenseAt(b.getGridX(), b.getGridY());
            if (score < best_score) {
                best_score = score;
                best = b;
            }
        }
        if (best == null) {
            List<Unit> units = new ArrayList<>(intel.enemy_peons);
            units.addAll(intel.enemy_warriors);
            units.addAll(intel.enemy_chieftains);
            int best_d = Integer.MAX_VALUE;
            for (Unit u : units) {
                int d = MapAnalysis.dist2(from_x, from_y, u.getGridX(), u.getGridY());
                if (d < best_d) {
                    best_d = d;
                    best = u;
                }
            }
        }
        return best;
    }

    /**
     * How fast the enemy's field army has grown over the last minute or so, in strength per second: warriors
     * outside plus weapons it could deploy are not visible, so this watches what comes out.
     */
    private float enemyGrowthPerSecond() {
        if (enemy_history.size() < 2)
            return 0f;
        float[] first = enemy_history.getFirst();
        float[] last = enemy_history.getLast();
        float dt = last[0] - first[0];
        return dt > 0f ? (last[1] - first[1]) / dt : 0f;
    }

    private void recordEnemyStrength() {
        enemy_history.addLast(new float[]{ai.time(), enemyFieldStrength()});
        while (enemy_history.size() > 2 && ai.time() - enemy_history.getFirst()[0] > 90f)
            enemy_history.removeFirst();
    }

    private void considerAttack() {
        Intel intel = ai.intel();
        Strategy strategy = ai.strategy();
        Selectable<?> t = chooseTarget(staging_x, staging_y);
        if (t == null)
            return;
        float army = armyStrength();
        float potential = army + stockStrength();
        float defense = defenseAt(t.getGridX(), t.getGridY());
        if (strategy.project_defense) {
            // The enemy keeps arming while we march; judge the fight at the moment of arrival.
            int d = ai.planner().getEnemyField().get(staging_x, staging_y);
            float march = d == DistanceField.UNREACHABLE ? 120f : d / 3f;
            defense += Math.max(0f, enemyGrowthPerSecond()) * march;
        }
        // Chieftains decide battles: count ours as a big plus and theirs as a big minus, unless ours can answer his.
        boolean chief = intel.chieftain != null && intel.chieftain.getHitPoints() > 30;
        boolean enemy_chief = false;
        for (Unit c : intel.enemy_chieftains)
            enemy_chief |= !c.isDead() && c.getHitPoints() > 15;
        float bonus = chief && !enemy_chief ? 1.4f : chief ? 1.05f : enemy_chief ? .7f : 1f;
        Player owner = ai.owner();
        boolean capped = owner.getUnitCountContainer().getNumSupplies() >= owner.getWorld().getMaxUnitCount() - 10;
        boolean go = potential >= strategy.attack_min_strength && potential * bonus >= strategy.attack_ratio * defense;
        go |= potential >= strategy.attack_max_strength && potential * bonus >= .8f * defense;
        go |= capped && potential * bonus >= strategy.capped_ratio * defense;
        if (!go || ai.time() < next_wave_time)
            return;
        target = t;
        mode = Mode.MUSTER;
        muster_start = ai.time();
        ai.log(String.format("muster: army %.1f + stock %.1f vs defense %.1f at %d,%d", army, potential - army,
                defense, t.getGridX(), t.getGridY()));
    }

    /**
     * Offensive towers and other enemy buildings going up inside the base or next to our gatherers are cheapest to
     * kill right away, before they are finished and manned. The home army goes out, knocks the building down and
     * comes back.
     */
    private void considerStrike() {
        Building b = intruder();
        if (b == null)
            return;
        int bx = b.getGridX();
        int by = b.getGridY();
        float defense = 1.1f * enemyFightersNear(bx, by, 24);
        for (Building t : ai.intel().enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), bx, by) <= 12 * 12)
                defense += Combat.towerValue(t);
        float army = armyStrength();
        if (army < Math.max(4f, 1.5f * defense))
            return;
        target = b;
        strike = true;
        ai.log(String.format("strike on %s at %d,%d: army %.1f vs %.1f", b, bx, by, army, defense));
        launchAttack();
    }

    /** The enemy building closest to our own inside the base or near our gatherers, or null. */
    private @Nullable Building intruder() {
        Intel intel = ai.intel();
        List<Building> own = new ArrayList<>(intel.armories);
        own.addAll(intel.quarters);
        own.addAll(intel.towers);
        int r = ai.strategy().base_radius + 6;
        Building best = null;
        int best_d = Integer.MAX_VALUE;
        for (Building b : intel.enemy_buildings) {
            if (b.isDead())
                continue;
            int d = Integer.MAX_VALUE;
            for (Building o : own)
                d = Math.min(d, MapAnalysis.dist2(o.getGridX(), o.getGridY(), b.getGridX(), b.getGridY()));
            if (d > r * r && !gatherersNear(b.getGridX(), b.getGridY(), 16))
                continue;
            if (d < best_d) {
                best_d = d;
                best = b;
            }
        }
        return best;
    }

    private boolean gatherersNear(int x, int y, int radius) {
        Intel intel = ai.intel();
        for (Unit p : intel.peons) {
            PeonState s = intel.peon_states.get(p);
            if ((s == PeonState.GATHER_TREE || s == PeonState.GATHER_ROCK || s == PeonState.GATHER_IRON)
                    && MapAnalysis.dist2(x, y, p.getGridX(), p.getGridY()) <= radius * radius)
                return true;
        }
        return false;
    }

    private float stockStrength() {
        Building armory = ai.intel().armory();
        return armory == null ? 0f : stockStrength(armory);
    }

    /** Warriors an armory could deploy right away: weapons in stock, as far as peons inside can carry them. */
    private static float stockStrength(@NonNull Building armory) {
        int workers = armory.getUnitContainer().getNumSupplies();
        int iron = armory.getSupplyContainer(com.oddlabs.tt.model.weapon.IronAxeWeapon.class).getNumSupplies();
        int chicken = armory.getSupplyContainer(com.oddlabs.tt.model.weapon.RubberAxeWeapon.class).getNumSupplies();
        int rock = armory.getSupplyContainer(com.oddlabs.tt.model.weapon.RockAxeWeapon.class).getNumSupplies();
        float s = 0f;
        int left = workers;
        int c = Math.min(chicken, left);
        s += c * Combat.CHICKEN;
        left -= c;
        int i = Math.min(iron, left);
        s += i * Combat.IRON;
        left -= i;
        s += Math.min(rock, left) * Combat.ROCK;
        return s;
    }

    /** Empties the armory, waits for the warriors to reach the staging point, then marches. */
    private void muster() {
        holdStaging();
        Building armory = ai.intel().armory();
        boolean stock_left = armory != null && stockStrength() > .5f
                && armory.getUnitContainer().getNumSupplies() > 0;
        int pending = 0;
        if (armory != null) {
            pending += armory.getDeployContainer(com.oddlabs.tt.model.DeployType.IRON_WARRIOR).getNumSupplies();
            pending += armory.getDeployContainer(com.oddlabs.tt.model.DeployType.RUBBER_WARRIOR).getNumSupplies();
            pending += armory.getDeployContainer(com.oddlabs.tt.model.DeployType.ROCK_WARRIOR).getNumSupplies();
        }
        int near = 0;
        int total = 0;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() != Role.ARMY)
                continue;
            total++;
            Unit u = e.getKey();
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), staging_x, staging_y) <= 12 * 12)
                near++;
        }
        boolean gathered = total > 0 && near >= total * 8 / 10;
        boolean timeout = ai.time() - muster_start > 45f;
        if ((!stock_left && pending == 0 && gathered) || timeout)
            launchAttack();
    }

    private void launchAttack() {
        Selectable<?> t = target != null && !target.isDead() ? target : chooseTarget(staging_x, staging_y);
        if (t == null) {
            mode = Mode.HOME;
            return;
        }
        setTarget(t);
        float s = 0f;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() == Role.ARMY) {
                e.setValue(Role.ATTACK);
                s += Combat.value(e.getKey());
            }
        }
        attack_initial_strength = s;
        attack_start = ai.time();
        last_progress_time = ai.time();
        best_target_dist = Integer.MAX_VALUE;
        mode = s > 0 ? Mode.ATTACK : Mode.HOME;
        ai.log(String.format("attack with %.1f on %s at %d,%d", s, t, t.getGridX(), t.getGridY()));
    }

    private void setTarget(@NonNull Selectable<?> t) {
        target = t;
        if (target_field == null || MapAnalysis.dist2(target_x, target_y, t.getGridX(), t.getGridY()) > 8 * 8) {
            target_x = t.getGridX();
            target_y = t.getGridY();
            target_field = ai.map().computeField(target_x, target_y, Integer.MAX_VALUE);
            best_target_dist = Integer.MAX_VALUE;
            last_progress_time = ai.time();
        }
    }

    private void endAttack() {
        if (mode != Mode.HOME)
            ai.log("attack over, back home");
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.ATTACK)
                e.setValue(Role.ARMY);
        mode = Mode.HOME;
        target = null;
        next_wave_time = ai.time() + 20f;
        strike = false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Attack

    private void attack() {
        Intel intel = ai.intel();
        List<Unit> army = new ArrayList<>();
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.ATTACK)
                army.add(e.getKey());
        if (army.isEmpty()) {
            endAttack();
            return;
        }
        int[] c = centroid(army);
        float ours = 0f;
        for (Unit u : army)
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), c[0], c[1]) <= 18 * 18)
                ours += Combat.value(u);
        float local_enemy = enemyFightersNear(c[0], c[1], ENGAGE_RADIUS);
        for (Building t : intel.enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), c[0], c[1]) <= ENGAGE_RADIUS * ENGAGE_RADIUS)
                local_enemy += Combat.towerValue(t);
        // Height decides a lot: up to a quarter more (or less) chance to hit.
        ours *= terrainFactor(army, intel.enemy_warriors, c[0], c[1], ENGAGE_RADIUS);
        boolean toot = ai.chieftain().stunReady() && intel.chieftain != null
                && MapAnalysis.dist2(intel.chieftain.getGridX(), intel.chieftain.getGridY(), c[0], c[1]) < 20 * 20;
        if (enemyStunReadyNear(c[0], c[1], ENGAGE_RADIUS + 4))
            local_enemy *= 1.5f;
        float total = 0f;
        int stunned_count = 0;
        for (Unit u : army) {
            total += Combat.lastingValue(u);
            if (Intel.isStunned(u))
                stunned_count++;
        }
        // A stunned army cannot walk away; decide once it can move again.
        boolean pinned = stunned_count * 10 > army.size() * 3;
        if (ai.logging() && ai.time() - last_trace >= 4f) {
            last_trace = ai.time();
            int far = 0;
            int fighting = 0;
            for (Unit u : army) {
                if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), c[0], c[1]) > 16 * 16)
                    far++;
                if (intel.warrior_states.get(u) == WarriorState.FIGHT)
                    fighting++;
            }
            int enemies = Combat.countNear(intel.enemy_warriors, c[0], c[1], ENGAGE_RADIUS);
            int towers = Combat.countNear(intel.enemy_towers, c[0], c[1], ENGAGE_RADIUS);
            int target_d = target_field != null ? target_field.get(c[0], c[1]) : -1;
            if (enemies > 0)
                ExpertAI.debug_battle = new int[]{c[0], c[1]};
            int[] types = new int[3];
            int stunned = 0;
            for (Unit e : intel.enemy_warriors) {
                if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), c[0], c[1]) > ENGAGE_RADIUS * ENGAGE_RADIUS)
                    continue;
                types[Intel.warriorType(e).ordinal()]++;
                if (Intel.isStunned(e))
                    stunned++;
            }
            int our_stunned = 0;
            for (Unit u : army)
                if (Intel.isStunned(u))
                    our_stunned++;
            ai.log(String.format(
                    "battle: army %d (%.1f, %d far, %d fighting, %d stunned) at %d,%d h%.0f, %dm to " + "target; near: %d warriors (r%d i%d c%d, %d stunned) h%.0f %d towers (%.1f)",
                    army.size(), total,
                    far, fighting, our_stunned, c[0], c[1], meanHeight(army, c[0], c[1], 18), target_d, enemies,
                    types[0], types[1], types[2], stunned, meanHeight(intel.enemy_warriors, c[0], c[1],
                            ENGAGE_RADIUS), towers, local_enemy));
        }
        // Enemies lying stunned nearby cannot fight back for a while. As long as we can take on the ones still awake,
        // run the stunned down instead of weighing the odds, which would count them as awake again soon.
        List<Unit> stunned = pinned || !ai.strategy().exploit_stun ? List.of() : stunnedEnemiesNear(c[0], c[1], 36);
        if (!stunned.isEmpty()) {
            float asleep = 0f;
            for (Unit e : stunned)
                asleep += Combat.lastingValue(e);
            float awake = enemyFightersNear(c[0], c[1], 36);
            for (Building t : intel.enemy_towers)
                if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), c[0], c[1]) <= 36 * 36)
                    awake += Combat.towerValue(t);
            // An enemy chieftain with his spell ready would stun the charge in turn.
            if (asleep >= 3f && total >= .8f * awake && !enemyStunReadyNear(c[0], c[1], 45)) {
                if (ai.time() - last_charge_log > 10f) {
                    last_charge_log = ai.time();
                    ai.log(String.format("charging %d stunned enemies (%.1f asleep, %.1f awake, army %.1f)",
                            stunned.size(), asleep, awake, total));
                }
                int[] sc = centroid(stunned);
                engageSpread(army, stunned, sc[0], sc[1], true);
                huntChieftains(army);
                return;
            }
        }
        if (!toot && !pinned && ai.strategy().precontact_ratio > 0f && !anyFighting(army)) {
            // Before contact, look at everything that can defend the area, not just what is next to us: turning
            // back now costs nothing, walking into a stronger defense costs the army.
            float wide = enemyFightersNear(c[0], c[1], 36);
            for (Building t : intel.enemy_towers)
                if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), c[0], c[1]) <= 36 * 36)
                    wide += Combat.towerValue(t);
            float terrain = terrainFactor(army, intel.enemy_warriors, c[0], c[1], 36);
            if (wide > 0f && total * terrain < ai.strategy().precontact_ratio * wide) {
                ai.log(String.format("turning back before contact: %.1f against %.1f", total * terrain, wide));
                beginRetreat();
                return;
            }
        }
        if (!toot && !pinned && local_enemy > ai.strategy().retreat_ratio * Math.max(ours, 1f)) {
            ai.log(String.format("retreat: local %.1f vs enemy %.1f (army %.1f of %.1f)", ours, local_enemy, total,
                    attack_initial_strength));
            beginRetreat();
            return;
        }
        if (!pinned && total < .2f * attack_initial_strength && local_enemy > total) {
            ai.log(String.format("retreat: worn down to %.1f of %.1f", total, attack_initial_strength));
            beginRetreat();
            return;
        }
        if (target == null || target.isDead()) {
            if (strike) {
                endAttack();
                return;
            }
            Selectable<?> next = chooseTarget(c[0], c[1]);
            if (next == null) {
                endAttack();
                return;
            }
            setTarget(next);
        }
        // An enemy army walking at us: wait for it on the best ground nearby instead of running uphill into it.
        if (holdForApproachingEnemy(army, c))
            return;
        // Fight what is close, otherwise keep marching as one clump.
        int[] focus = enemyFocus(c[0], c[1]);
        if (focus != null) {
            List<Selectable<?>> fighters = new ArrayList<>();
            for (Unit e : intel.enemy_warriors)
                if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), c[0],
                        c[1]) <= (ENGAGE_RADIUS + 8) * (ENGAGE_RADIUS + 8))
                    fighters.add(e);
            for (Unit e : intel.enemy_chieftains)
                if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), c[0],
                        c[1]) <= (ENGAGE_RADIUS + 8) * (ENGAGE_RADIUS + 8))
                    fighters.add(e);
            engageSpread(army, fighters, focus[0], focus[1], true);
            huntChieftains(army);
            return;
        }
        // March along the path as one body. Units are ranked by how far along the path they are; the pivot is a third
        // of the way back from the front, so the lead keeps moving while the tail catches up. Positions along the
        // path, unlike the centroid, stay meaningful when the army wraps around a cliff.
        DistanceField field = target_field;
        int dist = DistanceField.UNREACHABLE;
        int[] waypoint = new int[]{target_x, target_y};
        Unit pivot = null;
        int pivot_dist = 0;
        if (field != null) {
            List<Unit> ranked = new ArrayList<>(army);
            ranked.removeIf(u -> field.getAround(u.getGridX(), u.getGridY(), 1) == DistanceField.UNREACHABLE);
            ranked.sort((a, b) -> Integer.compare(field.getAround(a.getGridX(), a.getGridY(), 1),
                    field.getAround(b.getGridX(), b.getGridY(), 1)));
            if (!ranked.isEmpty()) {
                pivot = ranked.get(ranked.size() / 3);
                pivot_dist = field.getAround(pivot.getGridX(), pivot.getGridY(), 1);
                dist = pivot_dist;
            }
        }
        // A big army is wide; lead it far enough ahead that its middle keeps moving.
        int lead = 22 + 3 * (int) Math.sqrt(army.size());
        if (pivot != null && dist >= 30)
            waypoint = field.stepTowardsSource(pivot.getGridX(), pivot.getGridY(), lead);
        for (Unit u : army) {
            int d = field != null ? field.getAround(u.getGridX(), u.getGridY(), 1) : DistanceField.UNREACHABLE;
            // Units well ahead of the pivot wait for the rest instead of walking on alone.
            if (pivot != null && d != DistanceField.UNREACHABLE && d < pivot_dist - 24)
                continue;
            attackGround(u, waypoint[0], waypoint[1], false);
        }
        // Give up only when the army stops getting anywhere, not because the march is long.
        if (dist != DistanceField.UNREACHABLE && dist < best_target_dist - 20) {
            best_target_dist = dist;
            last_progress_time = ai.time();
        }
        if (ai.time() - last_progress_time > 75f && local_enemy == 0f) {
            ai.log("attack stalled");
            beginRetreat();
        }
    }

    /**
     * How much better our warriors fight than theirs around (x, y) because of height, as a factor on our strength.
     * Throws gain 1/80 hit chance per meter above the target, up to a quarter; in Lanchester terms the strength ratio
     * moves with the square root of the hit chance ratio.
     */
    private float terrainFactor(@NonNull List<@NonNull Unit> ours, @NonNull List<@NonNull Unit> theirs, int x, int y,
            int radius) {
        float h_ours = meanHeight(ours, x, y, radius);
        float h_theirs = meanHeight(theirs, x, y, radius + 6);
        if (Float.isNaN(h_ours) || Float.isNaN(h_theirs))
            return 1f;
        float bonus = Math.clamp((h_ours - h_theirs) / 80f, -.25f, .25f);
        return (float) Math.sqrt((.75f + bonus) / (.75f - bonus));
    }

    private float meanHeight(@NonNull List<@NonNull Unit> units, int x, int y, int radius) {
        float sum = 0f;
        int n = 0;
        for (Unit u : units) {
            if (u.isDead() || MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()) > radius * radius)
                continue;
            sum += ai.map().height(u.getGridX(), u.getGridY());
            n++;
        }
        return n == 0 ? Float.NaN : sum / n;
    }

    /**
     * When an enemy army comes at ours but is not yet in reach, stop on the highest ground close by and let it walk
     * into our throws. Gives up after a while if they do not come, so a waiting enemy cannot stall the attack.
     */
    private boolean holdForApproachingEnemy(@NonNull List<@NonNull Unit> army, int @NonNull [] c) {
        Intel intel = ai.intel();
        int far = Combat.countNear(intel.enemy_warriors, c[0], c[1], 26);
        int near = 0;
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead())
                continue;
            for (Unit u : army) {
                if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), e.getGridX(), e.getGridY()) <= 11 * 11) {
                    near++;
                    break;
                }
            }
            if (near > 0)
                break;
        }
        if (far < 3 || near > 0) {
            hold_until = -1f;
            return false;
        }
        int[] enemy = nearestGroup(intel.enemy_warriors, c[0], c[1], 26);
        if (enemy == null)
            return false;
        int d = MapAnalysis.dist2(c[0], c[1], enemy[0], enemy[1]);
        boolean closing = d < last_enemy_d2 - 4;
        last_enemy_d2 = d;
        if (hold_until < 0f) {
            if (!closing)
                return false;
            hold_until = ai.time() + 12f;
            hold_spot = ai.map().highGround(c[0], c[1], 6);
            ai.log(String.format("holding at %d,%d (%.0fm up) for enemy army at %d,%d", hold_spot[0], hold_spot[1],
                    ai.map().height(hold_spot[0], hold_spot[1]) - ai.map().height(c[0], c[1]), enemy[0], enemy[1]));
        }
        if (ai.time() > hold_until)
            return false;
        for (Unit u : army)
            attackGround(u, hold_spot[0], hold_spot[1], false);
        return true;
    }

    /** Enemy warriors and chieftains lying stunned within radius cells of a spot. */
    private @NonNull List<@NonNull Unit> stunnedEnemiesNear(int x, int y, int radius) {
        Intel intel = ai.intel();
        List<Unit> stunned = new ArrayList<>();
        int r2 = radius * radius;
        for (Unit e : intel.enemy_warriors)
            if (!e.isDead() && Intel.isStunned(e) && MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= r2)
                stunned.add(e);
        for (Unit e : intel.enemy_chieftains)
            if (!e.isDead() && Intel.isStunned(e) && MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= r2)
                stunned.add(e);
        return stunned;
    }

    /** Where the nearest enemies around the army are, preferring warriors, then towers, peons and buildings. */
    private int @Nullable [] enemyFocus(int x, int y) {
        Intel intel = ai.intel();
        int[] w = nearestGroup(intel.enemy_warriors, x, y, ENGAGE_RADIUS);
        if (w != null)
            return w;
        int[] ch = nearestGroup(intel.enemy_chieftains, x, y, ENGAGE_RADIUS);
        if (ch != null)
            return ch;
        int[] t = nearestGroup(intel.enemy_towers, x, y, ENGAGE_RADIUS);
        if (t != null)
            return t;
        int[] p = nearestGroup(intel.enemy_peons, x, y, 14);
        if (p != null)
            return p;
        return nearestGroup(intel.enemy_buildings, x, y, 14);
    }

    private static int @Nullable [] nearestGroup(@NonNull List<? extends Selectable<?>> units, int x, int y,
            int radius) {
        Selectable<?> nearest = null;
        int best = radius * radius;
        for (Selectable<?> s : units) {
            if (s.isDead())
                continue;
            int d = MapAnalysis.dist2(x, y, s.getGridX(), s.getGridY());
            if (d <= best) {
                best = d;
                nearest = s;
            }
        }
        if (nearest == null)
            return null;
        // Aim at the middle of the group around the nearest one so the whole army piles in together.
        long sx = 0;
        long sy = 0;
        int n = 0;
        for (Selectable<?> s : units) {
            if (s.isDead())
                continue;
            if (MapAnalysis.dist2(nearest.getGridX(), nearest.getGridY(), s.getGridX(), s.getGridY()) <= 8 * 8) {
                sx += s.getGridX();
                sy += s.getGridY();
                n++;
            }
        }
        return new int[]{(int) (sx / n), (int) (sy / n)};
    }

    private void beginRetreat() {
        mode = Mode.RETREAT;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() == Role.ATTACK)
                move(e.getKey(), staging_x, staging_y);
        }
    }

    private void retreat() {
        int n = 0;
        int home = 0;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() != Role.ATTACK)
                continue;
            Unit u = e.getKey();
            n++;
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), staging_x, staging_y) <= 14 * 14)
                home++;
            else if (ai.intel().warrior_states.get(u) == WarriorState.IDLE)
                move(u, staging_x, staging_y);
        }
        if (n == 0 || home >= n * 7 / 10)
            endAttack();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Raids on enemy gatherers

    private void considerRaid() {
        Strategy strategy = ai.strategy();
        if (ai.time() < strategy.raid_time || ai.time() - last_raid_end < 40f)
            return;
        if (countRole(Role.RAID) > 0)
            return;
        int army = countRole(Role.ARMY);
        if (army < strategy.raid_size + 6)
            return;
        int[] spot = raidSpot(staging_x, staging_y, strategy.raid_size * Combat.IRON);
        if (spot == null)
            return;
        List<Unit> squad = new ArrayList<>();
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (squad.size() >= strategy.raid_size)
                break;
            Unit u = e.getKey();
            if (e.getValue() == Role.ARMY && Intel.warriorType(u) == WarriorType.IRON
                    && ai.intel().warrior_states.get(u) == WarriorState.IDLE)
                squad.add(u);
        }
        if (squad.size() < strategy.raid_size)
            return;
        for (Unit u : squad) {
            roles.put(u, Role.RAID);
            attackGround(u, spot[0], spot[1], true);
        }
        raid_x = spot[0];
        raid_y = spot[1];
        raid_start = ai.time();
        ai.log("raid with " + squad.size() + " on peons at " + raid_x + "," + raid_y);
    }

    /** A cluster of enemy peons far from their warriors and towers, or null. */
    private int @Nullable [] raidSpot(int from_x, int from_y, float strength) {
        Intel intel = ai.intel();
        int[] best = null;
        float best_score = Float.MAX_VALUE;
        for (Unit p : intel.enemy_peons) {
            int px = p.getGridX();
            int py = p.getGridY();
            float danger = enemyStrengthNear(px, py, 30);
            for (Building t : intel.enemy_towers)
                if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), px, py) <= 12 * 12)
                    danger += Combat.towerValue(t);
            if (danger > .5f * strength)
                continue;
            int count = Combat.countNear(intel.enemy_peons, px, py, 10);
            if (count < 3)
                continue;
            float score = MapAnalysis.meters(from_x, from_y, px, py) - 25f * count;
            if (score < best_score) {
                best_score = score;
                best = new int[]{px, py};
            }
        }
        return best;
    }

    private void raid() {
        List<Unit> squad = new ArrayList<>();
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.RAID)
                squad.add(e.getKey());
        if (squad.isEmpty())
            return;
        int[] c = centroid(squad);
        float ours = 0f;
        for (Unit u : squad)
            ours += Combat.value(u);
        float danger = enemyStrengthNear(c[0], c[1], 24);
        for (Building t : ai.intel().enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), c[0], c[1]) <= 10 * 10)
                danger += Combat.towerValue(t);
        boolean done = ai.time() - raid_start > 150f;
        if (danger > .8f * ours || done || squad.size() < 2) {
            for (Unit u : squad) {
                roles.put(u, Role.ARMY);
                move(u, staging_x, staging_y);
            }
            last_raid_end = ai.time();
            return;
        }
        int[] peons = nearestGroup(ai.intel().enemy_peons, c[0], c[1], 18);
        if (peons != null) {
            for (Unit u : squad)
                attackGround(u, peons[0], peons[1], true);
            return;
        }
        int[] next = raidSpot(c[0], c[1], ours);
        if (next != null && MapAnalysis.dist2(next[0], next[1], c[0], c[1]) < 70 * 70) {
            raid_x = next[0];
            raid_y = next[1];
        }
        for (Unit u : squad)
            attackGround(u, raid_x, raid_y, false);
        if (MapAnalysis.dist2(c[0], c[1], raid_x, raid_y) < 5 * 5 && peons == null) {
            for (Unit u : squad) {
                roles.put(u, Role.ARMY);
                move(u, staging_x, staging_y);
            }
            last_raid_end = ai.time();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Orders

    private int countRole(@NonNull Role role) {
        int n = 0;
        for (Role r : roles.values())
            if (r == role)
                n++;
        return n;
    }

    static int @NonNull [] centroid(@NonNull List<? extends Selectable<?>> units) {
        long sx = 0;
        long sy = 0;
        for (Selectable<?> u : units) {
            sx += u.getGridX();
            sy += u.getGridY();
        }
        int n = Math.max(1, units.size());
        return new int[]{(int) (sx / n), (int) (sy / n)};
    }

    /**
     * Attack-moves a warrior to a spot. Warriors already fighting are left alone unless forced, and the same order is
     * not repeated more often than every few seconds, since each order makes the unit find a new path.
     */
    private void attackGround(@NonNull Unit u, int x, int y, boolean urgent) {
        WarriorState s = ai.intel().warrior_states.get(u);
        if (s == WarriorState.FIGHT || s == WarriorState.STUNNED || s == WarriorState.ENTER)
            return;
        Float last = last_order.get(u);
        int[] last_spot = last_spots.get(u);
        boolean same_spot = last_spot != null && MapAnalysis.dist2(last_spot[0], last_spot[1], x, y) <= 3 * 3;
        float period = urgent ? 1f : same_spot ? 2 * REORDER_PERIOD : REORDER_PERIOD;
        if (last != null && ai.time() - last < period && s != WarriorState.IDLE)
            return;
        if (s == WarriorState.IDLE && same_spot && MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y) <= 4 * 4)
            return; // already there
        queueOrder(u, x, y, true);
    }

    /**
     * Sends each warrior at the enemy nearest to it rather than all at one spot: they fan out along the enemy front
     * and nearly all get to throw, where a clump only fights with its edge. Staged fights: an attacker spreading
     * this way beat an equal waiting arc 7 times in 8, against 6 when clumping.
     */
    private void engageSpread(@NonNull List<@NonNull Unit> units, @NonNull List<? extends Selectable<?>> enemies,
            int fallback_x, int fallback_y, boolean urgent) {
        for (Unit u : units) {
            if (!ai.strategy().engage_spread) {
                attackGround(u, fallback_x, fallback_y, urgent);
                continue;
            }
            Selectable<?> nearest = null;
            int best = Integer.MAX_VALUE;
            for (Selectable<?> e : enemies) {
                if (e.isDead())
                    continue;
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), e.getGridX(), e.getGridY());
                if (d < best) {
                    best = d;
                    nearest = e;
                }
            }
            if (nearest != null)
                attackGround(u, nearest.getGridX(), nearest.getGridY(), urgent);
            else
                attackGround(u, fallback_x, fallback_y, urgent);
        }
    }

    private void move(@NonNull Unit u, int x, int y) {
        queueOrder(u, x, y, false);
    }

    /**
     * Orders are collected and sent once per tick, one call per destination: the game only spreads units over
     * separate cells around the spot when they are ordered together, otherwise they all queue for the same cell.
     */
    private void queueOrder(@NonNull Unit u, int x, int y, boolean aggressive) {
        last_order.put(u, ai.time());
        last_spots.put(u, new int[]{x, y});
        long key = ((long) x << 32) | ((long) y << 1) | (aggressive ? 1 : 0);
        pending_orders.computeIfAbsent(key, k -> new ArrayList<>()).add(u);
    }

    private void flushOrders() {
        for (Map.Entry<Long, List<Unit>> e : pending_orders.entrySet()) {
            long key = e.getKey();
            int x = (int) (key >> 32);
            int y = (int) ((key & 0xffffffffL) >> 1);
            boolean aggressive = (key & 1) != 0;
            List<Unit> units = e.getValue();
            ai.owner().setLandscapeTarget(units.toArray(new Selectable<?>[0]), x, y,
                    aggressive ? Action.ATTACK : Action.MOVE, aggressive);
        }
        pending_orders.clear();
    }

    int stagingX() {
        return staging_x;
    }

    int stagingY() {
        return staging_y;
    }

    @NonNull
    Mode mode() {
        return mode;
    }

    int threatX() {
        return threat_x;
    }

    int threatY() {
        return threat_y;
    }

    private boolean anyFighting(@NonNull List<@NonNull Unit> units) {
        for (Unit u : units)
            if (ai.intel().warrior_states.get(u) == WarriorState.FIGHT)
                return true;
        return false;
    }

    /** Middle of the attacking army, or null when it is at home. */
    int @Nullable [] attackCenter() {
        List<Unit> army = new ArrayList<>();
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.ATTACK)
                army.add(e.getKey());
        return army.isEmpty() ? null : centroid(army);
    }

    /** Where the fighting is, for the chieftain: the base threat, else the attacking army. */
    int @Nullable [] battleFront() {
        if (threat_level > 0)
            return new int[]{threat_x, threat_y};
        List<Unit> army = new ArrayList<>();
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.ATTACK)
                army.add(e.getKey());
        if (!army.isEmpty())
            return centroid(army);
        return null;
    }

    @NonNull
    String debugStatus() {
        return "mode=" + mode + " army=" + countRole(Role.ARMY) + " atk=" + countRole(
                Role.ATTACK) + " raid=" + countRole(Role.RAID) + " twr=" + countRole(Role.TOWER) + String.format(
                        " str=%.1f thr=%d/%.1f",
                        armyStrength(), threat_level, threat_strength);
    }
}
