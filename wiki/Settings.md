# Settings

Open **Options** from the main menu or the in-game menu. Options are grouped in tabs.

## General

| Setting | What it does |
|---|---|
| Aggressive units | Units attack enemies they see while moving (also Ctrl+A in game). Off by default. |
| Show compass | Compass in the bottom-right corner. On by default. |
| Game speed | Default speed for single player games. |
| Tool tip delay | How long to hover before tool tips appear. |
| Multiplayer Domain | Which server to use for [online play](Multiplayer.md). Leave on the default unless you are joining a community server. |

## Graphics

| Setting | What it does |
|---|---|
| Graphical detail | Low, Medium or High. Takes effect the next time the game starts. |
| Display mode | Resolution and refresh rate. |
| UI Scale | Makes the interface bigger, from 100% up to what fits your screen. |
| Fullscreen | Fullscreen or windowed (not on macOS). |
| Confine cursor to window | Keeps the mouse inside the game window (not on macOS). |

## Camera

Pan, rotate and zoom speeds; whether panning speeds up the longer you hold it; the cinematic
camera's speed; whether the cinematic camera may go beyond the normal limits; and inverted
axes. See [Spectating](Spectating.md#recording-tips) for the recording camera controls.

## Keyboard

Every action can be rebound. Actions are grouped (General, Camera, Interface, Unit, Army
Groups, Production, Training, Resources, Magic, Game), with a filter box to find one quickly.

- Clear or reset a single binding, or **Reset All**.
- If a new key clashes with another action in the same situation, the game offers to swap
  them.
- **Export** and **Import** save and load your bindings as a JSON file, which is handy for
  sharing layouts.

See [Controls](Controls.md) for the defaults.

## Accessibility

| Setting | What it does |
|---|---|
| High Contrast | Raises contrast across the game, with an intensity slider. |
| Colour Mode | Standard, Protanopia, Deuteranopia or Tritanopia correction, with an intensity slider. |
| Team colours | Change the colour of each of the 12 player slots, or reset them. Defaults: orange, royal blue, red, teal, purple, lime, yellow, hot pink, dark green, maroon, sky blue, white. |
| Team Overlay | Tints units and buildings in their team's colour, so sides are easy to tell apart. |

Together with UI Scale (Graphics) and full key remapping (Keyboard), these came from the
community's restoration work. See [History](History.md).

## Sound

| Setting | What it does |
|---|---|
| Music | On/off and volume. |
| Sound effects | On/off and volume. |
| Audio Output | Speakers or Headphones (3D sound for headphones, if your hardware supports it). |
| Classic Audio | The original sound mix. On by default; turn it off to choose an Audio Output. |

## Language

| Language | |
|---|---|
| English | |
| Dansk (Danish) | |
| Deutsch (German) | |
| Español (Spanish) | |
| Italiano (Italian) | |
| Português brasileiro (Brazilian Portuguese) | |

*System default* picks your computer's language if it is on the list. Changes take effect
the next time the game starts. A Polish translation is in progress. To help translate, see
[Community](Community.md#translating).

## Where settings are saved

| Install | Folder |
|---|---|
| Steam | `save_data/` in the game's install folder (synced with Steam Cloud) |
| Windows | `%APPDATA%\TribalTrouble` |
| macOS | `~/Library/Application Support/TribalTrouble` |
| Linux | `~/.config/tribaltrouble` |
| Portable | A `TribalTrouble` folder next to the game |

## Data sources

`tt/src/main/java/com/oddlabs/tt/form/` (the options panels), `global/Settings.java`,
`gui/Languages.java`, `render/Renderer.java` (data folders), and the `form/OptionsMenu` rows
of `tt/translations-baseline.csv`.

Categories: Reference · Settings · Accessibility
