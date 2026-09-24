package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides what to build next and runs the opening: first quarters at the start with almost everyone, scouts laying
 * the second quarters and the armory foundations at once, crews rolling from one site to the next, then a third
 * quarters, the tower ring around the armory, more quarters as the peon count grows, and rebuilds.
 */
public final class BuildPlanner {
    private final @NonNull Orders orders;
    private final @NonNull Jobs jobs;
    private final @NonNull Construction construction;
    private final @NonNull BasePlan plan;
    private final @NonNull Economy economy;
    private final @NonNull Params params;
    private final @NonNull AiLog log;

    private boolean opening_started;
    private boolean armory_ever_complete;
    private float last_tower_started = -1000f;
    private float last_quarters_started = -1000f;
    private float last_site_failure = -1000f;
    private int quarters_built;
    private int towers_built;
    private Construction.@Nullable Job q1_job;
    private Construction.@Nullable Job q2_job;
    private Construction.@Nullable Job armory_job;

    public BuildPlanner(@NonNull Orders orders, @NonNull Jobs jobs, @NonNull Construction construction,
            @NonNull BasePlan plan, @NonNull Economy economy, @NonNull Params params, @NonNull AiLog log) {
        this.orders = orders;
        this.jobs = jobs;
        this.construction = construction;
        this.plan = plan;
        this.economy = economy;
        this.params = params;
        this.log = log;
    }

    public void tick(float now, @NonNull Roster roster, @NonNull Intel intel, @NonNull Threat threat,
            @NonNull Military military) {
        List<Construction.Job> done = construction.tick(now, roster);
        for (Construction.Job job : done) {
            if (job.state == Construction.State.FAILED) {
                failed_sites.add(new int[]{job.gx, job.gy, (int) now});
                if (job.purpose.equals("A2"))
                    expand_blocked_until = now + params.expand_retry_after; // the enemy found it: wait it out
                continue; // nothing to staff or rally
            }
            onDone(job, now, roster);
        }
        failed_sites.removeIf(f -> now - f[2] > 150f);
        if (!opening_started) {
            opening(now, roster);
            return;
        }
        LandBuilding armory = roster.mainArmory();
        boolean armory_pending = construction.hasActive(Race.BUILDING_ARMORY);
        if (armory == null && !armory_pending) {
            rebuildArmory(now, roster, intel);
            return;
        }
        if (armory == null) {
            // the armory is being built: keep crews on it and on the second quarters; births head there too
            Construction.Job aj = construction.activeJob(Race.BUILDING_ARMORY);
            economy.opening_site = aj == null ? null : new int[]{aj.gx, aj.gy};
            keepOpeningCrews(now, roster);
            return;
        }
        economy.opening_site = null;
        armory_ever_complete = true;
        // the armory stands: no quarters crew is topped up beyond a normal crew any more (the existing crew stays)
        for (Construction.Job job : construction.active())
            if (job.template_id == Race.BUILDING_QUARTERS && job.crew_target > params.crew_quarters + 4)
                job.crew_target = params.crew_quarters + 4;
        keepCrewsStaffed(now, roster);
        boolean emergency = economy.demands.emergency;
        if (emergency)
            return;
        int peons = roster.totalPeons();
        int active_jobs = construction.active().size();
        int max_jobs = peons >= 50 ? 2 : 1;
        if (active_jobs >= max_jobs)
            return;
        if (now - last_site_failure < 8f)
            return;
        // an enemy group at the gates kills every crew that walks out: no new sites until it is gone (the first
        // two towers are worth the risk, they are what drives it off)
        if ((threat.level == Threat.Level.ALERT || threat.level == Threat.Level.ENGAGED) && roster.towers.size() >= 2
                && threat.approach_value >= 6f)
            return;
        if (maybeExpand(now, roster, intel, threat, military, armory, active_jobs))
            return;
        // quarters wanted: from the sustainable rate (each quarters at 3 breeders gives ~0.13 peons/s)
        int quarters_target = Math.clamp((int) Math.ceil(economy.rate_star / 0.131f) + 1, params.quarters_min,
                params.quarters_max);
        if (now < 300f)
            quarters_target = Math.min(quarters_target, params.opening_quarters);
        if (roster.unitCount() >= roster.unitCap() - 20)
            quarters_target = Math.min(quarters_target, roster.quarters.size());
        int quarters_now = roster.quarters.size() + construction.countActive(
                Race.BUILDING_QUARTERS) + countUnderConstruction(roster, Race.BUILDING_QUARTERS);
        int towers_now = roster.towers.size() + construction.countActive(Race.BUILDING_TOWER) + countUnderConstruction(
                roster, Race.BUILDING_TOWER);
        int towers_target = towersWanted(now, roster, threat);
        boolean can_build_more = roster.buildingCount() + active_jobs < roster.buildingCap();
        if (!can_build_more)
            return;
        // priority: a third quarters, then the first two towers, then alternate
        boolean want_quarters = quarters_now < quarters_target && now - last_quarters_started > 20f;
        boolean want_tower = towers_now < towers_target && now - last_tower_started > 15f;
        if (want_quarters && (quarters_now < 3 || !want_tower || towers_now >= 2)) {
            if (startQuarters(now, roster, armory))
                return;
        }
        if (want_tower) {
            startTower(now, roster, intel, threat, armory, towers_now);
        }
    }

