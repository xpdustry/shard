package com.xpdustry.shard.compat.v160.blocks.power;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code GeneratorBuild.write}/{@code read} (PowerGenerator.java:218), revision 1: {@code productionEfficiency}, then
 * {@code generateTime} (from revision 1). A kind that adds fields writes them after, in {@link #writeMore}.
 */
abstract class GeneratorCodec implements BuildingCodec{
    private final KindKey kind;

    GeneratorCodec(String kind){
        this.kind = KindKey.of(kind);
    }

    @Override
    public final KindKey kind(){
        return kind;
    }

    @Override
    public final int revision(){
        return 1;
    }

    @Override
    public final boolean reads(int revision){
        return revision == 0 || revision == 1;
    }

    @Override
    public final void write(Building building, Writes out, ContentHeader.IdMapping ids){
        var generator = (Generator)building;
        out.f(generator.productionEfficiency);
        out.f(generator.generateTime);
        writeMore(generator, out);
    }

    @Override
    public final void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var generator = (Generator)building;
        generator.productionEfficiency = in.f();
        if(revision >= 1) generator.generateTime = in.f();
        readMore(generator, in);
    }

    @Override
    public final void skip(Tape in, int revision){
        in.f();
        if(revision >= 1) in.f();
        skipMore(in);
    }

    void writeMore(Generator generator, Writes out){
    }

    void readMore(Generator generator, Reads in){
    }

    void skipMore(Tape in){
    }
}
