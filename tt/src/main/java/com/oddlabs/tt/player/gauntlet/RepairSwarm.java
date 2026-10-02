package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.IronSupply;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.HarvestController;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.RepairBehaviour;
import com.oddlabs.tt.model.behaviour.RepairController;
import com.oddlabs.tt.model.behaviour.WalkController;
import com.oddlabs.tt.player.gauntlet.Intel.PeonState;
import com.oddlabs.tt.player.gauntlet.Intel.WarriorType;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * repair_swarm (Strategy), each economy tick before allocatePeons: our damaged quarters, armories and towers, also
 * under attack, get as many repairers as hold the damage (Siege: measured, or modelled from the enemy warriors in
 * reach), plus the missing hit points over swarm_catchup_ticks, at most a share of the cells around the building
 * (repairers block the way, RepairBehaviour.isBlocking). Only peons that carry wood are sent (a piece is 5 HP at 1
 * HP/s): wood carriers nearby, wood transporters out of the armory itself (swarm_lee: to the side away from the
 * attackers) or out of a quiet armory within swarm_reach, and tree gatherers only where a tree stands within
 * TREE_CELLS. A repairer that has spent its piece walks into an armory to be sent out again instead of to a far tree:
 * unfed repairers walking for wood were the peon columns of enter_move's note. Every peon it orders is marked BUILD
 * for this tick's allocatePeons, and its crew and fresh transporters are kept from evacuatePeons, the shepherds, lures
 * and sappers (exempt). A building it cannot hold (damage beyond its ring, falling, below half) is given up for
 * ABANDON_HOLD_TICKS; salvage then empties it.
 */
final class RepairSwarm {
    /** Wood carriers within this many cells of the building are sent to it. */
    private static final int CARRIER_CELLS = 16;
    /** Tree gatherers repair only a building with a tree within this many cells: else they would walk for wood. */
    private static final int TREE_CELLS = 6;
    private static final int GATHERER_CELLS = 20;
    /** Wood transporters queued in an armory at once (0.5 s each: warriors go out first). */
    private static final int BATCH = 8;
    /** Workers an armory keeps inside when it feeds its own repairs. */
    private static final int KEEP_WORKERS = 6;
    /** Cells from an armory's centre the lee rally stands, away from the attackers (its spawn ring is at 4). */
    private static final int LEE_CELLS = 5;
    /**
     * Fresh transporters within this many cells of a lee rally's armory or of a feeding armory are claimed (and exempt
     * until then). The design's 6 cells for a feeding armory left those spawned 7-8 cells out (its ring of spawn cells
     * full) exempt but never claimed.
     */
    private static final int CLAIM_CELLS = 8;
    /** A transporter still walking to the lee cell is claimed once this close to it. */
    private static final int LEE_ARRIVED_CELLS = 3;
    /** Enemy warriors within this many cells give the lee its side. */
    private static final int LEE_THREAT_CELLS = 15;
    /** A remote feeding armory has no threat within this many cells, so its transporters come out idle safely. */
    private static final int SOURCE_QUIET_CELLS = 12;
    /** A building given up is left alone this long. */
    private static final float ABANDON_HOLD_TICKS = 1000f; // 20 s
    /** A wood carrier whose repair order gave up after one walk is ordered again at most this often. */
    private static final float REORDER_TICKS = 150f; // 3 s
    private static final float LOG_TICKS = 500f; // 10 s

    private final @NonNull GauntletAI ai;
    private final @NonNull Economy economy;
    private final @NonNull Siege siege;
    /** Peons the swarm sent, and the building each repairs. */
    private final Map<@NonNull Unit, @NonNull Building> crew = new LinkedHashMap<>();
    /** Armories whose rally point the swarm set to the lee cell given. */
    private final Map<@NonNull Building, int @NonNull []> lee = new LinkedHashMap<>();
    /** Armories whose wood transporters are on their way out, and the building they are for. */
    private final Map<@NonNull Building, @NonNull Building> feeding = new LinkedHashMap<>();
    /** Buildings the swarm works on this tick: manageRepairs leaves them alone. */
    private final Set<@NonNull Building> handled = new LinkedHashSet<>();
    private final Map<@NonNull Building, Float> abandoned = new LinkedHashMap<>();
    private final Map<@NonNull Building, Float> last_log = new LinkedHashMap<>();
    private final Map<@NonNull Unit, Float> reordered = new LinkedHashMap<>();
    /** Per building the swarm worked on since it was damaged: pieces of wood sent to it and crew seen dying. */
    private final Map<@NonNull Building, int @NonNull []> worked = new LinkedHashMap<>();

    RepairSwarm(@NonNull GauntletAI ai, @NonNull Economy economy, @NonNull Siege siege) {
        this.ai = ai;
        this.economy = economy;
        this.siege = siege;
    }

