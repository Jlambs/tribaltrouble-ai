package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.MagicController;
import com.oddlabs.tt.player.VikingChieftainAI;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The Viking chieftain: trains it, keeps it safely behind the army, and casts the stun ("terrifying toot") when
 * enough enemy weight is inside the 36 m (18 cell) radius and our army can exploit the 10-30 s window.
 */
final class ChieftainManager {
    private static final float STUN_RADIUS_CELLS = 16.5f;

    private final @NonNull Context ctx;
    private @Nullable Building training_q;
    private float lock_until;
    private float energy_full_since = -1f;
    private float last_check;
    private float theta;
    /** Cast from where the chieftain stands even with enemies close (the 9-cell rule only limits walking in). */
    private final boolean close_cast;
    /** Threshold factor while an enemy chieftain is near: being stunned first loses the fight. */
    private final float preempt;
    /** Stun value of a manned enemy tower in reach (a stunned garrison cannot shoot while the army tears it down). */
    private final float toot_tower_value;
    private final boolean dodge;
    private final boolean dodge_smart;
    private final float dodge_max;
    private final float dodge_to;
    private final boolean toot_after_enemy;
    private final boolean toot_base_cover;
    private final float chief_time;
    private final int chief_min_quarters;
    private final int chief_keep;

    ChieftainManager(@NonNull Context ctx) {
        this.ctx = ctx;
        chief_time = ctx.strategy.f("chiefTime");
        chief_min_quarters = ctx.strategy.i("chiefMinQuarters");
        chief_keep = ctx.strategy.i("chiefKeep");
        theta = ctx.strategy.f("tootTheta");
        close_cast = ctx.strategy.b("tootCloseCast");
        preempt = ctx.strategy.f("tootPreempt");
        toot_tower_value = ctx.strategy.f("tootTowerValue");
        dodge = ctx.strategy.b("dodge");
        dodge_smart = ctx.strategy.b("dodgeSmart");
        dodge_max = ctx.strategy.f("dodgeMax");
        dodge_to = ctx.strategy.f("dodgeTo");
        toot_after_enemy = ctx.strategy.b("tootAfterEnemy");
        toot_base_cover = ctx.strategy.b("tootBaseCover");
    }

    @Nullable
    Building trainingQuarters() {
        if (training_q != null && (training_q.isDead() || !ctx.owner.isTrainingChieftain()))
            training_q = null;
        return training_q;
    }

    int keepInside() {
        return chief_keep;
    }

    /** Peons the training quarters still wants inside. */
    int peonsWanted() {
        Building q = trainingQuarters();
        if (q == null)
            return 0;
        return Math.max(0, chief_keep - q.getUnitContainer().getNumSupplies());
    }

