package com.oddlabs.tt.player.ai;

import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Computer player built to beat strong humans: it lays out a base by resource value, balances peons between
 * reproduction, gathering and weapon making, keeps weapons flowing into a clumped army, defends with towers and the
 * chieftain's stun, and attacks when it has the numbers.
 *
 * <p>The AI thinks in game time on the game-time animation manager, and only iterates insertion-ordered collections,
 * so every peer of a multiplayer game makes the same decisions.
 */
public final class ExpertAI extends AI {
    private static final float INTEL_PERIOD = .5f;
    private static final float ECONOMY_PERIOD = 1f;
    private static final float PLAN_PERIOD = 3f;
    private static final float SCAN_PERIOD = 30f;

    private final @NonNull Strategy strategy;
    private final @NonNull Random random;
    private final @NonNull Intel intel;
    private @Nullable MapAnalysis map;
    private @Nullable SitePlanner planner;
    private @Nullable Economy economy;
    private @Nullable Military military;
    private @Nullable Chieftain chieftain;

    private static final Logger logger = Logger.getLogger(ExpertAI.class.getName());
    /** Set by the developer match runner so that mistakes stop the run instead of being logged. */
    public static boolean strict = false;

    private int errors;
    private float time;
    private float next_intel;
    private float next_economy = .25f;
    private float next_plan = .5f;
    private float next_scan = SCAN_PERIOD;
    private boolean initialized;
    private @Nullable GameLog game_log;
    private float next_report;
    private float next_flush;
    private boolean game_over;

    public ExpertAI(@NonNull Player owner, @Nullable UnitInfo unit_info) {
        this(owner, unit_info, Strategy.forGame(owner.getWorld().getMapSize(), countEnemies(owner)));
    }

    /** An expert AI with some strategy numbers changed, for the developer match runner. */
    public static @NonNull ExpertAI withOverrides(@NonNull Player owner, @Nullable UnitInfo unit_info,
            @NonNull String overrides) {
        Strategy strategy = Strategy.forGame(owner.getWorld().getMapSize(), countEnemies(owner));
        strategy.override(overrides);
        return new ExpertAI(owner, unit_info, strategy);
    }

    ExpertAI(@NonNull Player owner, @Nullable UnitInfo unit_info, @NonNull Strategy strategy) {
        super(owner, unit_info);
        World world = owner.getWorld();
        // Think in game time so that decisions keep pace with the simulation at any game speed.
        world.getAnimationManagerRealTime().removeAnimation(this);
        world.getAnimationManagerGameTime().registerAnimation(this);
        this.strategy = strategy;
        this.random = new Random(7919L * (1 + indexOf(owner)));
        this.intel = new Intel(owner);
    }

    /**
     * Keeps a log of this game in dir: every decision, and a report on every player each minute. For going over a
     * game against a human afterwards.
     */
    public void logTo(@NonNull Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot create " + dir + " for the expert AI log", e);
            return;
        }
        // One file per game: several games played in one session share the session's folder.
        String name = "expert-ai-" + getOwner().getPlayerInfo().getName().replaceAll("[^A-Za-z0-9_-]", "_");
        Path file = dir.resolve(name + ".log");
        for (int n = 2; Files.exists(file); n++)
            file = dir.resolve(name + "-" + n + ".log");
        game_log = new GameLog(file);
        game_log.add("Expert AI game log, started " + LocalDateTime.now(ZoneId.systemDefault()).withNano(0));
        logger.info("Expert AI log: " + file.toAbsolutePath());
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
        time += t;
        try {
            think();
        } catch (RuntimeException | AssertionError e) {
            // A mistake in the AI must not take the game down with it. Every peer hits the same mistake at the same
            // tick, so skipping the rest of this think keeps multiplayer games in step.
            if (strict)
                throw e;
            if (errors++ < 5)
                logger.log(Level.WARNING, "Expert AI error at " + time + "s", e);
            log("error: " + e);
        }
        if (game_log != null)
            keepLog(game_log);
    }

    private void keepLog(@NonNull GameLog log) {
        if (game_over)
            return;
        int enemies_alive = 0;
        for (Player p : getOwner().getWorld().getPlayers())
            if (getOwner().isEnemy(p) && p.isAlive())
                enemies_alive++;
        boolean over = !getOwner().isAlive() || enemies_alive == 0;
        if (time >= next_report || over) {
            next_report = time + 60f;
            log.add("---- " + GameLog.clock(time).trim() + (over ? " game over" : ""));
            for (Player p : getOwner().getWorld().getPlayers()) {
                if (p.getUnits().getSet().isEmpty() && !p.isAlive())
                    continue;
                log.add("  " + GameLog.describe(p));
                if (p == getOwner())
                    log.add("    plan: " + debugStatus());
            }
        }
        if (time >= next_flush || over) {
            next_flush = time + 3f;
            log.flush();
        }
        game_over = over;
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
        }
        if (due_economy) {
            next_economy = time + ECONOMY_PERIOD;
            economy().tick();
        }
        if (due_plan) {
            next_plan = time + PLAN_PERIOD;
            economy().plan();
            military().plan();
        }
        if (time >= next_scan) {
            next_scan = time + SCAN_PERIOD;
            map().scanSupplies();
        }
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
        log(String.format("map %d cells, start %d,%d, nearest enemy %s starts %d,%d (%dm walk)", map.getSize(), sx, sy,
                enemy, ex, ey, start_field.get(ex, ey)));
        intel.update();
        economy = new Economy(this);
        military = new Military(this);
        chieftain = new Chieftain(this);
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
    Chieftain chieftain() {
        assert chieftain != null;
        return chieftain;
    }

    /** When set, AIs print what they decide, for the developer match runner. */
    public static boolean debug = false;
    /** Grid cell of the latest battle an AI fought, for the developer match runner to take pictures of. */
    public static int @Nullable [] debug_battle;

    public static void debugSites(boolean on) {
        SitePlanner.debug_sites = on;
    }

    /** Whether decisions are being written anywhere, so that describing them is worth the work. */
    boolean logging() {
        return debug || game_log != null;
    }

    void log(@NonNull String message) {
        if (!logging())
            return;
        String line = "[" + GameLog.clock(time) + "] " + getOwner() + ": " + message;
        if (debug)
            System.out.println(line);
        if (game_log != null)
            game_log.add(line);
    }

    /** A one-line summary of the AI's state, for the developer match runner. */
    public @NonNull String debugStatus() {
        if (economy == null || military == null)
            return "starting";
        return economy.debugStatus() + " " + military.debugStatus();
    }
}
