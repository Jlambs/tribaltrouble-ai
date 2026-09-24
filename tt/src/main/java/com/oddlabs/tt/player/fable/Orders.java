package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.gui.BuildSpinner;
import com.oddlabs.tt.landscape.LandscapeTarget;
import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.BuildSupplyContainer;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.model.weapon.ThrowingWeapon;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

/**
 * Thin, assertion-safe layer over {@link Player}'s order methods. Every call re-validates its arguments (the game
 * runs with {@code -ea}: ordering a dead or mounted unit, or touching a dead building, throws), clamps grid
 * coordinates and drops units that do not belong to this player. All game-state changes made by the AI go through
 * here so they can be logged in one place.
 */
public final class Orders {
    private final @NonNull Player player;
    private final @NonNull UnitGrid grid;
    private final @NonNull AiLog log;

    public Orders(@NonNull Player player, @NonNull AiLog log) {
        this.player = player;
        this.grid = player.getWorld().getUnitGrid();
        this.log = log;
    }

    public @NonNull Player getPlayer() {
        return player;
    }

    // ------------------------------------------------------------------ validation

    /** A unit of ours that can still receive orders (alive, not inside a building). */
    public boolean usable(@Nullable Unit unit) {
        return unit != null && !unit.isDead() && unit.getOwner() == player && !unit.isMounted();
    }

    /** A building of ours that still exists (placed or not). */
    public boolean usable(@Nullable Building building) {
        return building != null && !building.isDead() && building.getOwner() == player;
    }

    /** A completed building of ours. */
    public boolean complete(@Nullable Building building) {
        return usable(building) && building.isPlaced() && building.isComplete();
    }

    public static boolean alive(@Nullable Target target) {
        return target != null && !target.isDead();
    }

    public int clampGrid(int c) {
        return Math.clamp(c, 0, grid.getGridSize() - 1);
    }

    private Selectable<?> @NonNull [] filterUnits(@NonNull Collection<? extends Unit> units) {
        int n = 0;
        for (Unit u : units)
            if (usable(u))
                n++;
        Selectable<?>[] result = Selectable.newArray(n);
        int i = 0;
        for (Unit u : units)
            if (usable(u))
                result[i++] = u;
        return result;
    }

    // ------------------------------------------------------------------ movement / combat

    /** Attack-move: walk to the cell and fight anything met on the way ("attack the ground"). */
    public int attackMove(@NonNull Collection<? extends Unit> units, int gx, int gy) {
        Selectable<?>[] sel = filterUnits(units);
        if (sel.length == 0)
            return 0;
        player.setLandscapeTarget(sel, clampGrid(gx), clampGrid(gy), Action.ATTACK, true);
        return sel.length;
    }

    public void attackMove(@NonNull Unit unit, int gx, int gy) {
        if (!usable(unit))
            return;
        player.setLandscapeTarget(Selectable.newArray(unit), clampGrid(gx), clampGrid(gy), Action.ATTACK, true);
    }

    /** Move ignoring enemies on the way (retreats, regrouping, sneaking). */
    public int move(@NonNull Collection<? extends Unit> units, int gx, int gy) {
        Selectable<?>[] sel = filterUnits(units);
        if (sel.length == 0)
            return 0;
        player.setLandscapeTarget(sel, clampGrid(gx), clampGrid(gy), Action.MOVE, false);
        return sel.length;
    }

    public void move(@NonNull Unit unit, int gx, int gy) {
        if (!usable(unit))
            return;
        player.setLandscapeTarget(Selectable.newArray(unit), clampGrid(gx), clampGrid(gy), Action.MOVE, false);
    }

    /** Attack-move flagged as defending (units end up idle at the cell, engaging anything within range). */
    public int defend(@NonNull Collection<? extends Unit> units, int gx, int gy) {
        Selectable<?>[] sel = filterUnits(units);
        if (sel.length == 0)
            return 0;
        player.setLandscapeTarget(sel, clampGrid(gx), clampGrid(gy), Action.DEFEND, true);
        return sel.length;
    }

    /** Hunt a specific enemy (unit or building). */
    public int attack(@NonNull Collection<? extends Unit> units, @NonNull Selectable<?> target) {
        if (!alive(target))
            return 0;
        Selectable<?>[] sel = filterUnits(units);
        if (sel.length == 0)
            return 0;
        player.setTarget(sel, target, Action.ATTACK, true);
        return sel.length;
    }

    public void attack(@NonNull Unit unit, @NonNull Selectable<?> target) {
        if (!usable(unit) || !alive(target))
            return;
        player.setTarget(Selectable.newArray(unit), target, Action.ATTACK, true);
    }

    // ------------------------------------------------------------------ peon work

    /** Gather a specific supply node (drops off at the nearest armory). */
    public void gather(@NonNull Unit peon, @NonNull Supply supply) {
        if (!usable(peon) || supply.isEmpty() || supply.isDead())
            return;
        player.setTarget(Selectable.newArray(peon), supply, Action.DEFAULT, false);
    }

    /** Enter a building: peons become workers/breeders, warriors mount towers or recycle their weapon at the armory. */
    public void enter(@NonNull Unit unit, @NonNull Building building) {
        if (!usable(unit) || !complete(building))
            return;
        player.setTarget(Selectable.newArray(unit), building, Action.DEFAULT, false);
    }

    /** Help constructing or repairing a placed building (the peon fetches wood first). */
    public void repair(@NonNull Unit peon, @NonNull Building building) {
        if (!usable(peon) || !usable(building) || !building.isPlaced())
            return;
        player.setTarget(Selectable.newArray(peon), building, Action.GATHER_REPAIR, false);
    }

