package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.landscape.LandscapeTarget;
import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.Behaviour;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.HarvestBehaviour;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.MagicController;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.behaviour.StunController;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.util.Target;
import com.oddlabs.tt.pathfinder.Occupant;
import com.oddlabs.tt.pathfinder.UnitGrid;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orders given on exact ticks, run every world tick (ported from the gauntlet AI's Reflexes, where they were measured):
 * both are orders to our own units that the UI can give, what makes them pay is their timing.
 *
 * <p><b>Swing restart.</b> A peon's harvest swing credits its hit once the swing passes the weapon's release point and
 * stays interruptible to the end of the cycle (HarvestBehaviour.animate). Ordering the peon to the same supply right
 * after the credit starts a new swing at once, so a viking peon hits every 15 ticks instead of 51. Construction chops
 * trees under a RepairController: the order goes to the building. The swing's time is summed in game seconds exactly
 * as HarvestBehaviour sums its anim_time, so it is due on the tick of the hit at every speed.
 *
 * <p><b>Stun cancel.</b> Unit.stun pushes a StunController that has not animated yet; an order in the same tick
 * replaces it before it runs. Each stunned unit is ordered back to what it was doing.
 */
final class Reflexes {
    private final @NonNull Player me;
    private final @NonNull AiLog log;
    private final boolean swing_restart;
    private final boolean stun_cancel;
    private final @NonNull CorsairAI ai;
    /** Per tower, the controller its garrison had on top in the previous tick. */
    private final Map<com.oddlabs.tt.model.@NonNull Building, @NonNull Controller> tower_last = new LinkedHashMap<>();
    private final Map<@NonNull Unit, @NonNull Swing> swings = new LinkedHashMap<>();
    private final List<@NonNull Unit> due = new ArrayList<>();
    private final List<@NonNull Unit> stunned = new ArrayList<>();
    private int tick;

    private static final class Swing {
        final @NonNull Behaviour behaviour;
        float elapsed;

        Swing(@NonNull Behaviour behaviour) {
            this.behaviour = behaviour;
        }
    }

    Reflexes(@NonNull CorsairAI ai, @NonNull Player me, @NonNull AiLog log, boolean swing_restart,
            boolean stun_cancel) {
        this.ai = ai;
        this.me = me;
        this.log = log;
        this.swing_restart = swing_restart;
        this.stun_cancel = stun_cancel;
    }

    /** {@code step}: the game seconds this world tick covers (0 while paused). */
    void tick(float step) {
        if (!swing_restart && !stun_cancel)
            return;
        if (step <= 0f)
            return;
        tick++;
        if (ai.params.tower_unstun)
            towerUnstun();
        due.clear();
        stunned.clear();
        for (Selectable<?> s : me.getUnits().getSet()) {
            if (!(s instanceof Unit u) || u.isDead() || u.isMounted())
                continue;
            if (stun_cancel && u.getCurrentController() instanceof StunController) {
                stunned.add(u);
                continue;
            }
            if (!swing_restart)
                continue;
            Behaviour b = u.getCurrentBehaviour();
            if (!(b instanceof HarvestBehaviour))
                continue;
            Swing sw = swings.get(u);
            if (sw == null || sw.behaviour != b) {
                swings.put(u, new Swing(b));
                continue;
            }
            sw.elapsed += step;
            if (sw.elapsed > u.getWeaponFactory().getSecondsPerRelease(1f))
                due.add(u);
        }
        for (Unit u : stunned)
            unstun(u);
        for (Unit u : due)
            restart(u);
        if (tick % 250 == 0)
            swings.keySet().removeIf(Unit::isDead);
    }

    /**
     * A stun also lands on a tower's garrison. An order to the tower pushes an AttackController on the garrison without
     * clearing its stack, so ordered on the tick its StunController comes on top it decides at once and throws on; the
     * stun waits underneath (the gauntlet AI's tower_unstun).
     */
    private void towerUnstun() {
        for (com.oddlabs.tt.model.Building t : ai.intel.towers) {
            Unit g = Towers.gunner(t);
            if (g == null)
                continue;
            Controller current = g.getCurrentController();
            Controller previous = tower_last.put(t, current);
            if (!(current instanceof StunController) || current == previous)
                continue;
            Unit target = Towers.bestTarget(ai.intel, t, g);
            if (target == null)
                continue;
            me.setTarget(Selectable.newArray(t), target, Action.ATTACK, false);
            log.count("tower_unstun");
        }
        if (tick % 250 == 0)
            tower_last.keySet().removeIf(com.oddlabs.tt.model.Building::isDead);
    }

    private void restart(@NonNull Unit u) {
        swings.remove(u);
        if (u.isDead() || u.isMounted() || !(u.getCurrentBehaviour() instanceof HarvestBehaviour))
            return;
        Controller primary = u.getPrimaryController();
        if (primary instanceof GatherController<?> gather) {
            Supply supply = gather.getSupply();
            if (supply == null)
                supply = adjacentSupply(u, gather.getSupplyType()); // gatherers from an armory carry none
            if (supply == null || supply.isDead())
                return;
            me.setTarget(Selectable.newArray(u), supply, Action.GATHER_REPAIR, false);
            log.count("swing_restart");
        } else if (primary instanceof RepairController repair) {
            Building b = repair.getBuilding();
            if (b == null || b.isDead())
                return;
            me.setTarget(Selectable.newArray(u), b, Action.GATHER_REPAIR, false);
            log.count("swing_restart_build");
        }
    }

    /** The supply of a type on a cell next to the unit (the one it is chopping), or null. */
    private @Nullable Supply adjacentSupply(@NonNull Unit u, @NonNull Class<?> type) {
        UnitGrid grid = u.getUnitGrid();
        int size = grid.getGridSize();
        Supply best = null;
        long bd = Long.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = u.getGridX() + dx, y = u.getGridY() + dy;
                if (x < 0 || y < 0 || x >= size || y >= size)
                    continue;
                Occupant occ = grid.getOccupant(x, y);
                if (occ instanceof Supply s && type.isInstance(s) && !s.isEmpty()) {
                    long d = (long) dx * dx + (long) dy * dy;
                    if (d < bd) {
                        bd = d;
                        best = s;
                    }
                }
            }
        }
        return best;
    }

    private void unstun(@NonNull Unit u) {
        Controller primary = u.getPrimaryController();
        if (primary instanceof MagicController)
            return;
        Target target = null;
        Action action = Action.DEFAULT;
        boolean aggressive = false;
        if (primary instanceof GatherController<?> gather && gather.getSupply() != null) {
            target = gather.getSupply();
            action = Action.GATHER_REPAIR;
        } else if (primary instanceof RepairController repair && repair.getBuilding() != null) {
            target = repair.getBuilding();
            action = Action.GATHER_REPAIR;
        } else if (primary instanceof PlaceBuildingController place && place.getBuilding() != null) {
            target = place.getBuilding();
        } else if (primary instanceof WalkController walk) {
            target = walk.getTarget();
            aggressive = walk.isAgressive();
            action = aggressive ? Action.DEFAULT : Action.MOVE;
        } else if (primary instanceof HuntController hunt) {
            target = hunt.getTarget();
            action = Action.ATTACK;
        } else if (primary instanceof AttackController attack) {
            target = attack.getTarget();
            action = Action.ATTACK;
        }
        if (target == null || target.isDead() || target == u) {
            boolean fighter = !u.getAbilities().hasAbilities(Abilities.BUILD);
            target = new LandscapeTarget(u.getGridX(), u.getGridY());
            action = fighter ? Action.ATTACK : Action.MOVE;
            aggressive = fighter;
        }
        me.setTarget(Selectable.newArray(u), target, action, aggressive);
        log.count("stun_cancel");
    }
}
