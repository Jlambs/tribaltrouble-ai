# aisim: headless AI-vs-AI games for developing computer players

`aisim` plays Tribal Trouble games between computer players without showing them, 50-400x faster than real time,
and records every game so you can see why it was won or lost. It is built for this loop:

```
edit your AI -> build -> batch against an opponent -> compare with the last version -> find out why it lost -> edit
```

and for play-tests: `./aisim.sh gui SPEC` lets you play against your AI, and records that game in the same format.

Everything runs through `./aisim.sh` (Git Bash on Windows). All output is plain text and JSON lines, so scripts and
coding agents can drive it as well as you can. Its analyses are generic, and questions about your own AI are best
answered by tools you write yourself, in your AI's `lab/` folder ([Your own tools](#your-own-tools)). How to write the
AI itself is in **[the AI guide](../tt/src/main/java/com/oddlabs/tt/player/AGENTS.md)**; how this branch is maintained
is in [maintaining.md](./maintaining.md).

Contents: [Words used here](#words-used-here) · [Requirements](#requirements) · [Quick start](#quick-start) ·
[The development loop](#the-development-loop) · [Workers](#workers) · [Speed](#speed) · [Opponents](#opponents) · [Players](#players) · [Maps](#maps) ·
[Reading results](#reading-results) ·
[Why did it lose?](#why-did-it-lose-show-and-replay) · [Across a run](#across-a-run-curves-fights-and-export) ·
[Your own tools](#your-own-tools) · [Play-tests](#play-tests) · [Troubleshooting](#troubleshooting) · Reference:
[Commands](#commands), [How a game ends](#how-a-game-ends), [Files](#files)

## Words used here

- **Team A** is the first team of `--players` ([Players](#players)); list your AI first to put it there. The other
  teams are **B**, **C**, ... in the order given. A team's numbers are its players' totals. The summary's headline,
  `compare` and a few other reports are team A's; the summary also lists every team's record.
- **Spec**: how a command names an AI: `easy`, `normal` or `hard` (the game's stock AI), `myai` (your AI
  `com.oddlabs.tt.player.myai.MyaiAI`), a class name, or `@TAG` (a frozen AI), optionally with params:
  `myai:rush=1,wave=12`.
- **Run**: one `play` or `batch`, stored in `aisim/runs/<run>/`.
- **Seed**: a map, as the skirmish menu's map seed (0..39999), with the menu's settings (size, terrain and the
  hills, trees and supplies sliders) drawn for it or given ([Maps](#maps)). **Slot** (or side): a player's start
  position; every player plays every start of every map. A game's **key** is `s<seed>-<the first player's slot>`,
  such as `s19-1`.
- **Snapshot**: an immutable copy of a build. `build` makes one, and every other command runs from the latest one,
  so you can keep editing while batches run.
- **Frozen AI**: a copy of one AI package from a build, played as `@TAG` by any later build (`freeze`).
- **Lab**: `lab/<name>/`, the scratch folder of AI `<name>` for its own analysis tools and notes, committed with the
  AI but never part of it.
- **Census**: a player's numbers at one moment (units, buildings, kills, ...), recorded every 30 game seconds.
- **Counted** games ended by elimination or at the time limit; **failed** games (crash, hang, error) are listed but
  not counted.
- **kd30**, **w15**, **margin**: finer measures than winning, see [How a game ends](#how-a-game-ends).

## Requirements

- **JDK 26.** `aisim.sh` takes the first JDK 26 it finds in `AISIM_JDK`, `JAVA_HOME`, `C:/Program Files/Java/jdk-26*`,
  `/usr/lib/jvm/*26*` or `/Library/Java/JavaVirtualMachines/*26*`, so your default JDK does not matter.
- **For `gui` only, a desktop session with OpenGL 4.1 and an audio device.** The games the harness plays are headless
  (`-Dcom.oddlabs.tt.headless=true`, see `com.oddlabs.tt.global.Headless`): they load only what the simulation
  reads, and open no window and no audio device.
- **Git Bash on Windows** (it comes with Git for Windows). From PowerShell run
  `& "C:\Program Files\Git\bin\bash.exe" ./aisim.sh ...`.
- **A build first.** Every command except `build` runs from the last build. The first build needs the network
  (Gradle dependencies) and converts the game's textures, which takes several minutes with no output.

## Quick start

```bash
./aisim.sh build                                   # compile, lint, snapshot
./aisim.sh play --players "hard vs normal" --seed 3   # one game -> aisim/runs/play-.../, then prints a show command
./aisim.sh play --players "hard easy vs normal*2"     # a 2 vs 2 game
./aisim.sh help                                    # every command and option
```

## The development loop

```bash
./aisim.sh new myai                     # tt/src/main/java/com/oddlabs/tt/player/myai/MyaiAI.java, spec "myai"
./aisim.sh build                        # compiles and lints it; a fair-play error refuses the build
./aisim.sh play --players "myai vs easy"   # smoke test: it loads, plays and writes its decision log
```

Then measure every change on the same games as the version before it. There are two ways to have "the version
before":

- **A change behind a param** whose default keeps the old behaviour: compare `myai` with `myai:rush=1` from the same
  build.

  ```bash
  ./aisim.sh batch --name base --players "myai vs hard"          # 120 games: 60 maps, each player from both starts
  ./aisim.sh batch --name rush --players "myai:rush=1 vs hard"   # the variant, on the same 120 games
  ./aisim.sh compare base rush                              # paired, game by game
  ```

- **A code change**: freeze the current version before you edit, then compare the frozen copy with the new build.

  ```bash
  ./aisim.sh freeze v1 myai                                 # this build of myai, as opponent @v1
  # ... edit, then ./aisim.sh build ...
  ./aisim.sh batch --name v1  --players "@v1 vs hard"
  ./aisim.sh batch --name new --players "myai vs hard"
  ./aisim.sh compare v1 new
  ```

Look at the games that went wrong (`show`, `replay`), and when a change looks good, confirm it on maps it was not
tuned on: the same two batches with `--seeds holdout`. The maps of a batch are random: every setting you do not give
is drawn per seed, so they cover every size and terrain, and they are the same maps in every run ([Maps](#maps)).
Narrow them to what you are working on, such as `--size small,medium`.

A 120-game batch takes from about a minute (games of the stock AIs end early) to an hour or more (most games run to
the 360-minute limit) on a recent desktop; huge maps take the longest. Run batches in the background and keep
working: they run from their snapshot, so rebuilding cannot disturb them. [Workers](#workers) explains how many games
run at once.

## Workers

A batch plays its games in parallel, one per worker JVM. By default (`--workers auto`) it uses as much of the machine
as is free, and keeps adjusting: every 5 seconds it looks at the hardware threads, the CPU load and the available
memory, and at the other batches running on the machine, from this checkout or any other. Every batch registers its
live workers in `aisim-runs/` in the system's temp directory. The threads that nothing else uses, less one in
sixteen (1 of 28) left for the desktop, are split evenly among the automatic batches (threads left over go one each
to the batches that started first), and a batch takes what another leaves unused. A new batch starts with a quarter
of the machine at most and grows from there, so batches started together share rather than both grab everything;
when the machine fills up, workers beyond a batch's share retire after their game, never during one. Workers run
below normal priority (`nice -n 10` off Windows), so the desktop and other programs come first and stay responsive;
the games are the same at any priority. The progress output says when the count changes:

```
workers: 7 to start, up to 28 (28 threads, 47% busy, 10.8 GB available)
workers 7 -> 14 (28 threads, 83% busy, 9.1 GB available, other runs 0 workers)
```

Games differ a lot in length (the longest of a run often take ten times the median), and a run ends only when its
last game does: the long games started last would run on alone while the other workers idle. So a batch plays the
longest games first: a game's length is its mean CPU in the earlier runs that played the same games (the same lineup
but for team A, maps and settings, what compare pairs), and a game none of them played counts at their median
(`order: the longest games first, from 24 of the 30 games in 6 earlier runs`). Only the order changes, never a game.
A run still cannot end before its longest game, so start the next batch while one finishes: automatic batches share
the machine, and the new one takes the threads the old one's last games leave free.

To take less, or a fixed amount:

- `--cpus N` or `--cpus P%`: at most N hardware threads, or P% of them (a worker keeps about one busy), so other work
  keeps the rest. `--cpus 50%` leaves half the machine.
- `--memory SIZE` or `--memory P%`: at most SIZE (such as `800m` or `6g`) or P% of the memory for all the workers,
  counted at a worker's typical size: half its heap cap plus 128 MB (256 MB for 2-3 players, 384 MB for 4-12 players
  or a huge map, 640 MB for more than 12 players). With or without it, a batch starts a worker only while that much
  memory is available above a margin (5% of the memory, at least 512 MB).
- `--workers N` (1..256): exactly N workers, within `--cpus` and `--memory` when those are given; it does not adapt
  to the machine, but other automatic batches leave its workers alone.

The machine's numbers come from the JDK (`com.sun.management.OperatingSystemMXBean`, and `MemAvailable` in
`/proc/meminfo` on Linux), so they respect a container's CPU and memory limits. `play` and `replay` use one worker.

## Speed

The simulation's CPU per game is in `summary` (`cost`) and in each result row (`cpu`). To make your AI (or the
harness) cheaper, find where the time goes, change it, and measure the change on the same games:

```bash
./aisim.sh batch --players "myai vs hard*3" --seeds 1..8 --profile --name prof-myai
./aisim.sh profile prof-myai                              # engine, each AI, map generation; the top methods
./aisim.sh profile prof-myai --focus player.myai          # only your methods
./aisim.sh profile prof-myai --callers HashMap.getNode    # who calls a method, three callers deep
```

`--profile` runs every worker under Java Flight Recorder (about 2% more CPU) and writes one file per worker JVM to
the run's `prof/` when it exits (with `-XX:+DebugNonSafepoints`, so a sample inside an inlined method counts for that
method rather than its caller); `replay RUN KEY --profile` profiles one game. `profile` reads the simulation
thread's samples: the share of the engine, of each AI (the samples inside it, the engine calls it makes included), of
map generation and of the recorder; then the methods by inclusive share (with everything they call) and own share
(their own code), and the time the JIT compiled and the GC paused on other threads.

A profile says where time may go, not what a change saves: a method can look several times bigger than removing it
saves. Measure every change with `compare` on runs that played the same games at the same time, since the CPU per
game moves with the machine's load (a few % between runs of the same build, one after the other):

```bash
./aisim.sh freeze myai-before myai                        # the current version, before the change
# change the code, then ./aisim.sh build
./aisim.sh batch --players "@myai-before vs hard*3" --seeds 1..24 --name speed-before &
./aisim.sh batch --players "myai vs hard*3" --seeds 1..24 --name speed-after
wait; ./aisim.sh compare speed-before speed-after
```

A speed change should play every game the same: `compare` then says all games are identical, and its `cpu` line
gives the change and its standard error over the games, such as `cpu per game 39.2 s -> 26.3 s: -32.6% +- 1.4% *`;
`*` marks a change beyond 2 SE. It warns when the runs started more than 10 minutes apart, or only one was profiled.

## Opponents

- **The stock AI**: `easy`, `normal`, `hard`. Hard is the first opponent to beat.
- **Your own earlier versions**: `./aisim.sh freeze TAG myai` copies the compiled package of `myai` (subpackages
  included) from the last build into `aisim/pool/TAG.jar`; `@TAG` in `--players` plays it. A worker loads it
  once, through a class loader of its own that all its slots and games share (only that package; the engine comes
  from the running build), so several versions of one package can play each other. Like any AI, a frozen one must
  keep no state in statics: one that does replays with a MISMATCH. A tag is never overwritten: pick a
  new tag for a new version.
- **An AI from another branch or checkout**, from its compiled classes: build that checkout first, then freeze from
  its class folder (a relative path is resolved from this repository's root).

  ```bash
  ./aisim.sh freeze rival-1a2b3c rival --from ../other-checkout/tt/build/classes/java/main
  ```

  [maintaining.md](./maintaining.md#playing-ais-from-other-branches) shows how with a `git worktree`. Freezing does not
  lint; `./aisim.sh lint @TAG` does.

A frozen AI plays on the engine of whichever build runs it. One compiled against an engine without a method it calls
fails with a `link error` row naming the method. `run.json` records the hash of each frozen opponent, and `compare`
refuses runs against different versions.

## Players

`--players` names every player and the teams they form: teams separated by `vs`, players by spaces, 2 to 32
players in all. Quote it.

```bash
./aisim.sh batch --players "myai vs hard"                        # 1 vs 1
./aisim.sh batch --players "myai vs hard*3"                      # 1 vs 3: three allied copies of hard
./aisim.sh batch --players "myai hard vs normal*2 easy"          # 2 vs 3, myai allied with the stock hard AI
./aisim.sh batch --players "myai vs hard vs normal" --rng 1      # free-for-all: three teams
./aisim.sh batch --players "myai/n vs hard/n"                    # races: both natives
```

- A player is `SPEC[/RACE][*COUNT]`: RACE is `v` or `n` (`vikings`, `natives`; vikings when not given), and
  `*COUNT` puts COUNT copies in a row.
- **Team A is the first team**, the one the summary's headline, `compare`, `fights RUN` and `--logs lost` are
  about: list your AI first to put it there. The teams are named A, B, C, ... in the order given (T26, T27, ... past
  Z).
- **Every game is played to the end**: until one team is left, or to the time limit. A team is out when all its
  players are ([How a game ends](#how-a-game-ends)); the others play on without it. `--stop-when-a-out` ends a game
  as soon as team A is out instead, which saves the time of playing the other teams to the end: team A's place and
  measures are the same either way, but the teams still standing then share first place.
- **Places**: a team's place is 1 plus the number of teams that outlasted it. Teams that went out in the same second,
  or were standing at the time limit, share their places (two teams sharing first both have 1.5). A team **wins** when
  it is first alone, **draws** when it shares first, and **loses** otherwise. Its **score** is its place scaled from
  1 (first) to 0 (last): with two teams, 1 / 0.5 / 0 for a win, draw and loss.
- **Seating**: the games of one map rotate the players through the slots, one slot further each game, so every player
  plays from every start: with 4 players, the first player plays from slot 0, 1, 2 and 3, and a map is 4 games.
  `--side S` plays only the rotation with the first player in slot S. Which start each slot gets is up to the map (it
  shuffles its starts per seed, as in the game), so players listed next to each other do not necessarily start next to
  each other.
- When two rotations seat the same AIs in the same places (`"hard vs hard"`, or `"myai easy vs myai easy"`), they
  would play the very same game, and the harness refuses the run: add `--rng N` (the world's random seed then differs
  per start), or `--side S`.
- To compare versions of your AI, change only team A (its AIs, params or races, not how many players it has) and
  keep the other teams: `compare` refuses runs whose other teams differ.
- **More than 12 players** goes beyond the skirmish menu, which stops at 12; the engine plays them the same way, starts
  close together on smaller maps included. A run notes (`!!`) when its maps are more crowded than any menu game (12
  players on a small map, as crowded as 24 on a medium one), and plays them as they are. Such games take more: a map
  is as many games as there are players (`--seeds 1..60` with 32 players is 1920 games; `--side` plays one start), a
  16 vs 16 game costs about 20-30 s of CPU, and each worker gets a 1 GB heap.

## Maps

A map is a seed plus the skirmish menu's settings. Each setting option takes one value, a list (`2,5`), a range
(`0..4`, `small..large`) or `random`:

| Option | Values |
|---|---|
| `--size` | `small`, `medium`, `large`, `huge` (the menu's Enormous) |
| `--terrain` | `tropical`, `northern` |
| `--hills`, `--trees`, `--supplies` | the sliders, 0..10 |

A setting given one value holds on every map. **A setting not given, or given several values, is drawn per seed**:
a hash of the seed and the setting picks one of its values. The draw is the same for a seed in every run and every
version of the harness, so random maps are still the same games from run to run and `compare` pairs them; and
narrowing one setting does not change the draws of the others.

```bash
./aisim.sh batch --players "myai vs hard"                                     # 60 maps, every setting random
./aisim.sh batch --players "myai vs hard" --size small --terrain northern     # sliders random, small northern maps
./aisim.sh batch --players "myai vs hard" --size large --terrain tropical --hills 2 --trees 10 --supplies 10
./aisim.sh batch --players "myai vs hard" --seeds random:20                   # 20 new random maps
./aisim.sh play --players "myai vs hard" --seed random                        # one new random map
./aisim.sh play --players "myai vs hard" --map "PIZZA HABIT HISTORY"          # the map of a skirmish map code
```

- **Seeds**: `play --seed N` plays one (default 1); `batch --seeds` takes seeds and ranges (`1..20,31`), `tune`
  (1..60, the default, for developing) and `holdout` (1001..1060, for confirming a change on maps it was not tuned
  on). `random` (play) and `random:N` (batch, and in a list) draw seeds at random; the run prints them, and giving
  that list to `--seeds` plays the same maps again.
- **Map codes**: `--map "WORDS"` plays the map of a skirmish map code, and `--map "WORDS, WORDS"` several. It replaces
  `--seed(s)` and the settings. Every result row carries its map's code, the words you can type into the menu's map
  code box, and `show` prints it.
- The harness generates exactly the map a player gets with the same menu settings and number of players (starts and
  resources depend on the player count). Harness games have **no ships** and no Archipelago maps.

## Reading results

`summary RUN` is printed at the end of every run and saved as `summary.txt`. From a batch of
`--players "hard vs normal vs easy"`, shortened:

```
games 12/12 done, 12 counted, 0 failed
score 0.917 [0.646, 0.985]   W 11  L 1  D 0                             team A's score, Wilson 95% interval
  by elimination W 11 L 1 (by collapse W 0 L 0) | by timeout W 0 L 0 D 0 (0% of games)
  by start (the first player's slot):
    slot 0: W 4 L 0 D 0 | slot 1: W 3 L 1 D 0 | slot 2: W 4 L 0 D 0
  by map size: small W 6 L 0 D 0 | medium W 5 L 1 D 0
teams (place: 1 + the teams that outlasted it; won = first alone, drew = first shared):
  team players score place won drew lost out                                every team's record
  A    hard    0.917  1.17  11    0    1   1
  B    normal  0.417  2.17   1    0   11  11
  C    easy    0.167  2.67   0    0   12  12
means: kd30 +198.8 | w15 48.4 | margin +0.833 | length 22.4 min | cost 1.7 s CPU (1.7 s wall) per game
swallowed errors (AiLog.error): none | counters (AiLog.count): none
curves (mean over games still running; A / B / C):
  min games     units warriors   workers quarters armories towers  kills   strength
    5    12  71/65/54    1/0/0  70/55/54    1/1/1    1/1/1  0/0/0  7/0/3   93/64/67
milestones (median seconds A / B / C, and in how many games): Q1 57/78/77 (12/12/12) Q4 -/-/- (0/0/0) ...
worst games for team A:
  s2-1      loss elim     31.4m margin -1.00  ./aisim.sh show example s2-1 | ./aisim.sh replay example s2-1
RESULT example a=hard n=12/12 score=0.917 [0.646,0.985] W11 L1 D0 elim=11-1 ... fail=0
```

Warriors include tower garrisons, and workers are peons outside and inside buildings (`curves` explains the table).
The milestones are the first quarters (Q1), a player's fourth (Q4), the first armory (A1), tower (T1) and
chieftain, each the first on its team. The by-map lines show only settings that varied in the run. A team's AIs
appear under swallowed errors and counters only when they reported any. With more than 4 teams, curves and
milestones show team A and the other teams' mean per team ("others"). In the RESULT line, a value with spaces, such
as `a="myai hard"`, is quoted.

`compare BASE VARIANT` pairs the two runs game by game (same key = same map, start and world seed). Per metric it prints
both runs' means and the paired difference with its standard error (SE), weighting maps equally, since a map's starts
are not independent. `*` marks a difference larger than 2 SE. It counts identical games (same final checksum): when
almost all are identical, the variant is inert or never triggers, or it is a pure speed change, which should play every
game the same. Its `cpu` line is the change of the simulation's CPU per game, paired over the games
([Speed](#speed)). It refuses runs that differ in anything but team A's
AIs and races: the other teams (`lineup` in run.json), how many players team A has, the frozen version of any player
outside team A, the map options, the maps of the paired games, minutes, `--rng`, `--no-collapse` or `--stop-when-a-out`
(`--force` compares anyway). It prints team A's curves for both runs. The metrics are team A's ([How a game
ends](#how-a-game-ends)).

`compare BASE V1 V2 ...` compares several variants with one base as a table, a line per variant with the paired
difference of each metric. That is the table of a param sweep:

```bash
for wave in 8 12 16; do ./aisim.sh batch --name wave$wave --players "myai:wave=$wave vs hard"; done
./aisim.sh compare base wave8 wave12 wave16
```

How to decide:

- 120 games pin a win rate only to about +-9 points, and single tweaks rarely move it that much. `kd30` and `margin`
  are far more sensitive. When most games time out, decide on `elim`, `kd30` and `margin`, not `score`.
- Tune on the `tune` seeds and confirm on `holdout`: a change picked for doing well on some maps looks a little
  better on them than it will on new ones.
- Re-test old decisions when the opponent changes.

## Why did it lose? Show and replay

`show RUN KEY` prints the players, a census table every 5 minutes and the key events in time order (buildings,
chieftains, casts, stuns of 5 units or more, 10-second windows with 10 deaths or more). It reads any game file, so it
works on play-test files too.

**AI logs.** `play` writes every AI's decision log (`g/<key>-ai-s<slot>.log`; only AIs that use `AiLog` write one,
the stock AI does not). `batch` writes none unless asked, since logging costs time and an AI that logs a lot writes
megabytes per game: `--logs` keeps the logs of every game, `--logs lost` only those of the games team A did not
win, usually the ones you want to read. Without them, `replay` gets the log of any one game.

`replay RUN KEY` plays that game again in a fresh JVM from the run's snapshot, with AI logs on, and compares every
census and the final checksum with the original game.

- `VERIFIED`: the replay is the same game, so the logs explain what happened in the batch. Replaying a game of a batch
  without `--logs` also checks that logging does not change the AI's decisions; after `batch --logs`, both games
  logged, so it checks determinism only.
- `MISMATCH: first difference at t=...`: the AI is not deterministic (see the AI guide). Each JVM draws a different
  number of identity hashes before its first game (`perturb` in the row), so hash-order bugs show up as mismatches,
  though not in every game: replay a few games after changing collections or iteration.
- `--snap latest` replays the game on your newest build instead: `SAME` or `DIFFERENT from t=...` tells whether and
  when your change altered this game. `--until MIN` stops early. Census samples are compared on the fields both
  have, so a field one build records and the other does not (such as `lostInside` against older runs) is no
  difference.
- Replaying a hang takes up to the hang limit again: `AISIM_JAVA_OPTS=-Daisim.hangCpu=30 ./aisim.sh replay ...`,
  and read `g/<key>.err` for the stuck stack.

## Across a run: curves, fights and export

Three more commands look across all counted games of a run. Like `show`, they read only the recorded files, so they
work for every AI, the stock AI included.

**`curves RUN`** is the summary's curve table with the fields, minutes and games you choose: census means at each
minute over the games still running then, for every team (A / B / ...; with more than 4 teams, team A and the
others' mean per team). A team that is out counts as zero while the game goes on. `--split` shows team A in the
games it won / lost, which shows where the two part; several runs show team A in each, over the games all of them
counted. `--fields` takes census fields (see
[Files](#files)), the sums `warriors` (tower garrisons included), `workers` (peons outside and inside), `harvested`
(all four harvested fields) and `stock` (all three stock fields), and any of them with `/min` for the gain over the
minute before; `--at` takes the minutes:

```
$ ./aisim.sh curves example --split --fields warriors,harvestedIron/min,stockIron --at 5,10
curves of example: mean over the games still running (team A in the games it won / lost)
  min games warriors harvestedIron/min stockIron
    5  18/2  5.1/5.5             7.7/3     2.1/0
   10  18/1    16/17             6.5/3     6.9/7
```

**`fights RUN KEY`** splits one game into fights: deaths and razed buildings under 20 s apart and within 40 cells of
each other. For each fight it prints when; where, by the two teams whose starts are nearest (a team's base when the
fight's share of the way from the nearest team's start to the next team's is below 0.35, else between the two, such as
`A-C 0.40`); every team's losses by kind (one column per team, or with more than 4 teams one column of the teams that
lost any); the net, the enemies' losses minus team A's (`-` for a fight team A had no part in); and the buildings
lost. **`fights RUN`** sums a whole run from team A's side, by where the fights were, also in the games it won and
lost, and lists its worst fights:

```
$ ./aisim.sh fights example
fights of example: 77 of team A with 8+ deaths in 12 games, per game:
  where         fights A lost enemies lost    net net when A won (10) net when A lost (2)
  A's base         2.3   40.6         23.4  -17.2               -13.9               -33.5
  between          1.5   21.8         37.4  +15.7               +20.0                -6.0
  an enemy base    2.7   26.1        242.8 +216.7              +260.0                +0.0
  (13 fights left out: team A lost nothing in them and they were not at its base, so they were most likely among other teams)
team A's worst fights:
  game time       where                     A lost enemies lost net
  s4-2 8:39-13:51 A's base 0.22    83 (14r 6i 63p)           24 -59
```

With three teams or more, the run table counts only the fights team A took part in, as far as the recording tells:
it lost units or buildings there, or the fight was at its base. The recording has no killer per death, so a fight
among other teams that team A won cleanly, far from its base, is left out too.

Losses are counted by kind: `r`, `i` and `c` rock, iron and chicken (rubber) warriors, `p` peons, `C` the chieftain.
`--min N` (default 8) leaves out fights with fewer deaths. `fights FILE.jsonl` reads any game file; in a play-test,
team A is the team of the first player that is not human.

**`export RUN`** writes the counted games as CSV next to the results: `census.csv`, a line per census sample, and
`events.csv`, a line per event (both in [Files](#files)). Spreadsheets, `awk` and pandas read them as they are.

## Your own tools

The commands above are generic on purpose. The questions that matter most are about your own AI (does the rush start
too early? which maps starve it of iron? which of its decisions came before the lost fights?), and the best answers
come from small tools written for them. Write them whenever a question comes up twice.

Each AI has a scratch folder for them, `lab/<name>/` at the repository root (`lab/myai/` for `myai`), committed on the
AI's branch with the AI: analyses, scripts, experiments and notes, kept as a record of how the AI was made. Nothing
checks, formats or compiles it, and the game never sees it: the AI guide's
[Your own tools](../tt/src/main/java/com/oddlabs/tt/player/AGENTS.md#your-own-tools) says what may and may not cross
between the two.

A Java tool is one source file that `./aisim.sh lab` runs straight from source on the last build's class path:

```bash
./aisim.sh lab lab/starter/FirstArmory.java example     # when A built its first armory, in won and lost games
```

`lab/starter/FirstArmory.java` is a complete example, and a template to copy. A tool can use:

- **`com.oddlabs.tt.aisim.analysis.Game`**, one recorded game: `Game.counted(RUN)` (the counted games of a run),
  `Game.of(RUN, KEY)` and `Game.file(PATH)` (any game file). Per game: `row()` (the result row), `header()`,
  `players()`, `events()` or `events("deaths")`, `census(slot)` and `census(slot, seconds)`, the first player's slot
  `aSlot()`, team A's slots `aSlots()`, every other player's slots `bSlots()`, `isA(slot)` (on team A) and `result()`
  (team A's win, loss or draw). Teams: `teams()` (team numbers, team A's first), `aTeam()`, `teamOf(slot)`,
  `slotsOf(team)`, `teamName(team)` (A, B, C, ...) and `place(team)`. `Game.num(map, key)` reads a number and
  `Game.value(census, field)` a census field or one of the sums `curves` knows. The class comment and method comments
  say the rest.
- **`Table`** and **`Stats`** from the same package: `Table.align(rows)` prints rows as an aligned table, as the
  harness's commands do; `Stats.wilson` and `Stats.paired` are the statistics of `summary` and `compare`.
- helper classes in other `.java` files of the same folder;
- the engine, the harness and your AI's public classes, as of the last build: run `./aisim.sh build` after changing
  them.

Tools print to the terminal; write any files they produce under `aisim/`, which git ignores, not into `lab/`.
`aisim.sh` sets no heap limit for them; `JDK_JAVA_OPTIONS=-Xmx4g ./aisim.sh lab ...` sets one. Other languages work as
well: every file is plain JSON lines (see [Files](#files)), and `export` turns a run into CSV.

## Play-tests

```bash
./aisim.sh gui myai:rush=1
```

checks the spec and the build, then starts the game itself from the latest snapshot, in developer mode, with the
same options as `gradlew :tt:run`. In a single-player **skirmish**, that AI plays every **Hard** slot; campaigns,
tutorials and multiplayer games are unchanged. Frozen `@TAG` AIs cannot play in the game. The game is recorded in its
session log folder: the game prints `logs dir: ...` at startup and `Recording this game to <folder>` when a recorded
skirmish starts (see [Event Logs](./event-logs.md); outside Steam that is usually
`%LOCALAPPDATA%\TribalTrouble\logs\<start millis>\` on Windows, `~/Library/Logs/TribalTrouble/<start millis>/` on
macOS and `~/.local/state/tribaltrouble/logs/<start millis>/` on Linux):

- `game-<n>.jsonl`: the same format as harness games, with every player including you (`ai: "human"`), and in the
  header the map code, the advanced settings (`units maxUnits maxBuildings ships`) and the snapshot id. Its `end` line
  has no `places` and ends when one team is left: `winnerTeam` is the winning team number; `end` is `quit` when you
  left the game before it ended; a game you quit by closing the program has no `end` line.
- `game-<n>-ai-s<slot>.log`: the AI's decision log.
- `t` is game time at every speed, following the speed changes that `speed` events mark. Play-tests from before the
  recorder counted game time stamped world ticks / 50, which is game time at normal speed only; the analysis commands
  convert them.

Read them with `./aisim.sh show "<folder>/game-1.jsonl"`. `./aisim.sh play --map "WORDS" --players "..."`, with as
many players as the game had, recreates the map in the harness, with the default advanced settings and without
ships.
`gui hard` records games against the stock Hard AI for comparison. The Hard-slot swap only works in developer mode,
which `./aisim.sh gui` sets; started any other way, the game's Hard AI is the stock one.

Re-simulating a play-test with full logs: `./aisim.sh gui SPEC --eventload normal <folder>/event.log` replays the
recorded input with SPEC on the latest snapshot, so do it before you rebuild: the header's `snap` must equal the first
word of `aisim/snap/latest`. The game runs in `tt/`, so give the path to `event.log` as an absolute path or relative
to `tt/`. The replay writes fresh files under `replay-<millis>/` in the session folder the game was originally
recorded to, never over the originals; compare the `checksum` of the `census` lines to confirm it is the same game. A normal
(non-developer) start of the game deletes `event.log`, `std.out` and `std.err` from all but the previous session
folder, so copy the folders you want to re-simulate under `aisim/`, for example to `aisim/playtests/`, and load them
from there: `--eventload normal ../aisim/playtests/<millis>/event.log`.

## Troubleshooting

- Each worker is a JVM whose heap may grow to 256 MB (512 MB when a game of the run has 4 players or more, or a huge
  map; 1 GB with more than 12 players). Headless workers load no textures, models or sounds, and a full garbage
  collection before each game drops the last map's garbage, so a worker takes about 250-350 MB in all even for 12
  players on a large map: the CPU, not memory, usually limits how many run at once ([Workers](#workers)). Cancel a
  run with `touch aisim/runs/<name>/STOP` or Ctrl+C. Workers die with their parent.
- The CPU goes to the engine's unit movement and path finding, map generation (about 0.8 s per game) and the AIs.
  Headless, the engine skips what only drawing reads: the scene tree, the heights and bounds of models, and the motion
  of particles.
  `summary` prints the cost per game; [Speed](#speed) shows where it goes and how to measure a change.
- Snapshots cost about 2-3 MB per changed build (the game and harness classes as jars); the ~65 MB of assets and
  resources are stored once. Snapshots hold absolute paths into `aisim/snap/blobs` and the Gradle cache: after
  moving the checkout or clearing `~/.gradle/caches`, run `./aisim.sh build`; runs from older snapshots can then no
  longer be replayed. Deleting `aisim/snap` has the same effect.
- `AISIM_JAVA_OPTS` adds JVM options to workers, split at spaces. The hang limits are `-Daisim.hangCpu=SECONDS` and
  `-Daisim.hangWall=SECONDS`. `-Dcom.oddlabs.tt.headless=false` plays games the old way, with the client's resources
  in a hidden GL window (it needs GL 4.1 and an audio device): headless must play every game the same, checksum for
  checksum.
- `ripgrep` skips `aisim/` because git ignores it: use `grep`/`awk`, or `rg -uu`.

What the messages mean:

- `no build yet` / `no snapshot yet: run ./aisim.sh build`: run `./aisim.sh build` first.
- `... changed after the last build`: build again, or pass `--stale-ok` (for `gui` it goes before SPEC:
  `./aisim.sh gui --stale-ok SPEC`).
- `run NAME already exists`: choose another `--name`, or delete `aisim/runs/NAME`.
- `no frozen AI @TAG`: freeze it first.
- `!! snapshot ... unchanged`: the build produced the same classes; did the edit compile?
- `lint NAME: ... error` and `build refused`: the AI breaks a fair-play rule; each error says what to use instead,
  and the AI guide explains the rules.
- `worker exited with N (see aisim/runs/<run>/log/w<i>.log)`, `run aborted: the first 3 games could not be played`
  or `the replay worker died; see ...`: read that log. `glfwInit failed` or an OpenAL error there (only with
  `-Dcom.oddlabs.tt.headless=false`) means that needs a desktop session with GL 4.1 and an audio device.
- `ai_init` errors: the AI's constructor threw (often an unknown param), with the stack in `g/<key>.err`.
- `$'\r': command not found` in Git Bash: `aisim.sh` was checked out with CRLF line endings. The repository's
  `.gitattributes` keeps `*.sh` files LF; delete `aisim.sh` and run `git checkout aisim.sh` to get it back with LF.

## Commands

The same list as `./aisim.sh help`:

```
./aisim.sh build                      compile (JDK 26), lint every AI package and snapshot the build
./aisim.sh new     NAME               start AI NAME from the template
./aisim.sh lint    [NAME|CLASS|@TAG...]
./aisim.sh play    [--players "P.. vs P.."] [--seed N|random] [--side S] [--name NAME] [--profile] [MAP] [GAME]
./aisim.sh batch   --players "P.. vs P.." [--seeds LIST] [--side S] [--logs [lost]] [--name NAME] [--profile]
                   [WORKERS] [MAP] [GAME]
./aisim.sh summary RUN
./aisim.sh compare BASE VARIANT [VARIANT...] [--force]
./aisim.sh show    RUN KEY | FILE.jsonl
./aisim.sh replay  RUN KEY [--snap latest|ID] [--until MIN] [--profile]
./aisim.sh profile RUN | FILE.jfr [--focus TEXT] [--callers TEXT] [--top N]
./aisim.sh curves  RUN [RUN...] [--fields F,F] [--at MIN,MIN] [--split]
./aisim.sh fights  RUN [KEY] | FILE.jsonl [--min N]
./aisim.sh export  RUN [RUN...]
./aisim.sh lab     FILE.java [args]
./aisim.sh freeze  TAG NAME|CLASS [--from DIR|JAR]
./aisim.sh gui     [--stale-ok] SPEC [game args]
--players: the teams, separated by vs, each one or more players P; 2..32 players in all. Teams are named
  A, B, C, ... in that order; team A is the one summary and compare report. Games play on until one team
  is left, or to the time limit.
  e.g. "myai vs hard", "myai vs hard*3", "myai hard vs normal*2 easy", "myai vs hard vs normal"
  P: SPEC[/RACE][*COUNT]: RACE v or n (vikings, natives), COUNT copies in a row, e.g. hard/n*2
  SPEC: easy|normal|hard | NAME (com.oddlabs.tt.player.NAME.NameAI) | CLASS | @TAG, then optional :k=v,k=v
MAP: --size small|medium|large|huge --terrain tropical|northern --hills 0..10 --trees 0..10 --supplies 0..10
     each a value, a list (2,5), a range (0..4) or random. A setting not given, or given several values, is drawn
     per seed, the same for a seed in every run.
     Or --map "WORDS[, WORDS...]": the maps of skirmish map codes, in place of seeds and settings.
LIST: seeds and ranges like 1..20,31, tune (1..60), holdout (1001..1060), random:N (N random seeds)
WORKERS: --workers N|auto (auto: as many as the machine has room for now, shared with the other runs on it,
         growing and shrinking as it frees up or fills) --cpus N|P% (at most N or P% of the hardware threads)
         --memory SIZE|P% (at most SIZE, such as 6g, or P% of the memory, for all workers)
GAME: --minutes M (the time limit in game minutes; a game that reaches it is a draw) --rng N --no-collapse
      --speed slow|normal|fast|ludicrous (the game speed; the harness counts game time at every speed)
      --stop-when-a-out (end a game once team A is out, instead of playing the other teams to the end)
```

- **Defaults**: vikings, every map setting random, 360 minutes, `--seeds tune` from every start, `--workers auto`
  ([Workers](#workers)). `play` also defaults to `--players "hard vs hard" --seed 1 --side 0`; `batch` needs
  `--players`. [Players](#players) and [Maps](#maps) explain the players and the map options.
- **`--minutes`** (1..600, default 360) is the time limit, in game minutes. A game that reaches it is a draw,
  however far ahead anyone is: only beating every opponent wins. A limit far below the natural length of a game turns
  late-game play into draws. Games that run to the limit cost the most CPU, so shorten it for quick opening
  experiments.
- **Start positions**: maps are rarely fair (resources can lie much farther from one start than from another), so a
  batch plays every map once from each start of every player, rotating the seating ([Players](#players)). `--side S`
  plays only the games where the first player starts in slot S.
- **`--logs`** (batch) keeps every game's AI logs, `--logs lost` those of the games team A did not win (the others'
  are deleted as their rows come in); `play` always keeps them ([AI logs](#why-did-it-lose-show-and-replay)).
- **`--stop-when-a-out`** ends a game as soon as team A is out ([Players](#players)).
- **`--speed`** (default `normal`) is the world's game speed, as in the skirmish menu. The world ticks 50 times a
  real second at every speed; the speed sets how much game time a tick covers (half the normal tick's 0.02 s at slow,
  1.75 times at fast, 4 times at ludicrous), so the game plays in coarser steps, and an AI that counts ticks as time
  acts less often per game second (the stock AIs do: at ludicrous the Hards decide four times less often per game
  second, which is how the game plays). The harness counts game time at every speed (`GameTime`, in the AI guide):
  `--minutes`, the collapse rule's 60 s, the milestones at 15:00 and 30:00, the census every 30 s, and every time in
  rows, game files, AI logs and reports. A moment that falls between two ticks happens on the tick before it.
- **`--rng N`** reseeds the world's random generator (per start). It is needed when two starts would seat the same
  players in the same places, which the harness otherwise refuses, because they would replay the same game.
- **`--name`**: 1..40 characters of `A-Z a-z 0-9 . _ -`, and the run must not exist yet; delete `aisim/runs/NAME` to
  reuse a name. Without it, a run is named after its players and the time, such as `myai-vs-hardx3-0927-103412`
  (`play-` in front for `play`).
- **`lint`** checks every AI package of the last build, or the ones named: `NAME` or `CLASS` from the last build,
  `@TAG` a frozen AI. The AI guide lists the rules.
- **`new NAME`**: lowercase letters, digits and `_`, starting with a letter; not `easy`, `normal`, `hard` or a Java
  keyword. Pool tags are 1..32 characters of `A-Z a-z 0-9 . _ -`.
- **Stale builds**: `play`, `batch`, `lint` (unless it only checks `@TAG`s), `freeze` (without `--from`),
  `replay --snap latest` and `gui` refuse to run when a `.java` file under `tt/src/main/java` or `tt/src/aisim/java`
  changed after the last `build`: nothing is more confusing than measuring the old code. `--stale-ok` runs the last
  build anyway (`gui --stale-ok SPEC`; every other command takes it after its arguments).
- **Exit codes**: 0 ok; 1 the run has failed games or no counted games, a replay did not reproduce its game, or
  `compare` found no common games; 2 a usage error, a refusal, or a cancelled or aborted run; 3 the harness itself
  failed (a bug; it prints the stack trace).

## How a game ends

Every game second, each player is checked: a player is out by the game's own rule (no units, active chieftain or
quarters left), or by the harness's collapse rule. A team is out when all its players are; it gets a `team_out` event
and the others play on. The game ends at the first match:

| End | Rule |
|---|---|
| `elim`, via `engine` | At most one team is left standing (none when the last teams went out in the same second), or with `--stop-when-a-out` team A is out. `via` tells how the last teams went out. |
| `elim`, via `collapse` | As above, where a player went out by the collapse rule: at most 8 units, no chieftain and no quarters or armory (built or started) for 60 s. The engine's rule would let one surviving peon drag a lost game to the time limit. `--no-collapse` turns it off (the `collapse` event is still recorded). |
| `timeout` | At `--minutes`: every team still standing shares first place, whoever is ahead, so only beating every opponent wins. The row still records the margin of team A over the strongest standing team against it, as a measure of who was ahead; it decides nothing. |
| `crash` | An exception escaped an AI or the engine. |
| `hang` | No simulation tick for 120 s of CPU or 600 s of wall time (an endless loop). |
| `error` | The game could not be played: an AI could not be created (`ai_init`), an AI was compiled against an engine with other methods (`link error`), or the worker died. A run stops early when its first 3 games all end this way. |

`crash`, `hang` and `error` games **are not counted**; the summary lists them and the command exits 1.

Every team gets a **place**: 1 plus the number of teams that outlasted it, where teams that went out in the same
second, or were standing at the time limit, share their places (two teams sharing first both have 1.5). A team
**wins** when it is first alone, **draws** when it shares first and **loses** otherwise.

Per game, team A's measures, the ones `summary` and `compare` use:

- `score`: its place scaled from 1 (first) to 0 (last), `(teams - place) / (teams - 1)`; with two teams, 1 / 0.5 / 0
  for a win, draw and loss.
- `elim`: +1 when it won by eliminating every other team, -1 when it was eliminated and lost, else 0.
- `kd30`: its kills minus its losses at 30:00, or at the end. Units killed by their own side or an ally count in both,
  so friendly fire cancels out.
- `w15`: its warriors, tower garrisons included, at 15:00 or at the end.
- `margin`: +1 for a win, -1 for a loss, 0 for a shared elimination, and at the time limit `(sA - sB) / (sA + sB)` of
  the strength of team A and of the strongest standing team against it (`Player.getStatus()` plus tower garrisons,
  which that engine score leaves out, summed over a team).

## Files

```
aisim/                                  (in the repository root, git-ignored)
  snap/<id>/cp.args, meta.json          a build snapshot (its jars are in snap/blobs/, the newest id in snap/latest)
  pool/<tag>.jar, <tag>.json            frozen AIs
  runs/<name>/
    run.json                            the run: players, config, snapshot, every job
    results.jsonl                       one row per game, in completion order
    summary.txt
    census.csv, events.csv              from `export`
    g/<key>.jsonl                       per game: header, a census of every player every 30 s, events, end
    g/<key>-ai-s<slot>.log              AI decision logs (play, and batch --logs; only AIs that use AiLog write one)
    g/<key>.err                         the full stack of a crash, hang or error
    replay/<key>.jsonl, <key>-ai-s<slot>.log, <key>.row.json, <key>.worker.log      from `replay`
    replay/<key>.jfr                    from `replay --profile`
    prof/<pid>.jfr                      from `--profile`: one Flight Recorder file per worker JVM, written as it exits
    replay-<snap>/...                   from `replay --snap latest|ID` (another build)
    log/w<i>.log                        worker output (engine chatter, errors)
    n/                                  native libraries unpacked for the workers (ignore)
    STOP                                create this file to cancel the run
  natives/gui                           native libraries unpacked for `gui` (ignore)
```

**run.json**: `v name snap java created workers workerLimits profile players a lineup config logs aPools pools expected
jobs`.
`workers` is the most workers the run may have, and `workerLimits` what was asked for: `workers` (`auto` or the
count), `cpus` and `memoryMb` (null when not given); `profile` whether the workers were profiled. `players` is
`--players` as given (like `hard easy vs normal*2`), `a` team A's part of it, `lineup` the same with each player of team
A written as `A` (like `A*2 vs normal*2`) and `config` the map options and game settings (compare checks both), `logs`
which games keep AI logs (`all`, `lost` or null), and `aPools` and `pools` the jar hashes of the frozen AIs on team A
and on the other teams (compare checks `pools`). Each job has `run key seed side players seats size terrain hills trees
supplies minutes rng collapse stopWhenAOut game logs speed`, where `seats` is each slot's `spec race team`, `size` and
`terrain` index `small medium large huge` and `tropical northern`, and `speed` is null at normal speed.

**Result row** (`results.jsonl`): `v run key seed side slots players a map mapcode minutes rng speed collapse snap perturb
end via winnerTeam t ticks checksum result place score elim kd30 w15 margin teams recorderFailed problem replay wall
cpu`.

- `side` is the first player's slot, `slots` the number of players, `map` the settings in short (`large tropical h2 t10
  s10`).
- `winnerTeam` is the number of the team first alone, or null when first place is shared or the game does not count.
- `result` (`win`, `draw` or `loss`), `place`, `score`, `elim`, `kd30`, `w15` and `margin` are team A's
  ([How a game ends](#how-a-game-ends)); `result` is null for a game that does not count.
- `teams` has a block per team, by team number: `team players slots place score out via`, where `players` is its part
  of `--players`, `out` the game second it went out and `via` how (both null while it stood), then its final census
  summed over its players, `aiError` (its first swallowed error) and `counters`.
- Crash, `ai_init` and `link error` rows have every field, with the results, places and scores null. Hang,
  dead-worker and harness-error rows have only the fields up to `perturb` plus `end winnerTeam t result problem
  replay`.
- `t` is the game length in game seconds, `wall` the wall-clock time and `cpu` the simulation thread's CPU time
  (unlike `wall`, not slowed by other load), all in seconds. `minutes`, `out`, `kd30` and `w15` count game time
  too.
- `ticks`, the world ticks played, is there only at a speed other than normal, where `t` is not ticks / 50. Rows of
  such runs from before the harness counted game time have a `speed` but no `ticks`: their `t`, `out` and
  `minutes` count world ticks / 50 (a quarter of the game time at ludicrous). The analysis commands convert their
  `t` and `out` to game seconds.

In Python: `pandas.read_json("aisim/runs/<run>/results.jsonl", lines=True)`.

**Game file** (`g/<key>.jsonl`, and `game-<n>.jsonl` from play-tests): one JSON object per line, `t` in game seconds
(world ticks / 50 at normal speed; at others what the ticks covered at the speed then in effect) and `s` the player
slot. Files from before the recorder counted game time stamped world ticks / 50 at every speed; a newer file played
at another speed has `clock: "game"` in its header or its `speed` events, and the analysis commands convert the
older ones.

| `ev` | Meaning |
|---|---|
| `game` | Header: `source` (`aisim`, or `gui` for a play-test), run, key, `a` (team A's players), `players`, map, seed, map code, snapshot, `secondsPerTick` (the game time of a tick at the start), `clock: "game"` when that is not the normal 0.02, and per player `s name team race ai x y` (start). |
| `census` | Census of player `s` every 30 game seconds and at the end (fields below), plus `checksum` (the world's). |
| `placed`, `built`, `razed` | A building (`b` quarters/armory/tower, `x`, `y`) was started, completed, destroyed (`site:1` when unfinished). `razed` also has `inside`, the units lost with the building (see `lostInside` below), and when there were any, how many of each kind (`rock iron rubber peon chief`). |
| `chief`, `chief_died` | The player gained or lost an active chieftain. |
| `cast` | The chieftain cast `magic` (e.g. `Stun`, `PoisonFog`) at `x`, `y`. |
| `stunned` | `n` of the player's units in the field became stunned, around `x`, `y` (tower garrisons are not seen). |
| `deaths` | `n` of the player's units in the field were killed in the last second (by kind), around `x`, `y`. |
| `collapse`, `out` | The collapse rule fired for the player / the player is out by the game's rule. |
| `team_out` | Every player of `team` is out (`via` `engine` or `collapse`); no `s`. |
| `speed` | The game speed changed between two game seconds (`secondsPerTick`, `clock: "game"`; play-tests only; a pause shows only if the game resumes at another speed). |
| `recorder_error` | The recorder stopped; later data is missing (the row has `recorderFailed: true`). |
| `end` | `end via winnerTeam places checksum`: `places` by team number (harness games only). |

The census fields, in order: `alive units peons rock iron rubber inside garrison quarters armories towers sites kills
lost razed buildingsLost harvestedTree harvestedRock harvestedIron harvestedRubber stockRock stockIron stockRubber
chief casts stunned armyX armyY status strength errors lostInside`.

- `units` counts everyone, including peons inside buildings; `peons`, `rock`, `iron` and `rubber` are units outside.
- `inside` sits in quarters and armories, `garrison` in towers; `quarters armories towers` count completed buildings,
  `sites` the unfinished ones.
- `kills`, `lost`: units killed and lost; `razed`, `buildingsLost`: enemy buildings destroyed and own buildings lost.
- `harvested...`: resources harvested so far; `stock...`: weapons stocked in armories.
- `chief`: an active chieftain (0/1); `casts`: its casts so far; `stunned`: own units stunned so far.
- `armyX armyY`: the centre of the player's warriors (where the army is), -1 without warriors.
- `status`: the engine's score (`Player.getStatus()`); `strength`: `status` plus tower garrisons; `errors`: the AI's
  swallowed errors so far.
- `lostInside`: units lost inside razed buildings so far, which `lost` leaves out (below), so `lost + lostInside` is
  every unit lost.

Units inside a razed building vanish from `units`, `inside` and `garrison` without dying: a quarters' or armory's
peons, inside or queued to deploy, and a tower's gunner. The engine counts no death for them, so they are in neither
`deaths` nor `lost`, and the attacker gets no `kills` for them. The recorder counts them instead: every tick it reads
what each building holds, and when one is razed, the `razed` event and `lostInside` take the count it read the tick
before (exact unless a unit went in or out in that last tick). Files from before `lostInside` lack it.

**CSV tables** (`export`): every line of both starts with its game's `run key seed side result` (`side` is the first
player's slot, `result` team A's win, loss or draw), so the tables of several runs can be joined into one.

- `census.csv`: then `end slot team t` and the census fields in the order above. `team` is the player's team as the
  reports name it: `A` for team A, then `B`, `C`, ...
- `events.csv`: then `t slot team ev` (slot and team empty for events of no player, such as `end` and `team_out`),
  then every other member that any event of the run has, empty where an event lacks it. The header and the census
  lines are left out.

**AI log** (`<key>-ai-s<slot>.log`): a `# ai-log` header line (slot, player name, key, snapshot), then
`<seconds> s<slot> <TOPIC> <message>`, e.g. `   30.00 s0 STAT  units 20`.
`grep -v '^#' FILE | awk '$1>=600 && $1<=900'` selects a time window, `grep ' STAT '` a topic.
