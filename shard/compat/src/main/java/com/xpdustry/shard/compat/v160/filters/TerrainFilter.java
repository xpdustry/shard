package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;

/** Vanilla's {@code TerrainFilter}: walls where noise plus the distance from the map's centre passes the threshold. */
public record TerrainFilter(float scl, float threshold, float octaves, float falloff, float magnitude, float circleScl, float tilt,
                            BlockDef floor, BlockDef block) implements GenerateFilter{
    @Override
    public void apply(GenerateInput in){
        float noise = in.noise(in.x, in.y + in.x * tilt, scl, magnitude, octaves, falloff)
            + Mathf.dst((float)in.x / in.width, (float)in.y / in.height, 0.5f, 0.5f) * circleScl;

        if(floor.id() != 0){
            in.floor = floor.id();
        }

        if(noise >= threshold){
            in.block = block.id();
        }
    }
}
