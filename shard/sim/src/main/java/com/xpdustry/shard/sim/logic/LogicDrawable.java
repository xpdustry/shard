package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.logic.*;

/**
 * Vanilla's {@code LDrawable}: a display {@code drawflush} flushes to. A headless server's draw buffer is always empty
 * ({@code DrawI} returns first), so a flush only counts as an operation.
 */
public interface LogicDrawable{
    boolean drawable(Processor exec);

    /** {@code draw(graphicsBuffer)} with the empty buffer. */
    void drawFlush();
}
