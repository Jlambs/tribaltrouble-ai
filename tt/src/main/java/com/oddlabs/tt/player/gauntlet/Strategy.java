package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.matchmaking.Game;
import com.oddlabs.tt.aikit.AiParams;
import org.jspecify.annotations.NonNull;

/**
 * Tunable numbers behind the expert AI's plan. The defaults are tuned for 1v1 on large islands; {@link #forMapSize}
 * adjusts them for other sizes, and new styles of play can subclass or copy this.
 */
class Strategy {
    /** Quarters to raise before or alongside the armory. */
    int initial_quarters = 4;
    /** Quarters to have once the economy is running. More than five pays off little. */
    int max_quarters = 4;
    /** Seconds after which to aim for max_quarters. */
    float expand_time = 300f;

    /** Upper bound on how far the armory may be from the start, as walking meters. */
    int max_armory_distance = 260;
    /** Peon-seconds per warrior of gathering that one meter of walking from the start is worth. */
    float armory_distance_weight = .03f;
    /** Peon-seconds per warrior of gathering that one second of delay to the armory is worth. */
    float armory_delay_weight = .4f;
    /** How strongly to avoid putting the armory towards the enemy. */
    float armory_threat_weight = 60f;

    /** Builders for the first quarters, the rest of the starting peons scout and lay out the base. */
    int scouts = 1;
    /** Builders the armory is expected to get, for estimating how long it takes to build. */
    int armory_builders = 16;
    /** Most builders on a quarters once the armory stands, and on a tower. */
    int quarters_builders = 12;
    int tower_builders = 8;
    /** Tower projects waiting to be placed at once, and non-armory sites standing unfinished at once. */
    int tower_parallel = 1;
    /**
     * Attacks go after the copy they hit last: its buildings count focus_bonus meters nearer, and with focus_finish
     * its last units are hunted down (within 90 cells) once its buildings are gone, before it can rebuild.
     */
    float focus_bonus = 0f;
    /** Against several enemies, the enemy warriors within this many cells of a target count in full as its defense. */
    int defense_radius = 150;
    /** Share of the ore gatherers sent for rock, with rock weapons always on order (0: rock only as a fallback). */
    float rock_share = 0f;
    /** Attack a copy's quarters before its armory: the peons bred inside die with it and chieftain training stops. */
    boolean quarters_first = false;
    /**
     * Attack targets score meters from the army, plus target_defense_weight meters per unit of the defense expected
     * there, plus target_home_weight times the meters from our staging point (keeps the campaign near home).
     */
    float target_defense_weight = 8f;
    float target_home_weight = 0f;
    /**
     * Attack even with the base threatened, when the enemies in the base are worth less than this share of the army.
     */
    float attack_threat_ratio = 0f;
    /** An attack is called home when the enemies in the base beat the home defense and this share of the attack. */
    float recall_ratio = .35f;
    boolean focus_finish = false;
    int sites_parallel = 2;
    /** Quarters completed before builders move to the armory. */
    int quarters_before_armory = 4;
    /** Raise the opening quarters next to the first one instead of next to the armory site. */
    boolean opening_near_start = false;
    /**
     * When the enemy arms early (six warriors out, or an armory up with fewer than rush_quarters quarters) while ours
     * is
     * not up yet, move the armory ahead of the remaining opening quarters and put weapons before quarters for up to
     * rush_seconds.
     */
    boolean rush_response = true;
    int rush_quarters = 2;
    float rush_seconds = 240f;
    /**
     * Until pressure_time, while enemies in the base outnumber our warriors, keep gathering away from the fighting
     * instead of hiding while the armory starves.
     */
    boolean pressure_response = true;
    float pressure_time = 720f;

    /** Peons to keep inside each quarters to speed up reproduction, early and later in the game. */
    int hold_early = 4;
    int hold_mid = 14;
    int hold_late = 8;
    float hold_mid_time = 240f;
    /** Peons kept in the quarters that trains the chieftain, to finish him sooner. */
    int hold_chieftain = 14;

