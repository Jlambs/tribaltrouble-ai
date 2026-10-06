package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.ShipHR;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Our ships and what each is doing: the opening voyage to the home island, ferrying an expedition to an enemy island
 * and back, and waiting at home.
 */
final class Fleet {
    enum Job {
        HOME,
        LAND,
        BOARD,
        SAIL,
        UNLOAD,
        RETURN
    }

    static final class Duty {
        @NonNull
        Job job = Job.HOME;
        /** Where the ship is headed: a cell on the target island (the shore planner picks the landing). */
        int tx = -1;
        int ty = -1;
        int island;
        int since;
        int attempts;
        /** Units ordered aboard for the current job. */
        final List<@NonNull Unit> boarding = new ArrayList<>();
        /** Expedition id this ship carries, -1 without. */
        int expedition = -1;
    }

    private final @NonNull CorsairAI ai;
    private final @NonNull Player me;
    final Map<@NonNull Ship, @NonNull Duty> duties = new LinkedHashMap<>();

    Fleet(@NonNull CorsairAI ai) {
        this.ai = ai;
        this.me = ai.me();
    }

    @NonNull
    Duty duty(@NonNull Ship ship) {
        return duties.computeIfAbsent(ship, s -> new Duty());
    }

    /** The island a ship lies docked at, or 0 at sea. */
    static int dockedAt(@NonNull Ship ship) {
        Building e = ship.getEntrance();
        return e == ship ? 0 : e.getIslandId();
    }

    // --- the opening -----------------------------------------------------------------------------------------------

    /** Picks the home island and sends every starting ship there with everyone aboard ready to land. */
    void openingVoyage(@NonNull Intel in, int second) {
        Geo geo = ai.geo;
        geo.scanSupplies();
        geo.rateIsles();
        int sx = Geo.gridOf(me.getStartX()), sy = Geo.gridOf(me.getStartY());
        Geo.Isle best = null;
        float best_score = -1e9f;
        for (Geo.Isle isle : geo.isles) {
            if (isle.score < 8f || isle.ax < 0)
                continue;
            int[] shore = isle.nearestShore(isle.ax, isle.ay);
            if (shore == null)
                continue;
            int travel = Geo.dist(sx, sy, shore[0], shore[1]) + Geo.dist(shore[0], shore[1], isle.ax, isle.ay) / 2;
            int mine = Geo.dist(sx, sy, isle.ax, isle.ay);
            float contest = 0;
            for (Player e : in.enemies) {
                int d = Geo.dist(Geo.gridOf(e.getStartX()), Geo.gridOf(e.getStartY()), isle.ax, isle.ay);
                if (d < mine * 1.25f)
                    contest += d < mine ? 1f : .5f;
            }
            float allied = 0;
            for (Player a : in.allies) {
                int d = Geo.dist(Geo.gridOf(a.getStartX()), Geo.gridOf(a.getStartY()), isle.ax, isle.ay);
                if (d < mine)
                    allied += .5f;
            }
            float s = isle.score - (float) ai.params.travel_weight * travel / 20f - (float) ai.params.contest_weight * 8f * contest - 2f * allied;
            float contested = contest;
            ai.log.log("OPEN",
                    () -> "candidate " + isle + " base " + isle.ax + "," + isle.ay + " quality " + isle.score + " travel " + travel + " contest " + contested + " -> " + s);
            if (s > best_score) {
                best_score = s;
                best = isle;
            }
        }
        if (best == null) {
            // no island with all three: the largest island
            for (Geo.Isle isle : geo.isles)
                if (best == null || isle.area > best.area)
                    best = isle;
            if (best == null)
                return;
            best.ax = best.cx;
            best.ay = best.cy;
        }
        Geo.Isle home = best;
        ai.colony.found(home.id, home.ax, home.ay);
        ai.log.log("OPEN", () -> "home " + home + " base " + home.ax + "," + home.ay);
        for (Ship ship : in.ships) {
            Duty d = duty(ship);
            d.job = Job.LAND;
            d.island = home.id;
            d.tx = home.ax;
            d.ty = home.ay;
            d.since = second;
            sail(ship, d.tx, d.ty);
            landAll(ship);
        }
    }

    private void sail(@NonNull Ship ship, int x, int y) {
        me.setSailingTarget(Selectable.newArray(ship), x, y);
    }

    /** Queues everyone aboard to land (they leave once the ship docks). */
    private void landAll(@NonNull Ship ship) {
        ShipHR crew = ship.getShipHR();
        int peons = crew.countPeons() - (crew.hasChieftain() ? 1 : 0) - pending(ship, DeployType.PEON);
        if (peons > 0)
            me.deployUnits(ship, DeployType.PEON, peons);
        landWarriors(ship);
        if (crew.hasChieftain())
            me.trainChieftain(ship, true);
    }

