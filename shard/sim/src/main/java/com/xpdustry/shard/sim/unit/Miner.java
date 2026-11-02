package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code MinerComp} (MinerComp.java): a unit mining the ore at {@link Unit#mineTile}, a tile as
 * {@code Point2.pack(x, y)}. {@link #update} is the component's {@code update()}, which {@link MinerComponent} runs in
 * the merged unit update; the other methods are the component's, for controllers, AI and processors.
 *
 * <p>Each tick a miner that mines what its stack can't take hands the stack to its core in range; drops a tile it
 * can no longer mine (out of {@code mineRange}, or nothing it can mine there); and, while not actively building, mines:
 * {@code mineTimer} grows by {@code mineSpeed} (times the rules'), and each time it reaches the item's time one item is
 * mined. A player's unit gives it straight to the core in range ({@code transferItemTo}); anything else, or a player
 * out of range, gets it in its own stack when the item lands ({@link ItemTransfers#transferItemToUnit}); a full stack
 * stops mining. Every random draw a server makes is kept, the effect's included.
 *
 * <p>Vanilla quirk, unsafe input: a server takes a player's mine tile from its snapshot and checks it here only for
 * range and for something the type can mine, not {@code playerUnmineable} (sand), which only the client checks
 * (InputHandler.java:2092).
 */
public final class Miner{
    /** {@code Vars.mineTransferRange}: a miner hands items to a core this close. */
    public static final float TRANSFER_RANGE = 220f;
    private static final float TILESIZE = 8f;

    private Miner(){
    }

    /** The type's mining fields. */
    public static MineType type(Unit unit){
        return unit.world().unitTable().mine(unit.type());
    }

    /** {@code canMine(item)}. */
    public static boolean canMine(Unit unit, int item){
        return type(unit).canMine(item);
    }

    /** {@code canMine()}: the type mines, and the rules let its team. */
    public static boolean canMine(Unit unit){
        MineType type = type(unit);
        return type.speed * UnitCalls.rules(unit.world()).unitMineSpeed(unit.team) > 0 && type.tier >= 0;
    }

    /** {@code offloadImmediately()}: a player's unit gives what it mines straight to the core. */
    public static boolean offloadImmediately(Unit unit){
        return unit.isPlayer();
    }

    /** {@code mining()}: a mine tile, and not actively building. */
    public static boolean mining(Unit unit){
        return unit.mineTile != -1 && !Builder.activelyBuilding(unit);
    }

    /**
     * {@code getMineResult(tile)}: the floor's ore under no block, for a floor miner; else a wall's ore, for a wall
     * miner; -1 when there's none, or the type can't mine it. {@code tile} is packed, or -1.
     */
    public static int mineResult(Unit unit, int tile){
        if(tile == -1) return -1;
        MineType type = type(unit);
        TileGrid tiles = unit.world().tiles();
        int pos = tiles.pos(Point2.x(tile), Point2.y(tile));
        int result;
        if(type.floor && tiles.block(pos) == 0){
            result = tiles.drop(pos);
        }else if(type.walls){
            result = tiles.wallDrop(pos);
        }else{
            return -1;
        }
        return type.canMine(result) ? result : -1;
    }

    /** {@code validMine(tile, checkDst)}: on the map, within {@code mineRange} if asked, with something to mine. */
    public static boolean validMine(Unit unit, int tile, boolean checkDst){
        if(tile == -1 || !unit.world().tiles().inBounds(Point2.x(tile), Point2.y(tile))) return false;
        if(checkDst && !unit.within(Point2.x(tile) * TILESIZE, Point2.y(tile) * TILESIZE, type(unit).range)) return false;
        return mineResult(unit, tile) != -1;
    }

    /** {@code validMine(tile)}. */
    public static boolean validMine(Unit unit, int tile){
        return validMine(unit, tile, true);
    }

    /** {@code closestCore()}: the nearest of the unit's team's cores, or null. */
    public static Building closestCore(Unit unit){
        Teams teams = Teams.of(unit.world());
        return teams == null ? null : teams.closestCore(unit.x, unit.y, unit.team);
    }

    /** {@code MinerComp.update()}. */
    public static void update(Unit unit){
        if(unit.mineTile == -1) return;
        World world = unit.world();
        Rand rand = world.rand();
        Building core = closestCore(unit);
        int item = mineResult(unit, unit.mineTile);
        float tx = Point2.x(unit.mineTile) * TILESIZE, ty = Point2.y(unit.mineTile) * TILESIZE;

        if(core != null && item != -1 && !unit.acceptsItem(item) && unit.within(core.x(), core.y(), TRANSFER_RANGE) && !offloadImmediately(unit)){
            int accepted = core.acceptStack(unit.item(), unit.stackAmount, unit.team);
            if(accepted > 0){
                float x = tx + rand.random(-TILESIZE / 2f, TILESIZE / 2f), y = ty + rand.random(-TILESIZE / 2f, TILESIZE / 2f);
                ItemTransfers.transferItemTo(world, unit, unit.item(), accepted, x, y, core);
                unit.clearItem();
            }
        }

        // a server always validates: !net.client() || isLocal()
        if(!validMine(unit, unit.mineTile)){
            unit.mineTile = -1;
            unit.mineTimer = 0f;
        }else if(mining(unit) && item != -1){
            float rate = type(unit).speed * UnitCalls.rules(world).unitMineSpeed(unit.team);
            unit.mineTimer += rate;

            // Fx.unitMine's position: an effect, but its draws are the random's
            if(Mathf.chance(rand, 0.035f * rate)){
                rand.random(-2f, 2f);
                rand.random(-2f, 2f);
            }

            if(unit.mineTimer >= type(unit).time(item)){
                unit.mineTimer = 0;
                // the sector's production stats are campaign only
                if(core != null && unit.within(core.x(), core.y(), TRANSFER_RANGE) && core.acceptStack(item, 1, unit.team) == 1 && offloadImmediately(unit)){
                    // add the item to the inventory before it is transferred
                    if(unit.item() == item) unit.addItem(item);
                    float x = tx + rand.random(-TILESIZE / 2f, TILESIZE / 2f), y = ty + rand.random(-TILESIZE / 2f, TILESIZE / 2f);
                    ItemTransfers.transferItemTo(world, unit, item, 1, x, y, core);
                }else if(unit.acceptsItem(item)){
                    float x = tx + rand.random(-TILESIZE / 2f, TILESIZE / 2f), y = ty + rand.random(-TILESIZE / 2f, TILESIZE / 2f);
                    ItemTransfers.transferItemToUnit(world, item, x, y, unit);
                }else{
                    unit.mineTile = -1;
                    unit.mineTimer = 0f;
                }
            }
            // the mining sound loops only on a client
        }
    }
}
