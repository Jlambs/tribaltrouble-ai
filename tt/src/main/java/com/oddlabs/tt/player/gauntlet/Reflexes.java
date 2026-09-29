package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.aikit.GameTime;
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
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orders given on exact ticks, run every world tick. Both are orders to our own units that the UI can give; what
 * makes them pay is their timing, as the owner's exploit audit found (tribaltrouble-bench/exploit-audit, A26 and
 * K1/A11; the audit's engine differed in its stun rules, this one has no stun break window).
 *
 * <p><b>Swing restart (A26, K3).</b> A peon's harvest swing credits its hit once the swing passes the weapon's release
 * point (0.29 s of the 1 s cycle for viking peons, 0.61 s for natives) and stays interruptible to the end of the
 * cycle (HarvestBehaviour.animate). Ordering the peon to the same supply right after the credit starts a new swing at
 * once, so it hits every 15 ticks instead of 51 (vikings; 31 for natives). The hit counter lives in the supply, so
 * the restart loses nothing. For construction the peon chops trees under its RepairController: the order goes to the
 * building instead. A swing seen first at our tick T was made in tick T (a controller's decide, or our own order) and
 * first animates in tick T+1, since the AI runs after the units in each world tick (World.tick: game-time pass, then
 * the real-time pass the AI is on); so its hit lands in the game-time pass of tick T+n, and our order at T+n comes
 * right after it. Seen late, the order is late, never early (an early order voids the swing).
 *
 * <p><b>Stun cancel (K1).</b> Unit.stun pushes a StunController and makes it decide at once, which sets a StunBehaviour
 * that has not animated yet (Selectable.forceDecide leaves the unit interruptible). An order in the same tick clears
 * the controller stack and decides at once, replacing the StunBehaviour before it ever runs: the stun is gone. Each
 * stunned unit is ordered back to what it was doing, from the controllers under the stun.
 *
 * <p><b>Tower stun cancel.</b> A stun also lands on a tower's garrison (Stun.animate). An order to the tower pushes an
 * AttackController on the garrison (LandBuilding.setTarget) without clearing its stack, so ordered on the tick the
 * StunController comes on top (the stun landed, or the attack above it just ended) it decides at once and the garrison
 * throws on; the stun waits under it for the next time nothing is in reach. Orders on a stun that already runs are
 * deferred by the engine, so each tower is ordered only on the tick its StunController becomes current.
 */
final class Reflexes {
    /** Seconds a world tick lasts, as HarvestBehaviour counts them. */
    private static final float TICK_SECONDS = 1f / GameTime.TICKS_PER_SECOND;

    private final @NonNull GauntletAI ai;
    private final boolean swing_restart;
    private final boolean stun_cancel;
    private final boolean tower_unstun;
    /** Per tower, the controller its garrison had on top in the previous tick. */
    private final Map<@NonNull Building, @NonNull Controller> tower_last = new LinkedHashMap<>();
    /** The swing each harvesting peon is in, and the tick we first saw it. */
    private final Map<@NonNull Unit, @NonNull Swing> swings = new LinkedHashMap<>();
    /** Ticks from the start of a swing to its hit, per release time (the peons of one race share it). */
    private final Map<Float, Integer> release_ticks = new LinkedHashMap<>();
    private final List<@NonNull Unit> due = new ArrayList<>();
    private final List<@NonNull Unit> stunned = new ArrayList<>();
    private int tick;

    private static final class Swing {
        final @NonNull Behaviour behaviour;
        final int seen;

        Swing(@NonNull Behaviour behaviour, int seen) {
            this.behaviour = behaviour;
            this.seen = seen;
        }
    }

    Reflexes(@NonNull GauntletAI ai, boolean swing_restart, boolean stun_cancel, boolean tower_unstun) {
        this.ai = ai;
        this.swing_restart = swing_restart;
        this.stun_cancel = stun_cancel;
        this.tower_unstun = tower_unstun;
    }

    void tick() {
        if (!swing_restart && !stun_cancel && !tower_unstun)
            return;
        tick++;
        if (tower_unstun)
            towerUnstun();
        Player me = ai.owner();
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
                swings.put(u, new Swing(b, tick));
                continue;
            }
            if (tick - sw.seen >= releaseTicks(u))
                due.add(u);
        }
        for (Unit u : stunned)
            unstun(u);
        for (Unit u : due)
            restart(u);
        if (tick % 250 == 0)
            swings.keySet().removeIf(Unit::isDead);
    }

    private int releaseTicks(@NonNull Unit u) {
        float release = u.getWeaponFactory().getSecondsPerRelease(1f);
        Integer n = release_ticks.get(release);
        if (n == null) {
            // Count as HarvestBehaviour does: the hit comes on the first animate whose running sum passes the release.
            float sum = 0f;
            int ticks = 0;
            while (sum <= release) {
                sum += TICK_SECONDS;
                ticks++;
            }
            n = ticks;
            release_ticks.put(release, n);
        }
        return n;
    }

    private void restart(@NonNull Unit u) {
        swings.remove(u);
        if (u.isDead() || u.isMounted() || !(u.getCurrentBehaviour() instanceof HarvestBehaviour))
            return;
        Controller primary = u.getPrimaryController();
        if (primary instanceof GatherController<?> gather) {
            Supply supply = gather.getSupply();
            if (supply == null || supply.isDead())
                return;
            ai.owner().setTarget(Selectable.newArray(u), supply, Action.GATHER_REPAIR, false);
            ai.aiLog().count("swing_restart");
        } else if (primary instanceof RepairController repair) {
            Building b = repair.getBuilding();
            if (b == null || b.isDead())
                return;
            ai.owner().setTarget(Selectable.newArray(u), b, Action.GATHER_REPAIR, false);
            ai.aiLog().count("swing_restart_build");
        }
    }

    /** Orders a unit whose stun landed this tick back to what it was doing, which cancels the stun. */
    private void unstun(@NonNull Unit u) {
        Controller primary = u.getPrimaryController();
        // Never order a chieftain with a spell queued under the stun: the order would throw the spell away (A10).
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
            // Idle, entering or anything else: stand on the spot, fighting back if it can fight.
            boolean fighter = !u.getAbilities().hasAbilities(Abilities.BUILD);
            target = new LandscapeTarget(u.getGridX(), u.getGridY());
            action = fighter ? Action.ATTACK : Action.MOVE;
            aggressive = fighter;
        }
        ai.owner().setTarget(Selectable.newArray(u), target, action, aggressive);
        ai.aiLog().count("stun_cancel");
    }

    private void towerUnstun() {
        for (Building t : ai.intel().towers) {
            if (t.isDead() || !t.isComplete() || t.getUnitContainer() == null
                    || t.getUnitContainer().getNumSupplies() == 0)
                continue;
            Unit gunner = Intel.gunner(t);
            if (gunner == null || gunner.isDead())
                continue;
            Controller current = gunner.getCurrentController();
            Controller previous = tower_last.put(t, current);
            if (!(current instanceof StunController) || current == previous)
                continue;
            Selectable<?> target = ai.military().towerTargetFor(t, gunner);
            if (target == null)
                continue;
            ai.owner().setTarget(Selectable.newArray(t), target, Action.ATTACK, false);
            ai.aiLog().count("tower_unstun");
        }
        if (tick % 250 == 0)
            tower_last.keySet().removeIf(Building::isDead);
    }
}
