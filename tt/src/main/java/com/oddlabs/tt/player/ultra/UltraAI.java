package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.util.StateChecksum;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The strongest skirmish AI. It runs inside the lockstep simulation on every peer, so everything it does is
 * deterministic: game-time scheduling, its own seeded random numbers, and only insertion-ordered iteration.
 * <p>
 * Modules: {@link EconomyManager} (peons, gathering, production), {@link BuildManager} (construction),
 * {@link MilitaryManager} (defense, attacks, micro), {@link TowerManager}, {@link ChieftainManager}; shared
 * state lives in {@link Context}. Parameters come from a {@link Strategy} and can be overridden with a spec string.
 */
public final class UltraAI extends AI {
    private interface Task {
        void run();
    }

    private final @NonNull Context ctx;
    private final String @NonNull [] task_names = {"sense", "micro", "econ", "threat", "posture", "plan", "garrison"};
    private final float @NonNull [] periods = {0.25f, 0.25f, 0.5f, 0.5f, 1.0f, 2.0f, 1.0f};
    private final float @NonNull [] next = {0f, 0.01f, 0.12f, 0.2f, 0.4f, 1.1f, 0.7f};
    private final Task @NonNull [] tasks;
    private boolean started;

    public UltraAI(@NonNull Player owner, @Nullable UnitInfo unit_info) {
        this(owner, unit_info, null);
    }

    public UltraAI(@NonNull Player owner, @Nullable UnitInfo unit_info, @Nullable String params) {
        super(owner, unit_info);
        Params p = new Params(params);
        ctx = new Context(owner, p, Strategy.forMap(p, owner.getWorld().getMapSize()));
        ctx.resources = new ResourceTracker(ctx);
        ctx.build = new BuildManager(ctx);
        ctx.economy = new EconomyManager(ctx);
        ctx.military = new MilitaryManager(ctx);
        ctx.towers = new TowerManager(ctx);
        ctx.chief = new ChieftainManager(ctx);
        ctx.corps = new CorpsManager(ctx);
        ctx.creep = new CreepManager(ctx);
        ctx.proxy = new ProxyManager(ctx);
        tasks = new Task[]{this::sense, this::micro, this::economy, ctx.military::threatTick, this::posture, ctx.towers::planTick, ctx.towers::garrisonTick};
    }

    @Override
    public void animate(float t) {
        float dt = getOwner().getWorld().getSecondsPerTick() * t / AnimationManager.ANIMATION_SECONDS_PER_TICK;
        if (dt <= 0f || !Globals.run_ai)
            return;
        ctx.now += dt;
        if (!started) {
            started = true;
            guarded("start", this::start);
        }
        for (int i = 0; i < tasks.length; i++) {
            if (ctx.now >= next[i]) {
                next[i] += periods[i];
                if (next[i] < ctx.now)
                    next[i] = ctx.now + periods[i];
                guarded(task_names[i], tasks[i]);
            }
        }
    }

    private void start() {
        // Enemy players that are really in the game (they have units on the map) decide the multi-enemy defaults.
        int enemies = 0;
        for (Player pl : getOwner().getWorld().getPlayers()) {
            if (pl != getOwner() && getOwner().isEnemy(pl) && !pl.getUnits().getSet().isEmpty())
                enemies++;
        }
        ctx.strategy.setEnemyCount(enemies);
        if (ctx.strategy.multiEnemy()) {
            ctx.economy.readMultiParams();
            ctx.military.readMultiParams();
            int n = enemies;
            ctx.log(() -> "multi-enemy defaults on: " + n + " enemy players");
        }
        ctx.model.update();
        ctx.resources.update();
        ctx.build.planOpening(false);
    }

    private void sense() {
        ctx.model.update();
        ctx.pruneRoles();
        ctx.orders.prune();
        ctx.build.prune();
        ctx.military.prune();
    }

    private void micro() {
        ctx.military.microTick();
        ctx.towers.microTick();
        ctx.chief.tick();
        ctx.corps.microTick();
    }

    private float next_census;

    private void posture() {
        if (UltraLog.enabled() && ctx.now >= next_census) {
            next_census = ctx.now + 30f;
            ctx.log(this::census);
        }
        ctx.military.postureTick();
        ctx.corps.recruitTick();
        ctx.creep.tick();
        ctx.proxy.tick();
    }

