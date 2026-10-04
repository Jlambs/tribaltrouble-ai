package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.gui.GUIIcons;
import com.oddlabs.tt.gui.IconQuad;
import com.oddlabs.tt.gui.ModeIconQuads;
import com.oddlabs.tt.gui.RaceIcons;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The game's own icons (gui/icons.png, as its action buttons and status bar show them) for the watch HUD's panels:
 * buildings, units, weapons and resources, each in its race's look. {@link TextPanel.Builder#icon} draws one as high
 * as a line. Every method only reads the races' icon sets, which the game loaded with its textures.
 */
final class HudIcons {
    /** Resources by the inspector's order: wood, rock, iron, chicken. */
    static final int WOOD = 0;
    static final int ROCK = 1;
    static final int IRON = 2;
    static final int CHICKEN = 3;

    private HudIcons() {
    }

    /** The icon of a unit kind (Race.UNIT_*: the three warriors, peon, chieftain) in {@code race}'s look. */
    static @NonNull IconQuad unit(@NonNull Race race, int kind) {
        RaceIcons icons = race.getIcons();
        return normal(switch (kind) {
            case Race.UNIT_WARRIOR_ROCK -> icons.warriorRockIcon();
            case Race.UNIT_WARRIOR_IRON -> icons.warriorIronIcon();
            case Race.UNIT_WARRIOR_RUBBER -> icons.warriorRubberIcon();
            case Race.UNIT_CHIEFTAIN -> icons.chieftainIcon();
            default -> icons.peonIcon();
        });
    }

    /** The icon of a building kind (Race.BUILDING_*: quarters, armory, tower, ship) in {@code race}'s look. */
    static @NonNull IconQuad building(@NonNull Race race, int id) {
        RaceIcons icons = race.getIcons();
        return normal(switch (id) {
            case Race.BUILDING_QUARTERS -> icons.quartersIcon();
            case Race.BUILDING_ARMORY -> icons.armoryIcon();
            case Race.BUILDING_TOWER -> icons.towerIcon();
            default -> icons.shipIcon();
        });
    }

    /** {@code race}'s army icon: a group of warriors (a column of warrior counts). */
    static @NonNull IconQuad army(@NonNull Race race) {
        return normal(race.getIcons().armyIcon());
    }

    /** {@code race}'s small figure of a unit, as the status bar counts units. */
    static @NonNull IconQuad units(@NonNull Race race) {
        return race.getIcons().unitStatusIcon();
    }

    /** A weapon kind (rock, iron, chicken: Race.UNIT_WARRIOR_*'s order) in {@code race}'s look, small. */
    static @NonNull IconQuad weapon(@NonNull Race race, int kind) {
        RaceIcons icons = race.getIcons();
        return switch (kind) {
            case Race.UNIT_WARRIOR_ROCK -> icons.weaponRockStatusIcon();
            case Race.UNIT_WARRIOR_IRON -> icons.weaponIronStatusIcon();
            default -> icons.weaponRubberStatusIcon();
        };
    }

    /** A resource ({@link #WOOD}, {@link #ROCK}, {@link #IRON}, {@link #CHICKEN}), small. */
    static @NonNull IconQuad resource(int resource) {
        GUIIcons icons = GUIIcons.getIcons();
        return switch (resource) {
            case WOOD -> icons.getTreeStatusIcon();
            case ROCK -> icons.getRockStatusIcon();
            case IRON -> icons.getIronStatusIcon();
            default -> icons.getRubberStatusIcon();
        };
    }

    /** A warning triangle. */
    static @NonNull IconQuad warning() {
        return GUIIcons.getIcons().getCheatIcon();
    }

    /** A clock face filled to {@code progress} (0 to 1), red to green: how far a game or a task got. */
    static @NonNull IconQuad progress(float progress) {
        return GUIIcons.getIcons().getWatch(Math.clamp(progress, 0f, 1f));
    }

    /** The infinity sign (an endless order). */
    static @NonNull IconQuad endless() {
        return GUIIcons.getIcons().getInfinite();
    }

    /** The icon of {@code s}'s kind, or null for one of no known kind (or whose getters assert: it just died). */
    static @Nullable IconQuad of(@NonNull Selectable<?> s) {
        try {
            Race race = s.getOwnerNoCheck().getRace();
            if (s instanceof Building building) {
                return building(race, building.getTemplate().getTemplateID());
            }
            if (s instanceof Unit unit) {
                for (int kind = 0; kind <= Race.UNIT_CHIEFTAIN; kind++) {
                    if (unit.getTemplate() == race.getUnitTemplate(kind)) {
                        return unit(race, kind);
                    }
                }
            }
            return null;
        } catch (RuntimeException | AssertionError e) {
            return null;
        }
    }

    private static @NonNull IconQuad normal(@NonNull ModeIconQuads quads) {
        return quads.quad(ModeIconQuads.Mode.NORMAL);
    }
}
