package com.xpdustry.shard.compat.v160.blocks.power;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.sim.blocks.power.*;

/**
 * {@code VariableReactorBuild.write}/{@code read} (VariableReactor.java:178): the generator base's fields, then
 * {@code heat}, {@code instability} and {@code warmup}.
 */
@Register
public final class VariableReactorCodec extends GeneratorCodec{
    public VariableReactorCodec(){
        super("variable-reactor");
    }

    @Override
    void writeMore(Generator generator, Writes out){
        var reactor = (VariableReactor)generator;
        out.f(reactor.heat);
        out.f(reactor.instability);
        out.f(reactor.warmup);
    }

    @Override
    void readMore(Generator generator, Reads in){
        var reactor = (VariableReactor)generator;
        reactor.heat = in.f();
        reactor.instability = in.f();
        reactor.warmup = in.f();
    }

    @Override
    void skipMore(Tape in){
        in.f();
        in.f();
        in.f();
    }
}
