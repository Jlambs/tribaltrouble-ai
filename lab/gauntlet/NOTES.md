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
- `lab/gauntlet/board.py BASE PREFIX [--median]`: a sweep's scoreboard, every finished arm against one base
  (winproxy --pair per arm, cached in aisim/board_cache.json); `--median` reads each arm against the median arm that
  changes play, which takes the shared base's own draw out (see "Method: one base shared by many arms").
  `lab/gauntlet/pairs.sh "BASE ARM" ...`: one line per pair.
- Logged-game readers: `lab/gauntlet/musters.py RUN` (campaign target choices), `lab/gauntlet/cuts.py RUN` (freeze
  path-(c) cuts and how they ended); `lab/gauntlet/chiefs.py RUN...` (enemy chieftain gating, from game records).

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

### N=12 screens; the freeze opening; what kills us at 25-40 min (2026-09-29)

Engine: merged headless 2a79342a and b42a45be (headless harness games: ~40 % less CPU, a third of the memory, workers
up to 32 and --workers auto); games bit-identical (s2-s4 25-min checksums unchanged). N=13 base: cur2-vs13-hv(-b)
**2/400 (0.5 %: s26, s370)**. winproxy.py --pair now also prints survival (surv60, alive30, alive40, hold20) and the
window columns of late/spec.md S0 (hold20a1 = first armory standing at 20 min, base25, arm25, towers20, peons20,
vanished20); base rates at N=12: hold20a1 .30, arm25 .16, towers20 3.9, peons20 27, vanished20 ~105.

N=12 screens (paired with cur2-vs12-hv, seeds 1..200):

| arm | wins | elim (z) | wp (z) | surv60 (z) | alive40 (z) | verdict |
|---|---|---|---|---|---|---|
| **freeze_open** | **7 vs 2** (6/1) | **+.041 (3.3)** | +0.7 pp (1.1) | **+1.5 min (2.4)** | **+5.0 pp (2.4)** | confirming (fresh N=12, N=13, N=11, N=8) |
| armory_threat_cap=2 | 4 vs 2 | +.003 | +0.1 pp | +0.7 (1.7) | +0.5 pp | confirming on 201..400 |
| shepherd_lead=144 | 2 vs 2 | -.002 | +0.2 pp | +0.8 (1.2) | +3.0 pp (1.5) | maybe |
| recall_ratio=4 | 2 vs 2 | -.002 | 0 | -0.1 | 0 | no |
| shepherd_until=1200 | 1 vs 2 | -.004 | +0.1 | **-1.0 (-6.4)** | +0.5 | no |
| base_radius=22 | 0 vs 2 | -.015 | **-0.6 pp (-2.0)** | 0 | +0.5 | no |

The freeze opening puts the nearest copy out in about a minute (10 starting peons kill its quarters builders before
the quarters is finished; with no units, chieftain or finished quarters it collapses). Window columns: arm25 +7.5 pp
(z 2.1), towers20 +0.8 (z 2.3), peons20 +4.4 (z 2.1). Follow-ups being built: unfreeze a recovered copy, let the squad
fight while staging, push our armory after a strike, strike a second copy.

N=11 legacy screens since: front_tower_max=12 and front_tower_min=10,max=19 (see lane results), tower_mutual,
towers_early_time=120 and the rest are running.

What kills us between 25 and 40 min at N=12 (late/collapse.md, late/spec.md; 16 logged N=12 games alive at 40 min
plus the 400-game census): the 25-40-min step is mostly a 13-24-min collapse that shows up later (47 % of the N=12
games alive at 25 min have no armory or quarters left; N=11 28 %); three quarters of the N=11 -> N=12 drop in alive40
is the worse 20-min state (fewer towers, warriors, peons), one quarter is extra pressure at an equal state. Mechanisms:
the peon trap (idle peons bank in the primary armory, usually the exposed expansion; ~103 units vanish inside razed
buildings by 20 min), the expansion as a sinkhole (built at 8 min 106 cells out, razed at 14.5 min, ~80 units lost per
razing), tower attrition (6.8 towers razed and 0.8 completed from 15 to 20 min: a tower project vetoed by a nearby
threat is never re-sited, and only one is built at a time), iron starvation, reinforcements blocked by threat level 2,
and failed rebuilds (62 % never complete another armory after the first falls). Half the armory falls happen with fewer
than 10 field warriors alive, so a recall would not have saved them. Being built: veto_resite (move or drop a vetoed
tower project), bank_guard (cap the armory's idle bank; the rest wait in the safest quarters), wood_reach (the endgame
wood lock of the 360-min draws).

### cur3: veto_resite and the freeze opening adopted; long games; jams (2026-09-29 night)

Adopted (commit b4f1a27f; the default build equals the explicit arms, same checksums at N=11 and N=12):
- **veto_resite=20** for all N: survival up at every N (surv60 +1.9 / +1.0 / +0.8 min at N=11 / 12 / 13, z 4.4 / 2.2 /
  3.0; towers at 20 min +1.3 to +1.8, z 6-8); wins N=11 15 -> 19 (1..200), N=12 2 -> 5 and 2 -> 1, N=13 1 -> 1.
- **freeze_open from N=12** (Strategy.forGame, enemies >= 12): N=12 W 2 -> 7, 2 -> 2, 0 -> 4 on seeds 1..200 / 201..400 /
  401..600 (elim +.041 / +.021 / +.033, z 3.3 / 2.2 / 3.4); N=13 W 1 -> 2, 1 -> 2 (elim +.025 / +.020); N=11 W 15 -> 11,
  13 -> 12 (wp -1.2 pp, z -1.4): not at N=11. With veto_resite on top at N=12: W 2 -> 6, elim +.042, alive40 +7.5 pp
  (z 3.4); N=13: W 1 -> 2, elim +.028 (z 2.9). Kept under test (the user: no local-minimum bait either way): every
  re-base keeps a freeze-off arm at N=12/13; cheaper variants queued (squad 6/8, eta 30, fight + unfreeze).
- Not adopted: freeze_squad=14 (worse), freeze_armory_push (armory 80 s earlier but W 3 vs 7 against plain freeze),
  armory_threat_cap=2 (N=12 fresh neutral), bank_guard (nothing on top of veto_resite), stall_peons (converts s98 draw ->
  win at 89 min but flips s264 the other way; fires rarely), weapon_sync (2.8 duplications per game, W 15 vs 14),
  shepherd_until=1200 (surv60 -1.0, z -6.4), recall_ratio=4, base_radius=22 (wp z -2.0), shepherd_lead (neutral),
  N=11 legacy: front towers in/out, tower_mutual, towers_early_time=120, shepherd_ring26, shepherd patience 60 +
  site_shepherd, retreat_ratio=2.0 (all neutral or negative).

Long games (the user asked what the tails are): at N=12-13 the longest games are mostly wins (N=12 > 90 min: 11 wins,
5 losses, 1 draw in 1,600 games; N=13: 2 wins, 1 loss in 800); the 360-min draws are an N=11 thing (7 in 1,000). A
lower time cap would cut wins; the tails are two pathologies: the **wood lock** (trees only searched within 60 cells:
Economy.pickSupply `radius = type == TreeSupply.class ? 60 : 200`; the gatherer loop breaks at the first failed send so
workers are never released; the primary armory never releases workers at weapon stock 0; 150-210 peons idle in the
armory with no army for 20-200 min; the 3-hour N=12 wins all had a 33-57-min lock) -> wood_reach (built, screening);
and the **stuck army** (s98: a column wedged at a choke, 30-46 warriors blocked at one cell for 5 hours, and peon fights
reset the stall clock) -> stall_peons (converts s98) and an unjam fix in progress (s97 in the weapon-sync run: the stall
fired 236 times and the army still never moved, so the choke jam itself needs fixing). Once <= 3 copies remain,
conversion is fast (median 9.5 min) and no game was ever lost from there.

