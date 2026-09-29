# Writing a computer player (AI)

For coding agents and people writing a Tribal Trouble AI. This file covers the AI itself: how it plugs into the game,
the orders it can give, what it can read, and the rules. The harness that plays, compares and replays AIs is
documented in `docs/aisim.md`. Paths here are relative to the repository root, where `./aisim.sh` runs.

## Start

```bash
./aisim.sh build                    # once: compile, lint and snapshot (the first build takes several minutes)
./aisim.sh new myai                 # creates tt/src/main/java/com/oddlabs/tt/player/myai/MyaiAI.java, spec "myai"
./aisim.sh build                    # compiles and lints your AI; breaking a fair-play rule refuses the build
./aisim.sh play --players "myai vs easy"   # one game, then prints how to show and replay it
```

Then work in the loop that `docs/aisim.md` describes: batch the new version and the previous one (a param's default,
or a frozen copy) against the same opponent, `compare`, and `show` / `replay` the games it lost. Write your own
analysis tools in `lab/<name>/` whenever the harness's generic ones do not answer a question (see
[Your own tools](#your-own-tools)). The stock AI (`easy`, `normal`, `hard`: `AdvancedAI.java` in this folder) is the
first opponent to beat.

## How an AI plugs in

- **One package per AI**: `com.oddlabs.tt.player.<name>` in `tt/src/main/java`, so the game itself can load it too
  (`./aisim.sh gui <name>` puts it in the skirmish Hard slots). The entry class is `<Name>AI extends AI`. Put your
  other classes in the same package or below it. `freeze` copies exactly that package, so it must not use another
  AI's package. Keep tools out of it: the harness runs matches, and analyses and experiments go in `lab/<name>/`
  (see [Your own tools](#your-own-tools)).
- **Constructor** `(Player owner, UnitInfo units, String spec_params)`, calling `super(owner, units)` first: that
  registers the AI and creates the starting units. The spec `myai:k=v,k=v` passes `k=v,k=v` as `spec_params`. Parse
  them with `AiParams params = AiParams.parse(spec_params)`: `params.getInt("wave", 12)`, `getDouble`, `getBoolean`
  (true/false/1/0) and `getString`, each with your default, then `params.done()`, which fails on keys nobody read, so
  a typo cannot silently play the defaults.
- **`animate(float t)`** is called on every world tick, `GameTime.TICKS_PER_SECOND` (50) per game second at normal
  speed, the harness's only speed. (It runs on the world's real-time animation manager, so in a GUI game at another
  speed, paused included, it keeps ticking 50 times per real second.) Decide once every second or few seconds, not
  every tick. The template counts ticks and calls `think(second)`; `GameTime.seconds(world)` is the game time.
- **State**: one AI object plays one game. Keep all state in its fields and in objects it owns, never in statics.
- **`AiLog`**: get it once in the constructor with `log = AiLog.of(getOwner())`. `log.log(TOPIC, () -> text)` writes
  a decision log line (only in `play`, `replay` and GUI play-tests; otherwise it costs one field check).
  `log.count(key)` adds to a counter that shows in every result row and batch summary. `log.error(where, e)` records
  an exception you caught; the game goes on and the count shows in every row.
- `AiLog`, `AiParams` and `GameTime` are the AI's toolkit, `com.oddlabs.tt.aikit`. Its subpackage `aikit.harness`
  is the harness's.
- **Ships**: harness games never have ships. GUI games have them only on Archipelago maps or with the Ships advanced
  setting, so ship orders matter only for play-tests there.

## How orders reach the game

Tribal Trouble is a lockstep game. Every machine in a game runs the whole simulation, and the machines stay identical
because they apply the same orders at the same world tick.

- **A human's order** (a click in the UI) calls a method of `PlayerInterface` on a network proxy
  (`net/PeerHub.getPlayerInterface()`). The call travels as an event through the router, gets stamped with a tick,
  and at that tick every machine calls the same method on that player's `Player` object (`net/Peer.executeEvents`).
- **A computer player's order** never crosses the network. The AI itself runs on every machine, and it calls the
  same `Player` methods directly, at the same tick on each.

So `PlayerInterface` is the complete set of orders, the same for humans and AIs. Everything else an AI does is
reading state, which is why an AI must decide exactly the same on every machine (see Determinism).

`Player`'s implementation of an order skips the units and buildings you give it that are dead or not yours. It does
not check the rest:

- A dead target fails an assertion (the harness runs with `-ea`).
- `deployUnits` needs a finished building that has a queue for that type, and `build*Weapons` needs a finished
  armory. Anything else throws.

So check `isDead()`, `isComplete()` and what kind of building it is before you give an order.

## The orders

All of them are methods of `getOwner()`, your `Player`. `gx, gy` are grid cells (see Reading the game).

| Order | What it does |
|---|---|
| `setTarget(units, target, action, aggressive)` | The right-click. `target` is a unit, a building, a resource (tree, rock, iron or rubber) or `new LandscapeTarget(gx, gy)`. `Action.DEFAULT` chooses like a right-click, in this order: a peon builds an unplaced building site, gathers a resource, or repairs a damaged building of yours or an ally's (but a finished armory it enters; repairing that takes `GATHER_REPAIR`); a unit enters your building (quarters, armory, tower) if there is room; it attacks an enemy; otherwise it walks there, fighting on the way if `aggressive`. `MOVE` enters or walks without fighting. `ATTACK` attacks the target, even a friendly one, or walks there fighting. `GATHER_REPAIR` gathers or repairs. `aggressive` only matters when `DEFAULT` ends up walking. |
| `setLandscapeTarget(units, gx, gy, action, aggressive)` | The same towards a map cell. The group spreads over the cells around it. |
| `placeBuilding(peons, Race.BUILDING_QUARTERS / _ARMORY / _TOWER / _SHIP, gx, gy)` | Creates a building site and sends the peons to build it. The UI checks the site before sending this order; the peons check only a slightly smaller footprint when they arrive, and drop an illegal site. So check the site yourself (recipe below). |
| `deployUnits(building, DeployType, count)` | Units leave a finished building through its deploy queue. Quarters: `PEON` only. Armory, and ships: `PEON`; `ROCK_WARRIOR`, `IRON_WARRIOR`, `RUBBER_WARRIOR` (each takes a weapon from stock); `PEON_HARVEST_TREE`, `_ROCK`, `_IRON`, `_RUBBER` (gather that resource and bring it back); `PEON_TRANSPORT_*` (carry a load of it to the rally point). A negative count cancels queued units, as the UI's decrease button does. |
| `buildRockWeapons`, `buildIronWeapons`, `buildRubberWeapons(armory, count, infinite)` | Queue weapons in a finished armory. The peons inside make them from the resources in its stock. |
| `recallGatherers(building, TreeSupply.class / RockSupply.class / IronSupply.class / RubberSupply.class, count)` | The `count` nearest gatherers of that resource working for the building walk back into it. |
| `setRallyPoint(building, target)`, `setRallyPoint(building, gx, gy)` | Where units deployed from the building go. |
| `trainChieftain(quarters, start)` | Starts (`true`) or stops (`false`) training the chieftain. |
| `doMagic(chieftain, index)` | Casts a spell if the chieftain is charged (`chieftain.canDoMagic(index)`). Vikings: `RacesResources.INDEX_MAGIC_STUN`, `INDEX_MAGIC_BLAST`. Natives: `INDEX_MAGIC_POISON`, `INDEX_MAGIC_LIGHTNING`. |
| `exitTower(tower)` | The unit in the tower comes out. |
| `setSailingTarget(ships, target)`, `setSailingTarget(ships, gx, gy)` | Ships sail (only in GUI games with ships, see above). |

Not for AIs:

- **`createHarvesters`**: it is on the interface, but no player can send it. The UI never does, and the engine's
  deploy queue calls the building's own method instead. It skips the queue, so lint refuses it. Use
  `deployUnits(..., PEON_HARVEST_*, n)`.
- **`setPreferredGamespeed`, `changePreferredGamespeed`**: these are the players' votes on the game speed. An AI's
  vote counts, so it could slow the game down or pause it for everyone. Lint refuses them.
- **`Action.DEFEND`**: the UI never sends it (the stock AI uses it). It is a short attack-move after which the unit
  goes idle. Use `ATTACK`; lint warns.
- **`view*`**: these broadcast a human's camera and UI state to spectators. They are harmless and useless to an AI.

## Reading the game

The game has no fog of war (humans see the whole map), so an AI may read any state.

- **Your player**, `getOwner()`:
  - `getUnits().getSet()` holds your units and buildings, in a stable order. It leaves out units inside buildings,
    towers and ships, and sites that are not placed yet. It is the engine's live set, and an order can change it at
    once: a unit next to the building it is sent into enters it and leaves the set, and a site placed on the spot
    joins it. So copy the set (`new ArrayList<>(...)`) before you give orders in a loop over it, and never modify
    the set itself.
  - `getQuarters()`, `getArmory()`: your first finished quarters or armory, or null. Both scan all your units, so
    read them once per round.
  - `getChieftain()`, `hasActiveChieftain()`, `isTrainingChieftain()`, `getRace()`.
  - `getStartX()`, `getStartY()`: world coordinates.
  - `getUnitCountContainer().getNumSupplies()`.
  - `isEnemy(other_player)`, `findNearestEnemy(gx, gy)`, `findNearestEnemyBuilding(gx, gy)`.
- **The base class `AI`**:
  - `getIdlePeons()`, `getIdleWarriors()`, `getGatherTreePeons()` (and rock, iron, rubber), `getArmory()`,
    `getQuarters()`, `getTowers()`, `getConstructionSites()` and more. They return arrays, or null when a group is
    empty, after `reclassify()`.
  - `reclassify()` is **not** only a read. Whenever it finds a finished armory, it orders all three weapon types
    without end (`build*Weapons(armory, INFINITE_LIMIT, true)`), as the stock AI's economy wants. That overrides your
    own weapon orders, so use the base class's groups only if you want that too. `manTowers()` calls it as well.
- **The world**, `getOwner().getWorld()`: `getPlayers()`, `getUnitGrid()`, `getHeightMap()`, `getRandom()`.
- **Units and buildings** (`Selectable`, `Unit`, `Building`):
  - Everything: `isDead()`, `getOwner()`, `getGridX()`, `getGridY()`, `getPositionX()`, `getPositionY()`.
  - `getAbilities().hasAbilities(Abilities.BUILD)`, and likewise `HARVEST`, `ATTACK`, `THROW`, `MAGIC`, ...
  - `getPrimaryController()` tells what a unit is doing: `IdleController`, `GatherController`,
    `PlaceBuildingController`, `RepairController`, `HuntController`, `WalkController`, ... (in `model/behaviour`).
  - Buildings: `isComplete()` and `getHitPoints()`. `getUnitContainer().getNumSupplies()` counts the units inside;
    the container is null until the building is finished.
  - Stock, only on a finished armory (and ships): `getSupplyContainer(TreeSupply.class)` for resources (also
    `RockSupply`, `IronSupply`, `RubberSupply`), and `getSupplyContainer(RockAxeWeapon.class)` for weapons of both
    races (also `IronAxeWeapon`, `RubberAxeWeapon`).
  - These getters assert that the building is alive.
- **Coordinates**: orders and scans take grid cells. `UnitGrid.toGridCoordinate(world_x)` converts a world position.
- **Scans**: `getUnitGrid().scan(filter, gx, gy)`, with `new FindOccupantFilter<>(x, y, radius, null, Unit.class)`
  for units within `radius` meters of `(x, y)`, or `BuildingSiteScanFilter` for legal building sites.

## Recipes

Sketches of common tasks, not a complete AI. `me` is `getOwner()`. `gx, gy` is a grid cell. `quarters` and `armory`
are finished buildings from `me.getQuarters()` and `me.getArmory()`.

```java
// peons into the quarters, where peons make more peons (idle_peons: a List<Unit> you collected)
me.setTarget(idle_peons.toArray(new Unit[0]), quarters, Action.DEFAULT, false);

// gatherers: 3 peons leave the armory to gather rock and bring it back
me.deployUnits(armory, DeployType.PEON_HARVEST_ROCK, 3);

// weapons, then warriors armed with them
me.buildRockWeapons(armory, 10, false);
int axes = armory.getSupplyContainer(RockAxeWeapon.class).getNumSupplies();
int inside = armory.getUnitContainer().getNumSupplies();
me.deployUnits(armory, DeployType.ROCK_WARRIOR, Math.min(axes, inside));

// a tower at a legal site near (gx, gy), keeping the site to follow it
BuildingTemplate tower = me.getRace().getBuildingTemplate(Race.BUILDING_TOWER);
BuildingSiteScanFilter sites = new BuildingSiteScanFilter(getUnitGrid(), tower, 40, true);
getUnitGrid().scan(sites, gx, gy);
if (!sites.getResult().isEmpty()) {
    Target site = sites.getResult().getFirst();
    me.placeBuilding(builders, Race.BUILDING_TOWER, site.getGridX(), site.getGridY());
    // a builder carries the site; one already standing there places it at once and starts building
    Controller builder = builders[0].getPrimaryController();
    Building building = builder instanceof PlaceBuildingController placing ? placing.getBuilding()
            : builder instanceof RepairController repairing ? repairing.getBuilding() : null;
}

// attack the nearest enemy building, fighting whatever is met on the way
Selectable<?> target = me.findNearestEnemyBuilding(gx, gy);
if (target != null) {
    me.setTarget(warriors, target, Action.ATTACK, true);
}

// a warrior into a tower (it shoots from there); exitTower(tower) lets it out
me.setTarget(Selectable.newArray(warrior), tower, Action.DEFAULT, false);

// the chieftain, and a spell when it is charged
if (!me.hasActiveChieftain() && !me.isTrainingChieftain()) {
    me.trainChieftain(quarters, true);
}
Unit chieftain = me.getChieftain();
if (chieftain != null && chieftain.canDoMagic(RacesResources.INDEX_MAGIC_STUN)) {
    me.doMagic(chieftain, RacesResources.INDEX_MAGIC_STUN);
}

// enemy units within 30 m of a point
FindOccupantFilter<Unit> near = new FindOccupantFilter<>(x, y, 30f, null, Unit.class);
getUnitGrid().scan(near, UnitGrid.toGridCoordinate(x), UnitGrid.toGridCoordinate(y));
for (Unit unit : near.getResult()) {
    if (!unit.isDead() && me.isEnemy(unit.getOwner())) { /* ... */ }
}
```

## Rules

`./aisim.sh build` lints every AI package in `tt/src/main/java` (a package `com.oddlabs.tt.player.NAME` that holds
its `NameAI` class), and `./aisim.sh lint [NAME|CLASS|@TAG]` runs the same check on demand. An error refuses the
build, so such code never reaches a snapshot to be played, frozen or compared. A warning is printed and the build goes
on. AIs frozen from other checkouts (`freeze --from`) are not checked; `lint @TAG` checks them.

Lint reads the compiled code. It catches mistakes, not deliberate cheating, so the rules below hold whether or not
lint can see them. It counts an engine method that returns nothing as a change unless it knows the method only
reads. When it flags one that only reads, add it to `isVoidQuery` in
`tt/src/aisim/java/com/oddlabs/tt/aisim/build/Lint.java`, on the `headless` branch.

### Fair play

An AI may do exactly what a human player can do through the UI, and nothing more.

- **Give orders only through the orders of your own Player, `getOwner()`** (the table above). Never call
  engine methods that change the game (lint errors):
  - the order-like methods of buildings and units: `Building.setRallyPoint`, `Building.deployUnits`,
    `Unit.setTarget`, `Selectable.initTarget`, `Unit.doMagic` and so on. Each has an order that does the same fairly.
  - the stock chieftain AI (`getRace().getChieftainAI().decide(...)`), which casts with `Unit.doMagic` directly;
  - anything else that changes state: `Unit.hit`, `Selectable.remove`, `Player.buildBuilding`,
    `BuildingTemplate.create`, `SupplyContainer.increaseSupply`, `Army.add`, and `new Unit(...)` or any other
    world object;
  - writing any engine field, such as `Globals` or `Settings`;
  - `Player.createHarvesters`, `setPreferredGamespeed` and `changePreferredGamespeed`;
  - creating another AI (`new AdvancedAI(...)`) or a `UnitInfo`. Either would give your player a second set of
    starting units;
  - the harness's part of the toolkit, `com.oddlabs.tt.aikit.harness` (`AiSpec`, `GameRecorder`, ...): an AI uses
    only `com.oddlabs.tt.aikit` (`AiLog`, `AiParams` and `GameTime`);
  - another AI's package: an AI is one package (`freeze` copies only that one), so copy what you need into yours.
- **Never give orders through another player's `Player`**. The engine would accept them for that player's units, and
  lint cannot see whose `Player` an order goes to.
- **Leave the engine's arrays and collections alone**: the sets, lists and arrays that getters such as
  `getUnits().getSet()` and `getWorld().getPlayers()` return are the engine's own. Copy one before you sort or change
  it. Never reseed the world's random generator.
- **Check building sites** before `placeBuilding`, with `BuildingTemplate.isPlacingLegal` or `BuildingSiteScanFilter`
  (lint warns when a package places buildings and never checks a site).
- **Never change engine code** (anything outside your package, `aikit` included) to help your AI. That changes the
  game for everyone. It also escapes `freeze`, so it would change your frozen baselines underneath you. The one
  exception: when an AI needs to read state the engine has no public getter for, add a plain read-only getter on the
  `headless` branch, in a commit of its own (`docs/maintaining.md`), never on an AI's branch.
- **No reflection into the engine** (lint warns on reflection; it cannot tell what reflection reaches).

### Determinism

Every machine must reach the same decisions from the same state, and a replay in another JVM must reproduce the
game. `./aisim.sh replay RUN KEY` checks this: it replays a game in a fresh JVM and prints `VERIFIED`, or `MISMATCH`
when the AI is nondeterministic.

- **Random numbers** only from `getOwner().getWorld().getRandom()` or `new Random(seed)`, never `Math.random()` or
  `new Random()` (lint warns).
- **No wall clock in decisions** (lint warns on `System.nanoTime` and similar; timing your own code is fine).
- **No hash order.** These iterate in an order that differs between JVMs:
  - a `HashMap` or `HashSet` keyed by engine objects, classes or enum constants (all of them hash by identity);
  - `Set.of(...)` and `Map.of(...)`, even with string keys;
  - the groups from `Player.classifyUnits()`: find a group by its type, never by its index.

  Use `EnumMap`, `LinkedHashMap`, `LinkedHashSet` or `TreeMap`, a list, or sort by a stable key such as grid
  position. Lint cannot see this; `replay` can.
- **No `hashCode()` or default `toString()` of engine objects** in decisions or logs: both show identity hashes
  (lint warns on `hashCode`).
- **No threads or parallel streams** (lint warns).
- **No mutable statics**: two copies of your AI can share a JVM, as both sides of a mirror game and in each game a
  worker plays, and so do frozen copies (`@TAG`) of it. Lint warns on static fields that are not final. A `static final` collection or array that you
  change is shared state too.
- **Logging must not change decisions.** The harness checks that too, by replaying with logs on.

### Speed and robustness

- Think every second or few seconds, not every tick. `summary` prints each run's CPU cost per game; compare it with a
  run of the stock AI to see what your AI adds.
- Catch exceptions around your decisions and pass them to `log.error` (the template does). An exception that
  escapes `animate` crashes the game, which is then not counted, and the run exits 1.
- Check what you act on first (see How orders reach the game): the engine asserts, and the harness runs with `-ea`.

## Structuring your AI

The design is yours: nothing in the harness depends on how your AI is organised. A few habits pay off regardless:

- Read the state once per decision round into your own objects, then decide from those.
- Put every new behaviour behind a param whose default is your current best, so `batch --players "myai:k=v vs hard"` measures it
  against the default on the same games. Count it with `log.count` so summaries show whether it fired. Log its
  decisions under a topic of its own, so `grep ' TOPIC '` finds them in the decision log.
- Split classes by concern once the AI class grows (the package can hold any number of them).
- `AdvancedAI.java` in this folder is a complete, simple AI. Read it to see how the game works, but it predates these
  rules: it sets rally points with `Building.setRallyPoint`, leaves its chieftain to the stock chieftain AI, and uses
  `Action.DEFEND`. Copy those parts and lint refuses them (DEFEND only gets a warning).

## Your own tools

The harness's analyses (`summary`, `compare`, `show`, `curves`, `fights`) are generic. For questions about your AI,
write tools of your own: scripts that read recorded games, experiments, helper functions for them, notes on what you
tried. They go in `lab/<name>/` at the repository root and are committed on your AI's branch with it, as a
scratchpad and a record of how the AI was made. Nothing checks, formats or compiles them. `docs/aisim.md` (Your own
tools) shows how to run a Java tool and what it can read.

The AI must play without them, because the game ships only the AI's package:

- **Everything the AI runs during a game is in its package**, helpers included, and follows the rules above. If
  your AI analyses something to decide (the map at the start, say), that analysis is AI code.
- **Lab tools study games from outside.** They may use the AI's classes; the AI never uses theirs. The build does not
  compile `lab/`, so the AI cannot come to depend on it, and `freeze` and the game take only the package.
- **What a tool finds out goes into the AI as code**: a constant, a table or a param default, with a comment naming
  the tool and the run it came from, so it can be worked out again.
