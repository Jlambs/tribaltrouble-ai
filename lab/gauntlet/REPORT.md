# gauntlet: report

`gauntlet` is a Tribal Trouble computer player built to beat as many allied stock Hard AIs as possible, one
against N (`--players "gauntlet vs hard*N"`). Package `tt/src/main/java/com/oddlabs/tt/player/gauntlet/`, branch
`gauntlet` off `headless` (ff8bc33d). The working record, with every run, is `lab/gauntlet/NOTES.md`.

## Result: N* = 8

The exam: large tropical maps, hills 0..2, trees 10, supplies 10, our AI in slot 0, seeds 30001..30100, 360-minute
limit, collapse rule on, a frozen tag with no params (the AI detects N and races itself). Every exam attempt:

| attempt | tag (commit) | lineup | races | W / L / D | win % | Wilson 95 % | wins by elimination (via collapse) | failed games | replays |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `@g-shep` (540e97e6) | vs hard*7 | vikings everywhere | 45 / 55 / 0 | 45 % | [35.6, 54.8] | 45 (3) | 0 | s30001-0, s30002-0, s30003-0 VERIFIED |
| 2 | `@g-v8` (c002c1ae) | vs hard*8 | vikings everywhere | 11 / 89 / 0 | 11 % | [6.3, 18.6] | 11 (2) | 0 | s30001-0, s30002-0, s30003-0 VERIFIED |
| 3 | `@g-final` (2f266fba) | vs hard*8 | vikings everywhere | 25 / 75 / 0 | 25 % | [17.5, 34.3] | 25 (4) | 0 | s30001-0, s30002-0, s30003-0 VERIFIED |

Attempt 3 re-sat N=8 with the final version, so that the delivered package is the one examined (dev estimate before
it: 52/200 on fresh seeds). No exam was run at N=9: the dev estimate never came near 10 % (below). Draws: none in
the exams. Lint of every examined tag: ok. Exam games were used for nothing but the exams, and nothing was tuned on
them. No exam game failed (crash, hang or error) and none swallowed an error.

Cost: 32.7 s CPU per 37-minute exam game (attempt 3), about 1.2x an all-stock game of the same length; our AI is ~14 %
of the samples in a profile of an N=8 game (the engine and the eight stock AIs the rest), ~0.4 % of real time.

Dev estimates of the final version (`@g-final`, never tuned on these fresh seeds 201..400):

| N | games | wins | win % | Wilson 95 % |
|---|---|---|---|---|
| 8 | 200 (seeds 201..400) | 52 | 26.0 % | [20.4, 32.5] |
| 9 | 200 (seeds 201..400) | 9 (1 draw) | 4.5 % | [2.4, 8.3] |

Ladder on dev seeds 1..100 over the project (Hard race in brackets): port of Expert N=5 32 [v], N=6 4 [v]; + swing
restart N=5 76, N=6 52, N=7 19 [n]; + shepherds and attack gate N=7 35 [n], 34 [v], N=8 15 [v]; g-v8 N=8 16 [v];
aggressive campaign N=8 19, N=9 5 [v]; g-final N=8 23, N=9 4 [v].

Robustness of `@g-final` (lab/gauntlet/robustness.sh: every map setting random, all sizes and both terrains, 505
games): 0 failed games and no swallowed errors anywhere. Records: duel vs hard 59-1; as natives vs hard*3 51-28-1; in
team B vs hard 40-0; allied with a Hard vs hard*3 63-12; free-for-all vs hard, normal and easy 34-4-2; mirror 9-9-2;
huge maps vs hard*4 (90 min) 39-5-6; small/medium maps vs 6 native Hards 5-135 (the same 5-135 as `@g-v8` on those
games).

## How it plays, and where each part comes from

The base is the **Expert AI** (branch expert-ai, the strongest of the five earlier AIs on this benchmark: 92/70/33/4 %
at N=3..6), ported whole (commit 2630f043: its classes, byte-identical to the frozen `@expert` jar, renamed;
`placeBuilding` instead of `BuildingTemplate.create`; AiLog and AiParams; no statics; landscape orders clamped into
the map, which fixed an off-map crash of the original). Expert brings the economy (four quarters by ~4 minutes, an
expansion armory), the army and its micro (per-warrior target choice by value, hit chance and survival), towers
facing every enemy, the chieftain's stun policy and dodging, and sappers. The port played like the original (N=5 32
vs 33, N=6 4 vs 7 on the same 100 seeds).

What was added, each behind a param whose default is the measured best (evidence: paired runs on the same seeds):

