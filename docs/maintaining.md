# Maintaining the headless branch

How this fork is organised, how upstream changes come in, and how to check the harness. Using the harness is in
[aisim.md](./aisim.md); writing an AI in the
[AI guide](../tt/src/main/java/com/oddlabs/tt/player/AGENTS.md).

## Branches

| Branch | What it holds | Who commits |
|---|---|---|
| `main` | Upstream (Tribal-Trouble/tribaltrouble), unchanged. | Only syncs from upstream. |
| `headless` | `main` plus the harness (`aisim.sh`, `tt/src/aisim`), the AI toolkit (`tt/src/main/java/com/oddlabs/tt/aikit`), their docs and CI, and the few engine lines listed [below](#where-headless-differs-from-upstream). | Harness, toolkit and doc changes, and read-only engine getters that AIs need. |
| one per AI | `headless` plus one AI package, `tt/src/main/java/com/oddlabs/tt/player/<name>/`, and its scratch folder of tools and notes, `lab/<name>/`. | Only those two folders. |

Changes flow one way: `main` -> `headless` -> AI branches, always by merge. Never rebase `headless`: the AI branches
are built on its commits, and rebasing it would give each of them a history to untangle.

## Bringing in upstream

```bash
git remote add upstream https://github.com/Tribal-Trouble/tribaltrouble.git   # once
git fetch upstream
git switch main && git merge --ff-only upstream/main && git push origin main  # or GitHub's "Sync fork" button
git switch headless && git merge main
```

Resolve conflicts with the table below, then check the merge:

```bash
./gradlew spotlessCheck
./aisim.sh build                        # compiles the game, the harness and the example AIs, and lints AI packages
./aisim.sh batch --players "hard vs normal" --seeds 1..3 --name sync-check && ./aisim.sh replay sync-check s2-1
```

The replay must print `VERIFIED`. When upstream changed the simulation, world creation or anything the recorder reads,
run the whole [self-check](#checking-the-harness-itself) as well. Then push `headless` and merge it into each AI
branch (`git switch <ai> && git merge headless`), building each.

Upstream changes to game rules change the games themselves (upstream then bumps `SIM_VERSION`): runs from before and
after the merge are no longer comparable game by game, so play the baselines again. Frozen AIs play on the engine of
whichever build runs them: after an engine change, check each one you still use with `./aisim.sh lint @TAG` and one
`play`; one that calls a method the engine no longer has fails with a `link error`.

## Where headless differs from upstream

These are the places a merge from upstream can conflict. On any conflict, take upstream's version and apply the
`headless` change to it again.

| File | The change |
|---|---|
| `tt/src/main/java/com/oddlabs/tt/viewer/WorldViewer.java` | Three lines: the import of `com.oddlabs.tt.aikit.harness.PlayTest`; the Hard slot `case PlayerSlot.AI_HARD -> ai = PlayTest.hardAi(player, unit_info, ingame_info, world_params);` (upstream creates `new AdvancedAI(player, unit_info, AdvancedAI.DIFFICULTY_HARD)`); and `PlayTest.leave(world);` as the first line of `close()`. For every game except an `./aisim.sh gui` play-test they change nothing. |
| `tt/src/main/java/com/oddlabs/tt/landscape/HeightMap.java` | `computeInterpolatedHeight` wraps coordinates without float remainders: bit for bit the same results (the comment there proves it), games about 22% faster. A candidate for an upstream pull request; once upstream has it, this row goes away. |
| `tt/src/main/java/com/oddlabs/tt/model/behaviour/HuntController.java`, `AttackController.java`, `GatherController.java` and `tt/src/main/java/com/oddlabs/tt/model/BuildProductionContainer.java` | Read-only getters for AIs: `getTarget()` (the unit a hunter or attacker is after), `getSupply()` (the resource a gatherer works) and `isInfinite()` (whether an armory's weapon order is endless). The frozen `@ultra` and `@fable` call them. |
| `tt/src/main/java/com/oddlabs/tt/global/Headless.java` (new) and the `Headless.ENABLED` branches in `model/Element.java`, `model/Model.java`, `model/AttackScanFilter.java`, `model/RacesResources.java`, `pathfinder/UnitGrid.java`, `pathfinder/ScanFilter.java`, `pathfinder/FindOccupantFilter.java`, `animation/AnimationManager.java`, `particle/Particle.java`, `particle/LinearEmitter.java`, `particle/ParametricEmitter.java`, `render/RenderQueues.java`, `audio/AudioFile.java`, `landscape/HeightMap.java`, `resource/BlendInfo.java` and `resource/StructureBlend.java` | Headless mode, which harness workers turn on with `-Dcom.oddlabs.tt.headless=true`. They skip what only drawing and sound read (the element tree, the heights and bounds of models, particle motion, GPU and OpenAL resources), and scan for enemies (over a byte grid that tags each cell with what occupies it: nothing, a non-Selectable, or a Selectable of team t) and keep the animation list with cheaper structures that give the same results in the same order. Workers then take about a third of the memory and games about 40% less CPU. The game never sets the flag, which is a constant, so for the game these branches are dead code. On a conflict, take upstream's version and put the branch back. Headless must play every game the same as the game does: after such a merge, run [the headless check](#checking-the-harness-itself). |
| `tt/src/main/java/com/oddlabs/tt/pathfinder/GridNode.java`, `AStarAlgorithm.java`, `GridPathFinder.java`, `RegionPathFinder.java`, `TargetGridPathFinder.java`, `FinderFilter.java`, `FinderTrackerAlgorithm.java` and `tt/src/main/java/com/oddlabs/tt/model/SupplyFinder.java` | Path finding with the same results, a little faster: the grid search keeps its window's offset once instead of in every node it touches and looks a node's region up once; a gatherer checks each step whether its target region still holds a supply without finding the closest one, and finds the closest with loops instead of streams (the same first of equals). Games about 3% faster. |
| `tt/src/main/java/com/oddlabs/tt/util/BezierPath.java` | The control points and the current point are fields instead of small arrays: the same arithmetic, bit for bit, and one object instead of seven to reach per moving unit per tick. A candidate for an upstream pull request. |
| `tt/src/main/java/com/oddlabs/tt/landscape/World.java` | `getGameMillis()` and `getGameMillisPerTick()`: a counter of the game time played (what each tick covered at the game speed then in effect), which `tick` adds to between its two passes. AI logs, game recordings and the harness's rules count game time with it at every speed; the simulation never reads it. |
| `tt/build.gradle.kts` | The `aisim` block after `tasks.run`: the source set, `check` depending on it, and the `aisimClasspath` task. |
| `README.md` | The "Developing Computer Players" section and its line in the contents. |
| `.gitignore` | `/aisim/`. |

Everything else on `headless` is new files: `AGENTS.md`, `CLAUDE.md`, `.gitattributes`, `aisim.sh`, `docs/aisim.md`,
this file, `.github/workflows/aisim.yml`, `lab/`, `tt/src/aisim/`, `tt/src/main/java/com/oddlabs/tt/aikit/`, and
`AGENTS.md` and `CLAUDE.md` in `tt/src/main/java/com/oddlabs/tt/player/`. They conflict only if upstream adds a file
of the same name (most likely `AGENTS.md`, `CLAUDE.md` or `.gitattributes`); then merge the two.

A merge can also break the build without a conflict, when upstream changes engine code the harness uses:
`ClientWorld` builds worlds the way `IslandGenerator`, `TerrainMenu` and `Client` do, `Census` and `GameRecorder` read
player and building getters, and `Lint` lists engine classes and methods. Fix those on `headless`.

### Engine getters for AIs

When an AI needs to read state the engine has no public getter for, add a plain read-only getter (no logic, no side
effect) on `headless`, in a commit of its own that says which AI needs it, add it to the table above, and merge
`headless` into the AI's branch. Never add one on an AI's branch: it would conflict with every other branch that needs
it, and `freeze` would not carry it.

## Starting an AI

```bash
git switch -c myai headless
./aisim.sh new myai                     # tt/src/main/java/com/oddlabs/tt/player/myai/MyaiAI.java
```

Commit only that package and `lab/myai/` (the AI's own tools and notes, [aisim.md](./aisim.md#your-own-tools)) on
the AI's branch. When the AI needs a harness or toolkit change, make it on `headless` and merge `headless` in. A lab
tool that proves generally useful can become a harness command there, rewritten to work for any AI.

## Playing AIs from other branches

`aisim/` belongs to one working tree, and so do its frozen AIs. To play another branch's AI, build that branch in a
second working tree and freeze it from there:

```bash
git worktree add ../tt-rival rival                                   # the other AI's branch, beside this checkout
(cd ../tt-rival && JAVA_HOME=<JDK 26> ./gradlew -q :tt:classes)
./aisim.sh freeze rival-$(git rev-parse --short rival) rival --from ../tt-rival/tt/build/classes/java/main
./aisim.sh batch --players "myai vs @rival-1a2b3c"
```

`aisim/pool/<tag>.json` records the folder a frozen AI came from, not its commit, so put the commit in the tag.

## CI

`.github/workflows/aisim.yml` runs on every push to a branch that contains it, which is `headless` and the AI branches:
`spotlessCheck`, then `./aisim.sh build`, which compiles the game, the harness and the example AIs and lints every AI
package. It plays no games and does not look at `lab/`, which is scratch. Upstream's `gradle.yml` only runs for `main` and `release`.

## Checking the harness itself

`chaos` (`tt/src/aisim/java/com/oddlabs/tt/player/chaos/ChaosAI.java`) is a test AI with faults on demand. After
changing the harness, or merging an engine change:

```bash
./aisim.sh lint starter chaos                                   # both ok
rm -rf aisim/runs/check-*                                       # the run names below are single-use
# replays of the same build: VERIFIED, for a 1 vs 1 run, a 2 vs 2 run, a free-for-all and 32 players
./aisim.sh batch --players "hard vs normal" --seeds 1..3 --name check-replay && ./aisim.sh replay check-replay s2-1
./aisim.sh batch --players "hard easy vs normal*2" --seeds 1..2 --size small --minutes 10 --name check-teams &&
    ./aisim.sh replay check-teams s1-1
./aisim.sh batch --players "hard vs normal/n vs easy" --seeds 1 --minutes 20 --name check-free-for-all &&
    ./aisim.sh replay check-free-for-all s1-2
# headless plays every game as the game's own code path does (headless=false needs GL 4.1 and an audio device):
# compare prints "identical games 3"
./aisim.sh batch --players "hard/n*2 vs hard*2 vs normal/n" --size medium --seeds 1..3 --side 0 --minutes 20 \n    --name check-headless
AISIM_JAVA_OPTS=-Dcom.oddlabs.tt.headless=false ./aisim.sh batch --players "hard/n*2 vs hard*2 vs normal/n" \n    --size medium --seeds 1..3 --side 0 --minutes 20 --name check-headless-off
./aisim.sh compare check-headless-off check-headless
# 32 players, beyond the skirmish menu's 12: the engine must still build and play them
./aisim.sh batch --players "hard*16 vs normal*16" --size large --seeds 3 --side 5 --minutes 3 --name check-32-players &&
    ./aisim.sh replay check-32-players s3-5
# faults: chaos:nondet=1 replays with some MISMATCH; crash rows with .err files, exit 1; a hang row; the bad param
# aborts the run, exit 2; swallowed errors and counters in the summary
./aisim.sh batch --players "chaos:nondet=1 vs hard" --seeds 1..3 --minutes 6 --name check-nondeterminism
./aisim.sh batch --players "chaos:crash=90 vs hard" --seeds 1 --minutes 5 --name check-crash
AISIM_JAVA_OPTS=-Daisim.hangCpu=15 ./aisim.sh batch --players "chaos:hang=30 vs hard" --seeds 1 --name check-hang
./aisim.sh batch --players "chaos:bogus=1 vs hard" --seeds 1..3 --name check-bad-param
./aisim.sh batch --players "chaos:err=1,count=1 vs hard" --seeds 1 --minutes 5 --name check-ai-errors
# the analyses
./aisim.sh curves check-replay --split && ./aisim.sh fights check-teams && ./aisim.sh export check-teams
./aisim.sh compare check-replay check-nondeterminism check-ai-errors --force   # the several-variant table
./aisim.sh lab lab/starter/FirstArmory.java check-replay        # the example lab tool still compiles
```

The example lab tool is the one piece of `lab/` that `headless` looks after: it shows lab tools how to use
`com.oddlabs.tt.aisim.analysis`, whose `Game`, `Table` and `Stats` lab tools on AI branches call. Keep their public
methods working as they are; add, never rename or remove.

After a change to the recorder or to how games are played, also check that games are unchanged: batch the same
games on the build before and after, and `compare` them; every game must be identical.
