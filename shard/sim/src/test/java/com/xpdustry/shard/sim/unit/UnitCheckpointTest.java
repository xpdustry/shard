package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkpoints carry every field of a unit and its parts (the unit hash covers every one: {@link UnitStateTest}), and
 * the team index with its pending cap deaths. A restore applies the saved controller and rebuilds only the type's
 * stats, so it draws nothing from the world random.
 */
class UnitCheckpointTest{
    static final List<String> TYPES = List.of("atrax", "mega", "latum", "risso", "elude", "stell", "dagger");

    static World restore(World world){
        return Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
    }

    static Unit byId(World world, int id){
        for(int i = 0; i < world.units().size(); i++) if(world.units().get(i).id() == id) return world.units().get(i);
        throw new AssertionError("no unit " + id);
    }

    @Test
    void aRestoredUnitHasEveryFieldOfEveryPart(){
        World world = UnitWorlds.world(32, 32, 14);
        var units = new ArrayList<Unit>();
        for(String type : TYPES) units.add(UnitStateTest.populated(world, type));
        long seed0 = world.rand().seed0;
        World restored = restore(world);
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        assertEquals(seed0, restored.rand().seed0, "restoring draws nothing");

        for(Unit unit : units){
            Unit copy = byId(restored, unit.id());
            assertSame(restored, copy.world());
            assertSame(unit.type(), copy.type());
            assertEquals(unit.hitSize, copy.hitSize, "restoreType() rebuilt the type's stats");
            assertEquals(unit.maxHealth, copy.maxHealth);
            assertEquals(unit.drag, copy.drag);
            assertEquals(unit.armor, copy.armor);
            assertInstanceOf(Follow.class, copy.controller, "the saved controller, not the type's default");
            assertEquals(unit.abilities.length, copy.abilities.length, "abilities whose count differs from the type's stay");
            assertEquals(unit.mounts.length, copy.mounts.length);
            assertSame(copy, copy.mounts[0].target, "a mount's target is a reference");
            assertArrayEquals(new int[]{7, 8}, (int[])copy.plans.get(0).config, "a plan's config keeps its type");
            assertEquals(unit.shape.getClass(), copy.shape.getClass());
        }
        Unit carrier = byId(restored, units.get(1).id());
        Unit carried = ((Payload.UnitPayload)carrier.payloads.get(0)).unit();
        assertSame(restored, carried.world());
        assertFalse(carried.isAdded(), "a carried unit is out of the world, and stays out");
        assertSame(restored.buildings().get(units.get(1).tether.index()), carrier.tether);
        var legs = (MoveShape.Legs)byId(restored, units.get(0).id()).shape;
        assertEquals(3f, legs.legs[1].base.x);
        assertTrue(legs.legs[1].moving);

        for(int i = 0; i < 60; i++){
            world.tick();
            restored.tick();
            assertEquals(world.hash(), restored.hash(), "tick " + i + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
        }
    }

    @Test
    void aUnitOverItsCapDiesAfterTheRestoreToo(){
        World world = UnitWorlds.world(32, 32, 15);
        Unit.spawn(world, VanillaWorlds.VANILLA.unit("dagger"), 1, 40f, 40f, 0f);
        world.tick();
        TeamUnits teams = world.part(TeamUnits.class);
        UnitWorlds.cap(world, 1);
        Unit doomed = Unit.spawn(world, VanillaWorlds.VANILLA.unit("dagger"), 1, 60f, 40f, 0f);
        assertTrue(doomed.dead);
        assertEquals(1, teams.doomed());
        World restored = restore(world);
        TeamUnits copy = restored.part(TeamUnits.class);
        assertEquals(1, copy.doomed(), "the pending cap death is state");
        assertEquals(1, Units.getCap(restored, 1));
        assertEquals(teams.count(1), copy.count(1));
        assertEquals(teams.units(1).size(), copy.units(1).size());
        assertEquals(world.hash(), restored.hash());
        world.tick();
        restored.tick();
        assertEquals(0, copy.doomed());
        assertEquals(world.units().size(), restored.units().size());
        assertEquals(world.hash(), restored.hash());
    }
}
