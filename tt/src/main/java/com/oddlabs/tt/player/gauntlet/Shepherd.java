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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A shepherd peon per Hard copy that draws each of its waves onto empty ground away from our base.
 *
 * <p>A copy aims its wave from its oldest idle warrior at our nearest selectable of any kind if that one is closer
 * than 0.707 of our nearest building, else at that building (AdvancedAI.findTarget, Player.findNearestEnemy), and
 * orders an attack-move to that target's cell as it stands (a snapshot). A lone peon of ours standing 12-20 cells from
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

    private final @NonNull GauntletAI ai;
    private final List<@NonNull Flock> flocks = new ArrayList<>();
    private float last_tick = -10f;

    private static final class Flock {
        final @NonNull Player copy;
        @Nullable
        Unit shepherd;
        int spot_x = -1;
        int spot_y = -1;
        @Nullable
        Unit leader;
        boolean launched;
        float last_order = -100f;
        float nospot_since = -1f;
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
        // Log only (maxn K2), never read by a decision: arrival, spot jumps and launches.
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
        @Nullable
        Unit prev_wave;

        Flock(@NonNull Player copy) {
            this.copy = copy;
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
            flocks.add(new Flock(p));
        for (Flock f : flocks) {
            if (f.shepherd != null && f.shepherd.isDead()) {
                ai.aiLog().count("shepherd_lost");
                if (ai.logging())
                    ai.log("shepherd of " + f.copy.getPlayerInfo().getName() + " lost at " + f.last_x + "," + f.last_y + " (spot " + f.spot_x + "," + f.spot_y + ", nearest enemy warrior " + nearestEnemy(
                            ai.intel().enemy_warriors, f.last_x, f.last_y) + " cells, peon " + nearestEnemy(
                                    ai.intel().enemy_peons, f.last_x, f.last_y) + ", tower " + nearestTower(f.last_x,
                                            f.last_y) + ", recruited " + (int) (ai.time() - f.recruited) + " s ago)");
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
            if (away != null && ai.time() - f.last_order >= .3f) {
                ai.landscapeOrder(Selectable.newArray(s), away[0], away[1], Action.MOVE, false);
                f.last_order = ai.time();
            }
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
        if (leader != null) {
            ox = leader.getGridX();
            oy = leader.getGridY();
        } else {
            Building armory = armory(f.copy);
            if (armory == null)
                return; // no armory, no warriors: no wave to steer yet
            ox = armory.getGridX();
            oy = armory.getGridY();
        }
        String origin = leader != null ? "leader" : "armory";
        // A wave started: count it if it heads for our spot, and let the shepherd clear out.
        if (f.leader != null && f.leader != leader && !f.leader.isDead()
                && f.leader.getPrimaryController() instanceof WalkController walk && walk.isAgressive()) {
            int tx = walk.getTarget().getGridX();
            int ty = walk.getTarget().getGridY();
            // An idle warrior that spots something walks back to its own cell after the hunt: not a wave.
            if (MapAnalysis.dist2(tx, ty, f.leader.getGridX(), f.leader.getGridY()) > 20 * 20) {
                f.launches++;
                if (f.spot_x >= 0 && MapAnalysis.dist2(tx, ty, f.spot_x, f.spot_y) <= 4 * 4) {
                    ai.aiLog().count("wave_drawn");
                    ai.log("wave of " + f.copy.getPlayerInfo().getName() + " drawn to " + tx + "," + ty);
                } else {
                    Building near = nearestOwnBuilding(tx, ty);
                    boolean base = near != null && MapAnalysis.dist2(near.getGridX(), near.getGridY(), tx,
                            ty) <= 20 * 20;
                    // A wave drawn to one of our decoy sites is not a base wave.
                    boolean decoy = base && ai.decoys().isDecoy(near);
                    if (base && !decoy)
                        f.base_waves++;
                    ai.aiLog().count(decoy ? "wave_to_decoy" : base ? "wave_to_base" : "wave_elsewhere");
                    ai.log("wave of " + f.copy.getPlayerInfo().getName() + " goes to " + tx + "," + ty + (decoy ? " (our decoy)" : base ? " (our base)" : "") + " (spot " + f.spot_x + "," + f.spot_y + ")");
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
            f.lead_x = ox;
            f.lead_y = oy;
        }
        if (ai.strategy().shepherd_hold) {
            int num = Math.min(40, 10 + 5 * f.launches);
            int idle = 0;
            for (Unit e : intel.enemy_warriors)
                if (!e.isDead() && e.getOwner() == f.copy
                        && e.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.IdleController)
                    idle++;
            boolean was = f.imminent;
            f.imminent = idle >= num && (num < 20 || f.copy.hasActiveChieftain());
            if (f.imminent && !was)
                ai.aiLog().count("shepherd_imminent");
        }
        if (ai.time() < strategy.shepherd_time)
            return; // shepherd_lead: before shepherd_time flocks only watch
        if (f.shepherd == null) {
            // shepherd_range: far copies' shepherds walk 150-300 cells and die on the way (N=10 logs); skip them.
            int range = ai.strategy().shepherd_range;
            if (range < 100000 && MapAnalysis.dist2(ox, oy, ai.planner().getStartX(),
                    ai.planner().getStartY()) > range * range)
                return;
            // Only when some spot would draw this copy's wave, counting every unit of ours as a rival target.
            int[] probe = findSpot(f, ox, oy, null, intel);
            if (probe == null) {
                if (ai.strategy().site_shepherd && ai.time() >= ai.strategy().shepherd_time)
                    ai.decoys().placeHome(f.copy, ox, oy);
                return;
            }
            Unit candidate = recruit(ox, oy, intel);
            if (candidate == null) {
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
            if (lead)
                ai.aiLog().count("shepherd_lead_recruit");
            f.arrived = false;
            f.rec_x = candidate.getGridX();
            f.rec_y = candidate.getGridY();
            f.rec_spot_x = probe[0];
            f.rec_spot_y = probe[1];
            f.prev_spot_x = -1;
            if (ai.logging())
                ai.log("shepherd of " + f.copy.getPlayerInfo().getName() + " recruited: " + (state == null ? "?" : state.name().toLowerCase(
                        Locale.ROOT)) + " peon at " + f.rec_x + "," + f.rec_y + ", spot " + probe[0] + "," + probe[1] + ", walk " + (int) Math.sqrt(
                                walk2) + " cells, origin " + origin + (lead ? ", lead (armory at " + (int) f.armory_at + " s)" : ""));
        }
        Unit s = f.shepherd;
        f.last_x = s.getGridX();
        f.last_y = s.getGridY();
        // Flee first: any enemy near the shepherd, or a wave walking toward where it stands.
        int[] away = threatAway(s, intel, ai.strategy().shepherd_hold && f.imminent ? 9 : CLEAR_CELLS);
        if (away != null) {
            if (ai.time() - f.last_order >= .3f) {
                ai.landscapeOrder(Selectable.newArray(s), away[0], away[1], Action.MOVE, false);
                f.last_order = ai.time();
            }
            return;
        }
        int[] spot = findSpot(f, ox, oy, s, intel);
        if (spot == null) {
            // No spot draws this copy's wave: a shepherd left standing there only gets killed, so after a while it
            // goes home (a new one is recruited once a spot opens up again).
            f.spot_x = -1;
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
                ai.log("spot of " + f.copy.getPlayerInfo().getName() + " jumps " + (int) Math.sqrt(MapAnalysis.dist2(
                        f.prev_spot_x, f.prev_spot_y, spot[0],
                        spot[1])) + " cells from " + f.prev_spot_x + "," + f.prev_spot_y + " (origin " + f.origin + ") to " + spot[0] + "," + spot[1] + " (origin " + origin + ")");
        }
        f.prev_spot_x = spot[0];
        f.prev_spot_y = spot[1];
        f.origin = origin;
        f.spot_x = spot[0];
        f.spot_y = spot[1];
        if (!f.arrived && MapAnalysis.dist2(s.getGridX(), s.getGridY(), spot[0], spot[1]) <= 3 * 3) {
            f.arrived = true;
            ai.aiLog().count("shepherd_at_spot");
            if (ai.logging())
                ai.log("shepherd of " + f.copy.getPlayerInfo().getName() + " at spot after " + (int) (ai.time() - f.recruited) + " s (from " + f.rec_x + "," + f.rec_y + ", " + (int) Math.sqrt(
                        MapAnalysis.dist2(
                                f.rec_x, f.rec_y, s.getGridX(),
                                s.getGridY())) + " cells; spot moved " + (int) Math.sqrt(MapAnalysis.dist2(f.rec_spot_x,
                                        f.rec_spot_y, spot[0], spot[1])) + " cells since recruited)");
        }
        if (MapAnalysis.dist2(s.getGridX(), s.getGridY(), spot[0], spot[1]) > 2 * 2
                && ai.time() - f.last_order >= 2f) {
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
            if (intel.shepherds.contains(p))
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
        int[] candidates = index.query(sx, sy, Math.max(40 * 40, 2 * clear * clear));
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
     * A cell 12-20 cells from the copy's wave origin, nearer to it than 0.66 of our nearest building and nearer than
     * any other unit of ours, clear of every enemy by 10 cells, of the copy's defense circles and of enemy towers,
     * reachable, and as far from our start as possible.
     */
    private int @Nullable [] findSpot(@NonNull Flock f, int ox, int oy, @Nullable Unit s, @NonNull Intel intel) {
        int building2 = nearestOwnBuilding2(ox, oy);
        if (building2 == Integer.MAX_VALUE)
            return null;
        int unit2 = nearestOtherUnit2(ox, oy, s);
        float limit = (float) Math.sqrt(Math.min(building2 * .44f, unit2 * .8f));
        int max_r = (int) Math.min(ai.strategy().shepherd_max_r, limit);
        // Candidate cells the leash cuts off (counter only).
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
        int[] best = null;
        float best_score = -Float.MAX_VALUE;
        for (int r = 14; r <= max_r; r += r < 22 ? 2 : 4) {
            for (int a = 0; a < 24; a++) {
                double ang = a * Math.PI / 12;
                int x = ox + (int) Math.round(r * Math.cos(ang));
                int y = oy + (int) Math.round(r * Math.sin(ang));
                if (!reach.reachable(x, y)) {
                    ai.aiLog().count("shepherd_rej_reach");
                    continue;
                }
                String enemy = enemyNear(intel, x, y);
                if (enemy != null) {
                    ai.aiLog().count(enemy);
                    continue;
                }
                boolean ok = true;
                for (Building b : guarded)
                    if (MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y) <= DEFENSE_CELLS * DEFENSE_CELLS) {
                        ok = false;
                        break;
                    }
                if (!ok) {
                    ai.aiLog().count("shepherd_rej_defense17");
                    continue;
                }
                for (Building t : intel.enemy_towers)
                    if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= TOWER_CELLS * TOWER_CELLS) {
                        ok = false;
                        break;
                    }
                if (!ok) {
                    ai.aiLog().count("shepherd_rej_tower19");
                    continue;
                }
                float score = (float) Math.sqrt(MapAnalysis.dist2(x, y, bx, by)) - r * .5f;
                float home_weight = ai.strategy().shepherd_home_weight;
                if (home_weight > 0f && !guarded.isEmpty()) {
                    int home = Integer.MAX_VALUE;
                    for (Building b : guarded)
                        home = Math.min(home, MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y));
                    score += home_weight * (float) Math.sqrt(home);
                }
                if (score > best_score) {
                    best_score = score;
                    best = new int[]{x, y};
                }
            }
        }
        if (best == null)
            ai.aiLog().count("shepherd_nospot_ground");
        return best;
    }

    /**
     * Null when no enemy unit (dead ones still in Intel's lists included) stands within shepherd_clear cells of (x, y)
     * along both axes, else the findSpot rejection counter: warriors first, then peons, then chieftains.
     */
    private @Nullable String enemyNear(@NonNull Intel intel, int x, int y) {
        EnemyIndex index = intel.enemyIndex(ai.ticks());
        int[] near = index.queryBox(x, y, ai.strategy().shepherd_clear);
        boolean peon = false;
        boolean chief = false;
        for (int k = 0, n = index.count(); k < n; k++) {
            byte group = index.group(near[k]);
            if (group == EnemyIndex.WARRIOR)
                return "shepherd_rej_warrior";
            peon |= group == EnemyIndex.PEON;
            chief |= group == EnemyIndex.CHIEFTAIN;
        }
        return peon ? "shepherd_rej_peon" : chief ? "shepherd_rej_chief" : null;
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
        ai.log("launch of " + f.copy.getPlayerInfo().getName() + " from " + f.lead_x + "," + f.lead_y + " (previous wave " + prev_state + ") to " + tx + "," + ty + ": target " + targetClass(
                tx, ty) + ", nearest unit " + (nearest == null ? "none" : roleOf(nearest) + " " + (int) Math.sqrt(
                        nearest_d) + " cells") + ", own shepherd " + shepherd_d + " cells");
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
                return "shepherd of " + f.copy.getPlayerInfo().getName();
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

    private int nearestOwnBuilding2(int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Selectable<?> sel : ai.owner().getUnits().getSet())
            if (sel instanceof Building b && !b.isDead()
                    && b.getTemplate().getType() == BuildingTemplate.TYPE_BUILDING)
                best = Math.min(best, MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y));
        return best;
    }

    private int nearestOtherUnit2(int x, int y, @Nullable Unit self) {
        int best = Integer.MAX_VALUE;
        for (Selectable<?> sel : ai.owner().getUnits().getSet())
            if (sel instanceof Unit u && u != self && !u.isDead() && !u.isMounted())
                best = Math.min(best, MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y));
        return best;
    }
}
