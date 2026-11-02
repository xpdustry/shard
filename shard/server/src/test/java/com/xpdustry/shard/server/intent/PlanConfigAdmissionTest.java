package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 40: a snapshot plan's processor code is admitted as a direct config is, through the same bounds and the same
 * tick's {@link LogicPart#CONFIG_BYTES_PER_TICK}. A code it refuses is dropped from the queued plan, which keeps the
 * rest; a config of another block costs nothing.
 */
class PlanConfigAdmissionTest{
    static final long ALPHA = 7;

    IntentWorld w;
    Player player;
    Unit unit;
    Building processor;
    int snapshots;

    @BeforeEach
    void world(){
        w = new IntentWorld();
        player = w.joinWith(ALPHA, "alpha", "alpha", 100f, 100f);
        unit = player.unit();
        processor = w.place("micro-processor", 14, 12, player.team, 0);
        w.world.tick();
        w.drain();
    }

    /** A snapshot that queues {@code plans} and doesn't build them. */
    Snapshot snapshot(List<BuildPlan> plans){
        return new Snapshot(Policy.VANILLA, snapshots++, unit.id(), false, unit.x, unit.y, 0, 0, 0, 0, 0, 0, -1, false, false, false, false, -1,
            0, plans, unit.x, unit.y, 400, 300);
    }

    static BuildPlan micro(int x, byte[] config){
        return BuildPlan.place(x, 20, 0, IntentWorld.VANILLA.block("micro-processor").id(), config);
    }

    /** One link whose name vanilla reads ({@code readUTF}) and Shard's {@link LogicConfig#MAX_NAME_BYTES} refuses. */
    static byte[] longNamed(){
        return LogicConfig.compress("set a 2", List.of(new LogicConfig.Link("n".repeat(LogicConfig.MAX_NAME_BYTES + 1), 1, 0)));
    }

    @Test
    void aPlansCodeIsChargedToTheTicksBudgetAndDroppedPastIt(){
        // the most code vanilla reads: ten fit in a tick
        byte[] large = LogicConfig.compress("set a 3\n#" + "x".repeat(LogicConfig.maxByteLen - 9), List.of());
        int fits = LogicPart.CONFIG_BYTES_PER_TICK / LogicConfig.measure(large, LogicConfig.MAX_INFLATED);
        assertEquals(10, fits);
        var plans = new ArrayList<BuildPlan>();
        for(int i = 0; i < 12; i++) plans.add(micro(4 + i, large));
        // refused whatever the budget left; checking first, it would spend all it could have read
        plans.add(micro(2, longNamed()));
        var copper = ContentRef.item(IntentWorld.item("copper").id());
        plans.add(BuildPlan.place(30, 20, 0, IntentWorld.VANILLA.block("sorter").id(), copper));

        // the snapshot, then a direct config in the same tick
        w.offer(ALPHA, snapshot(plans), 1_000);
        byte[] direct = ProcessorConfigBoundsTest.object(LogicConfig.compress("set a 4", List.of()));
        w.offer(ALPHA, new BlockIntents.TileConfig(ProcessorConfigBoundsTest.NO_RATE_LIMIT, processor.pos(), direct), 1_000);
        int logged = w.world.log().size();
        w.world.tick();
        assertEquals(Verdict.ADMITTED, w.world.log().get(logged).verdict());
        assertEquals(Verdict.rejected(ProcessorConfigBoundsTest.REFUSED), w.world.log().get(logged + 1).verdict(),
            "the plans spent the tick's budget");

        assertEquals(plans.size(), unit.plans.size(), "every plan is queued");
        for(int i = 0; i < 12; i++){
            if(i < fits) assertArrayEquals(large, (byte[])unit.plans.get(i).config, "plan " + i);
            else assertNull(unit.plans.get(i).config, "plan " + i + ": past the tick's budget");
        }
        assertNull(unit.plans.get(12).config, "past Shard's bounds: dropped");
        assertEquals(copper, unit.plans.get(13).config, "a sorter's item costs nothing");
        assertNotSame(plans.getFirst().config, unit.plans.first().config, "the queue holds a copy");

        // the next tick has its own budget
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, snapshot(List.of(micro(4, large))), 1_066));
        assertArrayEquals(large, (byte[])unit.plans.first().config);
    }
}
