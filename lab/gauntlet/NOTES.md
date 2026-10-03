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

### cur7: the chieftain from 300 s (2026-09-29 late afternoon)

- **Adopted (0dd43463): chieftain_time 300 for many enemies (Strategy.forGame; was 240).** On cur6: N=13 s1..200 surv60
  +0.67, s201..400 +1.83 (z 2.8); fresh 4001..4300 W 2 -> 5, surv60 +1.41 (z 2.8), wp +0.7 pp (z 2.6), elim +.013;
  4301..4600 W 2 -> 3, elim +.016 (z 2.4); N=14 s1..200 surv60 +0.65, s201..400 surv60 +0.85 (z 2.0), arm25 +6 pp (z 2.2);
  fresh 4001..4300 W 4 -> 0, surv60 -0.44, arm25 -4 pp (z -2.4); 4301..4600 W 1 -> 4, elim +.024 (z 3.3); N=12 ~0.
  Fresh-seed wins 9 -> 12 of 1,200. 360 / 420 / 480 at N=13: surv60 +0.75 / +1.04 / +0.74, elim +.012 / +.005 / +.016:
  the optimum is broad; 240 trained him too early (training takes a quarters' breeding in the opening).
- N=15 fishing on cur6: 0 wins in 1,000 more games (0 in 1,600 in all). N=16: 0 in 400.
- cur7 benchmark on fresh seeds (6001+) running: N=14 2,000, N=15 2,000, N=13 1,000.
- **N=15 audit on cur6 (audit15: 4 lenses, verifiers, judge; scratchpad/audit15/judge.md):** no lever it found is
  expected to be worth a step in N. The largest gaps (copies neutralized by 10-12 min, warriors at 12 min, the third of
  N=15 games that fall behind from minute 3) have no lever that survived checking. Ranked, all small: decoys with
  decoy_free_slots=1 (positive survival in 3 of 3 N=13 blocks, never run at N>=14); defend at posts near towers
  (hold_multi + hold_ratio, never screened at N>1: 74-89 % of the home army's uncovered losses at N=15 are 15-30 cells
  from a finished tower, i.e. defenders chasing out of tower reach); keep the before-contact turn-back out of mid-battle
  (precontact_calm); recall for the last buildings. Not levers: campaign target choice and timing (home fights trade 7.5:1,
  field fights 1.5-2.5:1; a 45-s earlier campaign adds +0.14-0.18 neutralizations), peon rules after 12 min (no iron),
  the opening (does not change with N), rock_stream, more towers to save armories (armory fall times do not move with
  tower count), wider shepherd rings for far copies, focusing fire on enemy chieftains (30 throws for ~0.8 fewer base
  waves), sweeping parked blobs, recalls. The no-code arms are queued after the cur7 benchmark, on its seeds.
- **First N=15 wins** (cur7 benchmark, fresh seeds 6001..6500): cur7-bench-vs15-a s6022 (305 min) and s6303 (204 min),
  2 of 500; same seeds N=14 2 of 500 (s6409 116 min, s6415 288 min), N=13 5 of 500 (s6037, s6187, s6029, s6189, s6490).
  Replays running.
- **N=15 beaten, replay VERIFIED** (snapshot 81b6277e74, cur7 defaults): cur7-bench-vs15-a s6022 won at 305:00 (checksum
  -90583123) and s6303 at 204:20 (checksum -833116341). Also verified: N=14 s6409 (115:48), s6415 (288:26).
- **cur7 benchmark, final** (fresh seeds 6001-8000, default cur7, snapshot 81b6277e74): N=13 12 of 1,000 (1.2 %), N=14 4
  of 2,000 (0.20 %), N=15 3 of 2,000 (0.15 %: s6022 305 min, s6303 204 min, s7282 59 min), N=16 0 of 1,000. Median game
  24.2 / 22.6 / 21.5 / 20.4 min. N=14 is within noise of cur6 (8 of 2,000 on mixed seeds). Highest N beaten: 15.
- **Why the wins are long: dry spells.** After 3-5 copies are out by ~30 min, many wins stall with no elimination for
  100-220 min, then the rest fall in 15-60 min (s6415: 4 out by 13 min, nothing until 232, the other 10 by 288; s6022:
  146-min gap then 5 outs in 15 min); the short wins (59-116 min) have no gap over 23 min. A dry-spell diagnosis workflow
  is running (replay logs of s6022, s6303, s6415, s6409 and the long N=13 wins).
- **Winning from k copies left** (lab/gauntlet/remaining.py over 9,400 cur6/cur7 games at N=13-15): reaching 8 left
  (alive) wins 19 % / 41 % / 30 % at N=13 / 14 / 15 (125 / 34 / 10 games) against 60 % for a fresh N=8; 9 left 5-11 %,
  10 left 2 %; 7 left 57 % / 88 % / 50 %; 6 or fewer 77-100 %. The state differs from a fresh start: 8 left is reached at
  19-23 min, when the survivors hold 430-470 warriors and 1,200-1,300 units against our ~60 warriors and 12 towers, our
  iron gone, theirs still respawning (the opening lead that wins fresh N=8 games is spent). At N=15 only 10 of 3,600 games
  reach 8 left alive: the bottleneck is 11 -> 7 inside the collapse window. From 7 left the endgame wins but slowly (N=14:
  7 left at 52 min, 5 at 81, 4 at 109; 15-30 min a copy): the dry spells.
- Replays VERIFIED: N=15 s7282 (59 min), N=14 s7104 (83 min), and the long N=13 wins s6029, s6189, s6490 (logs for the
  dry-spell diagnosis).
- **audit15 no-code arms on cur7** (seeds 6001..6300, paired with cur7-bench-vs14-a / -vs15-a; W base -> arm; the
  base drew poorly at N=15, every arm is up there): decoys + decoy_free_slots=1 N=14 wp +0.39 pp (z 2.0), alive40 +2.3
  pp (z 2.1), surv60 +0.7, W 0 -> 0; N=15 alive40 +1.3 pp, arm25 +3 pp (z 1.7), W 1 -> 0. hold_multi + hold_ratio=0.5
  N=14 W 0 -> 3 (s6207, s6215, s6257), wp z 1.7, the rest ~0; N=15 ~0, W 1 -> 0. hold_ratio=1.0 N=14 slightly negative
  (W 0 -> 1), N=15 wp z 2.1 within the base's draw. precontact_ratio=0 N=14 elim z 2.2, N=15 surv60 z 2.1, W 0 -> 0 / 1
  -> 1. Confirmations of decoyfs1 and hold05 on fresh seeds (6301..6800 at N=14, plus N=13 and the pair combined) queued.
- **Dry spells diagnosed** (workflow: 3 lenses over the replay logs of the long wins and 53 long losses, check, judge;
  scratchpad dryspell/judge.md): the long wins are short wins with 1-3 h holes in the middle. In the holes our army is
  absent, mainly because the home armory's 60-cell tree ring is cut out after 45-150 min (**wood lock**: 42-56 % of gap
  time; tree cycle 91 s, wood 0-1, 150-200 peons banked, iron full at 200, 1.4-1.6 warriors/min against 9.5-13 when
  copies fall; trees 43-122 cells away in every lock); short of wood otherwise 17 %, of iron 15 % (early in the gaps),
  an army of 50+ that turns back 16 %. Moving is blocked by the threat gate and the building cap in 96-100 % of locked
  minutes, and relocated armories are fragile (5 of 17 completed after 60 min razed within 2 min). A gap ends when
  wood reaches a standing armory; the first out follows 7-32 min later, then the rest fall at 3-8 min each. The copies
  left are static (warriors flat). Long losses: the usual end is an armory razed with its peons inside (39 of 53 lost
  100+ units in one razing, median 163; units 235 -> 42; the loss a median 21 min later); the lock itself is a fairly
  safe state (9 % lost within 30 min vs 46 % with <= 10 warriors). Fixes queued on the 171 cur7 benchmark games alive
  at 40 min (late-acting, identical before; lab/gauntlet/late.py scores them): wood_reach=150, bank_guard from 2400 s,
  and both. lock_relocate (relocate on the lock with a reserved slot) is folded into the relocation design.
