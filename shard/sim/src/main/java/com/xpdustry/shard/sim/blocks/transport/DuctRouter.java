package com.xpdustry.shard.sim.blocks.transport;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code DuctRouter} (DuctRouter.java): takes one item from behind and, after {@code speed} ticks, gives it
 * to {@link #target()}.
 */
@Kind("duct-router")
public final class DuctRouter extends DuctRouterBuild{
    @Override
    public void updateTile(){
        float speed = speed();
        progress += edelta() / speed * 2f;
        if(current != -1){
            if(progress >= (1f - 1f / speed)){
                Building target = target();
                if(target != null){
                    target.handleItem(this, current);
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

    @Override
    public boolean acceptItem(Building source, int item){
        return current == -1 && items.total() == 0
            && Blending.relativeTo(world(), Blending.facingEdge(source, this), tileX(), tileY()) == rotation;
    }
}
