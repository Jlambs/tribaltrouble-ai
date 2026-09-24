package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.BuildProductionContainer;
import com.oddlabs.tt.model.DeployContainer;
import com.oddlabs.tt.model.DeployType;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Supply;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.weapon.IronAxeWeapon;
import com.oddlabs.tt.model.weapon.RockAxeWeapon;
import com.oddlabs.tt.model.weapon.RubberAxeWeapon;
import com.oddlabs.tt.model.weapon.ThrowingWeapon;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.util.Target;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The only place that issues commands. Every call validates its preconditions first, because the game runs with
 * assertions enabled and a failed assert (dead target, mounted unit, missing container) would crash the game.
 */
final class Orders {
    enum Kind {
        MOVE,
        ATTACK_MOVE,
        ATTACK,
        GATHER,
        BUILD,
        REPAIR,
        ENTER,
        STOP,
        DODGE
    }

    /** The last order given to a unit, used to avoid re-issuing identical orders every think. */
    record Last(@NonNull Kind kind, @Nullable Object target, int x, int y, float time) {
    }

    private final @NonNull Context ctx;
    private final @NonNull Player p;
    private final Map<Unit, Last> last = new HashMap<>();
    int issued;

    Orders(@NonNull Context ctx) {
        this.ctx = ctx;
        this.p = ctx.owner;
    }

    /**
     * True if the unit can take an order. Stunned units are left alone: re-ordering one would swap out its stun
     * controller and give back its dodge chance while it stays frozen, an engine quirk we do not exploit.
     */
    boolean canOrder(@Nullable Unit u) {
        if (u == null || u.isDead() || u.isMounted() || u.getOwner() != p
                || u.getCurrentBehaviour() instanceof com.oddlabs.tt.model.behaviour.StunBehaviour)
            return false;
        Float hold = hold_until.get(u);
        return hold == null || ctx.now >= hold;
    }

    /** Units walking out of an enemy stun: no other order until this time. */
    private final java.util.Map<Unit, Float> hold_until = new java.util.LinkedHashMap<>();

    /** Moves the unit out of a pending enemy stun; other modules cannot re-order it until the given time. */
    void dodge(@NonNull Unit u, int gx, int gy, float until) {
        if (!canOrder(u) || !ctx.map.inside(gx, gy))
            return;
        p.setLandscapeTarget(Selectable.newArray(u), gx, gy, Action.MOVE, false);
        record(u, Kind.DODGE, null, gx, gy);
        hold_until.put(u, until);
    }

    static boolean alive(@Nullable Target t) {
        return t != null && !t.isDead();
    }

    @Nullable
    Last lastOrder(@NonNull Unit u) {
        return last.get(u);
    }

    /**
     * True if the unit was given this order within the last window seconds. Landscape points count as the same
     * order when within a tolerance that grows with the unit's distance, so moving destinations do not re-order.
     */
    boolean recently(@NonNull Unit u, @NonNull Kind kind, @Nullable Object target, int x, int y, float window) {
        Last l = last.get(u);
        if (l == null || l.kind != kind || l.target != target || ctx.now - l.time >= window)
            return false;
        if (target != null)
            return true;
        int dist = MapAnalysis.chebyshev(u.getGridX(), u.getGridY(), x, y);
        int tolerance = Math.max(2, dist / 8);
        return MapAnalysis.chebyshev(l.x, l.y, x, y) <= tolerance;
    }

    boolean recently(@NonNull Unit u, float window) {
        Last l = last.get(u);
        return l != null && ctx.now - l.time < window;
    }

    /** The stun being dodged will not come (its caster died): every dodging unit takes orders again. */
    void releaseHolds() {
        hold_until.clear();
    }

    void prune() {
        last.keySet().removeIf(Unit::isDead);
        hold_until.entrySet().removeIf(e -> e.getKey().isDead() || ctx.now >= e.getValue());
    }

    private void record(@NonNull Unit u, @NonNull Kind kind, @Nullable Object target, int x, int y) {
        last.put(u, new Last(kind, target, x, y, ctx.now));
        issued++;
    }

