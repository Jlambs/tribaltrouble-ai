# fable: working notes

`fable` (`tt/src/main/java/com/oddlabs/tt/player/fable/`, entry `FableAI`) plays one against N allied stock Hard AIs.
Goal (user, 2026-11-14): the highest N it beats with a win rate of 50 % or more. A timeout is a draw, not a win.

## Result (2026-11-15)

Exam: seeds 36001..36200, never used for any decision; benchmark settings below; frozen tags `@f2` (fable) and `@g16`
(Gauntlet cur16, compiled unchanged on this engine); 200 games each, none failed, no swallowed errors.

| N | fable | Gauntlet | paired, fable against Gauntlet |
|---|---|---|---|
| 13 | 144 / 200 = 72.0 % [65.4, 77.8] | 121 / 200 = 60.5 % [53.6, 67.0] | +40 / -17 (z 3.0) |
| 14 | 107 / 200 = 53.5 % [46.6, 60.3] | 83 / 200 = 41.5 % [34.9, 48.4] | +41 / -17 (z 3.2) |
| 15 | 70 / 199 = 35.2 % [28.9, 42.0] | 59 / 199 = 29.6 % [23.7, 36.3] | +35 / -24 (z 1.4) |

(Wilson 95 % intervals. One N=15 game of each tag had not ended when the table was made.)

A second exam block for fable at N=14, seeds 36201..36500: 153 of 300 (2 draws). Over all 500 exam games at N=14
fable wins 260 = 52.0 % [47.6, 56.3].

So the highest N beaten at 50 % or more is 14 for fable and 13 for Gauntlet, and N=14 is right at the line: the point
estimate is above 50 % in both exam blocks and on the dev seeds that chose the defaults (154 of 300, 51.3 %), the
interval reaches below it.

What changed from Gauntlet, in the order of what it bought (details below):

1. The campaign's target choice weighs a base's defense at 2 m a unit instead of 8, so the army takes the nearer
   bases instead of crossing the map for the weakest one.
2. The attack keeps recruiting sappers (peons that pull towers down). They used to be recruited only at an attack's
   launch, and the campaign is one attack that launches before any Hard has a tower.
3. Six quarters holding 4 peons each instead of four holding 10 (the breeding formula rewards quarters, not peons
   inside them).

Robustness of the final source (2026-11-15, no failed game, no swallowed error): 1 against 1 on random maps of every
size, both starts 39-0-1; against 6 Hards at normal speed, hills 0..4, 10-2; against 3 on small and medium maps
11-1. A replay in a fresh JVM reproduced its game (final-check2 s21005-0 VERIFIED). `spotlessCheck` passes.

Second session (2026-11-15 and 16, "Second session" below): about forty more variants at N=15, none better than
these defaults, so the result above stands. Natives as opponents are harder (N=14 48 wins of 100 against 58, N=15 27
against 40). The source differs from `@f2` only in counters and log lines (lost_ROLE, reinforce_sent, the `lost ...`
lines and two more fields of the battle line): it plays `@f2`'s games (8 of 8 identical checksums, same-check3;
s21005-0 of it replays VERIFIED in a fresh JVM), and `spotlessCheck` passes. Committed on branch `fable` since
2026-11-16.

Not done: nothing is pushed; the game itself was not run (headless games only).

## Benchmark (user, 2026-11-15: the same as Gauntlet's)

Large tropical island, hills 0, trees 10, supplies 10, ludicrous speed, vikings on both sides, our AI in slot 0:

```bash
./lab/fable/dev.sh NAME "fable vs hard*14" 21001..21100        # dev seeds 21001..21999
EXAM=1 ./lab/fable/dev.sh NAME "fable vs hard*14" 36001..36100 # exam seeds 36001..36999: never tuned on
python lab/fable/stats.py RUN...                               # wins, survival, copies out by minute T
python lab/fable/stats.py --pair BASE VARIANT...               # paired on the same games
python lab/fable/proxy.py BASE VARIANT...                      # wins and the minute-20 model, paired
python lab/fable/early.py BASE VARIANT...                      # towers, warriors, copies with a base by minute
python lab/fable/timeline.py RUN                               # won and lost games side by side
python lab/fable/towerkills.py RUN                             # what our towers kill, by period
./lab/fable/queue.sh "NAME|PLAYERS|SEEDS" ...                  # batches one after another
```

## Where it starts from

