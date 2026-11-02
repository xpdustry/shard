package com.xpdustry.shard.sim.blocks.transport;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conveyors and routing in a Shard world alone: what each kind does with items, logic {@code enabled}, the
 * read-time update, and checkpoints and forks of it all continuing bit for bit. {@code ConveyorOracleTest} and
 * {@code DistributionOracleTest} hold the same kinds to vanilla frame by frame.
 */
class TransportTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final int COPPER = BLOCKS.item("copper").id(), LEAD = BLOCKS.item("lead").id();

    World world;
    Buildings buildings;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(24, 24, 1);
        buildings = world.buildings();
    }

    Building place(String block, int x, int y, int rotation){
        return buildings.setBlock(x, y, BLOCKS.block(block), 1, rotation, Cause.SERVER);
    }

    void line(String block, int x, int y, int length){
        for(int i = 0; i < length; i++) place(block, x + i, y, 0);
    }

    void source(int x, int y, int item){
        place("item-source", x, y, 0).configureAny(ContentRef.item(item));
    }

    @Test
    void aLineCarriesItemsToItsEndAndSleepsWhenEmpty(){
        source(1, 5, COPPER);
        line("conveyor", 2, 5, 6);
        place("item-void", 8, 5, 0);
        var first = (ConveyorBuild)buildings.at(2, 5);
        var last = (ConveyorBuild)buildings.at(7, 5);
        assertSame(buildings.at(3, 5), first.next);
        assertTrue(first.aligned);
        for(int i = 0; i < 300; i++) world.tick();
        assertEquals(3, first.len, "a fed belt is full");
        assertTrue(last.len > 0, "items reach the end");
        assertEquals(first.items.total(), first.len);

        buildings.at(1, 5).configureAny(null);
        for(int i = 0; i < 400; i++) world.tick();
        assertEquals(0, last.len, "the belt empties into the void");
        assertTrue(last.sleeping(), "an empty belt sleeps");
        assertFalse(last.isAdded());
    }

    @Test
    void logicStopsALineOnTheTickAndStartsItAgain(){
        source(1, 5, COPPER);
        line("titanium-conveyor", 2, 5, 6);
        place("item-void", 8, 5, 0);
        for(int i = 0; i < 120; i++) world.tick();
        var middle = (ConveyorBuild)buildings.at(4, 5);
        middle.control(LAccess.enabled, 0, 0, 0, 0);
        world.tick();
        float[] stopped = middle.ys.clone();
        for(int i = 0; i < 30; i++) world.tick();
        assertArrayEquals(stopped, middle.ys, "nothing moves on a disabled conveyor");
        assertEquals(0f, middle.efficiency);

        middle.noSleep();
        middle.control(LAccess.enabled, 1, 0, 0, 0);
        world.tick();
        assertNotEquals(stopped[middle.len - 1], middle.ys[middle.len - 1], "it moves on the tick it is enabled");
    }

    @Test
    void sideInputsEnterTheMiddleSlotFromTheirSide(){
        var belt = (ConveyorBuild)place("conveyor", 5, 5, 0);
        var left = place("router", 5, 6, 0);
        assertTrue(belt.acceptItem(left, COPPER));
        belt.handleItem(left, COPPER);
        assertEquals(1, belt.len);
        assertEquals(0.5f, belt.ys[0]);
        assertEquals(1f, belt.xs[0], "from the left (above, at rotation 0) it starts at x 1");
        var behind = place("router", 4, 5, 0);
        belt.minitem = 1f;
        belt.handleItem(behind, LEAD);
        assertEquals(LEAD, belt.ids[0], "from behind it enters slot 0");
        assertEquals(0f, belt.ys[0]);
    }

    @Test
    void theReadTimeUpdateMovesLoadedItemsOnce(){
        var belt = (ConveyorBuild)place("conveyor", 5, 5, 0);
        belt.len = 1;
        belt.ids[0] = COPPER;
        belt.ys[0] = 0.5f;
        belt.xs[0] = 1f;
        belt.items.add(COPPER, 1);
        belt.efficiency = 1f;
        belt.loaded();
        assertEquals(0.5f + belt.speed(), belt.ys[0]);
        assertEquals(1f - belt.speed() * 2, belt.xs[0]);
        assertEquals(0.5f + belt.speed(), belt.minitem);
    }

    @Test
    void sortersAndGatesHoldNothing(){
        source(1, 5, COPPER);
        place("conveyor", 2, 5, 0);
        var sorter = (Sorter)place("sorter", 3, 5, 0);
        sorter.configureAny(ContentRef.item(LEAD));
        place("item-void", 4, 5, 0);
        place("item-void", 3, 6, 0);
        place("item-void", 3, 4, 0);
        for(int i = 0; i < 120; i++) world.tick();
        assertNull(sorter.items, "a sorter has no items");
        assertEquals(0, sorter.rotation & ~1, "only items from the west flipped a bit");
        Building west = buildings.at(2, 5);
        Building first = sorter.getTileTarget(COPPER, west, true), second = sorter.getTileTarget(COPPER, west, true);
        assertNotSame(first, second, "copper alternates between the sides");
        assertSame(buildings.at(4, 5), sorter.getTileTarget(LEAD, west, false), "the sort item goes straight on");
        assertEquals(ContentRef.item(LEAD), sorter.config());
    }

    @Test
    void aCheckpointAndAForkCarryEveryKind(){
        source(1, 5, COPPER);
        line("conveyor", 2, 5, 3);
        place("junction", 5, 5, 0);
        line("titanium-conveyor", 6, 5, 2);
        place("sorter", 8, 5, 0).configureAny(ContentRef.item(COPPER));
        place("item-void", 9, 5, 0);
        place("item-void", 8, 6, 0);
        source(5, 2, LEAD);
        place("conveyor", 5, 3, 1);
        place("conveyor", 5, 4, 1);
        place("underflow-gate", 5, 6, 0);
        place("item-void", 5, 7, 0);
        place("item-void", 4, 6, 0);
        source(1, 10, LEAD);
        line("conveyor", 2, 10, 2);
        line("plastanium-conveyor", 4, 10, 6);
        place("item-void", 10, 10, 0);
        place("unloader", 6, 11, 0);
        place("router", 7, 11, 0);
        place("conveyor", 6, 12, 1);
        for(int i = 0; i < 200; i++) world.tick();
        buildings.setBlock(7, 11, BLOCKS.block("air"), 0, 0, Cause.SERVER);
        assertEquals(1, ContainerStats.of(world).size(), "the router's stat went back to the pool");
        for(int i = 0; i < 37; i++) world.tick();

        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        World fork = world.fork();
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        assertEquals(world.hash(), fork.hash(), () -> world.hash().firstDifference(fork.hash()).orElse("?"));
        for(int i = 0; i < 300; i++){
            world.tick();
            restored.tick();
            fork.tick();
            int tick = i;
            assertEquals(world.hash(), restored.hash(), () -> "tick " + tick + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
            assertEquals(world.hash(), fork.hash(), () -> "tick " + tick + ": " + world.hash().firstDifference(fork.hash()).orElse("?"));
        }
        var stack = (StackConveyor)restored.buildings().at(9, 10);
        assertEquals(((StackConveyor)buildings.at(9, 10)).state, stack.state);
    }

    /** Review 38 and 39: content and other wire configs in a builder's plans, nested ones and foreign types included, survive checkpoints and forks. */
    @Test
    void contentConfigsInPlansSurviveCheckpointsAndForks(){
        int water = BLOCKS.liquid("water").id();
        var unit = com.xpdustry.shard.sim.unit.Unit.spawn(world, BLOCKS.unit("poly"), 1, 40f, 40f, 0f);
        Object[] configs = {ContentRef.item(COPPER), ContentRef.liquid(water), new Object[]{ContentRef.item(LEAD), ContentRef.liquid(water), 4},
            new ContentRef.Foreign(16, 2),
            // review 39: the values only a client's config carries
            new com.xpdustry.shard.sim.building.ConfigValue.Team(2), new com.xpdustry.shard.sim.building.ConfigValue.TechNode(ContentRef.item(COPPER)),
            new com.xpdustry.shard.sim.building.ConfigValue.IntArray(new int[]{1, 2}), com.xpdustry.shard.logic.lang.LAccess.health,
            new Object[]{new com.xpdustry.shard.sim.building.ConfigValue.Team(1), com.xpdustry.shard.logic.lang.LAccess.enabled}};
        for(int i = 0; i < configs.length; i++){
            unit.plans.addLast(com.xpdustry.shard.sim.unit.BuildPlan.place(10 + i, 10, 0, BLOCKS.block("sorter").id(), configs[i]));
        }
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        World fork = world.fork();
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        assertEquals(world.hash(), fork.hash(), () -> world.hash().firstDifference(fork.hash()).orElse("?"));
        for(World copy : new World[]{restored, fork}){
            var plans = copy.units().get(0).plans;
            for(int i = 0; i < configs.length; i++){
                assertTrue(java.util.Objects.deepEquals(configs[i], plans.get(i).config), "plan " + i + ": " + plans.get(i).config);
            }
        }
        var nested = new Object[]{ContentRef.item(COPPER)};
        assertNotEquals(world.hash(), withConfig(nested).hash(), "the config is hashed");
    }

    World withConfig(Object config){
        World other = world.fork();
        other.units().get(0).plans.get(0).config = config;
        return other;
    }
}
