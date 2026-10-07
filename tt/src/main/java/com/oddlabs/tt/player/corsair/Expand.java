package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * expand: when the home island runs out of iron, the colony moves. A ship takes peons and an escort to the best
 * island no enemy builds on, the colony is founded again there (quarters and armory follow from the plan), and a
 * ferry brings over whoever is left at the old home. Small islands run dry: a home with 15 iron nodes had none left
 * by 12 minutes, and 120 peons then sat in the armory for the rest of the game with nothing to make.
 */
final class Expand {
    private enum State {
        NONE,
        BOARD,
        SAIL,
        FERRY
    }

    private final @NonNull CorsairAI ai;
    private final @NonNull Player me;
    private @NonNull State state = State.NONE;
    private @Nullable Ship ship;
    private int since;
    private int cooldown;
    private Geo.@Nullable Isle target;
    private int bx, by, lx, ly;
    /** The home we left, and its dock. */
    private int old_island;
    private int old_dx, old_dy;
    private @Nullable Ship ferry;

    private enum Phase {
        CREW,
        GO,
        LOAD,
        BACK
    }

    private @NonNull Phase phase = Phase.CREW;
    private int ferry_since;
    private int moves;

    Expand(@NonNull CorsairAI ai) {
        this.ai = ai;
        this.me = ai.me();
    }

    /** Whether the ship is the expedition's or the ferry's (no one else may take it). */
    boolean owns(@NonNull Ship s) {
        return s == ship && state != State.NONE && state != State.FERRY || s == ferry;
    }

    void think(@NonNull Intel in, int second) {
        if (ai.params.expand <= 0 || !ai.colony.founded())
            return;
        switch (state) {
            case NONE -> {
                if (second % 30 == 0 && second >= Math.max(cooldown, 300) && moves < 3)
                    consider(in, second);
            }
            case BOARD -> board(in, second);
            case SAIL -> sail(in, second);
            case FERRY -> {
                if (second % 5 == 0)
                    ferry(in, second);
                if (second % 30 == 0 && second >= cooldown && moves < 3)
                    consider(in, second);
            }
        }
    }

    // --- moving ----------------------------------------------------------------------------------------------------

    private void consider(@NonNull Intel in, int second) {
        Colony c = ai.colony;
        if (ferry != null)
            return;
        int stock = 0;
        for (Building a : in.armories)
            if (a.getIslandId() == c.island && a.isComplete())
                stock += a.getSupplyContainer(IronSupply.class).getNumSupplies();
        if (stock >= 15)
            return;
        Geo geo = ai.geo;
        geo.scanSupplies();
        int iron = geo.totalsOn(c.island)[2];
        if (iron > ai.params.expand)
            return;
        geo.rateIsles();
        int[] dock = ai.fleet.homeDock();
        int[] field = ai.sea.field(dock[0], dock[1]);
        Geo.Isle best = null;
        float best_score = 0;
        int[] best_spot = null;
        for (Geo.Isle isle : geo.isles) {
            if (isle.id == c.island || isle.score < 8f || geo.totalsOn(isle.id)[2] <= 2 * ai.params.expand)
                continue;
            boolean enemy = false;
            for (Building b : in.enemy_buildings)
                if (b.getIslandId() == isle.id && !(b instanceof Ship)) {
                    enemy = true;
                    break;
                }
            if (enemy)
                continue;
            // wood counts most here: iron axes and every building take it (a 2-tree island once starved)
            if (geo.totalsOn(isle.id)[0] < 150)
                continue;
            for (int[] cand : geo.candidates(isle, .6f, 20)) {
                int[] w = geo.windowOn(cand[0] / Geo.BIN, cand[1] / Geo.BIN, 3, isle.id);
                float quality = 3f * Math.min(w[2], 8) + .4f * Math.min(w[0], 60) + .3f * Math.min(w[1], 6);
                float[] r = ai.fleet.reach(field, isle, cand[0], cand[1]);
                if (r[0] == Float.MAX_VALUE)
                    continue;
                float score = quality - r[0] / 20f;
                if (best == null || score > best_score) {
                    best = isle;
                    best_score = score;
                    best_spot = new int[]{cand[0], cand[1], (int) r[1], (int) r[2]};
                }
            }
        }
        if (best == null || best_spot == null) {
            cooldown = second + 300;
            return;
        }
        List<Ship> ships = ai.fleet.idleAtHome();
        if (ships.isEmpty())
            return;
        Ship s = ships.getFirst();
        target = best;
        bx = best_spot[0];
        by = best_spot[1];
        lx = best_spot[2];
        ly = best_spot[3];
        ship = s;
        ai.fleet.cancelDeploys(s);
        ai.ratchet.haltInbound(s);
        // the settlers: peons (they row on the way), and an escort of warriors
        List<Unit> go = new ArrayList<>();
        for (Unit u : in.peons)
            if (go.size() < 45 && u.getIslandId() == c.island && !ai.reserved(u)
                    && (u.getPrimaryController() instanceof IdleController
                            || u.getPrimaryController() instanceof GatherController<?>))
                go.add(u);
        if (go.size() < 45)
            ai.colony.releasePeons(in, 45 - go.size());
        List<Unit> free = ai.army.freeWarriorsAtHome(in);
        go.addAll(free.subList(0, Math.min(20, free.size())));
        for (Unit u : go)
            ai.reserve(u, 120);
        ai.order(go, s, Action.MOVE, false);
        state = State.BOARD;
        since = second;
        Geo.Isle t = best;
        int n = go.size(), left = iron;
        ai.log.log("EXPAND",
                () -> "home has " + left + " iron nodes: settling " + t + " base " + bx + "," + by + " landing " + lx + "," + ly + " with " + n + " units t" + second);
        ai.log.count("expand");
    }

