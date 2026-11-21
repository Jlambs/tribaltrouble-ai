package com.oddlabs.tt.player.fable;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackBehaviour;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.Behaviour;
import com.oddlabs.tt.model.behaviour.Controller;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.IdleBehaviour;
import com.oddlabs.tt.model.behaviour.WalkBehaviour;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Log only: what our units do within a manned enemy tower's reach. A contact begins with the first warrior of ours
 * within REACH cells of the tower and ends when the tower falls, loses its garrison, or has had none of ours in reach
 * for ten seconds. Every half second each warrior in reach counts under what it does (throwing at this tower, walking
 * up to it, after an enemy warrior, after an enemy peon, at another building, walking, blocked, idle, other), and the
 * garrison under what it throws at. One "TOWER s" line a second while the contact lasts, one "TOWER end" line with the
 * sums when it ends.
 */
final class TowerTrace {
    /** A garrison throws 6 + 8 + 1.9 cells. */
    private static final int REACH = 16;
    /** A warrior throws at a tower from about 6 cells plus the tower's size. */
    private static final int NEAR = 9;
    private static final String[] STATES = {"throw_t", "close_t", "unit_w", "unit_p", "bld", "walk", "blocked", "idle", "other"};
    private static final String[] ROLES = {"attack", "reinforce", "other"};
    private static final float QUIET_TICKS = 500f; // 10 s

    private static final class Watch {
        final int id;
        final float first;
        final int hp0;
        float first_dmg = -1f;
        float last_seen;
        int hp;
        int samples;
        final int[] w = new int[STATES.length];
        final int[] w_near = new int[STATES.length];
        final int[] roles = new int[ROLES.length];
        int sapper;
        int sapper_swing;
        int peon_other;
        int dead_w;
        int dead_w_gun;
        int dead_p;
        int dead_p_gun;
        int gun_w;
        int gun_p;
        int gun_b;
        int gun_none;
        int gun_stun;
        int max_in;
        int max_throw;
        int max_swing;
        int foes_sum;
        int big_samples;
        List<Unit> present = new ArrayList<>();
        @Nullable
        Selectable<?> gun_target;

        Watch(int id, float first, int hp0) {
            this.id = id;
            this.first = first;
            this.hp0 = hp0;
            this.hp = hp0;
            this.last_seen = first;
        }
    }

    private final @NonNull FableAI ai;
    private final Map<Building, Watch> watches = new LinkedHashMap<>();
    private float last_sample;
    private int serial;
    private int half;
    private int pictures;

    TowerTrace(@NonNull FableAI ai) {
        this.ai = ai;
    }

    void tick() {
        if (!ai.logging() || !ai.periodDue(last_sample, 25f)) // 0.5 s
            return;
        last_sample = ai.now();
        half++;
        Intel intel = ai.intel();
        // contacts that ended
        for (Iterator<Map.Entry<Building, Watch>> it = watches.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Building, Watch> e = it.next();
            Building t = e.getKey();
            Watch w = e.getValue();
            String why = t.isDead() ? "razed" : !Intel.isTowerManned(
                    t) ? "unmanned" : ai.now() - w.last_seen > QUIET_TICKS ? "left" : null;
            if (why == null)
                continue;
            countDead(w);
            end(t, w, why);
            it.remove();
        }
        for (Building t : intel.enemy_towers) {
            if (!Intel.isTowerManned(t))
                continue;
            Watch w = watches.get(t);
            List<Unit> in = new ArrayList<>();
            int warriors = 0;
            for (Unit u : intel.warriors)
                if (!u.isDead() && within(u, t, REACH)) {
                    in.add(u);
                    warriors++;
                }
            if (w == null && warriors == 0)
                continue;
            for (Unit p : intel.peons)
                if (!p.isDead() && within(p, t, REACH))
                    in.add(p);
            if (w == null) {
                w = new Watch(serial++, ai.now(), t.getHitPoints());
                watches.put(t, w);
            }
            sample(t, w, in);
        }
    }

