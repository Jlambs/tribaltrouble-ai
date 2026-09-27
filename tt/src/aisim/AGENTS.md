# The aisim harness

For coding agents and people changing the harness itself. Using it is in `docs/aisim.md`, writing an AI in
`tt/src/main/java/com/oddlabs/tt/player/AGENTS.md`, the branch workflow in `docs/maintaining.md`.

## Where things are

`java/com/oddlabs/tt/aisim/`, one folder per part:

| Package | Files | Job |
|---|---|---|
| `aisim` | `Aisim`, `Options`, `UsageException` | The command line: usage text, options checked per command, the jobs of a run. Also what every part shares: the JSON mapper, the `aisim/` root, usage errors (exit 2). |
| | `Lineup`, `Maps` | The players of a run (`--teams`, or `--a --b --vs`) and their seating by rotation; its maps (seeds and settings drawn per seed, or map codes). |
| `aisim.play` | `Batch` | A run: the job queue, one driver thread per worker, results.jsonl, progress, STOP and aborts. |
| | `WorkerProcess`, `WorkerMain` | A worker JVM as the parent starts and talks to it; the inside of one: boots the engine, plays jobs, the hang watchdog. |
| | `Match`, `ClientWorld`, `Job` | One game as the client simulates it, its end rules and its result row; its world, built exactly as the client builds it; its settings, as sent to workers and stored in run.json. |
| | `Replay` | replay and its verification. |
| `aisim.build` | `Snapshot`, `Pool`, `ClassFiles` | Build snapshots; frozen AIs; reading and writing class folders and jars. |
| | `Lint`, `NewAi` | The fair-play and determinism check of AI packages; new NAME, from the template `player/starter/StarterAI`. |
| `aisim.analysis` | `Runs`, `Game`, `End` | Reading run folders; one recorded game (result row and game file); how a game ended. |
| | `Summary`, `Compare`, `Show`, `Curves`, `Fights`, `Export` | One command each (`Curves` also makes summary's and compare's curve tables). |
| | `Stats`, `Table` | Statistics; aligned text tables, short numbers and clock times. |

`play` uses `build` (snapshots, frozen AIs) and `analysis` (the summary after a run, reading games to replay them).
`build` and `analysis` use only the root package. `analysis` reads only recorded files, which is what lets it analyse
every AI and what makes it the library of lab tools.

`java/com/oddlabs/tt/player/`: `starter` (the template, spec `starter`) and `chaos` (a test AI with faults on demand).
The harness's part of the toolkit lives in the game's source set, `tt/src/main/java/com/oddlabs/tt/aikit/harness`:
`AiSpec` (specs), `GameRecorder` and `Census` (the game file), `PlayTest` (the `gui` hook in WorldViewer). The rest
of `aikit` is the AI toolkit.

## Rules

- **The harness never changes the game.** Harness games must be the client's simulation: `ClientWorld` builds the
  world the way `IslandGenerator`, `TerrainMenu` and `Client` do, and the recorder only reads. When upstream changes
  those, follow it there.
- **File formats only grow.** Existing names in these must never be renamed, and readers must accept files from
  older and newer harness versions:
  - `Job` fields (workers of older snapshots read them for replays); only a nullable field may be removed;
  - result row fields (`Match.row`), census fields (`Census.Field`, whose order is also the order of a census line),
    game file events (`GameRecorder`), and run.json.
  Describe every change in the Files section of `docs/aisim.md`.
- **Keep in sync**:
  - `Aisim.USAGE` with the Commands section of `docs/aisim.md`;
  - the parent's JVM options in `aisim.sh` (`OPTS`) with `WorkerProcess.command`;
  - the game's JVM options in `aisim.sh` (`GUI_OPTS`) with `application` in `tt/build.gradle.kts`;
  - the lint rules in `Lint` with the Rules section of the AI guide.
- `aikit` itself (`AiLog`, `AiParams`, `GameTime`) is for AIs, and lint lets them use nothing else of it; keep its API
  stable, since frozen AIs link against it.
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
