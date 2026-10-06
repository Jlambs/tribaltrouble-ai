package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.aikit.AiParams;
import com.oddlabs.tt.aikit.GameTime;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import com.oddlabs.tt.animation.AnimationManager;
import org.jspecify.annotations.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computer player {@code corsair}, built for Archipelago maps: it sails its starting peons to the best island it can
 * reach first, builds a base there, and ships its warriors to the enemy's islands.
 *
 * <p>Rules, orders and recipes: tt/src/main/java/com/oddlabs/tt/player/AGENTS.md
 */
public final class CorsairAI extends AI {
    final @NonNull AiLog log;
    final @NonNull Params params;
    private final GameTime.@NonNull Every every_second = new GameTime.Every(1000);
    final @NonNull Geo geo;
    final @NonNull SeaMap sea;
    final @NonNull Intel intel;
    final @NonNull Colony colony;
    final @NonNull Fleet fleet;
    final @NonNull Army army;
    final @NonNull Campaign campaign;
    final @NonNull Ratchet ratchet;
    final @NonNull Chief chief;
    private final @NonNull Reflexes reflexes;
    /** Units another module owns for now, with the game second the claim ends. */
    private final Map<@NonNull Unit, Integer> reserved = new LinkedHashMap<>();
    private int second;
    private boolean opened;

    public CorsairAI(@NonNull Player owner, @NonNull UnitInfo units, @NonNull String spec_params) {
        super(owner, units); // first: registers this AI and creates the starting units
        AiParams a = AiParams.parse(spec_params);
        params = new Params(a);
        log = AiLog.of(owner);
        log.log("PARAM", a.done()); // fails on unknown keys, so a typo cannot silently play the defaults
        geo = new Geo(owner);
        sea = new SeaMap(owner.getWorld().getUnitGrid());
        intel = new Intel(owner, geo);
        colony = new Colony(this);
        fleet = new Fleet(this);
        army = new Army(this);
        campaign = new Campaign(this);
        ratchet = new Ratchet(this);
        chief = new Chief(this);
        reflexes = new Reflexes(owner, log, params.swing_restart, params.stun_cancel);
    }

    @Override
    public void animate(float t) {
        try {
            float step = getOwner().getWorld().getSecondsPerTick() * t / AnimationManager.ANIMATION_SECONDS_PER_TICK;
            reflexes.tick(step);
        } catch (RuntimeException | AssertionError e) {
            log.error("reflexes", e);
        }
        if (!every_second.due(getOwner().getWorld())) {
            return;
        }
        try {
            think((int) (every_second.struck() / 1000));
        } catch (RuntimeException | AssertionError e) {
            log.error("think", e);
        }
    }

    private void think(int second) {
        this.second = second;
        intel.refresh();
        if (second == 1 && params.survey)
            survey();
        if (!opened) {
            opened = true;
            if (geo.archipelago && !intel.ships.isEmpty()) {
                fleet.openingVoyage(intel, second);
            } else {
                // land start (no archipelago): the base grows where the peons stand
                int x = Geo.gridOf(getOwner().getStartX()), y = Geo.gridOf(getOwner().getStartY());
                geo.scanSupplies();
                colony.found(geo.islandAt(x, y), x, y);
            }
        }
        reserved.entrySet().removeIf(e -> e.getKey().isDead() || e.getValue() < second);
        fleet.think(intel, second);
        campaign.think(intel, second);
        ratchet.think(intel, second);
        colony.think(second);
        army.think(intel, second);
        chief.think(intel, second);
        if (second % 30 == 0)
            stat();
    }

    @NonNull
    Player me() {
        return getOwner();
    }

    /** Whether a unit can take an order now: alive, and not inside a tower or aboard a ship. */
    static boolean orderable(@NonNull Unit u) {
        return !u.isDead() && !u.isMounted();
    }

    /** setTarget for the orderable units of the list (the engine asserts on dead and mounted ones). */
    void order(java.util.@NonNull List<@NonNull Unit> units, com.oddlabs.tt.util.@NonNull Target target,
            com.oddlabs.tt.model.@NonNull Action action, boolean aggressive) {
        if (target.isDead())
            return;
        java.util.List<Unit> ok = new java.util.ArrayList<>();
        for (Unit u : units)
            if (orderable(u))
                ok.add(u);
        if (!ok.isEmpty())
            getOwner().setTarget(ok.toArray(new Unit[0]), target, action, aggressive);
    }

    void order(@NonNull Unit u, com.oddlabs.tt.util.@NonNull Target target, com.oddlabs.tt.model.@NonNull Action action,
            boolean aggressive) {
        if (orderable(u) && !target.isDead())
            getOwner().setTarget(com.oddlabs.tt.model.Selectable.newArray(u), target, action, aggressive);
    }

    /** setLandscapeTarget for the orderable units of the list. */
    void move(java.util.@NonNull List<@NonNull Unit> units, int x, int y, com.oddlabs.tt.model.@NonNull Action action,
            boolean aggressive) {
        java.util.List<Unit> ok = new java.util.ArrayList<>();
        for (Unit u : units)
            if (orderable(u))
                ok.add(u);
        if (ok.isEmpty())
            return;
        int size = geo.size;
        getOwner().setLandscapeTarget(ok.toArray(new Unit[0]), Math.clamp(x, 1, size - 2), Math.clamp(y, 1, size - 2),
                action, aggressive);
    }

    int now() {
        return second;
    }

    boolean reserved(@NonNull Unit u) {
        return reserved.containsKey(u);
    }

    void reserve(@NonNull Unit u) {
        reserved.put(u, second + 120);
    }

    void reserve(@NonNull Unit u, int seconds) {
        reserved.put(u, second + seconds);
    }

    void release(@NonNull Unit u) {
        reserved.remove(u);
    }

    private void stat() {
        Intel in = intel;
        log.log("STAT",
                () -> "units " + in.units() + " peons " + in.peons.size() + "+" + in.peonsInside() + " warriors " + in.warriors.size() + " range+" + String.format(
                        "%.1f", Ratchet.meanBonus(
                                in.warriors)) + " q " + in.quarters.size() + " a " + in.armories.size() + " t " + in.towers.size() + " ships " + in.ships.size() + " sites " + in.sites.size() + " projects " + colony.projects.size());
    }

    /** One line per island and per player: what the map gives, for lab/corsair/survey.py. */
    private void survey() {
        Player me = getOwner();
        int sx = Geo.gridOf(me.getStartX()), sy = Geo.gridOf(me.getStartY());
        log.log("SURVEY", () -> "map size " + geo.size + " isles " + geo.isles.size());
        Player[] players = me.getWorld().getPlayers();
        for (int i = 0; i < players.length; i++) {
            Player p = players[i];
            int slot = i;
            int px = Geo.gridOf(p.getStartX()), py = Geo.gridOf(p.getStartY());
            log.log("SURVEY",
                    () -> "player " + slot + " team " + p.getPlayerInfo().getTeam() + " start " + px + " " + py + (p == me ? " me" : ""));
        }
        for (Geo.Isle isle : geo.isles) {
            int[] near = isle.nearestShore(sx, sy);
            int d = near == null ? -1 : Geo.dist(sx, sy, near[0], near[1]);
            log.log("SURVEY",
                    () -> "isle " + isle.id + " area " + isle.area + " trees " + isle.trees + " rocks " + isle.rocks + " iron " + isle.iron + " center " + isle.cx + " " + isle.cy + " shore " + isle.shore.size() + " dist " + d);
        }
    }
}
