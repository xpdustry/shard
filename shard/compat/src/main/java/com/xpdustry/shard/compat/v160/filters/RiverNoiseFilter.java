package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.content.*;

/** Vanilla's {@code RiverNoiseFilter}: ridged noise carves rivers of one floor with a deeper floor in their middle. */
public record RiverNoiseFilter(float scl, float threshold, float threshold2, float octaves, float falloff, BlockDef floor,
                               BlockDef floor2, BlockDef block, BlockDef target) implements GenerateFilter{
    @Override
    public void apply(GenerateInput in){
        float noise = in.rnoise(in.x, in.y, (int)octaves, scl, falloff, 1f);

        if(noise >= threshold && (target.id() == 0 || in.floor == target.id() || in.block == target.id())){
            if(floor.id() != 0) in.floor = floor.id();

            if(in.grid.isSolid(in.block) && block.id() != 0 && in.block != 0){
                in.block = block.id();
            }

            if(noise >= threshold2 && floor2.id() != 0){
                in.floor = floor2.id();
            }
        }
    }
}
