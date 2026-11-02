package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A unit's lifecycle, as vanilla's {@code UnitType.create}/{@code spawn} and generated {@code add()}/{@code remove()}. */
class UnitTest{
    static final UnitDef DAGGER = VanillaWorlds.VANILLA.unit("dagger"), FLARE = VanillaWorlds.VANILLA.unit("flare");
    static final UnitDef LATUM = VanillaWorlds.VANILLA.unit("latum"), MEGA = VanillaWorlds.VANILLA.unit("mega");

    @Test
    void createDrawsTheIdThenTheFieldInitializersThenTheAiTimers(){
        World world = UnitWorlds.world(32, 32, 7);
        int id = world.ids().peek();
        Rand expected = world.rand().copy();
        Unit crawl = Unit.create(world, LATUM, 1);
        assertEquals(id, crawl.id());
        // CrawlComp's initializer runs before UnitComp's in the generated constructor
        assertEquals(expected.random(100f), ((MoveShape.Crawl)crawl.shape).crawlTime);
        assertEquals(expected.random(10f), crawl.resupplyTime);
        var ai = (AiController)crawl.controller;
        assertEquals(-expected.random(40f), ai.timers[0]);
        assertEquals(-expected.random(60f), ai.timers[1]);
        Unit mech = Unit.create(world, DAGGER, 1);
        assertEquals(id + 1, mech.id());
        assertEquals(expected.random(10f), mech.resupplyTime, "a mech draws only resupplyTime");
        expected.random(40f);
        expected.random(60f);
        assertEquals(expected.getState(0), world.rand().getState(0));
        assertFalse(mech.isAdded());
    }

    @Test
    void theTypeSetsHealthHitboxMountsAndElevation(){
        World world = UnitWorlds.world(32, 32, 1);
        Unit dagger = Unit.create(world, DAGGER, 1);
        assertEquals(150f, dagger.health);
        assertEquals(150f, dagger.maxHealth);
        assertEquals(8f, dagger.hitSize);
        assertEquals(DAGGER.weapons().size(), dagger.mounts.length);
        assertTrue(dagger.mounts.length > 0);
        assertEquals(0f, dagger.elevation);
        assertInstanceOf(MoveShape.Mech.class, dagger.shape);
        assertNull(dagger.payloads);
        Unit flare = Unit.create(world, FLARE, 1);
        assertEquals(1f, flare.elevation);
        assertEquals(UnitPhysics.LAYER_FLYING, flare.collisionLayer());
        assertEquals(UnitPhysics.LAYER_GROUND, dagger.collisionLayer());
        assertNotNull(Unit.create(world, MEGA, 1).payloads);
        WeaponMount[] mounts = dagger.mounts;
        dagger.setType(DAGGER);
        assertSame(mounts, dagger.mounts, "the same weapon count keeps the mounts");
    }

    @Test
    void addJoinsTheListCountsTheUnitAndGivesItABody(){
        World world = UnitWorlds.world(32, 32, 2);
        Unit unit = Unit.spawn(world, DAGGER, 2, 40f, 50f, 90f);
        assertTrue(unit.isAdded());
        assertSame(unit, world.units().get(0));
        assertEquals(40f, unit.lastX);
        assertEquals(40f, unit.deltaX, "the last position of a new unit is (0, 0)");
        TeamUnits counts = world.part(TeamUnits.class);
        assertEquals(1, counts.count(2));
        assertEquals(1, counts.count(2, DAGGER));
        assertEquals(0, counts.count(1, DAGGER));
        assertTrue(unit.hasPhysicsRef);
        assertEquals(1, world.part(UnitPhysics.class).bodies());
        unit.add();
        assertEquals(1, world.units().size(), "adding twice does nothing");
        assertEquals(1, counts.count(2));
    }

    @Test
    void removeUncountsAndTellsTheController(){
        World world = UnitWorlds.world(32, 32, 3);
        Unit unit = Unit.spawn(world, DAGGER, 1, 40f, 40f, 0f);
        var removed = new ArrayList<Unit>();
        unit.controller = new Recording(removed);
        unit.remove();
        assertFalse(unit.isAdded());
        assertEquals(List.of(unit), removed);
        assertEquals(0, world.part(TeamUnits.class).count(1));
        unit.remove();
        assertEquals(1, removed.size(), "removing twice does nothing");
        assertEquals(0, world.part(TeamUnits.class).count(1), "counts never go below zero");
        assertTrue(unit.hasPhysicsRef, "the body goes at the next physics begin");
        world.tick();
        assertFalse(unit.hasPhysicsRef);
        assertEquals(0, world.part(UnitPhysics.class).bodies());
    }

    @Test
    void aUnitOverTheCapDiesAtOnceAndIsDestroyedNextTick(){
        World world = UnitWorlds.world(32, 32, 4);
        TeamUnits counts = world.part(TeamUnits.class);
        UnitWorlds.cap(world, 1);
        Unit first = Unit.spawn(world, DAGGER, 1, 40f, 40f, 0f);
        Unit second = Unit.spawn(world, DAGGER, 1, 60f, 40f, 0f);
        Unit flare = Unit.spawn(world, FLARE, 1, 80f, 40f, 0f);
        assertFalse(first.dead);
        assertTrue(second.dead);
        assertFalse(flare.dead, "the cap counts per type");
        assertTrue(second.isAdded(), "still in the world until the posted destroy");
        assertEquals(1, counts.count(1, DAGGER));
        assertEquals(1, counts.doomed());
        Unit core = Unit.create(world, DAGGER, 1);
        core.spawnedByCore = true;
        core.add();
        assertFalse(core.dead, "core units ignore the cap");
        world.tick();
        assertFalse(second.isAdded());
        assertTrue(first.isAdded());
        assertFalse(core.isAdded(), "a core unit nobody controls despawns at its first update");
        assertEquals(0, counts.doomed());
        assertEquals(1, counts.count(1, DAGGER), "the recount sees the first dagger");
    }

    @Test
    void theRecountCountsCarriedUnitsForTheirOwnTeam(){
        World world = UnitWorlds.world(32, 32, 5);
        Unit mega = Unit.spawn(world, MEGA, 1, 40f, 40f, 0f);
        Unit carried = Unit.spawn(world, DAGGER, 3, 40f, 40f, 0f);
        carried.remove();
        mega.payloads.add(new Payload.UnitPayload(carried));
        TeamUnits counts = world.part(TeamUnits.class);
        assertEquals(0, counts.count(3));
        world.tick();
        assertEquals(1, counts.count(3, DAGGER));
        assertEquals(1, counts.count(1));
        assertEquals(0, counts.units(3).size(), "carried units count but are not listed");
    }

    @Test
    void teamListsChangeOnlyAtTheRecount(){
        World world = UnitWorlds.world(32, 32, 6);
        Unit a = Unit.spawn(world, DAGGER, 1, 40f, 40f, 0f);
        TeamUnits counts = world.part(TeamUnits.class);
        assertEquals(0, counts.units(1).size(), "not listed before the recount");
        world.tick();
        assertSame(a, counts.units(1).get(0));
        assertSame(a, counts.units(1, DAGGER).get(0));
        a.remove();
        assertSame(a, counts.units(1).get(0), "still listed until the next recount");
        world.tick();
        assertEquals(0, counts.units(1).size());
    }

    record Recording(List<Unit> removed) implements UnitController{
        @Override
        public void updateUnit(Unit unit){
        }

        @Override
        public void removed(Unit unit){
            removed.add(unit);
        }

        @Override
        public UnitController copy(CopyContext context){
            return this;
        }

        @Override
        public void hash(Hasher hasher){
        }
    }
}
