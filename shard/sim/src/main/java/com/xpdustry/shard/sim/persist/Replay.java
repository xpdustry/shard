package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * Re-runs a {@link CrashBundle} on the build that wrote it (ARCHITECTURE.md §4: "a same-build {@code shard replay
 * <bundle>} reader, the first user of the log"). It restores the checkpoint, offers each logged input at the tick it
 * was admitted, and ticks; after each tick it checks the verdicts the log recorded and every hash the world
 * reported, and at the failed tick it expects the same error.
 *
 * <p>Replay stops at the first difference: a different verdict, a hash that differs (naming the first subsystem),
 * an error at another tick, or no error at the failed one.
 */
public final class Replay{
    /**
     * @param until stop once the world reaches this tick ({@link Long#MAX_VALUE}: through the failed tick or the
     *     log's end)
     * @param hashEvery also print the world hash every this many ticks (0: never)
     * @param force replay even if the build or the systems differ; hashes are then expected to diverge
     */
    public record Options(long until, int hashEvery, boolean force){
        public static final Options DEFAULT = new Options(Long.MAX_VALUE, 0, false);

        public Options{
            if(hashEvery < 0) throw new IllegalArgumentException("hashEvery " + hashEvery);
        }
    }

    /**
     * @param from the checkpoint's tick
     * @param to the tick the replay reached
     * @param inputs log entries offered
     * @param hashes reported hashes compared, all equal unless {@code divergence} says otherwise
     * @param reproduced whether the failed tick threw the same error
     * @param divergence the first difference, or null
     */
    public record Result(long from, long to, int inputs, int hashes, boolean reproduced, String divergence){
        /** No difference, and the failure (if the bundle has one) came back. */
        public boolean ok(){
            return divergence == null;
        }
    }

    /** Replays {@code bundle} on {@code config}, which must be the world's (see {@link Checkpoint#config()}). */
    public static Result run(CrashBundle bundle, WorldConfig config, Options options, Consumer<String> out){
        var checkpoint = Checkpoint.decode(bundle.checkpoint());
        Header header = checkpoint.header();
        if(!header.build().equals(BuildInfo.id())){
            String why = "the bundle was written by build " + header.build() + "; this is " + BuildInfo.id();
            if(!options.force()) throw new CheckpointException(why + " (replay is exact only on the same build)");
            out.accept("warning: " + why);
        }
        if(header.systems() != Checkpoint.systemsId(config)){
            String why = "the world ran other systems (" + Long.toHexString(header.systems()) + ") than this config's ("
                + Long.toHexString(Checkpoint.systemsId(config)) + ")";
            if(!options.force()) throw new CheckpointException(why);
            out.accept("warning: " + why);
        }
        if(checkpoint.logPosition() != bundle.logStart()){
            throw new CheckpointException("the bundle's log starts at " + bundle.logStart() + " but its checkpoint is at " + checkpoint.logPosition());
        }
        World world = checkpoint.restore(config);
        // the inputs that waited at the checkpoint are in the log too, under the same arrival numbers
        world.inbox().forget();
        long from = world.ticks();
        out.accept("restored tick " + from + " from a " + header.build() + " checkpoint; " + bundle.entries().size()
            + " inputs, " + bundle.hashes().size() + " hashes" + (bundle.failedTick() >= 0 ? ", failed at tick " + bundle.failedTick() : ""));

        var marks = new TreeMap<Long, WorldHash>();
        for(CrashBundle.Mark mark : bundle.hashes()) marks.put(mark.tick(), mark.hash());
        List<CommandLog.Entry> entries = bundle.entries();
        long logBase = world.log().position();
        int next = 0, compared = 0;
        String divergence = compare(marks, world);
        if(divergence != null) return new Result(from, from, 0, 0, false, divergence);
        if(marks.containsKey(from)) compared++;
        long stop;
        if(bundle.failedTick() >= 0) stop = Math.min(options.until(), bundle.failedTick() + 1);
        else if(options.until() != Long.MAX_VALUE) stop = options.until();
        else stop = Math.max(entries.isEmpty() ? from : entries.getLast().tick() + 1, marks.isEmpty() ? from : marks.lastKey());

        while(world.ticks() < stop){
            long tick = world.ticks();
            int first = next;
            while(next < entries.size() && entries.get(next).tick() == tick){
                CommandLog.Entry entry = entries.get(next);
                boolean queued;
                try{
                    queued = world.inbox().replay(new Inbox.Arrival(entry.arrival(), entry.input()));
                }catch(IllegalArgumentException e){
                    return new Result(from, tick, next, compared, false, "input " + next + " at tick " + tick + ": " + e.getMessage());
                }
                if(!queued) return new Result(from, tick, next, compared, false, "the inbox refused input " + next + " at tick " + tick);
                next++;
            }
            if(next < entries.size() && entries.get(next).tick() < tick){
                return new Result(from, tick, next, compared, false, "log entry " + next + " is for tick " + entries.get(next).tick() + ", before " + tick);
            }
            try{
                world.tick();
            }catch(Throwable error){
                String summary = CrashBundle.trace(error).lines().findFirst().orElse("").strip();
                if(tick == bundle.failedTick()){
                    boolean same = summary.equals(bundle.errorSummary());
                    out.accept("tick " + tick + " threw " + summary + (same ? " (as recorded)" : "; the bundle recorded " + bundle.errorSummary()));
                    return new Result(from, tick, next, compared, same, same ? null : "tick " + tick + " threw " + summary + ", not " + bundle.errorSummary());
                }
                return new Result(from, tick, next, compared, false, "tick " + tick + " threw " + summary + ", which the bundle didn't record");
            }
            List<CommandLog.Entry> admitted = world.log().since(logBase + first);
            for(int i = first; i < next; i++){
                CommandLog.Entry replayed = i - first < admitted.size() ? admitted.get(i - first) : null;
                CommandLog.Entry recorded = entries.get(i);
                if(replayed == null || !replayed.equals(recorded)){
                    return new Result(from, tick, next, compared, false, "input " + i + " at tick " + tick + " got "
                        + (replayed == null ? "no verdict" : replayed.verdict()) + "; the log recorded " + recorded.verdict());
                }
            }
            divergence = compare(marks, world);
            if(divergence != null) return new Result(from, world.ticks(), next, compared, false, divergence);
            if(marks.containsKey(world.ticks())) compared++;
            if(options.hashEvery() > 0 && world.ticks() % options.hashEvery() == 0) out.accept("tick " + world.ticks() + " " + world.hash());
        }
        if(bundle.failedTick() >= 0 && stop > bundle.failedTick()){
            return new Result(from, world.ticks(), next, compared, false, "tick " + bundle.failedTick() + " didn't throw; the bundle recorded " + bundle.errorSummary());
        }
        out.accept("reached tick " + world.ticks() + "; " + compared + " hashes match");
        return new Result(from, world.ticks(), next, compared, false, null);
    }

    private static String compare(SortedMap<Long, WorldHash> marks, World world){
        WorldHash expected = marks.get(world.ticks());
        if(expected == null) return null;
        WorldHash actual = world.hash();
        if(actual.value() == expected.value()) return null;
        return "the hash at tick " + world.ticks() + " differs, first in " + actual.firstDifference(expected).orElse("the total")
            + ": " + actual + " vs recorded " + expected;
    }

    private Replay(){}
}
