package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.MountUnitContainer;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.MagicController;
import com.oddlabs.tt.model.behaviour.StunBehaviour;
import com.oddlabs.tt.model.behaviour.StunController;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The chieftain brain (runs on the fast lane): when to toot (stun) and roar (blast), where to stand, when to run.
 *
 * <p>Stun facts: radius 36 m = 18 cells; enemies must be inside at cast+2.15 s and cast+3.77 s; the stun lasts
 * 20 (6/7)^d + 10 seconds at d metres from the chieftain, so plan on a 10 s window; stunned units and garrisons
 * have defense 0; casting resets both energies. Our stun landing on a casting enemy chieftain only delays its
 * toot, so the enemy chieftain must then be killed inside the window or left.
 */
public final class Chieftain {
    private final @NonNull Orders orders;
    private final @NonNull Params params;
    private final @NonNull AiLog log;

    /** Time of our last stun cast (for focus-fire windows), or -100. */
    public float last_stun_at = -100f;
    public int @Nullable [] last_stun_cell;
    public float last_blast_at = -100f;
    /** A pending siege cast: set by the military when the chieftain stands at the tower stand. */
    public boolean siege_cast_requested;
    /** The military is walking the chieftain to a tower stand: keep the energy for that cast. */
    public boolean hold_for_siege;
    private float last_move_order = -100f;
    private int last_move_x = -1;
    private int last_move_y = -1;

    public Chieftain(@NonNull Orders orders, @NonNull Params params, @NonNull AiLog log) {
        this.orders = orders;
        this.params = params;
        this.log = log;
    }

    /** Stun controller on top: defence is 0 and a new order would replace it (see Military.recoverStunned). */
    public static boolean isStunned(@NonNull Unit u) {
        return !u.isDead() && u.getCurrentController() instanceof StunController;
    }

    /**
     * Frozen by a stun (the stun behaviour runs until the stun ends, whatever controller sits on top). A frozen
     * unit neither moves nor throws; its defence is 0 unless its owner re-ordered it.
     */
    public static boolean isFrozen(@NonNull Unit u) {
        return !u.isDead() && u.getCurrentBehaviour() instanceof StunBehaviour;
    }

    public static boolean isCasting(@NonNull Unit u) {
        return !u.isDead() && u.getCurrentController() instanceof MagicController;
    }

    public static boolean stunReady(@NonNull Unit chief) {
        return !chief.isDead() && chief.canDoMagic(RacesResources.INDEX_MAGIC_STUN);
    }

    public static float stunProgress(@NonNull Unit chief) {
        return chief.isDead() ? 0f : chief.getMagicProgress(RacesResources.INDEX_MAGIC_STUN);
    }

    /** Enemy value (warriors + manned towers + chieftain) within the stun disc around a cell. */
    public float valueInDisc(@NonNull Intel intel, int cx, int cy, float radius) {
        float r2 = radius * radius;
        float v = 0f;
        for (Unit u : intel.warriors)
            if (BasePlan.dist2(u.getGridX(), u.getGridY(), cx, cy) <= r2 && !isFrozen(u))
                v += Intel.unitValue(u);
        for (Unit u : intel.chieftains)
            if (BasePlan.dist2(u.getGridX(), u.getGridY(), cx, cy) <= r2 && !isFrozen(u))
                v += 3f;
        for (LandBuilding t : intel.manned_towers)
            if (BasePlan.dist2(t.getGridX(), t.getGridY(), cx, cy) <= r2 && !towerStunned(t))
                v += 4f;
        return v;
    }

    public int warriorsInDisc(@NonNull Intel intel, int cx, int cy, float radius) {
        float r2 = radius * radius;
        int n = 0;
        for (Unit u : intel.warriors)
            if (BasePlan.dist2(u.getGridX(), u.getGridY(), cx, cy) <= r2)
                n++;
        return n;
    }

    public static boolean towerStunned(@NonNull LandBuilding tower) {
        if (tower.isDead() || !tower.isComplete() || tower.getUnitContainer() == null)
            return false;
        if (tower.getUnitContainer() instanceof MountUnitContainer m && m.getUnit() != null)
            return isFrozen(m.getUnit());
        return false;
    }

