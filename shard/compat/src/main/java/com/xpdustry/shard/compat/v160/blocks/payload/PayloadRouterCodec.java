package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;

/**
 * The payload router's save bytes (PayloadRouter.java:190-213), revision 1: the conveyor's, then the sorted content as
 * its type's ordinal (-1 for none) and id (-1), and {@code recDir}. Revision 0 has the conveyor's only. A read checks
 * the match again, as vanilla's does.
 */
@Register
public final class PayloadRouterCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("payload-router");

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
        var router = (PayloadRouter)building;
        PayloadConveyorCodec.writeConveyor(router, out, ids);
        ContentRef sorted = router.sorted;
        out.b(sorted == null ? -1 : sorted.type().vanillaOrdinal());
        out.s(sorted == null ? -1 : ids.requireStream(sorted.type(), sorted.id()));
        out.b(router.recDir);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var router = (PayloadRouter)building;
        PayloadConveyorCodec.readConveyor(router, in, ids);
        if(revision >= 1){
            int ctype = in.b();
            int sort = in.s();
            router.sorted = sorted(ctype, sort, ids);
            router.recDir = in.b();
            router.checkMatch();
        }
    }

    /** {@code content.getByID(ContentType.all[ctype], sort)}: null for -1, and for an id the type doesn't have. */
    private static ContentRef sorted(int ctype, int sort, ContentHeader.IdMapping ids){
        if(ctype == -1) return null;
        ContentType type = null;
        for(ContentType t : ContentType.values()) if(t.vanillaOrdinal() == ctype) type = t;
        if(type == null || sort < 0) return null;
        int id = ids.toCurrent(type, sort);
        return id < 0 ? null : new ContentRef(type, id);
    }

    @Override
    public void skip(Tape in, int revision){
        PayloadConveyorCodec.skipConveyor(in);
        if(revision >= 1){
            in.b();
            in.s();
            in.b();
        }
    }
}
