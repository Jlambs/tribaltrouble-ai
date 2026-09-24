package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployContainer;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Proxy tower: a crew deployed from the armory (carrying wood when the stock allows) marches next to the enemy's
 * biggest exposed gatherer group, or its armory, and raises a tower there; the garrison logic then mans it. A single
 * tower there shoots enemy gatherers and
 * warriors leaving the armory and pins the enemy's attention.
 */
final class ProxyManager {
    private static final int CREW = 12;
    private static final int STANDOFF = 20;

    private final @NonNull Context ctx;
    private final boolean enabled;
    private final float start_time;
    private final int max_towers;
    private BuildManager.@Nullable Task task;
    private int built;
    private int failed;
    private float last_try = Float.NEGATIVE_INFINITY;

    ProxyManager(@NonNull Context ctx) {
        this.ctx = ctx;
        this.enabled = ctx.strategy.b("proxyTower");
        this.start_time = ctx.strategy.f("proxyTime");
        this.max_towers = ctx.strategy.i("proxyMax");
    }

    void tick() {
        if (!enabled)
            return;
        if (task != null && !task.open()) {
            if (task.state == BuildManager.State.DONE)
                built++;
            else
                failed++;
            task = null;
        }
        if (task != null) {
            BuildManager.Task t = task;
            if (t.drop_pending == 0 && ctx.build.builderCount(t) == 0 && t.state != BuildManager.State.ORDERED
                    && ctx.now - t.drop_time > 30f) {
                Building b = t.building;
                if (b != null && !b.isDead() && b.isPlaced() && failed <= max_towers)
                    recrew(t);
                else
                    ctx.build.cancel(t);
            }
            return;
        }
        if (built >= max_towers || failed > max_towers || ctx.now < start_time || ctx.now - last_try < 15f)
            return;
        last_try = ctx.now;
        Building a = ctx.economy.armory();
        if (a == null || ctx.model.enemy.armories.isEmpty() || ctx.build.dropPending())
            return;
        int wood = a.getSupplyContainer(TreeSupply.class).getNumSupplies();
        int inside = a.getUnitContainer().getNumSupplies();
        int n = Math.min(CREW, inside - 2);
        boolean carry = wood >= n + 4;
        DeployType type = carry ? DeployType.PEON_TRANSPORT_TREE : DeployType.PEON;
        DeployContainer c = a.getDeployContainer(type);
        if (n < 8 || c == null || c.getNumSupplies() > 0)
            return;
        if (!ctx.owner.canBuild(Race.BUILDING_TOWER)
                || ctx.owner.getBuildingCountContainer().getNumSupplies() + ctx.build.pendingSlots() >= ctx.world.getMaxBuildingCount() - 1)
            return;
        int[] focus = gathererCluster();
        if (focus == null) {
            Building armory = ctx.model.enemy.armories.get(0);
            focus = new int[]{armory.getGridX(), armory.getGridY()};
        }
        int[] site = site(focus[0], focus[1]);
        int[] f = focus;
        if (site == null) {
            ctx.log(() -> "proxy no site near " + f[0] + "," + f[1]);
            return;
        }
        BuildManager.Task t = ctx.build.request(Race.BUILDING_TOWER, site[0], site[1], 940, 0, "Proxy");
        t.manual = true;
        t.drop_pending = n;
        t.drop_plain = !carry;
        t.drop_time = ctx.now;
        task = t;
        ctx.orders.deploy(a, type, n);
        ctx.log(() -> "proxy tower at " + site[0] + "," + site[1] + " crew " + n + (carry ? " with wood" : ""));
    }

    /** The crew of a placed proxy died or fled: send a new one to finish it. */
    private void recrew(BuildManager.@NonNull Task t) {
        Building a = ctx.economy.armory();
        if (a == null || ctx.build.dropPending())
            return;
        int n = Math.min(CREW, a.getUnitContainer().getNumSupplies() - 2);
        DeployContainer c = a.getDeployContainer(DeployType.PEON);
        if (n < 6 || c == null || c.getNumSupplies() > 0)
            return;
        failed++;
        t.drop_pending = n;
        t.drop_plain = true;
        t.drop_time = ctx.now;
        ctx.orders.deploy(a, DeployType.PEON, n);
        ctx.log(() -> "proxy recrew " + n + " -> " + t);
    }

    /** The centre of the biggest group of enemy gatherers outside enemy tower cover, or null. */
    private int @Nullable [] gathererCluster() {
        int best_n = 3;
        int[] best = null;
        for (Unit e : ctx.model.enemy.peons) {
            if (WorldModel.visibleActivity(e) != WorldModel.Activity.GATHER)
                continue;
            int x = e.getGridX();
            int y = e.getGridY();
            boolean covered = false;
            for (Building t : ctx.model.enemy.towers) {
                if (!t.isDead() && MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) < STANDOFF * STANDOFF) {
                    covered = true;
                    break;
                }
            }
            if (covered)
                continue;
            int n = 0;
            for (Unit o : ctx.model.enemy.peons) {
                if (MapAnalysis.chebyshev(o.getGridX(), o.getGridY(), x, y) <= 8)
                    n++;
            }
            if (n > best_n) {
                best_n = n;
                best = new int[]{x, y};
            }
        }
        return best;
    }

    private int @Nullable [] site(int tx, int ty) {
        int hx = ctx.map.home_x;
        int hy = ctx.map.home_y;
        return ctx.sites.best(ctx.race.getBuildingTemplate(Race.BUILDING_TOWER), tx, ty, 16, (x, y) -> {
            int d = MapAnalysis.chebyshev(x, y, tx, ty);
            if (d < 4 || d > 13)
                return Float.NEGATIVE_INFINITY;
            for (Building e : ctx.model.enemy.towers) {
                if (!e.isDead() && MapAnalysis.dist2(e.getGridX(), e.getGridY(), x, y) < STANDOFF * STANDOFF)
                    return Float.NEGATIVE_INFINITY;
            }
            // The side facing home: shorter walk, and away from their rear.
            return -(float) Math.sqrt(MapAnalysis.dist2(x, y, hx, hy)) / 10f - d;
        });
    }
}
