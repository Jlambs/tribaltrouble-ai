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
    private final int crash;
    private final int hang;
    private final boolean nondet;
    private final boolean err;
    private final boolean count;
    private int ticks;

    public ChaosAI(@NonNull Player owner, @NonNull UnitInfo units, @NonNull String params) {
        super(owner, units);
        AiParams p = AiParams.parse(params);
        crash = p.getInt("crash", -1);
        hang = p.getInt("hang", -1);
        nondet = p.getBoolean("nondet", false);
        err = p.getBoolean("err", false);
        count = p.getBoolean("count", false);
        log = AiLog.of(owner);
        log.log("PARAM", p.done());
    }

    @Override
    public void animate(float t) {
        if (++ticks % GameTime.TICKS_PER_SECOND != 0) { // act once per game second
            return;
        }
        int second = ticks / GameTime.TICKS_PER_SECOND;
        if (count) {
            log.count("second");
        }
        if (second == crash) {
            throw new IllegalStateException("chaos: crash at " + second + " s");
        }
        if (second == hang) {
            while (ticks > 0) { // always true: spins until the worker's hang watchdog kills it
                Thread.onSpinWait();
            }
        }
        if (err && second % 60 == 0) {
            log.error("chaos", new IllegalStateException("chaos: error at " + second + " s"));
        }
        if (nondet && second % 5 == 0) {
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
