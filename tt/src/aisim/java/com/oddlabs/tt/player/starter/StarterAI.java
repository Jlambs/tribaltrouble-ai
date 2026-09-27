package com.oddlabs.tt.player.starter;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.AiParams;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import org.jspecify.annotations.NonNull;

/**
 * The template of {@code ./aisim.sh new NAME}, which copies this file into the game's source set as AI {@code NAME}
 * (with its own class comment). Every build compiles it here, so a new AI always starts from code that compiles
 * against the current engine. As spec {@code starter} it plays too: it gives no orders, so any opponent beats it.
 * Keep it this small: it is boilerplate, and it must not suggest a design. NewAi relies on its layout: a class
 * comment starting a line with {@code /**}, and the line {@code public final class StarterAI}.
 */
public final class StarterAI extends AI {
    private final @NonNull AiLog log;
    private int ticks;

    public StarterAI(@NonNull Player owner, @NonNull UnitInfo units, @NonNull String spec_params) {
        super(owner, units); // first: registers this AI and creates the starting units
        AiParams params = AiParams.parse(spec_params);
        // Read params into final fields here, for example boolean rush = params.getBoolean("rush", false); the spec
        // NAME:rush=1 then turns it on. The defaults should be your best known values: batches compare against them.
        log = AiLog.of(owner);
        log.log("PARAM", params.done()); // fails on unknown keys, so a typo cannot silently play the defaults
    }

    /** Runs every tick; deciding once a game second is plenty to start with. */
    @Override
    public void animate(float t) {
        if (++ticks % GameTime.TICKS_PER_SECOND != 0) {
            return;
        }
        try {
            think(ticks / GameTime.TICKS_PER_SECOND);
        } catch (RuntimeException | AssertionError e) {
            // engine getters assert on units that just died, and the harness runs with assertions on: count the
            // error (every result row shows it), log the first stack traces, and let the game go on
            log.error("think", e);
        }
    }

    /**
     * One round of decisions at game second {@code second}: read the world, then give orders only through the
     * PlayerInterface methods of {@code getOwner()} (tt/src/main/java/com/oddlabs/tt/player/AGENTS.md lists them).
     */
    private void think(int second) {
        if (second % 30 == 0) {
            log.log("STAT", () -> "units " + getOwner().getUnitCountContainer().getNumSupplies());
        }
    }
}
