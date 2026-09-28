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
 * The opening freeze strike on the nearest Hard copies (sweep's lab/sweep/model/freeze.md, the study's contrarian
 * plan): a squad of peons walks to a copy at the start and kills every peon it has outside once its armory site is
 * placed.
 *
 * <p>Why it holds (AdvancedAI, AI): a copy places its armory with the 20 peons that built its quarters, at about 73 s,
 * and finishes it at about 131 s. Builders never fight back (Repair and PlaceBuilding controllers walk without
 * scanning,
 * and nothing reacts to being hit). With no armory the copy has nobody to defend with: its defense deploys the armory
 * (none) and sends idle warriors, idle peons and gatherers (none). Once every builder is dead the copy is frozen for
 * good: the armory site keeps its "under construction" flag set (AI.classifyIndex clears it only when a finished armory
 * is seen or no site exists), so nodeBuildArmory never runs again, and without an armory nothing ever deploys a peon
 * from the quarters. The quarters is then razed with no response, which removes every peon bred inside, and the copy
 * is out by the engine's rule (no units, no quarters). The frozen armory site must never be touched: razing it clears
 * the flag and the copy rebuilds with a crew from the quarters.
 */
final class Freeze {
    enum Phase {
        WALK,
        WAIT,
        STRIKE,
        RAZE,
        HOME,
        DONE
    }

    /** A peon's speed, in meters per second. */
    private static final float PEON_SPEED = 5f;
    /** Cells from the copy's quarters where the squad waits: outside its 30 m defense circle. */
    private static final int STAGE_CELLS = 19;

    private final @NonNull GauntletAI ai;
    private final List<@NonNull Strike> strikes = new ArrayList<>();
    /** Copies whose armory builders all died with the site standing: the military never touches their armory site. */
    private final List<@NonNull Player> frozen = new ArrayList<>();

    static final class Strike {
        final @NonNull Player target;
        final int start_x;
        final int start_y;
        final List<@NonNull Unit> squad = new ArrayList<>();
        @NonNull
        Phase phase = Phase.WALK;
        float phase_time;
        int stage_x;
        int stage_y;
        float last_order = -100f;

        Strike(@NonNull Player target, int start_x, int start_y) {
            this.target = target;
            this.start_x = start_x;
            this.start_y = start_y;
        }
    }

    Freeze(@NonNull GauntletAI ai) {
        this.ai = ai;
    }

    /** Whether the copy is frozen (its armory site must be left alone). */
    boolean isFrozen(@NonNull Player p) {
        return frozen.contains(p);
    }

    /** Whether the building is a frozen copy's armory site. */
    boolean isFrozenSite(@NonNull Building b) {
        return !b.isComplete() && b.getTemplate().getTemplateID() == Race.BUILDING_ARMORY && isFrozen(b.getOwner());
    }

    /** Picks the targets and their squads at the start, before the economy hands out the starting peons. */
    void plan(@NonNull DistanceField start_field) {
        Strategy strategy = ai.strategy();
        if (!strategy.freeze || strategy.freeze_targets <= 0)
            return;
        Player me = ai.owner();
        List<Strike> candidates = new ArrayList<>();
        List<Float> etas = new ArrayList<>();
        for (Player p : me.getWorld().getPlayers()) {
            if (!me.isEnemy(p))
                continue;
            int gx = UnitGrid.toGridCoordinate(p.getStartX());
            int gy = UnitGrid.toGridCoordinate(p.getStartY());
            int walk = start_field.getAround(gx, gy, 3);
            if (walk == DistanceField.UNREACHABLE)
                continue;
            float eta = walk / PEON_SPEED;
            if (eta > strategy.freeze_max_eta)
                continue;
            Strike s = new Strike(p, gx, gy);
            int i = 0;
            while (i < etas.size() && etas.get(i) <= eta)
                i++;
            candidates.add(i, s);
            etas.add(i, eta);
        }
        Intel intel = ai.intel();
        List<Unit> pool = new ArrayList<>(intel.peons);
        for (int k = 0; k < candidates.size() && k < strategy.freeze_targets; k++) {
            Strike s = candidates.get(k);
            if (pool.size() - strategy.freeze_squad < strategy.freeze_keep)
                break;
            pool.sort(Comparator.comparingInt(u -> MapAnalysis.dist2(u.getGridX(), u.getGridY(), s.start_x,
                    s.start_y)));
            for (int i = 0; i < strategy.freeze_squad && !pool.isEmpty(); i++)
                s.squad.add(pool.removeFirst());
            stagePoint(s);
            strikes.add(s);
            for (Unit u : s.squad)
                intel.strikers.add(u);
            ai.aiLog().count("freeze_sent");
            float eta = etas.get(k);
            ai.log("freeze strike on " + s.target.getPlayerInfo().getName() + " at " + s.start_x + "," + s.start_y + " with " + s.squad.size() + " peons, eta " + (int) eta + "s");
        }
    }

