# Runtime: threads, transport, sessions, joins, scheduler, JVM

Detail for `ARCHITECTURE.md` §7, binding like the rest of it; `§N` in this file means a section of
`../../ARCHITECTURE.md`. Open it when you touch a thread or pool, the transport, a session or world transfer, the join
protocol, the scheduler or the launcher's JVM flags. The world lifecycle and the binding rule stay in §7.

## Threads

| Thread(s) | Kind | Count | Owns |
|---|---|---|---|
| `tick-N` | platform | max(1, cores − 1) | world ticks, scheduled earliest-deadline-first |
| `udp-recv` | platform | 1 per port | UDP receive loop |
| reader + writer | virtual | per connection | socket IO only |
| `io-cpu` | platform pool | max(2, cores / 4) | deflate, checkpoints, big path builds |
| `io` | virtual | unbounded | DB, HTTP, files |
| `server` | platform | 1 | console, world lifecycle, transfers |

The total CPU budget is configurable. Everything queued is bounded: commands, jobs, bytes and join captures.

**Transport.** API: `Transport.bind(addr, Config, Handler)`, `Conn.sendTcp/sendUdp/queue/flush/sendStream/close`,
and immutable `Frame`s encoded once and shared.
- Config: reliable bytes queued soft-capped at 256 KiB for at most 3 s and hard-capped at 1 MiB, unreliable at
  256 KiB (a datagram past it is dropped), 12 s read timeout, 8 s keepalive, 300 packets per 3 s, a 10 s handshake deadline (accept to attach) and a 5 s deadline per TCP
  frame.
- **One queue per connection for TCP and UDP (Q215, W4.5).** Its writer hands frames to the kernel in queue order:
  pending TCP bytes are written before each datagram. So a tick's Calls leave before its snapshots, which vanilla
  guarantees only while its 41,000 B write buffer is empty. Past the kernel there is no order between the two
  protocols, on vanilla either: a stock client handles whichever socket its selector lists first.
- A world queues its tick (`Outbox` → `Conn.queue`) and wakes each connection once at the tick's end (`flush`), or
  earlier once 32 KiB wait, so a big tick drains while it is sent. No tick thread touches a socket or the
  `DatagramChannel`'s lock.
- Slow clients are disconnected for a sustained backlog, not a burst (W4.11). A reliable queue may go over its 256 KiB
  soft cap (`maxQueuedBytes`); the sweeper closes it once it has stayed over for longer than the grace
  (`overCapGraceMillis`, 3 s), with the reason `write queue over 262144 B for over 3000 ms`. A frame that would take it
  over the 1 MiB ceiling (`hardMaxQueuedBytes`) closes it at once, for memory (`write queue over the 1048576 B
  ceiling`). A write blocked past the write-stall limit closes it too. All three count in `NetStats.slowClients`.
  Reliable Calls are never coalesced. The soft cap holds a join's catch-up (at most 160 KiB) on a 200 ms client with
  room to spare (W4.5, `BurstBench`); a tick of 5,000 chat lines (about 275 KB, `LifecycleTest`) goes over it and
  drains within the grace.
- Packet and chat spam DOS-ban the address (`RateLimits.Action.BLACKLIST`), as vanilla's blacklist does.
- Handlers only decode and route intents (§4). Worlds emit frames and never touch sockets.

**Sessions.**
- `Session` (connection, identity), `Viewer` (what it may see) and `PlayerEntity` (in-world) are separate types.
- A `Session` owns the state machine `IN_WORLD → TRANSFERRING → LOADING → IN_WORLD`, its current `WorldId` and a
  monotonic **route generation**.

A transfer goes like this:
1. Reserve capacity in the destination and pin its epoch.
2. At a tick boundary of the source, fence old inputs, bump the route generation and remove the source player
   exactly once.
3. Send `worldDataBegin` and the destination stream.
4. Activate the destination player only after the matching `connectConfirm`.

Packets from an old route are dropped. If something fails before the source removal, the source stays intact. After
the removal, an explicit transfer record and idempotent cleanup take over. A same-process transfer keeps the socket.

**Join protocol.**
1. At one tick boundary, pin an immutable per-viewer projection, the epoch and a sequence number.
2. Encode and compress it off-thread with reserved capacity.
3. Buffer relevant reliable changes made after that sequence.
4. After the matching confirm, send a current baseline plus the buffered changes, in order.
5. If the buffer overflows or the epoch changes, retry the stream or disconnect, within a defined timeout.
6. Release all pinned data on success or failure.

Verify the exact catch-up order with the stock client. Never assume messages sent while the client loads are kept.

**Scheduler.**
- Earliest deadline first. A leader hand-off between workers is needed (a bug in note 24's prototype).
- Snapshot phases are staggered across worlds with a **mixed** seed: `new Random(worldId)` gave nearly identical
  phases. When every world sent snapshots on the same tick, about a fifth of the UDP data was lost.
- UDP snapshot fan-out moves off the tick thread above a load threshold. At 1,000–2,000 players it cut tick
  lateness p99 from 11–25 ms to under 0.4 ms. At 100 players it doubled CPU.
- Never spin on a virtual thread: in note 24, one CPU loop starved 1,600 others. Pinning was not a problem (JFR
  showed 0 pinned events).
- Each world keeps a tick histogram and a watchdog (stack dump when a tick takes more than 5× its budget).
- Per-system, per-world CPU accounting feeds `/shard profile`.

## JVM

**JVM (note 24, one synthetic run each):** generational Shenandoah (`-XX:+UseShenandoahGC
-XX:ShenandoahGCMode=generational`) had the best tail: p999 2.3 ms. G1 is the choice for small hosts: it was the
cheapest but had a worse tail. ZGC used twice the CPU on 4 vCPUs. The launcher always names its collector, so JDK
27's switch to G1 everywhere (JEP 523) changes nothing. Also `-XX:+AlwaysPreTouch`, with JFR always on; compact object
headers are JDK 27's default (JEP 534). An AOT cache cut startup from about 1.7 s to 0.7 s. Since JDK 26 (JEP 516)
one cache serves any collector: on JDK 27 a cache trained under either GC gave generational Shenandoah and G1 the same
classes (3,854 of 4,380, none read from jars) and its archived heap objects, and took 18 worlds from 2.3 s to 1.75 s
to `ready` (J27). The default format beat `-XX:+AOTStreamableObjects` (a diagnostic flag) by 0.1 s on a warm VM.
`bin/shard` starts from the cache of its build, JDK and install when there is one (`SHARD_AOT=auto`), and never trains
one on its own, because the JVM records no JFR while it trains. `bin/shard --aot-train` trains it on purpose, once per
deploy: a run of the bundled load generator beside the live server, on throwaway state, with the production
collector, heap and JFR options. It took the first join's longest tick from 8-16 ms to 2-8 ms (W2.4h).
