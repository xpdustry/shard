package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Construction on its own: a builder pays for a site item by item and finishes it, breaking refunds, events in Call
 * order, rebuild plans, {@code overwrote}, {@code planRotation}, placement rules, and forks and checkpoints taken
 * mid-build. Agreement with vanilla, frame by frame, is {@code ConstructionOracleTest}'s.
 */
class ConstructionTest{
    static final ContentEpoch VANILLA = VanillaWorlds.VANILLA;
    static final int COPPER = VANILLA.item("copper").id(), LEAD = VANILLA.item("lead").id(), TITANIUM = VANILLA.item("titanium").id();

    World world;
    Rules rules;
    Core core;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(48, 48, 3);
        rules = world.part(WorldRules.class).rules;
        core = (Core)world.buildings().setBlock(6, 6, VANILLA.block("core-shard"), Team.SHARDED, 0, Cause.SERVER);
    }

    Unit alpha(float x, float y){
        Unit unit = Unit.spawn(world, VANILLA.unit("alpha"), Team.SHARDED, x, y, 0f);
        unit.controller(new NoAi());
        return unit;
    }

    BlockDef block(String name){
        return VANILLA.block(name);
    }

    List<WorldEvent> drain(Observer observer){
        var out = new ArrayList<WorldEvent>();
        for(WorldEvent event; (event = observer.poll()) != null; ) out.add(event);
        return out;
    }

    int ticksUntil(java.util.function.BooleanSupplier done, int max){
        for(int tick = 1; tick <= max; tick++){
            world.tick();
            if(done.getAsBoolean()) return tick;
        }
        return fail("not done after " + max + " ticks");
    }

    @Test
    void aBuilderPaysForASiteAndFinishesIt(){
        for(int item : new int[]{COPPER, LEAD, TITANIUM}) core.items.set(item, 100);
        Unit unit = alpha(20 * 8, 20 * 8);
        BlockDef belt = block("titanium-conveyor");
        unit.plans.addLast(BuildPlan.place(22, 20, 1, belt, null));
        Observer events = world.observe(256, List.of(BuildEvent.class), null);
        world.tick();
        var site = assertInstanceOf(ConstructBuild.class, world.buildings().at(22, 20));
        assertEquals(belt.id(), site.current);
        assertEquals(1, site.rotation);
        assertNotNull(site.accumulator);
        // 2.2 ticks of build time at alpha's 0.5: five ticks
        int ticks = ticksUntil(() -> world.buildings().at(22, 20) instanceof Conveyor, 600);
        assertEquals(4, ticks);
        assertEquals(List.of(99, 99, 99), List.of(core.items.get(COPPER), core.items.get(LEAD), core.items.get(TITANIUM)), "one of each paid");
        assertEquals(1, world.buildings().at(22, 20).rotation);
        world.tick();
        assertTrue(unit.plans.isEmpty(), "the done plan is dropped the next tick");
        List<WorldEvent> sent = drain(events);
        assertEquals(2, sent.size(), sent::toString);
        var begin = assertInstanceOf(BuildEvent.BeginPlace.class, sent.get(0));
        assertEquals(List.of(unit.id(), belt.id(), 22, 20, 1), List.of(begin.unit(), begin.block(), begin.x(), begin.y(), begin.rotation()));
        var finish = assertInstanceOf(BuildEvent.ConstructFinish.class, sent.get(1));
        assertEquals(List.of(22, 20, belt.id(), Team.SHARDED), List.of(finish.x(), finish.y(), finish.block(), finish.team()));
    }

    @Test
    void aBuilderWaitsForItemsAndSkipsWhatItCantAfford(){
        Unit unit = alpha(20 * 8, 20 * 8);
        unit.plans.addLast(BuildPlan.place(22, 20, 0, block("titanium-conveyor"), null));
        unit.plans.addLast(BuildPlan.place(23, 20, 0, block("conveyor"), null));
        core.items.set(COPPER, 10);
        ticksUntil(() -> world.buildings().at(23, 20) instanceof Conveyor, 600);
        world.tick();
        assertNull(world.buildings().at(22, 20), "no titanium: never begun");
        assertEquals(1, unit.plans.size());
        core.items.set(LEAD, 10);
        core.items.set(TITANIUM, 10);
        ticksUntil(() -> world.buildings().at(22, 20) instanceof Conveyor, 600);
        assertEquals(List.of(8, 9, 9), List.of(core.items.get(COPPER), core.items.get(LEAD), core.items.get(TITANIUM)));
    }

    @Test
    void breakingRefundsTheRulesShare(){
        core.items.set(COPPER, 0);
        world.buildings().setBlock(22, 20, block("copper-wall-large"), Team.SHARDED, 0, Cause.SERVER);
        Unit unit = alpha(20 * 8, 20 * 8);
        unit.plans.addLast(BuildPlan.breaking(22, 20));
        Observer events = world.observe(256, List.of(BuildEvent.class), null);
        world.tick();
        var site = assertInstanceOf(ConstructBuild.class, world.buildings().at(22, 20));
        assertEquals(1f, site.progress, 0.05f);
        ticksUntil(() -> world.buildings().at(22, 20) == null, 600);
        // copper-wall-large costs 24 copper; the default refund is half
        assertEquals(12, core.items.get(COPPER));
        assertEquals(0, world.tiles().block(world.tiles().pos(23, 21)), "the footprint is clear");
        List<WorldEvent> sent = drain(events);
        assertInstanceOf(BuildEvent.BeginBreak.class, sent.getFirst());
        var finish = assertInstanceOf(BuildEvent.DeconstructFinish.class, sent.getLast());
        assertEquals(block("copper-wall-large").id(), finish.block());
    }

    @Test
    void infiniteResourcesBuildFromNothing(){
        rules.infiniteResources = true;
        world.buildings().remove(core, Cause.SERVER);
        Unit unit = alpha(20 * 8, 20 * 8);
        unit.plans.addLast(BuildPlan.place(30, 30, 0, block("router"), null));
        ticksUntil(() -> world.buildings().at(30, 30) != null && world.buildings().at(30, 30).block() == block("router"), 600);
    }

    @Test
    void placedAutolinksANodeNextToABattery(){
        core.items.set(COPPER, 200);
        core.items.set(LEAD, 200);
        world.buildings().setBlock(24, 20, block("battery"), Team.SHARDED, 0, Cause.SERVER);
        Unit unit = alpha(20 * 8, 20 * 8);
        unit.plans.addLast(BuildPlan.place(22, 20, 0, block("power-node"), null));
        ticksUntil(() -> world.buildings().at(22, 20) instanceof PowerNode, 600);
        var node = (PowerNode)world.buildings().at(22, 20);
        assertTrue(node.power.links.contains(Point2.pack(24, 20)), "placed() linked the battery");
    }

    @Test
    void aTitaniumConveyorOverAConveyorKeepsItsItems(){
        for(int item : new int[]{COPPER, LEAD, TITANIUM}) core.items.set(item, 100);
        var belt = (Conveyor)world.buildings().setBlock(22, 20, block("conveyor"), Team.SHARDED, 0, Cause.SERVER);
        belt.handleItem(belt, COPPER);
        belt.handleItem(belt, LEAD);
        Unit unit = alpha(20 * 8, 20 * 8);
        unit.plans.addLast(BuildPlan.place(22, 20, 0, block("titanium-conveyor"), null));
        world.tick();
        var site = assertInstanceOf(ConstructBuild.class, world.buildings().at(22, 20));
        assertEquals(List.of(belt), site.prevBuild);
        ticksUntil(() -> world.buildings().at(22, 20) instanceof Conveyor c && c.block() == block("titanium-conveyor"), 600);
        var upgraded = (Conveyor)world.buildings().at(22, 20);
        assertEquals(2, upgraded.len);
        assertEquals(1, upgraded.items.get(COPPER));
        assertEquals(1, upgraded.items.get(LEAD));
    }

    @Test
    void aDestroyedBlockBecomesARebuildPlanUntilBuiltOver(){
        var wall = world.buildings().setBlock(20, 20, block("copper-wall"), Team.SHARDED, 0, Cause.SERVER);
        var node = world.buildings().setBlock(22, 20, block("power-node"), Team.SHARDED, 0, Cause.SERVER);
        world.buildings().kill(wall);
        world.buildings().kill(node);
        List<BlockPlan> plans = Teams.of(world).get(Team.SHARDED).plans();
        assertEquals(2, plans.size());
        assertEquals(List.of(22, 20), List.of(plans.get(0).x, plans.get(1).x), "the newest first");
        assertEquals(block("copper-wall").id(), plans.get(1).block);
        BlockPlan nodePlan = plans.get(0);
        // something else built over the node's tile drops its plan
        core.items.set(COPPER, 100);
        Unit unit = alpha(20 * 8, 20 * 8);
        unit.plans.addLast(BuildPlan.place(22, 20, 0, block("copper-wall"), null));
        ticksUntil(() -> world.buildings().at(22, 20) != null && world.buildings().at(22, 20).block() == block("copper-wall"), 600);
        assertEquals(1, plans.size());
        assertTrue(nodePlan.removed);
        assertEquals(1, RebuildPlans.delete(world, Team.SHARDED, new int[]{Point2.pack(20, 20)}));
        assertTrue(plans.isEmpty());
        rules.ghostBlocks = false;
        world.buildings().kill(world.buildings().at(22, 20));
        assertTrue(plans.isEmpty(), "no ghost blocks, no plans");
    }

    @Test
    void aSiteDestroyedWhileBuildingIsPlannedAsItsBlock(){
        core.items.set(COPPER, 100);
        Unit unit = alpha(20 * 8, 20 * 8);
        unit.plans.addLast(BuildPlan.place(22, 20, 0, block("copper-wall"), null));
        world.tick();
        var site = assertInstanceOf(ConstructBuild.class, world.buildings().at(22, 20));
        world.buildings().kill(site);
        List<BlockPlan> plans = Teams.of(world).get(Team.SHARDED).plans();
        assertEquals(1, plans.size());
        assertEquals(block("copper-wall").id(), plans.getFirst().block);
        assertNull(world.buildings().at(22, 20), "a site doesn't explode, and the tile is air");
    }

    @Test
    void planRotationZeroesBlocksThatDontRotate(){
        assertEquals(0, BuildPlan.place(1, 1, 3, block("router"), null).rotation);
        assertEquals(3, BuildPlan.place(1, 1, 3, block("conveyor"), null).rotation);
    }

    @Test
    void placementRules(){
        BlockDef wall = block("copper-wall");
        assertTrue(Build.validPlace(world, wall, Team.SHARDED, 20, 20, 0));
        assertFalse(Build.validPlace(world, wall, Team.SHARDED, 6, 6, 0), "the core is in the way");
        assertFalse(Build.validPlace(world, wall, Team.SHARDED, 0, 20, 0), "the map's dark edge");
        assertFalse(Build.validPlace(world, wall, Team.CRUX, 20, 20, 0), "inside sharded's core build radius");
        rules.enemyCoreBuildRadius = 0f;
        world.buildings().setBlock(20, 20, wall, Team.CRUX, 0, Cause.SERVER);
        assertFalse(Build.validPlace(world, block("titanium-wall"), Team.SHARDED, 20, 20, 0), "another team's wall");
        assertTrue(Build.validPlace(world, block("titanium-wall"), Team.CRUX, 20, 20, 0), "its own team replaces it");
        assertFalse(Build.validPlace(world, wall, Team.CRUX, 20, 20, 0), "the same block, which doesn't rotate");
        assertFalse(Build.validBreak(world, Team.SHARDED, 20, 20));
        assertTrue(Build.validBreak(world, Team.CRUX, 20, 20));
        assertFalse(Build.validPlace(world, block("mechanical-drill"), Team.SHARDED, 30, 30, 0), "no ore under the drill");
        // alpha flies; a dagger stands on the ground
        Unit.spawn(world, VANILLA.unit("dagger"), Team.SHARDED, 25 * 8, 25 * 8, 0f).controller(new NoAi());
        // the unit index sees a unit from the next tick on
        world.tick();
        assertFalse(Build.checkNoUnitOverlap(world, wall, 25, 25), "a grounded unit stands there");
        assertTrue(Build.checkNoUnitOverlap(world, block("conveyor"), 25, 25), "conveyors aren't solid");
    }

    @Test
    void forksAndCheckpointsContinueAMidwayBuild(){
        for(int item : new int[]{COPPER, LEAD, TITANIUM}) core.items.set(item, 100);
        var belt = (Conveyor)world.buildings().setBlock(22, 20, block("conveyor"), Team.SHARDED, 0, Cause.SERVER);
        belt.handleItem(belt, COPPER);
        world.buildings().kill(world.buildings().setBlock(30, 30, block("copper-wall"), Team.SHARDED, 0, Cause.SERVER));
        Unit unit = alpha(20 * 8, 20 * 8);
        unit.plans.addLast(BuildPlan.place(22, 20, 0, block("titanium-conveyor"), null));
        unit.plans.addLast(BuildPlan.place(24, 20, 0, block("power-node"), null));
        for(int i = 0; i < 3; i++) world.tick();
        assertInstanceOf(ConstructBuild.class, world.buildings().at(22, 20));
        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        for(World copy : List.of(fork, restored)){
            assertEquals(world.hash(), copy.hash(), () -> world.hash().firstDifference(copy.hash()).orElse("?"));
        }
        for(int i = 0; i < 120; i++){
            world.tick();
            fork.tick();
            restored.tick();
            int at = i;
            assertEquals(world.hash(), fork.hash(), () -> "fork, tick " + at + ": " + world.hash().firstDifference(fork.hash()).orElse("?"));
            assertEquals(world.hash(), restored.hash(), () -> "restored, tick " + at + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
        }
        assertInstanceOf(PowerNode.class, world.buildings().at(24, 20), "both plans finished");
    }
}