    void tick() {
        if (strikes.isEmpty())
            return;
        float now = ai.time();
        Intel intel = ai.intel();
        for (Strike s : strikes) {
            s.squad.removeIf(Unit::isDead);
            if (s.phase == Phase.DONE)
                continue;
            if (s.squad.size() < ai.strategy().freeze_min_squad && s.phase != Phase.HOME) {
                ai.log("freeze strike on " + s.target.getPlayerInfo().getName() + " down to " + s.squad.size() + " peons in " + s.phase);
                ai.aiLog().count("freeze_broken");
                goHome(s);
            }
            switch (s.phase) {
                case WALK -> walk(s, now);
                case WAIT -> await(s, now);
                case STRIKE -> strike(s, now);
                case RAZE -> raze(s, now);
                case HOME -> home(s, intel);
                default -> {
                }
            }
        }
    }

    private void setPhase(@NonNull Strike s, @NonNull Phase phase) {
        s.phase = phase;
        s.phase_time = ai.time();
        s.last_order = -100f;
    }

    /** The copy's quarters (finished or not), or null. */
    private static @Nullable Building quarters(@NonNull Player p) {
        return building(p, Race.BUILDING_QUARTERS);
    }

    private static @Nullable Building building(@NonNull Player p, int type) {
        for (Selectable<?> sel : p.getUnits().getSet())
            if (sel instanceof Building b && !b.isDead() && b.getTemplate().getTemplateID() == type)
                return b;
        return null;
    }

