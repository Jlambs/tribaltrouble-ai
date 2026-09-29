package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.gauntlet.Intel.PeonState;
import com.oddlabs.tt.player.gauntlet.Intel.WarriorState;
import com.oddlabs.tt.player.gauntlet.Intel.WarriorType;
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
        /** On the way to join the attacking army. */
        REINFORCE,
        RAID,
        /** chief_hunt squad. */
        CHASE
    }

    private static final int ENGAGE_RADIUS = 22;
    private static final float REORDER_PERIOD = 2.5f;

    private final @NonNull GauntletAI ai;
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
    /** Enemy peons raiding the base, kept apart from the threats so that they do not draw the army about. */
    private final List<@NonNull Unit> raiders = new ArrayList<>();
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
    /**
     * Targets whose attack stalled (the army could not get there: a site on ground it cannot reach), and when. Target
     * choice skips them for STALL_MEMORY seconds, so the army moves on instead of marching at them for hours (the
     * N=10 draw gfinal-vs10-hv s93: 5 hours of attacks on an unreachable site while two remnant copies lived on).
     */
    private final Map<@NonNull Selectable<?>, Float> stalled_targets = new LinkedHashMap<>();
    private static final float STALL_MEMORY = 600f;
    /**
     * Regions the army could not enter (the stalled target's distance field did not reach our staging point), and when
     * found: every target inside one is skipped too (N=10 s93: a copy's 20 peons stuck with a site in a 252-cell pocket
     * drew every re-target of the army). At most three are kept.
     */
    private final List<@NonNull DistanceField> dead_regions = new ArrayList<>();
    private final List<@NonNull Float> dead_region_times = new ArrayList<>();

    /** Whether (x, y) lies in a region the army found it cannot enter. */
    private boolean inDeadRegion(int x, int y) {
        for (DistanceField f : dead_regions)
            if (f.getAround(x, y, 2) != DistanceField.UNREACHABLE)
                return true;
        return false;
    }

    /** The copy whose buildings the attacks go after first while it is alive (focus_bonus). */
    private com.oddlabs.tt.player.@Nullable Player focus_owner;
    /** frozen_last: the frozen copies a choice has passed over at least once (counted once each). */
    private final List<com.oddlabs.tt.player.@NonNull Player> frozen_deferred = new ArrayList<>();
    private int target_x;
    private int target_y;
    private @Nullable DistanceField target_field;
    private float attack_start;
    private float attack_initial_strength;
    /** worn_basis 1: the attacking army's peak strength since the launch. */
    private float worn_peak;
    /**
     * worn_basis 2: (time, strength) of the attacking army, each strength lower than every one before it, so the first
     * is the peak of the last worn_window seconds.
     */
    private final java.util.ArrayDeque<float @NonNull []> worn_history = new java.util.ArrayDeque<>();
    /** worn_skipped and split_guard_kept are counted once per attack. */
    private boolean worn_skip_counted;
    private boolean split_guard_counted;
    private float muster_start;
    private float next_wave_time;
    private float last_trace;
    private float hold_until = -1f;
    private float last_progress_time;
    private int best_target_dist = Integer.MAX_VALUE;
    private int @NonNull [] hold_spot = new int[2];
    private int last_enemy_d2 = Integer.MAX_VALUE;
    /** hold_closing 1: (time, x, y) of the enemy group the attack last weighed a hold for, over the last 4 s. */
    private final java.util.ArrayDeque<float @NonNull []> hold_groups = new java.util.ArrayDeque<>();
    private float last_hold_skip = -100f;
    private float last_charge_log = -100f;
    /** The enemy each warrior was last sent after with a direct attack order. */
    private final Map<@NonNull Unit, @NonNull Unit> hunt_targets = new LinkedHashMap<>();
    private final Map<@NonNull Unit, Float> dodge_orders = new LinkedHashMap<>();
    private final Map<@NonNull Building, @NonNull Unit> tower_targets = new LinkedHashMap<>();
    private final Map<@NonNull Unit, Float> sapper_orders = new LinkedHashMap<>();
    private float last_pillage_log = -100f;
    /** When each enemy chieftain was last seen casting, judged by our units getting stunned around him. */
    private final Map<@NonNull Unit, Float> enemy_casts = new LinkedHashMap<>();
    /** enemy_first_seen: when each enemy chieftain was first seen on the field. */
    private final Map<@NonNull Unit, Float> enemy_chief_seen = new LinkedHashMap<>();
    private int own_stunned_before;
    /** Seconds an enemy chieftain's spell takes to recharge, as far as the AI assumes. */
    private static final float SPELL_RECHARGE = 40f;
    /** Seconds from the first frame an enemy viking chieftain is seen raising his horn to his stun going off. */
    private static final float STUN_WINDUP = 3.7f;
    /** Reach of the stun in meters, measured from a point this far in front of the chieftain. */
    private static final float STUN_RADIUS = 36f;
    private static final float STUN_OFFSET = 2.57f;

    /** A unit running out of an enemy stun's reach: where to, and until the stun has gone off. */
    private record Dodge(int x, int y, float until) {
    }

    private final Map<@NonNull Unit, @NonNull Dodge> dodges = new LinkedHashMap<>();
    /** Warriors throwing at a winding-up enemy chieftain, until his spell would go off. */
    private final Map<@NonNull Unit, Float> caster_hunters = new LinkedHashMap<>();
    /** When the current siege began, or negative; after a siege gives up, none until siege_cooldown. */
    private float siege_start = -1f;
    private float siege_cooldown = -1f;
    private float blast_play_until = -1f;
    private float last_blast_play = -100f;
    private float siege_progress = -1f;
    private float last_pull_log = -100f;
    /** Which stunned tower each warrior was sent to pull down, so orders are not repeated mid-throw. */
    private final Map<@NonNull Unit, @NonNull Building> siege_assign = new LinkedHashMap<>();
    /** Cells from an enemy tower the sieging army waits at: out of its throws (16 cells). */
    private static final int SIEGE_HOLD = 17;
    /** Cells within which an enemy tower's garrison reaches a warrior: 6 + 8 for the tower + 1.9 for the target. */
    private static final int TOWER_REACH = 16;
    /** Enemy chieftains being hunted while they wind up, until their spell would go off. */
    private final Map<@NonNull Unit, Float> caster_targets = new LinkedHashMap<>();
    /** Chieftains, ours included, seen winding up a spell, and when a stun would go off. */
    private final Map<@NonNull Unit, Float> windups = new LinkedHashMap<>();

    /** A poison fog on the ground or about to be: who cast it, where, when, and whether it turned out to be fog. */
    private static final class Fog {
        final @NonNull Unit caster;
        final float x;
        final float y;
        final float cast;
        boolean confirmed;

        Fog(@NonNull Unit caster, float x, float y, float cast) {
            this.caster = caster;
            this.x = x;
            this.y = y;
            this.cast = cast;
        }
    }

    private final List<@NonNull Fog> fogs = new ArrayList<>();
    /**
     * A native chieftain's poison fog comes down 3.6 s into the wind-up, 26 m around a point just in front of him, and
     * every 2 s for 20 s kills an enemy inside with even odds (his own side's units with a quarter of that).
     */
    private static final float FOG_RELEASE = 3.64f;
    private static final float FOG_TIME = 20f;
    private static final float FOG_RADIUS = 26f;
    private static final float FOG_OFFSET = .9f;
    /** A tower our sappers are raising by a besieged building, until it stands. */
    private @Nullable Building creep_site;
    /** Towers raised by sappers next to enemy buildings. */
    private final List<@NonNull Building> creep_towers = new ArrayList<>();
    private float last_creep_try = -100f;
    /** Multiplies what the next attack must beat, raised by attacks that traded badly. */
    private float attack_caution = 1f;
    private float last_caution_ease;
    private int attack_kills_start;
    private int attack_losses_start;
    private boolean attack_running;
    private final Map<@NonNull Unit, Float> militia_orders = new LinkedHashMap<>();
    private float last_militia_log = -100f;
    private float last_peon_rush = -100f;
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

    /** Enemies bucketed by position for the towers, rebuilt once per tick (or when Intel rebuilds its lists). */
    private @Nullable EnemyIndex enemy_index;
    private float enemy_index_time = -1f;
    private int enemy_index_version = -1;

    private @NonNull EnemyIndex enemyIndex() {
        Intel intel = ai.intel();
        if (enemy_index == null)
            enemy_index = new EnemyIndex(ai.map().getSize());
        if (enemy_index_time != ai.time() || enemy_index_version != intel.version) {
            enemy_index.rebuild(intel.enemy_warriors, intel.enemy_chieftains, intel.enemy_peons);
            enemy_index_time = ai.time();
            enemy_index_version = intel.version;
        }
        return enemy_index;
    }

    Military(@NonNull GauntletAI ai) {
        this.ai = ai;
        staging_x = ai.planner().getStartX();
        staging_y = ai.planner().getStartY();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Queries used by the economy

    int baseThreatLevel() {
        return threat_level;
    }

    /** A warrior's military role in lower case, or null when it has none (for logs: Shepherd's launch lines). */
    @Nullable
    String roleOf(@NonNull Unit u) {
        Role role = roles.get(u);
        return role == null ? null : role.name().toLowerCase(java.util.Locale.ROOT);
    }

    boolean threatNear(int x, int y, int radius) {
        int r2 = radius * radius;
        for (Unit u : threats)
            if (!u.isDead() && MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()) <= r2)
                return true;
        return false;
    }

    /**
     * threatNear for the economy's gathering, deploy, shelter, repair and project tests: with parked_scan_econ, a
     * parked threat counts only within that many cells (Chebyshev, at most radius) of (x, y), as it scans only an
     * 8-cell square and never answers what it does not see.
     */
    boolean threatNearEcon(int x, int y, int radius) {
        int scan = ai.strategy().parked_scan_econ;
        if (scan <= 0)
            return threatNear(x, y, radius);
        int r2 = radius * radius;
        int box = Math.min(radius, scan);
        boolean exempt = false;
        for (Unit u : threats) {
            if (u.isDead() || MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()) > r2)
                continue;
            if (isParked(u) && Math.max(Math.abs(u.getGridX() - x), Math.abs(u.getGridY() - y)) > box) {
                exempt = true;
                continue;
            }
            return true;
        }
        if (exempt)
            ai.aiLog().count("parked_exempt_econ");
        return false;
    }

    /** An enemy standing idle: it sees 8 cells and never answers being hit (IdleController). Check isDead first. */
    private static boolean isParked(@NonNull Unit e) {
        return e.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.IdleController
                && e.getCurrentController() == e.getPrimaryController();
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

    /** Enemy warriors and chieftains within radius cells at full value, the rest at a third. */
    private float enemyFieldStrengthNear(int x, int y, int radius) {
        int r2 = radius * radius;
        float s = 0f;
        for (Unit u : ai.intel().enemy_warriors)
            s += Combat.value(u) * (MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()) <= r2 ? 1f : .3f);
        for (Unit u : ai.intel().enemy_chieftains)
            s += Combat.value(u) * (MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()) <= r2 ? 1f : .3f);
        return s;
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

    private float last_provoke_probe = -10f;

    /**
     * Diagnostic counters only (no orders): every 5 s in the first 15 minutes, our units standing within 15 cells of an
     * enemy quarters or armory, by peon state or warrior role. A Hard copy scans 30 m around its first quarters and
     * armory, and anything of ours there makes it deploy its armory stock and send its peons (AdvancedAI
     * nodeDefendBase), which brings its next wave forward.
     */
    private void provokeProbe() {
        if (ai.time() - last_provoke_probe < 5f || ai.time() > 900f)
            return;
        last_provoke_probe = ai.time();
        Intel intel = ai.intel();
        List<Building> homes = new ArrayList<>(intel.enemy_quarters);
        homes.addAll(intel.enemy_armories);
        if (homes.isEmpty())
            return;
        List<Unit> ours = new ArrayList<>(intel.peons);
        ours.addAll(intel.warriors);
        for (Unit u : ours) {
            if (u.isDead() || u.isMounted())
                continue;
            boolean near = false;
            for (Building b : homes)
                if (!b.isDead() && MapAnalysis.dist2(u.getGridX(), u.getGridY(), b.getGridX(),
                        b.getGridY()) <= 15 * 15) {
                            near = true;
                            break;
                        }
            if (!near)
                continue;
            PeonState state = intel.peon_states.get(u);
            Role role = roles.get(u);
            String what = state != null ? state.name() : role != null ? role.name() : "OTHER";
            ai.aiLog().count("prov_" + what);
            ai.aiLog().count(ai.time() < 480f ? "prov_early" : "prov_mid");
        }
    }

    void tick() {
        watchEnemyCasts();
        updateRoles();
        provokeProbe();
        updateStaging();
        updateThreat();
        manTowers();
        if (threat_level > 0)
            defend();
        if (!raiders.isEmpty())
            answerRaiders();
        if (mode != Mode.ATTACK && siege_start >= 0f)
            endSiege();
        switch (mode) {
            case HOME -> holdStaging();
            case MUSTER -> muster();
            case ATTACK -> attack();
            case RETREAT -> retreat();
        }
        reinforce();
        raid();
        chase();
        peonRush();
        restoreDodge();
        towerFire();
        sapperTick();
        flushOrders();
    }

    void plan() {
        updateRoles();
        recordEnemyStrength();
        easeCaution();
        if (mode == Mode.HOME && threat_level < 2 && ai.strategy().strikes)
            considerStrike();
        // Against many copies the base is rarely quiet: a small raid (next to the whole army) does not hold it back.
        boolean small_threat = threat_level >= 2
                && base_threat_strength < ai.strategy().attack_threat_ratio * (ai.strategy().launch_recheck ? stagingStrength(
                        40) : armyStrength());
        if (mode == Mode.HOME && (threat_level < 2 || small_threat))
            considerAttack();
        // reinforce_threat_ratio: reinforce the attack with the base under threat too, while what stands in the base is
        // worth less than that share of our whole army.
        boolean reinforce_ok = threat_level < 2
                || base_threat_strength < ai.strategy().reinforce_threat_ratio * (armyStrength() + attackStrength());
        if (mode == Mode.ATTACK && reinforce_ok && ai.strategy().reinforce)
            considerReinforcing();
        if (mode == Mode.HOME && threat_level <= ai.strategy().raid_threat)
            considerRaid();
        if (ai.strategy().chief_hunt && countRole(Role.CHASE) == 0 && ai.time() - last_chase_end >= 10f)
            considerChase();
    }

    private void updateRoles() {
        Intel intel = ai.intel();
        for (Iterator<Map.Entry<Unit, Role>> it = roles.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Unit, Role> e = it.next();
            Unit u = e.getKey();
            if (u.isDead() || u.isMounted()) {
                if (u.isDead() && e.getValue() == Role.CHASE)
                    ai.aiLog().count("hunt_lost");
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
                    || (intel.warrior_states.get(u) != WarriorState.ENTER && !front_entry.containsKey(u))) {
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
        raiders.clear();
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
        // parked_scan_threat: a parked enemy is a threat only within its scan of something of ours (Chebyshev).
        int parked_scan = ai.strategy().parked_scan_threat;
        for (Unit e : enemies) {
            if (ai.decoys().caged(e))
                continue;
            int box = parked_scan > 0 && !e.isDead() && isParked(e) ? parked_scan : Integer.MAX_VALUE;
            boolean exempt = false;
            boolean near_base = false;
            for (Building b : own) {
                if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), b.getGridX(), b.getGridY()) <= r2) {
                    if (box != Integer.MAX_VALUE && Math.max(Math.abs(e.getGridX() - b.getGridX()), Math.abs(
                            e.getGridY() - b.getGridY())) > box) {
                        exempt = true;
                        continue;
                    }
                    near_base = true;
                    break;
                }
            }
            boolean near_peons = false;
            if (!near_base) {
                for (Unit p : intel.peons) {
                    PeonState s = intel.peon_states.get(p);
                    if (s == PeonState.GATHER_CHICKEN || s == PeonState.SHEPHERD)
                        continue;
                    if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), p.getGridX(), p.getGridY()) <= 12 * 12) {
                        if (box != Integer.MAX_VALUE && Math.max(Math.abs(e.getGridX() - p.getGridX()), Math.abs(
                                e.getGridY() - p.getGridY())) > box) {
                            exempt = true;
                            continue;
                        }
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
            } else if (exempt) {
                ai.aiLog().count("parked_exempt");
            }
        }
        // Enemy peons tearing down towers count too, and so do peons raiding the base far from any building of
        // theirs: a band of starting peons can kill every builder of an armory that is not up yet.
        for (Unit e : intel.enemy_peons) {
            boolean hostile = false;
            for (Building t : intel.towers) {
                if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), t.getGridX(), t.getGridY()) <= 5 * 5) {
                    hostile = true;
                    break;
                }
            }
            if (hostile) {
                threats.add(e);
                at_base.add(e);
            } else if (ai.strategy().peon_militia && ai.time() < ai.strategy().militia_time
                    && raiding(e, own, r2)) {
                        raiders.add(e);
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
        // Each threat's cell, value and base flag once (the pairwise loop used to recompute them per pair and scan
        // at_base for every pair); the sums run in the same order, so the result is the same.
        int nt = threats.size();
        int[] txs = new int[nt];
        int[] tys = new int[nt];
        float[] vals = new float[nt];
        boolean[] in_base = new boolean[nt];
        java.util.Set<Unit> base_set = new java.util.LinkedHashSet<>(at_base);
        for (int i = 0; i < nt; i++) {
            Unit e = threats.get(i);
            txs[i] = e.getGridX();
            tys[i] = e.getGridY();
            vals[i] = Math.max(.2f, Combat.value(e));
            in_base[i] = base_set.contains(e);
        }
        for (int i = 0; i < nt; i++) {
            long sx = 0;
            long sy = 0;
            int n = 0;
            float strength = 0f;
            boolean base = false;
            for (int j = 0; j < nt; j++) {
                if (MapAnalysis.dist2(txs[i], tys[i], txs[j], tys[j]) <= 15 * 15) {
                    sx += txs[j];
                    sy += tys[j];
                    n++;
                    strength += vals[j];
                    base |= in_base[j];
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
                && base_threat_strength > ai.strategy().recall_ratio * attackStrength()) {
            ai.log(String.format("calling the army home: %.1f in the base against %.1f", base_threat_strength, home));
            recalled = true;
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
        // Strict shred never stuns: its charge is for the blast.
        boolean stable = ai.strategy().defend_stable;
        boolean toot_ready = chief != null && ai.chieftain().stunReady()
                && !(ai.strategy().shred && ai.strategy().shred_strict);
        if (stable && toot_ready && MapAnalysis.dist2(chief.getGridX(), chief.getGridY(), threat_x,
                threat_y) > 30 * 30) {
            toot_ready = false;
            ai.aiLog().count("stun_credit_denied");
        }
        float effective = ours + towers + (toot_ready ? .6f * threat_strength : 0f);
        // Against a single enemy the army behind his raiders is his whole army; against several the base is busy
        // enough without waiting for them.
        float behind = ai.strategy().threat_look > 0 && ai.enemiesAlive() == 1 ? enemyStrengthNear(threat_x,
                threat_y, ai.strategy().threat_look) : 0f;
        float enemy_raw = Math.max(threat_strength, behind);
        float enemy = enemy_raw * (enemyStunReadyNear(threat_x, threat_y, 25) ? ai.strategy().enemy_stun_mult : 1f);
        float engage_ratio = .8f - engage_state * ai.strategy().defend_hysteresis;
        int armory_cells = stable && engage_state == 1 ? 17 : 14;
        boolean at_armory = armory != null && MapAnalysis.dist2(threat_x, threat_y, armory.getGridX(),
                armory.getGridY()) <= armory_cells * armory_cells;
        boolean engage = effective >= engage_ratio * enemy || at_armory;
        if (stable) {
            if (engage && engage_state != 1)
                engage_since = ai.time();
            else if (!engage && engage_state == 1 && ai.time() - engage_since < 3f) {
                engage = true;
                ai.aiLog().count("engage_held");
            }
        }
        // Counter only: the defenders fall back where, without the stun fear, they would have engaged.
        if (!engage && engage_state != -1 && effective >= engage_ratio * enemy_raw)
            ai.aiLog().count("stun_fear_fallback");
        if (threat_level != last_logged_threat || engage != last_logged_engage) {
            last_logged_threat = threat_level;
            last_logged_engage = engage;
            ai.log(String.format("threat %d: %.1f at %d,%d; defenders %.1f towers %.1f -> %s", threat_level,
                    threat_strength, threat_x, threat_y, ours, towers, engage ? "engage" : "fall back"));
        }
        engage_state = engage ? 1 : -1;
        if (ai.strategy().defend_exploit_stun && engage && chargeStunned(defenders, threat_x, threat_y)) {
            evacuatePeons();
            return;
        }
        if (blastDefense(defenders, enemy)) {
            evacuatePeons();
            return;
        }
        float hold = ai.strategy().hold_ratio;
        // Against several enemies attacks come from every side, and a post would leave the rest of the base open.
        if (engage && hold > 0f && (ai.enemiesAlive() == 1 || ai.strategy().hold_multi)
                && enemy >= hold * Math.max(1f, ours)
                && holdAtPost(defenders)) {
            if (ai.enemiesAlive() != 1)
                ai.aiLog().count("hold_post_multi");
            evacuatePeons();
            return;
        }
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

    /**
     * Meets a strong threat at the building of ours nearest to it, a tower if one is close: defenders gather there and
     * fight what comes within reach of it rather than walk out to the enemy. Returns false without a post.
     */
    private boolean holdAtPost(@NonNull List<@NonNull Unit> defenders) {
        Intel intel = ai.intel();
        Building post = null;
        int best = Integer.MAX_VALUE;
        List<Building> own = new ArrayList<>(intel.towers);
        own.addAll(intel.armories);
        own.addAll(intel.quarters);
        for (Building b : own) {
            int d = MapAnalysis.dist2(b.getGridX(), b.getGridY(), threat_x, threat_y);
            // Towers count as a little nearer: fighting under one is worth a short walk.
            if (b.getTemplate().getTemplateID() == com.oddlabs.tt.model.Race.BUILDING_TOWER && Intel.isTowerActive(b))
                d -= 10 * 10;
            if (d < best) {
                best = d;
                post = b;
            }
        }
        if (post == null)
            return false;
        int px = post.getGridX();
        int py = post.getGridY();
        List<Unit> close = new ArrayList<>();
        for (Unit e : threats)
            if (!e.isDead() && MapAnalysis.dist2(e.getGridX(), e.getGridY(), px, py) <= 14 * 14)
                close.add(e);
        List<Unit> at_post = new ArrayList<>();
        for (Unit u : defenders) {
            if (roles.get(u) == Role.RAID)
                roles.put(u, Role.ARMY);
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), px, py) <= 12 * 12)
                at_post.add(u);
            else
                attackGround(u, px, py, false);
        }
        if (!close.isEmpty())
            engageSpread(at_post, close, px, py, false);
        huntChieftains(at_post);
        return true;
    }

    private float towersStrength() {
        float s = 0f;
        for (Building t : ai.intel().towers)
            s += Combat.towerValue(t);
        return s;
    }

    /** Whether an enemy chieftain with his spell ready stands within radius cells (enemyThreatReady). */
    boolean enemyStunReadyNear(int x, int y, int radius) {
        for (Unit c : ai.intel().enemy_chieftains) {
            if (c.isDead() || Intel.isStunned(c))
                continue;
            if (MapAnalysis.dist2(x, y, c.getGridX(), c.getGridY()) > radius * radius)
                continue;
            if (enemyThreatReady(c))
                return true;
        }
        return false;
    }

    /**
     * Whether an enemy chieftain counts as ready to stun in the army's fear of his spell: enemy_spell_recharge s after
     * he was seen casting, and with enemy_first_seen, never seen casting, that long after he was first seen (a newborn
     * chieftain has no charge). Our own stun timing keeps enemySpellReady.
     */
    private boolean enemyThreatReady(@NonNull Unit chief) {
        if (ai.strategy().hidden_info)
            return enemySpellReady(chief);
        Float cast = enemy_casts.get(chief);
        if (cast != null)
            return ai.time() - cast >= ai.strategy().enemy_spell_recharge;
        if (!ai.strategy().enemy_first_seen)
            return true;
        Float seen = enemy_chief_seen.get(chief);
        return seen == null || ai.time() - seen >= ai.strategy().enemy_spell_recharge;
    }

    /**
     * Whether an enemy chieftain may have his spell ready. A human cannot see the recharge, so unless hidden_info is
     * set the AI assumes it is ready unless he was seen casting within the recharge time.
     */
    boolean enemySpellReady(@NonNull Unit chief) {
        if (ai.strategy().hidden_info)
            return chief.getMagicProgress(0) >= 1f || chief.getMagicProgress(1) >= 1f;
        Float cast = enemy_casts.get(chief);
        return cast == null || ai.time() - cast >= SPELL_RECHARGE;
    }

    /**
     * Notes enemy casts from what a player sees: a chieftain casting, or several of our units stunned at once next to
     * one.
     */
    private void watchEnemyCasts() {
        Intel intel = ai.intel();
        enemy_casts.keySet().removeIf(Unit::isDead);
        if (ai.strategy().enemy_first_seen) {
            enemy_chief_seen.keySet().removeIf(Unit::isDead);
            for (Unit e : intel.enemy_chieftains)
                if (!e.isDead())
                    enemy_chief_seen.putIfAbsent(e, ai.time());
        }
        // Casting is plain to see: the chieftain stops and blows his horn.
        for (Unit e : intel.enemy_chieftains)
            if (!e.isDead() && e.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.MagicController)
                enemy_casts.put(e, ai.time());
        List<Unit> stunned = new ArrayList<>();
        for (Unit u : intel.warriors)
            if (Intel.isStunned(u))
                stunned.add(u);
        for (Unit u : intel.peons)
            if (Intel.isStunned(u))
                stunned.add(u);
        if (stunned.size() >= own_stunned_before + 2) {
            int[] c = centroid(stunned);
            Unit caster = null;
            int best = 25 * 25;
            for (Unit e : intel.enemy_chieftains) {
                int d = MapAnalysis.dist2(e.getGridX(), e.getGridY(), c[0], c[1]);
                if (!e.isDead() && d <= best) {
                    best = d;
                    caster = e;
                }
            }
            if (caster != null) {
                enemy_casts.put(caster, ai.time());
                ai.log("enemy stun caught " + (stunned.size() - own_stunned_before) + " of our units");
            }
        }
        own_stunned_before = stunned.size();
    }

    /** Whether a unit is running out of reach of an enemy stun; other orders leave it alone meanwhile. */
    boolean isDodging(@NonNull Unit u) {
        return dodges.containsKey(u);
    }

    /**
     * Keeps our units out of the chieftains' spells, as a player watching the fight would. Called every frame, as the
     * first tenths of a second decide who makes it.
     */
    void dodgeSpells() {
        float now = ai.time();
        dodges.values().removeIf(d -> d.until() < now);
        // The horn stays up for a while after the spell: a wind-up is over once he stops casting.
        windups.entrySet().removeIf(w -> w.getValue() < now && (w.getKey().isDead()
                || !(w.getKey().getCurrentController() instanceof com.oddlabs.tt.model.behaviour.MagicController)));
        updateFogs(now);
        Intel intel = ai.intel();
        com.oddlabs.tt.model.Race vikings = ai.owner().getWorld().getRacesResources().getRace(
                com.oddlabs.tt.model.RacesResources.RACE_VIKINGS);
        List<Unit> casters = new ArrayList<>(intel.enemy_chieftains);
        if (intel.chieftain != null)
            casters.add(intel.chieftain);
        for (Unit e : casters) {
            if (e.isDead() || windups.containsKey(e)
                    || !(e.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.MagicController))
                continue;
            windups.put(e, now + STUN_WINDUP);
            if (e.getOwner().getRace() != vikings) {
                // Fog and lightning look alike until they come down: clear out at once, the fog gives 5.6 s.
                if (ai.strategy().dodge_fog)
                    fogs.add(new Fog(e, e.getPositionX() + FOG_OFFSET * e.getDirectionX(),
                            e.getPositionY() + FOG_OFFSET * e.getDirectionY(), now));
            } else if (e != intel.chieftain && ai.strategy().dodge_stun) {
                // With the stun cancel (Reflexes) a stun costs nothing: only the sonic blast, which hits the same
                // 36 m, is worth running from. The spell's index is set on the tick the horn is raised.
                boolean blast = e.getLastMagicIndex() == com.oddlabs.tt.model.RacesResources.INDEX_MAGIC_BLAST;
                if (blast)
                    ai.aiLog().count("enemy_blast");
                if (!ai.strategy().dodge_blast_only || !ai.strategy().stun_cancel || blast)
                    dodgeStun(e, now);
            }
        }
        for (Fog f : fogs)
            keepOutOf(f, now);
        keepHuntingCasters(now);
        // Whatever else ordered them this frame, keep them running until they are clear.
        for (Map.Entry<Unit, Dodge> en : dodges.entrySet()) {
            Unit u = en.getKey();
            Dodge d = en.getValue();
            if (u.isDead() || u.isMounted() || Intel.isStunned(u)
                    || MapAnalysis.dist2(u.getGridX(), u.getGridY(), d.x(), d.y()) <= 2 * 2)
                continue;
            if (u.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.WalkController w
                    && MapAnalysis.dist2(w.getTarget().getGridX(), w.getTarget().getGridY(), d.x(), d.y()) <= 3 * 3)
                continue;
            ai.landscapeOrder(Selectable.newArray(u), d.x(), d.y(), Action.MOVE, false);
        }
    }

    /** Drops fogs that lifted, or that turned out to be lightning or never came down. */
    private void updateFogs(float now) {
        for (Iterator<Fog> it = fogs.iterator(); it.hasNext();) {
            Fog f = it.next();
            if (now > f.cast + FOG_RELEASE + FOG_TIME + .5f) {
                it.remove();
            } else if (!f.confirmed && now >= f.cast + FOG_RELEASE + .1f) {
                // By now it is plain to see which spell came down.
                if (!f.caster.isDead()
                        && f.caster.getLastMagicIndex() == com.oddlabs.tt.model.RacesResources.INDEX_MAGIC_POISON) {
                    f.confirmed = true;
                    ai.log("poison fog down at " + com.oddlabs.tt.pathfinder.UnitGrid.toGridCoordinate(
                            f.x) + "," + com.oddlabs.tt.pathfinder.UnitGrid.toGridCoordinate(
                                    f.y) + (f.caster.getOwner() == ai.owner() ? " (ours)" : ""));
                } else {
                    it.remove();
                }
            } else if (!f.confirmed && f.caster.isDead()) {
                it.remove();
            }
        }
    }

    /** Sends our units inside a fog, or about to walk in, out of it. The caster's own chieftain is immune. */
    private void keepOutOf(@NonNull Fog f, float now) {
        Intel intel = ai.intel();
        List<Unit> units = new ArrayList<>(intel.warriors);
        units.addAll(intel.peons);
        if (intel.chieftain != null && f.caster != intel.chieftain)
            units.add(intel.chieftain);
        int grid = ai.map().getSize();
        float reach = FOG_RADIUS + 3f;
        float until = Math.min(now + 4f, f.cast + FOG_RELEASE + FOG_TIME);
        for (Unit u : units) {
            if (u.isDead() || u.isMounted() || Intel.isStunned(u) || dodges.containsKey(u)
                    || u.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.MagicController)
                continue;
            float dx = u.getPositionX() - f.x;
            float dy = u.getPositionY() - f.y;
            if (dx * dx + dy * dy > reach * reach)
                continue;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 1f) {
                dx = staging_x * 2f - f.x;
                dy = staging_y * 2f - f.y;
                d = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
            }
            float to = (FOG_RADIUS + 7f) / d;
            int tx = Math.clamp(com.oddlabs.tt.pathfinder.UnitGrid.toGridCoordinate(f.x + dx * to), 0, grid - 1);
            int ty = Math.clamp(com.oddlabs.tt.pathfinder.UnitGrid.toGridCoordinate(f.y + dy * to), 0, grid - 1);
            dodges.put(u, new Dodge(tx, ty, until));
            ai.landscapeOrder(Selectable.newArray(u), tx, ty, Action.MOVE, false);
        }
    }

    /**
     * Runs our units out of reach of an enemy viking chieftain winding up his stun. It goes off 3.8 s after he raises
     * his horn and freezes, defenseless for 10 s or more, whatever was within 36 m of him 1.6 s earlier and still is;
     * the units near the edge have time to walk out, and those just outside must not walk in.
     */
    private void dodgeStun(@NonNull Unit e, float now) {
        Intel intel = ai.intel();
        float release = now + STUN_WINDUP;
        float sx = e.getPositionX() + STUN_OFFSET * e.getDirectionX();
        float sy = e.getPositionY() + STUN_OFFSET * e.getDirectionY();
        List<Unit> units = new ArrayList<>(intel.warriors);
        units.addAll(intel.peons);
        if (intel.chieftain != null
                && !(intel.chieftain.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.MagicController))
            units.add(intel.chieftain);
        int grid = ai.map().getSize();
        int n = 0;
        int hunters = 0;
        for (Unit u : units) {
            if (u.isDead() || u.isMounted() || Intel.isStunned(u))
                continue;
            float dx = u.getPositionX() - sx;
            float dy = u.getPositionY() - sy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > STUN_RADIUS + 10f)
                continue;
            // Out by the time it goes off, with a little to spare for the path around others.
            float out = STUN_RADIUS + 3f - d;
            if (out > .85f * u.getTemplate().getMetersPerSecond() * STUN_WINDUP) {
                // Caught either way: throw at him instead, his spell dies with him.
                if (ai.strategy().hunt_caster && intel.warriors.contains(u)
                        && MapAnalysis.dist2(u.getGridX(), u.getGridY(), e.getGridX(),
                                e.getGridY()) <= ai.strategy().hunt_caster_cells * ai.strategy().hunt_caster_cells) {
                    caster_hunters.put(u, release);
                    huntCaster(u, e);
                    hunters++;
                }
                continue;
            }
            float to = Math.max(d, STUN_RADIUS + 6f) / Math.max(d, .1f);
            int tx = Math.clamp(com.oddlabs.tt.pathfinder.UnitGrid.toGridCoordinate(sx + dx * to), 0, grid - 1);
            int ty = Math.clamp(com.oddlabs.tt.pathfinder.UnitGrid.toGridCoordinate(sy + dy * to), 0, grid - 1);
            dodges.put(u, new Dodge(tx, ty, release + .3f));
            ai.landscapeOrder(Selectable.newArray(u), tx, ty, Action.MOVE, false);
            n++;
        }
        if (hunters > 0)
            caster_targets.put(e, release);
        if (n > 0 || hunters > 0)
            ai.log("enemy chieftain winds up at " + e.getGridX() + "," + e.getGridY() + ": " + n + " units run out of reach, " + hunters + " go for him");
    }

    private void huntCaster(@NonNull Unit u, @NonNull Unit chief) {
        hunt_targets.put(u, chief);
        last_order.put(u, ai.time());
        ai.intel().warrior_states.put(u, WarriorState.FIGHT);
        ai.owner().setTarget(Selectable.newArray(u), chief, Action.ATTACK, true);
    }

    /** Keeps the warriors on a winding-up enemy chieftain until his spell goes off or he falls. */
    private void keepHuntingCasters(float now) {
        caster_hunters.values().removeIf(t -> t < now);
        caster_targets.entrySet().removeIf(c -> c.getValue() < now || c.getKey().isDead());
        for (Map.Entry<Unit, Float> en : caster_hunters.entrySet()) {
            Unit u = en.getKey();
            Unit chief = hunt_targets.get(u);
            if (u.isDead() || Intel.isStunned(u))
                continue;
            if (chief == null || !caster_targets.containsKey(chief)) {
                // Another order took him off the caster: put him back on the nearest one.
                chief = null;
                for (Unit c : caster_targets.keySet())
                    if (chief == null || MapAnalysis.dist2(u.getGridX(), u.getGridY(), c.getGridX(),
                            c.getGridY()) < MapAnalysis.dist2(u.getGridX(), u.getGridY(), chief.getGridX(),
                                    chief.getGridY()))
                        chief = c;
                if (chief != null)
                    huntCaster(u, chief);
            } else if (!(u.getCurrentController() instanceof HuntController)) {
                huntCaster(u, chief);
            }
        }
    }

    /**
     * An enemy chieftain close to our warriors is worth going straight for: his stun wins fights, and he dies to a
     * handful of axes. The nearest warriors hunt him down while the rest keep attacking the ground.
     */
    private void huntChieftains(@NonNull List<@NonNull Unit> units) {
        for (Unit chief : ai.intel().enemy_chieftains) {
            if (chief.isDead())
                continue;
            // Thirty hits bring down a healthy chieftain: only a wounded or helpless one is worth a squad.
            if (ai.strategy().chief_per_hit && chief.getHitPoints() > 20 && !Intel.isStunned(chief))
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
    /**
     * An enemy peon inside our base and far from every building of his side outside it; a tower he is putting up in
     * our base is no home of his.
     */
    private boolean raiding(@NonNull Unit e, @NonNull List<@NonNull Building> own, int r2) {
        if (!nearAny(own, e.getGridX(), e.getGridY(), r2))
            return false;
        for (Building b : ai.intel().enemy_buildings) {
            if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), b.getGridX(), b.getGridY()) > 30 * 30)
                continue;
            if (!nearAny(own, b.getGridX(), b.getGridY(), r2))
                return false;
        }
        return true;
    }

    private static boolean nearAny(@NonNull List<@NonNull Building> buildings, int x, int y, int r2) {
        for (Building b : buildings)
            if (MapAnalysis.dist2(x, y, b.getGridX(), b.getGridY()) <= r2)
                return true;
        return false;
    }

    /**
     * Raiding enemy peons with no warriors of ours about to stop them: our peons nearby gang up on them when they
     * outnumber them, instead of dying one by one at their building sites.
     */
    private void peonMilitia() {
        Intel intel = ai.intel();
        militia_orders.keySet().removeIf(Unit::isDead);
        int[] c = centroid(raiders);
        // Peons are no match for warriors: with enemy warriors about, they shelter instead.
        if (enemyStrengthNear(c[0], c[1], 20) > 1f)
            return;
        float guard = 0f;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            Unit u = e.getKey();
            if (e.getValue() != Role.TOWER && MapAnalysis.dist2(u.getGridX(), u.getGridY(), c[0], c[1]) <= 30 * 30)
                guard += Combat.value(u);
        }
        if (guard >= .4f * raiders.size())
            return;
        List<Unit> militia = new ArrayList<>();
        for (Unit p : intel.peons) {
            PeonState s = intel.peon_states.get(p);
            if (s == PeonState.TRANSIT || s == PeonState.STUNNED || s == PeonState.SAPPER || s == PeonState.SHEPHERD)
                continue;
            if (MapAnalysis.dist2(p.getGridX(), p.getGridY(), c[0], c[1]) <= 30 * 30)
                militia.add(p);
        }
        if (militia.size() < 1.2f * raiders.size()) {
            // Too few to fight: the ones they are on go inside.
            for (Unit p : militia) {
                boolean close = false;
                for (Unit e : raiders)
                    close |= MapAnalysis.dist2(p.getGridX(), p.getGridY(), e.getGridX(), e.getGridY()) <= 8 * 8;
                Building shelter = close ? nearest(intel.quarters, p) : null;
                if (shelter != null && shelter.getUnitContainer() != null)
                    ai.owner().setTarget(Selectable.newArray(p), shelter, Action.DEFAULT, false);
            }
            return;
        }
        if (ai.time() - last_militia_log > 15f) {
            last_militia_log = ai.time();
            ai.log(militia.size() + " peons fight off " + raiders.size() + " raiding peons at " + c[0] + "," + c[1]);
        }
        for (Unit p : militia) {
            intel.peon_states.put(p, PeonState.FIGHT);
            Float last = militia_orders.get(p);
            if (last != null && ai.time() - last < 3f)
                continue;
            Unit target = null;
            int best = Integer.MAX_VALUE;
            for (Unit e : raiders) {
                int d = MapAnalysis.dist2(p.getGridX(), p.getGridY(), e.getGridX(), e.getGridY());
                if (d < best) {
                    best = d;
                    target = e;
                }
            }
            if (target == null)
                continue;
            militia_orders.put(p, ai.time());
            ai.owner().setTarget(Selectable.newArray(p), target, Action.ATTACK, true);
        }
    }

    /**
     * Enemy peons raiding the base: a few warriors from home run them down when there are any, otherwise our peons
     * fight them off or take cover.
     */
    private void answerRaiders() {
        int[] c = centroid(raiders);
        if (threat_level == 0) {
            List<Unit> home = new ArrayList<>();
            for (Map.Entry<Unit, Role> e : roles.entrySet())
                if (e.getValue() == Role.ARMY && ai.intel().warrior_states.get(e.getKey()) != WarriorState.STUNNED)
                    home.add(e.getKey());
            home.sort((a, b) -> Integer.compare(MapAnalysis.dist2(a.getGridX(), a.getGridY(), c[0], c[1]),
                    MapAnalysis.dist2(b.getGridX(), b.getGridY(), c[0], c[1])));
            float needed = .4f * raiders.size() + 1f;
            float sent = 0f;
            int n = 0;
            while (n < home.size() && sent < needed)
                sent += Combat.value(home.get(n++));
            if (sent >= needed) {
                engageSpread(home.subList(0, n), raiders, c[0], c[1], false);
                return;
            }
        }
        peonMilitia();
    }

    private static @Nullable Building nearest(@NonNull List<@NonNull Building> buildings, @NonNull Unit u) {
        Building best = null;
        int best_d = Integer.MAX_VALUE;
        for (Building b : buildings) {
            int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), b.getGridX(), b.getGridY());
            if (d < best_d) {
                best_d = d;
                best = b;
            }
        }
        return best;
    }

    /** Sparring only: the starting peons go for the enemy's peons. */
    private void peonRush() {
        if (!ai.strategy().peon_rush || ai.time() > 300f || ai.time() - last_peon_rush < 4f)
            return;
        last_peon_rush = ai.time();
        Intel intel = ai.intel();
        for (Unit p : intel.peons) {
            Unit target = null;
            int best = Integer.MAX_VALUE;
            for (Unit e : intel.enemy_peons) {
                int d = MapAnalysis.dist2(p.getGridX(), p.getGridY(), e.getGridX(), e.getGridY());
                if (d < best) {
                    best = d;
                    target = e;
                }
            }
            if (target != null) {
                intel.peon_states.put(p, PeonState.FIGHT);
                ai.owner().setTarget(Selectable.newArray(p), target, Action.ATTACK, true);
            }
        }
    }

    private void evacuatePeons() {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        List<Unit> evacuate = new ArrayList<>();
        for (Unit p : intel.peons) {
            PeonState s = intel.peon_states.get(p);
            if (s == PeonState.TRANSIT || s == PeonState.STUNNED || s == PeonState.SAPPER || s == PeonState.SHEPHERD)
                continue;
            // Peons sent to fight off raiding peons stay in the fight.
            Float militia = militia_orders.get(p);
            if (militia != null && ai.time() - militia < 5f)
                continue;
            if (!threatNear(p.getGridX(), p.getGridY(), 11))
                continue;
            evacuate.add(p);
        }
        // bank_guard: a main armory at its cap shelters peons only when no quarters can (Economy.guardBank).
        boolean bank_full = !evacuate.isEmpty() && ai.economy().bankFull(armory);
        for (Unit p : evacuate) {
            Building shelter = null;
            int best = Integer.MAX_VALUE;
            if (armory != null && !bank_full && !threatNear(armory.getGridX(), armory.getGridY(), 6))
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
            if (shelter == null && bank_full && armory != null && !threatNear(armory.getGridX(), armory.getGridY(), 6))
                shelter = armory; // no quarters qualifies: as without bank_guard
            else if (bank_full && shelter != null)
                ai.aiLog().count("bank_shelter_quarters");
            if (shelter != null && shelter.getUnitContainer() != null)
                ai.owner().setTarget(Selectable.newArray(p), shelter, Action.DEFAULT, false);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Towers

    /** tower_front_entry: gunners walking to the threat side of their tower before they enter, {x, y, since}. */
    private final Map<@NonNull Unit, float @NonNull []> front_entry = new LinkedHashMap<>();

    /**
     * A garrison throws from the cell it entered by, two cells from the tower's centre, so its reach runs 17-18.4
     * cells towards that side and 12.7-13 away from it. The gunner first walks to a cell three beyond the tower on the
     * side of the nearest enemies (else of the nearest enemy start), then enters from there. Null when there is no
     * safe such cell.
     */
    private int @Nullable [] frontCell(@NonNull Building tower) {
        Intel intel = ai.intel();
        int tx = tower.getGridX();
        int ty = tower.getGridY();
        long ex = 0;
        long ey = 0;
        int n = 0;
        for (Unit e : intel.enemy_warriors)
            if (!e.isDead() && MapAnalysis.dist2(tx, ty, e.getGridX(), e.getGridY()) <= 45 * 45) {
                ex += e.getGridX();
                ey += e.getGridY();
                n++;
            }
        float dx;
        float dy;
        float[] live = n == 0 && ai.strategy().tower_face_live ? liveFacing(tx, ty) : null;
        if (n > 0) {
            dx = ex / (float) n - tx;
            dy = ey / (float) n - ty;
        } else if (live != null) {
            dx = live[0];
            dy = live[1];
            ai.aiLog().count("tower_face_live");
        } else {
            dx = ai.planner().getEnemyX() - tx;
            dy = ai.planner().getEnemyY() - ty;
            ai.aiLog().count("tower_face_fallback");
        }
        double base = Math.atan2(dy, dx);
        for (double turn : new double[]{0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2}) {
            int fx = tx + (int) Math.round(3 * Math.cos(base + turn));
            int fy = ty + (int) Math.round(3 * Math.sin(base + turn));
            if (!ai.map().passable(fx, fy))
                continue;
            boolean hot = false;
            for (Unit e : intel.enemy_warriors)
                if (!e.isDead() && MapAnalysis.dist2(fx, fy, e.getGridX(), e.getGridY()) <= 10 * 10) {
                    hot = true;
                    break;
                }
            return hot ? null : new int[]{fx, fy};
        }
        return null;
    }

    /**
     * tower_face_live: the unit vector from the tower towards the living copies' mean start plus the unit vector from
     * our core (the centre of our finished quarters and armories, else our start) out to the tower. Where the two
     * nearly cancel (towers behind the core) only the first; null when that is degenerate too (old fallback).
     */
    private float @Nullable [] liveFacing(int tx, int ty) {
        float[] live = ai.liveEnemyCenter();
        if (live == null)
            return null;
        float ux = live[0] - tx;
        float uy = live[1] - ty;
        float nu = (float) Math.sqrt(ux * ux + uy * uy);
        if (nu < 1e-3f)
            return null;
        ux /= nu;
        uy /= nu;
        Intel intel = ai.intel();
        long cx = 0;
        long cy = 0;
        int n = 0;
        for (Building b : intel.quarters) {
            cx += b.getGridX();
            cy += b.getGridY();
            n++;
        }
        for (Building b : intel.armories) {
            cx += b.getGridX();
            cy += b.getGridY();
            n++;
        }
        float bx = n > 0 ? cx / (float) n : ai.planner().getStartX();
        float by = n > 0 ? cy / (float) n : ai.planner().getStartY();
        float wx = tx - bx;
        float wy = ty - by;
        float nw = (float) Math.sqrt(wx * wx + wy * wy);
        float dx = ux + (nw > 0f ? wx / nw : 0f);
        float dy = uy + (nw > 0f ? wy / nw : 0f);
        if (Math.sqrt(dx * dx + dy * dy) < .5f)
            return new float[]{ux, uy};
        return new float[]{dx, dy};
    }

    /** tower_reaim: when each tower last re-aimed. */
    private final Map<@NonNull Building, Float> reaimed = new LinkedHashMap<>();
    private float last_reaim = -10f;

    /**
     * tower_reaim, every 2 s: a manned tower with no awake enemy within 14 cells and nothing in its reach, but idle
     * enemies (which never answer being hit) that one of the 16 cells around it would reach and its entry cell does not
     * (at least 3 more), is re-entered from that cell: the garrison throws from the cell it entered by, so its reach
     * disc moves 2-2.8 cells that way. Once per tower per 30 s.
     */
    private void reaimTowers() {
        if (!ai.strategy().tower_reaim || ai.time() - last_reaim < 2f)
            return;
        last_reaim = ai.time();
        Intel intel = ai.intel();
        List<Unit> idle = new ArrayList<>();
        List<Unit> awake = new ArrayList<>();
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead())
                continue;
            boolean parked = e.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.IdleController
                    && e.getCurrentController() == e.getPrimaryController();
            (parked ? idle : awake).add(e);
        }
        if (idle.isEmpty())
            return;
        for (Building t : intel.towers) {
            if (t.isDead() || !t.isComplete() || t.getUnitContainer() == null || t.getUnitCount() == 0
                    || tower_assignments.containsValue(t))
                continue;
            Float last = reaimed.get(t);
            if (last != null && ai.time() - last < 30f)
                continue;
            Unit gunner = ((com.oddlabs.tt.model.MountUnitContainer) t.getUnitContainer()).getUnit();
            if (gunner == null || gunner.isDead() || Intel.isStunned(gunner))
                continue;
            int tx = t.getGridX();
            int ty = t.getGridY();
            boolean quiet = true;
            for (Unit e : awake)
                if (MapAnalysis.dist2(tx, ty, e.getGridX(), e.getGridY()) <= 14 * 14) {
                    quiet = false;
                    break;
                }
            if (!quiet)
                continue;
            int gx = gunner.getGridX();
            int gy = gunner.getGridY();
            int now = 0;
            for (Unit e : idle)
                if (MapAnalysis.dist2(gx, gy, e.getGridX(), e.getGridY()) <= 252)
                    now++;
            if (now > 0)
                continue; // it has something to throw at already
            int[] best = null;
            int best_n = 0;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != 2)
                        continue;
                    int cx = tx + dx;
                    int cy = ty + dy;
                    if (!ai.map().passable(cx, cy))
                        continue;
                    int n = 0;
                    for (Unit e : idle)
                        if (MapAnalysis.dist2(cx, cy, e.getGridX(), e.getGridY()) <= 252)
                            n++;
                    if (n > best_n) {
                        best_n = n;
                        best = new int[]{cx, cy};
                    }
                }
            if (best == null || best_n < 3)
                continue;
            boolean safe = true;
            for (Unit e : awake)
                if (MapAnalysis.dist2(best[0], best[1], e.getGridX(), e.getGridY()) <= 12 * 12) {
                    safe = false;
                    break;
                }
            if (!safe)
                continue;
            reaimed.put(t, ai.time());
            ai.owner().exitTower(t);
            if (gunner.isDead() || gunner.isMounted())
                continue;
            roles.put(gunner, Role.TOWER);
            tower_assignments.put(gunner, t);
            front_entry.put(gunner, new float[]{best[0], best[1], ai.time() - 6f}); // 6 s to get there
            ai.landscapeOrder(Selectable.newArray(gunner), best[0], best[1], Action.MOVE, false);
            ai.aiLog().count("tower_reaim");
            for (int i = 0; i < best_n; i++)
                ai.aiLog().count("reaim_gain");
        }
    }

    private void manTowers() {
        Intel intel = ai.intel();
        reaimTowers();
        // Gunners at (or long on the way to) their front cell go in now.
        for (java.util.Iterator<Map.Entry<Unit, float[]>> it = front_entry.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Unit, float[]> e = it.next();
            Unit u = e.getKey();
            Building t = tower_assignments.get(u);
            if (u.isDead() || t == null || t.isDead() || Intel.isTowerManned(t)) {
                it.remove();
                continue;
            }
            float[] f = e.getValue();
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), (int) f[0], (int) f[1]) <= 2 * 2
                    || ai.time() - f[2] > 12f) {
                it.remove();
                ai.owner().setTarget(Selectable.newArray(u), t, Action.DEFAULT, false);
            }
        }
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
                int[] front = ai.strategy().tower_front_entry ? frontCell(tower) : null;
                if (front != null) {
                    front_entry.put(best, new float[]{front[0], front[1], ai.time()});
                    ai.landscapeOrder(Selectable.newArray(best), front[0], front[1], Action.MOVE, false);
                    ai.aiLog().count("tower_front_entry");
                } else
                    ai.owner().setTarget(Selectable.newArray(best), tower, Action.DEFAULT, false);
            }
        }
        // Swap iron warriors in towers for chicken warriors when the base is quiet (chicken_gunners: whenever the
        // tower itself has no enemy within 20 cells; a rubber axe bounces on to a neighbour with the same hit roll).
        boolean chickens = ai.strategy().chicken_gunners;
        if (threat_level > 0 && !chickens)
            return;
        for (Building tower : intel.towers) {
            if (!Intel.isTowerManned(tower))
                continue;
            Unit inside = ((com.oddlabs.tt.model.MountUnitContainer) tower.getUnitContainer()).getUnit();
            if (inside == null || Intel.warriorType(inside) == WarriorType.CHICKEN)
                continue;
            if (enemyStrengthNear(tower.getGridX(), tower.getGridY(), chickens ? 20 : 30) > 0)
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
        return mode == Mode.HOME && threat_level <= ai.strategy().forward_threat
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
        // Defenders waiting for an attacker win even fights about three times in four. With several enemies, only
        // the warriors within reach of the spot come to its defense in time; the rest count for a little.
        float s = 1.1f * (ai.enemiesAlive() > 1 ? enemyFieldStrengthNear(x, y,
                ai.strategy().defense_radius) : enemyFieldStrength());
        for (Building t : intel.enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= 22 * 22)
                s += enemyTowerValue(t);
        // Peons near their base pile onto attackers.
        s += .5f * Combat.strengthNear(intel.enemy_peons, x, y, 40);
        // Weapons stocked in an armory nearby come out as soon as the attack shows up, if the AI may look inside.
        if (ai.strategy().hidden_info)
            for (Building a : intel.enemy_armories)
                if (!a.isDead() && MapAnalysis.dist2(a.getGridX(), a.getGridY(), x, y) <= 40 * 40)
                    s += stockStrength(a);
        return s;
    }

    /**
     * The defense of a target building or unit. With gate_owner (against several enemies) only its owner's warriors
     * come from afar (a Hard copy defends with its own idle warriors, wherever they stand: AdvancedAI.nodeDefendBase),
     * at half value beyond defense_radius; other copies count only within defense_radius. Without it, defenseAt, which
     * counts every enemy warrior beyond defense_radius at 0.3: at N=10 that alone is ~2.7 copy armies.
     */
    private float defenseFor(@NonNull Selectable<?> t) {
        int x = t.getGridX();
        int y = t.getGridY();
        if (!ai.strategy().gate_owner || ai.enemiesAlive() <= 1)
            return defenseAt(x, y);
        Intel intel = ai.intel();
        Player owner = t.getOwner();
        int r = ai.strategy().defense_radius;
        int r2 = r * r;
        float s = 0f;
        for (List<Unit> group : List.of(intel.enemy_warriors, intel.enemy_chieftains))
            for (Unit u : group) {
                if (u.isDead())
                    continue;
                boolean near = MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()) <= r2;
                if (u.getOwner() == owner)
                    s += Combat.value(u) * (near ? 1f : .5f);
                else if (near)
                    s += Combat.value(u);
            }
        s *= 1.1f;
        for (Building tw : intel.enemy_towers)
            if (MapAnalysis.dist2(tw.getGridX(), tw.getGridY(), x, y) <= 22 * 22)
                s += enemyTowerValue(tw);
        s += .5f * Combat.strengthNear(intel.enemy_peons, x, y, 40);
        return s;
    }

    /** A copy with no quarters or armory, finished or placed: raided, and kept in only by its units or a site. */
    private boolean homeless(@NonNull Player p) {
        for (Building b : ai.intel().enemy_buildings) {
            if (b.isDead() || b.getOwner() != p)
                continue;
            int id = b.getTemplate().getTemplateID();
            if (b.isComplete() && (id == com.oddlabs.tt.model.Race.BUILDING_QUARTERS
                    || id == com.oddlabs.tt.model.Race.BUILDING_ARMORY))
                return false;
        }
        return true;
    }

    /**
     * finish_copies: the copy the army just raided (no finished quarters or armory left) is finished before another is
     * chosen: its quarters and armory sites first, then its chieftain (which alone keeps a copy in: the collapse rule
     * needs no chieftain), then its other units, all within finish_range cells of the army.
     */
    private @Nullable Selectable<?> finishTarget(int from_x, int from_y) {
        Intel intel = ai.intel();
        int range2 = ai.strategy().finish_range * ai.strategy().finish_range;
        Selectable<?> best = null;
        float best_score = Float.MAX_VALUE;
        for (Building b : intel.enemy_buildings) {
            if (b.isDead() || b.isComplete() || stalled_targets.containsKey(b) || inDeadRegion(b.getGridX(),
                    b.getGridY()))
                continue;
            int id = b.getTemplate().getTemplateID();
            if (id != com.oddlabs.tt.model.Race.BUILDING_QUARTERS && id != com.oddlabs.tt.model.Race.BUILDING_ARMORY)
                continue;
            int d = MapAnalysis.dist2(from_x, from_y, b.getGridX(), b.getGridY());
            if (d > range2 || !homeless(b.getOwner()))
                continue;
            if (d < best_score) {
                best_score = d;
                best = b;
            }
        }
        if (best != null)
            return best;
        Strategy s = ai.strategy();
        boolean lean = s.finish_skip_out || s.finish_units >= 0 || s.finish_ratio > 0f;
        if (lean)
            countBases();
        float ref = mode == Mode.ATTACK ? attackStrength() : armyStrength();
        Map<Player, Boolean> skip = new LinkedHashMap<>();
        // Units of homeless copies: chieftains count as four times nearer.
        for (List<Unit> group : List.of(intel.enemy_chieftains, intel.enemy_warriors, intel.enemy_peons))
            for (Unit u : group) {
                if (u.isDead() || stalled_targets.containsKey(u) || inDeadRegion(u.getGridX(), u.getGridY()))
                    continue;
                int d = MapAnalysis.dist2(from_x, from_y, u.getGridX(), u.getGridY());
                if (d > range2 || !homeless(u.getOwner()))
                    continue;
                Player p = u.getOwner();
                if (lean && skip.computeIfAbsent(p, k -> leanSkip(k, from_x, from_y, ref)))
                    continue;
                if (s.finish_units >= 0 && group != intel.enemy_chieftains
                        && p.getUnitCountContainer().getNumSupplies() - (p.hasActiveChieftain() ? 1 : 0) <= s.finish_units)
                    continue; // only its chieftain (and sites, above) still matter
                if (s.finish_ratio > 0f && guarded(u, ref))
                    continue;
                float score = group == intel.enemy_chieftains ? d / 4f : d;
                if (score < best_score) {
                    best_score = score;
                    best = u;
                }
            }
        return best;
    }

    /** finish_lean: a copy already collapsing, or a remnant stronger than finish_ratio x our army. */
    private boolean leanSkip(@NonNull Player p, int fx, int fy, float ref) {
        Strategy s = ai.strategy();
        int[] c = qa_counts.getOrDefault(p, new int[2]);
        if (s.finish_skip_out && p.getUnitCountContainer().getNumSupplies() <= 8 && !p.hasActiveChieftain()
                && c[1] == 0) {
            ai.aiLog().count("finish_skip_out");
            return true;
        }
        if (s.finish_ratio > 0f) {
            int r2 = s.finish_range * s.finish_range;
            float remnant = 0f;
            for (List<Unit> g : List.of(ai.intel().enemy_warriors, ai.intel().enemy_chieftains))
                for (Unit u : g)
                    if (!u.isDead() && u.getOwner() == p && MapAnalysis.dist2(fx, fy, u.getGridX(), u.getGridY()) <= r2)
                        remnant += Combat.value(u);
            if (remnant > s.finish_ratio * ref) {
                ai.aiLog().count("finish_skip_big");
                return true;
            }
        }
        return false;
    }

    /** finish_lean: other copies' awake warriors within 25 cells of the candidate outweigh 0.3 x our army. */
    private boolean guarded(@NonNull Unit t, float ref) {
        float others = 0f;
        for (Unit e : ai.intel().enemy_warriors)
            if (!e.isDead() && e.getOwner() != t.getOwner()
                    && !(e.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.IdleController)
                    && MapAnalysis.dist2(e.getGridX(), e.getGridY(), t.getGridX(), t.getGridY()) <= 25 * 25)
                others += Combat.value(e);
        if (others > .3f * ref) {
            ai.aiLog().count("finish_skip_guarded");
            return true;
        }
        return false;
    }

    private @Nullable Selectable<?> chooseTarget(int from_x, int from_y) {
        return chooseTarget(from_x, from_y, null);
    }

    /** target_path: a re-target from where the army stands, scored by walking distance. */
    private @Nullable Selectable<?> retarget(int @NonNull [] c) {
        if (!ai.strategy().target_path)
            return chooseTarget(c[0], c[1]);
        return chooseTarget(c[0], c[1], ai.map().computeField(c[0], c[1], 1400));
    }

    /** With {@code path}, candidates are scored by its walking distance (meters) and unreachable ones skipped. */
    private @Nullable Selectable<?> chooseTarget(int from_x, int from_y, @Nullable DistanceField path) {
        Intel intel = ai.intel();
        Strategy strategy = ai.strategy();
        if (focus_owner != null && !focus_owner.isAlive())
            focus_owner = null;
        if (focus_owner != null && strategy.focus_finish) {
            // A copy with no building left but some units: hunt them down before it rebuilds.
            boolean buildings = false;
            for (Building b : intel.enemy_buildings)
                if (!b.isDead() && b.getOwner() == focus_owner) {
                    buildings = true;
                    break;
                }
            if (!buildings) {
                Unit prey = null;
                int best_d = Integer.MAX_VALUE;
                for (List<Unit> group : List.of(intel.enemy_peons, intel.enemy_warriors, intel.enemy_chieftains))
                    for (Unit u : group) {
                        if (u.isDead() || u.getOwner() != focus_owner)
                            continue;
                        int d = MapAnalysis.dist2(from_x, from_y, u.getGridX(), u.getGridY());
                        if (d < best_d) {
                            best_d = d;
                            prey = u;
                        }
                    }
                if (prey != null && best_d <= 90 * 90)
                    return prey;
            }
        }
        if (strategy.finish_copies && ai.enemiesAlive() > 1) {
            Selectable<?> finish = finishTarget(from_x, from_y);
            if (finish != null) {
                ai.aiLog().count("finish_target");
                return finish;
            }
        }
        Selectable<?> best = null;
        float best_score = Float.MAX_VALUE;
        // gate_freeze: a copy without quarters (finished or placed) cannot train the chieftain its waves need from
        // 20 on, and does not rebuild while its idle warriors outnumber its wave size: leave it until last.
        java.util.List<com.oddlabs.tt.player.Player> quartered = new ArrayList<>();
        if (strategy.gate_freeze)
            for (Building b : intel.enemy_buildings)
                if (!b.isDead() && b.getTemplate().getTemplateID() == com.oddlabs.tt.model.Race.BUILDING_QUARTERS
                        && !quartered.contains(b.getOwner()))
                    quartered.add(b.getOwner());
        boolean skip_frozen = strategy.gate_freeze && !quartered.isEmpty();
        // target_threat_weight: the copies whose warriors stand in our base are the ones razing it; their buildings
        // come first, by that many meters per unit of their strength there.
        Map<com.oddlabs.tt.player.Player, Float> base_threat = new LinkedHashMap<>();
        if (strategy.target_threat_weight > 0f)
            for (Unit u : threats)
                if (!u.isDead() && !u.getAbilities().hasAbilities(Abilities.BUILD))
                    base_threat.merge(u.getOwner(), Combat.value(u), Float::sum);
        stalled_targets.entrySet().removeIf(e -> e.getKey().isDead() || ai.time() - e.getValue() > STALL_MEMORY);
        while (!dead_region_times.isEmpty() && ai.time() - dead_region_times.getFirst() > STALL_MEMORY) {
            dead_regions.removeFirst();
            dead_region_times.removeFirst();
        }
        List<Building> candidates = new ArrayList<>(intel.enemy_armories);
        candidates.addAll(intel.enemy_quarters);
        candidates.addAll(intel.enemy_towers);
        candidates.removeIf(b -> stalled_targets.containsKey(b) || inDeadRegion(b.getGridX(), b.getGridY()));
        if (strategy.gate_freeze)
            for (Building b : intel.enemy_buildings)
                if (!b.isComplete() && b.getTemplate().getTemplateID() == com.oddlabs.tt.model.Race.BUILDING_QUARTERS)
                    candidates.add(b);
        if (candidates.isEmpty()) {
            candidates.addAll(intel.enemy_buildings);
            candidates.removeIf(b -> stalled_targets.containsKey(b) || inDeadRegion(b.getGridX(), b.getGridY()));
        }
        Building best_line = null;
        float best_line_score = Float.MAX_VALUE;
        Building best_frozen = null;
        float best_frozen_score = Float.MAX_VALUE;
        for (Building b : candidates) {
            if (b.isDead() || (skip_frozen && !quartered.contains(b.getOwner())) || ai.freeze().isFrozenSite(b))
                continue;
            float line = MapAnalysis.meters(from_x, from_y, b.getGridX(), b.getGridY());
            float d = line;
            if (path != null) {
                int walk = path.getAround(b.getGridX(), b.getGridY(), 4);
                if (walk == DistanceField.UNREACHABLE)
                    continue;
                d = walk;
            }
            float priority = switch (b.getTemplate().getTemplateID()) {
                case com.oddlabs.tt.model.Race.BUILDING_ARMORY -> strategy.quarters_first
                        || strategy.gate_freeze ? 60f : 0f;
                case com.oddlabs.tt.model.Race.BUILDING_QUARTERS -> strategy.quarters_first
                        || strategy.gate_freeze ? 0f : 60f;
                default -> 120f;
            };
            float defense = strategy.target_defense_weight * defenseFor(b);
            if (ai.freeze().isFrozen(b.getOwner())) {
                // A frozen copy (Freeze) has nobody outside to defend with, and only its quarters keeps it in.
                priority = 0f;
                defense = 0f;
            }
            float score = d + priority + defense;
            if (strategy.target_home_weight > 0f)
                score += strategy.target_home_weight * MapAnalysis.meters(staging_x, staging_y, b.getGridX(),
                        b.getGridY());
            if (focus_owner != null && b.getOwner() == focus_owner)
                score -= strategy.focus_bonus;
            score -= strategy.target_threat_weight * base_threat.getOrDefault(b.getOwner(), 0f);
            if (strategy.frozen_last && ai.freeze().isFrozen(b.getOwner())) {
                if (score < best_frozen_score) {
                    best_frozen_score = score;
                    best_frozen = b;
                }
                continue;
            }
            if (score < best_score) {
                best_score = score;
                best = b;
            }
            if (path != null && score - d + line < best_line_score) {
                best_line_score = score - d + line;
                best_line = b;
            }
        }
        if (path != null && best != best_line)
            ai.aiLog().count("retarget_path_changed");
        if (best_frozen != null) {
            if (best == null) {
                best = best_frozen;
                ai.aiLog().count("frozen_last_target");
            } else if (!frozen_deferred.contains(best_frozen.getOwner())) {
                frozen_deferred.add(best_frozen.getOwner());
                ai.aiLog().count("frozen_deferred");
            }
        }
        if (best == null) {
            List<Unit> units = new ArrayList<>(intel.enemy_peons);
            units.addAll(intel.enemy_warriors);
            units.addAll(intel.enemy_chieftains);
            int best_d = Integer.MAX_VALUE;
            for (Unit u : units) {
                if (u.isDead() || stalled_targets.containsKey(u) || inDeadRegion(u.getGridX(), u.getGridY()))
                    continue;
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
        float defense = defenseFor(t);
        if (strategy.project_defense) {
            // The enemy keeps arming while we march; judge the fight at the moment of arrival.
            int d = ai.planner().getEnemyField().get(staging_x, staging_y);
            float march = d == DistanceField.UNREACHABLE ? 120f : d / 3f;
            defense += Math.max(0f, enemyGrowthPerSecond()) * march;
        }
        // Chieftains decide battles: count ours as a big plus and theirs as a big minus, unless ours can answer his.
        boolean chief = intel.chieftain != null && intel.chieftain.getHitPoints() > 30
                && !(strategy.shred && strategy.shred_strict); // strict shred keeps him home
        boolean enemy_chief = false;
        int cr2 = strategy.defense_radius * strategy.defense_radius;
        for (Unit c : intel.enemy_chieftains)
            enemy_chief |= !c.isDead() && c.getHitPoints() > 15 && (!strategy.gate_owner || ai.enemiesAlive() <= 1
                    || c.getOwner() == t.getOwner()
                    || MapAnalysis.dist2(c.getGridX(), c.getGridY(), t.getGridX(), t.getGridY()) <= cr2);
        float bonus = chief && !enemy_chief ? 1.4f : chief ? 1.05f : enemy_chief ? .7f : 1f;
        Player owner = ai.owner();
        boolean capped = owner.getUnitCountContainer().getNumSupplies() >= owner.getWorld().getMaxUnitCount() - 10;
        float caution = attack_caution;
        boolean go = potential >= strategy.attack_min_strength
                && potential * bonus >= strategy.attack_ratio * caution * defense;
        go |= potential >= strategy.attack_max_strength * caution && potential * bonus >= .8f * caution * defense;
        go |= capped && potential * bonus >= strategy.capped_ratio * caution * defense;
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
                defense += enemyTowerValue(t);
        float army = armyStrength();
        // A handful sent at a tower going up only feeds it: go with enough to win outright.
        if (army < Math.max(10f, 2f * defense))
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
        if (ai.strategy().launch_recheck && timeout && !gathered && threat_level >= 2
                && base_threat_strength > ai.strategy().attack_threat_ratio * (stagingStrength(30) + stockStrength())) {
            mode = Mode.HOME;
            next_wave_time = ai.time() + 60f;
            ai.log(String.format("muster dropped: %.1f in the base", base_threat_strength));
            ai.aiLog().count("muster_dropped");
            return;
        }
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
        List<Unit> army = new ArrayList<>();
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.ARMY)
                army.add(e.getKey());
        for (Unit u : beyondGuard(army)) {
            roles.put(u, Role.ATTACK);
            s += Combat.value(u);
        }
        attack_initial_strength = s;
        worn_peak = s;
        worn_history.clear();
        worn_skip_counted = false;
        split_guard_counted = false;
        attack_kills_start = ai.owner().getUnitsKilled();
        attack_losses_start = ai.owner().getUnitsLost();
        attack_running = true;
        attack_start = ai.time();
        last_progress_time = ai.time();
        best_target_dist = Integer.MAX_VALUE;
        mode = s > 0 ? Mode.ATTACK : Mode.HOME;
        // Name the target without the engine's toString (a Unit's shows an identity hash, which differs between JVMs).
        String what = (t instanceof Building ? "building " : "unit ") + t.getTemplate().getClass().getSimpleName() + " of " + t.getOwner().getPlayerInfo().getName();
        ai.log(String.format("attack with %.1f on %s at %d,%d", s, what, t.getGridX(), t.getGridY()));
        if (mode == Mode.ATTACK)
            recruitSappers(t.getGridX(), t.getGridY());
    }

    /**
     * Peons to take along when the target is covered by towers: two per tower, from those nearest the staging point.
     */
    private void recruitSappers(int x, int y) {
        Intel intel = ai.intel();
        if (!ai.strategy().sappers || !intel.sappers.isEmpty())
            return;
        int towers = 0;
        for (Building t : intel.enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= 30 * 30)
                towers++;
        if (towers == 0)
            return;
        int want = Math.min(16, Math.max(6, 2 * towers));
        List<Unit> pool = new ArrayList<>();
        for (Unit p : intel.peons) {
            PeonState s = intel.peon_states.get(p);
            if (s == PeonState.IDLE || s == PeonState.GATHER_TREE || s == PeonState.GATHER_ROCK
                    || s == PeonState.GATHER_IRON || s == PeonState.MOVE)
                pool.add(p);
        }
        if (pool.size() < want + 15)
            return;
        pool.sort((a, b) -> Integer.compare(MapAnalysis.dist2(a.getGridX(), a.getGridY(), staging_x, staging_y),
                MapAnalysis.dist2(b.getGridX(), b.getGridY(), staging_x, staging_y)));
        for (Unit p : pool.subList(0, want)) {
            intel.sappers.add(p);
            intel.peon_states.put(p, PeonState.SAPPER);
        }
        ai.log(want + " peons go along to pull down " + towers + " towers");
    }

    /**
     * Sappers follow a little behind the army and go for the nearest enemy tower that cannot hurt them much: one whose
     * guard is stunned or gone, or one the army is holding the ground around.
     */
    private void sapperTick() {
        Intel intel = ai.intel();
        intel.sappers.removeIf(Unit::isDead);
        sapper_orders.keySet().removeIf(Unit::isDead);
        if (intel.sappers.isEmpty())
            return;
        int[] c = mode == Mode.ATTACK ? attackCenter() : null;
        if (c == null) {
            Building home = intel.armory();
            for (Unit p : intel.sappers)
                if (home != null && home.getUnitContainer() != null)
                    ai.owner().setTarget(Selectable.newArray(p), home, Action.DEFAULT, false);
            ai.log(intel.sappers.size() + " sappers go home");
            intel.sappers.clear();
            return;
        }
        float ours = 0f;
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.ATTACK && MapAnalysis.dist2(e.getKey().getGridX(), e.getKey().getGridY(), c[0],
                    c[1]) <= 20 * 20)
                ours += Combat.value(e.getKey());
        int[] back = stepTowards(c[0], c[1], staging_x, staging_y, 6);
        creepTick(c, ours);
        Building site = creep_site;
        for (Unit p : intel.sappers) {
            Building tower = null;
            int best = 18 * 18;
            for (Building t : intel.enemy_towers) {
                int d = MapAnalysis.dist2(t.getGridX(), t.getGridY(), c[0], c[1]);
                if (d > best)
                    continue;
                boolean quiet = !Intel.isTowerActive(t)
                        || (enemyStrengthNear(t.getGridX(), t.getGridY(), 10) < .5f * ours && ours >= 8f);
                if (quiet) {
                    best = d;
                    tower = t;
                }
            }
            Float last = sapper_orders.get(p);
            if (last != null && ai.time() - last < 3f)
                continue;
            // Already swinging at a tower: leave it be, a new order would start the swing over.
            if (tower != null && p.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.AttackController)
                continue;
            sapper_orders.put(p, ai.time());
            if (tower != null)
                ai.owner().setTarget(Selectable.newArray(p), tower, Action.ATTACK, true);
            else if (site != null
                    && !(p.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.RepairController))
                ai.owner().setTarget(Selectable.newArray(p), site, Action.DEFAULT, false);
            else if (site == null && MapAnalysis.dist2(p.getGridX(), p.getGridY(), back[0], back[1]) > 6 * 6)
                move(p, back[0], back[1]);
        }
    }

    /**
     * Towers raised by our sappers by enemy buildings, standing or going up, which the base's tower count leaves out.
     */
    int creepTowerCount() {
        creep_towers.removeIf(Building::isDead);
        return creep_towers.size() + (creep_site != null && !creep_site.isDead() ? 1 : 0);
    }

    /**
     * Raises a tower next to the besieged building once the army holds the ground there, and mans the ones that
     * stand with a warrior of the attack.
     */
    private void creepTick(int @NonNull [] c, float ours) {
        creep_towers.removeIf(Building::isDead);
        if (creep_site != null && (creep_site.isDead() || creep_site.isComplete())) {
            if (!creep_site.isDead()) {
                creep_towers.add(creep_site);
                ai.log("creep tower up at " + creep_site.getGridX() + "," + creep_site.getGridY());
            }
            creep_site = null;
        }
        for (Building t : creep_towers) {
            if (Intel.isTowerManned(t) || tower_assignments.containsValue(t))
                continue;
            Unit best = null;
            int best_d = 30 * 30;
            for (Map.Entry<Unit, Role> e : roles.entrySet()) {
                Unit u = e.getKey();
                if (e.getValue() != Role.ATTACK || ai.intel().warrior_states.get(u) == WarriorState.STUNNED)
                    continue;
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), t.getGridX(), t.getGridY());
                if (Intel.warriorType(u) == WarriorType.CHICKEN)
                    d -= 15 * 15;
                if (d < best_d) {
                    best_d = d;
                    best = u;
                }
            }
            if (best != null) {
                roles.put(best, Role.TOWER);
                tower_assignments.put(best, t);
                ai.owner().setTarget(Selectable.newArray(best), t, Action.DEFAULT, false);
            }
        }
        Selectable<?> goal = target;
        if (!ai.strategy().creep_towers || creep_site != null || goal == null || goal.isDead()
                || ai.time() - last_creep_try < 20f || ai.intel().sappers.size() < 4)
            return;
        int gx = goal.getGridX();
        int gy = goal.getGridY();
        if (MapAnalysis.dist2(c[0], c[1], gx, gy) > 30 * 30 || ours < 1.2f * enemyStrengthNear(gx, gy, 20) + 4f)
            return;
        int near = 0;
        for (Building t : creep_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), gx, gy) <= 20 * 20)
                near++;
        if (near >= 2)
            return;
        last_creep_try = ai.time();
        int[] spot = creepSpot(gx, gy, c);
        if (spot == null)
            return;
        Unit placer = null;
        int best_d = Integer.MAX_VALUE;
        for (Unit p : ai.intel().sappers) {
            int d = MapAnalysis.dist2(p.getGridX(), p.getGridY(), spot[0], spot[1]);
            if (d < best_d) {
                best_d = d;
                placer = p;
            }
        }
        if (placer != null)
            creep_site = ai.placeSite(List.of(placer), com.oddlabs.tt.model.Race.BUILDING_TOWER, spot[0], spot[1]);
        ai.log("sappers raise a tower at " + spot[0] + "," + spot[1] + " by " + goal);
    }

    /** A tower site in range of a besieged building, out of reach of active enemy towers, with trees to build from. */
    private int @Nullable [] creepSpot(int gx, int gy, int @NonNull [] c) {
        com.oddlabs.tt.model.BuildingTemplate tower = ai.owner().getRace().getBuildingTemplate(
                com.oddlabs.tt.model.Race.BUILDING_TOWER);
        MapAnalysis map = ai.map();
        int[] best = null;
        float best_score = -Float.MAX_VALUE;
        for (int y = gy - 11; y <= gy + 11; y++) {
            for (int x = gx - 11; x <= gx + 11; x++) {
                int d2 = MapAnalysis.dist2(x, y, gx, gy);
                if (d2 < 5 * 5 || d2 > 11 * 11 || !map.canPlace(tower, x, y))
                    continue;
                boolean covered = false;
                for (Building t : ai.intel().enemy_towers) {
                    if (Intel.isTowerActive(t) && MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= 12 * 12) {
                        covered = true;
                        break;
                    }
                }
                if (covered)
                    continue;
                int trees = map.treesAround(x, y, 8);
                if (trees < 2)
                    continue;
                float score = -(float) Math.sqrt(d2) - .3f * (float) Math.sqrt(MapAnalysis.dist2(x, y, c[0],
                        c[1])) + Math.min(trees, 8);
                if (score > best_score) {
                    best_score = score;
                    best = new int[]{x, y};
                }
            }
        }
        return best;
    }

    /** The point `cells` away from (x, y) in the direction of (to_x, to_y). */
    private static int @NonNull [] stepTowards(int x, int y, int to_x, int to_y, int cells) {
        float dx = to_x - x;
        float dy = to_y - y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= cells)
            return new int[]{to_x, to_y};
        return new int[]{x + (int) (dx / len * cells), y + (int) (dy / len * cells)};
    }

    private void setTarget(@NonNull Selectable<?> t) {
        if (ai.strategy().focus_bonus > 0f && t.getOwner() != ai.owner())
            focus_owner = t.getOwner();
        target = t;
        if (target_field == null || MapAnalysis.dist2(target_x, target_y, t.getGridX(), t.getGridY()) > 8 * 8) {
            target_x = t.getGridX();
            target_y = t.getGridY();
            target_field = ai.map().computeField(target_x, target_y, Integer.MAX_VALUE);
            best_target_dist = Integer.MAX_VALUE;
            last_progress_time = ai.time();
        }
    }

    /** Lets caution from past attacks wear off while the army sits capped at home. */
    private void easeCaution() {
        Player owner = ai.owner();
        boolean capped = owner.getUnitCountContainer().getNumSupplies() >= owner.getWorld().getMaxUnitCount() - 10;
        if (mode != Mode.HOME || !capped || attack_caution <= 1f || ai.strategy().caution_decay <= 1f) {
            last_caution_ease = ai.time();
            return;
        }
        if (ai.time() - last_caution_ease < 60f)
            return;
        last_caution_ease = ai.time();
        float before = attack_caution;
        attack_caution = Math.max(1f, attack_caution / ai.strategy().caution_decay);
        ai.log(String.format("capped at home: caution %.2f -> %.2f", before, attack_caution));
    }

    private void endAttack() {
        if (mode != Mode.HOME)
            ai.log("attack over, back home");
        if (attack_running && ai.strategy().adaptive_caution) {
            int kills = ai.owner().getUnitsKilled() - attack_kills_start;
            int losses = ai.owner().getUnitsLost() - attack_losses_start;
            if (kills + losses >= 10) {
                float trade = kills / (float) Math.max(1, losses);
                float before = attack_caution;
                if (trade < 1f)
                    attack_caution = Math.min(2.5f, attack_caution * 1.25f);
                else if (trade > 1.5f)
                    attack_caution = Math.max(1f, attack_caution / 1.25f);
                ai.log(String.format("attack traded %d kills for %d losses: caution %.2f -> %.2f", kills, losses,
                        before, attack_caution));
            }
        }
        attack_running = false;
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.ATTACK || e.getValue() == Role.REINFORCE)
                e.setValue(Role.ARMY);
        mode = Mode.HOME;
        target = null;
        next_wave_time = ai.time() + (recalled ? ai.strategy().recall_cooldown : 20f);
        recalled = false;
        strike = false;
    }

    /** defend_stable: when the current engage began. */
    private float engage_since = -10f;

    /** The attack being ended was called home (recall_cooldown). */
    private boolean recalled;

    /** launch_recheck: the fighting value of home army units within {@code radius} cells of the staging point. */
    private float stagingStrength(int radius) {
        float s = 0f;
        int r2 = radius * radius;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() != Role.ARMY)
                continue;
            Unit u = e.getKey();
            WarriorState st = ai.intel().warrior_states.get(u);
            if (u.isDead() || st == WarriorState.STUNNED || st == WarriorState.ENTER)
                continue;
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), staging_x, staging_y) <= r2)
                s += Combat.value(u);
        }
        return s;
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
        // stall_peons: what could stop the army, without the peons it cuts down on the way
        float armed = ai.strategy().stall_peons ? enemyStrengthNear(c[0], c[1], ENGAGE_RADIUS) : 0f;
        for (Building t : intel.enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), c[0], c[1]) <= ENGAGE_RADIUS * ENGAGE_RADIUS) {
                float v = enemyTowerValue(t);
                local_enemy += v;
                armed += v;
            }
        // Height decides a lot: up to a quarter more (or less) chance to hit.
        ours *= terrainFactor(army, intel.enemy_warriors, c[0], c[1], ENGAGE_RADIUS);
        boolean toot = ai.chieftain().stunReady() && intel.chieftain != null
                && MapAnalysis.dist2(intel.chieftain.getGridX(), intel.chieftain.getGridY(), c[0], c[1]) < 20 * 20;
        // Counters compare what the stun fear decides with what the enemy alone would have (local_raw).
        float local_raw = local_enemy;
        if (enemyStunReadyNear(c[0], c[1], ENGAGE_RADIUS + 4))
            local_enemy *= ai.strategy().enemy_stun_mult;
        // stall_calm: the stall clock runs only while the march is calm; long fights on the way are no stall
        // (6 of 6 reachable stalls outside one corner deadlock came after ~80 s of fighting).
        boolean stall_peons = ai.strategy().stall_peons;
        if (ai.strategy().stall_calm && (stall_peons ? armed > 0f : local_enemy > 0f || anyFighting(army)))
            last_progress_time = ai.time();
        else if (stall_peons && ai.strategy().stall_calm && local_enemy > 0f && anyFighting(army))
            ai.aiLog().count("stall_peon_pin");
        float total = 0f;
        int stunned_count = 0;
        for (Unit u : army) {
            total += Combat.lastingValue(u);
            if (Intel.isStunned(u))
                stunned_count++;
        }
        // A stunned army cannot walk away; decide once it can move again.
        boolean pinned = stunned_count * 10 > army.size() * 3;
        float worn_base = wornBasis(total);
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
                    awake += enemyTowerValue(t);
            // An enemy chieftain with his spell ready would stun the charge in turn (unless his stun is not feared).
            if (asleep >= 3f && total >= .8f * awake && !(ai.strategy().enemy_stun_mult > 1f && enemyStunReadyNear(
                    c[0], c[1], 45))) {
                if (ai.time() - last_charge_log > 10f) {
                    last_charge_log = ai.time();
                    ai.log(String.format("charging %d stunned enemies (%.1f asleep, %.1f awake, army %.1f)",
                            stunned.size(), asleep, awake, total));
                }
                int[] sc = centroid(stunned);
                List<Selectable<?>> prey = new ArrayList<>(stunned);
                // The ones still awake keep throwing: each warrior weighs them against the helpless.
                if (ai.strategy().charge_mixed)
                    for (Unit e : intel.enemy_warriors)
                        if (!Intel.isStunned(e) && MapAnalysis.dist2(e.getGridX(), e.getGridY(), c[0],
                                c[1]) <= (ENGAGE_RADIUS + 8) * (ENGAGE_RADIUS + 8))
                            prey.add(e);
                engageSpread(army, prey, sc[0], sc[1], true);
                huntChieftains(army);
                return;
            }
        }
        if (ai.strategy().siege && !pinned && siege(army, c, total))
            return;
        if (!toot && !pinned && ai.strategy().precontact_ratio > 0f && !anyFighting(army)) {
            // Before contact, look at everything that can defend the area, not just what is next to us: turning
            // back now costs nothing, walking into a stronger defense costs the army.
            float wide = enemyFightersNear(c[0], c[1], 36);
            for (Building t : intel.enemy_towers)
                if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), c[0], c[1]) <= 36 * 36)
                    wide += enemyTowerValue(t);
            float terrain = terrainFactor(army, intel.enemy_warriors, c[0], c[1], 36);
            if (wide > 0f && total * terrain < ai.strategy().precontact_ratio * wide) {
                ai.log(String.format("turning back before contact: %.1f against %.1f", total * terrain, wide));
                beginRetreat();
                return;
            }
        }
        Strategy strategy = ai.strategy();
        // retreat_split_guard: with most of the army away from its centre, weigh all of it.
        float weighed = strategy.retreat_split_guard && ours < .5f * total ? total : ours;
        boolean outmatched = local_enemy > strategy.retreat_ratio * Math.max(weighed, 1f);
        boolean worn = total < strategy.worn_ratio * worn_base && local_enemy > total;
        if (!toot && !pinned && (outmatched || worn) && pillage(army, c, total))
            return;
        if (!toot && !pinned && !outmatched && !split_guard_counted
                && local_enemy > strategy.retreat_ratio * Math.max(ours, 1f)) {
            split_guard_counted = true;
            ai.aiLog().count("split_guard_kept");
        }
        if (!toot && !pinned && outmatched) {
            if (local_raw <= strategy.retreat_ratio * Math.max(weighed, 1f))
                ai.aiLog().count("stun_fear_retreat");
            ai.log(String.format("retreat: local %.1f vs enemy %.1f (army %.1f of %.1f)", ours, local_enemy, total,
                    attack_initial_strength));
            beginRetreat();
            return;
        }
        if (!pinned && worn) {
            ai.aiLog().count("worn_fired");
            if (local_raw <= total)
                ai.aiLog().count("stun_fear_worn");
            ai.log(String.format("retreat: worn down to %.1f of %.1f", total, worn_base));
            beginRetreat();
            return;
        }
        // worn_basis: an attack that the cumulative basis would have called worn goes on.
        if (!pinned && !worn_skip_counted && strategy.worn_basis != 0
                && total < strategy.worn_ratio * attack_initial_strength && local_enemy > total) {
            worn_skip_counted = true;
            ai.aiLog().count("worn_skipped");
        }
        if (target == null || target.isDead()) {
            if (strike) {
                endAttack();
                return;
            }
            Selectable<?> next = retarget(c);
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
        march_wp = waypoint;
        march_calm_time = ai.time();
        // reinforce_intercept: a reinforcement group worth 40 % of the army within 80 cells is waited for (20 s at
        // most per target) instead of being left to chase the army across the field at the same speed.
        if (ai.strategy().reinforce_intercept && waitForReinforcements(c, total))
            return;
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
        if (ai.time() - last_progress_time > 75f && (ai.strategy().stall_peons ? armed == 0f : local_enemy == 0f)) {
            if (ai.logging()) {
                // Why: how big is the region the target stands in, and can our staging point reach it?
                DistanceField f = target_field;
                int cells = 0;
                if (f != null)
                    for (int cost : f.raw())
                        if (cost != DistanceField.UNREACHABLE)
                            cells++;
                int fc = cells;
                ai.log("attack stalled (field from " + target_x + "," + target_y + ", target region " + fc + " cells, staging " + (f != null
                        && f.getAround(staging_x,
                                staging_y, 2) != DistanceField.UNREACHABLE ? "reaches it" : "cut off") + ")");
            } else
                ai.log("attack stalled");
            if (target != null && ai.strategy().skip_stalled) {
                stalled_targets.put(target, ai.time());
                ai.aiLog().count("target_stalled");
                DistanceField f = target_field;
                if (f != null && f.getAround(staging_x, staging_y, 2) == DistanceField.UNREACHABLE) {
                    dead_regions.add(f);
                    dead_region_times.add(ai.time());
                    if (dead_regions.size() > 3) {
                        dead_regions.removeFirst();
                        dead_region_times.removeFirst();
                    }
                    ai.aiLog().count("target_region_dead");
                }
                // stall_calm: a target the army can reach but not get to (a deadlock at a corner) is dropped for the
                // next one from where the army stands, instead of walking everyone home.
                if (ai.strategy().stall_calm && (f == null || f.getAround(staging_x, staging_y,
                        2) != DistanceField.UNREACHABLE)) {
                    Selectable<?> next = retarget(c);
                    if (next != null) {
                        setTarget(next);
                        // A new target near the old one keeps the old field: restart the clock either way, or the
                        // next tick would ban it too.
                        last_progress_time = ai.time();
                        best_target_dist = Integer.MAX_VALUE;
                        ai.aiLog().count("stall_retarget");
                        return;
                    }
                }
            }
            beginRetreat();
        }
    }

    /**
     * The strength the worn retreat measures the attacking army (now worth total) against, by worn_basis: the launch
     * strength plus every reinforcement that joined, the peak since the launch, or the peak of the last worn_window
     * seconds.
     */
    private float wornBasis(float total) {
        Strategy strategy = ai.strategy();
        if (strategy.worn_basis == 1) {
            worn_peak = Math.max(worn_peak, total);
            return worn_peak;
        }
        if (strategy.worn_basis == 2) {
            float now = ai.time();
            while (!worn_history.isEmpty() && worn_history.getLast()[1] <= total)
                worn_history.removeLast();
            worn_history.addLast(new float[]{now, total});
            while (worn_history.size() > 1 && worn_history.getFirst()[0] < now - strategy.worn_window)
                worn_history.removeFirst();
            return worn_history.getFirst()[1];
        }
        return attack_initial_strength;
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
        // hold_closing 1: only the group's own motion towards us counts, not our march towards it.
        boolean motion = ai.strategy().hold_closing == 1;
        if (motion) {
            boolean coming = groupComing(intel.enemy_warriors, enemy, c);
            if (hold_until < 0f && closing && !coming && ai.time() - last_hold_skip >= 12f) {
                last_hold_skip = ai.time();
                ai.aiLog().count("hold_skipped_static");
            }
            closing = coming;
        }
        if (hold_until < 0f) {
            if (!closing)
                return false;
            if (motion)
                ai.aiLog().count("hold_coming");
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

    /**
     * hold_closing 1: whether the enemy group centred at {@code enemy} came at least 2 cells nearer to our army's
     * centre
     * {@code c} by its own motion since its centre of 2-4 s ago, with fewer than half of its units parked.
     */
    private boolean groupComing(@NonNull List<@NonNull Unit> units, int @NonNull [] enemy, int @NonNull [] c) {
        float now = ai.time();
        hold_groups.removeIf(g -> g[0] < now - 4f);
        float[] then = null;
        for (float[] g : hold_groups)
            if (g[0] <= now - 2f)
                then = g;
        hold_groups.addLast(new float[]{now, enemy[0], enemy[1]});
        if (then == null)
            return false;
        float tx = c[0] - then[1];
        float ty = c[1] - then[2];
        float len = (float) Math.sqrt(tx * tx + ty * ty);
        if (len < 1f)
            return false;
        float toward = ((enemy[0] - then[1]) * tx + (enemy[1] - then[2]) * ty) / len;
        if (toward < 2f)
            return false;
        // The group as nearestGroup takes it: everything within 8 cells of its unit nearest to the army.
        Unit nearest = null;
        int best = 26 * 26;
        for (Unit u : units) {
            if (u.isDead())
                continue;
            int dd = MapAnalysis.dist2(c[0], c[1], u.getGridX(), u.getGridY());
            if (dd <= best) {
                best = dd;
                nearest = u;
            }
        }
        if (nearest == null)
            return false;
        int n = 0;
        int parked = 0;
        for (Unit u : units) {
            if (u.isDead() || MapAnalysis.dist2(nearest.getGridX(), nearest.getGridY(), u.getGridX(),
                    u.getGridY()) > 8 * 8)
                continue;
            n++;
            if (isParked(u))
                parked++;
        }
        return parked * 2 < n;
    }

    /**
     * Instead of turning back from towers with the enemy army beaten, go for the enemy's peons outside tower cover.
     * Returns whether the army is pillaging.
     */
    private boolean pillage(@NonNull List<@NonNull Unit> army, int @NonNull [] c, float total) {
        if (!ai.strategy().pillage || total < 6f || enemyStrengthNear(c[0], c[1], 30) > .5f * total)
            return false;
        Intel intel = ai.intel();
        List<Unit> prey = new ArrayList<>();
        for (Unit p : intel.enemy_peons) {
            if (p.isDead() || MapAnalysis.dist2(p.getGridX(), p.getGridY(), c[0], c[1]) > 45 * 45)
                continue;
            boolean covered = false;
            for (Building t : intel.enemy_towers) {
                if (Intel.isTowerActive(t)
                        && MapAnalysis.dist2(t.getGridX(), t.getGridY(), p.getGridX(), p.getGridY()) <= 11 * 11) {
                    covered = true;
                    break;
                }
            }
            if (!covered && enemyStrengthNear(p.getGridX(), p.getGridY(), 16) < .5f * total)
                prey.add(p);
        }
        if (prey.size() < 3)
            return false;
        if (ai.time() - last_pillage_log > 15f) {
            last_pillage_log = ai.time();
            ai.log(String.format("pillaging %d peons outside the towers with %.1f", prey.size(), total));
        }
        int[] pc = centroid(prey);
        engageSpread(army, prey, pc[0], pc[1], false);
        return true;
    }

    /**
     * Sends the given warriors at enemies lying stunned around (x, y) while the ones still awake are no match for
     * them. Returns whether they charge.
     */
    private boolean chargeStunned(@NonNull List<@NonNull Unit> units, int x, int y) {
        List<Unit> stunned = stunnedEnemiesNear(x, y, 30);
        if (stunned.isEmpty() || units.isEmpty())
            return false;
        float asleep = 0f;
        for (Unit e : stunned)
            asleep += Combat.lastingValue(e);
        float ours = 0f;
        for (Unit u : units)
            ours += Combat.lastingValue(u);
        float awake = enemyFightersNear(x, y, 30);
        if (asleep < 3f || ours < .8f * awake || (ai.strategy().enemy_stun_mult > 1f && enemyStunReadyNear(x, y, 40)))
            return false;
        int[] sc = centroid(stunned);
        engageSpread(units, stunned, sc[0], sc[1], true);
        huntChieftains(units);
        return true;
    }

    /** Where the chieftain is meeting an enemy army alone to blast it, or null. */
    int @Nullable [] blastPlay() {
        return ai.time() < blast_play_until ? new int[]{threat_x, threat_y} : null;
    }

    /**
     * Against a strong attack on the base with the blast charged: the defenders step back out of its reach and the
     * chieftain walks up to the enemy alone. Returns whether the play has the defenders' orders this tick.
     */
    private boolean blastDefense(@NonNull List<@NonNull Unit> defenders, float enemy) {
        Strategy strategy = ai.strategy();
        float now = ai.time();
        if (!strategy.blast || !strategy.blast_defense)
            return false;
        if (now >= blast_play_until) {
            if (threat_level < 2 || enemy < strategy.blast_min || now - last_blast_play < 60f
                    || !ai.chieftain().blastReady())
                return false;
            blast_play_until = now + strategy.blast_play_time;
            last_blast_play = now;
            ai.log(String.format("blast play against %.1f at %d,%d", enemy, threat_x, threat_y));
        }
        if (!ai.chieftain().blastReady() && ai.chieftain().sinceCast() > 5f) {
            blast_play_until = now;
            return false;
        }
        Unit chief = ai.intel().chieftain;
        int cx = chief != null ? chief.getGridX() : threat_x;
        int cy = chief != null ? chief.getGridY() : threat_y;
        // Everyone out to 22 cells from the chieftain, on the far side from the enemy.
        for (Unit u : defenders) {
            if (Intel.isStunned(u))
                continue;
            int d2 = MapAnalysis.dist2(u.getGridX(), u.getGridY(), cx, cy);
            if (d2 >= 22 * 22)
                continue;
            float dx = u.getGridX() - threat_x;
            float dy = u.getGridY() - threat_y;
            float len = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
            int[] to = {Math.round(threat_x + dx / len * 30f), Math.round(threat_y + dy / len * 30f)};
            int size = ai.map().getSize();
            move(u, Math.clamp(to[0], 0, size - 1), Math.clamp(to[1], 0, size - 1));
        }
        return true;
    }

    /** Whether the attacking army is holding outside enemy towers for the chieftain's stun. */
    boolean sieging() {
        return mode == Mode.ATTACK && siege_start >= 0f;
    }

    private void endSiege() {
        if (siege_start >= 0f)
            ai.log(String.format("siege over after %.0fs", ai.time() - siege_start));
        siege_start = -1f;
        siege_assign.clear();
    }

    /**
     * Takes manned towers apart without walking into their throws: the army waits just outside their reach until the
     * chieftain stuns them from his standoff, then the whole army pulls the stunned towers down in the ten seconds
     * before they wake. Returns whether the siege has the army's orders this tick.
     */
    private boolean siege(@NonNull List<@NonNull Unit> army, int @NonNull [] c, float total) {
        Intel intel = ai.intel();
        float now = ai.time();
        List<Building> awake = new ArrayList<>();
        List<Building> helpless = new ArrayList<>();
        for (Building t : intel.enemy_towers) {
            if (!Intel.isTowerManned(t) || MapAnalysis.dist2(t.getGridX(), t.getGridY(), c[0], c[1]) > 34 * 34)
                continue;
            if (Intel.isTowerActive(t))
                awake.add(t);
            else
                helpless.add(t);
        }
        // A field army is fought as usual; a siege is for towers.
        float field = siege_start >= 0f ? .7f : .5f;
        if ((awake.isEmpty() && helpless.isEmpty()) || enemyStrengthNear(c[0], c[1], 30) > field * total
                || total < 10f) {
            endSiege();
            return false;
        }
        Unit chief = intel.chieftain;
        boolean stunner = chief != null && !chief.isDead() && chief.getHitPoints() > 24
                && ai.owner().getRace() == ai.owner().getWorld().getRacesResources().getRace(
                        com.oddlabs.tt.model.RacesResources.RACE_VIKINGS);
        float since = ai.chieftain().sinceCast();
        // The towers to go for now: stunned ones, nearly fallen ones, and the ones our stun is about to land on (the
        // walk in takes most of the ten seconds a stun lasts, so the army sets off while the horn is still up).
        List<Building> goals = new ArrayList<>(helpless);
        for (Building t : awake) {
            boolean weak = t.getHitPoints() <= 50 && siege_assign.containsValue(t);
            boolean about_to = stunner && since >= 1.8f && since < 4.5f && MapAnalysis.dist2(chief.getGridX(),
                    chief.getGridY(), t.getGridX(), t.getGridY()) <= 300;
            if (weak || about_to)
                goals.add(t);
        }
        siege_assign.entrySet().removeIf(e -> e.getKey().isDead() || !goals.contains(e.getValue()));
        if (!goals.isEmpty()) {
            if (siege_start < 0f)
                siege_start = now;
            siege_progress = now;
            if (now - last_pull_log >= 5f) {
                last_pull_log = now;
                int hp = 0;
                for (Building t : goals)
                    hp += t.getHitPoints();
                ai.log(String.format("pulling down %d towers (%d stunned, %d hp) with %.1f", goals.size(),
                        helpless.size(), hp, total));
            }
            pullDown(army, goals);
            return true;
        }
        // Worth waiting for a stun that is at most half a recharge away, or one on its way down.
        boolean pending = since < 5f;
        if (!stunner || (!pending
                && chief.getMagicProgress(com.oddlabs.tt.model.RacesResources.INDEX_MAGIC_STUN) < .5f)
                || now < siege_cooldown) {
            endSiege();
            return false;
        }
        if (siege_start < 0f) {
            siege_start = now;
            siege_progress = now;
            ai.log(String.format("siege of %d towers with %.1f", awake.size(), total));
        }
        if (now - siege_progress > ai.strategy().siege_patience) {
            ai.log("siege gives up");
            siege_cooldown = now + 60f;
            endSiege();
            return false;
        }
        siege_assign.clear();
        // Wait close in, just outside the nearest tower's reach, so the walk in fits inside the stun.
        Building first = null;
        int first_d = Integer.MAX_VALUE;
        for (Building t : awake) {
            int d = MapAnalysis.dist2(t.getGridX(), t.getGridY(), c[0], c[1]);
            if (d < first_d) {
                first_d = d;
                first = t;
            }
        }
        int hx = c[0];
        int hy = c[1];
        if (first != null && first_d > 0) {
            float len = (float) Math.sqrt(first_d);
            hx = first.getGridX() + Math.round((c[0] - first.getGridX()) / len * (SIEGE_HOLD + 1));
            hy = first.getGridY() + Math.round((c[1] - first.getGridY()) / len * (SIEGE_HOLD + 1));
        }
        int[] hold = outOfReach(hx, hy, awake);
        int r2 = TOWER_REACH * TOWER_REACH;
        for (Unit u : army) {
            if (Intel.isStunned(u))
                continue;
            boolean exposed = false;
            for (Building t : awake)
                exposed |= MapAnalysis.dist2(u.getGridX(), u.getGridY(), t.getGridX(), t.getGridY()) <= r2;
            // Inside a tower's reach even a fight is not worth staying for; outside, fight whatever comes out.
            if (exposed)
                move(u, hold[0], hold[1]);
            else
                attackGround(u, hold[0], hold[1], false);
        }
        return true;
    }

    /** A point near (x, y) at least SIEGE_HOLD cells from every given tower. */
    private int @NonNull [] outOfReach(int x, int y, @NonNull List<@NonNull Building> towers) {
        for (int pass = 0; pass < 4; pass++) {
            Building near = null;
            int best = SIEGE_HOLD * SIEGE_HOLD;
            for (Building t : towers) {
                int d = MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY());
                if (d < best) {
                    best = d;
                    near = t;
                }
            }
            if (near == null)
                break;
            float dx = x - near.getGridX();
            float dy = y - near.getGridY();
            if (dx * dx + dy * dy < 1f) {
                dx = staging_x - near.getGridX();
                dy = staging_y - near.getGridY();
            }
            float len = Math.max(.1f, (float) Math.sqrt(dx * dx + dy * dy));
            x = near.getGridX() + Math.round(dx / len * (SIEGE_HOLD + 1));
            y = near.getGridY() + Math.round(dy / len * (SIEGE_HOLD + 1));
        }
        int size = ai.map().getSize();
        return new int[]{Math.clamp(x, 0, size - 1), Math.clamp(y, 0, size - 1)};
    }

    /**
     * Sends the army at stunned towers, the nearest warriors to each, a dozen or so per tower: at three quarters of a
     * hit for two points each they bring a hundred points down in moments.
     */
    private void pullDown(@NonNull List<@NonNull Unit> army, @NonNull List<@NonNull Building> helpless) {
        List<Unit> free = new ArrayList<>();
        for (Unit u : army)
            if (!Intel.isStunned(u) && !u.isDead())
                free.add(u);
        int per = Math.max(6, Math.min(14, free.size() / helpless.size()));
        for (Building t : helpless) {
            free.sort((a, b) -> Integer.compare(MapAnalysis.dist2(a.getGridX(), a.getGridY(), t.getGridX(),
                    t.getGridY()), MapAnalysis.dist2(b.getGridX(), b.getGridY(), t.getGridX(), t.getGridY())));
            List<Unit> squad = new ArrayList<>();
            for (int k = 0; k < per && !free.isEmpty(); k++) {
                Unit u = free.removeFirst();
                if (siege_assign.get(u) == t)
                    continue;
                siege_assign.put(u, t);
                squad.add(u);
            }
            if (!squad.isEmpty())
                ai.owner().setTarget(squad.toArray(new Selectable<?>[0]), t, Action.ATTACK, false);
        }
        // The rest fight whatever is around the towers.
        if (!free.isEmpty()) {
            int[] tc = centroid(new ArrayList<>(helpless));
            for (Unit u : free)
                attackGround(u, tc[0], tc[1], true);
        }
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
            if (e.getValue() == Role.REINFORCE) {
                e.setValue(Role.ARMY);
                move(e.getKey(), staging_x, staging_y);
            } else if (e.getValue() == Role.ATTACK) {
                if (ai.strategy().retreat_rearguard && inFight(e.getKey())) {
                    ai.aiLog().count("rearguard_units");
                    continue;
                }
                move(e.getKey(), staging_x, staging_y);
            }
        }
    }

    /** retreat_rearguard: fighting, or an awake enemy warrior within 9 cells. */
    private boolean inFight(@NonNull Unit u) {
        if (ai.intel().warrior_states.get(u) == WarriorState.FIGHT)
            return true;
        for (Unit e : ai.intel().enemy_warriors)
            if (!e.isDead() && MapAnalysis.dist2(u.getGridX(), u.getGridY(), e.getGridX(), e.getGridY()) <= 9 * 9)
                return true;
        return false;
    }

    /**
     * Warriors that gathered at home while an attack is out go and join it as one group once they are worth it, rather
     * than idle until the attack is over and the attacking army has worn away.
     */
    private void considerReinforcing() {
        List<Unit> group = new ArrayList<>();
        float home = 0f;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() != Role.ARMY)
                continue;
            Unit u = e.getKey();
            WarriorState s = ai.intel().warrior_states.get(u);
            if (s == WarriorState.STUNNED || s == WarriorState.ENTER)
                continue;
            group.add(u);
            home += Combat.value(u);
        }
        group = beyondGuard(group);
        home = 0f;
        for (Unit u : group)
            home += Combat.value(u);
        if (group.isEmpty())
            return;
        float away = attackStrength();
        Player owner = ai.owner();
        boolean capped = owner.getUnitCountContainer().getNumSupplies() >= owner.getWorld().getMaxUnitCount() - 10;
        // Reinforcements go as a clump: a trickle of a few at a time is picked off on the way.
        float capped_need = capped ? cappedNeed(away) : 12f;
        if (home < Math.max(12f, ai.strategy().reinforce_ratio * away) && !(capped && home >= capped_need)) {
            if (capped && home >= 12f)
                ai.aiLog().count("capped_clump_wait");
            return;
        }
        if (!capped && ai.enemiesAlive() > 1 && !ai.strategy().reinforce_multi)
            return;
        for (Unit u : group)
            roles.put(u, Role.REINFORCE);
        ai.log(String.format("reinforcing the attack (%.1f) with %.1f", away, home));
    }

    /**
     * capped_clump: the strength a home group needs to go at the unit cap, 12 while the attack army is near the
     * armory, and a clump of max(capped_clump_min, capped_clump x the army) for an army capped_clump_cells or farther.
     */
    private float cappedNeed(float away) {
        Strategy strategy = ai.strategy();
        if (strategy.capped_clump <= 0f)
            return 12f;
        int[] front = attackCenter();
        Building armory = ai.intel().armory();
        if (front == null || armory == null || MapAnalysis.dist2(front[0], front[1], armory.getGridX(),
                armory.getGridY()) < strategy.capped_clump_cells * strategy.capped_clump_cells)
            return 12f;
        return Math.max(12f, Math.max(strategy.capped_clump_min, strategy.capped_clump * away));
    }

    /**
     * The units of a home group that may leave: all but a guard of home_guard strength, the ones nearest the armory,
     * kept to hold the base while the army is out (at N=8 the base otherwise falls behind the attacks).
     */
    private @NonNull List<@NonNull Unit> beyondGuard(@NonNull List<@NonNull Unit> group) {
        float guard = ai.strategy().home_guard;
        Building armory = ai.intel().armory();
        if (guard <= 0f || armory == null)
            return group;
        List<Unit> sorted = new ArrayList<>(group);
        int ax = armory.getGridX();
        int ay = armory.getGridY();
        sorted.sort(java.util.Comparator.comparingInt(u -> MapAnalysis.dist2(u.getGridX(), u.getGridY(), ax, ay)));
        float kept = 0f;
        List<Unit> leave = new ArrayList<>();
        for (Unit u : sorted) {
            if (kept < guard)
                kept += Combat.value(u);
            else
                leave.add(u);
        }
        return leave;
    }

    /** Marches reinforcements to the attacking army; they join it once close. */
    private void reinforce() {
        int[] front = null;
        boolean intercept = ai.strategy().reinforce_intercept;
        List<Unit> attackers = null;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() != Role.REINFORCE)
                continue;
            if (front == null)
                front = attackCenter();
            Unit u = e.getKey();
            if (front == null) {
                e.setValue(Role.ARMY);
                continue;
            }
            boolean joined = MapAnalysis.dist2(u.getGridX(), u.getGridY(), front[0], front[1]) <= 20 * 20;
            if (!joined && intercept) {
                // Joined as soon as it is near any attacker, not only near the army's centre.
                if (attackers == null) {
                    attackers = new ArrayList<>();
                    for (Map.Entry<Unit, Role> a : roles.entrySet())
                        if (a.getValue() == Role.ATTACK)
                            attackers.add(a.getKey());
                }
                for (Unit a : attackers)
                    if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), a.getGridX(), a.getGridY()) <= 12 * 12) {
                        joined = true;
                        break;
                    }
            }
            if (joined) {
                e.setValue(Role.ATTACK);
                attack_initial_strength += Combat.value(u);
                ai.aiLog().count("reinforce_joined");
                continue;
            }
            // While the army marches calmly, head for where it is going (its march waypoint), which it has just
            // cleared, instead of chasing its centre.
            int[] goal = intercept && march_wp != null && ai.time() - march_calm_time < 2f ? march_wp : front;
            attackGround(u, goal[0], goal[1], false);
        }
    }

    /** The army's current march waypoint, and when it was set (only while marching calmly). */
    private int @Nullable [] march_wp;
    private float march_calm_time = -100f;
    private @Nullable Selectable<?> hold_target;
    private float hold_since;

    /** True while the army should stand and wait for a big reinforcement group that is on its way. */
    private boolean waitForReinforcements(int @NonNull [] c, float army_strength) {
        float s = 0f;
        long x = 0;
        long y = 0;
        int n = 0;
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.REINFORCE) {
                Unit u = e.getKey();
                s += Combat.value(u);
                x += u.getGridX();
                y += u.getGridY();
                n++;
            }
        if (n == 0 || s < .4f * army_strength)
            return false;
        int gx = (int) (x / n);
        int gy = (int) (y / n);
        if (MapAnalysis.dist2(gx, gy, c[0], c[1]) > 80 * 80)
            return false;
        if (hold_target != target) {
            hold_target = target;
            hold_since = ai.time();
        }
        if (ai.time() - hold_since > 20f)
            return false;
        ai.aiLog().count("reinforce_hold");
        return true;
    }

    private void retreat() {
        int n = 0;
        int home = 0;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() != Role.ATTACK)
                continue;
            Unit u = e.getKey();
            n++;
            WarriorState st = ai.intel().warrior_states.get(u);
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), staging_x, staging_y) <= 14 * 14)
                home++;
            else if (st == WarriorState.IDLE || (ai.strategy().retreat_rearguard && st != WarriorState.MOVE
                    && st != WarriorState.FIGHT && st != WarriorState.STUNNED))
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
                    danger += enemyTowerValue(t);
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

    // ------------------------------------------------------------------------------------------------------------
    // chief_hunt: a small squad finishes homeless copies (their lone chieftain, their quarters/armory sites)

    private @Nullable Selectable<?> chase_target;
    private @Nullable Player chase_owner;
    private float chase_start = -1000f;
    private float last_chase_end = -1000f;
    private final Map<@NonNull Selectable<?>, Float> chase_banned = new LinkedHashMap<>();
    /** Per enemy player: {finished quarters + armories, quarters + armory sites}. Rebuilt by countBases(). */
    private final Map<@NonNull Player, int @NonNull []> qa_counts = new LinkedHashMap<>();

    private void countBases() {
        qa_counts.clear();
        for (Building b : ai.intel().enemy_buildings) {
            int id = b.getTemplate().getTemplateID();
            if (b.isDead() || (id != com.oddlabs.tt.model.Race.BUILDING_QUARTERS
                    && id != com.oddlabs.tt.model.Race.BUILDING_ARMORY))
                continue;
            qa_counts.computeIfAbsent(b.getOwner(), k -> new int[2])[b.isComplete() ? 0 : 1]++;
        }
    }

    /** Killing its chieftain and razing its quarters/armory sites puts p out: homeless and at most 8 other units. */
    private boolean huntable(@NonNull Player p) {
        int[] c = qa_counts.getOrDefault(p, new int[2]);
        if (c[0] > 0)
            return false;
        int after = p.getUnitCountContainer().getNumSupplies() - (p.hasActiveChieftain() ? 1 : 0);
        if (after > 8)
            return false;
        return p.hasActiveChieftain() || c[1] > 0;
    }

    private boolean clearForChase(@NonNull Selectable<?> t, int @NonNull [] from, int @Nullable [] army_c) {
        Strategy s = ai.strategy();
        Intel intel = ai.intel();
        int x = t.getGridX();
        int y = t.getGridY();
        if (MapAnalysis.dist2(from[0], from[1], x, y) > s.chief_hunt_range * s.chief_hunt_range) {
            ai.aiLog().count("hunt_skip_far");
            return false;
        }
        if (inDeadRegion(x, y) || stalled_targets.containsKey(t))
            return false;
        if (Combat.countNear(intel.enemy_warriors, x, y, 15) > s.chief_hunt_escort) {
            ai.aiLog().count("hunt_skip_escort");
            return false;
        }
        for (Building tw : intel.enemy_towers)
            if (Intel.isTowerActive(tw) && MapAnalysis.dist2(tw.getGridX(), tw.getGridY(), x, y) <= 22 * 22) {
                ai.aiLog().count("hunt_skip_tower");
                return false;
            }
        // Near the army its own fights have him; near our buildings the towers and the home defense do.
        if (army_c != null && MapAnalysis.dist2(army_c[0], army_c[1], x, y) <= 25 * 25) {
            ai.aiLog().count("hunt_skip_army");
            return false;
        }
        for (Selectable<?> sel : ai.owner().getUnits().getSet())
            if (sel instanceof Building b && !b.isDead() && MapAnalysis.dist2(b.getGridX(), b.getGridY(), x,
                    y) <= 25 * 25) {
                        ai.aiLog().count("hunt_skip_base");
                        return false;
                    }
        return true;
    }

    private void considerChase() {
        Strategy s = ai.strategy();
        Intel intel = ai.intel();
        Role source = mode == Mode.ATTACK ? Role.ATTACK : mode == Mode.HOME && threat_level < 2 ? Role.ARMY : null;
        if (source == null)
            return;
        List<Unit> pool = new ArrayList<>();
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            Unit u = e.getKey();
            WarriorState st = intel.warrior_states.get(u);
            if (e.getValue() == source && Intel.warriorType(u) == WarriorType.IRON && st != WarriorState.STUNNED
                    && st != WarriorState.ENTER && st != WarriorState.FIGHT)
                pool.add(u);
        }
        if (pool.size() < s.chief_hunt_size + (source == Role.ATTACK ? 12 : 6))
            return;
        int[] army_c = source == Role.ATTACK ? centroid(pool) : null;
        int[] from = army_c != null ? army_c : new int[]{staging_x, staging_y};
        countBases();
        chase_banned.entrySet().removeIf(e -> e.getKey().isDead() || ai.time() - e.getValue() > 120f);
        Selectable<?> best = null;
        int best_d = Integer.MAX_VALUE;
        for (Unit ch : intel.enemy_chieftains) {
            if (ch.isDead() || chase_banned.containsKey(ch) || !huntable(ch.getOwner()) || !clearForChase(ch, from,
                    army_c))
                continue;
            int d = MapAnalysis.dist2(from[0], from[1], ch.getGridX(), ch.getGridY());
            if (d < best_d) {
                best_d = d;
                best = ch;
            }
        }
        if (s.hunt_sites)
            for (Building b : intel.enemy_buildings) {
                int id = b.getTemplate().getTemplateID();
                if (b.isDead() || b.isComplete() || chase_banned.containsKey(b)
                        || (id != com.oddlabs.tt.model.Race.BUILDING_QUARTERS
                                && id != com.oddlabs.tt.model.Race.BUILDING_ARMORY)
                        || !huntable(b.getOwner()) || !clearForChase(b, from, army_c))
                    continue;
                int d = MapAnalysis.dist2(from[0], from[1], b.getGridX(), b.getGridY());
                if (d < best_d) {
                    best_d = d;
                    best = b;
                }
            }
        if (best == null)
            return;
        Selectable<?> t = best;
        pool.sort((a, b) -> Integer.compare(MapAnalysis.dist2(a.getGridX(), a.getGridY(), t.getGridX(), t.getGridY()),
                MapAnalysis.dist2(b.getGridX(), b.getGridY(), t.getGridX(), t.getGridY())));
        for (Unit u : pool.subList(0, s.chief_hunt_size)) {
            roles.put(u, Role.CHASE);
            last_order.put(u, ai.time());
            ai.owner().setTarget(Selectable.newArray(u), t, Action.ATTACK, true);
        }
        chase_target = t;
        chase_owner = t.getOwner();
        chase_start = ai.time();
        ai.aiLog().count(t instanceof Unit ? "hunt_start_chief" : "hunt_start_site");
        int dist = (int) Math.sqrt(best_d);
        ai.log(String.format("chase: %d on %s of %s at %d,%d, %d cells out", s.chief_hunt_size,
                t instanceof Unit ? "chieftain" : "site", t.getOwner().getPlayerInfo().getName(), t.getGridX(),
                t.getGridY(), dist));
    }

    /** The owner's next blocker within {@code cells} of c: its chieftain, or (hunt_sites) a quarters/armory site. */
    private @Nullable Selectable<?> nextBlocker(@NonNull Player owner, int @NonNull [] c, int cells) {
        Intel intel = ai.intel();
        countBases();
        if (!huntable(owner))
            return null;
        Selectable<?> best = null;
        int best_d = cells * cells;
        List<Selectable<?>> candidates = new ArrayList<>();
        for (Unit ch : intel.enemy_chieftains)
            if (!ch.isDead() && ch.getOwner() == owner)
                candidates.add(ch);
        if (ai.strategy().hunt_sites)
            for (Building b : intel.enemy_buildings) {
                int id = b.getTemplate().getTemplateID();
                if (!b.isDead() && !b.isComplete() && b.getOwner() == owner
                        && (id == com.oddlabs.tt.model.Race.BUILDING_QUARTERS
                                || id == com.oddlabs.tt.model.Race.BUILDING_ARMORY))
                    candidates.add(b);
            }
        for (Selectable<?> t : candidates) {
            int d = MapAnalysis.dist2(c[0], c[1], t.getGridX(), t.getGridY());
            if (d <= best_d && !chase_banned.containsKey(t) && clearForChase(t, c, null)) {
                best_d = d;
                best = t;
            }
        }
        return best;
    }

    private void chase() {
        List<Unit> squad = new ArrayList<>();
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.CHASE)
                squad.add(e.getKey());
        if (squad.isEmpty())
            return;
        Strategy s = ai.strategy();
        Intel intel = ai.intel();
        Selectable<?> t = chase_target;
        int[] c = centroid(squad);
        if (t == null || t.isDead()) {
            if (t != null) {
                ai.aiLog().count(t instanceof Unit ? "hunt_kill" : "hunt_site_razed");
                ai.log("chase: " + (t instanceof Unit ? "chieftain" : "site") + " of " + t.getOwner().getPlayerInfo().getName() + " done");
            }
            Selectable<?> next = chase_owner == null ? null : nextBlocker(chase_owner, c, 60);
            if (next != null) {
                chase_target = next;
                chase_start = ai.time();
                ai.aiLog().count("hunt_chain");
                return;
            }
            endChase(squad);
            return;
        }
        float ours = 0f;
        for (Unit u : squad)
            ours += Combat.value(u);
        float danger = Combat.strengthNear(intel.enemy_warriors, c[0], c[1], 24);
        for (Unit ch : intel.enemy_chieftains)
            if (ch != t && !ch.isDead() && MapAnalysis.dist2(ch.getGridX(), ch.getGridY(), c[0], c[1]) <= 24 * 24)
                danger += Combat.value(ch);
        for (Building tw : intel.enemy_towers)
            if (!tw.isDead() && MapAnalysis.dist2(tw.getGridX(), tw.getGridY(), c[0], c[1]) <= 10 * 10)
                danger += enemyTowerValue(tw);
        String abort = danger > .8f * ours ? "hunt_abort_danger" : ai.time() - chase_start > s.chief_hunt_time ? "hunt_abort_time" : squad.size() < 3 ? "hunt_abort_small" : Combat.countNear(
                intel.enemy_warriors, t.getGridX(), t.getGridY(),
                15) > s.chief_hunt_escort + 2 ? "hunt_abort_escort" : null;
        if (abort == null) {
            countBases();
            if (!huntable(t.getOwner()))
                abort = "hunt_abort_changed";
        }
        if (abort != null) {
            ai.aiLog().count(abort);
            chase_banned.put(t, ai.time());
            endChase(squad);
            return;
        }
        for (Unit u : squad) {
            WarriorState st = intel.warrior_states.get(u);
            if (st == WarriorState.FIGHT || st == WarriorState.STUNNED || st == WarriorState.ENTER)
                continue;
            Float last = last_order.get(u);
            if (last != null && ai.time() - last < 2f)
                continue;
            last_order.put(u, ai.time());
            ai.owner().setTarget(Selectable.newArray(u), t, Action.ATTACK, true);
        }
    }

    private void endChase(@NonNull List<@NonNull Unit> squad) {
        Role back = mode == Mode.ATTACK ? Role.ATTACK : Role.ARMY;
        for (Unit u : squad) {
            roles.put(u, back);
            if (back == Role.ARMY)
                move(u, staging_x, staging_y);
        }
        chase_target = null;
        chase_owner = null;
        last_chase_end = ai.time();
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
                danger += enemyTowerValue(t);
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
        java.util.Set<Unit> targeted = ai.strategy().micro_targets ? assignTargets(units, enemies) : java.util.Set.of();
        for (Unit u : units) {
            if (targeted.contains(u))
                continue;
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

    /** Cells within which a warrior can throw at an enemy right away, or after a step. */
    private static final int THROW_CELLS = 9;

    /**
     * Gives each warrior with enemies in reach its own target: the one worth most times the chance to hit it times
     * the chance it is still standing after the throws already on their way to it. A hit kills, so every axe thrown
     * at an enemy already doomed is wasted. Returns the warriors that have a target now.
     */
    private java.util.@NonNull Set<@NonNull Unit> assignTargets(@NonNull List<@NonNull Unit> units,
            @NonNull List<? extends Selectable<?>> enemies) {
        java.util.Set<Unit> targeted = new java.util.LinkedHashSet<>();
        hunt_targets.entrySet().removeIf(e -> e.getKey().isDead() || e.getValue().isDead());
        List<Unit> foes = new ArrayList<>();
        for (Selectable<?> e : enemies)
            if (e instanceof Unit u && !u.isDead())
                foes.add(u);
        if (foes.isEmpty())
            return targeted;
        Map<Unit, Float> survive = new LinkedHashMap<>();
        for (Unit w : units) {
            Unit t = hunt_targets.get(w);
            if (t != null && w.getCurrentController() instanceof HuntController)
                survive.merge(t, 1f - hitChance(w, t), (a, b) -> a * b);
        }
        int r2 = THROW_CELLS * THROW_CELLS;
        for (Unit w : units) {
            WarriorState s = ai.intel().warrior_states.get(w);
            if (s == WarriorState.STUNNED || s == WarriorState.ENTER || w.isDead())
                continue;
            Unit current = hunt_targets.get(w);
            boolean hunting = current != null && w.getCurrentController() instanceof HuntController;
            if (hunting && MapAnalysis.dist2(w.getGridX(), w.getGridY(), current.getGridX(),
                    current.getGridY()) <= (THROW_CELLS + 3) * (THROW_CELLS + 3)) {
                targeted.add(w);
                continue;
            }
            Unit best = null;
            float best_score = 0f;
            float best_p = 0f;
            for (Unit e : foes) {
                if (MapAnalysis.dist2(w.getGridX(), w.getGridY(), e.getGridX(), e.getGridY()) > r2)
                    continue;
                float p = hitChance(w, e);
                float score = throwValue(w, e) * p * survival(survive, e);
                if (score > best_score) {
                    best_score = score;
                    best = e;
                    best_p = p;
                }
            }
            if (best == null)
                continue;
            if (!isMultiHit(best))
                survive.merge(best, 1f - best_p, (a, b) -> a * b);
            hunt_targets.put(w, best);
            last_order.put(w, ai.time());
            ai.intel().warrior_states.put(w, WarriorState.FIGHT);
            ai.owner().setTarget(Selectable.newArray(w), best, Action.ATTACK, true);
            targeted.add(w);
        }
        return targeted;
    }

    /**
     * Every tick: a warrior whose assigned target just died gets the best enemy within throwing reach at once, instead
     * of idling until our next round (0.5 s) or its own rescan (1-2 s, IdleController). During the throw's recovery
     * the order waits for the 2 s cycle to end, so the next throw follows without a gap.
     */
    void armyReflex() {
        if (!ai.strategy().army_reflex || hunt_targets.isEmpty())
            return;
        Intel intel = ai.intel();
        List<Unit> retarget = null;
        for (Map.Entry<Unit, Unit> e : hunt_targets.entrySet())
            if (!e.getKey().isDead() && e.getValue().isDead()) {
                if (retarget == null)
                    retarget = new ArrayList<>();
                retarget.add(e.getKey());
            }
        if (retarget == null)
            return;
        int r2 = THROW_CELLS * THROW_CELLS;
        for (Unit w : retarget) {
            hunt_targets.remove(w);
            Role role = roles.get(w);
            // Not while the army falls back, and never a warrior on its way into a tower.
            if (w.isMounted() || Intel.isStunned(w) || role == Role.TOWER
                    || (mode == Mode.RETREAT && role == Role.ATTACK))
                continue;
            Unit best = null;
            float best_score = 0f;
            for (List<Unit> group : List.of(intel.enemy_warriors, intel.enemy_chieftains, intel.enemy_peons))
                for (Unit e : group) {
                    if (e.isDead() || MapAnalysis.dist2(w.getGridX(), w.getGridY(), e.getGridX(), e.getGridY()) > r2)
                        continue;
                    int others = 0;
                    for (Unit o : hunt_targets.values())
                        if (o == e)
                            others++;
                    float score = throwValue(w, e) * hitChance(w, e) / (1 << Math.min(others, 4));
                    if (score > best_score) {
                        best_score = score;
                        best = e;
                    }
                }
            if (best == null)
                continue;
            hunt_targets.put(w, best);
            last_order.put(w, ai.time());
            ai.owner().setTarget(Selectable.newArray(w), best, Action.ATTACK, true);
            ai.aiLog().count("army_reflex");
        }
    }

    /** A chieftain takes many hits; everyone else falls to one. */
    private boolean isMultiHit(@NonNull Unit e) {
        return ai.strategy().chief_per_hit && e.getAbilities().hasAbilities(Abilities.MAGIC);
    }

    /** Chance a target is still standing after the throws already on their way; chieftains take more than one. */
    private float survival(@NonNull Map<Unit, Float> survive, @NonNull Unit e) {
        return isMultiHit(e) ? 1f : survive.getOrDefault(e, 1f);
    }

    /**
     * What one throw at an enemy is worth, in iron warriors. A chieftain's kill is worth about 25 of them, spread
     * over the hits his remaining hit points take (an axe does 2, a rock axe 1): little while he is healthy, a lot
     * when he is nearly down.
     */
    private float throwValue(@NonNull Unit thrower, @NonNull Unit e) {
        if (!isMultiHit(e))
            return Combat.lastingValue(e);
        int damage = thrower.getAbilities().hasAbilities(Abilities.THROW)
                && Intel.warriorType(thrower) != WarriorType.ROCK ? 2 : 1;
        int hits = Math.max(1, (e.getHitPoints() + damage - 1) / damage);
        return Math.min(6f, 25f / hits);
    }

    /** Cells within which a tower's garrison throws. */
    private static final int TOWER_CELLS = 15;

    /**
     * Squared reach of a garrison in grid cells: weapon 6 + tower 8 + target size 1.9 = 15.9, compared as squared grid
     * distance (Selectable.isCloseEnough): 252. The towers' own scan is a square of 14 (AttackScanFilter.TOWER_RANGE),
     * so enemies 14-15.9 cells out along the axes are only hit when told.
     */
    private int towerReach2() {
        return ai.strategy().tower_full_reach || ai.strategy().tower_gunner_reach ? 252 : TOWER_CELLS * TOWER_CELLS;
    }

    /**
     * Where a garrison throws from: the grid cell it entered by, on the ring two cells around the tower (Unit.mount
     * moves only the world position; range checks and scans use the grid cell), so the reach disc is shifted 2-2.8
     * cells towards the entry side. With tower_gunner_reach reach is measured from there, else from the centre.
     */
    private int @NonNull [] towerOrigin(@NonNull Building t, @NonNull Unit gunner) {
        return ai.strategy().tower_gunner_reach ? new int[]{gunner.getGridX(), gunner.getGridY()} : new int[]{t.getGridX(), t.getGridY()};
    }

    /** tower_self_first: enemies attacking this tower count double for it. */
    private float towerSelfFactor(@NonNull Building t, @NonNull Unit e) {
        if (!ai.strategy().tower_self_first)
            return 1f;
        com.oddlabs.tt.model.behaviour.Controller c = e.getCurrentController();
        Selectable<?> target = c instanceof com.oddlabs.tt.model.behaviour.AttackController a ? a.getTarget() : c instanceof HuntController h ? h.getTarget() : null;
        return target == t ? 2f : 1f;
    }

    /**
     * Every tick: a manned tower whose target died or left its reach gets the next one at once, instead of waiting for
     * our next round (0.5 s) or for its own rescan (1-2 s, IdleController). An order given during the throw's
     * recovery takes effect when the 2 s cycle ends, so nothing of the cycle is lost.
     */
    void towerReflex() {
        Strategy strategy = ai.strategy();
        if ((!strategy.tower_reflex && !strategy.tower_prequeue) || !strategy.tower_fire)
            return;
        Intel intel = ai.intel();
        int r2 = towerReach2();
        if (!inflight.isEmpty())
            inflight.entrySet().removeIf(e -> e.getKey().isDead() || ai.time() - e.getValue() > 2.5f);
        for (Building t : intel.towers) {
            if (t.isDead() || !t.isComplete() || t.getUnitContainer() == null || t.getUnitCount() == 0)
                continue;
            Unit gunner = ((com.oddlabs.tt.model.MountUnitContainer) t.getUnitContainer()).getUnit();
            if (gunner == null || gunner.isDead() || Intel.isStunned(gunner))
                continue;
            int[] o = towerOrigin(t, gunner);
            if (strategy.tower_prequeue && prequeue(t, gunner, o, r2))
                continue;
            if (!strategy.tower_reflex)
                continue;
            Unit current = tower_targets.get(t);
            if (current != null && !current.isDead()
                    && MapAnalysis.dist2(o[0], o[1], current.getGridX(), current.getGridY()) <= r2)
                continue;
            Unit best = bestTowerTarget(t, gunner, o, r2, null);
            if (best == null) {
                tower_targets.remove(t);
                continue;
            }
            tower_targets.put(t, best);
            ai.owner().setTarget(Selectable.newArray(t), best, Action.ATTACK, false);
            ai.aiLog().count("tower_reflex");
        }
    }

    /** Throws in flight at a 1-HP enemy that will hit (p >= .99), and when thrown: other towers leave them alone. */
    private final Map<@NonNull Unit, Float> inflight = new LinkedHashMap<>();
    /** Per tower, the attack behaviour seen last tick, and how many targets were queued in a row. */
    private final Map<@NonNull Building, com.oddlabs.tt.model.behaviour.Behaviour> tower_throws = new LinkedHashMap<>();
    private final Map<@NonNull Building, Integer> tower_queued = new LinkedHashMap<>();

    /**
     * tower_prequeue: an axe flies 20-30 m/s from the tower's centre, released 1 s into the 2 s throw; beyond ~12
     * cells (iron) the target is still alive when the throw ends, so the garrison starts another 2 s throw at a unit
     * the axe in flight will kill. On the tick a throw starts at a 1-HP enemy it will hit, the next target is queued:
     * the order waits under the running throw and is taken up the moment it ends. True if it queued one.
     */
    private boolean prequeue(@NonNull Building t, @NonNull Unit gunner, int @NonNull [] o, int r2) {
        com.oddlabs.tt.model.behaviour.Behaviour b = gunner.getCurrentBehaviour();
        if (!(b instanceof com.oddlabs.tt.model.behaviour.AttackBehaviour)) {
            tower_throws.remove(t);
            tower_queued.remove(t);
            return false;
        }
        if (tower_throws.get(t) == b)
            return false;
        tower_throws.put(t, b);
        if (!(gunner.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.AttackController a)
                || !(a.getTarget() instanceof Unit x) || x.isDead() || isMultiHit(x))
            return false;
        float px = towerHitChance(gunner, t, x);
        if (px < .99f && !ai.strategy().tower_prequeue_any)
            return false;
        // Cells the axe covers in the throw's last second: iron 25 m/s, rock 20, rubber 30 (2 m per cell).
        WarriorType type = Intel.warriorType(gunner);
        float fly = type == WarriorType.ROCK ? 9.5f : type == WarriorType.CHICKEN ? 14.5f : 12f;
        if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x.getGridX(), x.getGridY()) <= fly * fly)
            return false;
        // A sure hit dooms the target for everyone; a likely miss leaves it to the queue underneath (the order waits
        // below the running throw, and X's own controller resumes if its axe misses).
        if (px >= .99f)
            inflight.put(x, ai.time());
        int queued = tower_queued.getOrDefault(t, 0);
        if (queued >= 20)
            return false; // each queued order stays on the garrison's controller stack until the fight ends
        Unit next = bestTowerTarget(t, gunner, o, r2, x);
        if (next == null)
            return false;
        tower_queued.put(t, queued + 1);
        tower_targets.put(t, next);
        ai.owner().setTarget(Selectable.newArray(t), next, Action.ATTACK, false);
        ai.aiLog().count("tower_prequeue");
        return true;
    }

    /** The best enemy in reach for this tower, skipping one and any already doomed by an axe in flight. */
    private @Nullable Unit bestTowerTarget(@NonNull Building t, @NonNull Unit gunner, int @NonNull [] o, int r2,
            @Nullable Unit skip) {
        Unit best = null;
        float best_score = 0f;
        Map<Unit, Integer> targeted = new LinkedHashMap<>();
        for (Unit other : tower_targets.values())
            targeted.merge(other, 1, Integer::sum);
        EnemyIndex index = enemyIndex();
        int[] near = index.query(o[0], o[1], r2);
        for (int k = 0, n = index.count(); k < n; k++) {
            Unit e = index.unit(near[k]);
            if (e == skip || e.isDead() || inflight.containsKey(e))
                continue;
            int others = targeted.getOrDefault(e, 0);
            float score = throwValue(gunner, e) * towerSelfFactor(t, e) * towerHitChance(gunner, t,
                    e) / (1 << Math.min(others, 4));
            if (score > best_score) {
                best_score = score;
                best = e;
            }
        }
        return best;
    }

    /**
     * Gives each manned tower its target, as a player can order it: the enemy in range worth most times the chance
     * to hit it times the chance it survives what other towers already throw at it. Peons hacking at one of our towers
     * come first. A target is kept while it lives and stays in range.
     */
    private void towerFire() {
        if (!ai.strategy().tower_fire)
            return;
        Intel intel = ai.intel();
        tower_targets.entrySet().removeIf(e -> e.getKey().isDead() || e.getValue().isDead());
        Map<Unit, Float> survive = new LinkedHashMap<>();
        int r2 = towerReach2();
        for (Building t : intel.towers) {
            if (!t.isComplete() || t.getUnitCount() == 0)
                continue;
            Unit gunner = ((com.oddlabs.tt.model.MountUnitContainer) t.getUnitContainer()).getUnit();
            if (gunner == null || gunner.isDead() || Intel.isStunned(gunner))
                continue;
            int[] o = towerOrigin(t, gunner);
            Unit current = tower_targets.get(t);
            if (current != null && MapAnalysis.dist2(o[0], o[1], current.getGridX(), current.getGridY()) <= r2) {
                survive.merge(current, 1f - towerHitChance(gunner, t, current), (a, b) -> a * b);
                continue;
            }
            Unit best = null;
            float best_score = 0f;
            float best_p = 0f;
            EnemyIndex index = enemyIndex();
            int[] near = index.query(o[0], o[1], r2);
            for (int k = 0, n = index.count(); k < n; k++) {
                Unit e = index.unit(near[k]);
                if (e.isDead() || inflight.containsKey(e))
                    continue;
                float value = throwValue(gunner, e) * towerSelfFactor(t, e);
                if (index.isPeon(near[k]) && nearOwnTower(e))
                    value = 1.5f;
                float p = towerHitChance(gunner, t, e);
                float score = value * p * survival(survive, e);
                if (score > best_score) {
                    best_score = score;
                    best = e;
                    best_p = p;
                }
            }
            if (best == null) {
                tower_targets.remove(t);
                continue;
            }
            if (!isMultiHit(best))
                survive.merge(best, 1f - best_p, (a, b) -> a * b);
            tower_targets.put(t, best);
            ai.owner().setTarget(Selectable.newArray(t), best, Action.ATTACK, false);
        }
    }

    /**
     * What a tower should throw at now: its current target while alive and in reach, else the enemy in reach worth
     * most (as towerFire scores them, without the shared survival bookkeeping), or null.
     */
    @Nullable
    Unit towerTargetFor(@NonNull Building t, @NonNull Unit gunner) {
        Intel intel = ai.intel();
        int r2 = towerReach2();
        int[] o = towerOrigin(t, gunner);
        Unit current = tower_targets.get(t);
        if (current != null && !current.isDead()
                && MapAnalysis.dist2(o[0], o[1], current.getGridX(), current.getGridY()) <= r2)
            return current;
        Unit best = null;
        float best_score = 0f;
        EnemyIndex index = enemyIndex();
        int[] near = index.query(o[0], o[1], r2);
        for (int k = 0, n = index.count(); k < n; k++) {
            Unit e = index.unit(near[k]);
            if (e.isDead())
                continue;
            float score = throwValue(gunner, e) * towerHitChance(gunner, t, e);
            if (score > best_score) {
                best_score = score;
                best = e;
            }
        }
        if (best != null)
            tower_targets.put(t, best);
        return best;
    }

    private boolean nearOwnTower(@NonNull Unit e) {
        for (Building t : ai.intel().towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), e.getGridX(), e.getGridY()) <= 3 * 3)
                return true;
        return false;
    }

    /** A tower's garrison throws with three times the usual accuracy. */
    private float towerHitChance(@NonNull Unit gunner, @NonNull Building tower, @NonNull Unit dst) {
        MapAnalysis map = ai.map();
        float dz = map.height(tower.getGridX(), tower.getGridY()) - map.height(dst.getGridX(), dst.getGridY());
        float terrain = Math.clamp(dz / 80f, -.25f, .25f);
        float p = 3f * (gunner.getOwner().getHitBonus() + terrain + baseHitChance(
                gunner)) * (1f - dst.getDefenseChance());
        return Math.clamp(p, 0f, 1f);
    }

    private static float baseHitChance(@NonNull Unit src) {
        if (!src.getAbilities().hasAbilities(Abilities.THROW))
            return .2f;
        return switch (Intel.warriorType(src)) {
            case ROCK -> .5f;
            case IRON -> .75f;
            case CHICKEN -> .95f;
        };
    }

    /** Chance that a throw from src hits dst, as the game rolls it. */
    private float hitChance(@NonNull Unit src, @NonNull Unit dst) {
        float base;
        if (!src.getAbilities().hasAbilities(Abilities.THROW))
            base = .2f;
        else
            base = switch (Intel.warriorType(src)) {
                case ROCK -> .5f;
                case IRON -> .75f;
                case CHICKEN -> .95f;
            };
        MapAnalysis map = ai.map();
        float dz = map.height(src.getGridX(), src.getGridY()) - map.height(dst.getGridX(), dst.getGridY());
        float terrain = Math.clamp(dz / 80f, -.25f, .25f);
        float p = (src.getOwner().getHitBonus() + terrain + base) * (1f - dst.getDefenseChance());
        return Math.clamp(p, 0f, 1f);
    }

    /**
     * A stunned unit has no chance to dodge while the stun controller is on top; an order replaces the controller and
     * the stun behaviour still keeps it frozen, so each of our stunned warriors is ordered to stand its ground once.
     */
    private void restoreDodge() {
        if (!ai.strategy().restore_dodge)
            return;
        dodge_orders.keySet().removeIf(Unit::isDead);
        for (Unit w : ai.intel().warriors) {
            if (!Intel.isDefenseless(w))
                continue;
            Float last = dodge_orders.get(w);
            if (last != null && ai.time() - last < ai.strategy().restore_dodge_gap)
                continue;
            dodge_orders.put(w, ai.time());
            queueOrder(w, w.getGridX(), w.getGridY(), true);
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
            ai.landscapeOrder(units.toArray(new Selectable<?>[0]), x, y,
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

    /** An enemy tower's fighting value as the attack decisions count it. */
    private float enemyTowerValue(@NonNull Building tower) {
        return Combat.towerValue(tower) * ai.strategy().tower_weight;
    }

    float threatStrength() {
        return threat_strength;
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
                        armyStrength(), threat_level, threat_strength) + parkedStatus();
    }

    /**
     * Idle enemy warriors near our base (within 45 cells of a building of ours): out of reach of our manned towers /
     * in reach / how many of those out of reach a tower 11+ cells from every enemy could reach (15 cells).
     */
    private @NonNull String parkedStatus() {
        Intel intel = ai.intel();
        List<Building> own = new ArrayList<>(intel.armories);
        own.addAll(intel.quarters);
        own.addAll(intel.towers);
        int out = 0;
        int in = 0;
        int[] by_building = new int[4];
        int[] by_tower = new int[4];
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead() || !(e.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.IdleController)
                    || e.getCurrentController() != e.getPrimaryController())
                continue;
            boolean near = false;
            for (Building b : own)
                if (!b.isDead() && MapAnalysis.dist2(b.getGridX(), b.getGridY(), e.getGridX(),
                        e.getGridY()) <= 45 * 45) {
                            near = true;
                            break;
                        }
            if (!near)
                continue;
            boolean reach = false;
            for (Building t : intel.towers)
                if (Intel.isTowerActive(t)
                        && MapAnalysis.dist2(t.getGridX(), t.getGridY(), e.getGridX(), e.getGridY()) <= 15 * 15) {
                            reach = true;
                            break;
                        }
            if (reach)
                in++;
            else
                out++;
            int nb = Integer.MAX_VALUE;
            for (Building b : own)
                if (!b.isDead())
                    nb = Math.min(nb, MapAnalysis.dist2(b.getGridX(), b.getGridY(), e.getGridX(), e.getGridY()));
            int nt = Integer.MAX_VALUE;
            for (Building t : intel.towers)
                if (Intel.isTowerActive(t))
                    nt = Math.min(nt, MapAnalysis.dist2(t.getGridX(), t.getGridY(), e.getGridX(), e.getGridY()));
            by_building[nb <= 8 * 8 ? 0 : nb <= 16 * 16 ? 1 : nb <= 28 * 28 ? 2 : 3]++;
            by_tower[nt <= 15 * 15 ? 0 : nt <= 25 * 25 ? 1 : nt <= 45 * 45 ? 2 : 3]++;
        }
        int manned = 0;
        for (Building t : intel.towers)
            if (Intel.isTowerActive(t))
                manned++;
        return " park=" + out + "/" + in + " man=" + manned + "/" + intel.towers.size() + " pb=" + java.util.Arrays.toString(
                by_building).replace(" ", "") + " pt=" + java.util.Arrays.toString(by_tower).replace(" ", "");
    }
}
