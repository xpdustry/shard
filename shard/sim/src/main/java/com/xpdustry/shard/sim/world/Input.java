package com.xpdustry.shard.sim.world;

/**
 * Anything that enters a world from outside the tick: a decoded client {@link IntentEnvelope} or a
 * {@link Submission} of a {@link Command}. Inputs are immutable; they queue in the {@link Inbox} and are admitted in
 * {@link Phase#COMMANDS}, in arrival order, each logged with its verdict.
 */
public sealed interface Input permits IntentEnvelope, Submission{
}
