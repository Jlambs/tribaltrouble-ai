package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.DefendController;
import com.oddlabs.tt.model.behaviour.EnterController;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.behaviour.StunController;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberSpearWeapon;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of everything this player owns, rebuilt each AI tick from {@link Player#getUnits()} (a LinkedHashSet,
 * so iteration order is deterministic). Units inside buildings are not in the set; they are counted through the
 * buildings' containers instead.
 */
public final class Roster {
    /** What the engine says a unit is currently doing (from its primary controller). */
    public enum Activity {
        IDLE,
        GATHER_TREE,
        GATHER_ROCK,
        GATHER_IRON,
        GATHER_RUBBER,
        WALK,
        ATTACK_MOVE,
        DEFEND,
        PLACE_BUILDING,
        REPAIR,
        HUNT,
        ENTER,
        STUNNED,
        OTHER
    }

    private final @NonNull Player player;

    public final @NonNull List<Unit> peons = new ArrayList<>();
    public final @NonNull List<Unit> warriors = new ArrayList<>();
    public @Nullable Unit chieftain;
    public final @NonNull List<LandBuilding> quarters = new ArrayList<>();
    public final @NonNull List<LandBuilding> armories = new ArrayList<>();
    public final @NonNull List<LandBuilding> towers = new ArrayList<>();
    /** Placed but not yet complete, any type. */
    public final @NonNull List<LandBuilding> under_construction = new ArrayList<>();

    public int idle_peons;
    public int gatherers_tree;
    public int gatherers_rock;
    public int gatherers_iron;
    public int gatherers_rubber;
    public int builders;
    public int idle_warriors;
    public int rubber_warriors;
    public int peons_in_quarters;
    public int peons_in_armory;
    public int warriors_in_towers;

    public Roster(@NonNull Player player) {
        this.player = player;
    }

    public void refresh() {
        peons.clear();
        warriors.clear();
        chieftain = null;
        quarters.clear();
        armories.clear();
        towers.clear();
        under_construction.clear();
        idle_peons = gatherers_tree = gatherers_rock = gatherers_iron = gatherers_rubber = builders = 0;
        idle_warriors = rubber_warriors = peons_in_quarters = peons_in_armory = warriors_in_towers = 0;
        for (Selectable<?> s : player.getUnits().getSet()) {
            if (s.isDead())
                continue;
            if (s instanceof Unit u) {
                if (u.isMounted())
                    continue;
                if (u.getAbilities().hasAbilities(Abilities.MAGIC)) {
                    chieftain = u;
                } else if (u.getAbilities().hasAbilities(Abilities.BUILD)) {
                    peons.add(u);
                    switch (activity(u)) {
                        case IDLE -> idle_peons++;
                        case GATHER_TREE -> gatherers_tree++;
                        case GATHER_ROCK -> gatherers_rock++;
                        case GATHER_IRON -> gatherers_iron++;
                        case GATHER_RUBBER -> gatherers_rubber++;
                        case PLACE_BUILDING, REPAIR -> builders++;
                        default -> {
                        }
                    }
                } else if (u.getAbilities().hasAbilities(Abilities.THROW)) {
                    warriors.add(u);
                    if (activity(u) == Activity.IDLE)
                        idle_warriors++;
                    if (isRubber(u))
                        rubber_warriors++;
                }
            } else if (s instanceof LandBuilding b) {
                if (!b.isPlaced())
                    continue;
                if (!b.isComplete()) {
                    under_construction.add(b);
                    continue;
                }
                switch (b.getTemplate().getTemplateID()) {
                    case Race.BUILDING_QUARTERS -> {
                        quarters.add(b);
                        peons_in_quarters += unitsInside(b);
                    }
                    case Race.BUILDING_ARMORY -> {
                        armories.add(b);
                        peons_in_armory += unitsInside(b);
                    }
                    case Race.BUILDING_TOWER -> {
                        towers.add(b);
                        warriors_in_towers += unitsInside(b);
                    }
                    default -> {
                    }
                }
            }
        }
    }

    public static int unitsInside(@NonNull LandBuilding b) {
        if (b.isDead() || !b.isComplete() || b.getUnitContainer() == null)
            return 0;
        return b.getUnitContainer().getNumSupplies();
    }

    public static boolean isRubber(@NonNull Unit u) {
        Class<?> type = u.getWeaponFactory().getType();
        return type == RubberAxeWeapon.class || type == RubberSpearWeapon.class;
    }

    /** Classify a unit's current engine activity. */
    public static @NonNull Activity activity(@NonNull Unit u) {
        if (u.isDead())
            return Activity.OTHER;
        Controller c = u.getPrimaryController();
        if (u.getCurrentController() instanceof StunController)
            return Activity.STUNNED;
        if (c instanceof IdleController)
            return Activity.IDLE;
        if (c instanceof GatherController<?> g) {
            Class<?> type = g.getSupplyType();
            if (type == TreeSupply.class)
                return Activity.GATHER_TREE;
            if (type == RockSupply.class)
                return Activity.GATHER_ROCK;
            if (type == IronSupply.class)
                return Activity.GATHER_IRON;
            if (type == RubberSupply.class)
                return Activity.GATHER_RUBBER;
            return Activity.OTHER;
        }
        if (c instanceof WalkController w)
            return w.isAgressive() ? Activity.ATTACK_MOVE : Activity.WALK;
        if (c instanceof DefendController)
            return Activity.DEFEND;
        if (c instanceof PlaceBuildingController)
            return Activity.PLACE_BUILDING;
        if (c instanceof RepairController)
            return Activity.REPAIR;
        if (c instanceof HuntController)
            return Activity.HUNT;
        if (c instanceof EnterController)
            return Activity.ENTER;
        return Activity.OTHER;
    }

    /** The building a peon is placing or repairing, or null. */
    public static @Nullable LandBuilding buildingWorkedOn(@NonNull Unit u) {
        if (u.isDead())
            return null;
        Controller c = u.getPrimaryController();
        if (c instanceof PlaceBuildingController p && p.getBuilding() instanceof LandBuilding b)
            return b;
        if (c instanceof RepairController r && r.getBuilding() instanceof LandBuilding b)
            return b;
        return null;
    }

    public int unitCount() {
        return player.getUnitCountContainer().getNumSupplies();
    }

    public int unitCap() {
        return player.getWorld().getMaxUnitCount();
    }

    public int buildingCount() {
        return player.getBuildingCountContainer().getNumSupplies();
    }

    public int buildingCap() {
        return player.getWorld().getMaxBuildingCount();
    }

    public @Nullable LandBuilding mainArmory() {
        return armories.isEmpty() ? null : armories.getFirst();
    }

    public int totalWarriors() {
        return warriors.size() + warriors_in_towers;
    }

    public int totalPeons() {
        return peons.size() + peons_in_quarters + peons_in_armory;
    }
}
