package com.xpdustry.shard.sim.world;

import java.util.*;

/** A {@link Command} queued with its {@link Cause}. */
public record Submission(Command command, Cause cause) implements Input{
    public Submission{
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(cause, "cause");
    }
}
