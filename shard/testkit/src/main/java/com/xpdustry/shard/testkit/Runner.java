package com.xpdustry.shard.testkit;

/**
 * An engine that runs scenarios: {@link OracleRunner} (vanilla) or {@link ShardRunner}. A runner is a reusable
 * configuration; each {@link #open} makes a fresh world.
 */
public interface Runner{
    /** The name reports use: {@code vanilla}, {@code shard}… */
    String name();

    /**
     * True when this runner's worlds live in static state (vanilla's {@code Vars}), so at most one of its sessions may
     * exist at a time. Two such runners can't run in lockstep; {@link Differential} records one side first.
     */
    default boolean global(){
        return false;
    }

    /** A new world for {@code scenario}, before any action or tick. {@link Run} drives it through the timeline. */
    Session open(Scenario scenario);

    /** Runs the whole scenario and returns the projection of its last tick. */
    default Projection run(Scenario scenario){
        try(Run run = Run.start(this, scenario)){
            run.finish();
            return run.project();
        }
    }
}
