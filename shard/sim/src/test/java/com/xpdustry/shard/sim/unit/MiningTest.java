package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit items, transfers, mining and the ore index on their own: {@code ItemsComp}'s stack rules, one event per Call,
 * the item that lands a flight later, {@code MinerComp}'s rules, and the index's vanilla order through forks and
 * checkpoints. Agreement with vanilla, frame by frame, is {@code MinerOracleTest}'s and {@code PlayerItemsOracleTest}'s.
 */
class MiningTest{
    static final ContentEpoch VANILLA = VanillaWorlds.VANILLA;
    static final int COPPER = VANILLA.item("copper").id(), LEAD = VANILLA.item("lead").id(), TITANIUM = VANILLA.item("titanium").id();

    World world;
    Core core;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(96, 96, 5);
        core = (Core)world.buildings().setBlock(10, 10, VANILLA.block("core-shard"), Team.SHARDED, 0, Cause.SERVER);
    }

    void ore(int x, int y, String ore){
        world.tiles().overlay(world.tiles().pos(x, y), VANILLA.block(ore).id());
    }

    Unit mono(float x, float y){
        Unit unit = Unit.spawn(world, VANILLA.unit("mono"), Team.SHARDED, x, y, 0f);
        unit.controller(new NoAi());
        return unit;
    }

    List<ItemEvent> drain(Observer observer){
        var out = new ArrayList<ItemEvent>();
        for(WorldEvent event; (event = observer.poll()) != null; ) if(event instanceof ItemEvent item) out.add(item);
        return out;
    }

    @Test
    void theStackFollowsItemsComp(){
        Unit unit = mono(40, 40);
        int capacity = unit.itemCapacity();
        assertTrue(capacity > 0);
        assertFalse(unit.hasItem());
        assertTrue(unit.acceptsItem(LEAD), "an empty stack takes anything");
        unit.addItem(COPPER, 5);
        assertEquals(COPPER, unit.item());
        assertFalse(unit.acceptsItem(LEAD), "another item doesn't fit");
        assertEquals(0, unit.maxAccepted(LEAD));
        assertEquals(capacity - 5, unit.maxAccepted(COPPER));
        unit.addItem(LEAD, 3);
        assertEquals(LEAD, unit.item(), "a different item replaces the stack");
        assertEquals(3, unit.stackAmount);
        unit.addItem(LEAD, capacity * 2);
        assertEquals(capacity, unit.stackAmount, "capped at the capacity");
        assertFalse(unit.acceptsItem(LEAD), "full");
        unit.clearItem();
        assertEquals(0, unit.stackAmount);
        assertEquals(LEAD, unit.item(), "an empty stack keeps its item");
    }

    @Test
    void eachTransferIsOneCall(){
        Unit unit = mono(40, 40);
        unit.addItem(COPPER, 10);
        core.items.set(COPPER, 100);
        Observer events = world.observe(64, List.of(ItemEvent.class), null);
        ItemTransfers.transferItemTo(world, unit, COPPER, 4, unit.x, unit.y, core);
        assertEquals(6, unit.stackAmount);
        assertEquals(104, core.items.get(COPPER));
        // another item than the stack's: the stack is untouched, the building still takes them
        ItemTransfers.transferItemTo(world, unit, LEAD, 2, unit.x, unit.y, core);
        assertEquals(6, unit.stackAmount);
        assertEquals(2, core.items.get(LEAD));
        ItemTransfers.takeItems(world, core, COPPER, 50, unit);
        assertEquals(unit.itemCapacity(), unit.stackAmount, "as much as fits");
        assertEquals(104 - (unit.itemCapacity() - 6), core.items.get(COPPER));
        ItemTransfers.transferItemToUnit(world, COPPER, 0f, 0f, unit);
        world.tick();
        List<ItemEvent> sent = drain(events);
        assertEquals(3, sent.size(), "one event per Call, none for transferItemToUnit: " + sent);
        assertEquals(new ItemEvent.TransferItemTo(unit.id(), -1, COPPER, 4, unit.x, unit.y, core.tile()), sent.get(0));
        assertInstanceOf(ItemEvent.TakeItems.class, sent.get(2));
    }

    @Test
    void aFlownItemLandsTwelveFramesLater(){
        Unit unit = mono(40, 40);
        ItemTransfers.transferItemToUnit(world, LEAD, 0f, 0f, unit);
        for(int tick = 1; tick < 12; tick++){
            world.tick();
            assertEquals(0, unit.stackAmount, "in flight at tick " + tick);
        }
        world.tick();
        assertEquals(1, unit.stackAmount);
        assertEquals(LEAD, unit.item());
    }

    @Test
    void minesOnlyWhatItsTierAndRangeAllow(){
        ore(60, 60, "ore-copper");
        ore(62, 60, "ore-titanium");
        ore(10, 60, "ore-copper");
        Unit unit = mono(61 * 8, 60 * 8);
        assertTrue(Miner.validMine(unit, Point2.pack(60, 60)));
        assertFalse(Miner.validMine(unit, Point2.pack(62, 60)), "titanium is harder than a mono's tier");
        assertFalse(Miner.validMine(unit, Point2.pack(61, 60)), "no ore");
        assertFalse(Miner.validMine(unit, Point2.pack(10, 60)), "out of range");
        assertTrue(Miner.validMine(unit, Point2.pack(10, 60), false), "in range or not");
        world.buildings().setBlock(60, 60, VANILLA.block("copper-wall"), Team.SHARDED, 0, Cause.SERVER);
        assertFalse(Miner.validMine(unit, Point2.pack(60, 60)), "a mono mines floors, not what is under a block");
    }

    @Test
    void aMinerFillsItsStackAwayFromTheCoreAndHandsItInWhenFull(){
        ore(60, 60, "ore-copper");
        Unit unit = mono(61 * 8, 60 * 8);
        unit.mineTile = Point2.pack(60, 60);
        for(int tick = 0; tick < 2_000 && unit.stackAmount < unit.itemCapacity(); tick++) world.tick();
        assertEquals(unit.itemCapacity(), unit.stackAmount, "the stack filled");
        assertEquals(0, core.items.get(COPPER), "out of the core's range, nothing went in");
        // full, next to the core: the stack goes in
        ore(14, 10, "ore-copper");
        unit.set(15 * 8, 10 * 8);
        unit.mineTile = Point2.pack(14, 10);
        Observer events = world.observe(64, List.of(ItemEvent.class), null);
        world.tick();
        assertEquals(0, unit.stackAmount);
        assertEquals(unit.itemCapacity(), core.items.get(COPPER));
        assertEquals(1, drain(events).size());
    }

    @Test
    void aFullStackOfAnotherItemStopsMining(){
        ore(60, 60, "ore-copper");
        Unit unit = mono(61 * 8, 60 * 8);
        unit.addItem(LEAD, unit.itemCapacity());
        unit.mineTile = Point2.pack(60, 60);
        for(int tick = 0; tick < 200 && unit.mineTile != -1; tick++) world.tick();
        assertEquals(-1, unit.mineTile, "nowhere to put copper");
        assertEquals(0f, unit.mineTimer);
    }

    @Test
    void theOreIndexKeepsVanillasOrder(){
        // three copper ores in the quadrant at (40..59, 40..59): listed in tile order
        ore(45, 41, "ore-copper");
        ore(42, 42, "ore-copper");
        ore(41, 43, "ore-copper");
        ore(70, 70, "ore-lead");
        // the world loaded empty: index the ores as a map load would
        OreIndex ores = OreIndex.of(world);
        ores.loaded();
        TileGrid tiles = world.tiles();
        assertTrue(ores.hasOre(COPPER));
        assertFalse(ores.hasOre(TITANIUM));
        assertEquals(tiles.pos(45, 41), ores.findClosestOre(0, 0, COPPER), "a quadrant's first tile, the lowest row first");
        // covering the first swaps the last into its place; uncovering appends it again
        world.buildings().setBlock(45, 41, VANILLA.block("copper-wall"), Team.SHARDED, 0, Cause.SERVER);
        assertEquals(tiles.pos(41, 43), ores.findClosestOre(0, 0, COPPER));
        world.buildings().setBlock(45, 41, VANILLA.block("air"), 0, 0, Cause.SERVER);
        assertEquals(tiles.pos(41, 43), ores.findClosestOre(0, 0, COPPER), "uncovered tiles go to the back");
        world.buildings().setBlock(42, 42, VANILLA.block("copper-wall-large"), Team.SHARDED, 0, Cause.SERVER);
        // the 2x2 block covers (42, 42): the back entry, (45, 41), takes its place
        assertEquals(tiles.pos(41, 43), ores.findClosestOre(0, 0, COPPER), "a 2x2 block covers its whole footprint");
        world.buildings().setBlock(41, 43, VANILLA.block("copper-wall"), Team.SHARDED, 0, Cause.SERVER);
        assertEquals(tiles.pos(45, 41), ores.findClosestOre(0, 0, COPPER));
        world.buildings().setBlock(41, 43, VANILLA.block("air"), 0, 0, Cause.SERVER);
        // a fork and a checkpoint keep the order
        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        for(World copy : List.of(fork, restored)){
            assertEquals(world.hash(), copy.hash(), () -> world.hash().firstDifference(copy.hash()).orElse("?"));
            copy.buildings().setBlock(45, 41, VANILLA.block("copper-wall"), Team.SHARDED, 0, Cause.SERVER);
            assertEquals(tiles.pos(41, 43), OreIndex.of(copy).findClosestOre(0, 0, COPPER), "the fork's and the restored list's order");
            assertEquals(tiles.pos(70, 70), OreIndex.of(copy).findClosestOre(0, 0, LEAD));
        }
        world.buildings().setBlock(41, 43, VANILLA.block("copper-wall"), Team.SHARDED, 0, Cause.SERVER);
        world.buildings().setBlock(45, 41, VANILLA.block("copper-wall"), Team.SHARDED, 0, Cause.SERVER);
        assertFalse(OreIndex.of(world).hasOre(COPPER), "every copper tile is covered");
        assertEquals(-1, OreIndex.of(world).findClosestOre(0, 0, COPPER));
    }
}
