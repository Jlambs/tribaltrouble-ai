package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.BuildProductionContainer;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.UnitSupplyContainer;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.gui.BuildSpinner;
import com.oddlabs.tt.pathfinder.Occupant;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.gauntlet.Intel.PeonState;
import com.oddlabs.tt.player.gauntlet.SitePlanner.Site;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the base: where and when to build, who builds, how many peons stay in each quarters, how the armory's peons
 * split between making weapons and gathering, and which supply each gatherer works so they do not crowd one tree.
 */
final class Economy {
    /** Man-seconds of armory work per iron weapon. */
    private static final float IRON_WORK = 80f;
    private static final int MAX_BUILDERS = 20;
    /** Peons per supply before another supply is preferred, for trees and for ore. */
    private static final int TREE_LOAD = 2;

    private final @NonNull GauntletAI ai;
    private final List<@NonNull Project> projects = new ArrayList<>();
    private final Map<@NonNull Unit, @NonNull Supply> gather_targets = new LinkedHashMap<>();
    private final Map<@NonNull Supply, Integer> supply_load = new LinkedHashMap<>();
    private final List<@NonNull RubberSupply> chickens = new ArrayList<>();

    private @Nullable Unit scout;
    private @Nullable Site armory_site;
    private @Nullable DistanceField armory_field;
    private @Nullable Building armory_field_owner;
    private float armory_field_time;
    private float chickens_time = -100f;

    private int want_tree;
    private int want_iron;
    private int want_rock;
    private int want_chicken;
    private int want_workers;
    private float tree_cycle = 14f;
    private float iron_cycle = 22f;
    private boolean rock_weapons;
    private boolean rock_filler;
    private @Nullable Project expansion_project;
    private @Nullable Building expansion;
    private float last_expansion_check = -100f;
    private float last_old_recall = -100f;
    private boolean chieftain_topup;
    private int project_counter;
    private final List<@NonNull Building> forward_towers = new ArrayList<>();
    private final List<@NonNull Building> sniper_towers = new ArrayList<>();
    /** Quarters and armories being emptied because they are about to fall (evacuate), and since when. */
    private final Map<@NonNull Building, Float> evacuating = new LinkedHashMap<>();
    private float last_sniper = -100f;
    /** Per gatherer: the load it carried and since when, to catch peons stuck walking to a supply. */
    private final Map<@NonNull Unit, float @NonNull []> gather_progress = new LinkedHashMap<>();
    /** Supplies a gatherer got stuck on, avoided until the time given. */
    private final Map<@NonNull Supply, Float> bad_supplies = new LinkedHashMap<>();
    private int unstuck;
    private float last_unstuck_log;
    private boolean rush_alert;
    private float rush_alert_time;
    private boolean had_armory;

