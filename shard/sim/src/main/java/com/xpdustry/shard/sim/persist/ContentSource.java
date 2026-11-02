package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;

/**
 * Finds a content epoch by its hash, so a tool that only has a checkpoint (the {@code shard replay} reader) can
 * rebuild the world's config. Register it with {@code @Register}; WP10 registers the vanilla pack's.
 */
@Registry
public interface ContentSource{
    /** The epoch whose {@link ContentEpoch#hash()} is {@code hash}, or null if this source doesn't have it. */
    ContentEpoch find(String hash);

    /**
     * Makes {@code epoch}, made at runtime (a map's patched content), resolvable by its hash in this process
     * ({@link RememberedContentSource}, which holds it only while something else does). Safe from any thread;
     * remembering the same hash again keeps the first.
     *
     * @throws IllegalArgumentException if {@code epoch} is not a patched {@code Epoch}
     */
    static void remember(ContentEpoch epoch){
        RememberedContentSource.remember(epoch);
    }

    /** The epoch from the first registered source (by class name) that has it. */
    static ContentEpoch resolve(String hash){
        for(ContentSource source : Registries.instances(ContentSource.class)){
            ContentEpoch epoch = source.find(hash);
            if(epoch != null) return epoch;
        }
        throw new CheckpointException("no registered ContentSource has content " + hash
            + ": a pack epoch this build lacks, or a map's patched content this process hasn't loaded (load the map first)");
    }
}
