# Armory

| Armory | |
|---|---|
| **Tribe** | Both |
| **Hit points** | 200 |
| **Footprint** | 5 × 5 |
| **Cost** | 40 [Wood](Resources.md#wood) |
| **Stores** | Up to 200 of each resource and each weapon type |
| **Produces** | Rock, iron and chicken weapons; equips warriors |
| **Default key** | R |

The **Armory** is the centre of the economy. Peons bring [resources](Resources.md) here,
peons inside turn them into weapons, and the armory hands those weapons to peons to make
warriors. Its main menu has three sub-menus: *Gather Resources*, *Produce Weapons* and
*Deploy Army*. The armory's stock of resources, units and weapons is shown at the top of its
panel.

## Gather Resources (G)

For each resource type, click **+** to send that many peons from inside the armory out to
gather it. They keep gathering and bringing loads back until you give them other orders.
Build armories close to resources: shorter walks mean more loads per minute.

The **Transport Resources** command sends peons out carrying resources from the armory's
stock. Use it to bring wood to a construction site, or to move stock to another armory.

## Produce Weapons (W)

Set how many of each weapon to make with the + and − icons. Keep increasing past the maximum
and an infinity symbol appears: the armory will keep making that weapon until told to stop.
Hover over an icon to see what it costs.

| Weapon | Cost | Work time |
|---|---|---|
| Rock weapon | 2 wood + 1 rock | 40 peon-seconds |
| Iron weapon | 2 wood + 1 iron | 80 peon-seconds |
| Chicken weapon | 2 wood + 1 rock + 1 iron + 1 chicken | 120 peon-seconds |

The work is done by the peons inside. Every second, each peon inside adds one peon-second of
work, split evenly between the weapon types being made. With 20 peons inside working only on
rock weapons, the armory turns out a rock weapon every 2 seconds. Resources are taken when each
weapon is finished; if a resource runs out, that weapon type waits.

## Deploy Army (A)

Choose how many of each warrior to send out. Each warrior uses one peon from inside and one
weapon from stock.

| Unit | Time per unit |
|---|---|
| Peon (unarmed) | 0.5 seconds |
| [Rock Warrior](Rock-Warrior.md) | 1 second |
| [Iron Warrior](Iron-Warrior.md) | 1.5 seconds |
| [Chicken Warrior](Chicken-Warrior.md) | 2 seconds |

Set a **rally point** so new warriors gather in a useful spot.

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/LandBuilding.java` (costs, work times, deploy times,
storage), `model/WeaponsProducer.java` and `model/BuildProductionContainer.java` (production),
tutorial strings in `tt/translations-baseline.csv`.

Categories: Buildings · Economy
