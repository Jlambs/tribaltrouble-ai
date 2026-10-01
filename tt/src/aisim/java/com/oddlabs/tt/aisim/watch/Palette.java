package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.global.Settings;
import com.oddlabs.util.Color;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;

/**
 * Player colours for up to 32 players, all distinct: the player's own team colours (the menu's 12), then the six more
 * of the game's hand-picked colours the menu leaves out, then evenly spaced hues. Colours only draw the game; the
 * simulation never reads them.
 */
final class Palette {
    /** Settings' hand-picked colours 12 to 17 (navy, coral, gold, magenta, forest green, slate). */
    private static final int[] MORE = {0xFF000080, 0xFFFF7F50, 0xFFFFD700, 0xFFFF00FF, 0xFF228B22, 0xFF708090};

    private Palette() {
    }

    static @NonNull Vector4fc @NonNull [] colors(int players) {
        Vector4fc[] own = Settings.getSettings().team_colours;
        Vector4fc[] colors = new Vector4fc[players];
        int generated = Math.max(players - own.length - MORE.length, 1);
        for (int slot = 0; slot < players; slot++) {
            if (slot < own.length) {
                colors[slot] = own[slot];
            } else if (slot < own.length + MORE.length) {
                colors[slot] = Color.argb4v(MORE[slot - own.length]);
            } else {
                int index = slot - own.length - MORE.length;
                int rgb = java.awt.Color.HSBtoRGB(index / (float) generated + .05f, .75f, .95f);
                colors[slot] = Color.argb4v(0xFF000000 | (rgb & 0x00FFFFFF));
            }
        }
        return colors;
    }
}
