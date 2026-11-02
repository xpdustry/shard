package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.blocks.environment.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The registry and the environment's tile kinds agree with the vanilla epoch's kind keys (W1.1) on every block: the
 * tile kinds are exactly the block classes of the blocks without a building, every other block resolves by the class
 * that declares its building, a world of the vanilla epoch loads
 * without passthrough, and one with an unported kind only with it.
 */
class KindKeysTest{
    static final Epoch VANILLA = Epoch.vanilla();
    static final KindKey BLOCK = KindKey.of("block");

    @Test
    void theTileKindsAreTheClassesOfTheBlocksWithoutABuilding(){
        var without = new TreeSet<KindKey>();
        var with = new TreeSet<KindKey>();
        int tiles = 0;
        for(int id = 0; id < VANILLA.blocks(); id++){
            BlockDef block = VANILLA.block(id);
            // Block.hasBuilding()
            boolean building = block.update() || block.destructible();
            (building ? with : without).add(block.kind());
            if(!building) tiles++;
        }
        assertEquals(new TreeSet<>(new Environment().keys()), without);
        var both = new TreeSet<>(without);
        both.retainAll(with);
        assertEquals(Set.of(), both, "a block class with and without buildings");
        assertEquals(165, tiles);
        for(KindKey key : without) assertTrue(KindRegistry.builtIn().tileKind(key), key + " is no tile kind");
    }

    @Test
    void everyBlockResolvesByItsTileKindOrItsBuildKind(){
        KindRegistry kinds = KindRegistry.builtIn().withPassthrough();
        var differ = new TreeSet<String>();
        for(int id = 0; id < VANILLA.blocks(); id++){
            BlockDef block = VANILLA.block(id);
            if(kinds.tileKind(block.kind())){
                assertEquals(block.kind(), kinds.key(block), block.name());
                assertEquals(BLOCK, block.buildKind(), block.name() + ": an environment block has a plain building class");
                continue;
            }
            assertEquals(block.buildKind(), kinds.key(block), block.name());
            assertFalse(kinds.tileKind(block.buildKind()), block.name());
            assertNotNull(kinds.type(block), block.name());
            if(!block.kind().equals(block.buildKind())) differ.add(block.name());
        }
        // off the environment, only a block whose class declares no building class has two keys
        assertEquals(Set.of("power-void"), differ);
    }

    @Test
    void aVanillaWorldLoadsWithoutPassthrough(){
        // every vanilla block is ported, the unit assembler last (W5.1)
        KindRegistry.builtIn().requireSupported(VANILLA);
        World world = new World(WorldConfig.of(VANILLA, 8, 8, 1));
        assertInstanceOf(com.xpdustry.shard.sim.blocks.units.UnitAssembler.class, world.buildings().create(VANILLA.block("tank-assembler"), 1, 2, 2, 0, Cause.SERVER));
        world.tick();
    }

    @Test
    void aWorldWithAnUnportedKindLoadsWithPassthroughAndOnlyWithIt(){
        ContentEpoch probe = com.xpdustry.shard.sim.tile.VanillaWorlds.withKind(KindKey.of("unported-probe"), "tank-assembler");
        var error = assertThrows(IllegalStateException.class, () -> new World(WorldConfig.of(probe, 8, 8, 1)));
        assertTrue(error.getMessage().contains("tank-assembler ("), error.getMessage());
        assertFalse(error.getMessage().contains("conveyor ("), "the conveyor is ported");
        assertFalse(error.getMessage().contains("router ("), "the router is ported");
        World world = new World(WorldConfig.of(probe, 8, 8, 1).withKinds(KindRegistry.builtIn().withPassthrough()));
        Buildings buildings = world.buildings();
        assertInstanceOf(PassthroughBuilding.class, buildings.create(probe.block("tank-assembler"), 1, 2, 2, 0, Cause.SERVER));
        assertInstanceOf(com.xpdustry.shard.sim.blocks.distribution.Router.class, buildings.create(probe.block("router"), 1, 5, 2, 0, Cause.SERVER));
        assertNull(buildings.setBlock(4, 4, probe.block("stone-wall"), 0, 0, Cause.SERVER), "a static wall has no building");
        assertEquals(probe.block("stone-wall").id(), world.tiles().block(world.tiles().pos(4, 4)));
        world.tick();
    }
}
