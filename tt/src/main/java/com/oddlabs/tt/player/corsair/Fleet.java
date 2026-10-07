package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.ShipHR;
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
 * Our ships: where each is going and whether it got there. Operations ({@link Campaign}) and the opening give ships
 * destinations; this class sails them, retries landings when a ship stalls or docks at the wrong shore, and hunts
 * enemy ships with ships that carry deck fighters.
 */
final class Fleet {
    enum Job {
        /** Docked or waiting, free for any use. */
        IDLE,
        /** Sailing to (island, tx, ty). */
        TRANSIT,
        /** Docked and still at the destination island. */
        ARRIVED,
        /** Gave up reaching the destination. */
        FAILED,
        /** Chasing an enemy ship. */
        HUNT
    }

    static final class Duty {
        @NonNull
        Job job = Job.IDLE;
        int island;
        int tx = -1;
        int ty = -1;
        int since;
        int attempts;
        int last_x = -1;
        int last_y = -1;
        int moved;
        int docked_since = -1;
        /** The operation that owns the ship, or null. */
        Campaign.@Nullable Op op;
        @Nullable
        Ship prey;
        /** Land everyone aboard on arrival (the opening). */
        boolean land_all;
    }

    private final @NonNull CorsairAI ai;
    private final @NonNull Player me;
    final Map<@NonNull Ship, @NonNull Duty> duties = new LinkedHashMap<>();
    /** Sea distances from our home landing, for choosing beachheads. */
    int @Nullable [] home_field;

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

    static int warriorsAboard(@NonNull Ship ship) {
        ShipHR crew = ship.getShipHR();
        return crew.countUnitsOfType(RockAxeWeapon.class) + crew.countUnitsOfType(
                IronAxeWeapon.class) + crew.countUnitsOfType(RubberAxeWeapon.class);
    }

    static int peonsAboard(@NonNull Ship ship) {
        ShipHR crew = ship.getShipHR();
        return crew.countPeons() - (crew.hasChieftain() ? 1 : 0);
    }

    static int pending(@NonNull Ship ship, @NonNull DeployType type) {
        var c = ship.getDeployContainer(type);
        return c == null ? 0 : c.getNumSupplies();
    }

    // --- orders ----------------------------------------------------------------------------------------------------

    void sail(@NonNull Ship ship, int x, int y) {
        me.setSailingTarget(Selectable.newArray(ship), x, y);
    }

    /** Sends a ship to a cell on an island (0 for a point at sea). */
    void goTo(@NonNull Ship ship, int island, int x, int y, int second) {
        Duty d = duty(ship);
        d.job = Job.TRANSIT;
        d.island = island;
        d.tx = x;
        d.ty = y;
        d.since = second;
        d.attempts = 0;
        d.moved = second;
        d.docked_since = -1;
        d.prey = null;
        sail(ship, x, y);
    }

    void goHome(@NonNull Ship ship, int second) {
        Colony c = ai.colony;
        int[] cell = homeDock();
        if (ai.params.docks > 1) {
            // the nearest spaced dock no other ship of ours holds or heads for
            long gap2 = (long) ai.params.dock_gap * ai.params.dock_gap;
            for (int[] dock : spacedShores(c.island, cell[0], cell[1])) {
                boolean free = true;
                for (Map.Entry<Ship, Duty> e : duties.entrySet()) {
                    Ship o = e.getKey();
                    Duty od = e.getValue();
                    if (o == ship || o.isDead())
                        continue;
                    boolean near = Geo.dist2(o.getGridX(), o.getGridY(), dock[0], dock[1]) < gap2;
                    boolean bound = od.job == Job.TRANSIT && od.island == c.island
                            && Geo.dist2(od.tx, od.ty, dock[0], dock[1]) < gap2;
                    if (near || bound) {
                        free = false;
                        break;
                    }
                }
                if (free) {
                    cell = dock;
                    break;
                }
            }
        }
        goTo(ship, c.island, cell[0], cell[1], second);
    }

    /**
     * docks: the k-th of up to `docks` shore cells of an island near (x, y), at least dock_gap cells apart, nearest
     * first. Ships docked side by side lock each other in: a ship leaves a dock sliding back to deep water,
     * uninterruptibly, and stops for good while another ship is behind it (ShipSlideBehaviour); 3 of 5 ships once
     * sat so at our dock for minutes.
     */
    int @NonNull [] spacedShore(int island, int x, int y, int k) {
        List<int[]> picked = spacedShores(island, x, y);
        if (picked.isEmpty())
            return new int[]{x, y};
        int[] c = picked.get(k % picked.size());
        return new int[]{c[0], c[1]};
    }

