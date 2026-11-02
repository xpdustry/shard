package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code OverflowGateBuild.read} (OverflowGate.java:88): nothing past the base at revision 4. Revision 1 held an
 * item buffer and revision 3 an int, both dropped; whatever items the base held are cleared.
 */
@Register
public final class OverflowGateCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("overflow-gate");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 4;
    }

    @Override
    public boolean reads(int revision){
        return revision >= 0 && revision <= 4;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        skip(in, revision);
        building.items.clear();
    }

    private static void skip(Reads in, int revision){
        if(revision == 1){
            ItemBuffers.skip(in);
        }else if(revision == 3){
            in.i();
        }
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision == 1){
            ItemBuffers.skip(in);
        }else if(revision == 3){
            in.i();
        }
    }
}
