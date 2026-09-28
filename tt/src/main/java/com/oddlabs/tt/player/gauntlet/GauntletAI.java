package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.AiParams;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Computer player built to beat many allied stock Hard AIs at once. It started as a port of the Expert AI (branch
 * expert-ai of the owner's tribaltrouble checkout, package player/ai): it lays out a base by resource value, balances
 * peons between reproduction, gathering and weapon making, keeps weapons flowing into a clumped army, defends with
 * towers and the chieftain's stun, and attacks when it has the numbers. lab/gauntlet/NOTES.md records what changed
 * since, and why.
 *
 * <p>The AI only iterates insertion-ordered collections, so every peer of a multiplayer game makes the same decisions.
 */
public final class GauntletAI extends AI {
    private static final float INTEL_PERIOD = .5f;
    private static final float ECONOMY_PERIOD = 1f;
    private static final float PLAN_PERIOD = 3f;
    private static final float SCAN_PERIOD = 30f;

    private final @NonNull AiLog log;
    private final @NonNull Strategy strategy;
    private final @NonNull Random random;
    private final @NonNull Intel intel;
    private final @NonNull Reflexes reflexes;
    private @Nullable MapAnalysis map;
    private @Nullable SitePlanner planner;
    private @Nullable Economy economy;
    private @Nullable Military military;
    private @Nullable Chieftain chieftain;
    private @Nullable Decoys decoys;
    private @Nullable Shepherd shepherd;

    private int ticks;
    private float time;
    private float next_intel;
    private float next_economy = .25f;
    private float next_plan = .5f;
    private float next_scan = SCAN_PERIOD;
    private float next_stat = 30f;
    private boolean initialized;
    /** Whether the decision log is written anywhere, so that describing decisions is worth the work. */
    private boolean logging;

    public GauntletAI(@NonNull Player owner, @NonNull UnitInfo units, @NonNull String spec_params) {
        super(owner, units); // first: registers this AI and creates the starting units
        log = AiLog.of(owner);
        AiParams params = AiParams.parse(spec_params);
        strategy = Strategy.forGame(owner.getWorld().getMapSize(), countEnemies(owner));
        strategy.apply(params);
        String read = params.done(); // fails on unknown keys, so a typo cannot silently play the defaults
        log.log("PARAM", read);
        log.log("LOG", () -> {
            logging = true;
            return "decision log on";
        });
        random = new Random(7919L * (1 + indexOf(owner)));
        intel = new Intel(owner);
        reflexes = new Reflexes(this, strategy.swing_restart, strategy.stun_cancel, strategy.tower_unstun);
    }

    private static int countEnemies(@NonNull Player owner) {
        int n = 0;
        for (Player p : owner.getWorld().getPlayers())
            if (owner.isEnemy(p))
                n++;
        return n;
    }

    private static int indexOf(@NonNull Player owner) {
        Player[] players = owner.getWorld().getPlayers();
        for (int i = 0; i < players.length; i++)
            if (players[i] == owner)
                return i;
        return 0;
    }

    @Override
    public void animate(float t) {
        if (!Globals.run_ai)
            return;
        ticks++;
        time = ticks / (float) GameTime.TICKS_PER_SECOND;
        try {
            reflexes.tick();
            if (initialized && ticks % 5 == 0)
                shepherd().guard();
            think();
        } catch (RuntimeException | AssertionError e) {
            // Engine getters assert on units that just died: count the error (every result row shows it), log the
            // first stack traces, and let the game go on. Every peer hits the same mistake at the same tick, so
            // skipping the rest of this think keeps multiplayer games in step.
            log.error("think", e);
        }
    }

    private void think() {
        if (!initialized) {
            initialize();
            initialized = true;
        }
        if (!getOwner().isAlive())
            return;
        boolean due_intel = time >= next_intel;
        boolean due_economy = time >= next_economy;
        boolean due_plan = time >= next_plan;
        if (due_intel || due_economy || due_plan)
            intel.update();
        if (due_intel) {
            next_intel = time + INTEL_PERIOD;
            military().tick();
            chieftain().tick();
            shepherd().tick();
        }
        if (due_economy) {
            next_economy = time + ECONOMY_PERIOD;
            economy().tick();
            decoys().tick();
        }
        if (due_plan) {
            next_plan = time + PLAN_PERIOD;
            economy().plan();
            military().plan();
        }
        if (logging && time >= next_stat) {
            next_stat = time + 30f;
            log.log("STAT", debugStatus());
        }
        if (time >= next_scan) {
            next_scan = time + SCAN_PERIOD;
            map().scanSupplies();
        }
        military().dodgeSpells();
    }

    private void initialize() {
        Player owner = getOwner();
        map = new MapAnalysis(owner.getWorld());
        int sx = UnitGrid.toGridCoordinate(owner.getStartX());
        int sy = UnitGrid.toGridCoordinate(owner.getStartY());
        Player enemy = nearestEnemy(sx, sy);
        int ex = enemy != null ? UnitGrid.toGridCoordinate(enemy.getStartX()) : map.getSize() - sx;
        int ey = enemy != null ? UnitGrid.toGridCoordinate(enemy.getStartY()) : map.getSize() - sy;
        DistanceField start_field = map.computeField(sx, sy, Integer.MAX_VALUE);
        // Danger comes from every enemy start, not just the nearest: with several enemies the middle is no-man's land.
        DistanceField enemy_field = enemyStarts(ex, ey);
        planner = new SitePlanner(map, owner, strategy, sx, sy, ex, ey, start_field, enemy_field);
        log(String.format("map %d cells, start %d,%d, nearest enemy starts %d,%d (%dm walk), %d enemies", map.getSize(),
                sx, sy, ex, ey, start_field.get(ex, ey), countEnemies(owner)));
        intel.update();
        economy = new Economy(this);
        military = new Military(this);
        chieftain = new Chieftain(this);
        decoys = new Decoys(this);
        shepherd = new Shepherd(this);
        intel.decoys = decoys;
    }

    private @NonNull DistanceField enemyStarts(int nearest_x, int nearest_y) {
        List<int[]> starts = new ArrayList<>();
        starts.add(new int[]{nearest_x, nearest_y});
        for (Player p : getOwner().getWorld().getPlayers()) {
            if (!getOwner().isEnemy(p))
                continue;
            int x = UnitGrid.toGridCoordinate(p.getStartX());
            int y = UnitGrid.toGridCoordinate(p.getStartY());
            if (x != nearest_x || y != nearest_y)
                starts.add(new int[]{x, y});
        }
        int[] xs = new int[starts.size()];
        int[] ys = new int[starts.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = starts.get(i)[0];
            ys[i] = starts.get(i)[1];
        }
        return map().computeField(xs, ys, Integer.MAX_VALUE);
    }

    /** Enemy players still in the game. */
    int enemiesAlive() {
        int n = 0;
        for (Player p : getOwner().getWorld().getPlayers())
            if (getOwner().isEnemy(p) && p.isAlive())
                n++;
        return n;
    }

    private @Nullable Player nearestEnemy(int sx, int sy) {
        Player best = null;
        int best_d = Integer.MAX_VALUE;
        for (Player p : getOwner().getWorld().getPlayers()) {
            if (!getOwner().isEnemy(p))
                continue;
            int d = MapAnalysis.dist2(sx, sy, UnitGrid.toGridCoordinate(p.getStartX()),
                    UnitGrid.toGridCoordinate(p.getStartY()));
            if (d < best_d) {
                best_d = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Sends {@code builders} to place a building site of {@code type} at a cell the caller checked, as the UI does,
     * and returns the site they carry (or build, when one already stood on the spot), or null when none took it.
     */
    @Nullable
    Building placeSite(@NonNull List<@NonNull Unit> builders, int type, int gx, int gy) {
        if (builders.isEmpty())
            return null;
        getOwner().placeBuilding(builders.toArray(new Selectable<?>[0]), type, gx, gy);
        for (Unit u : builders) {
            if (u.isDead())
                continue;
            Controller c = u.getPrimaryController();
            if (c instanceof PlaceBuildingController placing)
                return placing.getBuilding();
            if (c instanceof RepairController repairing)
                return repairing.getBuilding();
        }
        return null;
    }

    /**
     * A landscape order with the target clamped into the map: a unit walking toward a point outside it leaves the
     * height map and crashes the game (ArrayIndexOutOfBoundsException in HeightMap.getLeafFromCoordinates; seen with
     * the Expert AI in base-expert-vs5 s98-0, and by sweep in s62-shcd45-vs4 s77-0).
     */
    void landscapeOrder(Selectable<?> @NonNull [] units, int gx, int gy, @NonNull Action action, boolean aggressive) {
        int size = getOwner().getWorld().getUnitGrid().getGridSize();
        int x = Math.max(3, Math.min(size - 4, gx));
        int y = Math.max(3, Math.min(size - 4, gy));
        getOwner().setLandscapeTarget(units, x, y, action, aggressive);
    }

    @NonNull
    Player owner() {
        return getOwner();
    }

    float time() {
        return time;
    }

    @NonNull
    Random random() {
        return random;
    }

    @NonNull
    Strategy strategy() {
        return strategy;
    }

    @NonNull
    Intel intel() {
        return intel;
    }

    @NonNull
    AiLog aiLog() {
        return log;
    }

    @NonNull
    MapAnalysis map() {
        assert map != null;
        return map;
    }

    @NonNull
    SitePlanner planner() {
        assert planner != null;
        return planner;
    }

    @NonNull
    Economy economy() {
        assert economy != null;
        return economy;
    }

    @NonNull
    Military military() {
        assert military != null;
        return military;
    }

    @NonNull
    Shepherd shepherd() {
        assert shepherd != null;
        return shepherd;
    }

    @NonNull
    Decoys decoys() {
        assert decoys != null;
        return decoys;
    }

    @NonNull
    Chieftain chieftain() {
        assert chieftain != null;
        return chieftain;
    }

    /** Whether decisions are being written anywhere, so that describing them is worth the work. */
    boolean logging() {
        return logging;
    }

    void log(@NonNull String message) {
        if (logging)
            log.log("AI", message);
    }

    /** A one-line summary of the AI's state. */
    @NonNull
    String debugStatus() {
        if (economy == null || military == null)
            return "starting";
        return economy.debugStatus() + " " + military.debugStatus();
    }
}
