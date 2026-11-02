# Intents, snapshots and Calls

Read before touching `server.sync`, `server.intent`, `compat.v160.sync`, the `Outbox`, `CallMapping`s, the action
filter, config forwarding, or world streams. Rows: W2.13, W2.14, W2.20, W2.25, W4.3, W4.5. Their owned paths and WPs are in
[`history.md`](history.md). Index, ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths are relative
to `shard/`.

**Sync and intents (from "Connect flow, sync and the server")**
- Intents (W2.14 done, WP26): every client packet decoded into a bounded intent; `clientSnapshot`, `SyncComp`,
  client RPCs, forwards checked against vanilla's `Call.class`; the action filter (16 types, vanilla's rate limits
  and rollbacks, deny rules as world state). `intent-walk.rig` and `shard-intents.rig` passed on a scratch build
  with W2.13 and are rerun by W2.13 after merging. Open:
  block-unit control (WP60); team rebuild plans for `deletePlans` (WP32); CommandAI formations and stances (WP54);
  transfer-item effect Calls; `lastAccessed`. Rate-limit windows are per player,
  not per uuid. For construction: each snapshot replaces `unit.plans` with the client's filtered queue.
- Sync (W2.13 done, WP25): the `Outbox` (audiences, encode-once, per-join buffering on load generations; a loading
  session's overflow kicks with "join again"); snapshots at vanilla cadence and byte-exact against vanilla fed the same random and universe time
  (`SnapshotOracleTest`; vanilla seeds its random from the clock once per JVM);
  events → Calls through `CallMapping`s in vanilla's order (`CallOrderOracleTest`, `CoreSyncOracleTest`); W2.14's
  `IntentCall`s are `Outbound`; replay hooks (`WorldSync.record`, match checkpoints). Rig: cadence 16/16, per-join
  buffering, core items and the wave timer on a stock client; router divergence 8.5% on Shard vs 13.1% on vanilla at
  5 min, so review 38's proximity exception holds. Open: stateless Calls (chat, messages, menus, effects) are lost in
  a seat's join tick and between a `/sync` begin and its capture, and on a sync-observer overflow (state is
  resynced); `outbox().offer()` from other threads is unbounded per world (closed by W4.5: bounded, below); `Audience.At` means everyone until fog (still everyone after W4.3, whose fog is cosmetic);
  match checkpoints lack the command log between them; the session viewer's view size (W2.19); `fetch` (processors). Deviations: `GameOver` maps to `updateGameOver` (vanilla's
  dedicated server never sends `gameOver`); an empty entity snapshot as a heartbeat.
- Found by W2.13: a live-placed unported block made the next join's stream throw and took the world down. W2.19
  fixes the writer; W2.4h makes a stream failure kick only the joining session. Testkit
  `WorldAdapter`s for cores and wave state; units in the world stream aren't fog-filtered (closed by W4.3 for live units, `fog.md`); name bans.

**Call order and the UDP path (W4.5 done: Q215)**
- Done:
  - the server hands a connection's TCP and UDP frames over in one order: one queue per connection, with pending TCP
    written before each datagram (Q240). Q215 is fixed; past the kernel the order is as vanilla's;
  - `Outbox.flush()` runs once per tick (`HostedWorld` calls it, and `Transmit.flush(Session)` for a session), and a
    writer is woken early at 32 KiB waiting;
  - `Outbox.offer` returns a boolean and refuses the newest frame past 1,024 frames or 1 MiB;
  - packet and chat spam DOS-ban the address (Q242); no per-recipient `ByteBuffer`, no per-packet decode strings.
- Gates: `SendOrderTest` (`net`); 9 of 9 breaks fail (`scripts/breaks/w4.5-send-order.py`); L5 `payload-drop-cycles.rig`: main
  missed 3 of 190 drops and W4.5 0 of 200, and over both rigs main failed 4 of 30 runs and W4.5 0 of 30
  (`evidence/w4.5/`); a full check clean on `e93e805f`.
- Deviations: the per-connection hand-off order is stronger than vanilla's; frames go out at the end of the tick or at
  32 KiB waiting; the unreliable cap drops datagrams; `Outbox.offer` refuses past its bounds; the DOS list is checked at
  the connect packet, unchanged.
- Quirks: Q240–Q243. Gaps: no order past the kernel; stateless Calls are still lost in a join tick (out of scope);
  the writers allocate small JDK wake objects.
- Open: world processors' Calls are bounded only by the 256 KiB write cap, which closes the client as slow (Q243). Bound
  world-processor Calls per world.

**World streams (from the review 38 routing, done)**
- W2.12: world streams project live entities (`EntityProjector`), not the map's saved chunks; `UnitSink` reads the
  old revisions `:wire` decodes.

**Open from reviews 39 and 40**
  Open from W2.20:
  - the world stream still sends `MapState`'s seeds, so a joiner's random is wrong until its first state snapshot;
    fixing it moves W2.19's at-load golden;
  - a config key for universe seconds (vanilla's `utimei`) for `SyncSettings.withUniverseSeconds`, at
    `Server.java:119` (W2.4h);
  - tech-node configs forward tag 9 where vanilla sends null.
- Open from W2.25:
  - past 1 MiB of processor code in one tick, a plan's code is dropped where vanilla applies it;
  - a snapshot plan's code is charged on every snapshot.
