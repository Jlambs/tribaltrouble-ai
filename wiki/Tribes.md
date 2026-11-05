# Tribes

There are two playable tribes: the seafaring **[Vikings](Vikings.md)** and the island-dwelling
**[Natives](Natives.md)**. They share the same economy, buildings and unit types, with the same
costs and the same stats. What sets them apart is their [Chieftain](Chieftain.md) and the
Chieftain's two spells.

## Side by side

| | Vikings | Natives |
|---|---|---|
| **Chieftain** | Chief Fjorleif | Chief Ngumba |
| **Chieftain hit points** | 60 | 40 |
| **Spell 1 (40 s)** | [Terrifying Toot](Magic.md#terrifying-toot): stuns enemies within 36, removing their defense | [Stinking Stew](Magic.md#stinking-stew): poison cloud, radius 26, for 20 seconds |
| **Spell 2 (70 s)** | [Ravaging Roar](Magic.md#ravaging-roar): shockwave, radius 36, heavy damage, hits friends too | [Crackling Cloud](Magic.md#crackling-cloud): thundercloud that hunts the nearest enemies for 22 seconds |
| **Warrior weapons** | Throwing axes | Throwing spears |
| **Buildings, peons, warriors** | Identical stats | Identical stats |
| **Home terrain** | Cold, northern islands | Tropical islands |
| **Campaign** | [Viking Campaign](Viking-Campaign.md) | [Native Campaign](Native-Campaign.md) |

Axes and spears are purely cosmetic: both fly at the same speed per tier and use the same hit
chances and damage.

## Which tribe should I pick?

- **Vikings** have the tougher Chieftain, and their spells are about **burst**. The Toot sets up
  a decisive engagement; the Roar wins one outright if you can get the Chieftain close. Both
  reward a well-timed push.
- **Natives** have the more fragile Chieftain, and their spells are about **area denial and
  attrition**. The Stew holds a choke point or punishes a crowded base; the Cloud keeps hunting
  on its own and reaches enemies behind towers.

Most of a match is decided by the economy, which is identical for both tribes, so pick the one
whose spells suit how you like to play.

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/RacesResources.java`, `model/Race.java`,
`player/Player.java`.

Categories: Tribes · Overview
