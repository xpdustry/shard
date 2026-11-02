package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@link Phase#PATH_FLUSH}: the tick's tile changes go to the {@link PathService}, which repairs its fields before
 * anything reads them.
 *
 * <p>The changes come from {@link com.xpdustry.shard.sim.tile.TileGrid#changes}, the tick's change list that every
 * reader shares (the read rule is in {@code sim.tile}'s package comment). A change that doesn't touch the grid but
 * should re-sample a tile calls {@link PathService#tileChanged(int)} itself.
 */
@Register
public final class PathFlush implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.PATH_FLUSH;
    }

    @Override
    public void run(World world){
        PathService paths = world.part(PathService.class);
        world.tiles().changes((pos, before) -> paths.tileChanged(pos));
        paths.flush(world);
    }
}
