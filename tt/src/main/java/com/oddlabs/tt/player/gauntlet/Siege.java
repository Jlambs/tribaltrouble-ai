package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.DeployContainer;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.RepairBehaviour;
import com.oddlabs.tt.model.behaviour.RepairController;
import org.jspecify.annotations.NonNull;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How our finished buildings hold up under attack, for repair_swarm and salvage, and the outcome counters of every arm:
 * each economy tick (first in Economy.tick) it samples every quarters', armory's and tower's hit points into a short
 * ring, notes the units inside it and in its deploy queues (both vanish with it, LandBuilding.removeDying), which
 * of our peons repair it right now and which enemy warriors hunt or attack it. It gives no orders, draws no random
 * numbers and none of its state feeds a decision unless an option asks it, so it always runs: razed_*, inside_lost_*
 * and hp_repaired count the base too.
 */
final class Siege {
    /** Samples kept per building: 8 economy ticks, 7 s, so the 6-s window of measuredLoss always has one. */
    private static final int RING = 8;
    /** measuredLoss: the oldest sample used is at most this old, and spans at least the least window. */
    private static final float LOSS_WINDOW_TICKS = 300f; // 6 s
    private static final float LOSS_MIN_SPAN_TICKS = 100f; // 2 s
    /** An enemy peon this close to a tower takes it down at 3 HP/s (InstantHitFactory: 6 damage every 2 s). */
    private static final int TOWER_PEON_CELLS = 5;
    private static final float TOWER_PEON_RATE = 3f;
    /** damageModel: warriors this far from a building and beyond its reach count as approaching it. */
    private static final int APPROACH_CELLS = 20;

    /** A building's samples: game tick and hit points, newest at head - 1; its units at stake; doomed by Retire. */
    private static final class History {
        final float @NonNull [] t = new float[RING];
        final int @NonNull [] hp = new int[RING];
        int head;
        int n;
        int inside;
        boolean doomed;

        int newest() {
            return hp[(head - 1 + RING) % RING];
        }
    }

    private final @NonNull GauntletAI ai;
    private final @NonNull Economy economy;
    private final Map<@NonNull Building, @NonNull History> hist = new LinkedHashMap<>();
    /** Units at stake in buildings that fell since the last update, for salvage's end (lastInside). */
    private final Map<@NonNull Building, Integer> fallen = new LinkedHashMap<>();
    /** Our peons repairing each building right now (RepairBehaviour under its RepairController). */
    private final Map<@NonNull Building, Integer> repairing = new LinkedHashMap<>();
    /** Enemy warriors whose hunt or attack targets one of our buildings, and that building. */
    private final Map<@NonNull Unit, @NonNull Building> targets = new LinkedHashMap<>();

    Siege(@NonNull GauntletAI ai, @NonNull Economy economy) {
        this.ai = ai;
        this.economy = economy;
    }

