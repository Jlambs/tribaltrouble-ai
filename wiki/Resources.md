# Resources

There are four resources. [Peons](Peon.md) gather them one load at a time and carry them to an
[Armory](Armory.md), which can store up to 200 of each.

| Resource | Found as | Per source | Used for |
|---|---|---|---|
| [Wood](#wood) | Trees and palm trees | 10 | Every building, every weapon |
| [Rock](#rock) | Rock deposits | 10 | Rock weapons, chicken weapons |
| [Iron](#iron) | Iron deposits | 10 | Iron weapons, chicken weapons |
| [Tropical Chicken](#tropical-chicken) | Roaming flocks | 1 per chicken | Chicken weapons |

## Wood

Wood is used for everything. Buildings are made of nothing else (40 for a
[Quarters](Quarters.md) or [Armory](Armory.md), 20 for a [Tower](Tower.md), 50 for a
[Ship](Ship.md)), and every weapon costs 2 wood on top of its other ingredients. Each tree
gives 10 wood and then falls.

Wood is almost always the resource you need most. A steady wood supply is the first thing to
set up in any game.

## Rock

Rock deposits give 10 rock each. Rock goes into [rock weapons](Rock-Warrior.md) (1 each) and
[chicken weapons](Chicken-Warrior.md) (1 each).

## Iron

Iron deposits look like rock deposits with a different colour and give 10 iron each. Iron goes
into [iron weapons](Iron-Warrior.md) (1 each) and [chicken weapons](Chicken-Warrior.md) (1
each). Iron is what turns a rock army into an iron one, so iron deposits are often worth
fighting over.

## Tropical Chicken

The **Tropical Chicken** is a colourful island bird. Its beak makes the head of the best
weapon in the game: in the [Viking Campaign](Viking-Campaign.md) the Vikings learn that the
Natives use chicken beaks as spearheads, and copy the idea for their axes. (The game's code
calls the resource "rubber".) Chickens wander
the island in flocks, and peons have to hunt them down; each chicken gives 1 load.

- At most **3 flocks** exist at once, each of **3 to 7** chickens.
- Every 60 seconds the game checks whether there are fewer than 3 flocks and, if so, adds a new
  flock. New chickens drop out of the trees.

Chickens are a trickle, not a stream. Each [chicken weapon](Chicken-Warrior.md) needs one, so
plan on a handful of elite warriors rather than an army of them.

## Regrowth

Trees, rock and iron come back, but only when the island is running out. Every 10 seconds, if
more than three quarters of the original sources of a resource type are used up, one used-up
source of that type (chosen at random, and not under a unit or building) is restored.

## Data sources

`tt/src/main/java/com/oddlabs/tt/landscape/TreeSupply.java`, `model/RockSupply.java`,
`model/IronSupply.java`, `model/RubberSupply.java` (amounts),
`model/RubberSupplyManager.java` and `model/RubberGroup.java` (flocks),
`model/SupplyManager.java` (regrowth), `model/LandBuilding.java` (costs, storage).

Categories: Resources · Economy
