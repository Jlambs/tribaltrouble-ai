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
    /** Cells from the building it covers that a front tower (one facing each enemy) stands. */
    int front_tower_min = 7;
    int front_tower_max = 15;
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
     * Raze each copy's quarters and move on: without quarters it can train no chieftain, and from wave size 20 no
     * wave leaves without one (AdvancedAI); its armory, army and peons are left until every copy is quarterless.
     */
    boolean gate_freeze = false;
    /**
     * Attack targets score meters from the army, plus target_defense_weight meters per unit of the defense expected
     * there, plus target_home_weight times the meters from our staging point (keeps the campaign near home).
     */
    float target_defense_weight = 8f;
    float target_home_weight = 0f;
    /** Meters taken off a target's score per unit of its owner's strength standing in our base. */
    float target_threat_weight = 0f;
    /** Towers 10-13 cells from their neighbours (SitePlanner.findTowerSite) instead of spread 16+ apart. */
    boolean tower_mutual = false;
    /**
     * Sniper towers by idle enemy blobs of at least snipe_min within snipe_range cells of the base
     * (Economy.planSniper).
     */
    boolean snipers = false;
    /**
     * Re-order a tower on the tick its garrison's stun comes on top, so it keeps throwing (Reflexes). vs hard*8:
     * tunstun-vs8-hv 23 vs 19 (seeds 1..100), tunstun-vs8-hv-b 52 vs 43 of 200 (fresh seeds 201..400); 22 games
     * gained, 9 lost.
     */
    boolean tower_unstun = true;
    /** Skip, for 10 minutes, a target whose attack stalled (Military.stalled_targets). */
    boolean skip_stalled = true;
    /** Owner-aware attack gate against several enemies (Military.defenseFor) and a local chieftain malus. */
    boolean gate_owner = false;
    /** Reinforce the attack under base threat while the threat is worth less than this share of all our warriors. */
    float reinforce_threat_ratio = 0f;
    /** Finish a raided copy (sites, chieftain, units within finish_range cells) before choosing another. */
    boolean finish_copies = false;
    int finish_range = 90;
    /**
     * finish_lean (with finish_copies): skip copies that already meet the collapse rule, stop hunting a copy's units
     * once it has finish_units or fewer besides its chieftain (-1 = off), skip remnants stronger than finish_ratio x
     * our
     * army (0 = off) and targets guarded by other copies' awake warriors (Military.finishTarget).
     */
    boolean finish_skip_out = false;
    int finish_units = -1;
    float finish_ratio = 0f;
    /**
     * chief_hunt: a squad of chief_hunt_size iron warriors kills the chieftain of a copy with no finished quarters or
     * armory and at most 8 other units (it is then out); hunt_sites: its quarters/armory sites too. Target within
     * chief_hunt_range cells, at most chief_hunt_escort enemy warriors within 15 cells, no enemy tower within 22; the
     * squad gives up after chief_hunt_time s or when outmatched (Military.considerChase). Six never set off a Viking
     * blast (7 of our selectables within 18 cells).
     */
    boolean chief_hunt = false;
    boolean hunt_sites = false;
    int chief_hunt_size = 6;
    int chief_hunt_range = 150;
    int chief_hunt_escort = 3;
    float chief_hunt_time = 90f;
    /**
     * weapon_sync: an armory takes a weapon's cost when the weapon is done, clamped at zero, so weapons done on the
     * same tick share what they have in common. While the main armory can forge a rubber axe and the iron it shares
     * with an iron axe is short, the queue that would finish first is paused (its orders cancelled, which keeps its
     * progress) and resumed so both finish on the same tick (Economy.weaponSync). weapon_sync_three: the rock axe joins
     * when rock is short too.
     */
    boolean weapon_sync = false;
    boolean weapon_sync_three = true;
    /** Keep the tower target under the building cap; front towers add at most front_tower_bonus_max. */
    boolean tower_cap = false;
    int front_tower_bonus_max = 100;
    /** Shepherds only for copies whose wave origin is within this many cells of our start. */
    int shepherd_range = 100000;
    /**
     * Empty a quarters or armory below evac_hp of its hit points with evac_min enemy warriors by it (Economy.evacuate).
     */
    boolean evacuate = false;
    /** Gatherers skip supplies an idle enemy can see (Economy.seenByParked). */
    boolean gather_avoid_parked = false;
    /** Lure-kiting (Lures): up to lure_max peons pull blobs of lure_min+ idle enemies into tower reach. */
    boolean lure = false;
    /** Rock filler (Economy.computeGatherTargets) while iron starves the armory. */
    boolean rock_surge = false;
    /** Decoy sites near a copy's wave origin when its shepherd finds no spot (Decoys.placeHome). */
    boolean site_shepherd = false;
    /**
     * The armory site's exposure weight grows by 0.75 per enemy beyond the first, up to this many (SitePlanner): past
     * N=6 it kept the first armory from the good iron (vs hard*9 atcap5-vs9-hv-b W 15 vs 11 of 200, elim +.024 z 2.1;
     * N=10 with the same weight via armory_threat_weight=37: W 6 vs 2 of 200; N=8 W 65 vs 65).
     */
    int armory_threat_cap = 5;
    /** Tower targets out to the garrison's full reach (15.9 cells) instead of 15 (Military.towerReach2). */
    boolean tower_full_reach = false;
    /** Retarget towers on the tick their target dies (Military.towerReflex). */
    boolean tower_reflex = true;
    /** Retarget warriors on the tick their target dies (Military.armyReflex). */
    boolean army_reflex = false;
    /**
     * Tower micro, on by default (vs hard*11, tow2-vs11-hv: W 4 vs 0 of 200, lsr20 +.52 z 4.9, kd +.073 z 2.7): reach
     * from the garrison's entry cell (tower_gunner_reach), the next target queued while a long axe flies
     * (tower_prequeue), retarget on the kill tick (tower_reflex), attackers of the tower first (tower_self_first),
     * chicken gunners whenever a tower is quiet (chicken_gunners).
     */
    boolean tower_gunner_reach = true;
    /** Queue a tower's next target when it starts a throw its target cannot survive (Military.prequeue). */
    boolean tower_prequeue = true;
    /** Towers shoot their own attackers first (Military.towerSelfFactor). */
    boolean tower_self_first = true;
    /** Swap iron gunners for chicken warriors whenever a tower is quiet, not only when the base is. */
    boolean chicken_gunners = true;
    /** The attack's stall clock runs only while calm; a reachable stall re-targets instead of retreating. */
    boolean stall_calm = true;
    /** Top up the chieftain's training quarters under threat too, and keep it full at the unit cap. */
    boolean chief_topup_any = false;
    /**
     * Train the chieftain in a quarters near the armory that no enemy warrior stands within 30 cells of (Chieftain),
     * and top it up only with peons sent to it and walkers within 40 cells (Economy): the start quarters, 57 cells from
     * the armory in a median game, pulled armory-bound newborns across the base (lab/gauntlet audit, camp-mf-vs11-hv).
     */
    boolean chief_trainer_near = false;
    /**
     * While the base threat is at the main armory and it has no ore to forge, spare peons go to a quiet armory or
     * quarters instead of into it, and quarters keep theirs (Economy): peons piled into an armory that is about to be
     * razed vanish with it (LandBuilding.removeDying; ~52 units per razing in camp-mf-vs11-hv).
     */
    boolean danger_refuge = false;
    /**
     * Look for an expansion armory under threat too, at a site with no enemy within 30 cells nor along the way
     * (Economy.considerExpansion): from 10 minutes parked blobs keep the threat above 0, so after the expansion falls
     * no new armory was ever placed (camp-mf-vs11-hv: 0 in 32 windows of a median 358 s).
     */
    boolean expand_under_threat = false;
    /**
     * With the base under heavy threat, a muster that timed out without gathering launches only if the army at the
     * staging point outweighs the threat; the small-threat attack test counts only the army near staging (Military).
     */
    boolean launch_recheck = false;
    /** Seconds before the next attack after the army was called home (20 after any other attack end). */
    float recall_cooldown = 20f;
    /**
     * The worn retreat (Military.attack): the army turns back once it is worth less than worn_ratio of worn_basis and
     * the enemy around it more than the army. 0: the launch strength plus every reinforcement that ever joined (1.31 x
     * the army's own peak in a median attack); 1: the peak of the army's strength since the launch; 2: its peak over
     * the last worn_window seconds.
     */
    int worn_basis = 0;
    float worn_window = 120f;
    float worn_ratio = .2f;
    /**
     * Enemy stun fear (Military.enemyThreatReady): an enemy chieftain counts as ready to stun enemy_spell_recharge s
     * after he was seen casting, and with enemy_first_seen only that long after he was first seen (newborns start with
     * no charge); ready ones near a fight multiply its enemy by enemy_stun_mult, and at 1 or less no longer veto a
     * charge on stunned enemies. Our own stun timing keeps the 40 s (Chieftain.shouldStun).
     */
    float enemy_stun_mult = 1.5f;
    float enemy_spell_recharge = 40f;
    boolean enemy_first_seen = false;
    /**
     * The outmatched retreat weighs the whole attacking army, not only the part within 18 cells of its centre, when
     * that part is less than half of it (a split army).
     */
    boolean retreat_split_guard = false;
    /**
     * Parked enemies (idle, scanning an 8-cell square) count as threats only within this many cells (Chebyshev):
     * parked_scan_econ in the economy's threat tests (Military.threatNearEcon), parked_scan_threat around our
     * buildings, sites and peons in the base threat itself (Military.updateThreat). 0: they count like awake ones.
     */
    int parked_scan_econ = 0;
    int parked_scan_threat = 0;
    /**
     * The attack holds on high ground for an enemy group only when (1) the group itself came 2 cells nearer over the
     * last 2 s and fewer than half of it is parked; 0: whenever the distance to it shrinks, our own march included.
     */
    int hold_closing = 0;
    /** hold_ratio's posts against several enemies too (Military.holdAtPost). */
    boolean hold_multi = false;
    /**
     * On a retreat, warriors in a fight or with an enemy warrior within 9 cells finish it first (Military). Vs hard*11
     * elim +.004 (z 2.0), kd +.018 (rearg-vs11-hv); in the base2 stack.
     */
    boolean retreat_rearguard = true;
    /** No early-rush alarm once we have had an armory (Economy.checkRush): it fired after our last armory fell. */
    boolean rush_opening_only = false;
    /** Re-targets during an attack go by walking distance from the army, not straight-line distance (Military). */
    boolean target_path = false;
    /**
     * Steadier defense (Military.defend): the chieftain's stun counts only when he is within 30 cells of the threat,
     * the armory rule releases beyond 17 cells (engages at 14), and an engage holds at least 3 s.
     */
    boolean defend_stable = false;
    /**
     * No new tower within 25 cells of a building of ours razed in the last 90 s, and no new builders to a tower site
     * with an awake enemy warrior within 12 cells (Economy): 61 % of such sites were razed, 13 % of the others.
     */
    boolean tower_cooldown = false;
    /**
     * The attack's stall clock and stall test count armed enemies and towers only, not peons (Military.attack): in the
     * N=11 draw s98 the army farmed the last copy's peons for 5 hours 150 m from its building, and every peon fight
     * reset the stall clock, so the stall rule never fired (late/endgame.md).
     */
    boolean stall_peons = false;
    /** Gatherers per ore node before the next node is preferred, and the metres a gatherer already there costs. */
    int ore_load = 3;
    float ore_load_penalty = 6f;
    /** Rally point of every quarters on the primary armory (Economy.choosePrimaryArmory). */
    boolean quarters_rally = false;
    /**
     * Rock stream from measured yields (Economy.computeGatherTargets): from rock_stream_time, above rock_stream_iron_s.
     */
    boolean rock_stream = false;
    /** Hunted peons run for cover before the hunter is in range (Dodges). */
    boolean peon_dodge = false;
    /** Our chieftain walks away from enemies hunting him, towards our towers (Dodges). */
    boolean chief_dodge = false;
    /**
     * Gunners enter their tower from the threat side (Military.frontCell): a garrison throws from its entry cell. Vs
     * hard*11 elim +.025 (z 2.1, front-vs11-hv); in the stack with tower_reaim and tower_prequeue_any vs hard*8 on
     * fresh seeds W 111 vs 99 of 200 (full-vs8-hv-b).
     */
    boolean tower_front_entry = true;
    /** Reinforcements head for the army's march waypoint, join near any attacker, and are waited for (Military). */
    boolean reinforce_intercept = false;
    /** Shepherds hold their spot (flee at 9 cells, not 12) while their copy's launch is imminent (Shepherd). */
    boolean shepherd_hold = false;
    /**
     * Quiet towers re-enter from the side of idle enemies out of their reach (Military.reaimTowers). With
     * tower_prequeue_any vs hard*11: elim +.026 (z 2.2), W 7 vs 3 (towmicro2-vs11-hv).
     */
    boolean tower_reaim = true;
    /** Queue the next tower target for any hit chance, not only sure hits (Military.prequeue). */
    boolean tower_prequeue_any = true;
    float rock_stream_time = 540f;
    float rock_stream_iron_s = 70f;
    int rock_stream_max = 30;
    int site_max = 3;
    int rock_filler_div = 10;
    int rock_filler_min_workers = 14;
    int rock_filler_stock = 20;
    int lure_max = 2;
    int lure_min = 3;
    int lure_range = 45;
    float lure_time = 420f;
    /** From tower_parallel_late_time on: tower projects and placed sites at a time (the siege razes towers). */
    int tower_parallel_late = 1;
    int sites_parallel_late = 2;
    float tower_parallel_late_time = 600f;
    float evac_hp = .6f;
    int evac_min = 3;
    int snipe_min = 6;
    int snipe_range = 45;
    /**
     * Attack even with the base threatened, when the enemies in the base are worth less than this share of the army.
     */
    float attack_threat_ratio = 0f;
    /**
     * An attack is called home when the enemies in the base beat the home defense and this share of the attack. 99:
     * never; after the first recall no copy was ever put out (audit of camp-mf-vs11-hv), and vs hard*11 never recalling
     * gave elim +.008 / +.020 on seeds 1..200 / 201..400 (recall99-vs11-hv, -b), neutral at N=8 and 1v1.
     */
    float recall_ratio = 99f;
    /** Strength (iron warriors) kept at home when the army attacks or reinforces. */
    float home_guard = 0f;
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
    /**
     * hold_mid 10 (was 14): peons wait in quarters while the armory has ore for them; vs hard*11 elim +.013 / +.030
     * (hm10-vs11-hv, -b), W 16 vs 11 over 400; N=8 neutral (W 114 vs 111).
     */
    int hold_mid = 10;
    int hold_late = 8;
    float hold_mid_time = 240f;
    /**
     * While the main armory could forge at least hold_backlog weapons (0 = off), quarters hold only hold_early: a held
     * peon above 4 buys ~8 peons per 1000 s, a worker with ore ~12.5 weapons (Economy.holdFor). Off again once 1 or
     * fewer
     * can be forged and 30 s have passed; hold_backlog_until > 0 limits it to the early game.
     */
    int hold_backlog = 0;
    float hold_backlog_until = 0f;
    /**
     * veto_resite (s, 0 = off; late/spec S1): from veto_resite_time, a tower project that projectMayStart has vetoed
     * for a threat near its site this long moves to the nearest site with no threat within veto_resite_clear cells,
     * or is dropped and tower planning pauses for veto_resite s (Economy.manageProjects): one vetoed project stopped
     * all tower planning for 225-794 s in 14 of 16 logged N=12 games while the standing towers fell.
     */
    // Adopted 20 s (2026-09-29): survival up at every N (surv60 +1.0 to +1.9 min, z 2.2-4.4; towers at 20 min +1.3 to
    // +1.8, z 6-8; N=11 W 15 -> 19; veto-resite-vs11/12/13-hv, -vs12-hv-b).
    float veto_resite = 20f;
    float veto_resite_time = 600f;
    int veto_resite_clear = 20;
    /** The same for quarters projects (the second arm of veto_resite). */
    boolean veto_resite_quarters = false;
    /**
     * bank_guard (late/spec S2): from bank_guard_time the main armory keeps only the workers its measured iron income
     * and stock can keep forging (bank_min once it cannot forge for bank_noforge_s); the rest wait in the quarters
     * farthest from the threat and come out for builders or when the armory has room again (Economy.guardBank): 105
     * units per game vanish in razed buildings by 20 min at N=12, 42 per armory razing.
     */
    boolean bank_guard = false;
    float bank_guard_time = 600f;
    int bank_min = 6;
    float bank_margin = 1.5f;
    float bank_noforge_s = 20f;
    /** Most peons bank_guard parks in one quarters above its hold. */
    int bank_reserve_max = 60;
    /**
     * wood_reach (cells, 0 = off; late/spec S3): from wood_reach_time, when the main armory's 60-cell tree ring is
     * exhausted (tree cycle >= 90 s) or a 60-cell tree search finds nothing, trees up to this far are gathered
     * (Economy.pickSupply): the wood lock that left ~200 peons idle in the armory in s63, s60 and s315.
     */
    int wood_reach = 0;
    float wood_reach_time = 2400f;
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
     * Fight raiding enemy peons with our own peons when no warriors are at hand to do it, until militia_time. Off:
     * neighbouring copies' gatherers work near our start and passed for raiders, so the militia sent most of the
     * starting peons after single enemy peons, again and again (vs hard*11 militiaoff-vs11-hv elim +.051, z 4.1, lsr10
     * +.26, z 5.8; 1v1 duel-new-hv 100/100). Hard copies never raid with peons.
     */
    boolean peon_militia = false;
    float militia_time = 600f;
    /** Sparring only: send the starting peons at the enemy's peons for the first minutes, as some humans do. */
    boolean peon_rush = false;
    /**
     * The freeze opening (Freeze; archaeology A1, re-scoped from the freeze strike of NOTES 2026-09-28 for N >= 11):
     * at the start freeze_squad starting peons walk to the copy with the least walking time, if it is at most
     * freeze_eta seconds of peon walk away (walking distance, so the rule is inert where copies start far apart), and
     * kill its peons before its first quarters stands, which puts it out (no units, no finished quarters). If the
     * quarters stands first, the squad waits outside its defense circle for the armory site and kills its builders,
     * which freezes the copy (never touching the site); freeze_raze then stays to raze the frozen quarters.
     */
    boolean freeze_open = false;
    int freeze_squad = 10;
    float freeze_eta = 40f;
    boolean freeze_raze = false;
    /**
     * A frozen copy stops counting as frozen (in the attack target's choice) once its frozen armory site is gone or it
     * has a finished armory: off, the frozen state never cleared.
     */
    boolean freeze_unfreeze = false;
    /**
     * Path (c): once staged, the squad no longer walks back to the stage point every 6 s but fights the copy's units
     * within 6 cells of it, keeping out of the 30 m defense circle of its finished quarters or armory.
     */
    boolean freeze_fight = false;
    /**
     * After a path-(a) out our first armory moves up the build order to right after the first quarters, and the
     * returning squad builds its site (smoke: our first armory came 64-102 s later with the strike than without).
     */
    boolean freeze_armory_push = false;
    /**
     * After a path-(a) out the squad strikes once more: the living copy with the least walking time from it, if at
     * most freeze_eta seconds away and its quarters is not finished (that strike gives up when its quarters stands).
     */
    boolean freeze_retarget = false;
    /**
     * The attack target's choice leaves frozen copies (Freeze) until no other copy is a candidate: a frozen copy never
     * launches a wave, so its quarters is worth nothing to our survival, while it scores as the easiest target (no
     * priority, no defense) and took our first attack in 35 of 38 N=13 games (audit13 frozen_last).
     */
    boolean frozen_last = false;
    /**
     * The freeze opening strikes this many copies at once, the nearest ones first: each strike after the first takes
     * freeze_squad2 peons (0: freeze_squad) at a copy at most freeze_eta2 seconds of peon walk away (0: freeze_eta),
     * and every strike leaves at least freeze_keep starting peons at home.
     */
    int freeze_targets = 1;
    int freeze_squad2 = 0;
    float freeze_eta2 = 0f;
    int freeze_keep = 1;

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
    /**
     * Shepherds from 120 s (was 200): vs hard*11 elim +.048 / +.024 on seeds 1..200 / 201..400, W 28 vs 19 over 400,
     * lsr15 +.16 / +.19 (st120b2-vs11-hv, -b); N=8 +.023 (W 118 vs 115). 90 s: same survival, fewer outs; 150 s: less.
     */
    float shepherd_time = 120f;
    float shepherd_until = 100000f;
    /**
     * Farthest a shepherd stands from the wave's leader, in cells (it must stay within 0.66 of our nearest building).
     */
    int shepherd_max_r = 22;
    /** Chebyshev cells a shepherd's spot keeps from every enemy unit (idle and walking units scan 8). */
    int shepherd_clear = 12;
    /**
     * shepherd_lead (seconds, 0 = off; maxn K4): until a copy's first launch, and while it has no idle warrior, its
     * shepherd is recruited only once it would reach its spot shepherd_lead_margin s before the copy's armory time
     * plus shepherd_lead (walking shepherd_speed cells/s, plus 5 s), never before shepherd_time. Flocks then watch for
     * the copies' armories from 90 s, and far copies are tended first (Shepherd.tend).
     */
    float shepherd_lead = 0f;
    float shepherd_lead_margin = 15f;
    /** Cells per second a shepherd walks, for shepherd_lead (2.1 until the K2 "at spot" logs measure it). */
    float shepherd_speed = 2.1f;
    /**
     * A gunner with no enemy warrior within 45 cells of its tower enters from the side of the living copies' mean
     * start blended with outward from our core, not from the side of the nearest start (Military.frontCell; maxn K3).
     */
    boolean tower_face_live = false;
    /** Armory and quarters towers face the living copies' mean start, recomputed at every plan (Economy; K3). */
    boolean tower_face_place = false;
    /** Armory towers anchor on the home armory (the finished armory nearest our start), not the primary (K8). */
    boolean tower_home_anchor = false;
    /** Every third tower covers the most exposed quarters; false: it anchors on the home armory (K8). */
    boolean tower_q_anchor = true;
    /**
     * The copies odd front towers face in turn (Economy.enemyFront; maxn K9): 0 in slot order, 1 farthest start first,
     * 2 most base-bound waves first (Shepherd), ties farthest first.
     */
    int front_order = 0;
    /** Finished quarters needed beside a finished armory before towers are planned (Economy; archaeology A3). */
    int tower_min_quarters = 2;
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
    boolean dodge_blast_only = true;
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
        shepherd_lead = (float) params.getDouble("shepherd_lead", shepherd_lead);
        shepherd_lead_margin = (float) params.getDouble("shepherd_lead_margin", shepherd_lead_margin);
        shepherd_speed = (float) params.getDouble("shepherd_speed", shepherd_speed);
        tower_face_live = params.getBoolean("tower_face_live", tower_face_live);
        tower_face_place = params.getBoolean("tower_face_place", tower_face_place);
        tower_home_anchor = params.getBoolean("tower_home_anchor", tower_home_anchor);
        tower_q_anchor = params.getBoolean("tower_q_anchor", tower_q_anchor);
        front_order = params.getInt("front_order", front_order);
        tower_min_quarters = params.getInt("tower_min_quarters", tower_min_quarters);
        shepherd_patience = (float) params.getDouble("shepherd_patience", shepherd_patience);
        shepherd_home_weight = (float) params.getDouble("shepherd_home_weight", shepherd_home_weight);
        tower_parallel = params.getInt("tower_parallel", tower_parallel);
        front_tower_min = params.getInt("front_tower_min", front_tower_min);
        front_tower_max = params.getInt("front_tower_max", front_tower_max);
        focus_bonus = (float) params.getDouble("focus_bonus", focus_bonus);
        defense_radius = params.getInt("defense_radius", defense_radius);
        rock_share = (float) params.getDouble("rock_share", rock_share);
        quarters_first = params.getBoolean("quarters_first", quarters_first);
        gate_freeze = params.getBoolean("gate_freeze", gate_freeze);
        target_defense_weight = (float) params.getDouble("target_defense_weight", target_defense_weight);
        target_home_weight = (float) params.getDouble("target_home_weight", target_home_weight);
        target_threat_weight = (float) params.getDouble("target_threat_weight", target_threat_weight);
        tower_mutual = params.getBoolean("tower_mutual", tower_mutual);
        snipers = params.getBoolean("snipers", snipers);
        tower_unstun = params.getBoolean("tower_unstun", tower_unstun);
        skip_stalled = params.getBoolean("skip_stalled", skip_stalled);
        gate_owner = params.getBoolean("gate_owner", gate_owner);
        reinforce_threat_ratio = (float) params.getDouble("reinforce_threat_ratio", reinforce_threat_ratio);
        finish_copies = params.getBoolean("finish_copies", finish_copies);
        finish_skip_out = params.getBoolean("finish_skip_out", finish_skip_out);
        finish_units = params.getInt("finish_units", finish_units);
        finish_ratio = (float) params.getDouble("finish_ratio", finish_ratio);
        chief_hunt = params.getBoolean("chief_hunt", chief_hunt);
        hunt_sites = params.getBoolean("hunt_sites", hunt_sites);
        chief_hunt_size = params.getInt("chief_hunt_size", chief_hunt_size);
        chief_hunt_range = params.getInt("chief_hunt_range", chief_hunt_range);
        chief_hunt_escort = params.getInt("chief_hunt_escort", chief_hunt_escort);
        chief_hunt_time = (float) params.getDouble("chief_hunt_time", chief_hunt_time);
        weapon_sync = params.getBoolean("weapon_sync", weapon_sync);
        weapon_sync_three = params.getBoolean("weapon_sync_three", weapon_sync_three);
        finish_range = params.getInt("finish_range", finish_range);
        tower_cap = params.getBoolean("tower_cap", tower_cap);
        front_tower_bonus_max = params.getInt("front_tower_bonus_max", front_tower_bonus_max);
        shepherd_range = params.getInt("shepherd_range", shepherd_range);
        evacuate = params.getBoolean("evacuate", evacuate);
        gather_avoid_parked = params.getBoolean("gather_avoid_parked", gather_avoid_parked);
        lure = params.getBoolean("lure", lure);
        rock_surge = params.getBoolean("rock_surge", rock_surge);
        site_shepherd = params.getBoolean("site_shepherd", site_shepherd);
        armory_threat_cap = params.getInt("armory_threat_cap", armory_threat_cap);
        tower_full_reach = params.getBoolean("tower_full_reach", tower_full_reach);
        tower_reflex = params.getBoolean("tower_reflex", tower_reflex);
        army_reflex = params.getBoolean("army_reflex", army_reflex);
        tower_gunner_reach = params.getBoolean("tower_gunner_reach", tower_gunner_reach);
        tower_prequeue = params.getBoolean("tower_prequeue", tower_prequeue);
        tower_self_first = params.getBoolean("tower_self_first", tower_self_first);
        chicken_gunners = params.getBoolean("chicken_gunners", chicken_gunners);
        stall_calm = params.getBoolean("stall_calm", stall_calm);
        chief_topup_any = params.getBoolean("chief_topup_any", chief_topup_any);
        chief_trainer_near = params.getBoolean("chief_trainer_near", chief_trainer_near);
        danger_refuge = params.getBoolean("danger_refuge", danger_refuge);
        expand_under_threat = params.getBoolean("expand_under_threat", expand_under_threat);
        launch_recheck = params.getBoolean("launch_recheck", launch_recheck);
        recall_cooldown = (float) params.getDouble("recall_cooldown", recall_cooldown);
        worn_basis = params.getInt("worn_basis", worn_basis);
        worn_window = (float) params.getDouble("worn_window", worn_window);
        worn_ratio = (float) params.getDouble("worn_ratio", worn_ratio);
        enemy_stun_mult = (float) params.getDouble("enemy_stun_mult", enemy_stun_mult);
        enemy_spell_recharge = (float) params.getDouble("enemy_spell_recharge", enemy_spell_recharge);
        enemy_first_seen = params.getBoolean("enemy_first_seen", enemy_first_seen);
        retreat_split_guard = params.getBoolean("retreat_split_guard", retreat_split_guard);
        parked_scan_econ = params.getInt("parked_scan_econ", parked_scan_econ);
        parked_scan_threat = params.getInt("parked_scan_threat", parked_scan_threat);
        hold_closing = params.getInt("hold_closing", hold_closing);
        hold_multi = params.getBoolean("hold_multi", hold_multi);
        retreat_rearguard = params.getBoolean("retreat_rearguard", retreat_rearguard);
        rush_opening_only = params.getBoolean("rush_opening_only", rush_opening_only);
        target_path = params.getBoolean("target_path", target_path);
        defend_stable = params.getBoolean("defend_stable", defend_stable);
        tower_cooldown = params.getBoolean("tower_cooldown", tower_cooldown);
        stall_peons = params.getBoolean("stall_peons", stall_peons);
        ore_load = params.getInt("ore_load", ore_load);
        ore_load_penalty = (float) params.getDouble("ore_load_penalty", ore_load_penalty);
        quarters_rally = params.getBoolean("quarters_rally", quarters_rally);
        rock_stream = params.getBoolean("rock_stream", rock_stream);
        peon_dodge = params.getBoolean("peon_dodge", peon_dodge);
        chief_dodge = params.getBoolean("chief_dodge", chief_dodge);
        tower_front_entry = params.getBoolean("tower_front_entry", tower_front_entry);
        reinforce_intercept = params.getBoolean("reinforce_intercept", reinforce_intercept);
        shepherd_hold = params.getBoolean("shepherd_hold", shepherd_hold);
        tower_reaim = params.getBoolean("tower_reaim", tower_reaim);
        tower_prequeue_any = params.getBoolean("tower_prequeue_any", tower_prequeue_any);
        rock_stream_time = (float) params.getDouble("rock_stream_time", rock_stream_time);
        rock_stream_iron_s = (float) params.getDouble("rock_stream_iron_s", rock_stream_iron_s);
        rock_stream_max = params.getInt("rock_stream_max", rock_stream_max);
        site_max = params.getInt("site_max", site_max);
        rock_filler_div = params.getInt("rock_filler_div", rock_filler_div);
        rock_filler_min_workers = params.getInt("rock_filler_min_workers", rock_filler_min_workers);
        rock_filler_stock = params.getInt("rock_filler_stock", rock_filler_stock);
        lure_max = params.getInt("lure_max", lure_max);
        lure_min = params.getInt("lure_min", lure_min);
        lure_range = params.getInt("lure_range", lure_range);
        lure_time = (float) params.getDouble("lure_time", lure_time);
        tower_parallel_late = params.getInt("tower_parallel_late", tower_parallel_late);
        sites_parallel_late = params.getInt("sites_parallel_late", sites_parallel_late);
        tower_parallel_late_time = (float) params.getDouble("tower_parallel_late_time", tower_parallel_late_time);
        evac_hp = (float) params.getDouble("evac_hp", evac_hp);
        evac_min = params.getInt("evac_min", evac_min);
        snipe_min = params.getInt("snipe_min", snipe_min);
        snipe_range = params.getInt("snipe_range", snipe_range);
        attack_threat_ratio = (float) params.getDouble("attack_threat_ratio", attack_threat_ratio);
        recall_ratio = (float) params.getDouble("recall_ratio", recall_ratio);
        home_guard = (float) params.getDouble("home_guard", home_guard);
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
        hold_backlog = params.getInt("hold_backlog", hold_backlog);
        hold_backlog_until = (float) params.getDouble("hold_backlog_until", hold_backlog_until);
        veto_resite = (float) params.getDouble("veto_resite", veto_resite);
        veto_resite_time = (float) params.getDouble("veto_resite_time", veto_resite_time);
        veto_resite_clear = params.getInt("veto_resite_clear", veto_resite_clear);
        veto_resite_quarters = params.getBoolean("veto_resite_quarters", veto_resite_quarters);
        bank_guard = params.getBoolean("bank_guard", bank_guard);
        bank_guard_time = (float) params.getDouble("bank_guard_time", bank_guard_time);
        bank_min = params.getInt("bank_min", bank_min);
        bank_margin = (float) params.getDouble("bank_margin", bank_margin);
        bank_noforge_s = (float) params.getDouble("bank_noforge_s", bank_noforge_s);
        bank_reserve_max = params.getInt("bank_reserve_max", bank_reserve_max);
        wood_reach = params.getInt("wood_reach", wood_reach);
        wood_reach_time = (float) params.getDouble("wood_reach_time", wood_reach_time);
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
        freeze_open = params.getBoolean("freeze_open", freeze_open);
        freeze_squad = params.getInt("freeze_squad", freeze_squad);
        freeze_eta = (float) params.getDouble("freeze_eta", freeze_eta);
        freeze_raze = params.getBoolean("freeze_raze", freeze_raze);
        freeze_unfreeze = params.getBoolean("freeze_unfreeze", freeze_unfreeze);
        freeze_fight = params.getBoolean("freeze_fight", freeze_fight);
        freeze_armory_push = params.getBoolean("freeze_armory_push", freeze_armory_push);
        freeze_retarget = params.getBoolean("freeze_retarget", freeze_retarget);
        frozen_last = params.getBoolean("frozen_last", frozen_last);
        freeze_targets = params.getInt("freeze_targets", freeze_targets);
        freeze_squad2 = params.getInt("freeze_squad2", freeze_squad2);
        freeze_eta2 = (float) params.getDouble("freeze_eta2", freeze_eta2);
        freeze_keep = params.getInt("freeze_keep", freeze_keep);
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
            // Wins against many copies are long all-in campaigns (lab/gauntlet/campaign.py, camp40-vs8-hv): attack
            // at even strength, keep attacking with the base under threat, and do not call the army home (vs
            // hard*8: aggro-vs8-hv-b 43/200 vs 31/200 on fresh seeds 201..400, elim +.077 z 3.4; 19 vs 16 on 1..100).
            strategy.attack_ratio = 1f;
            strategy.adaptive_caution = false;
            strategy.attack_threat_ratio = 1f;
            // A copy defends with its own warriors only: count other copies' armies only near the target (vs hard*8
            // gateown-vs8-hv-b 65 vs 52 of 200, elim +.086 z 3.4; N=9 elim +.035 z 2.3; N=10 +.031 and +.040, z 3.1).
            strategy.gate_owner = true;
        }
        if (enemies >= 12) {
            // The freeze opening puts the nearest copy out in about a minute; it pays from N=12, where survival under
            // pressure decides (N=12 W 4 -> 13 over 600 seeds, elim +.02 to +.04 on each half; N=13 W 2 -> 4 over 400),
            // but not at N=11, where the delayed opening costs the early campaign (W 26 -> 23 over 400).
            strategy.freeze_open = true;
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
