package com.oddlabs.tt.player.ai;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Rough fighting value of units, measured in iron warriors. Hits kill outright, so a unit's worth is roughly its hit
 * chance divided by the chance of being hit, with bonuses for chicken axes bouncing and towers throwing three times as
 * accurately from far away.
 */
final class Combat {
    // Measured in staged fights: 10 chicken warriors beat 17 iron ones and bounce through clumps, 10 rock beat 5
    // iron, 30 peons beat 5 iron.
    static final float IRON = 1f;
    static final float CHICKEN = 2f;
    static final float ROCK = .6f;
    static final float PEON = .2f;
    static final float CHIEFTAIN = 2.5f;
    static final float TOWER = 4f;

    static float value(@NonNull Unit unit) {
        if (Intel.isStunned(unit))
            return 0f;
        return lastingValue(unit);
    }

    /** Fighting value once any stun has worn off. */
    static float lastingValue(@NonNull Unit unit) {
        if (unit.isDead())
            return 0f;
        if (unit.getAbilities().hasAbilities(Abilities.MAGIC))
            return CHIEFTAIN * Math.max(.3f, unit.getHitPoints() / 60f);
        if (!unit.getAbilities().hasAbilities(Abilities.THROW))
            return PEON;
        return switch (Intel.warriorType(unit)) {
            case IRON -> IRON;
            case CHICKEN -> CHICKEN;
            case ROCK -> ROCK;
        };
    }

    static float towerValue(@NonNull Building tower) {
        if (!Intel.isTowerActive(tower))
            return 0f;
        return TOWER * Math.max(.35f, tower.getHitPoints() / (float) tower.getTemplate().getMaxHitPoints());
    }

    static float value(@NonNull Selectable<?> s) {
        if (s instanceof Unit unit)
            return value(unit);
        if (s instanceof Building b && b.getTemplate().getTemplateID() == Race.BUILDING_TOWER)
            return towerValue(b);
        return 0f;
    }

    /**
     * Sum of values of the units within radius (in grid cells) of a point.
     */
    static float strengthNear(List<? extends Selectable<?>> units, int x, int y, int radius) {
        int r2 = radius * radius;
        float sum = 0f;
        for (Selectable<?> s : units) {
            if (s.isDead())
                continue;
            if (MapAnalysis.dist2(x, y, s.getGridX(), s.getGridY()) <= r2)
                sum += value(s);
        }
        return sum;
    }

    static int countNear(List<? extends Selectable<?>> units, int x, int y, int radius) {
        int r2 = radius * radius;
        int n = 0;
        for (Selectable<?> s : units) {
            if (!s.isDead() && MapAnalysis.dist2(x, y, s.getGridX(), s.getGridY()) <= r2)
                n++;
        }
        return n;
    }

    /**
     * Lanchester's square law: fighting strength grows with the square of numbers, so compare squared sums when
     * judging who wins a straight-up fight.
     */
    static float advantage(float ours, float theirs) {
        return ours * ours - theirs * theirs;
    }

    private Combat() {
    }
}
