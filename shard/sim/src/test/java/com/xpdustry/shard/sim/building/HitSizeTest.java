package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.sim.team.Team;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code hitSize()} (BuildingComp.java:2054) is {@code tile.block().size * tilesize}: a building measures the block on
 * its tile now, so one off its tile measures what replaced it. A {@code CommandAI} holding a dead building as its
 * attack target reads it there ({@code nearAttackTarget}, CommandAI.java:553; TeamAiOracleTest's RTS fixture).
 */
class HitSizeTest{
    @Test
    void aDeadBuildingMeasuresAir(){
        World world = VanillaWorlds.world(24, 24, 1);
        Building drill = world.buildings().setBlock(10, 10, world.content().block("mechanical-drill"), Team.SHARDED, 0, Cause.SERVER);
        assertEquals(16f, drill.hitSize(), "a live 2x2 building");
        drill.kill();
        assertFalse(drill.isValid());
        assertEquals(8f, drill.hitSize(), "dead, its tile is air");
    }

    @Test
    void aReplacedBuildingMeasuresItsReplacement(){
        World world = VanillaWorlds.world(24, 24, 1);
        Building drill = world.buildings().setBlock(10, 10, world.content().block("mechanical-drill"), Team.SHARDED, 0, Cause.SERVER);
        Building laser = world.buildings().setBlock(10, 10, world.content().block("laser-drill"), Team.SHARDED, 0, Cause.SERVER);
        assertNotSame(drill, laser);
        assertFalse(drill.isValid());
        assertEquals(24f, drill.hitSize(), "removed for a 3x3 block over its tile");
        assertEquals(24f, laser.hitSize());
    }

    @Test
    void aPayloadsEmptyTileIsAir(){
        World world = VanillaWorlds.world(24, 24, 1);
        Building drill = world.buildings().setBlock(10, 10, world.content().block("mechanical-drill"), Team.SHARDED, 0, Cause.SERVER);
        world.buildings().remove(drill, Cause.SERVER);
        drill.toEmptyTile();
        assertTrue(drill.isPayload());
        assertEquals(8f, drill.hitSize(), "emptyTile's block is air");
    }
}
