package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.content.*;

/** Vanilla's {@code OreFilter}: an ore where noise passes the threshold, on floors with a surface. */
public record OreFilter(float scl, float threshold, float octaves, float falloff, float tilt, BlockDef ore, BlockDef target)
    implements GenerateFilter{
    @Override
    public void apply(GenerateInput in){
        float noise = in.noise(in.x, in.y + in.x * tilt, scl, 1f, octaves, falloff);

        if(noise > threshold && in.overlay != in.grid.spawn && (target.id() == 0 || in.floor == target.id() || in.overlay == target.id())
            && hasSurface(in.grid, in.floor)){
            in.overlay = ore.id();
        }
    }

    /** {@code floor.asFloor().hasSurface()}, whose cast fails vanilla's load when the floor isn't one. */
    private static boolean hasSurface(FilterGrid grid, int floor){
        grid.requireFloor(floor, "floor");
        return grid.hasSurface(floor);
    }
}
