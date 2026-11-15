package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.player.fable.Intel.PeonState;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Log only: times every iron gatherer's trip, so a logged game shows where the gatherers' time goes. */
final class TripTrace {
    private static final class Trip {
        final float start;
        float loaded = -1f;
        int far;
        int picked;
        int retargets;
        @Nullable
        Supply supply;

        Trip(float start) {
            this.start = start;
        }
    }

    private final @NonNull FableAI ai;
    private final Map<Unit, Trip> trips = new LinkedHashMap<>();

    TripTrace(@NonNull FableAI ai) {
        this.ai = ai;
    }

    void tick() {
        Intel intel = ai.intel();
        float now = ai.now();
        Iterator<Map.Entry<Unit, Trip>> it = trips.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Unit, Trip> e = it.next();
            Unit u = e.getKey();
            Trip t = e.getValue();
            if (u.isDead()) {
                end("dead", u, t, now);
                it.remove();
                continue;
            }
            PeonState state = intel.peon_states.get(u);
            if (state != PeonState.GATHER_IRON) {
                end(state == null ? "inside" : state.name().toLowerCase(java.util.Locale.ROOT), u, t, now);
                it.remove();
                continue;
            }
            int d = homeCells(intel, u);
            t.far = Math.max(t.far, d);
            if (u.getPrimaryController() instanceof GatherController<?> gather) {
                Supply s = gather.getSupply();
                if (s != t.supply && t.loaded < 0f) {
                    if (t.supply != null)
                        t.retargets++;
                    t.supply = s;
                }
            }
            boolean has = u.getSupplyContainer().getNumSupplies() > 0;
            if (t.loaded < 0f && has) {
                t.loaded = now;
                t.picked = d;
            } else if (t.loaded >= 0f && !has) {
                ai.log(String.format("gtrip ok out %.0f back %.0f picked %d far %d retargets %d", FableAI.seconds(
                        t.loaded - t.start), FableAI.seconds(now - t.loaded), t.picked, t.far, t.retargets));
                e.setValue(new Trip(now));
            }
        }
        for (Unit u : intel.peons)
            if (!u.isDead() && intel.peon_states.get(u) == PeonState.GATHER_IRON && !trips.containsKey(u))
                trips.put(u, new Trip(now));
    }

    private void end(@NonNull String how, @NonNull Unit u, @NonNull Trip t, float now) {
        ai.log(String.format("gtrip %s after %.0f loaded %b far %d retargets %d at %d,%d", how, FableAI.seconds(
                now - t.start), t.loaded >= 0f, t.far, t.retargets, u.getGridX(), u.getGridY()));
    }

    private static int homeCells(@NonNull Intel intel, @NonNull Unit u) {
        Building home = intel.gather_buildings.get(u);
        if (home == null || home.isDead())
            return -1;
        return (int) Math.sqrt(MapAnalysis.dist2(home.getGridX(), home.getGridY(), u.getGridX(), u.getGridY()));
    }
}
