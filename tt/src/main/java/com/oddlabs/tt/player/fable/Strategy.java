package com.oddlabs.tt.player.fable;

import com.oddlabs.matchmaking.Game;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;

/**
 * A strategy is a named set of {@link Params} chosen for the map and game. The large-island 1v1 doctrine is the
 * reference; other map sizes scale the distances and the opening.
 */
public interface Strategy {
    @NonNull
    String name();

    @NonNull
    Params params();

    static @NonNull Strategy forWorld(@NonNull World world, @NonNull Player owner) {
        int size = world.getMapSize();
        int players = world.getPlayers().length;
        int enemies = 0;
        for (Player p : world.getPlayers())
            if (owner.isEnemy(p))
                enemies++;
        return switch (size) {
            case Game.SIZE_SMALL -> new Scaled("small-island", 0.5f, enemies);
            case Game.SIZE_MEDIUM -> new Scaled("medium-island", 0.75f, enemies);
            case Game.SIZE_ENORMOUS -> new Scaled("enormous-island", 1.4f, enemies);
            // crowded maps: the centre is where the other bases are, so the armory stays close to the start and
            // the site search may look in any direction
            default -> players >= 6 ? new Scaled("large-island-crowded", 0.6f, enemies,
                    true) : players > 2 ? new Scaled("large-island-ffa", 1f, enemies) : new LargeIsland1v1();
        };
    }

    /** The reference doctrine: large tropical island, one opponent. */
    final class LargeIsland1v1 implements Strategy {
        private final Params params = new Params();

        @Override
        public @NonNull String name() {
            return "large-island-1v1";
        }

        @Override
        public @NonNull Params params() {
            return params;
        }
    }

    /** Same doctrine with distances scaled; smaller maps shorten the opening and fight earlier. */
    final class Scaled implements Strategy {
        private final String name;
        private final Params params = new Params();

        Scaled(@NonNull String name, float scale, int enemies) {
            this(name, scale, enemies, false);
        }

        Scaled(@NonNull String name, float scale, int enemies, boolean crowded) {
            this.name = name;
            params.armory_max_dist = Math.round(params.armory_max_dist * scale);
            if (crowded) {
                params.armory_sector_deg = 180f;
                params.quarters_max_dist = Math.min(params.quarters_max_dist, 20);
                params.expansion_armory = false;
            }
            params.staging_dist = Math.max(10, Math.round(params.staging_dist * scale));
            params.leg_length = Math.max(15, Math.round(params.leg_length * scale));
            params.hunt_max_dist = Math.round(params.hunt_max_dist * scale);
            if (scale < 1f) {
                // the enemy is close: towers and warriors earlier, attack earlier
                params.attack_min_time = Math.round(params.attack_min_time * scale);
                params.chieftain_min_time = Math.round(params.chieftain_min_time * scale);
                params.quarters_first_crew = 14;
            }
            if (enemies > 1) {
                params.home_guard_early += 2;
                params.home_guard_mid += 2;
                params.home_guard_late += 2;
                params.attack_ratio += 0.3f;
            }
        }

        @Override
        public @NonNull String name() {
            return name;
        }

        @Override
        public @NonNull Params params() {
            return params;
        }
    }
}
