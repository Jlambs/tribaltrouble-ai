package com.oddlabs.tt.player.ai;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Trains the chieftain once the economy can spare a quarters for it, keeps him near the fighting but out of reach,
 * and blows the stun (the viking toot) when it catches enough enemies, towers included, to decide the fight.
 */
final class Chieftain {
    /** Stun radius is 36 m (18 cells); count warriors a little inside that, as they keep moving. */
    private static final int STUN_CELLS = 16;
    /**
     * An enemy chieftain inside the radius (less the few meters he walks during the wind-up) is caught before he casts.
     */
    private static final int STUN_REACH = 17;
    private static final float MOVE_PERIOD = 1.5f;

    private final @NonNull ExpertAI ai;
    private float last_move = -100f;

    Chieftain(@NonNull ExpertAI ai) {
        this.ai = ai;
    }

    private int magicIndex() {
        return ai.owner().getRace() == ai.owner().getWorld().getRacesResources().getRace(
                RacesResources.RACE_VIKINGS) ? RacesResources.INDEX_MAGIC_STUN : RacesResources.INDEX_MAGIC_POISON;
    }

    boolean stunReady() {
        Unit chief = ai.intel().chieftain;
        return chief != null && !chief.isDead() && chief.canDoMagic(magicIndex());
    }

    @Nullable
    Building trainingQuarters() {
        for (Building q : ai.intel().quarters) {
            if (q.getChieftainContainer() != null && q.getChieftainContainer().isTraining())
                return q;
        }
        return null;
    }

    void tick() {
        Unit chief = ai.intel().chieftain;
        if (chief == null) {
            considerTraining();
            return;
        }
        if (Intel.isStunned(chief))
            return;
        if (stunReady() && shouldStun(chief)) {
            ai.log("chieftain stuns at " + chief.getGridX() + "," + chief.getGridY());
            ai.owner().doMagic(chief, magicIndex());
            return;
        }
        position(chief);
    }

    private void considerTraining() {
        Strategy strategy = ai.strategy();
        Intel intel = ai.intel();
        if (ai.owner().isTrainingChieftain() || !ai.owner().canBuildChieftains())
            return;
        if (intel.quarters.size() < strategy.chieftain_min_quarters || ai.time() < strategy.chieftain_time)
            return;
        if (intel.armory() == null)
            return;
        Building best = null;
        float best_score = -Float.MAX_VALUE;
        for (Building q : intel.quarters) {
            if (!q.canBuildChieftain())
                continue;
            float score = q.getUnitContainer().getNumSupplies() - 20f * ai.planner().exposure(q.getGridX(),
                    q.getGridY());
            if (score > best_score) {
                best_score = score;
                best = q;
            }
        }
        if (best != null) {
            ai.log("training chieftain in quarters at " + best.getGridX() + "," + best.getGridY());
            ai.owner().trainChieftain(best, true);
        }
    }

