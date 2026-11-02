# `tools/fuzz`: client RPC fuzzer (WP93)

Fuzzes everything a client can send to Shard, over real loopback connections into an in-process server, and checks the
server's invariants while it runs.

## Run it

```sh
# the short run in `check` (FuzzSmokeTest): 20,000 messages, 2 worlds, 8 fuzz clients
scripts/vm-gradle.sh :tools:fuzz:test

# a campaign; the report goes to stdout and to <out>/report.txt (exit 0: nothing broke, 1: something did)
scripts/vm-gradle.sh :tools:fuzz:fuzz -Pargs="--packets 10000000 --clients 64 --out build/fuzz/big"
```

Run campaigns on a boat VM, never on the dev box: the server, its four worlds and the clients are one JVM (4 GiB heap,
`-PfuzzHeap=` changes it).

| Option | Default | Meaning |
|---|---|---|
| `--packets` | 100,000 | messages to send in all: the run's bound (a count, not a time) |
| `--maps` | `maze,fortress,glacier,triad` | one world per map |
| `--clients` | 32 | fuzzing clients |
| `--rate` | 400 | messages a second per fuzz client, at most |
| `--mutate` | 30 | % of payloads damaged after generation |
| `--frames` | 4 | per mille of frames damaged around the payload (each closes its connection) |
| `--seed` | 1 | client `i` uses `seed + i` |
| `--out` | `build/fuzz` | report, server log, replays, violation dumps |
| `--heap-growth` | 512 | MiB the live heap may grow over its baseline |
| `--audit` | true | replay every recorded match at the end |

## What it sends

Each fuzz client reconnects again and again, from a fresh `127.x.y.z` address (a kick's cooldown or the DOS list never
stops it, and the control clients on 127.0.0.1 are never blacklisted). Each connection is one of:

- **garbage** (4%): frames, half frames and noise on a raw socket before or instead of the handshake, and a stray
  datagram from an unknown address;
- **connect fuzz** (4%): the handshake, then a mutated `ConnectPacket` (version, names, uuid and its CRC, mods) and
  packets before the server answers;
- **player** (92%): a valid join (10% fuzz before they confirm), then 500 to 50,000 messages, then it leaves.

Messages (`Gen`, `Mutate`):
- **structure-aware** bodies for the 38 client Calls of note 25 §7 (confirm,
  chat, ping, `requestWorld` and `requestAssets` included) and `ConnectPacket`, written with `:wire`'s generated records. Values are, by turns, a live
  target in the sender's world, a target in **another world** (unit, player and building ids, positions), a boundary
  (0, -1, extremes, NaN, ±∞, `0xffff`), or noise; lists are empty, short, or thousands long (`deletePlans`,
  `commandUnits`, `requestAssets`, nested `Object[]`, 1,000+-element arrays); strings are null, empty, long, multibyte,
  lone surrogates, markup, and chat commands (`/world`, `/sync`, `/votekick`);
- 6% server-only ids and 2% ids past the registry;
- **payload mutation** (30%): bit flips, interesting bytes, i16/i32 length fields set to extremes, truncation, extension,
  repeated slices, swaps, deletions;
- **frame mutation**: odd but legal compression (5%), and 0.4% of frames broken: a wrong raw length, a bad compression
  flag or damaged LZ4, a bad TCP length prefix, a truncated header;
- 3% framework messages (pings, keepalives, discovery, `RegisterUDP`/`RegisterTCP` with wrong ids, unknown types), over
  TCP or UDP; reliable packets go over UDP and the reverse 20% of the time.

The packet spam limit is raised (`net.packet-spam-limit`) so that packets reach the decoders; the chat spam limit and
every per-packet rate limit are vanilla's. The spam ban itself is tested by `net` (W4.5).

## Invariants

Checked every second (`Campaign`); each break is printed, listed in the report, and dumped with every fuzz client's last
64 messages (hex) to `<out>/violations/`.

| Invariant | How |
|---|---|
| no server exception escapes | a Logback appender on the root logger: any ERROR, and any WARN with a throwable (the transport's "reader failed", a world's "failed at tick"); uncaught exceptions; a world `FAILED` |
| no world stops ticking or stays over budget | over every 10 s, each world ran at least half its ticks; no single tick over 1 s; every world answers an inspection. Watchdog stalls (one tick over 5× its budget) are counted in the report, not violations: with 128 clients in the server's JVM every world stalls in the same second, a process-wide pause |
| per-world state stays valid | every 5 s, on each world's thread: units and players finite and on the map, health and rotation not NaN, no negative item counts or stacks, a player's unit on the player's team, no unit held by two players, no unit moved to another team while a player holds it |
| … and replays cleanly | `replay.record` is on; at the end every match bundle is made again by `Regenerator` from its inputs, and every live hash must match |
| no session affects another world | the replay rebuilds each world from its own sessions' inputs only, so a write from another world shows as a hash difference; the control clients stay in their worlds |
| memory stays bounded | every 15 s, the live heap after a GC stays under the baseline + `--heap-growth` |
| a control client in each world keeps playing | it walks, shoots, builds and breaks a conveyor line, chats and pings; it must stay connected and get entity snapshots and ping replies at least every 10 s |

## Reproducing a finding

The dump names the session and its last messages. A minimal test usually needs one of them: decode it with
`Intents.decode` (see `server/.../intent/IntentDecodeTest`), or send it with `TestClient` into a server as `ServerTest`
does.
