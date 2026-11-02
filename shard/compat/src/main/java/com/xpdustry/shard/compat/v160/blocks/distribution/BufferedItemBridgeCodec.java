package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.building.*;

/**
 * The bridge conveyor's save bytes (BufferedItemBridge.java:53): {@link ItemBridgeBodies}, then its {@code ItemBuffer}
 * (the queued count and the capacity as bytes, then every slot). Entries hold the item's content id unmapped, as
 * vanilla saves and reads them. A buffer saved longer than the block's keeps its first entries.
 *
 * <p>Vanilla quirk kept: {@code ItemBuffer.read} caps the count at the saved length minus one, so a full buffer loses
 * its last item on every load (ItemBuffer.java:66).
 */
@Register
public final class BufferedItemBridgeCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("buffered-item-bridge");

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
        var bridge = (BufferedItemBridge)building;
        ItemBridgeBodies.write(bridge, out);
        out.b(bridge.bufferIndex);
        out.b(bridge.buffer.length);
        for(long l : bridge.buffer) out.l(l);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var bridge = (BufferedItemBridge)building;
        ItemBridgeBodies.read(bridge, in, revision);
        int index = in.b();
        byte length = in.b();
        for(int i = 0; i < length; i++){
            long l = in.l();
            if(i < bridge.buffer.length) bridge.buffer[i] = l;
        }
        bridge.bufferIndex = Math.min(index, length - 1);
    }

    @Override
    public void skip(Tape in, int revision){
        ItemBridgeBodies.skip(in, revision);
        in.b();
        byte length = in.b();
        for(int i = 0; i < length; i++) in.l();
    }
}
