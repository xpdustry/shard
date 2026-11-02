package com.xpdustry.shard.server.ops;

import java.util.*;
import java.util.concurrent.*;

/**
 * Health checks (note 34). {@code /livez} runs the {@link Kind#LIVENESS} checks only: is the process and its control
 * loop alive. {@code /readyz} runs them all: can the host take new work (storage, migrations, admission reserve).
 * {@link Status#DEGRADED} still answers 200, with reasons, so a transient IO spike doesn't get a healthy host
 * restarted; only {@link Status#DOWN} answers 503. One world with a bad save is that world's status, not the host's.
 *
 * <p>Checks must be cheap and must not block: they run on every probe.
 */
public final class Health{
    private final ConcurrentSkipListMap<String, Registered> checks = new ConcurrentSkipListMap<>();

    public enum Status{ UP, DEGRADED, DOWN }

    public enum Kind{ LIVENESS, READINESS }

    @FunctionalInterface
    public interface Check{
        Result check();
    }

    /** One check's answer; {@code detail} says why when it isn't up. */
    public record Result(Status status, String detail){
        public static Result up(){
            return new Result(Status.UP, "");
        }

        public static Result degraded(String detail){
            return new Result(Status.DEGRADED, detail);
        }

        public static Result down(String detail){
            return new Result(Status.DOWN, detail);
        }
    }

    /** The worst status among the checks run, and each result by name. */
    public record Report(Status status, SortedMap<String, Result> checks){
        public int httpStatus(){
            return status == Status.DOWN ? 503 : 200;
        }
    }

    private record Registered(Kind kind, Check check){
    }

    public void register(String name, Kind kind, Check check){
        if(checks.putIfAbsent(name, new Registered(kind, check)) != null) throw new IllegalArgumentException("check " + name + " is already registered");
    }

    public void unregister(String name){
        checks.remove(name);
    }

    public Report live(){
        return run(true);
    }

    public Report ready(){
        return run(false);
    }

    private Report run(boolean livenessOnly){
        var results = new TreeMap<String, Result>();
        var worst = Status.UP;
        for(var entry : checks.entrySet()){
            if(livenessOnly && entry.getValue().kind != Kind.LIVENESS) continue;
            Result result;
            try{
                result = Objects.requireNonNull(entry.getValue().check.check(), "null result");
            }catch(RuntimeException e){
                result = Result.down("check failed: " + e);
            }
            results.put(entry.getKey(), result);
            if(result.status().compareTo(worst) > 0) worst = result.status();
        }
        return new Report(worst, Collections.unmodifiableSortedMap(results));
    }
}
