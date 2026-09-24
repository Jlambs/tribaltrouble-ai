package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.MountUnitContainer;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.UnitContainer;
import com.oddlabs.tt.model.behaviour.AttackBehaviour;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.Behaviour;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.DefendController;
import com.oddlabs.tt.model.behaviour.EnterController;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.HarvestBehaviour;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.MagicBehaviour;
import com.oddlabs.tt.model.behaviour.MagicController;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairBehaviour;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.behaviour.StunBehaviour;
import com.oddlabs.tt.model.behaviour.StunController;
import com.oddlabs.tt.model.behaviour.TransferUnitController;
import com.oddlabs.tt.model.behaviour.WalkBehaviour;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A snapshot of every player's units and buildings, rebuilt each think. Lists preserve the deterministic
 * {@code Army} (LinkedHashSet) order. Units inside quarters/armories are only counts; tower garrisons are
 * reached through their tower.
 */
final class WorldModel {
    enum Kind {
        PEON,
        ROCK,
        IRON,
        RUBBER,
        CHIEFTAIN
    }

    enum Activity {
        IDLE,
        GATHER,
        BUILD,
        PLACE,
        WALK,
        ATTACK_MOVE,
        HUNT,
        ENTER,
        STUNNED,
        MAGIC,
        OTHER
    }

    /** Units and buildings of one player (or all enemies merged). */
    static final class Side {
        final List<Unit> peons = new ArrayList<>();
        final List<Unit> warriors = new ArrayList<>();
        final List<Unit> units = new ArrayList<>();
        @Nullable
        Unit chieftain;
        /** Every chieftain of the side's players (more than one in team games); chieftain is the last of them. */
        final List<Unit> chieftains = new ArrayList<>();
        final List<Building> quarters = new ArrayList<>();
        final List<Building> armories = new ArrayList<>();
        final List<Building> towers = new ArrayList<>();
        final List<Building> sites = new ArrayList<>();
        final List<Building> buildings = new ArrayList<>();
        int rock_warriors;
        int iron_warriors;
        int rubber_warriors;
        int garrisoned;
        int peons_inside;
        int armory_workers;
        int weapons_rock;
        int weapons_iron;
        int weapons_rubber;
        int unit_count;

        void clear() {
            peons.clear();
            warriors.clear();
            units.clear();
            chieftain = null;
            chieftains.clear();
            quarters.clear();
            armories.clear();
            towers.clear();
            sites.clear();
            buildings.clear();
            rock_warriors = iron_warriors = rubber_warriors = 0;
            garrisoned = peons_inside = armory_workers = 0;
            weapons_rock = weapons_iron = weapons_rubber = 0;
            unit_count = 0;
        }

        int fieldWarriors() {
            return warriors.size();
        }

        /**
         * Strength (iron = 1) of warriors our armories could deploy right now: workers armed best weapons first.
         * Only meaningful for our own side: the contents of enemy buildings are hidden and never read.
         */
        float latentStrength() {
            int workers = armory_workers;
            int rub = Math.min(workers, weapons_rubber);
            workers -= rub;
            int iron = Math.min(workers, weapons_iron);
            workers -= iron;
            int rock = Math.min(workers, weapons_rock);
            return 1.35f * rub + iron + 0.63f * rock;
        }

        int potentialWarriors() {
            return warriors.size() + garrisoned + Math.min(peons_inside, weapons_rock + weapons_iron + weapons_rubber);
        }
    }

    final Side me = new Side();
    final Side enemy = new Side();
    private final @NonNull Player owner;

    WorldModel(@NonNull Player owner) {
        this.owner = owner;
    }

    /** Incremented on every update(), so caches built from the unit lists know when to rebuild. */
    int version;

    void update() {
        version++;
        me.clear();
        fill(me, owner, true);
        enemy.clear();
        for (Player p : owner.getWorld().getPlayers()) {
            if (owner.isEnemy(p))
                fill(enemy, p, false);
        }
    }

    /**
     * Collects a player's units and buildings. For enemies (own = false) only what a human in our seat can see is
     * read: positions, types, hit points, tower garrisons; never the contents of their buildings or their totals.
     */
    private static void fill(@NonNull Side side, @NonNull Player p, boolean own) {
        if (own)
            side.unit_count += p.getUnitCountContainer().getNumSupplies();
        Selectable<?>[] all = p.getUnits().getSet().toArray(new Selectable<?>[0]);
        for (Selectable<?> s : all) {
            if (s.isDead())
                continue;
            if (s instanceof Unit u) {
                if (u.isMounted())
                    continue;
                Kind kind = kindOf(u);
                switch (kind) {
                    case PEON -> side.peons.add(u);
                    case CHIEFTAIN -> {
                        side.chieftain = u;
                        side.chieftains.add(u);
                    }
                    case ROCK -> {
                        side.warriors.add(u);
                        side.rock_warriors++;
                    }
                    case IRON -> {
                        side.warriors.add(u);
                        side.iron_warriors++;
                    }
                    case RUBBER -> {
                        side.warriors.add(u);
                        side.rubber_warriors++;
                    }
                }
                side.units.add(u);
            } else if (s instanceof Building b) {
                side.buildings.add(b);
                if (!b.isComplete()) {
                    if (b.isPlaced())
                        side.sites.add(b);
                    continue;
                }
                int id = b.getTemplate().getTemplateID();
                if (id == Race.BUILDING_QUARTERS) {
                    side.quarters.add(b);
                    if (own)
                        side.peons_inside += b.getUnitContainer().getNumSupplies();
                } else if (id == Race.BUILDING_ARMORY) {
                    side.armories.add(b);
                    if (!own)
                        continue;
                    side.peons_inside += b.getUnitContainer().getNumSupplies();
                    side.armory_workers += b.getUnitContainer().getNumSupplies();
                    side.weapons_rock += b.getSupplyContainer(
                            com.oddlabs.tt.model.weapon.RockAxeWeapon.class).getNumSupplies();
                    side.weapons_iron += b.getSupplyContainer(
                            com.oddlabs.tt.model.weapon.IronAxeWeapon.class).getNumSupplies();
                    side.weapons_rubber += b.getSupplyContainer(
                            com.oddlabs.tt.model.weapon.RubberAxeWeapon.class).getNumSupplies();
                } else if (id == Race.BUILDING_TOWER) {
                    side.towers.add(b);
                    UnitContainer c = b.getUnitContainer();
                    if (c != null && c.getNumSupplies() > 0)
                        side.garrisoned++;
                }
            }
        }
    }