    /** Debug trace only: where every unit is and what it does. */
    private @NonNull String census() {
        WorldModel.Side me = ctx.model.me;
        int[] act = new int[WorldModel.Activity.values().length];
        int[] gather = new int[4];
        int[] roles = new int[Context.Role.values().length];
        for (Unit u : me.peons) {
            act[WorldModel.activityOf(u).ordinal()]++;
            if (WorldModel.primary(u) instanceof com.oddlabs.tt.model.behaviour.GatherController<?> gc) {
                Class<?> t = gc.getSupplyType();
                gather[t == com.oddlabs.tt.landscape.TreeSupply.class ? 0 : t == com.oddlabs.tt.model.IronSupply.class ? 1 : t == com.oddlabs.tt.model.RockSupply.class ? 2 : 3]++;
            }
            Context.Role r = ctx.roleOf(u);
            if (r != null)
                roles[r.ordinal()]++;
        }
        StringBuilder sb = new StringBuilder("census units=").append(me.unit_count).append(" peons=").append(
                me.peons.size()).append(" inside=").append(me.peons_inside).append(" (armory ").append(
                        me.armory_workers).append(") warriors=").append(me.warriors.size()).append(" garr=").append(
                                me.garrisoned).append(" Q=").append(me.quarters.size()).append(" T=").append(
                                        me.towers.size()).append(" | act");
        for (WorldModel.Activity a : WorldModel.Activity.values()) {
            if (act[a.ordinal()] > 0)
                sb.append(' ').append(a.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(act[a.ordinal()]);
        }
        sb.append(" | gather w/i/r/o=").append(gather[0]).append('/').append(gather[1]).append('/').append(
                gather[2]).append('/').append(gather[3]).append(" | roles");
        for (Context.Role r : Context.Role.values())
            sb.append(' ').append(r.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(roles[r.ordinal()]);
        sb.append(" | econ ").append(ctx.economy.phase()).append(" wood=").append(ctx.build.woodDemand()).append(
                " weapons r/i/u=").append(me.weapons_rock).append('/').append(me.weapons_iron).append('/').append(
                        me.weapons_rubber).append(" harvested w=").append(ctx.owner.getTreeHarvested());
        Unit chief = ctx.owner.getChieftain();
        if (chief != null && !chief.isDead()) {
            sb.append(" | chief ").append(chief.getGridX()).append(',').append(chief.getGridY()).append(" hp=").append(
                    chief.getHitPoints()).append(" stun=").append(String.format(java.util.Locale.ROOT, "%.2f",
                            chief.getMagicProgress(com.oddlabs.tt.model.RacesResources.INDEX_MAGIC_STUN)));
        } else {
            sb.append(" | chief none training=").append(ctx.owner.isTrainingChieftain());
        }
        return sb.toString();
    }

    private void economy() {
        ctx.economy.updateArmory();
        ctx.resources.update();
        ctx.build.tick(periods[2]);
        ctx.economy.tick();
    }

    private void guarded(@NonNull String name, @NonNull Task task) {
        // Timing is read only for the optional debug trace and never influences a decision.
        long start = UltraLog.enabled() ? System.nanoTime() : 0L;
        try {
            task.run();
            if (UltraLog.enabled()) {
                long micros = (System.nanoTime() - start) / 1000;
                if (micros >= 15_000)
                    ctx.log(() -> "SLOW " + name + " " + micros / 1000 + " ms");
            }
        } catch (RuntimeException | AssertionError e) {
            ctx.error_count++;
            ctx.log(() -> {
                StringBuilder sb = new StringBuilder("ERROR in " + name + ": " + e);
                StackTraceElement[] st = e.getStackTrace();
                for (int i = 0; i < Math.min(8, st.length); i++)
                    sb.append("\n    at ").append(st[i]);
                return sb.toString();
            });
            if (UltraLog.isStrict())
                throw e;
        }
    }

    /** Number of exceptions swallowed so far (the harness treats any as a bug). */
    public int getErrorCount() {
        return ctx.error_count;
    }

    @Override
    public void updateChecksum(@NonNull StateChecksum checksum) {
        checksum.update(Float.floatToIntBits(ctx.now));
        checksum.update(ctx.military.state().ordinal());
        checksum.update(ctx.military.armySize());
        checksum.update(ctx.error_count);
    }
}