`fable` began as a copy of Gauntlet (`origin/gauntlet` 16fa1a79, "cur16"), package and entry class renamed. With no
params it reproduced Gauntlet game for game (6 of 6 identical checksums, fork-check-vs12) before anything was added.
Gauntlet is frozen in this checkout as `@g16`; it compiles unchanged on the current engine (headless bdc87aee).

Gauntlet's own notes (`lab/gauntlet/` on its branch) were written on an engine 454 commits older; its numbers there do
not hold on this one. Baseline of `@g16` here, dev seeds 21001..21100, 100 games each:

| N | 10 | 12 | 13 | 14 | 15 | 16 | 18 |
|---|---|---|---|---|---|---|---|
| wins | 95 | 82 | 60 | 37 | (running) | 11 | 2 |

So Gauntlet's highest N at 50 % is 13; the target is 14 and beyond. At every N it has ~1.7 / 3.0 / 3.9 / 4.5 copies
out by 10 / 15 / 20 / 30 min, and its first armory falls at ~24 min, in wins and losses alike. Wins have more copies
out early (N=14: 3.5 against 2.7 by 15 min).

## Engine facts checked in the source (this engine)

- 20 starting peons, 250 units and 20 buildings a player. Quarters and armory 200 hp, tower 100, chieftain 60.
- A quarters breeds (1/11) x max(n, 0.5)^(1/3) peons a second with n inside: 4.3 a minute empty, 8.7 with 4, 11.8 with
  10, 14.8 with 20. It stops at the unit cap.
- Weapons: rock 2 wood + 1 rock (40 worker-s), iron 2 wood + 1 iron (80), chicken 2 wood + 1 rock + 1 iron + 1
  chicken (120). A throw takes 2 s (AttackBehaviour.SECONDS_PER_ATTACK); hit = factor x hit chance x (1 - defense),
  hit chance .5 / .75 / .95, defense .5 / .7 / .7, a tower's factor 3. Damage to buildings 1 / 2 / 2 a throw.
- A peon's swing: 1 in 5 against a unit (times 1 - defense), 6 damage to a tower every swing, 1 damage 1 time in 5 to
  a quarters or armory: peons cannot raze a base. A stunned unit has defense 0.
- Peons walk 5 m/s, warriors and chieftains 4 (2 cells).
- Ore: a node holds 10; once 75 % of a kind's nodes are empty one random empty node comes back every 10 s (60 a
  minute map-wide per kind). Chickens: at most 3 groups of 3-7 on the map, a new one every 60 s.
- Stun: 40 energy, blast 70, a cast empties both.
- The stock AI (AdvancedAI, AI): its construction flags (armory/quarters/tower under construction) clear only when
  that building stands or when it has no site and no peon carrying one, so an orphaned site freezes the flag (the
  "freeze"). It defends only when an enemy stands within 30 m of its first quarters or armory, and not at all without
  either.

## The Hard copies' first minutes (40 games of g16-lud-vs14, per living copy, mean)

| min | units | warriors | weapons in stock | towers | chieftain | inside buildings | peons outside |
|---|---|---|---|---|---|---|---|
| 3 | 32 | 0 | 0 | 0 | 0 | 20 | 12 |
| 4 | 46 | 0 | 4 | 0 | 0 | 33 | 12 |
| 5 | 62 | 4 | 9 | 0 | 0 | 41 | 16 |
| 6 | 80 | 10 | 10 | 0 | 0 | 54 | 15 |
| 7 | 95 | 14 | 10 | 0 | 0 | 64 | 16 |
| 8 | 102 | 18 | 8 | 0.4 | 0 | 67 | 16 |
| 9 | 105 | 20 | 8 | 0.8 | 0.4 | 72 | 12 |
| 10 | 119 | 22 | 9 | 1.0 | 0.8 | 84 | 12 |
| 12 | 157 | 27 | 7 | 1.3 | 0.9 | 108 | 19 |
| 15 | 214 | 35 | 11 | 1.7 | 0.9 | 152 | 23 |

## Where our own numbers go (g16-lud-vs14, won / lost games)

- Warriors peak at 15 min (163 / 140) and then melt: 89 / 50 at 25 min, 89 / 29 at 30. We sit at the unit cap from
  10 min; from 15 min on the cap is filled with workers (81 -> 137) that have nothing to forge.