    static @NonNull Kind kindOf(@NonNull Unit u) {
        Abilities a = u.getAbilities();
        if (a.hasAbilities(Abilities.MAGIC))
            return Kind.CHIEFTAIN;
        if (a.hasAbilities(Abilities.BUILD))
            return Kind.PEON;
        Race race = u.getOwner().getRace();
        if (u.getTemplate() == race.getUnitTemplate(Race.UNIT_WARRIOR_RUBBER))
            return Kind.RUBBER;
        if (u.getTemplate() == race.getUnitTemplate(Race.UNIT_WARRIOR_IRON))
            return Kind.IRON;
        return Kind.ROCK;
    }

    /** The unit's primary controller, or null if it died or entered a building since the snapshot. */
    static @Nullable Controller primary(@NonNull Unit u) {
        return u.isDead() ? null : u.getPrimaryController();
    }

    static @NonNull Activity activityOf(@NonNull Unit u) {
        if (u.isDead())
            return Activity.OTHER;
        Controller current = u.getCurrentController();
        if (current instanceof StunController)
            return Activity.STUNNED;
        if (current instanceof MagicController)
            return Activity.MAGIC;
        Controller primary = u.getPrimaryController();
        if (primary instanceof IdleController)
            return current instanceof HuntController ? Activity.HUNT : Activity.IDLE;
        if (primary instanceof GatherController<?>)
            return Activity.GATHER;
        if (primary instanceof RepairController)
            return Activity.BUILD;
        if (primary instanceof PlaceBuildingController)
            return Activity.PLACE;
        if (primary instanceof HuntController || primary instanceof AttackController)
            return Activity.HUNT;
        if (primary instanceof WalkController w)
            return current instanceof HuntController ? Activity.HUNT : w.isAgressive() ? Activity.ATTACK_MOVE : Activity.WALK;
        if (primary instanceof DefendController)
            return current instanceof HuntController ? Activity.HUNT : Activity.ATTACK_MOVE;
        if (primary instanceof EnterController || primary instanceof TransferUnitController)
            return Activity.ENTER;
        return Activity.OTHER;
    }

    /**
     * What an enemy unit is visibly doing, judged only by its animation and what it carries (a human cannot see
     * orders, only actions): chopping or mining and carrying goods count as gathering, hammering as building.
     */
    static @NonNull Activity visibleActivity(@NonNull Unit u) {
        if (u.isDead())
            return Activity.OTHER;
        Behaviour b = u.getCurrentBehaviour();
        if (b instanceof StunBehaviour)
            return Activity.STUNNED;
        if (b instanceof MagicBehaviour)
            return Activity.MAGIC;
        if (b instanceof HarvestBehaviour)
            return Activity.GATHER;
        if (b instanceof RepairBehaviour)
            return Activity.BUILD;
        if (b instanceof AttackBehaviour)
            return Activity.HUNT;
        if (b instanceof WalkBehaviour) {
            com.oddlabs.tt.model.UnitSupplyContainer c = u.getSupplyContainer();
            return c != null && c.getNumSupplies() > 0 ? Activity.GATHER : Activity.WALK;
        }
        return Activity.IDLE;
    }

    /** True while a unit is stunned: its stun animation, which any player can see (controllers are not visible). */
    static boolean isStunned(@NonNull Unit u) {
        return !u.isDead() && u.getCurrentBehaviour() instanceof StunBehaviour;
    }

    /** The building a builder is working on (placing or constructing/repairing), or null. */
    static @Nullable Building buildTarget(@NonNull Unit u) {
        Controller primary = primary(u);
        if (primary instanceof RepairController r)
            return r.getBuilding();
        if (primary instanceof PlaceBuildingController p)
            return p.getBuilding();
        return null;
    }

    /** The unit garrisoned in a complete tower, or null. */
    static @Nullable Unit garrisonOf(@NonNull Building tower) {
        if (tower.isDead() || !tower.getAbilities().hasAbilities(Abilities.ATTACK))
            return null;
        UnitContainer c = tower.getUnitContainer();
        if (c instanceof MountUnitContainer m)
            return m.getUnit();
        return null;
    }
}
