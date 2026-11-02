package com.xpdustry.shard.compat.v160.blocks.production;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.production.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code DrillBuild.write}/{@code read} (Drill.java:385), revision 1: {@code progress}, then {@code warmup}; revision 0
 * has neither. The burst drill's building adds nothing to it. The dominant item isn't saved: a loaded drill counts its
 * ore again when its proximity is built.
 */
public abstract class DrillCodec implements BuildingCodec{
    private final KindKey kind;

    DrillCodec(String kind){
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
        var drill = (Drill)building;
        out.f(drill.progress);
        out.f(drill.warmup);
    }

    @Override
    public final void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var drill = (Drill)building;
        if(revision >= 1){
            drill.progress = in.f();
            drill.warmup = in.f();
        }
    }

    @Override
    public final void skip(Tape in, int revision){
        if(revision >= 1){
            in.f();
            in.f();
        }
    }

    /** The mechanical, pneumatic, laser and blast drills. */
    @Register
    public static final class Plain extends DrillCodec{
        public Plain(){
            super("drill");
        }
    }

    /** The impact and eruption drills. */
    @Register
    public static final class Burst extends DrillCodec{
        public Burst(){
            super("burst-drill");
        }
    }
}
