# Community

## Where to find players

- **Discord:** [discord.gg/tribaltrouble](https://discord.gg/tribaltrouble) is the main
  meeting place: finding games, asking questions, reporting bugs and following development.
- **In game:** the online chat rooms (see [Multiplayer](Multiplayer.md)). Some rooms are
  linked to Discord channels, so you can talk to players in game from Discord and back.
- **Website:** [tribaltrouble.org](https://tribaltrouble.org) has downloads, player profiles
  and a page for watching games live.

## Discord bot

The community Discord server has a bot connected to the game server.

### Game feed

A game-activity channel announces when online games start and finish, with the teams, the
winner and a link to watch. Games that end with conflicting results are announced as
invalidated.

### Commands

| Command | Shows |
|---|---|
| `/matches` | Games in progress: name, whether rated, teams, start time and a **Watch** link |
| `/online` | Who is logged in to the game |
| `/profile <player>` | Rating and rank, online status, win/loss record and win rate, current and best win streak, games played, favourite tribe, and the linked Discord user |
| `/leaderboard [start] [count]` | The League table: rating, wins and losses (25 rows by default, up to 200) |
| `/rank <player> [range]` | A player's League position with the players just above and below |
| `/streaks` | The top 10 current and top 10 best win streaks |
| `/matchup <player1> <player2>` | Head-to-head record of two players in 1v1 games, with recent results |
| `/whois <user or player>` | Which game profiles belong to a Discord user, or which Discord user owns a profile |
| `/register-user <profile>` | Link your game profile to your Discord account. The game asks you to confirm with a private message. |

Moderators also have `/kick`, `/ban`, `/unban` and `/bannedwords`.

## Contributing to the game

Tribal Trouble is open source under the GPL v2. The project's GitHub repository has build
instructions in its README.

- Pull requests target the `main` branch.
- Issues list known bugs and planned work; the project roadmap is linked from the README.
- The Discord has a channel for contributors where you can ask about setup and where to find
  things.
- You can help without coding: play online, report bugs with event logs (see
  [FAQ](FAQ.md#reporting-a-bug)), and share old screenshots, especially of the original
  leaderboards.

## Translating

Game text is translated in a shared spreadsheet, and changes flow into the game automatically
through reviewed pull requests. No coding is needed.

1. Ask for edit access in the Discord, or open an issue on GitHub, and say which language
   you want to work on.
2. Only type in your language's column. Yellow cells are machine-translated drafts: fix them
   if needed, then clear the yellow to mark them as checked.
3. The spreadsheet's *instructions* tab has the full rules.

Full details are in the project's `docs/translations/README.md`.

## Contributing to this wiki

See the [Style Guide](Style-Guide.md).

Categories: Community
