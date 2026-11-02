package com.xpdustry.shard.compat.v160.filters;

/** Vanilla's {@code DistortFilter}: each tile copies a tile displaced by two noise fields. */
public record DistortFilter(float scl, float mag) implements GenerateFilter{
    @Override
    public boolean buffered(){
        return true;
    }

    @Override
    public void apply(GenerateInput in){
        FilterGrid grid = in.grid;
        int tile = in.tile(in.x + in.noise(scl, mag) - mag / 2f, in.y + in.noise(1, scl, mag) - mag / 2f);

        in.floor = grid.floor(tile);
        if(!grid.synthetic(tile) && !grid.isSynthetic(in.block)) in.block = grid.block(tile);
        in.overlay = grid.overlay(tile);
    }
}
