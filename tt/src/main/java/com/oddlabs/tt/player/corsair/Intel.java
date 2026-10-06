package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.ShipProxy;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.IronSpearWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RockSpearWeapon;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What stands on the map this round, read once per think. Lists hold only live things; units inside buildings and
 * aboard ships are not in them (they are counted through their building or ship).
 */
final class Intel {
    enum Kind {
        PEON,
        ROCK,
        IRON,
        RUBBER,
        CHIEF
    }

    final @NonNull Player me;
    final @NonNull Geo geo;

    // ours
    final List<@NonNull Unit> peons = new ArrayList<>();
    final List<@NonNull Unit> warriors = new ArrayList<>();
    @Nullable
    Unit chief;
    final List<@NonNull Building> quarters = new ArrayList<>();
    final List<@NonNull Building> armories = new ArrayList<>();
    final List<@NonNull Building> towers = new ArrayList<>();
    /** Unfinished land buildings (placed sites). */
    final List<@NonNull Building> sites = new ArrayList<>();
    final List<@NonNull Ship> ships = new ArrayList<>();
    final List<@NonNull Ship> ship_sites = new ArrayList<>();
    int buildings;

    // theirs
    final List<@NonNull Unit> enemy_units = new ArrayList<>();
    final List<@NonNull Unit> enemy_warriors = new ArrayList<>();
    final List<@NonNull Building> enemy_buildings = new ArrayList<>();
    final List<@NonNull Ship> enemy_ships = new ArrayList<>();
    final List<@NonNull Player> enemies = new ArrayList<>();
    final List<@NonNull Player> allies = new ArrayList<>();

    Intel(@NonNull Player me, @NonNull Geo geo) {
        this.me = me;
        this.geo = geo;
        for (Player p : me.getWorld().getPlayers()) {
            if (p == me)
                continue;
            if (me.isEnemy(p))
                enemies.add(p);
            else
                allies.add(p);
        }
    }

    static boolean isQuarters(@NonNull Building b) {
        return b.getTemplate().getTemplateID() == Race.BUILDING_QUARTERS;
    }

    static boolean isArmory(@NonNull Building b) {
        return b.getTemplate().getTemplateID() == Race.BUILDING_ARMORY;
    }

    static boolean isTower(@NonNull Building b) {
        return b.getTemplate().getTemplateID() == Race.BUILDING_TOWER;
    }

    static @NonNull Kind kind(@NonNull Unit u) {
        if (u.isChieftain())
            return Kind.CHIEF;
        if (!u.isWarrior())
            return Kind.PEON;
        Class<?> t = u.getWeaponFactory().getType();
        if (t == RockAxeWeapon.class || t == RockSpearWeapon.class)
            return Kind.ROCK;
        if (t == IronAxeWeapon.class || t == IronSpearWeapon.class)
            return Kind.IRON;
        return Kind.RUBBER;
    }

    /** Fighting value in iron warriors (gauntlet Combat: hits kill, so value ~ hit chance / chance of being hit). */
    static float value(@NonNull Unit u) {
        return switch (kind(u)) {
            case PEON -> .2f;
            case ROCK -> .6f;
            case IRON -> 1f;
            case RUBBER -> 2f;
            case CHIEF -> 2.5f;
        };
    }

    void refresh() {
        peons.clear();
        warriors.clear();
        chief = null;
        quarters.clear();
        armories.clear();
        towers.clear();
        sites.clear();
        ships.clear();
        ship_sites.clear();
        buildings = 0;
        for (Selectable<?> s : me.getUnits().getSet()) {
            if (s.isDead())
                continue;
            if (s instanceof Unit u) {
                if (u.isMounted())
                    continue;
                switch (kind(u)) {
                    case PEON -> peons.add(u);
                    case CHIEF -> chief = u;
                    default -> warriors.add(u);
                }
            } else if (s instanceof Ship ship) {
                buildings++;
                if (ship.isComplete())
                    ships.add(ship);
                else
                    ship_sites.add(ship);
            } else if (s instanceof ShipProxy) {
                // the landing stage of a docked ship: takes no orders
            } else if (s instanceof Building b) {
                buildings++;
                if (!b.isComplete())
                    sites.add(b);
                else if (isQuarters(b))
                    quarters.add(b);
                else if (isArmory(b))
                    armories.add(b);
                else if (isTower(b))
                    towers.add(b);
            }
        }
        enemy_units.clear();
        enemy_warriors.clear();
        enemy_buildings.clear();
        enemy_ships.clear();
        for (Player p : enemies) {
            for (Selectable<?> s : p.getUnits().getSet()) {
                if (s.isDead())
                    continue;
                if (s instanceof Unit u) {
                    if (u.isMounted())
                        continue;
                    enemy_units.add(u);
                    if (u.isWarrior() || u.isChieftain())
                        enemy_warriors.add(u);
                } else if (s instanceof Ship ship) {
                    enemy_ships.add(ship);
                } else if (s instanceof ShipProxy) {
                    // skip
                } else if (s instanceof Building b) {
                    enemy_buildings.add(b);
                }
            }
        }
    }

    /** Units inside our quarters and armories. */
    int peonsInside() {
        int n = 0;
        for (Building b : quarters)
            n += b.getUnitContainer().getNumSupplies();
        for (Building b : armories)
            n += b.getUnitContainer().getNumSupplies();
        return n;
    }

    int units() {
        return me.getUnitCountContainer().getNumSupplies();
    }

    /** Enemy warriors' value on an island. */
    float enemyStrengthOn(int island) {
        float v = 0;
        for (Unit u : enemy_warriors)
            if (u.getIslandId() == island)
                v += value(u);
        return v;
    }

    float ourStrengthOn(int island) {
        float v = 0;
        for (Unit u : warriors)
            if (u.getIslandId() == island)
                v += value(u);
        if (chief != null && chief.getIslandId() == island)
            v += value(chief);
        return v;
    }
}
