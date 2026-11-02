package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.content.*;

/** Vanilla's {@code NoiseFilter}: where simplex noise passes the threshold, sets the floor and replaces natural walls. */
public record NoiseFilter(float scl, float threshold, float octaves, float falloff, float tilt, BlockDef floor, BlockDef block,
                          BlockDef target) implements GenerateFilter{
    @Override
    public void apply(GenerateInput in){
        float noise = in.noise(in.x, in.y + in.x * tilt, scl, 1f, octaves, falloff);

        if(noise > threshold && (target.id() == 0 || in.floor == target.id() || in.block == target.id())){
            if(floor.id() != 0) in.floor = floor.id();
            if(block.id() != 0 && in.block != 0 && !in.grid.isSynthetic(in.block)) in.block = block.id();
        }
    }
}
