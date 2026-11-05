# Buildings

Each tribe has the same three land buildings, plus the ship in *Resurrected*. The two tribes'
buildings look different but have identical stats.

| Building | Hit points | Footprint | Wood to build | Job | Default key |
|---|---|---|---|---|---|
| [Quarters](Quarters.md) | 200 | 5 × 5 | 40 | Breeds peons, trains the Chieftain | Q |
| [Armory](Armory.md) | 200 | 5 × 5 | 40 | Stores resources, makes weapons, equips warriors | R |
| [Tower](Tower.md) | 100 | 3 × 3 | 20 | Defensive post for one warrior | T |
| [Ship](Ship.md) (*Resurrected*) | 250 | Shoreline site | 50 | Carries units and supplies by sea | S |

## Construction

1. Select one or more [Peons](Peon.md) and pick a building (icon or hotkey).
2. Green squares show where the building fits. Click one to place the construction site.
3. The peons fetch wood and hammer it into the building. Each piece of wood adds 5 points and
   takes 5 seconds of work, and the building is finished when its build points reach its
   maximum hit points. That is where the "wood to build" numbers above come from.

Construction and repair are the same job, so the same command (*Gather/Repair*, default
**G**) also repairs damaged buildings.

To build faster, send a group of peons out of an [Armory](Armory.md) already carrying wood
with the *Transport Resources* command, so no one has to walk to a tree first. More peons on
the site also means faster building.

When an Armory is finished, the peons who built it go inside automatically, ready to be put
to work.

## Limits and placement

- Buildings need clear ground of their footprint size.
- By default each player can have at most 20 buildings at a time. The limit can be changed in
  the advanced game settings (*Resurrected*); see [Game Setup](Game-Setup.md).
- A building's model changes as it goes up: a start stage, a half-built stage below 50%, and
  the finished building.
- Buildings have no defense (see [Combat Mechanics](Combat-Mechanics.md#defense)), so every
  throw at them has its full hit chance. Damaged buildings start to smoke below half health.

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/RacesResources.java` (sizes, hit points),
`model/LandBuilding.java` (build state, repair), `model/behaviour/RepairBehaviour.java`
(5 points per wood), `common/src/main/java/com/oddlabs/matchmaking/Game.java` (building
limit).

Categories: Buildings · Overview
