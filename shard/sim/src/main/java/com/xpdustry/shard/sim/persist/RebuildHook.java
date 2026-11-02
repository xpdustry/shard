package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Rebuilds derived state that belongs to no single object or part after a restore: derived attachments, indexes
 * kept outside parts. Register it with {@code @Register}. Hooks run last, after derived parts and
 * {@link Checkpointable#restored()}, in ({@link #order()}, class name) order. Like derived parts, a hook must rebuild
 * exactly the state a fork at the same tick boundary would hold.
 */
@Registry
public interface RebuildHook{
    /** Lower runs first; ties break by class name. */
    default int order(){
        return 0;
    }

    void rebuild(World world);
}
