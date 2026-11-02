package com.xpdustry.shard.compat.v160.blocks.transport;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Blending}'s table of vanilla's per-class overrides answers as vanilla's blocks do, for every block:
 * {@code outputsItems()}, and {@code rotatedOutput(x, y, destination)} toward an empty tile, which is what a transport
 * block's blending asks of its neighbours' blocks (a stack conveyor's own answer follows its state; the conveyor
 * oracles cover it).
 */
class BlendingOracleTest{
    @Test
    void outputsItemsAndRotatedOutputMatchEveryBlock(){
        Sim.empty(4, 4);
        var shard = new World(WorldConfig.of(BlockRun.EPOCH, 4, 4, 1).withKinds(BlockRun.KINDS));
        Tile destination = world.tile(1, 1);
        var differences = new ArrayList<String>();
        for(Block block : content.blocks()){
            var def = BlockRun.EPOCH.block(block.name);
            boolean outputs = Blending.outputsItems(def), rotated = Blending.rotatedOutput(shard, def, 0, 0);
            if(outputs != block.outputsItems()) differences.add(block.name + " outputsItems " + outputs + " vs vanilla " + block.outputsItems());
            boolean expected = block.rotatedOutput(0, 0, destination);
            if(rotated != expected) differences.add(block.name + " rotatedOutput " + rotated + " vs vanilla " + expected);
        }
        assertEquals(List.of(), differences);
    }
}
