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
[The development loop](#the-development-loop) · [Opponents](#opponents) · [Reading results](#reading-results) ·
[Why did it lose?](#why-did-it-lose-show-and-replay) · [Across a run](#across-a-run-curves-fights-and-export) ·
[Your own tools](#your-own-tools) · [Play-tests](#play-tests) · [Troubleshooting](#troubleshooting) · Reference:
[Commands](#commands), [How a game ends](#how-a-game-ends), [Files](#files)

## Words used here

- **A** is the AI under test, **B** its opponent. With `--vs N`, B is a team of N copies, and B's numbers are team
  totals.
- **Spec**: how a command names an AI: `easy`, `normal` or `hard` (the game's stock AI), `myai` (your AI
  `com.oddlabs.tt.player.myai.MyaiAI`), a class name, or `@TAG` (a frozen AI), optionally with params:
  `myai:rush=1,wave=12`.
- **Run**: one `play` or `batch`, stored in `aisim/runs/<run>/`.
- **Seed**: a map, as the skirmish menu's map seed (0..39999). **Slot** (or side): a player's start position; A plays
  every start of every seed. A game's **key** is `s<seed>-<slot of A>`, such as `s19-1`.
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
- **A desktop session with OpenGL 4.1 and an audio device.** Loading the game's resources creates GL objects and
  sound buffers, so every game JVM opens a hidden 64x64 window. Games do not run on a display-less server.
- **Git Bash on Windows** (it comes with Git for Windows). From PowerShell run
  `& "C:\Program Files\Git\bin\bash.exe" ./aisim.sh ...`.
- **A build first.** Every command except `build` runs from the last build. The first build needs the network
  (Gradle dependencies) and converts the game's textures, which takes several minutes with no output.

## Quick start

```bash
./aisim.sh build                                   # compile, lint, snapshot
./aisim.sh play --a hard --b normal --seed 3       # one game -> aisim/runs/play-<time>/, then prints a show command
./aisim.sh help                                    # every command and option
```

## The development loop

```bash
./aisim.sh new myai                     # tt/src/main/java/com/oddlabs/tt/player/myai/MyaiAI.java, spec "myai"
./aisim.sh build                        # compiles and lints it; a fair-play error refuses the build
./aisim.sh play --a myai --b easy       # smoke test: it loads, plays and writes its decision log
```

Then measure every change on the same games as the version before it. There are two ways to have "the version
before":

- **A change behind a param** whose default keeps the old behaviour: compare `myai` with `myai:rush=1` from the same
  build.

  ```bash
  ./aisim.sh batch --name base  --a myai        --b hard    # 120 games: seeds 1..60, A from both starts
  ./aisim.sh batch --name rush  --a myai:rush=1  --b hard   # the variant, on the same 120 games
  ./aisim.sh compare base rush                              # paired, game by game
  ```

- **A code change**: freeze the current version before you edit, then compare the frozen copy with the new build.

  ```bash
  ./aisim.sh freeze v1 myai                                 # this build of myai, as opponent @v1
  # ... edit, then ./aisim.sh build ...
  ./aisim.sh batch --name v1  --a @v1  --b hard
  ./aisim.sh batch --name new --a myai --b hard
  ./aisim.sh compare v1 new
  ```

Look at the games that went wrong (`show`, `replay`), and when a change looks good, confirm it on maps it was not
tuned on: the same two batches with `--seeds holdout`.

A 120-game batch takes from about a minute (games of the stock AIs end early) to about 20 minutes (most games run to
the 120-minute limit) with 4 workers on a recent desktop. Run batches in the background and keep working: they run
from their snapshot, so rebuilding cannot disturb them.

## Opponents

- **The stock AI**: `easy`, `normal`, `hard`. Hard is the first opponent to beat.
- **Your own earlier versions**: `./aisim.sh freeze TAG myai` copies the compiled package of `myai` (subpackages
  included) from the last build into `aisim/pool/TAG.jar`; `--b @TAG` or `--a @TAG` plays it. Each game loads it
  through a class loader of its own (only that package; the engine comes from the running build), so frozen AIs keep
  no state between games and several versions of one package can play each other. A tag is never overwritten: pick a
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

## Reading results

`summary RUN` is printed at the end of every run and saved as `summary.txt`. From a batch of `hard` against
`normal`, shortened:

```
games 20/20 done, 20 counted, 0 failed
score 0.900 [0.699, 0.972]   W 18  L 2  D 0                              A's score, Wilson 95% interval
  by elimination W 18 L 2 (by collapse W 1 L 0) | by timeout W 0 L 0 D 0 (0% of games)
  A in slot 0: W 10 L 0 D 0 | A in slot 1: W 8 L 2 D 0
means: kd30 +198.8 | w15 48.4 | margin +0.800 | length 22.4 min | cost 1.7 s CPU (1.7 s wall) per game
A swallowed errors 0 in 0 games | B swallowed errors 0 in 0 games     exceptions the AIs caught (AiLog.error)
A counters: none | B counters: none                                    AiLog.count counters, per game
curves (mean over games still running; A / B):
  min games  units warriors workers quarters armories towers kills strength
    5    20  65/62  5.1/9.7   59/52      1/1      1/1    0/0 0/0.1   94/103
   10    19 86/139    16/14  69/124    1/0.9    1/0.9  0.6/0 23/17  225/191
milestones (median seconds A / B, and in how many games): Q1 66/66 (19/19) Q4 -/- (0/0) A1 158/158 (19/19) ...
worst games for A:
  s2-1      loss elim     31.4m margin -1.00  ./aisim.sh show example s2-1 | ./aisim.sh replay example s2-1
RESULT example a=hard b=normal n=20/20 score=0.900 [0.699,0.972] W18 L2 D0 elim=18-2 ... fail=0
```

Warriors include tower garrisons, and workers are peons outside and inside buildings (`curves` explains the table).
The milestones are the first quarters (Q1), the fourth (Q4), the first armory (A1), tower (T1) and chieftain.

`compare BASE VARIANT` pairs the two runs game by game (same key = same map, start and world seed). Per metric it
prints both runs' means and the paired difference with its standard error (SE), weighting maps equally, since a
map's starts are not independent. `*` marks a difference larger than 2 SE. It counts identical games (same final
checksum): when almost all are identical, the variant is inert or never triggers. It refuses runs whose B, frozen B
version, map settings, races, `--vs`, minutes, `--rng` or `--no-collapse` differ (`--force` compares anyway), and
prints A's curves for both runs.

`compare BASE V1 V2 ...` compares several variants with one base as a table, a line per variant with the paired
difference of each metric. That is the table of a param sweep:

```bash
for wave in 8 12 16; do ./aisim.sh batch --name wave$wave --a myai:wave=$wave; done
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

`replay RUN KEY` plays that game again in a fresh JVM from the run's snapshot, with AI logs on, and compares every
census and the final checksum with the original game. Only AIs that use `AiLog` write a log; the stock AI does not.

- `VERIFIED`: the replay is the same game, so the logs explain what happened in the batch.
- `MISMATCH: first difference at t=...`: the AI is not deterministic (see the AI guide). Each JVM draws a different
  number of identity hashes before its first game (`perturb` in the row), so hash-order bugs show up as mismatches,
  though not in every game: replay a few games after changing collections or iteration.
- `--snap latest` replays the game on your newest build instead: `SAME` or `DIFFERENT from t=...` tells whether and
  when your change altered this game. `--until MIN` stops early.
- Replaying a hang takes up to the hang limit again: `AISIM_JAVA_OPTS=-Daisim.hangCpu=30 ./aisim.sh replay ...`,
  and read `g/<key>.err` for the stuck stack.

## Across a run: curves, fights and export

Three more commands look across all counted games of a run. Like `show`, they read only the recorded files, so they
work for every AI, the stock AI included.

**`curves RUN`** is the summary's curve table with the fields, minutes and games you choose: census means at each
minute over the games still running then, for A / B. `--split` shows A in the games it won / lost, which shows where
the two part; several runs show A in each, over the games all of them counted. `--fields` takes census fields (see
[Files](#files)), the sums `warriors` (tower garrisons included), `workers` (peons outside and inside), `harvested`
(all four harvested fields) and `stock` (all three stock fields), and any of them with `/min` for the gain over the
minute before; `--at` takes the minutes:

```
$ ./aisim.sh curves example --split --fields warriors,harvestedIron/min,stockIron --at 5,10
curves of example: mean over the games still running (A in the games it won / lost)
  min games warriors harvestedIron/min stockIron
    5  18/2  5.1/5.5             7.7/3     2.1/0
   10  18/1    16/17             6.5/3     6.9/7
```

**`fights RUN KEY`** splits one game into fights: deaths and razed buildings under 20 s apart and within 40 cells
of each other. For each fight it prints when, where (A's base, the middle or B's base, by the fight's share of the way
from A's start to B's nearest: below 0.35, above 0.65, else the middle), each side's losses by kind and the buildings
lost. **`fights RUN`** sums a whole run by place, also in the games A won and lost, and lists A's worst fights:

```
$ ./aisim.sh fights example
fights of example: 146 with 8+ deaths in 20 games, per game:
  where    fights A lost B lost    net net when A won (18) net when A lost (2)
  A's base    1.5   24.6   15.0   -9.7                +2.8              -122.0
  middle      2.2   15.6   20.4   +4.9                +5.1                +2.5
  B's base    3.6   38.9  240.6 +201.7              +218.5               +50.5
A's worst fights:
  game time        where                      A lost       B lost net
  s2-1 26:34-29:05 A's base 0.16            98 (98p)            0 -98
```

Losses are counted by kind: `r`, `i` and `c` rock, iron and chicken (rubber) warriors, `p` peons, `C` the chieftain.
`--min N` (default 8) leaves out fights with fewer deaths. `fights FILE.jsonl` reads any game file; in a play-test, A
is the first player that is not human.

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
  `players()`, `events()` or `events("deaths")`, `census(slot)` and `census(slot, seconds)`, A's slot `aSlot()`,
  B's slots `bSlots()`, `isA(slot)` and `result()` (win, loss or draw). `Game.num(map, key)` reads a number and
  `Game.value(census, field)` a census field or one of the sums `curves` knows. The class comment and method
  comments say the rest.
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
  differs: `winnerTeam` is the winning team number; `end` is `quit` when you left the game before it ended; a game
  you quit by closing the program has no `end` line.
- `game-<n>-ai-s<slot>.log`: the AI's decision log.
- `t` counts world ticks / 50, which is game time at normal speed only; `speed` events mark speed changes.

Read them with `./aisim.sh show "<folder>/game-1.jsonl"`. `./aisim.sh play --map "WORDS" --vs <players - 1> --races
<A's race>,<B's race>` recreates the map in the harness, with the default advanced settings and without ships.
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

- Batches default to 4 workers, each a JVM with a 256 MB heap (512 MB with `--vs 3` or more, or on huge maps) that
  takes about 0.6-0.8 GB in total, most of it the graphics driver's copy of the game's textures; use `--workers`.
  Cancel a run with `touch aisim/runs/<name>/STOP` or Ctrl+C. Workers die with their parent.
- The CPU goes to the engine's unit movement and path finding, map generation (about 0.8 s per game) and the AIs.
  `summary` prints the cost per game. To see where one game's time goes, replay it under Java Flight Recorder
  (`jfr` is in the JDK's `bin` folder):

  ```bash
  AISIM_JAVA_OPTS=-XX:StartFlightRecording=filename=aisim/profile.jfr,settings=profile ./aisim.sh replay RUN KEY
  jfr view hot-methods aisim/profile.jfr               # the busiest methods
  jfr print --stack-depth 64 --events jdk.ExecutionSample aisim/profile.jfr |
      awk '/jdk.ExecutionSample/ {n++; in_ai=0} /player\.myai\./ && !in_ai {ai++; in_ai=1} END {print ai+0 " of " n " samples in myai"}'
  ```
- Snapshots cost about 2-3 MB per changed build (the game and harness classes as jars); the ~65 MB of assets and
  resources are stored once. Snapshots hold absolute paths into `aisim/snap/blobs` and the Gradle cache: after
  moving the checkout or clearing `~/.gradle/caches`, run `./aisim.sh build`; runs from older snapshots can then no
  longer be replayed. Deleting `aisim/snap` has the same effect.
- `AISIM_JAVA_OPTS` adds JVM options to workers, split at spaces. The hang limits are `-Daisim.hangCpu=SECONDS` and
  `-Daisim.hangWall=SECONDS`.
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
  or `the replay worker died; see ...`: read that log. `glfwInit failed` or an OpenAL error there means aisim needs a
  desktop session with GL 4.1 and an audio device.
- `ai_init` errors: the AI's constructor threw (often an unknown param), with the stack in `g/<key>.err`.
- `$'\r': command not found` in Git Bash: `aisim.sh` was checked out with CRLF line endings. The repository's
  `.gitattributes` keeps `*.sh` files LF; delete `aisim.sh` and run `git checkout aisim.sh` to get it back with LF.

## Commands

The same list as `./aisim.sh help`:

```
./aisim.sh build                      compile (JDK 26), lint every AI package and snapshot the build
./aisim.sh new     NAME               start AI NAME from the template
./aisim.sh lint    [NAME|CLASS|@TAG...]
./aisim.sh play    [--a SPEC] [--b SPEC] [--seed N] [--side S] [--name NAME] [MAP]
./aisim.sh batch   --a SPEC [--b SPEC] [--seeds tune|holdout|LIST] [--side S] [--workers W] [--name NAME] [MAP]
./aisim.sh summary RUN
./aisim.sh compare BASE VARIANT [VARIANT...] [--force]
./aisim.sh show    RUN KEY | FILE.jsonl
./aisim.sh replay  RUN KEY [--snap latest|ID] [--until MIN]
./aisim.sh curves  RUN [RUN...] [--fields F,F] [--at MIN,MIN] [--split]
./aisim.sh fights  RUN [KEY] | FILE.jsonl [--min N]
./aisim.sh export  RUN [RUN...]
./aisim.sh lab     FILE.java [args]
./aisim.sh freeze  TAG NAME|CLASS [--from DIR|JAR]
./aisim.sh gui     [--stale-ok] SPEC [game args]
MAP: --size small|medium|large|huge --terrain tropical|northern --hills 0-10 --trees 0-10 --supplies 0-10
     --races v,v --vs N, or --map "MAP CODE WORDS" in place of the seed and the map settings
     play and batch also take --minutes M --rng N --no-collapse
```

- **Defaults**: `--b hard`. `play` also defaults to `--a hard --seed 1 --side 0`; `batch` needs `--a`. A large
  tropical map, hills 2, trees 10, supplies 10 (the skirmish menu's slider values); Vikings for both (`--races n,v`
  gives A natives); 120 minutes; `--seeds tune` from every start; 4 workers (`--workers` 1..16); `--vs 1`.
- **`--minutes`** (1..600, default 120) is the time limit. A limit far below the natural length of a game turns
  late-game play into "draws" decided by the timeout margin. Games that run to the limit cost the most CPU, so
  shorten it for quick opening experiments.
- **Seeds**: the harness generates exactly the map a player gets with the same menu settings and number of players
  (starts and resources depend on the player count). Every result row carries the map's **map code**, the words you
  can type into the menu's map code box, and `--map "WORDS"` does the reverse. `tune` is 1..60, for developing;
  `holdout` is 1001..1060, for confirming. A list looks like `1..20,31,40..45`; a seed listed twice is played once.
- Harness games have **no ships** and no Archipelago maps; `huge` is the menu's Enormous.
- **Start positions**: maps are rarely fair (resources can lie much farther from one start than from another), so a
  batch plays every seed once from each start. `--side S` plays only the games where A starts in slot S.
- **`--vs N`** (1..7) plays A against N allied copies of B, on a map for N+1 players; A rotates through every start.
- **`--rng N`** reseeds the world's random generator (per start). It is needed when A and B are the same spec and
  race, which the harness otherwise refuses, because both starts would replay the same game.
- **`--name`**: 1..40 characters of `A-Z a-z 0-9 . _ -`, and the run must not exist yet; delete `aisim/runs/NAME` to
  reuse a name. Without it, a run is named after the time (`play-MMdd-HHmmss`, or `MMdd-HHmmss` for a batch).
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

Checked every game second, first match wins:

| End | Rule |
|---|---|
| `elim`, via `engine` | A team has no living player by the game's own rule (units, an active chieftain or quarters left). |
| `elim`, via `collapse` | A player had at most 8 units, no chieftain and no quarters or armory (built or started) for 60 s. The engine's rule would let one surviving peon drag a lost game to the time limit. `--no-collapse` turns it off (the `collapse` event is still recorded). |
| `timeout` | At `--minutes`: the margin `m = (sA - sB) / (sA + sB)` of the teams' strength (`Player.getStatus()` plus tower garrisons, which that engine score leaves out) decides. A wins if `m >= 0.10`, B if `m <= -0.10` (one team has at least 55% of the strength), else it is a draw. |
| `crash` | An exception escaped an AI or the engine. |
| `hang` | No simulation tick for 120 s of CPU or 600 s of wall time (an endless loop). |
| `error` | The game could not be played: an AI could not be created (`ai_init`), an AI was compiled against an engine with other methods (`link error`), or the worker died. A run stops early when its first 3 games all end this way. |

`crash`, `hang` and `error` games **are not counted**; the summary lists them and the command exits 1.

Per game, from A's point of view: `score` (1 / 0.5 / 0), `elim` (+1 A eliminated B, -1 B eliminated A, else 0),
`kd30` (A's kills minus B's at 30:00, or at the end), `w15` (A's warriors, tower garrisons included, at 15:00 or at
the end) and `margin` (the timeout margin, or +-1 on elimination).

## Files

```
aisim/                                  (in the repository root, git-ignored)
  snap/<id>/cp.args, meta.json          a build snapshot (its jars are in snap/blobs/, the newest id in snap/latest)
  pool/<tag>.jar, <tag>.json            frozen AIs
  runs/<name>/
    run.json                            the run: specs, config, snapshot, every job
    results.jsonl                       one row per game, in completion order
    summary.txt
    census.csv, events.csv              from `export`
    g/<key>.jsonl                       per game: header, a census of every player every 30 s, events, end
    g/<key>-ai-s<slot>.log              AI decision logs (play only; only AIs that use AiLog write one)
    g/<key>.err                         the full stack of a crash, hang or error
    replay/<key>.jsonl, <key>-ai-s<slot>.log, <key>.row.json, <key>.worker.log      from `replay`
    replay-<snap>/...                   from `replay --snap latest|ID` (another build)
    log/w<i>.log                        worker output (engine chatter, errors)
    n/                                  native libraries unpacked for the workers (ignore)
    STOP                                create this file to cancel the run
  natives/gui                           native libraries unpacked for `gui` (ignore)
```

**Result row** (`results.jsonl`): `v run key seed side vs a b races map mapcode minutes rng collapse snap perturb end
via winner t checksum score elim kd30 w15 margin A B recorderFailed problem replay wall cpu`. `winner` is `a`, `b`, `draw`,
or null for a game that does not count. Crash, `ai_init` and `link error` rows have every field, with `score`, `elim`,
`kd30`, `w15` and `margin` null. Hang, dead-worker and harness-error rows have only the fields up to `perturb` plus
`end winner t problem replay`. `t` is the game length, `wall` the wall-clock time and `cpu` the simulation thread's
CPU time (unlike `wall`, not slowed by other load), all in seconds. `A` and `B` are the final census of A and of B's
team plus `aiError` (the first swallowed error) and `counters`. In Python:
`pandas.read_json("aisim/runs/<run>/results.jsonl", lines=True)`.

**Game file** (`g/<key>.jsonl`, and `game-<n>.jsonl` from play-tests): one JSON object per line, `t` in game seconds
(world ticks / 50) and `s` the player slot.

| `ev` | Meaning |
|---|---|
| `game` | Header: `source` (`aisim`, or `gui` for a play-test), run, key, specs, map, seed, map code, snapshot, and per player `s name team race ai x y` (start). |
| `census` | Census of player `s` every 30 s and at the end (fields below), plus `checksum` (the world's). |
| `placed`, `built`, `razed` | A building (`b` quarters/armory/tower, `x`, `y`) was started, completed, destroyed (`site:1` when unfinished). |
| `chief`, `chief_died` | The player gained or lost an active chieftain. |
| `cast` | The chieftain cast `magic` (e.g. `Stun`, `PoisonFog`) at `x`, `y`. |
| `stunned` | `n` of the player's units in the field became stunned, around `x`, `y` (tower garrisons are not seen). |
| `deaths` | `n` of the player's units in the field were killed in the last second (by kind), around `x`, `y`. |
| `collapse`, `out` | The collapse rule fired for the player / the player is out by the game's rule. |
| `speed` | The game speed changed (`secondsPerTick`; play-tests only). |
| `recorder_error` | The recorder stopped; later data is missing (the row has `recorderFailed: true`). |
| `end` | `end via winner checksum`. |

The census fields, in order: `alive units peons rock iron rubber inside garrison quarters armories towers sites kills
lost razed buildingsLost harvestedTree harvestedRock harvestedIron harvestedRubber stockRock stockIron stockRubber
chief casts stunned armyX armyY status strength errors`.

- `units` counts everyone, including peons inside buildings; `peons`, `rock`, `iron` and `rubber` are units outside.
- `inside` sits in quarters and armories, `garrison` in towers; `quarters armories towers` count completed buildings,
  `sites` the unfinished ones.
- `kills`, `lost`: units killed and lost; `razed`, `buildingsLost`: enemy buildings destroyed and own buildings lost.
- `harvested...`: resources harvested so far; `stock...`: weapons stocked in armories.
- `chief`: an active chieftain (0/1); `casts`: its casts so far; `stunned`: own units stunned so far.
- `armyX armyY`: the centre of the player's warriors (where the army is), -1 without warriors.
- `status`: the engine's score (`Player.getStatus()`); `strength`: `status` plus tower garrisons; `errors`: the AI's
  swallowed errors so far.

Units inside a razed building (tower garrisons, quarters and armory occupants) vanish from `units`, `inside` and
`garrison` without dying: they are in neither `deaths` nor `lost`, and the attacker gets no `kills` for them.

**CSV tables** (`export`): every line of both starts with its game's `run key seed side result` (`side` is A's slot,
`result` A's win, loss or draw), so the tables of several runs can be joined into one.

- `census.csv`: then `end slot role t` and the census fields in the order above. `role` is `A`, or `B` for every
  player not on A's team.
- `events.csv`: then `t slot role ev` (slot and role empty for events of no player, such as `end`), then every other
  member that any event of the run has, empty where an event lacks it. The header and the census lines are left out.

**AI log** (`<key>-ai-s<slot>.log`): a `# ai-log` header line (slot, player name, key, snapshot), then
`<seconds> s<slot> <TOPIC> <message>`, e.g. `   30.00 s0 STAT  units 20`.
`grep -v '^#' FILE | awk '$1>=600 && $1<=900'` selects a time window, `grep ' STAT '` a topic.