    private static boolean within(@NonNull Unit u, @NonNull Building t, int cells) {
        return MapAnalysis.dist2(u.getGridX(), u.getGridY(), t.getGridX(), t.getGridY()) <= cells * cells;
    }

    private void countDead(@NonNull Watch w) {
        for (Unit u : w.present)
            if (u.isDead()) {
                boolean peon = u.getAbilities().hasAbilities(Abilities.BUILD);
                boolean gun = u == w.gun_target;
                if (peon) {
                    w.dead_p++;
                    if (gun)
                        w.dead_p_gun++;
                } else {
                    w.dead_w++;
                    if (gun)
                        w.dead_w_gun++;
                }
            }
        w.present = new ArrayList<>();
    }

    private void sample(@NonNull Building t, @NonNull Watch w, @NonNull List<Unit> in) {
        Intel intel = ai.intel();
        countDead(w);
        w.samples++;
        int hp = t.getHitPoints();
        if (hp < w.hp && w.first_dmg < 0f)
            w.first_dmg = ai.now();
        w.hp = hp;
        if (!in.isEmpty())
            w.last_seen = ai.now();
        int[] now = new int[STATES.length];
        int[] near = new int[STATES.length];
        int n_w = 0;
        int sappers = 0;
        int swings = 0;
        int others = 0;
        for (Unit u : in) {
            if (u.getAbilities().hasAbilities(Abilities.BUILD)) {
                if (intel.sappers.contains(u)) {
                    sappers++;
                    if (u.getCurrentController() instanceof HuntController h && h.getTarget() == t
                            && u.getCurrentBehaviour() instanceof AttackBehaviour)
                        swings++;
                } else
                    others++;
                continue;
            }
            n_w++;
            int s = state(u, t);
            now[s]++;
            if (within(u, t, NEAR))
                near[s]++;
            String role = ai.military().roleOf(u);
            w.roles["attack".equals(role) ? 0 : "reinforce".equals(role) ? 1 : 2]++;
        }
        for (int s = 0; s < STATES.length; s++) {
            w.w[s] += now[s];
            w.w_near[s] += near[s];
        }
        w.sapper += sappers;
        w.sapper_swing += swings;
        w.peon_other += others;
        w.max_in = Math.max(w.max_in, n_w);
        w.max_throw = Math.max(w.max_throw, now[0]);
        w.max_swing = Math.max(w.max_swing, swings);
        // pictures of the first big contacts: 4 s and 10 s after 20 warriors are in reach
        if (n_w >= 20 || w.big_samples > 0) {
            w.big_samples++;
            if ((w.big_samples == 8 || w.big_samples == 20) && pictures < 8) {
                pictures++;
                ai.aiLog().log("TOWER", () -> "picture #" + w.id + " tower at " + t.getGridX() + "," + t.getGridY());
                ai.military().describeJam(t.getGridX(), t.getGridY());
            }
        }
        int foes = 0;
        for (Unit e : intel.enemy_warriors)
            if (!e.isDead() && within(e, t, REACH))
                foes++;
        w.foes_sum += foes;
        // the garrison
        Unit gunner = Intel.gunner(t);
        Selectable<?> gun_target = null;
        String gun = "none";
        if (gunner == null || gunner.isDead())
            w.gun_none++;
        else if (Intel.isStunned(gunner)) {
            w.gun_stun++;
            gun = "stunned";
        } else if (gunner.getCurrentController() instanceof AttackController a && !a.getTarget().isDead()) {
            gun_target = a.getTarget();
            boolean peon = gun_target instanceof Unit g && g.getAbilities().hasAbilities(Abilities.BUILD);
            if (peon)
                w.gun_p++;
            else if (gun_target instanceof Unit)
                w.gun_w++;
            else
                w.gun_b++;
            gun = (peon ? "peon" : gun_target instanceof Unit ? "warrior" : "building") + "@" + (int) Math.sqrt(
                    MapAnalysis.dist2(gun_target.getGridX(), gun_target.getGridY(), t.getGridX(), t.getGridY()));
        } else
            w.gun_none++;
        w.gun_target = gun_target;
        w.present = in;
        if (half % 2 == 0) {
            int[] c = ai.military().attackCenter();
            int[] f = ai.military().attackFocus();
            StringBuilder sb = new StringBuilder();
            sb.append("s #").append(w.id).append(" hp ").append(hp).append(" in ").append(n_w).append(" [");
            for (int s = 0; s < STATES.length; s++)
                sb.append(s == 0 ? "" : " ").append(now[s]);
            sb.append("] near [");
            for (int s = 0; s < STATES.length; s++)
                sb.append(s == 0 ? "" : " ").append(near[s]);
            sb.append("] sappers ").append(sappers).append(" swing ").append(swings).append(" peons ").append(
                    others).append(" foes ").append(foes).append(" gun ").append(gun).append(" army ").append(
                            c == null ? -1 : (int) Math.sqrt(MapAnalysis.dist2(c[0], c[1], t.getGridX(),
                                    t.getGridY()))).append(
                                            " target ").append(f == null ? -1 : (int) Math.sqrt(MapAnalysis.dist2(f[0],
                                                    f[1], t.getGridX(), t.getGridY())));
            ai.aiLog().log("TOWER", sb::toString);
        }
    }

