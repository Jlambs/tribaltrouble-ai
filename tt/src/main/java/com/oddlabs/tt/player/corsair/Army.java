package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Our warriors on land. They leave the armory as weapons come and man the towers. At home they defend what is near
 * our buildings and wait by the dock; an assault group (on foot against an enemy on our island, or landed by ships
 * on another) advances as one body towards its target and fights whatever comes within reach of it, a few warriors
 * per enemy.
 */
final class Army {
    /** A body of warriors with one target. */
    static final class Group {
        final int id;
        final int island;
        int tx;
        int ty;
        final List<@NonNull Unit> members = new ArrayList<>();
        int since;

        Group(int id, int island, int tx, int ty) {
            this.id = id;
            this.island = island;
            this.tx = tx;
            this.ty = ty;
        }
    }

    private final @NonNull CorsairAI ai;
    private final @NonNull Player me;
    private boolean home_threat;
    final List<@NonNull Group> groups = new ArrayList<>();
    private int next_group = 1;
    /** Per enemy, our warriors ordered at it this round. */
    private final Map<@NonNull Unit, Integer> attackers = new LinkedHashMap<>();

    Army(@NonNull CorsairAI ai) {
        this.ai = ai;
        this.me = ai.me();
    }

    boolean homeThreatened() {
        return home_threat;
    }

    void think(@NonNull Intel in, int second) {
        Colony c = ai.colony;
        if (!c.founded())
            return;
        deploy(in);
        manTowers(in);
        attackers.clear();
        // drop the dead from groups, and groups without members
        for (Group g : groups)
            g.members.removeIf(u -> u.isDead() || u.isMounted() || u.getIslandId() != g.island);
        groups.removeIf(g -> g.members.isEmpty());
        // warriors on foreign islands join that island's group
        for (Unit w : in.warriors) {
            if (ai.reserved(w) || inGroup(w) || w.getIslandId() == c.island || w.getIslandId() == 0)
                continue;
            Group g = groupOn(w.getIslandId());
            if (g == null) {
                int[] t = targetOn(in, w.getIslandId(), w.getGridX(), w.getGridY());
                g = new Group(next_group++, w.getIslandId(), t[0], t[1]);
                g.since = second;
                groups.add(g);
            }
            g.members.add(w);
        }
        home_threat = false;
        defend(in, second);
        for (Group g : groups)
            lead(in, g, second);
    }

    private boolean inGroup(@NonNull Unit w) {
        for (Group g : groups)
            if (g.members.contains(w))
                return true;
        return false;
    }

    private @Nullable Group groupOn(int island) {
        for (Group g : groups)
            if (g.island == island)
                return g;
        return null;
    }

    /** Starts an assault on foot with the given warriors. */
    void assault(@NonNull List<@NonNull Unit> warriors, int island, int tx, int ty, int second) {
        Group g = new Group(next_group++, island, tx, ty);
        g.since = second;
        for (Unit w : warriors)
            if (!inGroup(w))
                g.members.add(w);
        groups.add(g);
    }

    boolean assaulting(int island) {
        return groupOn(island) != null;
    }

