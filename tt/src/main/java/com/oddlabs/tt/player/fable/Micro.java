package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackBehaviour;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RockSpearWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberSpearWeapon;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Engagement micro: every warrior that is not mid-throw gets the enemy in reach with the best
 * {@code value x hit chance x survival} score, where survival is the chance the target is still alive after the
 * throws already committed to it (spread fire, no overkill). All units are 1 hp, so a second throw at a target
 * that the first one hits is wasted, and the engine's own auto-targeting sends a whole clump at the nearest
 * enemy. Warriors with nothing in reach attack-move toward the fight; during a siege they throw at towers.
 *
 * <p>Only enemies within throw range (+1.5 cells) are considered, so the clump does not dissolve into chases.
 * Iterates over lists in deterministic order; the survival map is keyed by identity but never iterated.
 */
final class Micro {
    /** A throw reaches this many cells (engine scan radius 8; range 6 m + target size). */
    static final float RANGE_CELLS = 7.9f;
    private static final float TERRAIN_BONUS_PER_HEIGHT = 0.25f / 20f;
    private static final float CHIEF_KILL_VALUE = 25f;

    private final @NonNull Player player;
    private final @NonNull HeightMap heights;
    private final @NonNull Orders orders;
    private final @NonNull Jobs jobs;
    private final @NonNull Params params;

    private final Map<Unit, Float> survive = new HashMap<>();
    private final List<Unit> enemies = new ArrayList<>();
    private final List<LandBuilding> buildings = new ArrayList<>();

    /** Counters of the last {@link #fight} call, for logging. */
    int retargeted;
    int moved;
    int on_buildings;
    int in_reach;

    Micro(@NonNull Player player, @NonNull Orders orders, @NonNull Jobs jobs, @NonNull Params params) {
        this.player = player;
        this.heights = player.getWorld().getHeightMap();
        this.orders = orders;
        this.jobs = jobs;
        this.params = params;
    }

    /** Hit chance of one throw of {@code a} at {@code t}: (base + terrain + difficulty bonus) x (1 - defence). */
    float hitChance(@NonNull Unit a, @NonNull Selectable<?> t) {
        float base;
        if (a.getAbilities().hasAbilities(Abilities.MAGIC))
            base = 0.75f;
        else if (a.getAbilities().hasAbilities(Abilities.BUILD))
            base = 0.2f;
        else {
            Class<?> type = a.getWeaponFactory().getType();
            if (type == RockAxeWeapon.class || type == RockSpearWeapon.class)
                base = 0.5f;
            else if (type == RubberAxeWeapon.class || type == RubberSpearWeapon.class)
                base = 0.95f;
            else
                base = 0.75f;
        }
        float dz = heights.getNearestHeight(a.getPositionX(), a.getPositionY()) - heights.getNearestHeight(
                t.getPositionX(), t.getPositionY());
        float terrain = Math.clamp(dz * TERRAIN_BONUS_PER_HEIGHT, -0.25f, 0.25f);
        float p = (base + terrain + player.getHitBonus()) * (1f - t.getDefenseChance());
        return Math.clamp(p, 0f, 1f);
    }

    /** Value of killing an enemy unit, per throw that hits. */
    private static float value(@NonNull Unit e) {
        if (e.getAbilities().hasAbilities(Abilities.MAGIC)) {
            // 2 damage per iron hit against 60 hp: a wounded chieftain becomes the best target on the field
            int hits = Math.max(1, (e.getHitPoints() + 1) / 2);
            return Math.min(6f, CHIEF_KILL_VALUE / hits);
        }
        if (e.getAbilities().hasAbilities(Abilities.BUILD)) {
            var c = e.getPrimaryController();
            if (c instanceof RepairController || c instanceof PlaceBuildingController)
                return 0.9f;
            return 0.3f;
        }
        return Intel.unitValue(e);
    }

    private static @Nullable Selectable<?> currentTarget(@NonNull Unit u) {
        return u.getCurrentController() instanceof HuntController hc ? hc.getTarget() : null;
    }

