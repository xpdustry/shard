package com.xpdustry.shard.content.yaml;

import java.util.*;

/**
 * Collects every error of a load instead of stopping at the first, so one run reports all of them (note 20 §5.6).
 * {@link #check()} throws when any were reported.
 */
public final class Diagnostics{
    private final List<Diagnostic> list = new ArrayList<>();

    public void error(Mark mark, String message){
        list.add(new Diagnostic(mark, message));
    }

    public void error(Node node, String message){
        error(node.mark(), message);
    }

    public void addAll(Collection<Diagnostic> diagnostics){
        list.addAll(diagnostics);
    }

    public boolean isEmpty(){
        return list.isEmpty();
    }

    public int size(){
        return list.size();
    }

    public List<Diagnostic> list(){
        return List.copyOf(list);
    }

    /** Throws a {@link ContentException} with every error so far, if there is any. */
    public void check(){
        if(!list.isEmpty()) throw new ContentException(list);
    }
}
