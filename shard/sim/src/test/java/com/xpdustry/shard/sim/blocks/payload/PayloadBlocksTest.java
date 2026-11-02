package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The advanced payload blocks in a Shard world alone: a constructor's walls carried to a deconstructor, a payload
 * source's containers and daggers thrown along a chain of payload mass drivers into a loader, an unloader and a void,
 * and checkpoints and forks of it all, continuing bit for bit. {@code PayloadBlocksOracleTest} holds the same to vanilla
 * frame by frame.
 */
class PayloadBlocksTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final int Y = 10;

    World world;
    Buildings buildings;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(64, 24, 1);
        buildings = world.buildings();
        world.part(WorldRules.class).rules.disableUnitCap = true;
    }

    Building place(String block, int x, int y, int rotation){
        return buildings.setBlock(x, y, BLOCKS.block(block), 1, rotation, Cause.SERVER);
    }

    int item(String name){
        return BLOCKS.item(name).id();
    }

    /** The constructor line on row {@code Y}; the checkpoint test lays it on a row of its own beside the drivers'. */
    void constructorLine(){
        constructorLine(Y);
    }

    void constructorLine(int Y){
        world.part(WorldRules.class).rules.planet = 1;
        place("item-source", 3, Y, 0).configureAny(ContentRef.item(item("tungsten")));
        place("constructor", 5, Y, 0);
        place("power-source", 5, Y + 2, 0);
        place("payload-conveyor", 8, Y, 0);
        place("payload-conveyor", 11, Y, 0);
        place("small-deconstructor", 14, Y, 0);
        place("power-source", 14, Y + 2, 0);
        place("container", 16, Y, 0);
        buildings.at(5, Y).configureAny(ContentRef.block(BLOCKS.block("tungsten-wall-large").id()));
    }

    void driverLine(){
        place("payload-source", 2, Y, 0).configureAny(ContentRef.block(BLOCKS.block("container").id()));
        int[] drivers = {6, 26, 46};
        for(int x : drivers){
            place("payload-mass-driver", x, Y, 0);
            place("power-source", x, Y + 2, 0);
        }
        for(int i = 0; i < 2; i++) buildings.at(drivers[i], Y).configureAny(Point2.pack(drivers[i + 1], Y));
        place("payload-loader", 49, Y, 0);
        place("item-source", 49, Y + 2, 0).configureAny(ContentRef.item(item("copper")));
        place("power-source", 49, Y - 2, 0);
        place("payload-unloader", 52, Y, 0);
        place("power-source", 52, Y - 2, 0);
        place("item-void", 52, Y + 2, 0);
        place("payload-void", 56, Y, 0);
    }

    @Test
    void aConstructorsWallsAreTakenApartByADeconstructor(){
        constructorLine();
        var constructor = (Constructor)buildings.at(5, Y);
        assertEquals(BLOCKS.block("tungsten-wall-large").id(), constructor.recipe, "a filtered block it can make");
        var deconstructor = (PayloadDeconstructor)buildings.at(14, Y);
        boolean built = false, taken = false;
        for(int tick = 0; tick < 1500; tick++){
            world.tick();
            built |= constructor.payload != null;
            taken |= deconstructor.deconstructing != null;
        }
        assertTrue(built && taken, "built " + built + ", taken apart " + taken);
        assertTrue(buildings.at(16, Y).items.get(item("tungsten")) > 0, "the tungsten came back into the container");
        constructor.configureAny(ContentRef.block(BLOCKS.block("copper-wall").id()));
        assertEquals(0f, constructor.progress, "a new recipe resets the progress");
        assertEquals(BLOCKS.block("tungsten-wall-large").id(), constructor.recipe, "outside its filter, the recipe stays");
    }

    @Test
    void driversThrowContainersAndDaggersToALoader(){
        driverLine();
        var last = (PayloadMassDriver)buildings.at(46, Y);
        var loader = (PayloadLoader)buildings.at(49, Y);
        boolean thrown = false, loaded = false, unloaded = false;
        for(int tick = 0; tick < 2200; tick++){
            if(tick == 300) buildings.at(2, Y).configureAny(new ContentRef(ContentType.UNIT, BLOCKS.unit("dagger").id()));
            world.tick();
            thrown |= last.payload != null;
            loaded |= loader.payload != null && loader.payload.building().items.get(item("copper")) > 0;
            unloaded |= ((PayloadUnloader)buildings.at(52, Y)).payload != null;
        }
        assertTrue(thrown && loaded && unloaded, "thrown " + thrown + ", loaded " + loaded + ", unloaded " + unloaded);
        assertInstanceOf(Payload.UnitPayload.class, last.payload, "a dagger crossed both gaps and waits at the loader");
    }

    @Test
    void aCheckpointAndAForkContinueBitForBit(){
        constructorLine(Y + 8);
        // the walls are made by now (the constructor checks its recipe when configured); containers need serpulo's
        world.part(WorldRules.class).rules.planet = 5;
        driverLine();
        for(int tick = 0; tick < 450; tick++) world.tick();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        World fork = world.fork();
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        assertEquals(world.hash(), fork.hash(), () -> world.hash().firstDifference(fork.hash()).orElse("?"));
        for(int tick = 0; tick < 600; tick++){
            world.tick();
            restored.tick();
            fork.tick();
            int at = tick;
            assertEquals(world.hash(), restored.hash(), () -> "tick " + at + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
            assertEquals(world.hash(), fork.hash(), () -> "tick " + at + ": " + world.hash().firstDifference(fork.hash()).orElse("?"));
        }
    }

    @Test
    void aPayloadVoidDestroysWhatItTakes(){
        var voidBlock = (PayloadVoid)place("payload-void", 10, 10, 0);
        Unit dagger = Unit.create(world, BLOCKS.unit("dagger"), 1);
        dagger.set(voidBlock.x() + 12, voidBlock.y());
        assertTrue(voidBlock.acceptUnitPayload(dagger));
        voidBlock.handlePayload(voidBlock, new Payload.UnitPayload(dagger));
        int ticks = 0;
        while(voidBlock.payload != null && ticks++ < 100) world.tick();
        assertNull(voidBlock.payload, "gone at the centre");
        assertTrue(ticks > 5, "it was pulled in first");
    }
}
