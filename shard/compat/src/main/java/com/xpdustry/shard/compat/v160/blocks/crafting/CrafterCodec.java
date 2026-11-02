package com.xpdustry.shard.compat.v160.blocks.crafting;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.crafting.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code GenericCrafterBuild.write}/{@code read} (GenericCrafter.java:384), revision 0: {@code progress}, {@code warmup},
 * then, for a block with {@code legacyReadWarmup} (the cultivator), a 0 that is read and dropped. A kind that adds fields
 * writes them after, in {@link #writeMore}.
 */
public abstract class CrafterCodec implements BuildingCodec{
    private final KindKey kind;

    protected CrafterCodec(String kind){
        this.kind = KindKey.of(kind);
    }

    @Override
    public final KindKey kind(){
        return kind;
    }

    @Override
    public final int revision(){
        return 0;
    }

    @Override
    public final void write(Building building, Writes out, ContentHeader.IdMapping ids){
        var crafter = (GenericCrafterBuild)building;
        out.f(crafter.progress);
        out.f(crafter.warmup);
        if(crafter.crafter().legacyReadWarmup()) out.f(0f);
        writeMore(crafter, out);
    }

    @Override
    public final void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var crafter = (GenericCrafterBuild)building;
        crafter.progress = in.f();
        crafter.warmup = in.f();
        if(crafter.crafter().legacyReadWarmup()) in.f();
        readMore(crafter, in);
    }

    @Override
    public final void skip(Tape in, int revision){
        skip(in, revision, null);
    }

    @Override
    public final void skip(Tape in, int revision, BlockDef block){
        in.f();
        in.f();
        if(block != null && block.baked() && block.fields().b("legacyReadWarmup")) in.f();
        skipMore(in);
    }

    protected void writeMore(GenericCrafterBuild crafter, Writes out){
    }

    protected void readMore(GenericCrafterBuild crafter, Reads in){
    }

    protected void skipMore(Tape in){
    }
}
