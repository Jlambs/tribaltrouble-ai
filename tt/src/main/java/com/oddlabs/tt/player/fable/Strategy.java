package com.oddlabs.tt.player.fable;

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
 * ({@link FableAI#TICKS_PER_SECOND}), so that the AI plays the same at every speed: times since the start,
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
     * builders_floor: a site gets at most four builders per tree within 12 cells of it, but never fewer than this.
     * At 6 a site whose near trees are cut (or that never had many) is built by six peons that walk 12 to 20 cells for
     * every piece while dozens stand idle (op1-log-vs15 s21012: armory after 306 s, 54 of 82 peons idle at 300 s); on
     * the 24 screening maps whose sixth quarters stands after 330 s the default wins one game in eight.
     * builders_far (cells, 0 = off): the site may also have one builder per tree within this many cells.
     */
    int builders_floor = 6;
    int builders_far = 0;
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
     * the economy, the shepherds, decoys and freeze stand down (FableAI.think, Military.lastStand). Such a game is
     * lost (we never rebuild without a base), and the collapse rule (8 units or fewer, no chieftain, no quarters or
     * armory built or started) cannot end it while more survive or an unbuilt site stands: s6274 (cur11 N=17) kept 86
     * homeless warriors to the 360 min limit, a tenth of its block's compute; s6215 (cur12 N=6) 6 peons and 3 tower
     * gunners, s8389 (cur12 N=15) a peon, a gunner and a site nobody built.
     */
    boolean last_stand = false;
    /**
     * enter_move: orders that send our units into a finished building of ours (shelter, a quarters to breed or train
     * in, home) use MOVE instead of DEFAULT, under which a peon repairs a damaged quarters or tower rather than
     * entering it (FableAI.enterAction). Peons sheltering in a quarters under attack then stood outside it as
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
     * repairs it from outside (GATHER_REPAIR) instead of entering it (FableAI.enterAction): the deliberate form of
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
     * evade (cells, 0 = off): a gatherer with a threat within 11 cells and no quarters or armory of ours within this
     * many cells steps 12 cells out of the enemy's way and goes back to gathering once no enemy warrior is within 14,
     * instead of walking to shelter and out again (a third to a half of the iron gatherers' time from minute 16:
     * lab/fable/NOTES.md, the flights). A peon outruns a warrior (5 m/s against 4).
     */
    int evade_cells = 0;
    /**
     * evade_tower (cells, 0 = off): the step out of an enemy's way goes towards our nearest manned tower within this
     * many cells, unless that leads at the enemy (the tower more than 120 degrees off the way straight from him). A
     * Hard warrior that hunts a peon walks without scanning and never answers a hit (Lures), so the tower shoots it;
     * stepping straight away the gatherer drags it along and steps away again 69 times a minute a game from minute
     * 30, 42 % of the times within 3 s of setting out again (16 long games, eg2-log-vs15).
     */
    int evade_tower = 0;
    /**
     * bystander (cells, -1 = off): an enemy warrior locked in a hunt on another unit is no reason for a peon to walk
     * to shelter or step out of its way unless it is within this many cells. A hunter walks to its target without
     * scanning and answers nothing (HuntController: WalkBehaviour without scan); only when the hunt ends does it scan
     * its 8-cell square again, and it takes a warrior before a peon (AttackScanFilter). In 12 logged games at N=16
     * (ev1log-vs16, lab/fable/evades.py) the nearest enemy was such a hunter in 6,000 of a game's 7,200 flights to
     * shelter and in 1,300 of its 2,200 steps out of the way: one warrior after one peon turns back every gatherer
     * that passes within 11 cells of it, and from minute 20 two iron trips in three end that way.
     */
    int bystander = -1;
    /**
     * bystander_reach (cells, 0 = off): bystander minds a hunter of another unit all the same when that hunt is about
     * to end: its target is a unit within this many cells of it (in throwing reach: a hit kills), or a peon of ours
     * about to walk into a building. The hunter then scans at once, and a peon in its throwing reach at that moment
     * is hit with .75 before it can step away (peons lost stepping away or on the way to shelter: 140 an hour of game
     * to 230 with bystander alone, by0log-vs16).
     */
    int bystander_reach = 0;
    /**
     * flight_keep: a peon sent to shelter from a threat within 11 cells stays on its way there until the threat is 14
     * cells off or it is inside. Without it the economy's next tick takes it, as any peon on its way into a building,
     * for the gatherer its own flight left the plan short of, the military's next tick sends it to shelter again, and
     * it turns about by the enemy twice a second: 80 % of a game's 7,200 flight orders follow one given to a peon
     * within 4 cells 0.5-2 s before (ev1log-vs16).
     */
    boolean flight_keep = false;
    /**
     * evade_chicken: chicken hunters step out of an enemy's way as the other gatherers do (evade_cells). Left out of
     * it, one with a threat within 11 cells walks to the main armory from wherever it is, up to chicken_range cells
     * out: 17 an hour of game die hunting far from home, and the peons lost far from home on their way into a
     * building went from 23 to 44 an hour with bystander (rn5-vs16, by0-vs16).
     */
    boolean evade_chicken = false;
    /**
     * chief_bystander: an enemy warrior locked in a hunt on something other than our chieftain does not count as
     * awake for his blast mission (as bystander for the peons: a hunter does not scan). An awake warrior within 9
     * cells calls the walk to a cast point off and one within 16 drives him from his waiting place towards the
     * armory: 39 approaches called off an hour of game for 11 blasts, and for every blast 55 s walked with the blast
     * charged on top of the 78 s it takes to charge (mb2-vs16, the shred_t_* counters).
     */
    boolean chief_bystander = false;
    /**
     * shred_hunters: enemy warriors locked in a hunt on something other than our chieftain (a wave after a shepherd)
     * are members of a blob for the blast mission as parked ones are: neither sees him come. With the blast charged
     * he has no blob 27 % of the time (shred_t_noblob, mb2-vs16), and in a third of those moments eight and more
     * awake enemy warriors are within shred_range of the armory (cnlog-vs16). Meant with chief_bystander.
     */
    boolean shred_hunters = false;
    /**
     * iron_real (0 = off): the plan's share of iron gatherers follows the measured gatherer-time per unit of iron (a
     * running mean of measureYield's 30-s windows) where that is above the modelled trip, up to this multiple of the
     * model. The model is the walk to the nearest three nodes; the real cost is 2-3 times that from minute 14
     * (lab/fable/NOTES.md), so the forge's workers wait for iron while the plan thinks the pool balanced.
     */
    float iron_real = 0f;
    /**
     * tree_real (0 = off): the plan takes a unit of wood at what it really costs (Economy.measureYield's running
     * mean of gatherer-ticks per unit), up to this many times its modelled trip. From 14 min a unit costs 52-70
     * gatherer-seconds against a model of 33-39 (lab/fable/cost.py), and with a rock line the armory holds 10-16 iron
     * and 1-3 wood.
     */
    float tree_real = 0f;
    /**
     * rock_real (0 = off): a rock line next to the iron one, sized by what a warrior of each kind really costs. The
     * value is what a rock warrior is worth against an iron one (Combat counts 0.6). From rock_real_ticks on, at
     * every yield measurement (30 s) the line's share of the armory's pool goes up a step while
     * rock_real x (80 s + 2 wood + a unit of iron as measured) / (40 s + 2 wood + a unit of rock as measured) is above
     * 1.1 and down a step below 0.9, between 0 and rock_real_share. The line is rock gatherers (by rock's measured
     * trip), the wood for them and the forgers; its gatherers go out before the iron ones, and iron is forged first:
     * no rock axe is on order while the armory holds rock_real_iron_first iron (the forge splits its work evenly
     * between the kinds it can make). Measured in 12 logged N=15 games (all1-trips-vs15, lab/fable/NOTES.md): a unit of
     * iron costs 113 gatherer-seconds in minutes 10-14, 191 in 14-18 and 330-437 in 18-30, a unit of rock 48-66; the
     * cap is filled with peons that have nothing to forge, and the rock gatherers rock_idle asks for never go out
     * (peons are handed out wood, iron, rock, and iron is never full).
     */
    float rock_real = 0f;
    float rock_real_share = .7f;
    int rock_real_iron_first = 3;
    /**
     * rock_real_hold (-1 = off): while the rock line is on and the main armory could forge six weapons from its
     * stock, a quarters holds at most this many peons. A held peon beyond the second breeds under 1.4 peons a minute
     * (n^(1/3) / 11 a second for n inside); a forger with ore makes 1.5 rock axes a minute, and with the line on the
     * peons run short before the ore does (rk-smoke: 39 of 44 peons held, one forger, 20 rock in stock).
     */
    int rock_real_hold = 2;
    /**
     * rock_real_peons (0 = no limit): the line takes at most this many peons of the pool, whatever its share. The
     * screen rk1 (lab/fable/NOTES.md) showed the line trading iron warriors for rock ones one for one at an even
     * strength; with a limit it can be sized to the peons another change frees (a lower hold_late, say).
     */
    int rock_real_peons = 0;
    float rock_real_ticks = 42000f; // 14 min
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
    /**
     * gather_parked: a gatherer is sent only to a supply that no parked enemy warrior sees and that it can walk to
     * unseen, straight from where it stands (and from there to the armory), the cheapest such of the cheapest
     * gather_parked_tries; with none, to none (it is free for other work). A parked warrior scans an 8-cell square,
     * hunts what it sees and stands where it is until then, so its square, with two cells to spare, is ground to walk
     * round, where an enemy that walks is one to step away from. Without it a gatherer that stepped out of a parked
     * group's way is sent to the cheapest ore from the armory, which lies behind the group as often as not: one
     * bounced off a group of 10-16 nine times in 50 s (s22003 #150, by0log-vs16), and half the steps away from a parked
     * enemy come within 5 s of going back to work (lab/fable/evades.py). gather_avoid_parked and gather_route_clear
     * look from the armory only, and at every enemy.
     */
    boolean gather_parked = false;
    int gather_parked_tries = 12;
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
     * hold_levels: which threats hold_ratio's posts answer: 0 all, 1 only harassment away from the base (threat level
     * 1), 2 only threats in the base (level 2). On 150 maps at N=16 the post won 113 for the default's 106, 110
     * against harassment alone and 103 against threats in the base alone (hp3, hr3, hq3-vs16).
     */
    int hold_levels = 0;
    /**
     * threat_sappers: an enemy within 12 cells of a sapper of ours is a threat to answer, as one by any other peon
     * away from the base (false: it is not, as by a shepherd or a chicken hunter). The sappers march with the attack:
     * of the enemy-seconds that make a threat of level 1, 1,108 an hour of game stand by a sapper, 792 of them more
     * than 100 cells from every building of ours, and the home army sends its nearest warriors there (ha3log-vs16,
     * HomeTrace). No sapper flees such a threat either (evacuatePeons leaves them alone). Left on: those walks are
     * called off within seconds, and without them the same warriors answer the threats by far gatherers instead (60
     * deaths an hour for 42, sp1log-vs16); 108 wins of 150 for 106, and 115 for 114 with the post (hw3, hx3-vs16).
     */
    boolean threat_sappers = true;
    /**
     * hold_stick (cells, 0 = off): the post of the last 5 s stays the post until another building of ours is this
     * much nearer the threat. Without it the post follows the centre of the most dangerous group from building to
     * building: once in 15 s, 24 cells a move, and the defenders walk 65 % of their time at it (hp3log-vs16).
     */
    int hold_stick = 0;
    /**
     * hold_commit (cells, 0 = off): the post of the last 5 s stays the post while a threat stands within this many
     * cells of it: the defenders finish at one building before they walk to the next. With two groups on two sides of
     * the base the most dangerous one changes by the second, and hold_stick does not hold a post between buildings 40
     * cells apart (hs1log-vs16, s22006 at 2053-2104 s: eleven defenders walking between three towers).
     */
    int hold_commit = 0;
    /**
     * hold_far (cells, 0 = off): against harassment away from the base (threat level 1) a post farther than this from
     * the threat is no post: the warriors picked for it get no order. In 78 % of such a post's seconds it stands more
     * than 28 cells from the threat (hp3log-vs16): they walk to a building and stand there. Off: 107 wins of 150 for
     * 114 without it (hv3, hu3-vs16): the post's order is also what calls them off the walk they were on.
     */
    int hold_far = 0;
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
    /**
     * reinforce_route: reinforcements walk to the army along the cheapest way that keeps clear of enemy warriors,
     * bases and towers (SafeField, one flood from the army's centre every 2 s for all of them), three blocks at a
     * time, instead of attack-moving straight at it one by one. Of 450 sent in an hour of game 74 die on the way
     * (rn5-vs16), 75 in 92 more than 60 cells from the army and 54 by a group on the move (ft1log-vs16).
     */
    boolean reinforce_route = false;
    /**
     * reinforce_gather (cells, 0 = off): while the attack is out, the idle warriors at home walk to the staging point
     * (Military.gatherHome), and a group of reinforcements is the warriors within that many cells of the one with the
     * most about it: the rest wait for the next. As sent, a group is everyone in role army wherever they stand: its
     * two members farthest apart are 48 cells from each other in the first seconds (the median of 487 groups;
     * quartiles 28 and 74) and still 42 after a minute, and it meets the enemy a few at a time: 86 of 497 sent in an
     * hour of game die on the way for 72 enemy warriors, 0.84 for one where the army and the home army trade 1.6
     * (lab/fable/reinforce.py rt1log-vs16; lab/fable/NOTES.md, the reinforcements traced). The gather closes the
     * group up (13 cells) and costs more than it saves: five more warriors stand at home at any moment, an eighth
     * fewer reach the army, and on 150 maps it wins 97 for the default's 109 (rg3-vs16, +12 -24). Off.
     * reinforce_march: a group on its way fights as one body, every member at the enemies about it once one comes
     * within 10 cells of any of them, and a member more than 12 cells ahead of the one a third of the way back
     * stands until the rest are up (Military.marchGroup). A group kills 1.4 for one of its own on the way with it
     * (119 for 85 an hour, ro1log-vs16) and nothing waits; level in the wins (ro3-vs16, 109 for 109). On in forGame
     * against several enemies on large maps, with retreat_regroup.
     */
    int reinforce_gather = 0;
    boolean reinforce_march = false;
    /**
     * retreat_regroup (strength, 0 = off): an army that would turn back (worn, outmatched, or before contact) with
     * that much strength of reinforcements on their way within retreat_regroup_reach cells of it falls back on them
     * instead of walking home: its units that are not in a fight walk to the nearest of them, they keep coming, and
     * once those have joined or fallen, or after retreat_regroup_ticks, the army is weighed anew (the worn retreat
     * against what it is then). No second one within retreat_regroup_cooldown_ticks of the last. As written, a
     * retreat sends the reinforcements on their way home with the army: at a worn retreat the army is 16 strong and
     * 21 are on their way to it, at an outmatched one 26 and 16 (rt2log-vs16, 2.6 retreats an hour of game, 49
     * reinforcements an hour turned back), and the next attack leaves 65 s (median) after the army is home, with 49.
     * At 8: 0.9 to 1.6 regroups an hour, all joined within a minute, 33 reinforcements turned back for 49 and a tenth
     * more of them joined; half are followed by a retreat within two minutes all the same (rq1log-vs16). Level in the
     * wins (rq3-vs16, 108 and a draw for 109). On at 8 in forGame against several enemies on large maps.
     */
    float retreat_regroup = 0f;
    /** retreat_regroup only when the army with those reinforcements would not have turned back. */
    boolean retreat_regroup_need = true;
    int retreat_regroup_reach = 120;
    float retreat_regroup_ticks = 4500f; // 90 s
    float retreat_regroup_cooldown_ticks = 3000f; // 60 s
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
     * satellite: a finished armory that is not the main one keeps working. The engine delivers a load to the armory
     * nearest its supply (Unit.nearestSupplyBuilding), so a gatherer sent to ore nearer an old armory is linked to
     * it; drainSecondary recalled it within 10 s and the plan, which does not count it, sent the next one (from
     * minute 16 a quarter of the iron gatherers' time ended in a delivered load: lab/fable/NOTES.md, the recall
     * loop). With it such gatherers stay, ore is picked by its walk to the nearest armory, and the old armory gets
     * the share of the wood gatherers and workers that its share of the iron gatherers asks for.
     */
    boolean satellite = false;
    /**
     * sat_staff: a satellite gets the peons its stock can use. Its workers and wood gatherers come out of the peons
     * inside it, and only gatherers it recalls ever go in, so a satellite the iron gatherers were linked to sat on
     * its iron: eg0-log-vs15 s21194, minutes 60-90, 45 iron gatherers, 36 iron and 17 wood in stock, 1 worker
     * inside and 1 wood gatherer of 26 wanted, while the main armory 50 cells away held 45-130 idle workers and no
     * weapon was made; s21589's held 139 iron and 1 wood. With it a satellite is to have a forger for every weapon
     * its stock pays for and a wood gatherer for every four wood its iron lacks (at most SAT_STAFF_MAX each), free
     * peons and then the main armory's spare workers walk over, and it keeps the ones meant for its wood.
     */
    boolean sat_staff = false;
    /**
     * stock_balance: workers the main armory's stock cannot employ go for the wood its iron waits for. The plan
     * splits the pool by the trips' model, and when iron piles up it cuts the iron gatherers and counts the peons
     * freed as forgers, who have no wood to forge with: from minute 20 the armory has under two wood and iron in
     * stock in 22-38 % of the half-minute samples of 16 long games (eg0-log-vs15), with 11-40 iron and 31-60 workers
     * inside. Each plan tick with four or more wood missing (two per iron in stock) and four or more workers
     * inside beyond the forgers the plan's rate of weapons needs (the forge works with everyone inside, so those
     * stay), three more are wanted as wood gatherers, up to the wood missing and STOCK_BALANCE_MAX; three fewer when
     * the wood is there.
     */
    boolean stock_balance = false;
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
     * bank (weapons, 0 = off): from bank_ticks, while the army is home and worth less than bank_army, the main armory
     * lets out only the warriors its empty towers need and keeps every other weapon, with a worker for each, until
     * it holds this many; then all come out at once and the muster's gate has an army to send. It also lets them
     * out when the armory is below half its hit points or is being emptied. After the first campaign every weapon
     * is spent as it is made: the home army loses four warriors a minute, 43 % of them with two mates or fewer
     * around, and attacks go out 12-25 strong, which raze 0.15-0.30 buildings per ten warriors lost against 0.5-1.0
     * for 45 or more (lab/fable/launches.py); xt1-band-vs15 s21056 makes about 15 weapons a minute for three hours
     * while the enemy's 22 buildings stand.
     */
    int bank = 0;
    float bank_ticks = 90000f; // 30 min
    float bank_army = 25f;
    /**
     * bank_always: the bank fills whatever the army does, with an attack out too. After the first campaign an attack
     * is out nearly all the time (see late_reinforce_min), so the bank as built hardly ever starts; and warriors that
     * wait at home for a clump die there instead (pu1-log-vs15, pu2-log-vs15: 355 and 442 of the home army lost a
     * game against 270, and no more copies unhoused).
     */
    boolean bank_always = false;
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
    /**
     * reloc_sat: the hop's gate does not wait for a satellite to be drained. The gate wants every other armory
     * drained (nobody inside, no stock, no gatherers), and a satellite never is: with satellite on, the gate was shut
     * for 653 plan ticks a game (33 min; RL1f-vs15, reloc with reloc_slot and retire on 150 fresh maps, 0.86 hops
     * completed a game).
     */
    boolean reloc_sat = false;
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
     * hold_chieftain_again (0: hold_chieftain): the peons kept in the trainer of a chieftain after the first. Training
     * takes 440 / n^(1/3) s with n inside: 183 s with 14, 129 s with 40. For the first one 40 cost the opening nine
     * warriors at minute 15 (bz9-log-vs15); later ones are trained at the unit cap with a hundred peons about.
     */
    int hold_chieftain_again = 0;

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
    /**
     * late_min_strength (0 = off): from late_min_ticks, while the enemy's warriors are worth twice this or more, no
     * muster with less army and stock than this. After the first campaign the gate lets 12-25 warriors go at a
     * building whose own guard is weak, and they meet the other copies' waves on the way: in 337 attacks of 48 long
     * games (lab/fable/launches.py eg2-log-vs15 hm1-log-vs15 sb1-log-vs15) an attack launched after minute 30 with
     * 12-25 razes 0.15-0.30 buildings per ten warriors it loses, one with 45 or more 0.5-1.0, and the small ones are
     * the most frequent (18-24 per window against 2-19).
     */
    float late_min_strength = 0f;
    float late_min_ticks = 90000f; // 30 min
    /**
     * late_reinforce_min (strength, 0 = off): from late_min_ticks the warriors at home join an attack only as a clump
     * of this strength. At the unit cap, where we are from minute 8, every 12 leave as they are made, so after the
     * first campaign one attack never ends: s21111 of nd1-log-vs15 keeps 40-50 warriors out for 100 minutes on 12
     * more every 45 s, loses 15 a minute and leaves seven copies housed throughout; from minute 20 a game unhouses 1.5
     * copies in ten minutes on 150 warriors where the first campaign takes 4.2 on 135 (lab/fable/rebuild.py).
     */
    float late_reinforce_min = 0f;
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
     * sapper_refill: sappers (peons that pull down towers, 6 damage a swing) used to be recruited only when an attack
     * launched, and only if its first target stood under towers. Against many copies the campaign is one attack that
     * launches around minute 9, before any Hard has a tower, and never ends: the army then met every later base's
     * towers without a single sapper (no "peons go along" line in any logged N=14-16 game), and two towers cost it
     * about 8 warriors a base. With sapper_refill the attack tops its sappers up every 5 s while manned enemy towers
     * stand within 30 cells of its target or 40 of the army: sapper_per_tower each, at least sapper_min, at most
     * sapper_max, taken from the idle and gathering peons nearest the army while the economy keeps 15.
     */
    boolean sapper_refill = false;
    int sapper_per_tower = 2;
    int sapper_min = 6;
    int sapper_max = 16;
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
     * harass_memory_ticks (game ticks, 0 = off): an enemy warrior away from the base is a threat only while a peon of
     * ours is within 12 cells of it, and peons step away from it (evade) or run home, so the threat ends within a
     * second or two, the warriors sent at it stop, and it is there again when the peons come back: eg2-log-vs15
     * s21327 logs a new threat of one or two warriors every 1-2 s for minutes, and from minute 30 an iron gatherer
     * steps out of an enemy's way 69 times a minute a game (16 long games), half of the times from a single warrior
     * and 42 % within 3 s of setting out again; 15-29 % of the gatherers' time ends in a delivered load. With it such
     * a warrior stays a threat for this long after the last peon was near it, while it is within
     * harass_memory_cells of a quarters or armory of ours.
     */
    float harass_memory_ticks = 0f;
    int harass_memory_cells = 120;

    /**
     * Against several enemies, keep gathering away from the enemies while the base is threatened but the armory
     * itself is not: the base is hardly ever quiet, and stopping would starve the armory for good.
     */
    boolean gather_under_threat = true;
    /** Also in a 1v1: on smaller maps the fighting is at the base so often that hiding starves the armory. */
    boolean gather_threat_1v1 = false;
    /** A gatherer counts as stuck after this many round trips (at least 70 s) without its load changing; 0 = 70 s. */
    float stuck_trip_factor = 0f;
    /**
     * stuck_still_ticks (0 = off): a gatherer whose load has not changed for the stuck time (unstickGatherers, 70 s)
     * is stuck only if it has also not left a 5-cell circle for this long. Late iron lies 100 cells and more of walk
     * out, and of a game's 280 gatherers called stuck, 245 were walking (byelog-vs16): 140 on their way out, turned
     * to another supply and their own avoided for two minutes, 105 on their way home with their load.
     */
    float stuck_still_ticks = 0f;

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
     * armory_iron_need (iron nodes, 0 = off): the first armory's site is scored on its nearest 15 iron nodes, so a
     * site with a handful of nodes and water behind them looks as good as one in a field of 60. Of 300 default games
     * (pc1-00, def4-fresh-vs15; lab/fable/oremap.py) the 37 whose site had fewer than 30 nodes within 300 m of
     * walking won 2 and the 18 with 30 to 40 won 7, the others 45 %; on the maps whose 20th node is more than 300 m
     * from the start the sites with under 20 nodes won 0 of 26, the others 11 of 45. With armory_iron_need a site
     * with fewer than this many nodes within armory_iron_reach m of walking costs armory_iron_short_ticks more per
     * missing node, and while the best site is short the search widens to twice max_armory_distance and then to
     * armory_iron_far m of walking from the start (0: no further).
     *
     * Default 50 and 800 since the fresh maps 21301..21600: 102 of 300 armories move and those maps win 34
     * against 23 (in50f-vs15, def5f-vs15; +18 -7); on 21001..21300 85 move, 28 against 23 (in50-vs15; +12 -7).
     * The gain is in the moves of under 100 m to a fuller field (16 -> 24 of 61 on the fresh maps); the moves
     * of 200 m and more win 2 of 18 and 4 of 31 against 1 and 1. Needs of 40, 60 and 70 on 21001..21300: 121,
     * 121 and 128 wins of 300 against 124 and the old 119 (lab/fable/planwins.py).
     */
    int armory_iron_need = 50;
    int armory_iron_reach = 300;
    float armory_iron_short_ticks = 200f; // 4 s a node
    int armory_iron_far = 800;
    /**
     * armory_route_clear (cells, 0 = off): a first armory site whose walk from the start passes within this many cells
     * of an enemy's start costs armory_route_ticks more (SitePlanner.hotRoute). The Hard sends its peons at anything
     * within 30 m of its first quarters and they follow our builders to the site, where builders do not fight back:
     * from seat 0 at N=12-16 on maps 22001..22100 such a walk (under 30 cells: 19 games of 485 with no copy next
     * door, 14 of them to iron 300 m and more out) wins 5 games where the seat-0 rates of the same N make 11, with
     * 8 of our peons dead by minute 5 against 1 (lab/fable/route.py rt as-f4).
     *
     * Off, because the walk is still the better choice: on those 19 games a charge that keeps the armory off such a
     * walk wins 2 (30 cells, 20000 ticks: rv1-vsN-s0) or none (40 cells: rv3, and 9 of the 19 games at 30-39 cells
     * against 11), and a charge of 4000 ticks moves hardly a site (rv2: 5 and 11). Staying by the start starves.
     */
    int armory_route_clear = 0;
    float armory_route_ticks = 20000f;
    /**
     * opening_move_m (meters of walking, 0 = off): with the armory site this far from the start or farther, the first
     * quarters goes up by the armory site like the others, so the whole tribe walks there first. With the first
     * quarters by the start and the rest far (fo1-log-vs15, 16 maps with the armory 320 to 520 m out) the armory
     * stands at 333 s and the sixth quarters at 501 s, there are 82 peons at 8 min against 168, and the peons bred by
     * the start walk to the base past a neighbour's quarters, where they are killed one by one (s21142, s21061).
     */
    int opening_move_m = 0;
    /**
     * opening_prewalk (meters of walking, 0 = off): with the first armory's site this far from the start or farther,
     * the peons with nothing to do walk to it while the scout still carries it out, instead of waiting by the first
     * quarters for the site to be placed. as-f3-vs8-s0 s22088: the site 719 m of walking out is placed at 185 s, the
     * first quarters stands at 41 s, 17 to 26 peons stand idle by it until then and only then set out, and the armory
     * stands at 430 s, three minutes after the neighbours' first warriors; the game is lost at 12:42 with no kill.
     * With the walk ahead it stands at 321 s and the game is won. For every site (pw-vs8/10/12, pwf/pwg-vs15, the
     * peons more than 20 cells from it) the armories under 120 cells out stand 0 to 5 s later, those farther out 39 s
     * earlier: a walk under 200 m takes no longer than the first quarters.
     *
     * Default 300 since the any-seat ladder's seat-0 games on 22001..22100 (bsA-vs8/10/12/14 against as-f3-vsN-s0):
     * 23 of 400 games change, 6 lost ones are won and 1 won one is lost (97 -> 99, 96 -> 98, 89 -> 90, 76 -> 76 of
     * 100). A search out to 1300 m (armory_iron_far) changes none of them, and moving the whole tribe at 500 m
     * (opening_move_m) changes 3 without changing a result.
     */
    int opening_prewalk = 300;
    /**
     * opening_join: before the first armory stands, builders set out for a site while its carrier is still on the way
     * (an order to an unplaced site makes a peon carry it too), instead of waiting for it to be put down. Off: on
     * top of idle_builders=40 it wins 87 and 61 of 100 maps at N=12 and 15 against 90 and 64 (ibj-vsN against
     * ib40-vsN, maps 22001..22100; +3 -7 against the default). Why is not checked; a builder sent to a placed site
     * chops where it stands and walks there with the wood, one that carries the site may arrive with none.
     */
    boolean opening_join = false;
    /**
     * idle_builders (0 = off): while no armory of ours stands, before the first one and after the last one fell, a
     * peon the allocation would leave standing joins a site that is going up, the armory's first, until the site has
     * this many builders. buildersWanted gives a site 4 builders a tree within 12 cells and 6 at least, so that
     * builders are not taken from other work for wood that is not there; with no armory there is no other work.
     * idl-vs15 (100 maps, counters idle_s_*, lab/fable/idle.py): 33 games stand 300 peon-seconds and more after
     * minute 15, all of it with no armory, where the armory going up again takes 157 to 276 s with 60 to 178 peons
     * standing by it (32 to 82 s while another armory stands); and s22022 opens with 6 builders on the armory and 110
     * peons standing.
     *
     * At 100 it does what it says (standing after minute 15 from 2,579 to 207 peon-seconds a game at N=15, a lost
     * armory back after 81 s instead of 116, the first armory of the two worst openings 74 and 92 s earlier) and the
     * wins hardly move: on the default before release_builders +19 -19 over 1,200 seat-0 games (N=8-16 on
     * 22001..22100, N=12-16 on 23001..23100; ib100-vsN, ib100h-vsN; at 40 the same wins with half the standing left),
     * on the default with it +19 -10 over 700 (ibn-vs12/15, ibnh-vs15, ibnn-vs13/15 against rel2-vsN, rel2h-vs15,
     * nb2-vsN). Default 100 since 2026-11-19 on the user's word that a peon with nothing to do should work.
     */
    int idle_builders = 100;
    /**
     * opening_builders (0 = off): idle_builders for the opening alone, before our first armory has stood: a peon left
     * over joins a site, the armory's first, until it has this many builders. Off: at 100 it fires in 4 to 23 games
     * of 100 and is +17 -16 over 1,200 seat-0 games (ob-vsN, obh-vsN).
     */
    int opening_builders = 0;
    /**
     * release_builders: a builder is free for the next job the moment its quarters or tower stands, instead of when
     * the engine lets go of it. The engine's RepairController only ends by the building: a builder with no wood chops
     * once more for the finished building, carries the wood back to it and goes idle there, and the allocation takes
     * idle peons only (the user, 2026-11-19: "once they've gotten wood they'll just go stand around the previous
     * quarters they were working on").
     *
     * Default since 2026-11-19, for the builders of a building just gone up (release_fresh_ticks): the first armory
     * stands 7 to 10 s earlier and the sixth quarters 9 to 14 s (100 and 200 maps a run at N=12, 13, 15), with 2 to
     * 4 more warriors at minute 8. Wins +142 -119 over 1,600 seat-0 games: N=8-16 on 22001..22100 and N=12-16 on
     * 23001..23100 +94 -82 (rel2-vsN, rel2h-vsN against as-f4-vsN-s0, as-f4h-vsN-s0), 200 maps never used before
     * 174 -> 177 at N=13 and 113 -> 121 at N=15 (nb2-vsN against nb0-vsN, 24001..24200); lab/fable/proxy.py's win
     * chance at minute 20 +2.1 +- 0.7 points over the 1,400 games at N=12-16. Every game changes, from second 42.
     */
    boolean release_builders = true;
    /**
     * release_fresh_ticks: release_builders frees the builders of a building for this long after it went up (0: of
     * any standing building that needs no repair, a repair's too: +91 -99 over the first 1,200 of those games, the
     * same earlier opening, rel-vsN, relh-vsN).
     */
    float release_fresh_ticks = 3000f; // 60 s
    /**
     * builder_tree (cells, 0 = off): a builder sent to a site with no wood first chops the tree with the shortest way
     * from where it stands by the tree to the site, when that way is this many cells or more shorter than by the
     * tree nearest to it, which the engine's builder chops whatever the direction (the user, 2026-11-19: "picking an
     * optimal tree to harvest on the way over"). tr-vs12 (30 logged openings, TREE lines): of 115 builders a game
     * sent with no wood in the first 8 minutes the nearest tree costs 4.3 cells of walking more each, 492 a game,
     * and 16 of them 10 cells and more.
     *
     * Default 6 since 2026-11-19: 77 to 127 builders a game go by a tree of ours, the first armory stands 2 to 5 s
     * earlier in each of four runs (237 -> 232 s and 236 -> 234 s at N=12 and 15 on 22001..22100, 235 -> 232 s and
     * 239 -> 238 s at N=13 and 15 on 24001..24200) and the sixth quarters 1 to 6 s; wins +79 -74 over 700 games, the
     * model at minute 20 +1.0 +- 0.9 points (btr-vsN, btrh-vs15, btrn-vsN against rel2-vsN, rel2h-vs15, nb2-vsN).
     * Thresholds of 3 and 10 cells were only tried on 16 openings (bt3-vs12, bt10-vs12).
     */
    int builder_tree = 6;
    /**
     * armory_spare_ticks (game ticks, 0 = off): from then on a second armory is kept as a spare (Economy.planSpare).
     * idl-vs8/12/15 (lab/fable/noarmory.py): of the 244 games in which an armory of ours stands at every moment after
     * the first 243 are won, of the 56 with a moment of none 8; before that moment we stand at exactly one armory for
     * a median of 355 s at N=15 (17 and 21 min in s22027 and s22016): a tower takes the razed armory's building slot
     * at once, and the expansion rule wants cheaper gathering and a base with no threat.
     *
     * Off: from minute 10 (30000) the time at one armory falls from 28 % to 4 % in the games won, as many games
     * reach a moment with none (46 against 44 at N=15), and it wins 55 of 100 against 62 at N=15 (+2 -9) and 90
     * against 90 at N=12 (sp-vsN); with idle_builders=100 55 and 91 (spi-vsN). The moment with no armory marks a base
     * that is being overrun; a second armory there does not hold it and costs a tower's slot.
     */
    float armory_spare_ticks = 0f;
    /**
     * opening_fallback: when the first armory's site is lost before any armory of ours has stood, the next one goes
     * up by our safest quarters, as a lost armory's does, instead of on the same site again, and the opening's
     * quarters not carried out yet are dropped when they lie more than 60 cells from it. as-f3-vs11-s0 s22003: the
     * site 799 m out falls at 3:42, 7:58 and 10:16 and is planned again each time; from minute 11 up to 39 peons
     * stand idle by the one quarters until the game is lost at 16:09 with no armory ever built.
     */
    boolean opening_fallback = false;
    /**
     * opening_keepout (cells, 0 = off): the opening's buildings keep a square of this half size (plus their own and
     * the usual gap) around the start of every copy that starts within opening_keepout_range cells of ours. A Hard
     * copy sends its idle peons and gatherers at whatever of ours comes within 30 m of its quarters, and its idle
     * peons answer anything within 8 cells themselves; with a copy next door our first quarters goes up inside that
     * circle and the builders are killed at it (bsA-vs14 s22011: the copy 15 cells away, the quarters site 24 cells
     * from its start, 19 of 20 peons dead in the first minute with the strike and, without it, out at 1:12).
     */
    int opening_keepout = 0;
    int opening_keepout_range = 40;
    /**
     * expansion_iron_need (iron nodes, 0 = off): the opening stays by the start, and when the first armory stands with
     * fewer than this many nodes within armory_iron_reach m of walking, a second one is planned at once (not before
     * expansion_iron_ticks) on the site the first-armory search finds when it charges for missing iron, up to
     * expansion_iron_far m of walking from the start. With the whole base at such a site (armory_iron_need 40,
     * in40-vs15: 21 maps with the armory 350 m and more out) the armory stands 95 s later and there are 101 units at
     * 8 min against 193; 2 of 21 games are won against 0.
     */
    int expansion_iron_need = 0;
    float expansion_iron_ticks = 0f;
    int expansion_iron_far = 800;
    /**
     * expansion_iron_crew: this many peons walk out with the placer of that second armory (idle ones, tree and rock
     * gatherers), so they arrive with the site. With the placer alone (fo3-log-vs15 s21036) the site 519 m out is
     * planned at 231 s, placed at 334 s and stands at 466 s: its builders set out when it is placed and each carries
     * a piece of wood cut by the start.
     */
    int expansion_iron_crew = 1;

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
    /**
     * freeze_min_eta_ticks (game ticks of peon walk, 0 = off): no strike when the nearest copy starts nearer than
     * this. The squad then arrives while all twenty of the copy's peons still stand idle by its start; they take the
     * squad on and go on to our builders next door. In the any-seat ladder's games at N=12..16 (as-f3-vsN-sS, 1149
     * games) the 8 with a copy under 20 cells away lose 15.2 of our 20 units by 2:00 and the 7 at 20 to 29 cells lose
     * 10.7, none of the 15 is won and the strike puts the copy out in 0 and 3 of them; from 30 cells on we lose 2.0
     * and less and the strike puts it out in 64 to 85 %. bsA-vs14 s22011: the copy 34 m of walking away, 19 of our 20
     * peons dead within the first minute, out at 5:54.
     */
    float freeze_min_eta_ticks = 0f;
    /**
     * freeze_near_ticks (game ticks of peon walk, 0 = off): against a copy that starts nearer than this the first
     * strike takes freeze_near_squad peons instead of freeze_squad (all but one at most). Skipping the strike there
     * (freeze_min_eta_ticks 700 on the 15 ladder games with a copy under 30 cells away: out before minute 10 in 13
     * against 9, none won either way) and building clear of the copy (opening_keepout 30: out before minute 10 in 7,
     * one won) both leave the copy's twenty peons to kill ours, who are under orders and do not answer.
     */
    float freeze_near_ticks = 0f;
    int freeze_near_squad = 19;
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
     * micro_step (1 = off): what a target out of throwing reach is worth to micro_targets against one in reach, and a
     * warrior walking up to its target takes a better one in reach instead. The targets are chosen within 9 cells and
     * a throw reaches 7.9, a cell of walk is half a second of a 2-s throw, and the target walks too: with an opposing
     * warrior within 8 cells our attackers throw 72 % of the time and walk up to their target 21 % (ft1log-vs16,
     * lab/fable/fighttime.py; the engine's own choice, as the Hard copies fight, walks 26 %).
     */
    float micro_step = 1f;
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
     * chief_retrain_late: from chief_refresh_from_ticks, at the unit cap (training costs no
     * births there) a chieftain who died is retrained with one finished quarters and no armory, in a quarters with no
     * awake enemy warrior within chief_refresh_clear cells; the trainer keeps hold_chieftain peons at the cap too and
     * is
     * topped up whenever no threat is within 20 cells of it (s7125: no chief for the last 105 min with 2 quarters and
     * no armory). Below the cap the old gates hold (smoke: chiefs trained at once in a fight died at once).
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
     * shred_fill (share, 0 = off): the strict mission blasts from where the chieftain stands only once that catches
     * this share of what the blob's cast point would, unless an awake enemy warrior is within 9 cells of him. Without
     * it he blasts at the first shred_min in reach, from the blob's edge: 10 of a blob of 40 (bz2-log-vs15 s21318),
     * and never from a cast point (0.0 of 12 casts a game).
     */
    float shred_fill = 0f;
    /**
     * shred_buildings: the most buildings of ours a shred blast may reach (18 cells). At 2 none of them may be below
     * 40 hit points, as before; above 2 each finished one must outlast what the blast takes at its distance (30 from
     * four cells out, 150 at the caster) by 10. The old rule shuts the blast out of our own base, where the waves
     * are: bz5-log-vs15 s21318, minutes 20-24, charged for 200 s with blobs of 17-42 within 15-28 cells of him.
     */
    int shred_buildings = 2;
    /**
     * shred_base: enemy warriors within 20 cells of a finished building of ours count as blob members, awake or not.
     */
    boolean shred_base = false;
    /**
     * shred_bait: with the blast charged the strict chieftain stands still for shred_min awake enemy warriors within
     * 24 cells instead of backing off, and with one of them within 9 cells he blasts whatever is in reach, however
     * near they stand. A copy launches at our unit nearest its first idle warrior, so a blob he waits by comes to
     * him (bz5-log-vs15: 7.6 of 10.3 casts a game are made with awake warriors at him).
     */
    boolean shred_bait = false;
    /**
     * shred_from_ticks (game ticks): the shred mission, strict or not, starts then; before it the chieftain marches
     * and stuns with the army as without shred, and counts in the muster's gate. A strict chieftain from the start
     * costs the first campaign its stun and the gate its bonus: 0.12 copies fewer out at minute 15 (bz2-band-vs15).
     */
    float shred_from_ticks = 0f;
    /**
     * shred_lurk (cells, 0 = off): while the blast charges, the strict chieftain waits this far from the centre of
     * the next blob, out of every enemy warrior's scan, instead of behind the armory. His walks home and back took
     * most of a charge: in minutes 10-40 of bz2-log-vs15 he stands charged with a blob 33-50 cells away in half of
     * the 20 s samples and casts 3 times in ten minutes of 8 possible.
     */
    int shred_lurk = 0;
    /**
     * shred_press: charged, with a blob to blast and its cast point within shred_press_cells, the strict chieftain is
     * not turned back by an awake enemy warrior within 9 cells: he walks on to the cast point with whatever hunts
     * him behind him, and blasts once the fill is reached or he is hit with shred_min in reach. Walking home does not
     * shake a hunter (it walks as fast as he does, and only the blast or our towers end it): 10 times an hour of game
     * one to seven of them turn him back, 330 s of a charged blast, with a blob of 15 and more within a 15 s walk in
     * 60 % (cf0log-vs16, ChiefTrace). Off: with shred_point_blank the blasts kill 248 an hour for 234 and 2.3
     * chieftains are lost for 1.6 (he stands six seconds in the cast with what followed him), 103 wins of 150 for the
     * default's 110 (pp2-vs16).
     */
    boolean shred_press = false;
    int shred_press_cells = 30;
    /** shred_press: while he is being hit (2 s after a hit) he walks on only to a cast point this near. */
    int shred_press_fire_cells = 12;
    /** shred_press: only with this many hunters of his within 12 cells or fewer; more of them turn him back. */
    int shred_press_hunters = 3;
    /**
     * shred_stick (factor, 1 = off): the blob chosen within the last 10 s scores this much more than the others
     * (Chieftain.findBlob scores members / (30 + cells)). The sizes jump with every launch, and without it he turns
     * round between blobs 100 and more cells apart every few seconds: a cast point jumps 3.9 times, 119 cells, in a
     * walk that ends in a blast (cf0log-vs16, s22005 at 1119-1207 s). Off: with the sidestep and point blank it adds
     * nothing to point blank alone (sk2-vs16: 230 killed an hour for 234, 107 wins of 150).
     */
    float shred_stick = 1f;
    /**
     * shred_sidestep: the strict chieftain keeps out of sight of enemy warriors on an attack-move: when the next 14
     * cells of one's way come within 10 cells (Chebyshev; a walking warrior scans 8) of him or of his next 4 cells, he
     * steps to the nearest of 32 cells about him that is clear of every such way, and his waiting points are chosen
     * clear of them. 24 times an hour of game an enemy warrior begins to hunt him, 16 of them with a walking wave
     * within 17 cells the second before and 8 with parked warriors only (cf2log-vs16, ChiefTrace): the waves that
     * walk past see him, not the blobs he walks up to. Off: a walking wave sets a hunter on a charged chieftain 3.1
     * times an hour for 8.8 (cd1log-vs16), he walks 100 s an hour more, and the blasts kill 210 for 204 (sj2-vs16, 104
     * wins of 150 for 110).
     */
    boolean shred_sidestep = false;
    /**
     * shred_defend (0 = off): within 2 s of a hit, a charged strict chieftain blasts from where he stands with this
     * many in reach instead of shred_min: whoever hits him follows him as fast as he walks.
     */
    int shred_defend = 0;
    /**
     * shred_point_blank: with an awake enemy warrior within 9 cells, an enemy within 5 cells of him does not rule out
     * a blast from where he stands (castValue's rule for cast points: what stands that near throws before the blast
     * lands). Whatever is that near has seen him already, and with it there he could not blast at all: charged with 8
     * and more in reach he stands 64 s an hour of game under that rule, and 1.1 of his 1.7 deaths an hour come with
     * the blast charged (cf0log-vs16, cp1log-vs16, ChiefTrace). Off: 11.7 blasts an hour for 10.3 and 234 killed by
     * them for 204 at the same chieftains lost, and 105 wins of 150 for 110 (pb2-vs16), 210 of 300 for 214 (pbm-vs16).
     */
    boolean shred_point_blank = false;
    /**
     * shred_ahead: with shred_lurk and no blob, he waits by the target of the biggest group of enemy warriors on one
     * attack-move (shred_min or more, the target within shred_range of our armory): where they will stand parked.
     * With the blast charged he has no blob in 730 s of a 70-minute game and walks to cast points for 700 s, at 1.4
     * cells a second from 50-100 cells away (bz10-log-vs15: 10 casts a game where the charge allows 40).
     */
    boolean shred_ahead = false;
    /**
     * shred_gate: the muster's gate counts a strict chieftain as if he marched along. Without his bonus the gate
     * asks for 40 % more army and the campaign starts later: 0.12 copies fewer out at minute 15 (bz2-band-vs15).
     */
    boolean shred_gate = false;
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
     * armory_cover (towers, 0 = off): while a finished armory has fewer towers than this within 15 cells (standing,
     * sites and planned), the next tower goes by it, the armory with the fewest first, before the turn of the
     * primary armory, the exposed quarters or a front. Towers anchor on the primary armory and on the building
     * nearest each enemy, so the other armory has what the fronts gave it; the 20-building cap is reached by minute
     * 15 in nine games of ten, after which no armory is built until one is lost (0.01-0.13 built a game per five
     * minutes between minutes 15 and 30, 0.6 razed in the games won and 1.3 in the others), and of the armories
     * razed in minutes 15-35 those with no tower within 15 cells stood in 12 % of the cases, with one in 17 %, with
     * two in 44 %, with three or more in 57 % (lab/fable/armories.py in50f-vs15 def6f-vs15).
     */
    int armory_cover = 0;
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
     * later (nine shepherds of ten die, most on the way: Shepherd.tend). At N=15 on the default of 2026-11-20
     * (sg30-vs15, sg90-vs15, 100 maps): 30 s loses 10 wins of 70 and 90 s 26, with 24 and 54 fewer shepherds lost
     * a game and 7 and 16 more waves at our base. A wave at the base costs far more than the peon.
     */
    float shepherd_gap_ticks = 0f;
    /**
     * shepherd_home_pair_ticks (game ticks): shepherd_home_pair's second shepherds only from this game time on. From
     * the start (hp200-vs15, hp120-vs15, 100 maps at N=15 on the default of 2026-11-20) the pair wins 71 for 70 with
     * 137 more shepherds lost a game and 1.6 to 3.3 fewer waves at our base: the state model is 6 to 9 points down at
     * minute 20 and level again at minute 40. From minute 12 (hpl12-vs15) 70 for 70; from minute 20 (hpl20-vs15,
     * hpl20h-vs15, 60000) 74 for 70 and 64 for 65, +10 -7 over 200 games, the model at minute 40 -0.9 +- 1.8 and
     * -3.6 +- 2.0, 67 to 97 more shepherds lost a game and waves at our base 19.9 -> 15.7 and 19.9 -> 19.3: it buys
     * nothing that shows for the peons it costs, at any start. Off.
     */
    float shepherd_home_pair_ticks = 0f;
    /**
     * shepherd_home_spare: shepherd_home_pair's second shepherd is for the time the copy's own one is away with the
     * parked wave. While the own flock's origin is at the copy's home (before its first wave, and whenever its oldest
     * idle warrior stands there), no second one is recruited, and one that stands there goes home once the own one is
     * within 40 cells. As written the pair sent both to the same place: of the second shepherds recruited in the
     * first ten minutes 82 % had their spot within 30 cells of the own one's (hplog-vs15, 8 logged games at N=15: 22
     * a game), and 21-27 % later.
     */
    boolean shepherd_home_spare = false;
    /**
     * shepherd_home_pair_until (game ticks, 0 = never): from then on no new second shepherd is recruited; one that
     * stands stays. Started at minute 20 the second shepherds buy nothing (hps20-vs15: waves at our base 18.7 for
     * 19.9 with 88 of them recruited a game), and those sent across the field after minute 20 reach a spot in one
     * case of eight, which read as if the pair gained only by the ones that walked out early. It does not: at N=16
     * on 300 maps an end at minute 12, 20 and 30 wins 144, 151 and 151 where no end wins 162 (hpu12-vs16, hpu20-vs16,
     * hpu30-vs16 against hpa-vs16: +23 -41, +18 -29, +7 -18), the worse the sooner. Off.
     */
    float shepherd_home_pair_until = 0f;
    /**
     * shepherd_detour_ticks (game ticks, -1: shepherd_fixes_ticks): from when shepherd_detour acts. It came in with
     * the late fixes (stuck, no-progress and no-spot releases, each with a gap of minutes for the copy), all behind
     * shepherd_fixes_ticks = 40 min, though it is a walk round what stands in the way and not a release.
     */
    float shepherd_detour_ticks = -1f;
    /**
     * shepherd_route: where a shepherd's straight walk to its spot is not clear (walkClear), its walk order goes
     * three blocks along the cheapest way over a grid of 4-cell blocks, on which a block by an enemy warrior, base or
     * tower costs 80 cells more (Shepherd.route). Half the shepherds lost never had a wave aimed at them, a quarter
     * to a third of those recruited after minute 20 reach a spot, and what there was for the walk looks 8-60 cells
     * ahead (shepherd_detour, shepherd_safe_walk, shepherd_recruit_clear: all flat, lab/fable/NOTES.md).
     */
    boolean shepherd_route = false;
    /**
     * shepherd_route_near (blocks of 4 cells): the hot blocks within this many of the spot's are free. The spot lies
     * by its copy's group, so every way ends through hot blocks, and their price there tells the search nothing while
     * it widens it: with 0 a plan that found a way looked at 2,400 blocks and 1,300 plans a game ran out of their
     * 8,000 (tmprt-vs16), 7 % of the simulation's CPU. With 5 at N=16 on two blocks of 300 maps: 178 wins for 172
     * and 178 for 165 (rn5-vs16, rn5m-vs16: +91 -72), the plans that find no way 1,240-1,420 -> 590 a game, the
     * planner 4.7 % of the CPU for 6.2 %. 3: 167 for 172 on the first block.
     */
    int shepherd_route_near = 5;
    /**
     * shepherd_unloaded (cells, 0 = off): the recruit is the nearest eligible peon that carries nothing, when it is at
     * most this many cells farther from the wave's origin than the nearest of all. A peon with a load walks at 4 m/s,
     * a warrior's speed, and has no order to drop it, so a loaded shepherd cannot leave a wave behind: 40 of the 158
     * recruits a game at N=15 carry one (shepherd_recruit_loaded), four in five recruits after minute 10 are
     * gatherers, and 88 % of the shepherds lost die in a flee (lab/fable/shepherds.py, pb0-vs13). Off: it does what
     * it says (loaded recruits 40 -> 1 a game) and the load is not what kills them, 93 % of the recruits are lost
     * with it as without, while the farther peon comes later: at N=15 +19 -35 over 300 games (su40-vs15, su1k-vs15,
     * su1kh-vs15), the state model at minute 20 -4 to -7 points, 2 to 7 more waves at our base a game.
     */
    int shepherd_unloaded = 0;
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
     * shepherd_feed (off): from feed_from_ticks, while feed_towers towers of ours are manned, the shepherds of the
     * feed_copies copies whose wave stands nearest lead it to our towers instead of away from them: the spot is the
     * ring cell nearest the manned tower nearest the wave that has feed_cover other manned towers within 14 cells.
     * While feed_busy enemy warriors stand within 40 cells of a manned tower only the waves within feed_commit cells
     * of their tower are fed; the others are led away as ever. A wave that targets a tower of ours loses 35 warriors
     * for 4 of ours and half a tower (lab/fable/killzone.py, fd0-log-vs15); in the field the army trades at 1.7-1.9
     * for one (lab/fable/towerkills.py), and a copy only arms new warriors while fewer than its wave size stand idle
     * (AdvancedAI), so what dies at the towers is not met in the field.
     */
    boolean shepherd_feed = false;
    float feed_from_ticks = 36000f; // 12 min
    int feed_towers = 6;
    int feed_cover = 1;
    int feed_copies = 3;
    int feed_busy = 30;
    int feed_commit = 50;
    /**
     * shepherd_feed: only towers with no quarters of ours within this many cells take fed waves (0: any tower). Fed
     * into the whole base, twice as many peons die at home and 1.5-2 more quarters fall a game (fd4..fd7-log-vs15).
     */
    int feed_quarters_clear = 0;
    /**
     * shepherd_army_weight (0 = off): a spot scores this per cell from what the attack is fighting for (its target,
     * else its centre), up to shepherd_army_cells, on top of the cells from our start; the flee half of it per cell
     * gained. Away from our start is towards the far copies, where the army goes next: beyond 200 cells from our
     * start it kills 1.7 warriors for one of ours (3.6 in the middle, lab/fable/zones.py fd0-log-vs15), and 59-80 % of
     * the warriors it kills from minute 15 belong to copies it is not attacking.
     */
    float shepherd_army_weight = 0f;
    int shepherd_army_cells = 120;
    /**
     * bastion (0 = off): from bastion_from_ticks, this many of our towers stand together about bastion_cells from the
     * home armory on the enemy's side, 40 cells or more from every quarters and armory of ours (Economy.bastionSite);
     * a tower that falls there is the next one built. feed_bastion: fed waves are led only there (feed_towers then
     * counts the manned towers of the bastion), so they die away from the peons, quarters and armories that the
     * waves fed into the base cost (fd4..fd7-log-vs15).
     */
    int bastion = 0;
    float bastion_from_ticks = 24000f; // 8 min
    int bastion_cells = 50;
    boolean feed_bastion = false;
    /**
     * breach: the attack's sappers as a standing squad. Without it they trail six cells behind the army's centre, go
     * only for a tower within 18 cells of that centre with little around it, and are replaced two at a time by
     * gatherers that walk to the army alone: an army jammed in the trees feeds its head to towers the sappers never
     * come near (fd0-log-vs15 s21001: 37 warriors in 155 s under two towers), a manned tower kills a warrior every four
     * seconds it has one in reach (lab/fable/etowers.py: 0.26 a second, 148 warriors a game in sb1-00), and razing one
     * costs nine warriors although 17 swings of a peon bring it down. With breach: breach_sappers peons while a manned
     * enemy tower stands, topped up every breach_refill_ticks when breach_refill_min are missing; they stand with the
     * army's head (the warriors farthest along its path), and all within breach_charge_cells go for a manned tower as
     * soon as breach_contact warriors of the attack are within its reach. A sapper far from the army waits at the
     * staging point and walks out with a reinforcement group. breach_axes: this many warriors by such a tower with no
     * enemy warrior within throwing reach throw at it too.
     */
    boolean breach = false;
    int breach_sappers = 12;
    float breach_refill_ticks = 1000f; // 20 s
    int breach_refill_min = 3;
    int breach_contact = 1;
    int breach_charge_cells = 45;
    int breach_axes = 0;
    /**
     * breach_gate (warriors, 0 = off): the sappers of br1-log-vs15 reached a charged tower 10 to 30 s after the first
     * warrior did (they stand behind a column jammed in the trees), and the warriors walked into its reach one by one
     * meanwhile. With the gate the attack's warriors stay 19 cells or more from a manned, awake enemy tower (those
     * inside with no enemy warrior in throwing reach step out) until this many of them (or six tenths of the army)
     * stand within 26 cells of it and six sappers within 30, or breach_gate_ticks have passed since the first of them
     * came within 34 cells, or eight are inside anyway. breach_solo: while the gate is shut and at most
     * breach_solo_foes enemy warriors stand within 12 cells of the tower, the sappers go in alone for up to 15 s, so
     * the garrison throws at peons.
     */
    int breach_gate = 0;
    float breach_gate_ticks = 750f; // 15 s
    boolean breach_solo = true;
    int breach_solo_foes = 2;
    /**
     * raze (cells, 0 = off): the copies still housed at minute 20 decide the game (800 games of sb1: 8 or fewer 60 to
     * 88 % won, 9 30 %, 10 15 %, 11 or more 2 %), and a copy takes the army 108 s, of which 40 are the march
     * (fd0-log-vs15: 15 s lingering, 40 marching, 19 from arrival to the first raze, 34 razing). At the base nothing
     * orders a warrior onto a building: the army attack-moves, and the engine's scan picks warriors, then peons, then
     * towers, and quarters and armories last, so the 400 throws the buildings need come after every peon in sight.
     * With raze the attack's warriors within this many cells of an enemy quarters, armory, site or manned tower (when
     * raze_towers) throw at the nearest of them, once raze_min of them are that near; a warrior with an enemy warrior
     * or chieftain in throwing reach fights that instead (micro_targets). raze_per (0 = all) caps the warriors on one
     * building.
     */
    int raze = 0;
    /**
     * storm (cells, 0 = off): a manned enemy tower kills the warriors in its reach (15.9 cells; they throw 8.9 at it)
     * and nothing orders them onto it: the army attack-moves, and the engine's scan takes any warrior or peon within
     * 8 cells before a tower. TowerTrace, 12 logged games at N=16 (tw0log-vs16): 16 towers razed a game, each standing
     * 29.5 s from the first warrior in its reach (19 s before anything damages it) for 9.4 warriors; of the
     * warrior-seconds in reach 8 % throw at the tower, 21 % chase peons, 25 % walk. With storm every warrior of the
     * attack within storm_join cells of a manned tower that has one of them within storm cells is ordered onto it (a
     * hunt: it walks up without scanning and throws), once storm_min of them are within storm_join; one with an enemy
     * warrior or chieftain in throwing reach fights that first. storm_sappers: the sappers within 30 cells go in
     * with them (the garrison takes warriors before peons). storm_close (cells, 0 = off): a hunt stops at the first
     * cell in reach, 8.9 from the tower, so the first dozen warriors of a column stand in an arc there and the rest
     * behind them, in the tower's reach and out of their own (st1log-vs16: half the warrior-seconds in reach are the
     * walk up, 12 throw while 30 wait). With it a warrior walks to within storm_close cells of the tower first, on its
     * own bearing, and throws from there, or from where it stands in reach once it gets no nearer.
     */
    int storm = 0;
    /**
     * bait: a tower site of ours within an enemy garrison's scan square, placed and kept up by a crew of the sappers
     * before the army comes: the garrison throws at the first thing it finds until that is dead, and does not look
     * again meanwhile (Bait). It holds a garrison when it is in place in time (bt2log-vs16: a site mended from 1 to
     * 43 hit points under a garrison's throws for 50 s; towers razed under it cost 0 to 3 warriors for 9), which is
     * one tower in fifteen (bt10log-vs16: 3.4 garrisons an hour of game, 1.2 towers razed under one). As built (two
     * building slots kept free, ten sappers with the army) it wins 86 to 88 of 150 for 110, with the hold, without
     * it, and with the storm on the towers it holds (ba2, bc2, bb2 against ml2-vs16); the slots alone are not the
     * cost (q72-vs16: seven quarters and a tower fewer, 104). Off.
     */
    boolean bait = false;
    /** bait: towers baited at a time (each takes a building slot our towers leave free), sappers a crew. */
    int bait_jobs = 2;
    int bait_crew = 5;
    /** bait: a job starts on a manned tower this near the attack's head, or twice as far by its target. */
    int bait_start = 60;
    /** bait: how long the attack's warriors are kept out of a tower's square for its bait, once a tower. */
    float bait_hold_ticks = 1000f; // 20 s
    /** storm_locked: the storm goes only for towers whose garrison is on a bait. */
    boolean storm_locked = false;
    float storm_close = 0f;
    /**
     * storm_per (0 = all): the warriors on one tower, the nearest. All of them is worse than none: 95 wins of 150 for
     * 110 (sa2-vs16 against ml2-vs16, +11 -26; with storm_close the same, sb2-vs16), with 0.27 more copies housed
     * at minute 20. A dozen to two dozen throw from the arc at 8.9 cells and fifty more stand behind it for the ten
     * seconds the tower lasts, and those fifty are the ones that cut the copy's peons down and pull its quarters
     * down meanwhile. The garrison kills one in four seconds however many stand in its reach. With 24 the same 95
     * (sc2-vs16), with 12 103 (sd2-vs16). Below 0: no warrior, the sappers alone.
     */
    int storm_per = 0;
    /**
     * storm_lead: a sapper's swing does 6 to a tower every 2.2 s, as much as 3.6 warriors throwing, but the sappers
     * trail six cells behind the army's centre and reach a stormed tower as it falls (st1log-vs16: 792 sapper-seconds
     * in reach a game, 96 of them swinging). With it they walk with the army's head while a manned enemy tower stands
     * within 45 cells of its centre.
     */
    boolean storm_lead = false;
    int storm_join = 24;
    int storm_min = 8;
    boolean storm_sappers = true;
    int raze_min = 6;
    boolean raze_towers = true;
    int raze_per = 0;
    /**
     * split (strength, 0 = off): the copies still housed at minute 20 decide the game, and one army takes a copy
     * every 108 s, of which the march is 40 and the rest is the copy's own warriors and walls (lab/fable/campaign.py):
     * only a second force takes more. The wing of the third session was 25 warriors gathered from reinforcements and
     * was ground down on its way across the map. With split, an attacking army of at least this strength with no
     * enemy warrior near sends split_share of itself, the warriors nearest to it, at the quarters or armory of
     * another copy within split_reach cells whose defense is at most the force's strength over split_margin; the
     * main army leaves that copy alone. The second force marches and fights as the army does, takes that copy's other
     * buildings and then the next copy in reach; it walks to the main army (as a reinforcement) when it is below
     * split_merge, when the enemy within 24 cells is over split_retreat of it, when nothing is left in reach, or when
     * its march stalls. The next split waits split_cooldown_ticks.
     */
    float split = 0f;
    float split_share = .5f;
    float split_from_ticks = 24000f; // 8 min
    int split_reach = 110;
    float split_margin = 1.3f;
    float split_merge = 20f;
    float split_retreat = 1.2f;
    float split_cooldown_ticks = 1500f; // 30 s
    /**
     * The first runs (sp1..sp3-log-vs15): while split, the half that stays razes 0.62 quarters and armories a minute
     * with 54 warriors where the whole army razes 0.81, but the second force only 0.29: its first target is 100 cells
     * out and it merges as soon as nothing is in reach (no_target, 50 to 86 % of the merges). split_busy: the army
     * may split with enemy warriors within 30 cells worth up to this share of it. split_far: a second force with no
     * target looks again this many times farther, at split_far_margin, before it merges. split_reinforce: a group of
     * reinforcements goes to the second force when that is the weaker of the two.
     */
    float split_busy = 0f;
    float split_far = 1f;
    float split_far_margin = 1f;
    boolean split_reinforce = false;
    /**
     * split_worn (share, 0 = off): a second force down to this share of the most it ever had while still more than
     * 30 cells from its target merges at once (sq4-log-vs15 s21003: 37 went, 28 were left 57 cells out, 19 turned
     * back at 37 cells; and 44 went, 18 turned back at the target).
     */
    float split_worn = 0f;
    /**
     * split_share_max (0 = split_share): with targets only within 80 cells the second force razes as fast as the main
     * army (sr2-log-vs15: 0.62 a minute against 0.58), but the army finds no such target on 158 of its checks a game.
     * Up to this share of the army goes when the target's defense times split_margin asks for more than split_share.
     */
    float split_share_max = 0f;
    /**
     * split_promote (ratio, 0 = off): the reinforcements go to the weaker force, so the second one can outgrow the
     * main army (sq4-log-vs15 s21001: 36 at the split, 82 four minutes later), and when the main army then ends its
     * attack the second force goes home with it. With split_promote a second force this many times the main army's
     * strength becomes the main army: its target is the attack's, and what is left of the old main army walks to it.
     */
    float split_promote = 0f;
    /**
     * shepherd_feed: a feeding shepherd's flee scores this per cell gained towards the tower, where the others score
     * shepherd_flee_out per cell gained away from our start (a third of a shepherd's time is flight, and the wave
     * goes where he stands, not where his spot is: fd2-log-vs15).
     */
    float feed_flee_in = 1f;
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
        sapper_refill = params.getBoolean("sapper_refill", sapper_refill);
        sapper_per_tower = params.getInt("sapper_per_tower", sapper_per_tower);
        sapper_min = params.getInt("sapper_min", sapper_min);
        sapper_max = params.getInt("sapper_max", sapper_max);
        forward_threat = params.getInt("forward_threat", forward_threat);
        raid_threat = params.getInt("raid_threat", raid_threat);
        shepherd = params.getBoolean("shepherd", shepherd);
        shred = params.getBoolean("shred", shred);
        shred_min = params.getInt("shred_min", shred_min);
        shred_min_hp = params.getInt("shred_min_hp", shred_min_hp);
        shred_range = params.getInt("shred_range", shred_range);
        shred_strict = params.getBoolean("shred_strict", shred_strict);
        shred_fill = (float) params.getDouble("shred_fill", shred_fill);
        shred_buildings = params.getInt("shred_buildings", shred_buildings);
        shred_base = params.getBoolean("shred_base", shred_base);
        shred_bait = params.getBoolean("shred_bait", shred_bait);
        shred_from_ticks = (float) params.getDouble("shred_from_ticks", shred_from_ticks);
        shred_lurk = params.getInt("shred_lurk", shred_lurk);
        shred_press = params.getBoolean("shred_press", shred_press);
        shred_press_cells = params.getInt("shred_press_cells", shred_press_cells);
        shred_press_fire_cells = params.getInt("shred_press_fire_cells", shred_press_fire_cells);
        shred_press_hunters = params.getInt("shred_press_hunters", shred_press_hunters);
        shred_stick = (float) params.getDouble("shred_stick", shred_stick);
        shred_sidestep = params.getBoolean("shred_sidestep", shred_sidestep);
        shred_defend = params.getInt("shred_defend", shred_defend);
        shred_point_blank = params.getBoolean("shred_point_blank", shred_point_blank);
        shred_ahead = params.getBoolean("shred_ahead", shred_ahead);
        shred_gate = params.getBoolean("shred_gate", shred_gate);
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
        armory_cover = params.getInt("armory_cover", armory_cover);
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
        shepherd_unloaded = params.getInt("shepherd_unloaded", shepherd_unloaded);
        shepherd_home_pair_ticks = (float) params.getDouble("shepherd_home_pair_ticks", shepherd_home_pair_ticks);
        shepherd_home_spare = params.getBoolean("shepherd_home_spare", shepherd_home_spare);
        shepherd_home_pair_until = (float) params.getDouble("shepherd_home_pair_until", shepherd_home_pair_until);
        shepherd_detour_ticks = (float) params.getDouble("shepherd_detour_ticks", shepherd_detour_ticks);
        shepherd_route = params.getBoolean("shepherd_route", shepherd_route);
        shepherd_route_near = params.getInt("shepherd_route_near", shepherd_route_near);
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
        shepherd_feed = params.getBoolean("shepherd_feed", shepherd_feed);
        feed_from_ticks = (float) params.getDouble("feed_from_ticks", feed_from_ticks);
        feed_towers = params.getInt("feed_towers", feed_towers);
        feed_cover = params.getInt("feed_cover", feed_cover);
        feed_copies = params.getInt("feed_copies", feed_copies);
        feed_busy = params.getInt("feed_busy", feed_busy);
        feed_commit = params.getInt("feed_commit", feed_commit);
        feed_quarters_clear = params.getInt("feed_quarters_clear", feed_quarters_clear);
        shepherd_army_weight = (float) params.getDouble("shepherd_army_weight", shepherd_army_weight);
        shepherd_army_cells = params.getInt("shepherd_army_cells", shepherd_army_cells);
        bastion = params.getInt("bastion", bastion);
        bastion_from_ticks = (float) params.getDouble("bastion_from_ticks", bastion_from_ticks);
        bastion_cells = params.getInt("bastion_cells", bastion_cells);
        feed_bastion = params.getBoolean("feed_bastion", feed_bastion);
        breach = params.getBoolean("breach", breach);
        breach_sappers = params.getInt("breach_sappers", breach_sappers);
        breach_refill_ticks = (float) params.getDouble("breach_refill_ticks", breach_refill_ticks);
        breach_refill_min = params.getInt("breach_refill_min", breach_refill_min);
        breach_contact = params.getInt("breach_contact", breach_contact);
        breach_charge_cells = params.getInt("breach_charge_cells", breach_charge_cells);
        breach_axes = params.getInt("breach_axes", breach_axes);
        breach_gate = params.getInt("breach_gate", breach_gate);
        breach_gate_ticks = (float) params.getDouble("breach_gate_ticks", breach_gate_ticks);
        breach_solo = params.getBoolean("breach_solo", breach_solo);
        breach_solo_foes = params.getInt("breach_solo_foes", breach_solo_foes);
        raze = params.getInt("raze", raze);
        raze_min = params.getInt("raze_min", raze_min);
        storm = params.getInt("storm", storm);
        bait = params.getBoolean("bait", bait);
        bait_jobs = params.getInt("bait_jobs", bait_jobs);
        bait_crew = params.getInt("bait_crew", bait_crew);
        bait_start = params.getInt("bait_start", bait_start);
        bait_hold_ticks = (float) params.getDouble("bait_hold_ticks", bait_hold_ticks);
        storm_locked = params.getBoolean("storm_locked", storm_locked);
        storm_close = (float) params.getDouble("storm_close", storm_close);
        storm_per = params.getInt("storm_per", storm_per);
        storm_lead = params.getBoolean("storm_lead", storm_lead);
        storm_join = params.getInt("storm_join", storm_join);
        storm_min = params.getInt("storm_min", storm_min);
        storm_sappers = params.getBoolean("storm_sappers", storm_sappers);
        raze_towers = params.getBoolean("raze_towers", raze_towers);
        raze_per = params.getInt("raze_per", raze_per);
        split = (float) params.getDouble("split", split);
        split_share = (float) params.getDouble("split_share", split_share);
        split_from_ticks = (float) params.getDouble("split_from_ticks", split_from_ticks);
        split_reach = params.getInt("split_reach", split_reach);
        split_margin = (float) params.getDouble("split_margin", split_margin);
        split_merge = (float) params.getDouble("split_merge", split_merge);
        split_retreat = (float) params.getDouble("split_retreat", split_retreat);
        split_cooldown_ticks = (float) params.getDouble("split_cooldown_ticks", split_cooldown_ticks);
        split_busy = (float) params.getDouble("split_busy", split_busy);
        split_far = (float) params.getDouble("split_far", split_far);
        split_far_margin = (float) params.getDouble("split_far_margin", split_far_margin);
        split_reinforce = params.getBoolean("split_reinforce", split_reinforce);
        split_worn = (float) params.getDouble("split_worn", split_worn);
        split_share_max = (float) params.getDouble("split_share_max", split_share_max);
        split_promote = (float) params.getDouble("split_promote", split_promote);
        feed_flee_in = (float) params.getDouble("feed_flee_in", feed_flee_in);
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
        evade_cells = params.getInt("evade_cells", evade_cells);
        evade_tower = params.getInt("evade_tower", evade_tower);
        bystander = params.getInt("bystander", bystander);
        bystander_reach = params.getInt("bystander_reach", bystander_reach);
        stuck_still_ticks = (float) params.getDouble("stuck_still_ticks", stuck_still_ticks);
        flight_keep = params.getBoolean("flight_keep", flight_keep);
        evade_chicken = params.getBoolean("evade_chicken", evade_chicken);
        chief_bystander = params.getBoolean("chief_bystander", chief_bystander);
        shred_hunters = params.getBoolean("shred_hunters", shred_hunters);
        iron_real = (float) params.getDouble("iron_real", iron_real);
        tree_real = (float) params.getDouble("tree_real", tree_real);
        rock_real = (float) params.getDouble("rock_real", rock_real);
        rock_real_share = (float) params.getDouble("rock_real_share", rock_real_share);
        rock_real_iron_first = params.getInt("rock_real_iron_first", rock_real_iron_first);
        rock_real_hold = params.getInt("rock_real_hold", rock_real_hold);
        rock_real_peons = params.getInt("rock_real_peons", rock_real_peons);
        rock_real_ticks = (float) params.getDouble("rock_real_ticks", rock_real_ticks);
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
        gather_parked = params.getBoolean("gather_parked", gather_parked);
        gather_parked_tries = params.getInt("gather_parked_tries", gather_parked_tries);
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
        hold_levels = params.getInt("hold_levels", hold_levels);
        threat_sappers = params.getBoolean("threat_sappers", threat_sappers);
        hold_stick = params.getInt("hold_stick", hold_stick);
        hold_commit = params.getInt("hold_commit", hold_commit);
        hold_far = params.getInt("hold_far", hold_far);
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
        reinforce_route = params.getBoolean("reinforce_route", reinforce_route);
        reinforce_gather = params.getInt("reinforce_gather", reinforce_gather);
        reinforce_march = params.getBoolean("reinforce_march", reinforce_march);
        retreat_regroup = (float) params.getDouble("retreat_regroup", retreat_regroup);
        retreat_regroup_need = params.getBoolean("retreat_regroup_need", retreat_regroup_need);
        retreat_regroup_reach = params.getInt("retreat_regroup_reach", retreat_regroup_reach);
        retreat_regroup_ticks = (float) params.getDouble("retreat_regroup_ticks", retreat_regroup_ticks);
        retreat_regroup_cooldown_ticks = (float) params.getDouble("retreat_regroup_cooldown_ticks",
                retreat_regroup_cooldown_ticks);
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
        builders_floor = params.getInt("builders_floor", builders_floor);
        builders_far = params.getInt("builders_far", builders_far);
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
        satellite = params.getBoolean("satellite", satellite);
        sat_staff = params.getBoolean("sat_staff", sat_staff);
        stock_balance = params.getBoolean("stock_balance", stock_balance);
        ore_iron_first = params.getBoolean("ore_iron_first", ore_iron_first);
        ore_reach_fail = params.getBoolean("ore_reach_fail", ore_reach_fail);
        weapon_reserve = params.getInt("weapon_reserve", weapon_reserve);
        bank = params.getInt("bank", bank);
        bank_ticks = (float) params.getDouble("bank_ticks", bank_ticks);
        bank_army = (float) params.getDouble("bank_army", bank_army);
        bank_always = params.getBoolean("bank_always", bank_always);
        weapon_reserve_ticks = (float) params.getDouble("weapon_reserve_ticks", weapon_reserve_ticks);
        sortie_ratio = (float) params.getDouble("sortie_ratio", sortie_ratio);
        rearm_placer = params.getBoolean("rearm_placer", rearm_placer);
        rearm_reach = params.getInt("rearm_reach", rearm_reach);
        reloc = params.getBoolean("reloc", reloc);
        reloc_sat = params.getBoolean("reloc_sat", reloc_sat);
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
        hold_chieftain_again = params.getInt("hold_chieftain_again", hold_chieftain_again);
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
        late_min_strength = (float) params.getDouble("late_min_strength", late_min_strength);
        late_min_ticks = (float) params.getDouble("late_min_ticks", late_min_ticks);
        late_reinforce_min = (float) params.getDouble("late_reinforce_min", late_reinforce_min);
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
        harass_memory_ticks = (float) params.getDouble("harass_memory_ticks", harass_memory_ticks);
        harass_memory_cells = params.getInt("harass_memory_cells", harass_memory_cells);
        gather_under_threat = params.getBoolean("gather_under_threat", gather_under_threat);
        gather_threat_1v1 = params.getBoolean("gather_threat_1v1", gather_threat_1v1);
        stuck_trip_factor = (float) params.getDouble("stuck_trip_factor", stuck_trip_factor);
        expansion = params.getBoolean("expansion", expansion);
        far_expansion = params.getBoolean("far_expansion", far_expansion);
        recall_old_gatherers = params.getBoolean("recall_old_gatherers", recall_old_gatherers);
        desperate_expansion = (float) params.getDouble("desperate_expansion", desperate_expansion);
        desperate_iron_cycle_ticks = (float) params.getDouble("desperate_iron_cycle_ticks", desperate_iron_cycle_ticks);
        armory_far_cost_ticks = (float) params.getDouble("armory_far_cost_ticks", armory_far_cost_ticks);
        armory_iron_need = params.getInt("armory_iron_need", armory_iron_need);
        armory_iron_reach = params.getInt("armory_iron_reach", armory_iron_reach);
        armory_iron_short_ticks = (float) params.getDouble("armory_iron_short_ticks", armory_iron_short_ticks);
        armory_iron_far = params.getInt("armory_iron_far", armory_iron_far);
        armory_route_clear = params.getInt("armory_route_clear", armory_route_clear);
        armory_route_ticks = (float) params.getDouble("armory_route_ticks", armory_route_ticks);
        opening_move_m = params.getInt("opening_move_m", opening_move_m);
        opening_prewalk = params.getInt("opening_prewalk", opening_prewalk);
        opening_join = params.getBoolean("opening_join", opening_join);
        idle_builders = params.getInt("idle_builders", idle_builders);
        opening_builders = params.getInt("opening_builders", opening_builders);
        release_builders = params.getBoolean("release_builders", release_builders);
        release_fresh_ticks = (float) params.getDouble("release_fresh_ticks", release_fresh_ticks);
        builder_tree = params.getInt("builder_tree", builder_tree);
        armory_spare_ticks = (float) params.getDouble("armory_spare_ticks", armory_spare_ticks);
        opening_fallback = params.getBoolean("opening_fallback", opening_fallback);
        opening_keepout = params.getInt("opening_keepout", opening_keepout);
        opening_keepout_range = params.getInt("opening_keepout_range", opening_keepout_range);
        expansion_iron_need = params.getInt("expansion_iron_need", expansion_iron_need);
        expansion_iron_ticks = (float) params.getDouble("expansion_iron_ticks", expansion_iron_ticks);
        expansion_iron_far = params.getInt("expansion_iron_far", expansion_iron_far);
        expansion_iron_crew = params.getInt("expansion_iron_crew", expansion_iron_crew);
        peon_militia = params.getBoolean("peon_militia", peon_militia);
        militia_ticks = (float) params.getDouble("militia_ticks", militia_ticks);
        peon_rush = params.getBoolean("peon_rush", peon_rush);
        freeze_open = params.getBoolean("freeze_open", freeze_open);
        freeze_squad = params.getInt("freeze_squad", freeze_squad);
        freeze_eta_ticks = (float) params.getDouble("freeze_eta_ticks", freeze_eta_ticks);
        freeze_min_eta_ticks = (float) params.getDouble("freeze_min_eta_ticks", freeze_min_eta_ticks);
        freeze_near_ticks = (float) params.getDouble("freeze_near_ticks", freeze_near_ticks);
        freeze_near_squad = params.getInt("freeze_near_squad", freeze_near_squad);
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
        micro_step = (float) params.getDouble("micro_step", micro_step);
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
        // fable (lab/fable/NOTES.md; N=14, ludicrous, 0/10/10, dev seeds 21001..21100, against Gauntlet's defaults on
        // the same maps: 37 wins).
        // Target choice weighs a base's defense at 2 m a unit, not 8: at 8 a base 240 m farther with 30 less defense
        // won, and the army walked 500-700 m between targets (tdw2-vs14: 45 wins, +14 / -6; 4: 43; 0: 44).
        strategy.target_defense_weight = 2f;
        // The attack keeps its sappers topped up (sap-vs14: 41 wins, +14 / -10, 81 fewer units lost a game).
        strategy.sapper_refill = true;
        // Six quarters holding 4 peons each once the armory stands, not four holding 10 (large maps and up; the
        // smaller sizes keep forMapSize's rush opening). A quarters breeds (1/11) x n^(1/3) a second with n inside, so
        // 6 x 4 breed 52 a minute against 47 for 4 x 10, with 16 peons fewer held back from a weapon pool that is
        // short of them in minutes 5-9. With the two above: N=14 58 wins on the dev seeds (+29 / -8, z 3.5) and 96 of
        // 200 on fresh ones (Gauntlet 85), N=15 40 against 26, N=16 22 against 11. Five quarters do as well at N=14
        // (159 of 300 with hold 4), seven and eight worse: they take the towers' places under the 20-building cap.
        if (map_size != Game.SIZE_SMALL && map_size != Game.SIZE_MEDIUM) {
            strategy.initial_quarters = 6;
            strategy.max_quarters = 6;
            strategy.hold_mid = 4;
            // The gatherers after minute 12 (lab/fable/NOTES.md, third session): the old armory keeps working, far
            // gatherers step aside from a passing enemy, and iron is staffed by what it really costs. Together at
            // N=15: 45 wins against 40 on the dev seeds and 81 against 72 of 200 on fresh ones (sei-b-vs15; the
            // minute-20 state model +7.9 points, z 4.3); singly neutral.
            strategy.satellite = true;
            strategy.evade_cells = 30;
            strategy.iron_real = 3f;
            // The step out of an enemy's way goes towards a manned tower (lab/fable/NOTES.md, the long game): at N=15
            // 139 wins against 126 on 300 fresh maps (et1f-vs15, et1g-vs15: +28 -15) and 52 of the 100 maps still
            // open at minute 30 against 44 and 47 in the default's two draws there.
            strategy.evade_tower = 45;
            // The chieftain blasts instead of stunning (lab/fable/NOTES.md, the chieftain's blast): he keeps away
            // from the army, waits by parked enemy blobs and blows the sonic blast from their cast point, and his
            // training quarters is kept full at the unit cap. At N=15 158 wins against 139 on 300 fresh maps
            // (sh6f-vs15, sh6g-vs15: +44 -25) and +8.2 +- 2.9 and +4.2 +- 2.7 wins of 100 for the mission and the
            // trainer in the screen on the open maps (bt1).
            strategy.shred = true;
            strategy.shred_strict = true;
            strategy.shred_min_hp = 0;
            strategy.shred_fill = .8f;
            strategy.shred_lurk = 28;
            strategy.chief_topup_any = true;
            // With the mission the first chieftain has nothing to do before the first waves park, and his training
            // takes 40 births of a quarters out of the opening: from 480 s, at the unit cap, instead of 300 s we have
            // 9 more warriors at minute 10 and 0.2 more copies out at minute 15, and win 171 against 158 on the 300
            // fresh maps (ct8f-vs15, ct8g-vs15: +46 -33). 600 s scored 53 of the 100 open maps against 62.
            strategy.chieftain_ticks = 24000f; // 480 s
            // Fifteen switches that each end a measured waste and were each left off after one flat screen
            // (lab/fable/NOTES.md, the mechanics left off as neutral), together: from seat 0 at N=8-16 on two blocks
            // of 100 maps +99 -72 against the same build without them (pb6, pb6h: 1,800 games, z 2.1) and no draw
            // where there were five; the maps won from some start 199 of 200 at N=15 and 196 at N=16, where they
            // were 199 and 194; no swallowed error, the opening on the second where it was.
            // The stock: workers the armory cannot employ fetch the wood its iron waits for, a satellite gets the
            // peons its stock can use, and a new armory is looked for under threat too.
            strategy.stock_balance = true;
            strategy.sat_staff = true;
            strategy.expand_under_threat = true;
            // Building: wood is carried to a tower site with no trees, a tower with no site at its anchor looks
            // around the other buildings and is not kept waiting by a quarters, a wedged builder is taken out, the
            // builders with the shortest walk are taken, and a new quarters starts with the peons at its door.
            strategy.tower_wood_drop = true;
            strategy.tower_wood_ticks = 30000f; // 600 s
            strategy.tower_site_fallback = true;
            strategy.site_towers_first = true;
            strategy.unstick_builders_ticks = 1500f; // 30 s
            strategy.walk_select = 300;
            strategy.seed_quarters_ticks = 1500f; // 30 s
            // The rush alarm sends no peon out with no armory to go to, and not after the opening.
            strategy.rush_hold_armory = true;
            strategy.rush_opening_only = true;
            // The attack: no muster with no army, peons do not reset the stall clock, a frozen copy that arms
            // counts again.
            strategy.capped_min_strength = 40f;
            strategy.stall_peons = true;
            strategy.freeze_unfreeze = true;
            // A second shepherd by the home of every copy 120 cells and more away, for the waves that leave from
            // there while its own shepherd is off with the parked one, and none while the own one's place is the
            // home (lab/fable/NOTES.md, the second shepherd repaired). As written the two went to one place in
            // the opening and the pair won 71 for 70 at N=15; repaired, +23 -15 on 200 maps there, and at N=16
            // on 300 maps never used 154 wins for 124 (+60 -30, hpsn-vs16), 5 fewer waves at our base a game.
            strategy.shepherd_home_pair = 120;
            strategy.shepherd_home_spare = true;
            // A shepherd whose straight walk to its spot is not clear walks a planned way round enemy warriors,
            // bases and towers (lab/fable/NOTES.md, a way for the shepherds). Half the shepherds lost never had a
            // wave aimed at them. In logged games those recruited in minutes 20-40 reach a spot in 53 % for 23 %;
            // at N=16 on two blocks of 300 maps never used 172 wins for 154 and 165 for 148 (rte-vs16, rtm-vs16:
            // +100 -65), 55 to 87 fewer shepherds lost a game.
            strategy.shepherd_route = true;
            // The micro round (lab/fable/NOTES.md): a peon minds no enemy that is locked in a hunt on another unit, a
            // gatherer on a long walk is not stuck, and gatherers walk round parked groups. From minute 24 a trip
            // for iron in ten was delivered; 12 % more iron an hour, fewer peons lost, a ninth less CPU. At N=16 on
            // 300 maps never used 204 wins for 178 (mbm-vs16, +50 -24), on 150 of another block 105 for 87.
            strategy.bystander = 0;
            strategy.stuck_still_ticks = 1000f; // 20 s
            strategy.gather_parked = true;
            // A peon on its way to shelter is left on it (the economy took it back for gathering every second): on
            // the same 300 maps 215 wins for 204 (mem-vs16, +35 -24), 179 peons lost an hour of game for 213. And the
            // chieftain's mission minds no hunter of another unit either: a quarter fewer approaches called off,
            // 108 wins for 105 on 150 maps (mf2-vs16).
            strategy.flight_keep = true;
            strategy.chief_bystander = true;
            // The home army meets a threat at the building of ours nearest to it, a tower if one is close, instead
            // of each warrior walking out to the enemy nearest to it from where it stands, and it stays at that
            // building until the enemies by it are gone (lab/fable/NOTES.md, the home army traced). Half its deaths
            // came in the company of seven mates or fewer, a third of them while answering enemies by far peons. At
            // N=16 on two blocks of maps never used 114 wins of 150 for 106 and 217 of 300 for 204 (hu3-vs16,
            // hu4-vs16: +53 -32), 94 home warriors lost an hour of game for 146.
            if (enemies > 1) {
                strategy.hold_multi = true;
                strategy.hold_ratio = .3f;
                strategy.hold_stick = 12;
                strategy.hold_commit = 20;
                // A group of reinforcements fights as one body, and an army about to turn back falls back on the
                // reinforcements on their way to it instead of taking them home (lab/fable/NOTES.md, the
                // reinforcements traced). A group kills 1.4 enemy warriors on its way for one of its own where it
                // killed 0.84, and 7 to 13 % more reinforcements reach the army. Nothing in the wins: at N=16 on
                // two blocks of maps never used 210 of 300 for 213 (rs3-vs16, ru4-vs16: +30 -33), at N=17 160 of
                // 300 for 162 (ru5-vs17: +37 -39). Waste removed that shows no harm.
                strategy.reinforce_march = true;
                strategy.retreat_regroup = 8f;
            }
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
