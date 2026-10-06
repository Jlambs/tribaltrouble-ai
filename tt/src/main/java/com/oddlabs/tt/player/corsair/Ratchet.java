package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The deck drill: idle warriors at home walk aboard an idle docked ship and are let out again, over and over. A
 * warrior seated on the upper deck gets +10 range (ShipHR.tryAllocate) and gives back only 8 when it leaves
 * (ShipHR.exitUnit), so every trip leaves it 2 more range for good (exploit audit A12). Both halves are plain UI
 * orders: right-click the ship, press its deploy button. Range costs nothing in accuracy (the hit is rolled at the
 * throw), so a drilled army throws first and, with targets picked for it, from beyond the reach of towers.
 */
final class Ratchet {
    private final @NonNull CorsairAI ai;
    private final @NonNull Player me;

    Ratchet(@NonNull CorsairAI ai) {
        this.ai = ai;
        this.me = ai.me();
    }

    /** The range a warrior has gained above its weapon's, in cells. */
    static float bonus(@NonNull Unit u) {
        return u.getRange(u) - u.getWeaponFactory().getRange() - u.getSize();
    }

    void think(@NonNull Intel in, int second) {
        if (!ai.params.ratchet || !ai.colony.founded() || ai.army.homeThreatened())
            return;
        float goal = 2f * ai.params.ratchet_trips;
        List<Ship> ships = ai.fleet.idleAtHome();
        if (ships.isEmpty())
            return;
        List<Unit> free = new ArrayList<>();
        for (Unit w : ai.army.freeWarriorsAtHome(in))
            if (bonus(w) < goal - .5f && w.getPrimaryController() instanceof IdleController)
                free.add(w);
        for (Ship s : ships) {
            int aboard = Fleet.warriorsAboard(s);
            if (aboard > 0)
                ai.fleet.landWarriors(s);
            int room = Math.min(8, 26 - aboard);
            if (room <= 0 || free.isEmpty())
                continue;
            Colony.sortByDistance(free, s.getGridX(), s.getGridY());
            List<Unit> go = new ArrayList<>();
            while (go.size() < room && !free.isEmpty())
                go.add(free.removeFirst());
            for (Unit u : go)
                ai.reserve(u, 12);
            me.setTarget(go.toArray(new Unit[0]), s, Action.MOVE, false);
            ai.log.count("ratchet_board");
        }
    }

    /** Mean range bonus of our warriors, for the log. */
    static float meanBonus(@NonNull List<@NonNull Unit> warriors) {
        if (warriors.isEmpty())
            return 0;
        float sum = 0;
        for (Unit w : warriors)
            sum += bonus(w);
        return sum / warriors.size();
    }
}
