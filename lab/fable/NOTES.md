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

Third session, the opening ("The slow openings are iron behind water" below): the first armory's site now pays for
the iron it lacks within reach (`armory_iron_need` 50, `armory_iron_far` 800, the default since). On development
maps at N=15 the maps whose armory moves win 34 against 23 of 102 (fresh maps 21301..21600) and 28 against 23 of 85
(21001..21300, 124 of 300 against 119); no exam seeds were spent on it, so the exam table above is still `@f2`'s.

Third session, the long game ("The long game" and "Where the open games part" below): a gatherer that steps out
of an enemy's way now steps towards our nearest manned tower (`evade_tower` 45, the default since): 139 wins against
126 on 300 fresh development maps at N=15 (46 %), all of the gain in games still open at minute 30. Seven other
repairs of late breakdowns (idle forgers, unstaffed satellites, small late launches, the spent chieftain, the
expansion's threat gate, a weapon bank, lures) are built and neutral, and stay off. No exam seeds were spent.

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
python lab/fable/trips.py RUN                                  # where the iron gatherers' time goes (logged runs)
python lab/fable/screen.py plan|read NAME                      # eight params at once in 16 runs (2^(8-4))
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

### Three systems for the gatherers (flags, off by default; the default plays `@f2`'s games, 8 of 8, same4-vs15)

- **satellite**: a finished armory that is not the main one keeps working: its iron gatherers stay (the engine links
  them to it anyway), ore is picked by its walk to the nearest armory, the plan is one pool, and each satellite gets
  the share of the wood gatherers and forge workers that its share of the iron gatherers asks for (the rest of the
  quarters' peons walk on to the main armory as before). First version (sat1-vs15): 36 wins against 40 and 3.5
  fewer warriors at 10 min (z -4.0): the satellite recalled its wood gatherers before its first plan and the main
  armory's retargeting took the harvesters it deployed. Fixed (sat2-vs15): 35 wins (+11 / -16), minute-20 model -0.6
  +- 2.6, 2.1 more warriors at 10 min (z 2.9). Recalls 135 -> 4 a game in minutes 16-20, delivered share 57 / 45 / 53 %
  in minutes 16-28 against 40 / 36 / 42 %, iron by 20 min +3 % (323 / 290 won / lost against 314 / 282).
- **evade_cells=30**: a gatherer with a threat within 11 cells and no quarters or armory within 30 steps 12 cells out
  of the enemy's way (straight away first, then +-40 and +-80 degrees, to a reachable cell with no enemy within 11)
  and gathers again once none is within 14. 432 evasions a game, 336 resumed, 65 given up, 31 died; flights fall
  from 19-29 % of the gatherers' time to 4-10 %, but every evasion still ends the trip, and iron a minute does not
  move (12 logged games).
- **iron_real=3**: the plan staffs iron by the measured gatherer-time per unit (a running mean of the 30-s windows),
  up to 3 times the modelled trip: 28-38 iron gatherers instead of 21-26, iron a minute 11.0 against 8.5 in minutes
  16-30 at 150-240 s a unit (12 logged games); in minutes 8-14 the same iron with 4-7 more gatherers.
- Iron by 20 min in the 12 logged games of each: 286 default, 299 satellite, 284 evade, 302 iron_real, 316 all
  three. What the gatherers flee from in minutes 16-28 (trips3-vs15): 291 flights a game from a moving enemy within
  11 cells (131 from a group of 2-5, 91 from a single warrior, 70 from 6 or more), 35 from parked ones; 85 % start
  more than 20 cells from the main armory. The shepherds are not the cause: of 66 waves a game led to a shepherd in
  minutes 14-28, 4 end within 60 cells of the newest armory. So the late field is a battlefield whatever the
  gatherers do, and the three systems together buy about a tenth more iron.

### The opening strike: what the engine allows

A Hard's first quarters stands at 73 / 83 / 110 s (10 / 50 / 90 %, cnt-vs15) and its first armory at 138 / 154 / 208
s. From our start the nearest copy is 133 m away (straight, median), the second 148 m, the third 264 m, the fourth
282 m, the fifth 392 m; a peon walks 5 m/s. So path (a) (every peon dead before the quarters stands) is in reach of
a squad that leaves at once for the four nearest copies, and nothing that leaves later reaches a copy before its
armory stands. freeze_chain (new, removed: the squad goes on to the next copy without a finished armory) chained
1.4 times a game and froze 1 copy in 24 games.

### Screens of the third session (N=15, dev seeds 21001..21100, default cnt-vs15: 40 wins)

| arm | wins | notes |
|---|---|---|
| satellite (first version) | 36 | w10 -3.5 (z -4.0): the wood bug above |
| satellite | 35 | +11 / -16; model -0.6 +- 2.6; w10 +2.1 (z 2.9) |
| iron_real=3 | 37 | +15 / -18; model -0.5 +- 2.8 |
| **satellite + evade_cells=30 + iron_real=3** | **45** | +16 / -11; model +4.1 +- 2.3 (z 1.8); outs@20 +0.22 (z 1.8), outs@30 +0.51 (z 3.8), w10 +2.5 (z 2.6) |
| freeze_targets=2 (two opening strikes at once) | 31 | +6 / -15 (z -2.0); model -5.8 (z -2.2); 1.72 copies out or frozen by the strikes against 0.86, outs@10 +0.16, but w10 -13.1 (z -7.8): by 10 min the campaign has taken that second copy anyway, and the 6 peons away cost the army of minutes 8-12 |
| freeze_targets=3 / 4 with freeze_eta2 70 s | stopped after 5 / 6 games | the squads for the third and fourth copy (264 / 282 m) arrive after its quarters stands: 1.4-2.3 strikes given up and 6 peons lost a game |
| shred=true (Gauntlet's blast mission) | stopped after 27 games | 0.5 blasts a game: the stun in every fight keeps zeroing the shared charge |

Singly the three gatherer systems are within the noise below the default; together they are the first arm above it
at N=15 (confirmation on fresh maps: below).

Built, checked in logged games and removed:

- **wing** (a second attack force: every other group of reinforcements from 8 min until it has 30-36 warriors, then
  the nearest copy the main army is not at). With the base's defense at most 0.6 of its strength it never found a
  target (37 refusals a game). At 1.3 it left 1.8 times a game, lost 31 warriors a game and took no base: 9.75
  copies with a base at 20 min against 9.00, 72 warriors of ours against 98 (12 logged 25-minute games). A force of
  25 crossing the map is ground down like the reinforcements are; the single army is not an accident.
- **freeze_chain** (above).

Where the lost games end (300 default games at N=15): with 10 or more copies still housed in 79 of 189, 7-9 in 99,
4-6 in 11, never with 3 or fewer; 8 before 25 min, 80 in minutes 25-40, 66 in 40-70, 35 later. Wins end at a median
of 87 min (10 %: 62, 90 %: 138). So no game is lost close to won: the campaign stalls at 5-8 copies taken, and the
rest is a long siege of our base.

### The three systems confirmed, and made the default

On 200 fresh maps (dev seeds 21101..21300, `base-b-vs15` against `sei-b-vs15`): 72 wins against 81 (+32 / -23,
z 1.2); the state model at minute 20 +7.9 +- 1.8 points (z 4.3), at minute 30 +8.3 +- 2.5 (z 3.3); survival +1.5 min
(z 2.0). With the 100 screening maps: 112 of 300 against 126 (+48 / -34, z 1.55). So the gain is about 4-5 points
of wins, not more. They are fable's defaults since `58a2e74b` (`same5-vs15`: 8 of 8 games identical to the arm's).

What they change, by minute (`lab/fable/flow.py base-b-vs15 sei-b-vs15`): 3-4 more iron a minute from minute 20
(12.2 against 8.8 at 25 min) and 3-4 less rock, the same number of warriors (43 and 43 at 25 min, 38 and 38 at 30)
with 7 more iron and 7 fewer rock ones among them. The larger iron staffing took the rock gatherers' peons.

A bug of the satellite code rode along in all of those runs: `sendGatherer` read the controller of a peon that an
order earlier in the tick had already sent into a building, the assertion was swallowed, and the rest of that
economy tick was lost: 818 times in 170 of the 200 `sei-b-vs15` games. Fixed in `5a59bf3f` (a smoke game: none).

### The eight-factor screen (`lab/fable/screen.py`, runs `scr1-00..15`, 40 maps each, on the old defaults)

| factor | wins | model at 20 min | model at 30 min |
|---|---|---|---|
| satellite | +5.3 +- 3.6 | +3.3 +- 1.6 (z 2.1) | +1.9 +- 1.9 |
| evade_cells=30 | -0.9 +- 2.3 | -1.3 +- 0.9 | +0.2 +- 1.3 |
| iron_real=3 | -2.2 +- 3.9 | -1.6 +- 1.8 | -1.4 +- 2.3 |
| gather_route_clear | +0.3 +- 1.8 | +0.8 +- 1.1 | +0.3 +- 1.2 |
| reinforce_multi | +5.3 +- 3.5 | 0.0 +- 1.2 | +1.5 +- 2.1 |
| capped_min_strength=60 | -2.2 +- 2.9 | -1.6 +- 1.0 | +1.1 +- 1.8 |
| worn_ratio=0.35 | -0.9 +- 2.4 | -1.5 +- 0.9 | +0.6 +- 1.6 |
| hold_late=4 | +0.3 +- 2.8 | +3.0 +- 1.7 | +1.2 +- 2.0 |

Forty maps are too few: a factor's effect on wins has a standard error of 2.3-3.9 points, so only the satellite
stands out, and only in the model. The late launch thresholds do nothing. The three worst runs (25, 25 and 28 %)
are the three with changes but neither the satellite nor reinforce_multi; that pair's interaction is aliased with
three others in this design and was not followed up. Later screens use 8 runs of 100 maps.

### The army by minute: it melts from minute 15 and is not rebuilt

Medians over the 200 `base-b-vs15` games (won 72, lost 127):

| minute | our warriors (won / lost) | our peons | copies housed | enemy warriors, all | per copy (median / largest) |
|---|---|---|---|---|---|
| 8 | 49 / 44 | 167 / 164 | 14 | 250 | 20 / 25 |
| 12 | 124 / 112 | 108 / 122 | 12 | 318-344 | 30 / 36-40 |
| 15 | 146 / 126 | 86 / 101 | 10-11 | 377-408 | 38-40 / 51-53 |
| 20 | 102 / 78 | 118 / 140 | 8-9 | 392-483 | 49-51 / 78-80 |
| 25 | 50 / 30 | 164 / 158 | 7-9 | 363-524 | 48-60 / 80-92 |
| 30 | 58 / 14 | 150 / 145 | 6-9 | 398-530 | 55-60 / 88-100 |
| 40 | 62 / 4 | 149 / 124 | 5-9 | 350-502 | 56-57 / 94-107 |

The unit cap is 240 for warriors and peons together. From minute 15 we lose 30-39 units a minute and arm 6-17: iron
comes in at 21-25 a minute until minute 15 and at 12-14 (won) or 6-7 (lost) after minute 20, and nothing is ever in
stock. The cap fills up with peons. A lost game is 10-20 minutes with 4-14 warriors, ten towers and 140 peons.

### What a unit of each ore really costs (`lab/fable/cost.py`, 12 logged games, `all1-trips-vs15`)

| minutes | iron gatherers | gatherer-seconds per iron | rock gatherers | per rock | per wood |
|---|---|---|---|---|---|
| 10-14 | 49 | 113 | 2.4 | 48 | 33 |
| 14-18 | 45 | 191 | 2.7 | 56 | 60 |
| 18-22 | 51 | 330 | 2.8 | 66 | 53 |
| 22-26 | 55 | 437 | 3.9 | 59 | 52 |
| 26-30 | 63 | 423 | 4.6 | 64 | 70 |
| 30-36 | 58 | 314 | 4.1 | 99 | 77 |

A node holds 10 units, so the near iron is gone by minute 15: the ORE lines show 16-43 iron nodes left in reach
with the nearest 140-170 m away, and 120-180 rock nodes with the nearest 45-110 m away. From minute 18 a unit of
iron costs five to seven times a unit of rock, and iron's returns fall with every gatherer: halving the iron
gatherers (rr1) lowered the cost to 250-310 s, so the second half of them brought a unit per 690 gatherer-seconds.
An iron warrior then costs about 570 peon-seconds (80 forging, 2 wood, the iron) and a rock one 220 (40, 2 wood,
the rock) for 0.6 of its strength (rock axes hit for 1, iron for 2; the same hit points).

The plan could not see it: its iron trip is the walk to the nearest three nodes (70-80 s), which is why the first
session's "rock only while it is the cheaper line" fired in 20 of 100 games for 0.3 min. And the rock gatherers that
`rock_idle` asks for from minute 20 never went out: peons are handed out wood, iron, rock in that order, and iron
is never fully staffed late.

### A rock line (`rock_real`, off by default)

From 14 min, at every yield measurement (30 s), the line's share of the armory's pool goes up 0.15 while
`rock_real x (80 s + 2 wood + iron as measured) / (40 s + 2 wood + rock as measured)` is above 1.1 and down below
0.9, up to `rock_real_share`. The share is rock gatherers (by rock's measured trip), their wood and their forgers;
they are staffed before the iron gatherers; iron is forged first (`rock_real_iron_first`: no rock axe on order
while 3 iron are in stock, as the forge splits its work evenly between the kinds it can make); and while six
weapons could be forged from the stock a quarters holds 2 peons, not 8 (`rock_real_hold`: a held peon beyond the
second breeds under 1.4 peons a minute, a forger with ore makes 1.5 rock axes). `tree_real` staffs wood by its
measured cost in the same way as `iron_real` does iron (with the line on, the armory held 10-22 rock and 1-3 wood).

Twelve logged games each (`lab/fable/flow.py`; maps 21001..21012, means over the games alive at that minute in
all three runs):

| | warriors (rock) at 25 min | at 30 | at 40 | iron + rock a minute at 30 | copies housed at 40 |
|---|---|---|---|---|---|
| no line (`all1-trips-vs15`) | 53 (7) | 35 (5) | 50 (4) | 9.7 + 5.1 | 6.0 |
| first version: half the pool once iron costs 120 s (`rr1`) | 58 (11) | 45 (20) | 66 (29) | 6.9 + 11.2 | 4.9 |
| share by measured costs, value 0.6, at most 0.7 (`rr3`) | 68 (20) | 55 (18) | 60 (24) | 6.3 + 9.2 | 5.1 |

With value 0.9 and a share up to 0.85 one game reached 177 warriors (46 rock) at 20 min against about 100, with 60
peons left, and iron fell to under 4 a minute: the settings trade iron for bodies, which is what the screen below
is for.

### The rock line's screen: it trades iron warriors for rock ones (`rk1-00..07`, 100 maps each)

Base: the fixed defaults on the same maps, `def3-vs15`, 41 wins (the run with the assertion bug had 45: +3 / -7,
model -0.2 +- 0.9, so the fix itself is neutral).

| run | value | most share | tree_real | iron_real | wins | model at 20 min against the base |
|---|---|---|---|---|---|---|
| rk1-00 | 0.6 | 0.55 | - | 3 | 41 | +1.1 +- 1.6 |
| rk1-01 | 0.9 | 0.55 | - | 0 | 31 | -2.5 +- 2.3 |
| rk1-02 | 0.6 | 0.85 | - | 0 | 34 | -3.2 +- 2.6 |
| rk1-03 | 0.9 | 0.85 | - | 3 | 31 | -3.9 +- 1.9 |
| rk1-04 | 0.6 | 0.55 | 2.5 | 0 | 38 | -1.1 +- 2.5 |
| rk1-05 | 0.9 | 0.55 | 2.5 | 3 | 35 | -4.5 +- 2.1 |
| rk1-06 | 0.6 | 0.85 | 2.5 | 3 | 37 | +0.1 +- 2.2 |
| rk1-07 | 0.9 | 0.85 | 2.5 | 0 | 34 | -4.4 +- 2.4 |

Effects on wins, paired by map: value 0.9 -4.8 +- 2.5 (z -1.9), share 0.85 -2.2 +- 1.9, tree_real +1.8 +- 2.2,
iron_real off -1.8 +- 3.3. The mildest line equals the base; every bolder one is below it.

Why (`lab/fable/flow.py def3-vs15 rk1-00 rk1-03`, means over the games alive in all runs):

| at 25 min | warriors (rock) | iron + rock a minute | peons |
|---|---|---|---|
| base | 45 (6) | 10.7 + 3.7 | 157 |
| rk1-00 | 53 (19) | 7.3 + 9.9 | 144 |
| rk1-03 | 52 (32) | 2.8 + 14.6 | 137 |

Weapons a minute go from 14.4 to 17.3, a fifth more, while iron falls by a third to three quarters. At Combat's 0.6
for a rock warrior the strength made a minute is 12.9, 13.2 and 11.6: flat or down. The average cost of iron (five
to seven times rock's) overstated what moving a peon buys: the pool that makes weapons is about 90 of the 140
peons, a rock axe still costs 2 wood at 52-100 gatherer-seconds each, and iron given up was not as dear at the
margin as the 12-game sample suggested. And a rock warrior is worth less than 0.6 to the campaign, which lives on
damage: at an even strength the rock-heavy runs kill and raze less.

So the line pays only if it is additive: fed by peons that make nothing now, with the iron line left alone. The
candidates are the peons the quarters hold: from 70 % of the cap on a quarters holds 8 (hold_late), 40 in all, to
breed 65 peons a minute when 30-36 units a minute are lost; 2 each would breed 41. Lowering the hold alone was
neutral twice (second session, and scr1 above), when the freed peons went to the iron line. `rock_real_peons` caps
the line at a number of peons, and the screen `rk2` crosses a line of 30 peons with hold_late 3 and tree_real.

### The capped line with a lower hold: nothing (`rk2-00..07`, 100 maps each; `rk2-00` is `def3-vs15`)

| run | hold_late | rock line of 30 peons | tree_real | wins | model at 20 min against the base |
|---|---|---|---|---|---|
| rk2-00 | 8 | - | - | 41 | |
| rk2-01 | 3 | - | - | 38 | +4.8 +- 2.3 |
| rk2-02 | 8 | yes | - | 45 | +0.1 +- 1.7 |
| rk2-03 | 3 | yes | - | 41 | +4.2 +- 2.3 |
| rk2-04 | 8 | - | 2.5 | 36 | +0.5 +- 1.9 |
| rk2-05 | 3 | - | 2.5 | 41 | +2.1 +- 2.2 |
| rk2-06 | 8 | yes | 2.5 | 39 | +1.7 +- 2.2 |
| rk2-07 | 3 | yes | 2.5 | 38 | +2.3 +- 2.2 |

Effects on wins: hold_late 3 -0.8 +- 3.1, the line +1.8 +- 2.3 (z 0.8), tree_real -2.8 +- 2.6. No setting is apart
from the base. By minute (`flow.py rk2-00 rk2-01 rk2-03`): the lower hold has 91 warriors against 84 at 20 min and 40
against 45 at 25, 33 against 39 at 30, 48 against 57 at 40: the peons it frees arm the army of minute 20 and are
missing from the breeding afterwards, which is why the minute-20 model likes it and the wins do not. The capped line
still takes its iron: 43 warriors (12 rock) against 45 (6) at 25 min, iron 7.7 a minute against 10.7.

The rock line is closed: `rock_real` stays in the code, off. Three sessions and Gauntlet's notes agree that rock
cannot be added to the iron line by any staffing rule: the weapon pool, not the ore, is what is short.

### Where the war is fought, and how the copies arm (`lab/fable/zones.py`, `towerkills.py`; `fd0-log-vs15`)

Twelve logged 40-minute games of the defaults, from minute 12, a game:

| zone (cells from our start) | enemy warriors dead | our warriors dead | for one of ours |
|---|---|---|---|
| home (up to 70) | 82 | 19 | 4.3 |
| middle (70-200: the forward armory, the near copies) | 666 | 186 | 3.6 |
| far (beyond 200) | 294 | 174 | 1.7 |

Within 17 cells of a standing tower of ours: 68 enemy warriors for 8 of ours in minutes 15-20, 136 for 13 in 20-25
(about 10 for one, and 1.0 and 3.1 towers razed); everywhere else 153 for 83 and 145 for 85 (1.7-1.8 for one). A
launch aimed at a tower of ours (`killzone.py`: 10 a game) costs the copy 35 warriors and us 4 and half a tower.

From `AdvancedAI`: a copy launches its first N idle warriors when it has N of them idle (N = 10, plus 5 a launch, at
most 40; with a chieftain from 20 on), aimed from the oldest; it arms new warriors only while fewer than N stand
idle. So a wave that falls below N stands idle where it stopped (under our towers it dies there), a copy's
production follows our kills (a copy needs 5-10 minutes to raise the next 40), and the copies hold 50-60 warriors
each from minute 20 whatever we kill. Units have one hit point: a throw kills or misses.

### Feeding waves to our towers (`shepherd_feed`, off; logged runs `fd1..fd9-log-vs15`, maps 21001..21012)

The shepherds lead waves away from our base, where they end up as the groups the army and the gatherers meet in
the field. `shepherd_feed` turns that round from minute 12, while six towers are manned: the spot is the ring cell
nearest the nearest manned tower that has another within 14 cells, and the flee runs towards it.

| run | what | tower kills 15-20 / 20-25 / 25-30 min | enemy dead at home | our warriors dead (home / middle / far) | towers / quarters razed | our peons dead at home | warriors at 20 / 25 / 30 min | lost by 40 min |
|---|---|---|---|---|---|---|---|---|
| fd0 | defaults | 68 / 136 / 118 | 82 | 19 / 186 / 174 | 10.4 / 3.3 | 45 | 99 / 46 / 37 | 3 |
| fd1 | spot only, 2 copies | 53 / 148 / 123 | | | | | 91 / 36 / 45 | 2 |
| fd2 | spot only, all copies | 71 / 115 / 91 | | | | | 91 / 44 / 36 | 4 |
| fd4 | spot and flee, all copies | 140 / 184 / 128 | 182 | 19 / 154 / 148 | 15.9 / 6.3 | 81 | 86 / 49 / 32 | 6 |
| fd5 | the same, flee weight 1.5 | 172 / 203 / 147 | 234 | 19 / 131 / 140 | 19.4 / 7.1 | 126 | 81 / 43 / 24 | 5 |
| fd6 | the 3 nearest waves, none joins while 30 enemy warriors stand by the towers | 97 / 147 / 142 | 142 | 14 / 171 / 159 | 13.7 / 4.8 | 63 | 90 / 40 / 23 | 5 |
| fd7 | the 6 nearest, 50 | 121 / 150 / 152 | 198 | 28 / 156 / 154 | 18.1 / 6.7 | 109 | 80 / 35 / 18 | 6 |

- The spot alone does nothing: a shepherd flees a third of its time, the wave goes where he stands, and the flee
  scored cells gained away from our start. With the flee turned too the waves come: 100-150 more enemy warriors die
  at home a game for no more warriors of ours.
- A wave follows the nearest shepherd, not its own copy's (`target unit_by_shepherd, nearest unit shepherd of
  s14:hard 1 cells, own shepherd 54 cells`), so feeding some copies and leading others away mixes the two.
- Fed with everything, the copies hold a fifth to a third fewer warriors from minute 25 (344 against 431 at 25
  min, 202 against 352 at 35): their production does not keep up.
- But the waves come through the whole base: 5-9 more towers, 1.5-4 more quarters and 20-80 more peons a game are
  lost at home, iron falls from 12.5 to 8-11 a minute at 20 min, and the army is smaller from minute 20 on in every
  version. Our own losses in the field fall by 30-90 warriors, partly because the army is smaller.
- `feed_quarters_clear=45` (only towers with no quarters within 45 cells: fd8, fd9) fed nothing: no such towers
  stand. A killing ground away from the economy has to be built for the purpose.

So the mechanism is real and its price is the economy at home. Not screened on 100 maps: every version is behind
the defaults in the 12 logged games.

A bastion (`bastion=5`, off): from 8 min five of our towers stand together about 50 cells from the home armory on
the enemy's side, 40 cells or more from every quarters and armory, and a tower that falls there is the next one
built (map 21001: the point at 200,305, 66 cells from the home armory and 50 from the forward one; five towers
within 12 cells of it between 8:38 and 10:58). `feed_bastion` leads fed waves only there.

| run | tower kills 15-20 / 20-25 / 25-30 min | our warriors dead (home / middle / far) | towers razed | our peons dead at home | iron a minute at 20 / 25 min | warriors at 20 / 25 / 30 min | lost by 40 min |
|---|---|---|---|---|---|---|---|
| fd0, defaults | 68 / 136 / 118 | 19 / 186 / 174 | 10.4 | 45 | 12.5 / 8.3 | 99 / 46 / 37 | 3 |
| fb1, bastion alone | 59 / 182 / 161 | 16 / 184 / 181 | 15.2 | 73 | 14.2 / 11.8 | 87 / 42 / 40 | 5 |
| fb2, all waves fed to it | 145 / 197 / 109 | 20 / 153 / 146 | 17.8 | 75 | 8.5 / 5.6 | 80 / 38 / 29 | 5 |

The bastion takes the waves (5-6 of its towers fall in each of minutes 15-25) and the attack loses 57 warriors
fewer a game, but iron still falls by a third to a half in minutes 20-25: the fed waves cross the ground the
gatherers work on their way in. Feeding is closed as built: what it saves the army in the field, it costs the
economy, wherever the waves are sent. Both stay in the code, off.

### Waves led away from the attack (`shepherd_army_weight`, off; `sa1`, `sa2-log-vs15`)

"Away from our start" is towards the far copies, where the army goes next and trades at 1.7. With the weight a spot
also scores its cells from what the attack is fighting for (up to 120). Twelve logged games each: our warriors dead
in the far zone 181 (weight 2) and 172 (weight 5) against 174, enemy warriors dead there 287 and 302 against 294;
warriors at 20 / 25 / 30 / 35 min 84 / 44 / 33 / 31 and 106 / 57 / 30 / 65 against 99 / 46 / 37 / 52. No effect that
twelve games can show: the far fights are not made of the groups the shepherds park.

### Screen `sb1`: bastion x army weight x tower rebuilding, 100 maps each: nothing

Bastion alone and the weight of 5 were the two arms the logged games could not call (iron and tower kills up with
the bastion, the army up at three of four minutes with the weight), so they went to 100 maps with
`tower_parallel_late=3` (three tower sites at a time from 10 min, for the towers the bastion loses):
`python lab/fable/screen.py read sb1`, base `sb1-00` = `def3-vs15`.

Wins by run 41 37 43 40 39 38 45 41 (mean 40.5). Main effects: bastion -3.0 +- 2.6, army weight +3.5 +- 3.1, three
tower sites +0.5 +- 2.4; the minute-20 model sees the weight at -2.3 and -1.2 in the two runs that won most, so the
+3.5 is noise. All three stay off.

Five of the seven runs had one game with a swallowed `AssertionError` (1 to 3 times, 52 to 99 minutes in):
`sendGatherer` asked a peon for its controller after a gather order that a loaded peon standing by its armory
answers by delivering at once, so it was inside the building. Fixed (f1736088); the default's games are unchanged
(8 of 8, `fix-d`).

### On whose ground warriors die (`lab/fable/ground.py`; any run, no logs needed)

`sb1-00` (the defaults, 100 maps), warriors dead a game, enemy / ours = exchange:

| minutes | our towers (17 cells) | our base (40 cells) | the field | their base (30 cells) | their towers (17 cells) |
|---|---|---|---|---|---|
| 12-20 | 53 / 8 = 7.0 | 24 / 12 = 2.0 | 98 / 68 = 1.5 | 50 / 23 = 2.2 | 52 / 36 = 1.5 |
| 20-30 | 231 / 24 = 9.5 | 74 / 31 = 2.4 | 66 / 61 = 1.1 | 26 / 13 = 2.0 | 33 / 20 = 1.6 |
| 30-60 | 428 / 39 = 11.0 | 142 / 60 = 2.4 | 108 / 73 = 1.5 | 41 / 19 = 2.1 | 36 / 27 = 1.3 |

Of about 510 warriors we lose a game from minute 12, 200 fall in the field at 1.1 to 1.5 and 83 under enemy towers at
1.3 to 1.6. `lab/fable/lostfar.py` (logged games) splits the losses by role and company: under their towers it is the
attack in a big group (60 of 80 with 31+ mates within 12 cells) with few enemy warriors about (52 of 80 with at most
three within 14 cells): the garrison's work. In the field it is the attack (72: half in real battles against ten or
more), reinforcements on their way (33, two thirds alone or in small groups against ten or more) and the home army (14).

### What a throw is worth, and what a tower is

Read in the engine (`RacesResources`, `WeaponFactory`, `InstantHitFactory`): a throw hits with (weapon + terrain)
x (1 - the target's defense): iron axe .75, rock .5, rubber .95; defense iron warrior .7, rock warrior .5, peon 0,
building 0, stunned unit 0. So iron on iron is .225 a throw, on a stunned warrior .75, and a tower's garrison (three
times the chance) .675: one manned tower is three iron warriors that take 133 axe throws (100 hit points at .75 a
throw) instead of 4.4 each. A peon's or a chieftain's swing at a tower always does 6: 17 swings.

`lab/fable/etowers.py` on `sb1-00`: 25.6 enemy towers a game, 15.7 razed; 148 of our warriors die within 17 cells of
one (deaths in reach of two count twice), in 565 s of dying: 0.26 a second, 9.4 warriors a razed tower. The 5.6 towers
a game with ten or more dead hold 64 % of them and stand a median 63 s after the first death. Why (logged games):
the sappers trail six cells behind the army's centre and go only for a tower within 18 cells of that centre with
little about; an army jammed in the trees sends its head into the towers in ones and twos while its centre stands 25
cells back (s21001: 37 warriors in 155 s under two towers with 130 in the army and not one sapper order); and the
refill takes the gatherers nearest the army, two every 5 to 10 s, which walk to it alone (s21001: 108 peons).

### A breach drill for towers (`breach`, off): three versions, the mechanism never engaged

Logged runs on maps 21001..21012 (40 min), `lab/fable/etowers.py`; `fd0-log-vs15` is the default.

| run | setting | our warriors dead in reach of an enemy tower | peons | first death to raze (10+ towers) |
|---|---|---|---|---|
| fd0 | default | 104 | 18 | 41 s |
| br1 | breach (12 sappers at the army's head, charge on contact, refills walk with reinforcements) | 101 | 45 | 53 s |
| br2 | + breach_axes=16 (warriors by the tower throw at it) | 92 | 35 | 46 s |
| bg1 | breach + breach_gate=24 (warriors hold 19+ cells out until assembled, sappers go in alone first) | 100 | 40 | 57 s |
| bg2 | + breach_axes=40 | 118 | 46 | 52 s |
| bg3 | gate without the solo | 103 | 44 | 64 s |

A charged tower stands 36 s (4 traced games, 36 towers), of which 22 s pass before anything damages it, and 5.5
warriors and 2.1 peons die in its reach meanwhile: 0.16 warriors a second. The sappers stand behind a column that is
jammed in the trees and reach the tower 10 to 30 s after the first warrior; the gate opened "late" (15 s without
assembly) 8 times a game, "ready" 5 times, and a solo began 3 times. A peon's swing takes 2.2 s, so seven sappers at a
tower bring it down in 5 s once they are there. Closed as built: the range (15.9 cells against our 6) and the forest
decide this, not the order of the charge.

### The home army at a tower (`hold_ratio`, `hold_multi`; Gauntlet's flags, never tried at N=15)

Of 122 warriors a game lost on our base ground outside our towers' reach (`sb1-00`), 36 fall 17-22 cells from the
nearest tower at 3.6 to 1, 44 at 22-30 cells at 2.0 and 37 at 30-45 cells at 1.6: the home army walks out to every
enemy within 28 cells of a building (`base_radius`). With `hold_multi=true` it meets them at the building nearest
the threat, a tower if one is close (`ho1`: `hold_ratio=0.3`, `ho2`: 1.5; 12 logged games each): under our towers
the enemy loses 88 / 344 / 215 in minutes 12-20 / 20-30 / 30-60 against 69 / 251 / 188, we lose 17 / 44 / 28 there
against 8 / 28 / 25 and 6 / 20 / 12 on the base ground against 10 / 26 / 31. The sum of our losses is the same and
so is the army (41 / 38 at 25 / 30 min against 50 / 37). Not screened.

### What decides a game: the copies still housed at minute 20 (`lab/fable/pace.py`, the 800 games of `sb1`)

| copies with a quarters or armory at minute 20 | games | won |
|---|---|---|
| 7 or fewer | 155 | 73 % |
| 8 | 222 | 60 % |
| 9 | 203 | 30 % |
| 10 | 92 | 15 % |
| 11 or more | 120 | 2 % |

The mean is 8.9. Games won and games not won part ways from minute 8: 13.6 against 13.8 copies housed at 8 min, 12.7
/ 13.2 at 10, 11.6 / 12.4 at 12, 9.6 / 10.8 at 16, 7.9 / 9.6 at 20, with 50 / 42 warriors of ours at 8 min, 91 / 76
at 10 and 139 / 114 at 14. One copy fewer at minute 20 is worth 25 to 30 points.

`lab/fable/campaign.py` (12 logged games, 55 moves from one copy to the next before minute 22): a copy takes the
army 108 s. 15 s it lingers at the old copy after its last quarters or armory fell, 40 s it marches (68 cells at
1.7 cells a second, near walking speed), 19 s pass from its arrival to the first raze, and 34 s from there to the
copy's last quarters or armory. Quarters and armory have 200 hit points each and no defense, so an iron axe does 1.5
a throw: 270 throws for the two, 9 s for a hundred warriors at a throw every 2 s. But nothing orders a warrior onto
a building: the army attack-moves, and the engine's scan takes warriors first, then peons, then towers, and quarters
and armories last.

### A raze drill (`raze`, off): the buildings fall a little sooner, the cycle is the same

With `raze` the attack's warriors within that many cells of an enemy quarters, armory, site or manned tower throw
at it unless an enemy warrior is in throwing reach. 12 logged games each, `lab/fable/campaign.py 22`:

| run | copies razed by 22 min | lingering | march | arrival to first raze | the raze | median (mean) s |
|---|---|---|---|---|---|---|
| fd0 (default) | 5.67 | 15 (16) | 40 (54) | 19 (20) | 34 (42) | |
| rz1 raze=12 | 5.92 | 21 (20) | 40 (52) | 16 (17) | 31 (32) | |
| rz2 raze=18 | 5.58 | 24 (27) | 36 (51) | 15 (15) | 29 (35) | |
| rz3 raze=12, raze_towers=false | 5.50 | 19 (20) | 44 (59) | 17 (17) | 28 (29) | |

A building stands 14 to 23 s after its first order (tower 14, quarters 20, armory 21 at raze=12), because the first
order is three to five warriors: the army is still walking in and fighting the copy's warriors (s21004: 22 of them
at the base). What the raze saves, the peons left alive cost in lingering. The cycle is about what walking and the
copy's own warriors make it; the order of the throws is a tenth of it.

### The map decides a third of the games, and the opening's speed is how (the 800 games of `sb1`)

The eight runs of `sb1` play the same opening on each map, so the first five minutes are the map's: of the 100 maps
32 are never won in 8 runs, 16 once or twice, 24 three to five times, 28 six to eight times. Correlation with winning
(800 games): our towers at 7 min +0.39, wood cut by 12 min +0.39 (-0.71 with the copies housed at 20 min), by 5 min
+0.26 (-0.52), iron by 8 min +0.34, quarters at 5 min +0.27; the distance to the nearest enemies and to the map's
edge: nothing.

| sixth quarters finished | maps | wins of 8 | first quarters | armory |
|---|---|---|---|---|
| before 260 s | 23 | 3.9 | 49 s | 206 s |
| 260-290 s | 32 | 4.0 | 49 s | 230 s |
| 290-330 s | 21 | 3.4 | 50 s | 244 s |
| 330-400 s | 9 | 2.6 | 57 s | 268 s |
| after 400 s | 15 | 0.7 | 126 s | 324 s |

Two kinds of slow opening among the 24 maps past 330 s (six replayed with logs, `op0-log-vs15`):

- the base is planned far from the start: armory 83 to 169 cells away (s21040: quarters 186 to 287 m of walking,
  the first one done at 136 s; s21054: armory at 465 m), about ten maps;
- the sites are near but some stand unfinished for minutes: s21012 has all seven within 100 m, yet the armory
  takes 306 s and one quarters 376 s while 54 of 82 peons are idle at 300 s. A building is made of wood (40 pieces
  for 200 hit points, each chopped at a tree near the site and carried in), and those sites have two or three
  trees around them where a normal one has four to ten. About a dozen maps (s21021, s21073, s21061, s21049 finish
  the sixth quarters after 520 s with the first two on time).

One map (s21088) starts 8 cells from an enemy and loses its 18 builders in the first minute.

Why a near site crawls (log-only `site trace` lines, `op1-log-vs15` s21012): `buildersWanted` allows four builders
per tree within 12 cells of the site and never fewer than six, "more only get in each other's way". The five trees
within 16 cells are gone by 150 s, so the quarters placed at 21 s has no builder until 150 s and six from then on,
each walking 12 to 20 cells for a piece; the armory has six too, at 280 to 320 s, while 40 to 64 peons are idle.

`builders_floor` (the six, as a param) on ten slow maps and two normal ones, first 8 minutes (`bf0..bf3-log-vs15`,
`lab/fable/opening.py`): the sixth quarters stands at 581 s on average with 6 (four maps do not have it by 480 s),
330 s with 16, 398 s with 20, 401 s with `builders_far=30` (one builder per tree within 30 cells); armory 259 / 244
/ 247 / 251 s; towers at 8 min 1.8 / 3.1 / 2.9 / 2.9; peons 142 / 164 / 156 / 149; warriors and iron at 8 min the
same. The two normal maps play the same game at every setting.

On 100 maps (`bfl-12-vs15`, `bfl-16-vs15` against `pc1-00`): 38 and 36 wins against 41 (+9 -12, +8 -13; model -2.0
and -2.6 +- 2.0). The maps with the sixth quarters after 330 s go from 24 to 18 and 15, but on the 15 slowest the
armory still stands at 292 s (297; 227 on the other maps) and they win 1 game of 15 instead of 0; the 9 next win 3
/ 3 / 2. The floor also binds after the opening wherever the near trees are cut: 97 of 100 games differ from the
base, and the 76 normal maps win 34 and 33 against 38. So the floor stays at 6: the sixth quarters was not what
held those maps back, the armory is, and more hands on the late sites cost gatherers everywhere else.

What holds the armory back on the 24 slow maps (`op2-log-vs15`, 7 minutes with the site trace, six normal maps
beside them): nothing single. On average its site is placed at 62 s against 42, its first builder comes at 198 s
against 182 (the fourth quarters stands later), and it is built in 81 s against 54 by 11.0 builders fetching wood
17 cells away against 13.6 at 14 cells. By map: seven far plans whose builders chop 24 to 39 cells from the site
(s21008, s21011, s21028, s21037, s21094, s21096, s21097), three with half their peons dead by 2 min (s21021, s21040,
s21089: 11 to 21 peons at 120 s against 24), three wood-poor sites at the floor of six builders (s21012, s21058,
s21049), and the rest within 30 s of normal. `quarters_before_armory` 1, 2 and 5 were screened in the first session
(worse or equal), so the order itself is not the lever.

The far plans are not a mistake to undo: with the five later quarters by the first one whenever the armory site is
more than 180 m from the start (`opening_near_far`, built and taken out again; `nf0..nf3-log-vs15`, ten far-plan
maps) the sixth quarters comes later on six maps and sooner on three, and on s21040 the near sites are where the
neighbour's first warriors walk: 3 peons left at 150 s and no quarters at all.

### Screen `pc1`: raze x breach x hold at a tower, 100 maps each: nothing

The three drills changed their mechanism little or not at all in 12 logged games, and those games cannot call the
army or the wins (every variant read 65 to 94 warriors at 20 min against the base's 99, which over 100 maps is 84:
the one base run was a lucky draw). So they went to 100 maps together: `python lab/fable/screen.py read pc1`, base
`pc1-00` = `sb1-00`.

Wins by run 41 37 39 37 38 38 38 35 (mean 37.9). Main effects: raze -2.2 +- 2.4 (model +0.4 +- 1.7), breach -1.2
+- 2.7 (model -2.0 +- 1.3), hold -1.2 +- 2.7 (model -0.9 +- 1.2). The grounds table moves as the logged games said
(hold: 258 / 36 under our towers in minutes 20-30 against 231 / 24, 57 / 22 on the base ground against 74 / 31) and
nothing else does. All three stay off. Over the 16 runs of `sb1` and `pc1` the base's 41 is the second best: the
default is nearer 39 on these maps than 41.

### Two attacking forces (`split`, off; `lab/fable/wing.py`, logged runs on maps 21001..21012, 40 min)

With `split` an attacking army of that strength sends `split_share` of itself, the warriors nearest to it, at the
quarters or armory of another copy within 110 cells whose defense it beats by `split_margin` (1.3); the main army
leaves that copy alone. The second force (role WING) marches and fights by the army's rules, takes the copy's other
buildings and the next copy in reach, and walks to the main army as a reinforcement when it is under 20, outmatched,
stalled or without a target.

A steadier base for these twelve maps than one logged run: over seventeen near-default 100-map runs they read 12.2 /
10.8 / 9.6 / 8.9 / 8.5 / 7.9 / 7.3 copies housed and 111 / 128 / 116 / 94 / 72 / 47 / 39 warriors of ours at 12 / 15
/ 18 / 20 / 22 / 25 / 30 min; one run of the twelve varies by 0.28 copies and 8 warriors at 20 min.

| run | setting | splits a game | while split, a minute: second force razes / loses | main army | unsplit | copies housed at 20 / 25 / 30 min | our warriors |
|---|---|---|---|---|---|---|---|
| sp1 | split=100 | 0.5 | 0.29 / 7.2 (sp1..3 together) | 0.62 / 6.8 | 0.81 / 11.8 | 9.2 / 8.2 / 7.5 | 90 / 42 / 40 |
| sp2 | split=80 | 0.6 | 0.36 / 7.0 | 0.57 / 6.0 | 0.80 / 12.1 | 9.2 / 7.8 / 7.3 | 89 / 41 / 34 |
| sp3 | split=100, margin 1.0, reach 140 | 0.9 | | | | 9.0 / 8.2 / 8.0 | 88 / 29 / 44 |
| sq1 | split=80 + busy .15, far 1.6, reinforce | 1.2 | 0.31 / 9.3 | 0.63 / 6.6 | 0.83 / 10.0 | 8.7 / 7.8 / 7.2 | 88 / 36 / 37 |
| sq2 | split=100 + the same | 0.9 | 0.28 / 8.9 | 0.61 / 6.6 | 0.82 / 9.8 | 8.7 / 7.9 / 7.2 | 92 / 36 / 40 |
| sq3 | split=60 + the same | 1.8 | 0.31 / 9.2 | 0.69 / 6.2 | 0.69 / 10.3 | 8.9 / 8.0 / 7.2 | 94 / 39 / 42 |
| sq4 | split=80, share .4 + the same | 1.0 | 0.34 / 8.1 | 0.66 / 5.9 | 0.85 / 11.1 | 8.5 / 7.6 / 7.1 | 88 / 48 / 63 |

The half that stays keeps three quarters of the whole army's razing with half its warriors (0.57 to 0.69 quarters
and armories a minute against 0.8) and loses half as many: the pace is not the army's size. The second force is the
weak half: 0.3 a minute for 7 to 9 warriors, because its first target is 90 to 110 cells out and a force of 35 to 45
is worn down on the way as the reinforcements are (s21003: 37 to 19 and 44 to 18 before either reached a building),
while one that the reinforcements fill up does well (s21001: 36 at the split, 82 four minutes later, two copies).
With `split_busy` (it may split with a few enemies near), `split_far` (a wider second look before merging) and
`split_reinforce` (reinforcements to the weaker force) it is split 4 to 5 minutes a game instead of 2, and every
such run reads at or under the base's copies at 20 and 30 min; sq4 by 0.4 at 20 min (1.5 sd) with no smaller army.

A second round on sq4 (split=80, share .4, busy .15, far 1.6, reinforce): `split_worn=0.75` (a second force down to
three quarters of its most, still 30 cells from its target, turns back), `split_reach=80` (near targets only) and
`split_share_max=0.55` (a bigger share when the target's defense asks for it).

| run | added to sq4 | splits a game, minutes split | second force razes / loses a minute | main army | together | unsplit | copies housed at 20 / 25 / 30 | our warriors at 20 / 25 / 30 |
|---|---|---|---|---|---|---|---|---|
| sr1 | worn | 1.2, 3.5 | 0.40 / 7.4 | 0.66 / 5.6 | 1.06 / 13.0 | 0.85 / 11.5 | 8.6 / 7.8 / 7.1 | 89 / 50 / 43 |
| sr2 | worn, reach 80 | 0.8, 2.0 | 0.62 / 7.2 | 0.58 / 4.6 | 1.19 / 11.7 | 0.76 / 11.9 | 8.8 / 7.9 / 6.8 | 98 / 53 / 48 |
| sr3 | worn, split=120, share .5 | 0.7, 1.5 | 0.51 / 6.5 | 0.62 / 4.9 | 1.13 / 11.4 | 0.77 / 11.7 | 8.8 / 7.9 / 7.4 | 97 / 42 / 48 |
| sr4 | worn, reach 80, share to .55 | 1.5, 2.9 | 0.49 / 8.1 | 0.61 / 4.8 | 1.10 / 13.0 | 0.77 / 11.1 | 8.9 / 7.8 / 7.3 | 97 / 49 / 57 |
| sr5 | the same, margin 1.0 | 2.0, 3.7 | 0.47 / 8.7 | 0.61 / 5.4 | 1.08 / 14.1 | 0.75 / 11.2 | 8.8 / 7.5 / 7.0 | 97 / 53 / 42 |
| sr6 | the same as sr4, split=60 | 2.2, 3.2 | 0.45 / 8.3 | 0.69 / 5.0 | 1.14 / 13.4 | 0.77 / 12.8 | 8.8 / 7.6 / 7.5 | 86 / 39 / 34 |

While split the two forces now raze 1.06 to 1.19 quarters and armories a minute against 0.75 to 0.85 for the whole
army, for 11 to 14 warriors a minute against 11 to 13. But the army is split only 2 to 4 of the eight minutes that
count: on 88 to 158 of its 2 s checks a game it is big enough and finds no copy near and weak enough. Over the ten
runs sq1..sr6 the copies housed read 8.75 / 7.78 / 7.18 at 20 / 25 / 30 min against the base's 8.93 / 7.9 / 7.3: a
sixth of a copy, with armies of 93 / 45 / 44 against 94 / 47 / 39.

On 100 maps (21001..21100, base `pc1-00`: 41 wins; all with split=80, busy .15, far 1.6, reinforce):

| run | setting | wins | changed games | model | splits a game | warriors lost in the second force | copies housed at 20 / 25 / 30 (base 9.0 / 8.1 / 7.6) |
|---|---|---|---|---|---|---|---|
| sx1 | share .4 | 43 | +8 -6 | +0.6 +- 1.6 | 1.1 | 22 | 8.8 / 8.1 / 7.7 |
| sx2 | share .5 | 44 | +10 -7 | -0.4 +- 1.8 | 1.3 | 32 | 8.8 / 8.0 / 7.6 |
| sx3 | share .4, margin 2.0 | 38 | +4 -7 | 0.0 +- 1.2 | 0.5 | 8 | 8.9 / 8.2 / 7.7 |
| sx4 | share .4, split_merge=30 | 44 | +7 -4 | +0.8 +- 1.6 | 1.4 | 15 | 8.8 / 8.1 / 7.6 |
| sy1 | share .4, worn .75, reach 80 | 45 | +8 -4 | +1.4 +- 1.4 | 0.9 | 12 | 8.9 / 8.2 / 7.7 |
| sy2 | the same, share to .55, margin 1.0 | 42 | +8 -7 | -1.0 +- 1.9 | 1.8 | 27 | 8.9 / 8.2 / 7.6 |

Five of the six split about once a game and all five are at or above the base (43.6 on average; +41 -28 changed
games over their 500, z 1.6); the one that splits half as often is below it. The effect is early and small: a fifth
of a copy fewer housed at 20 min, nothing at 25 and 30, and the copies made homeless by minute 30 are 6.6 a game with
or without it (as are the 0.6 that stand housed again): the split takes the same copies sooner, it does not take
more. The default's games are unchanged with split off (`fix-f`, 8 of 8).

sy1 on 200 fresh maps (21101..21300): 80 wins against the default's 78 (`sy1-fresh-vs15`, `def4-fresh-vs15`; +11
-9; model -1.5 +- 1.0), 0.9 splits and 11 warriors lost in the second force a game, the copies housed the same at
every minute (8.8 at 20, 8.0 at 25) and the army 85 / 43 / 35 against 88 / 44 / 39 at 20 / 25 / 30 min. Over the 700
games of the five variants and this run the split is +52 -37 (z 1.6): a point or two at most, and not the fifth of
a copy the screening maps showed. It stays off.

`split_promote` (a second force 1.5 times the main army becomes the army) never fires with the worn rule on
(`st1`, `st2-log-vs15` play sr2's and sr1's games).

What the split leaves behind: the half that stays razes three quarters as fast as the whole army for half the
losses, so past 55 warriors at a copy every further warrior buys little razing and costs as much as the first; and
the copies made homeless by minute 30 do not depend on how the army is divided. The army's budget of warriors sets
how many copies fall; dividing it only moves when.

### The slow openings are iron behind water (`lab/fable/openwin.py`, `oremap.py`, `plans.py`)

The 300 default games on maps 21001..21300 (`pc1-00`, `def4-fresh-vs15`: 119 won) by the opening's milestones: every
milestone's worst fifth wins 15 to 23 % and the other four fifths 35 to 55 % with no order among them, so the
opening does not grade a game, it breaks it or not. The strongest early readings are the iron by 8 min (worst fifth
12 %), the iron by 12 min (7 %) and the warriors at 12 min (under 90: 12 of 59 games; 110 and more: 96 of 186).

By where the armory stands: within 25 cells of the start 2 of 19 games are won, 25 to 40 cells 22 of 62, 40 to 55
cells 39 of 75, farther 56 of 144. The near armories are not slow (armory at 227 s, 207 units at 8 min); their iron
stops. Logged (`near1-log-vs15`, eight of them): the iron trip goes from 26-60 s at 5 min to 110-200 s at 7 to 9
min, with 4 to 33 iron nodes in the armory's 400 m field.

Every map's ore, logged at the start (`ore map` lines; `ore1-log-vs15`, a minute of each map): the iron nodes
within 300 m of walking from the armory site decide.

| iron nodes within 300 m of the armory site | games | won |
|---|---|---|
| under 20 | 26 | 0 |
| 20-29 | 11 | 2 |
| 30-39 | 18 | 7 |
| 40-64 | 108 | 47 |
| 65 and more | 137 | 63 |

The planner scores a site on its nearest 15 nodes, so it cannot tell 15 nodes from 60. But the short sites are not
its mistake alone: on those maps the nodes are 120 to 170 m away as the crow flies and 570 to 680 m to walk
(s21142), with corner cuts or without, and through the trees as well (`MapAnalysis.computeCutField`, trees free):
the barrier is water. By the walk from the start to the 20th nearest node: under 250 m 109 maps and 51 % won, 250
to 300 m 120 and 42 %, 300 to 350 m 42 and 24 %, 350 to 400 m 17 and 18 %, beyond 12 and none.

Three ways to the far iron, all behind flags, all off:

- `armory_iron_need` (the first armory's site pays 200 game ticks for every node it lacks of 40 within 300 m, and
  the search widens while the best site is short): 52 of the 300 armories move, 17 of them more than 400 m from the
  start, and the sites under 40 nodes go from 55 to 8 (`pl1-log-vs15`). The other 248 maps play the same games. On
  the 52: 11 wins against 9 (`in40-vs15`; +5 -3). With a need of 50 and the search out to 800 m 85 move: 28 against
  23 (`in50-vs15`; +12 -7). The 26 sites that had under 20 nodes win 3 instead of 0, but their armory stands at
  329 s instead of 225 and they have 109 units at 8 min instead of 208: the first quarters is by the start, the rest
  400 m away, and where the way there passes a neighbour's quarters the peons bred by the start are killed on it
  one by one (s21142, s21061: the stock AI sends everything it has at any unit within 30 m of its first quarters
  or armory).
- `opening_move_m` (with the armory that far, the first quarters goes up by it too: the whole tribe walks first):
  on 16 such maps the first quarters stands at 142 s, the armory at 361 s against 333 and the units at 8 min are 67
  against 82 (`fo2-log-vs15` against `fo1`); two tribes are killed on the way.
- `expansion_iron_need` (the opening stays by the start; a first armory with under 40 nodes gets a second one on the
  iron as soon as it stands, with a crew that walks out with the placer, `expansion_iron_crew`): the far armory
  stands at 6 to 8 min, the iron trip drops to 21 s, and the iron by 15 min is the same 174 as the default's 176 on
  16 maps (`fo3`, `fo4` against `fo0b-log-vs15`). On the 55 maps with a short first site: 5 wins against 9
  (`xi40-vs15`; +2 -6).

So nothing here turns the maps with the iron behind water: staying starves, moving costs 100 s and half the
peons, and a second base on the iron is fed by a road through the neighbours.

What `armory_iron_need` does win is the middle: sites with 20 to 50 nodes that have a fuller one 40 to 150 m
farther out. On fresh maps (21301..21600; `ore2-log-vs15` and `pl5-log-vs15` for the plans, 102 of 300 armories
move with a need of 50): 34 wins against the default's 23 on those 102 maps (`in50f-vs15`, `def5f-vs15`; +18 -7).
By how far the armory moved out: under 100 m 61 maps, 16 -> 24 won; 100 to 200 m 23 maps, 6 -> 8; 200 m and more
18 maps, 1 -> 2. At 12 min the moved games have less, not more (armory 16 s later, 110 warriors against 116, iron
195 against 201): the gain comes after, when the default's field is mined out. Both sets together: +30 -14 over
600 maps (z 2.4), 2.7 points.

`lab/fable/planwins.py` adds a plan's wins over a set of maps from games already played: a game depends on the
armory search's parameters only through the site, so a map's result under a plan is that of any game with that
site. On 21001..21300 (default 119 of 300): need 40 121 (52 armories move, +5 -3), need 50 124 (85, +12 -7), need 60
121 (140, +20 -18), need 70 128 (184, +30 -21; `in60-vs15`, `in70-vs15`); a 400 m reach with need 50 moves 21
(+3 -0), 400 ticks a node moves 83 (+10 -7). No order among the needs, so the default is the one played on fresh
maps: need 50, out to 800 m. It plays `in50-vs15`'s games where the site moves and the old ones elsewhere (`fix-h`,
8 of 8 at 10 min); with the flag at 0 the old default's (`fix-g`, 8 of 8).

Open: need 70 on fresh maps; and the far plans' own opening (38 of 300 armories are now more than 260 m out, 19 more
than 400 m): after the first quarters the builders wait for the scout to place the far sites and then carry a piece
of wood cut by the start the whole way at 4 m/s.

### The long game: what breaks after the first campaign (`lab/fable/endgame.py`, `late.py`, `band.py`, `launches.py`)

The user's lead (2026-11-16): look for behaviour that is plainly broken, above all late in the game, where
Gauntlet's development had few signals. 589 games at N=15 (`def4-fresh-vs15`, `pc1-00`, `in50-vs15`, `in50f-vs15`,
`def5f-vs15`; 204 won): no game is won before minute 40, half of all games last more than an hour, and the lost ones
end at 30-50 min or drag on. Share won by the copies still housed (a quarters or an armory) at a minute, games still
running then:

| minute | 0-3 | 4 | 5 | 6 | 7 | 8 | 9 | 10+ |
|---|---|---|---|---|---|---|---|---|
| 20 | | | | 8/8 | 82/106 | 78/146 | 27/127 | 9/185 |
| 30 | 8/8 | 19/20 | 42/48 | 50/73 | 44/95 | 29/101 | 12/81 | 0/105 |
| 40 | 50/50 | 32/34 | 39/41 | 31/49 | 29/61 | 12/78 | 11/55 | 0/49 |
| 60 | 99/99 | 19/19 | 23/29 | 21/28 | 8/31 | 14/48 | 3/18 | 0/10 |

Finishing is not the leak: with five copies or fewer at minute 30 we win 69 of 76. The open games are those with 6-9 copies at
minute 30 (135 won of 350), and 117 of the 385 lost games had the enemy down to seven copies or fewer at some point.
The state by minute in the games still running (the 589 games; iron over the five minutes before):

| | our warriors at 30 / 40 / 60 / 100 min | peons | quarters | towers | iron a minute | copies housed | their warriors |
|---|---|---|---|---|---|---|---|
| won | 60 / 68 / 98 / 86 | 112 / 109 / 94 / 98 | 5.0 / 4.9 / 4.8 / 4.3 | 12.6 / 12.9 / 13.4 / 14.1 | 16 / 16 / 14 / 13 | 6.2 / 5.0 / 3.1 / 3.1 | 363 / 305 / 195 / 206 |
| lost | 20 / 14 / 11 / 6 | 101 / 90 / 96 / 103 | 3.9 / 3.3 / 3.2 / 2.6 | 9.5 / 9.0 / 10.2 / 9.2 | 8 / 7 / 8 / 4.5 | 8.8 / 8.4 / 7.8 / 7.4 | 556 / 501 / 451 / 427 |

Sixteen games were logged at full length for the reading below (`eg0-log-vs15`, `eg2-log-vs15` with the peon
ledger: twelve lost games in which the enemy had been down to 4-6 copies, and the four longest wins, 184-217 min;
`late.py` prints a game by ten-minute windows from its STAT lines and census).

A long game is a siege of our base: we kill 33-45 units a minute, nearly all at our towers, and the copies replace
them while their ore lasts; the four long wins are games in which the base stood for two hours with 6-19 warriors
until the enemy's warriors had halved (367 to 174). In between nothing is built up: every weapon is spent at once. What is broken in that phase,
each read in the logs and then counted:

1. **The forge waits for wood with its workers idle.** A weapon is two wood and an iron, and everyone inside the
   armory forges (`WeaponsProducer`: man-seconds = workers x time). From minute 20 the main armory has under two wood
   and iron in stock in 22-38 % of the half-minute samples (55-67 % past minute 90), with 11-40 iron and 31-60
   workers inside and the wood gatherers 20 short of the plan's number: when iron piles up the plan cuts the iron
   gatherers (`ore_adj` .7 / .35) and counts the peons freed as forgers (s21327 at 43 min: 77 inside, 88 wanted, one
   wood, 19 iron).
2. **A satellite armory is never staffed.** Its workers and wood gatherers come out of the peons inside it, and only
   gatherers it recalls go in. s21194, minutes 60-90: the satellite has 45 iron gatherers, 36 iron and 17 wood in
   stock, one worker and one wood gatherer of 26 wanted; the main armory 50 cells away holds 45-130 workers, 45 rock
   and one wood; no weapon is made for half an hour (army 0-12). s21589's satellite holds 139 iron and one wood.
   Satellites stand in 13 % of the late minutes and are starved in 45 % of those.
3. **Lone enemy warriors keep the gatherers from gathering.** `PeonLedger` (new; counts every peon killed outside a
   building by its job): after minute 30 we lose 16 peons a minute, 27 % of them gatherers out of an enemy's way
   (`evade`), 21 % walking into a building at home, 14 % shepherds. An iron gatherer steps out of an enemy's way 69
   times a minute a game, half of the times from a single warrior, 42 % within 3 s of setting out again, and 15-29 %
   of the gatherers' time ends in a delivered load. The cause is in the defence: an enemy away from the base is a
   threat only while a peon is within 12 cells of it, the peon steps away, the threat is over within a second or two
   and the warriors sent at it stop (s21327 logs a new threat of one or two warriors every 1-2 s for minutes). A
   hunting Hard warrior never answers a hit (Lures' notes), so it would be a free kill.
4. **The late launches are too small.** `launches.py` (337 attacks of 48 logged long games): launched after minute 30
   with 12-25 warriors an attack razes 0.15-0.30 buildings per ten warriors it loses, with 45 or more 0.5-1.0, and
   the small ones are the most frequent. `late_caution` 0.5 halves the gate's ratios (and the retreat's) from minute
   40.
5. **No expansion while a threat stands, and one always does.** s21194 checks no site between minutes 59 and 152,
   with the nearest free iron 160-245 m from the armory; the armory built at minute 152 brings iron from 5 to 23 a
   minute and the game is won.
6. **The chieftain is spent.** He never heals, so below 24 hp he stands by the armory for good: 85 % of his log lines
   after minute 30, 2.3-3.8 casts per ten minutes against 5.7 before minute 20 (`chief_refresh`, Gauntlet's and off,
   kills and retrains him).
7. **Home warriors die one at a time.** 4 a minute after minute 30, a median 20 cells from our nearest building
   (outside every tower's reach), 43 % with two mates or fewer within 12 cells, 52 % outnumbered three to one where
   they fall: each new warrior walks from the armory to the threat on its own. Not addressed yet.

**The test set.** A change that acts only late leaves a game identical until then, so it can only turn games that
are still open: `band.py` lists the maps with 6-9 copies housed at minute 30 under the default (177 of 21001..21300;
the default wins 80 of them, 44 of the first 100, `lab/fable/band30-100.txt`) and compares a variant's games with
the default's there. Late-gated variants do play the same games up to their gate (the columns at minutes 30 and 40
are equal to the digit).

**What was built** (all behind flags, off unless said otherwise below):

- `stock_balance` (1): each plan tick with four or more wood missing (two per iron in stock) and four or more
  workers inside beyond the plan's own forgers, three more are wanted as wood gatherers, up to the wood missing and
  40; three fewer when the wood is there. In 16 logged games (`sb1-log-vs15`, with `sat_staff`) the forge waits for
  wood in 3-12 % of the samples of minutes 20-60 against 22-33 %.
- `sat_staff` (2): a satellite is to have a forger for every weapon its stock pays for and a wood gatherer for
  every four wood its iron lacks (at most 12 each); free peons and then the main armory's spare workers walk over,
  and it keeps the ones meant for its wood.
- `harass_memory_ticks` (3): an enemy warrior that came at our peons away from the base stays a threat for this
  long after the last peon was near it. With 30 s the gatherers step aside 41 times a minute against 77 (16 logged
  games, `hm1-log-vs15`).
- `evade_tower` (3): the step out of an enemy's way goes towards our nearest manned tower within this many cells,
  unless that leads at the enemy, so that the tower shoots the pursuer. With 45 cells on the 16 logged maps
  (`et1-log-vs15`): 46 steps a minute against 77, iron 12.0 a minute against 9.8 (up on 11 of the 16 maps).
- `late_min_strength` (4): no muster below it from minute 30 while the enemy's warriors are worth twice as much.
- Already there and never screened at N=15: `late_caution=1.0` (4: no halving of the gate late),
  `expand_under_threat` (5: an expansion while a threat stands, if no enemy is within 30 cells of the site or 12 of
  the way), `chief_refresh` with `chief_retrain_late` (6), and `lure` (idle peons bait parked groups past a tower),
  here from minute 30 (`lure_ticks=90000`).

**On the 100 open maps** (`band30-100.txt`). The default's own game wins 44 of them; a second draw of the default
(`null1-band-vs15`: a harasser memory of 0.6 s, which changes nothing but the games' course) wins 47 (+12 -9), so the
default is about 45.5 there and every comparison with the single draw of 44 flatters by one or two. The same replay
also shows 60 warriors against 51 and 5.0 copies against 5.7 at minute 50 in the games running in both: for a
variant that changes the game before minute 30 the state columns of `band.py --against` are biased by that much
(the maps were chosen by the default's own state at minute 30), and only late-gated variants compare cleanly.

| variant | wins | to a win / to a loss | |
|---|---|---|---|
| `late_caution=1.0` (`lc1`) | 49 | +8 -3 | the same games until minute 40 |
| and `capped_min_strength=40` (`cm1`) | 45 | +6 -5 | |
| and `late_min_strength=45` (`lm1`) | 45 | +6 -5 | the warriors kept home die at home: 354 lost in the home army a game against 307, 418 in attacks against 460 |
| `expand_under_threat` (`xt1`) | 48 | +9 -5 | one such expansion a game, 22 checks stopped by the site test |
| `sat_staff`, `stock_balance` (`sb2`) | 54 | +21 -11 | |
| `harass_memory_ticks=1500` (`hm1`) | 47 | +11 -8 | |
| staffing and memory (`sh2`) | 46 | +17 -15 | |
| `lure` from minute 30 (`lu1`) | 51 | +9 -2 | 86 lures a game, 136 hunters pulled past a tower, 19 lure peons lost |
| `chief_refresh`, `chief_retrain_late` from minute 30 (`cr1`) | 49 | +8 -3 | one cull a game |

Screen `lg1` (`screen.py`; staffing x memory x late caution x expansion under threat, the half fraction with D = not
ABC, whose single-factor runs are `xt1`, `sb2`, `hm1`, `lc1`; `lg1-03/05/06/07` the three-factor ones): wins by run
48 54 47 47 49 53 51 47, every run above the default. Contrasts inside the screen, on wins and on the state model's
win chance at minutes 40 / 50 / 60 (`screen.py model`):

| factor | wins | model at 40 | at 50 | at 60 |
|---|---|---|---|---|
| staffing | +1.5 +- 3.9 | +3.7 +- 3.2 | +3.3 +- 3.5 | +0.7 +- 4.1 |
| harasser memory 30 s | -3.0 +- 2.7 | -1.0 +- 2.0 | -2.8 +- 2.3 | -4.1 +- 2.4 |
| late caution 1.0 | +1.0 +- 1.8 | +0.8 +- 1.0 | +0.6 +- 1.4 | +0.7 +- 1.6 |
| expansion under threat | +0.5 +- 2.0 | +3.1 +- 1.1 | +2.2 +- 1.3 | +2.2 +- 1.4 |

No factor is settled by 800 games: a late fix is worth a few points on these maps at most, and a 100-map run has
an error of four or five. The memory halves the steps aside and still costs (the home army walks out after
harassers up to 120 cells from home); it stays off. Staffing, caution and expansion together (`lg1-05`) are the best
run by wins (53) and by the model at every minute (53, 55, 50).

Three games in about a thousand with these variants ran to the 360-minute limit, which the default never did:
`xt1-band-vs15` s21056 ends with all nine living copies without a single building and 371 warriors standing idle
(we have 44 warriors and 249 units), `L1f-vs15` s21400 with seven warriors of ours against 163 after six hours. An
enemy that cannot arm any more is not hunted down.

**On fresh maps the late fixes are worth nothing.** All 300 of 21301..21600, where the default wins 126
(`in50f-vs15` on the 102 maps whose armory the iron need moves, `def6f-vs15` on the other 198):

| | wins | to a win / to a loss | by the default's copies housed at minute 30: 5 or fewer (47 maps, default 43) / 6-9 (188, 83) / 10+ (44, 0) |
|---|---|---|---|
| staffing, late caution 1.0, expansion under threat (`L1f-vs15`) | 126, 2 draws | +38 -38 | 36 / 88 / 2 |
| and the lure and the chieftain's refresh from minute 30 (`L2f-vs15`) | 127 | +38 -37 | 36 / 87 / 4 |

So the breakdowns of the long game are real and their repairs do what they were built for, but who wins is settled
before they act. All of them stay off.

### Where the open games part: the armories of minutes 15-35 (`lab/fable/armories.py`)

787 games (the 589 and `def6f-vs15`), those with 6-9 copies housed at minute 30, by our own state then:

| our warriors | under 20 | 20-40 | 40-60 | 60-90 | 90+ |
|---|---|---|---|---|---|
| won / games | 31/189 | 41/104 | 42/80 | 41/62 | 40/44 |

| iron a minute over minutes 25-30 | under 6 | 6-10 | 10-14 | 14-18 | 18+ |
|---|---|---|---|---|---|
| won / games | 21/133 | 17/64 | 29/76 | 25/58 | 103/148 |

The iron is two-humped, and the two ends were the same game ten minutes earlier:

| | iron a minute, warriors, towers, armories at minute 15 | 20 | 25 | 30 |
|---|---|---|---|---|
| under 6 at minute 30 (133 games, 21 won) | 22.5, 129, 11.7, 1.9 | 13.3, 88, 11.4, 1.8 | 6.5, 33, 10.1, 1.3 | 2.4, 12, 8.8, 1.0 |
| 18+ at minute 30 (148 games, 103 won) | 23.5, 141, 11.9, 2.0 | 14.5, 96, 11.7, 1.8 | 15.1, 44, 12.0, 1.6 | 23.0, 64, 12.4, 1.6 |

One group loses its armories and the other gets a new one onto fresh iron. Why that is luck:

- **The building cap is 20 and we are on it from minute 15** (181 of 198 games of `def6f-vs15` have exactly 20
  buildings and sites at minute 15: 6 quarters, 2 armories, 12 towers). An armory needs a slot, a moment with no
  threat at the base (`considerExpansion`) and to get there before the tower planner retakes the slot. Armories
  built a game per five minutes (300 fresh games, won / lost): 0.99 / 0.87 in minutes 0-5, 0.73 / 0.80 in 5-10,
  0.25 / 0.21 in 10-15, then 0.00 / 0.01, 0.03 / 0.07 and 0.06 / 0.13 up to minute 30. Razed in those three
  windows: 0.13 / 0.23, 0.29 / 0.54, 0.16 / 0.54.
- **The armory that falls is the one without towers.** Towers anchor on the primary armory, the exposed quarters
  and the building nearest each enemy, never on the other armory. Of the armories razed in minutes 15-35 and those
  that stood through, by our towers within 15 cells: none 49 razed and 7 stood (12 % stood), one 62 and 13 (17 %),
  two 60 and 47 (44 %), three or more 163 and 215 (57 %); the razed ones stand a median 101 cells from our start,
  the others 75.

Built for it: `armory_cover` (the next tower goes by a finished armory with fewer than that many towers within 15
cells) and `bank` (from minute 30, while the army is home and under 25 strong, weapons stay in the armory until
there are that many: with 60 the armory fills in 200-360 s and lets out an army of about 47 to the muster; 16
logged games `bk1-log-vs15`). Gauntlet's own answer to the cap is a family that was only ever tried one flag at a
time (`reloc` alone: 39 of 100, "rarely fires"): `reloc` (a hop to fresh iron with no threat gate), `reloc_slot`
(the next free slot is kept for it) and `retire` (a building of ours is razed for the slot).

On the 100 open maps (default 44 and 47 in its two draws): `bank=60` 45 (+6 -5 against the first draw),
`evade_tower=45` 52 (+16 -8).

**On 150 fresh maps** (21301..21450, all of them, not only the open ones; the default wins 56):

| | wins | to a win / to a loss | armories standing at minutes 25 / 30 (default 1.47 / 1.28) | iron a minute at 30 (12.7) |
|---|---|---|---|---|
| `reloc`, `reloc_slot`, `retire` (`RL1f`) | 61 | +9 -4 | 1.64 / 1.44 | 13.7 |
| and `retire_any_tower`, `expand_under_threat` (`RL2f`) | 61 | +11 -6 | 1.68 / 1.51 | 13.9 |
| and `reloc_sat` (`RL3f`) | 56 | +20 -20 | 1.78 / 1.68 | 14.9 |
| `armory_cover=3` (`ac3f`) | 63 | +28 -21 | 1.50 / 1.44 | 13.6 |
| `evade_tower=45` (`et1f`) | 68 | +21 -9 | 1.43 / 1.33 | 12.4 |

The hop fires 0.9-1.1 times a game in the first two (its gate wants every other armory drained and a satellite
never is: shut for 653 plan ticks a game) and 3.1 times with `reloc_sat`, which also razes 2.4 towers and 0.6
quarters of ours a game for the slots: more armories and iron, fewer towers, the same wins. The step towards a tower
is the one result here that stands on two sets of maps.

Confirmation of the step towards a tower, and what stacks on it (the default wins 70 of 21451..21600):

| | maps | wins against the default | to a win / to a loss |
|---|---|---|---|
| `evade_tower=45` (`et1g-vs15`) | 21451..21600 | 71 against 70 | +7 -6 |
| `evade_tower=45`, both halves | all 300 | 139 against 126 | +28 -15 |
| and `armory_cover=3` (`ea1f-vs15`) | 21301..21450 | 68 against 56, 2 draws | +29 -17 |
| and `reloc`, `reloc_slot`, `retire` (`ear1f-vs15`) | 21301..21450 | 66 against 56, 1 draw | +29 -19 |

By the default's copies housed at minute 30 the step's gain on the first half is all in the open games (6-9 copies:
38 -> 49 of 99; 5 or fewer 18 -> 18; 10 or more 0 -> 1) with the same state up to minute 30, and on the 16 logged
long maps iron comes in at 12.0 a minute against 9.8. Armory cover and the hop add nothing on top of it on these
150 maps (68 and 66 against 68), so they stay off.

**`evade_tower=45` is the default since** (`Strategy.forGame`; +28 -15 on fresh maps and 52 of the 100 open maps
against 44 and 47, z about 2.2 together): it plays `et1f-vs15`'s and `et1g-vs15`'s games (`fix-j`, 8 of 8) and with
`evade_tower=0` the games of before (`fix-k`, 8 of 8). On the fresh maps that is 139 of 300 (46 %) at N=15; no exam
seeds were spent.

Open: the reach (45 cells was the only value tried), the same step for peons within 30 cells of a shelter (21 % of
the late peon deaths are peons walking into a building at home), the armories of minutes 15-35 (cover and hop each
help alone, 61-63 against 56, and not on top of the step), home warriors that go out one at a time, and games that
run to the limit against an enemy who can no longer arm.

### Minutes 15-25: the forward armory, the slots, and what does not help (`lab/fable/clusters.py`)

Where the open games part, read in the 16 logged long games of the default (`et1-log-vs15`) and counted in its 300
fresh games (`et1f-vs15`, `et1g-vs15`: 139 won):

- **The second armory falls at minute 18-22.** In 10 of the 16 logged games the armory built at minute 7-11 on the
  fresh iron (50-100 cells from the first) is razed between minutes 18.5 and 22.3. In the 300 games an armory falls
  in minutes 15-25 in 147, and those are won 33 % of the time against 59 % for the other 153; how soon another
  stands makes no difference (within 5 minutes 3 of 14 won, in 5-10 minutes 8 of 34, later or never 38 of 99).
- **Iron stops at minute 16-20 in every logged game**, won or lost: 13-16 a minute falls to 2.5-8 when the first
  waves park at the base (20-80 parked enemy warriors from minute 16-18) while 100-150 of our warriors are 200
  cells away. The won games have it back by minute 20-24, the lost ones never; s21015 loses 20-57 peons a minute
  for fifteen minutes for 2-4 iron a minute.
- **The slots are not it.** The tower target is 14 plus one per enemy beyond the first, 28 against a cap of 20, so
  the tower planner takes every slot a razed quarters or armory leaves (s21015 at 19.5 min: 5 quarters, 1 armory,
  14 towers and sites). But games on the cap with an armory missing are won as often as the rest (minute 25: 23 of
  48); the dying ones have free slots and build nothing (minute 25: no armory to spare and a quarters short with
  slots free, 71 games, 10 won). `tower_cap=true` (Gauntlet's flag: towers leave room for the quarters, two
  armories and a spare) scores 46 of the 100 open maps against the default's 52.
- **Calling the army home does not help**: `recall_ratio` 0.5 and 0.2 (never, since Gauntlet's N=11) score 50 and
  47 of 100.
- **The towers stand alone.** At minute 16 three towers of four have no other tower of ours within 10 cells (2593
  of 3472; a garrison reaches 15.9), and none in 43 % within 14. By design: `findTowerSite` takes 6 off a site for
  every tower within 16 cells. In the default's games a neighbour shows little (razed by minute 30: 39 % with no
  tower within 14 cells, 37 % with one, 36 % with two, 18 % with three), but Gauntlet's `tower_mutual=true`
  (towers 10-13 cells apart, never tried at N=15) scores 57 of 100 (+15 -10) with 12.3 towers razed a game against
  15.0 and 24 waves reaching the base against 28.
- **Waves that reach the base** (launch lines of the 16 logged games): in minutes 15-25 a game has 6.0 launches at
  our buildings and 6.6 at a unit of ours in the field, against 47 at a shepherd. Nearly all of the first kind come
  from a wave already standing near us while its shepherd is walking or fleeing (the shepherd further from the
  wave than 0.71 of our building's distance, where the copy picks the building, in 87 of 89).

### The chieftain's blast (`shred`, `shred_strict`; Gauntlet's mission, never run at N=15)

The engine (`SonicBlast`, `RacesResources`): the viking chieftain's second spell reaches 36 m (18 cells) around him
and hits every unit and building in it, friend and foe, with a chance of 2 to 17 times (1 - defense): certain for
rock warriors and peons, 0.6 or more for iron warriors; a building takes 30 from four cells out and 150 next to
him. It needs 70 s of charge against the stun's 40, and a cast empties both. The Hard copies arm mostly rock
warriors, park their waves in blobs of 20-40, and a parked warrior scans 8 cells and never answers a hit. Our
chieftain stuns a median of 6 warriors a cast; the stock chieftains blast us 13.5 times a game.

Gauntlet built a mission for it (`shred`: he walks to parked blobs within 140 cells of the armory and blasts them;
`shred_strict`: he never stuns and waits behind the armory between casts) and left it off. Session 2 stopped
`shred=true` after 27 games (0.5 blasts a game: every stun zeroes the charge) and never ran the strict form.

On the 100 open maps (default 52):

| | wins | to a win / to a loss | blasts a game, enemy warriors in reach | chieftains lost |
|---|---|---|---|---|
| `shred`, `shred_strict` (`bz1-band-vs15`; he stops below 35 hit points) | 53 | +17 -16 | 6.0, 89 | 1.0 |
| and `shred_min_hp=0` (`bz2-band-vs15`) | 59 | +18 -11 | 12.9, 205 | 2.0 |
| `blast=true` (he blasts instead of stunning when 12 of theirs stand by 6 times ours; `bz3-band-vs15`) | 56 | +5 -1 | 94 games identical | 1.8 |
| `blast`, `blast_save`, `blast_defense` (`bz4-band-vs15`) | 46.5 | | | 2.0 |

What the strict mission does in a game, and what was broken in it (16 logged long maps, 70 minutes each,
`bz2-log-vs15` and on; the scripts are in the session's scratchpad, the counters in the summaries):

- **He blasts from the blob's edge.** Every cast is made "from where he stands" as soon as `shred_min` (8) warriors
  are in reach, on his way to the cast point: 10 of a blob of 40 (s21318). 13-19 in reach a cast, and no cast at
  all from a cast point. `shred_fill=0.8`: he blasts on the way only once that catches 0.8 of what the cast point
  would, unless an awake warrior is within 9 cells: 20-24 in reach a cast.
- **He walks home after every cast.** Charged, with a blob 33-50 cells away, in half of the 20 s samples of minutes
  10-40; 3 casts in ten minutes where the charge allows 8. `shred_lurk=28`: while charging he waits 28-40 cells from
  the next blob's centre, 11 cells (Chebyshev) from every enemy warrior. He then becomes the launch target of the
  blob's copy (a copy launches at our unit nearest its first idle warrior): 7.6 of 10.3 casts a game are made with
  awake warriors at him.
- **A dead chieftain is away for 400 s** (median, with or without shred; the default loses 1.8 a game). Training
  takes 40 breed ticks of the quarters, 440 / n^(1/3) s with n peons inside, and `holdFor` keeps none inside at
  the unit cap, where we are from minute 8; nor is the trainer topped up under a base threat. Gauntlet's
  `chief_topup_any=true` brings it to 214 s (`bz8-log-vs15`), with `hold_chieftain=40` to 160 s, but 40 peons in
  the first chieftain's quarters cost the opening (136 warriors at minute 15 against 145). On the default
  `chief_topup_any` alone is 50 of 100 against 52 (+8 -10).
- **Where a charged chieftain's time goes** (`shred_t_*` counters, half-second rounds, `bz10-log-vs15`): of a
  70-minute game 857 s charging, and charged 727 s with no blob, 704 s walking to a cast point (1.4 cells a second,
  50-100 cells) and 332 s with an awake warrior within 9 cells. `shred_ahead` (with no blob he waits where the
  biggest walking wave will park) and a wider `shred_range=220,shred_min=6` cut the time without a blob to 136 s
  and add it to the walking: 10-11 casts a game either way.
- **Blasting at our own base** (`shred_base`: warriors within 20 cells of our buildings are a blob; `shred_bait`: he
  stands for a group instead of backing off; `shred_buildings=99`: any number of our buildings in reach as long as
  each outlasts it): 13.6 casts a game catching 314 against 205. In the 16 logged games it looked harmful (12.4
  towers razed a game against 9.4, 5 games lost within 70 minutes against 2), on the 100 open maps it is 56 with
  13.5 towers razed against the default's 15.0 (`bz6-band-vs15`): the 16 maps misled.
- **What it buys**: launches at our buildings fall from 22.6 a game to 16.6-18.4 in the first 70 minutes of the 16
  logged maps, launches at our units in the field from 30 to 24: a blob killed near the base is a wave that does
  not relaunch into it. The copies re-arm what dies (their warriors at minute 30: 1717 against 1684), and without
  his stun and his bonus in the muster's gate the first campaign is a little slower (0.12 copies fewer out at
  minute 15).

Screen `bt1` (`lab/fable/screen.py`; four factors in eight runs of the 100 open maps, the default 52): wins by run
52 46 56 58 51 55 62 61.

| factor | wins of 100 | state model at minute 30 |
|---|---|---|
| the mission (`shred`, `shred_strict`, `shred_min_hp=0`, `shred_fill=0.8`, `shred_lurk=28`) | +8.2 +- 2.9 | +6.1 +- 1.9 |
| `chief_topup_any` | +4.2 +- 2.7 | +3.4 +- 1.8 |
| `tower_mutual` | -0.2 +- 2.9 | -3.0 +- 2.0 |
| `armory_cover=3` | -3.2 +- 3.0 | -1.1 +- 2.0 |

On the 300 fresh maps (the default wins 139: `et1f-vs15`, `et1g-vs15`):

| | 21301..21450 (default 68) | 21451..21600 (default 71) | all 300 | to a win / to a loss |
|---|---|---|---|---|
| the mission (`sh5f-vs15`, `sh5g-vs15`) | 75 | 76, 1 draw | 151 | +36 -24 |
| and `chief_topup_any` (`sh6f-vs15`, `sh6g-vs15`) | 84, 1 draw | 74, 1 draw | 158 | +44 -25 |

With the mission the base holds through the minutes in which it used to break (first 150 fresh maps, games still
running, the mission against the default): armories at minutes 20 / 25 / 30 1.89 / 1.58 / 1.40 against 1.75 / 1.42 /
1.29, quarters 5.73 / 5.16 / 4.73 against 5.55 / 4.96 / 4.42, iron a minute 15.5 / 12.6 / 14.3 against 14.5 / 11.1
/ 12.0; the campaign is a little slower (8.99 copies housed at minute 20 against 8.76). By the default's copies
housed at minute 30 the gain is in the open games (6-9 copies: 53 -> 58 of 105) and in three of the 18 maps with ten
or more, which the default never wins.

**The mission and the full trainer are the default since** (`Strategy.forGame`: `shred`, `shred_strict`,
`shred_min_hp` 0, `shred_fill` 0.8, `shred_lurk` 28, `chief_topup_any`): it plays `sh6f-vs15`'s games (`fix-m`, 8
of 8) and with those six set back the games of before (`fix-n`, 8 of 8). On the fresh maps that is 158 of 300 at
N=15 (52.7 %, two draws); no exam seeds were spent.

The three draws are a new kind of game: the base stands for six hours with 0-30 warriors against 7-12 housed
copies and 500-1700 warriors (s21339, s21556, and s21064 with the options at our base). The default lost such maps
within the hour; now nothing converts them: iron comes in at 10 a minute and every weapon goes out as it is made.

Screen `bt2`, on the new default (run 0 is `bt1-06`, 62 wins): wins by run 62 60 59 55 67 51 58 57.

| factor | wins of 100 | state model at minute 30 |
|---|---|---|
| at our base (`shred_base`, `shred_bait`, `shred_buildings=99`) | -5.8 +- 2.7 | -3.4 +- 1.4 |
| `chief_dodge` (he steps towards a tower from warriors hunting him) | -2.8 +- 2.3 | -2.5 +- 1.8 |
| `shred_gate` (the muster's gate counts him as if he marched along) | -0.8 +- 2.2 | -1.8 +- 1.2 |
| `chieftain_ticks=24000` (the first chieftain from 480 s, not 300) | +4.2 +- 4.2 | +2.5 +- 2.9 |

So the options at our base are harmful after all (13.7 casts a game catching 314 instead of 11.8 catching 265, and
3.4 chieftains lost against 2.7), and stay off with the dodge and the gate bonus.

### After the first campaign under the blast default: one attack that never ends (`lab/fable/rebuild.py`)

The new default on the 16 logged long maps, full length (`nd1-log-vs15`): 11 wins, 4 losses and a draw where the old
default has 7, 8 and 1; the wins take 70-174 minutes. What a long game looks like now (s21111, won at 174 min): from
minute 20 to 120 iron comes in at 14-19 a minute, 40-50 warriors are out on an attack that is reinforced with 12
more every 45 s (at the unit cap every home group of 12 leaves) and loses 13-17 a minute, we kill 42-65 a minute,
and seven copies stay housed throughout; at minute 120-140 the copies' warriors fall from 250 to 24, our army grows
to 146 and takes the rest in half an hour at 2 warriors lost a minute.

The pace, over the default's 300 fresh games (`sh6f-vs15`, `sh6g-vs15`; per game still running): in minutes 10-20
4.2 copies lose their last quarters or armory, from minute 20 on 1.5 in every ten minutes, of which 0.1 have one
again within five minutes and 0.15-0.4 later. So the copies do not rebuild; the pace is what 15 warriors a minute
buy, about 100 warriors a copy where the first campaign pays 32.

Massing them does not buy it back (16 logged long maps, 120 minutes each; the default has 7 wins and 4 losses by
then, and unhouses 1.0-1.45 copies in ten minutes from minute 30):

| | wins / losses by minute 120 | copies unhoused in ten minutes, minutes 30-80 | home army lost in the 120 minutes (the default: 270 in its full games) |
|---|---|---|---|
| `late_reinforce_min=50` (from minute 30 the home group joins only as a clump of 50; `pu1-log-vs15`) | 6 / 4 | 0.6-1.2 | 355 |
| `late_reinforce_min=999,late_min_strength=80` (no reinforcing, launches of 80; `pu2-log-vs15`) | 7 / 4 | 0.6-0.9 | 442 |
| `bank=80,bank_always=true` (weapons wait in the armory, an attack out or not; `pu3-log-vs15`) | 7 / 6 | 1.0-1.4 | 189 |
| `bank=120,bank_always=true` (`pu4-log-vs15`) | 6 / 7 | 0.8-1.4 | 183 |

Warriors that wait at home die at home, and weapons that wait in the armory leave the base to the towers and the
chieftain, which costs two or three more bases. Nothing that waits has paid at N=15, early or late.

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