    /** docks: up to `docks` shore cells of an island within 100 cells of (x, y), dock_gap apart, nearest first. */
    @NonNull
    List<int @NonNull []> spacedShores(int island, int x, int y) {
        Geo.Isle isle = ai.geo.isle(island);
        List<int[]> picked = new ArrayList<>();
        if (ai.params.docks <= 1 || isle == null || isle.shore.isEmpty())
            return picked;
        List<int[]> cells = new ArrayList<>(isle.shore);
        cells.sort((a, b) -> {
            int c = Long.compare(Geo.dist2(a[0], a[1], x, y), Geo.dist2(b[0], b[1], x, y));
            if (c != 0)
                return c;
            c = Integer.compare(a[0], b[0]);
            return c != 0 ? c : Integer.compare(a[1], b[1]);
        });
        long gap2 = (long) ai.params.dock_gap * ai.params.dock_gap;
        for (int[] c : cells) {
            if (Geo.dist2(c[0], c[1], x, y) > 100 * 100)
                break;
            boolean ok = true;
            for (int[] p : picked)
                if (Geo.dist2(p[0], p[1], c[0], c[1]) < gap2) {
                    ok = false;
                    break;
                }
            if (ok) {
                picked.add(c);
                if (picked.size() >= ai.params.docks)
                    break;
            }
        }
        return picked;
    }

