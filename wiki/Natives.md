# Natives

| Natives | |
|---|---|
| **Chieftain** | Chief Ngumba (40 hit points) |
| **Spells** | [Stinking Stew](Magic.md#stinking-stew), [Crackling Cloud](Magic.md#crackling-cloud) |
| **Weapons** | Throwing spears: rock, iron and chicken |
| **Campaign** | [Native Campaign](Native-Campaign.md) |
| **Music** | `native.ogg` |

The **Natives** are the islanders whose home the [Vikings](Vikings.md) invade. They are led by
**Chief Ngumba** and guard a set of holy golden statues. Ngumba learned lightning magic from
the thunder god **Xarrr** ("The Mighty Xarrr"). The Natives were the first to make weapons from
the [Tropical Chicken](Resources.md#tropical-chicken).

## In play

The Natives' units and buildings have exactly the same stats as the Vikings'; see
[Tribes](Tribes.md). Their differences are:

- **A more fragile Chieftain**: 40 hit points against the Vikings' 60. Keep Ngumba out of
  the line of fire.
- **Weather magic and cooking:**
  - [Stinking Stew](Magic.md#stinking-stew) fills a 26-unit radius with poison for 20
    seconds. Enemies inside are poisoned every 2 seconds; your own units are much less
    affected but not immune.
  - [Crackling Cloud](Magic.md#crackling-cloud) sends a thundercloud after the nearest
    enemies for 22 seconds, striking once a second for 30 damage. It reaches enemies behind
    walls and in towers.

## Characters

| Character | Appears in |
|---|---|
| Chief Ngumba | Both campaigns |
| Chief Mozumba (an old, wise chieftain) | Viking island 9 |
| Xarrr the Thunderer (thunder god) | Viking island 10 |
| Nufungu the Not-so-fierce | Native island 0 |
| Zimfubo the Zealous, Lumzumba Lazybones | Native island 1 |
| Fufubi Feeblewit | Native island 4 |
| Chief Gotoba, Ngolongo Nimblefeet | Native island 6 |

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/RacesResources.java`, `player/Player.java`, and the
`player/campaign/*` rows of `tt/translations-baseline.csv`.

Categories: Tribes · Natives
