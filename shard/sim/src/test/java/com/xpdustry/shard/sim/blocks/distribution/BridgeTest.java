package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bridges and mass drivers in a Shard world alone: links by both configs, a chain carrying items, a pair of drivers
 * shooting, and checkpoints and forks of it all, bolts in flight and their arrival timers included, continuing bit for
 * bit. {@code BridgeOracleTest} and {@code MassDriverOracleTest} hold the same kinds to vanilla frame by frame.
 */
class BridgeTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final int COPPER = BLOCKS.item("copper").id();

    World world;
    Buildings buildings;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(64, 24, 1);
        buildings = world.buildings();
    }

    Building place(String block, int x, int y){
        return buildings.setBlock(x, y, BLOCKS.block(block), 1, 0, Cause.SERVER);
    }

    void source(int x, int y){
        place("item-source", x, y).configureAny(ContentRef.item(COPPER));
    }

    @Test
    void configsLinkByPositionOrOffset(){
        var a = (ItemBridgeBuild)place("bridge-conveyor", 2, 2);
        var b = (ItemBridgeBuild)place("bridge-conveyor", 5, 2);
        a.configureAny(Point2.pack(5, 2));
        assertEquals(Point2.pack(5, 2), a.link);
        assertEquals(new Point2(3, 0), a.config());
        b.configureAny(new Point2(-3, 0));
        assertEquals(Point2.pack(2, 2), b.link);
        assertFalse(b.linkValid(b.tile(), a.tile()), "a link back to a bridge linked here is refused");
        b.configureAny(-1);
        assertEquals(new Point2(-6, -3), b.config(), "unlinked: (-1, -1) minus the tile, as vanilla's");
    }

    @Test
    void aChainCarriesItemsAndRemembersItsFeeders(){
        source(1, 2);
        int[] xs = {2, 5, 8};
        for(int x : xs) place("bridge-conveyor", x, 2);
        place("item-void", 9, 2);
        ((ItemBridgeBuild)buildings.at(2, 2)).configureAny(Point2.pack(5, 2));
        ((ItemBridgeBuild)buildings.at(5, 2)).configureAny(Point2.pack(8, 2));
        for(int i = 0; i < 300; i++) world.tick();
        var middle = (ItemBridgeBuild)buildings.at(5, 2);
        var last = (ItemBridgeBuild)buildings.at(8, 2);
        assertEquals(Point2.pack(2, 2), middle.incoming.first());
        assertEquals(Point2.pack(5, 2), last.incoming.first());
        assertTrue(middle.wasMoved, "the middle bridge moved items");
        buildings.setBlock(5, 2, BLOCKS.block("air"), 0, 0, Cause.SERVER);
        world.tick();
        assertEquals(0, last.incoming.size, "a removed feeder leaves incoming");
    }

    @Test
    void aCheckpointAndAForkCarryBridgesAndBoltsInFlight(){
        source(1, 2);
        for(int x : new int[]{2, 5}) place("phase-conveyor", x, 2);
        place("power-source", 2, 3);
        place("power-source", 5, 3);
        ((ItemBridgeBuild)buildings.at(2, 2)).configureAny(Point2.pack(5, 2));
        source(1, 10);
        place("titanium-conveyor", 2, 10);
        place("titanium-conveyor", 3, 10);
        var from = (MassDriver)place("mass-driver", 5, 10);
        var to = (MassDriver)place("mass-driver", 45, 10);
        place("power-source", 5, 12);
        place("power-source", 45, 12);
        place("item-void", 47, 10);
        from.configureAny(to.pos());
        int ticks = 0;
        while(DriverBolts.of(world).size() == 0 && ticks++ < 2000) world.tick();
        assertEquals(1, DriverBolts.of(world).size(), "a bolt is in flight");
        assertEquals(MassDriver.DriverState.accepting, to.state);
        assertFalse(world.timers().pending().isEmpty(), "the shot's arrival is pending");

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
}