    private float last_expand_try = -1000f;
    /** Recently failed construction sites {gx, gy, time}. */
    private final List<int[]> failed_sites = new ArrayList<>();
    private float expand_blocked_until = -1f;

    /**
     * Expansion armory: once the iron by the main armory is mined out (mean iron distance beyond
     * {@code expand_iron_m}), raise a second armory by the richest fresh iron cluster on our half. Gatherers drop
     * their load at the nearest armory, so the walk per iron unit shrinks from minutes to seconds; the economy
     * staffs it with workers and it forges and deploys warriors like the main one.
     */
    private boolean maybeExpand(float now, @NonNull Roster roster, @NonNull Intel intel, @NonNull Threat threat,
            @NonNull Military military, @NonNull LandBuilding armory, int active_jobs) {
        if (!params.expansion_armory || now < params.expand_min_time || now - last_expand_try < 60f
                || now < expand_blocked_until)
            return false;
        if (roster.armories.size() != 1 || construction.hasActive(Race.BUILDING_ARMORY)
                || countUnderConstruction(roster, Race.BUILDING_ARMORY) > 0)
            return false;
        if (economy.d_iron_m < params.expand_iron_m || military.posture == Military.Posture.DEFEND
                || threat.level == Threat.Level.ALERT || threat.level == Threat.Level.ENGAGED)
            return false;
        if (roster.buildingCount() + active_jobs >= roster.buildingCap() - 1 || roster.totalPeons() < 60)
            return false;
        last_expand_try = now;
        SiteFinder.Site site = plan.chooseExpansionSite(armory.getGridX(), armory.getGridY(), params.expand_max_dist);
        if (site == null) {
            log.info("expansion: no site by fresh iron on our half");
            return false;
        }
        if (intel.warriorsNear(site.gx(), site.gy(), 40) > 0) {
            log.info(() -> "expansion: site " + site.gx() + "," + site.gy() + " contested, waiting");
            return false;
        }
        List<Unit> crew = economy.requestCrew(params.crew_armory_expand, site.gx(), site.gy(), roster, now, true);
        if (crew.size() < 4)
            return false;
        Unit scout = nearest(crew, site.gx(), site.gy(), null);
        crew.remove(scout);
        Construction.Job job = construction.start(Race.BUILDING_ARMORY, site.gx(), site.gy(), "A2", scout, crew,
                params.crew_armory_expand, now);
        if (job == null)
            return false;
        log.info(() -> String.format("EXPANSION armory at %d,%d (iron %.0f m from the main one)", site.gx(), site.gy(),
                economy.d_iron_m));
        return true;
    }

