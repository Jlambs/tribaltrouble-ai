package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Tower creep: while the main army holds ground next to an enemy building it is sieging, the peon corps that
 * marches with the army builds a tower in range of that building but outside the reach of enemy towers. A
 * garrisoned tower out-ranges every ground unit and keeps shelling the target after the army moves on.
 */
final class CreepManager {
    /** An enemy garrison reaches our tower centre from about 16.9 cells plus its entry-cell offset. */
    private static final int ENEMY_TOWER_STANDOFF = 20;
    /** Our garrison hits quarters/armory centres from 19.76 cells of its entry cell (about 2 cells off). */
    private static final int SHELL_RANGE = 17;

    private final @NonNull Context ctx;
    private BuildManager.@Nullable Task task;
    private float last_try = Float.NEGATIVE_INFINITY;
    private final boolean enabled;
    private final int max_towers;
    private int built;
    /** Last time a corps peon was available to staff the open task. */
    private float last_staffed;
    /** Where the last attempt failed (foundation destroyed): not again within 8 cells for a while. */
    private int failed_x = -100;
    private int failed_y = -100;
    private float failed_time = Float.NEGATIVE_INFINITY;

    CreepManager(@NonNull Context ctx) {
        this.ctx = ctx;
        this.enabled = ctx.strategy.b("creep");
        this.max_towers = ctx.strategy.i("creepMax");
    }

    void tick() {
        if (!enabled)
            return;
        if (task != null) {
            if (task.state == BuildManager.State.DONE)
                built++;
            if (task.state == BuildManager.State.FAILED) {
                failed_x = task.x;
                failed_y = task.y;
                failed_time = ctx.now;
            }
            if (!task.open()) {
                task = null;
            } else {
                staff();
                abandonIfStranded();
                return;
            }
        }
        if (built >= max_towers || ctx.now - last_try < 10f)
            return;
        last_try = ctx.now;
        MilitaryManager.State s = ctx.military.state();
        if (s != MilitaryManager.State.SIEGE && s != MilitaryManager.State.ENGAGE)
            return;
        Building target = ctx.military.objectiveBuilding();
        if (target == null || target.isDead() || !target.isComplete())
            return;
        int id = target.getTemplate().getTemplateID();
        if (id != Race.BUILDING_ARMORY && id != Race.BUILDING_QUARTERS)
            return;
        if (!ctx.military.holdingGround() || ctx.corps.size() < 6)
            return;
        if (!ctx.owner.canBuild(Race.BUILDING_TOWER)
                || ctx.owner.getBuildingCountContainer().getNumSupplies() + ctx.build.pendingSlots() >= ctx.world.getMaxBuildingCount() - 1)
            return;
        int[] site = siteNear(target);
        if (site == null)
            return;
        task = ctx.build.request(Race.BUILDING_TOWER, site[0], site[1], 950, 0, "Creep");
        task.manual = true;
        last_staffed = ctx.now;
        ctx.log(() -> "tower creep at " + site[0] + "," + site[1] + " vs " + target.getGridX() + "," + target.getGridY());
        staff();
    }

    /** Corps peons become the builders; the first one lays (or re-lays) the foundation. */
    private void staff() {
        BuildManager.Task t = task;
        if (t == null)
            return;
        int builders = ctx.build.builderCount(t);
        if (builders > 0)
            last_staffed = ctx.now;
        int want = 8 - builders;
        Building b = t.building;
        if (b != null && !b.isDead() && b.isPlaced())
            want = Math.min(want, Math.max(0, ctx.build.woodNeed(t)));
        if (want <= 0)
            return;
        for (Unit u : ctx.corps.members()) {
            if (want <= 0)
                break;
            if (u.isDead() || ctx.roleOf(u) != Context.Role.CORPS || !ctx.orders.canOrder(u))
                continue;
            // placeWith re-orders the placement when the foundation is not laid, and assigns otherwise.
            if (!ctx.build.placeWith(t, u))
                break;
            ctx.corps.release(u);
            last_staffed = ctx.now;
            want--;
        }
    }

    /** A site nobody has worked on for a while, with the army no longer fighting there, is given up. */
    private void abandonIfStranded() {
        BuildManager.Task t = task;
        if (t == null || ctx.build.builderCount(t) > 0 || t.state == BuildManager.State.ORDERED)
            return;
        MilitaryManager.State s = ctx.military.state();
        boolean fighting = s == MilitaryManager.State.MARCH || s == MilitaryManager.State.ENGAGE
                || s == MilitaryManager.State.SIEGE;
        if (fighting && ctx.now - last_staffed < 20f)
            return;
        ctx.log(() -> "creep abandoned " + t);
        ctx.build.cancel(t);
        task = null;
    }

    private int @Nullable [] siteNear(@NonNull Building target) {
        int tx = target.getGridX();
        int ty = target.getGridY();
        int[] anchor = ctx.military.squadAnchor();
        int ax = anchor != null ? anchor[0] : tx;
        int ay = anchor != null ? anchor[1] : ty;
        return ctx.sites.best(ctx.race.getBuildingTemplate(Race.BUILDING_TOWER), tx, ty, SHELL_RANGE, (x, y) -> {
            if (MapAnalysis.chebyshev(x, y, tx, ty) > SHELL_RANGE)
                return Float.NEGATIVE_INFINITY;
            if (ctx.now - failed_time < 120f && MapAnalysis.chebyshev(x, y, failed_x, failed_y) <= 8)
                return Float.NEGATIVE_INFINITY;
            for (Building e : ctx.model.enemy.towers) {
                if (!e.isDead() && MapAnalysis.dist2(e.getGridX(), e.getGridY(), x,
                        y) < ENEMY_TOWER_STANDOFF * ENEMY_TOWER_STANDOFF)
                    return Float.NEGATIVE_INFINITY;
            }
            // Close to our army (safe to build), as far from the target as the range allows.
            return -(float) Math.sqrt(MapAnalysis.dist2(x, y, ax, ay)) + 0.3f * MapAnalysis.chebyshev(x, y, tx, ty);
        });
    }
}