    // -----------------------------------------------------------------------------------------------------
    // Unit orders

    void move(@NonNull Unit u, int gx, int gy) {
        if (!canOrder(u) || !ctx.map.inside(gx, gy))
            return;
        p.setLandscapeTarget(Selectable.newArray(u), gx, gy, Action.MOVE, false);
        record(u, Kind.MOVE, null, gx, gy);
    }

    void attackMove(@NonNull Unit u, int gx, int gy) {
        if (!canOrder(u) || !ctx.map.inside(gx, gy))
            return;
        p.setLandscapeTarget(Selectable.newArray(u), gx, gy, Action.ATTACK, true);
        record(u, Kind.ATTACK_MOVE, null, gx, gy);
    }

    /** Group landscape order: the engine spreads the units over distinct free cells around the point. */
    void group(@NonNull List<Unit> units, int gx, int gy, boolean attack) {
        if (!ctx.map.inside(gx, gy))
            return;
        int n = 0;
        for (Unit u : units) {
            if (canOrder(u))
                n++;
        }
        if (n == 0)
            return;
        Selectable<?>[] sel = Selectable.newArray(n);
        int i = 0;
        for (Unit u : units) {
            if (canOrder(u)) {
                sel[i++] = u;
                record(u, attack ? Kind.ATTACK_MOVE : Kind.MOVE, null, gx, gy);
            }
        }
        p.setLandscapeTarget(sel, gx, gy, attack ? Action.ATTACK : Action.MOVE, attack);
    }

    void attack(@NonNull Unit u, @NonNull Selectable<?> target) {
        if (!canOrder(u) || !alive(target) || target.getOwner() == p)
            return;
        if (target instanceof Unit t && t.isMounted())
            return;
        p.setTarget(Selectable.newArray(u), target, Action.ATTACK, true);
        record(u, Kind.ATTACK, target, target.getGridX(), target.getGridY());
    }

    void gather(@NonNull Unit u, @NonNull Supply s) {
        if (!canOrder(u) || !alive(s) || s.isEmpty() || !u.getAbilities().hasAbilities(Abilities.BUILD))
            return;
        p.setTarget(Selectable.newArray(u), s, Action.GATHER_REPAIR, false);
        record(u, Kind.GATHER, s, s.getGridX(), s.getGridY());
    }

    /** Build (or help build) a site: an unplaced building object from {@link #place}, or a placed incomplete one. */
    void build(@NonNull Unit u, @NonNull Building b) {
        if (!canOrder(u) || b.isDead() || b.getOwner() != p || b.isComplete()
                || !u.getAbilities().hasAbilities(Abilities.BUILD))
            return;
        if (!b.isPlaced()) {
            // A foundation that is not laid yet cannot be clicked: wait next to its footprint (not on it, which
            // would block the placer) and help once it is placed.
            int half = b.getTemplate().getPlacingSize() / 2 + 2;
            int dx = u.getGridX() - b.getGridX();
            int dy = u.getGridY() - b.getGridY();
            int m = Math.max(1, Math.max(Math.abs(dx), Math.abs(dy)));
            int sx = b.getGridX() + Math.round((float) dx * half / m);
            int sy = b.getGridY() + Math.round((float) dy * half / m);
            if (Math.max(Math.abs(dx), Math.abs(dy)) > half && !recently(u, Kind.MOVE, null, sx, sy, 4f))
                move(u, sx, sy);
            return;
        }
        p.setTarget(Selectable.newArray(u), b, Action.DEFAULT, false);
        record(u, Kind.BUILD, b, b.getGridX(), b.getGridY());
    }

    void repair(@NonNull Unit u, @NonNull Building b) {
        if (!canOrder(u) || b.isDead() || b.getOwner() != p || !b.isPlaced() || !b.isDamaged()
                || !u.getAbilities().hasAbilities(Abilities.BUILD))
            return;
        p.setTarget(Selectable.newArray(u), b, Action.GATHER_REPAIR, false);
        record(u, Kind.REPAIR, b, b.getGridX(), b.getGridY());
    }

