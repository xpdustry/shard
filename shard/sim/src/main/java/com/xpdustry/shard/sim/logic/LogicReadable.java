package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.logic.*;

/** Vanilla's {@code LReadable}: a building a processor can {@code read} from (memory, processors, messages, canvases). */
public interface LogicReadable{
    /** {@code readable(exec)}: false makes the processor read null. */
    boolean readable(Processor exec);

    /** {@code read(position, output)}: {@code position} and {@code output} are the reader's slots. */
    void read(Processor exec, int position, int output);
}
