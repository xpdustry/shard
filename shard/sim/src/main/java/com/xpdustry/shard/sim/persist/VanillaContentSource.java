package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;

/**
 * The vanilla pack's epoch ({@link Epoch#vanilla()}), so {@code shard replay} reads the bundles of vanilla worlds. The
 * epoch is built on the first lookup that reaches this source.
 */
@Register
public final class VanillaContentSource implements ContentSource{
    @Override
    public ContentEpoch find(String hash){
        Epoch vanilla = Epoch.vanilla();
        return vanilla.hash().equals(hash) ? vanilla : null;
    }
}
