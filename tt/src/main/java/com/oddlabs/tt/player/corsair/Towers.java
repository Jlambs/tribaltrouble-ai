package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.MountUnitContainer;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fire control for our towers. A garrison scans for targets only within the tower's fixed range (TOWER_RANGE cells),
 * but it throws as far as its own range: tower +8 and whatever the deck drill gave the warrior inside. Given a target
 * by order (a right-click with the tower selected), a drilled garrison hits from well beyond its scan, at the tower's
 * triple accuracy. Each tower keeps a live target in reach; new ones go to the enemy worth most, spread over the
 * towers.
 */
final class Towers {
    private final @NonNull CorsairAI ai;
    private final @NonNull Player me;

    Towers(@NonNull CorsairAI ai) {
        this.ai = ai;
        this.me = ai.me();
    }

    static @Nullable Unit gunner(@NonNull Building tower) {
        if (tower.isDead() || !tower.isComplete() || tower.getUnitContainer() == null
                || tower.getUnitContainer().getNumSupplies() == 0)
            return null;
        if (!(tower.getUnitContainer() instanceof MountUnitContainer m))
            return null;
        Unit u = m.getUnit();
        return u == null || u.isDead() ? null : u;
    }

    void think(@NonNull Intel in) {
        if (!ai.params.tower_fire)
            return;
        Map<Unit, Integer> taken = new LinkedHashMap<>();
        for (Building t : in.towers) {
            Unit g = gunner(t);
            if (g == null || Chief.isStunned(g))
                continue;
            Unit current = null;
            if (g.getCurrentController() instanceof AttackController a && a.getTarget() instanceof Unit u)
                current = u;
            if (current != null && !current.isDead() && inReach(g, current)) {
                taken.merge(current, 1, Integer::sum);
                continue;
            }
            Unit best = null;
            float best_score = 0;
            for (Unit e : in.enemy_units) {
                if (e.isDead() || e.getIslandId() != t.getIslandId() || !inReach(g, e))
                    continue;
                float score = (e.isWarrior() || e.isChieftain() ? Intel.value(
                        e) : .3f) * (1f - e.getDefenseChance()) / (1 + taken.getOrDefault(e, 0));
                if (score > best_score) {
                    best_score = score;
                    best = e;
                }
            }
            if (best != null) {
                taken.merge(best, 1, Integer::sum);
                me.setTarget(Selectable.newArray(t), best, Action.ATTACK, false);
                ai.log.count("tower_fire");
            }
        }
    }

    /** Whether the garrison can throw at the unit from where it sits (its entry cell), with a cell to spare. */
    private static boolean inReach(@NonNull Unit g, @NonNull Unit e) {
        float r = g.getRange(e) - 1f;
        return Geo.dist2(g.getGridX(), g.getGridY(), e.getGridX(), e.getGridY()) <= (long) (r * r);
    }
}
