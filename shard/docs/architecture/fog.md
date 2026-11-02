# Real fog

Detail for `ARCHITECTURE.md` §8, binding like the rest of it; `§N` in this file means a section of
`../../ARCHITECTURE.md`. Open it when you implement, enable or test real fog on a fog map. The `Outbox` gate, cadence
and rate limits stay in §8.

**Real fog** is enabled on fog maps only once all of these are implemented and tested:
- server-authoritative movement budgets with 2–3-tile corrections;
- rate-limited rotation;
- the reveal rules: sight plus a speed-scaled margin, ground contact and in-range plan blockers, and shooters for
  2 s;
- hidden plans held without a state-dependent rejection;
- hidden references treated as absent;
- unprivileged logic `ulocate`/`sensor` filtered, while privileged world processors keep map-author behaviour;
- no cross-team echo, and config/rotate only on tiles in view.

With real fog on, the world stream contains the team's own buildings, terrain, derelict blocks and the last-known
enemy buildings. Reveal with `setTile` + `blockSnapshot`, remove with `removeTile`, and keep the last-known state
until the tile is seen again. Team memory and discovered tiles go into checkpoints. Test reconnect and hidden-tile
probing.
