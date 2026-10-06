package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The opening raid (param raid = N peons): the starting ship lands everyone but N at home, then carries the N to the
 * first building site an enemy places, lands them, and they cut down its builders and the site itself (an unfinished
 * building has the hit points of its progress). The gauntlet AI's freeze opening, by sea. Off by default.
 */
final class Raid {
    enum State {
        WAIT_HOME,
        WAIT_TARGET,
        SAIL,
        FIGHT,
        DONE
    }

    private final @NonNull CorsairAI ai;
    private final @NonNull Player me;
    @NonNull
    State state = State.WAIT_HOME;
    private @Nullable Ship ship;
    private @Nullable Building target;
    private int since;
    final List<@NonNull Unit> raiders = new ArrayList<>();

    Raid(@NonNull CorsairAI ai) {
        this.ai = ai;
        this.me = ai.me();
    }

    boolean active() {
        return ai.params.raid > 0 && state != State.DONE;
    }

    /** Whether the ship is the raid's (the fleet leaves it alone). */
    boolean owns(@NonNull Ship s) {
        return active() && s == ship;
    }

    void start(@NonNull Ship s) {
        ship = s;
    }

    void think(@NonNull Intel in, int second) {
        if (!active())
            return;
        Ship s = ship;
        if (s == null || s.isDead()) {
            state = State.DONE;
            return;
        }
        switch (state) {
            case WAIT_HOME -> {
                // the colonists are ashore once only the raiders are left aboard
                if (Fleet.dockedAt(s) == ai.colony.island && !s.isMoving()
                        && Fleet.peonsAboard(s) <= ai.params.raid && Fleet.pending(s, DeployType.PEON) == 0) {
                    state = State.WAIT_TARGET;
                    since = second;
                }
            }
            case WAIT_TARGET -> {
                Building t = firstSite(in);
                if (t == null) {
                    if (second - since > 150)
                        finish(s, second);
                    return;
                }
                if (t.getIslandId() == ai.colony.island) {
                    // the enemy builds on our island: the raiders land at home and walk there
                    target = t;
                    ai.fleet.landPeons(s, Fleet.peonsAboard(s));
                    state = State.FIGHT;
                    since = second;
                    ai.log.log("RAID",
                            () -> "walking to the site at " + t.getGridX() + "," + t.getGridY() + " t" + second);
                    return;
                }
                // a few rowers are slow: only a near neighbour is worth it (~2 cells/s with 7 rowers)
                int sea = ai.sea.to(ai.campaign.homeField(), t.getGridX(), t.getGridY());
                if (sea > ai.params.raid_range) {
                    ai.log.log("RAID",
                            () -> "site at " + t.getGridX() + "," + t.getGridY() + " is " + sea + " cells away by sea: no raid");
                    finish(s, second);
                    return;
                }
                target = t;
                ai.fleet.goTo(s, t.getIslandId(), t.getGridX(), t.getGridY(), second);
                state = State.SAIL;
                since = second;
                ai.log.log("RAID",
                        () -> "sailing for the site at " + t.getGridX() + "," + t.getGridY() + " t" + second);
            }
            case SAIL -> {
                Fleet.Duty d = ai.fleet.duty(s);
                if (d.job == Fleet.Job.ARRIVED) {
                    ai.fleet.landPeons(s, Fleet.peonsAboard(s));
                    state = State.FIGHT;
                    since = second;
                } else if (d.job == Fleet.Job.FAILED || second - since > 300) {
                    finish(s, second);
                }
            }
            case FIGHT -> fight(in, s, second);
            default -> {
            }
        }
    }

    /** The enemy building site nearest its owner's start, while the enemy has no finished building yet. */
    private @Nullable Building firstSite(@NonNull Intel in) {
        Building best = null;
        long bd = Long.MAX_VALUE;
        for (Building b : in.enemy_buildings) {
            if (b.isDead() || b.isComplete() || b.getIslandId() == 0)
                continue;
            long d = Geo.dist2(b.getGridX(), b.getGridY(), Geo.gridOf(me.getStartX()), Geo.gridOf(me.getStartY()));
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    private void fight(@NonNull Intel in, @NonNull Ship s, int second) {
        int island = target == null ? Fleet.dockedAt(s) : target.getIslandId();
        raiders.removeIf(u -> u.isDead() || u.getIslandId() != island);
        if (island == ai.colony.island) {
            // at home the raiders are the peons nearest the site, picked once
            if (raiders.isEmpty() && second - since < 5 && target != null) {
                List<Unit> near = new ArrayList<>();
                for (Unit u : in.peons)
                    if (u.getIslandId() == island && !ai.reserved(u))
                        near.add(u);
                Colony.sortByDistance(near, target.getGridX(), target.getGridY());
                raiders.addAll(near.subList(0, Math.min(ai.params.raid, near.size())));
            }
        } else {
            raiders.clear();
            for (Unit u : in.peons)
                if (u.getIslandId() == island)
                    raiders.add(u);
        }
        if (raiders.isEmpty() && Fleet.peonsAboard(s) == 0 && second - since > 20 || second - since > 240) {
            finish(s, second);
            return;
        }
        // builders first, then any peon near the site, then the site itself
        int cx = target != null ? target.getGridX() : s.getGridX(),
                cy = target != null ? target.getGridY() : s.getGridY();
        for (Unit r : raiders) {
            Unit best = null;
            long bd = 30L * 30;
            for (Unit e : in.enemy_units) {
                if (e.getIslandId() != island || e.isWarrior() || e.isChieftain())
                    continue;
                long d = Geo.dist2(e.getGridX(), e.getGridY(), r.getGridX(), r.getGridY());
                boolean builder = e.getPrimaryController() instanceof RepairController
                        || e.getPrimaryController() instanceof PlaceBuildingController;
                if (builder)
                    d /= 4;
                if (d < bd && Geo.dist2(e.getGridX(), e.getGridY(), cx, cy) <= 40 * 40) {
                    bd = d;
                    best = e;
                }
            }
            if (best != null)
                ai.order(r, best, Action.ATTACK, true);
            else if (target != null && !target.isDead())
                ai.order(r, target, Action.ATTACK, true);
        }
        for (Unit r : raiders)
            ai.reserve(r, 3);
    }

    private void finish(@NonNull Ship s, int second) {
        state = State.DONE;
        ai.log.log("RAID", () -> "over t" + second);
        if (!s.isDead())
            ai.fleet.goHome(s, second);
    }
}