    /** Lets warriors out of the armory as weapons come. */
    private void deploy(@NonNull Intel in) {
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
                if (w.getIslandId() != t.getIslandId() || ai.reserved(w) || inGroup(w))
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

    /** Our free warriors on the home island (not boarding, not in a group), nearest the dock first. */
    @NonNull
    List<@NonNull Unit> freeWarriorsAtHome(@NonNull Intel in) {
        List<Unit> out = new ArrayList<>();
        for (Unit w : in.warriors)
            if (w.getIslandId() == ai.colony.island && !ai.reserved(w) && !inGroup(w) && Campaign.isFree(w))
                out.add(w);
        int[] dock = ai.fleet.homeDock();
        Colony.sortByDistance(out, dock[0], dock[1]);
        return out;
    }

    /** Whether an enemy unit stands within r cells of one of our buildings on the home island. */
    private boolean nearOurBase(@NonNull Intel in, @NonNull Unit u, int r) {
        long r2 = (long) r * r;
        Colony c = ai.colony;
        if (Geo.dist2(u.getGridX(), u.getGridY(), c.ax, c.ay) <= r2)
            return true;
        for (List<Building> list : List.of(in.quarters, in.armories, in.towers, in.sites))
            for (Building b : list)
                if (b.getIslandId() == c.island && Geo.dist2(u.getGridX(), u.getGridY(), b.getGridX(),
                        b.getGridY()) <= r2)
                    return true;
        return false;
    }

    /** The home warriors not in a group fight enemies near our buildings, else wait between base and dock. */
    private void defend(@NonNull Intel in, int second) {
        Colony c = ai.colony;
        List<Unit> mine = new ArrayList<>();
        for (Unit w : in.warriors)
            if (w.getIslandId() == c.island && !ai.reserved(w) && !inGroup(w))
                mine.add(w);
        List<Unit> threats = new ArrayList<>();
        for (Unit u : in.enemy_units)
            if (u.getIslandId() == c.island && nearOurBase(in, u, 45))
                threats.add(u);
        float theirs = 0;
        for (Unit u : threats)
            if (u.isWarrior() || u.isChieftain())
                theirs += Intel.value(u);
        home_threat = theirs > 2;
        if (!threats.isEmpty()) {
            engage(mine, threats, 60);
            return;
        }
        int[] dock = ai.fleet.homeDock();
        gather(mine, (c.ax * 2 + dock[0]) / 3, (c.ay * 2 + dock[1]) / 3, 14);
    }

    /** Idle warriors farther than r from the point walk to it. */
    private void gather(@NonNull List<@NonNull Unit> warriors, int x, int y, int r) {
        List<Unit> far = new ArrayList<>();
        for (Unit w : warriors)
            if (w.getPrimaryController() instanceof IdleController && Geo.dist2(w.getGridX(), w.getGridY(), x,
                    y) > r * r)
                far.add(w);
        if (!far.isEmpty())
            me.setLandscapeTarget(far.toArray(new Unit[0]), x, y, Action.MOVE, false);
    }

    /** A target point on an island: its enemy building nearest (x, y), or the island's centre. */
    int @NonNull [] targetOn(@NonNull Intel in, int island, int x, int y) {
        Building b = nearestBuilding(in.enemy_buildings, island, x, y);
        if (b != null)
            return new int[]{b.getGridX(), b.getGridY()};
        Geo.Isle isle = ai.geo.isle(island);
        return isle == null ? new int[]{x, y} : new int[]{isle.cx, isle.cy};
    }

    /**
     * One assault group: fight what is within reach of its body, else close up and advance on the target, and at the
     * target attack its buildings, towers first.
     */
    private void lead(@NonNull Intel in, @NonNull Group g, int second) {
        int n = g.members.size();
        long sx = 0, sy = 0;
        for (Unit w : g.members) {
            sx += w.getGridX();
            sy += w.getGridY();
        }
        int cx = (int) (sx / n), cy = (int) (sy / n);
        // threats near the body
        List<Unit> threats = new ArrayList<>();
        for (Unit u : in.enemy_units) {
            if (u.getIslandId() != g.island)
                continue;
            if (Geo.dist2(u.getGridX(), u.getGridY(), cx, cy) <= 28 * 28) {
                threats.add(u);
                continue;
            }
            for (Unit w : g.members)
                if (Geo.dist2(u.getGridX(), u.getGridY(), w.getGridX(), w.getGridY()) <= 12 * 12) {
                    threats.add(u);
                    break;
                }
        }
        if (!threats.isEmpty()) {
            engage(g.members, threats, 40);
            return;
        }
        // the target: re-pick when its building is gone
        Building at = buildingAt(in, g.island, g.tx, g.ty);
        if (at == null) {
            Building next = nearestBuilding(in.enemy_buildings, g.island, cx, cy);
            if (next == null) {
                // nothing left on the island: hold the centre of the body
                return;
            }
            g.tx = next.getGridX();
            g.ty = next.getGridY();
            at = next;
        }
        int d = Geo.dist(cx, cy, g.tx, g.ty);
        if (d <= 14) {
            // at the target: every warrior on a building, towers first
            List<Building> near = new ArrayList<>();
            for (Building b : in.enemy_buildings)
                if (b.getIslandId() == g.island && Geo.dist2(b.getGridX(), b.getGridY(), cx, cy) <= 22 * 22)
                    near.add(b);
            for (Unit w : g.members) {
                if (busy(w))
                    continue;
                Building b = nearestBuilding(near, g.island, w.getGridX(), w.getGridY());
                if (b == null)
                    b = at;
                me.setTarget(Selectable.newArray(w), b, Action.ATTACK, true);
            }
            return;
        }
        // advance: stragglers close up on the body, the rest step forward together
        int step = Math.min(d, 14);
        int px = cx + (g.tx - cx) * step / Math.max(1, d);
        int py = cy + (g.ty - cy) * step / Math.max(1, d);
        List<Unit> stragglers = new ArrayList<>();
        List<Unit> body = new ArrayList<>();
        for (Unit w : g.members) {
            if (busy(w))
                continue;
            if (Geo.dist2(w.getGridX(), w.getGridY(), cx, cy) > 16 * 16)
                stragglers.add(w);
            else
                body.add(w);
        }
        if (!stragglers.isEmpty())
            me.setLandscapeTarget(stragglers.toArray(new Unit[0]), cx, cy, Action.MOVE, false);
        if (!body.isEmpty() && second % 2 == 0)
            me.setLandscapeTarget(body.toArray(new Unit[0]), px, py, Action.ATTACK, true);
    }

    /**
     * Every warrior not already on a live threat goes for the nearest enemy warrior within r cells with fewer than
     * four of ours on it, else the nearest threat.
     */
    private void engage(@NonNull List<@NonNull Unit> warriors, @NonNull List<@NonNull Unit> threats, int r) {
        List<Unit> fighters = new ArrayList<>();
        for (Unit e : threats)
            if (e.isWarrior() || e.isChieftain())
                fighters.add(e);
        long r2 = (long) r * r;
        for (Unit w : warriors) {
            Unit current = currentTarget(w);
            if (current != null && !current.isDead()) {
                attackers.merge(current, 1, Integer::sum);
                continue;
            }
            Unit best = null;
            long bd = r2 + 1;
            for (Unit e : fighters) {
                if (e.isDead() || attackers.getOrDefault(e, 0) >= 4)
                    continue;
                long d = Geo.dist2(e.getGridX(), e.getGridY(), w.getGridX(), w.getGridY());
                if (d < bd) {
                    bd = d;
                    best = e;
                }
            }
            if (best == null)
                for (Unit e : threats) {
                    if (e.isDead())
                        continue;
                    long d = Geo.dist2(e.getGridX(), e.getGridY(), w.getGridX(), w.getGridY());
                    if (d < bd) {
                        bd = d;
                        best = e;
                    }
                }
            if (best != null) {
                attackers.merge(best, 1, Integer::sum);
                me.setTarget(Selectable.newArray(w), best, Action.ATTACK, true);
            }
        }
    }

    private static @Nullable Unit currentTarget(@NonNull Unit w) {
        var c = w.getPrimaryController();
        if (c instanceof HuntController h && h.getTarget() instanceof Unit u)
            return u;
        if (c instanceof AttackController a && a.getTarget() instanceof Unit u)
            return u;
        return null;
    }

    private static boolean busy(@NonNull Unit w) {
        var c = w.getPrimaryController();
        if (c instanceof HuntController h)
            return h.getTarget() != null && !h.getTarget().isDead();
        if (c instanceof AttackController a)
            return a.getTarget() != null && !a.getTarget().isDead();
        return false;
    }

    private static @Nullable Building buildingAt(@NonNull Intel in, int island, int x, int y) {
        for (Building b : in.enemy_buildings)
            if (b.getIslandId() == island && b.getGridX() == x && b.getGridY() == y && !b.isDead())
                return b;
        return null;
    }

    static @Nullable Building nearestBuilding(@NonNull List<@NonNull Building> list, int island, int x, int y) {
        Building best = null;
        long bd = Long.MAX_VALUE;
        for (Building b : list) {
            if (b.isDead() || b.getIslandId() != island)
                continue;
            long d = Geo.dist2(b.getGridX(), b.getGridY(), x, y);
            if (Intel.isTower(b))
                d = d / 2; // towers count as nearer: they kill while standing
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }
}