    private void board(@NonNull Intel in, int second) {
        Ship s = ship;
        if (s == null || s.isDead()) {
            reset(second);
            return;
        }
        // stragglers: peons that came out of the quarters join
        if (second % 5 == 0 && second - since < 50) {
            List<Unit> more = new ArrayList<>();
            for (Unit u : in.peons)
                if (u.getIslandId() == ai.colony.island && !ai.reserved(u)
                        && u.getPrimaryController() instanceof IdleController && more.size() < 10)
                    more.add(u);
            if (Fleet.peonsAboard(s) < 40 && !more.isEmpty()) {
                for (Unit u : more)
                    ai.reserve(u, 90);
                ai.order(more, s, Action.MOVE, false);
            }
        }
        if (second - since < 60)
            return;
        if (Fleet.peonsAboard(s) < 10) {
            ai.log.log("EXPAND", () -> "too few aboard (" + Fleet.peonsAboard(s) + "), staying t" + second);
            ai.fleet.landAll(s);
            reset(second);
            return;
        }
        Geo.Isle t = target;
        if (t == null) {
            reset(second);
            return;
        }
        ai.fleet.goTo(s, t.id, lx, ly, second);
        ai.fleet.duty(s).land_all = true;
        ai.fleet.landAll(s);
        state = State.SAIL;
        since = second;
    }

    private void sail(@NonNull Intel in, int second) {
        Ship s = ship;
        Geo.Isle t = target;
        if (s == null || s.isDead() || t == null) {
            reset(second);
            return;
        }
        Fleet.Duty d = ai.fleet.duty(s);
        if (d.job == Fleet.Job.FAILED || second - since > 900) {
            ai.log.log("EXPAND", () -> "could not reach " + t + " t" + second);
            ai.fleet.goHome(s, second);
            reset(second);
            return;
        }
        if ((d.job == Fleet.Job.ARRIVED || d.job == Fleet.Job.IDLE) && Fleet.dockedAt(s) == t.id) {
            Colony c = ai.colony;
            old_island = c.island;
            int[] dock = ai.fleet.homeDock();
            old_dx = dock[0];
            old_dy = dock[1];
            c.relocate(t.id, bx, by, lx, ly);
            ai.fleet.home_field = ai.sea.field(c.dock_x, c.dock_y);
            moves++;
            ship = null;
            state = State.FERRY;
            cooldown = second + 900;
            ai.log.log("EXPAND",
                    () -> "settled on " + t + " base " + bx + "," + by + "; old home isle " + old_island + " t" + second);
        }
    }

    private void reset(int second) {
        ship = null;
        state = old_island != 0 ? State.FERRY : State.NONE;
        cooldown = second + 600;
    }

    // --- the ferry -------------------------------------------------------------------------------------------------

    /** Our units left on the old home island that could come over (no chieftain: a bench assertion). */
    private List<@NonNull Unit> leftBehind(@NonNull Intel in) {
        List<Unit> out = new ArrayList<>();
        for (Unit u : in.peons)
            if (u.getIslandId() == old_island && !ai.reserved(u))
                out.add(u);
        for (Unit w : in.warriors)
            if (w.getIslandId() == old_island && !ai.reserved(w) && Campaign.isFree(w))
                out.add(w);
        return out;
    }

