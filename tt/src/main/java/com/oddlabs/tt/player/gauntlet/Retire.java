package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.player.gauntlet.Intel.PeonState;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * retire: frees a slot under the 20-building cap for a flagged armory project (a reloc hop or a reloc_lock move) that
 * has waited unplaced retire_wait_ticks at the cap, by razing one building of ours with the explicit attack order, as a
 * human does with the attack button and a click on it (Unit.setTarget attacks a friendly target on Action.ATTACK;
 * raze.md). The slot frees on the tick of the razing; nothing is refunded and units inside vanish, so the building is
 * emptied first. It looks, in this order, for a stalled site (placed, no builders, SITE_AGE_TICKS old), a stranded
 * tower (no quarters or armory within COVER_CELLS), a quiet drained armory (not primary, nobody inside, no stock or
 * gatherers, no threat within 30), a far quarters (retire_quarters_dist cells from the main armory, units >=
 * retire_pop, another quarters within 25 cells, not training the chieftain) and, with retire_any_tower, any tower,
 * never one with a threat within 20 cells. While it empties and falls the building is doomed: tower manning, peon
 * filling and shelter, repairs, the primary armory and the chieftain's training leave it alone (isDoomed), and no
 * armory site goes within RETIRED_CELLS of it for RETIRED_MEMORY_TICKS after (retiredNear). A retirement is called off
 * when a threat comes, when the slot is no longer wanted, or when the building has become our main or last armory or
 * our last quarters. At most one retirement every GAP_TICKS. Times are in game ticks.
 */
final class Retire {
    /** The kinds of building retired, in the order they are looked for (counter and log names). */
    private static final String @NonNull [] KINDS = {"site", "tower", "armory", "quarters", "anytower"};
    private static final int SITE = 0;
    private static final int TOWER = 1;
    private static final int ARMORY = 2;
    private static final int QUARTERS = 3;
    /** retire_any_tower: the last resort, any quiet tower. */
    private static final int ANY_TOWER = 4;
    /** A tower with a finished quarters or armory within this many cells covers it (not stranded). */
    private static final int COVER_CELLS = 25;
    /** A placed site this old with no builders is stalled. */
    private static final float SITE_AGE_TICKS = 6000f; // 120 s
    /**
     * At most one retirement per GAP_TICKS (from its order); another try RETRY_TICKS after one called off or given up.
     */
    private static final float GAP_TICKS = 6000f; // 120 s
    private static final float RETRY_TICKS = 1500f; // 30 s
    /** Game ticks between searches for a building to retire while none is found. */
    private static final float SEARCH_EVERY_TICKS = 250f; // 5 s
    /** A tower's gunner has this long to come out (then the tower goes down with it); others this long to empty. */
    private static final float EMPTY_WAIT_TOWER_TICKS = 500f; // 10 s
    private static final float EMPTY_WAIT_TICKS = 3000f; // 60 s
    /** Game ticks to find razers before the retirement is given up. */
    private static final float UNITS_WAIT_TICKS = 1500f; // 30 s
    /** Razers that stopped attacking are ordered again every REORDER_TICKS. */
    private static final float REORDER_TICKS = 150f; // 3 s
    /** Peons, and lent warriors, are looked for within this many cells of the building. */
    private static final int UNIT_CELLS = 80;
    private static final int WARRIOR_CELLS = 60;
    /** No armory site within RETIRED_CELLS of a retired building for RETIRED_MEMORY_TICKS. */
    private static final int RETIRED_CELLS = 12;
    private static final float RETIRED_MEMORY_TICKS = 3000f; // 60 s
    /** Game ticks within which the flagged project placed after a retirement counts as having used its slot. */
    private static final float SLOT_USE_TICKS = 3000f; // 60 s

    private final @NonNull GauntletAI ai;
    private final @NonNull Economy economy;
    private final Set<@NonNull Building> doomed = new LinkedHashSet<>();
    /** Each placed site of ours and when it was first seen (stalled sites). */
    private final Map<@NonNull Building, Float> site_seen = new LinkedHashMap<>();
    /** {x, y, game tick} of the buildings retired in the last RETIRED_MEMORY_TICKS. */
    private final List<float @NonNull []> retired = new ArrayList<>();
    /** Buildings passed over until the game tick given: no razers could be found for them (nounits). */
    private final Map<@NonNull Building, Float> passed = new LinkedHashMap<>();
    /** Game ticks a building no razers came for is passed over. */
    private static final float PASS_OVER_TICKS = 6000f; // 120 s

