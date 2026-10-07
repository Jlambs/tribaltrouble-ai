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
    /** Per ship, the game second of our last drill boarding order to it. */
    private final java.util.Map<@NonNull Ship, Integer> last_board = new java.util.LinkedHashMap<>();
    /** Per drill walker: the ship it was sent to. */
    private final java.util.Map<@NonNull Unit, @NonNull Ship> walking = new java.util.LinkedHashMap<>();

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
        float goal = goal(in);
        List<Ship> ships = ai.fleet.idleAtHome();
        if (ships.isEmpty())
            return;
        List<Unit> free = new ArrayList<>();
        List<Unit> home = ai.army.freeWarriorsAtHome(in);
        // elite_trips: the most drilled few (one per tower and two more) drill on to garrison the towers
        List<Unit> elite = new ArrayList<>();
        if (ai.params.elite_trips > ai.params.ratchet_trips) {
            List<Unit> by_bonus = new ArrayList<>(home);
            by_bonus.sort((a, b) -> {
                int c = Float.compare(bonus(b), bonus(a));
                if (c != 0)
                    return c;
                c = Integer.compare(a.getGridX(), b.getGridX());
                return c != 0 ? c : Integer.compare(a.getGridY(), b.getGridY());
            });
            int k = Math.min(by_bonus.size(), in.towers.size() + 2);
            elite.addAll(by_bonus.subList(0, k));
        }
        float elite_goal = Math.max(2f * ai.params.elite_trips,
                goal + 2f * (ai.params.elite_trips - ai.params.ratchet_trips));
        for (Unit w : home) {
            if (!(w.getPrimaryController() instanceof IdleController))
                continue;
            if (elite.contains(w) ? bonus(w) < elite_goal - .5f : bonus(w) < goal - .5f)
                free.add(w);
        }
        for (Ship s : ships) {
            int aboard = Fleet.warriorsAboard(s);
            if (aboard > 0)
                ai.fleet.landWarriors(s);
            int room = Math.min(8, 18 - aboard);
            if (room <= 0 || free.isEmpty())
                continue;
            Colony.sortByDistance(free, s.getGridX(), s.getGridY());
            List<Unit> go = new ArrayList<>();
            while (go.size() < room && !free.isEmpty())
                go.add(free.removeFirst());
            for (Unit u : go)
                ai.reserve(u, 12);
            ai.order(go, s, Action.MOVE, false);
            last_board.put(s, second);
            for (Unit u : go)
                walking.put(u, s);
            ai.log.count("ratchet_board");
        }
    }

    /**
     * Halts the drill warriors still walking to a ship an operation takes (each stops where it stands), so no one but
     * the operation's own boards it. Returns how many were stopped.
     */
    int haltInbound(@NonNull Ship s) {
        int n = 0;
        java.util.List<Unit> stop = new ArrayList<>();
        for (java.util.Map.Entry<Unit, Ship> e : walking.entrySet())
            if (e.getValue() == s && CorsairAI.orderable(e.getKey()) && me.getUnits().getSet().contains(e.getKey()))
                stop.add(e.getKey());
        for (Unit u : stop) {
            ai.move(java.util.List.of(u), u.getGridX(), u.getGridY(), Action.MOVE, false);
            walking.remove(u);
            n++;
        }
        walking.entrySet().removeIf(e -> e.getKey().isDead() || e.getValue().isDead() || e.getKey().isMounted());
        return n;
    }

    /**
     * The drill goal in cells: ratchet_trips trips, or with drill_adapt enough to outreach the enemy warriors seen by
     * that many cells (their mean drill), at most drill_max trips. Drilling deeper than the enemy needs only delays
     * the attacks (no-drill v4: 8 trips elim -.16); against a drilled enemy range wins fights (v5c: kd30 +85..+169).
     */
    float goal(@NonNull Intel in) {
        // late_drill: at the cap that late in the game neither side attacks an equal defence; drill on to
        // late_trips, so our warriors outreach theirs and their towers by far
        if (ai.params.late_drill > 0 && !late && ai.now() >= ai.params.late_drill
                && in.units() >= me.getWorld().getMaxUnitCount() - 10) {
            late = true;
            ai.log.log("DRILL", () -> "late drill to " + ai.params.late_trips + " trips t" + ai.now());
        }
        if (late)
            return Math.max(2f * ai.params.late_trips, goalBefore(in));
        return goalBefore(in);
    }

    /** late_drill: set once the late drill began (it goes on even when units are lost). */
    private boolean late;

    private float goalBefore(@NonNull Intel in) {
        float base = 2f * ai.params.ratchet_trips;
        if (ai.params.drill_adapt <= 0 || in.enemy_warriors.isEmpty())
            return base;
        float theirs = 0;
        int n = 0;
        for (Unit e : in.enemy_warriors)
            if (!e.isChieftain()) {
                theirs += bonus(e);
                n++;
            }
        if (n == 0)
            return base;
        float want = theirs / n + (float) ai.params.drill_adapt;
        return Math.min(2f * ai.params.drill_max, Math.max(base, want));
    }

    /** Whether drill warriors may still be walking to the ship (ordered within the last 20 s). */
    boolean inbound(@NonNull Ship s, int second) {
        last_board.keySet().removeIf(Ship::isDead);
        Integer t = last_board.get(s);
        return t != null && second - t < 20;
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
