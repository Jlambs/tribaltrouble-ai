# FAQ

## Playing

### Is Tribal Trouble free?

The source code is free under the GPL v2, and builds are available from
[tribaltrouble.org](https://tribaltrouble.org) and GitHub. The Steam release is
*Tribal Trouble: Resurrected*. See [Getting Started](Getting-Started.md).

### Which tribe is better?

Neither. Both tribes have identical economies, buildings and units; only the
[Chieftain](Chieftain.md) and the spells differ. See [Tribes](Tribes.md).

### Why does my warrior die to a single hit?

All ordinary units have 1 hit point. Survival depends on *defense*, the chance to shrug off a
hit: 50% for rock warriors, 70% for iron and chicken warriors, 0% for peons. See
[Combat Mechanics](Combat-Mechanics.md).

### Why can't I start the Native campaign?

It unlocks after you finish the [Viking Campaign](Viking-Campaign.md).

### Why can't I replay a campaign island?

Completed islands are locked on the campaign map. Start a new campaign (you can keep several
under different names) to play them again.

### How do I keep the armory making weapons forever?

In *Produce Weapons*, keep pressing + until an infinity symbol appears.

### Where did my boats go?

[Ships](Ship.md) are switched off in the campaigns and the tutorial, and can be switched off in
a custom game with the **Boats** advanced setting.

## Saves and files

### Moving my campaign save to Steam

The save format is the same; only the location and file name change.

1. Find your old save, a file called `savegames`:
   - Windows: `C:\Users\<you>\TribalTrouble\savegames`
   - macOS: `~/Library/Application Support/TribalTrouble/savegames`
   - Linux: `~/.TribalTrouble/savegames`
2. Find the Steam game folder: in Steam, right-click the game → **Manage → Browse local
   files**. Inside is (or will be) a `save_data/` folder.
3. Launch the game through Steam once, save a campaign, and quit. A file named
   `<accountID>.savegames` appears in `save_data/`; the number is your Steam account ID.
4. Copy your old `savegames` file into `save_data/` and rename it to
   `<accountID>.savegames`, replacing the new one (back that one up first if you want it).

The full guide, with troubleshooting, is in the project's
`docs/migrate-save-to-steam.md`.

## Reporting a bug

Every run of the game writes an `event.log` (plus `std.out` and `std.err`) into a folder named
after the launch time. The event log records all input, so developers can replay your session
exactly and see the bug happen. Attach the folder for the session where the bug happened to
your report.

| Install | Log location |
|---|---|
| Steam | `<Steam game folder>/save_data/logs/<timestamp>/` |
| Portable (a `TribalTrouble` folder next to the game) | `<TribalTrouble>/logs/<timestamp>/` |
| Other installs | The operating system's usual log folder for the game; see the project's `docs/event-logs.md` |

Report bugs in the [Discord](https://discord.gg/tribaltrouble) or as an issue on the project's
GitHub. See [Community](Community.md).

## Technical

### What does the game need to run?

The release builds bundle everything they need. To build from source you need Java 26 and the
included Gradle wrapper; see the project README.

### Which languages are supported?

See [Settings](Settings.md#language). Translations are done by volunteers; see
[Community](Community.md#translating) to help.

Categories: Help · Reference
