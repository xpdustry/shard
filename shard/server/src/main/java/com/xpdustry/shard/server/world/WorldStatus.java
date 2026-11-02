package com.xpdustry.shard.server.world;

import com.xpdustry.shard.server.sched.*;

import java.nio.file.*;
import java.time.*;

/**
 * What a world reports (ARCHITECTURE.md §7: "the world reports its durable tick and the checkpoint's age").
 *
 * @param mode the scheduler mode while resident, or {@code PARKED}
 * @param tick the world's tick, or the durable tick while it is on disk
 * @param members sessions in the world
 * @param generation the current checkpoint generation, or 0 if none is durable yet
 * @param durableTick the tick of that generation, or -1
 * @param checkpointAge how long ago it was published, or null
 * @param stats the scheduler's tick summary while resident, or empty
 * @param error the last failure (a tick, a publication, a wake), or null
 * @param bundle the last crash bundle written, or null
 * @param recovery why the last load didn't use {@code CURRENT}, or null
 */
public record WorldStatus(WorldId id, Lifecycle lifecycle, WorldScheduler.Mode mode, long tick, int members, long generation,
                          long durableTick, Duration checkpointAge, String stats, String error, Path bundle, String recovery){
}
