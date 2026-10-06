package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.gui.BuildSpinner;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RockSupply;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.GatherController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.player.BuildingSiteScanFilter;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The economy of our base on the home island: building projects, peons in quarters to breed, gatherers and workers of
 * the armory, and the weapons it makes. Warriors leave the armory through {@link #deployWarriors}.
 */
final class Colony {
    private final @NonNull CorsairAI ai;
    private final @NonNull Player me;
    private final @NonNull Params p;

    /** The island we live on, and the point our base grows around. */
    int island;
    int ax;
    int ay;

    static final class Project {
        final int type;
        int x;
        int y;
        @Nullable
        Building site;
        /** Game second it was placed or last re-ordered. */
        int ordered = -1000;
        int tries;
        final int created;

        Project(int type, int x, int y, int created) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.created = created;
        }
    }

    final List<@NonNull Project> projects = new ArrayList<>();

    Colony(@NonNull CorsairAI ai) {
        this.ai = ai;
        this.me = ai.me();
        this.p = ai.params;
    }

    void found(int island, int ax, int ay) {
        this.island = island;
        this.ax = ax;
        this.ay = ay;
    }

    boolean founded() {
        return island != 0;
    }

    // ---------------------------------------------------------------------------------------------------------------

    void think(int second) {
        if (!founded())
            return;
        Intel in = ai.intel;
        trackProjects(in, second);
        plan(in, second);
        runProjects(in, second);
        manageArmories(in, second);
        manageQuarters(in, second);
        assignIdle(in, second);
    }

    /** Matches projects with the sites their builders placed, and drops finished or lost ones. */
    private void trackProjects(@NonNull Intel in, int second) {
        for (Iterator<Project> it = projects.iterator(); it.hasNext();) {
            Project pr = it.next();
            if (pr.site != null) {
                if (pr.site.isDead()) {
                    ai.log.log("BUILD", () -> "lost site " + name(pr.type) + " at " + pr.x + "," + pr.y);
                    it.remove();
                } else if (pr.site.isComplete()) {
                    ai.log.log("BUILD", () -> "done " + name(pr.type) + " at " + pr.x + "," + pr.y + " t" + second);
                    it.remove();
                }
                continue;
            }
            // find the site our builders placed
            for (Building b : pr.type == Race.BUILDING_SHIP ? new ArrayList<Building>(in.ship_sites) : in.sites) {
                if (b.getTemplate().getTemplateID() == pr.type && Geo.dist2(b.getGridX(), b.getGridY(), pr.x, pr.y) <= 9
                        && !claimed(b)) {
                    pr.site = b;
                    break;
                }
            }
            if (pr.site == null && second - pr.ordered > 40) {
                if (pr.tries >= 3) {
                    ai.log.log("BUILD", () -> "gave up " + name(pr.type) + " at " + pr.x + "," + pr.y);
                    it.remove();
                } else {
                    pr.ordered = -1000; // re-place
                }
            }
        }
    }

    private boolean claimed(@NonNull Building b) {
        for (Project pr : projects)
            if (pr.site == b)
                return true;
        return false;
    }

    static String name(int type) {
        return switch (type) {
            case Race.BUILDING_QUARTERS -> "quarters";
            case Race.BUILDING_ARMORY -> "armory";
            case Race.BUILDING_TOWER -> "tower";
            default -> "ship";
        };
    }

    int count(int type) {
        int n = 0;
        for (Project pr : projects)
            if (pr.type == type)
                n++;
        return n;
    }

    /** Decides which buildings to start. */
    private void plan(@NonNull Intel in, int second) {
        int q = in.quarters.size() + count(Race.BUILDING_QUARTERS);
        int a = in.armories.size() + count(Race.BUILDING_ARMORY);
        int t = in.towers.size() + count(Race.BUILDING_TOWER);
        int peons = in.peons.size() + in.peonsInside();
        int room = me.getWorld().getMaxBuildingCount() - in.buildings - unplaced();
        if (room <= 0)
            return;
        boolean busy = unplaced() > 0 || projects.size() >= 2;
        if (q == 0) {
            add(Race.BUILDING_QUARTERS, ax, ay, second);
            return;
        }
        if (in.quarters.isEmpty())
            return; // first quarters first
        if (a == 0) {
            add(Race.BUILDING_ARMORY, ax, ay, second);
            return;
        }
        if (busy)
            return;
        if (q < p.quarters && peons >= q * p.peons_per_quarters + 4) {
            add(Race.BUILDING_QUARTERS, ax, ay, second);
            return;
        }
        if (in.armories.isEmpty())
            return;
        int want_towers = Math.min(p.max_towers, (second - p.tower_start) / p.tower_every + 1);
        if (second >= p.tower_start && t < want_towers && peons >= 30) {
            int[] spot = towerSpot(in);
            add(Race.BUILDING_TOWER, spot[0], spot[1], second);
            return;
        }
        if (a < p.armories && peons >= 60) {
            add(Race.BUILDING_ARMORY, ax, ay, second);
        }
    }

    int unplaced() {
        int n = 0;
        for (Project pr : projects)
            if (pr.site == null)
                n++;
        return n;
    }

    private void add(int type, int x, int y, int second) {
        projects.add(new Project(type, x, y, second));
        ai.log.log("BUILD", () -> "plan " + name(type) + " near " + x + "," + y);
    }

    /** A tower spot: between the base and the nearest enemy landing, or the shore towards the nearest enemy. */
    private int[] towerSpot(@NonNull Intel in) {
        // the shore cell of our island nearest to the nearest enemy start, pulled 40% back towards the base
        Geo.Isle isle = ai.geo.isle(island);
        int ex = ax, ey = ay;
        long best = Long.MAX_VALUE;
        for (Player e : in.enemies) {
            int sx = Geo.gridOf(e.getStartX()), sy = Geo.gridOf(e.getStartY());
            long d = Geo.dist2(ax, ay, sx, sy);
            if (d < best) {
                best = d;
                ex = sx;
                ey = sy;
            }
        }
        int[] shore = isle == null ? null : isle.nearestShore(ex, ey);
        if (shore == null)
            return new int[]{ax, ay};
        int t = in.towers.size() + count(Race.BUILDING_TOWER);
        // alternate: odd towers nearer the base
        float f = t % 2 == 0 ? .45f : .7f;
        int x = (int) (ax + (shore[0] - ax) * f);
        int y = (int) (ay + (shore[1] - ay) * f);
        int d = Geo.dist(ax, ay, x, y);
        if (d > 30) {
            x = ax + (x - ax) * 30 / d;
            y = ay + (y - ay) * 30 / d;
        }
        return new int[]{x, y};
    }

    /** Sends builders to unplaced projects, and keeps placed sites manned. */
    private void runProjects(@NonNull Intel in, int second) {
        for (Project pr : projects) {
            if (pr.site == null && second - pr.ordered >= 15) {
                List<Unit> free = freePeons(in, pr.x, pr.y, builderTarget(pr, in));
                if (free.isEmpty())
                    continue;
                int[] site = findSite(pr.type, pr.x, pr.y, free.get(0));
                if (site == null) {
                    pr.tries++;
                    pr.ordered = second;
                    ai.log.log("BUILD", () -> "no site for " + name(pr.type) + " near " + pr.x + "," + pr.y);
                    continue;
                }
                pr.x = site[0];
                pr.y = site[1];
                pr.ordered = second;
                pr.tries++;
                me.placeBuilding(free.toArray(new Unit[0]), pr.type, site[0], site[1]);
                ai.log.log("BUILD", () -> "place " + name(
                        pr.type) + " at " + site[0] + "," + site[1] + " with " + free.size());
            } else if (pr.site != null && !pr.site.isDead()) {
                int have = buildersOn(in, pr.site);
                int want = builderTarget(pr, in);
                if (have < want) {
                    List<Unit> free = freePeons(in, pr.site.getGridX(), pr.site.getGridY(), want - have);
                    if (!free.isEmpty())
                        me.setTarget(free.toArray(new Unit[0]), pr.site, Action.GATHER_REPAIR, false);
                }
            }
        }
    }

    private int builderTarget(@NonNull Project pr, @NonNull Intel in) {
        int peons = in.peons.size() + in.peonsInside();
        return switch (pr.type) {
            case Race.BUILDING_QUARTERS -> in.quarters.isEmpty() ? 20 : Math.max(6, Math.min(12, peons / 5));
            case Race.BUILDING_ARMORY -> Math.max(8, Math.min(14, peons / 4));
            case Race.BUILDING_TOWER -> 6;
            default -> Math.max(8, Math.min(16, peons / 5));
        };
    }

    private int buildersOn(@NonNull Intel in, @NonNull Building site) {
        int n = 0;
        for (Unit u : in.peons) {
            Controller c = u.getPrimaryController();
            if (c instanceof RepairController r && r.getBuilding() == site)
                n++;
            else if (c instanceof PlaceBuildingController pb && pb.getBuilding() == site)
                n++;
        }
        return n;
    }

    /** Peons on our island that are idle or gathering (nearest first), at most {@code n}. */
    private @NonNull List<@NonNull Unit> freePeons(@NonNull Intel in, int x, int y, int n) {
        List<Unit> idle = new ArrayList<>();
        List<Unit> gather = new ArrayList<>();
        for (Unit u : in.peons) {
            if (u.getIslandId() != island || ai.reserved(u))
                continue;
            Controller c = u.getPrimaryController();
            if (c instanceof IdleController)
                idle.add(u);
            else if (c instanceof GatherController<?>)
                gather.add(u);
        }
        sortByDistance(idle, x, y);
        sortByDistance(gather, x, y);
        List<Unit> out = new ArrayList<>();
        for (Unit u : idle)
            if (out.size() < n)
                out.add(u);
        // gatherers only for the first buildings, when everyone else is busy
        if (out.size() < Math.min(n, 4))
            for (Unit u : gather)
                if (out.size() < Math.min(n, 4))
                    out.add(u);
        return out;
    }

    static void sortByDistance(@NonNull List<@NonNull Unit> units, int x, int y) {
        units.sort((u, v) -> {
            int c = Long.compare(Geo.dist2(u.getGridX(), u.getGridY(), x, y), Geo.dist2(v.getGridX(), v.getGridY(), x,
                    y));
            if (c != 0)
                return c;
            c = Integer.compare(u.getGridX(), v.getGridX());
            return c != 0 ? c : Integer.compare(u.getGridY(), v.getGridY());
        });
    }

    /**
     * A legal site for a building near (x, y) on our island. Quarters and armories keep a few cells from other
     * buildings so the base keeps its paths.
     */
    int @Nullable [] findSite(int type, int x, int y, @NonNull Unit builder) {
        BuildingTemplate template = me.getRace().getBuildingTemplate(type);
        BuildingSiteScanFilter filter = new BuildingSiteScanFilter(ai.geo.grid, template,
                type == Race.BUILDING_SHIP ? 120 : 60, false, island);
        ai.geo.grid.scan(filter, x, y);
        int spacing = type == Race.BUILDING_TOWER ? 7 : 10;
        int checked = 0;
        for (Target t : filter.getResult()) {
            if (++checked > 400)
                break;
            if (type != Race.BUILDING_SHIP && !spaced(t.getGridX(), t.getGridY(), spacing))
                continue;
            return new int[]{t.getGridX(), t.getGridY()};
        }
        if (!filter.getResult().isEmpty()) {
            Target t = filter.getResult().getFirst();
            return new int[]{t.getGridX(), t.getGridY()};
        }
        return null;
    }

    private boolean spaced(int x, int y, int min) {
        long m2 = (long) min * min;
        Intel in = ai.intel;
        for (List<Building> list : List.of(in.quarters, in.armories, in.towers, in.sites))
            for (Building b : list)
                if (Geo.dist2(b.getGridX(), b.getGridY(), x, y) < m2)
                    return false;
        return true;
    }

    // --- armory ----------------------------------------------------------------------------------------------------

    /** Peons an armory keeps inside to make weapons. */
    private int workersWanted(@NonNull Intel in) {
        int pop = in.peons.size() + in.peonsInside();
        return Math.max(3, Math.min(14, pop / 8));
    }

    private void manageArmories(@NonNull Intel in, int second) {
        for (Building armory : in.armories) {
            if (armory.getIslandId() != island)
                continue;
            orderWeapons(armory);
            manageGatherers(in, armory);
        }
    }

    private void orderWeapons(@NonNull Building armory) {
        if (armory.getBuildSupplyContainer(IronAxeWeapon.class).getNumSupplies() == 0)
            me.buildIronWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        int iron = armory.getSupplyContainer(IronSupply.class).getNumSupplies();
        int rock = armory.getSupplyContainer(RockSupply.class).getNumSupplies();
        boolean want_rock = p.rock_weapons && (iron == 0 || rock > 6);
        int rock_orders = armory.getBuildSupplyContainer(RockAxeWeapon.class).getNumSupplies();
        if (want_rock && rock_orders == 0)
            me.buildRockWeapons(armory, BuildSpinner.INFINITE_LIMIT, true);
        else if (!want_rock && rock_orders > 0)
            me.buildRockWeapons(armory, -rock_orders, false);
    }

    private void manageGatherers(@NonNull Intel in, @NonNull Building armory) {
        int inside = armory.getUnitContainer().getNumSupplies();
        int tree = me.getGathererCount(TreeSupply.class, armory);
        int iron = me.getGathererCount(IronSupply.class, armory);
        int rock = me.getGathererCount(RockSupply.class, armory);
        int wood_stock = armory.getSupplyContainer(TreeSupply.class).getNumSupplies();
        int iron_stock = armory.getSupplyContainer(IronSupply.class).getNumSupplies();
        int rock_stock = armory.getSupplyContainer(RockSupply.class).getNumSupplies();
        int workers = workersWanted(in);
        int spare = inside - workers - pendingDeploys(armory);
        if (spare <= 0)
            return;
        // gatherer shares: wood twice the ore, ore mostly iron
        int total = tree + iron + rock + spare;
        int want_tree = (int) (total * p.wood_share);
        int want_rock = p.rock_weapons ? (int) (total * p.rock_share) : 0;
        int want_iron = total - want_tree - want_rock;
        if (wood_stock > 40)
            want_tree = Math.min(want_tree, tree);
        if (iron_stock > 25)
            want_iron = Math.min(want_iron, iron);
        if (rock_stock > 25)
            want_rock = Math.min(want_rock, rock);
        int dt = Math.max(0, want_tree - tree), di = Math.max(0, want_iron - iron), dr = Math.max(0, want_rock - rock);
        while (dt + di + dr > spare) {
            if (dt >= di && dt >= dr && dt > 0)
                dt--;
            else if (di >= dr && di > 0)
                di--;
            else if (dr > 0)
                dr--;
        }
        if (dt > 0)
            me.deployUnits(armory, DeployType.PEON_HARVEST_TREE, dt);
        if (di > 0)
            me.deployUnits(armory, DeployType.PEON_HARVEST_IRON, di);
        if (dr > 0)
            me.deployUnits(armory, DeployType.PEON_HARVEST_ROCK, dr);
    }

    static int pendingDeploys(@NonNull Building b) {
        int n = 0;
        for (DeployType t : DeployType.values()) {
            var c = b.getDeployContainer(t);
            if (c != null)
                n += c.getNumSupplies();
        }
        return n;
    }

    /** Weapons in an armory's stock, and peons inside that could carry them. */
    static int weapons(@NonNull Building armory) {
        return armory.getSupplyContainer(IronAxeWeapon.class).getNumSupplies() + armory.getSupplyContainer(
                RockAxeWeapon.class).getNumSupplies() + armory.getSupplyContainer(
                        RubberAxeWeapon.class).getNumSupplies();
    }

    /** Lets up to n warriors out of our armories, the best weapons first. Returns how many were ordered. */
    int deployWarriors(@NonNull Intel in, int n) {
        int done = 0;
        for (Building armory : in.armories) {
            if (done >= n)
                break;
            int inside = armory.getUnitContainer().getNumSupplies() - pendingDeploys(armory);
            int keep = Math.min(2, inside);
            int can = Math.min(n - done, inside - keep);
            if (can <= 0)
                continue;
            int rubber = Math.min(can, armory.getSupplyContainer(RubberAxeWeapon.class).getNumSupplies());
            int iron = Math.min(can - rubber, armory.getSupplyContainer(IronAxeWeapon.class).getNumSupplies());
            int rock = Math.min(can - rubber - iron, armory.getSupplyContainer(RockAxeWeapon.class).getNumSupplies());
            if (rubber > 0)
                me.deployUnits(armory, DeployType.RUBBER_WARRIOR, rubber);
            if (iron > 0)
                me.deployUnits(armory, DeployType.IRON_WARRIOR, iron);
            if (rock > 0)
                me.deployUnits(armory, DeployType.ROCK_WARRIOR, rock);
            done += rubber + iron + rock;
        }
        return done;
    }

    // --- quarters --------------------------------------------------------------------------------------------------

    private void manageQuarters(@NonNull Intel in, int second) {
        int pop = in.units();
        boolean capped = pop >= me.getWorld().getMaxUnitCount() - 2;
        for (Building q : in.quarters) {
            if (q.getIslandId() != island)
                continue;
            int inside = q.getUnitContainer().getNumSupplies() - pendingDeploys(q);
            int hold = capped ? 0 : p.peons_per_quarters;
            // breed more while few peons are out working
            if (inside > hold) {
                int out = inside - hold;
                me.deployUnits(q, DeployType.PEON, out);
            }
        }
        // the chieftain
        if (p.chieftain && second >= p.chieftain_start && in.chief == null && !me.isTrainingChieftain()
                && !in.quarters.isEmpty() && in.quarters.size() >= 2 && pop >= 50) {
            Building q = in.quarters.getFirst();
            me.trainChieftain(q, true);
            ai.log.log("CHIEF", () -> "train at " + q.getGridX() + "," + q.getGridY());
        }
    }

    /** Idle peons on our island go where they are needed most. */
    private void assignIdle(@NonNull Intel in, int second) {
        List<Unit> idle = new ArrayList<>();
        for (Unit u : in.peons)
            if (u.getIslandId() == island && !ai.reserved(u) && u.getPrimaryController() instanceof IdleController)
                idle.add(u);
        if (idle.isEmpty())
            return;
        // quarters short of their hold
        List<Unit> to_quarters = new ArrayList<>();
        List<Unit> to_armory = new ArrayList<>();
        int q_room = 0;
        for (Building q : in.quarters)
            if (q.getIslandId() == island)
                q_room += Math.max(0, p.peons_per_quarters - q.getUnitContainer().getNumSupplies());
        boolean capped = in.units() >= me.getWorld().getMaxUnitCount() - 2;
        for (Unit u : idle) {
            if (q_room > 0 && !capped) {
                to_quarters.add(u);
                q_room--;
            } else if (!in.armories.isEmpty()) {
                to_armory.add(u);
            } else if (!in.quarters.isEmpty()) {
                to_quarters.add(u);
            }
        }
        sendInto(to_quarters, in.quarters);
        sendInto(to_armory, in.armories);
    }

    /** Each peon into the nearest building of the list on our island. */
    private void sendInto(@NonNull List<@NonNull Unit> units, @NonNull List<@NonNull Building> buildings) {
        if (units.isEmpty() || buildings.isEmpty())
            return;
        for (Building b : buildings) {
            if (b.getIslandId() != island)
                continue;
            List<Unit> group = new ArrayList<>();
            for (Unit u : units) {
                Building best = null;
                long bd = Long.MAX_VALUE;
                for (Building c : buildings) {
                    if (c.getIslandId() != island)
                        continue;
                    long d = Geo.dist2(u.getGridX(), u.getGridY(), c.getGridX(), c.getGridY());
                    if (d < bd) {
                        bd = d;
                        best = c;
                    }
                }
                if (best == b)
                    group.add(u);
            }
            if (!group.isEmpty())
                me.setTarget(group.toArray(new Unit[0]), b, Action.MOVE, false);
        }
    }
}
