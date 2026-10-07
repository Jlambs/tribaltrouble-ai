package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Ship;
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
        int checked;
        /** Broke off (abort_ratio): dissolved next round. */
        boolean aborted;

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
        upgrade(in);
        manTowers(in);
        attackers.clear();
        // drop the dead from groups, and groups without members
        for (Group g : groups)
            g.members.removeIf(u -> u.isDead() || u.isMounted() || u.getIslandId() != g.island);
        groups.removeIf(g -> g.members.isEmpty() || !enemyOn(in, g.island) || g.aborted);
        // warriors on foreign islands join that island's group, while there is an enemy there to fight
        for (Unit w : in.warriors) {
            if (ai.reserved(w) || inGroup(w) || w.getIslandId() == c.island || w.getIslandId() == 0)
                continue;
            if (!enemyOn(in, w.getIslandId()))
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

    /** Whether any enemy unit, building or docked ship is on the island. */
    static boolean enemyOn(@NonNull Intel in, int island) {
        for (Unit u : in.enemy_units)
            if (u.getIslandId() == island)
                return true;
        for (Building b : in.enemy_buildings)
            if (b.getIslandId() == island)
                return true;
        return !in.enemyShipsDockedAt(island).isEmpty();
    }

    boolean inGroup(@NonNull Unit w) {
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

    /**
     * upgrade: an armory takes any unit back in as a worker (WorkerUnitContainer, the weapon is lost). At the unit cap
     * an idle rock warrior at home is worth less than the iron warrior its slot could hold: while iron axes wait in
     * stock, as many rock warriors walk in, to come out with them.
     */
    private void upgrade(@NonNull Intel in) {
        if (!ai.params.upgrade || in.armories.isEmpty() || home_threat)
            return;
        if (in.units() < me.getWorld().getMaxUnitCount() - 4)
            return;
        Building armory = in.armories.getFirst();
        int axes = armory.getSupplyContainer(
                com.oddlabs.tt.model.weapon.IronAxeWeapon.class).getNumSupplies() + armory.getSupplyContainer(
                        com.oddlabs.tt.model.weapon.RubberAxeWeapon.class).getNumSupplies();
        if (axes <= 0)
            return;
        List<Unit> rocks = new ArrayList<>();
        for (Unit w : in.warriors)
            if (Intel.kind(w) == Intel.Kind.ROCK && w.getIslandId() == ai.colony.island && !ai.reserved(w)
                    && !inGroup(w) && w.getPrimaryController() instanceof IdleController)
                rocks.add(w);
        Colony.sortByDistance(rocks, armory.getGridX(), armory.getGridY());
        List<Unit> go = new ArrayList<>(rocks.subList(0, Math.min(axes, rocks.size())));
        if (go.isEmpty())
            return;
        for (Unit w : go)
            ai.reserve(w, 30);
        ai.order(go, armory, Action.MOVE, false);
        ai.log.count("upgrade");
    }

    /**
     * Lets warriors out of the armory as weapons come, keeping enough peons to work: a warrior is a peon of the armory
     * given a weapon, and at the unit cap no more peons are born.
     */
    private void deploy(@NonNull Intel in) {
        int stock = 0;
        for (Building a : in.armories)
            stock += Colony.weapons(a);
        int peons = in.peons.size() + in.peonsInside();
        int spare = home_threat ? stock : Math.min(stock, peons - ai.params.min_peons);
        if (spare > 0)
            ai.colony.deployWarriors(in, spare);
    }

    private void manTowers(@NonNull Intel in) {
        if (ai.params.elite_trips > 0)
            swapGarrisons(in);
        for (Building t : in.towers) {
            if (t.getUnitContainer() == null || t.getUnitContainer().getNumSupplies() > 0)
                continue;
            Unit best = null;
            long bd = Long.MAX_VALUE;
            for (Unit w : in.warriors) {
                if (w.getIslandId() != t.getIslandId() || ai.reserved(w) || inGroup(w))
                    continue;
                long d = Geo.dist2(w.getGridX(), w.getGridY(), t.getGridX(), t.getGridY());
                if (d >= 60 * 60)
                    continue;
                // drilled_garrison: the most drilled warrior near, its range is the tower's under fire control
                if (ai.params.drilled_garrison)
                    d -= (long) (Ratchet.bonus(w) * 400);
                if (d < bd) {
                    bd = d;
                    best = w;
                }
            }
            if (best != null) {
                ai.order(best, t, Action.DEFAULT, false);
                ai.reserve(best, 20);
            }
        }
    }

    /**
     * elite_trips: a tower whose garrison is far less drilled than a free warrior near it lets the garrison out, so
     * the better one goes in (manTowers prefers the most drilled), while no enemy is near the tower.
     */
    private void swapGarrisons(@NonNull Intel in) {
        for (Building t : in.towers) {
            Unit g = Towers.gunner(t);
            if (g == null)
                continue;
            boolean quiet = true;
            for (Unit e : in.enemy_units)
                if (e.getIslandId() == t.getIslandId() && Geo.dist2(e.getGridX(), e.getGridY(), t.getGridX(),
                        t.getGridY()) < 50 * 50) {
                            quiet = false;
                            break;
                        }
            if (!quiet)
                continue;
            float best = 0;
            for (Unit w : in.warriors)
                if (!ai.reserved(w) && !inGroup(w) && w.getIslandId() == t.getIslandId()
                        && Geo.dist2(w.getGridX(), w.getGridY(), t.getGridX(), t.getGridY()) < 40 * 40)
                    best = Math.max(best, Ratchet.bonus(w));
            if (best >= Ratchet.bonus(g) + 6f) {
                me.exitTower(t);
                ai.log.count("garrison_swap");
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
        if (!militia(in, mine, threats) && ai.params.shelter)
            shelter(in, threats);
        // an enemy ship docked at our island is landing troops: its hull is in reach from the shore, and each hit
        // may kill one aboard; sunk, it drowns them all
        List<Ship> docked = new ArrayList<>();
        if (ai.params.sink_docked)
            for (Ship s : in.enemyShipsDockedAt(c.island))
                if (Geo.dist2(s.getGridX(), s.getGridY(), c.ax, c.ay) <= 90 * 90)
                    docked.add(s);
        if (!docked.isEmpty()) {
            home_threat = true;
            Ship s = docked.getFirst();
            List<Unit> sinkers = new ArrayList<>();
            for (Unit w : mine)
                if (!busy(w) && (threats.isEmpty() || sinkers.size() * 2 < mine.size()))
                    sinkers.add(w);
            for (Unit w : sinkers)
                ai.order(w, s, Action.ATTACK, true);
            mine.removeAll(sinkers);
        }
        if (!threats.isEmpty()) {
            engage(mine, threats, 60);
            return;
        }
        if (!docked.isEmpty())
            return;
        int[] dock = ai.fleet.homeDock();
        gather(mine, (c.ax * 2 + dock[0]) / 3, (c.ay * 2 + dock[1]) / 3, 14);
    }

    /**
     * Peon militia: an early raid (enemy units in the base while our warriors cannot match them) is met by the peons
     * near it, a few per raider, when they outnumber it well. Raids by peons on builders are the usual case; against
     * warriors, peons are worth little and stay at work.
     */
    /**
     * shelter: peons near raiders that no militia meets go into our nearest quarters or armory (an early raid
     * once killed 190 of our peons, gathering or fighting, in six minutes).
     */
    private void shelter(@NonNull Intel in, @NonNull List<@NonNull Unit> threats) {
        List<Unit> raiders = new ArrayList<>();
        for (Unit u : threats)
            if (!u.isDead() && (u.isWarrior() || u.isChieftain()))
                raiders.add(u);
        if (raiders.isEmpty())
            return;
        List<Building> refuges = new ArrayList<>(ai.colony.local(in.quarters));
        refuges.addAll(ai.colony.local(in.armories));
        if (refuges.isEmpty())
            return;
        int n = 0;
        for (Unit p : in.peons) {
            if (p.isDead() || ai.reserved(p) || p.getIslandId() != ai.colony.island)
                continue;
            boolean near = false;
            for (Unit r : raiders)
                if (Geo.dist2(p.getGridX(), p.getGridY(), r.getGridX(), r.getGridY()) <= 22 * 22) {
                    near = true;
                    break;
                }
            if (!near)
                continue;
            Building best = null;
            long bd = Long.MAX_VALUE;
            for (Building b : refuges) {
                long d = Geo.dist2(p.getGridX(), p.getGridY(), b.getGridX(), b.getGridY());
                if (d < bd) {
                    bd = d;
                    best = b;
                }
            }
            if (best != null) {
                ai.order(p, best, Action.MOVE, false);
                ai.reserve(p, 10);
                n++;
            }
        }
        if (n > 0)
            ai.log.count("shelter");
    }

    /** Mean drill bonus of the warriors in a list (peons and the dead left out). */
    private static float warriorBonus(@NonNull List<@NonNull Unit> units) {
        float sum = 0;
        int n = 0;
        for (Unit u : units)
            if (!u.isDead() && u.isWarrior()) {
                sum += Ratchet.bonus(u);
                n++;
            }
        return n == 0 ? 0f : sum / n;
    }

    private boolean militia(@NonNull Intel in, @NonNull List<@NonNull Unit> warriors,
            @NonNull List<@NonNull Unit> threats) {
        if (!ai.params.militia || threats.isEmpty())
            return false;
        float ours = 0, theirs = 0;
        for (Unit w : warriors)
            ours += Intel.value(w);
        List<Unit> raiders = new ArrayList<>();
        for (Unit u : threats)
            if (nearOurBase(in, u, 25)) {
                raiders.add(u);
                theirs += Intel.value(u);
            }
        if (raiders.isEmpty() || ours >= theirs * 1.5f)
            return false;
        // militia_smart: peons only meet early raids by undrilled warriors; drilled ones kite them down from
        // range (an enemy base next to ours once cost 220 peons in six minutes)
        if (ai.params.militia_smart && (ai.now() >= 900 || warriorBonus(raiders) >= 2f))
            return false;
        // peons fight at ~0.2 of an iron warrior: they need numbers
        float peon_value = 0;
        List<Unit> near = new ArrayList<>();
        for (Unit p : in.peons) {
            if (ai.reserved(p) || p.getIslandId() != ai.colony.island)
                continue;
            for (Unit r : raiders)
                if (Geo.dist2(p.getGridX(), p.getGridY(), r.getGridX(), r.getGridY()) <= 30 * 30) {
                    near.add(p);
                    peon_value += Intel.value(p);
                    break;
                }
        }
        if (peon_value + ours < 1.5f * theirs)
            return false;
        Map<Unit, Integer> on = new LinkedHashMap<>();
        for (Unit p : near) {
            Unit best = null;
            long bd = Long.MAX_VALUE;
            for (Unit r : raiders) {
                if (r.isDead() || on.getOrDefault(r, 0) >= (r.isWarrior() ? 6 : 3))
                    continue;
                long d = Geo.dist2(p.getGridX(), p.getGridY(), r.getGridX(), r.getGridY());
                if (d < bd) {
                    bd = d;
                    best = r;
                }
            }
            if (best == null)
                break;
            on.merge(best, 1, Integer::sum);
            ai.order(p, best, Action.ATTACK, true);
            ai.reserve(p, 8);
        }
        ai.log.count("militia");
        return true;
    }

    /** Idle warriors farther than r from the point walk to it. */
    private void gather(@NonNull List<@NonNull Unit> warriors, int x, int y, int r) {
        List<Unit> far = new ArrayList<>();
        for (Unit w : warriors)
            if (w.getPrimaryController() instanceof IdleController && Geo.dist2(w.getGridX(), w.getGridY(), x,
                    y) > r * r)
                far.add(w);
        if (!far.isEmpty())
            ai.move(far, x, y, Action.MOVE, false);
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
        int reach = 28 + (int) Ratchet.meanBonus(g.members);
        // threats near the body
        List<Unit> threats = new ArrayList<>();
        for (Unit u : in.enemy_units) {
            if (u.getIslandId() != g.island)
                continue;
            if (Geo.dist2(u.getGridX(), u.getGridY(), cx, cy) <= (long) reach * reach) {
                threats.add(u);
                continue;
            }
            for (Unit w : g.members)
                if (Geo.dist2(u.getGridX(), u.getGridY(), w.getGridX(), w.getGridY()) <= 12 * 12) {
                    threats.add(u);
                    break;
                }
        }
        if (second % 10 == 0) {
            float theirs = 0;
            for (Unit u : threats)
                if (u.isWarrior() || u.isChieftain())
                    theirs += Intel.value(u);
            float st = theirs;
            int nt = threats.size();
            ai.log.log("GROUP",
                    () -> "g" + g.id + " isle " + g.island + " n " + n + " at " + cx + "," + cy + " target " + g.tx + "," + g.ty + " threats " + nt + " (" + st + ") range+" + String.format(
                            "%.1f", Ratchet.meanBonus(g.members)));
        }
        if (ai.params.snipe_towers)
            snipeTowers(in, g, cx, cy, reach);
        // tower_rush: a garrisoned tower that reaches the body shoots it for as long as it stays; a few enemy
        // warriors near kept a 130-warrior body kiting in front of one for six minutes (218 lost, 21 killed). With
        // little else around, the whole body goes for the tower
        if (ai.params.tower_rush && towerRush(in, g, threats))
            return;
        List<Unit> movers = g.members;
        if (!threats.isEmpty()) {
            // siege: with no enemy warrior worth the name near, peons (gatherers walking by, or coming out of their
            // buildings) would keep the whole body busy for ever: a 195-warrior march sat 137 cells short of a base
            // for hours, and another killed 7,000 peons at one without razing a building. A quarter takes the
            // peons; near buildings the rest raze them (towers first, then quarters, which breed), else they advance.
            if (ai.params.siege_peons && peonsOnly(threats, g)) {
                if (siege(in, g, threats, cx, cy))
                    return;
                movers = new ArrayList<>(g.members);
                int hunters = movers.size() / 4;
                List<Unit> hunt = new ArrayList<>(movers.subList(0, hunters));
                movers.subList(0, hunters).clear();
                engage(hunt, threats, 40);
                ai.log.count("siege_advance");
            } else {
                engage(g.members, threats, 40);
                return;
            }
        }
        // an enemy ship docked near the body: its crew dies with it, and its hull draws their fire
        for (Ship s : in.enemyShipsDockedAt(g.island)) {
            if (Geo.dist2(s.getGridX(), s.getGridY(), cx, cy) > (long) (reach + 20) * (reach + 20))
                continue;
            for (Unit w : movers)
                if (!busy(w))
                    ai.order(w, s, Action.ATTACK, true);
            return;
        }
        // the first ashore wait at the beach for the rest
        if (ai.campaign.landingOn(g.island) && second - g.since < 60)
            return;
        // stage: hold at the beachhead until the campaign releases the stage
        Campaign.Stage stage = ai.campaign.holding(g.island);
        if (stage != null) {
            gather(movers, stage.bx, stage.by, 12);
            return;
        }
        // the target: re-pick when its building is gone
        Building at = buildingAt(in, g.island, g.tx, g.ty);
        // soft_target: the buildings no garrisoned tower covers first (quarters and armory end their army); the
        // towers when nothing else is left
        if (ai.params.soft_target && second % 10 == 0 && !g.members.isEmpty()) {
            Building soft = softTarget(in, g, cx, cy);
            if (soft != null && soft != at) {
                g.tx = soft.getGridX();
                g.ty = soft.getGridY();
                at = soft;
                ai.log.count("soft_target");
            }
        }
        if (at == null) {
            Building next = nearestBuilding(in.enemy_buildings, g.island, cx, cy);
            if (next == null) {
                // no building left: go for the nearest enemy unit on the island, else hold
                Unit u = null;
                long bd = Long.MAX_VALUE;
                for (Unit e : in.enemy_units) {
                    if (e.getIslandId() != g.island)
                        continue;
                    long dd = Geo.dist2(e.getGridX(), e.getGridY(), cx, cy);
                    if (dd < bd) {
                        bd = dd;
                        u = e;
                    }
                }
                if (u == null) {
                    // nothing on land: a docked enemy ship anywhere on the island
                    List<Ship> docked = in.enemyShipsDockedAt(g.island);
                    if (!docked.isEmpty()) {
                        Ship s = docked.getFirst();
                        g.tx = s.getGridX();
                        g.ty = s.getGridY();
                        for (Unit w : movers)
                            if (!busy(w))
                                ai.order(w, s, Action.ATTACK, true);
                    }
                    return;
                }
                g.tx = u.getGridX();
                g.ty = u.getGridY();
                if (bd <= 14 * 14) {
                    for (Unit w : movers)
                        if (!busy(w))
                            ai.order(w, u, Action.ATTACK, true);
                    return;
                }
            } else {
                g.tx = next.getGridX();
                g.ty = next.getGridY();
                at = next;
            }
        }
        int d = Geo.dist(cx, cy, g.tx, g.ty);
        // abort_ratio: closing on the target, weigh the body against what defends the target (warriors within 50
        // cells of it, towers at four); outmatched, break off before contact: at home the warriors go back to the
        // defence, abroad they hold the beach
        if (ai.params.abort_ratio > 0 && d <= 70 && second - g.checked >= 5) {
            g.checked = second;
            float ours = 0;
            for (Unit w : movers)
                ours += Intel.value(w);
            float theirs = 0, their_bonus = 0;
            int nb = 0;
            for (Unit e : in.enemy_warriors)
                if (e.getIslandId() == g.island && Geo.dist2(e.getGridX(), e.getGridY(), g.tx, g.ty) <= 50 * 50) {
                    theirs += Intel.value(e);
                    their_bonus += Ratchet.bonus(e);
                    nb++;
                }
            // abort_edge: their warriors count for less the more ours outreach them (as in the launch rule)
            if (ai.params.abort_edge && nb > 0)
                theirs *= ai.campaign.rangeFactor(their_bonus / nb);
            for (Building b : in.enemy_buildings)
                if (Intel.isTower(b) && b.isComplete() && b.getIslandId() == g.island
                        && Geo.dist2(b.getGridX(), b.getGridY(), g.tx, g.ty) <= 40 * 40)
                    theirs += 4f;
            if (ours * ai.params.abort_ratio < theirs) {
                float o = ours, t = theirs;
                ai.log.log("GROUP",
                        () -> "g" + g.id + " breaks off: " + o + " against " + t + " near " + g.tx + "," + g.ty + " t" + second);
                ai.log.count("abort");
                g.aborted = true;
                return;
            }
        }
        if (d <= 14 && at != null) {
            // at the target: every warrior on a building, towers first
            List<Building> near = new ArrayList<>();
            for (Building b : in.enemy_buildings)
                if (b.getIslandId() == g.island && Geo.dist2(b.getGridX(), b.getGridY(), cx, cy) <= 22 * 22)
                    near.add(b);
            for (Unit w : movers) {
                if (busy(w))
                    continue;
                Building b = nearestBuilding(near, g.island, w.getGridX(), w.getGridY());
                if (b == null)
                    b = at;
                ai.order(w, b, Action.ATTACK, true);
            }
            return;
        }
        // advance: stragglers close up on the body, the rest step forward together
        int step = Math.min(d, 14);
        int px = cx + (g.tx - cx) * step / Math.max(1, d);
        int py = cy + (g.ty - cy) * step / Math.max(1, d);
        List<Unit> stragglers = new ArrayList<>();
        List<Unit> body = new ArrayList<>();
        for (Unit w : movers) {
            if (busy(w))
                continue;
            if (Geo.dist2(w.getGridX(), w.getGridY(), cx, cy) > 16 * 16)
                stragglers.add(w);
            else
                body.add(w);
        }
        if (!stragglers.isEmpty())
            ai.move(stragglers, cx, cy, Action.MOVE, false);
        if (!body.isEmpty() && second % 2 == 0)
            ai.move(body, px, py, Action.ATTACK, true);
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
        for (Unit w : warriors) {
            if (ai.reserved(w))
                continue;
            if (ai.params.kite && kite(w, fighters))
                continue;
            long reach = r + (long) Ratchet.bonus(w);
            long r2 = reach * reach;
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
                ai.order(w, best, Action.ATTACK, true);
            }
        }
    }

    /** Whether the threats hold no enemy warrior worth a tenth of the body (and less than 3). */
    private static boolean peonsOnly(@NonNull List<@NonNull Unit> threats, @NonNull Group g) {
        float warriors = 0;
        for (Unit u : threats)
            if (u.isWarrior() || u.isChieftain())
                warriors += Intel.value(u);
        float ours = 0;
        for (Unit w : g.members)
            ours += Intel.value(w);
        return warriors < Math.max(3f, .1f * ours);
    }

    /** Returns whether it ordered the siege (threats are peons only and buildings stand near). */
    private boolean siege(@NonNull Intel in, @NonNull Group g, @NonNull List<@NonNull Unit> threats, int cx, int cy) {
        float warriors = 0;
        for (Unit u : threats)
            if (u.isWarrior() || u.isChieftain())
                warriors += Intel.value(u);
        float ours = 0;
        for (Unit w : g.members)
            ours += Intel.value(w);
        if (warriors >= Math.max(3f, .1f * ours))
            return false;
        List<Building> near = new ArrayList<>();
        for (Building b : in.enemy_buildings)
            if (b.getIslandId() == g.island && Geo.dist2(b.getGridX(), b.getGridY(), cx, cy) <= 45 * 45)
                near.add(b);
        if (near.isEmpty())
            return false;
        near.sort((a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb)
                return Integer.compare(ra, rb);
            return Long.compare(Geo.dist2(a.getGridX(), a.getGridY(), cx, cy), Geo.dist2(b.getGridX(), b.getGridY(),
                    cx, cy));
        });
        List<Unit> members = new ArrayList<>(g.members);
        int hunters = members.size() / 4;
        List<Unit> peon_hunters = new ArrayList<>();
        List<Unit> breakers = new ArrayList<>();
        for (Unit w : members)
            (peon_hunters.size() < hunters ? peon_hunters : breakers).add(w);
        engage(peon_hunters, threats, 40);
        // up to 20 on each building, in rank order
        int i = 0;
        for (Building b : near) {
            for (int k = 0; k < 20 && i < breakers.size(); k++, i++) {
                Unit w = breakers.get(i);
                if (w.getPrimaryController() instanceof HuntController h && h.getTarget() == b)
                    continue;
                ai.order(w, b, Action.ATTACK, true);
            }
        }
        ai.log.count("siege_peons");
        return true;
    }

    /** Siege order of enemy buildings: towers (they shoot), quarters (they breed), armories, the rest. */
    private static int rank(@NonNull Building b) {
        if (Intel.isTower(b))
            return 0;
        if (Intel.isQuarters(b))
            return 1;
        if (Intel.isArmory(b))
            return 2;
        return 3;
    }

    /**
     * Tower sniping: a warrior whose reach against an enemy tower beats the garrison's reach against it (the drill
     * against an undrilled garrison) attacks the tower and stands at its own range, out of the tower's. Each sniper is
     * reserved for a few seconds so engage leaves it on the tower.
     */
    private void snipeTowers(@NonNull Intel in, @NonNull Group g, int cx, int cy, int reach) {
        long r2 = (long) (reach + 20) * (reach + 20);
        for (Building t : in.enemy_buildings) {
            if (!Intel.isTower(t) || !t.isComplete() || t.getIslandId() != g.island
                    || Geo.dist2(t.getGridX(), t.getGridY(), cx, cy) > r2)
                continue;
            Unit garrison = Towers.gunner(t);
            int on = 0;
            for (Unit w : g.members) {
                if (on >= 12 || !CorsairAI.orderable(w))
                    continue;
                float ours = w.getRange(t);
                float theirs = garrison == null ? 0f : garrison.getRange(w);
                if (ours < theirs + 2f)
                    continue;
                if (currentTarget(w) == null && w.getPrimaryController() instanceof HuntController h
                        && h.getTarget() == t) {
                    on++;
                    continue;
                }
                ai.order(w, t, Action.ATTACK, true);
                ai.reserve(w, 3);
                on++;
                ai.log.count("snipe");
            }
        }
    }

    /**
     * Stand-off for a warrior that outranges its nearest threat: when an enemy warrior comes within its own throwing
     * range (plus a step, as decisions come once a second), back off a few cells, straight away from it, and throw
     * again once out of its reach. Returns whether the warrior was ordered back.
     */
    private boolean kite(@NonNull Unit w, @NonNull List<@NonNull Unit> fighters) {
        Unit danger = null;
        long bd = Long.MAX_VALUE;
        for (Unit e : fighters) {
            if (e.isDead() || e.isChieftain())
                continue;
            float er = e.getRange(w) + (float) ai.params.kite_slack;
            long d2 = Geo.dist2(e.getGridX(), e.getGridY(), w.getGridX(), w.getGridY());
            if (d2 <= (long) (er * er) && d2 < bd) {
                bd = d2;
                danger = e;
            }
        }
        if (danger == null || w.getRange(danger) < danger.getRange(w) + 3f)
            return false;
        int dx = w.getGridX() - danger.getGridX(), dy = w.getGridY() - danger.getGridY();
        int len = Math.max(1, (int) Math.sqrt((double) dx * dx + (double) dy * dy));
        int step = ai.params.kite_step;
        int tx = w.getGridX() + dx * step / len, ty = w.getGridY() + dy * step / len;
        if (ai.geo.islandAt(tx, ty) != w.getIslandId())
            return false; // backed against the shore: stand and throw
        ai.move(List.of(w), tx, ty, Action.MOVE, false);
        ai.log.count("kite");
        return true;
    }

    private static @Nullable Unit currentTarget(@NonNull Unit w) {
        var c = w.getPrimaryController();
        if (c instanceof HuntController h && h.getTarget() instanceof Unit u)
            return u;
        if (c instanceof AttackController a && a.getTarget() instanceof Unit u)
            return u;
        return null;
    }

    /** soft_target: the nearest enemy building on the group's island that no garrisoned tower reaches, or null. */
    private @Nullable Building softTarget(@NonNull Intel in, @NonNull Group g, int cx, int cy) {
        Unit sample = g.members.getFirst();
        List<Building> towers = new ArrayList<>();
        List<Unit> guns = new ArrayList<>();
        for (Building b : in.enemy_buildings)
            if (Intel.isTower(b) && b.getIslandId() == g.island) {
                Unit gun = Towers.gunner(b);
                if (gun != null) {
                    towers.add(b);
                    guns.add(gun);
                }
            }
        Building best = null;
        long bd = Long.MAX_VALUE;
        for (Building b : in.enemy_buildings) {
            if (b.isDead() || b.getIslandId() != g.island || Intel.isTower(b) || b instanceof Ship)
                continue;
            boolean covered = false;
            for (int i = 0; i < towers.size() && !covered; i++) {
                float r = guns.get(i).getRange(sample) + 3;
                Building t = towers.get(i);
                covered = Geo.dist2(b.getGridX(), b.getGridY(), t.getGridX(), t.getGridY()) <= (long) (r * r);
            }
            if (covered)
                continue;
            long d = Geo.dist2(b.getGridX(), b.getGridY(), cx, cy);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    /** tower_rush: orders the body onto an enemy tower whose garrison reaches one of us, if few enemies are near. */
    private boolean towerRush(@NonNull Intel in, @NonNull Group g, @NonNull List<@NonNull Unit> threats) {
        Building tower = null;
        long best = Long.MAX_VALUE;
        for (Building b : in.enemy_buildings) {
            if (!Intel.isTower(b) || b.getIslandId() != g.island)
                continue;
            Unit gun = Towers.gunner(b);
            if (gun == null)
                continue;
            for (Unit w : g.members) {
                float r = gun.getRange(w) + 2;
                long d2 = Geo.dist2(w.getGridX(), w.getGridY(), b.getGridX(), b.getGridY());
                if (d2 <= (long) (r * r)) {
                    if (d2 < best) {
                        best = d2;
                        tower = b;
                    }
                    break;
                }
            }
        }
        if (tower == null)
            return false;
        float ours = 0, theirs = 0;
        for (Unit w : g.members)
            ours += Intel.value(w);
        for (Unit u : threats)
            theirs += Intel.value(u);
        if (theirs >= .25f * ours)
            return false;
        Building t = tower;
        for (Unit w : g.members)
            if (!(w.getPrimaryController() instanceof AttackController a && a.getTarget() == t))
                ai.order(w, t, Action.ATTACK, true);
        ai.log.count("tower_rush");
        return true;
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
