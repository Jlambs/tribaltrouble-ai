package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.landscape.TreeSupply;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.UnitSupplyContainer;
import com.oddlabs.tt.model.behaviour.Behaviour;
import com.oddlabs.tt.model.behaviour.PlaceBuildingController;
import com.oddlabs.tt.player.fable.Intel.PeonState;
import org.jspecify.annotations.NonNull;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Log only: where the peons' time goes in the opening, an "OPEN s" line a second for the first ten minutes. The line:
 * the units we have (pop), the peons inside each quarters (q, with the hold each is to keep after the slash) and each
 * armory (a), the warriors (w); then every peon outside a building once, by what it does this second: strike (the
 * freeze squad), shep (shepherds, lures, dodgers), place (carrying a site to its spot), the builders by their part of
 * the piece of wood (seek: walking to a tree, chop, carry: walking back with the piece, hammer, bstand: standing,
 * bblock: walking and blocked), enter (walking into a building), move, idle, the gatherers by kind (gt tree, gi iron,
 * gr rock, gc chicken) and other; then every site going up as
 * id:kind:hit points:builders:seek:chop:carry:hammer:other:mean cells of its seeking and chopping builders from
 * it:trees within 8 cells:trees within 16 cells, and every planned site not placed yet as id:kind:-1.
 */
final class OpeningTrace {
    private static final float UNTIL = 30000f; // 10 min
    private static final String[] ACTS = {"strike", "shep", "place", "seek", "chop", "carry", "hammer", "bstand", "bblock", "enter", "move", "idle", "gt", "gi", "gr", "gc", "other"};
    private static final int STRIKE = 0, SHEP = 1, PLACE = 2, SEEK = 3, CHOP = 4, CARRY = 5, HAMMER = 6, BSTAND = 7,
            BBLOCK = 8, ENTER = 9, MOVE = 10, IDLE = 11, GT = 12, GI = 13, GR = 14, GC = 15, OTHER = 16;

    private final @NonNull FableAI ai;
    private float last_sample = -1000f;

    OpeningTrace(@NonNull FableAI ai) {
        this.ai = ai;
    }

    void tick() {
        if (!ai.logging() || ai.now() > UNTIL || !ai.periodDue(last_sample, 50f)) // 1 s
            return;
        last_sample = ai.now();
        Intel intel = ai.intel();
        int[] acts = new int[ACTS.length];
        // per site: builders, seek, chop, carry, hammer, other, sum of cells of the seeking and chopping, their count
        Map<Building, int[]> sites = new LinkedHashMap<>();
        for (Unit u : intel.peons) {
            if (u.isDead())
                continue;
            PeonState s = intel.peon_states.get(u);
            Behaviour b = u.getCurrentBehaviour();
            String name = b == null ? "" : b.getClass().getSimpleName();
            int act;
            if (intel.strikers.contains(u))
                act = STRIKE;
            else if (s == PeonState.SHEPHERD)
                act = SHEP;
            else if (s == PeonState.BUILD) {
                Building site = intel.builder_sites.get(u);
                if (u.getPrimaryController() instanceof PlaceBuildingController)
                    act = PLACE;
                else {
                    UnitSupplyContainer c = u.getSupplyContainer();
                    boolean loaded = c != null && c.getSupplyType() == TreeSupply.class && c.getNumSupplies() > 0;
                    act = switch (name) {
                        case "HarvestBehaviour" -> CHOP;
                        case "RepairBehaviour" -> HAMMER;
                        case "WalkBehaviour" -> b.isBlocking() ? BBLOCK : loaded ? CARRY : SEEK;
                        default -> BSTAND;
                    };
                    if (site != null && !site.isDead()) {
                        int[] row = sites.computeIfAbsent(site, k -> new int[8]);
                        row[0]++;
                        row[act == SEEK ? 1 : act == CHOP ? 2 : act == CARRY ? 3 : act == HAMMER ? 4 : 5]++;
                        if (act == SEEK || act == CHOP) {
                            row[6] += (int) Math.sqrt(MapAnalysis.dist2(u.getGridX(), u.getGridY(), site.getGridX(),
                                    site.getGridY()));
                            row[7]++;
                        }
                    }
                }
            } else if (s == PeonState.TRANSIT)
                act = ENTER;
            else if (s == PeonState.MOVE)
                act = MOVE;
            else if (s == PeonState.IDLE)
                act = IDLE;
            else if (s == PeonState.GATHER_TREE)
                act = GT;
            else if (s == PeonState.GATHER_IRON)
                act = GI;
            else if (s == PeonState.GATHER_ROCK)
                act = GR;
            else if (s == PeonState.GATHER_CHICKEN)
                act = GC;
            else
                act = OTHER;
            acts[act]++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("s pop ").append(ai.owner().getUnitCountContainer().getNumSupplies()).append(" q");
        for (Building q : intel.quarters)
            if (!q.isDead() && q.isComplete())
                sb.append(' ').append(q.getUnitContainer().getNumSupplies()).append('/').append(
                        ai.economy().holdFor(q));
        sb.append(" a");
        for (Building a : intel.armories)
            if (!a.isDead() && a.isComplete())
                sb.append(' ').append(a.getUnitContainer().getNumSupplies());
        sb.append(" w ").append(intel.warriors.size()).append(" |");
        for (int i = 0; i < ACTS.length; i++)
            sb.append(' ').append(ACTS[i]).append(' ').append(acts[i]);
        sb.append(" |");
        for (Economy.Project p : ai.economy().traceProjects()) {
            Building site = p.building;
            if (site == null || site.isDead() || !site.isPlaced()) {
                sb.append(String.format(Locale.ROOT, " %d:%s:-1", p.id, kindOf(p)));
                continue;
            }
            if (site.isComplete())
                continue;
            int[] row = sites.getOrDefault(site, new int[8]);
            int trees8 = 0;
            int trees16 = 0;
            for (TreeSupply t : ai.map().getTrees()) {
                if (t.isEmpty())
                    continue;
                int d2 = MapAnalysis.dist2(t.getGridX(), t.getGridY(), site.getGridX(), site.getGridY());
                if (d2 <= 8 * 8)
                    trees8++;
                if (d2 <= 16 * 16)
                    trees16++;
            }
            sb.append(String.format(Locale.ROOT, " %d:%s:%d:%d:%d:%d:%d:%d:%d:%d:%d:%d", p.id, kindOf(p),
                    site.getHitPoints(), row[0], row[1], row[2], row[3], row[4], row[5],
                    row[7] == 0 ? -1 : row[6] / row[7], trees8, trees16));
        }
        String line = sb.toString();
        ai.aiLog().log("OPEN", () -> line);
    }

    private static @NonNull String kindOf(Economy.@NonNull Project p) {
        return p.describe().substring(0, p.describe().indexOf('#')).replace(' ', '_');
    }
}
