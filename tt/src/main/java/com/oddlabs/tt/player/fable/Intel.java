package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RockSpearWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberSpearWeapon;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of everything the enemy owns, rebuilt each slow tick from the enemy players' unit sets (the game has no
 * fog of war, so this is exactly what a human sees on the minimap). Lists keep the engine's insertion order, which
 * is identical on every peer.
 */
public final class Intel {
    /** Relative combat value of a unit, used for strength comparisons. Warriors have 1 hp, so hit rate dominates. */
    public static final float VALUE_PEON = 0.25f;
    public static final float VALUE_ROCK = 0.6f;
    public static final float VALUE_IRON = 1.0f;
    public static final float VALUE_RUBBER = 1.6f;
    public static final float VALUE_CHIEFTAIN = 6f;
    public static final float VALUE_MANNED_TOWER = 4f;

    private final @NonNull Player me;

    public final @NonNull List<Unit> warriors = new ArrayList<>();
    public final @NonNull List<Unit> peons = new ArrayList<>();
    public final @NonNull List<Unit> chieftains = new ArrayList<>();
    public final @NonNull List<LandBuilding> armories = new ArrayList<>();
    public final @NonNull List<LandBuilding> quarters = new ArrayList<>();
    public final @NonNull List<LandBuilding> towers = new ArrayList<>();
    /** Towers with a warrior inside. */
    public final @NonNull List<LandBuilding> manned_towers = new ArrayList<>();
    /** Buildings still under construction. */
    public final @NonNull List<LandBuilding> construction_sites = new ArrayList<>();

    public float warrior_value;
    public int unit_count;

    public Intel(@NonNull Player me) {
        this.me = me;
    }

    public void refresh() {
        warriors.clear();
        peons.clear();
        chieftains.clear();
        armories.clear();
        quarters.clear();
        towers.clear();
        manned_towers.clear();
        construction_sites.clear();
        warrior_value = 0f;
        unit_count = 0;
        for (Player p : me.getWorld().getPlayers()) {
            if (!me.isEnemy(p))
                continue;
            for (Selectable<?> s : p.getUnits().getSet()) {
                if (s.isDead())
                    continue;
                if (s instanceof Unit u) {
                    unit_count++;
                    if (u.getAbilities().hasAbilities(Abilities.MAGIC)) {
                        chieftains.add(u);
                    } else if (u.getAbilities().hasAbilities(Abilities.BUILD)) {
                        peons.add(u);
                    } else if (u.getAbilities().hasAbilities(Abilities.THROW)) {
                        warriors.add(u);
                        warrior_value += unitValue(u);
                    }
                } else if (s instanceof LandBuilding b) {
                    if (!b.isComplete()) {
                        construction_sites.add(b);
                    } else if (b.getAbilities().hasAbilities(Abilities.BUILD_ARMIES)) {
                        armories.add(b);
                    } else if (b.getAbilities().hasAbilities(Abilities.REPRODUCE)) {
                        quarters.add(b);
                    } else if (b.getAbilities().hasAbilities(Abilities.ATTACK)) {
                        towers.add(b);
                        if (b.getUnitContainer() != null && b.getUnitContainer().getNumSupplies() > 0)
                            manned_towers.add(b);
                    }
                }
            }
        }
    }

    /** Combat value of a single unit (see the VALUE_ constants). */
    public static float unitValue(@NonNull Unit u) {
        if (u.getAbilities().hasAbilities(Abilities.MAGIC))
            return VALUE_CHIEFTAIN;
        if (u.getAbilities().hasAbilities(Abilities.BUILD))
            return VALUE_PEON;
        Class<?> type = u.getWeaponFactory().getType();
        if (type == RockAxeWeapon.class || type == RockSpearWeapon.class)
            return VALUE_ROCK;
        if (type == RubberAxeWeapon.class || type == RubberSpearWeapon.class)
            return VALUE_RUBBER;
        return VALUE_IRON;
    }

    public boolean hasBuildings() {
        return !armories.isEmpty() || !quarters.isEmpty() || !towers.isEmpty() || !construction_sites.isEmpty();
    }

    /** Sum of warrior values (plus chieftains) within {@code radius} cells of a cell. */
    public float strengthNear(int gx, int gy, int radius) {
        int r2 = radius * radius;
        float sum = 0f;
        for (Unit u : warriors)
            if (dist2(u, gx, gy) <= r2)
                sum += unitValue(u);
        for (Unit u : chieftains)
            if (dist2(u, gx, gy) <= r2)
                sum += VALUE_CHIEFTAIN;
        for (LandBuilding t : manned_towers)
            if (dist2(t, gx, gy) <= r2)
                sum += VALUE_MANNED_TOWER;
        return sum;
    }

    public int warriorsNear(int gx, int gy, int radius) {
        int r2 = radius * radius;
        int n = 0;
        for (Unit u : warriors)
            if (dist2(u, gx, gy) <= r2)
                n++;
        return n;
    }

    public int unitsNear(int gx, int gy, int radius) {
        int r2 = radius * radius;
        int n = 0;
        for (Unit u : warriors)
            if (dist2(u, gx, gy) <= r2)
                n++;
        for (Unit u : peons)
            if (dist2(u, gx, gy) <= r2)
                n++;
        for (Unit u : chieftains)
            if (dist2(u, gx, gy) <= r2)
                n++;
        return n;
    }

    public @Nullable Unit nearestWarrior(int gx, int gy) {
        return nearest(warriors, gx, gy);
    }

    public @Nullable LandBuilding nearestBuilding(int gx, int gy) {
        LandBuilding best = null;
        int best_d2 = Integer.MAX_VALUE;
        for (List<LandBuilding> list : List.of(armories, quarters, towers, construction_sites)) {
            for (LandBuilding b : list) {
                int d2 = dist2(b, gx, gy);
                if (d2 < best_d2) {
                    best_d2 = d2;
                    best = b;
                }
            }
        }
        return best;
    }

    public static <T extends Selectable<?>> @Nullable T nearest(@NonNull List<T> list, int gx, int gy) {
        T best = null;
        int best_d2 = Integer.MAX_VALUE;
        for (T s : list) {
            int d2 = dist2(s, gx, gy);
            if (d2 < best_d2) {
                best_d2 = d2;
                best = s;
            }
        }
        return best;
    }

    public static int dist2(@NonNull Selectable<?> s, int gx, int gy) {
        int dx = s.getGridX() - gx;
        int dy = s.getGridY() - gy;
        return dx * dx + dy * dy;
    }

    /** Centre of mass of the enemy warriors within radius of a seed cell, or null when none. */
    public int @Nullable [] armyCentroid(int gx, int gy, int radius) {
        int r2 = radius * radius;
        long sx = 0, sy = 0;
        int n = 0;
        for (Unit u : warriors) {
            if (dist2(u, gx, gy) <= r2) {
                sx += u.getGridX();
                sy += u.getGridY();
                n++;
            }
        }
        if (n == 0)
            return null;
        return new int[]{(int) (sx / n), (int) (sy / n)};
    }

    @Nullable
    Building enemyBuildingAt(int gx, int gy) {
        for (List<LandBuilding> list : List.of(armories, quarters, towers, construction_sites))
            for (LandBuilding b : list)
                if (b.getGridX() == gx && b.getGridY() == gy)
                    return b;
        return null;
    }
}
