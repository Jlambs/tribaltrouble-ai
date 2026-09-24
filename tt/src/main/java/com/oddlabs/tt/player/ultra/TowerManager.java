package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.IdleController;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Towers: slots in a ring around the armory oriented at the enemy, build requests as threats grow, garrisons
 * (rubber > iron > rock), focus-fire micro that skips the auto-scan delay and prefers peons demolishing our
 * towers, and evacuation of doomed towers.
 */
final class TowerManager {
    private static final class Slot {
        final @NonNull String name;
        final float angle;
        final float radius;
        BuildManager.@Nullable Task task;

        Slot(@NonNull String name, float angle_degrees, float radius) {
            this.name = name;
            this.angle = (float) StrictMath.toRadians(angle_degrees);
            this.radius = radius;
        }
    }

    private final @NonNull Context ctx;
    private final List<Slot> slots = new ArrayList<>();
    private final Map<Building, Unit> pending_garrison = new java.util.LinkedHashMap<>();
    private final Map<Building, Float> last_order = new HashMap<>();
    /** Towers we just evacuated: no new garrison until this time (or until repaired). */
    private final Map<Building, Float> evacuated_until = new HashMap<>();
    private final Map<Building, Unit> repairer = new HashMap<>();
    private final float c1_time;
    private final float c2_time;
    private final int tower_max;
    private final int tower_builders;
    /** Extra towers answer only enemy warriors within this many cells of the armory (0 = the whole enemy army). */
    private final int tower_threat_r;
    /** Towers by a time schedule (see scheduledTowers), at least 15 s between tower requests. */
    private final boolean tower_schedule;
    /** At most this many towers (0 = no cap) before the fourth quarters stands or towerEarlyTime, unless raided. */
    private final int tower_early_cap;
    private final float tower_early_time;
    /** Standing quarters that lift the early tower cap before towerEarlyTime. */
    private final int tower_early_quarters;
    private float last_tower_request = Float.NEGATIVE_INFINITY;
    /**
     * Extra tower slots filled at the unit cap (0 = off), from this game time, keeping this many building slots free.
     */
    private final int late_towers;
    private final float late_time;
    private final int late_reserve;
    /** Open tower tasks at a time without a raid on the base (0 = no limit), and with one. */
    private final int tower_open_max;
    /** Enemy warriors on the base (or an economy emergency) at the last plan tick. */
    private boolean hot_now;
    private final int tower_open_hot;
    /** Tower priority before the fourth quarters stands (0 = always the high priority 800). */
    private final int tower_prio_low;

    TowerManager(@NonNull Context ctx) {
        this.ctx = ctx;
        c1_time = ctx.strategy.f("c1Time");
        c2_time = ctx.strategy.f("c2Time");
        tower_max = ctx.strategy.i("towerMax");
        tower_builders = ctx.strategy.i("towerBuilders");
        tower_threat_r = ctx.strategy.i("towerThreatR");
        tower_schedule = ctx.strategy.b("towerSchedule");
        tower_early_cap = ctx.strategy.i("towerEarlyCap");
        tower_early_time = ctx.strategy.f("towerEarlyTime");
        tower_early_quarters = ctx.strategy.i("towerEarlyQuarters");
        tower_open_max = ctx.strategy.i("towerOpenMax");
        tower_open_hot = ctx.strategy.i("towerOpenHot");
        tower_prio_low = ctx.strategy.i("towerPrioLow");
        float r = ctx.strategy.f("towerRadius");
        slots.add(new Slot("C1", 0f, r));
        slots.add(new Slot("C2", 45f, r + 2));
        slots.add(new Slot("C3", -45f, r + 2));
        slots.add(new Slot("C4", 110f, r));
        slots.add(new Slot("C5", -110f, r));
        slots.add(new Slot("C6", 180f, r));
        slots.add(new Slot("C7", 25f, r + 8));
        slots.add(new Slot("C8", -25f, r + 8));
        late_towers = Math.max(0, Math.min(4, ctx.strategy.i("lateTowers")));
        late_time = ctx.strategy.f("lateTowerTime");
        late_reserve = ctx.strategy.i("lateReserve");
        // Extra slots for the late game, only used once the army is at the unit cap (see desiredTowers).
        float[][] late = {{70f, r + 8}, {-70f, r + 8}, {0f, r + 16}, {145f, r + 6}};
        for (int i = 0; i < late_towers; i++)
            slots.add(new Slot("C" + (9 + i), late[i][0], late[i][1]));
    }

