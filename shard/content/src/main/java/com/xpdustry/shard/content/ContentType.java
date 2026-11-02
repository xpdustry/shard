package com.xpdustry.shard.content;

import java.util.*;

/**
 * The content types a {@link ContentEpoch} holds, in vanilla's {@code ContentType} order ("do not rearrange, ever!"),
 * which is also the order vanilla initializes them in.
 */
public enum ContentType{
    ITEM("item", 0),
    BLOCK("block", 1),
    BULLET("bullet", 3),
    LIQUID("liquid", 4),
    STATUS("status", 5),
    UNIT("unit", 6),
    WEATHER("weather", 7);

    /**
     * Vanilla's {@code ContentType} names by ordinal, the types without Shard definitions included: mlog's
     * {@code lookup} parses these names, and the wire and saves carry the ordinals ({@link #vanillaOrdinal()} maps the
     * types above into it). The {@code _UNUSED} entries keep vanilla's ordinals.
     */
    public static final List<String> VANILLA_NAMES = List.of("item", "block", "mech_UNUSED", "bullet", "liquid", "status",
        "unit", "weather", "effect_UNUSED", "sector", "loadout_UNUSED", "typeid_UNUSED", "error", "planet", "ammo_UNUSED",
        "team", "unitCommand", "unitStance");

    private final String yamlName;
    private final int vanillaOrdinal;

    ContentType(String yamlName, int vanillaOrdinal){
        this.yamlName = yamlName;
        this.vanillaOrdinal = vanillaOrdinal;
    }

    /** The {@code type:} of a prototype ({@code block}) and vanilla's {@code ContentType} name. */
    public String yamlName(){
        return yamlName;
    }

    /** Vanilla's {@code ContentType.ordinal()}, as written on the wire ({@code TypeIO.writeContent}). */
    public int vanillaOrdinal(){
        return vanillaOrdinal;
    }

    /** The type named {@code name} ({@code block}), or null. */
    public static ContentType of(String name){
        for(ContentType t : values()) if(t.yamlName.equals(name)) return t;
        return null;
    }
}
