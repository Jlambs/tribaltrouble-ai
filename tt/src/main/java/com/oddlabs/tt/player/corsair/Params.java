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
    final int min_peons;
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
    final boolean chief_rides;
    final boolean rower_topup;
    final int beach_building_cells;
    final int beach_warrior_cells;
    final boolean sink_docked;
    final boolean tower_fire;
    final double ally_join;
    final boolean kite;
    final boolean team_target;
    final double kite_slack;
    final int kite_step;
    final boolean drilled_garrison;
    final boolean tower_unstun;
    final int rower_floor;
    final double worker_share;
    final boolean upgrade;
    final int rock_axe_cap;
    final boolean militia;
    final int raid;
    final int raid_range;
    final boolean snipe_towers;
    final boolean stage;
    final int elite_trips;
    final int bombard;
    final int siege_after;
    final boolean site_trees;
    final int outnumbered_towers;
    final double range_edge;
    final double drill_adapt;
    final int drill_max;
    final double abort_ratio;
    final boolean tower_face_land;
    final boolean siege_peons;
    final int stage_max;
    final boolean ratchet;
    final int ratchet_trips;

    // opening
    final double travel_weight;
    final double contest_weight;

    final boolean survey;

    /** standard, blitz (early small raids, few towers) or turtle (many towers, late big assaults). */
    final @NonNull String style;

    Params(@NonNull AiParams a) {
        style = a.getString("style", "standard");
        boolean blitz = style.equals("blitz"), turtle = style.equals("turtle");
        if (!blitz && !turtle && !style.equals("standard"))
            throw new IllegalArgumentException("style " + style);
        swing_restart = a.getBoolean("swing_restart", true);
        stun_cancel = a.getBoolean("stun_cancel", true);
        quarters = a.getInt("quarters", blitz ? 3 : 4);
        peons_per_quarters = a.getInt("hold", 10);
        armories = a.getInt("armories", 1);
        wood_share = a.getDouble("wood_share", .5);
        rock_share = a.getDouble("rock_share", .15);
        rock_weapons = a.getBoolean("rock_weapons", false);
        min_peons = a.getInt("min_peons", 45);
        max_towers = a.getInt("max_towers", blitz ? 1 : turtle ? 8 : 4);
        tower_start = a.getInt("tower_start", blitz ? 600 : turtle ? 200 : 300);
        tower_every = a.getInt("tower_every", turtle ? 60 : 120);
        chieftain = a.getBoolean("chieftain", !blitz);
        chieftain_start = a.getInt("chieftain_start", 420);
        expedition_size = a.getInt("expedition", blitz ? 12 : turtle ? 60 : 20);
        attack_ratio = a.getDouble("attack_ratio", blitz ? .8 : turtle ? 2.0 : 1.3);
        rowers = a.getInt("rowers", 20);
        max_ships = a.getInt("ships", 3);
        ship_start = a.getInt("ship_start", blitz ? 150 : 300);
        chief_rides = a.getBoolean("chief_rides", false);
        rower_topup = a.getBoolean("rower_topup", true);
        beach_building_cells = a.getInt("beach_buildings", 50);
        beach_warrior_cells = a.getInt("beach_warriors", 45);
        sink_docked = a.getBoolean("sink_docked", false);
        tower_fire = a.getBoolean("tower_fire", true);
        ally_join = a.getDouble("ally_join", 1.0);
        kite = a.getBoolean("kite", true);
        team_target = a.getBoolean("team_target", false);
        kite_slack = a.getDouble("kite_slack", 3.0);
        kite_step = a.getInt("kite_step", 5);
        drilled_garrison = a.getBoolean("drilled_garrison", true);
        tower_unstun = a.getBoolean("tower_unstun", false);
        rower_floor = a.getInt("rower_floor", 0);
        worker_share = a.getDouble("worker_share", 0);
        upgrade = a.getBoolean("upgrade", false);
        rock_axe_cap = a.getInt("rock_axe_cap", 0);
        militia = a.getBoolean("militia", true);
        raid = a.getInt("raid", 0);
        raid_range = a.getInt("raid_range", 350);
        snipe_towers = a.getBoolean("snipe_towers", false);
        stage = a.getBoolean("stage", false);
        elite_trips = a.getInt("elite_trips", 8);
        bombard = a.getInt("bombard", 0);
        siege_after = a.getInt("siege_after", 0);
        site_trees = a.getBoolean("site_trees", true);
        outnumbered_towers = a.getInt("outnumbered_towers", 2);
        range_edge = a.getDouble("range_edge", 0.05);
        drill_adapt = a.getDouble("drill_adapt", 4);
        drill_max = a.getInt("drill_max", 12);
        abort_ratio = a.getDouble("abort_ratio", 1.0);
        tower_face_land = a.getBoolean("tower_face_land", false);
        siege_peons = a.getBoolean("siege_peons", true);
        stage_max = a.getInt("stage_max", 600);
        ratchet = a.getBoolean("ratchet", true);
        ratchet_trips = a.getInt("ratchet_trips", 4);
        travel_weight = a.getDouble("travel_weight", 1.0);
        contest_weight = a.getDouble("contest_weight", 1.0);
        survey = a.getBoolean("survey", false);
    }
}
