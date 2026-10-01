package com.oddlabs.tt.player.chaos;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.AiParams;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import org.jspecify.annotations.NonNull;

/**
 * Test fixture for the harness itself (spec {@code chaos}); it lives in the aisim source set and never ships. It
 * builds nothing, so any real opponent beats it. Params: {@code crash=S} throws at second S, {@code hang=S} spins
 * forever at second S, {@code nondet=1} moves units in {@code classifyUnits()} group order (an identity-hash order,
 * so replays in other JVMs diverge), {@code err=1} reports a swallowed error every minute, {@code count=1} counts
 * seconds. docs/aisim.md uses it to check the harness.
 */
public final class ChaosAI extends AI {
    private final @NonNull AiLog log;
    private final int crash_second;
    private final int hang_second;
    private final boolean nondeterministic;
    private final boolean report_errors;
    private final boolean count_seconds;
    private final GameTime.@NonNull Every every_second = new GameTime.Every(1000);

    public ChaosAI(@NonNull Player owner, @NonNull UnitInfo units, @NonNull String spec_params) {
        super(owner, units);
        AiParams params = AiParams.parse(spec_params);
        crash_second = params.getInt("crash", -1);
        hang_second = params.getInt("hang", -1);
        nondeterministic = params.getBoolean("nondet", false);
        report_errors = params.getBoolean("err", false);
        count_seconds = params.getBoolean("count", false);
        log = AiLog.of(owner);
        log.log("PARAM", params.done());
    }

    @Override
    public void animate(float t) {
        if (!every_second.due(getOwner().getWorld())) { // act once per game second
            return;
        }
        int second = (int) (every_second.struck() / 1000);
        if (count_seconds) {
            log.count("second");
        }
        if (second == crash_second) {
            throw new IllegalStateException("chaos: crash at " + second + " s");
        }
        if (second == hang_second) {
            while (every_second.struck() > 0) { // always true: spins until the worker's hang watchdog kills it
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
}
