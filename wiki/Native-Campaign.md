# Native Campaign

| Native Campaign | |
|---|---|
| **Tribe** | [Natives](Natives.md) |
| **Chieftain** | Chief Ngumba |
| **Islands** | 8, numbered 0–7 (island 7 is hidden) |
| **Starting roster** | 10 peons |
| **Available from the start** | Chicken weapons, [Stinking Stew](Magic.md#stinking-stew) |
| **Earned along the way** | [Crackling Cloud](Magic.md#crackling-cloud) (island 3) |
| **Unlocked by** | Finishing the [Viking Campaign](Viking-Campaign.md) |

## Story

The Native campaign starts where the Viking one ends. Chief **Ngumba** watches the Vikings
come for the last of the holy statues and slips away "to arrange reinforcements", but comes
back too late: Fjorleif has sailed off with the statues and a few chickens. Ngumba captures
Gunnar the Gullible instead and makes him draw a map of the Viking homelands.

In the cold north, the natives free kinsmen taken as thralls, discover that the Crackling
Cloud doesn't work in the cold, and get help from **Thor**, an old classmate of Xarrr, in
exchange for a pot of Stinking Stew. They raid the Vikings' prison island and Fjorleif's home
island, team up with escaped thralls under **Chief Gotoba**, and finally track Fjorleif to a
secret island to take the statues back.

## Islands

| # | Island | Objective | Notes | Unlocks |
|---|---|---|---|---|
| 0 | *(first island)* | Retreat to the beach and find the reinforcements. Then destroy the Vikings. | Ngumba starts alone while Fjorleif's army attacks the village. Reach the peons on the beach, then defeat Gunnar's camp. | 1 |
| 1 | Völlurströnd | Kill the guards. Then destroy the Vikings. | Gunnar's island. Kill the 3 guards to free 10 kinsmen, who join as peons. Repeated Viking attack waves. The Crackling Cloud doesn't work here. | 2, 5 |
| 2 | Öldurey | Destroy the Vikings. Rescue as many captives as possible. | Survivors join your roster. Lose if all captives die. | 3 |
| 3 | Dyngjaskagi | Go to Thor. Then cook some Stinking Stew for Thor. | No building. Fight past many Viking towers to Thor. **Earns Crackling Cloud.** | 4 |
| 4 | Jökullhnjúkur | Protect the captives for 15 minutes. | The Vikings' prison island. Survivors join your roster. Unlocks nothing. | — |
| 5 | Hvermörkey | Destroy the Vikings. | Fjorleif's home island. | 3, 6 |
| 6 | Varðaskógur | Destroy the Vikings. Protect your allies. | Large map. Help Chief Gotoba's escaped thralls against two Viking tribes. Lose if the allies are wiped out. | 7 (hidden) |
| 7 | Reykuröræfi *(hidden)* | Destroy Fjorleif's tribe and reclaim the statues. | Fjorleif's fortified hideout. Finishing it completes the campaign. | — |

## Route

```
0 ── 1 ─┬─ 2 ── 3 ── 4
        └─ 5 ─┬─ 3
              └─ 6 ── 7*
```

From reading the unlock code, the shortest route is 0 → 1 → 5 → 6 → 7. Islands 2, 3 and 4 look
optional, but island 3 is where you earn the Crackling Cloud. *Community: please confirm this
in play.*

## Data sources

`tt/src/main/java/com/oddlabs/tt/player/campaign/NativeIsland0.java` through
`NativeIsland7.java`, `CampaignState.java`, and the `player/campaign/NativeIsland*` rows of
`tt/translations-baseline.csv`.

Categories: Single player · Campaigns · Natives
