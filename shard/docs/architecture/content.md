# Content: format, pack, reload, kinds

Detail for `ARCHITECTURE.md` §5, binding like the rest of it; `§N` in this file means a section of
`../../ARCHITECTURE.md`. Open it when you edit YAML content, the vanilla pack or its extraction, a content reload, a
per-world overlay or a building kind (including passthrough). `ContentEpoch` stays in §5.

**Format.**
- **YAML** (snakeyaml-engine), with vanilla's field names kept flat. Keys: `type`, `id`, `parent` (list), `abstract`,
  `kind`, `wire`, then the fields.
- Inheritance is merged on raw YAML before typing, as in Space Station 14.
- Each field of a kind schema is tagged `server`, `shared` or `client`.

**The vanilla pack.**
- `:tools:content-extract` generates it from the fields that differ from a fresh instance of their class.
- Keep authored defaults separate from fully baked values. Shard computes the derived `init()` values (health,
  build time, capacities…) and checks them against the oracle.

**Reload.**
1. Build, bake and validate a candidate epoch off-thread.
2. Reject it before adoption if it contains:
   - unknown fields;
   - changes to unpatchable fields (vanilla's `@NoPatch`: `size`, `hasItems`, …);
   - unsupported kinds.
3. Apply `server` field changes at a tick boundary.
4. Stage `shared`/`client` changes until the next world load. Live resend is an experiment, off by default, until
   a migration is specified (id remaps, capacities, removed types, bullets in flight, derived caches) and the stock
   client passes the gate.

**Per-world overlays → patchgen.**
- A world may run content that differs from vanilla. The difference is sent as vanilla content patches in the
  world stream, derived fields included.
- CI applies them with vanilla's own patcher and expects zero differences.
- Never send effect ids that a patch created (note 20 §6).
- **A map's own patches** (W5.4) go the other way: `content.patch.DataPatcher` applies them to vanilla's baked content
  as vanilla's patcher does, into a per-world `Epoch.patched` with the base's ids and names, and the world stream
  sends the map's assets back verbatim. A world never combines them with a Shard overlay.

**Kinds.**
- A kind is a Java class registered by a kebab-case name (`conveyor`, `generic-crafter`, `item-turret`…). An
  unsupported kind is a clear load error.
- **Passthrough:** a block whose behaviour isn't ported yet keeps its wire id and carries its save bytes opaquely,
  but only from a known length-delimited input, with its original version and a compatible content epoch. The
  source is a v160.7 map building chunk (note 26).
- Passthrough promises no simulation, config edits, cross-version export or nested payload decoding. Unsupported
  gameplay is fenced explicitly.