    /** Whether manageRepairs should leave the building to the swarm this tick. */
    boolean handled(@NonNull Building b) {
        return handled.contains(b);
    }

    /**
     * Whether the peon is the swarm's: in its crew, or a wood carrier by a lee rally or a feeding armory, which it
     * claims once it stands there (fresh transporters are neither sheltered nor sent into the armory).
     */
    boolean exempt(@NonNull Unit u) {
        if (crew.containsKey(u))
            return true;
        if ((lee.isEmpty() && feeding.isEmpty()) || u.isDead() || !Economy.carriesWood(u))
            return false;
        int r2 = CLAIM_CELLS * CLAIM_CELLS;
        for (Building b : lee.keySet())
            if (!b.isDead() && MapAnalysis.dist2(u.getGridX(), u.getGridY(), b.getGridX(), b.getGridY()) <= r2)
                return true;
        for (Building s : feeding.keySet())
            if (!s.isDead() && MapAnalysis.dist2(u.getGridX(), u.getGridY(), s.getGridX(), s.getGridY()) <= r2)
                return true;
        return false;
    }

    void tick() {
        handled.clear();
        Intel intel = ai.intel();
        float now = ai.now();
        abandoned.entrySet().removeIf(e -> e.getKey().isDead() || now - e.getValue() > ABANDON_HOLD_TICKS);
        endEpisodes();
        prune();
        recycle();
        // Candidates: hot ones first, armories, then quarters, then towers; then the cold ones (swarm_cold).
        List<Building> hot = new ArrayList<>();
        List<Building> cold = new ArrayList<>();
        for (List<Building> group : List.of(intel.armories, intel.quarters, intel.towers))
            for (Building b : group) {
                if (b.isDead() || !b.isComplete() || !b.isDamaged() || economy.isDoomed(b)
                        || economy.isEvacuating(b) || abandoned.containsKey(b))
                    continue;
                (isHot(b) ? hot : cold).add(b);
            }
        cleanLee(hot);
        claim();
        for (Building b : hot)
            work(b, true);
        if (ai.strategy().swarm_cold)
            for (Building b : cold)
                work(b, false);
    }

    /**
     * Under attack: an enemy warrior within 4 cells beyond its reach, an enemy peon within 5 cells of a tower, or hit
     * points lost over the last samples.
     */
    private boolean isHot(@NonNull Building b) {
        int x = b.getGridX();
        int y = b.getGridY();
        int r = Siege.reach(b) + 4;
        if (ai.military().threatNearEcon(x, y, r)) {
            if (Combat.countNear(ai.intel().enemy_warriors, x, y, r) > 0)
                return true;
            if (Siege.isTower(b) && Combat.countNear(ai.intel().enemy_peons, x, y, 5) > 0)
                return true;
        }
        return siege.measuredLoss(b) > 0f;
    }

    /** A building the swarm worked on fell or stands repaired (counted once), or is being emptied. */
    private void endEpisodes() {
        for (Iterator<Map.Entry<Building, int[]>> it = worked.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Building, int[]> e = it.next();
            Building b = e.getKey();
            String end;
            if (b.isDead()) {
                end = "fell";
                ai.aiLog().count("swarm_fell");
            } else if (!b.isDamaged()) {
                end = "done";
                ai.aiLog().count("swarm_done");
            } else if (economy.isEvacuating(b)) {
                end = economy.isSalvaging(b) ? "salvage" : "evacuated";
            } else {
                continue;
            }
            it.remove();
            last_log.remove(b);
            int[] w = e.getValue();
            String why = end;
            ai.aiLog().log("SWARM", () -> String.format("swarm at %d,%d ends (%s): %d pieces, %d dead", b.getGridX(),
                    b.getGridY(), why, w[0], w[1]));
        }
        last_log.keySet().removeIf(Building::isDead);
    }

