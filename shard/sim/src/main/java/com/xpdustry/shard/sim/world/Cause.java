package com.xpdustry.shard.sim.world;

/**
 * Who caused a change. Every admitted input carries one, and every tile and building mutation records one in the
 * {@link Journal} (ARCHITECTURE.md §4), which is what block logs and rollback read.
 */
public sealed interface Cause{
    /** The server itself: world load, rules, scripted events with no other author. */
    Cause SERVER = new Server();

    /** A player, by session id. */
    record Player(long session) implements Cause{
    }

    /** A team acting as a whole (team AI, wave spawns). */
    record Team(int team) implements Cause{
    }

    /** An administrator, by name, through a command or the console. */
    record Admin(String name) implements Cause{
    }

    /** Unit or build AI working for a team. */
    record Ai(int team) implements Cause{
    }

    /** A plugin, by id. */
    record Plugin(String id) implements Cause{
    }

    /** Use {@link #SERVER}. */
    record Server() implements Cause{
    }
}