Diagnostics: Jams.java (counters peon_blocked, warrior_blocked, peon_jam, warrior_jam; log "jam: N ... blocked around
x,y"; no decisions) and lab/gauntlet/quirks.py (per-run pathology signatures: wood_lock, stuck_army, peon_trap,
tower_decay, dry_spell, homeless_alive, stall_churn, draw_at_cap, warrior_jam, peon_jam). Seeds with repeated peon jams
across configs (s9, s93, s192) point to map geography; logged replays queued. Silly behaviour found: at the unit cap the
attack gate's capped clause launches attacks with army 0.0 vs defense 0.0 (wood locks); guard param in the hygiene
branch. N=12/13 logged games (camp-cur-vs12/13-hv): games end at 16-37 min; peon banks of 100-236 inside the armory
vanish with it (230 units at once in N=13 s38); towers razed 7.4-8.8 vs completed 0.7 per game at 15-25 min (before
veto_resite). An N=12/13 collapse audit is running; unjam and behaviour-preserving hygiene are being built in worktrees.
Harness: pool.sh (K auto-sized batches from a job file) replaced the lanes.
- **cur3 levels (400 seeds each):** N=11 31/400 (7.75 %), N=12 8/400 (2.0 %), N=13 3/400 (0.75 %). Against cur2 on the
  fresh halves: survival up (N=12 surv60 +2.0 min z 2.9; N=13 +2.0 z 3.6, arm25 +10.5 pp z 3.8), wins level.
- **First N=14 win: cur3-vs14-hv s169-0 at 260:45, all 14 copies out; replay VERIFIED** (checksum -1932835330; replay
  logs in aisim/runs/cur3-vs14-hv/replay/). N=14 1/200. Even this win had a stuck army: 218 warriors jammed at 422,202
  from 110 to 246 min (warrior jams 57/min, 106 stall retargets): the unjam fix matters at high N.
- On cur3: stall_peons at N=12 W 6 -> 6 (fires rarely); stall_peons + wood_reach at N=13 W 2 -> 2; wood_reach=150 at N=11
  W 19 -> 20 (fires rarely); at N=12 5 games changed (2 wins faster, 1 loss -> win, 1 long win -> loss): wood_reach=110
  queued. live facing (tower_face_live + tower_face_place) at N=11 W 19 -> 11: no.

### cur4: freeze squad 6; frozen_last; two strikes (2026-09-29 day)

The N=13 collapse audit (4 lenses + verifiers + judge, scratchpad audit13) found the freeze is not bait: on the cur3
stack it beats freeze-off on every block (N=12 s1..200 elim +.030 z 2.0, s201..400 +.026 z 2.6; N=13 +.034 z 3.7),
and most of its opening price is the size of the squad. It also warned that aborting path (c) (proposed by three lenses
on squad-10 data) would have hidden that path (c) is the best class at squad 6.

- **freeze_squad 6 adopted (commit 6d706c11).** N=12 s1..200 W 6 -> 9, surv60 +1.25 min (z 1.9); s201..400 W 2 -> 6,
  surv60 +1.38 (z 1.9), alive40 +5 pp (z 2.2); pooled over 400 W 8 -> 15, surv60 +1.3 (z ~2.7), wp z ~1.7. N=13 s1..200
  W 2 -> 0, surv60 +0.77 (z 1.3), towers20 +0.8 (z 2.5), wp -0.3 pp (z -1.1): N=13 s201..400 and N=14 decide whether it
  needs an N gate. squad 4: W 9 -> 2 against squad 6 (elim z -3.5; strikes fail); squad 8 W 6 -> 7; squad 14 W 7 -> 1.
  At squad 6 more strikes end in path (c): 70 of 400 N=12 games freeze a copy (39 at squad 10).
- **frozen_last** (commit cde8c7e4, off): the attack target's choice leaves frozen copies until no other copy is a
  candidate (a frozen copy never launches; it scored as the easiest target and took the first attack). Games without a
  frozen copy are bit-identical, so it is screened only on the seeds where the base froze a copy.
- **Freeze runs several strikes** (commit ceaf6d17; freeze_targets, freeze_squad2, freeze_eta2, freeze_keep; one strike
  reproduces the reference checksums). Geometry: the second-nearest copy is within 200 m (straight line) in 86 / 91 / 93 %
  of N=12 / 13 / 14 games (the third is at 250-330 m). Smoke (N=12 s1..8, two strikes of 6, eta 40 s): 4 of 8 launched
  a second strike: 2 outs (s7), out + frozen (s2), out + failed strike (s3, s4). Screens queued at N=12 (eta2 40 and 50)
  and N=14.
- N=11 code arms on cur3 (vs veto-resite-vs11-hv): weapon_sync W 19 -> 20 (wp z -0.3), tower_min_quarters=1 W 19 -> 18
  (towers20 z 2.6, wp z 1.6: fresh half queued), worn_basis=1 W 19 -> 18 (neutral), enemy_stun_mult=1.0 W 19 -> 11 (wp z
  -2.4: the default stays), parked_scan_econ=10 W 19 -> 7 (wp z -2.3). freeze_fight + freeze_unfreeze at N=12 (squad
  10) W 6 -> 6, surv60 +0.35 (z 1.9): re-run on cur4.
- Levels on cur3: N=14 1/600 (s169), N=15 0/200; median game 21 min at N=14, 20 min at N=15. quirks.py at N=14: 4-8.5 %
  of games show a pathology (peon trap, peon jams, tower decay); the typical loss is the plain collapse at ~21 min with
  1-2 copies out, so the lever at N=14 is removing more copies early.

### Army jams at a choke: what wedges them, and unjam (2026-09-29)

Question (late/endgame.md 2.1, spec S6): why does the attack army sit for hours at a choke (s98 at N=11 from 47.5 min
at 178,53; s97 with `weapon_sync=true,veto_resite=0` from 60 min at 410-424 x 258-266, the stall rule firing 236
times)? Hypotheses were a column jam behind a pass, or a stale target field.

