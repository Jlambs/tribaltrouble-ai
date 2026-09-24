package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.model.Race;
import org.jspecify.annotations.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A strategy is a named set of parameter defaults plus the opening build order. Modules read every tunable
 * through {@link #f}/{@link #i} once at construction. Map-size specific strategies (e.g. a future medium-map
 * strategy) only need to provide different defaults and opening orders; any default can be overridden from the
 * harness spec string.
 */
abstract class Strategy {
    private final @NonNull Params params;
    private final Map<String, Float> defaults = new LinkedHashMap<>();
    /** Defaults that apply instead when heavily outnumbered (at least multiN enemy players). */
    private final Map<String, Float> multi_defaults = new LinkedHashMap<>();
    private boolean multi;

    Strategy(@NonNull Params params) {
        this.params = params;
        defineDefaults();
    }

    abstract @NonNull String name();

    /** Building template ids to build, in order, before the opening ends (armory included). */
    abstract int @NonNull [] openingOrder(boolean threatened);

    protected abstract void defineDefaults();

    protected final void def(@NonNull String key, float value) {
        defaults.put(key, value);
    }

    /**
     * A default used instead of def()'s when there are at least multiN enemy players (an explicit value still wins).
     */
    protected final void defMulti(@NonNull String key, float value) {
        multi_defaults.put(key, value);
    }

    /** Called once the number of enemy players in the game is known (at the AI's first tick). */
    final void setEnemyCount(int enemies) {
        multi = enemies >= i("multiN");
    }

    final boolean multiEnemy() {
        return multi;
    }

    final float f(@NonNull String key) {
        Float d = defaults.get(key);
        if (d == null)
            throw new IllegalArgumentException("Unknown strategy parameter " + key);
        if (multi) {
            Float m = multi_defaults.get(key);
            if (m != null)
                d = m;
        }
        return params.get(key, d);
    }

    final int i(@NonNull String key) {
        return Math.round(f(key));
    }

    final boolean b(@NonNull String key) {
        return f(key) != 0f;
    }

    static @NonNull Strategy forMap(@NonNull Params params, int map_size) {
        return new LargeIslandStrategy(params);
    }

    static final int Q = Race.BUILDING_QUARTERS;
    static final int A = Race.BUILDING_ARMORY;
    static final int T = Race.BUILDING_TOWER;
}
