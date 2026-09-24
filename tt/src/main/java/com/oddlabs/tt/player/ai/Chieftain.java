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
    /** Cells from an active enemy tower the chieftain keeps: out of its throws (16 cells), inside the stun's reach. */
    private static final int TOWER_KEEP = 17;
    /** Squared cells within which a tower is caught by the stun from the standoff. */
    private static final int TOWER_CAUGHT2 = 300;

    private final @NonNull ExpertAI ai;
    private float last_move = -100f;
    private float last_cast = -100f;
    /** Enemy warriors in reach when the last stun was cast, checked once it has gone off. */
    private final java.util.List<@NonNull Unit> cast_candidates = new java.util.ArrayList<>();
    private float cast_check = -1f;
    /** Most of the candidates seen stunned so far: the stun may go off late if the chieftain was mid-swing. */
    private int cast_best;
    /** Running share of the enemies in reach that our stuns caught; low against an enemy who runs from the horn. */
    private float catch_rate = 1f;

    Chieftain(@NonNull ExpertAI ai) {
        this.ai = ai;
    }

    private int magicIndex() {
        if (ai.owner().getRace() == ai.owner().getWorld().getRacesResources().getRace(RacesResources.RACE_VIKINGS))
            return RacesResources.INDEX_MAGIC_STUN;
        return ai.strategy().native_lightning ? RacesResources.INDEX_MAGIC_LIGHTNING : RacesResources.INDEX_MAGIC_POISON;
    }

    /** Seconds since the chieftain last cast, or a large number. */
    float sinceCast() {
        return ai.time() - last_cast;
    }

    private boolean isViking() {
        return ai.owner().getRace() == ai.owner().getWorld().getRacesResources().getRace(RacesResources.RACE_VIKINGS);
    }

    /**
     * Whether a sonic blast now kills far more of theirs than of ours. It reaches 36 m from a point just in front of
     * the chieftain and hits friend and foe alike; count ours a little further out, as they keep moving.
     */
    private boolean shouldBlast(@NonNull Unit chief) {
        Intel intel = ai.intel();
        int x = chief.getGridX();
        int y = chief.getGridY();
        float theirs = 0f;
        for (Unit e : intel.enemy_warriors)
            if (MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 16 * 16)
                theirs += Combat.value(e);
        for (Unit e : intel.enemy_peons)
            if (MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 16 * 16)
                theirs += .3f;
        float ours = 0f;
        for (Unit u : intel.warriors)
            if (!u.isMounted() && MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()) <= 20 * 20)
                ours += Combat.value(u);
        for (Unit u : intel.peons)
            if (MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()) <= 20 * 20)
                ours += .3f;
        boolean go = theirs >= ai.strategy().blast_min && theirs >= ai.strategy().blast_ratio * ours;
        if (go)
            ai.log(String.format("chieftain blasts at %d,%d: %.1f of theirs against %.1f of ours", x, y, theirs,
                    ours));
        return go;
    }

    /** Whether the chieftain could blow the sonic blast now, and is fit to walk up to an enemy army for it. */
    boolean blastReady() {
        Unit chief = ai.intel().chieftain;
        return chief != null && !chief.isDead() && isViking() && chief.getHitPoints() > 40
                && chief.canDoMagic(RacesResources.INDEX_MAGIC_BLAST) && !Intel.isStunned(chief);
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
        checkCatch();
        Unit chief = ai.intel().chieftain;
        if (chief == null) {
            considerTraining();
            return;
        }
        if (Intel.isStunned(chief))
            return;
        if (ai.strategy().blast && isViking() && chief.canDoMagic(RacesResources.INDEX_MAGIC_BLAST)
                && shouldBlast(chief)) {
            ai.owner().doMagic(chief, RacesResources.INDEX_MAGIC_BLAST);
            last_cast = ai.time();
            return;
        }
        if (stunReady() && shouldStun(chief)) {
            int x = chief.getGridX();
            int y = chief.getGridY();
            String rivals = "";
            for (Unit e : ai.intel().enemy_chieftains)
                if (!e.isDead())
                    rivals += String.format(" [enemy chief %d cells%s%s]", (int) Math.sqrt(MapAnalysis.dist2(x, y,
                            e.getGridX(), e.getGridY())),
                            e.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.MagicController ? " casting" : "",
                            ai.military().enemySpellReady(e) ? " ready" : "");
            ai.log("chieftain stuns at " + x + "," + y + ": " + Combat.countNear(ai.intel().enemy_warriors, x, y,
                    17) + " enemy warriors in reach" + rivals);
            ai.owner().doMagic(chief, magicIndex());
            last_cast = ai.time();
            if (isViking()) {
                cast_candidates.clear();
                for (Unit e : ai.intel().enemy_warriors)
                    if (!Intel.isStunned(e) && MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 17 * 17)
                        cast_candidates.add(e);
                cast_check = ai.time() + 8f;
                cast_best = 0;
            }
            return;
        }
        position(chief);
    }

    /** While our stun goes off, counts how many of the enemies in reach it caught, at best. */
    private void checkCatch() {
        if (cast_check < 0f)
            return;
        int alive = 0;
        int caught = 0;
        for (Unit e : cast_candidates) {
            if (e.isDead())
                continue;
            alive++;
            if (Intel.isStunned(e))
                caught++;
        }
        cast_best = Math.max(cast_best, caught);
        if (ai.time() < cast_check)
            return;
        cast_check = -1f;
        caught = Math.min(alive, cast_best);
        cast_candidates.clear();
        Unit chief = ai.intel().chieftain;
        String state = chief == null || chief.isDead() ? " (our chief died)" : Intel.isStunned(
                chief) ? " (our chief stunned)" : "";
        if (alive < 4) {
            if (!state.isEmpty())
                ai.log("our stun: " + state.trim());
            return;
        }
        float share = caught / (float) alive;
        catch_rate = .6f * catch_rate + .4f * share;
        ai.log(String.format("our stun caught %d of %d in reach (running share %.2f)%s", caught, alive, catch_rate,
                state));
    }

    /** Whether the enemy has been seen running from our stun. */
    boolean enemyDodges() {
        return ai.strategy().stun_learn && catch_rate < ai.strategy().dodge_catch;
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
        float warriors = 0f;
        // Against an enemy who runs from the horn only the ones too close to get away count in full.
        boolean dodges = enemyDodges();
        int core2 = ai.strategy().dodge_core * ai.strategy().dodge_core;
        for (Unit e : intel.enemy_warriors) {
            if (Intel.isStunned(e))
                continue;
            int d2 = MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY());
            if (d2 <= r2)
                warriors += !dodges || d2 <= core2 ? 1f : .25f;
        }
        int towers = 0;
        int tower_r2 = ai.strategy().chief_tower_standoff ? TOWER_CAUGHT2 : r2;
        // A stunned tower is only worth it with an army at hand to pull it down.
        int[] army = ai.military().attackCenter();
        boolean follow_up = !ai.strategy().tower_stun_follow_up
                || (army != null && MapAnalysis.dist2(x, y, army[0], army[1]) <= 25 * 25);
        for (Building t : intel.enemy_towers)
            if (follow_up && Intel.isTowerActive(t) && MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY()) <= tower_r2)
                towers++;
        int chiefs = 0;
        for (Unit e : intel.enemy_chieftains) {
            if (Intel.isStunned(e) || MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) > STUN_REACH * STUN_REACH)
                continue;
            chiefs++;
            // Whoever stuns first wins: a stunned chieftain cannot answer with his own spell.
            if (ai.military().enemySpellReady(e))
                return true;
        }
        // Laying a siege, one tower from the standoff is what the army has been waiting for.
        if (towers > 0 && ai.military().sieging())
            return true;
        float caught = warriors + 3f * towers + 3f * chiefs;
        // A wounded chieftain stuns whatever is on him before he dies.
        if (chief.getHitPoints() <= 20 && caught >= 1f)
            return true;
        // Saving up for the blast: past half its charge the stun would throw it away.
        if (ai.strategy().blast && ai.strategy().blast_save
                && chief.getMagicProgress(RacesResources.INDEX_MAGIC_BLAST) >= .5f)
            return false;
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
        float coming = warriors + Combat.countNear(intel.enemy_warriors, x, y, 32) - Combat.countNear(
                intel.enemy_warriors, x, y, STUN_CELLS);
        float share = caught / Math.max(1f, coming + 3f * towers + 3f * chiefs);
        boolean answer = false;
        for (Unit e : intel.enemy_chieftains)
            answer |= !Intel.isStunned(e) && MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 45 * 45
                    && ai.military().enemySpellReady(e);
        if (answer)
            return caught >= 20f || (share >= .8f && caught >= 10f);
        return caught >= 20f || share >= .55f;
    }

    private void position(@NonNull Unit chief) {
        int safe = ai.strategy().chief_safe;
        boolean threatened = safe > 0 && !stunReady()
                && nearestWarriorDistance(chief.getGridX(), chief.getGridY()) <= safe;
        if ((ai.time() - last_move < MOVE_PERIOD && !threatened) || ai.military().isDodging(chief))
            return;
        Military military = ai.military();
        int[] army = military.attackCenter();
        int[] enemies = nearestEnemies(chief.getGridX(), chief.getGridY(), 30);
        int tx;
        int ty;
        if (chief.getHitPoints() <= ai.strategy().chief_flee_hp) {
            Building armory = ai.intel().armory();
            tx = armory != null ? armory.getGridX() : military.stagingX();
            ty = armory != null ? armory.getGridY() : military.stagingY();
        } else if (ai.military().blastPlay() != null) {
            // Meet the enemy army alone: walk up to just outside its throws; the blast goes off once it is worth it.
            int[] at = ai.military().blastPlay();
            int nearest = nearestWarriorDistance(chief.getGridX(), chief.getGridY());
            if (nearest <= 9) {
                tx = chief.getGridX();
                ty = chief.getGridY();
            } else {
                int[] stop = towards(chief.getGridX(), chief.getGridY(), at[0], at[1], Math.max(3, nearest - 9));
                tx = stop[0];
                ty = stop[1];
            }
        } else if (enemies != null && stunReady()) {
            // Walk into stun range of the nearest enemies; the stun goes off as soon as enough are caught. Stop short
            // of their throws: the radius reaches well past them.
            tx = enemies[0];
            ty = enemies[1];
            int keep = ai.strategy().chief_keep_out;
            if (keep > 0) {
                int nearest = nearestWarriorDistance(chief.getGridX(), chief.getGridY());
                if (nearest <= keep + 1) {
                    tx = chief.getGridX();
                    ty = chief.getGridY();
                } else {
                    int[] stop = towards(chief.getGridX(), chief.getGridY(), enemies[0], enemies[1], nearest - keep);
                    tx = stop[0];
                    ty = stop[1];
                }
            }
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
        if (safe > 0 && !stunReady()) {
            int[] away = awayFromWarriors(tx, ty, safe + 1);
            tx = away[0];
            ty = away[1];
        }
        if (ai.strategy().chief_tower_standoff && chief.getHitPoints() > 24) {
            int[] clear = clearOfTowers(tx, ty, chief.getGridX(), chief.getGridY());
            tx = clear[0];
            ty = clear[1];
        }
        if (MapAnalysis.dist2(chief.getGridX(), chief.getGridY(), tx, ty) <= 3 * 3)
            return;
        last_move = ai.time();
        ai.owner().setLandscapeTarget(Selectable.newArray(chief), tx, ty, Action.MOVE, false);
    }

    /**
     * Moves a destination out to TOWER_KEEP cells from active enemy towers, away from the tower or, for a point on
     * it, back towards (from_x, from_y).
     */
    private int @NonNull [] clearOfTowers(int x, int y, int from_x, int from_y) {
        for (int pass = 0; pass < 3; pass++) {
            Building near = null;
            int best = TOWER_KEEP * TOWER_KEEP;
            for (Building t : ai.intel().enemy_towers) {
                int d = MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY());
                if (Intel.isTowerActive(t) && d < best) {
                    best = d;
                    near = t;
                }
            }
            if (near == null)
                break;
            float dx = x - near.getGridX();
            float dy = y - near.getGridY();
            if (dx * dx + dy * dy < 1f) {
                dx = from_x - near.getGridX();
                dy = from_y - near.getGridY();
            }
            float len = Math.max(.1f, (float) Math.sqrt(dx * dx + dy * dy));
            x = near.getGridX() + Math.round(dx / len * TOWER_KEEP);
            y = near.getGridY() + Math.round(dy / len * TOWER_KEEP);
        }
        int size = ai.map().getSize();
        return new int[]{Math.clamp(x, 0, size - 1), Math.clamp(y, 0, size - 1)};
    }

    /** Moves a point out to `keep` cells from the enemy warriors nearest to it, a few passes deep. */
    private int @NonNull [] awayFromWarriors(int x, int y, int keep) {
        for (int pass = 0; pass < 3; pass++) {
            Unit near = null;
            int best = keep * keep;
            for (Unit e : ai.intel().enemy_warriors) {
                if (Intel.isStunned(e))
                    continue;
                int d = MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY());
                if (d < best) {
                    best = d;
                    near = e;
                }
            }
            if (near == null)
                break;
            float dx = x - near.getGridX();
            float dy = y - near.getGridY();
            if (dx * dx + dy * dy < 1f) {
                dx = ai.military().stagingX() - near.getGridX();
                dy = ai.military().stagingY() - near.getGridY();
            }
            float len = Math.max(.1f, (float) Math.sqrt(dx * dx + dy * dy));
            x = near.getGridX() + Math.round(dx / len * keep);
            y = near.getGridY() + Math.round(dy / len * keep);
        }
        int size = ai.map().getSize();
        return new int[]{Math.clamp(x, 0, size - 1), Math.clamp(y, 0, size - 1)};
    }

    /** Cells to the nearest enemy warrior that is not stunned, or a large number. */
    private int nearestWarriorDistance(int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Unit e : ai.intel().enemy_warriors)
            if (!Intel.isStunned(e))
                best = Math.min(best, MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()));
        return best == Integer.MAX_VALUE ? 1000 : (int) Math.sqrt(best);
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