    private int insideOld(@NonNull Intel in) {
        int n = 0;
        for (List<Building> l : List.of(in.quarters, in.armories))
            for (Building b : l)
                if (b.getIslandId() == old_island && b.isComplete() && b.getUnitContainer() != null)
                    n += b.getUnitContainer().getNumSupplies();
        return n;
    }

    private void ferry(@NonNull Intel in, int second) {
        if (old_island == 0 || old_island == ai.colony.island)
            return;
        Ship f = ferry;
        if (f != null && f.isDead()) {
            ferry = null;
            f = null;
        }
        if (f == null) {
            List<Unit> left = leftBehind(in);
            if (left.size() + insideOld(in) < 8)
                return;
            List<Ship> ships = ai.fleet.idleAtHome();
            if (ships.isEmpty())
                return;
            f = ships.getFirst();
            ferry = f;
            phase = Phase.CREW;
            ferry_since = second;
            ai.fleet.cancelDeploys(f);
            ai.ratchet.haltInbound(f);
            // rowers: the ships at home landed theirs
            List<Unit> crew = new ArrayList<>();
            for (Unit u : in.peons)
                if (crew.size() < 16 && u.getIslandId() == ai.colony.island && !ai.reserved(u)
                        && (u.getPrimaryController() instanceof IdleController
                                || u.getPrimaryController() instanceof GatherController<?>))
                    crew.add(u);
            for (Unit u : crew)
                ai.reserve(u, 60);
            ai.order(crew, f, Action.MOVE, false);
            int n = left.size();
            ai.log.log("FERRY", () -> "fetching " + n + " from isle " + old_island + " t" + second);
            ai.log.count("ferry");
            return;
        }
        Fleet.Duty d = ai.fleet.duty(f);
        switch (phase) {
            case CREW -> {
                if (Fleet.peonsAboard(f) >= 10 || second - ferry_since >= 45) {
                    if (Fleet.peonsAboard(f) < 3) {
                        ferry = null;
                        return;
                    }
                    ai.fleet.goTo(f, old_island, old_dx, old_dy, second);
                    phase = Phase.GO;
                    ferry_since = second;
                }
            }
            case GO -> {
                if (d.job == Fleet.Job.ARRIVED && Fleet.dockedAt(f) == old_island) {
                    phase = Phase.LOAD;
                    ferry_since = second;
                } else if (d.job == Fleet.Job.FAILED || second - ferry_since > 600) {
                    ai.fleet.goHome(f, second);
                    d.land_all = true;
                    ai.fleet.landAll(f);
                    phase = Phase.BACK;
                    ferry_since = second;
                }
            }
            case LOAD -> {
                // everyone out of the old buildings, then aboard
                for (List<Building> l : List.of(in.quarters, in.armories))
                    for (Building b : l)
                        if (b.getIslandId() == old_island && b.isComplete() && b.getUnitContainer() != null) {
                            int k = b.getUnitContainer().getNumSupplies() - Colony.pendingDeploys(b);
                            if (k > 0)
                                me.deployUnits(b, DeployType.PEON, k);
                        }
                List<Unit> left = leftBehind(in);
                int peons = 0, warriors = 0;
                List<Unit> go = new ArrayList<>();
                for (Unit u : left) {
                    boolean w = u.isWarrior();
                    if (w ? warriors++ < 26 - Fleet.warriorsAboard(f) : peons++ < 60 - Fleet.peonsAboard(f))
                        go.add(u);
                }
                for (Unit u : go)
                    ai.reserve(u, 40);
                ai.order(go, f, Action.MOVE, false);
                if (second - ferry_since >= 60 || left.isEmpty() && second - ferry_since >= 15) {
                    ai.fleet.goHome(f, second);
                    d.land_all = true;
                    ai.fleet.landAll(f);
                    phase = Phase.BACK;
                    ferry_since = second;
                    int aboard = Fleet.peonsAboard(f) + Fleet.warriorsAboard(f);
                    ai.log.log("FERRY", () -> "bringing " + aboard + " over t" + second);
                }
            }
            case BACK -> {
                // home again and landed: the ship is free
                boolean home = Fleet.dockedAt(f) == ai.colony.island && !f.isMoving() && !d.land_all;
                if (home || d.job == Fleet.Job.FAILED || second - ferry_since > 900) {
                    if (!home)
                        ai.fleet.goHome(f, second);
                    ferry = null;
                }
            }
        }
    }
}
