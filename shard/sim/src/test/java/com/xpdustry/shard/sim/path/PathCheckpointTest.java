package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.sim.path.PathWorlds.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Forks and checkpoints of the path service (ARCHITECTURE.md §9): a fork, and a world restored from
 * {@link PathService#write} (alone, or as a world checkpoint's {@code paths} section), equal the source in every
 * array, derived ones included, and continue identically. The
 * churn leaves tile changes, direct {@code tileChanged} calls, rule changes and unsampled damage pending at every
 * boundary, and RTS fields mid-build.
 */
class PathCheckpointTest{
    static World churned(long seed, int ticks){
        World world = world(56, 56, seed, systems(List.of(), new Orders.Handler()).with(Orders.class, Orders::new));
        Churn.generate(world);
        PathService paths = paths(world);
        paths.rules(PathRules.DEFAULT.withWaves(true).withThreatPaths(true).withRtsPopBudget(900));
        paths.invalidateAll();
        for(int team = 1; team <= 3; team++){
            for(PathCost cls : PathCost.values()) paths.field(team, cls);
        }
        world.part(Orders.class).enabled = true;
        for(int i = 0; i < ticks; i++) world.tick();
        return world;
    }

    @Test
    void aForkContinuesIdentically(){
        World source = churned(31, 90);
        World fork = source.fork();
        same(source, fork, "at the fork");
        for(int i = 0; i < 200; i++){
            source.tick();
            fork.tick();
            hashes(source, fork, "tick " + source.ticks());
            if(i % 40 == 0) same(source, fork, "tick " + source.ticks());
        }
        same(source, fork, "at the end");
    }

    @Test
    void aRestoredCheckpointRebuildsTheSameFieldsAndContinuesIdentically(){
        World world = churned(32, 40);
        PathService paths = paths(world);
        int building = 0, bytes = 0;
        for(int round = 0; round < 5; round++){
            for(int i = 0; i < 37; i++) world.tick();
            // more pending state at the boundary: a rule change, a queued tile, damage the view hasn't sampled
            paths.rules(paths.rules().withWave(round + 2));
            paths.tileChanged(world.rand().random(world.tiles().size() - 1));
            List<Building> all = buildings(world);
            all.get(world.rand().random(all.size() - 1)).health *= 0.5f;
            for(Flowfield field : paths.slots()) if(field != null && field.state == Flowfield.State.BUILDING) building++;

            var out = new Writes();
            paths.write(world, out);
            bytes = Math.max(bytes, out.size());
            World restored = world.fork();
            var reads = new Reads(out.toByteArray());
            restored.part(PathService.class).read(restored, reads);
            assertEquals(0, reads.remaining(), "round " + round + ": unread checkpoint bytes");
            String where = "round " + round;
            same(world, restored, where + " restored");
            hashes(world, restored, where + " restored");

            World fork = world.fork();
            for(int i = 0; i < 45; i++){
                fork.tick();
                restored.tick();
                hashes(fork, restored, where + " +" + (i + 1));
            }
            same(fork, restored, where + " after 45 ticks");
        }
        assertTrue(building > 0, "no RTS field was mid-build at a checkpoint");
        assertTrue(bytes < 64 * 1024, "the checkpoint holds derived state: " + bytes + " bytes");
    }

    @Test
    void aWorldCheckpointCarriesThePathsSection(){
        World world = churned(33, 70);
        // the boundary must fall on a tick whose tile changes are still pending
        for(int i = 0; i < 200 && world.tiles().pendingChanges() == 0; i++) world.tick();
        PathService paths = paths(world);
        paths.rules(paths.rules().withWave(4));
        paths.tileChanged(world.rand().random(world.tiles().size() - 1));
        assertTrue(world.tiles().pendingChanges() > 0, "the boundary has tile changes pending");
        Capture capture = Checkpoint.capture(world);
        assertTrue(capture.sectionSizes().containsKey("paths"), capture.sectionSizes().toString());
        World restored = Checkpoint.decode(capture.encode(Compression.FAST)).restore(world.config());
        same(world, restored, "restored");
        hashes(world, restored, "restored");
        for(int i = 0; i < 90; i++){
            world.tick();
            restored.tick();
            hashes(world, restored, "+" + (i + 1));
        }
        same(world, restored, "after 90 ticks");
    }

    @Test
    void anInactiveServiceIsDerivedAndStoresNothing(){
        World world = new World(WorldConfig.of(com.xpdustry.shard.sim.toy.ToyContent.INSTANCE, 16, 16, 35)
            .withSystems(systems(List.of())));
        world.tick();
        assertFalse(paths(world).active());
        assertEquals(Persistence.DERIVED, paths(world).persistence());
        Capture capture = Checkpoint.capture(world);
        assertFalse(capture.sectionSizes().containsKey("paths"), capture.sectionSizes().toString());
        World restored = Checkpoint.decode(capture.encode(Compression.FAST)).restore(world.config());
        assertFalse(paths(restored).active());
        hashes(world, restored, "restored");
    }

    static void hashes(World a, World b, String where){
        WorldHash ha = a.hash(), hb = b.hash();
        assertEquals(ha, hb, where + ": " + ha.firstDifference(hb).orElse("?"));
    }

    /** Every array of both services, derived ones included. */
    static void same(World a, World b, String where){
        PathService pa = paths(a), pb = paths(b);
        assertEquals(pa.rules(), pb.rules(), where + ": rules");
        PathGrid ga = pa.grid(), gb = pb.grid();
        assertArrayEquals(ga.view, gb.view, where + ": view");
        assertArrayEquals(ga.tiles, gb.tiles, where + ": packed tiles");
        Threat ta = pa.threat(), tb = pb.threat();
        assertEquals(ta == null, tb == null, where + ": threat layer");
        if(ta != null){
            for(int team = 0; team < 4; team++){
                for(int pos = 0; pos < ga.size; pos++) assertEquals(ta.at(team, pos), tb.at(team, pos), where + ": threat of " + team + " at " + pos);
            }
        }
        assertEquals(pa.slots().size(), pb.slots().size(), where + ": slots");
        for(int s = 0; s < pa.slots().size(); s++){
            Flowfield fa = pa.slots().get(s), fb = pb.slots().get(s);
            String field = where + ": field " + s;
            assertEquals(fa == null, fb == null, field);
            if(fa == null) continue;
            assertEquals(fa.key(), fb.key(), field + " key");
            assertEquals(fa.state, fb.state, field + " state");
            assertEquals(fa.pops, fb.pops, field + " pops");
            assertEquals(fa.frontierKey, fb.frontierKey, field + " frontier");
            assertEquals(fa.refs, fb.refs, field + " refs");
            assertEquals(fa.released, fb.released, field + " released");
            assertEquals(fa.targets, fb.targets, field + " targets");
            assertEquals(fa.large, fb.large, field + " clearance");
            assertEquals(fa.threat, fb.threat, field + " threat");
            assertArrayEquals(fa.dist, fb.dist, field + " distances");
            assertArrayEquals(fa.parent, fb.parent, field + " parents");
        }
    }
}
