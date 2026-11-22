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

Fourth session, the chieftain's blast ("Minutes 15-25" and "The chieftain's blast" below): the chieftain no longer
stuns for the army. He keeps away from it, waits by the enemy's parked waves and blows the sonic blast into them
(Gauntlet's shred mission, repaired: he blasts from the blob's cast point, waits by the next blob instead of walking
home, and his training quarters is kept full at the unit cap), and the first one is trained from 480 s instead of
300. On the 300 fresh development maps at N=15: 158 wins with the mission against 139 (+44 -25), 171 with the later
chieftain too (57 %), and on 300 more that chose nothing 174 against 134 (58 % against 45 %, +64 -24). The base
now holds through minutes 15-30, where it used to lose its second armory and its iron. Against sixteen the same 300
maps give 124 wins (41.5 %).

Exam of the fourth session's default (2026-11-16): frozen tag `@f3` (the source at commit afccc03c; it plays the
source's games, `f3-check` 8 of 8), seeds 36501..36700, never used for any decision, 200 games each, none failed, no
swallowed errors:

| N | `@f3` | `@f2` on the same seeds | paired, `@f3` against `@f2` |
|---|---|---|---|
| 15 | 102 / 200 = 51.0 % [44.1, 57.8] | 74 / 200 = 37.0 % [30.6, 43.9] | +43 / -15 (z 3.7) |
| 16 | 68 / 200 = 34.0 % [27.8, 40.8] | | |

So the highest N beaten at 50 % or more is now 15, again right at the line: the point estimate is above 50 %, the
interval reaches below it. `@f2` scores on these seeds as on its first exam (37.0 % against 35.2 %), so they are no
easier; the development maps said 58 % for `@f3`, 7 points higher than its exam (about 1.5 standard errors of the
difference). Exam seeds used so far: 36001..36700.

