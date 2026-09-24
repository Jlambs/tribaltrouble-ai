package com.oddlabs.tt.player.ai;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.MountUnitContainer;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.DefendController;
import com.oddlabs.tt.model.behaviour.EnterController;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.behaviour.StunBehaviour;
import com.oddlabs.tt.model.behaviour.StunController;
import com.oddlabs.tt.model.behaviour.TransferUnitController;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.IronSpearWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RockSpearWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberSpearWeapon;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of everything the AI knows about its own units and the enemy's, rebuilt on every think. Lists keep the
 * insertion order of the players' unit sets so that every peer of a multiplayer game sees the same order.
 */
final class Intel {
    enum PeonState {
        IDLE,
        GATHER_TREE,
        GATHER_ROCK,
        GATHER_IRON,
        GATHER_CHICKEN,
        BUILD,
        TRANSIT,
        FIGHT,
        MOVE,
        STUNNED,
        /** Following the army to pull down enemy towers; the economy leaves them alone. */
        SAPPER
    }

    enum WarriorState {
        IDLE,
        ATTACK_MOVE,
        MOVE,
        FIGHT,
        ENTER,
        STUNNED
    }

    enum WarriorType {
        ROCK,
        IRON,
        CHICKEN
    }

    private final @NonNull Player owner;

    // Own buildings
    final List<@NonNull Building> quarters = new ArrayList<>();
    final List<@NonNull Building> armories = new ArrayList<>();
    final List<@NonNull Building> towers = new ArrayList<>();
    final List<@NonNull Building> quarters_sites = new ArrayList<>();
    final List<@NonNull Building> armory_sites = new ArrayList<>();
    final List<@NonNull Building> tower_sites = new ArrayList<>();

    // Own units
    final List<@NonNull Unit> peons = new ArrayList<>();
    final List<@NonNull Unit> warriors = new ArrayList<>();
    /** Peons the military has taken along to pull down towers, kept by it across updates. */
    final java.util.Set<@NonNull Unit> sappers = new java.util.LinkedHashSet<>();
    final Map<@NonNull Unit, @NonNull PeonState> peon_states = new LinkedHashMap<>();
    final Map<@NonNull Unit, @NonNull WarriorState> warrior_states = new LinkedHashMap<>();
    /** Construction site each builder works on. */
    final Map<@NonNull Unit, @NonNull Building> builder_sites = new LinkedHashMap<>();
    final Map<@NonNull Unit, @Nullable Building> gather_buildings = new LinkedHashMap<>();
    @Nullable
    Unit chieftain;
    private @Nullable Building primary_armory;

    // Enemy
    final List<@NonNull Unit> enemy_warriors = new ArrayList<>();
    final List<@NonNull Unit> enemy_peons = new ArrayList<>();
    final List<@NonNull Building> enemy_buildings = new ArrayList<>();
    final List<@NonNull Building> enemy_towers = new ArrayList<>();
    final List<@NonNull Building> enemy_armories = new ArrayList<>();
    final List<@NonNull Building> enemy_quarters = new ArrayList<>();
    final List<@NonNull Unit> enemy_chieftains = new ArrayList<>();

    Intel(@NonNull Player owner) {
        this.owner = owner;
    }

