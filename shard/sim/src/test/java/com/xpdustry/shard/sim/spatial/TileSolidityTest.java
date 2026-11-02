package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Which answer {@link TileSolidity#of(World)} gives: a world's own solidity part first, else its grid (W4.6). */
class TileSolidityTest{
    @Test
    void aWorldWithoutAPartAnswersFromItsOwnGrid(){
        World world = VanillaWorlds.world(8, 8, 1);
        assertSame(world.tiles(), TileSolidity.of(world));
        World fork = world.fork();
        assertSame(fork.tiles(), TileSolidity.of(fork));
        assertNotSame(TileSolidity.of(world), TileSolidity.of(fork));
    }

    @Test
    void aSolidityPartReplacesTheGrid(){
        var parts = new ArrayList<>(Systems.builtIn().parts());
        var handlers = new ArrayList<PhaseHandler>();
        for(Phase phase : Phase.values()) handlers.addAll(Systems.builtIn().handlers(phase));
        World world = VanillaWorlds.world(8, 8, 1, Systems.of(handlers, parts).with(Column.class, Column::new));
        TileSolidity solidity = TileSolidity.of(world);
        assertInstanceOf(Column.class, solidity);
        assertTrue(solidity.solid(Solidity.SOLID, 3, 5));
        assertFalse(solidity.solid(Solidity.SOLID, 4, 5));
        assertInstanceOf(Column.class, TileSolidity.of(world.fork()));
    }

    /** Only column 3 is solid. */
    public static final class Column implements WorldPart, TileSolidity{
        @Override
        public boolean solid(Solidity kind, int x, int y){
            return x == 3;
        }

        @Override
        public WorldPart copy(CopyContext context){
            return new Column();
        }

        @Override
        public void hash(Hasher hasher){
        }
    }
}
