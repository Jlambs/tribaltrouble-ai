# The aisim harness

For coding agents and people changing the harness itself. Using it is in `docs/aisim.md`, writing an AI in
`tt/src/main/java/com/oddlabs/tt/player/AGENTS.md`, the branch workflow in `docs/maintaining.md`.

## Where things are

`java/com/oddlabs/tt/aisim/`:

| File | Job |
|---|---|
| `Aisim` | The command line: usage text, one method per command, options into jobs. Also the shared JSON mapper and `aisim/` root. |
| `Options`, `UsageException` | Parsing `--key value` options; a usage error (exit 2). |
| `Job`, `End` | One game's settings, as sent to workers and stored in run.json; how a game ended. |
| `Batch` | The parent side of a run: worker JVMs, the job queue, results.jsonl, progress, STOP and aborts. |
| `WorkerMain` | The inside of a worker JVM: boots the engine, plays jobs, the hang watchdog. |
| `Match` | Plays one game as the client simulates it, and builds its result row. |
| `Runs`, `Report`, `Stats` | Reading run folders; summary, compare and show; the statistics. |
| `Game` | One recorded game: its result row and game file. Public: lab tools read games through it. |
| `Curves`, `Fights`, `Export` | curves (also summary's and compare's curve tables), fights, export. |
| `Table` | Aligned text tables. |
| `Replay` | replay and its verification. |
| `Snapshot`, `Pool`, `ClassFiles` | Build snapshots; frozen AIs; reading and writing class folders and jars. |
| `Lint` | The fair-play and determinism check of AI packages. |
| `NewAi` | new NAME, from the template `player/starter/StarterAI`. |

`java/com/oddlabs/tt/player/`: `starter` (the template, spec `starter`) and `chaos` (a test AI with faults on demand).
The harness's part of the toolkit lives in the game's source set, `tt/src/main/java/com/oddlabs/tt/aikit`: `AiSpec`
(specs), `GameRecorder` and `Census` (the game file), `PlayTest` (the `gui` hook in WorldViewer).

## Rules

- **The harness never changes the game.** Harness games must be the client's simulation: `Match` builds the world
  the way `IslandGenerator`, `TerrainMenu` and `Client` do, and the recorder only reads. When upstream changes
  those, follow it here.
- **File formats only grow.** Existing names in these must never be renamed, and readers must accept files from
  older and newer harness versions:
  - `Job` fields (workers of older snapshots read them for replays); only a nullable field may be removed;
  - result row fields (`Match.row`), census fields (`Census.Field`, whose order is also the `tl` order), game file
    events (`GameRecorder`), and run.json.
  Describe every change in the Files section of `docs/aisim.md`.
- **Keep in sync**:
  - `Aisim.USAGE` with the Commands section of `docs/aisim.md`;
  - the parent's JVM options in `aisim.sh` (`OPTS`) with `Batch.workerCommand`;
  - the game's JVM options in `aisim.sh` (`GUI_OPTS`) with `application` in `tt/build.gradle.kts`;
  - the lint rules in `Lint` with the Rules section of the AI guide.
- Only `AiLog`, `AiParams` and `GameTime` of aikit are for AIs (`Lint.TOOLKIT`); keep their API stable, since frozen
  AIs link against it.
- `Game`'s public methods are the API of lab tools (`lab/<name>/` on AI branches, `docs/aisim.md` Your own tools):
  add to them, never rename or remove one. `lab/starter/FirstArmory.java` uses it and runs in the self-check.
- Analyses read only recorded files, so they work for every AI. One that needs to know an AI's internals belongs in
  that AI's `lab/`, not here.

## Before committing

```bash
./gradlew spotlessApply
./aisim.sh build
```

then the harness self-check in `docs/maintaining.md` for anything that touches how games are played or recorded.
