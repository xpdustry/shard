package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;

/** The payload loader's save bytes (PayloadLoader.java:246-261), revision 1: the payload block's body, then {@code exporting}. Revision 0 has the body only. The unloader's are the same ({@link PayloadUnloaderCodec}). */
@Register
public final class PayloadLoaderCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("payload-loader");

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
        writeLoader((PayloadLoaderBuild)building, out, ids);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        readLoader((PayloadLoaderBuild)building, in, revision, ids);
    }

    @Override
    public void skip(Tape in, int revision){
        skipLoader(in, revision);
    }

    static void writeLoader(PayloadLoaderBuild loader, Writes out, ContentHeader.IdMapping ids){
        PayloadBodies.write(loader, out, ids);
        out.bool(loader.exporting);
    }

    static void readLoader(PayloadLoaderBuild loader, Reads in, int revision, ContentHeader.IdMapping ids){
        PayloadBodies.read(loader, in, ids);
        if(revision >= 1) loader.exporting = in.bool();
    }

    static void skipLoader(Tape in, int revision){
        PayloadBodies.skipBlockBody(in);
        if(revision >= 1) in.bool();
    }
}
