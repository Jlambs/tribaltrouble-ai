package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.MagicController;
import com.oddlabs.tt.model.behaviour.StunController;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The chieftain: walks with the biggest body of our warriors on his island, keeps out of enemy towers' throws, and
 * casts when the spell catches enough. Vikings stun (36 m, ~16 cells counted) or blast; natives call lightning or
 * poison.
 * Both spells share one charge.
 */
final class Chief {
    private final @NonNull CorsairAI ai;
    private final @NonNull Player me;
    private final boolean viking;
    private int last_move = -100;
    /** blast_adapt: the enemy warriors our last stun should have caught, and when to look whether it did. */
    private final java.util.List<@NonNull Unit> stun_victims = new java.util.ArrayList<>();
    private int stun_check = -1;
    /** Share of the enemies in reach that our stuns caught (an enemy who cancels stuns brings it down). */
    private float catch_rate = 1f;

    Chief(@NonNull CorsairAI ai) {
        this.ai = ai;
        this.me = ai.me();
        viking = me.getPlayerInfo().getRace() == RacesResources.RACE_VIKINGS;
    }

    static boolean isStunned(@NonNull Unit u) {
        return u.getCurrentController() instanceof StunController;
    }

    void think(@NonNull Intel in, int second) {
        Unit chief = in.chief;
        if (chief == null || chief.isDead() || chief.isMounted() || ai.reserved(chief))
            return;
        if (isStunned(chief) || chief.getCurrentController() instanceof MagicController)
            return;
        if (cast(in, chief, second))
            return;
        position(in, chief, second);
    }

    /** blast_adapt: 4 s after a stun, the share of its expected victims that stand stunned, into catch_rate. */
    private void checkStun(int second) {
        if (stun_check < 0 || second < stun_check)
            return;
        stun_check = -1;
        int alive = 0, stunned = 0;
        for (Unit e : stun_victims) {
            if (e.isDead())
                continue;
            alive++;
            if (isStunned(e))
                stunned++;
        }
        stun_victims.clear();
        if (alive == 0)
            return;
        catch_rate = .5f * catch_rate + .5f * stunned / alive;
        float r = catch_rate;
        ai.log.log("CHIEF", () -> "stun caught " + r + " (running)");
    }

    /** Counts enemy warriors (chieftains count 3) within r cells of the chieftain, not already stunned. */
    private float caught(@NonNull Intel in, @NonNull Unit chief, int r) {
        float n = 0;
        long r2 = (long) r * r;
        for (Unit e : in.enemy_warriors) {
            if (e.isDead() || isStunned(e))
                continue;
            if (Geo.dist2(e.getGridX(), e.getGridY(), chief.getGridX(), chief.getGridY()) <= r2)
                n += e.isChieftain() ? 3f : 1f;
        }
        return n;
    }

    private int oursNear(@NonNull Intel in, @NonNull Unit chief, int r) {
        int n = 0;
        long r2 = (long) r * r;
        for (Unit w : in.warriors)
            if (Geo.dist2(w.getGridX(), w.getGridY(), chief.getGridX(), chief.getGridY()) <= r2)
                n++;
        return n;
    }

    private boolean cast(@NonNull Intel in, @NonNull Unit chief, int second) {
        if (viking && ai.params.blast_adapt)
            checkStun(second);
        if (viking && ai.params.blast_adapt && catch_rate < .3f) {
            // the enemy cancels our stuns: the blast hurts whether he orders his units or not, friend and foe within
            // 36 m of a point before the chieftain, so only where enemies are thick and ours are not
            if (!chief.canDoMagic(RacesResources.INDEX_MAGIC_BLAST))
                return false;
            float theirs = 0;
            for (Unit e : in.enemy_warriors)
                if (Geo.dist2(e.getGridX(), e.getGridY(), chief.getGridX(), chief.getGridY()) <= 16 * 16)
                    theirs += Intel.value(e);
            float ours = 0;
            for (Unit u : in.warriors)
                if (Geo.dist2(u.getGridX(), u.getGridY(), chief.getGridX(), chief.getGridY()) <= 20 * 20)
                    ours += Intel.value(u);
            if (theirs < 5f || ours > .4f * theirs)
                return false;
            me.doMagic(chief, RacesResources.INDEX_MAGIC_BLAST);
            ai.log.count("blast");
            float t = theirs, o = ours;
            ai.log.log("CHIEF", () -> "blast catching " + t + " of theirs and " + o + " of ours t" + second);
            return true;
        }
        if (viking) {
            if (!chief.canDoMagic(RacesResources.INDEX_MAGIC_STUN))
                return false;
            float c = caught(in, chief, 16);
            int ours = oursNear(in, chief, 20);
            float need = ours >= 6 ? 4f : 7f;
            if (chief.getHitPoints() <= 20 && c >= 1)
                need = 1f;
            if (c < need)
                return false;
            me.doMagic(chief, RacesResources.INDEX_MAGIC_STUN);
            ai.log.count("stun");
            if (ai.params.blast_adapt) {
                stun_victims.clear();
                for (Unit e : in.enemy_warriors)
                    if (!isStunned(e) && Geo.dist2(e.getGridX(), e.getGridY(), chief.getGridX(), chief.getGridY())
                            <= 16 * 16)
                        stun_victims.add(e);
                stun_check = second + 4;
            }
            ai.log.log("CHIEF",
                    () -> "stun at " + chief.getGridX() + "," + chief.getGridY() + " catching " + c + " with " + ours + " of ours t" + second);
            return true;
        }
        if (!chief.canDoMagic(RacesResources.INDEX_MAGIC_LIGHTNING))
            return false;
        float near = caught(in, chief, 12);
        if (near >= 6 && chief.canDoMagic(RacesResources.INDEX_MAGIC_POISON)) {
            me.doMagic(chief, RacesResources.INDEX_MAGIC_POISON);
            ai.log.count("poison");
            return true;
        }
        if (near >= 3) {
            me.doMagic(chief, RacesResources.INDEX_MAGIC_LIGHTNING);
            ai.log.count("lightning");
            return true;
        }
        return false;
    }