    /**
     * Hold the stun until it catches most of the enemies closing in, and longer while an enemy chieftain with his
     * spell ready is near enough to join the fight, rather than spending it on the first few.
     */
    boolean stun_patience = true;
    /** Charge enemies lying stunned near the attacking army instead of weighing the odds against them. */
    boolean exploit_stun = true;
    /** While charging the stunned, let each warrior also pick from the enemies still awake around the army. */
    boolean charge_mixed = false;
    /**
     * When an enemy viking chieftain raises his horn, run the units near the edge of the stun's reach out of it
     * before it goes off, as a player watching the fight would.
     */
    boolean dodge_stun = true;
    /**
     * The viking chieftain's other spell, the sonic blast, kills nearly every unit within 18 cells, ours included, and
     * takes 70 s to charge. Blow it instead of the stun when the enemies in reach are worth blast_ratio times our own
     * units there and at least blast_min.
     */
    boolean blast = false;
    float blast_ratio = 6f;
    float blast_min = 12f;
    /**
     * Against a strong attack on the base with the blast charged, pull the defenders back out of its reach and send
     * the chieftain to meet the enemy alone, for up to blast_play_time seconds.
     */
    boolean blast_defense = false;
    float blast_play_time = 14f;
    /** Past half the blast's charge, hold the stun for it (it still answers an enemy chieftain). */
    boolean blast_save = false;
    /**
     * Warriors too deep inside the stun's reach to get out throw at the winding-up chieftain instead: he stands still,
     * and his spell dies with him.
     */
    boolean hunt_caster = true;
    /** Cells from the winding-up chieftain within which trapped warriors go for him. */
    int hunt_caster_cells = 11;
    /** Defenders charge enemies lying stunned around the threat, as the attacking army does. */
    boolean defend_exploit_stun = false;
    /** Keep out of poison fog, the enemy's and our own chieftain's (it hurts his own side too), until it lifts. */
    boolean dodge_fog = true;
    /**
     * The native chieftain's spell: poison fog, or the lightning cloud, which hunts enemies down and cannot be walked
     * out of.
     */
    boolean native_lightning = false;
    /**
     * Keep the chieftain just outside the reach of active enemy towers (they throw 16 cells, the stun reaches 18) and
     * count the towers there as caught: he stuns them without taking a throw.
     */
    boolean chief_tower_standoff = true;
    /**
     * Watch how many of the enemies in reach each stun actually catches. Against an enemy who runs from the horn
     * (share below dodge_catch), count only the ones too close to get away, within dodge_core cells, in full.
     */
    boolean stun_learn = false;
    float dodge_catch = .6f;
    int dodge_core = 10;
    /** Count towers toward a stun only while the attacking army is near enough to pull them down. */
    boolean tower_stun_follow_up = true;

    /** Chieftain training starts once this many quarters stand and this much time has passed. */
    int chieftain_min_quarters = 3;
    float chieftain_time = 330f;

    /**
     * Against several enemies, every other tower covers the building nearest to each enemy in turn, facing him, and
     * one more tower is built per extra enemy: each attacks the building closest to him.
     */
    boolean multi_front_towers = true;
    /** Towers to build next to the armory, early and later. */
    int towers_early = 1;
    int towers_mid = 3;
    int towers_late = 6;
    float towers_early_time = 420f;
    float towers_mid_time = 420f;
    float towers_late_time = 720f;

    /** Warriors (as iron warrior values) needed before the first attack. */
    float attack_min_strength = 18f;
    /** How much stronger than what can defend the target the army must be before attacking. */
    float attack_ratio = 1.35f;
    /**
     * While an attack is out, warriors gathering at home march out as one group to join it once they are worth
     * reinforce_ratio of the attacking army (or at the unit cap), instead of idling until the attack ends.
     */
    boolean reinforce = true;
    float reinforce_ratio = .5f;
    /** Against several enemies, reinforce only at the unit cap: the others would walk into an emptied base. */
    boolean reinforce_multi = false;
    /** How much more an enemy manned tower counts than Combat.TOWER when judging an attack or retreat. */
    float tower_weight = 1f;
    /** Army strength that attacks regardless of the odds. */
    float attack_max_strength = 70f;
    /** At the unit cap losses are replaced for free, so attack against this much of the defense. */
    float capped_ratio = .6f;
    /** Retreat when the enemy around the army is this much stronger and the chieftain cannot stun. */
    float retreat_ratio = 1.45f;
    /** Units in the staging army sent to hunt enemy peons when the enemy army is elsewhere. */
    int raid_size = 5;
    float raid_time = 360f;