Against the original Gauntlet (`@g16`) at high N (2026-11-16, at the user's request), each pair on the same maps:
N=15 on the exam seeds already played above (36501..36700, `exam-g16-vs15-c`), N=16-20 on 100 maps never used for
anything (31001..31100, `hn-f3-vsN`, `hn-g16-vsN`). No failed game, no swallowed error.

| N | `@f3` | `@g16` | paired, `@f3` against `@g16` | minutes survived, more | copies out by minute 30, more |
|---|---|---|---|---|---|
| 15 (200 maps) | 102 = 51.0 % [44.1, 57.8] | 60 = 30.0 % [24.1, 36.7], 1 draw | +56 / -14 (z 5.0) | +3.9 (z 4.2) | +0.34 (z 2.4) |
| 16 | 43 % [34, 53] | 15 % [9, 23] | +34 / -6 (z 4.4) | +6.9 (z 5.1) | +0.61 (z 3.4) |
| 18 | 10 % [6, 18], 1 draw | 3 % [1, 9] | +8 / -1 (z 2.3) | +5.2 (z 4.7) | +0.50 (z 3.0) |
| 20 | 0 % | 0 % | | +0.7 (z 1.1) | +0.38 (z 2.3) |

With the exam table above (Gauntlet 60.5 % at N=13, 41.5 % at 14): Gauntlet crosses 50 % at about N=13.5, `@f3`
at about N=15 (51 % at 15, 34-43 % at 16 on three sets of maps), and `@f3` at N=16 wins about as often as Gauntlet at
N=14. At N=20 both are overrun in 30 minutes: the copies take about four out by minute 20, then the rest overwhelm
the base whichever AI it is.

The same at 1000 maps per N (2026-11-17, at the user's request: 100 maps gave too few wins at N=18 and 20): the 100
above and 900 more never used for anything (31101..32000, `hk-f3-vsN`, `hk-g16-vsN`; `lab/fable/highn.py` pairs a
side made of several runs). 5400 games more, none failed, no swallowed error.

| N | `@f3` | `@g16` | maps won by both / only `@f3` / only `@g16` | sign test | minutes survived | copies out by minute 30 / by the end |
|---|---|---|---|---|---|---|
| 16 | 377 = 37.7 % [34.7, 40.7], 2 draws | 127 = 12.7 % [10.8, 14.9], 6 draws | 85 / 292 / 42 | z 13.7 | 57.6 -> 66.6 | 4.42 -> 5.01 / 5.90 -> 8.91 |
| 18 | 96 = 9.6 % [7.9, 11.6], 1 draw | 10 = 1.0 % [0.5, 1.8], 3 draws | 6 / 90 / 4 | z 8.9 | 37.7 -> 47.1 | 4.16 -> 4.70 / 4.34 -> 5.93 |
| 20 | 9 = 0.9 % [0.5, 1.7] | 3 = 0.3 % [0.1, 0.9] | 2 / 7 / 1 | z 2.1 | 30.5 -> 32.6 | 3.85 -> 4.36 / 3.90 -> 4.50 |

So the first 100 maps flattered `@f3` at N=16 (43 % there, 37.2 % on the next 900); its N=16 figures are now 34 %
(exam), 37.7 % (these 1000) and 41.5 % (21601..21900). `@f3` at N=16 wins about as often as Gauntlet at N=14 (41.5 %
on the exam), and at N=18 about as often as Gauntlet at N=16 (9.6 % against 12.7 %): it is worth about two Hard
copies more. Its win rate falls about fourfold from 16 to 18 and tenfold from 18 to 20; Gauntlet's twelvefold, then
threefold. Every 1-in-10 map `@f3` wins at N=18 is one Gauntlet loses (90 of 96), and at every N it lasts longer
and puts out more copies before it falls (z 4-16).

Gauntlet's record (2026-11-17/18, at the user's request; "Gauntlet's record, searched the same way" below): `@f3` has a
verified, clean win at **N=25** (seed 10272, 296 min) against Gauntlet's N=24 (seed 9902, 216 min). The same search,
5,000 maps per N until two N in a row had none: N=21 21 wins, N=22 13, N=23 1, N=24 3, N=25 1, N=26 0, N=27 0 (N=28 0 in
4,810, cancelled). One win is as thin as Gauntlet's own record (1 in 4,320), and none of the three N=24 wins is clean.

Any seat (2026-11-19, at the user's request; "Any seat: a map is won when one of its starts wins it" below): counting a
map as won when fable beats the N Hards from at least one of the map's N+1 starts, the default wins **every one of
200 maps at every N up to 13**, 199 at N=14, 198 at N=15 and 193 at N=16 (100 fresh maps 22001..22100 and 100 held
out, 23001..23100; from the first start alone 99-100 of 100 up to N=7, 95-99 at N=8-11, 85-93 at N=12-13, 76-77, 52-62
and 38-42 at N=14, 15, 16). `@f3` on the first block: the same up to N=16, then 87 of 100 at N=17 and 76 at N=18.
The low-N losses it showed are seat-level leaks: a first armory a long walk from the start (repaired: idle peons walk
ahead, `opening_prewalk` 300, the default since, frozen as `@f4` at commit bbe0e145; seat 0 at N=8-11 +7 -1), a start
with no iron within a kilometre, a copy starting next door, and a walk to the far iron that passes a copy (all three
open: nothing tried on them helped). `@f4` has no exam of its own: at N=12-16 it plays `@f3`'s results (+7 -4 of
1,000 seat-0 games). The default since (`@f7`, "The mechanics left off as neutral" below) wins every one of the 200
maps at every N up to 14, 199 at N=15 and 196 at N=16, and from the first start alone 161, 135 and 92 of
200 at N=14, 15, 16 where `@f4` won 153, 114 and 80.

Standing peons (2026-11-19, the user's question; "Standing peons, and the moment with no armory" below): peons with no
order are counted now (`idle_s_*`). The median game has none; what there is stands by a site whose trees are cut (6
builders, up to 110 standing) and, far more, by the armory going up again after the last one fell (33 games of 100 at
N=15). Letting them all build (`idle_builders`) removes it and moves no wins (+19 -19 in 1,200 games; on since `@f6`,
with the builders' release and the tree on the way, below), a spare second armory loses 7 of 100 at N=15, and builders
that set out before a site is placed lose a little; those two are off. Of 244 games with an armory standing at every
moment 243 are won, of 56 with a moment of none 8: the standing marks a base being overrun. A rare swallowed assertion
in the caster hunt is repaired.

Mechanics left off (2026-11-20, the user's question; "The mechanics left off as neutral" below): of the options
that were off, fifteen each end a measured waste and had each been dropped after one flat screen (workers shut in
an armory with iron and no wood, satellites nobody staffs, tower sites with no trees or no place, wedged builders,
quarters bred up from empty, peons sent out by the rush alarm with no armory to go to, a muster with no army, a
stall clock that peons reset). All fifteen together, against the same build without them: from the first start at
N=8-16 **+99 -72** over 1,800 games (z 2.1), no draw where there were five, no swallowed error, the opening on the
same second. They are the default since, frozen as `@f7`; from `@f4` to `@f7` the same 1,800 games are +117 -70 (z
3.4). Options with a measured cost stay off. On 300 maps never used (25001..25300) at N=16, from the first start,
`@f7` wins 41.3 % [35.9, 47.0] and `@f4` 35.7 % (+59 -42): the 50 % line is still between N=15 and N=16.

The second shepherd (2026-11-20, the user: "don't assume the disabled features were perfect as written"; "The
shepherds: what a life buys, and the second shepherd repaired" below): Gauntlet's second shepherd for far copies had
been dropped as flat, and was flat here too (71 wins for 70 at N=15). In logged games both shepherds of a copy went
to the same place before its first wave. With that repaired it is +23 -15 on 200 maps at N=15 and, on 300 maps
never used, **154 wins for 124 at N=16: 51.3 % for 41.3 %** (+60 -30, z 3.2). The default since, frozen as `@f8`;
on 300 maps of its own `@f8` wins 49.3 % at N=16 for `@f7`'s 42.0 % (+60 -38), 50.3 % over the 600: N=16 is at
the line now, as N=15 was for `@f3`. No harm lower down (N=8-14: +24 -13 over 500 games).
A pause before a lost shepherd's replacement, recruits with no load and an earlier walk round all lose or do nothing:
a wave at our base costs far more than the peon that would have drawn it.

A way for the shepherds (2026-11-20; "A way for the shepherds" below): half the shepherds lost never had a wave
aimed at them, and the three switched-off options for their walk each look 8 to 60 cells ahead and do nothing. A
planned way round enemy warriors, bases and towers, built new: those recruited in minutes 20-40 reach their spot in
53 % for 23 %, and at N=16 on the 600 maps never used `@f8`'s 50.3 % becomes **56.2 %** (337 wins for 302, +100
-65). The default since, frozen as `@f9`. From `@f7` to `@f9` in a day: 41.7 % to 56.2 % at N=16; at N=17 `@f9`
wins 40.3 % of 300 maps of its own, and nothing is lost lower down (N=15 on the first block 70 -> 78). The planner
took 7 % of the simulation's CPU; with a heap and the hot blocks by the goal free it takes 4.7 % and wins more:
`@f10`, **59.3 % at N=16** (356 of the 600); at N=17 37.3 % (`@f9` 40.3 % on the same 300 maps).

The micro round (2026-11-20/21, user: no new strategy, the handling of single units; "The micro round" below).
Tracers of what the units do second by second found three flaws in how peons live among the enemy. A peon fled
from every enemy warrior within 11 cells, also from one locked in a hunt on another peon, which sees nothing until
that hunt ends: 7,190 orders to shelter a game, and from minute 24 one trip for iron in ten was delivered. A
gatherer whose load had not changed for 70 s was called stuck and sent elsewhere while it was walking to iron 100
cells out. And a gatherer that stepped out of a parked group's way was sent straight back through it. With the three
mended (`bystander`, `stuck_still_ticks`, `gather_parked`): 12 % more iron an hour, fewer peons lost, a ninth less
CPU, and at N=16 on 300 maps never used **68.0 %** for `@f10`'s 59.3 % (204 wins for 178, +50 -24). The default
since, frozen as `@f11`; with a fleeing peon left on its way to shelter and the chieftain's mission under the same
rule, `@f12`: **71.3 % at N=16** (214 of the 300) and **52.0 % at N=17** (156 of 300 maps of its own, `@f10` 112).
The warriors' own fighting has little left in it (they throw 70-75 % of the time with an
enemy in reach, the Hard copies 63-65 %); routed reinforcements arrive alive and win nothing; the chieftain blasts
once in 320 s where the charge takes 70, and three tries at that changed nothing.

The tower round (2026-11-20, user: a counter to the enemy's towers; "The towers" below). `TowerTrace`: a
garrison kills one of ours in about four seconds whatever stands in its reach, 104 warriors a game, and a razed
tower stands 29.5 s from the first warrior in its reach, 19 of them before anything damages it, because nothing
orders a warrior onto a tower. Three counters were built and none is a default. Ordering the warriors onto it
(`storm`) halves that wait and takes 30 % of the garrison's kills, and loses 15 games of 150: the warriors it takes
are the ones that cut the copy down. The sappers alone, sooner or more of them, are even or worse. And a tower site
of ours as a bait (`bait`): the garrison throws at the first thing it finds until that is dead, a mended site
holds it for a minute and the towers razed under it cost 0 to 3 warriors for 9; but the site has to stand before
the army comes, which worked for one tower in fifteen, and as built (two building slots kept free, ten peons
with the army) it costs 22 games of 150. `fable` still plays `@f12`'s games.

The chieftain round (2026-11-20, user: the chieftain next; "The chieftain's blast, traced" below). `ChiefTrace`:
his blasts kill 204 enemy warriors an hour of game, a fourteenth of our kills, 27 taken a blast; he is there 2,540 s
of an hour, which allows 33 casts (not 51), and makes 12, because a charge waits 72 s for its blast: the blob he
walks to is chosen anew every half second, a walking wave that passes sees him 16 times an hour, and one to seven
warriors hunting him turn a charged blast back to the armory ten times an hour. Five repairs were built
(`shred_point_blank`, `shred_press`, `shred_stick`, `shred_sidestep`, and a wider range with them), each does what
it is for, and none is a default: the best is an eighth more blasts at the same losses, on two blocks of maps, and
no arm wins more games than the default (101 to 108 of 150 for its 110; 210 of 300 for 214). His stun with the army
(`shred_from_ticks`) never reaches a tower and is ten seconds from where he may stand. `fable` still plays `@f12`'s
games.

The home army round (2026-11-20, user: the home army's lopsided fights; "The home army, traced" below). `HomeTrace`,
and on every warrior's death the order it had: the home army's fights trade 1.6 enemy warriors for one of ours, as
the attack's do, and 1.7 to 2.0 at every ratio of numbers when it engages in the base. What was lopsided was the
company: half its 155 deaths an hour of game came with seven mates or fewer about, because each defender walked to
the enemy nearest to it from where it stood, and a fifth of its time went into answering enemies by peons up to a
hundred cells and more from the base. Gauntlet's old post (`hold_ratio`, `hold_multi`: meet a threat at our
building nearest to it, a tower if one is close), off since a screen at N=15, takes a third of those deaths away. As
written it changed buildings every 15 s; `hold_stick` and `hold_commit` keep it in place. At N=16 on two blocks of
maps never used it wins 331 of 450 for 310 (+53 -32), and at N=17 162 of 300 for 156. The default since, frozen as
`@f13`: **72.3 % at N=16** (217 of 300 fresh maps, `@f12` 204 on the same), **54.0 % at N=17** and **33.7 % at
N=18** (101 of 300 maps of its own). `hold_levels`, `hold_far` and `threat_sappers` were built with it and stay off.

The reinforcements round (2026-11-21, user: the next pick; "The reinforcements, traced" below). `ReinforceTrace`
follows every group from the armory to the army. A group of twelve is everyone in role army wherever they stand, 48
cells from end to end from its first second, and it never closes up; it meets the enemy a few at a time and kills
0.84 for one of its own, 86 dead of 497 sent an hour. Gathering it first (`reinforce_gather`) closes it up and
loses: five more warriors stand at home at any moment, an eighth fewer reach the army, 97 wins of 150 for 109. What
looked like waste is the home army being emptied into the attack as fast as it fills, and a wait costs here what
every wait has cost. Two repairs that wait for nothing work and win nothing: a group that fights as one body
(`reinforce_march`, 1.4 for one) and an army that falls back on the reinforcements on their way instead of taking
them home (`retreat_regroup`, a tenth more of them joined). Together 210 of 300 for 213 at N=16 and 160 of 300 for
162 at N=17: the default since, as waste removed that shows no harm, frozen as `@f14`. Found on the way: two thirds
of the attack army's time is the walk between targets with nothing near.

The march round (2026-11-21, user: yes to that lead; "The march, traced" below). `MarchTrace` follows the attacking
army second by second. The walk is the map's: the units walk at a warrior's pace, the targets are the nearest, the
way is the straight line, and what is left is 274 m between one copy and the next, 13.6 times an hour; it is a third
of the time out in the first campaign and two thirds only past the hour. What the army does stop for is small: 260 s
an hour for peons with no enemy warrior near, 200 s for one or two warriors, 29 holds. Three options take those away
(`march_past`, `engage_min`, `hold_approach`), the campaign gets faster by a third of a copy at minute 20, and the
wins do not move: 209 of 300 for 213 with `march_past`, 108 of 150 for 109 with `engage_min` too, 104 for 109
without the hold. All off; the default stays `@f14`. Copies taken out sooner are not games won: that reading tells
how a game stands. Where the games are lost: the slowest fifth of the openings, the far armories of the second
session, wins 40 % and the rest 79 %.

The opening round (2026-11-21/22, user: openings next, "feels like the most important phase of the game"; "The
opening, traced" below). `OpeningTrace` follows every peon through the first ten minutes, and games cut at minute 10
measure an opening in minutes (`lab/fable/openspeed.py`). Nobody stands in the default's opening, but its builders
walk more than half their time and four things were plainly off: the sites are visited in the order they were
planned (127 cells of walking where the shortest order has 77); the shepherds leave when their copy's armory stands,
though its first wave comes 146 s and more after it; a rush alarm made for one enemy goes off in the slowest seventh
of the openings and cuts their quarters to three builders for four minutes; and a quarters keeps two peons while the
opening builds, where six breed more than they would build. With the four repaired (`opening_route`,
`shepherd_lead_ticks=7000`, `rush_response` off, `hold_build` and `hold_early` 6) an opening has 24 %, 17 % and 10 %
more warriors at minutes 6, 8 and 10 and is at the cap half a minute sooner; whole games have 6 to 10 more warriors
through minute 10 and 0.2 to 0.4 fewer copies housed from minute 8 to 20; and on three blocks of 150 maps never used
it wins 107 for 105 and 112 for 106 at N=16 and 92 for 84 at N=17 (+57 -41, every block ahead), and at N=18 46 for
43. Frozen as `@f15`. On top of it, two of the quarters before the armory stand along the way to it
(`opening_chain`), which puts the second quarters up 16 s sooner and adds 2 to 3 warriors through minute 10, and
nobody walks ahead to a far armory site before the first quarters stands (a fault that took the first quarters'
builders in one opening of 100): level in whole games, 412 of 600 for 411 on four blocks never used at N=16 and 17,
and adopted as walking taken out with no harm. Frozen as `@f16`. `hold_mid` 6 (six inside after 240 s too) moves
the army later and wins nothing (+53 -49). What did not help: quarters by the start before the walk to a far armory,
tree claims between the
sites, more builders on the last quarters, the armory a quarters earlier or later, a seventh quarters, the true
harvest time in the cost model. What decides an opening's speed beyond all this is how far the armory stands from
the start: 117 units at minute 5 under 45 cells, 75 at 90 cells and more, a quarter of the maps.

What a quarters should keep (2026-11-22, the user: can the six be proved, would a value that changes with the game
do better, is seeding worth another look; "What a quarters should keep inside" below). There is a proof for the
growth to the cap: if work is worth the same whenever it is done, the quarters should keep between them half the
units still missing, whatever their number; the optimum is flat (1.8 % of the work), and its message is the shape,
a hold that falls towards the cap where the old one rose to eight. While the opening builds there is only a
plateau, four to eight, in the games and in a model of the opening fitted to them (`lab/fable/breed.py`). Seeding
a new quarters with two of its builders is worth two peons a quarters by the law and 1.9, 3.0 and 0.9 warriors at
minutes 6, 8 and 10 in the games; with a quarter of the missing units kept from the armory to the cap, 2.0, 10.7
and 5.2. In whole games those warriors are there (ten to twelve more at minute 8 on every one of five blocks) and
the wins are the same, 451 of 750 for 454: they come after the first attack has left. The default since, as waste
taken out with no harm, frozen as `@f17`.

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

**A later first chieftain.** With the mission he has nothing to blast before the first waves park, and his
training takes 40 births of a quarters out of the opening. `chieftain_ticks=24000` (480 s, when we are at the unit
cap, instead of 300 s) on the 300 fresh maps (`ct8f-vs15`, `ct8g-vs15`):

| | 21301..21450 | 21451..21600 | all 300 | to a win / to a loss | warriors at minute 10 | copies out at minutes 10 / 15 |
|---|---|---|---|---|---|---|
| the mission and the full trainer, chieftain from 300 s | 84 | 74 | 158 | | | |
| chieftain from 480 s | 87 | 84 | 171 | +46 -33 | +8.6 and +9.6 (z 9 and 10) | +0.25 (z 5 and 4) / +0.19 (z 3 and 2) |

On the 100 open maps it shows less: +4.2 +- 4.2 in `bt2`, 0.0 +- 5.7 in `lg2` (below), 64 alone against 62; from
600 s 53 (`ct10-band-vs15`), from 720 s 58 and a draw (`ct12-band-vs15`). (Before the mission 240 s and 420 s were
both worse than 300 s: his stun was part of the first campaign.) **480 s is the default since** (`fix-o`: the
default plays `ct8f-vs15`'s games, 8 of 8; with `chieftain_ticks=15000` `sh6f-vs15`'s, `fix-p`, 8 of 8): 171 of 300
on the fresh maps at N=15 (57 %), the least certain of the three steps.

**An independent block.** The three steps were chosen on maps 21301..21600, so 57 % flatters them. Maps
21601..21900, never used before: the default wins 174 of 300 (58.0 % [52.5, 63.6], one draw; `nd2h-vs15`), the
default of the morning (the mission, the full trainer and the later chieftain off; `et1h-vs15`) 134: +64 -24, z 4.3,
with 10.8 more warriors at minute 10 and 0.2 more copies out at minutes 10, 15 and 30. Also played with no failed
game and no swallowed error: 20 of 20 against one Hard, 12 of 12 against six, 19 of 20 against ten (large maps,
`rb1-vs1`, `rb6-vs6`, `rb10-vs10`).

Against sixteen on the same 300 maps (`nd2h-vs16`): 124 wins (41.5 % [36.1, 47.1], one draw). So on development
maps the default stands at 58 % against fifteen and 41.5 % against sixteen.

Screen `bt3` around the default (run 0 is `lg2-04`, 64 wins of the 100 open maps; wins by run 64 63 61 65 66 61 64
64): `hold_chieftain_again=40` (the trainer of a chieftain after the first keeps 40 peons, 129 s of training) -0.5
+- 1.8, `evade_tower=70` 0.0 +- 2.4 (so the reach of the step towards a tower, open since the morning, is flat),
`shred_range=200` +0.5 +- 3.2, `shred_min=6` 0.0 +- 2.9. The mission's settings are flat around the default.

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

Screen `lg2` on the 100 open maps (run 0 is `bt1-06`, 62 wins; wins by run 62 65 63 64 64 64 64 62): the bank as
first built, `bank=80`, +0.5 +- 1.7 (it starts twice a game and fills 0.4 times: an attack is out nearly always),
a floor under late launches, `late_min_strength=50`, -0.5 +- 1.3. Both off.

### Gauntlet's record, searched the same way (`lab/fable/scan.py`, `spawns.py`, `cleanwins.py`)

The user (2026-11-17): "see if this will beat gauntlet's record: scan 5000 maps for each N>20 until you get two N in a
row that have no wins". Gauntlet's own record, from its notes and taken at face value (its search is not rerun):
N=24, seed 9902, `cur16` (the AI frozen here as `@g16`), won at 216:52, replay VERIFIED, clean spawns (no two copies
within 20 cells, the nearest copies 45 and 51 cells from us), found after 4,320 N=24 games on seeds 6001..11000
(`frontier_search.py`: 0/10/10, ludicrous, 5,000 games per N, stop once two N in a row have none; cur15 had N=21 0 in
2,436 and N=22 one win in about 1,860; cur16 N=23 0 in 5,000, N=24 1, N=25 0 and N=26 0 in 5,000 each).

`@f3` played the same 5,000 maps (seeds 6001..11000, none ever used by fable) at N=21, 22, ... two N at a time, every
N as one batch `sc-f3-vsN`; 19:05 to 05:28, 39,810 games, none failed. N=26 and N=27 both had no wins, so the scan
stopped, and N=28 (0 in 4,810) was cancelled. A win is *clean* by Gauntlet's standard when every copy is real: no
copy alive at 20 minutes without an armory (`spawns.py`: about 1 copy in 300 is stuck for good by its spawn, and the
copy our opening strike removes is a kill, not a stuck one) and no two copies starting under 20 cells apart
(`cleanwins.py`).

| N | `@f3` wins in 5,000 | draws | wins with every copy armed | clean wins (and no pair under 20 cells) | Gauntlet (its notes) |
|---|---|---|---|---|---|
| 21 | 21 (0.42 %) | 0 | 17 | 15 | cur15 0 in 2,436 |
| 22 | 13 (0.26 %) | 4 | 10 | 7 | cur15 1 in about 1,860 |
| 23 | 1 | 0 | 1 | 1 | cur16 0 in 5,000 |
| 24 | 3 | 1 | 0 | 0 | cur16 1 in 4,320 (clean): the record |
| **25** | **1** | 0 | **1** | **1** | cur16 0 in 5,000 |
| 26 | 0 | 0 | | | cur16 0 in 5,000 |
| 27 | 0 | 1 | | | not played |
| 28 | 0 in 4,810 | 0 | | | not played |

The wins at N=23 and above (all six replay VERIFIED in a fresh JVM):

| N | seed | length | field |
|---|---|---|---|
| 23 | s7834 | 147 min | clean: effective N 23, closest pair of copies 29 cells, nearest copies 42 and 51 cells from us |
| 24 | s6601 | 240 min | one copy armed at 25 min (slow, but it armed), one pair at 20 cells: effective N 23-24 |
| 24 | s9974 | 94 min | crowded starts (13 pairs under 20 cells, two 3 cells apart), one copy never armed and was put out at 27 min |
| 24 | s10386 | 335 min | one copy armed at 49 min: effective N 23 |
| **25** | **s10272** | **296 min** | **clean: the 24 copies not removed by the opening all armed by minute 8 (the nearest one, s2 at 42 cells, never did and was put out at 7.7 min, the same pattern as Gauntlet's record), nearest copies 42 and 50 cells from us; the closest pair of starts is exactly 20.0 cells (s12, s17), every other pair at least 36; checksum -1508468867** |

So by Gauntlet's own standard the record moves from N=24 to N=25, by one clean win in 5,000 games. How much that is
worth: the rate at N=23-25 is about one win in 5,000 (95 % interval 0.001 % to 0.11 % for a single win), as thin as
Gauntlet's one in 4,320, so the frontier is N=25 give or take one, not a certain 25. The rates below it are the firmer
comparison: N=21 0.42 % and N=22 0.26 % against Gauntlet's 0 in 2,436 and 1 in 1,860, and at N=20 `@f3` 0.9 % against
`@g16` 0.3 % on 1,000 maps. The N=24 wins are all against a weakened field (one copy that armed late or never): the
stuck copies are 1 in 300 per copy, so at N=24 and above they are in 7 % of games, and a stuck copy is how most wins
there come about (all three N=24 wins, against 2 of 21 at N=21).

On Gauntlet's record map, s9902, `@f3` wins at N=21 (107 min) and loses at 22 (31 min), 23 (28 min) and 24 (31 min).

### Any seat: a map is won when one of its starts wins it (`lab/fable/anyseat.py`, `barren.py`, `near.py`, `route.py`, `subset.py`)

The user (2026-11-19): instead of 50 % of the games, win 100 % of the maps at as many N as possible, where a map
counts as won when fable wins it from one or more of its N+1 starts; 100 or 200 maps at a time are enough. The point:
a map lost from the first start may only have a bad first start, a map lost from every start is a bad map or a leak
of ours, and the losses at low N are where leaks show.

The harness seats by rotation already: `--side S` puts our AI in slot S and the Hards in the others, and the starts
belong to the slots, so the N+1 seats of a seed are the same map with the same N+1 starts. Nothing in it changed.
`dev.sh` takes `SIDE=S` (or `SIDE=all` for every seat in one batch); `stats.py`, `spawns.py` and `cleanwins.py` read
our slot from the row instead of assuming slot 0. `anyseat.py` plays the ladder in rounds: seat 0 on every map, then
seats 1, 2-3, 4-7, 8-15, ... only on the maps not won yet, each seat one batch `as-NAME-vsN-sS` (a batch that exists
is read, not replayed). That is a tenth of the full matrix at N=12-14 (130-190 games instead of 1,300-1,500) and a
quarter at N=16. The price of the order: the games played are mostly the hard maps' seats, so "games won" below is
not the win rate of a random seat.

Maps 22001..22100, never used before; maps won from some start / from seat 0 / games played. `@f4` is `@f3` with the
pre-walk below (the default since); no game failed, no swallowed error.

| N | `@f3` | `@f4` | lost from every start |
|---|---|---|---|
| 1-3 | 100 / 100 / 100 each | 100 / 100 / 100 each | |
| 4 | 100 / 99 / 101 | 100 / 99 / 101 | |
| 5-7 | 100 / 100 / 100 each | 100 / 100 / 100 each | |
| 8 | 100 / 97 / 103 | 100 / 99 / 101 | |
| 9 | 100 / 95 / 105 | 100 / 96 / 104 | |
| 10 | 100 / 96 / 108 | 100 / 98 / 104 | |
| 11 | 100 / 94 / 108 | 100 / 95 / 105 | |
| 12 | 100 / 89 / 128 | 100 / 90 / 127 | |
| 13 | 100 / 85 / 133 | 100 / 85 / 133 | |
| 14 | 99 / 76 / 194 | 99 / 76 / 188 | 22018 |
| 15 | 98 / 62 / 230 | 98 / 62 / 230 | 22018, 22095 |
| 16 | 95 / 37 / 464 | 95 / 38 / 463 | 22018, 22082, 22088, 22095, 22097 |
| 17 | 87 / 21 / 813 | | 13 maps |
| 18 | 76 / 10 / 1,033 | | 24 maps |

Maps 23001..23100, held out: never used before, and nothing was decided on them (the pre-walk was the default
before their first game).

| N | `@f3` | `@f4` | lost from every start |
|---|---|---|---|
| 1-7 | | 100 / 100 / 100 each | |
| 8 | | 100 / 98 / 102 | |
| 9 | | 100 / 99 / 101 | |
| 10 | | 100 / 98 / 104 | |
| 11 | | 100 / 95 / 107 | |
| 12 | 100 / 92 / 121 | 100 / 90 / 116 | |
| 13 | 100 / 91 / 119 | 100 / 93 / 113 | |
| 14 | 100 / 77 / 143 | 100 / 77 / 141 | |
| 15 | 100 / 52 / 264 | 100 / 52 / 250 | |
| 16 | 98 / 41 / 411 | 98 / 42 / 418 | 23002, 23072 |

So `@f4` wins every one of the 200 maps from some start at every N up to **13**, 199 of them at N=14, 198 at N=15
and 193 at N=16; the two blocks differ by what two maps in a hundred allow (99 and 100, 98 and 100, 95 and 98).
Seat 0 alone, the usual benchmark, is under 100 % from N=8 on (N=4 too, by one map), and the second start already
wins most of what the first lost: at N=13 eight of the 15 maps lost from seat 0 are won from seat 1, at N=16 it takes
up to 16 starts. Below N=14 the map is not what loses: one or two starts in fifteen are bad.

The losses at low N are on the maps that leave the ladder later. `@f3` loses 18 games from seat 0 at N=8-11, and 9
of them are on four of the five maps lost from every start at N=16 (22088 at N=8, 9, 10 and 11, 22018 at 10 and 11,
22097 at 9 and 10, 22095 at 11), where chance makes one; the rest are 22003 (leak 2) twice, 22064 twice and five
single maps. So a hole found at N=8 is a hole of the frontier's maps.

**Leak 1, a first armory a long walk away (repaired, the default since: `opening_prewalk` 300, frozen as `@f4`).**
At N=8-12 from seat 0, a first armory 300 m or more of walking from the start won 2 games of 9, and an armory that
stood after 5.5 minutes 3 of 11, where seat 0 wins 94 of 100. In the opening one peon carries the armory site
while the others build the first quarters, and builders are given only to a site that is placed: with the site 300 m
out, the peons the quarters do not need stood idle at home until the carrier arrived, and then walked the same 300 m.
Now they walk ahead to a point 9 cells short of the site as soon as the plan says the site is 300 m or more away
(counter `prewalk_sent`; a peon within 30 cells of a quarters that still wants peons goes in first). s22088 at N=8:
armory at 321 s instead of 430 s, a loss at 12.7 min becomes a win; s22077: a loss at 14.6 min becomes a win. Seat 0
on the 100 maps (`bsA-vsN` against `as-f3-vsN-s0`): N=8 97 -> 99, N=10 96 -> 98, N=12 89 -> 90, N=14 76 -> 76 (+1
-1); 23 of 400 games change, +6 -1. With it, searching iron out to 1,300 m (`armory_iron_far`, `bsB`) changes no
game, and a wider first move (`opening_move_m=500`, `bsC`) three, none in its result. The first version walked ahead
to every site more than 20 cells out: on the N=15 development maps 21301..21600 171 -> 173 of 300 (+10 -8), with
ordinary armories 0-5 s later, so the trigger became the walk. On the ladders, `@f4` against `@f3` from seat 0:
N=8-11 99, 96, 98, 95 against 97, 95, 96, 94 (+7 -1); N=12-16 +4 -2 on 22001..22100 and +3 -2 on the held-out maps.
The repair is worth a map or two in a hundred at N=8-11 and nothing at the frontier, where leak 4 undoes it: over
the 23 seat-0 games at N=8-14 in which it fires the armory stands 20-110 s earlier in 12 (N=8-10 mostly) and 9-142 s
later in 7 (N=12-14), results +6 -1.

**Leak 2, a start with no iron (open).** s22003 from seat 0 has 2 iron nodes within reach and the next iron about
1,020 m of walking away: the one loss of the ladder below N=8 (N=4, lost at 171 min with 30 iron harvested in the
whole game). Every map has 145 iron nodes, most of them in the interior (mean radius 95-100
cells, the starts on a ring farther out), so a bare start is the seat's, not the map's, and seat 1 wins the map. Tried:
`armory_iron_far=1300` finds the far iron, the trek passes copies and fails, and then the armory is planned on
the same far site again for the rest of the game with up to 39 peons idle (the default does the same at N=11 on
s22003, site 799 m out). `opening_fallback` (off) plans the next armory by the safest quarters once a first site was
lost before any armory stood: N=11 s22003 lost at 17.6 min instead of 16.1, and it fired in 1 of 326 other games
(`fbk-vsN`; N=12 s22095, lost at 20 min instead of 15), so it stays off for want of evidence, not because it hurt.
The iron expansion that exists (`expansion_iron_need`, `_far`, `_crew`) did not rescue N=4 s22003 either: the far
site loses the expansion's score, and with `expansion_iron_ticks=30000` it fired at 8,499 s (`xi1-vs4`, `xi2-vs4`).
What would: an armory by the home rock first, then a guarded second base at the far iron. Not built.

**Leak 3, a copy starting next door (open; nothing tried helps).** Over the 1,149 ladder games of `@f3` at N=12-16
(`near.py`), by the distance from our start to the nearest copy's:

| nearest copy | games | won | our units lost by 2:00 (of 20) | opening strike kills its copy |
|---|---|---|---|---|
| under 20 cells | 8 | 0 | 15.2 | 0 % |
| 20-29 | 7 | 0 | 10.7 | 43 % |
| 30-39 | 28 | 12 | | |
| 40 and more | 1,106 | | under 1 | 77-85 % |

(The share won keeps rising with the distance, 39 % at 60-69 cells, 65 % at 70-79, 80 % from 80, pooled over N and
biased by the order of play.) Why: the Hard's `nodeDefendBase` scans 30 m around its quarters and armory and sends
its idle warriors, idle peons and gatherers at any enemy unit there, an idle peon answers anything within 8 cells by
itself, and our peons under a build order do not fight back. Under 30 cells our twenty peons stand in that range at
the first tick. Replayed on those games (`subset.py`, sets `close` and `close34`, runs `nzK-vsN-sS`), all off by
default:

- no strike when the copy is under 700 ticks of walking (`freeze_min_eta_ticks`, nz1): worse, out before minute 10 in
  13 of 15 games against 9;
- the opening's buildings kept 30 cells from that start (`opening_keepout=30`, nz2): under 20 cells 0 -> 1 win of 8
  and the mean end from 7.4 to 30.5 min, 20-29 cells 0 -> 0, 30-39 cells 12 -> 13;
- both (nz3): worse than the keep-out alone;
- a strike of 19 peons instead of the usual squad (`freeze_near_ticks=700`, `freeze_near_squad`, nz4, and with the
  keep-out nz5): the copy dies, about 4 of our peons are left, no game won, and 30-34 cells 2 -> 0.

A human would do no better from that seat; it is the mirror of the stuck copies in "Gauntlet's record" above, and
the rotation exists to set it aside.

**Leak 4, the walk to a far first armory passes a copy (known since the third session, measured here; open).** The
stock AI sends its idle peons and gatherers at any unit within 30 m of its first quarters or armory, and they follow
what they chase: our builders walk on to the site with them behind, builders do not fight back, and a site with 1 hp
is razed. `SitePlanner.hotRoute` follows the start field from the planned site back to the start; the log line
`route to armory#1 passes C cells from the start of sK` is the gap between that walk and the nearest copy's start
(`route.py rt as-f4`: `rt-vsN` are one-minute logged games for the plans). From seat 0 at N=12-16 on 22001..22100,
500 games, the 15 with a copy starting under 40 cells from us set aside:

| the walk passes a copy's start at | games | won | our units lost by 5:00 |
|---|---|---|---|
| under 20 cells | 12 | 3 | 11.4 |
| 20-29 | 7 | 2 | 1.0 |
| 30-39 | 19 | 11 | 2.1 |
| 40-49 | 50 | 25 | 1.1 |
| 50-59 | 115 | 77 | 1.2 |
| 60 and more | 282 | 227 | 1.2 |

The bands from 40 cells up mostly sort the games by N (the copies stand closer at 16 than at 12). The 19 games under
30 cells do not: at the seat-0 rates of their own N they would win 11, and they win 5. Fourteen of them are armories
300 m and more out (4 won of 14, against 14 of the 27 far armories with a clear walk and 326 of the 439 near ones),
and in 13 the opening strike is on that very copy already. On 22095, lost from every start at N=15 and 16, six of
the seats played at those N lose 22-29 units in the first five minutes, by a copy's start or at our own site.
s22049 at N=14 shows the mechanism, and that the pre-walk can make it worse: by default the builders pass s9's
quarters in one group at 100-120 s, 10 units are lost by 5:00 and the armory stands at 318 s; with the pre-walk the
walkers pass ahead of the group, s9's peons come after them to the site, 28 are lost by 5:00, two quarters sites are
razed, and the armory has one builder for 90 s, then none for 70 s, and stands at 460 s.

Tried on the 38 games under 40 cells (`subset.py hot rvK`, before -> after; all off):

- no armory on such a walk (`armory_route_clear=30`, a charge of 20,000 ticks on the site, rv1): the 19 games under
  30 cells win 2 instead of 5: the armory goes up by the start (on s22049 at the start itself) and starves;
- the same at 40 cells (rv3): none of those 19, and 9 instead of 11 of the 19 at 30-39 cells;
- a charge of 4,000 ticks (rv2): 5 and 11, hardly a site moves;
- the whole tribe walks first (`opening_move_m=300`, rv4): 3 instead of 5 and 12 instead of 11, with three tribes out
  before minute 10.

So the far iron is worth the walk even at this price, as the third session found at N=15 ("The slow openings are
iron behind water"). What is missing is a way past the copy: a convoy that fights back, or peons bred by the start
that stay there until the copy is out. Not built.

**The maps lost from every start.** Not a seat's fault by construction, and not the spacing either: the starts lie
on a ring at every N (innermost start 130-170 cells from the centre), each with two neighbours, and the best-isolated
seat's nearest copy is 76 cells on the lost map at N=14 against 78 on the 99 won, 72 against 70 at N=16, 65 against
66 at N=17. What the lost maps share is a slow opening at most seats: iron nodes within 300 m of walking from the
start, median over the 17 seats of N=16 (`orx-vs16-sS`, one-minute logged games, `barren.py`): 14 on 22088 (where 7
of 17 seats also have the first armory 300 m or more out), 21 on 22018 and 23 on 22082, against 28-39 on five maps
that are won; 22095 (32) and 22097 (27) look ordinary. On 22018 at N=14 no seat gets below 9 copies left, four seats
last 70-124 min, and the map is won at N=13 from seat 4 (353 min) and at N=12 from seat 10 (225 min): it is the
ordinary long game, lost 15 times in a row at about the frontier's odds. From N=16 on that is what the ladder
measures (5, 13, 24 maps lost at N=16, 17, 18): the win rate of a seat falling (25 %, 13 %, 8 % of the games played)
faster than the seats are added.

Reproduce: `python lab/fable/anyseat.py --name f4 --n 1..16` (then `--report --detail` for the seats),
`--name f4h --spec "@f4" --seeds 23001..23100 --n 12..16` for the held-out maps (a seed block needs a name of its
own); `./aisim.sh freeze f4 fable` at commit bbe0e145 for the tag.

### Standing peons, and the moment with no armory (`lab/fable/idle.py`, `noarmory.py`)

The user (2026-11-19, on the pre-walk above): do peons wait idle until a site has been put down? "Peons should
probably never be literally standing around."

They do: a builder is given to a site only once its carrier has put it down, and before the first armory a peon
with no such site and no room in a quarters has no order. It seldom costs anything in the opening: the carrier
leaves in the first second, and a site under about 200 m of walking is down before the first quarters stands.
`Economy.countStanding` counts a second for every peon that has no order at two allocations in a row (counters
`idle_s_open` before the first armory stood, `idle_s_15`, `idle_s_30`, `idle_s_late` by the minute after). The default
on maps 22001..22100 from seat 0, peon-seconds a game (mean / 90th percentile / largest / games with any):

| N | before the first armory | to minute 15 | minutes 15-30 | later |
|---|---|---|---|---|
| 8 (`idl-vs8`) | 12 / 38 / 309 / 16 | 0 / 0 / 14 / 6 | 289 / 0 / 28,941 / 2 | 120 / 0 / 11,997 / 1 |
| 12 (`idl-vs12`) | 46 / 39 / 2,391 / 22 | 1 / 2 / 29 / 11 | 81 / 0 / 4,635 / 6 | 934 / 1 / 41,045 / 11 |
| 15 (`idl-vs15`) | 155 / 47 / 8,563 / 25 | 13 / 2 / 1,024 / 15 | 732 / 1,800 / 26,142 / 26 | 1,847 / 5,023 / 38,654 / 32 |

So the median game has none, and the standing there is comes in two places (the `IDLE` log line says where and in
what state):

- **A site with its trees cut.** `buildersWanted` gives a site 4 builders a tree within 12 cells and 6 at least. On
  s22022 at N=15 the first armory has 6 builders from 200 s on while 36, 74, 84 and 110 peons stand by it at 271 to
  361 s (8,563 peon-seconds; armory at 370 s, lost at 41 min); s22039 the same with 74 standing. Five openings in a
  hundred at N=15 and eight at N=12 stand 100 peon-seconds and more.
- **No armory.** Nearly all of the standing after minute 15 is while the last armory is down (152 of 162 `IDLE`
  lines in 14 logged games, `idg-vs12`, `idg-vs15`; the other 10 are by an armory emptied ahead of a wave): with an
  armory every spare peon works in it, with none there is the opening's rule only. The armory going up again takes 157 to 276 s
  with 60 to 178 peons standing by it (32 to 82 s for an armory built while another stands). At N=15 33 games of 100
  stand 300 peon-seconds and more after minute 15, and 6 of them are won; of the 55 with none 49.

`noarmory.py` on the same runs, by whether an armory of ours stands at every moment after the first:

| N | an armory at every moment | a moment with none | from minute 10: none / one / two or more, games won | games not won |
|---|---|---|---|---|
| 8 | 99 games, 99 won | 1, 0 won | 0 / 7 / 93 % | 43 / 57 / 0 % |
| 12 | 89, 88 won | 11, 2 won | 0 / 15 / 85 % | 23 / 28 / 49 % |
| 15 | 56, 56 won | 44, 6 won | 0 / 28 / 71 % | 18 / 41 / 41 % |

Before the first moment with none we stand at exactly one armory for a median of 312 s at N=12 and 355 s at N=15
(quartiles 160 to 679 s; 17 and 21 minutes in s22027 and s22016 at N=15, with no second armory so much as placed).
The second armory is an expansion only (`considerExpansion`: gathering a quarter cheaper elsewhere, no threat at the
base, a building slot free), and at the cap of 20 buildings a tower takes a razed armory's slot at once.

Three answers, each behind a flag, 100 maps a run, wins against the default's (`idl-vsN`):

| | N=12 (90) | N=15 (62) | what it does |
|---|---|---|---|
| `idle_builders=40`: with no armory standing, a peon left over joins a site, the armory's first, up to 40 builders (`ib40`) | 90 (+0 -0) | 64 (+3 -1) | standing after minute 15 at N=15 2,579 -> 1,069 peon-seconds a game |
| `idle_builders=100` (`ib100`) | 90 (+0 -0) | 64 (+3 -1) | 2,579 -> 207; a lost armory back after 81 s instead of 116 (median), 39 of 75 episodes rebuilt against 33 of 71; s22022's first armory at 278 s, s22039's at 241 s instead of 315 |
| with `opening_join`: builders set out for a site still carried (`ibj`, on `idle_builders=40`) | 87 (+0 -3) | 61 (+3 -4) | standing before the first armory 46 -> 3 and 155 -> 6 |
| `armory_spare_ticks=30000`: from minute 10 a second armory is kept, planned by a calm quarters, holding the next building slot (`sp`) | 90 (+2 -2) | 55 (+2 -9) | time at one armory in the games won 28 % -> 4 %; a moment with none in 46 games against 44 |
| spare and `idle_builders=100` (`spi`) | 91 (+2 -1) | 55 (+4 -11) | |

`idle_builders=100` at the other N and on the held-out maps (against `as-f4-vsN-s0` and `as-f4h-vsN-s0`, the same
games): N=8 99 -> 99, N=10 98 -> 99, N=13 85 -> 82, N=14 76 -> 74, N=16 38 -> 38; 23001..23100 N=12 90 -> 89, N=13
93 -> 90, N=14 77 -> 80, N=15 52 -> 56, N=16 42 -> 41. Over the 1,200 games **+19 -19**. All off.

So the user's rule finds the place and not the cure. The peons stand because the last armory is gone, and a base
whose last armory is gone is being overrun: the same peons building it again three times as fast win one game more
of 33, and a second armory kept in reserve falls with the first and costs a tower's slot. What the 244 games with an
armory at every moment have (243 won) is a base that holds, which is the long game's old question ("Minutes 15-25",
"The chieftain's blast"). `opening_join` loses for a reason not looked into (a builder sent to a placed site chops
where it stands and walks out with the wood; one that carries the site may arrive with none). The one clear gain is
the opening with a cut site (s22022: armory 92 s earlier, a loss at 41 min becomes a win), five to eight games in a
hundred; an `idle_builders` for the opening alone is not tried.

Found on the way and repaired (commit 6670682e): `Military.keepHuntingCasters` ordered a warrior that had gone into
a tower since to attack the winding-up chieftain, and the engine asserts on an order to a mounted unit: 45 swallowed
errors in one game of about 3,000 (`spi-vs15` s22001, from 5,886 s). The default plays the same games otherwise (8 of
8 checksums).

### Builders after their building stands (`release_builders`, the default since, `@f5`)

The user (2026-11-19, on the standing peons above): gathering wood is fine, "but once they've gotten wood they'll
just go stand around the previous quarters they were working on".

That is the engine's `RepairController`: it ends only by its building. Once the building is finished, a builder with
no wood in hand still goes to chop for it once more and carries the wood back, a builder with wood walks back the
same, and both go idle by it (an armory's builder walks in with its wood, which is wanted). The allocation gave jobs
to idle peons only, so after every quarters and tower each builder went by the finished building before its next
job. With `release_builders` a builder of a quarters or tower that has stood for under 60 s (`release_fresh_ticks`)
and needs no repair is free at the next allocation (counter `builders_released`, 130 to 180 peon-seconds in the
first 12 minutes), and takes the wood it carries to the next site.

The opening, means over the maps of a run (`opening.py`; the old default -> with the release):

| run | first armory | sixth quarters | warriors at minute 8 | iron by minute 8 | maps with the sixth quarters after 330 s |
|---|---|---|---|---|---|
| N=8, 22001..22100 | 210 -> 206 s | 273 -> 269 s | 77.2 -> 80.5 | 114 -> 116 | 13 -> 12 |
| N=12, 22001..22100 | 244 -> 237 s | 306 -> 298 s | 59.0 -> 62.1 | 93 -> 97 | 17 -> 15 |
| N=15, 22001..22100 | 246 -> 236 s | 308 -> 297 s | 55.2 -> 59.4 | 88 -> 92 | 22 -> 16 |
| N=13, 24001..24200 | 242 -> 235 s | 306 -> 294 s | 59.5 -> 61.0 | 91 -> 95 | 32 -> 22 of 200 |
| N=15, 24001..24200 | 246 -> 239 s | 316 -> 303 s | 56.0 -> 57.9 | 88 -> 91 | 51 -> 39 of 200 |

Wins from seat 0, the old default (`as-f4-vsN-s0`, `as-f4h-vsN-s0`, `nb0-vsN`) -> with the release (`rel2-vsN`,
`rel2h-vsN`, `nb2-vsN`); every game changes, from second 42 on:

| N | 22001..22100 | 23001..23100 | 24001..24200 (200 maps, never used before) |
|---|---|---|---|
| 8 | 99 -> 100 | | |
| 10 | 98 -> 99 | | |
| 12 | 90 -> 93 | 90 -> 93 | |
| 13 | 85 -> 81 | 93 -> 92 | 174 -> 177 |
| 14 | 76 -> 81 | 77 -> 76 | |
| 15 | 62 -> 64 | 52 -> 56 | 113 -> 121 |
| 16 | 38 -> 41 | 42 -> 38 | |

Together +142 -119 over 1,600 games (sign test z 1.4), and `proxy.py`'s win chance from the state at minute 20
(trained on the old default's games of the same N) +2.1 +- 0.7 points over the 1,400 games at N=12-16, positive in
10 of 12 runs and largest at N=15 and 16 (+4 to +6 on the first two blocks). A first version freed the builders of
any standing building, a repair's too (`release_fresh_ticks=0`, `rel-vsN`, `relh-vsN`): the same earlier opening, the
model +2.6 +- 0.8, and the wins +91 -99 over the first 1,200 games; nothing shows that the repairs are why, but
the fresh ones alone are the smaller change.

Not adopted: `opening_builders=100` (the `idle_builders` rule for the opening alone: a peon left over joins the
armory's site): it fires in 4 to 23 games of 100 and is +17 -16 over 1,200 games (`ob-vsN`, `obh-vsN`).

### The tree on the way, and idle peons after all (`builder_tree`, `idle_builders`, the defaults since, `@f6`)

The user (2026-11-19), on `opening_builders` left off as neutral: "This still sounds more principled though right?
Or maybe even picking an optimal tree to harvest on the way over".

**The tree.** A builder sent to a site with no wood chops the tree nearest to itself (the engine's `SupplyFinder`,
from the unit), whichever way the site lies, and only its later trips start at the site. Logged over 30 openings
(`tr-vs12`, `TREE` lines, first 8 minutes): 115 builders a game are sent with no wood and 20 with; against the tree
with the shortest way from the peon by the tree to the site, as the crow flies, the nearest one costs 4.3 cells of
walking a builder, 492 a game (177 to 949), and 16 builders a game 10 cells and more. `builder_tree` (cells): when
the best tree saves that much, the builder is first told to gather that tree (`MapAnalysis.treeOnWay`; a tree the
start field puts across water from the peon or the site is passed over) and counts as the site's builder meanwhile
(`Intel.fetchers`), and once its load is full `tendFetchers` sends it on to the site; counter `builder_tree`. On 16
openings (`btN-vs12`, 12 minutes): thresholds of 3, 6 and 10 cells reroute 77, 57 and 35 builders a game and put
the armory at 236, 236 and 239 s against 241. At 6, against the default with the release (`rel2-vsN`, `rel2h-vs15`,
`nb2-vsN`):

| run | builders rerouted a game | first armory | sixth quarters | warriors at minute 8 | wins |
|---|---|---|---|---|---|
| N=12, 22001..22100 (`btr-vs12`) | 77 | 237 -> 232 s | 298 -> 292 s | 62.1 -> 63.6 | 93 -> 91 (+3 -5) |
| N=15, 22001..22100 (`btr-vs15`) | 126 | 236 -> 234 s | 297 -> 296 s | 59.4 -> 58.8 | 64 -> 66 (+13 -11) |
| N=15, 23001..23100 (`btrh-vs15`) | 126 | | | | 56 -> 61 (+18 -13) |
| N=13, 24001..24200 (`btrn-vs13`) | 99 | 235 -> 232 s | 294 -> 291 s | 61.0 -> 62.9 | 177 -> 176 (+14 -15) |
| N=15, 24001..24200 (`btrn-vs15`) | 127 | 239 -> 238 s | 303 -> 300 s | 57.9 -> 58.8 | 121 -> 122 (+31 -30) |

+79 -74 over 700 games, the model at minute 20 +1.0 +- 0.9 points, no swallowed error. A third of what the release
gave, in the same place.

**Idle peons.** `idle_builders=100` again, on the default with the release (`ibn-vs12/15`, `ibnh-vs15`,
`ibnn-vs13/15`): 93 -> 93, 64 -> 69, 56 -> 57, 177 -> 176, 121 -> 125; **+19 -10** over 700 games (it fires in 8
to 36 games of 100), the model +0.5 +- 0.3. With the +19 -19 on the old default that is +38 -29 over 1,900 games:
not a loss anywhere, a gain perhaps, and the user's rule that a peon with nothing to do should work.

Both are the default since commit aee7e81e, frozen as `@f6`.

### The ladders of `@f5` and `@f6`

Maps of 100 won from at least one start (and from the first start alone), 22001..22100 / 23001..23100:

| N | `@f4` | `@f5` | `@f6` |
|---|---|---|---|
| 1-13 | 100 / 100 | 100 / 100 | 100 / 100 |
| 14 | 99 (76) / 100 (77) | 99 (81) / 100 (76) | 100 (79) / 100 (78) |
| 15 | 98 (62) / 100 (52) | 98 (64) / 100 (56) | 99 (65) / 100 (64) |
| 16 | 95 (38) / 98 (42) | 95 (41) / 93 (38) | 96 (36) / 98 (47) |

The N=16 count is the noisiest figure here: a map is lost only when 17 games in a row are, and a start wins a game
in four there. Of the seven held-out maps `@f5` lost from every start `@f4` had won three from its first (23069,
23070, 23084), and `@f5` won 23002, 22082 and 22097 from one start each (seats 7, 9, 9) where `@f4` lost all 17.
`@f6` lost 22095 from every start at N=15 and 16, 22018, 22088 and 22097 at N=16, and 23037 and 23069 at N=16.

From the first start the ladders of two builds are a paired run of 100 maps a rung (`lab/fable/seat0.py`). At
N=8-16, 900 games a block: `@f4` -> `@f5` +53 -44 and +45 -47, `@f5` -> `@f6` +44 -48 and +58 -41; the wins over
the 1,800 games 1,483, 1,490 and 1,503. Less than the +142 -119 and +79 -74 of the runs that adopted them, and not a
loss.

### The mechanics left off as neutral (fifteen switches, the default since, `@f7`)

The user (2026-11-20): "Are there any other mechanics that may have been neglected like this: things that would
clearly be a principled improvement on the strategy but wasn't adopted due to not showing a significant winrate
increase?"

Yes. `Strategy` has about 195 options that are off with a record of why (`forGame`'s settings counted as on). Read
by what each record measured, they fall in three kinds.

**It ends a measured waste, and one screen of 100 maps or fewer was flat.** A run of 100 maps has a standard error
of 4 to 5 wins, so it cannot see a mechanic worth one or two, and fifteen were dropped that way:

| switch | the waste | its own record |
|---|---|---|
| `stock_balance` | from minute 20 the main armory holds iron and no wood in 22-38 % of the half-minute samples, with 31-60 workers inside | `sb2` +21 -11 on the 100 open maps |
| `sat_staff` | a satellite sits on its iron with one worker inside while the main armory holds 45-130 idle ones | with the above; in `L1f-vs15` with two others 126 against 126 of 300 |
| `expand_under_threat` | after the expansion falls no armory is placed again: parked blobs keep the threat above 0 | `xt1` +9 -5, the model at minute 40 +3.1 +- 1.1 |
| `capped_min_strength` 40 | at the unit cap the attack musters with an army of 0 and deploys every weapon for 45 s at a time | `cm1` +6 -5 |
| `stall_peons` | every fight with a peon resets the attack's stall clock (Gauntlet's five-hour draw) | 6 wins against 6 |
| `freeze_unfreeze` | a frozen copy that gets its armory still counts as frozen | never run alone |
| `tower_wood_drop` from 600 s | a tower site with no trees takes 116 s against 45-69 s | 61 s against 127 s in 12 logged games; no screen |
| `tower_site_fallback` | a tower with no site at its anchor retries the same ring every plan tick: 9 % of the construction slots | fires 2.8 times a game |
| `site_towers_first` | a quarters site holds the slot a tower waits for in 9.3 % of the samples | no screen |
| `unstick_builders_ticks` 1500 | builders wedged in a notch, 6-23 of them for 13 minutes in one game | fires in 60 % of the games |
| `walk_select` 300 | builders are taken by straight line, whatever the walk | N=11: 18 wins against 15 |
| `seed_quarters_ticks` 1500 | a new quarters breeds up from empty for 77 s, about 5 peons | 3 wins against 4, 2 against 0 |
| `rush_hold_armory` | the rush alarm sends 20-90 peons out of the quarters with no armory to go to | N=13: 71 against 70 |
| `rush_opening_only` | the rush alarm fires again after our last armory fell | never run alone |

(`tower_wood_ticks` is the fifteenth param. The second half of the table is Gauntlet's, screened at N=11-13.)

**It has a measured cost, and stays off:** `harass_memory_ticks` (-3.0 +- 2.7 in the screen), `capped_clump` (+2
-17), `hold_backlog` (z -5.3), `armory_spare_ticks` (55 wins against 62), `opening_join` (+3 -7), `armory_cover` on
top of `evade_tower` (-3.2 +- 3.0), `late_reinforce_min` and `late_min_strength` (warriors kept at home die at
home).

**A real waste with no fix that works yet:** the units inside buildings when they are razed (63-66 a game at N=15
here, the bundle does not change it; Gauntlet tried four fixes), the home warriors that die one at a time after
minute 30, and copies that can no longer arm and are not hunted down (`mopup` exists, off).

**The test.** All fifteen at once on the frozen default (`@f6:stock_balance=true,...`, runs `pb6` and `pb6h`), as
any-seat ladders at N=8-16 next to `@f6`'s, so the games from the first start pair map by map. Wins from the first
start, `@f6` -> with the fifteen:

| N | 22001..22100 | 23001..23100 |
|---|---|---|
| 8 | 100 -> 100 | 97 -> 98 |
| 9 | 95 -> 97 | 99 -> 99 |
| 10 | 98 -> 98 | 99 -> 97 |
| 11 | 93 -> 96 | 95 -> 96 |
| 12 | 92 -> 89 | 91 -> 95 |
| 13 | 86 -> 88 | 89 -> 89 |
| 14 | 79 -> 81 | 78 -> 80 |
| 15 | 65 -> 70 | 64 -> 65 |
| 16 | 36 -> 45 | 47 -> 47 |
| all | 744 -> 764 (+55 -35, z 2.1) | 759 -> 766 (+44 -37, z 0.8) |

Together **+99 -72** over 1,800 games (z 2.1), 16 of the 18 rungs level or up; from `@f4` +63 -38 and +54 -32 (z 3.4)
and from `@f5` +53 -37 and +58 -34. Draws from the first start 5 -> 0 (`stall_peons` was built for them), no swallowed
error in either. Maps won from some start: as `@f6` up to N=15 (100 everywhere, 99 on the first block at N=15, 22095
again), and at N=16 97 and 99 (`@f6` 96 and 98): 22018, 22088 and 22097 are lost from every start by both, 22095 is
won now, and 23003 is lost where `@f6` lost 23037 and 23069.

What else the adoption rule asks for (the mechanism's own counter, the opening, the state model, errors):

- Counters a game at N=15, first block / held out (games of 100 in which it fires): `stock_balance` 425 / 446 (97 /
  99), `sat_staff_sent` 177 / 137 (95 / 95), `seed_quarters_sent` 11.3 / 10.8 (98 / 98), `tower_wood_drop` 17.6 / 17.6
  (94 / 95), `tower_site_fallback` 2.5 / 3.3 (70 / 66) with `tower_nosite`, the plan ticks with room for a tower and
  no site on its ring, 95 -> 2.5 and 137 -> 3.3, `site_towers_first_used` 15.5 / 13.4 (30 / 33), `capped_min_blocked`
  16.1 / 8.4 (45 / 45), `builder_unstuck` 2.2 / 1.7 (73 / 71), `exp_blocked_site` 5.7 / 5.9 (51 / 59),
  `rush_hold_armory` 1.1 / 1.4 (8 / 8).
- The opening (`opening.py`, N=13 and 15, both blocks): first quarters, armory and sixth quarters within 0.3 s of
  `@f6`'s; at minute 8 warriors +1.8 to +2.8 (62.2 -> 64.7, 62.7 -> 64.5, 59.1 -> 61.3, 60.2 -> 63.0), peons -0.5
  to -4.0, iron +0.5 to +1.6.
- The state model (`proxy.py`, N=14 and 15, both blocks) at minute 20: +1.1, -4.2, +4.4, -0.1 points (each +- 2.6
  to 2.9); at minute 30: -2.4, -2.2, +4.2, +2.7; at minute 40: -0.2, -2.5, +4.1, +4.4. No direction at N=14, up
  at N=15; nothing beyond 1.7 standard errors either way.
- Buildings lost a game at N=15 20.2 -> 18.7 and 21.6 -> 20.0. Standing peons (`idle_s_late`) 307 -> 39 s on the
  first block and 80 -> 446 s on the other: a few games carry it either way.
- Cost: 23.8 -> 26.4 CPU seconds a game over the 1,800 (58 game minutes in both), measured with eight games at a time
  on a busy machine; a profiled run of the same 8 maps at N=15 for each (`prof-f6-vs15`, `prof-f7-vs15`) has 350 and
  343 s of simulation CPU with the AI at 34.6 and 34.8 % of it, so the fifteen cost nothing themselves and the rest is
  longer games and load.

By the user's rule (2026-11-19: a mechanic that is the principled thing and shows no harm is adopted, not shelved as
neutral) all fifteen are on in `forGame` for large maps and up since commit 00461225, frozen as `@f7`: the default
plays the bundle's games (16 of 16 checksums, `f7chk-vs13` against `pb0-vs13`). The halves were not run apart: the
bundle is not worse, so nothing asked for the split. At N=1-7 the bundle wins every map of both blocks from some
start, as before, and 699 and 700 of 700 from the first (`@f6` 698 and 700).

**On maps never used** (25001..25300, N=16, from the first start; `f4n-vs16`, `f7n-vs16`): `@f4` 107 of 300 = 35.7 %
[30.5, 41.2], `@f7` 124 = **41.3 %** [35.9, 47.0], paired +59 -42 (z 1.7), the state model at minute 20 +3.1 +- 1.7
points, no draw and no swallowed error in either. So the two blocks above run about five points easy at N=16 (40 %
and 46 % there), the three defaults since `@f4` are worth five to six points at N=16 on any of them, and the 50 %
line still lies between N=15 and N=16.

### The shepherds: what a life buys, and the second shepherd repaired (`lab/fable/shepherds.py`)

Where our units die at N=15 put the shepherds first among the losses that do not depend on the result: 147 lost of
158 recruited a game. What the logs say (16 games of `pb0-vs13`, 8 of `f7log-vs15`):

- Recruiting is not early: a copy's first shepherd is recruited at a median 171 s and walks 252 cells, the first
  launch is at a median 339 s (earliest 276 s), and only 91 of 187 stand on their spot before it, a median 55 s.
- 88 % die in a flee and 9 % on the walk, a median 129 s after they were recruited. 47 % of the lost drew no wave
  (82 % of those never reached a spot, median life 80 s), 22 % one, 13 % two, 17 % three and more. Recruited before
  minute 5 they reach a spot in 64-72 % and live 280-300 s; after minute 20 in 25-30 % and 90-110 s. The next one is
  recruited in the tick the last one died. A wave was never aimed at 49 % of the lost; 18 % die within 20 s of one.
- Launches at N=13, a game: 176 at a shepherd (its own is "away", a median 39-47 cells from the origin, in 73 % of
  them: it need not stand on the spot, only be nearer than 0.7 of our nearest building), 24 at a unit in the field,
  8.3 at a tower or by the base. Of those 8.3 a quarter leave the copy's home with its shepherd a median 189 cells
  off; the rest leave a parked group with the shepherd a median 65 cells off (away 75 %, no spot 23 %).

Tried at N=15 against `@f7` on the same maps (22001..22100, and 23001..23100 where "h"), 100 maps an arm:

| arm | wins | shepherds lost a game | waves at our base a game | the state model, minute 20 / 40 |
|---|---|---|---|---|
| a pause before the next one, 30 s (`shepherd_gap_ticks`, `sg30`) | 70 -> 60 (+6 -16) | 148 -> 124 | 19.9 -> 26.5 | -8.3 +- 2.7 / -8.9 |
| the same, 90 s (`sg90`) | 70 -> 44 (+3 -29) | 148 -> 94 | 19.9 -> 35.9 | -20.9 +- 2.6 / -26.4 |
| recruits with no load, 40 cells (`shepherd_unloaded`, new; `su40`) | 70 -> 67 (+7 -10) | 148 -> 154 | 19.9 -> 22.2 | -5.9 +- 2.8 / -4.9 |
| the same, always (`su1k`, `su1kh`) | 70 -> 67, 65 -> 55 | 148 -> 167 | 19.9 -> 23.3, 27.2 | -6.7, -4.2 / -6.0, -7.5 |
| the late fixes from minute 20, from minute 10 (`shepherd_fixes_ticks`; `sf20`, `sf10`) | 70 -> 68, 66 | 148 -> 139, 121 | 19.9 -> 19.8, 17.6 | 0, -3.2 / -2.7, -3.7 |
| the walk round alone from minute 10, from minute 2 (`shepherd_detour_ticks`, new; `dt10`, `dt2`) | 70 -> 66, 70 | 148 -> 126, 148 | 19.9 -> 19.3, 20.4 | -4.4, -2.2 / -5.0, -2.0 |
| the late stuck gap 30 s for 4 min (`shepherd_stuck_gap_ticks`; `sgap30`) | 70 -> 71 (+1 -0) | 148 -> 145 | 19.9 -> 19.5 | 0 / 0 |
| a second shepherd as written, copies 200 / 120 cells off (`shepherd_home_pair`; `hp200`, `hp120`) | 70 -> 71, 71 | 148 -> 284, 288 | 19.9 -> 18.3, 16.6 | -5.7, -9.1 / -1.8, -0.3 |
| the same from minute 12, from minute 20 (`shepherd_home_pair_ticks`, new; `hpl12`, `hpl20`, `hpl20h`) | 70 -> 70, 74; 65 -> 64 | 148 -> 270, 215; 243 | 19.9 -> 16.6, 15.7; 19.3 | -5.7, 0 / -0.5, -0.9; -3.6 |

What they say together. A wave at our base costs far more than the peon: 24 shepherds saved for 7 more waves at
the base is 10 wins of 70. The lives with no wave are tries to cover a copy again, and the nearest peon at once is
the fastest try: the unloaded one does what it says (loaded recruits 40 -> 1-4 a game), 93 % of the recruits are
lost with a load as without, and it comes later. The walk round stood behind the gate of the late fixes (minute
40) with releases that pause a copy for minutes; alone and early it leaves the arrivals where they were (recruited
in minutes 20-40: 25 % reach a spot without it, 28 % with, `f7log-vs15`, `dtlog-vs15`, 8 logged games each; net
speed to the spot 1.0 cells a second either way): it looks 8 and 16 cells ahead, and that is too near to matter.

**The second shepherd had a flaw as written** (the user, 2026-11-20: "don't assume the disabled features were
perfect as written, maybe they had some sort of flaw that stopped them from reaching their potential before they
were discarded"). It is meant for the time the copy's own shepherd is away with the parked wave and the next wave
leaves from home. But before a copy's first wave, and whenever its oldest idle warrior stands at home, the own
flock's origin is the home too, and both were sent to the same place: of the second shepherds recruited in the
first ten minutes 82 % had their spot within 30 cells of the own one's (`hplog-vs15`, 8 logged games: 18 of 24 a
game), a sixth later. `shepherd_home_spare` (new): while the own flock's origin is at the copy's home no second one is
recruited, and one that stands there is sent home alive once the own one is within 40 cells (`hpslog-vs15`: the
doubles of the first ten minutes 18.2 -> 3.6 a game, 20 sent home a game). With it, pair 120:

| start | 22001..22100 | 23001..23100 | waves at our base | buildings lost | the model at minute 40 |
|---|---|---|---|---|---|
| with the shepherds (`hps`, `hpsh`) | 70 -> 77 (+14 -7) | 65 -> 66 (+9 -8) | 19.9 -> 15.2, 18.5 | 18.7 -> 15.5, 20.0 -> 18.9 | +1.9, +1.5 |
| minute 12 (`hps12`, `hps12h`) | 70 -> 75 (+8 -3) | 65 -> 69 (+10 -6) | 14.8, 16.3 | 16.4, 16.7 | +3.4, -0.5 |
| minute 20 (`hps20`, `hps20h`) | 70 -> 70 (+5 -5) | 65 -> 67 (+5 -3) | 18.7, 18.8 | 18.5, 20.0 | -1.1, -4.7 |
| every copy, minute 20 (`hpsa20`, `hpsa20h`) | 70 -> 72 (+7 -5) | 65 -> 66 (+5 -4) | 18.4, 19.8 | 18.4, 20.8 | -1.7, -2.9 |

As written it was +8 -7; repaired, +23 -15 from the start and +18 -9 from minute 12 (+41 -24 over the 400 games, z
2.1), with 85-90 more shepherds lost a game and four to five fewer waves at the base. Started at minute 20 it buys
nothing: the waves it catches leave home between the first launch and minute 20, when the base has three to six
towers. The model at minute 20 stays 3 to 5 points down, as it must (it counts the peons and not the waves kept
off). Seven variants were run on these two blocks, so the two best were played on other maps before anything was
adopted: N=16 on 25001..25300, from the first start, against `@f7`'s 124 wins of 300 there (`f7n-vs16`).

| start | wins of 300 | waves at our base | buildings lost | the model at minute 20 / 40 |
|---|---|---|---|---|
| with the shepherds (`hpsn-vs16`) | 124 -> **154** = 51.3 % [45.7, 56.9] (+60 -30, z 3.2) | 29.7 -> 24.8 | 30.0 -> 25.8 | +0.6 +- 1.5 / +7.3 +- 2.4 |
| minute 12 (`hps12n-vs16`) | 124 -> 148 = 49.3 % (+48 -24, z 2.8) | 29.7 -> 26.4 | 30.0 -> 26.6 | -3.4 +- 1.2 / +4.1 +- 2.2 |

Ten points at N=16, for 163 more shepherds lost a game (169 -> 332) and games 13 minutes longer; two draws in each
arm, no swallowed error. The second shepherd with the shepherds' own start is the default in `forGame` for large
maps since commit 65e5d5a4, frozen as `@f8`; the default plays the arm's games (6 of 6 checksums, `f8chk-vs16`).
The 51.3 % is the better of two arms on those maps. `@f8` on maps of its own, 26001..26300 at N=16 (`f8m-vs16`,
`f7m-vs16`): **148 of 300 = 49.3 %** [43.9, 55.1] with one draw, `@f7` 126 = 42.0 %, paired +60 -38 (z 2.2), the
model at minute 40 +5.9 +- 2.3, waves at our base 31.0 -> 26.1, buildings lost 30.6 -> 26.5. Both blocks together:
302 of 600 = 50.3 % for 250 = 41.7 % (+120 -68, z 3.8). At lower N on 22001..22100 against `@f7`'s games from the
first start (`f8d-vsN`): N=8 100 -> 100, N=10 98 -> 99, N=12 89 -> 94, N=13 88 -> 87, N=14 81 -> 87, +24 -13
together, fewer buildings lost at every N (N=14: 13.9 -> 8.8).

On top of `@f8` at N=16, 300 maps an arm, against its 154 wins on 25001..25300 (its 148 on 26001..26300 where "m"):

| arm | wins | the model at minute 40 | what its counter says |
|---|---|---|---|
| a second shepherd for every copy (`shepherd_home_pair=1`; `hpa`, `hpam`) | 154 -> 162 (+27 -19); 148 -> 142 (+25 -31) | +3.4 +- 1.8; +0.4 +- 1.8 | as many second shepherds either way (162 a game) |
| for the copies 60 cells off (`hp60`) | 154 -> 162 (+27 -19) | +3.1 +- 1.8 | at N=16 nearly every copy is |
| no new second shepherd from minute 12 / 20 / 30 (`shepherd_home_pair_until`, new; `hpu12`, `hpu20`, `hpu30`, against `hpa`) | 162 -> 144, 151, 151 (+23 -41, +18 -29, +7 -18) | -4.9, -2.5, -1.5 | second recruits 162 -> 28, 60, 89 a game |
| rings out to 38 cells where the ring of 22 has no spot (`shepherd_fallback_r=38`; `fb38`) | 154 -> 158 (+44 -40) | -1.2 +- 2.3 | tends with no spot 13,540 -> 10,872, waves at the base 24.8 -> 25.2 |
| the same, parked warriors block only within 8 cells (`shepherd_clear_parked=8`; `fbcp`) | 154 -> 152 (+32 -34) | -0.9 +- 2.2 | no spot 13,540 -> 9,028, waves at the base 25.7 |
| a spot with a clear walk to it (`shepherd_safe_walk`; `sw`) | 154 -> 159 (+46 -41) | -0.2 +- 2.5 | none of the 12 best spots has one in 15,966 tends a game; lost 332 -> 318 |
| a recruit with a clear walk, of the five nearest (`shepherd_recruit_clear` from 120 s; `rc`) | 154 -> 156 (+40 -38) | +0.1 +- 2.4 | no recruit in 8,131 tends a game; lost 332 -> 287, waves at the base 25.6 |

Which copies get the second shepherd does not matter (+52 -50 over the 600 maps), so 120 cells stays. The late
second shepherds are not waste, though one in eight arrives: ending the recruiting loses wins, the more the sooner
(my reading of the start at minute 20 was wrong). The wider rings do what they say and move nothing: a ring beyond
22 cells is allowed only where the copy's group stands far from our buildings, and those are not the groups whose
waves reach the base. The two for the walk are too near-sighted to make it safe, like the walk round: the twelve
best spots lie on one ring of 14-22 cells about one origin, so the walks to them from a peon 100 cells off are one
walk, and of the five nearest peons none has a clear start most of the time (it then recruits nobody, which loses
nothing here: 45 fewer shepherds lost a game for 0.8 more waves at the base). What the walk wants is a route, not a
choice among near things.

### A way for the shepherds (`shepherd_route`, the default since, `@f9`)

Half the shepherds lost never had a wave aimed at them: they die crossing a field in which the copies' waves stand
parked, on the straight walk the engine gives them. `Shepherd.route`: when the straight walk to the spot is not
clear (the old test, `walkClear`), the walk order goes three blocks along the cheapest way over a grid of 4-cell
blocks. A block within the flee distance of an enemy warrior, 17 cells of an enemy quarters or armory or 19 of an
enemy tower costs 80 cells more, so it can be crossed at a price (the spot itself lies by the copy's group); two
blocks are joined when both can be reached and the walking distances from our start put no water between them.
The plan is A* with the blocks' walking distances read once a game, and is made again with every walk order (2 s).

In logged games first (8 at N=15, 22001..22008, `f8log-vs15` -> `rtlog-vs15`), no swallowed error:

| | `@f8` | with the way |
|---|---|---|
| walk orders along a planned way that is clear / crosses a hot block / none found, a game | | 1,181 / 3,592 / 771 |
| shepherds recruited / lost a game | 271 / 234 | 224 / 185 |
| of them lost on the walk / in a flee | 31 / 195 | 21 / 159 |
| the own shepherds recruited in minutes 5-10 that reach a spot | 43 % | 55 % |
| in minutes 10-20 | 33 % | 45 % |
| in minutes 20-40 | 23 % | 53 % |
| waves they draw a life, recruited in minutes 20-40 | 1.44 | 2.41 |

Then at N=16 from the first start, 300 maps a block, against `@f8` on the same maps:

| maps | `@f8` | with the way | shepherds lost a game | the model at minute 40 |
|---|---|---|---|---|
| 25001..25300 (`rte-vs16`) | 154 | **172** = 57.3 % (+49 -31) | 332 -> 244 | +5.2 +- 2.3 |
| 26001..26300 (`rtm-vs16`) | 148 | **165** = 55.0 % (+51 -34) | 329 -> 275 | +3.2 +- 2.3 |
| both | 302 = 50.3 % | 337 = **56.2 %** (+100 -65, z 2.7) | | |

One arm, not picked among several, so both blocks count. The waves at our base stay where they were (24.8 -> 24.0,
26.1 -> 25.3): the gain is 55 to 87 peons a game that live, more waves drawn to a shepherd (305 -> 327-360), and
fewer buildings lost (25.8 -> 24.5, 26.5 -> 25.4). The default in `forGame` for large maps since commit c64eb747,
frozen as `@f9`; the default plays the arm's games (4 of 4 checksums, `f9chk-vs16`). Cost: about a seventh more
CPU a game as first written, a tenth less of that with the distances read once (the same games, 6 of 6 checksums);
771 to 1,238 plans a game find no way within 8,000 blocks, which is where the rest of the cost is.

`@f9` up and down the ladder, from the first start. N=17 on 27001..27300 (`f9n-vs17`): **121 of 300 = 40.3 %**
[35.3, 46.3], two draws, no error. On 22001..22100 (`f9d-vsN`), `@f7` -> `@f8` -> `@f9`: N=10 98 -> 99 -> 98, N=12
89 -> 94 -> 95, N=14 81 -> 87 -> 91, N=15 70 -> 77 -> 78. Buildings lost a game at N=15: 18.7 -> 15.5 -> 13.2.

The planner's cost (`prof-f8-vs16`, `prof-f9-vs16`, the same 8 games profiled): 324 -> 391 s of simulation CPU,
`Shepherd.route` 7.2 % of it. A plan that finds a way looks at 2,400 blocks, and the 1,300 a game that find none
all run out of their 8,000 (none is cut off by water; `tmprt-vs16`): the spot lies by its copy's group, so every
way ends through hot blocks, and A* looks far and wide for a cheaper end that is not there. A heap of longs for the
boxed queue plays the same games (6 of 6 checksums, commit f925a7f6). `shepherd_route_near` (blocks): the hot
blocks that near the goal are free; it changes the ways, so it was played before it was taken. At N=16 against
`@f9`'s games:

| near | 25001..25300 | 26001..26300 | plans with no way a game | the planner's share of the CPU |
|---|---|---|---|---|
| 0 (`@f9`) | 172 | 165 | 1,240-1,420 | 7.2 % as first written, 6.2 % with the heap |
| 3 (`rn3-vs16`) | 167 (+45 -50) | | 890 | |
| 5 (`rn5-vs16`, `rn5m-vs16`) | **178** (+43 -37) | **178** (+48 -35) | 590 | 4.7 % |

Five is the default since commit 00f7ed2b, frozen as `@f10`: 356 of 600 = **59.3 %** at N=16 for `@f9`'s 56.2 % (+91
-72, z 1.5; the model at minute 40 +2.1 +- 2.5 and +5.2 +- 2.2), and a fifth less CPU a game minute than the
planner as first written. At N=17 on 27001..27300 `@f10` wins 112 of 300 = 37.3 % with three draws, where `@f9` won
121 (+40 -49): over the three blocks five against none is +131 -121, so it is the cheaper planner and not a
better one, and N=17 stands at 37 to 40 %. An estimate that counts the hot blocks no way can go round (the depth of the goal in hot
ground) was tried in its place and thrown out: it found no more ways (18 % of the plans still ran out) and cost a
percent of its own. The search is wide because the field itself is crossed by broad hot belts late in a game, not
only because of the ground by the goal; what is left of the cost (590 plans a game that give up after 8,000 blocks)
wants fewer plans (one every walk order, 2 s, now) or coarser blocks. Not done.

### The micro round: what single units do among the enemy (2026-11-20)

The user's direction: no new strategy, the handling of single units: paths, the choice of supply and destination,
warriors and the chieftain in a fight. The method was the one that found the shepherds' flaws: a log-only tracer
of what the units do second by second, then the code that makes them do it. The tracers stay in the AI (they write
only with `--logs`): `Military.traceEvade` / `traceFlight` (every step out of an enemy's way and every flight to
shelter, with what the nearest enemy was doing), the `stuck` line of `Economy.unstickGatherers`, `FightTrace` (what
the warriors of both sides do with an opposing warrior near), `ThrashTrace` (units whose orders turn them about) and
`Chieftain.traceNoBlob`. Their readers: `lab/fable/evades.py`, `fighttime.py`, and `rates.py` (any run's counters
per hour of game time, run against run, no logs needed).

**Where an iron gatherer's time went** (`@f10`, 12 logged games at N=16, maps 22001..22012, `ev1log-vs16`; trips by
how they end, a game per 8 minutes: number / seconds they took):

| min | delivered | step out of an enemy's way | on the way into a building | dead |
|---|---|---|---|---|
| 8-16 | 116 / 5,570 | 5 / 102 | 75 / 1,830 | 15 / 305 |
| 16-24 | 78 / 5,143 | 128 / 2,102 | 136 / 3,153 | 17 / 558 |
| 24-32 | 70 / 3,232 | 362 / 4,563 | 300 / 3,849 | 17 / 331 |
| 32-40 | 59 / 2,684 | 322 / 4,074 | 193 / 3,045 | 19 / 373 |

From minute 24 a trip in ten is delivered. A game has 7,190 orders to shelter (`evacuatePeons`: a threat within 11
cells) and 2,240 steps out of the way (`evade`), and 724 peons die outside a building, 262 of them shepherds.

**1. The bystander** (`bystander=0`). In 5,160 of the 7,190 flights and 1,010 of the 2,240 steps the nearest enemy
was a warrior locked in a hunt on another peon. The engine's hunter walks to its target without scanning and
answers nothing (`HuntController`: `WalkBehaviour` without scan); only when its hunt ends does it scan its 8-cell
square again, warriors before peons (`AttackScanFilter`). One warrior after one peon (a shepherd, or a gatherer
running home) turned back every gatherer that passed within 11 cells of it: s22003 at 1802-1808 s, one hunter by
the ore, twenty gatherers stepping away one after the other every second. With `bystander=0` such a hunter is no
reason to flee or to step away (`Military.huntsElsewhere`); at 6 cells the gatherers walk into the circle and bounce
there (12 logged games: 603 steps from hunters a game against 154).

**2. The stuck gatherer that was walking** (`stuck_still_ticks=1000`). `unstickGatherers` re-sends a gatherer whose
load has not changed for 70 s and avoids its supply for two minutes. Late iron lies 100 cells of walk and more
out: of 281 gatherers called stuck a game 245 were walking, 140 on their way out (56 % of them within 40 cells of
their supply) and 105 on their way home with their load (`byelog-vs16`). Now one is stuck only if it has also not
left a 5-cell circle for 20 s; the 28 a game that swing at a supply for 70 s without a load still are.

**3. The parked group in the way** (`gather_parked`). A gatherer that stepped out of a parked group's way was sent to
the cheapest supply from the armory, which lay behind the group as often as not: #150 of s22003 bounced off a
group of 10-16 nine times in 50 s, sent to a new supply each time, and half the steps away from a parked enemy came
within 5 s of going back to work. A parked warrior stands where it is until it sees something, so its 8-cell
square (10 with two to spare) is ground to walk round: a gatherer is sent only to a supply no parked warrior sees,
with a straight walk there from where it stands, and from there to the armory, that none sees (`Economy.parkedSees`,
`walkUnseen`, the cheapest such of the cheapest 12, else none: the peon is free for other work).
`gather_avoid_parked` and `gather_route_clear`, both off since, look from the armory only and at every enemy.

Per hour of game time (`rates.py`), on maps 25151..25300 from the first start, 150 games each:

| | wins | iron | steps away | peons lost stepping away / on the way in / gathering | CPU s |
|---|---|---|---|---|---|
| `@f10` (`rn5-vs16`) | 87 | 793 | 2,051 | 85 / 103 / 53 | 45.0 |
| `bystander=0` (`by0-vs16`) | 100 | 858 | 1,506 | 122 / 138 / 64 | 42.5 |
| and `stuck_still_ticks=1000` (`ma2-vs16`) | 100 | 867 | 1,221 | 96 / 122 / 51 | 39.3 |
| and `gather_parked` (`mb2-vs16`) | **105** | 890 | 733 | 71 / 109 / 28 | 39.8 |

The bystander alone costs peons (a hunter whose hunt ends takes the nearest it then sees); with the parked groups
walked round, fewer are lost than before the round, for 12 % more iron an hour and a ninth less CPU. On the first
half of the block (25001..25150) `bystander=0` won 97 for 91, and with `stuck_still_ticks` and
`bystander_reach=9` 99.

**What was built and stays off, with its mechanism checked:**

- `bystander_reach=9` (a hunter within throwing reach of its target, or whose target is about to walk into a
  building, counts again: its hunt is about to end). The peons lost do not fall (95 an hour stepping away, as
  without it) and the steps come back (2,019 for 1,165): the deaths are not at the end of such hunts
  (`byr-vs16`, 94 for 97).
- `reinforce_route` (reinforcements walk to the army along `SafeField`, a flood from the army's centre over 4-cell
  blocks that keeps clear of enemy warriors, bases and towers, in place of attack-moving straight at it one by
  one). It does what it is for: 82 reinforcements an hour lost on the way become 23 (of 500 sent). It wins nothing:
  96 for 100 (+18 -22, `mc2-vs16`), with 8 % less iron and more peons lost. The reinforcements that walk through
  the field one by one are also what clears the hunters out of it.
- `micro_step=0.75` (a target out of throwing reach is worth less to `micro_targets`, and a warrior walking up to
  its target takes one in reach instead). `FightTrace`: with an opposing warrior within 8 cells our attackers throw
  70-72 % of the time and walk up to their target 21 % (the Hard copies, on the engine's own choice: 63-65 % and
  26-28 %). But two targets in three are given with nothing in reach at all (the first contact, at 8 to 9 cells),
  and the option moved 215 walks an hour: 75 % and 20 % (`mslog-vs16`). Nothing to win there.
- The turning about. The military sends a peon to shelter from a threat within 11 cells; the economy's next tick
  takes it, as any peon on its way into a building, for the gatherer its own flight left the plan short of; the
  military sends it in again: 80 % of the 7,190 flight orders followed one given within 4 cells 0.5-2 s before,
  and `ThrashTrace` counts 490 such turns a game beyond the fourth within 5 s (`m0log-vs16`). With the three
  defaults a game has 1,194 flight orders, 7 % of them towards the enemy, and 65 such turns (`mblog-vs16`).
  `flight_keep` (the economy leaves a peon on its way to shelter while the threat is within 14 cells): see `@f12`
  below. `evade_cells=1` (every gatherer steps away, none walks to shelter): 100 wins for 105 (+7 -12, `md2-vs16`),
  167 peons lost an hour for 207 but 4 % less iron.
- `evade_chicken` (chicken hunters step away as the other gatherers do, in place of walking to the main armory from
  up to 150 cells out): 21 of them lost far from home an hour for 27, 100 wins for 105 (+7 -12, `mg2-vs16`).
- `shred_range=200` (the chieftain's blobs up to 200 cells from the armory): a third less time without a blob, 11.7
  blasts an hour for 11.2, more approaches called off (46 for 39) and more chieftains lost (1.9 for 1.6): 100 wins
  for 105 (+15 -20, `mh2-vs16`).
- `shred_hunters` (waves locked in a hunt on a shepherd count as a blob: they see him no more than parked ones do),
  with `chief_bystander`: 107 for 105, the same 11.2 blasts and the same time without a blob (`mi2-vs16`): he does
  not catch up with a group that walks as fast as he does.

**Confirmed and made the default** (`forGame`, large maps: `bystander=0`, `stuck_still_ticks=1000`,
`gather_parked=true`; frozen as `@f11`). At N=16 from the first start on the 300 maps 26001..26300, never used for
any of this: **204 wins for 178** (`mbm-vs16` against `rn5m-vs16`, +50 -24, z 3.0), 68.0 % for 59.3 %; 862 iron an
hour of game for 807, 650 steps out of the way for 2,035, CPU 39.8 s an hour of game for 42.0. At N=17 on
27001..27300, where `@f9` won 121 and `@f10` 112: **148 of 300 = 49.3 %** (`f11n-vs17`, +57 -21 against `@f10`).

**`@f12`: `flight_keep` and `chief_bystander`** (the default since commit 5e14c475). `flight_keep` on top of the
three: 106 wins for 105 on 150 maps (`me2-vs16`, +13 -12) and, on the 300 maps 26001..26300, **215 for `@f11`'s 204
and `@f10`'s 178** (`mem-vs16`: +35 -24 against `@f11`, +52 -15 against `@f10`), 71.7 %. An hour of game, `@f10` /
`@f11` / with `flight_keep`: peons lost stepping away, on the way into a building, gathering and building 255 /
213 / 179; units lost inside razed buildings 48 / 37 / 32; iron 807 / 862 / 879. `chief_bystander` (the chieftain's
mission minds no enemy locked in a hunt on another unit: the same reasoning as for the peons): 29 approaches called
off an hour for 39, the same 11 blasts, 108 wins for 105 (`mf2-vs16`, +10 -7); adopted as waste removed with no
harm seen, not as a gain. **At N=17 on 27001..27300 `@f12` wins 156 of 300 = 52.0 %** (`f12n-vs17`; `@f11` 148,
`@f10` 112, `@f9` 121; maps used for these measurements only, no choice was made on them): the first N=17 at
half and more. The default itself (`@f12`) on 26001..26300: **214 of 300 = 71.3 %** (`f12m-vs16`;
+50 -14 against `@f10`); on maps 25151..25300: **110 of 150** (`ml2-vs16`; `@f10` 87, the three 105). On top of it, tried a second time: `reinforce_route` 106 (`mj2-vs16`) and `evade_chicken` 107
(`mk2-vs16`). Both do what they are for and neither wins; both stay off.

**The chieftain** (`shred`, strict: the `shred_t_*` counters count his rounds of half a second by what he does;
`mb2-vs16`, an hour of game): 11.2 blasts that catch 22 warriors each, and of the hour 878 s charging, 613 s walking
with the blast charged, 960 s charged with no blob, 188 s charged with an awake warrior within 9 cells, the rest
without a chieftain. A blast takes 70 s to charge and comes every 320 s. `traceNoBlob` (12 logged games,
`cnlog-vs16`, 396 moments charged without a blob): in 86 % a parked group of 8 and more stands beyond `shred_range`
(140 cells from the armory; 77 % one of 20 and more, 27 % one within 200 cells), in 33 % eight and more awake enemy
warriors are in range, in 14 % there is nothing.

### The towers: what a garrison does, and three counters that do not pay (2026-11-20)

The user's direction after the micro round: a counter to the enemy's towers. The method again a log-only tracer
first: `TowerTrace` (a contact is one manned enemy tower from the first warrior of ours within 16 cells of it until it
falls, loses its garrison, or has had none of ours in reach for 10 s; every half second what each warrior in reach
does and what the garrison throws at; with `--logs` a `TOWER s` line a second, a `TOWER end` line a contact, and a
`jampic` of the first big contacts), read by `lab/fable/towers.py`. Nothing of this round is a default: `fable`
without params plays `@f12`'s games (8 of 8 identical on 26001..26008, `chk12b-vs16`).

**The engine's tower** (`MountUnitContainer`, `IdleController`, `AttackController`, `AttackScanFilter`,
`UnitGrid.scan`, `InstantHitFactory`, `RepairBehaviour`, `Stun`):

- One warrior inside. It scans only while it has no target, every 1 to 2 s, the square of 14 cells around the tower
  (29 x 29), ring by ring outwards, and takes the highest priority anywhere in the square (warrior 4, peon 3,
  building 1 or 2), the first found among equals. Then it throws at that target every 2 s until it is dead or out of
  reach (15.9 cells for a unit, 16.9 for a tower's site), and does not scan meanwhile. After a kill it stands 1 to 2 s
  before the next scan. Its throw hits with three times the chance: .675 on an iron warrior, always on a peon.
- So a garrison kills one warrior in about four seconds however many stand in its reach, and its kills are its
  seconds of contact divided by four.
- A warrior throws at a tower from 8.9 cells, 1.5 damage a throw on average: 133 thrower-seconds a tower. A peon's
  swing takes 6 from a tower or a tower's site every 2.2 s (3.6 warriors' worth) and needs the cell next to it.
- The chieftain's stun silences every garrison within 18 cells for 10 to 30 s (`Stun` walks into the tower for its
  unit); his blast does 30 to a tower. Either cast empties both charges (40 s and 70 s). He is at home (`shred`).
- A peon carries one wood, five strokes of 1 hit point a second on a site; a placed site starts at 1 hit point.

**What the army does under a tower** (`@f12`, 12 logged games at N=16, maps 22001..22012, `tw0log-vs16`, a game):
20 contacts, 16 towers razed, 718 s; 196 warriors dead in reach, 104 of them the garrison's target when they died.
A razed tower stands 29.5 s from the first warrior in its reach (19 s before anything damages it) and costs 9.4
warriors and a peon. Of the 18,500 warrior-seconds in reach 8 % throw at the tower, 6 % walk up to it, 26 % are
after enemy warriors (7 in reach on average), 21 % after peons, 14 % at other buildings, 25 % walk. Nothing orders a
warrior onto a tower: the army attack-moves, and the engine's own scan takes any warrior or peon within 8 cells
first. Sappers are in reach 996 s and swing 172 s of it.

**1. The storm** (`storm=17`: every warrior of the attack within `storm_join` 24 cells of a manned tower that has
one of them in its reach is sent at it as a hunt, which does not scan on the way; one with an enemy warrior in
throwing reach fights that first; the sappers within 30 cells go in with them). It does what it says, and it loses
games:

| logged, 12 games | contacts (razed) | first damage | a razed tower stands | warriors + peons a razed tower | the garrison's kills a game |
|---|---|---|---|---|---|
| `tw0log` default | 20.2 (16.4) | 19.0 s | 29.5 s | 9.4 + 1.0 | 104 |
| `st1log` storm=17 | 20.7 (17.4) | 10.0 s | 20.5 s | 7.4 + 0.5 | 74 |
| `st2log` and storm_close=5.5 | 15.6 (12.9) | 12.0 s | 21.0 s | 8.1 + 0.5 | 54 |
| `st8log` storm_per=24 | 24.0 (20.8) | 11.0 s | 21.8 s | 7.6 + 0.9 | 84 |
| `st9log` storm_per=24, 12 sappers at the head | 21.9 (18.8) | 8.6 s | 18.0 s | 6.2 + 1.8 | 68 |
| `st10log` no warriors, 12 sappers at the head | 20.4 (16.9) | 12.5 s | 23.4 s | 7.2 + 3.1 | 81 |

| 150 maps 25151..25300 from the first start | wins | against `ml2-vs16` | copies housed at min 20 | attack lost / sappers lost an hour |
|---|---|---|---|---|
| `ml2-vs16` `@f12` | 110 | | 9.55 | 436 / 34 |
| `sa2` storm=17 | 95 | +11 -26 | 9.82 | 424 / 24 |
| `sb2` and storm_close=5.5 | 95 | +8 -23 | 9.82 | 421 / 23 |
| `sc2` storm_per=24 | 95 | +10 -25 | 9.63 | 409 / 27 |
| `sd2` storm_per=12 | 103 | +11 -18 | 9.40 | 435 / 36 |
| `sg2` storm_per=-1 (the sappers alone) | 110 | +11 -11 | 9.49 | 420 / 46 |
| `sh2` the sappers alone, 12 of them at the army's head | 108 | +16 -18 | 9.46 | 414 / 106 |
| `si2` the same with 16 to 24 | 100 | +9 -19 | 9.62 | 387 / 120 |

Why. The pictures (`st4pic-vs16`): a hunt stops at the first cell in reach, so the first dozen warriors of a broad
front stand shoulder to shoulder on the arc 8.9 cells from the tower and the rest cannot pass them: 12 to 25 throw
while 50 stand behind in the tower's reach for the 10 to 13 s it lasts (st1: half the warrior-seconds in reach are
that walk). `storm_close` (walk to the tower's foot first, each on its own bearing) fills the arc faster and starts
it 2 s later: the same 21 s. And those fifty are the ones that otherwise cut the copy's peons down and throw at its
quarters: with the storm the campaign is 0.27 copies behind at minute 20, which is what the 15 wins are (a copy at
minute 20 is worth 25 to 30 points). The more warriors on the tower, the fewer wins: all 95, 24 95, 12 103, none
110. The garrison kills one in four seconds whatever we do; 133 thrower-seconds buy the end of that, and the same
seconds on peons and warriors are worth more. The sappers do not change the sum either: sent at once they swing a
little more and die a little more, led at the army's head (`storm_lead`) they die three times as often for 5 %
fewer warriors lost.

**2. The bait** (`bait`, class `Bait`). The garrison's rule above has a hole: what it finds first, it throws at until
that is dead. A tower site of ours with one row of cells inside its scan square, its builders outside the square,
found while no unit of ours is in the square, holds the garrison as long as it stands, and builders with wood mend
it faster than an iron axe breaks it. Confirmed in games: s22001 of `bt2log-vs16` at 1528 s, a garrison on a site
2.5 s after it was placed and for the 50 s the trace goes on, the site growing from 1 to 43 hit points under it,
no builder shot; in the eight contacts of that run with the garrison on a site most of the time, 0 to 3 warriors
dead in six of them (2 dead with 99 warriors in reach for 22 s) where a razed tower costs nine.

It is not a counter yet, because it has to stand before the first warrior enters the square:

| what was needed, found in this order (logged runs `bt1log`..`bt10log`) | |
|---|---|
| a building slot | we are at the cap of 20 most of the game: our towers must leave it (`canBuildTower`) |
| wood in hand | a site without it has 1 hit point when the first throw lands; the sappers chop on the way (`Bait.wood`), crews are wood carriers only |
| two towers a copy | a job a tower, two at a time |
| the way | the nearest side of the square as the crow flies is often across water: the side nearest on foot (`computeField`) |
| time | a crew makes 1.5 cells a second, not the 2.5 of its speed, and starts behind the army's centre: sent ahead it dies (`bt6log`: 137 sappers lost an hour), from behind it comes through a jammed army 20 to 35 s late, so the head is held outside the square meanwhile (`bait_hold_ticks`) |
| a site that lasts | a crew of five has 25 hit points of wood; 20 to 30 of the locks end with the site down before the tower |

With all of it (`bt10log`): 16 jobs an hour of game, 8 sites placed, 3.4 garrisons locked, 1.2 towers razed under
lock, the garrison at a building 45 s a game where it is at a warrior 386 s. And on 150 maps it costs 22 to 24 wins, with the hold or
without (`ba2` 86, `bb2` with the storm on locked towers 87, `bc2` without the hold 88, against 110: +9 -31). What
that cost is made of is not isolated. The two building slots are 1.4 towers fewer at home from minute 12 (12.0 to
10.6), ten peons stand with the army and 80 sappers are lost an hour for 34, and the home army loses 24 to 32 more
an hour. The slots alone are not it: with seven quarters, which is a tower fewer too (11.0), the default wins 104
(`q72-vs16`, +13 -19), and with five quarters and 13 towers 105 (`q52-vs16`, +15 -20): six quarters sits on a
plateau. Left: the standing squad, and the sites themselves, which a Hard copy's waves take for our nearest
building (`Decoys`).

**3. What a garrison cannot reach.** The scan square's corners are out of its throw (15.9 cells; a corner is 19.8):
a warrior of ours there is found, cannot be thrown at, and is found again at every scan as long as no warrior
stands nearer. It would hold a garrison without wood or a site. Not built: like the bait it needs every other
warrior out of the square, and peons alone at the tower's foot among the copy's own warriors.

**Where this leaves the towers.** 104 warriors a game die as a garrison's target, of 196 dead in a tower's reach. Every way to
shorten the contact takes warriors from the work that decides the game or peons from the economy, and both ways to
stop the garrison throwing need units in place before the army arrives. The stun is the engine's own answer (a cast
within 18 cells, 10 to 30 s for every tower there), and the chieftain is at home for 250 kills an hour.

### The chieftain's blast, traced: five repairs that work and none that wins (2026-11-20)

The user's direction after the tower round: the chieftain. Again a log-only tracer first: `ChiefTrace` (every half
second the enemy warriors about him by what they do: parked, hunting him, hunting another unit of ours, walking,
other; how many a blast would reach from where he stands and from the best cell a 6 s and a 15 s walk away; with
`--logs` a `CHIEF s` line a second, a `CHIEF blast` line a cast with who was in reach at the order, who the blast
took, who died and what it cost him, `CHIEF dmg`, `CHIEF dead`, and a `CHIEF sum` a minute), read by
`lab/fable/chief.py`. And counters that need no logs: `shred_taken`, `shred_killed` (the enemy warriors within 18
cells when a blast takes its targets, and those of them dead when its ring has passed), `chief_spotted` and
`chief_spotted_charged` (an enemy warrior begins to hunt him with none after him the round before), `chief_hp_lost`.
Nothing of this round is a default: `fable` without params plays `@f12`'s games (8 of 8 identical on 26001..26008,
`chk12d-vs16`; with the first two repairs in the code, 150 of 150 on 25151..25300, `cz2-vs16` against `ml2-vs16`).

**The engine's blast and the engine's wave** (`SonicBlast`, `SonicBlastFactory`, `MagicBehaviour`, `Stun`,
`AdvancedAI.nodeAttackWithWarriorsAndChieftain`, `WalkBehaviour`):

- A cast is six seconds in which he stands, whatever is ordered. 2.15 s after the order the blast takes its targets:
  everything within 36 m of him then. From 3.77 s a ring runs out to 36 m in 0.8 s and hits each target as it
  passes, with a chance of 2 to 17 (by the ring's radius; 2 from about 8 m out) times (1 - defense) and 150 to 30
  damage: certain for rock warriors and peons, .6 to .65 for iron. A unit that walks in after the 2.15 s is not hit.
  If he dies before the 3.77 s there is no blast.
- The stun is the same cast with 10 to 30 s by distance on the same curve: 16 s at one cell, 10.7 s from three
  cells out. From the 17 cells the standoff at a tower keeps it is a ten-second stun.
- A wave is not sent after a unit. It is an attack-move to the cell where our unit nearest the copy's oldest idle
  warrior stood at the launch (or to our nearest building, when that unit is not within 0.71 of its distance). Its
  warriors scan 8 cells (Chebyshev) as they walk, hunt what they see (a hunt does not scan), and park at the cell
  when they arrive with nothing in sight.

**What he does in an hour of game** (`@f12` at N=16; the 150 maps 25151..25300, `cz2-vs16`): 10.3 blasts that take
277 enemy warriors and kill 204 of them, 7.5 % of our 2,726 kills, and 1.5 chieftains lost. In the 12 logged games
(22001..22012, `cf2log-vs16`):

- He is there 2,540 s of the 3,600: the first is born at 683 s, and a dead one is away 198 s (median; quartiles 196
  and 209: the training with 14 peons inside). So the charge allows 33 casts an hour, not the 51 the micro round's
  note counted on the whole hour, and he makes 12.4.
- A blast takes 27 warriors (quartiles 18 / 26 / 34), 14.5 of them rock, and kills 19.8; 2.2 of ours stand in it
  and 1.4 die. 9.4 of the 12.4 are cast with an awake warrior within 9 cells of him ("hunted": 22.5 in reach at the
  order, 28.0 taken, 2.4 of his hit points lost in the cast), 2.7 when the fill is reached on the way to a cast
  point (25.3 taken, 0.3 hit points), 0.4 from the cast point itself.
- While a charge waited, a blast would have reached 21 at most from where he stood, 38 from the best cell a 6 s walk
  away and 48 from a 15 s walk: he takes the edge of what is there.
- A charge waits 72 s for its blast (median 27). Of 13.5 charges an hour 3.6 are cast within 15 s, 4.3 within 45 s,
  3.5 within two minutes (276 s together) and 2.2 after more (542 s: ten approaches and 281 cells walked each).
  Another 600 s of "charged, no blob" are the ends of won games.
- 39.7 walks to a cast point an hour: 23.3 end with an awake warrior within 9 cells (the mission is called off and
  he walks towards the armory), 10.8 with the blast (the point 47 cells off at the start, 17 s), 5.1 with the blob
  gone (94 cells off at the start). In a walk that ends with the blast the cast point jumps 3.9 times, 119 cells:
  the blob is chosen anew every half second by members / (30 + cells) and the sizes change with every launch
  (s22005 at 1119-1207 s: blobs 130 cells north and 65 south by turns, a turn every few seconds).
- An enemy warrior begins to hunt him 24.3 times an hour, 16.1 of them with a walking wave within 17 cells the
  second before and 8.1 with parked warriors only: the waves that pass see him, not the blobs he walks up to (a
  cast point may lie 6 cells from a parked warrior, but he blasts on the way). 12.2 times an hour hunters hold a
  charged blast up, 453 s: one to three of them 6.4 times (223 s), four to seven 3.4 times (109 s), with a parked
  blob of 15 and more within a 15 s walk in 60 % (s22003 at 1034 s: four of them, 86 s to the armory with a blob
  of 50 27 cells behind him, 75 s back).
- Charged with 8 and more in reach and no cast: 146 s an hour. 87 s of it our own units rule it out (22 of ours
  within 19 cells: the home army, gatherers, builders: he is at the base or in a fight of ours), 42 s an enemy
  within 5 cells of him does (`castValue`'s rule for cast points, applied to the cell he stands on: with a hunter at
  him he cannot blast at all), 17 s he waits for the fill.
- 109 hit points lost an hour (he never heals), 89 % with a hunter of his within 17 cells: 45 charged, 63 not, 24 in
  the casts. 1.7 deaths, 1.1 of them with the blast charged.
- Of 4,500 launches in the 12 games 3,945 go to a shepherd's cell and 63 to the chieftain's: he is seen, not aimed
  at.

**Five repairs** (all off):

- `shred_point_blank`: with an awake warrior within 9 cells, an enemy within 5 cells of him does not rule out a
  blast from where he stands.
- `shred_press` (`shred_press_cells` 30, `shred_press_fire_cells` 12, `shred_press_hunters` 3): charged, with a cast
  point near, he is not turned back by an awake warrior within 9 cells, walks on with what hunts him and blasts at
  the fill or when he is hit. `shred_defend` (n): being hit he blasts for n in reach instead of `shred_min`.
- `shred_stick` (factor): the blob chosen within the last 10 s scores that much more.
- `shred_sidestep`: he keeps 10 cells (Chebyshev) from the next 14 cells of every walking enemy warrior's way: steps
  to the nearest of 32 cells about him that is clear, and waits only where no way passes.
- `shred_range` beyond 140 with these: 37 % of the moments with no blob have one of 15 and more within 200 cells.

Logged, per 1,000 s he is there (12 games each; `cf2log`, `cb1log`, `cq1log`, `cq2log`, `cs1log`, `cd1log`):

| | casts | taken | killed | deaths | hit points lost | a blast takes / costs him | a charge waits, approaches |
|---|---|---|---|---|---|---|---|
| default | 4.9 | 132 | 97 | 0.67 | 43 | 27.0 / 1.9 | 72 s, 3.3 |
| point blank | 5.6 | 162 | 119 | 0.85 | 49 | 29.0 / 2.0 | 71 s, 3.4 |
| and press (any number of hunters) | 6.0 | 189 | 133 | 1.34 | 77 | 33.3 / 5.6 | 56 s, 2.0 |
| and press with two hunters at most, defend 3 | 5.6 | 160 | 115 | 1.05 | 66 | 29.2 / 4.0 | 60 s, 2.1 |
| and press with three, defend 4, stick 1.6 | 5.3 | 154 | 109 | 1.16 | 70 | 29.5 / 3.8 | 69 s, 2.6 |
| sidestep alone | 5.5 | 141 | 104 | 0.73 | 43 | 25.7 / 1.2 | 76 s, 2.5 |

The sidestep does what it is for (a walking wave sets a hunter on a charged chieftain 3.1 times an hour for 8.8)
and the pressed blasts are the big ones (38 to 43 taken), at the price of the cast: he stands six seconds with
whatever followed him in throwing reach.

On 150 maps from the first start (25151..25300, an hour of game):

| | wins | against `cz2-vs16` | blasts | killed by them | chieftains lost |
|---|---|---|---|---|---|
| `cz2-vs16` `@f12` | 110 | | 10.3 | 204 | 1.5 |
| `pb2` point blank | 105 | +5 -10 | 11.7 | 234 | 1.6 |
| `sj2` sidestep | 104 | +10 -16 | 10.7 | 210 | 1.6 |
| `sk2` sidestep, point blank, stick 1.6 | 107, 1 draw | +13 -16 | 11.8 | 230 | 1.5 |
| `sr2` and `shred_range=200` | 101 | +14 -23 | 12.8 | 239 | 1.8 |
| `pp2` point blank and press | 103 | +8 -15 | 11.1 | 248 | 2.3 |
| `ha2` point blank, `hold_chieftain_again=40` (a later chieftain trained by 40 peons) | 108 | +7 -9 | 12.1 | 241 | 1.6 |
| `sv2` `shred_from_ticks=60000` (with the army until minute 20) | 104, 1 draw | +10 -16 | 8.1 | 152 | 1.7 |

And point blank on the 300 maps 26001..26300 (`pbm-vs16` against `f12m-vs16`): 210 wins and two draws for 214 (+11
-15), 11.3 blasts for 10.2, 258 in reach at the order for 229, 1.6 chieftains lost for 1.6.

So the one repair that is free is the point-blank rule: an eighth more blasts and a seventh more kills by them at
the same losses, on both blocks. The others add little to it (the sidestep 3 % by itself, the wider range, the
press and the fuller trainer 2 to 6 % on top) and cost what they gain: the sidestep its walking (100 s an hour more
on the way), the press and the wider range chieftains (2.3 and 1.8 lost for 1.5).
And none of it shows in the wins: every arm is 3 to 9 under the default on the 150 maps and point blank 4 under it
on the 300 (+16 -25 over both, z -1.4). Part of that is the default's own luck on these maps (`@f12`'s options were
chosen on them, so anything that plays other games falls back towards its true rate), and the rest is the size of
the thing: thirty more kills an hour are one in a hundred of ours. All five stay off.

**His stun with the army** (`shred_from_ticks=60000`: he marches and stuns as before the mission until minute 20,
then blasts; `sf1log-vs16`, 12 logged games): 4.2 stuns a game in those nine minutes where the charge allows 13,
and the garrisons of the towers the army meets are stunned 7 s a game of their 686 s of contact. As written he
never stuns a tower: `shouldStun` wants a catch of 4 with a tower counting 3, and he marches behind the army's
middle while the towers stand at its head. And a tower's stun from the standoff is ten seconds, two or three
warriors of the nine a razed tower costs.
On the 150 maps (`sv2-vs16`) the nine minutes with the army are worth 0.09 copies at minute 20 (9.46 housed for
9.55) and cost the blasts of those minutes: 470 to 480 enemy warriors stand at minutes 18 to 20 for 441 to 457, the
blasts kill 152 an hour for 204, and it wins 104 and a draw for 110.

**Where this leaves the chieftain.** His blasts are a fourteenth of our kills and nothing found doubles them: the
targets within his 140 cells are transient (a blob launches as soon as its copy has a wave's worth idle), the waves
that pass see him, and every way to blast more often stands him six seconds among what hunts him. The mission as it
is uses 12 of the 33 casts his time allows; the repairs here reach 13 to 14.

### The home army, traced: its fights are sound, its walk to them is not (2026-11-20)

The user's direction after the chieftain round: the home army's lopsided fights (218 of its warriors dead a game, 134
of them at two to one and worse). Again a log-only tracer first. `Military` notes what gave every warrior its last
order (`trace_why`: engage1, the answer to an enemy by a peon away from the base, threat level 1; engage2, the
answer to a threat in the base; fall, the walk back to the armory from one that is too strong; post, `holdAtPost`;
the army's mode otherwise), and its "lost ROLE at ..." line now ends with that order, how long the warrior had it,
where to and from where, the zone and the threat then. `HomeTrace` writes a "HOME s" line a second (the threat as
the military sees it; the warriors in role army by why/zone/act; the enemy warriors within 45 cells of a building
of ours by zone and kind; the peons the enemies of a level-1 threat stand by; the post) and a "HOME kill" line for
every enemy warrior that dies, with who of ours was throwing at it half a second before. Zones: tower (within 16
cells of a manned tower of ours), base (within 28 of a building of ours), near (within 45), far. Read by
`lab/fable/home.py`.

**What the home army is and what it does** (`@f12` at N=16, 12 logged games on 22001..22012, `ha2log-vs16`; an hour
of game). Role army is the army at home in the modes HOME and MUSTER, and in ATTACK and RETREAT the warriors that
wait at the armory until 12 of strength may leave together as reinforcements. 37,800 warrior-seconds an hour (10.5
warriors at any moment: 16,000 in ATTACK, 9,300 in HOME, 8,400 in RETREAT, 4,100 in MUSTER) and 155 deaths (138 on
the 150 maps of `hb3-vs16`), a seventh of our 1,100.

| the last order | warrior-seconds | deaths: tower / base / near / far zone | all | enemy warriors killed | for a death |
|---|---|---|---|---|---|
| engage2 | 10,990 | 30.5 / 45.2 / 10.4 / 3.9 | 90.0 | 156 | 1.73 |
| engage1 | 7,840 | 1.5 / 10.1 / 13.4 / 16.8 | 41.8 | 64 | 1.54 |
| fall | 6,360 | 15.4 / 6.9 / 0.2 / 0.1 | 22.6 | 26 | 1.13 |
| none yet, or the mode's own | 12,640 | | 1.1 | | |

- The kills of an hour, by who was throwing: the attack 555, our towers 432, nobody 555 (the blast's 200, axes on
  their way, and 220 within 16 cells of a tower), the home army 246, reinforcements on their way 55. By the game
  record, theirs for ours: 687 / 51 in the tower zone, 257 / 68 in the base zone, 127 / 42 near, 778 / 418 far.
- So the home army trades 1.6 for one, as the attack does or better, and an engaged fight in the base trades 1.7
  to 2.0 at every ratio of numbers from one of ours for four of theirs up (`ratio.py` of the session: the seconds
  of threat level 2 by home warriors for one enemy warrior in the tower and base zones). The fights are not the
  leak.
- What is lopsided is the company a warrior dies in. Of the 90 deaths under engage2, 18 have at most two mates
  within 12 cells and 27 have three to seven; of the 42 under engage1, 14 and 18. `engageSpread` sends each
  defender to the enemy nearest to it from wherever it stands, and they arrive one by one: 62 % of the
  warrior-seconds under engage2 and 75 % under engage1 are a walk.
- The answer to harassment (engage1) is a fifth of the home army's time. 53 % of its seconds answer a threat more
  than 45 cells from our nearest building and 23 % one more than 100 cells away; in half of the seconds twice the
  counted strength stands within 15 cells of the threat's centre, because the threat list holds only the enemies
  within 12 cells of a peon. The enemies that make such a threat stand by a sapper in 1,108 enemy-seconds an hour
  (792 of them more than 100 cells from every building of ours: the attack's own front), by an iron gatherer in
  995, by a peon on its way into a building in 595 and by a tree gatherer in 312 (`ha3log-vs16`).
- With one of ours for four and more of theirs in the base (507 s an hour, 2.9 home warriors) the army falls back:
  12.9 deaths for 9.3 kills, while the towers kill 240. Those are the warriors that leave the armory one by one
  into a base that is being overrun; the game is lost by then.
- Where a game is lost (`cz2-vs16`, 150 maps; won 110, lost 40): the lost games lose 5.3 / 7.6 / 6.1 towers in
  minutes 20-30 / 30-40 / 40-50, 97 % of them an armory, the first at minute 28 (median); the won games 1.8 / 1.0 /
  0.7 towers, and half of them an armory at some time.

**The post** (`hold_ratio`, `hold_multi`: Gauntlet's flags, screened once at N=15 on the defaults of then, "Screen
`pc1`" above: -1.2 +- 2.7 wins of 100). With `hold_multi=true,hold_ratio=0.3` the defenders of a threat that is at
least 0.3 of their own strength walk to the building of ours nearest the threat, a manned tower counting somewhat
nearer (a tower in 87 % of the post's seconds), and fight what comes within 14 cells of it; a weaker threat they walk
out to as before (3.2 kills for a death). It applies to both levels: against harassment the warriors picked for it
(twice its strength and 2) take the post when two and more enemies are counted, and in 78 % of those seconds the
post stands more than 28 cells from the threat: they do not walk out at all.

Logged, an hour of game (12 games each; the games differ once the decisions do):

| run | home army lost | under engage2 and post | under engage1 | under fall | enemy warriors it kills | the post moves | buildings lost |
|---|---|---|---|---|---|---|---|
| `ha2log` `@f12` | 155 | 90 | 42 | 23 | 246 | | 21.0 |
| `hp2log` `hold_ratio=1.2` | 159 | 89 | 41 | 26 | 229 | | |
| `hp3log` `hold_ratio=0.3` | 100 | 65 | 4 | 26 | 169 | once in 15 s | 13.6 |
| `hs1log` and `hold_stick=12` | 65 | 51 | 3 | 11 | 120 | once in 42 s | 9.1 |
| `hc1log` and `hold_commit=20` | 95 | 67 | 5 | 20 | 160 | once in 27 s | 18.1 |
| `hu1log` both | 77 | 52 | 2 | 21 | 112 | once in 64 s | 19.0 |
| `sp1log` `threat_sappers=false` alone | 165 | 80 | 60 | 21 | 241 | | 18.5 |

- The deaths in small company go: under engage2 and post together, 7 with at most two mates for 18 and 13 with
  three to seven for 27, 40 in a group of eight and more for 45; under engage1 10 for 42.
- As written the post follows the centre of the most dangerous group: it moves to another building once in 15 s,
  24 cells a move, and the defenders walk 72 % of their time at it (`hp3log-vs16`; s22006 at 2053-2104 s: eleven
  of them between three towers). `hold_stick=12` (the post of the last 5 s stays until another building is 12 cells
  nearer the threat): once in 42 s. `hold_commit=20` (it stays while a threat stands within 20 cells of it): once in
  27 s. Both: once in 39 s on the 150 maps.
- `hold_far=30` (against harassment a post more than 30 cells from the threat is no post, and the warriors picked
  get no order) and `threat_sappers=false` (an enemy by a sapper alone is no threat, as one by a shepherd or a
  chicken hunter is none): see the table. The sappers' threats are far walks that are called off within seconds;
  without them the same warriors answer the threats by the far gatherers instead (logged, `sp1log-vs16`: 60 deaths
  an hour under engage1 for 42, the threats more than 100 cells out 54 s an hour for 174).

On 150 maps from the first start that no choice had been made on (28001..28150), an hour of game:

| | wins | against `hb3-vs16` | home army lost | units lost | buildings lost | iron |
|---|---|---|---|---|---|---|
| `hb3-vs16` `@f12` | 106 | | 138 | 1,086 | 14.1 | 890 |
| `hp3` the post (`hold_multi=true,hold_ratio=0.3`) | 113 | +19 -12 | 91 | 1,040 | 12.0 | 863 |
| `hq3` the post against threats in the base only (`hold_levels=2`) | 103 | +14 -17 | 126 | 1,090 | 14.1 | 893 |
| `hr3` the post against harassment only (`hold_levels=1`) | 110 | +18 -14 | 111 | 1,053 | 13.0 | 873 |
| `hu3` the post, `hold_stick=12,hold_commit=20` | **114** | +19 -11 | 90 | 1,053 | 12.1 | 880 |
| `hv3` and `hold_far=30` | 107, 1 draw | +17 -16 | 104 | 1,088 | 13.2 | 857 |
| `hw3` `threat_sappers=false` alone | 108, 2 draws | +17 -15 | 147 | 1,110 | 12.3 | 886 |
| `hx3` `hu3` and `threat_sappers=false` | 115, 1 draw | +23 -14 | 96 | 1,062 | 12.1 | 864 |

- The answer to harassment is the larger part: 27 of the 46 home warriors an hour that the post saves, and 110 wins
  with it alone for 103 with the base's part alone. Both together are what was confirmed.
- `hold_far` loses what the post gains against harassment. The post's order is also what calls the picked warriors
  off the walk they were on; without an order they finish it.
- `threat_sappers=false` saves no home warrior alone (147 for 138) and adds nothing that 150 maps can tell on top of
  the post. Off.
- The post that stays is no worse than the one that jumps and walks less. It is the one confirmed.

**Confirmed and made the default** (`forGame`, large maps, more than one enemy: `hold_multi=true`,
`hold_ratio=0.3`, `hold_stick=12`, `hold_commit=20`; commit 49939d96, frozen as `@f13`). At N=16 on the 300 maps
28151..28450, used for nothing before: **217 wins for 204** (`hu4-vs16` against `hb4-vs16`, +34 -21), 72.3 % for
68.0 %; with the 150 before, 331 of 450 for 310 (+53 -32, z 2.3), 73.6 % for 68.9 %. An hour of game on the 300: 94
home warriors lost for 146, 1,041 units for 1,109, 2,673 kills for 2,745, 863 iron for 893, 14.1 buildings lost for
15.1, CPU 36.0 s for 38.4. **At N=17 on 27001..27300: 162 of 300 = 54.0 %** for `@f12`'s 156 (`hu5-vs17` against
`f12n-vs17`, +39 -33), 111 home warriors lost an hour for 160. **At N=18 on 29001..29300, maps of its own: 101 of
300 = 33.7 %** [28.7, 39.4] and a draw (`f13-vs18`; no earlier default was played there, `@f3` won 9.6 % of 1,000
other maps at N=18). `fable` without params plays the arm's games (12 of 12 on 28151..28162, `chk13a-vs16`), and
with the four options off `@f12`'s (8 of 8 on 26001..26008, `chk13b-vs16`).

**Looked at and left:**

- The fall back: 21 deaths an hour at one kill for a death. The warriors walk to the armory and wait there until the
  enemy comes to them.
- Enemy warriors locked in a hunt on a building of ours answer nothing until it is down (`HuntController`), and the
  military counts them at full strength. In the tower and base zones they are 6,300 enemy-seconds an hour, with
  4,260 locked on a peon and 1,700 on a warrior (`ha4log-vs16`), and for 185 s an hour five and more home warriors
  stand back from such a group. But each locked enemy has three to four about it that are not (10 on a building
  and 40 others for 17 of ours, `locked.py` of the session): counting them for less would not turn the decision. Not
  built.
- The sappers' threats: the home army's nearest warriors are sent towards the attack's own front, and the threat
  is over within seconds. A long walk begun and called off, not a fight.

### The reinforcements, traced: a group that is no group, and what closing it up costs (2026-11-21)

The user's pick after the home army round: the reinforcements, 83 lost an hour on their way, two thirds of them alone
or in small company. A log-only tracer first. `Military` numbers the groups as `considerReinforcing` sends them
(`reinforce_groups`), and `ReinforceTrace` writes a "REIN start" line when a group leaves (where each member stands
and what gave it its last order before), a "REIN s" line a second while a member is on its way (those on the way,
joined, dead and turned back; len, the widest distance between two of them; how many fight and walk; the enemy
warriors within 14 cells of any of them by `HomeTrace`'s kind; the cells to our nearest building and to the army) and
a "REIN end" line. `HomeTrace`'s kill lines name the group whose member was throwing. Read by
`lab/fable/reinforce.py`: a meeting is a run of seconds with three and more enemy warriors within 14 cells of a
member.

**What the reinforcements are** (`@f13` at N=16, 12 logged games on 22001..22012, `rt2log-vs16`; an hour of game).
38.6 groups of 12.9 warriors, to an army 76 strong and 167 cells away (medians). Of 497 warriors sent 360 join, 86 die
on the way and 49 turn back because the attack ended first. They kill 72 enemy warriors on the way: 0.84 for one of
theirs, where the army and the home army trade 1.6. A group is on its way for 112 s (median): 46,000 warrior-seconds
an hour, 13 warriors at any moment.

- A group is everyone in role army, wherever they stand. Five seconds after it is sent its two members farthest
  apart are 48 cells from each other (the median of 487 groups; quartiles 28 and 74), and 42 after a minute: all
  walk at one speed, so it never closes up. Of the 497 members 324 stand within 10 cells of the middle one, 61 within
  11 to 20, 60 within 21 to 40 and 52 farther. The far ones are fresh warriors at another armory's door (54 of the
  280 that had no order yet stand more than 20 cells off), half of the 60 that were answering harassment, and 15 of
  the 88 at a post.
- 76 of the 86 deaths fall in 18.6 meetings an hour. A group strung over more than 30 cells (12.1 meetings) loses 51
  for 40 kills, over 17 to 30 cells 19 for 18, over 16 and less 6 for 7. By the enemy's strength for one of the
  group there: up to a half 3.8 deaths for 8.2 kills, a half to one 9.3 for 9.3, one to two 33.5 for 27.9, two to
  four 25.3 for 17.8. By what the enemy was doing when they met: walking (a wave on its way) 30 deaths, parked 21,
  hunting a warrior of ours 11, hunting a peon 9. 30 of the deaths fall more than 100 cells from every building of
  ours, 23 within 46 to 100, 23 nearer.

**Two repairs, built behind options** (both off):

- `reinforce_gather=10`: while the attack is out the idle warriors at home that defend nothing walk to the staging
  point, as they do in HOME mode (`gatherHome`), and a group is the warriors within 10 cells of the one with the most
  about it (`densest`); the rest wait for the next.
- `reinforce_march`: a group on its way fights as one body (`marchGroup`). With an enemy warrior within 10 cells of a
  member every member goes at the enemies about the group, each at its nearest (`engageSpread`, as the army does);
  and a member more than 12 cells nearer the army than the one a third of the way back stands until the rest are up.

Logged (12 games each on the same maps; the games differ once the decisions do), an hour of game:

| | sent | joined | dead on the way | turned back | enemy warriors they kill | for a death | len at 5 s | the home army, warrior-seconds |
|---|---|---|---|---|---|---|---|---|
| `rt2log` `@f13` | 497 | 360 | 86 | 49 | 72 | 0.84 | 48 | 36,100 |
| `rg1log` `reinforce_gather=10` | 364 | 249 | 55 | 58 | 54 | 0.98 | 13 | 52,000 |
| `rm1log` and `reinforce_march` | 493 | 341 | 87 | 63 | 128 | 1.47 | 13 | 54,500 |
| `ro1log` `reinforce_march` alone | 516 | 375 | 85 | 54 | 119 | 1.40 | 48 | 36,600 |

- The gather does what it is for (len 13, and 19 after a minute), and by itself changes little in the fights: the
  members of a compact group still fight one by one, each at what it sees itself (0.98).
- The march is what the kills come from: 1.4 for a death against up to twice the group's strength and 1.0 against
  more, with the group as strung out as before (with the gather 1.6 to 2.4, and 1.0). It costs no time: 700
  warrior-seconds an hour standing.

On 150 maps that no choice had been made on (30001..30150), an hour of game:

| | wins | against `rb3-vs16` | sent | joined | lost on the way | home army lost | kills | iron |
|---|---|---|---|---|---|---|---|---|
| `rb3-vs16` `@f13` | 109 | | 519 | 372 | 88 | 94 | 2,681 | 879 |
| `rg3` `reinforce_gather=10` | 97 | +12 -24 | 461 | 331 | 71 | 118 | 2,735 | 849 |
| `rm3` and `reinforce_march` | 105 | +16 -20 | 460 | 328 | 69 | 115 | 2,721 | 841 |
| `ro3` `reinforce_march` alone | 109 | +12 -12 | 527 | 362 | 94 | 101 | 2,702 | 883 |
| `rq3` `retreat_regroup=8` | 108, 1 draw | +6 -7 | 540 | 408 | 92 | 86 | 2,672 | 877 |
| `rs3` `retreat_regroup=8,reinforce_march=true` | 107 | +11 -13 | 565 | 420 | 102 | 91 | 2,732 | 899 |

- **The gather costs what it was meant to save.** It keeps five more warriors at home at any moment (36,100
  warrior-seconds an hour become 52,000 to 54,500): 6,000 of them are the walk to the staging point and the wait for
  the cluster, 10,000 to 13,000 are defenders that stay at their post or in their fight, where the group as sent
  takes them along from wherever they stand. An eighth fewer warriors reach the army (328 to 331 for 372), the home
  army loses 21 to 24 more an hour, and no fewer buildings fall (14.0 and 15.6 for 13.6). 97 wins for 109 (z -2.0).
- It is the result of every wait measured here before (`capped_clump`, `late_reinforce_min`, `bank`, `convoy`,
  `siege`, and the routed reinforcements' longer way): at the unit cap a warrior that stands is a slot that neither
  fights nor gathers. What looked like waste, warriors leaving from wherever they stand, is the home army being
  emptied into the attack as fast as it fills.
- The march alone is level: 109 for 109 (+12 -12), 21 more kills an hour, as many joined.

**The retreat and the reinforcements.** A retreat (`beginRetreat`) sends the reinforcements on their way home with
the army. An hour of game has 2.6 of them (`rt2log-vs16`): 1.0 worn (the army 16 strong, 21 reinforcements on their
way to it), 0.8 outmatched (26, 16 on their way, the enemy 55) and 0.8 before contact (28 against 34, 9 on their
way). Those are the 49 reinforcements an hour that turn back. `retreat_regroup=8`: an army that would turn back with
8 and more of strength on their way within 120 cells of it, and with them strong enough not to
(`retreat_regroup_need`), falls back on them instead. Its units that are not in a fight walk to the nearest of them,
they keep coming, and once they have joined the army is weighed anew, the worn retreat against what it is then; no
second one within 60 s. Logged (`rq1log-vs16`): 0.94 regroups an hour (worn 0.34, outmatched 0.40, before contact
0.20), all joined, in 10 to 58 s; 33 reinforcements an hour turn back for 49 and 388 join for 360. Half of them are
followed by a retreat within two minutes all the same, and an outmatched army is smaller after one than before
with what joined it (31 and 17 make 21).

- The time with no attack out is not what it buys back. HOME mode is 21 % of the time, and 15 of the 21 are the
  lost games' last stretch, a threat in the base and three warriors at home; in the games won the next attack
  leaves at once (1.6 % of the time).
- On the 150 maps: 108 and a draw for 109 (+6 -7), 408 reinforcements joined an hour for 372. With the march 107
  (+11 -13), 420 joined, 51 more kills an hour and 2 % more iron.

**Confirmed as harmless and made the default** (`forGame`, large maps, more than one enemy: `reinforce_march=true`,
`retreat_regroup=8`; frozen as `@f14`), under the rule of 2026-11-19: waste removed that shows no harm is adopted.
At N=16 on 30151..30300, used for nothing before: 103 wins for 104 (`ru4-vs16` against `rb4-vs16`, +19 -20); with
the 150 before, 210 of 300 for 213 (+30 -33). At N=17 on 27001..27300: 160 of 300 for 162 (`ru5-vs17` against
`hu5-vs17`, +37 -39). An hour of game: 405 reinforcements joined for 358 at N=16 and 360 for 337 at N=17, 19 and 64
more kills, the losses and the buildings lost the same. `fable` without params plays the arm's games (12 of 12 on 30151..30162, `chk14a-vs16`),
and with the two options off `@f13`'s (8 of 8 on 30151..30158, `chk14b-vs16`).

**The attack army's time** (the "battle:" lines, every 4 s while an attack is out; `rt2log-vs16`). 198,000
warrior-seconds an hour, 55 warriors. By what stands within 22 cells of its centre: nothing, and more than 40 m from
its target, 66.6 % (26.5 % more than 250 m from the target, 20.7 % at 121 to 250 m, 19.4 % at 41 to 120 m); three
and more enemy warriors 19.0 %; a tower with fewer than three warriors 7.6 %; nothing, at the target, 6.9 %. With
nothing near, its centre moves 1.3 to 1.5 cells a second (the median; under 0.8 to 1.0 a quarter of the time), where
a group of reinforcements with nothing near moves 1.9. Two thirds of the army's time is the walk between targets,
at seven tenths of a warrior's pace; the reinforcements' way is 46,000 warrior-seconds more and the home army
36,000. Not looked into: which legs those are, how often the target changes under way, and what the slow quarter
is.

### The march, traced: the walk between copies is the map's, and a faster campaign is not a won one (2026-11-21)

The lead of the reinforcements round: two thirds of the attack army's time is a walk with nothing near. A log-only
tracer first. `MarchTrace` writes a "MARCH s" line a second while an attack is out (the branch of `Military.attack`
that gave the round's orders, `trace_phase`: march, column, engage_warrior / chieftain / tower / peon / building by
what `enemyFocus` found, hold, regroup; the army's size and centre, the straight line to the target and the walking
distance to it of the pivot, the front and the last unit; what the units do and what their hunts are after; the
enemy about; of the walking units the mean cells moved and metres of walking distance gained), a "MARCH target" line
when the target changes and a "MARCH pick" line with the eight best candidates when `retarget` chooses. Read by
`lab/fable/march.py`.

**The army's time** (`@f14` at N=16, 12 logged games on 22001..22012, `mt3log-vs16`; an hour of game). An attack is
out 2,759 s of the hour with 84 warriors (233,000 warrior-seconds, 65 warriors over the whole hour).

| the round's orders | seconds | warrior-seconds | share | throwing | closing | walking |
|---|---|---|---|---|---|---|
| march (and column) | 1,256 | 141,100 | 60.7 % | 2 % | 1 % | 96 % |
| engage: enemy warriors | 850 | 51,400 | 22.1 % | 39 % | 19 % | 42 % |
| engage: peons, no warrior within 22 cells | 260 | 17,100 | 7.4 % | 38 % | 11 % | 51 % |
| engage: a tower | 111 | 7,500 | 3.2 % | 36 % | 14 % | 50 % |
| hold for a group that closes | 119 | 6,500 | 2.8 % | 15 % | 6 % | 72 % |
| engage: a chieftain | 74 | 5,600 | 2.4 % | 30 % | 11 % | 59 % |
| engage: a building | 38 | 2,100 | 0.9 % | 53 % | 13 % | 34 % |
| regroup | 50 | 1,100 | 0.5 % | | | |

- **The march is the map's.** The units walk: 96.5 % of them, 0.3 % stand and 0.2 % are blocked, at 1.82 cells a
  second (a lone group 1.9) and 3.05 m a second of walking distance gained, so a sixth of the walk is sideways (the
  flanks converging on one waypoint, the trees). The targets are the nearest: of 17.5 picks an hour one takes a
  target 60 m and more past a nearer one, and the one taken is 156 m off where the nearest of its best eight is 145.
  The walk is the line: 1.06 times it (the median), 1.5 times and more for one target an hour. What is left is the
  distance between copies: 13.6 legs an hour to another copy at 274 m of walking each, 12.3 to the next building of
  the same copy at 38 m, 3.6 launches from home at 289 m.
- By the minute the march is a third of the time out until minute 15 and a quarter until minute 30 (at the target
  38 %, 33 %, 29 %, 26 % in minutes 5.5-10, 10-15, 15-20, 20-30; fights with warriors away from it 7 %, 17 %, 25 %,
  31 %); it is 64 % past the hour, when a won game walks to the far copies. The two thirds of the last round's
  reading are the long games' ends.
- **What stops the army for little.** `enemyFocus` turns the whole army on anything by its centre: enemy warriors,
  a chieftain or a tower within 22 cells, peons or a building within 14. Away from the target (more than 40 m) that
  is 187 s an hour for peons with no enemy warrior within 14 cells of any unit (68 warriors about 6 peons, 41 % of
  them hunting one), 97 s for one or two enemy warriors, 47 s for a tower by the way and 36 s for a chieftain; and
  29 holds an hour, 119 s, for a group that the army closes on (`hold_closing=0`: its own march counts).

**Three options** (all off): `march_past=20` (while the centre is more than 20 cells from the target, peons and
buildings by it do not turn the whole army; its units still take what comes within their own sight), `engage_min=3`
(nor do enemy warriors worth less than 3 together within 22 cells), `hold_approach=false` (no hold). Logged
(`mp1log`, `me1log`, `mh1log-vs16`): the army's seconds on peons 260 -> 102, on warriors 850 -> 557 with both, the
hold gone; 144 and 265 s an hour of turns not taken (`march_past_rounds`, `engage_min_rounds`).

On 150 maps that no choice had been made on (31001..31150), an hour of game:

| | wins | against `mb5-vs16` | copies housed at minute 20 | kills | reinforcements joined |
|---|---|---|---|---|---|
| `mb5-vs16` `@f14` | 109 | | 9.56 | 2,696 | 413 |
| `mp5` `march_past=20` | 112, 1 draw | +17 -14 | 9.45 | 2,672 | 391 |
| `me5` `march_past=20,engage_min=3` | 108 | +14 -15 | 9.27 | 2,709 | 392 |
| `mh5` `hold_approach=false` | 104, 1 draw | +10 -15 | 9.34 | 2,718 | 399 |

**Confirmed as level, and left off.** On 31151..31300, used for nothing before, `march_past=20` wins 97 and a draw
for 104 (`mp6-vs16` against `mb6-vs16`, +13 -20, 9.42 copies housed at minute 20 for 9.50); with the 150 before, 209
and two draws of 300 for 213 (+30 -34). The default stays `@f14`; `fable` with the tracer and the three options off
plays its games (8 of 8 on 31151..31158, `chk15-vs16`).

- **A faster campaign is not a won one.** With both turns off 0.3 more copies have lost their last quarters and
  armory by minute 20, the reading that sorts the games best (9 housed: 86 % won, 11: 22 %), and as many games are
  won. The copies housed at minute 20 tell how the game stands; taking them out sooner by passing their peons and
  their stragglers does not change it. It is what `reinforce_min=6` showed at N=15 ("more copies out, no more
  wins").
- The hold is worth what it costs or more (104 for 109): the army that stands lets the group come to it.

**Where the games are lost instead** (`lab/fable/openwin.py` on the 450 games of `mb5`, `rb3`, `rb4-vs16`: 322 won).
The slowest fifth of the openings (under 86 units at minute 5) wins 35 of 88 and the rest 287 of 362: 40 % and 79 %.
Its armory stands 114 cells from the start (the median; the others 60), its second quarters at 173 s for 119, its
sixth at 438 s for 272, and a quarter of it has lost ten peons by minute 4. Those are the far plans of "The slow
openings are iron behind water" above, and they are 53 of the 128 games lost. In the games lost the enemy's warriors
grow from 545 at minute 20 to 589 at minute 25 while ours fall from 88 to 52; in the games won theirs stand at 425
and ours fall from 100 to 72.

### The opening, traced: the builders' walk, a rush alarm that is no rush, and how many to keep inside (2026-11-21/22)

The user's word after the march round: openings next, "lots of potential there, feels like the most important phase
of the game". A log-only tracer first. `OpeningTrace` writes an "OPEN s" line a second for the first ten minutes:
the units, the peons inside each quarters (with the hold it is to keep) and each armory, then every peon outside a
building once by what it does that second (strike, shepherd, carrying a site, the builders by their part of the
piece of wood: seeking a tree, chopping, carrying, hammering; walking into a building, idle, the gatherers by kind),
then every site going up with its hit points, its builders by part, how far from it they chop and the trees about
it. Read by `lab/fable/opentrace.py`. And a quick measure: a ten-minute game is enough to read an opening, so
`lab/fable/openspeed.py` compares runs of 100 maps cut at minute 10 (`--minutes 10`, five to seven minutes a run)
on our units at minutes 3 to 8, our warriors at 6, 8 and 10, the iron by 10 and when the quarters and the armory
stood, paired by map, with errors of half a unit to one and a half.

**The engine's opening, measured** (`ot1log-vs16`, 48 logged openings of `@f14` at N=16):

- A building is 40 pieces of wood, 5 hit points each. A piece is 3.6 s of chopping (the swing restart, a hit every
  15 ticks) and 5 s of hammering; the first quarters, with its trees 6 cells away, costs 590 builder-seconds, 14.7
  a piece: 39 % of it is the walk to the tree and back.
- A quarters breeds n^(1/3) / 11 a second with n inside (the peons it has bred and not let out count): .072 empty,
  .115 with two, .144 with four, .165 with six, .182 with eight. A peon more inside is worth .024 a second as the
  second, .015 as the third or fourth, .009 as the sixth.
- The Hard copies: armory at 120 to 222 s (the median 159), the first wave of ten 146 s and more after it (the
  fastest twentieth; the median 169, the least 110), so the first launch of a game comes at 280 to 311 s and a
  copy's first at 334 s (the median); its ten warriors come out together in the last half minute.

**The default's opening** (300 games, `mb5`, `mb6-vs16`): 20 units for a minute, 23 at 90 s, 31 at 150, 48 at 210,
78 at 270, 99 at 300, then 45 a minute to the cap at 8 minutes. The quarters stand at 50, 127, 168, 202, 268 and
281 s (medians), the armory at 233 s. Peon-seconds of the first five minutes (12,270 a game, `ot1log-vs16`):

| | share | |
|---|---|---|
| inside the quarters | 15.6 % | breeding |
| builders carrying a piece | 15.6 % | |
| shepherds | 14.9 % | 5.7 of 36 units out at 150-180 s, 11.5 of 44 at 180-210 s, 14.5 from then on |
| builders hammering | 11.4 % | 1,396 a game: seven buildings of 199 |
| builders walking to a tree | 8.9 % | |
| builders chopping | 8.2 % | |
| inside the armory | 6.5 % | |
| the strike on the nearest copy | 5.0 % | six peons for two minutes |
| gatherers | 8.4 % | |
| walking into a building | 2.7 % | |
| carrying a site, idle, other | 2.7 % | idle 0.6 % |

Nobody stands: until the armory is up every peon outside builds, twenty to a site and the rest on the next. What
the builders lose is the walk. Of their time 44 % is work (hammer and chop) and 56 % walking, where the first
quarters has 61 % work: the buildings after it cost 1,105, 851, 835, 747, 746 and 757 builder-seconds for its 590.

- **The sites are visited in the order they were planned.** The later quarters lie around the armory site, 15 cells
  from their nearest neighbour, and the builders go from one to the next in the order the planner found them:
  after the 53 cells from the first quarters to the first far one, 127 cells of walking where the shortest order
  through the same sites has 77 (44 openings; the armory keeping its turn). `opening_route` builds and places them
  in the shortest order (every order of the five is tried, on walking distances).
- **The walk from the first quarters to the armory's ground** is the map's: 53 cells on average, and it decides how
  fast an opening is more than anything else. By the armory's distance from the start (100 maps, `os0-vs16`):
  under 45 cells 117 units at minute 5, 45 to 65 cells 113, 65 to 90 cells 100, 90 and more (26 maps of 100) 75,
  with the second quarters at 106, 113, 133 and 201 s. Raising quarters by the start first does not undo it
  (`opening_home_quarters`, with `opening_home_m` for the far armories alone): the second quarters stands 21 s
  sooner, 51 s on the far maps, and by minute 8 there are no more units and fewer warriors, since everything bred
  at home walks out (two home quarters +3.6 units at minute 5 and -1.6 warriors at minute 8 on the 32 far maps;
  three and four -5 and -8 warriors and -12 and -17 iron by minute 10). The second session's `opening_near_far`
  found the same.
- **A rush alarm that is no rush.** `checkRush` (Gauntlet's, from one enemy) raises the alarm when six enemy
  warriors are out before our armory stands, and for four minutes the quarters then get three builders and keep
  two peons ("weapons before quarters"). Against sixteen copies six warriors are simply the first copy's first
  ten: the alarm goes off in 7 of 48 openings, the ones whose armory is not up by 255 to 285 s, and their last
  quarters is then built by three peons for two minutes while eight build each tower (s22025: 61 to 196 hit
  points from 260 to 400 s). Off against more than one enemy (`rush_response`).
- **Two peons inside while the opening builds.** `holdFor` keeps two in each quarters while a site is short of
  builders and no armory stands (hard-coded). A third and a fourth breed .015 a second each; as builders they add
  a five-hundredth of a quarters a second between them. A small model of the build phase said four to six, and so
  do the games (`hold_build`): six instead of two gives 9 units more at minute 4 and 7 to 9 through minute 7, the
  armory 8 s later, and 1.9, 4.8 and 2.3 warriors more at minutes 6, 8 and 10. Eight and ten give no more
  warriors; a hold that grows with the quarters standing (`hold_build_ramp`) gives nothing over six. With
  `hold_early` 6 as well (the hold between the armory and 240 s) 2.7, 5.2 and 3.9.
- **The shepherds leave when their copy's armory stands**, whatever the walk: recruited at 172 s (the median), at
  their spot at 336 s, 11 s after their copy's first launch (the median again: four in five are still walking when
  it launches, and 683 of 690 first waves go to the shepherd all the same, since he is the nearest unit of ours).
  `shepherd_lead_ticks` (Gauntlet's, never switched on here, its timing "a guess") sends a shepherd only in time to
  stand on his spot 15 s before his copy's armory time plus that lead. With 140 s (7000), from the timing above:
  300 shepherd-seconds fewer of 8,150 in ten minutes, the first recruits at 182 s, and 681 of 692 first waves
  still go to a shepherd (`ot2log-vs16`).

What did not help: `opening_floor` (the last quarters keep up to twenty builders after the armory stands and when
their trees are cut: +0.8 units at minute 5, a warrior fewer at minute 6), `opening_claim` (each planned site
claims its four trees, the next are scored on the rest: +0.4 units), the armory a quarters earlier (30 s sooner, 1.6
to 2.9 warriors fewer at minutes 6 to 10) or later (26 s later, the same warriors), a seventh quarters (3.3 warriors
fewer at minute 6, 3.7 more at minute 10). The hold after 240 s (`hold_mid`, 4) moves the army later, as the model
of that phase says it must (its total work is flat from 3 to 12): at 6 and 8, 2.4 and 4.5 warriors fewer at minute 6,
1.5 and 3.2 more at minute 8, 4.0 and 4.7 more at minute 10, the cap 45 s sooner.

**The speed of each step** (100 maps of 22001..22100 cut at minute 10; differences to the default, paired):

| | units at 5 / 7 min | warriors at 6 / 8 / 10 min | iron by 10 | sixth quarters | armory |
|---|---|---|---|---|---|
| `os0-vs16` the default | 100.0 / 190.8 | 23.3 / 54.8 / 89.3 | 146.6 | 319 s | 244 s |
| `os1` `opening_route` | +4.2 / +6.8 | +1.8 / +3.0 / +1.3 | +2.4 | -18 s | -8 s |
| `os6` and `shepherd_lead_ticks=7000` | +5.9 / +9.9 | +3.2 / +4.5 / +3.9 | +5.0 | -28 s | -11 s |
| `os10` and `rush_response=false` | +5.9 / +11.5 | +2.9 / +4.1 / +5.5 | +6.5 | -33 s | -11 s |
| `os15` and `hold_build=6` | +13.5 / +20.4 | +4.9 / +8.9 / +7.8 | +10.4 | -24 s | -2 s |
| `os18` and `hold_early=6` | +15.3 / +22.0 | +5.6 / +9.4 / +9.4 | +11.4 | -25 s | -5 s |
| `os22` and `hold_mid=6` | +20.5 / +36.2 | +3.3 / +10.9 / +13.4 | +14.2 | -25 s | -5 s |

The errors are 0.5 to 1.5 on every figure but the sixth quarters (4 to 5 s). `os18` has 24 %, 17 % and 10 % more
warriors than the default at minutes 6, 8 and 10.

**In whole games** (150 maps no choice had been made on, 32001..32150, N=16):

| | wins | against `ow0-vs16` | our warriors at 6 / 8 / 10 / 12 min | copies housed at 12 / 15 / 20 / 25 min |
|---|---|---|---|---|
| `ow0-vs16` the default | 105 | | 22.9 / 54.4 / 88.5 / 99.3 | 12.8 / 11.5 / 9.6 / 8.8 |
| `ow1` route, no rush alarm | 100 | +14 -19 | +2.3 / +2.7 / +5.0 / +5.1 | 0.0 / -0.1 / -0.1 / 0.0 |
| `ow2` and the shepherds' lead | 108 | +17 -14 | +3.3 / +2.8 / +5.0 / +2.1 | -0.1 / -0.2 / -0.2 / -0.3 |
| `ow3` and six inside (`hold_build`, `hold_early`) | 107 | +16 -14 | +6.1 / +8.4 / +9.1 / +6.0 | -0.3 / -0.3 / -0.2 / -0.4 |

The opening's gain is there in the whole games (every warrior figure of `ow3` and its copies housed from minute 12
on are beyond twice their error), and 150 maps cannot tell what it is worth in wins: one run's error is 5.5.

**Confirmed, and the default since.** On 32151..32300, used for nothing before, the whole of it (`ow5-vs16`: the
route, the lead, no alarm, six inside) wins 112 and a draw for the default's 106 and a draw (`ow4-vs16`, +18 -12),
and at N=17 on 33001..33150 it wins 92 for 84 (`ow7-vs17` against `ow6-vs17`, +23 -15). With the first block 311 of
450 for 295, +57 -41 (z 1.6), every block ahead; in each the warriors are 6 to 10 more at minutes 6 to 10 and the
copies housed 0.1 to 0.4 fewer from minute 8 to 20 (beyond twice the error), and at N=17 the copies have 18 fewer
warriors at minute 20 and 35 fewer at minute 30. Frozen as `@f15`. With no params the code plays `ow5-vs16`'s games
(`chk16a-vs16`, 12 of 12), and with the five settings put back `ow4-vs16`'s (`chk16b-vs16`, 8 of 8).

At N=18 on 34001..34150 `@f15` wins 46 of 150 for `@f14`'s 43 (`oz1-vs18` against `oz0-vs18`, +17 -14), with 6 to
10 more warriors at minutes 6 to 10, 0.2 to 0.4 fewer copies housed from minute 8 on and 40 fewer warriors of the
copies at minutes 25 and 30. Over the four blocks 357 of 600 for 338, +74 -55 (z 1.7).

What the corrected harvest time did (`harvest_ticks`, 500 in the cost model where the trace measures 180): 0.8 and
1.6 more warriors at minutes 6 and 8, 2.9 fewer at minute 10 and 2.6 less iron (`os30-vs16`): left as it is.

**After `@f15`: quarters along the way, and the slowest openings one by one.**

- **The chain** (`opening_chain`). The walk from the first quarters to the armory's ground is made before the
  second building; with the quarters that come before the armory at even steps along that way, it is made a step
  before each. Two along the way wherever the armory is 60 m and more beyond the first quarters (nine openings in
  ten): the second quarters stands 16 s sooner, 5 units more at minute 5 and 6 at minute 7, and 1.9, 3.1 and 2.3
  more warriors at minutes 6, 8 and 10 (`os38-vs16` against `os18-vs16`; errors 0.5 to 1.2), with the iron and the
  armory where they were: 32 %, 23 % and 13 % more warriors than `@f14`'s opening. From 100 m and 150 m less of
  the same (+1.3 / +2.0 / +1.8 and +0.8 / +1.1 / +1.0); three along the way no better than two (the last warriors
  and the iron slip), one half as good. On the 32 far armories of 100 two give 10 units more at minute 5 and 2.3,
  3.4 and 3.1 warriors, where quarters by the start cost warriors. The route's order makes no difference to it
  (`opening_route_sum`, the sum of the walks to each site instead of the walk in all: the same to half a unit, with
  the chain and without, `os40`, `os41-vs16`).
- **A walk ahead that took the first quarters' builders** (`opening_prewalk_first`). `ot3log-vs16` s22001, the
  slowest opening of 48, armory 351 m out: the first quarters, placed at 11 s, stands at 160 s. A peon carrying its
  site that reaches the spot while another still moves on it drops the site and stands idle until the last one
  puts it down (the engine's placing test); `opening_prewalk` (peons left over walk ahead to an armory site 300 m
  and more out) took those for left over, and 9 of the 13 builders walked off at 3 to 12 s, to come back at 84 s.
  With no walk ahead before the first quarters stands one opening of 100 changes, that one: 88 units at minute 5
  for 44, 47 warriors at minute 8 for 5 (`os43-vs16`).
- **A chain through bare ground** (`opening_chain_trees`). `ot5log-vs16` (the sixteen slowest openings, logged)
  s22078, a start with no tree within 16 cells of the first sites: the chain's first quarters is built by six
  peons (`builders_floor`) fetching wood 15 to 19 cells away and stands at 498 s. A quarters of the chain now needs
  three trees within 7 cells or is planned by the armory as before (`os44-vs16`: the same openings elsewhere).
- **The first quarters' pull towards the armory** (`opening_toward`, 3 game ticks a metre where the walk is 10):
  s22054, few trees by the start, raises it 91 m out and 18 cells farther from the armory than the start is. At 6
  nothing to speak of (+0.9 units at minute 6, +1.3 warriors at minute 10, inside the error), at 10 worse (-2.4
  units at minute 5, -1.3 warriors at minute 6): left at 3.

What is left of the slow openings after these is the ground itself: starts with two trees or none in reach (the
first quarters at 59 to 98 s for the others' 38 to 50), armories 300 m out, and a neighbour next door.

**The chain and the later army in whole games: nothing in the wins.** `hold_mid` 6 (six kept inside from 240 s too)
had won 52 of 150 at N=18 for `@f15`'s 46 (`oz2-vs18`, +18 -12). Both, each alone and together, on 150 maps never
used at N=16 (35001..35150) and at N=17 (33151..33300), against `@f15` (`oc0-vs16` 112 wins, `oc2-vs17` 83):

| | N=16 wins | | N=17 wins | | our warriors at 6 / 8 / 10 min, N=16 | N=17 |
|---|---|---|---|---|---|---|
| the chain (`oc1`, `oc3`) | 111 | +8 -9 | 83 | +13 -13 | +1.3 / +4.4 / +2.2 | +1.0 / +1.7 / +0.6 |
| `hold_mid=6` (`oc4`, `oc5`) | 112 | +17 -17 | 81, 1 draw | +18 -20 | -2.2 / +3.5 / +2.3 | -2.4 / +2.4 / +3.8 |
| both (`oc6`, `oc7`) | 107 | +12 -17 | 79 | +15 -19 | -1.0 / +6.0 / +4.6 | -1.0 / +4.3 / +2.8 |

The armies move as the openings said and the campaign does not: the copies housed are the same to a tenth at every
minute from 8 to 20 in all six runs, where `@f15`'s 6 to 10 warriors had taken 0.2 to 0.4 off them. `hold_mid` is a
trade and it buys nothing over three blocks (+53 -49): left at 4. The chain is walking taken out with no harm
shown (+21 -22).

The same through the model of the state at minute 20 (`lab/fable/proxy.py`, fitted on 877 games of the defaults at
N=16 and on 450 at N=17), whose error is under half the win count's: `@f15` against `@f14` +4.1 +- 1.9 and +1.9 +-
2.0 points on its two N=16 blocks and +6.6 +- 2.5 at N=17; the chain +0.4 +- 1.7 and +1.6 +- 1.6; `hold_mid` +0.8
+- 1.9 and +2.1 +- 1.9; both together +1.2 +- 1.8 and -0.7 +- 2.1.

**The chain confirmed as level, and the default since.** With its two repairs (`opening_chain_trees=3`,
`opening_prewalk_first`) on two more blocks never used: 126 and a draw of 150 for 124 and a draw at N=16
(35151..35300, `od1-vs16` against `od0-vs16`, +9 -7) and 92 for 92 at N=17 (33301..33450, `od3-vs17` against
`od2-vs17`, +15 -15). Over its four blocks 412 of 600 for 411, +45 -44: walking taken out that shows no harm,
which by the user's rule of 2026-11-19 is adopted. Frozen as `@f16` (at commit d1eca39b). With no params the code plays
`od1-vs16`'s games (`chk17a-vs16`, 12 of 12), and with the chain and the two repairs off `od0-vs16`'s
(`chk17b-vs16`, 8 of 8).

### What a quarters should keep inside: the payback rule, a share of the missing units, and seeding (2026-11-22)

The user's questions after the opening round: can the six of `hold_build` be proved optimal, would a value that
changes over the game do better, and is it worth seeding a new quarters with some of its builders.

**The law, and what a peon inside is worth.** A quarters with n inside breeds r(n) = max(n, 1/2)^(1/3) / 11 peons a
second (the newborn counts until it is let out, which takes half a second). The k-th peon inside adds r(k) - r(k-1)
a second, so it has bred one more peon after the inverse of that:

| k-th peon inside | 1 | 2 | 3 | 4 | 5 | 6 | 8 | 10 | 12 | 16 | 20 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| has bred one more after | 53 s | 42 s | 60 s | 76 s | 90 s | 103 s | 126 s | 148 s | 168 s | 205 s | 239 s |

Further on it is 33 k^(2/3) s. The first is worth less than the second because the engine breeds an empty quarters
as if it held half a peon: one peon alone is never the right number, it is none or two and more.

**The growth to the cap has an exact answer.** With Q quarters standing, P units and a cap of C, let every quarters
keep n and the other P - Qn work, and suppose a second of work is worth the same whenever it is done. A peon kept
inside for a second costs a second of work and breeds r'(n) peons; a peon bred at time t is of use until the cap is
reached at T (from then on both courses of the game have C units), so keeping the peon pays while r'(n) (T - t) >=
1: n = ((T - t) / 33)^(3/2), 27 with five minutes to go, 13 with three, 7 with two, 2.4 with one and none in the
last 42 s. Following that rule back from the cap, dP/dt = Q r(n) gives C - P = Q (T - t)^(3/2) / 94.8, and dividing
the two: Q n = (C - P) / 2. **The quarters keep between them half the units still missing to the cap**, whatever
the number of quarters and whatever the constant of the law. (For a law r = c n^a the share is a / (1 - a); a third
gives a half.) Checked by playing the growth out from 80 units with six quarters (peon-seconds outside by 600 s):

| kept inside | cap after | work by 600 s | outside after one / two minutes |
|---|---|---|---|
| the schedule till now: 4 each, 8 above 175 units | 179 s | 128,043 | 108 of 132 / 138 of 186 |
| a fifth of the missing units | 245 s | +0.5 % | 112 of 135 / 169 of 182 |
| a quarter | 230 s | +1.0 % | 111 of 139 / 173 of 189 |
| a third | 211 s | +1.5 % | 109 of 144 / 180 of 197 |
| a half | 186 s | +1.8 %, the most | 104 of 153 / 191 of 210 |
| three quarters | 166 s | +1.5 % | 93 of 160 / 202 of 222 |

So the proof is of a shape more than of a number: the optimum is flat (1.8 % of the work between the old schedule
and the best), and what the shape says is that the hold should fall as the cap comes near, where the old schedule
raised it to eight above 175 units, 48 peons inside for the last 75. And the premise is a choice: weigh work now
above work later and the share drops under a half: played out with a second of work worth in proportion to the
time left on a fixed clock, the best share is .35, with the square root of it .40. After the cap is first reached
the premise is gone, since a peon bred then takes the place of one lost and is of use for as long as it lives: the
rule is for the opening.

**While the opening builds there is no such number, only a plateau.** A builder's second is not worth "a second of
work": it brings every building still to come forward. To first order it is worth N r(n) / B peons, with N quarters
still to build and B builders, which makes the hold B / 3N: one at the first quarters, five to ten at the last.
But that leaves out that what is bred builds too, that a quarters fills itself slowly from empty, and that a peon
let out at the first quarters walks half a minute to the others. `lab/fable/breed.py` plays an opening with all of
that (the engine's law, the buildings one after the other with twenty builders each, the walks, the strikers back
and the shepherds gone, a weapon line that makes .04 x the square root of its peons a second, as measured). Fitted
to the default with two inside and to what six did to it, it gives the units of the eleven hold settings measured
on 100 maps to one or two (holds of 3, 4, 6, 8, 10: +3, +7, +9, +11, +14 units at minute 4 for +3.3, +5.4, +8.8,
+11.1, +12.8 measured; `hold_mid` 6 and 8: +25 and +34 at minute 7 for +24.7 and +29.7), the armory's delay half
as much again as measured, and the warriors' direction. Its weapons by minute 6 / 8 / 10: 27.4 / 70.9 / 122.2 with
two, 28.4 / 73.2 / 124.1 with four, 27.7 / 73.2 / 124.0 with six, 26.7 / 72.8 / 123.7 with eight: four to six is
the top of a hill a warrior high, as the games had it. Holds that rise with the quarters standing (2/2/4/6,
2/4/6/8, 4/6/8/10, 3/6/10/14) are within a warrior of the flat six in the model, and in the games 3/6/10/14 has 1.4
warriors fewer at minute 6 and the rest the same (`ob4-vs16`; `hold_build_steps`).

**Seeding.** A quarters that keeps six and starts empty takes 55 s to breed them, and lets nobody out meanwhile.
With s of its builders sent in the moment it stands it breeds at r(s) from the first second:

| seeded with | full after | let out by second 55 | against no seed | builders short for the first |
|---|---|---|---|---|
| 1 | 41 s | 2.3 | +1.3 peons | 47 s |
| 2 | 30 s | 4.1 | +2.1 | 42 s |
| 3 | 21 s | 5.5 | +2.5 | 39 s |
| 4 | 13 s | 6.8 | +2.8 | 38 s |
| 6 | at once | 9.0 | +3.0 | 36 s |

Two get seven tenths of what six get, for a third of the builders taken off the next site. `seed_quarters_ticks`
did this only once the armory stood (its record says "before the first armory, idle peons already fill quarters
below their hold"; they did not, the sites took them first). `seed_build` does it while the opening builds: the
free peons nearest the door, those without wood first, go in within five seconds (`ot6log-vs16` s22003: two inside
at 48 s where the unseeded one has one at 64 s; six at 84 s for 112 s).

**Measured** (100 maps of 22001..22100 cut at minute 10, differences to `@f16`, `ob0-vs16`: 120.6 units at minute
5; 30.7, 67.4 and 101.8 warriors at minutes 6, 8 and 10; armory at 236 s; errors 0.3 to 1.3):

| | units at 5 / 7 min | warriors at 6 / 8 / 10 min | armory |
|---|---|---|---|
| `seed_build` 2 (`ob1`) | +5.6 / +6.1 | +1.9 / +3.0 / +0.9 | 3 s later |
| `seed_build` 3 (`oe1`) | +5.6 / +6.4 | +1.9 / +3.1 / +3.0 | 7 s later |
| `seed_build` 4 (`ob2`) | +5.7 / +6.5 | +2.1 / +4.2 / +2.2 | 7 s later |
| `seed_build` 6 (`ob3`) | +5.9 / +6.7 | +1.7 / +3.4 / +1.1 | 8 s later |
| `hold_late` 4, 2 for 8 (`ob8`, `ob9`) | 0 / -8.5, -17.8 | +0.1, +0.2 / +2.7, +3.3 / +0.4 | the same |
| `hold_share` a third, no seed (`oh5`) | +10.7 / -3.3 | -1.6 / +8.8 / +3.5 | |
| seed 2 and `hold_share` a fifth (`oh6`) | +9.7 / -15.1 | +2.6 / +9.1 / +3.2 | 3 s later |
| seed 2 and a quarter (`oh1`) | +12.9 / -8.0 | +2.0 / +10.7 / +5.2 | 3 s later |
| seed 2 and a third (`oh2`) | +16.2 / -0.2 | +0.1 / +11.3 / +6.0 | 3 s later |
| seed 2 and two fifths (`oh3`) | +18.6 / +5.9 | -0.8 / +12.8 / +5.1 | 3 s later |
| seed 2 and a half, the optimum of the proof (`oh4`) | +21.2 / +11.8 | -2.8 / +11.8 / +6.4 | 3 s later |

The share is the dial the proof says it is: more inside early costs warriors at minute 6 and gives them back with
interest at minutes 8 and 10, and the proof's own half, which counts minute 6 for no more than minute 10, pays 2.8
warriors at minute 6. A quarter gives at all three. The first form of the rule (`hold_payback`: the k-th peon is
kept while its payback time is under the seconds left to the cap at the pace of the moment) gave the same figures
(`of1-vs16`: +0.1 / +12.1 / +5.3 at strength 1) but flips near the cap, since an empty quarters breeds slowly and
says keep seven and a full one breeds fast and says keep none; the share needs only the unit count.

**In whole games the army is there and the wins are not.** Seeding two and a quarter of the missing units
(`seed_build=2`, `hold_share=0.25`) against `@f16`, on five blocks of 150 maps never used:

| | wins | | our warriors at 6 / 8 / 10 / 12 min | model at minute 20 |
|---|---|---|---|---|
| N=16, 35301..35450 (`og1-vs16` against `og0`) | 115 for 106, 2 draws | +25 -16 | +2.9 / +10.7 / +5.8 / +2.6 | +3.5 +- 2.1 |
| N=17, 33451..33600 (`og3-vs17` against `og2`) | 90 for 87, a draw each | +22 -19 | +2.4 / +12.0 / +4.8 / +2.5 | -1.1 +- 2.3 |
| N=16, 35451..35600 (`oj1-vs16` against `oj0`) | 107 for 114, 1 draw | +7 -14 | +2.4 / +11.6 / +7.0 / +3.8 | -1.4 +- 2.1 |
| N=17, 33601..33750 (`oj3-vs17` against `oj2`) | 89 for 92 | +23 -26 | +3.0 / +10.7 / +6.1 / +3.1 | +2.3 +- 2.5 |
| N=18, 34151..34300 (`oj5-vs18` against `oj4`) | 50, 1 draw for 55 | +20 -25 | +2.6 / +10.0 / +5.9 / +0.6 | -2.0 +- 2.4 |

451 of 750 for 454, +97 -100; the first two blocks, played first, were +12 and the three that were to confirm them
-15. Every block has ten to twelve more warriors at minute 8 and none more at minute 15. The first enemy building
falls at the same second with it and without (437 to 448 s), 0.2 to 0.35 more enemy buildings are down by minute 12
of 8.6, and the copies housed are the same to a tenth at every minute. The warriors this adds come at minutes 7 to
10, after the first attack has left: they are reinforcements, and the campaign of minutes 8 to 15 is not short of
those (the reinforcements round; `@f15`'s six more warriors were there at minute 6). So the answer to the user's
three questions is yes each time (there is a proof, a hold that changes with the game does what the proof says,
and seeding pays what its arithmetic says), and none of it is worth a game at N=16 to 18.

Made the default all the same, as waste taken out with no harm shown (the user's rule of 2026-11-19), and frozen
as `@f17` (at commit b592bf59; with no params the code plays `oj1-vs16`'s games, `chk18a-vs16` 12 of 12, and with the two
off `oj0-vs16`'s, `chk18b-vs16` 8 of 8). After the cap is first reached nothing changes: the quarters then hold 8 to 20 units between them
through the rest of a game (`mt3log-vs16`, 11 on average, the chieftain's trainer among them), which is not worth
a rule of its own.

## Reproduce

```bash
./aisim.sh build
./aisim.sh freeze f2 fable                     # or play `fable` itself: it has no params to set
EXAM=1 ./lab/fable/dev.sh exam-f2-vs14 "@f2 vs hard*14" 36001..36200
EXAM=1 ./lab/fable/dev.sh exam-g16-vs14 "@g16 vs hard*14" 36001..36200   # Gauntlet: freeze g16 from its branch first
python lab/fable/stats.py --pair exam-g16-vs14 exam-f2-vs14
./aisim.sh freeze f3 fable                     # the fourth session's default, at commit afccc03c
EXAM=1 ./lab/fable/dev.sh exam-f3-vs15 "@f3 vs hard*15" 36501..36700
EXAM=1 ./lab/fable/dev.sh exam-f2-vs15-c "@f2 vs hard*15" 36501..36700
python lab/fable/stats.py --pair exam-f2-vs15-c exam-f3-vs15
./aisim.sh freeze f4 fable                     # the any-seat default, at commit bbe0e145
python lab/fable/anyseat.py --name f4 --n 1..16                                        # maps 22001..22100
python lab/fable/anyseat.py --name f4h --spec "@f4" --seeds 23001..23100 --n 1..16     # the held-out maps
python lab/fable/anyseat.py --name f4 --n 12..16 --report --detail                     # what was played, by seat
./aisim.sh freeze f5 fable                     # the builders' release, at commit 77fde350
./aisim.sh freeze f6 fable                     # the tree on the way and idle builders, at commit aee7e81e
./aisim.sh freeze f7 fable                     # the fifteen switches, at commit 00461225
python lab/fable/anyseat.py --name f6 --spec "@f6" --n 1..16                           # likewise f5, and f5h, f6h on 23001..23100
PB="@f6:stock_balance=true,sat_staff=true,expand_under_threat=true,tower_wood_drop=true,tower_wood_ticks=30000,tower_site_fallback=true,unstick_builders_ticks=1500,rush_hold_armory=true,rush_opening_only=true,walk_select=300,capped_min_strength=40,seed_quarters_ticks=1500,site_towers_first=true,stall_peons=true,freeze_unfreeze=true"
python lab/fable/anyseat.py --name pb6 --spec "$PB" --n 8..16                          # plays what @f7 plays; pb6h on 23001..23100
python lab/fable/seat0.py f6 pb6 --n 8..16                                             # two ladders paired from the first start
./lab/fable/dev.sh f7n-vs16 "@f7 vs hard*16" 25001..25300                              # likewise f4n-vs16 with @f4
./aisim.sh freeze f8 fable                     # the second shepherd repaired, at commit 65e5d5a4
./lab/fable/dev.sh f8m-vs16 "@f8 vs hard*16" 26001..26300                              # likewise f7m-vs16 with @f7
./aisim.sh freeze f9 fable                     # the planned way for shepherds, at commit c64eb747
./aisim.sh freeze f10 fable                    # the same with the goal's hot blocks free, at commit 00f7ed2b
./aisim.sh freeze f11 fable                    # the micro round's three defaults, at commit abb99be6
./aisim.sh freeze f12 fable                    # and flight_keep, chief_bystander, at commit 5e14c475
./aisim.sh freeze f13 fable                    # and the home army's post, at commit 49939d96
./aisim.sh freeze f14 fable                    # and reinforce_march, retreat_regroup=8, at commit 1d944e36
./lab/fable/dev.sh tw0log-vs16 "fable vs hard*16" 22001..22012 --logs   # the tower round's logged runs: st*log (storm), bt*log (bait)
python lab/fable/towers.py tw0log-vs16 st1log-vs16             # contacts with manned enemy towers, from TowerTrace
./lab/fable/dev.sh sa2-vs16 "fable:storm=17 vs hard*16" 25151..25300    # sb2..si2, ba2..bc2: the specs are in each run.json
./lab/fable/dev.sh cf2log-vs16 "fable vs hard*16" 22001..22012 --logs   # the chieftain round's logged runs: cb1log, cq1log, cq2log, cs1log, cd1log, sf1log
python lab/fable/chief.py cf2log-vs16 cb1log-vs16              # what the chieftain does with his blast, from ChiefTrace
./lab/fable/dev.sh cz2-vs16 "fable vs hard*16" 25151..25300    # ml2-vs16's games with the blast counters; pb2, sj2, sk2, sr2, pp2, ha2, sv2: the specs are in each run.json
./lab/fable/dev.sh pbm-vs16 "fable:shred_point_blank=true vs hard*16" 26001..26300
python lab/fable/rates.py --re "shred_(blast$|taken|killed)|chief_" cz2-vs16 pb2-vs16
./lab/fable/dev.sh ha4log-vs16 "@f12 vs hard*16" 22001..22012 --logs   # the home army round's logged runs (made as fable, then @f12): ha2log..ha4log; the post: hp1log..hp3log, hs1log, hc1log, hu1log; sp1log
python lab/fable/home.py ha4log-vs16 hu1log-vs16               # what the home army does and where it dies, from HomeTrace
./lab/fable/dev.sh hb3-vs16 "@f12 vs hard*16" 28001..28150     # hp3, hq3, hr3, hu3, hv3, hw3, hx3: the specs are in each run.json
./lab/fable/dev.sh hb4-vs16 "@f12 vs hard*16" 28151..28450
./lab/fable/dev.sh hu4-vs16 "@f13 vs hard*16" 28151..28450     # played as fable:hold_multi=true,hold_ratio=0.3,hold_stick=12,hold_commit=20
./lab/fable/dev.sh hu5-vs17 "@f13 vs hard*17" 27001..27300
./lab/fable/dev.sh f13-vs18 "@f13 vs hard*18" 29001..29300
./lab/fable/dev.sh rt2log-vs16 "@f13 vs hard*16" 22001..22012 --logs   # the reinforcements round's logged runs (made as fable): rg1log, rm1log, ro1log, rq1log; the specs are in each run.json
python lab/fable/reinforce.py rt2log-vs16 ro1log-vs16          # what becomes of the reinforcements, from ReinforceTrace
./lab/fable/dev.sh rb3-vs16 "@f13 vs hard*16" 30001..30150     # rg3, rm3, ro3, rq3, rs3: the specs are in each run.json
./lab/fable/dev.sh rb4-vs16 "@f13 vs hard*16" 30151..30300
./lab/fable/dev.sh ru4-vs16 "@f14 vs hard*16" 30151..30300     # played as fable:reinforce_march=true,retreat_regroup=8
./lab/fable/dev.sh ru5-vs17 "@f14 vs hard*17" 27001..27300
./lab/fable/dev.sh mt3log-vs16 "@f14 vs hard*16" 22001..22012 --logs   # the march round's logged runs (made as fable): mp1log, me1log, mh1log; the specs are in each run.json
python lab/fable/march.py mt3log-vs16 me1log-vs16              # what the attacking army does with its time, from MarchTrace
python lab/fable/openwin.py mb5-vs16 rb3-vs16 rb4-vs16         # the wins by the opening's milestones
./lab/fable/dev.sh mb5-vs16 "@f14 vs hard*16" 31001..31150     # mp5, me5, mh5: the specs are in each run.json
./lab/fable/dev.sh mb6-vs16 "@f14 vs hard*16" 31151..31300     # mp6: fable:march_past=20
./aisim.sh freeze f15 fable                    # and the opening round's five settings, at commit 2c1a1319
./lab/fable/dev.sh ot1log-vs16 "@f14 vs hard*16" 22001..22048 --logs --minutes 10   # the opening traced (made as fable); ot2log, ot3log: the specs are in each run.json
python lab/fable/opentrace.py ot1log-vs16      # where the opening's peon time goes, and every opening building
./lab/fable/dev.sh os0-vs16 "@f14 vs hard*16" 22001..22100 --minutes 10             # the speed runs os0..os31 (made as fable): the specs are in each run.json
python lab/fable/openspeed.py os0-vs16 os1-vs16 os10-vs16 os18-vs16                 # an opening's speed, run against run
./lab/fable/dev.sh ow0-vs16 "@f14 vs hard*16" 32001..32150     # ow1, ow2, ow3: the specs are in each run.json
./lab/fable/dev.sh ow4-vs16 "@f14 vs hard*16" 32151..32300     # ow5: @f15
./lab/fable/dev.sh ow6-vs17 "@f14 vs hard*17" 33001..33150     # ow7: @f15
./lab/fable/dev.sh oz0-vs18 "@f14 vs hard*18" 34001..34150     # oz1: @f15; oz2: fable:hold_mid=6 (on @f15's defaults)
./lab/fable/dev.sh oc0-vs16 "@f15 vs hard*16" 35001..35150     # oc1, oc4, oc6: the chain, hold_mid=6, both (made as fable): the specs are in each run.json
./lab/fable/dev.sh oc2-vs17 "@f15 vs hard*17" 33151..33300     # oc3, oc5, oc7: the same at N=17
./lab/fable/dev.sh ot5log-vs16 "fable:opening_chain=2,opening_chain_m=60,opening_prewalk_first=true vs hard*16" 22078,22054,22081,22094,22013,22061,22023,22046,22040,22065,22011,22057,22051,22024,22070,22093 --logs --minutes 10
./lab/fable/dev.sh od0-vs16 "@f15 vs hard*16" 35151..35300     # od1: @f16
./lab/fable/dev.sh od2-vs17 "@f15 vs hard*17" 33301..33450     # od3: @f16
./aisim.sh freeze f17 fable                    # and seed_build=2, hold_share=0.25, at commit b592bf59
python lab/fable/breed.py check                # the model of the opening's breeding against the measured openings; flat, seed, search
./lab/fable/dev.sh ob0-vs16 "@f16 vs hard*16" 22001..22100 --minutes 10   # the speed runs ob1..ob9, oe1..oe9, of1..of8, oh1..oh6, oi1..oi3 (made as fable): the specs are in each run.json
./lab/fable/dev.sh og0-vs16 "@f16 vs hard*16" 35301..35450     # og1: @f17; og2, og3-vs17 on 33451..33600
./lab/fable/dev.sh oj0-vs16 "@f16 vs hard*16" 35451..35600     # oj1: @f17; oj2, oj3-vs17 on 33601..33750; oj4, oj5-vs18 on 34151..34300
./aisim.sh freeze f16 fable                    # and the chain, its trees and no walk ahead before the first quarters, at commit d1eca39b
./lab/fable/dev.sh f12m-vs16 "@f12 vs hard*16" 26001..26300  # f12n-vs17: "@f12 vs hard*17" 27001..27300
./lab/fable/dev.sh mbm-vs16 "@f11 vs hard*16" 26001..26300    # played as fable:bystander=0,stuck_still_ticks=1000,gather_parked=true
python lab/fable/rates.py rn5m-vs16 mbm-vs16   # the counters per hour of game; evades.py, fighttime.py on runs with --logs
./lab/fable/dev.sh rtm-vs16 "@f8:... vs hard*16" 26001..26300   # on d2686e1c: fable:shepherd_route=true; rte-vs16 on 25001..25300
./lab/fable/dev.sh hplog-vs15 "@f7:shepherd_home_pair=120 vs hard*15" 22001..22008 --logs   # the pair as written
python lab/fable/shepherds.py hplog-vs15                                               # what a shepherd's life buys
./lab/fable/dev.sh hpsn-vs16 "@f7:... vs hard*16" 25001..25300   # on 094fafa7: fable:shepherd_home_pair=120,shepherd_home_spare=true
python lab/fable/waylaid.py --maps as-pb6-vs15-s0                                      # builders killed by a copy's start
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
- Seats (2026-11-19, "Any seat" above): the far iron behind a copy (19 of 485 seat-0 games at N=12-16 win 5 where
  their N makes 11) wants a way past the copy, not a way round the walk; a start with no iron wants an armory by the
  home rock and a guarded second base. A copy next door has no answer found.
- Where our units die (2026-11-20, the 200 first-start games of `pb6` and `pb6h` at N=15, counters a game; won 135
  games of 84 min / lost 65 of 54 min). Units inside razed buildings are a mark of the lost game, not a leak of its
  own: 17 a game in the won games, 160 in the lost. What does not depend on the result: shepherds, 147 lost of 158
  recruited (131 of them while fleeing; 70 reach their spot, and 205 of a game's 420 waves go to a shepherd, 20 to
  our base), and gatherers that step out of an enemy's way and die all the same (93 of 2,340 steps). Peons walking
  between our own buildings: 59 in the won games, 172 in the lost.
- The shepherds (2026-11-20): "The shepherds: what a life buys, and the second shepherd repaired" and "A way for
  the shepherds" above. Still open: the waves at our base did not fall with the way (24-26 a game at N=16); three
  quarters of them leave a group parked near us while its shepherd is 36-112 cells off or has no spot. And the
  way's plans that find nothing (771-1,238 a game), and its cost.
- After the micro round (2026-11-20, "The micro round" above; an hour of game at N=16 with `@f11`): 1,100 units
  lost for 2,660 killed. Open, by size: (1) the towers: 126 of the attack's 400 deaths a game fall within 17 cells
  of an enemy tower, 80 of them with three enemy warriors or fewer about (the breach and raze drills did not change
  it: the tower throws 15.9 cells and the army arrives as a column); (2) the chieftain: 11 blasts an hour of 22
  warriors each where the charge allows 51; for every blast he walks 55 s charged and stands 86 s charged with no
  blob in range, while a third of those moments have eight and more awake warriors in range, most of them waves
  after a shepherd that cannot see him and walk as fast as he does: a shepherd that leads its wave to him would be
  the kill cell the second session asked for; (3) the home army: 218 deaths a game, 134 of them at odds of two to
  one and worse, 11 to 50 cells from our nearest building; (4) peons on their way into a building, 110 an hour,
  the largest loss of peons left after the shepherds' 180.
- After the tower round (2026-11-20, "The towers" above): the garrisons take 104 warriors a game and nothing
  found takes them back without costing more. Open: (1) a bait or a corner decoy that is in place before the army,
  from a crew that waits at the next copy instead of racing the army to this one, and without a home tower's slot
  (a slot freed for the minute it is needed); (2) the stun, 10 to 30 s for every garrison within 18 cells, if the
  chieftain's place is ever reopened; (3) what the bait's 22 games are made of: not the slots alone (five or
  seven quarters for six, a tower more or fewer, are 105 and 104 for 110), so the standing squad or the sites.
- After the chieftain round (2026-11-20, "The chieftain's blast, traced" above): the mission makes 12 of the 33
  casts his time allows and the repairs reach 13 to 14 with nothing in the wins, so (2) of the micro round's list
  is smaller than it read there. Open: `shred_point_blank` is free by its counters (an eighth more blasts, the same
  chieftains lost) and 9 wins under the default over 450 maps on which the default's options were chosen; a pair
  on maps of their own would settle it. What would change his size is a blob brought to him whole, a wave's
  worth at one known cell with nothing awake about, and the shepherds lead the waves the other way.
- After the home army round (2026-11-20, "The home army, traced" above): 94 home warriors lost an hour of game
  for 146. Open: (1) the fall back, 21 deaths an hour at one for one, the warriors waiting at the armory for the
  enemy; (2) the warriors that leave the armory one by one into a base with four enemies for each of ours, 13
  deaths an hour for 9 kills: held in until they make a clump or the towers have thinned the wave; (3) the
  reinforcements, 83 lost an hour on their way, two thirds of them alone or in small company: the same lens says
  of them what it said of the home army, and `reinforce_route`, which walked them round the enemy, did not win.
  N=17 stands at 54.0 % [48.3, 59.6] and N=18 at 33.7 %: the next N needs about 16 points.
- After the reinforcements round (2026-11-21, "The reinforcements, traced" above): the reinforcements' own fights
  are repaired (1.4 for one) and it is nothing in the wins, as the routed way was. Open: (1) the march: two thirds
  of the attack army's 198,000 warrior-seconds an hour are a walk between targets with nothing near, at seven
  tenths of a warrior's pace, and nobody has looked at which legs they are, how often the target changes under way
  or what holds the slow quarter up; (2) the meetings against twice a group's strength and more, a wave on its
  way as a rule (32 of the 85 deaths on the way for 34 kills), seen from 30 cells off and walked into; (3) the size
  of a group: 12 wait 9,400 warrior-seconds an hour at the armory door, and a group that fights as one body may
  not need to be 12 (`reinforce_min=6` was 37 wins for 40 at N=15 without the march).
- After the march round (2026-11-21, "The march, traced" above): (1) of the last bullet is closed. The walk is
  the distance between copies, and the army's stops for peons, for a warrior or two and for a group it closes on
  cost a third of a copy at minute 20 and no game. Two rounds in a row have made the army's and the reinforcements'
  time better spent by a tenth with nothing in the wins: at N=16 the games are not lost there. Where they are: (a)
  the slowest fifth of the openings (armory 114 cells from the start, the sixth quarters at 438 s) wins 40 % for
  the others' 79 % and is 53 of 128 losses; the second session tried three ways to the far iron and none held, and
  the any-seat measure sets those starts aside; (b) in the games lost the copies' warriors keep growing after
  minute 20 (545 to 589 by minute 25) while ours fall to 52, and in the games won ours fall too, from 124 at minute
  16 to 72 at minute 25: the army is not rebuilt as fast as it is spent, at 600 weapons an hour from a pool of 60
  peons.
- After the opening round (2026-11-22, "The opening, traced" above): (a) of the last bullet is half answered. The
  build phase is now near what the layout allows: a building after the first still costs 750 to 970
  builder-seconds for its 590, which is twenty builders walking a 15-cell leg at every change of site and the 53
  cells from the first quarters to the armory's ground, which the chain (`@f16`) now breaks into steps. What the
  round says about the measure: an opening is read to half a unit in ten-minute games, a whole game's wins to 5 of
  150, and between them the model of the state at minute 20 to 2 points; 6 to 10 more warriors through minute 10
  were worth about 3 points at N=16 and 6 at N=17, 2 to 4 more were worth nothing that 600 games show. Open: (1)
  the far armories, 90 cells and more from the start on a quarter of the maps, still open slowly (the chain gives
  them 10 units at minute 5 of the 40 they lack), with the starts that have no trees in reach and the neighbours
  next door; (2) the shepherds are 12 % of all peon time before minute 5 and 23 units of 240 at minute 8, the price
  of sixteen copies, and four in five are still walking when their copy first launches; (3) sixteen peons build
  towers from minute 5.5 on and six quarters hold 24 to 48: what the opening spends that is not army is settled
  by earlier screens, each read on 100 games.
- After the breeding questions (2026-11-22, "What a quarters should keep inside" above): the opening now has 32 %,
  45 % and 20 % more warriors at minutes 6, 8 and 10 than `@f14`'s, and of that only what was there by minute 6
  moved the campaign. Ten more warriors at minute 8 leave the first enemy building falling at the same second and
  the copies housed where they were. What the opening still has to give is therefore before the first attack
  leaves, at about 5:30: the armory's own time (233 to 243 s whatever was tried here), the first weapons, and how
  large that attack is when it goes.
- Builders killed on their way past a copy (`lab/fable/waylaid.py`, no logs needed: our units lost before 360 s
  within 30 cells of a copy's start and away from ours). Of `@f7`'s 1,800 first-start games at N=8-16, 49 lose 6
  and more that way (30 won) and 6 lose 12 and more (1 won); 7 are over within 16 minutes (22095 at N=11 and 12,
  where the walk to an armory 511 m out passes the struck copy and then a second one at 15 cells, 22049, 22059,
  23037, 22008, and 23047 with a copy next door). Under half a game in a hundred from the first start; on 22095
  at N=15, lost from all 16 starts, 6 starts lose 6 and more that way and the other 10 lose without it. A walk round
  the copy's 30 m would have to carry every peon the home quarters breeds for minutes, not only the first group; not
  built.
