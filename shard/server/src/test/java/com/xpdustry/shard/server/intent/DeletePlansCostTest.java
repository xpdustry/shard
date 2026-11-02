package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 40: a {@code deletePlans} packet costs its positions plus the team's rebuild plans, counted by
 * {@link Construction#deleteVisits}, however long the history and whatever the positions: none matching, one tile
 * repeated, or real tiles. Vanilla's handler checks every plan against every position, 164 million for one such packet
 * against 20,000 plans. The wall-clock version, on the real server beside a second world, is
 * {@code DeletePlansFloodServerTest} (slow).
 */
class DeletePlansCostTest{
    static final long ALPHA = 7;
    static final int PLANS = 20_000;

    IntentWorld w;
    List<BlockPlan> plans;
    long millis = 1_000;

    @BeforeEach
    void world(){
        w = new IntentWorld();
        Player player = w.joinWith(ALPHA, "alpha", "alpha", 100f, 100f);
        plans = Teams.of(w.world).get(player.team).plans();
        int block = IntentWorld.VANILLA.block("conveyor").id();
        for(int i = 0; i < PLANS; i++) plans.add(new BlockPlan(i % 200, i / 200, 0, block, null));
    }

    /** Admits one packet in its own tick and returns what it visited. */
    long visits(int[] positions){
        long tick = w.world.ticks();
        millis += 100;
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new PlayerIntents.DeletePlans(ProcessorConfigBoundsTest.NO_RATE_LIMIT, positions), millis));
        Construction construction = w.world.find(Construction.class);
        assertEquals(tick, construction.deleteTick(), "counted in the tick that admitted it");
        return construction.deleteVisits();
    }

    @Test
    void aPacketVisitsItsPositionsAndThePlansOnce(){
        int[] missing = new int[DeletePlansFloodServerTest.MAX_POSITIONS], repeated = new int[missing.length];
        for(int i = 0; i < missing.length; i++) missing[i] = Point2.pack(300 + i % 64, 300);
        Arrays.fill(repeated, Point2.pack(400, 400));
        for(int[] positions : List.of(missing, repeated)){
            int before = plans.size();
            long visited = visits(positions);
            assertEquals(positions.length + before, visited, "the positions indexed, each plan checked once");
            assertEquals(PLANS, plans.size(), "nothing matched");
        }

        // real tiles: the first row of plans, in a packet that names each twice
        int[] row = new int[400];
        for(int i = 0; i < row.length; i++) row[i] = Point2.pack(i % 200, 0);
        BlockPlan next = plans.get(200);
        assertEquals(row.length + PLANS, visits(row));
        assertEquals(PLANS - 200, plans.size());
        assertSame(next, plans.getFirst(), "the rest in order");
    }
}