    private int countUnderConstruction(@NonNull Roster roster, int template_id) {
        int n = 0;
        for (LandBuilding b : roster.under_construction)
            if (b.getTemplate().getTemplateID() == template_id && !construction.isTracked(b))
                n++;
        return n;
    }

    private int towersWanted(float now, @NonNull Roster roster, @NonNull Threat threat) {
        int t = 2;
        if (now >= 480f)
            t = 4;
        if (now >= 720f)
            t = 6 + (int) ((now - 720f) / 60f);
        if (threat.level == Threat.Level.ALERT || threat.level == Threat.Level.ENGAGED)
            t += 1;
        // wood piling up in the armory is free tower material
        LandBuilding armory = roster.mainArmory();
        if (armory != null && now >= 600f && Economy.stock(armory, com.oddlabs.tt.landscape.TreeSupply.class) >= 120)
            t += 2;
        // never starve the quarters count and keep some building slots
        return Math.min(t, Math.min(params.towers_max, roster.buildingCap() - params.quarters_max - 1));
    }

    // ------------------------------------------------------------------ opening

    /** Sites of the opening quarters (Q1 near the start, the rest in a line toward the armory site). */
    private final List<int[]> opening_sites = new ArrayList<>();
    private int opening_index;

    /**
     * Opening: everybody raises the first quarters at once (one scout walks ahead to lay the armory foundation so
     * the site is reserved and grows while nobody is there); when it is done the whole crew moves to the next
     * quarters, and after {@code opening_quarters} quarters to the armory. Births join the current site.
     */
    private void opening(float now, @NonNull Roster roster) {
        if (roster.peons.isEmpty())
            return;
        opening_started = true;
        List<Unit> peons = new ArrayList<>();
        for (Unit p : roster.peons)
            if (orders.usable(p))
                peons.add(p);
        if (peons.isEmpty())
            return;
        // plan the opening quarters sites: Q1 by the start, then along the line start -> armory
        List<int[]> reserved = new ArrayList<>();
        reserved.add(new int[]{plan.armory_x, plan.armory_y});
        int[] q1 = new int[]{plan.q1_x, plan.q1_y};
        reserved.add(q1);
        opening_sites.add(q1);
        for (int i = 1; i < params.opening_quarters; i++) {
            float f = (float) i / params.opening_quarters;
            int ax = Math.round(plan.q1_x + (plan.armory_x - plan.q1_x) * f);
            int ay = Math.round(plan.q1_y + (plan.armory_y - plan.q1_y) * f);
            final int fax = ax;
            final int fay = ay;
            SiteFinder.Site s = plan.nextQuartersSite(ax, ay, reserved);
            if (s == null) {
                log.info("opening: no site for quarters " + (i + 1) + " near " + fax + "," + fay);
                continue;
            }
            int[] c = new int[]{s.gx(), s.gy()};
            reserved.add(c);
            opening_sites.add(c);
        }
        Unit s1 = nearest(peons, plan.q1_x, plan.q1_y, null);
        peons.remove(s1);
        Unit s_armory = peons.size() > 6 ? nearest(peons, plan.armory_x, plan.armory_y, null) : null;
        if (s_armory != null)
            peons.remove(s_armory);
        q1_job = construction.start(Race.BUILDING_QUARTERS, plan.q1_x, plan.q1_y, "Q1", s1, peons, 99, now);
        if (q1_job == null) {
            SiteFinder.Site alt = plan.nextQuartersSite(plan.start_x, plan.start_y, reserved);
            if (alt != null) {
                opening_sites.set(0, new int[]{alt.gx(), alt.gy()});
                q1_job = construction.start(Race.BUILDING_QUARTERS, alt.gx(), alt.gy(), "Q1", s1, peons, 99, now);
            }
        }
        opening_index = 1;
        if (s_armory != null) {
            armory_job = construction.start(Race.BUILDING_ARMORY, plan.armory_x, plan.armory_y, "armory", s_armory,
                    List.of(), params.crew_armory_opening, now);
            if (armory_job == null) {
                plan.chooseArmorySite();
                armory_job = construction.start(Race.BUILDING_ARMORY, plan.armory_x, plan.armory_y, "armory", s_armory,
                        List.of(), params.crew_armory_opening, now);
            }
            if (armory_job != null)
                armory_job.crew_target = 1; // the scout alone until the quarters are up
        }
        log.info(() -> "opening: " + opening_sites.size() + " quarters sites, armory scout " + (armory_job != null));
    }