    /** Enter an own complete building (peons: quarters/armory; warriors: an empty tower or the armory). */
    void enter(@NonNull Unit u, @NonNull Building b) {
        if (!canOrder(u) || b.isDead() || b.getOwner() != p || !b.isComplete() || b.getUnitContainer() == null)
            return;
        // A besieged armory takes everyone inside down with it: peons wait at the rear instead (every shelter,
        // refuge and deposit path comes through here).
        if (ctx.economy != null && ctx.economy.shelterBlocked(u, b)) {
            int[] r = ctx.military.rearPoint();
            if (r != null) {
                if (ctx.military.inDanger(r[0], r[1])) {
                    if (ctx.military.inDanger(u.getGridX(), u.getGridY()) && !recently(u, 2.5f)) {
                        int[] away = ctx.military.awayFromEnemy(u.getGridX(), u.getGridY(), 14);
                        move(u, away[0], away[1]);
                    }
                    return;
                }
                if (!recently(u, Kind.MOVE, null, r[0], r[1], 3f))
                    move(u, r[0], r[1]);
                return;
            }
        }
        p.setTarget(Selectable.newArray(u), b, Action.MOVE, false);
        record(u, Kind.ENTER, b, b.getGridX(), b.getGridY());
    }

    void stop(@NonNull Unit u) {
        if (!canOrder(u))
            return;
        p.setTarget(Selectable.newArray(u), u, Action.DEFAULT, false);
        record(u, Kind.STOP, null, u.getGridX(), u.getGridY());
    }

    // -----------------------------------------------------------------------------------------------------
    // Building orders

    /**
     * Creates a building object at the cell and sends the builders to place it (exactly what
     * {@code Player.placeBuilding} does, but we keep the object so we can track it).
     */
    @Nullable
    Building place(@NonNull List<Unit> builders, int template_id, int gx, int gy) {
        int n = 0;
        for (Unit u : builders) {
            if (canOrder(u) && u.getAbilities().hasAbilities(Abilities.BUILD))
                n++;
        }
        if (n == 0 || !p.canBuild(template_id)
                || !com.oddlabs.tt.model.LandBuilding.isPlacingLegal(ctx.grid, ctx.race.getBuildingTemplate(
                        template_id), gx, gy))
            return null;
        Building b = ctx.race.getBuildingTemplate(template_id).create(p, gx, gy);
        Selectable<?>[] sel = Selectable.newArray(n);
        int i = 0;
        for (Unit u : builders) {
            if (canOrder(u) && u.getAbilities().hasAbilities(Abilities.BUILD)) {
                sel[i++] = u;
                record(u, Kind.BUILD, b, gx, gy);
            }
        }
        p.setTarget(sel, b, Action.DEFAULT, false);
        return b;
    }

    static boolean complete(@Nullable Building b) {
        return b != null && !b.isDead() && b.isComplete();
    }

    void deploy(@Nullable Building b, @NonNull DeployType type, int n) {
        if (!complete(b) || n == 0 || b.getOwner() != p)
            return;
        DeployContainer c = b.getDeployContainer(type);
        if (c == null)
            return;
        p.deployUnits(b, type, n);
        issued++;
    }

    /** Sets a weapon type's production state: infinite on, or fully stopped. Idempotent. */
    void weapons(@Nullable Building armory, @NonNull Class<? extends ThrowingWeapon> type, boolean on) {
        if (!complete(armory) || !armory.getAbilities().hasAbilities(Abilities.BUILD_ARMIES))
            return;
        BuildProductionContainer c = (BuildProductionContainer) armory.getBuildSupplyContainer(type);
        if (c == null)
            return;
        if (on) {
            if (c.isInfinite() && c.getNumSupplies() >= 30)
                return;
            if (type == RockAxeWeapon.class)
                p.buildRockWeapons(armory, 30, true);
            else if (type == IronAxeWeapon.class)
                p.buildIronWeapons(armory, 30, true);
            else if (type == RubberAxeWeapon.class)
                p.buildRubberWeapons(armory, 30, true);
        } else {
            if (!c.isInfinite() && c.getNumSupplies() == 0)
                return;
            if (type == RockAxeWeapon.class)
                p.buildRockWeapons(armory, -30, false);
            else if (type == IronAxeWeapon.class)
                p.buildIronWeapons(armory, -30, false);
            else if (type == RubberAxeWeapon.class)
                p.buildRubberWeapons(armory, -30, false);
        }
        issued++;
    }

