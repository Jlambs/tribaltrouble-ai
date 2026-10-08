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
    final boolean blast_adapt;
    final boolean wood_weight;
    final int pack;
    final int dock_near;
    final int row_min;
    final int late_drill;
    final int late_trips;
    final double edge_floor;
    final boolean abort_edge;
    final int docks;
    final int dock_gap;
    final boolean rebeach;
    final int unload_wait;
    final int rock_until;
    final int expand;
    final int workers_max;
    final int workers_per;
    final int stall_drop;
    final boolean stall_raze;
    final boolean safe_build;
    final boolean militia_smart;
    final double spread_open;
    final int late_idle;
    final boolean ally_aid;
    final boolean expand_safe;
    final boolean rock_fallback;
    final int expand_wood;
    final boolean shelter;
    final boolean unstick;
    final boolean evac;
    final double outnumbered_ratio;
    final int neighbor_exp;
    final double ally_near;
    final int outpost;
    final int outpost_idle;
    final boolean team_march;
    final boolean tower_rush;
    final boolean soft_target;
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
        max_ships = a.getInt("ships", 5);
        ship_start = a.getInt("ship_start", blitz ? 150 : 300);
        chief_rides = a.getBoolean("chief_rides", false);
        rower_topup = a.getBoolean("rower_topup", true);
        beach_building_cells = a.getInt("beach_buildings", 50);
        beach_warrior_cells = a.getInt("beach_warriors", 45);
        sink_docked = a.getBoolean("sink_docked", false);
        tower_fire = a.getBoolean("tower_fire", true);
        ally_join = a.getDouble("ally_join", 1.0);
        kite = a.getBoolean("kite", true);
        team_target = a.getBoolean("team_target", true);
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
        blast_adapt = a.getBoolean("blast_adapt", false);
        wood_weight = a.getBoolean("wood_weight", false);
        pack = a.getInt("pack", 20);
        dock_near = a.getInt("dock_near", 40);
        row_min = a.getInt("row_min", 10);
        late_drill = a.getInt("late_drill", 1500);
        late_trips = a.getInt("late_trips", 15);
        edge_floor = a.getDouble("edge_floor", .3);
        abort_edge = a.getBoolean("abort_edge", true);
        docks = a.getInt("docks", 5);
        dock_gap = a.getInt("dock_gap", 30);
        rebeach = a.getBoolean("rebeach", true);
        unload_wait = a.getInt("unload_wait", 15);
        rock_until = a.getInt("rock_until", 0);
        expand = a.getInt("expand", 3);
        workers_max = a.getInt("workers_max", 14);
        workers_per = a.getInt("workers_per", 8);
        stall_drop = a.getInt("stall_drop", 180);
        stall_raze = a.getBoolean("stall_raze", true);
        safe_build = a.getBoolean("safe_build", false);
        militia_smart = a.getBoolean("militia_smart", false);
        spread_open = a.getDouble("spread_open", 0);
        late_idle = a.getInt("late_idle", 600);
        ally_aid = a.getBoolean("ally_aid", false);
        expand_safe = a.getBoolean("expand_safe", false);
        rock_fallback = a.getBoolean("rock_fallback", true);
        expand_wood = a.getInt("expand_wood", 0);
        shelter = a.getBoolean("shelter", false);
        unstick = a.getBoolean("unstick", false);
        evac = a.getBoolean("evac", false);
        outnumbered_ratio = a.getDouble("outnumbered_ratio", 1.0);
        neighbor_exp = a.getInt("neighbor_exp", 0);
        ally_near = a.getDouble("ally_near", 0);
        outpost = a.getInt("outpost", 0);
        outpost_idle = a.getInt("outpost_idle", 0);
        team_march = a.getBoolean("team_march", true);
        tower_rush = a.getBoolean("tower_rush", false);
        soft_target = a.getBoolean("soft_target", false);
        stage_max = a.getInt("stage_max", 600);
        ratchet = a.getBoolean("ratchet", true);
        ratchet_trips = a.getInt("ratchet_trips", 4);
        travel_weight = a.getDouble("travel_weight", 1.0);
        contest_weight = a.getDouble("contest_weight", 1.0);
        survey = a.getBoolean("survey", false);
    }
}
