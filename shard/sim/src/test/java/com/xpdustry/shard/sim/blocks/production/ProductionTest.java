package com.xpdustry.shard.sim.blocks.production;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drills, beam drills, wall crafters and pumps: ore counting and the dominant item, mining into the drill, beams and
 * walls, pumped floors, and forks and checkpoints that carry on alike. Exact agreement with vanilla is
 * {@code ProductionOracleTest}'s.
 */
class ProductionTest{
    static final ContentEpoch VANILLA = VanillaWorlds.VANILLA;

    World world;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(40, 40, 3);
    }

    Building place(String block, int x, int y, int rotation){
        return world.buildings().setBlock(x, y, VANILLA.block(block), Team.SHARDED, rotation, Cause.SERVER);
    }

    void ore(int x, int y, String ore){
        world.buildings().setOverlay(x, y, VANILLA.block(ore), Cause.SERVER);
    }

    int item(String name){
        return VANILLA.item(name).id();
    }

    @Test
    void aDrillMinesTheMostCommonOreItMayAndPrefersAnythingToSand(){
        ore(10, 10, "ore-copper");
        ore(11, 10, "ore-lead");
        ore(10, 11, "ore-titanium");
        ore(11, 11, "ore-titanium");
        var drill = (Drill)place("mechanical-drill", 10, 10, 0);
        assertEquals(item("lead"), drill.dominantItem, "titanium is too hard; copper and lead tie, and lead's id is higher");
        assertEquals(1, drill.dominantItems);

        world.buildings().setFloor(20, 20, VANILLA.block("sand-floor"), Cause.SERVER);
        world.buildings().setFloor(21, 20, VANILLA.block("sand-floor"), Cause.SERVER);
        world.buildings().setFloor(20, 21, VANILLA.block("sand-floor"), Cause.SERVER);
        ore(21, 21, "ore-scrap");
        var other = (Drill)place("pneumatic-drill", 20, 20, 0);
        assertEquals(item("scrap"), other.dominantItem, "sand is low priority");

        var bare = (Drill)place("mechanical-drill", 30, 30, 0);
        assertEquals(-1, bare.dominantItem);
        assertFalse(bare.shouldConsume());
    }

    @Test
    void aDrillFillsUpWithNowhereToPutItsOre(){
        for(int x = 9; x <= 11; x++) for(int y = 9; y <= 11; y++) ore(x, y, "ore-copper");
        var drill = (Drill)place("pneumatic-drill", 10, 10, 0);
        for(int i = 0; i < 2000; i++) world.tick();
        assertEquals(10, drill.items.get(item("copper")));
        assertEquals(0f, drill.lastDrillSpeed);
        assertTrue(drill.warmup < 0.5f, "it cools down once full: " + drill.warmup);
    }

    @Test
    void aBeamDrillMinesTheFirstSolidTileOfEachBeam(){
        world.buildings().setBlock(15, 10, VANILLA.block("graphitic-wall"), 0, 0, Cause.SERVER);
        world.buildings().setBlock(13, 11, VANILLA.block("beryllic-stone-wall"), 0, 0, Cause.SERVER);
        ore(13, 11, "ore-wall-beryllium");
        var bore = (BeamDrill)place("plasma-bore", 10, 10, 0);
        var tiles = world.tiles();
        assertEquals(List.of(tiles.pos(15, 10), tiles.pos(13, 11)), List.of(bore.facing[0], bore.facing[1]));
        assertEquals(2, bore.facingAmount);
        assertEquals(-1, bore.lastItem, "two items: none");
        world.buildings().setBlock(13, 11, VANILLA.block("air"), 0, 0, Cause.SERVER);
        bore.updateFacing();
        assertEquals(-1, bore.facing[1]);
        assertEquals(item("graphite"), bore.lastItem);
    }

    @Test
    void aWallCrafterSumsItsFrontWallsAttribute(){
        world.buildings().setBlock(12, 10, VANILLA.block("sand-wall"), 0, 0, Cause.SERVER);
        world.buildings().setBlock(12, 11, VANILLA.block("dune-wall"), 0, 0, Cause.SERVER);
        var crusher = (WallCrafter)place("cliff-crusher", 10, 10, 0);
        assertEquals(4f, crusher.efficiencyAt(10, 10, 0));
        assertEquals(0f, crusher.efficiencyAt(10, 10, 2));
    }

    @Test
    void aPumpAddsUpItsLiquidFloors(){
        world.buildings().setFloor(10, 10, VANILLA.block("deep-water"), Cause.SERVER);
        world.buildings().setFloor(11, 10, VANILLA.block("shallow-water"), Cause.SERVER);
        world.buildings().setFloor(10, 11, VANILLA.block("deep-water"), Cause.SERVER);
        var pump = (Pump)place("rotary-pump", 10, 10, 0);
        assertEquals(VANILLA.liquid("water").id(), pump.liquidDrop);
        assertEquals(1.5f + 1f + 1.5f, pump.amount);

        var extractor = (SolidPump)place("water-extractor", 20, 20, 0);
        assertEquals(1f, extractor.validTiles, 1e-6f);
    }

    @Test
    void forksAndCheckpointsCarryOnAlike(){
        for(int x = 9; x <= 11; x++) for(int y = 9; y <= 11; y++) ore(x, y, "ore-titanium");
        place("pneumatic-drill", 10, 10, 0);
        world.buildings().setFloor(20, 20, VANILLA.block("shallow-water"), Cause.SERVER);
        place("mechanical-pump", 20, 20, 0);
        place("mechanical-drill", 21, 20, 0);
        world.buildings().setBlock(32, 10, VANILLA.block("sand-wall"), 0, 0, Cause.SERVER);
        place("plasma-bore", 30, 10, 0);
        for(int i = 0; i < 300; i++) world.tick();

        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        for(World copy : List.of(fork, restored)){
            assertEquals(world.hash(), copy.hash());
        }
        for(int i = 0; i < 300; i++){
            world.tick();
            fork.tick();
            restored.tick();
        }
        assertEquals(world.hash(), fork.hash());
        assertEquals(world.hash(), restored.hash());
        assertTrue(((Drill)world.buildings().at(10, 10)).items.get(item("titanium")) > 0, "the drill drilled");
    }
}