1. **Harvest swing restart** (Reflexes; exploit audit A26/K3). A peon's hit is credited at the swing's release
   point; re-ordering it to the same supply right after starts the next swing at once, so a viking peon hits every
   15 ticks instead of 51. Construction chopping likewise. N=5 43 -> 76, N=6 10 -> 52 (vs native Hards, 34 gained /
   1 lost and 44/2). The largest single gain.
2. **Stun cancel** (Reflexes; audit K1, re-checked on this engine). An order on the tick a stun lands replaces the
   StunBehaviour before it runs. Viking Hard chieftains' stuns never land on our field units (0 `stunned` events for
   our slot in the test game). With it viking Hards are no harder than native ones at N=7 and easier at N=8 (native
   lightning cannot be cancelled), so the exams were sat against vikings.
3. **Shepherds** (Shepherd.java; the Hard's targeting rule, AdvancedAI.findTarget, which sweep and outnumbered used
   with decoy buildings; the unit side of the rule is used here for the first time). A
   Hard wave goes for our building nearest its oldest idle warrior, or for one of our units if that unit is nearer
   than 0.707 of that. One peon per copy stands at a legal spot 14-22 cells from the copy's leader, nearer than any
   building of ours, and flees when the wave starts: the wave walks to an empty spot and idles there. Strength at
   15-20 minutes up by ~0.4-1.2 log units at N=7-8 (z 4-7).
4. **Attack gate for leashed copies** (Military; Strategy.forGame for several enemies). With shepherds holding the
   waves, only enemies within 60 cells of a target count fully, and the copies' growth during the march is no
   longer added: N=7 23 -> 35, N=8 6 -> 12. Plus **reinforcements against several enemies** (N=7 19 -> 27).
5. **Dodge only blasts** (dodge_blast_only): stuns are cancelled, so units run only from viking blasts (N=8 14 -> 16).
   Frozen as g-v8 and examined at N=8.
6. **All-in campaign against several enemies** (Strategy.forGame: attack at even strength, no adaptive caution,
   never call the army home, attack with the base under threat). The campaign analysis of 40 logged N=8 games
   (lab/gauntlet/campaign.py) showed that wins are long sustained attacks and losses make one attack and are called
   home for good. N=8 on fresh seeds 31/200 -> 43/200 (25 gained, 13 lost; share of copies eliminated +.077, z 3.4); N=9 2 -> 5.
7. **Tower stun cancel** (tower_unstun, Reflexes). Stuns also land on tower garrisons (Stun.animate). An order to a
   tower pushes an AttackController on the garrison without clearing its stack, so ordered on the tick its
   StunController comes on top it decides at once and keeps throwing; the stun waits underneath until nothing is in
   reach. N=8: 19 -> 23 on seeds 1..100 and 43 -> 52 of 200 on fresh seeds (22 gained, 9 lost together). Frozen as
   g-final.

All exploits are orders to our own units that the UI can give (a right-click on a supply, a building, the ground,
or an enemy with a tower selected); what makes them pay is their timing. No engine or harness code was changed.

How a won N=8 game goes (tunstun-vs8-hv-b, lab/gauntlet/timeline.py; logged games): the army leaves at 4-5 minutes,
the first copy is out at a median 10.3 minutes and the second at 13.6, and the army keeps marching from copy to
copy, reinforced in clumps, while shepherds leave many waves idling in empty places and the towers (stuns cancelled)
hold the rest. Our first building falls at ~12 minutes in wins and losses alike; the wins are the games where the
campaign outruns the fall of the base (first armory lost at 18.1 minutes in wins, 16.1 in losses). What stops N=9:
with one more copy the base falls with 4-5 copies still standing, and the army, cut off from reinforcements, dies
after it.

## What did not work (paired with the then-current version on the same seeds; W = wins per 100 unless noted)

