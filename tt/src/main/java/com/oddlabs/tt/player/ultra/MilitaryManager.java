package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackBehaviour;
import com.oddlabs.tt.model.behaviour.HuntController;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Army control: threat detection and base defense, peon evacuation, the home gathering point, attack decisions,
 * marching in cohesive legs along a walking-distance field, engagement micro (spread fire, no overkill),
 * sieging buildings, retreat and reinforcement.
 */
final class MilitaryManager {
    enum State {
        GATHER,
        MARCH,
        ENGAGE,
        SIEGE,
        RETREAT
    }

    private static final int HOME = 0;
    private static final int MAIN = 1;
    private static final int REINF = 2;
    private static final int GARRISON = 3;
    private static final int RAID = 4;
    private static final float UNIT_RANGE = 7.9f;
    private static final float TOWER_TARGET_RANGE = 8.93f;

    private final @NonNull Context ctx;
    private final Map<Unit, Integer> squad_of = new HashMap<>();
    private final List<Unit> home = new ArrayList<>();
    private final List<Unit> main = new ArrayList<>();
    private final List<Unit> reinf = new ArrayList<>();
    private final List<Unit> raid = new ArrayList<>();
    private State state = State.GATHER;
    private float state_since;
    private int[] gather;
    private float gather_time = Float.NEGATIVE_INFINITY;

    private @Nullable Building objective;
    private int obj_x;
    private int obj_y;
    private int @Nullable [] route;
    private int[] waypoint;
    private float leg_start;
    private float last_enemy_near;

    // Threat.
    private final List<Selectable<?>> intruders = new ArrayList<>();
    private float intruder_e;
    private int intr_x;
    private int intr_y;
    private boolean defending;
    private float last_defend_order;

    private final int attack_min;
    private final float commit_ratio;
    private final float grind_ratio;
    private final float retreat_ratio;
    private final int defend_radius;
    private final int reinforce_batch;
    private final int engage_range;
    private final float cohesion_frac;
    private final int leg_length;
    private final float cap_attack_frac;
    private final boolean micro;
    private final boolean raid_enabled;
    private final boolean recall;
    private final boolean peon_strike;
    private final int raid_size;

    MilitaryManager(@NonNull Context ctx) {
        this.ctx = ctx;
        this.chief_target_value = ctx.strategy.f("chiefTargetValue");
        Strategy s = ctx.strategy;
        attack_min = s.i("attackMin");
        commit_ratio = s.f("commitRatio");
        weapon_buffer = s.i("weaponBuffer");
        enemy_latent_guess = s.f("enemyLatentGuess");
        attack_learn = s.b("attackLearn");
        stun_tower_focus = s.b("stunTowerFocus");
        tower_linear = s.f("towerLinear");
        cover_weight = s.f("coverWeight");
        stun_siege = s.b("stunSiege");
        learn_decay = s.f("learnDecay");
        siege_evac = s.b("siegeEvac");
        siege_hp = s.f("siegeHp");
        march_watch = s.b("marchWatch");
        dodge = s.b("dodge");
        assault_gate = s.b("assaultGate");
        frozen_window = s.b("frozenWindow");
        toot_grace = s.f("tootGrace");
        frozen_min = s.i("frozenMin");
        frozen_reengage = s.f("frozenReengage");
        assault_ratio = s.f("assaultRatio");
        tower_lin_w = s.f("towerLinW");
        assault_r = s.i("assaultR");
        assault_chief = s.f("assaultChief");
        assault_memory = s.f("assaultMemory");
        approach_towers = s.b("approachTowers");
        dodge_min = s.f("dodgeMin");
        dodge_max = s.f("dodgeMax");
        dodge_to = s.f("dodgeTo");
        dodge_hold = s.f("dodgeHold");
        dodge_peons = s.b("dodgePeons");
        dodge_smart = s.b("dodgeSmart");
        enemy_chief_factor = s.f("enemyChiefFactor");
        grind_ratio = s.f("grindRatio");
        retreat_ratio = s.f("retreatRatio");
        defend_radius = s.i("defendRadius");
        reinforce_batch = s.i("reinforceBatch");
        engage_range = s.i("engageRange");
        cohesion_frac = s.f("cohesionFrac");
        leg_length = s.i("legLength");
        cap_attack_frac = s.f("capAttackFrac");
        micro = s.b("micro");
        raid_enabled = s.b("raid");
        recall = s.b("recall");
        peon_strike = s.b("peonStrike");
        raid_size = s.i("raidSize");
        gather = new int[]{ctx.map.home_x, ctx.map.home_y};
        waypoint = gather;
    }

    @NonNull
    State state() {
        return state;
    }

    int @Nullable [] rallyPoint() {
        return gather;
    }

    /**
     * Iron weapons the armory keeps in stock instead of deploying: the peon who would carry each one keeps working
     * while the army only waits at home. Released when defending, mustering, attacking, or near the unit cap.
     */
    int latentReserve() {
        if (weapon_buffer <= 0 || defending || state != State.GATHER || ctx.now < muster_until)
            return 0;
        if (ctx.owner.getUnitCountContainer().getNumSupplies() >= ctx.world.getMaxUnitCount() - 10)
            return 0;
        return weapon_buffer;
    }

    private final int weapon_buffer;
    /** Assumed deployable strength inside each enemy armory (its contents are hidden). */
    private final float enemy_latent_guess;

    // Learning from our own attacks: an attack that trades badly means this enemy defends better than our estimate
    // (towers, reserves, stuns), so later attack decisions scale its strength up; cheap wins scale it back down.
    private final boolean attack_learn;
    private final boolean stun_tower_focus;
    private final float tower_linear;
    /** How strongly target choice avoids buildings covered by enemy towers. */
    private final float cover_weight;
    private final boolean stun_siege;
    /** Half-life (s) of the learned enemy defence multiplier while waiting at the unit cap; 0 = never decays. */
    private final float learn_decay;
    /**
     * Keep peons out of, and empty, a besieged armory (siegeHp: HP fraction that counts as besieged with enemies at
     * it).
     */
    private boolean siege_evac;
    private final float siege_hp;
    /** Route-aware march legs and a no-progress watchdog (an army looping at a narrow passage). */
    private boolean march_watch;
    private final boolean dodge;
    private final boolean assault_gate;
    private final boolean frozen_window;
    private final float toot_grace;
    private final int frozen_min;
    private final float frozen_reengage;
    private final float assault_ratio;
    private final float tower_lin_w;
    private final int assault_r;
    private final float assault_chief;
    private final float assault_memory;
    private final boolean approach_towers;
    private final float dodge_min;
    private final float dodge_max;
    private final float dodge_to;
    private final float dodge_hold;
    private final boolean dodge_peons;
    /** Skip stuns our own lands first on, stop dodging once victims are picked or the stun resolved. */
    private final boolean dodge_smart;
    private float defense_mult = 1f;
    private float last_decay;
    private float last_gather_trace = Float.NEGATIVE_INFINITY;
    private boolean attack_active;
    private int attack_lost0;
    private int attack_killed0;

    private void startAttack() {
        attack_active = true;
        attack_lost0 = ctx.owner.getUnitsLost();
        attack_killed0 = ctx.owner.getUnitsKilled();
    }

    private void endAttack() {
        if (!attack_active)
            return;
        attack_active = false;
        if (!attack_learn)
            return;
        int lost = ctx.owner.getUnitsLost() - attack_lost0;
        int killed = ctx.owner.getUnitsKilled() - attack_killed0;
        float old = defense_mult;
        if (lost >= 8 && lost > 1.25f * killed)
            defense_mult = Math.min(3f, defense_mult * (1f + Math.min(0.8f, (lost - killed) / (float) lost)));
        else if (killed >= lost)
            defense_mult = Math.max(1f, defense_mult * 0.88f);
        float now = defense_mult;
        ctx.log(() -> String.format("attack over: lost %d killed %d, enemy defense x%.2f -> x%.2f", lost, killed, old,
                now));
    }

    private final float enemy_chief_factor;
    /** Stock released for an attack: the army waits at home until it has walked out. */
    private float muster_until = Float.NEGATIVE_INFINITY;

    /** Iron weapons in stock that workers could carry out now. */
    private int stockedIron() {
        Building a = ctx.economy.armory();
        if (a == null || weapon_buffer <= 0)
            return 0;
        return Math.min(a.getSupplyContainer(com.oddlabs.tt.model.weapon.IronAxeWeapon.class).getNumSupplies(),
                a.getUnitContainer().getNumSupplies());
    }

    boolean isDefending() {
        return defending;
    }

    /**
     * While defending: {defenders' centre x, y, threat centre x, y} of the strongest threat that has defenders,
     * else null.
     */
    int @Nullable [] defenseFront() {
        if (!defending)
            return null;
        Threat best = null;
        for (Threat t : threats) {
            if (!t.defenders.isEmpty() && (best == null || t.e > best.e))
                best = t;
        }
        if (best == null)
            return null;
        long sx = 0;
        long sy = 0;
        for (Unit w : best.defenders) {
            sx += w.getGridX();
            sy += w.getGridY();
        }
        int n = best.defenders.size();
        return new int[]{(int) (sx / n), (int) (sy / n), best.cx, best.cy};
    }

    /** The main squad's anchor cell while it is away from home, else null. */
    int @Nullable [] squadAnchor() {
        if (main.isEmpty() || state == State.GATHER)
            return null;
        Unit u = anchor(main);
        return new int[]{u.getGridX(), u.getGridY()};
    }

    private float toot_until = Float.NEGATIVE_INFINITY;
    /** March progress watchdog (marchWatch): best route value the anchor reached, and when. */
    private int best_prog = Integer.MAX_VALUE;
    private float best_prog_t;
    private int leg_anchor_v = Integer.MAX_VALUE;
    private final Map<Building, Float> stalled = new java.util.LinkedHashMap<>();
    /** Visibly frozen enemies near the army: fight toward them (the retreat checks stay on). */
    private float frozen_until = Float.NEGATIVE_INFINITY;
    /** The army was recalled to defend the core; the frozen window does not turn it around meanwhile. */
    private float recall_until = Float.NEGATIVE_INFINITY;
    /** ENGAGE was entered from GATHER/RETREAT because of our toot: go back home when it is over. */
    private boolean toot_return;
    private boolean closeout;
    private int toot_x;
    private int toot_y;

    /** Our chieftain just cast the stun here: every warrior in reach charges during the 10-30 s window. */
    void onToot(int x, int y) {
        toot_order_time = ctx.now;
        toot_until = ctx.now + (frozen_window ? toot_grace : 12f);
        toot_x = x;
        toot_y = y;
        if (!main.isEmpty() && (state == State.GATHER || state == State.RETREAT || state == State.MARCH)) {
            Unit a = anchor(main);
            if (MapAnalysis.chebyshev(a.getGridX(), a.getGridY(), x, y) <= 30) {
                last_enemy_near = ctx.now;
                if (state != State.MARCH)
                    toot_return = true;
                setState(State.ENGAGE);
            }
        }
    }

    /** Where the main army fights toward: our toot, the objective, the closeout target, or home. */
    private int @NonNull [] fightPoint() {
        if (tootActive() || ctx.now < frozen_until)
            return new int[]{toot_x, toot_y};
        if ((objective != null && !objective.isDead()) || closeout)
            return new int[]{obj_x, obj_y};
        return gather;
    }

    private boolean tootActive() {
        return ctx.now < toot_until;
    }

    /** The building the main army is attacking, or null. */
    @Nullable
    Building objectiveBuilding() {
        if (state == State.GATHER || state == State.RETREAT || closeout)
            return null;
        return objective != null && !objective.isDead() ? objective : null;
    }

    /** The main army is out and clearly winning where it stands. */
    boolean holdingGround() {
        return main.size() >= 8 && (state == State.SIEGE || state == State.ENGAGE) && localRatio(main) >= 1.3f;
    }

    /** The attack objective while the main army is out, else null. */
    int @Nullable [] objectivePoint() {
        if (state == State.GATHER || state == State.RETREAT || (objective == null && !closeout))
            return null;
        return new int[]{obj_x, obj_y};
    }

    int armySize() {
        return home.size() + main.size() + reinf.size() + raid.size();
    }

