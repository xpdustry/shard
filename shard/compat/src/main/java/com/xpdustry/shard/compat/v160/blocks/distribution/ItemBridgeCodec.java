package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.building.*;

/** The phase conveyor's save bytes: {@link ItemBridgeBodies}, revision 1 ({@code ItemBridgeBuild.version()}), reading 0 too. */
@Register
public final class ItemBridgeCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("item-bridge");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 1;
    }

    @Override
    public boolean reads(int revision){
        return revision == 0 || revision == 1;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        ItemBridgeBodies.write((ItemBridgeBuild)building, out);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        ItemBridgeBodies.read((ItemBridgeBuild)building, in, revision);
    }

    @Override
    public void skip(Tape in, int revision){
        ItemBridgeBodies.skip(in, revision);
    }
}
