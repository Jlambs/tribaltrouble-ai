package com.oddlabs.tt.player.privateer;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Warriors on the decks of our own ships at home. A seat on the upper deck adds 10 to a warrior's range while it sits
 * there (ShipHR.tryAllocate) and leaving takes the 10 back (ShipHR.exitUnit), so a docked ship is a gun platform and
 * nothing more: no range is kept after leaving.
 *
 * <p>deck_defence: while raiders are at the base, idle ships docked near them take warriors on deck, where they shoot
 * 10 cells farther; they land again once the raid is over.
 *
 * <p>deck_garrison (warriors per ship): idle warriors at home keep that many seats of each idle ship at home filled,
 * as a battery on our shore; an operation that takes the ship takes them along. Without it every warrior aboard an
 * idle ship at home is landed.
 */
final class Decks {
    private final @NonNull PrivateerAI ai;
    /** Per deck walker: the ship it was sent to. */
    private final Map<@NonNull Unit, @NonNull Ship> walking = new LinkedHashMap<>();

    Decks(@NonNull PrivateerAI ai) {
        this.ai = ai;
    }

    void think(@NonNull Intel in) {
        if (!ai.colony.founded())
            return;
        if (ai.params.deck_defence && ai.army.homeThreatened()) {
            defend(in);
            return;
        }
        if (ai.params.deck_garrison > 0) {
            garrison(in);
            return;
        }
        for (Ship s : ai.fleet.idleAtHome())
            if (Fleet.warriorsAboard(s) > 0)
                ai.fleet.landWarriors(s);
    }

    /** Forgets the walkers that boarded, died or were let go (their reservation ran out). */
    private void forget() {
        walking.entrySet().removeIf(e -> e.getKey().isDead() || e.getValue().isDead() || e.getKey().isMounted()
                || !ai.reserved(e.getKey()));
    }

    private void garrison(@NonNull Intel in) {
        forget();
        List<Unit> free = null;
        for (Ship s : ai.fleet.idleAtHome()) {
            if (Fleet.pending(s, com.oddlabs.tt.model.DeployType.IRON_WARRIOR) > 0
                    || Fleet.pending(s, com.oddlabs.tt.model.DeployType.ROCK_WARRIOR) > 0)
                continue; // still landing (an op came home): board once it is done
            int want = Math.min(26, ai.params.deck_garrison) - Fleet.warriorsAboard(s);
            for (Ship w : walking.values())
                if (w == s)
                    want--;
            if (want <= 0)
                continue;
            if (free == null)
                free = ai.army.freeWarriorsAtHome(in);
            Colony.sortByDistance(free, s.getGridX(), s.getGridY());
            List<Unit> go = new ArrayList<>();
            while (go.size() < want && !free.isEmpty()) {
                Unit w = free.removeFirst();
                if (Geo.dist2(w.getGridX(), w.getGridY(), s.getGridX(), s.getGridY()) <= 60 * 60)
                    go.add(w);
            }
            if (go.isEmpty())
                continue;
            for (Unit u : go) {
                ai.reserve(u, 30);
                walking.put(u, s);
            }
            ai.order(go, s, Action.MOVE, false);
            ai.log.count("deck_garrison");
        }
    }

    /** Warriors seated on our idle ships at home: an operation that takes the ships takes them along. */
    int aboardAtHome(@NonNull List<@NonNull Ship> ships) {
        if (ai.params.deck_garrison <= 0)
            return 0;
        int n = 0;
        for (Ship s : ships)
            n += Math.min(26, Fleet.warriorsAboard(s));
        return n;
    }

    private void defend(@NonNull Intel in) {
        List<Unit> raiders = new ArrayList<>();
        for (Unit u : ai.army.home_threats)
            if (!u.isDead() && (u.isWarrior() || u.isChieftain()))
                raiders.add(u);
        if (raiders.isEmpty())
            return;
        List<Unit> free = ai.army.freeWarriorsAtHome(in);
        for (Ship s : ai.fleet.idleAtHome()) {
            boolean near = false;
            for (Unit r : raiders)
                if (Geo.dist2(r.getGridX(), r.getGridY(), s.getGridX(), s.getGridY()) <= 40 * 40) {
                    near = true;
                    break;
                }
            if (!near)
                continue;
            ai.fleet.cancelDeploys(s);
            int room = 26 - Fleet.warriorsAboard(s);
            if (room <= 0 || free.isEmpty())
                continue;
            Colony.sortByDistance(free, s.getGridX(), s.getGridY());
            List<Unit> go = new ArrayList<>();
            while (go.size() < room && !free.isEmpty()) {
                Unit w = free.removeFirst();
                if (Geo.dist2(w.getGridX(), w.getGridY(), s.getGridX(), s.getGridY()) <= 30 * 30)
                    go.add(w);
            }
            if (go.isEmpty())
                continue;
            for (Unit u : go) {
                ai.reserve(u, 15);
                walking.put(u, s);
            }
            ai.order(go, s, Action.MOVE, false);
            ai.log.count("deck_defence");
        }
    }

    /**
     * Halts the deck walkers still on their way to a ship an operation takes (each stops where it stands), so no one
     * but the operation's own boards it. Returns how many were stopped.
     */
    int haltInbound(@NonNull Ship s) {
        forget();
        List<Unit> stop = new ArrayList<>();
        for (Map.Entry<Unit, Ship> e : walking.entrySet())
            if (e.getValue() == s && PrivateerAI.orderable(e.getKey()) && ai.me().getUnits().getSet().contains(
                    e.getKey()))
                stop.add(e.getKey());
        for (Unit u : stop) {
            ai.move(List.of(u), u.getGridX(), u.getGridY(), Action.MOVE, false);
            walking.remove(u);
        }
        return stop.size();
    }
}