    /**
     * Drops crew members that are gone (killed: swarm_dead; into a building: swarm_gone), whose building fell or
     * stands repaired (a spent one with no tree by it is sent into an armory first), and those given other work since;
     * a wood carrier whose repair gave up after one walk is ordered again, at most every REORDER_TICKS.
     */
    private void prune() {
        Intel intel = ai.intel();
        float now = ai.now();
        for (Iterator<Map.Entry<Unit, Building>> it = crew.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Unit, Building> e = it.next();
            Unit u = e.getKey();
            Building b = e.getValue();
            if (u.isDead()) {
                // A killed unit is dead at once (Unit.startDying removes it) with 0 hit points; one that walked into
                // a building (WorkerUnitContainer, ReproduceUnitContainer: removeNow) keeps its own.
                if (u.getHitPoints() <= 0) {
                    ai.aiLog().count("swarm_dead");
                    int[] w = worked.get(b);
                    if (w != null)
                        w[1]++;
                } else {
                    ai.aiLog().count("swarm_gone");
                }
                it.remove();
                reordered.remove(u);
                continue;
            }
            Controller primary = u.getPrimaryController();
            boolean ours = primary instanceof RepairController rc && rc.getBuilding() == b;
            if (b.isDead() || !b.isDamaged()) {
                // A repairer with no wood fetches a piece even for a building that needs none (RepairController
                // checks the wood first): with no tree by it, into an armory instead.
                if (!b.isDead() && ours && spent(u) && !treesBy(b))
                    sendIn(u, b);
                it.remove();
                reordered.remove(u);
                continue;
            }
            if (ours)
                continue;
            PeonState s = intel.peon_states.get(u);
            if ((s == PeonState.IDLE || s == PeonState.FIGHT) && Economy.carriesWood(u) && !economy.isEvacuating(b)
                    && !abandoned.containsKey(b)) {
                Float last = reordered.get(u);
                if (last == null || ai.periodDue(last, REORDER_TICKS)) {
                    reordered.put(u, now);
                    ai.owner().setTarget(Selectable.newArray(u), b, Action.GATHER_REPAIR, false);
                    intel.peon_states.put(u, PeonState.BUILD);
                    intel.builder_sites.put(u, b);
                    ai.aiLog().count("swarm_reorder");
                }
                continue;
            }
            it.remove();
            reordered.remove(u);
            ai.aiLog().count("swarm_release");
        }
    }

    /** Whether a crew member has spent its piece and walks to a tree for the next (RepairController's harvest). */
    private static boolean spent(@NonNull Unit u) {
        return u.getCurrentController() instanceof HarvestController<?>;
    }

    private boolean treesBy(@NonNull Building b) {
        return ai.map().treesAround(b.getGridX(), b.getGridY(), TREE_CELLS) > 0;
    }

    /**
     * A crew member that spent its piece at a building with no tree by it walks into an armory instead (one 0.5-s
     * deploy per piece instead of a walk to a far tree and back): into the salvage's refuge while the building is
     * salvaged.
     */
    private void recycle() {
        for (Iterator<Map.Entry<Unit, Building>> it = crew.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Unit, Building> e = it.next();
            Unit u = e.getKey();
            Building b = e.getValue();
            if (u.isDead() || b.isDead() || !spent(u) || treesBy(b))
                continue;
            it.remove();
            reordered.remove(u);
            sendIn(u, b);
        }
    }

    /**
     * MOVE into the salvage's refuge while b is salvaged, else into a source of wood (b itself if it is an armory, the
     * armory feeding it, or the nearest quiet armory within swarm_reach), else into the main armory (its refuge while
     * it is salvaged); never into an armory emptied or given up, which is expected to fall with them inside. With none
     * the peon is left to the economy.
     */
    private void sendIn(@NonNull Unit u, @NonNull Building b) {
        Building dest = economy.salvageDest(b);
        if (dest == null)
            dest = source(b);
        if (dest != null) {
            ai.aiLog().count("swarm_recycle");
        } else {
            Building home = economy.homeFor(ai.intel().armory());
            if (home != null && !economy.isEvacuating(home) && !abandoned.containsKey(home))
                dest = home;
            ai.aiLog().count("swarm_release");
        }
        if (dest != null && !dest.isDead() && !u.isDead()) {
            ai.owner().setTarget(Selectable.newArray(u), dest, Action.MOVE, false);
            ai.intel().peon_states.put(u, PeonState.TRANSIT);
        }
    }

    private @Nullable Building source(@NonNull Building b) {
        // not into an armory given up: it is expected to fall with them inside (a self-fed one is its own feeder)
        if (isArmory(b) && !economy.isEvacuating(b) && !abandoned.containsKey(b))
            return b;
        for (Map.Entry<Building, Building> e : feeding.entrySet()) {
            Building s = e.getKey();
            if (e.getValue() == b && !s.isDead() && !economy.isEvacuating(s) && !abandoned.containsKey(s))
                return s;
        }
        int reach2 = ai.strategy().swarm_reach * ai.strategy().swarm_reach;
        Building best = null;
        int best_d = reach2 + 1;
        for (Building a : ai.intel().armories) {
            if (a.isDead() || !a.isComplete() || economy.isEvacuating(a) || economy.isDoomed(a)
                    || abandoned.containsKey(a))
                continue;
            int d = MapAnalysis.dist2(a.getGridX(), a.getGridY(), b.getGridX(), b.getGridY());
            if (d < best_d && !ai.military().threatNearEcon(a.getGridX(), a.getGridY(), SOURCE_QUIET_CELLS)) {
                best_d = d;
                best = a;
            }
        }
        return best;
    }

    private static boolean isArmory(@NonNull Building b) {
        return b.getTemplate().getTemplateID() == Race.BUILDING_ARMORY;
    }

