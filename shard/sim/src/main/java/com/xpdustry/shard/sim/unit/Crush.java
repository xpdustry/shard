package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.world.*;

/** What tanks, crawlers and splashing legs do to the tiles they move over. */
final class Crush{
    private Crush(){
    }

    /**
     * {@code ConstructBlock.deconstructFinish(tile, tile.block(), unit)}, called directly (not as a {@code Call}) by
     * {@code TankComp} and {@code LegsComp} on a {@code unitMoveBreakable} block (a boulder, a bush): the tile becomes
     * air. Clients run the same movement and break the block themselves. Not yet modelled: the
     * {@code BlockBuildEndEvent} it fires, which only counts the default team's deconstructions in the game stats.
     */
    static void breakProp(Unit unit, int pos){
        var tiles = unit.world().tiles();
        unit.world().buildings().setBlock(tiles.x(pos), tiles.y(pos), unit.world().content().block(0), 0, 0, new Cause.Team(unit.team));
    }

    /** {@code world.tileWorld(x, y)}'s position, or -1 outside the map. */
    static int tileWorld(Unit unit, float x, float y){
        var tiles = unit.world().tiles();
        int tx = Math.round(x / 8f), ty = Math.round(y / 8f);
        return tiles.inBounds(tx, ty) ? tiles.pos(tx, ty) : -1;
    }
}
