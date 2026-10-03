package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.DefendController;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    /** Cells from a copy's quarters and armory where its defense starts (30 m). */
    private static final int DEFENSE_CELLS = 17;
    private static final int TOWER_CELLS = 19;
    /** shepherd_sticky: ring cells this near the current spot share its bonus. */
    private static final int STICKY_CELLS = 4;
    /** shepherd_safe_walk: candidates tried, best first, for a walk clear of enemy warriors. */
    private static final int SAFE_TRIES = 12;
    /** shepherd_home_pair and shepherd_follow: a copy's warrior this near its armory stands at home. */
    private static final int HOME_CELLS = 40;
    /** Log only: a flee line at most this often while a flee lasts (12 s; its destination moves with it). */
    private static final float FLEE_LOG_TICKS = 600f;
    /** Log only: a spot jump to the same place is logged again only after this long (30 s). */
    private static final float JUMP_LOG_TICKS = 1500f;
    /** shepherd_stuck_ticks: a shepherd that stays within this many cells (Chebyshev) of a point stands still. */
    private static final int STUCK_BOX = 6;
    /** shepherd_coming_stalled: game ticks between the snapshots of the enemy warriors' cells (10 s). */
    private static final float STALL_SNAP_TICKS = 500f;
    /** shepherd_safe_walk: game ticks a flee runs before the shepherd heads back for its spot. */
    private static final float FLEE_HOLD_TICKS = 150f; // 3 s
    /** shepherd_hunted: game ticks the walk back to the spot waits after an enemy hunting the shepherd was counted. */
    private static final float HUNTED_HOLD_TICKS = 200f; // 4 s
    /** shepherd_flee_pick: game ticks a picked flee point is kept while it stays clear (1.5 s). */
    private static final float PICK_HOLD_TICKS = 75f;
    /** shepherd_flee_pick: a heading next to the last pick's scores PICK_KEEP more within this long (3 s). */
    private static final float PICK_KEEP_TICKS = 150f;
    private static final float PICK_KEEP = 6f;
    /** shepherd_predict: a launch is checked against a prediction this recent (60 s). */
    private static final float PREDICT_CHECK_TICKS = 3000f;

    private final @NonNull GauntletAI ai;
    private final List<@NonNull Flock> flocks = new ArrayList<>();
    private float last_tick = -500f;
    /** Log and counters only: the threats the last threatAway counted. */
    private int threat_coming;
    private int threat_peons;
    private int threat_hunters;
    private int threat_warriors;
    /**
     * shepherd_flee_side, for flee's counters and tend's log: 1 when the last threatAway turned an inward flee
     * sideways, 2 when it found no side clear (the flee stays inward), else 0.
     */
    private int flee_side;
    /**
     * shepherd_hunted, for tend's counter and log: the enemies hunting the shepherd from beyond the flee box that the
     * last threatAway counted.
     */
    private int threat_far_hunters;
    /**
     * shepherd_flee_pick, for flee's counters and tend's log: 1 when the last threatAway picked a fresh checked point,
     * 2 when it kept the held one, 3 when no point was legal (the flee goes as before), else 0; flee_tethered when the
     * fresh pick differs from the best without shepherd_tether's term.
     */
    private int flee_pick;
    private boolean flee_tethered;
    /**
     * shepherd_flee_pick: the targets of the enemy warriors on an attack-move, one per 4-cell bucket, per world tick.
     */
    private int[] walk_tx = new int[64];
    private int[] walk_ty = new int[64];
    private int walk_n;
    private int walk_tick = -1;
    /**
     * shepherd_all_circles and shepherd_flee_pick: every copy's finished quarters and armories as of the last tick
     * (Intel's lists, so check isDead), the centres of the defense circles a spot or flee point keeps out of.
     */
    private final List<@NonNull Building> circles = new ArrayList<>();
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
        float last_order = -5000f;
        float nospot_since = -1f;
        /** shepherd_sticky: since when enemies have blocked the current spot, -1 while it is clear. */
        float blocked_since = -1f;
        /** shepherd_safe_walk: after a flee the shepherd heads back for its spot only from this time on. */
        float flee_until = -1f;
        /**
         * shepherd_flee_side: the side (1 or -1 along the left perpendicular of the line from our start) of the last
         * sideways flee, kept until side_until (sideAway tries it first).
         */
        int side_sign;
        float side_until = -1f;
        /** Log and counters only: what the shepherd did at the last tend (walk, at, flee, nospot). */
        @NonNull
        String last_state = "walk";
        /**
         * Log only: when the current flee began, and when its last flee line was written (a line when a flee begins,
         * every FLEE_LOG_TICKS while it lasts, and at the end of one that ran FLEE_LOG_TICKS / 2 or longer).
         */
        float flee_began;
        float flee_log_at;
        /** Log only: the destination and time of the last spot-jump line (a spot that flips back and forth). */
        int jump_log_x = -1;
        int jump_log_y = -1;
        float jump_log_at = -50000f;
        /** When the copy's last shepherd was lost (shepherd_gap_ticks). */
        float lost_at = -50000f;
        /**
         * shepherd_stuck_ticks: since when the shepherd (not arrived yet) has stood within STUCK_BOX cells of
         * (stuck_x, stuck_y), -1 before its first tend, and its walk to the spot then (-1 without a spot); no recruit
         * for the copy before gap_until after a stuck release.
         */
        float stuck_since = -1f;
        int stuck_x;
        int stuck_y;
        int stuck_spot_d = -1;
        float gap_until = -1f;
        /**
         * shepherd_gap_backoff: stuck, no-progress and no-spot releases in a row; shepherd_progress_ticks: since when
         * the shepherd has been no nearer its spot (-1 before), its walk to the spot then and that spot.
         */
        int fails;
        float prog_since = -1f;
        int prog_d;
        int prog_x;
        int prog_y;
        /**
         * shepherd_need: the copy's last launch (-1 before), its idle warriors at the last tend, and since when it has
         * needed no shepherd (-1 while it does).
         */
        float launched_at = -1f;
        int idle_n;
        float unneeded_since = -1f;
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
        // Log only (maxn K2), never read by a decision (prev_wave aside, and arrived by shepherd_flee_side): arrival,
        // spot jumps and launches.
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
         * shepherd_flee_pick: the last tend's wave origin and (with shepherd_tether) the cells from it to our nearest
         * building, -1 before; the held flee point, its heading (0-15) and when it was picked; shepherd_hunted: the
         * walk back to the spot waits until hunted_until.
         */
        int ox;
        int oy;
        float ob = -1f;
        int pick_x;
        int pick_y;
        int pick_head;
        float pick_at = -50000f;
        float hunted_until = -1f;
        /**
         * shepherd_predict: whether the copy could launch at the last tend; whether this tend's origin is predicted,
         * the walking warrior's cell, its target (the origin) and the wave's spread; when the last prediction since
         * the copy's last launch ran, -1 when none did.
         */
        boolean ready;
        boolean predicted;
        int pred_ux;
        int pred_uy;
        int pred_x;
        int pred_y;
        int pred_spread;
        float pred_at = -1f;
        /**
         * shepherd_clear_parked: whether the last tend's spot came from the parked tier (findSpot), where the shepherd
         * flees from parked enemy warriors only within shepherd_clear_parked.
         */
        boolean parked_spot;
        /**
         * Log only: the point and tags of the last flee order (tend's flee line prints them: between the guard's
         * orders tend's own threatAway orders nothing, and with shepherd_flee_pick its point is not the one ordered),
         * and whether the shepherd's last order was a flee.
         */
        int flee_x;
        int flee_y;
        @NonNull
        String flee_tags = "";
        boolean flee_ordered;

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

    /**
     * shepherd_coming_stalled: the enemy warriors' cells at the last snapshot, the attack-walkers that moved fewer than
     * shepherd_coming_stalled cells since the one before, and when the last one was taken.
     */
    private final Map<@NonNull Unit, int[]> stall_cells = new LinkedHashMap<>(); // x, y, walk target x, y (-1)
    private final Set<@NonNull Unit> stalled = new LinkedHashSet<>();
    private float stall_snap = -5000f;

    private void snapStalled(@NonNull Intel intel) {
        stall_snap = ai.now();
        stalled.clear();
        int n = ai.strategy().shepherd_coming_stalled;
        Map<Unit, int[]> next = new LinkedHashMap<>();
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead())
                continue;
            int[] was = stall_cells.get(e);
            int x = e.getGridX();
            int y = e.getGridY();
            int tx = -1;
            int ty = -1;
            if (e.getPrimaryController() instanceof WalkController w && w.isAgressive()) {
                tx = w.getTarget().getGridX();
                ty = w.getTarget().getGridY();
            }
            // stalled: walking at the same target at both snapshots (a wave just launched was idle at the last one)
            if (was != null && tx >= 0 && was[2] == tx && was[3] == ty
                    && Math.max(Math.abs(x - was[0]), Math.abs(y - was[1])) < n)
                stalled.add(e);
            next.put(e, new int[]{x, y, tx, ty});
        }
        stall_cells.clear();
        stall_cells.putAll(next);
    }

    void tick() {
        Strategy strategy = ai.strategy();
        if (!strategy.shepherd || !ai.periodDue(last_tick, 25f))
            return;
        last_tick = ai.now();
        // shepherd_lead: flocks watch for the copies' armories from 90 s; tend recruits nothing before shepherd_ticks.
        float from = strategy.shepherd_lead_ticks > 0f ? Math.min(4500f,
                strategy.shepherd_ticks) : strategy.shepherd_ticks;
        if (ai.now() < from || ai.now() > strategy.shepherd_until_ticks) {
            releaseAll();
            return;
        }
        Intel intel = ai.intel();
        if (strategy.shepherd_coming_stalled > 0 && ai.periodDue(stall_snap, STALL_SNAP_TICKS))
            snapStalled(intel);
        circles.clear();
        if (strategy.shepherd_all_circles || strategy.shepherd_flee_pick) {
            circles.addAll(intel.enemy_quarters);
            circles.addAll(intel.enemy_armories);
        }
        List<Player> fresh = new ArrayList<>();
        for (Player p : ai.owner().getWorld().getPlayers())
            if (ai.owner().isEnemy(p) && p.isAlive() && flockOf(p) == null)
                fresh.add(p);
        if ((strategy.shepherd_lead_ticks > 0f || strategy.shepherd_far_first) && fresh.size() > 1) {
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
                // killed, or walked into a building (removed with its hit points: counters and log only)
                boolean killed = f.shepherd.getHitPoints() <= 0;
                ai.aiLog().count(killed ? "shepherd_lost" : "shepherd_entered");
                if (killed)
                    ai.aiLog().count("shepherd_lost_" + f.last_state);
                f.lost_at = ai.now();
                if (ai.logging())
                    ai.log("shepherd of " + name(
                            f) + " lost at " + f.last_x + "," + f.last_y + " (spot " + f.spot_x + "," + f.spot_y + ", nearest enemy warrior " + nearestEnemy(
                                    ai.intel().enemy_warriors, f.last_x, f.last_y) + " cells, peon " + nearestEnemy(
                                            ai.intel().enemy_peons, f.last_x, f.last_y) + ", tower " + nearestTower(
                                                    f.last_x,
                                                    f.last_y) + ", recruited " + (int) GauntletAI.seconds(
                                                            ai.now() - f.recruited) + " s ago, " + f.last_state + ", last flee " + (f.flee_until < 0f ? "never" : (int) GauntletAI.seconds(
                                                                    ai.now() - f.flee_until + FLEE_HOLD_TICKS) + " s ago") + ")");
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
            int clear = ai.strategy().shepherd_hold && f.imminent ? 9 : ai.strategy().shepherd_flee_clear;
            // 0.3 s at least between orders: a minimum spacing, not a period (the guard runs 4 or 8 game ticks
            // apart at ludicrous, where periodDue would re-order after 12 or 16)
            boolean due = ai.now() - f.last_order >= 15f;
            int[] away = threatAway(f, s, intel, clear, due);
            if (away != null && due)
                flee(f, s, away);
        }
    }

    /** The flock's name in logs: the copy's, with "/home" for a home flock (shepherd_home_pair). */
    private static @NonNull String name(@NonNull Flock f) {
        return f.copy.getPlayerInfo().getName() + (f.home ? "/home" : "");
    }

    private void flee(@NonNull Flock f, @NonNull Unit s, int @NonNull [] away) {
        ai.landscapeOrder(Selectable.newArray(s), away[0], away[1], Action.MOVE, false);
        f.last_order = ai.now();
        f.flee_until = ai.now() + FLEE_HOLD_TICKS;
        // shepherd_flee_side: per flee order, whether the threatAway just before turned it sideways.
        if (flee_side != 0)
            ai.aiLog().count(flee_side == 1 ? "shepherd_flee_side" : "shepherd_flee_side_none");
        // shepherd_flee_pick: per flee order, whether it goes to a checked point.
        if (flee_pick != 0)
            ai.aiLog().count(flee_pick == 3 ? "shepherd_flee_pick_none" : "shepherd_flee_pick");
        if (flee_pick == 2)
            ai.aiLog().count("shepherd_flee_pick_held");
        if (flee_tethered)
            ai.aiLog().count("shepherd_flee_tethered");
        // shepherd_calm_peons: flee orders from enemy peons alone (counted in every game, as the base to compare with).
        if (threat_peons > 0 && threat_warriors == 0 && threat_hunters == 0 && threat_coming == 0)
            ai.aiLog().count("shepherd_flee_peon");
        f.flee_ordered = true;
        if (ai.logging()) {
            f.flee_x = away[0];
            f.flee_y = away[1];
            f.flee_tags = (flee_side == 1 ? " (sideways)" : flee_side == 2 ? " (no side clear)" : "") + (flee_pick == 1
                    || flee_pick == 2 ? " (picked)" : "") + (flee_pick == 2 ? " (held)" : "") + (flee_tethered ? " (tethered)" : "");
        }
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
     * shepherd_stuck_ticks and shepherd_need: release f's shepherd, keeping f.leader, the launch-tracking state this
     * tend has just set (release() clears it, so the next tend would miss a launch).
     */
    private void releaseKeepingLeader(@NonNull Flock f) {
        Unit leader = f.leader;
        release(f);
        f.leader = leader;
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
                f.lost_at = ai.now();
                ai.aiLog().count("shepherd_given_up");
                return true;
            }
        return false;
    }

    private void sendHome(@NonNull Unit u) {
        Building armory = ai.intel().armory();
        // salvage: into the refuge while the main armory is emptied
        Building home = ai.economy().homeFor(armory);
        if (armory == null && !ai.intel().quarters.isEmpty())
            home = ai.intel().quarters.getFirst();
        if (home != null && !home.isDead())
            ai.owner().setTarget(Selectable.newArray(u), home, ai.enterAction(home), false);
    }

    private void tend(@NonNull Flock f, @NonNull Intel intel) {
        Strategy strategy = ai.strategy();
        if (strategy.shepherd_lead_ticks > 0f && f.armory_at < 0f && armory(f.copy) != null)
            f.armory_at = ai.now();
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
            Building home = armory(f.copy);
            origin = "armory";
            if (home == null && strategy.shepherd_site_origin >= 1) {
                // shepherd_site_origin: the copy's first wave leaves from its armory once that is done, so its shepherd
                // walks out while the armory is still a site (with 2, while it has none, around its quarters). A copy
                // the freeze opening froze keeps its armory site unfinished for good (Freeze), so no wave ever leaves
                // it: no shepherd, as before (one recruited before the freeze goes home).
                if (ai.freeze().isFrozen(f.copy)) {
                    ai.aiLog().count("shepherd_site_frozen");
                    release(f);
                    return;
                }
                home = armorySite(f.copy);
                origin = "site";
                if (home == null && strategy.shepherd_site_origin >= 2) {
                    home = quarters(f.copy);
                    origin = "quarters";
                }
                if (home != null)
                    ai.aiLog().count("shepherd_site_origin");
            }
            if (home == null)
                return; // no armory, no warriors: no wave to steer yet
            ox = home.getGridX();
            oy = home.getGridY();
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
                f.launched_at = ai.now();
                if (strategy.shepherd_predict) {
                    // shepherd_predict's diagnostics: whether the last tend saw the copy ready, and whether the launch
                    // left from near the last prediction since the previous launch.
                    ai.aiLog().count(f.ready ? "shepherd_launch_ready" : "shepherd_launch_unready");
                    if (f.pred_at >= 0f && ai.now() - f.pred_at <= PREDICT_CHECK_TICKS) {
                        ai.aiLog().count(MapAnalysis.dist2(f.lead_x, f.lead_y, f.pred_x,
                                f.pred_y) <= 12 * 12 ? "shepherd_predict_hit" : "shepherd_predict_miss");
                        f.pred_at = -1f;
                    }
                }
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
                if (nearShepherd(tx, ty, 6)) {
                    ai.aiLog().count("wave_to_shepherd");
                    f.fails = 0;
                }
                if (ai.logging())
                    logLaunch(f, tx, ty);
                // raid_evac: an armory of ours the wave goes for may empty before it arrives.
                if (strategy.raid_evac)
                    ai.economy().waveLaunched(f.copy, tx, ty);
                f.prev_wave = f.leader;
            }
        }
        f.leader = leader;
        if (leader != null) {
            f.lead_x = leader.getGridX();
            f.lead_y = leader.getGridY();
        }
        f.predicted = false;
        if (strategy.shepherd_predict && !f.home) {
            // shepherd_predict: the copy launches its first wave-size idle warriors, in Army order, once it has that
            // many (and a chieftain from 20 on), led by the first. A warrior ahead of every idle one in that order that
            // is out on an attack-move goes idle at its target and then leads the next launch from there, so while the
            // copy is not ready yet the spot is picked around that target, where that leashes one (predictLeash).
            int num = Math.min(40, 10 + 5 * f.launches);
            int idle = 0;
            Unit first = null;
            for (Selectable<?> sel : f.copy.getUnits().getSet()) {
                if (!(sel instanceof Unit u) || u.isDead() || u.isMounted())
                    continue;
                if (u.getAbilities().hasAbilities(Abilities.BUILD) || u.getAbilities().hasAbilities(Abilities.MAGIC)
                        || !u.getAbilities().hasAbilities(Abilities.ATTACK))
                    continue;
                Controller c = u.getPrimaryController();
                boolean is_idle = c instanceof IdleController;
                if (is_idle)
                    idle++;
                if (first == null && (is_idle || (c instanceof WalkController w && w.isAgressive())))
                    first = u;
            }
            f.ready = idle >= num && (num < 20 || f.copy.hasActiveChieftain());
            f.idle_n = idle;
            if (f.ready)
                ai.aiLog().count("shepherd_ready");
            if (first != null && first.getPrimaryController() instanceof WalkController walk) {
                int tx = walk.getTarget().getGridX();
                int ty = walk.getTarget().getGridY();
                if (f.ready) {
                    ai.aiLog().count("shepherd_predict_ready");
                } else if (!predictLeash(f, tx, ty)) {
                    ai.aiLog().count("shepherd_predict_leash");
                } else {
                    ox = tx;
                    oy = ty;
                    origin = "predicted";
                    f.predicted = true;
                    f.pred_ux = first.getGridX();
                    f.pred_uy = first.getGridY();
                    f.pred_x = ox;
                    f.pred_y = oy;
                    f.pred_at = ai.now();
                    f.pred_spread = (int) Math.ceil(Math.sqrt(2 * num) / 2);
                    ai.aiLog().count("shepherd_predict");
                }
            }
        }
        if (!f.home && leader != null && !strategy.shepherd_predict
                && (strategy.shepherd_follow || f.partner != null)) {
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
        // shepherd_flee_pick: the origin for the flee's tether (guard flees between tends).
        f.ox = ox;
        f.oy = oy;
        if (strategy.shepherd_flee_pick && strategy.shepherd_tether > 0f)
            f.ob = (float) Math.sqrt(nearestOwnBuilding2(ox, oy));
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
        if (ai.now() < strategy.shepherd_ticks)
            return; // shepherd_lead: before shepherd_ticks flocks only watch
        boolean fixes = ai.now() >= strategy.shepherd_fixes_ticks;
        if (strategy.shepherd_need > 0f && fixes && !f.home && !needed(f)) {
            // shepherd_need: a copy that is not close to a launch and has not launched for a while (its army stuck in
            // an attack-move, or idle far below its wave size) gets no shepherd, and its shepherd goes home once the
            // need has been gone for shepherd_need_ticks.
            if (f.shepherd == null) {
                ai.aiLog().count("shepherd_unneeded_wait");
                return;
            }
            if (ai.now() - f.unneeded_since >= strategy.shepherd_need_ticks) {
                ai.aiLog().count("shepherd_unneeded");
                if (ai.logging())
                    ai.log("shepherd of " + name(f) + " released: no launch for " + (int) GauntletAI.seconds(
                            ai.now() - Math.max(0f,
                                    f.launched_at)) + " s, " + f.idle_n + " idle of a wave of " + Math.min(40,
                                            10 + 5 * f.launches));
                releaseKeepingLeader(f);
                return;
            }
        }
        if (f.shepherd == null) {
            // shepherd_gap: after a shepherd is lost, its copy waits that long for the next one.
            if (ai.now() - f.lost_at < strategy.shepherd_gap_ticks) {
                ai.aiLog().count("shepherd_gap_wait");
                return;
            }
            // shepherd_stuck_ticks: after a stuck release, shepherd_stuck_gap_ticks
            if (ai.now() < f.gap_until) {
                // shepherd_gap_ready: a copy about to launch gets its shepherd at once
                if (strategy.shepherd_gap_ready && f.ready) {
                    ai.aiLog().count("shepherd_gap_ready");
                    f.gap_until = -1f;
                } else {
                    ai.aiLog().count("shepherd_stuck_gap_wait");
                    return;
                }
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
                if (ai.strategy().site_shepherd && ai.now() >= ai.strategy().shepherd_ticks)
                    ai.decoys().placeHome(f.copy, ox, oy);
                return;
            }
            Unit candidate = recruit(ox, oy, probe, intel);
            if (candidate == null) {
                ai.aiLog().count("shepherd_t_none");
                ai.aiLog().count("shepherd_norecruit");
                return;
            }
            // shepherd_lead: before the copy's first launch, while it has no idle warrior to launch, the shepherd
            // leaves only in time to stand on the spot shepherd_lead_margin_ticks before armory_at +
            // shepherd_lead_ticks.
            boolean lead = strategy.shepherd_lead_ticks > 0f && f.launches == 0 && leader == null;
            int walk2 = MapAnalysis.dist2(candidate.getGridX(), candidate.getGridY(), probe[0], probe[1]);
            if (lead) {
                float eta = (float) Math.sqrt(walk2) / strategy.shepherd_cells_per_tick + 250f;
                if (f.armory_at < 0f
                        || ai.now() < f.armory_at + strategy.shepherd_lead_ticks - eta - strategy.shepherd_lead_margin_ticks) {
                    ai.aiLog().count("shepherd_wait");
                    return;
                }
            }
            PeonState state = intel.peon_states.get(candidate);
            f.shepherd = candidate;
            intel.shepherds.add(candidate);
            f.recruited = ai.now();
            f.nospot_since = -1f;
            f.stuck_since = -1f;
            f.prog_since = -1f;
            ai.aiLog().count("shepherd_recruit");
            if (loaded(candidate))
                ai.aiLog().count("shepherd_recruit_loaded");
            if (f.home)
                ai.aiLog().count("shepherd_recruit_home");
            if (lead)
                ai.aiLog().count("shepherd_lead_recruit");
            if ("site".equals(origin) || "quarters".equals(origin))
                ai.aiLog().count("shepherd_recruit_site");
            f.arrived = false;
            f.last_state = "walk";
            f.flee_until = -1f;
            f.side_until = -1f;
            f.pick_at = -50000f;
            f.hunted_until = -1f;
            f.parked_spot = false;
            f.flee_ordered = false;
            f.rec_x = candidate.getGridX();
            f.rec_y = candidate.getGridY();
            f.rec_spot_x = probe[0];
            f.rec_spot_y = probe[1];
            f.prev_spot_x = -1;
            if (ai.logging())
                ai.log("shepherd of " + name(f) + " recruited: " + (state == null ? "?" : state.name().toLowerCase(
                        Locale.ROOT)) + (loaded(
                                candidate) ? " loaded" : "") + " peon at " + f.rec_x + "," + f.rec_y + ", spot " + probe[0] + "," + probe[1] + ", walk " + (int) Math.sqrt(
                                        walk2) + " cells, origin " + origin + (lead ? ", lead (armory at " + (int) GauntletAI.seconds(
                                                f.armory_at) + " s)" : ""));
        }
        Unit s = f.shepherd;
        if ((strategy.shepherd_stuck_ticks > 0f || strategy.shepherd_stuck_base_ticks > 0f)
                && ai.now() >= strategy.shepherd_fixes_ticks && !f.arrived && stuck(f, s)) {
            ai.aiLog().count("shepherd_stuck_release");
            if (ai.logging())
                ai.log("shepherd of " + name(
                        f) + " released: stood within " + STUCK_BOX + " cells of " + f.stuck_x + "," + f.stuck_y + " for " + (int) GauntletAI.seconds(
                                ai.now() - f.stuck_since) + " s (spot " + f.spot_x + "," + f.spot_y + ", recruited " + (int) GauntletAI.seconds(
                                        ai.now() - f.recruited) + " s ago)");
            lateRelease(f);
            return;
        }
        if (strategy.shepherd_progress_ticks > 0f && ai.now() >= strategy.shepherd_fixes_ticks && !f.arrived
                && noProgress(f, s)) {
            ai.aiLog().count("shepherd_progress_release");
            if (ai.logging())
                ai.log("shepherd of " + name(
                        f) + " released: no nearer its spot " + f.prog_x + "," + f.prog_y + " than " + f.prog_d + " cells for " + (int) GauntletAI.seconds(
                                ai.now() - f.prog_since) + " s, at " + s.getGridX() + "," + s.getGridY());
            lateRelease(f);
            return;
        }
        int moved = Math.abs(s.getGridX() - f.last_x) + Math.abs(s.getGridY() - f.last_y);
        f.last_x = s.getGridX();
        f.last_y = s.getGridY();
        // Flee first: any enemy near the shepherd, or a wave walking toward where it stands.
        boolean due = ai.now() - f.last_order >= 15f; // the guard's minimum spacing
        int[] away = threatAway(f, s, intel, ai.strategy().shepherd_hold
                && f.imminent ? 9 : ai.strategy().shepherd_flee_clear, due);
        if (away != null) {
            ai.aiLog().count("shepherd_t_flee");
            if (threat_far_hunters > 0)
                ai.aiLog().count("shepherd_hunted");
            boolean begins = !"flee".equals(f.last_state);
            f.last_state = "flee";
            if (begins)
                f.flee_began = ai.now();
            if (due)
                flee(f, s, away);
            // log only: one line when the flee begins and every FLEE_LOG_TICKS while it lasts (the tend runs every half
            // second, and a line each time was most of a replay's log); the point is the last flee order's, this one's
            // or the guard's, and "(pending)" when none went out since the last walk order
            if (ai.logging() && (begins || ai.periodDue(f.flee_log_at,
                    FLEE_LOG_TICKS))) {
                f.flee_log_at = ai.now();
                ai.log((begins ? "flee of " : "flee goes on: ") + name(
                        f) + " at " + s.getGridX() + "," + s.getGridY() + " (moved " + moved + ") from " + threat_warriors + " warriors, " + threat_hunters + " hunters, " + threat_peons + " peons, " + threat_coming + " coming, to " + (f.flee_ordered ? f.flee_x + "," + f.flee_y + f.flee_tags : away[0] + "," + away[1] + " (pending)") + (threat_far_hunters > 0 ? " (hunted)" : ""));
            }
            return;
        }
        // log only: the end of a flee that ran FLEE_LOG_TICKS / 2 or longer (most last 2-5 s: their start line is enough)
        if (ai.logging() && "flee".equals(f.last_state) && ai.now() - f.flee_began >= FLEE_LOG_TICKS / 2f)
            ai.log("flee of " + name(
                    f) + " over at " + s.getGridX() + "," + s.getGridY() + " after " + (int) GauntletAI.seconds(
                            ai.now() - f.flee_began) + " s");
        int[] spot = findSpot(f, ox, oy, s, intel);
        if (spot == null) {
            // No spot draws this copy's wave: a shepherd left standing there only gets killed, so after a while it
            // goes home (a new one is recruited once a spot opens up again).
            f.spot_x = -1;
            ai.aiLog().count("shepherd_t_nospot");
            f.last_state = "nospot";
            if (f.nospot_since < 0f)
                f.nospot_since = ai.now();
            else if (ai.now() - f.nospot_since > ai.strategy().shepherd_patience_ticks) {
                ai.aiLog().count("shepherd_home");
                release(f);
            } else if (strategy.shepherd_nospot_ticks > 0f && ai.now() >= strategy.shepherd_fixes_ticks
                    && ai.now() - f.nospot_since >= strategy.shepherd_nospot_ticks) {
                        // shepherd_nospot_ticks: no spot for that long; home, and a gap before the next one
                        ai.aiLog().count("shepherd_nospot_release");
                        if (ai.logging())
                            ai.log("shepherd of " + name(f) + " released: no spot for " + (int) GauntletAI.seconds(
                                    ai.now() - f.nospot_since) + " s at " + s.getGridX() + "," + s.getGridY());
                        lateRelease(f);
                    }
            return;
        }
        f.nospot_since = -1f;
        if (f.prev_spot_x >= 0 && MapAnalysis.dist2(f.prev_spot_x, f.prev_spot_y, spot[0], spot[1]) > 30 * 30) {
            ai.aiLog().count("shepherd_spot_jump");
            // the late release rules: a shepherd that reached its old spot is on its way again
            Strategy st = ai.strategy();
            if (f.arrived && ai.now() >= st.shepherd_fixes_ticks && (st.shepherd_stuck_ticks > 0f
                    || st.shepherd_stuck_base_ticks > 0f || st.shepherd_progress_ticks > 0f)) {
                f.arrived = false;
                f.stuck_since = -1f;
                f.prog_since = -1f;
                ai.aiLog().count("shepherd_rearm");
            }
            // log only: a jump back to where the last logged one went, within JUMP_LOG_TICKS, is not logged again
            if (ai.logging() && (MapAnalysis.dist2(spot[0], spot[1], f.jump_log_x, f.jump_log_y) > 8 * 8
                    || ai.periodDue(f.jump_log_at, JUMP_LOG_TICKS))) {
                f.jump_log_x = spot[0];
                f.jump_log_y = spot[1];
                f.jump_log_at = ai.now();
                ai.log("spot of " + name(f) + " jumps " + (int) Math.sqrt(MapAnalysis.dist2(
                        f.prev_spot_x, f.prev_spot_y, spot[0],
                        spot[1])) + " cells from " + f.prev_spot_x + "," + f.prev_spot_y + " (origin " + f.origin + ") to " + spot[0] + "," + spot[1] + " (origin " + origin + ")");
            }
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
            f.fails = 0;
            ai.aiLog().count("shepherd_at_spot");
            if (ai.logging())
                ai.log("shepherd of " + name(
                        f) + " at spot after " + (int) GauntletAI.seconds(
                                ai.now() - f.recruited) + " s (from " + f.rec_x + "," + f.rec_y + ", " + (int) Math.sqrt(
                                        MapAnalysis.dist2(
                                                f.rec_x, f.rec_y, s.getGridX(),
                                                s.getGridY())) + " cells; spot moved " + (int) Math.sqrt(
                                                        MapAnalysis.dist2(
                                                                f.rec_spot_x,
                                                                f.rec_spot_y, spot[0],
                                                                spot[1])) + " cells since recruited)");
        }
        boolean on_spot = MapAnalysis.dist2(s.getGridX(), s.getGridY(), spot[0], spot[1]) <= 3 * 3;
        ai.aiLog().count(on_spot ? "shepherd_t_atspot" : "shepherd_t_walk");
        f.last_state = on_spot ? "at" : "walk";
        // shepherd_safe_walk: a shepherd that just fled runs its full course before it heads back; shepherd_hunted: so
        // does one that was hunted in the last HUNTED_HOLD_TICKS.
        boolean held = (strategy.shepherd_safe_walk && ai.now() < f.flee_until)
                || (strategy.shepherd_hunted && ai.now() < f.hunted_until);
        if (MapAnalysis.dist2(s.getGridX(), s.getGridY(), spot[0], spot[1]) > 2 * 2
                && ai.periodDue(f.last_order, 100f) && !held) {
            int[] to = spot;
            // shepherd_detour: around what stands on the straight walk
            if (strategy.shepherd_detour && ai.now() >= strategy.shepherd_fixes_ticks
                    && !walkClear(s.getGridX(), s.getGridY(), spot[0], spot[1], intel)) {
                int[] wp = detour(s, spot, intel);
                if (wp != null) {
                    to = wp;
                    ai.aiLog().count("shepherd_detour");
                } else {
                    ai.aiLog().count("shepherd_detour_none");
                }
            }
            ai.landscapeOrder(Selectable.newArray(s), to[0], to[1], Action.MOVE, false);
            f.last_order = ai.now();
            f.flee_ordered = false;
        }
    }

    /**
     * shepherd_detour: of the cells shepherd_detour_r and half that from shepherd s in 16 directions, reachable, with a
     * clear walk there (walkClear) and at least 2 cells nearer the spot, the nearest the spot, one with a clear walk on
     * to the spot first; null when none.
     */
    private int @Nullable [] detour(@NonNull Unit s, int @NonNull [] spot, @NonNull Intel intel) {
        int sx = s.getGridX();
        int sy = s.getGridY();
        DistanceField reach = ai.planner().getStartField();
        float here = (float) Math.sqrt(MapAnalysis.dist2(sx, sy, spot[0], spot[1]));
        int r = ai.strategy().shepherd_detour_r;
        int[] best = null;
        float best_score = Float.MAX_VALUE;
        for (int k = 0; k < 16; k++) {
            double ang = k * Math.PI / 8;
            for (int rr = r / 2; rr <= r; rr += Math.max(1, r / 2)) {
                int x = sx + (int) Math.round(rr * Math.cos(ang));
                int y = sy + (int) Math.round(rr * Math.sin(ang));
                if (!reach.reachable(x, y) || !walkClear(sx, sy, x, y, intel))
                    continue;
                float left = (float) Math.sqrt(MapAnalysis.dist2(x, y, spot[0], spot[1]));
                if (left > here - 2f)
                    continue;
                float score = left + (walkClear(x, y, spot[0], spot[1], intel) ? 0f : 15f);
                if (score < best_score) {
                    best_score = score;
                    best = new int[]{x, y};
                }
            }
        }
        return best;
    }

    /**
     * shepherd_stuck_ticks: whether shepherd s of f has stood within STUCK_BOX cells of one point for
     * shepherd_stuck_ticks without getting 10 cells nearer its spot (a walk-flee cycle at the edge of a stuck army's
     * coming disc, or a shepherd with no spot standing still). Moves the point when it walks on.
     */
    private boolean stuck(@NonNull Flock f, @NonNull Unit s) {
        int sx = s.getGridX();
        int sy = s.getGridY();
        int sd = f.spot_x >= 0 ? (int) Math.sqrt(MapAnalysis.dist2(sx, sy, f.spot_x, f.spot_y)) : -1;
        boolean walked = f.stuck_since < 0f || Math.max(Math.abs(sx - f.stuck_x), Math.abs(sy - f.stuck_y)) > STUCK_BOX
                || (sd >= 0 && f.stuck_spot_d >= 0 && f.stuck_spot_d - sd >= 10);
        if (walked) {
            f.stuck_since = ai.now();
            f.stuck_x = sx;
            f.stuck_y = sy;
            f.stuck_spot_d = sd;
            return false;
        }
        float limit = stuckLimit(f);
        return limit > 0f && ai.now() - f.stuck_since >= limit;
    }

    /**
     * A stuck, no-progress or no-spot release: the shepherd goes home (f.leader kept), and its copy gets no new one for
     * shepherd_stuck_gap_ticks, doubled for each such release in a row up to shepherd_gap_max_ticks with
     * shepherd_gap_backoff.
     */
    private void lateRelease(@NonNull Flock f) {
        Strategy st = ai.strategy();
        f.fails++;
        float gap = st.shepherd_stuck_gap_ticks;
        if (st.shepherd_gap_backoff)
            gap = Math.min(st.shepherd_gap_max_ticks, gap * (1 << Math.min(10, f.fails - 1)));
        f.lost_at = ai.now();
        f.gap_until = ai.now() + gap;
        releaseKeepingLeader(f);
    }

    /**
     * shepherd_progress_ticks: whether shepherd s of f has been no 10 cells nearer its spot for shepherd_progress_ticks
     * (a spot that moved more than 10 cells starts the count again; with no spot nothing counts).
     */
    private boolean noProgress(@NonNull Flock f, @NonNull Unit s) {
        if (f.spot_x < 0)
            return false;
        int d = (int) Math.sqrt(MapAnalysis.dist2(s.getGridX(), s.getGridY(), f.spot_x, f.spot_y));
        if (f.prog_since < 0f || d <= f.prog_d - 10 || Math.max(Math.abs(f.spot_x - f.prog_x), Math.abs(
                f.spot_y - f.prog_y)) > 10) {
            f.prog_since = ai.now();
            f.prog_d = d;
            f.prog_x = f.spot_x;
            f.prog_y = f.spot_y;
            return false;
        }
        if (ai.now() - f.prog_since < ai.strategy().shepherd_progress_ticks)
            return false;
        // shepherd_progress_base: only one in or by our base
        int c = ai.strategy().shepherd_stuck_base_cells;
        return !ai.strategy().shepherd_progress_base || nearestOwnBuilding2(s.getGridX(), s.getGridY()) <= c * c;
    }

    /**
     * The stuck time for f's shepherd standing at (stuck_x, stuck_y): shepherd_stuck_base_ticks near one of our
     * buildings, else shepherd_stuck_ticks (0: never).
     */
    private float stuckLimit(@NonNull Flock f) {
        Strategy st = ai.strategy();
        int c = st.shepherd_stuck_base_cells;
        if (st.shepherd_stuck_base_ticks > 0f && nearestOwnBuilding2(f.stuck_x, f.stuck_y) <= c * c)
            return st.shepherd_stuck_base_ticks;
        return st.shepherd_stuck_ticks;
    }

    /**
     * shepherd_need: whether the copy of f needs a shepherd: its idle warriors reach shepherd_need of its wave size, it
     * launched fewer than 3 waves, or its last launch was within shepherd_need_ticks. Keeps f.unneeded_since.
     */
    private boolean needed(@NonNull Flock f) {
        Strategy st = ai.strategy();
        int num = Math.min(40, 10 + 5 * f.launches);
        if (!st.shepherd_predict)
            f.idle_n = idleWarriors(f.copy);
        boolean need = f.idle_n >= st.shepherd_need * num || f.launches < 3
                || (f.launched_at >= 0f && ai.now() - f.launched_at < st.shepherd_need_ticks);
        if (need)
            f.unneeded_since = -1f;
        else if (f.unneeded_since < 0f)
            f.unneeded_since = ai.now();
        return need;
    }

    /** The copy's idle warriors, as shepherd_predict counts them. */
    private static int idleWarriors(@NonNull Player p) {
        int idle = 0;
        for (Selectable<?> sel : p.getUnits().getSet()) {
            if (!(sel instanceof Unit u) || u.isDead() || u.isMounted())
                continue;
            if (u.getAbilities().hasAbilities(Abilities.BUILD) || u.getAbilities().hasAbilities(Abilities.MAGIC)
                    || !u.getAbilities().hasAbilities(Abilities.ATTACK))
                continue;
            if (u.getPrimaryController() instanceof IdleController)
                idle++;
        }
        return idle;
    }

    /**
     * shepherd_predict: whether a walking wave's target (tx, ty) leashes a spot as findSpot does, our shepherds within
     * 14 cells of it left out (the coming test makes them run). The target is a cell of ours (AdvancedAI.findTarget),
     * and only a wave a shepherd drew goes idle there: one aimed at our building, army or a field peon fights there,
     * and findSpot held no spot around such a target for the whole walk (shep-smoke-S4-vs13 against
     * shep-identity-ref-vs13, per game: nospot_building 2137 -> 3989, nospot_unit 2425 -> 5221, t_nospot 7885 ->
     * 11371), so the origin stays the leader's (or the armory's) then.
     */
    private boolean predictLeash(@NonNull Flock f, int tx, int ty) {
        int building2 = nearestOwnBuilding2(tx, ty);
        if (building2 == Integer.MAX_VALUE)
            return false;
        int unit2 = nearestOtherUnit2(tx, ty, f.shepherd, f.partner == null ? null : f.partner.shepherd, true);
        return Math.min(ai.strategy().shepherd_max_r, (float) Math.sqrt(Math.min(building2 * .44f,
                unit2 * .8f))) >= 14f;
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

    /** shepherd_site_origin: the copy's first placed armory site that is not finished yet, or null. */
    private static @Nullable Building armorySite(@NonNull Player p) {
        for (Selectable<?> sel : p.getUnits().getSet())
            if (sel instanceof Building b && !b.isDead() && b.getTemplate().getType() == BuildingTemplate.TYPE_BUILDING
                    && b.getTemplate().getTemplateID() == Race.BUILDING_ARMORY && !b.isComplete())
                return b;
        return null;
    }

    /** shepherd_site_origin 2: the copy's first finished quarters, or null. */
    private static @Nullable Building quarters(@NonNull Player p) {
        for (Selectable<?> sel : p.getUnits().getSet())
            if (sel instanceof Building b && !b.isDead() && b.isComplete()
                    && b.getTemplate().getTemplateID() == Race.BUILDING_QUARTERS)
                return b;
        return null;
    }

    private @Nullable Unit recruit(int ox, int oy, int @NonNull [] spot, @NonNull Intel intel) {
        Strategy st = ai.strategy();
        if (st.shepherd_recruit_clear && ai.now() >= st.shepherd_recruit_clear_ticks)
            return recruitClear(ox, oy, spot, intel);
        return nearestRecruit(ox, oy, intel);
    }

    /**
     * shepherd_recruit_clear: of the eligible peons nearest the origin, the first of up to 5 whose straight walk to the
     * spot (its first 60 cells, every 4) would not set off the flee test: no enemy warrior within shepherd_flee_clear
     * (Chebyshev) and no attack-walker within 40 cells aiming within 14 cells of the point. Null when none is clear.
     */
    private @Nullable Unit recruitClear(int ox, int oy, int @NonNull [] spot, @NonNull Intel intel) {
        // one pass: the eligible peons by distance to the origin (a stable sort keeps the list order on ties, as
        // nearestRecruit's strict comparison does)
        List<Unit> eligible = new ArrayList<>();
        List<Integer> dist = new ArrayList<>();
        for (Unit p : intel.peons) {
            if (!eligible(p, intel))
                continue;
            eligible.add(p);
            dist.add(MapAnalysis.dist2(p.getGridX(), p.getGridY(), ox, oy));
        }
        Integer[] order = new Integer[eligible.size()];
        for (int i = 0; i < order.length; i++)
            order[i] = i;
        Arrays.sort(order, Comparator.comparingInt(dist::get));
        for (int k = 0; k < Math.min(5, order.length); k++) {
            Unit p = eligible.get(order[k]);
            if (walkClear(p.getGridX(), p.getGridY(), spot[0], spot[1], intel))
                return p;
        }
        ai.aiLog().count("shepherd_norecruit_clear");
        return null;
    }

    /** shepherd_recruit_clear: whether the straight walk from (x0, y0) to (x1, y1) starts clear (recruitClear). */
    private boolean walkClear(int x0, int y0, int x1, int y1, @NonNull Intel intel) {
        float len = (float) Math.sqrt(MapAnalysis.dist2(x0, y0, x1, y1));
        int clear = ai.strategy().shepherd_flee_clear;
        EnemyIndex index = intel.enemyIndex(ai.worldTicks());
        float end = Math.min(60f, len);
        // every 4 cells, and the last cell looked at too (else a waypoint's own cell can go untested)
        for (float step = 0f;; step += 4f) {
            float d = Math.min(step, end);
            int x = len > 0f ? Math.round(x0 + (x1 - x0) * d / len) : x0;
            int y = len > 0f ? Math.round(y0 + (y1 - y0) * d / len) : y0;
            int[] candidates = index.queryUnordered(x, y, 40 * 40);
            for (int k = 0, m = index.count(); k < m; k++) {
                if (index.group(candidates[k]) != EnemyIndex.WARRIOR)
                    continue;
                Unit e = index.unit(candidates[k]);
                if (e.isDead())
                    continue;
                int dx = e.getGridX() - x;
                int dy = e.getGridY() - y;
                if (Math.abs(dx) <= clear && Math.abs(dy) <= clear)
                    return false;
                if (e.getPrimaryController() instanceof WalkController w && w.isAgressive()
                        && !(ai.strategy().shepherd_coming_stalled > 0 && stalled.contains(e))) {
                    int tx = w.getTarget().getGridX() - x;
                    int ty = w.getTarget().getGridY() - y;
                    if (tx * tx + ty * ty <= 14 * 14)
                        return false;
                }
            }
            if (d >= end)
                break;
        }
        return true;
    }

    /** Whether peon p may become a shepherd: at work that can stop, no one's placer or repairer, no enemy near. */
    private boolean eligible(@NonNull Unit p, @NonNull Intel intel) {
        PeonState st = intel.peon_states.get(p);
        if (st != PeonState.IDLE && st != PeonState.GATHER_TREE && st != PeonState.GATHER_ROCK
                && st != PeonState.GATHER_IRON && st != PeonState.TRANSIT && st != PeonState.MOVE)
            return false;
        if (intel.shepherds.contains(p) || ai.economy().reservedPlacer(p))
            return false;
        // repair_swarm: not its repairers nor its fresh wood transporters
        if (ai.strategy().repair_swarm && ai.economy().swarmExempt(p))
            return false;
        int danger = nearestEnemy(intel.enemy_warriors, p.getGridX(), p.getGridY());
        return danger < 0 || danger > 14;
    }

    /** The eligible peon nearest (ox, oy). */
    private @Nullable Unit nearestRecruit(int ox, int oy, @NonNull Intel intel) {
        Unit best = null;
        int best_d = Integer.MAX_VALUE;
        for (Unit p : intel.peons) {
            if (!eligible(p, intel))
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

    /**
     * A point to run to when enemies are near the shepherd or a wave is walking at it, else null. With
     * shepherd_flee_side, a shepherd of f that has not reached its spot yet and would run back towards our start runs
     * sideways instead where that is clear (sideAway). With shepherd_flee_pick and order (a flee order goes out with
     * the point), the checked point of pickFlee comes first.
     */
    private int @Nullable [] threatAway(@NonNull Flock f, @NonNull Unit s, @NonNull Intel intel, int clear,
            boolean order) {
        int sx = s.getGridX();
        int sy = s.getGridY();
        long ex = 0;
        long ey = 0;
        int n = 0;
        // shepherd_flee_pick: the counted threat nearest the shepherd (ties by cell, so the scan order does not matter).
        int near_x = sx;
        int near_y = sy;
        int near_d2 = Integer.MAX_VALUE;
        boolean hunted = ai.strategy().shepherd_hunted;
        boolean hunted_seen = false;
        // The enemies near enough to count: warriors within the square or walking at us from 40 cells, peons within
        // the square (the sums do not depend on the order).
        EnemyIndex index = intel.enemyIndex(ai.worldTicks());
        threat_coming = 0;
        threat_peons = 0;
        threat_hunters = 0;
        threat_warriors = 0;
        threat_far_hunters = 0;
        flee_side = 0;
        flee_pick = 0;
        flee_tethered = false;
        int calm = ai.strategy().shepherd_calm_peons;
        int parked = f.parked_spot ? ai.strategy().shepherd_clear_parked : 0;
        int[] candidates = index.queryUnordered(sx, sy, Math.max(40 * 40, 2 * clear * clear));
        for (int k = 0, m = index.count(); k < m; k++) {
            byte group = index.group(candidates[k]);
            Unit e = index.unit(candidates[k]);
            if (group == EnemyIndex.CHIEFTAIN || e.isDead())
                continue;
            int dx = e.getGridX() - sx;
            int dy = e.getGridY() - sy;
            boolean near = Math.abs(dx) <= clear && Math.abs(dy) <= clear;
            // shepherd_calm_peons: a peon at its work starts no fight, so it counts only that near.
            if (near && calm > 0 && group == EnemyIndex.PEON && Math.max(Math.abs(dx), Math.abs(dy)) > calm
                    && !active(e))
                near = false;
            // shepherd_clear_parked: on a spot cleared only that far from parked warriors, they count only that near.
            if (near && parked > 0 && group == EnemyIndex.WARRIOR && Math.max(Math.abs(dx), Math.abs(dy)) > parked
                    && Intel.isParked(e))
                near = false;
            boolean coming = false;
            if (group == EnemyIndex.WARRIOR && !near && e.getPrimaryController() instanceof WalkController w
                    && w.isAgressive() && dx * dx + dy * dy <= 40 * 40) {
                int tx = w.getTarget().getGridX() - sx;
                int ty = w.getTarget().getGridY() - sy;
                coming = tx * tx + ty * ty <= 14 * 14;
                // shepherd_coming_stalled: an attack-walker that has hardly moved for 10 s is stuck, not coming
                if (coming && ai.strategy().shepherd_coming_stalled > 0 && stalled.contains(e)) {
                    coming = false;
                    ai.aiLog().count("shepherd_coming_stalled");
                }
            }
            // shepherd_hunted: an enemy hunting the shepherd runs at it from anywhere in the query.
            boolean hunter = hunted && e.getCurrentController() instanceof HuntController hunt
                    && hunt.getTarget() == s;
            boolean far_hunter = hunter && !near && !coming;
            if (near || coming || far_hunter) {
                ex += e.getGridX();
                ey += e.getGridY();
                n++;
                int d2 = dx * dx + dy * dy;
                if (d2 < near_d2 || (d2 == near_d2 && (e.getGridX() < near_x || (e.getGridX() == near_x
                        && e.getGridY() < near_y)))) {
                    near_d2 = d2;
                    near_x = e.getGridX();
                    near_y = e.getGridY();
                }
                if (hunter)
                    hunted_seen = true;
                if (far_hunter)
                    threat_far_hunters++;
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
        if (hunted_seen)
            f.hunted_until = ai.now() + HUNTED_HOLD_TICKS;
        float cx = (float) ex / n;
        float cy = (float) ey / n;
        float dx = sx - cx;
        float dy = sy - cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (ai.strategy().shepherd_flee_pick && order) {
            // shepherd_flee_pick: away from the centroid, or from the nearest threat when the shepherd stands on it.
            int[] pick = len < .5f ? pickFlee(f, sx, sy, near_x, near_y, intel) : pickFlee(f, sx, sy, cx, cy, intel);
            if (pick != null)
                return pick;
        }
        if (len < .5f) {
            dx = ai.planner().getStartX() - sx;
            dy = ai.planner().getStartY() - sy;
            len = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
        }
        if (ai.strategy().shepherd_flee_side && !f.arrived) {
            // shepherd_flee_side: a wave that catches a shepherd on its way out parks about 25 cells nearer to us
            // (logged N=13 ludicrous games), so a flee that would lose ground towards our start (away vector a, u the
            // unit vector from our start to the shepherd: a.u < -0.3 |a|, the fallback towards our start included)
            // goes sideways where that is clear.
            float ux = sx - ai.planner().getStartX();
            float uy = sy - ai.planner().getStartY();
            float ulen = (float) Math.sqrt(ux * ux + uy * uy);
            if (ulen >= 1f && dx * ux + dy * uy < -.3f * len * ulen) {
                int[] side = sideAway(f, sx, sy, dx, dy, ux / ulen, uy / ulen, cx, cy, intel);
                flee_side = side != null ? 1 : 2;
                if (side != null)
                    return side;
            }
        }
        return new int[]{sx + Math.round(22 * dx / len), sy + Math.round(22 * dy / len)};
    }

    /**
     * shepherd_flee_pick: the held flee point of f while it is younger than PICK_HOLD_TICKS, clear of enemy warriors
     * and chieftains within half shepherd_clear, more than 3 cells from the shepherd at (sx, sy), no nearer to the
     * threat centre (cx, cy) than the shepherd and more than 14 cells from every walking wave's target (a threat that
     * turns up within the hold, on the pick's side, gets a fresh pick, not a run at it); else the best of
     * 16 headings at shepherd_flee_r and 0.6 of it that fleeLegal accepts and that gain 6 cells or more from the
     * threat centre (cx, cy). The score is the cells gained from the centre, plus shepherd_flee_out per cell gained
     * away from our start, minus shepherd_tether per cell beyond the leash disc (0.66 of the origin's distance to our
     * nearest building, less 2; origins within 150 cells only), plus PICK_KEEP for a heading next to the last pick's
     * within PICK_KEEP_TICKS. Null with no legal point (the flee then goes as before). Picked afresh at every order, a
     * flee flips between headings of about equal score and the shepherd hardly moves (see sideAway); the hold and the
     * heading bonus keep it on one course. It leaves side_sign and side_until alone.
     */
    private int @Nullable [] pickFlee(@NonNull Flock f, int sx, int sy, float cx, float cy, @NonNull Intel intel) {
        Strategy strategy = ai.strategy();
        EnemyIndex index = intel.enemyIndex(ai.worldTicks());
        float now = ai.now();
        if (now - f.pick_at < PICK_HOLD_TICKS && MapAnalysis.dist2(sx, sy, f.pick_x, f.pick_y) > 3 * 3
                && (index.groupsInBox(f.pick_x, f.pick_y,
                        strategy.shepherd_clear / 2) & (1 << EnemyIndex.WARRIOR | 1 << EnemyIndex.CHIEFTAIN)) == 0) {
            if (dist(f.pick_x, f.pick_y, cx, cy) >= dist(sx, sy, cx, cy) && !nearWalkTarget(f.pick_x, f.pick_y,
                    intel)) {
                flee_pick = 2;
                return new int[]{f.pick_x, f.pick_y};
            }
            ai.aiLog().count("shepherd_flee_pick_unheld");
        }
        int bx = ai.planner().getStartX();
        int by = ai.planner().getStartY();
        float from_c = dist(sx, sy, cx, cy);
        float from_b = dist(sx, sy, bx, by);
        boolean tether = strategy.shepherd_tether > 0f && f.ob >= 0f && f.ob <= 150f;
        float leash = .66f * f.ob - 2f;
        boolean keep = now - f.pick_at < PICK_KEEP_TICKS;
        int best_h = -1;
        int best_x = 0;
        int best_y = 0;
        float best = 0f;
        // The best without the tether's term, for shepherd_flee_tethered.
        int free_x = 0;
        int free_y = 0;
        float free = -Float.MAX_VALUE;
        for (int h = 0; h < 16; h++) {
            double ang = h * Math.PI / 8;
            for (int k = 0; k < 2; k++) {
                int r = k == 0 ? strategy.shepherd_flee_r : Math.round(.6f * strategy.shepherd_flee_r);
                int x = sx + (int) Math.round(r * Math.cos(ang));
                int y = sy + (int) Math.round(r * Math.sin(ang));
                float gain = dist(x, y, cx, cy) - from_c;
                if (gain < 6f || !fleeLegal(sx, sy, x, y, intel))
                    continue;
                float score = gain + strategy.shepherd_flee_out * (dist(x, y, bx, by) - from_b);
                int turn = Math.floorMod(h - f.pick_head, 16);
                if (keep && (turn <= 1 || turn == 15))
                    score += PICK_KEEP;
                if (score > free) {
                    free = score;
                    free_x = x;
                    free_y = y;
                }
                if (tether)
                    score -= strategy.shepherd_tether * Math.max(0f, dist(x, y, f.ox, f.oy) - leash);
                if (best_h < 0 || score > best) {
                    best = score;
                    best_h = h;
                    best_x = x;
                    best_y = y;
                }
            }
        }
        if (best_h < 0) {
            flee_pick = 3;
            return null;
        }
        flee_pick = 1;
        flee_tethered = best_x != free_x || best_y != free_y;
        f.pick_x = best_x;
        f.pick_y = best_y;
        f.pick_head = best_h;
        f.pick_at = now;
        return new int[]{best_x, best_y};
    }

    /**
     * shepherd_flee_pick: whether a flee from (sx, sy) to (x, y) is safe: (x, y) and the leg's midpoint reachable from
     * our start, (x, y) beyond TOWER_CELLS of every enemy tower and DEFENSE_CELLS of every copy's quarters and
     * armories, with no enemy warrior or chieftain within shepherd_clear cells along both axes and more than 14 cells
     * from every attack-moving warrior's target, and no enemy warrior within 6 cells of the leg (sampled every 4
     * cells).
     */
    private boolean fleeLegal(int sx, int sy, int x, int y, @NonNull Intel intel) {
        DistanceField reach = ai.planner().getStartField();
        if (!reach.reachable(x, y) || !reach.reachable((sx + x) / 2, (sy + y) / 2))
            return false;
        for (Building t : intel.enemy_towers)
            if (!t.isDead() && MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= TOWER_CELLS * TOWER_CELLS)
                return false;
        if (inCircle(x, y))
            return false;
        EnemyIndex index = intel.enemyIndex(ai.worldTicks());
        if ((index.groupsInBox(x, y,
                ai.strategy().shepherd_clear) & (1 << EnemyIndex.WARRIOR | 1 << EnemyIndex.CHIEFTAIN)) != 0)
            return false;
        if (nearWalkTarget(x, y, intel))
            return false;
        float dx = x - sx;
        float dy = y - sy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        for (float d = 4f; d < len; d += 4f)
            if ((index.groupsInBox(sx + Math.round(dx * d / len), sy + Math.round(dy * d / len),
                    6) & 1 << EnemyIndex.WARRIOR) != 0)
                return false;
        return true;
    }

    /** shepherd_flee_pick: whether (x, y) lies within 14 cells of an attack-moving enemy warrior's target. */
    private boolean nearWalkTarget(int x, int y, @NonNull Intel intel) {
        walkTargets(intel);
        for (int i = 0; i < walk_n; i++)
            if (MapAnalysis.dist2(walk_tx[i], walk_ty[i], x, y) <= 14 * 14)
                return true;
        return false;
    }

    /**
     * shepherd_flee_pick: fills walk_tx / walk_ty with the targets of the living enemy warriors on an attack-move, the
     * first one per 4-cell bucket (a wave's warriors share one), once per world tick.
     */
    private void walkTargets(@NonNull Intel intel) {
        if (walk_tick == ai.worldTicks())
            return;
        walk_tick = ai.worldTicks();
        walk_n = 0;
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead() || !(e.getPrimaryController() instanceof WalkController w) || !w.isAgressive())
                continue;
            int tx = w.getTarget().getGridX();
            int ty = w.getTarget().getGridY();
            boolean seen = false;
            for (int i = 0; i < walk_n && !seen; i++)
                seen = walk_tx[i] >> 2 == tx >> 2 && walk_ty[i] >> 2 == ty >> 2;
            if (seen)
                continue;
            if (walk_n == walk_tx.length) {
                walk_tx = Arrays.copyOf(walk_tx, walk_n * 2);
                walk_ty = Arrays.copyOf(walk_ty, walk_n * 2);
            }
            walk_tx[walk_n] = tx;
            walk_ty[walk_n] = ty;
            walk_n++;
        }
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * shepherd_flee_side: the cell 22 cells from (sx, sy) along a perpendicular to the unit vector (ux, uy) that lies
     * farther from the threat centroid (cx, cy) than the shepherd and that sideClear accepts, else null. The side of
     * f's last sideways flee goes first while it is kept (FLEE_HOLD_TICKS after it), with sideClear's looser test for
     * a kept side; else the side the away vector (dx, dy) leans to. Picked afresh at every guard and tend, the flee
     * flipped between the two sides and inward within a second and the shepherd hardly moved (logged N=6 ludicrous
     * game); the centroid test keeps the other side from running past the threat.
     */
    private int @Nullable [] sideAway(@NonNull Flock f, int sx, int sy, float dx, float dy, float ux, float uy,
            float cx, float cy, @NonNull Intel intel) {
        boolean held = f.side_sign != 0 && ai.now() < f.side_until;
        int sign = held ? f.side_sign : dx * -uy + dy * ux < 0f ? -1 : 1;
        float d2 = (sx - cx) * (sx - cx) + (sy - cy) * (sy - cy);
        for (int k = 0; k < 2; k++) {
            int x = sx + Math.round(22 * sign * -uy);
            int y = sy + Math.round(22 * sign * ux);
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > d2 && sideClear(x, y, held && k == 0, intel)) {
                f.side_sign = sign;
                f.side_until = ai.now() + FLEE_HOLD_TICKS;
                return new int[]{x, y};
            }
            sign = -sign;
        }
        return null;
    }

    /**
     * shepherd_flee_side: whether (x, y) is reachable from our start, has no enemy warrior or chieftain (dead ones
     * still in Intel's lists included) within shepherd_clear cells along both axes, and lies more than 14 cells from
     * the target of every enemy warrior on an attack-move (the threatAway coming test, over all of them). A kept side
     * needs only the first two, within half shepherd_clear. With shepherd_all_circles it also lies outside every copy's
     * defense circles.
     */
    private boolean sideClear(int x, int y, boolean kept, @NonNull Intel intel) {
        if (!ai.planner().getStartField().reachable(x, y))
            return false;
        if (ai.strategy().shepherd_all_circles && inCircle(x, y))
            return false;
        int clear = kept ? ai.strategy().shepherd_clear / 2 : ai.strategy().shepherd_clear;
        int groups = intel.enemyIndex(ai.worldTicks()).groupsInBox(x, y, clear);
        if ((groups & (1 << EnemyIndex.WARRIOR | 1 << EnemyIndex.CHIEFTAIN)) != 0)
            return false;
        if (kept)
            return true;
        for (Unit e : intel.enemy_warriors)
            if (!e.isDead() && e.getPrimaryController() instanceof WalkController w && w.isAgressive()
                    && MapAnalysis.dist2(w.getTarget().getGridX(), w.getTarget().getGridY(), x, y) <= 14 * 14)
                return false;
        return true;
    }

    /**
     * A cell on the rings 14 to shepherd_max_r cells from the copy's wave origin (24 cells to a ring), nearer to it
     * than 0.66 of our nearest building and 0.89 of our nearest other unit, with no enemy within shepherd_clear cells
     * along both axes, outside the copy's defense circles and enemy towers' reach, reachable from our start, and as far
     * from our start as possible (spotScore). With s null this is the recruiting probe, which the shepherd_sticky,
     * shepherd_travel and shepherd_safe_walk terms leave alone.
     */
    private int @Nullable [] findSpot(@NonNull Flock f, int ox, int oy, @Nullable Unit s, @NonNull Intel intel) {
        // shepherd_clear_parked: the current spot came from the parked tier (the sticky block keeps it on that tier's
        // test: on enemyNear's the parked warriors it was picked beside block it, and it stays a candidate within
        // shepherd_grace_ticks with its flee relaxation gone, so the shepherd fled from the spot it was sent to).
        boolean was_parked = f.parked_spot;
        if (s != null)
            f.parked_spot = false;
        int building2 = nearestOwnBuilding2(ox, oy);
        if (building2 == Integer.MAX_VALUE)
            return null;
        int unit2 = nearestOtherUnit2(ox, oy, s, f.partner == null ? null : f.partner.shepherd, f.predicted);
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
        // shepherd_clear_parked: whether the sticky candidate is a kept parked-tier spot (it stays one if it wins).
        boolean sticky_parked = false;
        if (sticky) {
            // The current spot itself while it still leashes the wave: enemies passing by block it only after
            // shepherd_grace_ticks game ticks (the flee in tend keeps the shepherd safe meanwhile).
            int x = f.spot_x;
            int y = f.spot_y;
            int r2 = MapAnalysis.dist2(x, y, ox, oy);
            if (r2 >= 12 * 12 && r2 <= max_r * max_r && reach.reachable(x, y) && coverAt(guarded, intel, x, y) == 0
                    && !(f.predicted && landing(f, x, y))) {
                boolean blocked = (was_parked ? enemyNearParked(intel, x, y) : enemyNear(intel, x, y)) != null;
                if (!blocked)
                    f.blocked_since = -1f;
                else if (f.blocked_since < 0f)
                    f.blocked_since = ai.now();
                if (!blocked || ai.now() - f.blocked_since < strategy.shepherd_grace_ticks) {
                    float score = spotScore(x, y, (float) Math.sqrt(r2), bx, by, guarded) + strategy.shepherd_sticky;
                    if (travel > 0f)
                        score -= travel * (float) Math.sqrt(MapAnalysis.dist2(x, y, s.getGridX(), s.getGridY()));
                    n = addCandidate(n, x, y, score);
                    sticky_parked = was_parked;
                }
            }
        }
        n = ringCells(f, ox, oy, s, intel, guarded, 14, max_r, 24, false, rejections, n);
        if (n == 0 && strategy.shepherd_fallback_r >= 26 && limit >= 26f) {
            // shepherd_fallback_r: wider rings before giving up, where the leash allows them.
            n = ringCells(f, ox, oy, s, intel, guarded, 26, (int) Math.min(strategy.shepherd_fallback_r, limit), 32,
                    false, false, n);
            if (n > 0)
                ai.aiLog().count(s != null ? "shepherd_spot_fallback" : "shepherd_spot_fallback_probe");
        }
        if (n == 0 && strategy.shepherd_clear_parked > 0) {
            // shepherd_clear_parked: the rings again, with parked enemy warriors blocking only within
            // shepherd_clear_parked (the shepherd on such a spot flees from parked ones only that near: threatAway).
            n = ringCells(f, ox, oy, s, intel, guarded, 14, max_r, 24, true, false, n);
            if (n > 0) {
                ai.aiLog().count(s != null ? "shepherd_spot_parked" : "shepherd_spot_parked_probe");
                if (s != null)
                    f.parked_spot = true;
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
            return spotOf(f, sticky_parked, first);
        int pick = first;
        for (int tries = 0; tries < SAFE_TRIES; tries++) {
            if (safePath(s.getGridX(), s.getGridY(), cand_x[pick], cand_y[pick], intel)) {
                if (tries > 0)
                    ai.aiLog().count("shepherd_safe_detour");
                return spotOf(f, sticky_parked, pick);
            }
            cand_score[pick] = -Float.MAX_VALUE;
            pick = bestCandidate(n);
            if (cand_score[pick] == -Float.MAX_VALUE)
                break;
        }
        ai.aiLog().count("shepherd_safe_none");
        return spotOf(f, sticky_parked, first);
    }

    /**
     * findSpot's result, candidate i (the scores may be spent, the cells are not); shepherd_clear_parked: the sticky
     * candidate (0) that is a kept parked-tier spot stays one.
     */
    private int @NonNull [] spotOf(@NonNull Flock f, boolean sticky_parked, int i) {
        if (sticky_parked && i == 0) {
            f.parked_spot = true;
            ai.aiLog().count("shepherd_spot_parked_kept");
        }
        return new int[]{cand_x[i], cand_y[i]};
    }

    /**
     * findSpot's ring cells: adds to the n candidates the legal cells on the rings r0, r0 + 2, ... (by 4 from 22) up to
     * r1 cells around (ox, oy), angles cells to a ring, and returns the new count. With parked, enemyNearParked's test
     * replaces enemyNear's; with rejections, each rejected cell is counted (shepherd_rej_*).
     */
    private int ringCells(@NonNull Flock f, int ox, int oy, @Nullable Unit s, @NonNull Intel intel,
            @NonNull List<@NonNull Building> guarded, int r0, int r1, int angles, boolean parked, boolean rejections,
            int n) {
        Strategy strategy = ai.strategy();
        DistanceField reach = ai.planner().getStartField();
        int bx = ai.planner().getStartX();
        int by = ai.planner().getStartY();
        boolean sticky = s != null && strategy.shepherd_sticky > 0f && f.spot_x >= 0;
        float travel = s == null ? 0f : strategy.shepherd_travel;
        int half = angles / 2;
        for (int r = r0; r <= r1; r += r < 22 ? 2 : 4) {
            for (int a = 0; a < angles; a++) {
                double ang = a * Math.PI / half;
                int x = ox + (int) Math.round(r * Math.cos(ang));
                int y = oy + (int) Math.round(r * Math.sin(ang));
                if (!reach.reachable(x, y)) {
                    if (rejections)
                        ai.aiLog().count("shepherd_rej_reach");
                    continue;
                }
                String enemy = parked ? enemyNearParked(intel, x, y) : enemyNear(intel, x, y);
                if (enemy != null) {
                    if (rejections)
                        ai.aiLog().count(enemy);
                    continue;
                }
                int cover = coverAt(guarded, intel, x, y);
                if (cover != 0) {
                    if (rejections)
                        ai.aiLog().count(
                                cover == 1 ? "shepherd_rej_defense17" : cover == 2 ? "shepherd_rej_tower19" : "shepherd_rej_circle");
                    continue;
                }
                if (f.predicted && landing(f, x, y)) {
                    if (rejections)
                        ai.aiLog().count("shepherd_rej_landing");
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
        return n;
    }

    /**
     * shepherd_predict: whether (x, y) lies where the predicted wave lands or walks: within shepherd_clear plus the
     * wave's spread of its target along both axes (its warriors park around the target, and none stands there yet for
     * enemyNear to see), or within 10 cells of the walk from the predicted warrior to the target.
     */
    private boolean landing(@NonNull Flock f, int x, int y) {
        int c = ai.strategy().shepherd_clear + f.pred_spread;
        if (Math.abs(x - f.pred_x) <= c && Math.abs(y - f.pred_y) <= c)
            return true;
        float dx = f.pred_x - f.pred_ux;
        float dy = f.pred_y - f.pred_uy;
        float l2 = dx * dx + dy * dy;
        float t = l2 <= 0f ? 0f : Math.clamp(((x - f.pred_ux) * dx + (y - f.pred_uy) * dy) / l2, 0f, 1f);
        float qx = f.pred_ux + t * dx - x;
        float qy = f.pred_uy + t * dy - y;
        return qx * qx + qy * qy <= 10 * 10;
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
     * within TOWER_CELLS of an enemy tower, else (shepherd_all_circles) 3 within a defense circle of any copy, else 0.
     */
    private int coverAt(@NonNull List<@NonNull Building> guarded, @NonNull Intel intel, int x, int y) {
        for (Building b : guarded)
            if (MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y) <= DEFENSE_CELLS * DEFENSE_CELLS)
                return 1;
        for (Building t : intel.enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= TOWER_CELLS * TOWER_CELLS)
                return 2;
        if (ai.strategy().shepherd_all_circles && inCircle(x, y))
            return 3;
        return 0;
    }

    /** shepherd_all_circles, shepherd_flee_pick: whether (x, y) lies within DEFENSE_CELLS of a living circle centre. */
    private boolean inCircle(int x, int y) {
        for (Building b : circles)
            if (!b.isDead() && MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y) <= DEFENSE_CELLS * DEFENSE_CELLS)
                return true;
        return false;
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
        EnemyIndex index = intel.enemyIndex(ai.worldTicks());
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
        EnemyIndex index = intel.enemyIndex(ai.worldTicks());
        int groups = index.groupsInBox(x, y, clear);
        if ((groups & 1 << EnemyIndex.WARRIOR) != 0)
            return "shepherd_rej_warrior";
        if ((groups & 1 << EnemyIndex.PEON) != 0 && (ai.strategy().shepherd_calm_peons <= 0 || peonNear(index, x, y,
                clear)))
            return "shepherd_rej_peon";
        return (groups & 1 << EnemyIndex.CHIEFTAIN) != 0 ? "shepherd_rej_chief" : null;
    }

    /**
     * shepherd_calm_peons: whether a living enemy peon within clear cells of (x, y) along both axes could start a
     * fight: one that is active, or any peon within shepherd_calm_peons cells.
     */
    private boolean peonNear(@NonNull EnemyIndex index, int x, int y, int clear) {
        int calm = ai.strategy().shepherd_calm_peons;
        int[] found = index.queryUnordered(x, y, 2 * clear * clear);
        for (int k = 0, m = index.count(); k < m; k++) {
            if (!index.isPeon(found[k]))
                continue;
            Unit e = index.unit(found[k]);
            if (e.isDead())
                continue;
            int d = Math.max(Math.abs(e.getGridX() - x), Math.abs(e.getGridY() - y));
            if (d <= clear && (d <= calm || active(e)))
                return true;
        }
        return false;
    }

    /**
     * shepherd_clear_parked: enemyNear, except that a parked enemy warrior (Intel.isParked: idle on its default
     * controller, blind beyond 8 cells) blocks (x, y) only within shepherd_clear_parked cells; dead warriors block
     * nothing, while peons and chieftains are tested as in enemyNear.
     */
    private @Nullable String enemyNearParked(@NonNull Intel intel, int x, int y) {
        int clear = ai.strategy().shepherd_clear;
        int parked = ai.strategy().shepherd_clear_parked;
        EnemyIndex index = intel.enemyIndex(ai.worldTicks());
        // With no warrior in the box this is enemyNear (the box test is cheaper than the scan).
        if ((index.groupsInBox(x, y, clear) & 1 << EnemyIndex.WARRIOR) == 0)
            return enemyNear(intel, x, y);
        int[] found = index.queryUnordered(x, y, 2 * clear * clear);
        int groups = 0;
        for (int k = 0, m = index.count(); k < m; k++) {
            Unit e = index.unit(found[k]);
            int d = Math.max(Math.abs(e.getGridX() - x), Math.abs(e.getGridY() - y));
            if (d > clear)
                continue;
            byte group = index.group(found[k]);
            if (group != EnemyIndex.WARRIOR)
                groups |= 1 << group;
            else if (!e.isDead() && (d <= parked || !Intel.isParked(e)))
                return "shepherd_rej_warrior";
        }
        // After the loop: peonNear's query reuses the index's result array.
        if ((groups & 1 << EnemyIndex.PEON) != 0 && (ai.strategy().shepherd_calm_peons <= 0 || peonNear(index, x, y,
                clear)))
            return "shepherd_rej_peon";
        return (groups & 1 << EnemyIndex.CHIEFTAIN) != 0 ? "shepherd_rej_chief" : null;
    }

    /**
     * shepherd_calm_peons: an enemy peon that may start a fight: idle (it attacks what it sees), defending, attacking
     * or walking aggressively (a hunter walking back), or with a hunt or an attack on top. A DefendController peon is
     * what the Hard sends at a threat near its base, so it is active although no fight is on yet. Check isDead first.
     */
    private static boolean active(@NonNull Unit peon) {
        Controller primary = peon.getPrimaryController();
        Controller current = peon.getCurrentController();
        return primary instanceof IdleController || primary instanceof DefendController
                || primary instanceof AttackController || (primary instanceof WalkController w && w.isAgressive())
                || current instanceof HuntController || current instanceof AttackController;
    }

    /** Base-bound waves seen from a copy so far (front_order 2). */
    int baseWaves(@NonNull Player p) {
        Flock f = flockOf(p);
        return f == null ? 0 : f.base_waves;
    }

    /**
     * Launches seen from a copy so far (its wave size is 10 + 5 per launch, so from two on it needs a chieftain), or -1
     * while no flock watches it (shepherd off, or before the flocks start). Read by decapitate.
     */
    int launches(@NonNull Player p) {
        Flock f = flockOf(p);
        return f == null ? -1 : f.launches;
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

    /**
     * The squared cells from (x, y) to our nearest unit but self and partner; with predicted (shepherd_predict: a
     * walking wave's target), our shepherds within 14 cells of it left out too.
     */
    private int nearestOtherUnit2(int x, int y, @Nullable Unit self, @Nullable Unit partner, boolean predicted) {
        int best = Integer.MAX_VALUE;
        Intel intel = ai.intel();
        for (Selectable<?> sel : ai.owner().getUnits().getSet())
            if (sel instanceof Unit u && u != self && u != partner && !u.isDead() && !u.isMounted()) {
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y);
                if (d < best && !(predicted && d <= 14 * 14 && intel.shepherds.contains(u)))
                    best = d;
            }
        return best;
    }
}
