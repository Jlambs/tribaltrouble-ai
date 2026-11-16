package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.player.fable.Intel.PeonState;
import org.jspecify.annotations.NonNull;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counts and logs only: every peon that dies outside a building, by what it was doing and whether it was within
 * HOME_CELLS of a quarters or armory of ours (peon_lost_JOB, and peon_late_JOB from LATE_TICKS on), with a log line
 * a minute. Peons that die inside a razed building are Siege's inside_lost_*.
 */
final class PeonLedger {
    private static final int HOME_CELLS = 30;
    private static final float LATE_TICKS = 90000f; // 30 min

    private final @NonNull FableAI ai;
    private final Map<Unit, String> jobs = new LinkedHashMap<>();
    private final Map<String, Integer> minute = new TreeMap<>();
    private float last_log;

    PeonLedger(@NonNull FableAI ai) {
        this.ai = ai;
    }

    void tick() {
        Intel intel = ai.intel();
        boolean late = ai.now() >= LATE_TICKS;
        for (Iterator<Map.Entry<Unit, String>> it = jobs.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Unit, String> e = it.next();
            Unit u = e.getKey();
            if (u.isDead() && u.getHitPoints() > 0) {
                it.remove(); // it entered a building: the engine removes the unit and makes a new one on the way out
            } else if (u.isDead()) {
                String job = e.getValue();
                ai.aiLog().count("peon_lost_" + job);
                if (late)
                    ai.aiLog().count("peon_late_" + job);
                if (ai.logging())
                    minute.merge(job, 1, Integer::sum);
                it.remove();
            } else if (!intel.peon_states.containsKey(u)) {
                it.remove();
            }
        }
        for (Map.Entry<Unit, PeonState> e : intel.peon_states.entrySet())
            jobs.put(e.getKey(), job(intel, e.getKey(), e.getValue()));
        if (ai.logging() && ai.periodDue(last_log, 3000f)) { // 60 s
            last_log = ai.now();
            if (!minute.isEmpty()) {
                StringBuilder sb = new StringBuilder("peons lost in a minute:");
                for (Map.Entry<String, Integer> e : minute.entrySet())
                    sb.append(' ').append(e.getKey()).append(' ').append(e.getValue());
                ai.aiLog().log("PEONS", sb::toString);
                minute.clear();
            }
        }
    }

    private static @NonNull String job(@NonNull Intel intel, @NonNull Unit u, @NonNull PeonState state) {
        String name = intel.evading.contains(u) ? "evade" : state.name().toLowerCase(java.util.Locale.ROOT);
        int best = Integer.MAX_VALUE;
        for (List<Building> list : List.of(intel.armories, intel.quarters))
            for (Building b : list)
                if (!b.isDead())
                    best = Math.min(best, MapAnalysis.dist2(b.getGridX(), b.getGridY(), u.getGridX(), u.getGridY()));
        return name + (best <= HOME_CELLS * HOME_CELLS ? "_home" : "_far");
    }
}
