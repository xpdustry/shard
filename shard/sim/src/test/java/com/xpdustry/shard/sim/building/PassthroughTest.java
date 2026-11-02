package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PassthroughBuilding}: the registry fallback, the opaque bytes and their epoch fence. Every vanilla block is
 * ported (the unit assembler last, W5.1), so the tank assembler is given a kind no class registers.
 */
class PassthroughTest{
    static final ContentEpoch BLOCKS = VanillaWorlds.withKind(KindKey.of("unported-probe"), "tank-assembler");

    World world = VanillaWorlds.filled(BLOCKS, 12, 12, "stone");

    @Test
    void unregisteredKindsBecomePassthroughsOnlyWhenAsked(){
        KindKey factory = BLOCKS.block("tank-assembler").kind();
        assertTrue(KindRegistry.builtIn().withPassthrough().passesThrough(factory));
        assertFalse(KindRegistry.builtIn().passthrough());
        assertThrows(RuntimeException.class, () -> KindRegistry.builtIn().requireSupported(BLOCKS));
        Building placed = world.buildings().setBlock(3, 3, BLOCKS.block("tank-assembler"), 1, 0, Cause.SERVER);
        assertInstanceOf(PassthroughBuilding.class, placed);
        assertTrue(world.builds().toList().contains(placed), "a passthrough keeps its place in the update list");
    }

    @Test
    void theBytesComeBackForTheirEpochOnly(){
        var building = (PassthroughBuilding)world.buildings().setBlock(3, 3, BLOCKS.block("tank-assembler"), 1, 0, Cause.SERVER);
        assertFalse(building.hasBody());
        assertEquals(-1, building.revision());
        assertThrows(UnsupportedOperationException.class, () -> building.body(BLOCKS), "a live placement has no bytes");
        byte[] source = {4, 0, 0, 0, 1};
        building.load(source, 1, BLOCKS.hash());
        source[0] = 9;
        assertArrayEquals(new byte[]{4, 0, 0, 0, 1}, building.body(BLOCKS), "the source array is copied in");
        building.body(BLOCKS)[1] = 7;
        assertArrayEquals(new byte[]{4, 0, 0, 0, 1}, building.body(BLOCKS), "and out");
        assertEquals(5, building.bodyLength());
        assertThrows(IllegalStateException.class, () -> building.load(new byte[0], 1, BLOCKS.hash()));
        assertThrows(IllegalStateException.class, () -> building.body(new Renamed(BLOCKS)));
    }

    @Test
    void gameplayIsFenced(){
        var building = (PassthroughBuilding)world.buildings().setBlock(3, 3, BLOCKS.block("tank-assembler"), 1, 0, Cause.SERVER);
        var refused = building.unported("accept an item");
        assertTrue(refused.getMessage().contains("tank-assembler"), refused.getMessage());
        long before = world.hash().value();
        building.updateTile();
        assertEquals(before, world.hash().value(), "an unported kind doesn't simulate");
    }

    @Test
    void forksCopyAndHashTheBytes(){
        var building = (PassthroughBuilding)world.buildings().setBlock(3, 3, BLOCKS.block("tank-assembler"), 1, 0, Cause.SERVER);
        World plain = world.fork();
        building.load(new byte[]{1, 2, 3}, 2, BLOCKS.hash());
        assertNotEquals(plain.hash(), world.hash());
        World fork = world.fork();
        assertEquals(world.hash(), fork.hash());
        var copy = (PassthroughBuilding)fork.buildings().at(3, 3);
        assertNotSame(building, copy);
        assertArrayEquals(building.body(BLOCKS), copy.body(BLOCKS));
        assertEquals(2, copy.revision());
    }

    /** The same blocks under another content hash. */
    record Renamed(ContentEpoch source) implements ContentEpoch{
        @Override
        public String hash(){
            return "renamed";
        }

        @Override
        public int blocks(){
            return source.blocks();
        }

        @Override
        public BlockDef block(int id){
            return source.block(id);
        }

        @Override
        public int items(){
            return source.items();
        }

        @Override
        public int liquids(){
            return source.liquids();
        }

        @Override
        public int units(){
            return source.units();
        }

        @Override
        public UnitDef unit(int id){
            return source.unit(id);
        }
    }
}