    /**
     * During the opening move the whole crew from a finished quarters to the next site; once the planned quarters
     * stand, everybody goes to the armory.
     */
    /**
     * Opening crews: up to two quarters go up at once (the quarters count is what the cube-root breeding law
     * rewards, so four quarters by minute four beats three in sequence), each finished quarters keeps
     * {@code breeders_early} breeders and frees the rest for the next site, and the armory takes a real crew as
     * soon as two quarters stand instead of waiting for the last one.
     */
    private void keepOpeningCrews(float now, @NonNull Roster roster) {
        int active_q = construction.countActive(Race.BUILDING_QUARTERS);
        int quarters_done = roster.quarters.size();
        if (active_q < params.opening_parallel && quarters_done + active_q < params.opening_quarters
                && opening_index < opening_sites.size() && (quarters_done >= 1 || active_q == 0)) {
            int[] site = opening_sites.get(opening_index);
            // the first two quarters take everyone; from the third on a normal crew is enough, so births and the
            // freed builders feed the armory (an armory with two workers while seventy peons hammer a quarters
            // means no warriors when the first rush arrives)
            int target = quarters_done + active_q >= 2 ? params.crew_quarters + 4 : 99;
            List<Unit> crew = economy.requestCrew(target, site[0], site[1], roster, now, false);
            if (crew.size() >= 3) {
                Unit scout = nearest(crew, site[0], site[1], null);
                crew.remove(scout);
                Construction.Job job = construction.start(Race.BUILDING_QUARTERS, site[0], site[1],
                        "Q" + (quarters_done + active_q + 1), scout, crew, target, now);
                opening_index++;
                if (job == null) {
                    last_site_failure = now;
                    return;
                }
                last_quarters_started = now;
            }
        }
        Construction.Job aj = construction.activeJob(Race.BUILDING_ARMORY);
        if (aj != null) {
            if (quarters_done >= params.opening_quarters || (active_q == 0 && opening_index >= opening_sites.size()))
                aj.crew_target = 99; // the armory gets everyone now
            else if (quarters_done >= 2)
                aj.crew_target = params.crew_armory_opening;
        }
        keepCrewsStaffed(now, roster);
    }

    private void onDone(Construction.@NonNull Job job, float now, @NonNull Roster roster) {
        LandBuilding b = job.placedBuilding();
        if (b == null)
            return;
        switch (job.template_id) {
            case Race.BUILDING_QUARTERS -> {
                quarters_built++;
                // a few builders become breeders; the rest are freed (the planner staffs the next job)
                List<Unit> crew = crewOf(job);
                int breeders = 0;
                for (Unit u : crew) {
                    if (breeders >= params.breeders_early)
                        break;
                    orders.enter(u, b);
                    jobs.set(u, Jobs.Kind.ENTER, b.getGridX(), b.getGridY(), b, null, now, 0);
                    breeders++;
                }
                if (roster.mainArmory() == null) {
                    // births walk toward the armory site until the armory exists
                    orders.setRally(b, plan.armory_x, plan.armory_y);
                }
            }
            case Race.BUILDING_ARMORY -> {
                // builders holding wood entered by themselves; anyone idle goes in too
                for (Unit u : crewOf(job)) {
                    orders.enter(u, b);
                    jobs.set(u, Jobs.Kind.ENTER, b.getGridX(), b.getGridY(), b, null, now, 0);
                }
                economy.bootstrap_until = now + 25f;
            }
            case Race.BUILDING_TOWER -> towers_built++;
            default -> {
            }
        }
    }

