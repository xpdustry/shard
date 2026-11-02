package com.xpdustry.shard.tools.extract;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replaying the bundle of a vanilla world, a bundled map as the server hosts it: the registered
 * {@link VanillaContentSource} finds the vanilla pack's epoch from the checkpoint's content hash alone. The map holds
 * kinds Shard hasn't ported, so the restored world passes them through, as {@link ShardMaps} loaded it.
 */
class VanillaReplayTest{
    @Test
    void aBundledMapReplays(){
        World world = ShardMaps.vanilla().load("serpulo/groundZero", WorldConfig.of(Epoch.vanilla(), 1, 1, 7));
        for(int i = 0; i < 30; i++) world.tick();
        byte[] checkpoint = Checkpoint.capture(world).encode(Compression.FAST);
        var marks = new ArrayList<CrashBundle.Mark>();
        for(int i = 0; i < 120; i++){
            world.tick();
            if(world.ticks() % 30 == 0) marks.add(new CrashBundle.Mark(world.ticks(), world.hash()));
        }
        long last = world.ticks();
        //the world never failed: the bundle records a failure at the next tick, and the replay stops before it
        var bundle = CrashBundle.decode(CrashBundle.of(checkpoint, world, marks, last + 1, new IllegalStateException("not reached"), new TreeMap<>()).encode());

        ContentEpoch content = ContentSource.resolve(Checkpoint.header(bundle.checkpoint()).content());
        assertSame(Epoch.vanilla(), content);
        WorldConfig config = Checkpoint.decode(bundle.checkpoint()).config(content);
        config = config.withKinds(config.kinds().withPassthrough());
        var out = new ArrayList<String>();
        Replay.Result result = Replay.run(bundle, config, new Replay.Options(last, 30, false), out::add);
        assertTrue(result.ok() && !result.reproduced(), result + "\n" + out);
        assertEquals(last, result.to());
        assertEquals(marks.size(), result.hashes(), out.toString());
    }
}