    /**
     * The retirement under way: its building and kind, whether it is being razed yet (else emptied), and since when.
     */
    private @Nullable Building target;
    private int kind;
    private boolean razing;
    private float started;
    private float raze_started;
    private float last_order;
    private final List<@NonNull Unit> razers = new ArrayList<>();
    /** Whether the razers are warriors the military lent (else peons). */
    private boolean lent;
    private int buildings_before;
    private float last_deploy = -500f;

    private float capped_since = -1f;
    private float next_allowed = -1f;
    private float last_search = -SEARCH_EVERY_TICKS;
    private float last_done = -50000f;
    private float last_none_log = -3000f;

    Retire(@NonNull GauntletAI ai, @NonNull Economy economy) {
        this.ai = ai;
        this.economy = economy;
    }

    /** Whether the building is being emptied or razed by a retirement. */
    boolean isDoomed(@Nullable Building b) {
        return b != null && !doomed.isEmpty() && doomed.contains(b);
    }

    /** Whether a building of ours retired in the last RETIRED_MEMORY_TICKS stood within RETIRED_CELLS of (x, y). */
    boolean retiredNear(int x, int y) {
        for (float[] r : retired)
            if (MapAnalysis.dist2(x, y, (int) r[0], (int) r[1]) <= RETIRED_CELLS * RETIRED_CELLS)
                return true;
        return false;
    }

    /** The flagged project's site was placed (counts retire_slot_used within SLOT_USE_TICKS of a retirement). */
    void flaggedPlaced() {
        if (ai.now() - last_done <= SLOT_USE_TICKS)
            ai.aiLog().count("retire_slot_used");
    }

    /** Every economy tick, after manageProjects. */
    void tick() {
        float now = ai.now();
        trackSites(now);
        retired.removeIf(r -> now - r[2] > RETIRED_MEMORY_TICKS);
        passed.entrySet().removeIf(e -> e.getKey().isDead() || now >= e.getValue());
        if (target != null) {
            follow(now);
            return;
        }
        Economy.Project flagged = economy.flaggedWaiting();
        boolean capped = !ai.owner().canBuild(Race.BUILDING_ARMORY);
        if (flagged == null || !capped) {
            capped_since = -1f;
            return;
        }
        if (capped_since < 0f)
            capped_since = now;
        if (now - capped_since < ai.strategy().retire_wait_ticks)
            return;
        // economy ticks (1 s) a flagged project has waited retire_wait_ticks at the cap
        ai.aiLog().count("retire_armed");
        if (now < next_allowed || !ai.periodDue(last_search, SEARCH_EVERY_TICKS))
            return;
        last_search = now;
        // Why candidates were passed over: {kind}{threat, other}.
        int[][] why = new int[KINDS.length][2];
        Building b = stalledSite(now, why[SITE]);
        int k = SITE;
        if (b == null) {
            b = quietTower(why[TOWER], COVER_CELLS);
            k = TOWER;
        }
        if (b == null) {
            b = drainedArmory(why[ARMORY]);
            k = ARMORY;
        }
        if (b == null) {
            b = farQuarters(why[QUARTERS]);
            k = QUARTERS;
        }
        if (b == null && ai.strategy().retire_any_tower) {
            b = quietTower(why[ANY_TOWER], 0);
            k = ANY_TOWER;
        }
        if (b == null) {
            ai.aiLog().count("retire_none"); // searches (every SEARCH_EVERY_TICKS) that found nothing to retire
            if (ai.logging() && ai.periodDue(last_none_log, 3000f)) { // every 60 s
                last_none_log = now;
                int pop = ai.owner().getUnitCountContainer().getNumSupplies();
                ai.aiLog().log("RETIRE", () -> String.format(
                        "nothing to retire for %s (%d s at the cap): passed over (threat/other) sites %d/%d, towers %d/%d, armories %d/%d, quarters %d/%d; units %d",
                        flagged.describe(), (int) GauntletAI.seconds(now - capped_since), why[SITE][0], why[SITE][1],
                        why[TOWER][0], why[TOWER][1], why[ARMORY][0], why[ARMORY][1], why[QUARTERS][0],
                        why[QUARTERS][1], pop));
            }
            return;
        }
        start(b, k, flagged, now);
    }

