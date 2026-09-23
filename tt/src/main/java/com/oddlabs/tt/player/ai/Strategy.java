package com.oddlabs.tt.player.ai;

import com.oddlabs.matchmaking.Game;
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

    /** Open a second armory by fresh iron once the first one's surroundings are mined out. */
    boolean expansion = true;

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
    boolean restore_dodge = true;

    /**
     * Take peons along on attacks against towers: a peon's swing always does 6 damage to a tower, eight times what an
     * iron axe does, so they pull towers down while the army holds the ground or the stun keeps the tower quiet.
     */
    boolean sappers = true;

    /** Radius, in grid cells, around own buildings within which enemies count as attacking the base. */
    int base_radius = 28;

    /**
     * Sets fields by name from "name=value" pairs, for tuning experiments.
     */
    void override(@NonNull String assignments) {
        for (String pair : assignments.split(",")) {
            if (pair.isBlank())
                continue;
            String[] kv = pair.split("=");
            try {
                java.lang.reflect.Field f = Strategy.class.getDeclaredField(kv[0].trim());
                String v = kv[1].trim();
                if (f.getType() == int.class)
                    f.setInt(this, Integer.parseInt(v));
                else if (f.getType() == float.class)
                    f.setFloat(this, Float.parseFloat(v));
                else if (f.getType() == boolean.class)
                    f.setBoolean(this, Boolean.parseBoolean(v));
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("bad strategy override " + pair, e);
            }
        }
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
