package com.xpdustry.shard.sim.blocks.liquid;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code DirectionLiquidBridge.DuctBridgeBuild} with its {@code DirectionBridge} base
 * (DirectionLiquidBridge.java:48, DirectionBridge.java): the reinforced bridge conduit. A bridge links to the first
 * same-block, same-team bridge within {@code range} tiles ahead and moves its current liquid into it every tick
 * ({@code moveLiquid}); the last bridge of a chain moves it forward once a tick, without leaking. A bridge marks itself
 * in its link's {@link #occupied}, by its rotation, which stops the link taking liquid from that side. It takes a
 * liquid from its own team, from anywhere but its front, while it has a link or a bridge links to it, and while it
 * holds that liquid or less than 0.2 of its current one. Vanilla saves nothing of it.
 *
 * <p>Not ported: drawing.
 */
@Kind("direction-liquid-bridge")
public final class DirectionLiquidBridge extends Building implements Checkpointable{
    /** Per direction, the bridge feeding this one from there, as of its last update, or null. */
    public DirectionLiquidBridge[] occupied = new DirectionLiquidBridge[4];
    /** The link found on the last update, or null. */
    public DirectionLiquidBridge lastLink;
    @Derived("the block's range, read on first use")
    private int bridgeRange = -1;
    @Derived("the block's flow timer index, read on first use")
    private int timerFlow = -1;

    private int bridgeRange(){
        if(bridgeRange == -1) bridgeRange = block().baked() ? block().fields().i("range") : 4;
        return bridgeRange;
    }

    private int timerFlow(){
        if(timerFlow == -1) timerFlow = block().baked() ? block().fields().i("timerFlow") : 0;
        return timerFlow;
    }

    /** {@code findLink()}: the first bridge of this block and team ahead, within range. */
    public DirectionLiquidBridge findLink(){
        for(int i = 1; i <= bridgeRange(); i++){
            Building other = nearby(Geometry.d4x(rotation) * i, Geometry.d4y(rotation) * i);
            if(other instanceof DirectionLiquidBridge build && other.block() == block() && other.team == team) return build;
        }
        return null;
    }

    @Override
    public void updateTile(){
        DirectionLiquidBridge link = lastLink = findLink();
        if(link != null){
            moveLiquid(link, liquids.current());
            link.occupied[rotation % 4] = this;
        }
        if(link == null){
            if(liquids.currentAmount() > 0.0001f && timer(timerFlow(), 1)){
                moveLiquidForward(false, liquids.current());
            }
        }
        for(int i = 0; i < 4; i++){
            if(occupied[i] == null || occupied[i].rotation != i || !occupied[i].isValid() || occupied[i].lastLink != this){
                occupied[i] = null;
            }
        }
    }

    @Override
    public boolean acceptLiquid(Building source, int liquid){
        // only accept if there's an output point, or it comes from a link
        if(findLink() == null && !(source instanceof DirectionLiquidBridge bridge && bridge.findLink() == this)) return false;
        int edge = Blending.facingEdge(source, this);
        int rel = relativeTo(world().tiles().x(edge), world().tiles().y(edge));
        DirectionLiquidBridge from = occupied[(rel + 2) % 4];
        return team == source.team && (liquids.current() == liquid || liquids.get(liquids.current()) < 0.2f) && rel != rotation
            && (from == null || from == source);
    }

    @Override
    public void write(CheckpointOut out){
        out.refs("occupied", occupied, 4).ref("lastLink", lastLink);
    }

    @Override
    public void read(CheckpointIn in){
        occupied = in.refs("occupied", DirectionLiquidBridge.class);
        lastLink = in.ref("lastLink", DirectionLiquidBridge.class);
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (DirectionLiquidBridge)into;
        for(int i = 0; i < 4; i++) out.occupied[i] = occupied[i] == null ? null : context.map(occupied[i]);
        out.lastLink = lastLink == null ? null : context.map(lastLink);
        out.bridgeRange = bridgeRange;
        out.timerFlow = timerFlow;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        for(DirectionLiquidBridge bridge : occupied) hasher.i(bridge == null ? -1 : bridge.id());
        hasher.i(lastLink == null ? -1 : lastLink.id());
    }
}
