# Tower

| Tower | |
|---|---|
| **Tribe** | Both |
| **Hit points** | 100 |
| **Footprint** | 3 × 3 |
| **Cost** | 20 [Wood](Resources.md#wood) |
| **Garrison** | One warrior |
| **Range bonus** | +8 (warrior range 6 → 14) |
| **Default key** | T |

The **Tower** is a defensive post. Put one warrior in it and the warrior attacks every enemy
in range on its own, with more than double its usual range.

## Using a tower

- **Garrison:** select a [Rock](Rock-Warrior.md), [Iron](Iron-Warrior.md) or
  [Chicken](Chicken-Warrior.md) warrior and right-click the tower. Peons and the Chieftain
  can't man towers.
- **Empty:** select the tower and click **Exit** (default **X**) to bring the warrior down.
- An empty tower does nothing.

## Protection

The warrior inside **can't be killed while the tower stands**. Hits on the warrior damage the
tower instead. The weapon in the tower still matters: an iron or chicken warrior in a tower
hits far more often than a rock warrior.

The garrison is not immune to magic: [Terrifying Toot](Magic.md#terrifying-toot) stuns it, and
[Crackling Cloud](Magic.md#crackling-cloud) strikes damage the tower.

## Taking a tower down

Melee blows from [Peons](Peon.md) and the [Chieftain](Chieftain.md) always deal **6 damage**
to a tower, hit roll or not. Fewer than 20 peon blows destroy one. Thrown weapons need their
normal hit roll and deal 1–2 damage, so warriors are much slower at it. Send peons at towers,
and keep warriors back to shoot whatever comes out to stop them.

## Placement tips

- Towers are best at guarding buildings, gathering peons, and narrow passages.
- Remember the [high-ground](Combat-Mechanics.md#high-ground) bonus comes from the ground
  height, not the tower's height. A tower on a hill is better than a tower in a valley.

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/MountUnitContainer.java` (garrison, range bonus),
`model/Unit.java` (damage redirect), `model/weapon/InstantHitFactory.java` (6 damage melee),
`model/RacesResources.java`.

Categories: Buildings · Defense
