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
 *
 * <p><b>Units.</b> Every time here is in game ticks, 50 a game second at every game speed
 * ({@link GauntletAI#TICKS_PER_SECOND}), so that the AI plays the same at every speed: times since the start,
 * durations, cooldowns, windows, ETAs, and the gathering costs of the armory site model (peon-ticks per warrior). Such
 * a param's name ends in _ticks (or _per_tick for a rate), and the comment by its default gives it in seconds; history
 * notes keep the seconds the experiments ran with, marked "s". Distances are in grid cells or walking meters, as each
 * doc says.
 */
class Strategy {
    /** Quarters to raise before or alongside the armory. */
    int initial_quarters = 4;
    /** Quarters to have once the economy is running. More than five pays off little. */
    int max_quarters = 4;
    /** Game ticks after which to aim for max_quarters. */
    float expand_ticks = 15000f; // 300 s

    /** Upper bound on how far the armory may be from the start, as walking meters. */
    int max_armory_distance = 260;
    /** Peon-ticks per warrior of gathering that one meter of walking from the start is worth. */
    float armory_distance_weight_ticks = 1.5f; // .03 s a meter
    /** Peon-ticks per warrior of gathering that one game tick of delay to the armory is worth. */
    float armory_delay_weight = .4f;
    /**
     * How strongly to avoid putting the armory towards the enemy: peon-ticks per warrior of gathering per unit of the
     * site's exposure beyond 0.42 (SitePlanner).
     */
    float armory_threat_weight_ticks = 3000f; // 60 s

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
     * tower_wood_ticks (game ticks) on. Treeless sites take a median 116 s against 45-69 s for sites with trees: their
     * builders walk for wood (tower13 audit, 53 % of the 10-25-min sites at N=13). Smoke N=13 s2001-2012 (smoke-wood2
     * against logs-cur5-vs13): treeless sites placed at 10-25 min finish in a median 61 s instead of 127 s, and those
     * placed before 10 min in 46 s instead of 89 s; 44 % of the wood leaves before 10 min, when the armory needs it for
     * weapons (w15 68 -> 54 over the 12 games, noisy): tower_wood_ticks=30000 (600 s) keeps it to the audited window.
     */
    boolean tower_wood_drop = false;
    int tower_wood_trees = 0;
    int tower_wood_reach = 40;
    int tower_wood_reserve = 8;
    int tower_wood_max = 20;
    float tower_wood_ticks = 0f;
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
    /**
     * rock_share_late (-1: off): from rock_late_ticks on, the share of the ore gatherers sent for rock is this instead
     * of rock_share, so rock can come in late only (rock_share 0), early only (rock_share_late 0) or in two steps.
     */
    float rock_share_late = -1f;
    float rock_late_ticks = 120000f; // 40 min
    /**
     * rock_on_fail: an iron pick that finds no iron (all of it near a threat, avoided after stuck gatherers or out of
     * reach) sends the gatherer for rock instead, and rock axes stay on order for the next 60 s.
     */
    boolean rock_on_fail = false;
    /** rock_on_fail: from this game time on (0: from the start). */
    float rock_fail_from_ticks = 0f;
    /**
     * rock_idle (0: off; cur16: 1 from 20 min): while the main armory holds at most rock_idle_iron iron, this share of
     * the workers inside
     * it beyond rock_idle_keep make rock axes on top of the ore gatherers (not out of the iron share): split between
     * rock
     * gatherers, forgers staying in and, while its wood stock is under 20, wood gatherers, as the economy's ore model
     * splits its pool. From rock_idle_ticks on.
     */
    float rock_idle = 1f; // cur16
    /**
     * away_strike (off): when the usual gate holds the army home, muster on the copy base whose own army is away
     * (kited by shepherds or stuck in an attack-move): its warriors within away_home_cells count in full, beyond that
     * its idle ones (a Hard recalls all of them) at away_idle_value and the rest at away_walk_value. From
     * away_strike_ticks on.
     */
    boolean away_strike = false;
    /**
     * wedge_real_kills (cur16: on; off as before cur16): the wedge watchdog's kills and losses, and its and the column
     * march's
     * "target fell", count only units killed (0 hit points). A unit that walked into a building is removed (dead) with
     * its hit points: near a live Hard base its peons commute in and out, 10 'kills' in 300 s cleared the strikes and
     * the watchdog never fired (the dead-vs-killed audit).
     */
    boolean wedge_real_kills = true; // cur16
    /**
     * gather_kill_zone (off): where gather_kill_n of our peons (shepherds left out) died within gather_kill_cells of
     * each
     * other within gather_kill_window_ticks, no supply within gather_kill_cells is picked for gather_kill_ticks after
     * the last of those deaths (gatherers keep walking into the same parked army). From gather_fixes_ticks on.
     */
    boolean gather_kill_zone = false;
    int gather_kill_n = 3;
    int gather_kill_cells = 8;
    float gather_kill_window_ticks = 6000f; // 2 min
    float gather_kill_ticks = 15000f; // 5 min
    /**
     * gather_route_clear (off): a supply pick takes the best of its gather_route_tries cheapest supplies whose straight
     * walk from the armory keeps gather_route_cells from every enemy warrior (none clear: the cheapest, as without it).
     * From gather_fixes_ticks.
     */
    boolean gather_route_clear = false;
    int gather_route_cells = 10;
    int gather_route_tries = 5;
    float gather_fixes_ticks = 0f;
    /**
     * shepherd_stuck_ticks (0: off; cur16 on, with the tight settings below, from 40 min): a shepherd that has not
     * reached its spot and stood within 6 cells of one point for
     * this long, not getting 10 cells nearer its spot, goes home; its copy gets no new shepherd for
     * shepherd_stuck_gap_ticks.
     */
    float shepherd_stuck_ticks = 12000f; // cur16: 4 min
    float shepherd_stuck_gap_ticks = 12000f; // cur16: 4 min
    /**
     * shepherd_stuck_base_ticks (0: shepherd_stuck_ticks everywhere): the stuck time for a shepherd standing within
     * shepherd_stuck_base_cells of one of our buildings (in or by our base, where nothing is drawn).
     */
    float shepherd_stuck_base_ticks = 6000f; // cur16: 2 min
    int shepherd_stuck_base_cells = 28;
    /** shepherd_gap_ready: a stuck or no-spot release's gap ends as soon as the copy is ready to launch. */
    boolean shepherd_gap_ready = true; // cur16
    /**
     * shepherd_nospot_ticks (0: off): from shepherd_fixes_ticks, a shepherd that has had no spot for this long goes
     * home; its copy then waits shepherd_stuck_gap_ticks for the next one.
     */
    float shepherd_nospot_ticks = 6000f; // cur16: 2 min
    /**
     * shepherd_detour (cur16: on): from shepherd_fixes_ticks, a shepherd whose straight walk to its spot would set off
     * the flee
     * test walks to a waypoint shepherd_detour_r or half that away whose walk is clear and that gets it nearer.
     */
    boolean shepherd_detour = true; // cur16
    /**
     * shepherd_gap_backoff: each further stuck, no-progress or no-spot release in a row for a copy doubles its gap,
     * up to shepherd_gap_max_ticks; a shepherd of the copy reaching its spot, or a wave aimed at a shepherd, resets it.
     */
    boolean shepherd_gap_backoff = true; // cur16
    float shepherd_gap_max_ticks = 48000f; // 16 min
    /**
     * shepherd_progress_ticks (0: off): from shepherd_fixes_ticks, a shepherd that has not reached its spot and has not
     * got 10 cells nearer it (the same spot, within 10 cells) for this long goes home, with the gap as a stuck one.
     */
    float shepherd_progress_ticks = 9000f; // cur16: 3 min
    /**
     * shepherd_progress_base: shepherd_progress_ticks releases only a shepherd within shepherd_stuck_base_cells of one
     * of our buildings (one far out is on a long walk to a far copy's spot and still draws its waves: tight2 N=24 s7062
     * lost its base 81 s after such a release).
     */
    boolean shepherd_progress_base = true; // cur16
    int shepherd_detour_r = 16;
    /** shepherd_stuck_ticks and shepherd_need act from this game time on. */
    float shepherd_fixes_ticks = 120000f; // cur16: 40 min
    /**
     * shepherd_need (0: off): a copy whose idle warriors are below this share of its wave size, that launched 3 waves
     * or more and none within shepherd_need_ticks, gets no shepherd, and its shepherd goes home once that has lasted
     * shepherd_need_ticks.
     */
    float shepherd_need = 0f;
    float shepherd_need_ticks = 30000f;
    /**
     * shepherd_recruit_clear: from shepherd_recruit_clear_ticks, a shepherd is recruited only from the up to 5 peons
     * nearest the origin whose straight walk to the spot starts clear of the flee test.
     */
    boolean shepherd_recruit_clear = false;
    float shepherd_recruit_clear_ticks = 90000f;
    /** shepherd_coming_stalled (0: off): attack-walkers that moved fewer cells than this in 10 s are not coming. */
    int shepherd_coming_stalled = 0;
    /** shepherd_coming_stalled applies from then on. */
    float shepherd_coming_stalled_ticks = 0f;
    float away_strike_ticks = 0f;
    int away_home_cells = 40;
    float away_idle_value = .5f;
    float away_walk_value = .15f;
    float rock_idle_ticks = 60000f; // cur16: 20 min
    int rock_idle_keep = 4;
    int rock_idle_iron = 2;
    /** Attack a copy's quarters before its armory: the peons bred inside die with it and chieftain training stops. */
    boolean quarters_first = false;
    /**
     * Raze each copy's quarters and move on: without quarters it can train no chieftain, and from wave size 20 no
     * wave leaves without one (AdvancedAI); its armory, army and peons are left until every copy is quarterless.
     */
    boolean gate_freeze = false;
    /**
     * decapitate: a campaign that makes the copies passive before it puts them out. A Hard copy sends a wave of 20 or
     * more (its third wave on) only with an active chieftain, trains one only in a finished quarters, and defends only
     * near its first quarters or armory (AdvancedAI). So a copy is live while it has a finished quarters or an active
     * or training chieftain, and pacified once it has neither: it never sends a wave again unless it rebuilds its
     * quarters, which it does when it lacks idle warriors and its armory lacks the peons to arm more (so fighting its
     * warriors or razing its armory can wake it). With decapitate, from decapitate_from_ticks on, target choice
     * (Military.chooseTarget) takes first, by the usual score: every enemy quarters (a finished one is a live copy's;
     * a site would make its copy live again), the finished armory of a copy without either that may still send a
     * wave of 10 or 15 without a chieftain (fewer than two waves seen by the shepherds, or not watched), and every
     * active enemy chieftain within decapitate_chief_cells of the army, scored like a quarters (a quarterless copy's
     * chieftain without the quarters' 60 m priority: his death pacifies it for good). Everything else, the armories,
     * towers and units of pacified copies among it, is attacked only when none of those is a candidate, or when the
     * muster's best campaign target fails the gate: then the best other building target choice scored, if it passes
     * (decapitate_alt; without it a quarters guarded by a parked blob, or a capped army's failed capped_ratio test,
     * held the army home for good while the pacified copies' cheap buildings were never weighed), or with
     * remnant_ladder_ticks the ladder's first that passes instead; finish_copies and focus_finish wait for the same. So
     * the army moves on from a copy once its quarters is razed and its chieftain dead, instead of staying to put it
     * out. gate_freeze (quarters first by score, chieftains ignored) did not pay: 78 of 142 copies that lost their
     * quarters kept waving with their chieftain, 59 rebuilt (lab/gauntlet/NOTES.md, quarters_gate.py), hence the
     * chieftains and the sites here. A chieftain target is followed: its field is computed again once it has walked 8
     * cells off, at most every 5 s (Military.DECAP_FOLLOW_TICKS), while within twice decapitate_chief_cells of the
     * army's centre; the stall clocks keep running across a follow (gains measured from where the army stands on the
     * new field), so a chieftain the army cannot catch stalls as any target does. Every gate (strength, stalls, dead
     * regions, frozen copies, wedges) stays as it is. Counts: decapitate_target (and _quarters, _site, _armory, _chief)
     * per attack target it took, decapitate_changed when that passed over a better-scoring other target,
     * decapitate_alt, decapitate_follow, and per copy decapitate_pacified (once), decapitate_pacified_early (it may
     * still send a small wave), decapitate_relive.
     */
    boolean decapitate = false;
    /** decapitate: cells from the army (the staging point at a muster) within which an enemy chieftain is a target. */
    int decapitate_chief_cells = 60;
    /** decapitate acts from this game tick (0: from the start; 120000 keeps games identical to 40 min for late.py). */
    float decapitate_from_ticks = 0f;
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
    /**
     * corner_fields: a target field that does not reach our staging point is computed again with corner cuts (a
     * diagonal step needs only the diagonal cell open, as in the engine's pathfinder; our fields also want both
     * straight cells beside it open), and the attack marches, measures progress and judges the region dead on that
     * field (Military.setTarget). The engine walks units through a corner cut, so a pocket joined to the map only by
     * one looked sealed: the army stalled 75 s after each launch ("staging cut off") and skipped the region, 24 to
     * 102 times a game, in 6 ludicrous timeouts that were all won positions (s6206 N=6, s6657 x4 and s8462 N=13).
     * Default since the ludicrous timeout screens (late.py on the games alive at 40 min): with sealed_progress and
     * dead_region_reach 4, wins +1 / -0 over 654 games of the cand-tsf bases at N=13-15 (s6657 draw -> win at 74 min).
     */
    boolean corner_fields = true;
    /**
     * Cells around a building's centre that the dead-region test looks at (Military.inDeadRegion; units: 2). A
     * quarters' or armory's 7 x 7 footprint covers every cell within 2 of its centre, and no field but its own reaches
     * into it, so at 2 a dead region never excluded the other buildings in it and the army cycled over them (s6657
     * N=13: s1's and s4's armories and quarters for 300 min). 2: as before; 4 reaches the ring around the footprint,
     * as chooseTarget's path test does. A building is tested no farther than the ring around its own footprint
     * (placing size - 1, at least 2): 4 for a quarters or armory, 2 for a tower, whose 3 x 3 footprint has its ring at
     * 2, so that a tower 2-3 cells past it, across a thin wall from a dead pocket, stays a target.
     */
    int dead_region_reach = 4;
    /**
     * sealed_progress: while no attacking unit stands where the target's field reaches (no pivot, as for a target in a
     * pocket the field calls sealed), a 20 m gain in straight-line distance from the army's centre to the target counts
     * as progress (Military.attack). Without it such a march can never progress and stalls 75 s after launch, far
     * from the target (s8462 N=13: 23 stalls, each ~85 cells short of a lone chieftain in a 15-cell pocket); with it
     * the army walks up to the pocket, where the target may be in throw reach, before the stall.
     */
    boolean sealed_progress = true;
    /**
     * corner_fields, dead_region_reach and sealed_progress act only from this game tick (0: from the start), so games
     * stay identical up to it (late.py with T = 2400 s).
     */
    float pocket_from_ticks = 120000f; // 40 min
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
    /**
     * deny_rebuild: a quarters or armory site of a homeless copy (no finished quarters or armory) within
     * deny_rebuild_cells of the army or staging point is target choice's first pick (Military.rebuildSite), before
     * finishing or the campaign. 27 % of the copies left homeless rebuilt one (cur13 0/10/10 bases, 6,385 cases, a
     * median 10.5 min later), and a rebuilt copy killed 2.24 of our units a minute against 0.51 while homeless (2.51
     * with its first base); 64 % of them fielded a chieftain again. A site has few hit points and only the copy's band
     * by it.
     */
    boolean deny_rebuild = false;
    /**
     * late_caution (1 = off): from late_from_ticks, and while at most late_copies copies are still in (0: any number),
     * every caution threshold of the attack is scaled at once (Military.lateCaution): the muster gate's ratios (attack,
     * max strength, capped) and the threat at the base that keeps the army home are multiplied by it and divided by
     * it respectively, the turn-back before contact and the worn retreat by it, the outmatched retreat divided by it.
     * At .5 the army goes against twice the defense and turns back only twice as outmatched. The thresholds were set
     * where the opening and the collapse window decide; late, with copies out and the map emptying, they kept the
     * army home or turned it back (the fighting-at-the-cap and quiet-standoff timeouts).
     */
    // .5 since cur14: on E's 375 games alive at 40 min (N=13, 0/10/10) +13 / -9, on cand14's 500 games W 230 -> 234
    float late_caution = .5f;
    float late_from_ticks = 120000f; // 40 min
    int late_copies = 0;
    int deny_rebuild_cells = 150;
    int finish_units = -1;
    float finish_ratio = 0f;
    /**
     * remnant_ladder_ticks (game ticks, 0 = off; arm 120000 = 40 min): from this game tick, when the muster's best
     * target fails the attack gate, or no building is left to attack (the nearest enemy unit, or nothing), the gate
     * is tried on a ladder of other targets, and the first that passes is attacked (Military.walkLadder): the other
     * buildings target choice scored, by their score; then the quarters and armory sites of homeless copies (no
     * finished quarters or armory; never a frozen copy's armory site, Freeze.isFrozenSite); then one remnant of each
     * homeless copy still in (more than 8 units or an active chieftain, or any unit while a frozen site keeps it in),
     * its chieftain if it has one, lone chieftains included, else its unit nearest the centre of its units. Sites and
     * remnants rank by meters from the staging point plus target_defense_weight per unit of defense, and remnants with
     * no other copy's warrior or chieftain within 20 cells come first, so that one parked blob fights at a time;
     * frozen_last and wedge-memory buildings stay last, and so do sites and remnants whose way from the staging point
     * runs through a remembered wedge (stall_engaged_ticks). After a remnant or site of the ladder falls, the attack
     * goes on to the same copy's next remnant until it is out (8 units or fewer and no chieftain; a frozen copy:
     * none): its chieftain, else its unit nearest the army, as long as that stands within finish_range cells of the
     * army (remnant_chain_far: else the chain ends, since it is never gated again and a scattered copy would march the
     * army back and forth across the map while the base stood unguarded). Only
     * the single best building was weighed, so one guarded base hid every cheap out: s6074 (N=13, 10 arms) idled 266
     * min at home with 198 warriors against six remnants and one copy guarded by a wedged mass; across the 87
     * ludicrous timeouts remnants kept 169 copies in (17 lone chieftains, 54 homeless copies with a chieftain, 63 with
     * more than 8 units, 35 copies by a site).
     */
    float remnant_ladder_ticks = 0f;
    /**
     * mopup: from endgame_from_ticks, while no copy still in has a quarters or armory, finished or placed (a frozen
     * copy's armory site, Freeze.isFrozenSite, does not count), the remnant ladder runs as with remnant_ladder_ticks
     * (Military.mopUp). The copies left then cannot train a chieftain, so they never send a wave of 20 or more again,
     * and cannot replace a unit, so finishing them costs nothing; with bases still standing the ladder traded wins
     * (+13 / -12 over 654 games alive at 40 min). 12 of the 93 distinct ludicrous timeouts ended with only such bands
     * left, most of them won positions (margin +0.8 to +0.96).
     */
    boolean mopup = false;
    /**
     * allin_ticks (game ticks, 0 = off; arm 540000 = 180 min): from this game tick, on a frozen board (no copy out and
     * no threat at our base, threat level 2, for allin_quiet_ticks) the muster goes for the copy base (a quarters or
     * armory, finished or placed) with the least defense, by target_defense_weight per unit of defense plus meters from
     * the staging point, past the gate, and that attack neither turns back before contact nor retreats outmatched
     * (Military.allIn). A draw counts as a loss, so on a board that has not moved for an hour there is nothing to keep:
     * 20 of the 93 distinct ludicrous timeouts were such standoffs (no kill in their last hour; nothing changed from
     * 180 to 360 min in 18), most with the army at home and the gate failing for hours (s6074 N=13: by 3 % for 4.4 h).
     */
    float allin_ticks = 0f;
    /** allin_ticks: game ticks without a copy going out and without a threat at our base that make the board frozen. */
    float allin_quiet_ticks = 180000f; // 60 min
    /**
     * push_ticks (game ticks, 0 = off): from this game tick, once no copy has gone out for push_quiet_ticks, the all-in
     * muster of allin_ticks (the weakest copy base, past the gate, no turning back) goes whatever stands at our base,
     * at most once per push_period_ticks (Military.pushDue). The collapse-window campaign is the only push: in 56 of
     * the 93 distinct ludicrous timeouts the copies still fought at the cap to the end (about 1,000 deaths an hour on
     * each side) while our army, held home by the threat at the base (plan: considerAttack only below threat level 2
     * or against a small threat), never went out again.
     */
    float push_ticks = 0f;
    float push_quiet_ticks = 90000f; // 30 min
    float push_period_ticks = 90000f; // 30 min
    /**
     * push_calm: a push musters only while no threat stands at our base (threat level below 2). Over 1,082 games alive
     * at 40 min the push gained 5 wins and lost 8; 7 of the 8 lost had a push under threat, 3 of the 5 gained none.
     */
    boolean push_calm = false;
    /**
     * push_soft: a push is the usual muster decision (considerAttack: target choice, the gate, the usual retreats), let
     * run under a threat at our base too, instead of the all-in muster past the gate: a retrigger for sending the army
     * out again rather than defending at home for hours.
     */
    boolean push_soft = false;
    /**
     * last_stand: from endgame_from_ticks, once we have had no finished quarters or armory for last_stand_ticks, every
     * unit of ours outside a building (warriors, the chieftain, peons) attacks the nearest enemy, again every 30 s, and
     * the economy, the shepherds, decoys and freeze stand down (GauntletAI.think, Military.lastStand). Such a game is
     * lost (we never rebuild without a base), and the collapse rule (8 units or fewer, no chieftain, no quarters or
     * armory built or started) cannot end it while more survive or an unbuilt site stands: s6274 (cur11 N=17) kept 86
     * homeless warriors to the 360 min limit, a tenth of its block's compute; s6215 (cur12 N=6) 6 peons and 3 tower
     * gunners, s8389 (cur12 N=15) a peon, a gunner and a site nobody built.
     */
    boolean last_stand = false;
    /**
     * enter_move: orders that send our units into a finished building of ours (shelter, a quarters to breed or train
     * in, home) use MOVE instead of DEFAULT, under which a peon repairs a damaged quarters or tower rather than
     * entering it (GauntletAI.enterAction). Peons sheltering in a quarters under attack then stood outside it as
     * repairers: s6689 (N=13) had 60-69 peons blocked 40 cells from a quarters they all were "repairing" for 10+
     * min, s6129 (N=14) a column of them for 10 min; such jams cost up to 45 % of all peon time in the worst games
     * (median 1 %). Repairs stay manageRepairs' (at most 4 a building, never under threat). On since cur14 (the user's
     * retune goal: no repairs by accident); alone at N=13 on 0/10/10 it cost wins (219 -> 202 of 500), which
     * repair_swarm, salvage and shelter_reach more than win back.
     */
    boolean enter_move = true;
    /**
     * repair_swarm (RepairSwarm, design-2 A): manageRepairs never repairs under threat and sends at most 4 tree
     * gatherers (who walk for wood), so a quarters or armory under attack falls at the attackers' pace, with the units
     * inside. The swarm gives each damaged quarters, armory and tower as many repairers as hold its damage,
     * swarm_margin x the damage rate (Siege: measured, or modelled from the enemy warriors in reach) plus the missing
     * hit points over swarm_catchup_ticks, at most swarm_ring_qa of the 32 cells around a quarters or armory and
     * swarm_ring_tower of the 16 around a tower (repairers block the way; the rest stays free for spawns, entries and
     * a gunner), and at most swarm_peon_share of our peons in all (at least 8). Only peons carrying wood repair (a
     * piece is 5 HP at
     * 1 HP/s): carriers nearby; wood transporters out of the armory itself, which keeps 6 workers, its wood reserve
     * (swarm_wood_reserve and two per iron in stock under attack, swarm_cold_reserve otherwise) and a bank a sortie
     * could win with (swarm_lee: they walk to the side away from the attackers first); or out of a quiet armory within
     * swarm_reach cells; and tree gatherers only with a tree within 6 cells. A spent repairer walks into an armory to
     * be sent out again. swarm_cold: damaged buildings with no threat too (else manageRepairs keeps them). A building
     * it cannot hold (damage above its ring, falling, below half) is given up for 20 s. Forces enter_move's MOVE: with
     * DEFAULT, peons sheltering in a damaged quarters stood outside it as unfed repairers. On since cur14: with
     * enter_move at N=13 on 0/10/10, W 202 -> 219 of 500 (z 1.7, surv60 +1.1 min, z 2.8); with salvage 228.
     */
    boolean repair_swarm = true;
    /**
     * swarm_default: repair_swarm without its MOVE semantics: orders into our buildings keep DEFAULT, under which a
     * peon
     * sent into a damaged quarters or tower repairs it (the accidental repairs, which enter_move alone showed were
     * worth about 6 points of wins at N=13 on 0/10/10), and the swarm adds its deliberate repairs on top.
     */
    boolean swarm_default = false;
    /**
     * shelter_repair: an order into a damaged building of ours (quarters, armory, tower) with an enemy within 15 cells
     * repairs it from outside (GATHER_REPAIR) instead of entering it (GauntletAI.enterAction): the deliberate form of
     * what the DEFAULT right-click did by accident to quarters and towers (enter_move alone, which removes that, lost
     * about 6 points of wins at N=13 on 0/10/10), now for armories too, whose occupants vanish when it falls (26 a
     * razing on average). Meant with enter_move=true, the retune's baseline (no accidental repairs).
     */
    boolean shelter_repair = false;
    /**
     * quarters_sortie: a quarters holding quarters_sortie_min or more sorties like an armory (Military.sorties: its
     * peons out onto the attackers when with the defence they reach sortie_ratio of the threat the defence alone does
     * not hold), instead of keeping them in to vanish if it falls (manageQuarters holds a threatened quarters' peons).
     */
    boolean quarters_sortie = false;
    int quarters_sortie_min = 8;
    /**
     * shelter_reach: evacuatePeons shelters peons only in a building with no threat within this many cells (6 before;
     * attackers hit a quarters or armory from up to ~12 cells, so a shelter with none within 6 is often under fire).
     * 15 since cur14: with enter_move and repair_swarm at N=13 on 0/10/10, W 219 -> 236 of 500 (z 1.6; 10: 225, 20:
     * 234), fewer quarters and armories razed (3.81 / 1.80 a game against 4.02 / 1.95); shelter_off: 182 (z -3.4).
     */
    int shelter_reach = 15;
    /**
     * shelter_off: evacuatePeons shelters nobody: threatened peons keep at their work (repairs, salvage, sorties
     * apart).
     */
    boolean shelter_off = false;
    boolean swarm_cold = true;
    // The design's values (cur14). The aggressive swarm (margin 1.5, rings 28 / 12, share .7, wood reserve 2; the user's
    // guess that aggressive repairing is best) won at N=13 (230 -> 249 of 500, z 2.1) but lost at N=14 (131 -> 114,
    // z -2.2) and tied at N=16-17 (with late_caution .5: 21 + 7 against these values' 21 + 9, whose shared wins came
    // a median 74-83 min instead of 118-128).
    float swarm_margin = 1.25f;
    float swarm_catchup_ticks = 1000f; // 20 s
    int swarm_ring_qa = 20;
    int swarm_ring_tower = 10;
    float swarm_peon_share = .5f;
    int swarm_reach = 40;
    int swarm_wood_reserve = 6;
    int swarm_cold_reserve = 20;
    boolean swarm_lee = true;
    /**
     * salvage (Economy.salvage, design-2 B): units inside a razed building vanish with it, uncounted (13 per quarters
     * razing with enter_move, a median 15 per armory). A quarters or armory holding salvage_min or more (inside and
     * queued) with salvage_attackers enemy warriors within 12 cells is emptied once it would fall (Siege.timeToFall,
     * approaching warriors at salvage_approach_weight) before everyone is out plus salvage_margin_ticks, the threat
     * within 15 cells is salvage_ratio x its defence (at least 1) or more, and no sortie could win with its bank. Each
     * unit comes out with a rally on our nearest armory, else quarters (undamaged), within salvage_reach cells with no
     * threat within salvage_safe and at least 8 cells farther from the threat, and walks in without scanning; into an
     * armory the stock goes along as transporters. It ends when the building falls, after salvage_calm_ticks with no
     * threat within 15 cells, or after 120 s; a quarters that stood takes free peons back. The old evacuate fired on HP
     * alone, too late, and let its evacuees out idle by the door or into the attackers. On since cur14: with enter_move
     * and repair_swarm at N=13 on 0/10/10, W 219 -> 228 of 500 (surv60 +1.06 min, z 3.7; units lost a razed armory 31
     * -> 20).
     */
    boolean salvage = true;
    float salvage_ratio = 2f;
    float salvage_margin_ticks = 300f; // 6 s
    int salvage_min = 4;
    int salvage_attackers = 2;
    float salvage_approach_weight = .5f;
    int salvage_safe = 20;
    int salvage_reach = 90;
    float salvage_calm_ticks = 1000f; // 20 s
    float last_stand_ticks = 15000f; // 300 s
    /**
     * mopup and last_stand act only from this game tick (0: from the start; 120000 keeps games identical to 40 min).
     */
    float endgame_from_ticks = 120000f; // 40 min
    /**
     * With gate_owner, other copies' warriors and chieftains count in a target's defense, and their chieftains in the
     * gate's chieftain malus, only within this many cells (0: defense_radius, as before; arm 20), from
     * defense_others_from_ticks on (Military.defenseFor). A copy defends with its own warriors only
     * (AdvancedAI.nodeDefendBase); another copy's units join only through their 8-cell scans. s6074's last base read
     * 284.7 with a wedged mass of four copies' 309 warriors 14-60 cells off, about 44 without it, and the capped gate
     * failed it by 3 % for 4.4 hours.
     */
    int defense_others_radius = 0;
    float defense_others_from_ticks = 120000f; // 40 min
    /**
     * chief_hunt: a squad of chief_hunt_size iron warriors kills the chieftain of a copy with no finished quarters or
     * armory and at most 8 other units (it is then out); hunt_sites: its quarters/armory sites too. Target within
     * chief_hunt_range cells, at most chief_hunt_escort enemy warriors within 15 cells, no enemy tower within 22; the
     * squad gives up after chief_hunt_ticks game ticks or when outmatched (Military.considerChase). Six never set off a
     * Viking blast (7 of our selectables within 18 cells).
     */
    boolean chief_hunt = false;
    boolean hunt_sites = false;
    int chief_hunt_size = 6;
    int chief_hunt_range = 150;
    int chief_hunt_escort = 3;
    float chief_hunt_ticks = 4500f; // 90 s
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
     * N=10 with the same weight via an armory threat weight of 37 s: W 6 vs 2 of 200; N=8 W 65 vs 65).
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
    /** Game ticks before the next attack after the army was called home (20 s after any other attack end). */
    float recall_cooldown_ticks = 1000f; // 20 s
    /**
     * The worn retreat (Military.attack): the army turns back once it is worth less than worn_ratio of worn_basis and
     * the enemy around it more than the army. 0: the launch strength plus every reinforcement that ever joined (1.31 x
     * the army's own peak in a median attack); 1: the peak of the army's strength since the launch; 2: its peak over
     * the last worn_window_ticks game ticks.
     */
    int worn_basis = 0;
    float worn_window_ticks = 6000f; // 120 s
    float worn_ratio = .2f;
    /**
     * Enemy stun fear (Military.enemyThreatReady): an enemy chieftain counts as ready to stun
     * enemy_spell_recharge_ticks game ticks after he was seen casting, and with enemy_first_seen only that long after
     * he was first seen (newborns start with no charge); ready ones near a fight multiply its enemy by enemy_stun_mult,
     * and at 1 or less no longer veto a charge on stunned enemies. Our own stun timing keeps the 40 s
     * (Chieftain.shouldStun).
     */
    float enemy_stun_mult = 1.5f;
    float enemy_spell_recharge_ticks = 2000f; // 40 s
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
    /**
     * The early-rush alarm's hold cut (hold 2 in each quarters, so peons go out to gather and arm) waits for our first
     * finished armory: before it, a cut peon has nowhere to go and stands at the quarters door (beta tester; 20-90 door
     * peons per alarm before the armory in logged ludicrous games).
     */
    boolean rush_hold_armory = false;
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
     * Rock stream from measured yields (Economy.computeGatherTargets): from rock_stream_ticks, above
     * rock_stream_iron_ticks gatherer-ticks per unit of iron.
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
    float rock_stream_ticks = 27000f; // 540 s
    float rock_stream_iron_ticks = 3500f; // 70 s
    int rock_stream_max = 30;
    int site_max = 3;
    int rock_filler_div = 10;
    int rock_filler_min_workers = 14;
    int rock_filler_stock = 20;
    int lure_max = 2;
    int lure_min = 3;
    int lure_range = 45;
    float lure_ticks = 21000f; // 420 s
    /**
     * From tower_parallel_late_ticks on: tower projects and placed sites at a time (the siege razes towers). 2 and 3
     * (were 1 and 2): two placed sites shared with quarters were the real bound on tower completions in the collapse
     * window (tower13 audit), so more towers stand; towers20 +0.7 to +1.3 and surv60 +0.3 to +1.3 in every one of 11
     * blocks at N=12-14; wins on 1,200 fresh seeds N=13 0 -> 3, N=14 1 -> 2; N=12 over 400 W 15 -> 13.
     */
    int tower_parallel_late = 2;
    int sites_parallel_late = 3;
    float tower_parallel_late_ticks = 30000f; // 600 s
    /**
     * From tower_parallel_late_ticks on, a quarters project may not take the last free construction-site slot while a
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
     * rush_ticks game ticks.
     */
    boolean rush_response = true;
    int rush_quarters = 2;
    float rush_ticks = 12000f; // 240 s
    /**
     * Until pressure_ticks, while enemies in the base outnumber our warriors, keep gathering away from the fighting
     * instead of hiding while the armory starves.
     */
    boolean pressure_response = true;
    float pressure_ticks = 36000f; // 720 s

    /** Peons to keep inside each quarters to speed up reproduction, early and later in the game. */
    int hold_early = 4;
    /**
     * hold_mid 10 (was 14): peons wait in quarters while the armory has ore for them; vs hard*11 elim +.013 / +.030
     * (hm10-vs11-hv, -b), W 16 vs 11 over 400; N=8 neutral (W 114 vs 111).
     */
    int hold_mid = 10;
    int hold_late = 8;
    float hold_mid_ticks = 12000f; // 240 s
    /**
     * seed_quarters_ticks (game ticks, 0 = off): once the first armory stands, a quarters finished less than this long
     * ago takes its hold from idle peons within seed_quarters_reach cells (the builders at its door) instead of
     * breeding it up from empty: breeding is n^(1/3) / 11 a second with an empty quarters counted as 0.5, so filling 0
     * -> 10 takes ~77 s and a seeded quarters breeds ~5 more peons meanwhile. Before the first armory, idle peons
     * already fill quarters below their hold.
     */
    float seed_quarters_ticks = 0f;
    int seed_quarters_reach = 12;
    /**
     * While the main armory could forge at least hold_backlog weapons (0 = off), quarters hold only hold_early: a held
     * peon above 4 buys ~8 peons per 1000 s, a worker with ore ~12.5 weapons (Economy.holdFor). Off again once 1 or
     * fewer can be forged and 30 s have passed; hold_backlog_until_ticks > 0 limits it to the early game.
     */
    int hold_backlog = 0;
    float hold_backlog_until_ticks = 0f;
    /**
     * veto_resite (veto_resite_ticks game ticks, 0 = off; late/spec S1): from veto_resite_from_ticks, a tower project
     * that projectMayStart has vetoed for a threat near its site this long moves to the nearest site with no threat
     * within veto_resite_clear cells, or is dropped and tower planning pauses for veto_resite_ticks
     * (Economy.manageProjects): one vetoed project stopped all tower planning for 225-794 s in 14 of 16 logged N=12
     * games while the standing towers fell.
     */
    // Adopted 20 s (2026-09-29): survival up at every N (surv60 +1.0 to +1.9 min, z 2.2-4.4; towers at 20 min +1.3 to
    // +1.8, z 6-8; N=11 W 15 -> 19; veto-resite-vs11/12/13-hv, -vs12-hv-b).
    float veto_resite_ticks = 1000f; // 20 s
    float veto_resite_from_ticks = 30000f; // 600 s
    int veto_resite_clear = 20;
    /** The same for quarters projects (the second arm of veto_resite). */
    boolean veto_resite_quarters = false;
    /**
     * unjam (units, 0 = off; arm 8): when the Jams scan finds at least this many attack units blocked (walking but on
     * the same cell as 5 s before) on every scan for unjam_after_ticks game ticks, with no enemy warrior, chieftain or
     * tower within 30 cells of them, while the army's pivot got less than unjam_progress m closer to the target, the
     * attack marches as a column until unjam_ticks game ticks after the last jammed scan: no pivot hold, and every unit
     * walks towards its own point `lead` meters on along the target field (the front at most two leads past the pivot)
     * instead of the pivot's waypoint (Military.noteBlocked, Military.attack). s97 and s98 at N=11 (lab note
     * 2026-09-29, jam pictures): the first units through a 1-3-cell pass reach their spread cells at its exit and stand
     * idle; the engine's pathfinder treats idle and blocked units as walls, so the column behind them blocks, the pivot
     * in it never moves the waypoint on, and the idle plug, "already there" and ahead of the pivot, is never
     * re-ordered.
     */
    int unjam = 8;
    float unjam_after_ticks = 750f; // 15 s
    int unjam_progress = 10;
    float unjam_ticks = 1500f; // 30 s
    /**
     * unjam acts only from this game time (game ticks). 0 since cur13 (cur8 to cur12: 2400 s, so the late screens
     * stayed
     * identical to 40 min): from the start it fired in 6-11 % of games at N=13-15 (81 of 103 column marches got
     * through),
     * wins +4 / -2 over 1,424 games (1,362 identical), and it ended s6274 (cur11 N=17: the army jammed in a 1-cell
     * canyon from 22 min, a 360 min draw) as a loss at 34 min. On the cur7 benchmark games alive at 40 min the late
     * version changed nothing but s6028 (N=13, 225 warriors wedged in a cliff pocket: draw at 360 -> win).
     */
    float unjam_from_ticks = 0f;
    /**
     * bank_guard (late/spec S2): from bank_guard_ticks the main armory keeps only the workers its measured iron income
     * and stock can keep forging (bank_min once it cannot forge for bank_noforge_ticks); the rest wait in the quarters
     * farthest from the threat and come out for builders or when the armory has room again (Economy.guardBank): 105
     * units per game vanish in razed buildings by 20 min at N=12, 42 per armory razing.
     */
    boolean bank_guard = false;
    float bank_guard_ticks = 30000f; // 600 s
    int bank_min = 6;
    float bank_margin = 1.5f;
    float bank_noforge_ticks = 1000f; // 20 s
    /** Most peons bank_guard parks in one quarters above its hold. */
    int bank_reserve_max = 60;
    /**
     * wood_reach (cells, 0 = off; late/spec S3): from wood_reach_ticks, when the main armory's 60-cell tree ring is
     * exhausted (tree cycle >= 90 s) or a 60-cell tree search finds nothing, trees up to this far are gathered
     * (Economy.pickSupply): the wood lock that left ~200 peons idle in the armory in s63, s60 and s315. 150 (cur8; was
     * 0): the lock is 42-56 % of the 1-3-h gaps in the long wins (trees 43-122 cells away in every lock); on the cur7
     * benchmark games alive at 40 min wins 19 -> 26 at N=13-15 with none lost, fresh block N=13-14 12 -> 13, long wins
     * 30-190 min shorter (s6189 319 -> 113 min, s6022 305 -> 117).
     */
    int wood_reach = 150;
    float wood_reach_ticks = 120000f; // 2400 s
    /**
     * ore_reach (cells, 0 = off): when the ore the weapons need has none within the main armory's 400 m walking field
     * (rock axes when iron is far, and no rock there either, or iron gone), gatherers walk to iron, else rock, up to
     * 2 x ore_reach m away instead of waiting: with every ore beyond 400 m the armory forged nothing and its 100-200
     * workers sat idle while one Hard out-built us (all four N=1-2 losses of low-vs1/vs2).
     */
    int ore_reach = 400;
    /**
     * gather_probe: armory workers are deployed for gathering only when a supply of the kind can be picked, and a peon
     * whose pick fails goes on to its next use instead of standing where it is (the play test's peons walking out of
     * the armory and straight back in, and peons standing still in the base).
     */
    boolean gather_probe = true;
    /**
     * gather_home: a gatherer the engine would send to another armory than the main one (a supply nearer an old
     * armory) is given another supply; the old armory's supply is left alone for 60 s.
     */
    boolean gather_home = false;
    /**
     * A gatherer the engine links to a finished secondary armory (it delivers to the supply's nearest armory) is not
     * recalled by drainSecondary for 30 s, and counts as a gatherer of its supply meanwhile: the 10-s recall of a
     * secondary armory's gatherers fought every such re-link (recall loop in 11 of 30 logged N=6 ludicrous games).
     */
    boolean relink_guard = false;
    /**
     * ore_reach: when rock weapons are wanted but no iron is left within the 400 m field, look for iron in the far
     * field (2 x ore_reach) before settling on rock (rock-only stretches in 73 of 500 ludicrous N=13 games; 3 of the 4
     * logged N=6 losses were iron-window cases).
     */
    boolean ore_iron_first = false;
    /**
     * ore_reach: a near pick that finds nothing (for any reason, unless a threat or a parked enemy blob is by some of
     * that ore) tries the far field too, as an ore_far plan tick does (failed near picks in 41 of 500 ludicrous N=13
     * games). Meant on top of relink_guard (with ore_iron_first too): far ore often lies nearer an old armory, the
     * engine links its gatherers there and the recall loop eats their trips (beta2-ore smoke without it: twice the
     * recalls of the base game).
     */
    boolean ore_reach_fail = false;
    /**
     * From weapon_reserve_ticks, while the base threat is below 2, the primary armory keeps up to this many weapons in
     * stock undeployed (iron first) with workers for them, released at threat 2: iron-made defenders for the collapse
     * window (0.18 weapons in stock at its onset at N=13). 0: off. home_guard, which held warriors back, cost wins (N=8
     * W 16 -> 5); this holds weapons, not deployed warriors.
     */
    int weapon_reserve = 0;
    float weapon_reserve_ticks = 30000f;
    /**
     * A besieged armory sends its peons out to fight when they can win: at threat level 2, an armory holding 30 or more
     * with a threat within 15 cells, when bank, defenders and towers near it reach sortie_ratio of the threat within 30
     * cells and defenders and towers alone do not, deploys its peons onto the attackers (they vanish uncounted inside a
     * razed armory: ~112 units per ludicrous N=13 game; the bank could win in about 14 % of falls), until the threat
     * within 15 cells is gone, they fall below half that ratio of it, or the defence alone holds (Military.sorties). 0:
     * off.
     */
    float sortie_ratio = 1f; // cur11 (see forGame); alone at N=13: W +11 over 800 games, surv60 up in every block
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
     * reloc (expand/critique #2, the hop): from reloc_ticks, the expansion check no longer needs a quiet base and a
     * lone armory. With no armory site or project, a primary armory and every other armory drained (not primary, nobody
     * inside, iron + rock <= 1, no gatherers linked, not evacuating), every 30 s and reloc_gap_ticks game ticks after
     * the last expansion ended (completed or dropped), Economy.considerRelocation moves the armory when the current one
     * is poor (iron cycle >= 70 s or cost >= 110, the expansion rule) or has fewer than reloc_nodes live iron nodes
     * within 30 cells, to the best site within reloc_reach m of the primary (a Search reused for a minute; reloc_reach
     * above 400 computes a field per check) that is quiet (quietOk), at least 40 cells from it (tested in the pass over
     * every cell, so the candidates by the primary take none of the 200 rejections), with at least reloc_nodes live
     * iron nodes within 30 cells (the verify smokes moved to sites with 0 nodes against 0 on the cost ratio alone) and
     * no enemy warrior within 12 cells of the straight way, and costs at most 0.75 of the current armory (0.9 from
     * desperate_iron_cycle_ticks). One armory project at a time; a hop project unplaced for 90 s (with reloc_slot: 90 s
     * with a slot open) is dropped. Its builders may come out of the primary above want_workers + 5. Once the hop
     * falls, the primary is chosen once (no threat within 16 cells, lowest gathering cost, newest on ties) and switched
     * only after 20 s with a threat within 16 and at least 60 s after the last switch, since every switch recalls the
     * old armory's gatherers. Why: the global threat gate stopped 100 % of the one-armory checks after 13 min at N=14,
     * the two-armory gate 66 % of 8-13-min plan ticks (stall.md); the expansion mines out its 25-cell pile 2-6 min
     * after completion (34 -> 3 -> 0 loads) while a site 40-80 cells deeper holds a median 158 loads within 30 cells
     * (critique hop.py), and expansion=false cost surv60 -1.3 min (z -3.0).
     */
    boolean reloc = false;
    float reloc_ticks = 30000f; // 600 s
    float reloc_gap_ticks = 6000f; // 120 s
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
     * raid_bank (expand/critique #4, D3): from raid_bank_ticks, a forward primary armory (not the finished armory
     * nearest our start, Economy.homeArmory) keeps only the workers its measured iron income can keep forging
     * (bank_margin x income x 80 s a weapon) plus a backlog of min(raid_bank_extra, its iron, + half its rock while
     * rock axes are made), bank_min once it has been unable to forge for bank_noforge_ticks; the rest wait in the
     * quarters farthest from the threat (bank_guard's machinery: Economy.guardBank, reserveQuarters, the reserve kept
     * above the quarters' hold). The expansion was razed in all 850 of 1,000 N=14 games that built it, a median 6.0 min
     * after completion, and our units dropped a median 60 in that census step with 81 inside just before (raze.md);
     * drainSecondary moves the home armory's bank into the forward one as soon as the home one cannot forge, and each
     * hop (reloc) does it again. raid_bank_extra is bank_guard's fixed backlog of 12 as a param (the dry-spell judge's
     * caveat: 12 keeps an armory small when wood comes back to a full iron bank). Arm raid_bank_ticks=0: the first
     * expansion from its completion (~7-8 min) too.
     */
    boolean raid_bank = false;
    float raid_bank_ticks = 30000f; // 600 s
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
     * of ours holding at least raid_evac_min units (from raid_evac_ticks), and the wave's strength is at least
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
     * raid_evac_ticks (arms 39000 = 780 s, and 120000 = 2400 s for the late track, where an arm must not act before
     * 40 min): no armory is emptied before this game tick (0 = from the start). An evacuation stops the armory's forge
     * and every gatherer sent for it for 60 s: in the verify smoke (8 N=14 games) raid_evac alone cut our iron at
     * 8-13 min 833 -> 674, and 10 of its 12 evacuations before 13 min were false alarms (the armory stood), against 6
     * of the 12 later ones, which fell with up to 115 inside; the big falls come late (39 of 53 long losses lost 100+
     * units in one armory razing, 34 of them after 40 min, dryspell judge).
     */
    float raid_evac_ticks = 0f;
    /**
     * reloc_lock (reloc_lock_ticks held, 0 = off; arm 6000 = 120 s; expand/critique #8, dryspell/judge.md fix 3): from
     * reloc_lock_from_ticks, once the wood lock has held this long (Economy.trackLock: the primary armory's tree cycle
     * >= 90 s, i.e. no usable tree within its 60-cell ring, its wood < 2 and its workers >= want_workers + 20), the
     * armory moves to trees (Economy.lockRelocate): with no armory site or project, and no global threat gate, the best
     * armory site by gathering cost (2 x tree + iron: the banked iron stays behind) within reloc_reach m that is quiet
     * (quietOk: no threat and no enemy within 30 cells, no razing of ours within 25 in 180 s) and has no enemy warrior
     * within 12 cells of the straight way, both tested down the ranked candidates, and a tree cycle of its own under
     * 60 s; after a miss it looks again in 10 s (the search reused for a minute). The project is added at the
     * 20-building cap too: its placer waits for a slot, and while it waits unplaced with the count at the cap less one
     * no tower is planned or started (reloc_slot's reserve). An unplaced lock project whose slot has stood open for
     * 90 s is dropped and planned afresh. Its placer is chosen by rearm_placer's safe rule (and left out of
     * evacuatePeons while no threat is within 6 cells) with rearm_placer off too: in the s6415 smoke 4 of 8 lock
     * projects were dropped after 7 placer failures in 7-15 s. Its builders may come out of the locked armory above
     * want_workers + 5. Once it stands (and becomes primary), the locked armory keeps its workers inside until the new
     * one holds 2 wood and has no threat within 16 cells, 120 s at most. Why: the lock was 42 % of the gap minutes of
     * the long wins (150-200 peons waiting inside, iron at the 200 cap, 1.4-1.6 warriors/min against 9.5-13), usable
     * trees stood 43-122 cells away in every lock, moving was blocked in 96-100 % of locked minutes by the threat gate
     * and the cap (55-88 %), and wood reaching an armory again ended 7 of 12 long locks, the first out ~12 min later.
     */
    int reloc_lock_ticks = 0;
    float reloc_lock_from_ticks = 120000f; // 2400 s
    /**
     * retire (expand/critique #8, D5 retire; quarters.md, the skeptic's narrow case): when a flagged armory project (a
     * reloc hop, which waits at the cap only with reloc_slot, or a reloc_lock move) has waited unplaced
     * retire_wait_ticks at the 20-building cap, one slot is freed by razing a building of ours with the explicit attack
     * order (the attack button and a click on it), at most one every 120 s, taking the first of: a stalled site
     * (placed, no builders, 120 s old); a stranded tower (no quarters or armory within 25 cells; its gunner out first,
     * 4-8 peons at 3 HP/s each); a quiet drained armory (not primary, nobody inside, no stock or gatherers, no threat
     * within 30); a far quarters (more than retire_quarters_dist cells from the main armory, units >= retire_pop so
     * breeding is off, another quarters within 25 cells, emptied first, not training the chieftain). Never a besieged
     * building (a threat within 20 cells, 30 for an armory; a razing is called off when one comes), and it is called
     * off too once the slot is not wanted (the project placed or dropped, or a slot freed another way) or the building
     * has become our main or last armory or our last quarters. Quarters and armories go down to up to 12 idle iron or
     * chicken warriors the military lends (0.75 HP/s each, 10 for 200 HP) when 8 are at hand, a quarters else to up to
     * 20 peons (1 HP a swing on a 20 % roll, 0.1 HP/s each: ~100 s for 200 HP; an armory never, D5). The building is
     * doomed meanwhile: no tower manning, no peons sent in, no repairs, never primary, and no armory site within 12
     * cells of it for 60 s after. Why: the cap blocks 55-88 % of wood-locked minutes and s6189's new armory waited
     * 112 min for a slot (dryspell judge); the slot frees on the tick of the razing, and our own AI fought the test
     * razings (18 of 19 gunners died in their tower, quarters refilled, repairers stayed on; raze.md). Far quarters
     * hold a slot at the cap 8.6-10.3 min in wins, 9.5 of them above 187 units in the N=13 wins (quarters.md skeptic).
     */
    boolean retire = false;
    float retire_wait_ticks = 3000f; // 60 s
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
     * the chieftain to meet the enemy alone, for up to blast_play_ticks game ticks.
     */
    boolean blast_defense = false;
    float blast_play_ticks = 700f; // 14 s
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

    /** Chieftain training starts once this many quarters stand and chieftain_ticks game ticks have passed. */
    int chieftain_min_quarters = 3;
    float chieftain_ticks = 16500f; // 330 s

    /**
     * Against several enemies, every other tower covers the building nearest to each enemy in turn, facing him, and
     * one more tower is built per extra enemy: each attacks the building closest to him.
     */
    boolean multi_front_towers = true;
    /** Towers to build next to the armory, early and later, each from its towers_*_ticks game tick on. */
    int towers_early = 1;
    int towers_mid = 3;
    int towers_late = 6;
    float towers_early_ticks = 21000f; // 420 s
    float towers_mid_ticks = 21000f; // 420 s
    float towers_late_ticks = 36000f; // 720 s

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
     * Between ring_sweep_from_ticks and ring_sweep_until_ticks, with no copy out for ring_sweep_quiet_ticks game ticks,
     * an attack army within ring_sweep_reach cells of the armory and worth ring_sweep_ratio times the parked ring (idle
     * enemy warriors within 45 cells of our buildings) comes home, and the home army then takes on the ring's blobs
     * nearest the armory one at a time while the base is quiet, until the ring is down to 30 % or
     * ring_sweep_until_ticks + 60 s (audit13 military 5: 12-15 min is the one window in which the army outnumbers the
     * ring, whose blobs launch 37-41 % of the base-bound waves at 12-20 min; fights near our buildings trade 4.6-5.9:1,
     * abroad 2.1-2.6:1). An attack whose target's owner has fewer than two finished buildings left is not called off.
     */
    boolean ring_sweep = false;
    float ring_sweep_from_ticks = 36000f; // 720 s
    float ring_sweep_until_ticks = 46500f; // 930 s
    float ring_sweep_ratio = 1.5f;
    float ring_sweep_quiet_ticks = 6000f; // 120 s
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
    /** Units in the staging army sent to hunt enemy peons when the enemy army is elsewhere, from raid_ticks on. */
    int raid_size = 5;
    float raid_ticks = 18000f; // 360 s

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
     * by forward_ratio and not before forward_tower_ticks.
     */
    int forward_towers = 0;
    float forward_tower_ticks = 21000f; // 420 s
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
    /**
     * Expand for a smaller gain (this share of the current cost instead of three quarters) once iron is this far: an
     * iron cycle of desperate_iron_cycle_ticks game ticks.
     */
    float desperate_expansion = .9f;
    float desperate_iron_cycle_ticks = 7500f; // 150 s
    /**
     * Look twice as far for the first armory when the best site nearby costs more than this (peon-ticks of gathering
     * per warrior).
     */
    float armory_far_cost_ticks = 8500f; // 170 s

    /**
     * Fight raiding enemy peons with our own peons when no warriors are at hand to do it, until militia_ticks. Off:
     * neighbouring copies' gatherers work near our start and passed for raiders, so the militia sent most of the
     * starting peons after single enemy peons, again and again (vs hard*11 militiaoff-vs11-hv elim +.051, z 4.1, lsr10
     * +.26, z 5.8; 1v1 duel-new-hv 100/100). Hard copies never raid with peons.
     */
    boolean peon_militia = false;
    float militia_ticks = 30000f; // 600 s
    /** Sparring only: send the starting peons at the enemy's peons for the first minutes, as some humans do. */
    boolean peon_rush = false;
    /**
     * The freeze opening (Freeze; archaeology A1, re-scoped from the freeze strike of NOTES 2026-09-28 for N >= 11): at
     * the start freeze_squad starting peons walk to the copy with the least walking time, if it is at most
     * freeze_eta_ticks game ticks of peon walk away (walking distance, so the rule is inert where copies start far
     * apart), and kill its peons before its first quarters stands, which puts it out (no units, no finished quarters).
     * If the quarters stands first, the squad waits outside its defense circle for the armory site and kills its
     * builders, which freezes the copy (never touching the site); freeze_raze then stays to raze the frozen quarters.
     * freeze_squad 6 (was 10): the four peons more at home pay (N=12 over 400 seeds W 8 -> 15, surv60 +1.3 min, z 2.7;
     * freeze-squad6-c3-vs12-hv and -b), 4 fails the strike too often (W 9 -> 2) and 14 starves the opening (W 7 -> 1).
     */
    boolean freeze_open = false;
    int freeze_squad = 6;
    float freeze_eta_ticks = 2000f; // 40 s
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
     * After a path-(a) out the squad strikes once more: the living copy with the least walking time from it, if at most
     * freeze_eta_ticks game ticks away and its quarters is not finished (that strike gives up when its quarters
     * stands).
     */
    boolean freeze_retarget = false;
    /**
     * Path (a) works around a crowd of idle peons: while at least as many of the copy's peons stand idle as the squad
     * has peons (it has not given its orders yet), the squad takes on only its busy peons (builders never fight back)
     * farther than 12 cells from every idle one, and otherwise keeps Freeze.HOLD_CELLS from the nearest idle peon
     * (beyond its 8-cell scan), for up to this many game ticks from when it first came within 24 cells of the crowd;
     * then the plain strike. The stock AI decides on real time, every 20-28 game s at ludicrous speed, so there the
     * squad arrived before the copy's first orders and met 20 idle peons (ludicrous N=13: freeze kills 8.9 per game
     * against 17.6 at normal speed, peons lost 2.6 against 0.7; a hold 16 cells from the centre of all its peons,
     * 1500 ticks, changed nothing: W 65 -> 66 of 500, 199 of 238 held strikes still given up). 0: off. 1500 (30 s; 3000
     * plays the same games): ludicrous N=13 W 65 -> 107 of 500 (z 4.2, surv60 +4.9 min), fresh 8001..8500 59 -> 105 (z
     * 4.8), N=15 5 -> 16 (z 2.5, surv60 +4.8), N=16 5 -> 10 (surv60 +4.2); normal-speed N=13 281 of 300 games
     * identical, 6 -> 7, surv60 +0.46 (z 2.3).
     */
    float freeze_patience_ticks = 1500f;
    /**
     * The attack target's choice leaves frozen copies (Freeze) until no other copy is a candidate: a frozen copy never
     * launches a wave, so its quarters is worth nothing to our survival, while it scores as the easiest target (no
     * priority, no defense) and took our first attack in 35 of 38 N=13 games (audit13 frozen_last).
     */
    boolean frozen_last = false;
    /**
     * The freeze opening strikes this many copies at once, the nearest ones first: each strike after the first takes
     * freeze_squad2 peons (0: freeze_squad) at a copy at most freeze_eta2_ticks game ticks of peon walk away (0:
     * freeze_eta_ticks), and every strike leaves at least freeze_keep starting peons at home.
     */
    int freeze_targets = 1;
    int freeze_squad2 = 0;
    float freeze_eta2_ticks = 0f;
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
     * Game ticks before a warrior re-ordered out of a stun may be re-ordered again: the Expert AI waited 30 s, sweep
     * re-ordered every 5 ticks (+9 points at N=2, lab/sweep NOTES base22 vs s24-nounstun).
     */
    float restore_dodge_gap_ticks = 1500f; // 30 s

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
    /** Give a siege up after this many game ticks without a stun landing on a tower. */
    float siege_patience_ticks = 5000f; // 100 s

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
     * Chickens are few and whoever hunts first gets them: up to chicken_hunters peons hunt from chicken_ticks on, two
     * plus one per chicken_pool_div working peons.
     */
    int chicken_hunters = 7;
    /** Farthest chicken a hunter goes for, in cells from the main armory. */
    int chicken_range = 150;
    /**
     * stall_cap_ticks (game ticks, 0 = off): an attack that gains no 20 m on its target and kills fewer than
     * stall_cap_kills units in that time stalls (target skipped, as a calm stall), even while some of the army fights.
     */
    float stall_cap_ticks = 15000f; // 300 s
    int stall_cap_kills = 10;
    /**
     * retreat_cap_ticks (game ticks, 0 = off; arm 6000 = 120 s): a retreat ends anyway, the army back home to be
     * weighed for the next attack, once for this long no further attacker has come within 14 cells of the staging
     * point and those still out have got no 20 m nearer it on average (walking, Military.retreatCap). A retreat ended
     * only with 70 % of the attackers home, so a way home plugged for good locked the mode, and attacks are weighed
     * only at home: s6409 (N=14), 89 attackers turned back in a dead end whose only way home was a canyon held by an
     * idle blob, RETREAT for 279 min while 136 warriors and 58 weapons waited at home. The attackers a capped retreat
     * leaves out keep walking home, and while one stands more than 30 cells from the staging point it counts neither
     * in the musters' strength and gathering nor in the launches (retreat_stranded_kept, per launch that left some
     * out): counted in, the next attack marched the home army off with the stranded group, its centre between the
     * two, and turned back or was capped again (rep-fix-s6409: three launches within 190 s each, the same group 612 m
     * away every time). It counts again once home (14 cells) or with the army as a reinforcement. Default 6000 since
     * the
     * ludicrous timeout screens: wins +1 / -0 over 654 games alive at 40 min (s6192 N=14 draw -> win).
     */
    float retreat_cap_ticks = 6000f; // 120 s
    /**
     * stall_cap_keep: the calm stall's retarget (75 s with no gain) no longer restarts the stall_cap clock, nor does
     * the first measure on the new target's field; only a 20 m gain, stall_cap_kills kills, a new field after a
     * fallen target, a launch or stall_cap's own first strike do (Military.stalled, Military.attack). Each calm stall
     * restarted it, so a wedged army retargeted every 75 s for hours and stall_cap never struck (s8150 N=14: 169
     * retargets in 222 min; s6036 N=6: 261, both with stall_cap 0). Now strike 1 retargets at 300 s and strike 2
     * walks the army home at 600 s.
     */
    boolean stall_cap_keep = true;
    /** retreat_cap_ticks and stall_cap_keep act only from this game tick (0: from the start). */
    float unlock_from_ticks = 120000f; // 40 min
    /**
     * stall_engaged_ticks (game ticks, 0 = off; arm 15000 = 300 s): a wedge watchdog, first in every attack round, so
     * that no charge, siege, pillage, hold or engage path returns before it looks (Military.wedgeWatch). Progress: a
     * 20 m gain of the march pivot on the target field (of the straight line while no attacker stands on it), the
     * target falling or losing a tenth of its hit points, or stall_cap_kills deaths among the enemy units seen within
     * 30 cells of the army since the last progress (those that came up later count too: another copy's units joining
     * through their scans, late defenders), or among the attackers at it; a new target's field is a new baseline,
     * not progress. After this long without: strike 1 stalls the target (skipped, the next one from where the army
     * stands), strike 2 in a row walks every attacker home and remembers the wedge (wedge_memory_ticks). stall_cap is
     * left out meanwhile: this does its job on every path and counts kills near the army, where stall_cap counted the
     * player's (tower kills at home restarted it). s6215 (N=15): 216 warriors stood 260 min at a 1-cell pass engaging
     * enemies they could not reach, no stall line and no stall_cap (it comes after the engage return), against five
     * copies with 0 warriors. Default 15000 since the ludicrous timeout screens, as stall_cap_keep: no result changed
     * over 654 games alive at 40 min (N=13-15) and 438 more (fresh N=14-15, N=16).
     */
    float stall_engaged_ticks = 15000f; // 300 s
    /**
     * stall_engaged_ticks: game ticks a wedge the army was walked home from is remembered (0: never). Target choice
     * passes over buildings whose way from the staging point runs within 12 cells of a remembered wedge (a detour
     * through it costs at most 24 cells more than the best way, walking with corner cuts, on a field from the staging
     * point computed again every 60 s as trees fall and buildings rise), and takes the best of them only when no
     * other building is left (Military.chooseTarget): otherwise the next muster marches into the same pass (s6021 N=6
     * under stall_cap). The remnant ladder ranks such sites and remnants last too.
     */
    float wedge_memory_ticks = 60000f; // 1200 s
    /** stall_engaged_ticks acts only from this game tick (0: from the start). */
    float stall_engaged_from_ticks = 120000f; // 40 min
    float chicken_ticks = 7500f; // 150 s
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
     * A builder (or repairer) that has stood on the same cell for this many game ticks more than 3 cells from its
     * building is wedged (a dead-end notch or a pass it deadlocks in with others, jam-logs s9: 6-23 builders for
     * 13 min) and is sent into the nearest armory, which frees it for the economy. 0: off.
     */
    float unstick_builders_ticks = 0f;
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
     * From chief_wake_retreat_ticks game ticks after our chieftain's cast until his stun is ready again, he keeps
     * chief_wake_keep cells from every enemy warrior within reach, stunned ones included: the ones his stun froze wake
     * inside his reach otherwise (audit13 shepherds 2: 92 % of his deaths come within 40 s after his own stun, a median
     * 11 cells from where he cast; chief_safe skips stunned warriors). 0: off.
     */
    float chief_wake_retreat_ticks = 0f;
    int chief_wake_keep = 12;
    /** Hit points at which the chieftain walks home to the armory. */
    int chief_flee_hp = 24;
    /**
     * chief_refresh (late): from chief_refresh_from_ticks, a chieftain at chief_refresh_hp or less (parked at the
     * armory
     * for good: he never heals, can enter no building, and no other chief trains while he lives) who made no useful
     * cast (chief_refresh_useful unstunned warriors in reach, or an enemy chief) for chief_refresh_idle_ticks at the
     * unit
     * cap (where training costs no births) or chief_refresh_idle_low_ticks below it is killed by our own towers (Attack
     * and a click on him), or by lent warriors when no tower reaches him, at a calm moment (no awake enemy warrior
     * within
     * chief_refresh_clear cells of him, the armory or the trainer), once the trainer quarters holds chief_refresh_hold
     * peons (or chief_refresh_min_inside after chief_refresh_arm_ticks); a fresh 60-hp chief is trained there at once.
     */
    boolean chief_refresh = false;
    float chief_refresh_from_ticks = 120000f; // 40 min
    int chief_refresh_hp = 24;
    float chief_refresh_idle_ticks = 15000f; // 5 min
    float chief_refresh_idle_low_ticks = 30000f; // 10 min
    int chief_refresh_useful = 4;
    int chief_refresh_clear = 30;
    int chief_refresh_hold = 20;
    int chief_refresh_min_inside = 8;
    float chief_refresh_arm_ticks = 3000f; // 60 s
    float chief_refresh_cull_ticks = 2250f; // 45 s
    /**
     * chief_retrain_late (chief_refresh implies it): from chief_refresh_from_ticks, a chieftain who died is retrained
     * with one finished quarters at the unit cap (two below it) and no armory, and the trainer keeps hold_chieftain
     * peons at the cap too and is topped up whenever no threat is within 20 cells of it (s7125: no chief for the last
     * 105 min with 2 quarters and no armory; s9902: 415 s waiting for a third quarters).
     */
    boolean chief_retrain_late = false;

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
     * steer its waves where the towers shoot them (Decoys). From decoy_ticks on, at most decoy_max at once, leaving
     * decoy_free_slots of the building cap for real buildings; a spot must be within decoy_margin of the distance of
     * the copy's nearest real target. decoy_cage leaves enemies standing in tower reach to the towers.
     */
    boolean decoys = false;
    /**
     * A shepherd peon per copy draws its waves onto empty ground (Shepherd), from shepherd_ticks to
     * shepherd_until_ticks.
     */
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
     * Giants (Giants, late): from giants_from_ticks, stalled blocks of attack-walkers and parked enemies count as
     * inert.
     * giant_keepout (cells, 0 = off): a project site, a re-site target or an expansion site with at least giant_min
     * inert
     * enemy warriors within it waits (a builder within their 8-cell scan wakes them). giant_stun_hold: the chieftain's
     * stun leaves inert enemies out of its count unless a tower of ours reaches them (a stun drops their walks, and
     * their copies relaunch them). giant_shred: the shred mission blasts blocks of at least giant_shred_min inert
     * warriors within giant_shred_range cells of our buildings or gatherers, from 9 cells (Chebyshev) outside every
     * enemy,
     * and with giant_shred_back from behind (away from the members' targets).
     */
    float giants_from_ticks = 120000f; // 40 min
    int giant_keepout = 0;
    int giant_min = 5;
    boolean giant_stun_hold = false;
    boolean giant_shred = false;
    int giant_shred_min = 40;
    int giant_shred_range = 45;
    boolean giant_shred_back = true;
    /** giant_shred: most units of ours a blast may also catch (within 19 cells of the cast point). */
    int giant_shred_friends = 2;
    /**
     * giant_shred: the chieftain shreds above this many hit points (he never heals; a dead one is trained afresh, and a
     * blast from out of every scan costs him none).
     */
    int giant_shred_min_hp = 0;
    /**
     * Shepherds from 120 s (was 200): vs hard*11 elim +.048 / +.024 on seeds 1..200 / 201..400, W 28 vs 19 over 400,
     * lsr15 +.16 / +.19 (st120b2-vs11-hv, -b); N=8 +.023 (W 118 vs 115). 90 s: same survival, fewer outs; 150 s: less.
     */
    float shepherd_ticks = 6000f; // 120 s
    float shepherd_until_ticks = 5000000f; // 100000 s
    /**
     * Farthest a shepherd stands from the wave's leader, in cells (it must stay within 0.66 of our nearest building).
     */
    int shepherd_max_r = 22;
    /** Chebyshev cells a shepherd's spot keeps from every enemy unit (idle and walking units scan 8). */
    int shepherd_clear = 12;
    /**
     * shepherd_lead_ticks (game ticks, 0 = off; maxn K4): until a copy's first launch, and while it has no idle
     * warrior, its shepherd is recruited only once it would reach its spot shepherd_lead_margin_ticks before the copy's
     * armory time plus shepherd_lead_ticks (walking shepherd_cells_per_tick, plus 5 s), never before shepherd_ticks.
     * Flocks then watch for the copies' armories from 90 s, and far copies are tended first (Shepherd.tend).
     */
    float shepherd_lead_ticks = 0f;
    float shepherd_lead_margin_ticks = 750f; // 15 s
    /** Cells per game tick a shepherd walks, for shepherd_lead_ticks (a guess until K2's "at spot" logs measure it). */
    float shepherd_cells_per_tick = .042f; // 2.1 cells/s
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
    /** Game ticks a shepherd waits without a spot before it goes home (large: never). */
    float shepherd_patience_ticks = 5000000f; // 100000 s
    /** Weight of a spot's distance from the copy's own quarters and armory, beside its distance from our start. */
    float shepherd_home_weight = 0f;
    /**
     * shepherd_sticky (score cells, 0 = off): a shepherd's current spot, and ring cells within 4 cells of it, score
     * this much more, so the spot no longer flips between ring cells of about equal score (Shepherd.findSpot); the
     * current spot also stays a candidate while enemies have blocked it for less than shepherd_grace_ticks game ticks.
     * shepherd_travel: each cell from the shepherd to a candidate costs this much score.
     */
    float shepherd_sticky = 0f;
    float shepherd_grace_ticks = 0f;
    float shepherd_travel = 0f;
    /**
     * shepherd_safe_walk: a shepherd takes the best of the 12 best spots whose straight walk, over its first
     * shepherd_safe_look cells, keeps shepherd_safe_clear cells from every enemy warrior and chieftain, and a flee runs
     * 3 s before it heads back (Shepherd.findSpot, tend).
     */
    boolean shepherd_safe_walk = false;
    /**
     * A shepherd that flees before reaching its spot, when the way away from the threat points back towards our start,
     * flees sideways instead (perpendicular to the line from our start, the side the threat vector leans to first) if
     * that point is reachable, clear of enemy warriors and away from any walking wave's target: caught waves were
     * pulled 25 cells towards us per catch and fed 73 % of the base-threat entries (logged N=13 ludicrous games).
     */
    boolean shepherd_flee_side = true; // cur11 (see forGame); alone: survival up at N=13-14, wins flat at N=15
    /**
     * shepherd_follow: while a copy's oldest idle warrior stands at home (40 cells from its armory) and its last wave
     * is still out, its shepherd's spot is picked around that wave's target (where its survivors go idle and lead the
     * next launch), not at home. shepherd_home_pair (cells, 0 = off): copies starting at least this far from us get a
     * second, home shepherd, whose spot is picked around the copy's oldest idle warrior at home, else its armory;
     * the copy's own shepherd then follows its wave as with shepherd_follow (Shepherd.tend).
     */
    boolean shepherd_follow = false;
    /**
     * shepherd_gap_ticks (game ticks, 0 = off): after a copy's shepherd is lost, the next is recruited only this much
     * later (nine shepherds of ten die, most on the way: Shepherd.tend).
     */
    float shepherd_gap_ticks = 0f;
    int shepherd_home_pair = 0;
    int shepherd_safe_look = 40;
    int shepherd_safe_clear = 10;
    /**
     * shepherd_site_origin (0 = off; shepherd spec S1): a copy with no idle warrior and no finished armory has its
     * spot picked around its first placed armory site (1), else its first finished quarters (2), not left without a
     * shepherd until the armory is done. Armory sites are placed at about 100 s while recruits came at 162-173 s, and
     * a shepherd that walks out 50-70 s earlier stands inside a far copy's huge first-wave disc, so a first wave that
     * went straight at our base is caught (early shepherds pay: shepherd_ticks 240 s instead of 120 s cut N=13 W 45 ->
     * 19). shepherd_far_first: far copies are tended first (as with shepherd_lead), so their shepherds get the scarce
     * early peons. A copy the freeze opening froze gets none (its armory site never finishes, Freeze).
     */
    int shepherd_site_origin = 0;
    boolean shepherd_far_first = false;
    /**
     * shepherd_calm_peons (Chebyshev cells, 0 = off; S2a): an enemy peon that is neither idle, defending, attacking,
     * hunting nor walking aggressively counts as a shepherd's flee threat and blocks a spot only within this many
     * cells (such a peon starts no fight, and one beyond 8 that turns idle still cannot see the shepherd). Meant with
     * shepherd_all_circles: alone (a 16-seed smoke) it gave more spots but fewer arrivals.
     */
    // cur15 (with shepherd_flee_pick, _tether 2, _hunted, _calm_peons 8, _all_circles, _predict), 0/10/10, 500 games
    // each: N=13 W 234 -> 322 (z 6.9), the flee pick alone 288 (z 4.4); N=14 / N=16 so far 100 -> 142 / 21 -> 44 on the
    // same seeds; launches at our base 13.0 -> 10.0 % (flee pick, N=13).
    int shepherd_calm_peons = 8;
    /**
     * shepherd_all_circles (S2b): spots and sideways flee points keep DEFENSE_CELLS from every copy's finished quarters
     * and armories, not only from the shepherd's own copy's buildings (the Hard defends around its first finished
     * quarters, else its armory; every finished one is a conservative superset).
     */
    boolean shepherd_all_circles = true; // cur15
    /**
     * shepherd_flee_clear (S2c): a shepherd flees from enemy warriors and peons within this many Chebyshev cells (12
     * before, a constant): a spot cleared below 12 cells (shepherd_clear) otherwise sets off a flee on arrival.
     */
    int shepherd_flee_clear = 12;
    /**
     * shepherd_flee_pick (S3): a fleeing shepherd runs to the best of 32 checked points (16 headings, shepherd_flee_r
     * and 0.6 of it), reachable, clear of enemy warriors along the way and at the end, out of tower reach and defense
     * circles and 14 cells from every walking wave's target, at least 6 cells farther from the threat; the score adds
     * shepherd_flee_out per cell gained away from our start and takes off shepherd_tether per cell beyond the copy's
     * leash disc (0.66 of its origin's distance to our nearest building, minus 2; origins within 150 cells only), and
     * a pick is held 1.5 s. With no legal point the flee goes as before (shepherd_flee_side, else straight away). The
     * precedent is shepherd_flee_side (+4.2 pp W at N=13): at launches that hit our base the copy's own shepherd was
     * away in 62-70 %, three quarters of those after a flee in the 8 s before. shepherd_hunted: an enemy hunting the
     * shepherd counts as a threat anywhere within 40 cells (not only within the flee box), and holds the walk back to
     * the spot for 4 s after it was last seen.
     */
    boolean shepherd_flee_pick = true; // cur15
    int shepherd_flee_r = 22;
    float shepherd_flee_out = .5f;
    float shepherd_tether = 2f; // cur15
    boolean shepherd_hunted = true; // cur15
    /**
     * shepherd_predict (S4): while a copy's oldest warrior, in its Army order, is out on an attack-move (its last wave
     * marching, or a hunter walking back), and the copy cannot launch yet (fewer idle warriors than its wave size, or
     * no chieftain from the third wave on), the spot is picked around that walk's target, where the warrior goes idle
     * and leads the next launch, not around the current idle leader at home (the spot flipped 30+ cells home and
     * back, 942 jumps a game at N=13); spots then keep clear of the wave's landing zone and its walk. Only a target
     * that leashes a spot (our shepherds near it left out) is taken: a wave aimed at our buildings, army or a field
     * peon fights where it lands, and around its target no spot was found for the whole walk (the first smoke:
     * shepherd_t_nospot +44 %). It replaces shepherd_follow.
     */
    boolean shepherd_predict = true; // cur15
    /**
     * shepherd_fallback_r (cells, 0 = off; S5a): when no ring cell up to shepherd_max_r makes a spot, rings 26, 30, ...
     * up to this (and the leash) are tried, 32 cells to a ring. shepherd_clear_parked (cells, 0 = off; S5b): when those
     * fail too, a parked enemy warrior (idle on its default controller, blind beyond 8 cells) blocks a ring cell only
     * within this many cells, and the shepherd on such a spot flees from parked warriors only that near. No spot made
     * 15-19 % of tends at N=13-17, and its share of base waves grows with N and with hills.
     */
    int shepherd_fallback_r = 0;
    int shepherd_clear_parked = 0;
    /**
     * Per-tick orders (Reflexes): restart each harvest swing right after its hit (audit A26: a viking peon then
     * hits every 15 ticks instead of 51), and cancel each stun on the tick it lands by ordering the unit again (K1).
     */
    boolean swing_restart = true;
    /**
     * Game ticks a gatherer spends at the supply per load, in the gather cost model (armory site and crew split): 10
     * hits of 51 ticks without the swing restart; 10 of 15 ticks (150 ticks, 3 s) plus settling in with it.
     */
    float harvest_ticks = 500f; // 10 s
    boolean stun_cancel = true;
    /** With stun_cancel, run only from an enemy sonic blast, not from the stun (which Reflexes cancels anyway). */
    boolean dodge_blast_only = true;
    float decoy_ticks = 12000f; // 240 s
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
        giants_from_ticks = (float) params.getDouble("giants_from_ticks", giants_from_ticks);
        giant_keepout = params.getInt("giant_keepout", giant_keepout);
        giant_min = params.getInt("giant_min", giant_min);
        giant_stun_hold = params.getBoolean("giant_stun_hold", giant_stun_hold);
        giant_shred = params.getBoolean("giant_shred", giant_shred);
        giant_shred_min = params.getInt("giant_shred_min", giant_shred_min);
        giant_shred_range = params.getInt("giant_shred_range", giant_shred_range);
        giant_shred_back = params.getBoolean("giant_shred_back", giant_shred_back);
        giant_shred_friends = params.getInt("giant_shred_friends", giant_shred_friends);
        giant_shred_min_hp = params.getInt("giant_shred_min_hp", giant_shred_min_hp);
        shepherd_ticks = (float) params.getDouble("shepherd_ticks", shepherd_ticks);
        shepherd_until_ticks = (float) params.getDouble("shepherd_until_ticks", shepherd_until_ticks);
        shepherd_max_r = params.getInt("shepherd_max_r", shepherd_max_r);
        shepherd_clear = params.getInt("shepherd_clear", shepherd_clear);
        shepherd_lead_ticks = (float) params.getDouble("shepherd_lead_ticks", shepherd_lead_ticks);
        shepherd_lead_margin_ticks = (float) params.getDouble("shepherd_lead_margin_ticks", shepherd_lead_margin_ticks);
        shepherd_cells_per_tick = (float) params.getDouble("shepherd_cells_per_tick", shepherd_cells_per_tick);
        tower_face_live = params.getBoolean("tower_face_live", tower_face_live);
        tower_face_place = params.getBoolean("tower_face_place", tower_face_place);
        tower_home_anchor = params.getBoolean("tower_home_anchor", tower_home_anchor);
        tower_q_anchor = params.getBoolean("tower_q_anchor", tower_q_anchor);
        front_order = params.getInt("front_order", front_order);
        tower_min_quarters = params.getInt("tower_min_quarters", tower_min_quarters);
        shepherd_patience_ticks = (float) params.getDouble("shepherd_patience_ticks", shepherd_patience_ticks);
        shepherd_home_weight = (float) params.getDouble("shepherd_home_weight", shepherd_home_weight);
        shepherd_sticky = (float) params.getDouble("shepherd_sticky", shepherd_sticky);
        shepherd_grace_ticks = (float) params.getDouble("shepherd_grace_ticks", shepherd_grace_ticks);
        shepherd_travel = (float) params.getDouble("shepherd_travel", shepherd_travel);
        shepherd_safe_walk = params.getBoolean("shepherd_safe_walk", shepherd_safe_walk);
        shepherd_flee_side = params.getBoolean("shepherd_flee_side", shepherd_flee_side);
        shepherd_follow = params.getBoolean("shepherd_follow", shepherd_follow);
        shepherd_gap_ticks = (float) params.getDouble("shepherd_gap_ticks", shepherd_gap_ticks);
        shepherd_home_pair = params.getInt("shepherd_home_pair", shepherd_home_pair);
        shepherd_safe_look = params.getInt("shepherd_safe_look", shepherd_safe_look);
        shepherd_safe_clear = params.getInt("shepherd_safe_clear", shepherd_safe_clear);
        shepherd_site_origin = params.getInt("shepherd_site_origin", shepherd_site_origin);
        shepherd_far_first = params.getBoolean("shepherd_far_first", shepherd_far_first);
        shepherd_calm_peons = params.getInt("shepherd_calm_peons", shepherd_calm_peons);
        shepherd_all_circles = params.getBoolean("shepherd_all_circles", shepherd_all_circles);
        shepherd_flee_clear = params.getInt("shepherd_flee_clear", shepherd_flee_clear);
        shepherd_flee_pick = params.getBoolean("shepherd_flee_pick", shepherd_flee_pick);
        shepherd_flee_r = params.getInt("shepherd_flee_r", shepherd_flee_r);
        shepherd_flee_out = (float) params.getDouble("shepherd_flee_out", shepherd_flee_out);
        shepherd_tether = (float) params.getDouble("shepherd_tether", shepherd_tether);
        shepherd_hunted = params.getBoolean("shepherd_hunted", shepherd_hunted);
        shepherd_predict = params.getBoolean("shepherd_predict", shepherd_predict);
        shepherd_fallback_r = params.getInt("shepherd_fallback_r", shepherd_fallback_r);
        shepherd_clear_parked = params.getInt("shepherd_clear_parked", shepherd_clear_parked);
        tower_parallel = params.getInt("tower_parallel", tower_parallel);
        front_tower_min = params.getInt("front_tower_min", front_tower_min);
        front_tower_max = params.getInt("front_tower_max", front_tower_max);
        focus_bonus = (float) params.getDouble("focus_bonus", focus_bonus);
        defense_radius = params.getInt("defense_radius", defense_radius);
        rock_share = (float) params.getDouble("rock_share", rock_share);
        rock_share_late = (float) params.getDouble("rock_share_late", rock_share_late);
        rock_late_ticks = (float) params.getDouble("rock_late_ticks", rock_late_ticks);
        rock_on_fail = params.getBoolean("rock_on_fail", rock_on_fail);
        rock_fail_from_ticks = (float) params.getDouble("rock_fail_from_ticks", rock_fail_from_ticks);
        rock_idle = (float) params.getDouble("rock_idle", rock_idle);
        away_strike = params.getBoolean("away_strike", away_strike);
        wedge_real_kills = params.getBoolean("wedge_real_kills", wedge_real_kills);
        gather_kill_zone = params.getBoolean("gather_kill_zone", gather_kill_zone);
        gather_kill_n = params.getInt("gather_kill_n", gather_kill_n);
        gather_kill_cells = params.getInt("gather_kill_cells", gather_kill_cells);
        gather_kill_window_ticks = (float) params.getDouble("gather_kill_window_ticks", gather_kill_window_ticks);
        gather_kill_ticks = (float) params.getDouble("gather_kill_ticks", gather_kill_ticks);
        gather_route_clear = params.getBoolean("gather_route_clear", gather_route_clear);
        gather_route_cells = params.getInt("gather_route_cells", gather_route_cells);
        gather_route_tries = params.getInt("gather_route_tries", gather_route_tries);
        gather_fixes_ticks = (float) params.getDouble("gather_fixes_ticks", gather_fixes_ticks);
        shepherd_stuck_ticks = (float) params.getDouble("shepherd_stuck_ticks", shepherd_stuck_ticks);
        shepherd_stuck_gap_ticks = (float) params.getDouble("shepherd_stuck_gap_ticks", shepherd_stuck_gap_ticks);
        shepherd_stuck_base_ticks = (float) params.getDouble("shepherd_stuck_base_ticks", shepherd_stuck_base_ticks);
        shepherd_stuck_base_cells = params.getInt("shepherd_stuck_base_cells", shepherd_stuck_base_cells);
        shepherd_gap_ready = params.getBoolean("shepherd_gap_ready", shepherd_gap_ready);
        shepherd_nospot_ticks = (float) params.getDouble("shepherd_nospot_ticks", shepherd_nospot_ticks);
        shepherd_detour = params.getBoolean("shepherd_detour", shepherd_detour);
        shepherd_gap_backoff = params.getBoolean("shepherd_gap_backoff", shepherd_gap_backoff);
        shepherd_gap_max_ticks = (float) params.getDouble("shepherd_gap_max_ticks", shepherd_gap_max_ticks);
        shepherd_progress_ticks = (float) params.getDouble("shepherd_progress_ticks", shepherd_progress_ticks);
        shepherd_progress_base = params.getBoolean("shepherd_progress_base", shepherd_progress_base);
        shepherd_detour_r = params.getInt("shepherd_detour_r", shepherd_detour_r);
        shepherd_need = (float) params.getDouble("shepherd_need", shepherd_need);
        shepherd_fixes_ticks = (float) params.getDouble("shepherd_fixes_ticks", shepherd_fixes_ticks);
        shepherd_need_ticks = (float) params.getDouble("shepherd_need_ticks", shepherd_need_ticks);
        shepherd_recruit_clear = params.getBoolean("shepherd_recruit_clear", shepherd_recruit_clear);
        shepherd_recruit_clear_ticks = (float) params.getDouble("shepherd_recruit_clear_ticks",
                shepherd_recruit_clear_ticks);
        shepherd_coming_stalled = params.getInt("shepherd_coming_stalled", shepherd_coming_stalled);
        shepherd_coming_stalled_ticks = (float) params.getDouble("shepherd_coming_stalled_ticks",
                shepherd_coming_stalled_ticks);
        away_strike_ticks = (float) params.getDouble("away_strike_ticks", away_strike_ticks);
        away_home_cells = params.getInt("away_home_cells", away_home_cells);
        away_idle_value = (float) params.getDouble("away_idle_value", away_idle_value);
        away_walk_value = (float) params.getDouble("away_walk_value", away_walk_value);
        rock_idle_ticks = (float) params.getDouble("rock_idle_ticks", rock_idle_ticks);
        rock_idle_keep = params.getInt("rock_idle_keep", rock_idle_keep);
        rock_idle_iron = params.getInt("rock_idle_iron", rock_idle_iron);
        quarters_first = params.getBoolean("quarters_first", quarters_first);
        gate_freeze = params.getBoolean("gate_freeze", gate_freeze);
        decapitate = params.getBoolean("decapitate", decapitate);
        decapitate_chief_cells = params.getInt("decapitate_chief_cells", decapitate_chief_cells);
        decapitate_from_ticks = (float) params.getDouble("decapitate_from_ticks", decapitate_from_ticks);
        target_defense_weight = (float) params.getDouble("target_defense_weight", target_defense_weight);
        target_home_weight = (float) params.getDouble("target_home_weight", target_home_weight);
        target_threat_weight = (float) params.getDouble("target_threat_weight", target_threat_weight);
        tower_mutual = params.getBoolean("tower_mutual", tower_mutual);
        snipers = params.getBoolean("snipers", snipers);
        tower_unstun = params.getBoolean("tower_unstun", tower_unstun);
        skip_stalled = params.getBoolean("skip_stalled", skip_stalled);
        corner_fields = params.getBoolean("corner_fields", corner_fields);
        dead_region_reach = params.getInt("dead_region_reach", dead_region_reach);
        sealed_progress = params.getBoolean("sealed_progress", sealed_progress);
        pocket_from_ticks = (float) params.getDouble("pocket_from_ticks", pocket_from_ticks);
        gate_owner = params.getBoolean("gate_owner", gate_owner);
        reinforce_threat_ratio = (float) params.getDouble("reinforce_threat_ratio", reinforce_threat_ratio);
        finish_copies = params.getBoolean("finish_copies", finish_copies);
        finish_skip_out = params.getBoolean("finish_skip_out", finish_skip_out);
        finish_units = params.getInt("finish_units", finish_units);
        finish_ratio = (float) params.getDouble("finish_ratio", finish_ratio);
        remnant_ladder_ticks = (float) params.getDouble("remnant_ladder_ticks", remnant_ladder_ticks);
        mopup = params.getBoolean("mopup", mopup);
        allin_ticks = (float) params.getDouble("allin_ticks", allin_ticks);
        allin_quiet_ticks = (float) params.getDouble("allin_quiet_ticks", allin_quiet_ticks);
        push_ticks = (float) params.getDouble("push_ticks", push_ticks);
        push_quiet_ticks = (float) params.getDouble("push_quiet_ticks", push_quiet_ticks);
        push_period_ticks = (float) params.getDouble("push_period_ticks", push_period_ticks);
        push_calm = params.getBoolean("push_calm", push_calm);
        push_soft = params.getBoolean("push_soft", push_soft);
        last_stand = params.getBoolean("last_stand", last_stand);
        enter_move = params.getBoolean("enter_move", enter_move);
        repair_swarm = params.getBoolean("repair_swarm", repair_swarm);
        swarm_default = params.getBoolean("swarm_default", swarm_default);
        shelter_repair = params.getBoolean("shelter_repair", shelter_repair);
        quarters_sortie = params.getBoolean("quarters_sortie", quarters_sortie);
        quarters_sortie_min = params.getInt("quarters_sortie_min", quarters_sortie_min);
        shelter_reach = params.getInt("shelter_reach", shelter_reach);
        shelter_off = params.getBoolean("shelter_off", shelter_off);
        swarm_cold = params.getBoolean("swarm_cold", swarm_cold);
        swarm_margin = (float) params.getDouble("swarm_margin", swarm_margin);
        swarm_catchup_ticks = (float) params.getDouble("swarm_catchup_ticks", swarm_catchup_ticks);
        swarm_ring_qa = params.getInt("swarm_ring_qa", swarm_ring_qa);
        swarm_ring_tower = params.getInt("swarm_ring_tower", swarm_ring_tower);
        swarm_peon_share = (float) params.getDouble("swarm_peon_share", swarm_peon_share);
        swarm_reach = params.getInt("swarm_reach", swarm_reach);
        swarm_wood_reserve = params.getInt("swarm_wood_reserve", swarm_wood_reserve);
        swarm_cold_reserve = params.getInt("swarm_cold_reserve", swarm_cold_reserve);
        swarm_lee = params.getBoolean("swarm_lee", swarm_lee);
        salvage = params.getBoolean("salvage", salvage);
        salvage_ratio = (float) params.getDouble("salvage_ratio", salvage_ratio);
        salvage_margin_ticks = (float) params.getDouble("salvage_margin_ticks", salvage_margin_ticks);
        salvage_min = params.getInt("salvage_min", salvage_min);
        salvage_attackers = params.getInt("salvage_attackers", salvage_attackers);
        salvage_approach_weight = (float) params.getDouble("salvage_approach_weight", salvage_approach_weight);
        salvage_safe = params.getInt("salvage_safe", salvage_safe);
        salvage_reach = params.getInt("salvage_reach", salvage_reach);
        salvage_calm_ticks = (float) params.getDouble("salvage_calm_ticks", salvage_calm_ticks);
        last_stand_ticks = (float) params.getDouble("last_stand_ticks", last_stand_ticks);
        endgame_from_ticks = (float) params.getDouble("endgame_from_ticks", endgame_from_ticks);
        defense_others_radius = params.getInt("defense_others_radius", defense_others_radius);
        defense_others_from_ticks = (float) params.getDouble("defense_others_from_ticks", defense_others_from_ticks);
        chief_hunt = params.getBoolean("chief_hunt", chief_hunt);
        hunt_sites = params.getBoolean("hunt_sites", hunt_sites);
        chief_hunt_size = params.getInt("chief_hunt_size", chief_hunt_size);
        chief_hunt_range = params.getInt("chief_hunt_range", chief_hunt_range);
        chief_hunt_escort = params.getInt("chief_hunt_escort", chief_hunt_escort);
        chief_hunt_ticks = (float) params.getDouble("chief_hunt_ticks", chief_hunt_ticks);
        weapon_sync = params.getBoolean("weapon_sync", weapon_sync);
        weapon_sync_three = params.getBoolean("weapon_sync_three", weapon_sync_three);
        finish_range = params.getInt("finish_range", finish_range);
        deny_rebuild = params.getBoolean("deny_rebuild", deny_rebuild);
        late_caution = (float) params.getDouble("late_caution", late_caution);
        late_from_ticks = (float) params.getDouble("late_from_ticks", late_from_ticks);
        late_copies = params.getInt("late_copies", late_copies);
        deny_rebuild_cells = params.getInt("deny_rebuild_cells", deny_rebuild_cells);
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
        recall_cooldown_ticks = (float) params.getDouble("recall_cooldown_ticks", recall_cooldown_ticks);
        worn_basis = params.getInt("worn_basis", worn_basis);
        worn_window_ticks = (float) params.getDouble("worn_window_ticks", worn_window_ticks);
        worn_ratio = (float) params.getDouble("worn_ratio", worn_ratio);
        enemy_stun_mult = (float) params.getDouble("enemy_stun_mult", enemy_stun_mult);
        enemy_spell_recharge_ticks = (float) params.getDouble("enemy_spell_recharge_ticks", enemy_spell_recharge_ticks);
        enemy_first_seen = params.getBoolean("enemy_first_seen", enemy_first_seen);
        retreat_split_guard = params.getBoolean("retreat_split_guard", retreat_split_guard);
        parked_scan_econ = params.getInt("parked_scan_econ", parked_scan_econ);
        parked_scan_threat = params.getInt("parked_scan_threat", parked_scan_threat);
        hold_closing = params.getInt("hold_closing", hold_closing);
        hold_multi = params.getBoolean("hold_multi", hold_multi);
        retreat_rearguard = params.getBoolean("retreat_rearguard", retreat_rearguard);
        rush_opening_only = params.getBoolean("rush_opening_only", rush_opening_only);
        rush_hold_armory = params.getBoolean("rush_hold_armory", rush_hold_armory);
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
        rock_stream_ticks = (float) params.getDouble("rock_stream_ticks", rock_stream_ticks);
        rock_stream_iron_ticks = (float) params.getDouble("rock_stream_iron_ticks", rock_stream_iron_ticks);
        rock_stream_max = params.getInt("rock_stream_max", rock_stream_max);
        site_max = params.getInt("site_max", site_max);
        rock_filler_div = params.getInt("rock_filler_div", rock_filler_div);
        rock_filler_min_workers = params.getInt("rock_filler_min_workers", rock_filler_min_workers);
        rock_filler_stock = params.getInt("rock_filler_stock", rock_filler_stock);
        lure_max = params.getInt("lure_max", lure_max);
        lure_min = params.getInt("lure_min", lure_min);
        lure_range = params.getInt("lure_range", lure_range);
        lure_ticks = (float) params.getDouble("lure_ticks", lure_ticks);
        tower_parallel_late = params.getInt("tower_parallel_late", tower_parallel_late);
        sites_parallel_late = params.getInt("sites_parallel_late", sites_parallel_late);
        tower_parallel_late_ticks = (float) params.getDouble("tower_parallel_late_ticks", tower_parallel_late_ticks);
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
        harvest_ticks = (float) params.getDouble("harvest_ticks", harvest_ticks);
        stun_cancel = params.getBoolean("stun_cancel", stun_cancel);
        dodge_blast_only = params.getBoolean("dodge_blast_only", dodge_blast_only);
        decoy_ticks = (float) params.getDouble("decoy_ticks", decoy_ticks);
        decoy_max = params.getInt("decoy_max", decoy_max);
        decoy_free_slots = params.getInt("decoy_free_slots", decoy_free_slots);
        decoy_margin = (float) params.getDouble("decoy_margin", decoy_margin);
        decoy_cage = params.getBoolean("decoy_cage", decoy_cage);
        initial_quarters = params.getInt("initial_quarters", initial_quarters);
        max_quarters = params.getInt("max_quarters", max_quarters);
        expand_ticks = (float) params.getDouble("expand_ticks", expand_ticks);
        max_armory_distance = params.getInt("max_armory_distance", max_armory_distance);
        armory_distance_weight_ticks = (float) params.getDouble("armory_distance_weight_ticks",
                armory_distance_weight_ticks);
        armory_delay_weight = (float) params.getDouble("armory_delay_weight", armory_delay_weight);
        armory_threat_weight_ticks = (float) params.getDouble("armory_threat_weight_ticks", armory_threat_weight_ticks);
        scouts = params.getInt("scouts", scouts);
        armory_builders = params.getInt("armory_builders", armory_builders);
        quarters_builders = params.getInt("quarters_builders", quarters_builders);
        tower_builders = params.getInt("tower_builders", tower_builders);
        tower_wood_drop = params.getBoolean("tower_wood_drop", tower_wood_drop);
        tower_wood_trees = params.getInt("tower_wood_trees", tower_wood_trees);
        tower_wood_reach = params.getInt("tower_wood_reach", tower_wood_reach);
        tower_wood_reserve = params.getInt("tower_wood_reserve", tower_wood_reserve);
        tower_wood_max = params.getInt("tower_wood_max", tower_wood_max);
        tower_wood_ticks = (float) params.getDouble("tower_wood_ticks", tower_wood_ticks);
        quarters_before_armory = params.getInt("quarters_before_armory", quarters_before_armory);
        opening_near_start = params.getBoolean("opening_near_start", opening_near_start);
        rush_response = params.getBoolean("rush_response", rush_response);
        rush_quarters = params.getInt("rush_quarters", rush_quarters);
        rush_ticks = (float) params.getDouble("rush_ticks", rush_ticks);
        pressure_response = params.getBoolean("pressure_response", pressure_response);
        pressure_ticks = (float) params.getDouble("pressure_ticks", pressure_ticks);
        hold_early = params.getInt("hold_early", hold_early);
        hold_mid = params.getInt("hold_mid", hold_mid);
        seed_quarters_ticks = (float) params.getDouble("seed_quarters_ticks", seed_quarters_ticks);
        seed_quarters_reach = params.getInt("seed_quarters_reach", seed_quarters_reach);
        hold_backlog = params.getInt("hold_backlog", hold_backlog);
        hold_backlog_until_ticks = (float) params.getDouble("hold_backlog_until_ticks", hold_backlog_until_ticks);
        veto_resite_ticks = (float) params.getDouble("veto_resite_ticks", veto_resite_ticks);
        veto_resite_from_ticks = (float) params.getDouble("veto_resite_from_ticks", veto_resite_from_ticks);
        veto_resite_clear = params.getInt("veto_resite_clear", veto_resite_clear);
        veto_resite_quarters = params.getBoolean("veto_resite_quarters", veto_resite_quarters);
        unjam = params.getInt("unjam", unjam);
        unjam_after_ticks = (float) params.getDouble("unjam_after_ticks", unjam_after_ticks);
        unjam_progress = params.getInt("unjam_progress", unjam_progress);
        unjam_ticks = (float) params.getDouble("unjam_ticks", unjam_ticks);
        unjam_from_ticks = (float) params.getDouble("unjam_from_ticks", unjam_from_ticks);
        bank_guard = params.getBoolean("bank_guard", bank_guard);
        bank_guard_ticks = (float) params.getDouble("bank_guard_ticks", bank_guard_ticks);
        bank_min = params.getInt("bank_min", bank_min);
        bank_margin = (float) params.getDouble("bank_margin", bank_margin);
        bank_noforge_ticks = (float) params.getDouble("bank_noforge_ticks", bank_noforge_ticks);
        bank_reserve_max = params.getInt("bank_reserve_max", bank_reserve_max);
        wood_reach = params.getInt("wood_reach", wood_reach);
        wood_reach_ticks = (float) params.getDouble("wood_reach_ticks", wood_reach_ticks);
        ore_reach = params.getInt("ore_reach", ore_reach);
        gather_probe = params.getBoolean("gather_probe", gather_probe);
        gather_home = params.getBoolean("gather_home", gather_home);
        relink_guard = params.getBoolean("relink_guard", relink_guard);
        ore_iron_first = params.getBoolean("ore_iron_first", ore_iron_first);
        ore_reach_fail = params.getBoolean("ore_reach_fail", ore_reach_fail);
        weapon_reserve = params.getInt("weapon_reserve", weapon_reserve);
        weapon_reserve_ticks = (float) params.getDouble("weapon_reserve_ticks", weapon_reserve_ticks);
        sortie_ratio = (float) params.getDouble("sortie_ratio", sortie_ratio);
        rearm_placer = params.getBoolean("rearm_placer", rearm_placer);
        rearm_reach = params.getInt("rearm_reach", rearm_reach);
        reloc = params.getBoolean("reloc", reloc);
        reloc_ticks = (float) params.getDouble("reloc_ticks", reloc_ticks);
        reloc_gap_ticks = (float) params.getDouble("reloc_gap_ticks", reloc_gap_ticks);
        reloc_reach = params.getInt("reloc_reach", reloc_reach);
        reloc_nodes = params.getInt("reloc_nodes", reloc_nodes);
        reloc_slot = params.getBoolean("reloc_slot", reloc_slot);
        raid_bank = params.getBoolean("raid_bank", raid_bank);
        raid_bank_ticks = (float) params.getDouble("raid_bank_ticks", raid_bank_ticks);
        raid_bank_extra = params.getInt("raid_bank_extra", raid_bank_extra);
        reloc_draw = params.getInt("reloc_draw", reloc_draw);
        raid_evac = params.getBoolean("raid_evac", raid_evac);
        raid_evac_min = params.getInt("raid_evac_min", raid_evac_min);
        raid_evac_ratio = (float) params.getDouble("raid_evac_ratio", raid_evac_ratio);
        raid_evac_ticks = (float) params.getDouble("raid_evac_ticks", raid_evac_ticks);
        reloc_lock_ticks = params.getInt("reloc_lock_ticks", reloc_lock_ticks);
        reloc_lock_from_ticks = (float) params.getDouble("reloc_lock_from_ticks", reloc_lock_from_ticks);
        retire = params.getBoolean("retire", retire);
        retire_wait_ticks = (float) params.getDouble("retire_wait_ticks", retire_wait_ticks);
        retire_quarters_dist = params.getInt("retire_quarters_dist", retire_quarters_dist);
        retire_pop = params.getInt("retire_pop", retire_pop);
        retire_any_tower = params.getBoolean("retire_any_tower", retire_any_tower);
        hold_late = params.getInt("hold_late", hold_late);
        hold_mid_ticks = (float) params.getDouble("hold_mid_ticks", hold_mid_ticks);
        hold_chieftain = params.getInt("hold_chieftain", hold_chieftain);
        stun_patience = params.getBoolean("stun_patience", stun_patience);
        exploit_stun = params.getBoolean("exploit_stun", exploit_stun);
        charge_mixed = params.getBoolean("charge_mixed", charge_mixed);
        dodge_stun = params.getBoolean("dodge_stun", dodge_stun);
        blast = params.getBoolean("blast", blast);
        blast_ratio = (float) params.getDouble("blast_ratio", blast_ratio);
        blast_min = (float) params.getDouble("blast_min", blast_min);
        blast_defense = params.getBoolean("blast_defense", blast_defense);
        blast_play_ticks = (float) params.getDouble("blast_play_ticks", blast_play_ticks);
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
        chieftain_ticks = (float) params.getDouble("chieftain_ticks", chieftain_ticks);
        multi_front_towers = params.getBoolean("multi_front_towers", multi_front_towers);
        towers_early = params.getInt("towers_early", towers_early);
        towers_mid = params.getInt("towers_mid", towers_mid);
        towers_late = params.getInt("towers_late", towers_late);
        towers_early_ticks = (float) params.getDouble("towers_early_ticks", towers_early_ticks);
        towers_mid_ticks = (float) params.getDouble("towers_mid_ticks", towers_mid_ticks);
        towers_late_ticks = (float) params.getDouble("towers_late_ticks", towers_late_ticks);
        attack_min_strength = (float) params.getDouble("attack_min_strength", attack_min_strength);
        attack_ratio = (float) params.getDouble("attack_ratio", attack_ratio);
        reinforce = params.getBoolean("reinforce", reinforce);
        reinforce_ratio = (float) params.getDouble("reinforce_ratio", reinforce_ratio);
        reinforce_multi = params.getBoolean("reinforce_multi", reinforce_multi);
        capped_clump = (float) params.getDouble("capped_clump", capped_clump);
        ring_sweep = params.getBoolean("ring_sweep", ring_sweep);
        ring_sweep_from_ticks = (float) params.getDouble("ring_sweep_from_ticks", ring_sweep_from_ticks);
        ring_sweep_until_ticks = (float) params.getDouble("ring_sweep_until_ticks", ring_sweep_until_ticks);
        ring_sweep_ratio = (float) params.getDouble("ring_sweep_ratio", ring_sweep_ratio);
        ring_sweep_quiet_ticks = (float) params.getDouble("ring_sweep_quiet_ticks", ring_sweep_quiet_ticks);
        ring_sweep_reach = params.getInt("ring_sweep_reach", ring_sweep_reach);
        capped_clump_min = (float) params.getDouble("capped_clump_min", capped_clump_min);
        capped_clump_cells = params.getInt("capped_clump_cells", capped_clump_cells);
        tower_weight = (float) params.getDouble("tower_weight", tower_weight);
        attack_max_strength = (float) params.getDouble("attack_max_strength", attack_max_strength);
        capped_ratio = (float) params.getDouble("capped_ratio", capped_ratio);
        capped_min_strength = (float) params.getDouble("capped_min_strength", capped_min_strength);
        retreat_ratio = (float) params.getDouble("retreat_ratio", retreat_ratio);
        raid_size = params.getInt("raid_size", raid_size);
        raid_ticks = (float) params.getDouble("raid_ticks", raid_ticks);
        engage_spread = params.getBoolean("engage_spread", engage_spread);
        project_defense = params.getBoolean("project_defense", project_defense);
        precontact_ratio = (float) params.getDouble("precontact_ratio", precontact_ratio);
        strikes = params.getBoolean("strikes", strikes);
        forward_towers = params.getInt("forward_towers", forward_towers);
        forward_tower_ticks = (float) params.getDouble("forward_tower_ticks", forward_tower_ticks);
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
        desperate_iron_cycle_ticks = (float) params.getDouble("desperate_iron_cycle_ticks", desperate_iron_cycle_ticks);
        armory_far_cost_ticks = (float) params.getDouble("armory_far_cost_ticks", armory_far_cost_ticks);
        peon_militia = params.getBoolean("peon_militia", peon_militia);
        militia_ticks = (float) params.getDouble("militia_ticks", militia_ticks);
        peon_rush = params.getBoolean("peon_rush", peon_rush);
        freeze_open = params.getBoolean("freeze_open", freeze_open);
        freeze_squad = params.getInt("freeze_squad", freeze_squad);
        freeze_eta_ticks = (float) params.getDouble("freeze_eta_ticks", freeze_eta_ticks);
        freeze_raze = params.getBoolean("freeze_raze", freeze_raze);
        freeze_unfreeze = params.getBoolean("freeze_unfreeze", freeze_unfreeze);
        freeze_fight = params.getBoolean("freeze_fight", freeze_fight);
        freeze_armory_push = params.getBoolean("freeze_armory_push", freeze_armory_push);
        freeze_retarget = params.getBoolean("freeze_retarget", freeze_retarget);
        freeze_patience_ticks = (float) params.getDouble("freeze_patience_ticks", freeze_patience_ticks);
        frozen_last = params.getBoolean("frozen_last", frozen_last);
        freeze_targets = params.getInt("freeze_targets", freeze_targets);
        freeze_squad2 = params.getInt("freeze_squad2", freeze_squad2);
        freeze_eta2_ticks = (float) params.getDouble("freeze_eta2_ticks", freeze_eta2_ticks);
        freeze_keep = params.getInt("freeze_keep", freeze_keep);
        micro_targets = params.getBoolean("micro_targets", micro_targets);
        restore_dodge = params.getBoolean("restore_dodge", restore_dodge);
        restore_dodge_gap_ticks = (float) params.getDouble("restore_dodge_gap_ticks", restore_dodge_gap_ticks);
        sappers = params.getBoolean("sappers", sappers);
        pillage = params.getBoolean("pillage", pillage);
        siege = params.getBoolean("siege", siege);
        siege_patience_ticks = (float) params.getDouble("siege_patience_ticks", siege_patience_ticks);
        adaptive_caution = params.getBoolean("adaptive_caution", adaptive_caution);
        caution_decay = (float) params.getDouble("caution_decay", caution_decay);
        creep_towers = params.getBoolean("creep_towers", creep_towers);
        hidden_info = params.getBoolean("hidden_info", hidden_info);
        chicken_hunters = params.getInt("chicken_hunters", chicken_hunters);
        chicken_range = params.getInt("chicken_range", chicken_range);
        stall_cap_ticks = (float) params.getDouble("stall_cap_ticks", stall_cap_ticks);
        stall_cap_kills = params.getInt("stall_cap_kills", stall_cap_kills);
        retreat_cap_ticks = (float) params.getDouble("retreat_cap_ticks", retreat_cap_ticks);
        stall_cap_keep = params.getBoolean("stall_cap_keep", stall_cap_keep);
        unlock_from_ticks = (float) params.getDouble("unlock_from_ticks", unlock_from_ticks);
        stall_engaged_ticks = (float) params.getDouble("stall_engaged_ticks", stall_engaged_ticks);
        wedge_memory_ticks = (float) params.getDouble("wedge_memory_ticks", wedge_memory_ticks);
        stall_engaged_from_ticks = (float) params.getDouble("stall_engaged_from_ticks", stall_engaged_from_ticks);
        chicken_ticks = (float) params.getDouble("chicken_ticks", chicken_ticks);
        chicken_pool_div = params.getInt("chicken_pool_div", chicken_pool_div);
        tower_fire = params.getBoolean("tower_fire", tower_fire);
        unstick = params.getBoolean("unstick", unstick);
        unstick_builders_ticks = (float) params.getDouble("unstick_builders_ticks", unstick_builders_ticks);
        walk_select = params.getInt("walk_select", walk_select);
        tower_site_fallback = params.getBoolean("tower_site_fallback", tower_site_fallback);
        chief_keep_out = params.getInt("chief_keep_out", chief_keep_out);
        chief_safe = params.getInt("chief_safe", chief_safe);
        chief_wake_retreat_ticks = (float) params.getDouble("chief_wake_retreat_ticks", chief_wake_retreat_ticks);
        chief_wake_keep = params.getInt("chief_wake_keep", chief_wake_keep);
        chief_flee_hp = params.getInt("chief_flee_hp", chief_flee_hp);
        chief_refresh = params.getBoolean("chief_refresh", chief_refresh);
        chief_refresh_from_ticks = (float) params.getDouble("chief_refresh_from_ticks", chief_refresh_from_ticks);
        chief_refresh_hp = params.getInt("chief_refresh_hp", chief_refresh_hp);
        chief_refresh_idle_ticks = (float) params.getDouble("chief_refresh_idle_ticks", chief_refresh_idle_ticks);
        chief_refresh_idle_low_ticks = (float) params.getDouble("chief_refresh_idle_low_ticks",
                chief_refresh_idle_low_ticks);
        chief_refresh_useful = params.getInt("chief_refresh_useful", chief_refresh_useful);
        chief_refresh_clear = params.getInt("chief_refresh_clear", chief_refresh_clear);
        chief_refresh_hold = params.getInt("chief_refresh_hold", chief_refresh_hold);
        chief_refresh_min_inside = params.getInt("chief_refresh_min_inside", chief_refresh_min_inside);
        chief_refresh_arm_ticks = (float) params.getDouble("chief_refresh_arm_ticks", chief_refresh_arm_ticks);
        chief_refresh_cull_ticks = (float) params.getDouble("chief_refresh_cull_ticks", chief_refresh_cull_ticks);
        chief_retrain_late = params.getBoolean("chief_retrain_late", chief_retrain_late);
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
        // cur11: towers 3 from 300 s and 6 from 480 s (were 200 s and 330 s), stacked with sortie_ratio 1.0 and
        // shepherd_flee_side, at ludicrous speed against cur10: N=13 W 127 -> 145 of 500 (6501..7000), N=14 51 -> 70
        // and fresh 49 -> 59, N=15 16 -> 29 and fresh 30 -> 27, N=16 10 -> 15; surv60 +1.1..+2.2 min in every block
        // (z 2.5..3.8); N=6 and normal speed neutral. Later towers alone: N=13 W +22 over 800 games.
        strategy.towers_early_ticks = Math.min(strategy.towers_early_ticks, 15000f); // 300 s
        strategy.towers_mid = 6;
        strategy.towers_mid_ticks = 24000f; // 480 s
        strategy.towers_late = 14;
        strategy.towers_late_ticks = 30000f; // 600 s
        // The chieftain from 300 s (was 240): training takes a quarters' breeding, and an earlier chieftain costs the
        // opening more peons than his stuns win back (cur6 N=13-14, 9 blocks: surv60 up in 8, fresh-seed wins 9 -> 12;
        // 360-480 about as good).
        strategy.chieftain_ticks = Math.min(strategy.chieftain_ticks, 15000f); // 300 s
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
        // The freeze opening puts the nearest copy out in about a minute when it starts within freeze_eta_ticks of peon
        // walk (N=12 W 4 -> 13 over 600 seeds with squad 10). With squad 6 it pays at every N tried, so the old enemies
        // >= 12 gate went: N=11 over 400 seeds W 31 -> 35, elim +.032, surv60 +2.3 min (s201..400: W 12 -> 20, wp z
        // 4.0); N=8 W 111 -> 115, elim +.016 (z 1.8); N=14 against freeze off elim +.021 (z 2.7).
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
                strategy.armory_distance_weight_ticks = 3f; // .06 s a meter
                strategy.armory_delay_weight = .6f;
                strategy.armory_threat_weight_ticks = 4500f; // 90 s
                strategy.hold_early = 2;
                strategy.expand_ticks = 12000f; // 240 s
                strategy.towers_early_ticks = 7500f; // 150 s
                strategy.attack_min_strength = 12f;
                strategy.chieftain_ticks = 15000f; // 300 s
                // The fighting reaches the base early and keeps coming back: a second armory only splits the
                // economy just as it starts, while two more towers hold the base (medium, vs both rival AIs on two
                // seed sets each: +.4 to +.7).
                strategy.expansion = false;
                strategy.towers_mid = 4;
                strategy.towers_late = 8;
            }
            case Game.SIZE_ENORMOUS -> {
                strategy.max_armory_distance = 700;
                strategy.expand_ticks = 18000f; // 360 s
            }
            default -> {
            }
        }
        return strategy;
    }
}