    /** Fan warriors out onto the nearest enemies when fighting, instead of sending all at the enemy's middle. */
    boolean engage_spread = true;
    /** Add the enemy's recent arming rate times the march time to the defense an attack must beat. */
    boolean project_defense = true;
    /** Turn an attack back before contact when the whole defense in view is this much stronger; 0 disables. */
    float precontact_ratio = 1.1f;
    /** Send the home army at enemy buildings going up in the base or next to our gatherers. */
    boolean strikes = true;
    /**
     * Towers to raise over the enemy's iron gatherers, escorted by the army, once it outnumbers the enemy's field army
     * by forward_ratio and not before forward_tower_time.
     */
    int forward_towers = 0;
    float forward_tower_time = 420f;
    float forward_ratio = 1.4f;
    /** Highest base threat level at which the army still escorts forward tower builders. */
    int forward_threat = 0;
    /** Highest base threat level at which a raid on enemy peons may leave. */
    int raid_threat = 0;

    /**
     * Defenders engage a threat at .8 of its strength; once engaged they hold down to .8 minus this, and once fallen
     * back they wait for .8 plus this, so the army does not run back and forth under fire.
     */
    float defend_hysteresis = .15f;
    /** Answer harassment away from the base with this many times its strength, not the whole army; 0 sends all. */
    float response_ratio = 2f;

    /**
     * Against several enemies, keep gathering away from the enemies while the base is threatened but the armory
     * itself is not: the base is hardly ever quiet, and stopping would starve the armory for good.
     */
    boolean gather_under_threat = true;
    /** Also in a 1v1: on smaller maps the fighting is at the base so often that hiding starves the armory. */
    boolean gather_threat_1v1 = false;
    /** A gatherer counts as stuck after this many round trips (at least 70 s) without its load changing; 0 = 70 s. */
    float stuck_trip_factor = 0f;

    /** Open a second armory by fresh iron once the first one's surroundings are mined out. */
    boolean expansion = true;
    /**
     * When nothing within max_armory_distance of the armory beats it clearly, look for the expansion twice as far from
     * the start, counting the walk, the delay and the exposure of the site.
     */
    boolean far_expansion = false;
    /**
     * Call back the gatherers still working for an armory that is no longer the main one: they walk ever further for
     * its mined-out surroundings while the new armory waits for hands.
     */
    boolean recall_old_gatherers = true;
    /** Expand for a smaller gain (this share of the current cost instead of three quarters) once iron is this far. */
    float desperate_expansion = .9f;
    float desperate_iron_cycle = 150f;
    /** Look twice as far for the first armory when the best site nearby costs more than this (seconds per warrior). */
    float armory_far_cost = 170f;

    /**
     * Fight raiding enemy peons with our own peons when no warriors are at hand to do it, until militia_time: peon
     * raids come before warriors do, and later wandering enemy gatherers would only draw the army about.
     */
    boolean peon_militia = true;
    float militia_time = 600f;
    /** Sparring only: send the starting peons at the enemy's peons for the first minutes, as some humans do. */
    boolean peon_rush = false;

    /**
     * In a fight, give each warrior its own target: the enemy in range with the best value times hit chance times
     * chance that nobody else's throw kills it first, instead of letting several throw at the same nearest one.
     */
    boolean micro_targets = true;
    /**
     * Order our stunned warriors again: the stun behaviour keeps them frozen, but the order replaces the stun
     * controller, which is what takes away their chance to dodge.
     */
    boolean restore_dodge = false;
    /**
     * Seconds before a warrior re-ordered out of a stun may be re-ordered again: the Expert AI waited 30 s, sweep
     * re-ordered every 5 ticks (+9 points at N=2, lab/sweep NOTES base22 vs s24-nounstun).
     */
    float restore_dodge_gap = 30f;