    /** Walk to an unplaced building and place it, then start building it. */
    public void build(@NonNull Unit peon, @NonNull Building building) {
        if (!usable(peon) || !usable(building))
            return;
        player.setTarget(Selectable.newArray(peon), building, Action.DEFAULT, false);
    }

    /**
     * Create a building object at a cell and send the peons to place/build it. The building is only placed when the
     * first peon arrives and the site is still legal; callers must watch {@link Building#isPlaced()}.
     */
    public @Nullable Building placeBuilding(int template_id, int gx, int gy,
            @NonNull Collection<? extends Unit> peons) {
        if (!player.canBuild(template_id))
            return null;
        BuildingTemplate template = player.getRace().getBuildingTemplate(template_id);
        gx = clampGrid(gx);
        gy = clampGrid(gy);
        if (!template.isPlacingLegal(grid, gx, gy))
            return null;
        Selectable<?>[] sel = filterUnits(peons);
        if (sel.length == 0)
            return null;
        Building building = template.create(player, gx, gy);
        player.setTarget(sel, building, Action.DEFAULT, false);
        log.info("place " + templateName(template_id) + " at " + gx + "," + gy + " with " + sel.length + " peons");
        return building;
    }

    public static @NonNull String templateName(int template_id) {
        return switch (template_id) {
            case com.oddlabs.tt.model.Race.BUILDING_QUARTERS -> "quarters";
            case com.oddlabs.tt.model.Race.BUILDING_ARMORY -> "armory";
            case com.oddlabs.tt.model.Race.BUILDING_TOWER -> "tower";
            default -> "building" + template_id;
        };
    }

    // ------------------------------------------------------------------ buildings

    public void deploy(@NonNull Building building, @NonNull DeployType type, int count) {
        if (count <= 0 || !complete(building))
            return;
        player.deployUnits(building, type, count);
    }

    public void setRally(@NonNull Building building, int gx, int gy) {
        if (!complete(building))
            return;
        player.setRallyPoint(building, new LandscapeTarget(clampGrid(gx), clampGrid(gy)));
    }

    public void setRally(@NonNull Building building, @NonNull Building target) {
        if (!complete(building) || !complete(target))
            return;
        player.setRallyPoint(building, target);
    }

    /**
     * Switch continuous production of each weapon type on or off. An "infinite" order keeps the production
     * container at {@code INFINITE_LIMIT}; a negative finite order drains it, which stops production.
     */
    public void queueWeapons(@NonNull Building armory, boolean rock, boolean iron, boolean rubber) {
        if (!complete(armory) || !armory.getAbilities().hasAbilities(Abilities.BUILD_ARMIES))
            return;
        setProduction(armory, RockAxeWeapon.class, rock);
        setProduction(armory, IronAxeWeapon.class, iron);
        setProduction(armory, RubberAxeWeapon.class, rubber);
    }

    private void setProduction(@NonNull Building armory, @NonNull Class<? extends ThrowingWeapon> type, boolean on) {
        BuildSupplyContainer container = armory.getBuildSupplyContainer(type);
        if (container == null)
            return;
        int queued = container.getNumSupplies();
        if (on && queued < BuildSpinner.INFINITE_LIMIT) {
            orderWeapons(armory, type, BuildSpinner.INFINITE_LIMIT, true);
        } else if (!on && queued > 0) {
            orderWeapons(armory, type, -queued, false);
        }
    }

    private void orderWeapons(@NonNull Building armory, @NonNull Class<? extends ThrowingWeapon> type, int amount,
            boolean infinite) {
        if (type == RockAxeWeapon.class)
            player.buildRockWeapons(armory, amount, infinite);
        else if (type == IronAxeWeapon.class)
            player.buildIronWeapons(armory, amount, infinite);
        else
            player.buildRubberWeapons(armory, amount, infinite);
    }

    public void trainChieftain(@NonNull Building quarters, boolean start) {
        if (!complete(quarters) || !quarters.getAbilities().hasAbilities(Abilities.REPRODUCE))
            return;
        if (start && !quarters.canBuildChieftain())
            return;
        if (!start && !quarters.canStopChieftain())
            return;
        player.trainChieftain(quarters, start);
    }

    public void exitTower(@NonNull Building tower) {
        if (!complete(tower) || !tower.canExitTower())
            return;
        player.exitTower(tower);
    }

    public void recallGatherers(@NonNull Building armory, @NonNull Class<? extends Supply> type, int count) {
        if (count <= 0 || !complete(armory))
            return;
        player.recallGatherers(armory, type, count);
    }

    // ------------------------------------------------------------------ chieftain

    public boolean stun(@NonNull Unit chieftain) {
        if (!usable(chieftain) || !chieftain.canDoMagic(RacesResources.INDEX_MAGIC_STUN))
            return false;
        player.doMagic(chieftain, RacesResources.INDEX_MAGIC_STUN);
        log.info("chieftain STUN at " + chieftain.getGridX() + "," + chieftain.getGridY());
        return true;
    }

    public boolean blast(@NonNull Unit chieftain) {
        if (!usable(chieftain) || !chieftain.canDoMagic(RacesResources.INDEX_MAGIC_BLAST))
            return false;
        player.doMagic(chieftain, RacesResources.INDEX_MAGIC_BLAST);
        log.info("chieftain BLAST at " + chieftain.getGridX() + "," + chieftain.getGridY());
        return true;
    }
}
