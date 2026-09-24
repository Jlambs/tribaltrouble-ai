package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.LandBuilding;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.model.behaviour.RepairController;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Construction jobs, the "scout lays the foundation, helpers join once it is placed" pattern generalised.
 *
 * <p>Lifecycle of a {@link Job}: SCOUTING (one peon walks to the site and places the foundation; helpers are kept
 * nearby chopping wood) -> PLACED (everyone assigned repairs it; crews are topped up from idle peons) -> DONE, or
 * FAILED (placement illegal three times, scout lost repeatedly, or timeout) after which the caller picks another
 * site. Foundations are created by the AI itself so it keeps the reference (the engine's placeBuilding returns
 * nothing and an unplaced building is not in the player's unit set).
 */
public final class Construction {
    public enum State {
        SCOUTING,
        PLACED,
        DONE,
        FAILED
    }

    public static final class Job {
        public final int template_id;
        public final int gx;
        public final int gy;
        public final @NonNull String purpose;
        public int crew_target;
        public @NonNull State state = State.SCOUTING;
        public @Nullable Building building;
        public @Nullable Unit scout;
        public float started_at;
        public float placed_at;
        public int last_hp;
        public float last_progress_at;
        public float last_progress_log = -100f;
        public float last_scout_order;
        public int failures;
        public int illegal_checks;
        /** Peons told to help (they may die or wander; verified each tick). */
        public final List<Unit> crew = new ArrayList<>();

        Job(int template_id, int gx, int gy, @NonNull String purpose, int crew_target, float now) {
            this.template_id = template_id;
            this.gx = gx;
            this.gy = gy;
            this.purpose = purpose;
            this.crew_target = crew_target;
            this.started_at = now;
        }

        public boolean active() {
            return state == State.SCOUTING || state == State.PLACED;
        }

        public @Nullable LandBuilding placedBuilding() {
            return building instanceof LandBuilding b && !b.isDead() && b.isPlaced() ? b : null;
        }

        public boolean isPlaced() {
            return building != null && !building.isDead() && building.isPlaced();
        }

        public boolean isComplete() {
            return building != null && !building.isDead() && building.isPlaced() && building.isComplete();
        }

        @Override
        public @NonNull String toString() {
            return purpose + "@" + gx + "," + gy + " " + state + " crew=" + crew.size() + "/" + crew_target;
        }
    }

    private final @NonNull Orders orders;
    private final @NonNull Jobs jobs;
    private final @NonNull SiteFinder sites;
    private final @NonNull Params params;
    private final @NonNull AiLog log;
    private final List<Job> active = new ArrayList<>();
    private final List<Job> finished = new ArrayList<>();

    public Construction(@NonNull Orders orders, @NonNull Jobs jobs, @NonNull SiteFinder sites, @NonNull Params params,
            @NonNull AiLog log) {
        this.orders = orders;
        this.jobs = jobs;
        this.sites = sites;
        this.params = params;
        this.log = log;
    }

    public @NonNull List<Job> active() {
        return active;
    }

    public boolean hasActive(int template_id) {
        for (Job j : active)
            if (j.template_id == template_id && j.active())
                return true;
        return false;
    }

    public int countActive(int template_id) {
        int n = 0;
        for (Job j : active)
            if (j.template_id == template_id && j.active())
                n++;
        return n;
    }

    public @Nullable Job activeJob(int template_id) {
        for (Job j : active)
            if (j.template_id == template_id && j.active())
                return j;
        return null;
    }

    /** True when an active job owns this building object. */
    public boolean isTracked(@NonNull Building b) {
        for (Job j : active)
            if (j.active() && j.building == b)
                return true;
        return false;
    }

    /** Sites of active jobs (for spacing checks). */
    public @NonNull List<int[]> reservedCells() {
        List<int[]> result = new ArrayList<>();
        for (Job j : active)
            if (j.active())
                result.add(new int[]{j.gx, j.gy});
        return result;
    }

    /** Peons assigned to any active job (scouts and crews). */
    public int assignedPeons() {
        int n = 0;
        for (Job j : active) {
            if (!j.active())
                continue;
            if (j.scout != null && orders.usable(j.scout))
                n++;
            for (Unit u : j.crew)
                if (orders.usable(u) && u != j.scout)
                    n++;
        }
        return n;
    }

    /**
     * Start a job: the scout is ordered to place the foundation now; helpers are recorded and ordered once the
     * foundation exists. Returns null when the site is illegal or the building cap is reached.
     */
    public @Nullable Job start(int template_id, int gx, int gy, @NonNull String purpose, @NonNull Unit scout,
            @NonNull List<Unit> helpers, int crew_target, float now) {
        if (!sites.isLegal(template_id, gx, gy))
            return null;
        Job job = new Job(template_id, gx, gy, purpose, crew_target, now);
        Building b = orders.placeBuilding(template_id, gx, gy, List.of(scout));
        if (b == null)
            return null;
        job.building = b;
        job.scout = scout;
        job.last_scout_order = now;
        jobs.set(scout, Jobs.Kind.BUILD, gx, gy, b, null, now, template_id);
        for (Unit h : helpers) {
            if (h != scout && orders.usable(h)) {
                job.crew.add(h);
                jobs.set(h, Jobs.Kind.BUILD, gx, gy, b, null, now, template_id);
            }
        }
        active.add(job);
        log.info(() -> "construction start " + job + " scout " + scout.getGridX() + "," + scout.getGridY());
        return job;
    }

    /** Add a helper to a job (ordered immediately if the foundation is placed, otherwise pre-positioned). */
    /** Release crew beyond {@code keep} (the ones not yet hammering first); they go back to the economy idle. */
    public void trimCrew(@NonNull Job job, int keep) {
        int have = job.crew.size();
        if (have <= keep)
            return;
        List<Unit> release = new ArrayList<>();
        for (Unit u : job.crew)
            if (!isWorkingOn(u, job.building))
                release.add(u);
        for (Unit u : job.crew)
            if (release.size() < have - keep && !release.contains(u))
                release.add(u);
        int n = 0;
        for (Unit u : release) {
            if (n >= have - keep)
                break;
            job.crew.remove(u);
            jobs.clear(u);
            if (orders.usable(u))
                orders.move(u, u.getGridX(), u.getGridY()); // drop the build order; the allocator picks it up
            n++;
        }
    }

    public void addHelper(@NonNull Job job, @NonNull Unit peon, float now) {
        if (!orders.usable(peon) || job.crew.contains(peon) || peon == job.scout)
            return;
        job.crew.add(peon);
        jobs.set(peon, Jobs.Kind.BUILD, job.gx, job.gy, job.building, null, now, job.template_id);
        if (job.isPlaced()) {
            orders.build(peon, job.building);
        } else {
            preposition(peon, job);
        }
    }

    /** Helpers wait next to a tree near the site and come back holding wood, ready to hammer. */
    private void preposition(@NonNull Unit peon, @NonNull Job job) {
        // walk toward the site but stop short so the 7x7 footprint stays free of moving units when the scout arrives
        int dx = job.gx - peon.getGridX();
        int dy = job.gy - peon.getGridY();
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 9f)
            return; // already close: just stay
        int tx = Math.round(job.gx - dx / len * 8f);
        int ty = Math.round(job.gy - dy / len * 8f);
        orders.move(peon, tx, ty);
    }

    /**
     * Advance every job. Returns the jobs that became DONE this tick (so the caller can react: rally points,
     * garrisons, reassigning the crew).
     */
    public @NonNull List<Job> tick(float now, @NonNull Roster roster) {
        List<Job> done = new ArrayList<>();
        Iterator<Job> it = active.iterator();
        while (it.hasNext()) {
            Job job = it.next();
            if (!job.active()) {
                it.remove();
                continue;
            }
            step(job, now, roster);
            if (job.state == State.DONE) {
                done.add(job);
                finished.add(job);
                it.remove();
            } else if (job.state == State.FAILED) {
                // returned too, so the planner can avoid the site for a while (crews die there)
                done.add(job);
                finished.add(job);
                it.remove();
            }
        }
        return done;
    }

    private void step(@NonNull Job job, float now, @NonNull Roster roster) {
        Building b = job.building;
        if (b != null && b.isDead()) {
            fail(job, "destroyed");
            return;
        }
        if (job.isComplete()) {
            job.state = State.DONE;
            releaseCrew(job);
            log.info(() -> "construction done " + job);
            return;
        }
        if (job.state == State.SCOUTING) {
            if (job.isPlaced()) {
                job.state = State.PLACED;
                job.placed_at = now;
                log.info(() -> "construction placed " + job);
                // everyone recorded as crew starts hammering (they fetch wood first)
                for (Unit h : job.crew)
                    if (orders.usable(h))
                        orders.build(h, b);
                return;
            }
            // the foundation is not down yet: watch the scout; one that never arrives (site behind water or a
            // pocket the pathfinder cannot enter) times out whatever it is doing
            if (now - job.started_at > params.site_fail_timeout * 2f) {
                fail(job, "scout never placed the foundation");
                return;
            }
            Unit scout = job.scout;
            boolean scout_ok = scout != null && orders.usable(scout);
            boolean scout_busy = scout_ok && scout.getPrimaryController() instanceof PlaceBuildingController pc
                    && pc.getBuilding() == b;
            if (now - job.last_progress_log >= 30f) {
                job.last_progress_log = now;
                final Unit fs = scout;
                log.info(() -> "construction scouting " + job + " scout " + (fs == null
                        || fs.isDead() ? "none" : "at " + fs.getGridX() + "," + fs.getGridY() + " dist=" + (int) Math.hypot(
                                fs.getGridX() - job.gx,
                                fs.getGridY() - job.gy) + " ctrl=" + (fs.getPrimaryController() == null ? "none" : fs.getPrimaryController().getClass().getSimpleName())));
            }
            if (!scout_ok || (!scout_busy && now - job.last_scout_order > 3f)) {
                // scout died or the placement was rejected (units moving through the footprint, cap) -> retry
                job.failures++;
                if (job.failures > params.site_retries || now - job.started_at > params.site_fail_timeout) {
                    fail(job, scout_ok ? "placement rejected " + job.failures + " times" : "scout lost");
                    return;
                }
                if (!sites.isLegal(job.template_id, job.gx, job.gy)) {
                    // transiently illegal (moving units) or permanently (a building went up): retry a few times
                    job.illegal_checks++;
                    if (job.illegal_checks >= 4) {
                        fail(job, "site illegal");
                        return;
                    }
                    job.last_scout_order = now; // wait another 3 s before the next try
                    return;
                }
                Unit next = scout_ok ? scout : nearestPeon(roster, job, null);
                if (next == null)
                    return;
                Building fresh = orders.placeBuilding(job.template_id, job.gx, job.gy, List.of(next));
                if (fresh == null) {
                    if (job.failures > params.site_retries - 1)
                        fail(job, "cannot place");
                    return;
                }
                job.building = fresh;
                job.scout = next;
                job.last_scout_order = now;
                job.crew.remove(next);
                jobs.set(next, Jobs.Kind.BUILD, job.gx, job.gy, fresh, null, now, job.template_id);
                // re-point the crew's job records at the new building object
                for (Unit h : job.crew)
                    if (orders.usable(h))
                        jobs.set(h, Jobs.Kind.BUILD, job.gx, job.gy, fresh, null, now, job.template_id);
                log.info(() -> "construction retry " + job);
            }
            return;
        }
        // PLACED: keep the crew working and topped up. The timeout is progress based: a foundation whose hit points
        // have not grown for a long while is abandoned, a slow build that is still rising is not (a fixed clock
        // from placement once failed an armory whose crew arrived late and was hammering).
        int hp = b.getHitPoints();
        if (hp > job.last_hp) {
            job.last_hp = hp;
            job.last_progress_at = now;
        }
        float stalled = now - Math.max(job.placed_at, job.last_progress_at);
        int working = 0;
        Iterator<Unit> it = job.crew.iterator();
        while (it.hasNext()) {
            Unit u = it.next();
            if (!orders.usable(u)) {
                it.remove();
                continue;
            }
            if (isWorkingOn(u, b)) {
                working++;
            } else if (Roster.activity(u) == Roster.Activity.IDLE || Roster.activity(u) == Roster.Activity.WALK) {
                // helper arrived / lost its controller: order it again (fetches wood, hammers)
                orders.build(u, b);
                jobs.set(u, Jobs.Kind.BUILD, job.gx, job.gy, b, null, now, job.template_id);
            }
        }
        if (job.scout != null && orders.usable(job.scout) && isWorkingOn(job.scout, b))
            working++;
        job.crew_target = Math.max(job.crew_target, 1);
        // no progress with a crew on it for a minute means the builders cannot get wood (no tree in their search
        // radius): abandon the site so the planner picks another; without a crew allow much longer
        boolean stuck_with_crew = working >= 3 && stalled > params.site_stall_timeout;
        if (stuck_with_crew || stalled > params.site_fail_timeout * 2f
                || now - job.placed_at > params.site_fail_timeout * 8f) {
            fail(job, "build timeout (" + (int) stalled + " s without progress, " + working + " working)");
            return;
        }
        if (now - job.last_progress_log >= 30f) {
            job.last_progress_log = now;
            final int fw = working, fhp = hp;
            log.info(
                    () -> "construction progress " + job + " hp=" + fhp + "/" + b.getTemplate().getMaxHitPoints() + " working=" + fw);
        }
    }

    public static boolean isWorkingOn(@NonNull Unit u, @Nullable Building b) {
        if (b == null || u.isDead())
            return false;
        var c = u.getPrimaryController();
        return (c instanceof RepairController r && r.getBuilding() == b)
                || (c instanceof PlaceBuildingController p && p.getBuilding() == b);
    }

    /** Number of peons currently hammering/placing this job's building. */
    public int workers(@NonNull Job job) {
        int n = 0;
        if (job.scout != null && orders.usable(job.scout) && isWorkingOn(job.scout, job.building))
            n++;
        for (Unit u : job.crew)
            if (orders.usable(u) && u != job.scout && isWorkingOn(u, job.building))
                n++;
        return n;
    }

    private void fail(@NonNull Job job, @NonNull String why) {
        job.state = State.FAILED;
        releaseCrew(job);
        // the crew still holds the engine's repair order for the abandoned foundation (a builder that cannot find
        // wood keeps that order forever): drop it, so the peons read as idle and the planner can re-crew them
        List<Unit> all = new ArrayList<>(job.crew);
        if (job.scout != null)
            all.add(job.scout);
        for (Unit u : all)
            if (orders.usable(u))
                orders.move(u, u.getGridX(), u.getGridY());
        log.info(() -> "construction FAILED " + job + ": " + why);
    }

    private void releaseCrew(@NonNull Job job) {
        if (job.scout != null)
            jobs.clear(job.scout);
        for (Unit u : job.crew)
            jobs.clear(u);
    }

    private @Nullable Unit nearestPeon(@NonNull Roster roster, @NonNull Job job, @Nullable Unit exclude) {
        Unit best = null;
        int best_d2 = Integer.MAX_VALUE;
        for (Unit u : job.crew) {
            if (u == exclude || !orders.usable(u))
                continue;
            int d2 = BasePlan.dist2(u.getGridX(), u.getGridY(), job.gx, job.gy);
            if (d2 < best_d2) {
                best_d2 = d2;
                best = u;
            }
        }
        if (best != null)
            return best;
        for (Unit u : roster.peons) {
            if (u == exclude || !orders.usable(u) || jobs.kindOf(u) == Jobs.Kind.BUILD)
                continue;
            int d2 = BasePlan.dist2(u.getGridX(), u.getGridY(), job.gx, job.gy);
            if (d2 < best_d2) {
                best_d2 = d2;
                best = u;
            }
        }
        return best;
    }

    public static @NonNull String name(int template_id) {
        return switch (template_id) {
            case Race.BUILDING_QUARTERS -> "quarters";
            case Race.BUILDING_ARMORY -> "armory";
            case Race.BUILDING_TOWER -> "tower";
            default -> "building";
        };
    }
}
