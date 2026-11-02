package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.logic.*;
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
 * Review 40: a plan's config reaches the block it builds through {@code Building.admitConfig}, as a client's direct
 * config does. A processor code past {@link LogicConfig}'s bounds isn't applied and its {@code constructFinish} carries
 * null, so clients configure what the server did; processors finishing in one tick share the tick's
 * {@link LogicPart#CONFIG_BYTES_PER_TICK}, so finishing many inflates no more than the budget, as its own counters tell.
 */
class PlanConfigTest{
    static final ContentEpoch VANILLA = VanillaWorlds.VANILLA;
    static final BlockDef MICRO = VANILLA.block("micro-processor");

    World world;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(64, 64, 3);
        world.part(WorldRules.class).rules.infiniteResources = true;
    }

    Unit builder(int x, int y){
        Unit unit = Unit.spawn(world, VANILLA.unit("alpha"), Team.SHARDED, x * 8, y * 8, 0f);
        unit.controller(new NoAi());
        return unit;
    }

    LogicBlock processor(int x, int y){
        return assertInstanceOf(LogicBlock.class, world.buildings().at(x, y));
    }

    static List<BuildEvent.ConstructFinish> finishes(Observer events){
        var out = new ArrayList<BuildEvent.ConstructFinish>();
        for(WorldEvent event; (event = events.poll()) != null; ){
            if(event instanceof BuildEvent.ConstructFinish finish) out.add(finish);
        }
        return out;
    }

    /** One link whose name vanilla reads ({@code readUTF}) and Shard's {@link LogicConfig#MAX_NAME_BYTES} refuses. */
    static byte[] longNamed(){
        return LogicConfig.compress("set a 2", List.of(new LogicConfig.Link("n".repeat(LogicConfig.MAX_NAME_BYTES + 1), 1, 0)));
    }

    @Test
    void aProcessorCodeTheBoundsRefuseIsNeitherAppliedNorSent(){
        byte[] refused = longNamed(), accepted = LogicConfig.compress("set a 3", List.of());
        assertNull(LogicConfig.decode(refused), "Shard refuses it");
        Observer events = world.observe(64, List.of(BuildEvent.class), null);
        Unit unit = builder(30, 30);
        Builder.addBuild(unit, BuildPlan.place(28, 30, 0, MICRO, refused), true);
        Builder.addBuild(unit, BuildPlan.place(32, 30, 0, MICRO, accepted), true);
        for(int tick = 0; tick < 10; tick++) world.tick();

        assertEquals("", processor(28, 30).processor().code(), "built, unconfigured");
        assertEquals(0, processor(28, 30).processor().linkCount());
        assertEquals("set a 3", processor(32, 30).processor().code());
        List<BuildEvent.ConstructFinish> sent = finishes(events);
        assertEquals(2, sent.size(), "one constructFinish each");
        assertNull(sent.get(0).config(), "what the server applied: nothing");
        assertArrayEquals(accepted, assertInstanceOf(ConfigValue.Bytes.class, sent.get(1).config()).bytes(), "what the server applied: the plan's own bytes");
    }

    @Test
    void theBlockLevelCheckIsTheBuildingsOwn(){
        Building standing = world.buildings().setBlock(10, 10, MICRO, Team.SHARDED, 0, Cause.SERVER);
        for(Object config : new Object[]{longNamed(), LogicConfig.compress("set a 3", List.of()), 5, "tag", null}){
            assertEquals(standing.admitConfig(config), Build.admitConfig(world, MICRO, config), String.valueOf(config));
        }
        assertNull(Build.admitConfig(world, VANILLA.block("sorter"), longNamed()), "only processors bound their configs");
        assertNull(Build.admitConfig(world, VANILLA.block("world-processor"), longNamed()), "a world processor reads none");
    }

    /** About 240 kB inflated: the most code vanilla reads, and 2,000 links with names at Shard's longest. */
    static byte[] nearLimit(int n){
        var links = new ArrayList<LogicConfig.Link>();
        for(int i = 0; i < 2_000; i++) links.add(new LogicConfig.Link(String.format("%064d", i), 1 + i, 0));
        String code = "set a " + n + "\n#" + "x".repeat(LogicConfig.maxByteLen - 16);
        return LogicConfig.compress(code, links);
    }

    @Test
    void manyProcessorsFinishingInOneTickShareOneBudget(){
        int count = 128, side = 12;
        byte[] config = nearLimit(1);
        int size = LogicConfig.measure(config, LogicConfig.MAX_INFLATED);
        assertTrue(size > 230_000 && size < LogicConfig.MAX_INFLATED, size + " bytes");
        int fits = LogicPart.CONFIG_BYTES_PER_TICK / size;
        assertEquals(4, fits);
        Observer events = world.observe(256, List.of(BuildEvent.class), null);
        var plans = new ArrayList<int[]>();
        for(int i = 0; i < count; i++){
            int x = 26 + i % side, y = 26 + i / side;
            Unit unit = builder(32, 32);
            Builder.addBuild(unit, BuildPlan.place(x, y, 0, MICRO, config), true);
            plans.add(new int[]{x, y});
        }
        long tick = world.ticks();
        world.tick();

        int configured = 0;
        for(int[] plan : plans) if(!processor(plan[0], plan[1]).processor().code().isEmpty()) configured++;
        assertEquals(fits, configured, "the tick's budget's worth");
        List<BuildEvent.ConstructFinish> sent = finishes(events);
        assertEquals(count, sent.size(), "every processor finished in the tick");
        assertEquals(fits, sent.stream().filter(finish -> finish.config() != null).count(), "the rest carry null");
        // the budget's counters for the tick: unbounded, 128 codes of 240 kB went through no budget at all
        LogicPart logic = LogicPart.require(world);
        assertEquals(tick, logic.configTick());
        assertEquals(fits, logic.configsAdmitted());
        assertTrue(logic.configBytes() <= LogicPart.CONFIG_BYTES_PER_TICK, logic.configBytes() + " bytes inflated");

        // the next tick has its own budget: a processor built in it gets its code
        Unit late = builder(32, 32);
        Builder.addBuild(late, BuildPlan.place(26, 40, 0, MICRO, nearLimit(2)), true);
        world.tick();
        assertTrue(processor(26, 40).processor().code().startsWith("set a 2"));
    }
}
