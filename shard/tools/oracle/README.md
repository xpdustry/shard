# `:tools:oracle`: vanilla v160.7 as a test oracle

TL;DR: this module boots vanilla Mindustry v160.7 headless inside a test JVM, with no sockets and no threads. With it
you can:

- decode world streams, packets and entity snapshots; every error names a byte offset and a section;
- get vanilla's exact bytes for any world, unit, building or `Call` packet;
- step the simulation tick by tick;
- regenerate the raw data dumps in `shard/data/vanilla-v160.7/`.

Use it from tests only. It is never a runtime dependency of Shard.

```kotlin
// some-module/build.gradle.kts
dependencies { testImplementation(project(":tools:oracle")) }
tasks.test { forkEvery = 1 } // vanilla is static globals: one JVM per test class
```

The vanilla server jar must be at `~/.cache/shard/vanilla/v160.7/server-release.jar`, or you pass it with
`-PvanillaJar=/path/server-release.jar`. Nothing else is needed. The jar has the maps and `icons/icons.properties`
that the headless content load reads. Bundles are not needed: `loadLocales = false`, so `localizedName == name`.
Sprites and sounds are not needed either.

## Boot and state

```java
Vanilla.boot();                    // once per JVM, idempotent; every entry point calls it
Vanilla.reset();                   // menu state, entity ids and Time.time from 0, seeded rand, no captured packets
Vanilla.host("fortress");          // as the server's `host fortress`; also "serpulo/groundZero", "Ancient Caldera", a .msav path
Vanilla.emptyWorld(32, 32);        // stone world, playing, no game over
Vanilla.frame();                   // one server frame: posted tasks, Time.update, logic.update, netServer.update
Vanilla.net(NetMode.client, () -> Call.sendChatMessage("hi")); // net pretends to be a client; sends are captured
```

`Vanilla`'s javadoc lists every global it sets: `Core.app/settings/files/graphics/audio/input`, `Time`'s delta
provider, `Log.level`, `Vars.headless/net/content/world/state/logic/netServer/netClient/...`. `Vars.player` stays
null, as on a dedicated server. The decoders swap in `Vanilla.localPlayer()` while they read as a client. Pass
`-Dcom.xpdustry.shard.oracle.log=warn` to quiet vanilla, and `-Dcom.xpdustry.shard.oracle.dataDir=...` to keep its settings directory; the
default is a temp dir.

## Decoders (`Oracle`)

```java
WorldView w = Oracle.decodeWorldStream(bytes);       // zlib or raw: NetServer.sendWorldData's payload
w.sections();                                        // [Section[name=rules, offset=.., length=..], ...] (inflated offsets)
w.building(16, 16).fields().get("len");              // block-specific fields; items/liquids/power/config too
System.out.println(w.json());

PacketView p = Oracle.decodePacket(id, body);        // body = after id/length/compression byte
PacketView q = Oracle.decodePacket(id, body, Direction.toServer); // Loc.both packets differ per side
PacketView r = Oracle.decodeFrame(serialized, Direction.toClient); // [id u8][len u16][lz4 u8][body], LZ4 handled
p.fields();                                          // [FieldView[name=player, codec=TypeIO.readEntity, offset=0, length=4, value=..]]

EntitySnapshotView s = Oracle.decodeEntitySnapshot(entitySnapshotBody); // or (amount, data)
s.entities().getFirst().fields().get("x");
```

A failure throws `DecodeException` with `section`, `sectionOffset` and `offset`:

```
world stream: map (starts at byte 101): the stream ends inside this section at byte 150 (+49 into the section): EOFException
world stream: map (starts at byte 101): block run at tile 2304 covers 256 tiles, past the end of the map at byte 195 (+94 into the section)
packet StateSnapshotCallPacket#141: field rand0 (starts at byte 19): the body ends inside this field at byte 22 (+3 into the section): ...
```

World-stream decoding is stricter than vanilla. It checks building and entity chunk lengths, which
`readChunkReads` skips, and reports unknown content ids in `warnings()`. Offsets are into the inflated stream.

