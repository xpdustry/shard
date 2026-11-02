package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.common.struct.*;

/** Vanilla's {@code CoreSpawnFilter}: keeps {@code amount} of the default team's cores, picked at random, and removes the rest. */
public record CoreSpawnFilter(int amount) implements GenerateFilter{
    @Override
    public boolean post(){
        return true;
    }

    @Override
    public void apply(FilterGrid grid, GenerateInput in){
        var spawns = new IntSeq();
        for(int pos = 0; pos < grid.size(); pos++){
            if(grid.team(pos) == grid.rules.defaultTeam && grid.isCore(grid.block(pos)) && grid.isCenter(pos)){
                spawns.add(pos);
            }
        }

        spawns.shuffle(grid.rand);

        int used = Math.min(spawns.size, amount);
        for(int i = used; i < spawns.size; i++){
            grid.remove(spawns.get(i));
        }
    }
}