- Iron harvested: 132 by 10 min, 248 by 15, 304 by 20, 361 by 25: ~28 a minute until 12 min, ~10 after. Rock: 22 by 10
  min, 39 by 15, 57 by 20. Over a whole game we take 756 iron, 303 rock and 70 chickens; the 14 copies together 1725,
  1950 and 215.
- Early weapons are limited by iron trips (55-95 s a round trip), not by forge workers: iron in the armory stays
  near 0 while rock piles up.
- The campaign: 8.6 copies a game lose a quarters or armory; both kinds are down a median 29 s after the first, the
  copy is out a median 114 s after it (a quarter take more than 20 min, 18 % never), 25 % rebuild. The next copy is
  first hit a median 2.1-2.4 min after the last one.
- At the base, towers and defenders kill hundreds (465 in the last 7 minutes of one N=16 game) but each wave razes a
  building or two before it dies: 40 warriors do ~34 damage a second.

## Experiments (N=14, dev seeds 21001..21100, paired with g16-lud-vs14: 37 wins)

| arm | wins | notes |
|---|---|---|
| quarters_before_armory=2 | 36 | outs@10 -0.15 (z -2.0), w10 -5 |
| quarters_before_armory=1 | 35 | outs@10 -0.31, outs@20 -0.45 (z -2.6), w10 -10 |
| deny_rebuild=true | 34 | outs@15 +0.11 (z 2.1), surv -1.1: neutral |
| target_defense_weight=4 (was 8) | 43 | +13 / -7; outs@20 +0.11 |
| target_defense_weight=2 | 45 | +14 / -6 (z 1.8); outs@15 +0.12 (z 1.9) |
| rock_idle_ticks=30000 (rock axes from idle workers from 10 min, not 20) | 37 | neutral |
| rock_share_late=0.4 from 12 min | 31 of 95 | worse: rock displaces iron |
| chief_home_from_ticks=0, stun_mob=3 (chieftain stays at the base) | stopped at 36 | surv -5.8 min (z -4.3): the army needs him |

| target_defense_weight=0 | 44 | +14 / -7 |
| target_defense_weight=2, target_home_weight=0.3 | 45 | as tdw 2 alone |
| target_defense_weight=2, focus_bonus=80 | 43 | no gain over tdw 2 |
| hold_mid=6 (was 10) | 39 | +15 / -13; surv +1.6 min (z 1.6), outs@15 +0.16 |
| rock_stream=true | 34 of 99 | no |
| chief_home_from_ticks=54000 (home from 18 min), stun_mob=3 | 34 | no |
| sapper_refill=true | 41 | +14 / -10; 81 fewer units lost a game |
| rock_idle_ticks=0 (idle armory workers make rock axes from the start) | 43 | +19 / -13 |
| **tdw 2 + sapper_refill + rock_line=0.4** | 48 | +18 / -7 (z 2.2) |
| **tdw 2 + sapper_refill + rock_line=0.4 + hold_mid=6** ("f1") | 49 of 99 | +22 / -10 (z 2.1); wins 9 of the 31 maps every earlier arm lost |

Baseline of `@g16` at N=15 on the same seeds: 26 wins, 1 draw (99 games).

## What decides a map

Outcomes are mostly decided by the map: over the baseline and seven single-param arms on the same 100 maps, 32 maps
were lost by all eight and 28 won by six or more (lab/fable/groups.py). The start's geometry does not tell them apart
(distances to the nearest four copies, to the edge and the centre are the same in every group); ore does. From the
first ORE line of a 5-minute logged opening on every map (open5-vs14): the lost maps have 82 iron nodes within the
armory's 400 m field, the mixed ones 95, the won ones 103, and in the full games 12-15 % less iron and fewer warriors
through minutes 8-15 (111 warriors at 12 min against 133). So a little more army in those minutes moves a map from
lost to won, and the other ore is the place to get it: rock is nearly always close (33-38 m at the start) and we take
300 of it a game against the copies' 1950.

### The second ore did not pay (tried and removed)

Three ways of making rock axes next to iron ones early, all neutral or worse:

| arm (N=14, same 100 maps) | wins | what happened |
|---|---|---|
| rock_idle_ticks=0 alone | 43 | 7 more rock warriors and 7 fewer iron ones at 10 min: an armory splits its forging evenly between the kinds it can make (WeaponsProducer), so rock axes took half the forge |
| f1 + rock_idle_ticks=0 | 42 | below f1's 49 |
| f1 + rock_line 0.4, only while rock is the cheaper fighting value per peon-second | 49 | fired in 20 of 100 games for 0.3 min: iron is almost always the cheaper line |
| f1 + rock_line 0.4 always | 17 of 60, stopped | 30-40 % of the weapon pool on rock: less iron (82 against 98 by 10 min in one game), few rock warriors |
| the same with rock axes on order only while no iron axe can be forged | 4 of 17, stopped | the pool is short of peons in minutes 5-9 (forge workers 19-21 of 25-42 wanted), so a second line only thins the first |