    /** With the largest group on his island, a few cells behind its middle; at home, between base and dock. */
    private void position(@NonNull Intel in, @NonNull Unit chief, int second) {
        if (second - last_move < 2)
            return;
        int island = chief.getIslandId();
        Army.Group best = null;
        for (Army.Group g : ai.army.groups)
            if (g.island == island && (best == null || g.members.size() > best.members.size()))
                best = g;
        int tx, ty;
        if (best != null && !best.members.isEmpty()) {
            long sx = 0, sy = 0;
            for (Unit w : best.members) {
                sx += w.getGridX();
                sy += w.getGridY();
            }
            int cx = (int) (sx / best.members.size()), cy = (int) (sy / best.members.size());
            // walk into stun reach of enemies near the body when charged; else a little behind the middle
            Unit e = nearestEnemyWarrior(in, cx, cy, 30);
            if (e != null && chief.canDoMagic(
                    viking ? RacesResources.INDEX_MAGIC_STUN : RacesResources.INDEX_MAGIC_LIGHTNING)) {
                tx = (cx + e.getGridX()) / 2;
                ty = (cy + e.getGridY()) / 2;
            } else {
                int d = Geo.dist(cx, cy, best.tx, best.ty);
                tx = d == 0 ? cx : cx - (best.tx - cx) * 5 / d;
                ty = d == 0 ? cy : cy - (best.ty - cy) * 5 / d;
            }
        } else if (island == ai.colony.island) {
            Unit e = nearestEnemyWarrior(in, ai.colony.ax, ai.colony.ay, 50);
            if (e != null && chief.canDoMagic(
                    viking ? RacesResources.INDEX_MAGIC_STUN : RacesResources.INDEX_MAGIC_LIGHTNING)) {
                tx = e.getGridX();
                ty = e.getGridY();
            } else {
                int[] dock = ai.fleet.homeDock();
                tx = (ai.colony.ax * 2 + dock[0]) / 3;
                ty = (ai.colony.ay * 2 + dock[1]) / 3;
            }
        } else {
            return;
        }
        int[] clear = clearOfTowers(in, tx, ty, chief.getGridX(), chief.getGridY());
        tx = clear[0];
        ty = clear[1];
        if (Geo.dist2(chief.getGridX(), chief.getGridY(), tx, ty) <= 9)
            return;
        last_move = second;
        ai.move(java.util.List.of(chief), tx, ty, Action.MOVE, false);
    }

    private @Nullable Unit nearestEnemyWarrior(@NonNull Intel in, int x, int y, int r) {
        Unit best = null;
        long bd = (long) r * r + 1;
        for (Unit e : in.enemy_warriors) {
            long d = Geo.dist2(e.getGridX(), e.getGridY(), x, y);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    /** Moves a point out to 17 cells from enemy towers (their throws reach ~16), back towards (fx, fy). */
    private int @NonNull [] clearOfTowers(@NonNull Intel in, int x, int y, int fx, int fy) {
        for (int pass = 0; pass < 3; pass++) {
            Building near = null;
            for (Building t : in.enemy_buildings)
                if (Intel.isTower(t) && t.isComplete() && Geo.dist2(t.getGridX(), t.getGridY(), x, y) < 17 * 17) {
                    near = t;
                    break;
                }
            if (near == null)
                break;
            int d = Math.max(1, Geo.dist(near.getGridX(), near.getGridY(), fx, fy));
            x = near.getGridX() + (fx - near.getGridX()) * 18 / d;
            y = near.getGridY() + (fy - near.getGridY()) * 18 / d;
        }
        return new int[]{x, y};
    }
}