    void update() {
        quarters.clear();
        armories.clear();
        towers.clear();
        quarters_sites.clear();
        armory_sites.clear();
        tower_sites.clear();
        peons.clear();
        warriors.clear();
        peon_states.clear();
        warrior_states.clear();
        builder_sites.clear();
        gather_buildings.clear();
        chieftain = null;
        enemy_warriors.clear();
        enemy_peons.clear();
        enemy_buildings.clear();
        enemy_towers.clear();
        enemy_armories.clear();
        enemy_quarters.clear();
        enemy_chieftains.clear();

        for (Selectable<?> s : owner.getUnits().getSet()) {
            if (s.isDead())
                continue;
            if (s instanceof Building building) {
                classifyOwnBuilding(building);
            } else if (s instanceof Unit unit) {
                classifyOwnUnit(unit);
            }
        }
        for (Player player : owner.getWorld().getPlayers()) {
            if (!owner.isEnemy(player))
                continue;
            for (Selectable<?> s : player.getUnits().getSet()) {
                if (s.isDead())
                    continue;
                if (s instanceof Building building) {
                    if (building.getTemplate().getType() != com.oddlabs.tt.model.BuildingTemplate.TYPE_BUILDING)
                        continue;
                    enemy_buildings.add(building);
                    if (!building.isComplete())
                        continue;
                    switch (building.getTemplate().getTemplateID()) {
                        case Race.BUILDING_TOWER -> enemy_towers.add(building);
                        case Race.BUILDING_ARMORY -> enemy_armories.add(building);
                        case Race.BUILDING_QUARTERS -> enemy_quarters.add(building);
                        default -> {
                        }
                    }
                } else if (s instanceof Unit unit) {
                    if (unit.isMounted())
                        continue;
                    if (unit.getAbilities().hasAbilities(Abilities.MAGIC))
                        enemy_chieftains.add(unit);
                    else if (unit.getAbilities().hasAbilities(Abilities.THROW))
                        enemy_warriors.add(unit);
                    else if (unit.getAbilities().hasAbilities(Abilities.HARVEST))
                        enemy_peons.add(unit);
                }
            }
        }
    }

    private void classifyOwnBuilding(@NonNull Building building) {
        if (building.getTemplate().getType() != com.oddlabs.tt.model.BuildingTemplate.TYPE_BUILDING)
            return;
        boolean complete = building.isComplete();
        switch (building.getTemplate().getTemplateID()) {
            case Race.BUILDING_QUARTERS -> (complete ? quarters : quarters_sites).add(building);
            case Race.BUILDING_ARMORY -> (complete ? armories : armory_sites).add(building);
            case Race.BUILDING_TOWER -> (complete ? towers : tower_sites).add(building);
            default -> {
            }
        }
    }

    private void classifyOwnUnit(@NonNull Unit unit) {
        if (unit.isMounted())
            return;
        Controller controller = unit.getPrimaryController();
        if (unit.getAbilities().hasAbilities(Abilities.MAGIC)) {
            chieftain = unit;
        } else if (unit.getAbilities().hasAbilities(Abilities.THROW)) {
            warriors.add(unit);
            warrior_states.put(unit, warriorState(unit, controller));
        } else if (unit.getAbilities().hasAbilities(Abilities.HARVEST)) {
            peons.add(unit);
            peon_states.put(unit, peonState(unit, controller));
        }
    }

    private @NonNull PeonState peonState(@NonNull Unit unit, @NonNull Controller controller) {
        if (isStunned(unit))
            return PeonState.STUNNED;
        if (sappers.contains(unit))
            return PeonState.SAPPER;
        if (controller instanceof IdleController && unit.getCurrentController() instanceof HuntController)
            return PeonState.FIGHT;
        if (controller instanceof IdleController)
            return PeonState.IDLE;
        if (controller instanceof GatherController<?> gather) {
            gather_buildings.put(unit, gather.getAssignedBuilding());
            Class<?> type = gather.getSupplyType();
            if (type == TreeSupply.class)
                return PeonState.GATHER_TREE;
            if (type == RockSupply.class)
                return PeonState.GATHER_ROCK;
            if (type == IronSupply.class)
                return PeonState.GATHER_IRON;
            if (type == RubberSupply.class)
                return PeonState.GATHER_CHICKEN;
            return PeonState.IDLE;
        }
        if (controller instanceof RepairController repair) {
            builder_sites.put(unit, repair.getBuilding());
            return PeonState.BUILD;
        }
        if (controller instanceof PlaceBuildingController place) {
            builder_sites.put(unit, place.getBuilding());
            return PeonState.BUILD;
        }
        if (controller instanceof EnterController || controller instanceof TransferUnitController)
            return PeonState.TRANSIT;
        if (controller instanceof HuntController || controller instanceof AttackController
                || controller instanceof DefendController)
            return PeonState.FIGHT;
        if (controller instanceof WalkController walk)
            return walk.isAgressive() ? PeonState.FIGHT : PeonState.MOVE;
        if (controller instanceof StunController)
            return PeonState.STUNNED;
        return PeonState.IDLE;
    }

