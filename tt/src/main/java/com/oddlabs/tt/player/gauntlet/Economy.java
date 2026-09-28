package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.RubberSupply;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.Unit;
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
    private static final int ORE_LOAD = 3;

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

        int first_builders = Math.max(1, intel.peons.size() - strategy.scouts);
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
                if (peon != scout)
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
        manageProjects();
        escortForward();
        evacuate();
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
        if (ai.military().threatNear(p.site.x, p.site.y, 16))
            return false;
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
        if (armory != null && intel.quarters.size() >= 2) {
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
                int min_cells = 7;
                int max_cells = 15;
                if (fronts && tower_count % 2 == 1) {
                    int[][] front = enemyFront(tower_count / 2);
                    if (front != null) {
                        center = front[0];
                        face = front[1];
                        // Front towers further out leave room for decoys in front of them (Decoys).
                        min_cells = ai.strategy().front_tower_min;
                        max_cells = ai.strategy().front_tower_max;
                    }
                }
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
        if (rush_alert || !ai.strategy().rush_response || !intel.armories.isEmpty())
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

    /** Towers mostly guard the armory; every third one covers the quarters nearest the enemy. */
    private int @NonNull [] towerAnchor(int tower_count) {
        Intel intel = ai.intel();
        Building armory = intel.armory();
        assert armory != null;
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
        return new int[]{armory.getGridX(), armory.getGridY()};
    }

    /**
     * The k-th living enemy in turn, as {our building nearest to his start, his start}: the building his attacks go
     * for first.
     */
    private int @Nullable [] @Nullable [] enemyFront(int k) {
        List<Player> enemies = new ArrayList<>();
        for (Player p : ai.owner().getWorld().getPlayers())
            if (ai.owner().isEnemy(p) && p.isAlive())
                enemies.add(p);
        if (enemies.isEmpty())
            return null;
        Player enemy = enemies.get(k % enemies.size());
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
        if (pop >= max - 2)
            return 0;
        if (quarters.getChieftainContainer() != null && quarters.getChieftainContainer().isTraining())
            return strategy.hold_chieftain;
        if (ai.intel().armories.isEmpty() && !ai.intel().quarters.isEmpty() && needsBuilders())
            return Math.min(2, strategy.hold_early);
        if (pop > max * 7 / 10)
            return strategy.hold_late;
        int hold = ai.time() < strategy.hold_mid_time ? strategy.hold_early : strategy.hold_mid;
        return armsRace() ? Math.min(2, hold) : hold;
    }

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
            if (threatened && ai.military().threatNear(q.getGridX(), q.getGridY(), gatherUnderThreat() ? 12 : 20))
                continue;
            if (inside > hold)
                ai.owner().deployUnits(q, DeployType.PEON, inside - hold);
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
        if (ai.military().baseThreatLevel() > 0 || countProjects(Race.BUILDING_ARMORY, false) > 0)
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
        Project p = addProject(Race.BUILDING_ARMORY, site, 1);
        expansion_project = p;
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
        if (!can_make && left > 0 && pending == 0)
            owner.deployUnits(armory, DeployType.PEON, left);
    }

    private void orderWeapons(@NonNull Building armory) {
        Player owner = ai.owner();
        if (armory.getBuildSupplyContainer(IronAxeWeapon.class).getNumSupplies() == 0)
            owner.buildIronWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        if (owner.canUseRubber() && armory.getBuildSupplyContainer(RubberAxeWeapon.class).getNumSupplies() == 0)
            owner.buildRubberWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        int rock_orders = armory.getBuildSupplyContainer(RockAxeWeapon.class).getNumSupplies();
        boolean make_rock = rock_weapons || rock_filler || ai.strategy().rock_share > 0f;
        if (make_rock && rock_orders == 0)
            owner.buildRockWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        else if (!make_rock && rock_orders > 0)
            owner.buildRockWeapons(armory, -rock_orders, false);
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
        if (!rock_filler && iron_stock <= 1 && armory_workers >= 14 && iron_cycle > 45f)
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
        if (rock_filler && !rock_weapons && rock_stock < 20)
            want_rock += Math.max(2, armory_workers / 10);
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
            List<Unit> chosen = new ArrayList<>();
            takeNearest(free, chosen, need, p.site.x, p.site.y);
            takeNearest(transit, chosen, need - chosen.size(), p.site.x, p.site.y);
            if (chosen.size() < need && (p.type == Race.BUILDING_ARMORY || p.first))
                takeGatherers(chosen, need - chosen.size(), p.site.x, p.site.y);
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
        if (trainer != null && ai.military().baseThreatLevel() == 0 && !evacuating.containsKey(trainer)) {
            int need = ai.strategy().hold_chieftain - trainer.getUnitContainer().getNumSupplies() - countHeadingTo(
                    trainer);
            if (need > 0) {
                List<Unit> chosen = new ArrayList<>();
                takeNearest(free, chosen, need, trainer.getGridX(), trainer.getGridY());
                takeNearest(transit, chosen, need - chosen.size(), trainer.getGridX(), trainer.getGridY());
                order(chosen, trainer, Action.DEFAULT);
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
                || ai.military().threatNear(armory.getGridX(), armory.getGridY(), 16));
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
        int max_load = type == TreeSupply.class ? TREE_LOAD : ORE_LOAD;
        float load_penalty = type == TreeSupply.class ? 9f : 6f;
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
            if (ai.military().threatNear(s.getGridX(), s.getGridY(), 14))
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
        for (RubberSupply c : chickens) {
            if (c.isEmpty() || c.isHit())
                continue;
            if (supply_load.getOrDefault(c, 0) > 0)
                continue;
            if (ai.military().enemyStrengthNear(c.getGridX(), c.getGridY(), 20) > 0)
                continue;
            int d = MapAnalysis.dist2(armory.getGridX(), armory.getGridY(), c.getGridX(), c.getGridY());
            if (d < best_d) {
                best_d = d;
                best = c;
            }
        }
        if (best != null && best_d > 150 * 150)
            return null;
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
            if (ai.military().threatNear(b.getGridX(), b.getGridY(), 12))
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
