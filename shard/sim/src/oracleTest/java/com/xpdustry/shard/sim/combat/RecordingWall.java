package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.sim.building.*;

import java.util.*;

/** A wall that logs every damage it takes, after taking it, as "x,y health": the Shard side of {@link BulletFixtureOracleTest}. */
public class RecordingWall extends Building{
    /** Every recording wall's damage, in order. Tests clear it. */
    public static final List<String> LOG = new ArrayList<>();

    @Override
    public void damage(int sourceTeam, float damage){
        super.damage(sourceTeam, damage);
        LOG.add(world().tiles().x(tile()) + "," + world().tiles().y(tile()) + " " + health);
    }
}
