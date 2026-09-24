package com.oddlabs.tt.player.ultra;

import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/** Shared state for all UltraAI modules. */
final class Context {
    /** Who currently owns a peon's time. Peons without an entry belong to the economy. */
    enum Role {
        BUILDER,
        HUNTER,
        CORPS,
        EVACUEE
    }

    final @NonNull Player owner;
    final @NonNull World world;
    final @NonNull UnitGrid grid;
    final @NonNull Race race;
    final @NonNull Params params;
    final @NonNull Strategy strategy;
    final @NonNull Random rng;
    final @NonNull MapAnalysis map;
    final @NonNull WorldModel model;
    final @NonNull SiteFinder sites;
    final @NonNull Orders orders;
    /** Lookup only; never iterated in a way that affects decisions. */
    private final Map<Unit, Role> roles = new HashMap<>();

    ResourceTracker resources;
    BuildManager build;
    EconomyManager economy;
    MilitaryManager military;
    TowerManager towers;
    ChieftainManager chief;
    CorpsManager corps;
    CreepManager creep;
    ProxyManager proxy;

    /** Game seconds since the AI started. */
    float now;
    int error_count;

    Context(@NonNull Player owner, @NonNull Params params, @NonNull Strategy strategy) {
        this.owner = owner;
        this.world = owner.getWorld();
        this.grid = world.getUnitGrid();
        this.race = owner.getRace();
        this.params = params;
        this.strategy = strategy;
        int slot = 0;
        Player[] players = world.getPlayers();
        for (int i = 0; i < players.length; i++) {
            if (players[i] == owner)
                slot = i;
        }
        this.rng = new Random(0x7777L ^ slot);
        this.map = new MapAnalysis(owner);
        this.model = new WorldModel(owner);
        this.sites = new SiteFinder(map);
        this.orders = new Orders(this);
    }

    Role roleOf(@NonNull Unit u) {
        return roles.get(u);
    }

    boolean hasRole(@NonNull Unit u) {
        return roles.containsKey(u);
    }

    void setRole(@NonNull Unit u, Role role) {
        if (role == null)
            roles.remove(u);
        else
            roles.put(u, role);
    }

    void clearRole(@NonNull Unit u) {
        roles.remove(u);
    }

    /** Drops entries for units that died or entered buildings. Removal has no side effects, so order is irrelevant. */
    void pruneRoles() {
        roles.keySet().removeIf(Unit::isDead);
    }

    void log(@NonNull Supplier<String> message) {
        if (UltraLog.enabled())
            UltraLog.log(owner, () -> String.format("%7.1f ", now) + message.get());
    }
}
