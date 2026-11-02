package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;

/**
 * Vanilla's {@code OreMedianFilter}: drops ores not on a full 2x2 cell of the same ore over non-static blocks, then
 * takes the given percentile of the overlay ids in a circle, spawns excluded.
 */
public record OreMedianFilter(float radius, float percentile) implements GenerateFilter{
    @Override
    public boolean buffered(){
        return true;
    }

    @Override
    public void apply(GenerateInput in){
        FilterGrid grid = in.grid;
        if(in.overlay == grid.spawn) return;

        int cx = (in.x / 2) * 2;
        int cy = (in.y / 2) * 2;
        if(in.overlay != 0){
            int a = in.tile(cx + 1, cy), b = in.tile(cx, cy), c = in.tile(cx + 1, cy + 1), d = in.tile(cx, cy + 1);
            if(!(grid.overlay(a) == in.overlay && grid.overlay(b) == in.overlay && grid.overlay(c) == in.overlay && grid.overlay(d) == in.overlay
                && !grid.isStatic(grid.block(a)) && !grid.isStatic(grid.block(b)) && !grid.isStatic(grid.block(c)) && !grid.isStatic(grid.block(d)))){
                in.overlay = 0;
            }
        }

        int rad = (int)radius;

        var blocks = new IntSeq();
        for(int x = -rad; x <= rad; x++){
            for(int y = -rad; y <= rad; y++){
                if(Mathf.dst2(x, y) > rad * rad) continue;

                int tile = in.tile(in.x + x, in.y + y);
                if(grid.overlay(tile) != grid.spawn) blocks.add(grid.overlay(tile));
            }
        }

        blocks.sort();

        int index = Math.min((int)(blocks.size * percentile), blocks.size - 1);
        // vanilla turns the id back into a block with content.block (FilterGrid.byId)
        in.overlay = grid.byId(blocks.get(index));
    }
}
