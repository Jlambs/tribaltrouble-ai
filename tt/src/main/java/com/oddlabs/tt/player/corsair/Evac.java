package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Ship;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.IdleController;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * evac: warriors left idle on a foreign island (no group leads them: the island was cleared, or the body could not
 * reach anything from the pocket it landed in) are fetched home by ship to fight again. A body of 93 once stood on
 * a beach for the last eight minutes of a game.
 */
final class Evac {
    private enum Phase {
        CREW,
        GO,
        LOAD,
        BACK
    }

    private final @NonNull CorsairAI ai;
    private @Nullable Ship ship;
    private @NonNull Phase phase = Phase.CREW;
    private int island;
    private int since;
    private int cooldown;

    Evac(@NonNull CorsairAI ai) {
        this.ai = ai;
    }

    boolean owns(@NonNull Ship s) {
        return s == ship;
    }

    /** Our idle warriors on an island other than home, in no group. */
    private List<@NonNull Unit> stranded(@NonNull Intel in, int isl) {
        List<Unit> out = new ArrayList<>();
        for (Unit w : in.warriors)
            if (w.getIslandId() == isl && !ai.reserved(w) && !ai.army.inGroup(w) && Campaign.isFree(w))
                out.add(w);
        return out;
    }

    void think(@NonNull Intel in, int second) {
        if (!ai.params.evac || !ai.colony.founded())
            return;
        Ship s = ship;
        if (s != null && s.isDead()) {
            ship = null;
            s = null;
        }
        if (s == null) {
            if (second % 10 != 0 || second < cooldown)
                return;
            pick(in, second);
            return;
        }
        Fleet.Duty d = ai.fleet.duty(s);
        switch (phase) {
            case CREW -> {
                if (Fleet.peonsAboard(s) >= 10 || second - since >= 45) {
                    if (Fleet.peonsAboard(s) < 3) {
                        ship = null;
                        cooldown = second + 120;
                        return;
                    }
                    List<Unit> left = stranded(in, island);
                    if (left.isEmpty()) {
                        ai.fleet.landAll(s);
                        ship = null;
                        return;
                    }
                    int[] c = centroid(left);
                    int[] cell = ai.fleet.landingCell(island, c[0], c[1], 0);
                    if (cell == null) {
                        ai.fleet.landAll(s);
                        ship = null;
                        cooldown = second + 300;
                        return;
                    }
                    ai.fleet.goTo(s, island, cell[0], cell[1], second);
                    phase = Phase.GO;
                    since = second;
                }
            }
            case GO -> {
                if (d.job == Fleet.Job.ARRIVED && Fleet.dockedAt(s) == island) {
                    phase = Phase.LOAD;
                    since = second;
                } else if (d.job == Fleet.Job.FAILED || second - since > 600)
                    back(s, d, second);
            }
            case LOAD -> {
                List<Unit> left = stranded(in, island);
                int room = Math.max(0, 26 + ai.params.pack - Fleet.warriorsAboard(s));
                List<Unit> go = new ArrayList<>(left.subList(0, Math.min(room, left.size())));
                for (Unit u : go)
                    ai.reserve(u, 30);
                ai.order(go, s, Action.MOVE, false);
                if (second - since >= 60 || left.isEmpty() && second - since >= 10) {
                    int n = Fleet.warriorsAboard(s);
                    ai.log.log("EVAC", () -> "bringing " + n + " warriors home from isle " + island + " t" + second);
                    back(s, d, second);
                }
            }
            case BACK -> {
                boolean home = Fleet.dockedAt(s) == ai.colony.island && !s.isMoving() && !d.land_all;
                if (home || d.job == Fleet.Job.FAILED || second - since > 900) {
                    if (!home)
                        ai.fleet.goHome(s, second);
                    ship = null;
                }
            }
        }
    }

    private void back(@NonNull Ship s, Fleet.@NonNull Duty d, int second) {
        ai.fleet.goHome(s, second);
        d.land_all = true;
        ai.fleet.landAll(s);
        phase = Phase.BACK;
        since = second;
    }

    private void pick(@NonNull Intel in, int second) {
        // the island with the most stranded warriors, if they are enough to be worth a ship
        int best = 0, best_n = 0;
        java.util.Map<Integer, Integer> count = new java.util.TreeMap<>();
        for (Unit w : in.warriors) {
            int isl = w.getIslandId();
            if (isl == 0 || isl == ai.colony.island || ai.reserved(w) || ai.army.inGroup(w) || !Campaign.isFree(w))
                continue;
            count.merge(isl, 1, Integer::sum);
        }
        for (var e : count.entrySet())
            if (e.getValue() > best_n) {
                best_n = e.getValue();
                best = e.getKey();
            }
        if (best_n < 12 || ai.campaign.fightingOn(best))
            return;
        // the stranded must have stood idle a while: a fresh landing waits for its body to form
        List<Ship> ships = ai.fleet.idleAtHome();
        if (ships.isEmpty())
            return;
        Ship s = ships.getFirst();
        ship = s;
        island = best;
        phase = Phase.CREW;
        since = second;
        ai.fleet.cancelDeploys(s);
        ai.ratchet.haltInbound(s);
        List<Unit> crew = new ArrayList<>();
        for (Unit u : in.peons)
            if (crew.size() < 16 && u.getIslandId() == ai.colony.island && !ai.reserved(u)
                    && (u.getPrimaryController() instanceof IdleController
                            || u.getPrimaryController() instanceof GatherController<?>))
                crew.add(u);
        for (Unit u : crew)
            ai.reserve(u, 60);
        ai.order(crew, s, Action.MOVE, false);
        int n = best_n, isl = best;
        ai.log.log("EVAC", () -> n + " warriors stranded on isle " + isl + ": fetching them t" + second);
        ai.log.count("evac");
    }

    private static int @NonNull [] centroid(@NonNull List<@NonNull Unit> units) {
        long sx = 0, sy = 0;
        for (Unit u : units) {
            sx += u.getGridX();
            sy += u.getGridY();
        }
        return new int[]{(int) (sx / units.size()), (int) (sy / units.size())};
    }
}
