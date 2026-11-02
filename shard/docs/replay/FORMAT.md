# The view stream (`.sview`), formats 1 and 2

What a replay viewer plays: the world as a full-vision viewer saw it, frame by frame, with no simulation code. Any
producer can write it. Today these do:

- vanilla v160.7 through the oracle (`RecordVanilla`), format 1;
- a Shard `World` in a tool (`RecordShard`), format 1;
- the live Shard server's recorder and its replay service (`server.replay`), format 2. The service re-simulates a
  match's replay bundle and writes the same segment bytes the live recorder wrote
  (note 37 §5).

The reference encoders and decoders:
- format 1: `tools/replay/.../view/ViewWriter.java` and `ViewReader.java`, held to each other by `ViewStreamTest`;
- format 2: `server/.../replay/view/ViewWriter.java` and `ViewReader.java`, held to the live world by
  `LiveReplayTest`.

A viewer's decoder should read both; `LiveReplayTest` dumps a fixture (`build/reports/replay-fixture/`) to test one against.

**Design rules:**
- Content is named, never numbered. The header's name tables map file-local indices to vanilla content names
  (`duo`, `dagger`, `stone`). In format 2 the tables are the content epoch's whole tables, by content id. Any segment
  can then be made again on its own, without the live writer's state.
- The header's `game` field (`v160.7`) picks the viewer's asset pack. A stream outlives the build that wrote it.
- Each segment starts with a keyframe and is deflated on its own. A viewer seeks by inflating one segment and
  playing at most `keyframeEvery` ticks of differences.
- Format 2 records are lists of typed, length-delimited sections. A reader skips the types it doesn't know, so new
  sections need no new format.

## Encodings

- All fixed-width integers are little-endian: `u8`, `u16`, `u32`.
- `varint`: unsigned LEB128.
- `svarint`: zigzag (`(n << 1) ^ (n >> 63)`), then LEB128.
- `str`: `varint byteLength`, then that many bytes of UTF-8. At most 1024 bytes; writers cut longer text on a
  character boundary.
- **Quantization:**
  - positions (`q`): world units × 8, rounded. A tile is 8 world units, so one step is 1/64 tile.
  - angles (`rot16`): degrees × 65536 / 360, rounded, mod 65536.
  - fractions such as health and progress (`f8`): × 255, rounded and clamped to 1–255 when above 0, else 0.
- Tile index: `y * width + x`. A building sits at vanilla's `build.tile`; draw it at `(x * 8 + o, y * 8 + o)`,
  where `o = ((size + 1) % 2) * 4`.
- Compression: raw deflate (RFC 1951). The browser reads it with `DecompressionStream('deflate-raw')`.
- Segment hash (format 2): SHA-256 of the **inflated** segment, in lower-case hex.

## File

```
"SVIEW" u8 format(1 or 2)
u32 headerLength  header: UTF-8 JSON, whose format equals the byte above
u32 terrainLength terrain: deflate-raw(Terrain)
segment*          u32 length, deflate-raw(Segment)
```

Header fields (format 2):

| Field | Meaning |
|---|---|
| `format` | 2 |
| `producer` | free text, such as `shard dev` |
| `game` | the content version the names refer to, such as `v160.7` |
| `content` | Shard's content epoch hash |
| `map`, `width`, `height` | the map's name and size in tiles |
| `tickRate` | 60 |
| `frameEvery`, `keyframeEvery` | ticks between frames, and between keyframes |
| `start`, `ticks`, `frames` | the first tick (absolute), the length in ticks, the record count; ticks in the stream count from `start` |
| `teams` | `[{id, name, color: "rrggbbaa"}]`: the teams the stream mentions |
| `blocks` | block names by content id (floors, overlays, walls, props and buildings share one table); index 0 is `air` |
| `units`, `liquids` | unit type and liquid names by content id |
| `bullets` | bullet styles by content id: `{name, kind, sprite, width, height, front, back, shrinkY, lifetime, trailLength, trailWidth, trailColor, hitSize}`; numbers are finite (an endless lifetime is the largest float) |
| `effects` | event kinds, by index: `unit-death`, `block-destroy`, `wave`, `game-over`, append only |
| `match` | `{id, mode, pvp, fog, complete, winner}`: `complete` once the stream covers the match to its end, `winner` the winning team after a game over, else -1 |
| `terrain` | the terrain chunk's compressed length |
| `segments` | `[{offset, length, tick, frames, hash}]`; `offset` counts from the first segment's length prefix and is absent when served in pieces |