    /** Placed sites of ours and when each was first seen. */
    private void trackSites(float now) {
        Intel intel = ai.intel();
        site_seen.keySet().removeIf(b -> b.isDead() || b.isComplete());
        for (List<Building> group : List.of(intel.quarters_sites, intel.armory_sites, intel.tower_sites))
            for (Building b : group)
                site_seen.putIfAbsent(b, now);
    }

    /**
     * The oldest placed site (decoys aside) SITE_AGE_TICKS old with no builders and no threat within 20 cells, or null.
     */
    private @Nullable Building stalledSite(float now, int @NonNull [] why) {
        Building best = null;
        float oldest = Float.MAX_VALUE;
        for (Map.Entry<Building, Float> e : site_seen.entrySet()) {
            Building b = e.getKey();
            if (b.isDead() || now - e.getValue() < SITE_AGE_TICKS || passed.containsKey(b))
                continue;
            if (economy.buildersOn(b) > 0) {
                why[1]++;
                continue;
            }
            if (ai.military().threatNear(b.getGridX(), b.getGridY(), 20)) {
                why[0]++;
                continue;
            }
            if (e.getValue() >= oldest)
                continue;
            if (!razersAt(b, 1)) {
                why[1]++;
                continue;
            }
            oldest = e.getValue();
            best = b;
        }
        return best;
    }

    /**
     * The finished tower with no finished quarters or armory within cover cells (0: any) and no threat within 20
     * cells farthest from the main armory (else from our start), or null.
     */
    private @Nullable Building quietTower(int @NonNull [] why, int cover) {
        Intel intel = ai.intel();
        int[] from = anchor();
        Building best = null;
        int best_d = -1;
        for (Building t : intel.towers) {
            if (t.isDead() || passed.containsKey(t))
                continue;
            boolean covers = false;
            for (List<Building> group : List.of(intel.quarters, intel.armories))
                for (Building b : group)
                    covers |= cover > 0 && !b.isDead() && MapAnalysis.dist2(t.getGridX(), t.getGridY(),
                            b.getGridX(), b.getGridY()) <= cover * cover;
            if (covers) {
                why[1]++;
                continue;
            }
            if (ai.military().threatNear(t.getGridX(), t.getGridY(), 20)) {
                why[0]++;
                continue;
            }
            int d = MapAnalysis.dist2(t.getGridX(), t.getGridY(), from[0], from[1]);
            if (d <= best_d)
                continue;
            if (!razersAt(t, 4)) {
                why[1]++;
                continue;
            }
            best_d = d;
            best = t;
        }
        return best;
    }

    /**
     * The drained armory (Economy.drained) with no threat within 30 cells and 8 warriors the military can lend within
     * WARRIOR_CELLS, farthest from the main one, or null.
     */
    private @Nullable Building drainedArmory(int @NonNull [] why) {
        Intel intel = ai.intel();
        Building primary = intel.armory();
        if (primary == null)
            return null;
        Building best = null;
        int best_d = -1;
        for (Building a : intel.armories) {
            if (a == primary || a.isDead() || passed.containsKey(a))
                continue;
            if (!economy.isDrained(a)) {
                why[1]++;
                continue;
            }
            if (ai.military().threatNear(a.getGridX(), a.getGridY(), 30)) {
                why[0]++;
                continue;
            }
            int d = MapAnalysis.dist2(a.getGridX(), a.getGridY(), primary.getGridX(), primary.getGridY());
            if (d <= best_d)
                continue;
            // Lent warriors only: peons take ~100 s over 200 HP (0.1 HP/s each) and 20 of them are 20 gatherers.
            if (ai.military().lendable(a.getGridX(), a.getGridY(), WARRIOR_CELLS) < 8) {
                why[1]++;
                continue;
            }
            best_d = d;
            best = a;
        }
        return best;
    }

