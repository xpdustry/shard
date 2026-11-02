package com.xpdustry.shard.compat.v160.blocks.sandbox;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code LandingPadBuild.write}/{@code read}, revision 1: the configured item (a short, -1 for none), the priority, the
 * cooldown, then the arriving item, its timer and the water used so far, which revision 0 lacks.
 */
@Register
public final class LandingPadCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("landing-pad");

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
        var pad = (LandingPad)building;
        out.s(pad.config == -1 ? -1 : ids.requireStream(ContentType.ITEM, pad.config));
        out.i(pad.priority);
        out.f(pad.cooldown);
        out.s(pad.arriving == -1 ? -1 : ids.requireStream(ContentType.ITEM, pad.arriving));
        out.f(pad.arrivingTimer);
        out.f(pad.liquidRemoved);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var pad = (LandingPad)building;
        pad.config = ids.toCurrent(ContentType.ITEM, in.s());
        pad.priority = in.i();
        pad.cooldown = in.f();
        if(revision >= 1){
            pad.arriving = ids.toCurrent(ContentType.ITEM, in.s());
            pad.arrivingTimer = in.f();
            pad.liquidRemoved = in.f();
        }
    }

    @Override
    public void skip(Tape in, int revision){
        in.s();
        in.i();
        in.f();
        if(revision >= 1){
            in.s();
            in.f();
            in.f();
        }
    }
}
