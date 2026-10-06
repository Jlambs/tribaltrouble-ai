package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operations across the sea. An assault musters enough warriors at the home dock for the target's defence, embarks
 * them on as many ships as it takes (with peons to row), lands them together at a beachhead out of reach of the
 * enemy's towers, and sends the ships home; the army then fights on the island ({@link Army}) until no enemy building
 * is left. A hunt puts deck fighters on a ship and chases an enemy ship.
 */
final class Campaign {
    enum Kind {
        ASSAULT,
        HUNT
    }

    enum State {
        EMBARK,
        SAIL,
        LANDING,
        FIGHT,
        DONE
    }

    static final class Op {
        final int id;
        final @NonNull Kind kind;
        @NonNull
        State state = State.EMBARK;
        final int island;
        final int tx;
        final int ty;
        int bx;
        int by;
        final List<@NonNull Ship> ships = new ArrayList<>();
        final Map<@NonNull Unit, @NonNull Ship> boarding = new LinkedHashMap<>();
        int since;
        int carried;
        @Nullable
        Ship prey;

        Op(int id, @NonNull Kind kind, int island, int tx, int ty) {
            this.id = id;
            this.kind = kind;
            this.island = island;
            this.tx = tx;
            this.ty = ty;
        }
    }

    private final @NonNull CorsairAI ai;
    private final @NonNull Player me;
    final List<@NonNull Op> ops = new ArrayList<>();
    private int next_id = 1;
    private int last_launch = -1000;

    Campaign(@NonNull CorsairAI ai) {
        this.ai = ai;
        this.me = ai.me();
    }

    /** Whether an operation is fighting on the island. */
    boolean fightingOn(int island) {
        for (Op op : ops)
            if (op.island == island && op.kind == Kind.ASSAULT)
                return true;
        return false;
    }

    void think(@NonNull Intel in, int second) {
        for (Iterator<Op> it = ops.iterator(); it.hasNext();) {
            Op op = it.next();
            step(in, op, second);
            if (op.state == State.DONE) {
                release(op);
                it.remove();
            }
        }
        ai.fleet.recallStrays(second);
        if (second - last_launch >= 10 && ai.colony.founded() && !ai.army.homeThreatened())
            launch(in, second);
    }

    private void release(@NonNull Op op) {
        for (Ship s : op.ships)
            if (!s.isDead())
                ai.fleet.duty(s).op = null;
        for (Unit u : op.boarding.keySet())
            ai.release(u);
        op.ships.clear();
        op.boarding.clear();
    }

    // --- launching -------------------------------------------------------------------------------------------------

    /** Enemy defence of an island near a point: every warrior on it, and towers within 30 at four warriors each. */
    float defence(@NonNull Intel in, int island, int x, int y) {
        float d = 0;
        for (Unit u : in.enemy_warriors)
            if (u.getIslandId() == island)
                d += Intel.value(u);
        for (Building b : in.enemy_buildings)
            if (Intel.isTower(b) && b.isComplete() && Geo.dist2(b.getGridX(), b.getGridY(), x, y) <= 30 * 30)
                d += 4f;
        return d;
    }

    private void launch(@NonNull Intel in, int second) {
        Colony c = ai.colony;
        List<Unit> free = ai.army.freeWarriorsAtHome(in);
        if (landAssault(in, free, second))
            return;
        List<Ship> ships = ai.fleet.idleAtHome();
        if (ships.isEmpty())
            return;
        // an assault on the weakest, nearest enemy island
        Building target = null;
        float best = Float.MAX_VALUE;
        float target_def = 0;
        for (Building b : in.enemy_buildings) {
            int isl = b.getIslandId();
            if (isl == 0 || isl == c.island || b.isDead())
                continue;
            if (!(Intel.isArmory(b) || Intel.isQuarters(b)) && hasEconomy(in, isl))
                continue;
            float def = defence(in, isl, b.getGridX(), b.getGridY());
            int[] field = homeField();
            int sea = ai.sea.to(field, b.getGridX(), b.getGridY());
            float cost = 2f * def + Math.min(sea, 2000) / 40f + (fightingOn(isl) ? -10f : 0f);
            if (cost < best) {
                best = cost;
                target = b;
                target_def = def;
            }
        }
        int pop = in.units();
        boolean capped = pop >= me.getWorld().getMaxUnitCount() - 6;
        if (target != null) {
            int need = Math.max(ai.params.expedition_size, (int) Math.ceil(ai.params.attack_ratio * target_def + 6));
            int room = 26 * ships.size();
            int all_ships = ai.fleet.duties.size();
            // the whole force at once: enough ships at home for it, or every ship we have
            boolean enough_ships = room >= need || ships.size() >= all_ships;
            boolean go = enough_ships && (free.size() >= need || (capped && free.size() >= Math.min(need, 30)
                    && free.size() >= target_def));
            if (go) {
                int take = Math.min(free.size(), room);
                assault(in, target, ships, free.subList(0, take), second);
                return;
            }
        }
        // no land target worth it: hunt enemy ships when no enemy building is left on other islands
        if (target == null && !in.enemy_ships.isEmpty() && free.size() >= 12) {
            Ship prey = nearestEnemyShip(in);
            if (prey != null)
                hunt(in, ships.getFirst(), prey, free.subList(0, Math.min(26, free.size())), second);
        }
    }

