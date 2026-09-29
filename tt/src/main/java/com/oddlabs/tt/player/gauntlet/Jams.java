package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.WalkBehaviour;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostics only (counters and log lines, no orders): traffic jams of our own units. Every 5 s, a unit that is
 * walking (WalkBehaviour) but stands on the same grid cell as 5 s before is blocked; four or more blocked units within
 * 4 cells of each other are a jam. Peons clustering to harvest behind a narrow gap and an army column stuck at a choke
 * both show up here, which the census cannot see.
 */
final class Jams {
    private static final float PERIOD = 5f;
    private static final int CLUSTER = 4;
    private static final int RADIUS = 4;

    private final @NonNull GauntletAI ai;
    private final Map<@NonNull Unit, int @NonNull []> last_cells = new LinkedHashMap<>();
    private float last_scan = -10f;
    private float last_log = -100f;

    Jams(@NonNull GauntletAI ai) {
        this.ai = ai;
    }

    void tick() {
        if (ai.time() - last_scan < PERIOD)
            return;
        last_scan = ai.time();
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
        count(blocked_peons, "peon_blocked", "peon_jam", "peons");
        count(blocked_warriors, "warrior_blocked", "warrior_jam", "warriors");
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
            if (ai.time() - last_log >= 20f) {
                last_log = ai.time();
                int size = jam.size();
                int x = seed.getGridX();
                int y = seed.getGridY();
                ai.log(String.format("jam: %d %s blocked around %d,%d", size, what, x, y));
            }
        }
    }
}
