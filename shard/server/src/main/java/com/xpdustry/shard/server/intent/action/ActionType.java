package com.xpdustry.shard.server.intent.action;

/**
 * Vanilla's {@code Administration.ActionType}, in its order (Administration.java:764): what a player asks to do to the
 * world, which {@link ActionFilter}s may deny. Each names the intent that asks for it.
 */
public enum ActionType{
    /** A breaking plan in {@code clientSnapshot}. */
    BREAK_BLOCK("breakBlock"),
    /** A placing plan in {@code clientSnapshot}. */
    PLACE_BLOCK("placeBlock"),
    /** {@code rotateBlock}. */
    ROTATE("rotate"),
    /** {@code tileConfig}. */
    CONFIGURE("configure"),
    /** {@code requestItem}. */
    WITHDRAW_ITEM("withdrawItem"),
    /** {@code transferInventory}. */
    DEPOSIT_ITEM("depositItem"),
    /** {@code unitControl}. */
    CONTROL("control"),
    /** {@code buildingControlSelect}. */
    BUILD_SELECT("buildSelect"),
    /**
     * Vanilla's legacy {@code command}, from the removed command center: nothing in v160.7 asks for it. It is kept for
     * vanilla's order and for filters written against its list; denying it denies nothing.
     */
    COMMAND("command"),
    /** {@code deletePlans}. */
    REMOVE_PLANNED("removePlanned"),
    /** {@code commandUnits}, {@code setUnitCommand} and {@code setUnitStance}. */
    COMMAND_UNITS("commandUnits"),
    /** {@code commandBuilding}. */
    COMMAND_BUILDING("commandBuilding"),
    /** {@code unitClear}. */
    RESPAWN("respawn"),
    /** {@code requestBuildPayload}. */
    PICKUP_BLOCK("pickupBlock"),
    /** {@code requestDropPayload}. */
    DROP_PAYLOAD("dropPayload"),
    /** {@code pingLocation}. */
    PING_LOCATION("pingLocation");

    private static final ActionType[] VALUES = values();

    private final String vanillaName;

    ActionType(String vanillaName){
        this.vanillaName = vanillaName;
    }

    /** The name vanilla's enum constant has, which admins and plugins know. */
    public String vanillaName(){
        return vanillaName;
    }

    /** The bit of this type in a rule's mask: its ordinal, vanilla's order. */
    public int bit(){
        return 1 << ordinal();
    }

    /** By vanilla's name or Shard's, ignoring case; null if none. */
    public static ActionType named(String name){
        for(ActionType type : VALUES){
            if(type.vanillaName.equalsIgnoreCase(name) || type.name().equalsIgnoreCase(name)) return type;
        }
        return null;
    }
}