Tool: jam pictures (Military.describeJam, log only). At a warrior jam of 12 or more (Jams), at most every 150 s, the
log gets a 65 x 45-cell picture around it: terrain (#), buildings (B), trees and supplies (T), our attack units
(b blocked walking, w walking, i idle, f other), the march waypoint (W), and a head line comparing the target field
with one computed at that moment. `grep jampic` in any logged game.

What wedges the army (logged reproductions jam-s97-base, jam-s98-base at the gauntlet HEAD, and their pictures):
- **Not a stale field.** In 22 pictures (s97, s98 and four screen replays) the stored field and a fresh one agree at
  the jam and at the waypoint. setTarget's field computed once is fine.
- **Engine: idle and blocked units are walls.** IdleBehaviour.isBlocking() is true, and a WalkBehaviour in state
  BLOCKED makes Unit.getPenalty() return Occupant.STATIC, so the grid pathfinder (GridNode.addNeighbour) routes around
  them like around a tree. PathTracker's deadlock solver only frees cycles of walking units, not a queue that ends at
  an idle one. MapAnalysis fields count units as passable, so no field sees this.
- **The plug.** The march waypoint (a lead, ~60-67 m, past the pivot) lies inside (s97) or at the exit (s98) of a 1-3
  cell pass, and setLandscapeTarget spreads the group over cells around it. The first units through reach their cells
  at the exit and go idle: a wall across the pass. Everyone behind turns BLOCKED (more walls). The pivot, a third back
  from the front, is inside the queue, so the waypoint never moves on; and the idle plug is never re-ordered
  (attackGround skips an idle unit within 4 cells of the same spot, and the pivot hold skips units 24+ m ahead).
  - s97 at 3755 s: 102 of 150 attack units blocked in the corridor at 418-424 x 245-265 (3-6 cells wide with trees),
    3 idle at its north exit, waypoint 428,236 just past it.
  - s98 at 2795 s: 54 blocked in the 2-cell pass along the map's north edge (164-175 x 46-47), 5 idle at its exit
    around the waypoint 178,48. The base game stalls there 5 times (1345, 2795, 3740, 4820, 5920 s).
  - The stall rule (75 calm s) then bans the target and retargets, often to one behind the same pass (s97: 47 stalls in
    120 min, alternating fields from 459,154 and 352,142).

**unjam** (default 0 = off; arm `unjam=8`): Jams hands every scan's blocked warriors to Military.noteBlocked. With at
least `unjam` attack units blocked on every scan for `unjam_after` (15) s, no enemy warrior, chieftain or tower within
30 cells of them, and the pivot less than `unjam_progress` (10) m closer to the target over that window, the attack
marches as a column until `unjam_time` (30) s after the last jammed scan: no pivot hold, and each unit is ordered to
its own point a lead further along the target field (the front no farther than two leads past the pivot).
`unjam_from` (s) delays it, for replaying a jammed game unchanged up to its jam. Counters: unjam_column (starts),
unjam_through (the pivot 30+ m closer to the same target at the end, or the target fell), unjam_still, unjam_retarget,
unjam_ended (the attack ended during a column), unjam_moving (a jam window vetoed because the army still moved). Log:
"unjam: N of M attack units blocked around x,y, pivot d m ...: column march" and "unjam: column march over (outcome)".

How the trigger got its guards (first version: 8 blocked on two scans, nothing else): paired N=11 1..40 (60 min) had 6
firings, 4 of them in a crowded march that was still moving (s13, s16, s31) or in a melee (s48). The column strung the
army out and it met the enemy piecemeal: s31 had 39 of 147 units left at 1000 s against 102 in the base game. Hence the
progress test and the enemy-fighter test (enemy peons do not count: the stuck s98 army cut down gatherers all the
time), and the cap on the column's front (uj3-s98: 45 of 75 units far from the centre after a column, 20 lost to a
tower).

Results (snapshot of the final code; reference checksums re-recorded first from the unchanged HEAD 66cf27d5: N=11
25 min s2 571072128, s3 1709397488, s4 1142615447; N=12 20 min s3 -1648123377; the flags arm at N=11 20 min s3
-1478054063):
- Default: all five references reproduced. `unjam=8` on the same five games: identical (it never fires).
- s97 (`weapon_sync=true,veto_resite=0,unjam=8`, from the start or with unjam_from=3500: the same game, identical to the
  base until 3640 s): column at 3640 s (77 of 150 blocked, pivot 248 m for 15 s); 30 s later the pivot is at 165 m and
  the army at 431,235, past the corridor; the target at 459,154 falls at ~3715 s and the game is **won at 3814 s**. Base:
  a 120-min draw with ~210 warriors stuck in the corridor.
- s98 with unjam_from=2700 (identical to the base until 2805 s): column at 2805 s (55 of 69 blocked, pivot 200 m); 35 s
  later the pivot is at 88 m, s7's quarters at 252,56 falls at ~2910 s; a second column at 2985 s (the way back west
  through the same pass) also gets through; **won at 4416 s** (base: won at 6408 s after 5 stalls at the pass).
- s98 with unjam from the start: column at 1360 s (64 of 85 blocked), through (206 -> 46 m); **won at 3505 s**.
- Paired screens against the default (identical games where unjam never fired, and only there):

| screen | games | fired | identical | outcome changes |
|---|---|---|---|---|
| N=11, 1..120, 60 min | 120 | 4 (s22, s91, s97, s98) | 116 | s22 and s98 draw -> win (W 3 vs 1); s91 (column 5 s before the end), s97 draws |
| N=12, 1..80, 45 min | 80 | 2 (s16, s63) | 78 | none (s16 out 5 s earlier) |

So the jam is rare (about 1 game in 30 at N=11 and 40 at N=12 within these limits) and unjam leaves every other game
alone. Not measured: longer games (the s98-type draws run to 360 min), and adoption. Next: an N=11 1..400 360-min pair
(or the draw seeds of all 65 s98 configs) before making unjam=8 the default. Not done: jams of reinforcements or of a
retreat through the same pass (only the attack role is watched).

### cur4 screens: frozen_last and two strikes do not pay; hygiene and unjam merged (2026-09-29 day)

- **frozen_last: no.** On the seeds where cur4 froze a copy (N=12: 70 of 400, N=13: 25 of 400; all other games are
  bit-identical): N=12 W 4 -> 2, elim -.043 (z -1.5), wp -1.4 pp (z -1.4); N=13 elim -.034 (z -2.0), surv60 -1.0.
  The audit's reading (the frozen copy takes the first attack for nothing) was wrong in sign: that first attack is a
  quick, undefended out next to home, and skipping it sends the army farther.
- **Two strikes (freeze_targets=2): no.** The second strike launches and converts well (N=14: 117 second strikes in
  200 games, outs 141 -> 217, frozen 24 -> 41), but final eliminations barely move: N=14 elim +.007 (z 1.2), surv60
  +0.03; N=12 W 9 -> 9, elim -.011 (z -1.4); eta2 50 N=12 W 9 -> 6, elim -.034 (z -3.0); eta2 30 N=14 elim +.003. The
  first strike's value sits in a very near neighbour (audit: starts within 60-80 cells); the second copy is usually
  75-100 cells out, a copy the campaign takes around 9 min anyway, and the 12 peons away cost later outs.
- Squad size, the rest: squad 5 = squad 6 at N=12 (W 10 vs 9, all columns ~0); squad 6 vs 10 at N=13 s201..400 W 1 -> 2,
  elim +.017 (z 1.8), surv60 +0.3 (pooled N=13 400: W 3 -> 2, surv60 +0.5, elim +.010, wp -0.4 pp); at N=14 s1..200
  neutral (W 1 -> 0, elim +.002, surv60 +0.2). Freeze off at N=14 (squad 10 base): elim -.021 (z -2.7), surv60 -0.75
  (z -1.9), towers20 -0.9 (z -2.8): the freeze pays at N=14 too.
- **Merged**: the behaviour-preserving hygiene branch (15 commits: shared helpers, Intel.isParked/gunner, EnemyIndex
  shared with the shepherds and updated in place, computeField buffer reuse, dead code, the N>1 gate of forGame
  removed, capped_min_strength param at 0) and unjam (default off). The merged build reproduces cur4's N=12 checksums
  (s1, s5, s9, s120, frozen games included) and N=11 (s1-s3).
- Enemy chieftains (census, lab/gauntlet/chiefs.py): chieftain-gated copies (the Hard launches only
  with an active chieftain once its wave size reaches 20, AdvancedAI:118-119, 305-306) are without one 19-21 % of
  the time by 20 min (16-19 births, 9.5 deaths per game). Terciles by that share: N=14 games last 19.4 / 21.6 / 24.1
  min (correlation only). A chieftain has 60 HP against a warrior's 1, so killing one costs ~30 iron hits; training a
  new one takes a copy 40 breeding ticks (~160 s with 20 peons inside), during which it breeds no peons.
