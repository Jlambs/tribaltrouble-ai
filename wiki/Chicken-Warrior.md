# Chicken Warrior

| Chicken Warrior | |
|---|---|
| **Tribe** | Both |
| **Role** | Elite ranged infantry |
| **Hit points** | 1 |
| **Speed** | 4 |
| **Attack** | Thrown weapon, range 6, can bounce to further targets |
| **Hit chance** | 95% |
| **Damage** | 2 per impact |
| **Defense** | 70% |
| **Weapon cost** | 2 [Wood](Resources.md#wood) + 1 [Rock](Resources.md#rock) + 1 [Iron](Resources.md#iron) + 1 [Tropical Chicken](Resources.md#tropical-chicken) |
| **Weapon build time** | 120 peon-seconds |
| **Deploy time** | 2 seconds per warrior |
| **Unit value** | 10 |

The **Chicken Warrior** carries the game's best weapon, tipped with the beak of a
[Tropical Chicken](Resources.md#tropical-chicken). (In the code the resource is called
"rubber", so you may also see these called *rubber warriors*.) The weapon flies
faster than rock or iron weapons and, after striking, can **bounce** on to another enemy.

## Bouncing

When a chicken weapon lands, it looks for another enemy within a short distance (3 map
cells). If it finds one, there is a 50% chance it bounces on to that target. It can keep
bouncing for as long as the coin flips go its way. Whether a throw hits is decided once, when
it is thrown, so a throw that hits its first target also hits every target it bounces to.

## Chance to hit

Base chance per throw on level ground:

| Target | Chance |
|---|---|
| Peon | 95% |
| Rock Warrior, Chieftain | 47.5% |
| Iron Warrior, Chicken Warrior | 28.5% |
| Building | 95% |

## Use

- Costs one of every resource and three times the workshop time of a rock weapon. Chickens
  have to be hunted, and only a few flocks are on the map at once (see
  [Resources](Resources.md#tropical-chicken)), so chicken weapons come slowly.
- Best against tight groups, where bounces chain through several enemies.
- Some campaign islands disable chicken weapons until you have unlocked them. See
  [Campaigns](Campaigns.md).

## Notes for editors

In the current code the final target of a chicken throw has the damage applied twice (once by
the bounce check and once when the throw ends), so the last impact effectively deals 4 damage.
This only matters against buildings and Chieftains. Check
`model/weapon/RubberAxeWeapon.java` before quoting a damage number.

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/RacesResources.java`,
`model/weapon/RubberAxeWeapon.java`, `model/weapon/RubberSpearWeapon.java`,
`model/LandBuilding.java`.

Categories: Units · Warriors
