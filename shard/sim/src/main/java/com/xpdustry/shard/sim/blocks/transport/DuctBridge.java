package com.xpdustry.shard.sim.blocks.transport;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code DuctBridge.DuctBridgeBuild} with its {@code DirectionBridge} base (DirectionBridge.java,
 * DuctBridge.java): a bridge links to the first same-block, same-team bridge within {@code range} tiles ahead and
 * sends it one item every {@code speed} ticks while that one has room; the last bridge of a chain moves its items
 * forward. A bridge marks itself in its link's {@link #occupied}, by its rotation, which stops the link taking items
 * from that side. It takes items from anywhere but its front, only while it has a link. Disabled by logic, it doesn't
 * update. Vanilla saves nothing of it.
 */
@Kind("duct-bridge")
public final class DuctBridge extends Building implements Checkpointable{
    /** Per direction, the bridge feeding this one from there, as of its last update, or null. */
    public DuctBridge[] occupied = new DuctBridge[4];
    /** The link found on the last update, or null. */
    public DuctBridge lastLink;
    public float progress;
    @Derived("the block's parameters, read on first use")
    private float speed = Float.NaN;
    @Derived("the block's range, read on first use")
    private int bridgeRange = -1;

    private float speed(){
        if(Float.isNaN(speed)) speed = block().baked() ? block().fields().f("speed") : 5f;
        return speed;
    }

    private int bridgeRange(){
        if(bridgeRange == -1) bridgeRange = block().baked() ? block().fields().i("range") : 4;
        return bridgeRange;
    }

    /** {@code findLink()}: the first bridge of this block and team ahead, within range. */
    public DuctBridge findLink(){
        for(int i = 1; i <= bridgeRange(); i++){
            Building other = nearby(Geometry.d4x(rotation) * i, Geometry.d4y(rotation) * i);
            if(other instanceof DuctBridge build && other.block() == block() && other.team == team) return build;
        }
        return null;
    }

    @Override
    public void updateTile(){
        DuctBridge link = lastLink = findLink();
        if(link != null){
            link.occupied[rotation % 4] = this;
            int capacity = link.info().itemCapacity;
            if(items.any() && link.items.total() < capacity){
                progress += edelta();
                while(progress > speed()){
                    int next = items.take();
                    if(next != -1 && link.items.total() < capacity) link.handleItem(this, next);
                    progress -= speed();
                }
            }
        }
        if(link == null && items.any()){
            int next = items.first();
            if(moveForward(next)) items.remove(next, 1);
        }
        for(int i = 0; i < 4; i++){
            if(occupied[i] == null || occupied[i].rotation != i || !occupied[i].isValid() || occupied[i].lastLink != this){
                occupied[i] = null;
            }
        }
    }

    @Override
    public boolean acceptItem(Building source, int item){
        // only accept if there's an output point
        if(findLink() == null) return false;
        int edge = Blending.facingEdge(source, this);
        int rel = relativeTo(world().tiles().x(edge), world().tiles().y(edge));
        return items.total() < info().itemCapacity && rel != rotation && occupied[(rel + 2) % 4] == null;
    }

    @Override
    public void write(CheckpointOut out){
        out.refs("occupied", occupied, 4).ref("lastLink", lastLink).f("progress", progress);
    }

    @Override
    public void read(CheckpointIn in){
        occupied = in.refs("occupied", DuctBridge.class);
        lastLink = in.ref("lastLink", DuctBridge.class);
        progress = in.f("progress");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (DuctBridge)into;
        for(int i = 0; i < 4; i++) out.occupied[i] = occupied[i] == null ? null : context.map(occupied[i]);
        out.lastLink = lastLink == null ? null : context.map(lastLink);
        out.progress = progress;
        out.speed = speed;
        out.bridgeRange = bridgeRange;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        for(DuctBridge bridge : occupied) hasher.i(bridge == null ? -1 : bridge.id());
        hasher.i(lastLink == null ? -1 : lastLink.id()).f(progress);
    }
}
