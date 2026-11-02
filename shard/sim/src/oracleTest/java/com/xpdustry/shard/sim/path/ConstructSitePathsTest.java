package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.build.Build;
import com.xpdustry.shard.sim.world.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.world.*;
import org.junit.jupiter.api.*;

import static com.xpdustry.shard.sim.path.PathWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Construct sites in the paths: {@code Tile.solid()} asks the site's {@code checkSolid()}, which is true while the block
 * it builds or the one it replaces is solid (ConstructBlock.java:193), so a wall's or a turret's site blocks ground
 * paths from its first frame and a conveyor's doesn't. On fortress, sites for a copper wall, a conveyor and a lancer
 * begin south of the core in vanilla and in Shard; after the flush Shard's packed tiles equal vanilla's
 * {@code packTile} on every tile. Found by the M0 scenario (W2.22): Shard's path layer never read a building's
 * {@code checkSolid()}.
 */
class ConstructSitePathsTest{
    @Test
    void sitesAreAsSolidAsTheBlocksTheyBuild(){
        Vanilla.host("fortress");
        VanillaPaths.repack();
        World shard = VanillaPaths.mirror();
        PathService paths = paths(shard);
        paths.rules(VanillaPaths.rules());
        paths.invalidateAll();
        shard.tick();
        var core = state.rules.defaultTeam.core();
        int cx = core.tile.x, cy = core.tile.y;
        begin(shard, Blocks.copperWall, cx - 4, cy - 5);
        begin(shard, Blocks.conveyor, cx - 2, cy - 5);
        begin(shard, Blocks.lancer, cx, cy - 6);
        assertTrue(world.tile(cx - 4, cy - 5).solid() && !world.tile(cx - 2, cy - 5).solid() && world.tile(cx + 1, cy - 5).solid(),
            "vanilla's sites: the wall's and the lancer's solid, the conveyor's not");
        shard.tick();

        int width = world.width();
        int[] vanilla = VanillaPaths.repack();
        for(int pos = 0; pos < vanilla.length; pos++){
            int mine = paths.packed(pos) & PathTile.VANILLA_BITS;
            if(mine != vanilla[pos]){
                fail("packed tile " + pos % width + "," + pos / width + " is " + Integer.toHexString(mine) + ", vanilla "
                    + Integer.toHexString(vanilla[pos]) + " (" + world.tiles.geti(pos) + ")");
            }
        }
    }

    /** {@code Build.beginPlace} in vanilla and in Shard, for team sharded with no builder. */
    static void begin(World shard, Block block, int x, int y){
        mindustry.world.Build.beginPlace(null, block, Team.sharded, x, y, 0, null);
        assertTrue(world.tile(x, y).build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild, block.name + "'s site in vanilla");
        Build.beginPlace(shard, null, VANILLA.block(block.id), Team.sharded.id, x, y, 0, null);
        assertTrue(shard.buildings().at(x, y) instanceof com.xpdustry.shard.sim.build.ConstructBuild, block.name + "'s site in Shard");
    }
}
