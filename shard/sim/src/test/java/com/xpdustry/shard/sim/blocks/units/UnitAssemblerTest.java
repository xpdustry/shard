package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The unit assembler in a Shard world alone: a powered, cyanogen-fed tank assembler fed stell and large tungsten walls
 * by payload sources and conveyors makes its drones, which take the area's corners, and builds a vanquish; a fork and
 * a restored checkpoint taken mid-build go on tick for tick, drones and received payloads included. A module placed
 * against the area raises its tier. Placement: assembler areas may not overlap, and a module goes only where it links.
 * {@code AssemblerOracleTest} holds the same to vanilla frame by frame.
 */
class UnitAssemblerTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;

    World world;
    Rules rules;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(64, 48, 3);
        rules = world.part(WorldRules.class).rules;
        rules.disableUnitCap = true;
        rules.unitBuildSpeedMultiplier = 27.5f;
        rules.unitCostMultiplier = 0.1f;
        // Erekir walls are buildable on Erekir, so a payload source makes them
        rules.planet = 1;
    }

    Building place(String block, int x, int y, int rotation){
        return world.buildings().setBlock(x, y, BLOCKS.block(block), Team.SHARDED, rotation, Cause.SERVER);
    }

    void source(int x, int y, int rotation, ContentRef content){
        place("payload-source", x, y, rotation).configureAny(content);
    }

    UnitAssembler assembler(int x, int y){
        var assembler = (UnitAssembler)place("tank-assembler", x, y, 0);
        place("power-source", x - 2, y - 3, 0);
        place("liquid-source", x - 1, y - 3, 0).configureAny(ContentRef.liquid(BLOCKS.liquid("cyanogen").id()));
        source(x - 8, y, 0, new ContentRef(ContentType.UNIT, BLOCKS.unit("stell").id()));
        place("payload-conveyor", x - 4, y, 0);
        source(x, y + 8, 3, ContentRef.block(BLOCKS.block("tungsten-wall-large").id()));
        place("payload-conveyor", x, y + 4, 3);
        return assembler;
    }

    void until(int max, BooleanSupplier done, String what){
        for(int i = 0; i < max; i++){
            if(done.getAsBoolean()) return;
            world.tick();
        }
        fail(what + " never happened in " + max + " ticks");
    }

    int count(String type){
        int n = 0;
        for(int i = 0; i < world.units().size(); i++) if(world.units().get(i).type().name().equals(type)) n++;
        return n;
    }

    @Test
    void anAssemblerBuildsWithItsDronesAndGoesOnInForksAndCheckpoints(){
        UnitAssembler assembler = assembler(11, 20);
        until(300, () -> assembler.units.size() == 4, "four drones");
        for(Unit drone : assembler.units){
            assertInstanceOf(AssemblerAi.class, drone.controller, "a drone runs AssemblerAI");
            assertSame(assembler, drone.tether, "tethered to its assembler");
        }
        until(400, () -> assembler.progress > 0.3f, "the assembler started building");
        assertTrue(assembler.blocks.total() > 0, "it holds payloads");

        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        List<World> copies = List.of(fork, restored);
        for(int i = 0; i < 300; i++){
            world.tick();
            for(World copy : copies){
                copy.tick();
                int at = i;
                assertEquals(world.hash(), copy.hash(), () -> "after " + at + ": " + world.hash().firstDifference(copy.hash()).orElse("?"));
            }
        }
        assertEquals(1, count("vanquish"), "a vanquish was assembled");
        assertTrue(assembler.wasOccupied, "the vanquish holds the area");

        // a module against the area's east side raises the tier, which restarts the progress
        var module = (UnitAssemblerModule)place("basic-assembler-module", 11 + 18, 20, 2);
        place("power-source", 11 + 16, 20 - 3, 0);
        world.tick();
        world.tick();
        assertSame(assembler, module.link);
        assertEquals(1, assembler.currentTier);
        assertEquals("conquer", assembler.unit().name());

        // removing the assembler despawns its drones
        world.buildings().setBlock(11, 20, BLOCKS.block("air"), 0, 0, Cause.SERVER);
        world.tick();
        world.tick();
        assertEquals(0, count("assembly-drone"), "the drones despawned with their assembler");
        assertNull(module.link, "the module lost its link");
    }

    @Test
    void areasMayNotOverlapAndModulesGoWhereTheyLink(){
        BlockDef tank = BLOCKS.block("tank-assembler"), module = BLOCKS.block("basic-assembler-module");
        assertTrue(UnitAssembler.canPlaceOn(world, tank, 11, 20, Team.SHARDED, 0));
        assertFalse(UnitAssemblerModule.canPlaceOn(world, module, 29, 20, Team.SHARDED, 2), "no assembler to link to");
        place("tank-assembler", 11, 20, 0);
        // the area spans tiles 14..26 by 14..26; another facing it from the east overlaps, one 14 tiles up doesn't
        assertFalse(UnitAssembler.canPlaceOn(world, tank, 32, 20, Team.SHARDED, 2), "areas overlap");
        assertTrue(UnitAssembler.canPlaceOn(world, tank, 11, 34, Team.SHARDED, 0), "areas apart");
        assertTrue(UnitAssembler.canPlaceOn(world, tank, 11, 20, Team.SHARDED, 0), "the assembler itself may be replaced");
        assertFalse(UnitAssembler.canPlaceOn(world, tank, 11, 33, Team.SHARDED, 0), "edges touching count, grown by 0.1");
        assertTrue(UnitAssembler.canPlaceOn(world, tank, 11, 34, Team.DERELICT, 0) && UnitAssembler.canPlaceOn(world, tank, 32, 20, 2, 2),
            "another team's area doesn't count");
        // modules: against the east, north and south sides, facing the centre, anywhere along the side
        assertTrue(UnitAssemblerModule.canPlaceOn(world, module, 29, 20, Team.SHARDED, 2));
        assertTrue(UnitAssemblerModule.canPlaceOn(world, module, 20, 29, Team.SHARDED, 3));
        assertTrue(UnitAssemblerModule.canPlaceOn(world, module, 24, 11, Team.SHARDED, 1));
        assertFalse(UnitAssemblerModule.canPlaceOn(world, module, 29, 20, Team.SHARDED, 0), "facing away");
        assertFalse(UnitAssemblerModule.canPlaceOn(world, module, 30, 20, Team.SHARDED, 2), "a tile off the edge");
        assertFalse(UnitAssemblerModule.canPlaceOn(world, module, 29, 20, 2, 2), "another team's assembler");
    }
}
