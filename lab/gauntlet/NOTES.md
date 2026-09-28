# gauntlet: notes

AI `gauntlet` (package `tt/src/main/java/com/oddlabs/tt/player/gauntlet/`, branch `gauntlet` off `headless`). Goal:
beat the largest number N of allied stock Hard AIs (`--players "gauntlet vs hard*N"`) with at least 10% wins on the
final exam (large tropical, hills 0..2, trees 10, supplies 10, slot 0, seeds 30001..30100). A win is eliminating
every opponent; a timeout is a draw.

This file is the working record: the comparison of the five earlier AIs, the current best, the plan, every
hypothesis with its runs, and the dead ends. The next session resumes from here.

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

References on the same dev seeds (1..100, slot 0, vikings everywhere): `@expert` 33 at N=5 (base-expert-vs5),
7 at N=6 (base-expert-vs6); `@sweep-v75` 12 at N=4 (base-sweep75-vs4).

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
