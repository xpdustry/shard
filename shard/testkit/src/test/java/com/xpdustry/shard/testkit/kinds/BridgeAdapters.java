package com.xpdustry.shard.testkit.kinds;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.testkit.*;

/**
 * {@link KindAdapter}s for the bridge and mass driver kinds (W3.1): their public fields as reflected, renamed to
 * vanilla's where Shard renames them. A bridge conveyor's buffer is a private {@code ItemBuffer} in vanilla, so it isn't
 * projected; a mass driver's turret angle is vanilla's {@code rotation}, and its waiting shooters, an
 * {@code OrderedSet}, are left out.
 */
public final class BridgeAdapters{
    private BridgeAdapters(){
    }

    @Register
    public static final class BufferedItemBridgeAdapter implements KindAdapter<BufferedItemBridge>{
        @Override
        public KindKey kind(){
            return KindKey.of("buffered-item-bridge");
        }

        @Override
        public void fields(BufferedItemBridge building, Fields out){
            out.reflect(building).remove("buffer").remove("bufferIndex");
        }
    }

    @Register
    public static final class MassDriverAdapter implements KindAdapter<MassDriver>{
        @Override
        public KindKey kind(){
            return KindKey.of("mass-driver");
        }

        @Override
        public void fields(MassDriver building, Fields out){
            out.reflect(building).remove("driverRotation").put("rotation", building.driverRotation).remove("waitingShooters");
        }
    }
}
