package com.xpdustry.shard.compat.v160.blocks.power;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.sim.blocks.power.*;

/** {@code HeaterGeneratorBuild.write}/{@code read}: the generator base's fields, then {@code heat}. */
@Register
public final class HeaterGeneratorCodec extends GeneratorCodec{
    public HeaterGeneratorCodec(){
        super("heater-generator");
    }

    @Override
    void writeMore(Generator generator, Writes out){
        out.f(((HeaterGenerator)generator).heat);
    }

    @Override
    void readMore(Generator generator, Reads in){
        ((HeaterGenerator)generator).heat = in.f();
    }

    @Override
    void skipMore(Tape in){
        in.f();
    }
}
