# corsair: report

`corsair` is a Tribal Trouble computer player for Archipelago maps (`--size archipelago`), tuned on tropical, hills 0,
trees 10, supplies 10 at ludicrous speed, for any team sizes. Package `tt/src/main/java/com/oddlabs/tt/player/corsair/`
(about 6,300 lines), branch `corsair` off `boats`. The working record is `lab/corsair/NOTES.md`; tools are in
`lab/corsair/` (eval.sh, sweep.sh, league.sh, robust.sh, collect.sh, quirks.py, survey.py). The release is the
branch head, frozen as `@v15` (it plays exactly as `@v14`; params added since are off by default).

## How it plays

1. **Opening (Fleet.openingVoyage, Geo, SeaMap).** Resources are counted per island in 8-cell bins; every bin of an
   island that has wood, rock and iron is a candidate base. A candidate scores its iron, rock and wood within ~24
   cells (its own island only), less the sea-plus-walk cost from our start (Dijkstra on a 4-cell sea grid, so routes
   round islands count) and a penalty when an enemy reaches it sooner. The starting ship sails there and lands
   everyone. Archipelagos have only 1-4 good islands (mean 2.5) whatever the player count, so islands are shared.
2. **Economy (Colony, Reflexes).** Quarters at once, then the armory, four quarters (10 peons each breeding), towers
   from 5 min, two more ships from 5 min. Sites are picked among trees (builders chop their own wood) and the armory
   near iron. Gatherers split wood/iron; **iron axes only** (at the 250-unit cap every slot counts and a rock warrior is
   worth 0.6 of an iron one). The gauntlet AI's tick reflexes: harvest swing restart (a viking peon hits every 15 ticks
   instead of 51) and stun cancel.
3. **Range drill (Ratchet).** Idle warriors at home board an idle docked ship and are let out again: a seat on the
   upper deck gives +10 range and leaving takes back 8, so each trip leaves +2 range for good (exploit audit A12; both
   halves are UI orders). The drill goes 4 trips deep, or 4 cells past the enemy's mean drill if it is deeper
   (adaptive), and the few most drilled go on to 8 trips to garrison the towers.
4. **Fighting on land (Army, Towers, Chief).** Defence engages enemies within 45 cells of our buildings; a peon
   militia meets early raids. Every manned tower gets an explicit target within its garrison's own range (tower +8
   and the drill), beyond its 14-cell scan, at the tower's triple accuracy. Assault groups advance as one body, focus
   fire (at most four on one enemy) and **kite**: a warrior that outranges the nearest enemy warrior backs off when it
   comes within its own reach. Groups break off before contact when the target's defence outweighs them. The
   chieftain stuns (vikings) or calls lightning/poison (natives) when enough enemies are in reach.
5. **Across the sea (Campaign, Fleet).** An assault waits until free warriors outweigh the target island's whole
   defence (warriors there, towers at four, discounted by our range edge) and enough ships are home to carry them.
   Each ship carries 26 warriors on deck and 20 more on the benches beside its rowers (the bench warriors board once
   the rowers sit); an op takes only as many ships as it has rowers for (10+ each; spare armory peons row too). It
   lands at a beach away from enemy towers, buildings and warriors (units leave a ship one at a time, so a contested
   beach is a massacre), re-picks the beach on approach if the enemy has moved onto it, and **the ships stay docked
   until every warrior is off** (they used to leave after 30 s with up to half aboard). Ships are built and dock 30
   cells apart (side by side they lock each other in sliding out) and stop only near their target cell. Enemies on
   our own island are met by a march on foot. Mop-up hunts enemy ships and attacks ships docked at cleared islands
   from the shore (each hit may kill one aboard; sunk, all drown). Allies count each other's armies: they pick
   targets by their average sea distance and march together on a shared cue when they share an island.
6. **Stalemates and stuck states.** After 25 minutes at the unit cap with no op for 10 minutes, the drill goes on
   to 15 trips (+30 cells) and the launch rule discounts the defence by the range edge down to 0.3: deep range is
   what breaks towers and equal armies. A construction site that stops growing is pulled down by our own warriors
   (one walled 106 wood gatherers into a pocket). When the home island runs out of iron the armory makes rock
   axes, and the colony moves to the best free island while a ferry brings the rest.

## Results

League (lab/corsair/league.sh; 60-minute games at ludicrous on archipelago 0/10/10, `--rng 1`; 1v1 on seeds 1..30
from both starts, 2v2 on seeds 1..15 from all four, 60 games per cell; W-L-D, margin = strength edge at the time
limit). The styles are `@v7` with params (blitz: early aggression, turtle: towers and defence, raid: a raiding
opening).

