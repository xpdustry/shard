package com.xpdustry.shard.sim.world;

/**
 * The fixed phases of one tick, in order: vanilla's {@code Logic.update} plus Shard's (ARCHITECTURE.md §3 "Tick
 * phases"). Every tick advances exactly Δ = 1. Built-in subsystems attach {@link PhaseHandler}s; the world runs input
 * admission itself at the start of {@link #COMMANDS}.
 */
public enum Phase{
    /** Admit queued intents and commands in arrival order; log each with its verdict. */
    COMMANDS,
    /** Tile changes → incremental flowfield repair (note 22). */
    PATH_FLUSH,
    /** Team stats; the visibility pass when fog is on; fillItems, buildAi, rtsAi, prebuildAi. */
    TEAMS,
    /** Timers (vanilla {@code Time.run}). */
    TIME,
    /** The {@code @time} and {@code @tick} logic globals. */
    LOGIC_VARS,
    /** Server-side weather. */
    WEATHER,
    /** Map objectives. */
    OBJECTIVES,
    /** Wave countdown and {@code runWave}. */
    WAVES,
    /** Weather attributes. */
    ENV,
    /** Bullet and unit physics; its owner documents the exact order and checks it against vanilla traces (note 29). */
    PHYSICS,
    /** Players. */
    PLAYERS,
    /** Effects (server-side state only). */
    EFFECTS,
    /** Fires, puddles, labels and the rest of vanilla's {@code Groups.all}. */
    MISC,
    /** Brains (low rate, staggered), steering, movement, weapons; builders revalidate and commit plans here. */
    UNITS,
    /** Power graphs (note 30). */
    POWER,
    /** The building update list in vanilla order; processors execute in their own building's turn. */
    BUILDINGS,
    /** Bullet update, then collision. */
    BULLETS,
    /** {@code checkGameState}. */
    GAME_STATE,
    /** Freeze the projections the sync layer encodes, at snapshot cadence (§8). */
    SYNC
}