    /** Quarters may use a slot only while enough slots stay free for the towers we still want. */
    boolean hot() {
        return hot_now;
    }

    boolean slotAvailableForQuarters() {
        int used = ctx.owner.getBuildingCountContainer().getNumSupplies() + ctx.build.pendingSlots();
        int reserve = Math.max(0, Math.min(4, tower_max) - ctx.model.me.towers.size());
        return used + 1 <= ctx.world.getMaxBuildingCount() - reserve;
    }

    private int desiredTowers() {
        if (tower_schedule)
            return scheduledTowers();
        int enemy_warriors = ctx.model.enemy.warriors.size();
        int n = 0;
        if (ctx.now >= c1_time || enemy_warriors >= 2)
            n = 1;
        if (ctx.now >= c2_time || enemy_warriors >= 4)
            n = 2;
        if (ctx.now >= c2_time + 120f || enemy_warriors >= 10)
            n = 3;
        if (enemy_warriors >= 16 || ctx.now >= c2_time + 240f)
            n = 4;
        float ours = CombatModel.sumWeights(ctx.model.me.warriors);
        float theirs = CombatModel.sumWeights(ctx.model.enemy.warriors);
        // Optionally only an enemy army near the base asks for extra towers: one far away may never come here.
        Building home = ctx.economy.armory();
        if (tower_threat_r > 0 && home != null && !home.isDead()) {
            theirs = 0f;
            for (Unit e : ctx.model.enemy.warriors) {
                if (MapAnalysis.chebyshev(e.getGridX(), e.getGridY(), home.getGridX(),
                        home.getGridY()) <= tower_threat_r)
                    theirs += CombatModel.weight(e);
            }
        }
        n += Math.max(0, (int) ((theirs - ours) / 6f));
        n = Math.min(n, Math.min(tower_max, slots.size()));
        // The boom comes first: until towerEarlyQuarters quarters stand (or towerEarlyTime), at most towerEarlyCap towers,
        // unless enemy warriors are at the base already.
        if (tower_early_cap > 0 && ctx.now < tower_early_time && ctx.model.me.quarters.size() < tower_early_quarters) {
            Building a = ctx.economy.armory();
            boolean raid = a != null && !a.isDead() && enemyWarriorsNear(a, 45) >= 4;
            if (!raid)
                n = Math.min(n, tower_early_cap);
        }
        // At the unit cap more warriors cannot be made, but more towers can: spare building slots (a reserve stays
        // free for a new armory or quarters) become towers, each one multiplying its garrison's worth.
        if (late_towers > 0 && ctx.now >= late_time && ctx.economy.phase() == EconomyManager.Phase.CAP
                && ctx.owner.getUnitCountContainer().getNumSupplies() >= ctx.world.getMaxUnitCount() - 15) {
            int used = ctx.owner.getBuildingCountContainer().getNumSupplies() + ctx.build.pendingSlots();
            int spare = ctx.world.getMaxBuildingCount() - late_reserve - used;
            int have = ctx.model.me.towers.size() + ctx.build.openCount(Race.BUILDING_TOWER);
            if (spare > 0)
                n = Math.max(n, Math.min(slots.size(), Math.min(tower_max + late_towers, have + 1)));
        }
        return n;
    }

    /**
     * Time schedule instead of answering the enemy's army size (a big army far away starved the boom of builders):
     * one tower at c1Time, two at c2Time, four at 8 min, six at 12 min and one more per minute after, plus one while
     * enemy warriors are at the base.
     */
    private int scheduledTowers() {
        float t = ctx.now;
        int n = t >= c2_time ? 2 : t >= c1_time ? 1 : 0;
        if (t >= 480f)
            n = 4;
        if (t >= 720f)
            n = 6 + (int) ((t - 720f) / 60f);
        Building a = ctx.economy.armory();
        if (a != null && !a.isDead() && enemyWarriorsNear(a, 45) >= 4)
            n++;
        // Two building slots stay free for a new armory or quarters.
        int others = ctx.owner.getBuildingCountContainer().getNumSupplies() + ctx.build.pendingSlots() - ctx.model.me.towers.size() - ctx.build.openCount(
                Race.BUILDING_TOWER);
        n = Math.min(n, Math.max(2, ctx.world.getMaxBuildingCount() - 2 - others));
        return Math.min(n, slots.size());
    }

