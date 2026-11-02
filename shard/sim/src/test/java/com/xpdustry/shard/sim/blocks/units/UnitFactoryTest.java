package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit factories and reconstructors in a Shard world alone: a powered, stocked factory builds its plan and pushes the
 * unit out, through a payload conveyor into a reconstructor that upgrades it; the team's cap stops a factory, which
 * goes on when a unit dies; configs; and checkpoints and forks mid-production continuing bit for bit.
 * {@code UnitFactoryOracleTest} holds the same to vanilla frame by frame.
 */
class UnitFactoryTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;

    World world;
    Buildings buildings;
    Rules rules;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(48, 48, 1);
        buildings = world.buildings();
        rules = world.part(WorldRules.class).rules;
        rules.disableUnitCap = true;
        // 900 ticks of dagger in 30
        rules.unitBuildSpeedMultiplier = 30f;
    }

    Building place(String block, int x, int y, int rotation){
        return buildings.setBlock(x, y, BLOCKS.block(block), 1, rotation, Cause.SERVER);
    }

    void stock(Building building, String item, int amount){
        building.items.add(BLOCKS.item(item).id(), amount);
    }

    int count(String type){
        int n = 0;
        for(int i = 0; i < world.units().size(); i++) if(world.units().get(i).type().name().equals(type)) n++;
        return n;
    }

    List<WorldEvent> run(int ticks){
        var observer = world.observe(4096, List.<Class<? extends WorldEvent>>of(UnitBlockEvent.class), null);
        for(int i = 0; i < ticks; i++) world.tick();
        var events = observer.drain();
        observer.close();
        return events;
    }

    @Test
    void aPoweredFactoryBuildsItsFirstPlanAndPushesTheUnitOut(){
        var factory = (UnitFactory)place("ground-factory", 10, 10, 0);
        place("power-source", 8, 10, 0);
        assertEquals(0, factory.currentPlan, "a new factory starts on its first plan");
        // enough for one dagger (10 and 10), not two
        stock(factory, "silicon", 15);
        stock(factory, "lead", 15);
        var events = run(200);
        assertEquals(1, count("dagger"), "one dagger stands on the ground");
        assertEquals(5, factory.items.get(BLOCKS.item("silicon").id()), "it took one plan's items");
        assertEquals(List.of(new UnitBlockEvent.Spawned(world.tiles().pos(10, 10))), events, "unitBlockSpawn, once");
        assertNull(factory.payload);
    }

    @Test
    void aFactoryFeedsAReconstructorThroughAPayloadConveyor(){
        var factory = (UnitFactory)place("ground-factory", 10, 10, 0);
        place("power-source", 8, 10, 0);
        place("payload-conveyor", 13, 10, 0);
        var reconstructor = (Reconstructor)place("additive-reconstructor", 16, 10, 0);
        place("power-source", 16, 12, 0);
        stock(factory, "silicon", 10);
        stock(factory, "lead", 10);
        stock(reconstructor, "silicon", 40);
        stock(reconstructor, "graphite", 40);
        boolean upgrading = false;
        for(int i = 0; i < 400 && count("mace") == 0; i++){
            world.tick();
            upgrading |= reconstructor.constructing;
        }
        assertTrue(upgrading, "the reconstructor worked on the dagger");
        assertEquals(1, count("mace"), "a mace came out");
        assertEquals(0, count("dagger"));
        assertEquals(0, reconstructor.items.get(BLOCKS.item("graphite").id()));
    }

    @Test
    void theCapStopsAFactoryUntilAUnitDies(){
        rules.disableUnitCap = false;
        rules.unitCapVariable = false;
        rules.unitCap = 1;
        var factory = (UnitFactory)place("ground-factory", 10, 10, 0);
        place("power-source", 8, 10, 0);
        stock(factory, "silicon", 20);
        stock(factory, "lead", 20);
        run(400);
        assertEquals(1, count("dagger"));
        assertNotNull(factory.payload, "the second dagger waits in the factory");
        assertFalse(Units.canCreate(world, 1, BLOCKS.unit("dagger")));
        assertFalse(factory.shouldConsume(), "a full factory consumes nothing");
        assertEquals(0, factory.items.get(BLOCKS.item("silicon").id()), "both daggers were paid for");
        world.units().get(0).kill();
        run(30);
        assertEquals(1, count("dagger"), "the waiting dagger left once the first died");
        assertNull(factory.payload);
    }

    @Test
    void configsChoosePlansAndCommands(){
        var factory = (UnitFactory)place("ground-factory", 10, 10, 0);
        int move = world.content().unitCommands().indexOf("move");
        factory.configureAny(1);
        assertEquals(1, factory.currentPlan);
        assertEquals(BLOCKS.unit("crawler").id(), ((ContentRef)factory.senseObject(com.xpdustry.shard.logic.lang.LAccess.config)).id());
        factory.configureAny(new ContentRef(ContentType.UNIT, BLOCKS.unit("nova").id()));
        assertEquals(2, factory.currentPlan);
        factory.configureAny(new ContentRef.Foreign(UnitBuild.UNIT_COMMAND, move));
        assertEquals(move, factory.command);
        factory.configureAny(null);
        assertEquals(-1, factory.command, "null clears the command");
        factory.configureAny(9);
        assertEquals(-1, factory.currentPlan, "out of range is none");
        var fabricator = (UnitFactory)place("tank-fabricator", 20, 20, 0);
        fabricator.configureAny(-1);
        assertEquals(0, fabricator.currentPlan, "a fabricator isn't configurable");
        var reconstructor = (Reconstructor)place("additive-reconstructor", 30, 30, 0);
        reconstructor.configureAny(new ContentRef.Foreign(UnitBuild.UNIT_COMMAND, move));
        assertEquals(new ContentRef.Foreign(UnitBuild.UNIT_COMMAND, move), reconstructor.config());
        reconstructor.onCommand(new Vec2(80f, 96f));
        assertEquals(new Vec2(80f, 96f), reconstructor.getCommandPosition());
    }

    @Test
    void aCheckpointAndAForkContinueProductionBitForBit(){
        var factory = (UnitFactory)place("air-factory", 10, 10, 0);
        place("power-source", 8, 10, 0);
        place("payload-conveyor", 13, 10, 0);
        var reconstructor = (Reconstructor)place("additive-reconstructor", 16, 10, 0);
        place("power-source", 16, 12, 0);
        stock(factory, "silicon", 60);
        stock(reconstructor, "silicon", 80);
        stock(reconstructor, "graphite", 80);
        factory.onCommand(new Vec2(30 * 8, 30 * 8));
        for(int tick = 0; tick < 25; tick++) world.tick();

        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        World fork = world.fork();
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        assertEquals(world.hash(), fork.hash(), () -> world.hash().firstDifference(fork.hash()).orElse("?"));
        for(int tick = 0; tick < 400; tick++){
            world.tick();
            restored.tick();
            fork.tick();
            int at = tick;
            assertEquals(world.hash(), restored.hash(), () -> "tick " + at + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
            assertEquals(world.hash(), fork.hash(), () -> "tick " + at + ": " + world.hash().firstDifference(fork.hash()).orElse("?"));
        }
        assertTrue(count("horizon") > 0, "flares were made and upgraded");
    }
}
