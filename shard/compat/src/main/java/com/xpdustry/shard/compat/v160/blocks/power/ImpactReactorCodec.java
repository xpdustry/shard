package com.xpdustry.shard.compat.v160.blocks.power;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.sim.blocks.power.*;

/** {@code ImpactReactorBuild.write}/{@code read}: the generator base's fields, then {@code warmup}. */
@Register
public final class ImpactReactorCodec extends GeneratorCodec{
    public ImpactReactorCodec(){
        super("impact-reactor");
    }

    @Override
    void writeMore(Generator generator, Writes out){
        out.f(((ImpactReactor)generator).warmup);
    }

    @Override
    void readMore(Generator generator, Reads in){
        ((ImpactReactor)generator).warmup = in.f();
    }

    @Override
    void skipMore(Tape in){
        in.f();
    }
}
