package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.WalkController;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Log only: every group of reinforcements from the armory to the army. Military numbers the groups as it sends them
 * (traceGroups). A "REIN start" line when a group leaves (with the staging point and, after the bar, where each
 * member stands and what gave it its last order before), a "REIN s" line a second while a member is on its way, and a
 * "REIN end" line when the last has joined the army, turned back (the attack ended) or died. The second's line: the
 * members on their way, joined, dead and back; of those on their way the centre, the widest distance between two
 * (len), the median distance from the centre (r50) and how many fight (hunting a target), walk or stand; the enemy
 * warriors within 14 cells of any of them (count, strength, and by HomeTrace's kind after the bar), those within 8
 * cells, the enemy towers within 17, our other warriors within 14; the centre's cells from our nearest building and
 * from the army.
 */
final class ReinforceTrace {
    private static final int FOE_CELLS = 14;
    private static final int SCAN_CELLS = 8;
    private static final int TOWER_CELLS = 17;

    private static final class Group {
        final int id;
        final float since;
        final int sent;
        final List<Unit> way = new ArrayList<>();
        int joined;
        int dead;
        int back;

        Group(int id, float since, @NonNull List<@NonNull Unit> members) {
            this.id = id;
            this.since = since;
            this.sent = members.size();
            way.addAll(members);
        }
    }

    private final @NonNull FableAI ai;
    private final Map<Integer, Group> groups = new LinkedHashMap<>();
    private int last_group;
    private float last_sample;

    ReinforceTrace(@NonNull FableAI ai) {
        this.ai = ai;
    }

    void tick() {
        if (!ai.logging())
            return;
        Military military = ai.military();
        Intel intel = ai.intel();
        int[] front = military.attackCenter();
        if (military.traceGroupCount() > last_group) {
            Map<Integer, List<Unit>> fresh = new TreeMap<>();
            for (Map.Entry<Unit, Integer> e : military.traceGroups().entrySet())
                if (e.getValue() > last_group)
                    fresh.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            last_group = military.traceGroupCount();
            for (Map.Entry<Integer, List<Unit>> e : fresh.entrySet()) {
                Group g = new Group(e.getKey(), ai.now(), e.getValue());
                groups.put(g.id, g);
                int[] c = MapAnalysis.centroid(g.way);
                float strength = Combat.total(g.way);
                int dist = front == null ? -1 : (int) Math.sqrt(MapAnalysis.dist2(c[0], c[1], front[0], front[1]));
                StringBuilder members = new StringBuilder();
                for (Unit u : g.way)
                    members.append(' ').append(u.getGridX()).append(',').append(u.getGridY()).append(',').append(
                            military.traceBefore(u));
                ai.aiLog().log("REIN", () -> String.format(Locale.ROOT,
                        "start g %d n %d str %.1f at %d,%d army %.1f dist %d own %d staging %d,%d |%s", g.id, g.sent,
                        strength, c[0], c[1], military.traceAttackStrength(), dist, own(intel, c[0], c[1]),
                        military.stagingX(), military.stagingY(), members));
            }
        }
        for (Iterator<Group> it = groups.values().iterator(); it.hasNext();) {
            Group g = it.next();
            for (Iterator<Unit> wit = g.way.iterator(); wit.hasNext();) {
                Unit u = wit.next();
                if (u.isDead()) {
                    g.dead++;
                } else {
                    String role = military.roleOf(u);
                    if ("reinforce".equals(role) && military.traceGroup(u) == g.id)
                        continue;
                    if ("attack".equals(role))
                        g.joined++;
                    else
                        g.back++;
                }
                wit.remove();
            }
            if (g.way.isEmpty()) {
                ai.aiLog().log("REIN", () -> String.format(Locale.ROOT,
                        "end g %d age %.0f sent %d joined %d dead %d back %d", g.id, FableAI.seconds(
                                ai.now() - g.since),
                        g.sent, g.joined, g.dead, g.back));
                it.remove();
            }
        }
        if (groups.isEmpty() || !ai.periodDue(last_sample, 50f)) // 1 s
            return;
        last_sample = ai.now();
        for (Group g : groups.values())
            sample(g, intel, front);
    }

    private static int own(@NonNull Intel intel, int x, int y) {
        int own = Integer.MAX_VALUE;
        for (List<Building> list : List.of(intel.quarters, intel.armories, intel.towers))
            for (Building b : list)
                if (!b.isDead())
                    own = Math.min(own, MapAnalysis.dist2(x, y, b.getGridX(), b.getGridY()));
        return own == Integer.MAX_VALUE ? -1 : (int) Math.sqrt(own);
    }

    private static int nearest2(@NonNull List<@NonNull Unit> way, int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Unit u : way)
            best = Math.min(best, MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()));
        return best;
    }

    private void sample(@NonNull Group g, @NonNull Intel intel, int[] front) {
        List<Unit> way = g.way;
        int[] c = MapAnalysis.centroid(way);
        int len = 0;
        List<Integer> radii = new ArrayList<>();
        int fight = 0;
        int walk = 0;
        for (Unit u : way) {
            for (Unit v : way)
                len = Math.max(len, MapAnalysis.dist2(u.getGridX(), u.getGridY(), v.getGridX(), v.getGridY()));
            radii.add(MapAnalysis.dist2(u.getGridX(), u.getGridY(), c[0], c[1]));
            if (u.getCurrentController() instanceof HuntController)
                fight++;
            else if (u.getCurrentController() instanceof WalkController)
                walk++;
        }
        radii.sort(null);
        Map<String, Integer> kinds = new TreeMap<>();
        int foes = 0;
        int near = 0;
        float strength = 0f;
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead())
                continue;
            int d = nearest2(way, e.getGridX(), e.getGridY());
            if (d > FOE_CELLS * FOE_CELLS)
                continue;
            foes++;
            strength += Combat.value(e);
            if (d <= SCAN_CELLS * SCAN_CELLS)
                near++;
            kinds.merge(HomeTrace.kind(e), 1, Integer::sum);
        }
        int towers = 0;
        for (Building t : intel.enemy_towers)
            if (!t.isDead() && nearest2(way, t.getGridX(), t.getGridY()) <= TOWER_CELLS * TOWER_CELLS)
                towers++;
        int friends = 0;
        for (Unit w : intel.warriors)
            if (!w.isDead() && !way.contains(w)
                    && nearest2(way, w.getGridX(), w.getGridY()) <= FOE_CELLS * FOE_CELLS)
                friends++;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "s g %d age %.0f way %d joined %d dead %d back %d c %d,%d len %d r50 %d fight %d walk %d idle %d " + "foes %d fstr %.1f near %d towers %d friends %d own %d army %d |",
                g.id, FableAI.seconds(ai.now() - g.since), way.size(), g.joined, g.dead, g.back, c[0], c[1],
                (int) Math.sqrt(len), (int) Math.sqrt(radii.get(radii.size() / 2)), fight, walk,
                way.size() - fight - walk, foes, strength, near, towers, friends, own(intel, c[0], c[1]),
                front == null ? -1 : (int) Math.sqrt(MapAnalysis.dist2(c[0], c[1], front[0], front[1]))));
        for (Map.Entry<String, Integer> e : kinds.entrySet())
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        ai.aiLog().log("REIN", sb::toString);
    }
}
