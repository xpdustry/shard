package com.xpdustry.shard.sim.blocks.transport;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Duct.DuctBuild} (Duct.java): Erekir's duct and armored duct. It holds one item, which takes
 * {@code speed} ticks to cross ({@link #progress} runs from -1 to {@code 1 - 1 / speed}) and then moves forward. A duct
 * takes items from behind and the sides, never from the building it feeds; the armored duct only from behind, or
 * from a duct pointing into it. Disabled by logic, a duct doesn't update.
 *
 * <p>Not ported: drawing state ({@code blendbits}, the caps, {@code prev}) and logic sensing.
 */
@Kind("duct")
public final class Duct extends Building implements Checkpointable{
    public float progress;
    /** The item held, or -1. */
    public int current = -1;
    /** The direction the item came from, saved for drawing. */
    public int recDir;
    /** The building in front, as of the last proximity update, or null. */
    public Building next;
    @Derived("the block's speed, read on first use")
    private float speed = Float.NaN;

    private float speed(){
        if(Float.isNaN(speed)) speed = block().baked() ? block().fields().f("speed") : 5f;
        return speed;
    }

    private boolean armored(){
        return block().baked() && block().fields().b("armored");
    }

    static boolean isDuct(BlockDef block){
        return block.baked() && block.fields().b("isDuct");
    }

    /** {@code sensor @progress} (Duct.java:291). */
    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return progress;
        return super.sense(sensor);
    }

    @Override
    public void updateTile(){
        float speed = speed();
        progress += edelta() / speed * 2f;
        if(current != -1 && next != null){
            if(progress >= (1f - 1f / speed) && moveForward(current)){
                items.remove(current, 1);
                current = -1;
                progress %= (1f - 1f / speed);
            }
        }else{
            progress = 0;
        }
        if(current == -1 && items.total() > 0) current = items.first();
    }

    @Override
    public boolean acceptItem(Building source, int item){
        if(current != -1 || items.total() != 0) return false;
        int facing = Blending.relativeTo(world(), Blending.facingEdge(source, this), tileX(), tileY());
        if(armored()){
            return (source.block().rotate() && source.front() == this && source.block().hasItems() && isDuct(source.block())) || facing == rotation;
        }
        // standard acceptance: not from the front
        return !(source.block().rotate() && next == source) && Math.abs(facing - rotation) != 2;
    }

    @Override
    public int removeStack(int item, int amount){
        int removed = super.removeStack(item, amount);
        if(item == current) current = -1;
        return removed;
    }

    @Override
    public void handleStack(int item, int amount, int sourceTeam){
        super.handleStack(item, amount, sourceTeam);
        current = item;
    }

    @Override
    public void handleItem(Building source, int item){
        current = item;
        progress = -1f;
        int edge = Blending.facingEdge(source, this);
        recDir = relativeTo(world().tiles().x(edge), world().tiles().y(edge));
        items.add(item, 1);
        noSleep();
    }

    @Override
    public void onProximityUpdate(){
        super.onProximityUpdate();
        next = front();
    }

    /** {@code read}'s last line: the item held is the first one. */
    public void loaded(){
        current = items.first();
    }

    @Override
    public void write(CheckpointOut out){
        out.f("progress", progress).i("current", current).i("recDir", recDir).ref("next", next);
    }

    @Override
    public void read(CheckpointIn in){
        progress = in.f("progress");
        current = in.i("current");
        recDir = in.i("recDir");
        next = in.ref("next", Building.class);
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Duct)into;
        out.progress = progress;
        out.current = current;
        out.recDir = recDir;
        out.next = next == null ? null : context.map(next);
        out.speed = speed;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(progress).i(current).i(recDir).i(next == null ? -1 : next.id());
    }
}