    private void landWarriors(@NonNull Ship ship) {
        ShipHR crew = ship.getShipHR();
        int rock = crew.countUnitsOfType(RockAxeWeapon.class) - pending(ship, DeployType.ROCK_WARRIOR);
        int iron = crew.countUnitsOfType(IronAxeWeapon.class) - pending(ship, DeployType.IRON_WARRIOR);
        int rubber = crew.countUnitsOfType(RubberAxeWeapon.class) - pending(ship, DeployType.RUBBER_WARRIOR);
        if (rock > 0)
            me.deployUnits(ship, DeployType.ROCK_WARRIOR, rock);
        if (iron > 0)
            me.deployUnits(ship, DeployType.IRON_WARRIOR, iron);
        if (rubber > 0)
            me.deployUnits(ship, DeployType.RUBBER_WARRIOR, rubber);
    }

    static int pending(@NonNull Ship ship, @NonNull DeployType type) {
        var c = ship.getDeployContainer(type);
        return c == null ? 0 : c.getNumSupplies();
    }

    static int warriorsAboard(@NonNull Ship ship) {
        ShipHR crew = ship.getShipHR();
        return crew.countUnitsOfType(RockAxeWeapon.class) + crew.countUnitsOfType(
                IronAxeWeapon.class) + crew.countUnitsOfType(RubberAxeWeapon.class);
    }

    // --- every second ----------------------------------------------------------------------------------------------

    void think(@NonNull Intel in, int second) {
        duties.keySet().removeIf(Ship::isDead);
        for (Ship ship : in.ships) {
            Duty d = duty(ship);
            switch (d.job) {
                case LAND -> land(ship, d, second);
                case BOARD -> board(ship, d, second);
                case SAIL -> sailOn(ship, d, second);
                case UNLOAD -> unload(ship, d, second);
                case RETURN -> returnHome(ship, d, second);
                case HOME -> home(ship, d, second);
            }
        }
        buildShips(in, second);
    }

    private void land(@NonNull Ship ship, @NonNull Duty d, int second) {
        if (ship.isMoving())
            return;
        int at = dockedAt(ship);
        if (at == d.island) {
            if (ship.getShipHR().countPeons() - (ship.getShipHR().hasChieftain() ? 1 : 0) > 0
                    || warriorsAboard(ship) > 0) {
                landAll(ship);
                return;
            }
            ai.log.log("FLEET",
                    () -> "landed at isle " + d.island + " " + ship.getGridX() + "," + ship.getGridY() + " t" + second);
            d.job = Job.HOME;
            return;
        }
        // stopped at sea or at the wrong island: try another landing
        if (second - d.since > 5) {
            d.attempts++;
            d.since = second;
            int[] cell = landingCell(d.island, d.tx, d.ty, d.attempts);
            ai.log.log("FLEET",
                    () -> "re-land attempt " + d.attempts + " at " + (cell == null ? "-" : cell[0] + "," + cell[1]));
            if (cell != null)
                sail(ship, cell[0], cell[1]);
        }
    }

    /** A shore cell of the island near (x, y): the nearest for attempt 0, then further ones round the coast. */
    int @Nullable [] landingCell(int island, int x, int y, int attempt) {
        Geo.Isle isle = ai.geo.isle(island);
        if (isle == null || isle.shore.isEmpty())
            return null;
        List<int[]> cells = new ArrayList<>(isle.shore);
        cells.sort((a, b) -> {
            int c = Long.compare(Geo.dist2(a[0], a[1], x, y), Geo.dist2(b[0], b[1], x, y));
            if (c != 0)
                return c;
            c = Integer.compare(a[0], b[0]);
            return c != 0 ? c : Integer.compare(a[1], b[1]);
        });
        int i = Math.min(cells.size() - 1, attempt * 7);
        return cells.get(i);
    }

    private void home(@NonNull Ship ship, @NonNull Duty d, int second) {
        // nothing: Army asks for ferries
    }

    // --- expeditions -----------------------------------------------------------------------------------------------

    /** A ship docked at home and free to ferry, or null. */
    @Nullable
    Ship freeFerry(int home) {
        for (Map.Entry<Ship, Duty> e : duties.entrySet()) {
            Ship s = e.getKey();
            if (!s.isDead() && s.isComplete() && e.getValue().job == Job.HOME && !s.isMoving() && dockedAt(s) == home)
                return s;
        }
        return null;
    }

