package com.xpdustry.shard.server.world;

import com.xpdustry.shard.sim.persist.*;

import java.time.*;

/**
 * When worlds idle, save and hibernate (note 24 §2.3: "60 Hz for 30 s → 1 Hz → hibernate after 10 min").
 *
 * @param idleAfter how long an empty world keeps ticking at 60 Hz before it drops to 1 Hz maintenance
 * @param hibernateAfter how long a world must be empty before it hibernates
 * @param retryAfter how long a world that failed to hibernate stays resident before it tries again
 * @param autosaveEvery how often a running world publishes a checkpoint generation
 * @param hashEvery every how many ticks a world records its hash for crash bundles
 * @param keepGenerations how many checkpoint generations to keep on disk
 * @param continuous keep ticking at 60 Hz when empty, never hibernate (a world that must keep simulating)
 * @param compression how checkpoints are compressed (deflate level 1: see {@code CheckpointBench})
 * @param matchCheckpointEvery every how many ticks a world also writes a match checkpoint, kept for the whole match
 *                             ({@link CheckpointStore#publishMatch}); a multiple of {@link #MATCH_ALIGNMENT}, or 0
 *                             for none
 * @param resume whether a world hosted again under an id with checkpoints on disk, from the same map and mode,
 *               continues from its current generation ({@link WorldManager#prepare}) instead of starting over: a
 *               server restart then goes on with the match
 */
public record LifecyclePolicy(Duration idleAfter, Duration hibernateAfter, Duration retryAfter, Duration autosaveEvery,
                              int hashEvery, int keepGenerations, boolean continuous, Compression compression, int matchCheckpointEvery,
                              boolean resume){
    /**
     * Match checkpoints fall on view keyframe ticks, multiples of 600 (docs/replay/FORMAT.md), so that a replay service can
     * regenerate each view segment from the checkpoint at its start (note 37 §1).
     */
    public static final int MATCH_ALIGNMENT = 600;

    public static final LifecyclePolicy DEFAULT = new LifecyclePolicy(Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(1),
        Duration.ofSeconds(60), 300, 3, false, Compression.FAST, 0, false);

    public LifecyclePolicy{
        if(hashEvery < 1 || keepGenerations < 1) throw new IllegalArgumentException(toString());
        if(hibernateAfter.compareTo(idleAfter) < 0) throw new IllegalArgumentException("hibernateAfter < idleAfter: " + this);
        if(matchCheckpointEvery < 0 || matchCheckpointEvery % MATCH_ALIGNMENT != 0){
            throw new IllegalArgumentException("matchCheckpointEvery is not a multiple of " + MATCH_ALIGNMENT + ": " + this);
        }
    }

    public LifecyclePolicy withTimes(Duration idleAfter, Duration hibernateAfter, Duration retryAfter, Duration autosaveEvery){
        return new LifecyclePolicy(idleAfter, hibernateAfter, retryAfter, autosaveEvery, hashEvery, keepGenerations, continuous, compression, matchCheckpointEvery, resume);
    }

    public LifecyclePolicy withHashEvery(int hashEvery){
        return new LifecyclePolicy(idleAfter, hibernateAfter, retryAfter, autosaveEvery, hashEvery, keepGenerations, continuous, compression, matchCheckpointEvery, resume);
    }

    public LifecyclePolicy withContinuous(boolean continuous){
        return new LifecyclePolicy(idleAfter, hibernateAfter, retryAfter, autosaveEvery, hashEvery, keepGenerations, continuous, compression, matchCheckpointEvery, resume);
    }

    public LifecyclePolicy withMatchCheckpointEvery(int matchCheckpointEvery){
        return new LifecyclePolicy(idleAfter, hibernateAfter, retryAfter, autosaveEvery, hashEvery, keepGenerations, continuous, compression, matchCheckpointEvery, resume);
    }

    public LifecyclePolicy withResume(boolean resume){
        return new LifecyclePolicy(idleAfter, hibernateAfter, retryAfter, autosaveEvery, hashEvery, keepGenerations, continuous, compression, matchCheckpointEvery, resume);
    }

    /** Ticks for a match checkpoint interval of {@code every}, rounded up to {@link #MATCH_ALIGNMENT}; zero is none. */
    public static int matchTicks(Duration every){
        long ticks = every.toMillis() * 60 / 1000;
        return (int)Math.min(Integer.MAX_VALUE / MATCH_ALIGNMENT, (ticks + MATCH_ALIGNMENT - 1) / MATCH_ALIGNMENT) * MATCH_ALIGNMENT;
    }
}
