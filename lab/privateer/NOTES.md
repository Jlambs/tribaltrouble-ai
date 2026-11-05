# privateer: working notes

Computer player for Archipelago maps, started 2026-11-05 as a copy of `corsair` (`@v15`, branch `corsair`) without
the tactics that leaned on engine bugs upstream has since fixed. Benchmark settings as corsair's: archipelago,
tropical, hills 0, trees 10, supplies 10, 60-minute games at `--speed ludicrous`, `--rng 1`. Tools:
`lab/privateer/eval.sh NAME SPEC OPPONENT [formats]` (1v1 seeds 1..50, 2v2 seeds 1..25, 100 games each) and
`lab/privateer/queue.sh TAG OPPONENT FORMATS NAME:PARAMS...` (variants one after another; a batch already uses every
core). corsair's own tools in `lab/corsair/` (quirks.py, survey.py, league.sh) read privateer logs too.

## The engine it plays on

The branch now holds upstream main at afccf4f7 (2026-11-03), merged through `headless` and `boats`. What upstream
changed that corsair had used or worked around:

| Upstream change | What it did to corsair |
|---|---|
| `ShipHR` (9db11fd2, 2026-10-11): leaving the upper deck takes back the full +10 range a seat gave, not 8 | The deck drill (corsair's `Ratchet`, "exploit audit A12") gave +2 range per trip for good. Now a trip gains nothing and costs the warriors' time. Everything built on the drilled range went with it: kiting (`kite`: a warrior that outranges its nearest threat backs off; with all weapons at THROW_RANGE 6 no land warrior outranges another), `drill_adapt`, `elite_trips`, `drilled_garrison`, `late_drill` / `late_trips` / `late_idle`, the launch and abort rules' range discount (`range_edge`, `edge_floor`, `abort_edge`), `snipe_towers` and `militia_smart`. |
| `ShipHR.canFit` (9db11fd2, 670e9e5a): the chieftain sits only in his own seat, never on a bench, and gets no deck range | corsair kept `chief_rides` off because a chieftain alone on a bench was handed an oar and tripped an assertion (a crash). Now safe. |
| `Player.deployChieftainFromShip` (9db11fd2): a new order lands the chieftain | This fork's own fix (the button sent `trainChieftain(ship, true)`) gave way to it; `Ship.trainChieftain` ignores the order again. corsair now uses the new order. |
| Region links checked for land in between (19d54725), starting ships inside the sea map (23834660), islands labelled with diagonals and undockable islands dropped (b54b35a6, 69f46860), a guard for islands with no dockable cells (70f1c666) | Fewer stuck ships; the `ShipTrajectory.pickTargetPosition` crash in corsair's REPORT (an island with no contour points) is fixed upstream. Islands and their ids differ from before on some maps. |
| Magic is cast at once unless the chieftain is stunned (56fb74bb); entering quarters wins over repairing them (4add67d0); a full weapon stock no longer resets an endless order (107e5917) | Rules of play, not exploits. |

Still valid upstream, kept: the swing restart and stun cancel reflexes (orders on exact ticks; HarvestBehaviour,
StunController and Unit.stun are unchanged), tower fire control (an order to a tower; a garrison now reaches the
weapon's 6 + the tower's 8, its scan range), and warriors fighting from the upper deck of a docked ship, which
legitimately throw 10 cells farther while seated (`deck_defence`, `bombard`).

## v0: corsair without the drill

`@pv0`: corsair's code with `Ratchet` removed (its deck-defence half moved to `Decks`, which also lands any
warriors left aboard idle ships at home), kiting, tower sniping and garrison swaps removed, and the params listed
above gone. `ShipHR.removeUnit` keeps a dead warrior's deck range, but only for the dead: no other path keeps range.

- vs `@cs15` (corsair at its v15 defaults on the merged engine, still drilling for nothing): 1v1 W34 L15 D51,
  score .595 [.497, .686], kd30 -15, margin +.13; 2v2 W63 L2 D35, score .805 [.717, .871], kd30 +126, margin +.62.
- Why corsair still wins some 1v1s (replay of s5-1 with logs): its drill now just keeps ~100 warriors seated on
  ships docked at home (aboardWarriors 96-125 between 15 and 25 min), where they legitimately throw 10 cells
  farther. Those warriors are not in its unit set, so our launch rule and beach choice never saw them: seven
  assaults of 70-98 warriors on its island traded about 1:1 (kills +28/-25 ... +106/-76) and the game was drawn.
  Params for it: `deck_weight` (count crewed enemy ships docked near the target in its defence), `deck_beach`
  (beaches near them are dangerous) and `deck_garrison` (do the same at home on purpose: idle warriors keep that
  many seats of each idle home ship filled; an op takes them along, and the launch rule counts them as free).
