package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code OverflowGate.OverflowGateBuild} (OverflowGate.java): the overflow and underflow gates. It holds
 * nothing: an item goes straight on when the building in front takes it, else to a side, alternating per input
 * direction when both sides do. The underflow gate ({@code invert}) prefers the sides and goes straight on only when
 * neither takes it. Logic {@code enabled} false swaps the two. Two instant-transfer blocks never pass through it to
 * each other. The alternation is kept in the bits of {@code rotation}, as vanilla does.
 */
@Kind("overflow-gate")
public final class OverflowGate extends Building{
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
        Building target = getTileTarget(item, source, true);
        if(target != null) target.handleItem(this, item);
    }

    /** {@code getTileTarget(item, src, flip)}: where an item from {@code src} goes, or null. */
    public Building getTileTarget(int item, Building src, boolean flip){
        // relativeToEdge(src.tile): the direction of src's tile facing this one
        int edge = com.xpdustry.shard.sim.blocks.transport.Blending.facingEdge(src, this);
        int from = relativeTo(world().tiles().x(edge), world().tiles().y(edge));
        if(from == -1) return null;
        Building to = nearby((from + 2) % 4);
        boolean fromInst = src.info().instantTransfer;
        boolean canForward = to != null && to.team == team && !(fromInst && to.info().instantTransfer) && to.acceptItem(this, item);
        boolean inv = invert() == enabled;
        if(!canForward || inv){
            Building a = nearby(Mathf.mod(from - 1, 4));
            Building b = nearby(Mathf.mod(from + 1, 4));
            boolean ac = a != null && !(fromInst && a.info().instantTransfer) && a.team == team && a.acceptItem(this, item);
            boolean bc = b != null && !(fromInst && b.info().instantTransfer) && b.team == team && b.acceptItem(this, item);
            if(!ac && !bc) return inv && canForward ? to : null;
            if(ac && !bc){
                to = a;
            }else if(bc && !ac){
                to = b;
            }else{
                to = (rotation & (1 << from)) == 0 ? a : b;
                if(flip) rotation ^= (1 << from);
            }
        }
        return to;
    }
}