    /** The jobs of our ships, for the log ("IDLE@2 TRANSIT@15 ..."). */
    @NonNull
    String jobs() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Ship, Duty> e : duties.entrySet()) {
            Ship s = e.getKey();
            Duty d = e.getValue();
            sb.append(d.job).append(d.op != null ? "+op" : "").append(ai.raid.owns(s) ? "+raid" : "").append(
                    '@').append(dockedAt(s)).append(s.isMoving() ? "~" : "").append(' ');
        }
        return sb.toString().trim();
    }

    /** The shore cell our ships use at home. */
    int @NonNull [] homeDock() {
        Colony c = ai.colony;
        if (c.dock_x >= 0)
            return new int[]{c.dock_x, c.dock_y};
        Geo.Isle isle = ai.geo.isle(c.island);
        int[] cell = isle == null ? null : isle.nearestShore(c.ax, c.ay);
        return cell != null ? cell : new int[]{c.ax, c.ay};
    }

    /** Queues everyone aboard to land (they leave once the ship docks); the raid ship keeps its raiders. */
    void landAll(@NonNull Ship ship) {
        int keep = ai.raid.owns(ship) ? ai.params.raid : 0;
        int peons = peonsAboard(ship) - pending(ship, DeployType.PEON) - keep;
        if (peons > 0)
            me.deployUnits(ship, DeployType.PEON, peons);
        landWarriors(ship);
        if (ship.getShipHR().hasChieftain())
            me.trainChieftain(ship, true);
    }

    void landWarriors(@NonNull Ship ship) {
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

    /** Cancels every unit queued to leave the ship (the drill queues its whole deck to leave). */
    void cancelDeploys(@NonNull Ship ship) {
        for (DeployType t : new DeployType[]{DeployType.ROCK_WARRIOR, DeployType.IRON_WARRIOR, DeployType.RUBBER_WARRIOR, DeployType.PEON}) {
            int n = pending(ship, t);
            if (n > 0)
                me.deployUnits(ship, t, -n);
        }
    }

    /** Lands n peons (rowers to spare). */
    void landPeons(@NonNull Ship ship, int n) {
        int can = Math.min(n, peonsAboard(ship) - pending(ship, DeployType.PEON));
        if (can > 0)
            me.deployUnits(ship, DeployType.PEON, can);
    }

    // --- the opening -----------------------------------------------------------------------------------------------

    /** Walking a cell costs as much as sailing this many (peons walk ~2 cells/s, full ships sail ~4.7). */
    static final float WALK = 2.5f;

    /** A possible base: its anchor, the shore cell to land at, and what it costs us and the others to get there. */
    record Spot(Geo.Isle isle, int ax, int ay, int lx, int ly, float quality, float cost, float contest, float score) {
    }

    /** The cheapest landing for reaching (ax, ay) from a sea field: {cost, lx, ly}. */
    float @NonNull [] reach(int @NonNull [] field, Geo.@NonNull Isle isle, int ax, int ay) {
        float best = Float.MAX_VALUE;
        int lx = -1, ly = -1;
        SeaMap sea = ai.sea;
        for (int[] c : isle.shore) {
            float walk = (float) Math.sqrt((double) Geo.dist2(c[0], c[1], ax, ay));
            if (walk > 90)
                continue;
            int s = sea.to(field, c[0], c[1]);
            if (s >= SeaMap.FAR)
                continue;
            float cost = s + WALK * walk;
            if (cost < best) {
                best = cost;
                lx = c[0];
                ly = c[1];
            }
        }
        return new float[]{best, lx, ly};
    }

    /** Picks where to found the base and sends every starting ship there with everyone aboard ready to land. */
    void openingVoyage(@NonNull Intel in, int second) {
        Geo geo = ai.geo;
        geo.scanSupplies();
        geo.rateIsles();
        int sx = Geo.gridOf(me.getStartX()), sy = Geo.gridOf(me.getStartY());
        int[] mine = ai.sea.field(sx, sy);
        List<int[]> enemy_fields = new ArrayList<>();
        for (Player e : in.enemies)
            enemy_fields.add(ai.sea.field(Geo.gridOf(e.getStartX()), Geo.gridOf(e.getStartY())));
        List<int[]> ally_fields = new ArrayList<>();
        for (Player a : in.allies)
            ally_fields.add(ai.sea.field(Geo.gridOf(a.getStartX()), Geo.gridOf(a.getStartY())));
        Spot best = null;
        // spread_open: where each enemy would found its base by our own measure (quality less its travel); we
        // keep away from those spots on a shared island (bases side by side fight from the first minute: a 2v2
        // where two of four picked the same cell lost 220 peons by minute 16)
        List<int[]> enemy_spots = new ArrayList<>();
        if (ai.params.spread_open > 0)
            for (int[] f : enemy_fields) {
                float bs = -Float.MAX_VALUE;
                int[] spot = null;
                for (Geo.Isle isle : geo.isles) {
                    if (isle.score < 8f)
                        continue;
                    for (int[] cand : geo.candidates(isle, .6f, 40)) {
                        float q = geo.baseScore(cand[0] / Geo.BIN, cand[1] / Geo.BIN, isle.id);
                        float e = reach(f, isle, cand[0], cand[1])[0];
                        if (e == Float.MAX_VALUE)
                            continue;
                        float sc = q - e / 20f;
                        if (sc > bs) {
                            bs = sc;
                            spot = new int[]{cand[0], cand[1], isle.id};
                        }
                    }
                }
                if (spot != null)
                    enemy_spots.add(spot);
            }
        for (Geo.Isle isle : geo.isles) {
            if (isle.score < 8f)
                continue;
            for (int[] cand : geo.candidates(isle, .6f, 40)) {
                int ax = cand[0], ay = cand[1];
                float quality = geo.baseScore(ax / Geo.BIN, ay / Geo.BIN, isle.id);
                float[] r = reach(mine, isle, ax, ay);
                if (r[0] == Float.MAX_VALUE)
                    continue;
                float contest = 0;
                for (int[] f : enemy_fields) {
                    float e = reach(f, isle, ax, ay)[0];
                    if (e < r[0] * 1.25f)
                        contest += e < r[0] ? 1f : .5f;
                }
                float allied = 0;
                for (int[] f : ally_fields) {
                    float a = reach(f, isle, ax, ay)[0];
                    if (a < r[0])
                        allied += .5f;
                }
                float score = quality - (float) ai.params.travel_weight * r[0] / 20f - (float) ai.params.contest_weight * 8f * contest - 2f * allied;
                for (int[] es : enemy_spots)
                    if (es[2] == isle.id) {
                        float d = (float) Math.sqrt((double) Geo.dist2(es[0], es[1], ax, ay));
                        score -= (float) ai.params.spread_open * Math.max(0f, 1f - d / 150f);
                    }
                if (best == null || score > best.score())
                    best = new Spot(isle, ax, ay, (int) r[1], (int) r[2], quality, r[0], contest, score);
            }
        }
        if (best == null) {
            Geo.Isle big = null;
            for (Geo.Isle isle : geo.isles)
                if (big == null || isle.area > big.area)
                    big = isle;
            if (big == null)
                return;
            int[] shore = big.nearestShore(big.cx, big.cy);
            best = new Spot(big, big.cx, big.cy, shore == null ? big.cx : shore[0], shore == null ? big.cy : shore[1],
                    0, 0, 0, 0);
        }
        Spot home = best;
        ai.colony.found(home.isle().id, home.ax(), home.ay());
        ai.colony.dock_x = home.lx();
        ai.colony.dock_y = home.ly();
        home_field = ai.sea.field(home.lx(), home.ly());
        ai.log.log("OPEN",
                () -> "home " + home.isle() + " base " + home.ax() + "," + home.ay() + " landing " + home.lx() + "," + home.ly() + " quality " + home.quality() + " cost " + home.cost() + " contest " + home.contest() + " score " + home.score());
        boolean raider = ai.params.raid > 0;
        for (Ship ship : in.ships) {
            if (raider) {
                ai.raid.start(ship);
                raider = false;
            }
            goTo(ship, home.isle().id, home.lx(), home.ly(), second);
            duty(ship).land_all = true;
            landAll(ship);
        }
    }

    // --- every second ----------------------------------------------------------------------------------------------

    void think(@NonNull Intel in, int second) {
        duties.keySet().removeIf(Ship::isDead);
        for (Ship ship : in.ships) {
            Duty d = duty(ship);
            switch (d.job) {
                case TRANSIT -> transit(ship, d, second);
                case HUNT -> hunt(ship, d, in, second);
                case IDLE, ARRIVED, FAILED -> {
                    // rowers of a ship idle at home go back to work; operations take new ones aboard
                    if (d.op == null && !ship.isMoving() && dockedAt(ship) == ai.colony.island && !d.land_all
                            && peonsAboard(ship) > 0 && pending(ship, DeployType.PEON) == 0 && !ai.raid.owns(ship)
                            && !ai.expand.owns(ship) && !ai.evac.owns(ship))
                        landPeons(ship, peonsAboard(ship));
                    if (d.op == null && !ship.isMoving() && dockedAt(ship) != 0 && ship.getShipHR().hasChieftain())
                        me.trainChieftain(ship, true);
                    if (d.job == Job.ARRIVED && d.land_all) {
                        int keep = ai.raid.owns(ship) ? ai.params.raid : 0;
                        if (peonsAboard(ship) + warriorsAboard(ship) > keep)
                            landAll(ship);
                        else {
                            d.land_all = false;
                            d.job = Job.IDLE;
                        }
                    }
                }
                default -> {
                }
            }
        }
        buildShips(in, second);
    }

    private void transit(@NonNull Ship ship, @NonNull Duty d, int second) {
        boolean stalled = stalled(ship, d, second);
        if (second % 10 == 0)
            ai.log.log("SAIL",
                    () -> "ship at " + ship.getGridX() + "," + ship.getGridY() + " to isle " + d.island + " " + d.tx + "," + d.ty + " moving " + ship.isMoving() + " docked " + dockedAt(
                            ship) + " " + behaviourName(
                                    ship) + " attempts " + d.attempts + " rowers " + ship.getShipHR().countRowers());
        int at = dockedAt(ship);
        // dock_near: a ship that touches the target island far from its cell (a coast passed on the way: home ships
        // stopped 170 cells from the base and the warriors could not reach them in time) sails on; it stops there
        // only after 45 s along that shore
        boolean far = ai.params.dock_near > 0 && d.island != 0
                && Geo.dist2(ship.getGridX(), ship.getGridY(), d.tx,
                        d.ty) > (long) ai.params.dock_near * ai.params.dock_near;
        // docked at the target island but still moving (wiggling under ShipUnstuckBehaviour, or nudging along the
        // shore): stop there after 3 s, we can land from here
        if (ship.isMoving() && d.island != 0 && at == d.island) {
            if (d.docked_since < 0)
                d.docked_since = second;
            else if (second - d.docked_since >= (far ? 45 : 3)) {
                sail(ship, ship.getGridX(), ship.getGridY());
                ai.log.count("dock_stop");
                return;
            }
        } else if (at != d.island) {
            d.docked_since = -1;
        }
        if (ship.isMoving() && !stalled)
            return;
        if (d.island == 0 ? !ship.isMoving() : at == d.island && (!far || !ship.isMoving() && d.attempts >= 3)) {
            if (ship.isMoving()) {
                // held at the shore (an enemy ship in the way stops us): stop here, we are docked
                sail(ship, ship.getGridX(), ship.getGridY());
                return;
            }
            d.job = Job.ARRIVED;
            d.since = second;
            if (d.land_all)
                landAll(ship);
            ai.log.log("FLEET",
                    () -> "ship at " + ship.getGridX() + "," + ship.getGridY() + " arrived at isle " + d.island + " t" + second);
            return;
        }
        if (second - d.since > 4 || stalled) {
            d.attempts++;
            d.since = second;
            d.moved = second;
            if (d.attempts > 10) {
                d.job = Job.FAILED;
                ai.log.log("FLEET",
                        () -> "ship at " + ship.getGridX() + "," + ship.getGridY() + " gave up reaching isle " + d.island);
                return;
            }
            int[] cell = d.island == 0 ? new int[]{d.tx, d.ty} : landingCell(d.island, d.tx, d.ty, d.attempts);
            if (cell != null)
                sail(ship, cell[0], cell[1]);
        }
    }

    /** Whether a sailing ship has not moved for 4 s (blocked by another ship, or wedged). */
    private boolean stalled(@NonNull Ship ship, @NonNull Duty d, int second) {
        if (ship.getGridX() != d.last_x || ship.getGridY() != d.last_y) {
            d.last_x = ship.getGridX();
            d.last_y = ship.getGridY();
            d.moved = second;
            return false;
        }
        return ship.isMoving() && second - d.moved >= 4;
    }

    static String behaviourName(@NonNull Ship ship) {
        var b = ship.getCurrentBehaviour();
        if (b instanceof com.oddlabs.tt.model.behaviour.SailBehaviour sail)
            return "sail" + (sail.isStuck() ? " stuck" : "") + (sail.isBlocking() ? " blocked" : "") + (sail.getTrajectory() == null ? " notraj" : sail.getTrajectory().exists() ? "" : " nopath");
        return b == null ? "none" : b.getClass().getSimpleName();
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
        int i = Math.min(cells.size() - 1, attempt * 6);
        return cells.get(i);
    }

    // --- hunting ---------------------------------------------------------------------------------------------------

    void startHunt(@NonNull Ship ship, @NonNull Ship prey, int second) {
        Duty d = duty(ship);
        d.job = Job.HUNT;
        d.prey = prey;
        d.since = second;
        d.moved = second;
        me.setSailingTarget(Selectable.newArray(ship), prey);
        ai.log.log("FLEET",
                () -> "hunting the ship at " + prey.getGridX() + "," + prey.getGridY() + " with " + warriorsAboard(
                        ship) + " deck fighters");
    }

    private void hunt(@NonNull Ship ship, @NonNull Duty d, @NonNull Intel in, int second) {
        Ship prey = d.prey;

        if (prey == null || prey.isDead() || warriorsAboard(ship) == 0) {
            d.job = Job.IDLE;
            d.prey = null;
            return;
        }
        if (second % 10 == 0)
            ai.log.log("HUNT",
                    () -> "ship at " + ship.getGridX() + "," + ship.getGridY() + " prey at " + prey.getGridX() + "," + prey.getGridY() + " dist " + Geo.dist(
                            ship.getGridX(), ship.getGridY(), prey.getGridX(),
                            prey.getGridY()) + " prey hp " + prey.getHitPoints() + " moving " + ship.isMoving() + " " + behaviourName(
                                    ship) + " deck " + warriorsAboard(ship));
        if (ship.getGridX() != d.last_x || ship.getGridY() != d.last_y) {
            d.last_x = ship.getGridX();
            d.last_y = ship.getGridY();
            d.moved = second;
        }
        if (second - d.since >= 6 && !ship.isMoving()) {
            d.since = second;
            me.setSailingTarget(Selectable.newArray(ship), prey);
        }
    }

    // --- queries ---------------------------------------------------------------------------------------------------

    /** Our ships docked at home and free. */
    @NonNull
    List<@NonNull Ship> idleAtHome() {
        List<Ship> out = new ArrayList<>();
        int home = ai.colony.island;
        for (Map.Entry<Ship, Duty> e : duties.entrySet()) {
            Ship s = e.getKey();
            Duty d = e.getValue();
            if (s.isDead() || !s.isComplete() || d.op != null || s.isMoving() || ai.raid.owns(s) || ai.expand.owns(s)
                    || ai.evac.owns(s))
                continue;
            if ((d.job == Job.IDLE || d.job == Job.ARRIVED || d.job == Job.FAILED) && dockedAt(s) == home)
                out.add(s);
        }
        return out;
    }

    /** Free ships that are not at home (to bring back). */
    void recallStrays(int second) {
        int home = ai.colony.island;
        for (Map.Entry<Ship, Duty> e : duties.entrySet()) {
            Ship s = e.getKey();
            Duty d = e.getValue();
            if (s.isDead() || !s.isComplete() || d.op != null || ai.raid.owns(s) || ai.expand.owns(s) || ai.evac.owns(
                    s))
                continue;
            if ((d.job == Job.IDLE || d.job == Job.ARRIVED || d.job == Job.FAILED) && dockedAt(s) != home
                    && !s.isMoving())
                goHome(s, second);
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
        int[] dock = homeDock();
        c.projects.add(new Colony.Project(Race.BUILDING_SHIP, dock[0], dock[1], second));
        ai.log.log("BUILD", () -> "plan ship near " + dock[0] + "," + dock[1]);
    }
}
