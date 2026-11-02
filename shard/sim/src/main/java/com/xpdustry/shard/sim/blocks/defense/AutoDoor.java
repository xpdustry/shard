package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code AutoDoor.AutoDoorBuild} (AutoDoor.java): a door that opens for its own team's ground units. Every
 * {@code checkInterval} ticks it looks for a grounded unit that can't step over walls within {@code triggerMargin} of
 * its edges, and when the answer differs from {@link #open} the server toggles it ({@link DefenseEvent.AutoDoorToggled},
 * vanilla's {@code Call.autoDoorToggle}). Enemies never open it; its own team passes it ({@code teamPassable}).
 *
 * <p>Vanilla bug, kept: its constructor staggers the toggle timer with a draw from the generator (AutoDoor.java:55), but
 * {@code create()} then gives the building a new timer, so the draw is made ({@link Building#drawFields}) and lost, and
 * doors placed together check on the same ticks.
 */
@Kind("auto-door")
public final class AutoDoor extends Building implements Checkpointable{
    public boolean open;
    @Derived("the block's timerToggle, checkInterval and triggerMargin, read on first use")
    private float[] params;

    @Override
    public void updateTile(){
        float[] p = params();
        if(timer(timerToggle(), p[1])){
            float side = block().size() * Footprint.TILE_SIZE + p[2] * 2f;
            boolean[] shouldOpen = {false};
            SpatialIndex units = SpatialIndex.of(world());
            if(units != null){
                units.nearby(team, x() - side / 2f, y() - side / 2f, side, side, unit -> {
                    if(unit.isGrounded() && !unit.params().allowLegStep()) shouldOpen[0] = true;
                });
            }
            if(open != shouldOpen[0]) setOpen(shouldOpen[0]);
        }
    }

    /** {@code Call.autoDoorToggle(tile, open)}: the handler's {@code setOpen}, run here, then the Call. */
    public void setOpen(boolean open){
        this.open = open;
        // pathfinder.updateTile(tile) and recache: the grid's solidity follows
        world().buildings().solidityChanged(this);
        world().publish(new DefenseEvent.AutoDoorToggled(world().ticks(), tile(), open));
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.enabled) return open ? 1 : 0;
        return super.sense(sensor);
    }

    @Override
    public boolean checkSolid(){
        return !open;
    }

    private int timerToggle(){
        return (int)params()[0];
    }

    /** {@code timerToggle}, {@code checkInterval}, {@code triggerMargin}. */
    private float[] params(){
        if(params == null){
            var fields = block().fields();
            params = block().baked()
                ? new float[]{fields.i("timerToggle"), fields.f("checkInterval"), fields.f("triggerMargin")}
                : new float[]{1, 20f, 12f};
        }
        return params;
    }

    @Override
    public void write(CheckpointOut out){
        out.bool("open", open);
    }

    @Override
    public void read(CheckpointIn in){
        open = in.bool("open");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (AutoDoor)into;
        out.open = open;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bool(open);
    }
}