    private @NonNull List<Unit> crewOf(Construction.@NonNull Job job) {
        List<Unit> result = new ArrayList<>();
        if (job.scout != null && orders.usable(job.scout))
            result.add(job.scout);
        for (Unit u : job.crew)
            if (orders.usable(u) && !result.contains(u))
                result.add(u);
        return result;
    }

    /** Keep every active job staffed up to its crew target with the cheapest peons available. */
    private void keepCrewsStaffed(float now, @NonNull Roster roster) {
        for (Construction.Job job : construction.active()) {
            if (!job.active())
                continue;
            if (!job.isPlaced()) {
                // helpers wait until the foundation is down, except in the opening where they chop nearby
                continue;
            }
            int have = construction.workers(job) + countArriving(job);
            int need = job.crew_target - have;
            if (need <= 0)
                continue;
            boolean allow_workers = roster.mainArmory() != null && job.template_id != Race.BUILDING_ARMORY;
            List<Unit> crew = economy.requestCrew(need, job.gx, job.gy, roster, now, allow_workers);
            for (Unit u : crew)
                construction.addHelper(job, u, now);
        }
    }

    private int countArriving(Construction.@NonNull Job job) {
        int n = 0;
        for (Unit u : job.crew)
            if (orders.usable(u) && !Construction.isWorkingOn(u, job.building))
                n++;
        return n;
    }

    // ------------------------------------------------------------------ later buildings

    private boolean startQuarters(float now, @NonNull Roster roster, @NonNull LandBuilding armory) {
        List<int[]> reserved = reservedCells(roster);
        SiteFinder.Site site = plan.nextQuartersSite(armory.getGridX(), armory.getGridY(), reserved);
        if (site == null) {
            last_site_failure = now;
            log.info("no quarters site found");
            return false;
        }
        List<Unit> crew = economy.requestCrew(params.crew_quarters, site.gx(), site.gy(), roster, now, true);
        if (crew.isEmpty())
            return false;
        Unit scout = nearest(crew, site.gx(), site.gy(), null);
        crew.remove(scout);
        Construction.Job job = construction.start(Race.BUILDING_QUARTERS, site.gx(), site.gy(),
                "Q" + (roster.quarters.size() + 1), scout, crew, params.crew_quarters, now);
        if (job == null) {
            last_site_failure = now;
            return false;
        }
        last_quarters_started = now;
        return true;
    }