    /**
     * With units >= retire_pop (breeding stops at the 250-unit cap), the finished quarters more than
     * retire_quarters_dist cells from the main armory, not training the chieftain, with another finished quarters
     * within 25 cells (the base to fall back to, and safeArmorySite's anchor) and no threat within 20 cells, farthest
     * from the main armory; or null.
     */
    private @Nullable Building farQuarters(int @NonNull [] why) {
        Intel intel = ai.intel();
        Strategy st = ai.strategy();
        Building primary = intel.armory();
        if (primary == null || ai.owner().getUnitCountContainer().getNumSupplies() < st.retire_pop)
            return null;
        Building best = null;
        int best_d = st.retire_quarters_dist * st.retire_quarters_dist;
        for (Building q : intel.quarters) {
            if (q.isDead() || passed.containsKey(q))
                continue;
            int d = MapAnalysis.dist2(q.getGridX(), q.getGridY(), primary.getGridX(), primary.getGridY());
            if (d <= best_d)
                continue;
            boolean training = q.getChieftainContainer() != null && q.getChieftainContainer().isTraining();
            boolean neighbour = false;
            for (Building o : intel.quarters)
                neighbour |= o != q && !o.isDead() && MapAnalysis.dist2(q.getGridX(), q.getGridY(), o.getGridX(),
                        o.getGridY()) <= 25 * 25;
            if (training || !neighbour) {
                why[1]++;
                continue;
            }
            if (ai.military().threatNear(q.getGridX(), q.getGridY(), 20)) {
                why[0]++;
                continue;
            }
            if (!razersAt(q, 8)) {
                why[1]++;
                continue;
            }
            best_d = d;
            best = q;
        }
        return best;
    }

    /** The main armory's cell, else our start's. */
    private int @NonNull [] anchor() {
        Building primary = ai.intel().armory();
        return primary != null ? new int[]{primary.getGridX(), primary.getGridY()} : new int[]{ai.planner().getStartX(), ai.planner().getStartY()};
    }

    private void start(@NonNull Building b, int k, Economy.@NonNull Project flagged, float now) {
        target = b;
        kind = k;
        razing = false;
        started = now;
        lent = false;
        razers.clear();
        doomed.add(b);
        buildings_before = ai.owner().getBuildingCountContainer().getNumSupplies();
        int in = inside(b);
        ai.aiLog().count("retire_start_" + KINDS[k]);
        ai.aiLog().log("RETIRE",
                () -> String.format("retiring the %s at %d,%d (hp %d, %d inside) for %s, %d s at the cap",
                        KINDS[k], b.getGridX(), b.getGridY(), b.getHitPoints(), in, flagged.describe(),
                        (int) GauntletAI.seconds(now - capped_since)));
        empty(b);
    }

    /**
     * Whether a doomed armory has become the main armory or the last finished one standing, or a doomed quarters the
     * last finished quarters.
     */
    private boolean lastOfItsKind(@NonNull Building b) {
        Intel intel = ai.intel();
        if (kind == ARMORY) {
            if (b == intel.armory())
                return true;
            for (Building a : intel.armories)
                if (a != b && !a.isDead() && a.isComplete())
                    return false;
            return true;
        }
        if (kind == QUARTERS) {
            for (Building q : intel.quarters)
                if (q != b && !q.isDead() && q.isComplete())
                    return false;
            return true;
        }
        return false;
    }

    /** Units inside the building: a tower's gunner, a quarters' or armory's units (0 for a site). */
    private static int inside(@NonNull Building b) {
        return b.isComplete() && b.getUnitContainer() != null ? b.getUnitContainer().getNumSupplies() : 0;
    }

    /** Lets the building's units out: a tower's gunner (unless stunned), everyone in a quarters or armory as peons. */
    private void empty(@NonNull Building b) {
        int in = inside(b);
        if (in == 0)
            return;
        if (kind == TOWER || kind == ANY_TOWER) {
            Unit gunner = Intel.gunner(b);
            if (gunner != null && !Intel.isStunned(gunner))
                ai.owner().exitTower(b);
            return;
        }
        int pending = b.getDeployContainer(DeployType.PEON).getNumSupplies();
        if (in - pending > 0)
            ai.owner().deployUnits(b, DeployType.PEON, in - pending);
    }

