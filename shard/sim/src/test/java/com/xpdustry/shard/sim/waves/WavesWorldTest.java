package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wave, weather and status state through forks and checkpoints: a stone world with two spawns runs waves every 90
 * ticks (ground, air and a payload carrier, with effects) under rain and a sandstorm. A fork, and a world restored from
 * a checkpoint, continue tick for tick with the same hash, through new waves, weather events starting and expiring,
 * statuses ticking, and spawn points moving.
 */
class WavesWorldTest{
    static final ContentEpoch VANILLA = VanillaWorlds.VANILLA;

    static World world(long seed){
        World world = UnitWorlds.world(64, 64, seed);
        world.buildings().beginLoad();
        world.tiles().overlay(world.tiles().pos(32, 32), VANILLA.block("spawn").id());
        world.tiles().overlay(world.tiles().pos(8, 50), VANILLA.block("spawn").id());
        world.buildings().endLoad(false);
        WaveSpawner.of(world).reset(world);

        Rules rules = world.part(WorldRules.class).rules;
        rules.waves = true;
        rules.waveTimer = true;
        rules.waveSpacing = 90f;
        rules.dropZoneRadius = 40f;
        var dagger = new SpawnGroup(VANILLA.unit("dagger").id());
        dagger.unitScaling = 2f;
        dagger.effect = VANILLA.status("overdrive").id();
        var flare = new SpawnGroup(VANILLA.unit("flare").id());
        flare.begin = 1;
        flare.shields = 20f;
        var quad = new SpawnGroup(VANILLA.unit("quad").id());
        quad.begin = 2;
        quad.spacing = 3;
        quad.payloads = new ArrayList<>(List.of(VANILLA.unit("dagger").id()));
        quad.items = new ItemStack(VANILLA.item("copper").id(), 10);
        rules.spawns = new ArrayList<>(List.of(dagger, flare, quad));
        rules.weather.add(weather("rain", 50f, 150f, 100f, 200f, false));
        rules.weather.add(weather("sandstorm", 80f, 200f, 60f, 120f, false));
        rules.weather.add(weather("fog", 0f, 0f, 0f, 0f, true));

        WaveState.of(world).play(rules);
        Weathers.play(world);
        return world;
    }

    static WeatherEntry weather(String name, float minFrequency, float maxFrequency, float minDuration, float maxDuration, boolean always){
        var entry = new WeatherEntry();
        entry.weather = VANILLA.weather(name).id();
        entry.minFrequency = minFrequency;
        entry.maxFrequency = maxFrequency;
        entry.minDuration = minDuration;
        entry.maxDuration = maxDuration;
        entry.always = always;
        return entry;
    }

    static World restore(World world){
        return Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
    }

    /** Ticks both for {@code ticks}, moving the spawns at {@code moveAt}, and checks the hashes every tick. */
    static void together(World a, World b, int ticks, int moveAt){
        for(int i = 0; i < ticks; i++){
            if(i == moveAt){
                for(World world : List.of(a, b)){
                    world.tile(32, 32).setOverlay(world.content().block(0), Cause.SERVER);
                    world.tile(50, 12).setOverlay(VANILLA.block("spawn"), Cause.SERVER);
                }
            }
            a.tick();
            b.tick();
            assertEquals(a.hash(), b.hash(), () -> "tick: " + a.hash().firstDifference(b.hash()).orElse("?"));
        }
    }

    @Test
    void wavesWeatherAndStatusesForkAndRestore(){
        World world = world(11);
        int weatherEvents = 0;
        for(int i = 0; i < 400; i++){
            world.tick();
            weatherEvents = Math.max(weatherEvents, Weathers.of(world).list().size());
        }
        WaveState state = WaveState.of(world);
        assertEquals(4, state.wave, "waves at ticks 180, 270 and 360");
        assertTrue(world.units().size() > 5, "wave units live");
        assertTrue(weatherEvents >= 2, "weather events overlapped");
        int overdrive = VANILLA.status("overdrive").id();
        assertTrue(world.units().toList().stream().anyMatch(u -> u.statuses.hasEffect(overdrive)), "wave effects apply");
        assertTrue(world.units().toList().stream().anyMatch(u -> u.payloads != null && !u.payloads.isEmpty()), "a carrier holds its payload");

        World fork = world.fork();
        assertEquals(world.hash(), fork.hash());
        World restored = restore(world);
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));

        together(world, fork, 300, 50);
        World again = world(11);
        for(int i = 0; i < 400; i++) again.tick();
        together(again, restored, 300, 50);
        assertEquals(world.hash(), again.hash(), "the runs agree");

        IntSeqView spawns = new IntSeqView(WaveSpawner.of(world).spawns(world));
        assertEquals(List.of(world.tiles().pos(50, 12), world.tiles().pos(8, 50)).stream().sorted().toList(), spawns.sorted(),
            "the spawn moved");
    }

    @Test
    void theCountdownRunsTheWaveAtZero(){
        World world = world(5);
        WaveState state = WaveState.of(world);
        assertEquals(180f, state.wavetime, "twice the spacing before the first wave");
        var observer = world.observe(1024, List.of(WaveEvent.class), null);
        for(int i = 0; i < 179; i++) world.tick();
        assertEquals(1, state.wave);
        assertEquals(1f, state.wavetime);
        world.tick();
        assertEquals(2, state.wave);
        assertEquals(90f, state.wavetime);
        List<WorldEvent> events = observer.drain();
        assertInstanceOf(WaveEvent.Started.class, events.getLast());
        assertTrue(events.stream().filter(e -> e instanceof WaveEvent.UnitSpawned).count() >= 2, "a dagger per spawn");
        assertTrue(WaveSpawner.of(world).isSpawning());
        world.part(WorldRules.class).rules.waveTimer = false;
        for(int i = 0; i < 120; i++) world.tick();
        assertTrue(WaveSpawner.of(world).isSpawning());
        world.tick();
        assertFalse(WaveSpawner.of(world).isSpawning(), "spawning ends 121 ticks after the wave");
        assertEquals(90f, state.wavetime, "no wave timer, no countdown");

        world.submit(new Waves.SkipWave(), Cause.SERVER);
        world.tick();
        assertEquals(3, state.wave, "an admin skipped a wave");
    }

    /** The spawn list's contents, for comparison. */
    record IntSeqView(com.xpdustry.shard.common.struct.IntSeq seq){
        List<Integer> sorted(){
            var out = new ArrayList<Integer>();
            for(int i = 0; i < seq.size; i++) out.add(seq.items[i]);
            Collections.sort(out);
            return out;
        }
    }
}
