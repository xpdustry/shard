package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;

/** Vanilla's {@code MedianFilter}: each tile takes the given percentile of the floor and block ids in a circle. */
public record MedianFilter(float radius, float percentile) implements GenerateFilter{
    @Override
    public boolean buffered(){
        return true;
    }

    @Override
    public void apply(GenerateInput in){
        FilterGrid grid = in.grid;
        int rad = (int)radius;
        var blocks = new IntSeq();
        var floors = new IntSeq();
        for(int x = -rad; x <= rad; x++){
            for(int y = -rad; y <= rad; y++){
                if(Mathf.dst2(x, y) > rad * rad) continue;

                int tile = in.tile(in.x + x, in.y + y);
                blocks.add(grid.block(tile));
                floors.add(grid.floor(tile));
            }
        }

        floors.sort();
        blocks.sort();

        // vanilla turns the ids back into blocks with content.block (FilterGrid.byId)
        int floor = grid.byId(floors.get(Math.min((int)(floors.size * percentile), floors.size - 1)));
        int block = grid.byId(blocks.get(Math.min((int)(blocks.size * percentile), blocks.size - 1)));

        in.floor = floor;
        if(!grid.isSynthetic(block) && !grid.isSynthetic(in.block)) in.block = block;
    }
}