    // -----------------------------------------------------------------------------------------------------

    void planTick() {
        Building a = ctx.economy.armory();
        if (a == null || a.isDead())
            return;
        int want = desiredTowers();
        // Every home tower counts toward the budget, also those left around a former armory; held() only decides
        // which slots around the current armory are free for the next one.
        int have = 0;
        for (Slot s : slots) {
            BuildManager.Task t = s.task;
            if (t != null && t.open())
                have++;
        }
        for (Building t : ctx.model.me.towers) {
            if (!t.isDead() && nearHome(t))
                have++;
        }
        // Only build a tower when a warrior can man it.
        int manned = 0;
        for (Building t : ctx.model.me.towers) {
            if (!t.isDead() && t.getUnitCount() > 0)
                manned++;
        }
        int weapons = a.getSupplyContainer(
                com.oddlabs.tt.model.weapon.IronAxeWeapon.class).getNumSupplies() + a.getSupplyContainer(
                        com.oddlabs.tt.model.weapon.RubberAxeWeapon.class).getNumSupplies();
        int crews = ctx.military.armySize() + weapons + manned;
        // Towers compete with quarters and the armory for builders: without a raid on the base only a few are open
        // at a time, below the boom quarters until the fourth quarters stands.
        boolean hot = ctx.economy.emergency || enemyWarriorsNear(a, 45) >= 4;
        hot_now = hot;
        boolean low = tower_prio_low > 0 && !hot && ctx.model.me.quarters.size() < 4;
        if (tower_prio_low > 0 && !low) {
            for (Slot s : slots) {
                BuildManager.Task t = s.task;
                if (t != null && t.open() && t.priority < 800 - slots.indexOf(s))
                    ctx.build.reprioritize(t, 800 - slots.indexOf(s));
            }
        }
        if (have >= want || crews < have + 1)
            return;
        if (!ctx.owner.canBuild(Race.BUILDING_TOWER))
            return;
        if (tower_schedule && ctx.now - last_tower_request < 15f)
            return;
        if (tower_open_max > 0) {
            int open = 0;
            for (Slot s : slots) {
                BuildManager.Task t = s.task;
                if (t != null && t.open())
                    open++;
            }
            int cap = hot ? tower_open_hot : tower_open_max;
            if (cap > 0 && open >= cap)
                return;
        }
        for (Slot s : slots) {
            if (held(s, a))
                continue;
            int[] site = siteFor(s, a);
            if (site == null)
                continue;
            int prio = (low ? tower_prio_low : 800) - slots.indexOf(s);
            s.task = ctx.build.request(Race.BUILDING_TOWER, site[0], site[1], prio, tower_builders, s.name);
            last_tower_request = ctx.now;
            return;
        }
    }

    private int enemyWarriorsNear(@NonNull Building a, int r) {
        int n = 0;
        for (Unit e : ctx.model.enemy.warriors) {
            if (!e.isDead() && MapAnalysis.chebyshev(e.getGridX(), e.getGridY(), a.getGridX(), a.getGridY()) <= r)
                n++;
        }
        return n;
    }

    /**
     * A slot is held by an open task, or by a live tower near the current armory; towers left around a former
     * armory (after an expansion) free the slot for the new one.
     */
    private boolean nearHome(@NonNull Building t) {
        for (Building b : ctx.model.me.buildings) {
            int id = b.getTemplate().getTemplateID();
            if (!b.isDead() && (id == Race.BUILDING_ARMORY || id == Race.BUILDING_QUARTERS)
                    && MapAnalysis.chebyshev(b.getGridX(), b.getGridY(), t.getGridX(), t.getGridY()) <= 25)
                return true;
        }
        return false;
    }

    private static boolean held(@NonNull Slot s, @NonNull Building a) {
        BuildManager.Task t = s.task;
        if (t == null)
            return false;
        if (t.open())
            return true;
        Building b = t.building;
        return b != null && !b.isDead()
                && MapAnalysis.chebyshev(b.getGridX(), b.getGridY(), a.getGridX(), a.getGridY()) <= s.radius + 8;
    }

