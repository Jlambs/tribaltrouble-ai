package com.oddlabs.tt.player.corsair;

import com.oddlabs.tt.aikit.AiParams;
import org.jspecify.annotations.NonNull;

/**
 * Every tunable of the AI, read once from the spec ({@code corsair:k=v,...}). Defaults are the current best.
 * Times are game seconds.
 */
final class Params {
    // reflexes
    final boolean swing_restart;
    final boolean stun_cancel;

    // economy
    final int quarters;
    final int peons_per_quarters;
    final int armories;
    final double wood_share;
    final double rock_share;
    final boolean rock_weapons;
    final int max_towers;
    final int tower_start;
    final int tower_every;
    final boolean chieftain;
    final int chieftain_start;

    // army and fleet
    final int expedition_size;
    final double attack_ratio;
    final int rowers;
    final int max_ships;
    final int ship_start;
    final boolean ratchet;
    final int ratchet_trips;

    // opening
    final double travel_weight;
    final double contest_weight;

    final boolean survey;

    Params(@NonNull AiParams a) {
        swing_restart = a.getBoolean("swing_restart", true);
        stun_cancel = a.getBoolean("stun_cancel", true);
        quarters = a.getInt("quarters", 4);
        peons_per_quarters = a.getInt("hold", 10);
        armories = a.getInt("armories", 1);
        wood_share = a.getDouble("wood_share", .5);
        rock_share = a.getDouble("rock_share", .15);
        rock_weapons = a.getBoolean("rock_weapons", true);
        max_towers = a.getInt("max_towers", 4);
        tower_start = a.getInt("tower_start", 300);
        tower_every = a.getInt("tower_every", 120);
        chieftain = a.getBoolean("chieftain", true);
        chieftain_start = a.getInt("chieftain_start", 420);
        expedition_size = a.getInt("expedition", 20);
        attack_ratio = a.getDouble("attack_ratio", 1.3);
        rowers = a.getInt("rowers", 12);
        max_ships = a.getInt("ships", 2);
        ship_start = a.getInt("ship_start", 360);
        ratchet = a.getBoolean("ratchet", false);
        ratchet_trips = a.getInt("ratchet_trips", 4);
        travel_weight = a.getDouble("travel_weight", 1.0);
        contest_weight = a.getDouble("contest_weight", 1.0);
        survey = a.getBoolean("survey", false);
    }
}
