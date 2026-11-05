# Rock Warrior

| Rock Warrior | |
|---|---|
| **Tribe** | Both (Vikings throw rock axes, Natives throw rock spears) |
| **Role** | Cheap ranged infantry |
| **Hit points** | 1 |
| **Speed** | 4 |
| **Attack** | Thrown weapon, range 6 |
| **Hit chance** | 50% |
| **Damage** | 1 |
| **Defense** | 50% |
| **Weapon cost** | 2 [Wood](Resources.md#wood) + 1 [Rock](Resources.md#rock) |
| **Weapon build time** | 40 peon-seconds |
| **Deploy time** | 1 second per warrior |
| **Unit value** | 3 |

The **Rock Warrior** is the entry-level soldier: a [Peon](Peon.md) armed with a stone weapon
from the [Armory](Armory.md). Rock weapons are the cheapest and fastest to make, so rock
warriors are what you have first and what you have most of.

## Chance to hit

Base chance per throw on level ground, before [high-ground](Combat-Mechanics.md#high-ground)
or campaign difficulty bonuses:

| Target | Chance |
|---|---|
| Peon | 50% |
| Rock Warrior, Chieftain | 25% |
| Iron Warrior, Chicken Warrior | 15% |
| Building | 50% |

## Use

- Good in numbers early on and for picking off peons.
- Weak against iron and chicken warriors: they need about twice as many throws per kill as an
  iron warrior does.
- Only 1 damage per hit, so they are slow at knocking down buildings.

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/RacesResources.java`,
`model/weapon/RockAxeWeapon.java`, `model/weapon/RockSpearWeapon.java`,
`model/LandBuilding.java` (cost, build and deploy times).

Categories: Units · Warriors