    private int @Nullable [] siteFor(@NonNull Slot s, @NonNull Building a) {
        float base = (float) StrictMath.atan2(ctx.map.enemy_y - a.getGridY(), ctx.map.enemy_x - a.getGridX());
        float ang = base + s.angle;
        int sx = Math.round(a.getGridX() + s.radius * (float) StrictMath.cos(ang));
        int sy = Math.round(a.getGridY() + s.radius * (float) StrictMath.sin(ang));
        int ax = a.getGridX();
        int ay = a.getGridY();
        return ctx.sites.best(ctx.race.getBuildingTemplate(Race.BUILDING_TOWER), sx, sy, 4, (x, y) -> {
            if (MapAnalysis.chebyshev(x, y, ax, ay) < 7)
                return Float.NEGATIVE_INFINITY;
            float d = (float) Math.sqrt(MapAnalysis.dist2(x, y, sx, sy));
            return ctx.map.height(x, y) * 0.3f - d;
        });
    }

    int @Nullable [] replanSite(BuildManager.@NonNull Task t) {
        Building a = ctx.economy.armory();
        for (Slot s : slots) {
            if (s.task == t && a != null)
                return siteFor(s, a);
        }
        return null;
    }

    // -----------------------------------------------------------------------------------------------------

    boolean isPendingGarrison(@NonNull Unit w) {
        return pending_garrison.containsValue(w);
    }

    void garrisonTick() {
        // Release warriors whose tower died or got manned by someone else.
        List<Building> stale = new ArrayList<>();
        for (Map.Entry<Building, Unit> e : pending_garrison.entrySet()) {
            Building t = e.getKey();
            if (t.isDead() || (t.getUnitCount() > 0 && WorldModel.garrisonOf(t) != e.getValue()))
                stale.add(t);
        }
        for (Building t : stale) {
            Unit w = pending_garrison.remove(t);
            if (w != null)
                ctx.military.releaseFromGarrison(w);
        }
        evacuated_until.keySet().removeIf(Building::isDead);
        repairTowers();
        for (Building t : ctx.model.me.towers) {
            if (t.isDead())
                continue;
            if (t.getUnitCount() > 0) {
                pending_garrison.remove(t);
                continue;
            }
            Float until = evacuated_until.get(t);
            if (until != null && ctx.now < until && t.getHitPoints() < 40)
                continue;
            if (t.getHitPoints() <= 20 && enemiesAdjacent(t) > 0)
                continue;
            Unit w = pending_garrison.get(t);
            if (w != null && !w.isDead() && !w.isMounted()) {
                if (!ctx.orders.recently(w, Orders.Kind.ENTER, t, t.getGridX(), t.getGridY(), 6f))
                    ctx.orders.enter(w, t);
                continue;
            }
            if (w != null && !w.isMounted())
                ctx.military.releaseFromGarrison(w);
            w = ctx.military.takeForGarrison(t.getGridX(), t.getGridY());
            if (w == null)
                continue;
            pending_garrison.put(t, w);
            ctx.orders.enter(w, t);
        }
    }

    void microTick() {
        for (Building t : ctx.model.me.towers) {
            Unit g = WorldModel.garrisonOf(t);
            if (g == null || WorldModel.isStunned(g))
                continue;
            evacuateIfDoomed(t);
            if (t.isDead())
                continue;
            Selectable<?> current = null;
            if (g.getCurrentController() instanceof AttackController ac)
                current = ac.getTarget();
            boolean idle = g.getCurrentController() instanceof IdleController;
            int cur_class = current != null && !current.isDead() ? classOf(t, current) : 99;
            Selectable<?> best = null;
            int best_class = 99;
            int best_d = Integer.MAX_VALUE;
            for (Unit e : ctx.model.enemy.units) {
                int d2 = MapAnalysis.dist2(g.getGridX(), g.getGridY(), e.getGridX(), e.getGridY());
                if (d2 > 300 || !g.isCloseEnough(g.getRange(e), e))
                    continue;
                int c = classOf(t, e);
                if (c < best_class || (c == best_class && d2 < best_d)) {
                    best_class = c;
                    best_d = d2;
                    best = e;
                }
            }
            if (best == null) {
                for (Building b : ctx.model.enemy.buildings) {
                    if (b.isDead() || !g.isCloseEnough(g.getRange(b), b))
                        continue;
                    int c = classOf(t, b);
                    int d2 = MapAnalysis.dist2(g.getGridX(), g.getGridY(), b.getGridX(), b.getGridY());
                    if (c < best_class || (c == best_class && d2 < best_d)) {
                        best_class = c;
                        best_d = d2;
                        best = b;
                    }
                }
            }
            if (best == null || best == current)
                continue;
            Float last = last_order.get(t);
            boolean rate_ok = last == null || ctx.now - last >= 1.5f || best_class <= 1;
            if ((idle || cur_class == 99 || best_class < cur_class) && rate_ok) {
                ctx.orders.towerTarget(t, best);
                last_order.put(t, ctx.now);
            }
        }
    }

