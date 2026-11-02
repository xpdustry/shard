# Waves, statuses and weather

Read before touching `sim.waves`, `WaveSpawner`, `WaveState`, drop zones, `Statuses`/`StatusEntry` or weather. Rows:
W2.11. Their owned paths and WPs are in [`history.md`](history.md). Index, ownership rules and live contracts:
[`WAVES.md`](../../WAVES.md). Paths are relative to the repository root.

- Waves, statuses and weather (W2.11 done): statuses and the six reactions bit for bit for 400 frames, weather for
  4,000, waves 1–100 on five maps, the countdown, drop zones, and fortress's first wave. Routed: `Logic.play` hooks,
  `WaveCores`, `gameOver` and map-load state to W2.12; shockwave `Damage` and weapon multipliers to W2.8; the wave
  and weather Calls and `stateSnapshot` to W2.13; the world-processor API to W2.9. W2.9's `MapLoader` sets
  `WaveState` from the save's meta (vanilla's `readMeta`), so W2.12's map-load part is the spawner reset and weather. Open: `MapRules` could use
  `Waves.defaults(content)` (oracle-checked; vanilla's effect is null, not "none") instead of `default-waves.json`;
  wind on ground units is gated only through W2.10's fixture. Deviations: shockwave deaths in spatial-grid order
  (the set matches); spawn overlays reach the spawner through the tile change list; status entries and reused
  `WeatherState`s start fresh, not with vanilla's pooled leftovers; no campaign multipliers.
- For ground AI (WP54): `WaveSpawner.of(world).spawns(world)` (tiles as `y * width + x`), `hasSpawns`,
  `firstSpawn`, `isSpawning`; `WaveState.of(world).wave`; `unit.statuses.apply(unit, id, duration)`, `hasEffect`,
  `getDuration`, `speedMultiplier`, `disarmed`.