    /**
     * Run one micro pass over {@code squad} fighting around ({@code fx},{@code fy}).
     *
     * @param siege     when true units without a unit target throw at buildings even beyond reach (they walk)
     * @param advance   when false units with nothing in reach hold where they are instead of closing in
     * @param objective the building the assault is about, preferred among non-tower buildings
     * @return the number of squad units that had an enemy unit in reach
     */
    int fight(float now, @NonNull Collection<Unit> squad, @NonNull Intel intel, int fx, int fy, boolean siege,
            boolean advance, @Nullable Selectable<?> objective, int tag) {
        retargeted = 0;
        moved = 0;
        on_buildings = 0;
        in_reach = 0;
        if (squad.isEmpty())
            return 0;
        int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE, maxx = Integer.MIN_VALUE, maxy = Integer.MIN_VALUE;
        for (Unit w : squad) {
            if (w.isDead())
                continue;
            minx = Math.min(minx, w.getGridX());
            miny = Math.min(miny, w.getGridY());
            maxx = Math.max(maxx, w.getGridX());
            maxy = Math.max(maxy, w.getGridY());
        }
        if (minx == Integer.MAX_VALUE)
            return 0;
        int reach = (int) RANGE_CELLS + 4;
        minx -= reach;
        miny -= reach;
        maxx += reach;
        maxy += reach;
        enemies.clear();
        collect(intel.warriors, minx, miny, maxx, maxy);
        collect(intel.chieftains, minx, miny, maxx, maxy);
        collect(intel.peons, minx, miny, maxx, maxy);
        buildings.clear();
        collectBuildings(intel.towers, minx - 8, miny - 8, maxx + 8, maxy + 8);
        collectBuildings(intel.armories, minx - 8, miny - 8, maxx + 8, maxy + 8);
        collectBuildings(intel.quarters, minx - 8, miny - 8, maxx + 8, maxy + 8);
        collectBuildings(intel.construction_sites, minx - 8, miny - 8, maxx + 8, maxy + 8);
        if (enemies.isEmpty() && buildings.isEmpty()) {
            List<Unit> go = new ArrayList<>();
            int hold2 = advance ? 16 : 64;
            for (Unit w : squad) {
                if (!orders.usable(w) || Chieftain.isStunned(w))
                    continue;
                Jobs.Job j = jobs.get(w);
                if (j != null && j.kind == Jobs.Kind.MOVE && now - j.issued_at < 5f)
                    continue; // an explicit move (scatter, retreat) is left alone
                boolean same = j != null && j.kind == Jobs.Kind.ATTACK_MOVE && now - j.issued_at < 4f
                        && BasePlan.dist2(j.gx, j.gy, fx, fy) <= 16;
                if (!same && BasePlan.dist2(w.getGridX(), w.getGridY(), fx, fy) > hold2)
                    go.add(w);
            }
            if (!go.isEmpty()) {
                orders.attackMove(go, fx, fy);
                for (Unit w : go)
                    jobs.set(w, Jobs.Kind.ATTACK_MOVE, fx, fy, null, null, now, tag);
                moved = go.size();
            }
            return 0;
        }
        // throws already committed (units mid-cycle or walking at a target)
        survive.clear();
        for (Unit w : squad) {
            if (w.isDead())
                continue;
            if (currentTarget(w) instanceof Unit t && !t.isDead()) {
                float p = hitChance(w, t);
                survive.merge(t, 1f - p, (a, b) -> a * b);
            }
        }
        float r2_near = (RANGE_CELLS + 1.5f) * (RANGE_CELLS + 1.5f);
        float r2_in = RANGE_CELLS * RANGE_CELLS;
        for (Unit w : squad) {
            if (!orders.usable(w) || Chieftain.isStunned(w) || Chieftain.isFrozen(w))
                continue;
            if (w.getCurrentBehaviour() instanceof AttackBehaviour)
                continue; // mid-throw: the weapon is committed
            Jobs.Job job = jobs.get(w);
            if (job != null && job.kind == Jobs.Kind.MOVE && now - job.issued_at < 5f)
                continue; // an explicit move (scatter, retreat) is left alone
            Selectable<?> current = currentTarget(w);
            if (current != null && current.isDead())
                current = null;
            int wx = w.getGridX();
            int wy = w.getGridY();
            Unit best = null;
            float best_score = 0f;
            float best_p = 0f;
            float cur_score = 0f;
            int near_d2 = Integer.MAX_VALUE;
            int near_x = fx;
            int near_y = fy;
            float own = current instanceof Unit cu ? hitChance(w, cu) : 0f;
            for (Unit e : enemies) {
                int d2 = BasePlan.dist2(wx, wy, e.getGridX(), e.getGridY());
                if (d2 < near_d2) {
                    near_d2 = d2;
                    near_x = e.getGridX();
                    near_y = e.getGridY();
                }
                if (d2 > r2_near)
                    continue;
                float p = hitChance(w, e);
                Float s0 = survive.get(e);
                float s = s0 != null ? s0 : 1f;
                if (e == current && own < 0.999f)
                    s = s / (1f - own);
                float score = value(e) * p * Math.max(s, 0.05f) * (s < 0.3f ? 0.2f : 1f) * (d2 <= r2_in ? 1f : 0.75f);
                if (e == current)
                    cur_score = score;
                if (score > best_score) {
                    best_score = score;
                    best = e;
                    best_p = p;
                }
            }
            if (best != null) {
                in_reach++;
                // keep the current target unless the alternative is clearly better (orders cost pathfinding)
                if (best != current && (current == null || best_score > 1.25f * cur_score)) {
                    orders.attack(w, best);
                    jobs.set(w, Jobs.Kind.HUNT, best.getGridX(), best.getGridY(), best, null, now, tag);
                    survive.merge(best, 1f - best_p, (a, b) -> a * b);
                    retargeted++;
                }
                continue;
            }
            if (current instanceof Unit)
                continue; // hunting something just out of reach: let it close in
            // no enemy unit in reach: buildings (towers first, then the objective) when sieging or when close
            LandBuilding target = null;
            float target_score = 0f;
            for (LandBuilding b : buildings) {
                int d2 = BasePlan.dist2(wx, wy, b.getGridX(), b.getGridY());
                boolean tower = b.getAbilities().hasAbilities(Abilities.ATTACK);
                float range = tower ? 12f : 11f;
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
                if (current != target) {
                    orders.attack(w, target);
                    jobs.set(w, Jobs.Kind.HUNT, target.getGridX(), target.getGridY(), target, null, now, tag);
                    on_buildings++;
                }
                continue;
            }
            if (current == null) {
                // re-aim only when the last order is stale or the unit stopped; each order costs a path search
                boolean stale = job == null || now - job.issued_at >= 4f;
                boolean idle = Roster.activity(w) == Roster.Activity.IDLE && (job == null
                        || now - job.issued_at >= 1.5f);
                if (!stale && !idle)
                    continue;
                if (job != null && job.kind == Jobs.Kind.ATTACK_MOVE && !idle
                        && BasePlan.dist2(job.gx, job.gy, near_x, near_y) <= 36)
                    continue;
                if (advance && !enemies.isEmpty()) {
                    orders.attackMove(w, near_x, near_y);
                    jobs.set(w, Jobs.Kind.ATTACK_MOVE, near_x, near_y, null, null, now, tag);
                    moved++;
                } else if (BasePlan.dist2(wx, wy, fx, fy) > (advance ? 16 : 64)) {
                    orders.attackMove(w, fx, fy);
                    jobs.set(w, Jobs.Kind.ATTACK_MOVE, fx, fy, null, null, now, tag);
                    moved++;
                }
            }
        }
        return in_reach;
    }

    private void collect(@NonNull List<Unit> from, int minx, int miny, int maxx, int maxy) {
        for (Unit e : from) {
            if (e.isDead() || e.isMounted())
                continue;
            int x = e.getGridX();
            int y = e.getGridY();
            if (x >= minx && x <= maxx && y >= miny && y <= maxy)
                enemies.add(e);
        }
    }

    private void collectBuildings(@NonNull List<LandBuilding> from, int minx, int miny, int maxx, int maxy) {
        for (LandBuilding b : from) {
            if (b.isDead())
                continue;
            int x = b.getGridX();
            int y = b.getGridY();
            if (x >= minx && x <= maxx && y >= miny && y <= maxy)
                buildings.add(b);
        }
    }

    @Override
    public @NonNull String toString() {
        return "micro reach=" + in_reach + " retarget=" + retargeted + " bld=" + on_buildings + " move=" + moved;
    }
}
