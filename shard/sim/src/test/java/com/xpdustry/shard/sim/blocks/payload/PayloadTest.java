package com.xpdustry.shard.sim.blocks.payload;

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
 * Payloads in a Shard world alone: a mega lifting a building and putting it down elsewhere with its state, a quad
 * carrying a unit, a conveyor chain carrying both, and checkpoints and forks of it all mid-carry, continuing bit for
 * bit. {@code PayloadOracleTest} holds the same to vanilla frame by frame.
 */
class PayloadTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final int COPPER = BLOCKS.item("copper").id();

    World world;
    Buildings buildings;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(48, 48, 1);
        buildings = world.buildings();
        world.part(WorldRules.class).rules.disableUnitCap = true;
    }

    Building place(String block, int x, int y, int rotation){
        return buildings.setBlock(x, y, BLOCKS.block(block), 1, rotation, Cause.SERVER);
    }

    Unit spawn(String type, float x, float y){
        Unit unit = Unit.spawn(world, BLOCKS.unit(type), 1, x, y, 0f);
        unit.controller(new NoAi());
        return unit;
    }

    @Test
    void aMegaCarriesABuildingWithItsItemsAndDropsItElsewhere(){
        Building container = place("container", 5, 5, 0);
        container.items.add(COPPER, 77);
        Unit mega = spawn("mega", 5 * 8 + 4, 5 * 8 + 4);
        assertTrue(Payloads.canPickup(mega, container));
        assertTrue(Payloads.requestBuildPayload(mega, container, Cause.SERVER));
        assertNull(buildings.at(5, 5), "the tile is empty");
        assertFalse(container.isAdded());
        assertEquals(1, mega.payloads.size());
        assertFalse(Payloads.canPickup(mega, place("battery", 9, 9, 0)), "a battery doesn't fit beside the container");
        world.tick();
        assertEquals(mega.x, container.x(), "the building follows the unit");
        mega.set(20 * 8 + 4, 20 * 8 + 4);
        Payloads.requestDropPayload(mega, mega.x, mega.y, Cause.SERVER);
        assertTrue(mega.payloads.isEmpty());
        assertSame(container, buildings.at(20, 20), "the same building stands on its new tile");
        assertEquals(77, container.items.get(COPPER));
        assertTrue(container.isValid());
        assertEquals(20 * 8 + 4, container.x());
    }

    @Test
    void aCarriedBatteryKeepsItsChargeAndJoinsTheGraphItIsDroppedOn(){
        Building battery = place("battery", 5, 5, 0);
        Building node = place("power-node", 21, 20, 0);
        battery.power.status = 0.6f;
        Unit mega = spawn("mega", 5 * 8, 5 * 8);
        Payloads.pickedBuildPayload(mega, battery, true, Cause.SERVER);
        for(int i = 0; i < 5; i++) world.tick();
        assertEquals(0.6f, battery.power.status, "a battery keeps its charge in a carrier's graph");
        assertFalse(battery.power.graph.hasUpdater(), "the carrier's graph has no updater");
        mega.set(20 * 8, 20 * 8);
        assertTrue(Payloads.dropLastPayload(mega, Cause.SERVER));
        assertSame(battery.power.graph, node.power.graph, "dropped by a node, it joins its graph");
        assertTrue(battery.power.graph.hasUpdater());
    }

    @Test
    void aQuadCarriesAUnitWhichComesBackWithANewId(){
        Unit quad = spawn("quad", 100, 100);
        Unit dagger = Unit.spawn(world, BLOCKS.unit("dagger"), 1, 104, 100, 0f);
        assertTrue(dagger.isAI(), "a dagger's own AI");
        assertTrue(Payloads.requestUnitPayload(quad, dagger));
        assertFalse(dagger.isAdded());
        int id = dagger.id();
        world.tick();
        quad.set(200, 200);
        Payloads.payloadDropped(quad, 200, 200, Cause.SERVER);
        assertTrue(dagger.isAdded());
        assertNotEquals(id, dagger.id(), "a dropped unit syncs as a new one");
        assertTrue(dagger.within(200, 200, 2.01f));
    }

    @Test
    void aConveyorChainCarriesPayloadsAndARouterSortsThem(){
        for(int x = 4; x <= 13; x += 3) place("payload-conveyor", x, 20, 0);
        var router = (PayloadRouter)place("payload-router", 16, 20, 0);
        router.configureAny(ContentRef.block(BLOCKS.block("container").id()));
        place("payload-conveyor", 19, 20, 0);
        place("payload-conveyor", 16, 23, 1);
        var first = (PayloadConveyorBuild)buildings.at(4, 20);
        Building container = buildings.createPayload(BLOCKS.block("container"), 1);
        container.items.add(COPPER, 5);
        first.handlePayload(first, new Payload.BuildPayload(container));
        boolean straight = false;
        for(int tick = 0; tick < 600 && !straight; tick++){
            world.tick();
            straight = ((PayloadConveyorBuild)buildings.at(19, 20)).item instanceof Payload.BuildPayload b && b.building() == container;
        }
        assertTrue(straight, "the container goes straight through the router");
        assertTrue(container.isPayload());
        Unit dagger = Unit.create(world, BLOCKS.unit("dagger"), 1);
        first.handlePayload(first, new Payload.UnitPayload(dagger));
        boolean aside = false;
        for(int tick = 0; tick < 600 && !aside; tick++){
            world.tick();
            aside = ((PayloadConveyorBuild)buildings.at(16, 23)).item instanceof Payload.UnitPayload u && u.unit() == dagger || dagger.isAdded();
        }
        assertTrue(aside, "the dagger turns aside");
    }

    @Test
    void aCheckpointAndAForkCarryPayloadsMidway(){
        for(int x = 4; x <= 13; x += 3) place("payload-conveyor", x, 20, 0);
        var first = (PayloadConveyorBuild)buildings.at(4, 20);
        first.handlePayload(first, new Payload.UnitPayload(Unit.create(world, BLOCKS.unit("dagger"), 1)));
        Building container = place("container", 30, 30, 0);
        container.items.add(COPPER, 12);
        place("battery", 33, 30, 0).power.status = 0.5f;
        Unit mega = spawn("mega", 30 * 8 + 4, 30 * 8 + 4);
        Payloads.pickedBuildPayload(mega, container, true, Cause.SERVER);
        Unit quad = spawn("quad", 33 * 8, 30 * 8);
        Payloads.pickedBuildPayload(quad, buildings.at(33, 30), true, Cause.SERVER);
        for(int tick = 0; tick < 30; tick++) world.tick();

        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        World fork = world.fork();
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        assertEquals(world.hash(), fork.hash(), () -> world.hash().firstDifference(fork.hash()).orElse("?"));
        for(World w : new World[]{world, restored, fork}){
            for(int i = 0; i < w.units().size(); i++){
                Unit unit = w.units().get(i);
                if(unit.payloads != null && !unit.payloads.isEmpty()){
                    unit.set(unit.x + 64, unit.y);
                    Payloads.dropLastPayload(unit, Cause.SERVER);
                }
            }
        }
        for(int tick = 0; tick < 300; tick++){
            world.tick();
            restored.tick();
            fork.tick();
            int at = tick;
            assertEquals(world.hash(), restored.hash(), () -> "tick " + at + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
            assertEquals(world.hash(), fork.hash(), () -> "tick " + at + ": " + world.hash().firstDifference(fork.hash()).orElse("?"));
        }
        assertEquals(12, restored.buildings().at(38, 30).items.get(COPPER), "the restored container was dropped with its copper");
    }
}
