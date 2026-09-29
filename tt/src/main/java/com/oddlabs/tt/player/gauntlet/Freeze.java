package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The freeze opening (freeze_open; archaeology item A1, re-scoped from the freeze strike of lab/gauntlet/NOTES.md
 * 2026-09-28, commit 080918f1): at the start a squad of freeze_squad starting peons walks to the copy with the least
 * walking time from our start, if that is at most freeze_eta seconds of peon walk.
 *
 * <p>Path (a): the squad kills every peon the copy has outside before its first quarters stands. A player is in the
 * game only while it has units, an active chieftain or a finished quarters (StandardModeRules.isPlayerAlive), so the
 * copy is out at once. Builders never fight back (Repair and PlaceBuilding controllers walk without scanning), and
 * the Hard defends only around a finished quarters or armory (AdvancedAI.nodeDefendBase), so nothing answers.
 *
 * <p>Path (c), when the quarters stands first (its defense is then live): the squad waits STAGE_CELLS out, beyond the
 * copy's 30 m defense circle, until the copy places its armory site, then kills every peon it has outside and goes
 * home. The copy is then frozen for good: the armory site keeps its "under construction" flag set (AI.classifyIndex
 * clears it only when a finished armory is seen or no site exists), so nodeBuildArmory never runs again, and without
 * an armory nothing ever deploys a peon from the quarters. The frozen armory site must never be touched: razing it
 * clears the flag and the copy rebuilds with a crew from the quarters. With freeze_raze the squad stays to raze the
 * frozen quarters, which puts the copy out.
 *
 * <p>The squad's peons are kept in Intel.strikers until the strike ends, so the economy and the military leave them
 * alone; then they walk home and the economy takes them back.
 */
final class Freeze {
    enum Phase {
        /** Path (a): walking to the copy's start. */
        WALK,
        /** Path (a): killing its peons before its quarters stands. */
        STRIKE,
        /** Path (c): walking to the stage point outside its defense circle. */
        STAGE,
        /** Path (c): waiting there for its armory site. */
        WAIT,
        /** Path (c): killing its armory builders. */
        CUT,
        /** Path (c) with freeze_raze: razing the frozen quarters. */
        RAZE,
        DONE
    }

    /** A peon's speed, in meters per second (RacesResources, both races' peon templates). */
    private static final float PEON_SPEED = 5f;
    /** Cells from the copy's quarters where the squad waits in path (c): outside its 30 m defense circle. */
    private static final int STAGE_CELLS = 19;
    /** Cells from the copy's peons at which the walking squad starts to strike in path (a). */
    private static final int ARRIVE_CELLS = 12;
    /** Below this many peons the squad gives up and walks home. */
    private static final int MIN_SQUAD = 3;
    /** Seconds the squad waits at the stage point for the armory site (freeze_wait of the old strike). */
    private static final float WAIT_LIMIT = 150f;
    /** Seconds a strike may last before the squad gives up (freeze_strike_time of the old strike). */
    private static final float STRIKE_LIMIT = 100f;
    /** Seconds the squad waits in path (c) for a new armory site when the old one is gone. */
    private static final float SITE_GONE_LIMIT = 60f;

    private final @NonNull GauntletAI ai;
    private final List<@NonNull Unit> squad = new ArrayList<>();
    /** The target's units outside last tick, whose deaths are the squad's kills. */
    private final List<@NonNull Unit> seen = new ArrayList<>();
    /** Copies whose armory builders all died with the site standing: nothing of ours touches their armory site. */
    private final List<@NonNull Player> frozen = new ArrayList<>();
    private @Nullable Player target;
    private int start_x;
    private int start_y;
    private @NonNull Phase phase = Phase.DONE;
    private float phase_time;
    private float last_order = -100f;
    private int stage_x;
    private int stage_y;
    private boolean out_counted;

    Freeze(@NonNull GauntletAI ai) {
        this.ai = ai;
    }

