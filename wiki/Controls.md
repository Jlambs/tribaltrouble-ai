# Controls

These are the default controls. Every key can be changed in **Options → Keyboard** (see
[Settings](Settings.md#keyboard)), so check there if a key below doesn't work for you. On
macOS, Ctrl-click works as a right-click.

## Mouse

| Action | Effect |
|---|---|
| Left-click | Select a unit or building |
| Left-drag | Box-select units |
| Shift+click / Shift+drag | Add to or remove from the selection |
| Double-click a unit | Select all on-screen units of that kind (warriors, peons or ships) |
| Right-click | Context order: move, attack, gather, enter a building, or (for ships) sail |
| Mouse wheel | Zoom |
| Hold middle button and move | Turn the camera (free look) |
| Push against the screen edge | Pan |
| Hover over a unit or building | Show its status |

## Camera

| Key | Action |
|---|---|
| Arrow keys | Pan |
| Page Up / Page Down (numpad 9 / 3) | Zoom in / out |
| Hold **Z** and move the mouse | Zoom |
| Insert / Delete (numpad 6 / 4, Alt+→ / Alt+←) | Rotate right / left |
| Home / End (numpad 8 / 2, Alt+↑ / Alt+↓) | Look up / down |
| Hold **F** and move the mouse | Free look |
| **Space** or numpad 5 | Map Mode (click the map to jump there) |
| **Tab** | Jump to the latest notification (the flashing arrow) |
| Ctrl+F | Cinematic camera on/off |
| Ctrl+← / Ctrl+→ | Auto-orbit |
| Ctrl+↑ / Ctrl+↓ | Auto-pan forward / back |

## Selection and groups

| Key | Action |
|---|---|
| Ctrl+0–9 | Store the selection as a group |
| 0–9 | Select a stored group |
| 0–9 twice | Select the group and move the camera to it |
| **N** | Cycle through idle peons |

## Unit orders

With units selected:

| Key | Action |
|---|---|
| **M** | Move (ships: Sail) |
| **A** | Attack (click a target, or the ground to attack-move) |
| **G** | Gather / Repair |
| **Q** | Build a [Quarters](Quarters.md) |
| **R** | Build an [Armory](Armory.md) |
| **T** | Build a [Tower](Tower.md) |
| **S** | Build a [Ship](Ship.md) (*Resurrected*) |
| **X** | Exit (take the warrior out of a tower) |
| **S** | [Chieftain](Chieftain.md): spell 1 ([Terrifying Toot](Magic.md#terrifying-toot) / [Stinking Stew](Magic.md#stinking-stew)) |
| **C** | Chieftain: spell 2 ([Ravaging Roar](Magic.md#ravaging-roar) / [Crackling Cloud](Magic.md#crackling-cloud)) |
| Backspace | Back |

## Building menus

With a Quarters or Armory selected:

| Key | Action |
|---|---|
| **R** | Set rally point |
| **C** | Train Chieftain (Quarters) |
| **G** | Gather Resources (Armory) |
| **W** | Produce Weapons (Armory) |
| **A** | Deploy Army (Armory) |
| **T** | Transport Resources (Armory) |
| **Esc** or Backspace | Back to the building's main menu |

Inside a sub-menu, the counters have their own keys: **W** wood, **R** rock, **I** iron, **C**
chicken, **P** peons, **H** Chieftain. The key alone adds 1; with **Shift** it removes 1; with
**Ctrl** it adds 10; with **Ctrl+Shift** it removes 10. With the mouse, right-click the + and −
icons to change counts by 10.

## Game

| Key | Action |
|---|---|
| **Esc** | Game menu |
| **Enter** | Chat to everyone |
| **Shift+Enter** | Chat to your team |
| Ctrl+B, then click | Place a beacon for your team |
| **+** / **−** (numpad or main keyboard) | Game speed up / down |
| Ctrl+A | Aggressive units on/off (units attack enemies they see while moving) |
| Ctrl+H | Hide or show the HUD |
| Ctrl+I | Show or hide the status display (frame rate and so on) |
| Ctrl+S or Ctrl+P | Screenshot |
| F11 | Fullscreen on/off |
| Ctrl+Q (Cmd+Q on Mac) | Quit |

## Spectating

| Key | Action |
|---|---|
| **]** / **[** | Follow next / previous player |
| **/** | Free camera / follow |
| **Esc** | Stop spectating |

## Menus

Tab and Shift+Tab move between fields, Ctrl+Tab switches panels, Enter or Space activates,
and Esc cancels.

## Data sources

`tt/src/main/java/com/oddlabs/tt/input/InputManager.java` (default bindings), loading-screen
tips and tutorial text in `tt/translations-baseline.csv`.

Categories: Reference · Controls
