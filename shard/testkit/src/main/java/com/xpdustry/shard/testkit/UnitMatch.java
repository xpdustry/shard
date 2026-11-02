package com.xpdustry.shard.testkit;

/** How the diff pairs units of the two sides. */
public enum UnitMatch{
    /** By entity id. Shard replays vanilla's id counter, so ids match when both sides spawned the same things. */
    ID,
    /** By type and team, then by rank in id order: the n-th dagger of crux on one side is the n-th on the other. */
    TYPE_ORDER
}
