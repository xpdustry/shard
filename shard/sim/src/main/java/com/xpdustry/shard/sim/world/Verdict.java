package com.xpdustry.shard.sim.world;

/** What admission decided about one input. Logged with it in the {@link CommandLog}. */
public sealed interface Verdict{
    Verdict ADMITTED = new Admitted();

    static Verdict rejected(String reason){
        return new Rejected(reason);
    }

    default boolean admitted(){
        return this instanceof Admitted;
    }

    /** Use {@link #ADMITTED}. */
    record Admitted() implements Verdict{
    }

    /** Rejected for {@code reason}, which names the failed check ({@code "stale route"}, {@code "out of range"}). */
    record Rejected(String reason) implements Verdict{
    }
}
