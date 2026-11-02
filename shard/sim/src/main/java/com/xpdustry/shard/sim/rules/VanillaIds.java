package com.xpdustry.shard.sim.rules;

/**
 * The v160.7 ids that the rule defaults name: vanilla's {@code new Rules()} holds content objects, Shard holds their
 * wire ids. Checked against {@code data/vanilla-v160.7/content-ids.json} by compat's {@code VanillaIdsTest}; WP10 replaces
 * these with lookups in the pinned epoch.
 */
public final class VanillaIds{
    /** Team ids: {@code Team.all} has 256 entries. */
    public static final int TEAMS = 256, DERELICT = 0, SHARDED = 1, CRUX = 2;
    /** Item {@code copper}, the default loadout. */
    public static final int COPPER = 0;
    /** Unit {@code dagger}, the default spawn group type. */
    public static final int DAGGER = 0;
    /** Status effects {@code none} and {@code boss} (the old numeric boss effect id 8 maps to it). */
    public static final int STATUS_NONE = 0, STATUS_BOSS = 16;
    /** Weather {@code rain}, a weather entry's default. */
    public static final int RAIN = 1;
    /** Planets {@code erekir} and {@code serpulo}. */
    public static final int EREKIR = 1, SERPULO = 5;

    private VanillaIds(){}
}
