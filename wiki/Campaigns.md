# Campaigns

Tribal Trouble has two story campaigns, played island by island on a campaign map.

| Campaign | You play | Chieftain | Islands | Unlocked |
|---|---|---|---|---|
| [Viking Campaign](Viking-Campaign.md) | [Vikings](Vikings.md) | Chief Fjorleif | 15 (numbered 0–14) | From the start |
| [Native Campaign](Native-Campaign.md) | [Natives](Natives.md) | Chief Ngumba | 8 (numbered 0–7) | After finishing the Viking campaign |

The Native campaign picks up the story where the Viking campaign ends, so play them in order.

## Starting a campaign

1. Main menu → **Campaign** → **New Campaign**.
2. Enter a name for the save, and pick a tribe and a difficulty (Easy, Normal or Hard;
   Normal is the default). Picking Natives before the Viking campaign is finished shows
   *"The Viking campaign must be completed first."*
3. The first island starts at once. After that you choose islands on the campaign map; click an
   available island to read its briefing and start it.

## The campaign map

Each island is **available**, **unavailable**, **completed** or **hidden**.

- Winning an island marks it completed (with a flag) and unlocks one or more neighbours.
  Completed islands can't be replayed.
- Some islands need **two** other islands finished before they open.
- Each campaign has one **hidden** island that appears on the map, with a new sea route, only
  when it is unlocked.
- The map is branching: not every island has to be played to reach the end. See each campaign
  page for the unlock graph.

## How a campaign island plays

- You usually start with your **Chieftain** and your **roster**, and no buildings. The roster
  starts at 10 peons and grows only through rewards, such as rescued captives joining as
  peons. Losing units on an island does not shrink it.
- Some islands lock features: no Quarters or Armory, no Chieftain training, or a fixed army
  instead of peons.
- Chicken weapons and spells are **earned** during a campaign. The Vikings start with neither;
  the Natives start with chicken weapons and [Stinking Stew](Magic.md#stinking-stew).
- The usual win condition is that every enemy tribe has **no units left** (including warriors
  in towers) and no Chieftain. Enemy buildings left standing don't matter. Many islands have
  their own objectives instead, shown under *Objectives* in the game menu.
- You lose if your tribe is wiped out, if your Chieftain dies, or if you fail an island's
  special condition (for example, all the captives you were sent to rescue die).
- [Ships](Ship.md) are turned off in campaigns.

## Difficulty

Difficulty works on two levels:

1. **Accuracy.** On **Easy**, your units get a large bonus to hit (+75 percentage points
   before defense is applied). On **Hard**, every enemy tribe gets that bonus instead. On
   **Normal**, nobody does. See [Combat Mechanics](Combat-Mechanics.md#campaign-difficulty).
2. **Island tuning.** Each island also changes with difficulty: enemy population, how smart
   the enemy AI is, when attack waves come and how big they are, the size of ambushes, and how
   many allies or reinforcements you get.

## Saving

- Progress is saved **when you win an island**. There is no saving in the middle of an island.
- Losing, or quitting with *End game*, puts you back on the campaign map before that island.
- **Load Game** lists your campaigns by name, tribe, difficulty and date, and lets you delete
  old ones.
- Saves are kept in a file called `savegames` in the game's data folder (on Steam,
  `save_data/<accountID>.savegames`). See [FAQ](FAQ.md#moving-my-campaign-save-to-steam).

## Data sources

`tt/src/main/java/com/oddlabs/tt/player/campaign/` (`Campaign.java`, `CampaignState.java`,
`Island.java`, `VikingIsland*.java`, `NativeIsland*.java`), `form/NewCampaignForm.java`,
`trigger/campaign/`, and the `player/campaign/*` rows of `tt/translations-baseline.csv`.

Categories: Single player · Campaigns
