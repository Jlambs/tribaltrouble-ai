# Spectating

*Resurrected. Spectating was added by the community fork.*

You can watch online games while they are being played, either in the game client or in a web
browser.

## In the game

1. In a chat room, find the player in the **Playing** list.
2. Click the name and choose **Spectate**.
3. The game downloads the match so far, fast-forwards to the present and then follows it
   live.

You need the same game version as the players. If the versions differ, the game says it is
unable to spectate.

### What you see

By default you follow one player's view: their camera, cursor, selections (in their colour),
drag boxes, action panel, building placement, beacons, rally points, Map Mode and
notifications. Move the camera yourself to break away; press **/** to switch back.

| Key | Action |
|---|---|
| **]** | Follow the next player |
| **[** | Follow the previous player |
| **/** | Toggle free camera / follow |
| **Esc** | Stop spectating |

### Chat

Spectators can talk to each other. They see the players' all-chat, but not team chat.

## In a browser

Online games are also streamed to a watch page on the project's website. The Discord bot posts
a **Watch** link for each game in progress (see [Community](Community.md#discord-bot)).

## Recording tips

The camera options added for spectating also help with recording videos:

- **Ctrl+H** hides the HUD.
- **Ctrl+F** toggles the smooth cinematic camera.
- **Ctrl+←/→** starts or stops auto-orbit, and **Ctrl+↑/↓** auto-pan.
- See [Settings](Settings.md#camera) for speeds and camera limits.

## Data sources

`tt/src/main/java/com/oddlabs/tt/input/InputManager.java` (keys),
`delegate/SelectionDelegate.java` (spectator labels), git history of the spectator work
(September 2026).

Categories: Multiplayer · Resurrected
