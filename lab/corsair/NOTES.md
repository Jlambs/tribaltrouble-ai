# corsair: working notes

Computer player for Archipelago maps (`--size archipelago`), benchmark settings tropical, hills 0, trees 10,
supplies 10 ("0/10/10"), any team sizes. Branch `corsair` off `boats` (the archipelago harness, unmerged into
`headless` as of 2026-10-05). All runs at `--speed ludicrous` unless noted.

Tools: `lab/corsair/eval.sh NAME SPEC OPPONENT [formats]` (1v1 seeds 1..50 + 2v2 seeds 1..25, 100 games each,
60 min, --rng 1); `lab/corsair/formats.sh`; `lab/corsair/survey.py` (map survey from `corsair:survey=1` logs).

## The map (lab/corsair/survey.py on survey-2p, survey-6p: seeds 1..30)

- 1024x1024 cells, ~24 islands, but only 1-4 (mean 2.5) have wood, rock and iron together. The islands do not depend
  on the player count, so with 6 players nearly every good island is shared, and even 1v1 shares the only good island
  on 20 % of seeds.
- Starts are on a circle at 0.35 of the map at sea; the nearest good island's centre is ~265 cells away on average.
- No extra start resources on archipelagos (the land generator's 2 rock + 1 iron per start are skipped).
- Chickens (rubber): at most 3 flocks of 3-7 at random trees anywhere on the map. Not used yet.
- Sea routes round the big central islands can be ~1500 cells (5+ minutes at full rowing speed).

## Mechanics found on the way

- Starting ship: 20 peons, all rowers. Ship speed (rowers+1) * 0.45 m/s game time, x2 with the chieftain aboard: a
  full 20-rower ship covers ~4.7 cells/s. Ships path round islands on the SEA region graph.
- Seats (vikings): 13 upper-deck pairs (26 warriors; the 2 front pairs are blocked while the chieftain sits, and he
  needs them empty to sit), 16 lower benches (~88 peon places; warriors there take 1.2 against 0.7). Warriors sit
  from the stern; more than 26 spill onto the benches and leave no room to row.
- A chieftain that cannot get his seat sits on a bench; alone there he is given an oar and SittingBehaviour asserts
  (supply_container null: an NPE outside -ea, a crash for every client). chief_rides is off for this reason.
- A ship held against an enemy ship at the shore never leaves SailBehaviour ("stand your ground"): it stays
  isMoving() and nobody can land. Re-sail to its own cell to stop it (Fleet.transit).
- Units leave a ship one at a time (iron 1.5 s, rock 1 s, peon 0.5 s per queue, queues share the time), only while
  it is docked and still. Landing near an alert enemy is a massacre (mirror-s7 op 6: 79 landed, 17 kills, 90 lost).
- Deploy queues persist: the drill queues a ship's whole deck to leave; an operation that takes the ship must cancel
  them, or its warriors walk aboard and straight back out (Fleet.cancelDeploys).
- Armory-deployed gatherers have a GatherController with no supply (the HarvestController finds one): the swing
  restart takes the supply next to the peon instead (Reflexes.adjacentSupply).
- Deck fighters scan at TOWER_RANGE + 10 and prefer ships. Idle/walking land units scan only 8 cells
  (AttackScanFilter.UNIT_RANGE), so units with extra range must be given targets.
- Land units can attack a docked ship (through its landing stage); each hit may kill one aboard (crew/200), and a
  sunk ship drowns everyone. Mop-up of a crewed ship docked at a cleared island turns draws into wins.
- Range ratchet (exploit audit A12): seat on the upper deck +10, exit -8 -> +2 range per trip, no cap. Hit chance does
  not depend on distance (rolled at the throw). Plain UI orders (board = right-click the ship, unload = deploy button).
- At the unit cap (250) peons stop breeding and every warrior deployed costs a peon for good: without a floor the
  economy dies by ~20 min (Army.deploy keeps min_peons).
- Unit.isWarrior() asserts on dead units: purge dead units from any list kept across thinks before asking.
- `replay --snap latest` of a run whose team A was a frozen `@TAG` replays that jar, not the new code: play the seed
  with `corsair` instead to test new code.

- A warrior can walk back into an armory (WorkerUnitContainer takes any unit; the weapon is lost): rock warriors at the
  cap can be swapped for iron ones (param upgrade).
- Economy at the cap (lab: ECON log lines): iron is not scarce (a home island keeps 130+ nodes after an hour);
  weapons were labour-bound (armory workers pop/8, ~4 iron axes/min) and peons went to the oars (3 ships x 20 rowers
  left 2-6 working peons during operations).
- baseScore counted resources across the water: a tiny islet next to a rich island won the opening and the AI never
  made a warrior (3 % of 1v1 games, all lost). Fixed: per-island bin counts, and the island must have all three.
- Decisive mirror games are decided by the map: the same slot wins both rotations of a seed.

## Ladder

| version | what | vs hard (60 games, 30 seeds, 60 min) | self-play |
|---|---|---|---|
| v0 (a991262f) | land on best island, base, single-ship ferries | 18-5-37 | |
| v2 (12356135) | sea-distance base choice, massed campaigns, group combat | 45-0-15 | mirror 1v1 9-9-42, 2v2 6-6-48 |
| v3 (9156155b) | + ratchet (6 trips), peon floor 45, idle rowers land | | vs @v2 1v1: 19-4-37, kd30 +181, margin +.375 (z>2) |
| v4 (2bc931e8) | + chieftain, safer landings, mop-up of ships and units, ferry fixes | 48-0-12 (v4c) | vs @v3 level |
| v5c (374560a5) | + tower fire control, drilled garrisons, iron axes only, per-island base score, militia | 180 min: 38-0-2 | vs @v4 1v1 margin +.28, 2v2 +.25 (z>2); 180 min vs v4 12-1-27 |
| v5f | + kiting | | vs no-drill v4 1v1 43-1-56 (elim +.11, kd30 +107); vs v4 kd30 +64/+98 |

## Runs and findings

- v3base-1v1 (peon floor + rowers, no ratchet) vs @v2: score +.017, margin +.091*, kd30 +32.
- v3ratchet-1v1 (ratchet=1, 6 trips) vs @v2: score +.125*, elim +.25*, kd30 +181*, margin +.375*.
- Format sweep @v3 vs @v2 (15 seeds): 2v2 8-0-52, 2v1 40-0-5, 1v2 0-28-17, 3v3 10-4-76.
- ratchet_trips 12 / 20 vs 6 (vs @v3, 200 games each): no effect beyond noise. Range only pays with tactics that
  use it.
- Mirror games: 70-93 % timeouts at 60 min. After ~45 min both sides sit at the cap and stop launching (the launch
  rule needs more free warriors than the defender's whole army plus towers), and every landing into an alert
  defender loses 2-5 to 1.

### 2026-10-06 screens (paired, 100 games per format, vs @v4 unless noted)

| change | 1v1 | 2v2 | verdict |
|---|---|---|---|
| tower_fire | elim +.08*, kd30 +28*, margin +.08* | margin +.05* | adopted |
| + drilled_garrison | margin +.09 | kd30 +78*, margin +.09* | adopted |
| rock_weapons=false (iron only) | elim +.12*, kd30 +56*, margin +.23* | kd30 +163*, margin +.19* | adopted |
| kite (vs no-drill v4) | elim +.11*, kd30 +107*, margin +.15* | elim +.16*, margin +.15* | adopted |
| kite (vs v4) | kd30 +64*, margin +.06* | kd30 +99*, margin +.11* | |
| snipe_towers | kd30 -34* (no-drill) | kd30 -87* | rejected |
| sink_docked=false | kd30 +13 | kd30 +47, w15 +5* | adopted |
| worker_share 0.35 / 0.5 | margin -.07* / -.18* | margin -.16* / -.30* | rejected |
| armories=2, quarters=3, max_towers=2 | worse | worse | rejected |
| militia (vs a raiding copy) | kd30 +24*, w15 +7* | | adopted |
| stage (beachhead staging) | neutral | margin +.04 | not adopted |
| attack_ratio 1.0 / 1.6, rowers 26, quarters 5, max_towers 6, chief_rides, tower_unstun, team_target, upgrade, rower_floor, min_peons 30, expedition 35 | within noise | within noise | |

- Iron-only mirrors never attack: both sides hold ~205 iron warriors behind towers and the launch rule needs more than
  the defender's whole army. A siege_after rule (attack anyway when stalemated) still loses 2-3:1 per landing: in a
  true mirror not attacking is right; gains come from beating different strategies.
- Robustness (rb5-*, @v5c vs @v4): no failed games or swallowed errors with natives on either side, free-for-all,
  4v4, 2v3, hills 3, normal speed, northern terrain.

### 2026-10-06/07: v6 -> v8

- abort_ratio 1.0 (marches break off before contact when outmatched): 2v2 elim +.04*, 1v1/1v2 slightly +. A
  1v2 loss had a march grow to 193 warriors (home guard 6) and walk into both enemies' armies (175).
- drill_adapt 4 + elite_trips 8 (drill to outreach the enemy's mean drill by 4 cells; the most drilled few to 8
  trips for the towers): vs @v5c 1v1 margin +.14*, 2v2 elim +.06*; inert vs no-drill (goal stays 4 trips).
- siege_peons: a 195-warrior march sat 137 cells short of a base for hours, held by a few enemy gatherers in reach,
  and another farmed 7,500 peons at a base without razing a building. With peons only in reach a quarter takes
  them and the rest advance or raze (towers, quarters, armory): vs @v4 1v1 elim +.19*, 2v2 +.14*; vs no-drill
  1v1 49-2-49, 2v2 57-0-43. 180-min vs v4: 11-1-28 (was 9-1-30).
- attack_ratio 1.0 / siege_after 600 in 180-min games: no change (9-1-30). The draws are not a threshold problem.
- ships 4 / 5 (was 3): vs @v4 1v1 elim +.18* / +.21*, 2v2 +.12* / +.11*. Bigger simultaneous landings and
  parallel unloading. stage: 2v2 elim +.07*, 1v1 neutral. attack_ratio 1.1, min_peons 35, rowers 14,
  chief_rides: neutral.
- An island with 2 trees (good iron and rock) won an opening: with iron axes only, rock is worthless and wood is the
  most used resource (wood_weight screened next).

League v7 (60 games per cell; W-L-D): vs v4 20-1-39 / 19-0-41, no-drill 28-1-31 / 37-0-23, Hard 55-0-5 / 57-0-3,
blitz 9-3-48 / 5-3-52, turtle 1-1-58 / 1-0-59, raid 1-2-57 / 1-0-59 (1v1 / 2v2; styles are v7 with params).
234 wins and 11 losses in 720 games (v6: 177 and 6, against v6-based styles).

### 2026-10-07 night: logistics in stalled games

Logs of v7/v8 timeouts (`WAIT` trace every 120 s in Campaign.launch, `PEONS` controller counts) showed the
stalemates were often not about the launch rule but about ships:

- Ships home from an op stopped at the first shore of the home island they touched (dock_stop accepts any shore of
  the target island): 170 cells from the base, so the next op's boarders did not arrive in its 70 s and it sailed
  with 26 of 70 warriors and 2 rowers (13 minutes to cross). dock_near 40: only stop that way near the target cell.
- At the cap the peons are ~45, mostly inside the armory (full stocks deploy no gatherers), and ops sailed with
  20, 20, 5, 0, 0 rowers; the empty ships never left the dock. row_min 10: ships per op limited to the rowers we
  have, spare armory peons row too, ships without rowers drop out, an op without any lands again.
- pack 40: warriors fill the benches beside the rowers (26 deck + ~40 bench). The bench warriors must wait for
  the rowers still walking up, or they take the room (an op sailed with 3 rowers of 40 ordered).
- ShipSlideBehaviour: a ship leaving a dock slides back to deep water uninterruptibly and stops for good while
  another ship is behind it. Ships built side by side at the dock locked each other in (3 of 5 for 10+ minutes).
  docks 5: ships are built and dock dock_gap (30) cells apart, and an op's ships land apart.
- 2v2 with both enemies on one island: their two armies are one defence (need 334 against our 168 + 146 at
  home); nobody attacks. late_drill (drill on to 15 trips at the cap after 20 min, defence discount floor 0.3,
  marches weigh the range edge too) is screened for these stalemates.
- Screens vs @v4: pack 30 / 45 (alone) 2v2 elim +.09* / +.08*, 1v1 neutral; stage 2v2 elim +.06* (again), 1v1
  neutral; ships 6 / 7 and wood_weight neutral.
- v9 (pack 40, dock_near 40, row_min 10, docks 5 on) vs v8 in other formats vs @v4: 2v1 59-0-1 (v8 54-0-6),
  3v3 50-1-39 (42-1-47), 1v2 7-8-45 (10-6-44; margin -.12*). 1v2 losses come at 17-28 min, when both enemies land
  together on a base that has 50-70 warriors to their 140 + 80; common to v8 and v9.
- **The landing phase sent the ships home 30 s after the last one docked.** Units leave a ship one at a time
  (iron 1.5 s), so ships went home with up to 58 of 102 warriors still aboard (they landed back at home): every
  op landed a fraction of what it carried, packed ones most. unload_wait 15 (ships stay while warriors still
  come off, up to 5 min): vs @v4 1v1 W53 -> 79 of 100, elim +.26*, margin +.23*; 2v2 W64 -> 75, elim +.11*.
  With rebeach (re-pick the beach on approach when the enemy stands at it): 1v1 W83 (+.30*), 2v2 W74.
- stage on v9: 1v1 elim +.07*, other formats neutral.
- blast_adapt (v7b mirror): no effect. late_drill 1200: 1v1 elim -.11, 2v2 +.14* (with the fixes); at 120 min
  13-0-7 against the fixes' 16-0-4: rejected.
- v10 (unload_wait 15, rebeach, pack 20) vs @v4: 1v1 88-0-12, 2v2 79-0-21, 1v2 18-7-35 (v9: 53-0-47, 64-0-36,
  7-8-45). On v10: pack 30 / 40 neutral to worse (2v2 elim -.05* at 40), stage neutral, rock_until 720 neutral
  (1v2 w15 -18*).
- Iron runs out on small homes (15-17 iron nodes: none left by 12 min; 120 peons then sit in the armory). expand N
  (off): with N iron nodes or fewer left and an empty armory, a ship takes 45 peons and 20 warriors to the best
  wood-and-iron island no enemy builds on, the colony is founded again there, and a crewed ferry brings the rest
  over. One of three starved seeds turned into a win; one picked an island the enemy then razed.
- v10 league (styles from @v7): 394 wins and 11 losses in 720 (v7: 234 and 11); 180 min vs @v4 1v1 55-1-4,
  2v2 52-0-8 (v8: 34-2-24, 36-0-24). expand 3 / 6: 1v1 +1 win, 1v2 two more losses (an outnumbered colony that
  moves gets caught): not adopted. workers_max 20 / 28: neutral to worse.
- Team play (v10e vs @v4): team_target (allies count each other's home warriors and pick targets by their average
  sea distance) + team_march (allies sharing our island count against its enemies; marches start on a 30 s cue):
  2v2 W79 -> 87, elim +.08; 3v3 elim +.13* (W57 -> 69). Adopted (v11); inert without allies.
- v10 in other team sizes vs @v4: 2v1 60-0-0, 3v3 57-0-33, 2v3 21-2-37 (v8 13-2-45), 3v2 53-0-7 (v8 47-0-13).
  stall_drop 240 (give up construction sites that stop growing): fires in 0.1-0.2 games per 100, no effect.
- Garrisoned towers outrange our drilled warriors (tower +8 on an elite garrison): a 130-warrior march kited in
  front of one for six minutes (218 lost, 21 killed). tower_rush (the body goes for a tower that reaches it, if
  few enemies are near) and soft_target (raze the buildings no tower covers first): vs @v4 2v2 elim -.06* / -.04*,
  vs blitz and turtle neutral to worse. Rejected.
- Pathologies found with lab/corsair/quirks.py on logged draws: iron-starved homes (4 of 12 1v1 draws), stalemates
  at the cap with 150+ free warriors (2v2), and one wood lock: a stalled ship site at the shore walled 106 wood
  gatherers into a pocket for the rest of the game (stall_raze: our warriors pull down a site that stopped
  growing; it freed them). On a shared island the blitz style killed 134 of our peons building 20 tower sites
  under fire (safe_build: no placing or building within 30 cells of enemy warriors; turned that loss into a win).
- late_drill 1500 (drill on to 15 trips at the cap after 25 min; defence discount floor .3; marches weigh the
  range edge) vs the turtle style (8 towers): 1v1 W5 -> 12, margin +.19*; 2v2 +.06, margin +.14*; with
  soft_target 1v1 elim +.08*, 2v2 +.11*. Deep range is what breaks towers. Rescreened vs @v4 and blitz on v11.
- v11 vs @v10 (the new sparring partner: mirror games are decisive about 30 % of the time now): 1v1 14-15-71,
  2v2 22-19-59.
- On v11e vs @v4 / blitz: stall_raze, expand 3 (now only when not outnumbered), safe_build, militia_smart: all
  within a win or two of the base at 60 min (safe_build vs blitz 1v1 elim +.09, 2v2 -.01). Not adopted.
- late_drill 1500 on v11e: vs @v4 1v1 elim -.09* (88 -> 79 wins: the drill delays attacks we would win anyway),
  2v2 -.04; vs blitz 1v1 +.18* (W25 -> 40), 2v2 +.15* (W36 -> 49); soft_target on top adds nothing. late_idle
  600 (start the late drill only when no op or march has gone for 10 minutes) screened on v12.
- late_idle 600 on v12 (late drill only after 10 minutes without an op or march): vs @v4 1v1 -.05 (n.s.), 2v2
  -.01; turtle +.04 / +.04 (margin +.12*); mirror @v11 1v1 W13 -> 20, elim +.10*, 2v2 +.04 (margin +.09*); blitz
  1v1 +.10* (W26 -> 35), 2v2 +.08. Ungated vs blitz +.16* / +.14*. Adopted gated (v13): late_drill 1500,
  late_trips 15, late_idle 600, edge_floor .3, abort_edge.
- Decomposition vs blitz (ungated): the deep drill alone 1v1 elim +.07, 2v2 +.09 (margin z>2); the edge rules
  alone (defence discount floor .3, marches weigh the range edge) +.04 / +.07; both +.16* / +.14*. They need each
  other: the deeper range is what makes the bolder launch rule right.
- v13 league: 424 wins, 10 losses of 720 (v10: 394 and 11). v13 vs @v12 (late drill the only difference): 1v1
  19-11-70, 2v2 20-16-64. Tuning vs @v12: late_idle 300 1v1 +.07 (n.s.), late_trips 20 neutral, late_drill
  1200 2v2 +.05*.
- Robustness rb13 (@v13 vs @v4): natives 37-1-2, vs natives 33-0-7, FFA3 12-0-18, 4v4 33-0-7, 2v3 18-1-21,
  hills 3 32-1-7, normal speed 14-0-6, northern 11-1-8; no failed game, no swallowed error.
- v13 team sizes vs @v4: 1v2 15-8-37 (v10 18-7-35), 2v3 28-2-30 (v10 21-2-37), 3v3 68-1-21 (v10 57-0-33),
  2v1 60-0-0.
- late_idle 300 on v13: vs @v4 1v1 elim -.09* (2v2 0), vs blitz +.03 / +.02; with late_drill 1200 vs @v4 1v1
  -.06*, blitz 2v2 +.07*. Keeping 600 / 1500.
- 180-minute games, v13 1v1: vs turtle 34-6-20, raid 34-13-13, blitz 48-5-7, @v12 44-8-8. Most losses come after
  90 minutes. Homes run out of iron in long games and the colony moves (expand) in 6-14 of 60: those games went
  2-7-5 against raid. One: the new home was razed within minutes and 167 peons sat idle at the old one for the
  last 90 minutes with no ships to fetch them. expand_safe (settle away from enemy buildings; fall back to the old
  home when the new one has no peons left) screened at 180 minutes against expand 0 and 3.
- ally_aid (ship free warriors to an ally base under attack): vs blitz 2v2 / 3v3 and @v4 2v2 / 3v3 within a win of
  the base; it seldom fires (an ally under attack usually shares our island or our own island is contested). Not
  adopted. late_idle 300 + late_drill 1200 vs @v12: 2v2 elim +.08*, 1v1 +.05; vs @v4 1v1 -.06*: not adopted.
- 180 min vs raid: expand 3 34-13-13, expand 0 34-12-14, expand_safe 34-12-14: the colony move does not decide
  these. The long losses are iron attrition: in the losses after 2 hours we kill more than we lose, but the enemy
  harvested ~2x our iron (s8-0: 1490 against 680) once our island ran dry. rock_fallback (rock axes once the
  island's iron is gone) screened next.
- 180 min vs turtle: expand 3 34-6-20, expand 0 33-10-17, expand_safe 33-6-21. Moving the colony is worth ~4
  losses in 60 there; expand_safe adds nothing. Keeping expand 3.
- rock_fallback (rock axes once the island's iron nodes are down to 2): 180 min vs raid W34 L13 -> W36 L7, elim
  +.13* (with expand_safe 36-6); vs turtle 34-6 -> 33-4 (neutral). Adopted (v14).
- v14 vs @v4 at 60 min: 1v1 86-0-14, 2v2 87-0-13, 1v2 15-8-37; rock_fallback off instead: 85 / 85 / 15 (neutral
  at 60 min, as expected: it fires in long games).
- v14 league: 426 wins, 10 losses (v13: 424 and 10). 180 min: vs @v4 56-0-4, blitz 49-4-7 (v13 48-5-7), vs @v13
  16-24-20, but paired against @v13 vs @v13 on the same games (identical unless the fallback fires) that is +.025
  (3 better, 2 worse, 55 the same): mirror games swing on the start slot, not on the change.
- contest_weight 2 / 3 (opening): not one base choice changed in 1v1 or 1v2 (identical games).
- outnumbered_towers 0 / 4 (default 2) in 1v2 and 2v3: 0 worse (2v3 elim -.08*), 4 neutral.
- Wood runs out too: an island of 184 trees had none left at 76 min with 79 iron nodes standing; no axes, a loss
  at 146 min to the raid style. expand_wood 15 (the colony moves when 15 trees or fewer are left and the armory
  has under 20 wood) won that game; screened at 180 min.
- expand_wood 15 at 180 min: raid 36-7 -> 37-6, turtle identical, mirror vs @v13 one win -> draw. Fires rarely;
  net zero. Not adopted (param left in, off).
- Fresh seeds 51..100 (validation; nothing was tuned on them), 1v1 at 60 min, v14 / v8: vs @v4 81-0-19 / 41-0-59,
  vs @v10 25-14-61 / 3-15-82, vs Hard 91-0-9 / 88-0-11, vs blitz 29-3-68 / 14-4-82.
- **Engine crash (boats branch, not AI code):** `fresh-v8-hard-1v1 s64-0` (@v8 vs hard, seed 64, slot 0) crashes at
  38:24 with IndexOutOfBoundsException in `ShipTrajectory.pickTargetPosition` (ShipTrajectory.java:315,
  `pts.get(0)` on an island whose `contourPoints()` is empty), from `SailBehaviour.replanIfNeeded`. Our AI had
  not sailed since minute 1, so the sailing ship was most likely Hard's. Reproduce: `./aisim.sh replay
  fresh-v8-hard-1v1 s64-0` (VERIFIED, then crash). Any player's sailing target on such an island crashes every
  client.
- shelter (peons near raiders that no militia meets go inside the nearest quarters or armory): fewer peons die
  (kd30 +63* in 2v2) but games are lost: vs blitz 1v1 elim -.15*, 2v2 -.10; vs @v4 1v1 -.07, 1v2 -.12. Hidden
  peons stop the economy, and a razed building takes everyone inside with it. Rejected.
- unstick + evac (a body standing idle for a minute short of an unreachable target, often an enemy ship docked
  out of reach, retargets or gives the island up for ten minutes; stranded warriors are shipped home): turned the
  seed it was built on into a win, but vs @v4 1v1 0, 2v2 -.05 (first version, still and not idle: -.07*), blitz
  0 / 0, mirror @v13 +.02 / +.02. Not adopted (params off).
- Release candidate @v15 (branch head; plays as v14 on checked games): robustness rb15 natives 37-1-2, vs natives
  33-0-7, FFA3 12-0-18, 4v4 34-0-6, 2v3 18-1-21, hills 3 32-1-7, normal speed 14-0-6, northern 11-1-8; no failed
  game, no swallowed error.
