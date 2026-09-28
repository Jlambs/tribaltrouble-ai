package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.gauntlet.Intel.PeonState;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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
    private static final int CLEAR_CELLS = 10;
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

        Flock(@NonNull Player copy) {
            this.copy = copy;
        }
    }

    Shepherd(@NonNull GauntletAI ai) {
        this.ai = ai;
    }

    boolean isShepherd(@NonNull Unit u) {
        for (Flock f : flocks)
            if (f.shepherd == u)
                return true;
        return false;
    }

    void tick() {
        Strategy strategy = ai.strategy();
        if (!strategy.shepherd || ai.time() - last_tick < .5f)
            return;
        last_tick = ai.time();
        if (ai.time() < strategy.shepherd_time || ai.time() > strategy.shepherd_until) {
            releaseAll();
            return;
        }
        Intel intel = ai.intel();
        for (Player p : ai.owner().getWorld().getPlayers()) {
            if (!ai.owner().isEnemy(p) || !p.isAlive())
                continue;
            Flock f = flockOf(p);
            if (f == null) {
                f = new Flock(p);
                flocks.add(f);
            }
        }
        for (Flock f : flocks) {
            if (f.shepherd != null && f.shepherd.isDead()) {
                ai.aiLog().count("shepherd_lost");
                release(f);
            }
            if (!f.copy.isAlive()) {
                release(f);
                continue;
            }
            tend(f, intel);
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
        // A wave started: count it if it heads for our spot, and let the shepherd clear out.
        if (f.leader != null && f.leader != leader && !f.leader.isDead()
                && f.leader.getPrimaryController() instanceof WalkController walk && walk.isAgressive()) {
            if (f.spot_x >= 0 && MapAnalysis.dist2(walk.getTarget().getGridX(), walk.getTarget().getGridY(), f.spot_x,
                    f.spot_y) <= 4 * 4)
                ai.aiLog().count("shepherd_launch");
            else
                ai.aiLog().count("shepherd_wild");
        }
        f.leader = leader;
        if (f.shepherd == null) {
            f.shepherd = recruit(ox, oy, intel);
            if (f.shepherd == null)
                return;
            intel.shepherds.add(f.shepherd);
            ai.aiLog().count("shepherd_recruit");
        }
        Unit s = f.shepherd;
        // Flee first: any enemy near the shepherd, or a wave walking toward where it stands.
        int[] away = threatAway(s, intel);
        if (away != null) {
            ai.landscapeOrder(Selectable.newArray(s), away[0], away[1], Action.MOVE, false);
            f.last_order = ai.time();
            return;
        }
        int[] spot = findSpot(f, ox, oy, s, intel);
        if (spot == null) {
            // No spot draws this copy's wave: stand back where it is safe.
            f.spot_x = -1;
            return;
        }
        f.spot_x = spot[0];
        f.spot_y = spot[1];
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
            int d = MapAnalysis.dist2(p.getGridX(), p.getGridY(), ox, oy);
            if (d < best_d) {
                best_d = d;
                best = p;
            }
        }
        return best;
    }

    /** A point to run to when enemies are near the shepherd or a wave is walking at it, else null. */
    private int @Nullable [] threatAway(@NonNull Unit s, @NonNull Intel intel) {
        int sx = s.getGridX();
        int sy = s.getGridY();
        long ex = 0;
        long ey = 0;
        int n = 0;
        for (Unit e : intel.enemy_warriors) {
            int dx = e.getGridX() - sx;
            int dy = e.getGridY() - sy;
            boolean near = Math.abs(dx) <= CLEAR_CELLS && Math.abs(dy) <= CLEAR_CELLS;
            boolean coming = false;
            if (!near && e.getPrimaryController() instanceof WalkController w && w.isAgressive()
                    && dx * dx + dy * dy <= 40 * 40) {
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
        for (Unit e : intel.enemy_peons) {
            if (Math.abs(e.getGridX() - sx) <= CLEAR_CELLS && Math.abs(e.getGridY() - sy) <= CLEAR_CELLS) {
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
        return new int[]{sx + Math.round(18 * dx / len), sy + Math.round(18 * dy / len)};
    }

    /**
     * A cell 12-20 cells from the copy's wave origin, nearer to it than 0.66 of our nearest building and nearer than
     * any other unit of ours, clear of every enemy by 10 cells, of the copy's defense circles and of enemy towers,
     * reachable, and as far from our start as possible.
     */
    private int @Nullable [] findSpot(@NonNull Flock f, int ox, int oy, @NonNull Unit s, @NonNull Intel intel) {
        int building2 = nearestOwnBuilding2(ox, oy);
        if (building2 == Integer.MAX_VALUE)
            return null;
        int unit2 = nearestOtherUnit2(ox, oy, s);
        float limit = (float) Math.sqrt(Math.min(building2 * .44f, unit2 * .8f));
        int max_r = (int) Math.min(20f, limit);
        if (max_r < 12)
            return null;
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
        for (int r = 12; r <= max_r; r += 2) {
            for (int a = 0; a < 24; a++) {
                double ang = a * Math.PI / 12;
                int x = ox + (int) Math.round(r * Math.cos(ang));
                int y = oy + (int) Math.round(r * Math.sin(ang));
                if (!reach.reachable(x, y))
                    continue;
                if (!clearOfEnemies(intel, x, y))
                    continue;
                boolean ok = true;
                for (Building b : guarded)
                    if (MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y) <= DEFENSE_CELLS * DEFENSE_CELLS) {
                        ok = false;
                        break;
                    }
                if (!ok)
                    continue;
                for (Building t : intel.enemy_towers)
                    if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= TOWER_CELLS * TOWER_CELLS) {
                        ok = false;
                        break;
                    }
                if (!ok)
                    continue;
                float score = (float) Math.sqrt(MapAnalysis.dist2(x, y, bx, by)) - r * .5f;
                if (score > best_score) {
                    best_score = score;
                    best = new int[]{x, y};
                }
            }
        }
        return best;
    }

    private boolean clearOfEnemies(@NonNull Intel intel, int x, int y) {
        for (Unit e : intel.enemy_warriors)
            if (Math.abs(e.getGridX() - x) <= CLEAR_CELLS && Math.abs(e.getGridY() - y) <= CLEAR_CELLS)
                return false;
        for (Unit e : intel.enemy_peons)
            if (Math.abs(e.getGridX() - x) <= CLEAR_CELLS && Math.abs(e.getGridY() - y) <= CLEAR_CELLS)
                return false;
        for (Unit e : intel.enemy_chieftains)
            if (Math.abs(e.getGridX() - x) <= CLEAR_CELLS && Math.abs(e.getGridY() - y) <= CLEAR_CELLS)
                return false;
        return true;
    }

    private int nearestOwnBuilding2(int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Selectable<?> sel : ai.owner().getUnits().getSet())
            if (sel instanceof Building b && !b.isDead()
                    && b.getTemplate().getType() == BuildingTemplate.TYPE_BUILDING)
                best = Math.min(best, MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y));
        return best;
    }

    private int nearestOtherUnit2(int x, int y, @NonNull Unit self) {
        int best = Integer.MAX_VALUE;
        for (Selectable<?> sel : ai.owner().getUnits().getSet())
            if (sel instanceof Unit u && u != self && !u.isDead() && !u.isMounted())
                best = Math.min(best, MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y));
        return best;
    }
}
