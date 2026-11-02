package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W5.5's fuzzer, finding F2: a turret's block unit made on first use, with no player to take it, had a controller with
 * no kind, and the world's next hash threw on it, which fails a hosted world. Any client got there with one packet the
 * server refuses: a kind-1 {@code Unit} reference at the turret, which {@code Acting.unit} reads through
 * {@link BlockUnits#at}.
 */
class BlockUnitKindTest{
    @Test
    void aBlockUnitReadByReferenceHashes(){
        World world = VanillaWorlds.world(32, 32, 3);
        var duo = (TurretBuild)world.buildings().setBlock(10, 10, VanillaWorlds.VANILLA.block("duo"), 1, 0, Cause.SERVER);
        world.tick();
        // what a refused requestUnitPayload or unitControl at the duo does: read the block unit by its tile
        Unit unit = BlockUnits.at(world, Point2.pack(duo.tileX(), duo.tileY()));
        assertSame(duo.blockUnit, unit, "the duo's block unit, made on first use");
        assertInstanceOf(AiController.class, unit.controller);
        assertEquals("command-ai", ((AiController)unit.controller).kind, "UnitTypes.block's CommandAI");
        assertDoesNotThrow(world::hash);
        world.tick();
        assertDoesNotThrow(world::hash);
    }
}