    /**
     * Sends the n gatherers of the type delivering to this armory that are nearest to it back inside (what the
     * armory's recall button does), skipping stunned ones, which are never ordered.
     */
    void recallGatherers(@Nullable Building armory, @NonNull Class<?> type, int n) {
        if (!complete(armory) || n <= 0 || !Supply.class.isAssignableFrom(type))
            return;
        List<Unit> picks = new java.util.ArrayList<>();
        for (Unit u : ctx.model.me.peons) {
            if (canOrder(u) && WorldModel.primary(u) instanceof com.oddlabs.tt.model.behaviour.GatherController<?> gc
                    && gc.getSupplyType() == type && gc.getAssignedBuilding() == armory)
                picks.add(u);
        }
        picks.sort(java.util.Comparator.comparingInt(u -> MapAnalysis.dist2(u.getGridX(), u.getGridY(),
                armory.getGridX(), armory.getGridY())));
        for (int i = 0; i < Math.min(n, picks.size()); i++)
            enter(picks.get(i), armory);
    }

    void rally(@Nullable Building b, int gx, int gy) {
        if (!complete(b) || !ctx.map.inside(gx, gy) || !p.canSetRallyPoints())
            return;
        Target r = b.getRallyPoint();
        if (r != null && r != b && !(r instanceof Building) && r.getGridX() == gx && r.getGridY() == gy)
            return;
        p.setRallyPoint(b, gx, gy);
        issued++;
    }

    void clearRally(@Nullable Building b) {
        if (!complete(b) || !b.hasRallyPoint() || !p.canSetRallyPoints())
            return;
        p.setRallyPoint(b, b);
        issued++;
    }

    /** Explicit tower target. The engine ignores it if the target is out of the garrison's range. */
    void towerTarget(@NonNull Building tower, @NonNull Selectable<?> target) {
        if (!complete(tower) || !alive(target) || !tower.getAbilities().hasAbilities(Abilities.ATTACK)
                || tower.getUnitCount() == 0)
            return;
        if (target instanceof Unit t && t.isMounted())
            return;
        Unit g = WorldModel.garrisonOf(tower);
        if (g != null && WorldModel.isStunned(g))
            return;
        p.setTarget(Selectable.newArray(tower), target, Action.ATTACK, false);
        issued++;
    }

    boolean exitTower(@NonNull Building tower) {
        if (!complete(tower) || !tower.canExitTower())
            return false;
        p.exitTower(tower);
        issued++;
        return true;
    }

    /** Casts a spell. Returns true only if the cast really started. */
    boolean magic(@Nullable Unit chief, int index) {
        if (chief == null || chief.isDead() || chief.getOwner() != p || !chief.canDoMagic(index)
                || WorldModel.isStunned(chief))
            return false;
        p.doMagic(chief, index);
        issued++;
        // doMagic spends the energy and pushes the MagicController at once; the cast itself starts as soon as the
        // current behaviour (a walk step, a swing) can be interrupted. Either way it is committed.
        return chief.getCurrentController() instanceof com.oddlabs.tt.model.behaviour.MagicController;
    }

    void trainChieftain(@Nullable Building quarters) {
        if (!complete(quarters) || quarters.getTemplate().getTemplateID() != Race.BUILDING_QUARTERS
                || !quarters.canBuildChieftain())
            return;
        p.trainChieftain(quarters, true);
        issued++;
    }
}
