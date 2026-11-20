package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Log only: units whose orders contradict each other. Every round (0.5 s) each of our peons and warriors is looked at
 * for the kind of its primary controller (what it was last ordered to do); a unit whose kind changed THRASH times
 * within WINDOW_TICKS is thrashing, and each further change while it is counts a second half under the pair of kinds
 * it turns between. One THRASH line a minute with the pairs of that minute.
 */
final class ThrashTrace {
    private static final int THRASH = 4;
    private static final float WINDOW_TICKS = 250f; // 5 s

    private static final class Track {
        String kind = "";
        final float[] changes = new float[THRASH];
        int n;
    }

    private final @NonNull FableAI ai;
    private final Map<Unit, Track> tracks = new LinkedHashMap<>();
    private final Map<String, Integer> minute = new TreeMap<>();
    private float last_log;

    ThrashTrace(@NonNull FableAI ai) {
        this.ai = ai;
    }

    void tick() {
        if (!ai.logging())
            return;
        Intel intel = ai.intel();
        float now = ai.now();
        for (Iterator<Unit> it = tracks.keySet().iterator(); it.hasNext();)
            if (it.next().isDead())
                it.remove();
        for (List<Unit> group : List.of(intel.peons, intel.warriors))
            for (Unit u : group) {
                if (u.isDead())
                    continue;
                String kind = (group == intel.peons ? "peon " : "warrior ") + u.getPrimaryController().getClass().getSimpleName().replace(
                        "Controller", "");
                Track t = tracks.get(u);
                if (t == null) {
                    t = new Track();
                    t.kind = kind;
                    tracks.put(u, t);
                    continue;
                }
                if (kind.equals(t.kind))
                    continue;
                String pair = t.kind.compareTo(kind) < 0 ? t.kind + "/" + kind : kind + "/" + t.kind;
                t.kind = kind;
                t.changes[t.n++ % THRASH] = now;
                if (t.n >= THRASH && now - t.changes[t.n % THRASH] <= WINDOW_TICKS)
                    minute.merge(pair, 1, Integer::sum);
            }
        if (ai.periodDue(last_log, 3000f)) { // 60 s
            last_log = now;
            if (!minute.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> e : minute.entrySet())
                    sb.append(sb.length() == 0 ? "" : "; ").append(e.getKey()).append(' ').append(e.getValue());
                ai.aiLog().log("THRASH", sb::toString);
                minute.clear();
            }
        }
    }
}
