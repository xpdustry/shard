# PvP

Read before touching team assignment (`server.host.TeamAssigner`, `HostInputs.Join`), a PvP team's clean-up
(`sim.team.DeadTeams`), the world pause (`World.paused`, `sim.team.PvpAutoPause`, `sim.team.EmptyAutoPause`), the
console's `pause`, `save` and `load`, or `CoreChangeEvent`. Rows: W5.3, W6.4.
Index, ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths are relative to the repository root.

**PvP (W5.3 done: WP83)**
- Done:
  - team assignment through a pluggable `TeamAssigner` (`@Registry`; the registered one with the highest `order()`
    wins, `VanillaTeamAssigner` is 0): vanilla's `assignTeam` at a join over the added players (Q233's random tie-break,
    `Seq.min` keeping the last of equal values); vanilla's `null` (no active team) gives the default team;
  - teams on a map reload as `WorldReloader.end` assigns them: `Host.replace` hands the next world the old one's
    players in group order with their teams (`HostedWorld.seats()`, refreshed after each PvP tick), every `Join`
    carries that list, and the first join assigns the whole list in its order (`Roster`'s reload teams, checkpointed);
  - `cleanupDeadTeams`: a destroyed core posts vanilla's `CoreChangeEvent` check to the next tick (`Teams.coreChanged`,
    run by `DeadTeams` early in `TEAMS`); a PvP team without cores is destroyed to derelict (`destroyToDerelict`:
    `setTeams` to derelict in chunks of 1,000, `destroyPayload`, random building and unit kills, rebuild plans);
  - the world pause: `World.paused()` admits inputs and runs `COMMANDS`, `GAME_STATE` and `SYNC`, plus the handlers that
    `runsWhilePaused()` (the posted tasks, the enemy count, the dead-team check, the control pathfinder's flush);
    timers and game time stop, `ticks()` counts on (it stands in for vanilla's wall clock: the place-sound clock,
    FogControl's redraw gate, the pathfinder threads, RemoteSync, WorldSync's cadence). It is forked, hashed only while
    set, and checkpointed; the state snapshot sends it;
  - `ControlPathfinder.updateId` freezes while paused as vanilla's `state.updateId` does: it trails `ticks()` by the
    paused frames (`frozenTicks`), and its own `clock` keeps `ticks()` for the wall-clock uses (recompute time, the
    invalid check);
  - `pvpAutoPause` (`PvpAutoPause`, first in `SYNC`), and the console's `pause [world] <on/off>` (`server.game.SetPause`).
- Already on main and checked here: core capture (`Core.afterDestroyed`), the enemy build radius
  (`Teams.anyEnemyCoresWithinBuildRadius`, `Build.validPlace`), attack mode's game over (`CheckGameState`).
- Gates: L1 `PvpOracleTest` (compat oracleTest), exact every frame for 1,300 frames through a vanilla save, under
  `CommandRun`'s pins (formations run synchronously, vanilla's control pass on Shard's schedule, the construction
  clocks set from Shard's; review 45) and with two teams only: auto-pause
  and the state snapshot's paused byte, a commanded unit pathing across the pause, a refused and an allowed build by the enemy core radius, two core captures, the
  game over and the derelict clean-up, Calls byte for byte; unit tests `TeamAssignerTest` (one per assignment case and
  reload), `DeadTeamsTest`, `WorldPauseTest`, `PvpAutoPauseTest`, `WorldSyncTest`'s paused case; L5 `rig/scenarios/pvp.rig` (four clients on `pvp.msav`, from `PvpMap.java`).
- Deviations: `setTeams` reflows the linked buildings in the order it finds them, where vanilla walks an identity-hash
  `ObjectSet` (proximity-order exception); a scheduled building kill does nothing once the building is gone, where
  vanilla's `buildDestroyed` runs `killed()` on it again; `ticks()` counts on while paused (above); old checkpoints
  don't load, with a named `CheckpointException` (the world's `paused`, Teams' `coreChanges`/`pvpAutoPaused`, Roster's
  reload teams, control paths version 2's `frozenTicks`).
- Quirks: `pvpAutoPaused` starts true, so the first update lifts a pause made by hand; a reload counts the players after
  the one being assigned with their old map's teams; with every active team disqualified the last one still wins
  (`Seq.min`); `destroyToDerelict` plans the dead team's buildings for rebuilding right after clearing its plans.
- Gaps: `coreDestroyClear`'s `timeDestroy` for AI teams is W5.2's.

**Server leftovers (W6.4 done)**
- Done:
  - the empty-server auto-pause (`EmptyAutoPause`, ServerControl.java:320-335), per world: `game.auto-pause` (vanilla's
    `autoPause`, default off) reaches the world as a logged `SetAutoPause` (`server.game.AutoPauses`); with it on, a
    world with no player pauses and the first player resumes it, a pause made by hand survives (the console's `pause`
    clears the flag, as vanilla's does), and turning it off resumes a world it paused. Its flag (`Teams.autoPaused`)
    is apart from `pvpAutoPaused`, hashed only while set, checkpointed;
  - `World` takes the tick's pause once `COMMANDS` ran (was: at the tick's start), as vanilla runs its posted tasks
    (joins, console commands) and `Trigger.update` before `Logic.update`'s `isPaused` check: a console `pause` and the
    empty-server pause hold from the tick they are admitted in;
  - `sim.team.CoreChangeEvent`, from a core's `created`, `changeTeam` and `onDestroyed` (CoreBlock.java:593, :606,
    :708), heard by host listeners (plugins) with the intent and game events; a capture fires it three times as
    vanilla does (destroyed, created by `setBlock`, created again by the posted `setNet`);
  - the console's `save <slot> [world]`, `load <slot> [id]` and `saves` (ServerControl.java:1183-1232), over
    `persistence.saves-directory`: a save is vanilla's msav from the world-stream projection (`WorldSends.save`); a load
    hosts the slot as a new world with the save's rules and without `logic.play()` (`Host.hostSave`); slot names are
    `[A-Za-z0-9_-]{1,64}` and must resolve inside the directory;
  - Erekir's core units (evoke, incite, emanate) get `BuilderAI(true, 500)` on every team, as vanilla's lambda has no
    `isAI` test (UnitTypes.java:4352-4501, checked in the bytecode).