    /** The enemy chieftain nearest to a cell, or null. */
    public static @Nullable Unit nearestEnemyChief(@NonNull Intel intel, int gx, int gy) {
        return Intel.nearest(intel.chieftains, gx, gy);
    }

    /**
     * Cast decision. Returns true if a spell was cast this tick. {@code fighting} tells whether our own army is
     * engaged near the chieftain (so a stun will be exploited); {@code own_near} counts our warriors within 15
     * cells of the chieftain.
     */
    public boolean decideCast(float now, @NonNull Unit chief, @NonNull Intel intel, @NonNull Threat threat,
            boolean fighting, int own_near, int own_units_near_18, boolean defending,
            @NonNull List<Unit> own_warriors) {
        if (!orders.usable(chief) || isCasting(chief) || isStunned(chief))
            return false;
        int cx = chief.getGridX();
        int cy = chief.getGridY();
        float R = params.stun_radius_cells;
        boolean stun_ready = chief.canDoMagic(RacesResources.INDEX_MAGIC_STUN);
        boolean blast_ready = chief.canDoMagic(RacesResources.INDEX_MAGIC_BLAST);
        if (!stun_ready && !blast_ready)
            return false;
        // S1 counter-toot: enemy chieftain armed or casting within reach
        Unit echief = nearestEnemyChief(intel, cx, cy);
        if (stun_ready && echief != null && !isStunned(echief)) {
            int d2 = BasePlan.dist2(echief.getGridX(), echief.getGridY(), cx, cy);
            if (d2 <= 17 * 17 && (stunProgress(echief) >= params.counter_toot_progress || isCasting(echief))) {
                return cast(now, chief, true, "counter-toot vs enemy chieftain");
            }
        }
        // S5 panic
        int enemy_close8 = warriorsInDisc(intel, cx, cy, 8f);
        if (chief.getHitPoints() <= 20 && enemy_close8 >= 2) {
            if (blast_ready && own_units_near_18 <= params.blast_max_own && enemy_close8 >= 4)
                return cast(now, chief, false, "last stand blast");
            if (stun_ready)
                return cast(now, chief, true, "panic stun");
        }
        if (siege_cast_requested && stun_ready) {
            siege_cast_requested = false;
            return cast(now, chief, true, "siege stun");
        }
        // stun value inside the disc; enemies must be within ~15 cells so they are still inside at +3.8 s
        float v15 = valueInDisc(intel, cx, cy, params.stun_cast_dist);
        int n15 = warriorsInDisc(intel, cx, cy, params.stun_cast_dist);
        int n10 = warriorsInDisc(intel, cx, cy, 10f);
        // B1 blast: a cluster of enemy peons with none of ours nearby
        if (blast_ready && !defending) {
            int peons = 0;
            for (Unit p : intel.peons)
                if (BasePlan.dist2(p.getGridX(), p.getGridY(), cx, cy) <= 15 * 15)
                    peons++;
            if (peons + 1.5f * n15 >= params.blast_min_peons && own_units_near_18 <= params.blast_max_own)
                return cast(now, chief, false, "blast on " + peons + " peons/" + n15 + " warriors");
        }
        if (!stun_ready)
            return false;
        // S6: the enemy chieftain within the disc with enough of our warriors next to it: 20 s frozen at
        // 0 defence is a free kill of the unit that decides their fights (60 hp, 2 per iron hit)
        if (echief != null && !isFrozen(echief)) {
            int ex = echief.getGridX();
            int ey = echief.getGridY();
            if (BasePlan.dist2(ex, ey, cx, cy) <= 13 * 13 && own_near >= 6
                    && ownWarriorsNear(own_warriors, ex, ey, 12) >= 8)
                return cast(now, chief, true, "stun on the enemy chieftain");
        }
        if (defending) {
            // S2: enemy value near us at home
            if (v15 >= params.base_stun_value || (n10 >= 3 && v15 >= 3f && own_near >= 2))
                return cast(now, chief, true, "base stun value=" + v15);
        } else if (fighting && !hold_for_siege) {
            // S4: field battle. Cast when the disc holds a real share of the enemy near the fight, not on the
            // first six that come into reach while the clump is still 25 cells away (walk closer instead).
            float v25 = valueInDisc(intel, cx, cy, 25f);
            if ((v15 >= 5f || n10 >= 3) && own_near >= 4 && v15 >= 0.5f * v25)
                return cast(now, chief, true, "field stun value=" + v15 + " of " + v25 + " n10=" + n10);
        }
        return false;
    }