    private boolean startTower(float now, @NonNull Roster roster, @NonNull Intel intel, @NonNull Threat threat,
            @NonNull LandBuilding armory, int index) {
        List<int[]> towers = new ArrayList<>();
        for (LandBuilding t : roster.towers)
            towers.add(BasePlan.centre(t));
        for (LandBuilding t : roster.under_construction)
            if (t.getTemplate().getTemplateID() == Race.BUILDING_TOWER)
                towers.add(BasePlan.centre(t));
        for (Construction.Job j : construction.active())
            if (j.template_id == Race.BUILDING_TOWER)
                towers.add(new int[]{j.gx, j.gy});
        List<int[]> reserved = reservedCells(roster);
        SiteFinder.Site site;
        // the expansion armory gets two towers of its own before the ring at home grows further
        LandBuilding a2 = roster.armories.size() >= 2 ? roster.armories.get(1) : null;
        if (a2 != null) {
            int near_a2 = 0;
            for (int[] t : towers)
                if (BasePlan.dist2(t[0], t[1], a2.getGridX(), a2.getGridY()) <= 20 * 20)
                    near_a2++;
            if (near_a2 < 2) {
                List<int[]> avoid = new ArrayList<>(reserved);
                avoid.addAll(towers);
                site = plan.towerSiteNear(a2.getGridX(), a2.getGridY(), 14, avoid, 8);
                if (site != null) {
                    List<Unit> crew = economy.requestCrew(params.crew_tower, site.gx(), site.gy(), roster, now, true);
                    if (!crew.isEmpty()) {
                        Unit scout = nearest(crew, site.gx(), site.gy(), null);
                        crew.remove(scout);
                        if (construction.start(Race.BUILDING_TOWER, site.gx(), site.gy(), "T-A2", scout, crew,
                                params.crew_tower, now) != null) {
                            last_tower_started = now;
                            return true;
                        }
                    }
                }
            }
        }
        if (index >= 2 && index % 3 == 2 && economy.d_iron_m > 30f) {
            // every third tower covers the iron field the gatherers use
            site = ironFieldTowerSite(armory, roster, reserved, towers);
            if (site == null)
                site = plan.nextTowerSite(armory.getGridX(), armory.getGridY(), index, towers, reserved);
        } else {
            site = plan.nextTowerSite(armory.getGridX(), armory.getGridY(), index, towers, reserved);
        }
        // the ring bearing for this index may be blocked (trees, water, a quarters): try the other bearings, then
        // any legal spot around the armory, so a blocked bearing never stalls the tower schedule
        for (int k = 1; site == null && k < 8; k++)
            site = plan.nextTowerSite(armory.getGridX(), armory.getGridY(), index + k, towers, reserved);
        if (site == null) {
            List<int[]> avoid = new ArrayList<>(reserved);
            avoid.addAll(towers);
            site = plan.towerSiteNear(armory.getGridX(), armory.getGridY(), params.tower_ring_radius + 10, avoid, 8);
        }
        if (site == null) {
            last_site_failure = now;
            log.info("no tower site found for index " + index);
            return false;
        }
        int crew_size = params.crew_tower;
        if (threat.level == Threat.Level.ALERT && roster.towers.size() < 2)
            crew_size = params.crew_tower * 2;
        List<Unit> crew = economy.requestCrew(crew_size, site.gx(), site.gy(), roster, now, true);
        if (crew.isEmpty())
            return false;
        Unit scout = nearest(crew, site.gx(), site.gy(), null);
        crew.remove(scout);
        Construction.Job job = construction.start(Race.BUILDING_TOWER, site.gx(), site.gy(), "T" + (index + 1), scout,
                crew, crew_size, now);
        if (job == null) {
            last_site_failure = now;
            return false;
        }
        last_tower_started = now;
        return true;
    }

    private SiteFinder.@Nullable Site ironFieldTowerSite(@NonNull LandBuilding armory, @NonNull Roster roster,
            @NonNull List<int[]> reserved, @NonNull List<int[]> towers) {
        // centroid of the nearest live iron nodes
        List<ResourceMap.Node> nodes = economyIronNodes(armory);
        if (nodes.isEmpty())
            return null;
        int sx = 0, sy = 0;
        for (ResourceMap.Node n : nodes) {
            sx += n.grid_x;
            sy += n.grid_y;
        }
        int cx = sx / nodes.size();
        int cy = sy / nodes.size();
        List<int[]> all = new ArrayList<>(reserved);
        all.addAll(towers);
        for (int[] t : towers)
            if (BasePlan.dist2(t[0], t[1], cx, cy) <= 12 * 12)
                return null; // already covered
        return plan.towerSiteNear(cx, cy, 10, all, 3);
    }

    private @NonNull List<ResourceMap.Node> economyIronNodes(@NonNull LandBuilding armory) {
        return plan_resources().nearestAlive(ResourceMap.Kind.IRON, armory.getGridX(), armory.getGridY(),
                params.gather_radius, 8);
    }

    private ResourceMap plan_resources;

    public void setResources(@NonNull ResourceMap r) {
        plan_resources = r;
    }

    private @NonNull ResourceMap plan_resources() {
        return plan_resources;
    }

