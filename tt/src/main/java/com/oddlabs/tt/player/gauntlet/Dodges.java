package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.MagicController;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Running from locked hunters.
 *
 * <p>An enemy unit hunting one of ours walks without scanning, re-plans until it is in range, its target dies or the
 * target is out of path, and never answers being hit (HuntController, WalkBehaviour without scan, Unit.hit). Its
 * throw reaches 7.9 cells, while most first sightings come at the edge of its 8-cell scan square, out of range. A
 * peon (5 m/s) outruns it (4 m/s), and entering a building takes the peon off the grid, which ends the hunt; our
 * chieftain is as fast as a hunter, so walking away keeps it out of range while towers and our army shoot the
 * hunters. HuntController.getTarget and AttackController.getTarget are public.
 */
final class Dodges {
    private final @NonNull GauntletAI ai;
    /** Peons running for cover, and since when (game tick): kept out of the economy for 12 s. */
    private final Map<@NonNull Unit, Float> running = new LinkedHashMap<>();
    private float chief_move = -500f;

    Dodges(@NonNull GauntletAI ai) {
        this.ai = ai;
    }

    /** Whether the chieftain was moved away from hunters in the last 2 s (Chieftain leaves him alone then). */
    boolean chiefBusy() {
        return ai.now() - chief_move < 100f; // 2 s
    }

    /** Every 5 game ticks. */
    void guard() {
        Strategy strategy = ai.strategy();
        if (!strategy.peon_dodge && !strategy.chief_dodge)
            return;
        Intel intel = ai.intel();
        if (!running.isEmpty()) {
            running.entrySet().removeIf(e -> e.getKey().isDead() || ai.now() - e.getValue() > 600f); // 12 s
            intel.dodging.retainAll(running.keySet());
        }
        // Who hunts whom: our unit -> the enemies whose hunt or attack target it is.
        Map<Unit, List<Unit>> locks = new LinkedHashMap<>();
        for (List<Unit> group : List.of(intel.enemy_warriors, intel.enemy_chieftains)) {
            for (Unit e : group) {
                if (e.isDead())
                    continue;
                Controller c = e.getCurrentController();
                Selectable<?> t = c instanceof HuntController h ? h.getTarget() : c instanceof AttackController a ? a.getTarget() : null;
                if (t instanceof Unit u && u.getOwner() == ai.owner() && !u.isDead())
                    locks.computeIfAbsent(u, k -> new ArrayList<>()).add(e);
            }
        }
        if (locks.isEmpty())
            return;
        if (strategy.chief_dodge && intel.chieftain != null && !intel.chieftain.isDead())
            dodgeChief(intel.chieftain, locks.get(intel.chieftain));
        if (strategy.peon_dodge)
            for (Map.Entry<Unit, List<Unit>> e : locks.entrySet())
                dodgePeon(e.getKey(), e.getValue());
    }

    private void dodgePeon(@NonNull Unit p, @NonNull List<@NonNull Unit> hunters) {
        Intel intel = ai.intel();
        if (!p.getAbilities().hasAbilities(Abilities.BUILD) || p.isMounted() || running.containsKey(p)
                || intel.lures.contains(p) || intel.shepherds.contains(p) || Intel.isStunned(p)
                || ai.economy().reservedPlacer(p))
            return;
        int px = p.getGridX();
        int py = p.getGridY();
        long hx = 0;
        long hy = 0;
        for (Unit h : hunters) {
            // In range already: the throw is coming anyway, running only denies the next one; leave it.
            if (MapAnalysis.dist2(px, py, h.getGridX(), h.getGridY()) <= 62)
                return;
            hx += h.getGridX();
            hy += h.getGridY();
        }
        int cx = (int) (hx / hunters.size());
        int cy = (int) (hy / hunters.size());
        // Cover: our nearest quarters or armory within 60 cells that is not behind the hunters.
        Building best = null;
        int best_d = 60 * 60;
        List<Building> homes = intel.homes();
        for (Building b : homes) {
            if (b.isDead() || !b.isComplete() || ai.economy().isEvacuating(b))
                continue;
            int d = MapAnalysis.dist2(px, py, b.getGridX(), b.getGridY());
            if (d >= best_d || MapAnalysis.dist2(cx, cy, b.getGridX(), b.getGridY()) <= d)
                continue;
            best_d = d;
            best = b;
        }
        running.put(p, ai.now());
        intel.dodging.add(p);
        intel.peon_states.put(p, Intel.PeonState.SHEPHERD);
        ai.aiLog().count("peon_dodge");
        if (best != null) {
            ai.owner().setTarget(Selectable.newArray(p), best, Action.MOVE, false);
            return;
        }
        int[] away = awayFrom(px, py, cx, cy, 14);
        ai.landscapeOrder(Selectable.newArray(p), away[0], away[1], Action.MOVE, false);
    }

    private void dodgeChief(@NonNull Unit chief, @Nullable List<@NonNull Unit> hunters) {
        // a new step at most once a game second (50 game ticks)
        if (hunters == null || hunters.isEmpty() || Intel.isStunned(chief)
                || chief.getCurrentController() instanceof MagicController || !ai.periodDue(chief_move, 50f))
            return;
        int x = chief.getGridX();
        int y = chief.getGridY();
        long hx = 0;
        long hy = 0;
        int near = 0;
        for (Unit h : hunters)
            if (MapAnalysis.dist2(x, y, h.getGridX(), h.getGridY()) <= 12 * 12) {
                hx += h.getGridX();
                hy += h.getGridY();
                near++;
            }
        if (near == 0)
            return;
        int cx = (int) (hx / near);
        int cy = (int) (hy / near);
        // Towards our nearest manned tower or the home army's staging point, if that lies away from the hunters.
        int[] goal = null;
        int best = Integer.MAX_VALUE;
        for (Building t : ai.intel().towers) {
            if (!Intel.isTowerActive(t))
                continue;
            int d = MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY());
            if (d < best && d <= 50 * 50 && MapAnalysis.dist2(cx, cy, t.getGridX(), t.getGridY()) > d) {
                best = d;
                goal = new int[]{t.getGridX(), t.getGridY()};
            }
        }
        int[] step = goal != null ? stepTowards(x, y, goal[0], goal[1], 8) : awayFrom(x, y, cx, cy, 8);
        chief_move = ai.now();
        ai.aiLog().count("chief_dodge");
        ai.landscapeOrder(Selectable.newArray(chief), step[0], step[1], Action.MOVE, false);
    }

    private static int @NonNull [] awayFrom(int x, int y, int fx, int fy, int cells) {
        float dx = x - fx;
        float dy = y - fy;
        float len = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
        return new int[]{x + Math.round(cells * dx / len), y + Math.round(cells * dy / len)};
    }

    private static int @NonNull [] stepTowards(int x, int y, int tx, int ty, int cells) {
        float dx = tx - x;
        float dy = ty - y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= cells)
            return new int[]{tx, ty};
        return new int[]{x + Math.round(cells * dx / len), y + Math.round(cells * dy / len)};
    }
}
