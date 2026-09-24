package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.BuildProductionContainer;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The peon economy: how many peons breed in each quarters, work in the armory, gather each resource, and how the
 * armory's weapon production and warrior deployment are driven. Everything is re-derived from observed state each
 * tick (idempotent control), because deploy orders do not persist and the engine converts peons on its own.
 *
 * <p>The central formula: with N peons attached to the armory, tree distance d_w and iron distance d_i (metres),
 * the sustainable iron-warrior rate is R = N / (80 + 2c(d_w) + c(d_i)) with c(d) = 12 + 0.45 d peon-seconds per
 * resource unit; the armory then needs 80R workers, 2R c(d_w) wood gatherers and R c(d_i) iron gatherers.
 */
public final class Economy {
    public enum Mode {
        IRON,
        ROCK
    }

    /** What the military brain asks of the economy. */
    public static final class Demands {
        public boolean emergency;
        public boolean armory_threat;
        /** Nodes near which enemy warriors were seen; gatherers there are recalled. */
        public final List<int[]> hot_cells = new ArrayList<>();
        public int hot_radius = 20;
        public boolean want_rock_warriors;
        public boolean cap_assault;
    }

    private final @NonNull Player player;
    private final @NonNull Orders orders;
    private final @NonNull Jobs jobs;
    private final @NonNull ResourceMap resources;
    private final @NonNull BasePlan plan;
    private final @NonNull Params params;
    private final @NonNull AiLog log;
    public final @NonNull Demands demands = new Demands();

    public @NonNull Mode mode = Mode.IRON;
    // last computed targets (for status/debug)
    public float rate_star;
    public int workers_star;
    public int wood_star;
    public int iron_star;
    public int rock_star;
    public float d_wood_m;
    public float d_iron_m;
    private float last_conversion_wood = -100f;
    private float last_conversion_iron = -100f;
    private float last_conversion_rock = -100f;
    private float last_conversion_rubber = -100f;
    private float last_hunt_check = -100f;
    private final List<Unit> hunters = new ArrayList<>();
    /** Peons the military has taken (demolition corps): the allocator leaves them alone. */
    public final Set<Unit> reserved = new LinkedHashSet<>();
    private @Nullable LandBuilding rally_armory;
    private boolean cap_mode;
    private int quarters_rally_set_count = -1;
    public float bootstrap_until = -1f;
    private float starved_iron_since = -1f;

    public Economy(@NonNull Player player, @NonNull Orders orders, @NonNull Jobs jobs, @NonNull ResourceMap resources,
            @NonNull BasePlan plan, @NonNull Params params, @NonNull AiLog log) {
        this.player = player;
        this.orders = orders;
        this.jobs = jobs;
        this.resources = resources;
        this.plan = plan;
        this.params = params;
        this.log = log;
    }

    // ------------------------------------------------------------------ readings

    // All readers tolerate a building that died since the last snapshot (the container accessors assert !isDead()).

    public static int stock(@NonNull LandBuilding armory, @NonNull Class<?> key) {
        if (armory.isDead() || !armory.isComplete())
            return 0;
        var c = armory.getSupplyContainer(key);
        return c == null ? 0 : c.getNumSupplies();
    }

    public static int queued(@NonNull LandBuilding armory, @NonNull DeployType type) {
        if (armory.isDead() || !armory.isComplete())
            return 0;
        var c = armory.getDeployContainer(type);
        return c == null ? 0 : c.getNumSupplies();
    }

    public static int workers(@NonNull LandBuilding armory) {
        if (armory.isDead() || !armory.isComplete())
            return 0;
        return armory.getUnitContainer() == null ? 0 : armory.getUnitContainer().getNumSupplies();
    }

    public static boolean starved(@NonNull LandBuilding armory, @NonNull Class<?> weapon) {
        if (armory.isDead() || !armory.isComplete())
            return false;
        var c = armory.getBuildSupplyContainer(weapon);
        return c instanceof BuildProductionContainer p && !p.hasEnoughSupplies();
    }

    /** Peon-seconds per resource unit from a node at one-way distance d metres. */
    public static float tripCost(float d_metres) {
        return 12f + 0.45f * (d_metres + 4f);
    }

    /** Distance (metres) from the armory to the nearest live nodes of a kind (mean of the k nearest). */
    private float meanDistance(@NonNull LandBuilding armory, ResourceMap.@NonNull Kind kind, int k) {
        List<ResourceMap.Node> nodes = resources.nearestAlive(kind, armory.getGridX(), armory.getGridY(),
                params.gather_radius * 2, k);
        if (nodes.isEmpty())
            return params.gather_radius * 2f * 2f;
        float sum = 0f;
        for (ResourceMap.Node n : nodes)
            sum += n.distanceTo(armory.getGridX(), armory.getGridY()) * 2f;
        return sum / nodes.size();
    }

    // ------------------------------------------------------------------ slow tick (1 s)

