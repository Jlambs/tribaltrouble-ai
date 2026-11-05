# Economy

Tribal Trouble's economy is a loop of four steps, and every step runs on [Peons](Peon.md):

```
Quarters ──breed──▶ Peons ──gather──▶ Armory stock ──produce──▶ Weapons ──deploy──▶ Warriors
    ▲                                                                                   │
    └──────────────────────── more peons inside = faster breeding ──────────────────────┘
```

1. **Breed** peons in the [Quarters](Quarters.md).
2. **Gather** [resources](Resources.md) and bring them to the [Armory](Armory.md).
3. **Produce** weapons in the Armory with the peons inside.
4. **Deploy** warriors: one peon plus one weapon each.

Every peon you turn into a warrior stops breeding, gathering and making weapons. Balancing
how many peons do each job is most of the economic game.

## The three peon jobs

| Job | Where | What more peons do |
|---|---|---|
| Breeding | Inside a Quarters | More peons per second, with diminishing returns (cube root) |
| Gathering | Out on the island | More loads per minute, in direct proportion |
| Making weapons | Inside an Armory | More weapons per minute, in direct proportion |

Breeding is the only job with diminishing returns, which is why extra Quarters pay off. Two
Quarters with 20 peons each breed about 60% faster than one with 40.

## Costs at a glance

| Item | Wood | Rock | Iron | Chicken | Work |
|---|---|---|---|---|---|
| [Quarters](Quarters.md) | 40 | | | | 200 peon-seconds of building |
| [Armory](Armory.md) | 40 | | | | 200 peon-seconds of building |
| [Tower](Tower.md) | 20 | | | | 100 peon-seconds of building |
| [Ship](Ship.md) (*Resurrected*) | 50 | | | | 250 peon-seconds of building |
| Rock weapon | 2 | 1 | | | 40 peon-seconds |
| Iron weapon | 2 | | 1 | | 80 peon-seconds |
| Chicken weapon | 2 | 1 | 1 | 1 | 120 peon-seconds |
| [Chieftain](Chieftain.md) | | | | | The Quarters' time to breed 40 peons |

"Building" time counts only the hammering. Walking to fetch each piece of wood comes on top.

## Gathering efficiently

- Peons carry **one** load per trip, so trip length is everything. Build an Armory next to
  the resource you need most, often a wood line or an iron deposit.
- Use *Gather Resources* in the Armory to assign peons by resource type; they keep going
  until given new orders.
- Use *Transport Resources* to send peons out already holding wood when you need to build
  far from the Armory.
- Press **N** to cycle through idle peons.

## Data sources

See the pages linked above; formulas come from `ReproduceUnitContainer.java` (breeding),
`BuildProductionContainer.java` and `WeaponsProducer.java` (weapons), `RepairBehaviour.java`
(construction) and `LandBuilding.java` (costs), all under
`tt/src/main/java/com/oddlabs/tt/model/`.

Categories: Economy · Mechanics