rock_line and rock_line_strict were deleted. Gauntlet's notes reached the same verdict for later rock (rock_share,
rock_stream), and rock_stream / rock_share_late / rock_idle from 10 min were neutral or worse here too.

### Other things tried and removed

- Blitz: squads of 10-28 warriors leaving on their own from minute 4 for the nearest base they could beat (one logged
  game: three of four squads were too weak on arrival). By minute 6 only ~29 warriors exist, and Gauntlet's own first
  attack (5:00-5:30, ~20 warriors, at the nearest feasible base once the target weight is low) already uses them.
- chief_home / stun_mob: the chieftain kept at the base to stun waves under our towers, and peons sent at stunned
  enemies (a stunned unit has no defense). From the start: survival -5.8 min (z -4.3) in the first 36 games; from 18
  min with the mob: 34 wins against 37. The army's fights at enemy bases need him more.

## fable's defaults (2026-11-15, "f1", frozen as @f1)

Strategy.forGame: target_defense_weight 2, sapper_refill on, hold_mid 6. With no params `fable` plays s1-h6-vs14's
games (6 of 6 identical checksums, f1-check).

Checks of f1 (2026-11-15): N=13 64 wins of 100 (Gauntlet 60), N=15 32 (26), on dev seeds 21001..21100; N=14 on the
fresh dev seeds 21101..21300: 94 of 200 against Gauntlet's 85 (+32 / -23); with hold_mid 10 instead of 6: 96 of 200.
Over the 300 N=14 maps f1 wins 47.7 %, Gauntlet 40.7 % (paired +54 / -33, z 2.3).

More arms on f1 (N=14, dev seeds 21001..21100, f1 = 49): weapon_sync 46; rock (above); regate (a gate between the
campaign's targets: wait up to 60 s for reinforcements when the army is weaker than the next base's defense) 43 at
ratio 1.0, and stopped at 35-42 with regate_home: no. The opening strike: of 300 f1 games the target is out in 244
(49 % won), frozen in 20 (20 % won; Gauntlet 15 % on the same maps), not struck or failed in 36 (53 %).

## More quarters, fewer peons in each (the breeding formula)

A quarters breeds (1/11) x n^(1/3) a second with n inside, so Q quarters holding H peons in all breed in proportion to
Q^(2/3) x H^(1/3): quarters count for more than the peons inside them. Gauntlet's 4 quarters x 10 breed 47 a minute
with 40 peons held; 6 x 4 breed 52 with 24 held, and the 16 peons freed work in the minutes when the weapon pool is
short of them.

| arm on f1 (N=14, dev seeds 21001..21100; Gauntlet 37, f1 49) | wins | paired with Gauntlet |
|---|---|---|
| 5 quarters (hold 6) | 51 | +24 / -10; surv +3.3 min (z 3.2), outs@20 +0.37 (z 2.3) |
| 6 quarters (hold 6) | 49 | outs@15 +0.30, w10 +8.7 |
| 5 quarters, hold_mid 4 | 53 | |
| **6 quarters, hold_mid 4** | **58** | +29 / -8 (z 3.5); surv +2.9, outs@10 +0.16, outs@20 +0.35 |
| 7 quarters, hold_mid 4 | 52 | |
| 8 quarters, hold_mid 3 | 47 of 99 | fewer towers fit under the 20-building cap |

5 quarters (hold 6) on the fresh seeds 21101..21300: 104 of 199 against Gauntlet's 85 (+38 / -19, z 2.5): 51.8 % over
all 299 maps.

More on the quarters (N=14 unless noted; Gauntlet 37 on the dev seeds, 85 of 200 on the fresh ones, 26 at N=15, 11 at
N=16):

