# The world model: objects, tables, fork

Detail for `ARCHITECTURE.md` §3, binding like the rest of it; `§N` in this file means a section of
`../../ARCHITECTURE.md`. Open it when you write or change a `Building`, a `Unit`, an entity reference, an attachment,
`TileGrid`, `Bullets` or code that copies a world. The ownership rule, iteration order and the tick phases stay in §3.

**Objects:**
- **`Building`:** an abstract base with the common state:
  - its typed definition from the pinned `ContentEpoch`, plus team, tile, rotation, health, efficiency, timeScale
    and enabled;
  - the item, liquid and power modules, allocated only for blocks that have them.

  There is **one subclass per behaviour kind**, registered as `@Kind("conveyor")` and bound to the content kind key
  by `:sim`'s kind registry. Kinds use composition, not deep inheritance trees. Content is still data: a kind reads
  its parameters from its definition.
- **`Unit`:**
  - one class plus composed parts: a movement shape (mech, legs, tank, crawl, naval, hover, flying), weapon mounts,
    abilities, statuses, plans and payload;
  - a controller object;
  - a per-world wire id, allocated the way vanilla allocates entity ids.

  Unit "class shapes" are wire layouts chosen by `:compat`, not Java subclasses.
- **Fires, puddles, weather, labels, players:** plain objects.
- **Shared entity machinery** lives in `sim.entity`: the `Entity` base (`isAdded()`, attachments), `ActiveList`,
  `AttachmentKey`, `EntityIds`.
- **References** between entities are object references. Anything that can outlive its target checks `isAdded()`,
  as vanilla does. Plugins hold the same objects.
- **Attachments:** each `Building` and `Unit` carries a small attachment array, indexed by attachment-key id. Key
  ids follow class-initialisation order, so hashes combine attachments by key **name** and checkpoints store them by
  name, never by id.

**Tables:**
- **`TileGrid`:** flat primitive columns of `width × height` (`floor`, `overlay`, `block`, `team`, `build`, …).
  `build` indexes a dense building registry with a free list. Inside, `pos = y * width + x`; on the wire,
  `Point2.pack(x, y)`.
- **`Bullets`:** one dense struct-of-arrays table in vanilla order, with behaviour hooks from data (note 31).
  Bullets have no wire identity, because clients simulate them. This is where tables won clearly: 1.72 ms vs
  2.47 ms for 200k bullets.
  **But bullets still use up entity ids:** vanilla's `Bullet()` and `Bullet.reset()` both call
  `EntityGroup.nextId()`, and Arc's `Pool.free` always calls `reset()`. Units and buildings share that counter, so
  the bullet table must replay vanilla's pool timing, or unit ids drift from vanilla.
- **Derived indexes:** spatial grids (note 29), flowfields (note 22), solidity bitsets and chunk buckets.

**Fork and checkpoint.**
- Objects copy themselves through `copy(CopyContext)`. The context remaps cross-references inside the world.
- Tables copy their arrays.
- Note 21 measured about 6 ms to fork an object world. Watch the GC spikes it saw.
