# The aisim harness

For coding agents and people changing the harness itself. Using it is in `docs/aisim.md`, writing an AI in
`tt/src/main/java/com/oddlabs/tt/player/AGENTS.md`, the branch workflow in `docs/maintaining.md`.

## Where things are

`java/com/oddlabs/tt/aisim/`, one folder per part:

| Package | Files | Job |
|---|---|---|
| `aisim` | `Aisim`, `Options`, `UsageException` | The command line: usage text, options checked per command, the jobs of a run. Also what every part shares: the JSON mapper, the `aisim/` root, usage errors (exit 2). |
| | `Lineup`, `Maps` | The players and teams of a run (`--players`) and their seating by rotation; its maps (seeds and settings drawn per seed, or map codes). |
| `aisim.play` | `Batch` | A run: the job queue, one driver thread per worker slot, results.jsonl, progress, STOP and aborts. |
| | `Order` | The order a run plays its games in: the longest first, by their CPU in earlier runs of the same games. |
| | `Pace` | How many workers a run keeps: `--workers`, `--cpus` and `--memory`, the machine's threads, load and memory, and the registry of the runs on the machine (a folder in the temp directory). |
| | `WorkerProcess`, `WorkerMain` | A worker JVM as the parent starts and talks to it; the inside of one: boots the engine, plays jobs, the hang watchdog. |
| | `Match`, `ClientWorld`, `Job` | One game as the client simulates it, its end rules and its result row; its world, built exactly as the client builds it; its settings, as sent to workers and stored in run.json. |
| | `Replay` | replay and its verification. |
| `aisim.build` | `Snapshot`, `Pool`, `ClassFiles` | Build snapshots; frozen AIs; reading and writing class folders and jars. |
| | `Lint`, `NewAi` | The fair-play and determinism check of AI packages; new NAME, from the template `player/starter/StarterAI`. |
| `aisim.analysis` | `Runs`, `Game`, `End` | Reading run folders; one recorded game (result row and game file); how a game ended. |
| | `Summary`, `Compare`, `Show`, `Curves`, `Fights`, `Export`, `Profile` | One command each (`Curves` also makes summary's and compare's curve tables; `Profile` reads the Flight Recorder files of `--profile`). |
| | `Stats`, `Table` | Statistics; aligned text tables, short numbers and clock times. |
| `aisim.watch` | `WatchCommand`, `WatchFile` | watch's parent side (`Aisim watchfile`, from the repository root): the game's job (run.json's, or rebuilt from its game file's header), its frozen and `--ai-from-run` AIs, the recording; written as the watch file, with absolute paths, that the game loads. |
| | `WatchSession`, `WatchGame`, `Clock`, `Verification`, `Selftest` | The game side, loaded by `aikit.harness.Watch` in place of the main menu: the world built as `Match` builds it, the clock that ticks it, verification against the recording, `--selftest` (whose input steps go through the client's own input handling). |
| | `WatchHud` and its panels (`HudPanel`, `TextPanel`, `Scoreboard`, `Timeline`, `Inspector`, `AiLogPanel`, `HelpPanel`) | The controls and the HUD: the keys, the status panel and jump box, the scoreboard, the timeline of the recording's events, the inspector, the followed AI's log, the help. `HelpPanel`'s table and docs/aisim.md "Watching a game" list the keys `WatchHud.command` handles. |
| | `AiOverlay`, `OverlayPaint`, `OverlayRenderer`, `OverlayLabels` | The followed AI's overlay (`aikit.AiView`): calls its paint between ticks, at most once a frame, and catches what it throws; the shapes it painted, drawn into the 3D scene (through `WatchControls.render3D`, which SelectionDelegate forwards) and their labels on screen; the layer key, the AI panel and the inspector's line. |
| | `Rewinder`, `Checkpoints`, `Copier`, `Track` | Rewind: checkpoints that deep-copy the world as it first plays, restored into the same live World; the per-second checksum track that catches a rewind that does not replay exactly. |
| | `RewindCheck` | rewindcheck: the rewind's fidelity test, headless or in a hidden GL window. |

`play` uses `build` (snapshots, frozen AIs) and `analysis` (the summary after a run, reading games to replay them).
`build` and `analysis` use only the root package. `analysis` reads only recorded files, which is what lets it analyse
every AI and what makes it the library of lab tools. `watch` uses all three: `play`'s jobs and world, `build`'s
snapshots and frozen AIs, `analysis`'s run folders and game files.

`java/com/oddlabs/tt/player/`: `starter` (the template, spec `starter`) and `chaos` (a test AI with faults on demand).
The harness's part of the toolkit lives in the game's source set, `tt/src/main/java/com/oddlabs/tt/aikit/harness`:
`AiSpec` (specs), `GameRecorder` and `Census` (the game file), `PlayTest` (the `gui` hook in WorldViewer), `Watch`
(the `watch` hook in Renderer, which loads `aisim.watch.WatchSession` by name, so the game compiles and runs without
the harness). The rest of `aikit` is the AI toolkit.

## Rules

- **The harness never changes the game.** Harness games must be the client's simulation: `ClientWorld` builds the
  world the way `IslandGenerator`, `TerrainMenu` and `Client` do, and the recorder only reads. When upstream changes
  those, follow it there.
- **Watching changes nothing either.** `watch` builds the world in `Match`'s order through the same `ClientWorld`
  helpers and only ever calls `world.tick(0.02f)`: playback speed is ticks per frame, never the world's game speed or
  another tick length, which would simulate another game. Its engine hooks do nothing unless `aisim.watch` is set,
  and nothing of the viewer joins the world's animation managers but the checksum probe. The viewer checks every
  census second against the recording, and `rewindcheck` the rewind. The viewer calls an AI's `AiView` methods (its
  overlay) only between ticks, and nothing calls them in harness games; they must only read, and a painter that
  changes the game shows as `DIVERGED`.
