package com.xpdustry.shard.server.sync;

/**
 * A world's sync cadence and bounds, in ticks of 1/60 s. Vanilla times them with the wall clock
 * ({@code NetServer.java:39-43}, {@code :1319}); Shard counts its own ticks, so a world keeps its cadence through lag
 * (Tier C timing, the payloads are Tier A).
 *
 * @param snapshotTicks    entity and state snapshots: {@code net.snapshot-interval}, 200 ms
 * @param blockTicks       synced block snapshots: {@code blockSyncTime}, 6 s
 * @param healthTicks      building health: {@code healthSyncTime}, 0.5 s, polled only while a change waits
 * @param planTicks        plan previews to teammates: {@code planPreviewSyncTime}, 0.5 s
 * @param joinBufferFrames the most frames held for one loading viewer
 * @param joinBufferBytes  the most bytes held for one loading viewer: flushed at once on confirm, so it stays well
 *                         under the transport's write queue (256 KiB)
 * @param universeSeconds  {@code universe.seconds()}, which the state snapshot sends: a vanilla server loads it from
 *                         its settings ({@code utimei}) and, outside a campaign, never advances it; 0 on a new server
 */
public record SyncSettings(int snapshotTicks, int blockTicks, int healthTicks, int planTicks, int joinBufferFrames, long joinBufferBytes,
                           int universeSeconds){
    public static final SyncSettings DEFAULT = new SyncSettings(12, 360, 30, 30, 4096, 160 * 1024, 0);

    public SyncSettings{
        if(snapshotTicks < 1 || blockTicks < 1 || healthTicks < 1 || planTicks < 1 || joinBufferFrames < 1 || joinBufferBytes < 1
            || universeSeconds < 0){
            throw new IllegalArgumentException(toString());
        }
    }

    public SyncSettings withSnapshotTicks(int snapshotTicks){
        return new SyncSettings(snapshotTicks, blockTicks, healthTicks, planTicks, joinBufferFrames, joinBufferBytes, universeSeconds);
    }

    public SyncSettings withJoinBuffer(int frames, long bytes){
        return new SyncSettings(snapshotTicks, blockTicks, healthTicks, planTicks, frames, bytes, universeSeconds);
    }

    public SyncSettings withUniverseSeconds(int seconds){
        return new SyncSettings(snapshotTicks, blockTicks, healthTicks, planTicks, joinBufferFrames, joinBufferBytes, seconds);
    }
}
