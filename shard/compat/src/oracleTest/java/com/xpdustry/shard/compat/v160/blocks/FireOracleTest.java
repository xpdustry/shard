package com.xpdustry.shard.compat.v160.blocks;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.fire.Fires;
import com.xpdustry.shard.sim.weather.WeatherState;
import com.xpdustry.shard.sim.weather.Weathers;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP61): fires against vanilla, frame by frame for {@value #FRAMES} frames. A container of pyratite, one of coal and
 * a combustion generator are set alight among copper walls: the fires burn longer on what is flammable, spread to
 * neighbours, throw fireballs whose trails start more fires, and damage the buildings under them; from frame
 * {@value #RAIN} it rains, and the rain's water burns them out faster. Every frame compares, besides {@link PowerRun}'s
 * state (random, ids, timers, buildings, items), every fire in {@code Groups.all} order with its timers, the
 * {@code Groups.sync} order, the tile map, building health and live bullets; every {@value #BYTES} frames, each fire's
 * {@code writeSync} and save body byte for byte.
 */
class FireOracleTest{
    static final int FRAMES = 600, RAIN = 300, BYTES = 25;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void pyratiteAndAGeneratorBurnAndRainPutsItOut(){
        var run = new PowerRun(48, 48);
        HazardPools.empty();
        run.timers = true;
        run.place("container", 20, 20);
        run.items(20, 20, "pyratite", 300);
        run.place("container", 26, 20);
        run.items(26, 20, "coal", 150);
        run.place("combustion-generator", 23, 24);
        run.items(23, 24, "spore-pod", 2);
        for(int x = 16; x <= 30; x += 2) run.place("copper-wall", x, 27);
        for(int y = 16; y <= 26; y += 3) run.place("copper-wall", 17, y);
        run.at(1, () -> {
            ignite(run, 20, 20);
            ignite(run, 26, 21);
            ignite(run, 23, 24);
        });
        run.at(RAIN, () -> {
            mindustry.content.Weathers.rain.create(1f, 1000f);
            WeatherState.create(run.shard, HazardStates.EPOCH.weather("rain").id(), 1f, 1000f);
        });

        var burnt = new TreeSet<Integer>();
        var fireballs = new TreeSet<Integer>();
        int[] peak = {0}, atRain = {0}, bytes = {0};
        run.until(FRAMES, () -> {
            String at = "frame " + run.frame;
            HazardStates.same(at, run.shard);
            if(run.frame % BYTES == 0) bytes[0] += HazardStates.sameBytes(at, run.shard);
            int fires = 0;
            for(var entity : Groups.all){
                if(entity instanceof Fire f){
                    fires++;
                    burnt.add(f.tile.pos());
                }
            }
            for(var b : Groups.bullet) if(b.type == mindustry.content.Bullets.fireball) fireballs.add(b.id);
            peak[0] = Math.max(peak[0], fires);
            if(run.frame == RAIN - 1) atRain[0] = fires;
            return false;
        });
        int last = 0;
        for(var entity : Groups.all) if(entity instanceof Fire) last++;
        float water = Weathers.of(run.shard).envAttrs().get(com.xpdustry.shard.sim.rules.Attributes.id("water"));
        System.out.println("fires: " + burnt.size() + " tiles burnt, peak " + peak[0] + ", " + atRain[0] + " when the rain came, "
            + last + " at the end; " + fireballs.size() + " fireballs; " + bytes[0] + " bodies compared; water " + water);
        assertTrue(burnt.size() > 6, "the fires spread: " + burnt);
        assertTrue(fireballs.size() > 0, "the fires threw fireballs");
        assertTrue(water > 0f, "it rained");
        assertTrue(last < atRain[0], "the rain put fires out: " + atRain[0] + " then " + last);
        assertTrue(bytes[0] > 20, "fires were compared byte for byte: " + bytes[0]);
        assertTrue(HazardStates.vanillaHealth().stream().anyMatch(line -> line.startsWith("container") && !line.endsWith(" "
            + content.block("container").health + ".0")), "the fires damaged a container");
    }

    static void ignite(PowerRun run, int x, int y){
        ignite(run.shard, x, y);
    }

    /** {@code Fires.create} on both sides. */
    static void ignite(com.xpdustry.shard.sim.world.World shard, int x, int y){
        mindustry.entities.Fires.create(world.tile(x, y));
        Fires.create(shard, shard.tiles().pos(x, y));
    }
}