    /** An enemy warrior (or chieftain) is close enough to kill a peon standing here. */
    boolean inDanger(int x, int y) {
        return ctx.resources.threatened(x, y, 10);
    }

    boolean quartersThreatened(@NonNull Building q) {
        return q.getHitPoints() < q.getTemplate().getMaxHitPoints() * 0.6f
                && ctx.resources.threatened(q.getGridX(), q.getGridY(), 12);
    }

    // -----------------------------------------------------------------------------------------------------
    // Squads

    private void rebuildSquads() {
        home.clear();
        main.clear();
        reinf.clear();
        raid.clear();
        for (Unit w : ctx.model.me.warriors) {
            Integer s = squad_of.get(w);
            if (s == null) {
                s = HOME;
                squad_of.put(w, s);
            }
            switch (s) {
                case MAIN -> main.add(w);
                case REINF -> reinf.add(w);
                case RAID -> raid.add(w);
                case GARRISON -> {
                    if (!ctx.towers.isPendingGarrison(w)) {
                        squad_of.put(w, HOME);
                        home.add(w);
                    }
                }
                default -> home.add(w);
            }
        }
    }

    void prune() {
        squad_of.keySet().removeIf(u -> u.isDead() || u.isMounted());
        stalled.entrySet().removeIf(e -> e.getKey().isDead() || ctx.now >= e.getValue());
    }

    /** Re-reads the parameters that have multi-enemy defaults (see Strategy.setEnemyCount). */
    void readMultiParams() {
        siege_evac = ctx.strategy.b("siegeEvac");
        march_watch = ctx.strategy.b("marchWatch");
    }

    private boolean enemyBuildingsLeft() {
        for (Building b : ctx.model.enemy.buildings) {
            if (!b.isDead())
                return true;
        }
        return false;
    }

    /** Claims the best available home warrior to garrison a tower (rubber, then iron, then rock). */
    @Nullable
    Unit takeForGarrison(int x, int y) {
        Unit near = takeForGarrison(x, y, 60);
        return near != null ? near : takeForGarrison(x, y, Integer.MAX_VALUE);
    }

    private @Nullable Unit takeForGarrison(int x, int y, int max_dist) {
        Unit best = null;
        float best_score = Float.NEGATIVE_INFINITY;
        List<Unit> pool = concat(concat(home, main), reinf);
        for (Unit w : pool) {
            // Prefer warriors close by; only a tower with nobody near gets one from farther away.
            if (MapAnalysis.chebyshev(w.getGridX(), w.getGridY(), x, y) > max_dist)
                continue;
            WorldModel.Kind k = WorldModel.kindOf(w);
            float score = switch (k) {
                case RUBBER -> 300f;
                case IRON -> 200f;
                default -> 100f;
            };
            score -= (float) Math.sqrt(MapAnalysis.dist2(w.getGridX(), w.getGridY(), x, y));
            if (score > best_score) {
                best_score = score;
                best = w;
            }
        }
        if (best != null) {
            squad_of.put(best, GARRISON);
            home.remove(best);
            main.remove(best);
            reinf.remove(best);
        }
        return best;
    }

    void releaseFromGarrison(@NonNull Unit w) {
        if (!w.isDead() && !w.isMounted())
            squad_of.put(w, HOME);
    }

    private static @NonNull List<Unit> concat(@NonNull List<Unit> a, @NonNull List<Unit> b) {
        List<Unit> r = new ArrayList<>(a.size() + b.size());
        r.addAll(a);
        r.addAll(b);
        return r;
    }

    // -----------------------------------------------------------------------------------------------------
    // Threat and evacuation (every 0.5 s)

    /** A group of intruding enemies and the defenders sent against it. */
    private static final class Threat {
        final List<Unit> units = new ArrayList<>();
        final List<Building> sites = new ArrayList<>();
        final List<Unit> defenders = new ArrayList<>();
        float e;
        int cx;
        int cy;
    }

    private final List<Threat> threats = new ArrayList<>();