    /** Whether the copy is frozen (its armory site must be left alone). */
    boolean isFrozen(@NonNull Player p) {
        return !frozen.isEmpty() && frozen.contains(p);
    }

    /** Whether the building is a frozen copy's armory site. */
    boolean isFrozenSite(@NonNull Building b) {
        return !frozen.isEmpty() && !b.isDead() && !b.isComplete()
                && b.getTemplate().getTemplateID() == Race.BUILDING_ARMORY && frozen.contains(b.getOwner());
    }

    /**
     * Picks the target and the squad at the start, before the economy hands out the starting peons. Returns whether a
     * strike was launched (the peons' states then need an Intel update).
     */
    boolean plan(@NonNull DistanceField start_field) {
        Strategy strategy = ai.strategy();
        if (!strategy.freeze_open || strategy.freeze_squad <= 0)
            return false;
        Player me = ai.owner();
        Player best = null;
        float best_eta = Float.MAX_VALUE;
        // World order breaks ties: only a strictly shorter walk replaces the best so far.
        for (Player p : me.getWorld().getPlayers()) {
            if (!me.isEnemy(p) || !p.isAlive())
                continue;
            int walk = start_field.getAround(UnitGrid.toGridCoordinate(p.getStartX()),
                    UnitGrid.toGridCoordinate(p.getStartY()), 3);
            if (walk == DistanceField.UNREACHABLE)
                continue;
            float eta = walk / PEON_SPEED;
            if (eta < best_eta) {
                best_eta = eta;
                best = p;
            }
        }
        if (best == null || best_eta > strategy.freeze_eta) {
            String nearest = best == null ? "none reachable" : name(best) + " at " + (int) best_eta + "s";
            log(() -> "no strike: nearest copy " + nearest + ", freeze_eta " + (int) strategy.freeze_eta + "s");
            return false;
        }
        Intel intel = ai.intel();
        // Leave at least one peon for our own opening.
        int size = Math.min(strategy.freeze_squad, intel.peons.size() - 1);
        if (size < MIN_SQUAD) {
            log(() -> "no strike: only " + intel.peons.size() + " peons");
            return false;
        }
        target = best;
        start_x = UnitGrid.toGridCoordinate(best.getStartX());
        start_y = UnitGrid.toGridCoordinate(best.getStartY());
        List<Unit> pool = new ArrayList<>(intel.peons);
        pool.sort(Comparator.comparingInt(u -> MapAnalysis.dist2(u.getGridX(), u.getGridY(), start_x, start_y)));
        for (int i = 0; i < size; i++)
            squad.add(pool.get(i));
        intel.strikers.addAll(squad);
        setPhase(Phase.WALK);
        ai.aiLog().count("freeze_start");
        float eta = best_eta;
        Player t = best;
        log(() -> "strike on " + name(
                t) + " at " + start_x + "," + start_y + " with " + squad.size() + " peons, eta " + (int) eta + "s");
        walk(ai.time());
        return true;
    }

