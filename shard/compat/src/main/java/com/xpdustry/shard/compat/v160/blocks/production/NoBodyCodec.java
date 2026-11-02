package com.xpdustry.shard.compat.v160.blocks.production;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/**
 * The production kinds whose building class has no {@code write}: wall crafters, pumps, solid pumps and frackers.
 * Their liquids and items are the base's modules; the body is empty at revision 0, and a loaded one starts its
 * warmup, timers and pumped amounts over.
 */
public abstract class NoBodyCodec implements BuildingCodec{
    private final KindKey kind;

    NoBodyCodec(String kind){
        this.kind = KindKey.of(kind);
    }

    @Override
    public final KindKey kind(){
        return kind;
    }

    @Override
    public final int revision(){
        return 0;
    }

    @Override
    public final void write(Building building, Writes out, ContentHeader.IdMapping ids){
    }

    @Override
    public final void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
    }

    @Override
    public final void skip(Tape in, int revision){
    }

    @Register
    public static final class WallCrafterCodec extends NoBodyCodec{
        public WallCrafterCodec(){
            super("wall-crafter");
        }
    }

    @Register
    public static final class PumpCodec extends NoBodyCodec{
        public PumpCodec(){
            super("pump");
        }
    }

    @Register
    public static final class SolidPumpCodec extends NoBodyCodec{
        public SolidPumpCodec(){
            super("solid-pump");
        }
    }

    @Register
    public static final class FrackerCodec extends NoBodyCodec{
        public FrackerCodec(){
            super("fracker");
        }
    }
}
