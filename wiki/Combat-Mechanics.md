# Combat Mechanics

This page explains how fights are resolved, using the rules in the game code. For unit stats
see [Units](Units.md); for spells see [Magic](Magic.md).

## One hit, one kill

[Peons](Peon.md) and all warriors have **1 hit point**. Any hit that does damage kills them.
Only [Chieftains](Chieftain.md) (40 or 60 hit points) and [buildings](Buildings.md) (100 to
250) can take more than one hit, so damage numbers only matter against them. Against
ordinary units, the only question is **whether the throw hits**.

## The hit roll

Each attack makes one roll. The chance to hit is:

```
chance = (hit chance + high-ground bonus + difficulty bonus) × (1 − target defense)
```

| Attacker | Hit chance | | Target | Defense |
|---|---|---|---|---|
| Peon (melee) | 20% | | Peon | 0% |
| Rock Warrior | 50% | | Rock Warrior | 50% |
| Iron Warrior | 75% | | Iron Warrior | 70% |
| Chicken Warrior | 95% | | Chicken Warrior | 70% |
| Chieftain (melee) | 75% | | Chieftain | 50% |
| | | | Any building | 0% |

### Warrior against warrior

Base chance per throw on level ground, with no difficulty bonus:

| Attacker ↓ / Target → | Peon | Rock Warrior | Iron or Chicken Warrior | Chieftain | Building |
|---|---|---|---|---|---|
| **Rock Warrior** | 50% | 25% | 15% | 25% | 50% |
| **Iron Warrior** | 75% | 37.5% | 22.5% | 37.5% | 75% |
| **Chicken Warrior** | 95% | 47.5% | 28.5% | 47.5% | 95% |

### Defense

Defense is the chance that a unit dodges or blocks an attack that would otherwise land. It is
the reason iron and chicken warriors survive so much longer than rock warriors: they shrug off
70% of what reaches them.

A unit stunned by the Vikings' [Terrifying Toot](Magic.md#terrifying-toot) has **zero defense**
until the stun wears off.

### High ground

Attacking from higher ground adds a bonus to the hit chance, and attacking uphill subtracts
one. The bonus is 1.25 percentage points for each unit of height difference between the
attacker's and the target's ground, capped at **±25 points** (reached at a height difference
of 20). The bonus is added *before* defense is applied.

Example: a rock warrior on a hill 20 units above an iron warrior has
`(0.50 + 0.25) × (1 − 0.70) = 22.5%` per throw, the same as an iron warrior on level ground.
The iron warrior throwing back uphill has `(0.75 − 0.25) × (1 − 0.50) = 25%`.

Height is measured from the ground under each unit; a [Tower](Tower.md) does not raise its
garrison for this purpose.

### Campaign difficulty

In the [campaigns](Campaigns.md), difficulty adds a flat **+75 percentage points** to hit
chance: to your units on Easy, to every enemy tribe on Hard, and to nobody on Normal. On Easy,
your rock warriors hit enemy rock warriors 62.5% of the time instead of 25%.

## Damage

| Attack | Damage |
|---|---|
| Rock weapon | 1 |
| Iron weapon | 2 |
| Chicken weapon | 2, and may [bounce](Chicken-Warrior.md#bouncing) to further targets |
| Peon or Chieftain melee | 1; **always 6 against a tower**, with no hit roll |
| Spells | See [Magic](Magic.md) |

Thrown weapons are real projectiles with flight time: rock weapons travel at 20, iron at 25
and chicken at 30 units per second. A throw that is going to hit follows its target.

## Range and automatic targeting

- All warriors throw at range **6**. A warrior in a [Tower](Tower.md) throws at range 14.
- Peons and Chieftains fight in melee.
- Units on attack orders, and towers, pick targets automatically by priority:
  [Ships](Ship.md) first, then warriors and Chieftains, then peons, then towers, then
  Quarters and Armories.
- Units given an **Attack** order on empty ground walk there and attack any enemy they see on
  the way (attack-move).

## Towers and garrisons

A warrior in a tower can't be hit directly: every hit on it damages the tower instead. Peons
and Chieftains deal 6 damage per blow to towers regardless of the hit roll, so they are the
fastest way to bring a tower down.

## Fighting from ships (*Resurrected*)

Throws from the deck of a moving [Ship](Ship.md) land off target by a random amount (up to
about 2.2 units, or 0.2 with a Chieftain on board), and damage is scaled down by how far off
the throw lands. A badly aimed 1-damage throw can round down to no damage at all. Throws
*at* a ship hit one of the units on board, and if the ship sinks, everyone aboard dies.

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/weapon/WeaponFactory.java` (hit roll, high ground),
`model/weapon/ThrowingWeapon.java` (projectiles, ship accuracy),
`model/weapon/InstantHitFactory.java` (melee), `model/AttackScanFilter.java` (target
priority), `model/MountUnitContainer.java` (tower range), `player/campaign/Island.java`
(difficulty bonus), `model/RacesResources.java` (stats).

Categories: Combat · Mechanics
