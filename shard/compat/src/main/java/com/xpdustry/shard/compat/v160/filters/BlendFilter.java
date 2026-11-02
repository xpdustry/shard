package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.content.*;

/** Vanilla's {@code BlendFilter}: tiles within a radius of a block get a floor (or a wall, when it isn't a floor). */
public record BlendFilter(float radius, BlockDef block, BlockDef floor, BlockDef ignore) implements GenerateFilter{
    @Override
    public boolean buffered(){
        return true;
    }

    @Override
    public void apply(GenerateInput in){
        FilterGrid grid = in.grid;
        boolean floorIsFloor = grid.isFloor(floor.id());
        if(in.floor == block.id() || block.id() == 0 || in.floor == ignore.id()
            || (!floorIsFloor && (in.block == block.id() || in.block == ignore.id()))) return;

        int rad = (int)radius;
        boolean found = false;

        outer:
        for(int x = -rad; x <= rad; x++){
            for(int y = -rad; y <= rad; y++){
                if(x * x + y * y > rad * rad) continue;
                int tile = in.tile(in.x + x, in.y + y);

                if(grid.floor(tile) == block.id() || grid.block(tile) == block.id() || grid.overlay(tile) == block.id()){
                    found = true;
                    break outer;
                }
            }
        }

        if(found){
            if(!floorIsFloor){
                in.block = floor.id();
            }else{
                in.floor = floor.id();
            }
        }
    }
}
