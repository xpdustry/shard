package com.xpdustry.shard.sim.game;

import com.xpdustry.shard.sim.world.*;

/** What happens to the game as a whole, as domain events: published where vanilla fires the event. */
public sealed interface GameEvent extends WorldEvent{
    long tick();

    /**
     * {@code GameOverEvent(winner)} ({@code Logic.checkGameState}): the game ended, {@code winner} (a team id) won; the
     * derelict team when nobody did. The server decides what follows (vanilla's {@code ServerControl} loads the next
     * map).
     */
    record GameOver(long tick, int winner) implements GameEvent{
    }
}
