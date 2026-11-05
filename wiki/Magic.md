# Magic

Only the [Chieftain](Chieftain.md) can use magic. Each tribe has two spells:

| Tribe | Spell 1 (charges in 40 s) | Spell 2 (charges in 70 s) |
|---|---|---|
| [Vikings](Vikings.md) | [Terrifying Toot](#terrifying-toot): stuns enemies | [Ravaging Roar](#ravaging-roar): shockwave, heavy damage |
| [Natives](Natives.md) | [Stinking Stew](#stinking-stew): poison cloud | [Crackling Cloud](#crackling-cloud): lightning storm that hunts enemies |

## Charging

- The two spells have separate charge meters. Spell 1 is ready 40 seconds after the meter is
  empty, spell 2 after 70 seconds (at normal game speed).
- Casting **either** spell empties **both** meters.
- A newly trained Chieftain starts with both meters empty.
- Cast by selecting the Chieftain and clicking the spell's icon, or with **S** (spell 1) and
  **C** (spell 2) by default (see [Controls](Controls.md)). The Chieftain spends a few seconds on the
  casting animation.
- Some campaign islands lock one or both spells until you have earned them. See
  [Campaigns](Campaigns.md).

## Terrifying Toot

*Vikings, spell 1.* The Chieftain blasts a note on the lur (a long Viking horn), and every
enemy within range freezes in terror.

| | |
|---|---|
| Radius | 36 |
| Affects | Enemy units, including warriors posted in towers |
| Effect | Stun: 30 seconds at the centre, falling to 10 seconds at the edge |
| Damage | None |

Stunned units cannot act, and their [defense](Combat-Mechanics.md#defense) drops to zero for
the duration, so every throw at them lands with the thrower's full hit chance. An iron warrior
normally has a 22.5% chance per throw against another iron warrior; against a stunned one it is
75%.

Stun time falls off with distance: roughly 19 seconds at distance 5, 14 at distance 10 and 11
at distance 20.

## Ravaging Roar

*Vikings, spell 2.* A deafening blast from the lur sends a shockwave rolling outwards from the
Chieftain.

| | |
|---|---|
| Radius | 36, reached over 0.8 seconds |
| Affects | **Everything in range except the Chieftain: enemy and friendly units and buildings** |
| Damage | 150 at point blank, about 70 at distance 2, about 37 at distance 5, then levelling off at 30 |
| Hit chance | Certain at close range; at the edge, certain against peons, rock warriors and buildings, about 60% against iron and chicken warriors |

The Ravaging Roar is the strongest single attack in the game. Since ordinary units have one
hit point, the 30-damage floor is still a kill, and buildings and Chieftains caught close to the
blast take heavy damage. **It does not spare your own side**: pull your own warriors back
before casting.

## Stinking Stew

*Natives, spell 1.* The Chieftain cooks up a cauldron of stew so foul that the fumes are
poisonous.

| | |
|---|---|
| Radius | 26, centred where it was cast |
| Duration | 20 seconds, poisoning every 2 seconds (10 pulses) |
| Affects | Units only (not buildings) |
| Damage | 10 per poisoning |
| Chance per pulse | 50% × (1 − defense) for enemies; a quarter of that for your own units |

Each pulse kills an enemy peon half the time, a rock warrior 25% of the time and an iron or
chicken warrior 15% of the time. A unit that stays in the fumes for the full 20 seconds is
almost certainly dead, so the stew is best on a crowd that can't get out quickly: a choke point,
a group gathering at a resource, or units massed around their own buildings.

Your own units are "used to the stew" and are affected only a quarter as often, but they can
still die in it.

## Crackling Cloud

*Natives, spell 2.* The Chieftain summons a thundercloud that hunts the enemy for 22 seconds.

| | |
|---|---|
| Duration | 22 seconds |
| Movement | Drifts towards the nearest enemy unit or building at 8 units per second |
| Strikes | At most one lightning bolt per second |
| Damage | 30 per strike |
| Hit chance | 100% × (1 − defense) |

The cloud picks its own targets, always the nearest enemy, so it is good at reaching enemies
behind walls and in fortified camps. A warrior in a [Tower](Tower.md) can't be hit directly;
strikes on it damage the tower instead, and 30 damage a strike adds up fast against a tower's
100 hit points.

## Data sources

Spell parameters: `tt/src/main/java/com/oddlabs/tt/model/RacesResources.java` (the
`PoisonFogFactory`, `LightningCloudFactory`, `StunFactory` and `SonicBlastFactory` lines).
Behaviour: `model/weapon/PoisonFog.java`, `LightningCloud.java`, `Stun.java`,
`SonicBlast.java`. Charge times: `model/Unit.java` (`MAX_MAGIC_ENERGY`). Spell names:
`gui/Icons` and `form/ProgressForm` rows in `tt/translations-baseline.csv`.

Categories: Magic · Combat · Vikings · Natives
