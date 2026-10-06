package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Our warriors on land: they defend the home island, man the towers, and gather into expeditions that ships carry to
 * enemy islands, where they attack until nothing of the enemy is left there.
 */
final class Army {
    private final @NonNull CorsairAI ai;
    private final @NonNull Player me;
    private int expeditions;
    private int last_launch = -1000;

    Army(@NonNull CorsairAI ai) {
        this.ai = ai;
        this.me = ai.me();
    }

    void think(@NonNull Intel in, int second) {
        Colony c = ai.colony;
        if (!c.founded())
            return;
        deploy(in, second);
        manTowers(in);
        // warriors by island
        Map<Integer, List<Unit>> by_island = new LinkedHashMap<>();
        for (Unit w : in.warriors) {
            if (ai.reserved(w))
                continue;
            by_island.computeIfAbsent(w.getIslandId(), k -> new ArrayList<>()).add(w);
        }
        for (Map.Entry<Integer, List<Unit>> e : by_island.entrySet()) {
            int island = e.getKey();
            if (island == 0)
                continue;
            if (island == c.island)
                home(in, e.getValue(), second);
            else
                abroad(in, island, e.getValue(), second);
        }
    }

    /** Lets warriors out of the armory as weapons come, while the unit cap allows. */
    private void deploy(@NonNull Intel in, int second) {
        int stock = 0;
        for (Building a : in.armories)
            stock += Colony.weapons(a);
        if (stock > 0)
            ai.colony.deployWarriors(in, stock);
    }

    private void manTowers(@NonNull Intel in) {
        for (Building t : in.towers) {
            if (t.getUnitContainer() == null || t.getUnitContainer().getNumSupplies() > 0)
                continue;
            Unit best = null;
            long bd = Long.MAX_VALUE;
            for (Unit w : in.warriors) {
                if (w.getIslandId() != t.getIslandId() || ai.reserved(w))
                    continue;
                long d = Geo.dist2(w.getGridX(), w.getGridY(), t.getGridX(), t.getGridY());
                if (d < bd && d < 60 * 60) {
                    bd = d;
                    best = w;
                }
            }
            if (best != null) {
                me.setTarget(Selectable.newArray(best), t, Action.DEFAULT, false);
                ai.reserve(best, 20);
            }
        }
    }

    /** Defends the home island and launches expeditions from it. */
    private void home(@NonNull Intel in, @NonNull List<@NonNull Unit> warriors, int second) {
        Colony c = ai.colony;
        List<Unit> enemies = new ArrayList<>();
        for (Unit u : in.enemy_units)
            if (u.getIslandId() == c.island)
                enemies.add(u);
        List<Building> enemy_buildings = new ArrayList<>();
        for (Building b : in.enemy_buildings)
            if (b.getIslandId() == c.island)
                enemy_buildings.add(b);
        float ours = 0;
        for (Unit w : warriors)
            ours += Intel.value(w);
        float theirs = in.enemyStrengthOn(c.island);
        if (!enemies.isEmpty() || !enemy_buildings.isEmpty()) {
            // fight on our island when we are not much weaker, or when they are in the base
            boolean in_base = false;
            for (Unit u : enemies)
                if (Geo.dist2(u.getGridX(), u.getGridY(), c.ax, c.ay) < 45 * 45)
                    in_base = true;
            if (in_base || ours >= theirs * ai.params.attack_ratio) {
                attack(warriors, enemies, enemy_buildings, c.ax, c.ay);
                return;
            }
            gather(warriors, c.ax, c.ay);
            return;
        }
        // quiet at home: an expedition when the army is big enough
        int ready = 0;
        for (Unit w : warriors)
            if (isFree(w))
                ready++;
        if (ready >= ai.params.expedition_size && second - last_launch > 30) {
            if (launch(in, warriors, second))
                return;
        }
        gather(warriors, c.ax, c.ay);
    }

    private static boolean isFree(@NonNull Unit w) {
        return w.getPrimaryController() instanceof IdleController || w.getPrimaryController() instanceof WalkController;
    }

    /** Warriors not busy stand near the base. */
    private void gather(@NonNull List<@NonNull Unit> warriors, int x, int y) {
        List<Unit> far = new ArrayList<>();
        for (Unit w : warriors)
            if (w.getPrimaryController() instanceof IdleController && Geo.dist2(w.getGridX(), w.getGridY(), x,
                    y) > 20 * 20)
                far.add(w);
        if (!far.isEmpty())
            me.setLandscapeTarget(far.toArray(new Unit[0]), x, y, Action.MOVE, false);
    }

