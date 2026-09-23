package com.oddlabs.tt.player.ai;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildingTemplate;
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
import com.oddlabs.tt.player.ai.Intel.PeonState;
import com.oddlabs.tt.player.ai.SitePlanner.Site;
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

    private final @NonNull ExpertAI ai;
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
    private boolean chieftain_topup;
    private int project_counter;
    private final List<@NonNull Building> forward_towers = new ArrayList<>();
    private boolean rush_alert;
    private float rush_alert_time;
    private boolean had_armory;

    Economy(@NonNull ExpertAI ai) {
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
            if (!builders.isEmpty()) {
                createBuilding(first);
                order(builders, first.building, Action.DEFAULT);
            }
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
        manageQuarters();
        manageArmory();
        allocatePeons();
        manageRepairs();
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
                default -> forward ? "forward tower" : "tower";
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

    private void createBuilding(@NonNull Project p) {
        BuildingTemplate template = ai.owner().getRace().getBuildingTemplate(p.type);
        p.building = template.create(ai.owner(), p.site.x, p.site.y);
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
                if (p.failures > 6) {
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
            createBuilding(p);
            order(placer, p.building, Action.DEFAULT);
            intel.builder_sites.put(placer, p.building);
            intel.peon_states.put(placer, PeonState.BUILD);
        }
    }

    private boolean projectMayStart(@NonNull Project p) {
        if (p.use_scout)
            return true;
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
        return p.type == Race.BUILDING_ARMORY || placed_incomplete < 2;
    }

    private void onCompleted(@NonNull Project p) {
        if (p == expansion_project)
            expansion = p.building;
        if (p.forward && p.building != null)
            forward_towers.add(p.building);
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
            forward_towers.removeIf(Building::isDead);
            int tower_count = intel.towers.size() + intel.tower_sites.size() + countProjects(Race.BUILDING_TOWER,
                    false) - forward_towers.size() - countForward();
            if (tower_count < target_towers && countProjects(Race.BUILDING_TOWER, true) == 0
                    && ai.owner().canBuild(Race.BUILDING_TOWER)) {
                List<int[]> existing = new ArrayList<>();
                for (Building t : intel.towers)
                    existing.add(new int[]{t.getGridX(), t.getGridY()});
                for (Building t : intel.tower_sites)
                    existing.add(new int[]{t.getGridX(), t.getGridY()});
                int[] center = towerAnchor(tower_count);
                Site site = ai.planner().findTowerSite(reservedSites(null), center[0], center[1], 7, 15, existing,
                        ai.planner().getEnemyX(), ai.planner().getEnemyY());
                if (site != null)
                    addProject(Race.BUILDING_TOWER, site, 8);
            }
            planForwardTower();
        }
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
            else if (id == Race.BUILDING_ARMORY)
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
        if (!rush_alert || ai.time() > rush_alert_time + ai.strategy().rush_seconds)
            return false;
        float ours = 0f;
        for (Unit w : ai.intel().warriors)
            ours += Combat.value(w);
        float theirs = 0f;
        for (Unit w : ai.intel().enemy_warriors)
            theirs += Combat.value(w);
        return ours < 1.2f * theirs + 4f;
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

    private void manageQuarters() {
        Intel intel = ai.intel();
        boolean threatened = ai.military().baseThreatLevel() > 1;
        for (Building q : intel.quarters) {
            int inside = q.getUnitContainer().getNumSupplies();
            int hold = holdFor(q);
            // Peons are safe inside while enemies roam next to the quarters.
            if (threatened && ai.military().threatNear(q.getGridX(), q.getGridY(), 20))
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
        if (armory == null || armory_field == null || ai.time() < 300f || ai.time() - last_expansion_check < 30f)
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
        if (site == null)
            return;
        DistanceField field = ai.map().computeField(site.x, site.y, 220);
        float better = ai.planner().warriorGatherCost(field);
        ai.log(String.format("expansion check: current armory %.0f (iron %.0fs), best site %d,%d %.0f", current,
                iron_cycle, site.x, site.y, better));
        if (better > .75f * current)
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
        boolean make_rock = rock_weapons || rock_filler;
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
        tree_cycle = SitePlanner.gatherSeconds(armory_field, map.getTrees(), 60, 10, 120);
        iron_cycle = SitePlanner.gatherSeconds(armory_field, map.getIron(), 30, 10, 400);
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
        float ore_cycle = rock_weapons ? SitePlanner.gatherSeconds(armory_field, map.getRocks(), 30, 10,
                240) : iron_cycle;
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
        if (ai.owner().canUseRubber() && ai.time() > 150f && pool > 10 && chickens_left > 0)
            want_chicken = Math.min(Math.min(7, chickens_left), 2 + pool / 18);
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
        if (rock_weapons) {
            want_rock += want_ore;
            want_iron = 0;
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
        if (trainer != null && ai.military().baseThreatLevel() == 0) {
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
                if (q != null && q.getUnitContainer().getNumSupplies() + countHeadingTo(q) < holdFor(q))
                    order(u, q, Action.DEFAULT);
            }
            return;
        }

        // 3. Gatherers.
        boolean danger = ai.military().baseThreatLevel() > 1;
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
        int workers = armory.getUnitContainer().getNumSupplies();
        int pending = armory.getDeployContainer(DeployType.PEON).getNumSupplies();
        if (deploy_for_gathering > 0 && workers > 3 && pending == 0)
            ai.owner().deployUnits(armory, DeployType.PEON, Math.min(deploy_for_gathering, workers - 3));

        // 4. Everyone else works in the armory.
        for (Unit u : free)
            order(u, armory, Action.DEFAULT);
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
            int load = supply_load.getOrDefault(s, 0);
            float cost = d + load * load_penalty + (load >= max_load ? 60f : 0f);
            if (cost < best_cost) {
                best_cost = cost;
                best = s;
            }
        }
        return best;
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
        return sb.toString();
    }
}