    /** Where the squad waits: STAGE_CELLS from the copy's quarters (or start) on the way from our start. */
    private void stagePoint(@NonNull Strike s) {
        Building q = quarters(s.target);
        int cx = q != null ? q.getGridX() : s.start_x;
        int cy = q != null ? q.getGridY() : s.start_y;
        int ox = ai.planner().getStartX();
        int oy = ai.planner().getStartY();
        float dx = ox - cx;
        float dy = oy - cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) {
            s.stage_x = cx;
            s.stage_y = cy;
            return;
        }
        s.stage_x = cx + Math.round(STAGE_CELLS * dx / len);
        s.stage_y = cy + Math.round(STAGE_CELLS * dy / len);
    }

    private void walk(@NonNull Strike s, float now) {
        stagePoint(s);
        int[] c = centre(s.squad);
        if (MapAnalysis.dist2(c[0], c[1], s.stage_x, s.stage_y) <= 7 * 7) {
            setPhase(s, Phase.WAIT);
            ai.log("freeze squad arrived at " + s.target.getPlayerInfo().getName());
            return;
        }
        if (now - s.last_order >= 6f) {
            s.last_order = now;
            ai.landscapeOrder(s.squad.toArray(new Selectable<?>[0]), s.stage_x, s.stage_y, Action.MOVE, false);
        }
        armoryCheck(s);
    }

    private void await(@NonNull Strike s, float now) {
        Building armory = building(s.target, Race.BUILDING_ARMORY);
        if (armory != null) {
            if (armory.isComplete()) {
                ai.aiLog().count("freeze_late");
                goHome(s);
                return;
            }
            setPhase(s, Phase.STRIKE);
            ai.log("freeze strike on " + s.target.getPlayerInfo().getName() + ": armory site at " + armory.getGridX() + "," + armory.getGridY());
            return;
        }
        if (now - s.phase_time > ai.strategy().freeze_wait) {
            ai.aiLog().count("freeze_timeout");
            goHome(s);
            return;
        }
        stagePoint(s);
        if (now - s.last_order >= 6f) {
            s.last_order = now;
            ai.landscapeOrder(s.squad.toArray(new Selectable<?>[0]), s.stage_x, s.stage_y, Action.MOVE, false);
        }
    }

    /** A strike that arrives after the armory stands has missed its window. */
    private void armoryCheck(@NonNull Strike s) {
        Building armory = building(s.target, Race.BUILDING_ARMORY);
        if (armory != null && armory.isComplete()) {
            ai.aiLog().count("freeze_late");
            goHome(s);
        }
    }

    private void strike(@NonNull Strike s, float now) {
        Building armory = building(s.target, Race.BUILDING_ARMORY);
        if (armory == null) {
            // The site was dropped or razed: the copy will place another one with the survivors.
            if (now - s.phase_time > 60f) {
                goHome(s);
                return;
            }
        } else if (armory.isComplete()) {
            ai.aiLog().count("freeze_failed");
            ai.log("freeze strike on " + s.target.getPlayerInfo().getName() + " failed: armory finished");
            goHome(s);
            return;
        }
        List<Unit> prey = outsideUnits(s.target);
        if (prey.isEmpty()) {
            if (armory != null && !frozen.contains(s.target)) {
                frozen.add(s.target);
                ai.aiLog().count("freeze_frozen");
                ai.log("froze " + s.target.getPlayerInfo().getName() + " at " + (int) now + "s");
            }
            if (ai.strategy().freeze_raze && quarters(s.target) != null)
                setPhase(s, Phase.RAZE);
            else
                goHome(s);
            return;
        }
        if (now - s.phase_time > ai.strategy().freeze_strike_time) {
            ai.aiLog().count("freeze_timeout");
            goHome(s);
            return;
        }
        assign(s, prey);
    }

    /** Every squad peon without a live target goes for the nearest prey that has fewer than three hunters. */
    private void assign(@NonNull Strike s, @NonNull List<@NonNull Unit> prey) {
        List<Unit> busy = new ArrayList<>();
        List<Unit> targets = new ArrayList<>();
        for (Unit u : s.squad) {
            Controller c = u.getCurrentController();
            Selectable<?> t = c instanceof HuntController h ? h.getTarget() : c instanceof AttackController a ? a.getTarget() : null;
            if (t instanceof Unit tu && !tu.isDead() && prey.contains(tu)) {
                busy.add(u);
                targets.add(tu);
            }
        }
        for (Unit u : s.squad) {
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
            if (best == null && !prey.isEmpty())
                best = prey.getFirst();
            if (best != null) {
                ai.owner().setTarget(Selectable.newArray(u), best, Action.ATTACK, false);
                targets.add(best);
            }
        }
    }

    /** The copy's units in the field (not inside buildings or towers). */
    private static @NonNull List<@NonNull Unit> outsideUnits(@NonNull Player p) {
        List<Unit> units = new ArrayList<>();
        for (Selectable<?> sel : p.getUnits().getSet())
            if (sel instanceof Unit u && !u.isDead() && !u.isMounted())
                units.add(u);
        return units;
    }

    private void raze(@NonNull Strike s, float now) {
        Building q = quarters(s.target);
        if (q == null || !s.target.isAlive()) {
            ai.aiLog().count("freeze_out");
            ai.log("razed the frozen " + s.target.getPlayerInfo().getName() + " at " + (int) now + "s");
            goHome(s);
            return;
        }
        List<Unit> prey = outsideUnits(s.target);
        for (Unit u : prey) {
            if (u.getAbilities().hasAbilities(Abilities.THROW) || u.getAbilities().hasAbilities(Abilities.MAGIC)) {
                // Warriors or a chieftain: the copy is not frozen after all.
                ai.aiLog().count("freeze_armed");
                goHome(s);
                return;
            }
        }
        if (!prey.isEmpty()) {
            assign(s, prey);
            return;
        }
        if (now - s.last_order >= 5f) {
            s.last_order = now;
            ai.owner().setTarget(s.squad.toArray(new Selectable<?>[0]), q, Action.ATTACK, false);
        }
    }

    private void goHome(@NonNull Strike s) {
        setPhase(s, Phase.HOME);
    }

    private void home(@NonNull Strike s, @NonNull Intel intel) {
        Building home = intel.armory();
        if (home == null && !intel.quarters.isEmpty())
            home = intel.quarters.getFirst();
        if (home != null && !home.isDead() && !s.squad.isEmpty())
            ai.owner().setTarget(s.squad.toArray(new Selectable<?>[0]), home, Action.DEFAULT, false);
        else if (!s.squad.isEmpty())
            ai.landscapeOrder(s.squad.toArray(new Selectable<?>[0]), ai.planner().getStartX(), ai.planner().getStartY(),
                    Action.MOVE, false);
        for (Unit u : s.squad)
            intel.strikers.remove(u);
        s.phase = Phase.DONE;
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
}