    private boolean cast(float now, @NonNull Unit chief, boolean stun, @NonNull String why) {
        boolean ok = stun ? orders.stun(chief) : orders.blast(chief);
        if (ok) {
            if (stun) {
                last_stun_at = now;
                last_stun_cell = new int[]{chief.getGridX(), chief.getGridY()};
            } else {
                last_blast_at = now;
            }
            log.info(() -> "chieftain " + (stun ? "STUN" : "BLAST") + ": " + why);
        }
        return ok;
    }

    /** Move the chieftain (non-aggressively) to a cell; rate limited and skipped while casting. */
    public void moveTo(float now, @NonNull Unit chief, int gx, int gy, boolean urgent) {
        if (!orders.usable(chief) || isCasting(chief))
            return;
        int d2 = BasePlan.dist2(gx, gy, last_move_x, last_move_y);
        if (!urgent && now - last_move_order < 3f && d2 <= 16)
            return;
        if (BasePlan.dist2(chief.getGridX(), chief.getGridY(), gx, gy) <= 4 && !chief.isMoving())
            return;
        orders.move(chief, gx, gy);
        last_move_order = now;
        last_move_x = gx;
        last_move_y = gy;
    }

    /** Should the chieftain run? Low hp with enemies close, and not casting. */
    public boolean shouldEscape(@NonNull Unit chief, @NonNull Intel intel) {
        if (isCasting(chief))
            return false;
        int hp = chief.getHitPoints();
        int near = warriorsInDisc(intel, chief.getGridX(), chief.getGridY(), 10f);
        return (hp <= params.chief_escape_hp && near >= 2) || (hp <= params.chief_fragile_hp && near >= 4);
    }

    /** Own units inside a radius of the chieftain (for blast safety). */
    public static int ownUnitsNear(@NonNull Roster roster, int cx, int cy, int radius) {
        int r2 = radius * radius;
        int n = 0;
        for (Unit u : roster.warriors)
            if (BasePlan.dist2(u.getGridX(), u.getGridY(), cx, cy) <= r2)
                n++;
        for (Unit u : roster.peons)
            if (BasePlan.dist2(u.getGridX(), u.getGridY(), cx, cy) <= r2)
                n++;
        return n;
    }

    public static int ownWarriorsNear(@NonNull List<Unit> warriors, int cx, int cy, int radius) {
        int r2 = radius * radius;
        int n = 0;
        for (Unit u : warriors)
            if (!u.isDead() && u.getAbilities().hasAbilities(Abilities.THROW)
                    && BasePlan.dist2(u.getGridX(), u.getGridY(), cx, cy) <= r2)
                n++;
        return n;
    }

    /** Enemies currently stunned near a cell, nearest first (for focus fire). */
    public static void stunnedEnemies(@NonNull Intel intel, int cx, int cy, int radius,
            @NonNull List<Selectable<?>> out) {
        out.clear();
        int r2 = radius * radius;
        for (Unit u : intel.chieftains)
            if (isFrozen(u) && BasePlan.dist2(u.getGridX(), u.getGridY(), cx, cy) <= r2)
                out.add(u);
        for (Unit u : intel.warriors)
            if (isFrozen(u) && BasePlan.dist2(u.getGridX(), u.getGridY(), cx, cy) <= r2)
                out.add(u);
        for (LandBuilding t : intel.manned_towers)
            if (towerStunned(t) && BasePlan.dist2(t.getGridX(), t.getGridY(), cx, cy) <= r2)
                out.add(t);
        out.sort((a, b) -> Integer.compare(BasePlan.dist2(a.getGridX(), a.getGridY(), cx, cy),
                BasePlan.dist2(b.getGridX(), b.getGridY(), cx, cy)));
    }
}
