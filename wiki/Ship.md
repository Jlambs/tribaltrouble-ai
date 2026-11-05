# Ship

*Resurrected. Ships were not in the original 2004 game.*

| Ship | |
|---|---|
| **Tribe** | Both |
| **Hit points** | 250 |
| **Placement** | On the shoreline |
| **Cost** | 50 [Wood](Resources.md#wood) (from 250 build points at 5 per wood) |
| **Stores** | Resources and weapons, up to 200 of each |
| **Speed** | 0.45 × (rowers + 1); doubled with the Chieftain aboard |
| **Default key** | S (with peons selected) |

The **Ship** lets a tribe carry units and supplies across water, land invasions on other
islands, and fight at sea. Ships came with the *Archipelago* map type, where players start at
sea.

## Building and crewing

- Select peons and choose **Ship**. Only shoreline sites are offered; the button is greyed out
  when no site is available.
- Units board a ship and take seats as rowers or fighters. More rowers make the ship faster.
- A [Chieftain](Chieftain.md) can board. With the Chieftain aboard, the ship's speed doubles
  and the warriors aboard throw much more accurately. Without the Chieftain, throws from a
  rocking deck tend to go wide.
- Select a ship and use **Sail** (default **M**, or right-click the water) to move it.
  Double-click a ship to select all your ships on screen.
- When a ship reaches land, its units disembark onto the island where it is docked.

## Combat

- Throws aimed at a ship hit a unit on board.
- **If a ship is destroyed, everyone on board dies.**

## Availability

- Ships can be turned on or off per game with the **Boats** option in the advanced game
  settings. They are always on for Archipelago maps, where everyone starts at sea with one boat
  per 26 starting units.
- Ships are turned off in the [Campaigns](Campaigns.md) and the [Tutorial](Tutorial.md).

## Notes for editors

The ship is the newest and fastest-changing part of the game. Re-check numbers against
`tt/src/main/java/com/oddlabs/tt/model/Ship.java`, `ShipHR.java` and
`model/behaviour/SailBehaviour.java` before quoting them.

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/RacesResources.java` (hit points),
`model/Ship.java` (storage, starting units per ship, crew death),
`model/behaviour/SailBehaviour.java` (speed), `model/Unit.java` (accuracy on board), git
history of the `archipelago` and boats work (August–September 2026).

Categories: Buildings · Naval · Resurrected
