package com.oddlabs.tt.player.ai;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.player.AdvancedAI;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The expert AI's diary of a real game: what it decides as it decides it, and a report on every player each minute,
 * written next to the game's other logs so that a game against a human can be gone over afterwards. Lines are
 * collected and appended every few seconds, so no file stays open between writes.
 */
final class GameLog {
    private static final Logger logger = Logger.getLogger(GameLog.class.getName());

    private final @NonNull Path file;
    private final StringBuilder pending = new StringBuilder();
    private boolean failed;

    GameLog(@NonNull Path file) {
        this.file = file;
    }

    void add(@NonNull String line) {
        pending.append(line).append(System.lineSeparator());
    }

    void flush() {
        if (pending.isEmpty() || failed)
            return;
        try {
            Files.writeString(file, pending, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            failed = true;
            logger.log(Level.WARNING, "Cannot write the expert AI log " + file, e);
        }
        pending.setLength(0);
    }

    /** Game time as minutes and seconds. */
    static @NonNull String clock(float seconds) {
        int tenths = Math.round(seconds * 10f);
        return String.format(Locale.ROOT, "%2d:%02d.%d", tenths / 600, tenths % 600 / 10, tenths % 10);
    }

    /** One line on what a player has: units out and inside, warriors by weapon, buildings, stock, and losses. */
    static @NonNull String describe(@NonNull Player p) {
        int peons = 0;
        int[] warriors = new int[Intel.WarriorType.values().length];
        int chief_hp = -1;
        int quarters = 0;
        int armories = 0;
        int towers = 0;
        int manned = 0;
        int sites = 0;
        int iron = 0;
        int chicken = 0;
        int rock = 0;
        for (Selectable<?> s : p.getUnits().getSet()) {
            if (s.isDead())
                continue;
            if (s instanceof Unit u) {
                if (u.isMounted())
                    continue;
                if (u.getAbilities().hasAbilities(Abilities.MAGIC))
                    chief_hp = u.getHitPoints();
                else if (u.getAbilities().hasAbilities(Abilities.THROW))
                    warriors[Intel.warriorType(u).ordinal()]++;
                else if (u.getAbilities().hasAbilities(Abilities.HARVEST))
                    peons++;
            } else if (s instanceof Building b) {
                if (b.getTemplate().getType() != BuildingTemplate.TYPE_BUILDING)
                    continue;
                if (!b.isComplete()) {
                    sites++;
                    continue;
                }
                switch (b.getTemplate().getTemplateID()) {
                    case Race.BUILDING_QUARTERS -> quarters++;
                    case Race.BUILDING_ARMORY -> {
                        armories++;
                        iron += b.getSupplyContainer(IronAxeWeapon.class).getNumSupplies();
                        chicken += b.getSupplyContainer(RubberAxeWeapon.class).getNumSupplies();
                        rock += b.getSupplyContainer(RockAxeWeapon.class).getNumSupplies();
                    }
                    case Race.BUILDING_TOWER -> {
                        towers++;
                        if (Intel.isTowerManned(b))
                            manned++;
                    }
                    default -> {
                    }
                }
            }
        }
        int out = peons + warriors[0] + warriors[1] + warriors[2] + (chief_hp >= 0 ? 1 : 0) + manned;
        int total = p.getUnitCountContainer().getNumSupplies();
        String race = p.getPlayerInfo().getRace() == RacesResources.RACE_VIKINGS ? "vikings" : "natives";
        String chief = chief_hp >= 0 ? "chieftain " + chief_hp + "hp" : "no chieftain";
        StringBuilder line = new StringBuilder();
        line.append(String.format(Locale.ROOT, "%s (%s, %s, team %d): %d units, %d inside buildings",
                p.getPlayerInfo().getName(), race, controller(p), p.getPlayerInfo().getTeam(), total,
                Math.max(0, total - out)));
        line.append(String.format(Locale.ROOT, " | out: %d peons, warriors r%d i%d c%d, %s", peons, warriors[0],
                warriors[1], warriors[2], chief));
        line.append(String.format(Locale.ROOT, " | Q%d A%d T%d (%d manned) +%d sites", quarters, armories, towers,
                manned, sites));
        line.append(String.format(Locale.ROOT, " | armory stock i%d c%d r%d", iron, chicken, rock));
        line.append(String.format(Locale.ROOT, " | killed %d, lost %d, razed %d", p.getUnitsKilled(),
                p.getUnitsLost(), p.getBuildingsDestroyed()));
        return line.toString();
    }

    private static @NonNull String controller(@NonNull Player p) {
        if (p.getAI() == null)
            return "human";
        if (p.getAI() instanceof ExpertAI)
            return "expert AI";
        return p.getAI() instanceof AdvancedAI ? "AI" : "computer";
    }
}
