package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.AiParams;
import com.oddlabs.tt.animation.AnimationManager;
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

/**
 * Computer player built to beat many allied stock Hard AIs at once. It started as a port of the Expert AI (branch
 * expert-ai of the owner's tribaltrouble checkout, package player/ai): it lays out a base by resource value, balances
 * peons between reproduction, gathering and weapon making, keeps weapons flowing into a clumped army, defends with
 * towers and the chieftain's stun, and attacks when it has the numbers. lab/gauntlet/NOTES.md records what changed
 * since, and why.
 *
 * <p>The AI only iterates insertion-ordered collections, so every peer of a multiplayer game makes the same decisions.
 *
 * <p>It counts time in game ticks, {@link #TICKS_PER_SECOND} to a game second at every game speed ({@link #now}), so
 * that it plays the same at every speed: every time it keeps (parameters, constants, timestamps, durations, periods) is
 * in game ticks, and engine values in game seconds are converted where they are read ({@link #ticks}). A world tick
 * (one call of {@link #animate}, which runs on the real-time animation manager) covers {@link #tickStep} game ticks and
 * is never a measure of time.
 */
public final class GauntletAI extends AI {
    /** Game ticks in a game second, at every game speed: a game tick is 0.02 s of game time. */
    static final int TICKS_PER_SECOND = 50;
    private static final float INTEL_PERIOD_TICKS = 25f;
    private static final float ECONOMY_PERIOD_TICKS = 50f;
    private static final float PLAN_PERIOD_TICKS = 150f;
    private static final float SCAN_PERIOD_TICKS = 1500f;
    private static final float STAT_PERIOD_TICKS = 1500f;

    private final @NonNull AiLog log;
    private final @NonNull Strategy strategy;
    private final @NonNull Intel intel;
    private final @NonNull Reflexes reflexes;
    private @Nullable MapAnalysis map;
    private @Nullable SitePlanner planner;
    private @Nullable Economy economy;
    private @Nullable Military military;
    private @Nullable Chieftain chieftain;
    private @Nullable Decoys decoys;
    private @Nullable Shepherd shepherd;
    private @Nullable Lures lures;
    private @Nullable Dodges dodges;
    /** Traffic jams of our units: counters and log lines, and the blocked warriors that unjam acts on. */
    private @Nullable Jams jams;
    private @Nullable Freeze freeze;

    /** World ticks seen: a cache key and same-tick check only, never a time. */
    private int world_ticks;
    /**
     * Game ticks since the start: each world tick adds the game ticks it covers ({@link #tick_step}), so that every
     * period and timer of this AI counts game time at any game speed.
     */
    private double game_ticks;
    /** {@link #game_ticks} as a float, which holds it exactly (multiples of 0.25 for 23 game hours). */
    private float now;
    /** Game ticks the current world tick covers: 0.5 / 1 / 1.75 / 4 at slow / normal / fast / ludicrous, 0 paused. */
    private float tick_step;
    private float next_intel;
    private float next_economy = 12.5f;
    private float next_plan = 25f;
    private float next_scan = SCAN_PERIOD_TICKS;
    private float next_stat = STAT_PERIOD_TICKS;
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

