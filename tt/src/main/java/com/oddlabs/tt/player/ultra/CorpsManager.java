package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.HuntController;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The peon demolition corps. Peons deal a fixed 6 damage per swing to towers (no hit roll), so a handful of them
 * tears down a tower in seconds, far cheaper than warriors, especially while its garrison is stunned or busy
 * shooting our warriors (towers prefer warriors over peons). The corps follows the main army on attacks and is
 * handed back to the economy afterwards.
 */
final class CorpsManager {
    private static final int PER_TOWER = 8;

    private final @NonNull Context ctx;
    private final List<Unit> corps = new ArrayList<>();
    private final Map<Unit, Selectable<?>> target = new HashMap<>();
    private int pending_deploy;
    private boolean pending_wood;
    private float pending_time;
    private final boolean enabled;
    private final int size;

    CorpsManager(@NonNull Context ctx) {
        this.ctx = ctx;
        this.enabled = ctx.strategy.b("corps");
        this.size = ctx.strategy.i("corpsSize");
    }

    int size() {
        return corps.size();
    }

    @NonNull
    List<Unit> members() {
        return new ArrayList<>(corps);
    }

    /** Hands a corps peon to another job (its new owner sets the role). */
    void release(@NonNull Unit u) {
        corps.remove(u);
        target.remove(u);
        if (ctx.roleOf(u) == Context.Role.CORPS)
            ctx.clearRole(u);
    }

    /** Every second: recruit while the army is out attacking a defended target, release when it is home. */
    void recruitTick() {
        corps.removeIf(u -> u.isDead() || ctx.roleOf(u) != Context.Role.CORPS);
        target.keySet().removeIf(Unit::isDead);
        MilitaryManager.State s = ctx.military.state();
        boolean attacking = s == MilitaryManager.State.MARCH || s == MilitaryManager.State.ENGAGE
                || s == MilitaryManager.State.SIEGE;
        if (!enabled || !attacking) {
            releaseAll();
            return;
        }
        int[] obj = ctx.military.objectivePoint();
        if (obj == null || !towersNear(obj[0], obj[1], 30)) {
            if (corps.isEmpty())
                return;
        }
        int want = size - corps.size() - pending_deploy;
        if (want <= 0)
            return;
        // Free peons first (never builders, hunters or carriers), then spare armory workers.
        for (Unit u : ctx.model.me.peons) {
            if (want <= 0)
                break;
            if (u.isDead() || ctx.hasRole(u) || BuildManager.carriesAnything(u) || !ctx.economy.isFree(u))
                continue;
            recruit(u);
            want--;
        }
        Building a = ctx.economy.armory();
        if (want > 0 && a != null && !a.isDead() && pending_deploy == 0) {
            int spare = a.getUnitContainer().getNumSupplies() - Math.max(4, ctx.economy.workersTarget() / 2);
            int n = Math.min(want, spare);
            if (n > 0) {
                // Corps peons carry a wood each when the stock allows: a creep tower then goes up at once.
                int wood = a.getSupplyContainer(com.oddlabs.tt.landscape.TreeSupply.class).getNumSupplies();
                pending_wood = wood >= n + 8 && !ctx.build.dropPending();
                ctx.orders.deploy(a, pending_wood ? DeployType.PEON_TRANSPORT_TREE : DeployType.PEON, n);
                pending_deploy = n;
                pending_time = ctx.now;
            }
        }
        if (pending_deploy > 0 && ctx.now - pending_time > 15f)
            pending_deploy = 0;
    }

    private void recruit(@NonNull Unit u) {
        ctx.setRole(u, Context.Role.CORPS);
        corps.add(u);
    }

    private void releaseAll() {
        for (Unit u : corps) {
            if (!u.isDead() && ctx.roleOf(u) == Context.Role.CORPS) {
                ctx.clearRole(u);
                Building a = ctx.economy.armory();
                if (a != null)
                    ctx.orders.enter(u, a);
            }
        }
        corps.clear();
        target.clear();
        pending_deploy = 0;
    }

