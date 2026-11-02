package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.logic.*;

/** Vanilla's {@code LPrintable}: a building {@code printflush} gives the text buffer to (messages). */
public interface LogicPrintable{
    boolean printable(Processor exec);

    void print(CharSequence text);
}
