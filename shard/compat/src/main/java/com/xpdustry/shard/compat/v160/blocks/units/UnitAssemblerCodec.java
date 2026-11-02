package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.blocks.payload.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;

/**
 * {@code UnitAssemblerBuild.write}/{@code read} (UnitAssembler.java:756-789), version 1: the payload block's body, then
 * {@code progress}, the drones' count as a byte and their ids, {@code blocks} ({@code PayloadSeq.write}: the negated
 * entry count as a short, then each entry's content type ordinal, id as a short and amount as an int) and the rally
 * point ({@code writeVecNullable}, from revision 1). A read keeps the drone ids for the first update
 * ({@code readUnits}); a {@code PayloadSeq} of the old format (a positive count) holds blocks only.
 */
@Register
public final class UnitAssemblerCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("unit-assembler");
    private static final ContentType[] BY_ORDINAL = new ContentType[32];

    static{
        for(ContentType type : ContentType.values()) BY_ORDINAL[type.vanillaOrdinal()] = type;
    }

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
        return revision >= 0 && revision <= 1;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        var assembler = (UnitAssembler)building;
        PayloadBodies.write(assembler, out, ids);
        out.f(assembler.progress);
        out.b(assembler.units.size());
        for(Unit unit : assembler.units) out.i(unit.id());
        writeSeq(assembler.blocks, out, ids);
        UnitBlockBodies.writeVec(out, assembler.commandPos);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var assembler = (UnitAssembler)building;
        PayloadBodies.read(assembler, in, ids);
        assembler.progress = in.f();
        int count = in.b();
        assembler.readUnits.clear();
        for(int i = 0; i < count; i++) assembler.readUnits.add(in.i());
        readSeq(assembler.blocks, in, ids);
        if(revision >= 1) assembler.commandPos = UnitBlockBodies.readVec(in);
    }

    @Override
    public void skip(Tape in, int revision){
        PayloadBodies.skipBlockBody(in);
        in.f();
        int count = in.b();
        for(int i = 0; i < count; i++) in.i();
        short amount = in.s();
        if(amount >= 0){
            for(int i = 0; i < amount; i++){
                in.s();
                in.i();
            }
        }else{
            for(int i = 0; i < -amount; i++){
                in.ub();
                in.s();
                in.i();
            }
        }
        if(revision >= 1){
            in.f();
            in.f();
        }
    }

    /** {@code PayloadSeq.write}: the new format, entries in the sequence's order. */
    static void writeSeq(PayloadSeq seq, Writes out, ContentHeader.IdMapping ids){
        out.s(-seq.entries());
        for(int i = 0; i < seq.entries(); i++){
            ContentType type = seq.type(i);
            out.b(type.vanillaOrdinal());
            out.s(ids.requireStream(type, seq.id(i)));
            out.i(seq.amount(i));
        }
    }

    /** {@code PayloadSeq.read}: content the epoch lacks is dropped, as vanilla's {@code add(null)} ignores it. */
    static void readSeq(PayloadSeq seq, Reads in, ContentHeader.IdMapping ids){
        seq.clear();
        short amount = in.s();
        if(amount >= 0){
            // old format, block only
            for(int i = 0; i < amount; i++){
                int id = ids.toCurrent(ContentType.BLOCK, in.s());
                int count = in.i();
                if(id >= 0) seq.add(ContentType.BLOCK, id, count);
            }
        }else{
            for(int i = 0; i < -amount; i++){
                int ordinal = in.ub();
                int saved = in.s();
                int count = in.i();
                ContentType type = ordinal < BY_ORDINAL.length ? BY_ORDINAL[ordinal] : null;
                int id = type == null ? -1 : ids.toCurrent(type, saved);
                if(id >= 0) seq.add(type, id, count);
            }
        }
    }
}