| opponent | `@v14` 1v1 | `@v14` 2v2 | `@v10` 1v1 | `@v10` 2v2 | `@v7` 1v1 | `@v7` 2v2 |
|---|---|---|---|---|---|---|
| `@v4` (an earlier release) | 51-0-9, +.88 | 54-0-6, +.91 | 52-0-8 | 47-0-13 | 20-1-39 | 19-0-41 |
| `@v4` without the drill (a stand-in for AIs that do not use ships' quirks) | 58-0-2, +.96 | 57-0-3, +.96 | 56-0-4 | 55-0-5 | 28-1-31 | 37-0-23 |
| stock Hard | 54-0-6, +.97 | 59-0-1, +1.0 | 54-0-6 | 58-0-2 | 55-0-5 | 57-0-3 |
| blitz style | 26-3-31, +.42 | 29-2-29, +.52 | 18-4-38 | 24-3-33 | 9-3-48 | 5-3-52 |
| turtle style | 5-1-54, +.07 | 13-0-47, +.32 | 3-2-55 | 9-0-51 | 1-1-58 | 1-0-59 |
| raid opening | 4-2-54, +.09 | 16-2-42, +.36 | 4-2-54 | 18-0-42 | 1-2-57 | 1-0-59 |

`@v14` won 426 of 720 league games and lost 10 (`@v10`: 394 and 11; `@v7`: 234 and 11). No game failed and no
error was swallowed (also with natives, free-for-all, 4v4, 2v3, hills 3, normal speed and northern terrain).

The draws at 60 minutes mostly convert in longer games. 180-minute games, 1v1 (60 games each): against `@v4`
56-0-4 (`@v8`: 34-2-24), blitz 49-4-7, turtle 34-6-20 (`@v13`), raid 36-7-17 (`@v14`'s rock fallback; 34-13-13
without it). Against its predecessor the late drill wins mirror games: `@v13` vs `@v12` 1v1 W20-L11 in 60 minutes.
Other team sizes against `@v4` (60-90 games each): 1v2 15-8-37, 2v3 28-2-30, 3v3 68-1-21, 2v1 60-0-0 (`@v13`).

## Exploits used

All are orders of our own player that the UI can give (the user's fair-play line): harvest swing restart and stun
cancel (from the gauntlet AI), the deck range ratchet (A12), and explicit tower targets beyond the scan range. No
engine or harness code was changed on this branch.

## Tried and not adopted (params left in, off)

Outposts (a second colony on a free island, its warriors shipped home; `outpost`, `outpost_idle`), settling apart
from enemies or near allies (`spread_open`, `ally_near`), deeper or earlier late drills, tower rushes and
uncovered-target raids (`tower_rush`, `soft_target`), hiding peons (`shelter`), aid to allies (`ally_aid`),
stuck-body recovery and evacuation (`unstick`, `evac`), safe building (`safe_build`), early rock axes
(`rock_until`), more armory workers, a higher launch bar when outnumbered. Each is in `NOTES.md` with its numbers;
outposts were the closest (180 min vs raid +.067) but cost tempo against everything else.

## Known limits

- Engine bug on `boats` (outside this branch): a ship whose sailing target lies on an island with no contour
  points crashes the game in `ShipTrajectory.pickTargetPosition` (`pts.get(0)` on an empty list). Seen once in
  100 games of `@v8` vs Hard (`./aisim.sh replay fresh-v8-hard-1v1 s64-0` reproduces it, likely Hard's ship).

- Equal opponents: defending an island is far stronger than landing on one, so mirror games end in timeouts (both
  sides hold ~205 iron warriors behind towers and neither attacks). Wins come against weaker or different
  strategies.
- Outnumbered games: against `@v4` 1v2 15-8-37 and 2v3 28-2-30, but against the aggressive blitz style 1v2
  0-21-39 and 2v3 7-19-34. Most of those losses come early, with no op launched, from bases settled within ~50
  cells of an enemy on a shared island; none of the defensive rules tried (more towers, a higher launch bar,
  hiding peons, early marches on the neighbour) changed that.
- A shared home island with an aggressive neighbour is the weak spot (most league losses are seed 4, where we
  arrive second on the only good island). Garrisoned towers outrange our drilled warriors by the tower's 8 cells.
- The chieftain does not ride ships (a chieftain alone on a rowing bench trips an engine assertion: a crash outside
  the harness), so stuns happen only on our own island and on land marches.
