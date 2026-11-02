package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;

/** The payload source's save bytes (PayloadSource.java:166-187), revision 1: the payload block's body, the unit type and the block (shorts, -1 for none; an id the epoch lacks reads as none, as {@code content.unit(id)} gives null), then the rally point ({@code TypeIO.writeVecNullable}: two NaNs for none). Revision 0 has no rally point. */
@Register
public final class PayloadSourceCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("payload-source");

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
        var source = (PayloadSource)building;
        PayloadBodies.write(source, out, ids);
        out.s(source.unit == -1 ? -1 : ids.requireStream(ContentType.UNIT, source.unit));
        out.s(source.configBlock == -1 ? -1 : ids.requireStream(ContentType.BLOCK, source.configBlock));
        // TypeIO.writeVecNullable: NaN for none
        out.f(source.commandPos == null ? Float.NaN : source.commandPos.x);
        out.f(source.commandPos == null ? Float.NaN : source.commandPos.y);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var source = (PayloadSource)building;
        PayloadBodies.read(source, in, ids);
        source.unit = PayloadBodies.content(ContentType.UNIT, in.s(), ids);
        source.configBlock = PayloadBodies.content(ContentType.BLOCK, in.s(), ids);
        if(revision >= 1){
            float x = in.f(), y = in.f();
            source.commandPos = Float.isNaN(x) || Float.isNaN(y) ? null : new Vec2(x, y);
        }
    }

    @Override
    public void skip(Tape in, int revision){
        PayloadBodies.skipBlockBody(in);
        in.s();
        in.s();
        if(revision >= 1){
            in.f();
            in.f();
        }
    }
}
