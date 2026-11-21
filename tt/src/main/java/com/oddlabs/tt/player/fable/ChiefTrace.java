package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.MagicBehaviour;
import com.oddlabs.tt.model.behaviour.WalkController;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Log only: what our chieftain does with his blast. Every half second the enemy warriors about him by what they do
 * (parked, hunting him, hunting another unit of ours, walking, other) and how many a blast would reach from where he
 * stands, from the best cell a 6 s walk away and from the best a 15 s walk away. "CHIEF s" a second; "CHIEF blast"
 * once a cast has gone off: who was in reach at the order and when the blast took its targets, who died, what it
 * cost him, and what he did with the charge before it; "CHIEF dmg" when he is hit; "CHIEF dead"; "CHIEF sum" a minute,
 * with the sums so far (the last one is the game's).
 */
final class ChiefTrace {
    /** Cells counted as a blast's reach (it reaches 18). */
    private static final int REACH = 17;
    /** Cells he walks in about 6 s and in about 15 s. */
    private static final int NEAR = 12;
    private static final int FAR = 30;
    private static final int STEP = 3;
    /** The blast takes its targets 2.15 s after the order and its ring has passed 4.6 s after it. */
    private static final float CAPTURE_TICKS = 110f;
    private static final float DONE_TICKS = 250f;
    private static final String[] KINDS = {"parked", "at_him", "at_other", "walking", "other"};
    /** all, parked, at_him, at_other */
    private static final int POPS = 4;
    private static final int[] LEVELS = {8, 15, 25, 40};
    private static final String[] STATES = {"charging", "weak", "awake", "noblob", "nopoint", "walk", "blast", "cast", "stunned", "refresh", "press", "none"};

    private final @NonNull FableAI ai;
    private float last_sample;
    private int half;
    private boolean alive;
    private int lives;
    private int casts;
    // the last sample
    private int last_hp;
    private int last_x;
    private int last_y;
    private @NonNull String last_state = "";
    private @NonNull String last_den = "";
    private boolean last_charged;
    private boolean last_casting;
    private int last_at_him9;
    private int last_at_him17;
    private int last_tower = -1;
    private int last_home;
    // the charge being spent
    private float charged_since = -1f;
    private final int[] cyc_states = new int[STATES.length];
    private int cyc_path;
    private int cyc_walks;
    private int cyc_offs;
    private final int[] cyc_max = new int[3 * POPS];
    // the cast being followed
    private float cast_at = -1f;
    private int cast_hp;
    private int cast_x;
    private int cast_y;
    private @NonNull String cast_before = "";
    private @NonNull String cast_cycle = "";
    private final int[] cast_order = new int[KINDS.length];
    private @Nullable List<Unit> captured;
    private final int[] cap_kinds = new int[KINDS.length];
    private int cap_rock;
    private int cap_rock_alive;
    private final List<Boolean> cap_is_rock = new ArrayList<>();
    private final List<Selectable<?>> cap_ours = new ArrayList<>();
    private int cast_dmg;
    // sums
    private int sum_alive;
    private int sum_charged;
    private final int[] sum_states = new int[STATES.length];
    private final int[] sum_charged_states = new int[STATES.length];
    /** [where: here, near, far][population][level] */
    private final int[] sum_levels = new int[3 * POPS * LEVELS.length];
    private final int[] sum_dmg = new int[STATES.length];
    /** charged with 8 and more in reach and no cast: what rules the blast out (ok: nothing, he waits for more) */
    private static final String[] WHY = {"ok", "ground", "seen", "tower", "friends", "few"};
    private final int[] sum_why = new int[WHY.length];
    /** our units within 19 cells in the half seconds our own units rule the blast out, by job */
    private final java.util.Map<String, Integer> sum_friends = new java.util.TreeMap<>();
    private float last_sum;

    ChiefTrace(@NonNull FableAI ai) {
        this.ai = ai;
    }

    private static int stateIndex(@NonNull String s) {
        for (int i = 0; i < STATES.length; i++)
            if (STATES[i].equals(s))
                return i;
        return STATES.length - 1;
    }

    private static int kind(@NonNull Unit e, @NonNull Unit chief) {
        if (Intel.isParked(e))
            return 0;
        if (e.getCurrentController() instanceof HuntController h)
            return h.getTarget() == chief ? 1 : 2;
        if (e.getCurrentController() instanceof WalkController)
            return 3;
        return 4;
    }

    void tick() {
        if (!ai.logging() || !ai.periodDue(last_sample, 25f)) // 0.5 s
            return;
        last_sample = ai.now();
        half++;
        Intel intel = ai.intel();
        Unit chief = intel.chieftain;
        if (chief == null || chief.isDead()) {
            if (alive) {
                alive = false;
                if (cast_at >= 0f)
                    blastLine(null);
                line(String.format(Locale.ROOT,
                        "dead #%d at %d,%d home %d state %s/%s charged %b casting %b hp_last %d at_him9 %d at_him17 %d tower %d %s",
                        lives, last_x, last_y, last_home, last_state, last_den, last_charged, last_casting, last_hp,
                        last_at_him9, last_at_him17, last_tower, last_charged ? cycle() : "cyc none"));
                charged_since = -1f;
            }
            sum();
            return;
        }
        int x = chief.getGridX();
        int y = chief.getGridY();
        int hp = chief.getHitPoints();
        if (!alive) {
            alive = true;
            lives++;
            last_hp = hp;
            last_x = x;
            last_y = y;
            last_state = "";
            charged_since = -1f;
            line("born #" + lives + " at " + x + "," + y);
        }
        Chieftain mission = ai.chieftain();
        boolean casting = chief.getCurrentBehaviour() instanceof MagicBehaviour;
        boolean charged = chief.canDoMagic(RacesResources.INDEX_MAGIC_BLAST);
        String state = casting ? "cast" : mission.trace_mission;
        int si = stateIndex(state);
        String den = mission.trace_side ? "side" : mission.trace_den;
        Building armory = intel.armory();
        int home = armory == null ? -1 : (int) Math.sqrt(MapAnalysis.dist2(x, y, armory.getGridX(), armory.getGridY()));
        // the enemy warriors about him
        int[] in9 = new int[KINDS.length];
        int[] in17 = new int[KINDS.length];
        int[] in30 = new int[KINDS.length];
        List<int[]> about = new ArrayList<>();
        int reach2 = (FAR + REACH) * (FAR + REACH);
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead())
                continue;
            int d2 = MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY());
            if (d2 > reach2)
                continue;
            int k = kind(e, chief);
            about.add(new int[]{e.getGridX(), e.getGridY(), k});
            if (d2 <= 30 * 30)
                in30[k]++;
            if (d2 <= REACH * REACH)
                in17[k]++;
            if (d2 <= 9 * 9)
                in9[k]++;
        }
        int tower = -1;
        for (Building t : intel.enemy_towers)
            if (Intel.isTowerManned(t)) {
                int d = (int) Math.sqrt(MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY()));
                if (tower < 0 || d < tower)
                    tower = d;
            }
        // what a blast would reach: here, from the best cell within NEAR, within FAR; by population
        int[] best = new int[3 * POPS];
        int[] best_at = new int[4];
        int size = ai.map().getSize();
        for (int dy = -FAR; dy <= FAR; dy += STEP)
            for (int dx = -FAR; dx <= FAR; dx += STEP) {
                int r2 = dx * dx + dy * dy;
                if (r2 > FAR * FAR)
                    continue;
                int px = x + dx;
                int py = y + dy;
                if (px < 3 || py < 3 || px > size - 4 || py > size - 4 || (r2 > 0 && !ai.map().passable(px, py)))
                    continue;
                int[] n = new int[POPS];
                for (int[] e : about)
                    if (MapAnalysis.dist2(px, py, e[0], e[1]) <= REACH * REACH) {
                        n[0]++;
                        if (e[2] == 0)
                            n[1]++;
                        else if (e[2] == 1)
                            n[2]++;
                        else if (e[2] == 2)
                            n[3]++;
                    }
                for (int p = 0; p < POPS; p++) {
                    if (r2 == 0)
                        best[p] = n[p];
                    if (r2 <= NEAR * NEAR && n[p] > best[POPS + p]) {
                        best[POPS + p] = n[p];
                        if (p == 0) {
                            best_at[0] = dx;
                            best_at[1] = dy;
                        }
                    }
                    if (n[p] > best[2 * POPS + p]) {
                        best[2 * POPS + p] = n[p];
                        if (p == 0) {
                            best_at[2] = dx;
                            best_at[3] = dy;
                        }
                    }
                }
            }
        // sums
        sum_alive++;
        sum_states[si]++;
        if (hp < last_hp && !last_state.isEmpty()) {
            sum_dmg[si] += last_hp - hp;
            if (cast_at >= 0f)
                cast_dmg += last_hp - hp;
            line(String.format(Locale.ROOT,
                    "dmg hp %d>%d state %s/%s charged %b at_him9 %d at_him17 %d tower %d home %d",
                    last_hp, hp, state, den, charged, in9[1], in17[1], tower, home));
        }
        if (charged && !casting) {
            if (charged_since < 0f) {
                charged_since = ai.now();
                java.util.Arrays.fill(cyc_states, 0);
                java.util.Arrays.fill(cyc_max, 0);
                cyc_path = 0;
                cyc_walks = 0;
                cyc_offs = 0;
            }
            sum_charged++;
            sum_charged_states[si]++;
            cyc_states[si]++;
            cyc_path += (int) Math.round(Math.sqrt(MapAnalysis.dist2(x, y, last_x, last_y)));
            if (state.equals("walk") && !last_state.equals("walk"))
                cyc_walks++;
            if (last_state.equals("walk") && !state.equals("walk") && !state.equals("blast"))
                cyc_offs++;
            if (best[0] >= LEVELS[0]) {
                String why = mission.traceHere(chief);
                for (int i = 0; i < WHY.length; i++)
                    if (WHY[i].equals(why))
                        sum_why[i]++;
                if (why.equals("friends"))
                    for (Selectable<?> s : ai.owner().getUnits().getSet())
                        if (s instanceof Unit u && !u.isDead() && u != chief && MapAnalysis.dist2(x, y, u.getGridX(),
                                u.getGridY()) <= 19 * 19) {
                                    String role = ai.shepherd().roleOf(u);
                                    sum_friends.merge(role.startsWith("shepherd") ? "shepherd" : role, 1, Integer::sum);
                                }
            }
            for (int i = 0; i < best.length; i++) {
                cyc_max[i] = Math.max(cyc_max[i], best[i]);
                for (int l = 0; l < LEVELS.length; l++)
                    if (best[i] >= LEVELS[l])
                        sum_levels[i * LEVELS.length + l]++;
            }
        }
        // a cast: the order, the moment the blast takes its targets, and what is left when its ring has passed
        if (casting && !last_casting) {
            if (cast_at >= 0f)
                blastLine(chief);
            casts++;
            cast_at = ai.now();
            cast_hp = last_hp;
            cast_x = x;
            cast_y = y;
            cast_dmg = last_hp - hp;
            cast_before = last_state + "/" + den + " " + mission.trace_blast;
            cast_cycle = cycle();
            System.arraycopy(in17, 0, cast_order, 0, KINDS.length);
            captured = null;
            charged_since = -1f;
        }
        if (cast_at >= 0f && captured == null && ai.now() - cast_at >= CAPTURE_TICKS) {
            captured = new ArrayList<>();
            cap_is_rock.clear();
            cap_ours.clear();
            java.util.Arrays.fill(cap_kinds, 0);
            cap_rock = 0;
            for (Unit e : intel.enemy_warriors)
                if (!e.isDead() && MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 18 * 18) {
                    captured.add(e);
                    cap_kinds[kind(e, chief)]++;
                    boolean rock = Intel.warriorType(e) != Intel.WarriorType.IRON;
                    cap_is_rock.add(rock);
                    if (rock)
                        cap_rock++;
                }
            for (Selectable<?> s : ai.owner().getUnits().getSet())
                if (!s.isDead() && s != chief && MapAnalysis.dist2(x, y, s.getGridX(), s.getGridY()) <= 18 * 18)
                    cap_ours.add(s);
        }
        if (cast_at >= 0f && ai.now() - cast_at >= DONE_TICKS)
            blastLine(chief);
        if (half % 2 == 0)
            line(String.format(Locale.ROOT,
                    "s hp %d ch %.2f st %s/%s at %d,%d home %d tower %d in9 %s in17 %s in30 %s here %s near %s (%d,%d) far %s (%d,%d)%s",
                    hp, chief.getMagicProgress(RacesResources.INDEX_MAGIC_BLAST), state, den, x, y, home,
                    tower, join(in9, 0, KINDS.length), join(in17, 0, KINDS.length), join(in30, 0, KINDS.length),
                    join(best, 0, POPS), join(best, POPS, POPS), best_at[0], best_at[1], join(best, 2 * POPS, POPS),
                    best_at[2], best_at[3],
                    mission.trace_point == null ? "" : String.format(Locale.ROOT, " point %d,%d (%d) %d cells",
                            mission.trace_point[0], mission.trace_point[1], mission.trace_point[2], (int) Math.sqrt(
                                    MapAnalysis.dist2(x, y, mission.trace_point[0], mission.trace_point[1])))));
        last_hp = hp;
        last_x = x;
        last_y = y;
        last_state = state;
        last_den = den;
        last_charged = charged;
        last_casting = casting;
        last_at_him9 = in9[1];
        last_at_him17 = in17[1];
        last_tower = tower;
        last_home = home;
        sum();
    }

    /** What he did with the charge he is spending: its seconds, the half seconds by state, and the most in reach. */
    private @NonNull String cycle() {
        if (charged_since < 0f)
            return "cyc none";
        return String.format(Locale.ROOT,
                "cyc %.1f states %s path %d walks %d offs %d max_here %s max_near %s max_far %s",
                (ai.now() - charged_since) / 50f, join(cyc_states, 0, STATES.length), cyc_path, cyc_walks, cyc_offs,
                join(cyc_max, 0, POPS), join(cyc_max, POPS, POPS), join(cyc_max, 2 * POPS, POPS));
    }

    private void blastLine(@Nullable Unit chief) {
        int killed = 0;
        int killed_rock = 0;
        int ours_dead = 0;
        List<Unit> cap = captured;
        if (cap != null) {
            for (int i = 0; i < cap.size(); i++)
                if (cap.get(i).isDead()) {
                    killed++;
                    if (cap_is_rock.get(i))
                        killed_rock++;
                }
            for (Selectable<?> s : cap_ours)
                if (s.isDead())
                    ours_dead++;
        }
        line(String.format(Locale.ROOT,
                "blast #%d at %d,%d before %s order %s cap %d %s rock %d killed %d rock %d ours %d ours_dead %d hp %d>%d dmg %d after %.1f %s",
                casts, cast_x, cast_y, cast_before, join(cast_order, 0, KINDS.length), cap == null ? -1 : cap.size(),
                join(cap_kinds, 0, KINDS.length), cap_rock, killed, killed_rock, cap_ours.size(), ours_dead, cast_hp,
                chief == null ? 0 : chief.getHitPoints(), cast_dmg, (ai.now() - cast_at) / 50f, cast_cycle));
        cast_at = -1f;
        captured = null;
        cap_ours.clear();
    }

    private void sum() {
        if (lives == 0 || !ai.periodDue(last_sum, 3000f)) // 60 s
            return;
        last_sum = ai.now();
        line(String.format(Locale.ROOT,
                "sum lives %d casts %d alive %d charged %d states %s charged_states %s dmg %s levels %s why %s friends %s",
                lives, casts, sum_alive, sum_charged, join(sum_states, 0, STATES.length), join(sum_charged_states, 0,
                        STATES.length), join(sum_dmg, 0, STATES.length), join(sum_levels, 0, sum_levels.length), join(
                                sum_why, 0, WHY.length), sum_friends.toString().replace(" ", "")));
    }

    private static @NonNull String join(int @NonNull [] a, int from, int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++)
            sb.append(i == 0 ? "" : " ").append(a[from + i]);
        return sb.append(']').toString();
    }

    private void line(@NonNull String s) {
        ai.aiLog().log("CHIEF", () -> s);
    }
}
