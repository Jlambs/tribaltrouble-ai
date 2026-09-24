package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairBehaviour;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns construction: the task queue, site choice, laying foundations, builder assignment and re-tasking, and
 * placement verification. Builders carry the BUILDER role and an assigned task until released.
 */
final class BuildManager {
    enum State {
        PLANNED,
        ORDERED,
        PLACED,
        DONE,
        FAILED
    }

    static final class Task {
        final int type;
        int x;
        int y;
        int priority;
        final @NonNull String name;
        /** Wanted builders; -1 means every free peon (opening). */
        int desired;
        @Nullable
        Building building;
        State state = State.PLANNED;
        int tries;
        float state_time;
        /** Seconds since the placer was last seen heading to the site. */
        float placer_missing;
        /** Placed only by its owner module (e.g. tower creep), never by the nearest free peon. */
        boolean manual;
        /** Wood-carrying transporters deployed from the armory for this site and not yet claimed. */
        int drop_pending;
        float drop_time;
        /** The pending drop is plain peons (no wood): they chop at the site. */
        boolean drop_plain;
        /** Consecutive placement orders refused (site not legal right now). */
        int place_failures;
        /** Keep the full crew while no wood drop can go out (not just dropWaitBuilders). */
        boolean full_crew;

        Task(int type, int x, int y, int priority, int desired, @NonNull String name) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.priority = priority;
            this.desired = desired;
            this.name = name;
        }

        boolean open() {
            return state == State.PLANNED || state == State.ORDERED || state == State.PLACED;
        }

        int maxHp(@NonNull Context ctx) {
            return ctx.race.getBuildingTemplate(type).getMaxHitPoints();
        }

