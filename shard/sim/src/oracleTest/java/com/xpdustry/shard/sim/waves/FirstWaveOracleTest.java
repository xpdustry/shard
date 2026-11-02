package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M0 fixture (WP47): fortress, a bundled map hosted for survival, counts down to wave 1 frame by frame (14,400
 * frames), and wave 1's daggers spawn where vanilla's do. Each frame the countdown and wave number equal vanilla's.
 * Buildings and AI aren't ported yet, so before each tick the world takes vanilla's random state; the spawn positions
 * then depend only on the spawner.
 */
class FirstWaveOracleTest{
    @Test
    void fortressFirstWaveSpawnsVanillasDaggers(){
        Vanilla.host("fortress");
        World world = VanillaWaves.world();
        WaveState waves = WaveState.of(world);
        Observer observer = world.observe(4096, List.of(WaveEvent.class), null);
        assertTrue(state.rules.waves && state.rules.waveTimer, "fortress counts down to its waves");
        assertTrue(state.wavetime > 60f, "the first wave is a countdown away: " + state.wavetime);

        var calls = new ArrayList<String>();
        var events = new ArrayList<String>();
        int frames = 0;
        Vanilla.net(Vanilla.NetMode.server, () -> Vanilla.captured().clear());
        while(state.wave == 1){
            assertTrue(frames++ < 40_000, "wave 1 within 40,000 frames");
            VanillaWaves.sync(world);
            Vanilla.net(Vanilla.NetMode.server, Vanilla::frame);
            world.tick();
            assertEquals(state.wavetime, waves.wavetime, "frame " + frames + ": wavetime");
            assertEquals(state.wave, waves.wave, "frame " + frames + ": wave");
        }
        for(var sent : Vanilla.captured()){
            if(sent.packet() instanceof SpawnEffectCallPacket p) calls.add(p.u.name + " " + p.x + "," + p.y + " " + p.rotation);
        }
        for(WorldEvent event : observer.drain()){
            if(event instanceof WaveEvent.UnitSpawned e) events.add(world.content().unit(e.type()).name() + " " + e.x() + "," + e.y() + " " + e.rotation());
        }
        assertFalse(calls.isEmpty(), "wave 1 spawned units");
        assertTrue(calls.stream().allMatch(call -> call.startsWith("dagger ")), "wave 1 is daggers: " + calls);
        assertEquals(calls, events, "wave 1's daggers");
        assertEquals(2, waves.wave);
        System.out.println("fortress wave 1 after " + frames + " frames: " + calls);
    }

    /**
     * M0 (WP54): fortress's first wave walks the flowfield to the core and attacks it. The countdown runs as above,
     * vanilla's random taken each frame; from the frame the daggers spawn nothing is taken, and every frame until the
     * core's first hit the units (with their AI's targets and mounts), every building, the live bullets, the path
     * tiles and field, the random state and the next entity id equal vanilla's ({@code AiOracle}). Vanilla's
     * pathfinder runs to completion after each frame ({@code VanillaPathfinder}).
     */
    @Test
    void fortressFirstWaveWalksToTheCoreAndAttacksIt(){
        Vanilla.host("fortress");
        arc.util.Time.setInternalTime(0);
        World world = VanillaWaves.world();
        var o = com.xpdustry.shard.sim.ai.AiOracle.hosted(world);
        int frames = 0;
        while(state.wave == 1){
            assertTrue(frames++ < 40_000, "wave 1 within 40,000 frames");
            VanillaWaves.sync(world);
            o.frame();
        }
        o.same("wave 1 spawned after " + frames + " frames");
        var core = state.rules.defaultTeam.core();
        assertNotNull(core, "fortress has a core");
        float health = core.health;
        int walked = 0;
        float start = Float.NaN, closest = Float.MAX_VALUE;
        for(mindustry.gen.Unit unit : Groups.unit) if(Float.isNaN(start)) start = unit.dst(core);
        while(core.health == health){
            assertTrue(walked++ < 20_000, "the core is hit within 20,000 frames of the spawn");
            o.frame();
            o.same("frame " + walked + " after the spawn");
            for(mindustry.gen.Unit unit : Groups.unit) closest = Math.min(closest, unit.dst(core));
        }
        System.out.println("fortress wave 1 hit the core " + walked + " frames after spawning, " + start + " world units away, the closest came to "
            + closest + "; core " + health + " -> " + core.health);
        assertTrue(start - closest > 80f, "the daggers walked toward the core");
        assertTrue(Groups.unit.contains(u -> u.type.name.equals("dagger")), "the daggers made it");
    }
}
