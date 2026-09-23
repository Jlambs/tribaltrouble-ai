package com.oddlabs.tt.player.ai.sim;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a group of warriors closes in on enemies it is about to fight. Warriors throw from about four cells away and
 * cannot shoot through a crowd of their own, so a group that arrives as one tight clump only fights with its edge
 * while an enemy line around it fights with everyone. Spreading out along the enemy front fixes that.
 */
final class Tactics {
    public enum Engage {
        /** Everyone attack-moves to the middle of the enemy group. */
        CLUMP,
        /** Everyone attack-moves to the enemy nearest to them, fanning out along the enemy front. */
        SPREAD,
        /** Everyone takes a place on a line across the enemy's front, then attack-moves through it. */
        LINE
    }

    /**
     * Orders the given (not already fighting) units to engage the enemies. Orders are grouped so units heading to
     * the same spot get separate cells.
     */
    static void engage(@NonNull Player owner, @NonNull List<@NonNull Unit> units,
            @NonNull List<@NonNull Unit> enemies, @NonNull Engage how) {
        List<Unit> live = new ArrayList<>();
        for (Unit e : enemies)
            if (!e.isDead())
                live.add(e);
        if (live.isEmpty() || units.isEmpty())
            return;
        int[] ec = centroid(live);
        switch (how) {
            case CLUMP -> owner.setLandscapeTarget(units.toArray(new Selectable<?>[0]), ec[0], ec[1], Action.ATTACK,
                    true);
            case SPREAD -> {
                Map<Long, List<Unit>> groups = new LinkedHashMap<>();
                for (Unit u : units) {
                    Unit nearest = nearest(live, u.getGridX(), u.getGridY());
                    long key = ((long) nearest.getGridX() << 32) | nearest.getGridY();
                    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(u);
                }
                for (Map.Entry<Long, List<Unit>> g : groups.entrySet()) {
                    int x = (int) (g.getKey() >> 32);
                    int y = (int) (g.getKey() & 0xffffffffL);
                    owner.setLandscapeTarget(g.getValue().toArray(new Selectable<?>[0]), x, y, Action.ATTACK, true);
                }
            }
            case LINE -> {
                int[] oc = centroid(units);
                float dx = ec[0] - oc[0];
                float dy = ec[1] - oc[1];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1f) {
                    engage(owner, units, live, Engage.SPREAD);
                    return;
                }
                dx /= len;
                dy /= len;
                // Across the front, left to right as seen from our side.
                float px = -dy;
                float py = dx;
                List<Unit> sorted = new ArrayList<>(units);
                sorted.sort((a, b) -> Float.compare(a.getGridX() * px + a.getGridY() * py,
                        b.getGridX() * px + b.getGridY() * py));
                // The line is as wide as the enemy group, at least two cells per warrior.
                float width = 0f;
                for (Unit e : live)
                    width = Math.max(width, Math.abs((e.getGridX() - ec[0]) * px + (e.getGridY() - ec[1]) * py));
                float spacing = Math.max(1.6f, 2 * width / Math.max(1, sorted.size() - 1));
                spacing = Math.min(spacing, 2.5f);
                int n = sorted.size();
                for (int i = 0; i < n; i++) {
                    float offset = (i - (n - 1) / 2f) * spacing;
                    int x = Math.round(ec[0] + px * offset);
                    int y = Math.round(ec[1] + py * offset);
                    owner.setLandscapeTarget(Selectable.newArray(sorted.get(i)), x, y, Action.ATTACK, true);
                }
            }
        }
    }

    static @NonNull Unit nearest(@NonNull List<@NonNull Unit> units, int x, int y) {
        Unit best = units.getFirst();
        int best_d = Integer.MAX_VALUE;
        for (Unit u : units) {
            int d = dist2(x, y, u.getGridX(), u.getGridY());
            if (d < best_d) {
                best_d = d;
                best = u;
            }
        }
        return best;
    }

    private static int @NonNull [] centroid(@NonNull List<@NonNull Unit> units) {
        long sx = 0;
        long sy = 0;
        for (Unit u : units) {
            sx += u.getGridX();
            sy += u.getGridY();
        }
        int n = Math.max(1, units.size());
        return new int[]{(int) (sx / n), (int) (sy / n)};
    }

    private static int dist2(int x0, int y0, int x1, int y1) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        return dx * dx + dy * dy;
    }

    private Tactics() {
    }
}
