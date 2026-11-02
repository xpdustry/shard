package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Who holds RTS fields in the checkpoint tests: a world part, so forks and checkpoints carry the handles, and a
 * {@link Phase#BUILDINGS} handler that edits the map and issues orders from the world's {@code Rand}. It runs after
 * the flush, so every tick boundary has changes pending.
 */
public final class Orders implements WorldPart, Checkpointable{
    public final IntSeq handles = new IntSeq();
    public boolean enabled;

    @Override
    public WorldPart copy(CopyContext context){
        var out = new Orders();
        out.handles.addAll(handles);
        out.enabled = enabled;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.bool(enabled).ints(handles.items, 0, handles.size);
    }

    @Override
    public void write(CheckpointOut out){
        out.intSeq("handles", handles).bool("enabled", enabled);
    }

    @Override
    public void read(CheckpointIn in){
        in.intSeq("handles", handles);
        enabled = in.bool("enabled");
    }

    public static final class Handler implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.BUILDINGS;
        }

        @Override
        public void run(World world){
            Orders orders = world.part(Orders.class);
            if(!orders.enabled) return;
            PathService paths = world.part(PathService.class);
            Rand rand = world.rand();
            long tick = world.ticks();
            for(int i = rand.random(1, 3); i > 0; i--) Churn.edit(world);
            if(tick % 113 == 70) Churn.flood(world);
            if(tick % 5 == 0){
                int dest = rand.random(world.tiles().size() - 1);
                orders.handles.add(paths.field(1 + rand.random(1), PathCost.of(rand.random(3)), dest));
                if(orders.handles.size > 5) paths.release(orders.handles.removeIndex(0));
            }
            // a solidity change without a tile change, as a door makes
            if(rand.chance(0.4)) paths.tileChanged(rand.random(world.tiles().size() - 1));
            if(tick % 61 == 30){
                PathRules rules = paths.rules();
                paths.rules(rand.chance(0.5) ? rules.withSizeAwarePaths(!rules.sizeAwarePaths()) : rules.withThreatPaths(!rules.threatPaths()));
            }
        }
    }
}
