package com.xpdustry.shard.server.ops;

import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.ops.Metrics.*;
import com.xpdustry.shard.server.store.*;

import java.lang.management.*;
import java.time.*;
import java.util.*;

/** Metrics and health checks for the parts that exist so far: the JVM, the config and the store. */
public final class Instrumentation{
    /** A write that waited longer than this marks storage degraded. */
    public static final Duration SLOW_QUEUE = Duration.ofSeconds(5);

    /** Process uptime, heap, threads and GC, read at scrape time; and a liveness check that always passes. */
    public static void jvm(Metrics metrics, Health health){
        var runtime = ManagementFactory.getRuntimeMXBean();
        var memory = ManagementFactory.getMemoryMXBean();
        var threads = ManagementFactory.getThreadMXBean();
        metrics.gauge("process_uptime_seconds", "Seconds since the JVM started.", () -> runtime.getUptime() / 1000.0);
        metrics.gauge("jvm_memory_heap_used_bytes", "Heap in use.", () -> memory.getHeapMemoryUsage().getUsed());
        metrics.gauge("jvm_memory_heap_max_bytes", "The heap's limit.", () -> memory.getHeapMemoryUsage().getMax());
        metrics.gauge("jvm_threads_live", "Live platform threads.", threads::getThreadCount);
        var collectors = ManagementFactory.getGarbageCollectorMXBeans();
        metrics.collector("jvm_gc_collections_total", "Collections, by collector.", Type.COUNTER, List.of("gc"),
            () -> collectors.stream().map(gc -> new Sample(List.of(gc.getName()), Math.max(0, gc.getCollectionCount()))).toList());
        metrics.collector("jvm_gc_pause_seconds_total", "Time spent collecting, by collector.", Type.COUNTER, List.of("gc"),
            () -> collectors.stream().map(gc -> new Sample(List.of(gc.getName()), Math.max(0, gc.getCollectionTime()) / 1000.0)).toList());
        health.register("process", Health.Kind.LIVENESS, Health.Result::up);
    }

    /** The config generation and how many changes wait for a restart. */
    public static void config(Metrics metrics, ConfigService config){
        metrics.gauge("shard_config_generation", "The config generation in effect.", config::generation);
        metrics.gauge("shard_config_pending_changes", "Config changes that wait for a restart.", () -> config.pending().size());
    }

    /** The writer queue, transactions and WAL size; readiness fails when the store is closed and degrades when writes wait. */
    public static void store(Metrics metrics, Health health, Store store){
        metrics.gauge("shard_store_queue_depth", "Writes waiting for the writer thread.", () -> store.stats().queued());
        metrics.gauge("shard_store_queue_age_seconds", "How long the oldest waiting write has waited.", () -> store.stats().oldestQueuedNanos() / 1e9);
        metrics.gauge("shard_store_wal_bytes", "Size of the write-ahead log.", () -> store.stats().walBytes());
        metrics.collector("shard_store_writes_total", "Writes by outcome.", Type.COUNTER, List.of("outcome"), () -> {
            var stats = store.stats();
            return List.of(new Sample(List.of("committed"), stats.committed()), new Sample(List.of("failed"), stats.failed()),
                new Sample(List.of("rejected"), stats.rejected()));
        });
        metrics.collector("shard_store_batches_total", "Group commits.", Type.COUNTER, List.of(), () -> List.of(new Sample(List.of(), store.stats().batches())));
        metrics.collector("shard_store_commit_seconds_total", "Time spent running and committing batches.", Type.COUNTER, List.of(),
            () -> List.of(new Sample(List.of(), store.stats().commitNanos() / 1e9)));
        health.register("store", Health.Kind.READINESS, () -> {
            if(!store.isOpen()) return Health.Result.down("the store is closed");
            long waited = store.stats().oldestQueuedNanos();
            if(waited > SLOW_QUEUE.toNanos()) return Health.Result.degraded("a write has waited " + waited / 1_000_000 + " ms");
            return Health.Result.up();
        });
    }

    private Instrumentation(){}
}
