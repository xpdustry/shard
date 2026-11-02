package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 41: N mass-driver bolts in flight cost O(N) payload work a frame, not O(N²). N bolts fired at once from one
 * driver at another fly 320 units and are caught together; {@link DriverBolts#visits()} counts every payload entry a
 * lookup, removal or compaction touches. A linear scan (the old list) visited about N²/2 entries a frame.
 */
class DriverBoltsCostTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;

    record Flight(long midFrameVisits, long totalVisits, int frames){
    }

    static Flight fire(int count){
        World world = VanillaWorlds.world(64, 24, 1);
        Buildings buildings = world.buildings();
        var from = (MassDriver)buildings.setBlock(5, 10, BLOCKS.block("mass-driver"), 1, 0, Cause.SERVER);
        var to = (MassDriver)buildings.setBlock(45, 10, BLOCKS.block("mass-driver"), 1, 0, Cause.SERVER);
        BulletType bolt = world.bullets().types(world).get(BLOCKS.bullet("mass-driver/bullet").id());
        DriverBolts bolts = DriverBolts.of(world);
        float angle = Angles.angle(from.x(), from.y(), to.x(), to.y());
        for(int i = 0; i < count; i++){
            Bullet bullet = bolt.create(world, from, from.team, from.x() + Angles.trnsx(angle, 7f), from.y() + Angles.trnsy(angle, 7f),
                angle, 0f, 5.5f, 200f);
            bolts.attach(bullet, from, to, new int[world.content().items()]);
        }
        assertEquals(count, bolts.size());
        long start = bolts.visits(), mid = 0;
        int frames = 0;
        while(bolts.size() > 0 && frames < 300){
            long before = bolts.visits();
            world.tick();
            frames++;
            if(frames == 20) mid = bolts.visits() - before;
        }
        assertEquals(0, bolts.size(), "every bolt was caught");
        assertEquals(0, world.bullets().size(), "and removed");
        return new Flight(mid, bolts.visits() - start, frames);
    }

    @Test
    void payloadWorkIsLinearInTheBoltsInFlight(){
        for(int count : new int[]{10, 100, 1000}){
            long nanos = System.nanoTime();
            Flight flight = fire(count);
            nanos = System.nanoTime() - nanos;
            System.out.printf("%d bolts: %d visits in frame 20, %d over %d frames, %.1f ms%n", count, flight.midFrameVisits(),
                flight.totalVisits(), flight.frames(), nanos / 1e6);
            assertEquals(count, flight.midFrameVisits(), "one lookup per bolt per frame in flight");
            // a lookup each frame, then the catch's lookups and removal (a few each), then compaction (at most 2N)
            assertTrue(flight.totalVisits() <= (long)count * (flight.frames() + 8), count + " bolts: " + flight.totalVisits() + " visits");
        }
    }
}