    /**
     * An enemy on our own island: march on it once we have the warriors for its defence; while a march goes on, new
     * warriors beyond a home guard of 6 join it.
     */
    private boolean landAssault(@NonNull Intel in, @NonNull List<@NonNull Unit> free, int second) {
        Colony c = ai.colony;
        Building target = Army.nearestBuilding(in.enemy_buildings, c.island, c.ax, c.ay);
        if (target == null)
            return false;
        Army.Group g = null;
        for (Army.Group x : ai.army.groups)
            if (x.island == c.island)
                g = x;
        if (g != null) {
            if (free.size() > 6) {
                for (Unit w : free.subList(6, free.size()))
                    g.members.add(w);
            }
            return true;
        }
        float def = defence(in, c.island, target.getGridX(), target.getGridY());
        int need = Math.max(ai.params.expedition_size, (int) Math.ceil(ai.params.attack_ratio * def + 6));
        boolean capped = in.units() >= me.getWorld().getMaxUnitCount() - 6;
        if (free.size() >= need || (capped && free.size() >= def)) {
            ai.army.assault(free, c.island, target.getGridX(), target.getGridY(), second);
            last_launch = second;
            int n = free.size();
            ai.log.log("CAMP",
                    () -> "march on " + target.getGridX() + "," + target.getGridY() + " with " + n + " warriors (defence " + def + ") t" + second);
            ai.log.count("march");
            return true;
        }
        return true; // wait for the warriors: the enemy at home comes first
    }

    private boolean hasEconomy(@NonNull Intel in, int island) {
        for (Building b : in.enemy_buildings)
            if (b.getIslandId() == island && (Intel.isArmory(b) || Intel.isQuarters(b)))
                return true;
        return false;
    }

    int @NonNull [] homeField() {
        if (ai.fleet.home_field == null) {
            int[] dock = ai.fleet.homeDock();
            ai.fleet.home_field = ai.sea.field(dock[0], dock[1]);
        }
        return ai.fleet.home_field;
    }

