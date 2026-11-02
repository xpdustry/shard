package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.sim.blocks.distribution.*;

/**
 * {@code ItemBridgeBuild.write}/{@code read} (ItemBridge.java:519), after the base: the link, the warmup, the incoming
 * bridges (a signed byte count, then each packed position) and, from revision 1, whether it moved an item in its last
 * 30-tick window. Every kind built on {@link ItemBridgeBuild} saves this first: the bridge conveyor adds its buffer,
 * the bridge conduits nothing.
 */
public final class ItemBridgeBodies{
    private ItemBridgeBodies(){
    }

    public static void write(ItemBridgeBuild bridge, Writes out){
        out.i(bridge.link);
        out.f(bridge.warmup);
        out.b(bridge.incoming.size);
        for(int i = 0; i < bridge.incoming.size; i++) out.i(bridge.incoming.items[i]);
        out.bool(bridge.wasMoved || bridge.moved);
    }

    public static void read(ItemBridgeBuild bridge, Reads in, int revision){
        bridge.link = in.i();
        bridge.warmup = in.f();
        byte links = in.b();
        for(int i = 0; i < links; i++) bridge.incoming.add(in.i());
        if(revision >= 1) bridge.wasMoved = bridge.moved = in.bool();
    }

    public static void skip(Tape in, int revision){
        in.i();
        in.f();
        byte links = in.b();
        for(int i = 0; i < links; i++) in.i();
        if(revision >= 1) in.bool();
    }
}
