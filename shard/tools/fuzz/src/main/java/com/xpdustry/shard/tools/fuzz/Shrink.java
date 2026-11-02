package com.xpdustry.shard.tools.fuzz;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.nio.file.*;
import java.util.*;

/**
 * Shrinks a failed world's crash bundle to the few inputs that still fail it. A trial restores the bundle's checkpoint,
 * offers a subset of its logged inputs at their ticks and arrival numbers, and ticks to the failed tick, hashing the
 * world every 300 ticks and at the end, as a hosted world does ({@code ManagedWorld.tick}). It fails when a tick or a
 * hash throws the bundle's error. Delta debugging (Zeller's ddmin) over the log finds a subset no single input of which
 * can go; the inputs are printed in log order.
 *
 * <pre>./gradlew :tools:fuzz:shrink -Pargs="crash-300-0.zip"</pre>
 */
public final class Shrink{
    private final CrashBundle bundle;
    private final WorldConfig config;
    private final String error;
    private int trials;

    Shrink(CrashBundle bundle){
        this.bundle = bundle;
        ContentEpoch content = ContentSource.resolve(Checkpoint.header(bundle.checkpoint()).content());
        WorldConfig stored = Checkpoint.decode(bundle.checkpoint()).config(content);
        WorldConfig hosted = HostedSystems.matching(stored, Checkpoint.header(bundle.checkpoint()).systems());
        config = hosted != null ? hosted : stored;
        error = bundle.errorSummary();
    }

    public static void main(String[] args) throws Exception{
        var shrink = new Shrink(CrashBundle.decode(Files.readAllBytes(Path.of(args[0]))));
        System.out.println("error: " + shrink.error + "; " + shrink.bundle.entries().size() + " inputs, failed at tick " + shrink.bundle.failedTick());
        List<CommandLog.Entry> all = shrink.bundle.entries();
        if(!shrink.fails(all)){
            System.out.println("the whole log doesn't fail the world again: not reproducible this way");
            System.exit(1);
        }
        shrink.firstBadTick(all);
        List<CommandLog.Entry> minimal = shrink.ddmin(all);
        System.out.println(minimal.size() + " inputs fail it (" + shrink.trials + " trials):");
        for(CommandLog.Entry entry : minimal) System.out.println("  tick " + entry.tick() + " " + entry.verdict() + " " + entry.input());
    }

    /**
     * Replays the whole log hashing after every tick, and prints the first tick whose hash or tick throws with the
     * inputs admitted in it: for a failure the hash finds, the inputs that made the bad state, wherever it sat until the
     * next periodic hash.
     */
    void firstBadTick(List<CommandLog.Entry> entries){
        World world = Checkpoint.decode(bundle.checkpoint()).restore(config);
        world.inbox().forget();
        int next = 0;
        while(world.ticks() <= bundle.failedTick()){
            long tick = world.ticks();
            int first = next;
            while(next < entries.size() && entries.get(next).tick() <= tick){
                CommandLog.Entry entry = entries.get(next++);
                world.inbox().replay(new Inbox.Arrival(entry.arrival(), entry.input()));
            }
            try{
                world.tick();
                world.hash();
            }catch(Throwable thrown){
                System.out.println("first bad tick: " + tick + " (" + thrown + "); its inputs:");
                for(int i = first; i < next; i++) System.out.println("  " + entries.get(i).verdict() + " " + entries.get(i).input());
                return;
            }
        }
        System.out.println("no tick threw when hashed every tick");
    }

    /** Whether replaying {@code entries} throws the bundle's error by the failed tick. */
    boolean fails(List<CommandLog.Entry> entries){
        trials++;
        World world = Checkpoint.decode(bundle.checkpoint()).restore(config);
        world.inbox().forget();
        long stop = bundle.failedTick() >= 0 ? bundle.failedTick() + 1 : entries.isEmpty() ? world.ticks() + 300 : entries.getLast().tick() + 301;
        int next = 0;
        try{
            while(world.ticks() < stop){
                long tick = world.ticks();
                while(next < entries.size() && entries.get(next).tick() <= tick){
                    CommandLog.Entry entry = entries.get(next++);
                    world.inbox().replay(new Inbox.Arrival(entry.arrival(), entry.input()));
                }
                world.tick();
                if(world.ticks() % 300 == 0) world.hash();
            }
            world.hash();
            return false;
        }catch(Throwable thrown){
            // printStackTrace's first line, as the bundle recorded it
            String summary = thrown.toString().strip();
            return summary.equals(error);
        }
    }

    /** Zeller's ddmin: a failing subset from which no single input can go. */
    List<CommandLog.Entry> ddmin(List<CommandLog.Entry> failing){
        List<CommandLog.Entry> current = failing;
        int n = 2;
        while(current.size() >= 2){
            List<List<CommandLog.Entry>> chunks = split(current, n);
            boolean reduced = false;
            for(List<CommandLog.Entry> chunk : chunks){
                if(fails(chunk)){
                    current = chunk;
                    n = 2;
                    reduced = true;
                    break;
                }
            }
            if(!reduced){
                for(int i = 0; i < chunks.size(); i++){
                    List<CommandLog.Entry> complement = new ArrayList<>();
                    for(int j = 0; j < chunks.size(); j++) if(j != i) complement.addAll(chunks.get(j));
                    if(fails(complement)){
                        current = complement;
                        n = Math.max(n - 1, 2);
                        reduced = true;
                        break;
                    }
                }
            }
            if(!reduced){
                if(n >= current.size()) break;
                n = Math.min(current.size(), n * 2);
            }
            System.out.println("  " + current.size() + " inputs still fail (" + trials + " trials)");
        }
        if(current.size() == 1 && fails(List.of())) return List.of();
        return current;
    }

    private static List<List<CommandLog.Entry>> split(List<CommandLog.Entry> list, int n){
        var out = new ArrayList<List<CommandLog.Entry>>();
        int size = list.size();
        for(int i = 0; i < n; i++){
            List<CommandLog.Entry> part = list.subList(i * size / n, (i + 1) * size / n);
            if(!part.isEmpty()) out.add(part);
        }
        return out;
    }
}
