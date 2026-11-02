package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.ai.AiOracle;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;
import arc.struct.*;
import mindustry.gen.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP54): a wave on bundled maps walks (or flies, or sails) to the core and attacks it as in vanilla. The map is
 * hosted for survival, wave {@code wave} runs on both sides at once, and from then on every frame the units (with
 * their AI's targets and mounts), every building, the live bullets, the path tiles and the wave field, the random
 * state and the next entity id equal vanilla's ({@code AiOracle}), for {@value #FRAMES} frames and on until a core
 * first takes damage. Triad: daggers and crawlers from three ground spawns. Labyrinth: the same down a winding
 * flowfield. Debris Field: flares, which go for the solar panels (generators) first.
 *
 * <p>The Shard world is the map's tiles ({@code VanillaWaves.world()}), so three things of the map file are carried
 * over by hand: the buildings' saved health, the weather schedule as vanilla's {@code PlayEvent} left it, and the
 * units the map saved, which vanilla removes before the wave (their ids stay drawn on both sides).
 */
class WaveMapsOracleTest{
    static final int FRAMES = 1800, LIMIT = 12_000;

    @ParameterizedTest
    @CsvSource({"triad, 5", "labyrinth, 5", "debrisField, 5"})
    void aWaveReachesTheCoreAndAttacksItAsInVanilla(String map, int wave){
        Vanilla.host(map);
        arc.util.Time.setInternalTime(0);
        World world = VanillaWaves.world();
        for(var tile : mindustry.Vars.world.tiles){
            if(tile.build != null && tile.build.tile == tile) world.buildings().at(tile.x, tile.y).health = tile.build.health;
        }
        var rules = world.part(WorldRules.class).rules;
        for(var entry : state.rules.weather) rules.weather.add(weather(entry));
        removeMapUnits();
        assertEquals(0, world.units().size(), map + ": the Shard world has no units before the wave");
        var o = AiOracle.hosted(world);
        state.wave = wave;
        WaveState.of(world).wave = wave;
        Vanilla.net(Vanilla.NetMode.server, logic::runWave);
        Waves.runWave(world);
        o.same(map + " wave " + wave + " spawned");

        var spawned = new TreeMap<String, Integer>();
        for(Unit unit : Groups.unit) spawned.merge(unit.type.name, 1, Integer::sum);
        assertFalse(spawned.isEmpty(), map + " wave " + wave + " spawned units");
        float health = coreHealth();
        int frame = 0, hitAt = -1;
        while(frame < FRAMES || hitAt < 0){
            assertTrue(frame < LIMIT, map + ": a core is hit within " + LIMIT + " frames of the spawn");
            o.frame();
            o.same(map + " frame " + frame);
            frame++;
            if(hitAt < 0 && coreHealth() < health) hitAt = frame;
        }
        System.out.println(map + " wave " + wave + " " + spawned + ": the core was first hit " + hitAt + " frames after the spawn; " + frame
            + " frames exact, core " + health + " -> " + coreHealth() + ", units left " + Groups.unit.size());
    }

    /**
     * The map's saved units, gone from vanilla as if the map had none. Removing them isn't enough: their physics
     * bodies are flushed into their layers at the next {@code PhysicsProcess.begin} and then dropped from an unordered
     * list, which swaps the layer's last body into the gap; with Debris Field's boosting alpha gone that way, the
     * flares stacked on the spawn resolved in another order than Shard's. So the physics process starts over too.
     */
    static void removeMapUnits(){
        for(Unit unit : Groups.unit.copy(new Seq<>())) unit.remove();
        unitPhysics.reset();
        try{
            var pending = mindustry.async.PhysicsProcess.class.getDeclaredField("pendingAdds");
            pending.setAccessible(true);
            ((Seq<?>)pending.get(unitPhysics)).clear();
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
        unitPhysics.init();
    }

    /** Vanilla's {@code entry} with its cooldown, as Shard's rules hold it. */
    static WeatherEntry weather(mindustry.type.Weather.WeatherEntry entry){
        var out = new WeatherEntry();
        out.weather = entry.weather == null ? -1 : entry.weather.id;
        out.minFrequency = entry.minFrequency;
        out.maxFrequency = entry.maxFrequency;
        out.minDuration = entry.minDuration;
        out.maxDuration = entry.maxDuration;
        out.cooldown = entry.cooldown;
        out.intensity = entry.intensity;
        out.always = entry.always;
        return out;
    }

    /** The defenders' cores' total health. */
    static float coreHealth(){
        float sum = 0f;
        for(var core : state.rules.defaultTeam.cores()) sum += core.health;
        return sum;
    }
}
