package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code JunctionBuild.write}/{@code read} (Junction.java:98): its {@code DirectionalItemBuffer}. Entries are packed
 * longs with the item's content id as it is, unmapped, as vanilla saves and reads them. Revision 0 packed a byte id
 * ({@code BufferItemLegacy}), converted on read. A queue saved longer than the block's capacity keeps its first
 * entries; its count is cut at the next update.
 */
@Register
public final class JunctionCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("junction");

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
        var junction = (Junction)building;
        int capacity = junction.capacity();
        for(int i = 0; i < 4; i++){
            out.b(junction.indexes[i]);
            out.b(capacity);
            for(int j = 0; j < capacity; j++) out.l(junction.buffers[i * capacity + j]);
        }
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var junction = (Junction)building;
        int capacity = junction.capacity();
        for(int i = 0; i < 4; i++){
            junction.indexes[i] = in.b();
            byte length = in.b();
            for(int j = 0; j < length; j++){
                long value = in.l();
                if(revision == 0) value = Junction.BufferItem.fromLegacy(value);
                if(j < capacity) junction.buffers[i * capacity + j] = value;
            }
        }
    }

    @Override
    public void skip(Tape in, int revision){
        ItemBuffers.skip(in);
    }
}
