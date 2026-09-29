# gauntlet: notes

AI `gauntlet` (package `tt/src/main/java/com/oddlabs/tt/player/gauntlet/`, branch `gauntlet` off `headless`). Goal:
beat the largest number N of allied stock Hard AIs (`--players "gauntlet vs hard*N"`) with at least 10% wins on the
final exam (large tropical, hills 0..2, trees 10, supplies 10, slot 0, seeds 30001..30100). A win is eliminating
every opponent; a timeout is a draw.

This file is the working record: the comparison of the five earlier AIs, the current best, the plan, every
hypothesis with its runs, and the dead ends. The next session resumes from here. The final summary is REPORT.md
(N* = 8; exams: N=7 @g-shep 45/100, N=8 @g-v8 11/100, N=8 @g-final 25/100).

## The five earlier AIs (study of 2026-09-28)

Sources: each AI's code and notes, the old tournament (tribaltrouble-bench, `python tab_bench.py results/final`;
60-minute limit, 1.5x material wins a timeout, 4 maps per gauntlet step), the exploit audit
(tribaltrouble-bench/exploit-audit), our dev runs (seeds 1..100, benchmark maps, slot 0). The full reader reports of
the study workflow are summarised here; numbers are theirs unless a run name is given.

| AI | best at | worst at / breaks down | N curve |
|---|---|---|---|
| Expert (Opus) | Trading (1.83 kills per loss in N=5 wins, 1.19 in losses), micro (per-warrior target by value x hit chance x survival), 4-quarters tempo (Q4 at 3:53 every game), team play. Towers facing each enemy (multi_front_towers), stun patience, stun dodging, sappers. | The boom meets N simultaneous first waves: armory 4:08 vs Hard's 1:51, first tower 5:17; in N=5 losses the first quarters falls at a median 7:38 with 15-24 warriors out. Army capped at 45 strength held in stock until threat 2. Defense answers one cluster. 634 s between eliminations; reinforcements off vs several enemies; no per-player focus. | bench 88/92/94/65/21/11/0/0 (N=1..8); our exam maps 92/70/33/4 (N=3..6); dev base-expert-vs5 33, vs6 7 |
| Ultra (Opus) | Winning even fights (micro +45 points self-play, stun dodge +9), sieges with creep towers (5 of 31 lost), exploiting a local edge after waves break. | Merged enemy model (one WorldModel.enemy, one nearest start): 11 of 12 1v4 losses never eliminated a single Hard. Peons shelter in the armory and die with it (150-247 units, 12 of 12 1v4 eliminations). Deploy gate freezes under sustained threat. Greedy boom: 2 warriors at 5:00. | bench 100/100/100/45/4/0/0/0; own sweep N=5 4/20, N=6+ 0 |
| Fable (Fable) | Fast cheap kills of up to 3 Hards (71 warriors vs 26 at 10 min at N=3), closing out a base in under a minute, stun economy, re-orders stunned units every 0.15 s (K2 always, K1 by coincidence on ~12.5 %). | Attack rule sums every enemy (needs ~1.65 x 0.8 x all Hards), slow opening (armory 236 s), tower schedule by time only, one threat group, E_home measured at the wrong base (bug). | bench 100/83/88/35/0/0/0/0 |
| sweep (Opus) | Hard's targeting: a fort of 8 towers 45 cells toward the map centre plus 1-HP decoy sites 10.5+ cells in front; waves idle in tower reach (fort exchange ~2.9:1 at 7-10 min, 1650 of 2145 enemy chieftain deaths at the fort). Armory crew by man-seconds (N=3 53-59 -> 68/200, N=4 3 -> 11/200). 90 % iron (ironfrac 0.3/0.7/0.9/1.0: 24/40/56/45 of 200 at N=3). Unstun re-orders (+9 at N=2). | Weak basics (armory 201 s, Q4 7:48, 66 warriors at 15 min, 1.2-1.3 kills per loss). At N=4: production does not scale (54 vs 173 warriors at 15 min), the four chieftain waves land within ~50 s, 30 % of games lose a tower before 9 min (and win 0), ore income collapses while enemies stand at the fort (65 % of ore on the fort side). | exams N=1..3 89/78/39; N=4 dev 10.5 %; dev base-sweep75-vs4 12 |
| outnumbered (Fable) | The source-cited model (lab/outnumbered/model on its branch): numbers below. Decoys at a hex tower block killed waves 1-2 for 0-3 losses (smoke-v21-vs2). Towers from the armory's tree stock in ~20 s. | Never attacks: 0 wins by elimination anywhere. Opening no stronger than one Hard copy (units 68/94/154 vs 68/92/143 at 5/10/15 min). One decoy spot cannot serve every copy past N=5. | 0 wins under today's rules |

