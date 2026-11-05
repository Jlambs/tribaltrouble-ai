# Game Setup

The same setup screen is used for single player games (Main menu → **Single player**) and for
online games (**Create new game** in [Multiplayer](Multiplayer.md)). Every island is generated
from a random seed and a few sliders, so no two maps are the same unless you share a
[map code](#map-codes).

## Game mode

There is one game mode, **Standard**: the original rules. A tribe is defeated when it has **no
units, no Chieftain and no Quarters** left. The last team with a living tribe wins.

## Map options

| Option | Values | Notes |
|---|---|---|
| **Island size** | Small, Medium, Large, Enormous, Archipelago | Medium is the default. Each step up doubles the side length (256, 512, 1024, 2048). Enormous and Archipelago are *Resurrected* additions. |
| **Terrain** | Tropical, Northern | The look of the island: the Natives' tropics or the Vikings' north. |
| **Hills** | Min to Max (11 steps) | More hills means more high ground to fight over. |
| **Trees** | Min to Max | How much wood is on the island. |
| **Resources** | Min to Max | How much rock, iron and other supplies. |

### Archipelago (*Resurrected*)

Archipelago is an Enormous-size map split into separate islands. Every player starts at sea on
[ships](Ship.md), with one boat for every 26 starting units, and must land to build a base.
Boats are always on for this map type.

## Players

| Option | Values |
|---|---|
| **Slots** | Up to 12 players (the original game allowed 6) |
| **Controller** | Single player: Closed, Easy AI, Normal AI, Hard AI. Online games add **Open** for a human player. |
| **Tribe** | [Natives](Natives.md) or [Vikings](Vikings.md) |
| **Team** | Team 1 to Team N. At least two teams are needed to start. |

By default in single player you are on Team 1 and every AI is on Team 2; online, every slot
starts on its own team.

### Computer opponents

All three AI levels use the same AI with different settings:

| | Easy | Normal | Hard |
|---|---|---|---|
| Gatherers it aims for (wood / rock / iron / chicken) | 2 / 1 / 1 / 0 | 5 / 3 / 3 / 0 | 15 / 9 / 10 / 3 |
| Defensive strength | Low | Medium | High |
| Builds towers | No | No | Yes, once it has a large population |
| Other | Waits to build up a weapon stockpile before attacking | | |

Only the Hard AI hunts chickens.

## Advanced settings (*Resurrected*)

Click **Advanced...** to change the game's limits. **Reset to defaults** puts them back.

| Setting | Range | Default |
|---|---|---|
| Max units (per player) | 20–1000, in steps of 10 | 250 |
| Starting units | 1 up to Max units | 20 |
| Max buildings (per player) | 1–100 | 20 |
| Boats | On / off ("Allow ships in this game") | Off (always on for Archipelago) |

Values above the defaults can slow the game down. Advanced settings are locked to their
defaults in rated games.

## Game speed

| Speed | Relative to Normal |
|---|---|
| Paused | 0× |
| Slow | 0.5× |
| Normal | 1× |
| Fast | 1.75× |
| Ludicrous | 4× |

In single player, change speed at any time with **+** and **−** (numpad or main keyboard). In
online games each player states a preferred speed, and the game only changes speed when every
player who has stated one agrees.

All times quoted on this wiki are at Normal speed.

## Map codes

Every island has a **map code**: a short phrase of ordinary English words. It records the
seed, island size, terrain type and the three sliders, so entering the same code produces the
same island. Players, tribes and teams are not part of the code.

- The current code is shown in the setup screen and on the end-of-game statistics screen.
- **Enter map code...** lets you type one in; **Randomize** rolls a new island.
- Codes from the original game (letters separated by dashes) are still accepted.

Share map codes for fair matches, rematches and tournaments.

## Presets (*Resurrected*, online games only)

A preset saves the whole setup (map options, the Rated setting and the slot layout, including
which slots are AI and on which team) under a name.

- **+ Save current as preset** saves the current setup.
- Click a preset card to load it. If you change things afterwards, the banner shows
  "(modified)" with **Reset** and **Update** buttons.
- Presets are stored on your computer (`presets.json` in the game's data folder).

## After the game

The statistics screen shows, per player: units lost and killed, buildings lost and wrecked,
resources gathered of each type, metres walked, weapons thrown, spells cast and a total score.
In single player you can **Replay island** (same map, fresh start) or enter **Observer mode**.

## Data sources

`tt/src/main/java/com/oddlabs/tt/form/TerrainMenu.java`, `form/AdvancedSettingsForm.java`,
`form/ModeAndPresetsPanel.java`, `gamemode/standard/StandardModeRules.java`,
`player/AdvancedAI.java`, `landscape/World.java` (speeds), `delegate/GameStatsDelegate.java`,
`common/src/main/java/com/oddlabs/matchmaking/Game.java`.

Categories: Multiplayer · Single player · Reference
