# Peon

| Peon | |
|---|---|
| **Tribe** | Both |
| **Role** | Worker |
| **Hit points** | 1 |
| **Speed** | 5 (fastest unit on foot) |
| **Attack** | Melee, 20% hit chance, 1 damage (6 against towers) |
| **Defense** | 0% |
| **Carries** | 1 resource at a time |
| **Produced at** | [Quarters](Quarters.md) |
| **Unit value** | 1 |

The **Peon** is the worker of both tribes and the raw material for every army. Peons gather
[resources](Resources.md), carry them to an [Armory](Armory.md), construct and repair
buildings, staff the armory's weapon workshop, and become warriors when they pick up a weapon.

## Abilities

- **Gather.** A peon harvests one load of wood, rock, iron or [Tropical Chicken](Resources.md#tropical-chicken)
  and walks it to the nearest Armory. The *Gather/Repair* command on a resource sends it to
  that resource type and it keeps going until told otherwise.
- **Build and repair.** Construction and repair are the same job: a peon fetches one piece of
  wood, then spends five seconds hammering, adding 5 points to the building. A
  [Quarters](Quarters.md) or [Armory](Armory.md) takes 40 wood; a [Tower](Tower.md) takes 20.
- **Breed.** Peons sent into a Quarters increase its birth rate. See [Quarters](Quarters.md).
- **Make weapons.** Peons inside an Armory produce weapons. More peons, faster production. See
  [Armory](Armory.md).
- **Fight.** Peons can attack, but with a 20% hit chance and no defense they lose to any
  warrior. Their one real combat use: a peon's melee blow always deals 6 damage to a
  [Tower](Tower.md), so a crowd of peons can tear a tower down quickly.

## Tips

- Peons are the fastest unit on foot, so they are good scouts early on.
- Losing peons hurts twice: they are your economy *and* your future army. Keep gathering
  routes away from enemy towers.
- Set a [rally point](Quarters.md#rally-point) on your Quarters so newborn peons walk straight
  to work.

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/RacesResources.java` (peon templates),
`model/weapon/InstantHitFactory.java` (melee), `model/behaviour/RepairBehaviour.java`
(construction).

Categories: Units · Workers
