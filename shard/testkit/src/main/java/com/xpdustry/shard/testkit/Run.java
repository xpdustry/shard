package com.xpdustry.shard.testkit;

import com.xpdustry.shard.testkit.Action.*;

import java.util.*;

/**
 * A scenario playing on one runner: {@link #start} opens the world and applies the setup (tick 0), and each
 * {@link #step()} runs one tick, then the actions of the new tick. For poking at one engine by hand:
 * <pre>{@code
 * try(Run run = Run.start(OracleRunner.create(), scenario)){
 *     run.stepTo(120);
 *     System.out.println(run.project().json());
 * }
 * }</pre>
 */
public final class Run implements AutoCloseable{
    private final Runner runner;
    private final Scenario scenario;
    private final Session session;
    private int next;
    private long nanos;

    private Run(Runner runner, Scenario scenario, Session session){
        this.runner = runner;
        this.scenario = scenario;
        this.session = session;
    }

    public static Run start(Runner runner, Scenario scenario){
        long start = System.nanoTime();
        Session session;
        try{
            session = runner.open(scenario);
        }catch(RuntimeException e){
            throw new IllegalStateException("scenario '" + scenario.name() + "' on " + runner.name() + ": cannot open the world: " + e.getMessage(), e);
        }
        var run = new Run(runner, scenario, session);
        try{
            run.applyDue();
        }catch(RuntimeException e){
            session.close();
            throw e;
        }
        run.nanos += System.nanoTime() - start;
        return run;
    }

    public long tick(){
        return session.tick();
    }

    public boolean done(){
        return session.tick() >= scenario.ticks();
    }

    /** One tick, then the actions scheduled for it. */
    public void step(){
        long start = System.nanoTime();
        try{
            session.step();
        }catch(RuntimeException e){
            throw new IllegalStateException("scenario '" + scenario.name() + "' on " + runner.name() + ": tick " + (session.tick() + 1) + " failed: " + e, e);
        }
        applyDue();
        nanos += System.nanoTime() - start;
    }

    public void stepTo(long tick){
        while(session.tick() < tick) step();
    }

    /** Steps to the end of the scenario. */
    public void finish(){
        stepTo(scenario.ticks());
    }

    public Projection project(){
        long start = System.nanoTime();
        try{
            return session.project();
        }finally{
            nanos += System.nanoTime() - start;
        }
    }

    public Session session(){
        return session;
    }

    public Runner runner(){
        return runner;
    }

    /** Wall time spent in this run's world so far: opening, actions, ticks and projections. */
    public long nanos(){
        return nanos;
    }

    private void applyDue(){
        List<Timed> actions = scenario.actions();
        while(next < actions.size() && actions.get(next).at() <= session.tick()){
            Timed timed = actions.get(next++);
            try{
                session.apply(timed.action());
            }catch(RuntimeException e){
                throw new IllegalStateException("scenario '" + scenario.name() + "' on " + runner.name() + ", tick " + timed.at()
                    + ", action " + ScenarioYaml.describe(timed) + ": " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void close(){
        session.close();
    }
}
