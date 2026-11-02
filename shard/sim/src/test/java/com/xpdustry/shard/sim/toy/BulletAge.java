package com.xpdustry.shard.sim.toy;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Moves bullets and removes them at the end of their life, mid-iteration, sometimes splitting one into a frag that
 * the same pass visits. Only acts when {@link Chaos} is enabled.
 */
@Register
public final class BulletAge implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.BULLETS;
    }

    @Override
    public void run(World world){
        if(!world.part(Chaos.class).enabled) return;
        Bullets bullets = world.bullets();
        bullets.each(row -> {
            bullets.x[row] += bullets.velX[row];
            bullets.y[row] += bullets.velY[row];
            bullets.time[row] += 1f;
            if(bullets.time[row] >= bullets.lifetime[row]){
                if(bullets.type[row] == 0 && world.rand().chance(0.5)){
                    bullets.add(world.ids(), 1, bullets.team[row], bullets.x[row], bullets.y[row], -bullets.velY[row],
                        bullets.velX[row], 5f, bullets.damage[row] / 2f, bullets.owner[row]);
                }
                bullets.remove(row);
            }
        });
    }
}