- **Late iron is a global respawn trickle** (engine SupplyManager): once more than 75 % of a supply type's nodes are
  empty, one random empty node anywhere on the map regrows every 10 s (an iron node holds 10), skipped that cycle if a
  unit stands on its cell. That is ~60 iron/min map-wide; census at N=14 (freeze-squad6-c3-vs14-hv, 142 games alive
  at 20 min): 43.9 iron/min harvested by all players at 15-20 min, a mining copy 4.1/min (about one field's share
  each), we 1.8/min (4 %). So late production is set by how many iron fields a player works; the copies' combined
  ~50 iron/min is what the collapse window is made of. Unexplored: working the fields of copies that are out (their
  respawns go unclaimed), and denial (a unit standing on an empty node's cell blocks that respawn).
- **Campaign targets at N=14** (logged logs-cur4-vs14, seeds 1001-1010): the first muster (5.5-7 min, 18-22
  strength) goes to the nearest living copy in 8 of 10 games; in two it skipped a near copy for its defense and
  marched to a far one (s1006: rank 2 at 145 cells, worn 57 -> 11 with no out; s1007: rank 5 at 203 cells, 124
  strength poured in over 12 min). Typical campaign: 1-3 outs of near neighbours at 7.5-10 min, then far targets
  and worn down by 15-18 min while the base, with 10-15 towers, falls at 20-27 min. Target-weight arms
  (target_home_weight 0.3 / 1 / 2.5, target_defense_weight 2 / 4 / 14) are in the N=13 sweep.
- **unjam=8 screens: neutral, kept off.** N=11 s1..400 (360 min): fires in 12 games, the other 388 are bit-identical;
  W 31 -> 31: s203 draw -> win at 75 min, s98 107 -> 58 min, s22 68 -> 54 min, but s97 (a 316-min win) -> loss at 262
  min. N=12 s1..400: fires in 12, W 15 -> 15 (s63 61 -> 57 min). N=14 s1..200: never fires. It speeds up long wins and
  fixes the s98-type wedge; it has not shown more wins. Harness note: a 400-game batch with 360-min games ran the
  parent JVM (-Xmx768m) out of heap in the summary step (Curves) after every game had finished, so no summary.txt;
  results.jsonl was complete (stub summaries written by hand so the pool moves on).
- **More cur4 screens (all off / not adopted):** capped_clump=0.5 (fires in 60-70 % of games) N=12 W 9 -> 10, elim 0, wp
  -0.4 pp; N=13 elim -.007: neutral. chief_wake_retreat 6 s / 12 cells: our chieftain still dies ~1.05 times a game
  (0.85 of them in the wake window), N=13 elim +.005, wp +0.2 pp (z 1.2), N=12 W 9 -> 8: neutral; 10 s / 14 cells
  the same. freeze_fight + unfreeze on cur4 N=12: W 9 -> 7, elim -.010 (z -1.4). ring_sweep (built, commit 51ae511b):
  smoke shows it acting mostly as an early recall (the base sits at threat level 2, so defend() has the army);
  screen queued.
- **Freeze at other N with squad 6:** N=8 s201..400 W 111 -> 115, elim +.016 (z 1.8), wp +1.4 pp (z 1.5); N=11
  s1..200 W 19 -> 15, surv60 +1.3 (z 1.3), elim +.015, wp +0.1 pp. N=11 fresh half queued to decide whether the
  enemies >= 12 gate can go. tower_min_quarters=1 at N=11 on 201..400: surv60 +0.4 (z 2.4), W 12 -> 11, elim -.006
  (pooled 400: surv60 +0.3, W 31 -> 29, elim -.004 z -1.8): survival up, outs not; N=12 arm queued.
- **Snipe towers: no** (branch worktree-agent-a53df44682a25bdbf, commits 2cfe6bac, bdd051b4, ff10f3aa; not merged).
  A garrisoned tower 12-14 cells from an idle parked blob (outside its 8-cell scan, which `Unit.hit` does not wake)
  shoots it for free, parked chieftains first. Built with site choice clear of every enemy unit, builders carrying wood
  from the armory, gunner lending, and a cage rule so our defense does not wake the blob. Mechanism too slow: blobs park
  1-3 min, a tower takes ~90 s from plan to garrison; 2.6 parked kills per game (N=12 s1..12), 0.6 towers finished and
  manned per game. Screen N=12 s1..200: W 9 -> 5, elim -.018 (z -1.9), wp -0.75 pp (z -1.9). The old `snipers` rule was
  the same idea and had also been worse.
- **Why the campaign sometimes marches far** (muster candidate log, commit of this entry): scores are walking distance
  from the staging point (which sits toward the map centre, not at our start) + priority + 8 m per unit of the target's
  defense. In s1007 (N=14) four candidates tied within 8 points: s2's armory 140 m away with defense 36 against s13's
  352 m away with defense 9; a small first army then takes the weakest far copy.
- **N=12 cheap arms on cur4 (s1..200 vs freeze-squad6-c3-vs12-hv):** chief_safe=10 (our chieftain keeps 10 cells from
  awake enemy warriors while his stun recharges; never screened before) W 9 -> 11, surv60 +0.9 (z 1.7), alive40 +4.5
  pp (z 2.2), wp +0.9 pp (z 1.3): confirming (N=12 s201..400, N=13, safe 7 and 14). tower_home_anchor=true with
  tower_q_anchor=false W 9 -> 1, surv60 -3.0 (z -3.8), towers20 -2.4 (z -6.6): no. recall_old_gatherers=false W 9 -> 5,
  surv60 +1.2 (z 1.9), wp -0.7 pp: no. tower_min_quarters=1 W 9 -> 9, all ~0: no.

### cur5: the freeze at every N (2026-09-29 morning)

- **Adopted: freeze_open for all N** (Strategy.forGame; the enemies >= 12 gate removed). With squad 6: N=11 s201..400 W
  12 -> 20, elim +.049 (z 2.8), surv60 +3.3 min (z 3.4), alive40 +9.5 pp (z 2.6), wp +5.5 pp (z 4.0); with s1..200 (W 19
  -> 15, surv60 +1.3) pooled over 400: W 31 -> 35, elim +.032, surv60 +2.3. N=8 s201..400 W 111 -> 115, elim +.016 (z
  1.8). freeze_eta (40 s of peon walk) keeps it off where copies start far away. The default reproduces
  freeze-squad6-c3-vs11-hv (N=11 s1-s3) and cur4 at N=12. The user's no-N-gate preference, now backed by the data:
  the N=11 exception was squad 10's cost, not the freeze's.
- ring_sweep=true at N=13: elim -.033 (z -7.2), surv60 -0.9 (z -2.8), wp z -2.9: no (an early recall forfeits outs,
  as the audit warned).
- chief_safe=10 on N=12 s201..400: W 6 -> 7, surv60 -0.4, alive40 -2.5 pp, wp 0 (pooled 400: W 15 -> 18, surv60 +0.2,
  wp +0.45 pp): not confirmed yet; N=13 and safe 7/14 pending.
- **Why path-(c) cuts fail** (40 logged N=12 cur5 games on seeds with a fallback, 6-min runs logs-pathc-vs12; script
  cuts.py in the session): the armory site stands 8-10 cells from the copy's quarters in 37 of 40 (inside its 15-cell
  defense circle; a site is not an armory to AdvancedAI, so only the quarters counts). The outcome follows the copy's
  peons outside when the cut starts: 9 or fewer -> 24 of 25 froze; 10 or more -> 5 of 15 froze, 10 failed (the copy's
  defense sends its idle peons and gatherers at our 6). An abort at >= 10 outside would trade ~10 failures for ~5 lost
  frozen copies: about even, not built. chief_safe=10 at N=13: neutral (W 0 -> 1, elim -.004, towers20 -0.5 z -2.2).
- **A mid-game armory freeze would be small.** AdvancedAI clears its "under construction" flags only on seeing a
  finished building of that type or when it has no site and no placing peon, so a copy whose replacement armory site
  stands without builders would never forge again (path c's trick, later in the game; a quarters site is not stable:
  idle peons are sent to finish it). But in 200 N=13 games (freeze-squad6-c3-vs13-hv census) 532 first armories of
  copies were razed, 303 of those copies went out within 2 min, 91 re-placed an armory and 48 finished it (mean 5.8
  min after the razing): 0.24 rebuilt armories per game, ~10 enemy warriors of production. Not built.
