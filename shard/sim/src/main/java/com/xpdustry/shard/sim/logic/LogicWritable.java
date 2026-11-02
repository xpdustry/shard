package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.logic.*;

/** Vanilla's {@code LWritable}: a building a processor can {@code write} to. */
public interface LogicWritable{
    boolean writable(Processor exec);

    /** {@code write(position, value)}: {@code position} and {@code value} are the writer's slots. */
    void write(Processor exec, int position, int value);
}
