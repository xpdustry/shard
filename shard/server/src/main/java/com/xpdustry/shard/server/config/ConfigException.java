package com.xpdustry.shard.server.config;

import java.util.*;

/** A config that doesn't validate. It carries every problem found, not just the first. */
public final class ConfigException extends Exception{
    private final List<Problem> problems;

    public ConfigException(List<Problem> problems){
        super(problems.size() + " config problem" + (problems.size() == 1 ? "" : "s") + ":\n  "
            + String.join("\n  ", problems.stream().map(Problem::toString).toList()));
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems(){
        return problems;
    }

    /**
     * One problem.
     *
     * @param location {@code shard.yml:12:5} (1-based line and column), {@code env SHARD__NET__PORT}, or a file name
     */
    public record Problem(String location, String message){
        @Override
        public String toString(){
            return location + ": " + message;
        }
    }
}
