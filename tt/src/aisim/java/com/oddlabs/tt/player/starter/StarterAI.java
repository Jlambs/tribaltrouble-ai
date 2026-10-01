package com.oddlabs.tt.player.starter;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.AiParams;
import com.oddlabs.tt.aikit.AiView;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The template of {@code ./aisim.sh new NAME}, which copies this file into the game's source set as AI {@code NAME}
 * (with its own class comment). Every build compiles it here, so a new AI always starts from code that compiles
 * against the current engine. As spec {@code starter} it plays too: it gives no orders, so any opponent beats it.
 * Keep it this small: it is boilerplate, and it must not suggest a design. NewAi relies on its layout: a class
 * comment starting a line with {@code /**}, and the line {@code public final class StarterAI}.
 */
public final class StarterAI extends AI implements AiView {
    private static final int BASE_COLOR = 0xFF_D0_40;
    private static final int UNITS_COLOR = 0x60_C0_FF;
    private static final float BASE_RADIUS = 8f;

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

    /**
     * What {@code ./aisim.sh watch} shows of this AI while its game is watched: status lines, and marks, lines and
     * circles on the map, in layers. Only READ here: no orders, no log, no random numbers, no state of your own
     * changed (the AI guide, AiView). Show your AI's plans as it grows them.
     */
    @Override
    public void paint(@NonNull Canvas canvas) {
        Player owner = getOwner();
        canvas.status("second", String.valueOf(ticks / GameTime.TICKS_PER_SECOND));
        canvas.status("units", String.valueOf(owner.getUnitCountContainer().getNumSupplies()));
        int start_x = UnitGrid.toGridCoordinate(owner.getStartX());
        int start_y = UnitGrid.toGridCoordinate(owner.getStartY());
        canvas.mark("base", start_x, start_y, BASE_COLOR, "start");
        canvas.circle("base", start_x, start_y, BASE_RADIUS, BASE_COLOR);
        long x = 0;
        long y = 0;
        int count = 0;
        for (Selectable<?> s : owner.getUnits().getSet()) {
            if (s instanceof Unit && !s.isDead()) {
                x += s.getGridX();
                y += s.getGridY();
                count++;
            }
        }
        if (count > 0) {
            int center_x = (int) (x / count);
            int center_y = (int) (y / count);
            canvas.line("units", start_x, start_y, center_x, center_y, UNITS_COLOR);
            canvas.mark("units", center_x, center_y, UNITS_COLOR, count + " units");
        }
    }

    /** What one of this AI's units or buildings is for, as the watch viewer's inspector shows it; null for nothing. */
    @Override
    public @Nullable String describe(@NonNull Selectable<?> selectable) {
        return "no task: this AI gives no orders yet";
    }
}