    private static @NonNull WarriorState warriorState(@NonNull Unit unit, @NonNull Controller controller) {
        // Attack-moving and idle warriors push a hunt on top when they spot an enemy; that counts as fighting.
        Controller current = unit.getCurrentController();
        if (isStunned(unit))
            return WarriorState.STUNNED;
        if (current instanceof HuntController || current instanceof AttackController)
            return WarriorState.FIGHT;
        if (controller instanceof IdleController)
            return WarriorState.IDLE;
        if (controller instanceof WalkController walk)
            return walk.isAgressive() ? WarriorState.ATTACK_MOVE : WarriorState.MOVE;
        if (controller instanceof HuntController || controller instanceof AttackController
                || controller instanceof DefendController)
            return WarriorState.FIGHT;
        if (controller instanceof EnterController)
            return WarriorState.ENTER;
        if (controller instanceof StunController)
            return WarriorState.STUNNED;
        return WarriorState.IDLE;
    }

    static @NonNull WarriorType warriorType(@NonNull Unit unit) {
        if (unit.isDead())
            return WarriorType.IRON;
        Class<?> type = unit.getWeaponFactory().getType();
        if (type == IronAxeWeapon.class || type == IronSpearWeapon.class)
            return WarriorType.IRON;
        if (type == RubberAxeWeapon.class || type == RubberSpearWeapon.class)
            return WarriorType.CHICKEN;
        if (type == RockAxeWeapon.class || type == RockSpearWeapon.class)
            return WarriorType.ROCK;
        return WarriorType.IRON;
    }

    /**
     * Frozen by a stun. An order given to a stunned unit replaces its stun controller while the stun behaviour
     * keeps it frozen, so both count.
     */
    static boolean isStunned(@NonNull Unit unit) {
        return !unit.isDead() && (unit.getCurrentController() instanceof StunController
                || unit.getCurrentBehaviour() instanceof StunBehaviour);
    }

    /** Stunned with the stun controller still on top: no chance to dodge until it is ordered again. */
    static boolean isDefenseless(@NonNull Unit unit) {
        return !unit.isDead() && unit.getCurrentController() instanceof StunController;
    }

    /** Whether a tower currently has a warrior inside that is not stunned. */
    static boolean isTowerActive(@NonNull Building tower) {
        if (tower.isDead() || !tower.isComplete() || tower.getUnitContainer() == null)
            return false;
        if (tower.getUnitContainer().getNumSupplies() == 0)
            return false;
        Unit unit = ((MountUnitContainer) tower.getUnitContainer()).getUnit();
        return unit != null && !isStunned(unit);
    }

    static boolean isTowerManned(@NonNull Building tower) {
        return !tower.isDead() && tower.isComplete() && tower.getUnitContainer() != null
                && tower.getUnitContainer().getNumSupplies() > 0;
    }

    int countPeons(@NonNull PeonState state) {
        int n = 0;
        for (PeonState s : peon_states.values())
            if (s == state)
                n++;
        return n;
    }

    /**
     * The armory that new peons and gatherers go to: the one the economy chose (a newer one opened next to fresh
     * supplies), otherwise the first.
     */
    @Nullable
    Building armory() {
        if (primary_armory != null && armories.contains(primary_armory))
            return primary_armory;
        return armories.isEmpty() ? null : armories.getFirst();
    }

    void setPrimaryArmory(@Nullable Building armory) {
        primary_armory = armory;
    }

    /** Gatherers of a type working for the given armory (or for whichever armory is nearest their supply). */
    /** Gatherers of a kind working for exactly this building. */
    int countLinkedGatherers(@NonNull PeonState state, @NonNull Building armory) {
        int n = 0;
        for (Map.Entry<Unit, PeonState> e : peon_states.entrySet())
            if (e.getValue() == state && gather_buildings.get(e.getKey()) == armory)
                n++;
        return n;
    }

    int countGatherers(@NonNull PeonState state, @Nullable Building armory) {
        int n = 0;
        for (Map.Entry<Unit, PeonState> e : peon_states.entrySet()) {
            if (e.getValue() != state)
                continue;
            Building b = gather_buildings.get(e.getKey());
            if (b == null || b == armory || armory == null)
                n++;
        }
        return n;
    }
}
