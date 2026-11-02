package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.common.struct.*;

/** Vanilla's {@code EnemySpawnFilter}: keeps {@code amount} of the enemy spawn points, picked at random. */
public record EnemySpawnFilter(int amount) implements GenerateFilter{
    @Override
    public boolean post(){
        return true;
    }

    @Override
    public void apply(FilterGrid grid, GenerateInput in){
        var spawns = new IntSeq();
        for(int pos = 0; pos < grid.size(); pos++){
            if(grid.overlay(pos) == grid.spawn){
                spawns.add(pos);
            }
        }

        spawns.shuffle(grid.rand);

        int used = Math.min(spawns.size, amount);
        for(int i = used; i < spawns.size; i++){
            grid.setOverlay(spawns.get(i), 0);
        }
    }
}
