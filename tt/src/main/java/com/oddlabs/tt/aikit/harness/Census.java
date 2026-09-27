package com.oddlabs.tt.aikit.harness;

import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.MountUnitContainer;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.SupplyContainer;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;

/**
 * One player's numbers at one moment, as {@link GameRecorder} writes them every 30 game seconds (the {@code census}
 * lines) and as the harness's result rows report each team's end. Reads only public getters of live objects.
 */
public final class Census {
    /**
     * The census fields, in the order of a census array. The names are the keys of the census lines and of the result
     * rows' A and B blocks; docs/aisim.md explains each.
     */
    public enum Field {
        alive,
        units,
        peons,
        rock,
        iron,
        rubber,
        inside,
        garrison,
        quarters,
        armories,
        towers,
        sites,
        kills,
        lost,
        razed,
        buildingsLost,
        harvestedTree,
        harvestedRock,
        harvestedIron,
        harvestedRubber,
        stockRock,
        stockIron,
        stockRubber,
        chief,
        casts,
        stunned,
        armyX,
        armyY,
        status,
        strength,
        errors;

        /** This field's value in {@code census}. */
        public int of(int @NonNull [] census) {
            return census[ordinal()];
        }

        private void add(int @NonNull [] census, int n) {
            census[ordinal()] += n;
        }

        private void set(int @NonNull [] census, int value) {
            census[ordinal()] = value;
        }
    }

    /** Unit kinds by Race.UNIT_*: the warriors, then peons and the chieftain. */
    static final String[] UNIT_KINDS = {"rock", "iron", "rubber", "peon", "chief"};
    /** The warrior fields by Race.UNIT_WARRIOR_*. */
    private static final Field[] WARRIOR_FIELDS = {Field.rock, Field.iron, Field.rubber};

    private Census() {
    }

    /**
     * The census of {@code player}. The recorder keeps two numbers the player has no getter for: how many of its
     * units were stunned so far, and how many errors its AI swallowed.
     */
    static int @NonNull [] of(@NonNull Player player, int stunned_total, int ai_errors) {
        Race race = player.getRace();
        int[] census = new int[Field.values().length];
        int warriors = 0;
        long sum_x = 0;
        long sum_y = 0;
        int garrison_status = 0;
        for (Selectable<?> s : player.getUnits().getSet()) {
            if (s instanceof LandBuilding building) {
                garrison_status += countBuilding(census, building);
            } else if (s instanceof Unit unit) {
                int kind = kindOf(race, unit);
                if (kind == Race.UNIT_PEON) {
                    Field.peons.add(census, 1);
                } else if (kind <= Race.UNIT_WARRIOR_RUBBER) {
                    WARRIOR_FIELDS[kind].add(census, 1);
                    warriors++;
                    sum_x += unit.getGridX();
                    sum_y += unit.getGridY();
                }
            }
        }
        copyPlayerCounters(census, player);
        Field.stunned.set(census, stunned_total);
        Field.armyX.set(census, warriors > 0 ? (int) (sum_x / warriors) : -1);
        Field.armyY.set(census, warriors > 0 ? (int) (sum_y / warriors) : -1);
        int status = player.getStatus();
        Field.status.set(census, status);
        Field.strength.set(census, status + garrison_status);
        Field.errors.set(census, ai_errors);
        return census;
    }

    /**
     * Counts one building: a site, or a finished building with its occupants and armory stock. Returns the status
     * value of a tower's garrison (0 otherwise): mounted units are not in the player's unit set, so Player.getStatus()
     * leaves them out.
     */
    private static int countBuilding(int @NonNull [] census, @NonNull LandBuilding building) {
        if (!building.isComplete()) {
            Field.sites.add(census, 1);
            return 0;
        }
        int garrison_status = 0;
        switch (building.getTemplate().getTemplateID()) {
            case Race.BUILDING_QUARTERS -> {
                Field.quarters.add(census, 1);
                Field.inside.add(census, building.getUnitCount());
            }
            case Race.BUILDING_ARMORY -> {
                Field.armories.add(census, 1);
                Field.inside.add(census, building.getUnitCount());
                Field.stockRock.add(census, stock(building, RockAxeWeapon.class));
                Field.stockIron.add(census, stock(building, IronAxeWeapon.class));
                Field.stockRubber.add(census, stock(building, RubberAxeWeapon.class));
            }
            case Race.BUILDING_TOWER -> {
                Field.towers.add(census, 1);
                Field.garrison.add(census, building.getUnitCount());
                if (building.getUnitContainer() instanceof MountUnitContainer mount && mount.getUnit() != null) {
                    garrison_status = mount.getUnit().getStatusValue();
                }
            }
            default -> {
            }
        }
        return garrison_status;
    }

    /** The census fields that are plain player getters. */
    private static void copyPlayerCounters(int @NonNull [] census, @NonNull Player player) {
        Field.alive.set(census, player.isAlive() ? 1 : 0);
        Field.units.set(census, player.getUnitCountContainer().getNumSupplies());
        Field.kills.set(census, player.getUnitsKilled());
        Field.lost.set(census, player.getUnitsLost());
        Field.razed.set(census, player.getBuildingsDestroyed());
        Field.buildingsLost.set(census, player.getBuildingsLost());
        Field.harvestedTree.set(census, player.getTreeHarvested());
        Field.harvestedRock.set(census, player.getRockHarvested());
        Field.harvestedIron.set(census, player.getIronHarvested());
        Field.harvestedRubber.set(census, player.getRubberHarvested());
        Field.chief.set(census, player.hasActiveChieftain() ? 1 : 0);
        Field.casts.set(census, player.getMagics());
    }

    /** The unit's kind, an index of {@link #UNIT_KINDS}; a unit of no known template counts as a peon. */
    static int kindOf(@NonNull Race race, @NonNull Unit unit) {
        for (int kind = 0; kind < UNIT_KINDS.length; kind++) {
            if (unit.getTemplate() == race.getUnitTemplate(kind)) {
                return kind;
            }
        }
        return Race.UNIT_PEON;
    }

    /** The weapons of one kind an armory holds. */
    private static int stock(@NonNull LandBuilding armory, @NonNull Class<?> weapon) {
        SupplyContainer container = armory.getSupplyContainer(weapon);
        return container == null ? 0 : container.getNumSupplies();
    }
}