| arm | dev 21001..21100 | fresh 21101..21300 | N=15 dev | N=16 dev |
|---|---|---|---|---|
| 5 quarters, hold 6 | 51 | 104 of 199 | | |
| 5 quarters, hold 4 | 53 | 106 | 31 | |
| 6 quarters, hold 4 | 58 | 96 (1 draw) | 40 | 22 |
| 6 quarters, hold 3 | 54 of 99 | | | |
| 6 quarters, hold 4, hold_late 4 | 54 | | | |
| 6 quarters, hold 4, quarters_before_armory 5 | 53 | | | |
| 6 quarters, hold 4, sapper_min 12, sapper_per_tower 4 | 53 | | | |

Five and six quarters are the same within the noise at N=14 (159 and 154 of 300); six did better at N=15 and N=16.
The six-quarter build has 217 units at 8 min against Gauntlet's 192, its fifth and sixth quarters stand at 262 and
282 s (the armory at 231 s, 13 s later than Gauntlet's).

## fable's defaults since 2026-11-15 evening ("f2", frozen as @f2)

Strategy.forGame: target_defense_weight 2, sapper_refill on, and on large maps and up initial_quarters 6,
max_quarters 6, hold_mid 4. With no params `fable` plays q6h4-vs14's games (6 of 6 identical checksums, f2-check).

The gate between targets (regate_*) was deleted after its batches (43 wins against f1's 49).

Arms on f2 that did not move it (N=14, dev seeds 21001..21100, f2 = 58): expansion armory weighed at 0.9 of the
current gather cost instead of 0.75: 55 of 99; chicken_hunters 14 with chicken_range 250: 51.

## Second session (2026-11-15 evening on): where the army goes

### Natives as opponents: harder, not easier

`@f2 vs hard/n*N` on the dev seeds 21001..21100: N=14 48 wins (1 draw) against 58 for viking Hards, N=15 27 against
40. The Hards stay vikings.

### Where our warriors die (exam-f2-vs15, 200 games; lab/fable/timeline.py and the scripts quoted below)

- Won and lost games differ little until 15 min: 143 / 127 warriors at 15 min, then both melt (57 / 30 at 25 min). Iron
  comes in at ~28 a minute until 12 min and ~12 after; at the 250-unit cap the weapon pool is ~60 peons and a weapon
  costs ~285 peon-seconds (80 forging, 2 x ~46 wood, ~115 iron), so everything made after minute 10 only replaces
  losses.
- Our warrior deaths per game and 5 minutes: 60 (10-15), 105 (15-20), 118 (20-25). Within 17 cells of a standing
  enemy tower 37 % / 26 % / 17 %; within 20 cells of an enemy quarters or armory without a tower 15 % / 9 % / 6 %;
  the rest, 48 % / 65 % / 77 %, in the open field.
- By role (12 logged games, trace3-vs15; `lost ROLE at ...` log lines and the lost_* counters): in minutes 15-25 the
  attack loses ~120, the reinforcements on their way ~27 (15 % of those sent over a game; half die with no other of
  their group within 12 cells and ~20 enemy warriors around), and the home army ~48, nearly all within 45 cells of a
  building of ours (the forward armory, where fresh warriors wait in the open for their group to fill).
- Whom we kill: of the enemy warriors killed in minutes 15-20, 59 % belong to a copy we have not attacked yet and die
  far from its home (80 % in minutes 20-25); 17 % (10 %) die at the home of an intact copy, 15 % (6 %) belong to a
  homeless one. So the campaign is mostly a field war against the waves of every copy, which their shepherds parked
  between the bases, at 2.3 enemy warriors for one of ours overall (1.7 by enemy towers, 6.9 at our own base).
- Fights are won one against one: an iron warrior against the copies' mix (56 % rock, 39 % iron, 5 % chicken) trades
  at 1.66 by the hit and defense chances alone, and the big army gets 2.3-3.7 in its larger fights. The throw range
  is 6 m, so a clump has little of its number in reach.
- The chieftain: alive and above 24 hp through minutes 10-25, but he casts 3 times in 5 minutes (the recharge allows
  7) and the median cast catches 6 enemy units (a quarter catch one or none): the enemy arrives in dribbles, median 7
  warriors within 22 cells of the army when he casts. The stun reaches 36 m and lasts 10-30 s; an idle enemy sees 8
  cells.
- The stock AI recalls every idle warrior of a copy, wherever it stands, when we come within 30 m of that copy's
  first quarters or armory (AdvancedAI.nodeDefend), up to our strength; its waves walk to where the nearest unit or
  building of ours stood at launch (82 % of 2,965 logged launches: a shepherd).

### Where the peons are in minutes 3-16 (12 logged N=15 games of the defaults, trace3-vs15; means)

| min | towers (+sites) | held in quarters | building | wood | iron | armory workers (wanted) | home army | attack |
|---|---|---|---|---|---|---|---|---|
| 5 | 0.0 (0.7) | 20 | 9 | 9 | 10 | 17 (24) | 6 | 1 |
| 6 | 1.2 (1.5) | 30 | 16 | 10 | 12 | 24 (28) | 8 | 9 |
| 7 | 3.0 (1.5) | 39 | 16 | 12 | 13 | 26 (30) | 10 | 17 |
| 8 | 4.5 (1.7) | 37 | 15 | 16 | 17 | 26 (42) | 8 | 28 |
| 9 | 6.2 (1.7) | 13 | 17 | 23 | 25 | 43 (62) | 7 | 33 |
| 10 | 7.8 (2.1) | 8 | 18 | 19 | 23 | 43 (52) | 7 | 41 |
| 11 | 9.5 (1.7) | 4 | 13 | 21 | 24 | 45 (53) | 14 | 53 |
| 12 | 10.9 (0.6) | 1 | 5 | 19 | 25 | 44 (48) | 18 | 62 |
| 14 | 11.4 (0.3) | 5 | 2 | 17 | 21 | 28 (32) | 7 | 96 |

Sixteen peons build from minute 6 to minute 11 (towers at 1.8 a minute, and the forward armory), while the armory
has 4-19 workers fewer than it wants and the copies have no tower and no chieftain yet. The tower target is never
what stops them: it is towers_early / _mid / _late plus one per living enemy (multi_front_towers), 17 from minute 5
at N=15, so towers go up as fast as the one or two sites allow. Above 175 units the quarters hold hold_late (8)
each, 48 in six quarters, until the cap.

### Screens at N=15 (dev seeds 21001..21100; fable's defaults, cnt-vs15: 40 wins)

| arm | wins | notes |
|---|---|---|
| capped_clump=0.3 (reinforcements wait outside until worth 30 % of the army, at least 24) | 25 | +2 / -17 (z -3.4): the waiting group stands in the open at the forward armory |
| capped_clump=0.5 | 24 | the same |
| reinforce_min=6 (groups of 6, not 12) | 37 | +7 / -10; outs@20 +0.24 (z 1.9), outs@30 +0.31: more copies out, no more wins |
| sapper_reach=30 (sappers start 30 cells from the army's centre, not 18) | 34 | +8 / -14 |
| sapper_reach=30, sapper_lead=10 (and walk ahead of the army) | 39 | no |
| reloc=true (Gauntlet's armory hop) | 39 | +3 / -4: rarely fires |
| relink_guard=true (Gauntlet's guard against the gatherer recall loop) | 36 | +11 / -15 |
| siege=true (Gauntlet's: hold outside tower reach until the stun lands) | 17 | +1 / -24 (z -4.6); outs@15 -0.31, outs@20 -0.51: waiting costs the campaign its tempo |
| convoy=30 (new: weapons wait in the armory until 30 can leave together) | 18 | +3 / -25 (z -4.2); w10 -10, outs@15 -0.70: the first attack starves without its groups of 12 |
| peon_threat_cells=45 (new: the home army answers enemies by our peons only near our buildings) | 30 | +4 / -14 (z -2.4): the threat is also what makes far gatherers run |
| bypass=true (new: the attack leaves parked enemy groups alone and marches round them) | 37 of 86 | +11 / -14; surv -3.0 (z -2.0) |
| rally=true (new: units razing a building turn on enemy warriors within 22 cells) | 37 | +8 / -11; outs@30 +0.21 |
| rally + chief_leash=26 + hold_clear_towers (new) | 41 | neutral |
| towers_early_ticks=24000 (towers from 8 min, not 5) | 40 | +13 / -13; w10 +6.4 (z 5.3), outs@10 +0.21 (z 3.2), outs@30 +0.34 (z 2.1); 7 fewer warriors at 15 min |
| towers from 10 min (towers_early_ticks = towers_mid_ticks = 30000) | 40 | +12 / -12; w10 +9.1, outs@20 +0.36 (z 2.5); 10 fewer warriors at 15 min, 13 at 20 |
| chieftain_ticks=12000 (chieftain from 240 s, not 300) | 30 | +6 / -16 (z -2.1); 420 s cost 9 wins of 50 at N=14: 300 s is the spot |
| chicken_ticks=24000 (chicken hunters from 8 min, not 2.5) | 36 | +12 / -16 |
| raze_first=22 (new: quarters and armory before the peons around them) | 34 | +10 / -16; outs@10 -0.10 (z -2.0) |
| trim_quarters=4 (new: at both caps from 12 min, two of the six quarters are razed by our own peons for tower slots) | 31 | +3 / -12 (z -2.3); towers at 20 min 11.2 -> 12.1, warriors 89 -> 74: the breeding is worth more than the towers |
| late_from_ticks=0 (the bolder late thresholds from the start) | 38 | +9 / -11 |
| late_from_ticks=36000 (from 12 min) | 37 | +4 / -7 |
| attack_min_strength=12 / 26 (first attack smaller / larger; 18) | 37 / 37 | |
| hold_late=4 (8) | 37 | +13 / -16 |
| decoys=true, decoy_free_slots=1 (Gauntlet's decoy sites) | 39 | 3.3 waves a game go to a decoy |
| snipers=true (Gauntlet's sniper towers) | 36 | +1 / -5 |
| freeze_cut=false (new: no wait for the armory site when the quarters stood first) | 39 | +0 / -1 over the 9 games it touches |
| expand_ticks=21000 | 40 | the same games |
| reinforce_ratio=0.3 (0.5) | 37 | +9 / -12 |
| chicken_hunters=3 (7) | 36 | +10 / -14 |
| tower_builders=5 (8) | 37 | +14 / -17; w10 +4.0 (z 3.9), outs@15 +0.21 (z 2.0): the later-towers pattern again |
| hold_early=2 (4) | 40 | +9 / -9 |

Any change flips 10-13 games of the 100 each way (rally +8 / -11, bypass +11 / -14, later towers +13 / -13): a
quarter of the maps are decided by small things, and only effects of +10 or more show in 100 games.

### A steadier reading: the state at minute 20 (lab/fable/proxy.py)

A logistic model of "won" from the census at minute 20, fitted on the 300 games of the defaults at N=15
(exam-f2-vs15, cnt-vs15), is right 84 % of the time. Log-odds per unit: a copy with a quarters or armory -0.92, an
enemy warrior -0.008, a warrior of ours +0.009, a peon -0.015, an iron harvested +0.011, a tower +0.25, a quarters
+0.29. Its mean over an arm's games has a paired standard error of 2-3 points where the win count has 5, and it agreed
with the wins on every arm (none positive; later towers +0.4 +- 2.9, convoy -18.6, siege -21.6, trim -8.3). It sees
nothing after minute 20.

### The towers before minute 15, and two ways to feed them that did not work

Enemy warriors killed within 17 cells of a standing tower of ours, a game (lab/fable/towerkills.py, cnt-vs15): 0.3 in
minutes 5-9, 1.5 in 9-12, 6 in 12-15, 44 in 15-20, 120 in 20-25, for 6 and 14 of our warriors in the last two and 1.4
and 3.5 towers. A wave that targets a tower of ours loses 26-30 warriors for 2-3 of ours (lab/fable/killzone.py).

- Later towers (above): the peons and gunners they free are an army 6-9 warriors larger at 10 min and 0.2-0.36
  copies more out, and 7-13 warriors fewer at 15-20 min when the towers go up instead. No win either way.
- slaughter (new, removed): shepherds of copies whose wave origin stood within 110 cells of a manned tower led the
  wave towards it instead of away from our start: first onto a pad 28-36 cells from the tower, then onto a kill cell
  within 14 cells of a manned tower and 10 or more along an axis from every building of ours, where an idle wave sees
  nothing and the tower reaches it. The two launches of six logged games that landed on such a cell cost the copies
  39 and 114 warriors. But from the pad 4.7 launches a game went at the tower itself and 0.3 at the shepherd: at
  launch he had no spot or was still walking, and a gatherer or the tower was the nearer target. Tower kills before
  minute 15 did not rise (2.3 against 6.2). Shepherds from 240 s instead of 120 s cost Gauntlet half its wins, so
  simply letting early waves through is no alternative.

## Third session (2026-11-15/16): the economy after minute 12

Asked to step back from single-parameter screens and look for fundamentals. The first accounting was of peon time.

### The late economy runs at a quarter of its early yield

exam-f2-vs15 (200 games), won / lost: at 25 min we have 57 / 30 warriors and 119 / 108 peons outside buildings plus 40
inside, the armory holds no iron and no rock, and iron comes in at 11 a minute. Per peon outside, wood + iron + rock
harvested: 1.55 a minute in minutes 8-10, 0.41 in minutes 20-25. Iron gatherers stay at ~21 from minute 8 to 30 while
the plan's modelled iron cycle is 37 s at 10 min and 79 s at 20 min; the real cost of a unit of iron per gatherer
(census harvest over gatherers, 12 logged games) is 42-50 s until minute 10, 85 s at 14, 151 s at 16 and 195-213 s
from minute 18 to 24: three times the model. Wood stays on its model (31-36 s).

### Where an iron gatherer's time goes (TripTrace, lab/fable/trips.py; 12 logged games, trips2-vs15)

`TripTrace` (log only) times every iron gatherer from order to delivery and records how each trip ends.

| min | trips a game | delivered | recall by the old armory | flight | ordered in, no enemy by | dead | other work |
|---|---|---|---|---|---|---|---|
| 4-8 | 60 | 85 % | 0 | 1 % | 3 % | 3 % | 8 % |
| 8-12 | 112 | 72 % | 4 % | 7 % | 7 % | 3 % | 7 % |
| 12-16 | 82 | 69 % | 6 % | 8 % | 6 % | 3 % | 9 % |
| 16-20 | 50 | 40 % | 17 % | 19 % | 12 % | 2 % | 10 % |
| 20-24 | 57 | 36 % | 10 % | 28 % | 17 % | 2 % | 8 % |
| 24-28 | 53 | 42 % | 5 % | 29 % | 17 % | 1 % | 5 % |

(Shares of iron gatherer-seconds. A loaded gatherer that walks into an armory delivers there and counts as
delivered.)

- **The recall loop.** The engine links a gatherer to the armory nearest its supply (Unit.nearestSupplyBuilding),
  not to the one that sent it. From the expansion armory on (7-8 min, ~100 cells from the first) every supply nearer
  the first armory links its gatherer there; drainSecondary recalls the old armory's gatherers every 10 s, and
  allocatePeons, which counts only the main armory's, sends the next peon the same second. 135 such recalls a game in
  minutes 16-20. Gauntlet's notes knew the loop only as the cause of two 5-hour draws ("real but rare").
- **Flights.** evacuatePeons sends every peon with a threat within 11 cells to the main armory or a quarters,
  however far (iron is picked up 49-62 cells out from minute 16). "Ordered in, no enemy by" happens almost only at
  threat level 1-2 (29 of 1,696 at level 0), so most of it is the same flight seen a second late. Together a third to
  a half of the gatherers' time from minute 16.

## Reproduce

```bash
./aisim.sh build
./aisim.sh freeze f2 fable                     # or play `fable` itself: it has no params to set
EXAM=1 ./lab/fable/dev.sh exam-f2-vs14 "@f2 vs hard*14" 36001..36200
EXAM=1 ./lab/fable/dev.sh exam-g16-vs14 "@g16 vs hard*14" 36001..36200   # Gauntlet: freeze g16 from its branch first
python lab/fable/stats.py --pair exam-g16-vs14 exam-f2-vs14
```

Gauntlet for the baseline: `git archive origin/gauntlet tt/src/main/java/com/oddlabs/tt/player/gauntlet | tar -x`,
`./aisim.sh build`, `./aisim.sh freeze g16 gauntlet`, then remove the folder again.

## Where the headroom is

After the second session: the defaults sit at a local optimum of everything tried. What moved results strongly was
always tempo (anything that waits loses 10-20 wins of 100) and the chieftain's timing (240 s and 420 s both worse
than 300 s). What the measurements leave open: the open-field war from minute 15 (65-80 % of our warrior deaths, at
2.3 to 1, against copies not yet attacked), the stun (a median of 6 units a cast, 3 casts in 5 minutes of 7
possible), and a kill cell that works (two launches of that kind were worth 39 and 114 enemy warriors).


- Maps decide: ore within reach of the base sets how big the army is in minutes 8-15, and that sets the result. The
  rock next to the armory could not be turned into army (above); a build that could would be worth more than any
  tuning.
- N=15 is 35 %: the campaign still takes about four copies by 20 min at every N, and from 15 min each remaining copy
  stands at the unit cap with two towers and a chieftain.
- The opening strike leaves its copy frozen (alive, never armed) in 7 % of games, and those are won 15-20 % of the
  time against 45-50 % otherwise, for Gauntlet too; not looked into.
