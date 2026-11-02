package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.world.*;

/** Vanilla's {@code UnitSorts}, by the names content gives them ({@code sort:strongest}). */
public final class Sorts{
    /** {@code UnitSorts.closest}, every bullet's default. */
    public static final String CLOSEST = "sort:closest";

    private static final UnitSort FARTHEST = (u, x, y) -> -u.dst2(x, y);
    private static final UnitSort STRONGEST = (u, x, y) -> -u.maxHealth + Mathf.dst2(u.x, u.y, x, y) / 6400f;
    private static final UnitSort WEAKEST = (u, x, y) -> u.maxHealth + Mathf.dst2(u.x, u.y, x, y) / 6400f;
    private static final UnitSort MOST_ARMOR = (u, x, y) -> -u.armor + Mathf.dst2(u.x, u.y, x, y) / 6400f;
    private static final UnitSort LEAST_ARMOR = (u, x, y) -> u.armor + Mathf.dst2(u.x, u.y, x, y) / 6400f;
    private static final UnitSort MOST_SHIELD = (u, x, y) -> -u.shield + Mathf.dst2(u.x, u.y, x, y) / 6400f;
    private static final UnitSort LEAST_SHIELD = (u, x, y) -> u.shield + Mathf.dst2(u.x, u.y, x, y) / 6400f;

    private Sorts(){
    }

    /** The sort {@code name} names; {@code grouped} reads {@code world}'s {@link UnitClusters}. */
    public static UnitSort of(World world, String name){
        return switch(name){
            case CLOSEST -> UnitSort.CLOSEST;
            case "sort:farthest" -> FARTHEST;
            case "sort:strongest" -> STRONGEST;
            case "sort:weakest" -> WEAKEST;
            case "sort:mostArmor" -> MOST_ARMOR;
            case "sort:leastArmor" -> LEAST_ARMOR;
            case "sort:mostShield" -> MOST_SHIELD;
            case "sort:leastShield" -> LEAST_SHIELD;
            case "sort:grouped" -> grouped(world, 0.003f);
            default -> throw new IllegalArgumentException("unknown unit sort " + name);
        };
    }

    /** {@code UnitSorts.grouped(dstWeight)}: the most clustered ground units first, then the closest. */
    static UnitSort grouped(World world, float dstWeight){
        UnitClusters clusters = UnitClusters.of(world);
        return (u, x, y) -> -(clusters == null ? 0 : clusters.getClustered(world, u.team, u.x, u.y))
            + (dstWeight > 0 ? Mathf.dst(u.x, u.y, x, y) * dstWeight : 0f);
    }
}
