package com.xpdustry.shard.sim.blocks.environment;

/**
 * Vanilla's built-in {@code Attribute} ids ({@code Attribute.all} order), which index {@code BlockTable.attribute}.
 * A stand-in until WP10 ships attributes as content.
 */
public final class Attributes{
    public static final int HEAT = 0, SPORES = 1, WATER = 2, OIL = 3, LIGHT = 4, SAND = 5, STEAM = 6;
    public static final int COUNT = 7;

    private static final String[] NAMES = {"heat", "spores", "water", "oil", "light", "sand", "steam"};

    public static String name(int id){
        return NAMES[id];
    }

    /** The id of the attribute called {@code name}, or -1. */
    public static int id(String name){
        for(int i = 0; i < NAMES.length; i++){
            if(NAMES[i].equals(name)) return i;
        }
        return -1;
    }

    private Attributes(){}
}