    Economy(@NonNull GauntletAI ai) {
        this.ai = ai;
        openingPlan();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Opening

    private void openingPlan() {
        Intel intel = ai.intel();
        SitePlanner planner = ai.planner();
        Strategy strategy = ai.strategy();
        List<Site> reserved = new ArrayList<>();
        armory_site = planner.findArmorySite(reserved);
        int sx = planner.getStartX();
        int sy = planner.getStartY();
        int ax = armory_site != null ? armory_site.x : sx;
        int ay = armory_site != null ? armory_site.y : sy;
        if (armory_site != null)
            reserved.add(armory_site.withHalf(SitePlanner.RaceSizes.ARMORY));

        // The freeze squad (Freeze) walks off at the start: it builds nothing until the strike ends.
        int first_builders = Math.max(1, intel.peons.size() - strategy.scouts - intel.strikers.size());
        Site q1 = planner.findQuartersSite(reserved, sx, sy, 110, planner.getStartField(), ax, ay, first_builders,
                .2f, .06f);
        // The score is minus the seconds until the quarters stands; when that is poor nearby, a walk to better
        // ground pays off, and the walk is already part of the score.
        if (q1 == null || -q1.score > 110f) {
            Site further = planner.findQuartersSite(reserved, sx, sy, 400, planner.getStartField(), ax, ay,
                    first_builders, .2f, .06f);
            if (further != null && (q1 == null || further.score > q1.score))
                q1 = further;
        }
        if (q1 == null) {
            // Cramped start: give the quarters the armory's spot rather than go without peons.
            q1 = planner.findQuartersSite(List.of(), sx, sy, 400, planner.getStartField(), ax, ay, first_builders,
                    .2f, 0f);
            if (q1 != null && armory_site != null && SitePlanner.conflicts(List.of(armory_site), q1.x, q1.y,
                    SitePlanner.RaceSizes.QUARTERS)) {
                reserved.remove(armory_site);
                armory_site = null;
            }
        }
        if (q1 != null) {
            reserved.add(q1);
            Project p = addProject(Race.BUILDING_QUARTERS, q1, 0);
            p.first = true;
        }
        // Before the armory come the quarters_before_armory first quarters; builders go to one site at a time.
        int armory_priority = 1 + 2 * Math.max(0, strategy.quarters_before_armory - 1);
        if (armory_site != null) {
            Project p = addProject(Race.BUILDING_ARMORY, armory_site, armory_priority);
            p.use_scout = true;
        }
        DistanceField a_field = armory_site != null ? ai.map().computeField(ax, ay, 240) : null;
        for (int i = 1; i < strategy.initial_quarters; i++) {
            Site q;
            if (strategy.opening_near_start && q1 != null)
                q = planner.findQuartersSite(reserved, q1.x, q1.y, 80, planner.getStartField(), ax, ay,
                        strategy.quarters_builders, .25f, .02f);
            else if (a_field != null)
                q = planner.findQuartersSite(reserved, ax, ay, 80, a_field, sx, sy, strategy.quarters_builders, .25f,
                        .02f);
            else
                q = planner.findQuartersSite(reserved, sx, sy, 110, planner.getStartField(), sx, sy,
                        strategy.quarters_builders, .25f, 0f);
            if (q == null)
                break;
            reserved.add(q);
            Project p = addProject(Race.BUILDING_QUARTERS, q, 2 * i);
            p.use_scout = true;
        }

        // One peon walks ahead to lay out the armory and later quarters; the rest raise the first quarters at once.
        Unit best_scout = null;
        int best_d = Integer.MAX_VALUE;
        for (Unit peon : intel.peons) {
            if (intel.strikers.contains(peon))
                continue;
            int d = MapAnalysis.dist2(peon.getGridX(), peon.getGridY(), ax, ay);
            if (d < best_d) {
                best_d = d;
                best_scout = peon;
            }
        }
        scout = strategy.scouts > 0 ? best_scout : null;
        Project first = projects.isEmpty() ? null : projects.getFirst();
        if (first != null && first.first) {
            List<Unit> builders = new ArrayList<>();
            for (Unit peon : intel.peons)
                if (peon != scout && !intel.strikers.contains(peon))
                    builders.add(peon);
            if (!builders.isEmpty())
                place(first, builders);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Periodic work

    void tick() {
        Intel intel = ai.intel();
        choosePrimaryArmory();
        refreshArmoryField();
        if (ai.strategy().tower_cooldown)
            trackRazings();
        manageProjects();
        escortForward();
        evacuate();
        measureYield();
        manageQuarters();
        manageArmory();
        allocatePeons();
        manageRepairs();
    }

    /** Whether the building is being emptied because it is about to fall. */
    boolean isEvacuating(@NonNull Building b) {
        return evacuating.containsKey(b);
    }

    /**
     * Units inside a razed building die with it, uncounted (LandBuilding.removeDying): ~123 per game at N=10, 39 per
     * armory. A quarters or armory below evac_hp of its hit points with at least evac_min enemy warriors within 10
     * cells is emptied: the armory's weapons leave as warriors, everyone else as peons, towards a rally point away from
     * the attackers (for a quarters, into our quarters farthest from them). For 60 s nothing is sent into it.
     */
    private void evacuate() {
        Strategy strategy = ai.strategy();
        evacuating.entrySet().removeIf(e -> e.getKey().isDead() || ai.time() - e.getValue() > 60f);
        if (!strategy.evacuate)
            return;
        Intel intel = ai.intel();
        List<Building> homes = new ArrayList<>(intel.quarters);
        homes.addAll(intel.armories);
        for (Building b : homes) {
            if (b.isDead() || !b.isComplete() || b.getUnitContainer() == null)
                continue;
            int inside = b.getUnitContainer().getNumSupplies();
            if (inside == 0 && !evacuating.containsKey(b))
                continue;
            if (b.getHitPoints() > strategy.evac_hp * b.getTemplate().getMaxHitPoints())
                continue;
            int n = 0;
            long ex = 0;
            long ey = 0;
            for (Unit e : intel.enemy_warriors) {
                if (e.isDead() || MapAnalysis.dist2(e.getGridX(), e.getGridY(), b.getGridX(), b.getGridY()) > 10 * 10)
                    continue;
                n++;
                ex += e.getGridX();
                ey += e.getGridY();
            }
            if (n < strategy.evac_min)
                continue;
            Player owner = ai.owner();
            if (!evacuating.containsKey(b)) {
                int cx = (int) (ex / n);
                int cy = (int) (ey / n);
                boolean quarters = b.getTemplate().getTemplateID() == Race.BUILDING_QUARTERS;
                Building refuge = null;
                int far = -1;
                if (quarters)
                    for (Building q : intel.quarters) {
                        if (q == b || q.isDead() || evacuating.containsKey(q))
                            continue;
                        int d = MapAnalysis.dist2(q.getGridX(), q.getGridY(), cx, cy);
                        if (d > far && !ai.military().threatNear(q.getGridX(), q.getGridY(), 16)) {
                            far = d;
                            refuge = q;
                        }
                    }
                if (refuge != null) {
                    owner.setRallyPoint(b, refuge);
                } else {
                    float dx = b.getGridX() - cx;
                    float dy = b.getGridY() - cy;
                    float len = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
                    int size = ai.map().getSize();
                    int rx = Math.max(3, Math.min(size - 4, b.getGridX() + Math.round(18 * dx / len)));
                    int ry = Math.max(3, Math.min(size - 4, b.getGridY() + Math.round(18 * dy / len)));
                    owner.setRallyPoint(b, rx, ry);
                }
                evacuating.put(b, ai.time());
                ai.aiLog().count(quarters ? "evac_quarters" : "evac_armory");
                ai.log("evacuating " + (quarters ? "quarters" : "armory") + " at " + b.getGridX() + "," + b.getGridY() + ": " + inside + " inside, hp " + b.getHitPoints() + ", " + n + " enemy warriors by it");
            }
            if (inside == 0)
                continue;
            int deployed = 0;
            if (b.getTemplate().getTemplateID() == Race.BUILDING_ARMORY) {
                int c = Math.min(b.getSupplyContainer(RubberAxeWeapon.class).getNumSupplies(), inside);
                if (c > 0)
                    owner.deployUnits(b, DeployType.RUBBER_WARRIOR, c);
                int i = Math.min(b.getSupplyContainer(IronAxeWeapon.class).getNumSupplies(), inside - c);
                if (i > 0)
                    owner.deployUnits(b, DeployType.IRON_WARRIOR, i);
                int r = Math.min(b.getSupplyContainer(RockAxeWeapon.class).getNumSupplies(), inside - c - i);
                if (r > 0)
                    owner.deployUnits(b, DeployType.ROCK_WARRIOR, r);
                deployed = c + i + r;
            }
            int pending = b.getDeployContainer(DeployType.PEON).getNumSupplies();
            int peons = inside - deployed - pending;
            if (peons > 0)
                owner.deployUnits(b, DeployType.PEON, peons);
        }
    }

    void plan() {
        checkRush();
        planBuildings();
        computeGatherTargets();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Construction

    static final class Project {
        final int type;
        final int id;
        @NonNull
        Site site;
        @Nullable
        Building building;
        int priority;
        boolean first;
        boolean use_scout;
        /** A tower out by the enemy's gatherers, built under the army's cover. */
        boolean forward;
        /** A sniper tower next to idle enemies parked by our base (planSniper). */
        boolean sniper;
        int failures;
        float placed_time = -1f;

        Project(int type, int id, @NonNull Site site, int priority) {
            this.type = type;
            this.id = id;
            this.site = site;
            this.priority = priority;
        }

        boolean isPlaced() {
            return building != null && !building.isDead() && building.isPlaced();
        }

        @NonNull
        String describe() {
            String name = switch (type) {
                case Race.BUILDING_QUARTERS -> "quarters";
                case Race.BUILDING_ARMORY -> "armory";
                default -> sniper ? "sniper tower" : forward ? "forward tower" : "tower";
            };
            return name + "#" + id + " at " + site.x + "," + site.y;
        }
    }

    private @NonNull Project addProject(int type, @NonNull Site site, int priority) {
        site.withHalf(SitePlanner.RaceSizes.of(type));
        Project p = new Project(type, project_counter++, site, priority);
        ai.log("plan " + p.describe() + " trees=" + ai.map().treesAround(site.x, site.y,
                7) + " from start=" + ai.planner().getStartField().get(site.x, site.y) + "m");
        int i = 0;
        while (i < projects.size() && projects.get(i).priority <= priority)
            i++;
        projects.add(i, p);
        return p;
    }

    /** Sends the builders to place the project's site, and keeps the site they carry. */
    private void place(@NonNull Project p, @NonNull List<@NonNull Unit> builders) {
        p.building = ai.placeSite(builders, p.type, p.site.x, p.site.y);
    }

    private void order(@NonNull List<@NonNull Unit> units, @Nullable Building target, @NonNull Action action) {
        if (target == null || units.isEmpty())
            return;
        ai.owner().setTarget(units.toArray(new Selectable<?>[0]), target, action, false);
    }

    private void order(@NonNull Unit unit, @NonNull Building target, @NonNull Action action) {
        ai.owner().setTarget(Selectable.newArray(unit), target, action, false);
    }

    private int builderCount(@NonNull Building building) {
        int n = 0;
        for (Building b : ai.intel().builder_sites.values())
            if (b == building)
                n++;
        return n;
    }

    private @Nullable Unit placerOf(@NonNull Building building) {
        for (Map.Entry<Unit, Building> e : ai.intel().builder_sites.entrySet())
            if (e.getValue() == building)
                return e.getKey();
        return null;
    }

    private void manageProjects() {
        Intel intel = ai.intel();
        for (Iterator<Project> it = projects.iterator(); it.hasNext();) {
            Project p = it.next();
            if (p.building != null && p.building.isDead()) {
                it.remove();
                continue;
            }
            if (p.isPlaced()) {
                if (p.placed_time < 0) {
                    p.placed_time = ai.time();
                    ai.log("placed " + p.describe());
                }
                if (p.building.isComplete()) {
                    it.remove();
                    ai.log("completed " + p.describe() + " after " + (int) (ai.time() - p.placed_time) + "s");
                    onCompleted(p);
                }
                continue;
            }
            if (p.building != null && placerOf(p.building) != null)
                continue; // placer on its way
            if (p.building != null) {
                // Nobody is placing it any more: the site was blocked or the placer died. Try somewhere close by.
                p.failures++;
                p.building = null;
                if (p.failures > 6 || p.sniper) {
                    it.remove();
                    continue;
                }
                Site moved = resite(p);
                if (moved == null) {
                    it.remove();
                    continue;
                }
                p.site = moved;
            }
            if (!projectMayStart(p))
                continue;
            Unit placer = choosePlacer(p);
            if (placer == null)
                continue;
            place(p, List.of(placer));
            if (p.building != null)
                intel.builder_sites.put(placer, p.building);
            intel.peon_states.put(placer, PeonState.BUILD);
        }
    }

    private boolean projectMayStart(@NonNull Project p) {
        if (p.use_scout)
            return true;
        if (p.sniper)
            return sniperSafe(p.site.x, p.site.y);
        // A placer sent into a fight only dies there.
        if (ai.military().threatNearEcon(p.site.x, p.site.y, 16))
            return false;
        if (ai.strategy().tower_cooldown && p.type == Race.BUILDING_TOWER && recentlyRazedNear(p.site.x, p.site.y)) {
            ai.aiLog().count("tower_cooldown_skip");
            return false;
        }
        // Builders only walk out once the army stands guard.
        if (p.forward)
            return ai.military().escortArrived(p.site.x, p.site.y);
        // Keep the number of simultaneous sites small so builders are not spread thin.
        int placed_incomplete = 0;
        for (Project q : projects)
            if (q != p && q.isPlaced() && q.type != Race.BUILDING_ARMORY)
                placed_incomplete++;
        int sites = ai.time() >= ai.strategy().tower_parallel_late_time ? ai.strategy().sites_parallel_late : ai.strategy().sites_parallel;
        return p.type == Race.BUILDING_ARMORY || placed_incomplete < sites;
    }

    /** tower_cooldown: our buildings (and sites) seen standing, and where and when one of them fell. */
    private final java.util.Set<@NonNull Building> standing = new java.util.LinkedHashSet<>();
    private final List<float @NonNull []> razings = new ArrayList<>();

    private void trackRazings() {
        Intel intel = ai.intel();
        for (java.util.Iterator<Building> it = standing.iterator(); it.hasNext();) {
            Building b = it.next();
            if (b.isDead()) {
                razings.add(new float[]{b.getGridX(), b.getGridY(), ai.time()});
                it.remove();
            }
        }
        razings.removeIf(r -> ai.time() - r[2] > 90f);
        for (List<Building> group : List.of(intel.quarters, intel.armories, intel.towers, intel.quarters_sites,
                intel.armory_sites, intel.tower_sites))
            for (Building b : group)
                if (!b.isDead())
                    standing.add(b);
    }

    private boolean recentlyRazedNear(int x, int y) {
        for (float[] r : razings)
            if (MapAnalysis.dist2(x, y, (int) r[0], (int) r[1]) <= 25 * 25)
                return true;
        return false;
    }

    /** tower_cooldown: an enemy warrior that is not parked (idle and on its default controller) within r cells. */
    private boolean awakeEnemyNear(int x, int y, int r) {
        for (Unit e : ai.intel().enemy_warriors) {
            if (e.isDead() || MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) > r * r)
                continue;
            boolean parked = e.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.IdleController
                    && e.getCurrentController() == e.getPrimaryController();
            if (!parked)
                return true;
        }
        return false;
    }

    private void onCompleted(@NonNull Project p) {
        if (p == expansion_project)
            expansion = p.building;
        if (p.forward && p.building != null)
            forward_towers.add(p.building);
        if (p.sniper && p.building != null)
            sniper_towers.add(p.building);
        if (p.type == Race.BUILDING_ARMORY && p.building != null) {
            had_armory = true;
            Building armory = p.building;
            ai.owner().buildIronWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
            if (ai.owner().canUseRubber())
                ai.owner().buildRubberWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        }
    }

    private @Nullable Site resite(@NonNull Project p) {
        SitePlanner planner = ai.planner();
        List<Site> reserved = reservedSites(p);
        return switch (p.type) {
            case Race.BUILDING_ARMORY -> {
                Site s = planner.findQuartersSiteLike(reserved, p.site.x, p.site.y, 12, Race.BUILDING_ARMORY);
                yield s != null ? s : planner.findArmorySite(reserved);
            }
            case Race.BUILDING_QUARTERS -> planner.findQuartersSiteLike(reserved, p.site.x, p.site.y, 14,
                    Race.BUILDING_QUARTERS);
            default -> planner.findQuartersSiteLike(reserved, p.site.x, p.site.y, 10, Race.BUILDING_TOWER);
        };
    }

    private @NonNull List<@NonNull Site> reservedSites(@Nullable Project except) {
        List<Site> reserved = new ArrayList<>();
        for (Project q : projects)
            if (q != except)
                reserved.add(q.site);
        Intel intel = ai.intel();
        addBuildings(reserved, intel.quarters);
        addBuildings(reserved, intel.armories);
        addBuildings(reserved, intel.towers);
        addBuildings(reserved, intel.quarters_sites);
        addBuildings(reserved, intel.armory_sites);
        addBuildings(reserved, intel.tower_sites);
        addBuildings(reserved, intel.decoy_sites);
        return reserved;
    }

    private static void addBuildings(@NonNull List<@NonNull Site> reserved,
            @NonNull List<@NonNull Building> buildings) {
        for (Building b : buildings)
            reserved.add(new Site(b.getGridX(), b.getGridY(), 0).withHalf(
                    SitePlanner.RaceSizes.of(b.getTemplate().getTemplateID())));
    }

    private @Nullable Unit choosePlacer(@NonNull Project p) {
        Intel intel = ai.intel();
        if (p.use_scout && scout != null && !scout.isDead()) {
            // The scout places sites in priority order; it only takes the next once the previous one stands.
            for (Project q : projects) {
                if (q == p)
                    break;
                if (q.use_scout && !q.isPlaced() && q.building != null && placerOf(q.building) == scout)
                    return null;
            }
            return scout;
        }
        Unit best = null;
        int best_d = Integer.MAX_VALUE;
        for (Unit peon : intel.peons) {
            PeonState s = intel.peon_states.get(peon);
            if (s != PeonState.IDLE && s != PeonState.TRANSIT && s != PeonState.GATHER_TREE && s != PeonState.MOVE)
                continue;
            int d = MapAnalysis.dist2(peon.getGridX(), peon.getGridY(), p.site.x, p.site.y);
            if (s == PeonState.GATHER_TREE)
                d += 40 * 40;
            if (d < best_d) {
                best_d = d;
                best = peon;
            }
        }
        if (best == null && p.type == Race.BUILDING_ARMORY) {
            for (Unit peon : intel.peons) {
                if (intel.peon_states.get(peon) == PeonState.BUILD) {
                    best = peon;
                    break;
                }
            }
        }
        return best;
    }

    /** How many builders a placed site should have right now. */
    private int buildersWanted(@NonNull Project p) {
        Intel intel = ai.intel();
        Strategy strategy = ai.strategy();
        boolean have_quarters = !intel.quarters.isEmpty();
        boolean have_armory = !intel.armories.isEmpty();
        if (p.first)
            return MAX_BUILDERS;
        if (!have_quarters && hasFirstQuarters())
            return 0; // nothing may take the first quarters' builders
        // More builders than the trees nearby can feed only get in each other's way.
        int trees = ai.map().treesAround(p.site.x, p.site.y, 12);
        int feedable = Math.max(6, 4 * trees);
        int cap = switch (p.type) {
            case Race.BUILDING_ARMORY -> have_armory ? 12 : MAX_BUILDERS;
            case Race.BUILDING_QUARTERS -> armsRace() ? 3 : have_armory ? strategy.quarters_builders : MAX_BUILDERS;
            default -> strategy.tower_builders;
        };
        return Math.min(cap, feedable);
    }

    private boolean hasFirstQuarters() {
        for (Project p : projects)
            if (p.first)
                return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Building plan

    private void planBuildings() {
        Intel intel = ai.intel();
        Strategy strategy = ai.strategy();
        float time = ai.time();
        int armory_count = intel.armories.size() + intel.armory_sites.size() + countProjects(Race.BUILDING_ARMORY,
                false);
        if (armory_count == 0) {
            // A lost armory goes up again next to the quarters furthest from the fighting, not back where it fell.
            Site site = had_armory ? safeArmorySite() : ai.planner().findArmorySite(reservedSites(null));
            if (site != null && ai.military().threatNear(site.x, site.y, 25))
                return;
            if (site == null)
                site = ai.planner().findArmorySite(reservedSites(null));
            if (site == null && !intel.peons.isEmpty()) {
                Unit p = intel.peons.getFirst();
                site = ai.planner().findQuartersSiteLike(reservedSites(null), p.getGridX(), p.getGridY(), 40,
                        Race.BUILDING_ARMORY);
            }
            if (site != null) {
                armory_site = site;
                addProject(Race.BUILDING_ARMORY, site, 0);
            }
        } else if (armory_count == 1 && intel.armories.size() == 1) {
            considerExpansion();
        }
        Building armory = intel.armory();
        int quarters_count = intel.quarters.size() + intel.quarters_sites.size() + countProjects(
                Race.BUILDING_QUARTERS, false);
        int target_quarters = strategy.initial_quarters;
        if (armory != null && time >= strategy.expand_time)
            target_quarters = strategy.max_quarters;
        int pop = ai.owner().getUnitCountContainer().getNumSupplies();
        if (pop > ai.owner().getWorld().getMaxUnitCount() * 3 / 4)
            target_quarters = Math.min(target_quarters, intel.quarters.size());
        if (quarters_count < target_quarters && countProjects(Race.BUILDING_QUARTERS, true) == 0
                && (armory != null || intel.quarters.isEmpty())) {
            int ax = armory != null ? armory.getGridX() : ai.planner().getStartX();
            int ay = armory != null ? armory.getGridY() : ai.planner().getStartY();
            DistanceField field = armory != null ? armoryField() : ai.planner().getStartField();
            Site site = ai.planner().findQuartersSite(reservedSites(null), ax, ay, 90, field,
                    ai.planner().getStartX(), ai.planner().getStartY(), ai.strategy().quarters_builders, .25f, .02f);
            if (site != null)
                addProject(Race.BUILDING_QUARTERS, site, 5);
        }
        if (armory != null && intel.quarters.size() < strategy.tower_min_quarters)
            ai.aiLog().count("tower_gate_quarters"); // plan ticks the quarters gate keeps towers off (A3)
        if (armory != null && intel.quarters.size() >= strategy.tower_min_quarters) {
            int target_towers = 0;
            if (time >= strategy.towers_early_time)
                target_towers = strategy.towers_early;
            if (time >= strategy.towers_mid_time)
                target_towers = strategy.towers_mid;
            if (time >= strategy.towers_late_time)
                target_towers = strategy.towers_late;
            if (ai.military().baseThreatLevel() > 0 && target_towers < 2)
                target_towers = Math.max(target_towers, 1);
            int enemies = ai.enemiesAlive();
            boolean fronts = enemies > 1 && strategy.multi_front_towers;
            if (fronts && target_towers > 0)
                target_towers += Math.min(enemies - 1, strategy.front_tower_bonus_max);
            if (strategy.tower_cap)
                // Leave room under the building cap for every quarters, two armories and a spare site.
                target_towers = Math.min(target_towers,
                        ai.owner().getWorld().getMaxBuildingCount() - strategy.max_quarters - 3);
            forward_towers.removeIf(Building::isDead);
            sniper_towers.removeIf(Building::isDead);
            int tower_count = intel.towers.size() + intel.tower_sites.size() + countProjects(Race.BUILDING_TOWER,
                    false) - forward_towers.size() - countForward() - ai.military().creepTowerCount() - sniper_towers.size() - countSniper();
            int tower_parallel = time >= strategy.tower_parallel_late_time ? strategy.tower_parallel_late : strategy.tower_parallel;
            if (tower_count < target_towers && countProjects(Race.BUILDING_TOWER, true) < tower_parallel
                    && ai.owner().canBuild(Race.BUILDING_TOWER)) {
                List<int[]> existing = new ArrayList<>();
                for (Building t : intel.towers)
                    existing.add(new int[]{t.getGridX(), t.getGridY()});
                for (Building t : intel.tower_sites)
                    existing.add(new int[]{t.getGridX(), t.getGridY()});
                int[] center = towerAnchor(tower_count);
                int[] face = {ai.planner().getEnemyX(), ai.planner().getEnemyY()};
                float[] live = strategy.tower_face_place ? ai.liveEnemyCenter() : null;
                if (live != null)
                    face = new int[]{Math.round(live[0]), Math.round(live[1])};
                int min_cells = 7;
                int max_cells = 15;
                if (fronts && tower_count % 2 == 1) {
                    int[][] front = enemyFront(tower_count / 2);
                    if (front != null) {
                        center = front[0];
                        face = front[1];
                        live = null;
                        // Front towers further out leave room for decoys in front of them (Decoys).
                        min_cells = ai.strategy().front_tower_min;
                        max_cells = ai.strategy().front_tower_max;
                    }
                }
                if (live != null)
                    ai.aiLog().count("tower_face_place");
                Site site = ai.planner().findTowerSite(reservedSites(null), center[0], center[1], min_cells, max_cells,
                        existing,
                        face[0], face[1]);
                if (site != null)
                    addProject(Race.BUILDING_TOWER, site, 8);
            }
            planForwardTower();
            planSniper();
        }
    }

    /** Sniper tower projects, placed or not (their sites are in intel.tower_sites until they stand). */
    private int countSniper() {
        int n = 0;
        for (Project q : projects)
            if (q.sniper)
                n++;
        return n;
    }

    /** An enemy warrior standing idle: it sees 8 cells and never answers being hit (IdleController). */
    private static boolean isParked(@NonNull Unit e) {
        return e.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.IdleController
                && e.getCurrentController() == e.getPrimaryController();
    }

    /**
     * Sniper towers: waves that razed a building of ours stand idle where it was, 16-45 cells from the rest of the
     * base and out of our towers' reach (STAT pb/pt, play-park9b-s11). Idle units see 8 cells and never answer being
     * hit, while a tower garrison reaches 15.9: a tower 11-15 cells from such a blob, out of every parked enemy's
     * scan, shoots it for free until its owner sends the blob on again. One project at a time.
     */
    private void planSniper() {
        Strategy strategy = ai.strategy();
        if (!strategy.snipers || ai.time() - last_sniper < 2f || countSniper() > 0)
            return;
        last_sniper = ai.time();
        Player owner = ai.owner();
        if (!owner.canBuild(Race.BUILDING_TOWER)
                || owner.getBuildingCountContainer().getNumSupplies() + 2 >= owner.getWorld().getMaxBuildingCount())
            return;
        Intel intel = ai.intel();
        List<Building> own = new ArrayList<>(intel.armories);
        own.addAll(intel.quarters);
        own.addAll(intel.towers);
        int range2 = strategy.snipe_range * strategy.snipe_range;
        List<Unit> parked = new ArrayList<>();
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead() || !isParked(e))
                continue;
            for (Building b : own)
                if (!b.isDead() && MapAnalysis.dist2(b.getGridX(), b.getGridY(), e.getGridX(),
                        e.getGridY()) <= range2) {
                            parked.add(e);
                            break;
                        }
        }
        Unit seed = null;
        int best_n = 0;
        for (Unit s : parked) {
            int n = 0;
            for (Unit e : parked)
                if (MapAnalysis.dist2(s.getGridX(), s.getGridY(), e.getGridX(), e.getGridY()) <= 8 * 8)
                    n++;
            if (n > best_n) {
                best_n = n;
                seed = s;
            }
        }
        if (seed == null || best_n < strategy.snipe_min)
            return;
        com.oddlabs.tt.model.BuildingTemplate template = owner.getRace().getBuildingTemplate(Race.BUILDING_TOWER);
        List<Site> reserved = reservedSites(null);
        Building armory = intel.armory();
        int hx = armory != null ? armory.getGridX() : ai.planner().getStartX();
        int hy = armory != null ? armory.getGridY() : ai.planner().getStartY();
        Site best = null;
        float best_score = -Float.MAX_VALUE;
        int[] rejects = new int[4];
        for (int r = 11; r <= 15; r++) {
            for (int a = 0; a < 24; a++) {
                double ang = a * Math.PI / 12;
                int x = seed.getGridX() + (int) Math.round(r * Math.cos(ang));
                int y = seed.getGridY() + (int) Math.round(r * Math.sin(ang));
                if (!ai.map().inside(x, y) || !ai.planner().getStartField().reachable(x, y))
                    continue;
                int reach = 0;
                for (Unit e : parked)
                    if (MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 15 * 15)
                        reach++;
                if (reach < strategy.snipe_min) {
                    rejects[0]++;
                    continue;
                }
                if (!sniperSafe(x, y)) {
                    rejects[1]++;
                    continue;
                }
                if (!ai.map().canPlace(template, x, y)) {
                    rejects[2]++;
                    continue;
                }
                if (SitePlanner.conflicts(reserved, x, y, SitePlanner.RaceSizes.TOWER)) {
                    rejects[3]++;
                    continue;
                }
                float score = reach * 10f - .05f * (float) Math.sqrt(MapAnalysis.dist2(x, y, hx, hy));
                if (score > best_score) {
                    best_score = score;
                    best = new Site(x, y, score);
                }
            }
        }
        if (best == null) {
            int m = 0;
            for (int i = 1; i < 4; i++)
                if (rejects[i] > rejects[m])
                    m = i;
            ai.aiLog().count("sniper_nospot_" + new String[]{"reach", "unsafe", "illegal", "taken"}[m]);
            return;
        }
        Project p = addProject(Race.BUILDING_TOWER, best, 1);
        p.sniper = true;
        ai.aiLog().count("sniper_planned");
        Unit s = seed;
        int n = best_n;
        ai.log("sniper tower for " + n + " parked enemies at " + s.getGridX() + "," + s.getGridY());
    }

    /** No parked enemy within 9 cells (their scan sees 8), no other enemy within 12, no enemy tower within 20. */
    private boolean sniperSafe(int x, int y) {
        Intel intel = ai.intel();
        for (List<Unit> group : List.of(intel.enemy_warriors, intel.enemy_chieftains, intel.enemy_peons))
            for (Unit e : group) {
                if (e.isDead())
                    continue;
                int dx = Math.abs(e.getGridX() - x);
                int dy = Math.abs(e.getGridY() - y);
                if (isParked(e) ? Math.max(dx, dy) <= 9 : dx * dx + dy * dy <= 12 * 12)
                    return false;
            }
        for (Building t : intel.enemy_towers)
            if (MapAnalysis.dist2(t.getGridX(), t.getGridY(), x, y) <= 20 * 20)
                return false;
        return true;
    }

    /** Keeps the army over the forward tower going up, as long as it can hold the ground. */
    private void escortForward() {
        Military military = ai.military();
        for (Project p : projects) {
            if (p.forward && military.canEscort()) {
                military.escort(p.site.x, p.site.y);
                return;
            }
        }
    }

    /** Forward tower projects, placed or not. */
    private int countForward() {
        int n = 0;
        for (Project p : projects)
            if (p.forward)
                n++;
        return n;
    }

    /** Escorts builders of forward towers, drops them when the army cannot cover them, and plans the next one. */
    private void planForwardTower() {
        Strategy strategy = ai.strategy();
        Military military = ai.military();
        for (Iterator<Project> it = projects.iterator(); it.hasNext();) {
            Project p = it.next();
            if (!p.forward || p.building != null || military.canEscort())
                continue;
            ai.log("dropping " + p.describe() + ": no cover");
            it.remove();
        }
        if (strategy.forward_towers <= 0 || ai.time() < strategy.forward_tower_time || countForward() > 0
                || forward_towers.size() >= strategy.forward_towers || !military.canEscort()
                || !ai.owner().canBuild(Race.BUILDING_TOWER))
            return;
        int[] spot = military.forwardTarget();
        if (spot == null)
            return;
        List<int[]> existing = new ArrayList<>();
        for (Building t : ai.intel().towers)
            existing.add(new int[]{t.getGridX(), t.getGridY()});
        Site site = ai.planner().findTowerSite(reservedSites(null), spot[0], spot[1], 3, 6, existing,
                ai.planner().getStartX(), ai.planner().getStartY());
        if (site == null)
            return;
        Project p = addProject(Race.BUILDING_TOWER, site, 3);
        p.forward = true;
    }

    /** An armory site next to the quarters with the fewest enemy warriors around, or null. */
    private @Nullable Site safeArmorySite() {
        Military military = ai.military();
        Building safest = null;
        float least = Float.MAX_VALUE;
        for (Building q : ai.intel().quarters) {
            float danger = military.enemyStrengthNear(q.getGridX(), q.getGridY(), 40);
            if (danger < least) {
                least = danger;
                safest = q;
            }
        }
        if (safest == null)
            return null;
        return ai.planner().findQuartersSiteLike(reservedSites(null), safest.getGridX(), safest.getGridY(), 24,
                Race.BUILDING_ARMORY);
    }

    /**
     * An enemy arming early means an attack is coming before the opening quarters would pay off: once seen, the
     * armory moves ahead of the quarters still waiting for builders.
     */
    private void checkRush() {
        Intel intel = ai.intel();
        if (rush_alert || !ai.strategy().rush_response || !intel.armories.isEmpty()
                || (ai.strategy().rush_opening_only && had_armory))
            return;
        int enemy_quarters = 0;
        boolean enemy_armory = false;
        for (Building b : intel.enemy_buildings) {
            int id = b.getTemplate().getTemplateID();
            if (id == Race.BUILDING_QUARTERS)
                enemy_quarters++;
            // A foundation is no commitment (a booming opening lays one out early too): only a standing armory is.
            else if (id == Race.BUILDING_ARMORY && b.isComplete())
                enemy_armory = true;
        }
        if (intel.enemy_warriors.size() < 6 && !(enemy_armory && enemy_quarters < ai.strategy().rush_quarters))
            return;
        rush_alert = true;
        rush_alert_time = ai.time();
        ai.log("enemy arming early (" + intel.enemy_warriors.size() + " warriors, armory " + enemy_armory + ", " + enemy_quarters + " quarters): armory first");
        for (Project p : projects)
            if (p.type == Race.BUILDING_ARMORY)
                p.priority = 1;
        projects.sort(Comparator.comparingInt(p -> p.priority));
    }

    /**
     * After an early-arming alarm, weapons come before more quarters until our warriors match the enemy's: only a few
     * builders stay on quarters and the quarters let their peons out to gather and arm.
     */
    private boolean armsRace() {
        Strategy strategy = ai.strategy();
        float ours = 0f;
        for (Unit w : ai.intel().warriors)
            ours += Combat.value(w);
        if (!rush_alert || ai.time() > rush_alert_time + strategy.rush_seconds)
            return false;
        float theirs = 0f;
        for (Unit w : ai.intel().enemy_warriors)
            theirs += Combat.value(w);
        return ours < 1.2f * theirs + 4f;
    }

    /** Early in the game, enemies in the base that our warriors cannot handle. */
    private boolean underPressure() {
        Strategy strategy = ai.strategy();
        Military military = ai.military();
        if (!strategy.pressure_response || ai.time() >= strategy.pressure_time || military.baseThreatLevel() == 0)
            return false;
        float ours = 0f;
        for (Unit w : ai.intel().warriors)
            ours += Combat.value(w);
        return ours < 1.2f * military.threatStrength() + 4f;
    }

    /**
     * Towers mostly guard the armory (tower_home_anchor: the home armory); every third one covers the quarters nearest
     * the enemy (not tower_q_anchor: the home armory).
     */
    private int @NonNull [] towerAnchor(int tower_count) {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        assert armory != null;
        Strategy strategy = ai.strategy();
        if (tower_count % 3 == 2 && !intel.quarters.isEmpty() && !strategy.tower_q_anchor) {
            Building home = homeArmory(armory);
            ai.aiLog().count("tower_q_home");
            return new int[]{home.getGridX(), home.getGridY()};
        }
        if (tower_count % 3 == 2 && !intel.quarters.isEmpty()) {
            Building exposed = null;
            float best = -1f;
            for (Building q : intel.quarters) {
                float e = ai.planner().exposure(q.getGridX(), q.getGridY());
                if (e > best && MapAnalysis.dist2(q.getGridX(), q.getGridY(), armory.getGridX(),
                        armory.getGridY()) > 20 * 20) {
                    best = e;
                    exposed = q;
                }
            }
            if (exposed != null)
                return new int[]{exposed.getGridX(), exposed.getGridY()};
        }
        if (strategy.tower_home_anchor) {
            Building home = homeArmory(armory);
            if (home != armory) {
                ai.aiLog().count("tower_home_anchor");
                return new int[]{home.getGridX(), home.getGridY()};
            }
        }
        return new int[]{armory.getGridX(), armory.getGridY()};
    }

    /**
     * The home armory (tower_home_anchor, tower_q_anchor): the finished armory nearest our start, which stays the
     * core's armory once a finished expansion becomes the primary one (choosePrimaryArmory).
     */
    private @NonNull Building homeArmory(@NonNull Building primary) {
        Building home = primary;
        int best = Integer.MAX_VALUE;
        int sx = ai.planner().getStartX();
        int sy = ai.planner().getStartY();
        for (Building a : ai.intel().armories) {
            if (a.isDead())
                continue;
            int d = MapAnalysis.dist2(sx, sy, a.getGridX(), a.getGridY());
            if (d < best) {
                best = d;
                home = a;
            }
        }
        return home;
    }

    /**
     * The k-th living enemy in turn (front_order: in slot order, farthest start first, or most base-bound waves
     * first), as {our building nearest to his start, his start}: the building his attacks go for first.
     */
    private int @Nullable [] @Nullable [] enemyFront(int k) {
        List<Player> enemies = new ArrayList<>();
        for (Player p : ai.owner().getWorld().getPlayers())
            if (ai.owner().isEnemy(p) && p.isAlive())
                enemies.add(p);
        if (enemies.isEmpty())
            return null;
        Player enemy = enemies.get(k % enemies.size());
        int order = ai.strategy().front_order;
        if (order != 0 && enemies.size() > 1) {
            int sx = ai.planner().getStartX();
            int sy = ai.planner().getStartY();
            java.util.Comparator<Player> farthest = java.util.Comparator.comparingInt(
                    p -> -MapAnalysis.dist2(sx, sy, UnitGrid.toGridCoordinate(p.getStartX()),
                            UnitGrid.toGridCoordinate(p.getStartY())));
            Shepherd shepherd = ai.shepherd();
            List<Player> sorted = new ArrayList<>(enemies);
            // A stable sort: ties stay in slot order.
            sorted.sort(order == 2 ? java.util.Comparator.<Player>comparingInt(p -> -shepherd.baseWaves(
                    p)).thenComparing(farthest) : farthest);
            Player chosen = sorted.get(k % sorted.size());
            if (chosen != enemy) {
                ai.aiLog().count("front_order");
                if (ai.logging())
                    ai.log("front tower " + k + " faces " + chosen.getPlayerInfo().getName() + " (" + shepherd.baseWaves(
                            chosen) + " base waves), not " + enemy.getPlayerInfo().getName() + " (" + shepherd.baseWaves(
                                    enemy) + ")");
            }
            enemy = chosen;
        }
        int ex = UnitGrid.toGridCoordinate(enemy.getStartX());
        int ey = UnitGrid.toGridCoordinate(enemy.getStartY());
        List<Building> own = new ArrayList<>(ai.intel().quarters);
        own.addAll(ai.intel().armories);
        Building nearest = null;
        int best = Integer.MAX_VALUE;
        for (Building b : own) {
            int d = MapAnalysis.dist2(ex, ey, b.getGridX(), b.getGridY());
            if (d < best) {
                best = d;
                nearest = b;
            }
        }
        if (nearest == null)
            return null;
        return new int[][]{{nearest.getGridX(), nearest.getGridY()}, {ex, ey}};
    }

    /** Projects of a type, optionally only those still unplaced. */
    private int countProjects(int type, boolean unplaced_only) {
        int n = 0;
        for (Project p : projects) {
            if (p.type != type)
                continue;
            if (unplaced_only && p.isPlaced())
                continue;
            if (!unplaced_only && p.isPlaced())
                continue; // placed ones are counted from the intel as sites
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Quarters

    /** Peons a quarters should keep inside right now. */
    int holdFor(@NonNull Building quarters) {
        Strategy strategy = ai.strategy();
        Player owner = ai.owner();
        int pop = owner.getUnitCountContainer().getNumSupplies();
        int max = owner.getWorld().getMaxUnitCount();
        boolean training = quarters.getChieftainContainer() != null && quarters.getChieftainContainer().isTraining();
        // Training takes 40 breed ticks of the trainer, 440 / n^(1/3) s with n inside, and goes on at the unit cap.
        if (training && strategy.chief_topup_any)
            return strategy.hold_chieftain;
        if (pop >= max - 2)
            return 0;
        if (training)
            return strategy.hold_chieftain;
        if (ai.intel().armories.isEmpty() && !ai.intel().quarters.isEmpty() && needsBuilders())
            return Math.min(2, strategy.hold_early);
        int hold;
        if (pop > max * 7 / 10)
            hold = strategy.hold_late;
        else {
            hold = ai.time() < strategy.hold_mid_time ? strategy.hold_early : strategy.hold_mid;
            if (armsRace())
                hold = Math.min(2, hold);
        }
        if (strategy.hold_backlog > 0 && backlog_on && hold > strategy.hold_early) {
            hold = strategy.hold_early;
            ai.aiLog().count("hold_backlog_ticks");
        }
        return hold;
    }

    /** hold_backlog: ore waits in the main armory for workers; since when. */
    private boolean backlog_on;
    private float backlog_since;

    private boolean needsBuilders() {
        for (Project p : projects)
            if (p.isPlaced() && builderCount(p.building) < buildersWanted(p))
                return true;
        return false;
    }

    /** Against a single enemy, threats pass and hiding is cheap; against several the base is never quiet. */
    private boolean gatherUnderThreat() {
        return ai.strategy().gather_under_threat && (ai.enemiesAlive() > 1 || armsRace() || underPressure()
                || ai.strategy().gather_threat_1v1);
    }

    private void manageQuarters() {
        Intel intel = ai.intel();
        boolean threatened = ai.military().baseThreatLevel() > 1;
        for (Building q : intel.quarters) {
            if (evacuating.containsKey(q))
                continue;
            int inside = q.getUnitContainer().getNumSupplies();
            int hold = holdFor(q);
            // Peons are safe inside while enemies roam next to the quarters.
            if (threatened && ai.military().threatNearEcon(q.getGridX(), q.getGridY(), gatherUnderThreat() ? 12 : 20))
                continue;
            if (danger_idle && inside > hold && !needsBuilders()) {
                ai.aiLog().count("hold_danger");
                continue;
            }
            if (inside > hold) {
                ai.owner().deployUnits(q, DeployType.PEON, inside - hold);
                if (backlog_on)
                    for (int i = 0; i < inside - hold; i++)
                        ai.aiLog().count("hold_backlog_deployed");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Armory

    private void refreshArmoryField() {
        Building armory = ai.intel().armory();
        if (armory == null)
            return;
        if (armory_field == null || armory_field_owner != armory || ai.time() - armory_field_time > 60f) {
            armory_field = ai.map().computeField(armory.getGridX(), armory.getGridY(), 400);
            armory_field_owner = armory;
            armory_field_time = ai.time();
        }
    }

    /**
     * With several armories, new peons and gatherers go to the newest finished one: it was opened because the
     * supplies around the old one ran low.
     */
    private void choosePrimaryArmory() {
        Intel intel = ai.intel();
        Building primary = null;
        if (expansion != null && !expansion.isDead() && expansion.isComplete())
            primary = expansion;
        intel.setPrimaryArmory(primary);
        // quarters_rally: peons a quarters sends out walk into the armory its rally point names; without one they
        // enter the nearest armory, often the drained old one once the expansion is primary.
        Building armory = intel.armory();
        if (ai.strategy().quarters_rally && armory != null && armory != rally_armory) {
            rally_armory = armory;
            for (Building q : intel.quarters)
                if (!q.isDead() && q.isComplete() && !evacuating.containsKey(q))
                    ai.owner().setRallyPoint(q, armory);
        }
    }

    private @Nullable Building rally_armory;

    // rock_stream: measured gatherer-seconds per unit of iron and rock over the last 30 s.
    private float meas_start = -1f;
    private int meas_iron0;
    private int meas_rock0;
    private float meas_iron_gs;
    private float meas_rock_gs;
    private float iron_s = 0f;
    private float rock_s = 0f;
    private boolean rock_stream_on;

    /** Every economy tick: gatherer-seconds on iron and rock, turned into seconds per unit every 30 s. */
    private void measureYield() {
        Player owner = ai.owner();
        if (meas_start < 0f) {
            meas_start = ai.time();
            meas_iron0 = owner.getIronHarvested();
            meas_rock0 = owner.getRockHarvested();
        }
        Intel intel = ai.intel();
        meas_iron_gs += intel.countPeons(PeonState.GATHER_IRON);
        meas_rock_gs += intel.countPeons(PeonState.GATHER_ROCK);
        if (ai.time() - meas_start < 30f)
            return;
        int di = owner.getIronHarvested() - meas_iron0;
        int dr = owner.getRockHarvested() - meas_rock0;
        if (meas_iron_gs >= 60f)
            iron_s = meas_iron_gs / Math.max(.5f, di);
        if (meas_rock_gs >= 60f)
            rock_s = meas_rock_gs / Math.max(.5f, dr);
        meas_start = ai.time();
        meas_iron0 = owner.getIronHarvested();
        meas_rock0 = owner.getRockHarvested();
        meas_iron_gs = 0f;
        meas_rock_gs = 0f;
    }

    /**
     * Opens a second armory next to fresh iron once the first one's surroundings are mined out, when the walk saved
     * on every future warrior is worth the forty pieces of wood. Only one expansion at a time; the old armory keeps
     * turning its remaining stock into weapons and then sends its peons over.
     */
    private void considerExpansion() {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        if (armory == null || armory_field == null || ai.time() < 300f || ai.time() - last_expansion_check < 30f
                || !ai.strategy().expansion)
            return;
        boolean under_threat = ai.strategy().expand_under_threat;
        if (under_threat)
            last_expansion_check = ai.time();
        if ((!under_threat && ai.military().baseThreatLevel() > 0) || countProjects(Race.BUILDING_ARMORY, false) > 0)
            return;
        if (!ai.owner().canBuild(Race.BUILDING_ARMORY))
            return;
        last_expansion_check = ai.time();
        float current = ai.planner().warriorGatherCost(armory_field);
        if (iron_cycle < 70f && current < 110f)
            return;
        Site site = ai.planner().findExpansionSite(reservedSites(null), armory_field);
        float better = Float.MAX_VALUE;
        if (site != null)
            better = ai.planner().warriorGatherCost(ai.map().computeField(site.x, site.y, 220));
        if (ai.strategy().far_expansion && better > .75f * current) {
            // The whole neighbourhood is mined out: fresh iron further away pays for the walk.
            Site far = ai.planner().findFarExpansionSite(reservedSites(null));
            if (far != null && -far.score < Math.min(better, .75f * current)) {
                site = far;
                better = -far.score;
            }
        }
        if (site == null)
            return;
        ai.log(String.format("expansion check: current armory %.0f (iron %.0fs), best site %d,%d %.0f", current,
                iron_cycle, site.x, site.y, better));
        float gain = iron_cycle >= ai.strategy().desperate_iron_cycle ? ai.strategy().desperate_expansion : .75f;
        if (better > gain * current)
            return;
        if (under_threat && ai.military().baseThreatLevel() > 0) {
            if (!quietSite(armory, site.x, site.y)) {
                ai.aiLog().count("exp_blocked_site");
                return;
            }
            ai.aiLog().count("exp_under_threat");
        }
        Project p = addProject(Race.BUILDING_ARMORY, site, 1);
        expansion_project = p;
    }

    /** expand_under_threat: no threat or enemy warrior within 30 cells of the site, none within 12 of the way there. */
    private boolean quietSite(@NonNull Building armory, int x, int y) {
        Military military = ai.military();
        if (military.threatNear(x, y, 30) || military.enemyStrengthNear(x, y, 30) > 0f)
            return false;
        int ax = armory.getGridX();
        int ay = armory.getGridY();
        float len = (float) Math.sqrt(MapAnalysis.dist2(ax, ay, x, y));
        int steps = Math.max(1, (int) (len / 6f));
        for (int i = 0; i <= steps; i++) {
            int px = ax + Math.round((x - ax) * i / (float) steps);
            int py = ay + Math.round((y - ay) * i / (float) steps);
            for (Unit e : ai.intel().enemy_warriors)
                if (!e.isDead() && MapAnalysis.dist2(px, py, e.getGridX(), e.getGridY()) <= 12 * 12)
                    return false;
        }
        return true;
    }

    @Nullable
    DistanceField armoryField() {
        return ai.intel().armory() != null ? armory_field : null;
    }

    private void manageArmory() {
        Intel intel = ai.intel();
        Building primary = intel.armory();
        if (primary == null)
            return;
        for (Building armory : intel.armories) {
            orderWeapons(armory);
            if (armory == primary)
                deployFromPrimary(armory);
            else
                drainSecondary(armory);
        }
    }

    /**
     * An armory that is no longer the main one turns what it has into warriors and then sends its peons over to the
     * main armory.
     */
    private void drainSecondary(@NonNull Building armory) {
        Player owner = ai.owner();
        if (ai.strategy().recall_old_gatherers && ai.time() - last_old_recall >= 10f) {
            last_old_recall = ai.time();
            PeonState[] states = {PeonState.GATHER_TREE, PeonState.GATHER_IRON, PeonState.GATHER_ROCK, PeonState.GATHER_CHICKEN};
            Class<?>[] types = {TreeSupply.class, IronSupply.class, RockSupply.class, RubberSupply.class};
            int recalled = 0;
            for (int t = 0; t < states.length; t++) {
                int n = ai.intel().countLinkedGatherers(states[t], armory);
                if (n > 0) {
                    owner.recallGatherers(armory, supplyClass(types[t]), n);
                    recalled += n;
                }
            }
            if (recalled > 0)
                ai.log("recalling " + recalled + " gatherers of the old armory at " + armory.getGridX() + "," + armory.getGridY());
        }
        int workers = armory.getUnitContainer().getNumSupplies();
        if (workers == 0)
            return;
        int iron = armory.getSupplyContainer(IronAxeWeapon.class).getNumSupplies();
        int chicken = armory.getSupplyContainer(RubberAxeWeapon.class).getNumSupplies();
        int rock = armory.getSupplyContainer(RockAxeWeapon.class).getNumSupplies();
        int c = Math.min(chicken, workers);
        if (c > 0)
            owner.deployUnits(armory, DeployType.RUBBER_WARRIOR, c);
        int i = Math.min(iron, workers - c);
        if (i > 0)
            owner.deployUnits(armory, DeployType.IRON_WARRIOR, i);
        int r = Math.min(rock, workers - c - i);
        if (r > 0)
            owner.deployUnits(armory, DeployType.ROCK_WARRIOR, r);
        int left = workers - c - i - r;
        boolean can_make = armory.getSupplyContainer(TreeSupply.class).getNumSupplies() >= 2
                && (armory.getSupplyContainer(IronSupply.class).getNumSupplies() >= 1
                        || ((rock_weapons || rock_filler)
                                && armory.getSupplyContainer(RockSupply.class).getNumSupplies() >= 1));
        int pending = armory.getDeployContainer(DeployType.PEON).getNumSupplies();
        boolean refuge = armory == refuge_armory && ai.time() < refuge_until;
        if (!can_make && left > 0 && pending == 0 && !refuge)
            owner.deployUnits(armory, DeployType.PEON, left);
    }

    private void orderWeapons(@NonNull Building armory) {
        Player owner = ai.owner();
        // weapon_sync holds a queue by cancelling its orders: leave those to it
        boolean synced = armory == sync_armory;
        if (!(synced && sync_paused[SYNC_IRON])
                && armory.getBuildSupplyContainer(IronAxeWeapon.class).getNumSupplies() == 0)
            owner.buildIronWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        if (!(synced && sync_paused[SYNC_RUBBER]) && owner.canUseRubber()
                && armory.getBuildSupplyContainer(RubberAxeWeapon.class).getNumSupplies() == 0)
            owner.buildRubberWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        int rock_orders = armory.getBuildSupplyContainer(RockAxeWeapon.class).getNumSupplies();
        boolean make_rock = wantsRockWeapons();
        if (make_rock && rock_orders == 0 && !(synced && sync_paused[SYNC_ROCK]))
            owner.buildRockWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        else if (!make_rock && rock_orders > 0 && !(synced && (sync_helping & 1 << SYNC_ROCK) != 0))
            owner.buildRockWeapons(armory, -rock_orders, false);
    }

    private boolean wantsRockWeapons() {
        return rock_weapons || rock_filler || ai.strategy().rock_share > 0f || rock_stream_on;
    }

    private void deployFromPrimary(@NonNull Building armory) {
        Player owner = ai.owner();
        int workers = armory.getUnitContainer().getNumSupplies();
        int iron = armory.getSupplyContainer(IronAxeWeapon.class).getNumSupplies();
        int chicken = armory.getSupplyContainer(RubberAxeWeapon.class).getNumSupplies();
        int rock = armory.getSupplyContainer(RockAxeWeapon.class).getNumSupplies();
        int stock = iron + chicken + rock;
        if (stock == 0 || workers == 0)
            return;

        Military military = ai.military();
        int pop = owner.getUnitCountContainer().getNumSupplies();
        boolean capped = pop >= owner.getWorld().getMaxUnitCount() - 3;
        int deploy;
        if (military.wantsEverything() || capped) {
            deploy = stock;
        } else {
            deploy = 0;
            // Chicken warriors go straight into towers.
            deploy = Math.max(deploy, Math.min(chicken, military.towerSeatsFree()));
            // Keep a standing army able to handle raids; beyond that weapons wait in the armory while its peons
            // keep working, and come out when the army needs them or production outgrows gathering.
            float deficit = military.armyStrengthWanted() - military.armyStrength();
            if (deficit > 0)
                deploy = Math.max(deploy, (int) Math.ceil(deficit));
            if (workers > want_workers + 2)
                deploy = Math.max(deploy, workers - want_workers);
            if (stock > 12)
                deploy = Math.max(deploy, stock - 12);
        }
        int keep = military.wantsEverything() ? 0 : Math.min(2, workers);
        deploy = Math.min(deploy, Math.min(stock, workers - keep));
        if (deploy <= 0)
            return;
        int c = Math.min(chicken, deploy);
        if (c > 0)
            owner.deployUnits(armory, DeployType.RUBBER_WARRIOR, c);
        int i = Math.min(iron, deploy - c);
        if (i > 0)
            owner.deployUnits(armory, DeployType.IRON_WARRIOR, i);
        int r = Math.min(rock, deploy - c - i);
        if (r > 0)
            owner.deployUnits(armory, DeployType.ROCK_WARRIOR, r);
    }

    // ------------------------------------------------------------------------------------------------------------
    // weapon_sync
    //
    // Every tick, WeaponsProducer.animate gives each weapon queue that has orders and the resources for one weapon an
    // equal share of the armory's man-seconds (peons inside x tick / queues). A queue takes its weapon's cost only on
    // the tick the weapon is done (BuildProductionContainer.build), clamped at zero, and keeps its progress while it
    // has no orders. So weapons done on the same tick share the resources they have in common: an iron axe and a
    // rubber axe one iron, a rock axe and a rubber axe one rock, all three one iron, one rock and two wood (when the
    // stock holds just that). While the main armory can forge a rubber axe and the iron (or rock, weapon_sync_three)
    // it shares with the iron (rock) axe is short, the queues that would finish first are paused by cancelling their
    // orders until the rubber axe catches up, and ordered again so that all finish on the same tick. The world ticks
    // buildings before the AI (World.tick: game-time pass, then the real-time pass the AI is on), so an order given
    // in tick T counts from the producer's tick T+1; weaponSync plans after all our other orders of the tick, from the
    // queues' man-seconds added up exactly as the engine does in floats.

    /** The armory's weapon queues in the order WeaponsProducer builds them (LandBuilding): rock, iron, rubber axes. */
    private static final int SYNC_ROCK = 0;
    private static final int SYNC_IRON = 1;
    private static final int SYNC_RUBBER = 2;
    private static final String[] SYNC_NAMES = {"rock", "iron", "rubber"};
    private static final Class<?>[] SYNC_WEAPONS = {RockAxeWeapon.class, IronAxeWeapon.class, RubberAxeWeapon.class};
    /** Man-seconds per weapon of each queue (LandBuilding). */
    private static final float[] SYNC_WORK = {40f, 80f, 120f};
    /** The resources, and what a weapon of each queue costs in them (LandBuilding.COST_*_WEAPON). */
    private static final Class<?>[] SYNC_SUPPLIES = {TreeSupply.class, RockSupply.class, IronSupply.class, RubberSupply.class};
    private static final String[] SYNC_SUPPLY_NAMES = {"tree", "rock", "iron", "rubber"};
    private static final int[][] SYNC_COST = {{2, 1, 0, 0}, {2, 0, 1, 0}, {2, 1, 1, 1}};
    /** Ticks ahead within which the queues' finishing ticks are worked out exactly (and a plan searched for). */
    private static final int SYNC_HORIZON = 250;
    /** Most ticks of pauses a plan may take before all the queues run together. */
    private static final int SYNC_DEPTH = 5;
    /** Cells around the armory within which a carried load could reach its stock in one tick. */
    private static final int SYNC_REACH = 12;

    /** The armory whose queues weapon_sync runs, and the queues it holds by having cancelled their orders. */
    private @Nullable Building sync_armory;
    private final boolean[] sync_paused = new boolean[3];
    /** What the armory held after our last orders of tick sync_tick. */
    private int sync_tick = -100;
    private final float[] sync_progress = new float[3];
    private final float[] sync_ms = new float[3];
    private final int[] sync_weapons = new int[3];
    private final int[] sync_stock = new int[4];
    /** Each queue's man-seconds after the next tick, if the world does what the orders mean. */
    private final float[] sync_expect = new float[3];
    /** Loads carried next to the armory at the snapshot ({supply, amount}): they may reach its stock next tick. */
    private final Map<@NonNull Unit, int @NonNull []> sync_carried = new LinkedHashMap<>();
    /** The alignment under way: its queues (a bit each, 0 = none), its number, and the tick due (0 = not aligned). */
    private int sync_targets;
    /** A queue the alignment under way holds back because the rubber axe meets only its partner (a bit, 0 = none). */
    private int sync_held;
    /**
     * The rock queue ordered for a tick or two although the weapon policy wants no rock axes, only to change the
     * others' shares (a bit, 0 = none): with the iron and rubber axes alone every tick moves them by one or two
     * half-shares, which cannot line up remainders an odd number of half-shares apart.
     */
    private int sync_helping;
    private int sync_attempts;
    private int sync_due;
    /** The planner's inputs when a search last found nothing while every queue ran: not searched again meanwhile. */
    private long sync_failed = -1;
    /** Queue ticks whose man-seconds came out as the orders meant, and those that did not (the order latency). */
    private int sync_expect_ok;
    private int sync_expect_off;
    private int sync_rubber;

    /**
     * weapon_sync, first thing in a tick: what the world's tick did in the main armory, against the snapshot taken
     * after our last orders. A queue whose progress went back to zero while its weapon stock grew finished a weapon.
     * Weapons finished together duplicated a shared resource when its stock fell by less than their costs and could
     * not have paid them even with every load that could have come in during the tick.
     */
    void weaponSyncObserve() {
        Building armory = sync_armory;
        if (armory == null || armory.isDead() || sync_tick != ai.ticks() - 1)
            return;
        int done = 0;
        for (int c = 0; c < 3; c++) {
            float progress = syncQueue(armory, c).getBuildProgress();
            int weapons = armory.getSupplyContainer(SYNC_WEAPONS[c]).getNumSupplies();
            if (sync_progress[c] > 0f && progress == 0f && weapons > sync_weapons[c])
                done |= 1 << c;
        }
        if (done == 0)
            return;
        int[] stock = syncStock(armory);
        int[] delivered = new int[4];
        for (Map.Entry<Unit, int[]> e : sync_carried.entrySet()) {
            UnitSupplyContainer load = e.getKey().getSupplyContainer();
            int[] was = e.getValue();
            int now = load != null && syncSupplyIndex(load.getSupplyType()) == was[0] ? load.getNumSupplies() : 0;
            delivered[was[0]] += Math.max(0, was[1] - now);
        }
        boolean dup = false;
        if (Integer.bitCount(done) >= 2) {
            for (int s = 0; s < 4; s++) {
                int users = 0;
                int cost = 0;
                for (int c = 0; c < 3; c++) {
                    if ((done & 1 << c) != 0 && SYNC_COST[c][s] > 0) {
                        users++;
                        cost += SYNC_COST[c][s];
                    }
                }
                if (users >= 2 && sync_stock[s] - stock[s] < cost && sync_stock[s] + delivered[s] < cost)
                    dup = true;
            }
        }
        if ((done & 1 << SYNC_RUBBER) != 0)
            sync_rubber++;
        if (dup)
            ai.aiLog().count("weapon_sync_dup");
        int targets = sync_targets;
        String outcome = "";
        if (targets != 0 && (done & targets) != 0) {
            boolean hit = (done & targets) == targets;
            if (!hit)
                ai.aiLog().count("weapon_sync_missed");
            outcome = String.format("#%d %s (%s, due %s)", sync_attempts, hit ? "hit" : "missed",
                    syncNames(targets), sync_due > 0 ? Integer.toString(sync_due - ai.ticks()) : "-");
            sync_targets = 0;
            sync_due = 0;
            sync_held = 0;
        }
        if (targets != 0 || dup || Integer.bitCount(done) >= 2 || (done & 1 << SYNC_RUBBER) != 0) {
            String what = outcome;
            boolean duplicated = dup;
            int finished = done;
            ai.aiLog().log("WSYNC", () -> {
                StringBuilder sb = new StringBuilder();
                sb.append(what.isEmpty() ? "done" : what).append(": ").append(syncNames(finished));
                if ((finished & 1 << SYNC_RUBBER) != 0)
                    sb.append(" (rubber axe ").append(sync_rubber).append(')');
                sb.append(duplicated ? ", dup" : "").append(", stock");
                for (int s = 0; s < 4; s++) {
                    sb.append(' ').append(SYNC_SUPPLY_NAMES[s]).append(' ').append(sync_stock[s]).append("->").append(
                            stock[s]);
                    if (delivered[s] > 0)
                        sb.append(" (<=").append(delivered[s]).append(" in)");
                }
                sb.append(", predicted ").append(sync_expect_ok).append('/').append(sync_expect_ok + sync_expect_off);
                return sb.toString();
            });
        }
    }

    /**
     * weapon_sync, last thing in a tick, after all our other orders: chooses the main armory's queues that run in the
     * next tick and takes the snapshot the next tick's weaponSyncObserve compares with.
     */
    void weaponSync(float t) {
        Player owner = ai.owner();
        Building armory = ai.intel().armory();
        if (armory != null && (armory.isDead() || !armory.isComplete()))
            armory = null;
        if (armory != sync_armory) {
            syncRelease("main armory changed");
            sync_armory = armory;
            sync_tick = -100;
        }
        if (armory == null || !owner.isAlive())
            return;
        boolean checked = sync_tick == ai.ticks() - 1;
        BuildProductionContainer[] queues = new BuildProductionContainer[3];
        float[] ms = new float[3];
        for (int c = 0; c < 3; c++) {
            queues[c] = syncQueue(armory, c);
            ms[c] = syncManSeconds(queues[c].getBuildProgress(), SYNC_WORK[c], checked ? sync_expect[c] : Float.NaN,
                    checked ? sync_ms[c] : Float.NaN);
            if (checked && ms[c] == sync_expect[c])
                sync_expect_ok++;
            else if (checked)
                sync_expect_off++;
        }
        int workers = armory.getUnitContainer().getNumSupplies();
        int[] stock = syncStock(armory);
        // the game-time step, as World.tick gives it to the buildings
        float step = owner.getWorld().getSecondsPerTick() * t / AnimationManager.ANIMATION_SECONDS_PER_TICK;
        boolean three = ai.strategy().weapon_sync_three;
        boolean[] wanted = {wantsRockWeapons(), true, owner.canUseRubber()};
        // avail: the queues that can run next tick; forced: those among them we leave as the weapon policy has them;
        // helpers: the rock queue when the policy wants no rock axes, which a plan may run for a tick or two
        int avail = 0;
        int forced = 0;
        int helpers = 0;
        for (int c = 0; c < 3; c++) {
            boolean pausable = c != SYNC_ROCK || three;
            if (wanted[c] || !pausable)
                sync_helping &= ~(1 << c);
            if (!syncAffordable(stock, c))
                continue;
            if (wanted[c] && pausable)
                avail |= 1 << c;
            else if ((sync_helping & 1 << c) != 0 || (pausable && queues[c].getNumSupplies() == 0))
                helpers |= 1 << c;
            else if (queues[c].getNumSupplies() > 0) {
                avail |= 1 << c;
                forced |= 1 << c;
            }
        }
        int free = avail & ~forced;
        int targets = 0;
        if ((free & 1 << SYNC_RUBBER) != 0) {
            for (int c = SYNC_ROCK; c <= SYNC_IRON; c++)
                if ((free & 1 << c) != 0 && syncShortShared(stock, c))
                    targets |= 1 << c;
            if (targets != 0)
                targets |= 1 << SYNC_RUBBER;
        }
        if (targets == 0) {
            if (sync_targets != 0)
                syncRelease((free & 1 << SYNC_RUBBER) == 0 ? "no rubber axe to forge" : "nothing short");
            else
                syncRelease("");
            syncSnapshot(armory, queues, ms, stock, workers, step);
            return;
        }
        // an alignment narrowed to a pair holds the third queue while that one is still short
        int held = sync_held;
        if (sync_targets != 0 && held != 0 && (targets & held) == held
                && Integer.bitCount(targets & ~held) >= 2) {
            targets &= ~held;
            avail &= ~held;
        } else
            sync_held = 0;
        if (sync_targets == 0) {
            sync_attempts++;
            sync_failed = -1;
            int shown = targets;
            ai.aiLog().log("WSYNC", () -> String.format("#%d start %s: %s, %d peons", sync_attempts,
                    syncNames(shown), syncState(ms, stock), workers));
        }
        sync_targets = targets;
        if (workers > 0) {
            int run = syncPlan(ms, avail, forced, helpers, workers, step);
            for (int c = 0; c < 3; c++) {
                if ((forced & 1 << c) != 0 || (c == SYNC_ROCK && !three))
                    continue;
                int orders = queues[c].getNumSupplies();
                boolean helper = (helpers & 1 << c) != 0;
                int queue = c;
                if ((run & 1 << c) != 0 && orders == 0) {
                    syncOrder(armory, c, BuildSpinner.INFINITE_LIMIT, true);
                    sync_paused[c] = false;
                    if (helper)
                        sync_helping |= 1 << c;
                    ai.aiLog().log("WSYNC", () -> String.format("#%d %s %s: %s", sync_attempts,
                            helper ? "helper on" : "resume", SYNC_NAMES[queue], syncState(ms, stock)));
                } else if ((run & 1 << c) == 0 && ((avail | sync_held | helpers) & 1 << c) != 0 && orders > 0) {
                    syncOrder(armory, c, -orders, false);
                    if (helper) {
                        sync_helping &= ~(1 << c);
                        ai.aiLog().log("WSYNC", () -> String.format("#%d helper off %s: %s", sync_attempts,
                                SYNC_NAMES[queue], syncState(ms, stock)));
                    } else {
                        sync_paused[c] = true;
                        ai.aiLog().count("weapon_sync_pause");
                        ai.aiLog().log("WSYNC", () -> String.format("#%d pause %s: %s", sync_attempts,
                                SYNC_NAMES[queue], syncState(ms, stock)));
                    }
                }
            }
        }
        syncSnapshot(armory, queues, ms, stock, workers, step);
    }

    /**
     * The queues to run in the next tick: all of avail once the targets would finish on the same tick that way; else,
     * once they are within SYNC_DEPTH ticks of each other, the first tick of the shortest plan of pauses (which may
     * run the helpers) after which they would; else the targets furthest behind catch up while the others wait. When
     * three have caught up and cannot meet, the rubber axe meets the iron axe, else the rock axe, and the third one
     * waits. Sets sync_targets to the queues the plan aligns, and sync_due to the tick they finish (0: not aligned).
     */
    private int syncPlan(float @NonNull [] ms, int avail, int forced, int helpers, int workers, float step) {
        int targets = sync_targets;
        int k = syncAlignedIn(ms, avail, targets, workers, step);
        if (k > 0)
            return syncAligned(avail, targets, k);
        sync_due = 0;
        int catch_up = syncCatchUp(ms, avail, targets, workers, step);
        boolean close = k == 0 && syncSpread(ms, targets) <= SYNC_DEPTH * (workers * step);
        long key = ((long) workers << 16) | ((long) helpers << 12) | ((long) avail << 8) | ((long) forced << 4) | targets;
        if (close && key != sync_failed) {
            int run = syncSearch(ms, avail, forced, helpers, targets, workers, step);
            if (run != 0)
                return run;
            if (catch_up == avail && Integer.bitCount(targets) == 3) {
                for (int drop = SYNC_ROCK; drop <= SYNC_IRON; drop++) {
                    int pair = targets & ~(1 << drop);
                    int rest = avail & ~(1 << drop);
                    int kp = syncAlignedIn(ms, rest, pair, workers, step);
                    if (kp > 0) {
                        syncHold(drop, pair);
                        return syncAligned(rest, pair, kp);
                    }
                    if (kp == 0) {
                        run = syncSearch(ms, rest, forced, helpers, pair, workers, step);
                        if (run != 0) {
                            syncHold(drop, pair);
                            return run;
                        }
                    }
                }
            }
        }
        // Caught up and still apart: the queues run as they are until something changes (the peons inside, the
        // queues that can run), which is when a new search may find a plan.
        sync_failed = close && catch_up == avail ? key : -1;
        return catch_up;
    }

    /** How far apart the targets' remaining man-seconds are. */
    private static float syncSpread(float @NonNull [] ms, int targets) {
        float lo = Float.MAX_VALUE;
        float hi = 0f;
        for (int c = 0; c < 3; c++) {
            if ((targets & 1 << c) != 0) {
                lo = Math.min(lo, SYNC_WORK[c] - ms[c]);
                hi = Math.max(hi, SYNC_WORK[c] - ms[c]);
            }
        }
        return hi - lo;
    }

    /** Runs all of run, which makes the targets finish together in k ticks. */
    private int syncAligned(int run, int targets, int k) {
        int due = ai.ticks() + k;
        if (due != sync_due)
            ai.aiLog().log("WSYNC", () -> String.format("#%d aligned %s: due in %d ticks", sync_attempts,
                    syncNames(targets), k));
        sync_due = due;
        return run;
    }

    /** Narrows the alignment under way to the pair, holding queue drop until it is over. */
    private void syncHold(int drop, int pair) {
        sync_targets = pair;
        sync_held = 1 << drop;
        ai.aiLog().log("WSYNC", () -> String.format("#%d three cannot meet: %s, holding %s", sync_attempts,
                syncNames(pair), SYNC_NAMES[drop]));
    }

    /**
     * Ticks until the targets finish, running all of run from man-seconds ms, when they finish on the same tick; 0
     * when they finish apart, -1 when one of them is more than SYNC_HORIZON ticks away.
     */
    private static int syncAlignedIn(float @NonNull [] ms, int run, int targets, int workers, float step) {
        float share = workers * step / Integer.bitCount(run);
        int k = 0;
        boolean apart = false;
        for (int c = 0; c < 3; c++) {
            if ((targets & 1 << c) == 0)
                continue;
            int kc = syncTicksToFinish(ms[c], SYNC_WORK[c], share);
            if (kc > SYNC_HORIZON)
                return -1;
            if (k == 0)
                k = kc;
            else if (kc != k)
                apart = true;
        }
        return apart ? 0 : k;
    }

    /**
     * Ticks until a queue at man-seconds ms finishes, adding share each tick as BuildProductionContainer.build does.
     */
    private static int syncTicksToFinish(float ms, float work, float share) {
        if (share <= 0f)
            return SYNC_HORIZON + 1;
        float m = ms;
        for (int k = 1; k <= SYNC_HORIZON; k++) {
            m += share;
            if (m >= work)
                return k;
        }
        return SYNC_HORIZON + 1;
    }

    /**
     * The first tick of the plan with the fewest ticks, then the fewest paused and helping queue-ticks, of up to
     * SYNC_DEPTH ticks that run subsets of avail and helpers (never pausing forced) after which the targets finish
     * together running all of avail, or finish together within it; 0 when there is none. A plan is a multiset of
     * ticks tried in one order, so the rest of it is found again in the next tick.
     */
    private static int syncSearch(float @NonNull [] ms, int avail, int forced, int helpers, int targets, int workers,
            float step) {
        int[] moves = new int[7];
        int m = 0;
        for (int s = 1; s < 8; s++)
            if ((s & ~(avail | helpers)) == 0 && (s & forced) == forced && s != avail)
                moves[m++] = s;
        if (m == 0)
            return 0;
        int[] seq = new int[SYNC_DEPTH];
        float[] sim = new float[3];
        for (int depth = 1; depth <= SYNC_DEPTH; depth++) {
            Arrays.fill(seq, 0);
            int best = 0;
            int best_pauses = Integer.MAX_VALUE;
            while (true) {
                int pauses = 0;
                for (int i = 0; i < depth; i++)
                    pauses += Integer.bitCount(avail & ~moves[seq[i]]) + Integer.bitCount(helpers & moves[seq[i]]);
                if (pauses < best_pauses
                        && syncPlanWorks(ms, sim, moves, seq, depth, avail, helpers, targets, workers, step)) {
                    best = moves[seq[0]];
                    best_pauses = pauses;
                }
                int i = depth - 1;
                while (i >= 0 && seq[i] == m - 1)
                    i--;
                if (i < 0)
                    break;
                seq[i]++;
                for (int j = i + 1; j < depth; j++)
                    seq[j] = seq[i];
            }
            if (best != 0)
                return best;
        }
        return 0;
    }

    private static boolean syncPlanWorks(float @NonNull [] ms, float @NonNull [] sim, int @NonNull [] moves,
            int @NonNull [] seq, int depth, int avail, int helpers, int targets, int workers, float step) {
        System.arraycopy(ms, 0, sim, 0, 3);
        for (int i = 0; i < depth; i++) {
            int run = moves[seq[i]];
            float share = workers * step / Integer.bitCount(run);
            int finished = 0;
            for (int c = 0; c < 3; c++) {
                if ((run & 1 << c) == 0)
                    continue;
                float v = sim[c] + share;
                if (v >= SYNC_WORK[c]) {
                    finished |= 1 << c;
                    v = 0f;
                }
                sim[c] = v;
            }
            // a helper must not finish a weapon the policy did not want (it would take the rock the rubber axe needs)
            if ((finished & helpers) != 0)
                return false;
            if ((finished & targets) != 0)
                return (finished & targets) == targets;
        }
        return syncAlignedIn(sim, avail, targets, workers, step) > 0;
    }

    /** The targets with the most man-seconds left run, those more than a tick of all the peons' work ahead wait. */
    private static int syncCatchUp(float @NonNull [] ms, int avail, int targets, int workers, float step) {
        float top = 0f;
        for (int c = 0; c < 3; c++)
            if ((targets & 1 << c) != 0)
                top = Math.max(top, SYNC_WORK[c] - ms[c]);
        float slack = workers * step;
        int run = avail;
        for (int c = 0; c < 3; c++)
            if ((targets & 1 << c) != 0 && top - (SYNC_WORK[c] - ms[c]) > slack)
                run &= ~(1 << c);
        return run;
    }

    /** Ends the alignment under way (logging why, when there was one) and orders the queues it held again. */
    private void syncRelease(@NonNull String why) {
        if (sync_targets != 0) {
            int n = sync_attempts;
            ai.aiLog().log("WSYNC", () -> String.format("#%d released: %s", n, why));
            sync_targets = 0;
            sync_due = 0;
        }
        sync_held = 0;
        Building armory = sync_armory;
        boolean alive = armory != null && !armory.isDead() && armory.isComplete();
        if (sync_helping != 0 && alive && !wantsRockWeapons()) {
            int orders = syncQueue(armory, SYNC_ROCK).getNumSupplies();
            if (orders > 0)
                syncOrder(armory, SYNC_ROCK, -orders, false);
        }
        sync_helping = 0;
        for (int c = 0; c < 3; c++) {
            if (!sync_paused[c])
                continue;
            sync_paused[c] = false;
            boolean wanted = c == SYNC_ROCK ? wantsRockWeapons() : c == SYNC_IRON || ai.owner().canUseRubber();
            if (alive && wanted && syncQueue(armory, c).getNumSupplies() == 0)
                syncOrder(armory, c, BuildSpinner.INFINITE_LIMIT, true);
        }
    }

    /** Remembers what the armory holds after our orders, and what the next tick should make of its queues. */
    private void syncSnapshot(@NonNull Building armory, @NonNull BuildProductionContainer @NonNull [] queues,
            float @NonNull [] ms, int @NonNull [] stock, int workers, float step) {
        int run = 0;
        for (int c = 0; c < 3; c++)
            if (queues[c].getNumSupplies() > 0 && syncAffordable(stock, c))
                run |= 1 << c;
        float share = run != 0 ? workers * step / Integer.bitCount(run) : 0f;
        for (int c = 0; c < 3; c++) {
            float next = ms[c];
            if ((run & 1 << c) != 0) {
                next += share;
                if (next >= SYNC_WORK[c])
                    next = 0f;
            }
            sync_expect[c] = next;
            sync_ms[c] = ms[c];
            sync_progress[c] = queues[c].getBuildProgress();
            sync_weapons[c] = armory.getSupplyContainer(SYNC_WEAPONS[c]).getNumSupplies();
        }
        System.arraycopy(stock, 0, sync_stock, 0, 4);
        sync_carried.clear();
        int ax = armory.getGridX();
        int ay = armory.getGridY();
        for (Selectable<?> s : ai.owner().getUnits().getSet()) {
            if (!(s instanceof Unit u) || u.isDead() || Math.abs(u.getGridX() - ax) > SYNC_REACH
                    || Math.abs(u.getGridY() - ay) > SYNC_REACH)
                continue;
            UnitSupplyContainer load = u.getSupplyContainer();
            if (load == null || load.getNumSupplies() == 0)
                continue;
            int supply = syncSupplyIndex(load.getSupplyType());
            if (supply >= 0)
                sync_carried.put(u, new int[]{supply, load.getNumSupplies()});
        }
        sync_tick = ai.ticks();
    }

    /**
     * The man-seconds behind a queue's progress (man_seconds / work in floats): a guess that gives the same progress
     * (the planned next value, then the unchanged one), else the float nearest progress x work that does.
     */
    private static float syncManSeconds(float progress, float work, float planned, float unchanged) {
        if (progress == 0f)
            return 0f;
        if (!Float.isNaN(planned) && planned / work == progress)
            return planned;
        if (!Float.isNaN(unchanged) && unchanged / work == progress)
            return unchanged;
        float guess = progress * work;
        float best = guess;
        float best_d = Float.MAX_VALUE;
        float f = Math.nextDown(Math.nextDown(Math.nextDown(guess)));
        for (int i = 0; i < 7; i++, f = Math.nextUp(f)) {
            if (f / work == progress && Math.abs(f - guess) < best_d) {
                best = f;
                best_d = Math.abs(f - guess);
            }
        }
        return best;
    }

    /** Whether the stock holds a weapon of queue c, as BuildProductionContainer.hasEnoughSupplies checks. */
    private static boolean syncAffordable(int @NonNull [] stock, int c) {
        for (int s = 0; s < 4; s++)
            if (stock[s] < SYNC_COST[c][s])
                return false;
        return true;
    }

    /** Whether queue c and the rubber axe share a resource the stock holds too little of to pay for both. */
    private static boolean syncShortShared(int @NonNull [] stock, int c) {
        for (int s = 0; s < 4; s++)
            if (SYNC_COST[c][s] > 0 && SYNC_COST[SYNC_RUBBER][s] > 0
                    && stock[s] < SYNC_COST[c][s] + SYNC_COST[SYNC_RUBBER][s])
                return true;
        return false;
    }

    private static int syncSupplyIndex(@Nullable Class<?> type) {
        for (int s = 0; s < 4; s++)
            if (SYNC_SUPPLIES[s] == type)
                return s;
        return -1;
    }

    private static @NonNull BuildProductionContainer syncQueue(@NonNull Building armory, int c) {
        return (BuildProductionContainer) armory.getBuildSupplyContainer(SYNC_WEAPONS[c]);
    }

    private static int @NonNull [] syncStock(@NonNull Building armory) {
        int[] stock = new int[4];
        for (int s = 0; s < 4; s++)
            stock[s] = armory.getSupplyContainer(SYNC_SUPPLIES[s]).getNumSupplies();
        return stock;
    }

    private void syncOrder(@NonNull Building armory, int c, int count, boolean infinite) {
        Player owner = ai.owner();
        switch (c) {
            case SYNC_ROCK -> owner.buildRockWeapons(armory, count, infinite);
            case SYNC_IRON -> owner.buildIronWeapons(armory, count, infinite);
            default -> owner.buildRubberWeapons(armory, count, infinite);
        }
    }

    private static @NonNull String syncNames(int queues) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < 3; c++)
            if ((queues & 1 << c) != 0)
                sb.append(sb.isEmpty() ? "" : "+").append(SYNC_NAMES[c]);
        return sb.toString();
    }

    private static @NonNull String syncState(float @NonNull [] ms, int @NonNull [] stock) {
        return String.format("left rock %.2f iron %.2f rubber %.2f, stock %d/%d/%d/%d", SYNC_WORK[0] - ms[0],
                SYNC_WORK[1] - ms[1], SYNC_WORK[2] - ms[2], stock[0], stock[1], stock[2], stock[3]);
    }

    private void computeGatherTargets() {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        if (armory == null || armory_field == null) {
            want_tree = want_iron = want_rock = want_chicken = want_workers = 0;
            return;
        }
        MapAnalysis map = ai.map();
        float harvest = ai.strategy().harvest_seconds;
        tree_cycle = SitePlanner.gatherSeconds(armory_field, map.getTrees(), 60, 10, 120, harvest);
        iron_cycle = SitePlanner.gatherSeconds(armory_field, map.getIron(), 30, 10, 400, harvest);
        int iron_left = countReachable(map.getIron(), 400);
        // Rock warriors are a poor use of a peon; make them only once iron is out of reach.
        rock_weapons = (iron_left == 0 || iron_cycle > 200f)
                && armory.getSupplyContainer(IronSupply.class).getNumSupplies() < 2;
        // With iron far away, the armory's peons often sit waiting for ore. Let them make rock weapons meanwhile:
        // iron weapons still take every piece of iron that comes in, since both are made side by side.
        int iron_stock = armory.getSupplyContainer(IronSupply.class).getNumSupplies();
        int armory_workers = armory.getUnitContainer().getNumSupplies();
        if (ai.strategy().hold_backlog > 0 && armory.isComplete()) {
            int forge = Math.min(iron_stock, armory.getSupplyContainer(TreeSupply.class).getNumSupplies() / 2);
            float until = ai.strategy().hold_backlog_until;
            if (!backlog_on && forge >= ai.strategy().hold_backlog && (until <= 0f || ai.time() < until)) {
                backlog_on = true;
                backlog_since = ai.time();
                ai.aiLog().count("backlog_on");
            } else if (backlog_on && ((forge <= 1 && ai.time() - backlog_since >= 30f)
                    || (until > 0f && ai.time() >= until)))
                backlog_on = false;
        }
        if (!rock_filler && iron_stock <= 1 && armory_workers >= ai.strategy().rock_filler_min_workers
                && iron_cycle > 45f)
            rock_filler = true;
        else if (rock_filler && (iron_stock >= 5 || armory_workers < 8))
            rock_filler = false;
        float ore_cycle = rock_weapons ? SitePlanner.gatherSeconds(armory_field, map.getRocks(), 30, 10, 240,
                harvest) : iron_cycle;
        float work = rock_weapons ? IRON_WORK / 2 : IRON_WORK;

        int workers = armory.getUnitContainer().getNumSupplies();
        int g_tree = intel.countGatherers(PeonState.GATHER_TREE, armory);
        int g_iron = intel.countGatherers(PeonState.GATHER_IRON, armory);
        int g_rock = intel.countGatherers(PeonState.GATHER_ROCK, armory);
        int g_chicken = intel.countGatherers(PeonState.GATHER_CHICKEN, armory);
        int transit = intel.countPeons(PeonState.TRANSIT);
        int pool = workers + g_tree + g_iron + g_rock + g_chicken + transit;

        // Chicken warriors need one chicken, one rock and more work; hunt chickens once the base runs.
        // A chicken warrior is worth two iron ones, so chickens are hunted as soon as there are peons to spare.
        want_chicken = 0;
        int chickens_left = countChickens();
        Strategy strategy = ai.strategy();
        if (ai.owner().canUseRubber() && ai.time() > strategy.chicken_time && pool > 10 && chickens_left > 0)
            want_chicken = Math.min(Math.min(strategy.chicken_hunters, chickens_left),
                    2 + pool / strategy.chicken_pool_div);
        int rock_stock = armory.getSupplyContainer(RockSupply.class).getNumSupplies();
        int chicken_stock = armory.getSupplyContainer(RubberSupply.class).getNumSupplies();
        want_rock = (want_chicken > 0 || chicken_stock > 0) && rock_stock < 6 ? 1 + want_chicken / 4 : 0;
        Strategy st = ai.strategy();
        // rock_surge: the filler's gatherers come out of the armory's idle workers below, not out of the iron share.
        if (!st.rock_surge && rock_filler && !rock_weapons && rock_stock < st.rock_filler_stock)
            want_rock += Math.max(2, armory_workers / st.rock_filler_div);
        pool -= want_chicken + want_rock;

        float per_weapon = work + 2 * tree_cycle + ore_cycle;
        float x = Math.max(0, pool) / per_weapon;
        int tree_stock = armory.getSupplyContainer(TreeSupply.class).getNumSupplies();
        int ore_stock = armory.getSupplyContainer(rock_weapons ? RockSupply.class : IronSupply.class).getNumSupplies();
        float tree_adj = tree_stock > 40 ? .35f : tree_stock > 20 ? .7f : tree_stock < 6 ? 1.15f : 1f;
        float ore_adj = ore_stock > 20 ? .35f : ore_stock > 10 ? .7f : ore_stock < 3 ? 1.15f : 1f;
        want_tree = Math.round(2 * tree_cycle * x * tree_adj);
        int want_ore = Math.round(ore_cycle * x * ore_adj);
        if (pool >= 3) {
            want_tree = Math.max(1, want_tree);
            want_ore = Math.max(1, want_ore);
        }
        float rock_share = ai.strategy().rock_share;
        if (rock_weapons) {
            want_rock += want_ore;
            want_iron = 0;
        } else if (rock_share > 0f && want_ore > 0) {
            // A second stream: rock axes cost half the work of iron ones and rock is twice as plentiful (sweep's
            // ironfrac 0.9 beat 1.0 at N=3, 56 vs 45 of 200).
            int rock = Math.round(want_ore * rock_share);
            want_rock += rock;
            want_iron = want_ore - rock;
        } else {
            want_iron = want_ore;
        }
        want_workers = Math.max(2, pool - want_tree - want_ore);
        // rock_stream: once a unit of iron costs more gatherer-seconds than rock_stream_iron_s (measured, not
        // modelled: the model's iron cycle is 1.5-5 times too optimistic after 10 min), the armory's waiting workers
        // go for rock and the wood it needs (a rock axe: 2 wood, 1 rock, 40 man-s, 0.6 of an iron warrior); beyond
        // 150 s per iron unit the iron gatherers are capped and sent for rock too.
        rock_stream_on = st.rock_stream && ai.time() >= st.rock_stream_time && iron_s > st.rock_stream_iron_s
                && !rock_weapons;
        if (rock_stream_on) {
            int add = Math.min(st.rock_stream_max, Math.max(0, Math.min(want_workers - 4, armory_workers / 2)));
            if (iron_s > 150f && want_iron > 6) {
                add += want_iron - 6;
                want_iron = 6;
            }
            int wood = add / 3;
            want_rock += add - wood;
            want_tree += wood;
            want_workers = Math.max(2, want_workers - add);
            ai.aiLog().count("rock_stream");
        }
        if (st.rock_surge && rock_filler && !rock_weapons && rock_stock < st.rock_filler_stock) {
            // The armory's workers wait for iron: send some of them for rock, the plentiful ore (rock axes take half
            // the work of iron ones).
            int filler = Math.min(want_workers - 2, Math.max(2, armory_workers / st.rock_filler_div));
            if (filler > 0) {
                want_rock += filler;
                want_workers -= filler;
            }
        }
    }

    private int countReachable(@NonNull List<? extends Supply> supplies, int max_meters) {
        if (armory_field == null)
            return 0;
        int n = 0;
        for (Supply s : supplies) {
            if (s.isEmpty())
                continue;
            if (armory_field.getAround(s.getGridX(), s.getGridY(), 1) <= max_meters)
                n++;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Peon allocation

    private void allocatePeons() {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        List<Unit> free = new ArrayList<>();
        List<Unit> transit = new ArrayList<>();
        for (Unit peon : intel.peons) {
            PeonState s = intel.peon_states.get(peon);
            if (s == PeonState.IDLE)
                free.add(peon);
            else if (s == PeonState.TRANSIT)
                transit.add(peon);
        }
        if (scout != null && (scout.isDead() || !scoutHasWork()))
            scout = scout.isDead() ? null : scout;

        // 1. Construction.
        for (Project p : projects) {
            if (!p.isPlaced())
                continue;
            int need = buildersWanted(p) - builderCount(p.building);
            if (need <= 0)
                continue;
            if (ai.strategy().tower_cooldown && p.type == Race.BUILDING_TOWER && awakeEnemyNear(p.site.x, p.site.y,
                    12)) {
                ai.aiLog().count("tower_builders_held");
                continue;
            }
            List<Unit> chosen = new ArrayList<>();
            takeNearest(free, chosen, need, p.site.x, p.site.y);
            takeNearest(transit, chosen, need - chosen.size(), p.site.x, p.site.y);
            if (chosen.size() < need && (p.type == Race.BUILDING_ARMORY || p.first))
                takeGatherers(chosen, need - chosen.size(), p.site.x, p.site.y);
            if (ai.strategy().expand_under_threat && p == expansion_project && chosen.size() < need && armory != null
                    && armory.isComplete() && armory != p.building) {
                int workers = armory.getUnitContainer().getNumSupplies();
                int pending = armory.getDeployContainer(DeployType.PEON).getNumSupplies();
                if (workers > want_workers + 5 && pending == 0) {
                    ai.owner().deployUnits(armory, DeployType.PEON, Math.min(need - chosen.size(),
                            workers - want_workers));
                    ai.aiLog().count("exp_builders_deployed");
                }
            }
            for (Unit u : chosen) {
                if (u == scout && scoutHasWork())
                    continue;
                order(u, p.building, Action.DEFAULT);
                intel.peon_states.put(u, PeonState.BUILD);
                intel.builder_sites.put(u, p.building);
            }
        }

        // 2. Chieftain training quarters top-up.
        chieftain_topup = false;
        Building trainer = ai.chieftain().trainingQuarters();
        boolean topup_ok = ai.military().baseThreatLevel() == 0 || (ai.strategy().chief_topup_any && trainer != null
                && !ai.military().threatNear(trainer.getGridX(), trainer.getGridY(), 20));
        if (trainer != null && topup_ok && !evacuating.containsKey(trainer)) {
            boolean near = ai.strategy().chief_trainer_near;
            int heading = near ? countSentTo(trainer) : countHeadingTo(trainer);
            int need = ai.strategy().hold_chieftain - trainer.getUnitContainer().getNumSupplies() - heading;
            if (need > 0) {
                List<Unit> chosen = new ArrayList<>();
                takeNearest(free, chosen, need, trainer.getGridX(), trainer.getGridY());
                List<Unit> walkers = transit;
                if (near) {
                    walkers = new ArrayList<>();
                    for (Unit u : transit)
                        if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), trainer.getGridX(),
                                trainer.getGridY()) <= 40 * 40)
                            walkers.add(u);
                }
                takeNearest(walkers, chosen, need - chosen.size(), trainer.getGridX(), trainer.getGridY());
                if (walkers != transit)
                    transit.removeAll(chosen);
                order(chosen, trainer, Action.DEFAULT);
                if (near)
                    for (Unit u : chosen) {
                        topup_sent.put(u, trainer);
                        ai.aiLog().count("topup_sent");
                    }
                chieftain_topup = true;
            }
        }

        if (armory == null) {
            // Nothing to gather for yet: spare peons speed up a quarters that is below its reserve.
            for (Unit u : free) {
                Building q = nearest(intel.quarters, u.getGridX(), u.getGridY());
                if (q != null && !evacuating.containsKey(q)
                        && q.getUnitContainer().getNumSupplies() + countHeadingTo(q) < holdFor(q))
                    order(u, q, Action.DEFAULT);
            }
            return;
        }

        // 3. Gatherers.
        boolean danger = ai.military().baseThreatLevel() > 1 && (!gatherUnderThreat()
                || ai.military().threatNearEcon(armory.getGridX(), armory.getGridY(), 16));
        danger_idle = ai.strategy().danger_refuge && danger
                && armory.getSupplyContainer(IronSupply.class).getNumSupplies() + armory.getSupplyContainer(
                        RockSupply.class).getNumSupplies() < 2;
        int[] have = {intel.countGatherers(PeonState.GATHER_TREE, armory), intel.countGatherers(PeonState.GATHER_IRON,
                armory), intel.countGatherers(PeonState.GATHER_ROCK, armory), intel.countGatherers(
                        PeonState.GATHER_CHICKEN, armory)};
        int[] want = {want_tree, want_iron, want_rock, want_chicken};
        Class<?>[] types = {TreeSupply.class, IronSupply.class, RockSupply.class, RubberSupply.class};
        rebuildSupplyLoad();
        int deploy_for_gathering = 0;
        for (int t = 0; t < 4; t++) {
            int need = danger ? 0 : want[t] - have[t];
            while (need > 0) {
                Unit u = free.isEmpty() ? null : free.removeFirst();
                if (u == null && !transit.isEmpty())
                    u = transit.removeFirst();
                if (u == null) {
                    deploy_for_gathering += need;
                    break;
                }
                if (!sendGatherer(u, types[t], armory))
                    break;
                need--;
            }
            if (!danger && have[t] > want[t] + 1)
                ai.owner().recallGatherers(armory, supplyClass(types[t]), have[t] - want[t]);
        }
        retargetGatherers(armory);
        unstickGatherers(armory);
        int workers = armory.getUnitContainer().getNumSupplies();
        int pending = armory.getDeployContainer(DeployType.PEON).getNumSupplies();
        if (deploy_for_gathering > 0 && workers > 3 && pending == 0)
            ai.owner().deployUnits(armory, DeployType.PEON, Math.min(deploy_for_gathering, workers - 3));

        // 4. Everyone else works in the armory, unless it is being emptied: then in another armory, or they wait.
        Building work = armory;
        if (danger_idle && !free.isEmpty()) {
            refuge(free, armory);
            return;
        }
        if (evacuating.containsKey(armory)) {
            work = null;
            for (Building a : intel.armories)
                if (a != armory && !a.isDead() && a.isComplete() && !evacuating.containsKey(a))
                    work = a;
        }
        if (work != null)
            for (Unit u : free)
                order(u, work, Action.DEFAULT);
    }

    /** danger_refuge: the main armory is threatened and has no ore to forge (set each economy tick). */
    private boolean danger_idle;
    private @Nullable Building refuge_armory;
    private float refuge_until = -1f;

    /**
     * danger_refuge: spare peons go to the complete armory farthest from the threat with no threat within 25 cells,
     * else each to its nearest quarters with no threat within 16 cells, else they stay where they are.
     */
    private void refuge(@NonNull List<@NonNull Unit> free, @NonNull Building armory) {
        Intel intel = ai.intel();
        Military military = ai.military();
        int tx = military.threatX();
        int ty = military.threatY();
        Building work = null;
        int best = -1;
        for (Building a : intel.armories) {
            if (a == armory || a.isDead() || !a.isComplete() || evacuating.containsKey(a)
                    || military.threatNear(a.getGridX(), a.getGridY(), 25))
                continue;
            int d = MapAnalysis.dist2(a.getGridX(), a.getGridY(), tx, ty);
            if (d > best) {
                best = d;
                work = a;
            }
        }
        if (work != null) {
            refuge_armory = work;
            refuge_until = ai.time() + 30f;
            for (Unit u : free) {
                order(u, work, Action.DEFAULT);
                ai.aiLog().count("refuge_armory");
            }
            return;
        }
        for (Unit u : free) {
            Building shelter = null;
            int best_d = Integer.MAX_VALUE;
            for (Building q : intel.quarters) {
                if (q.isDead() || evacuating.containsKey(q) || military.threatNearEcon(q.getGridX(), q.getGridY(), 16))
                    continue;
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), q.getGridX(), q.getGridY());
                if (d < best_d) {
                    best_d = d;
                    shelter = q;
                }
            }
            if (shelter != null) {
                order(u, shelter, Action.DEFAULT);
                ai.aiLog().count("refuge_quarters");
            }
        }
    }

    private boolean scoutHasWork() {
        for (Project p : projects)
            if (p.use_scout && !p.isPlaced())
                return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Class<? extends Supply> supplyClass(@NonNull Class<?> c) {
        return (Class<? extends Supply>) c;
    }

    /** chief_trainer_near: peons ordered into the chieftain's training quarters, and which one. */
    private final Map<@NonNull Unit, @NonNull Building> topup_sent = new LinkedHashMap<>();

    /** chief_trainer_near: peons we sent to this trainer that are still walking there. */
    private int countSentTo(@NonNull Building trainer) {
        topup_sent.entrySet().removeIf(e -> e.getKey().isDead() || e.getValue() != trainer
                || ai.intel().peon_states.get(e.getKey()) != PeonState.TRANSIT);
        return topup_sent.size();
    }

    private int countHeadingTo(@NonNull Building building) {
        int n = 0;
        for (Unit peon : ai.intel().peons) {
            if (ai.intel().peon_states.get(peon) != PeonState.TRANSIT)
                continue;
            if (MapAnalysis.dist2(peon.getGridX(), peon.getGridY(), building.getGridX(), building.getGridY()) < 900)
                n++;
        }
        return n;
    }

    private void takeNearest(@NonNull List<@NonNull Unit> from, @NonNull List<@NonNull Unit> into, int n, int x,
            int y) {
        for (int k = 0; k < n && !from.isEmpty(); k++) {
            Unit best = null;
            int best_d = Integer.MAX_VALUE;
            for (Unit u : from) {
                int d = MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y);
                if (d < best_d) {
                    best_d = d;
                    best = u;
                }
            }
            if (best == null)
                return;
            from.remove(best);
            into.add(best);
        }
    }

    private void takeGatherers(@NonNull List<@NonNull Unit> into, int n, int x, int y) {
        List<Unit> gatherers = new ArrayList<>();
        for (Unit peon : ai.intel().peons) {
            PeonState s = ai.intel().peon_states.get(peon);
            if (s == PeonState.GATHER_TREE || s == PeonState.GATHER_IRON || s == PeonState.GATHER_ROCK)
                gatherers.add(peon);
        }
        takeNearest(gatherers, into, n, x, y);
    }

    private static @Nullable Building nearest(@NonNull List<@NonNull Building> buildings, int x, int y) {
        Building best = null;
        int best_d = Integer.MAX_VALUE;
        for (Building b : buildings) {
            int d = MapAnalysis.dist2(b.getGridX(), b.getGridY(), x, y);
            if (d < best_d) {
                best_d = d;
                best = b;
            }
        }
        return best;
    }

    private void rebuildSupplyLoad() {
        supply_load.clear();
        Intel intel = ai.intel();
        for (Iterator<Map.Entry<Unit, Supply>> it = gather_targets.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Unit, Supply> e = it.next();
            Unit u = e.getKey();
            PeonState s = intel.peon_states.get(u);
            if (u.isDead() || s == null || !isGathering(s)) {
                it.remove();
                continue;
            }
            Supply supply = e.getValue();
            if (!supply.isEmpty())
                supply_load.merge(supply, 1, Integer::sum);
        }
    }

    private static boolean isGathering(@NonNull PeonState s) {
        return s == PeonState.GATHER_TREE || s == PeonState.GATHER_IRON || s == PeonState.GATHER_ROCK
                || s == PeonState.GATHER_CHICKEN;
    }

    private boolean sendGatherer(@NonNull Unit peon, @NonNull Class<?> type, @NonNull Building armory) {
        Supply supply = pickSupply(type, armory, peon);
        if (supply == null)
            return false;
        ai.owner().setTarget(Selectable.newArray(peon), supply, Action.DEFAULT, false);
        gather_targets.put(peon, supply);
        supply_load.merge(supply, 1, Integer::sum);
        return true;
    }

    /**
     * A gatherer whose load has not changed for 70 seconds is stuck, most often walking to a tree it cannot reach: it
     * is sent to another supply and the old one is avoided for two minutes.
     */
    private void unstickGatherers(@NonNull Building armory) {
        if (!ai.strategy().unstick)
            return;
        Intel intel = ai.intel();
        float now = ai.time();
        gather_progress.keySet().removeIf(Unit::isDead);
        bad_supplies.values().removeIf(until -> until < now);
        for (Unit peon : intel.peons) {
            PeonState s = intel.peon_states.get(peon);
            Class<?> type = s == PeonState.GATHER_TREE ? TreeSupply.class : s == PeonState.GATHER_IRON ? IronSupply.class : s == PeonState.GATHER_ROCK ? RockSupply.class : null;
            if (type == null) {
                gather_progress.remove(peon);
                continue;
            }
            int amount = peon.getSupplyContainer() != null ? peon.getSupplyContainer().getNumSupplies() : 0;
            float[] seen = gather_progress.get(peon);
            if (seen == null || seen[0] != amount) {
                gather_progress.put(peon, new float[]{amount, now});
                continue;
            }
            // A gatherer on a long walk carries nothing new for a whole trip; only a stall well past it is stuck.
            float trip = type == IronSupply.class ? iron_cycle : type == TreeSupply.class ? tree_cycle : 0f;
            if (now - seen[1] < Math.max(70f, ai.strategy().stuck_trip_factor * trip))
                continue;
            Supply old = gather_targets.get(peon);
            if (old != null)
                bad_supplies.put(old, now + 120f);
            gather_progress.put(peon, new float[]{amount, now});
            unstuck++;
            sendGatherer(peon, type, armory);
        }
        if (unstuck > 0 && now - last_unstuck_log > 60f) {
            last_unstuck_log = now;
            ai.log(unstuck + " stuck gatherers re-sent so far");
        }
    }

    /**
     * Gatherers whose supply ran out walk to whatever is nearest the armory, which piles them onto the same tree.
     * Spread them over the supplies around instead.
     */
    private void retargetGatherers(@NonNull Building armory) {
        Intel intel = ai.intel();
        int moved = 0;
        for (Unit peon : intel.peons) {
            if (moved >= 4)
                return;
            PeonState s = intel.peon_states.get(peon);
            if (s == null || !isGathering(s) || s == PeonState.GATHER_CHICKEN)
                continue;
            // Gatherers still working for an armory that is no longer the main one move over to the new one.
            Building works_for = intel.gather_buildings.get(peon);
            boolean moving_over = works_for != null && works_for != armory && !works_for.isDead()
                    && intel.armories.contains(works_for);
            if (works_for != armory && works_for != null && !moving_over)
                continue;
            Supply current = gather_targets.get(peon);
            if (!moving_over && current != null && !current.isEmpty())
                continue;
            if (peon.getSupplyContainer() != null && peon.getSupplyContainer().getNumSupplies() > 0)
                continue; // let it drop off first
            Class<?> type = switch (s) {
                case GATHER_TREE -> TreeSupply.class;
                case GATHER_IRON -> IronSupply.class;
                default -> RockSupply.class;
            };
            if (sendGatherer(peon, type, armory))
                moved++;
        }
    }

    private @Nullable Supply pickSupply(@NonNull Class<?> type, @NonNull Building armory, @NonNull Unit peon) {
        if (type == RubberSupply.class)
            return pickChicken(armory);
        DistanceField field = armory_field;
        List<? extends Supply> supplies = type == TreeSupply.class ? ai.map().getTrees() : type == IronSupply.class ? ai.map().getIron() : ai.map().getRocks();
        int max_load = type == TreeSupply.class ? TREE_LOAD : ai.strategy().ore_load;
        float load_penalty = type == TreeSupply.class ? 9f : ai.strategy().ore_load_penalty;
        Supply best = null;
        float best_cost = Float.MAX_VALUE;
        int ax = armory.getGridX();
        int ay = armory.getGridY();
        int radius = type == TreeSupply.class ? 60 : 200;
        for (Supply s : supplies) {
            if (s.isEmpty())
                continue;
            int d2 = MapAnalysis.dist2(ax, ay, s.getGridX(), s.getGridY());
            if (d2 > radius * radius)
                continue;
            int d = field != null ? field.getAround(s.getGridX(), s.getGridY(), 1) : (int) (Math.sqrt(d2) * 2);
            if (d == DistanceField.UNREACHABLE)
                continue;
            if (ai.military().threatNearEcon(s.getGridX(), s.getGridY(), 14))
                continue;
            if (ai.strategy().gather_avoid_parked && seenByParked(s.getGridX(), s.getGridY()))
                continue;
            Float bad = bad_supplies.get(s);
            if (bad != null && bad > ai.time())
                continue;
            int load = supply_load.getOrDefault(s, 0);
            float cost = d + load * load_penalty + (load >= max_load ? 60f : 0f);
            if (cost < best_cost) {
                best_cost = cost;
                best = s;
            }
        }
        return best;
    }

    private final List<int @NonNull []> parked_cells = new ArrayList<>();
    private float parked_time = -100f;

    /**
     * Whether an idle enemy warrior stands within 10 cells (Chebyshev) of (x, y): idle units scan an 8-cell square and
     * hunt what they see, and parked blobs 28-45 cells from our buildings are not base threats (gather_avoid_parked).
     */
    private boolean seenByParked(int x, int y) {
        if (ai.time() - parked_time >= 1f) {
            parked_time = ai.time();
            parked_cells.clear();
            for (Unit e : ai.intel().enemy_warriors)
                if (!e.isDead() && e.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.IdleController
                        && e.getCurrentController() == e.getPrimaryController())
                    parked_cells.add(new int[]{e.getGridX(), e.getGridY()});
        }
        for (int[] c : parked_cells)
            if (Math.abs(c[0] - x) <= 10 && Math.abs(c[1] - y) <= 10)
                return true;
        return false;
    }

    private int countChickens() {
        if (ai.time() - chickens_time > 10f) {
            chickens_time = ai.time();
            chickens.clear();
            UnitGrid grid = ai.map().getGrid();
            int size = grid.getGridSize();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    Occupant occ = grid.getOccupant(x, y);
                    if (occ instanceof RubberSupply chicken && !chicken.isEmpty() && !chicken.isHit()
                            && !chickens.contains(chicken))
                        chickens.add(chicken);
                }
            }
        }
        int n = 0;
        for (RubberSupply c : chickens)
            if (!c.isEmpty())
                n++;
        return n;
    }

