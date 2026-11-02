# Review routings (reviews 38–41)

How each finding of reviews 38–41 was routed and fixed. All ✓ here; the items still open were moved
to their area files (server seed → [`game-admin.md`](game-admin.md); reviews 39 and 40 → [`sync.md`](sync.md);
`ContentRef.Foreign` → [`wire-content.md`](wire-content.md)). Read when a code comment or test names a review fix. Paths
are relative to the repository root; index: [`WAVES.md`](../../WAVES.md).

**Review 41 (wave 3a), routed** (note 41, verdict: major)
- W3.8 ✓ (`97994f78`), the known leaks: a pending game-over reload is cancelled when its world stops or the server
  closes; coreless test worlds no longer end; `LifecycleTest` confirms every load.
- W3.8 ✓ (`6008b8a5`), one more: `LifecycleTest` failed once under a full check's load because `TestClient` sent no
  keepalives and the server's 12 s read timeout closed a quiet client. It sends Arc's keepalive now
  (`ServerTest.aQuietClientOutlivesTheReadTimeout`), and the waits list every remaining session on timeout.
- W3.8 ✓ (`f435f90b`): a join or transfer that lands in a retired world is rechecked, then kicked or retried into
  the replacement (`RouteRaceTest`); a retired world's mailbox is released, and `HostListener.retired` drops its votes;
  each world shuffles from its own random, seeded from the server seed (logged at start) and its id. Open: the
  server seed isn't configurable; a world stopped in the middle of its own reload.
- W3.1 ✓ (`b1b21836`): `DriverBolts` finds payloads by bullet id, so 10, 100 and 1,000 bolts cost N visits a frame
  (`DriverBoltsCostTest`); `BlockRun` resets vanilla's random and id counter before the load, and the reload fixtures
  compare absolute ids. An extra id in Shard's `MapLoader.load` fails all four at the load.
- W3.5 ✓ (`41fcc3f4`): a door chain is one shared `DoorChain`, so checkpoints grow by about 9 bytes per door from
  100 to 5,000 doors (`DoorChainScaleTest`); `DefenseRun` loads both sides from seed 0 and id 0 and compares right
  after the load. A 17-door cross splits and rejoins through forks, restores and a save, exact for 1,200 frames.
- W3.2 ✓ (`32d2b298`): `CraftRun` loads both sides from seed 0 and id 0 and compares right after the load; a mid-ramp
  heat save is a fixture. An extra draw in `SeparatorCodec.read` fails it at the load.

**Review 40 (wave 2c), routed** (note 40, verdict: blocker)
- W2.25 ✓, all six fixed:
  - the negative build-plan rotation (the blocker): refused at admission and normalised in `Build`
    (`PlanRotationTest`, `RotationBoundTest`);
  - plan configs: they go through `admitConfig` and the per-tick budget, and `constructFinish` sends only what was
    applied (`PlanConfigServerTest`, `PlanConfigTest`, gated on counted work);
  - block units are in the hash. A restored block unit now keeps its size and health (`BlockUnitHashTest`);
  - `deletePlans` is indexed, with vanilla's result (`DeletePlansOracleTest`, `DeletePlansCostTest`);
  - the fire lookup is per object: 2–4 ns instead of up to 787 ns (`FireObjectsTest`, `FireLookupBench`);
  - `AiHashToggleTest` gives the AI's toggle proof against `0x57ca30d624dd9d58`.
- Pinned schedules for construction's place sound and the AI's pathfinder: declared in ARCHITECTURE §1, with the
  gates' wording qualified above.

**Review 39 (wave 2b), routed** (note 39, verdict: blocker)
- W2.20 ✓, all four fixed:
  - the double `TileConfig` forward (the blocker): rig `shard-config.rig` 52/52;
  - processor link-name bounds: 256 KiB per config, 64-byte names, and a 1 MiB inflate budget per world tick;
  - every safe config value admitted and forwarded once, with vanilla's bytes;
  - the state snapshot's live random and universe time.
- The capture budget's reservations and the stale encoder failure fencing: W2.4h ✓.
- Process-wide history (lightning seed, damage map, unloader pool) kept per world: declared in ARCHITECTURE §1.

**Review 38 (wave 2a), routed**
- Blocker, resolved as a contract: proximity order is a Tier B exception (`ARCHITECTURE.md` §1). The Router gate
  proves semantics under a pinned order; the sync rows measure divergence from stock clients against vanilla's own.
- W2.4 follow-up (after merge): generation-fenced world streams for joins, transfers and `/sync`; a byte budget
  for join captures; a reliable lifecycle path instead of the lossy observer; epoch-bound seat reservations;
  atomic admission with a moderation recheck at activation; the disconnect-vs-join-record race; buffering a
  joining viewer's frames between capture and confirm.
- W2.6 ✓: content-valued configs (`ContentRef`) across wire plans, building dispatch, unit sync and checkpoints.
