# Payloads

Read before touching `sim.blocks.payload`, the unit payload model, the payload intents and Calls, or any block that
picks up or drops buildings and units. Rows: W3.9, W4.1, W4.4. Their owned paths and WPs are in [`history.md`](history.md). Index,
ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths are relative to the repository root.

**Payloads (W3.9 done: WP65)**
- Done:
  - the payload model: `UnitPayload`, `BuildPayload`, and the carrier's `PayloadSlot`, whose power graph has no
    updater. A building can leave its tile, follow its carrier and be placed again with vanilla's re-init. Save and
    sync bytes are in `PayloadBodies`;
  - `PayloadBlock`, the payload conveyor and router, with codecs and the block snapshot vanilla sends after a dump;
  - `PayloadComp` as `Payloads`, and pickup and drop hooks for 19 kinds;
  - the three payload intents through W2.14's filters, and the three payload Calls;
  - `AiController.unloadPayloads`.

  `PayloadOracleTest` is exact frame by frame, through vanilla's save, with Calls byte for byte. That covers
  conveyors and routers (1,000 frames) and a mega and a quad picking up and dropping (640 frames): a container keeps
  its copper, a drill keeps drilling, a battery rejoins the power graph. All 6 breaks fail. A checkpoint and a fork
  taken mid-carry continue bit for bit. L5: `shard-payloads.rig` passed 39/39 with 2 stock clients.
- Open:
  - factory outputs (WP66; done by W4.1, `units.md`); `setprop @payloadType` (done by W5.7, `processors.md`);
  - units entering blocks (`onControlSelect`, done by W5.7): payload blocks, conveyors, routers and cores, with the new
    id vanilla posts to the next frame, from `ucontrol payEnter` and a player's `buildingControlSelect`;
  - a loaded carrier stays as bytes when it holds a kind without a codec (W4.4 lifted the payload-block case);
  - carrier entity snapshots have no oracle (the rig shows stock clients read them);
  - `Conn` sends UDP at once but queues TCP for a writer thread, so a tick's Calls can arrive after its snapshots
    (Q215; for `net`). Closed by W4.5: the server hands a connection's TCP and UDP frames over in one order (`server.md`),
    and Q215 is fixed.

**Advanced payload blocks (W4.4 done: WP80)**
- Done:
  - the constructors (`BlockProducerBuild`, `Constructor`) and deconstructors (`PayloadDeconstructor`), the payload
    mass driver (`PayloadMassDriver`: its state machine, links, the hand-over with the receiver's 11-tick wait, Q224),
    the payload loader and unloader (`PayloadLoaderBuild`, `PayloadLoader`, `PayloadUnloader`: items, liquids and
    battery power into and out of a carried building), and the payload source and void, with codecs;
  - `PayloadCosts`: a payload's requirements and build time, unit types' through their reconstructor chain as vanilla's
    `getTotalRequirements`/`getBuildTime` (Q222);
  - nested payloads in saves and streams: a `Tape` measures a carried payload block's own payload in its reader's
    `WireContext`, and `SavedUnits` replays its ids and draws, so a carrier holding a payload block with a payload comes
    to life (Q220: a lifted payload conveyor dumps its item, so only payload blocks carry one);
  - the loader's `basePowerUse` is a baked field now (the extractor keeps the protected field).

  `PayloadBlocksOracleTest` is exact frame by frame through vanilla's save, Calls byte for byte, save chunks at the end:
  a constructor's walls carried by conveyors to a deconstructor (1,400 frames), a payload source's containers and
  daggers thrown by three mass drivers across two gaps into a loader, an unloader and a void (2,200 frames), and an oct
  carrying a mass driver holding a dagger and a loader holding a container through the save (600 frames). It also holds
  every unit type's and block's costs to vanilla's. `LivePlacedOracleTest` streams and saves all seven kinds built by
  construction. `PayloadBlocksTest` checks the same in Shard alone, with a checkpoint and a fork mid-run.
- Open:
  - the payload source's `UnitCreateEvent` stat (`ObjectiveState.unitCreated`) and the `@Override`s on its command hooks
    waited for W4.1's merge. Closed by W4.1, which wired both and reran `PayloadBlocksOracleTest` (4/4),
    `LivePlacedOracleTest` (4/4) and `PayloadOracleTest` (2/2) with Q219's push fix (W4.1's report);
  - covered by W5.8 (`PayloadGapsOracleTest`, exact through vanilla's save, puddles compared): a deconstructor pouring
    a building's liquid (Q225), a loader charging a battery and an unloader draining it, and the large variants (large
    constructor, deconstructor, large payload mass driver).

**Factories and RTS notes (W4.1, W4.2)**
- W4.1: factories and reconstructors move units as `UnitPayload`s through `PayloadBlock`; the section is in
  [`units.md`](units.md). Q219 (`pushOutput`'s `Tmp.v1`) is ported in `PayloadBlock` and `PayloadBuild.moveOutPayload`.
  Q244 (a reign doesn't fit a payload conveyor) and Q245 (a held capped unit stops a factory for good) are kept.
- W4.2: `unitEnteredPayload` (`PayloadEvent.EnteredPayload`, mapped in `PayloadCallMapping`) removes the unit even when
  the block then refuses it (Q239, kept).
