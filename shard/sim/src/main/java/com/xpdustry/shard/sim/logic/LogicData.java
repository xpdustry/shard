package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * Logic state as a checkpoint stores it: {@link LogicState}'s bytes, and the world objects they name, as references.
 * A kind writes a processor's or a memory cell's state as one of these under that field's name.
 */
public final class LogicData implements Checkpointable{
    public byte[] state;
    public Copyable[] refs;

    public LogicData(){
    }

    /** What {@code writer} writes, with the host objects it names. */
    public static LogicData of(Consumer<LogicState.Out> writer){
        var bytes = new Writes();
        var objects = new ArrayList<Object>();
        writer.accept(new LogicState.Writer(bytes, objects));
        var data = new LogicData();
        data.state = bytes.toByteArray();
        data.refs = new Copyable[objects.size()];
        for(int i = 0; i < objects.size(); i++){
            if(!(objects.get(i) instanceof Copyable copyable)){
                throw new IllegalStateException("logic state holds " + objects.get(i).getClass().getName() + ", which is not a world object");
            }
            data.refs[i] = copyable;
        }
        return data;
    }

    /** A reader of the state, which must then be read to its end. */
    public LogicState.In reader(){
        return new LogicState.Reader(new Reads(state), Arrays.asList(refs));
    }

    /** A value copy for a fork: world objects through the context, the VM's own values as they are. */
    public static UnaryOperator<Object> remap(CopyContext context){
        return value -> value instanceof Copyable copyable ? context.map(copyable) : value;
    }

    @Override
    public void write(CheckpointOut out){
        out.bytes("state", state).refs("refs", refs, refs == null ? 0 : refs.length);
    }

    @Override
    public void read(CheckpointIn in){
        state = in.bytes("state");
        refs = in.refs("refs", Copyable.class);
        if(state == null || refs == null) throw new CheckpointException("logic state without its bytes or references");
    }
}
