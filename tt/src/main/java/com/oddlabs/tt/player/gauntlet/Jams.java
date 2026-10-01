package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.behaviour.WalkBehaviour;
import com.oddlabs.tt.model.behaviour.WalkController;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Traffic jams of our own units (counters and log lines, no orders of its own). Every 5 s, a unit that is walking
 * (WalkBehaviour) but stands on the same grid cell as 5 s before is blocked; four or more blocked units within 4 cells
 * of each other are a jam. Peons clustering to harvest behind a narrow gap and an army column stuck at a choke both
 * show up here, which the census cannot see. The blocked warriors of every scan go to Military.noteBlocked (unjam), and
 * a big warrior jam gets a picture of the cells around it in the log (Military.describeJam).
 */
final class Jams {
    /** Game ticks between scans (5 s). */
    static final float PERIOD_TICKS = 250f;
    private static final int CLUSTER = 4;
    private static final int RADIUS = 4;

    private final @NonNull GauntletAI ai;
    private final Map<@NonNull Unit, int @NonNull []> last_cells = new LinkedHashMap<>();
    private float last_scan = -500f;
    private float last_log = -5000f;
    private float last_pic = -50000f;

    Jams(@NonNull GauntletAI ai) {
        this.ai = ai;
    }

    void tick() {
        if (!ai.periodDue(last_scan, PERIOD_TICKS))
            return;
        last_scan = ai.now();
        Intel intel = ai.intel();
        List<Unit> blocked_peons = new ArrayList<>();
        List<Unit> blocked_warriors = new ArrayList<>();
        Map<Unit, int[]> cells = new LinkedHashMap<>();
        for (List<Unit> group : List.of(intel.peons, intel.warriors))
            for (Unit u : group) {
                if (u.isDead() || u.isMounted())
                    continue;
                int[] cell = {u.getGridX(), u.getGridY()};
                cells.put(u, cell);
                int[] before = last_cells.get(u);
                if (before == null || before[0] != cell[0] || before[1] != cell[1])
                    continue;
                if (!(u.getCurrentBehaviour() instanceof WalkBehaviour))
                    continue;
                (group == intel.peons ? blocked_peons : blocked_warriors).add(u);
            }
        last_cells.clear();
        last_cells.putAll(cells);
        // unjam: the one use of the scan that changes play (Military ignores it while unjam is 0); before the log-only
        // counting below, so nothing there can keep it from running
        ai.military().noteBlocked(blocked_warriors);
        count(blocked_peons, "peon_blocked", "peon_jam", "peons");
        count(blocked_warriors, "warrior_blocked", "warrior_jam", "warriors");
    }

    /** Log only: each jammed peon's job (primary controller) and where its walk is headed. */
    private static @NonNull String describePeons(@NonNull List<@NonNull Unit> jam) {
        StringBuilder sb = new StringBuilder();
        for (Unit u : jam) {
            if (!sb.isEmpty())
                sb.append(", ");
            sb.append(u.getPrimaryController().getClass().getSimpleName().replace("Controller", ""));
            if (u.getCurrentController() instanceof WalkController w)
                sb.append(" to ").append(w.getTarget().getGridX()).append(',').append(w.getTarget().getGridY());
            else if (u.getPrimaryController() instanceof RepairController r) {
                Building b = r.getBuilding();
                sb.append(" site ").append(Intel.kind(b)).append(" at ").append(b.getGridX()).append(',').append(
                        b.getGridY()).append(b.isComplete() ? " (complete)" : "");
            }
        }
        return sb.toString();
    }

    /** Counts the blocked units, and each jam: a blocked unit with CLUSTER - 1 other blocked units within RADIUS. */
    private void count(@NonNull List<@NonNull Unit> blocked, @NonNull String blocked_key, @NonNull String jam_key,
            @NonNull String what) {
        for (int i = 0; i < blocked.size(); i++)
            ai.aiLog().count(blocked_key);
        List<Unit> left = new ArrayList<>(blocked);
        while (!left.isEmpty()) {
            Unit seed = left.removeFirst();
            List<Unit> jam = new ArrayList<>();
            jam.add(seed);
            for (Unit u : left)
                if (Math.max(Math.abs(u.getGridX() - seed.getGridX()), Math.abs(
                        u.getGridY() - seed.getGridY())) <= RADIUS)
                    jam.add(u);
            if (jam.size() < CLUSTER)
                continue;
            left.removeAll(jam);
            ai.aiLog().count(jam_key);
            if (ai.periodDue(last_log, 1000f)) { // every 20 s
                last_log = ai.now();
                int size = jam.size();
                int x = seed.getGridX();
                int y = seed.getGridY();
                ai.log(String.format("jam: %d %s blocked around %d,%d", size, what, x, y));
                if (ai.logging() && what.equals("peons"))
                    ai.log("jam peons: " + describePeons(jam));
            }
            // log only: what the cells around a big jam hold (12 warriors or 6 peons), at most every 150 s
            if (ai.logging() && jam.size() >= (what.equals("warriors") ? 12 : 6) && ai.periodDue(last_pic, 7500f)) {
                last_pic = ai.now();
                try {
                    ai.military().describeJam(seed.getGridX(), seed.getGridY());
                } catch (RuntimeException e) {
                    ai.aiLog().error("Jams.describeJam", e);
                }
            }
        }
    }
}