    @Override
    public void animate(float t) {
        // the clock counts game time whether or not the AI runs
        world_ticks++;
        double before = game_ticks;
        tick_step = getOwner().getWorld().getSecondsPerTick() / AnimationManager.ANIMATION_SECONDS_PER_TICK;
        game_ticks += tick_step;
        now = (float) game_ticks;
        if (!Globals.run_ai)
            return;
        // the world's game-time pass of this tick in game seconds, as the units animate it (World.tick)
        float step = getOwner().getWorld().getSecondsPerTick() * t / AnimationManager.ANIMATION_SECONDS_PER_TICK;
        try {
            // weapon_sync looks at the armory before any order of this tick and plans after all of them
            if (initialized && strategy.weapon_sync)
                economy().weaponSyncObserve();
            reflexes.tick(step);
            if (initialized) {
                military().towerReflex();
                military().armyReflex();
            }
            // every 5 game ticks, or every world tick at a speed whose world ticks are longer
            if (initialized && Math.floor(game_ticks / 5) > Math.floor(before / 5)) {
                shepherd().guard();
                lures().guard();
                dodges().guard();
            }
            think();
            if (initialized && strategy.weapon_sync)
                economy().weaponSync(t);
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
        boolean due_intel = now >= next_intel;
        boolean due_economy = now >= next_economy;
        boolean due_plan = now >= next_plan;
        if (due_intel || due_economy || due_plan)
            intel.update();
        if (due_intel) {
            next_intel = nextRound(next_intel, INTEL_PERIOD_TICKS);
            military().tick();
            chieftain().tick();
            // last_stand: every unit is the military's
            if (!military().lastStand())
                shepherd().tick();
            if (jams != null)
                jams.tick();
        }
        if (due_economy) {
            next_economy = nextRound(next_economy, ECONOMY_PERIOD_TICKS);
            if (!military().lastStand()) {
                economy().tick();
                decoys().tick();
                freeze().tick();
            }
        }
        if (due_plan) {
            next_plan = nextRound(next_plan, PLAN_PERIOD_TICKS);
            if (!military().lastStand())
                economy().plan();
            military().plan();
        }
        if (logging && now >= next_stat) {
            next_stat = nextRound(next_stat, STAT_PERIOD_TICKS);
            log.log("STAT", debugStatus());
        }
        if (now >= next_scan) {
            next_scan = nextRound(next_scan, SCAN_PERIOD_TICKS);
            map().scanSupplies();
        }
        military().dodgeSpells();
    }

    /**
     * The game tick a round that was due at {@code due} and runs now is due next: a period after it was due, so that
     * the lateness of a round (up to a world tick, at a speed whose world ticks are longer than a game tick) does not
     * add up from round to round, but never earlier than a period after now less {@link #slack}, so that a round that
     * ran late for longer (the AI switched off, a speed change) does not run again at once. At slow and normal speed, a
     * period after now.
     */
    private float nextRound(float due, float period) {
        return Math.max(due + period, now + period - slack());
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
        // The freeze squad leaves before the economy hands out the starting peons.
        freeze = new Freeze(this);
        if (freeze.plan(start_field))
            intel.update();
        economy = new Economy(this);
        military = new Military(this);
        chieftain = new Chieftain(this);
        decoys = new Decoys(this);
        shepherd = new Shepherd(this);
        lures = new Lures(this);
        dodges = new Dodges(this);
        jams = new Jams(this);
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

    /** The mean start cell of the enemies still in the game (tower_face_live, tower_face_place), or null. */
    float @Nullable [] liveEnemyCenter() {
        long x = 0;
        long y = 0;
        int n = 0;
        for (Player p : getOwner().getWorld().getPlayers())
            if (getOwner().isEnemy(p) && p.isAlive()) {
                x += UnitGrid.toGridCoordinate(p.getStartX());
                y += UnitGrid.toGridCoordinate(p.getStartY());
                n++;
            }
        return n == 0 ? null : new float[]{x / (float) n, y / (float) n};
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

    /** Game ticks since the start, {@link #TICKS_PER_SECOND} to a game second at every game speed. */
    float now() {
        return now;
    }

    /** World ticks this AI has seen: for cache keys and same-tick checks only, never as a time. */
    int worldTicks() {
        return world_ticks;
    }

    /** Game ticks the current world tick covers: 0.5 / 1 / 1.75 / 4 at slow / normal / fast / ludicrous, 0 paused. */
    float tickStep() {
        return tick_step;
    }

    /**
     * Game ticks a check run in a round may come late beyond the normal tick: a whole world tick at a speed whose world
     * ticks are longer than a game tick, 0 at slow and normal speed (so that whatever uses it is unchanged there).
     */
    float slack() {
        return tick_step > 1f ? tick_step : 0f;
    }

    /**
     * Whether a periodic check that last ran at {@code last}, and re-arms on the tick it runs ({@code last = now()}),
     * is due again after {@code period} game ticks. It may run up to {@link #slack} early: otherwise each period would
     * round up to whole world ticks, and the error would add up (at ludicrous a 25-tick period ran every 28). One-shot
     * timeouts, which run once a time after an event, compare {@code now() - since >= limit} instead.
     */
    boolean periodDue(float last, float period) {
        return now - last >= period - slack();
    }

    /** Game ticks in {@code seconds} game seconds, for engine values in game seconds: convert once, where read. */
    static float ticks(float seconds) {
        return seconds * TICKS_PER_SECOND;
    }

    /** Game seconds in {@code ticks} game ticks, for log text only. */
    static float seconds(float ticks) {
        return ticks / TICKS_PER_SECOND;
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

    Lures lures() {
        assert lures != null;
        return lures;
    }

    Dodges dodges() {
        assert dodges != null;
        return dodges;
    }

    @NonNull
    Freeze freeze() {
        assert freeze != null;
        return freeze;
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
