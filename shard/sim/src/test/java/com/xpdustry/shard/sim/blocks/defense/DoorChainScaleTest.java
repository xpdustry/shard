package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.lang.management.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 41: a chain of doors costs its length in checkpoints, forks and hashes, not its square. One connected chain of
 * 100, 1,000 and 5,000 doors against as many copper walls: the doors' extra checkpoint bytes and restore and fork
 * allocation per door stay flat, and the times are printed beside the walls'.
 */
class DoorChainScaleTest{
    static final ContentEpoch VANILLA = VanillaWorlds.VANILLA;
    static final int WIDTH = 100;

    record Cost(long bytes, long captureNs, long restoreBytes, long restoreNs, long forkBytes, long forkNs, long hashNs){
        Cost minus(Cost other){
            return new Cost(bytes - other.bytes, captureNs - other.captureNs, restoreBytes - other.restoreBytes,
                restoreNs - other.restoreNs, forkBytes - other.forkBytes, forkNs - other.forkNs, hashNs - other.hashNs);
        }
    }

    static World filled(String block, int count){
        World world = UnitWorlds.world(WIDTH + 4, count / WIDTH + 5, 3);
        var def = VANILLA.block(block);
        for(int i = 0; i < count; i++) world.buildings().setBlock(2 + i % WIDTH, 2 + i / WIDTH, def, Team.SHARDED, 0, Cause.SERVER);
        return world;
    }

    static long allocated(){
        return ((com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    /** The cheapest of a few runs of each step, so a JIT pause or a collection doesn't count. */
    static Cost measure(World world){
        long bytes = 0, capture = Long.MAX_VALUE, restoreBytes = Long.MAX_VALUE, restore = Long.MAX_VALUE;
        long forkBytes = Long.MAX_VALUE, fork = Long.MAX_VALUE, hash = Long.MAX_VALUE;
        for(int run = 0; run < 3; run++){
            long t = System.nanoTime();
            byte[] encoded = Checkpoint.capture(world).encode(Compression.NONE);
            capture = Math.min(capture, System.nanoTime() - t);
            bytes = encoded.length;
            long a = allocated();
            t = System.nanoTime();
            World restored = Checkpoint.decode(encoded).restore(world.config());
            restore = Math.min(restore, System.nanoTime() - t);
            restoreBytes = Math.min(restoreBytes, allocated() - a);
            a = allocated();
            t = System.nanoTime();
            World forked = world.fork();
            fork = Math.min(fork, System.nanoTime() - t);
            forkBytes = Math.min(forkBytes, allocated() - a);
            t = System.nanoTime();
            world.hash();
            hash = Math.min(hash, System.nanoTime() - t);
            assertEquals(world.hash(), restored.hash());
            assertEquals(world.hash(), forked.hash());
        }
        return new Cost(bytes, capture, restoreBytes, restore, forkBytes, fork, hash);
    }

    @Test
    void chainsCostTheirLength(){
        var perDoor = new TreeMap<Integer, Cost>();
        for(int count : new int[]{100, 1_000, 5_000}){
            World doors = filled("door", count);
            var origin = (Door)doors.buildings().at(2, 2);
            assertEquals(count, origin.chained().length, "one chain");
            Cost door = measure(doors), wall = measure(filled("copper-wall", count));
            Cost extra = door.minus(wall);
            perDoor.put(count, extra);
            System.out.printf("%,d doors: checkpoint %,d bytes (walls %,d), capture %.2f ms (%.2f), restore %.2f ms %,d B (%.2f ms %,d B),"
                    + " fork %.2f ms %,d B (%.2f ms %,d B), hash %.2f ms (%.2f)%n", count,
                door.bytes, wall.bytes, door.captureNs / 1e6, wall.captureNs / 1e6, door.restoreNs / 1e6, door.restoreBytes,
                wall.restoreNs / 1e6, wall.restoreBytes, door.forkNs / 1e6, door.forkBytes, wall.forkNs / 1e6, wall.forkBytes,
                door.hashNs / 1e6, wall.hashNs / 1e6);
        }
        Cost small = perDoor.get(100), large = perDoor.get(5_000);
        // a chain stored per door made the 5,000-door chain's extra 50 times the 100-door one's, per door
        assertTrue(large.bytes / 5_000.0 <= small.bytes / 100.0 * 1.5 + 8, "checkpoint bytes per door: " + perDoor);
        assertTrue(large.restoreBytes / 5_000.0 <= Math.max(small.restoreBytes, 0) / 100.0 * 3 + 256, "restore allocation per door: " + perDoor);
        assertTrue(large.forkBytes / 5_000.0 <= Math.max(small.forkBytes, 0) / 100.0 * 3 + 256, "fork allocation per door: " + perDoor);
    }
}
