package com.xpdustry.shard.sim.blocks.logic;

import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code LogicDisplay.LogicDisplayBuild} (LogicDisplay.java): the logic display and the large one. A
 * headless server draws nothing ({@code DrawI} returns before it buffers a command), so its draw queue stays empty and
 * a {@code drawflush} only counts an {@code operation}, which processors can sense; clients draw their own. The
 * save keeps the transform ({@code Mat}, 9 floats) that a client's {@code draw translate/scale/rotate} left, which a
 * server never changes.
 */
@Kind("logic-display")
public final class LogicDisplay extends Building implements Checkpointable, LogicDrawable{
    /** {@code operations}: flushes so far. */
    public long operations;
    /** The saved {@code transform}'s values, or null. */
    public float[] transform;

    /** {@code displaySize}. */
    int displaySize(){
        return block().baked() ? block().fields().i("displaySize") : 64;
    }

    @Override
    public double sense(LAccess sensor){
        return switch(sensor){
            case displayWidth, displayHeight -> displaySize();
            // the draw queue: always empty on a server
            case bufferSize -> 0;
            case operations -> operations;
            default -> super.sense(sensor);
        };
    }

    @Override
    public boolean drawable(Processor exec){
        return isValid() && (exec.privileged || (team == exec.team() && !block().privileged()));
    }

    @Override
    public void drawFlush(){
        operations++;
    }

    @Override
    public void write(CheckpointOut out){
        out.l("operations", operations).floats("transform", transform);
    }

    @Override
    public void read(CheckpointIn in){
        operations = in.l("operations");
        transform = in.floats("transform");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (LogicDisplay)into;
        out.operations = operations;
        out.transform = transform == null ? null : transform.clone();
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.l(operations).bool(transform != null);
        if(transform != null) hasher.floats(transform);
    }
}
