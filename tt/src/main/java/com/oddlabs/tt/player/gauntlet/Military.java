package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployType;
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
    private static final float REORDER_PERIOD_TICKS = 125f; // 2.5 s

    private final @NonNull GauntletAI ai;
    private final Map<@NonNull Unit, @NonNull Role> roles = new LinkedHashMap<>();
    private final Map<@NonNull Unit, @NonNull Building> tower_assignments = new LinkedHashMap<>();
    private final Map<@NonNull Unit, Float> last_order = new LinkedHashMap<>();
    private final Map<@NonNull Unit, Float> chief_orders = new LinkedHashMap<>();
    private final Map<@NonNull Unit, int @NonNull []> last_spots = new LinkedHashMap<>();
    private final Map<Long, List<@NonNull Unit>> pending_orders = new LinkedHashMap<>();
    private final java.util.ArrayDeque<float @NonNull []> enemy_history = new java.util.ArrayDeque<>();
    /** retire: warriors lent to the economy to raze a building of ours, out of every role until when given. */
    private final Map<@NonNull Unit, Float> lent = new LinkedHashMap<>();

    private @NonNull Mode mode = Mode.HOME;
    private int staging_x;
    private int staging_y;
    private float staging_time = -5000f;

    // Threat to the base, recomputed every tick.
    private final List<@NonNull Unit> threats = new ArrayList<>();
    /** Enemy peons raiding the base, kept apart from the threats so that they do not draw the army about. */
    private final List<@NonNull Unit> raiders = new ArrayList<>();
    private float threat_strength;
    private float base_threat_strength;
    private int threat_x;
    private int threat_y;
    private int threat_level;
    private int last_logged_threat;
    private boolean last_logged_engage;
    /** 1 while defenders are engaged with the current threat, -1 while they have fallen back, 0 for a new threat. */
    private int engage_state;

    // Attack.
    private @Nullable Selectable<?> target;
    /**
     * Targets whose attack stalled (the army could not get there: a site on ground it cannot reach), and when. Target
     * choice skips them for STALL_MEMORY_TICKS game ticks, so the army moves on instead of marching at them for hours
     * (the N=10 draw gfinal-vs10-hv s93: 5 hours of attacks on an unreachable site while two remnant copies lived on).
     */
    private final Map<@NonNull Selectable<?>, Float> stalled_targets = new LinkedHashMap<>();
    private static final float STALL_MEMORY_TICKS = 30000f; // 600 s
    /**
     * Regions the army could not enter (the stalled target's distance field did not reach our staging point), and when
     * found: every target inside one is skipped too (N=10 s93: a copy's 20 peons stuck with a site in a 252-cell pocket
     * drew every re-target of the army). At most three are kept.
     */
    private final List<@NonNull DistanceField> dead_regions = new ArrayList<>();
    private final List<@NonNull Float> dead_region_times = new ArrayList<>();

    /**
     * Whether the target lies in a region the army found it cannot enter: a unit by the cells within 2 of it, a
     * building (whose footprint no field but its own reaches) by those within deadReach of its centre.
     */
    private boolean inDeadRegion(@NonNull Selectable<?> t) {
        return inDeadRegion(t.getGridX(), t.getGridY(), deadReach(t));
    }

    private boolean inDeadRegion(int x, int y, int reach) {
        for (DistanceField f : dead_regions)
            if (f.getAround(x, y, reach) != DistanceField.UNREACHABLE)
                return true;
        return false;
    }

    /**
     * The cells around t's centre that the dead-region test looks at: 2 for a unit; for a building dead_region_reach
     * (from pocket_from_ticks on, else 2), but no farther than the ring around its footprint (placing size - 1: 4 for
     * a quarters' or armory's 7 x 7, 2 for a tower's 3 x 3, LandBuilding.occupy; never below 2), so that a tower
     * across a thin wall from a dead pocket stays a target.
     */
    private int deadReach(@NonNull Selectable<?> t) {
        if (!(t instanceof Building b))
            return 2;
        Strategy s = ai.strategy();
        int reach = ai.now() >= s.pocket_from_ticks ? s.dead_region_reach : 2;
        return Math.min(reach, Math.max(2, b.getTemplate().getPlacingSize() - 1));
    }

    /** The copy whose buildings the attacks go after first while it is alive (focus_bonus). */
    private com.oddlabs.tt.player.@Nullable Player focus_owner;
    /** ring_sweep: until when the home army sweeps the parked ring (-1: not sweeping). */
    private float sweep_until = -1f;
    /** ring_sweep: the parked ring's strength when the sweep began. */
    private float sweep_ring_start;
    /** When the number of living copies last fell, and that number (ring_sweep's quiet test). */
    private float last_out_time;
    private int last_alive = -1;
    /**
     * allin_ticks: when a threat last stood at our base (threat level 2), and whether the attack under way is all-in.
     */
    private float last_base_threat;
    private boolean all_in;
    /** push_ticks: when the last push mustered. */
    private float last_push = -1e9f;
    /** mopup: counted and logged once, when it first acts. */
    private boolean mopup_seen;
    /** last_stand: when the last orders went out, and whether it was counted and logged. */
    private float last_stand_order = -5000f;
    private boolean last_stand_seen;
    /** Log only: chooseTarget records its best candidates while this is non-null (the muster's explanation). */
    private @Nullable List<@NonNull String> explain;
    /** frozen_last: the frozen copies a choice has passed over at least once (counted once each). */
    private final List<com.oddlabs.tt.player.@NonNull Player> frozen_deferred = new ArrayList<>();

    /**
     * remnant_ladder_ticks: a target the muster may try when its best one fails the gate, its rung (the ladder goes
     * rung by rung, RUNG_*), its score within the rung (lower first), its defense (defenseFor), and whether it is a
     * homeless copy's site or remnant (walkLadder's own; the attack then finishes that copy, remnantNext).
     */
    private record Rung(@NonNull Selectable<?> t, int rung, float score, float defense, boolean remnant) {
        /** A building target choice scored. */
        Rung(@NonNull Selectable<?> t, int rung, float score, float defense) {
            this(t, rung, score, defense, false);
        }
    }

    /** decapitate: the campaign's targets (quarters, a copy's armory that may still send waves, chieftains), first. */
    private static final int RUNG_DECAP = -1;
    /** The other buildings target choice scored, by its score. */
    private static final int RUNG_BUILDING = 0;
    /** Homeless copies' quarters and armory sites (never a frozen site). */
    private static final int RUNG_SITE = 1;
    /** Remnants of homeless copies with no other copy's fighter within REMNANT_ALONE_CELLS, then the others. */
    private static final int RUNG_ALONE = 2;
    private static final int RUNG_CROWDED = 3;
    /**
     * frozen_last's and the wedge memory's buildings, which target choice takes only when nothing else is left, and
     * the sites and remnants whose way from the staging point runs through a remembered wedge (wedgeOn).
     */
    private static final int RUNG_FROZEN = 4;
    private static final int RUNG_WEDGED = 5;
    /** remnant_ladder_ticks: cells within which another copy's warrior or chieftain makes a remnant crowded. */
    private static final int REMNANT_ALONE_CELLS = 20;
    /** remnant_ladder_ticks: chooseTarget records each building it scores while this is non-null. */
    private @Nullable List<@NonNull Rung> scored;
    /** remnant_ladder_ticks: chooseTarget scored no building and fell back on the nearest enemy unit. */
    private boolean ladder_fallback;
    /** remnant_ladder_ticks: the site or remnant the muster took from the ladder, and the copy the attack finishes. */
    private @Nullable Selectable<?> ladder_target;
    private @Nullable Player ladder_owner;
    private float last_ladder_log = -5000f;
    /**
     * decapitate: the campaign target chooseTarget (or the muster's ladder) returned last, null when it returned
     * another; whether that passed over a better-scoring other target; why it was taken (log only).
     */
    private @Nullable Selectable<?> decap_pick;
    private boolean decap_changed;
    private @Nullable String decap_why;
    /**
     * decapitate: when chooseTarget returned a campaign target, the best other building it scored (after the
     * frozen_last and wedge fallbacks), which the muster tries when the campaign's best fails the gate and no remnant
     * ladder runs (decapitate_alt); null otherwise.
     */
    private @Nullable Selectable<?> decap_alt;
    /** decapitate: the copies seen live since they last were not, and those counted pacified (once each). */
    private final List<@NonNull Player> decap_live = new ArrayList<>();
    private final List<@NonNull Player> decap_pacified = new ArrayList<>();
    /** decapitate: when the attack last computed its chieftain target's field again, and at most how often. */
    private float last_decap_follow = -5000f;
    private static final float DECAP_FOLLOW_TICKS = 250f; // 5 s
    private int target_x;
    private int target_y;
    private @Nullable DistanceField target_field;
    private float attack_initial_strength;
    /** worn_basis 1: the attacking army's peak strength since the launch. */
    private float worn_peak;
    /**
     * worn_basis 2: (time, strength) of the attacking army, each strength lower than every one before it, so the first
     * is the peak of the last worn_window_ticks game ticks.
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
    /**
     * stall_cap: when the attack last gained 20 m on its target or killed stall_cap_kills units, and our kills then.
     */
    private float cap_progress_time;
    private int cap_progress_kills;
    /** stall_cap: stalls in a row with no gain or kills between them; the second walks the army home to re-form. */
    private int cap_strikes;
    /**
     * stall_engaged_ticks, the wedge watchdog (wedgeWatch): the target at its last round (null: none since the launch),
     * when it last saw progress and its strikes since, the field and the pivot distance on it (or the straight-line
     * meters to the target) that gains are measured from, the target's hit points (-1: not a building), the enemy
     * units seen within 30 cells of the army since the last progress (in the order first seen; only counted), and the
     * attackers at the last progress.
     */
    private @Nullable Selectable<?> engaged_target;
    private float engaged_time;
    private int engaged_strikes;
    private @Nullable DistanceField engaged_field;
    private int engaged_dist = DistanceField.UNREACHABLE;
    private int engaged_line;
    private int engaged_hp = -1;
    private final java.util.LinkedHashSet<@NonNull Unit> engaged_foes = new java.util.LinkedHashSet<>();
    private final List<@NonNull Unit> engaged_ours = new ArrayList<>();

    /**
     * stall_engaged_ticks: a wedge the watchdog walked the army home from, at the cell of the attacker nearest the
     * army's centre, when, the field (with corner cuts) from it, and whether target choice passed over a building for
     * it or fell back on one (each logged once).
     */
    private static final class Wedge {
        final int x;
        final int y;
        final float time;
        final @NonNull DistanceField field;
        boolean avoided;
        boolean fallback;

        Wedge(int x, int y, float time, @NonNull DistanceField field) {
            this.x = x;
            this.y = y;
            this.time = time;
            this.field = field;
        }
    }

    /** stall_engaged_ticks: the remembered wedges, oldest first, at most three. */
    private final List<@NonNull Wedge> wedges = new ArrayList<>();
    /**
     * A target's way from the staging point runs through a wedge when going through the wedge's cell costs at most
     * this much more (field meters: two legs of 12 cells at 2 a cell).
     */
    private static final int WEDGE_DETOUR = 48;
    /**
     * The field (with corner cuts) from the staging point, for the wedge test and retreat_cap_ticks, kept until the
     * staging point moves or STAGING_FIELD_TICKS pass (trees fall and buildings rise), and when it was computed.
     */
    private @Nullable DistanceField staging_field;
    private float staging_field_time;
    private static final float STAGING_FIELD_TICKS = 3000f; // 60 s, as Economy's armory_field
    /**
     * retreat_cap_ticks: whether this retreat has been measured yet, the most attackers home in it, the mean walking
     * meters to the staging point of those still out at the last progress, and when that was.
     */
    private boolean retreat_measured;
    private int retreat_best_home;
    private int retreat_dist = DistanceField.UNREACHABLE;
    private float retreat_time;
    /**
     * retreat_cap_ticks: the attackers a capped retreat left out (more than 14 cells from the staging point), in the
     * order of the roles. While one is more than STRANDED_CELLS from the staging point it stays out of the musters'
     * strength and gathering and out of the launches (walking home all the while); updateRoles drops it once it is
     * dead, home (within 14 cells) or attacking again (a reinforcement that reached the army).
     */
    private final List<@NonNull Unit> stranded = new ArrayList<>();
    private static final int STRANDED_CELLS = 30;
    private int best_target_dist = Integer.MAX_VALUE;
    /** corner_fields: target_field was computed with corner cuts. */
    private boolean target_corner;
    /**
     * sealed_progress: the least straight-line distance (m) from the army's centre to the target while no unit stood
     * on the target field, since the last new target, and whether that march has been logged.
     */
    private int best_line_dist = Integer.MAX_VALUE;
    private boolean sealed_logged;
    private int @NonNull [] hold_spot = new int[2];
    private int last_enemy_d2 = Integer.MAX_VALUE;
    /** hold_closing 1: (time, x, y) of the enemy group the attack last weighed a hold for, over the last 4 s. */
    private final java.util.ArrayDeque<float @NonNull []> hold_groups = new java.util.ArrayDeque<>();
    private float last_hold_skip = -5000f;
    private float last_charge_log = -5000f;
    /** The enemy each warrior was last sent after with a direct attack order. */
    private final Map<@NonNull Unit, @NonNull Unit> hunt_targets = new LinkedHashMap<>();
    private final Map<@NonNull Unit, Float> dodge_orders = new LinkedHashMap<>();
    private final Map<@NonNull Building, @NonNull Unit> tower_targets = new LinkedHashMap<>();
    private final Map<@NonNull Unit, Float> sapper_orders = new LinkedHashMap<>();
    private float last_pillage_log = -5000f;
    /** When each enemy chieftain was last seen casting, judged by our units getting stunned around him. */
    private final Map<@NonNull Unit, Float> enemy_casts = new LinkedHashMap<>();
    /** enemy_first_seen: when each enemy chieftain was first seen on the field. */
    private final Map<@NonNull Unit, Float> enemy_chief_seen = new LinkedHashMap<>();
    private int own_stunned_before;
    /** Game ticks an enemy chieftain's spell takes to recharge, as far as the AI assumes. */
    private static final float SPELL_RECHARGE_TICKS = 2000f; // 40 s
    /** Game ticks from the first frame an enemy viking chieftain is seen raising his horn to his stun going off. */
    private static final float STUN_WINDUP_TICKS = 185f; // 3.7 s
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
    private float last_blast_play = -5000f;
    private float siege_progress = -1f;
    private float last_pull_log = -5000f;
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
    private static final float FOG_RELEASE_TICKS = 182f; // 3.64 s
    private static final float FOG_TIME_TICKS = 1000f; // 20 s
    private static final float FOG_RADIUS = 26f;
    private static final float FOG_OFFSET = .9f;
    /** A tower our sappers are raising by a besieged building, until it stands. */
    private @Nullable Building creep_site;
    /** Towers raised by sappers next to enemy buildings. */
    private final List<@NonNull Building> creep_towers = new ArrayList<>();
    private float last_creep_try = -5000f;
    /** Multiplies what the next attack must beat, raised by attacks that traded badly. */
    private float attack_caution = 1f;
    private float last_caution_ease;
    private int attack_kills_start;
    private int attack_losses_start;
    private boolean attack_running;
    private final Map<@NonNull Unit, Float> militia_orders = new LinkedHashMap<>();
    private float last_militia_log = -5000f;
    private float last_peon_rush = -5000f;
    /** The attack is a short strike on an enemy building in or next to our base, and ends when it falls. */
    private boolean strike;

    // Escort of forward tower builders.
    private int escort_x;
    private int escort_y;
    private float escort_time = -5000f;

    // Raid.
    private int raid_x;
    private int raid_y;
    private float raid_start = -50000f;
    private float last_raid_end = -50000f;

    /** Intel's shared index of enemies by position (towers and shepherds), brought up to date once per tick. */
    private @NonNull EnemyIndex enemyIndex() {
        return ai.intel().enemyIndex(ai.worldTicks());
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

    /**
     * retire: up to n idle iron or chicken warriors of the home army within r cells of (x, y), nearest first (ties in
     * role order), taken out of every role, and so out of defence, musters and tower manning, until released or for
     * {@code ticks} game ticks at most. None when fewer than min are at hand, while an attack is on or musters, or
     * while the base is under threat (level 2).
     */
    @NonNull
    List<@NonNull Unit> lend(int x, int y, int n, int min, int r, float ticks) {
        List<Unit> out = new ArrayList<>();
        if (mode != Mode.HOME || wantsEverything())
            return out;
        List<Unit> candidates = new ArrayList<>();
        List<Integer> dists = new ArrayList<>();
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            Unit u = e.getKey();
            if (!lendable(u, e.getValue()))
                continue;
            int d = MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY());
            if (d > r * r)
                continue;
            int i = 0;
            while (i < dists.size() && dists.get(i) <= d)
                i++;
            candidates.add(i, u);
            dists.add(i, d);
        }
        if (candidates.size() < min)
            return out;
        for (int i = 0; i < Math.min(n, candidates.size()); i++) {
            Unit u = candidates.get(i);
            roles.remove(u);
            tower_assignments.remove(u);
            front_entry.remove(u);
            last_order.remove(u);
            lent.put(u, ai.now() + ticks);
            out.add(u);
        }
        return out;
    }

    /** retire: how many warriors lend(x, y, ..., r, ...) could take now (0 while it would lend none). */
    int lendable(int x, int y, int r) {
        if (mode != Mode.HOME || wantsEverything())
            return 0;
        int n = 0;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            Unit u = e.getKey();
            if (lendable(u, e.getValue()) && MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()) <= r * r)
                n++;
        }
        return n;
    }

    /** retire: an idle iron or chicken warrior of the home army. */
    private boolean lendable(@NonNull Unit u, @NonNull Role role) {
        return role == Role.ARMY && !u.isDead() && !u.isMounted()
                && ai.intel().warrior_states.get(u) == WarriorState.IDLE && Intel.warriorType(u) != WarriorType.ROCK;
    }

    /** retire: lent warriors come back to the home army (updateRoles gives them the ARMY role again). */
    void release(@NonNull List<@NonNull Unit> units) {
        for (Unit u : units)
            lent.remove(u);
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
            if (Intel.isParked(u) && Math.max(Math.abs(u.getGridX() - x), Math.abs(u.getGridY() - y)) > box) {
                exempt = true;
                continue;
            }
            return true;
        }
        if (exempt)
            ai.aiLog().count("parked_exempt_econ");
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
            if (!Intel.isTowerManned(t) && !tower_assignments.containsValue(t) && !ai.economy().isDoomed(t))
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
        float wanted = Math.max(5f + ai.now() / 4500f, .75f * enemy);
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

    private float last_provoke_probe = -500f;

    /**
     * Diagnostic counters only (no orders): every 5 s in the first 15 minutes, our units standing within 15 cells of an
     * enemy quarters or armory, by peon state or warrior role. A Hard copy scans 30 m around its first quarters and
     * armory, and anything of ours there makes it deploy its armory stock and send its peons (AdvancedAI
     * nodeDefendBase), which brings its next wave forward.
     */
    private void provokeProbe() {
        if (!ai.periodDue(last_provoke_probe, 250f) || ai.now() > 45000f)
            return;
        last_provoke_probe = ai.now();
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
            ai.aiLog().count(ai.now() < 24000f ? "prov_early" : "prov_mid");
        }
    }

    void tick() {
        watchEnemyCasts();
        updateRoles();
        // last_stand: no base and no peon to build one: nothing else is left to do
        if (lastStand()) {
            lastStandTick();
            flushOrders();
            return;
        }
        provokeProbe();
        updateStaging();
        updateThreat();
        manTowers();
        // sortie_ratio: before defend, so that its evacuatePeons already leaves the peons coming out alone
        if (ai.strategy().sortie_ratio > 0f)
            sorties();
        if (threat_level > 0)
            defend();
        if (!raiders.isEmpty())
            answerRaiders();
        if (mode != Mode.ATTACK && siege_start >= 0f)
            endSiege();
        switch (mode) {
            case HOME -> {
                if (sweep_until >= 0f)
                    sweep();
                else
                    holdStaging();
            }
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
        int alive = ai.enemiesAlive();
        if (alive != last_alive) {
            if (last_alive >= 0 && alive < last_alive)
                last_out_time = ai.now();
            last_alive = alive;
        }
        if (lastStand())
            return;
        if (ai.strategy().ring_sweep)
            considerSweep();
        // decapitate: which copies went passive (counters and logs only; from the start, so none is missed)
        if (ai.strategy().decapitate)
            trackPacified();
        easeCaution();
        if (mode == Mode.HOME && threat_level < 2 && ai.strategy().strikes)
            considerStrike();
        // Against many copies the base is rarely quiet: a small raid (next to the whole army) does not hold it back.
        boolean small_threat = threat_level >= 2
                && base_threat_strength < ai.strategy().attack_threat_ratio * (ai.strategy().launch_recheck ? stagingStrength(
                        40) : armyStrength());
        if (mode == Mode.HOME && (threat_level < 2 || small_threat) && sweep_until < 0f)
            considerAttack();
        else if (mode == Mode.HOME && sweep_until < 0f && ai.now() >= next_wave_time && pushDue()) {
            // push_ticks: whatever stands at the base
            Building weakest = weakestBase();
            if (weakest != null)
                allInMuster(weakest, true);
        }
        // reinforce_threat_ratio: reinforce the attack with the base under threat too, while what stands in the base is
        // worth less than that share of our whole army.
        boolean reinforce_ok = threat_level < 2
                || base_threat_strength < ai.strategy().reinforce_threat_ratio * (armyStrength() + attackStrength());
        if (mode == Mode.ATTACK && reinforce_ok && ai.strategy().reinforce)
            considerReinforcing();
        if (mode == Mode.HOME && threat_level <= ai.strategy().raid_threat)
            considerRaid();
        if (ai.strategy().chief_hunt && countRole(Role.CHASE) == 0 && ai.now() - last_chase_end >= 500f)
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
            boolean doomed = ai.economy().isDoomed(t);
            if (u.isDead() || u.isMounted() || t.isDead() || doomed || Intel.isTowerManned(t)
                    || (intel.warrior_states.get(u) != WarriorState.ENTER && !front_entry.containsKey(u))) {
                if (!u.isDead() && !u.isMounted() && roles.get(u) == Role.TOWER)
                    roles.put(u, Role.ARMY);
                // retire: a gunner on its way into a tower being razed stops where it is.
                if (doomed && !u.isDead() && !u.isMounted())
                    ai.landscapeOrder(Selectable.newArray(u), u.getGridX(), u.getGridY(), Action.MOVE, false);
                it.remove();
            }
        }
        if (!lent.isEmpty())
            lent.entrySet().removeIf(e -> e.getKey().isDead() || ai.now() > e.getValue());
        // retreat_cap_ticks: a stranded attacker is no longer stranded once dead, home or with the army again
        if (!stranded.isEmpty())
            stranded.removeIf(u -> u.isDead() || u.isMounted() || roles.get(u) == Role.ATTACK
                    || MapAnalysis.dist2(u.getGridX(), u.getGridY(), staging_x, staging_y) <= 14 * 14);
        for (Unit w : intel.warriors) {
            if (!roles.containsKey(w) && !lent.containsKey(w))
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
        if (!ai.periodDue(staging_time, 1500f))
            return;
        staging_time = ai.now();
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
        // The cells of the peons an enemy near them threatens (not chicken hunters or shepherds), in list order, looked
        // up once instead of once per enemy.
        int np = 0;
        int[] pxs = new int[intel.peons.size()];
        int[] pys = new int[intel.peons.size()];
        for (Unit p : intel.peons) {
            PeonState s = intel.peon_states.get(p);
            if (s == PeonState.GATHER_CHICKEN || s == PeonState.SHEPHERD)
                continue;
            pxs[np] = p.getGridX();
            pys[np++] = p.getGridY();
        }
        for (Unit e : enemies) {
            if (ai.decoys().caged(e))
                continue;
            int box = parked_scan > 0 && !e.isDead() && Intel.isParked(e) ? parked_scan : Integer.MAX_VALUE;
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
                for (int k = 0; k < np; k++) {
                    if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), pxs[k], pys[k]) <= 12 * 12) {
                        if (box != Integer.MAX_VALUE && Math.max(Math.abs(e.getGridX() - pxs[k]), Math.abs(
                                e.getGridY() - pys[k])) > box) {
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
            } else if (ai.strategy().peon_militia && ai.now() < ai.strategy().militia_ticks
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
        if (threat_level == 2)
            last_base_threat = ai.now();
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
        float ours = Combat.total(defenders);
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
                engage_since = ai.now();
            else if (!engage && engage_state == 1 && ai.now() - engage_since < 150f) {
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
     * Whether an enemy chieftain counts as ready to stun in the army's fear of his spell: enemy_spell_recharge_ticks
     * after he was seen casting, and with enemy_first_seen, never seen casting, that long after he was first seen (a
     * newborn chieftain has no charge). Our own stun timing keeps enemySpellReady.
     */
    private boolean enemyThreatReady(@NonNull Unit chief) {
        if (ai.strategy().hidden_info)
            return enemySpellReady(chief);
        Float cast = enemy_casts.get(chief);
        if (cast != null)
            return ai.now() - cast >= ai.strategy().enemy_spell_recharge_ticks;
        if (!ai.strategy().enemy_first_seen)
            return true;
        Float seen = enemy_chief_seen.get(chief);
        return seen == null || ai.now() - seen >= ai.strategy().enemy_spell_recharge_ticks;
    }

    /**
     * Whether an enemy chieftain may have his spell ready. A human cannot see the recharge, so unless hidden_info is
     * set the AI assumes it is ready unless he was seen casting within the recharge time.
     */
    boolean enemySpellReady(@NonNull Unit chief) {
        if (ai.strategy().hidden_info)
            return chief.getMagicProgress(0) >= 1f || chief.getMagicProgress(1) >= 1f;
        Float cast = enemy_casts.get(chief);
        return cast == null || ai.now() - cast >= SPELL_RECHARGE_TICKS;
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
                    enemy_chief_seen.putIfAbsent(e, ai.now());
        }
        // Casting is plain to see: the chieftain stops and blows his horn.
        for (Unit e : intel.enemy_chieftains)
            if (!e.isDead() && e.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.MagicController)
                enemy_casts.put(e, ai.now());
        List<Unit> stunned = new ArrayList<>();
        for (Unit u : intel.warriors)
            if (Intel.isStunned(u))
                stunned.add(u);
        for (Unit u : intel.peons)
            if (Intel.isStunned(u))
                stunned.add(u);
        if (stunned.size() >= own_stunned_before + 2) {
            int[] c = MapAnalysis.centroid(stunned);
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
                enemy_casts.put(caster, ai.now());
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
        float now = ai.now();
        if (!dodges.isEmpty())
            dodges.values().removeIf(d -> d.until() < now);
        // The horn stays up for a while after the spell: a wind-up is over once he stops casting.
        if (!windups.isEmpty())
            windups.entrySet().removeIf(w -> w.getValue() < now && (w.getKey().isDead()
                    || !(w.getKey().getCurrentController() instanceof com.oddlabs.tt.model.behaviour.MagicController)));
        updateFogs(now);
        Intel intel = ai.intel();
        // Enemy chieftains, then ours.
        for (Unit e : intel.enemy_chieftains)
            watchCaster(e, now);
        if (intel.chieftain != null)
            watchCaster(intel.chieftain, now);
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

    /** A chieftain seen raising his horn this tick: a fog to keep out of, or an enemy stun or blast to run from. */
    private void watchCaster(@NonNull Unit e, float now) {
        if (e.isDead() || windups.containsKey(e)
                || !(e.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.MagicController))
            return;
        windups.put(e, now + STUN_WINDUP_TICKS);
        com.oddlabs.tt.model.Race vikings = ai.owner().getWorld().getRacesResources().getRace(
                com.oddlabs.tt.model.RacesResources.RACE_VIKINGS);
        if (e.getOwner().getRace() != vikings) {
            // Fog and lightning look alike until they come down: clear out at once, the fog gives 5.6 s.
            if (ai.strategy().dodge_fog)
                fogs.add(new Fog(e, e.getPositionX() + FOG_OFFSET * e.getDirectionX(),
                        e.getPositionY() + FOG_OFFSET * e.getDirectionY(), now));
        } else if (e != ai.intel().chieftain && ai.strategy().dodge_stun) {
            // With the stun cancel (Reflexes) a stun costs nothing: only the sonic blast, which hits the same
            // 36 m, is worth running from. The spell's index is set on the tick the horn is raised.
            boolean blast = e.getLastMagicIndex() == com.oddlabs.tt.model.RacesResources.INDEX_MAGIC_BLAST;
            if (blast)
                ai.aiLog().count("enemy_blast");
            if (!ai.strategy().dodge_blast_only || !ai.strategy().stun_cancel || blast)
                dodgeStun(e, now);
        }
    }

    /** Drops fogs that lifted, or that turned out to be lightning or never came down. */
    private void updateFogs(float now) {
        for (Iterator<Fog> it = fogs.iterator(); it.hasNext();) {
            Fog f = it.next();
            if (now > f.cast + FOG_RELEASE_TICKS + FOG_TIME_TICKS + 25f) {
                it.remove();
            } else if (!f.confirmed && now >= f.cast + FOG_RELEASE_TICKS + 5f) {
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
        float until = Math.min(now + 200f, f.cast + FOG_RELEASE_TICKS + FOG_TIME_TICKS);
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
        float release = now + STUN_WINDUP_TICKS;
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
            // Out by the time it goes off, with a little to spare for the path around others (speed per game tick).
            float out = STUN_RADIUS + 3f - d;
            if (out > .85f * u.getTemplate().getMetersPerSecond() / GauntletAI.TICKS_PER_SECOND * STUN_WINDUP_TICKS) {
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
            dodges.put(u, new Dodge(tx, ty, release + 15f));
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
        last_order.put(u, ai.now());
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
            int[] c = MapAnalysis.centroid(units);
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
                if (last != null && !ai.periodDue(last, 200f))
                    continue;
                chief_orders.put(u, ai.now());
                last_order.put(u, ai.now());
                ai.owner().setTarget(Selectable.newArray(u), chief, Action.ATTACK, true);
            }
        }
    }

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
        int[] c = MapAnalysis.centroid(raiders);
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
                Building shelter = close ? MapAnalysis.nearest(intel.quarters, p.getGridX(), p.getGridY()) : null;
                if (shelter != null && shelter.getUnitContainer() != null)
                    ai.owner().setTarget(Selectable.newArray(p), shelter, Action.DEFAULT, false);
            }
            return;
        }
        if (ai.now() - last_militia_log > 750f) {
            last_militia_log = ai.now();
            ai.log(militia.size() + " peons fight off " + raiders.size() + " raiding peons at " + c[0] + "," + c[1]);
        }
        for (Unit p : militia) {
            intel.peon_states.put(p, PeonState.FIGHT);
            Float last = militia_orders.get(p);
            if (last != null && !ai.periodDue(last, 150f))
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
            militia_orders.put(p, ai.now());
            ai.owner().setTarget(Selectable.newArray(p), target, Action.ATTACK, true);
        }
    }

    /**
     * Enemy peons raiding the base: a few warriors from home run them down when there are any, otherwise our peons
     * fight them off or take cover.
     */
    private void answerRaiders() {
        int[] c = MapAnalysis.centroid(raiders);
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

    /** Sparring only: the starting peons go for the enemy's peons. */
    private void peonRush() {
        if (!ai.strategy().peon_rush || ai.now() > 15000f || !ai.periodDue(last_peon_rush, 200f))
            return;
        last_peon_rush = ai.now();
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
            if (militia != null && ai.now() - militia < 250f)
                continue;
            // sortie_ratio: and so do peons out of a sortie armory.
            if (sortie_peons.containsKey(p))
                continue;
            if (!threatNear(p.getGridX(), p.getGridY(), 11))
                continue;
            // rearm_placer: a peon carrying an armory site walks on while no threat is within 6 cells.
            if (ai.economy().evacExempt(p))
                continue;
            evacuate.add(p);
        }
        // bank_guard: a main armory at its cap shelters peons only when no quarters can (Economy.guardBank).
        boolean bank_full = !evacuate.isEmpty() && ai.economy().bankFull(armory);
        // raid_evac: nor one being emptied ahead of a wave.
        if (!evacuate.isEmpty() && ai.economy().raidEvacuating(armory))
            armory = null;
        // sortie_ratio: nor one whose peons are out fighting by it.
        if (armory != null && sorties.containsKey(armory))
            armory = null;
        for (Unit p : evacuate) {
            Building shelter = null;
            int best = Integer.MAX_VALUE;
            if (armory != null && !bank_full && !threatNear(armory.getGridX(), armory.getGridY(), 6))
                shelter = armory;
            for (Building q : intel.quarters) {
                // retire: not into a quarters being razed (the main armory never is).
                if (threatNear(q.getGridX(), q.getGridY(), 6) || ai.economy().isDoomed(q))
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
    // sortie_ratio

    /** sortie_ratio: an armory's peons out fighting the threat by it: since when, and how many joined. */
    private static final class Sortie {
        final float start;
        int joined;

        Sortie(float start) {
            this.start = start;
        }
    }

    /** sortie_ratio: the armories whose peons are out, in the order their sorties began. */
    private final Map<@NonNull Building, @NonNull Sortie> sorties = new LinkedHashMap<>();
    /**
     * sortie_ratio: the peons out of each sortie armory, which evacuatePeons and the economy's allocatePeons leave
     * alone while the sortie lasts, and when each was last ordered onto the attackers.
     */
    private final Map<@NonNull Unit, @NonNull Building> sortie_peons = new LinkedHashMap<>();
    private final Map<@NonNull Unit, Float> sortie_orders = new LinkedHashMap<>();
    /** sortie_ratio: the threat to an armory is what stands within this many cells of it. */
    private static final int SORTIE_CELLS = 15;
    /**
     * sortie_ratio: a sortie starts, and the defence holds without one, by the threat within this many cells
     * (threat_look's default): the head of a wave walks in ahead of the rest (beta2-sortie smoke: a sortie on a threat
     * of 13.0 within 15 cells, 48.6 at the armory half a second later, outmatched within 28 s).
     */
    private static final int SORTIE_LOOK_CELLS = 30;
    /**
     * sortie_ratio: units an armory holds at least to sortie (the investigators' sortie_bank): a few workers by a
     * raider
     * would empty the armory and stop its forge for nothing.
     */
    private static final int SORTIE_MIN_BANK = 30;
    /** sortie_ratio: an idle or fighting peon this close to a sortie armory came out of it (or joins it there). */
    private static final int SORTIE_DOOR_CELLS = 8;

    /** sortie_ratio: whether the armory's peons are out fighting by it (allocatePeons sends nobody in). */
    boolean isSortie(@Nullable Building a) {
        return a != null && sorties.containsKey(a);
    }

    /** sortie_ratio: whether the peon is out fighting by its armory (allocatePeons leaves it alone). */
    boolean inSortie(@NonNull Unit p) {
        return sortie_peons.containsKey(p);
    }

    /**
     * sortie_ratio, every military tick: units inside a razed armory vanish with it, uncounted, and the peons by a
     * threat are sent in (evacuatePeons, allocatePeons), so the bank grows during a siege it could have broken. At
     * threat level 2 (the weapon_reserve release), a complete armory holding SORTIE_MIN_BANK or more with a threat
     * within SORTIE_CELLS (lasting values: a stun wears off before such a fight ends) sends everyone inside out onto it
     * when they tip the balance against all within SORTIE_LOOK_CELLS: its peons at Combat.PEON each, with our warriors
     * within 20 cells and the manned towers within 16 (raid_evac's defence), reach sortie_ratio of that threat, which
     * the defence alone does not (else the defenders hold it and the peons stay at work). Its weapons leave as
     * warriors, the rest as peons (no rally point: they come out idle by the door); every idle or fighting peon by the
     * door joins, and an idle one is attack-moved onto the threat nearest it. The sortie ends, and the economy takes
     * its peons back, when the armory falls or is emptied (evacuate, retire), no threat is left within SORTIE_CELLS,
     * the peons out within 20 cells, those still inside and the defence fall below half of sortie_ratio of it, or the
     * defence alone holds the threat within SORTIE_LOOK_CELLS; in the last three the peons still queued stay in.
     */
    private void sorties() {
        Intel intel = ai.intel();
        Economy economy = ai.economy();
        float ratio = ai.strategy().sortie_ratio;
        float now = ai.now();
        // Peons that came out since the last tick (and idle ones standing by) join their armory's sortie.
        if (!sorties.isEmpty())
            for (Unit p : intel.peons) {
                PeonState s = intel.peon_states.get(p);
                if ((s != PeonState.IDLE && s != PeonState.FIGHT) || sortie_peons.containsKey(p)
                        || economy.reservedPlacer(p))
                    continue;
                Map.Entry<Building, Sortie> door = null;
                int best = SORTIE_DOOR_CELLS * SORTIE_DOOR_CELLS + 1;
                for (Map.Entry<Building, Sortie> e : sorties.entrySet()) {
                    Building a = e.getKey();
                    if (a.isDead())
                        continue;
                    int d = MapAnalysis.dist2(p.getGridX(), p.getGridY(), a.getGridX(), a.getGridY());
                    if (d < best) {
                        best = d;
                        door = e;
                    }
                }
                if (door == null)
                    continue;
                sortie_peons.put(p, door.getKey());
                door.getValue().joined++;
                ai.aiLog().count("sortie_peons"); // peons that joined a sortie
            }
        for (Iterator<Map.Entry<Building, Sortie>> it = sorties.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Building, Sortie> e = it.next();
            Building a = e.getKey();
            float threat = 0f;
            float strength = 0f;
            String why = null;
            boolean standing = false;
            if (a.isDead()) {
                why = "the armory fell";
            } else if (economy.isEvacuating(a) || economy.isDoomed(a)) {
                why = "the armory is emptied";
            } else {
                standing = true;
                threat = sortieThreat(a, SORTIE_CELLS);
                strength = sortieStrength(a);
                if (threat <= 0f)
                    why = "no threat left";
                else if (strength < .5f * ratio * threat)
                    why = "outmatched";
                // the start test's own exclusion, against the same look (else the next tick starts it again)
                else if (sortieDefence(a) >= ratio * sortieThreat(a, SORTIE_LOOK_CELLS))
                    why = "the defence holds";
            }
            if (why == null)
                continue;
            it.remove();
            // The peons still queued to come out stay in (the UI's decrease button): at 0.5 s each, a big bank is still
            // walking out one by one into a fight judged lost or over (beta2-sortie smoke: about 14 of 70 after an
            // outmatched end at 28 s). Not when the armory is emptied: evacuate and retire want them out.
            int kept = 0;
            if (standing) {
                kept = a.getDeployContainer(DeployType.PEON).getNumSupplies();
                if (kept > 0) {
                    ai.owner().deployUnits(a, DeployType.PEON, -kept);
                    ai.aiLog().count("sortie_recalled"); // sortie ends that kept queued peons in
                }
            }
            int out = 0;
            for (Iterator<Map.Entry<Unit, Building>> pit = sortie_peons.entrySet().iterator(); pit.hasNext();) {
                Map.Entry<Unit, Building> pe = pit.next();
                if (pe.getValue() != a)
                    continue;
                if (!pe.getKey().isDead())
                    out++;
                sortie_orders.remove(pe.getKey());
                pit.remove();
            }
            ai.aiLog().count("sortie_end");
            if (ai.logging())
                ai.log(String.format(
                        "sortie at %d,%d ends after %.0f s (%s): %d peons joined, %d still out, %d queued kept in;" + " strength %.1f against %.1f",
                        a.getGridX(), a.getGridY(), GauntletAI.seconds(now - e.getValue().start), why,
                        e.getValue().joined, out, kept, strength, threat));
        }
        // updateThreat has run this tick: level 2 is a real attack on the base (or the threat at the main armory).
        if (threat_level >= 2)
            for (Building a : intel.armories) {
                if (a.isDead() || sorties.containsKey(a) || economy.isEvacuating(a) || economy.isDoomed(a))
                    continue;
                int inside = a.getUnitContainer().getNumSupplies();
                if (inside < SORTIE_MIN_BANK)
                    continue;
                // At the door, judged by all that comes behind it.
                float door = sortieThreat(a, SORTIE_CELLS);
                if (door <= 0f)
                    continue;
                float threat = sortieThreat(a, SORTIE_LOOK_CELLS);
                float defence = sortieDefence(a);
                float bank = Combat.PEON * inside;
                if (defence >= ratio * threat || bank + defence < ratio * threat)
                    continue;
                sorties.put(a, new Sortie(now));
                if (a.hasRallyPoint()) {
                    ai.owner().setRallyPoint(a, a); // none: the peons come out idle by the door
                    ai.aiLog().count("sortie_rally_cleared");
                }
                ai.aiLog().count("sortie_start");
                if (ai.logging())
                    ai.log(String.format(
                            "sortie: the armory at %d,%d sends its %d inside out onto a threat of %.1f within %d cells" + " (%.1f within %d; bank %.1f, defence %.1f)",
                            a.getGridX(), a.getGridY(), inside, threat, SORTIE_LOOK_CELLS, door, SORTIE_CELLS, bank,
                            defence));
            }
        if (sorties.isEmpty())
            return;
        // Everyone inside comes out, those who walked in since included.
        for (Building a : sorties.keySet())
            economy.sortieDeploy(a);
        // Idle sortie peons attack-move onto the threat nearest each, one order per cell (the game spreads a group
        // over the cells around it).
        Map<Long, List<Unit>> orders = new LinkedHashMap<>();
        for (Iterator<Map.Entry<Unit, Building>> it = sortie_peons.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Unit, Building> e = it.next();
            Unit p = e.getKey();
            PeonState s = p.isDead() ? null : intel.peon_states.get(p);
            // Dead, back inside a building, or given other work since (building, gathering, shepherding, sapping):
            // it leaves the sortie.
            if (s != PeonState.IDLE && s != PeonState.FIGHT && s != PeonState.MOVE && s != PeonState.STUNNED) {
                sortie_orders.remove(p);
                it.remove();
                continue;
            }
            Float last = sortie_orders.get(p);
            if (s != PeonState.IDLE || (last != null && !ai.periodDue(last, 50f))) // 1 s
                continue;
            Building a = e.getValue();
            Unit target = null;
            int best = Integer.MAX_VALUE;
            for (Unit t : threats) {
                if (t.isDead() || MapAnalysis.dist2(t.getGridX(), t.getGridY(), a.getGridX(),
                        a.getGridY()) > SORTIE_CELLS * SORTIE_CELLS)
                    continue;
                int d = MapAnalysis.dist2(p.getGridX(), p.getGridY(), t.getGridX(), t.getGridY());
                if (d < best) {
                    best = d;
                    target = t;
                }
            }
            if (target == null)
                continue;
            sortie_orders.put(p, now);
            ai.aiLog().count("sortie_orders"); // peons attack-moved onto the threat
            long key = ((long) target.getGridX() << 32) | (long) target.getGridY();
            orders.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        for (Map.Entry<Long, List<Unit>> e : orders.entrySet()) {
            long key = e.getKey();
            ai.landscapeOrder(e.getValue().toArray(new Selectable<?>[0]), (int) (key >> 32),
                    (int) (key & 0xffffffffL), Action.ATTACK, true);
        }
    }

    /** sortie_ratio: the lasting value of the threats within radius cells of the armory. */
    private float sortieThreat(@NonNull Building a, int radius) {
        int r2 = radius * radius;
        float s = 0f;
        for (Unit e : threats)
            if (!e.isDead() && MapAnalysis.dist2(e.getGridX(), e.getGridY(), a.getGridX(), a.getGridY()) <= r2)
                s += Combat.lastingValue(e);
        return s;
    }

    /**
     * sortie_ratio: the manned towers within 16 cells of the armory and our warriors within 20 (raid_evac's defence),
     * with the warriors in its deploy queues (a deploy order takes its workers out of the bank at once).
     */
    private float sortieDefence(@NonNull Building a) {
        Intel intel = ai.intel();
        return Combat.strengthNear(intel.towers, a.getGridX(), a.getGridY(), 16) + Combat.strengthNear(
                intel.warriors, a.getGridX(), a.getGridY(), 20) + Combat.CHICKEN * a.getDeployContainer(
                        DeployType.RUBBER_WARRIOR).getNumSupplies() + Combat.IRON * a.getDeployContainer(
                                DeployType.IRON_WARRIOR).getNumSupplies() + Combat.ROCK * a.getDeployContainer(
                                        DeployType.ROCK_WARRIOR).getNumSupplies();
    }

    /** sortie_ratio: a sortie's strength: the defence, its peons out within 20 cells, and those inside or queued. */
    private float sortieStrength(@NonNull Building a) {
        int ax = a.getGridX();
        int ay = a.getGridY();
        float s = sortieDefence(a) + Combat.PEON * (a.getUnitContainer().getNumSupplies() + a.getDeployContainer(
                DeployType.PEON).getNumSupplies());
        for (Map.Entry<Unit, Building> e : sortie_peons.entrySet()) {
            Unit p = e.getKey();
            if (e.getValue() == a && !p.isDead() && MapAnalysis.dist2(p.getGridX(), p.getGridY(), ax, ay) <= 20 * 20)
                s += Combat.value(p);
        }
        return s;
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
    private float last_reaim = -500f;

    /**
     * tower_reaim, every 2 s: a manned tower with no awake enemy within 14 cells and nothing in its reach, but idle
     * enemies (which never answer being hit) that one of the 16 cells around it would reach and its entry cell does not
     * (at least 3 more), is re-entered from that cell: the garrison throws from the cell it entered by, so its reach
     * disc moves 2-2.8 cells that way. Once per tower per 30 s.
     */
    private void reaimTowers() {
        if (!ai.strategy().tower_reaim || !ai.periodDue(last_reaim, 100f))
            return;
        last_reaim = ai.now();
        Intel intel = ai.intel();
        List<Unit> idle = new ArrayList<>();
        List<Unit> awake = new ArrayList<>();
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead())
                continue;
            (Intel.isParked(e) ? idle : awake).add(e);
        }
        if (idle.isEmpty())
            return;
        for (Building t : intel.towers) {
            if (tower_assignments.containsValue(t) || ai.economy().isDoomed(t))
                continue;
            Unit gunner = readyGunner(t);
            if (gunner == null)
                continue;
            Float last = reaimed.get(t);
            if (last != null && !ai.periodDue(last, 1500f))
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
                if (MapAnalysis.dist2(gx, gy, e.getGridX(), e.getGridY()) <= GARRISON_REACH2)
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
                        if (MapAnalysis.dist2(cx, cy, e.getGridX(), e.getGridY()) <= GARRISON_REACH2)
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
            reaimed.put(t, ai.now());
            ai.owner().exitTower(t);
            if (gunner.isDead() || gunner.isMounted())
                continue;
            roles.put(gunner, Role.TOWER);
            tower_assignments.put(gunner, t);
            front_entry.put(gunner, new float[]{best[0], best[1], ai.now() - 300f}); // 6 s to get there
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
            if (u.isDead() || t == null || t.isDead() || Intel.isTowerManned(t) || ai.economy().isDoomed(t)) {
                it.remove();
                continue;
            }
            float[] f = e.getValue();
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), (int) f[0], (int) f[1]) <= 2 * 2
                    || ai.now() - f[2] > 600f) {
                it.remove();
                ai.owner().setTarget(Selectable.newArray(u), t, Action.DEFAULT, false);
            }
        }
        for (Building tower : intel.towers) {
            // retire: a tower being razed is left empty.
            if (Intel.isTowerManned(tower) || tower_assignments.containsValue(tower) || ai.economy().isDoomed(tower))
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
                    front_entry.put(best, new float[]{front[0], front[1], ai.now()});
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
            Unit inside = Intel.gunner(tower);
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
        boolean escorting = mode == Mode.HOME && ai.now() - escort_time < 150f;
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
        escort_time = ai.now();
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
        s = withEnemyTowers(s, x, y, 22);
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
     * at half value beyond defense_radius; other copies count only within othersRadius() (defense_radius, or
     * defense_others_radius late). Without it, defenseAt, which counts every enemy warrior beyond defense_radius at
     * 0.3: at N=10 that alone is ~2.7 copy armies.
     */
    private float defenseFor(@NonNull Selectable<?> t) {
        return defenseFor(t, othersRadius());
    }

    /** defenseFor with other copies' warriors and chieftains counted within {@code others} cells. */
    private float defenseFor(@NonNull Selectable<?> t, int others) {
        int x = t.getGridX();
        int y = t.getGridY();
        if (!ai.strategy().gate_owner || ai.enemiesAlive() <= 1)
            return defenseAt(x, y);
        Intel intel = ai.intel();
        Player owner = t.getOwner();
        int r = ai.strategy().defense_radius;
        int r2 = r * r;
        int o2 = others * others;
        float s = 0f;
        for (List<Unit> group : List.of(intel.enemy_warriors, intel.enemy_chieftains))
            for (Unit u : group) {
                if (u.isDead())
                    continue;
                int d2 = MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY());
                if (u.getOwner() == owner)
                    s += Combat.value(u) * (d2 <= r2 ? 1f : .5f);
                else if (d2 <= o2)
                    s += Combat.value(u);
            }
        s *= 1.1f;
        s = withEnemyTowers(s, x, y, 22);
        s += .5f * Combat.strengthNear(intel.enemy_peons, x, y, 40);
        return s;
    }

    /**
     * Cells within which other copies' warriors and chieftains count against a target (defenseFor, the muster's
     * chieftain malus): defense_others_radius from defense_others_from_ticks on, else defense_radius.
     */
    private int othersRadius() {
        Strategy s = ai.strategy();
        return s.defense_others_radius > 0
                && ai.now() >= s.defense_others_from_ticks ? s.defense_others_radius : s.defense_radius;
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
            if (b.isDead() || b.isComplete() || stalled_targets.containsKey(b) || inDeadRegion(b))
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
                if (u.isDead() || stalled_targets.containsKey(u) || inDeadRegion(u))
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
        // decapitate: the campaign's targets come before finishing a raided copy (below, when there are none)
        boolean decap = decapitating();
        decap_pick = null;
        decap_alt = null;
        if (!decap) {
            Selectable<?> finish = finishFirst(from_x, from_y);
            if (finish != null)
                return finish;
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
        stalled_targets.entrySet().removeIf(e -> e.getKey().isDead() || ai.now() - e.getValue() > STALL_MEMORY_TICKS);
        while (!dead_region_times.isEmpty() && ai.now() - dead_region_times.getFirst() > STALL_MEMORY_TICKS) {
            dead_regions.removeFirst();
            dead_region_times.removeFirst();
        }
        while (!wedges.isEmpty() && ai.now() - wedges.getFirst().time > strategy.wedge_memory_ticks)
            wedges.removeFirst();
        List<Building> candidates = new ArrayList<>(intel.enemy_armories);
        candidates.addAll(intel.enemy_quarters);
        candidates.addAll(intel.enemy_towers);
        candidates.removeIf(b -> stalled_targets.containsKey(b) || inDeadRegion(b));
        if (strategy.gate_freeze)
            for (Building b : intel.enemy_buildings)
                if (!b.isComplete() && b.getTemplate().getTemplateID() == com.oddlabs.tt.model.Race.BUILDING_QUARTERS)
                    candidates.add(b);
        // decapitate: a quarters site would make its copy live again (Intel lists finished quarters only); not that of
        // a copy the engine has put out, which has no peon left to build it (s6510 N=13: the first muster went 221 m
        // for the site of s7, frozen out at 96 s)
        if (decap && !strategy.gate_freeze)
            for (Building b : intel.enemy_buildings)
                if (!b.isComplete() && b.getTemplate().getTemplateID() == com.oddlabs.tt.model.Race.BUILDING_QUARTERS
                        && b.getOwner().isAlive() && !stalled_targets.containsKey(b) && !inDeadRegion(b))
                    candidates.add(b);
        if (candidates.isEmpty()) {
            candidates.addAll(intel.enemy_buildings);
            candidates.removeIf(b -> stalled_targets.containsKey(b) || inDeadRegion(b));
        }
        Building best_line = null;
        float best_line_score = Float.MAX_VALUE;
        Building best_frozen = null;
        float best_frozen_score = Float.MAX_VALUE;
        // stall_engaged_ticks: the best building whose way runs through a remembered wedge, and that wedge
        Building best_wedged = null;
        float best_wedged_score = Float.MAX_VALUE;
        Wedge best_wedge = null;
        // decapitate: the best of the campaign's targets, which come first (after the loop)
        Selectable<?> decap_best = null;
        float decap_score = Float.MAX_VALUE;
        float decap_defense = 0f;
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
            float raw_defense = defenseFor(b);
            float defense = strategy.target_defense_weight * raw_defense;
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
            Wedge wedge = wedgeOn(b);
            boolean campaign = decap && decapTarget(b);
            if (explain != null)
                explain.add(String.format("%07.1f %s %s at %d,%d: d %.0f + pri %.0f + def %.0f%s%s", score,
                        b.getOwner().getPlayerInfo().getName(), Intel.kind(b), b.getGridX(), b.getGridY(), d, priority,
                        defense, wedge != null ? " (through the wedge at " + wedge.x + "," + wedge.y + ")" : "",
                        campaign ? b.isComplete() ? " (decapitate)" : " (decapitate: site)" : ""));
            if (wedge != null) {
                if (scored != null)
                    scored.add(new Rung(b, RUNG_WEDGED, score, raw_defense));
                if (score < best_wedged_score) {
                    best_wedged_score = score;
                    best_wedged = b;
                    best_wedge = wedge;
                }
                continue;
            }
            if (strategy.frozen_last && ai.freeze().isFrozen(b.getOwner())) {
                if (scored != null)
                    scored.add(new Rung(b, RUNG_FROZEN, score, raw_defense));
                if (score < best_frozen_score) {
                    best_frozen_score = score;
                    best_frozen = b;
                }
                continue;
            }
            if (campaign) {
                if (scored != null)
                    scored.add(new Rung(b, RUNG_DECAP, score, raw_defense));
                if (score < decap_score) {
                    decap_score = score;
                    decap_best = b;
                    decap_defense = raw_defense;
                }
                continue;
            }
            if (scored != null)
                scored.add(new Rung(b, RUNG_BUILDING, score, raw_defense));
            if (score < best_score) {
                best_score = score;
                best = b;
            }
            if (path != null && score - d + line < best_line_score) {
                best_line_score = score - d + line;
                best_line = b;
            }
        }
        if (decap) {
            // decapitate: active enemy chieftains within decapitate_chief_cells of the army, scored like a quarters; a
            // quarterless copy's chieftain without the quarters' priority, since his death pacifies it for good.
            int reach2 = strategy.decapitate_chief_cells * strategy.decapitate_chief_cells;
            float quarters_priority = strategy.quarters_first || strategy.gate_freeze ? 0f : 60f;
            for (Unit u : intel.enemy_chieftains) {
                if (u.isDead() || stalled_targets.containsKey(u) || inDeadRegion(u)
                        || MapAnalysis.dist2(from_x, from_y, u.getGridX(), u.getGridY()) > reach2)
                    continue;
                float d = MapAnalysis.meters(from_x, from_y, u.getGridX(), u.getGridY());
                if (path != null) {
                    int walk = path.getAround(u.getGridX(), u.getGridY(), 2);
                    if (walk == DistanceField.UNREACHABLE)
                        continue;
                    d = walk;
                }
                if (wedgeOn(u) != null)
                    continue;
                float priority = housed(u.getOwner()) ? quarters_priority : 0f;
                float raw_defense = defenseFor(u);
                float defense = strategy.target_defense_weight * raw_defense;
                float score = d + priority + defense;
                if (strategy.target_home_weight > 0f)
                    score += strategy.target_home_weight * MapAnalysis.meters(staging_x, staging_y, u.getGridX(),
                            u.getGridY());
                if (focus_owner != null && u.getOwner() == focus_owner)
                    score -= strategy.focus_bonus;
                score -= strategy.target_threat_weight * base_threat.getOrDefault(u.getOwner(), 0f);
                if (explain != null)
                    explain.add(String.format("%07.1f %s chieftain at %d,%d: d %.0f + pri %.0f + def %.0f (decapitate)",
                            score, u.getOwner().getPlayerInfo().getName(), u.getGridX(), u.getGridY(), d, priority,
                            defense));
                if (scored != null)
                    scored.add(new Rung(u, RUNG_DECAP, score, raw_defense));
                if (score < decap_score) {
                    decap_score = score;
                    decap_best = u;
                    decap_defense = raw_defense;
                }
            }
            if (decap_best != null) {
                decap_pick = decap_best;
                decap_changed = best != null && best_score < decap_score;
                decap_why = ai.logging() ? decapWhy(decap_best, decap_score, decap_defense, best, best_score) : null;
                // the muster's second choice when this one fails the gate: the best other building scored, with the
                // frozen_last and wedge fallbacks as below (uncounted)
                decap_alt = best != null ? best : best_frozen != null ? best_frozen : best_wedged;
                return decap_best;
            }
            // No campaign target left: target choice as without decapitate, finishing a raided copy first.
            Selectable<?> finish = finishFirst(from_x, from_y);
            if (finish != null)
                return finish;
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
        if (best_wedge != null && best_wedged != null) {
            if (best == null) {
                // Every building left is through a wedge: the best of them after all.
                best = best_wedged;
                if (!best_wedge.fallback) {
                    best_wedge.fallback = true;
                    ai.aiLog().count("wedge_fallback");
                    ai.log(String.format("wedge at %d,%d: every building left is through it, %s %s at %d,%d after all",
                            best_wedge.x, best_wedge.y, best_wedged.getOwner().getPlayerInfo().getName(),
                            Intel.kind(best_wedged), best_wedged.getGridX(), best_wedged.getGridY()));
                }
            } else if (best_wedged_score < best_score && !best_wedge.avoided) {
                best_wedge.avoided = true;
                ai.aiLog().count("wedge_avoided");
                ai.log(String.format("wedge at %d,%d: passing over %s %s at %d,%d, whose way runs through it",
                        best_wedge.x, best_wedge.y, best_wedged.getOwner().getPlayerInfo().getName(),
                        Intel.kind(best_wedged), best_wedged.getGridX(), best_wedged.getGridY()));
            }
        }
        if (best == null && scored != null)
            ladder_fallback = true;
        return best != null ? best : nearestEnemyUnit(from_x, from_y);
    }

    /** focus_finish's and finish_copies' targets, which target choice takes before any other (null: none). */
    private @Nullable Selectable<?> finishFirst(int from_x, int from_y) {
        Strategy strategy = ai.strategy();
        if (focus_owner != null && strategy.focus_finish) {
            Unit prey = focusRemnant(focus_owner, from_x, from_y);
            if (prey != null)
                return prey;
        }
        if (strategy.finish_copies && ai.enemiesAlive() > 1) {
            Selectable<?> finish = finishTarget(from_x, from_y);
            if (finish != null) {
                ai.aiLog().count("finish_target");
                return finish;
            }
        }
        return null;
    }

    /** decapitate, from decapitate_from_ticks on. */
    private boolean decapitating() {
        Strategy s = ai.strategy();
        return s.decapitate && ai.now() >= s.decapitate_from_ticks;
    }

    /**
     * decapitate: a building of the campaign's tier: a quarters, finished (its copy is live) or a site (it would be
     * again), or the finished armory of a copy that is not live but may still send a wave of 10 or 15, which needs no
     * chieftain (mayLaunch): its warriors come from there. Never a building of a copy the engine has put out.
     */
    private boolean decapTarget(@NonNull Building b) {
        if (!b.getOwner().isAlive())
            return false; // put out: it never sends a wave again
        int id = b.getTemplate().getTemplateID();
        if (id == com.oddlabs.tt.model.Race.BUILDING_QUARTERS)
            return true;
        return id == com.oddlabs.tt.model.Race.BUILDING_ARMORY && b.isComplete() && !liveCopy(b.getOwner())
                && mayLaunch(b.getOwner());
    }

    /**
     * decapitate: whether copy p can send a wave that needs a chieftain: it has a finished quarters (where it trains
     * one, AdvancedAI.nodeTrainChieftain) or an active or training chieftain. Not live: pacified.
     */
    private boolean liveCopy(@NonNull Player p) {
        return p.hasActiveChieftain() || p.isTrainingChieftain() || housed(p);
    }

    /** decapitate: whether copy p has a finished quarters. */
    private boolean housed(@NonNull Player p) {
        for (Building q : ai.intel().enemy_quarters)
            if (!q.isDead() && q.getOwner() == p)
                return true;
        return false;
    }

    /**
     * decapitate: whether copy p may still be below wave size 20 (10 + 5 per wave launched), from which on a wave
     * leaves only with an active chieftain: fewer than two launches seen by its shepherd flock, or no flock watching.
     */
    private boolean mayLaunch(@NonNull Player p) {
        return ai.shepherd().launches(p) < 2;
    }

    /** decapitate, for log lines: what copy p is in the campaign's terms. */
    private @NonNull String copyStatus(@NonNull Player p) {
        if (p.hasActiveChieftain())
            return "live, chieftain";
        if (p.isTrainingChieftain())
            return "live, training a chieftain";
        if (housed(p))
            return "live, quarters";
        int waves = ai.shepherd().launches(p);
        String seen = waves < 0 ? "waves not watched" : waves + " waves seen";
        return mayLaunch(p) ? "pacified, may still send a small wave (" + seen + ")" : "pacified (" + seen + ")";
    }

    /** decapitate, log only: why chooseTarget took {@code pick} (score, defense) over {@code other}, the best else. */
    private @NonNull String decapWhy(@NonNull Selectable<?> pick, float score, float defense,
            @Nullable Selectable<?> other, float other_score) {
        String what;
        if (!(pick instanceof Building b))
            what = "its chieftain, within " + ai.strategy().decapitate_chief_cells + " cells";
        else if (!b.isComplete())
            what = "a quarters site would make it live again";
        else if (b.getTemplate().getTemplateID() == com.oddlabs.tt.model.Race.BUILDING_QUARTERS)
            what = "its quarters";
        else
            what = "its armory, while it may still send a wave without a chieftain";
        int live = 0;
        int pacified = 0;
        for (Player p : ai.owner().getWorld().getPlayers())
            if (ai.owner().isEnemy(p) && p.isAlive()) {
                if (liveCopy(p))
                    live++;
                else
                    pacified++;
            }
        String instead = other == null ? "" : String.format(", over %s (score %.0f, %s)", describe(other),
                other_score, copyStatus(other.getOwner()));
        return String.format("%s, %s (%s; score %.0f, defense %.1f)%s; %d copies live, %d pacified", describe(pick),
                what, copyStatus(pick.getOwner()), score, defense, instead, live, pacified);
    }

    /**
     * decapitate: counts (decapitate_target, by kind, and decapitate_changed when it passed over a better-scoring
     * target) and logs an attack target that target choice took from the campaign's tier ({@code when}: the decision).
     */
    private void noteDecapitate(@Nullable Selectable<?> t, @NonNull String when) {
        if (t == null || t != decap_pick)
            return;
        ai.aiLog().count("decapitate_target");
        String kind = "chief";
        if (t instanceof Building b) {
            boolean quarters = b.getTemplate().getTemplateID() == com.oddlabs.tt.model.Race.BUILDING_QUARTERS;
            kind = !b.isComplete() ? "site" : quarters ? "quarters" : "armory";
        }
        ai.aiLog().count("decapitate_" + kind);
        if (decap_changed)
            ai.aiLog().count("decapitate_changed");
        if (ai.logging())
            ai.log("decapitate (" + when + "): " + (decap_why != null ? decap_why : describe(t)));
    }

    /**
     * decapitate: watches each copy go from live to pacified (decapitate_pacified, once a copy, and
     * decapitate_pacified_early while it may still send a small wave) and back (decapitate_relive: it rebuilt its
     * quarters or trained a chieftain), and logs each change. Counters and logs only.
     */
    private void trackPacified() {
        Intel intel = ai.intel();
        for (Player p : ai.owner().getWorld().getPlayers()) {
            if (!ai.owner().isEnemy(p) || !p.isAlive())
                continue;
            boolean live = liveCopy(p);
            boolean was = decap_live.contains(p);
            if (live == was)
                continue;
            String name = p.getPlayerInfo().getName();
            if (live) {
                decap_live.add(p);
                if (decap_pacified.contains(p)) {
                    ai.aiLog().count("decapitate_relive");
                    ai.log(String.format("decapitate: %s is %s again", name, copyStatus(p)));
                }
                continue;
            }
            decap_live.remove(p);
            boolean early = mayLaunch(p);
            if (!decap_pacified.contains(p)) {
                decap_pacified.add(p);
                ai.aiLog().count("decapitate_pacified");
                if (early)
                    ai.aiLog().count("decapitate_pacified_early");
            }
            if (ai.logging()) {
                int warriors = 0;
                for (Unit u : intel.enemy_warriors)
                    if (!u.isDead() && u.getOwner() == p)
                        warriors++;
                boolean armory = false;
                for (Building a : intel.enemy_armories)
                    armory |= !a.isDead() && a.getOwner() == p;
                ai.log(String.format("decapitate: %s %s, no quarters and no chieftain: %d units, %d warriors%s", name,
                        copyStatus(p), p.getUnitCountContainer().getNumSupplies(), warriors,
                        armory ? ", an armory" : ""));
            }
        }
    }

    /**
     * decapitate: a chieftain the attack goes for has walked more than 8 cells from where its field was computed:
     * the field again from where it stands now (at most every DECAP_FOLLOW_TICKS, and while it is within twice
     * decapitate_chief_cells of the army's centre), so the army follows it instead of stalling on an empty spot. The
     * stall clocks keep running: the gains on the new field are measured from where the army stands on it now, so only
     * a real 20 m gain restarts the calm stall's clock and stall_cap's, and a chieftain the army cannot catch (one
     * walking with a leashed flock) stalls as any target does. (A new field reset both, and the first measure on it
     * counted as a gain, every 5 s while he walked.)
     */
    private void followChief(int @NonNull [] c) {
        if (!(target instanceof Unit u) || u.isDead() || u.isMounted() || !u.getAbilities().hasAbilities(
                Abilities.MAGIC) || MapAnalysis.dist2(target_x, target_y, u.getGridX(), u.getGridY()) <= 8 * 8
                || !ai.periodDue(last_decap_follow, DECAP_FOLLOW_TICKS))
            return;
        int reach = 2 * ai.strategy().decapitate_chief_cells;
        if (MapAnalysis.dist2(c[0], c[1], u.getGridX(), u.getGridY()) > reach * reach)
            return;
        last_decap_follow = ai.now();
        int fx = target_x;
        int fy = target_y;
        float progress = last_progress_time;
        setTarget(u, false);
        last_progress_time = progress;
        DistanceField f = target_field;
        int p = f != null ? attackPivotDist(f) : DistanceField.UNREACHABLE;
        best_target_dist = p == DistanceField.UNREACHABLE ? Integer.MAX_VALUE : p;
        // sealed_progress: likewise for the straight line while no attacker stands on the field
        best_line_dist = (int) MapAnalysis.meters(c[0], c[1], target_x, target_y);
        ai.aiLog().count("decapitate_follow");
        ai.log(String.format("decapitate: following %s from %d,%d", describe(u), fx, fy));
    }

    /**
     * stall_engaged_ticks: the remembered wedge that b's way from the staging point runs through (within 12 cells:
     * going through the wedge's cell costs at most WEDGE_DETOUR more), or null. b: a building, or (decapitate) a
     * chieftain.
     */
    private @Nullable Wedge wedgeOn(@NonNull Selectable<?> b) {
        if (wedges.isEmpty())
            return null;
        DistanceField s = stagingField();
        int sb = s.getAround(b.getGridX(), b.getGridY(), 4);
        if (sb == DistanceField.UNREACHABLE)
            return null;
        for (Wedge w : wedges) {
            int sw = s.getAround(w.x, w.y, 2);
            int wb = w.field.getAround(b.getGridX(), b.getGridY(), 4);
            if (sw != DistanceField.UNREACHABLE && wb != DistanceField.UNREACHABLE && sw + wb <= sb + WEDGE_DETOUR)
                return w;
        }
        return null;
    }

    /**
     * The field with corner cuts (as the engine walks) from the staging point, computed again when that moves or
     * STAGING_FIELD_TICKS after the last time (trees are cut and buildings rise: kept for the game, it called newly
     * opened ground unreachable and kept ways that a building now blocks).
     */
    private @NonNull DistanceField stagingField() {
        DistanceField f = staging_field;
        if (f == null || f.getSourceX() != staging_x || f.getSourceY() != staging_y
                || ai.now() - staging_field_time > STAGING_FIELD_TICKS) {
            f = ai.map().computeField(staging_x, staging_y, Integer.MAX_VALUE, true);
            staging_field = f;
            staging_field_time = ai.now();
        }
        return f;
    }

    /**
     * focus_finish: the focus copy's unit nearest (from_x, from_y) within 90 cells once the copy has no building left
     * (hunted down before it rebuilds), else null.
     */
    private @Nullable Unit focusRemnant(@NonNull Player focus, int from_x, int from_y) {
        Intel intel = ai.intel();
        for (Building b : intel.enemy_buildings)
            if (!b.isDead() && b.getOwner() == focus)
                return null;
        Unit prey = null;
        int best_d = Integer.MAX_VALUE;
        for (List<Unit> group : List.of(intel.enemy_peons, intel.enemy_warriors, intel.enemy_chieftains))
            for (Unit u : group) {
                if (u.isDead() || u.getOwner() != focus)
                    continue;
                int d = MapAnalysis.dist2(from_x, from_y, u.getGridX(), u.getGridY());
                if (d < best_d) {
                    best_d = d;
                    prey = u;
                }
            }
        return prey != null && best_d <= 90 * 90 ? prey : null;
    }

    /** With no building to go for: the enemy unit nearest (from_x, from_y) outside stalled targets and dead regions. */
    private @Nullable Unit nearestEnemyUnit(int from_x, int from_y) {
        Intel intel = ai.intel();
        Unit best = null;
        int best_d = Integer.MAX_VALUE;
        for (List<Unit> group : List.of(intel.enemy_peons, intel.enemy_warriors, intel.enemy_chieftains))
            for (Unit u : group) {
                if (u.isDead() || stalled_targets.containsKey(u) || inDeadRegion(u))
                    continue;
                int d = MapAnalysis.dist2(from_x, from_y, u.getGridX(), u.getGridY());
                if (d < best_d) {
                    best_d = d;
                    best = u;
                }
            }
        return best;
    }

    /**
     * remnant_ladder_ticks: the first target on the ladder that passes the muster's gate (musterGo), or null. The
     * ladder holds the buildings chooseTarget scored ({@code rungs}) less {@code best}, the quarters and armory sites
     * of homeless copies (never a frozen copy's armory site, a stalled target or one in a dead region), and one
     * remnant of each homeless copy still in (remnantTarget). It goes rung by rung (RUNG_*), each by score: the
     * buildings by chooseTarget's, sites and remnants by meters from the staging point plus target_defense_weight per
     * unit of their defense. A remnant with another copy's warrior or chieftain within REMNANT_ALONE_CELLS waits for
     * those that stand alone, so that one parked blob fights at a time, and sites and remnants whose way runs through
     * a remembered wedge (wedgeOn) come last, with the wedge memory's buildings (RUNG_WEDGED). {@code log_none}: log
     * (once a minute) when nothing passes. With decapitate, the campaign's other targets chooseTarget scored
     * (RUNG_DECAP, chieftains among them) come first.
     */
    private @Nullable Rung walkLadder(@Nullable Selectable<?> best, @NonNull List<@NonNull Rung> rungs, float potential,
            float growth, boolean log_none) {
        Intel intel = ai.intel();
        Strategy strategy = ai.strategy();
        List<Rung> ladder = new ArrayList<>();
        for (Rung r : rungs)
            if (r.t() != best)
                ladder.add(r);
        for (Building b : intel.enemy_buildings) {
            int id = b.getTemplate().getTemplateID();
            if (b.isDead() || b.isComplete() || b == best || (id != com.oddlabs.tt.model.Race.BUILDING_QUARTERS
                    && id != com.oddlabs.tt.model.Race.BUILDING_ARMORY) || ai.freeze().isFrozenSite(b)
                    || stalled_targets.containsKey(b) || inDeadRegion(b) || scoredAlready(rungs, b)
                    || !b.getOwner().isAlive() || !homeless(b.getOwner()))
                continue;
            float d = defenseFor(b);
            float score = MapAnalysis.meters(staging_x, staging_y, b.getGridX(),
                    b.getGridY()) + strategy.target_defense_weight * d;
            // last when its way runs through a remembered wedge, as target choice does with buildings
            ladder.add(new Rung(b, wedgeOn(b) != null ? RUNG_WEDGED : RUNG_SITE, score, d, true));
        }
        for (Map.Entry<Player, List<Unit>> e : remnantGroups(null).entrySet()) {
            Unit u = remnantTarget(e.getKey(), e.getValue(), null);
            if (u == null)
                continue;
            float d = defenseFor(u);
            float score = MapAnalysis.meters(staging_x, staging_y, u.getGridX(),
                    u.getGridY()) + strategy.target_defense_weight * d;
            int rung = wedgeOn(u) != null ? RUNG_WEDGED : othersNear(u,
                    REMNANT_ALONE_CELLS) == 0 ? RUNG_ALONE : RUNG_CROWDED;
            ladder.add(new Rung(u, rung, score, d, true));
        }
        // stable: equal scores keep the order above (world order of the copies and their units)
        ladder.sort((a, b) -> a.rung() != b.rung() ? Integer.compare(a.rung(), b.rung()) : Float.compare(a.score(),
                b.score()));
        int others = othersRadius();
        for (Rung r : ladder) {
            float defense = r.defense();
            if (strategy.project_defense)
                defense += growth;
            if (musterGo(r.t(), potential, defense, others, false))
                return r;
        }
        if (log_none && ai.logging() && ai.periodDue(last_ladder_log, 3000f)) {
            last_ladder_log = ai.now();
            Rung first = ladder.isEmpty() ? null : ladder.getFirst();
            ai.log(String.format("remnant ladder: none of %d targets passes the gate with %.1f%s", ladder.size(),
                    potential, first == null ? "" : String.format(" (first: %s, defense %.1f)", describe(first.t()),
                            first.defense())));
        }
        return null;
    }

    private static boolean scoredAlready(@NonNull List<@NonNull Rung> rungs, @NonNull Building b) {
        for (Rung r : rungs)
            if (r.t() == b)
                return true;
        return false;
    }

    /**
     * remnant_ladder_ticks: the units of each homeless copy still in (remnantStanding), or only of {@code only}, that
     * an attack may go for (alive, not a stalled target, outside dead regions), by copy in the order Intel sees them,
     * chieftains first.
     */
    private @NonNull Map<@NonNull Player, @NonNull List<@NonNull Unit>> remnantGroups(@Nullable Player only) {
        Intel intel = ai.intel();
        Map<Player, Boolean> standing = new LinkedHashMap<>();
        Map<Player, List<Unit>> groups = new LinkedHashMap<>();
        for (List<Unit> group : List.of(intel.enemy_chieftains, intel.enemy_warriors, intel.enemy_peons))
            for (Unit u : group) {
                Player p = u.getOwner();
                if (u.isDead() || (only != null && p != only)
                        || !standing.computeIfAbsent(p, k -> homeless(k) && remnantStanding(k))
                        || stalled_targets.containsKey(u) || inDeadRegion(u))
                    continue;
                groups.computeIfAbsent(p, k -> new ArrayList<>()).add(u);
            }
        return groups;
    }

    /**
     * remnant_ladder_ticks: a homeless copy that units must still die for to go out: more than 8 units or an active
     * chieftain (the collapse rule), or any unit at all while a frozen armory site (never attacked) keeps it in.
     */
    private boolean remnantStanding(@NonNull Player p) {
        int units = p.getUnitCountContainer().getNumSupplies();
        if (units > 8 || p.hasActiveChieftain())
            return true;
        if (units == 0)
            return false;
        for (Building b : ai.intel().enemy_buildings)
            if (b.getOwner() == p && ai.freeze().isFrozenSite(b))
                return true;
        return false;
    }

    /**
     * remnant_ladder_ticks: the remnant of copy p to attack, from its {@code units} (remnantGroups): its chieftain,
     * which alone keeps it in, else its unit nearest {@code from} (null: the centre of those units); null while its
     * chieftain is out of reach (a stalled target, a dead region, mounted), since no other kill puts the copy out.
     */
    private @Nullable Unit remnantTarget(@NonNull Player p, @NonNull List<@NonNull Unit> units, int @Nullable [] from) {
        if (units.isEmpty())
            return null;
        if (p.hasActiveChieftain()) {
            Unit chief = p.getChieftain();
            return chief != null && units.contains(chief) ? chief : null;
        }
        int[] c = from != null ? from : MapAnalysis.centroid(units);
        Unit best = null;
        int best_d = Integer.MAX_VALUE;
        for (Unit u : units) {
            int d = MapAnalysis.dist2(c[0], c[1], u.getGridX(), u.getGridY());
            if (d < best_d) {
                best_d = d;
                best = u;
            }
        }
        return best;
    }

    /** remnant_ladder_ticks: other copies' living warriors and chieftains within {@code cells} of u. */
    private int othersNear(@NonNull Unit u, int cells) {
        Intel intel = ai.intel();
        int r2 = cells * cells;
        int n = 0;
        for (List<Unit> group : List.of(intel.enemy_warriors, intel.enemy_chieftains))
            for (Unit e : group)
                if (!e.isDead() && e.getOwner() != u.getOwner()
                        && MapAnalysis.dist2(e.getGridX(), e.getGridY(), u.getGridX(), u.getGridY()) <= r2)
                    n++;
        return n;
    }

    /**
     * remnant_ladder_ticks: after a site or remnant of the copy the attack finishes has fallen, that copy's next
     * remnant while it is still in: its chieftain, else its unit nearest the army's centre {@code c}; null when there
     * is none, or when it stands more than finish_range cells from c (remnant_chain_far: the chain is never gated
     * again, so a scattered copy would march the army back and forth across the map; the attack then retargets as
     * usual, and the next muster weighs the copy anew).
     */
    private @Nullable Unit remnantNext(@Nullable Selectable<?> fallen, int @NonNull [] c) {
        Player p = ladder_owner;
        ladder_owner = null;
        if (p == null || fallen == null || fallen.getOwner() != p)
            return null;
        List<Unit> units = remnantGroups(p).get(p);
        Unit next = units == null ? null : remnantTarget(p, units, c);
        String name = p.getPlayerInfo().getName();
        int left = p.getUnitCountContainer().getNumSupplies();
        if (next == null) {
            ai.aiLog().count("remnant_chain_done");
            if (ai.logging()) {
                String why = !homeless(p) ? "has a base again" : remnantStanding(
                        p) ? "stays in, the rest out of reach" : "is finished";
                ai.log(String.format("remnant ladder: %s %s (%d units%s)", name, why, left,
                        p.hasActiveChieftain() ? ", chieftain" : ""));
            }
            return null;
        }
        int range = ai.strategy().finish_range;
        if (MapAnalysis.dist2(c[0], c[1], next.getGridX(), next.getGridY()) > range * range) {
            ai.aiLog().count("remnant_chain_far");
            ai.log(String.format("remnant ladder: next of %s, %s, is %.0f m from the army at %d,%d: the chain ends",
                    name, describe(next), MapAnalysis.meters(c[0], c[1], next.getGridX(), next.getGridY()), c[0],
                    c[1]));
            return null;
        }
        ladder_owner = p;
        ai.aiLog().count("remnant_chain");
        ai.log(String.format("remnant ladder: next of %s, %s (%d units left%s)", name, describe(next), left,
                p.hasActiveChieftain() ? ", chieftain" : ""));
        return next;
    }

    /** For log lines: the owner, kind and cell of a target. */
    private static @NonNull String describe(@NonNull Selectable<?> t) {
        String kind;
        if (t instanceof Building b)
            kind = Intel.kind(b) + (b.isComplete() ? "" : " site");
        else if (t.getAbilities().hasAbilities(Abilities.MAGIC))
            kind = "chieftain";
        else if (t.getAbilities().hasAbilities(Abilities.THROW))
            kind = "warrior";
        else
            kind = "peon";
        return t.getOwner().getPlayerInfo().getName() + " " + kind + " at " + t.getGridX() + "," + t.getGridY();
    }

    /**
     * How fast the enemy's field army has grown over the last minute or so, in strength per game tick: warriors
     * outside plus weapons it could deploy are not visible, so this watches what comes out.
     */
    private float enemyGrowthPerTick() {
        if (enemy_history.size() < 2)
            return 0f;
        float[] first = enemy_history.getFirst();
        float[] last = enemy_history.getLast();
        float dt = last[0] - first[0];
        return dt > 0f ? (last[1] - first[1]) / dt : 0f;
    }

    private void recordEnemyStrength() {
        enemy_history.addLast(new float[]{ai.now(), enemyFieldStrength()});
        while (enemy_history.size() > 2 && ai.now() - enemy_history.getFirst()[0] > 4500f)
            enemy_history.removeFirst();
    }

    private void considerAttack() {
        Strategy strategy = ai.strategy();
        all_in = false;
        // allin_ticks: on a frozen board the weakest copy base, past the gate
        boolean push = pushDue();
        if (ai.now() >= next_wave_time && (push || allIn())) {
            Building weakest = weakestBase();
            if (weakest != null) {
                allInMuster(weakest, push);
                return;
            }
        }
        if (ai.logging())
            explain = new ArrayList<>();
        // remnant_ladder_ticks: chooseTarget records the buildings it scores, for the ladder; mopup: likewise once no
        // copy has a base
        boolean ladder = strategy.remnant_ladder_ticks > 0f && ai.now() >= strategy.remnant_ladder_ticks || mopUp();
        if (ladder) {
            scored = new ArrayList<>();
            ladder_fallback = false;
        }
        Selectable<?> t = chooseTarget(staging_x, staging_y);
        List<String> why = explain;
        explain = null;
        List<Rung> rungs = scored;
        scored = null;
        boolean fallback = ladder_fallback;
        ladder_fallback = false;
        if (t == null && rungs == null)
            return;
        float army = armyStrength();
        // retreat_cap_ticks: the attackers a capped retreat left out do not march with the next attack (launchAttack)
        if (!stranded.isEmpty())
            army -= strandedStrength();
        float potential = army + stockStrength();
        float growth = 0f;
        if (strategy.project_defense) {
            // The enemy keeps arming while we march (3 m a game second); judge the fight at the moment of arrival.
            int d = ai.planner().getEnemyField().get(staging_x, staging_y);
            float march = d == DistanceField.UNREACHABLE ? 6000f : GauntletAI.ticks(d / 3f);
            growth = Math.max(0f, enemyGrowthPerTick()) * march;
        }
        float defense = 0f;
        boolean go = false;
        if (t != null) {
            defense = defenseFor(t);
            if (strategy.project_defense)
                defense += growth;
            go = musterGo(t, potential, defense, othersRadius(), true);
        }
        // decapitate: the campaign's best fails the gate and no ladder weighs the rest, so the best other building is
        // tried; else a guarded quarters or a capped army's failed capped_ratio test held the army home for good while
        // the pacified copies' cheap armories and towers were never weighed.
        Selectable<?> alt = decap_alt;
        decap_alt = null;
        if (!go && t != null && t == decap_pick && rungs == null && alt != null && !alt.isDead()
                && ai.now() >= next_wave_time) {
            float alt_defense = defenseFor(alt);
            if (strategy.project_defense)
                alt_defense += growth;
            if (musterGo(alt, potential, alt_defense, othersRadius(), false)) {
                ai.aiLog().count("decapitate_alt");
                ai.log(String.format("decapitate: %s fails the gate at %.1f, so %s (%s, defense %.1f)", describe(t),
                        defense, describe(alt), copyStatus(alt.getOwner()), alt_defense));
                t = alt;
                defense = alt_defense;
                go = true;
            }
        }
        // remnant_ladder_ticks: the best building fails the gate, or there is none (the nearest unit, or nothing).
        Rung pick = null;
        if (rungs != null && (!go || fallback) && ai.now() >= next_wave_time) {
            pick = walkLadder(t, rungs, potential, growth, !go);
            if (pick != null) {
                String rung = switch (pick.rung()) {
                    case RUNG_DECAP -> "decapitate";
                    case RUNG_BUILDING -> "building";
                    case RUNG_SITE -> "site";
                    case RUNG_FROZEN -> "frozen";
                    case RUNG_WEDGED -> "wedged";
                    default -> pick.t().getAbilities().hasAbilities(Abilities.MAGIC) ? "chief" : "unit";
                };
                ai.aiLog().count("ladder_" + rung);
                if (pick.rung() == RUNG_CROWDED)
                    ai.aiLog().count("ladder_crowded");
                if (fallback)
                    ai.aiLog().count("ladder_fallback");
                if (ai.logging()) {
                    String instead;
                    if (t == null)
                        instead = "nothing else to attack";
                    else if (fallback)
                        instead = "no building left (nearest unit: " + describe(t) + ")";
                    else
                        instead = String.format("%s fails the gate at %.1f", describe(t), defense);
                    String crowd = pick.rung() == RUNG_CROWDED ? String.format(", %d other fighters within %d cells",
                            othersNear((Unit) pick.t(), REMNANT_ALONE_CELLS), REMNANT_ALONE_CELLS) : "";
                    ai.log(String.format("remnant ladder: %s, so %s (%s, defense %.1f, score %.0f%s)", instead,
                            describe(pick.t()), rung, pick.defense(), pick.score(), crowd));
                }
                t = pick.t();
                defense = pick.defense();
                if (strategy.project_defense)
                    defense += growth;
                go = true;
                if (pick.rung() == RUNG_DECAP) {
                    // decapitate: another of the campaign's targets, cheaper than its best
                    decap_pick = t;
                    decap_changed = false;
                    decap_why = ai.logging() ? String.format("%s from the ladder (%s; score %.0f, defense %.1f)",
                            describe(t), copyStatus(t.getOwner()), pick.score(), pick.defense()) : null;
                }
            }
        }
        if (t == null || !go || ai.now() < next_wave_time)
            return;
        // decapitate: count and log a muster on one of the campaign's targets
        noteDecapitate(t, "muster");
        target = t;
        mode = Mode.MUSTER;
        muster_start = ai.now();
        // remnant_ladder_ticks: a site or remnant from the ladder (wedged ones too) starts finishing its copy (attack,
        // remnantNext)
        boolean finish = pick != null && pick.remnant();
        ladder_target = finish ? t : null;
        ladder_owner = finish ? t.getOwner() : null;
        if (why != null && !why.isEmpty()) {
            // log only: the best few candidates by score (lower is better), from the staging point
            why.sort(null);
            ai.log("muster candidates from " + staging_x + "," + staging_y + ": " + String.join("; ", why.subList(0,
                    Math.min(4, why.size()))));
        }
        ai.log(String.format("muster: army %.1f + stock %.1f vs defense %.1f at %d,%d", army, potential - army,
                defense, t.getGridX(), t.getGridY()));
        // defense_others_radius: count (and log) the musters that counting other copies as far as before would stop
        int others = othersRadius();
        if (others != strategy.defense_radius && strategy.gate_owner && ai.enemiesAlive() > 1) {
            float wide = defenseFor(t, strategy.defense_radius);
            if (strategy.project_defense)
                wide += growth;
            if (!musterGo(t, potential, wide, strategy.defense_radius, false)) {
                ai.aiLog().count("defense_others_go");
                ai.log(String.format(
                        "muster on other copies within %d cells: defense %.1f, %.1f within %d would" + " hold it back",
                        others, defense, wide, strategy.defense_radius));
            }
        }
    }

    /**
     * mopup: from endgame_from_ticks, no copy still in has a quarters or armory, finished or placed (a frozen copy's
     * armory site does not count), so the remnant ladder finishes the bands left (Strategy.mopup).
     */
    private boolean mopUp() {
        Strategy s = ai.strategy();
        if (!s.mopup || ai.now() < s.endgame_from_ticks || ai.enemiesAlive() == 0)
            return false;
        for (Building b : ai.intel().enemy_buildings) {
            int id = b.getTemplate().getTemplateID();
            if (!b.isDead() && b.getOwner().isAlive() && (id == com.oddlabs.tt.model.Race.BUILDING_QUARTERS
                    || id == com.oddlabs.tt.model.Race.BUILDING_ARMORY) && !ai.freeze().isFrozenSite(b))
                return false;
        }
        if (!mopup_seen) {
            mopup_seen = true;
            ai.aiLog().count("mopup");
            ai.log(String.format("mopup: no copy has a base, %d still in", ai.enemiesAlive()));
        }
        return true;
    }

    /**
     * allin_ticks: from allin_ticks on, no copy has gone out and no threat has stood at our base for
     * allin_quiet_ticks (Strategy.allin_ticks).
     */
    private boolean allIn() {
        Strategy s = ai.strategy();
        return s.allin_ticks > 0f && ai.now() >= s.allin_ticks
                && ai.now() - Math.max(last_out_time, last_base_threat) >= s.allin_quiet_ticks;
    }

    /**
     * push_ticks: from push_ticks on, no copy has gone out for push_quiet_ticks, and the last push mustered
     * push_period_ticks ago or more (Strategy.push_ticks).
     */
    private boolean pushDue() {
        Strategy s = ai.strategy();
        return s.push_ticks > 0f && ai.now() >= s.push_ticks && ai.now() - last_out_time >= s.push_quiet_ticks
                && ai.periodDue(last_push, s.push_period_ticks);
    }

    /**
     * allin_ticks: the copy base (a quarters or armory, finished or placed, not a frozen site, a stalled target or in a
     * dead region) with the least target_defense_weight per unit of defense plus meters from the staging point, or
     * null.
     */
    private @Nullable Building weakestBase() {
        float w = ai.strategy().target_defense_weight;
        Building best = null;
        float best_score = Float.MAX_VALUE;
        for (Building b : ai.intel().enemy_buildings) {
            int id = b.getTemplate().getTemplateID();
            if (b.isDead() || !b.getOwner().isAlive() || (id != com.oddlabs.tt.model.Race.BUILDING_QUARTERS
                    && id != com.oddlabs.tt.model.Race.BUILDING_ARMORY) || ai.freeze().isFrozenSite(b)
                    || stalled_targets.containsKey(b) || inDeadRegion(b))
                continue;
            float score = w * defenseFor(b) + MapAnalysis.meters(staging_x, staging_y, b.getGridX(), b.getGridY());
            if (score < best_score) {
                best_score = score;
                best = b;
            }
        }
        return best;
    }

    /**
     * allin_ticks and push_ticks ({@code push}): musters everything on {@code t}, past the gate (considerAttack, plan).
     */
    private void allInMuster(@NonNull Building t, boolean push) {
        if (push) {
            last_push = ai.now();
            ai.aiLog().count("push_muster");
            if (threat_level >= 2)
                ai.aiLog().count("push_under_threat");
        } else
            ai.aiLog().count("allin_muster");
        ai.log(String.format(
                "%s: no copy out for %.0f s, no threat at the base for %.0f s (threat now %.1f), so %s" + " (defense %.1f) against army %.1f + stock %.1f",
                push ? "push" : "all-in",
                GauntletAI.seconds(ai.now() - last_out_time), GauntletAI.seconds(ai.now() - last_base_threat),
                base_threat_strength, describe(t), defenseFor(t), armyStrength(), stockStrength()));
        target = t;
        all_in = true;
        ladder_target = null;
        ladder_owner = null;
        mode = Mode.MUSTER;
        muster_start = ai.now();
    }

    /**
     * last_stand: from endgame_from_ticks, no quarters or armory of ours, finished or placed, no peon, and a warrior or
     * the chieftain left (Strategy.last_stand).
     */
    private boolean lastStand() {
        Strategy s = ai.strategy();
        Intel intel = ai.intel();
        return s.last_stand && ai.now() >= s.endgame_from_ticks && intel.peons.isEmpty() && intel.quarters.isEmpty()
                && intel.armories.isEmpty() && intel.quarters_sites.isEmpty() && intel.armory_sites.isEmpty()
                && (!intel.warriors.isEmpty() || intel.chieftain != null);
    }

    /** last_stand: every 30 s, every warrior and the chieftain attack the enemy unit or building nearest them. */
    private void lastStandTick() {
        if (!ai.periodDue(last_stand_order, 1500f))
            return;
        last_stand_order = ai.now();
        Intel intel = ai.intel();
        List<Unit> units = new ArrayList<>();
        for (Unit w : intel.warriors)
            if (!w.isDead() && !w.isMounted())
                units.add(w);
        Unit chief = intel.chieftain;
        if (chief != null && !chief.isDead() && !chief.isMounted())
            units.add(chief);
        if (units.isEmpty())
            return;
        int[] c = MapAnalysis.centroid(units);
        Selectable<?> t = nearestEnemyUnit(c[0], c[1]);
        int best = t == null ? Integer.MAX_VALUE : MapAnalysis.dist2(c[0], c[1], t.getGridX(), t.getGridY());
        for (Building b : intel.enemy_buildings) {
            if (b.isDead() || !b.getOwner().isAlive())
                continue;
            int d = MapAnalysis.dist2(c[0], c[1], b.getGridX(), b.getGridY());
            if (d < best) {
                best = d;
                t = b;
            }
        }
        if (t == null)
            return;
        if (!last_stand_seen) {
            last_stand_seen = true;
            ai.aiLog().count("last_stand");
            ai.log(String.format("last stand: no base, no peon; %d units on %s", units.size(), describe(t)));
        }
        ai.aiLog().count("last_stand_order");
        ai.owner().setTarget(units.toArray(new Selectable<?>[0]), t, Action.ATTACK, true);
    }

    /**
     * The muster's gate: our army and stock ({@code potential}) against a target's defense. Chieftains decide battles:
     * ours counts as a big plus and theirs (the target owner's, or another copy's within {@code others} cells) as a
     * big minus, unless ours can answer his. {@code count}: count capped_min_blocked (the best target's test only).
     */
    private boolean musterGo(@NonNull Selectable<?> t, float potential, float defense, int others, boolean count) {
        Intel intel = ai.intel();
        Strategy strategy = ai.strategy();
        boolean chief = intel.chieftain != null && intel.chieftain.getHitPoints() > 30
                && !(strategy.shred && strategy.shred_strict); // strict shred keeps him home
        boolean enemy_chief = false;
        int cr2 = others * others;
        for (Unit c : intel.enemy_chieftains)
            enemy_chief |= !c.isDead() && c.getHitPoints() > 15 && (!strategy.gate_owner || ai.enemiesAlive() <= 1
                    || c.getOwner() == t.getOwner()
                    || MapAnalysis.dist2(c.getGridX(), c.getGridY(), t.getGridX(), t.getGridY()) <= cr2);
        float bonus = chief && !enemy_chief ? 1.4f : chief ? 1.05f : enemy_chief ? .7f : 1f;
        boolean capped = nearUnitCap();
        float caution = attack_caution;
        boolean go = potential >= strategy.attack_min_strength
                && potential * bonus >= strategy.attack_ratio * caution * defense;
        go |= potential >= strategy.attack_max_strength * caution && potential * bonus >= .8f * caution * defense;
        boolean capped_go = capped && potential * bonus >= strategy.capped_ratio * caution * defense;
        if (capped_go && potential < strategy.capped_min_strength) {
            capped_go = false;
            if (!go && count)
                ai.aiLog().count("capped_min_blocked");
        }
        return go || capped_go;
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
        float defense = withEnemyTowers(1.1f * enemyFightersNear(bx, by, 24), bx, by, 12);
        float army = armyStrength();
        // A handful sent at a tower going up only feeds it: go with enough to win outright.
        if (army < Math.max(10f, 2f * defense))
            return;
        target = b;
        strike = true;
        ai.log(String.format("strike on %s's %s at %d,%d: army %.1f vs %.1f", b.getOwner().getPlayerInfo().getName(),
                Intel.kind(b), bx, by, army, defense));
        launchAttack();
    }

    /** The enemy building closest to our own inside the base or near our gatherers, or null. */
    private @Nullable Building intruder() {
        Intel intel = ai.intel();
        List<Building> own = intel.finishedBuildings();
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

    /**
     * Warriors our main armory could deploy right away. weapon_reserve: not the weapons it holds back below threat 2,
     * nor the workers kept in for them, which neither join an attack nor let a muster launch.
     */
    private float stockStrength() {
        Building armory = ai.intel().armory();
        if (armory == null)
            return 0f;
        int[] held = ai.economy().weaponReserve(armory);
        return held == null ? stockStrength(armory) : stockStrength(armory, held[0], held[1], held[2]);
    }

    /** Warriors an armory could deploy right away: weapons in stock, as far as peons inside can carry them. */
    private static float stockStrength(@NonNull Building armory) {
        return stockStrength(armory, 0, 0, 0);
    }

    /** The same less the chicken, iron and rock axes held back, and as many workers. */
    private static float stockStrength(@NonNull Building armory, int held_chicken, int held_iron, int held_rock) {
        int workers = armory.getUnitContainer().getNumSupplies();
        int iron = armory.getSupplyContainer(com.oddlabs.tt.model.weapon.IronAxeWeapon.class).getNumSupplies();
        int chicken = armory.getSupplyContainer(com.oddlabs.tt.model.weapon.RubberAxeWeapon.class).getNumSupplies();
        int rock = armory.getSupplyContainer(com.oddlabs.tt.model.weapon.RockAxeWeapon.class).getNumSupplies();
        if (held_chicken + held_iron + held_rock > 0) {
            workers = Math.max(0, workers - held_chicken - held_iron - held_rock);
            chicken = Math.max(0, chicken - held_chicken);
            iron = Math.max(0, iron - held_iron);
            rock = Math.max(0, rock - held_rock);
        }
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
            Unit u = e.getKey();
            // retreat_cap_ticks: the stranded do not go, so the muster does not wait for them either
            if (strandedOut(u))
                continue;
            total++;
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), staging_x, staging_y) <= 12 * 12)
                near++;
        }
        boolean gathered = total > 0 && near >= total * 8 / 10;
        boolean timeout = ai.now() - muster_start > 2250f;
        if (ai.strategy().launch_recheck && timeout && !gathered && threat_level >= 2
                && base_threat_strength > ai.strategy().attack_threat_ratio * (stagingStrength(30) + stockStrength())) {
            mode = Mode.HOME;
            next_wave_time = ai.now() + 3000f;
            ai.log(String.format("muster dropped: %.1f in the base", base_threat_strength));
            ai.aiLog().count("muster_dropped");
            return;
        }
        if ((!stock_left && pending == 0 && gathered) || timeout)
            launchAttack();
    }

    private void launchAttack() {
        boolean chosen = target == null || target.isDead();
        Selectable<?> t = chosen ? chooseTarget(staging_x, staging_y) : target;
        if (chosen)
            noteDecapitate(t, "launch");
        // remnant_ladder_ticks: the attack finishes the ladder's copy only when it goes for the ladder's pick
        if (t != ladder_target)
            ladder_owner = null;
        ladder_target = null;
        if (t == null) {
            mode = Mode.HOME;
            return;
        }
        setTarget(t);
        float s = 0f;
        int kept = 0;
        float kept_value = 0f;
        for (Unit u : beyondGuard(withRole(Role.ARMY))) {
            // retreat_cap_ticks: the stranded stay out (walking home), else the army's centre lands between the groups
            if (strandedOut(u)) {
                kept++;
                kept_value += Combat.value(u);
                continue;
            }
            roles.put(u, Role.ATTACK);
            s += Combat.value(u);
        }
        if (kept > 0) {
            ai.aiLog().count("retreat_stranded_kept");
            ai.log(String.format("launch leaves %d stranded attackers (%.1f) out, still walking home", kept,
                    kept_value));
        }
        attack_initial_strength = s;
        worn_peak = s;
        worn_history.clear();
        worn_skip_counted = false;
        split_guard_counted = false;
        attack_kills_start = ai.owner().getUnitsKilled();
        attack_losses_start = ai.owner().getUnitsLost();
        attack_running = true;
        last_progress_time = ai.now();
        markCapProgress();
        cap_strikes = 0;
        // stall_engaged_ticks: the watchdog marks the new attack at its first round
        engaged_target = null;
        best_target_dist = Integer.MAX_VALUE;
        best_line_dist = Integer.MAX_VALUE;
        sealed_logged = false;
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
            if ((s == PeonState.IDLE || s == PeonState.GATHER_TREE || s == PeonState.GATHER_ROCK
                    || s == PeonState.GATHER_IRON || s == PeonState.MOVE) && !ai.economy().reservedPlacer(p))
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
        int[] back = MapAnalysis.towards(c[0], c[1], staging_x, staging_y, 6);
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
            if (last != null && !ai.periodDue(last, 150f))
                continue;
            // Already swinging at a tower: leave it be, a new order would start the swing over.
            if (tower != null && p.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.AttackController)
                continue;
            sapper_orders.put(p, ai.now());
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
                || !ai.periodDue(last_creep_try, 1000f) || ai.intel().sappers.size() < 4)
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
        last_creep_try = ai.now();
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

    private void setTarget(@NonNull Selectable<?> t) {
        setTarget(t, true);
    }

    /**
     * Attacks t; a target more than 8 cells from the last one gets a field of its own, which restarts the stall
     * clocks, stall_cap's only with {@code cap_progress} (stall_cap_keep: not for a calm stall's retarget).
     */
    private void setTarget(@NonNull Selectable<?> t, boolean cap_progress) {
        if (ai.strategy().focus_bonus > 0f && t.getOwner() != ai.owner())
            focus_owner = t.getOwner();
        target = t;
        if (target_field == null || MapAnalysis.dist2(target_x, target_y, t.getGridX(), t.getGridY()) > 8 * 8) {
            target_x = t.getGridX();
            target_y = t.getGridY();
            DistanceField strict = ai.map().computeField(target_x, target_y, Integer.MAX_VALUE);
            target_field = strict;
            target_corner = false;
            Strategy s = ai.strategy();
            if (s.corner_fields && ai.now() >= s.pocket_from_ticks
                    && strict.getAround(staging_x, staging_y, 2) == DistanceField.UNREACHABLE)
                cornerField(strict);
            best_target_dist = Integer.MAX_VALUE;
            best_line_dist = Integer.MAX_VALUE;
            sealed_logged = false;
            last_progress_time = ai.now();
            if (cap_progress)
                markCapProgress();
        }
    }

    /**
     * corner_fields: the target field does not reach the staging point. The field with corner cuts, which the engine
     * walks units through, reaches every cell the strict one does and maybe more; when it reaches the staging point it
     * takes the strict one's place (corner_field): the march, its progress and the dead-region test then follow the
     * engine's rule. When it does not (corner_field_sealed: still a dead region if the attack stalls), the strict one
     * stays: the corner cuts only shorten its distances, which moved the march for nothing (s6099 N=14: it diverged at
     * 71 min, and the game won at 168 min was lost at 117).
     */
    private void cornerField(@NonNull DistanceField strict) {
        DistanceField corner = ai.map().computeField(target_x, target_y, Integer.MAX_VALUE, true);
        boolean reaches = corner.getAround(staging_x, staging_y, 2) != DistanceField.UNREACHABLE;
        if (reaches) {
            target_field = corner;
            target_corner = true;
        }
        ai.aiLog().count(reaches ? "corner_field" : "corner_field_sealed");
        if (ai.logging()) {
            int strict_cells = 0;
            int corner_cells = 0;
            for (int i = 0; i < corner.raw().length; i++) {
                if (strict.raw()[i] != DistanceField.UNREACHABLE)
                    strict_cells++;
                if (corner.raw()[i] != DistanceField.UNREACHABLE)
                    corner_cells++;
            }
            ai.log(String.format("corner field from %d,%d: %d cells (strict %d), staging %s", target_x, target_y,
                    corner_cells, strict_cells, reaches ? "reaches it through a corner cut" : "still cut off"));
        }
    }

    /** Within 10 units of the unit cap, where losses are replaced for free. */
    private boolean nearUnitCap() {
        Player owner = ai.owner();
        return owner.getUnitCountContainer().getNumSupplies() >= owner.getWorld().getMaxUnitCount() - 10;
    }

    /** Lets caution from past attacks wear off while the army sits capped at home. */
    private void easeCaution() {
        boolean capped = nearUnitCap();
        if (mode != Mode.HOME || !capped || attack_caution <= 1f || ai.strategy().caution_decay <= 1f) {
            last_caution_ease = ai.now();
            return;
        }
        if (!ai.periodDue(last_caution_ease, 3000f))
            return;
        last_caution_ease = ai.now();
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
        if (column_until >= 0f) {
            column_until = -1f;
            column_target = null;
            ai.aiLog().count("unjam_ended");
            ai.log("unjam: column march over, the attack ended");
        }
        jam_scans = 0;
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == Role.ATTACK || e.getValue() == Role.REINFORCE)
                e.setValue(Role.ARMY);
        mode = Mode.HOME;
        target = null;
        ladder_owner = null;
        all_in = false;
        next_wave_time = ai.now() + (recalled ? ai.strategy().recall_cooldown_ticks : 1000f);
        recalled = false;
        strike = false;
    }

    /** defend_stable: when the current engage began. */
    private float engage_since = -500f;

    /** The attack being ended was called home (recall_cooldown_ticks). */
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
        List<Unit> army = withRole(Role.ATTACK);
        if (army.isEmpty()) {
            endAttack();
            return;
        }
        int[] c = MapAnalysis.centroid(army);
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
            last_progress_time = ai.now();
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
        if (ai.logging() && ai.periodDue(last_trace, 200f))
            traceBattle(army, c, total, local_enemy);
        // stall_engaged_ticks: the wedge watchdog looks before any of the paths below can return.
        if (engagedWatch() && wedgeWatch(army, c, pinned))
            return;
        // Enemies lying stunned nearby cannot fight back for a while. As long as we can take on the ones still awake,
        // run the stunned down instead of weighing the odds, which would count them as awake again soon.
        if (!pinned && ai.strategy().exploit_stun && chargeStunned(army, c, total))
            return;
        if (ai.strategy().siege && !pinned && siege(army, c, total))
            return;
        if (!all_in && !toot && !pinned && ai.strategy().precontact_ratio > 0f && !anyFighting(army)) {
            // Before contact, look at everything that can defend the area, not just what is next to us: turning
            // back now costs nothing, walking into a stronger defense costs the army.
            float wide = withEnemyTowers(enemyFightersNear(c[0], c[1], 36), c[0], c[1], 36);
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
        if (!all_in && !toot && !pinned && outmatched) {
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
            // remnant_ladder_ticks: the copy whose site or remnant fell is finished before anything else
            Selectable<?> next = ladder_owner != null ? remnantNext(target, c) : null;
            if (next == null) {
                next = retarget(c);
                noteDecapitate(next, "next");
            }
            if (next == null) {
                endAttack();
                return;
            }
            setTarget(next);
        }
        // decapitate: a chieftain target is followed as it walks
        if (decapitating())
            followChief(c);
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
        march_calm_time = ai.now();
        // reinforce_intercept: a reinforcement group worth 40 % of the army within 80 cells is waited for (20 s at
        // most per target) instead of being left to chase the army across the field at the same speed.
        if (ai.strategy().reinforce_intercept && waitForReinforcements(c, total))
            return;
        // unjam: a jammed army marches as a column (see noteBlocked); the idle plug at a pass exit walks on too.
        boolean column = field != null && ai.now() < column_until;
        if (column_until >= 0f && !column)
            endColumnMarch(dist, c);
        for (Unit u : army) {
            int d = field != null ? field.getAround(u.getGridX(), u.getGridY(), 1) : DistanceField.UNREACHABLE;
            if (column && d != DistanceField.UNREACHABLE) {
                // Each unit walks on towards its own point; the front walks on too, clear of the pass exit where the
                // plug stood (one lead past the pivot), but no farther than two leads ahead of the pivot, so the
                // column cannot string out without end (uj3-s98: 45 of 75 units far from the centre, 20 lost).
                int step = pivot != null ? Math.min(lead, d - (pivot_dist - 2 * lead)) : lead;
                if (step >= 8) {
                    int[] own = field.stepTowardsSource(u.getGridX(), u.getGridY(), step);
                    attackGround(u, own[0], own[1], false);
                }
                continue;
            }
            // Units well ahead of the pivot wait for the rest instead of walking on alone.
            if (pivot != null && d != DistanceField.UNREACHABLE && d < pivot_dist - 24)
                continue;
            attackGround(u, waypoint[0], waypoint[1], false);
        }
        // Give up only when the army stops getting anywhere, not because the march is long.
        if (dist != DistanceField.UNREACHABLE && dist < best_target_dist - 20) {
            // a real gain, not the first measure after a new target
            boolean gain = best_target_dist != Integer.MAX_VALUE;
            if (gain)
                cap_strikes = 0;
            best_target_dist = dist;
            last_progress_time = ai.now();
            // stall_cap_keep: the first measure on a new field leaves stall_cap's clock alone
            if (gain || !capKeep())
                markCapProgress();
        }
        if (pivot == null && strategy.sealed_progress && ai.now() >= strategy.pocket_from_ticks)
            sealedProgress(c);
        if (ai.now() - last_progress_time > 3750f && (ai.strategy().stall_peons ? armed == 0f : local_enemy == 0f)) {
            stalled(c, false);
            return;
        }
        // stall_cap: the calm-march clock above restarts whenever anyone fights, so an army wedged at a pass with a
        // few enemies about (or peons to cut down) never stalls: s6021 at N=6 stood 5 hours at 240 warriors. The wedge
        // watchdog (stall_engaged_ticks) takes its place while on.
        float cap = ai.strategy().stall_cap_ticks;
        if (cap > 0f && !engagedWatch()) {
            if (ai.owner().getUnitsKilled() - cap_progress_kills >= ai.strategy().stall_cap_kills) {
                markCapProgress();
                cap_strikes = 0;
            } else if (ai.now() - cap_progress_time > cap) {
                if (cap_strikes++ == 0) {
                    ai.aiLog().count("stall_cap");
                    stalled(c, true);
                } else {
                    // A new target did not get the army moving either: it is wedged (s6021: every unit waits on a
                    // pivot stuck in a pocket). Walk it home and muster again from there.
                    ai.aiLog().count("stall_cap_retreat");
                    ai.log("attack wedged: the army walks home to re-form");
                    cap_strikes = 0;
                    // Everyone breaks off, fighters too: a few units locked on enemies they cannot reach can plug the
                    // pass for the rest (the rearguard rule would leave them there).
                    for (Unit u : army)
                        move(u, staging_x, staging_y);
                    beginRetreat();
                }
            }
        }
    }

    private void markCapProgress() {
        cap_progress_time = ai.now();
        cap_progress_kills = ai.owner().getUnitsKilled();
    }

    /**
     * sealed_progress: no attacking unit stands where the target field reaches, so the field cannot measure the march
     * (s8462: every attack on a chieftain in a 15-cell pocket stalled 75 s after launch, ~85 cells short). A 20 m gain
     * in straight-line distance from the army's centre to the target counts as progress instead, as a field gain does.
     */
    private void sealedProgress(int @NonNull [] c) {
        int line = (int) MapAnalysis.meters(c[0], c[1], target_x, target_y);
        if (!sealed_logged) {
            sealed_logged = true;
            ai.log(String.format("sealed march: no unit on the target field, by the straight line (%d m to %d,%d)",
                    line, target_x, target_y));
        }
        if (line >= best_line_dist - 20)
            return;
        // a real gain, not the first measure after a new target
        boolean gain = best_line_dist != Integer.MAX_VALUE;
        if (gain) {
            cap_strikes = 0;
            ai.aiLog().count("sealed_progress");
        }
        best_line_dist = line;
        last_progress_time = ai.now();
        // stall_cap_keep: the first measure on a new target leaves stall_cap's clock alone
        if (gain || !capKeep())
            markCapProgress();
    }

    /** stall_cap_keep, from unlock_from_ticks. */
    private boolean capKeep() {
        Strategy s = ai.strategy();
        return s.stall_cap_keep && ai.now() >= s.unlock_from_ticks;
    }

    /** stall_engaged_ticks: the wedge watchdog is on (from stall_engaged_from_ticks). */
    private boolean engagedWatch() {
        Strategy s = ai.strategy();
        return s.stall_engaged_ticks > 0f && ai.now() >= s.stall_engaged_from_ticks;
    }

    /**
     * stall_engaged_ticks: the wedge watchdog, first in every attack round, so that no charge, siege, pillage, hold or
     * engage path keeps it from looking (s6215 N=15: 216 warriors engaged enemies across a 1-cell pass for 260 min
     * and no stall rule was ever reached). Progress clears its strikes and restarts its clock: a 20 m gain of the
     * march pivot on the target field, or of the straight line from the army's centre while no attacker stands on
     * the field; the target falling, or losing a tenth of its hit points; stall_cap_kills deaths among the enemy units
     * seen within 30 cells of the army since the last progress, or among the attackers then. A new field (a new
     * target) is a new baseline, not progress, so the calm stall's retargets do not restart it. After
     * stall_engaged_ticks without progress, strike 1 stalls the target (skipped, the next one from where the army
     * stands), and strike 2 in a row walks every attacker home and remembers the wedge. Returns whether it acted.
     */
    private boolean wedgeWatch(@NonNull List<@NonNull Unit> army, int @NonNull [] c, boolean pinned) {
        Strategy s = ai.strategy();
        Selectable<?> t = target;
        // A fallen target is replaced further on in this round; the next round counts it.
        if (t == null || t.isDead())
            return false;
        Selectable<?> was = engaged_target;
        if (was == null) {
            // the first round of this attack
            engaged_strikes = 0;
            markEngaged(army, c);
            return false;
        }
        String why = null;
        if (t != was) {
            if (was.isDead())
                why = "the target fell";
            engaged_target = t;
            engaged_hp = hitPoints(t);
        }
        DistanceField f = target_field;
        int pivot = f != null ? attackPivotDist(f) : DistanceField.UNREACHABLE;
        int line = (int) MapAnalysis.meters(c[0], c[1], target_x, target_y);
        if (f != engaged_field) {
            // a new field: a new baseline
            engaged_field = f;
            engaged_dist = pivot;
            engaged_line = line;
        } else if (pivot != DistanceField.UNREACHABLE) {
            if (engaged_dist == DistanceField.UNREACHABLE)
                engaged_dist = pivot;
            else if (pivot <= engaged_dist - 20)
                why = "the pivot gained 20 m";
        } else if (line <= engaged_line - 20)
            why = "the army got 20 m nearer in a line";
        if (why == null && t instanceof Building b && engaged_hp >= 0
                && engaged_hp - b.getHitPoints() >= Math.max(1, b.getTemplate().getMaxHitPoints() / 10))
            why = "the target lost a tenth of its hit points";
        // enemies that came up since the last progress count too (another copy's units joining through their scans,
        // defenders arriving late): only the ones seen then, and a fight being won read as no progress
        noteEngagedFoes(c);
        if (why == null && deadCount(engaged_foes) >= s.stall_cap_kills)
            why = "kills";
        if (why == null && deadCount(engaged_ours) >= s.stall_cap_kills)
            why = "losses";
        if (why != null) {
            if (engaged_strikes > 0)
                ai.log("wedge watch: " + why + ", strikes cleared");
            engaged_strikes = 0;
            markEngaged(army, c);
            return false;
        }
        if (pinned || ai.now() - engaged_time < s.stall_engaged_ticks)
            return false;
        if (ai.logging()) {
            String piv = pivot == DistanceField.UNREACHABLE ? "-" : String.valueOf(pivot);
            ai.log(String.format(
                    "wedge watch: no progress in %.0f s at %d,%d (pivot %s m from %d,%d, %d m in a line; " + "dead since: %d of %d enemies near, %d of %d attackers), strike %d",
                    GauntletAI.seconds(ai.now() - engaged_time), c[0], c[1], piv, target_x, target_y, line,
                    deadCount(engaged_foes), engaged_foes.size(), deadCount(engaged_ours), engaged_ours.size(),
                    engaged_strikes + 1));
        }
        if (engaged_strikes++ == 0) {
            ai.aiLog().count("stall_engaged");
            stalled(c, true);
            // strike 2 comes another stall_engaged_ticks on, measured from here and on the new target
            markEngaged(army, c);
            return true;
        }
        // A new target did not get the army anywhere either: walk it home, fighters too, and keep the next musters off
        // the way through here.
        ai.aiLog().count("stall_engaged_retreat");
        ai.log("attack wedged in contact: the army walks home to re-form");
        engaged_strikes = 0;
        rememberWedge(army, c);
        for (Unit u : army)
            move(u, staging_x, staging_y);
        beginRetreat();
        return true;
    }

    /** stall_engaged_ticks: progress, from here and now. */
    private void markEngaged(@NonNull List<@NonNull Unit> army, int @NonNull [] c) {
        Selectable<?> t = target;
        engaged_time = ai.now();
        engaged_target = t;
        engaged_hp = t != null && !t.isDead() ? hitPoints(t) : -1;
        DistanceField f = target_field;
        engaged_field = f;
        engaged_dist = f != null ? attackPivotDist(f) : DistanceField.UNREACHABLE;
        engaged_line = (int) MapAnalysis.meters(c[0], c[1], target_x, target_y);
        engaged_foes.clear();
        noteEngagedFoes(c);
        engaged_ours.clear();
        engaged_ours.addAll(army);
    }

    /**
     * stall_engaged_ticks: adds the living enemy warriors, chieftains and peons within ENGAGE_RADIUS + 8 (30) cells of
     * the army's centre to those whose deaths count as progress (engaged_foes, in the order first seen).
     */
    private void noteEngagedFoes(int @NonNull [] c) {
        Intel intel = ai.intel();
        int r2 = (ENGAGE_RADIUS + 8) * (ENGAGE_RADIUS + 8);
        for (List<Unit> group : List.of(intel.enemy_warriors, intel.enemy_chieftains, intel.enemy_peons))
            for (Unit e : group)
                if (!e.isDead() && MapAnalysis.dist2(e.getGridX(), e.getGridY(), c[0], c[1]) <= r2)
                    engaged_foes.add(e);
    }

    /** A building's hit points, -1 for anything else (the watchdog weighs only buildings' damage). */
    private static int hitPoints(@NonNull Selectable<?> t) {
        return t instanceof Building b ? b.getHitPoints() : -1;
    }

    private static int deadCount(java.util.@NonNull Collection<@NonNull Unit> units) {
        int n = 0;
        for (Unit u : units)
            if (u.isDead())
                n++;
        return n;
    }

    /**
     * stall_engaged_ticks: remembers the wedge for wedge_memory_ticks, at the cell of the attacker nearest the army's
     * centre (the centre itself may be cliff when the army wraps around one), with a field from there.
     */
    private void rememberWedge(@NonNull List<@NonNull Unit> army, int @NonNull [] c) {
        float memory = ai.strategy().wedge_memory_ticks;
        if (memory <= 0f)
            return;
        int x = c[0];
        int y = c[1];
        int best = Integer.MAX_VALUE;
        for (Unit u : army) {
            int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), c[0], c[1]);
            if (d < best) {
                best = d;
                x = u.getGridX();
                y = u.getGridY();
            }
        }
        wedges.add(new Wedge(x, y, ai.now(), ai.map().computeField(x, y, Integer.MAX_VALUE, true)));
        if (wedges.size() > 3)
            wedges.removeFirst();
        ai.aiLog().count("wedge_remembered");
        ai.log(String.format("wedge at %d,%d remembered for %.0f s: buildings whose way runs through it come last", x,
                y, GauntletAI.seconds(memory)));
    }

    /**
     * Log only, every 4 s of an attack: the army (size, lasting value, units 16+ cells from its centre, fighting,
     * stunned, mean height) and what stands within ENGAGE_RADIUS of its centre.
     */
    private void traceBattle(@NonNull List<@NonNull Unit> army, int @NonNull [] c, float total, float local_enemy) {
        Intel intel = ai.intel();
        last_trace = ai.now();
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

    /**
     * exploit_stun: with enemies worth 3 or more lying stunned within 36 cells, an army worth .8 of the awake ones
     * (towers included) runs the stunned down, unless a feared enemy chieftain within 45 cells has his stun ready.
     * Returns whether it charged.
     */
    private boolean chargeStunned(@NonNull List<@NonNull Unit> army, int @NonNull [] c, float total) {
        List<Unit> stunned = stunnedEnemiesNear(c[0], c[1], 36);
        if (stunned.isEmpty())
            return false;
        float asleep = 0f;
        for (Unit e : stunned)
            asleep += Combat.lastingValue(e);
        float awake = withEnemyTowers(enemyFightersNear(c[0], c[1], 36), c[0], c[1], 36);
        // An enemy chieftain with his spell ready would stun the charge in turn (unless his stun is not feared).
        if (!(asleep >= 3f && total >= .8f * awake && !(ai.strategy().enemy_stun_mult > 1f && enemyStunReadyNear(
                c[0], c[1], 45))))
            return false;
        if (ai.now() - last_charge_log > 500f) {
            last_charge_log = ai.now();
            ai.log(String.format("charging %d stunned enemies (%.1f asleep, %.1f awake, army %.1f)", stunned.size(),
                    asleep, awake, total));
        }
        int[] sc = MapAnalysis.centroid(stunned);
        List<Selectable<?>> prey = new ArrayList<>(stunned);
        // The ones still awake keep throwing: each warrior weighs them against the helpless.
        if (ai.strategy().charge_mixed)
            for (Unit e : ai.intel().enemy_warriors)
                if (!Intel.isStunned(e) && MapAnalysis.dist2(e.getGridX(), e.getGridY(), c[0],
                        c[1]) <= (ENGAGE_RADIUS + 8) * (ENGAGE_RADIUS + 8))
                    prey.add(e);
        engageSpread(army, prey, sc[0], sc[1], true);
        huntChieftains(army);
        return true;
    }

    /**
     * unjam: the column march's time is up. Counts and logs how it went: through (the pivot got 30 m closer to the
     * same target, or the target fell), retarget (the stall rule, or a new target near the old one, took over) or
     * still (the column did not move the army on).
     */
    private void endColumnMarch(int dist, int @NonNull [] c) {
        column_until = -1f;
        int gain = column_start_dist != DistanceField.UNREACHABLE
                && dist != DistanceField.UNREACHABLE ? column_start_dist - dist : 0;
        Selectable<?> was = column_target;
        String outcome = was == null
                || was.isDead() ? "through" : was != target ? "retarget" : gain >= 30 ? "through" : "still";
        ai.aiLog().count("unjam_" + outcome);
        ai.log(String.format(
                "unjam: column march over (%s), pivot %d m from the target at %d,%d (%d m at the start), army at %d,%d",
                outcome, dist, target_x, target_y, column_start_dist, c[0], c[1]));
        column_target = null;
    }

    /**
     * The attack got no nearer its target for 75 s with nothing to fight: with skip_stalled the target (and, when the
     * staging point cannot reach its region, the region) is dropped for a while, and with stall_calm a reachable one
     * gives way to the next target from where the army stands; otherwise the army comes home. {@code from_cap}: a
     * strike of stall_cap or the wedge watchdog, whose retarget restarts stall_cap's clock even with stall_cap_keep.
     */
    private void stalled(int @NonNull [] c, boolean from_cap) {
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
            stalled_targets.put(target, ai.now());
            ai.aiLog().count("target_stalled");
            DistanceField f = target_field;
            if (f != null && f.getAround(staging_x, staging_y, 2) == DistanceField.UNREACHABLE) {
                dead_regions.add(f);
                dead_region_times.add(ai.now());
                if (dead_regions.size() > 3) {
                    dead_regions.removeFirst();
                    dead_region_times.removeFirst();
                }
                ai.aiLog().count("target_region_dead");
                deadRegionWide(f);
            }
            // stall_calm: a target the army can reach but not get to (a deadlock at a corner) is dropped for the
            // next one from where the army stands, instead of walking everyone home.
            if (ai.strategy().stall_calm && (f == null || f.getAround(staging_x, staging_y,
                    2) != DistanceField.UNREACHABLE)) {
                Selectable<?> next = retarget(c);
                noteDecapitate(next, "stall");
                if (next != null) {
                    // stall_cap_keep: the calm stall's retarget is no progress for stall_cap
                    boolean cap_progress = from_cap || !capKeep();
                    setTarget(next, cap_progress);
                    // A new target near the old one keeps the old field: restart the clock either way, or the
                    // next tick would ban it too.
                    last_progress_time = ai.now();
                    if (cap_progress) {
                        markCapProgress();
                    } else {
                        ai.aiLog().count("stall_cap_kept");
                        ai.log(String.format("stall_cap clock kept: %.0f s since the last gain",
                                GauntletAI.seconds(ai.now() - cap_progress_time)));
                    }
                    best_target_dist = Integer.MAX_VALUE;
                    best_line_dist = Integer.MAX_VALUE;
                    sealed_logged = false;
                    ai.aiLog().count("stall_retarget");
                    return;
                }
            }
        }
        beginRetreat();
    }

    /**
     * dead_region_reach: counts (dead_region_wide) and logs a new dead region that skips enemy buildings the radius 2
     * test would have kept, the other quarters and armories of a sealed base (s6657).
     */
    private void deadRegionWide(@NonNull DistanceField f) {
        if (ai.now() < ai.strategy().pocket_from_ticks || ai.strategy().dead_region_reach == 2)
            return;
        int wide = 0;
        for (Building b : ai.intel().enemy_buildings) {
            if (b.isDead() || b == target)
                continue;
            int reach = deadReach(b);
            if (reach != 2 && f.getAround(b.getGridX(), b.getGridY(), 2) == DistanceField.UNREACHABLE
                    && f.getAround(b.getGridX(), b.getGridY(), reach) != DistanceField.UNREACHABLE)
                wide++;
        }
        if (wide == 0)
            return;
        ai.aiLog().count("dead_region_wide");
        ai.log(String.format("dead region from %d,%d also skips %d more buildings (reach %d, towers 2)", target_x,
                target_y, wide, ai.strategy().dead_region_reach));
    }

    /**
     * The strength the worn retreat measures the attacking army (now worth total) against, by worn_basis: the launch
     * strength plus every reinforcement that joined, the peak since the launch, or the peak of the last
     * worn_window_ticks game ticks.
     */
    private float wornBasis(float total) {
        Strategy strategy = ai.strategy();
        if (strategy.worn_basis == 1) {
            worn_peak = Math.max(worn_peak, total);
            return worn_peak;
        }
        if (strategy.worn_basis == 2) {
            float now = ai.now();
            while (!worn_history.isEmpty() && worn_history.getLast()[1] <= total)
                worn_history.removeLast();
            worn_history.addLast(new float[]{now, total});
            while (worn_history.size() > 1 && worn_history.getFirst()[0] < now - strategy.worn_window_ticks)
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
            if (hold_until < 0f && closing && !coming && ai.periodDue(last_hold_skip, 600f)) {
                last_hold_skip = ai.now();
                ai.aiLog().count("hold_skipped_static");
            }
            closing = coming;
        }
        if (hold_until < 0f) {
            if (!closing)
                return false;
            if (motion)
                ai.aiLog().count("hold_coming");
            hold_until = ai.now() + 600f;
            hold_spot = ai.map().highGround(c[0], c[1], 6);
            ai.log(String.format("holding at %d,%d (%.0fm up) for enemy army at %d,%d", hold_spot[0], hold_spot[1],
                    ai.map().height(hold_spot[0], hold_spot[1]) - ai.map().height(c[0], c[1]), enemy[0], enemy[1]));
        }
        if (ai.now() > hold_until)
            return false;
        for (Unit u : army)
            attackGround(u, hold_spot[0], hold_spot[1], false);
        return true;
    }

    /**
     * hold_closing 1: whether the enemy group centred at {@code enemy} came at least 2 cells nearer to our army's
     * centre {@code c} by its own motion since its centre of 2-4 s ago, with fewer than half of its units parked.
     */
    private boolean groupComing(@NonNull List<@NonNull Unit> units, int @NonNull [] enemy, int @NonNull [] c) {
        float now = ai.now();
        hold_groups.removeIf(g -> g[0] < now - 200f);
        float[] then = null;
        for (float[] g : hold_groups)
            if (g[0] <= now - 100f)
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
            if (Intel.isParked(u))
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
        if (ai.now() - last_pillage_log > 750f) {
            last_pillage_log = ai.now();
            ai.log(String.format("pillaging %d peons outside the towers with %.1f", prey.size(), total));
        }
        int[] pc = MapAnalysis.centroid(prey);
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
        int[] sc = MapAnalysis.centroid(stunned);
        engageSpread(units, stunned, sc[0], sc[1], true);
        huntChieftains(units);
        return true;
    }

    /** Where the chieftain is meeting an enemy army alone to blast it, or null. */
    int @Nullable [] blastPlay() {
        return ai.now() < blast_play_until ? new int[]{threat_x, threat_y} : null;
    }

    /**
     * Against a strong attack on the base with the blast charged: the defenders step back out of its reach and the
     * chieftain walks up to the enemy alone. Returns whether the play has the defenders' orders this tick.
     */
    private boolean blastDefense(@NonNull List<@NonNull Unit> defenders, float enemy) {
        Strategy strategy = ai.strategy();
        float now = ai.now();
        if (!strategy.blast || !strategy.blast_defense)
            return false;
        if (now >= blast_play_until) {
            if (threat_level < 2 || enemy < strategy.blast_min || !ai.periodDue(last_blast_play, 3000f)
                    || !ai.chieftain().blastReady())
                return false;
            blast_play_until = now + strategy.blast_play_ticks;
            last_blast_play = now;
            ai.log(String.format("blast play against %.1f at %d,%d", enemy, threat_x, threat_y));
        }
        if (!ai.chieftain().blastReady() && ai.chieftain().sinceCast() > 250f) {
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
            ai.log(String.format("siege over after %.0fs", GauntletAI.seconds(ai.now() - siege_start)));
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
        float now = ai.now();
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
            boolean about_to = stunner && since >= 90f && since < 225f && MapAnalysis.dist2(chief.getGridX(),
                    chief.getGridY(), t.getGridX(), t.getGridY()) <= 300;
            if (weak || about_to)
                goals.add(t);
        }
        siege_assign.entrySet().removeIf(e -> e.getKey().isDead() || !goals.contains(e.getValue()));
        if (!goals.isEmpty()) {
            if (siege_start < 0f)
                siege_start = now;
            siege_progress = now;
            if (ai.periodDue(last_pull_log, 250f)) {
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
        boolean pending = since < 250f;
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
        if (now - siege_progress > ai.strategy().siege_patience_ticks) {
            ai.log("siege gives up");
            siege_cooldown = now + 3000f;
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
            int[] tc = MapAnalysis.centroid(new ArrayList<>(helpless));
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
        // retreat_cap_ticks: measured from its first round
        retreat_measured = false;
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
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() != Role.ARMY)
                continue;
            Unit u = e.getKey();
            WarriorState s = ai.intel().warrior_states.get(u);
            if (s == WarriorState.STUNNED || s == WarriorState.ENTER)
                continue;
            group.add(u);
        }
        group = beyondGuard(group);
        float home = Combat.total(group);
        if (group.isEmpty())
            return;
        float away = attackStrength();
        boolean capped = nearUnitCap();
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
                if (attackers == null)
                    attackers = withRole(Role.ATTACK);
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
            int[] goal = intercept && march_wp != null && ai.now() - march_calm_time < 100f ? march_wp : front;
            attackGround(u, goal[0], goal[1], false);
        }
    }

    /** The army's current march waypoint, and when it was set (only while marching calmly). */
    private int @Nullable [] march_wp;
    private float march_calm_time = -5000f;
    /**
     * unjam: the column march lasts until then (-1: none). A jam window is the run of scans in a row that found the
     * army jammed, on one target field, and when and where its pivot stood at the first of them.
     */
    private float column_until = -1f;
    private int jam_scans;
    private float jam_since;
    private int jam_first_dist = DistanceField.UNREACHABLE;
    private @Nullable DistanceField jam_field;
    private int column_start_dist = DistanceField.UNREACHABLE;
    private @Nullable Selectable<?> column_target;

    /**
     * unjam: called by Jams after every scan with the warriors it found blocked. With at least unjam attack units
     * blocked on every scan for unjam_after_ticks since the first of them (from unjam_from_ticks of game time), no
     * enemy fighter near them, and the pivot less than unjam_progress m closer to the target, the attack marches as a
     * column until unjam_ticks after the last jammed scan (Military.attack). The progress test keeps it out of a
     * crowded march that still moves: in s13, s16 and s31 at N=11 a column march started so, strung the army out, and
     * it met the enemy piecemeal (s31: 39 of 147 units left at 1000 s, against 102 in the base game).
     */
    void noteBlocked(@NonNull List<@NonNull Unit> blocked) {
        Strategy strategy = ai.strategy();
        if (strategy.unjam <= 0)
            return;
        List<Unit> stuck = new ArrayList<>();
        if (mode == Mode.ATTACK && ai.now() >= strategy.unjam_from_ticks)
            for (Unit u : blocked)
                if (roles.get(u) == Role.ATTACK)
                    stuck.add(u);
        if (stuck.size() < strategy.unjam || target_field == null) {
            jam_scans = 0;
            return;
        }
        // Units crowding a melee are no choke jam (s48 at N=11): only a jam with no enemy warrior, chieftain or tower
        // near counts. Enemy peons do not: the army stuck in s98 cut down gatherers all the time.
        int[] jc = MapAnalysis.centroid(stuck);
        int r = ENGAGE_RADIUS + 8;
        boolean fight = enemyStrengthNear(jc[0], jc[1], r) > 0f;
        for (Building t : ai.intel().enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), jc[0], jc[1]) <= r * r)
                fight = true;
        if (fight) {
            jam_scans = 0;
            return;
        }
        int pivot = attackPivotDist(target_field);
        if (jam_scans == 0 || jam_field != target_field) {
            jam_scans = 0;
            jam_field = target_field;
            jam_since = ai.now();
            jam_first_dist = pivot;
        }
        jam_scans++;
        // Game ticks since the window's first scan; the scans run in rounds, each up to a world tick late (slack).
        if (ai.now() - jam_since < strategy.unjam_after_ticks - ai.slack())
            return;
        if (column_until < 0f) {
            if (pivot == DistanceField.UNREACHABLE || jam_first_dist == DistanceField.UNREACHABLE
                    || pivot <= jam_first_dist - strategy.unjam_progress) {
                // crowded but moving: start a new window from here
                ai.aiLog().count("unjam_moving");
                jam_scans = 1;
                jam_since = ai.now();
                jam_first_dist = pivot;
                return;
            }
            column_start_dist = pivot;
            column_target = target;
            ai.aiLog().count("unjam_column");
            if (ai.logging())
                ai.log(String.format(
                        "unjam: %d of %d attack units blocked around %d,%d, pivot %d m from the target at " + "%d,%d (%d m %.0f s ago): column march",
                        stuck.size(), countRole(Role.ATTACK), jc[0], jc[1],
                        pivot, target_x, target_y, jam_first_dist, GauntletAI.seconds(ai.now() - jam_since)));
        }
        column_until = ai.now() + strategy.unjam_ticks;
    }

    /** unjam: the field distance of the attack unit a third of the way back from the front, as the march ranks them. */
    private int attackPivotDist(@NonNull DistanceField field) {
        List<Integer> ds = new ArrayList<>();
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            Unit u = e.getKey();
            if (e.getValue() != Role.ATTACK || u.isDead())
                continue;
            int d = field.getAround(u.getGridX(), u.getGridY(), 1);
            if (d != DistanceField.UNREACHABLE)
                ds.add(d);
        }
        if (ds.isEmpty())
            return DistanceField.UNREACHABLE;
        ds.sort(null);
        return ds.get(ds.size() / 3);
    }

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
            hold_since = ai.now();
        }
        if (ai.now() - hold_since > 1000f)
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
        else if (ai.strategy().retreat_cap_ticks > 0f && ai.now() >= ai.strategy().unlock_from_ticks)
            retreatCap(home, n);
    }

    /**
     * retreat_cap_ticks: progress is a new attacker home (within 14 cells of the staging point), or those still out
     * 20 m nearer it on average, walking; the first round sets the baseline. After retreat_cap_ticks without, the
     * retreat ends with the rest still out (s6409 N=14: 89 attackers blocked behind a canyon held by an idle blob, in
     * RETREAT for 279 min with the home army idle, since only HOME weighs attacks).
     */
    private void retreatCap(int home, int n) {
        DistanceField f = stagingField();
        long sum = 0;
        int out = 0;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() != Role.ATTACK)
                continue;
            Unit u = e.getKey();
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), staging_x, staging_y) <= 14 * 14)
                continue;
            int d = f.getAround(u.getGridX(), u.getGridY(), 1);
            if (d != DistanceField.UNREACHABLE) {
                sum += d;
                out++;
            }
        }
        int dist = out > 0 ? (int) (sum / out) : DistanceField.UNREACHABLE;
        float now = ai.now();
        if (!retreat_measured) {
            retreat_measured = true;
            retreat_best_home = home;
            retreat_dist = dist;
            retreat_time = now;
            return;
        }
        boolean nearer = dist != DistanceField.UNREACHABLE && retreat_dist != DistanceField.UNREACHABLE
                && dist <= retreat_dist - 20;
        if (home > retreat_best_home || nearer) {
            retreat_best_home = Math.max(retreat_best_home, home);
            retreat_dist = dist;
            retreat_time = now;
            return;
        }
        if (retreat_dist == DistanceField.UNREACHABLE)
            retreat_dist = dist;
        if (now - retreat_time < ai.strategy().retreat_cap_ticks)
            return;
        ai.aiLog().count("retreat_capped");
        ai.log(String.format(
                "retreat capped: %d of %d attackers home, the rest %s m away on average, none nearer for " + "%.0f s",
                home, n, dist == DistanceField.UNREACHABLE ? "-" : String.valueOf(dist),
                GauntletAI.seconds(now - retreat_time)));
        // Those still out stay out of the next musters and launches until they come home: counted in, they made the
        // next attack march the home army off with them, its centre between the two groups (rep-fix-s6409: three
        // launches turned back or capped again within 190 s each, the same group 612 m away every time).
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            Unit u = e.getKey();
            if (e.getValue() == Role.ATTACK && !stranded.contains(u)
                    && MapAnalysis.dist2(u.getGridX(), u.getGridY(), staging_x, staging_y) > 14 * 14)
                stranded.add(u);
        }
        endAttack();
    }

    /**
     * retreat_cap_ticks: a unit a capped retreat left out that is still more than STRANDED_CELLS from the staging
     * point (stranded).
     */
    private boolean strandedOut(@NonNull Unit u) {
        return !stranded.isEmpty() && stranded.contains(u) && MapAnalysis.dist2(u.getGridX(), u.getGridY(), staging_x,
                staging_y) > STRANDED_CELLS * STRANDED_CELLS;
    }

    /** retreat_cap_ticks: the fighting value of the home army (armyStrength's roles) that is strandedOut. */
    private float strandedStrength() {
        float s = 0f;
        for (Unit u : stranded) {
            Role r = roles.get(u);
            if ((r == Role.ARMY || r == Role.RAID) && strandedOut(u))
                s += Combat.value(u);
        }
        return s;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Raids on enemy gatherers

    private void considerRaid() {
        Strategy strategy = ai.strategy();
        if (ai.now() < strategy.raid_ticks || ai.now() - last_raid_end < 2000f)
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
        raid_start = ai.now();
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
            float danger = withEnemyTowers(enemyStrengthNear(px, py, 30), px, py, 12);
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
    private float chase_start = -50000f;
    private float last_chase_end = -50000f;
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
        if (inDeadRegion(t) || stalled_targets.containsKey(t))
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
        int[] army_c = source == Role.ATTACK ? MapAnalysis.centroid(pool) : null;
        int[] from = army_c != null ? army_c : new int[]{staging_x, staging_y};
        countBases();
        chase_banned.entrySet().removeIf(e -> e.getKey().isDead() || ai.now() - e.getValue() > 6000f);
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
            last_order.put(u, ai.now());
            ai.owner().setTarget(Selectable.newArray(u), t, Action.ATTACK, true);
        }
        chase_target = t;
        chase_owner = t.getOwner();
        chase_start = ai.now();
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
        List<Unit> squad = withRole(Role.CHASE);
        if (squad.isEmpty())
            return;
        Strategy s = ai.strategy();
        Intel intel = ai.intel();
        Selectable<?> t = chase_target;
        int[] c = MapAnalysis.centroid(squad);
        if (t == null || t.isDead()) {
            if (t != null) {
                ai.aiLog().count(t instanceof Unit ? "hunt_kill" : "hunt_site_razed");
                ai.log("chase: " + (t instanceof Unit ? "chieftain" : "site") + " of " + t.getOwner().getPlayerInfo().getName() + " done");
            }
            Selectable<?> next = chase_owner == null ? null : nextBlocker(chase_owner, c, 60);
            if (next != null) {
                chase_target = next;
                chase_start = ai.now();
                ai.aiLog().count("hunt_chain");
                return;
            }
            endChase(squad);
            return;
        }
        float ours = Combat.total(squad);
        float danger = Combat.strengthNear(intel.enemy_warriors, c[0], c[1], 24);
        for (Unit ch : intel.enemy_chieftains)
            if (ch != t && !ch.isDead() && MapAnalysis.dist2(ch.getGridX(), ch.getGridY(), c[0], c[1]) <= 24 * 24)
                danger += Combat.value(ch);
        for (Building tw : intel.enemy_towers)
            if (!tw.isDead() && MapAnalysis.dist2(tw.getGridX(), tw.getGridY(), c[0], c[1]) <= 10 * 10)
                danger += enemyTowerValue(tw);
        String abort = danger > .8f * ours ? "hunt_abort_danger" : ai.now() - chase_start > s.chief_hunt_ticks ? "hunt_abort_time" : squad.size() < 3 ? "hunt_abort_small" : Combat.countNear(
                intel.enemy_warriors, t.getGridX(), t.getGridY(),
                15) > s.chief_hunt_escort + 2 ? "hunt_abort_escort" : null;
        if (abort == null) {
            countBases();
            if (!huntable(t.getOwner()))
                abort = "hunt_abort_changed";
        }
        if (abort != null) {
            ai.aiLog().count(abort);
            chase_banned.put(t, ai.now());
            endChase(squad);
            return;
        }
        for (Unit u : squad) {
            WarriorState st = intel.warrior_states.get(u);
            if (st == WarriorState.FIGHT || st == WarriorState.STUNNED || st == WarriorState.ENTER)
                continue;
            Float last = last_order.get(u);
            if (last != null && !ai.periodDue(last, 100f))
                continue;
            last_order.put(u, ai.now());
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
        last_chase_end = ai.now();
    }

    private void raid() {
        List<Unit> squad = withRole(Role.RAID);
        if (squad.isEmpty())
            return;
        int[] c = MapAnalysis.centroid(squad);
        float ours = Combat.total(squad);
        float danger = withEnemyTowers(enemyStrengthNear(c[0], c[1], 24), c[0], c[1], 10);
        boolean done = ai.now() - raid_start > 7500f;
        if (danger > .8f * ours || done || squad.size() < 2) {
            for (Unit u : squad) {
                roles.put(u, Role.ARMY);
                move(u, staging_x, staging_y);
            }
            last_raid_end = ai.now();
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
            last_raid_end = ai.now();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Orders

    /** The units with the given role, in the roles map's order. */
    private @NonNull List<@NonNull Unit> withRole(@NonNull Role role) {
        List<Unit> units = new ArrayList<>();
        for (Map.Entry<Unit, Role> e : roles.entrySet())
            if (e.getValue() == role)
                units.add(e.getKey());
        return units;
    }

    private int countRole(@NonNull Role role) {
        int n = 0;
        for (Role r : roles.values())
            if (r == role)
                n++;
        return n;
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
        float period = urgent ? 50f : same_spot ? 2 * REORDER_PERIOD_TICKS : REORDER_PERIOD_TICKS;
        if (last != null && !ai.periodDue(last, period) && s != WarriorState.IDLE)
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
            last_order.put(w, ai.now());
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
            last_order.put(w, ai.now());
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
    private static final int GARRISON_REACH2 = 252;

    /** The squared reach tower targets are chosen within: the garrison's full reach, or 15 cells. */
    private int towerReach2() {
        return ai.strategy().tower_full_reach
                || ai.strategy().tower_gunner_reach ? GARRISON_REACH2 : TOWER_CELLS * TOWER_CELLS;
    }

    /** A tower's garrison when it can throw now: the tower finished and standing, the garrison alive and awake. */
    private static @Nullable Unit readyGunner(@NonNull Building t) {
        if (t.isDead() || !t.isComplete() || t.getUnitContainer() == null || t.getUnitCount() == 0)
            return null;
        Unit gunner = Intel.gunner(t);
        return gunner == null || gunner.isDead() || Intel.isStunned(gunner) ? null : gunner;
    }

    /** The tower's current target while it lives and stands within r2 of the garrison's origin (ox, oy), else null. */
    private @Nullable Unit liveTarget(@NonNull Building t, int ox, int oy, int r2) {
        Unit current = tower_targets.get(t);
        return current != null && !current.isDead()
                && MapAnalysis.dist2(ox, oy, current.getGridX(), current.getGridY()) <= r2 ? current : null;
    }

    /**
     * Where a garrison throws from: the grid cell it entered by, on the ring two cells around the tower (Unit.mount
     * moves only the world position; range checks and scans use the grid cell), so the reach disc is shifted 2-2.8
     * cells towards the entry side. With tower_gunner_reach reach is measured from there, else from the centre
     * (originX, originY).
     */
    private int originX(@NonNull Building t, @NonNull Unit gunner) {
        return ai.strategy().tower_gunner_reach ? gunner.getGridX() : t.getGridX();
    }

    private int originY(@NonNull Building t, @NonNull Unit gunner) {
        return ai.strategy().tower_gunner_reach ? gunner.getGridY() : t.getGridY();
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
            inflight.entrySet().removeIf(e -> e.getKey().isDead() || ai.now() - e.getValue() > 125f);
        for (Building t : intel.towers) {
            Unit gunner = readyGunner(t);
            if (gunner == null)
                continue;
            int ox = originX(t, gunner);
            int oy = originY(t, gunner);
            if (strategy.tower_prequeue && prequeue(t, gunner, ox, oy, r2))
                continue;
            if (!strategy.tower_reflex || liveTarget(t, ox, oy, r2) != null)
                continue;
            Unit best = bestTowerTarget(t, gunner, ox, oy, r2, null);
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
    private boolean prequeue(@NonNull Building t, @NonNull Unit gunner, int ox, int oy, int r2) {
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
            inflight.put(x, ai.now());
        int queued = tower_queued.getOrDefault(t, 0);
        if (queued >= 20)
            return false; // each queued order stays on the garrison's controller stack until the fight ends
        Unit next = bestTowerTarget(t, gunner, ox, oy, r2, x);
        if (next == null)
            return false;
        tower_queued.put(t, queued + 1);
        tower_targets.put(t, next);
        ai.owner().setTarget(Selectable.newArray(t), next, Action.ATTACK, false);
        ai.aiLog().count("tower_prequeue");
        return true;
    }

    /** The best enemy in reach for this tower, skipping one and any already doomed by an axe in flight. */
    private @Nullable Unit bestTowerTarget(@NonNull Building t, @NonNull Unit gunner, int ox, int oy, int r2,
            @Nullable Unit skip) {
        Unit best = null;
        float best_score = 0f;
        EnemyIndex index = enemyIndex();
        int[] near = index.query(ox, oy, r2);
        for (int k = 0, n = index.count(); k < n; k++) {
            Unit e = index.unit(near[k]);
            if (e == skip || e.isDead() || inflight.containsKey(e))
                continue;
            int others = 0;
            for (Unit other : tower_targets.values())
                if (other == e)
                    others++;
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
            Unit gunner = Intel.gunner(t);
            if (gunner == null || gunner.isDead() || Intel.isStunned(gunner))
                continue;
            int ox = originX(t, gunner);
            int oy = originY(t, gunner);
            Unit current = tower_targets.get(t);
            if (current != null && MapAnalysis.dist2(ox, oy, current.getGridX(), current.getGridY()) <= r2) {
                survive.merge(current, 1f - towerHitChance(gunner, t, current), (a, b) -> a * b);
                continue;
            }
            Unit best = null;
            float best_score = 0f;
            float best_p = 0f;
            EnemyIndex index = enemyIndex();
            int[] near = index.query(ox, oy, r2);
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
        int r2 = towerReach2();
        int ox = originX(t, gunner);
        int oy = originY(t, gunner);
        Unit current = liveTarget(t, ox, oy, r2);
        if (current != null)
            return current;
        Unit best = null;
        float best_score = 0f;
        EnemyIndex index = enemyIndex();
        int[] near = index.query(ox, oy, r2);
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
        float p = 3f * throwSkill(gunner, tower.getGridX(), tower.getGridY(), dst) * (1f - dst.getDefenseChance());
        return Math.clamp(p, 0f, 1f);
    }

    /** Chance that a throw from src hits dst, as the game rolls it. */
    private float hitChance(@NonNull Unit src, @NonNull Unit dst) {
        float p = throwSkill(src, src.getGridX(), src.getGridY(), dst) * (1f - dst.getDefenseChance());
        return Math.clamp(p, 0f, 1f);
    }

    /**
     * The hit chance of a throw by src from the cell (x, y) at dst before dst's defense: its owner's bonus, plus a
     * 1/80 per meter the thrower stands higher (at most a quarter either way), plus its weapon's base chance.
     */
    private float throwSkill(@NonNull Unit src, int x, int y, @NonNull Unit dst) {
        MapAnalysis map = ai.map();
        float dz = map.height(x, y) - map.height(dst.getGridX(), dst.getGridY());
        float terrain = Math.clamp(dz / 80f, -.25f, .25f);
        return src.getOwner().getHitBonus() + terrain + baseHitChance(src);
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
            if (last != null && !ai.periodDue(last, ai.strategy().restore_dodge_gap_ticks))
                continue;
            dodge_orders.put(w, ai.now());
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
        last_order.put(u, ai.now());
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

    /** s plus the value of each enemy tower within r cells of (x, y) (enemyTowerValue), added in list order. */
    private float withEnemyTowers(float s, int x, int y, int r) {
        for (Building t : ai.intel().enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= r * r)
                s += enemyTowerValue(t);
        return s;
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
        List<Unit> army = withRole(Role.ATTACK);
        return army.isEmpty() ? null : MapAnalysis.centroid(army);
    }

    @NonNull
    String debugStatus() {
        return "mode=" + mode + " army=" + countRole(Role.ARMY) + " atk=" + countRole(
                Role.ATTACK) + " raid=" + countRole(Role.RAID) + " twr=" + countRole(Role.TOWER) + String.format(
                        " str=%.1f thr=%d/%.1f",
                        armyStrength(), threat_level, threat_strength) + parkedStatus();
    }

    /**
     * Log only (Jams, for a big warrior jam): the cells around (cx, cy), one log line per row. # terrain nobody can
     * walk, B a building, T a tree or other static occupant, . open ground (, where the target field does not reach),
     * attack units b blocked walking / w walking / i idle / f anything else, o our other units, e enemy units, W the
     * march waypoint. The head line compares the target field with one computed now (a stale field would differ).
     */
    void describeJam(int cx, int cy) {
        if (!ai.logging())
            return;
        MapAnalysis map = ai.map();
        com.oddlabs.tt.pathfinder.UnitGrid grid = map.getGrid();
        int rx = 32;
        int ry = 22;
        Map<String, Integer> walk_targets = new LinkedHashMap<>();
        int[] kinds = new int[6];
        StringBuilder head = new StringBuilder(
                "jampic head x0=" + (cx - rx) + " y0=" + (cy - ry) + " mode=" + mode + " target=" + target_x + "," + target_y + " wp=" + (march_wp != null ? march_wp[0] + "," + march_wp[1] : "-"));
        DistanceField f = target_field;
        if (f != null) {
            DistanceField fresh = map.computeField(target_x, target_y, Integer.MAX_VALUE, target_corner);
            head.append(" field@c=").append(f.getAround(cx, cy, 1)).append(" fresh@c=").append(fresh.getAround(cx, cy,
                    1));
            if (march_wp != null)
                head.append(" field@wp=").append(f.getAround(march_wp[0], march_wp[1], 1)).append(" fresh@wp=").append(
                        fresh.getAround(march_wp[0], march_wp[1], 1));
        }
        ai.log(head.toString());
        for (int y = cy - ry; y <= cy + ry; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = cx - rx; x <= cx + rx; x++) {
                char ch;
                if (march_wp != null && x == march_wp[0] && y == march_wp[1])
                    ch = 'W';
                else if (!map.walkable(x, y))
                    ch = '#';
                else {
                    com.oddlabs.tt.pathfinder.Occupant occ = grid.getOccupant(x, y);
                    if (occ == null)
                        ch = f != null && !f.reachable(x, y) ? ',' : '.';
                    else if (occ instanceof Unit u && !u.isDead()) {
                        if (u.getOwner() == ai.owner()) {
                            Role role = roles.get(u);
                            com.oddlabs.tt.model.behaviour.Behaviour b = u.getCurrentBehaviour();
                            if (role == Role.ATTACK) {
                                if (b instanceof com.oddlabs.tt.model.behaviour.WalkBehaviour) {
                                    ch = b.isBlocking() ? 'b' : 'w';
                                    kinds[b.isBlocking() ? 0 : 1]++;
                                } else if (b instanceof com.oddlabs.tt.model.behaviour.IdleBehaviour) {
                                    ch = 'i';
                                    kinds[2]++;
                                } else {
                                    ch = 'f';
                                    kinds[3]++;
                                }
                                if (u.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.WalkController wc) {
                                    String k = wc.getTarget().getGridX() + "," + wc.getTarget().getGridY();
                                    walk_targets.merge(k, 1, Integer::sum);
                                }
                            } else {
                                ch = 'o';
                                kinds[4]++;
                            }
                        } else {
                            ch = 'e';
                            kinds[5]++;
                        }
                    } else if (occ instanceof Building)
                        ch = 'B';
                    else if (occ.getPenalty() >= com.oddlabs.tt.pathfinder.Occupant.STATIC)
                        ch = 'T';
                    else
                        ch = '?';
                }
                row.append(ch);
            }
            ai.log(String.format("jampic %4d %s", y, row));
        }
        ai.log("jampic kinds blocked=" + kinds[0] + " walking=" + kinds[1] + " idle=" + kinds[2] + " other=" + kinds[3] + " own_other=" + kinds[4] + " enemy=" + kinds[5] + " walk_targets=" + walk_targets);
    }

    /** The parked ring's value: its idle enemy warriors (inRing). */
    private float ringStrength() {
        float s = 0f;
        for (Unit e : ai.intel().enemy_warriors)
            if (inRing(e))
                s += Combat.value(e);
        return s;
    }

    /**
     * An enemy warrior of the parked ring: alive, idle (Intel.isParked), within 45 cells of our armories, quarters or
     * towers.
     */
    private boolean inRing(@NonNull Unit e) {
        return !e.isDead() && Intel.isParked(e) && nearOwnBuilding(e.getGridX(), e.getGridY(), 45);
    }

    private boolean nearOwnBuilding(int x, int y, int cells) {
        Intel intel = ai.intel();
        int r2 = cells * cells;
        for (List<Building> group : List.of(intel.armories, intel.quarters, intel.towers))
            for (Building b : group)
                if (!b.isDead() && MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y) <= r2)
                    return true;
        return false;
    }

    /** ring_sweep: calls a stalled attack home in the window while the army outnumbers the parked ring. */
    private void considerSweep() {
        Strategy strategy = ai.strategy();
        float now = ai.now();
        if (mode != Mode.ATTACK || sweep_until >= 0f || now < strategy.ring_sweep_from_ticks
                || now > strategy.ring_sweep_until_ticks || now - last_out_time < strategy.ring_sweep_quiet_ticks)
            return;
        float ring = ringStrength();
        float away = attackStrength();
        if (ring < 3f || away < strategy.ring_sweep_ratio * ring)
            return;
        int[] c = attackCenter();
        Building armory = ai.intel().armory();
        if (c == null || armory == null || MapAnalysis.dist2(c[0], c[1], armory.getGridX(),
                armory.getGridY()) > strategy.ring_sweep_reach * strategy.ring_sweep_reach)
            return;
        if (target != null && target.getOwner() != null) {
            int standing = 0;
            for (Building b : ai.intel().enemy_buildings)
                if (!b.isDead() && b.isComplete() && b.getOwner() == target.getOwner())
                    standing++;
            if (standing < 2)
                return;
        }
        ai.aiLog().count("ring_sweep");
        ai.log(String.format("ring sweep: attack %.1f comes home against a parked ring of %.1f", away, ring));
        endAttack();
        sweep_until = strategy.ring_sweep_until_ticks + 3000f;
        sweep_ring_start = ring;
    }

    /**
     * ring_sweep, in HOME mode: while the base is quiet, the home army attacks the parked enemy warrior nearest the
     * armory; ends when the ring is down to 30 % of what it was, the army is small or outmatched, or the time is up.
     */
    private void sweep() {
        float now = ai.now();
        float ring = ringStrength();
        float army = armyStrength();
        if (now > sweep_until || army < Math.max(12f, .8f * ring) || ring < Math.max(3f, .3f * sweep_ring_start)) {
            sweep_until = -1f;
            ai.aiLog().count("ring_sweep_end");
            ai.log(String.format("ring sweep over: ring %.1f, army %.1f", ring, army));
            holdStaging();
            return;
        }
        if (threat_level >= 2)
            return; // defend() has the army
        Building armory = ai.intel().armory();
        int ax = armory != null ? armory.getGridX() : staging_x;
        int ay = armory != null ? armory.getGridY() : staging_y;
        Unit prey = null;
        int best = Integer.MAX_VALUE;
        for (Unit e : ai.intel().enemy_warriors) {
            if (!inRing(e))
                continue;
            int d = MapAnalysis.dist2(ax, ay, e.getGridX(), e.getGridY());
            if (d < best) {
                best = d;
                prey = e;
            }
        }
        if (prey == null) {
            holdStaging();
            return;
        }
        int ordered = 0;
        for (Map.Entry<Unit, Role> e : roles.entrySet()) {
            if (e.getValue() != Role.ARMY)
                continue;
            attackGround(e.getKey(), prey.getGridX(), prey.getGridY(), false);
            ordered++;
        }
        if (ordered > 0)
            ai.aiLog().count("ring_sweep_order");
    }

    /**
     * Idle enemy warriors near our base (within 45 cells of a building of ours): out of reach of our manned towers /
     * in reach / how many of those out of reach a tower 11+ cells from every enemy could reach (15 cells).
     */
    private @NonNull String parkedStatus() {
        Intel intel = ai.intel();
        List<Building> own = intel.finishedBuildings();
        int out = 0;
        int in = 0;
        int[] by_building = new int[4];
        int[] by_tower = new int[4];
        for (Unit e : intel.enemy_warriors) {
            if (!inRing(e))
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
