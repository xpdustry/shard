package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Sorter.SorterBuild} (Sorter.java): the sorter and the inverted sorter. It holds nothing: an item
 * is passed on as it arrives, straight through when it is the sort item (the inverted sorter: when it isn't), else to
 * whichever side takes it, alternating per input direction when both do. Logic {@code enabled} false swaps the two
 * cases. Two instant-transfer blocks never pass through it to each other. The alternation is kept in the bits of
 * {@code rotation}, as vanilla does, so it is saved with the base.
 */
@Kind("sorter")
public final class Sorter extends Building implements Checkpointable{
    static final Configs<Sorter> CONFIGS = Configs.of(Sorter.class)
        .on(ContentType.ITEM, (sorter, item) -> sorter.sortItem = item)
        .clear(sorter -> sorter.sortItem = -1);

    /** The item it sorts, or -1. */
    public int sortItem = -1;

    private boolean invert(){
        return block().baked() && block().fields().b("invert");
    }

    @Override
    public boolean acceptItem(Building source, int item){
        Building to = getTileTarget(item, source, false);
        return to != null && to.acceptItem(this, item) && to.team == team;
    }

    @Override
    public void handleItem(Building source, int item){
        getTileTarget(item, source, true).handleItem(this, item);
    }

    /** {@code isSame(other)}: an instant-transfer block. */
    public boolean isSame(Building other){
        return other != null && other.info().instantTransfer;
    }

    /** {@code getTileTarget(item, source, flip)}: where an item from {@code source} goes, or null. */
    public Building getTileTarget(int item, Building source, boolean flip){
        int dir = source.relativeTo(tileX(), tileY());
        if(dir == -1) return null;
        Building to;
        if(((item == sortItem) != invert()) == enabled){
            // prevent 3-chains
            if(isSame(source) && isSame(nearby(dir))) return null;
            to = nearby(dir);
        }else{
            Building a = nearby(Mathf.mod(dir - 1, 4));
            Building b = nearby(Mathf.mod(dir + 1, 4));
            boolean sourceInstant = source.info().instantTransfer;
            boolean ac = a != null && !(a.info().instantTransfer && sourceInstant) && a.acceptItem(this, item);
            boolean bc = b != null && !(b.info().instantTransfer && sourceInstant) && b.acceptItem(this, item);
            if(ac && !bc){
                to = a;
            }else if(bc && !ac){
                to = b;
            }else if(!bc){
                return null;
            }else{
                to = (rotation & (1 << dir)) == 0 ? a : b;
                if(flip) rotation ^= (1 << dir);
            }
        }
        return to;
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public Object config(){
        return sortItem == -1 ? null : ContentRef.item(sortItem);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("sortItem", sortItem);
    }

    @Override
    public void read(CheckpointIn in){
        sortItem = in.i("sortItem");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        ((Sorter)into).sortItem = sortItem;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(sortItem);
    }
}