| idea | run(s) | result |
|---|---|---|
| Decoy tower sites in front of Expert's towers (decoys v1, margin 0.85) | decoys1-vs5/vs6-hn, 100 each | 40 vs 43, 10 vs 10: the spot search fails (Expert's base is too dense) |
| Decoys v2 (margin 0.97), with shepherds, without the cage rule, with front towers pushed out | decoys97-vs7/vs6-hn, shepdecoy, decoync, play-decoycheck4 | 12 vs 19, 47 vs 52, 12 vs 15, 14 vs 14; the waves' nearest building is an unfinished tower site out of reach of manned towers |
| Freeze strike (kill a neighbour's armory builders at the start) | freeze1-vs7-hn, freeze1-vs8-hn, 100 each | 9 vs 19 (5/15): half the starting peons costs too much; a third of strikes freeze |
| harvest_seconds 3.5, quarters_before_armory 2/3, hold_mid 6, chieftain_min_quarters 2 | N=7 screen, 100 each | 15, 16, 17, 20, 19 vs 19: no or inert |
| Parallel towers (tower_parallel 2-3) | towpar2/3-vs7-hn, combo2-vs7-hn | +3/+6 without shepherds, 20 vs 24 with them |
| recall_old_gatherers=false, expansion=false | N=7 and N=8 screens | 18/18 vs 19; 8 vs 14 at N=8 |
| Focus one copy (focus_bonus, focus_finish), always hunt chieftains | N=7 screen | 15, 15 vs 19 |
| Wider/nearer shepherd spots, shepherd_home_weight, shepherd_patience | shepwide, shephome, pat8 (N=8, 100 each) | 8 vs 12, 9 vs 12, 12 vs 11 |
| A second ore stream (rock_share=0.3) | rockshare30-vs8-hn | 8 vs 11: rock displaces iron |
| Target choice: defense weight 2, home weight 1 or 0.5, threat weight 5/15, quarters first | tdw2, thome1, thome05, tthreat5/15, qfirst (N=8) | 11, 10, 19, 16, 13, 10 vs 14-19 |
| Hidden information (enemy stock and chieftain charge) | hidden-vs8-hv | 7 vs 14: worse |
| Our AI as natives | usnat-vs8-hv | 4 vs 14: the native swing restart is far weaker |
| More quarters (5, 6) | q5/q6-vs8-hv | 14, 13 vs 14 (kd worse) |
| Raids on enemy peons under threat | raid8-vs8-hv | 9 vs 14 |
| Home guard (keep 20 strength home) | guard20-vs8-hv | 5 vs 16 (2/13) |
| Recall ratio 1.0 alone / with attack under threat (old defaults) | recall10, recallatk | 15, 15 vs 14 |
| Quarters gate (raze each copy's quarters first; v1 and v2) | gatefreeze-vs8-hv, gfaggro, gfv2 (100 each) | 12 vs 16, 16 vs 19, 17 vs 19: copies keep their chieftain or rebuild |
| attack_max_strength 50, reinforce_ratio 0.3, precontact 0.8, retreat 2.0 | 100 each | 19, 17, 19, 19 vs 19: inert |
| Sniper towers by parked blobs | sniper-vs8-hv | 16 vs 19, early strength worse |
| Mutual towers (10-13 cells apart) | tmutual, tmutunstun (100 each) | 21 vs 19, 23 vs 23 |
| Our chieftain blasting parked blobs (shred) | test games | stun and blast share one charge; never charged |
| Forward towers over the enemy's iron gatherers (forward_towers, forward_threat) | test games | planned 3 times a game, dropped every time for lack of cover while the army is out |
| Chieftain keep-out 14, attack_ratio 1.0 alone (before the campaign bundle) | keepout14, atk10 (N=8, 100 each) | 13 vs 11 and 13 vs 11 (native), 11 vs 14 and 13 vs 15 (viking) |

## Untried or unfinished ideas

- A chieftain policy that gives up stuns to keep the blast (stun and blast share one charge) and blasts parked
  enemy blobs, which never react to hits and set off no chieftain spell against a lone chieftain.
- Campaign routing: plan the order of eliminations over the whole map (distance, the copies hitting our base, the
  time left before the base falls) instead of the greedy nearest-target rule, which lets the army drift away from the
  copies that raze the base.
- A second economy near the army once a neighbour is out, so the campaign survives the fall of the home base.
- Rubber warriors against buildings (audit A15: 6.2 damage per throw to quarters/armory vs 1.68 for iron) to shorten
  each elimination.
- The freeze strike at N >= 10, where neighbours start ~90 cells away.
- Shepherds for re-launches of parked blobs near our base (today they serve launches from the copies' homes only).

## Reproduction

```bash
git checkout 2f266fba                         # g-final (g-v8: c002c1ae, g-shep: 540e97e6), on branch gauntlet
./aisim.sh build
./aisim.sh freeze g-final gauntlet
./aisim.sh lint @g-final
./aisim.sh batch --name final-vs8-g-final --players "@g-final vs hard*8" --size large --terrain tropical \
  --hills 0..2 --trees 10 --supplies 10 --seeds 30001..30100 --side 0
./aisim.sh replay final-vs8-g-final s30001-0
./lab/gauntlet/dev.sh gfinal-vs9-hv-b "@g-final vs hard*9" 201..400 --workers 14    # dev estimate
python lab/gauntlet/score.py --pair RUN_A RUN_B                                       # paired comparison
./lab/gauntlet/robustness.sh @g-final gfinal
```

No commit was made on `headless` and no engine or harness code was changed; the branch's commits touch only the
package and `lab/gauntlet/`.