    void tick() {
        if (phase == Phase.DONE || target == null)
            return;
        Player t = target;
        float now = ai.time();
        countLosses();
        countKills(t);
        if (!t.isAlive()) {
            if (!out_counted) {
                out_counted = true;
                ai.aiLog().count("freeze_target_out");
            }
            log(() -> name(t) + " is out at " + (int) now + "s (" + phase + ", " + squad.size() + " peons left)");
            goHome();
            return;
        }
        if (squad.size() < MIN_SQUAD) {
            abort("squad down to " + squad.size() + " peons");
            return;
        }
        switch (phase) {
            case WALK -> walk(now);
            case STRIKE -> strike(now);
            case STAGE -> stage(now);
            case WAIT -> await(now);
            case CUT -> cut(now);
            case RAZE -> raze(now);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Path (a)

    private void walk(float now) {
        Player t = target;
        assert t != null;
        if (quartersStands(t)) {
            fallback("its quarters stood before the squad arrived");
            return;
        }
        if (now - phase_time > ai.strategy().freeze_eta + STRIKE_LIMIT) {
            abort("the squad never reached its peons");
            return;
        }
        List<Unit> prey = outsideUnits(t);
        int[] goal = prey.isEmpty() ? new int[]{start_x, start_y} : centre(prey);
        int[] c = centre(squad);
        if (!prey.isEmpty() && MapAnalysis.dist2(c[0], c[1], goal[0], goal[1]) <= ARRIVE_CELLS * ARRIVE_CELLS) {
            setPhase(Phase.STRIKE);
            ai.aiLog().count("freeze_path_a");
            log(() -> "squad at " + name(t) + " at " + (int) now + "s: striking " + prey.size() + " peons");
            strike(now);
            return;
        }
        if (now - last_order >= 6f) {
            last_order = now;
            ai.landscapeOrder(squad.toArray(new Selectable<?>[0]), goal[0], goal[1], Action.MOVE, false);
        }
    }

    private void strike(float now) {
        Player t = target;
        assert t != null;
        if (quartersStands(t)) {
            fallback("its quarters stood during the strike");
            return;
        }
        if (now - phase_time > STRIKE_LIMIT) {
            abort("strike took over " + (int) STRIKE_LIMIT + "s");
            return;
        }
        List<Unit> prey = outsideUnits(t);
        // None left and no finished quarters: the engine takes it out, and the next tick sees it.
        if (!prey.isEmpty())
            assign(prey);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Path (c)

    private void fallback(@NonNull String why) {
        Player t = target;
        assert t != null;
        setPhase(Phase.STAGE);
        ai.aiLog().count("freeze_fallback_c");
        log(() -> "falling back to the armory cut on " + name(t) + ": " + why);
        stage(ai.time());
    }

    private void stage(float now) {
        Player t = target;
        assert t != null;
        stagePoint(t);
        if (armoryCheck(t))
            return;
        if (now - phase_time > WAIT_LIMIT) {
            abort("the squad never reached its stage point");
            return;
        }
        int[] c = centre(squad);
        if (MapAnalysis.dist2(c[0], c[1], stage_x, stage_y) <= 7 * 7) {
            setPhase(Phase.WAIT);
            log(() -> "squad staged by " + name(t) + " at " + stage_x + "," + stage_y);
            await(now);
            return;
        }
        if (now - last_order >= 6f) {
            last_order = now;
            ai.landscapeOrder(squad.toArray(new Selectable<?>[0]), stage_x, stage_y, Action.MOVE, false);
        }
    }

    private void await(float now) {
        Player t = target;
        assert t != null;
        if (armoryCheck(t))
            return;
        if (now - phase_time > WAIT_LIMIT) {
            abort("no armory site within " + (int) WAIT_LIMIT + "s");
            return;
        }
        stagePoint(t);
        if (now - last_order >= 6f) {
            last_order = now;
            ai.landscapeOrder(squad.toArray(new Selectable<?>[0]), stage_x, stage_y, Action.MOVE, false);
        }
    }

    /**
     * While staging or waiting: a finished armory means the window is gone, an armory site starts the cut. Returns
     * whether the phase changed.
     */
    private boolean armoryCheck(@NonNull Player t) {
        Building armory = building(t, Race.BUILDING_ARMORY);
        if (armory == null)
            return false;
        if (armory.isComplete()) {
            abort("its armory stands");
            return true;
        }
        setPhase(Phase.CUT);
        log(() -> "cut on " + name(
                t) + ": armory site at " + armory.getGridX() + "," + armory.getGridY() + ", " + outsideUnits(
                        t).size() + " peons outside");
        cut(ai.time());
        return true;
    }

    private void cut(float now) {
        Player t = target;
        assert t != null;
        Building armory = building(t, Race.BUILDING_ARMORY);
        if (armory != null && armory.isComplete()) {
            abort("its armory stood during the cut");
            return;
        }
        List<Unit> prey = outsideUnits(t);
        if (prey.isEmpty()) {
            if (armory == null) {
                // The site is gone: the copy places another one with a crew from its quarters.
                if (now - phase_time > SITE_GONE_LIMIT)
                    abort("no armory site to cut");
                return;
            }
            if (!frozen.contains(t)) {
                frozen.add(t);
                ai.aiLog().count("freeze_frozen");
                log(() -> "froze " + name(
                        t) + " at " + (int) now + "s, armory site at " + armory.getGridX() + "," + armory.getGridY());
            }
            if (ai.strategy().freeze_raze && building(t, Race.BUILDING_QUARTERS) != null)
                setPhase(Phase.RAZE);
            else
                goHome();
            return;
        }
        if (now - phase_time > STRIKE_LIMIT) {
            abort("cut took over " + (int) STRIKE_LIMIT + "s");
            return;
        }
        assign(prey);
    }

    private void raze(float now) {
        Player t = target;
        assert t != null;
        Building quarters = building(t, Race.BUILDING_QUARTERS);
        if (quarters == null) {
            abort("no quarters left to raze");
            return;
        }
        List<Unit> prey = outsideUnits(t);
        for (Unit u : prey) {
            if (u.getAbilities().hasAbilities(Abilities.THROW) || u.getAbilities().hasAbilities(Abilities.MAGIC)) {
                abort("warriors or a chieftain came out");
                return;
            }
        }
        if (!prey.isEmpty()) {
            assign(prey);
            return;
        }
        if (now - last_order >= 5f) {
            last_order = now;
            ai.owner().setTarget(squad.toArray(new Selectable<?>[0]), quarters, Action.ATTACK, false);
        }
    }

    /** Where the squad waits: STAGE_CELLS from the copy's quarters (or start) on the way from our start. */
    private void stagePoint(@NonNull Player t) {
        Building q = building(t, Race.BUILDING_QUARTERS);
        int cx = q != null ? q.getGridX() : start_x;
        int cy = q != null ? q.getGridY() : start_y;
        float dx = ai.planner().getStartX() - cx;
        float dy = ai.planner().getStartY() - cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) {
            stage_x = cx;
            stage_y = cy;
            return;
        }
        stage_x = cx + Math.round(STAGE_CELLS * dx / len);
        stage_y = cy + Math.round(STAGE_CELLS * dy / len);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Shared

    /** Every squad peon without a live target goes for the nearest prey that has fewer than three hunters. */
    private void assign(@NonNull List<@NonNull Unit> prey) {
        List<Unit> busy = new ArrayList<>();
        List<Unit> targets = new ArrayList<>();
        for (Unit u : squad) {
            Controller c = u.getCurrentController();
            Selectable<?> t = c instanceof HuntController h ? h.getTarget() : c instanceof AttackController a ? a.getTarget() : null;
            if (t instanceof Unit tu && !tu.isDead() && prey.contains(tu)) {
                busy.add(u);
                targets.add(tu);
            }
        }
        for (Unit u : squad) {
            if (busy.contains(u))
                continue;
            Unit best = null;
            int best_d = Integer.MAX_VALUE;
            for (Unit p : prey) {
                int hunters = 0;
                for (Unit t : targets)
                    if (t == p)
                        hunters++;
                if (hunters >= 3)
                    continue;
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), p.getGridX(), p.getGridY());
                if (d < best_d) {
                    best_d = d;
                    best = p;
                }
            }
            if (best == null)
                best = prey.getFirst();
            ai.owner().setTarget(Selectable.newArray(u), best, Action.ATTACK, false);
            targets.add(best);
        }
    }

    private void countLosses() {
        for (int i = squad.size() - 1; i >= 0; i--) {
            Unit u = squad.get(i);
            if (u.isDead()) {
                squad.remove(i);
                ai.intel().strikers.remove(u);
                ai.aiLog().count("freeze_lost");
            }
        }
    }

    /**
     * The target's outside units that died since the last tick are our kills: nothing else fights a copy this early
     * (the squad also finishes its kills while it walks off to the stage point).
     */
    private void countKills(@NonNull Player t) {
        for (Unit u : seen)
            if (u.isDead())
                ai.aiLog().count("freeze_kills");
        seen.clear();
        seen.addAll(outsideUnits(t));
    }

    private void abort(@NonNull String why) {
        Player t = target;
        ai.aiLog().count("freeze_abort");
        log(() -> "strike on " + (t != null ? name(
                t) : "?") + " given up in " + phase + ": " + why + " (" + squad.size() + " peons left)");
        goHome();
    }

    /** The strike is over: the squad walks home and the economy takes its peons back. */
    private void goHome() {
        Intel intel = ai.intel();
        for (Unit u : squad)
            intel.strikers.remove(u);
        setPhase(Phase.DONE);
        seen.clear();
        if (squad.isEmpty())
            return;
        Selectable<?>[] units = squad.toArray(new Selectable<?>[0]);
        int[] c = centre(squad);
        List<Building> homes = new ArrayList<>(intel.quarters);
        homes.addAll(intel.armories);
        Building home = nearest(homes, c[0], c[1]);
        if (home == null)
            home = nearest(intel.quarters_sites, c[0], c[1]);
        if (home != null)
            ai.owner().setTarget(units, home, Action.DEFAULT, false);
        else
            ai.landscapeOrder(units, ai.planner().getStartX(), ai.planner().getStartY(), Action.MOVE, false);
        Building h = home;
        log(() -> squad.size() + " peons walk home" + (h != null ? " to " + h.getGridX() + "," + h.getGridY() : ""));
        squad.clear();
    }

    private void setPhase(@NonNull Phase next) {
        phase = next;
        phase_time = ai.time();
        last_order = -100f;
    }

    private static boolean quartersStands(@NonNull Player p) {
        Building q = building(p, Race.BUILDING_QUARTERS);
        return q != null && q.isComplete();
    }

    /** The player's first building of the type, finished ones before sites, or null. */
    private static @Nullable Building building(@NonNull Player p, int type) {
        Building site = null;
        for (Selectable<?> sel : p.getUnits().getSet())
            if (sel instanceof Building b && !b.isDead() && b.getTemplate().getTemplateID() == type) {
                if (b.isComplete())
                    return b;
                if (site == null)
                    site = b;
            }
        return site;
    }

    /** The player's units in the field (not inside buildings or towers). */
    private static @NonNull List<@NonNull Unit> outsideUnits(@NonNull Player p) {
        List<Unit> units = new ArrayList<>();
        for (Selectable<?> sel : p.getUnits().getSet())
            if (sel instanceof Unit u && !u.isDead() && !u.isMounted())
                units.add(u);
        return units;
    }

    private static @Nullable Building nearest(@NonNull List<@NonNull Building> buildings, int x, int y) {
        Building best = null;
        int best_d = Integer.MAX_VALUE;
        for (Building b : buildings) {
            if (b.isDead())
                continue;
            int d = MapAnalysis.dist2(x, y, b.getGridX(), b.getGridY());
            if (d < best_d) {
                best_d = d;
                best = b;
            }
        }
        return best;
    }

    private static int @NonNull [] centre(@NonNull List<@NonNull Unit> units) {
        long x = 0;
        long y = 0;
        for (Unit u : units) {
            x += u.getGridX();
            y += u.getGridY();
        }
        int n = Math.max(1, units.size());
        return new int[]{(int) (x / n), (int) (y / n)};
    }

    private static @NonNull String name(@NonNull Player p) {
        return p.getPlayerInfo().getName();
    }

    private void log(java.util.function.@NonNull Supplier<String> message) {
        ai.aiLog().log("FREEZE", message);
    }
}