- Gates: L1 `PvpOutcomeOracleTest` (compat oracleTest, unpinned: `PvpRun.unpinned`, vanilla's threads and clocks as a
  server runs them, 60 frames a second, 1,100 frames): three teams, a join, two auto-pauses and resumes, malis
  destroyed to derelict while its commanded dagger walks a control-pathfinder path, then crux's core and the game over.
  Exact: every pause and resume, every change of the active teams, the game over's frame and winner, and every
  `CoreChangeEvent` (frame, core, team). Within tolerances: malis's units dead within 302 frames of its clean-up, 1 to 20
  of its 40 walls killed and the rest derelict, crux's field derelict or killed, the sharded dagger's arrival within 24
  units and 120 frames. `PvpOracleTest` still exact under its pins after the pause-timing change. Unit tests
  `EmptyAutoPauseTest`, `CoreChangeEventTest`, `ControllersTest`; server tests `GameFlowServerTest` (an auto-paused
  world, a plugin hearing a PvP team's cores die), `ConsoleCommandsTest` (save, saves, traversal refused, load back);
  L5 `rig/scenarios/m2.rig` step 5 (save, stop, load, both clients join the loaded base).
- Deviations: a new world plays its first tick before `game.auto-pause` reaches it (the listener sends it after a
  tick); `save` has no `embedAssets` (Shard keeps no asset cache); `load` hosts the slot as a new world instead of
  replacing the only one, and refuses an id already hosted; old checkpoints don't load (Teams' `autoPause` and
  `autoPaused`), with a named `CheckpointException`.
- Quirks: vanilla's `save`/`load` take any slot text (`saveDirectory.child(arg + ".msav")`), so `../x` reads or writes
  outside the saves directory (refused here); vanilla's Erekir core units build for player teams too.