    /**
     * Main economy pass. {@code training_quarters} is the quarters currently training the chieftain (or null);
     * {@code builders_wanted} is how many peons construction still needs (they are taken from workers).
     */
    public void tick(float now, @NonNull Roster roster, @NonNull Intel intel, @Nullable LandBuilding training_quarters,
            int total_peons) {
        last_intel = intel;
        LandBuilding armory = roster.mainArmory();
        cap_mode = roster.unitCount() >= roster.unitCap() - params.cap_margin;
        manageQuarters(now, roster, armory, training_quarters, total_peons);
        if (armory == null)
            return;
        if (rally_armory != armory) {
            rally_armory = armory;
            quarters_rally_set_count = -1;
        }
        for (LandBuilding a : roster.armories)
            if (orders.complete(a))
                manageWeaponQueues(a);
        allocate(now, roster, armory);
        manageExpansion(now, roster, armory);
        hunts(now, roster, intel, armory);
    }

    // ------------------------------------------------------------------ expansion armory

    private float last_a2_iron = -100f;
    private float last_a2_wood = -100f;
    private float last_a2_transfer = -100f;

    /** The second complete armory (by fresh iron), or null. */
    public static @Nullable LandBuilding expansion(@NonNull Roster roster) {
        return roster.armories.size() >= 2 ? roster.armories.get(1) : null;
    }

    /**
     * Staff and feed the expansion armory: workers come from the main armory's surplus and from idle peons around
     * it, its own harvesters fetch the iron and wood next to it (the engine's nearest-node search from the
     * expansion is exactly what we want there), and stock that piles up recalls them.
     */
    private void manageExpansion(float now, @NonNull Roster roster, @NonNull LandBuilding main) {
        LandBuilding a2 = expansion(roster);
        if (a2 == null || !orders.complete(a2))
            return;
        int W2 = workers(a2);
        int S2_i = stock(a2, IronSupply.class);
        int S2_w = stock(a2, TreeSupply.class);
        // workers: transfer the main armory's surplus (they walk over and enter as idle peons near it)
        if (W2 < params.expand_workers && now - last_a2_transfer > 15f && queued(main, DeployType.PEON) == 0) {
            int spare = workers(main) - workers_star - 4;
            int n = Math.min(Math.min(spare, 6), params.expand_workers - W2);
            if (n > 0) {
                orders.setRally(main, a2.getGridX(), a2.getGridY());
                orders.deploy(main, DeployType.PEON, n);
                rally_restore = main;
                last_a2_transfer = now;
                final int fn = n;
                log.info(() -> "expansion: " + fn + " workers sent over");
            }
        } else if (rally_restore != null && now - last_a2_transfer > 3f) {
            // the deploy clock has released them: put the main rally back (the military owns it)
            rally_restore = null;
            rally_dirty = true;
        }
        if (W2 <= 4)
            return;
        // harvesters: the engine's nearest-node search from the expansion finds the fresh field
        if (S2_i < 10 && queued(a2, DeployType.PEON_HARVEST_IRON) == 0 && now - last_a2_iron > 8f && W2 > 5) {
            orders.deploy(a2, DeployType.PEON_HARVEST_IRON, Math.min(3, W2 - 4));
            last_a2_iron = now;
        } else if (S2_w < 8 && queued(a2, DeployType.PEON_HARVEST_TREE) == 0 && now - last_a2_wood > 8f && W2 > 5) {
            orders.deploy(a2, DeployType.PEON_HARVEST_TREE, Math.min(2, W2 - 4));
            last_a2_wood = now;
        }
        if (S2_i >= 25)
            orders.recallGatherers(a2, IronSupply.class, 2);
        if (S2_w >= 40)
            orders.recallGatherers(a2, TreeSupply.class, 2);
    }

    private @Nullable LandBuilding rally_restore;
    /** Set when the economy moved the main armory's rally; the military re-applies its own rally then. */
    public boolean rally_dirty;