    private boolean towersNear(int x, int y, int r) {
        int r2 = r * r;
        for (Building t : ctx.model.enemy.towers) {
            if (!t.isDead() && MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= r2)
                return true;
        }
        for (Building b : ctx.model.enemy.sites) {
            if (!b.isDead() && b.getTemplate().getTemplateID() == Race.BUILDING_TOWER
                    && MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y) <= r2)
                return true;
        }
        return false;
    }

    /** Every 0.25 s: claim deployed peons, follow the army, assault towers the army is fighting next to. */
    void microTick() {
        if (!enabled)
            return;
        claimDeployed();
        if (corps.isEmpty())
            return;
        int[] anchor = ctx.military.squadAnchor();
        if (anchor == null) {
            return;
        }
        List<Building> towers = new ArrayList<>();
        for (Building t : ctx.model.enemy.towers) {
            if (!t.isDead() && MapAnalysis.chebyshev(t.getGridX(), t.getGridY(), anchor[0], anchor[1]) <= 22)
                towers.add(t);
        }
        for (Building b : ctx.model.enemy.sites) {
            if (!b.isDead() && b.getTemplate().getTemplateID() == Race.BUILDING_TOWER
                    && MapAnalysis.chebyshev(b.getGridX(), b.getGridY(), anchor[0], anchor[1]) <= 22)
                towers.add(b);
        }
        // Most urgent first: stunned or unmanned towers, then the nearest.
        towers.sort((x, y) -> {
            int px = priority(x, anchor);
            int py = priority(y, anchor);
            return Integer.compare(px, py);
        });
        Map<Building, Integer> assigned = new HashMap<>();
        for (Unit u : corps) {
            Selectable<?> t = target.get(u);
            if (t instanceof Building b && !b.isDead() && towers.contains(b))
                assigned.merge(b, 1, Integer::sum);
        }
        int[] rear = behind(anchor, 5);
        for (int extra = 0; extra < 4 && nearestWarrior(rear[0], rear[1]) < 11; extra++)
            rear = behind(anchor, 9 + 4 * extra);
        for (Unit u : corps) {
            if (u.isDead())
                continue;
            Selectable<?> t = target.get(u);
            if (t != null && !t.isDead() && t instanceof Building b && towers.contains(b)) {
                if (!(u.getCurrentController() instanceof HuntController) && !ctx.orders.recently(u, 2f))
                    ctx.orders.attack(u, b);
                continue;
            }
            target.remove(u);
            if (u.getCurrentController() instanceof HuntController hc && hc.getTarget() instanceof Unit) {
                // An idle corps peon auto-charged a warrior: pull it back.
                ctx.orders.move(u, rear[0], rear[1]);
                continue;
            }
            Building pick = null;
            for (Building tw : towers) {
                Integer n = assigned.get(tw);
                if ((n == null || n < PER_TOWER) && safeToAssault(tw)) {
                    pick = tw;
                    break;
                }
            }
            if (pick != null) {
                assigned.merge(pick, 1, Integer::sum);
                target.put(u, pick);
                ctx.orders.attack(u, pick);
                continue;
            }
            if (MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), rear[0], rear[1]) > 4
                    && !ctx.orders.recently(u, Orders.Kind.MOVE, null, rear[0], rear[1], 3f))
                ctx.orders.move(u, rear[0], rear[1]);
        }
    }

    private float nearestWarrior(int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Unit e : ctx.model.enemy.warriors)
            best = Math.min(best, MapAnalysis.dist2(e.getGridX(), e.getGridY(), x, y));
        return best == Integer.MAX_VALUE ? Float.MAX_VALUE : (float) Math.sqrt(best);
    }

    private int priority(@NonNull Building t, int @NonNull [] anchor) {
        int d = MapAnalysis.chebyshev(t.getGridX(), t.getGridY(), anchor[0], anchor[1]);
        Unit g = WorldModel.garrisonOf(t);
        if (g == null)
            return d;
        if (WorldModel.isStunned(g))
            return d - 100;
        return d + 30;
    }

    /** Peons go in when the garrison is stunned, or our warriors outnumber the enemy warriors around the tower. */
    private boolean safeToAssault(@NonNull Building t) {
        Unit g = WorldModel.garrisonOf(t);
        if (g != null && WorldModel.isStunned(g))
            return true;
        if (ctx.military.stunSieging())
            return false;
        int ours = 0;
        for (Unit w : ctx.model.me.warriors) {
            if (MapAnalysis.chebyshev(w.getGridX(), w.getGridY(), t.getGridX(), t.getGridY()) <= 14)
                ours++;
        }
        int theirs = 0;
        for (Unit e : ctx.model.enemy.warriors) {
            if (MapAnalysis.chebyshev(e.getGridX(), e.getGridY(), t.getGridX(), t.getGridY()) <= 14)
                theirs++;
        }
        return ours >= 3 && ours >= 2 * theirs;
    }

    private int @NonNull [] behind(int @NonNull [] anchor, int dist) {
        int[] home = ctx.military.rallyPoint();
        int hx = home != null ? home[0] : ctx.map.home_x;
        int hy = home != null ? home[1] : ctx.map.home_y;
        int dx = hx - anchor[0];
        int dy = hy - anchor[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f)
            return anchor;
        int x = ctx.map.clamp(Math.round(anchor[0] + dx / len * dist));
        int y = ctx.map.clamp(Math.round(anchor[1] + dy / len * dist));
        int[] acc = ctx.map.nearestAccessible(x, y);
        return acc != null ? acc : anchor;
    }

    /** Peons we deployed from the armory for the corps show up idle or walking near the rally point. */
    private void claimDeployed() {
        if (pending_deploy <= 0)
            return;
        int[] rally = ctx.military.rallyPoint();
        Building a = ctx.economy.armory();
        if (rally == null || a == null)
            return;
        for (Unit u : ctx.model.me.peons) {
            if (pending_deploy <= 0)
                return;
            if (u.isDead() || ctx.hasRole(u) || (BuildManager.carriesAnything(u) && !pending_wood))
                continue;
            WorldModel.Activity act = WorldModel.activityOf(u);
            if (act != WorldModel.Activity.WALK && act != WorldModel.Activity.IDLE)
                continue;
            if (MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), a.getGridX(), a.getGridY()) > 16
                    && MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), rally[0], rally[1]) > 6)
                continue;
            if (!u.getAbilities().hasAbilities(Abilities.BUILD))
                continue;
            recruit(u);
            pending_deploy--;
        }
    }
}
