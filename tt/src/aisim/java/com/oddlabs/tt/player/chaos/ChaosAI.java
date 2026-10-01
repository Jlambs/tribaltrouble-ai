package com.oddlabs.tt.player.chaos;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.AiParams;
import com.oddlabs.tt.aikit.AiView;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Test fixture for the harness itself (spec {@code chaos}); it lives in the aisim source set and never ships. It
 * builds nothing, so any real opponent beats it. Params: {@code crash=S} throws at second S, {@code hang=S} spins
 * forever at second S, {@code nondet=1} moves units in {@code classifyUnits()} group order (an identity-hash order,
 * so replays in other JVMs diverge), {@code err=1} reports a swallowed error every minute, {@code count=1} counts
 * seconds. For the watch viewer's AI overlay ({@link AiView}), {@code paint=draw} paints a line from its start through
 * the map's centre, a circle, and a labelled mark on every unit; {@code paint=throw} paints that, then fails every
 * other game
 * second (describe always fails); {@code paint=heavy} paints far more shapes than the overlay takes;
 * {@code paint=cheat} paints and draws from the world's random generator, which changes the game: the viewer must show
 * it as DIVERGED. docs/maintaining.md uses it to check the harness.
 */
public final class ChaosAI extends AI implements AiView {
    /** paint=heavy paints this many lines: more than the overlay draws. */
    private static final int HEAVY_LINES = 6_000;

    private final @NonNull AiLog log;
    private final int crash_second;
    private final int hang_second;
    private final boolean nondeterministic;
    private final boolean report_errors;
    private final boolean count_seconds;
    private final @NonNull String paint_mode;
    private int ticks;

    public ChaosAI(@NonNull Player owner, @NonNull UnitInfo units, @NonNull String spec_params) {
        super(owner, units);
        AiParams params = AiParams.parse(spec_params);
        crash_second = params.getInt("crash", -1);
        hang_second = params.getInt("hang", -1);
        nondeterministic = params.getBoolean("nondet", false);
        report_errors = params.getBoolean("err", false);
        count_seconds = params.getBoolean("count", false);
        paint_mode = params.getString("paint", "");
        log = AiLog.of(owner);
        log.log("PARAM", params.done());
    }

    @Override
    public void animate(float t) {
        if (++ticks % GameTime.TICKS_PER_SECOND != 0) { // act once per game second
            return;
        }
        int second = ticks / GameTime.TICKS_PER_SECOND;
        if (count_seconds) {
            log.count("second");
        }
        if (second == crash_second) {
            throw new IllegalStateException("chaos: crash at " + second + " s");
        }
        if (second == hang_second) {
            while (ticks > 0) { // always true: spins until the worker's hang watchdog kills it
                Thread.onSpinWait();
            }
        }
        if (report_errors && second % 60 == 0) {
            log.error("chaos", new IllegalStateException("chaos: error at " + second + " s"));
        }
        if (nondeterministic && second % 5 == 0) {
            Selectable<?>[][] groups = getOwner().classifyUnits();
            int x = UnitGrid.toGridCoordinate(getOwner().getStartX());
            int y = UnitGrid.toGridCoordinate(getOwner().getStartY());
            for (int i = 0; i < groups.length; i++) {
                // each group gets its own target, so the order of the groups shows in where units go
                getOwner().setLandscapeTarget(groups[i], x + 8 * i - 8, y + 4 * i, Action.MOVE, false);
            }
        }
        if (second % 30 == 0) {
            log.log("STAT", () -> "second " + second + " units " + getOwner().getUnitCountContainer().getNumSupplies());
        }
    }

    @Override
    public void paint(@NonNull Canvas canvas) {
        if (paint_mode.isEmpty()) {
            return;
        }
        int second = ticks / GameTime.TICKS_PER_SECOND;
        canvas.status("second", String.valueOf(second));
        canvas.status("paint", paint_mode);
        int size = getOwner().getWorld().getHeightMap().getGridUnitsPerWorld();
        int x = UnitGrid.toGridCoordinate(getOwner().getStartX());
        int y = UnitGrid.toGridCoordinate(getOwner().getStartY());
        canvas.line("reach", x, y, size - 1 - x, size - 1 - y, 0xFF_40_40); // through the map's centre
        canvas.circle("reach", size / 2, size / 2, size / 4f, 0xFF_80_80);
        int index = 0;
        for (Selectable<?> s : getOwner().getUnits().getSet()) {
            if (!s.isDead()) {
                canvas.mark("units", s.getGridX(), s.getGridY(), 0x80_FF_80, "u" + index++);
            }
        }
        switch (paint_mode) {
            case "throw" -> {
                if (second % 2 == 1) {
                    throw new IllegalStateException("chaos: paint fails at odd seconds");
                }
            }
            case "heavy" -> {
                for (int i = 0; i < HEAVY_LINES; i++) {
                    canvas.line("heavy", i % size, 0, (i * 7) % size, size - 1, 0x40_40_FF);
                }
            }
            case "cheat" -> getOwner().getWorld().getRandom().nextInt(); // what a painter must never do
            default -> {
            }
        }
    }

    @Override
    public @Nullable String describe(@NonNull Selectable<?> selectable) {
        if (paint_mode.equals("throw")) {
            throw new IllegalStateException("chaos: describe fails");
        }
        return paint_mode.isEmpty() ? null : "chaos " + paint_mode;
    }
}
