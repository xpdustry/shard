package com.xpdustry.shard.server.host;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * What a hosted world runs: every registered handler and part, plus the server's {@link Roster} and
 * {@link ActionRules}, with blocks Shard
 * doesn't model yet passing through as their file bytes ({@link KindRegistry#withPassthrough}).
 */
public final class HostedSystems{
    public static final Systems SYSTEMS = systems();
    public static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();

    /** A hosted world's config on {@code epoch}'s content; a map load resizes it to the map. */
    public static WorldConfig template(Epoch epoch, long seed, int inboxCapacity){
        return WorldConfig.of(epoch, 1, 1, seed).withSystems(SYSTEMS).withKinds(KINDS).withInboxCapacity(inboxCapacity);
    }

    /**
     * The config a checkpoint was written with, if it was a hosted world's or a plain one's: {@code shard replay}
     * reads both. Null if neither matches the checkpoint's systems id.
     */
    public static WorldConfig matching(WorldConfig stored, long systemsId){
        for(WorldConfig candidate : List.of(stored.withSystems(SYSTEMS).withKinds(KINDS), stored, stored.withKinds(KINDS))){
            if(Checkpoint.systemsId(candidate) == systemsId) return candidate;
        }
        return null;
    }

    private static Systems systems(){
        return Systems.builtIn().with(Roster.class, Roster::new).with(ActionRules.class, ActionRules::new);
    }

    private HostedSystems(){}
}
