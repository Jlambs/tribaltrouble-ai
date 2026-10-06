# corsair: working notes

Computer player for Archipelago maps (`--size archipelago`), benchmark settings tropical, hills 0, trees 10,
supplies 10 ("0/10/10"), any team sizes. Branch `corsair` off `boats` (the archipelago harness, unmerged into
`headless` as of 2026-10-05). All runs at `--speed ludicrous` unless noted.

## The map (lab/corsair/survey.py on survey-2p, survey-6p: seeds 1..30)

- 1024x1024 cells, ~24 islands, but only 1-4 (mean 2.5) have wood, rock and iron together. The islands do not depend
  on the player count, so with 6 players nearly every good island is shared, and even 1v1 shares the only good island
  on 20 % of seeds.
- Starts are on a circle at 0.35 of the map at sea; the nearest good island's centre is ~265 cells away on average.
- No extra start resources on archipelagos (the land generator's 2 rock + 1 iron per start are skipped).

## Mechanics found on the way

- Starting ship: 20 peons, all rowers. Ship speed (rowers+1) * 0.45 m/s game time, x2 with the chieftain aboard: a
  full 20-rower ship covers ~4.7 cells/s. Ships path round islands on the SEA region graph.
- A ship held against an enemy ship at the shore never leaves SailBehaviour ("stand your ground"): it stays
  isMoving() and nobody can land. Re-sail to its own cell to stop it (Fleet.transit).
- Armory-deployed gatherers have a GatherController with no supply (the HarvestController finds one): the swing
  restart takes the supply next to the peon instead (Reflexes.adjacentSupply).
- Deck fighters scan at TOWER_RANGE + 10 and prefer ships. Idle/walking land units scan only 8 cells
  (AttackScanFilter.UNIT_RANGE), so units with extra range must be given targets.
- Range ratchet (exploit audit A12): seat on the upper deck +10, exit -8 -> +2 range per trip, no cap. Hit chance does
  not depend on distance (rolled at the throw). Plain UI orders (board = right-click the ship, unload = deploy button).
- At the unit cap (250) peons stop breeding and every warrior deployed costs a peon for good: without a floor the
  economy dies by ~20 min (Army.deploy keeps min_peons).

## Ladder

| version | what | vs hard (60 games, 30 seeds, 60 min) | self-play |
|---|---|---|---|
| v0 (a991262f) | land on best island, base, single-ship ferries | 18-5-37 | |
| v2 (12356135) | sea-distance base choice, massed campaigns, group combat | 45-0-15 | mirror 1v1 9-9-42, 2v2 6-6-48 |
| v3 | + ratchet (6 trips), peon floor 45, idle rowers land | | vs @v2 1v1: 19-4-37, kd30 +181, margin +.375 (z>2) |

## Runs

- v3base-1v1 (peon floor + rowers, no ratchet) vs @v2: score +.017, margin +.091*, kd30 +32.
- v3ratchet-1v1 (ratchet=1, 6 trips) vs @v2: score +.125*, elim +.25*, kd30 +181*, margin +.375*.