    /** Every economy tick, before anything gives an order. */
    void update() {
        Intel intel = ai.intel();
        float now = ai.now();
        fallen.clear();
        for (Iterator<Map.Entry<Building, History>> it = hist.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Building, History> e = it.next();
            Building b = e.getKey();
            if (!b.isDead())
                continue;
            History h = e.getValue();
            it.remove();
            fallen.put(b, h.inside);
            // retire razes its own buildings on purpose
            if (h.doomed)
                continue;
            String kind = Intel.kind(b);
            ai.aiLog().count("razed_" + kind);
            if (b.getTemplate().getTemplateID() != Race.BUILDING_TOWER)
                for (int i = 0; i < h.inside; i++)
                    ai.aiLog().count("inside_lost_" + kind);
        }
        for (List<Building> group : List.of(intel.armories, intel.quarters, intel.towers))
            for (Building b : group) {
                if (b.isDead() || !b.isComplete())
                    continue;
                History h = hist.computeIfAbsent(b, k -> new History());
                int hp = b.getHitPoints();
                // a finished building gains hit points only by repair
                if (h.n > 0 && hp > h.newest())
                    for (int i = h.newest(); i < hp; i++)
                        ai.aiLog().count("hp_repaired");
                h.t[h.head] = now;
                h.hp[h.head] = hp;
                h.head = (h.head + 1) % RING;
                h.n = Math.min(RING, h.n + 1);
                h.inside = atStake(b);
                h.doomed = economy.isDoomed(b);
            }
        repairing.clear();
        for (Unit p : intel.peons) {
            if (p.isDead() || p.getHitPoints() <= 0)
                continue;
            if (p.getPrimaryController() instanceof RepairController rc
                    && p.getCurrentBehaviour() instanceof RepairBehaviour)
                repairing.merge(rc.getBuilding(), 1, Integer::sum);
        }
        targets.clear();
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead())
                continue;
            Controller c = e.getCurrentController();
            Selectable<?> t = c instanceof HuntController h ? h.getTarget() : c instanceof AttackController a ? a.getTarget() : null;
            // a land building is its own entrance (Building.getEntrance)
            if (t instanceof Building b && b.getOwner() == ai.owner() && !b.isDead())
                targets.put(e, b);
        }
    }

    /**
     * The units a razing of the building would take with it: those inside and those in its deploy queues (a deploy
     * order takes its workers at once, and queued units are removed with the building). Check isDead first.
     */
    static int atStake(@NonNull Building b) {
        int n = b.getUnitContainer() != null ? b.getUnitContainer().getNumSupplies() : 0;
        if (b.getTemplate().getTemplateID() == Race.BUILDING_TOWER)
            return n;
        for (DeployType type : DeployType.values()) {
            DeployContainer q = b.getDeployContainer(type);
            if (q != null)
                n += q.getNumSupplies();
        }
        return n;
    }

    /** Units at stake in the building at its last sample (also for one that fell since), or 0. */
    int lastInside(@NonNull Building b) {
        History h = hist.get(b);
        if (h != null)
            return h.inside;
        return fallen.getOrDefault(b, 0);
    }

    /** Our peons repairing the building right now. */
    int repairing(@NonNull Building b) {
        return repairing.getOrDefault(b, 0);
    }

    /** The building of ours the enemy warrior hunts or attacks, or null. */
    Building target(@NonNull Unit e) {
        return targets.get(e);
    }

    static boolean isTower(@NonNull Building b) {
        return b.getTemplate().getTemplateID() == Race.BUILDING_TOWER;
    }

    /**
     * Expected HP per second of one attacker on the building, without terrain (design-0 §4: hit chance x damage over
     * the 2-s throw): rock .25, iron .75, chicken 1.9 on a quarters or armory (a bounce re-hits the 7x7 building), .95
     * on a tower (bounces go to units next to it).
     */
    static float rate(@NonNull Unit e, boolean tower) {
        return switch (Intel.warriorType(e)) {
            case ROCK -> .25f;
            case IRON -> .75f;
            case CHICKEN -> tower ? .95f : 1.9f;
        };
    }

    /** Cells from the centre an attacker hits the building from: weapon range 6 plus its size (Unit.getRange). */
    static int reach(@NonNull Building b) {
        return isTower(b) ? 9 : 12;
    }

    /**
     * Modelled HP per second the building loses with no repairs: every enemy warrior in reach at its rate (half of it
     * when it hunts something else), each one beyond reach within APPROACH_CELLS at approach_w of it, and on a tower 3
     * per enemy peon within 5 cells.
     */
    float damageModel(@NonNull Building b, float approach_w) {
        int x = b.getGridX();
        int y = b.getGridY();
        if (!ai.military().threatNearEcon(x, y, APPROACH_CELLS))
            return 0f;
        boolean tower = isTower(b);
        int reach = reach(b);
        int r2 = reach * reach;
        float d = 0f;
        for (Unit e : ai.intel().enemy_warriors) {
            if (e.isDead())
                continue;
            int d2 = MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY());
            if (d2 > APPROACH_CELLS * APPROACH_CELLS)
                continue;
            float r = rate(e, tower);
            if (d2 <= r2)
                d += targets.get(e) == b ? r : .5f * r;
            else
                d += approach_w * r;
        }
        if (tower)
            for (Unit p : ai.intel().enemy_peons)
                if (!p.isDead() && MapAnalysis.dist2(x, y, p.getGridX(),
                        p.getGridY()) <= TOWER_PEON_CELLS * TOWER_PEON_CELLS)
                    d += TOWER_PEON_RATE;
        return d;
    }

    /**
     * HP per second the building lost over its samples, net of our repairs: from the oldest sample at most
     * LOSS_WINDOW_TICKS old, if it spans at least LOSS_MIN_SPAN_TICKS, else 0.
     */
    float measuredLoss(@NonNull Building b) {
        History h = hist.get(b);
        if (h == null || h.n < 2)
            return 0f;
        float now = ai.now();
        int newest = (h.head - 1 + RING) % RING;
        for (int j = h.n - 1; j >= 1; j--) {
            int i = (h.head - 1 - j + 2 * RING) % RING;
            if (now - h.t[i] > LOSS_WINDOW_TICKS)
                continue;
            float span = h.t[newest] - h.t[i];
            if (span < LOSS_MIN_SPAN_TICKS)
                return 0f;
            return Math.max(0, h.hp[i] - h.hp[newest]) * (float) GauntletAI.TICKS_PER_SECOND / span;
        }
        return 0f;
    }

    /**
     * Game seconds until the building falls: its hit points over the larger of the measured loss and the modelled
     * damage less our repairers now (1 HP/s each), or +infinity when neither is positive.
     */
    float timeToFall(@NonNull Building b, float approach_w) {
        float d = Math.max(measuredLoss(b), damageModel(b, approach_w) - repairing(b));
        return d <= 0f ? Float.POSITIVE_INFINITY : b.getHitPoints() / d;
    }
}