- **Relocation audit** (workflow: stall, iron, raze and value analysts, design, critique; scratchpad expand/*.md; all
  instrumented runs identical to cur7-bench): the armory moves once because (1) planArmory checks for an expansion only
  while exactly one armory stands, and the drained home armory stands on after the expansion (66 % of 8-13-min
  windows), and (2) any base threat stops the check (100 % of checks at 13-20 min, although the cost test would pass
  92-95 % of the time: best site 0.49 of the current cost). Other gates (cap, cost, no site) are <= 3.5 %. After the last
  armory falls a replacement is placed in 52 % and finished in 26 % (placers killed or re-ordered into buildings,
  sites razed as sites, no free peon because all are shepherds/lures/dodgers, treeless sites). 85 % of armory-less
  time is the final death spiral (0 warriors, 1 quarters, 4 towers). **Iron correction:** the late iron is initial
  stock in the middle (STRATEGY.md, facts), the respawn starts at a median 21 min, and out copies' homes are empty, so
  "move to the fields of out copies" gains nothing. The expansion's income by age: 30 / 20 / 6 / 1 iron/min at 0-2 /
  2-4 / 4-6 / 6-8 min; at expansion age 3 min a site 40-80 cells deeper holds a median 158 loads within 30 cells: a
  **second hop** is the dynamic-expansion lever. Model estimate of the value: +5 iron/min from 13 min takes N=13 wins
  1.2 % -> 1.6-2.0 %, +10 -> 2.1-3.2 % (at most half an N step; late warriors die fast: 13-20 min we make 31, lose 98).
  **Razing our own buildings** is a legal UI order (select, A, click own building: Unit.canAttack with kill_friendly
  on ATTACK); the slot frees the same tick; 10 iron warriors raze a tower in ~14 s, quarters/armory in ~28 s; peons do
  3 HP/s on towers; units inside vanish, nothing is refunded; waves in flight do not retarget (snapshot cell).
  **Far quarters:** keep them (the expansion falls in 100 % of games and the home quarters are the fallback: 7.5 vs 27
  razings per quarters-hour after the fall, and where a lost armory is rebuilt); one narrow case (both caps bind, a
  slot for a tower in long games) goes into `retire`. Being built in a worktree (critique's order, all off): rearm
  (placer fixes), reloc (the hop, local quietness, drained-armory gate, hysteresis), reloc_slot, raid_bank,
  reloc_draw, raid_evac, rearm_reach, reloc_lock + retire (late track).
- **Against the old Expert** (frozen @expert, benchmark map, seeds 9001-9050, current build): 1v1 W 40 L 3 D 7 (score
  .87); against 2 allied Experts W 9 L 33 D 8 (.26; losses a median 28 min, wins 22-138 min). For scale, Expert on the
  benchmark beat 3 / 4 / 5 / 6 Hards 92 / 70 / 33 / 4 % (expert-exam runs); we beat 8 / 11 / 13 / 15 Hards 60 / 8 / 1.2 /
  0.15 %. Against two Experts we reach every milestone first (Q1 37 vs 51 s, armory 193 vs 241, first tower 255 vs 487)
  and kill more (kd30 +160), but two economies out-produce one (workers 120 vs 244 at 10 min, warriors 52 vs 156 at 15).
  14-16 % draws at 360 min in both lineups: the long-game finishing problem (dry spells) shows against Expert too.
- **Late fixes, N=13** (the 83 cur7 benchmark games alive at 40 min, lab/gauntlet/late.py; checksums agree to 2400 s in
  83 of 83): **wood_reach=150** wins 12 -> 16 (gained s6028 (the 225-warrior stuck draw), s6068, s6514, s6856; lost
  none); long wins shorter: s6029 244 -> 107 min, s6189 319 -> 113, s6709 175 -> 109, s6789 221 -> 182, s6490 195 -> 231
  (mean -34 min). bank_guard from 2400 s 12 -> 13 (lost s6029); both 12 -> 16 (lost s6029, 4 new draws): the peon
  parking adds nothing on top of the wider tree reach. N=14/15 and the N=16 games pending.
- **Stuck army against Expert** (vs-expert2 s9041, a draw at 360 with 227 warriors 75 cells from a 10-warrior Expert):
  the replay shows 176 warriors wedged in a cliff pocket from ~41 min ("jam: 26 warriors blocked", attack stalled).
  unjam=8 with unjam_from=2400: a column march at 2500 s (91 of 115 blocked), through at 2555 s, win at 43.4 min.
  The benchmark has the same case (s6028 N=13, 225 warriors against 3 copies with 3 warriors, draw); unjam from 40 min
  is queued on the games alive at 40 min at N=13-16.
- Confirmations on fresh seeds (6301..6500, N=14, vs cur7-bench-vs14-a): decoys + decoy_free_slots=1 W 2 -> 0, elim z
  -1.7; hold_multi + hold_ratio 0.5 W 2 -> 0. Neither lead holds.
- wood_reach=150 late at N=14 (55 games alive at 40 min): W 4 -> 5 (gained s6777, lost none; s6415 288 -> 137 min);
  with the late bank_guard 4 -> 5 but lost s6409. N=13+14 together: +5 wins, 0 lost. Fresh block 8001..8500 at N=13-14
  queued (base first, then the arm on its games alive at 40 min). More audit15 confirmations: decoys N=14 6501..6800 W
  0 -> 1, N=13 6001..6300 W 4 -> 7 (elim z 2.1, towers20 -0.7 z -2.4); hold05 N=14 6501..6800 W 0 -> 1 (alive40 z 2.4);
  decoys + hold05 N=14 6001..6300 W 0 -> 3 (wp z 2.7, the same block where hold05 alone gave 0 -> 3). Totals at N=14
  over 800 fresh-ish seeds: decoys W 2 -> 1, hold05 2 -> 4: not adopted.
- **Broken spawns and the wins** (workflow: census of 85,000 copies, vanilla same-seed replays "hard vs hard*N" on all
  6,000 benchmark seeds (same map code and starts in 6,000 of 6,000), log-only geometry probe; judge and critic;
  scratchpad broken/judge.md): about 1 copy in 300 is stuck for good by its spawn (0.35 %: never finishes its quarters
  or its armory; 4-5 % of games; 3x as often on hilly maps). Causes: a wood-poor site (the crew loops between site and
  trees and never delivers; median 3 trees within 30 cells of walk vs 27) and the user's cliff case (the stock AI
  picks its armory site by straight-line ring scan: ~0.6 % of copies get one 150-700 m of walk from a quarters 30-60 m
  away; ~23 % of stuck armories); permanent because AdvancedAI never places a new site while one stands (the lock our
  freeze path (c) uses). **5 of the 19 wins had a stuck copy vs 0.75 expected** (p 0.0006; odds x9, CI 1.9-25; in
  vanilla 4 of the 5 were dead too). Corrected effective N: s6037, s6709, s6996 (N=13) were N=12 fields; s6415 (N=14) 13;
  s6022 (N=15) ~14. Clean wins left: N=13 9, N=14 3, N=15 2 (s6303, s7282): the highest N beaten stands. **Field speed
  is the larger driver:** by quartile of the field's median armory time (fastest to slowest) wins are 0 / 3 / 6 / 10
  (p 0.0004; 0 / 2 / 4 / 7 without any stuck, late or slow copy); measured before contact, so it is the map. Broken
  copies help by sending no waves in the 10-25-min danger window (+1.8 min survival each in losses), not by early
  outs; our target choice already leaves them alone. Lead for an audit: games where the freeze took path (c) (633)
  have 0 wins against 3.7 expected (p ~0.02, post hoc; aborted strikes, 873 games, are uninformative).
- **wood_reach=150 late, N=15** (33 games alive at 40 min): W 3 -> 5 (gained s6652, s7499, lost none; s6022 305 -> 117
  min, s6303 204 -> 260). **N=13-15 together: 19 -> 26 wins, none lost.** With the late bank_guard: N=15 3 -> 6 but
  N=13-14 lose s6029 and s6409; bank_guard alone N=14 4 -> 5, N=15 3 -> 3 (lost s6022). unjam=8 from 2400 s, N=13: 12 ->
  13 (s6028, the stuck draw, now a win), every other game unchanged (it only acts in a jam). Candidate cur8 = wood_reach
  150 + late unjam, pending the fresh block (8001..8500) and N=14-16.
- hold05 (hold_multi + hold_ratio 0.5) N=13 6001..6300: W 4 -> 9, elim z 2.0, wp z 1.6; across N=13-15 so far 7 -> 13.
  Fresh N=13 block queued. seed_quarters=30 N=13: W 4 -> 3, alive40 z -1.5 (seeds 5.3 peons per game in half the
  games): no gain.
- Freeze re-check queued (freeze_open=false at N=13/14, 300 games each; user doubts the opening investment).
- **Freeze re-check on cur7: keep it.** freeze_open=false vs cur7-bench (6001..6300): N=13 W 4 -> 2, elim -.040 (z
  -4.8), surv60 -1.7 min (z -3.0), towers20 -1.5 (z -4.7), arm25 -8 pp (z -2.8); N=14 W 0 -> 0, elim -.026 (z -4.3),
  surv60 -1.3 (z -2.9), towers20 -0.9 (z -2.8). The opening investment pays by a wide margin.
- unjam from 2400 s at N=14 / 15 / 16 (55 / 33 / 7 games alive at 40 min): no change (no late jams there). wood_reach
  150 late at N=16 (7 games): W 0 -> 0. hold05 on the fresh N=13 block (8001..8300 vs cur7-bench-vs13-e): W 4 -> 4, wp
  z -1.4: the lead does not hold. seed_quarters=30 at N=14: W 0 -> 2, proxies ~0 / slightly negative: not adopted.
- Fresh cur7 block 8001..8500: N=13 8 of 500 (1.6 %), N=14 4 of 500 (0.8 %; 4 of 2,000 on 6001..8000). The cur8
  candidate is being confirmed on its games alive at 40 min (32 at N=13, 16 at N=14).
- **cur8 adopted** (commit 870a4cba): wood_reach 150 and unjam 8 from 2400 s by default. Fresh block confirmation (games
  alive at 40 min of cur7-bench-vs13-e / vs14-e): N=13 W 8 -> 9 (gained s8222, s8231, lost s8130), N=14 4 -> 4 with the
  wins a median 138 -> 112 min (s8130 238 -> 121). With unjam the same wins as wood_reach alone. Over both blocks: 9
  gained, 1 lost. cur8 defaults reproduce the arm games 6 of 6 (short and long). Merged cur8 bases (cur7 rows before
  40 min + cur8 late re-plays; lab/gauntlet/mergebase.py) are being made for pairing the next screens.
- **cur8 benchmark** (merged bases cur8-bench-vs13..16: cur7 rows, with cur8 re-plays of every game alive at 40 min;
  checksums agree to 2400 s in all 178 + 48): N=13 25 of 1,500 (1.7 %; seeds 6001-7000 + 8001-8500), N=14 9 of 2,500
  (0.36 %; 6001-8500), N=15 5 of 2,000 (0.25 %), N=16 0 of 1,000. Pair later arms with cur8-bench-vsN.
- **Relocation build** (workflow: 3 builders, review, verify, fix; branch reloc-impl at ac170444 in worktree
  .claude/worktrees/wf_6f471724-ebb-1, off gauntlet 93b5eff8; not merged yet): all off by default, 11 of 11 identity
  games match (cur7), and with cur8's params given explicitly it replays cur8 (3 of 3). Params: rearm_placer (safe
  armory placer: idle/tree peons first, threat and path checks, a reserved peon deployed from a quarters, evacuation
  exemption, alternative rebuild site), rearm_reach (quiet rebuild site by iron and trees), reloc (the hop: drained
  second armory ignored, local quietness instead of the global gate, triggers iron_cycle / cost / < reloc_nodes live
  nodes within 30 cells, sites >= 40 cells out with reloc_nodes nodes, hysteresis on the primary), reloc_slot (the hop
  holds a building slot), raid_bank (bank cap in a forward armory), reloc_draw (reject sites that would draw >= k
  copies' waves), raid_evac (+ raid_evac_time; empty an armory ahead of a launched wave), reloc_lock (+ time 2400;
  relocate on the wood lock), retire (+ retire_any_tower; raze a stalled site, stranded tower, drained armory or far
  quarters with an explicit ATTACK when a flagged project waits at the cap). Smokes (8 N=14 games): hops are placed
  but mostly razed as sites (20 placed, 7 completed); raid_evac without a time gate costs 19 % of 8-13-min iron (16 of
  24 evacuations were false alarms of the home armory); lock + retire turned the long wins s6029 / s6415 / s6189 /
  s6709 into wins at 136 / 158 / 100 / 140 min (base 244 / 288 / 319 / 175). Screens running from the worktree
  (track A on 6001..6300 at N=14/13 vs cur8-bench; track B late on the games alive at 40 min vs cur8-bench).
- **First N=16 wins** (cur8, fresh block 8001..9000, snapshot d6cab35db1): 3 of 1,000: s8959 (103 min), s8662 (186),
  s8130 (299). N=15 on 8001..8500: 1 of 500 (s8112, 42 min). Spawn check: s8959 has one likely stuck copy (s13:
  quarters at 59 s, no armory, no contact, out at 8 min); s8662 one late copy (s3: armory at 371 s, recovered);
  s8130 none beyond the freeze target and campaign victims. Replays running.
- **N=16 beaten, replay VERIFIED** (snapshot d6cab35db1, cur8 defaults): cur8-bench-vs16-e s8959 won at 102:41 (checksum
  -1399051910), s8662 at 186:10 (-312080853), s8130 at 299:11 (1257346320); N=15 s8112 at 42:06 (-516647408).
  Highest N beaten: 16.
- reloc-impl merged into gauntlet (a8ec6a51, user OK). Merged build (snapshot 2738481522) at defaults reproduces cur8 6
  of 6 (N=14 s6001, s6002, s6409, s6777; N=15 s8112; N=13 s6029: checksum and length).
- **Relocation screen, N=14** (6001..6300 vs cur8-bench-vs14, which won 0 there, so every arm reads a little high):
  rearm_placer W 0 -> 2, wp z 0.8; + reloc 0 -> 1, surv60 +0.23; + reloc_slot 0 -> 1; **+ raid_bank 0 -> 2, surv60
  +0.56 (z 1.5), towers20 +0.54 (z 2.1), arm25 +4.3 pp (z 1.9), wp z 2.3; + reloc_draw=2 0 -> 3, elim z 1.7, surv60
  +0.61, wp z 2.0**; raid_evac from 780 s 0 -> 1, surv60 +0.26. Against the median arm (surv60 ~+0.25, towers ~+0.14)
  the raid_bank stacks stand out. Queued: raid_bank alone, and both stacks on the fresh block 8001..8500.
- **Relocation screen, N=13 and fresh N=14** (vs cur8-bench; W base -> arm, win flips gained / lost):
  | arm | N=14 6001..6300 | N=14 fresh 8001..8500 | N=13 6001..6300 |
  |---|---|---|---|
  | rearm_placer | 0 -> 2 | | 6 -> 7, surv60 +0.51, arm25 z 2.0 |
  | + reloc (hop) | 0 -> 1 | | 6 -> 5, surv60 +0.45 |
  | + reloc_slot | 0 -> 1 | | 6 -> 6 |
  | + raid_bank ("hopbank") | 0 -> 2, surv60 +0.56, towers20 z 2.1 | 4 -> 1, towers20 +0.46 (z 2.3), arm25 z 2.3, wp z -1.6 | 6 -> 10, surv60 +1.27 (z 2.5), arm25 +7.3 pp (z 2.8) |
  | + reloc_draw=2 ("hopdraw") | 0 -> 3, surv60 +0.61, wp z 2.0 | 4 -> 4, surv60 +0.40 (z 1.5), towers20 z 2.3, arm25 z 2.4, wp z -1.1 | 6 -> 9, surv60 +1.32 (z 2.5), arm25 z 2.5 |
  | raid_bank alone | 0 -> 2, towers20 +0.27 | 4 -> 3, elim z -2.3, towers20 z 2.5 | |
  | raid_evac from 780 s | 0 -> 1 | | 6 -> 6 |
  | rearm_placer + rearm_reach 260 | 0 -> 3, arm25 z 2.3 | | 6 -> 6, surv60 +0.87 (z 1.9), arm25 +8 pp (z 3.1) |
  hopdraw over 1,100 games: wins 10 -> 16 (12 gained, 6 lost); hopbank 10 -> 13 (9 / 6). Every block has survival and
  armory uptime up (the relocation lever works on the state it targets); wins are not yet clear (the fresh N=14 block
  is flat and its wp proxy negative), so **not adopted yet**: next, hopdraw on a fresh N=13 block (8001..8500 vs
  cur8-bench-vs13) and at N=15-16. reloc_lock on top of cur8 (78 of the 83 N=13 games alive at 40 min): identical
  wins and times: wood_reach 150 already removes the lock, so the lock move never fires; retire stays untested there.
- **State at wrap-up (2026-09-30):** defaults cur8 (wood_reach 150 + unjam from 2400 s, on cur7); relocation code merged,
  all off. Highest N beaten: 16 (3 of 1,000 fresh seeds, replay VERIFIED). cur8 benchmark: N=13 1.7 %, N=14 0.36 %, N=15
  0.25 % (plus 1 of 500 fresh), N=16 0.3 % on 8001..9000. Open leads: hopdraw adoption; freeze path (c) audit (0 of 633
  wins); the field-speed finding (wins cluster on slow fields: nothing to exploit found yet).

### Play-test feedback round (2026-09-30, user played online at ludicrous)

- **Game speed bug.** The user's online game (replay 12550 on tribaltrouble.org: 2 humans + gauntlet vs 6 Hards, large
  map, 20 starting units) ran at ludicrous: its spectator stream stamps world ticks (PeerHub, every 5), and the freeze
  target was out by tick 1200, i.e. 96 s at 80 ms a tick. World.tick runs the game-time pass with
  getSecondsPerTick() (0.5 / 1 / 1.75 / 4 x the 20 ms tick at slow / normal / fast / ludicrous), while GauntletAI's
  clock was ticks / 50: at ludicrous every period and timer ran 4x late in game time (shepherds from 8 game min,
  re-plans every 12 s, the late fixes from 160 min) and the swing restart counted 0.02 s ticks against 0.08 s
  animations (it fired a swing late, after the next swing had started: no gain, a voided swing). The stock AI counts
  seconds (AI.shouldDoAction), so it was unaffected. Fix a9b85bae: the clock adds each tick's game time, the swing
  restart counts the units' step, the 5-tick guards run every 5 normal ticks of game time. Harness: aisim --speed
  (headless a2b585b5; harness times stay world ticks, so at ludicrous they are a quarter of game time).
  N=6, normal speed, seeds 6001..6100: 87 of 100 for both builds, 100 of 100 games identical. **N=6 at ludicrous,
  6001..6200: @cur8 93 W / 105 L, fixed 189 W / 10 L** (median win 49.5 -> 33.5 game min). Chickens by 20 game min:
  ours 23 at normal, 9 at ludicrous with the old clock (iron 290 vs 215); each Hard ~9-11.
- The humans' units: the AI only counts its own units and isEnemy players, so allied peons never enter its
  thresholds. Stock waves target the nearest enemy unit if it is under 0.707 of the nearest enemy building
  (AdvancedAI.findTarget), so allied peons parked near our base can pull waves there; in 12550 one human's 20 peons
  stood 33 cells from our buildings all game (never died), the other's stayed at their start and vanished at ~7 game
  min. Allying with the Hards instead would make the humans enemies of ours (counted in N, freeze and campaign
  targets, needed for the win). Least influence: spectate, or park far from every start on our team.
- **Low-N baseline (cur8 + clock fix, normal speed):** N=1 996 W / 4 L (6001..7000), N=2 996 / 3 / 1 draw, N=3 593 / 6
  (6001..6600), N=4 392 / 5 / 2 draws (6001..6400), N=5 384 / 12 / 4, N=6 358 / 41 / 1 (89.5 %). Every N=1-2 loss and
  the N=2 draw: every ore of the kind the weapons need is beyond the main armory's 400 m walking field (computeField
  max 400; scanSupplies skips UNREACHABLE), so rock_weapons wants 140 rock gatherers and gets 0, iron is wanted 0, and
  100-200 workers sit in the armory for 20+ min while one Hard out-builds us (counter pick_null_rock_unreachable).
  **ore_reach=400** (iron, else rock, out to 800 m when the field has none): low-N losers 5 of 14 flip to wins (N=1
  s6087, s6508; N=2 s6529 draw; N=3 s6282, s6598); N=3 6001..6600 591 of 599 identical, W 593 -> 594 (+2 / -1, the
  loss s6034: 100+ peons on far-iron trips); N=6 372 of 400 identical, 358 -> 358 (+1 / -1); N=13 292 of 299
  identical, 6 -> 6. Remaining low-N losses: s6449 N=3 is a broken spawn (a stock Hard in our slot never places an
  armory either); s6045 N=1 has almost no ore within 800 m; s6102 N=2 builds 97 warriors by 15 min and never
  attacks while two Hards out-grow it; the hopdraw stack on top flips s6215 and s6102 (N=2) but not the rest.
- **Timeouts at N=4-6** (7 in 1,200): army wedges (s6021 N=5/6: 90+ warriors pressed against a 2-cell diagonal pass
  from 23 min for 5 hours; the stall clock restarts whenever anyone in the army fights, so no stall ever fires, and
  unjam's one column march at 40 min stays stuck) and production stalls (s6210 N=4, s6074 N=5: 250 units, 0
  warriors). **stall_cap=300** (no 20 m gain and < 10 kills in 300 s stalls the target even while some fight; a
  second stall in a row walks the army home to re-form): with ore_reach, 2 of 6 timeouts become wins (s6386 61 min,
  s6074 153 min); the pass in s6021 still holds (the army re-musters and marches back into it).
- **Quirks from the play test.** "Peons out of the armory and straight back in" and "peons standing still": in the
  gatherer allocation a peon whose pick fails (mostly chickens out of range or near enemies) is taken off the free
  list but never ordered (gather_pick_failed ~570 economy ticks per N=13 game), and when no peon is free the armory
  deploys workers for a supply none can reach, which then walk back in (deploy_gather ~54 per game).
  gather_probe=true fixes both: N=13 6001..6300 W 6 -> 10 (+9 / -5, z 1.1), surv60 +0.12 (z 0.2). Defense
  overcommit is rare at normal speed: 29 of 1,629 logged threat-2 events at N=6 pit a small threat against a home
  army 5x its size (the army is on campaign), and the military thinks every 0.5 s (2 s at ludicrous before the fix).
- **Towers and the cap (N=13 benchmark):** by 12 min 18-19 of 20 building slots are used (11-13 towers), 54-72 % of
  games at the cap; in losses towers then fall 11.2 -> 6.2 by 25 min while the cap no longer binds (13-29 %): towers
  are lost to razings, not missing. Chickens: by 20 min we take 11 of ~87 (each Hard ~6; N=6: 23 vs 9); most failed
  hunts are out of range (150 cells) or near enemies.
- **N=6 losses** (41 of 400): mostly 19-32 min with 4-6 copies alive. s6064: the army (100) marches 318 m to the far
  copy right after an out while 110 enemy strength stands on our main armory (built at the expansion, no towers in
  reach); both armories fall by 18:23, the army is ground down without production. s6074: iron gone at both armories
  by 8 min, 130 peons on rock that delivers ~1 unit per 800-5000 gatherer-seconds (peons 167 -> 26 by 15 min).
  Queued at N=6: recall_ratio 0.8 / 0.5, target_threat_weight 2, chickens (14 hunters, 250 cells), hopdraw,
  rearm_placer.
- **Screens (paired with base-vs6 6001..6400 / cur8-bench-vs13 6001..6300; W base -> arm, gained / lost):**
  | arm | N=6 | N=13 |
  |---|---|---|
  | gather_probe | 358 -> 364 (+15 / -9), surv60 +0.41 (z 1.5) | 6 -> 10 (+9 / -5), surv60 +0.12 |
  | ore_reach=400 + gather_probe, fresh (6401..6800 / 8001..8300) | 365 -> 367 (+10 / -8), surv60 +0.22 (z 1.1) | 5 -> 2 (+1 / -4), surv60 -0.11 |
  | recall_ratio=0.8 | 358 -> 359 (+4 / -3), surv60 +0.23 (z 1.7), 323 identical | |
  | recall_ratio=0.5 | 358 -> 356 | |
  | target_threat_weight=2 | inert (379 identical, 0 flips) | |
  | chicken_hunters=14, chicken_range=250 | 358 -> 358 (+14 / -14), surv60 +0.44 (z 1.8); chickens by 20 min 23.2 -> 25.7 | 6 -> 6 (+4 / -4) |
  | hopdraw (rearm_placer, reloc, reloc_slot, raid_bank, reloc_draw=2) | 357 -> 360 (+8 / -5), surv60 +0.45 (z 2.2), alive40 +7 | (earlier: surv60 up in every block) |
  | rearm_placer | 356 -> 359 (+10 / -7), surv60 +0.24 (z 1.3) | |
  Chickens are bounded by the map's spawns (3 flocks), not by hunters: no lever. At ludicrous the fixed AI is stronger
  than at normal speed on the same seeds (N=6 6001..6200: 180 -> 189 W, +13 / -4, z 2.2).
- In 10 of the 42 lost N=6 games a >= 30-strength enemy force stood at home with less than half its strength in
  defenders and towers while the attack army (>= 0.8x) was away (the s6064 pattern); recall_ratio=0.8 answers it but
  barely moves results.
- **More screens:** gather_home alone N=6 358 -> 365 (+13 / -6, surv60 +0.64, z 2.7), N=13 6 -> 6 (surv60 +0.69, z
  1.6), but on top of ore_reach + gather_probe: fresh N=6 364 -> 366 (as without it), fresh N=13 5 -> 2, N=10 73 -> 73
  (surv60 -0.96, z -1.2; without it 79): not adopted. ore_reach + gather_probe at N=10 (6001..6300): 73 -> 79 (+40 /
  -34). Earlier towers (tower_parallel=2, towers_mid_time 270, towers_late_time 480): N=6 358 -> 360, N=13 6 -> 7:
  near neutral, as before. stall_cap=300: N=13 300 of 300 identical, N=5 397 of 400 identical (+1 win). hopdraw on
  top of the candidate, fresh N=6: 366 -> 363: not adopted.
- **Ludicrous, N=13** (6001..6300, the old defaults with the clock fix): **47 of 300 wins vs 6 at normal speed**
  (surv60 +14.9 min). The stock AI is on the real-time animation manager too (AI constructor) and its
  shouldDoAction counts the real tick's 0.02 s, so at ludicrous every Hard decides every 20-28 game seconds instead of
  5-7: the stock Hards are 4x slower in game time there (vanilla behaviour, the same online). Ludicrous results are not
  comparable with the normal-speed benchmark.
- **cur9 adopted** (defaults): clock on game time + ore_reach=400 + gather_probe + stall_cap=300; 40 of 40 N=6 games
  replay the explicit-param candidate.
- **cur9 benchmark** (same seeds as cur8-bench): N=13 6001..6500 7 -> 12 of 500 (+11 / -6, surv60 -0.24), N=14
  6001..6500 2 -> 3 of 500 (+1 / 0, surv60 +0.08). Ludicrous (cur9, --minutes 90): N=16 0 of 200 (median game 28
  game min, 10 alive at 40), N=20 0 of 200 (24 min); N=13 was 47 of 300 (39 min, 140 alive at 40): the ludicrous
  ceiling sits between 13 and 16.

### Ludicrous tuning (2026-10-01): the benchmark moves to --speed ludicrous

The user plays online at ludicrous, and the stock Hards' slower decisions there (real-time cadence, every 20-28 game s)
are an accepted exploit: no engine or stock-AI change, no speed beyond ludicrous. Only our AI is made speed-correct.

- **Harness on game time** (headless 001fd17c, merged): rows, game files and AI log stamps hold game seconds at every
  speed (non-normal rows also carry the world `ticks`); the collapse rule (60 s), the time limit (--minutes, default
  360) and w15/kd30 are game time (at ludicrous the collapse rule used to take 240 game s). Normal speed is an exact
  identity. Old non-normal runs (lud-*, lud9-*) keep world-tick times; lab/gauntlet/gtime.py converts them at read time,
  and every lab tool now reads through it (the studies also count collapse-outs, which write no out event).
- **cur9 at ludicrous, new harness, 6001..6500:** N=13 68 / 500 (13.6 %), N=14 17, N=15 7, N=16 2 (s6215, s6224), N=17
  0, N=18 0. The ludicrous frontier is N=15-16 (normal speed: N=13 2.4 %, N=14 0.6 %).
- **Game-time audit** (workflow, 10 agents): the AI still drifted at ludicrous. Rounds re-anchored on the world tick
  they fired, so every period rounded up to whole 80-ms ticks (intel 0.5 s ran every 0.56 s, economy 1.04 s, plan
  3.04 s, and every timer checked in them with it); measureYield took a round as 1 s; unjam counted scans as 5 s; the
  swing restart hung the game in an endless loop when a human paused it (speed 0); weapon_sync horizons were world
  ticks.
- **Every time in game ticks** (user's call; 46911b10 / d172de4d): 64 parameters renamed `*_ticks` (50 a game second;
  shepherd_time 120 -> shepherd_ticks 6000, rates per tick: shepherd_cells_per_tick), constants `*_TICKS`, the clock an
  exact float game-tick count (multiples of 0.25, exact for 23 game hours), rounds on a game-tick schedule
  (`next = max(next + P, now + P - slack)`), periodic re-armed checks via `periodDue` (one world tick of lateness
  allowed), the audit's fixes folded in. Verification (paired, every game diverges because the old seconds clock
  slipped by float rounding): normal N=13 300 games W 10 -> 6 (+4 / -8, z -1.2), surv60 -0.04 (z -0.1); ludicrous
  N=13 500 games W 68 -> 65 (+43 / -46, z -0.3), surv60 -0.30. counters.py (new: counters per game minute, games
  <= 40 min, with z): at normal no counter moves beyond |z| 2; at ludicrous the round cadence shows (shepherd_t_* +12 %,
  peon_blocked +10 %), nothing else. One game replays VERIFIED. Adopted.
- **Old-build screen at ludicrous N=13** (cur9 names, 6001..6300 vs ludicrous-base-vs13, W 47): attack_min_strength
  10 / 30 W 37 / 37; attack_ratio 0.8 / 1.25 43 / 46; attack_max_strength 40 / 120 47 / 47; retreat_ratio 1.2 / 2.0
  44 / 43; reinforce_ratio 0.8 (300 games) neutral. The campaign's defaults hold at ludicrous.
- **The freeze opening fails at ludicrous:** first copy out at 8.8 game min (normal 1.6), freeze kills 8.9 per game
  (17.6), squad peons lost 2.6 (0.7), aborts 0.6 (0.2). The squad (eta 21-33 s) arrives before the copy's first orders
  and meets all 20 starting peons idle: idle peons answer through their 8-cell scan, builders never do ("squad at
  s9:hard at 24s: striking 20 peons ... given up: squad down to 2 peons"). New `freeze_patience_ticks` (off): within
  16 cells of a copy with at least as many idle peons as the squad has, the squad holds out of scan reach until fewer
  are idle or the patience runs out; screening at 750 / 1500 / 3000, and 1500 with freeze_eta_ticks 3000.
- **Freeze patience, first try** (hold 16 cells from the centre of all the copy's peons): W 65 -> 66 of 500, 262
  identical; 199 of 238 held strikes still given up (the busy peons walking to the quarters site pulled the centre,
  and so the hold point, into the idle crowd's reach). 750 and 3000 ticks played the same games as each other.
- **Freeze patience, reworked** (fb3d7913: keep 16 cells from the nearest idle peon, pick off busy peons more than 12
  cells from every idle one, patience from 24 cells): **N=13 ludicrous W 65 -> 107 of 500 (+71 / -29, z 4.2), surv60
  +4.90 min (z 7.9), alive40 +75**; freeze target out 0.3 -> 0.8 per game, kills 8.4 -> 18.1, squad lost 2.7 -> 0.2,
  first copy out 8.8 -> 1.7 min (as at normal speed). 3000 ticks: the same 300 games as 1500; with freeze_eta_ticks
  3000: 284 identical, W 71 -> 71. Confirmations queued (N=15, N=16, fresh 8001..8500, normal speed).
- **Screen on the tick build without patience** (6001..6300 vs ticks-ludicrous-vs13-all, W 45): shepherd_ticks 12000
  (240 s) W 19, surv60 -4.56 (z -5.8): the early shepherds are essential; 3000 (60 s) identical games. freeze_targets 2
  W 31 (z -2.5, surv60 z -2.8); freeze_squad 8 W 32 (z -1.9); freeze_eta_ticks 3000 / 4500 W 44 / 43 (284 / 280
  identical); target_defense_weight 16 W 28 (z -3.0), 4 W 46 (surv60 +0.72, z 1.7); reinforce_ratio 0.3 W 49 (z 0.7).
- **Beta tester's feedback** (workflow: 7 investigators over 70 logged ludicrous games, N=13 and N=6; plan in the
  session scratchpad): peon swarm: banks vanish with razed armories (~112 units per N=13 game, peons inside a razed
  building disappear uncounted) and evacuatePeons/allocatePeons feed the besieged armory, but weapons were 0 in 101 of
  104 falls and the threat ~7x the bank's value, so only a winnable sortie can help (sortie_engage, deferred).
  Quarters idle: orders come within 1 game s; only the rush alarm before the first armory holds 20-90 door peons
  (rush_hold_armory). Stockpiles: the forge is saturated and iron node-limited at 6-12 min; weapon_reserve (keep R
  weapons undeployed) is the honest test. Far gathering: real at N=6 (3 of 4 losses); stuck_trip_factor (never
  tested), ore_iron_first, ore_reach_fail. Rally points: a recall loop fights the engine's re-link to the nearest
  armory (relink_guard). Idle after a siege: the army leaves in ~20 s; long idle stretches are jams (unjam_from_ticks 0,
  mostly N=6). Shepherds: fleeing outbound shepherds pull caught waves 25 cells towards us, caught waves feed 73 % of
  base-threat entries (shepherd_flee_side).
- **cur10 adopted** (f40b07e1): freeze_patience_ticks 1500 by default. Confirmations: N=15 5 -> 16 of 500 (z 2.5,
  surv60 +4.75, z 9.8), N=16 5 -> 10 (surv60 +4.22, z 10.4), fresh N=13 8001..8500 59 -> 105 (z 4.8), normal speed
  N=13 281 of 300 identical (6 -> 7, surv60 +0.46). cur10 at ludicrous: N=6 288 of 300 (6001..6300), N=13 107 / 500,
  N=14 51 / 500 (fresh 49), N=15 16 / 500 (fresh 30), N=16 10 / 500.
- **Beta fixes, wave 1** (2dcb583f, off), vs cur10, 6001..6300: shepherd_flee_side N=13 W 71 -> 88 (z 2.0, surv60
  +1.39), N=6 287 / 288; relink_guard N=13 68, N=6 287 (neutral); rush_hold_armory N=13 71 (119 identical), N=6 287.
  shepherd_flee_side confirmations: N=13 6001..6500 107 -> 128 (z 2.0, surv60 +1.61, z 2.9), fresh 105 -> 102 (surv60
  +2.38, z 4.2, alive40 +44), N=14 51 -> 44 (surv60 +0.96, z 1.8), fresh N=14 49 -> 53 (surv60 +1.40, z 2.4), N=15 16
  -> 15 (surv60 +0.73), fresh N=15 30 -> 25 (surv60 -0.10). Survival up at N=13-14 in every block and wins faster
  (shared wins 7-15 min sooner), but W +9 over ~2950 games and N=15 -6: not adopted; a lead for N=13-14.
- **Beta fixes, wave 2** (28d0b2b3, off), vs cur10, 6001..6300: ore_iron_first + ore_reach_fail + relink_guard N=13 69,
  N=6 288; the same + stuck_trip_factor 1.0 N=13 72 (surv60 +0.48), N=6 288 (surv60 +0.14, z 1.8); weapon_reserve 12
  / 24 N=13 63 / 67 (surv60 -0.56 / -0.89): the hold-back family again; sortie_ratio 0.8 N=13 72 (85 identical), N=6
  289; sortie_ratio 1.0 N=13 80 (+16 / -7, z 1.9): confirming.
- **Screen on cur10** (no-code arms, N=13 6001..6300, W 71): stuck_trip_factor 1.0 78 (z 1.3), 0.6 74; unjam_from_ticks
  0 inert (285 identical; N=6 queued); woodr20 76, surv60 +0.84 (z 1.6), alive40 +11; gather_avoid_parked 75;
  rush_opening_only 71; target_defense_weight 4 66 (surv60 z -1.7); reinforce_ratio 0.3 81 (z 1.4); chieftain_ticks
  10000 (200 s) 60, surv60 -1.81 (z -2.6), 22500 (450 s) 87 (z 1.9); towers_early/mid_ticks 15000 / 24000 82 (z 1.3).
  Confirming chieftain 450 s, reinforce 0.3, later towers and sortie 1.0 on 6301..6500 and 8001..8300.
- **Confirmations on 6301..6500 + 8001..8300** (vs cur10): chieftain 450 s W -5 / +4 (surv60 +0.31 / +1.91);
  reinforce_ratio 0.3 0 / -13 (z -2.1): dropped; later towers +7 / +4 (surv60 +0.56 / +1.65, z 2.3); sortie_ratio 1.0
  0 / +2 (surv60 +0.34 / +0.68, z 2.4). Over 800 games each: towers +22, sortie +11, chieftain +15 (mixed).
- **Stacks** (vs cur10; N=13 on the new block 6501..7000, cur10 127): towers + sortie N=13 138, N=14 59, N=15 24;
  + chieftain 450 s 138 / 52 / 22; **+ shepherd_flee_side 145 (z 1.7, surv60 +1.79, z 3.3) / 70 (z 2.1, surv60 +1.85,
  z 3.6) / 29 (z 2.3, surv60 +1.68, z 3.4)**. Confirmations: fresh N=14 49 -> 59 (surv60 +2.24, z 3.8), fresh N=15 30 ->
  27 (surv60 +1.51, z 3.1, alive40 +26), N=16 10 -> 15 (surv60 +1.11, z 2.5), N=6 288 -> 287, normal speed N=13 7 -> 9.
  The flee alone did nothing at N=15; with the later towers and the sortie it does.
- **cur11 adopted**: towers 3 from 300 s and 6 from 480 s, sortie_ratio 1.0, shepherd_flee_side. The defaults replay the
  explicit-param games (s6510 N=13, s6003 N=15). Over the N=13-16 blocks: W +62, survival up in every block.
- **Timeout audit** (workflow, 4 diagnosticians + synthesis; 87 ludicrous timeouts on the tick builds, 53 seeds; plan in
  the session scratchpad): 0.24 % of N>=13 games. Mechanisms: (M1) our fields forbid corner cuts the engine allows, so
  pockets look sealed (stall, dead region, repeat; s6657 x4, s8462, s6206), and inDeadRegion's radius 2 falls inside a
  7x7 building so the army cycles the sealed region's buildings for hours; (M2) wedged armies whose stall clocks are
  reset (the calm retarget and setTarget call markCapProgress; tower kills at home count; stall_cap sits after the
  engage returns): s6036, s8150, s6215; (M3) RETREAT never ends when 70 % cannot get home (s6409: 279 min); (M4) the
  attack gate tests one building and counts other copies' parked masses within 60 cells; (M5) our own homeless remnant
  (a shepherd locked for 5 h); (M6) wood/iron locks (pick_null_tree_unreachable >= 20k in 18 of the 87 vs 5 of 5364
  wins); (M7) 47 real stalemates (fortresses at the cap, we are weaker). 16 rows are winnable positions spoiled by a
  logic failure. Remnants across the 87: 17 lone chieftains, 54 homeless copies with a chieftain, 63 homeless with > 8
  units and no chieftain (never launch again), 35 site-anchored.
- **The user's pacify-then-strike idea**: the Hard launches only with NUM idle warriors (10, +5, max 40) and, from NUM 20,
  only with an active chieftain, trained only in its quarters; a homeless copy past its second wave never attacks again.
  Being built (off): decapitate (quarters of live copies first, move on once homeless and chieftain-less), the remnant
  ladder (finish passive remnants late, isolated groups first), corner_fields + dead_region_reach + sealed_progress,
  retreat_cap_ticks, stall_cap_keep, stall_engaged_ticks.
- **Highest N at ludicrous: 18** (cur11, snapshot of the cand-tsf builds; 6001..6500): N=17 3 of 500 (s6081 101 min,
  s6257 111, s6366 155; 30 games alive at 40 min; 1 draw, margin -0.95), N=18 1 of 500 (s6220 114 min; 18 alive at 40
  min). Replays VERIFIED: N=18 s6220 won at 114:24 (checksum 1334996688), N=17 s6081 at 101:27 (495448014). cur9 had 0
  of 500 at both. N=19, N=20 and a fresh N=18 block queued.
- **Timeout fixes and decapitation built** (d389da8c, all off; flags-off identity checked): the four timeout smokes
  s6206 (N=6), s6657 (N=13), s8462 (N=13), s6409 (N=14) went draw -> win (48.8, 74, 96.5 min, and s6409 after the
  retreat cap released the army and the ladder finished the lone chieftain and homeless bands). Screens queued: the
  games alive at 40 min of the cur11 bases (1092 games, N=13-16) and 500-game decapitation blocks at N=13-15.
- **Highest N at ludicrous: 19** (cur11, cur11-vs19 6001..6500): 2 of 500 (12 alive at 40 min, no draws). Replays
  VERIFIED: s6045 won at 250:29 (checksum 1230984288), s6076 at 130:04 (2121349707). Spawn check (first armory per copy):
  s6045 is clean (only the freeze target, out at 121 s, has no armory); in s6076 copy s11 finished its first quarters only
  at 51 min (a stuck spawn: effectively N=18 until then). The N=18 s6220 and N=17 s6081 wins are clean likewise (freeze
  target only; the slowest armories at 323-403 s).
- **Timeout fix stack** on the games alive at 40 min of the cur11 bases (1092 games, N=13-16): W 344 -> 340 (+17 / -21),
  draws 5 -> 6 (s6657 draw -> win at 74 min; new draws s8091, s8039, s8367). Not adopted as a stack; components queued
  one by one on the cand-tsf bases' games alive at 40 min.
- Harness merged (headless 6f8d7d6b): automatic runs leave a thread free and run below normal priority; games identical.
- **Decapitation from the start: rejected** (500-game blocks vs the cand-tsf bases, 6001..6500): N=15 W 29 -> 15
  (+11 / -25, z -2.3), with the ladder 16 (z -2.1); N=14 W 70 -> 58 (+33 / -45, z -1.4), with the ladder 56 (z -1.6).
  The base's lead on these seeds is partly luck (cand-ts 24 / 59, cand-tsc 22 / 52 at N=15 / 14), but at N=15 the arm
  falls to the pre-cur11 level (flee-side 15, crowd 16). It overrides the best-scoring target in 73 % of its picks
  (decapitate_changed 0.156 vs decapitate_target 0.215 per game min). Shared wins at N=15 come faster (median 99 -> 81
  min): a late-only arm (decapitate_from_ticks 120000) is queued on the bases' games alive at 40 min instead of the N=13
  blocks (stopped after 27 and 9 games). Partial-batch caveat: pair.py on a batch's first ~130 rows showed z -3 (the
  longest-first order plays the base's lucky wins first); compare sibling runs on the same seeds before calling it.
- Record attempts paused (user, 2026-10-01): cur11-vs20 and cur11-fresh-vs18 left out until the experiments are done.
- **Timeout fixes one by one** (late.py on the games alive at 40 min of cand-tsf-new-vs13 / cand-tsf-vs14 /
  cand-tsf-vs15, 654 games; every arm identical to its base up to 40 min): pocket (corner_fields + sealed_progress +
  dead_region_reach 4) +1 / -1 at first. Its loss, s6099 N=14 (won at 168 min, lost at 117), came from a corner field
  taking the strict one's place although it did not reach the staging point either (corner_field_sealed): same
  cells, shorter distances, the march moved. Fixed (38541fe9: the strict field stays then): s6099 = base again,
  s6657 still draw -> win at 74 min, the other changed games = base. retreat_cap_ticks 6000 +1 / -0 (s6192 N=14 draw ->
  win), stall_cap_keep and stall_engaged_ticks 15000 no result changed (2 and 5 games changed), remnant ladder
  +13 / -12 over ~200 changed games (the whole stack's losses and new draws were the ladder's). The four clean ones
  together: +2 / -0 on the base sets, and no result changed on 438 games alive at 40 min of tsf-fresh-vs14,
  tsf-fresh-vs15 and tsf-vs16 (4 changed; the full stack there had +3 / -9). **cur12** = cur11 + those four, all from
  40 min (default-identity checked on s6657, s6827, s6192). Draws they do not fix: 2 at fresh N=14, 1 at fresh N=15.
- **Decapitation late only** (decapitate_from_ticks 120000, same 654 games): N=13 +15 / -9 (draws 1 -> 4), N=14
  +6 / -8, N=15 +1 / -2: neutral with more draws; not adopted. The pacify-then-strike idea is done for now: the copies
  do go passive, but going for their quarters and chieftains first does no better than the army's own target choice.
- **Why timeouts remain** (93 distinct ludicrous draws, all versions; scratchpad draws.py): A fighting at the unit cap
  56 (60 %: several copies keep a base at 250 units and keep sending waves, ~1,000 deaths an hour each side, copies
  left 7.9 -> 6.1 from 1 h to 6 h); B quiet standoff 20 (no kill in the last hour; our army at home failing the gate
  or wedged far out); C only homeless bands left 12 (won positions, margin +0.8 to +0.96); D our base gone 5 (lost
  games kept in by > 8 units or an unbuilt site). One draw costs 3-11 % of its block's compute.
- **Endgame options** (2a1a5230 ff., all off): mopup (the remnant ladder once no copy has a base), allin_ticks (all-in
  muster on the weakest base on a frozen board), last_stand (5 min without a finished base: everything attacks, the
  economy stands down), push_ticks (+ push_calm, push_soft: the user's late offensive). On 1,092 games alive at 40 min
  (6 bases) + 30 at N=17: mopup, allin and last_stand lost no win (mopup fired in 6, sped 2 wins by 4-11 min; allin
  never fired; last_stand in 57 losses, -9 % of their compute). push (all-in from 120 min, 30 min without an out):
  +5 / -8, fired in 83; 7 of the 8 lost had a push under threat: push_calm and push_soft (the usual muster decision
  under threat) queued. Draw seeds (27): push +2 (s6028 loss -> win, s8389 draw -> win).
- **cur13 = cur12 + unjam from the start** (3408cb1e): 1,424 games at N=13-15, 1,362 identical, wins +4 / -2; 81 of
  103 column marches got through; s6274 (N=17 canyon jam, draw) -> loss at 34 min. The two lost wins (s6929, s6719
  N=13: one early column march each, then divergence) are being traced.
- **Big finding, the repair right-click**: Action.DEFAULT on our own damaged quarters or tower makes a peon REPAIR
  instead of entering (Unit.setTarget: canRepair before canEnter; armories are entered); a repairer without wood first
  walks to a tree. Every shelter / home / breed order used DEFAULT, so under attack peons sent into a damaged quarters
  stood outside as repairers, and the worst peon jams (s6689: 60-69 peons for 10+ min, up to 45 % of peon time in
  the worst games, median 1 %) were such wood runs. enter_move (1833238d, off) uses MOVE; enter-move-vs13 running.
  Repairs cost 1 wood per 5 HP (~1 HP/s a peon). The tuned parameters assumed the accidental repairs: a deliberate
  repair swarm (wood-fed, under threat too) and a salvage (empty a building about to fall toward a safe one) are
  being designed, then a retune.
- **Units lost inside razed buildings** (1,500 games N=13-15): 95 % of games lose a finished armory, 2.3 armory razings
  a game (median 25 min), median 15 / mean 26 units vanish per armory, 13 per quarters; 47 (wins) / 175 (losses) a game.
- **Hills**: N=13 W h0 39.5 % / h1 29.7 / h2 20.0, N=14 15.8 / 13.4 / 9.5 (weaker at N=15-16). h0 -> h2: shepherd finds
  no ground spot +26 %, time at the spot -25 %, waves at the base +28 %, peon jams 4x. Next: the shepherd's spot search
  on rough terrain.
- **Benchmark maps 0/10/10 from 2026-10-02 (user).** cur13 there: N=13 219 / 500, N=14 79 (hills 0..2 runs not comparable).
- **cur14** (a87406e8, ee7cd473): the retune without the right-click quirk (user's goal): enter_move, repair_swarm
  (design values), salvage, shelter_reach 15, late_caution .5. N=13 234, N=14 125, N=16 21, N=17 7 (5 clean wins,
  replay VERIFIED). Steps: enter_move alone 202 (quirk worth ~17 wins), + swarm 219, + salvage 228, + shelter 15 236
  (shelter_off 182, quarters_sortie 220), finish_copies 167, deny_rebuild 191 (finishing still costs); the aggressive
  swarm +19 at N=13 but -17 at N=14 and tied at 16-17 (design values kept); late_caution within noise at N=16-17.
- **Shepherd deep dive** (workflow; scratchpad shep/): a wave hits our base when no shepherd is in the copy's 0.707
  disc at its decision tick; at base launches the copy's own shepherd was away after a flee in 62-70 %, had no spot in
  23-30 %. Zero-code sweeps neutral (follow 241, safe_walk 246, sticky 241 vs 234). Built S1-S5 (9debd57d): at N=13
  vs cur14 234: C3 flee pick + tether 2 + hunted **288 (z 4.4, surv60 +2.55 z 5.4, base launches 13.0 -> 10.0 %)**,
  confirmed N=14 125 -> 162, N=16 21 -> 35; B2 calm peons + circles 247, D1 predicted origin 248, A1 site origin 227,
  E2 fallback 227; stacks C3+B2 296, C3+D1 291, **C3+B2+D1 322 (z 6.9 vs cur14)**, N=14 / 16 so far 142 / 44 vs 100 / 21.
- **cur15** (38cd5f9d) = cur14 + C3 + B2 + D1. Frontier blocks N=17-21 queued.
- **cur15 frontier on 0/10/10** (6001..6500, 500 games each): N=14 221 (5 draws), N=16 59, N=17 26 (2 draws), N=18 9
  (1 draw), **N=19 5** (all replay VERIFIED: s6037 78:19, s6265 91:51, s6154 136:55, s6387 156:19, s6050 166:31; 4
  clean, s6037 had two copies that never built an armory until 65 min), N=20 0 (30 alive at 40 min, all lost by 99
  min, 8-15 copies still with a base at 40 min against ~5-6 in the quick N=19 wins), N=21 0. cur14 had N=16 21, N=17 7.
  Fresh N=20 blocks (6501..7000, 8001..8500) and N=22 running.
- **cur15 draws at N=14** (logged replays): s6241 unclosed remnants: a gatherer recall loop (drainSecondary every 10 s
  after the primary switched; loads re-link to the old armory, the new one counts no gatherers, want_tree 1; 118 wood
  in 5 h) left the army at 40 against a 147-warrior homeless blob; s6095: the only wood behind a 3-cell ridge gap held
  by 5-6 parked warriors, 25 peons a minute walked into it for hours (5,405 deaths in one 6x6 box; supply picks test
  threats near the supply, not on the way; 7 of 500 N=14 games show such a repeat kill zone, all 160+ min). Wood
  stalls of 30+ min: only these 2 of 128 long N=14 games, none at N=16-19: real but rare. Parked fixes: relink_guard
  (exists), pool_linked, gather_kill_zone.