    private void rebuildArmory(float now, @NonNull Roster roster, @NonNull Intel intel) {
        if (now - last_site_failure < 10f)
            return;
        int gx = plan.fallback_x;
        int gy = plan.fallback_y;
        // if we never had an armory (opening failure) or the fallback is unusable, re-plan near the quarters
        if (!armory_ever_complete || gx < 0 || !sitesLegal(Race.BUILDING_ARMORY, gx, gy)) {
            int ax = roster.quarters.isEmpty() ? plan.start_x : roster.quarters.getFirst().getGridX();
            int ay = roster.quarters.isEmpty() ? plan.start_y : roster.quarters.getFirst().getGridY();
            if (!armory_ever_complete) {
                plan.chooseArmorySite();
                gx = plan.armory_x;
                gy = plan.armory_y;
            } else {
                SiteFinder.Site s = plan.towerSiteNear(ax, ay, 30, reservedCells(roster), 11);
                // towerSiteNear searches size-3 sites; search a size-5 site around the quarters instead
                s = quartersSizedSiteNear(ax, ay, roster);
                if (s == null) {
                    last_site_failure = now;
                    return;
                }
                gx = s.gx();
                gy = s.gy();
            }
        }
        List<Unit> crew = economy.requestCrew(params.crew_armory_rebuild, gx, gy, roster, now, false);
        // pull peons out of the quarters too (leave one breeder each)
        for (LandBuilding q : roster.quarters) {
            int inside = Roster.unitsInside(q);
            if (inside > 1) {
                orders.setRally(q, gx, gy);
                orders.deploy(q, com.oddlabs.tt.model.DeployType.PEON, inside - 1);
            }
        }
        if (crew.isEmpty()) {
            last_site_failure = now;
            return;
        }
        Unit scout = nearest(crew, gx, gy, null);
        crew.remove(scout);
        Construction.Job job = construction.start(Race.BUILDING_ARMORY, gx, gy, "armory-rebuild", scout, crew,
                params.crew_armory_rebuild, now);
        if (job == null) {
            last_site_failure = now;
            return;
        }
        log.info("REBUILDING ARMORY at " + gx + "," + gy);
    }

    private SiteFinder.@Nullable Site quartersSizedSiteNear(int ax, int ay, @NonNull Roster roster) {
        List<int[]> reserved = reservedCells(roster);
        return plan.nextQuartersSite(ax, ay, reserved);
    }

    private boolean sitesLegal(int template_id, int gx, int gy) {
        return construction_sites().isLegal(template_id, gx, gy);
    }

    private SiteFinder site_finder;

    public void setSiteFinder(@NonNull SiteFinder s) {
        site_finder = s;
    }

    private @NonNull SiteFinder construction_sites() {
        return site_finder;
    }

    private @NonNull List<int[]> reservedCells(@NonNull Roster roster) {
        List<int[]> reserved = new ArrayList<>(construction.reservedCells());
        for (LandBuilding b : roster.quarters)
            reserved.add(BasePlan.centre(b));
        for (LandBuilding b : roster.armories)
            reserved.add(BasePlan.centre(b));
        for (LandBuilding b : roster.towers)
            reserved.add(BasePlan.centre(b));
        for (LandBuilding b : roster.under_construction)
            reserved.add(BasePlan.centre(b));
        // sites where a crew just died or a foundation was destroyed: not again for a while
        for (int[] f : failed_sites)
            reserved.add(new int[]{f[0], f[1]});
        return reserved;
    }

    private static @Nullable Unit nearest(@NonNull List<Unit> units, int gx, int gy, @Nullable Unit exclude) {
        Unit best = null;
        int best_d2 = Integer.MAX_VALUE;
        for (Unit u : units) {
            if (u == exclude || u.isDead())
                continue;
            int d2 = BasePlan.dist2(u.getGridX(), u.getGridY(), gx, gy);
            if (d2 < best_d2) {
                best_d2 = d2;
                best = u;
            }
        }
        return best;
    }

    public boolean openingDone() {
        return armory_ever_complete;
    }

    public @NonNull String status() {
        StringBuilder sb = new StringBuilder("q" + quarters_built + " t" + towers_built);
        for (Construction.Job j : construction.active())
            sb.append(' ').append(j);
        return sb.toString();
    }
}
