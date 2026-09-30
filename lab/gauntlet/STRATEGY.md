# The gauntlet strategy against many Hard AIs: what matters

A map of the AI's plan against 1 vs N allied stock Hard AIs (viking Hards, large tropical maps, hills 0-2, trees 10,
supplies 10), written from the measurements in NOTES.md. The goal is the highest N beaten at least a few times on
random seeds (N=13-14 as of 2026-09). Numbers are from 200-600-game paired screens unless noted.

## How a game against N copies goes

1. **0-4 min, the opening.** Four quarters and an armory (about 3.5 min), towers from ~4.5 min. The freeze opening
   (whenever a copy starts within 40 s of peon walk) sends 6 starting peons to kill the nearest copy's quarters builders: that copy is out in about a minute
   (path a, ~55-75 % of strikes), or frozen for good when its quarters stood first and the squad kills its armory
   builders (path c: the site keeps the copy's "armory under construction" flag, so it never builds another).
2. **2-8 min, the first waves.** Each copy launches a wave when it has enough idle warriors, at our nearest building (or
   a unit that is clearly nearer). Shepherd peons (from 120 s, one per copy) stand on empty ground ahead of each wave so
   the wave targets them instead of the base; towers (with front entry, re-aim and fast re-targeting) kill most of what
   arrives. Stun cancel makes the copies' chieftain stuns harmless.
3. **6-15 min, the campaign.** The army attacks the nearest weak copy, gets reinforced, and is never called home.
   Eliminations come only from attacks: a copy is out after 60 s with no quarters/armory, no chieftain and <= 8 units.
   Wins need about 4 copies neutralized (out or homeless) by 20 min at a strength ratio >= 0.2 against the rest
   (N=11); at N=13-14 that means about 6 by 20 min at >= 0.3.
4. **10-25 min, the collapse window.** Iron is gone at 10-12 min, we sit at the 250-unit cap, parked blobs hold the
   threat level at 2, towers are razed faster than they are rebuilt, and the expansion armory (the most exposed
   building) falls with its banked peons. At N=12-13 almost every loss happens here; games still alive at 25 min with
   an armory standing survive to 40 min about half the time.
5. **25 min +, the grind.** With the base standing the army keeps eliminating copies; once 3 or fewer remain conversion
   is fast (median 9.5 min). What stretched wins to 3 hours was our own economy locking (the wood lock) and, rarely, an
   army wedged at a choke.

## What moved results (in the order they were found)

| change | why it works | size |
|---|---|---|
| tower bundle (gunner reach, re-targeting, pre-queue, front entry, re-aim) | a garrison throws from its entry cell, and fast re-targeting stops wasted throws | N=11 lsr20 z 4.9 |
| peon militia off | neighbours' gatherers were taken for raiders; 16 of 20 starting peons chased them | N=11 elim +.051 (z 4.1) |
| never recall the attack | after the first recall no copy was ever put out | N=11 elim +.014 pooled |
| hold 10 peons per quarters (was 14) | breeding is n^(1/3): the 10th-14th peon adds little; workers need ore early | N=11 elim +.021 |
| shepherds from 120 s (was 200) | the first waves are leashed from the start | N=11 W 28 vs 19 over 400 |
| re-site vetoed tower projects | a project vetoed by a nearby threat was never moved: towers stopped being replaced | survival +1-2 min at every N |
| freeze opening from N=12 | one copy fewer from minute 1 relieves the collapse window | N=12 W 4 -> 13 over 600 |
| freeze squad 6 (was 10) | four more peons at home; a frozen copy is worth nearly an out | N=12 W 8 -> 15 over 400 |
| freeze at every N (gate at 12 removed) | the N=11 loss was squad 10's cost | N=11 W 31 -> 35 over 400 |
| two tower projects, three sites after 10 min | two placed sites (shared with quarters) bounded tower completions | towers20 +0.7..+1.3, surv60 +0.3..+1.3 in 10 blocks; N=13-14 wins 8 vs 3 |
| chieftain from 300 s (was 240) | training takes a quarters' breeding; an early chieftain costs the opening peons | surv60 up in 8 of 9 N=13-14 blocks; fresh wins 9 -> 12 |
| wood_reach 150 + unjam, both from 40 min (cur8) | the long wins stall 1-3 h in a wood lock (60-cell tree ring cut out); a wedged army never unplugs | late benchmark games: wins 19 -> 26 (N=13-15, none lost), fresh 12 -> 13; long wins 30-190 min shorter |

## What did not (and why, briefly)

- **Finishing homeless copies** (finish_copies, chief_hunt): more eliminations but fewer wins; homeless copies launch
  almost no waves (0.04 vs 0.56 per copy-minute), so chasing them costs tempo against the copies that matter.
- **Holding peons back or sheltering them** (hold_backlog, danger_refuge, bank_guard): breeding matters early, and the
  late bank is a symptom of having nothing to forge.
- **More quarters / bigger crews / earlier towers / tower rings / tower_mutual**: the legacy values are near their best.
- **Rock warriors** (rock_stream): twice the rock harvested, but rock warriors die too fast to matter.
- **Hysteresis and N-keyed rules**: the flips that happen are cheap; the costly moments are one-way decisions (retreats,
  recalls). Effects keep their sign across N, so tune once and check at N=8 and N=12 (the freeze's apparent N=11
  exception was the squad size).
- **Calling the army home** (recall, ring_sweep): survival can move, outs always drop (ring_sweep elim z -7).
- **Late-acting tweaks judged by early metrics**: finishers and endgame fixes need wins, not the 15-25-min win proxy.
- **More or different freeze strikes**: a second strike at the next copy converts 80 % of the time but final
  eliminations barely move (that copy falls to the campaign around 9 min anyway, and 12 peons away cost later outs);
  leaving frozen copies for last (frozen_last) is worse: the first attack on the undefended frozen copy is a cheap out.

## The opponent, in the few rules that matter (AdvancedAI)

- It thinks every 5-7 s. A wave is exactly NUM idle warriors (10, then +5 per launch up to 40), sent at our building
  nearest its oldest idle warrior, or at one of our units if that one is under 0.707 of that distance. After razing
  its target the wave stands idle there: the parked blobs of the collapse window.
- From NUM = 20 (after two launches) it launches only while it has an active chieftain; a new one takes 40 breeding
  ticks at its quarters (~160 s with 20 peons inside). Homeless copies therefore stop launching once their chieftain
  dies (0.04 waves per copy-minute).
- It builds one quarters and one armory, plus a tower at 90 units and a second at 120. It defends only when an enemy
  unit stands within 30 m of that quarters or armory, with its armory stock, idle warriors and gatherers.
- Idle units react only through their 8-cell scan; being hit does not wake them (Unit.hit). A tower garrison reaches
  ~15.5 cells.
- Iron (N=14, 30 probed games): 143 nodes of 10 loads, 29 % of them within 60 cells of a start (~2 near ours), ~40
  within 50 cells of one central point a median 172 cells from our start. Half the stock still stands at 12 min, so
  the ~44 iron/min mined map-wide at 15-20 min is initial stock walked in from the middle, not respawns: the respawn
  (one random empty spot every 10 s once at most 35 of the 143 nodes hold iron) first fires at 16-28 min (median 21),
  and uncollected full piles then hold it at 2-4 per minute instead of 6. The homes of copies that are out hold
  almost nothing (10-17 loads). Our expansion is a raid on one fresh stock (~30 iron/min for 2 min, ~1 by 6-8 min).

## How to measure

- Screen at N=11 or N=12 on seeds 1..200 paired with the current base; confirm on 201..400 and at the target N; N=8
  as a regression check. Adopt at win-proxy (wp) z >= 1.5 over 400 with the fresh half >= 0, or elim z >= 2 with wp
  z >= 0; judge late-acting changes on wins and survival (winproxy.py --pair prints surv60, alive30/40, hold20,
  hold20a1, base25, arm25, towers20, peons20, vanished20).
- Scan every batch with lab/gauntlet/quirks.py for pathologies (wood lock, stuck army, peon trap, tower decay, jams,
  dry spells, draws).
- Run batches through lab/gauntlet/pool.sh so tails overlap.

## The tower economy of the collapse window (tower13 audit)

- Manned towers = towers standing x 0.94: manning is not the gap. Wins hold 12.6-13.2 towers, losses ~8.
- Towers standing = stock at 12 min (a third of games are at the 20-building cap then) minus ~1.3 razed plus ~0.5
  completed per minute. The tower target never binds (it adds one per enemy); completions are set by the placed-site
  slots, the site search (a lock when the anchor's ring is full: tower_site_fallback), and treeless sites that take
  twice as long (tower_wood_drop). Wins and losses differ in razings (0.23 vs 1.21 per min), not completions.

## Where the headroom is

1. **The collapse window (10-25 min) at N=12+.** The base dies to converging copies, often while the army is away or
   already gone; towers and the expansion are the levers found so far. Fewer, safer buildings with more towers and no
   peon bank in exposed armories is the direction.
2. **Silly behaviour at high N.** Every big gain so far was a misevaluation that only showed in crowded or long games
   (militia, recall, vetoed towers, wood lock, empty-army musters at the cap, jams). Look at logged N=12-14 games.
3. **Iron.** The binding resource from ~9 min; after 13 min it is in the middle of the map, which our one-shot
   expansion reaches only once (relocation audit: the two-armory gate and the global threat gate stop every later
   check). Our share after 14 min is ~12 %.
4. **Fewer enemies from the start** (the freeze opening shows removing one copy early is worth an N step): cheaper or
   additional early strikes may pay more as N grows.
