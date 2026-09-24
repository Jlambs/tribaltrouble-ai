package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Battle strength estimates on an iron-warrior-equivalent scale. Fights follow Lanchester's square law closely
 * because every unit has 1 HP and fires independently, so side strength is sqrt(sum of squares) of groups.
 */
final class CombatModel {
    private CombatModel() {
    }

    static float weight(WorldModel.@NonNull Kind kind) {
        return switch (kind) {
            case ROCK -> 0.63f;
            case IRON -> 1.0f;
            case RUBBER -> 1.35f;
            case PEON -> 0.05f;
            case CHIEFTAIN -> 1.5f;
        };
    }

    static float weight(@NonNull Unit u) {
        return weight(WorldModel.kindOf(u));
    }

    /** Tower strength by garrison type (fitted to Monte Carlo runs of towers against iron warriors). */
    static float towerValue(@NonNull Building tower) {
        Unit g = WorldModel.garrisonOf(tower);
        // An empty tower, or one whose garrison is (visibly) stunned, does not shoot.
        if (g == null || WorldModel.isStunned(g))
            return 0f;
        float hp = tower.getHitPoints() / 100f;
        float base = switch (WorldModel.kindOf(g)) {
            case RUBBER -> 16f;
            case IRON -> 11f;
            default -> 7.5f;
        };
        return base * (0.4f + 0.6f * hp);
    }

    static float sumWeights(@NonNull List<Unit> units) {
        float e = 0f;
        for (Unit u : units)
            e += weight(u);
        return e;
    }

    /** Combined strength of units plus towers. */
    static float strength(float units, float tower_sq_sum) {
        return (float) Math.sqrt(units * units + tower_sq_sum);
    }

    /** Hit chance of attacker a against target t, including stun (defense 0) and height advantage. */
    static float hitChance(@NonNull Unit a, @NonNull Unit t, @Nullable MapAnalysis map) {
        float h = switch (WorldModel.kindOf(a)) {
            case ROCK -> 0.5f;
            case IRON -> 0.75f;
            case RUBBER -> 0.95f;
            case PEON -> 0.2f;
            case CHIEFTAIN -> 0.75f;
        };
        if (map != null) {
            float dz = map.height(a.getGridX(), a.getGridY()) - map.height(t.getGridX(), t.getGridY());
            h += Math.max(-0.25f, Math.min(0.25f, dz * 0.0125f));
        }
        // The unit's base defense, or none while it is visibly stunned. (The engine's live value also depends on
        // whether the owner re-ordered a stunned unit, which is not visible.)
        float def = WorldModel.isStunned(t) ? 0f : t.getTemplate().getDefenseChance();
        return Math.max(0f, Math.min(1f, h * (1f - def)));
    }

    /** Expected damage per throw against a building (defense 0). */
    static float buildingDamage(@NonNull Unit a) {
        return switch (WorldModel.kindOf(a)) {
            case ROCK -> 0.5f;
            case IRON -> 1.5f;
            case RUBBER -> 3.8f;
            case PEON, CHIEFTAIN -> 0.2f;
        };
    }
}
