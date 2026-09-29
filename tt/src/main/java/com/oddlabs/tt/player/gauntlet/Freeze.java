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
import com.oddlabs.tt.model.behaviour.WalkController;
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
 *
 * <p>Follow-ups, each off by default: freeze_unfreeze lets a copy go from the frozen set once its frozen armory site
 * is gone or it has a finished armory; freeze_fight lets the staged squad fight the copy's units that come near it,
 * outside its defense circle, instead of walking back to the stage point every 6 s; after a path-(a) out,
 * freeze_armory_push moves our first armory up the build order and sends the squad to build it, and freeze_retarget
 * strikes one more copy within freeze_eta of the squad whose quarters is not finished.
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
    /** freeze_fight: cells from a squad peon within which the staged squad takes on the copy's units. */
    private static final int FIGHT_CELLS = 6;
    /** freeze_fight: cells from the copy's finished quarters or armory the squad keeps out of (30 m and a cell). */
    private static final int DEFENSE_CELLS = 16;

    private final @NonNull GauntletAI ai;
    /** The strikes launched at the start, in launch order (freeze_targets). */
    private final List<@NonNull Strike> strikes = new ArrayList<>();
    /** Copies whose armory builders all died with the site standing: nothing of ours touches their armory site. */
    private final List<@NonNull Player> frozen = new ArrayList<>();
    /** The armory site each frozen copy was frozen with, by the index of the copy in frozen (freeze_unfreeze). */
    private final List<@NonNull Building> frozen_sites = new ArrayList<>();

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
     * Picks the targets and the squads at the start, before the economy hands out the starting peons: the nearest
     * copy by walking time, then (freeze_targets) the next nearest ones. Returns whether a strike was launched (the
     * peons' states then need an Intel update).
     */
    boolean plan(@NonNull DistanceField start_field) {
        Strategy strategy = ai.strategy();
        if (!strategy.freeze_open || strategy.freeze_squad <= 0)
            return false;
        Player me = ai.owner();
        List<Player> copies = new ArrayList<>();
        List<Float> etas = new ArrayList<>();
        for (Player p : me.getWorld().getPlayers()) {
            if (!me.isEnemy(p) || !p.isAlive())
                continue;
            int walk = start_field.getAround(UnitGrid.toGridCoordinate(p.getStartX()),
                    UnitGrid.toGridCoordinate(p.getStartY()), 3);
            if (walk == DistanceField.UNREACHABLE)
                continue;
            // Nearest first; world order breaks ties (inserted after every copy at most as far).
            float eta = walk / PEON_SPEED;
            int at = 0;
            while (at < etas.size() && etas.get(at) <= eta)
                at++;
            copies.add(at, p);
            etas.add(at, eta);
        }
        if (copies.isEmpty() || etas.getFirst() > strategy.freeze_eta) {
            String nearest = copies.isEmpty() ? "none reachable" : name(
                    copies.getFirst()) + " at " + (int) (float) etas.getFirst() + "s";
            log(() -> "no strike: nearest copy " + nearest + ", freeze_eta " + (int) strategy.freeze_eta + "s");
            return false;
        }
        Intel intel = ai.intel();
        for (int i = 0; i < copies.size() && strikes.size() < Math.max(1, strategy.freeze_targets); i++) {
            boolean first = strikes.isEmpty();
            float limit = first || strategy.freeze_eta2 <= 0f ? strategy.freeze_eta : strategy.freeze_eta2;
            if (etas.get(i) > limit)
                break;
            List<Unit> pool = new ArrayList<>(intel.peons);
            pool.removeAll(intel.strikers);
            // Leave freeze_keep peons for our own opening.
            int wanted = first || strategy.freeze_squad2 <= 0 ? strategy.freeze_squad : strategy.freeze_squad2;
            int size = Math.min(wanted, pool.size() - Math.max(1, strategy.freeze_keep));
            if (size < MIN_SQUAD) {
                int left = pool.size();
                log(() -> "no " + (first ? "" : "further ") + "strike: only " + left + " peons");
                break;
            }
            Player best = copies.get(i);
            int sx = UnitGrid.toGridCoordinate(best.getStartX());
            int sy = UnitGrid.toGridCoordinate(best.getStartY());
            pool.sort(Comparator.comparingInt(u -> MapAnalysis.dist2(u.getGridX(), u.getGridY(), sx, sy)));
            Strike strike = new Strike(best, pool.subList(0, size));
            strikes.add(strike);
            intel.strikers.addAll(strike.squad);
            strike.setPhase(Phase.WALK);
            ai.aiLog().count(first ? "freeze_start" : "freeze_start_more");
            float eta = etas.get(i);
            log(() -> (first ? "" : "further ") + "strike on " + name(
                    best) + " at " + sx + "," + sy + " with " + strike.squad.size() + " peons, eta " + (int) eta + "s");
            strike.walk(best, ai.time());
        }
        return !strikes.isEmpty();
    }

    void tick() {
        if (ai.strategy().freeze_unfreeze && !frozen.isEmpty())
            unfreeze();
        for (Strike strike : strikes)
            strike.tick();
    }

    /** Whether a strike other than the given one is on the copy (freeze_retarget never doubles up). */
    private boolean struck(@NonNull Player p, @NonNull Strike self) {
        for (Strike other : strikes)
            if (other != self && other.active() && other.target == p)
                return true;
        return false;
    }

    /** One strike: a squad at one copy, through path (a), or path (c) when its quarters stands first. */
    private final class Strike {
        private final List<@NonNull Unit> squad = new ArrayList<>();
        /** The target's units outside last tick, whose deaths are the squad's kills. */
        private final List<@NonNull Unit> seen = new ArrayList<>();
        /** Whether a path-(a) strike put its copy out (freeze_armory_push, freeze_retarget). */
        private boolean a_out;
        /** Whether the squad already looked for a second copy to strike (freeze_retarget). */
        private boolean retargeted;
        private @Nullable Player target;
        private int start_x;
        private int start_y;
        private @NonNull Phase phase = Phase.DONE;
        private float phase_time;
        private float last_order = -100f;
        private int stage_x;
        private int stage_y;
        private boolean out_counted;

        Strike(@NonNull Player target, @NonNull List<@NonNull Unit> squad) {
            this.target = target;
            this.squad.addAll(squad);
            start_x = UnitGrid.toGridCoordinate(target.getStartX());
            start_y = UnitGrid.toGridCoordinate(target.getStartY());
        }

        boolean active() {
            return phase != Phase.DONE && target != null;
        }

        void tick() {
            if (!active())
                return;
            Player t = target;
            assert t != null;
            float now = ai.time();
            countLosses();
            countKills(t);
            if (!t.isAlive()) {
                if (!out_counted) {
                    out_counted = true;
                    ai.aiLog().count("freeze_target_out");
                }
                log(() -> name(t) + " is out at " + (int) now + "s (" + phase + ", " + squad.size() + " peons left)");
                if (phase == Phase.WALK || phase == Phase.STRIKE) {
                    a_out = true;
                    if (ai.strategy().freeze_retarget && !retargeted && squad.size() >= MIN_SQUAD && retarget(now))
                        return;
                }
                goHome();
                return;
            }
            if (squad.size() < MIN_SQUAD) {
                abort("squad down to " + squad.size() + " peons");
                return;
            }
            switch (phase) {
                case WALK -> walk(t, now);
                case STRIKE -> strike(t, now);
                case STAGE -> stage(t, now);
                case WAIT -> await(t, now);
                case CUT -> cut(t, now);
                case RAZE -> raze(t, now);
                default -> {
                }
            }
        }

        // --------------------------------------------------------------------------------------------------------
        // Path (a)

        private void walk(@NonNull Player t, float now) {
            if (quartersStood(t, "its quarters stood before the squad arrived"))
                return;
            if (now - phase_time > ai.strategy().freeze_eta + STRIKE_LIMIT) {
                abort("the squad never reached its peons");
                return;
            }
            List<Unit> prey = outsideUnits(t);
            int[] goal = prey.isEmpty() ? new int[]{start_x, start_y} : MapAnalysis.centroid(prey);
            int[] c = MapAnalysis.centroid(squad);
            if (!prey.isEmpty() && MapAnalysis.dist2(c[0], c[1], goal[0], goal[1]) <= ARRIVE_CELLS * ARRIVE_CELLS) {
                setPhase(Phase.STRIKE);
                ai.aiLog().count("freeze_path_a");
                log(() -> "squad at " + name(t) + " at " + (int) now + "s: striking " + prey.size() + " peons");
                strike(t, now);
                return;
            }
            moveSquad(now, goal[0], goal[1]);
        }

        private void strike(@NonNull Player t, float now) {
            if (quartersStood(t, "its quarters stood during the strike"))
                return;
            if (now - phase_time > STRIKE_LIMIT) {
                abort("strike took over " + (int) STRIKE_LIMIT + "s");
                return;
            }
            List<Unit> prey = outsideUnits(t);
            // None left and no finished quarters: the engine takes it out, and the next tick sees it.
            if (!prey.isEmpty())
                assign(prey);
        }

        /**
         * freeze_retarget: after a path-(a) out the squad strikes once more, at the living copy with the least walking
         * time from it, if that is at most freeze_eta seconds of peon walk and its quarters is not finished. Returns
         * whether it did; either way there is no third look.
         */
        private boolean retarget(float now) {
            Player done = target;
            assert done != null;
            retargeted = true;
            Player me = ai.owner();
            float limit = ai.strategy().freeze_eta;
            int[] c = MapAnalysis.centroid(squad);
            DistanceField field = ai.map().computeField(c[0], c[1], (int) Math.ceil(limit * PEON_SPEED) + 10);
            Player best = null;
            float best_eta = Float.MAX_VALUE;
            // The nearest living copy whatever its quarters, to tell why there is no second strike.
            Player nearest = null;
            float nearest_eta = Float.MAX_VALUE;
            // World order breaks ties, as in plan.
            for (Player p : me.getWorld().getPlayers()) {
                if (p == done || !me.isEnemy(p) || !p.isAlive() || struck(p, this))
                    continue;
                int walk = field.getAround(UnitGrid.toGridCoordinate(p.getStartX()),
                        UnitGrid.toGridCoordinate(p.getStartY()), 3);
                if (walk == DistanceField.UNREACHABLE)
                    continue;
                float eta = walk / PEON_SPEED;
                if (eta < nearest_eta) {
                    nearest_eta = eta;
                    nearest = p;
                }
                if (eta < best_eta && !quartersStands(p)) {
                    best_eta = eta;
                    best = p;
                }
            }
            if (best == null || best_eta > limit) {
                if (nearest != null && nearest_eta <= limit)
                    ai.aiLog().count("freeze_retarget_quartered");
                Player n = nearest;
                float n_eta = nearest_eta;
                log(() -> "no second strike: nearest living copy " + (n == null ? "none within reach" : name(
                        n) + " at " + (int) n_eta + "s" + (quartersStands(
                                n) ? ", its quarters stands" : "")) + ", freeze_eta " + (int) limit + "s");
                return false;
            }
            target = best;
            start_x = UnitGrid.toGridCoordinate(best.getStartX());
            start_y = UnitGrid.toGridCoordinate(best.getStartY());
            out_counted = false;
            seen.clear();
            setPhase(Phase.WALK);
            ai.aiLog().count("freeze_retarget");
            float eta = best_eta;
            Player t = best;
            log(() -> "second strike on " + name(
                    t) + " at " + start_x + "," + start_y + " with " + squad.size() + " peons, eta " + (int) eta + "s");
            walk(best, now);
            return true;
        }

        /**
         * Path (a) is over once the copy's quarters stands: the squad falls back to the armory cut (path (c)), or,
         * on a second strike (freeze_retarget), gives up. Returns whether it did.
         */
        private boolean quartersStood(@NonNull Player t, @NonNull String why) {
            if (!quartersStands(t))
                return false;
            if (retargeted)
                abort(why);
            else
                fallback(t, why);
            return true;
        }

        // --------------------------------------------------------------------------------------------------------
        // Path (c)

        private void fallback(@NonNull Player t, @NonNull String why) {
            setPhase(Phase.STAGE);
            ai.aiLog().count("freeze_fallback_c");
            log(() -> "falling back to the armory cut on " + name(t) + ": " + why);
            stage(t, ai.time());
        }

        private void stage(@NonNull Player t, float now) {
            stagePoint(t);
            if (armoryCheck(t))
                return;
            if (now - phase_time > WAIT_LIMIT) {
                abort("the squad never reached its stage point");
                return;
            }
            int[] c = MapAnalysis.centroid(squad);
            if (MapAnalysis.dist2(c[0], c[1], stage_x, stage_y) <= 7 * 7) {
                setPhase(Phase.WAIT);
                log(() -> "squad staged by " + name(t) + " at " + stage_x + "," + stage_y);
                await(t, now);
                return;
            }
            moveSquad(now, stage_x, stage_y);
        }

        private void await(@NonNull Player t, float now) {
            if (armoryCheck(t))
                return;
            if (now - phase_time > WAIT_LIMIT) {
                abort("no armory site within " + (int) WAIT_LIMIT + "s");
                return;
            }
            stagePoint(t);
            if (ai.strategy().freeze_fight) {
                fight(t);
                return;
            }
            moveSquad(now, stage_x, stage_y);
        }

        /**
         * freeze_fight, while waiting: the squad takes on the copy's units within FIGHT_CELLS of a squad peon that
         * stand outside the defense circle of the copy's finished quarters or armory (its drafted peons hunted the
         * squad down at the stage point while it walked back there without fighting). A peon inside the circle walks
         * back to the stage point, and so does one that hunts nothing near or idles away from it; the others are left
         * where they are.
         */
        private void fight(@NonNull Player t) {
            List<Building> guards = new ArrayList<>();
            for (Selectable<?> sel : t.getUnits().getSet())
                if (sel instanceof Building b && !b.isDead() && b.isComplete()
                        && (b.getTemplate().getTemplateID() == Race.BUILDING_QUARTERS
                                || b.getTemplate().getTemplateID() == Race.BUILDING_ARMORY))
                    guards.add(b);
            List<Unit> prey = new ArrayList<>();
            for (Unit u : outsideUnits(t))
                if (!guarded(guards, u) && nearSquad(u))
                    prey.add(u);
            List<Unit> fighters = new ArrayList<>();
            List<Unit> back = new ArrayList<>();
            for (Unit u : squad) {
                // Already walking back (a MOVE without fighting): leave it be.
                boolean walking = u.getPrimaryController() instanceof WalkController w && !w.isAgressive()
                        && u.getCurrentController() == w;
                Selectable<?> hunted = huntTarget(u);
                boolean hunting = hunted != null && !hunted.isDead();
                boolean away = MapAnalysis.dist2(u.getGridX(), u.getGridY(), stage_x, stage_y) > 7 * 7;
                if (guarded(guards, u)) {
                    if (!walking)
                        back.add(u);
                } else if (!prey.isEmpty()) {
                    fighters.add(u);
                } else if (!walking && (hunting || away)) {
                    back.add(u);
                }
            }
            if (!fighters.isEmpty()) {
                ai.aiLog().count("freeze_fight");
                Freeze.this.assign(fighters, prey);
            }
            if (!back.isEmpty()) {
                ai.aiLog().count("freeze_fight_back");
                ai.landscapeOrder(back.toArray(new Selectable<?>[0]), stage_x, stage_y, Action.MOVE, false);
            }
        }

        /** freeze_fight: whether the unit stands within FIGHT_CELLS of a squad peon. */
        private boolean nearSquad(@NonNull Unit u) {
            for (Unit s : squad) {
                int d = MapAnalysis.dist2(s.getGridX(), s.getGridY(), u.getGridX(), u.getGridY());
                if (d <= FIGHT_CELLS * FIGHT_CELLS)
                    return true;
            }
            return false;
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
            cut(t, ai.time());
            return true;
        }

        private void cut(@NonNull Player t, float now) {
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
                    frozen_sites.add(armory);
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

        private void raze(@NonNull Player t, float now) {
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

        // --------------------------------------------------------------------------------------------------------
        // The squad

        /** Every 6 s the squad walks (a MOVE, without fighting) towards (x, y). */
        private void moveSquad(float now, int x, int y) {
            if (now - last_order < 6f)
                return;
            last_order = now;
            ai.landscapeOrder(squad.toArray(new Selectable<?>[0]), x, y, Action.MOVE, false);
        }

        /** Every squad peon without a live target goes for the nearest prey that has fewer than three hunters. */
        private void assign(@NonNull List<@NonNull Unit> prey) {
            Freeze.this.assign(squad, prey);
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
            // freeze_armory_push: after a path-(a) out our first armory moves up the build order, and the squad builds it.
            Building push = a_out && ai.strategy().freeze_armory_push ? ai.economy().pushArmory() : null;
            if (squad.isEmpty())
                return;
            Selectable<?>[] units = squad.toArray(new Selectable<?>[0]);
            if (push != null) {
                ai.owner().setTarget(units, push, Action.DEFAULT, false);
                for (int i = 0; i < units.length; i++)
                    ai.aiLog().count("freeze_push_builders");
                log(() -> units.length + " peons walk to build our armory at " + push.getGridX() + "," + push.getGridY());
                squad.clear();
                return;
            }
            int[] c = MapAnalysis.centroid(squad);
            List<Building> homes = intel.homes();
            Building home = MapAnalysis.nearest(homes, c[0], c[1]);
            if (home == null)
                home = MapAnalysis.nearest(intel.quarters_sites, c[0], c[1]);
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
    }

    /** freeze_fight: whether the unit stands within DEFENSE_CELLS of one of the buildings. */
    private static boolean guarded(@NonNull List<@NonNull Building> guards, @NonNull Unit u) {
        for (Building b : guards) {
            int d = MapAnalysis.dist2(b.getGridX(), b.getGridY(), u.getGridX(), u.getGridY());
            if (d <= DEFENSE_CELLS * DEFENSE_CELLS)
                return true;
        }
        return false;
    }

    /** Every one of the peons without a live target goes for the nearest prey that has fewer than three hunters. */
    private void assign(@NonNull List<@NonNull Unit> peons, @NonNull List<@NonNull Unit> prey) {
        List<Unit> busy = new ArrayList<>();
        List<Unit> targets = new ArrayList<>();
        for (Unit u : peons) {
            Selectable<?> t = huntTarget(u);
            if (t instanceof Unit tu && !tu.isDead() && prey.contains(tu)) {
                busy.add(u);
                targets.add(tu);
            }
        }
        for (Unit u : peons) {
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

    /** What the unit hunts or attacks right now, or null. */
    private static @Nullable Selectable<?> huntTarget(@NonNull Unit u) {
        Controller c = u.getCurrentController();
        return c instanceof HuntController h ? h.getTarget() : c instanceof AttackController a ? a.getTarget() : null;
    }

    /**
     * freeze_unfreeze: a copy whose frozen armory site is gone (razed, or finished) or that has a finished armory
     * rebuilds with a crew from its quarters, so it is frozen no more and the attack target's choice treats it as any
     * other copy.
     */
    private void unfreeze() {
        for (int i = frozen.size() - 1; i >= 0; i--) {
            Player p = frozen.get(i);
            Building site = frozen_sites.get(i);
            Building armory = building(p, Race.BUILDING_ARMORY);
            boolean armed = armory != null && armory.isComplete();
            if (p.isAlive() && !site.isDead() && !site.isComplete() && !armed)
                continue;
            frozen.remove(i);
            frozen_sites.remove(i);
            if (!p.isAlive())
                continue; // out: nothing of it is left to treat as frozen
            ai.aiLog().count("freeze_unfrozen");
            float now = ai.time();
            String why = armed ? "its armory stands" : "its frozen armory site is gone";
            log(() -> name(p) + " unfrozen at " + (int) now + "s: " + why);
        }
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

    private static @NonNull String name(@NonNull Player p) {
        return p.getPlayerInfo().getName();
    }

    private void log(java.util.function.@NonNull Supplier<String> message) {
        ai.aiLog().log("FREEZE", message);
    }
}