    /**
     * Take peons along on attacks against towers: a peon's swing always does 6 damage to a tower, eight times what an
     * iron axe does, so they pull towers down while the army holds the ground or the stun keeps the tower quiet.
     */
    boolean sappers = true;

    /**
     * When the attack would turn back from towers but the enemy army around it is beaten, hunt the enemy's peons
     * outside tower cover instead: a base that keeps its peons rebuilds its army in minutes.
     */
    boolean pillage = false;
    /**
     * Against manned towers with no strong field army about, hold just outside their reach while the chieftain's stun
     * comes back, stun them from his standoff and tear the stunned towers down with the whole army before they wake.
     */
    boolean siege = false;
    /** Give a siege up after this many seconds without a stun landing on a tower. */
    float siege_patience = 100f;

    /**
     * Learn from each attack: one that lost more units than it killed makes the next one wait for 25% more strength
     * (up to 2.5 times), one that traded well brings the bar back down.
     */
    boolean adaptive_caution = true;
    /**
     * At the unit cap waiting gains nothing: caution from past attacks eases by this factor every minute spent capped
     * at home; 1 keeps it.
     */
    float caution_decay = 1.1f;

    /**
     * While the army holds the ground by a besieged building, the sappers raise a tower in range of it and out of
     * reach of the enemy's towers, and a warrior mans it: it out-ranges every defender and keeps shelling.
     */
    boolean creep_towers = false;

    /**
     * Read what a human player cannot see: the weapons stocked in enemy armories and how far the enemy chieftain's
     * spell has recharged. Off for fair play: the AI then assumes an enemy chieftain can cast unless it saw him cast
     * within the recharge time.
     */
    boolean hidden_info = false;

    /**
     * Chickens are few and whoever hunts first gets them: up to chicken_hunters peons hunt from chicken_time on,
     * two plus one per chicken_pool_div working peons.
     */
    int chicken_hunters = 7;
    float chicken_time = 150f;
    int chicken_pool_div = 18;

    /**
     * Point each manned tower at the enemy in range worth most, as a player can: no waiting for its own scan, no
     * two towers on a doomed target, and peons pulling down our towers first.
     */
    boolean tower_fire = true;

    /**
     * Re-send gatherers whose load has not changed for a long while: the engine can keep one walking to a tree it
     * cannot reach.
     */
    boolean unstick = true;

    /**
     * Cells the chieftain keeps from the nearest enemy warrior while closing in to stun: inside his 18-cell stun
     * radius but out of throwing range, so he is not worn down before the spell is ready again. 0 walks right in.
     */
    int chief_keep_out = 11;
    /**
     * While the stun recharges, keep the chieftain this many cells from every enemy warrior (they throw 8), moving at
     * once when one comes closer; 0 leaves him in the clump. Enemies value his head highly.
     */
    int chief_safe = 0;
    /** Hit points at which the chieftain walks home to the armory. */
    int chief_flee_hp = 24;

    /**
     * Judge a threat in the base by everything within this many cells of it, not just what is inside the base: a few
     * raiders often walk ahead of the whole army, and chasing them out runs the defenders into it. 0 counts only the
     * threat itself.
     */
    int threat_look = 30;

    /**
     * Against a strong enemy (at least this share of our defenders), meet him at our buildings and towers instead of
     * walking out: whoever waits for the other wins most even fights. 0 always walks out, which tested better
     * against both rival AIs (the posted defenders bunch up for the enemy's stun and let the raiders work).
     */
    float hold_ratio = 0f;

    /**
     * Value a chieftain by what one throw does to him: he has 60 hit points and an axe takes 2, so a healthy one is
     * a poor target and a wounded one the best on the field. Otherwise he counts as a one-hit kill like a warrior, and
     * warriors are also sent after him whenever he is near.
     */
    boolean chief_per_hit = true;

