package com.xpdustry.shard.compat.v160.blocks.crafting;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.crafting.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code SeparatorBuild.write}/{@code read} (Separator.java:191), revision 1: {@code progress}, {@code warmup}, then
 * {@code seed} (from revision 1; a revision 0 separator keeps the seed {@code created()} drew from its position).
 */
@Register
public final class SeparatorCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("separator");

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
        var separator = (Separator)building;
        out.f(separator.progress);
        out.f(separator.warmup);
        out.i(separator.seed);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var separator = (Separator)building;
        separator.progress = in.f();
        separator.warmup = in.f();
        if(revision == 1) separator.seed = in.i();
    }

    @Override
    public void skip(Tape in, int revision){
        in.f();
        in.f();
        if(revision == 1) in.i();
    }
}
