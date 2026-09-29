package com.oddlabs.tt.global;

/**
 * Whether this JVM only simulates games and never draws or plays them: set by {@code -Dcom.oddlabs.tt.headless=true},
 * which the aisim harness gives its worker JVMs. The game itself never sets it.
 *
 * <p>Headless, the engine skips what only drawing and sound read: the scene tree of what to draw, the height models
 * are drawn at and their bounding boxes, and GPU uploads. Everything the simulation computes stays bit for bit the
 * same, so a headless game plays exactly as the client plays it. The flag is a constant, so in the game the JIT
 * removes every headless branch.
 */
public final class Headless {
    public static final boolean ENABLED = Boolean.getBoolean("com.oddlabs.tt.headless");

    private Headless() {
    }
}