    /**
     * Decoy tower sites 11-14 cells in front of our manned towers, nearer to each Hard copy than any real building,
     * steer its waves where the towers shoot them (Decoys). From decoy_time on, at most decoy_max at once, leaving
     * decoy_free_slots of the building cap for real buildings; a spot must be within decoy_margin of the distance of
     * the copy's nearest real target. decoy_cage leaves enemies standing in tower reach to the towers.
     */
    boolean decoys = false;
    /** A shepherd peon per copy draws its waves onto empty ground (Shepherd), from shepherd_time to shepherd_until. */
    boolean shepherd = true;
    /**
     * The chieftain's shred mission (Chieftain.shred): with the blast charged and more than shred_min_hp, he blasts
     * blobs of at least shred_min parked enemy warriors within shred_range cells of our armory.
     */
    boolean shred = false;
    int shred_min = 8;
    int shred_min_hp = 35;
    int shred_range = 140;
    /** The chieftain never stuns: every charge goes to shred blasts. */
    boolean shred_strict = false;
    float shepherd_time = 200f;
    float shepherd_until = 100000f;
    /**
     * Farthest a shepherd stands from the wave's leader, in cells (it must stay within 0.66 of our nearest building).
     */
    int shepherd_max_r = 22;
    /** Chebyshev cells a shepherd's spot keeps from every enemy unit (idle and walking units scan 8). */
    int shepherd_clear = 12;
    /** Seconds a shepherd waits without a spot before it goes home (large: never). */
    float shepherd_patience = 100000f;
    /** Weight of a spot's distance from the copy's own quarters and armory, beside its distance from our start. */
    float shepherd_home_weight = 0f;
    /**
     * Per-tick orders (Reflexes): restart each harvest swing right after its hit (audit A26: a viking peon then
     * hits every 15 ticks instead of 51), and cancel each stun on the tick it lands by ordering the unit again (K1).
     */
    boolean swing_restart = true;
    /**
     * Seconds a gatherer spends at the supply per load, in the gather cost model (armory site and crew split): 10 hits
     * of 51 ticks without the swing restart; 10 of 15 ticks (3 s) plus settling in with it.
     */
    float harvest_seconds = 10f;
    boolean stun_cancel = true;
    /** With stun_cancel, run only from an enemy sonic blast, not from the stun (which Reflexes cancels anyway). */
    boolean dodge_blast_only = false;
    float decoy_time = 240f;
    int decoy_max = 8;
    int decoy_free_slots = 3;
    float decoy_margin = .85f;
    boolean decoy_cage = true;

    /** Radius, in grid cells, around own buildings within which enemies count as attacking the base. */
    int base_radius = 28;

