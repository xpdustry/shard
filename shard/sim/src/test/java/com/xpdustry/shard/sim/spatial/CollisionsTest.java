package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Collisions} against vanilla's {@code EntityCollisions.move}. Vanilla's version reads {@code Vars.world}, so
 * the exact comparison is in {@code PhysicsTraceOracleTest}; this checks the rules on their own.
 */
class CollisionsTest{
    static final UnitDef DAGGER = VanillaWorlds.VANILLA.unit("dagger");
    /** A wall of solid tiles at x = 10. */
    static final TileSolidity WALL = (kind, x, y) -> kind == Solidity.SOLID && x == 10;

    @Test
    void openGroundMovesByTheDelta(){
        Unit unit = unit(40f, 40f);
        Collisions.move(unit, 3.5f, -2.25f, WALL, Solidity.SOLID);
        assertEquals(43.5f, unit.x, 1e-4f);
        assertEquals(37.75f, unit.y, 1e-4f);
    }

    @Test
    void aWallStopsTheTileHitbox(){
        Unit unit = unit(60f, 40f);
        Collisions.move(unit, 30f, 0f, WALL, Solidity.SOLID);
        // tile 10 spans x 76..84; the tile hitbox is min(8 * 0.66, 7.8) = 5.28 wide
        assertEquals(76f - 5.28f / 2f, unit.x, 1e-4f);
        Unit legs = unit(60f, 40f);
        Collisions.move(legs, 30f, 0f, WALL, Solidity.LEGS);
        assertEquals(90f, legs.x, 1e-4f, "the wall is not leg-solid");
    }

    @Test
    void tinyAndNaNDeltasDoNothingAndHugeOnesAreClamped(){
        Unit unit = unit(40f, 40f);
        Collisions.move(unit, 0.00005f, -0.00005f, WALL, Solidity.SOLID);
        Collisions.move(unit, Float.NaN, 1f, WALL, Solidity.SOLID);
        assertEquals(40f, unit.x);
        assertEquals(40f, unit.y);
        Collisions.move(unit, 0f, 5000f, (kind, x, y) -> false, Solidity.SOLID);
        assertEquals(1040f, unit.y, 1e-2f);
    }

    @Test
    void aZeroAxisStillPushesOutOfOverlaps(){
        // inside the wall's edge: moving along y alone still resolves the x overlap
        Unit unit = unit(75f, 40f);
        Collisions.move(unit, 0f, 1f, WALL, Solidity.SOLID);
        assertEquals(76f - 5.28f / 2f, unit.x, 1e-4f);
    }

    static Unit unit(float x, float y){
        World world = UnitWorlds.world(32, 32, 1);
        Unit unit = Unit.create(world, DAGGER, 1);
        unit.set(x, y);
        return unit;
    }
}
