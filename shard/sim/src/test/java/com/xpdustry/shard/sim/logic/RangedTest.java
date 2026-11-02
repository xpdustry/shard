package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.blocks.defense.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The radar's {@code Ranged.range()} of W3.6's kinds ({@link UnitStatements#rangeOf}): a force projector answers its
 * current radius, as {@code ForceBuild.range()} does; vanilla's {@code BaseTurretBuild}s (point defence, tractor beams,
 * build towers) the block's {@code range}; the kinds that aren't {@code Ranged} NaN.
 */
class RangedTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;

    World world;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(32, 32, 1);
    }

    Building place(String block, int x, int y){
        return world.buildings().setBlock(x, y, BLOCKS.block(block), 1, 0, Cause.SERVER);
    }

    @Test
    void aForceProjectorAnswersItsCurrentRadius(){
        var force = (ForceProjector)place("force-projector", 10, 10);
        place("power-source", 8, 10);
        assertEquals(0f, UnitStatements.rangeOf(force), "the shield isn't up yet");
        for(int i = 0; i < 120; i++) world.tick();
        assertTrue(force.realRadius() > 50f, "the shield grew: " + force.realRadius());
        assertEquals(force.realRadius(), UnitStatements.rangeOf(force));
    }

    @Test
    void baseTurretsAnswerTheirBlocksRange(){
        for(String block : new String[]{"segment", "parallax", "build-tower"}){
            Building building = place(block, 20, 20);
            assertEquals(BLOCKS.block(block).fields().f("range"), UnitStatements.rangeOf(building), block);
        }
    }

    @Test
    void theOthersArentRanged(){
        for(String block : new String[]{"regen-projector", "shockwave-tower", "radar", "shield-projector"}){
            assertTrue(Float.isNaN(UnitStatements.rangeOf(place(block, 20, 20))), block);
        }
    }
}
