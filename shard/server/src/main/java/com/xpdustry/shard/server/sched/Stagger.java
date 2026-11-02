package com.xpdustry.shard.server.sched;

/**
 * Spreads worlds over time, so a pool never runs them as one burst (note 24 §2.2, §2.5): each world gets a tick
 * phase within the period and a snapshot phase within the snapshot interval, both from a mixed hash of its id.
 *
 * <p>Note 24's prototype seeded {@code new Random(worldId)}: for ids 0, 1, 2… the first doubles are nearly equal, so
 * every world got the same "random" phase and all of them sent snapshots on the same tick, losing about a fifth of
 * the UDP data. Here each phase comes from a SplitMix64 finalizer over the id and a per-use salt, so neighbouring ids
 * land far apart and the tick and snapshot phases are independent.
 */
public final class Stagger{
    private static final long TICK_SALT = 0x632BE59BD9B4E019L, SNAPSHOT_SALT = 0x8CB92BA72F3D8DD7L;

    /** SplitMix64's finalizer: every input bit affects every output bit. */
    public static long mix(long value){
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A uniform fraction in [0, 1) for {@code id} and {@code salt}. */
    public static double fraction(long id, long salt){
        return (mix(mix(id) ^ salt) >>> 11) * 0x1.0p-53;
    }

    /** Where in the tick period the world's first deadline falls: {@code [0, period)}. */
    public static long tickPhase(long id, long periodNanos){
        return (long)(fraction(id, TICK_SALT) * periodNanos);
    }

    /** The world tick, modulo {@code intervalTicks}, on which the world sends snapshots: {@code [0, interval)}. */
    public static int snapshotPhase(long id, int intervalTicks){
        if(intervalTicks <= 0) throw new IllegalArgumentException("interval " + intervalTicks);
        return (int)(fraction(id, SNAPSHOT_SALT) * intervalTicks);
    }

    /**
     * Whether world {@code id} sends a snapshot after {@code tick}. Keyed by the world's own tick, not the wall clock,
     * so a world keeps its phase across lag and hibernation.
     */
    public static boolean snapshotDue(long id, long tick, int intervalTicks){
        return Math.floorMod(tick, intervalTicks) == snapshotPhase(id, intervalTicks);
    }

    private Stagger(){}
}
