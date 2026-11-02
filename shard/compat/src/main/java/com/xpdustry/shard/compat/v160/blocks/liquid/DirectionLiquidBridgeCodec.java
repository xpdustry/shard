package com.xpdustry.shard.compat.v160.blocks.liquid;

import com.xpdustry.shard.common.registry.*;

/**
 * The reinforced bridge conduit's save bytes: none ({@code DirectionLiquidBridge.DuctBridgeBuild} and its
 * {@code DirectionBridgeBuild} have no {@code write}).
 */
@Register
public final class DirectionLiquidBridgeCodec extends NoBodyCodec{
    public DirectionLiquidBridgeCodec(){
        super("direction-liquid-bridge");
    }
}
