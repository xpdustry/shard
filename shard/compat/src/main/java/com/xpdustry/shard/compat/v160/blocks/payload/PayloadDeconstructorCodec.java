package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;

/** The deconstructors' save bytes (PayloadDeconstructor.java:268-296), revision 0: the payload block's body, {@code progress}, {@code accum} (a short count, 0 for none, then the floats), then {@code deconstructing} as {@code Payload.write}. A read of no accumulator keeps the one the building has, as vanilla's does. */
@Register
public final class PayloadDeconstructorCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("payload-deconstructor");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 0;
    }

    @Override
    public boolean reads(int revision){
        return revision == 0;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        var deconstructor = (PayloadDeconstructor)building;
        PayloadBodies.write(deconstructor, out, ids);
        out.f(deconstructor.progress);
        if(deconstructor.accum != null){
            out.s(deconstructor.accum.length);
            for(float v : deconstructor.accum) out.f(v);
        }else{
            out.s(0);
        }
        PayloadBodies.write(deconstructor.world(), deconstructor.deconstructing, out, ids);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var deconstructor = (PayloadDeconstructor)building;
        PayloadBodies.read(deconstructor, in, ids);
        deconstructor.progress = in.f();
        short accums = in.s();
        if(accums > 0){
            deconstructor.accum = new float[accums];
            for(int i = 0; i < accums; i++) deconstructor.accum[i] = in.f();
        }
        deconstructor.deconstructing = PayloadBodies.read(deconstructor.world(), in, ids);
    }

    @Override
    public void skip(Tape in, int revision){
        PayloadBodies.skipBlockBody(in);
        in.f();
        short accums = in.s();
        for(int i = 0; i < accums; i++) in.f();
        PayloadBodies.skip(in);
    }
}
