package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;

/** The payload unloader's save bytes: the loader's ({@link PayloadLoaderCodec}); {@code lastOutputPower} isn't saved. */
@Register
public final class PayloadUnloaderCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("payload-unloader");

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
        PayloadLoaderCodec.writeLoader((PayloadLoaderBuild)building, out, ids);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        PayloadLoaderCodec.readLoader((PayloadLoaderBuild)building, in, revision, ids);
    }

    @Override
    public void skip(Tape in, int revision){
        PayloadLoaderCodec.skipLoader(in, revision);
    }
}
