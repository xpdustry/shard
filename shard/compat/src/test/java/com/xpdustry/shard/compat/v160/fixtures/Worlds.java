package com.xpdustry.shard.compat.v160.fixtures;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.*;

/** Vanilla v160.7 worlds as the msav and stream tests make them; bundled map files are testkit's {@link BundledMaps}. */
public final class Worlds{
    public static final Epoch EPOCH = Epoch.vanilla();

    private Worlds(){
    }

    /** A template over {@link #EPOCH} with the built-in systems and kinds, every unported kind passed through. */
    public static WorldConfig config(long seed){
        return WorldConfig.of(EPOCH, 1, 1, seed).withKinds(KindRegistry.builtIn().withPassthrough());
    }
}