- ore_scan (refresh the iron/rock lists from the start's ore cells every 5 s instead of the 30-s full scan; tried and
  reverted, not committed): N=13 s1..10 iron harvested at 10/15/20 min identical in 8 of 10 games, results identical
  in 8. Respawned nodes near us are found anyway (or are too few to matter); the 30-s scan is not what starves us.
- chief_safe on cur4 N=12 s1..200: 7 W 9 -> 6, surv60 +1.3 (z 2.4); 10 W 9 -> 11, surv60 +0.9; 14 W 9 -> 4, elim -.023
  (z -2.2); 10 on s201..400 and at N=13 neutral: no consistent gain, not adopted. quarters_first=true at N=13: surv60
  -0.7 (z -1.3), towers20 -0.5 (z -1.7); one game (s158) became a 360-min draw: no.
- **Peon jams (the user's question): builders wedged in dead-end notches.** jam-logs s9 (N=11): 6-23 peons "walking"
  on the same cells at 187,114 from 520 s for 13 min. The jam log now names each jammed peon's job and site: all but
  one are repairers/builders of towers 24-31 cells south (one tower already complete, only damaged), tree gatherers
  that were picked as the nearest crew; the jam picture shows them in a 1-2-cell dead-end notch of the cliff where
  they had been cutting a tree, deadlocked against each other at its mouth (the engine's deadlock solver only frees
  cycles of walking units). A stuck crew still counts as the building's crew, so nobody replaces it, and the peons
  are lost to the economy for the game. Built: unstick_builders (a builder on the same cell for N s more than 3 cells
  from its building goes into the armory; s9 frees them at 540 s and the jam never returns) and walk_select (crews
  taken from gatherers by walking distance: s93 peon jams 41 -> 0, s9 unchanged). Rare at high N (peon jams at N=14:
  median 0, p90 0.05 per minute); screens at N=11 and N=13 queued.
- Freeze reach with squad 6 at N=11: freeze_eta=50 W 15 -> 13, wp -0.9 pp (z -1.8); freeze_eta=35 W 15 -> 21, elim
  +.011, wp +0.85 pp (z 0.9) with strikes in 55 games instead of 164; N=12/13 and the fresh N=11 half queued.
  reinforce=false and reinforce_intercept are in the N=13 sweep.
- freeze_eta stays 40: eta 35 at N=12 W 9 -> 8, surv60 -1.1 (z -1.9), towers20 -0.5 (z -2.0); at N=13 elim -.006 (z
  -2.1); eta 50 at N=12 all ~0. The N=11 eta-35 gain does not carry to N>=12, and one rule for every N is the goal.
- N=13 sweep, first arms (vs freeze-squad6-c3-vs13-hv): reinforce=false elim -.058 (z -10.0), surv60 -1.9 (z -3.9):
  reinforcing the campaign is essential; reinforce_intercept towers20 -0.7 (z -2.3), surv60 -0.8; target_defense_weight
  2 and quarters_first neutral.
- **Shepherd efficiency: execution fixed, outcomes unchanged** (merged 62c7d2a5, all params off; lab tool
  shepherd_audit.py prints the mechanism per run). 40 logged N=13 games: shepherds stand on their spot 3 % of tends
  (walk 47 %, flee 22 %, no spot 22 %); 90 % of their deaths happen while fleeing (waves aimed at them, hunters), at a
  net 1.1-1.4 cells/s. shepherd_sticky (+ grace, travel cost) cut A->B->A flips 137 -> 46 per game; safe_walk +
  sticky raised arrivals 19 % and time on spot 3 % -> 5.8 %, drawn waves +20 %: over 400 N=13 games elim and survival
  do not move (sticky+travel elim +.010 / -.004, wp +0.4 / +0.25 pp). shepherd_home_pair=200 cuts the base share of
  waves .279 -> .228 but costs ~30 more peons a game and elim fell on both halves (-.009, -.020 z -2.6): no.
  shepherd_gap=60 null at N=13, elim -.018 at N=12: no. shepherd_range=200 (no shepherds for far copies): elim -.070
  (z -8.2), surv60 -3.6 min (z -6.0): far copies' shepherds are essential although most die on the way. The lever, if
  any, is how shepherds survive a flee, not where they stand.
- unstick_builders=30 fires in ~60 % of games (N=11: 460 unsticks in 121 of 200 games; N=13: 270 in 114), so wedged
  builders are common, but outcomes do not move: N=11 s1..200 W 15 -> 19, elim +.023 (z 1.8); s201..400 W 20 -> 17, wp
  -1.8 pp (z -2.5); pooled W 35 -> 36; N=13 W 0 -> 1, all ~0. With walk_select=300: N=11 W 15 -> 18, elim +.016 (z 1.1);
  N=13 ~0. Kept off (a correct fix of a silly behaviour, but no measurable gain).
- **Long games at N=11 carry the grind pathologies** (quirks.py on cur5 N=11, 400 games): 50-61 of 200 games last over
  40 min but only 15-20 are won; among the long games tower decay 52-61 %, dry spells ~60 %, peon trap 30-41 %, wood
  lock 13-18 %. On the 7 wood-locked games of veto-resite-vs11-hv, wood_reach=150 turned s106, s188, s177 from losses
  into wins, s97 (a 316-min win, fragile under every change) into a loss, and won s172 in 81 min instead of 154. Since
  the rare wins at N=13-14 are long games (s169: 261 min), a bundle of the long-game fixes (wood_reach=150, unjam=8,
  stall_peons, unstick_builders=30) is queued at N=11 and N=12 over 400 seeds each, judged on wins.
- **Long-game bundle (wood_reach=150, unjam=8, stall_peons, unstick_builders=30): not adopted.** N=11 W 15 -> 22 (elim
  +.029, z 2.3) and 20 -> 22 (wp -1.8 pp, z -2.5): pooled +9 of 400 (21 flips to wins, 11 to losses); N=12 W 9 -> 8
  and 6 -> 6 (pooled -1), and it lost two long N=12 wins (s169 220 min, s160 283 min), the kind it was meant to keep.
  Every such change flips 5-9 % of games both ways (many of them decided before 30 min), so a few-win net over 400 is
  within the churn.
- **N=13 legacy sweep, 16 arms in** (cur5, s1..200 vs freeze-squad6-c3-vs13-hv; base W 0): reinforce_ratio=0.8 W 0 -> 4,
  elim +.009 (z 1.1), wp +0.3 pp (z 1.3), towers20 -0.6 (z -2.1); target_home_weight=2.5 alive40 +3.5 pp (z 1.8), surv60
  +0.5, wp z 1.1; target_home_weight=1 elim +.008 (z 1.7); attack_min_strength=12 W 0 -> 1, towers20 -0.5; 26 surv60
  -0.7, towers20 -0.8 (z -2.4); retreat_ratio=1.2 elim z -1.9; reinforce_ratio=0.3 ~0. Bit-identical to the base (the
  param never binds at N=13): attack_max_strength 50 / 100, capped_ratio 0.4 / 0.9. Confirmations queued for
  reinforce_ratio 0.8 and target_home_weight 2.5 (fresh N=13 seeds, N=12, and both together).
- **Sweep confirmations failed** (fresh seeds): reinforce_ratio=0.8 N=13 s201..400 W 2 -> 1, elim -.014 (z -2.3); N=12
  W 9 -> 8, ~0. target_home_weight=2.5 N=13 s201..400 W 2 -> 1, ~0; N=12 W 9 -> 5, wp -0.8 pp (z -1.5). Both together
  at N=13 s1..200 ~0. The first-block "wins" (W 0 -> 4) were noise; at 0-2 base wins per 200, W alone decides nothing.
- **The tower targets never bind at high N.** Economy.planBuildings adds min(enemies - 1, front_tower_bonus_max=100) to
  towers_late (multi_front_towers), so the target is 26 at N=13; towers_late 11 or 16 and towers_mid 8 play bit-identical
  games. The number of towers is set by the 20-building cap (sites count) and by throughput (one tower project at a time,
  tower_parallel=1). Also bit-identical at N=13 (params that never bind there): attack_max_strength 50/100, capped_ratio
  0.4/0.9. tower_parallel 2/3 and a tower-pipeline audit (4 lenses + verifiers + judge) are running.
- logs-cur5-vs13 (24 logged N=13 games, seeds 2001-2024, current defaults): 2 wins, s2017 at 88 min and s2007 at 215 min.
- The two logged N=13 wins replay VERIFIED on snapshot cf55e1be15: logs-cur5-vs13 s2017 (88:22, checksum 1656383995)
  and s2007 (214:42, checksum -640923937).
- **Tower throughput is a lever** (N=13 s1..200): tower_parallel_late=2 (two tower projects at once from 600 s) alive40
  +4.5 pp (z 2.3), surv60 +0.48 (z 1.3), towers20 +0.31 (z 1.3), wp +0.3 pp (z 1.5); tower_parallel=2 from the start
  surv60 +0.42, wp +0.6 pp (z 1.7); tower_builders=12 no (elim -.012, towers20 -0.45). Confirmations queued (fresh N=13,
  N=12 both halves, N=14).
- **Method: one base shared by many arms carries its own draw into every comparison.** Any change re-rolls the games it
  touches (chaotic divergence), so the base's realized outcomes are one draw that all arms are compared with. Over the
  32 N=13 sweep arms that change play, the median difference against freeze-squad6-c3-vs13-hv is elim +.002, surv60
  -0.06, alive40 +0.75 pp, towers20 -0.31, wp +0.12 pp (~z 0.5): arms should be read against the median arm, and a
  first-block z of 1.5 is worth about 1.0 (which is why reinforce_ratio 0.8 and target_home_weight 2.5 failed to
  confirm). Against the median arm no legacy param stands out at N=13; chieftain_time=300 (W 0 -> 3, elim +.013, surv60
  +0.7) and defense_radius=40 (wp +0.7 pp) are the best, both within noise.
- tower_parallel_late=2 on N=12 s201..400: towers20 +0.53 (z 2.5), W 6 -> 5, wp -0.3 pp. Towers rise in every block
  (+0.31 / +0.34 / +0.57 / +0.53 / +0.82 at N=13 / 13 / 12 / 12 / 14), survival only at N=14 (surv60 +0.8, z 2.4), wins
  fall at N=12 (15 -> 10 of 400). A second N=14 block with its own base is running.

### Tower audit at N=13-14 (tower13: 4 lenses, adversarial verifiers, judge; scratchpad/tower13/judge.md)

- **Model:** manned towers in the 12-25-min window = towers standing x 0.94 (manning is not the gap: 94 % of tower-time
  is manned; wins 97-98 % with 12.6-13.2 towers standing, losses 93-94 % with ~8). Towers standing = the stock at 12 min
  (36-38 % of games are at the 20-building cap then) minus a net loss of ~1.3 razed against 0.43-0.55 completed per
  minute. Completions = open tower sites x share finishing / site time = 1.02 x 0.70 / 84 s: the real bound is two
  placed sites at a time (sites_parallel_late=2, shared with quarters), not tower_parallel; tower_parallel_late=2 worked
  through a loophole (a second placer on its way lets a third site go up).
- **The two construction slots at N=13, 10-25 min:** tower sites 48.8 %, building cap 10.8 %, no finished armory 10.2 %,
  site-search lock 9.2 %, quarters sites 7.8 %, placer walk 6.4 %, fewer than 2 quarters 3.5 %, one project at a time
  2.7 %, vetoes 0.4 %.
- **Site-search lock** (a misevaluation): after ~12 towers findTowerSite returns null for the one anchor tower_count
  picks, and planning retries it every 3 s until one of our towers is razed (23 of 24 locks end exactly at a razing),
  in about half the games; 31 % of the slots in the two wins' 15-25 min (s2007 planned no tower from 1045 to 1671 s).
  Built: tower_site_fallback (off; commit b61957a9; smoke fires in 9 of 12 games).
- **Treeless sites:** 53 % of window tower sites have no tree within 7 cells; they take a median 116 s against 45-69 s
  (same gap in the calm opening: walking for wood, not threats) and are razed as sites 48 % vs 33 %. Wood overall is not
  short (40/min harvested). Being built: tower_wood_drop (carry wood from the armory's transport-wood deploy).
- **Sites near a building razed in the last 3 min** are razed 54 % vs 26 % (only +0.10 after controlling for local
  fighting): tower_cooldown (existing) screened first, tower_hot_clear only if that is not negative.
- **Not levers:** the tower target (never binds in 1,628 capped samples), builders per site (at or above the wanted count
  86-92 %), manning/gunners (+0.35 towers at most), the building cap in the window (no wasted slot: the 4th quarters
  still breeds against losses of 28-33 units/min, the expansion brings +5 iron/min), moving expansion towers home.
  Wins and losses differ in razings (0.23 vs 1.21 per min), not completions (0.60 vs 0.48): the levers only slow the
  net loss.
- tower_parallel_late=2 at N=14 s201..400 (own cur5 base cur5-vs14-hv-b): towers20 +0.30, surv60 +0.05, alive40 -1 pp,
  W 0 -> 0: the s1..200 survival gain did not repeat. Not adopted alone; to be retried on top of the fallback and
  wood drop (the judge: the levers are not additive under the building cap).
- Tower audit, no-code arms (N=13 s1..200): tower_cooldown=true towers20 -0.65 (z -2.7), surv60 -0.6: no (so no
  tower_hot_clear, per the judge's gate). sites_parallel_late=3 alone towers20 +0.38, the rest ~0.
  **sites_parallel_late=3 + tower_parallel_late=2** (both construction limits raised): towers20 +0.75 (z 2.9), alive40
  +3.5 pp (z 2.1), surv60 +0.63 (z 1.5), wp +0.35 pp (z 1.5); against the median sweep arm still towers +1.05, alive40
  +2.8 pp. Confirmations queued (N=13 s201..400, N=14 both blocks, N=12).
- tower_site_fallback screens (fires in 145 / 154 of 200 games, 2.8-2.9 times a game, never finds nothing): N=13 s1..200
  towers20 +0.42 (z 1.6), alive40 +2.5 pp (z 1.5), wp +0.5 pp (z 1.6); N=12 s1..200 surv60 +1.1 (z 2.1), alive40 +4.5 pp
  (z 2.1), W 9 -> 4, elim -.013. A pattern across the tower arms: survival up, N=12 wins down (tower_parallel_late 15 ->
  10 of 400), elim slightly down; at 15 min the tower arms field 2-5 fewer warriors (for +0.4-0.5 towers).
- **slots32 = sites_parallel_late=3 + tower_parallel_late=2, confirmations:** towers20 +0.87 (z 3.4) / +1.06 (z 4.5) /
  +0.67 (z 2.4) / +1.16 (z 4.0) on N=13 s201..400 / N=14 s1..200 / N=14 s201..400 / N=12 s1..200; surv60 +0.43 / +0.61
  (z 1.9) / +0.37 / +1.29 (z 2.2); elim ~0 at N=13-14; W 2 -> 2, 0 -> 0, 0 -> 0, 9 -> 5. Survival rises in all five
  blocks (N=13 pooled 400: surv60 +0.53, alive40 +1.75 pp; N=14 pooled 400: surv60 +0.49). N=12 wins fall again (the
  third tower-adding change to do so). A win-count comparison on fresh seeds (N=13 and N=14, 600 per arm) and the fresh
  N=12 half are queued before adopting.
- tower_site_fallback N=12 s201..400: towers20 +0.55 (z 2.1), surv60 -0.2, W 6 -> 4 (pooled N=12 400: W 15 -> 8): not
  adopted alone.


### tower_wood_drop and site_towers_first built (off; commit 4ce4e277)

- **tower_wood_drop** (tower_wood_trees 0, _reach 40, _reserve 8, _max 20, _time 0), as the judge specified, with one
  change: the armory is the nearest one within reach that can spare wood now, not just the nearest (smoke-wood s2001:
  the nearest was an old armory the economy had emptied). Engine chain read and confirmed: `DeployContainer.orderSupply`
  takes the wood and the workers at the order, `createTransporters` gives each peon 1 piece (MAX_UNIT_RESOURCES 1), the
  armory has no rally point (only `evacuate` sets one) so they stand idle by it, and `RepairController` builds with a
  carried piece first (`RepairBehaviour`: 5 HP per piece), then pushes a `HarvestController` like any builder.
- **Smoke N=13 s2001-2012** (`smoke-wood2` against `logs-cur5-vs13`, `lab/gauntlet/tower_wood.py`): treeless sites
  placed at 10-25 min finish in a median 61 s instead of 127 s (31 of 41 finish vs 22 of 36); before 10 min 46 s
  instead of 89 s; sites with trees unchanged (46 s). A fully fed site finishes in 23-45 s. Per game tower_wood_units
  23-141 (mean 69), tower_wood_taken 45-158 (taken >= units: builders left holding wood are used too). Deploys are
  mostly limited by armory wood at the reserve (short_wood), then no armory within 40 cells. Quarters build no slower
  (median 112 -> 94 s). 40 % of deploys leave 5 or fewer workers inside (armories that had 5-10: the keep rule halves
  per deploy, not overall). w15 68 -> 54 (9 of 12 lower, noisy), W 1 -> 0 (s2007).
- **tower_wood_time=600** (`smoke-wood-t600`): 44 % of the wood left before 10 min, when the armory forges; with the
  window only, play before 600 s is identical, treeless window sites 127 -> 74 s (38 of 48 finish), w15 57.5, kd30 671
  vs 661. Screen both arms.
- **site_towers_first** (`smoke-sites`, no logs): site_towers_first_used 0/45/17/0/0/3/0/6/29/60/69/0 (economy ticks)
  in s2001-2012; the 5 games where it never fires play identically; W 1 (s2007), w15 69.3 vs 68.0.
- Default build: freeze-squad6-c3-vs13-hv s1-0..s3-0 and logs-cur5-vs13 s2017-0 checksums reproduced.
- Merged tower_wood_drop (+ tower_wood_trees/reach/reserve/max/time) and site_towers_first (both off; commit 10db0f79,
  reviewed: identity, determinism, engine safety and fair play OK; two review fixes applied). Smoke N=13 s2001-2012:
  treeless tower sites finish in a median 55 s instead of 105 s (600-1500 s: 61 vs 127 s), 58 -> 65 of ~80 finished;
  but the army at 15 min fell 74 -> 53 (median of 12; 57 with tower_wood_time=600), so screens carry an army guard.
- tower_wood_drop screens (N=13 s1..200 vs freeze-squad6-c3-vs13-hv): from the start elim -.028 (z -3.1), towers20
  -0.44: no (the early drain hurts the opening); from 600 s surv60 +0.64 (z 1.3), alive40 +3.5 pp (z 1.8), towers20
  +0.31, wp +0.4 pp (z 1.4), w15 57.5 -> 56.3; **from 600 s with reserve 20 (woodr20)** surv60 +0.52, alive40 +2.5 pp,
  towers20 +0.41, w15 57.5 -> 58.0 (25 carried pieces a game). site_towers_first: surv60 -0.3 (z -1.7), no. Queued:
  woodr20 confirmations and the tower package slots32 + woodr20 at N=12-14.
- **Second N=14 win:** cur5-vs14-w1 s3275 (default cur5), 151:44, replay VERIFIED (checksum 324272624).

### cur6: two tower projects and three sites after 10 min (2026-09-29 afternoon)

- **Adopted (commit 19e12bbb): tower_parallel_late 1 -> 2, sites_parallel_late 2 -> 3.** Found by the tower13 audit (the
  two placed-site slots, shared with quarters, bounded completions). Every block: towers20 +0.75 / +0.87 / +1.06 / +0.67
  / +1.16 / +1.12 (N=13 s1..200, s201..400, N=14 s1..200, s201..400, N=12 s1..200, s201..400) and on fresh seeds +1.28 /
  +0.68 / +0.82 / +0.95 (N=13 s3001..3300, s3301..3600, N=14 same); surv60 +0.3 to +1.3 in all 10 (z up to 2.9);
  arm25 +5.3 pp (z 3.0) on N=14 s3301..3600. Wins: fresh seeds N=13 0 -> 3 of 600, N=14 1 -> 2 of 600; all N=13-14
  blocks 8 vs 3; N=12 W 9 -> 5 and 6 -> 8 (15 -> 13 of 400). elim ~0 to +.014.
- The win-count comparison (cur5-vs1x-w1/w2 vs slots32-vs1x-w1/w2, 300 games each) also gave the default cur5 an N=14 win
  (s3275, above) and slots32 N=14 wins s3484, s3549 and N=13 wins s3077, s3287, s3521.
- **Why tower-adding changes cost warriors (towercost workflow: 3 hypotheses, checks, synthesis; scratchpad/towercost):**
  the channel is peon labour. An extra site takes its crew from idle and transit peons that would have entered the
  armory, and computeGatherTargets' pool leaves builders out, so forge workers and iron gatherers shrink pro rata: -1.35
  weapons per extra site placed (arm as its own instrument), -0.8 at 12 min, -1.6 at 15 min over 3,000 pairs; each extra
  tower also takes 0.7-0.8 warriors as its gunner. Wood is not the cost (builders chop their own) and neither is iron
  before 13 min (the forge is short of workers, not ore). It is repaid: pairs alive at 20 min have lost 2.7 fewer
  warriors (z -4.6). Outs, their timing and the army at each out do not change at any N. The "2-5 fewer warriors at 15
  min" was mostly the base's own draw (the base spikes at exactly 900 s against the non-tower arms).
- **The N=12 win loss is mostly the base's lucky block plus noise**: against 11 non-tower reference arms matched by
  seed block, about 10 of 14 lost wins may be tower-specific (z 1.25-1.4), all decided after 25 min, mostly fallback
  (-5.7) and twparl2 (-3.7); slots32 at N=12 (15 -> 13) is inside the reference range. Leads, unproven: the late slots
  never end (more tower placements after 25 min), wood lock 2.75 % vs 1.7 % (z ~2, post hoc). Seven of slots32's eight
  N=13-14 wins last over 90 min, so long-game conversion there is fine. The one variant (end the late slots at 1500 s,
  tower_parallel_late_end) expects ~0 and is not built.
- **N=13 sweep, the rest** (vs freeze-squad6-c3-vs13-hv; read against the median arm): bit-identical, never bind at N=13:
  expand_time 240/400, forward_towers 2/4 (plus attack_max_strength, capped_ratio, towers_late, towers_mid above). Best:
  decoys=true + decoy_free_slots=1 alive40 +5.5 pp (z 2.4), surv60 +0.7, W 0 -> 3 (decoys alone ~0: the free-slot rule
  kept them from being placed); chieftain_time=300 W 0 -> 3, elim +.013, surv60 +0.7. Negative: opening_near_start surv60
  -2.0 (z -3.5), elim -.023 (z -3.1); expansion=false surv60 -1.3 (z -3.0), towers20 -1.0; home_guard=8 elim -.018 (z
  -3.0); max_quarters=5 towers20 -1.3 (z -4.0); chieftain_time=180 surv60 -0.8; hold_mid=12 towers20 -0.9. Neutral:
  sites_parallel=3 (early), hold_early=6, hold_late=12, recall_old_gatherers=false, worn_ratio 0.1/0.35,
  max_armory_distance 200/350, defense_radius 40/90, base_radius 34, shepherd_max_r 30, armory_builders 12. The two
  leads are re-tested on cur6 (decoys compete with towers for the building slots).
- Tower package (cur6 + tower_wood_drop, time 600, reserve 20) vs cur6: N=13 s1..200 surv60 +0.81 (z 1.6), W 1 -> 1;
  s201..400 surv60 +0.77 (z 1.6), towers20 +1.13 (z 3.9), arm25 +6 pp (z 1.9), W 2 -> 0, elim -.011; N=14 surv60 +0.26;
  N=12 W 5 -> 2, surv60 ~0. woodr20 alone vs cur5 at N=14: surv60 +0.70 (z 2.4), towers20 +0.49 (z 2.2). Survival up in
  4 of 5 blocks, wins undecided: a fresh-seed win count (N=13 and N=14, 600 per arm) is queued.
- cur6 regression check at N=11 (cur6-vs11-hv vs freeze-squad6-c3-vs11-hv, s1..200): W 15 -> 16, elim +.016, surv60 +1.8
  (z 2.3), towers20 +1.22 (z 4.2): cur6 is good at N=11 too.
- slots32 (= cur6) wins replay VERIFIED: N=13 s3077 (358:23), s3287 (77:07), s3521 (130:48); N=14 s3484 (220:18) and
  s3549 (see replay-s32-14-3549.log).

### Hygiene pass 2 (behaviour-preserving, on cur6; 2026-09-29 evening)

- Commits f804f67d..f1670fc8: shared MapAnalysis.centroid / nearest and Intel.homes / finishedBuildings for helpers
  and lists copied in 4-5 places; Freeze.Strike phases take their target (moveSquad, quartersStood); the longest new
  methods split (Military.attack: traceBattle, chargeStunned, endColumnMarch, stalled; chooseTarget: focusRemnant,
  nearestEnemyUnit; Economy.planBuildings: planArmory / planQuarters / planTowers; dropWood: orderWood); one parked-
  ring test (inRing); stale and orphaned Javadoc, spotless-broken paragraphs, and the strike log that printed a
  Building's identity hash. Failed experiments stay behind their flags; no param was inert everywhere.
- Identity after every code commit: default N=13 s201-203 (slots32-c5-vs13-hv-b), N=14 s1-2 (slots32-c5-vs14-hv),
  N=11 s1-2 (cur6-vs11-hv), N=12 s1-2 (slots32-c5-vs12-hv), N=8 s201-202 (cur6-vs8-hv-b), logged N=13 s201, and the
  tower_site_fallback (cur5 slots) and tower_wood_drop arms: every checksum equal. The final build also plays the
  same games as the frozen starting point (@g-pre-hyg2) with 24 off-by-default params on at once (N=13 s1031-1033,
  N=11 s97-98: ring_sweep, unjam columns, stall_retarget, evacuate, lures, snipers, freeze_fight/armory_push,
  shepherd_follow/home_pair/safe_walk, tower_site_fallback, tower_wood_drop, site_towers_first all fired).
- CPU (paired, identical games, N=13): Shepherd.findSpot's per-candidate shepherd_rej_* counters are now counted only
  in logged games (3-7 million AiLog.count calls in a 30-120-min game): -0.9 % +- 0.4 % over 8 games. computeField tests each
  neighbour cell once per expansion (8 lookups instead of 16): -3.2 % +- 1.5 % over 7. The whole pass against the
  starting point on 24 fresh games (s1011-1022, s1041-1052): -1.5 % +- 0.7 % per game, -1.2 % of the total. The
  profile's 2.2 % for the counters overstated what removing them saves, as the harness manual warns.
- Lab: board.py (a sweep's scoreboard, --median reads each arm against the median arm), pairs.sh, musters.py,
  cuts.py and chiefs.py moved in from the session scratchpad.
- **Hygiene pass 2 merged** (dc7fea17; reviewed "merge"): shared MapAnalysis.centroid/nearest, Intel.homes and
  finishedBuildings, Freeze.Strike phases take their target, long methods split (Military.attack, chooseTarget,
  Economy.planBuildings into planArmory/planQuarters/planTowers), stale comments and broken Javadoc fixed, the strike log no
  longer prints an identity hash, computeField tests diagonals only when both side cells are open (identical distances),
  shepherd_rej_* counters only in logged games (they ran to 3-7 million count calls a game). Checksums identical after
  every commit at N=8/11/12/13/14, logged and unlogged, and with 24 off-by-default params on; CPU -1.2 % (24 paired N=13
  games). Lab tools now in lab/gauntlet: board.py (with --median: each arm against the median arm, scaled by its share of
  re-rolled games), pairs.sh, musters.py, cuts.py, chiefs.py. The main checkout's merged build reproduces cur6 (s201, s202).
- **cur6 benchmark so far** (all default cur6 games, fresh and screened seeds): N=13 10 wins in 1,600 (0.62 %), N=14 8 in
  2,000 (0.40 %; cur3/cur5 had 1 in 600 each), N=15 0 in 600, N=16 0 in 400. N=15 fishing continues (1,000 more).
- **cur6 + woodr20 loses the fresh-seed win count** (4001..4600, 600 per arm): N=13 cur6 4 vs 3, N=14 5 vs 1 (all runs:
  N=13 4 of 1,000, N=14 1 of 800 with the package). tower_wood_drop stays off.
- On cur6 (N=13, s1..200 / s201..400): chieftain_time=300 surv60 +0.67 / +1.83 (z 2.8), arm25 +2.5 / +7 pp (z 2.0),
  towers20 +0.5 / +1.03 (z 2.7), W 1 -> 0 / 2 -> 3 (with the cur5 sweep block: positive in all three); confirmations queued
  (fresh 4001..4600 at N=13-14, N=14 s1..400, N=12). decoys + decoy_free_slots=1 surv60 +0.9 / +0.5, alive40 +1 / +2.5
  pp, W 1 -> 0 / 2 -> 3: mild, not pursued. cur6 at N=8 (vs cur5, s201..400): W 115 -> 120, alive40 +5.5 pp (z 2.3),
  towers20 +0.87 (z 4.9). N=11 legacy arms on cur6 all negative: hold_closing=1 W 16 -> 13, stun_patience=false surv60
  -2.3 (z -2.8), shred strict elim -.030 (z -2.0). wood_reach=110 fires rarely: N=12 W 5 -> 5, N=11 W 16 -> 20 (elim z
  2.0). Race check on cur6 at N=12: native Hards W 5 -> 2, surv60 -2.5 (z -2.9): vikings stay the easier opponent.
- The summary step now runs out of heap even on 200-300-game batches (cur6 games last longer): stub summaries written
  for cur6-vs8-hv-b, woodr20-c6-vs13-f2, cur6-vs14-hv-d, cur6-vs16-hv; results.jsonl complete in each.
- cur6 N=14 wins replay VERIFIED: cur6-vs14-f1 s4051 (96:17), s4113 (209:48), s4139 (219:45), s4154 (88:04); cur6-vs14-f2
  s4455 (165:00); cur6-vs14-hv-d s642 (203:39); with slots32 s3484 and s3549, all 8 cur6 N=14 wins are verified.
- **Summary out-of-memory fixed on headless** (bceffd8d, merged 0fb44a3a): each analysis Game kept its whole parsed game
  file, so the summary held all of a run's census at once in the parent's 768 MB heap; it now adds each game to the curve
  cells in one pass and forgets it. The six stubbed runs (cur6-vs8-hv-b, cur6-vs14-hv-d, cur6-vs16-hv, unjam8-c4-vs11-hv,
  unjam8-c4-vs12-hv, woodr20-c6-vs13-f2) now have real summaries (./aisim.sh summary RUN); the 200-game cap and the stub
  watcher are no longer needed. The merged build plays cur6 bit-identically (s201, s202).