    private boolean shouldStun(@NonNull Unit chief) {
        Intel intel = ai.intel();
        int x = chief.getGridX();
        int y = chief.getGridY();
        int r2 = STUN_CELLS * STUN_CELLS;
        int warriors = 0;
        for (Unit e : intel.enemy_warriors)
            if (!Intel.isStunned(e) && MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= r2)
                warriors++;
        int towers = 0;
        for (Building t : intel.enemy_towers)
            if (Intel.isTowerActive(t) && MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY()) <= r2)
                towers++;
        int chiefs = 0;
        for (Unit e : intel.enemy_chieftains) {
            if (Intel.isStunned(e) || MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) > STUN_REACH * STUN_REACH)
                continue;
            chiefs++;
            // Whoever stuns first wins: a stunned chieftain cannot answer with his own spell.
            if (e.getMagicProgress(0) >= 1f || e.getMagicProgress(1) >= 1f)
                return true;
        }
        float caught = warriors + 3f * towers + 3f * chiefs;
        // A wounded chieftain stuns whatever is on him before he dies.
        if (chief.getHitPoints() <= 20 && caught >= 1f)
            return true;
        // With our own warriors at hand to cut down the stunned, a smaller catch is already worth it.
        int ours = Combat.countNear(intel.warriors, x, y, 20);
        float needed = ours >= 6 ? 4f : 6f;
        if (caught < needed)
            return false;
        if (!ai.strategy().stun_patience)
            return true;
        // The stun comes back only after 40 s: spent on the first few of a big army, the rest walk in unhindered.
        // Wait until most of what is closing in is inside, and while an enemy chieftain who could answer is on his
        // way, keep it to catch him too or to answer his spell.
        int coming = warriors + Combat.countNear(intel.enemy_warriors, x, y, 32) - Combat.countNear(
                intel.enemy_warriors, x, y, STUN_CELLS);
        float share = caught / Math.max(1f, coming + 3f * towers + 3f * chiefs);
        boolean answer = false;
        for (Unit e : intel.enemy_chieftains)
            answer |= !Intel.isStunned(e) && MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 45 * 45
                    && (e.getMagicProgress(0) >= 1f || e.getMagicProgress(1) >= 1f);
        if (answer)
            return caught >= 20f || (share >= .8f && caught >= 10f);
        return caught >= 20f || share >= .55f;
    }

    private void position(@NonNull Unit chief) {
        if (ai.time() - last_move < MOVE_PERIOD)
            return;
        Military military = ai.military();
        int[] army = military.attackCenter();
        int[] enemies = nearestEnemies(chief.getGridX(), chief.getGridY(), 30);
        int tx;
        int ty;
        if (chief.getHitPoints() <= 24) {
            Building armory = ai.intel().armory();
            tx = armory != null ? armory.getGridX() : military.stagingX();
            ty = armory != null ? armory.getGridY() : military.stagingY();
        } else if (enemies != null && stunReady()) {
            // Walk into stun range of the nearest enemies; the stun goes off as soon as enough are caught.
            tx = enemies[0];
            ty = enemies[1];
        } else if (army != null) {
            // March inside the clump, a little behind its middle.
            int[] back = towards(army[0], army[1], military.stagingX(), military.stagingY(), enemies != null ? 8 : 3);
            tx = back[0];
            ty = back[1];
        } else if (military.baseThreatLevel() > 0) {
            int[] back = towards(military.threatX(), military.threatY(), military.stagingX(), military.stagingY(),
                    12);
            tx = back[0];
            ty = back[1];
        } else {
            tx = military.stagingX();
            ty = military.stagingY();
        }
        if (MapAnalysis.dist2(chief.getGridX(), chief.getGridY(), tx, ty) <= 3 * 3)
            return;
        last_move = ai.time();
        ai.owner().setLandscapeTarget(Selectable.newArray(chief), tx, ty, Action.MOVE, false);
    }

    /** The point `cells` away from (x, y) in the direction of (to_x, to_y). */
    private static int @NonNull [] towards(int x, int y, int to_x, int to_y, int cells) {
        float dx = to_x - x;
        float dy = to_y - y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= cells)
            return new int[]{to_x, to_y};
        return new int[]{x + (int) (dx / len * cells), y + (int) (dy / len * cells)};
    }

    /** Middle of the enemy warriors and manned towers around the nearest one within radius cells, or null. */
    private int @Nullable [] nearestEnemies(int x, int y, int radius) {
        Intel intel = ai.intel();
        Selectable<?> nearest = null;
        int best = radius * radius;
        for (Unit e : intel.enemy_warriors) {
            if (Intel.isStunned(e))
                continue;
            int d = MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY());
            if (d < best) {
                best = d;
                nearest = e;
            }
        }
        for (Building t : intel.enemy_towers) {
            if (!Intel.isTowerActive(t))
                continue;
            int d = MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY());
            if (d < best) {
                best = d;
                nearest = t;
            }
        }
        if (nearest == null)
            return null;
        long sx = 0;
        long sy = 0;
        int n = 0;
        for (Unit e : intel.enemy_warriors) {
            if (MapAnalysis.dist2(nearest.getGridX(), nearest.getGridY(), e.getGridX(), e.getGridY()) <= 12 * 12) {
                sx += e.getGridX();
                sy += e.getGridY();
                n++;
            }
        }
        if (n == 0)
            return new int[]{nearest.getGridX(), nearest.getGridY()};
        return new int[]{(int) (sx / n), (int) (sy / n)};
    }
}