    /** Sends each warrior at the nearest enemy unit, or else the nearest enemy building. */
    private void attack(@NonNull List<@NonNull Unit> warriors, @NonNull List<@NonNull Unit> enemies,
            @NonNull List<@NonNull Building> buildings, int x, int y) {
        for (Unit w : warriors) {
            if (w.getPrimaryController() instanceof HuntController h && h.getTarget() != null
                    && !h.getTarget().isDead())
                continue; // already on someone
            Selectable<?> target = nearest(enemies, w.getGridX(), w.getGridY(), 60);
            if (target == null)
                target = nearestBuilding(buildings, w.getGridX(), w.getGridY());
            if (target == null)
                target = nearest(enemies, w.getGridX(), w.getGridY(), 10000);
            if (target != null)
                me.setTarget(Selectable.newArray(w), target, Action.ATTACK, true);
        }
    }

    private static @Nullable Unit nearest(@NonNull List<@NonNull Unit> list, int x, int y, int max) {
        Unit best = null;
        long bd = (long) max * max;
        for (Unit u : list) {
            if (u.isDead())
                continue;
            long d = Geo.dist2(u.getGridX(), u.getGridY(), x, y);
            if (d < bd) {
                bd = d;
                best = u;
            }
        }
        return best;
    }

    private static @Nullable Building nearestBuilding(@NonNull List<@NonNull Building> list, int x, int y) {
        Building best = null;
        long bd = Long.MAX_VALUE;
        for (Building b : list) {
            if (b.isDead())
                continue;
            long d = Geo.dist2(b.getGridX(), b.getGridY(), x, y);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    /** Warriors on an island not our home: attack whatever enemy is there. */
    private void abroad(@NonNull Intel in, int island, @NonNull List<@NonNull Unit> warriors, int second) {
        List<Unit> enemies = new ArrayList<>();
        for (Unit u : in.enemy_units)
            if (u.getIslandId() == island)
                enemies.add(u);
        List<Building> buildings = new ArrayList<>();
        for (Building b : in.enemy_buildings)
            if (b.getIslandId() == island)
                buildings.add(b);
        if (enemies.isEmpty() && buildings.isEmpty()) {
            // nothing left here: wait by the shore for a ship home (v0: they stay)
            return;
        }
        int cx = 0, cy = 0;
        for (Unit w : warriors) {
            cx += w.getGridX();
            cy += w.getGridY();
        }
        cx /= warriors.size();
        cy /= warriors.size();
        attack(warriors, enemies, buildings, cx, cy);
    }

    /** Picks an enemy island and ships the free warriors there. */
    private boolean launch(@NonNull Intel in, @NonNull List<@NonNull Unit> warriors, int second) {
        Colony c = ai.colony;
        Ship ship = ai.fleet.freeFerry(c.island);
        if (ship == null)
            return false;
        // target: the enemy building nearest our base on another island
        Building target = null;
        long bd = Long.MAX_VALUE;
        for (Building b : in.enemy_buildings) {
            if (b.getIslandId() == c.island || b.getIslandId() == 0)
                continue;
            long d = Geo.dist2(b.getGridX(), b.getGridY(), c.ax, c.ay);
            if (d < bd) {
                bd = d;
                target = b;
            }
        }
        if (target == null)
            return false;
        List<Unit> group = new ArrayList<>();
        for (Unit w : warriors)
            if (isFree(w) && group.size() < 26)
                group.add(w);
        // rowers: peons already aboard row too
        int have_rowers = ship.getShipHR().countPeons();
        List<Unit> rowers = new ArrayList<>();
        if (have_rowers < ai.params.rowers) {
            List<Unit> peons = new ArrayList<>();
            for (Unit u : in.peons)
                if (u.getIslandId() == c.island && !ai.reserved(u)
                        && (u.getPrimaryController() instanceof IdleController
                                || u.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.GatherController<?>))
                    peons.add(u);
            Colony.sortByDistance(peons, ship.getGridX(), ship.getGridY());
            for (Unit u : peons)
                if (rowers.size() < ai.params.rowers - have_rowers)
                    rowers.add(u);
        }
        expeditions++;
        last_launch = second;
        Building tgt = target;
        ai.log.log("ARMY",
                () -> "expedition " + expeditions + ": " + group.size() + " warriors to isle " + tgt.getIslandId() + " at " + tgt.getGridX() + "," + tgt.getGridY());
        ai.fleet.startFerry(ship, group, rowers, target.getIslandId(), target.getGridX(), target.getGridY(),
                expeditions, second);
        return true;
    }
}
