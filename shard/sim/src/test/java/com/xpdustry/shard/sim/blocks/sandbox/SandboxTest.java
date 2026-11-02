package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The target dummy, the power void, the legacy blocks and the campaign blocks a server hosts: a dummy's unit and its
 * hits, landing pods, a launch and its payload, the accelerator, and their state through a fork and a checkpoint.
 * Exact agreement with vanilla is {@code DefenseOracleTest}'s.
 */
class SandboxTest{
    static final ContentEpoch VANILLA = VanillaWorlds.VANILLA;
    static final int COPPER = VANILLA.item("copper").id(), WATER = VANILLA.liquid("water").id();

    World world;

    @BeforeEach
    void world(){
        world = UnitWorlds.uncapped(UnitWorlds.world(48, 48, 3));
    }

    Building place(String block, int x, int y, int team){
        return world.buildings().setBlock(x, y, VANILLA.block(block), team, 0, Cause.SERVER);
    }

    @Test
    void sandboxLegacyAndCampaignBlocksHaveTheirKinds(){
        assertInstanceOf(PlainBuilding.class, place("power-void", 2, 2, Team.SHARDED));
        assertInstanceOf(TargetDummy.class, place("target-dummy", 5, 2, Team.SHARDED));
        assertInstanceOf(LegacyMechPad.class, place("legacy-mech-pad", 8, 2, Team.SHARDED));
        assertInstanceOf(LegacyUnitFactory.class, place("legacy-unit-factory-air", 12, 2, Team.SHARDED));
        assertInstanceOf(LegacyCommandCenter.class, place("command-center", 16, 2, Team.SHARDED));
        assertInstanceOf(LaunchPad.class, place("advanced-launch-pad", 20, 3, Team.SHARDED));
        assertInstanceOf(LandingPad.class, place("landing-pad", 26, 3, Team.SHARDED));
        assertInstanceOf(Accelerator.class, place("interplanetary-accelerator", 36, 5, Team.SHARDED));
    }

    @Test
    void aPowerVoidTakesItsGraphsWholeSupply(){
        place("power-source", 10, 10, Team.SHARDED);
        var battery = place("battery", 11, 10, Team.SHARDED);
        var power = place("power-void", 12, 10, Team.SHARDED);
        for(int i = 0; i < 5; i++) world.tick();
        assertSame(battery.power.graph, power.power.graph);
        assertTrue(power.power.status < 0.01f, "the void is never satisfied: " + power.power.status);
    }

    @Test
    void aTargetDummyKeepsAUnitAndCountsItsHits(){
        var dummy = (TargetDummy)place("target-dummy", 20, 20, Team.SHARDED);
        Observer events = world.observe(64, List.of(SandboxEvent.class), null);
        world.tick();
        Unit unit = dummy.unit;
        assertNotNull(unit);
        assertSame(dummy, unit.tether);
        List<WorldEvent> spawned = events.drain();
        assertEquals(1, spawned.size(), spawned.toString());
        assertEquals(unit.id(), ((SandboxEvent.TetherSpawned)spawned.getFirst()).unit());
        float health = unit.health;
        unit.damage(30f);
        assertEquals(health, unit.health, "the unit takes nothing");
        assertEquals(30f, dummy.total);
        assertEquals(1, dummy.hits);
        dummy.configureAny(new ConfigValue.IntArray(new int[]{0, 0, 10, 300, Float.floatToIntBits(20f)}));
        world.tick();
        assertEquals(10f, unit.armor);
        assertEquals(20f, unit.hitSize);
        dummy.configureAny(new ConfigValue.IntArray(new int[]{1, 0, 0, 300, Float.floatToIntBits(Float.NaN)}));
        assertEquals(8f, dummy.dummySize, "a NaN size is 8");
        assertEquals(Team.CRUX, dummy.unitTeam, "the wave team");
        unit.remove();
        world.tick();
        assertNotSame(unit, dummy.unit, "a lost unit is made again");
    }

    @Test
    void aWateredCruxLandingPadCallsItsItemDown(){
        var pad = (LandingPad)place("landing-pad", 20, 20, Team.CRUX);
        var mine = (LandingPad)place("landing-pad", 30, 30, Team.SHARDED);
        pad.configureAny(ContentRef.item(COPPER));
        mine.configureAny(ContentRef.item(COPPER));
        assertEquals(COPPER, pad.config);
        assertEquals(-1, mine.config, "the default team's pad isn't accessible off the campaign");
        pad.configureAny(ContentRef.item(VANILLA.item("beryllium").id()));
        assertEquals(COPPER, pad.config, "an Erekir item isn't on the rules' planet");
        Observer events = world.observe(64, List.of(SandboxEvent.class), null);
        world.tick();
        assertEquals(-1, pad.arriving, "dry");
        pad.liquids.add(WATER, 1500f);
        world.tick();
        world.tick();
        assertEquals(COPPER, pad.arriving);
        assertEquals(1, events.drain().size());
        for(int i = 0; i < 160; i++) world.tick();
        assertEquals(-1, pad.arriving);
        assertTrue(pad.items.get(COPPER) > 0, "landed");
        assertEquals(0f, pad.liquids.get(WATER), 0.001f, "the pod used the water");
    }

    @Test
    void aFullPoweredLaunchPadSendsAPayload(){
        var pad = (LaunchPad)place("launch-pad", 20, 20, Team.SHARDED);
        place("power-source", 22, 20, Team.SHARDED);
        pad.items.add(COPPER, 100);
        assertFalse(pad.acceptItem(pad, COPPER), "full");
        LaunchPayload payload = null;
        for(int i = 0; i < 1300 && payload == null; i++){
            world.tick();
            var all = EntityGroups.of(world).all();
            for(int j = 0; j < all.size(); j++) if(all.get(j) instanceof LaunchPayload p) payload = p;
        }
        assertNotNull(payload, "launched");
        assertEquals(0, pad.items.total());
        assertArrayEquals(new int[]{COPPER, 100}, payload.stacks);
        for(int i = 0; i < 125; i++) world.tick();
        assertFalse(payload.isAdded(), "gone after its lifetime");
    }

    @Test
    void forksAndCheckpointsKeepEveryKindsState(){
        var dummy = (TargetDummy)place("target-dummy", 5, 5, Team.SHARDED);
        dummy.configureAny(new ConfigValue.IntArray(new int[]{0, 1, 5, 200, Float.floatToIntBits(16f)}));
        var pad = (LandingPad)place("landing-pad", 20, 20, Team.CRUX);
        pad.configureAny(ContentRef.item(COPPER));
        pad.liquids.add(WATER, 3000f);
        var launch = (LaunchPad)place("launch-pad", 30, 30, Team.SHARDED);
        place("power-source", 32, 30, Team.SHARDED);
        launch.items.add(COPPER, 100);
        launch.launchCounter = 1190f;
        place("power-void", 40, 40, Team.SHARDED);
        place("interplanetary-accelerator", 10, 38, Team.SHARDED);
        for(int i = 0; i < 40; i++) world.tick();
        dummy.unit.damage(5f);

        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        for(World copy : List.of(fork, restored)){
            assertEquals(world.hash(), copy.hash(), () -> world.hash().firstDifference(copy.hash()).orElse("?"));
            var d = (TargetDummy)copy.buildings().at(5, 5);
            assertSame(d, d.unit.tether);
            assertEquals(16f, d.unit.hitSize);
            for(int i = 0; i < 60; i++) copy.tick();
        }
        for(int i = 0; i < 60; i++) world.tick();
        assertEquals(world.hash(), fork.hash());
        assertEquals(world.hash(), restored.hash());
    }
}