        @Override
        public @NonNull String toString() {
            return name + "@" + x + "," + y + ":" + state;
        }
    }

    private static final int LONG_WALK = 14;
    private static final int OPENING_BUILDER_CAP = 30;

    private final @NonNull Context ctx;
    private final List<Task> tasks = new ArrayList<>();
    private final Map<Unit, Task> assignment = new HashMap<>();
    private @Nullable Unit scout;
    private boolean opening = true;
    private final int foundation_lead;
    private final float j_walk;
    private final float j_danger_r;
    private final int hub_radius;
    private final int q1_radius;
    private final boolean wood_drop;
    /** Builders kept on a drop-eligible site while no drop can go out. */
    private final int drop_wait_builders;
    /** Builders on a site before its foundation is laid (0 = the task's full crew). */
    private final int unplaced_crew;
    /** Iron units (nearest nodes first) whose gathering cost the armory site score includes. */
    private final int armory_iron_units;
    private int hub_x = -1;
    private int hub_y = -1;

    BuildManager(@NonNull Context ctx) {
        this.ctx = ctx;
        this.foundation_lead = ctx.strategy.i("foundationLead");
        this.j_walk = ctx.strategy.f("jWalk");
        this.j_danger_r = ctx.strategy.f("jDangerR");
        this.hub_radius = ctx.strategy.i("hubRadius");
        this.q1_radius = ctx.strategy.i("q1Radius");
        this.wood_drop = ctx.strategy.b("woodDrop");
        this.drop_wait_builders = ctx.strategy.i("dropWaitBuilders");
        this.unplaced_crew = ctx.strategy.i("unplacedCrew");
        this.armory_iron_units = ctx.strategy.i("armoryIronUnits");
    }

    boolean inOpening() {
        return opening;
    }

    /** True until the opening plan has been laid out (the first think). */
    boolean inOpeningPlan() {
        return tasks.isEmpty() && hub_x < 0;
    }

    int hubX() {
        return hub_x;
    }

    int hubY() {
        return hub_y;
    }

    @NonNull
    List<Task> tasks() {
        return tasks;
    }

    @Nullable
    Task taskOf(@NonNull Unit u) {
        return assignment.get(u);
    }

    /** A live, complete building of the type from a finished task (seen before the world model catches up). */
    @Nullable
    Building completedBuilding(int type) {
        for (Task t : tasks) {
            Building b = t.building;
            if (t.type == type && t.state == State.DONE && b != null && !b.isDead() && b.isComplete())
                return b;
        }
        return null;
    }

    int openCount(int type) {
        int n = 0;
        for (Task t : tasks) {
            if (t.type == type && t.open())
                n++;
        }
        return n;
    }

    /** Placed-but-incomplete sites plus ordered foundations: each uses one of the 20 building slots. */
    int pendingSlots() {
        int n = 0;
        for (Task t : tasks) {
            if (t.state == State.PLANNED || t.state == State.ORDERED)
                n++;
        }
        return n;
    }

    @NonNull
    Task request(int type, int x, int y, int priority, int desired, @NonNull String name) {
        Task t = new Task(type, x, y, priority, desired, name);
        t.state_time = ctx.now;
        ctx.sites.reserve(x, y, ctx.race.getBuildingTemplate(type).getPlacingSize());
        int i = 0;
        while (i < tasks.size() && tasks.get(i).priority >= priority)
            i++;
        tasks.add(i, t);
        ctx.log(() -> "build request " + t);
        return t;
    }

    /** Moves an open task to a new priority, keeping the task list ordered. */
    void reprioritize(@NonNull Task t, int priority) {
        if (!tasks.remove(t))
            return;
        t.priority = priority;
        int i = 0;
        while (i < tasks.size() && tasks.get(i).priority >= priority)
            i++;
        tasks.add(i, t);
    }

    void cancel(@NonNull Task t) {
        if (!t.open())
            return;
        t.state = State.FAILED;
        ctx.sites.unreserve(t.x, t.y);
        releaseBuildersOf(t);
    }

    // -----------------------------------------------------------------------------------------------------
    // Opening plan

    /** Plans the opening sites (quarters line toward the armory hub) and lays the first foundation. */
    void planOpening(boolean threatened) {
        List<Unit> peons = ctx.model.me.peons;
        if (peons.isEmpty())
            return;
        ctx.map.refreshTrees(ctx.now, 0f);
        long sx = 0;
        long sy = 0;
        for (Unit u : peons) {
            sx += u.getGridX();
            sy += u.getGridY();
        }
        int cx = (int) (sx / peons.size());
        int cy = (int) (sy / peons.size());

        int[] hub = planArmorySite(ctx.map.home_x, ctx.map.home_y, hub_radius);
        if (hub == null)
            hub = new int[]{ctx.map.home_x, ctx.map.home_y};
        hub_x = hub[0];
        hub_y = hub[1];
        ctx.log(() -> "hub " + hub_x + "," + hub_y + " home " + ctx.map.home_x + "," + ctx.map.home_y);

        int[] order = ctx.strategy.openingOrder(threatened);
        int prev_x = cx;
        int prev_y = cy;
        int priority = 1000;
        int[] armory_site = null;
        int quarters_total = 0;
        for (int type : order) {
            if (type == Race.BUILDING_QUARTERS)
                quarters_total++;
        }
        int quarters_done = 0;
        for (int type : order) {
            if (type == Race.BUILDING_ARMORY) {
                armory_site = ctx.sites.isLegal(template(Race.BUILDING_ARMORY), hub_x,
                        hub_y) ? new int[]{hub_x, hub_y} : planArmorySite(hub_x, hub_y, 12);
                if (armory_site == null)
                    armory_site = planArmorySite(ctx.map.home_x, ctx.map.home_y, hub_radius);
                if (armory_site == null)
                    continue;
                request(Race.BUILDING_ARMORY, armory_site[0], armory_site[1], priority--, -1, "A");
                prev_x = armory_site[0];
                prev_y = armory_site[1];
            } else {
                boolean first = quarters_done == 0;
                // Quarters #2.. are searched around evenly spaced points on the line from Q1 to the hub, so the
                // line progresses toward the armory while each site can still pick the nearby tree line.
                int lx = prev_x;
                int ly = prev_y;
                if (!first) {
                    float f = 1f / (quarters_total - quarters_done + 1);
                    lx = Math.round(prev_x + (hub_x - prev_x) * f);
                    ly = Math.round(prev_y + (hub_y - prev_y) * f);
                }
                // The hub is reserved while the quarters line is planned so no quarters lands on it.
                ctx.sites.reserve(hub_x, hub_y, 7);
                int[] q = planQuartersSite(lx, ly, prev_x, prev_y, first ? q1_radius : 36);
                if (q == null)
                    q = planQuartersSite(lx, ly, prev_x, prev_y, 48);
                ctx.sites.unreserve(hub_x, hub_y);
                quarters_done++;
                if (q == null)
                    continue;
                request(type, q[0], q[1], priority--, -1, "Q" + (openCount(Race.BUILDING_QUARTERS) + 1));
                prev_x = q[0];
                prev_y = q[1];
            }
        }
        // Scout: the peon nearest the first site lays foundations ahead of the builders.
        Task first = firstOpenTask();
        if (first != null) {
            Unit best = null;
            int best_d = Integer.MAX_VALUE;
            for (Unit u : peons) {
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), first.x, first.y);
                if (d < best_d) {
                    best_d = d;
                    best = u;
                }
            }
            scout = best;
            if (scout != null)
                orderPlace(first, scout);
        }
    }

    private @Nullable Task firstOpenTask() {
        for (Task t : tasks) {
            if (t.open())
                return t;
        }
        return null;
    }

    // -----------------------------------------------------------------------------------------------------
    // Tick

    private float last_report;

    void tick(float dt) {
        if (UltraLog.enabled() && ctx.now - last_report >= 15f) {
            last_report = ctx.now;
            StringBuilder sb = new StringBuilder("tasks:");
            for (Task t : tasks) {
                if (!t.open())
                    continue;
                int hp = t.building != null && !t.building.isDead()
                        && t.building.isPlaced() ? t.building.getHitPoints() : 0;
                sb.append(' ').append(t.name).append('[').append(t.state.name().charAt(0)).append(" hp=").append(
                        hp).append(" b=").append(builderCount(t)).append('/').append(t.desired).append(" need=").append(
                                woodNeed(t)).append(']');
            }
            for (Task t : tasks) {
                if (!t.open() || t.building == null || t.state != State.PLACED || woodNeed(t) > 0)
                    continue;
                for (Unit u : ctx.model.me.peons) {
                    if (assignment.get(u) != t)
                        continue;
                    sb.append(System.lineSeparator()).append("      ").append(t.name).append(" builder ").append(
                            u.getGridX()).append(',').append(u.getGridY()).append(' ').append(WorldModel.activityOf(
                                    u)).append(" wood=").append(carriesWood(u)).append(" cur=").append(
                                            u.getCurrentController().getClass().getSimpleName()).append(" beh=").append(
                                                    u.getCurrentBehaviour() == null ? "-" : u.getCurrentBehaviour().getClass().getSimpleName());
                }
            }
            String line = sb.toString();
            ctx.log(() -> line);
        }
        for (Task t : tasks) {
            if (t.open())
                updateState(t, dt);
        }
        tasks.removeIf(t -> !t.open() && ctx.now - t.state_time > 30f);
        if (opening && ctx.economy.armory() != null)
            endOpening();
        if (opening) {
            safeSwitch();
            scoutTick();
        }
        if (!opening) {
            woodDrops();
            claimTransporters();
        }
        builderTick();
    }

    // -----------------------------------------------------------------------------------------------------
    // Wood drops: the armory deploys peons already carrying wood from its stock straight to a nearby site.
    // Wood gatherers on short trips pay for construction, instead of builders walking to distant trees.

    private static final int DROP_RANGE = 36;
    private static final int WOOD_KEEP = 6;

    /** A site close enough to the armory to be built from its wood stock. */
    boolean dropEligible(@NonNull Task t) {
        Building a = ctx.economy.armory();
        return wood_drop && a != null && !opening && t.desired >= 0
                && MapAnalysis.chebyshev(t.x, t.y, a.getGridX(), a.getGridY()) <= DROP_RANGE;
    }

    /** True while wood transporters deployed for a site have not all been claimed. */
    boolean dropPending() {
        for (Task t : tasks) {
            if (t.drop_pending > 0)
                return true;
        }
        return false;
    }

    /** Wood the armory should hold for pending drops (read by the economy's wood target). */
    int woodDemand() {
        int demand = 0;
        for (Task t : tasks) {
            if (t.open() && dropEligible(t))
                demand += Math.max(0, woodNeed(t) - t.drop_pending);
        }
        return Math.min(demand, 20);
    }

    private void woodDrops() {
        Building a = ctx.economy.armory();
        if (a == null || a.isDead() || !wood_drop)
            return;
        com.oddlabs.tt.model.DeployContainer c = a.getDeployContainer(
                com.oddlabs.tt.model.DeployType.PEON_TRANSPORT_TREE);
        if (c == null || c.getNumSupplies() > 0)
            return;
        for (Task t : tasks) {
            if (t.drop_pending > 0 && ctx.now - t.drop_time > 25f)
                t.drop_pending = 0;
        }
        // One drop at a time: claimTransporters hands carriers to the first pending task.
        if (dropPending())
            return;
        for (Task t : tasks) {
            if (!t.open() || !dropEligible(t) || t.building == null || t.drop_pending > 0)
                continue;
            if (t.state != State.PLACED)
                continue;
            if (ctx.resources.threatened(t.x, t.y, 14))
                continue;
            int need = woodNeed(t);
            if (need <= 0)
                continue;
            int stock = a.getSupplyContainer(TreeSupply.class).getNumSupplies() - WOOD_KEEP;
            int inside = a.getUnitContainer().getNumSupplies() - 2;
            int n = Math.min(need, Math.min(stock, inside));
            // Only drop when it finishes the site or makes real progress; small drops waste deploy time.
            if (n <= 0 || (n < need && n < 8))
                continue;
            ctx.orders.deploy(a, com.oddlabs.tt.model.DeployType.PEON_TRANSPORT_TREE, n);
            t.drop_pending = n;
            t.drop_time = ctx.now;
            int count = n;
            ctx.log(() -> "wood drop " + count + " -> " + t);
            return;
        }
    }

    /** New transporters (wood in hand, no job, next to the armory) become builders of the site they were sent for. */
    private void claimTransporters() {
        Building a = ctx.economy.armory();
        if (a == null)
            return;
        boolean any = false;
        for (Task t : tasks) {
            if (t.drop_pending > 0) {
                any = true;
                break;
            }
        }
        if (!any)
            return;
        int[] rally = ctx.military.rallyPoint();
        for (Unit u : ctx.model.me.peons) {
            if (u.isDead() || ctx.hasRole(u))
                continue;
            boolean wood = carriesWood(u);
            if (!wood && carriesAnything(u))
                continue;
            // Deployed units walk to the armory's rally point.
            boolean near = MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), a.getGridX(), a.getGridY()) <= 12
                    || (rally != null && MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), rally[0], rally[1]) <= 6);
            if (!near)
                continue;
            WorldModel.Activity act = WorldModel.activityOf(u);
            if (act != WorldModel.Activity.WALK && act != WorldModel.Activity.IDLE)
                continue;
            // A plain peon is only a fresh deploy if we never ordered it.
            if (!wood && ctx.orders.lastOrder(u) != null)
                continue;
            Task best = null;
            for (Task t : tasks) {
                if (t.drop_pending > 0 && t.open() && (t.building != null || t.manual) && (wood || t.drop_plain)) {
                    best = t;
                    break;
                }
            }
            if (best == null)
                continue;
            Building bb = best.building;
            if (bb == null || bb.isDead() || !bb.isPlaced() || best.state == State.PLANNED) {
                // Placement refused this tick: keep the transporter for the next try.
                if (!placeWith(best, u))
                    break;
                best.drop_pending--;
            } else {
                best.drop_pending--;
                ctx.setRole(u, Context.Role.BUILDER);
                assignment.put(u, best);
                ctx.orders.build(u, best.building);
            }
        }
    }

    private boolean safe_mode;

    /**
     * SAFE opening: once the enemy fields warriors (or could deploy them) while our armory is not up, the
     * armory jumps ahead of the remaining quarters so we can make warriors in time.
     */
    private void safeSwitch() {
        if (safe_mode || !ctx.strategy.b("safeSwitch"))
            return;
        WorldModel.Side e = ctx.model.enemy;
        // Visible signals only: warriors in the field, or an armory already standing next to fewer than three
        // quarters (a rush build).
        float threat = CombatModel.sumWeights(e.warriors);
        if (!e.armories.isEmpty() && e.quarters.size() < 3)
            threat += 2.5f;
        if (threat < 2.5f)
            return;
        Task armory = null;
        int top = Integer.MIN_VALUE;
        for (Task t : tasks) {
            if (!t.open())
                continue;
            if (t.type == Race.BUILDING_ARMORY && armory == null)
                armory = t;
            else if (t.type == Race.BUILDING_QUARTERS && t.desired < 0 && (t.building == null || !t.building.isPlaced()
                    || t.building.getHitPoints() < 120))
                top = Math.max(top, t.priority);
        }
        safe_mode = true;
        if (armory == null || top == Integer.MIN_VALUE || armory.priority > top)
            return;
        armory.priority = top + 1;
        tasks.remove(armory);
        int i = 0;
        while (i < tasks.size() && tasks.get(i).priority >= armory.priority)
            i++;
        tasks.add(i, armory);
        float th = threat;
        ctx.log(() -> String.format("SAFE opening: enemy threat %.1f, armory promoted", th));
    }

    private void endOpening() {
        opening = false;
        if (scout != null && ctx.roleOf(scout) == Context.Role.BUILDER && assignment.get(scout) == null)
            ctx.clearRole(scout);
        ctx.log(() -> "opening done");
    }

    private void updateState(@NonNull Task t, float dt) {
        Building b = t.building;
        switch (t.state) {
            case PLANNED -> {
                if (b != null && !b.isDead() && b.isPlaced()) {
                    // Placed by builders assigned while the task had fallen back to PLANNED.
                    setState(t, State.PLACED);
                    break;
                }
                // Laid by the scout during the opening, else by the nearest assigned builder.
                if (!t.manual && (!opening || t.type == Race.BUILDING_TOWER))
                    placeWithBuilders(t);
            }
            case ORDERED -> {
                if (b == null) {
                    t.state = State.PLANNED;
                } else if (b.isDead()) {
                    replan(t);
                } else if (b.isPlaced()) {
                    setState(t, State.PLACED);
                } else if (placerOf(t) == null) {
                    t.placer_missing += dt;
                    if (t.placer_missing > 1.0f)
                        retryPlacement(t);
                } else {
                    t.placer_missing = 0f;
                }
            }
            case PLACED -> {
                if (b == null || b.isDead()) {
                    replan(t);
                } else if (b.isComplete()) {
                    setState(t, State.DONE);
                    ctx.sites.unreserve(t.x, t.y);
                    onComplete(t);
                }
            }
            default -> {
            }
        }
    }

    private void setState(@NonNull Task t, @NonNull State s) {
        t.state = s;
        t.state_time = ctx.now;
        ctx.log(() -> "task " + t);
    }

    private @Nullable Unit placerOf(@NonNull Task t) {
        for (Unit u : ctx.model.me.peons) {
            if (WorldModel.primary(u) instanceof PlaceBuildingController pc && pc.getBuilding() == t.building)
                return u;
        }
        return null;
    }

    private void retryPlacement(@NonNull Task t) {
        t.tries++;
        t.placer_missing = 0f;
        if (t.tries > 4) {
            replan(t);
            return;
        }
        BuildingTemplate tpl = template(t.type);
        ctx.sites.unreserve(t.x, t.y);
        boolean legal = ctx.sites.isLegal(tpl, t.x, t.y);
        ctx.sites.reserve(t.x, t.y, tpl.getPlacingSize());
        if (!legal) {
            replan(t);
            return;
        }
        if (!t.manual && ctx.resources.threatened(t.x, t.y, 14)) {
            // Under attack: wait (PLANNED) instead of sending the next peon into the raid.
            t.state = State.PLANNED;
            t.placer_missing = 0f;
            return;
        }
        // Remote (manual) sites are only laid by their own crew; the owning module re-staffs or cancels them.
        Unit placer = t.manual ? nearestAssigned(t) : nearestBuilderOrFree(t);
        if (placer != null) {
            orderPlace(t, placer);
        } else {
            t.state = State.PLANNED;
            t.placer_missing = 0f;
        }
    }

    private @Nullable Unit nearestAssigned(@NonNull Task t) {
        Unit best = null;
        int best_d = Integer.MAX_VALUE;
        for (Unit u : ctx.model.me.peons) {
            if (assignment.get(u) != t || !ctx.orders.canOrder(u))
                continue;
            int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), t.x, t.y);
            if (d < best_d) {
                best_d = d;
                best = u;
            }
        }
        return best;
    }

    /** Finds a new site near the old one. */
    private void replan(@NonNull Task t) {
        ctx.sites.unreserve(t.x, t.y);
        t.building = null;
        int[] site = switch (t.type) {
            case Race.BUILDING_ARMORY -> {
                int[] a = planArmorySite(t.x, t.y, 16);
                if (a == null)
                    a = planArmorySite(t.x, t.y, 40);
                if (a == null)
                    a = planArmorySite(ctx.map.home_x, ctx.map.home_y, hub_radius);
                yield a;
            }
            case Race.BUILDING_QUARTERS -> planQuartersSite(t.x, t.y, t.x, t.y, 16);
            default -> ctx.towers.replanSite(t);
        };
        if (site == null) {
            t.state = State.FAILED;
            t.state_time = ctx.now;
            releaseBuildersOf(t);
            ctx.log(() -> "task failed " + t);
            return;
        }
        t.x = site[0];
        t.y = site[1];
        t.tries = 0;
        ctx.sites.reserve(t.x, t.y, template(t.type).getPlacingSize());
        t.state = State.PLANNED;
        ctx.log(() -> "replanned " + t);
    }

    /**
     * Lays (or re-lays) the task's foundation with the placer, together with the helpers already assigned to it, as
     * a human does by placing with a multi-selection. Returns false if the placement was refused (site not legal
     * right now); a site refused many times in a row is replanned.
     */
    private boolean orderPlace(@NonNull Task t, @NonNull Unit placer) {
        Building old = t.building;
        if (old == null || old.isDead() || !old.isPlaced()) {
            List<Unit> crew = new ArrayList<>();
            crew.add(placer);
            for (Unit u : ctx.model.me.peons) {
                if (u != placer && assignment.get(u) == t && !u.isDead())
                    crew.add(u);
            }
            boolean orderable = false;
            for (Unit u : crew)
                orderable |= ctx.orders.canOrder(u);
            if (!orderable)
                return false;
            Building b = ctx.orders.place(crew, t.type, t.x, t.y);
            if (b == null) {
                if (++t.place_failures >= 8) {
                    t.place_failures = 0;
                    replan(t);
                }
                return false;
            }
            t.place_failures = 0;
            t.building = b;
        } else {
            ctx.orders.build(placer, old);
        }
        ctx.setRole(placer, Context.Role.BUILDER);
        assignment.put(placer, t);
        t.state = State.ORDERED;
        t.placer_missing = 0f;
        t.state_time = ctx.now;
        return true;
    }

    /**
     * Lays the task's foundation with a specific peon, or adds it as a builder if the foundation is already laid
     * (used by modules that bring their own builders). Returns false if nothing was ordered.
     */
    boolean placeWith(@NonNull Task t, @NonNull Unit placer) {
        Building b = t.building;
        boolean alive = b != null && !b.isDead();
        // Someone is already on the way to lay it: join as a helper instead of re-laying it.
        if (alive && (b.isPlaced() || placerOf(t) != null)) {
            assign(placer, t);
            return true;
        }
        return orderPlace(t, placer);
    }

    private void placeWithBuilders(@NonNull Task t) {
        if (!ctx.owner.canBuild(t.type) || ctx.resources.threatened(t.x, t.y, 14))
            return;
        Unit placer = nearestBuilderOrFree(t);
        if (placer != null)
            orderPlace(t, placer);
    }

    private @Nullable Unit nearestBuilderOrFree(@NonNull Task t) {
        Unit best = null;
        int best_d = Integer.MAX_VALUE;
        for (Unit u : ctx.model.me.peons) {
            boolean mine = assignment.get(u) == t;
            if ((!mine && !ctx.economy.isFree(u)) || !ctx.orders.canOrder(u))
                continue;
            int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), t.x, t.y) - (mine ? 400 : 0);
            if (d < best_d) {
                best_d = d;
                best = u;
            }
        }
        return best;
    }

    private void scoutTick() {
        if (scout == null || scout.isDead() || ctx.roleOf(scout) != Context.Role.BUILDER) {
            scout = null;
            Task next = null;
            for (Task t : tasks) {
                if (t.open() && t.state == State.PLANNED) {
                    next = t;
                    break;
                }
            }
            if (next == null)
                return;
            // A new scout: the nearest free peon or builder to the next foundation.
            Unit best = null;
            int best_d = Integer.MAX_VALUE;
            for (Unit u : ctx.model.me.peons) {
                if (u.isDead() || !ctx.orders.canOrder(u)
                        || !(ctx.economy.isFree(u) || ctx.roleOf(u) == Context.Role.BUILDER))
                    continue;
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), next.x, next.y);
                if (d < best_d) {
                    best_d = d;
                    best = u;
                }
            }
            if (best == null)
                return;
            scout = best;
        }
        int laid = 0;
        Task next = null;
        for (Task t : tasks) {
            if (!t.open())
                continue;
            if (t.state == State.ORDERED || (t.state == State.PLACED && t.building != null
                    && t.building.getHitPoints() < 5))
                laid++;
            if (t.state == State.PLANNED && next == null && !ctx.resources.threatened(t.x, t.y, 30))
                next = t;
        }
        Task scout_task = assignment.get(scout);
        boolean scout_busy = scout_task != null && scout_task.state == State.ORDERED;
        if (!scout_busy && next != null && laid < foundation_lead)
            orderPlace(next, scout);
    }

    // -----------------------------------------------------------------------------------------------------
    // Builders

    /** Builders currently hammering or fetching wood for the task's building. */
    int builderCount(@NonNull Task t) {
        int n = 0;
        for (Unit u : ctx.model.me.peons) {
            if (assignment.get(u) == t)
                n++;
        }
        return n;
    }

    /** Builders a task should recruit: only the placer when armory wood drops can pay for it. */
    private int wantedBuilders(@NonNull Task t) {
        if (t.desired < 0)
            return t.desired;
        if (dropEligible(t)) {
            if (t.state != State.PLACED)
                return 1;
            // Keep builders hammering unless a drop that finishes (or substantially advances) the site can go out
            // right now; never leave a placed site with nobody on it.
            return dropFeasible(t) || t.drop_pending > 0 ? 1 : Math.min(t.desired,
                    t.full_crew ? t.desired : drop_wait_builders);
        }
        return t.desired;
    }

    /** True if woodDrops would send a useful drop to this site now. */
    private boolean dropFeasible(@NonNull Task t) {
        Building a = ctx.economy.armory();
        if (a == null || a.isDead())
            return false;
        int need = woodNeed(t);
        int stock = a.getSupplyContainer(TreeSupply.class).getNumSupplies() - WOOD_KEEP;
        int inside = a.getUnitContainer().getNumSupplies() - 2;
        int n = Math.min(need, Math.min(stock, inside));
        return n > 0 && (n >= need || n >= 8);
    }

    /** The highest-priority site that wants more builders now, or null. */
    @Nullable
    Task neediest() {
        Task fallback = null;
        for (Task t : tasks) {
            if (!t.open() || t.building == null)
                continue;
            if (ctx.resources.threatened(t.x, t.y, 14))
                continue;
            if (t.desired < 0) {
                // More builders than the wood still missing only jams the paths around the site.
                int cap = Math.min(OPENING_BUILDER_CAP, woodNeed(t) + 6);
                if (builderCount(t) >= cap)
                    continue;
                if (t.state == State.PLACED && woodNeed(t) > 0)
                    return t;
                if (fallback == null)
                    fallback = t;
                continue;
            }
            if (t.state == State.PLACED && woodNeed(t) <= 0)
                continue;
            // A foundation not laid yet only needs a small crew; the rest join once it is placed.
            if (unplaced_crew > 0 && t.state != State.PLACED && builderCount(t) >= unplaced_crew)
                continue;
            if (builderCount(t) < wantedBuilders(t))
                return t;
        }
        // Opening sites that have enough wood in flight still take builders if nothing else needs them.
        return fallback;
    }

    /** Assigns a free peon to the task (the caller checked it is free). */
    void assign(@NonNull Unit u, @NonNull Task t) {
        ctx.setRole(u, Context.Role.BUILDER);
        assignment.put(u, t);
        issueBuild(u, t);
    }

    private void issueBuild(@NonNull Unit u, @NonNull Task t) {
        Building b = t.building;
        if (b == null || b.isDead())
            return;
        int d = MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), t.x, t.y);
        if (d > LONG_WALK && !carriesWood(u) && b.isPlaced()) {
            // Walk empty to the site first so the builder chops the site's trees, not trees near itself.
            int[] step = towards(u.getGridX(), u.getGridY(), t.x, t.y, 6);
            ctx.orders.move(u, step[0], step[1]);
        } else {
            ctx.orders.build(u, b);
        }
    }

    static boolean carriesWood(@NonNull Unit u) {
        return u.getSupplyContainer() != null && u.getSupplyContainer().getNumSupplies() > 0
                && u.getSupplyContainer().getSupplyType() == TreeSupply.class;
    }

    static boolean carriesAnything(@NonNull Unit u) {
        return u.getSupplyContainer() != null && u.getSupplyContainer().getNumSupplies() > 0;
    }

    /** Wood still missing after the wood already in builders' hands (and mid-hammer) is used. */
    int woodNeed(@NonNull Task t) {
        Building b = t.building;
        if (b == null || b.isDead())
            return 0;
        int hp = b.isPlaced() ? b.getHitPoints() : 0;
        int need = (t.maxHp(ctx) - hp + 4) / 5;
        for (Unit u : ctx.model.me.peons) {
            if (assignment.get(u) != t)
                continue;
            if (carriesWood(u) || u.getCurrentBehaviour() instanceof RepairBehaviour)
                need--;
        }
        return need;
    }

    /** Per builder: {carried amount, time it last changed}. */
    private final Map<Unit, float[]> builder_probe = new java.util.LinkedHashMap<>();
    private float last_builder_probe = Float.NEGATIVE_INFINITY;

    /**
     * A builder whose load has not changed for a long time while not hammering is stuck on the engine's own tree
     * search (it keeps walking to an unreachable tree). It is sent to a tree we pick near its site instead.
     */
    private void unstickBuilders() {
        if (ctx.now - last_builder_probe < 5f)
            return;
        last_builder_probe = ctx.now;
        builder_probe.keySet().removeIf(u -> u.isDead() || ctx.roleOf(u) != Context.Role.BUILDER);
        for (Unit u : ctx.model.me.peons) {
            if (ctx.roleOf(u) != Context.Role.BUILDER || u == scout)
                continue;
            Task t = assignment.get(u);
            if (t == null || t.state != State.PLACED)
                continue;
            int carry = u.getSupplyContainer().getNumSupplies();
            float[] p = builder_probe.get(u);
            if (p == null || p[0] != carry || u.getCurrentBehaviour() instanceof RepairBehaviour) {
                builder_probe.put(u, new float[]{carry, ctx.now});
                continue;
            }
            if (ctx.now - p[1] < EconomyManager.STUCK_TIME)
                continue;
            builder_probe.remove(u);
            ctx.resources.markBad(u.getGridX(), u.getGridY());
            TreeSupply tree = ctx.resources.bestTree(t.x, t.y, 3, 24, ResourceTracker.TREE_CAP);
            ctx.log(() -> "stuck builder at " + u.getGridX() + "," + u.getGridY() + " for " + t);
            if (tree != null) {
                ctx.orders.gather(u, tree);
                ctx.resources.noteAssigned(tree);
            } else {
                release(u);
                ctx.orders.stop(u);
            }
        }
    }

    private void builderTick() {
        unstickBuilders();
        List<Unit> peons = ctx.model.me.peons;
        for (Unit u : peons) {
            if (ctx.roleOf(u) != Context.Role.BUILDER)
                continue;
            Task t = assignment.get(u);
            if (t == null || !t.open() || t.building == null || t.building.isDead()) {
                if (t != null && t.state == State.PLANNED && u == scout)
                    continue;
                release(u);
                continue;
            }
            if (u == scout && t.state == State.ORDERED)
                continue;
            Building target = WorldModel.buildTarget(u);
            if (target == t.building)
                continue;
            WorldModel.Activity a = WorldModel.activityOf(u);
            if (a == WorldModel.Activity.WALK && ctx.orders.recently(u, 8f)) {
                if (MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), t.x, t.y) > 8)
                    continue;
            }
            if (ctx.orders.recently(u, Orders.Kind.BUILD, t.building, t.x, t.y, 1.5f))
                continue;
            if (!t.building.isPlaced() && ctx.orders.recently(u, 4f))
                continue;
            // A builder we sent to a specific tree keeps chopping until it carries wood.
            Orders.Last last = ctx.orders.lastOrder(u);
            if (last != null && last.kind() == Orders.Kind.GATHER && ctx.now - last.time() < 60f && !carriesWood(u)
                    && WorldModel.primary(u) instanceof com.oddlabs.tt.model.behaviour.GatherController<?>)
                continue;
            issueBuild(u, t);
        }
        handoff();
    }

    /**
     * When a site has enough wood in flight to finish, its empty-handed builders move on to the next open
     * site, so nobody chops wood that will never be used.
     */
    private void handoff() {
        Task current = null;
        Task next = null;
        for (Task t : tasks) {
            // Remote sites (creep, proxy) bring their own crew; their builders must never be sent home.
            if (!t.open() || t.building == null || t.manual)
                continue;
            if (current == null && t.state == State.PLACED)
                current = t;
            else if (current != null && next == null)
                next = t;
        }
        if (current == null)
            return;
        if (next == null) {
            for (Task t : tasks) {
                if (t != current && t.open() && t.state == State.PLANNED) {
                    next = t;
                    break;
                }
            }
        }
        int need = woodNeed(current);
        if (need > 0)
            return;
        for (Unit u : ctx.model.me.peons) {
            if (assignment.get(u) != current || carriesWood(u)
                    || u.getCurrentBehaviour() instanceof RepairBehaviour || u == scout)
                continue;
            if (next != null && next.building != null && next.desired < 0) {
                assignment.put(u, next);
                issueBuild(u, next);
            } else if (current.desired >= 0 || next == null) {
                release(u);
                Building a = ctx.economy.armory();
                if (a != null)
                    ctx.orders.enter(u, a);
                else
                    ctx.orders.stop(u);
            }
        }
    }

    private void onComplete(@NonNull Task t) {
        Building b = t.building;
        List<Unit> builders = new ArrayList<>();
        for (Unit u : ctx.model.me.peons) {
            if (assignment.get(u) == t)
                builders.add(u);
        }
        int keep = 0;
        if (b != null && t.type == Race.BUILDING_QUARTERS)
            keep = ctx.economy.nKeep();
        // Empty-handed builders nearest the quarters go inside to breed; carried wood would be lost there.
        builders.sort((a, c) -> {
            int da = MapAnalysis.dist2(a.getGridX(), a.getGridY(), t.x, t.y);
            int dc = MapAnalysis.dist2(c.getGridX(), c.getGridY(), t.x, t.y);
            return Integer.compare(da, dc);
        });
        Task next = null;
        for (Task o : tasks) {
            if (o != t && o.open() && o.desired < 0) {
                next = o;
                break;
            }
        }
        for (Unit u : builders) {
            if (keep > 0 && !carriesAnything(u) && b != null) {
                release(u);
                ctx.orders.enter(u, b);
                keep--;
            } else if (next != null && next.building != null) {
                assignment.put(u, next);
                issueBuild(u, next);
            } else {
                release(u);
            }
        }
    }

    void release(@NonNull Unit u) {
        assignment.remove(u);
        if (ctx.roleOf(u) == Context.Role.BUILDER)
            ctx.clearRole(u);
        if (u == scout && !opening)
            scout = null;
    }

    private void releaseBuildersOf(@NonNull Task t) {
        List<Unit> list = new ArrayList<>();
        for (Unit u : ctx.model.me.peons) {
            if (assignment.get(u) == t)
                list.add(u);
        }
        for (Unit u : list)
            release(u);
    }

    void prune() {
        assignment.keySet().removeIf(Unit::isDead);
    }

    // -----------------------------------------------------------------------------------------------------
    // Site scoring (lower cost is better; SiteFinder maximises, so scorers return -cost)

    @NonNull
    BuildingTemplate template(int type) {
        return ctx.race.getBuildingTemplate(type);
    }

    /** Labour cost per warrior of an armory at a cell (economy design J_A). */
    float armoryCost(int x, int y) {
        int home = ctx.map.homeDist(x, y);
        if (home == MapAnalysis.UNREACHABLE)
            return Float.POSITIVE_INFINITY;
        float cw = 10.2f + 0.45f * ctx.resources.treeWalk(x, y, 20, 5);
        float ci = nodeCost(ctx.resources.iron(), x, y, armory_iron_units, null);
        float cr = nodeCost(ctx.resources.rock(), x, y, 20, null);
        float danger = 400f * Math.max(0f, ctx.map.frontness(x, y) - j_danger_r);
        float build = 2f * (15.3f + 0.45f * ctx.resources.treeWalk(x, y, 8, 5)) / 60f;
        return 80f + 2f * cw + ci + 0.1f * cr + j_walk * home + danger + build;
    }

    /**
     * Mean seconds per unit over the nearest nodes holding the first `units` units; distances are straight-line,
     * or walking distances when a field computed from (x,y) up to PROBE_MAX is given.
     */
    private float nodeCost(@NonNull List<? extends Supply> nodes, int x, int y, int units, int @Nullable [] field) {
        final int k = Math.max(10, units / 5);
        int[] d = new int[k];
        int[] amount = new int[k];
        int n = 0;
        for (Supply s : nodes) {
            if (s.isDead())
                continue;
            int dist = field == null ? ResourceTracker.dropDistance(x, y, s.getGridX(),
                    s.getGridY()) : ctx.resources.walkFrom(field, PROBE_MAX, x, y, s.getGridX(), s.getGridY());
            if (n == k && dist >= d[k - 1])
                continue;
            int pos = n < k ? n : k - 1;
            while (pos > 0 && d[pos - 1] > dist) {
                if (pos < k) {
                    d[pos] = d[pos - 1];
                    amount[pos] = amount[pos - 1];
                }
                pos--;
            }
            d[pos] = dist;
            amount[pos] = ResourceTracker.remaining(s);
            if (n < k)
                n++;
        }
        float sum = 0f;
        int got = 0;
        for (int i = 0; i < n && got < units; i++) {
            int take = Math.min(amount[i], units - got);
            sum += take * (10.2f + 0.45f * d[i]);
            got += take;
        }
        if (got < units)
            sum += (units - got) * (10.2f + 0.45f * 300f);
        return sum / units;
    }

    int @Nullable [] planArmorySite(int cx, int cy, int radius) {
        ctx.map.refreshTrees(ctx.now, 10f);
        SiteFinder.Scorer scorer = (x, y) -> -armoryCost(x, y);
        // Finalists are re-ranked by walking distances: a site whose trees or ore sit behind a cliff looks great
        // on the straight-line estimate and starves the armory.
        SiteFinder.Scorer refine = ctx.strategy.b("pathAware") ? (x, y) -> -armoryCostPath(x, y) : scorer;
        int[] site = radius > 30 ? ctx.sites.bestCoarse(template(Race.BUILDING_ARMORY), cx, cy, radius, 3, scorer,
                refine) : ctx.sites.bestRefined(template(Race.BUILDING_ARMORY), cx, cy, radius, scorer, refine, 8);
        if (site != null && UltraLog.enabled()) {
            int[] s = site;
            ctx.log(() -> "armory site " + s[0] + "," + s[1] + " " + explainArmory(s[0],
                    s[1]) + " | home " + explainArmory(ctx.map.home_x, ctx.map.home_y));
        }
        return site;
    }

    private @NonNull String explainArmory(int x, int y) {
        float cw = 10.2f + 0.45f * ctx.resources.treeWalk(x, y, 20, 5);
        float ci = nodeCost(ctx.resources.iron(), x, y, armory_iron_units, null);
        float cr = nodeCost(ctx.resources.rock(), x, y, 20, null);
        return String.format("J=%.0f Jpath=%.0f wood=%.0f iron=%.0f rock=%.0f walk=%d R=%.2f", armoryCost(x, y),
                armoryCostPath(x, y), cw, ci, cr, ctx.map.homeDist(x, y), ctx.map.frontness(x, y));
    }

    private static final int PROBE_MAX = 2 * 120;
    private int @Nullable [] probe_field;

    /** armoryCost with every tree and node distance measured along walkable terrain from the site. */
    /** The iron term of armoryCostPath alone: gatherer trips to the iron nodes an armory here would use. */
    float armoryIronCost(int x, int y) {
        if (ctx.map.homeDist(x, y) == MapAnalysis.UNREACHABLE)
            return Float.POSITIVE_INFINITY;
        int[] f = ctx.map.distanceField(x, y, PROBE_MAX, probe_field);
        probe_field = f;
        return nodeCost(ctx.resources.iron(), x, y, armory_iron_units, f);
    }

    float armoryCostPath(int x, int y) {
        int home = ctx.map.homeDist(x, y);
        if (home == MapAnalysis.UNREACHABLE)
            return Float.POSITIVE_INFINITY;
        int[] f = ctx.map.distanceField(x, y, PROBE_MAX, probe_field);
        probe_field = f;
        float cw = 10.2f + 0.45f * ctx.resources.treeWalkPath(f, x, y, 20, 40);
        float ci = nodeCost(ctx.resources.iron(), x, y, armory_iron_units, f);
        float cr = nodeCost(ctx.resources.rock(), x, y, 20, f);
        float danger = 400f * Math.max(0f, ctx.map.frontness(x, y) - j_danger_r);
        float build = 2f * (15.3f + 0.45f * ctx.resources.treeWalkPath(f, x, y, 8, 40)) / 60f;
        return 80f + 2f * cw + ci + 0.1f * cr + j_walk * home + danger + build;
    }

    /**
     * Quarters cost (economy design J_Q): walk of the team from (fx,fy), construction time from tree access,
     * distance to the armory hub, progress toward the hub, danger, and not sitting on harvest cells.
     */
    float quartersCost(int x, int y, int fx, int fy) {
        return quartersCost(x, y, fx, fy, ctx.resources.treeWalk(x, y, 12, 5));
    }

    private static final int QUARTERS_PROBE_MAX = 2 * 45;

    /** quartersCost with the builders' tree walk measured along walkable terrain. */
    float quartersCostPath(int x, int y, int fx, int fy) {
        if (!ctx.strategy.b("pathAware"))
            return quartersCost(x, y, fx, fy);
        int[] f = ctx.map.distanceField(x, y, QUARTERS_PROBE_MAX, probe_field);
        probe_field = f;
        return quartersCost(x, y, fx, fy, ctx.resources.treeWalkPath(f, x, y, 12, 20));
    }

    private float quartersCost(int x, int y, int fx, int fy, float tree_walk) {
        if (ctx.map.homeDist(x, y) == MapAnalysis.UNREACHABLE)
            return Float.POSITIVE_INFINITY;
        float walk = MapAnalysis.octile(x - fx, y - fy) / 5f;
        float build = 1.5f * (40f / 18f) * (15.3f + 0.45f * tree_walk);
        int hx = hub_x >= 0 ? hub_x : ctx.map.home_x;
        int hy = hub_y >= 0 ? hub_y : ctx.map.home_y;
        int to_hub = MapAnalysis.octile(x - hx, y - hy);
        int prev_to_hub = MapAnalysis.octile(fx - hx, fy - hy);
        float hub = 2f * to_hub / 5f + Math.max(0, to_hub - prev_to_hub) / 5f;
        float danger = 300f * Math.max(0f, ctx.map.frontness(x, y) - 0.35f);
        int nodes = 0;
        for (IronSupply s : ctx.resources.iron()) {
            if (MapAnalysis.chebyshev(s.getGridX(), s.getGridY(), x, y) <= 6)
                nodes++;
        }
        for (RockSupply s : ctx.resources.rock()) {
            if (MapAnalysis.chebyshev(s.getGridX(), s.getGridY(), x, y) <= 6)
                nodes++;
        }
        // Keep clear of the hub's own harvest ring and tower slots.
        int hub_gap = MapAnalysis.chebyshev(x, y, hx, hy);
        float crowd = hub_gap < 11 ? 40f : 0f;
        return walk + build + hub + danger + 15f * nodes + crowd;
    }

    /** Best quarters site within radius of (cx,cy) for a team walking from (fx,fy). */
    int @Nullable [] planQuartersSite(int cx, int cy, int fx, int fy, int radius) {
        ctx.map.refreshTrees(ctx.now, 10f);
        return ctx.sites.bestRefined(template(Race.BUILDING_QUARTERS), cx, cy, radius,
                (x, y) -> -quartersCost(x, y, fx, fy), (x, y) -> -quartersCostPath(x, y, fx, fy), 8);
    }

    /** Next boom quarters: near the armory, on the home side, tucked into trees. */
    int @Nullable [] planBoomQuartersSite(@NonNull Building armory) {
        ctx.map.refreshTrees(ctx.now, 10f);
        int ax = armory.getGridX();
        int ay = armory.getGridY();
        float front = ctx.map.frontness(ax, ay);
        return ctx.sites.bestRefined(template(Race.BUILDING_QUARTERS), ax, ay, 34, (x, y) -> {
            int gap = MapAnalysis.chebyshev(x, y, ax, ay);
            if (gap < 10)
                return Float.NEGATIVE_INFINITY;
            float c = quartersCost(x, y, ax, ay);
            c += 200f * Math.max(0f, ctx.map.frontness(x, y) - front);
            return -c;
        }, (x, y) -> -quartersCostPath(x, y, ax, ay) - 200f * Math.max(0f, ctx.map.frontness(x, y) - front), 8);
    }

    static int @NonNull [] towards(int x, int y, int tx, int ty, int stop_short) {
        int dx = tx - x;
        int dy = ty - y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= stop_short)
            return new int[]{x, y};
        float f = (len - stop_short) / len;
        return new int[]{x + Math.round(dx * f), y + Math.round(dy * f)};
    }

    boolean isLegalStatic(int type, int x, int y) {
        return LandBuilding.isPlacingLegal(ctx.grid, template(type), x, y);
    }
}
