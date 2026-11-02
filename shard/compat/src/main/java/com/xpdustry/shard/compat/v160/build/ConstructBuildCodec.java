package com.xpdustry.shard.compat.v160.build;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code ConstructBuild.write}/{@code read} (ConstructBlock.java:484): the progress, the previous and current blocks'
 * ids as shorts, then the accumulators, -1 for none, or their count and per requirement the accumulated and total
 * fractions and, from revision 1, the items left. The same bytes are a site's {@code writeSync} in block snapshots, as
 * construct blocks are synced.
 *
 * <p>Reading keeps vanilla's defaults for what it doesn't save: no previous buildings, builder or config, and
 * {@code wasConstructing} false. The build cost is the current block's build time under the world's rules.
 */
@Register
public final class ConstructBuildCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("construct-block");

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
        var site = (ConstructBuild)building;
        out.f(site.progress);
        out.s(ids.requireStream(ContentType.BLOCK, site.previous));
        out.s(ids.requireStream(ContentType.BLOCK, site.current));
        if(site.accumulator == null){
            out.b(-1);
        }else{
            out.b(site.accumulator.length);
            for(int i = 0; i < site.accumulator.length; i++){
                out.f(site.accumulator[i]);
                out.f(site.totalAccumulator[i]);
                out.i(site.itemsLeft[i]);
            }
        }
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var site = (ConstructBuild)building;
        site.progress = in.f();
        short pid = in.s(), rid = in.s();
        byte acsize = in.b();
        if(acsize != -1){
            site.accumulator = new float[acsize];
            site.totalAccumulator = new float[acsize];
            site.itemsLeft = new int[acsize];
            for(int i = 0; i < acsize; i++){
                site.accumulator[i] = in.f();
                site.totalAccumulator[i] = in.f();
                if(revision >= 1) site.itemsLeft[i] = in.i();
            }
        }
        // content.block(id), null for an unknown id, is air
        site.previous = pid == -1 ? 0 : Math.max(0, ids.toCurrent(ContentType.BLOCK, pid));
        site.current = rid == -1 ? 0 : Math.max(0, ids.toCurrent(ContentType.BLOCK, rid));
        site.buildCost = Construction.of(site.world()).info(site.current).buildTime * site.rules().buildCostMultiplier;
    }

    @Override
    public void skip(Tape in, int revision){
        in.f();
        in.s();
        in.s();
        byte acsize = in.b();
        if(acsize == -1) return;
        for(int i = 0; i < acsize; i++){
            in.f();
            in.f();
            if(revision >= 1) in.i();
        }
    }
}
