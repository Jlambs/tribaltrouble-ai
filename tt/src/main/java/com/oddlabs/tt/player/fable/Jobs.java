package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers what the AI last told each unit to do, so orders are only re-issued when the job changes or the unit
 * stopped doing it (an order clears the unit's controller stack, so blindly repeating orders every tick would
 * interrupt throws and harvesting). Also used to reserve units for a task while they walk there.
 */
public final class Jobs {
    public enum Kind {
        NONE,
        GATHER,
        BUILD,
        ENTER,
        ATTACK_MOVE,
        MOVE,
        DEFEND,
        HUNT,
        GARRISON,
        HOLD
    }

    /** One assignment. Targets are optional depending on the kind. */
    public static final class Job {
        public final @NonNull Kind kind;
        public final int gx;
        public final int gy;
        public final @Nullable Selectable<?> target;
        public final ResourceMap.@Nullable Node node;
        public final float issued_at;
        /** Free-form tag chosen by the manager that issued the job (e.g. squad id). */
        public final int tag;

        Job(@NonNull Kind kind, int gx, int gy, @Nullable Selectable<?> target, ResourceMap.@Nullable Node node,
                float issued_at, int tag) {
            this.kind = kind;
            this.gx = gx;
            this.gy = gy;
            this.target = target;
            this.node = node;
            this.issued_at = issued_at;
            this.tag = tag;
        }

        public boolean sameAs(@NonNull Kind kind, int gx, int gy, @Nullable Selectable<?> target,
                ResourceMap.@Nullable Node node) {
            return this.kind == kind && this.gx == gx && this.gy == gy && this.target == target && this.node == node;
        }

        public @Nullable LandBuilding building() {
            return target instanceof LandBuilding b ? b : null;
        }

        @Override
        public @NonNull String toString() {
            return kind + (node != null ? " " + node : "") + (target != null ? " " + target : "") + (kind == Kind.ATTACK_MOVE
                    || kind == Kind.MOVE || kind == Kind.DEFEND ? " " + gx + "," + gy : "") + " tag=" + tag;
        }
    }

    private final Map<Unit, Job> jobs = new LinkedHashMap<>();

    public @Nullable Job get(@NonNull Unit unit) {
        return jobs.get(unit);
    }

    public @NonNull Kind kindOf(@NonNull Unit unit) {
        Job j = jobs.get(unit);
        return j == null ? Kind.NONE : j.kind;
    }

    public boolean has(@NonNull Unit unit) {
        return jobs.containsKey(unit);
    }

    /** Record a job (call after issuing the engine order). */
    public @NonNull Job set(@NonNull Unit unit, @NonNull Kind kind, int gx, int gy, @Nullable Selectable<?> target,
            ResourceMap.@Nullable Node node, float now, int tag) {
        Job job = new Job(kind, gx, gy, target, node, now, tag);
        jobs.put(unit, job);
        return job;
    }

    public void clear(@NonNull Unit unit) {
        jobs.remove(unit);
    }

    /** Drop jobs of dead or mounted units and jobs whose target is gone. */
    public void prune() {
        Iterator<Map.Entry<Unit, Job>> it = jobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Unit, Job> e = it.next();
            Unit u = e.getKey();
            Job j = e.getValue();
            if (u.isDead() || u.isMounted() || (j.target != null && j.target.isDead())) {
                it.remove();
            }
        }
    }

    public int count(@NonNull Kind kind) {
        int n = 0;
        for (Job j : jobs.values())
            if (j.kind == kind)
                n++;
        return n;
    }

    public int count(@NonNull Kind kind, int tag) {
        int n = 0;
        for (Job j : jobs.values())
            if (j.kind == kind && j.tag == tag)
                n++;
        return n;
    }

    /** Units currently assigned to a job kind (and tag when tag >= 0), in assignment order. */
    public @NonNull List<Unit> units(@NonNull Kind kind, int tag) {
        List<Unit> result = new ArrayList<>();
        for (Map.Entry<Unit, Job> e : jobs.entrySet())
            if (e.getValue().kind == kind && (tag < 0 || e.getValue().tag == tag))
                result.add(e.getKey());
        return result;
    }

    /** Units working on (building or entering) a particular building. */
    public @NonNull List<Unit> unitsOn(@NonNull Selectable<?> target) {
        List<Unit> result = new ArrayList<>();
        for (Map.Entry<Unit, Job> e : jobs.entrySet())
            if (e.getValue().target == target)
                result.add(e.getKey());
        return result;
    }

    /** Units gathering a particular node. */
    public int gatherersOn(ResourceMap.@NonNull Node node) {
        int n = 0;
        for (Job j : jobs.values())
            if (j.kind == Kind.GATHER && j.node == node)
                n++;
        return n;
    }

    public @NonNull Iterable<Map.Entry<Unit, Job>> entries() {
        return jobs.entrySet();
    }

    public int size() {
        return jobs.size();
    }
}
