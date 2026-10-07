package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.gui.BuildSpinner;
import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildingTemplate;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Ship;
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
    /** The shore cell our ships land at, -1 until known. */
    int dock_x = -1;
    int dock_y = -1;

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
        /** The peons sent to place the site: their controllers name it once placed. */
        final List<@NonNull Unit> placers = new ArrayList<>();
        /** The site's hit points when they last grew, and when. */
        int last_hp = -1;
        int hp_at;

        Project(int type, int x, int y, int created) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.created = created;
        }
    }

    final List<@NonNull Project> projects = new ArrayList<>();
    /** stall_raze: our own stalled sites our warriors pull down. */
    private final List<@NonNull Building> raze = new ArrayList<>();

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

    /** expand: the colony moves to another island; the old buildings stay where they are. */
    void relocate(int island, int ax, int ay, int dock_x, int dock_y) {
        this.island = island;
        this.ax = ax;
        this.ay = ay;
        this.dock_x = dock_x;
        this.dock_y = dock_y;
        projects.clear();
    }

    private void razeSites(@NonNull Intel in) {
        raze.removeIf(b -> b.isDead() || b.isComplete());
        for (Building b : raze) {
            List<Unit> near = new ArrayList<>();
            for (Unit w : in.warriors)
                if (w.getIslandId() == b.getIslandId() && !ai.reserved(w)
                        && Geo.dist2(w.getGridX(), w.getGridY(), b.getGridX(), b.getGridY()) <= 80 * 80)
                    near.add(w);
            sortByDistance(near, b.getGridX(), b.getGridY());
            List<Unit> go = new ArrayList<>(near.subList(0, Math.min(8, near.size())));
            for (Unit w : go)
                ai.reserve(w, 12);
            ai.order(go, b, Action.ATTACK, false);
            ai.log.count("raze_own");
        }
    }

    /** The buildings of a list that stand on our island. */
    @NonNull
    List<@NonNull Building> local(@NonNull List<@NonNull Building> list) {
        List<Building> out = new ArrayList<>();
        for (Building b : list)
            if (b.getIslandId() == island)
                out.add(b);
        return out;
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
            // find the site our builders placed: first from the placers' own controllers, then by position
            pr.placers.removeIf(Unit::isDead);
            for (Unit u : pr.placers) {
                Controller c = u.getPrimaryController();
                Building b = c instanceof PlaceBuildingController pb ? pb.getBuilding() : c instanceof RepairController rc ? rc.getBuilding() : null;
                if (b != null && !b.isDead() && b.getTemplate().getTemplateID() == pr.type && !claimed(b)
                        && (in.sites.contains(b) || b instanceof com.oddlabs.tt.model.Ship sh && in.ship_sites.contains(
                                sh))) {
                    pr.site = b;
                    pr.x = b.getGridX();
                    pr.y = b.getGridY();
                    break;
                }
            }
            if (pr.site != null)
                continue;
            for (Building b : pr.type == Race.BUILDING_SHIP ? new ArrayList<Building>(in.ship_sites) : in.sites) {
                if (b.getTemplate().getTemplateID() == pr.type && Geo.dist2(b.getGridX(), b.getGridY(), pr.x,
                        pr.y) <= 36
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

    /** Whether a placer of the project is still on its way to place it (re-placing would start a second site). */
    private static boolean placing(@NonNull Project pr) {
        for (Unit u : pr.placers)
            if (!u.isDead() && u.getPrimaryController() instanceof PlaceBuildingController pb
                    && pb.getBuilding() != null
                    && pb.getBuilding().getTemplate().getTemplateID() == pr.type)
                return true;
        return false;
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
        List<Building> quarters = local(in.quarters), armories = local(in.armories);
        int q = quarters.size() + count(Race.BUILDING_QUARTERS);
        int a = armories.size() + count(Race.BUILDING_ARMORY);
        int t = local(in.towers).size() + count(Race.BUILDING_TOWER);
        int peons = in.peons.size() + in.peonsInside();
        int room = me.getWorld().getMaxBuildingCount() - in.buildings - unplaced();
        if (room <= 0)
            return;
        boolean busy = unplaced() > 0 || projects.size() >= 2;
        if (q == 0) {
            add(Race.BUILDING_QUARTERS, ax, ay, second);
            return;
        }
        if (quarters.isEmpty())
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
        if (armories.isEmpty())
            return;
        // outnumbered_towers: each enemy beyond our team's size adds that many towers
        int surplus = Math.max(0, in.enemies.size() - in.allies.size() - 1);
        int max_towers = p.max_towers + p.outnumbered_towers * surplus;
        int want_towers = Math.min(max_towers, (second - p.tower_start) / p.tower_every + 1);
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
        // tower_face_land: an enemy building on our own island is where the attacks come from
        if (p.tower_face_land) {
            com.oddlabs.tt.model.Building land = Army.nearestBuilding(in.enemy_buildings, island, ax, ay);
            if (land != null)
                shore = new int[]{land.getGridX(), land.getGridY()};
        }
        if (shore == null)
            return new int[]{ax, ay};
        int t = local(in.towers).size() + count(Race.BUILDING_TOWER);
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
        // a site that stopped growing for minutes (its builders cannot reach it: a ship site behind a docked ship
        // once sat at 246 hp to the end, and no other ship was built) is left as it is
        if (p.stall_drop > 0)
            projects.removeIf(pr -> {
                if (pr.site == null || pr.site.isDead())
                    return false;
                int hp = pr.site.getHitPoints();
                if (hp > pr.last_hp) {
                    pr.last_hp = hp;
                    pr.hp_at = second;
                    return false;
                }
                if (second - pr.hp_at < p.stall_drop)
                    return false;
                ai.log.log("BUILD", () -> "stalled " + name(pr.type) + " at " + pr.x + "," + pr.y + " hp " + hp);
                ai.log.count("stalled_site");
                // stall_raze: a site that does not grow may be walling peons in (a ship site at the shore once
                // sealed 106 wood gatherers into a pocket for the rest of the game): our warriors pull it down
                if (p.stall_raze)
                    raze.add(pr.site);
                return true;
            });
        if (!raze.isEmpty() && second % 10 == 0)
            razeSites(in);
        for (Project pr : projects) {
            // safe_build: no building where the enemy fights; builders there die by the hundred (a shared island
            // once cost 134 peons and 20 tower sites in ten minutes): placing waits, builders on a site come away
            if (p.safe_build && underFire(in, pr)) {
                if (pr.site != null && !pr.site.isDead() && second % 5 == 0) {
                    List<Unit> away = new ArrayList<>();
                    for (Unit u : in.peons) {
                        Controller c = u.getPrimaryController();
                        if (c instanceof RepairController r && r.getBuilding() == pr.site
                                || c instanceof PlaceBuildingController pb && pb.getBuilding() == pr.site)
                            away.add(u);
                    }
                    if (!away.isEmpty()) {
                        ai.move(away, ax, ay, Action.MOVE, false);
                        ai.log.count("safe_build");
                    }
                }
                continue;
            }
            if (pr.site == null && second - pr.ordered >= 15 && !placing(pr)) {
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
                pr.placers.clear();
                pr.placers.addAll(free);
                float tdist = treeDistance(site[0], site[1]);
                ai.log.log("BUILD", () -> "place " + name(
                        pr.type) + " at " + site[0] + "," + site[1] + " with " + free.size() + " trees " + tdist);
            } else if (pr.site != null && !pr.site.isDead()) {
                int have = buildersOn(in, pr.site);
                int want = builderTarget(pr, in);
                if (second % 20 == 0) {
                    Building st = pr.site;
                    int idle = 0;
                    for (Unit u : in.peons)
                        if (u.getIslandId() == island && u.getPrimaryController() instanceof IdleController)
                            idle++;
                    int id = idle;
                    ai.log.log("BUILD", () -> "progress " + name(
                            pr.type) + " hp " + st.getHitPoints() + " builders " + have + "/" + want + " idle " + id);
                }
                if (have < want) {
                    List<Unit> free = freePeons(in, pr.site.getGridX(), pr.site.getGridY(), want - have);
                    if (!free.isEmpty())
                        ai.order(free, pr.site, Action.GATHER_REPAIR, false);
                }
            }
        }
    }

    private int builderTarget(@NonNull Project pr, @NonNull Intel in) {
        int peons = in.peons.size() + in.peonsInside();
        return switch (pr.type) {
            case Race.BUILDING_QUARTERS -> local(in.quarters).isEmpty() ? 20 : Math.max(6, Math.min(12, peons / 5));
            case Race.BUILDING_ARMORY -> Math.max(8, Math.min(14, peons / 4));
            case Race.BUILDING_TOWER -> 6;
            default -> Math.max(8, Math.min(16, peons / 5));
        };
    }

    /** safe_build: enemy warriors (value 3+) within 30 cells of a project's spot. */
    private boolean underFire(@NonNull Intel in, @NonNull Project pr) {
        int x = pr.site != null ? pr.site.getGridX() : pr.x, y = pr.site != null ? pr.site.getGridY() : pr.y;
        float v = 0;
        for (Unit e : in.enemy_warriors)
            if (e.getIslandId() == island && Geo.dist2(e.getGridX(), e.getGridY(), x, y) <= 30 * 30)
                v += Intel.value(e);
        return v >= 3f;
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
        int[] best = null;
        float best_score = Float.MAX_VALUE;
        int candidates = 0;
        int spaced_seen = 0;
        for (Target t : filter.getResult()) {
            if (++checked > 3000)
                break;
            if (type != Race.BUILDING_SHIP && !spaced(t.getGridX(), t.getGridY(), spacing))
                continue;
            // docks: a new ship stands clear of the others, or they lock each other in at the dock
            if (type == Race.BUILDING_SHIP && p.docks > 1 && nearOurShip(t.getGridX(), t.getGridY()))
                continue;
            // the 20 nearest spaced sites, then every 6th further out, so a site among trees farther off can win
            if (p.site_trees && type != Race.BUILDING_SHIP && ++spaced_seen > 20 && spaced_seen % 6 != 0)
                continue;
            if (type == Race.BUILDING_SHIP || !p.site_trees)
                return new int[]{t.getGridX(), t.getGridY()};
            // builders chop the wood they build with: a site among trees goes up about twice as fast; the armory's
            // gatherers walk to the iron
            int tx = t.getGridX(), ty = t.getGridY();
            float score = .5f * (float) Math.sqrt((double) Geo.dist2(tx, ty, x, y)) + 1.5f * treeDistance(tx, ty);
            if (type == Race.BUILDING_ARMORY)
                score += ironDistance(tx, ty);
            if (score < best_score) {
                best_score = score;
                best = new int[]{tx, ty};
            }
            if (++candidates >= 80)
                break;
        }
        if (best != null)
            return best;
        if (!filter.getResult().isEmpty()) {
            Target t = filter.getResult().getFirst();
            return new int[]{t.getGridX(), t.getGridY()};
        }
        return null;
    }

    private boolean nearOurShip(int x, int y) {
        long gap2 = (long) p.dock_gap * p.dock_gap;
        for (List<Ship> l : List.of(ai.intel.ships, ai.intel.ship_sites))
            for (Ship s : l)
                if (Geo.dist2(s.getGridX(), s.getGridY(), x, y) < gap2)
                    return true;
        return false;
    }

    /** Mean distance in cells to the 6 nearest standing trees within 18 cells (a missing one counts 25). */
    private float treeDistance(int x, int y) {
        var grid = ai.geo.grid;
        int size = grid.getGridSize();
        float[] near = new float[6];
        java.util.Arrays.fill(near, 25f);
        for (int dy = -18; dy <= 18; dy++)
            for (int dx = -18; dx <= 18; dx++) {
                int cx = x + dx, cy = y + dy;
                if (cx < 0 || cy < 0 || cx >= size || cy >= size)
                    continue;
                if (grid.getOccupant(cx, cy) instanceof TreeSupply tree && !tree.isEmpty()) {
                    float d = (float) Math.sqrt((double) (dx * dx + dy * dy));
                    // keep the 6 smallest
                    int worst = 0;
                    for (int i = 1; i < near.length; i++)
                        if (near[i] > near[worst])
                            worst = i;
                    if (d < near[worst])
                        near[worst] = d;
                }
            }
        float sum = 0;
        for (float d : near)
            sum += d;
        return sum / near.length;
    }

    /** Mean distance in cells to the 3 nearest iron nodes of our island (a missing one counts 40). */
    private float ironDistance(int x, int y) {
        float[] near = {40f, 40f, 40f};
        for (com.oddlabs.tt.model.IronSupply s : ai.geo.iron_nodes) {
            if (s.isEmpty() || ai.geo.islandAt(s.getGridX(), s.getGridY()) != 0
                    && ai.geo.islandAt(s.getGridX(), s.getGridY()) != island)
                continue;
            float d = (float) Math.sqrt((double) Geo.dist2(s.getGridX(), s.getGridY(), x, y));
            int worst = 0;
            for (int i = 1; i < near.length; i++)
                if (near[i] > near[worst])
                    worst = i;
            if (d < near[worst])
                near[worst] = d;
        }
        return (near[0] + near[1] + near[2]) / 3f;
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
        // worker_share: an iron weapon is 80 man-s of work and 3 loads (~1.9 gatherer-minutes at our harvest
        // rates), so about 40 % of the peons inside keep up with the rest gathering
        if (p.worker_share > 0)
            return Math.max(3, (int) Math.round(pop * p.worker_share));
        return Math.max(3, Math.min(p.workers_max, pop / p.workers_per));
    }

    /**
     * Whether the armory makes rock axes too. Iron only pays at the unit cap (every slot counts); early on, rock
     * axes are quicker to make (rock_until: until then). Two early-armed enemies once had 140 and 81 warriors to our
     * 50 at 10 minutes, and razed us by 21.
     */
    private boolean rockPhase() {
        // rock_fallback: with the island's iron gone, rock axes beat none (rock is left unused otherwise)
        if (p.rock_fallback && ai.home_iron >= 0 && ai.home_iron <= 2)
            return true;
        return p.rock_weapons || p.rock_until > 0 && ai.now() < p.rock_until;
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
        boolean want_rock = rockPhase() && (iron == 0 || rock > 6);
        // rock_axe_cap: axes nobody takes (the unit cap) are labour lost to iron
        if (p.rock_axe_cap > 0 && armory.getSupplyContainer(RockAxeWeapon.class).getNumSupplies() >= p.rock_axe_cap)
            want_rock = false;
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
        if (p.worker_share > 0 && spare < 0) {
            // short of workers: call back gatherers of whatever the stock has plenty of
            int need = -spare;
            if (wood_stock > 60 && tree > 2) {
                int k = Math.min(need, tree - 2);
                me.recallGatherers(armory, TreeSupply.class, k);
                need -= k;
            }
            if (need > 0 && rock_stock > 20 && rock > 1) {
                int k = Math.min(need, rock - 1);
                me.recallGatherers(armory, RockSupply.class, k);
                need -= k;
            }
            if (need > 0 && iron_stock > 30 && iron > 2)
                me.recallGatherers(armory, IronSupply.class, Math.min(need, iron - 2));
            return;
        }
        if (spare <= 0)
            return;
        // gatherer shares: wood twice the ore, ore mostly iron
        int total = tree + iron + rock + spare;
        int want_tree = (int) (total * p.wood_share);
        int want_rock = rockPhase() ? (int) (total * p.rock_share) : 0;
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

    /** Lets up to n peons out of our quarters (they come out idle, for rowing or work). */
    void releasePeons(@NonNull Intel in, int n) {
        for (Building q : in.quarters) {
            if (n <= 0)
                return;
            int inside = q.getUnitContainer().getNumSupplies() - pendingDeploys(q);
            int k = Math.min(n, inside);
            if (k > 0) {
                me.deployUnits(q, DeployType.PEON, k);
                n -= k;
            }
        }
        // row_min: at the cap the spare peons sit in the armory (full stocks deploy no gatherers); they row too
        if (p.row_min > 0)
            for (Building a : in.armories) {
                if (n <= 0 || a.getIslandId() != island)
                    return;
                int k = Math.min(n, armorySpare(in, a));
                if (k > 0) {
                    me.deployUnits(a, DeployType.PEON, k);
                    n -= k;
                }
            }
    }

    /** The peons in an armory beyond the workers it wants (and those already leaving). */
    int armorySpare(@NonNull Intel in, @NonNull Building armory) {
        return Math.max(0, armory.getUnitContainer().getNumSupplies() - workersWanted(in) - pendingDeploys(armory));
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
        if (p.chieftain && second >= p.chieftain_start && !me.hasActiveChieftain() && !me.isTrainingChieftain()
                && local(in.quarters).size() >= 2 && pop >= 50) {
            Building q = local(in.quarters).getFirst();
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
            } else if (!local(in.armories).isEmpty()) {
                to_armory.add(u);
            } else if (!local(in.quarters).isEmpty()) {
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
                ai.order(group, b, Action.MOVE, false);
        }
    }
}
