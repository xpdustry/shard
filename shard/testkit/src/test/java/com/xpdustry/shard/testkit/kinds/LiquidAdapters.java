package com.xpdustry.shard.testkit.kinds;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.testkit.*;

/**
 * {@link KindAdapter}s for the liquid kinds (W3.4) whose fields don't project as vanilla's: the liquid source holds
 * its liquid as an id where vanilla holds a {@code Liquid}, and takes liquid configs. The other liquid kinds project
 * their public fields by vanilla's names as they are.
 */
public final class LiquidAdapters{
    private LiquidAdapters(){
    }

    @Register
    public static final class LiquidSourceAdapter implements KindAdapter<LiquidSource>{
        @Override
        public KindKey kind(){
            return KindKey.of("liquid-source");
        }

        @Override
        public void fields(LiquidSource building, Fields out){
            out.reflect(building).put("source", building.source < 0 ? null : out.names().liquidName(building.source));
        }

        @Override
        public void configure(LiquidSource building, Config config, ShardNames names){
            switch(config){
                case Config.None none -> building.configureAny(null);
                case Config.Content(String type, String name) when type.equals("liquid") -> building.configureAny(ContentRef.liquid(names.liquid(name)));
                default -> throw new UnsupportedOperationException(building.block().name() + " takes a liquid config, not " + config);
            }
        }
    }
}