    void threatTick() {
        rebuildSquads();
        updateGather();
        intruders.clear();
        intruder_e = 0f;
        List<Building> mine = ctx.model.me.buildings;
        Building a = ctx.economy.armory();
        List<Unit> hostile = new ArrayList<>();
        for (Unit e : ctx.model.enemy.units) {
            WorldModel.Kind k = WorldModel.kindOf(e);
            if (k == WorldModel.Kind.PEON && WorldModel.visibleActivity(e) == WorldModel.Activity.GATHER)
                continue;
            if (nearOurAssets(e.getGridX(), e.getGridY(), mine) || (k != WorldModel.Kind.PEON && nearOurPeons(e)))
                hostile.add(e);
        }
        threats.clear();
        // Single-link clusters (10 cells) so raids in different places get separate responses.
        boolean[] used = new boolean[hostile.size()];
        for (int i = 0; i < hostile.size(); i++) {
            if (used[i])
                continue;
            Threat t = new Threat();
            List<Unit> queue = t.units;
            queue.add(hostile.get(i));
            used[i] = true;
            for (int q = 0; q < queue.size(); q++) {
                Unit u = queue.get(q);
                for (int j = 0; j < hostile.size(); j++) {
                    if (!used[j] && MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), hostile.get(j).getGridX(),
                            hostile.get(j).getGridY()) <= 10) {
                        used[j] = true;
                        queue.add(hostile.get(j));
                    }
                }
            }
            long sx = 0;
            long sy = 0;
            for (Unit u : t.units) {
                t.e += CombatModel.weight(u);
                sx += u.getGridX();
                sy += u.getGridY();
            }
            t.cx = (int) (sx / t.units.size());
            t.cy = (int) (sy / t.units.size());
            threats.add(t);
        }
        for (Building b : ctx.model.enemy.buildings) {
            if (b.isDead() || !nearOurAssets(b.getGridX(), b.getGridY(), mine))
                continue;
            Threat nearest = null;
            for (Threat t : threats) {
                if (MapAnalysis.chebyshev(t.cx, t.cy, b.getGridX(), b.getGridY()) <= 12)
                    nearest = t;
            }
            if (nearest == null) {
                nearest = new Threat();
                nearest.cx = b.getGridX();
                nearest.cy = b.getGridY();
                threats.add(nearest);
            }
            nearest.sites.add(b);
            nearest.e += b.isComplete() ? Math.max(0.5f, CombatModel.towerValue(b) * 0.6f) : 0.5f;
        }
        for (Threat t : threats) {
            intruders.addAll(t.units);
            intruders.addAll(t.sites);
            intruder_e += t.e;
        }
        if (!threats.isEmpty()) {
            Threat worst = threats.get(0);
            for (Threat t : threats) {
                if (t.e > worst.e)
                    worst = t;
            }
            intr_x = worst.cx;
            intr_y = worst.cy;
        }
        assignDefenders();
        peonStrike();
        boolean was = defending;
        defending = !threats.isEmpty();
        if (defending != was) {
            int count = intruders.size();
            float e = intruder_e;
            int groups = threats.size();
            ctx.log(() -> "defending=" + defending + " intruders=" + count + " groups=" + groups + " E=" + e);
        }
        ctx.economy.emergency = false;
        if (a != null) {
            for (Threat t : threats) {
                if (t.e >= 3f && MapAnalysis.chebyshev(t.cx, t.cy, a.getGridX(), a.getGridY()) < 40)
                    ctx.economy.emergency = true;
            }
        }
        evacuate();
    }

    /** Peons sent to demolish an enemy tower near our base, by tower. */
    private final Map<Unit, Building> strikers = new java.util.LinkedHashMap<>();

    /**
     * Enemy towers (foundations or finished) near our buildings are demolished by peons: they deal a fixed 6
     * damage per swing to towers, and a foundation only has as many HP as its build progress. A finished manned
     * tower is only rushed while it is stunned or our warriors are drawing its fire.
     */
    private void peonStrike() {
        strikers.keySet().removeIf(u -> {
            Building t = strikers.get(u);
            boolean done = u.isDead() || t == null || t.isDead() || ctx.roleOf(u) != Context.Role.CORPS;
            if (done && !u.isDead() && ctx.roleOf(u) == Context.Role.CORPS) {
                ctx.clearRole(u);
                Building a = ctx.economy.armory();
                if (a != null)
                    ctx.orders.enter(u, a);
            }
            return done;
        });
        if (!peon_strike)
            return;
        List<Building> mine = ctx.model.me.buildings;
        List<Building> towers = new ArrayList<>(ctx.model.enemy.towers);
        for (Building b : ctx.model.enemy.sites) {
            if (b.getTemplate().getTemplateID() == Race.BUILDING_TOWER)
                towers.add(b);
        }
        for (Building t : towers) {
            if (t.isDead() || !nearOurAssets(t.getGridX(), t.getGridY(), mine))
                continue;
            int want;
            Unit g = WorldModel.garrisonOf(t);
            if (!t.isComplete()) {
                want = Math.max(3, Math.min(6, (t.getHitPoints() + 5) / 6 + 2));
            } else if (g == null || WorldModel.isStunned(g) || warriorsNear(t, 12) >= 3) {
                want = 8;
            } else {
                continue;
            }
            int have = 0;
            for (Building target : strikers.values()) {
                if (target == t)
                    have++;
            }
            for (int k = have; k < want; k++) {
                Unit best = null;
                int best_d = 35 * 35;
                for (Unit u : ctx.model.me.peons) {
                    if (u.isDead() || (ctx.hasRole(u) && ctx.roleOf(u) != Context.Role.HUNTER))
                        continue;
                    if (strikers.containsKey(u))
                        continue;
                    int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), t.getGridX(), t.getGridY());
                    if (d < best_d) {
                        best_d = d;
                        best = u;
                    }
                }
                if (best == null)
                    break;
                ctx.setRole(best, Context.Role.CORPS);
                strikers.put(best, t);
                ctx.orders.attack(best, t);
            }
            for (Map.Entry<Unit, Building> e : strikers.entrySet()) {
                Unit u = e.getKey();
                if (e.getValue() == t && !(u.getCurrentController() instanceof HuntController)
                        && !ctx.orders.recently(u, 2f))
                    ctx.orders.attack(u, t);
            }
        }
    }

    private int warriorsNear(@NonNull Building t, int r) {
        int n = 0;
        for (Unit w : ctx.model.me.warriors) {
            if (MapAnalysis.chebyshev(w.getGridX(), w.getGridY(), t.getGridX(), t.getGridY()) <= r)
                n++;
        }
        return n;
    }

    /** Enemy warriors hunting near our peons inside our half of the map. */
    private boolean nearOurPeons(@NonNull Unit e) {
        if (ctx.map.frontness(e.getGridX(), e.getGridY()) > 0.55f)
            return false;
        for (Unit p : ctx.model.me.peons) {
            if (MapAnalysis.chebyshev(p.getGridX(), p.getGridY(), e.getGridX(), e.getGridY()) <= 14)
                return true;
        }
        return false;
    }

    /**
     * Sends the nearest available warriors to each threat until they outweigh it 1.5:1 (the main army only
     * while it is at home). Threats are served strongest first.
     */
    private void assignDefenders() {
        List<Unit> pool = new ArrayList<>(home);
        if (state == State.GATHER)
            pool.addAll(main);
        pool.addAll(reinf);
        List<Threat> order = new ArrayList<>(threats);
        order.sort((x, y) -> Float.compare(y.e, x.e));
        for (Threat t : order) {
            float need = Math.max(2f, 1.5f * t.e + 0.5f);
            float got = 0f;
            while (got < need && !pool.isEmpty()) {
                Unit best = null;
                int best_d = Integer.MAX_VALUE;
                for (Unit w : pool) {
                    int d = MapAnalysis.dist2(w.getGridX(), w.getGridY(), t.cx, t.cy);
                    if (d < best_d) {
                        best_d = d;
                        best = w;
                    }
                }
                pool.remove(best);
                t.defenders.add(best);
                got += CombatModel.weight(best);
            }
        }
    }

    /**
     * The armory is the one building we cannot lose. If a strong enemy force reaches it while the main army is
     * out, bring the army home unless it is about to win its own siege.
     */
    private void recallIfCoreThreatened() {
        if (!recall || state == State.GATHER || state == State.RETREAT || main.isEmpty() || toot_return)
            return;
        Building a = ctx.economy.armory();
        if (a == null)
            return;
        Unit an = anchor(main);
        if (MapAnalysis.chebyshev(an.getGridX(), an.getGridY(), a.getGridX(), a.getGridY()) <= 35)
            return;
        float threat = 0f;
        for (Threat t : threats) {
            if (MapAnalysis.chebyshev(t.cx, t.cy, a.getGridX(), a.getGridY()) <= 30)
                threat += t.e;
        }
        if (threat < 4f)
            return;
        float home_def = CombatModel.sumWeights(home) + CombatModel.sumWeights(reinf);
        float towers = 0f;
        for (Building tw : ctx.model.me.towers) {
            if (!tw.isDead() && MapAnalysis.chebyshev(tw.getGridX(), tw.getGridY(), a.getGridX(), a.getGridY()) <= 20)
                towers += CombatModel.towerValue(tw);
        }
        if (home_def + 0.5f * towers >= 1.2f * threat)
            return;
        // Winning a siege on their armory beats running home.
        if (state == State.SIEGE && objective != null && !objective.isDead()
                && objective.getTemplate().getTemplateID() == Race.BUILDING_ARMORY
                && objective.getHitPoints() < 80)
            return;
        float t = threat;
        ctx.log(() -> String.format("RECALL: core threat %.1f vs home %.1f", t, home_def));
        recall_until = ctx.now + 30f;
        retreat();
    }

    /** Enemy units (not just buildings) are inside our zone. */
    private boolean defendingUnits() {
        for (Threat t : threats) {
            if (!t.units.isEmpty())
                return true;
        }
        return false;
    }

    /** A threat big enough near the core that the army must stay home. */
    private boolean majorThreat() {
        Building a = ctx.economy.armory();
        float army = CombatModel.sumWeights(main) + CombatModel.sumWeights(home);
        for (Threat t : threats) {
            if (t.e < Math.max(3f, 0.3f * army))
                continue;
            if (a == null || MapAnalysis.chebyshev(t.cx, t.cy, a.getGridX(), a.getGridY()) <= 45)
                return true;
        }
        return false;
    }

    private boolean nearOurAssets(int x, int y, @NonNull List<Building> mine) {
        int r2 = defend_radius * defend_radius;
        for (Building b : mine) {
            if (b.isDead())
                continue;
            int rr = b.getTemplate().getTemplateID() == Race.BUILDING_ARMORY ? r2 : (r2 * 2) / 3;
            if (MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y) <= rr)
                return true;
        }
        return false;
    }

    private void evacuate() {
        Building a = ctx.economy.armory();
        for (Unit u : ctx.model.me.peons) {
            if (!ctx.resources.threatened(u.getGridX(), u.getGridY(), 9))
                continue;
            if (ctx.orders.recently(u, 2.5f) && ctx.orders.lastOrder(u).kind() == Orders.Kind.ENTER)
                continue;
            if (ctx.roleOf(u) == Context.Role.CORPS)
                continue;
            ctx.build.release(u);
            if (ctx.roleOf(u) == Context.Role.HUNTER)
                ctx.clearRole(u);
            if (a != null && MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), a.getGridX(), a.getGridY()) < 45) {
                ctx.orders.enter(u, a);
                continue;
            }
            Building q = nearestSafeQuarters(u.getGridX(), u.getGridY());
            if (q != null && !BuildManager.carriesAnything(u)
                    && MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), q.getGridX(), q.getGridY()) < 30) {
                ctx.orders.enter(u, q);
                continue;
            }
            int[] away = awayFromEnemy(u.getGridX(), u.getGridY(), 14);
            ctx.orders.move(u, away[0], away[1]);
        }
    }

    /** The nearest quarters that is not itself under attack (a besieged one deploys its peons straight back out). */
    private @Nullable Building nearestSafeQuarters(int x, int y) {
        Building best = null;
        int best_d = Integer.MAX_VALUE;
        for (Building q : ctx.model.me.quarters) {
            if (q.isDead() || quartersThreatened(q) || ctx.resources.threatened(q.getGridX(), q.getGridY(), 14))
                continue;
            int d = MapAnalysis.dist2(q.getGridX(), q.getGridY(), x, y);
            if (d < best_d) {
                best_d = d;
                best = q;
            }
        }
        return best;
    }

    int @NonNull [] awayFromEnemy(int x, int y, int dist) {
        float dx = 0f;
        float dy = 0f;
        for (Unit e : ctx.model.enemy.warriors) {
            int d2 = MapAnalysis.dist2(e.getGridX(), e.getGridY(), x, y);
            if (d2 > 400 || d2 == 0)
                continue;
            float inv = 1f / d2;
            dx += (x - e.getGridX()) * inv;
            dy += (y - e.getGridY()) * inv;
        }
        Unit chief = ctx.model.enemy.chieftain;
        if (chief != null && !chief.isDead()) {
            int d2 = MapAnalysis.dist2(chief.getGridX(), chief.getGridY(), x, y);
            if (d2 > 0 && d2 <= 400) {
                float inv = 1f / d2;
                dx += (x - chief.getGridX()) * inv;
                dy += (y - chief.getGridY()) * inv;
            }
        }
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6f) {
            dx = ctx.map.home_x - x;
            dy = ctx.map.home_y - y;
            len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-6f)
                return new int[]{x, y};
        }
        int tx = ctx.map.clamp(Math.round(x + dx / len * dist));
        int ty = ctx.map.clamp(Math.round(y + dy / len * dist));
        int[] acc = ctx.map.nearestAccessible(tx, ty);
        return acc != null ? acc : new int[]{x, y};
    }

    private void updateGather() {
        if (ctx.now - gather_time < 20f)
            return;
        gather_time = ctx.now;
        Building a = ctx.economy.armory();
        int bx = a != null ? a.getGridX() : ctx.map.home_x;
        int by = a != null ? a.getGridY() : ctx.map.home_y;
        int[] p = BuildManager.towards(bx, by, ctx.map.enemy_x, ctx.map.enemy_y, 0);
        int dx = p[0] - bx;
        int dy = p[1] - by;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        int gx = bx;
        int gy = by;
        if (len > 1f) {
            gx = Math.round(bx + dx / len * 12f);
            gy = Math.round(by + dy / len * 12f);
        }
        int[] acc = ctx.map.nearestAccessible(ctx.map.clamp(gx), ctx.map.clamp(gy));
        if (acc != null)
            gather = acc;
    }

    // -----------------------------------------------------------------------------------------------------
    // Posture (every 1 s)

    void postureTick() {
        rebuildSquads();
        switch (state) {
            case GATHER -> gatherState();
            case MARCH -> marchState();
            case ENGAGE -> engageState();
            case SIEGE -> siegeState();
            case RETREAT -> retreatState();
        }
        recallIfCoreThreatened();
        reinforce();
        raidTick();
        if (defending)
            defend();
        else
            idleHome();
    }

    private void setState(@NonNull State s) {
        if (s == state)
            return;
        if (s != State.ENGAGE)
            toot_return = false;
        if (s == State.GATHER)
            endAttack();
        if (s == State.GATHER || s == State.RETREAT)
            resetSiege();
        if (s == State.MARCH) {
            best_prog = Integer.MAX_VALUE;
            best_prog_t = ctx.now;
        }
        State old = state;
        state = s;
        state_since = ctx.now;
        int size = main.size();
        ctx.log(() -> "army " + old + " -> " + s + " size=" + size + (objective != null ? " obj=" + objective.getTemplate().getTemplateID() + "@" + obj_x + "," + obj_y : ""));
    }

    private float enemyFieldStrength() {
        WorldModel.Side e = ctx.model.enemy;
        float units = CombatModel.sumWeights(e.warriors);
        if (e.chieftain != null)
            units += 1.5f;
        // What an enemy armory holds is hidden: assume a fixed reserve per armory.
        units += enemy_latent_guess * e.armories.size();
        return units;
    }

    /**
     * Enemy strength around a point: the given unit strength plus the towers within r. Units that fight together add
     * up (Lanchester), so with towerLinear > 0 each tower counts as towerValue * towerLinear warriors; with 0 the
     * older quadrature combination is used.
     */
    private float withTowers(float units, int x, int y, int r) {
        if (tower_linear <= 0f)
            return CombatModel.strength(units, towerStrengthNear(x, y, r));
        float sum = 0f;
        int r2 = r * r;
        for (Building t : ctx.model.enemy.towers) {
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= r2)
                sum += CombatModel.towerValue(t) * tower_linear;
        }
        return units + sum;
    }

    private float towerStrengthNear(int x, int y, int r) {
        float sq = 0f;
        int r2 = r * r;
        for (Building t : ctx.model.enemy.towers) {
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= r2) {
                float v = CombatModel.towerValue(t);
                sq += v * v;
            }
        }
        return sq;
    }

    private void gatherState() {
        main.addAll(home);
        for (Unit w : home)
            squad_of.put(w, MAIN);
        home.clear();
        boolean trace = ctx.now - last_gather_trace >= 30f;
        if (trace)
            last_gather_trace = ctx.now;
        if (majorThreat()) {
            if (trace)
                ctx.log(() -> "gather: held by major threat, main=" + main.size());
            return;
        }
        int stocked = stockedIron();
        float ours = CombatModel.sumWeights(main) + stocked;
        Building first = chooseTarget();
        if (first == null) {
            if (!enemyBuildingsLeft() && !ctx.model.enemy.units.isEmpty() && !main.isEmpty()) {
                closeout = true;
                closeoutTick();
            }
            return;
        }
        // A push stopped at a tower line recently: targets near or behind it wait for clear odds there.
        Building target = assault_gate ? chooseTarget(ours) : first;
        if (target == null) {
            if (trace)
                ctx.log(() -> "gather: tower line at " + gate_x + "," + gate_y + " still too strong");
            return;
        }
        float theirs = withTowers(enemyFieldStrength(), target.getGridX(), target.getGridY(), 26);
        if (assault_gate && approach_towers)
            theirs = enemyFieldStrength() + approachLine(gather[0], gather[1], target.getGridX(), target.getGridY());
        // A defending enemy chieftain stuns a clumped attacker; only our own ready stun answers it.
        Unit their_chief = ctx.model.enemy.chieftain;
        Unit our_chief = ctx.owner.getChieftain();
        boolean answer = our_chief != null && !our_chief.isDead()
                && our_chief.getMagicProgress(com.oddlabs.tt.model.RacesResources.INDEX_MAGIC_STUN) >= 0.99f;
        if (their_chief != null && !their_chief.isDead() && !answer)
            theirs *= 1f + enemy_chief_factor;
        int pop = ctx.owner.getUnitCountContainer().getNumSupplies();
        // A full army waiting at the unit cap forgets old failed attacks slowly: the enemy may have spent its units
        // meanwhile, and waiting forever at the cap wastes the army.
        if (learn_decay > 0f && pop >= ctx.world.getMaxUnitCount() - 10 && defense_mult > 1f) {
            float dt = Math.max(0f, ctx.now - last_decay);
            float old = defense_mult;
            defense_mult = 1f + (defense_mult - 1f) * (float) StrictMath.pow(0.5, dt / learn_decay);
            if ((int) (old * 10f) != (int) (defense_mult * 10f)) {
                float now = defense_mult;
                ctx.log(() -> String.format("enemy defense decays x%.2f -> x%.2f", old, now));
            }
        }
        last_decay = ctx.now;
        theirs *= defense_mult;
        boolean at_cap = pop >= cap_attack_frac * ctx.world.getMaxUnitCount();
        if (trace) {
            float o = ours;
            float t = theirs;
            ctx.log(() -> String.format("gather: ours=%.1f theirs=%.1f (x%.2f) main=%d target=%s", o, t, defense_mult,
                    main.size(),
                    target.getTemplate().getTemplateID() + "@" + target.getGridX() + "," + target.getGridY()));
        }
        boolean commit = main.size() + stocked >= attack_min && ours >= commit_ratio * theirs;
        boolean grind = at_cap && main.size() + stocked >= attack_min / 2 && ours >= grind_ratio * theirs;
        if ((commit || grind) && stocked >= 3 && ctx.now >= muster_until + 20f) {
            // Muster: deploy the stock (1.5 s per iron warrior) and march once it has joined.
            muster_until = ctx.now + 1.5f * stocked + 4f;
            int n = stocked;
            ctx.log(() -> "muster " + n + " stocked weapons");
            return;
        }
        if (ctx.now < muster_until)
            return;
        if (commit || grind) {
            setObjective(target);
            float o = ours;
            float t = theirs;
            ctx.log(() -> String.format("attack: ours=%.1f theirs=%.1f", o, t));
            startAttack();
            setState(State.MARCH);
            newLeg();
        }
    }

    private void setObjective(@NonNull Building target) {
        closeout = false;
        toot_return = false;
        objective = target;
        obj_x = target.getGridX();
        obj_y = target.getGridY();
        // Only cells nearer to the objective than the army matter for walking downhill toward it.
        int from_x = main.isEmpty() ? gather[0] : anchor(main).getGridX();
        int from_y = main.isEmpty() ? gather[1] : anchor(main).getGridY();
        int limit = 3 * MapAnalysis.octile(obj_x - from_x, obj_y - from_y) / 2 + 200;
        route = ctx.map.distanceField(obj_x, obj_y, limit, route);
        best_prog = Integer.MAX_VALUE;
        best_prog_t = ctx.now;
    }

    /** The most valuable reachable enemy building, discounted by distance and tower cover. */
    private @Nullable Building chooseTarget() {
        return chooseTarget(-1f);
    }

    /** As chooseTarget(); with ours >= 0 also skips targets a recently stopped push says are too strong. */
    private @Nullable Building chooseTarget(float ours) {
        Building best = null;
        float best_score = Float.NEGATIVE_INFINITY;
        int fx = main.isEmpty() ? gather[0] : anchor(main).getGridX();
        int fy = main.isEmpty() ? gather[1] : anchor(main).getGridY();
        boolean has_armory = !ctx.model.enemy.armories.isEmpty();
        for (Building b : ctx.model.enemy.buildings) {
            if (b.isDead())
                continue;
            if (ours >= 0f && gateMemoryBlocks(b, ours, fx, fy))
                continue;
            Float su = stalled.get(b);
            if (su != null && ctx.now < su)
                continue;
            float value;
            int id = b.getTemplate().getTemplateID();
            if (!b.isComplete())
                value = 6f;
            else if (id == Race.BUILDING_ARMORY)
                value = 80f;
            else if (id == Race.BUILDING_QUARTERS)
                value = has_armory ? 35f : 70f;
            else
                value = 14f;
            float d = (float) Math.sqrt(MapAnalysis.dist2(b.getGridX(), b.getGridY(), fx, fy));
            float cover = 0f;
            for (Building t : ctx.model.enemy.towers) {
                if (t != b && MapAnalysis.dist2(t.getGridX(), t.getGridY(), b.getGridX(), b.getGridY()) <= 324)
                    cover += CombatModel.towerValue(t) * 0.6f * cover_weight;
            }
            float score = value / (1f + d / 70f) - cover;
            if (score > best_score) {
                best_score = score;
                best = b;
            }
        }
        return best;
    }

    private @NonNull Unit anchor(@NonNull List<Unit> squad) {
        long sx = 0;
        long sy = 0;
        for (Unit u : squad) {
            sx += u.getGridX();
            sy += u.getGridY();
        }
        int cx = (int) (sx / squad.size());
        int cy = (int) (sy / squad.size());
        Unit best = squad.get(0);
        int best_d = Integer.MAX_VALUE;
        for (Unit u : squad) {
            int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), cx, cy);
            if (d < best_d) {
                best_d = d;
                best = u;
            }
        }
        return best;
    }

    /** The next waypoint: leg_length steps down the objective's walking-distance field from the anchor. */
    private int routeAt(int x, int y) {
        int[] f = route;
        return f == null || !ctx.map.inside(x, y) ? MapAnalysis.UNREACHABLE : f[y * ctx.map.n + x];
    }

    private void newLeg() {
        newLeg(false);
    }

    /** from_wp: continue from the previous waypoint when it is further along the route than the anchor. */
    private void newLeg(boolean from_wp) {
        if (main.isEmpty())
            return;
        Unit a = anchor(main);
        int x = a.getGridX();
        int y = a.getGridY();
        int av = routeAt(x, y);
        leg_anchor_v = av;
        if (from_wp && waypoint != null) {
            int wv = routeAt(waypoint[0], waypoint[1]);
            if (wv != MapAnalysis.UNREACHABLE && (av == MapAnalysis.UNREACHABLE || wv < av)) {
                x = waypoint[0];
                y = waypoint[1];
            }
        }
        int[] field = route;
        if (field != null && field[y * ctx.map.n + x] == MapAnalysis.UNREACHABLE)
            field = null;
        if (field != null) {
            int n = ctx.map.n;
            for (int step = 0; step < leg_length; step++) {
                int best = field[y * n + x];
                int bx = x;
                int by = y;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (!ctx.map.inside(nx, ny))
                            continue;
                        int v = field[ny * n + nx];
                        if (v < best) {
                            best = v;
                            bx = nx;
                            by = ny;
                        }
                    }
                }
                if (bx == x && by == y)
                    break;
                x = bx;
                y = by;
            }
        } else {
            int[] t = BuildManager.towards(x, y, obj_x, obj_y, 0);
            x = t[0];
            y = t[1];
        }
        waypoint = new int[]{x, y};
        leg_start = ctx.now;
        ctx.orders.group(main, x, y, false);
    }

    private boolean enemiesNear(@NonNull List<Unit> squad, int r) {
        if (squad.isEmpty())
            return false;
        Unit a = anchor(squad);
        int r2 = r * r;
        for (Unit e : ctx.model.enemy.warriors) {
            if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), a.getGridX(), a.getGridY()) <= r2)
                return true;
        }
        for (Building t : ctx.model.enemy.towers) {
            if (WorldModel.garrisonOf(t) != null
                    && MapAnalysis.dist2(t.getGridX(), t.getGridY(), a.getGridX(), a.getGridY()) <= r2)
                return true;
        }
        return false;
    }

    private void marchState() {
        if (main.isEmpty()) {
            setState(State.GATHER);
            return;
        }
        if (objective == null || objective.isDead()) {
            nextObjectiveOrHome();
            return;
        }
        if (enemiesNear(main, 24)) {
            last_enemy_near = ctx.now;
            setState(State.ENGAGE);
            return;
        }
        Unit a = anchor(main);
        if (MapAnalysis.chebyshev(a.getGridX(), a.getGridY(), obj_x, obj_y) <= 14) {
            if (assaultBlocked(obj_x, obj_y)) {
                stopPush(obj_x, obj_y);
                return;
            }
            setState(State.SIEGE);
            return;
        }
        int av = routeAt(a.getGridX(), a.getGridY());
        if (march_watch) {
            // An army that makes no progress along the route for a minute (looping at a narrow passage) gives up
            // on this objective for a while instead of marching in circles.
            if (av == MapAnalysis.UNREACHABLE) {
                best_prog = Integer.MAX_VALUE;
                best_prog_t = ctx.now;
            } else if (av < best_prog - 10) {
                best_prog = av;
                best_prog_t = ctx.now;
            } else if (ctx.now - best_prog_t > 60f) {
                Building o = objective;
                int v = av;
                ctx.log(() -> "march stalled at route " + v + ": objective dropped for 300 s");
                if (o != null)
                    stalled.put(o, ctx.now + 300f);
                if (ctx.owner.getUnitsLost() == attack_lost0 && ctx.owner.getUnitsKilled() == attack_killed0)
                    attack_active = false;
                retreat();
                return;
            }
        }
        int rc = 3 + Math.round(0.8f * (float) Math.sqrt(main.size()));
        int wv = march_watch ? routeAt(waypoint[0], waypoint[1]) : MapAnalysis.UNREACHABLE;
        int close = 0;
        for (Unit u : main) {
            if (wv != MapAnalysis.UNREACHABLE) {
                // Near the waypoint along the route, not just in a straight line (a wall or inlet between them).
                int uv = routeAt(u.getGridX(), u.getGridY());
                if (uv != MapAnalysis.UNREACHABLE && uv <= wv + 2 * (rc + 2)
                        && MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), waypoint[0], waypoint[1]) <= rc + 2)
                    close++;
            } else if (MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), waypoint[0], waypoint[1]) <= rc + 2) {
                close++;
            }
        }
        float leg_timeout = leg_length * 1.4f / 2f + 6f;
        if (close >= cohesion_frac * main.size()) {
            newLeg(march_watch);
        } else if (ctx.now - leg_start > leg_timeout) {
            // No progress along the route since the last leg: push on from the waypoint instead of the anchor.
            boolean stuck = march_watch && av != MapAnalysis.UNREACHABLE && leg_anchor_v != MapAnalysis.UNREACHABLE
                    && av > leg_anchor_v - 6;
            newLeg(stuck);
        }
    }

    private void engageState() {
        if (main.isEmpty()) {
            setState(State.GATHER);
            return;
        }
        boolean warriors_near = enemyWarriorsNear(main, 28);
        if (enemiesNear(main, 28))
            last_enemy_near = ctx.now;
        if (!tootActive() && localRatio(main) < retreat_ratio) {
            retreat();
            return;
        }
        Unit gate_anchor = anchor(main);
        if (assaultBlocked(gate_anchor.getGridX(), gate_anchor.getGridY())) {
            stopPush(gate_anchor.getGridX(), gate_anchor.getGridY());
            return;
        }
        // Only towers left around us: siege the nearest one explicitly instead of idling out of range.
        if (!warriors_near && !closeout) {
            Building tower = nearestMannedTower(main, 24);
            if (tower != null) {
                if (assaultBlocked(tower.getGridX(), tower.getGridY())) {
                    stopPush(tower.getGridX(), tower.getGridY());
                    return;
                }
                if (objective == null || objective.isDead()
                        || MapAnalysis.chebyshev(obj_x, obj_y, tower.getGridX(), tower.getGridY()) > 24)
                    setObjective(tower);
                setState(State.SIEGE);
                return;
            }
        }
        if (ctx.now - last_enemy_near > 5f) {
            if (toot_return) {
                toot_return = false;
                objective = null;
                setState(State.GATHER);
                ctx.orders.group(main, gather[0], gather[1], false);
            } else if (closeout) {
                closeoutTick();
            } else if (objective == null || objective.isDead()) {
                nextObjectiveOrHome();
            } else if (!pressOnOk()) {
                ctx.log(() -> "attack called off before the tower line");
                Unit ga = anchor(main);
                gate_need = grind_ratio * defense_mult * (enemyFieldStrength() + approachLine(ga.getGridX(),
                        ga.getGridY(), obj_x, obj_y));
                gate_x = obj_x;
                gate_y = obj_y;
                gate_t = ctx.now;
                retreat();
            } else {
                setState(State.MARCH);
                newLeg();
            }
        }
    }

    private void retreat() {
        resetSiege();
        endAttack();
        objective = null;
        closeout = false;
        toot_return = false;
        setState(State.RETREAT);
        ctx.orders.group(main, gather[0], gather[1], false);
    }

    private boolean enemyWarriorsNear(@NonNull List<Unit> squad, int r) {
        if (squad.isEmpty())
            return false;
        Unit a = anchor(squad);
        int r2 = r * r;
        for (Unit e : ctx.model.enemy.warriors) {
            if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), a.getGridX(), a.getGridY()) <= r2)
                return true;
        }
        return false;
    }

    private @Nullable Building nearestMannedTower(@NonNull List<Unit> squad, int r) {
        Unit a = anchor(squad);
        Building best = null;
        int best_d = r * r + 1;
        for (Building t : ctx.model.enemy.towers) {
            if (t.isDead() || WorldModel.garrisonOf(t) == null)
                continue;
            int d = MapAnalysis.dist2(t.getGridX(), t.getGridY(), a.getGridX(), a.getGridY());
            if (d < best_d) {
                best_d = d;
                best = t;
            }
        }
        return best;
    }

    /** No enemy buildings remain: hunt the nearest remaining enemy unit until the enemy is eliminated. */
    private void closeoutTick() {
        if (main.isEmpty() || ctx.model.enemy.units.isEmpty()) {
            closeout = false;
            return;
        }
        Unit a = anchor(main);
        Unit best = null;
        int best_d = Integer.MAX_VALUE;
        for (Unit e : ctx.model.enemy.units) {
            int d = MapAnalysis.dist2(e.getGridX(), e.getGridY(), a.getGridX(), a.getGridY());
            if (d < best_d) {
                best_d = d;
                best = e;
            }
        }
        if (best == null)
            return;
        boolean moved = MapAnalysis.chebyshev(best.getGridX(), best.getGridY(), obj_x, obj_y) > 6;
        obj_x = best.getGridX();
        obj_y = best.getGridY();
        List<Unit> go = new ArrayList<>();
        for (Unit w : main) {
            if (!(w.getCurrentController() instanceof HuntController) && (moved || !ctx.orders.recently(w, 4f)))
                go.add(w);
        }
        if (!go.isEmpty())
            ctx.orders.group(go, obj_x, obj_y, true);
        last_enemy_near = ctx.now;
        setState(State.ENGAGE);
    }

    private void siegeState() {
        if (main.isEmpty()) {
            setState(State.GATHER);
            return;
        }
        if (!tootActive() && localRatio(main) < retreat_ratio) {
            retreat();
            return;
        }
        Unit gate_anchor = anchor(main);
        if (assaultBlocked(gate_anchor.getGridX(), gate_anchor.getGridY())) {
            stopPush(gate_anchor.getGridX(), gate_anchor.getGridY());
            return;
        }
        if (objective == null || objective.isDead())
            nextObjectiveOrHome();
    }

    private void retreatState() {
        if (main.isEmpty() || ctx.now - state_since > 25f) {
            setState(State.GATHER);
            return;
        }
        resendStragglers(main, state_since);
        Unit a = anchor(main);
        if (MapAnalysis.chebyshev(a.getGridX(), a.getGridY(), gather[0], gather[1]) < 10)
            setState(State.GATHER);
    }

    private void nextObjectiveOrHome() {
        Building next = chooseTarget();
        float ours = CombatModel.sumWeights(main);
        if (next != null && assault_gate) {
            next = chooseTarget(ours);
            if (next == null) {
                retreat();
                return;
            }
        }
        if (next != null) {
            float theirs = defense_mult * withTowers(enemyFieldStrength(), next.getGridX(), next.getGridY(), 26);
            if (assault_gate) {
                Unit an = anchor(main);
                theirs = defense_mult * (enemyFieldStrength() + approachLine(an.getGridX(), an.getGridY(),
                        next.getGridX(), next.getGridY()));
            }
            if (attack_active && ours >= grind_ratio * theirs && main.size() >= 4
                    && (!assault_gate || main.size() >= attack_min / 2)) {
                setObjective(next);
                setState(State.MARCH);
                newLeg();
                return;
            }
        } else if (!ctx.model.enemy.units.isEmpty() && !enemyBuildingsLeft()) {
            // Closeout: no buildings left, hunt the remaining units.
            objective = null;
            route = null;
            closeout = true;
            closeoutTick();
            return;
        }
        retreat();
    }

    /** Our local strength divided by the enemy's around the squad. */
    private float localRatio(@NonNull List<Unit> squad) {
        Unit a = anchor(squad);
        int ax = a.getGridX();
        int ay = a.getGridY();
        float ours = 0f;
        for (Unit u : squad) {
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), ax, ay) <= 625)
                ours += CombatModel.weight(u);
        }
        float theirs = 0f;
        for (Unit e : ctx.model.enemy.warriors) {
            if (MapAnalysis.dist2(e.getGridX(), e.getGridY(), ax, ay) <= 625 && !WorldModel.isStunned(e))
                theirs += CombatModel.weight(e);
        }
        float t = withTowers(theirs, ax, ay, 20);
        return t < 0.5f ? 99f : ours / t;
    }

    private void reinforce() {
        boolean away = state != State.GATHER && state != State.RETREAT;
        if (!away) {
            if (!reinf.isEmpty())
                ctx.orders.group(reinf, gather[0], gather[1], false);
            for (Unit w : reinf)
                squad_of.put(w, MAIN);
            reinf.clear();
            return;
        }
        if (!reinf.isEmpty() && !main.isEmpty()) {
            Unit ma = anchor(main);
            List<Unit> joined = new ArrayList<>();
            for (Unit w : reinf) {
                if (MapAnalysis.chebyshev(w.getGridX(), w.getGridY(), ma.getGridX(), ma.getGridY()) <= 12)
                    joined.add(w);
            }
            for (Unit w : joined) {
                squad_of.put(w, MAIN);
                main.add(w);
                reinf.remove(w);
            }
        }
        if (defendingUnits())
            return;
        if (home.size() >= reinforce_batch) {
            for (Unit w : home) {
                squad_of.put(w, REINF);
                reinf.add(w);
            }
            home.clear();
        }
        if (reinf.isEmpty() || main.isEmpty())
            return;
        Unit a = anchor(main);
        Unit r = anchor(reinf);
        if (MapAnalysis.chebyshev(a.getGridX(), a.getGridY(), r.getGridX(), r.getGridY()) <= 12) {
            for (Unit w : reinf) {
                squad_of.put(w, MAIN);
                main.add(w);
            }
            reinf.clear();
            return;
        }
        List<Unit> to_order = new ArrayList<>();
        for (Unit w : reinf) {
            if (!ctx.orders.recently(w, Orders.Kind.ATTACK_MOVE, null, a.getGridX(), a.getGridY(), 6f)
                    && !(w.getCurrentController() instanceof HuntController))
                to_order.add(w);
        }
        if (!to_order.isEmpty())
            ctx.orders.group(to_order, a.getGridX(), a.getGridY(), true);
    }

    private void defend() {
        Building a = ctx.economy.armory();
        for (Threat t : threats) {
            if (t.defenders.isEmpty() || canEngage(t))
                continue;
            if (a != null) {
                // Outnumbered: fall back under the towers at the armory and let production catch up.
                List<Unit> far = new ArrayList<>();
                for (Unit w : t.defenders) {
                    if (MapAnalysis.chebyshev(w.getGridX(), w.getGridY(), a.getGridX(), a.getGridY()) > 10
                            && !ctx.orders.recently(w, 3f))
                        far.add(w);
                }
                if (!far.isEmpty())
                    ctx.orders.group(far, a.getGridX(), a.getGridY(), false);
            }
        }
    }

    private boolean canEngage(@NonNull Threat t) {
        float ours = CombatModel.sumWeights(t.defenders);
        float towers = 0f;
        for (Building tw : ctx.model.me.towers) {
            if (!tw.isDead() && MapAnalysis.dist2(tw.getGridX(), tw.getGridY(), t.cx, t.cy) <= 18 * 18)
                towers += CombatModel.towerValue(tw);
        }
        return ours + 0.5f * towers >= 0.8f * t.e || t.e < 0.5f;
    }

    private void idleHome() {
        // Keep the home squad near the gathering point; idle units there auto-engage anything that comes close.
        if (state != State.GATHER && home.isEmpty())
            return;
        List<Unit> squad = state == State.GATHER ? main : home;
        List<Unit> far = new ArrayList<>();
        for (Unit w : squad) {
            if (MapAnalysis.chebyshev(w.getGridX(), w.getGridY(), gather[0], gather[1]) > 9
                    && WorldModel.activityOf(w) == WorldModel.Activity.IDLE && !ctx.orders.recently(w, 5f))
                far.add(w);
        }
        if (!far.isEmpty())
            ctx.orders.group(far, gather[0], gather[1], false);
    }

    // -----------------------------------------------------------------------------------------------------
    // Micro (every 0.25 s)

    void microTick() {
        rebuildSquads();
        dodgeTick();
        followFrozen();
        if (!raid.isEmpty() && raid_state == RaidState.STRIKE)
            engage(raid, raid_x, raid_y, false, 12);
        if (defending) {
            for (Threat t : threats) {
                if (!t.defenders.isEmpty() && canEngage(t))
                    engage(t.defenders, t.cx, t.cy, !t.sites.isEmpty(), 16);
            }
        }
        if (state == State.ENGAGE || state == State.SIEGE || (state == State.MARCH && enemiesNear(main, 14))) {
            int[] hold = stunSiegeHold();
            if (hold != null && hold[2] == 0) {
                holdAt(hold);
            } else if (hold != null) {
                engage(main, hold[0], hold[1], false, engage_range);
            } else {
                int[] fp = fightPoint();
                engage(main, fp[0], fp[1], state == State.SIEGE,
                        engage_range + (tootActive() || ctx.now < frozen_until ? 8 : 0));
            }
        }
    }

    // Assault gate: an army inside an enemy tower line only stays if the odds, towers counted linearly, are clearly
    // ours. Pushes into tower rings after a won field fight were the biggest source of lost units.

    private float toot_order_time = Float.NEGATIVE_INFINITY;
    private int gate_x;
    private int gate_y;
    private float gate_t = Float.NEGATIVE_INFINITY;
    /** Whole-army strength the stopped push would have needed (the field gate tests only the units near the anchor). */
    private float gate_need;

    private float towerLine(int x, int y, int r) {
        float sum = 0f;
        for (Building t : ctx.model.enemy.towers) {
            if (!t.isDead() && MapAnalysis.chebyshev(t.getGridX(), t.getGridY(), x, y) <= r)
                sum += CombatModel.towerValue(t) * tower_lin_w;
        }
        return sum;
    }

    private int mannedTowers(int x, int y, int r) {
        int n = 0;
        for (Building t : ctx.model.enemy.towers) {
            if (!t.isDead() && CombatModel.towerValue(t) > 0f
                    && MapAnalysis.chebyshev(t.getGridX(), t.getGridY(), x, y) <= r)
                n++;
        }
        return n;
    }

    /** Visible enemy defence around a point: unstunned warriors within 25, the chieftain, towers (linear). */
    private float groundStrength(int x, int y) {
        float s = 0f;
        for (Unit e : ctx.model.enemy.warriors) {
            if (!WorldModel.isStunned(e) && MapAnalysis.dist2(e.getGridX(), e.getGridY(), x, y) <= 625)
                s += CombatModel.weight(e);
        }
        Unit ec = ctx.model.enemy.chieftain;
        if (ec != null && !ec.isDead() && !WorldModel.isStunned(ec)
                && MapAnalysis.dist2(ec.getGridX(), ec.getGridY(), x, y) <= 900)
            s += assault_chief;
        return s + towerLine(x, y, assault_r);
    }

    private float oursNearAnchor() {
        Unit a = anchor(main);
        float ours = 0f;
        for (Unit u : main) {
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), a.getGridX(), a.getGridY()) <= 625)
                ours += CombatModel.weight(u);
        }
        return ours;
    }

    private boolean assaultBlocked(int x, int y) {
        if (!assault_gate || closeout || main.isEmpty() || ctx.now < toot_order_time + 4.5f)
            return false;
        if (mannedTowers(x, y, assault_r) < 2)
            return false;
        // A stun-siege hold waits outside tower reach for our stun; that is not a push into the line.
        if (siege_holding) {
            Unit a = anchor(main);
            if (!nearMannedEnemyTower(a.getGridX(), a.getGridY(), 12))
                return false;
        }
        return oursNearAnchor() < assault_ratio * groundStrength(x, y);
    }

    /** A recently stopped push: targets near the stop point or behind it (on the way there) wait for clear odds. */
    private boolean gateMemoryBlocks(@NonNull Building b, float ours, int fx, int fy) {
        if (ctx.now - gate_t >= assault_memory || mannedTowers(gate_x, gate_y, assault_r) < 2)
            return false;
        int tx = b.getGridX();
        int ty = b.getGridY();
        boolean near = MapAnalysis.chebyshev(gate_x, gate_y, tx, ty) <= 40;
        if (!near) {
            float vx = tx - fx;
            float vy = ty - fy;
            float l2 = vx * vx + vy * vy;
            float px = gate_x - fx;
            float py = gate_y - fy;
            float u = l2 < 1f ? 0f : Math.max(0f, Math.min(1f, (px * vx + py * vy) / l2));
            float dx = px - u * vx;
            float dy = py - u * vy;
            near = dx * dx + dy * dy <= assault_r * assault_r;
        }
        return near && ours < Math.max(gate_need, assault_ratio * groundStrength(gate_x, gate_y));
    }

    private void stopPush(int x, int y) {
        ctx.log(() -> "push stopped at tower line " + x + "," + y);
        gate_x = x;
        gate_y = y;
        gate_t = ctx.now;
        gate_need = CombatModel.sumWeights(main) * assault_ratio * groundStrength(x, y) / Math.max(1f,
                oursNearAnchor());
        if (ctx.owner.getUnitsLost() == attack_lost0 && ctx.owner.getUnitsKilled() == attack_killed0)
            attack_active = false;
        retreat();
    }

    /** Linear tower value on the way from (fx,fy) to (tx,ty): within 16 cells of the segment or 26 of the target. */
    private float approachLine(int fx, int fy, int tx, int ty) {
        float sum = 0f;
        float vx = tx - fx;
        float vy = ty - fy;
        float l2 = vx * vx + vy * vy;
        for (Building t : ctx.model.enemy.towers) {
            if (t.isDead())
                continue;
            float v = CombatModel.towerValue(t);
            if (v <= 0f)
                continue;
            float px = t.getGridX() - fx;
            float py = t.getGridY() - fy;
            float u = l2 < 1f ? 0f : Math.max(0f, Math.min(1f, (px * vx + py * vy) / l2));
            float dx = px - u * vx;
            float dy = py - u * vy;
            boolean near_path = dx * dx + dy * dy <= 16 * 16;
            boolean near_target = MapAnalysis.chebyshev(t.getGridX(), t.getGridY(), tx, ty) <= 26;
            if (near_path || near_target)
                sum += v * tower_lin_w;
        }
        return sum;
    }

    /** Pressing on to the objective is worth it: enough units and odds counting the towers on the way. */
    private boolean pressOnOk() {
        if (!assault_gate || objective == null)
            return true;
        Unit a = anchor(main);
        float theirs = enemyFieldStrength() + approachLine(a.getGridX(), a.getGridY(), obj_x, obj_y);
        return main.size() >= attack_min / 2 && CombatModel.sumWeights(main) >= grind_ratio * defense_mult * theirs;
    }

    /**
     * The stun window lasts while enemies are visibly frozen near the army (the cast may land late, e.g. when our
     * chieftain was interrupted), and an army falling back turns around to exploit a freeze it can win.
     */
    private void followFrozen() {
        if (!frozen_window || main.isEmpty())
            return;
        Unit a = anchor(main);
        int n = 0;
        float weight = 0f;
        long sx = 0;
        long sy = 0;
        for (Unit e : ctx.model.enemy.warriors) {
            if (WorldModel.isStunned(e) && MapAnalysis.dist2(e.getGridX(), e.getGridY(), a.getGridX(),
                    a.getGridY()) <= 22 * 22) {
                n++;
                weight += CombatModel.weight(e);
                sx += e.getGridX();
                sy += e.getGridY();
            }
        }
        for (Building t : ctx.model.enemy.towers) {
            Unit g = WorldModel.garrisonOf(t);
            if (g != null && WorldModel.isStunned(g)
                    && MapAnalysis.dist2(t.getGridX(), t.getGridY(), a.getGridX(), a.getGridY()) <= 22 * 22) {
                n++;
                weight += 2f;
                sx += t.getGridX();
                sy += t.getGridY();
            }
        }
        if (n < frozen_min || weight < 4f)
            return;
        frozen_until = ctx.now + 1f;
        toot_x = (int) (sx / n);
        toot_y = (int) (sy / n);
        boolean gated = ctx.now < recall_until
                || (assault_gate && (ctx.now - gate_t < 5f || assaultBlocked(a.getGridX(), a.getGridY())));
        if ((state == State.RETREAT || state == State.GATHER) && !gated && localRatio(main) >= frozen_reengage) {
            last_enemy_near = ctx.now;
            toot_return = true;
            setState(State.ENGAGE);
            int k = n;
            ctx.log(() -> "stun window: " + k + " frozen enemies, re-engaging");
        }
    }

    // Armory siege. The armory is the only shelter, and everyone inside dies with it (the engine removes a building's
    // occupants with it). While visible enemy warriors at the armory outweigh its defence, or it is badly damaged with
    // enemies at it, peons are kept out and sent to the rear, and the armory empties (EconomyManager.armoryDrain).

    private float sieged_until = Float.NEGATIVE_INFINITY;
    private float sieged_since = Float.NEGATIVE_INFINITY;
    private float siege_checked = Float.NaN;
    private int @Nullable [] rear;
    private float rear_time = Float.NEGATIVE_INFINITY;

    /** True while our armory is besieged (holds 5 s after the last time it was). */
    boolean armorySieged() {
        if (siege_checked != ctx.now) {
            siege_checked = ctx.now;
            updateArmorySiege();
        }
        return ctx.now < sieged_until;
    }

    /** Game time the current armory siege began. */
    float armorySiegedSince() {
        return sieged_since;
    }

    /** Where peons wait out a siege: our building farthest from the attackers, or a point behind the armory. */
    int @Nullable [] rearPoint() {
        return armorySieged() ? rear : null;
    }

    private void updateArmorySiege() {
        Building a = ctx.economy.armory();
        if (!siege_evac || a == null || a.isDead()) {
            sieged_until = Float.NEGATIVE_INFINITY;
            rear = null;
            return;
        }
        float enemy = 0f;
        long sx = 0;
        long sy = 0;
        int n = 0;
        for (Unit e : ctx.model.enemy.warriors) {
            if (WorldModel.isStunned(e)
                    || MapAnalysis.chebyshev(e.getGridX(), e.getGridY(), a.getGridX(), a.getGridY()) > 14)
                continue;
            enemy += CombatModel.weight(e);
            sx += e.getGridX();
            sy += e.getGridY();
            n++;
        }
        if (n > 0) {
            float def = 0f;
            for (Unit w : ctx.model.me.warriors) {
                if (MapAnalysis.chebyshev(w.getGridX(), w.getGridY(), a.getGridX(), a.getGridY()) <= 14)
                    def += CombatModel.weight(w);
            }
            for (Building t : ctx.model.me.towers) {
                if (!t.isDead() && MapAnalysis.chebyshev(t.getGridX(), t.getGridY(), a.getGridX(), a.getGridY()) <= 12)
                    def += 0.5f * CombatModel.towerValue(t);
            }
            float hp = a.getHitPoints() / (float) a.getTemplate().getMaxHitPoints();
            if ((hp < siege_hp && enemy > 0.5f * def) || enemy > def + 2f) {
                if (ctx.now >= sieged_until) {
                    sieged_since = ctx.now;
                    float e = enemy;
                    float d = def;
                    ctx.log(() -> String.format("armory siege: enemy %.1f vs defence %.1f, peons kept out", e, d));
                }
                sieged_until = ctx.now + 5f;
                // The rear point stays put (peons walking to a moving target never arrive) unless it comes under
                // threat itself; it is refreshed every 20 s.
                if (rear == null || ctx.now - rear_time > 20f
                        || (ctx.now - rear_time > 2f && ctx.resources.threatened(rear[0], rear[1], 14))) {
                    rear = computeRear(a, (int) (sx / n), (int) (sy / n));
                    rear_time = ctx.now;
                }
            }
        }
        if (ctx.now >= sieged_until)
            rear = null;
    }

    private int @Nullable [] computeRear(@NonNull Building a, int cx, int cy) {
        int bx = -1;
        int by = -1;
        int best = -1;
        List<Building> cands = new ArrayList<>(ctx.model.me.quarters);
        cands.addAll(ctx.model.me.towers);
        for (Building b : cands) {
            if (b.isDead() || b == a
                    || MapAnalysis.chebyshev(b.getGridX(), b.getGridY(), a.getGridX(), a.getGridY()) > 70
                    || ctx.resources.threatened(b.getGridX(), b.getGridY(), 14)
                    || ctx.map.frontness(b.getGridX(), b.getGridY()) > ctx.map.frontness(a.getGridX(), a.getGridY()))
                continue;
            int d = MapAnalysis.dist2(b.getGridX(), b.getGridY(), cx, cy);
            if (d > best) {
                best = d;
                bx = b.getGridX();
                by = b.getGridY();
            }
        }
        if (best < 0) {
            float dx = a.getGridX() - cx;
            float dy = a.getGridY() - cy;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 6f) {
                dx = a.getGridX() - ctx.map.enemy_x;
                dy = a.getGridY() - ctx.map.enemy_y;
                len = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
            }
            bx = ctx.map.clamp(Math.round(a.getGridX() + dx / len * 25f));
            by = ctx.map.clamp(Math.round(a.getGridY() + dy / len * 25f));
        }
        return ctx.map.nearestAccessible(bx, by);
    }

    // Stun dodge. The enemy chieftain's cast is visible (horn animation) and its facing too. The stun collects its
    // victims about 2.15 s after the cast starts (everything within 18 cells of the chieftain) and freezes those still
    // within 18 cells of a point about 1.3 cells ahead of it at about 3.77 s. Units in the outer ring walk out.

    private static final float CAST_WINDOW = 3.9f;

    /** An enemy stun in flight: caster, release point, when it was seen and when it has resolved. */
    private static final class Cast {
        final @NonNull Unit by;
        final float seen;
        final float x;
        final float y;
        final float until;
        /** Our own stun, cast earlier and reaching the caster, lands first: this one will be lost (dodgeSmart). */
        boolean doomed;
        /** Our warriors in reach when the stun picked its victims (dodgeSmart); null until then. */
        java.util.@Nullable Set<Unit> collected;

        Cast(@NonNull Unit by, float seen, float x, float y, float until) {
            this.by = by;
            this.seen = seen;
            this.x = x;
            this.y = y;
            this.until = until;
        }
    }

    private final List<Cast> casts = new ArrayList<>();
    /** Enemy chieftains in their cast animation at the last tick: a new cast starts when one enters it. */
    private final List<Unit> casting = new ArrayList<>();
    private float cast_seen = Float.NEGATIVE_INFINITY;
    private float own_cast_seen = Float.NEGATIVE_INFINITY;

    /** {x, y, until} of the pending enemy stun nearest to (x,y) that will land, or null. */
    float @Nullable [] pendingEnemyCastNear(int x, int y) {
        Cast best = null;
        float best_d = Float.MAX_VALUE;
        for (Cast c : casts) {
            if (ctx.now >= c.until || c.doomed)
                continue;
            float d = (c.x - x) * (c.x - x) + (c.y - y) * (c.y - y);
            if (d < best_d) {
                best_d = d;
                best = c;
            }
        }
        return best == null ? null : new float[]{best.x, best.y, best.until};
    }

    /** Game time an enemy chieftain last started a cast. */
    float lastEnemyCast() {
        return cast_seen;
    }

    private void dodgeTick() {
        Unit mc = ctx.owner.getChieftain();
        boolean mine = mc != null && !mc.isDead()
                && mc.getCurrentBehaviour() instanceof com.oddlabs.tt.model.behaviour.MagicBehaviour;
        if (!mine)
            own_cast_seen = Float.NEGATIVE_INFINITY;
        else if (own_cast_seen == Float.NEGATIVE_INFINITY)
            own_cast_seen = ctx.now;
        // A caster that dies or is (visibly) stunned before the release loses its stun: stop dodging it.
        boolean interrupted = false;
        for (java.util.Iterator<Cast> it = casts.iterator(); it.hasNext();) {
            Cast c = it.next();
            if (ctx.now >= c.until) {
                it.remove();
            } else if (c.by.isDead() || WorldModel.isStunned(c.by)) {
                it.remove();
                interrupted = true;
                ctx.log(() -> "enemy cast interrupted");
            } else if (c.doomed && !mine) {
                c.doomed = false;
            }
        }
        if (interrupted && dodge)
            ctx.orders.releaseHolds();
        casting.removeIf(u -> u.isDead() || WorldModel.visibleActivity(u) != WorldModel.Activity.MAGIC);
        for (Unit ec : ctx.model.enemy.chieftains) {
            if (casting.contains(ec) || WorldModel.visibleActivity(ec) != WorldModel.Activity.MAGIC)
                continue;
            casting.add(ec);
            Cast c = new Cast(ec, ctx.now, ec.getGridX() + ec.getDirectionX() * 1.3f,
                    ec.getGridY() + ec.getDirectionY() * 1.3f, ctx.now + CAST_WINDOW);
            // Our stun, started earlier (0.25 s covers the detection delay), collects this caster before its release.
            c.doomed = dodge_smart && mine && own_cast_seen <= ctx.now - 0.25f
                    && MapAnalysis.dist2(ec.getGridX(), ec.getGridY(), mc.getGridX(), mc.getGridY()) <= 16.5f * 16.5f;
            casts.add(c);
            cast_seen = ctx.now;
            float x = c.x;
            float y = c.y;
            boolean d = c.doomed;
            ctx.log(() -> String.format("enemy cast at %.0f,%.0f%s", x, y, d ? " (our stun lands first)" : ""));
        }
        if (!dodge)
            return;
        // Casts to dodge: the caster is still visibly casting, and (dodgeSmart) the stun is not lost or resolved.
        List<Cast> live = new ArrayList<>();
        for (Cast c : casts) {
            if (!casting.contains(c.by) || c.doomed)
                continue;
            if (dodge_smart) {
                if (ctx.now >= c.seen + 3.77f)
                    continue;
                if (c.collected == null && ctx.now >= c.seen + 1.9f) {
                    java.util.Set<Unit> set = new java.util.HashSet<>();
                    for (Unit u : ctx.model.me.warriors) {
                        if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), c.by.getGridX(), c.by.getGridY()) <= 19 * 19)
                            set.add(u);
                    }
                    c.collected = set;
                }
            }
            live.add(c);
        }
        if (live.isEmpty())
            return;
        int moved = 0;
        moved += dodgeUnits(ctx.model.me.warriors, live);
        if (dodge_peons)
            moved += dodgeUnits(ctx.model.me.peons, live);
        int n = moved;
        if (n > 0)
            ctx.log(() -> "DODGE moved " + n);
    }

    private int dodgeUnits(@NonNull List<Unit> units, @NonNull List<Cast> live) {
        int moved = 0;
        float min2 = dodge_min * dodge_min;
        float max2 = dodge_max * dodge_max;
        for (Unit u : units) {
            if (!ctx.orders.canOrder(u))
                continue;
            // The nearest pending release point decides (one in a 1v1).
            Cast c = live.get(0);
            float dx = u.getGridX() - c.x;
            float dy = u.getGridY() - c.y;
            float d2 = dx * dx + dy * dy;
            for (int i = 1; i < live.size(); i++) {
                Cast o = live.get(i);
                float ox = u.getGridX() - o.x;
                float oy = u.getGridY() - o.y;
                if (ox * ox + oy * oy < d2) {
                    c = o;
                    dx = ox;
                    dy = oy;
                    d2 = ox * ox + oy * oy;
                }
            }
            if (d2 < min2 || d2 > max2 || d2 < 1f)
                continue;
            // After the victims are picked only they can be frozen; others need not walk away.
            if (c.collected != null && !c.collected.contains(u))
                continue;
            float d = (float) Math.sqrt(d2);
            int tx = ctx.map.clamp(Math.round(c.x + dx / d * dodge_to));
            int ty = ctx.map.clamp(Math.round(c.y + dy / d * dodge_to));
            int[] acc = ctx.map.nearestAccessible(tx, ty);
            if (acc == null || nearMannedEnemyTower(acc[0], acc[1], 16))
                continue;
            ctx.orders.dodge(u, acc[0], acc[1], Math.min(ctx.now + dodge_hold, c.until));
            moved++;
        }
        return moved;
    }

    private boolean nearMannedEnemyTower(int x, int y, int r) {
        for (Building t : ctx.model.enemy.towers) {
            if (!t.isDead() && CombatModel.towerValue(t) > 0f && MapAnalysis.dist2(t.getGridX(), t.getGridY(), x,
                    y) < r * r)
                return true;
        }
        return false;
    }

    // Stun siege: an objective covered by several manned towers is not walked into. The army waits just outside the
    // towers' reach while the chieftain casts from the edge of it (a stunned garrison cannot shoot), then charges.
    // Once per objective: after the stun (or the 60 s wait) the fight goes on normally.

    private @Nullable Building siege_obj;
    private @Nullable Building siege_done_obj;
    private float siege_wait_since = -1f;
    private boolean siege_released;
    private boolean siege_holding;

    private void resetSiege() {
        siege_wait_since = -1f;
        siege_released = false;
        siege_holding = false;
    }

    /** Hold point {x, y, mode}: mode 0 = wait there, 1 = fight there without charging the towers; null = no hold. */
    private int @Nullable [] stunSiegeHold() {
        if (!stun_siege || main.isEmpty() || objective == null || objective.isDead()) {
            resetSiege();
            return null;
        }
        if (objective != siege_obj) {
            siege_obj = objective;
            resetSiege();
        }
        if (objective == siege_done_obj)
            return null;
        if (tootActive()) {
            if (siege_holding)
                siege_done_obj = objective;
            resetSiege();
            return null;
        }
        Unit a = anchor(main);
        Unit chief = ctx.owner.getChieftain();
        if (chief == null || chief.isDead() || chief.getHitPoints() < 25
                || chief.getMagicProgress(com.oddlabs.tt.model.RacesResources.INDEX_MAGIC_STUN) < 0.6f
                || MapAnalysis.chebyshev(chief.getGridX(), chief.getGridY(), a.getGridX(), a.getGridY()) > 30) {
            resetSiege();
            return null;
        }
        List<Building> cover = new ArrayList<>();
        List<Building> reach = new ArrayList<>();
        for (Building t : ctx.model.enemy.towers) {
            if (t.isDead() || CombatModel.towerValue(t) <= 0f)
                continue;
            int d = MapAnalysis.chebyshev(t.getGridX(), t.getGridY(), obj_x, obj_y);
            if (d <= 20)
                cover.add(t);
            if (d <= 40)
                reach.add(t);
        }
        if (cover.size() < 2) {
            resetSiege();
            return null;
        }
        if (!siege_holding) {
            // Only an army still outside the cover waits; one already fighting under the towers keeps fighting.
            for (Building t : cover) {
                if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), a.getGridX(), a.getGridY()) < 16 * 16)
                    return null;
            }
        }
        int[] p = holdPoint(a, reach);
        if (p == null)
            return null;
        // Far from the hold point: keep marching; the wait starts on arrival.
        if (!siege_holding && MapAnalysis.chebyshev(a.getGridX(), a.getGridY(), p[0], p[1]) > 30)
            return null;
        if (!siege_holding) {
            siege_holding = true;
            int n = cover.size();
            ctx.log(() -> "stun siege: holding outside " + n + " towers");
        }
        if (siege_wait_since < 0f && MapAnalysis.chebyshev(a.getGridX(), a.getGridY(), p[0], p[1]) <= 10)
            siege_wait_since = ctx.now;
        if (siege_wait_since >= 0f && ctx.now - siege_wait_since > 60f) {
            siege_done_obj = objective;
            resetSiege();
            return null;
        }
        // Enemy warriors on the army: fight them where we stand (hysteresis 12/16), not under the towers.
        boolean close = enemyWarriorsNear(main, siege_released ? 16 : 12);
        siege_released = close;
        return new int[]{p[0], p[1], close ? 1 : 0};
    }

    /** The first reachable point from the objective back toward the army that no manned tower within 40 reaches. */
    private int @Nullable [] holdPoint(@NonNull Unit a, @NonNull List<Building> towers) {
        float dx = a.getGridX() - obj_x;
        float dy = a.getGridY() - obj_y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f)
            return null;
        for (int step = 0; step <= 44; step += 2) {
            int px = ctx.map.clamp(Math.round(obj_x + dx / len * step));
            int py = ctx.map.clamp(Math.round(obj_y + dy / len * step));
            int[] acc = ctx.map.nearestAccessible(px, py);
            if (acc == null)
                continue;
            if (!clearOfTowers(acc[0], acc[1], towers))
                continue;
            int[] field = route;
            if (field != null && field[acc[1] * ctx.map.n + acc[0]] == MapAnalysis.UNREACHABLE)
                continue;
            return acc;
        }
        return null;
    }

    private static boolean clearOfTowers(int x, int y, @NonNull List<Building> towers) {
        for (Building t : towers) {
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) < 18 * 18)
                return false;
        }
        return true;
    }

    private void holdAt(int @NonNull [] p) {
        int tolerance = 3 + Math.round(1.2f * (float) Math.sqrt(main.size()));
        List<Unit> go = new ArrayList<>();
        for (Unit w : main) {
            if (MapAnalysis.chebyshev(w.getGridX(), w.getGridY(), p[0], p[1]) > tolerance
                    && !ctx.orders.recently(w, Orders.Kind.ATTACK_MOVE, null, p[0], p[1], 3f))
                go.add(w);
        }
        if (!go.isEmpty())
            ctx.orders.group(go, p[0], p[1], true);
    }

    /** True while the army waits outside tower reach for our stun (the chieftain may walk up to cast). */
    boolean stunSieging() {
        return siege_holding;
    }

    /** Engage value of the enemy chieftain: killing it ends its stuns for minutes. */
    private final float chief_target_value;

    private float value(@NonNull Unit e) {
        return switch (WorldModel.kindOf(e)) {
            case RUBBER -> 1.7f;
            case IRON -> 1.0f;
            case ROCK -> 0.55f;
            case CHIEFTAIN -> chief_target_value;
            case PEON -> {
                WorldModel.Activity a = WorldModel.visibleActivity(e);
                if (a == WorldModel.Activity.HUNT)
                    yield 0.8f;
                if (a == WorldModel.Activity.BUILD)
                    yield 0.9f;
                yield 0.3f;
            }
        };
    }

    /**
     * Assigns each ready warrior the enemy in range with the best value x hit chance x survival (spread fire,
     * no overkill). Warriors with nothing in range attack-move toward the fight.
     */
    private void engage(@NonNull List<Unit> squad, int fx, int fy, boolean siege, int reach) {
        if (squad.isEmpty())
            return;
        int minx = Integer.MAX_VALUE;
        int miny = Integer.MAX_VALUE;
        int maxx = Integer.MIN_VALUE;
        int maxy = Integer.MIN_VALUE;
        for (Unit w : squad) {
            minx = Math.min(minx, w.getGridX());
            miny = Math.min(miny, w.getGridY());
            maxx = Math.max(maxx, w.getGridX());
            maxy = Math.max(maxy, w.getGridY());
        }
        minx -= reach;
        miny -= reach;
        maxx += reach;
        maxy += reach;
        List<Unit> enemies = new ArrayList<>();
        for (Unit e : ctx.model.enemy.units) {
            int x = e.getGridX();
            int y = e.getGridY();
            if (x >= minx && x <= maxx && y >= miny && y <= maxy)
                enemies.add(e);
        }
        List<Building> buildings = new ArrayList<>();
        for (Building b : ctx.model.enemy.buildings) {
            int x = b.getGridX();
            int y = b.getGridY();
            if (!b.isDead() && x >= minx - 6 && x <= maxx + 6 && y >= miny - 6 && y <= maxy + 6)
                buildings.add(b);
        }
        if (enemies.isEmpty() && buildings.isEmpty()) {
            List<Unit> go = new ArrayList<>();
            for (Unit w : squad) {
                if (!ctx.orders.recently(w, Orders.Kind.ATTACK_MOVE, null, fx, fy, 4f)
                        && MapAnalysis.chebyshev(w.getGridX(), w.getGridY(), fx, fy) > 4)
                    go.add(w);
            }
            if (!go.isEmpty())
                ctx.orders.group(go, fx, fy, true);
            return;
        }

        if (!micro) {
            // Baseline for A/B tests: let the engine's auto-targeting fight (attack-move into the enemy).
            int nx = fx;
            int ny = fy;
            if (!enemies.isEmpty()) {
                nx = enemies.get(0).getGridX();
                ny = enemies.get(0).getGridY();
            }
            List<Unit> go = new ArrayList<>();
            for (Unit w : squad) {
                if (!(w.getCurrentController() instanceof HuntController) && !ctx.orders.recently(w, 3f))
                    go.add(w);
            }
            if (!go.isEmpty())
                ctx.orders.group(go, nx, ny, true);
            return;
        }
        Map<Unit, Float> survive = new HashMap<>();
        for (Unit w : squad) {
            if (w.getCurrentController() instanceof HuntController hc && hc.getTarget() instanceof Unit t
                    && !t.isDead()) {
                float p = Math.min(0.99f, CombatModel.hitChance(w, t, ctx.map));
                survive.merge(t, 1f - p, (a, b) -> a * b);
            }
        }
        // A tower whose garrison is stunned cannot shoot: the nearest warriors tear it down while it lasts.
        java.util.Set<Unit> on_tower = new java.util.HashSet<>();
        if (stun_tower_focus) {
            for (Building b : buildings) {
                if (!b.isComplete() || !b.getAbilities().hasAbilities(Abilities.ATTACK))
                    continue;
                Unit g = WorldModel.garrisonOf(b);
                if (g == null || !WorldModel.isStunned(g))
                    continue;
                List<Unit> cands = new ArrayList<>();
                for (Unit w : squad) {
                    if (!on_tower.contains(w) && ctx.orders.canOrder(w)
                            && MapAnalysis.chebyshev(w.getGridX(), w.getGridY(), b.getGridX(), b.getGridY()) <= 14)
                        cands.add(w);
                }
                cands.sort(java.util.Comparator.comparingInt(w -> MapAnalysis.dist2(w.getGridX(), w.getGridY(),
                        b.getGridX(), b.getGridY())));
                for (int i = 0; i < Math.min(6, cands.size()); i++) {
                    Unit w = cands.get(i);
                    on_tower.add(w);
                    Selectable<?> cur = w.getCurrentController() instanceof HuntController hc ? hc.getTarget() : null;
                    if (cur != b && !(w.getCurrentBehaviour() instanceof AttackBehaviour))
                        ctx.orders.attack(w, b);
                }
            }
        }
        int nearest_x = fx;
        int nearest_y = fy;
        for (Unit w : squad) {
            if (!ctx.orders.canOrder(w) || on_tower.contains(w))
                continue;
            Selectable<?> current = w.getCurrentController() instanceof HuntController hc ? hc.getTarget() : null;
            boolean mid_cycle = w.getCurrentBehaviour() instanceof AttackBehaviour;
            if (mid_cycle && current != null && !current.isDead() && current instanceof Unit)
                continue;
            Unit best = null;
            float best_score = 0f;
            float best_p = 0f;
            int wx = w.getGridX();
            int wy = w.getGridY();
            int near_d = Integer.MAX_VALUE;
            float own = current instanceof Unit cu && !cu.isDead() ? Math.min(0.99f, CombatModel.hitChance(w, cu,
                    ctx.map)) : 0f;
            for (Unit e : enemies) {
                int d2 = MapAnalysis.dist2(wx, wy, e.getGridX(), e.getGridY());
                if (d2 < near_d) {
                    near_d = d2;
                    nearest_x = e.getGridX();
                    nearest_y = e.getGridY();
                }
                if (d2 > (UNIT_RANGE + 1.5f) * (UNIT_RANGE + 1.5f))
                    continue;
                float p = CombatModel.hitChance(w, e, ctx.map);
                Float s0 = survive.get(e);
                float s = s0 != null ? s0 : 1f;
                if (e == current)
                    s = s / (1f - own);
                float score = value(e) * p * Math.max(s,
                        0.05f) * (s < 0.3f ? 0.2f : 1f) * (d2 <= UNIT_RANGE * UNIT_RANGE ? 1f : 0.75f) * (e == current ? 1.1f : 1f);
                if (score > best_score) {
                    best_score = score;
                    best = e;
                    best_p = p;
                }
            }
            if (best != null) {
                if (best != current) {
                    ctx.orders.attack(w, best);
                    survive.merge(best, 1f - best_p, (a, b) -> a * b);
                }
                continue;
            }
            // No unit in range: buildings (towers first, then the objective) when sieging or when close.
            Building target = null;
            float target_score = 0f;
            for (Building b : buildings) {
                int d2 = MapAnalysis.dist2(wx, wy, b.getGridX(), b.getGridY());
                boolean tower = b.getAbilities().hasAbilities(Abilities.ATTACK);
                float range = tower ? TOWER_TARGET_RANGE : 11.76f;
                boolean in_range = d2 <= range * range;
                if (!in_range && !siege)
                    continue;
                float score = (tower ? 3f : b == objective ? 2f : 1f) / (1f + (float) Math.sqrt(d2) / 10f);
                if (!b.isComplete())
                    score *= 2f;
                if (score > target_score) {
                    target_score = score;
                    target = b;
                }
            }
            if (target != null) {
                if (current != target)
                    ctx.orders.attack(w, target);
                continue;
            }
            if (current == null && !ctx.orders.recently(w, 2f)) {
                if (!enemies.isEmpty()) {
                    ctx.orders.attackMove(w, nearest_x, nearest_y);
                } else {
                    Building nearest_tower = null;
                    int nt = Integer.MAX_VALUE;
                    for (Building b : buildings) {
                        if (!b.getAbilities().hasAbilities(Abilities.ATTACK))
                            continue;
                        int d2 = MapAnalysis.dist2(wx, wy, b.getGridX(), b.getGridY());
                        if (d2 < nt) {
                            nt = d2;
                            nearest_tower = b;
                        }
                    }
                    if (nearest_tower != null && (siege || nt <= 20 * 20))
                        ctx.orders.attack(w, nearest_tower);
                    else if (MapAnalysis.chebyshev(wx, wy, fx, fy) > 4)
                        ctx.orders.attackMove(w, fx, fy);
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // Raids: a small squad hits enemy gatherers and builders far from enemy warriors and towers.

    private enum RaidState {
        NONE,
        MOVE,
        STRIKE,
        RETURN
    }

    private RaidState raid_state = RaidState.NONE;
    private int raid_x;
    private int raid_y;
    private float raid_since;
    private float raid_cooldown;

    private void setRaid(@NonNull RaidState s) {
        raid_state = s;
        raid_since = ctx.now;
        int n = raid.size();
        ctx.log(() -> "raid " + s + " size=" + n + " at " + raid_x + "," + raid_y);
    }

    private void raidTick() {
        if (!raid_enabled)
            return;
        if (raid_state != RaidState.NONE && raid.isEmpty()) {
            raid_state = RaidState.NONE;
            raid_cooldown = ctx.now + 30f;
            return;
        }
        switch (raid_state) {
            case NONE -> startRaid();
            case MOVE -> {
                Unit a = anchor(raid);
                if (raidDanger(a.getGridX(), a.getGridY())) {
                    returnRaid();
                } else if (MapAnalysis.chebyshev(a.getGridX(), a.getGridY(), raid_x, raid_y) <= 12) {
                    setRaid(RaidState.STRIKE);
                } else if (ctx.now - raid_since > 90f) {
                    returnRaid();
                } else if (!ctx.orders.recently(raid.get(0), 5f)) {
                    ctx.orders.group(raid, raid_x, raid_y, false);
                }
            }
            case STRIKE -> {
                Unit a = anchor(raid);
                if (raidDanger(a.getGridX(), a.getGridY())) {
                    returnRaid();
                    return;
                }
                int[] next = raidTarget(a.getGridX(), a.getGridY(), 20);
                if (next == null) {
                    returnRaid();
                } else {
                    raid_x = next[0];
                    raid_y = next[1];
                }
            }
            case RETURN -> {
                resendStragglers(raid, raid_since);
                Unit a = anchor(raid);
                if (MapAnalysis.chebyshev(a.getGridX(), a.getGridY(), gather[0], gather[1]) <= 10
                        || ctx.now - raid_since > 90f) {
                    for (Unit w : raid)
                        squad_of.put(w, HOME);
                    raid.clear();
                    raid_state = RaidState.NONE;
                    raid_cooldown = ctx.now + 30f;
                }
            }
        }
    }

    /**
     * Squad members that could not take a one-shot order home (stunned at the time; stunned units are never
     * ordered) and have had no order since: send them now.
     */
    private void resendStragglers(@NonNull List<Unit> squad, float since) {
        List<Unit> late = new ArrayList<>();
        for (Unit w : squad) {
            if (!ctx.orders.canOrder(w))
                continue;
            Orders.Last last = ctx.orders.lastOrder(w);
            if (last == null || last.time() < since || last.kind() == Orders.Kind.DODGE)
                late.add(w);
        }
        if (!late.isEmpty())
            ctx.orders.group(late, gather[0], gather[1], false);
    }

    private void returnRaid() {
        setRaid(RaidState.RETURN);
        ctx.orders.group(raid, gather[0], gather[1], false);
    }

    private void startRaid() {
        if (ctx.now < raid_cooldown || defending || state != State.GATHER)
            return;
        List<Unit> pool = main;
        if (pool.size() < raid_size + 4)
            return;
        int[] t = raidTarget(gather[0], gather[1], 170);
        if (t == null)
            return;
        raid_x = t[0];
        raid_y = t[1];
        // Iron and rubber warriors nearest the target; the rest of the army stays home.
        List<Unit> sorted = new ArrayList<>(pool);
        sorted.sort((x, y) -> Integer.compare(MapAnalysis.dist2(x.getGridX(), x.getGridY(), raid_x, raid_y),
                MapAnalysis.dist2(y.getGridX(), y.getGridY(), raid_x, raid_y)));
        for (Unit w : sorted) {
            if (raid.size() >= raid_size)
                break;
            if (WorldModel.kindOf(w) == WorldModel.Kind.ROCK)
                continue;
            squad_of.put(w, RAID);
            raid.add(w);
            main.remove(w);
        }
        if (raid.size() < Math.max(2, raid_size - 1)) {
            for (Unit w : raid)
                squad_of.put(w, MAIN);
            main.addAll(raid);
            raid.clear();
            return;
        }
        setRaid(RaidState.MOVE);
        ctx.orders.group(raid, raid_x, raid_y, false);
    }

    /** Enemy warriors (or a manned tower) that could beat the raid are near. */
    private boolean raidDanger(int x, int y) {
        float ours = CombatModel.sumWeights(raid);
        float theirs = 0f;
        for (Unit e : ctx.model.enemy.warriors) {
            if (MapAnalysis.chebyshev(e.getGridX(), e.getGridY(), x, y) <= 18
                    && !WorldModel.isStunned(e))
                theirs += CombatModel.weight(e);
        }
        Unit chief = ctx.model.enemy.chieftain;
        if (chief != null && MapAnalysis.chebyshev(chief.getGridX(), chief.getGridY(), x, y) <= 18)
            theirs += 1.5f;
        for (Building t : ctx.model.enemy.towers) {
            if (WorldModel.garrisonOf(t) != null && MapAnalysis.chebyshev(t.getGridX(), t.getGridY(), x, y) <= 17)
                return true;
        }
        return theirs >= 0.7f * ours;
    }

    /**
     * The best raid spot within reach: a clump of enemy peons working away from enemy warriors and towers.
     * Value counts peons within 8 cells, discounted by distance.
     */
    private int @Nullable [] raidTarget(int fx, int fy, int reach) {
        int best_x = -1;
        int best_y = -1;
        float best_v = 1.5f;
        for (Unit e : ctx.model.enemy.peons) {
            int x = e.getGridX();
            int y = e.getGridY();
            if (MapAnalysis.chebyshev(x, y, fx, fy) > reach)
                continue;
            boolean guarded = false;
            for (Unit w : ctx.model.enemy.warriors) {
                if (MapAnalysis.chebyshev(w.getGridX(), w.getGridY(), x, y) <= 22) {
                    guarded = true;
                    break;
                }
            }
            if (guarded)
                continue;
            for (Building t : ctx.model.enemy.towers) {
                if (MapAnalysis.chebyshev(t.getGridX(), t.getGridY(), x, y) <= 19) {
                    guarded = true;
                    break;
                }
            }
            if (guarded)
                continue;
            int n = 0;
            for (Unit o : ctx.model.enemy.peons) {
                if (MapAnalysis.chebyshev(o.getGridX(), o.getGridY(), x, y) <= 8)
                    n++;
            }
            float v = n - (float) Math.sqrt(MapAnalysis.dist2(x, y, fx, fy)) / 60f;
            if (v > best_v) {
                best_v = v;
                best_x = x;
                best_y = y;
            }
        }
        return best_x < 0 ? null : new int[]{best_x, best_y};
    }

}