    /** Starts boarding an expedition: the warriors and rower peons walk aboard. */
    void startFerry(@NonNull Ship ship, @NonNull List<@NonNull Unit> warriors, @NonNull List<@NonNull Unit> rowers,
            int island, int tx, int ty, int expedition, int second) {
        Duty d = duty(ship);
        d.job = Job.BOARD;
        d.island = island;
        d.tx = tx;
        d.ty = ty;
        d.since = second;
        d.attempts = 0;
        d.expedition = expedition;
        d.boarding.clear();
        d.boarding.addAll(warriors);
        d.boarding.addAll(rowers);
        List<Unit> all = new ArrayList<>(d.boarding);
        for (Unit u : all)
            ai.reserve(u);
        me.setTarget(all.toArray(new Unit[0]), ship, Action.MOVE, false);
        ai.log.log("FLEET",
                () -> "boarding " + warriors.size() + " warriors + " + rowers.size() + " rowers for isle " + island + " t" + second);
    }

    private void board(@NonNull Ship ship, @NonNull Duty d, int second) {
        d.boarding.removeIf(Unit::isDead);
        // units aboard have left our unit set; the ones still outside are the ones walking
        int outside = 0;
        List<Unit> lagging = new ArrayList<>();
        for (Unit u : d.boarding) {
            if (inSet(u)) {
                outside++;
                if (u.getPrimaryController() instanceof IdleController)
                    lagging.add(u);
            }
        }
        if (!lagging.isEmpty())
            me.setTarget(lagging.toArray(new Unit[0]), ship, Action.MOVE, false);
        boolean timeout = second - d.since > 45;
        if (outside == 0 || timeout) {
            for (Unit u : d.boarding)
                ai.release(u);
            d.boarding.clear();
            if (warriorsAboard(ship) == 0) {
                d.job = Job.HOME;
                ai.log.log("FLEET", () -> "boarding failed: no warriors aboard");
                return;
            }
            d.job = Job.SAIL;
            d.since = second;
            int[] cell = landingCell(d.island, d.tx, d.ty, 0);
            if (cell != null)
                sail(ship, d.tx, d.ty);
            ai.log.log("FLEET", () -> "sailing with " + warriorsAboard(
                    ship) + " warriors, " + ship.getShipHR().countRowers() + " rowers to isle " + d.island + " t" + second);
        }
    }

    private boolean inSet(@NonNull Unit u) {
        return !u.isDead() && !u.isMounted() && me.getUnits().getSet().contains(u);
    }

    private void sailOn(@NonNull Ship ship, @NonNull Duty d, int second) {
        if (ship.isMoving())
            return;
        int at = dockedAt(ship);
        if (at == d.island) {
            d.job = Job.UNLOAD;
            d.since = second;
            landWarriors(ship);
            ai.log.log("FLEET",
                    () -> "unloading at isle " + d.island + " " + ship.getGridX() + "," + ship.getGridY() + " t" + second);
            return;
        }
        if (second - d.since > 5) {
            d.attempts++;
            d.since = second;
            if (d.attempts > 6) {
                d.job = Job.RETURN;
                return;
            }
            int[] cell = landingCell(d.island, d.tx, d.ty, d.attempts);
            if (cell != null)
                sail(ship, cell[0], cell[1]);
        }
    }

    private void unload(@NonNull Ship ship, @NonNull Duty d, int second) {
        if (warriorsAboard(ship) > 0) {
            if (second - d.since > 30) {
                d.job = Job.RETURN;
                return;
            }
            landWarriors(ship);
            return;
        }
        d.job = Job.RETURN;
        d.since = second;
        ai.log.log("FLEET", () -> "unloaded, returning t" + second);
    }

    private void returnHome(@NonNull Ship ship, @NonNull Duty d, int second) {
        int home = ai.colony.island;
        if (!ship.isMoving() && dockedAt(ship) == home) {
            d.job = Job.HOME;
            d.expedition = -1;
            // rowers stay aboard for the next trip
            return;
        }
        if (!ship.isMoving() && second - d.since > 3) {
            d.since = second;
            d.attempts++;
            int[] cell = landingCell(home, ai.colony.ax, ai.colony.ay, d.attempts % 4);
            if (cell != null)
                sail(ship, cell[0], cell[1]);
        }
    }

    // --- building ships --------------------------------------------------------------------------------------------

    private void buildShips(@NonNull Intel in, int second) {
        Colony c = ai.colony;
        if (!c.founded() || second < ai.params.ship_start || in.armories.isEmpty())
            return;
        int have = in.ships.size() + in.ship_sites.size() + c.count(Race.BUILDING_SHIP);
        if (have >= ai.params.max_ships || c.count(Race.BUILDING_SHIP) > 0 || c.unplaced() > 0)
            return;
        if (in.peons.size() + in.peonsInside() < 50)
            return;
        // a dock on the shore nearest the base
        Geo.Isle isle = ai.geo.isle(c.island);
        int[] shore = isle == null ? null : isle.nearestShore(c.ax, c.ay);
        if (shore == null)
            return;
        c.projects.add(new Colony.Project(Race.BUILDING_SHIP, shore[0], shore[1], second));
        ai.log.log("BUILD", () -> "plan ship near " + shore[0] + "," + shore[1]);
    }
}