A bullet style's `kind` is the vanilla class family: `basic`, `artillery`, `missile`, `flak`, `laser`,
`continuousLaser`, `lightning`, `liquid`, `sap`, `shrapnel`, `point`, `rail`… Colours are `rrggbbaa`.

**Terrain** is the map at `start`. It is three layers in order, floor, overlay and env, each run-length coded:
`varint runs`, then `runs × (varint length, varint block)`. "Env" is the block of every tile that holds no
building: static walls, boulders, or `air`.

## Segment (format 2)

```
Segment  := Keyframe Frame*
Keyframe := u8 'K' varint tick Sections
Frame    := u8 'F' varint dtick Sections
Sections := varint n, n × (varint type, varint length, body)        types strictly ascending
```

- **In a keyframe** a missing section means empty: no buildings, no players. Status is always there.
- **In a frame** a missing section means no change. Units and bullets still move to their prediction (below).
- **A section type the reader doesn't know is skipped** by its length. A known section must be read to its last
  byte; anything left over is an error.
- **Keyframes carry no Events and no Chat.**
- **Gaps** are differences from the previous item of the same list, starting from 0.
- **Segments overlap by one tick.** Every segment but the last ends with a frame at the next segment's keyframe
  tick. That frame carries the events and chat of the ticks before the keyframe. Each segment is then a function of
  the world at its keyframe and the inputs after it, so a replay service can make it again from one checkpoint. A
  player moving from segment *i* to *i + 1* drops the duplicate tick.

### Sections

| Type | Name | Keyframe body | Frame body |
|---|---|---|---|
| 1 | Env | `varint n, n × (varint tileGap, varint floor, varint overlay, varint env)`: tiles that differ from Terrain | the same: tiles that changed |
| 2 | Buildings | `varint n, n × (varint tileGap, Building)` | removed, added, updated (below) |
| 3 | Units | `varint n, n × (varint idGap, Unit)` | removed, added, one update per survivor (below) |
| 4 | Bullets | `varint n, n × (varint idGap, Bullet)` | as Units |
| 5 | Events | — | `varint n, n × Event`: the events since the previous frame |
| 6 | Status | `varint wave, varint countdownTicks, varint enemies` | the same, when the wave or the enemy count changes, and at least every 60 ticks |
| 7 | Players | `varint n, n × (varint idGap, Player)` | removed, added, updated (below) |
| 8 | Chat | — | `varint n, n × (varint player+1, str text, varint age)`: player 0 is the server; `age` in ticks before the frame |
| 9 | Plans | `varint n, n × (varint unitGap, PlanList)` | the same, for the units whose queue changed: a whole new list, empty when cleared |
| 10 | Fires | `varint n, n × (varint tileGap, f8 life)` | removed tiles; added `(tileGap, f8 life)`; updated `(tileGap, f8 life)` |
| 11 | Puddles | `varint n, n × (varint tileGap, varint liquid, f8 amount)` | removed tiles; added `(tileGap, varint liquid, f8 amount)`; updated `(tileGap, f8 amount)`. A puddle whose liquid changes is removed and added again |
| 12 | Rebuild | `varint n, n × (varint tileGap, RebuildPlan)` | removed tiles; added `(tileGap, RebuildPlan)`. A plan that changes on its tile is removed and added again |

"Removed" is `varint n, n × varint gap`. "Added" is `varint n, n × (varint gap, record)`. A frame's list sections
always hold all their lists, possibly empty.

### Records

