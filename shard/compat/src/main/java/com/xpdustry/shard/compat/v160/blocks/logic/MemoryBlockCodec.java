package com.xpdustry.shard.compat.v160.blocks.logic;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.wire.*;

/**
 * {@code MemoryBuild.write}/{@code read} (MemoryBlock.java:144-185), version 1: the capacity, then each slot as a
 * {@code TypeIO} value, numbers as doubles. Revision 0 stored doubles only. Slots past this block's capacity are read
 * and dropped; buildings and units stay {@link com.xpdustry.shard.sim.logic.Boxed} until {@code afterReadAll}.
 */
@Register
public final class MemoryBlockCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("memory-block");

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
        var memory = (MemoryBlock)building;
        out.i(memory.objects.length);
        for(int i = 0; i < memory.objects.length; i++){
            Object value = memory.objects[i];
            if(value == MemoryBlock.NUMBER){
                out.b(WireObjects.DOUBLE);
                out.d(memory.numbers[i]);
            }else{
                LogicValues.write(out, value, ids);
            }
        }
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var memory = (MemoryBlock)building;
        int amount = in.i();
        if(revision == 0){
            for(int i = 0; i < amount; i++){
                double value = in.d();
                if(i < memory.objects.length){
                    memory.objects[i] = MemoryBlock.NUMBER;
                    memory.numbers[i] = value;
                }
            }
            return;
        }
        // read all data, but ignore anything not fitting inside this memory building
        Globals table = Globals.of(building.world().content());
        for(int i = 0; i < amount; i++){
            Object value = LogicValues.read(in, ids, table);
            if(i >= memory.objects.length) continue;
            if(value instanceof Double d){
                memory.objects[i] = MemoryBlock.NUMBER;
                memory.numbers[i] = d;
            }else{
                memory.objects[i] = value;
            }
        }
    }

    @Override
    public void skip(Tape in, int revision){
        int amount = in.i();
        if(revision == 0){
            in.b(amount * 8);
            return;
        }
        for(int i = 0; i < amount; i++) LogicValues.skip(in);
    }
}
