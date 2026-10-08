package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.IdleController;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * outpost: from that game second, a second colony on the best island no enemy builds on, beside the home: an armory
 * with its own gatherers, two quarters and a tower. It adds a second stream of weapons from fresh iron and wood (in
 * long games the home island runs dry), and its warriors are shipped home by Evac, a guard of ten staying.
 */
final class Outpost {
    private enum State {
        NONE,
        BOARD,
        SAIL,
        LIVE
    }

    private final @NonNull CorsairAI ai;
    private @NonNull State state = State.NONE;
    private @Nullable Ship ship;
    private int since;
    private int cooldown;
    private Geo.@Nullable Isle target;
    private int bx, by, lx, ly;
    private @Nullable Colony colony;

    Outpost(@NonNull CorsairAI ai) {
        this.ai = ai;
    }

    boolean owns(@NonNull Ship s) {
        return s == ship && (state == State.BOARD || state == State.SAIL);
    }

    /** The outpost's island, 0 without one. */
    int island() {
        Colony c = colony;
        return c == null ? 0 : c.island;
    }

    void think(@NonNull Intel in, int second) {
        if (ai.params.outpost <= 0 || !ai.colony.founded())
            return;
        switch (state) {
            case NONE -> {
                if (second >= Math.max(ai.params.outpost, cooldown) && second % 30 == 0)
                    consider(in, second);
            }
            case BOARD -> board(in, second);
            case SAIL -> sail(second);
            case LIVE -> {
                Colony c = colony;
                if (c == null)
                    return;
                // lost: no building and no peon of ours left there
                boolean alive = false;
                for (Unit u : in.peons)
                    if (u.getIslandId() == c.island) {
                        alive = true;
                        break;
                    }
                for (List<Building> l : List.of(in.quarters, in.armories))
                    for (Building b : l)
                        if (b.getIslandId() == c.island)
                            alive = true;
                if (!alive || c.island == ai.colony.island) {
                    ai.log.log("OUTPOST", () -> "outpost on isle " + c.island + " gone t" + second);
                    colony = null;
                    state = State.NONE;
                    cooldown = second + 900;
                    return;
                }
                c.think(second);
            }
        }
    }

    private void consider(@NonNull Intel in, int second) {
        Colony home = ai.colony;
        if (in.enemies.size() > in.allies.size() + 1 || home.local(in.armories).isEmpty()
                || home.local(in.quarters).size() < 3 || in.peons.size() + in.peonsInside() < 60)
            return;
        Geo geo = ai.geo;
        geo.scanSupplies();
        geo.rateIsles();
        int[] dock = ai.fleet.homeDock();
        int[] field = ai.sea.field(dock[0], dock[1]);
        Geo.Isle best = null;
        float best_score = 0;
        int[] best_spot = null;
        for (Geo.Isle isle : geo.isles) {
            if (isle.id == home.island || isle.score < 8f)
                continue;
            int[] left = geo.totalsOn(isle.id);
            if (left[0] < 150 || left[2] < 8)
                continue;
            boolean taken = false;
            for (Building b : in.enemy_buildings)
                if (b.getIslandId() == isle.id && !(b instanceof Ship)) {
                    taken = true;
                    break;
                }
            if (taken)
                continue;
            for (int[] cand : geo.candidates(isle, .6f, 20)) {
                int[] w = geo.windowOn(cand[0] / Geo.BIN, cand[1] / Geo.BIN, 3, isle.id);
                float quality = 3f * Math.min(w[2], 8) + .4f * Math.min(w[0], 60) + .3f * Math.min(w[1], 6);
                float[] r = ai.fleet.reach(field, isle, cand[0], cand[1]);
                if (r[0] == Float.MAX_VALUE)
                    continue;
                float score = quality - r[0] / 20f;
                // away from the enemy's buildings
                for (Building b : in.enemy_buildings) {
                    float d = (float) Math.sqrt((double) Geo.dist2(b.getGridX(), b.getGridY(), cand[0], cand[1]));
                    score -= 25f * Math.max(0f, 1f - d / 300f) / Math.max(1, in.enemy_buildings.size() / 6);
                }
                if (best == null || score > best_score) {
                    best = isle;
                    best_score = score;
                    best_spot = new int[]{cand[0], cand[1], (int) r[1], (int) r[2]};
                }
            }
        }
        if (best == null || best_spot == null || best_score < 15f) {
            cooldown = second + 600;
            return;
        }
        List<Ship> ships = ai.fleet.idleAtHome();
        if (ships.size() < 2)
            return;
        // the ship nearest the base: the docks lie up to 100 cells apart, and peons walk ~2 cells a second
        Ship s = ships.getFirst();
        for (Ship o : ships)
            if (Geo.dist2(o.getGridX(), o.getGridY(), home.ax, home.ay) < Geo.dist2(s.getGridX(), s.getGridY(),
                    home.ax, home.ay))
                s = o;
        target = best;
        bx = best_spot[0];
        by = best_spot[1];
        lx = best_spot[2];
        ly = best_spot[3];
        ship = s;
        ai.fleet.cancelDeploys(s);
        ai.ratchet.haltInbound(s);
        List<Unit> go = new ArrayList<>();
        for (Unit u : in.peons)
            if (go.size() < 30 && u.getIslandId() == home.island && !ai.reserved(u)
                    && (u.getPrimaryController() instanceof IdleController
                            || u.getPrimaryController() instanceof GatherController<?>))
                go.add(u);
        if (go.size() < 30)
            home.releasePeons(in, 30 - go.size());
        List<Unit> free = ai.army.freeWarriorsAtHome(in);
        go.addAll(free.subList(0, Math.min(12, free.size())));
        for (Unit u : go)
            ai.reserve(u, 120);
        ai.order(go, s, Action.MOVE, false);
        state = State.BOARD;
        since = second;
        Geo.Isle t = best;
        float sc = best_score;
        ai.log.log("OUTPOST", () -> "settling " + t + " at " + bx + "," + by + " (score " + sc + ") t" + second);
        ai.log.count("outpost");
    }

