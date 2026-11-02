package com.xpdustry.shard.tools.fuzz;

import java.nio.file.*;
import java.util.*;

/**
 * A campaign's parameters. {@link #parse} reads {@code --name value} pairs; every name is a component.
 *
 * @param packets how many messages the fuzzers send in all: the run's bound
 * @param maps the maps hosted, one world each
 * @param clients fuzzing clients, spread over the worlds as they join and move
 * @param packetsPerSecond what one fuzzing client sends at most
 * @param mutatePercent the share of payloads damaged after generation
 * @param framePerMille the share of frames damaged around a valid payload (each closes its connection)
 * @param seed the fuzzers' random seed; client {@code i} uses {@code seed + i}
 * @param out where the report, the replays and any violation's packets go
 * @param joinWaitSeconds how long a join may take before a control client fails (a fuzzer just retries)
 * @param tickWindowSeconds the window over which a world must have run at least {@code minTickShare} of its ticks
 * @param minTickShare see above
 * @param longTickMillis a single tick longer than this is a violation
 * @param heapGrowthMiB the live heap after a GC may grow at most this much over the run's first measurement
 * @param audit whether to replay every recorded match at the end and compare its hashes
 */
record FuzzConfig(long packets, List<String> maps, int clients, int packetsPerSecond, int mutatePercent, int framePerMille, long seed,
                  Path out, int joinWaitSeconds, int tickWindowSeconds, double minTickShare, int longTickMillis, int heapGrowthMiB, boolean audit){
    static final FuzzConfig DEFAULT = new FuzzConfig(100_000, List.of("maze", "fortress", "glacier", "triad"), 32, 400, 30, 4, 1,
        Path.of("build/fuzz"), 180, 10, 0.5, 1000, 512, true);

    static FuzzConfig parse(String[] args){
        var c = DEFAULT;
        for(int i = 0; i < args.length; i += 2){
            if(i + 1 >= args.length) throw new IllegalArgumentException("no value for " + args[i]);
            String value = args[i + 1];
            c = switch(args[i]){
                case "--packets" -> c.withPackets(Long.parseLong(value.replace("_", "")));
                case "--maps" -> new FuzzConfig(c.packets, List.of(value.split(",")), c.clients, c.packetsPerSecond, c.mutatePercent, c.framePerMille, c.seed, c.out, c.joinWaitSeconds, c.tickWindowSeconds, c.minTickShare, c.longTickMillis, c.heapGrowthMiB, c.audit);
                case "--clients" -> new FuzzConfig(c.packets, c.maps, Integer.parseInt(value), c.packetsPerSecond, c.mutatePercent, c.framePerMille, c.seed, c.out, c.joinWaitSeconds, c.tickWindowSeconds, c.minTickShare, c.longTickMillis, c.heapGrowthMiB, c.audit);
                case "--rate" -> new FuzzConfig(c.packets, c.maps, c.clients, Integer.parseInt(value), c.mutatePercent, c.framePerMille, c.seed, c.out, c.joinWaitSeconds, c.tickWindowSeconds, c.minTickShare, c.longTickMillis, c.heapGrowthMiB, c.audit);
                case "--mutate" -> new FuzzConfig(c.packets, c.maps, c.clients, c.packetsPerSecond, Integer.parseInt(value), c.framePerMille, c.seed, c.out, c.joinWaitSeconds, c.tickWindowSeconds, c.minTickShare, c.longTickMillis, c.heapGrowthMiB, c.audit);
                case "--frames" -> new FuzzConfig(c.packets, c.maps, c.clients, c.packetsPerSecond, c.mutatePercent, Integer.parseInt(value), c.seed, c.out, c.joinWaitSeconds, c.tickWindowSeconds, c.minTickShare, c.longTickMillis, c.heapGrowthMiB, c.audit);
                case "--seed" -> c.withSeed(Long.parseLong(value));
                case "--out" -> c.withOut(Path.of(value));
                case "--heap-growth" -> new FuzzConfig(c.packets, c.maps, c.clients, c.packetsPerSecond, c.mutatePercent, c.framePerMille, c.seed, c.out, c.joinWaitSeconds, c.tickWindowSeconds, c.minTickShare, c.longTickMillis, Integer.parseInt(value), c.audit);
                case "--audit" -> new FuzzConfig(c.packets, c.maps, c.clients, c.packetsPerSecond, c.mutatePercent, c.framePerMille, c.seed, c.out, c.joinWaitSeconds, c.tickWindowSeconds, c.minTickShare, c.longTickMillis, c.heapGrowthMiB, Boolean.parseBoolean(value));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            };
        }
        return c;
    }

    FuzzConfig withPackets(long packets){
        return new FuzzConfig(packets, maps, clients, packetsPerSecond, mutatePercent, framePerMille, seed, out, joinWaitSeconds, tickWindowSeconds, minTickShare, longTickMillis, heapGrowthMiB, audit);
    }

    FuzzConfig withSeed(long seed){
        return new FuzzConfig(packets, maps, clients, packetsPerSecond, mutatePercent, framePerMille, seed, out, joinWaitSeconds, tickWindowSeconds, minTickShare, longTickMillis, heapGrowthMiB, audit);
    }

    FuzzConfig withOut(Path out){
        return new FuzzConfig(packets, maps, clients, packetsPerSecond, mutatePercent, framePerMille, seed, out, joinWaitSeconds, tickWindowSeconds, minTickShare, longTickMillis, heapGrowthMiB, audit);
    }

    FuzzConfig withClients(int clients){
        return new FuzzConfig(packets, maps, clients, packetsPerSecond, mutatePercent, framePerMille, seed, out, joinWaitSeconds, tickWindowSeconds, minTickShare, longTickMillis, heapGrowthMiB, audit);
    }

    FuzzConfig withMaps(List<String> maps){
        return new FuzzConfig(packets, maps, clients, packetsPerSecond, mutatePercent, framePerMille, seed, out, joinWaitSeconds, tickWindowSeconds, minTickShare, longTickMillis, heapGrowthMiB, audit);
    }

    /** For a run inside a loaded test JVM ({@code check}): only a world that nearly stops counts as not ticking. */
    FuzzConfig withTickLeniency(double minTickShare, int longTickMillis){
        return new FuzzConfig(packets, maps, clients, packetsPerSecond, mutatePercent, framePerMille, seed, out, joinWaitSeconds, tickWindowSeconds, minTickShare, longTickMillis, heapGrowthMiB, audit);
    }

    static final String USAGE = """
        shard-fuzz [--packets N] [--maps a,b,c] [--clients N] [--rate per-client/s] [--mutate %] [--frames per-mille]
                   [--seed S] [--out dir] [--heap-growth MiB] [--audit true|false]""";
}
