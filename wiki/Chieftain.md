# Chieftain

| Chieftain | |
|---|---|
| **Tribe** | Both: Chief Fjorleif (Vikings), Chief Ngumba (Natives) |
| **Role** | Hero and spellcaster |
| **Hit points** | 60 (Vikings), 40 (Natives) |
| **Speed** | 4 |
| **Attack** | Melee, 75% hit chance, 1 damage (6 against towers) |
| **Defense** | 50% |
| **Spells** | Vikings: [Terrifying Toot](Magic.md#terrifying-toot), [Ravaging Roar](Magic.md#ravaging-roar). Natives: [Stinking Stew](Magic.md#stinking-stew), [Crackling Cloud](Magic.md#crackling-cloud) |
| **Trained at** | [Quarters](Quarters.md) |
| **Limit** | One at a time |
| **Unit value** | 40 |

The **Chieftain** leads the tribe and is its only spellcaster. Unlike every other unit, a
Chieftain has many hit points, so a single lucky throw is not fatal. The Chieftain's real value
is a pair of spells that can turn a battle on their own.

## Training

1. Move peons into a [Quarters](Quarters.md).
2. Select the Quarters and click **Train Chieftain** (default key **C**).

Training uses the Quarters' birth cycle: the time it would take the Quarters to produce
**40 peons** goes into the Chieftain instead, and the Quarters makes no peons while it trains.
The more peons inside, the faster this goes. With 8 peons inside, training takes about 3
minutes 40 seconds at normal speed; with 27 it takes about 2.5 minutes; with 64, just under 2
minutes. Clicking Train Chieftain again cancels training, and the progress is lost.

You can only have one Chieftain alive or in training at a time. If the Chieftain dies you can
train another. In the [campaigns](Campaigns.md), losing your Chieftain loses the island, and some islands
don't allow Chieftain training at all.

## Magic

Each Chieftain has two spells with separate charge meters. The first spell charges in **40
seconds** and the second in **70 seconds**. Casting either spell empties *both* meters, so
you can't cast them back to back. A new Chieftain starts with empty meters. See
[Magic](Magic.md) for what each spell does.

## On ships (*Resurrected*)

A Chieftain can board a [Ship](Ship.md). With the Chieftain on board, the ship rows twice as
fast and the warriors on it throw far more accurately.

## Tips

- Keep the Chieftain behind the front line. 40 or 60 hit points goes quickly under concentrated fire, and
  the Native Chieftain is the more fragile of the two.
- Time spells with your attacks. A Terrifying Toot right before your army closes in, or a
  Stinking Stew dropped on a crowded choke point, is worth a dozen warriors.

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/RacesResources.java` (templates and hit points),
`model/ChieftainContainer.java` and `model/ReproduceUnitContainer.java` (training),
`model/Unit.java` (magic charge), `model/behaviour/SailBehaviour.java` and `model/Unit.java`
(ship bonuses), `player/Player.java` (names).

Categories: Units · Heroes · Magic
