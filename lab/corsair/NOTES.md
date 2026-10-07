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
