package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The static half of vanilla's {@code PayloadBlock} (PayloadBlock.java): what payload blocks share that isn't a
 * building's. {@code blends} only draws, so it isn't ported.
 */
public final class PayloadBlock{
    /** {@code pushOutput}'s progress threshold. */
    private static final float PUSH_THRESHOLD = 0.55f;

    private PayloadBlock(){
    }

    /**
     * {@code pushOutput(payload, progress)}: past 55% of the way out, a payload pushes the grounded units of its leg
     * kind that its square overlaps away from its centre, harder as it goes, by at most one unit a tick.
     *
     * @return the last push, which vanilla leaves in {@code Tmp.v1}; null when nothing was pushed. Vanilla quirk:
     *         {@code moveOutPayload}'s destination is that same {@code Tmp.v1}, so a push replaces it
     */
    public static Vec2 pushOutput(World world, Payload payload, float progress){
        if(progress < PUSH_THRESHOLD) return null;
        SpatialIndex index = SpatialIndex.of(world);
        if(index == null) return null;
        Vec2[] last = {null};
        boolean legStep = payload instanceof Payload.UnitPayload u && u.unit().params().allowLegStep();
        float size = payload.size(), radius = size / 2f, x = payload.x(), y = payload.y();
        float scl = Mathf.clamp(((progress - PUSH_THRESHOLD) / (1f - PUSH_THRESHOLD)) * 1.1f);
        index.intersect(x - size / 2f, y - size / 2f, size, size, u -> {
            float dst = u.dst(x, y);
            float rs = radius + u.hitSize / 2f;
            if(u.isGrounded() && u.params().allowLegStep() == legStep && dst < rs){
                Vec2 push = new Vec2(u.x - x, u.y - y).setLength(Math.min(rs - dst, 1f)).scl(scl);
                u.vel.add(push);
                last[0] = push;
            }
        });
        return last[0];
    }
}