    private @Nullable Supply pickChicken(@NonNull Building armory) {
        countChickens();
        RubberSupply best = null;
        int best_d = Integer.MAX_VALUE;
        // Why no chicken comes back (counters only): none left, all taken, all by enemies, the nearest too far.
        int left = 0;
        int free = 0;
        for (RubberSupply c : chickens) {
            if (c.isEmpty() || c.isHit())
                continue;
            left++;
            if (supply_load.getOrDefault(c, 0) > 0)
                continue;
            free++;
            if (ai.military().enemyStrengthNear(c.getGridX(), c.getGridY(), 20) > 0)
                continue;
            int d = MapAnalysis.dist2(armory.getGridX(), armory.getGridY(), c.getGridX(), c.getGridY());
            if (d < best_d) {
                best_d = d;
                best = c;
            }
        }
        if (best == null)
            ai.aiLog().count(
                    left == 0 ? "chicken_null_left" : free == 0 ? "chicken_null_taken" : "chicken_null_enemy20");
        if (best != null && best_d > 150 * 150) {
            ai.aiLog().count("chicken_null_range");
            return null;
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Repairs

    private void manageRepairs() {
        Intel intel = ai.intel();
        if (intel.armory() == null)
            return;
        List<Building> damaged = new ArrayList<>();
        for (Building b : intel.armories)
            if (b.isDamaged())
                damaged.add(b);
        for (Building b : intel.towers)
            if (b.isDamaged())
                damaged.add(b);
        for (Building b : intel.quarters)
            if (b.isDamaged())
                damaged.add(b);
        for (Building b : damaged) {
            if (ai.military().threatNearEcon(b.getGridX(), b.getGridY(), 12))
                continue;
            int missing = b.getTemplate().getMaxHitPoints() - b.getHitPoints();
            int want = Math.min(4, 1 + missing / 40);
            int have = builderCount(b);
            if (have >= want)
                continue;
            List<Unit> chosen = new ArrayList<>();
            takeGatherers(chosen, want - have, b.getGridX(), b.getGridY());
            order(chosen, b, Action.GATHER_REPAIR);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    int wantWorkers() {
        return want_workers;
    }

    @NonNull
    String debugStatus() {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        StringBuilder sb = new StringBuilder();
        sb.append("Q").append(intel.quarters.size()).append('+').append(intel.quarters_sites.size());
        sb.append(" A").append(intel.armories.size()).append('+').append(intel.armory_sites.size());
        sb.append(" T").append(intel.towers.size()).append('+').append(intel.tower_sites.size());
        sb.append(" proj=").append(projects.size());
        sb.append(String.format(" Is=%.0f Rs=%.0f%s", iron_s, rock_s, rock_stream_on ? " RS" : ""));
        int held = 0;
        for (Building q : intel.quarters)
            if (!q.isDead())
                held += q.getUnitContainer().getNumSupplies();
        sb.append(" held=").append(held);
        sb.append(" peons=").append(intel.peons.size());
        sb.append(" (idle ").append(intel.countPeons(PeonState.IDLE));
        sb.append(" bld ").append(intel.countPeons(PeonState.BUILD));
        sb.append(" tr ").append(intel.countPeons(PeonState.TRANSIT));
        sb.append(" g ").append(intel.countPeons(PeonState.GATHER_TREE)).append('/').append(want_tree);
        sb.append(',').append(intel.countPeons(PeonState.GATHER_IRON)).append('/').append(want_iron);
        sb.append(',').append(intel.countPeons(PeonState.GATHER_ROCK)).append('/').append(want_rock);
        sb.append(',').append(intel.countPeons(PeonState.GATHER_CHICKEN)).append('/').append(want_chicken);
        sb.append(')');
        if (armory != null && !armory.isDead()) {
            sb.append(" W=").append(armory.getUnitContainer().getNumSupplies()).append('/').append(want_workers);
            sb.append(" res=").append(armory.getSupplyContainer(TreeSupply.class).getNumSupplies());
            sb.append(',').append(armory.getSupplyContainer(RockSupply.class).getNumSupplies());
            sb.append(',').append(armory.getSupplyContainer(IronSupply.class).getNumSupplies());
            sb.append(',').append(armory.getSupplyContainer(RubberSupply.class).getNumSupplies());
            sb.append(" wpn=").append(armory.getSupplyContainer(IronAxeWeapon.class).getNumSupplies());
            sb.append(',').append(armory.getSupplyContainer(RubberAxeWeapon.class).getNumSupplies());
            sb.append(String.format(" cyc=%.0f/%.0f", tree_cycle, iron_cycle));
        }
        // Peons in transit: entering near the primary armory, more than 60 cells from it, or transferring.
        int near = 0;
        int far = 0;
        int transfer = 0;
        for (Unit p : intel.peons) {
            if (p.isDead() || intel.peon_states.get(p) != PeonState.TRANSIT)
                continue;
            if (p.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.TransferUnitController)
                transfer++;
            else if (armory != null && MapAnalysis.dist2(armory.getGridX(), armory.getGridY(), p.getGridX(),
                    p.getGridY()) <= 8 * 8)
                near++;
            else if (armory != null && MapAnalysis.dist2(armory.getGridX(), armory.getGridY(), p.getGridX(),
                    p.getGridY()) > 60 * 60)
                far++;
        }
        sb.append(" trx=").append(near).append('/').append(far).append('/').append(transfer);
        return sb.toString();
    }
}
