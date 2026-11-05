# Iron Warrior

| Iron Warrior | |
|---|---|
| **Tribe** | Both (Vikings throw iron axes, Natives throw iron spears) |
| **Role** | Main-line ranged infantry |
| **Hit points** | 1 |
| **Speed** | 4 |
| **Attack** | Thrown weapon, range 6 |
| **Hit chance** | 75% |
| **Damage** | 2 |
| **Defense** | 70% |
| **Weapon cost** | 2 [Wood](Resources.md#wood) + 1 [Iron](Resources.md#iron) |
| **Weapon build time** | 80 peon-seconds |
| **Deploy time** | 1.5 seconds per warrior |
| **Unit value** | 5 |

The **Iron Warrior** is a [Peon](Peon.md) armed with an iron weapon. It hits more often, hits
harder and survives far more often than a [Rock Warrior](Rock-Warrior.md), at the cost of
iron and twice the workshop time.

## Chance to hit

Base chance per throw on level ground:

| Target | Chance |
|---|---|
| Peon | 75% |
| Rock Warrior, Chieftain | 37.5% |
| Iron Warrior, Chicken Warrior | 22.5% |
| Building | 75% |

## Use

- The backbone of most mid-game armies. Against rock warriors an iron warrior lands hits 2.5
  times as often as it is hit back (37.5% versus 15%).
- 2 damage per hit makes iron warriors twice as good as rock warriors at tearing down
  buildings.
- Iron is usually scarcer than rock and wood. Secure an iron deposit early.

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/RacesResources.java`,
`model/weapon/IronAxeWeapon.java`, `model/weapon/IronSpearWeapon.java`,
`model/LandBuilding.java`.

Categories: Units · Warriors
