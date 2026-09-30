package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.gauntlet.Intel.PeonState;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A shepherd peon per Hard copy that draws each of its waves onto empty ground away from our base.
 *
 * <p>A copy aims its wave from its oldest idle warrior at our nearest selectable of any kind if that one is closer
 * than 0.707 of our nearest building, else at that building (AdvancedAI.findTarget, Player.findNearestEnemy), and
 * orders an attack-move to that target's cell as it stands (a snapshot). A lone peon of ours standing 14-22 cells from
 * that warrior, where no enemy sees it (idle and walking units scan 8 cells), is that target. When the wave starts
 * walking the peon steps away, faster than the warriors (5 m/s against 4), and the wave arrives on an empty cell and
 * goes idle there, blind beyond 8 cells. Its survivors lead the copy's next wave, so the next spot is picked from
 * them, always on their far side from our base. A lone peon sets off no chieftain spell (the stun and poison want 5
 * enemy units within reach, lightning 2), and it keeps out of the copy's 30 m defense circle around its quarters and
 * armory (the study's exploit-first plan, lab/gauntlet/NOTES.md).
 */
final class Shepherd {
    /** Idle and walking units scan a Chebyshev square of 8 cells; keep this far from every enemy unit. */
    private static final int CLEAR_CELLS = 12;
    /** Cells from a copy's quarters and armory where its defense starts (30 m). */
    private static final int DEFENSE_CELLS = 17;
    private static final int TOWER_CELLS = 19;
    /** shepherd_sticky: ring cells this near the current spot share its bonus. */
    private static final int STICKY_CELLS = 4;
    /** shepherd_safe_walk: candidates tried, best first, for a walk clear of enemy warriors. */
    private static final int SAFE_TRIES = 12;
    /** shepherd_home_pair and shepherd_follow: a copy's warrior this near its armory stands at home. */
    private static final int HOME_CELLS = 40;
    /** shepherd_safe_walk: seconds a flee runs before the shepherd heads back for its spot. */
    private static final float FLEE_HOLD = 3f;

    private final @NonNull GauntletAI ai;
    private final List<@NonNull Flock> flocks = new ArrayList<>();
    private float last_tick = -10f;
    /** Log only: the threats the last threatAway counted. */
    private int threat_coming;
    private int threat_peons;
    private int threat_hunters;
    private int threat_warriors;
    /** findSpot's legal candidates, reused. */
    private int[] cand_x = new int[128];
    private int[] cand_y = new int[128];
    private float[] cand_score = new float[128];

    private static final class Flock {
        final @NonNull Player copy;
        @Nullable
        Unit shepherd;
        int spot_x = -1;
        int spot_y = -1;
        @Nullable
        Unit leader;
        float last_order = -100f;
        float nospot_since = -1f;
        /** shepherd_sticky: since when enemies have blocked the current spot, -1 while it is clear. */
        float blocked_since = -1f;
        /** shepherd_safe_walk: after a flee the shepherd heads back for its spot only from this time on. */
        float flee_until = -1f;
        /** Log and counters only: what the shepherd did at the last tend (walk, at, flee, nospot). */
        @NonNull
        String last_state = "walk";
        /** When the copy's last shepherd was lost (shepherd_gap). */
        float lost_at = -1000f;
        float recruited;
        int last_x;
        int last_y;
        /** Launches seen: the copy's wave size is 10 + 5 per launch, up to 40 (AdvancedAI NUM_WARRORS). */
        int launches;
        /** Whether the copy's next decision (every 5-7 s) launches a wave: idle warriors >= wave size (+ chieftain). */
        boolean imminent;
        /** shepherd_lead: the first tend at which the copy had a finished armory, -1 before. */
        float armory_at = -1f;
        /** Launches at our base (front_order 2). */
        int base_waves;
        // Log only (maxn K2), never read by a decision (prev_wave aside): arrival, spot jumps and launches.
        boolean arrived;
        int rec_x;
        int rec_y;
        int rec_spot_x;
        int rec_spot_y;
        int prev_spot_x = -1;
        int prev_spot_y;
        @NonNull
        String origin = "";
        int lead_x;
        int lead_y;
        /** The leader of the copy's last launch (read by shepherd_follow). */
        @Nullable
        Unit prev_wave;

        /**
         * shepherd_home_pair: a home flock's shepherd stands by the copy's home (its armory, or its oldest idle warrior
         * there); its partner, the copy's other flock, follows the copy's field wave.
         */
        final boolean home;
        @Nullable
        Flock partner;

        Flock(@NonNull Player copy, boolean home) {
            this.copy = copy;
            this.home = home;
        }
    }

    Shepherd(@NonNull GauntletAI ai) {
        this.ai = ai;
    }

    void tick() {
        Strategy strategy = ai.strategy();
        if (!strategy.shepherd || ai.time() - last_tick < .5f)
            return;
        last_tick = ai.time();
        // shepherd_lead: flocks watch for the copies' armories from 90 s; tend recruits nothing before shepherd_time.
        float from = strategy.shepherd_lead > 0f ? Math.min(90f, strategy.shepherd_time) : strategy.shepherd_time;
        if (ai.time() < from || ai.time() > strategy.shepherd_until) {
            releaseAll();
            return;
        }
        Intel intel = ai.intel();
        List<Player> fresh = new ArrayList<>();
        for (Player p : ai.owner().getWorld().getPlayers())
            if (ai.owner().isEnemy(p) && p.isAlive() && flockOf(p) == null)
                fresh.add(p);
        if (strategy.shepherd_lead > 0f && fresh.size() > 1) {
            // Far copies first, so their shepherds get the scarce peons (a stable sort: ties stay in slot order).
            int sx = ai.planner().getStartX();
            int sy = ai.planner().getStartY();
            fresh.sort(Comparator.comparingInt(p -> -MapAnalysis.dist2(sx, sy, UnitGrid.toGridCoordinate(
                    p.getStartX()), UnitGrid.toGridCoordinate(p.getStartY()))));
        }
        for (Player p : fresh)
            flocks.add(new Flock(p, false));
        int pair = strategy.shepherd_home_pair;
        if (pair > 0)
            for (Player p : fresh) {
                // After every copy's own flock, so those get peons first.
                int d2 = MapAnalysis.dist2(ai.planner().getStartX(), ai.planner().getStartY(),
                        UnitGrid.toGridCoordinate(p.getStartX()), UnitGrid.toGridCoordinate(p.getStartY()));
                Flock main = flockOf(p);
                if (d2 >= pair * pair && main != null) {
                    Flock h = new Flock(p, true);
                    h.partner = main;
                    main.partner = h;
                    flocks.add(h);
                }
            }
        for (Flock f : flocks) {
            if (f.shepherd != null && f.shepherd.isDead()) {
                ai.aiLog().count("shepherd_lost");
                ai.aiLog().count("shepherd_lost_" + f.last_state);
                f.lost_at = ai.time();
                if (ai.logging())
                    ai.log("shepherd of " + name(
                            f) + " lost at " + f.last_x + "," + f.last_y + " (spot " + f.spot_x + "," + f.spot_y + ", nearest enemy warrior " + nearestEnemy(
                                    ai.intel().enemy_warriors, f.last_x, f.last_y) + " cells, peon " + nearestEnemy(
                                            ai.intel().enemy_peons, f.last_x, f.last_y) + ", tower " + nearestTower(
                                                    f.last_x,
                                                    f.last_y) + ", recruited " + (int) (ai.time() - f.recruited) + " s ago, " + f.last_state + ", last flee " + (f.flee_until < 0f ? "never" : (int) (ai.time() - f.flee_until + FLEE_HOLD) + " s ago") + ")");
                release(f);
            }
            if (!f.copy.isAlive()) {
                release(f);
                continue;
            }
            tend(f, intel);
        }
    }

    /** Every few ticks: a shepherd with enemies close or a wave walking at it runs at once. */
    void guard() {
        if (!ai.strategy().shepherd || flocks.isEmpty())
            return;
        Intel intel = ai.intel();
        for (Flock f : flocks) {
            Unit s = f.shepherd;
            if (s == null || s.isDead() || s.isMounted())
                continue;
            int clear = ai.strategy().shepherd_hold && f.imminent ? 9 : CLEAR_CELLS;
            int[] away = threatAway(s, intel, clear);
            if (away != null && ai.time() - f.last_order >= .3f)
                flee(f, s, away);
        }
    }

    /** The flock's name in logs: the copy's, with "/home" for a home flock (shepherd_home_pair). */
    private static @NonNull String name(@NonNull Flock f) {
        return f.copy.getPlayerInfo().getName() + (f.home ? "/home" : "");
    }

    private void flee(@NonNull Flock f, @NonNull Unit s, int @NonNull [] away) {
        ai.landscapeOrder(Selectable.newArray(s), away[0], away[1], Action.MOVE, false);
        f.last_order = ai.time();
        f.flee_until = ai.time() + FLEE_HOLD;
    }

    private @Nullable Flock flockOf(@NonNull Player p) {
        for (Flock f : flocks)
            if (f.copy == p)
                return f;
        return null;
    }

    private void releaseAll() {
        for (Flock f : flocks)
            release(f);
    }

    private void release(@NonNull Flock f) {
        if (f.shepherd != null) {
            ai.intel().shepherds.remove(f.shepherd);
            if (!f.shepherd.isDead())
                sendHome(f.shepherd);
        }
        f.shepherd = null;
        f.spot_x = -1;
        f.leader = null;
    }

    /**
     * rearm_placer: lets the economy take shepherd u as the placer of a lost armory (its flock recruits again), and
     * whether u was one of ours.
     */
    boolean giveUp(@NonNull Unit u) {
        for (Flock f : flocks)
            if (f.shepherd == u) {
                ai.intel().shepherds.remove(u);
                f.shepherd = null;
                f.spot_x = -1;
                f.leader = null;
                f.lost_at = ai.time();
                ai.aiLog().count("shepherd_given_up");
                return true;
            }
        return false;
    }

    private void sendHome(@NonNull Unit u) {
        Building home = ai.intel().armory();
        if (home == null && !ai.intel().quarters.isEmpty())
            home = ai.intel().quarters.getFirst();
        if (home != null && !home.isDead())
            ai.owner().setTarget(Selectable.newArray(u), home, Action.DEFAULT, false);
    }

    private void tend(@NonNull Flock f, @NonNull Intel intel) {
        Strategy strategy = ai.strategy();
        if (strategy.shepherd_lead > 0f && f.armory_at < 0f && armory(f.copy) != null)
            f.armory_at = ai.time();
        Unit leader = oldestIdleWarrior(f.copy);
        int ox;
        int oy;
        String origin;
        if (f.home) {
            // shepherd_home_pair: the copy's oldest idle warrior while it stands at home, else the armory.
            Building armory = armory(f.copy);
            if (armory == null)
                return;
            boolean at_home = leader != null && MapAnalysis.dist2(leader.getGridX(), leader.getGridY(),
                    armory.getGridX(), armory.getGridY()) <= HOME_CELLS * HOME_CELLS;
            ox = at_home ? leader.getGridX() : armory.getGridX();
            oy = at_home ? leader.getGridY() : armory.getGridY();
            origin = at_home ? "leader" : "armory";
        } else if (leader != null) {
            ox = leader.getGridX();
            oy = leader.getGridY();
            origin = "leader";
        } else {
            Building armory = armory(f.copy);
            if (armory == null)
                return; // no armory, no warriors: no wave to steer yet
            ox = armory.getGridX();
            oy = armory.getGridY();
            origin = "armory";
        }
        // A wave started: count it if it heads for our spot, and let the shepherd clear out (the copy's own flock
        // counts, not its home flock).
        if (!f.home && f.leader != null && f.leader != leader && !f.leader.isDead()
                && f.leader.getPrimaryController() instanceof WalkController walk && walk.isAgressive()) {
            int tx = walk.getTarget().getGridX();
            int ty = walk.getTarget().getGridY();
            // An idle warrior that spots something walks back to its own cell after the hunt: not a wave.
            if (MapAnalysis.dist2(tx, ty, f.leader.getGridX(), f.leader.getGridY()) > 20 * 20) {
                f.launches++;
                if (f.spot_x >= 0 && MapAnalysis.dist2(tx, ty, f.spot_x, f.spot_y) <= 4 * 4) {
                    ai.aiLog().count("wave_drawn");
                    ai.log("wave of " + name(f) + " drawn to " + tx + "," + ty);
                } else {
                    Building near = nearestOwnBuilding(tx, ty);
                    boolean base = near != null && MapAnalysis.dist2(near.getGridX(), near.getGridY(), tx,
                            ty) <= 20 * 20;
                    // A wave drawn to one of our decoy sites is not a base wave.
                    boolean decoy = base && ai.decoys().isDecoy(near);
                    if (base && !decoy)
                        f.base_waves++;
                    ai.aiLog().count(decoy ? "wave_to_decoy" : base ? "wave_to_base" : "wave_elsewhere");
                    ai.log("wave of " + name(
                            f) + " goes to " + tx + "," + ty + (decoy ? " (our decoy)" : base ? " (our base)" : "") + " (spot " + f.spot_x + "," + f.spot_y + ")");
                }
                if (nearShepherd(tx, ty, 6))
                    ai.aiLog().count("wave_to_shepherd");
                if (ai.logging())
                    logLaunch(f, tx, ty);
                f.prev_wave = f.leader;
            }
        }
        f.leader = leader;
        if (leader != null) {
            f.lead_x = leader.getGridX();
            f.lead_y = leader.getGridY();
        }
        if (!f.home && leader != null && (strategy.shepherd_follow || f.partner != null)) {
            // shepherd_follow (and the field flock of shepherd_home_pair): while the copy's oldest idle warrior is at
            // home and its last wave is still out (walking, hunting), the wave's survivors will lead the next launch
            // from where they go idle, so the shepherd waits by the wave's target (or its leader), not at home.
            Unit prev = f.prev_wave;
            Building armory = armory(f.copy);
            if (prev != null && !prev.isDead() && armory != null
                    && !(prev.getPrimaryController() instanceof IdleController) && MapAnalysis.dist2(ox, oy,
                            armory.getGridX(), armory.getGridY()) <= HOME_CELLS * HOME_CELLS) {
                if (prev.getPrimaryController() instanceof WalkController walk && walk.isAgressive()) {
                    ox = walk.getTarget().getGridX();
                    oy = walk.getTarget().getGridY();
                } else {
                    ox = prev.getGridX();
                    oy = prev.getGridY();
                }
                origin = "wave";
                ai.aiLog().count("shepherd_follow");
            }
        }
        if (ai.strategy().shepherd_hold) {
            int num = Math.min(40, 10 + 5 * f.launches);
            int idle = 0;
            for (Unit e : intel.enemy_warriors)
                if (!e.isDead() && e.getOwner() == f.copy
                        && e.getPrimaryController() instanceof IdleController)
                    idle++;
            boolean was = f.imminent;
            f.imminent = idle >= num && (num < 20 || f.copy.hasActiveChieftain());
            if (f.imminent && !was)
                ai.aiLog().count("shepherd_imminent");
        }
        if (ai.time() < strategy.shepherd_time)
            return; // shepherd_lead: before shepherd_time flocks only watch
        if (f.shepherd == null) {
            // shepherd_gap: after a shepherd is lost, its copy waits that long for the next one.
            if (ai.time() - f.lost_at < strategy.shepherd_gap) {
                ai.aiLog().count("shepherd_gap_wait");
                return;
            }
            // shepherd_range: far copies' shepherds walk 150-300 cells and die on the way (N=10 logs); skip them.
            int range = ai.strategy().shepherd_range;
            if (range < 100000 && MapAnalysis.dist2(ox, oy, ai.planner().getStartX(),
                    ai.planner().getStartY()) > range * range)
                return;
            // Only when some spot would draw this copy's wave, counting every unit of ours as a rival target.
            int[] probe = findSpot(f, ox, oy, null, intel);
            if (probe == null) {
                ai.aiLog().count("shepherd_t_none");
                if (ai.strategy().site_shepherd && ai.time() >= ai.strategy().shepherd_time)
                    ai.decoys().placeHome(f.copy, ox, oy);
                return;
            }
            Unit candidate = recruit(ox, oy, intel);
            if (candidate == null) {
                ai.aiLog().count("shepherd_t_none");
                ai.aiLog().count("shepherd_norecruit");
                return;
            }
            // shepherd_lead: before the copy's first launch, while it has no idle warrior to launch, the shepherd
            // leaves only in time to stand on the spot shepherd_lead_margin s before armory_at + shepherd_lead.
            boolean lead = strategy.shepherd_lead > 0f && f.launches == 0 && leader == null;
            int walk2 = MapAnalysis.dist2(candidate.getGridX(), candidate.getGridY(), probe[0], probe[1]);
            if (lead) {
                float eta = (float) Math.sqrt(walk2) / strategy.shepherd_speed + 5f;
                if (f.armory_at < 0f
                        || ai.time() < f.armory_at + strategy.shepherd_lead - eta - strategy.shepherd_lead_margin) {
                    ai.aiLog().count("shepherd_wait");
                    return;
                }
            }
            PeonState state = intel.peon_states.get(candidate);
            f.shepherd = candidate;
            intel.shepherds.add(candidate);
            f.recruited = ai.time();
            f.nospot_since = -1f;
            ai.aiLog().count("shepherd_recruit");
            if (loaded(candidate))
                ai.aiLog().count("shepherd_recruit_loaded");
            if (f.home)
                ai.aiLog().count("shepherd_recruit_home");
            if (lead)
                ai.aiLog().count("shepherd_lead_recruit");
            f.arrived = false;
            f.last_state = "walk";
            f.flee_until = -1f;
            f.rec_x = candidate.getGridX();
            f.rec_y = candidate.getGridY();
            f.rec_spot_x = probe[0];
            f.rec_spot_y = probe[1];
            f.prev_spot_x = -1;
            if (ai.logging())
                ai.log("shepherd of " + name(f) + " recruited: " + (state == null ? "?" : state.name().toLowerCase(
                        Locale.ROOT)) + (loaded(
                                candidate) ? " loaded" : "") + " peon at " + f.rec_x + "," + f.rec_y + ", spot " + probe[0] + "," + probe[1] + ", walk " + (int) Math.sqrt(
                                        walk2) + " cells, origin " + origin + (lead ? ", lead (armory at " + (int) f.armory_at + " s)" : ""));
        }
        Unit s = f.shepherd;
        int moved = Math.abs(s.getGridX() - f.last_x) + Math.abs(s.getGridY() - f.last_y);
        f.last_x = s.getGridX();
        f.last_y = s.getGridY();
        // Flee first: any enemy near the shepherd, or a wave walking toward where it stands.
        int[] away = threatAway(s, intel, ai.strategy().shepherd_hold && f.imminent ? 9 : CLEAR_CELLS);
        if (away != null) {
            ai.aiLog().count("shepherd_t_flee");
            f.last_state = "flee";
            if (ai.logging())
                ai.log("flee of " + name(
                        f) + " at " + s.getGridX() + "," + s.getGridY() + " (moved " + moved + ") from " + threat_warriors + " warriors, " + threat_hunters + " hunters, " + threat_peons + " peons, " + threat_coming + " coming, to " + away[0] + "," + away[1]);
            if (ai.time() - f.last_order >= .3f)
                flee(f, s, away);
            return;
        }
        int[] spot = findSpot(f, ox, oy, s, intel);
        if (spot == null) {
            // No spot draws this copy's wave: a shepherd left standing there only gets killed, so after a while it
            // goes home (a new one is recruited once a spot opens up again).
            f.spot_x = -1;
            ai.aiLog().count("shepherd_t_nospot");
            f.last_state = "nospot";
            if (f.nospot_since < 0f)
                f.nospot_since = ai.time();
            else if (ai.time() - f.nospot_since > ai.strategy().shepherd_patience) {
                ai.aiLog().count("shepherd_home");
                release(f);
            }
            return;
        }
        f.nospot_since = -1f;
        if (f.prev_spot_x >= 0 && MapAnalysis.dist2(f.prev_spot_x, f.prev_spot_y, spot[0], spot[1]) > 30 * 30) {
            ai.aiLog().count("shepherd_spot_jump");
            if (ai.logging())
                ai.log("spot of " + name(f) + " jumps " + (int) Math.sqrt(MapAnalysis.dist2(
                        f.prev_spot_x, f.prev_spot_y, spot[0],
                        spot[1])) + " cells from " + f.prev_spot_x + "," + f.prev_spot_y + " (origin " + f.origin + ") to " + spot[0] + "," + spot[1] + " (origin " + origin + ")");
        }
        if (spot[0] != f.spot_x || spot[1] != f.spot_y)
            f.blocked_since = -1f;
        f.prev_spot_x = spot[0];
        f.prev_spot_y = spot[1];
        f.origin = origin;
        f.spot_x = spot[0];
        f.spot_y = spot[1];
        if (!f.arrived && MapAnalysis.dist2(s.getGridX(), s.getGridY(), spot[0], spot[1]) <= 3 * 3) {
            f.arrived = true;
            ai.aiLog().count("shepherd_at_spot");
            if (ai.logging())
                ai.log("shepherd of " + name(
                        f) + " at spot after " + (int) (ai.time() - f.recruited) + " s (from " + f.rec_x + "," + f.rec_y + ", " + (int) Math.sqrt(
                                MapAnalysis.dist2(
                                        f.rec_x, f.rec_y, s.getGridX(),
                                        s.getGridY())) + " cells; spot moved " + (int) Math.sqrt(MapAnalysis.dist2(
                                                f.rec_spot_x,
                                                f.rec_spot_y, spot[0], spot[1])) + " cells since recruited)");
        }
        boolean on_spot = MapAnalysis.dist2(s.getGridX(), s.getGridY(), spot[0], spot[1]) <= 3 * 3;
        ai.aiLog().count(on_spot ? "shepherd_t_atspot" : "shepherd_t_walk");
        f.last_state = on_spot ? "at" : "walk";
        // shepherd_safe_walk: a shepherd that just fled runs its full course before it heads back.
        boolean held = strategy.shepherd_safe_walk && ai.time() < f.flee_until;
        if (MapAnalysis.dist2(s.getGridX(), s.getGridY(), spot[0], spot[1]) > 2 * 2
                && ai.time() - f.last_order >= 2f && !held) {
            ai.landscapeOrder(Selectable.newArray(s), spot[0], spot[1], Action.MOVE, false);
            f.last_order = ai.time();
        }
    }

    /** The copy's oldest idle warrior: the first one in its Army order, as its getIdleWarriors()[0]. */
    private static @Nullable Unit oldestIdleWarrior(@NonNull Player p) {
        for (Selectable<?> sel : p.getUnits().getSet()) {
            if (!(sel instanceof Unit u) || u.isDead() || u.isMounted())
                continue;
            if (!(u.getPrimaryController() instanceof IdleController))
                continue;
            if (u.getAbilities().hasAbilities(Abilities.BUILD) || u.getAbilities().hasAbilities(Abilities.MAGIC))
                continue;
            if (u.getAbilities().hasAbilities(Abilities.ATTACK))
                return u;
        }
        return null;
    }

    private static @Nullable Building armory(@NonNull Player p) {
        for (Selectable<?> sel : p.getUnits().getSet())
            if (sel instanceof Building b && !b.isDead() && b.isComplete()
                    && b.getTemplate().getTemplateID() == Race.BUILDING_ARMORY)
                return b;
        return null;
    }

    private @Nullable Unit recruit(int ox, int oy, @NonNull Intel intel) {
        Unit best = null;
        int best_d = Integer.MAX_VALUE;
        for (Unit p : intel.peons) {
            PeonState st = intel.peon_states.get(p);
            if (st != PeonState.IDLE && st != PeonState.GATHER_TREE && st != PeonState.GATHER_ROCK
                    && st != PeonState.GATHER_IRON && st != PeonState.TRANSIT && st != PeonState.MOVE)
                continue;
            if (intel.shepherds.contains(p) || ai.economy().reservedPlacer(p))
                continue;
            int danger = nearestEnemy(intel.enemy_warriors, p.getGridX(), p.getGridY());
            if (danger >= 0 && danger <= 14)
                continue;
            int d = MapAnalysis.dist2(p.getGridX(), p.getGridY(), ox, oy);
            if (d < best_d) {
                best_d = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Whether a peon carries a load: it then walks at 4 m/s (Unit.TRANSPORT_SPEED_SCALE), not 5, as fast as a warrior.
     */
    private static boolean loaded(@NonNull Unit peon) {
        return peon.getSupplyContainer() != null && peon.getSupplyContainer().getNumSupplies() > 0;
    }

    /** A point to run to when enemies are near the shepherd or a wave is walking at it, else null. */
    private int @Nullable [] threatAway(@NonNull Unit s, @NonNull Intel intel, int clear) {
        int sx = s.getGridX();
        int sy = s.getGridY();
        long ex = 0;
        long ey = 0;
        int n = 0;
        // The enemies near enough to count: warriors within the square or walking at us from 40 cells, peons within
        // the square (the sums do not depend on the order).
        EnemyIndex index = intel.enemyIndex(ai.ticks());
        threat_coming = 0;
        threat_peons = 0;
        threat_hunters = 0;
        threat_warriors = 0;
        int[] candidates = index.queryUnordered(sx, sy, Math.max(40 * 40, 2 * clear * clear));
        for (int k = 0, m = index.count(); k < m; k++) {
            byte group = index.group(candidates[k]);
            Unit e = index.unit(candidates[k]);
            if (group == EnemyIndex.CHIEFTAIN || e.isDead())
                continue;
            int dx = e.getGridX() - sx;
            int dy = e.getGridY() - sy;
            boolean near = Math.abs(dx) <= clear && Math.abs(dy) <= clear;
            boolean coming = false;
            if (group == EnemyIndex.WARRIOR && !near && e.getPrimaryController() instanceof WalkController w
                    && w.isAgressive() && dx * dx + dy * dy <= 40 * 40) {
                int tx = w.getTarget().getGridX() - sx;
                int ty = w.getTarget().getGridY() - sy;
                coming = tx * tx + ty * ty <= 14 * 14;
            }
            if (near || coming) {
                ex += e.getGridX();
                ey += e.getGridY();
                n++;
                // Log only: what the shepherd runs from.
                if (coming)
                    threat_coming++;
                else if (group == EnemyIndex.PEON)
                    threat_peons++;
                else if (e.getCurrentController() instanceof HuntController hunt && hunt.getTarget() == s)
                    threat_hunters++;
                else
                    threat_warriors++;
            }
        }
        if (n == 0)
            return null;
        float cx = (float) ex / n;
        float cy = (float) ey / n;
        float dx = sx - cx;
        float dy = sy - cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < .5f) {
            dx = ai.planner().getStartX() - sx;
            dy = ai.planner().getStartY() - sy;
            len = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
        }
        return new int[]{sx + Math.round(22 * dx / len), sy + Math.round(22 * dy / len)};
    }

    /**
     * A cell on the rings 14 to shepherd_max_r cells from the copy's wave origin (24 cells to a ring), nearer to it
     * than 0.66 of our nearest building and 0.89 of our nearest other unit, with no enemy within shepherd_clear cells
     * along both axes, outside the copy's defense circles and enemy towers' reach, reachable from our start, and as far
     * from our start as possible (spotScore). With s null this is the recruiting probe, which the shepherd_sticky,
     * shepherd_travel and shepherd_safe_walk terms leave alone.
     */
    private int @Nullable [] findSpot(@NonNull Flock f, int ox, int oy, @Nullable Unit s, @NonNull Intel intel) {
        int building2 = nearestOwnBuilding2(ox, oy);
        if (building2 == Integer.MAX_VALUE)
            return null;
        int unit2 = nearestOtherUnit2(ox, oy, s, f.partner == null ? null : f.partner.shepherd);
        float limit = (float) Math.sqrt(Math.min(building2 * .44f, unit2 * .8f));
        int max_r = (int) Math.min(ai.strategy().shepherd_max_r, limit);
        // The per-candidate rejection counters (shepherd_rej_*) only in logged games: counted in every game they took
        // 2.2 % of the simulation's CPU (prof-cur4-vs14, AiLog.count under findSpot), the most of any one AI method.
        boolean rejections = ai.logging();
        if (rejections)
            // Candidate cells the leash cuts off.
            for (int r = 14; r <= ai.strategy().shepherd_max_r; r += r < 22 ? 2 : 4)
                if (r > max_r)
                    for (int a = 0; a < 24; a++)
                        ai.aiLog().count("shepherd_rej_leash");
        if (max_r < 14) {
            ai.aiLog().count(building2 * .44f < unit2 * .8f ? "shepherd_nospot_building" : "shepherd_nospot_unit");
            return null;
        }
        List<Building> guarded = new ArrayList<>();
        for (Selectable<?> sel : f.copy.getUnits().getSet())
            if (sel instanceof Building b && !b.isDead()
                    && b.getTemplate().getType() == BuildingTemplate.TYPE_BUILDING
                    && b.getTemplate().getTemplateID() != Race.BUILDING_TOWER)
                guarded.add(b);
        DistanceField reach = ai.planner().getStartField();
        int bx = ai.planner().getStartX();
        int by = ai.planner().getStartY();
        Strategy strategy = ai.strategy();
        // shepherd_sticky, shepherd_travel and shepherd_safe_walk steer a shepherd's own spot, not the recruiting probe.
        boolean sticky = s != null && strategy.shepherd_sticky > 0f && f.spot_x >= 0;
        float travel = s == null ? 0f : strategy.shepherd_travel;
        boolean safe = s != null && strategy.shepherd_safe_walk;
        int n = 0;
        if (sticky) {
            // The current spot itself while it still leashes the wave: enemies passing by block it only after
            // shepherd_grace seconds (the flee in tend keeps the shepherd safe meanwhile).
            int x = f.spot_x;
            int y = f.spot_y;
            int r2 = MapAnalysis.dist2(x, y, ox, oy);
            if (r2 >= 12 * 12 && r2 <= max_r * max_r && reach.reachable(x, y) && coverAt(guarded, intel, x, y) == 0) {
                boolean blocked = enemyNear(intel, x, y) != null;
                if (!blocked)
                    f.blocked_since = -1f;
                else if (f.blocked_since < 0f)
                    f.blocked_since = ai.time();
                if (!blocked || ai.time() - f.blocked_since < strategy.shepherd_grace) {
                    float score = spotScore(x, y, (float) Math.sqrt(r2), bx, by, guarded) + strategy.shepherd_sticky;
                    if (travel > 0f)
                        score -= travel * (float) Math.sqrt(MapAnalysis.dist2(x, y, s.getGridX(), s.getGridY()));
                    n = addCandidate(n, x, y, score);
                }
            }
        }
        for (int r = 14; r <= max_r; r += r < 22 ? 2 : 4) {
            for (int a = 0; a < 24; a++) {
                double ang = a * Math.PI / 12;
                int x = ox + (int) Math.round(r * Math.cos(ang));
                int y = oy + (int) Math.round(r * Math.sin(ang));
                if (!reach.reachable(x, y)) {
                    if (rejections)
                        ai.aiLog().count("shepherd_rej_reach");
                    continue;
                }
                String enemy = enemyNear(intel, x, y);
                if (enemy != null) {
                    if (rejections)
                        ai.aiLog().count(enemy);
                    continue;
                }
                int cover = coverAt(guarded, intel, x, y);
                if (cover != 0) {
                    if (rejections)
                        ai.aiLog().count(cover == 1 ? "shepherd_rej_defense17" : "shepherd_rej_tower19");
                    continue;
                }
                float score = spotScore(x, y, r, bx, by, guarded);
                if (sticky && MapAnalysis.dist2(x, y, f.spot_x, f.spot_y) <= STICKY_CELLS * STICKY_CELLS)
                    score += strategy.shepherd_sticky;
                if (travel > 0f)
                    score -= travel * (float) Math.sqrt(MapAnalysis.dist2(x, y, s.getGridX(), s.getGridY()));
                n = addCandidate(n, x, y, score);
            }
        }
        if (n == 0) {
            ai.aiLog().count("shepherd_nospot_ground");
            return null;
        }
        // The best candidate, the first of equals in scan order; with shepherd_safe_walk the best of the first
        // SAFE_TRIES whose walk from the shepherd keeps clear of enemy warriors, else the best.
        int first = bestCandidate(n);
        if (!safe)
            return new int[]{cand_x[first], cand_y[first]};
        int pick = first;
        int first_x = cand_x[first];
        int first_y = cand_y[first];
        for (int tries = 0; tries < SAFE_TRIES; tries++) {
            if (safePath(s.getGridX(), s.getGridY(), cand_x[pick], cand_y[pick], intel)) {
                if (tries > 0)
                    ai.aiLog().count("shepherd_safe_detour");
                return new int[]{cand_x[pick], cand_y[pick]};
            }
            cand_score[pick] = -Float.MAX_VALUE;
            pick = bestCandidate(n);
            if (cand_score[pick] == -Float.MAX_VALUE)
                break;
        }
        ai.aiLog().count("shepherd_safe_none");
        return new int[]{first_x, first_y};
    }

    /** Score of a spot: far from our start, a little less for a wider ring (and shepherd_home_weight). */
    private float spotScore(int x, int y, float r, int bx, int by, @NonNull List<@NonNull Building> guarded) {
        float score = (float) Math.sqrt(MapAnalysis.dist2(x, y, bx, by)) - r * .5f;
        float home_weight = ai.strategy().shepherd_home_weight;
        if (home_weight > 0f && !guarded.isEmpty()) {
            int home = Integer.MAX_VALUE;
            for (Building b : guarded)
                home = Math.min(home, MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y));
            score += home_weight * (float) Math.sqrt(home);
        }
        return score;
    }

    /**
     * What covers (x, y): 1 within a defense circle of the copy (DEFENSE_CELLS from its quarters and armories), else 2
     * within TOWER_CELLS of an enemy tower, else 0.
     */
    private static int coverAt(@NonNull List<@NonNull Building> guarded, @NonNull Intel intel, int x, int y) {
        for (Building b : guarded)
            if (MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y) <= DEFENSE_CELLS * DEFENSE_CELLS)
                return 1;
        for (Building t : intel.enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= TOWER_CELLS * TOWER_CELLS)
                return 2;
        return 0;
    }

    private int addCandidate(int n, int x, int y, float score) {
        if (n == cand_x.length) {
            cand_x = Arrays.copyOf(cand_x, n * 2);
            cand_y = Arrays.copyOf(cand_y, n * 2);
            cand_score = Arrays.copyOf(cand_score, n * 2);
        }
        cand_x[n] = x;
        cand_y[n] = y;
        cand_score[n] = score;
        return n + 1;
    }

    /** The index of the highest score among the first n candidates, the first of equals. */
    private int bestCandidate(int n) {
        int best = 0;
        for (int i = 1; i < n; i++)
            if (cand_score[i] > cand_score[best])
                best = i;
        return best;
    }

    /**
     * shepherd_safe_walk: whether the first shepherd_safe_look cells of the straight walk from (sx, sy) to (x, y) keep
     * shepherd_safe_clear cells from every enemy warrior and chieftain (sampled every 6 cells).
     */
    private boolean safePath(int sx, int sy, int x, int y, @NonNull Intel intel) {
        Strategy strategy = ai.strategy();
        float dx = x - sx;
        float dy = y - sy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f)
            return true;
        float look = Math.min(len, strategy.shepherd_safe_look);
        int clear2 = strategy.shepherd_safe_clear * strategy.shepherd_safe_clear;
        EnemyIndex index = intel.enemyIndex(ai.ticks());
        for (float d = 0f;; d += 6f) {
            float t = Math.min(d, look);
            int px = sx + Math.round(dx * t / len);
            int py = sy + Math.round(dy * t / len);
            int[] found = index.queryUnordered(px, py, clear2);
            for (int k = 0, m = index.count(); k < m; k++)
                if (!index.isPeon(found[k]) && !index.unit(found[k]).isDead())
                    return false;
            if (t >= look)
                return true;
        }
    }

    /**
     * Null when no enemy unit (dead ones still in Intel's lists included) stands within shepherd_clear cells of (x, y)
     * along both axes, else the findSpot rejection counter: warriors first, then peons, then chieftains.
     */
    private @Nullable String enemyNear(@NonNull Intel intel, int x, int y) {
        int clear = ai.strategy().shepherd_clear;
        EnemyIndex index = intel.enemyIndex(ai.ticks());
        int groups = index.groupsInBox(x, y, clear);
        if ((groups & 1 << EnemyIndex.WARRIOR) != 0)
            return "shepherd_rej_warrior";
        if ((groups & 1 << EnemyIndex.PEON) != 0)
            return "shepherd_rej_peon";
        return (groups & 1 << EnemyIndex.CHIEFTAIN) != 0 ? "shepherd_rej_chief" : null;
    }

    /** Base-bound waves seen from a copy so far (front_order 2). */
    int baseWaves(@NonNull Player p) {
        Flock f = flockOf(p);
        return f == null ? 0 : f.base_waves;
    }

    /** Whether a live shepherd of ours stands within r cells of (x, y). */
    private boolean nearShepherd(int x, int y, int r) {
        for (Flock f : flocks) {
            Unit s = f.shepherd;
            if (s != null && !s.isDead() && MapAnalysis.dist2(s.getGridX(), s.getGridY(), x, y) <= r * r)
                return true;
        }
        return false;
    }

    /**
     * Log only (K2): a launch's origin (the prior leader's cell), the state of the copy's previous wave, what it goes
     * for, the nearest unit of ours to its target and that unit's role, and how far the copy's own shepherd is.
     */
    private void logLaunch(@NonNull Flock f, int tx, int ty) {
        Unit prev = f.prev_wave;
        String prev_state = prev == null ? "none" : prev.isDead() ? "dead" : prev.getPrimaryController() instanceof WalkController ? "walking" : prev.getPrimaryController() instanceof IdleController ? "idle" : prev.getPrimaryController() instanceof HuntController ? "hunting" : "busy";
        Unit nearest = null;
        int nearest_d = Integer.MAX_VALUE;
        for (Selectable<?> sel : ai.owner().getUnits().getSet())
            if (sel instanceof Unit u && !u.isDead() && !u.isMounted()) {
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), tx, ty);
                if (d < nearest_d) {
                    nearest_d = d;
                    nearest = u;
                }
            }
        Unit s = f.shepherd;
        int shepherd_d = s == null || s.isDead() ? -1 : (int) Math.sqrt(MapAnalysis.dist2(s.getGridX(), s.getGridY(),
                tx, ty));
        // Where the copy's own shepherd stood at the launch, seen from the origin, and our nearest building.
        String own = s == null || s.isDead() ? "none" : (f.spot_x < 0 ? "nospot" : MapAnalysis.dist2(s.getGridX(),
                s.getGridY(), f.spot_x, f.spot_y) <= 6 * 6 ? "atspot" : "away") + " " + (int) Math.sqrt(
                        MapAnalysis.dist2(s.getGridX(), s.getGridY(), f.lead_x, f.lead_y)) + " cells";
        int building2 = nearestOwnBuilding2(f.lead_x, f.lead_y);
        String prev_at = prev == null || prev.isDead() ? "none" : prev.getGridX() + "," + prev.getGridY();
        ai.log("launch of " + name(
                f) + " from " + f.lead_x + "," + f.lead_y + " (previous wave " + prev_state + ") to " + tx + "," + ty + ": target " + targetClass(
                        tx, ty) + ", nearest unit " + (nearest == null ? "none" : roleOf(
                                nearest) + " " + (int) Math.sqrt(
                                        nearest_d) + " cells") + ", own shepherd " + shepherd_d + " cells; origin: shepherd " + own + ", building " + (building2 == Integer.MAX_VALUE ? -1 : (int) Math.sqrt(
                                                building2)) + " cells, previous wave at " + prev_at);
    }

    /**
     * Log only (K2): what a wave aimed at (tx, ty) goes for: our tower, tower site, decoy site, quarters or armory
     * (or their sites) standing there, else a unit of ours by a shepherd, by our quarters or armory, or in the field.
     */
    private @NonNull String targetClass(int tx, int ty) {
        Selectable<?> best = null;
        int best_d = Integer.MAX_VALUE;
        for (Selectable<?> sel : ai.owner().getUnits().getSet()) {
            if (sel.isDead() || (sel instanceof Unit u && u.isMounted()))
                continue;
            int d = MapAnalysis.dist2(sel.getGridX(), sel.getGridY(), tx, ty);
            if (d < best_d) {
                best_d = d;
                best = sel;
            }
        }
        if (best == null || best_d > 4 * 4)
            return "none";
        if (best instanceof Building b) {
            boolean done = b.isComplete();
            return switch (b.getTemplate().getTemplateID()) {
                case Race.BUILDING_TOWER -> done ? "tower" : ai.decoys().isDecoy(b) ? "decoy_site" : "tower_site";
                case Race.BUILDING_ARMORY -> done ? "armory" : "armory_site";
                case Race.BUILDING_QUARTERS -> done ? "quarters" : "quarters_site";
                default -> "building";
            };
        }
        if (nearShepherd(tx, ty, 6))
            return "unit_by_shepherd";
        Intel intel = ai.intel();
        for (Building b : intel.quarters)
            if (MapAnalysis.dist2(b.getGridX(), b.getGridY(), tx, ty) <= 20 * 20)
                return "unit_by_base";
        for (Building b : intel.armories)
            if (MapAnalysis.dist2(b.getGridX(), b.getGridY(), tx, ty) <= 20 * 20)
                return "unit_by_base";
        return "field_unit";
    }

    /** Log only (K2): a unit's job, as the Shepherd, the economy or the military sees it. */
    private @NonNull String roleOf(@NonNull Unit u) {
        Intel intel = ai.intel();
        for (Flock f : flocks)
            if (f.shepherd == u)
                return "shepherd of " + name(f);
        if (intel.shepherds.contains(u))
            return "shepherd";
        if (intel.lures.contains(u))
            return "lure";
        if (intel.sappers.contains(u))
            return "sapper";
        PeonState state = intel.peon_states.get(u);
        if (state != null)
            return state.name().toLowerCase(Locale.ROOT);
        if (u == intel.chieftain)
            return "chieftain";
        String role = ai.military().roleOf(u);
        return role != null ? role : "warrior";
    }

    private static int nearestEnemy(java.util.@NonNull List<@NonNull Unit> units, int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Unit u : units)
            if (!u.isDead())
                best = Math.min(best, MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y));
        return best == Integer.MAX_VALUE ? -1 : (int) Math.sqrt(best);
    }

    private int nearestTower(int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Building t : ai.intel().enemy_towers)
            best = Math.min(best, MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y));
        return best == Integer.MAX_VALUE ? -1 : (int) Math.sqrt(best);
    }

    /** Our building or site nearest (x, y) (towers and decoy sites included), or null. */
    private @Nullable Building nearestOwnBuilding(int x, int y) {
        Building best = null;
        int best_d = Integer.MAX_VALUE;
        for (Selectable<?> sel : ai.owner().getUnits().getSet())
            if (sel instanceof Building b && !b.isDead()
                    && b.getTemplate().getType() == BuildingTemplate.TYPE_BUILDING) {
                        int d = MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y);
                        if (d < best_d) {
                            best_d = d;
                            best = b;
                        }
                    }
        return best;
    }

    /** The squared cells from (x, y) to our nearest building or site, Integer.MAX_VALUE with none. */
    private int nearestOwnBuilding2(int x, int y) {
        Building b = nearestOwnBuilding(x, y);
        return b == null ? Integer.MAX_VALUE : MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y);
    }

    private int nearestOtherUnit2(int x, int y, @Nullable Unit self, @Nullable Unit partner) {
        int best = Integer.MAX_VALUE;
        for (Selectable<?> sel : ai.owner().getUnits().getSet())
            if (sel instanceof Unit u && u != self && u != partner && !u.isDead() && !u.isMounted())
                best = Math.min(best, MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y));
        return best;
    }
}
