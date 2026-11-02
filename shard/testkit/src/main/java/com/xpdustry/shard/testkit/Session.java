package com.xpdustry.shard.testkit;

/** One live world of a {@link Runner}. Called from one thread, between ticks. */
public interface Session extends AutoCloseable{
    /** Ticks run so far. */
    long tick();

    /** Applies an action now, between ticks. Throws with a clear message when this engine can't do it. */
    void apply(Action action);

    /** Runs one tick (Δ = 1). */
    void step();

    /** The world now. */
    Projection project();

    @Override
    void close();
}
