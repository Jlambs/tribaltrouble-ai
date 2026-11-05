# Viking Campaign

| Viking Campaign | |
|---|---|
| **Tribe** | [Vikings](Vikings.md) |
| **Chieftain** | Chief Fjorleif |
| **Islands** | 15, numbered 0–14 (island 10 is hidden) |
| **Starting roster** | 10 peons |
| **Earned along the way** | Chicken weapons (island 2), [Terrifying Toot](Magic.md#terrifying-toot) (island 4), [Ravaging Roar](Magic.md#ravaging-roar) (island 10) |
| **Next** | [Native Campaign](Native-Campaign.md) |

## Story

After a heavy night of drinking and a thunderstorm that lasts for days, Chief Fjorleif's crew
wakes up shipwrecked among unknown tropical islands. Half the crew is missing, and the Chief
has fallen overboard and been taken captive by the natives. Once rescued, Fjorleif produces a
copy of a native map that shows islands full of **golden statues**. Having lost all their loot,
and not daring to go home empty-handed, the Vikings set off to plunder.

On the way they rescue lost crewmen, learn the natives' secret of the Tropical Chicken,
free Fjorleif's cousin **Gunnar the Gullible** (who teaches the Terrifying Toot on his lur),
and capture the old native chief **Mozumba**, who tells them of a hidden island where the
thunder god **Xarrr** grants wishes. Rival Viking **Egill Egghead** shows up demanding a
looting-licence fee. The campaign ends with an assault on the natives' last stronghold and a
hasty voyage home.

## Islands

| # | Island | Objective | Notes | Unlocks |
|---|---|---|---|---|
| 0 | *(first island)* | Destroy the natives. Rescue the chieftain. | You start with 10 peons and no Chieftain; Fjorleif is held captive near the native base. On Normal and Hard the natives counterattack once your armory stock reaches a threshold. Lose if Fjorleif dies. | 1, 3 |
| 1 | Mobubu | Destroy the natives. Rescue as many captives as possible. | 10 Viking captives near the native base. Survivors join your roster as peons. Lose if all captives die. | 2 |
| 2 | Guzumbabwe | Destroy the natives. | "We have spotted a lot of colourful chickens on this island." The natives field chicken warriors. **Earns chicken weapons.** | 1, 3 |
| 3 | Bongomomu | Loot the natives. | A golden statue island against a full skirmish AI. | 2, 4, 8 |
| 4 | Gobazumbi | Destroy the natives. Rescue Gunnar. | A defended native base with a Chieftain and towers. Lose if Gunnar dies. **Earns Terrifying Toot.** | 5 |
| 5 | Gutshumdi | Destroy the natives. Protect Gunnar's tribe. | Help Gunnar's allied tribe against two native tribes. Gunnar lends you **5 rock warriors**. | 4, 6 |
| 6 | Kosobimba | Destroy the natives. Rescue as many of Gunnar's men as possible. | Stranded allied peons start near you. Survivors join your roster. | 5, 7 |
| 7 | Tukomoba | Loot the natives. | Another statue island, two native tribes. | 6, 8; half of 9 and 11 |
| 8 | Lumumgumphe | Reach the rally point, then do a Terrifying Toot near it. | The ship can't cross the reef, so you cross on foot with your roster as rock warriors and no buildings. Watch for towers and ambushes. Stranded Vikings join you on the way. | 7; half of 9 and 11 |
| 9 | Togongolum | Destroy the natives. Capture chief Mozumba. | Needs islands 7 **and** 8. Heavily fortified. You lose if Mozumba is killed. | 10 (hidden) |
| 10 | Kogafutshlum *(hidden)* | Reach the top of the mountain, then do a Ravaging Roar from the top. | No Quarters, Armory or towers. Fight up the mountain to Xarrr the Thunderer. **Earns Ravaging Roar.** | 13 |
| 11 | Fufupogo | Destroy the Eggheads. | Needs islands 7 **and** 8. The enemy is Egill Egghead's rival Viking tribe. | 12 |
| 12 | Zumtoba | Destroy the natives. Protect the allies. | Bergfinn Bilgekeel's marooned Vikings. Survivors join your roster. | 11, 13 |
| 13 | Ngopazum | Survive for 15 minutes. | The overloaded ship needs repairs. Hold out against six attack waves (at 3.5, 4.5, 6, 9, 11 and 12.5 minutes) from a heavily fortified enemy. | 12, 14 |
| 14 | Hazimumbo | Loot the natives. | The natives' final stronghold. Finishing it completes the campaign and unlocks the Native campaign. | — |

"Half of 9 and 11" means each of islands 7 and 8 provides one of the two unlocks those islands
need.

## Route

```
0 ─┬─ 1 ── 2
   └─ 3 ─┬─ 4 ── 5 ── 6 ── 7 ─┐
         └─ 8 ─────────────────┤   (9 and 11 each need both 7 and 8)
                               ├─ 9 ── 10* ─┐
                               └─ 11 ── 12 ─┴─ 13 ── 14
```

From reading the unlock code, the shortest route is 0 → 3 → 8 → 7 → (9 → 10 or 11 → 12) → 13 →
14. Islands 1, 2 and 4–6 look optional, but skipping 2 and 4 means going without chicken
weapons and the Terrifying Toot, and island 8 asks for a Toot (the game enables it there if
you haven't earned it yet). *Community: please confirm this in play.*

## Tips

- Your roster only grows through rescues and rewards, so the rescue islands (1, 6, 12) pay
  off for the rest of the campaign.
- Islands 8 and 10 give you no economy. Keep the army together and use the Chieftain's spells.
- On island 13, build towers and stock weapons early; the last waves are the biggest.

## Data sources

`tt/src/main/java/com/oddlabs/tt/player/campaign/VikingIsland0.java` through
`VikingIsland14.java`, `CampaignState.java`, and the `player/campaign/VikingIsland*` rows of
`tt/translations-baseline.csv`.

Categories: Single player · Campaigns · Vikings