    /** The complete armory nearest to a cell (peons enter the nearest one). */
    private @NonNull LandBuilding nearestArmory(@NonNull Roster roster, @NonNull LandBuilding fallback, int gx,
            int gy) {
        LandBuilding best = fallback;
        int best_d2 = Integer.MAX_VALUE;
        for (LandBuilding a : roster.armories) {
            if (!orders.complete(a))
                continue;
            int d2 = BasePlan.dist2(a.getGridX(), a.getGridY(), gx, gy);
            if (d2 < best_d2) {
                best_d2 = d2;
                best = a;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ quarters

    private void manageQuarters(float now, @NonNull Roster roster, @Nullable LandBuilding armory,
            @Nullable LandBuilding training_quarters, int total_peons) {
        if (roster.quarters.isEmpty())
            return;
        // rally every quarters to the armory (peons enter it as workers); re-check when the count changes
        if (armory != null && roster.quarters.size() != quarters_rally_set_count) {
            for (LandBuilding q : roster.quarters)
                orders.setRally(q, armory);
            quarters_rally_set_count = roster.quarters.size();
        }
        // opening: a few breeders (everyone else builds); boom: many (the cube-root law still rewards it and the
        // unit cap is the real race); late: fewer so the armory gets workers; cap: one
        int base_target = cap_mode ? 1 : armory == null ? params.breeders_early : roster.unitCount() < params.boom_units ? params.breeders_boom : params.breeders_late;
        if (demands.emergency && armory != null)
            base_target = Math.min(base_target, params.breeders_late);
        // the quarters nearest the armory keeps the most peons; the farthest (rear) keeps fewer
        LandBuilding rear = null;
        if (armory != null && roster.quarters.size() >= 3 && base_target <= params.breeders_late) {
            int best_d2 = -1;
            for (LandBuilding q : roster.quarters) {
                int d2 = BasePlan.dist2(q.getGridX(), q.getGridY(), armory.getGridX(), armory.getGridY());
                if (d2 > best_d2) {
                    best_d2 = d2;
                    rear = q;
                }
            }
        }
        for (LandBuilding q : roster.quarters) {
            int inside = Roster.unitsInside(q);
            int target = base_target;
            if (q == training_quarters)
                target = params.breeders_training;
            else if (q == rear)
                target = Math.max(1, base_target - 1);
            if (cap_mode)
                target = 1;
            if (inside < target && armory != null && !demands.emergency && roster.unitCount() < params.boom_units) {
                // fill up from the armory's spare workers (they walk over and enter)
                int spare = workers(armory) - workers_star - 2;
                if (spare > 0 && queued(armory, DeployType.PEON) == 0 && queued(q, DeployType.PEON) == 0) {
                    int n = Math.min(spare, target - inside);
                    orders.setRally(armory, q);
                    orders.deploy(armory, DeployType.PEON, n);
                }
            }
            if (inside > target) {
                int n = inside - target;
                if (armory != null) {
                    // surplus walks to the armory and enters it (rally point)
                    if (queued(q, DeployType.PEON) == 0)
                        orders.deploy(q, DeployType.PEON, n);
                } else if (opening_site != null) {
                    // no armory yet: births walk to the site under construction and join its crew
                    if (queued(q, DeployType.PEON) == 0) {
                        orders.setRally(q, opening_site[0], opening_site[1]);
                        orders.deploy(q, DeployType.PEON, n);
                    }
                }
            }
        }
    }

    /** Cell births should walk to while there is no armory (the armory construction site), or null. */
    public int @Nullable [] opening_site;

    // ------------------------------------------------------------------ weapon queues

    private void manageWeaponQueues(@NonNull LandBuilding armory) {
        // the rock line only takes man-seconds while rock and wood are in stock, so it is harmless whenever iron is
        // out; it is switched off again as soon as iron flows so it does not dilute iron production
        boolean rock = mode == Mode.ROCK || rock_fallback || demands.want_rock_warriors
                || (stock(armory, IronSupply.class) == 0 && stock(armory, RockSupply.class) >= 1);
        orders.queueWeapons(armory, rock, true, true);
    }

    // ------------------------------------------------------------------ allocation

    private void allocate(float now, @NonNull Roster roster, @NonNull LandBuilding armory) {
        int W = workers(armory);
        int G_w = roster.gatherers_tree;
        int G_i = roster.gatherers_iron;
        int G_k = roster.gatherers_rock;
        int G_r = roster.gatherers_rubber;
        int S_w = stock(armory, TreeSupply.class);
        int S_i = stock(armory, IronSupply.class);
        int S_k = stock(armory, RockSupply.class);
        int S_r = stock(armory, RubberSupply.class);
        int K_i = stock(armory, IronAxeWeapon.class);
        int K_c = stock(armory, RubberAxeWeapon.class);
        int queued_peons = queued(armory, DeployType.PEON) + queued(armory, DeployType.PEON_HARVEST_TREE) + queued(
                armory, DeployType.PEON_HARVEST_IRON) + queued(armory, DeployType.PEON_HARVEST_ROCK) + queued(armory,
                        DeployType.PEON_HARVEST_RUBBER);

        d_wood_m = meanDistance(armory, ResourceMap.Kind.TREE, Math.max(4, G_w));
        d_iron_m = meanDistance(armory, ResourceMap.Kind.IRON, Math.max(3, G_i));
        int iron_reachable = resources.countAlive(ResourceMap.Kind.IRON, armory.getGridX(), armory.getGridY(),
                params.gather_radius * 2);
        // production mode
        if (mode == Mode.IRON && iron_reachable == 0 && S_i == 0 && starved(armory, IronAxeWeapon.class)) {
            if (starved_iron_since < 0)
                starved_iron_since = now;
            else if (now - starved_iron_since > 30f) {
                mode = Mode.ROCK;
                log.info("economy: iron exhausted -> ROCK mode");
            }
        } else {
            starved_iron_since = -1f;
            if (mode == Mode.ROCK && iron_reachable >= 6) {
                mode = Mode.IRON;
                log.info("economy: iron available again -> IRON mode");
            }
        }

        int N_a = W + G_w + G_i + G_k + queued_peons;
        float k = mode == Mode.IRON ? 1f : 0.5f;
        float c_w = tripCost(d_wood_m);
        float c_main = mode == Mode.IRON ? tripCost(d_iron_m) : tripCost(meanDistance(armory, ResourceMap.Kind.ROCK,
                2));
        rate_star = N_a / (80f * k + 2f * c_w + c_main);
        int W_star = Math.max(params.workers_min, Math.round(80f * k * rate_star));
        int Gw_star = (int) Math.ceil(2f * rate_star * c_w);
        int Gi_star = mode == Mode.IRON ? (int) Math.ceil(rate_star * c_main) : 0;
        int Gk_star = mode == Mode.ROCK ? (int) Math.ceil(rate_star * c_main) : (S_k < params.rock_reserve && (S_r > 0
                || !hunters.isEmpty() || now < 360f || K_c > 0) ? 1 : 0);
        // stock feedback (targets ~40 s of consumption)
        int T_w = Math.clamp(Math.round(params.stock_target_wood_per_rate * rate_star), params.stock_wood_min,
                params.stock_wood_max);
        int T_i = Math.clamp(Math.round(params.stock_target_iron_per_rate * rate_star), params.stock_iron_min,
                params.stock_iron_max);
        float e_w = (S_w - T_w) / (float) T_w;
        float e_i = (S_i - T_i) / (float) T_i;
        if (e_w > 1f)
            Gw_star = Math.max(1, Gw_star - (int) e_w);
        else if (e_w < -0.5f && starved(armory, IronAxeWeapon.class) && S_w < 2)
            Gw_star += 1;
        if (mode == Mode.IRON) {
            if (e_i > 1f)
                Gi_star = Math.max(1, Gi_star - (int) e_i);
            else if (e_i < -0.5f && S_i == 0 && iron_reachable > 0)
                Gi_star += 1;
        }
        if (S_k >= params.stock_rock_target * 3)
            Gk_star = 0;
        if (cap_mode) {
            // at the cap weapons are the only way to turn peons into warriors: keep the balanced split (it is
            // already resource limited) but never hold more workers than the weapons queue can use
            W_star = Math.max(params.cap_workers, W_star);
            Gk_star = Math.min(Gk_star, 1);
        }
        // rock as the fallback weapon: whenever iron stock is empty the rock line costs no man-seconds, and at the
        // cap or under long iron starvation rock warriors beat idle peons
        boolean iron_dry = S_i == 0 && (starved(armory, IronAxeWeapon.class) || iron_reachable == 0);
        if (iron_dry && iron_dry_since < 0f)
            iron_dry_since = now;
        if (!iron_dry)
            iron_dry_since = -1f;
        rock_fallback = mode == Mode.ROCK || (iron_dry && (cap_mode || now - iron_dry_since > 20f)
                && S_k + G_k * 3 < 30);
        if (rock_fallback && Gk_star < 2)
            Gk_star = Math.max(Gk_star, cap_mode ? Math.max(2, Gw_star / 2) : 2);
        // under attack gatherers near the fighting come home (hot cells below) but the economy keeps running: an
        // armory that stops receiving iron stops making warriors, which is how sieges are lost
        workers_star = W_star;
        wood_star = Gw_star;
        iron_star = Gi_star;
        rock_star = Gk_star;

        // ---- conversions (cheapest first, at most a few per tick)
        int conversions = 0;
        // 1. excess gatherers -> recall (they enter the armory with their load)
        if (G_w > Gw_star + 1 && conversions < params.conversions_per_tick && cooled(now, ResourceMap.Kind.TREE,
                false)) {
            orders.recallGatherers(armory, TreeSupply.class, Math.min(G_w - Gw_star, 3));
            last_conversion_wood = now;
            conversions++;
        }
        if (G_i > Gi_star + 1 && conversions < params.conversions_per_tick && cooled(now, ResourceMap.Kind.IRON,
                false)) {
            orders.recallGatherers(armory, IronSupply.class, Math.min(G_i - Gi_star, 3));
            last_conversion_iron = now;
            conversions++;
        }
        if (G_k > Gk_star && conversions < params.conversions_per_tick && cooled(now, ResourceMap.Kind.ROCK, false)) {
            orders.recallGatherers(armory, RockSupply.class, G_k - Gk_star);
            last_conversion_rock = now;
            conversions++;
        }
        // 2. deficits -> deploy harvesters from the workers (one type per tick keeps the deploy clock undivided)
        int spare = W - params.workers_min;
        // the engine's own nearest-node search is bounded; far iron must be assigned node by node
        boolean far_iron = d_iron_m > params.far_iron_m;
        if (spare > 0 && conversions < params.conversions_per_tick && rock_fallback && G_k < Gk_star
                && cooled(now, ResourceMap.Kind.ROCK, S_k == 0)) {
            // iron is dry: the rock line comes first, otherwise the iron branch below (peons sent at far iron
            // that never arrives while the enemy sits on the road) starves it every tick
            int n = Math.min(Gk_star - G_k, Math.min(spare, 4));
            orders.deploy(armory, DeployType.PEON_HARVEST_ROCK, n);
            last_conversion_rock = now;
            conversions++;
            spare -= n;
            log.info(() -> "economy: +" + n + " rock gatherers (iron dry)");
        }
        // workers piling up inside the armory with nothing to forge (iron dry, road cut) are idle units: send the
        // surplus out to gather what is useful, rock when wood is plentiful
        if (spare > 0 && conversions < params.conversions_per_tick && W > W_star + params.workers_surplus_max) {
            int n = Math.min(W - W_star - params.workers_surplus_max / 2, 6);
            boolean wood = S_w < params.stock_wood_max * 4 && G_w < Gw_star + 10;
            orders.deploy(armory, wood ? DeployType.PEON_HARVEST_TREE : DeployType.PEON_HARVEST_ROCK, n);
            if (wood)
                last_conversion_wood = now;
            else
                last_conversion_rock = now;
            conversions++;
            spare -= n;
            final int fw = W, fws = W_star;
            log.info(
                    () -> "economy: " + n + " surplus workers out to " + (wood ? "wood" : "rock") + " (W=" + fw + " W*=" + fws + ")");
        }
        if (spare > 0 && conversions < params.conversions_per_tick) {
            boolean starved_w = S_w < 2;
            boolean starved_i = mode == Mode.IRON && S_i == 0;
            if (G_i + pending_iron < Gi_star && cooled(now, ResourceMap.Kind.IRON, starved_i) && iron_reachable > 0) {
                int n = Math.min(Gi_star - G_i - pending_iron, Math.min(spare, 4));
                if (far_iron) {
                    // plain peons walk out of the armory; the next tick points each one at a specific iron node
                    orders.setRally(armory, armory.getGridX() + 5, armory.getGridY() + 5);
                    orders.deploy(armory, DeployType.PEON, n);
                    pending_iron += n;
                    pending_iron_since = now;
                } else {
                    orders.deploy(armory, DeployType.PEON_HARVEST_IRON, n);
                }
                final int nn = n;
                log.info(() -> "economy: +" + nn + " iron gatherers (" + (far_iron ? "assigned" : "engine") + ")");
                last_conversion_iron = now;
                conversions++;
                spare -= n;
            } else if (G_w < Gw_star && cooled(now, ResourceMap.Kind.TREE, starved_w)) {
                int n = Math.min(Gw_star - G_w, Math.min(spare, 5));
                orders.deploy(armory, DeployType.PEON_HARVEST_TREE, n);
                last_conversion_wood = now;
                conversions++;
                spare -= n;
            } else if (G_k < Gk_star && cooled(now, ResourceMap.Kind.ROCK, mode == Mode.ROCK && S_k == 0)) {
                int n = Math.min(Gk_star - G_k, Math.min(spare, 2));
                orders.deploy(armory, DeployType.PEON_HARVEST_ROCK, n);
                last_conversion_rock = now;
                conversions++;
            }
        }
        // 3. idle peons anywhere -> assigned iron node (when iron is far and wanted) or into the armory
        if (now - pending_iron_since > 20f)
            pending_iron = 0;
        int supply_orders = 0;
        for (Unit p : roster.peons) {
            if (!orders.usable(p) || Roster.activity(p) != Roster.Activity.IDLE)
                continue;
            if (jobs.has(p) && jobs.kindOf(p) != Jobs.Kind.NONE && now - jobs.get(p).issued_at < 20f)
                continue;
            if (hunters.contains(p) || reserved.contains(p))
                continue;
            if (far_iron && G_i < Gi_star && supply_orders < params.max_supply_orders_per_tick
                    && BasePlan.dist2(p.getGridX(), p.getGridY(), armory.getGridX(), armory.getGridY()) <= 30 * 30) {
                ResourceMap.Node node = pickIronNode(armory, roster);
                if (node != null) {
                    Supply s = node.live(player.getWorld().getUnitGrid());
                    if (s != null) {
                        orders.gather(p, s);
                        jobs.set(p, Jobs.Kind.GATHER, node.grid_x, node.grid_y, null, node, now, 0);
                        supply_orders++;
                        G_i++;
                        if (pending_iron > 0)
                            pending_iron--;
                        continue;
                    }
                }
            }
            LandBuilding into = nearestArmory(roster, armory, p.getGridX(), p.getGridY());
            orders.enter(p, into);
            jobs.set(p, Jobs.Kind.ENTER, into.getGridX(), into.getGridY(), into, null, now, 0);
        }
        // gatherers whose assigned node died walk to the next node on the engine's own search; if that fails they
        // come back idle and get a fresh node above
        // 4. hot nodes: gatherers near enemy warriors come home with their load
        if (!demands.hot_cells.isEmpty()) {
            int r2 = demands.hot_radius * demands.hot_radius;
            for (Unit p : roster.peons) {
                if (!orders.usable(p))
                    continue;
                Roster.Activity a = Roster.activity(p);
                if (a != Roster.Activity.GATHER_TREE && a != Roster.Activity.GATHER_IRON
                        && a != Roster.Activity.GATHER_ROCK
                        && a != Roster.Activity.GATHER_RUBBER)
                    continue;
                for (int[] c : demands.hot_cells) {
                    if (BasePlan.dist2(p.getGridX(), p.getGridY(), c[0], c[1]) <= r2) {
                        LandBuilding into = nearestArmory(roster, armory, p.getGridX(), p.getGridY());
                        orders.enter(p, into);
                        jobs.set(p, Jobs.Kind.ENTER, into.getGridX(), into.getGridY(), into, null, now, 1);
                        break;
                    }
                }
            }
        }
        // 5. armory under serious threat: evacuate the workers to the rear so they survive to rebuild
        if (demands.armory_threat && armory.getHitPoints() < 80 && W > 0 && !roster.quarters.isEmpty()) {
            LandBuilding rear = roster.quarters.getFirst();
            int best_d2 = -1;
            for (LandBuilding q : roster.quarters) {
                int d2 = BasePlan.dist2(q.getGridX(), q.getGridY(), armory.getGridX(), armory.getGridY());
                if (d2 > best_d2) {
                    best_d2 = d2;
                    rear = q;
                }
            }
            orders.setRally(armory, rear);
            orders.deploy(armory, DeployType.PEON, W);
            log.info("economy: evacuating armory workers");
        }
    }

    private int pending_iron;
    private float pending_iron_since = -100f;
    private float iron_dry_since = -1f;
    private boolean rock_fallback;

    /**
     * Live iron node with the shortest walk from the armory (the engine's region path, not the straight line: a
     * node across a ridge is minutes away) and fewer than the maximum assigned gatherers. Nodes under an enemy
     * tower or with enemy warriors standing on them are skipped: a gatherer sent there just dies.
     */
    private ResourceMap.@Nullable Node pickIronNode(@NonNull LandBuilding armory, @NonNull Roster roster) {
        List<ResourceMap.Node> nodes = new ArrayList<>(resources.nearestAlive(ResourceMap.Kind.IRON,
                armory.getGridX(), armory.getGridY(), params.gather_radius * 2, 12));
        final int ax = armory.getGridX();
        final int ay = armory.getGridY();
        nodes.sort((a, b) -> {
            int c = Float.compare(plan.walk(ax, ay, a.grid_x, a.grid_y), plan.walk(ax, ay, b.grid_x, b.grid_y));
            if (c != 0)
                return c;
            c = Integer.compare(a.grid_x, b.grid_x);
            return c != 0 ? c : Integer.compare(a.grid_y, b.grid_y);
        });
        ResourceMap.Node fallback = null;
        for (ResourceMap.Node n : nodes) {
            if (underEnemyTower(n.grid_x, n.grid_y) || contested(n.grid_x, n.grid_y))
                continue;
            if (fallback == null)
                fallback = n;
            if (jobs.gatherersOn(n) < params.max_peons_per_node)
                return n;
        }
        return fallback;
    }

    /** True when enemy warriors stand within 25 cells of the cell (a gathering field they are camping). */
    private boolean contested(int gx, int gy) {
        return last_intel != null && last_intel.warriorsNear(gx, gy, 25) >= 2;
    }

    /** True when an enemy manned tower can shoot a peon working at the cell (reach 16 cells plus a margin). */
    private boolean underEnemyTower(int gx, int gy) {
        if (last_intel == null)
            return false;
        for (LandBuilding t : last_intel.manned_towers)
            if (BasePlan.dist2(t.getGridX(), t.getGridY(), gx, gy) <= 20 * 20)
                return true;
        return false;
    }

    private @Nullable Intel last_intel;

    private boolean cooled(float now, ResourceMap.@NonNull Kind kind, boolean starved) {
        if (starved)
            return true;
        float last = switch (kind) {
            case TREE -> last_conversion_wood;
            case IRON -> last_conversion_iron;
            case ROCK -> last_conversion_rock;
        };
        return now - last >= params.conversion_cooldown;
    }

    // ------------------------------------------------------------------ fast tick (warrior deploys)

    /**
     * Keep exactly one warrior in the deploy queue (an order pulls the peon off the anvil immediately, and the deploy
     * clock is shared between queued types), or burst everything out in an emergency. Returns true if something
     * was ordered.
     */
    public boolean deployWarriors(@NonNull Roster roster) {
        boolean any = false;
        for (LandBuilding a : roster.armories)
            if (orders.complete(a) && deployWarriorsFrom(a))
                any = true;
        return any;
    }

    private boolean deployWarriorsFrom(@NonNull LandBuilding armory) {
        int W = workers(armory);
        int K_c = stock(armory, RubberAxeWeapon.class);
        int K_i = stock(armory, IronAxeWeapon.class);
        int K_k = stock(armory, RockAxeWeapon.class);
        int q = queued(armory, DeployType.RUBBER_WARRIOR) + queued(armory, DeployType.IRON_WARRIOR) + queued(armory,
                DeployType.ROCK_WARRIOR);
        boolean rock_ok = mode == Mode.ROCK || rock_fallback || demands.emergency || demands.want_rock_warriors
                || (K_i == 0 && K_c == 0);
        if (demands.emergency) {
            int spare = Math.max(0, W - 1);
            if (spare == 0 || q > 0)
                return false;
            int n = Math.min(spare, K_c);
            if (n > 0) {
                orders.deploy(armory, DeployType.RUBBER_WARRIOR, n);
                spare -= n;
            }
            n = Math.min(spare, K_i);
            if (n > 0) {
                orders.deploy(armory, DeployType.IRON_WARRIOR, n);
                spare -= n;
            }
            n = Math.min(spare, K_k);
            if (n > 0)
                orders.deploy(armory, DeployType.ROCK_WARRIOR, n);
            return true;
        }
        int min_workers = params.workers_min;
        if (cap_mode) {
            // at the cap every spare peon becomes a warrior, but keep a small weapon buffer for instant reinforcement
            if (q > 1 || W <= params.cap_workers)
                return false;
        } else if (q > 0 || W < min_workers + 1) {
            return false;
        }
        if (K_c > 0) {
            orders.deploy(armory, DeployType.RUBBER_WARRIOR, 1);
            return true;
        }
        if (K_i > 0) {
            orders.deploy(armory, DeployType.IRON_WARRIOR, 1);
            return true;
        }
        if (K_k > 0 && rock_ok) {
            orders.deploy(armory, DeployType.ROCK_WARRIOR, 1);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ crews for construction

    /**
     * Provide up to n peons for a job near (gx, gy): idle peons first, then wood gatherers nearest the site, then
     * workers deployed from the armory with the rally point set to the site. Returned units are not yet ordered.
     */
    public @NonNull List<Unit> requestCrew(int n, int gx, int gy, @NonNull Roster roster, float now,
            boolean allow_workers) {
        List<Unit> result = new ArrayList<>();
        if (n <= 0)
            return result;
        List<Unit> candidates = new ArrayList<>();
        for (Unit p : roster.peons) {
            if (!orders.usable(p) || hunters.contains(p) || reserved.contains(p))
                continue;
            Roster.Activity a = Roster.activity(p);
            Jobs.Kind kind = jobs.kindOf(p);
            if (kind == Jobs.Kind.BUILD || kind == Jobs.Kind.HOLD)
                continue;
            if (a == Roster.Activity.IDLE || a == Roster.Activity.WALK || a == Roster.Activity.ENTER
                    || a == Roster.Activity.GATHER_TREE || a == Roster.Activity.GATHER_ROCK)
                candidates.add(p);
        }
        candidates.sort((u, v) -> {
            int ru = rank(u);
            int rv = rank(v);
            if (ru != rv)
                return Integer.compare(ru, rv);
            return Integer.compare(BasePlan.dist2(u.getGridX(), u.getGridY(), gx, gy),
                    BasePlan.dist2(v.getGridX(), v.getGridY(), gx, gy));
        });
        for (Unit p : candidates) {
            if (result.size() >= n)
                break;
            result.add(p);
        }
        if (result.size() < n && allow_workers) {
            LandBuilding armory = roster.mainArmory();
            if (armory != null) {
                int spare = workers(armory) - params.workers_min - 2;
                int deploy = Math.min(spare, n - result.size());
                if (deploy > 0 && queued(armory, DeployType.PEON) == 0) {
                    orders.setRally(armory, gx, gy);
                    orders.deploy(armory, DeployType.PEON, deploy);
                    log.info(() -> "economy: deploying " + deploy + " workers as builders toward " + gx + "," + gy);
                }
            }
        }
        return result;
    }

    private static int rank(@NonNull Unit u) {
        return switch (Roster.activity(u)) {
            case IDLE -> 0;
            case WALK, ENTER -> 1;
            case GATHER_TREE -> 2;
            case GATHER_ROCK -> 3;
            default -> 4;
        };
    }

    // ------------------------------------------------------------------ chickens

    private void hunts(float now, @NonNull Roster roster, @NonNull Intel intel, @NonNull LandBuilding armory) {
        hunters.removeIf(u -> !orders.usable(u) || Roster.activity(u) == Roster.Activity.IDLE
                || Roster.activity(u) == Roster.Activity.ENTER);
        if (now - last_hunt_check < 5f)
            return;
        last_hunt_check = now;
        if (now < params.hunt_min_time || demands.emergency)
            return;
        int S_r = stock(armory, RubberSupply.class);
        int K_c = stock(armory, RubberAxeWeapon.class);
        if (S_r + K_c >= 4 || hunters.size() >= params.hunt_max_peons)
            return;
        List<RubberSupply> chickens = resources.findChickens();
        if (chickens.isEmpty())
            return;
        int ax = armory.getGridX();
        int ay = armory.getGridY();
        int max_d2 = params.hunt_max_dist * params.hunt_max_dist;
        List<RubberSupply> targets = new ArrayList<>();
        for (RubberSupply c : chickens) {
            int d2 = BasePlan.dist2(c.getGridX(), c.getGridY(), ax, ay);
            if (d2 > max_d2)
                continue;
            // must be closer to us than to the enemy armory/start and unguarded
            LandBuilding enemy_armory = intel.armories.isEmpty() ? null : intel.armories.getFirst();
            if (enemy_armory != null && BasePlan.dist2(c.getGridX(), c.getGridY(), enemy_armory.getGridX(),
                    enemy_armory.getGridY()) * 1.4f < d2)
                continue;
            if (intel.warriorsNear(c.getGridX(), c.getGridY(), 30) > 0)
                continue;
            targets.add(c);
        }
        if (targets.isEmpty())
            return;
        // hunters: the wood gatherers nearest the chickens, one chicken each
        int want = Math.min(params.hunt_max_peons - hunters.size(), targets.size());
        List<Unit> pool = new ArrayList<>();
        for (Unit p : roster.peons)
            if (orders.usable(p) && !hunters.contains(p) && !reserved.contains(p) && jobs.kindOf(p) != Jobs.Kind.BUILD
                    && (Roster.activity(p) == Roster.Activity.GATHER_TREE || Roster.activity(
                            p) == Roster.Activity.IDLE))
                pool.add(p);
        RubberSupply first = targets.getFirst();
        pool.sort((u, v) -> Integer.compare(BasePlan.dist2(u.getGridX(), u.getGridY(), first.getGridX(),
                first.getGridY()),
                BasePlan.dist2(v.getGridX(), v.getGridY(), first.getGridX(), first.getGridY())));
        int sent = 0;
        for (int i = 0; i < pool.size() && sent < want; i++) {
            Unit p = pool.get(i);
            RubberSupply c = targets.get(sent);
            if (c.isDead() || c.isEmpty())
                continue;
            orders.gather(p, c);
            jobs.set(p, Jobs.Kind.GATHER, c.getGridX(), c.getGridY(), null, null, now, 9);
            hunters.add(p);
            sent++;
        }
        if (sent > 0) {
            final int n = sent;
            log.info(
                    () -> "economy: " + n + " peons hunting chickens near " + first.getGridX() + "," + first.getGridY());
        }
    }

    public int hunterCount() {
        return hunters.size();
    }

    public boolean isCapMode() {
        return cap_mode;
    }

    /** Move idle peons holding nothing into the nearest quarters when there is no armory (they breed there). */
    public void parkIdlePeons(float now, @NonNull Roster roster) {
        if (roster.quarters.isEmpty())
            return;
        for (Unit p : roster.peons) {
            if (!orders.usable(p) || Roster.activity(p) != Roster.Activity.IDLE || jobs.kindOf(p) == Jobs.Kind.BUILD
                    || reserved.contains(p))
                continue;
            LandBuilding q = nearestQuarters(roster, p.getGridX(), p.getGridY());
            if (q != null) {
                orders.enter(p, q);
                jobs.set(p, Jobs.Kind.ENTER, q.getGridX(), q.getGridY(), q, null, now, 0);
            }
        }
    }

    public static @Nullable LandBuilding nearestQuarters(@NonNull Roster roster, int gx, int gy) {
        LandBuilding best = null;
        int best_d2 = Integer.MAX_VALUE;
        for (LandBuilding q : roster.quarters) {
            int d2 = BasePlan.dist2(q.getGridX(), q.getGridY(), gx, gy);
            if (d2 < best_d2) {
                best_d2 = d2;
                best = q;
            }
        }
        return best;
    }

    /** Peons that may be pulled into a quarters to raise its breeder count (idle or wood gatherers near it). */
    public void fillQuarters(@NonNull LandBuilding q, int wanted, @NonNull Roster roster, float now) {
        int inside = Roster.unitsInside(q);
        if (inside >= wanted)
            return;
        int need = wanted - inside;
        LandBuilding armory = roster.mainArmory();
        if (armory != null && workers(armory) > params.workers_min + need && queued(armory, DeployType.PEON) == 0) {
            orders.setRally(armory, q);
            orders.deploy(armory, DeployType.PEON, need);
            return;
        }
        List<Unit> pool = new ArrayList<>();
        for (Unit p : roster.peons)
            if (orders.usable(p) && jobs.kindOf(p) != Jobs.Kind.BUILD && !reserved.contains(p)
                    && (Roster.activity(p) == Roster.Activity.IDLE || Roster.activity(
                            p) == Roster.Activity.GATHER_TREE))
                pool.add(p);
        pool.sort((u, v) -> Integer.compare(BasePlan.dist2(u.getGridX(), u.getGridY(), q.getGridX(), q.getGridY()),
                BasePlan.dist2(v.getGridX(), v.getGridY(), q.getGridX(), q.getGridY())));
        for (int i = 0; i < pool.size() && i < need; i++) {
            orders.enter(pool.get(i), q);
            jobs.set(pool.get(i), Jobs.Kind.ENTER, q.getGridX(), q.getGridY(), q, null, now, 0);
        }
    }

    public @NonNull String status(@NonNull Roster roster) {
        LandBuilding a = roster.mainArmory();
        if (a == null)
            return "no armory";
        String s = String.format(
                "%s R*=%.2f W=%d/%d wood=%d/%d iron=%d/%d rock=%d/%d stock w%d i%d k%d r%d wpn i%d c%d d_w=%.0fm d_i=%.0fm",
                mode, rate_star, workers(a), workers_star, roster.gatherers_tree, wood_star, roster.gatherers_iron,
                iron_star, roster.gatherers_rock, rock_star, stock(a, TreeSupply.class), stock(a, IronSupply.class),
                stock(a, RockSupply.class), stock(a, RubberSupply.class), stock(a, IronAxeWeapon.class),
                stock(a, RubberAxeWeapon.class), d_wood_m, d_iron_m);
        LandBuilding a2 = expansion(roster);
        if (a2 != null && orders.complete(a2))
            s += String.format(" | A2 W=%d stock w%d i%d wpn i%d", workers(a2), stock(a2, TreeSupply.class),
                    stock(a2, IronSupply.class), stock(a2, IronAxeWeapon.class));
        return s;
    }
}
