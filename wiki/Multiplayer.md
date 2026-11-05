# Multiplayer

Online play runs through the community's matchmaking server at `tribaltrouble.org`, the
replacement for Oddlabs' original servers. You can chat, set up games, play rated matches,
climb the leaderboard and [spectate](Spectating.md) games in progress.

## Logging in

- **Steam:** if you launch through Steam, you are logged in automatically. The first time, you
  are asked to create a profile (choose a nickname).
- **Other builds:** log in with a username and password, or create a new account (username,
  email and a password of at least 6 characters). Tick *Remember login* to skip this next time.

### Profiles

One account can hold several **profiles** (nicknames), each with its own rating and record.
The profile list shows each profile's Rating, Wins, Losses and Invalid games. Nicknames use
letters, digits and underscores. A new profile starts at a rating of 1000.

## Chat

- After logging in you join a chat room automatically. The **Chat** tab lists rooms and lets
  you create or join one.
- Each room shows who is in the lobby and who is playing. Click a name for **Profile info**,
  **Message**, **Ignore** or, for players in a game, **Spectate**.
- Some chat rooms are linked to the community [Discord](Community.md#discord-bot) server, so
  messages pass both ways.

### Chat commands

| Command | Effect |
|---|---|
| `/msg <nick> <text>` | Private message (also `/message`, `/tell`, `/whisper`) |
| `/info <nick>` | Show a player's profile: rating, wins, losses, invalid games (also `/finger`) |
| `/ignore <nick>` | Hide a player's messages and beacons |
| `/unignore <nick>` | Stop ignoring a player |
| `/ignorelist` | List ignored players |

In a game, **Enter** chats to everyone and **Shift+Enter** to your team. **Ctrl+B** and a click
places a beacon your team can see.

## Finding and hosting games

The **Games** tab lists open games with their name, game speed, map size and whether they are
rated. **Create new game** opens the [Game Setup](Game-Setup.md) screen with extra
online-only options (game name, Rated, game speed, presets). Set slots to **Open** for human
players or to an AI level.

In the lobby every player picks a tribe and team and clicks **Ready**. The host can start once
everyone is ready and there are at least two teams. **Game info** shows all settings, including
the game version.

Games made with a different game version are greyed out; everyone in a game must run the same
version.

## Quitting and results

- The first couple of minutes of a game are a **free quit** period, shown in the game menu.
  Leaving during it doesn't count as a loss.
- Leaving after that counts as a loss.
- If the players' reported results disagree, the server decides from the game's progress. If
  it can't, the game is marked **invalid** for everyone in it, which shows in each profile's
  Invalid count.
- Wins and losses only count in games where a human team lost. Games against the AI alone
  don't change your record.

## Rated games and the League

A game can be marked **Rated** when you create it. Rated games:

- need every player to have **won at least 5** normal games first,
- are humans only, on exactly two teams,
- always use the default advanced settings.

Ratings use an **Elo** system on team totals. Each team's rating is the sum of its players'
ratings. With `N` human players, `K = 16 × N`, and the expected score of a team is
`1 / (1 + 10^((other team's rating − this team's rating) / 400))`. The winners gain
`K × (1 − expected)` and the losers lose `K × expected`, split evenly between the members of
each team. The lobby shows how much you stand to win or lose before the game starts.

**The League** tab lists the top 50 profiles (with at least 5 wins) by rating, then by wins
minus losses.

### OpenSkill ladder (*Resurrected*)

The server also keeps an **OpenSkill** rating for every finished online game, rated or not,
including games against the AI (Easy, Normal and Hard AI are rated as if they were players).
Shown ratings start at 1000 and are marked with "?" while still provisional. To see this ladder,
**Shift+click the League tab**; it lists the top 50 and your own position. Click the tab
normally to switch back.

## Connection problems

- The game runs in lockstep: every player's computer simulates the same game. If two copies
  drift apart (a desync), the player who is out of step is dropped with a message such as
  "Synchronization failed". After a desync, the game offers to send a bug report on the next
  start; please do.
- There is no way to rejoin a game once you have left or been dropped.

## Community servers

Options → General → **Multiplayer Domain** points the game at a different server. Leave it on
the default to play with the main community.

## Steam extras

With Steam, online wins, losses and win streaks are tracked as Steam stats, and there are
achievements for finishing each campaign on Normal and on Hard, and for beating several Hard
AIs on Ludicrous speed.

## Data sources

Client: `tt/src/main/java/com/oddlabs/tt/net/ChatCommand.java`, `net/PeerHub.java`,
`form/GameMenu.java`, `form/SelectGameMenu.java`. Shared: `common/.../GameSession.java`
(rating formula, 5-win rule). Server: `server/src/main/java/com/oddlabs/matchserver/`
(`TimestampedGameSession.java`, `OpenSkillRatingSystem.java`, `ChatRoom.java`,
`Authenticator.java`).

Categories: Multiplayer
