# Quarters

| Quarters | |
|---|---|
| **Tribe** | Both |
| **Hit points** | 200 |
| **Footprint** | 5 × 5 |
| **Cost** | 40 [Wood](Resources.md#wood) |
| **Produces** | [Peons](Peon.md), the [Chieftain](Chieftain.md) |
| **Default key** | Q |

The **Quarters** is the tribe's home. Peons inside it breed new peons, and it is where the
[Chieftain](Chieftain.md) is trained. Every army you build starts here.

## Breeding

Move peons into the Quarters (right-click it with peons selected). New peons are born inside
over time. The more peons inside, the faster they arrive, though the gain tapers off:

| Peons inside | One new peon every |
|---|---|
| 0 | ~14 seconds |
| 1 | 11 seconds |
| 8 | 5.5 seconds |
| 27 | ~3.7 seconds |
| 64 | 2.75 seconds |

The rate is `cube root of (peons inside) ÷ 11` peons per second at normal speed, with an empty
Quarters counting as half a peon. Because of the cube root, two Quarters with 20 peons each
breed faster than one Quarters with 40. Building extra Quarters is the way to speed up your
population.

Breeding stops while the tribe is at its unit cap (250 by default), and while the Quarters is
training a Chieftain.

## Moving peons out

Newborn peons wait inside. Select the Quarters and use the **+** icon to send peons out
(right-click the + and − icons to change the number by 10). Peons leaving a Quarters that has
no rally point walk to the nearest [Armory](Armory.md) and go inside.

## Rally point

Select the Quarters, click **Rally Point** (default **R** when a building is selected) and
click on the map. Peons leaving the Quarters will walk there instead.

## Training the Chieftain

Click **Train Chieftain** (default **C**). Training uses the Quarters' birth cycle, so the more
peons inside, the faster it goes. See [Chieftain](Chieftain.md#training).

## Data sources

`tt/src/main/java/com/oddlabs/tt/model/ReproduceUnitContainer.java` (breeding rate),
`model/LandBuilding.java` (deploy and rally point), `model/behaviour/TransferUnitController.java`,
`model/RacesResources.java`.

Categories: Buildings · Economy
