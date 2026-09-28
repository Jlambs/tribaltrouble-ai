package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Trains the chieftain once the economy can spare a quarters for it, keeps him near the fighting but out of reach,
 * and blows the stun (the viking toot) when it catches enough enemies, towers included, to decide the fight.
 */
final class Chieftain {
    /** Stun radius is 36 m (18 cells); count warriors a little inside that, as they keep moving. */
    private static final int STUN_CELLS = 16;
    /**
     * An enemy chieftain inside the radius (less the few meters he walks during the wind-up) is caught before he casts.
     */
    private static final int STUN_REACH = 17;
    private static final float MOVE_PERIOD = 1.5f;
    /** Cells from an active enemy tower the chieftain keeps: out of its throws (16 cells), inside the stun's reach. */
    private static final int TOWER_KEEP = 17;
    /** Squared cells within which a tower is caught by the stun from the standoff. */
    private static final int TOWER_CAUGHT2 = 300;

    private final @NonNull GauntletAI ai;
    private float last_move = -100f;
    private float last_cast = -100f;
    /** Enemy warriors in reach when the last stun was cast, checked once it has gone off. */
    private final java.util.List<@NonNull Unit> cast_candidates = new java.util.ArrayList<>();
    private float cast_check = -1f;
    /** Most of the candidates seen stunned so far: the stun may go off late if the chieftain was mid-swing. */
    private int cast_best;
    /** Running share of the enemies in reach that our stuns caught; low against an enemy who runs from the horn. */
    private float catch_rate = 1f;

    Chieftain(@NonNull GauntletAI ai) {
        this.ai = ai;
    }

    private int magicIndex() {
        if (ai.owner().getRace() == ai.owner().getWorld().getRacesResources().getRace(RacesResources.RACE_VIKINGS))
            return RacesResources.INDEX_MAGIC_STUN;
        return ai.strategy().native_lightning ? RacesResources.INDEX_MAGIC_LIGHTNING : RacesResources.INDEX_MAGIC_POISON;
    }

    /** Seconds since the chieftain last cast, or a large number. */
    float sinceCast() {
        return ai.time() - last_cast;
    }

    private boolean isViking() {
        return ai.owner().getRace() == ai.owner().getWorld().getRacesResources().getRace(RacesResources.RACE_VIKINGS);
    }