    private static int state(@NonNull Unit u, @NonNull Building t) {
        Behaviour b = u.getCurrentBehaviour();
        Controller c = u.getCurrentController();
        if (c instanceof HuntController h) {
            Selectable<?> target = h.getTarget();
            if (target == t)
                return b instanceof AttackBehaviour ? 0 : 1;
            if (target instanceof Unit e)
                return e.getAbilities().hasAbilities(Abilities.BUILD) ? 3 : 2;
            return 4;
        }
        if (b instanceof WalkBehaviour)
            return b.isBlocking() ? 6 : 5;
        if (b instanceof IdleBehaviour)
            return 7;
        return 8;
    }

    private void end(@NonNull Building t, @NonNull Watch w, @NonNull String why) {
        StringBuilder sb = new StringBuilder();
        sb.append("end #").append(w.id).append(' ').append(why).append(" at ").append(t.getGridX()).append(',').append(
                t.getGridY()).append(" owner ").append(t.getOwner().getPlayerInfo().getName()).append(String.format(
                        java.util.Locale.ROOT, " dur %.1f first_dmg %.1f hp %d>%d samples %d",
                        (w.last_seen - w.first) / 50f, w.first_dmg < 0f ? -1f : (w.first_dmg - w.first) / 50f, w.hp0,
                        t.isDead() ? 0 : w.hp, w.samples));
        sb.append(" w [");
        for (int s = 0; s < STATES.length; s++)
            sb.append(s == 0 ? "" : " ").append(w.w[s]);
        sb.append("] near [");
        for (int s = 0; s < STATES.length; s++)
            sb.append(s == 0 ? "" : " ").append(w.w_near[s]);
        sb.append("] roles [").append(w.roles[0]).append(' ').append(w.roles[1]).append(' ').append(w.roles[2]).append(
                "] sappers ").append(w.sapper).append(" swing ").append(w.sapper_swing).append(" peons ").append(
                        w.peon_other).append(" dead_w ").append(w.dead_w).append(" by_gun ").append(
                                w.dead_w_gun).append(" dead_p ").append(w.dead_p).append(" by_gun ").append(
                                        w.dead_p_gun).append(" gun [").append(
                                                w.gun_w).append(' ').append(w.gun_p).append(' ').append(
                                                        w.gun_none).append(' ').append(
                                                                w.gun_stun).append("] max_in ").append(w.max_in).append(
                                                                        " max_throw ").append(
                                                                                w.max_throw).append(
                                                                                        " max_swing ").append(
                                                                                                w.max_swing).append(
                                                                                                        " foes ").append(
                                                                                                                w.foes_sum).append(
                                                                                                                        " gun_b ").append(
                                                                                                                                w.gun_b);
        ai.aiLog().log("TOWER", sb::toString);
    }
}
