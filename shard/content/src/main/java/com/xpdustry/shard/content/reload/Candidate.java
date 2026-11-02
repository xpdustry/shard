package com.xpdustry.shard.content.reload;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;

/**
 * A content reload, built and checked off-thread before anything adopts it (ARCHITECTURE.md §5 "Reload").
 *
 * @param epoch the baked candidate, or null when it didn't load or bake
 * @param diagnostics why it is refused; empty when it may be adopted
 * @param changes what differs from the current epoch, by top-level field
 */
public record Candidate(Epoch epoch, List<Diagnostic> diagnostics, List<Change> changes){
    public Candidate{
        diagnostics = List.copyOf(diagnostics);
        changes = List.copyOf(changes);
    }

    public boolean accepted(){
        return epoch != null && diagnostics.isEmpty();
    }

    /** Whether every change is to {@code server} fields, which apply at the next tick boundary without a patch. */
    public boolean serverOnly(){
        return changes.stream().allMatch(c -> c.tag() == Tag.SERVER);
    }
}
