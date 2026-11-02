package com.xpdustry.shard.compat.v160.blocks.storage;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/**
 * The container's and vault's save bytes: none. {@code StorageBuild} has no {@code write}, so the base's item module is
 * all a save keeps, which for linked storage is its core's.
 */
@Register
public final class StorageCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("storage-block");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 0;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
    }

    @Override
    public void skip(Tape in, int revision){
    }
}