    private void follow(float now) {
        Building b = target;
        assert b != null;
        if (b.isDead()) {
            done(b, now);
            return;
        }
        // The slot is no longer wanted: the flagged project was placed or dropped, or a slot came free another way (an
        // enemy razed a building of ours). Else a hop dropped meanwhile cost a building for nothing.
        if (economy.flaggedWaiting() == null || ai.owner().canBuild(Race.BUILDING_ARMORY)) {
            stop(b, "unneeded", now);
            return;
        }
        // Never our main or last armory, nor our last quarters: the enemy may raze the others meanwhile, and then this
        // one is where the economy sends every worker (the primary is chosen again when the old one falls).
        if (lastOfItsKind(b)) {
            stop(b, "last", now);
            return;
        }
        // Never a besieged building: it absorbs waves for free.
        if (ai.military().threatNear(b.getGridX(), b.getGridY(), kind == ARMORY ? 30 : 20)) {
            stop(b, "threat", now);
            return;
        }
        if (!razing) {
            if (inside(b) > 0) {
                empty(b);
                boolean tower = kind == TOWER || kind == ANY_TOWER;
                float wait = tower ? EMPTY_WAIT_TOWER_TICKS : EMPTY_WAIT_TICKS;
                if (now - started < wait)
                    return;
                if (!tower) {
                    stop(b, "giveup_empty", now);
                    return;
                }
                ai.aiLog().count("retire_gunner_lost"); // the gunner could not come out: it goes down with the tower
            }
            if (!beginRaze(b, now) && now - started > UNITS_WAIT_TICKS)
                stop(b, "nounits", now);
            return;
        }
        // Peons that walked in (newborns go to the nearest armory) come out again.
        if (kind == ARMORY || kind == QUARTERS)
            empty(b);
        razers.removeIf(Unit::isDead);
        // Builders still repairing it razes it with the rest.
        List<Unit> repairers = new ArrayList<>();
        for (Map.Entry<Unit, Building> e : ai.intel().builder_sites.entrySet())
            if (e.getValue() == b && !razers.contains(e.getKey()) && !e.getKey().isDead())
                repairers.add(e.getKey());
        if (!repairers.isEmpty()) {
            razers.addAll(repairers);
            attack(repairers, b);
            for (int i = 0; i < repairers.size(); i++)
                ai.aiLog().count("retire_repairers");
        }
        if (razers.isEmpty()) {
            razing = false;
            return;
        }
        if (ai.periodDue(last_order, REORDER_TICKS)) {
            last_order = now;
            List<Unit> idle = new ArrayList<>();
            for (Unit u : razers)
                if (!u.isMounted() && !Intel.isStunned(u)
                        && !(u.getCurrentController() instanceof HuntController hunt && hunt.getTarget() == b))
                    idle.add(u);
            if (!idle.isEmpty()) {
                attack(idle, b);
                for (int i = 0; i < idle.size(); i++)
                    ai.aiLog().count("retire_reorder");
            }
        }
        // Peons hit quarters and armories for 1 HP on a 20 % roll every 2 s: 20 of them take ~100 s.
        boolean tower = b.getTemplate().getTemplateID() == Race.BUILDING_TOWER;
        float limit = tower ? 3000f : lent ? 4500f : 9000f; // 60 s, 90 s, 180 s
        if (now - raze_started > limit)
            stop(b, "giveup_raze", now);
    }

