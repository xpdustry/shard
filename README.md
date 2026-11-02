# Shard

So, this is my sloppy attempt at rewriting the Mindustry runtime, optimized for servers.

Besides the main improvement, being able to run multiple worlds at once, I will also play around with different
ideas, implementations and architectures.

So far, it works pretty well: it's running right now on the Chaotic Neutral testing server. But for developers, it's
unusable; I am too scared to look at the code.

> To pull this off, I used a swarm of Opus 5.5 agents and boat.dev VMs.
> Give it 2 days and you get this pile of slop. :)
>
> Make sure to keep an eye on the agents and stop anything that could slow down the iteration loop,
> because boy, will they have silly ideas...

---

**slop line**: everything below was written by an AI (Opus 5.5).

---

## What it is

A Mindustry v160.7 server written from scratch in Java, no vanilla code at runtime. Stock v160.7 clients join it as
they would a vanilla server, with no mod. One process hosts several worlds, each ticking on its own, and players
move between them with `/world`.

## Requirements

The code lives in [`shard/`](shard/); the commands and paths below are relative to it.

- **JDK 27**, Eclipse Temurin (`mise install` picks it up from `mise.toml`). Gradle runs on it too.
- Nothing else. The build downloads vanilla's `server-release.jar` from Mindustry's GitHub release for the tests and
  checks its SHA-256 (`gradle/verification-metadata.xml`); vanilla is a test oracle, never a runtime dependency.

## Build and test

```sh
./gradlew :server:installDist   # the server, in server/build/install/shard/
./gradlew :sim:test             # one module's tests
./gradlew check                 # everything: about 7 minutes on 8 vCPUs and 16 GB
```

`check` boots vanilla hundreds of times, one JVM per oracle test class, so it wants the cores and the RAM.
`scripts/vm-gradle.sh` runs any of these on a boat VM instead. Test kinds,
tags, the nightly run and the rest are in [`docs/development.md`](shard/docs/development.md).

## Run

The 18 default maps come out of the vanilla jar, so fetch it once (`scripts/fetch-vanilla.sh`), then write a config
directory with a `shard.yml`:

```yaml
schema-version: 1
net:
  bind: 127.0.0.1        # the host's address, to let players in
  port: 6567
maps:
  vanilla-jar: /home/you/.cache/shard/vanilla/v160.7/server-release.jar
host:
  worlds: ["fortress", "veins pvp"]   # "[<id>=]<map> [<mode>]", one world each
```

```sh
server/build/install/shard/bin/shard --config <dir>
```

That starts two worlds in about two seconds and reads console commands from stdin (`help`, `status`, `host`,
`exit`). Every key, with its default and whether it applies live, is in the
[config reference](shard/server/src/main/resources/com/xpdustry/shard/server/config/reference.md);
[`shard.example.yml`](shard/server/src/main/resources/com/xpdustry/shard/server/config/shard.example.yml) is a full config.
For a real deploy (heap, GC, the AOT cache), see [Deploying](shard/docs/development.md#deploying).

## A few facts

- **Size.** About 118,000 lines of server code and 70,000 lines of tests (1,676 test methods). For comparison,
  Mindustry v160.7 is 145,000 lines and Arc, its engine, 87,000; much of both is the client (rendering, UI, input,
  platforms), which a server doesn't need. The simulation alone, `sim`, is 57,000 lines.
- **Vanilla is the spec, and the test suite runs it.** 34,000 lines of oracle tests boot the real vanilla server
  inside the test JVM, play the same scenario on both, and compare the state tick by tick: conveyors, power, units,
  pathfinding, turrets, processors, payloads, fog, saves. About 250 vanilla bugs and quirks turned up along the way;
  the ones Shard keeps on purpose are marked in the code.
- **Deterministic.** A world's next tick depends only on its state and its logged inputs. No `HashMap` iteration,
  no threads in the simulation, `StrictMath` for every transcendental (HotSpot's `Math.sin` differs by CPU), and a
  build check that rejects the rest. A golden hash of a scripted world guards it, worlds fork and continue bit for
  bit, and a crashed world's bundle replays on the same build with `shard replay`.
- **The protocol is lifted, not hand-written.** `tools/wirelift` reads vanilla's packet classes from the jar's
  bytecode into `wire/schema/v160.7.yaml` and generates the codecs; `check` lifts the jar again and fails if the
  committed schema differs. Vanilla's content (blocks, units, items) is extracted the same way into a YAML pack.
- **Small and quick.** Two idle worlds use about 110 MB of heap, and an idle tick takes 0.1 to 0.2 ms (median). One
  optimisation pass cut allocation from 13.1 to 4.4 KiB per world tick, and an AOT cache (JEP 483) brings 18 worlds
  up half a second sooner.
- **Replays.** Matches can be recorded as a sim-free view stream ([`docs/replay/FORMAT.md`](shard/docs/replay/FORMAT.md))
  that a viewer plays without any game code.
- **Written by AI agents.** Teams of coding agents built it in waves against [`ARCHITECTURE.md`](shard/ARCHITECTURE.md),
  each wave gated by the oracle tests and by scripted runs with real vanilla clients (`rig/`).
  [`AGENTS.md`](shard/AGENTS.md) and [`WAVES.md`](shard/WAVES.md) are how that worked.

## License

[GNU GPL v3](LICENSE.md), like Mindustry.
