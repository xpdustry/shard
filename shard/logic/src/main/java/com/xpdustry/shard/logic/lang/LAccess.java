package com.xpdustry.shard.logic.lang;

/** Vanilla's {@code LAccess}: the sensor properties and control commands. The order is vanilla's. */
public enum LAccess{
    totalItems, firstItem, totalLiquids, totalPower, itemCapacity, liquidCapacity, powerCapacity, powerNetStored,
    powerNetCapacity, powerNetIn, powerNetOut, ammo, ammoCapacity, currentAmmoType, memoryCapacity, health, maxHealth,
    heat, shield, armor, efficiency, progress, timescale, rotation, x, y, velocityX, velocityY, shootX, shootY,
    cameraX(true), cameraY(true), cameraWidth(true), cameraHeight(true), displayWidth, displayHeight, bufferSize,
    operations, size, solid, dead, range, shooting, boosting, mineX, mineY, mining, buildX, buildY, pingX, pingY,
    pingText, building, breaking, speed, team, type, flag, flying, controlled, controller, name, payloadCount,
    payloadType, totalPayload, payloadCapacity, maxUnits, id, selectedBlock, selectedRotation, bulletLifetime,
    bulletTime,

    // values with parameters are controllable
    enabled(false, false, "to"),
    shoot(false, false, "x", "y", "shoot"),
    shootp(false, true, "unit", "shoot"),
    config(false, true, "to"),
    color(false, false, "to");

    private static final LAccess[] all = values();

    /** The parameter names of a control command; empty for sensor-only properties. */
    private final String[] params;
    /** Whether {@code control} passes an object first parameter through as an object. */
    public final boolean isObj;
    /** Only world processors may sense it (the camera properties). */
    public final boolean privileged;

    LAccess(){
        this(false, false);
    }

    LAccess(boolean privileged){
        this(privileged, false);
    }

    LAccess(boolean privileged, boolean isObj, String... params){
        this.privileged = privileged;
        this.isObj = isObj;
        this.params = params;
    }

    public int params(){
        return params.length;
    }

    public String param(int index){
        return params[index];
    }

    /** The constant with this ordinal. */
    public static LAccess of(int ordinal){
        return all[ordinal];
    }
}