    /**
     * Orders the razers: towers and tower sites (6 HP a peon swing, 3 HP/s) to 2-8 peons by their hit points; quarters
     * and armories, and their sites, to 2-12 idle iron or chicken warriors lent by the military (0.75 HP/s each) when
     * it has enough, else (not an armory) to 2-20 peons. Returns false when too few units are at hand (peons are then
     * let out of an armory for the next try).
     */
    private boolean beginRaze(@NonNull Building b, float now) {
        int hp = b.getHitPoints();
        boolean tower = b.getTemplate().getTemplateID() == Race.BUILDING_TOWER;
        List<Unit> units;
        if (tower) {
            int n = Math.clamp((int) Math.ceil(hp / 12.5f), b.isComplete() ? 4 : 2, 8);
            units = peons(b, n, Math.min(n, b.isComplete() ? 4 : 1));
        } else {
            int n = Math.clamp((int) Math.ceil(hp / 20f), 2, 12);
            units = ai.military().lend(b.getGridX(), b.getGridY(), n, Math.min(n, 8), WARRIOR_CELLS, 12000f); // 240 s
            lent = !units.isEmpty();
            // An armory only goes down to lent warriors (drainedArmory); a quarters, else to peons.
            if (!lent && kind != ARMORY) {
                int np = Math.clamp((int) Math.ceil(hp / 10f), 2, 20);
                units = peons(b, np, Math.min(np, 8));
            }
        }
        if (units.isEmpty())
            return false;
        razers.addAll(units);
        razing = true;
        raze_started = now;
        last_order = now;
        attack(units, b);
        ai.aiLog().count(lent ? "retire_raze_warriors" : "retire_raze_peons");
        if (ai.logging()) {
            int n = units.size();
            float walk = 0f;
            for (Unit u : units)
                walk += (float) Math.sqrt(MapAnalysis.dist2(u.getGridX(), u.getGridY(), b.getGridX(), b.getGridY()));
            float mean = walk / n;
            ai.aiLog().log("RETIRE", () -> String.format("razing the %s at %d,%d (hp %d) with %d %s, %.0f cells away",
                    KINDS[kind], b.getGridX(), b.getGridY(), hp, n, lent ? "lent warriors" : "peons", mean));
        }
        return true;
    }

    /**
     * Up to n peons for razers within UNIT_CELLS of the building, nearest first (ties in list order): idle, walking,
     * gathering or walking into a building, not a reserved armory placer. With fewer than min, none, and the complete
     * armory nearest the building with enough inside lets the rest out (at most every 10 s) for the next try.
     */
    private @NonNull List<@NonNull Unit> peons(@NonNull Building b, int n, int min) {
        Intel intel = ai.intel();
        int bx = b.getGridX();
        int by = b.getGridY();
        List<Unit> candidates = new ArrayList<>();
        List<Integer> dists = new ArrayList<>();
        for (Unit p : intel.peons) {
            if (!razerPeon(p))
                continue;
            int d = MapAnalysis.dist2(bx, by, p.getGridX(), p.getGridY());
            if (d > UNIT_CELLS * UNIT_CELLS)
                continue;
            int i = 0;
            while (i < dists.size() && dists.get(i) <= d)
                i++;
            candidates.add(i, p);
            dists.add(i, d);
        }
        if (candidates.size() >= min)
            return new ArrayList<>(candidates.subList(0, Math.min(n, candidates.size())));
        float now = ai.now();
        if (ai.periodDue(last_deploy, 500f)) { // 10 s
            Building from = null;
            int best = Integer.MAX_VALUE;
            for (Building a : intel.armories) {
                // weapon_reserve: the workers for the weapons held back stay in too
                if (a.isDead() || isDoomed(a)
                        || a.getUnitContainer().getNumSupplies() < n + Math.max(4, economy.weaponReserveHeld(a)))
                    continue;
                int d = MapAnalysis.dist2(bx, by, a.getGridX(), a.getGridY());
                if (d < best && d <= UNIT_CELLS * UNIT_CELLS) {
                    best = d;
                    from = a;
                }
            }
            if (from != null) {
                last_deploy = now;
                ai.owner().deployUnits(from, DeployType.PEON, n - candidates.size());
                ai.aiLog().count("retire_deploy");
            }
        }
        return List.of();
    }

    /**
     * The explicit attack order on our own building (the attack button and a click on it). Peons ordered count as
     * fighting at once, so the rest of this economy tick (allocatePeons) leaves them to it.
     */
    private void attack(@NonNull List<@NonNull Unit> units, @NonNull Building b) {
        ai.owner().setTarget(units.toArray(new Selectable<?>[0]), b, Action.ATTACK, false);
        Intel intel = ai.intel();
        for (Unit u : units)
            if (intel.peon_states.containsKey(u))
                intel.peon_states.put(u, PeonState.FIGHT);
    }

    /** Peon states a razer may be taken from. */
    private static final EnumSet<PeonState> RAZER_STATES = EnumSet.of(PeonState.IDLE, PeonState.MOVE,
            PeonState.GATHER_TREE, PeonState.GATHER_IRON, PeonState.GATHER_ROCK, PeonState.TRANSIT);

