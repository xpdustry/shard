package com.xpdustry.shard.sim.world;

/**
 * An input that doesn't come from a client packet: admin actions, AI decisions, scripts, the results of async work
 * and the server's own lifecycle commands (ARCHITECTURE.md §4). Each goes through the same {@link Inbox} and
 * {@link CommandLog} as client intents, so nothing mutates a world from another thread.
 *
 * <p>The categories are sealed; each leaves its records open, so a subsystem defines its commands in its own
 * package. Commands are immutable records.
 */
public sealed interface Command permits Command.Admin, Command.Ai, Command.Script, Command.Async, Command.Internal{
    /** Applies this command at admission and returns the verdict; mutate only when admitting. */
    Verdict apply(World world, Cause cause);

    /** Admin and console actions: kick, rollback, rule edits, map changes. */
    non-sealed interface Admin extends Command{
    }

    /** Decisions from AI that runs outside the tick, joined at a fixed tick. */
    non-sealed interface Ai extends Command{
    }

    /** Script and plugin requests, applied as commands for a future tick (§4 "Plugins and observers"). */
    non-sealed interface Script extends Command{
    }

    /** Results of work done off the tick thread (path searches, checkpoint compression), joined at a fixed tick. */
    non-sealed interface Async extends Command{
    }

    /** The server's own commands, such as {@link Members.Join} and {@link Members.Leave}. */
    non-sealed interface Internal extends Command{
    }
}