- **Headless changes nothing the simulation computes.** Workers run the engine with `-Dcom.oddlabs.tt.headless=true`
  (`com.oddlabs.tt.global.Headless`), which skips what only drawing and sound read. Every headless branch in the
  engine must leave every game identical, checksum for checksum and game file for game file: run the headless check
  in `docs/maintaining.md` after touching one. The game itself never sets the flag.
- **File formats only grow.** Existing names in these must never be renamed, and readers must accept files from
  older and newer harness versions:
  - `Job` fields (workers of older snapshots read them for replays); only a nullable field may be removed;
  - result row fields (`Match.row`), census fields (`Census.Field`, whose order is also the order of a census line),
    game file events (`GameRecorder`), and run.json.
  Describe every change in the Files section of `docs/aisim.md`.
- **Keep in sync**:
  - `Aisim.USAGE` with the Commands section of `docs/aisim.md`, and the worker options with its Workers section;
  - the parent's JVM options in `aisim.sh` (`OPTS`) with `WorkerProcess.command`;
  - the game's JVM options in `aisim.sh` (`GUI_OPTS`) with `application` in `tt/build.gradle.kts`;
  - the rewind's JVM options in `aisim.sh` (`WATCH_OPTS`, for `watch` and `rewindcheck`) with what `Copier` writes by
    reflection (JDK collections and atomics, final fields), and the watch heap's default there (`AISIM_WATCH_HEAP`)
    with `WatchCommand.DEFAULT_HEAP`;
  - the watch view's keys (`WatchHud.handleInput` and `command`) with `HelpPanel`'s table and the key table of
    `docs/aisim.md` "Watching a game", and the selftest's steps (`Selftest`) with that section;
  - the lint rules in `Lint` with the Rules section of the AI guide.
- `aikit` itself (`AiLog`, `AiParams`, `GameTime`, `AiView`) is for AIs, and lint lets them use nothing else of it;
  keep its API stable, since frozen AIs link against it.
- The public methods of `Game`, `Table` and `Stats` are the API of lab tools (`lab/<name>/` on AI branches,
  `docs/aisim.md` Your own tools): add to them, never rename or remove one. `lab/starter/FirstArmory.java` uses
  `Game` and runs in the self-check. Other classes are public only because another package of the harness uses them.
- Analyses read only recorded files, so they work for every AI: `analysis` never imports `play` or `build`. One that
  needs to know an AI's internals belongs in that AI's `lab/`, not here.

## Before committing

```bash
./gradlew spotlessApply
./aisim.sh build
```

then the harness self-check in `docs/maintaining.md` for anything that touches how games are played or recorded.