    /** A peon that may raze: idle, walking, gathering or walking into a building, not a reserved armory placer. */
    private boolean razerPeon(@NonNull Unit p) {
        PeonState s = ai.intel().peon_states.get(p);
        return !p.isDead() && s != null && RAZER_STATES.contains(s) && !economy.reservedPlacer(p);
    }

    /**
     * Whether razers can be had for the building: min peons (razerPeon) within UNIT_CELLS, or a complete armory there
     * that can let min out and keep 4 (or the workers weapon_reserve keeps in, if more). A candidate that fails is
     * passed over (s6189 smoke: the farthest towers had no peons within reach, 12 retirements called off for no units,
     * each after its gunner came out).
     */
    private boolean razersAt(@NonNull Building b, int min) {
        Intel intel = ai.intel();
        int n = 0;
        for (Unit p : intel.peons)
            if (razerPeon(p) && MapAnalysis.dist2(b.getGridX(), b.getGridY(), p.getGridX(),
                    p.getGridY()) <= UNIT_CELLS * UNIT_CELLS && ++n >= min)
                return true;
        for (Building a : intel.armories)
            if (!a.isDead() && !isDoomed(a)
                    && a.getUnitContainer().getNumSupplies() >= min + Math.max(4, economy.weaponReserveHeld(a))
                    && MapAnalysis.dist2(b.getGridX(), b.getGridY(), a.getGridX(),
                            a.getGridY()) <= UNIT_CELLS * UNIT_CELLS)
                return true;
        return false;
    }

    /** The building fell: the slot is free (Player.canBuild on this tick). */
    private void done(@NonNull Building b, float now) {
        ai.aiLog().count("retire_done_" + KINDS[kind]);
        retired.add(new float[]{b.getGridX(), b.getGridY(), now});
        last_done = now;
        next_allowed = started + GAP_TICKS;
        int after = ai.owner().getBuildingCountContainer().getNumSupplies();
        float order = razing ? GauntletAI.seconds(now - raze_started) : -1f;
        int n = razers.size();
        ai.aiLog().log("RETIRE", () -> String.format(
                "retired the %s at %d,%d: %.0f s after it was chosen, %.0f s razing, %d %s left, buildings %d -> %d",
                KINDS[kind], b.getGridX(), b.getGridY(), GauntletAI.seconds(now - started), order, n,
                lent ? "lent warriors" : "peons", buildings_before, after));
        release(false);
    }

    /** The retirement is called off (why: threat, giveup_empty, giveup_raze, nounits). */
    private void stop(@NonNull Building b, @NonNull String why, float now) {
        // No razers within reach: another building is taken next time (s6189 smoke: the same far tower 4 times).
        if (why.equals("nounits"))
            passed.put(b, now + PASS_OVER_TICKS);
        ai.aiLog().count("retire_stop_" + why);
        next_allowed = now + RETRY_TICKS;
        int hp = b.getHitPoints();
        ai.aiLog().log("RETIRE", () -> String.format("the %s at %d,%d stays (%s): hp %d, %.0f s after it was chosen",
                KINDS[kind], b.getGridX(), b.getGridY(), why, hp, GauntletAI.seconds(now - started)));
        release(true);
    }

    /**
     * Ends the retirement. Called off (stand), razers still attacking the building stop: warriors where they stand,
     * peons into the main armory. Lent warriors go back to the military.
     */
    private void release(boolean stand) {
        Building b = target;
        List<Unit> busy = new ArrayList<>();
        if (stand && b != null)
            for (Unit u : razers)
                if (!u.isDead() && !u.isMounted() && u.getCurrentController() instanceof HuntController hunt
                        && hunt.getTarget() == b)
                    busy.add(u);
        if (!busy.isEmpty()) {
            Building home = ai.intel().armory();
            if (lent || home == null)
                for (Unit u : busy)
                    ai.landscapeOrder(Selectable.newArray(u), u.getGridX(), u.getGridY(), Action.MOVE, false);
            else
                ai.owner().setTarget(busy.toArray(new Selectable<?>[0]), home, Action.DEFAULT, false);
        }
        if (lent)
            ai.military().release(razers);
        razers.clear();
        if (b != null)
            doomed.remove(b);
        target = null;
        razing = false;
        lent = false;
    }
}
