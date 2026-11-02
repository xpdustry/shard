# Wire: schema, codecs, string limits

Detail for `ARCHITECTURE.md` §6, binding like the rest of it; `§N` in this file means a section of
`../../ARCHITECTURE.md`. Open it when you add or change a packet, a generated codec, a hand-written `:compat` codec or
a string limit. The gates L1–L5 and the version rule stay in §6.

**Schema and codecs.**
- `shard/wire/schema/v160.7.yaml` is committed. It is lifted from the jar by `:tools:wirelift` (proto 25
  `WireLift`); CI re-lifts it and fails on a diff.
- A Gradle task generates codecs into `com.xpdustry.shard.wire.v160_7` from the schema. They are committed under
  `wire/src/generated/java`, so the normal build never reads the jar; `checkWireGen` fails when they are stale.
- Entity layouts are small generated interfaces. `:compat` adapts frozen unit projections to them.
- Codecs dispatch by kind and revision through registered adapters. Save, payload, world-stream and sync are
  distinct contexts.

**Hand-written in `:compat`:**
- `writeBase` and the item, liquid and power modules;
- the world-stream writer;
- the 26 branching building bodies;
- Rules JSON (an Arc-JSON dialect writer over the `:sim` `Rules` model), objectives and markers;
- payload bodies.

`:wire` generates the records of the 15 JSON/UI/plan packets and decodes plans, `MenuResult` and `UiBuilder` with
vanilla's limits, because the server enforces those limits while decoding and L4 fuzzes every client decoder. Their
JSON payloads (rules, objectives, markers) stay raw strings for `:compat`.

**String limits** are set per codec and direction, as vanilla does:
- `readObjectSafe` strings: 1,200 encoded bytes;
- `writeUTF`: 65,535 bytes of modified UTF-8;
- and so on.

Test NUL and astral characters on both `readObject` paths.
