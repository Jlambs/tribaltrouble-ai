package com.oddlabs.tt.player.fable;

import org.jspecify.annotations.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * Every tunable of the hard AI in one place. Values are plain public fields so the match runner can override them
 * with {@code name=value,name=value} strings ({@link #override}) for tuning experiments; a normal game always uses
 * the defaults, so all peers of a multiplayer game agree.
 *
 * <p>Distances are in grid cells (2 m) unless the name says metres; times are game seconds.
 */
public final class Params {
    // ---------------------------------------------------------------- tick rates
    public float fast_period = 0.15f;
    public float slow_period = 0.5f;
    public float econ_period = 1.0f;

    // ---------------------------------------------------------------- opening / base layout
    /** Max distance (cells) from the start for the armory site. */
    public int armory_max_dist = 110;
    /** Min distance (cells) from the start for the armory site (be closer to the centre iron than the beach). */
    public int armory_min_dist = 12;
    /** Half-angle (degrees) of the search sector around the direction start -> map centre. */
    public float armory_sector_deg = 75f;
    /** Site score weights (lower peon-seconds per warrior is better). */
    public float site_w_wood = 0.9f;
    public float site_w_iron = 0.45f;
    public float site_w_start = 0.12f;
    /** Cost per cell of detour (walk minus straight line) between the start and the armory site. */
    public float site_w_detour = 0.3f;
    /** Longest walk (cells) from the start to the armory site; beyond it the opening never gets going. */
    public int armory_max_walk = 150;
    public float site_iron_radius = 60;
    public int site_min_iron_nodes = 8;
    public float site_penalty_few_iron = 40f;
    public float site_enemy_iron_factor = 2f;
    /** Quarters: distance band from the armory and tree preference. */
    public int quarters_min_dist = 11;
    public int quarters_max_dist = 26;
    public int building_spacing = 11;
    public int tower_ring_radius = 12;
    public int tower_spacing_min = 7;
    /** Number of quarters wanted as a function of the peon count is computed; these bound it. */
    public int quarters_min = 4;
    public int quarters_max = 5;
    public int towers_max = 12;
    public int quarters_first_crew = 17;
    /** Quarters raised before the armory in the opening. */
    public int opening_quarters = 4;
    /** Quarters built at the same time during the opening. */
    public int opening_parallel = 2;

    // ---------------------------------------------------------------- economy
    /** Peons kept inside each quarters: opening (no armory), boom (armory up, below boom_units), late, at the cap. */
    public int breeders_early = 8;
    public int breeders_boom = 12;
    public int breeders_late = 6;
    public int breeders_rear = 1;
    public int boom_units = 190;
    public int breeders_training = 14;
    public int workers_min = 2;
    /** Workers beyond the target that the allocator tolerates inside the armory before sending them out. */
    public int workers_surplus_max = 20;
    public float stock_target_wood_per_rate = 80f;
    public float stock_target_iron_per_rate = 40f;
    public int stock_wood_min = 6;
    public int stock_wood_max = 30;
    public int stock_iron_min = 3;
    public int stock_iron_max = 15;
    public int stock_rock_target = 6;
    public int rock_reserve = 8;
    public int max_peons_per_node = 4;
    public int gather_radius = 70;
    public int conversions_per_tick = 2;
    public float conversion_cooldown = 6f;
    public int max_supply_orders_per_tick = 4;
    public int cap_margin = 2;
    public int cap_workers = 16;
    public int cap_wood = 6;
    public int cap_iron = 4;
    public int cap_weapon_buffer = 20;
    public int hunt_max_dist = 125;
    public int hunt_max_peons = 5;
    public int hunt_min_time = 200;
    /** Beyond this mean iron distance (metres) gatherers are pointed at specific nodes instead of the engine search. */
    public float far_iron_m = 50f;
    public int hot_node_radius = 20;

    // ---------------------------------------------------------------- construction
    public int crew_quarters = 10;
    public int crew_tower = 6;
    public int crew_armory_rebuild = 25;
    public int crew_armory_opening = 14;
    /** Expansion armory by fresh iron once the home field is mined out (see BuildPlanner.maybeExpand). */
    public boolean expansion_armory = true;
    public float expand_min_time = 540f;
    public float expand_iron_m = 110f;
    public int expand_max_dist = 140;
    public int crew_armory_expand = 14;
    /** Seconds without another expansion attempt after an expansion armory was destroyed. */
    public float expand_retry_after = 400f;
    public int expand_workers = 16;
    public float site_fail_timeout = 120f;
    /** Seconds a placed site may make no progress while a crew stands on it before it is abandoned. */
    public float site_stall_timeout = 60f;
    public int site_retries = 3;
    public int helper_radius = 45;

    // ---------------------------------------------------------------- chieftain
    public int chieftain_min_peons = 40;
    public float chieftain_min_time = 300f;
    public int chieftain_min_quarters = 3;
    public float stun_radius_cells = 18f;
    public int stun_cast_dist = 15;
    public float counter_toot_progress = 0.85f;
    public int chief_escape_hp = 30;
    public int chief_fragile_hp = 24;
    public int chief_follow_dist = 6;
    public int blast_min_peons = 10;
    public int blast_max_own = 2;

    // ---------------------------------------------------------------- military
    public float value_rock = 0.6f;
    public float value_iron = 1.0f;
    public float value_rubber = 1.6f;
    public float value_chief = 1.5f;
    public float value_tower_rock = 2.5f;
    public float value_tower_iron = 4f;
    public float value_tower_rubber = 6f;
    public float engage_ratio = 1.3f;
    public float hold_ratio = 0.8f;
    public float retreat_ratio = 0.55f; // a retreat under fire costs more than the fight it leaves
    public float attack_ratio = 1.35f;
    public float attack_ratio_no_chief = 2.0f;
    public float attack_ratio_stun = 1.15f;
    public float attack_min_time = 420f;
    public int attack_min_army = 18;
    /** Army value that attacks regardless of the odds (losses are cheap at the cap). */
    /**
     * From this army value on, attack as soon as the army also beats their home defence plus the march margin (waiting
     * at the cap only lets them match us).
     */
    public float attack_max_army = 90f;
    /** At the unit cap attack when the army is this fraction of the defence (losses cost nothing there). */
    public float capped_ratio = 1.0f;
    /** ... but never against a larger total enemy force than this fraction of the army. */
    public float capped_total_ratio = 1.1f;
    /** After cap_relax_time seconds at the cap the total ratio relaxes to this. */
    public float capped_total_ratio_relaxed = 1.15f;
    public float cap_relax_time = 240f;
    /** Extra strength required per second of march (the enemy's production joins its defence at once). */
    public float attack_margin_per_march_s = 0.25f;
    /** Seconds after a retreat before another attack may launch. */
    public float retreat_cooldown = 60f;
    /** Enemy value inside the disc that justifies a stun at home (a raid of four is not worth the cooldown). */
    public float base_stun_value = 6f;
    /** Re-order own stunned units: a new order replaces the stun controller (the engine allows it for anyone). */
    public boolean stun_recovery = true;
    /** Extra strength required beyond the ratio, covering what the enemy produces during the march. */
    public float attack_margin = 6f;
    public int home_guard_early = 3;
    public int home_guard_mid = 5;
    public int home_guard_late = 7;
    public int home_guard_defend_bonus = 3;
    public float guard_mid_time = 480f;
    public float guard_late_time = 840f;
    public int reinforce_batch = 6;
    public int reinforce_batch_near = 3;
    public int leg_length = 30;
    public float leg_arrival_fraction = 0.85f;
    public int leg_arrival_radius = 10;
    public float leg_timeout = 25f;
    public int staging_dist = 18;
    public int threat_radius = 45;
    public int alert_eta = 45;
    public float engage_reorder_period = 4f;
    public int leash_dist = 15;
    public int all_in_units = 235;
    public float posture_dwell = 10f;
    public int tower_reach2 = 253;
    public int stand_min2 = 250;
    public int stand_max2 = 275;
    public int siege_min_army = 17;
    /** Enemy field value under a besieged tower, as a fraction of the army, that makes the siege pointless. */
    public float siege_camp_ratio = 0.4f;
    public float siege_camp_patience = 40f;
    /** In BUILD_UP the staged army sweeps enemy groups off our gathering fields within this many cells of home. */
    public boolean field_sweep = true;
    public int sweep_radius = 130;
    /** Seconds in DEFEND after which a weaker group camping near the base gets swept instead of waited out. */
    public float defend_sweep_after = 60f;
    public float stun_window = 10f;
    public float defend_pursuit = 22f;
    /** Demolition corps: peons marching with the army to tear down towers (6 flat damage per swing). */
    public boolean corps = true;
    public int corps_size = 10;
    public int corps_min_army = 20;
    public int corps_min_demolish = 4;
    public int corps_follow_dist = 7;

    /** Apply {@code name=value,name=value} overrides by reflection. Unknown names are ignored with a log line. */
    public void override(@NonNull String spec) {
        if (spec.isBlank())
            return;
        for (String entry : spec.split(",")) {
            int eq = entry.indexOf('=');
            if (eq <= 0)
                continue;
            String name = entry.substring(0, eq).trim();
            String value = entry.substring(eq + 1).trim();
            try {
                Field f = Params.class.getField(name);
                if (Modifier.isStatic(f.getModifiers()))
                    continue;
                Class<?> t = f.getType();
                if (t == int.class)
                    f.setInt(this, Integer.parseInt(value));
                else if (t == float.class)
                    f.setFloat(this, Float.parseFloat(value));
                else if (t == boolean.class)
                    f.setBoolean(this, Boolean.parseBoolean(value) || value.equals("1"));
            } catch (ReflectiveOperationException | NumberFormatException e) {
                IO.println("Params: cannot apply override '" + entry + "': " + e);
            }
        }
    }

    @Override
    public @NonNull String toString() {
        StringBuilder sb = new StringBuilder();
        for (Field f : Params.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()))
                continue;
            try {
                sb.append(f.getName()).append('=').append(f.get(this)).append(' ');
            } catch (IllegalAccessException _) {
                // skip
            }
        }
        return sb.toString().trim().toLowerCase(Locale.ROOT);
    }
}
