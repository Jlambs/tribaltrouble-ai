package com.oddlabs.tt.player.gauntlet;

import com.oddlabs.matchmaking.Game;
import com.oddlabs.tt.aikit.AiParams;
import org.jspecify.annotations.NonNull;

/**
 * Tunable numbers behind the AI's plan. The field defaults come from the 1v1 Expert AI it was ported from (large
 * islands); {@link #forGame} sets the ones tuned against many Hard copies and the freeze opening (for every number of
 * enemies), {@link #forMapSize} adjusts them for other sizes, and new styles of play can subclass or copy this. Every
 * field is a param of the spec (gauntlet:name=value, {@link #apply}); the ones that default to off are experiments
 * kept for reference, lab/gauntlet/NOTES.md records how each one did.
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
    /**
     * tower_wood_drop: a placed tower site with at most tower_wood_trees trees within 7 cells gets its wood carried
     * from the nearest complete armory within tower_wood_reach cells that can spare it (the armory's transport-wood
     * deploy, 1 piece of 5 HP per peon), at most tower_wood_max pieces per project, while the armory keeps
     * tower_wood_reserve wood and half its workers (at least 4). Idle peons carrying wood are kept for such sites. From
     * tower_wood_time (game seconds) on. Treeless sites take a median 116 s against 45-69 s for sites with trees: their
     * builders walk for wood (tower13 audit, 53 % of the 10-25-min sites at N=13). Smoke N=13 s2001-2012 (smoke-wood2
     * against logs-cur5-vs13): treeless sites placed at 10-25 min finish in a median 61 s instead of 127 s, and those
     * placed before 10 min in 46 s instead of 89 s; 44 % of the wood leaves before 10 min, when the armory needs it
     * for weapons (w15 68 -> 54 over the 12 games, noisy): tower_wood_time=600 keeps it to the audited window.
     */
    boolean tower_wood_drop = false;
    int tower_wood_trees = 0;
    int tower_wood_reach = 40;
    int tower_wood_reserve = 8;
    int tower_wood_max = 20;
    float tower_wood_time = 0f;
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
     * our army (0 = off) and targets guarded by other copies' awake warriors (Military.finishTarget).
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
    /**
     * From tower_parallel_late_time on: tower projects and placed sites at a time (the siege razes towers). 2 and 3
     * (were 1 and 2): two placed sites shared with quarters were the real bound on tower completions in the collapse
     * window (tower13 audit), so more towers stand; towers20 +0.7 to +1.3 and surv60 +0.3 to +1.3 in every one of 11
     * blocks at N=12-14; wins on 1,200 fresh seeds N=13 0 -> 3, N=14 1 -> 2; N=12 over 400 W 15 -> 13.
     */
    int tower_parallel_late = 2;
    int sites_parallel_late = 3;
    float tower_parallel_late_time = 600f;
    /**
     * From tower_parallel_late_time on, a quarters project may not take the last free construction-site slot while a
     * tower project that could start waits to be placed (tower13 audit: a quarters site holds a slot while a tower
     * waits in 9.3 % of the 12-25-min samples at N=13, and quarters, priority 5, claim a freed slot before towers, 8).
     */
    boolean site_towers_first = false;
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
     * is not up yet, move the armory ahead of the remaining opening quarters and put weapons before quarters for up to
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
     * seed_quarters (seconds, 0 = off): once the first armory stands, a quarters finished less than this long ago takes
     * its hold from idle peons within seed_quarters_reach cells (the builders at its door) instead of breeding it up
     * from empty: breeding is n^(1/3) / 11 a second with an empty quarters counted as 0.5, so filling 0 -> 10 takes
     * ~77 s and a seeded quarters breeds ~5 more peons meanwhile. Before the first armory, idle peons already fill
     * quarters below their hold.
     */
    float seed_quarters = 0f;
    int seed_quarters_reach = 12;
    /**
     * While the main armory could forge at least hold_backlog weapons (0 = off), quarters hold only hold_early: a held
     * peon above 4 buys ~8 peons per 1000 s, a worker with ore ~12.5 weapons (Economy.holdFor). Off again once 1 or
     * fewer can be forged and 30 s have passed; hold_backlog_until > 0 limits it to the early game.
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
     * unjam (units, 0 = off; arm 8): when the Jams scan finds at least this many attack units blocked (walking but on
     * the same cell as 5 s before) on every scan for unjam_after s, with no enemy warrior, chieftain or tower within
     * 30 cells of them, while the army's pivot got less than unjam_progress m closer to the target, the attack marches
     * as a column until unjam_time s after the last jammed scan: no pivot hold, and every unit walks towards its own
     * point `lead` meters on along the target field (the front at most two leads past the pivot) instead of the
     * pivot's waypoint (Military.noteBlocked, Military.attack). s97 and s98 at N=11 (lab note 2026-09-29, jam
     * pictures): the first units through a 1-3-cell pass reach their spread cells at its exit and stand idle; the
     * engine's pathfinder treats idle and blocked units as walls, so the column behind them blocks, the pivot in it
     * never moves the waypoint on, and the idle plug, "already there" and ahead of the pivot, is never re-ordered.
     */
    int unjam = 8;
    float unjam_after = 15f;
    int unjam_progress = 10;
    float unjam_time = 30f;
    /**
     * unjam acts only from this game time (s). 2400 (cur8; was 0 with unjam off): from 40 min it only acts in a jam;
     * on the cur7 benchmark games alive at 40 min it changed nothing but s6028 (N=13, 225 warriors wedged in a cliff
     * pocket against 3 copies with 3 warriors: draw at 360 -> win), and it freed the same wedge against two Experts.
     */
    float unjam_from = 2400f;
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
     * (Economy.pickSupply): the wood lock that left ~200 peons idle in the armory in s63, s60 and s315. 150 (cur8; was
     * 0): the lock is 42-56 % of the 1-3-h gaps in the long wins (trees 43-122 cells away in every lock); on the cur7
     * benchmark games alive at 40 min wins 19 -> 26 at N=13-15 with none lost, fresh block N=13-14 12 -> 13, long wins
     * 30-190 min shorter (s6189 319 -> 113 min, s6022 305 -> 117).
     */
    int wood_reach = 150;
    float wood_reach_time = 2400f;
    /**
     * ore_reach (cells, 0 = off): when the ore the weapons need has none within the main armory's 400 m walking field
     * (rock axes when iron is far, and no rock there either, or iron gone), gatherers walk to iron, else rock, up to
     * 2 x ore_reach m away instead of waiting: with every ore beyond 400 m the armory forged nothing and its 100-200
     * workers sat idle while one Hard out-built us (all four N=1-2 losses of low-vs1/vs2).
     */
    int ore_reach = 0;
    /**
     * gather_probe: armory workers are deployed for gathering only when a supply of the kind can be picked, and a peon
     * whose pick fails goes on to its next use instead of standing where it is (the play test's peons walking out of
     * the armory and straight back in, and peons standing still in the base).
     */
    boolean gather_probe = false;
    /**
     * rearm_placer (expand/critique #1, D1a): an armory project's placer (Economy.choosePlacer) is the nearest idle,
     * walking or tree-gathering peon, else one walking into a building, that has no threat within 11 cells and no enemy
     * warrior within 12 cells of its straight way to the site; with none, one peon leaves the quarters nearest the site
     * (a peon inside, no threat within 12, a clear way) at most every 5 s, reserved for 3 s so Shepherd, Lures, Dodges,
     * Decoys and the sappers (which run first) leave it; then a safe builder of another site, then a peon with only 8
     * clear cells along the way, then (no armory standing, a quarters left) a shepherd; else the project waits rather
     * than send a placer into a threat: a lost armory's for 30 s, then it is planned afresh; the first armory's or an
     * expansion's for 30 s, then its placer is chosen by the old rule (unplaced, it would stop every later expansion
     * check); a hop's and a lock move's until reloc's drop. The placer carrying an armory site is left out of
     * Military.evacuatePeons while no threat is within 6 cells. A lost armory's new site with a threat within 25 cells
     * gives way to a site by another quarters with none, instead of waiting. In 15 logged N=14 games 45 rebuild
     * placements failed: 21 placers killed on the way, 24 re-ordered into buildings (28 of the placers sent were
     * already walking into one), and in the end every peon outside was a shepherd (stall.md).
     */
    boolean rearm_placer = false;
    /**
     * rearm_reach (m of walking from the start, 0 = off; arms 260 and 400; expand/critique #7, D1b): a lost last armory
     * goes up again at the best armory site (gathering cost: iron and trees, walk and exposure) within this reach that
     * is quiet: no threat and no enemy warrior within 30 cells, no building of ours razed within 25 cells in the last
     * 180 s (Economy.quietOk); searched at most every 10 s, else the old site next to the quarters with the least enemy
     * strength (safeArmorySite, which ignores iron and nearly wood: 30 % of rebuild sites had no tree within 7 cells,
     * and s6010's stood unfinished for 318 s with 6 builders, stall.md).
     */
    int rearm_reach = 0;
    /**
     * reloc (expand/critique #2, the hop): from reloc_time, the expansion check no longer needs a quiet base and a lone
     * armory. With no armory site or project, a primary armory and every other armory drained (not primary, nobody
     * inside, iron + rock <= 1, no gatherers linked, not evacuating), every 30 s and reloc_gap s after the last
     * expansion ended (completed or dropped), Economy.considerRelocation moves the armory when the current one is poor
     * (iron cycle >= 70 s or cost >= 110, the expansion rule) or has fewer than reloc_nodes live iron nodes within 30
     * cells, to the best site within reloc_reach m of the primary (a Search reused for a minute; reloc_reach above 400
     * computes a field per check) that is quiet (quietOk), at least 40 cells from it (tested in the pass over every
     * cell, so the candidates by the primary take none of the 200 rejections), with at least reloc_nodes live iron
     * nodes within 30 cells (the verify smokes moved to sites with 0 nodes against 0 on the cost ratio alone) and no
     * enemy warrior within 12 cells of the straight way, and costs at most 0.75 of the current armory (0.9 from
     * desperate_iron_cycle). One armory project at a time; a hop project unplaced for 90 s (with reloc_slot: 90 s with
     * a slot open) is dropped. Its builders may come out of the primary above want_workers + 5. Once the hop falls, the
     * primary is chosen once (no threat within 16 cells, lowest gathering cost, newest on ties) and switched only after
     * 20 s with a threat within 16 and at least 60 s after the last switch, since every switch recalls the old armory's
     * gatherers. Why: the global threat gate stopped 100 % of the one-armory checks after 13 min at N=14, the
     * two-armory gate 66 % of 8-13-min plan ticks (stall.md); the expansion mines out its 25-cell pile 2-6 min after
     * completion (34 -> 3 -> 0 loads) while a site 40-80 cells deeper holds a median 158 loads within 30 cells
     * (critique hop.py), and expansion=false cost surv60 -1.3 min (z -3.0).
     */
    boolean reloc = false;
    float reloc_time = 600f;
    float reloc_gap = 120f;
    int reloc_reach = 260;
    /**
     * reloc: the node trigger, fewer than this many live iron nodes within 30 cells of the primary, and the least a hop
     * site needs within 30 cells (0 = both off).
     */
    int reloc_nodes = 3;
    /**
     * reloc_slot (expand/critique #3): a hop is planned at the 20-building cap too, and while its project waits
     * unplaced with the engine's count (buildings and placed sites), and the sites other placers carry, at the cap less
     * one, no new tower project is planned and no other project (tower, quarters, sniper tower) starts, so the next
     * freed slot goes to the armory: with two armories standing the cap binds in 33 % (8-13 min) and 58 % (13-20) of
     * censuses (capstate.py), and towers fall at ~1.3/min then. The hop's 90-s drop then counts only while a slot
     * stands open.
     */
    boolean reloc_slot = false;
    /**
     * raid_bank (expand/critique #4, D3): from raid_bank_time, a forward primary armory (not the finished armory
     * nearest our start, Economy.homeArmory) keeps only the workers its measured iron income can keep forging
     * (bank_margin x income x 80 s a weapon) plus a backlog of min(raid_bank_extra, its iron, + half its rock while
     * rock axes are made), bank_min once it has been unable to forge for bank_noforge_s; the rest wait in the quarters
     * farthest from the threat (bank_guard's machinery: Economy.guardBank, reserveQuarters, the reserve kept above the
     * quarters' hold). The expansion was razed in all 850 of 1,000 N=14 games that built it, a median 6.0 min after
     * completion, and our units dropped a median 60 in that census step with 81 inside just before (raze.md);
     * drainSecondary moves the home armory's bank into the forward one as soon as the home one cannot forge, and each
     * hop (reloc) does it again. raid_bank_extra is bank_guard's fixed backlog of 12 as a param (the dry-spell judge's
     * caveat: 12 keeps an armory small when wood comes back to a full iron bank). Arm raid_bank_time=0: the first
     * expansion from its completion (~7-8 min) too.
     */
    boolean raid_bank = false;
    float raid_bank_time = 600f;
    int raid_bank_extra = 12;
    /**
     * reloc_draw (copies, 0 = off; arm 2; expand/critique #5): a hop site (Economy.considerRelocation) is turned down
     * when it would be our nearest building for the oldest idle warriors of at least this many copies (Economy.drawOf):
     * a Hard copy aims each wave from its oldest idle warrior at our building nearest to it, with no range limit
     * (AdvancedAI.findTarget), and our razings follow where idle warriors stand, not the copies' starts (razed_rank.py:
     * 63 % of razed buildings were in the outer third of those standing, with a start-exposure rank of 0.49, as
     * random). The 30-cell quiet test does not model that: in the reloc1 smokes most hop sites were razed as sites or
     * within a minute. With reloc on, every hop check logs its site's draw whether or not this is on.
     */
    int reloc_draw = 0;
    /**
     * raid_evac (expand/critique #6, D3): when Shepherd sees a copy launch (its oldest idle warrior walks off
     * aggressively to a cell more than 20 cells away) a wave of at least 12 warriors (the copy's warriors walking to
     * within 12 cells of that cell; one sent elsewhere since drops out) at a cell within 20 cells of a complete armory
     * of ours holding at least raid_evac_min units (from raid_evac_time), and the wave's strength is at least
     * raid_evac_ratio x the armory's defence (manned towers within 16 cells, our warriors within 20), the armory is
     * emptied once the wave's front is 45 s out (at 2.5 cells/s; not under 10 s, which would send the evacuees into
     * it): weapons leave as warriors and the rest as peons, towards the home armory's cell when that is another armory
     * with no threat within 16 (the peons wait inside it for the window), else into the quarters farthest from the
     * threat (held there above its hold for the window), else 18 cells away from the wave. For 60 s nothing is sent
     * into it and no gatherer out for it, then its rally point is cleared. The old evacuate waited for HP < evac_hp (kd
     * -.068, z -3.7: evacuees walked out into the attackers). The expansion falls with a median 81 of our units inside;
     * an armory lets ~2 peons out a second, 40 in 20 s, while a wave walks 100 cells in 35-40 s (raze.md). Needs
     * shepherd (the launch detection).
     */
    boolean raid_evac = false;
    int raid_evac_min = 12;
    float raid_evac_ratio = 1f;
    /**
     * raid_evac (arms 780, and 2400 for the late track, where an arm must not act before 40 min): no armory is emptied
     * before this time (s; 0 = from the start). An evacuation stops the armory's forge and every gatherer sent for it
     * for 60 s: in the verify smoke (8 N=14 games) raid_evac alone cut our iron at 8-13 min 833 -> 674, and 10 of its
     * 12 evacuations before 13 min were false alarms (the armory stood), against 6 of the 12 later ones, which fell
     * with up to 115 inside; the big falls come late (39 of 53 long losses lost 100+ units in one armory razing, 34 of
     * them after 40 min, dryspell judge).
     */
    float raid_evac_time = 0f;
    /**
     * reloc_lock (seconds held, 0 = off; arm 120; expand/critique #8, dryspell/judge.md fix 3): from reloc_lock_time,
     * once the wood lock has held this long (Economy.trackLock: the primary armory's tree cycle >= 90 s, i.e. no usable
     * tree within its 60-cell ring, its wood < 2 and its workers >= want_workers + 20), the armory moves to trees
     * (Economy.lockRelocate): with no armory site or project, and no global threat gate, the best armory site by
     * gathering cost (2 x tree + iron: the banked iron stays behind) within reloc_reach m that is quiet (quietOk: no
     * threat and no enemy within 30 cells, no razing of ours within 25 in 180 s) and has no enemy warrior within 12
     * cells of the straight way, both tested down the ranked candidates, and a tree cycle of its own under 60 s; after
     * a miss it looks again in 10 s (the search reused for a minute). The project is added at the 20-building cap too:
     * its placer waits for a slot, and while it waits unplaced with the count at the cap less one no tower is planned
     * or started (reloc_slot's reserve). An unplaced lock project whose slot has stood open for 90 s is dropped and
     * planned afresh. Its placer is chosen by rearm_placer's safe rule (and left out of evacuatePeons while no threat
     * is within 6 cells) with rearm_placer off too: in the s6415 smoke 4 of 8 lock projects were dropped after 7 placer
     * failures in 7-15 s. Its builders may come out of the locked armory above want_workers + 5. Once it stands (and
     * becomes primary), the locked armory keeps its workers inside until the new one holds 2 wood and has no threat
     * within 16 cells, 120 s at most. Why: the lock was 42 % of the gap minutes of the long wins (150-200 peons waiting
     * inside, iron at the 200 cap, 1.4-1.6 warriors/min against 9.5-13), usable trees stood 43-122 cells away in every
     * lock, moving was blocked in 96-100 % of locked minutes by the threat gate and the cap (55-88 %), and wood
     * reaching an armory again ended 7 of 12 long locks, the first out ~12 min later.
     */
    int reloc_lock = 0;
    float reloc_lock_time = 2400f;
    /**
     * retire (expand/critique #8, D5 retire; quarters.md, the skeptic's narrow case): when a flagged armory project (a
     * reloc hop, which waits at the cap only with reloc_slot, or a reloc_lock move) has waited unplaced retire_wait s
     * at the 20-building cap, one slot is freed by razing a building of ours with the explicit attack order (the attack
     * button and a click on it), at most one every 120 s, taking the first of: a stalled site (placed, no builders,
     * 120 s old); a stranded tower (no quarters or armory within 25 cells; its gunner out first, 4-8 peons at 3 HP/s
     * each); a quiet drained armory (not primary, nobody inside, no stock or gatherers, no threat within 30); a far
     * quarters (more than retire_quarters_dist cells from the main armory, units >= retire_pop so breeding is off,
     * another quarters within 25 cells, emptied first, not training the chieftain). Never a besieged building (a threat
     * within 20 cells, 30 for an armory; a razing is called off when one comes), and it is called off too once the slot
     * is not wanted (the project placed or dropped, or a slot freed another way) or the building has become our main or
     * last armory or our last quarters. Quarters and armories go down to up to 12 idle iron or chicken warriors the
     * military lends (0.75 HP/s each, 10 for 200 HP) when 8 are at hand, a quarters else to up to 20 peons (1 HP a
     * swing on a 20 % roll, 0.1 HP/s each: ~100 s for 200 HP; an armory never, D5). The building is doomed meanwhile:
     * no tower manning, no peons sent in, no repairs, never primary, and no armory site within 12 cells of it for 60 s
     * after. Why: the cap blocks 55-88 % of wood-locked minutes and s6189's new armory waited 112 min for a slot
     * (dryspell judge); the slot frees on the tick of the razing, and our own AI fought the test razings (18 of 19
     * gunners died in their tower, quarters refilled, repairers stayed on; raze.md). Far quarters hold a slot at the
     * cap 8.6-10.3 min in wins, 9.5 of them above 187 units in the N=13 wins (quarters.md skeptic).
     */
    boolean retire = false;
    float retire_wait = 60f;
    int retire_quarters_dist = 80;
    int retire_pop = 245;
    /**
     * retire_any_tower (with retire; arm true): with none of retire's buildings to raze, the tower with no threat
     * within 20 cells farthest from the main armory goes (its gunner out first, 4-8 peons): in the s6189 and s6709
     * smokes a lock move waited 19 and 31 min at the cap with 15-16 towers, every one within 25 cells of a quarters or
     * the armory, and no stalled site, drained armory or far quarters.
     */
    boolean retire_any_tower = false;
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
    /**
     * At the unit cap a home group of 12 may go to the attack whatever its size; with capped_clump > 0 that holds only
     * while the attack army is within capped_clump_cells of the armory, and a group for a farther army waits until it
     * is worth max(capped_clump_min, capped_clump x the army) (audit13 military 4: capped trickles to an army 100+
     * cells out lost 0.38-0.46 per unit sent, clumps 0.18-0.28). 0: off.
     */
    float capped_clump = 0f;
    float capped_clump_min = 24f;
    int capped_clump_cells = 100;
    /**
     * Between ring_sweep_from and ring_sweep_until, with no copy out for ring_sweep_quiet seconds, an attack army
     * within ring_sweep_reach cells of the armory and worth ring_sweep_ratio times the parked ring (idle enemy warriors
     * within 45 cells of our buildings) comes home, and the home army then takes on the ring's blobs nearest the armory
     * one at a time while the base is quiet, until the ring is down to 30 % or ring_sweep_until + 60 s (audit13
     * military 5: 12-15 min is the one window in which the army outnumbers the ring, whose blobs launch 37-41 % of the
     * base-bound waves at 12-20 min; fights near our buildings trade 4.6-5.9:1, abroad 2.1-2.6:1). An attack whose
     * target's owner has fewer than two finished buildings left is not called off.
     */
    boolean ring_sweep = false;
    float ring_sweep_from = 720f;
    float ring_sweep_until = 930f;
    float ring_sweep_ratio = 1.5f;
    float ring_sweep_quiet = 120f;
    int ring_sweep_reach = 200;
    /** How much more an enemy manned tower counts than Combat.TOWER when judging an attack or retreat. */
    float tower_weight = 1f;
    /** Army strength that attacks regardless of the odds. */
    float attack_max_strength = 70f;
    /** At the unit cap losses are replaced for free, so attack against this much of the defense. */
    float capped_ratio = .6f;
    /**
     * The capped attack needs at least this much army and stock (0: none): during wood locks at the cap it mustered
     * with nothing ("muster: army 0.0 + stock 0.0 vs defense 0.0", 0 >= 0.6 x 0) and sat in MUSTER, deploying every
     * weapon, for 45 s at a time (Military.considerAttack).
     */
    float capped_min_strength = 0f;
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
     * freeze_squad 6 (was 10): the four peons more at home pay (N=12 over 400 seeds W 8 -> 15, surv60 +1.3 min, z 2.7;
     * freeze-squad6-c3-vs12-hv and -b), 4 fails the strike too often (W 9 -> 2) and 14 starves the opening (W 7 -> 1).
     */
    boolean freeze_open = false;
    int freeze_squad = 6;
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
    /** Farthest chicken a hunter goes for, in cells from the main armory. */
    int chicken_range = 150;
    /**
     * stall_cap (s, 0 = off): an attack that gains no 20 m on its target and kills fewer than stall_cap_kills units in
     * that time stalls (target skipped, as a calm stall), even while some of the army fights.
     */
    float stall_cap = 0f;
    int stall_cap_kills = 10;
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
     * A builder (or repairer) that has stood on the same cell for this many seconds more than 3 cells from its building
     * is wedged (a dead-end notch or a pass it deadlocks in with others, jam-logs s9: 6-23 builders for 13 min) and is
     * sent into the nearest armory, which frees it for the economy. 0: off.
     */
    float unstick_builders = 0f;
    /**
     * Builders and repairers taken from the gatherers are the ones with the shortest walk to the site (meters, the
     * farthest considered), not the nearest in a straight line. 0: straight line.
     */
    int walk_select = 0;
    /**
     * When the tower anchor's ring has no legal site, look around the other buildings (the same anchor at 4-20 cells,
     * then the home and primary armory and every finished quarters at 7-15 cells, then all of them at 4-20) instead of
     * retrying the full ring every plan tick until one of our towers is razed (tower13 audit: the lock takes 9 % of the
     * construction slots at N=13 in 12-25 min, 31 % in the wins' 15-25 min; s2007 planned no tower from 1045 to 1671 s
     * with room for two buildings). A miss waits 15 s before the next try.
     */
    boolean tower_site_fallback = false;

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
    /**
     * From chief_wake_retreat seconds after our chieftain's cast until his stun is ready again, he keeps
     * chief_wake_keep cells from every enemy warrior within reach, stunned ones included: the ones his stun froze wake
     * inside his reach otherwise (audit13 shepherds 2: 92 % of his deaths come within 40 s after his own stun, a median
     * 11 cells from where he cast; chief_safe skips stunned warriors). 0: off.
     */
    float chief_wake_retreat = 0f;
    int chief_wake_keep = 12;
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
     * shepherd_sticky (score cells, 0 = off): a shepherd's current spot, and ring cells within 4 cells of it, score
     * this much more, so the spot no longer flips between ring cells of about equal score (Shepherd.findSpot); the
     * current spot also stays a candidate while enemies have blocked it for less than shepherd_grace seconds.
     * shepherd_travel: each cell from the shepherd to a candidate costs this much score.
     */
    float shepherd_sticky = 0f;
    float shepherd_grace = 0f;
    float shepherd_travel = 0f;
    /**
     * shepherd_safe_walk: a shepherd takes the best of the 12 best spots whose straight walk, over its first
     * shepherd_safe_look cells, keeps shepherd_safe_clear cells from every enemy warrior and chieftain, and a flee runs
     * 3 s before it heads back (Shepherd.findSpot, tend).
     */
    boolean shepherd_safe_walk = false;
    /**
     * shepherd_follow: while a copy's oldest idle warrior stands at home (40 cells from its armory) and its last wave
     * is still out, its shepherd's spot is picked around that wave's target (where its survivors go idle and lead the
     * next launch), not at home. shepherd_home_pair (cells, 0 = off): copies starting at least this far from us get a
     * second, home shepherd, whose spot is picked around the copy's oldest idle warrior at home, else its armory;
     * the copy's own shepherd then follows its wave as with shepherd_follow (Shepherd.tend).
     */
    boolean shepherd_follow = false;
    /**
     * shepherd_gap (seconds, 0 = off): after a copy's shepherd is lost, the next is recruited only this much later
     * (nine shepherds of ten die, most on the way: Shepherd.tend).
     */
    float shepherd_gap = 0f;
    int shepherd_home_pair = 0;
    int shepherd_safe_look = 40;
    int shepherd_safe_clear = 10;
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
        shepherd_sticky = (float) params.getDouble("shepherd_sticky", shepherd_sticky);
        shepherd_grace = (float) params.getDouble("shepherd_grace", shepherd_grace);
        shepherd_travel = (float) params.getDouble("shepherd_travel", shepherd_travel);
        shepherd_safe_walk = params.getBoolean("shepherd_safe_walk", shepherd_safe_walk);
        shepherd_follow = params.getBoolean("shepherd_follow", shepherd_follow);
        shepherd_gap = (float) params.getDouble("shepherd_gap", shepherd_gap);
        shepherd_home_pair = params.getInt("shepherd_home_pair", shepherd_home_pair);
        shepherd_safe_look = params.getInt("shepherd_safe_look", shepherd_safe_look);
        shepherd_safe_clear = params.getInt("shepherd_safe_clear", shepherd_safe_clear);
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
        site_towers_first = params.getBoolean("site_towers_first", site_towers_first);
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
        tower_wood_drop = params.getBoolean("tower_wood_drop", tower_wood_drop);
        tower_wood_trees = params.getInt("tower_wood_trees", tower_wood_trees);
        tower_wood_reach = params.getInt("tower_wood_reach", tower_wood_reach);
        tower_wood_reserve = params.getInt("tower_wood_reserve", tower_wood_reserve);
        tower_wood_max = params.getInt("tower_wood_max", tower_wood_max);
        tower_wood_time = (float) params.getDouble("tower_wood_time", tower_wood_time);
        quarters_before_armory = params.getInt("quarters_before_armory", quarters_before_armory);
        opening_near_start = params.getBoolean("opening_near_start", opening_near_start);
        rush_response = params.getBoolean("rush_response", rush_response);
        rush_quarters = params.getInt("rush_quarters", rush_quarters);
        rush_seconds = (float) params.getDouble("rush_seconds", rush_seconds);
        pressure_response = params.getBoolean("pressure_response", pressure_response);
        pressure_time = (float) params.getDouble("pressure_time", pressure_time);
        hold_early = params.getInt("hold_early", hold_early);
        hold_mid = params.getInt("hold_mid", hold_mid);
        seed_quarters = (float) params.getDouble("seed_quarters", seed_quarters);
        seed_quarters_reach = params.getInt("seed_quarters_reach", seed_quarters_reach);
        hold_backlog = params.getInt("hold_backlog", hold_backlog);
        hold_backlog_until = (float) params.getDouble("hold_backlog_until", hold_backlog_until);
        veto_resite = (float) params.getDouble("veto_resite", veto_resite);
        veto_resite_time = (float) params.getDouble("veto_resite_time", veto_resite_time);
        veto_resite_clear = params.getInt("veto_resite_clear", veto_resite_clear);
        veto_resite_quarters = params.getBoolean("veto_resite_quarters", veto_resite_quarters);
        unjam = params.getInt("unjam", unjam);
        unjam_after = (float) params.getDouble("unjam_after", unjam_after);
        unjam_progress = params.getInt("unjam_progress", unjam_progress);
        unjam_time = (float) params.getDouble("unjam_time", unjam_time);
        unjam_from = (float) params.getDouble("unjam_from", unjam_from);
        bank_guard = params.getBoolean("bank_guard", bank_guard);
        bank_guard_time = (float) params.getDouble("bank_guard_time", bank_guard_time);
        bank_min = params.getInt("bank_min", bank_min);
        bank_margin = (float) params.getDouble("bank_margin", bank_margin);
        bank_noforge_s = (float) params.getDouble("bank_noforge_s", bank_noforge_s);
        bank_reserve_max = params.getInt("bank_reserve_max", bank_reserve_max);
        wood_reach = params.getInt("wood_reach", wood_reach);
        wood_reach_time = (float) params.getDouble("wood_reach_time", wood_reach_time);
        ore_reach = params.getInt("ore_reach", ore_reach);
        gather_probe = params.getBoolean("gather_probe", gather_probe);
        rearm_placer = params.getBoolean("rearm_placer", rearm_placer);
        rearm_reach = params.getInt("rearm_reach", rearm_reach);
        reloc = params.getBoolean("reloc", reloc);
        reloc_time = (float) params.getDouble("reloc_time", reloc_time);
        reloc_gap = (float) params.getDouble("reloc_gap", reloc_gap);
        reloc_reach = params.getInt("reloc_reach", reloc_reach);
        reloc_nodes = params.getInt("reloc_nodes", reloc_nodes);
        reloc_slot = params.getBoolean("reloc_slot", reloc_slot);
        raid_bank = params.getBoolean("raid_bank", raid_bank);
        raid_bank_time = (float) params.getDouble("raid_bank_time", raid_bank_time);
        raid_bank_extra = params.getInt("raid_bank_extra", raid_bank_extra);
        reloc_draw = params.getInt("reloc_draw", reloc_draw);
        raid_evac = params.getBoolean("raid_evac", raid_evac);
        raid_evac_min = params.getInt("raid_evac_min", raid_evac_min);
        raid_evac_ratio = (float) params.getDouble("raid_evac_ratio", raid_evac_ratio);
        raid_evac_time = (float) params.getDouble("raid_evac_time", raid_evac_time);
        reloc_lock = params.getInt("reloc_lock", reloc_lock);
        reloc_lock_time = (float) params.getDouble("reloc_lock_time", reloc_lock_time);
        retire = params.getBoolean("retire", retire);
        retire_wait = (float) params.getDouble("retire_wait", retire_wait);
        retire_quarters_dist = params.getInt("retire_quarters_dist", retire_quarters_dist);
        retire_pop = params.getInt("retire_pop", retire_pop);
        retire_any_tower = params.getBoolean("retire_any_tower", retire_any_tower);
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
        capped_clump = (float) params.getDouble("capped_clump", capped_clump);
        ring_sweep = params.getBoolean("ring_sweep", ring_sweep);
        ring_sweep_from = (float) params.getDouble("ring_sweep_from", ring_sweep_from);
        ring_sweep_until = (float) params.getDouble("ring_sweep_until", ring_sweep_until);
        ring_sweep_ratio = (float) params.getDouble("ring_sweep_ratio", ring_sweep_ratio);
        ring_sweep_quiet = (float) params.getDouble("ring_sweep_quiet", ring_sweep_quiet);
        ring_sweep_reach = params.getInt("ring_sweep_reach", ring_sweep_reach);
        capped_clump_min = (float) params.getDouble("capped_clump_min", capped_clump_min);
        capped_clump_cells = params.getInt("capped_clump_cells", capped_clump_cells);
        tower_weight = (float) params.getDouble("tower_weight", tower_weight);
        attack_max_strength = (float) params.getDouble("attack_max_strength", attack_max_strength);
        capped_ratio = (float) params.getDouble("capped_ratio", capped_ratio);
        capped_min_strength = (float) params.getDouble("capped_min_strength", capped_min_strength);
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
        chicken_range = params.getInt("chicken_range", chicken_range);
        stall_cap = (float) params.getDouble("stall_cap", stall_cap);
        stall_cap_kills = params.getInt("stall_cap_kills", stall_cap_kills);
        chicken_time = (float) params.getDouble("chicken_time", chicken_time);
        chicken_pool_div = params.getInt("chicken_pool_div", chicken_pool_div);
        tower_fire = params.getBoolean("tower_fire", tower_fire);
        unstick = params.getBoolean("unstick", unstick);
        unstick_builders = (float) params.getDouble("unstick_builders", unstick_builders);
        walk_select = params.getInt("walk_select", walk_select);
        tower_site_fallback = params.getBoolean("tower_site_fallback", tower_site_fallback);
        chief_keep_out = params.getInt("chief_keep_out", chief_keep_out);
        chief_safe = params.getInt("chief_safe", chief_safe);
        chief_wake_retreat = (float) params.getDouble("chief_wake_retreat", chief_wake_retreat);
        chief_wake_keep = params.getInt("chief_wake_keep", chief_wake_keep);
        chief_flee_hp = params.getInt("chief_flee_hp", chief_flee_hp);
        threat_look = params.getInt("threat_look", threat_look);
        hold_ratio = (float) params.getDouble("hold_ratio", hold_ratio);
        chief_per_hit = params.getBoolean("chief_per_hit", chief_per_hit);
        base_radius = params.getInt("base_radius", base_radius);
    }

    /** The strategy for a game on a map of the given size against the given number of enemy players. */
    static @NonNull Strategy forGame(int map_size, int enemies) {
        Strategy strategy = forMapSize(map_size);
        // For every N (the N>1 gate was removed 2026-09-29: 1v1 vs hard on seeds 1..60, duel-before -> duel-allN, W 60 -> 60,
        // kd30 +9.1 (z 2.5), w15 +10.5, games 10.9 -> 13.1 min).
        // Every enemy sends his waves at our nearest building: towers early, and many of them, hold them all,
        // and the chieftain's stun is wanted sooner.
        strategy.towers_early = 3;
        strategy.towers_early_time = Math.min(strategy.towers_early_time, 200f);
        strategy.towers_mid = 6;
        strategy.towers_mid_time = Math.min(strategy.towers_mid_time, 330f);
        strategy.towers_late = 14;
        strategy.towers_late_time = 600f;
        // The chieftain from 300 s (was 240): training takes a quarters' breeding, and an earlier chieftain costs the
        // opening more peons than his stuns win back (cur6 N=13-14, 9 blocks: surv60 up in 8, fresh-seed wins 9 -> 12;
        // 360-480 about as good).
        strategy.chieftain_time = Math.min(strategy.chieftain_time, 300f);
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
        // The freeze opening puts the nearest copy out in about a minute when it starts within freeze_eta of peon walk
        // (N=12 W 4 -> 13 over 600 seeds with squad 10). With squad 6 it pays at every N tried, so the old enemies >= 12
        // gate went: N=11 over 400 seeds W 31 -> 35, elim +.032, surv60 +2.3 min (s201..400: W 12 -> 20, wp z 4.0);
        // N=8 W 111 -> 115, elim +.016 (z 1.8); N=14 against freeze off elim +.021 (z 2.7).
        strategy.freeze_open = true;
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