```
Building   := varint block, u8 team, u8 rotation(0-3), f8 health, u8 flags,
              [u16 aim rot16          if flags & AIM]
              [varint target+1, f8 progress   if flags & (CONSTRUCT | DECONSTRUCT)]
              flags: 1 ACTIVE, 2 AIM, 4 CONSTRUCT, 8 DECONSTRUCT, 16 SHOOTING
Unit       := varint type, u8 team, svarint qx, svarint qy, u16 rot16, f8 health, f8 elevation, u8 flags,
              [varint player   if flags & 128]
              [u16 aim rot16   if flags & 4]
              flags: 1 SHOOTING, 2 BOOSTING, 4 AIM, 128 PLAYER
Bullet     := varint style, u8 team, svarint qx, svarint qy, u16 rot16, varint age (ticks)
Player     := str name, u32 color (RGBA8888), u8 team, u8 flags (1 ADMIN), varint unit+1
Event      := varint kind, u8 team, svarint qx, svarint qy, u8 rotation/256,
              varint content+1 (0: none; a unit type for kinds starting with "unit", else a block),
              varint size×4, varint age
PlanList   := varint n (at most 512), n × (varint tile, varint block+1 (0: break what is there), u8 rotation, f8 progress)
RebuildPlan := u8 team, varint block, u8 rotation
```

- **Buildings.** A construction site has CONSTRUCT or DECONSTRUCT set. Its `target` is the block it becomes, or the
  one it takes down. A turret has AIM: `aim` is its turret rotation. SHOOTING marks a turret that fired this frame.
- **Units.** `player` is a key of the Players list. AIM is set while the unit shoots: `aim` is the absolute angle
  from the unit to where it aims, for players and AI alike.
- **Bullets.** `age` is the age when the bullet was added or keyframed. The displayed age is that plus the ticks
  since.
- **Rebuild plans** are a team's ghosts of its lost buildings (vanilla's `TeamData.plans`). Two teams rarely plan the
  same tile; a stream keeps the first team's newest.

Event kinds:
- `unit-death`: the content is the unit type, the size its hit size;
- `block-destroy`: the content is the block, the size its size in tiles;
- `wave`: the size is the wave number;
- `game-over`: the team is the winner (0 for none).

### Frame bodies of lists

- **Buildings:** removed; added (a building whose block or team changes is removed and added again); then
  `varint n, n × (varint tileGap, u8 mask, fields)` updated. The mask bits and their fields:
  1 `f8 health`, 2 `u8 rotation`, 4 `u8 flags`, 8 `u16 aim`, 16 `varint target+1, f8 progress`.
- **Units, Bullets:**
  1. removed;
  2. added, with velocity and turn 0;
  3. one update per **survivor**: each mover of the previous frame that wasn't removed, in ascending id order.
     The update is `u8 mask` and then:
     - **1, position:** `svarint dx, svarint dy`. These are the residual from the prediction
       `p' = p + v`, where `v` is the mover's last step. Without this bit the mover moves to the prediction.
       Afterwards `v = p_new − p_old`.
     - **2, rotation:** `svarint d`, the residual from the predicted rotation `r' = r + s`, where `s` is the
       mover's last turn (a signed 16-bit difference). Without this bit the rotation is the prediction.
       Afterwards `s = (short)(r_new − r_old)`.
     - Units only:
       - 4: `f8 health`
       - 8: `f8 elevation`
       - 16: `u8 flags`, then `[varint player]`
       - 32: `varint type, u8 team`
       - 64: `u16 aim`
  A bullet has no type or team update: one whose style or team changed (a deflecting wall, `Wall.java:199`) is
  removed and added again under the same id.
- **Players:** removed; added; then `varint n, n × (varint idGap, u8 mask, fields)` updated:
  1 `u8 team`, 2 `varint unit+1`, 4 `str name`, 8 `u32 color`, 16 `u8 flags`.

## Rules a decoder relies on

- **One keyframe per segment, first.** A segment is exactly one `K`, then `F*`. Every `dtick` is at least 1. Ticks
  count from the header's `start`; the header's `frames` counts encoded records, repeated boundary keyframes
  included. `keyframeEvery` is a multiple of `frameEvery`.
- **Gaps.** Every gap list starts from 0. The first gap may be 0; every later gap is at least 1, so indices are
  strictly ascending and unique.
