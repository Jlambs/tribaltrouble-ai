package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackBehaviour;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.WalkController;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Log only: what the home army (the warriors in role army) does, one "HOME s" line a second from the first warrior
 * on. The threat as the military sees it (level, whether it engages, the strength it counts, the strength of every
 * enemy warrior within 15 cells of the threat's centre, the centre's cells from our nearest building), then the home
 * warriors by why/zone/act and the enemy warriors within 45 cells of a building of ours by zone/kind. why is what
 * gave the warrior its last order (Military.traceWhy), zone is tower (within 16 cells of a manned tower of ours), base
 * (within 28 of a building of ours), near (within 45) or far, act is fight (hunting a target), walk or idle, kind is
 * parked, hunt, walk or other. The deaths are Military's "lost" lines. And a "HOME kill" line for every enemy warrior
 * that dies: its zone and who of ours was throwing at it half a second before (tower, peon, or a warrior's role, with
 * the why for role army), none when nobody was (a blast, or an axe on its way).
 */
final class HomeTrace {
    private static final int TOWER_CELLS = 16;
    private static final int BASE_CELLS = 28;
    private static final int NEAR_CELLS = 45;

    private final @NonNull FableAI ai;
    private float last_sample;
    /** The enemy warriors alive at the last round, each with who of ours was throwing at it. */
    private final Map<Unit, Map<String, Integer>> watched = new LinkedHashMap<>();

    HomeTrace(@NonNull FableAI ai) {
        this.ai = ai;
    }

    /** The zone of a cell: tower, base, near or far (see the class comment). */
    static @NonNull String zone(@NonNull Intel intel, int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Building t : intel.towers) {
            int d = MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY());
            if (d <= TOWER_CELLS * TOWER_CELLS && Intel.isTowerManned(t))
                return "tower";
            best = Math.min(best, d);
        }
        for (List<Building> list : List.of(intel.quarters, intel.armories))
            for (Building b : list)
                if (!b.isDead())
                    best = Math.min(best, MapAnalysis.dist2(x, y, b.getGridX(), b.getGridY()));
        return best <= BASE_CELLS * BASE_CELLS ? "base" : best <= NEAR_CELLS * NEAR_CELLS ? "near" : "far";
    }

    private static @NonNull String kind(@NonNull Unit e) {
        if (Intel.isParked(e))
            return "parked";
        if (e.getCurrentController() instanceof HuntController)
            return "hunt";
        if (e.getCurrentController() instanceof WalkController)
            return "walk";
        return "other";
    }

    /** Every round (0.5 s): the enemy warriors dead since the last one, with who was throwing at each. */
    private void kills() {
        Intel intel = ai.intel();
        Military military = ai.military();
        for (Map.Entry<Unit, Map<String, Integer>> e : watched.entrySet()) {
            Unit dead = e.getKey();
            if (!dead.isDead())
                continue;
            StringBuilder sb = new StringBuilder();
            sb.append("kill at ").append(dead.getGridX()).append(',').append(dead.getGridY()).append(" zone ").append(
                    zone(intel, dead.getGridX(), dead.getGridY())).append(" by");
            if (e.getValue().isEmpty())
                sb.append(" none=1");
            for (Map.Entry<String, Integer> by : e.getValue().entrySet())
                sb.append(' ').append(by.getKey()).append('=').append(by.getValue());
            ai.aiLog().log("HOME", sb::toString);
        }
        watched.clear();
        for (Unit e : intel.enemy_warriors)
            if (!e.isDead())
                watched.put(e, new TreeMap<>());
        for (Unit u : intel.warriors) {
            if (u.isDead() || !(u.getCurrentController() instanceof HuntController h)
                    || !(u.getCurrentBehaviour() instanceof AttackBehaviour))
                continue;
            Map<String, Integer> by = watched.get(h.getTarget());
            if (by == null)
                continue;
            String role = military.roleOf(u);
            by.merge("army".equals(role) ? "army:" + military.traceWhy(u) : String.valueOf(role), 1, Integer::sum);
        }
        for (Unit u : intel.peons) {
            if (u.isDead() || !(u.getCurrentController() instanceof HuntController h)
                    || !(u.getCurrentBehaviour() instanceof AttackBehaviour))
                continue;
            Map<String, Integer> by = watched.get(h.getTarget());
            if (by != null)
                by.merge("peon", 1, Integer::sum);
        }
        for (Building t : intel.towers) {
            if (!Intel.isTowerActive(t))
                continue;
            Unit gunner = Intel.gunner(t);
            if (gunner == null || !(gunner.getCurrentController() instanceof AttackController a))
                continue;
            Map<String, Integer> by = watched.get(a.getTarget());
            if (by != null)
                by.merge("tower", 1, Integer::sum);
        }
    }

    void tick() {
        if (!ai.logging())
            return;
        kills();
        if (!ai.periodDue(last_sample, 50f)) // 1 s
            return;
        last_sample = ai.now();
        Intel intel = ai.intel();
        Military military = ai.military();
        if (intel.warriors.isEmpty() && military.baseThreatLevel() == 0)
            return;
        Map<String, Integer> army = new TreeMap<>();
        int n = 0;
        for (Unit u : intel.warriors) {
            if (u.isDead() || !"army".equals(military.roleOf(u)))
                continue;
            n++;
            String act = u.getCurrentController() instanceof HuntController ? "fight" : u.getCurrentController() instanceof WalkController ? "walk" : "idle";
            army.merge(military.traceWhy(u) + "/" + zone(intel, u.getGridX(), u.getGridY()) + "/" + act, 1,
                    Integer::sum);
        }
        Map<String, Integer> foes = new TreeMap<>();
        List<Unit> enemies = new ArrayList<>(intel.enemy_warriors);
        float real = 0f;
        int tx = military.threatX();
        int ty = military.threatY();
        int level = military.baseThreatLevel();
        for (Unit e : enemies) {
            if (e.isDead())
                continue;
            if (level > 0 && MapAnalysis.dist2(tx, ty, e.getGridX(), e.getGridY()) <= 15 * 15)
                real += Combat.value(e);
            String zone = zone(intel, e.getGridX(), e.getGridY());
            if (!"far".equals(zone))
                foes.merge(zone + "/" + kind(e), 1, Integer::sum);
        }
        if (n == 0 && foes.isEmpty() && level == 0)
            return;
        int own = Integer.MAX_VALUE;
        if (level > 0)
            for (List<Building> list : List.of(intel.quarters, intel.armories, intel.towers))
                for (Building b : list)
                    if (!b.isDead())
                        own = Math.min(own, MapAnalysis.dist2(tx, ty, b.getGridX(), b.getGridY()));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "s lvl %d eng %d str %.1f base %.1f real %.1f at %d,%d own %d mode %s n %d post %d,%d |",
                level, military.engageState(), level > 0 ? military.threatStrength() : 0f,
                military.baseThreatStrength(),
                real, level > 0 ? tx : -1, level > 0 ? ty : -1, own == Integer.MAX_VALUE ? -1 : (int) Math.sqrt(own),
                military.modeName(), n, military.tracePostX(), military.tracePostY()));
        for (Map.Entry<String, Integer> e : army.entrySet())
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        sb.append(" |");
        for (Map.Entry<String, Integer> e : foes.entrySet())
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        ai.aiLog().log("HOME", sb::toString);
    }
}
