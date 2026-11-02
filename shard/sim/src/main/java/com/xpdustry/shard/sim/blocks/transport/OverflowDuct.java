package com.xpdustry.shard.sim.blocks.transport;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code OverflowDuct.OverflowDuctBuild} (OverflowDuct.java): the overflow and underflow ducts. It takes one
 * item from behind and, after {@code speed} ticks, passes it forward, else to a side (the underflow duct: to a side,
 * else forward), alternating sides through {@code cdump} (0 or 2). Disabled by logic, it doesn't update. Vanilla
 * saves nothing of it: a loaded duct picks its item up again on its first update.
 */
@Kind("overflow-duct")
public final class OverflowDuct extends Building implements Checkpointable{
    public float progress;
    /** The item held, or -1. */
    public int current = -1;
    @Derived("the block's speed, read on first use")
    private float speed = Float.NaN;

    private float speed(){
        if(Float.isNaN(speed)) speed = block().baked() ? block().fields().f("speed") : 5f;
        return speed;
    }

    private boolean invert(){
        return block().baked() && block().fields().b("invert");
    }

    @Override
    public void updateTile(){
        float speed = speed();
        progress += edelta() / speed * 2f;
        if(current != -1){
            if(progress >= (1f - 1f / speed)){
                Building target = target();
                if(target != null){
                    target.handleItem(this, current);
                    cdump = (byte)(cdump == 0 ? 2 : 0);
                    items.remove(current, 1);
                    current = -1;
                    progress %= (1f - 1f / speed);
                }
            }
        }else{
            progress = 0;
        }
        if(current == -1 && items.total() > 0) current = items.first();
    }

    /** {@code target()}: where the held item goes, or null. */
    public Building target(){
        if(current == -1) return null;
        boolean invert = invert();
        if(invert){
            Building l = left(), r = right();
            boolean lc = l != null && l.team == team && l.acceptItem(this, current);
            boolean rc = r != null && r.team == team && r.acceptItem(this, current);
            if(lc && !rc){
                return l;
            }else if(rc && !lc){
                return r;
            }else if(lc && rc){
                return cdump == 0 ? l : r;
            }
        }
        Building front = front();
        if(front != null && front.team == team && front.acceptItem(this, current)) return front;
        if(invert) return null;
        for(int i = -1; i <= 1; i++){
            int dir = Mathf.mod(rotation + (((i + cdump + 1) % 3) - 1), 4);
            if(dir == rotation) continue;
            Building other = nearby(dir);
            if(other != null && other.team == team && other.acceptItem(this, current)) return other;
        }
        return null;
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return current == -1 && items.total() == 0
            && Blending.relativeTo(world(), Blending.facingEdge(source, this), tileX(), tileY()) == rotation;
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
        items.add(item, 1);
        noSleep();
    }

    @Override
    public void write(CheckpointOut out){
        out.f("progress", progress).i("current", current);
    }

    @Override
    public void read(CheckpointIn in){
        progress = in.f("progress");
        current = in.i("current");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (OverflowDuct)into;
        out.progress = progress;
        out.current = current;
        out.speed = speed;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(progress).i(current);
    }
}