- **List operations.** A removal names a live item. An addition never names a survivor. The same id may be removed
  and added in one frame, meaning "replaced".
- **Prediction is per encoded frame,** not velocity × `dtick`. Rotations are modulo 65536. A rotation residual or
  turn is the signed 16-bit difference (`(short)(a − b)`), so −32768 stands for half a turn.
- **Events** of a frame are those of the ticks in (previous frame's tick, this frame's tick], in the order they
  happened. An event's `u8 rotation` is the top byte of its `rot16`.
- **Quantization** rounds half up (`Math.round`, that is `floor(x + 0.5)`) on the `float` product. Inputs are finite.
- **The end.** A stream ends at its last sample. Events after it are not recorded.
- **Unknown bits.** A mask bit a known section doesn't define is an error. An unknown section type is skipped.
- **Hashes.** A reader may check each inflated segment against its index entry and refuse one that differs. The
  browser does where WebCrypto exists (a secure context).

## Limits

Streams come from the internet. A decoder checks everything before it allocates for it, and refuses the file with
an error rather than truncating:

- `width` and `height` are at least 1, and `width × height` is at most 4096² tiles;
- a `u32` length or section length is at most the bytes left, and so is a list count (every item takes at least a
  byte);
- a varint holds at most 64 bits, and a tick at most 2³¹ − 1;
- every index is inside its table: blocks, units, liquids, effects, bullet styles, and tiles inside the map;
- text is at most 1024 bytes, a plan list at most 512 plans;
- the terrain and each segment inflate to at most 64 MiB, checked while inflating;
- a segment is inflated only when it is played.

Both `ViewReader`s (`ViewReaderLimitsTest` for format 1) and `stream.ts` (`limits.test.ts`) enforce these.

## Serving it in pieces (HTTP)

A viewer can also fetch a stream lazily, segment by segment, from a base URL:

| Request | Response |
|---|---|
| `GET <base>/header.json` | the header JSON, as in the file (segment `offset`s may be absent) |
| `GET <base>/terrain` | the terrain chunk: deflate-raw bytes, without the length prefix |
| `GET <base>/segments/<i>` | segment `i`: deflate-raw bytes, without the length prefix |

Shard writes these as plain files and runs no HTTP server; any static host or CDN serves them. In a server's replay
directory (`replay.directory`):

```
views/<match>.sview     a baked stream, whole
views/<match>/          the same, split as above; a live match's grows as its segments close
views/index.json        the published streams, newest first: [{title, note, match, ticks, bytes?, file?, stream}]
bundles/<build>/<match>/ the replay bundle: match checkpoints, the input log split at them, the live hashes, the
                        recorded segments (replay/Bundle.java)
```

A live stream (spectating) is the same protocol, with `header.json` re-fetched for new segments. A PvP match's
stream is published only after it ends, unless `replay.pvp-views` is `live`.

## Versioning

- A reader rejects a `format` it doesn't know.
- New header fields and new event kinds are additive. Readers ignore what they don't know.
- Format 2: a new section takes the next type and needs no new format. A changed body of an existing section does.

## Format 1

What a format 1 stream does differently. Readers still read it: the vanilla and tool recordings are format 1.

- **No typed sections.** A keyframe is `u8 'K' varint tick`, then Env, Buildings, Units and Bullets as format 2's
  keyframe bodies, then `varint wave, varint countdownTicks, varint enemies`, in that order and always present.
  A frame is `u8 'F' varint dtick u8 sections`, then the sections whose bit is set, in this order: 1 Env,
  2 Buildings, 4 Units, 8 Bullets, 16 Events, 32 Status. A set bit the reader doesn't know is an error.
- **Header:** no `match`, `liquids` or segment `hash`. Bullet styles have no `name`. A `players` table lists player
  names, and a unit's `player` is an index into it. Name tables list only the content met, in the order met.
- **Units** have no AIM flag or aim field, and no update bit 64.
- **Bullets** store `u8 age`, clamped to 255.
- **Events** have no `age`.
- **No** Players, Chat, Plans, Fires, Puddles or Rebuild. A viewer makes its roster from the player-controlled units.