    void tick() {
        if (!(ctx.race.getChieftainAI() instanceof VikingChieftainAI))
            return;
        Unit chief = ctx.owner.getChieftain();
        if (chief == null || chief.isDead()) {
            if (ctx.now - last_check >= 2f) {
                last_check = ctx.now;
                maybeTrain();
            }
            return;
        }
        if (ctx.now < lock_until)
            return;
        if (chief.getCurrentController() instanceof MagicController
                || chief.getCurrentBehaviour() instanceof com.oddlabs.tt.model.behaviour.MagicBehaviour
                || chief.getCurrentBehaviour() instanceof com.oddlabs.tt.model.behaviour.StunBehaviour)
            return;
        // A visibly casting enemy chieftain: step out of its stun first.
        float[] pending = ctx.military.pendingEnemyCastNear(chief.getGridX(), chief.getGridY());
        if (dodge && pending != null) {
            float dx = chief.getGridX() - pending[0];
            float dy = chief.getGridY() - pending[1];
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d >= 1f && d <= dodge_max) {
                // Straight away from the release point; with dodgeSmart only to a spot out of tower and warrior
                // reach, trying 30 and 60 degrees to either side before giving up the dodge.
                float[] turns = dodge_smart ? new float[]{0f, 0.52f, -0.52f, 1.05f, -1.05f} : new float[]{0f};
                for (float turn : turns) {
                    float cos = (float) StrictMath.cos(turn);
                    float sin = (float) StrictMath.sin(turn);
                    float ux = (dx * cos - dy * sin) / d;
                    float uy = (dx * sin + dy * cos) / d;
                    int tx = ctx.map.clamp(Math.round(pending[0] + ux * dodge_to));
                    int ty = ctx.map.clamp(Math.round(pending[1] + uy * dodge_to));
                    int[] acc = ctx.map.nearestAccessible(tx, ty);
                    if (acc == null)
                        continue;
                    if (dodge_smart && (nearestMannedTower(acc[0], acc[1]) < 17f
                            || nearestEnemyWarrior(acc[0], acc[1]) < 9f))
                        continue;
                    ctx.orders.dodge(chief, acc[0], acc[1], pending[2]);
                    return;
                }
            }
        }
        if (chief.canDoMagic(RacesResources.INDEX_MAGIC_STUN)) {
            if (energy_full_since < 0f)
                energy_full_since = ctx.now;
            if (tryToot(chief, chief.getHitPoints() < 20))
                return;
        } else if (!chief.canDoMagic(RacesResources.INDEX_MAGIC_STUN)) {
            energy_full_since = -1f;
        }
        position(chief);
    }

    private void maybeTrain() {
        if (ctx.owner.isTrainingChieftain() || ctx.owner.hasActiveChieftain())
            return;
        Building a = ctx.economy.armory();
        if (a == null)
            return;
        int pop = ctx.owner.getUnitCountContainer().getNumSupplies();
        boolean trigger = (ctx.model.me.quarters.size() >= chief_min_quarters && ctx.now >= chief_time)
                || ctx.model.enemy.chieftain != null || pop >= ctx.world.getMaxUnitCount() - 20;
        if (!trigger)
            return;
        Building best = null;
        float best_f = Float.MAX_VALUE;
        for (Building q : ctx.model.me.quarters) {
            if (q.isDead())
                continue;
            float f = ctx.map.frontness(q.getGridX(), q.getGridY());
            if (f < best_f) {
                best_f = f;
                best = q;
            }
        }
        if (best == null)
            return;
        ctx.orders.trainChieftain(best);
        if (ctx.owner.isTrainingChieftain()) {
            training_q = best;
            ctx.log(() -> "training chieftain");
        }
    }

    /** Weighted enemy presence within the stun radius of a point (towers count through their garrison). */
    private float tootValue(float sx, float sy) {
        float w = 0f;
        float r2 = STUN_RADIUS_CELLS * STUN_RADIUS_CELLS;
        for (Unit e : ctx.model.enemy.units) {
            float dx = e.getGridX() - sx;
            float dy = e.getGridY() - sy;
            if (dx * dx + dy * dy > r2)
                continue;
            w += switch (WorldModel.kindOf(e)) {
                case RUBBER -> 1.4f;
                case IRON -> 1.0f;
                case ROCK -> 0.6f;
                case PEON -> 0.2f;
                case CHIEFTAIN -> 3f;
            };
        }
        for (Building t : ctx.model.enemy.towers) {
            if (WorldModel.garrisonOf(t) == null)
                continue;
            float dx = t.getGridX() - sx;
            float dy = t.getGridY() - sy;
            if (dx * dx + dy * dy <= r2)
                w += toot_tower_value;
        }
        return w;
    }

    /** Cast point chosen by the planner (grid cell), valid while plan_time is recent. */
    private int plan_x;
    private int plan_y;
    private float plan_value;
    private float plan_time = Float.NEGATIVE_INFINITY;

    /**
     * Plans the toot: evaluates cast positions on the line from the chieftain toward the nearest enemy clump
     * (never closer than 9 cells to an unstunned enemy warrior) and casts once standing on the best one. The
     * stun (10-30 s, defense drops to 0) is only worth it when our warriors can exploit it, or when the enemy is
     * attacking our base.
     */
    private boolean tryToot(@NonNull Unit chief, boolean wounded) {
        int cx = chief.getGridX();
        int cy = chief.getGridY();
        Unit target = null;
        int target_d = Integer.MAX_VALUE;
        for (Unit e : ctx.model.enemy.warriors) {
            int d = MapAnalysis.dist2(e.getGridX(), e.getGridY(), cx, cy);
            if (d < target_d) {
                target_d = d;
                target = e;
            }
        }
        for (Building t : ctx.model.enemy.towers) {
            if (WorldModel.garrisonOf(t) == null)
                continue;
            int d = MapAnalysis.dist2(t.getGridX(), t.getGridY(), cx, cy);
            if (d < target_d) {
                target_d = d;
                target = null;
                plan_x = t.getGridX();
                plan_y = t.getGridY();
            }
        }
        if (target_d > 34 * 34)
            return false;
        int tx = target != null ? target.getGridX() : plan_x;
        int ty = target != null ? target.getGridY() : plan_y;
        float th = theta;
        if (energy_full_since >= 0f && ctx.now - energy_full_since > 30f)
            th = theta * 0.7f;
        if (ctx.military.stunSieging())
            th = Math.min(th, 0.9f * toot_tower_value);
        // Right after the enemy chieftain's (visible) cast its stun is spent: our cast meets no counter-stun.
        float since_enemy = ctx.now - ctx.military.lastEnemyCast();
        if (toot_after_enemy && since_enemy > 3.9f && since_enemy < 35f)
            th = Math.min(th, 0.7f * theta);
        boolean base_attacked = ctx.military.isDefending();
        Unit enemy_chief = ctx.model.enemy.chieftain;
        if (enemy_chief != null && !enemy_chief.isDead()
                && MapAnalysis.chebyshev(enemy_chief.getGridX(), enemy_chief.getGridY(), cx, cy) <= 30)
            th *= preempt;

        // Candidate cast points: from here toward the target in 2-cell steps.
        float dx = tx - cx;
        float dy = ty - cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float best_value = -1f;
        int best_x = cx;
        int best_y = cy;
        for (int step = 0; step <= 16; step += 2) {
            int px = len < 1f ? cx : Math.round(cx + dx / len * step);
            int py = len < 1f ? cy : Math.round(cy + dy / len * step);
            if (step > 0 && (wounded || !ctx.map.isAccessible(px, py)))
                break;
            if (nearestEnemyWarrior(px, py) < 9f && (step > 0 || !(close_cast || wounded)))
                break;
            if (step > 0 && nearestMannedTower(px, py) < 15.5f)
                break;
            // The stun is centred slightly ahead of the chieftain, in its walking direction.
            float sx = len < 1f ? px : px + dx / len * 1.3f;
            float sy = len < 1f ? py : py + dy / len * 1.3f;
            float v = tootValue(sx, sy) - 0.02f * step;
            if (v > best_value) {
                best_value = v;
                best_x = px;
                best_y = py;
            }
        }
        if (best_value < th)
            return false;
        int ours = 0;
        for (Unit w : ctx.model.me.warriors) {
            if (MapAnalysis.dist2(w.getGridX(), w.getGridY(), best_x, best_y) <= 20 * 20)
                ours++;
        }
        if (ours < 3 && base_attacked && toot_base_cover && !baseCastCovered(chief, best_x, best_y))
            return false;
        if (ours < 3 && !base_attacked)
            return false;
        plan_x = best_x;
        plan_y = best_y;
        plan_value = best_value;
        plan_time = ctx.now;
        if (MapAnalysis.chebyshev(cx, cy, best_x, best_y) <= 1) {
            if (ctx.orders.magic(chief, RacesResources.INDEX_MAGIC_STUN)) {
                lock_until = ctx.now + 6.3f;
                energy_full_since = -1f;
                float v = best_value;
                int o = ours;
                ctx.log(() -> String.format("TOOT value=%.1f ours=%d at %d,%d", v, o, cx, cy));
                ctx.military.onToot(cx, cy);
                return true;
            }
            return false;
        }
        if (!ctx.orders.recently(chief, Orders.Kind.MOVE, null, best_x, best_y, 1.5f))
            ctx.orders.move(chief, best_x, best_y);
        return true;
    }

    /**
     * A base-defence stun with hardly any of our warriors near only pays off under our own manned tower, or to save
     * a wounded chieftain from an enemy warrior next to it.
     */
    private boolean baseCastCovered(@NonNull Unit chief, int x, int y) {
        for (Building t : ctx.model.me.towers) {
            if (!t.isDead() && WorldModel.garrisonOf(t) != null
                    && MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= 14 * 14)
                return true;
        }
        return chief.getHitPoints() < 20 && nearestEnemyWarrior(chief.getGridX(), chief.getGridY()) < 8f;
    }

    /** Distance (cells) to the nearest enemy tower whose garrison can shoot. */
    private float nearestMannedTower(int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Building t : ctx.model.enemy.towers) {
            if (!t.isDead() && CombatModel.towerValue(t) > 0f)
                best = Math.min(best, MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y));
        }
        return best == Integer.MAX_VALUE ? Float.MAX_VALUE : (float) Math.sqrt(best);
    }

    private float nearestEnemyWarrior(int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Unit e : ctx.model.enemy.warriors) {
            if (WorldModel.isStunned(e))
                continue;
            best = Math.min(best, MapAnalysis.dist2(e.getGridX(), e.getGridY(), x, y));
        }
        return best == Integer.MAX_VALUE ? Float.MAX_VALUE : (float) Math.sqrt(best);
    }

    private void position(@NonNull Unit chief) {
        int cx = chief.getGridX();
        int cy = chief.getGridY();
        // Never let the chieftain idle or chase: it melees and dies.
        Unit nearest = null;
        int nearest_d = Integer.MAX_VALUE;
        for (Unit e : ctx.model.enemy.warriors) {
            int d = MapAnalysis.dist2(e.getGridX(), e.getGridY(), cx, cy);
            if (d < nearest_d) {
                nearest_d = d;
                nearest = e;
            }
        }
        boolean near_enemy = nearest_d <= 9 * 9;
        int[] dest = escortPoint();
        if (chief.getHitPoints() < 25 && !ctx.military.isDefending())
            dest = ctx.military.rallyPoint() != null ? ctx.military.rallyPoint() : dest;
        boolean hunting = chief.getCurrentController() instanceof HuntController;
        boolean idle = chief.getCurrentController() instanceof IdleController;
        int off = MapAnalysis.chebyshev(cx, cy, dest[0], dest[1]);
        if (hunting || (near_enemy && !ctx.orders.recently(chief, 1.5f)) || (off > 4 && !ctx.orders.recently(chief,
                Orders.Kind.MOVE, null, dest[0], dest[1], 3f)) || (idle && off > 2 && !ctx.orders.recently(chief,
                        2f))) {
            if (near_enemy)
                dest = retreatPoint(cx, cy, nearest);
            ctx.orders.move(chief, dest[0], dest[1]);
        }
    }

    private int @NonNull [] escortPoint() {
        int[] a = ctx.military.squadAnchor();
        int[] home = ctx.military.rallyPoint();
        if (a == null) {
            // Defending: stand behind the defenders, facing the attackers, so the stun reaches them first.
            int[] f = ctx.military.defenseFront();
            if (f != null) {
                int dx = f[0] - f[2];
                int dy = f[1] - f[3];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                int x = len < 1f ? f[0] : Math.round(f[0] + dx / len * 6f);
                int y = len < 1f ? f[1] : Math.round(f[1] + dy / len * 6f);
                int[] acc = ctx.map.nearestAccessible(ctx.map.clamp(x), ctx.map.clamp(y));
                if (acc != null)
                    return acc;
            }
            return home != null ? home : new int[]{ctx.map.home_x, ctx.map.home_y};
        }
        int hx = home != null ? home[0] : ctx.map.home_x;
        int hy = home != null ? home[1] : ctx.map.home_y;
        int dx = hx - a[0];
        int dy = hy - a[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f)
            return a;
        int x = Math.round(a[0] + dx / len * 7f);
        int y = Math.round(a[1] + dy / len * 7f);
        int[] acc = ctx.map.nearestAccessible(ctx.map.clamp(x), ctx.map.clamp(y));
        return acc != null ? acc : a;
    }

    private int @NonNull [] retreatPoint(int cx, int cy, @Nullable Unit threat) {
        if (threat == null)
            return new int[]{cx, cy};
        int dx = cx - threat.getGridX();
        int dy = cy - threat.getGridY();
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f)
            return escortPoint();
        int x = Math.round(cx + dx / len * 10f);
        int y = Math.round(cy + dy / len * 10f);
        int[] acc = ctx.map.nearestAccessible(ctx.map.clamp(x), ctx.map.clamp(y));
        return acc != null ? acc : new int[]{cx, cy};
    }
}