    /** Lower is more urgent. */
    private int classOf(@NonNull Building tower, @NonNull Selectable<?> e) {
        if (e instanceof Unit u) {
            WorldModel.Kind k = WorldModel.kindOf(u);
            if (k == WorldModel.Kind.PEON || k == WorldModel.Kind.CHIEFTAIN) {
                if (MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), tower.getGridX(), tower.getGridY()) <= 3)
                    return 1;
                if (WorldModel.visibleActivity(u) == WorldModel.Activity.BUILD)
                    return 2;
                return k == WorldModel.Kind.CHIEFTAIN ? 4 : 5;
            }
            return switch (k) {
                case RUBBER -> 3;
                case IRON -> 3;
                default -> 4;
            };
        }
        if (e instanceof Building b) {
            if (!b.isComplete() && b.getHitPoints() <= 20)
                return 2;
            return 6;
        }
        return 7;
    }

    private int enemiesAdjacent(@NonNull Building t) {
        int adjacent = 0;
        for (Unit e : ctx.model.enemy.units) {
            if (MapAnalysis.chebyshev(e.getGridX(), e.getGridY(), t.getGridX(), t.getGridY()) <= 3)
                adjacent++;
        }
        return adjacent;
    }

    /** A tower about to fall under melee (peons deal 6 per swing) lets its garrison out instead of losing it. */
    private void evacuateIfDoomed(@NonNull Building t) {
        if (t.getHitPoints() > 14 || !t.canExitTower())
            return;
        int adjacent = enemiesAdjacent(t);
        if (adjacent == 0)
            return;
        if (6 * adjacent < t.getHitPoints() - 6)
            return;
        Unit g = WorldModel.garrisonOf(t);
        if (ctx.orders.exitTower(t) && g != null) {
            pending_garrison.remove(t);
            evacuated_until.put(t, ctx.now + 20f);
            ctx.military.releaseFromGarrison(g);
            ctx.log(() -> "evacuated tower at " + t.getGridX() + "," + t.getGridY());
        }
    }

    /** One repairer per damaged tower while no enemy warrior is close. */
    private void repairTowers() {
        repairer.keySet().removeIf(t -> t.isDead() || !t.isDamaged());
        for (Building t : ctx.model.me.towers) {
            if (t.isDead() || !t.isDamaged() || t.getHitPoints() > 85)
                continue;
            if (ctx.resources.threatened(t.getGridX(), t.getGridY(), 12))
                continue;
            Unit r = repairer.get(t);
            if (r != null && !r.isDead() && WorldModel.buildTarget(r) == t)
                continue;
            Unit best = null;
            int best_d = 30 * 30;
            for (Unit u : ctx.model.me.peons) {
                boolean wood_gatherer = !ctx.hasRole(u)
                        && WorldModel.primary(u) instanceof com.oddlabs.tt.model.behaviour.GatherController<?> gc
                        && gc.getSupplyType() == com.oddlabs.tt.landscape.TreeSupply.class;
                if (u.isDead() || (!ctx.economy.isFree(u) && !wood_gatherer))
                    continue;
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), t.getGridX(), t.getGridY());
                if (d < best_d) {
                    best_d = d;
                    best = u;
                }
            }
            if (best != null) {
                ctx.orders.repair(best, t);
                repairer.put(t, best);
            }
        }
    }
}
