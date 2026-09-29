# AGENTS.md

Tribal Trouble, a real-time strategy game from 2004 (GPL-2.0), restored and extended. Java 26 and Gradle. People start
with `README.md`; this is the short version for coding agents.

## Repository

- Modules: `tt` (the game client), `common` (code the client and servers share, the network interfaces among it),
  `server` (matchmaker and router), `assets` (geometry and texture conversion) and `tools`.
- Build and run: `./gradlew tt:run` runs the game, `./gradlew build` builds everything (`gradlew.bat` on Windows).
- Format before committing: `./gradlew spotlessApply`. CI fails on `spotlessCheck`. Error Prone runs on every compile.
- The network interfaces that the `api-guard` job in `.github/workflows/gradle.yml` lists are a wire protocol:
  changing one needs an `API_VERSION` bump in `common/src/main/java/com/oddlabs/util/Compatibility.java`.
- The simulation runs in lockstep on every machine of a game, so game logic must stay deterministic. Changing what
  the simulation computes needs a `SIM_VERSION` bump in the same file. AI and harness work never changes it.
- `aisim/` (harness output and frozen AIs) is git-ignored. Never commit it.

## Branches of this fork

This repository is a fork of Tribal-Trouble/tribaltrouble for developing computer players. Upstream's own workflow
(`docs/development-workflow.md`) applies to upstream, not here:

- `main` mirrors upstream; nothing is committed to it here.
- `headless` is `main` plus the AI harness (`./aisim.sh`, `tt/src/aisim`), the AI toolkit
  (`tt/src/main/java/com/oddlabs/tt/aikit`) and their docs. Changes to those, and read-only engine getters that AIs
  need, are committed here.
- Each AI has a branch of its own off `headless`, which commits only its package
  (`tt/src/main/java/com/oddlabs/tt/player/<name>/`) and its scratch folder of tools and notes (`lab/<name>/`).
- Changes flow `main` -> `headless` -> AI branches, by merge, never by rebase.

`docs/maintaining.md` has the steps, and the list of files where `headless` differs from upstream.

## Writing a computer player (AI)

Read `tt/src/main/java/com/oddlabs/tt/player/AGENTS.md` before writing AI code. It covers how an AI plugs in, the
orders it can give, the fair-play and determinism rules, and recipes. Start an AI with `./aisim.sh new NAME`. The
harness (`./aisim.sh`, manual in `docs/aisim.md`) plays, compares, replays and analyses AIs. Write your own analysis
tools in `lab/<name>/`; the AI must play without them. An AI never changes game mechanics: engine code stays as it is.
Profile your AI regularly as it grows (`--profile`, `./aisim.sh profile RUN --focus player.<name>`): its CPU is time
every experiment waits for. Keep a speed change only when `compare` shows every game identical and a real `cpu`
saving (the AI guide, Speed and robustness).

## Changing the harness

Read `tt/src/aisim/AGENTS.md` first: it lists the file formats and the pairs of places that must change together.
