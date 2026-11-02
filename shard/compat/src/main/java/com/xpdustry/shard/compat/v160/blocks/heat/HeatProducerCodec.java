package com.xpdustry.shard.compat.v160.blocks.heat;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.blocks.crafting.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.sim.blocks.crafting.*;
import com.xpdustry.shard.sim.blocks.heat.*;

/** {@code HeatProducerBuild.write}/{@code read} (HeatProducer.java:76): the crafter base's fields, then {@code heat}. */
@Register
public final class HeatProducerCodec extends CrafterCodec{
    public HeatProducerCodec(){
        super("heat-producer");
    }

    @Override
    protected void writeMore(GenericCrafterBuild crafter, Writes out){
        out.f(((HeatProducer)crafter).heat);
    }

    @Override
    protected void readMore(GenericCrafterBuild crafter, Reads in){
        ((HeatProducer)crafter).heat = in.f();
    }

    @Override
    protected void skipMore(Tape in){
        in.f();
    }
}