    /**
     * A lee rally ends with its building's siege (no longer hot or damaged, emptied, in a sortie or given up): the
     * rally point is cleared when it is still the lee cell (a sortie or a salvage sets its own).
     */
    private void cleanLee(@NonNull List<@NonNull Building> hot) {
        for (Iterator<Map.Entry<Building, int[]>> it = lee.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Building, int[]> e = it.next();
            Building b = e.getKey();
            if (b.isDead()) {
                it.remove();
                continue;
            }
            if (hot.contains(b) && !ai.military().isSortie(b))
                continue;
            clearLee(b, e.getValue());
            it.remove();
        }
    }

    /** Clears the armory's rally point when it is still the lee cell (a sortie or a salvage sets its own). */
    private void clearLee(@NonNull Building b, int @NonNull [] cell) {
        if (b.isDead() || !b.hasRallyPoint())
            return;
        Target r = b.getRallyPoint();
        if (!(r instanceof Building) && MapAnalysis.dist2(r.getGridX(), r.getGridY(), cell[0], cell[1]) <= 2 * 2)
            ai.owner().setRallyPoint(b, b);
    }

    /**
     * The swarm's own transporters queued in a source armory: none once it is emptied (salvage queues its stock as
     * transporters too, so the queue is salvage's then).
     */
    private int queued(@NonNull Building s) {
        return economy.isEvacuating(s) ? 0 : s.getDeployContainer(DeployType.PEON_TRANSPORT_TREE).getNumSupplies();
    }

    /**
     * Fresh transporters: those by a lee rally once idle or within LEE_ARRIVED_CELLS of the cell (still walking ones
     * walk on), and idle ones by a feeding armory, repair the building they were sent for (into the salvage's refuge
     * while it is salvaged). Idle includes one whose idle scan has started a hunt (a peon has 1 HP). A feed ends once
     * its queue is empty (or its armory is emptied) and none of its transporters walks by it.
     */
    private void claim() {
        for (Map.Entry<Building, int[]> e : lee.entrySet()) {
            Building b = e.getKey();
            int[] cell = e.getValue();
            for (Unit u : candidates(b.getGridX(), b.getGridY(), CLAIM_CELLS, true))
                if (!walking(u) || MapAnalysis.dist2(u.getGridX(), u.getGridY(), cell[0],
                        cell[1]) <= LEE_ARRIVED_CELLS * LEE_ARRIVED_CELLS)
                    claimFor(u, b);
        }
        for (Iterator<Map.Entry<Building, Building>> it = feeding.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Building, Building> e = it.next();
            Building s = e.getKey();
            Building b = e.getValue();
            if (s.isDead() || b.isDead() || !b.isDamaged()) {
                it.remove();
                continue;
            }
            boolean waiting = false;
            for (Unit u : candidates(s.getGridX(), s.getGridY(), CLAIM_CELLS, true)) {
                if (!walking(u))
                    claimFor(u, b);
                else
                    waiting = true;
            }
            if (!waiting && queued(s) == 0)
                it.remove();
        }
    }

    /**
     * A claimed transporter repairs b; while b is salvaged it walks into the refuge; while b is emptied otherwise,
     * given up or doomed it is left to the economy.
     */
    private void claimFor(@NonNull Unit u, @NonNull Building b) {
        Building dest = economy.salvageDest(b);
        if (dest != null && !dest.isDead()) {
            ai.owner().setTarget(Selectable.newArray(u), dest, Action.MOVE, false);
            ai.intel().peon_states.put(u, PeonState.TRANSIT);
            ai.aiLog().count("swarm_claimed");
            return;
        }
        if (economy.isEvacuating(b) || economy.isDoomed(b) || abandoned.containsKey(b))
            return;
        order(u, b);
        ai.aiLog().count("swarm_claimed");
    }

