package com.xpdustry.shard.sim.path;

import static com.xpdustry.shard.sim.path.PathTile.*;

/**
 * A cost class: how a kind of unit pays for a packed tile. Each class has two vanilla formulas, one per field family:
 * <ul>
 *   <li>wave-AI fields (enemy cores) use {@code Pathfinder.costTypes} ({@code Pathfinder.java:54}), which
 *   {@code UnitType.flowfieldPathType} picks;</li>
 *   <li>RTS fields (a destination tile) use {@code ControlPathfinder}'s ({@code ControlPathfinder.java:30}), which
 *   {@code UnitType.pathCost} picks.</li>
 * </ul>
 * There is no flat class for flying units: they don't use fields. Vanilla's unused neoplasm class isn't ported.
 */
public enum PathCost{
    GROUND,
    LEGS,
    NAVAL,
    HOVER,
    /** Ground plus a soft clearance cost near walls, when {@link PathRules#sizeAwarePaths()} is on; else ground. */
    GROUND_LARGE;

    public static final int IMPASSABLE = -1;
    /** {@code ControlPathfinder.wallImpassableCap}: an enemy wall, "impassable synthetic". */
    static final int WALL_CAP = 1_000_000;
    /** {@code ControlPathfinder.solidCap}. */
    static final int SOLID_CAP = 7000;
    /** {@link #GROUND_LARGE}'s cost on a tile next to something it can't cross (note 22 §2.5). */
    static final int CLEARANCE_COST = 30;

    private static final PathCost[] ALL = values();

    public static PathCost of(int ordinal){
        return ALL[ordinal];
    }

    /** {@code UnitType.initPathType}'s choice ({@code UnitType.java:891}), for units that use fields. */
    public static PathCost of(boolean naval, boolean allowLegStep, boolean hovering){
        return naval ? NAVAL : allowLegStep ? LEGS : hovering ? HOVER : GROUND;
    }

    /** The wave-AI cost of {@code tile} for {@code team}, without Shard's layers. */
    static int flow(PathCost cls, int team, int tile){
        return switch(cls){
            case GROUND, GROUND_LARGE ->
                (is(tile, ALL_DEEP) || ((team(tile) == team && !is(tile, TEAM_PASSABLE)) || team(tile) == 0) && is(tile, SOLID)) ? IMPASSABLE :
                1 + health(tile) * 5 + (is(tile, NEAR_SOLID) ? 2 : 0) + (is(tile, NEAR_LIQUID) ? 6 : 0) + (is(tile, DEEP) ? 6000 : 0) +
                (is(tile, DAMAGES) ? 30 : 0);
            case LEGS ->
                is(tile, LEG_SOLID) ? IMPASSABLE : 1 + (is(tile, DEEP) ? 6000 : 0) + (is(tile, SOLID) ? 5 : 0);
            case NAVAL ->
                (!is(tile, LIQUID) || (is(tile, SOLID) && (team(tile) == team || team(tile) == 0)) ? 7000 : 1 + health(tile) * 5) +
                (is(tile, NEAR_GROUND) || is(tile, NEAR_SOLID) ? 14 : 0) + (is(tile, DEEP) ? 0 : 1) + (is(tile, DAMAGES) ? 35 : 0);
            case HOVER ->
                (((team(tile) == team && !is(tile, TEAM_PASSABLE)) || team(tile) == 0) && is(tile, SOLID)) ? IMPASSABLE :
                1 + health(tile) * 5 + (is(tile, NEAR_SOLID) ? 2 : 0);
        };
    }

    /** The RTS cost of {@code tile} for {@code team}, without the map-area limit or Shard's layers. */
    static int control(PathCost cls, int team, int tile){
        boolean ownOrNeutralSolid = is(tile, SOLID) && ((team(tile) == team && !is(tile, TEAM_PASSABLE)) || team(tile) == 0);
        boolean enemySolid = team(tile) != team && team(tile) != 0 && is(tile, SOLID);
        return switch(cls){
            case GROUND, GROUND_LARGE ->
                is(tile, ALL_DEEP) ? IMPASSABLE :
                ownOrNeutralSolid ? IMPASSABLE :
                (enemySolid ? WALL_CAP : 0) + 1 + (is(tile, NEAR_SOLID) ? 6 : 0) + (is(tile, NEAR_LIQUID) ? 8 : 0) +
                (is(tile, DEEP) ? 6000 : 0) + (is(tile, DAMAGES) ? 50 : 0);
            case HOVER ->
                ownOrNeutralSolid ? IMPASSABLE : (enemySolid ? WALL_CAP : 0) + 1 + (is(tile, NEAR_SOLID) ? 6 : 0);
            case LEGS ->
                is(tile, LEG_SOLID) ? IMPASSABLE :
                1 + (is(tile, NEAR_DEEP) ? 8 : 0) + (is(tile, DEEP) ? 6000 : 0) + (is(tile, NEAR_LEG_SOLID) ? 3 : 0);
            case NAVAL ->
                ownOrNeutralSolid ? IMPASSABLE :
                (!is(tile, LIQUID) ? 6000 : 1) + (enemySolid ? WALL_CAP : 0) + (is(tile, NEAR_GROUND) || is(tile, NEAR_SOLID) ? 6 : 0);
        };
    }

    /**
     * Whether a unit may step onto a tile of base cost {@code cost}: {@code Flowfield.passable}
     * ({@code Pathfinder.java:701}) for wave-AI fields, {@code ControlPathfinder.passable} ({@code :1470}) for RTS.
     */
    static boolean passable(PathCost cls, boolean rts, int cost){
        if(cost == IMPASSABLE) return false;
        // vanilla's edge case: naval reports 6000+ on land, which isn't really passable
        return rts ? cost < SOLID_CAP : !(cls == NAVAL && cost >= 6000);
    }
}