## Reference encoders (`Golden`)

```java
Golden.worldStream("fortress").deflated();           // exact sendWorldData bytes for a new player "oracle"
Golden.worldStream(player).raw();                    // current world, your player
Golden.unit("dagger").sync();                        // writeSync bytes (after i32 id, u8 class id); .save() = write
Golden.units();                                      // all unit types, in id order (block units: not added)
Golden.building("conveyor").chunk();                 // u8 version + writeAll, as in the map section
Golden.building(Blocks.conveyor, 0, b -> b.handleItem(null, Items.copper)).write();
Wire.Frame f = Golden.call(() -> Call.sendMessage("hi")); // f.id() 106, f.body(), f.serialized(), f.tcp()
Golden.call(() -> Call.kick(Vanilla.connection(), "bye")); // a to-one-connection call
Golden.calls(NetMode.client, () -> Call.tileConfig(p, build, Items.copper)); // client -> server form
Golden.packet(snapshot, Direction.toClient);         // any packet object you filled in
Hex.of(bytes); Hex.parse("6a 00 05 00 ...");
```

## Simulation (`Sim`)

```java
Sim sim = Sim.empty(16, 16);                         // or Sim.host("fortress")
sim.place("mechanical-drill", 2, 2, 0);              // tile.setBlock, team sharded; returns the Building
sim.spawn("dagger", Team.sharded, 64f, 64f);
sim.step(600);                                       // 600 server frames = 10 s; Time.delta == 1 per frame
int frames = sim.until(() -> sim.build(4, 4).items.total() > 0, 600);
System.out.println(sim.state().json());              // tick, wave, buildings (+fields), units, core items
```

Measured with the tests in `SimTest`:
- The basic conveyor moves exactly 5 items/s, which matches its stat.
- The titanium conveyor moves 11.3/s, while its stat says 10.

## Dumps

```
cd shard && ./gradlew :tools:oracle:dump      # writes shard/data/vanilla-v160.7/ (about 11.5 MiB, about 4 s)
```

`content/<type>.json` holds every field of every content object, by reflection. The other files are
`content-ids.json`, `packets.json`, `entities.json`, `buildings.json`, `rules-default.json` and `maps.json`. Their
conventions (`$ref`, `$lambda`, `$same`, ...) are in the generated `shard/data/vanilla-v160.7/README.md`. The output
is deterministic, and `DumpTest` fails when the committed dumps are stale. Field orders in the packet, entity and
building dumps come from bytecode (`Layouts`), via the JDK 25 ClassFile API. `ops` are in write order, with `loop` and
`if` marks.

## Caveats

- One vanilla per JVM. Tests in one class share it, so call `Vanilla.reset()` or `host` per test.
- Deterministic: entity ids, `Time.time`, `Mathf.rand` and `GlobalVars.rand` are reset. Not deterministic: the pathfinder threads
  that `Vars.init` starts, which ground units moving along flow fields depend on.
- A decoded world does not write back byte-equal in two sections, `rules` and `team plans`. Vanilla's client adds
  TeamRules for all 256 teams on load (see `WorldStreamTest`).
- Legacy blocks (`LegacyBlock`) only read; their `write` is not their `read`.
- Block-unit types (turret-controlled units) can be created but not added to the world.

## Layout

| Class | What |
|---|---|
| `Vanilla` | boot, reset, frames, hosting, net modes, packet capture |
| `Oracle` | decoders and the JSON-able views (`WorldView`, `PacketView`, `EntitySnapshotView`, ...) |
| `Golden` | reference encoders |
| `Sim` | scenario stepping |
| `Wire` | packet framing (vanilla's `PacketSerializer`), LZ4 |
| `Layouts` | serializer field order from bytecode |
| `Views`, `Json`, `Hex` | object → JSON views, Jackson setup, hex |
| `dump.*` | `./gradlew :tools:oracle:dump` |
