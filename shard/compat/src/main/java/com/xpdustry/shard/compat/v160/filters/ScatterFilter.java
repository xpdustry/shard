package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.content.*;

/** Vanilla's {@code ScatterFilter}: by a hashed chance per tile, puts a block (or ore) on empty tiles and sets floors. */
public record ScatterFilter(float chance, BlockDef flooronto, BlockDef floor, BlockDef block) implements GenerateFilter{
    @Override
    public void apply(GenerateInput in){
        if(block.id() != 0 && (in.floor == flooronto.id() || flooronto.id() == 0) && in.block == 0 && in.chance(in.x, in.y) <= chance){
            if(!in.grid.isOverlay(block.id())){
                in.block = block.id();
            }else{
                in.overlay = block.id();
            }
        }

        if(floor.id() != 0 && (in.floor == flooronto.id() || flooronto.id() == 0) && in.chance(in.x, in.y) <= chance){
            in.floor = floor.id();
        }
    }
}
