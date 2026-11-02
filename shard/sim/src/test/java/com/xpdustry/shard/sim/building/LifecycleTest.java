package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Building and unit lifecycles: update-list membership, sleep and wake, and stale references. */
class LifecycleTest{
    @Test
    void aToyKindRegisteredThroughTheIndexTicks(){
        World world = Worlds.quiet(1);
        var counter = (Counter)world.buildings().create(ToyContent.COUNTER, 1, 10, 10, 0, Cause.SERVER);
        assertTrue(counter.isAdded());
        assertSame(counter, world.builds().get(0));
        for(int i = 0; i < 5; i++) world.tick();
        assertEquals(5, counter.count, "it can't fall asleep within 60 ticks");
        assertNotNull(counter.items, "a block with items gets an item module");
        assertNull(counter.liquids);
    }

    @Test
    void blocksWithoutUpdateNeverJoinTheList(){
        World world = Worlds.quiet(2);
        Building wall = world.buildings().create(ToyContent.WALL, 1, 5, 5, 0, Cause.SERVER);
        assertFalse(wall.isAdded());
        assertTrue(wall.isValid());
        assertEquals(0, world.builds().size());
    }

    @Test
    void sleepingLeavesTheListAndWakingAppends(){
        World world = Worlds.quiet(3);
        Building first = world.buildings().create(ToyContent.WALL, 1, 1, 1, 0, Cause.SERVER);
        first.add();
        Building second = world.buildings().create(ToyContent.WALL, 1, 3, 3, 0, Cause.SERVER);
        second.add();
        Building third = world.buildings().create(ToyContent.WALL, 1, 5, 5, 0, Cause.SERVER);
        third.add();
        for(int i = 0; i < 59; i++) first.sleep();
        assertTrue(first.isAdded(), "awake until 60 idle ticks");
        first.sleep();
        assertTrue(first.sleeping());
        assertFalse(first.isAdded(), "vanilla quirk: a sleeping building is not added");
        assertTrue(first.isValid(), "but it still stands");
        assertEquals(java.util.List.of(third, second), world.builds().toList(), "the last building filled the hole");
        first.noSleep();
        assertEquals(java.util.List.of(third, second, first), world.builds().toList(), "waking appends");
    }

    @Test
    void removedBuildingsAreStaleEvenWhenTheirIndexIsReused(){
        World world = Worlds.quiet(4);
        Building old = world.buildings().create(ToyContent.LINK, 1, 10, 10, 0, Cause.SERVER);
        int index = old.index();
        world.buildings().remove(old, Cause.SERVER);
        assertFalse(old.isAdded());
        assertFalse(old.isValid());
        assertEquals(-1, old.index());
        assertNull(world.buildings().at(world.tiles().pos(10, 10)));
        Building reused = world.buildings().create(ToyContent.COUNTER, 2, 30, 30, 0, Cause.SERVER);
        assertEquals(index, reused.index(), "freed indexes are reused");
        assertFalse(old.isValid(), "the old reference stays stale");
        assertTrue(reused.isValid());
        assertEquals(4 + 4 + 1, world.journal().entries().size(), "2×2 placed, 2×2 removed, 1×1 placed");
        assertEquals(ToyContent.LINK.id(), world.journal().entries().get(4).before().block(), "removal records the block it removed");
    }

    @Test
    void placementRefusesTakenAndOutOfBoundsTiles(){
        World world = Worlds.quiet(5);
        // A 2×2 block's offset is -(2 - 1) / 2 = 0, as vanilla's sizeOffset: (10, 10) covers 10..11 on both axes.
        world.buildings().create(ToyContent.LINK, 1, 10, 10, 0, Cause.SERVER);
        assertThrows(IllegalStateException.class, () -> world.buildings().create(ToyContent.COUNTER, 1, 11, 11, 0, Cause.SERVER));
        world.buildings().create(ToyContent.COUNTER, 1, 9, 9, 0, Cause.SERVER);
        assertThrows(IllegalStateException.class, () -> world.buildings().create(ToyContent.LINK, 1, 47, 47, 0, Cause.SERVER));
        assertEquals(2, world.buildings().count());
    }

    @Test
    void unitsAreStaleOnceRemoved(){
        World world = Worlds.quiet(6);
        int before = world.ids().peek();
        Unit unit = Unit.create(world, ToyContent.DRONE, 1);
        assertEquals(before, unit.id(), "ids come from the world's counter");
        assertFalse(unit.isAdded());
        unit.add();
        assertTrue(unit.isValid());
        unit.dead = true;
        unit.remove();
        assertFalse(unit.isAdded());
        assertFalse(unit.isValid());
        assertTrue(world.units().isEmpty());
    }
}
