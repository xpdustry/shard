# Commands, plugins and the journal

Detail for `ARCHITECTURE.md` §4, binding like the rest of it; `§N` in this file means a section of
`../../ARCHITECTURE.md`. Open it when you add an input path, an admin action, a plugin or observer hook, logic
throttling or a journal entry. The fixed tick, pure observation, the checkpoint contract and the crash bundle stay in
§4.

**Intents → commands:**
1. The transport bounds and decodes each packet into an immutable **intent envelope**: authenticated session,
   route generation, sequence number and recorded arrival time.
2. The runtime routes it to a **bounded** per-session and per-world mailbox. Only superseded input snapshots may
   be coalesced; reliable structural commands never are.
3. In `COMMANDS`, the world checks membership, sequence, permissions, syntax and plan limits. It logs the admitted
   intent and its verdict, and replaces the builder's snapshot plan queue, as vanilla does.
4. A plan that is out of range or short of resources stays queued.
5. At the builder's turn the world **revalidates** content, footprint, visibility, permissions, unit overlap and
   treasury. Then it commits atomically and emits ordered domain events.

**Plugins and observers (first run):**
- Built-in admission policies run on the world thread with an explicit non-blocking budget.
- External observers receive immutable post-commit events through bounded queues.
- Their mutations are commands for a future tick, and go through the validator and the log.

**Logic throttling:**
- Default: full vanilla efficiency.
- If configured, a deterministic weighted-instruction budget sets a logged per-team efficiency factor at a defined
  boundary.
- Wall-clock profiling never changes simulation state.

**Journal:**
- Every tile and building mutation records its `Cause` (player, team, admin, AI, plugin, system) and a before-image.
- This enables rollback and block logs (note 32).