    /** Sets any field from the spec's params (gauntlet:attack_ratio=1.2,...), for tuning experiments. */
    void apply(@NonNull AiParams params) {
        decoys = params.getBoolean("decoys", decoys);
        forward_threat = params.getInt("forward_threat", forward_threat);
        raid_threat = params.getInt("raid_threat", raid_threat);
        shepherd = params.getBoolean("shepherd", shepherd);
        shred = params.getBoolean("shred", shred);
        shred_min = params.getInt("shred_min", shred_min);
        shred_min_hp = params.getInt("shred_min_hp", shred_min_hp);
        shred_range = params.getInt("shred_range", shred_range);
        shred_strict = params.getBoolean("shred_strict", shred_strict);
        shepherd_time = (float) params.getDouble("shepherd_time", shepherd_time);
        shepherd_until = (float) params.getDouble("shepherd_until", shepherd_until);
        shepherd_max_r = params.getInt("shepherd_max_r", shepherd_max_r);
        shepherd_clear = params.getInt("shepherd_clear", shepherd_clear);
        shepherd_patience = (float) params.getDouble("shepherd_patience", shepherd_patience);
        shepherd_home_weight = (float) params.getDouble("shepherd_home_weight", shepherd_home_weight);
        tower_parallel = params.getInt("tower_parallel", tower_parallel);
        focus_bonus = (float) params.getDouble("focus_bonus", focus_bonus);
        defense_radius = params.getInt("defense_radius", defense_radius);
        rock_share = (float) params.getDouble("rock_share", rock_share);
        quarters_first = params.getBoolean("quarters_first", quarters_first);
        target_defense_weight = (float) params.getDouble("target_defense_weight", target_defense_weight);
        target_home_weight = (float) params.getDouble("target_home_weight", target_home_weight);
        attack_threat_ratio = (float) params.getDouble("attack_threat_ratio", attack_threat_ratio);
        recall_ratio = (float) params.getDouble("recall_ratio", recall_ratio);
        focus_finish = params.getBoolean("focus_finish", focus_finish);
        sites_parallel = params.getInt("sites_parallel", sites_parallel);
        swing_restart = params.getBoolean("swing_restart", swing_restart);
        harvest_seconds = (float) params.getDouble("harvest_seconds", harvest_seconds);
        stun_cancel = params.getBoolean("stun_cancel", stun_cancel);
        dodge_blast_only = params.getBoolean("dodge_blast_only", dodge_blast_only);
        decoy_time = (float) params.getDouble("decoy_time", decoy_time);
        decoy_max = params.getInt("decoy_max", decoy_max);
        decoy_free_slots = params.getInt("decoy_free_slots", decoy_free_slots);
        decoy_margin = (float) params.getDouble("decoy_margin", decoy_margin);
        decoy_cage = params.getBoolean("decoy_cage", decoy_cage);
        initial_quarters = params.getInt("initial_quarters", initial_quarters);
        max_quarters = params.getInt("max_quarters", max_quarters);
        expand_time = (float) params.getDouble("expand_time", expand_time);
        max_armory_distance = params.getInt("max_armory_distance", max_armory_distance);
        armory_distance_weight = (float) params.getDouble("armory_distance_weight", armory_distance_weight);
        armory_delay_weight = (float) params.getDouble("armory_delay_weight", armory_delay_weight);
        armory_threat_weight = (float) params.getDouble("armory_threat_weight", armory_threat_weight);
        scouts = params.getInt("scouts", scouts);
        armory_builders = params.getInt("armory_builders", armory_builders);
        quarters_builders = params.getInt("quarters_builders", quarters_builders);
        tower_builders = params.getInt("tower_builders", tower_builders);
        quarters_before_armory = params.getInt("quarters_before_armory", quarters_before_armory);
        opening_near_start = params.getBoolean("opening_near_start", opening_near_start);
        rush_response = params.getBoolean("rush_response", rush_response);
        rush_quarters = params.getInt("rush_quarters", rush_quarters);
        rush_seconds = (float) params.getDouble("rush_seconds", rush_seconds);
        pressure_response = params.getBoolean("pressure_response", pressure_response);
        pressure_time = (float) params.getDouble("pressure_time", pressure_time);
        hold_early = params.getInt("hold_early", hold_early);
        hold_mid = params.getInt("hold_mid", hold_mid);
        hold_late = params.getInt("hold_late", hold_late);
        hold_mid_time = (float) params.getDouble("hold_mid_time", hold_mid_time);
        hold_chieftain = params.getInt("hold_chieftain", hold_chieftain);
        stun_patience = params.getBoolean("stun_patience", stun_patience);
        exploit_stun = params.getBoolean("exploit_stun", exploit_stun);
        charge_mixed = params.getBoolean("charge_mixed", charge_mixed);
        dodge_stun = params.getBoolean("dodge_stun", dodge_stun);
        blast = params.getBoolean("blast", blast);
        blast_ratio = (float) params.getDouble("blast_ratio", blast_ratio);
        blast_min = (float) params.getDouble("blast_min", blast_min);
        blast_defense = params.getBoolean("blast_defense", blast_defense);
        blast_play_time = (float) params.getDouble("blast_play_time", blast_play_time);
        blast_save = params.getBoolean("blast_save", blast_save);
        hunt_caster = params.getBoolean("hunt_caster", hunt_caster);
        hunt_caster_cells = params.getInt("hunt_caster_cells", hunt_caster_cells);
        defend_exploit_stun = params.getBoolean("defend_exploit_stun", defend_exploit_stun);
        dodge_fog = params.getBoolean("dodge_fog", dodge_fog);
        native_lightning = params.getBoolean("native_lightning", native_lightning);
        chief_tower_standoff = params.getBoolean("chief_tower_standoff", chief_tower_standoff);
        stun_learn = params.getBoolean("stun_learn", stun_learn);
        dodge_catch = (float) params.getDouble("dodge_catch", dodge_catch);
        dodge_core = params.getInt("dodge_core", dodge_core);
        tower_stun_follow_up = params.getBoolean("tower_stun_follow_up", tower_stun_follow_up);
        chieftain_min_quarters = params.getInt("chieftain_min_quarters", chieftain_min_quarters);
        chieftain_time = (float) params.getDouble("chieftain_time", chieftain_time);
        multi_front_towers = params.getBoolean("multi_front_towers", multi_front_towers);
        towers_early = params.getInt("towers_early", towers_early);
        towers_mid = params.getInt("towers_mid", towers_mid);
        towers_late = params.getInt("towers_late", towers_late);
        towers_early_time = (float) params.getDouble("towers_early_time", towers_early_time);
        towers_mid_time = (float) params.getDouble("towers_mid_time", towers_mid_time);
        towers_late_time = (float) params.getDouble("towers_late_time", towers_late_time);
        attack_min_strength = (float) params.getDouble("attack_min_strength", attack_min_strength);
        attack_ratio = (float) params.getDouble("attack_ratio", attack_ratio);
        reinforce = params.getBoolean("reinforce", reinforce);
        reinforce_ratio = (float) params.getDouble("reinforce_ratio", reinforce_ratio);
        reinforce_multi = params.getBoolean("reinforce_multi", reinforce_multi);
        tower_weight = (float) params.getDouble("tower_weight", tower_weight);
        attack_max_strength = (float) params.getDouble("attack_max_strength", attack_max_strength);
        capped_ratio = (float) params.getDouble("capped_ratio", capped_ratio);
        retreat_ratio = (float) params.getDouble("retreat_ratio", retreat_ratio);
        raid_size = params.getInt("raid_size", raid_size);
        raid_time = (float) params.getDouble("raid_time", raid_time);
        engage_spread = params.getBoolean("engage_spread", engage_spread);
        project_defense = params.getBoolean("project_defense", project_defense);
        precontact_ratio = (float) params.getDouble("precontact_ratio", precontact_ratio);
        strikes = params.getBoolean("strikes", strikes);
        forward_towers = params.getInt("forward_towers", forward_towers);
        forward_tower_time = (float) params.getDouble("forward_tower_time", forward_tower_time);
        forward_ratio = (float) params.getDouble("forward_ratio", forward_ratio);
        defend_hysteresis = (float) params.getDouble("defend_hysteresis", defend_hysteresis);
        response_ratio = (float) params.getDouble("response_ratio", response_ratio);
        gather_under_threat = params.getBoolean("gather_under_threat", gather_under_threat);
        gather_threat_1v1 = params.getBoolean("gather_threat_1v1", gather_threat_1v1);
        stuck_trip_factor = (float) params.getDouble("stuck_trip_factor", stuck_trip_factor);
        expansion = params.getBoolean("expansion", expansion);
        far_expansion = params.getBoolean("far_expansion", far_expansion);
        recall_old_gatherers = params.getBoolean("recall_old_gatherers", recall_old_gatherers);
        desperate_expansion = (float) params.getDouble("desperate_expansion", desperate_expansion);
        desperate_iron_cycle = (float) params.getDouble("desperate_iron_cycle", desperate_iron_cycle);
        armory_far_cost = (float) params.getDouble("armory_far_cost", armory_far_cost);
        peon_militia = params.getBoolean("peon_militia", peon_militia);
        militia_time = (float) params.getDouble("militia_time", militia_time);
        peon_rush = params.getBoolean("peon_rush", peon_rush);
        micro_targets = params.getBoolean("micro_targets", micro_targets);
        restore_dodge = params.getBoolean("restore_dodge", restore_dodge);
        restore_dodge_gap = (float) params.getDouble("restore_dodge_gap", restore_dodge_gap);
        sappers = params.getBoolean("sappers", sappers);
        pillage = params.getBoolean("pillage", pillage);
        siege = params.getBoolean("siege", siege);
        siege_patience = (float) params.getDouble("siege_patience", siege_patience);
        adaptive_caution = params.getBoolean("adaptive_caution", adaptive_caution);
        caution_decay = (float) params.getDouble("caution_decay", caution_decay);
        creep_towers = params.getBoolean("creep_towers", creep_towers);
        hidden_info = params.getBoolean("hidden_info", hidden_info);
        chicken_hunters = params.getInt("chicken_hunters", chicken_hunters);
        chicken_time = (float) params.getDouble("chicken_time", chicken_time);
        chicken_pool_div = params.getInt("chicken_pool_div", chicken_pool_div);
        tower_fire = params.getBoolean("tower_fire", tower_fire);
        unstick = params.getBoolean("unstick", unstick);
        chief_keep_out = params.getInt("chief_keep_out", chief_keep_out);
        chief_safe = params.getInt("chief_safe", chief_safe);
        chief_flee_hp = params.getInt("chief_flee_hp", chief_flee_hp);
        threat_look = params.getInt("threat_look", threat_look);
        hold_ratio = (float) params.getDouble("hold_ratio", hold_ratio);
        chief_per_hit = params.getBoolean("chief_per_hit", chief_per_hit);
        base_radius = params.getInt("base_radius", base_radius);
    }

