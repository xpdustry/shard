package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.patch.*;
import com.xpdustry.shard.content.patch.DataAsset.*;
import org.junit.jupiter.api.*;

import java.lang.ref.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W6.2: a map's patched content resolves by hash once remembered, and only that hash; vanilla's stays vanilla's; a hash
 * nothing remembers fails naming it, as in a fresh process; and remembering is bounded.
 */
class RememberedContentTest{
    static Epoch patched(String text){
        return DataPatcher.apply(Epoch.vanilla(), List.of(new PatchAsset("patch.json", text)), "serpulo").epoch();
    }

    @Test
    void aRememberedPatchResolvesByItsOwnHashOnly(){
        Epoch fast = patched("{\"block.conveyor.speed\": 0.081}"), tough = patched("{\"block.conveyor.health\": 501}");
        assertNotEquals(fast.hash(), tough.hash());
        ContentSource.remember(fast);
        assertSame(fast, ContentSource.resolve(fast.hash()));
        var missing = assertThrows(CheckpointException.class, () -> ContentSource.resolve(tough.hash()),
            "another world's patch is not this one");
        assertTrue(missing.getMessage().contains(tough.hash()), "the error names the missing hash: " + missing.getMessage());
    }

    @Test
    void vanillasEpochStaysVanillas(){
        Epoch fast = patched("{\"block.conveyor.speed\": 0.082}");
        ContentSource.remember(fast);
        ContentEpoch vanilla = ContentSource.resolve(Epoch.vanilla().hash());
        assertSame(Epoch.vanilla(), vanilla);
        assertEquals(0.035f, Epoch.vanilla().block("conveyor").fields().f("speed"));
        assertThrows(IllegalArgumentException.class, () -> ContentSource.remember(Epoch.vanilla()), "a pack epoch is never remembered");
    }

    /** A process that never loaded the map: nothing remembered for its hash, so resolve fails with the hash. */
    @Test
    void aPatchNoOneRememberedFailsNamingItsHash(){
        Epoch unseen = patched("{\"block.conveyor.speed\": 0.083}");
        var e = assertThrows(CheckpointException.class, () -> ContentSource.resolve(unseen.hash()));
        assertTrue(e.getMessage().contains(unseen.hash()) && e.getMessage().contains("patched"), e.getMessage());
    }

    @Test
    void theSameMapAgainAddsNothingAndADroppedEpochGoes() throws InterruptedException{
        Epoch first = patched("{\"block.conveyor.speed\": 0.084}");
        ContentSource.remember(first);
        int size = RememberedContentSource.size();
        Epoch again = patched("{\"block.conveyor.speed\": 0.084}");
        assertEquals(first.hash(), again.hash(), "the same patch makes the same hash");
        ContentSource.remember(again);
        assertEquals(size, RememberedContentSource.size(), "reloading the same map adds nothing");
        assertSame(first, ContentSource.resolve(again.hash()), "and keeps the first");

        // dropped by every world: the epoch is collected and its entry goes on the next remember
        var dropped = new WeakReference<>(patched("{\"block.conveyor.speed\": 0.085}"));
        String hash = dropped.get().hash();
        ContentSource.remember(dropped.get());
        int gcs = 0;
        for(; dropped.get() != null && gcs < 10_000; gcs++){
            System.gc();
            Thread.sleep(1);
        }
        assertNull(dropped.get(), "collected after " + gcs + " GCs");
        assertThrows(CheckpointException.class, () -> ContentSource.resolve(hash));
        int before = RememberedContentSource.size();
        Epoch keep = patched("{\"block.conveyor.speed\": 0.086}");
        ContentSource.remember(keep);
        assertTrue(RememberedContentSource.size() <= before, "the stale entry made room: " + before + " -> " + RememberedContentSource.size());
        assertSame(keep, ContentSource.resolve(keep.hash()));
    }
}
