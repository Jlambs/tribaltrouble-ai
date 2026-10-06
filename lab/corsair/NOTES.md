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

## Ladder

| version | what | vs hard (60 games, 30 seeds, 60 min) | self-play |
|---|---|---|---|
| v0 (a991262f) | land on best island, base, single-ship ferries | 18-5-37 | |
| v2 (12356135) | sea-distance base choice, massed campaigns, group combat | 45-0-15 | mirror 1v1 9-9-42, 2v2 6-6-48 |
| v3 (9156155b) | + ratchet (6 trips), peon floor 45, idle rowers land | | vs @v2 1v1: 19-4-37, kd30 +181, margin +.375 (z>2) |
| v4 (2bc931e8) | + chieftain, safer landings, mop-up of ships and units, ferry fixes | 48-0-12 (v4c) | vs @v3 level |

## Runs and findings

- v3base-1v1 (peon floor + rowers, no ratchet) vs @v2: score +.017, margin +.091*, kd30 +32.
- v3ratchet-1v1 (ratchet=1, 6 trips) vs @v2: score +.125*, elim +.25*, kd30 +181*, margin +.375*.
- Format sweep @v3 vs @v2 (15 seeds): 2v2 8-0-52, 2v1 40-0-5, 1v2 0-28-17, 3v3 10-4-76.
- ratchet_trips 12 / 20 vs 6 (vs @v3, 200 games each): no effect beyond noise. Range only pays with tactics that
  use it.
- Mirror games: 70-93 % timeouts at 60 min. After ~45 min both sides sit at the cap and stop launching (the launch
  rule needs more free warriors than the defender's whole army plus towers), and every landing into an alert
  defender loses 2-5 to 1.