    /** The strategy for a game on a map of the given size against the given number of enemy players. */
    static @NonNull Strategy forGame(int map_size, int enemies) {
        Strategy strategy = forMapSize(map_size);
        if (enemies > 1) {
            // Every enemy sends his waves at our nearest building: towers early, and many of them, hold them all,
            // and the chieftain's stun is wanted sooner.
            strategy.towers_early = 3;
            strategy.towers_early_time = Math.min(strategy.towers_early_time, 200f);
            strategy.towers_mid = 6;
            strategy.towers_mid_time = Math.min(strategy.towers_mid_time, 330f);
            strategy.towers_late = 14;
            strategy.towers_late_time = 600f;
            strategy.chieftain_time = Math.min(strategy.chieftain_time, 240f);
            // Against many Hard copies (lab/gauntlet/NOTES.md, 2026-09-28): shepherds leash their waves, so a target's
            // defense is what stands near it (shepatk-vs7-hn 35 vs 23, shepatk-vs8-hn 12 vs 6), and attacks are
            // reinforced (rmulti-vs7-hn 27 vs 19).
            strategy.defense_radius = 60;
            strategy.project_defense = false;
            strategy.reinforce_multi = true;
        }
        return strategy;
    }

    static @NonNull Strategy forMapSize(int map_size) {
        Strategy strategy = new Strategy();
        switch (map_size) {
            case Game.SIZE_SMALL, Game.SIZE_MEDIUM -> {
                // Armies arrive twice as fast, so rushes pay: a safer opening with the armory after two quarters.
                strategy.initial_quarters = 2;
                strategy.quarters_before_armory = 2;
                strategy.hold_mid = 7;
                strategy.max_armory_distance = 230;
                strategy.armory_distance_weight = .06f;
                strategy.armory_delay_weight = .6f;
                strategy.armory_threat_weight = 90f;
                strategy.hold_early = 2;
                strategy.expand_time = 240f;
                strategy.towers_early_time = 150f;
                strategy.attack_min_strength = 12f;
                strategy.chieftain_time = 300f;
                // The fighting reaches the base early and keeps coming back: a second armory only splits the
                // economy just as it starts, while two more towers hold the base (medium, vs both rival AIs on two
                // seed sets each: +.4 to +.7).
                strategy.expansion = false;
                strategy.towers_mid = 4;
                strategy.towers_late = 8;
            }
            case Game.SIZE_ENORMOUS -> {
                strategy.max_armory_distance = 700;
                strategy.expand_time = 360f;
            }
            default -> {
            }
        }
        return strategy;
    }
}
