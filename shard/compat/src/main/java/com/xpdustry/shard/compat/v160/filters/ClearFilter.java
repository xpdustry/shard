package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.content.*;

/** Vanilla's {@code ClearFilter}: replaces a target block, floor or overlay, with air clearing only overlays and walls. */
public record ClearFilter(BlockDef target, BlockDef replace, BlockDef ignore) implements GenerateFilter{
    @Override
    public void apply(GenerateInput in){
        FilterGrid grid = in.grid;
        if(ignore.id() != 0 && (in.block == ignore.id() || in.floor == ignore.id() || in.overlay == ignore.id())) return;

        if(in.block == target.id() || in.floor == target.id() || (grid.isOverlay(target.id()) && in.overlay == target.id())){
            // special case: when air is the result, replace only the overlay or wall
            if(replace.id() == 0){
                if(in.overlay == target.id()){
                    in.overlay = 0;
                }else{
                    in.block = 0;
                }
            }else if(grid.isOverlay(replace.id())){ // replace the best match based on type
                in.overlay = replace.id();
            }else if(grid.isFloor(replace.id())){
                in.floor = replace.id();
            }else{
                in.block = replace.id();
            }
        }
    }
}