    /**
     * Whether a sonic blast now kills far more of theirs than of ours. It reaches 36 m from a point just in front of
     * the chieftain and hits friend and foe alike; count ours a little further out, as they keep moving.
     */
    private boolean shouldBlast(@NonNull Unit chief) {
        Intel intel = ai.intel();
        int x = chief.getGridX();
        int y = chief.getGridY();
        float theirs = 0f;
        for (Unit e : intel.enemy_warriors)
            if (MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 16 * 16)
                theirs += Combat.value(e);
        for (Unit e : intel.enemy_peons)
            if (MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 16 * 16)
                theirs += .3f;
        float ours = 0f;
        for (Unit u : intel.warriors)
            if (!u.isMounted() && MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()) <= 20 * 20)
                ours += Combat.value(u);
        for (Unit u : intel.peons)
            if (MapAnalysis.dist2(x, y, u.getGridX(), u.getGridY()) <= 20 * 20)
                ours += .3f;
        boolean go = theirs >= ai.strategy().blast_min && theirs >= ai.strategy().blast_ratio * ours;
        if (go)
            ai.log(String.format("chieftain blasts at %d,%d: %.1f of theirs against %.1f of ours", x, y, theirs,
                    ours));
        return go;
    }

    /** Whether the chieftain could blow the sonic blast now, and is fit to walk up to an enemy army for it. */
    boolean blastReady() {
        Unit chief = ai.intel().chieftain;
        return chief != null && !chief.isDead() && isViking() && chief.getHitPoints() > 40
                && chief.canDoMagic(RacesResources.INDEX_MAGIC_BLAST) && !Intel.isStunned(chief);
    }

    boolean stunReady() {
        Unit chief = ai.intel().chieftain;
        return chief != null && !chief.isDead() && chief.canDoMagic(magicIndex());
    }

    @Nullable
    Building trainingQuarters() {
        for (Building q : ai.intel().quarters) {
            if (q.getChieftainContainer() != null && q.getChieftainContainer().isTraining())
                return q;
        }
        return null;
    }

    void tick() {
        checkCatch();
        Unit chief = ai.intel().chieftain;
        if (chief == null) {
            considerTraining();
            return;
        }
        if (Intel.isStunned(chief))
            return;
        if (ai.strategy().blast && isViking() && chief.canDoMagic(RacesResources.INDEX_MAGIC_BLAST)
                && shouldBlast(chief)) {
            ai.owner().doMagic(chief, RacesResources.INDEX_MAGIC_BLAST);
            last_cast = ai.time();
            return;
        }
        if (shred(chief))
            return;
        if (stunReady() && shouldStun(chief) && !savingForBlast(chief)) {
            int x = chief.getGridX();
            int y = chief.getGridY();
            String rivals = "";
            for (Unit e : ai.intel().enemy_chieftains)
                if (!e.isDead())
                    rivals += String.format(" [enemy chief %d cells%s%s]", (int) Math.sqrt(MapAnalysis.dist2(x, y,
                            e.getGridX(), e.getGridY())),
                            e.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.MagicController ? " casting" : "",
                            ai.military().enemySpellReady(e) ? " ready" : "");
            ai.log("chieftain stuns at " + x + "," + y + ": " + Combat.countNear(ai.intel().enemy_warriors, x, y,
                    17) + " enemy warriors in reach" + rivals);
            ai.owner().doMagic(chief, magicIndex());
            last_cast = ai.time();
            if (isViking()) {
                cast_candidates.clear();
                for (Unit e : ai.intel().enemy_warriors)
                    if (!Intel.isStunned(e) && MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 17 * 17)
                        cast_candidates.add(e);
                cast_check = ai.time() + 8f;
                cast_best = 0;
            }
            return;
        }
        if (ai.strategy().shred && ai.strategy().shred_strict && isViking())
            den(chief);
        else
            position(chief);
    }

    /**
     * Strict shred: between blasts the chieftain keeps out of fights at the staging point, and backs off towards the
     * armory from any awake enemy warrior within 16 cells (it never heals, and no N=10 game was won with our chieftain
     * dead by 15 min).
     */
    private void den(@NonNull Unit chief) {
        if (ai.time() - last_move < MOVE_PERIOD || ai.military().isDodging(chief))
            return;
        Military military = ai.military();
        int tx = military.stagingX();
        int ty = military.stagingY();
        Building home = ai.intel().armory();
        if (home != null) {
            // Behind the armory: the staging point is 13 cells in front of it, towards the enemy.
            int ax = home.getGridX();
            int ay = home.getGridY();
            float dx = ax - tx;
            float dy = ay - ty;
            float len = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
            int size = ai.map().getSize();
            tx = Math.max(3, Math.min(size - 4, ax + Math.round(8 * dx / len)));
            ty = Math.max(3, Math.min(size - 4, ay + Math.round(8 * dy / len)));
        }
        if (nearestWarriorDistance(chief.getGridX(), chief.getGridY()) <= 16) {
            Building armory = ai.intel().armory();
            if (armory != null) {
                tx = armory.getGridX();
                ty = armory.getGridY();
            }
            int[] away = awayFromWarriors(tx, ty, 17);
            tx = away[0];
            ty = away[1];
        }
        if (MapAnalysis.dist2(chief.getGridX(), chief.getGridY(), tx, ty) <= 4 * 4)
            return;
        last_move = ai.time();
        ai.landscapeOrder(Selectable.newArray(chief), tx, ty, Action.MOVE, false);
    }

    /** The idle blob the chieftain is walking to, to blast it, or null. */
    private int @Nullable [] shred_target;
    private float shred_trace = -100f;
    private float shred_trace2 = -100f;

    /**
     * The shred mission: idle enemy blobs (waves parked by shepherds, or left idle after razing something) see 8 cells
     * and never react to being hit, and a lone chieftain sets off no chieftain spell of theirs (lightning wants 2 of
     * our units within 30 m, stun and poison 5). So with the blast charged he walks up to 9-12 cells from the blob's
     * nearest member and blows it: every rock warrior within 36 m dies, iron ones with P = 0.6 (SonicBlast: hit chance
     * never below 2, times 1 - defense). Returns whether the mission took charge of him this round.
     */
    private boolean shred(@NonNull Unit chief) {
        Strategy strategy = ai.strategy();
        if (!strategy.shred || !isViking())
            return false;
        boolean charged = chief.canDoMagic(RacesResources.INDEX_MAGIC_BLAST);
        if (ai.logging() && ai.time() - shred_trace >= 20f) {
            shred_trace = ai.time();
            int[] b = findBlob(chief.getGridX(), chief.getGridY());
            ai.log(String.format("shred: charged %b hp %d blast %.2f blob %s", charged, chief.getHitPoints(),
                    chief.getMagicProgress(RacesResources.INDEX_MAGIC_BLAST),
                    b == null ? "none" : b[3] + " at " + b[0] + "," + b[1] + " nearest " + b[2]));
        }
        if (!charged || chief.getHitPoints() <= strategy.shred_min_hp) {
            shred_target = null;
            return false;
        }
        int cx = chief.getGridX();
        int cy = chief.getGridY();
        // Anything awake coming at him ends the mission.
        if (strategy.shred_strict && blastHere(chief, cx, cy))
            return true;
        for (Unit e : ai.intel().enemy_warriors) {
            if (e.isDead() || isParked(e))
                continue;
            int abort = strategy.shred_strict ? 9 : 13; // strict: only what can see him
            if (MapAnalysis.dist2(cx, cy, e.getGridX(), e.getGridY()) <= abort * abort) {
                if (shred_target != null)
                    ai.aiLog().count("shred_abort");
                shred_target = null;
                return false;
            }
        }
        int[] blob = findBlob(cx, cy);
        if (blob == null) {
            shred_target = null;
            return false;
        }
        shred_target = blob;
        if (strategy.shred_strict) {
            java.util.List<Unit> enemies = new java.util.ArrayList<>(ai.intel().enemy_warriors);
            enemies.addAll(ai.intel().enemy_chieftains);
            enemies.addAll(ai.intel().enemy_peons);
            int[] at = castPoint(blob, cx, cy);
            if (at == null && ai.logging() && ai.time() - shred_trace2 >= 20f) {
                shred_trace2 = ai.time();
                // What sees the ring around the blob: per kind, how many ring cells each blocks.
                int[] seen_by = new int[4];
                int cells = 0;
                for (int r = 10; r <= 24; r += 2)
                    for (int a = 0; a < 24; a++) {
                        double ang = a * Math.PI / 12;
                        int x = blob[0] + (int) Math.round(r * Math.cos(ang));
                        int y = blob[1] + (int) Math.round(r * Math.sin(ang));
                        cells++;
                        for (Unit e : enemies)
                            if (!e.isDead() && Math.abs(e.getGridX() - x) <= 8 && Math.abs(e.getGridY() - y) <= 8) {
                                int k = ai.intel().enemy_peons.contains(e) ? 0 : ai.intel().enemy_chieftains.contains(
                                        e) ? 1 : isParked(e) ? 2 : 3;
                                seen_by[k]++;
                                break;
                            }
                    }
                int fc = cells;
                ai.log(String.format(
                        "shred: no point around blob %d at %d,%d: of %d ring cells seen by peon %d, chief %d, " + "parked %d, awake %d",
                        blob[3], blob[0], blob[1], fc, seen_by[0], seen_by[1], seen_by[2],
                        seen_by[3]));
            }
            if (at == null) {
                ai.aiLog().count("shred_nopoint");
                return false;
            }
            if (MapAnalysis.dist2(cx, cy, at[0], at[1]) <= 1 && at[2] >= strategy.shred_min) {
                ai.log(String.format("chieftain blasts a parked blob at %d,%d from %d,%d (%d in reach)", blob[0],
                        blob[1], cx, cy, at[2]));
                ai.owner().doMagic(chief, RacesResources.INDEX_MAGIC_BLAST);
                last_cast = ai.time();
                last_move = ai.time();
                ai.aiLog().count("shred_blast");
                shred_target = null;
                return true;
            }
            if (ai.time() - last_move >= 1f && !ai.military().isDodging(chief)) {
                ai.landscapeOrder(Selectable.newArray(chief), at[0], at[1], Action.MOVE, false);
                last_move = ai.time();
            }
            return true;
        }
        int nearest = blob[2];
        int centre2 = MapAnalysis.dist2(cx, cy, blob[0], blob[1]);
        if (nearest >= 9 && nearest <= 13 && centre2 <= 16 * 16) {
            ai.log(String.format("chieftain blasts a parked blob of %d at %d,%d (nearest %d cells)", blob[3], blob[0],
                    blob[1], nearest));
            ai.owner().doMagic(chief, RacesResources.INDEX_MAGIC_BLAST);
            last_cast = ai.time();
            last_move = ai.time();
            ai.aiLog().count("shred_blast");
            shred_target = null;
            return true;
        }
        if (ai.time() - last_move >= 1f && !ai.military().isDodging(chief)) {
            int[] stop = nearest > 11 ? towards(cx, cy, blob[0], blob[1], Math.max(2, nearest - 10)) : towards(blob[0],
                    blob[1], cx, cy, 12);
            ai.landscapeOrder(Selectable.newArray(chief), stop[0], stop[1], Action.MOVE, false);
            last_move = ai.time();
        }
        return true;
    }

    /**
     * Stun and blast share one charge, so with a parked blob to shred and the base not seriously threatened the
     * chieftain holds his stun until the blast (70 s) is charged.
     */
    private boolean savingForBlast(@NonNull Unit chief) {
        if (ai.strategy().shred && isViking() && ai.strategy().shred_strict)
            return true; // any cast zeroes both charges (Unit.java:727): a stun would throw the blast away
        if (!ai.strategy().shred || !isViking() || chief.canDoMagic(RacesResources.INDEX_MAGIC_BLAST))
            return false;
        if (ai.military().baseThreatLevel() >= 2 || chief.getHitPoints() <= ai.strategy().shred_min_hp)
            return false;
        return findBlob(chief.getGridX(), chief.getGridY()) != null;
    }

    /**
     * Where to blast the blob from: {x, y, parked enemies within 17 cells}. A walkable cell 10-24 cells from the blob's
     * centre, at least 9 cells (Chebyshev: idle units scan an 8-cell square) from every enemy unit, 22 from enemy
     * towers, with no unit or building of ours within 19 cells (the blast reaches 18 and hits friends), catching at
     * least shred_min parked warriors; enemy chieftains in reach count 8 more (two blasts kill one, and a copy past
     * wave
     * size 20 cannot launch without one). Nearer to the chieftain breaks ties. Null if there is none.
     */
    /** Strict shred: blasts from where the chieftain stands if that catches enough; true if it did. */
    private boolean blastHere(@NonNull Unit chief, int cx, int cy) {
        java.util.List<Unit> enemies = new java.util.ArrayList<>(ai.intel().enemy_warriors);
        enemies.addAll(ai.intel().enemy_chieftains);
        enemies.addAll(ai.intel().enemy_peons);
        java.util.List<Selectable<?>> ours = new java.util.ArrayList<>();
        for (Selectable<?> s : ai.owner().getUnits().getSet())
            if (!s.isDead() && s != chief)
                ours.add(s);
        int here = castValue(cx, cy, enemies, java.util.List.of(), ours, null);
        if (here < ai.strategy().shred_min)
            return false;
        ai.log(String.format("chieftain blasts from %d,%d (%d enemy warriors in reach)", cx, cy, here));
        ai.owner().doMagic(chief, RacesResources.INDEX_MAGIC_BLAST);
        last_cast = ai.time();
        last_move = ai.time();
        ai.aiLog().count("shred_blast");
        for (int i = 0; i < here; i++)
            ai.aiLog().count("shred_caught");
        shred_target = null;
        return true;
    }

    /**
     * Parked enemies a blast from (x, y) catches, or -1 when (x, y) is no place to blast from (see castPoint). why, if
     * given, counts the first reason a cell fails: ground, seen, tower, friends, few.
     */
    private int castValue(int x, int y, java.util.@NonNull List<@NonNull Unit> enemies,
            java.util.@NonNull List<@NonNull Unit> parked, java.util.@NonNull List<@NonNull Selectable<?>> ours,
            int @Nullable [] why) {
        if (!ai.map().passable(x, y)) {
            if (why != null)
                why[0]++;
            return -1;
        }
        // Whatever sees the caster (8 cells, Chebyshev: at most 11.3 away) stands inside the 18-cell blast, which goes
        // off ~3.8 s after the cast; only enemies close enough to throw at him before then rule a cell out.
        for (Unit e : enemies)
            if (!e.isDead() && Math.abs(e.getGridX() - x) <= 5 && Math.abs(e.getGridY() - y) <= 5) {
                if (why != null)
                    why[1]++;
                return -1;
            }
        for (Building t : ai.intel().enemy_towers)
            if (MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY()) <= 22 * 22) {
                if (why != null)
                    why[2]++;
                return -1;
            }
        // The blast hits friends: none of our units within 19 cells; at most two of our buildings within 18, none of
        // them below 40 hit points (each loses ~30).
        int buildings = 0;
        int friends = 0;
        for (Selectable<?> s : ours) {
            int d2 = MapAnalysis.dist2(x, y, s.getGridX(), s.getGridY());
            if (s instanceof Building b) {
                if (d2 <= 18 * 18 && (++buildings > 2 || b.getHitPoints() < 40)) {
                    if (why != null)
                        why[3]++;
                    return -1;
                }
            } else if (d2 <= 19 * 19) {
                friends++;
            }
        }
        int hit = 0;
        for (Unit e : enemies)
            if (!e.isDead() && !e.getAbilities().hasAbilities(com.oddlabs.tt.model.Abilities.BUILD)
                    && MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 17 * 17)
                hit++;
        // Our units in the blast die too: worth it only at three of theirs for each of ours.
        if (hit < 3 * friends) {
            if (why != null)
                why[3]++;
            return -1;
        }
        if (hit < ai.strategy().shred_min) {
            if (why != null)
                why[4]++;
            return -1;
        }
        return hit;
    }

    private int @Nullable [] castPoint(int @NonNull [] blob, int cx, int cy) {
        Intel intel = ai.intel();
        java.util.List<Unit> enemies = new java.util.ArrayList<>(intel.enemy_warriors);
        enemies.addAll(intel.enemy_chieftains);
        enemies.addAll(intel.enemy_peons);
        java.util.List<Unit> parked = new java.util.ArrayList<>();
        for (Unit e : intel.enemy_warriors)
            if (!e.isDead() && isParked(e))
                parked.add(e);
        java.util.List<Selectable<?>> ours = new java.util.ArrayList<>();
        for (Selectable<?> s : ai.owner().getUnits().getSet())
            if (!s.isDead() && s != intel.chieftain)
                ours.add(s);
        int[] best = null;
        float best_score = -Float.MAX_VALUE;
        int[] why = new int[5];
        for (int r = 10; r <= 24; r += 2) {
            for (int a = 0; a < 24; a++) {
                double ang = a * Math.PI / 12;
                int x = blob[0] + (int) Math.round(r * Math.cos(ang));
                int y = blob[1] + (int) Math.round(r * Math.sin(ang));
                int hit = castValue(x, y, enemies, parked, ours, why);
                if (hit < 0)
                    continue;
                int chiefs = 0;
                for (Unit c : intel.enemy_chieftains)
                    if (!c.isDead() && MapAnalysis.dist2(x, y, c.getGridX(), c.getGridY()) <= 17 * 17)
                        chiefs++;
                float score = 10f * (hit + 8 * chiefs) - (float) Math.sqrt(MapAnalysis.dist2(x, y, cx, cy));
                if (score > best_score) {
                    best_score = score;
                    best = new int[]{x, y, hit};
                }
            }
        }
        if (best == null) {
            int m = 0;
            for (int i = 1; i < why.length; i++)
                if (why[i] > why[m])
                    m = i;
            ai.aiLog().count("shred_nopoint_" + new String[]{"ground", "seen", "tower", "friends", "few"}[m]);
        }
        return best;
    }

    /** An enemy warrior standing idle, not hunting: it sees 8 cells and does not react to being hit. */
    private static boolean isParked(@NonNull Unit e) {
        return e.getPrimaryController() instanceof com.oddlabs.tt.model.behaviour.IdleController
                && e.getCurrentController() == e.getPrimaryController();
    }

    /**
     * The best blob of parked enemy warriors within shred_range cells of our armory: {x, y, nearest member's distance
     * from (cx, cy) in cells, members}, with at least shred_min members within 10 cells of its centre, no awake enemy
     * warrior within 16 cells, no enemy tower within 22, and none of our units within 20 (the blast hits friends too).
     */
    private int @Nullable [] findBlob(int cx, int cy) {
        Intel intel = ai.intel();
        Strategy strategy = ai.strategy();
        Building armory = intel.armory();
        int hx = armory != null ? armory.getGridX() : ai.planner().getStartX();
        int hy = armory != null ? armory.getGridY() : ai.planner().getStartY();
        java.util.List<Unit> parked = new java.util.ArrayList<>();
        java.util.List<Unit> awake = new java.util.ArrayList<>();
        for (Unit e : intel.enemy_warriors) {
            if (e.isDead())
                continue;
            (isParked(e) ? parked : awake).add(e);
        }
        int[] best = null;
        float best_score = 0f;
        int range2 = strategy.shred_range * strategy.shred_range;
        for (Unit seed : parked) {
            int sx = seed.getGridX();
            int sy = seed.getGridY();
            if (MapAnalysis.dist2(sx, sy, hx, hy) > range2)
                continue;
            long x = 0;
            long y = 0;
            int n = 0;
            for (Unit e : parked)
                if (MapAnalysis.dist2(sx, sy, e.getGridX(), e.getGridY()) <= 10 * 10) {
                    x += e.getGridX();
                    y += e.getGridY();
                    n++;
                }
            if (n < strategy.shred_min)
                continue;
            int bx = (int) (x / n);
            int by = (int) (y / n);
            if (!clearAround(bx, by, awake))
                continue;
            float d = (float) Math.sqrt(MapAnalysis.dist2(cx, cy, bx, by));
            float score = n / (30f + d);
            if (score > best_score) {
                int nearest = Integer.MAX_VALUE;
                for (Unit e : parked)
                    nearest = Math.min(nearest, MapAnalysis.dist2(cx, cy, e.getGridX(), e.getGridY()));
                best_score = score;
                best = new int[]{bx, by, (int) Math.sqrt(nearest), n};
            }
        }
        return best;
    }

    /** No awake enemy warrior within 16 cells, no enemy tower within 22 and none of our units within 20. */
    private boolean clearAround(int bx, int by, java.util.@NonNull List<@NonNull Unit> awake) {
        Intel intel = ai.intel();
        if (ai.strategy().shred_strict) {
            // castPoint keeps the chieftain out of every enemy's sight and our units out of the blast; awake enemies
            // by the blob only add to what it kills.
            for (Building t : intel.enemy_towers)
                if (MapAnalysis.dist2(bx, by, t.getGridX(), t.getGridY()) <= 22 * 22)
                    return false;
            return true;
        }
        for (Unit e : awake)
            if (MapAnalysis.dist2(bx, by, e.getGridX(), e.getGridY()) <= 16 * 16)
                return false;
        for (Building t : intel.enemy_towers)
            if (MapAnalysis.dist2(bx, by, t.getGridX(), t.getGridY()) <= 22 * 22)
                return false;
        for (Unit u : intel.warriors)
            if (!u.isMounted() && MapAnalysis.dist2(bx, by, u.getGridX(), u.getGridY()) <= 20 * 20)
                return false;
        for (Unit u : intel.peons)
            if (MapAnalysis.dist2(bx, by, u.getGridX(), u.getGridY()) <= 20 * 20)
                return false;
        return true;
    }

    /** While our stun goes off, counts how many of the enemies in reach it caught, at best. */
    private void checkCatch() {
        if (cast_check < 0f)
            return;
        int alive = 0;
        int caught = 0;
        for (Unit e : cast_candidates) {
            if (e.isDead())
                continue;
            alive++;
            if (Intel.isStunned(e))
                caught++;
        }
        cast_best = Math.max(cast_best, caught);
        if (ai.time() < cast_check)
            return;
        cast_check = -1f;
        caught = Math.min(alive, cast_best);
        cast_candidates.clear();
        Unit chief = ai.intel().chieftain;
        String state = chief == null || chief.isDead() ? " (our chief died)" : Intel.isStunned(
                chief) ? " (our chief stunned)" : "";
        if (alive < 4) {
            if (!state.isEmpty())
                ai.log("our stun: " + state.trim());
            return;
        }
        float share = caught / (float) alive;
        catch_rate = .6f * catch_rate + .4f * share;
        ai.log(String.format("our stun caught %d of %d in reach (running share %.2f)%s", caught, alive, catch_rate,
                state));
    }

    /** Whether the enemy has been seen running from our stun. */
    boolean enemyDodges() {
        return ai.strategy().stun_learn && catch_rate < ai.strategy().dodge_catch;
    }

    private void considerTraining() {
        Strategy strategy = ai.strategy();
        Intel intel = ai.intel();
        if (ai.owner().isTrainingChieftain() || !ai.owner().canBuildChieftains())
            return;
        if (intel.quarters.size() < strategy.chieftain_min_quarters || ai.time() < strategy.chieftain_time)
            return;
        if (intel.armory() == null)
            return;
        Building best = null;
        float best_score = -Float.MAX_VALUE;
        for (Building q : intel.quarters) {
            if (!q.canBuildChieftain())
                continue;
            float score = q.getUnitContainer().getNumSupplies() - 20f * ai.planner().exposure(q.getGridX(),
                    q.getGridY());
            if (score > best_score) {
                best_score = score;
                best = q;
            }
        }
        if (best != null) {
            ai.log("training chieftain in quarters at " + best.getGridX() + "," + best.getGridY());
            ai.owner().trainChieftain(best, true);
        }
    }

    private boolean shouldStun(@NonNull Unit chief) {
        Intel intel = ai.intel();
        int x = chief.getGridX();
        int y = chief.getGridY();
        int r2 = STUN_CELLS * STUN_CELLS;
        float warriors = 0f;
        // Against an enemy who runs from the horn only the ones too close to get away count in full.
        boolean dodges = enemyDodges();
        int core2 = ai.strategy().dodge_core * ai.strategy().dodge_core;
        for (Unit e : intel.enemy_warriors) {
            if (Intel.isStunned(e))
                continue;
            int d2 = MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY());
            if (d2 <= r2)
                warriors += !dodges || d2 <= core2 ? 1f : .25f;
        }
        int towers = 0;
        int tower_r2 = ai.strategy().chief_tower_standoff ? TOWER_CAUGHT2 : r2;
        // A stunned tower is only worth it with an army at hand to pull it down.
        int[] army = ai.military().attackCenter();
        boolean follow_up = !ai.strategy().tower_stun_follow_up
                || (army != null && MapAnalysis.dist2(x, y, army[0], army[1]) <= 25 * 25);
        for (Building t : intel.enemy_towers)
            if (follow_up && Intel.isTowerActive(t) && MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY()) <= tower_r2)
                towers++;
        int chiefs = 0;
        for (Unit e : intel.enemy_chieftains) {
            if (Intel.isStunned(e) || MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) > STUN_REACH * STUN_REACH)
                continue;
            chiefs++;
            // Whoever stuns first wins: a stunned chieftain cannot answer with his own spell.
            if (ai.military().enemySpellReady(e))
                return true;
        }
        // Laying a siege, one tower from the standoff is what the army has been waiting for.
        if (towers > 0 && ai.military().sieging())
            return true;
        float caught = warriors + 3f * towers + 3f * chiefs;
        // A wounded chieftain stuns whatever is on him before he dies.
        if (chief.getHitPoints() <= 20 && caught >= 1f)
            return true;
        // Saving up for the blast: past half its charge the stun would throw it away.
        if (ai.strategy().blast && ai.strategy().blast_save
                && chief.getMagicProgress(RacesResources.INDEX_MAGIC_BLAST) >= .5f)
            return false;
        // With our own warriors at hand to cut down the stunned, a smaller catch is already worth it.
        int ours = Combat.countNear(intel.warriors, x, y, 20);
        float needed = ours >= 6 ? 4f : 6f;
        if (caught < needed)
            return false;
        if (!ai.strategy().stun_patience)
            return true;
        // The stun comes back only after 40 s: spent on the first few of a big army, the rest walk in unhindered.
        // Wait until most of what is closing in is inside, and while an enemy chieftain who could answer is on his
        // way, keep it to catch him too or to answer his spell.
        float coming = warriors + Combat.countNear(intel.enemy_warriors, x, y, 32) - Combat.countNear(
                intel.enemy_warriors, x, y, STUN_CELLS);
        float share = caught / Math.max(1f, coming + 3f * towers + 3f * chiefs);
        boolean answer = false;
        for (Unit e : intel.enemy_chieftains)
            answer |= !Intel.isStunned(e) && MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()) <= 45 * 45
                    && ai.military().enemySpellReady(e);
        if (answer)
            return caught >= 20f || (share >= .8f && caught >= 10f);
        return caught >= 20f || share >= .55f;
    }

    private void position(@NonNull Unit chief) {
        int safe = ai.strategy().chief_safe;
        boolean threatened = safe > 0 && !stunReady()
                && nearestWarriorDistance(chief.getGridX(), chief.getGridY()) <= safe;
        if ((ai.time() - last_move < MOVE_PERIOD && !threatened) || ai.military().isDodging(chief))
            return;
        Military military = ai.military();
        int[] army = military.attackCenter();
        int[] enemies = nearestEnemies(chief.getGridX(), chief.getGridY(), 30);
        int tx;
        int ty;
        if (chief.getHitPoints() <= ai.strategy().chief_flee_hp) {
            Building armory = ai.intel().armory();
            tx = armory != null ? armory.getGridX() : military.stagingX();
            ty = armory != null ? armory.getGridY() : military.stagingY();
        } else if (ai.military().blastPlay() != null) {
            // Meet the enemy army alone: walk up to just outside its throws; the blast goes off once it is worth it.
            int[] at = ai.military().blastPlay();
            int nearest = nearestWarriorDistance(chief.getGridX(), chief.getGridY());
            if (nearest <= 9) {
                tx = chief.getGridX();
                ty = chief.getGridY();
            } else {
                int[] stop = towards(chief.getGridX(), chief.getGridY(), at[0], at[1], Math.max(3, nearest - 9));
                tx = stop[0];
                ty = stop[1];
            }
        } else if (enemies != null && stunReady()) {
            // Walk into stun range of the nearest enemies; the stun goes off as soon as enough are caught. Stop short
            // of their throws: the radius reaches well past them.
            tx = enemies[0];
            ty = enemies[1];
            int keep = ai.strategy().chief_keep_out;
            if (keep > 0) {
                int nearest = nearestWarriorDistance(chief.getGridX(), chief.getGridY());
                if (nearest <= keep + 1) {
                    tx = chief.getGridX();
                    ty = chief.getGridY();
                } else {
                    int[] stop = towards(chief.getGridX(), chief.getGridY(), enemies[0], enemies[1], nearest - keep);
                    tx = stop[0];
                    ty = stop[1];
                }
            }
        } else if (army != null) {
            // March inside the clump, a little behind its middle.
            int[] back = towards(army[0], army[1], military.stagingX(), military.stagingY(), enemies != null ? 8 : 3);
            tx = back[0];
            ty = back[1];
        } else if (military.baseThreatLevel() > 0) {
            int[] back = towards(military.threatX(), military.threatY(), military.stagingX(), military.stagingY(),
                    12);
            tx = back[0];
            ty = back[1];
        } else {
            tx = military.stagingX();
            ty = military.stagingY();
        }
        if (safe > 0 && !stunReady()) {
            int[] away = awayFromWarriors(tx, ty, safe + 1);
            tx = away[0];
            ty = away[1];
        }
        if (ai.strategy().chief_tower_standoff && chief.getHitPoints() > 24) {
            int[] clear = clearOfTowers(tx, ty, chief.getGridX(), chief.getGridY());
            tx = clear[0];
            ty = clear[1];
        }
        if (MapAnalysis.dist2(chief.getGridX(), chief.getGridY(), tx, ty) <= 3 * 3)
            return;
        last_move = ai.time();
        ai.landscapeOrder(Selectable.newArray(chief), tx, ty, Action.MOVE, false);
    }

    /**
     * Moves a destination out to TOWER_KEEP cells from active enemy towers, away from the tower or, for a point on
     * it, back towards (from_x, from_y).
     */
    private int @NonNull [] clearOfTowers(int x, int y, int from_x, int from_y) {
        for (int pass = 0; pass < 3; pass++) {
            Building near = null;
            int best = TOWER_KEEP * TOWER_KEEP;
            for (Building t : ai.intel().enemy_towers) {
                int d = MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY());
                if (Intel.isTowerActive(t) && d < best) {
                    best = d;
                    near = t;
                }
            }
            if (near == null)
                break;
            float dx = x - near.getGridX();
            float dy = y - near.getGridY();
            if (dx * dx + dy * dy < 1f) {
                dx = from_x - near.getGridX();
                dy = from_y - near.getGridY();
            }
            float len = Math.max(.1f, (float) Math.sqrt(dx * dx + dy * dy));
            x = near.getGridX() + Math.round(dx / len * TOWER_KEEP);
            y = near.getGridY() + Math.round(dy / len * TOWER_KEEP);
        }
        int size = ai.map().getSize();
        return new int[]{Math.clamp(x, 0, size - 1), Math.clamp(y, 0, size - 1)};
    }

    /** Moves a point out to `keep` cells from the enemy warriors nearest to it, a few passes deep. */
    private int @NonNull [] awayFromWarriors(int x, int y, int keep) {
        for (int pass = 0; pass < 3; pass++) {
            Unit near = null;
            int best = keep * keep;
            for (Unit e : ai.intel().enemy_warriors) {
                if (Intel.isStunned(e))
                    continue;
                int d = MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY());
                if (d < best) {
                    best = d;
                    near = e;
                }
            }
            if (near == null)
                break;
            float dx = x - near.getGridX();
            float dy = y - near.getGridY();
            if (dx * dx + dy * dy < 1f) {
                dx = ai.military().stagingX() - near.getGridX();
                dy = ai.military().stagingY() - near.getGridY();
            }
            float len = Math.max(.1f, (float) Math.sqrt(dx * dx + dy * dy));
            x = near.getGridX() + Math.round(dx / len * keep);
            y = near.getGridY() + Math.round(dy / len * keep);
        }
        int size = ai.map().getSize();
        return new int[]{Math.clamp(x, 0, size - 1), Math.clamp(y, 0, size - 1)};
    }

    /** Cells to the nearest enemy warrior that is not stunned, or a large number. */
    private int nearestWarriorDistance(int x, int y) {
        int best = Integer.MAX_VALUE;
        for (Unit e : ai.intel().enemy_warriors)
            if (!Intel.isStunned(e))
                best = Math.min(best, MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY()));
        return best == Integer.MAX_VALUE ? 1000 : (int) Math.sqrt(best);
    }

    /** The point `cells` away from (x, y) in the direction of (to_x, to_y). */
    private static int @NonNull [] towards(int x, int y, int to_x, int to_y, int cells) {
        float dx = to_x - x;
        float dy = to_y - y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= cells)
            return new int[]{to_x, to_y};
        return new int[]{x + (int) (dx / len * cells), y + (int) (dy / len * cells)};
    }

    /** Middle of the enemy warriors and manned towers around the nearest one within radius cells, or null. */
    private int @Nullable [] nearestEnemies(int x, int y, int radius) {
        Intel intel = ai.intel();
        Selectable<?> nearest = null;
        int best = radius * radius;
        for (Unit e : intel.enemy_warriors) {
            if (Intel.isStunned(e))
                continue;
            int d = MapAnalysis.dist2(x, y, e.getGridX(), e.getGridY());
            if (d < best) {
                best = d;
                nearest = e;
            }
        }
        for (Building t : intel.enemy_towers) {
            if (!Intel.isTowerActive(t))
                continue;
            int d = MapAnalysis.dist2(x, y, t.getGridX(), t.getGridY());
            if (d < best) {
                best = d;
                nearest = t;
            }
        }
        if (nearest == null)
            return null;
        long sx = 0;
        long sy = 0;
        int n = 0;
        for (Unit e : intel.enemy_warriors) {
            if (MapAnalysis.dist2(nearest.getGridX(), nearest.getGridY(), e.getGridX(), e.getGridY()) <= 12 * 12) {
                sx += e.getGridX();
                sy += e.getGridY();
                n++;
            }
        }
        if (n == 0)
            return new int[]{nearest.getGridX(), nearest.getGridY()};
        return new int[]{(int) (sx / n), (int) (sy / n)};
    }
}
