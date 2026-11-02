package com.xpdustry.shard.compat.v160.blocks.sandbox;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/**
 * The legacy blocks' save bytes, which a map load reads and then removes the blocks. Vanilla quirk, kept: the mech pad
 * and the unit factory read fields they never write, so one saved live (placed by a world processor) reads past its
 * chunk; Shard writes and reads as vanilla does.
 */
public final class LegacyCodecs{
    private LegacyCodecs(){
    }

    /** {@code LegacyMechPadBuild.read}: three floats of pad data, discarded, whatever the revision; it writes none. */
    @Register
    public static final class MechPad implements BuildingCodec{
        private static final KindKey KIND = KindKey.of("legacy-mech-pad");

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
            return true;
        }

        @Override
        public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        }

        @Override
        public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
            in.f();
            in.f();
            in.f();
        }

        @Override
        public void skip(Tape in, int revision){
            in.f();
            in.f();
            in.f();
        }
    }

    /** {@code LegacyUnitFactoryBuild.read}: the build time, and at revision 0 the spawn count, discarded; it writes none. */
    @Register
    public static final class UnitFactory implements BuildingCodec{
        private static final KindKey KIND = KindKey.of("legacy-unit-factory");

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
            return true;
        }

        @Override
        public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        }

        @Override
        public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
            in.f();
            if(revision == 0) in.i();
        }

        @Override
        public void skip(Tape in, int revision){
            in.f();
            if(revision == 0) in.i();
        }
    }

    /** {@code LegacyCommandBuild.write}/{@code read}: one byte, 0. */
    @Register
    public static final class CommandCenter implements BuildingCodec{
        private static final KindKey KIND = KindKey.of("legacy-command-center");

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
            return true;
        }

        @Override
        public void write(Building building, Writes out, ContentHeader.IdMapping ids){
            out.b(0);
        }

        @Override
        public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
            in.b();
        }

        @Override
        public void skip(Tape in, int revision){
            in.b();
        }
    }
}
