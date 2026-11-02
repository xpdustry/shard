package com.xpdustry.shard.server.sched;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * The server's threads outside the transport (ARCHITECTURE.md §7):
 *
 * <table>
 *   <caption>The server's threads</caption>
 *   <tr><th>Name</th><th>Kind</th><th>Count</th><th>Owns</th></tr>
 *   <tr><td>{@code tick-N}</td><td>platform</td><td>max(1, cores − 1)</td><td>world ticks ({@link WorldScheduler})</td></tr>
 *   <tr><td>{@code io-cpu-N}</td><td>platform pool</td><td>max(2, cores / 4)</td><td>deflate, checkpoints</td></tr>
 *   <tr><td>{@code io}</td><td>virtual</td><td>unbounded</td><td>DB, HTTP, files</td></tr>
 *   <tr><td>{@code server}</td><td>platform</td><td>1</td><td>console, world lifecycle, transfers</td></tr>
 * </table>
 *
 * The io-cpu queue is bounded ({@link Sizes#ioCpuQueue}); a full queue refuses work, so callers see backpressure
 * instead of an unbounded backlog.
 */
public final class ServerThreads implements AutoCloseable{
    /** Thread counts; {@link #forCores} gives the defaults. */
    public record Sizes(int tick, int ioCpu, int ioCpuQueue){
        public Sizes{
            if(tick < 1 || ioCpu < 1 || ioCpuQueue < 1) throw new IllegalArgumentException(toString());
        }

        public static Sizes forCores(int cores){
            // two io-cpu threads at least: with one, a slow encode holds every other world's joins and checkpoints
            return new Sizes(Math.max(1, cores - 1), Math.max(2, cores / 4), 256);
        }

        public static Sizes forThisMachine(){
            return forCores(Runtime.getRuntime().availableProcessors());
        }
    }

    private final Sizes sizes;
    private final ThreadPoolExecutor ioCpu;
    private final ExecutorService io;
    private final ExecutorService server;
    private volatile Thread serverThread;

    public ServerThreads(Sizes sizes){
        this.sizes = sizes;
        var counter = new AtomicInteger();
        ioCpu = new ThreadPoolExecutor(sizes.ioCpu(), sizes.ioCpu(), 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(sizes.ioCpuQueue()),
            task -> Thread.ofPlatform().name("io-cpu-" + counter.getAndIncrement()).daemon().unstarted(task),
            new ThreadPoolExecutor.AbortPolicy());
        io = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("io-", 0).factory());
        server = Executors.newSingleThreadExecutor(task -> {
            Thread thread = Thread.ofPlatform().name("server").daemon().unstarted(task);
            serverThread = thread;
            return thread;
        });
    }

    public Sizes sizes(){
        return sizes;
    }

    /** Settings for a {@link WorldScheduler} with {@link Sizes#tick} threads. */
    public WorldScheduler.Settings scheduler(){
        return WorldScheduler.Settings.of(sizes.tick());
    }

    /** CPU-bound work off the tick threads: compression, checkpoint encoding. Bounded; refuses work when full. */
    public Executor ioCpu(){
        return ioCpu;
    }

    /** Blocking IO: files, DB, HTTP. */
    public Executor io(){
        return io;
    }

    /** The single lifecycle thread: world state transitions run here, in order. */
    public Executor server(){
        return server;
    }

    /** Whether the caller is the server thread. */
    public boolean onServerThread(){
        return Thread.currentThread() == serverThread;
    }

    @Override
    public void close(){
        server.shutdown();
        ioCpu.shutdown();
        io.shutdown();
        try{
            server.awaitTermination(10, TimeUnit.SECONDS);
            ioCpu.awaitTermination(10, TimeUnit.SECONDS);
            io.awaitTermination(10, TimeUnit.SECONDS);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
        }
    }
}