    private void board(@NonNull Intel in, int second) {
        Ship s = ship;
        Geo.Isle t = target;
        if (s == null || s.isDead() || t == null) {
            reset(second);
            return;
        }
        if (second - since < 100 && Fleet.peonsAboard(s) < 28) {
            List<Unit> more = new ArrayList<>();
            for (Unit u : in.peons)
                if (u.getIslandId() == ai.colony.island && !ai.reserved(u)
                        && u.getPrimaryController() instanceof IdleController && more.size() < 10)
                    more.add(u);
            if (Fleet.peonsAboard(s) < 30 && !more.isEmpty()) {
                for (Unit u : more)
                    ai.reserve(u, 90);
                ai.order(more, s, Action.MOVE, false);
            }
        }
        if (second - since < 100 && Fleet.peonsAboard(s) < 28)
            return;
        if (Fleet.peonsAboard(s) < 12) {
            int n = Fleet.peonsAboard(s);
            ai.log.log("OUTPOST", () -> "only " + n + " peons aboard: staying t" + second);
            ai.fleet.landAll(s);
            reset(second);
            return;
        }
        ai.fleet.goTo(s, t.id, lx, ly, second);
        ai.fleet.duty(s).land_all = true;
        ai.fleet.landAll(s);
        state = State.SAIL;
        since = second;
    }

    private void sail(int second) {
        Ship s = ship;
        Geo.Isle t = target;
        if (s == null || s.isDead() || t == null) {
            reset(second);
            return;
        }
        Fleet.Duty d = ai.fleet.duty(s);
        if (second % 10 == 0)
            ai.log.log("OUTPOST", () -> "ship " + d.job + " docked " + Fleet.dockedAt(
                    s) + " land_all " + d.land_all + " aboard " + Fleet.peonsAboard(s) + "+" + Fleet.warriorsAboard(
                            s) + " t" + second);
        if (d.job == Fleet.Job.FAILED || second - since > 900) {
            ai.fleet.goHome(s, second);
            reset(second);
            return;
        }
        if ((d.job == Fleet.Job.ARRIVED || d.job == Fleet.Job.IDLE) && Fleet.dockedAt(s) == t.id && !d.land_all) {
            Colony c = new Colony(ai);
            c.found(t.id, bx, by);
            c.dock_x = lx;
            c.dock_y = ly;
            c.outpost = true;
            colony = c;
            ai.fleet.goHome(s, second);
            ship = null;
            state = State.LIVE;
            ai.log.log("OUTPOST", () -> "founded on isle " + t.id + " t" + second);
        }
    }

    private void reset(int second) {
        ship = null;
        state = State.NONE;
        cooldown = second + 600;
    }
}
