package com.oddlabs.tt.aikit;

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
 * One player's numbers at one moment, as {@link GameRecorder} writes them every 30 game seconds (the {@code tl}
 * lines) and as the harness's result rows report each team's end. Reads only public getters of live objects.
 */
public final class Census {
    /**
     * The census fields, in the order of a census array. Names and order are the keys of the tl lines and of the
     * result rows' A and B blocks: never rename or reorder them. docs/aisim.md explains each.
     */
    public enum Field {
        alive,
        units,
        peons,
        rock,
        iron,
        rubber,
        inside,
        tower,
        Q,
        A,
        T,
        sites,
        kills,
        lost,
        razed,
        bLost,
        hTree,
        hRock,
        hIron,
        hRubber,
        sRock,
        sIron,
        sRubber,
        chief,
        magics,
        stunned,
        ax,
        ay,
        status,
        strength,
        err;

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
        int[] counts = new int[Field.values().length];
        int warriors = 0;
        long sum_x = 0;
        long sum_y = 0;
        int garrison_status = 0;
        for (Selectable<?> s : player.getUnits().getSet()) {
            if (s instanceof LandBuilding b) {
                garrison_status += countBuilding(counts, b);
            } else if (s instanceof Unit u) {
                int kind = kindOf(race, u);
                if (kind == Race.UNIT_PEON) {
                    Field.peons.add(counts, 1);
                } else if (kind <= Race.UNIT_WARRIOR_RUBBER) {
                    WARRIOR_FIELDS[kind].add(counts, 1);
                    warriors++;
                    sum_x += u.getGridX();
                    sum_y += u.getGridY();
                }
            }
        }
        copyPlayerCounters(counts, player);
        Field.stunned.set(counts, stunned_total);
        Field.ax.set(counts, warriors > 0 ? (int) (sum_x / warriors) : -1);
        Field.ay.set(counts, warriors > 0 ? (int) (sum_y / warriors) : -1);
        int status = player.getStatus();
        Field.status.set(counts, status);
        Field.strength.set(counts, status + garrison_status);
        Field.err.set(counts, ai_errors);
        return counts;
    }

    /**
     * Counts one building: a site, or a finished building with its occupants and armory stock. Returns the status
     * value of a tower's mounted unit (0 otherwise): mounted units are not in the player's unit set, so
     * Player.getStatus() leaves them out.
     */
    private static int countBuilding(int @NonNull [] counts, @NonNull LandBuilding b) {
        if (!b.isComplete()) {
            Field.sites.add(counts, 1);
            return 0;
        }
        int garrison_status = 0;
        switch (b.getTemplate().getTemplateID()) {
            case Race.BUILDING_QUARTERS -> {
                Field.Q.add(counts, 1);
                Field.inside.add(counts, b.getUnitCount());
            }
            case Race.BUILDING_ARMORY -> {
                Field.A.add(counts, 1);
                Field.inside.add(counts, b.getUnitCount());
                Field.sRock.add(counts, stock(b, RockAxeWeapon.class));
                Field.sIron.add(counts, stock(b, IronAxeWeapon.class));
                Field.sRubber.add(counts, stock(b, RubberAxeWeapon.class));
            }
            case Race.BUILDING_TOWER -> {
                Field.T.add(counts, 1);
                Field.tower.add(counts, b.getUnitCount());
                if (b.getUnitContainer() instanceof MountUnitContainer mount && mount.getUnit() != null) {
                    garrison_status = mount.getUnit().getStatusValue();
                }
            }
            default -> {
            }
        }
        return garrison_status;
    }

    /** The census fields that are plain player getters. */
    private static void copyPlayerCounters(int @NonNull [] counts, @NonNull Player player) {
        Field.alive.set(counts, player.isAlive() ? 1 : 0);
        Field.units.set(counts, player.getUnitCountContainer().getNumSupplies());
        Field.kills.set(counts, player.getUnitsKilled());
        Field.lost.set(counts, player.getUnitsLost());
        Field.razed.set(counts, player.getBuildingsDestroyed());
        Field.bLost.set(counts, player.getBuildingsLost());
        Field.hTree.set(counts, player.getTreeHarvested());
        Field.hRock.set(counts, player.getRockHarvested());
        Field.hIron.set(counts, player.getIronHarvested());
        Field.hRubber.set(counts, player.getRubberHarvested());
        Field.chief.set(counts, player.hasActiveChieftain() ? 1 : 0);
        Field.magics.set(counts, player.getMagics());
    }

    /** The unit's kind, an index of {@link #UNIT_KINDS}; a unit of no known template counts as a peon. */
    static int kindOf(@NonNull Race race, @NonNull Unit u) {
        for (int kind = 0; kind < UNIT_KINDS.length; kind++) {
            if (u.getTemplate() == race.getUnitTemplate(kind)) {
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