    private @Nullable Ship nearestEnemyShip(@NonNull Intel in) {
        int[] dock = ai.fleet.homeDock();
        Ship best = null;
        long bd = Long.MAX_VALUE;
        for (Ship s : in.enemy_ships) {
            if (s.isDead() || !s.isComplete())
                continue;
            long d = Geo.dist2(s.getGridX(), s.getGridY(), dock[0], dock[1]);
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    /** A shore cell of the target island out of reach of the enemy's towers, near the target. */
    int @Nullable [] beachhead(@NonNull Intel in, int island, int tx, int ty) {
        Geo.Isle isle = ai.geo.isle(island);
        if (isle == null)
            return null;
        int[] field = homeField();
        int[] best = null;
        float best_cost = Float.MAX_VALUE;
        for (int[] c : isle.shore) {
            float walk = (float) Math.sqrt((double) Geo.dist2(c[0], c[1], tx, ty));
            if (walk > 110)
                continue;
            float danger = 0;
            for (Building b : in.enemy_buildings) {
                if (b.getIslandId() != island)
                    continue;
                long d2 = Geo.dist2(b.getGridX(), b.getGridY(), c[0], c[1]);
                if (Intel.isTower(b) && d2 < 26 * 26)
                    danger += 1000;
                else if (d2 < 14 * 14)
                    danger += 200;
            }
            for (Unit u : in.enemy_warriors)
                if (u.getIslandId() == island && Geo.dist2(u.getGridX(), u.getGridY(), c[0], c[1]) < 20 * 20)
                    danger += 15 * Intel.value(u);
            int sea = ai.sea.to(field, c[0], c[1]);
            if (sea >= SeaMap.FAR)
                continue;
            float cost = sea / 4f + 2.5f * walk + danger;
            if (cost < best_cost) {
                best_cost = cost;
                best = c;
            }
        }
        return best;
    }

    private void assault(@NonNull Intel in, @NonNull Building target, @NonNull List<@NonNull Ship> ships,
            @NonNull List<@NonNull Unit> warriors, int second) {
        int island = target.getIslandId();
        int[] beach = beachhead(in, island, target.getGridX(), target.getGridY());
        if (beach == null)
            return;
        Op op = new Op(next_id++, Kind.ASSAULT, island, target.getGridX(), target.getGridY());
        op.bx = beach[0];
        op.by = beach[1];
        op.since = second;
        int need_ships = Math.min(ships.size(), (warriors.size() + 25) / 26);
        for (int i = 0; i < need_ships; i++) {
            Ship s = ships.get(i);
            op.ships.add(s);
            ai.fleet.duty(s).op = op;
        }
        embark(in, op, warriors, second);
        ops.add(op);
        last_launch = second;
        ai.log.log("CAMP",
                () -> "op " + op.id + " assault isle " + island + " target " + op.tx + "," + op.ty + " beach " + op.bx + "," + op.by + " with " + op.boarding.size() + " units on " + op.ships.size() + " ships t" + second);
        ai.log.count("assault");
    }

    private void hunt(@NonNull Intel in, @NonNull Ship ship, @NonNull Ship prey, @NonNull List<@NonNull Unit> warriors,
            int second) {
        Op op = new Op(next_id++, Kind.HUNT, 0, prey.getGridX(), prey.getGridY());
        op.prey = prey;
        op.since = second;
        op.ships.add(ship);
        ai.fleet.duty(ship).op = op;
        embark(in, op, warriors, second);
        ops.add(op);
        last_launch = second;
        ai.log.log("CAMP",
                () -> "op " + op.id + " hunt ship at " + prey.getGridX() + "," + prey.getGridY() + " t" + second);
        ai.log.count("hunt");
    }

    /** Warriors to the op's ships, 26 each, and peons to row where a ship lacks them. */
    private void embark(@NonNull Intel in, @NonNull Op op, @NonNull List<@NonNull Unit> warriors, int second) {
        int i = 0;
        for (Ship s : op.ships) {
            List<Unit> group = new ArrayList<>();
            for (int k = 0; k < 26 && i < warriors.size(); k++, i++)
                group.add(warriors.get(i));
            int rowers = Math.max(0, ai.params.rowers - Fleet.peonsAboard(s));
            List<Unit> peons = rowerCandidates(in, s, rowers);
            group.addAll(peons);
            for (Unit u : group) {
                op.boarding.put(u, s);
                ai.reserve(u, 90);
            }
            if (!group.isEmpty())
                me.setTarget(group.toArray(new Unit[0]), s, Action.MOVE, false);
        }
    }

    private @NonNull List<@NonNull Unit> rowerCandidates(@NonNull Intel in, @NonNull Ship s, int n) {
        List<Unit> out = new ArrayList<>();
        if (n <= 0)
            return out;
        List<Unit> peons = new ArrayList<>();
        for (Unit u : in.peons)
            if (u.getIslandId() == ai.colony.island && !ai.reserved(u)
                    && (u.getPrimaryController() instanceof IdleController
                            || u.getPrimaryController() instanceof GatherController<?>))
                peons.add(u);
        Colony.sortByDistance(peons, s.getGridX(), s.getGridY());
        for (Unit u : peons)
            if (out.size() < n)
                out.add(u);
        return out;
    }

    // --- running ---------------------------------------------------------------------------------------------------

    private void step(@NonNull Intel in, @NonNull Op op, int second) {
        op.ships.removeIf(Ship::isDead);
        switch (op.state) {
            case EMBARK -> embarking(in, op, second);
            case SAIL -> sailing(op, second);
            case LANDING -> landing(op, second);
            case FIGHT -> fighting(in, op, second);
            default -> {
            }
        }
    }

    private void embarking(@NonNull Intel in, @NonNull Op op, int second) {
        int outside = 0;
        Map<Ship, List<Unit>> lagging = new LinkedHashMap<>();
        for (Map.Entry<Unit, Ship> e : op.boarding.entrySet()) {
            Unit u = e.getKey();
            if (u.isDead() || !inSet(u))
                continue;
            outside++;
            if (u.getPrimaryController() instanceof IdleController && !e.getValue().isDead())
                lagging.computeIfAbsent(e.getValue(), s -> new ArrayList<>()).add(u);
        }
        for (Map.Entry<Ship, List<Unit>> e : lagging.entrySet())
            me.setTarget(e.getValue().toArray(new Unit[0]), e.getKey(), Action.MOVE, false);
        if (outside > 0 && second - op.since < 40)
            return;
        for (Unit u : op.boarding.keySet())
            ai.release(u);
        op.boarding.clear();
        int aboard = 0;
        for (Ship s : op.ships)
            aboard += Fleet.warriorsAboard(s);
        op.carried = aboard;
        if (aboard == 0) {
            op.state = State.DONE;
            ai.log.log("CAMP", () -> "op " + op.id + " embark failed");
            return;
        }
        op.state = State.SAIL;
        op.since = second;
        if (op.kind == Kind.HUNT) {
            Ship prey = op.prey;
            if (prey == null || prey.isDead()) {
                op.state = State.DONE;
                return;
            }
            for (Ship s : op.ships)
                ai.fleet.startHunt(s, prey, second);
            return;
        }
        for (Ship s : op.ships)
            ai.fleet.goTo(s, op.island, op.bx, op.by, second);
        int carried = aboard;
        ai.log.log("CAMP", () -> "op " + op.id + " sails with " + carried + " warriors t" + second);
    }

    private boolean inSet(@NonNull Unit u) {
        return !u.isDead() && !u.isMounted() && me.getUnits().getSet().contains(u);
    }

    private void sailing(@NonNull Op op, int second) {
        if (op.kind == Kind.HUNT) {
            boolean active = false;
            for (Ship s : op.ships)
                if (ai.fleet.duty(s).job == Fleet.Job.HUNT)
                    active = true;
            if (!active || op.prey == null || op.prey.isDead() || second - op.since > 300) {
                for (Ship s : op.ships) {
                    ai.fleet.duty(s).op = null;
                    ai.fleet.goHome(s, second);
                }
                op.ships.clear();
                op.state = State.DONE;
                ai.log.log("CAMP", () -> "op " + op.id + " hunt over t" + second);
            }
            return;
        }
        boolean all = true;
        for (Ship s : op.ships) {
            Fleet.Duty d = ai.fleet.duty(s);
            if (d.job == Fleet.Job.ARRIVED)
                ai.fleet.landWarriors(s);
            else if (d.job == Fleet.Job.FAILED)
                ai.fleet.goHome(s, second);
            else if (d.job == Fleet.Job.TRANSIT && d.island == op.island)
                all = false;
        }
        if (op.ships.isEmpty()) {
            op.state = State.DONE;
            return;
        }
        if (all || second - op.since > 240) {
            op.state = State.LANDING;
            op.since = second;
            ai.log.log("CAMP", () -> "op " + op.id + " landing t" + second);
        }
    }

    private void landing(@NonNull Op op, int second) {
        boolean empty = true;
        for (Ship s : op.ships) {
            if (Fleet.warriorsAboard(s) > 0 && Fleet.dockedAt(s) == op.island) {
                ai.fleet.landWarriors(s);
                empty = false;
            }
        }
        if (!empty && second - op.since < 30)
            return;
        for (Ship s : op.ships) {
            ai.fleet.duty(s).op = null;
            ai.fleet.goHome(s, second);
        }
        op.ships.clear();
        op.state = State.FIGHT;
        op.since = second;
        ai.log.log("CAMP", () -> "op " + op.id + " landed, fighting t" + second);
    }

    private void fighting(@NonNull Intel in, @NonNull Op op, int second) {
        boolean buildings = false;
        for (Building b : in.enemy_buildings)
            if (b.getIslandId() == op.island) {
                buildings = true;
                break;
            }
        int ours = 0;
        for (Unit w : in.warriors)
            if (w.getIslandId() == op.island)
                ours++;
        if (!buildings || ours == 0) {
            op.state = State.DONE;
            int left = ours;
            ai.log.log("CAMP",
                    () -> "op " + op.id + (left == 0 ? " lost" : " cleared isle " + op.island) + " t" + second);
        }
    }

    /** Whether a unit is walking aboard for an operation. */
    boolean boarding(@NonNull Unit u) {
        for (Op op : ops)
            if (op.boarding.containsKey(u))
                return true;
        return false;
    }

    static boolean isFree(@NonNull Unit w) {
        return w.getPrimaryController() instanceof IdleController || w.getPrimaryController() instanceof WalkController;
    }
}
