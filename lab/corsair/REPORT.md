# corsair: report

`corsair` is a Tribal Trouble computer player for Archipelago maps (`--size archipelago`), tuned on tropical, hills 0,
trees 10, supplies 10 at ludicrous speed, for any team sizes. Package `tt/src/main/java/com/oddlabs/tt/player/corsair/`
(about 6,000 lines), branch `corsair` off `boats`. The working record is `lab/corsair/NOTES.md`; tools are in
`lab/corsair/` (eval.sh, sweep.sh, league.sh, robust.sh, collect.sh, survey.py).

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
   from the shore (each hit may kill one aboard; sunk, all drown).

## Results

League (lab/corsair/league.sh; 60-minute games at ludicrous on archipelago 0/10/10, `--rng 1`; 1v1 on seeds 1..30
from both starts, 2v2 on seeds 1..15 from all four, 60 games per cell; W-L-D, margin = strength edge at the time
limit). The styles are `@v7` with params (blitz: early aggression, turtle: towers and defence, raid: a raiding
opening).

| opponent | `@v10` 1v1 | `@v10` 2v2 | `@v7` 1v1 | `@v7` 2v2 |
|---|---|---|---|---|
| `@v4` (an earlier release) | 52-0-8, +.86 | 47-0-13, +.85 | 20-1-39 | 19-0-41 |
| `@v4` without the drill (a stand-in for AIs that do not use ships' quirks) | 56-0-4, +.94 | 55-0-5, +.94 | 28-1-31 | 37-0-23 |
| stock Hard | 54-0-6, +.97 | 58-0-2, +.99 | 55-0-5 | 57-0-3 |
| blitz style | 18-4-38, +.30 | 24-3-33, +.37 | 9-3-48 | 5-3-52 |
| turtle style | 3-2-55, -.04 | 9-0-51, +.16 | 1-1-58 | 1-0-59 |
| raid opening | 4-2-54, -.02 | 18-0-42, +.28 | 1-2-57 | 1-0-59 |

`@v10` won 394 of 720 league games and lost 11 (`@v7`: 234 and 11). No game failed and no error was swallowed.
180-minute games against `@v4`: 1v1 55-1-4, 2v2 52-0-8 (`@v8`: 34-2-24, 36-0-24). Other team sizes against `@v4`
(60-90 games each): 1v2 18-7-35 (`@v8`: 10-6-44), 2v1 59-0-1 and 3v3 50-1-39 (`@v9`).

## Exploits used

All are orders of our own player that the UI can give (the user's fair-play line): harvest swing restart and stun
cancel (from the gauntlet AI), the deck range ratchet (A12), and explicit tower targets beyond the scan range. No
engine or harness code was changed on this branch.

## Known limits

- Equal opponents: defending an island is far stronger than landing on one, so mirror games end in timeouts (both
  sides hold ~205 iron warriors behind towers and neither attacks). Wins come against weaker or different
  strategies.
- Outnumbered (1v2) games are mostly draws; the losses come at 17-28 minutes when both enemies land together.
- A shared home island with an aggressive neighbour is the weak spot (most league losses are seed 4, where we
  arrive second on the only good island). Garrisoned towers outrange our drilled warriors by the tower's 8 cells.
- The chieftain does not ride ships (a chieftain alone on a rowing bench trips an engine assertion: a crash outside
  the harness), so stuns happen only on our own island and on land marches.
