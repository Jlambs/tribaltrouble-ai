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
        /** The ship the chieftain boards, and the warriors that board it once he sits in his seat. */
        @Nullable
        Ship chief_ship;
        final List<@NonNull Unit> after_chief = new ArrayList<>();
        /** pack: per ship, the warriors that board the benches once its rowers sit (they would take their room). */
        final Map<@NonNull Ship, @NonNull List<@NonNull Unit>> after_rowers = new LinkedHashMap<>();
        int since;
        int carried;
        int started;
        int landed_at = Integer.MAX_VALUE;
        /** unload_wait: the warriors still aboard the docked ships, and when that last fell. */
        int last_aboard = Integer.MAX_VALUE;
        int unloaded_at = -1;
        int kills0;
        int lost0;
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

    /**
     * stage: a beachhead where landed warriors hold while ships bring the rest, until the whole force outweighs the
     * target's defence (or nothing more is coming, or stage_max seconds passed).
     */
    static final class Stage {
        final int island;
        final int bx;
        final int by;
        final int tx;
        final int ty;
        final int since;
        boolean released;
        int last_n;
        int last_growth;

        Stage(int island, int bx, int by, int tx, int ty, int since) {
            this.island = island;
            this.bx = bx;
            this.by = by;
            this.tx = tx;
            this.ty = ty;
            this.since = since;
            last_growth = since;
        }
    }

    private final @NonNull CorsairAI ai;
    private final @NonNull Player me;
    final List<@NonNull Op> ops = new ArrayList<>();
    final List<@NonNull Stage> stages = new ArrayList<>();
    private int next_id = 1;
    private int last_launch = -1000;
    /** When the last assault set out (the stalemate clock of siege_after). */
    private int last_assault = 0;

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
                int k = me.getUnitsKilled() - op.kills0, l = me.getUnitsLost() - op.lost0;
                ai.log.log("CAMP",
                        () -> "op " + op.id + " " + op.kind + " isle " + op.island + " over after " + (second - op.started) + " s: carried " + op.carried + ", kills +" + k + ", lost +" + l);
                release(op);
                it.remove();
            }
        }
        ai.fleet.recallStrays(second);
        manageStages(in, second);
        if (second - last_launch >= 10 && ai.colony.founded() && !ai.army.homeThreatened())
            launch(in, second);
    }

    /** The holding stage on an island, or null (none, or released: the troops advance). */
    @Nullable
    Stage holding(int island) {
        for (Stage s : stages)
            if (s.island == island && !s.released)
                return s;
        return null;
    }

    private void manageStages(@NonNull Intel in, int second) {
        for (java.util.Iterator<Stage> it = stages.iterator(); it.hasNext();) {
            Stage s = it.next();
            int n = 0;
            float ours = 0;
            for (Unit w : in.warriors)
                if (w.getIslandId() == s.island) {
                    n++;
                    ours += Intel.value(w);
                }
            boolean in_flight = false;
            for (Op op : ops)
                if (op.island == s.island && op.state != State.FIGHT && op.state != State.DONE)
                    in_flight = true;
            if (n == 0 && !in_flight && second - s.since > 30) {
                it.remove();
                continue;
            }
            if (!Army.enemyOn(in, s.island)) {
                it.remove();
                continue;
            }
            if (s.released)
                continue;
            if (n > s.last_n) {
                s.last_n = n;
                s.last_growth = second;
            }
            float def = defence(in, s.island, s.tx, s.ty);
            boolean ready = ours >= ai.params.attack_ratio * def;
            boolean nothing_coming = !in_flight && second - s.last_growth > 120
                    && ai.army.freeWarriorsAtHome(in).size() < 15;
            if (ready || nothing_coming || second - s.since > ai.params.stage_max) {
                s.released = true;
                String why = ready ? "strong enough" : nothing_coming ? "nothing more coming" : "staged long enough";
                float o = ours, d = def;
                int nn = n;
                ai.log.log("CAMP",
                        () -> "stage on isle " + s.island + " released (" + why + "): " + nn + " warriors, " + o + " vs defence " + d + " t" + second);
                ai.log.count("stage_release");
            }
        }
    }

    private void release(@NonNull Op op) {
        for (Ship s : op.ships)
            if (!s.isDead())
                ai.fleet.duty(s).op = null;
        for (Unit u : op.boarding.keySet())
            ai.release(u);
        for (Unit u : op.after_chief)
            ai.release(u);
        for (List<Unit> l : op.after_rowers.values())
            for (Unit u : l)
                ai.release(u);
        op.ships.clear();
        op.boarding.clear();
        op.after_chief.clear();
        op.after_rowers.clear();
    }

    // --- launching -------------------------------------------------------------------------------------------------

    /** Enemy defence of an island near a point: every warrior on it, and towers within 30 at four warriors each. */
    float defence(@NonNull Intel in, int island, int x, int y) {
        float d = 0;
        float their_bonus = 0;
        int n = 0;
        for (Unit u : in.enemy_warriors)
            if (u.getIslandId() == island) {
                d += Intel.value(u);
                their_bonus += Ratchet.bonus(u);
                n++;
            }
        for (Building b : in.enemy_buildings)
            if (Intel.isTower(b) && b.isComplete() && Geo.dist2(b.getGridX(), b.getGridY(), x, y) <= 30 * 30)
                d += 4f;
        // range_edge: kiting turns a range edge into kills; the defence counts for less the more our drilled warriors
        // outreach theirs (per cell of mean drill bonus, clamped to [edge_floor, 1.4])
        if (n > 0)
            d *= rangeFactor(their_bonus / n);
        return d;
    }

    /** range_edge: what an enemy's strength counts for against our warriors, from their mean drill bonus. */
    float rangeFactor(float their_bonus) {
        if (ai.params.range_edge <= 0)
            return 1f;
        float edge = Ratchet.meanBonus(ai.intel.warriors) - their_bonus;
        return Math.clamp(1f - (float) ai.params.range_edge * edge, (float) ai.params.edge_floor, 1.4f);
    }

    /** Per ally: the sea field from its base, refreshed every two minutes (team_target). */
    private final Map<com.oddlabs.tt.player.@NonNull Player, int @NonNull []> ally_fields = new LinkedHashMap<>();
    private int ally_fields_at = -1000;

    /** An ally's base: the centre of its quarters and armories, or null without any. */
    private static int @Nullable [] baseOf(com.oddlabs.tt.player.@NonNull Player p) {
        long sx = 0, sy = 0;
        int n = 0;
        for (com.oddlabs.tt.model.Selectable<?> s : p.getUnits().getSet())
            if (s instanceof Building b && !b.isDead() && b.isComplete() && (Intel.isQuarters(b) || Intel.isArmory(
                    b))) {
                        sx += b.getGridX();
                        sy += b.getGridY();
                        n++;
                    }
        return n == 0 ? null : new int[]{(int) (sx / n), (int) (sy / n)};
    }

    private void refreshAllyFields(@NonNull Intel in, int second) {
        if (second - ally_fields_at < 120)
            return;
        ally_fields_at = second;
        ally_fields.clear();
        for (com.oddlabs.tt.player.Player p : in.allies) {
            int[] base = baseOf(p);
            if (base == null)
                continue;
            Geo.Isle isle = ai.geo.isle(ai.geo.islandAt(base[0], base[1]));
            int[] shore = isle == null ? null : isle.nearestShore(base[0], base[1]);
            if (shore != null)
                ally_fields.put(p, ai.sea.field(shore[0], shore[1]));
        }
    }

    /** Allied warriors standing on their own home islands (what they could ship to a common target). */
    private float allyHomeStrength(@NonNull Intel in) {
        float v = 0;
        for (com.oddlabs.tt.player.Player p : in.allies) {
            int[] base = baseOf(p);
            if (base == null)
                continue;
            int isl = ai.geo.islandAt(base[0], base[1]);
            for (Unit u : in.ally_warriors)
                if (u.getOwner() == p && u.getIslandId() == isl)
                    v += Intel.value(u);
        }
        return v;
    }

    private void launch(@NonNull Intel in, int second) {
        Colony c = ai.colony;
        boolean team = ai.params.team_target && !in.allies.isEmpty();
        if (team)
            refreshAllyFields(in, second);
        List<Unit> free = ai.army.freeWarriorsAtHome(in);
        boolean trace = second % 120 == 0;
        if (landAssault(in, free, second)) {
            if (trace)
                ai.log.log("WAIT", () -> "enemy on our island; free " + free.size() + " t" + second);
            return;
        }
        List<Ship> ships = ai.fleet.idleAtHome();
        if (trace) {
            // where the warriors are: home or abroad, what they do (r: reserved, g: in a group)
            Map<String, Integer> what = new java.util.TreeMap<>();
            for (Unit w : in.warriors) {
                if (w.isDead())
                    continue;
                String k = (w.getIslandId() == c.island ? "home:" : "away:") + w.getPrimaryController().getClass().getSimpleName().replace(
                        "Controller", "") + (ai.reserved(w) ? "/r" : "") + (ai.army.inGroup(w) ? "/g" : "");
                what.merge(k, 1, Integer::sum);
            }
            ai.log.log("ARMY", what::toString);
        }
        if (trace)
            ai.log.log("WAIT",
                    () -> "free " + free.size() + " ships home " + ships.size() + " of " + ai.fleet.duties.size() + " " + ai.fleet.jobs() + " t" + second);
        if (ships.isEmpty())
            return;
        // stage: every free warrior goes to the beachhead that holds
        for (Stage s : stages) {
            if (s.released || free.size() < 15)
                continue;
            int room = 0;
            for (Ship sh : ships)
                room += Math.max(0, 26 - Fleet.warriorsAboard(sh)) + ai.params.pack;
            int take = Math.min(free.size(), room);
            if (take < 15)
                return;
            reinforce(in, s, ships, free.subList(0, take), second);
            return;
        }
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
            float allied = (float) ai.params.ally_join * in.allyStrengthOn(isl);
            float def = Math.max(0f, defence(in, isl, b.getGridX(), b.getGridY()) - .7f * allied);
            int[] field = homeField();
            int sea = ai.sea.to(field, b.getGridX(), b.getGridY());
            if (team) {
                // the whole team measures the same way, so allies pick the same target
                int sum = Math.min(sea, 2000), n = 1;
                for (int[] f : ally_fields.values()) {
                    sum += Math.min(ai.sea.to(f, b.getGridX(), b.getGridY()), 2000);
                    n++;
                }
                sea = sum / n;
            }
            float cost = 2f * def + Math.min(sea, 2000) / 40f + (fightingOn(
                    isl) ? -10f : 0f) - (allied >= 8f ? 15f : 0f);
            if (cost < best) {
                best = cost;
                target = b;
                target_def = def;
            }
        }
        int tx = target == null ? 0 : target.getGridX(), ty = target == null ? 0 : target.getGridY();
        int tisl = target == null ? 0 : target.getIslandId();
        if (target == null) {
            // mop-up: no enemy building on another island, but enemy units on one
            int[] cl = unitCluster(in, c.island);
            if (cl != null) {
                tisl = cl[0];
                tx = cl[1];
                ty = cl[2];
                target_def = defence(in, tisl, tx, ty);
            }
        }
        int pop = in.units();
        boolean capped = pop >= me.getWorld().getMaxUnitCount() - 6;
        if (tisl != 0) {
            int need = Math.max(ai.params.expedition_size, (int) Math.ceil(ai.params.attack_ratio * target_def + 6));
            int room = 0;
            int rowable = shipsWeCanRow(in, ships);
            for (Ship s : ships.subList(0, rowable))
                room += Math.max(0, 26 - Fleet.warriorsAboard(s)) + ai.params.pack;
            int all_ships = ai.fleet.duties.size();
            // the whole force at once: enough ships at home for it, or every ship we have
            boolean enough_ships = room >= need || ships.size() >= all_ships;
            // team_target: allies shipping to the same target at the same time share the need
            float team_free = free.size() + (team ? .8f * allyHomeStrength(in) : 0f);
            boolean go = enough_ships && (free.size() >= need || (team && free.size() >= 15 && team_free >= need)
                    || (capped && free.size() >= Math.min(need, 30) && free.size() >= target_def));
            // siege_after: at the cap and no assault for that long, a stalemate: go with what the ships carry
            if (!go && ai.params.siege_after > 0 && capped && ships.size() >= all_ships && free.size() >= 40
                    && second - last_assault >= ai.params.siege_after) {
                go = true;
                ai.log.count("siege");
            }
            // stage: start a beachhead with what the ships carry, if the beach is quiet; the rest follows
            if (!go && ai.params.stage && free.size() >= Math.min(need, Math.max(30, room)) && holding(tisl) == null) {
                int[] beach = beachhead(in, tisl, tx, ty);
                if (beach != null && beachQuiet(in, tisl, beach[0], beach[1]))
                    go = true;
            }
            if (trace) {
                int t_isl = tisl, t_need = need, t_room = room;
                float t_def = target_def;
                boolean t_go = go;
                ai.log.log("WAIT",
                        () -> "target isle " + t_isl + " def " + t_def + " need " + t_need + " room " + t_room + " go " + t_go);
            }
            if (go) {
                int take = Math.min(free.size(), room);
                assault(in, tisl, tx, ty, ships, free.subList(0, take), second);
                return;
            }
        }
        // hunt enemy ships: when no enemy building is left on other islands, or one comes near our dock
        Ship prey = nearestEnemyShip(in);
        if (prey != null && free.size() >= 12) {
            int[] dock = ai.fleet.homeDock();
            boolean near = Geo.dist2(prey.getGridX(), prey.getGridY(), dock[0], dock[1]) < 90 * 90;
            if (target == null || near)
                hunt(in, ships.getFirst(), prey, free.subList(0, Math.min(26, free.size())), second);
        }
    }

    /**
     * The biggest gathering of enemy units on an island other than {@code skip} (within 25 cells of its first unit),
     * as {island, x, y}, or null.
     */
    int @Nullable [] unitCluster(@NonNull Intel in, int skip) {
        int[] best = null;
        int best_n = 0;
        for (Unit u : in.enemy_units) {
            int isl = u.getIslandId();
            if (isl == 0 || isl == skip)
                continue;
            int n = 0;
            long sx = 0, sy = 0;
            for (Unit v : in.enemy_units)
                if (v.getIslandId() == isl && Geo.dist2(u.getGridX(), u.getGridY(), v.getGridX(),
                        v.getGridY()) <= 25 * 25) {
                            n++;
                            sx += v.getGridX();
                            sy += v.getGridY();
                        }
            if (n > best_n) {
                best_n = n;
                best = new int[]{isl, (int) (sx / n), (int) (sy / n)};
            }
        }
        return best;
    }

    /**
     * An enemy on our own island: march on it once we have the warriors for its defence; while a march goes on, new
     * warriors beyond a home guard of 6 join it.
     */
    private boolean landAssault(@NonNull Intel in, @NonNull List<@NonNull Unit> free, int second) {
        Colony c = ai.colony;
        Building target = Army.nearestBuilding(in.enemy_buildings, c.island, c.ax, c.ay);
        int tx, ty;
        if (target != null) {
            tx = target.getGridX();
            ty = target.getGridY();
        } else {
            // enemy units on our island away from the base (the defence takes those near it)
            Unit far = null;
            long bd = Long.MAX_VALUE;
            for (Unit u : in.enemy_units) {
                if (u.getIslandId() != c.island)
                    continue;
                long d = Geo.dist2(u.getGridX(), u.getGridY(), c.ax, c.ay);
                if (d < bd) {
                    bd = d;
                    far = u;
                }
            }
            if (far == null)
                return false;
            tx = far.getGridX();
            ty = far.getGridY();
        }
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
        float def = defence(in, c.island, tx, ty);
        int need = Math.max(ai.params.expedition_size, (int) Math.ceil(ai.params.attack_ratio * def + 6));
        boolean capped = in.units() >= me.getWorld().getMaxUnitCount() - 6;
        if (free.size() >= need || (capped && free.size() >= def)) {
            ai.army.assault(free, c.island, tx, ty, second);
            last_launch = second;
            int n = free.size();
            ai.log.log("CAMP",
                    () -> "march on " + tx + "," + ty + " with " + n + " warriors (defence " + def + ") t" + second);
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
    /**
     * Units leave a ship one at a time: land where the enemy cannot meet them on the beach, away from their towers,
     * their buildings (they defend near them) and their warriors (drilled ones reach far).
     */
    float beachDanger(@NonNull Intel in, int island, int x, int y) {
        float danger = 0;
        for (Building b : in.enemy_buildings) {
            if (b.getIslandId() != island)
                continue;
            long d2 = Geo.dist2(b.getGridX(), b.getGridY(), x, y);
            if (Intel.isTower(b) && d2 < 30 * 30)
                danger += 1000;
            else if (d2 < (long) ai.params.beach_building_cells * ai.params.beach_building_cells)
                danger += 150;
        }
        for (Unit u : in.enemy_warriors) {
            if (u.getIslandId() != island)
                continue;
            long d2 = Geo.dist2(u.getGridX(), u.getGridY(), x, y);
            int reach = ai.params.beach_warrior_cells + (int) Ratchet.bonus(u);
            if (d2 < (long) reach * reach)
                danger += 25 * Intel.value(u);
        }
        return danger;
    }

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
            float danger = beachDanger(in, island, c[0], c[1]);
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

    /** Whether no enemy warrior (with its reach) nor building is near a beach: a stage can grow there. */
    private boolean beachQuiet(@NonNull Intel in, int island, int x, int y) {
        for (Unit u : in.enemy_warriors) {
            int r = 50 + (int) Ratchet.bonus(u);
            if (u.getIslandId() == island && Geo.dist2(u.getGridX(), u.getGridY(), x, y) < (long) r * r)
                return false;
        }
        for (Building b : in.enemy_buildings)
            if (b.getIslandId() == island && Geo.dist2(b.getGridX(), b.getGridY(), x, y) < 55 * 55)
                return false;
        return true;
    }

    private void reinforce(@NonNull Intel in, @NonNull Stage s, @NonNull List<@NonNull Ship> ships,
            @NonNull List<@NonNull Unit> warriors, int second) {
        Op op = new Op(next_id++, Kind.ASSAULT, s.island, s.tx, s.ty);
        op.started = second;
        op.kills0 = me.getUnitsKilled();
        op.lost0 = me.getUnitsLost();
        op.bx = s.bx;
        op.by = s.by;
        op.since = second;
        int need_ships = Math.min(ships.size(), (warriors.size() + 25 + ai.params.pack) / (26 + ai.params.pack));
        need_ships = Math.min(need_ships, shipsWeCanRow(in, ships));
        for (int i = 0; i < need_ships; i++) {
            Ship sh = ships.get(i);
            op.ships.add(sh);
            ai.fleet.duty(sh).op = op;
            ai.fleet.cancelDeploys(sh);
            ai.ratchet.haltInbound(sh);
        }
        embark(in, op, warriors, second);
        ops.add(op);
        last_launch = second;
        ai.log.log("CAMP",
                () -> "op " + op.id + " reinforces the stage on isle " + s.island + " with " + op.boarding.size() + " units t" + second);
        ai.log.count("reinforce");
    }

    private void assault(@NonNull Intel in, int island, int tx, int ty, @NonNull List<@NonNull Ship> ships,
            @NonNull List<@NonNull Unit> warriors, int second) {
        int[] beach = beachhead(in, island, tx, ty);
        if (beach == null)
            return;
        if (ai.params.stage && holding(island) == null && beachQuiet(in, island, beach[0], beach[1])) {
            boolean exists = false;
            for (Stage s : stages)
                if (s.island == island)
                    exists = true;
            if (!exists) {
                stages.add(new Stage(island, beach[0], beach[1], tx, ty, second));
                ai.log.log("CAMP", () -> "stage on isle " + island + " at " + beach[0] + "," + beach[1]);
            }
        }
        Op op = new Op(next_id++, Kind.ASSAULT, island, tx, ty);
        op.started = second;
        op.kills0 = me.getUnitsKilled();
        op.lost0 = me.getUnitsLost();
        op.bx = beach[0];
        op.by = beach[1];
        op.since = second;
        int need_ships = Math.min(ships.size(), (warriors.size() + 25 + ai.params.pack) / (26 + ai.params.pack));
        need_ships = Math.min(need_ships, shipsWeCanRow(in, ships));
        for (int i = 0; i < need_ships; i++) {
            Ship s = ships.get(i);
            op.ships.add(s);
            ai.fleet.duty(s).op = op;
            ai.fleet.cancelDeploys(s);
            ai.ratchet.haltInbound(s);
        }
        embark(in, op, warriors, second);
        ops.add(op);
        last_launch = second;
        ai.log.log("CAMP",
                () -> "op " + op.id + " assault isle " + island + " target " + op.tx + "," + op.ty + " beach " + op.bx + "," + op.by + " with " + op.boarding.size() + " units on " + op.ships.size() + " ships t" + second);
        ai.log.count("assault");
        last_assault = second;
    }

    private void hunt(@NonNull Intel in, @NonNull Ship ship, @NonNull Ship prey, @NonNull List<@NonNull Unit> warriors,
            int second) {
        Op op = new Op(next_id++, Kind.HUNT, 0, prey.getGridX(), prey.getGridY());
        op.prey = prey;
        op.since = second;
        op.ships.add(ship);
        ai.fleet.duty(ship).op = op;
        ai.fleet.cancelDeploys(ship);
        ai.ratchet.haltInbound(ship);
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
        // the chieftain rides along: his seat doubles the ship's speed, and it is free while 22 warriors or fewer sit
        // on the upper deck (they fill it from the stern, his seat's neighbours last)
        Unit chief = in.chief;
        boolean take_chief = ai.params.chief_rides && op.kind == Kind.ASSAULT && chief != null && !chief.isDead()
                && !chief.isMounted() && chief.getIslandId() == ai.colony.island && !ai.reserved(chief);
        // Never let him sit anywhere but his own seat: alone on a lower bench he would be given an oar, which only a
        // peon can hold (an engine assertion, a crash outside the harness). His seat needs both front pairs of the
        // upper deck empty, so he boards only a ship with no warriors aboard, which takes at most 22 more.
        Ship chief_ship = null;
        if (take_chief)
            for (Ship s : op.ships)
                if (Fleet.warriorsAboard(s) == 0 && !s.getShipHR().hasChieftain()) {
                    chief_ship = s;
                    break;
                }
        op.chief_ship = chief_ship;
        for (Ship s : op.ships) {
            List<Unit> group = new ArrayList<>();
            // warriors beyond the upper deck's 26 seats sit on the benches and leave no room to row
            int seats = Math.max(0, 26 - Fleet.warriorsAboard(s));
            if (s == chief_ship) {
                group.add(chief);
                seats = 22;
            }
            List<Unit> later = s == chief_ship ? op.after_chief : group;
            for (int k = 0; k < seats && i < warriors.size(); k++, i++)
                later.add(warriors.get(i));
            // pack: once the rowers sit, the benches hold about 40 more warriors (1.2 m each beside 0.7 m rowers)
            if (ai.params.pack > 0 && s != chief_ship) {
                List<Unit> benches = new ArrayList<>();
                for (int k = 0; k < ai.params.pack && i < warriors.size(); k++, i++)
                    benches.add(warriors.get(i));
                if (!benches.isEmpty()) {
                    op.after_rowers.put(s, benches);
                    for (Unit u : benches)
                        ai.reserve(u, 120);
                }
            }
            int rowers = Math.max(0, ai.params.rowers - Fleet.peonsAboard(s));
            List<Unit> peons = rowerCandidates(in, s, rowers);
            group.addAll(peons);
            for (Unit u : group) {
                op.boarding.put(u, s);
                ai.reserve(u, 90);
            }
            for (Unit u : op.after_chief)
                ai.reserve(u, 90);
            if (!group.isEmpty())
                ai.order(group, s, Action.MOVE, false);
        }
    }

    /** Peons rowing or walking aboard for any operation. */
    private int rowersAway(@NonNull Intel in) {
        int n = 0;
        for (Ship s : in.ships)
            if (ai.fleet.duty(s).op != null)
                n += Fleet.peonsAboard(s);
        for (Op op : ops)
            for (Unit u : op.boarding.keySet())
                if (!u.isDead() && !u.isWarrior() && !u.isChieftain() && inSet(u))
                    n++;
        return n;
    }

    /**
     * row_min: how many of the ships we can man with at least row_min rowers each (at least one), from the peons
     * aboard them, the idle and gathering peons at home and those in our quarters. At the unit cap the peons run out:
     * an op of 5 ships once sailed with 20, 20, 5, 0 and 0 rowers, and the empty ships never left the dock.
     */
    private int shipsWeCanRow(@NonNull Intel in, @NonNull List<@NonNull Ship> ships) {
        if (ai.params.row_min <= 0)
            return ships.size();
        int n = 0;
        for (Ship s : ships)
            n += Fleet.peonsAboard(s);
        for (Unit u : in.peons)
            if (u.getIslandId() == ai.colony.island && !u.isMounted() && !ai.reserved(u)
                    && (u.getPrimaryController() instanceof IdleController
                            || u.getPrimaryController() instanceof GatherController<?>))
                n++;
        for (Building q : in.quarters)
            if (q.getUnitContainer() != null)
                n += q.getUnitContainer().getNumSupplies();
        for (Building a : in.armories)
            if (a.getIslandId() == ai.colony.island)
                n += ai.colony.armorySpare(in, a);
        return Math.max(1, Math.min(ships.size(), n / ai.params.row_min));
    }

    private @NonNull List<@NonNull Unit> rowerCandidates(@NonNull Intel in, @NonNull Ship s, int n) {
        List<Unit> out = new ArrayList<>();
        // rower_floor: the economy keeps that many peons at home whatever the ships want
        if (ai.params.rower_floor > 0) {
            int home = in.peons.size() + in.peonsInside() - rowersAway(in);
            n = Math.min(n, home - ai.params.rower_floor);
        }
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
        // pack: bench warriors board once their ship's rowers are seated (or 30 s passed)
        for (java.util.Iterator<Map.Entry<Ship, List<Unit>>> it = op.after_rowers.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Ship, List<Unit>> e = it.next();
            Ship s = e.getKey();
            if (s.isDead()) {
                for (Unit u : e.getValue())
                    ai.release(u);
                it.remove();
                continue;
            }
            // (warriors on the benches leave no room for rowers still walking up: an op once sailed with 3 of 40)
            int coming = 0;
            for (Map.Entry<Unit, Ship> b : op.boarding.entrySet())
                if (b.getValue() == s && inSet(b.getKey()) && !b.getKey().isWarrior() && !b.getKey().isChieftain())
                    coming++;
            if (Fleet.peonsAboard(s) >= Math.min(ai.params.rowers, 12) || coming == 0 && second - op.since > 20
                    || second - op.since > 75) {
                List<Unit> go = new ArrayList<>();
                for (Unit u : e.getValue())
                    if (!u.isDead()) {
                        go.add(u);
                        op.boarding.put(u, s);
                    }
                ai.order(go, s, Action.MOVE, false);
                it.remove();
            }
        }
        // the chieftain's ship: its warriors board once he sits in his seat (or he is gone, or 25 s passed)
        if (!op.after_chief.isEmpty() && op.chief_ship != null) {
            Ship cs = op.chief_ship;
            boolean seated = !cs.isDead() && cs.getShipHR().hasChieftain();
            boolean stuck = second - op.since > 25 || in.chief == null && !seated && !me.hasActiveChieftain();
            if (seated || stuck) {
                List<Unit> go = new ArrayList<>();
                for (Unit u : op.after_chief)
                    if (!u.isDead()) {
                        go.add(u);
                        op.boarding.put(u, cs);
                    }
                op.after_chief.clear();
                if (!go.isEmpty() && !cs.isDead() && (seated || Fleet.warriorsAboard(cs) == 0))
                    ai.order(go, cs, Action.MOVE, false);
                else
                    for (Unit u : go)
                        ai.release(u);
            }
            return;
        }
        op.boarding.keySet().removeIf(Unit::isDead);
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
            ai.order(e.getValue(), e.getKey(), Action.MOVE, false);
        // rowers: a ship without peons crawls; top them up while boarding goes on
        boolean short_rowers = false;
        for (Ship s : ai.params.rower_topup ? op.ships : List.<Ship>of()) {
            int coming = 0;
            for (Map.Entry<Unit, Ship> e : op.boarding.entrySet())
                if (e.getValue() == s && !e.getKey().isWarrior() && !e.getKey().isChieftain() && inSet(e.getKey()))
                    coming++;
            int have = Fleet.peonsAboard(s) + coming;
            if (have < ai.params.rowers) {
                List<Unit> more = rowerCandidates(in, s, ai.params.rowers - have);
                for (Unit u : more) {
                    op.boarding.put(u, s);
                    ai.reserve(u, 90);
                }
                if (!more.isEmpty())
                    ai.order(more, s, Action.MOVE, false);
                else if (ai.params.rower_floor == 0)
                    ai.colony.releasePeons(in, ai.params.rowers - have);
            }
            if (Fleet.peonsAboard(s) < 8)
                short_rowers = true;
        }
        if ((outside > 0 || short_rowers || !op.after_rowers.isEmpty())
                && second - op.since < 40 + (ai.params.pack > 0 ? 55 : 0))
            return;
        if (short_rowers && second - op.since < 70)
            return;
        for (Unit u : op.boarding.keySet())
            ai.release(u);
        op.boarding.clear();
        // row_min: a ship that got almost no rowers would crawl; its warriors stay home and it stays for the drill
        if (ai.params.row_min > 0 && op.kind == Kind.ASSAULT) {
            int best = 0;
            for (Ship s : op.ships)
                best = Math.max(best, s.getShipHR().countRowers());
            if (best < 2) {
                // nobody to row: everyone lands again
                for (Ship s : op.ships) {
                    ai.fleet.duty(s).op = null;
                    ai.fleet.landAll(s);
                }
                op.ships.clear();
                op.state = State.DONE;
                ai.log.log("CAMP", () -> "op " + op.id + " has no rowers t" + second);
                ai.log.count("unrowed");
                return;
            }
            for (java.util.Iterator<Ship> it = op.ships.iterator(); it.hasNext();) {
                Ship s = it.next();
                if (s.getShipHR().countRowers() < Math.min(4, best)) {
                    ai.fleet.duty(s).op = null;
                    ai.fleet.landAll(s);
                    it.remove();
                    ai.log.count("unrowed");
                }
            }
        }
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
        // docks: the ships of an op land side by side, apart enough to leave again
        if (ai.params.docks > 1 && op.kind == Kind.ASSAULT)
            for (int k = 0; k < op.ships.size(); k++) {
                int[] cell = ai.fleet.spacedShore(op.island, op.bx, op.by, k);
                ai.fleet.goTo(op.ships.get(k), op.island, cell[0], cell[1], second);
            }
        int carried = aboard;
        int rowers = 0;
        for (Ship s : op.ships)
            rowers += s.getShipHR().countRowers();
        int rowing = rowers;
        ai.log.log("CAMP",
                () -> "op " + op.id + " sails with " + carried + " warriors, " + rowing + " rowers on " + op.ships.size() + " ships t" + second);
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
            boolean headway = false;
            for (Ship s : op.ships)
                if (second - ai.fleet.duty(s).moved < 15)
                    headway = true;
            if (!active || op.prey == null || op.prey.isDead() || (!headway && second - op.since > 120)
                    || second - op.since > 900) {
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
        boolean moving = false;
        for (Ship s : op.ships) {
            Fleet.Duty d = ai.fleet.duty(s);
            if (d.job == Fleet.Job.TRANSIT && second - d.moved < 10)
                moving = true;
            if (d.job == Fleet.Job.ARRIVED && op.landed_at == Integer.MAX_VALUE)
                op.landed_at = second;
            if (d.job == Fleet.Job.ARRIVED) {
                if (!holdFire(ai.intel, s, op, second)) {
                    ai.fleet.landWarriors(s);
                    if (s.getShipHR().hasChieftain())
                        me.trainChieftain(s, true);
                }
            } else if (d.job == Fleet.Job.FAILED)
                ai.fleet.goHome(s, second);
            else if (d.job == Fleet.Job.TRANSIT && d.island == op.island)
                all = false;
        }
        if (op.ships.isEmpty()) {
            op.state = State.DONE;
            return;
        }
        // rebeach: the enemy moved to our beach while we sailed (a beach is picked minutes before we get there):
        // land at a quieter one, while no ship has docked yet
        if (ai.params.rebeach && op.kind == Kind.ASSAULT && op.landed_at == Integer.MAX_VALUE && second % 10 == 0) {
            float now = beachDanger(ai.intel, op.island, op.bx, op.by);
            if (now >= 125f) {
                int[] nb = beachhead(ai.intel, op.island, op.tx, op.ty);
                if (nb != null && beachDanger(ai.intel, op.island, nb[0], nb[1]) < now / 3f
                        && Geo.dist2(nb[0], nb[1], op.bx, op.by) > 30 * 30) {
                    op.bx = nb[0];
                    op.by = nb[1];
                    for (int k = 0; k < op.ships.size(); k++) {
                        int[] cell = ai.fleet.spacedShore(op.island, op.bx, op.by, k);
                        ai.fleet.goTo(op.ships.get(k), op.island, cell[0], cell[1], second);
                    }
                    ai.log.count("rebeach");
                    ai.log.log("CAMP",
                            () -> "op " + op.id + " lands at " + nb[0] + "," + nb[1] + " instead (danger " + now + ") t" + second);
                }
            }
        }
        // sea routes round the big islands can take five minutes: wait while the ships make headway
        if (all || (!moving && second - op.since > 60) || second - op.since > 900) {
            op.state = State.LANDING;
            op.since = second;
            ai.log.log("CAMP", () -> "op " + op.id + " landing t" + second);
        }
    }

    /**
     * bombard (seconds): a ship docked at the beachhead keeps its warriors aboard while enemy warriors are near the
     * shore. On the upper deck they throw from 10 cells further (and a stun does not reach them), and units come off
     * a ship one at a time: an enemy met on the beach is better shot from the deck than landed into. Returns whether
     * the ship holds its fire position this round (queued landings are cancelled).
     */
    private boolean holdFire(@NonNull Intel in, @NonNull Ship s, @NonNull Op op, int second) {
        if (ai.params.bombard <= 0 || second - op.landed_at > ai.params.bombard)
            return false;
        int near = 0;
        for (Unit e : in.enemy_warriors)
            if (e.getIslandId() == op.island && Geo.dist2(e.getGridX(), e.getGridY(), s.getGridX(),
                    s.getGridY()) <= 30 * 30)
                near++;
        if (near < 3)
            return false;
        ai.fleet.cancelDeploys(s);
        ai.log.count("bombard");
        return true;
    }

    private void landing(@NonNull Op op, int second) {
        boolean empty = true;
        boolean holding = false;
        int aboard = 0;
        for (Ship s : op.ships) {
            if (Fleet.dockedAt(s) != op.island)
                continue;
            aboard += Fleet.warriorsAboard(s);
            if (holdFire(ai.intel, s, op, second)) {
                empty = false;
                holding = true;
                continue;
            }
            if (Fleet.warriorsAboard(s) > 0) {
                ai.fleet.landWarriors(s);
                empty = false;
            }
            if (s.getShipHR().hasChieftain()) {
                me.trainChieftain(s, true);
                empty = false;
            }
        }
        if (ai.params.unload_wait > 0) {
            // unload_wait: units leave one at a time (an iron warrior 1.5 s), so a packed ship takes over a minute:
            // the ships stay while the warriors still come off (they went home after 30 s with two thirds aboard)
            if (op.unloaded_at < 0 || aboard < op.last_aboard || holding)
                op.unloaded_at = second;
            op.last_aboard = Math.min(op.last_aboard, aboard);
            if (!empty && second - op.unloaded_at < ai.params.unload_wait && second - op.since < 300)
                return;
        } else if (!empty && second - op.since < 30 + Math.max(0, ai.params.bombard))
            return;
        if (aboard > 0) {
            int a = aboard;
            ai.log.log("CAMP", () -> "op " + op.id + " ships leave with " + a + " warriors aboard t" + second);
            ai.log.count("unlanded");
        }
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
        for (Unit u : in.enemy_units)
            if (u.getIslandId() == op.island) {
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

    /** Whether an operation is still bringing warriors ashore on the island (the landed wait for the rest). */
    boolean landingOn(int island) {
        for (Op op : ops)
            if (op.island == island && op.kind == Kind.ASSAULT && (op.state == State.SAIL || op.state == State.LANDING))
                return true;
        return false;
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
