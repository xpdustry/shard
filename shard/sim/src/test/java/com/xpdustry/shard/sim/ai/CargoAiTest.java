package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cargo blocks and {@code CargoAI} on their own: a powered, nitrogen-fed loader builds its manifold, which carries
 * beryllium to an unload point; a fork and a restored checkpoint taken mid-carry go on tick for tick, the manifold's
 * tether and target included. A point with nowhere to dump goes stale. Agreement with vanilla is
 * {@code CargoOracleTest}'s.
 */
class CargoAiTest{
    World world;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(48, 48, 5);
    }

    Building place(String block, int x, int y){
        return world.buildings().setBlock(x, y, world.content().block(block), Team.SHARDED, 0, Cause.SERVER);
    }

    void until(int max, BooleanSupplier done, String what){
        for(int i = 0; i < max; i++){
            if(done.getAsBoolean()) return;
            world.tick();
        }
        fail(what + " never happened in " + max + " ticks");
    }

    @Test
    void aManifoldCarriesAndGoesOnInForksAndCheckpoints(){
        var loader = (UnitCargoLoader)place("unit-cargo-loader", 10, 24);
        place("power-source", 8, 24);
        place("liquid-source", 10, 22).configureAny(ContentRef.liquid(world.content().liquid("nitrogen").id()));
        int beryllium = world.content().item("beryllium").id();
        loader.items.add(beryllium, 150);
        var point = (UnitCargoUnloadPoint)place("unit-cargo-unload-point", 30, 30);
        point.configureAny(ContentRef.item(beryllium));

        until(700, () -> loader.unit != null, "the loader made its manifold");
        Unit manifold = loader.unit;
        assertInstanceOf(CargoAi.class, manifold.controller, "a manifold runs CargoAI");
        assertSame(loader, manifold.tether, "tethered to its loader");

        until(600, manifold::hasItem, "the manifold took a stack");
        assertSame(point, ((CargoAi)manifold.controller).unloadTarget);

        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        List<World> copies = List.of(fork, restored);
        for(int i = 0; i < 400; i++){
            world.tick();
            for(World copy : copies){
                copy.tick();
                int at = i;
                assertEquals(world.hash(), copy.hash(), () -> "after " + at + ": " + world.hash().firstDifference(copy.hash()).orElse("?"));
            }
        }
        assertTrue(point.items.get(beryllium) > 0, "the point got beryllium");

        // the point dumps nowhere: once full for staleTimeDuration it is stale
        point.items.add(beryllium, 100 - point.items.total());
        until(400, () -> point.stale, "the full point went stale");
    }
}