Where each one breaks down as N grows, in one line: every AI hits a cliff at N=4-5; at N>=7 all die in 12-17
minutes, so high N is decided by surviving the first 15-20 minutes (the N simultaneous first waves at 5-7 min and
the chieftain waves at 9-11 min), and N=5-6 additionally by finishing copies fast enough while the rest keep
producing (a copy's production curve does not depend on N: ~25 warriors by 7 min, ~63 by 13, ~112 by 20).

### Facts used in decisions (source-verified by the readers; see their files for line numbers)

- Hard (AdvancedAI, difficulty 2) decides every U[5,7) s. Quarters built ~66 s, armory ~133 s; first 10 warriors by
  5:00; waves of 10 (~5:00), 15 (~6:50), then 20+chieftain (~11 min), +5 per wave to 40. NUM_WARRIORS grows only
  when a target is found, per copy. At NUM>=20 no wave leaves without a chieftain; training takes 41 steps
  (166 s with 20 inside) during which the quarters breeds nothing.
- A wave targets our building nearest its oldest idle warrior (earliest in the Army), placed 1-HP sites included; a
  unit of ours wins only if nearer than 0.707 of that. It attack-moves (8-cell Chebyshev scan, priority warrior 4 >
  peon 3 > tower 2 > building/site 1) to a snapshot cell, razes what is there and goes idle. Idle units never react
  to being hit. Survivors lead the copy's next wave.
- Hard defends only against units within 30 m of its first quarters (then armory): it deploys its whole armory and
  sends idle warriors (anywhere, oldest first), idle peons, then gatherers with DEFEND up to 2x our score.
- A razed quarters during chieftain training cancels it and kills the 20-40 peons inside; with idle warriors >= NUM
  the copy then never rebuilds (wanted <= 0 skips nodeTransferUnits/nodeBuildQuarters): a frozen copy.
- Tower: 100 HP, 20 trees, one garrison, reach 15.9 cells vs units (from the cell it entered), hit x3: an iron
  garrison always hits rock warriors, peons and chieftains, 0.675 vs iron. Building cap 20 (sites count), unit cap
  250 (chieftain excluded).
- Weapons: rock 2 wood + 1 rock, 40 man-s; iron 2 wood + 1 iron, 80 man-s; rubber 2 wood + rock + iron + rubber,
  120 man-s. Breeding per quarters 5.45 x N^(1/3) per minute (N inside); total over Q quarters grows as
  Q^(2/3) N^(1/3) (audit A32), so more, emptier quarters breed faster.
- Races differ only in chieftain HP (60 viking, 40 native), spells, and release timing. Native Hard chieftain:
  lightning at >= 2 of our units within 30 m (homes on units and buildings), poison fog at >= 5.
- Exploits reachable through our own orders (audit, re-checked on this engine by the audit reader): K1 an order on
  the tick a stun lands cancels it completely (this engine has no stun break window); K2 any later order restores
  dodge while frozen; A26/K3 re-ordering a harvesting peon right after its hit restarts the swing (viking peon: a hit
  every 15 ticks instead of 51; +64-70 % delivered loads at short hauls; construction 1.2-1.6x); A22 explicit tower
  targets after each kill (+51 % tower kills vs weak units); A15 rubber throws do 6.2 damage to quarters/armory vs
  1.68 for iron; A18 peons do 6 damage per swing to towers.

## Current best

| tag | what | N=5 (dev 1..100) | N=6 (dev 1..100) |
|---|---|---|---|
| `g-port` (commit 2630f043) | the Expert AI ported, vs viking Hards | 32 | 4 |
| gauntlet (swing_restart on) | + harvest swing restart, vs native Hards | 76 | 52 |
| `g-shep` (commit 540e97e6) | + stun cancel, shepherds, multi-enemy attack gate, reinforce | | N=7 34/100 vs viking, N=8 15/100 vs viking (20/200 vs native) |
| `g-v8` (commit c002c1ae) | + safer shepherd recruitment, dodge only blasts | | N=8 31/200 vs viking on fresh seeds 201..400 |
| `g-agg` (commit 0bf70b47) | + all-in campaign against several enemies (attack at even strength, no recall) | | N=8 43/200 fresh (19/100 on 1..100); N=9 5/100 |
| `g-final` (commit 2f266fba) | + tower stun cancel (tower_unstun) | | N=8 52/200 fresh (23/100 on 1..100); N=9 4/100 |

**Exams**: N=7 `@g-shep` vs hard*7 (vikings): 45/100 [35.6, 54.8], passed. N=8 `@g-v8` vs hard*8 (vikings): 11/100 [6.3, 18.6], passed. **N* = 8.**

References on the same dev seeds (1..100, slot 0, vikings everywhere): `@expert` 33 at N=5 (base-expert-vs5),
7 at N=6 (base-expert-vs6); `@sweep-v75` 12 at N=4 (base-sweep75-vs4).

## Plan (2026-09-28, after the study, the swing restart and K1)

Ladder now (dev seeds 1..100, slot 0): vs native Hards N=4 78 (port), N=5 76, N=6 52, N=7 19, N=8 2; vs viking
Hards with K1 N=6 49, N=7 17. So N*=7 is within reach on dev, N=8 is the next frontier. The three strategists of
the study (full texts in the session scratchpad: ideate-*.md) ranked, and I added, in order of expected value at
N>=7:

1. **Freeze strike** (contrarian #1, sweep lab/sweep/model/freeze.md): at N>=6 the two ring neighbours start
   ~130-150 cells away (peon ETA ~55-60 s). Kill every armory builder of a copy after its armory site is placed
   (~73 s, built ~131 s): the copy never builds again (armory flag never cleared, nobody leaves the quarters), and
   its quarters can be razed later with no response at all -> out. Worth ~one N step per frozen copy.
2. **Per-copy elimination focus** (all three): commit to one copy until it is out (armory, quarters, peons,
   sites); reinforce against several enemies; never touch a frozen copy's armory site.
3. **Steering waves** (exploit-first #1/#4): the 0.707 unit rule lets one shepherd peon draw each launch to an
   empty cell; decoys (Decoys.java) need a tower layout with room in front. Feed blobs one at a time into tower
   reach.
4. **Basics at high N** (basics-first): army by 6:00, parallel front towers, earlier expansion armory, peons out of
   threatened quarters, rock as a second stream. Param screen first (below).
5. **Our chieftain on parked blobs** (exploit-first #2): blast or stun+charge idle blobs that never react.
6. Re-check the Hard race per N before each exam (natives ~ vikings+K1 at N=6-7 so far).

## Tools

- `lab/gauntlet/dev.sh NAME "PLAYERS" SEEDS [args]`: a batch on the benchmark's maps; refuses exam seeds.
- `lab/gauntlet/score.py`: continuous scores per game (`elim` share of Hards out, `prog`, `lsrM` log strength ratio
  at minute M, `kd`), run summaries, `--pair BASE VARIANT` paired tests, `--predict` checks which score predicts
  wins. On base-expert-vs5 + vs6 (200 games, 40 wins): elim r=+0.96 (trivial: wins are 1), lsr20 r=+0.57, lsr15
  r=+0.46, lsr10 r=+0.39, kd r=+0.49; by lsr15 tercile the win rate is 0% / 0% / 60%. So lsr15/lsr20 and elim are
  the continuous scores to decide on beside wins.

## Log

### 2026-09-28: port of Expert

Why Expert: on the benchmark it is far ahead of the others (92/70/33/4 % at N=3/4/5/6; sweep 39 % at N=3, ~12 %
at N=4 on dev). Its basics (economy, army, fights) carried it; sweep's tricks carried sweep with weaker basics. The
plan is Expert's basics plus Hard-specific tricks.

Port (commit 2630f043): the package of the expert-ai branch (its 24 classes are byte-identical to the frozen
`@expert` jar), renamed, with `placeBuilding` for `BuildingTemplate.create` (the engine's placeBuilding is exactly
create + the DEFAULT order Expert gave), AiLog, AiParams, no statics, landscape orders clamped into the map.
Check on dev seeds 1..100: N=5 32 vs 33 (13 gained, 14 lost), N=6 4 vs 7 (1 gained, 4 lost); plays like the
original.

First look at the port's losses (port-vs5, port-vs6):
- Hard copies have their armory at ~1:50-3:20 and 11-14 warriors each at 5:00; our armory stands at ~3:52 and we
  have 2 warriors at 5:00 (the four-quarters boom).
- Our iron harvest peaks at 14-19/min (7-12 min) and falls to ~9/min after 15 min, while we hold 120-135 workers
  (50-65 inside buildings). Warriors at 15 min: 61 (N=5), against 177 of the five Hards together.
- Hard chieftains arrive at 8-10 min, one per copy, and stun our army over and over (s20-0 at N=6: 307 of our units
  stunned by 20:00).
- The worst fights are "between" (on the way to or from a Hard base) and lose many peons.

### 2026-09-28: the Hard copies' race

Hypothesis: natives Hards are easier, since their chieftain has no stun (the stun froze 307 of our units by 20:00 in
port-vs6 s20-0) and no blast, and 40 HP instead of 60. Their spells are poison fog (26 m) and lightning (hits units
and buildings, cast when 2+ of our units are within 30 m).

| run | lineup | W / 100 | elim | lsr15 |
|---|---|---|---|---|
| port-vs5 | gauntlet vs hard*5 | 32 | 0.370 | -1.54 |
| port-vs5-hn | gauntlet vs hard/n*5 | 43 (1 draw) | 0.473 | -1.32 |
| port-vs6 | gauntlet vs hard*6 | 4 | 0.075 | -2.99 |
| port-vs6-hn | gauntlet vs hard/n*6 | 10 | 0.148 | -2.75 |

Decision: native Hards from here on (our AI stays viking: its chieftain logic is built around the stun). Re-check
per N before each exam. Note: the stun tricks (restore_dodge, dodging the stun) are moot against native Hards; an
A/B of restore_dodge vs native Hards was started by mistake and stopped (unstun1-vs5-hn, unstun1-vs6-hn: ignore).

### 2026-09-28: decoys v1 (killbox decoys in front of Expert's towers)

Decoys.java: per living copy, a 1-HP tower site 11-14 cells from one of our manned towers, nearer to the copy's
wave origin (oldest idle warrior, else armory) than our nearest real building by a 0.85 margin, 10+ cells clear
of our buildings; Military leaves "caged" enemies (not fighting, in tower reach, 10+ cells from our buildings) to
the towers.

| run | W / 100 | lsr15 | decoys placed per game |
|---|---|---|---|
| decoys1-vs5-hn vs port-vs5-hn | 40 vs 43 (7 gained, 10 lost) | -1.41 vs -1.31 | 1-11 |
| decoys1-vs6-hn vs port-vs6-hn | 10 vs 10 (6/6) | -2.51 vs -2.75 (z +2.7) | |

Verdict: inert, not wrong: the spot search fails ~1,700 times a game (decoy_nospot) because Expert's base is dense:
no cell 11-14 cells beyond a tower is also 10 cells clear of every building and nearer to the copy. The mechanism
needs towers laid out for it (a fort, or front towers pushed outward). Kept behind decoys=false for later.

### 2026-09-28: harvest swing restart (audit A26 / K3)

Reflexes.java, run every world tick: a peon whose HarvestBehaviour has run as many ticks as its hit takes (15 for
viking peons: the hit comes on the first animate past 11/38 of the 1 s cycle) is ordered to the same supply
(GatherController.getSupply) or, when chopping for construction, to the same building; the new swing starts at
once. Ticks are counted from the tick we first see the swing, which is the tick it was made (AI runs after the
units in World.tick), so the order is never early.

10-minute probe (swing-probe-off/on, seeds 1..10, vs hard/n*5), team A at 9 min: wood 541 -> 680, iron 64 -> 106,
warriors 34 -> 73, towers 4.1 -> 7.7, units 190 -> 229; quarters at 3 min 2.3 -> 3.4.

| run | W / 100 | elim | lsr15 | paired |
|---|---|---|---|---|
| swing-vs5-hn vs port-vs5-hn | 76 vs 43 | 0.80 vs 0.48 | -0.23 vs -1.31 | 34 gained, 1 lost; win z +6.7 |
| swing-vs6-hn vs port-vs6-hn | 52 vs 10 | 0.59 vs 0.15 | -0.96 vs -2.75 | 44 gained, 2 lost; win z +7.8 |

Decision: on by default. Cost: ~16,000 + 8,500 orders per game (counters swing_restart, swing_restart_build).

### 2026-09-28: stun cancel (K1) works on this engine

Reflexes: every tick, each own unit whose current controller is a StunController is ordered back to its previous
job (from the controllers under the stun; never a chieftain with a MagicController queued, audit A10). One game
vs viking Hards (play-k1-s20): 47 stuns cancelled, and no `stunned` event for our slot at all, while the copies cast
stuns throughout. Next: does it make viking Hards easier than native ones?

### 2026-09-28: param screen at N=7 (vs native Hards, seeds 1..100, paired with swing-vs7-hn, 19 wins)

| variant | W | elim | lsr15 | gained/lost | verdict |
|---|---|---|---|---|---|
| harvest_seconds=3.5 (gather model with the swing restart's chop time) | 15 | 0.267 | -1.48 | 9/13 | no (the stock-based crew corrections already cope) |
| quarters_before_armory=2 | 16 | 0.279 | -1.37 | 8/11 | no |
| quarters_before_armory=3 | 17 | 0.291 | -1.29 | 9/11 | no |
| chieftain_min_quarters=2 | 19 | 0.300 | -1.40 | 1/1 | inert (3 quarters already stand by 240 s) |
| reinforce_multi=true | **27** | 0.361 | -1.35 | **10/2** (win z +2.4) | **yes**: reinforce the attack against several enemies too |
| hold_mid=6 | 20 | 0.290 | -1.40 | 10/9 | no |

Ladder of the same build: vs viking Hards with K1, N=7 17/100 (k1-vs7-hv); vs native Hards N=8 2/100 (k1-vs8-hn).

What the N=7 losses look like (swing-vs7-hn s12-0): early fights are won (+2..+28 each, 5-11 min), then the
chieftain waves of several copies converge at 11-17 min; the native chieftains cast LightningCloud 11 times in
five minutes and the one long fight costs 180 of ours (90 peons, the chieftain) for 122 of theirs (4 chieftains),
with quarters, armory and towers razed. Lightning (30 damage per strike, one strike per second for 22 s, homing on
units and buildings, P = 1 - defense) is the killer against native copies.

### 2026-09-28: freeze strike, first game

play-freeze-s2 (N=7, freeze=true, 12-minute cap): squad of 10 sent at 0 s to s4 (eta 59 s), arrived 45 s, armory
site placed 69 s, all outside peons dead and s4 frozen at 115 s; the squad razed the frozen quarters by 489 s and
s4 went out at 8:09 (counters freeze_frozen 1, freeze_out 1).

### 2026-09-28: freeze strike A/B (dead end at N=7-8)

Freeze.java (commit of this entry, then deleted): at the start, freeze_squad=10 of the 20 peons walk to the
nearest copy within 90 s, wait outside its 30 m circle for the armory site, kill every outside peon, then raze
the frozen quarters.

| run | W | elim | lsr10 | lsr15 | notes |
|---|---|---|---|---|---|
| freeze1-vs7-hn vs swing-vs7-hn | 9 vs 19 (5 gained, 15 lost) | 0.150 vs 0.300 | -1.67 vs -0.93 | -2.95 vs -1.40 | 99 sent, 35 frozen, 23 razed out, 69 squads worn below 3 peons |
| freeze2-vs7-hn (freeze_targets=2) | 9 | 0.150 | -1.67 | -2.95 | identical: freeze_keep=8 leaves no peons for a second squad |
| freeze1-vs8-hn vs k1-vs8-hn | 4 vs 2 | 0.094 vs 0.100 | -2.26 vs -1.27 | -3.72 vs -2.15 | |

Why it fails: taking half the starting peons costs a full ~0.75 of log strength at 10 minutes (the opening
compounds: Q1 builders and the first breeders), and only a third of the strikes freeze their copy (squads are worn
down by idle builders and by the copy's defense drafting idle peons, as sweep's proto-strike logs showed). One
frozen copy is worth about one N step, but not at this price. It could pay at N>=10, where neighbours start ~90
cells away (ETA ~35 s), but N=10 is out of reach. Not retried with smaller squads: at 6 peons the kill rate
(0.6/s) cannot finish 20 builders inside the armory's ~58 s.

### 2026-09-28: decoys v2 (margin 0.97) and parallel towers

The 0.85 margin made decoys impossible for far copies: a decoy 11-14 cells in front of a tower is only ~10 % nearer
to a copy 130 cells away. With decoy_margin=0.97, 25.8 decoys per game are placed (15.3 razed by waves), still
~2,100 failed searches.

| run (paired with swing-*) | W | elim | lsr15 | gained/lost |
|---|---|---|---|---|
| decoys97-vs7-hn | 12 vs 19 | 0.244 vs 0.300 | -1.43 vs -1.40 | 5/12 |
| decoys97-vs6-hn | 47 vs 52 | 0.542 vs 0.588 | -0.87 vs -0.96 (z +1.9) | 7/12 |
| towpar2-vs7-hn (tower_parallel=2, sites_parallel=3) | 22 vs 19 | 0.317 vs 0.300 | -1.40 vs -1.40 | 11/8 |

Verdict: decoys in front of Expert's towers do not pay (mid-game strength a little up at N=6, wins down); shelved
(decoys=false). towpar2: +3, not significant; towpar3 pending.

### 2026-09-28: the expansion armory stalls the economy (Expert bug)

A logged N=7 game (play-decoy97-s12) showed, after the expansion armory completed 40 cells from the first one at
11:09, "recalling 69 gatherers of the old armory", then 60 -> 83 -> 108 -> 129 peons in TRANSIT over two minutes
with 3-6 armory workers: the economy stopped just as the chieftain waves arrived. drainSecondary recalls every
gatherer of the old armory into it every 10 s (recall_old_gatherers), and the allocation re-sends transit peons to
the new one. transit-check-vs7 (seeds 1..20, logs, 20 min): peak transit 40-103 in 9 of 20 games, after recalls of
33-49 gatherers at once. A/B of recall_old_gatherers=false and expansion=false queued.

### 2026-09-28: more N=7 screens (vs native Hards, paired with swing-vs7-hn, 19 wins)

| variant | W | elim | lsr15 | lsr20 | gained/lost | verdict |
|---|---|---|---|---|---|---|
| recall_old_gatherers=false | 18 | 0.296 | -1.38 | -2.16 | 5/6 | neutral: the transit pile-ups are mostly peons sheltering during the 11-13 min attacks, not the recall |
| expansion=false | 18 | 0.316 | -1.35 | -1.90 (z +2.8) | 8/9 | wins unchanged, mid-game strength up; keep in mind for a combined test |
| tower_parallel=3, sites_parallel=4 | 25 | 0.313 | -1.49 | -2.15 | 15/9 | +6, not significant (towpar2: +3) |
| focus_bonus=100, focus_finish=true | 15 | 0.266 | -1.40 | -2.24 | 2/6 | no |
| chief_per_hit=false (always go for chieftains) | 15 | 0.263 | -1.40 | -2.17 | 5/9 | no |

### 2026-09-28: shepherds (first game)

Shepherd.java: per copy, one peon stands 12-20 cells from the copy's oldest idle warrior (or its armory), nearer
than 0.66 of our nearest building and nearer than any other unit of ours, 10+ cells clear of every enemy unit,
17+ cells from the copy's quarters and armory, 19+ from enemy towers, on the side away from our start; it flees
when enemies come within 10 cells or a wave walks at its spot. play-shep-s12 (N=8, 15-minute cap): no building of
ours lost until 12:07 (the same seed at N=7 without shepherds was under attack from 5:30 and fell at ~15 min);
12 towers by 11:30; the game reached the cap. Counters: 10 launches drawn to the spot, 24 shepherds recruited,
16 lost.

### 2026-09-28: shepherd A/B, and two dead-unit bugs

| run (paired) | W | elim | lsr15 | lsr20 | kd | gained/lost | shepherds lost/game |
|---|---|---|---|---|---|---|---|
| shep-vs8-hn (v1) vs k1-vs8-hn | 6 vs 2 | 0.180 vs 0.100 (z +3.2) | -1.30 vs -2.15 (z +5.9) | -1.98 vs -3.14 (z +6.4) | +0.13 | 5/1 | 45.5 (14.4 launches drawn) |
| shep-vs7-hn (v2, with the guard bug) vs swing-vs7-hn | 22 vs 19 | 0.349 vs 0.300 | -1.00 vs -1.40 (z +3.9) | -1.52 vs -2.22 (z +4.2) | +0.24 (z +6.1) | 14/11 | 31.7 (15.5 drawn) |

Bugs found in the logs of play-shep5-s12: getPrimaryController asserts on units that died since the last Intel
snapshot. (1) The shepherd's guard pass runs every 5 ticks on Intel's enemy lists: 1,628 swallowed errors per game
in shep-vs7-hn, each aborting that tick's animate (so think was skipped when due). (2) The STAT transit breakdown
did the same on our peons, only when logging: logging changed decisions. Both now check isDead first; shep-vs6-hn
(buggy build) stopped; fixed build re-run as shep3-* and shep2-vs8-hn.

Fixed shepherd (dead-unit checks, guard every 5 ticks, spots 14-22 cells, 12 cells clear):

| run (paired) | W | elim | lsr15 | lsr20 | kd | gained/lost | shepherds lost/game |
|---|---|---|---|---|---|---|---|
| shep2-vs8-hn vs k1-vs8-hn | 6 vs 2 | 0.184 vs 0.100 (z +3.9) | -1.28 vs -2.15 (z +5.9) | -1.94 vs -3.14 (z +6.7) | +0.15 (z +5.2) | 4/0 | |
| shep3-vs7-hn vs swing-vs7-hn | 23 vs 19 | 0.347 vs 0.300 | -0.95 vs -1.40 (z +4.4) | -1.34 vs -2.22 (z +5.4) | +0.19 (z +4.6) | 12/8 | 34.0 (16.7 drawn) |

The strength gain is large and robust; wins lag. The 40-minute game play-shep4-s12 shows why: with 65-83
warriors at home from 10 to 14 minutes, the army never mustered once. Expert's attack test counts every enemy
warrior within 150 cells of the target in full (all the leashed blobs of neighbouring copies) and adds every
copy's growth times the march time (project_defense); at N=8 that never clears 1.35x. Then shepherds fail one by
one (no legal spot when a blob idles within ~21 cells of our buildings, or killed walking out) and the base falls
at 12-23 min. Tests queued: defense_radius=60 + project_defense=false (shepatk-*), combinations with
reinforce_multi and parallel towers (combo1/2-*). New counters: shepherd_nospot_building / _unit / _ground.

### 2026-09-28: attack gate for leashed copies (big win)

defense_radius=60 (enemy warriors beyond 60 cells of the target count 0.3 instead of full, was 150) and
project_defense=false (no longer add every copy's growth times the march time):

| run (paired) | W | elim | lsr15 | kd | gained/lost |
|---|---|---|---|---|---|
| shepatk-vs8-hn vs shep2-vs8-hn | **12 vs 6** | 0.259 vs 0.184 (z +2.7) | -1.25 vs -1.28 | +0.04 | 10/4 |
| shepatk-vs7-hn vs shep3-vs7-hn | **35 vs 23** (z +2.4) | 0.491 vs 0.347 (z +3.7) | -0.91 vs -0.95 | +0.05 | 19/7 |
| shep3-vs6-hn (shepherd only) vs swing-vs6-hn | 57 vs 52 | 0.650 vs 0.588 | -0.72 vs -0.96 (z +2.7) | +0.09 | 18/13 |

Shepherd no-spot counters (shepatk-vs8-hn, per game, counted per 0.5 s per copy): no legal ground 2,323, another
unit of ours nearer 1,092, our building within ~21 cells 979. Replay of shep2-vs8-hn s3-0: VERIFIED.

### 2026-09-28: the army never attacked at N=8 (threat level 2 all the time)

play-atk-s12 (N=8, shepherd + defense_radius=60 + project_defense=false, 40 min): not a single muster; the army
grew to 92 at 12:30 while threat_level stayed 2 from 9 minutes on (small groups at the base: 3-20 strength).
Military.plan only considers attacking at threat level < 2. New param attack_threat_ratio: also attack when the
enemies in the base are worth less than that share of the army. With 0.3 (play-atk2-s12): muster at 9:21, attack
at 10:06, copy s7 out at 14:00, army recalled at 13:02 (47 in the base vs 43 at home); still lost at 17:12.
A/B queued (atkthr-*). combo1-vs8-hn (shepherd + reinforce_multi) vs shep2-vs8-hn: 7 vs 6, identical otherwise:
reinforcing is moot while the army never attacks.

### 2026-09-28: more shepherd-era screens; new defaults

| run (paired) | W | elim | lsr15 | lsr20 | gained/lost | verdict |
|---|---|---|---|---|---|---|
| combo1-vs7-hn (shepherd + reinforce_multi) vs shep3-vs7-hn | 24 vs 23 | 0.370 vs 0.347 | -0.92 vs -0.95 | -1.29 vs -1.34 | 5/4 | neutral |
| combo2-vs7-hn (+ tower_parallel=3, sites_parallel=4) vs combo1 | 20 vs 24 | 0.312 vs 0.367 | -1.04 vs -0.92 (z -3.2) | -1.62 vs -1.29 (z -3.5) | 9/13 | no: parallel towers hurt once shepherds hold the waves |
| shepwide-vs8-hn (shepherd_max_r=30, shepherd_clear=10) vs shepatk-vs8-hn | 8 vs 12 | 0.220 vs 0.259 | -1.21 vs -1.25 | | 3/7 | no: more shepherds lost (38 vs 33), fewer launches drawn (10 vs 13) |
| atkthr-vs8-hn (attack_threat_ratio=0.3) vs shepatk-vs8-hn | 11 vs 12 | 0.250 vs 0.259 | same | same | 0/1 | inert in the batch (one game differs) |

Shred mission (Chieftain.shred, shred=true): no blast in a test game: stun and blast share one charge (any cast
zeroes both) and the chieftain stuns in fights every ~40 s, so the 70 s blast never charges. Needs a policy that
gives up stuns; parked.

New defaults (commit of this entry): shepherd=true; against several enemies defense_radius=60,
project_defense=false, reinforce_multi=true. Frozen as g-shep.

### 2026-09-28: g-shep at N=8 on 200 seeds; shepherd diagnostics

gshep-vs8-hn (seeds 1..200): **20/200 = 10.0 % [6.6, 14.9]**, elim 0.242, lsr15 -1.37 (seeds 1..100: 12, 101..200:
8). Not comfortably above 10 %: no N=8 exam yet. shephome-vs8-hn (shepherd_home_weight=1): 9 vs 12, no.

Our chieftain at N=8 (gshep-vs8-hn): born at a median 7:22, 1.58 births and 1.49 deaths per game, 9.4 casts.

Where shepherds die (play-shepdeath2-s21, loss log with context): many within 0-4 s of recruitment with an enemy
warrior 1-5 cells away (the recruiter took our peon nearest the copy, often one already in a fight), others while
crossing the map to a far spot, others standing with no spot at all. Fixes: recruit only peons with no enemy
warrior within 14 cells and only when some spot exists (counting every unit of ours as a rival target); param
shepherd_patience (send a spotless shepherd home after that many seconds). One game: shepherds lost 40 -> 14 (with
patience 8; launches drawn 11 -> 6). A/B queued: base4-vs8-hn (new recruitment, defaults), pat8-vs8-hn,
rockshare30-vs8-hn (rock_share=0.3: a second ore stream; we harvest 42 rock by 15 min against the Hards' 645).

### 2026-09-28: g-shep confirmation, the Hard race again, more N=8 screens

| run | W | wilson95 | elim | lsr15 |
|---|---|---|---|---|
| gshep-vs7-hn (seeds 1..200, native Hards) | 67/200 = 33.5 % | [27.3, 40.3] | 0.464 | -0.93 |
| gshep-vs7-hv (1..100, viking Hards) | 34/100 | [25.5, 43.7] | 0.460 | -0.98 |
| gshep-vs8-hn (1..200, native) | 20/200 = 10.0 % | [6.6, 14.9] | 0.242 | -1.37 |
| gshep-vs8-hv (1..100, viking) | 15/100 | [9.3, 23.3] | 0.265 | -1.19 |
| gshep-vs9-hn (1..98, native; stopped at 98) | 3/98 | | | |

Race decision: with the stun cancel and shepherds, viking copies are no harder than native ones at N=7 and easier at
N=8 (lightning, which K1 cannot cancel, was the main killer). Exams use viking Hards from here on.

N=8 screens (paired, native Hards, seeds 1..100):
- base4-vs8-hn (safer shepherd recruitment, new defaults) vs gshep-vs8-hn: 11 vs 12, shepherds lost 28 vs 33 per
  game; neutral, kept (sensible).
- pat8-vs8-hn (shepherd_patience=8) vs base4: 12 vs 11, lsr20 -0.13; no.
- rockshare30-vs8-hn (rock_share=0.3) vs base4: 8 vs 11, lsr10 -0.07 (z -3.5), lsr20 -0.31 (z -2.7): rock
  gatherers displace iron ones (iron at 10 min 134 -> 95) and rock warriors are too weak; no.

Timeline (lab/gauntlet/timeline.py, gshep-vs8-hn): wins have the first copy out at 8.8 min (losses 11.1); in losses
our first armory falls at 15.9 min with 1.0 copy out. Early eliminations matter: attack_ratio=1.0 and
quarters_first queued.

### 2026-09-28: N=7 exam with g-shep (viking Hards): passed

`./aisim.sh batch --name final-vs7-g-shep --players "@g-shep vs hard*7" --size large --terrain tropical --hills
0..2 --trees 10 --supplies 10 --seeds 30001..30100 --side 0` (g-shep = commit 540e97e6; lint @g-shep ok):
**W 45 / L 55 / D 0**, Wilson 95 % [35.6, 54.8], all 45 wins by elimination (3 via collapse), 0 failed, 49.3 min
mean length, 39.7 s CPU per game. Replays s30001-0, s30002-0, s30003-0: VERIFIED. (Dev estimate before: 34/100 on
seeds 1..100 against viking Hards, 67/200 against native ones.) Nothing from the exam games is used for tuning.

### 2026-09-28: N=8 after the exam: small tweaks, and a far-target march

Paired with base4-vs8-hn (native Hards, seeds 1..100, 11 wins):
- atk10-vs8-hn (attack_ratio=1.0): 13 (3 gained, 1 lost), lsr10 +0.017 (z +2.9); few games differ.
- noexp-vs8-hn (expansion=false): 9 (3/5), kd -0.08 (z -3.1); no.
- keepout14-vs8-hn (chief_keep_out=14): 13 (4/2); marginal.

play-v8-s5 (N=8, viking Hards, logged): attack at 7:25 with 59, s8 out at 9:02, reinforcements of 12-16 every
30-40 s, s2 out at 14:39, army recalled at 13:52 (62 in the base vs 61 at home), then no more attacks and the
base fell by 29 min. Between the two eliminations the army marched to a target 612 m away (chooseTarget adds 8 m
per unit of expected defense, which pushed it past nearer copies) and spent ~5 min walking and skirmishing across
the map. Params target_defense_weight (default 8) and target_home_weight (default 0: extra meters per meter from
our staging point) queued for tests (tdw2-vs8-hv, thome1-vs8-hv).

### 2026-09-28: CPU, and why the army melts at home against viking copies

CPU (JFR on the replay of play-v8-s5, N=8, 29 min): 511 of 3,634 execution samples are in player.gauntlet (51 in
Reflexes, 118 in Shepherd), 37 in the 8 stock AIs together, the rest in the engine. A game with our AI costs about
1.2x an all-stock game of the same length (summary: 39.7 s CPU for a 49-minute N=7 exam game); our AI uses ~0.4 %
of real time. Fine for real-time play.

play-v8-s5 after the army's recall at 13:52: 155 warriors at home against base threats of 68-125 strength, yet the
army fell to 96 by 17:30 and to 0 by 24 min, with the armory stock empty (wood 0-5, iron 0-4) and iron trips of
80-128 s. Against viking copies a big clump of ours is blast bait: a Hard chieftain blasts when our units and
buildings within 36 m outnumber its side 2:1 (7+), and the blast kills every rock warrior and peon and 60 % of the
iron ones within 18 cells. Expert treats every viking cast as a stun (units run out, trapped ones rush the caster);
with the stun cancel that running is wasted on stuns, while blasts are the real danger. Worse, shepherd-leashed
chieftains idle far from our units for minutes, so they arrive with a full blast (normally their 40 s stun fires
first and zeroes both charges). Param dodge_blast_only (dodge only when getLastMagicIndex() is the blast) queued.

### 2026-09-28: N=8 screens against viking Hards (paired with base4-vs8-hv: current defaults, 14/100)

| variant | W | elim | lsr15 | kd | gained/lost | verdict |
|---|---|---|---|---|---|---|
| dodge_blast_only=true | 16 | 0.301 | -1.17 (z +1.8) | same | 7/5 | small +; enemy blasts are only ~4 per game |
| expansion=false | 8 | 0.250 | -1.21 | -0.10 (z -3.4) | 4/10 | no |
| target_defense_weight=2 | 11 | 0.264 | -1.21 | | 4/7 | no |
| target_home_weight=1 | 10 | 0.251 | -1.21 | -0.02 | 3/7 | no |
| quarters_first=true | 10 | 0.240 | -1.20 | -0.03 | 6/10 | no |
| chief_keep_out=14 | 11 | 0.246 | -1.23 | | 6/9 | no |
| hidden_info=true (read enemy chieftain charge and armory stock) | 7 (z -2.1) | 0.212 (z -2.7) | -1.21 | -0.04 | 2/9 | no: worse |
| our AI as natives (gauntlet/n) | 4 (z -2.8) | 0.134 | -1.67 (lsr10 -0.26, z -7.2) | | 2/12 | no: the native swing restart (31 ticks) is far weaker |
| (g-shep frozen, old recruitment) | 15 | 0.265 | -1.19 | | 6/5 vs base4 | |
| (shepdecoy: decoys with shepherds, vs gshep-vs8-hv) | 12 vs 15 | | | | 6/9 | no |
| (atk10: attack_ratio=1.0, vs gshep-vs8-hv) | 13 vs 15 | | | | 8/10 | no |

Forward towers (Expert's forward_towers over the busiest enemy iron gatherers, with a new forward_threat param to
allow escorts under threat): planned 3 times in a test game and dropped every time for "no cover": escorts need the
army at home, which conflicts with the attack campaign. Not pursued.

Late-game economy at N=8 (play-v8-s5, 18-20 min): 43-54 iron gatherers but ~5 iron per minute: the 10 iron piles by
our start are gone within minutes, the rest lies in the contested central disc (~120 s trips) and gatherers die on
the way; the copies harvest most of the map's iron (they are 8). The window to win is the economic peak, 8-20 min:
eliminations must come faster then.

Map geometry does not explain N=8 wins (500 games of gshep/base4/blastonly): win rate 9-14 % whether the nearest
copy starts 90-100, 100-120 or 120-140 cells away.

### 2026-09-28: the campaign at N=8 (camp40-vs8-hv, 40 logged games, lab/gauntlet/campaign.py)

Per game in the first 30 minutes: HOME 1,106 s, ATTACK 437 s, MUSTER 47 s, RETREAT 20 s; 1.25 musters, 1.23
attacks, 0.85 "calling the army home", 5.45 reinforcements, 1.55 copies out. Most losses make exactly one attack
(59-94 strong at 7-10 min), it is called home when the base is hit, and the army never attacks again: after that
the base threat never drops below level 2 (blobs idle within 28 cells of our buildings), and considerAttack only
runs below level 2. The wins are long sustained attacks (810-1,350 s in ATTACK, 11-20 reinforcements) that
eliminate 3-6 copies. Params recall_ratio (default 0.35: recall when the base threat beats the home defense and
0.35 of the attack) and attack_threat_ratio queued: recall10-vs8-hv (1.0), recallatk-vs8-hv (1.0 + 1.0).
Also pending: q5/q6 (more quarters), raid8 (raids under threat), decoync (decoys without the cage rule).

### 2026-09-28: more N=8 attempts (viking Hards, paired with base4-vs8-hv, 14/100)

| variant | W | notes |
|---|---|---|
| initial_quarters=5, max_quarters=5 | 14 | kd -0.11 (z -3.0) |
| max_quarters=6 | 13 | kd -0.09 (z -2.7) |
| raid_threat=2, raid_size=8 (raids on enemy peons under threat) | 9 | 4/9 |
| recall_ratio=1.0 | 15 | 4/3 |
| recall_ratio=1.0, attack_threat_ratio=1.0 | 15 | 5/4; logged campaign (camp40ra): ATTACK 572 s vs 437, still ~1 muster per game |
| decoys without the cage rule (with shepherds) | 14 | 6/6; decoys active (20 placed, 11 razed per game) |

What the logged games show (camp40ra s5-0): every big fight is won on trades (+9 to +161), including 132 and 40
enemy peons killed at their bases, but while the army is out our base is razed (7 buildings in one fight, 13 in the
next), the economy stops, and the army cannot be replaced: at N=8 we cannot both hold the base and attack. Cheap base
defense is the missing piece, and none of the tower/decoy layouts tried so far delivers it.

Consolidated: dodge_blast_only on by default (commit c002c1ae), frozen as g-v8. Fresh-seed estimate at N=8
(seeds 201..400) running.

Robustness sweep of @g-v8 (lab/gauntlet/robustness.sh, first half): random maps of every size and terrain.
duel vs hard 59-1 (60 games), as natives vs hard*3 53-25-2 (80), in team B vs hard 40-0 (40), allied with hard vs
hard*3 64-11 (75); 0 failed games, no swallowed errors.

### 2026-09-28: g-v8 fresh-seed estimate at N=8; home guard

gv8-vs8-hv-b (@g-v8 vs hard*8, seeds 201..400, never used for tuning): **31/200 = 15.5 % [11.1, 21.2]**, elim 0.287,
0 failed, no swallowed errors. The Wilson lower bound is above 10 %, so the N=8 exam is sat with @g-v8 (queued).

New param home_guard: when the army attacks or reinforces, units worth that much strength stay home (the ones
nearest the armory), since at N=8 the base otherwise falls behind the attacks. Tests queued: guard20, guard40 vs
gv8-vs8-hv (seeds 1..100).

### 2026-09-28: N=8 exam with g-v8 (viking Hards): passed

`./aisim.sh batch --name final-vs8-g-v8 --players "@g-v8 vs hard*8" --size large --terrain tropical --hills 0..2
--trees 10 --supplies 10 --seeds 30001..30100 --side 0` (g-v8 = commit c002c1ae; lint @g-v8 ok): **W 11 / L 89 / D
0**, Wilson 95 % [6.3, 18.6], all 11 wins by elimination (2 via collapse), 0 failed, no swallowed errors, 34.6 min
mean length, 30.2 s CPU per game. Replays s30001-0, s30002-0, s30003-0: VERIFIED. Dev before: 31/200 on fresh
seeds 201..400, 16/100 on 1..100. **N* = 8.**

home_guard=20 vs gv8-vs8-hv (seeds 1..100, 16 wins): 5 wins (2 gained, 13 lost, z -2.9): keeping warriors home
hurts; attacks need everything (wins are long all-in campaigns). home_guard=40 cancelled.

### 2026-09-28: N=9, decoys at N=8, the Hard's quarters gate

gv8-vs9-hv (@g-v8 vs hard*9, seeds 1..100): **2/100** [0.6, 7.0], elim 0.162. N=9 needs something new.

Why decoys rarely fire at N=8 (decoy_nospot_far): a logged game (play-decoycheck4-s7, decoys on, front towers
pushed out with the new params front_tower_min/max) traced each failure. The nearest of our buildings to a copy's
wave origin is usually an unfinished tower site 19-53 cells from any manned tower, so no spot 11-14 cells from a
manned tower can be nearer to the origin, and waves go to the site instead (outside tower reach). Decoys can only
pull waves in front of a finished tower line, which at N=8 we never have towards most copies. Parked.

**The quarters gate** (AdvancedAI.nodeAttackWithWarriorsAndChieftain, source-read): from wave size 20 on (Hard's
third wave) a wave leaves only with an active chieftain, and the chieftain trains only at a finished quarters. A
copy that loses its quarters while its idle warriors are at least its wave size never rebuilds them (the rebuild
sits in nodeTransferUnits, which runs only when warriors are missing), so once its chieftain is dead it never
attacks again. New param gate_freeze: the attack target is the nearest quarters (finished or site) of a copy that
still has one, the copies without quarters are skipped until none is left. First game (play-gatefreeze-s7): the
first attack razed s5's quarters (and its armory, since the army was standing on it), then marched on to s7's
quarters. Current source with defaults = @g-v8 (identical checksums on seeds 203..206, idcheck-gv8src).

aggro-vs8-hv-b (attack_ratio=1.0, adaptive_caution=false, recall_ratio=2.0, attack_threat_ratio=1.0, fresh seeds
201..400): **43/200 = 21.5 %** vs gv8-vs8-hv-b 31/200 (25 gained, 13 lost; win z +2.0, elim +.077 z 3.4, prog z
3.3, kd +.10 z 5.4). With 19 vs 16 on seeds 1..100 that is 62/300 vs 47/300. Adopted as the default whenever there
is more than one enemy (Strategy.forGame).

gatefreeze-vs8-hv (gate_freeze=true on the g-v8 defaults, seeds 1..100): 12 vs 16 (6 gained, 10 lost), elim -0.02,
kd -0.03: no gain. lab/gauntlet/quarters_gate.py on it: 142 copies lost their quarters and stayed in; 78 of them still
had a chieftain (so they kept waving until it died), 59 rebuilt their quarters (fighting at the quarters kills
their home warriors, which drops them below their wave size and into the rebuild branch), 15 trained a new
chieftain. The gate holds for too few copies. v2 also targets the rebuilt quarters sites (they were not candidates:
Intel lists finished quarters only); gfv2-vs8-hv queued on the new defaults.

### 2026-09-28: aggressive defaults at N=9; where the base falls

aggro-vs9-hv (new defaults vs hard*9, seeds 1..100): **5/100** vs gv8-vs9-hv 2/100 (3 gained, 0 lost), elim +.069 (z
4.3), prog +.072 (z 3.5), kd +.05. Better, still far from 10 %. gfaggro-vs8-hv (gate_freeze v1 on the aggressive
defaults): 16 vs 19 (9/12), neutral. amax50-vs8-hv (attack_max_strength=50): 19 vs 19, inert.

Logged N=9 game (play-agg9-s11, new defaults; a STAT diagnostic park=out/in man=manned/towers added, identical
checksum with and without it): the army leaves at 4:14 and never comes home. It razes s4 (7 min), s1 (10), s8
(14), s5 (16) and s6's and s9's bases (18-21 min), always marching to the target nearest to itself, so it drifts
across the map (s5, s6, s9 are 340-354 cells from our start) while s3 (136 cells away) and s2 (213) are never
attacked. From 16 min 20-60 idle enemy warriors stand within 45 cells of our buildings, almost none within reach of
our towers, all 14 of which are manned; the waves raze the expansion armory, the towers one by one and the base by
19-21 min, with 4-5 copies left. New param target_threat_weight (meters off a target's score per unit of strength
its owner has in our base); tthreat5, tthreat15 and thome05 (target_home_weight=0.5) queued on N=8.

### 2026-09-28: N=8 screens on the aggressive defaults (paired with aggro-vs8-hv, 19/100, seeds 1..100)

| variant | W | gained/lost | notes | verdict |
|---|---|---|---|---|
| gate_freeze=true (v2: quarters sites too) | 17 | 11/13 | elim +.004, kd +.04 | no |
| attack_max_strength=50 | 19 | 1/1 | inert | no |
| reinforce_ratio=0.3 | 17 | 4/6 | | no |
| precontact_ratio=0.8 | 19 | 1/1 | inert | no |
| retreat_ratio=2.0 | 19 | 0/0 | inert | no |
| target_home_weight=0.5 | 19 | 2/2 | nearly inert | no |
| target_threat_weight=5 | 16 | 0/3 | kd +.012 (z 2.2) | no |
| target_threat_weight=15 | 13 | 5/11 | elim -.033 | no: worse |
| snipers=true (sniper towers by parked blobs) | 16 | 3/6 | lsr10 -.015 (z -2.7); 24 planned per game, most never placed (sniper_nospot_unsafe 34 per game: blobs overlap and awake units pass) | no |

Where parked enemies stand (STAT pb/pt histograms, play-park9b-s11): 16-45 cells from our nearest building and
15-45 from our nearest manned tower, i.e. where the building they razed stood; all towers are manned (man=14/14)
until they fall.

**Tower garrisons and stuns** (source-read): Stun.animate stuns the garrison of every tower in its radius, and
towerFire skipped stunned gunners. An order to a tower (LandBuilding.setTarget) pushes an AttackController on the
garrison without clearing its stack: given on the tick the StunController comes on top (Unit.stun leaves the unit
interruptible for that tick, as K1 uses), it decides at once and the garrison throws on; the stun waits under it
until the attack ends, when it comes on top again and can be deferred once more. New param tower_unstun (Reflexes,
per tick, one order per tower per stun resumption). play-tunstun9-s11 (N=9, seed 11): 35 tower un-stuns, kills
1,551 vs 1,319 on the same seed without it, 39:21 vs 37:23 survived; replay VERIFIED. Batches queued (N=8, N=9).
tower_mutual (towers 10-13 cells apart) running.

tunstun-vs8-hv (tower_unstun=true, paired with aggro-vs8-hv): **23 vs 19** (6 gained, 2 lost), elim +.044 (z 2.2),
prog +.057 (z 2.3), lsr20 +.18 (z 2.9); 13.5 tower un-stuns per game, no errors. tmutual-vs8-hv (tower_mutual=true):
21 vs 19 (10/8), lsr20 +.195 (z 1.6): maybe. Queued: tunstun-vs8-hv-b (fresh seeds 201..400, paired with
aggro-vs8-hv-b), tunstun-vs9-hv, tower_unstun + tower_mutual.

tmutunstun-vs8-hv (tower_mutual on top of tower_unstun, vs tunstun-vs8-hv): 23 vs 23 (11/11): nothing on top;
tower_mutual stays off (the fresh-seed run tmutunstun-vs8-hv-b was stopped). tunstun-vs9-hv: 4 vs 5 (1/2): the
tower stun cancel does not move N=9.

### 2026-09-28: final version

Since the N=8 exam (N* = 8), substantially different attempts at N=9: base defense by home guard (worse), decoys
with front towers pushed out (no spot), the all-in campaign (N=8 up, N=9 2 -> 5 %), the quarters gate (neutral),
target choice by threat or home distance (neutral or worse), sniper towers (worse), mutual towers (neutral), the
tower stun cancel (N=8 up, N=9 flat). N=9 stays at ~5 % on dev, far from an exam: the stop rule holds.

Final version = aggressive campaign defaults + tower_unstun, frozen as **g-final** (commit 2f266fba; lint @g-final
ok). Final runs: gfinal-vs9-hv-b (N=9, fresh seeds 201..400), robustness sweep robust-gfinal-*, and the N=8 exam
of @g-final (so the delivered package is the one examined; g-v8's N=8 exam stands as the first attempt).

gfinal-vs9-hv-b (@g-final vs hard*9, fresh seeds 201..400): **9/200 = 4.5 % [2.4, 8.3]**, 1 draw, 0 failed, no
swallowed errors. No N=9 exam.

Robustness of @g-final (robust-gfinal-*, random maps of every size and terrain), first half: duel vs hard 59-1 (60),
as natives vs hard*3 51-28-1 (80), in team B vs hard 40-0 (40), allied with hard vs hard*3 63-12 (75); 0 failed games,
no swallowed errors. Second half running; the N=8 exam of @g-final (final-vs8-g-final) running.

### 2026-09-28: N=8 exam with g-final (viking Hards): passed

`./aisim.sh batch --name final-vs8-g-final --players "@g-final vs hard*8" --size large --terrain tropical --hills
0..2 --trees 10 --supplies 10 --seeds 30001..30100 --side 0 --workers 14` (g-final = commit 2f266fba; lint @g-final
ok): **W 25 / L 75 / D 0**, Wilson 95 % [17.5, 34.3], all 25 wins by elimination (4 via collapse), 0 failed, no
swallowed errors, 37.0 min mean length, 32.7 s CPU per game. Replays s30001-0, s30002-0, s30003-0: VERIFIED. Dev
before: 52/200 on fresh seeds 201..400. **N* = 8** (N=9 dev 9/200).

## After the report: N=10 and N=11 (2026-09-28, user asked for even a single win at N=10 or 11)

Baselines of @g-final on dev seeds (running): gfinal-vs10-hv (1..200), gfinal-vs10-hn (1..100), gfinal-vs11-hv
(1..100). **First N=10 win: gfinal-vs10-hv s40-0** (seed 40, hills 2), a 68-minute game, 4,075 kills for 1,230 losses;
replay VERIFIED. The nearest copies start 91 (s1) and 100 cells (s9) away; s9 is out at 11.2 min, s1 at 14.0. From
11 min the waves raze towers and quarters again and again (the main armory falls at 32.9 min), but the base keeps
rebuilding and a second armory stands from 24 min; the army campaigns almost without pause and puts out s8 (25.6),
s5 (34.1), s4 (41.5), s7 (49.4), s10 (60.6), s2 (64.8), s3 (68.2). Logged N=10 losses (play-gfinal10-s11, -s23): the
army attacks only at ~10 min (at N=9: ~4 min), with threat level 2 from 5 min.

Baselines complete (@g-final, dev seeds): N=10 vikings 2/200 (s40, s196; 1 draw s93), natives 1/100 (s81); N=11
0/100 vikings, 0/200 more vikings (seeds 101..300), 0/100 natives.

Analysis workflow (4 analysts, 3 strategists, a judge; reports in the session scratchpad n10/*.md): at N=10 the base
falls at ~20 min; parked blobs of 20-120 idle enemies stand 16-45 cells from our buildings, out of tower reach;
units inside razed buildings vanish uncounted (LandBuilding.removeDying: ~123 per game at N=10, 39 per armory);
our attack gate counted 0.3 x every other copy's army (Military.enemyFieldStrengthNear), which at N=10 alone is
~2.7 copy armies, and a global "any enemy chieftain" malus, so the first attack came at ~10 min instead of ~4;
the draws were endgame failures.

Endgame fix (default): a target whose attack stalls is skipped for 10 min, and so is every target in the region its
distance field covers when that region does not reach our staging point (Military.stalled_targets, dead_regions).
The N=10 draw s93 (5 hours marching at a site in a 252-cell pocket; re-targets mid-attack kept picking the stuck
copy's 20 peons) became a win at 130 min (play-stall10d-s93).

N=10 screens (paired with gfinal-vs10-hv, seeds 1..100 unless noted; W = wins, elim = share of copies out):

| variant | elim diff (z) | other | verdict |
|---|---|---|---|
| gate_owner (owner-aware defense, local chieftain malus) | +.031 (1.7); seeds 101..200 +.040 (3.1) | N=9 fresh +.035 (2.3), W 11 vs 9; **N=8 fresh W 65 vs 52 of 200 (30/17), elim +.086 (3.4)** | **adopted** (several enemies) |
| reinforce_threat_ratio=1.0 | +.008 | kd +.038 | no |
| finish_copies (sites, chieftain, units of raided copies) | +.007 | lsr15 -.021 | no |
| tower_cap | -.015 (-1.5) | | no |
| attack_ratio=0.85 | .000 | | no |
| shepherd=false | **-.084 (-6.0)** | lsr15 -1.77 (z -11) | shepherds are vital at N=10 |
| evacuate (60 % hp, 3 warriors) | -.011 | lsr15 +.055 (2.0), kd -.068 (-3.7): evacuees die outside | parked |
| tower_parallel_late=2, sites_parallel_late=3 (from 10 min) | -.012 | kd +.057 | no |

Shred v2 (strict: never stun, blast parked/near enemies): first versions never cast (the cast cell had to be
outside every enemy's 8-cell sight, but whatever can see the caster stands inside the 18-cell blast anyway). Fixed:
cast when >= 8 enemy warriors are within 17 cells, none within 5, no enemy tower within 22, at least 3 of theirs
per unit of ours in the blast, at most 2 of our buildings within 18; blast from where he stands when hunted; den
behind the armory; Military no longer counts his stun or his attack bonus. Probes (N=10 seeds 11/23/40): 2-5
blasts per game, 37-91 enemy warriors in reach. Batch queued. Lure-kiting (Lures.java): works mechanically after
relaxing the bait geometry (8-16 lures per 30 min, 2/3 get home), but draws only 0-4 hunters each (only the member
that sees the peon hunts). Batch queued. Rock surge (rock gatherers from idle armory workers while iron starves)
queued.

More N=10 screens (paired with gateown-vs10-hv, i.e. on top of gate_owner, seeds 1..100):

| variant | elim diff | other | verdict |
|---|---|---|---|
| shred v2 fixed (shred_strict, min_hp 15, range 70) | -.007 | 2.75 blasts, 59 caught per game; lsr20 +.22 (z 1.6), kd +.03 | no (strength up a little, eliminations flat) |
| lure (21 lures per game, 15 get home) | +.004 | lsr15 -.10 (z -1.8) | no |
| rock_surge (div 3, min workers 8, stock 40) | +.004 | rock harvested 63 vs 57: barely fires | no |
| gather_avoid_parked (vs gfinal) | -.006 | | no |

First attack at N=10 with gate_owner: muster at 384-393 s (6.5 min) instead of 456-549 s. Running: current defaults
(gate_owner, endgame fix) at N=10 seeds 1..200 and N=11 seeds 1..300.

Current defaults (gate_owner + endgame fix, = @g-n10 frozen from HEAD) on dev seeds: **N=10 2/200** (s20 at 101 min,
s196 at 46 min), elim +.035 (z 3.2) and lsr20 +.27 (z 2.5) vs @g-final on the same seeds; **N=11 0/300** (elim +.022,
z 3.9 vs gfinal-vs11-hv-b). N=11 total so far: 0 wins in 700 games.

Opening screens at N=10 (paired with cur-vs10-hv):
- hold_mid_time=360: neutral.
- **armory_threat_weight=37**: seeds 1..100 W 4 vs 1 (3/0), elim +.020, kd +.067 (z 2.0); seeds 101..200 W 2 vs 1
  (1/0), elim +.015, kd +.100 (z 3.1). armory_threat_weight=20: seeds 1..100 W 4 vs 1 (3/0), elim +.032. The
  armory's exposure weight is 60 x (1 + 0.75 (N-1)) = 465 at N=10 (SitePlanner): it keeps the first armory away
  from the good iron. New param armory_threat_cap caps (N-1) (cap 5 at N=10 = weight 285, like 37 x 7.75 = 287);
  cap 5 checked at N=8 and N=9 on fresh seeds (running).

armory_threat_cap=5 checks (paired with the gate_owner runs on fresh seeds 201..400): N=8 W 65 vs 65 (13/13), kd
+.034 (z 2.1); N=9 **W 15 vs 11** (6/2), elim +.024 (z 2.1). Adopted as the default (it changes nothing below N=7).
Frozen: **@g-final2** (commit 4fe8ddfd: gate_owner, endgame fix, armory_threat_cap 5, new params off). Running:
@g-final2 at N=11 on seeds 1..400 and at N=10 on fresh seeds 201..400.

**@g-final2 results on dev seeds**: N=10 fresh seeds 201..400 **4/200** (s290, s316, s345, s365; s316 replay
VERIFIED); **N=11 seeds 1..400: 2/400** (s336 at 50:17, s397 at 62:06; both replays VERIFIED). Both N=11 wins put
out four copies by 14.1 min (s336: 8.1, 10.2, 12.7, 14.1 min; s397: 8.2, 10.2, 12.2, 14.1) with the two nearest
copies 88-95 cells away. N=11 before: 0 of 700 (@g-final 400, current defaults without the cap 300).
N=8 exam re-sit with @g-final2 running (final-vs8-g-final2).

### 2026-09-28: N=8 exam with g-final2 (viking Hards): passed

`./aisim.sh batch --name final-vs8-g-final2 --players "@g-final2 vs hard*8" --size large --terrain tropical --hills
0..2 --trees 10 --supplies 10 --seeds 30001..30100 --side 0 --workers 14` (g-final2 = commit 4fe8ddfd; lint
@g-final2 ok): **W 26 / L 74 / D 0**, Wilson 95 % [18.4, 35.4], all 26 wins by elimination (4 via collapse), 0
failed, no swallowed errors, 36.6 min mean length, 29.7 s CPU per game. Replays s30001-0, s30002-0, s30003-0:
VERIFIED. Dev before: 65/200 on fresh seeds. **N* = 8** (N=9 dev 15/200; no exam at N>=9).

Robustness of @g-final2 (505 games): 0 failed, no swallowed errors; duel 59-1, natives 52-27-1, team B 40-0, allied
64-11, free-for-all 35-4-1, mirror 8-10-2, huge 39-4-7, medium6 6-134. Replays of the listed N=10/11 wins
(gfinal-vs10-hv s40-0, play-stall10d-s93, gf2-vs10-hv-b s316-0, gf2-vs11-hv s336-0 and s397-0): all VERIFIED.
REPORT.md updated (exam attempt 4, "Wins against 10 and 11 Hards", the N=10 dead ends).

## Micro push for 1v11 (2026-09-28, user: micromanagement, towers, pathing, economy, luring)

Reports of the analysis workflow (5 investigators, verifiers, a judge) are in the session scratchpad micro/*.md.
Engine facts behind the tower work (verified in source): a garrison keeps the grid cell it entered from (Unit.mount
moves only the world position), and range checks and scans use that cell, so a tower reaches 17-18.4 cells towards
its entry side and 12.7-13 behind; range is compared in grid cells (dist^2 <= 252 for garrisons, 62 in the field);
the throw is 2 s and uninterruptible with the release at 1.0 s, and an iron axe flies 25 m/s (rock 20, rubber 30), so
beyond ~12.5 cells the target is still alive when the throw ends; an order given during a throw waits under it.

Protocol: pair with t2-vs11-hv (the defaults after the tower bundle and stall_calm) on seeds 1..200; adopt at elim
z >= 1.5 with lsr15/lsr20 not negative, or lsr20 z >= 2 with elim >= 0; check at N=8 on seeds 201..400.

| variant (N=11, seeds 1..200) | elim diff (z) | other | verdict |
|---|---|---|---|
| tower bundle: tower_gunner_reach, tower_prequeue, tower_reflex, tower_self_first, chicken_gunners (vs gf2) | +.015 (1.5) | **lsr20 +.52 (4.9)**, W 4 vs 0, +3.2 min | **adopted** |
| stall_calm (stall clock paused while fighting; ban cascade fixed) | +.004 | | adopted |
| tower_full_reach (from the centre) + tower_reflex | -.004 | phantom targets | superseded |
| army_reflex | +.005 | | no |
| lure v2 (bait hunters into towers) | +.004 | lsr20 -.04 | no: only the member that sees the bait hunts |
| peon_dodge + chief_dodge | -.001 | saves units, elim flat | no |
| hold_chieftain=45 + chief_topup_any | **-.034 (-4.5)** | | no |
| tower_front_entry (garrison enters from the side facing the enemy) | **+.025 (2.1)** | W 6 vs 3, kd +.061 | adopt (N=8 check running) |
| reinforce_intercept | -.005 | | no |
| max_armory_distance=200 | | tmin -1.3 (-2.1), W 0 vs 2 | no |
| rock_stream | -.005 | lsr15 +.036, W 0 vs 2 | no |
| ore_load=6, ore_load_penalty=2 | +.012 (0.8) | | no |
| quarters_before_armory=2 | +.002 | | no |
| **peon_militia=false** | **+.051 (4.1)** | **lsr10 +.26 (5.8)**, lsr20 +.55 (4.1), kd +.17 (4.4), W 6 vs 3 | **adopt for N>1** |

Why the militia hurt so much (play-mil-on-s1 / play-mil-off-s1): with 12 players on the map, neighbouring copies'
gatherers work near our start, and raiding() (an enemy peon near our buildings and away from its own) takes them
for raiders. At 27 s 16 of our 20 starting peons chase one of them, and again 14 times in the first 5 minutes. With
the militia at 4 min: 1 quarters, no armory; without: 4 quarters, an armory and 2 tower sites; at 10 min 1 quarters
and no armory against 4 quarters, 2 armories, 11 towers and 106 peons. lsr10 better by >0.5 in 32 games, worse in 1.
(Hard copies do not raid with peons, so the militia has nothing to answer at N>1.)

A Hard copy defends only when an enemy unit stands within 30 m (15 cells) of its first quarters or armory
(AdvancedAI.nodeDefendBase): it then deploys its armory stock and sends idle warriors and gatherers there, and the
deployed warriors bring its next wave forward. Diagnostic counters prov_<peon state or role> (Military.provokeProbe,
no orders) count our units within 15 cells of an enemy quarters or armory every 5 s in the first 15 minutes; in
play-prov-11-s23 only the attacking army (1,211) and shepherds (10) were there.

**Engine crash (not ours).** 1 in ~200 N=11 games crashed with ArrayIndexOutOfBoundsException in
HeightMap.getLeafFromCoordinates from PathTracker.update (t2/tow2/armdist200 s125, dodge s168; the old s77 at N=4 is
the same trace). Reproduced on s125 at 1294.76 s; a temporary print in PathTracker (reverted, never committed)
named the unit: a Hard copy's (s11) warrior in a jam, whose PathTracker was SOFTBLOCKED and whose Bezier path
parameter went negative when the deadlock solver advanced it mid-curve (solveDeadlock -> advance -> nextPoint does
t -= 1), so the spline evaluated at a large negative t and threw it ~150 cells off the map. Nothing of ours is
involved except that our play changes which games reach such a jam. Both N=11 crashes came when we were already
beaten (s125: no buildings, 2 peons; s168: 1 peon), when all eleven copies' waves converge on our last units.
An engine fix would change the simulation (not allowed here), so failed games stay counted as losses.

Wins at N=11 are bimodal (militiaoff-vs11-hv): 6 wins at 62-85 min and losses with at most 5 of 11 out, mostly at
24-38 min. By 15 min the wins have put out 2.8 copies against 1.8 in the losses that last past 25 min, with the same
own strength (850 vs 778); iron harvested flattens after 15 min in the losses (220 -> 258 by 25 min) but keeps
growing in the wins (235 -> 326). Levers: more copies out at 10-15 min, and iron after 15 min.

**New defaults (the full stack):** peon_militia=false for N>1 (forGame), tower_front_entry, tower_reaim and
tower_prequeue_any on. Screens: towmicro2-vs11-hv (tower_reaim + tower_prequeue_any) elim +.026 (z 2.2), W 7 vs 3,
3.7 re-aims and 220 pre-queues per game (54 before); qrally (quarters_rally) neutral to negative.
- full-vs11-hv (1..200) vs t2: elim +.050 (z 3.9), lsr10 +.26, lsr15 +.45, lsr20 +.62 (z 4.9), kd +.17, +3.0 min,
  W 5 vs 3. Against militiaoff alone: elim -.002, lsr20 +.07: the tower micro adds survival, not eliminations, at N=11.
- **N=8 fresh seeds 201..400: full-vs8-hv-b W 111 vs 99 of 200** (cur-vs8-hv-b: the tower bundle and stall_calm
  defaults), elim +.054 (z 2.2), lsr10 +.12 (z 4.8). (For reference @g-final2 had 65/200 on these seeds.)
- **Fresh N=11 seeds 201..400: full-vs11-hv-b W 6 vs 2** (t2-vs11-hv-b), elim +.040 (z 3.2), lsr10 +.28 (z 6.3),
  lsr20 +.57 (z 4.6). The full stack at N=11 so far: 11 wins in 400 games (old defaults: 5 in 400).
- Screens on the full stack (paired with full-vs11-hv): chieftain_time=180 neutral (-.008); initial_quarters=3
  negative (elim -.026, z -2.1; lsr10 -.10, z -5.2).

### Audit of 40 logged N=11 games (camp-mf-vs11-hv; 4 lens auditors, a skeptic each, a judge)

Reports: session scratchpad audit/{opening,campaign,defense,economy}.md, *-verify.md, judge.md; 18 of the findings
survived verification. What frames them:
- Every copy that goes out is put out by an attack (77 of 80), and **after the first "calling the army home" no copy
  ever goes out** (48 before, 1 after, over a median 548 s more; 24 of 40 games recall). Military.defend recalls at
  base threat > home army + towers and > recall_ratio (2) x the attack's current, shrinking strength: it ends 21-22
  of 39 main attacks, a median 238 cells out at 57 % of peak, 9 of them within ~60 m of the target.
- The army is iron-limited, not gate-limited: weapons in stock ~0 all game; at 12 min (peak 88 warriors) ATTACK 59,
  home 19, towers 10. The map runs out of iron at 10-12 min, and the engine respawns a node only once 75 % are empty,
  one every 10 s at a random empty spot (SupplyManager). We collect ~12 % of the map's iron after 14 min.
- Units vanish uncounted inside razed buildings: ~50 per armory razing, ~18 per quarters, ~200 per game; idle peons
  are sent into the primary armory even while a blob besieges it and it has no ore.
- rock_stream doubled rock harvested (86 vs 45 by 18 min) but kept only 4-5 more rock warriors alive.
- The screened towers_early/mid/late values change no decision (Economy adds min(alive-1, front_tower_bonus_max=100)
  to every tower target) and expand_time is dead with initial_quarters = max_quarters = 4: dropped from the queue.

New params (all default off, commits c6a1c828 and 059dfce6): chief_trainer_near (train in the quarters near the
armory with no enemy within 30 cells; top up only with peons sent there), danger_refuge (spare peons avoid a
threatened ore-less armory; quarters hold theirs), expand_under_threat (expansion check under threat, at a quiet site
and route), launch_recheck (drop a timed-out muster that the staging army cannot back) and recall_cooldown,
retreat_rearguard, and a consistency bundle: rush_opening_only, target_path (re-targets by walking distance),
defend_stable (stun credit only near the threat, armory hysteresis 14/17, 3 s engage dwell), tower_cooldown.
Screens queued: recall_ratio=99 (N=11 and N=8), each new param, the bundle, and the remaining param screens.

### Screens after the audit (paired with full-vs11-hv unless noted; seeds 1..200)

| variant | elim diff (z) | other | verdict |
|---|---|---|---|
| recall_ratio=99 | +.008 (1.4); fresh 201..400 **+.020 (3.3)**, W 8 vs 6 | lsr20 +.10 (3.0); N=8 +.004, W 111/111 | **adopt** (logged: ATTACK 676 s vs 560, 2.23 outs vs 2.00; attacks wear out 2 min later) |
| launch_recheck (on recall99) | -.002 vs recall99 | 0.18 drops/game | no |
| hold_mid=10 | +.013 (1.0); fresh **+.030 (2.4)** | W 16 vs 11 over 400; N=8 +.003, W 114/111, kd +.051 | **adopt** |
| retreat_rearguard | **+.004 (2.0)** | kd +.018 (2.0) | adopt (fires only on retreats) |
| sites_parallel=3 | +.006; fresh +.015 | lsr15 +.09 (2.0); N=8 lsr20 -.12 (-2.0), elim -.024; with hm10 +.005 fresh | no |
| **shepherd_time=150** | **+.038 (2.4)** | lsr15 +.12 (2.4), **W 12 vs 5** | confirm on the new base, fresh seeds, N=8 |
| attack_min_strength=14 | +.014 (1.2) | W 7 vs 5 | maybe |
| chief_trainer_near | +.003 | kd -.048 (-2.1) | no |
| expand_under_threat | -.003 | 14.5 sites blocked, 0.8 placed per game | no |
| danger_refuge | -.002 | | no |
| bundle (rush_opening_only, target_path, defend_stable, tower_cooldown) | -.001 | | no |
| max_quarters=5 | -.010 | kd -.078 (-3.7) | no |
| armory_builders=20 | +.003 | | no |
| chieftain_time=180 / initial_quarters=3 | -.008 / -.026 (-2.1) | | no |

The user asked (2026-09-28) whether hysteresis, enemy-count-dynamic strategies and wasted peon-time are worth
pursuing; the analysis (session scratchpad threads/synthesis.md) found: engage/fall-back flips (26/game) already have
hysteresis and cost little; the costly moments are one-way decisions (retreat: 247 lost / 113 killed in the next
20 s), which recall99 and rearguard soften. Games are decided at 9-6 copies alive and effects keep their sign
across N, so no count-keyed rules (and new adoptions go to all N>1, checked at N=8; the user prefers no N>1 gating
where it barely matters). Unit-time budget over 0-25 min: breeding holds below the cap 13.6 % (too high: hold_mid=10),
idle armory workers 7.9 % (mostly exposure after 10 min, not recoverable), cap time not a waste (stock ~0 there).
New param hold_backlog (quarters hold only hold_early while the main armory can forge >= N weapons) queued.

New params behind flags (commit 7d8c74b8): finish_lean (finish_skip_out, finish_units, finish_ratio) and the
chief_hunt squad (6 iron warriors kill the lone chieftain / rebuild sites of homeless copies; round-3 design report
scratchpad round3/judge.md). All tuning batches use the benchmark map settings (dev.sh: large tropical h0..2 t10
s10, slot 0); 93 runs checked.

### New base (base2 = recall_ratio 99, hold_mid 10, retreat_rearguard), adopted for all N (commit e5048fd2)

- base2-vs11-hv vs full-vs11-hv (1..200): elim +.026 (z 1.9), W 7 vs 5; **base2-vs11-hv-b vs full-vs11-hv-b (201..400):
  elim +.050 (z 3.7), W 12 vs 6.** Over 400 seeds: ~+.038 elim, **W 19 vs 11 (4.75 % vs 2.75 %)**. The three effects
  add up (+.008, +.013, +.004 measured alone).
- 1v1 on the benchmark maps (duel-cur-hv vs duel-new-hv, with militia off too): W 100 vs 99 of 100, games 3.5 min
  shorter. So the new values are plain defaults, without an N>1 gate (the user's preference); peon_militia=false and
  recall_ratio moved out of forGame. The default build reproduces the explicit-param base exactly (same checksum on
  s5).
- Later screens: tower_parallel=2 -.014 (no); focus_bonus=100 + focus_finish +.017 (z 2.5; W 4 vs 5; being re-run on
  base2); attack_min_strength=14 +.014 (re-run on base2).
- Finishers on base2 (1..200): **finish_copies +.028 (z 2.4)**, survival flat, W 6 vs 7 (confirming on 201..400 and
  N=8); finish_lean (skip_out, units 8, ratio .5) +.015; finish_lean + chief_hunt +.017; chief_hunt alone +.004 with
  lsr15 -.023 (z -2.5): the squad starts 0.49 chieftain hunts and kills 0.14 per game (most targets are > 150 cells
  out). No to lean and hunt.

### shepherd_time 120 adopted (commit d6dfd295); first N=12 numbers; the goal

The user (2026-09-28): the benchmark is the highest N that a general strategy beats even a few times on random seeds
(N=13-14 with single-digit wins among many games counts more than a 10 % rate), strategy-first and never
map-specific; the legacy params and early features come from a 1v1 superhuman project that never beat more than 4-5 in
1vN, so every legacy default, old behaviour and early-discarded idea is to be re-screened in the 1vN regime.

- shepherd_time (paired with base2): 120 s: 1..200 elim +.048 (z 2.9), W 15 vs 7; 201..400 +.024 (z 1.4), W 13 vs 12,
  lsr15 +.19 (z 4.7), lsr20 +.38 (z 3.9); N=8 +.023, W 118 vs 115. 150 s: survival up, elim -.012 / -.001, W 3 vs 7 and
  10 vs 12. 90 s: same survival as 120, elim -.030 against it (W 8 vs 15). Adopted 120 (the default build reproduces
  the explicit arm, same checksum).
- Not adopted on base2: finish_copies (elim +.028 / +.024, z 2.4 each, but W 14 vs 19 over 400: homeless copies launch
  0.04 waves per copy-minute vs 0.56 housed, so finishing them adds outs that do not win games), focus_bonus 100 +
  focus_finish (+.008, W 3 vs 5), hold_backlog=4 (lsr10 -.063, z -5.3: breeding matters early), hold_mid 8 / 12
  (-.005 / -.010: flat optimum around 8-10).
- **N=12, base2 (before shepherds 120): cur-vs12-hv W 3/200 (s13, s64, s83), elim .177.** N=12/13 baselines with the
  new defaults running (7 workers each: >12 players need a 1 GB heap per worker).

### Planning for N=13-14; code round 4 (2026-09-28/29)

Plans (session scratchpad): maxn/synthesis.md (legacy params, win proxy, shepherds, towers, scaling) and
archaeology/synthesis.md (1v1-era features, discarded ideas, hard-coded constants). Key findings:
- N=11 gains do not always carry to N=12: shepherd_time 120 gave N=11 +2-3 min survival and W 28 vs 19, but at N=12
  alive-at-40-min -2.5 pp and the first armory standing at 20 min -.045 (z -2.0). The N-step is survival from 25 to
  40 min (alive at 25 min -> alive at 40 min: 30 % at N=11, 13 % at N=12, 7 % at N=13); copies out at 25 min are the
  same in wins and losses. So adoptions need an N=12 check and survival ideas are screened at N=12.
- Baselines (current defaults): **N=11 28/400 (7.0 %), N=12 4/400 (1.0 %: s63, s64, s274, s399), N=13 1/200 (s26)**.
- lab/gauntlet/winproxy.py (committed): logistic P(win) from the 15/20/25-min census (copies out, homeless copies,
  strength ratios, buildings, iron); held-out AUC .95-.99 at N=11, paired SE ~.007 per 200 games (wins .014). Used to
  decide small margins only; N>=12 borrows N=11's intercept and it cannot see the 25-40-min window.
- The user approved engine timing exploits that a human with perfect information and tick-by-tick control could do
  (weapon_sync below).

Legacy screens at N=11 (paired with st120b2-vs11-hv, wp = winproxy): shepherd_hold wp -1.1 pp (z -1.0), W 9 vs 10;
attack_min_strength=22 wp -2.0 pp (z -2.0); base_radius=22 wp -0.5, W 7 vs 12; armory_delay_weight=0.8 elim -.035
(z -2.7); attack_min_strength=14 -.024; hold_mid=6 +.003; hold_late=4 -.022. None adopted.

Merged (all default off, the default build reproduces the reference checksums on s2-s5): freeze opening (Freeze.java:
freeze_open, freeze_squad, freeze_eta, freeze_raze; N=12 smoke: target out at 73 s and 99 s in 2 of 3 seeds, our
first armory +64-102 s later), Military legacy params (worn_basis/window/ratio, enemy_stun_mult, enemy_spell_recharge,
enemy_first_seen, retreat_split_guard, parked_scan_econ/threat, hold_closing, hold_multi), Economy/Shepherd items
(tower_face_live/place, shepherd_lead, tower_home_anchor, tower_q_anchor, front_order, tower_min_quarters, shepherd
and chicken diagnostics), weapon_sync (align rubber/iron/rock axe completions on one tick so they share iron/rock:
1-3 verified duplications per game when chickens are available; weapon_sync_three), and EnemyIndex (towers look up
nearby enemies instead of every enemy; same choices, less CPU; suggested by the headless-engine session's profile).
Observed: we gather few chickens (1 in 24 min in one smoke game while each Hard got 0-16): pickChicken skips chickens
with enemies near.
