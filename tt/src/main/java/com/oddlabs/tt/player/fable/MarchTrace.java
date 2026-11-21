package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.Behaviour;
import com.oddlabs.tt.model.behaviour.HuntController;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Log only: what the attacking army does with its time, a "MARCH s" line a second while an attack is out and a
 * "MARCH target" line whenever its target changes. The second's line: the branch of Military.attack that gave this
 * round's orders (phase: march, column, engage, hold, charge, siege, pillage, regroup, wedge, wait, none), the army's
 * size and centre, the target and the centre's straight line to it, the walking distance to it of the unit a third of
 * the way back from the front (pivot) and of the front and the last unit, the units more than 16 cells from the
 * centre; then what the units do (throw, close: walking up to a hunt's target, walk, blocked, idle, stun, other), what
 * their hunts are after (warrior, peon, tower, building), and what stands about: the enemy warriors, towers,
 * buildings and peons within 22 cells of the centre, the enemy warriors within 14 cells of any unit and the units
 * with one within 14 cells; and of the units walking (not blocked, not closing on a hunt's target) the mean cells
 * they moved since the last sample (step, -1 for none) and the mean metres of walking distance to the target they
 * gained (gain, -99 for none or a new target).
 */
final class MarchTrace {
    private static final String[] ACTS = {"throw", "close", "walk", "blocked", "idle", "stun", "other"};

    private final @NonNull FableAI ai;
    private float last_sample;
    private @Nullable Selectable<?> last_target;
    private boolean was_out;
    /** Where each unit stood at the last sample and its walking distance to the target then, on which field. */
    private final java.util.Map<Unit, int[]> last_pos = new java.util.LinkedHashMap<>();
    private @Nullable DistanceField last_field;

    MarchTrace(@NonNull FableAI ai) {
        this.ai = ai;
    }

    private static @NonNull String kind(@NonNull Selectable<?> t) {
        if (t instanceof Building b)
            return Intel.kind(b);
        return t.getAbilities().hasAbilities(Abilities.MAGIC) ? "chieftain" : t.getAbilities().hasAbilities(
                Abilities.THROW) ? "warrior" : "peon";
    }

    void tick() {
        if (!ai.logging() || !ai.periodDue(last_sample, 50f)) // 1 s
            return;
        last_sample = ai.now();
        Military military = ai.military();
        List<Unit> army = military.traceAttackers();
        boolean out = "ATTACK".equals(military.modeName()) && !army.isEmpty();
        if (!out) {
            if (was_out)
                ai.aiLog().log("MARCH", () -> "over mode " + military.modeName());
            was_out = false;
            last_target = null;
            return;
        }
        Intel intel = ai.intel();
        int[] c = MapAnalysis.centroid(army);
        Selectable<?> target = military.traceTarget();
        DistanceField field = military.traceTargetField();
        int front = Integer.MAX_VALUE;
        int tail = -1;
        int far = 0;
        int[] acts = new int[ACTS.length];
        int[] hunts = new int[4];
        int near_units = 0;
        boolean[] seen = new boolean[intel.enemy_warriors.size()];
        double step = 0;
        int steppers = 0;
        long gain = 0;
        int gainers = 0;
        boolean same_field = field == last_field;
        java.util.Map<Unit, int[]> now_pos = new java.util.LinkedHashMap<>();
        for (Unit u : army) {
            int own = field != null ? field.getAround(u.getGridX(), u.getGridY(), 1) : DistanceField.UNREACHABLE;
            now_pos.put(u, new int[]{u.getGridX(), u.getGridY(), own});
            int[] before = last_pos.get(u);
            Behaviour wb = u.getCurrentBehaviour();
            if (before != null && wb != null && "WalkBehaviour".equals(wb.getClass().getSimpleName())
                    && !wb.isBlocking()
                    && !(u.getCurrentController() instanceof HuntController)) {
                step += Math.sqrt(MapAnalysis.dist2(u.getGridX(), u.getGridY(), before[0], before[1]));
                steppers++;
                if (same_field && own != DistanceField.UNREACHABLE && before[2] != DistanceField.UNREACHABLE) {
                    gain += before[2] - own;
                    gainers++;
                }
            }
            if (field != null) {
                int d = own;
                if (d != DistanceField.UNREACHABLE) {
                    front = Math.min(front, d);
                    tail = Math.max(tail, d);
                }
            }
            if (MapAnalysis.dist2(u.getGridX(), u.getGridY(), c[0], c[1]) > 16 * 16)
                far++;
            Behaviour b = u.getCurrentBehaviour();
            String name = b == null ? "" : b.getClass().getSimpleName();
            boolean hunting = u.getCurrentController() instanceof HuntController;
            acts[switch (name) {
                case "AttackBehaviour" -> 0;
                case "WalkBehaviour" -> b.isBlocking() ? 3 : hunting ? 1 : 2;
                case "IdleBehaviour" -> 4;
                case "StunBehaviour" -> 5;
                default -> 6;
            }]++;
            if (u.getCurrentController() instanceof HuntController h) {
                Selectable<?> prey = h.getTarget();
                hunts[prey instanceof Building b2 ? "tower".equals(Intel.kind(
                        b2)) ? 2 : 3 : prey.getAbilities().hasAbilities(Abilities.THROW) ? 0 : 1]++;
            }
            boolean any = false;
            for (int i = 0; i < seen.length; i++) {
                Unit e = intel.enemy_warriors.get(i);
                if (!e.isDead() && MapAnalysis.dist2(u.getGridX(), u.getGridY(), e.getGridX(),
                        e.getGridY()) <= 14 * 14) {
                    seen[i] = true;
                    any = true;
                }
            }
            if (any)
                near_units++;
        }
        int about = 0;
        for (boolean s : seen)
            if (s)
                about++;
        int pivot = field != null ? military.tracePivotDist() : DistanceField.UNREACHABLE;
        int line = target == null ? -1 : (int) MapAnalysis.meters(c[0], c[1], target.getGridX(), target.getGridY());
        if (target != last_target || !was_out) {
            Selectable<?> old = last_target;
            String why = !was_out ? "launch" : old == null ? "none" : old.isDead() ? "fell" : "switched";
            int walk = pivot;
            ai.aiLog().log("MARCH", () -> target == null ? "target none why " + why : String.format(Locale.ROOT,
                    "target %s of %s at %d,%d why %s walk %d line %d army %d at %d,%d", kind(target),
                    target.getOwner().getPlayerInfo().getName(), target.getGridX(), target.getGridY(), why,
                    walk == DistanceField.UNREACHABLE ? -1 : walk, line, army.size(), c[0], c[1]));
            last_target = target;
        }
        was_out = true;
        int f = front;
        int t = tail;
        int fr = far;
        int nu = near_units;
        int ab = about;
        last_pos.clear();
        last_pos.putAll(now_pos);
        last_field = field;
        double mean_step = steppers == 0 ? -1 : step / steppers;
        double mean_gain = gainers == 0 ? -99 : gain / (double) gainers;
        ai.aiLog().log("MARCH", () -> String.format(Locale.ROOT,
                "s phase %s n %d c %d,%d tgt %s line %d pivot %d front %d tail %d far %d | throw %d close %d walk %d " + "blocked %d idle %d stun %d other %d | hunt w %d p %d t %d b %d | near ew %d et %d eb %d " + "ep %d about %d units %d step %.2f gain %.2f",
                military.tracePhase(), army.size(), c[0], c[1], target == null ? "none" : kind(target), line,
                pivot == DistanceField.UNREACHABLE ? -1 : pivot, f == Integer.MAX_VALUE ? -1 : f, t, fr, acts[0],
                acts[1], acts[2], acts[3], acts[4], acts[5], acts[6], hunts[0], hunts[1], hunts[2], hunts[3],
                Combat.countNear(intel.enemy_warriors, c[0], c[1], 22), Combat.countNear(intel.enemy_towers, c[0],
                        c[1], 22),
                Combat.countNear(intel.enemy_buildings, c[0], c[1], 22), Combat.countNear(intel.enemy_peons, c[0],
                        c[1], 22),
                ab, nu, mean_step, mean_gain));
    }
}