    /**
     * Wood carriers not in the crew within r cells of (x, y), idle (a hunt its idle scan started included) or, with
     * moving, walking (to a lee rally, say), alive and nobody else's (sortie, a reserved placer), nearest first.
     */
    private @NonNull List<@NonNull Unit> candidates(int x, int y, int r, boolean moving) {
        Intel intel = ai.intel();
        int r2 = r * r;
        List<Unit> out = new ArrayList<>();
        for (Unit u : intel.peons) {
            if (u.isDead() || crew.containsKey(u) || u.getHitPoints() <= 0)
                continue;
            PeonState s = intel.peon_states.get(u);
            boolean idle = s == PeonState.IDLE
                    || (scanHunt(u, s) && u.getCurrentController() instanceof HuntController);
            if (!idle && !(moving && walking(u)))
                continue;
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y) > r2 || !Economy.carriesWood(u)
                    || ai.military().inSortie(u) || economy.reservedPlacer(u))
                continue;
            out.add(u);
        }
        sortNearest(out, x, y);
        return out;
    }

    /**
     * A fresh unit's IdleController scans at once (Unit's constructor): with an enemy within 8 cells it pushes an
     * aggressive walk and a hunt under the rally order, so Intel calls the transporter FIGHT (its primary is that
     * walk) while it walks to the rally and once it hunts.
     */
    private static boolean scanHunt(@NonNull Unit u, @Nullable PeonState s) {
        return s == PeonState.FIGHT && u.getPrimaryController() instanceof WalkController w && w.isAgressive();
    }

    /** Whether a fresh transporter still walks to its rally (a plain walk on top, over a scan's hunt or not). */
    private boolean walking(@NonNull Unit u) {
        PeonState s = ai.intel().peon_states.get(u);
        return s == PeonState.MOVE || (scanHunt(u, s) && u.getCurrentController() instanceof WalkController w
                && !w.isAgressive());
    }

    /** Sorts by distance to (x, y); the sort is stable, so ties keep list order. */
    private static void sortNearest(@NonNull List<@NonNull Unit> units, int x, int y) {
        units.sort((a, c) -> Integer.compare(MapAnalysis.dist2(a.getGridX(), a.getGridY(), x, y),
                MapAnalysis.dist2(c.getGridX(), c.getGridY(), x, y)));
    }

    /** GATHER_REPAIR on b (DEFAULT would enter a finished armory), marked BUILD for this tick's allocatePeons. */
    private void order(@NonNull Unit u, @NonNull Building b) {
        ai.owner().setTarget(Selectable.newArray(u), b, Action.GATHER_REPAIR, false);
        crew.put(u, b);
        Intel intel = ai.intel();
        intel.peon_states.put(u, PeonState.BUILD);
        intel.builder_sites.put(u, b);
        handled.add(b);
        int[] w = worked.computeIfAbsent(b, k -> new int[2]);
        if (Economy.carriesWood(u))
            w[0]++;
    }

    /** What the swarm sent to a building this tick, for its log line. */
    private static final class Sent {
        int carriers;
        int self;
        int remote;
        int gatherers;
        @Nullable
        Building from;
        int @Nullable [] lee_cell;
    }

    /** Sizes the repairs of one damaged building and sends what it lacks. */
    private void work(@NonNull Building b, boolean hot) {
        Strategy st = ai.strategy();
        Intel intel = ai.intel();
        boolean tower = Siege.isTower(b);
        int x = b.getGridX();
        int y = b.getGridY();
        // Chicken axes bounce onto the units next to a tower (they outrank it), so its repairers die.
        if (tower && hot && chickensNear(x, y, Siege.reach(b)) >= 2) {
            ai.aiLog().count("swarm_tower_bounce");
            return;
        }
        ai.aiLog().count(hot ? "swarm_hot" : "swarm_cold");
        float measured = hot ? siege.measuredLoss(b) : 0f;
        int repairing = siege.repairing(b);
        float model = hot ? siege.damageModel(b, 0f) : 0f;
        float d = hot ? Math.max(measured + repairing, model) : 0f;
        int max = b.getTemplate().getMaxHitPoints();
        int hp = b.getHitPoints();
        int cap = tower ? st.swarm_ring_tower : st.swarm_ring_qa;
        if (hot && d > cap && measured > 0f && 2 * hp < max) {
            abandon(b, d, measured, cap);
            return;
        }
        int want = Math.min(cap, (int) Math.ceil(
                st.swarm_margin * d + (max - hp) * (float) GauntletAI.TICKS_PER_SECOND / st.swarm_catchup_ticks));
        int have = 0;
        for (Building c : crew.values())
            if (c == b)
                have++;
        for (Map.Entry<Unit, Building> e : intel.builder_sites.entrySet()) {
            Unit u = e.getKey();
            if (e.getValue() != b || crew.containsKey(u) || u.isDead())
                continue;
            if (u.getCurrentBehaviour() instanceof RepairBehaviour || Economy.carriesWood(u))
                have++;
        }
        int pend = pending(b);
        int need = want - have - pend;
        if (need > 0) {
            int room = room();
            if (need > room) {
                ai.aiLog().count("swarm_cap_share");
                need = Math.max(0, room);
            }
        }
        Sent sent = new Sent();
        if (need > 0) {
            sent.carriers = carriers(b, need);
            need -= sent.carriers;
        }
        if (need > 0 && isArmory(b)) {
            sent.self = selfFeed(b, hot, need, sent);
            need -= sent.self;
        }
        // an armory that sends its own wood is fed by no other
        if (need > 0 && sent.self == 0) {
            sent.remote = remoteFeed(b, hot, need, sent);
            need -= sent.remote;
        }
        if (need > 0 && treesBy(b)) {
            sent.gatherers = selfSupply(b, need);
            need -= sent.gatherers;
        }
        int ordered = sent.carriers + sent.self + sent.remote + sent.gatherers;
        if (have + pend + ordered > 0)
            handled.add(b);
        if (ordered > 0)
            worked.computeIfAbsent(b, k -> new int[2]);
        if (ordered > 0 && ai.logging()) {
            Float last = last_log.get(b);
            if (last == null || ai.periodDue(last, LOG_TICKS)) {
                last_log.put(b, ai.now());
                logWork(b, hp, max, d, model, measured, repairing, want, cap, sent);
            }
        }
    }

    private void logWork(@NonNull Building b, int hp, int max, float d, float model, float measured, int repairing,
            int want, int cap, @NonNull Sent sent) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "swarm on the %s at %d,%d: hp %d/%d, damage %.1f hp/s (model %.1f, measured %.1f, %d repairing), %d wanted (cap %d): %d carriers",
                Intel.kind(b), b.getGridX(), b.getGridY(), hp, max,
                d, model, measured, repairing, want, cap, sent.carriers));
        Building from = sent.from;
        if (sent.self > 0 && from != null)
            sb.append(String.format(", %d out of the armory itself (wood %d, iron %d)", sent.self,
                    Economy.stock(from, TreeSupply.class), Economy.stock(from, IronSupply.class)));
        if (sent.remote > 0 && from != null)
            sb.append(String.format(", %d out of the armory at %d,%d (%d cells, wood %d)", sent.remote,
                    from.getGridX(), from.getGridY(), (int) Math.sqrt(MapAnalysis.dist2(from.getGridX(),
                            from.getGridY(), b.getGridX(), b.getGridY())), Economy.stock(from, TreeSupply.class)));
        if (sent.gatherers > 0)
            sb.append(", ").append(sent.gatherers).append(" tree gatherers");
        int[] cell = sent.lee_cell != null ? sent.lee_cell : lee.get(b);
        if (cell != null)
            sb.append(", lee ").append(cell[0]).append(',').append(cell[1]);
        String text = sb.toString();
        ai.aiLog().log("SWARM", () -> text);
    }

    private int chickensNear(int x, int y, int r) {
        int r2 = r * r;
        int n = 0;
        for (Unit e : ai.intel().enemy_warriors)
            if (!e.isDead() && Intel.warriorType(e) == WarriorType.CHICKEN
                    && MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= r2)
                n++;
        return n;
    }

    /**
     * Gives the building up: the crew members not repairing right now go (evacuatePeons shelters them), those
     * repairing finish their piece and are sent into an armory after it. Its feeds end: the transporters still queued
     * for it (or in it) stay in, since they would come out idle among its attackers, and its lee rally is cleared, so
     * those out are the economy's and evacuatePeons' again.
     */
    private void abandon(@NonNull Building b, float d, float measured, int cap) {
        ai.aiLog().count("swarm_abandon");
        abandoned.put(b, ai.now());
        int released = 0;
        for (Iterator<Map.Entry<Unit, Building>> it = crew.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Unit, Building> e = it.next();
            Unit u = e.getKey();
            if (e.getValue() != b || (!u.isDead() && u.getCurrentBehaviour() instanceof RepairBehaviour))
                continue;
            it.remove();
            reordered.remove(u);
            released++;
            ai.aiLog().count("swarm_release");
        }
        int cancelled = 0;
        for (Iterator<Map.Entry<Building, Building>> it = feeding.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Building, Building> e = it.next();
            Building s = e.getKey();
            if (e.getValue() != b && s != b)
                continue;
            it.remove();
            int k = s.isDead() ? 0 : queued(s);
            if (k > 0) {
                ai.owner().deployUnits(s, DeployType.PEON_TRANSPORT_TREE, -k); // the UI's decrease button
                cancelled += k;
                for (int i = 0; i < k; i++)
                    ai.aiLog().count("swarm_cancelled");
            }
        }
        int[] cell = lee.remove(b);
        if (cell != null)
            clearLee(b, cell);
        int out = released;
        int kept = cancelled;
        ai.aiLog().log("SWARM", () -> String.format(
                "swarm gives up the %s at %d,%d: hp %d/%d, damage %.1f hp/s (measured %.1f) above its %d cells, %d released, %d transporters kept in",
                Intel.kind(b), b.getGridX(), b.getGridY(), b.getHitPoints(),
                b.getTemplate().getMaxHitPoints(), d, measured, cap, out, kept));
    }

    /**
     * Wood on its way to b and not claimed yet: transporters queued in the armories feeding it, and those out by them
     * (by the lee rally of b).
     */
    private int pending(@NonNull Building b) {
        int n = 0;
        for (Map.Entry<Building, Building> e : feeding.entrySet()) {
            Building s = e.getKey();
            if (e.getValue() != b || s.isDead())
                continue;
            n += queued(s);
            n += candidates(s.getGridX(), s.getGridY(), CLAIM_CELLS, false).size();
        }
        if (lee.containsKey(b))
            for (Unit u : candidates(b.getGridX(), b.getGridY(), CLAIM_CELLS, true))
                if (walking(u))
                    n++;
        return n;
    }

    /**
     * Repairers the swarm may still send: its crew and the wood queued for it stay within swarm_peon_share of our peons
     * (out and in the armories), at least 8.
     */
    private int room() {
        Intel intel = ai.intel();
        int peons = intel.peons.size();
        for (Building a : intel.armories)
            if (!a.isDead())
                peons += a.getUnitContainer().getNumSupplies();
        int limit = Math.max(8, (int) (ai.strategy().swarm_peon_share * peons));
        int used = crew.size();
        for (Building s : feeding.keySet())
            if (!s.isDead())
                used += queued(s);
        return limit - used;
    }

    /**
     * Idle, walking or tree-gathering peons carrying wood within CARRIER_CELLS, nearest first; not fresh transporters
     * (exempt), which are pending and claimed where they were sent (a lee walker taken on its way would repair on the
     * attackers' side).
     */
    private int carriers(@NonNull Building b, int need) {
        Intel intel = ai.intel();
        int x = b.getGridX();
        int y = b.getGridY();
        List<Unit> pool = new ArrayList<>();
        for (Unit u : intel.peons) {
            if (u.isDead() || crew.containsKey(u) || u.getHitPoints() <= 0)
                continue;
            PeonState s = intel.peon_states.get(u);
            // not TRANSIT: those go somewhere on purpose, salvage evacuees included
            if (s != PeonState.IDLE && s != PeonState.MOVE && s != PeonState.GATHER_TREE)
                continue;
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y) > CARRIER_CELLS * CARRIER_CELLS
                    || !Economy.carriesWood(u) || ai.military().inSortie(u) || economy.reservedPlacer(u)
                    || exempt(u))
                continue;
            pool.add(u);
        }
        sortNearest(pool, x, y);
        int n = 0;
        for (Unit u : pool) {
            if (n >= need)
                break;
            if (u.isDead())
                continue;
            order(u, b);
            ai.aiLog().count("swarm_carriers");
            n++;
        }
        return n;
    }

    /**
     * The armory feeds its own repairs: it keeps max(KEEP_WORKERS, weapon_reserve's) workers and its wood reserve
     * (hot: swarm_wood_reserve, and two per iron in stock, which the forge can use; cold: swarm_cold_reserve), leaves
     * a bank a sortie could win with (or is out on) alone, lets its warriors out first and queues at most BATCH. Hot,
     * with swarm_lee and no rally point of its own, its transporters walk to the lee cell first, the side away from
     * the attackers' centroid; else they come out idle by the door.
     */
    private int selfFeed(@NonNull Building b, boolean hot, int need, @NonNull Sent sent) {
        Strategy st = ai.strategy();
        Military military = ai.military();
        int workers = b.getUnitContainer().getNumSupplies();
        int keep = Math.max(KEEP_WORKERS, economy.weaponReserveHeld(b));
        int wood = Economy.stock(b, TreeSupply.class);
        int reserve = reserve(b, hot);
        if (workers <= keep || wood <= reserve || military.isSortie(b) || military.sortieCouldWin(b))
            return 0;
        if (b.getDeployContainer(DeployType.RUBBER_WARRIOR).getNumSupplies() + b.getDeployContainer(
                DeployType.IRON_WARRIOR).getNumSupplies() + b.getDeployContainer(
                        DeployType.ROCK_WARRIOR).getNumSupplies() > 0)
            return 0;
        int queued = b.getDeployContainer(DeployType.PEON_TRANSPORT_TREE).getNumSupplies();
        int k = Math.min(Math.min(need, BATCH - queued), Math.min(wood - reserve, workers - keep));
        if (k <= 0)
            return 0;
        if (hot && st.swarm_lee && !b.hasRallyPoint()) {
            int[] cell = leeCell(b);
            if (cell != null) {
                ai.owner().setRallyPoint(b, cell[0], cell[1]);
                lee.put(b, cell);
                sent.lee_cell = cell;
                ai.aiLog().count("swarm_lee");
            }
        }
        feeding.put(b, b);
        ai.owner().deployUnits(b, DeployType.PEON_TRANSPORT_TREE, k);
        sent.from = b;
        ai.aiLog().count("swarm_feed");
        for (int i = 0; i < k; i++)
            ai.aiLog().count("swarm_self_units");
        return k;
    }

    private int reserve(@NonNull Building a, boolean hot) {
        Strategy st = ai.strategy();
        return hot ? Math.max(st.swarm_wood_reserve, 2 * Economy.stock(a, IronSupply.class)) : st.swarm_cold_reserve;
    }

    /**
     * LEE_CELLS from the armory's centre on the side away from the centroid of the enemy warriors within
     * LEE_THREAT_CELLS (clamped into the map as evacuate does), or null with none.
     */
    private int @Nullable [] leeCell(@NonNull Building b) {
        int x = b.getGridX();
        int y = b.getGridY();
        long sx = 0;
        long sy = 0;
        int n = 0;
        for (Unit e : ai.intel().enemy_warriors) {
            if (e.isDead() || MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) > LEE_THREAT_CELLS * LEE_THREAT_CELLS)
                continue;
            sx += e.getGridX();
            sy += e.getGridY();
            n++;
        }
        if (n == 0)
            return null;
        float dx = x - sx / (float) n;
        float dy = y - sy / (float) n;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f)
            return null;
        int size = ai.map().getSize();
        int lx = Math.max(3, Math.min(size - 4, x + Math.round(LEE_CELLS * dx / len)));
        int ly = Math.max(3, Math.min(size - 4, y + Math.round(LEE_CELLS * dy / len)));
        return new int[]{lx, ly};
    }

    /**
     * The nearest other armory within swarm_reach that can spare wood and workers sends them (orderWood's tests: half
     * its workers, at least 4, stay; nothing of its own queued), quiet within SOURCE_QUIET_CELLS and with no rally
     * point, so its transporters come out idle by its door; feeding nothing else.
     */
    private int remoteFeed(@NonNull Building b, boolean hot, int need, @NonNull Sent sent) {
        Strategy st = ai.strategy();
        Military military = ai.military();
        int reach2 = st.swarm_reach * st.swarm_reach;
        Building src = null;
        int best = reach2 + 1;
        int k = 0;
        for (Building s : ai.intel().armories) {
            if (s == b || s.isDead() || !s.isComplete())
                continue;
            int d = MapAnalysis.dist2(s.getGridX(), s.getGridY(), b.getGridX(), b.getGridY());
            if (d >= best)
                continue;
            if (economy.isEvacuating(s) || economy.isDoomed(s) || military.isSortie(s) || s.hasRallyPoint())
                continue;
            Building f = feeding.get(s);
            if (f != null && f != b)
                continue;
            if (military.threatNearEcon(s.getGridX(), s.getGridY(), SOURCE_QUIET_CELLS))
                continue;
            int wood = Economy.stock(s, TreeSupply.class);
            int reserve = reserve(s, hot);
            int workers = s.getUnitContainer().getNumSupplies();
            int floor = Math.max(Math.max(4, workers / 2), economy.weaponReserveHeld(s));
            if (wood <= reserve || workers <= floor)
                continue;
            if (s.getDeployContainer(DeployType.PEON).getNumSupplies() + s.getDeployContainer(
                    DeployType.PEON_TRANSPORT_TREE).getNumSupplies() > 0)
                continue;
            best = d;
            src = s;
            k = Math.min(Math.min(need, BATCH), Math.min(wood - reserve, workers - floor));
        }
        if (src == null || k <= 0) {
            if (hot)
                ai.aiLog().count("swarm_nosource");
            return 0;
        }
        feeding.put(src, b);
        ai.owner().deployUnits(src, DeployType.PEON_TRANSPORT_TREE, k);
        if (sent.from == null)
            sent.from = src;
        ai.aiLog().count("swarm_feed");
        for (int i = 0; i < k; i++)
            ai.aiLog().count("swarm_feed_units");
        return k;
    }

    /**
     * With a tree within TREE_CELLS: the nearest tree gatherers within GATHERER_CELLS that carry no wood (a carrier is
     * taken as one above); never ore gatherers, whose load the first wood chopped would destroy.
     */
    private int selfSupply(@NonNull Building b, int need) {
        Intel intel = ai.intel();
        int x = b.getGridX();
        int y = b.getGridY();
        List<Unit> pool = new ArrayList<>();
        for (Unit u : intel.peons) {
            if (u.isDead() || crew.containsKey(u) || u.getHitPoints() <= 0
                    || intel.peon_states.get(u) != PeonState.GATHER_TREE)
                continue;
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), x, y) > GATHERER_CELLS * GATHERER_CELLS
                    || Economy.carriesWood(u) || ai.military().inSortie(u) || economy.reservedPlacer(u))
                continue;
            pool.add(u);
        }
        sortNearest(pool, x, y);
        int n = 0;
        for (Unit u : pool) {
            if (n >= need)
                break;
            if (u.isDead())
                continue;
            order(u, b);
            ai.aiLog().count("swarm_selfsupply");
            n++;
        }
        return n;
    }
}
