package com.xpdustry.shard.content;

/**
 * A block that is a floor ({@code mindustry.world.blocks.environment.Floor}, overlays included), as vanilla's
 * {@code instanceof Floor}: the fields a unit or building standing on it reads. Every baked block of a {@code floor}
 * kind is one.
 */
public interface FloorDef extends BlockDef{
    /** The kind every floor is or extends. */
    KindKey FLOOR = KindKey.of("floor"), OVERLAY_FLOOR = KindKey.of("overlay-floor");

    /** {@code instanceof OverlayFloor}: drawn over the floor; ores are overlays. */
    default boolean isOverlay(){
        return isA(OVERLAY_FLOOR);
    }

    default boolean isLiquid(){
        return fields().b("isLiquid");
    }

    /** Ticks until a unit standing here drowns; 0 for floors that don't drown. */
    default float drownTime(){
        return fields().f("drownTime");
    }

    /** {@code isDeep()}: {@code drownTime > 0}. */
    default boolean isDeep(){
        return drownTime() > 0f;
    }

    default float speedMultiplier(){
        return fields().f("speedMultiplier");
    }

    default float dragMultiplier(){
        return fields().f("dragMultiplier");
    }

    /** {@code shallow}: naval units move at their plain speed here, not 1.3 times it. */
    default boolean shallow(){
        return fields().b("shallow");
    }

    /** Damage per tick to units standing here. */
    default float damageTaken(){
        return fields().f("damageTaken");
    }

    /** The status applied to units standing here ({@code none} by default). */
    StatusDef status();

    default float statusDuration(){
        return fields().f("statusDuration");
    }

    /** {@code damages()}: {@code damageTaken > 0 || status.damage > 0}. */
    default boolean damages(){
        return damageTaken() > 0f || status().damage() > 0f;
    }

    /** The liquid pumps draw from it, or null. */
    LiquidDef liquidDrop();

    default float liquidMultiplier(){
        return fields().f("liquidMultiplier");
    }

    /** {@code wallOre}: an ore that sits on walls. */
    default boolean wallOre(){
        return fields().b("wallOre");
    }
}
