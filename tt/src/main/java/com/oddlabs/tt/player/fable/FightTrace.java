package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.Behaviour;
import com.oddlabs.tt.model.behaviour.HuntController;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Log only: what the warriors of both sides do while an opposing warrior is near, one FIGHT line a minute. Every second
 * each warrior outside a tower with an opposing warrior within 8, 14 or 30 cells counts a warrior-second in that zone
 * under what it does: throwing, walking up to its hunt's target (close), walking otherwise, blocked on its walk, idle,
 * stunned. Ours are split into the attack (roles attack and reinforce) and the rest (home).
 */
final class FightTrace {
    private static final String[] GROUPS = {"attack", "home", "enemy"};
    private static final int[] ZONES = {8, 14, 30};
    private static final String[] STATES = {"throw", "close", "walk", "blocked", "idle", "stunned", "other"};
    private static final int BUCKET = 32;

    private final @NonNull FableAI ai;
    private final int[][][] counts = new int[GROUPS.length][ZONES.length][STATES.length];
    private float last_sample;
    private float last_log;

    FightTrace(@NonNull FableAI ai) {
        this.ai = ai;
    }

    void tick() {
        if (!ai.logging() || !ai.periodDue(last_sample, 50f)) // 1 s
            return;
        last_sample = ai.now();
        Intel intel = ai.intel();
        Map<Integer, List<Unit>> ours = buckets(intel.warriors);
        Map<Integer, List<Unit>> theirs = buckets(intel.enemy_warriors);
        for (Unit u : intel.warriors) {
            if (u.isDead())
                continue;
            String role = ai.military().roleOf(u);
            sample("attack".equals(role) || "reinforce".equals(role) ? 0 : 1, u, theirs);
        }
        for (Unit u : intel.enemy_warriors)
            if (!u.isDead())
                sample(2, u, ours);
        if (ai.periodDue(last_log, 3000f)) { // 60 s
            last_log = ai.now();
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            for (int g = 0; g < GROUPS.length; g++)
                for (int z = 0; z < ZONES.length; z++) {
                    sb.append(g + z == 0 ? "" : " | ").append(GROUPS[g]).append(" in").append(ZONES[z]).append(':');
                    for (int s = 0; s < STATES.length; s++) {
                        sb.append(' ').append(STATES[s]).append(' ').append(counts[g][z][s]);
                        any |= counts[g][z][s] > 0;
                        counts[g][z][s] = 0;
                    }
                }
            if (any)
                ai.aiLog().log("FIGHT", sb::toString);
        }
    }

    private static @NonNull Map<Integer, List<Unit>> buckets(@NonNull List<@NonNull Unit> units) {
        Map<Integer, List<Unit>> map = new LinkedHashMap<>();
        for (Unit u : units)
            if (!u.isDead())
                map.computeIfAbsent((u.getGridY() / BUCKET) * 4096 + u.getGridX() / BUCKET, k -> new ArrayList<>()).add(
                        u);
        return map;
    }

    private void sample(int group, @NonNull Unit u, @NonNull Map<Integer, List<Unit>> others) {
        int bx = u.getGridX() / BUCKET;
        int by = u.getGridY() / BUCKET;
        int best = Integer.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                List<Unit> list = others.get((by + dy) * 4096 + bx + dx);
                if (list == null)
                    continue;
                for (Unit o : list)
                    best = Math.min(best, MapAnalysis.dist2(u.getGridX(), u.getGridY(), o.getGridX(), o.getGridY()));
            }
        int zone = -1;
        for (int z = ZONES.length - 1; z >= 0; z--)
            if (best <= ZONES[z] * ZONES[z])
                zone = z;
        if (zone < 0)
            return;
        Behaviour b = u.getCurrentBehaviour();
        String name = b == null ? "" : b.getClass().getSimpleName();
        int state = switch (name) {
            case "AttackBehaviour" -> 0;
            case "WalkBehaviour" -> b.isBlocking() ? 3 : u.getCurrentController() instanceof HuntController ? 1 : 2;
            case "IdleBehaviour" -> 4;
            case "StunBehaviour" -> 5;
            default -> 6;
        };
        counts[group][zone][state]++;
    }
}
