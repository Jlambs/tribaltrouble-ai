# Units

Both tribes field the same five unit types with the same stats. Only the models, sounds,
weapons' look (Vikings throw axes, Natives throw spears) and the [Chieftain](Chieftain.md)'s
hit points and spells differ. See [Tribes](Tribes.md) for the full comparison.

Every unit starts life as a **[Peon](Peon.md)** born in a [Quarters](Quarters.md). A warrior
is a peon who has picked up a weapon at an [Armory](Armory.md); the weapon decides the warrior
type. The **[Chieftain](Chieftain.md)** is trained separately and is the only hero unit.

## Comparison

| Unit | Hit points | Speed | Attack | Hit chance | Damage | Range | Defense | Made from |
|---|---|---|---|---|---|---|---|---|
| [Peon](Peon.md) | 1 | 5 | Melee | 20% | 1 (6 vs towers) | Melee | 0% | Born in a [Quarters](Quarters.md) |
| [Rock Warrior](Rock-Warrior.md) | 1 | 4 | Thrown | 50% | 1 | 6 | 50% | Peon + rock weapon |
| [Iron Warrior](Iron-Warrior.md) | 1 | 4 | Thrown | 75% | 2 | 6 | 70% | Peon + iron weapon |
| [Chicken Warrior](Chicken-Warrior.md) | 1 | 4 | Thrown, bounces | 95% | 2 | 6 | 70% | Peon + chicken weapon |
| [Chieftain](Chieftain.md) | 60 (Vikings) / 40 (Natives) | 4 | Melee + [Magic](Magic.md) | 75% | 1 (6 vs towers) | Melee | 50% | Trained in a [Quarters](Quarters.md) |

How to read the table:

- **Hit points.** Ordinary units have a single hit point, so any hit kills them. Fights are
  decided by who lands hits, which is why *hit chance* and *defense* matter more than damage.
  Damage only matters against buildings and Chieftains.
- **Speed** is in the game's distance units per second at normal game speed.
- **Hit chance** is the attacker's base chance to land a hit. **Defense** is the target's
  chance to shrug it off. The real chance of a hit is roughly
  `hit chance × (1 − defense)`, adjusted for high ground. See
  [Combat Mechanics](Combat-Mechanics.md).
- **Range** 6 for all warriors; a warrior posted in a [Tower](Tower.md) gets +8 range.

## Population

- A new game starts each tribe with 20 units by default (a Chieftain is not included).
- The default unit cap is 250 per player. Both numbers can be changed in the advanced game
  settings (*Resurrected*); see [Game Setup](Game-Setup.md).

## Unit value

The game gives each unit a "status value" that feeds the tribe-strength comparison. It is a
fair rule of thumb for what a unit is worth:

| Unit | Value |
|---|---|
| Peon | 1 |
| Rock Warrior | 3 |
| Iron Warrior | 5 |
| Chicken Warrior | 10 |
| Chieftain | 40 |

## Data sources

Unit templates and weapons: `tt/src/main/java/com/oddlabs/tt/model/RacesResources.java`,
`model/weapon/*Weapon.java`, `model/weapon/InstantHitFactory.java`. Starting units and cap:
`common/src/main/java/com/oddlabs/matchmaking/Game.java`.

Categories: Units · Overview
