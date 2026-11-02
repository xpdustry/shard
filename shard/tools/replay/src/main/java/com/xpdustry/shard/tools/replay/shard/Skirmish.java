package com.xpdustry.shard.tools.replay.shard;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A scripted flying unit (Shard's AI is WP54): flies at the enemy core, circles it, flies home and back. It steers
 * as {@code FlyingTraceOracleTest}'s script does and keeps its state in the world (cores by tile, the world tick),
 * so a fork or a restored checkpoint flies on exactly as the original.
 */
public final class Skirmish implements UnitController, Checkpointable{
    /** The tiles of the unit's own core and of the core it attacks. */
    public int home, enemy;
    /** Offsets the unit's orbit and timing, so a squad spreads out. */
    public int phase;

    public Skirmish(){
    }

    Skirmish(int home, int enemy, int phase){
        this.home = home;
        this.enemy = enemy;
        this.phase = phase;
    }

    @Override
    public void updateUnit(Unit unit){
        World world = unit.world();
        long tick = world.ticks() + phase;
        boolean attack = tick % 2400 < 1600;
        Building goal = world.buildings().at(attack ? enemy : home);
        if(goal == null) goal = world.buildings().at(home);
        if(goal == null){
            unit.approach(new Vec2());
            return;
        }
        float orbit = tick * 0.02f * Mathf.radDeg;
        float radius = attack ? 60f + phase % 40 : 30f + phase % 20;
        float gx = goal.x() + Angles.trnsx(orbit, radius), gy = goal.y() + Angles.trnsy(orbit, radius);
        unit.movePref(new Vec2(gx, gy).sub(unit.x, unit.y).limit(unit.speed()));
        unit.lookAt(attack ? goal.x() : gx, attack ? goal.y() : gy);
    }

    @Override
    public UnitController copy(CopyContext context){
        return new Skirmish(home, enemy, phase);
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(home).i(enemy).i(phase);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("home", home).i("enemy", enemy).i("phase", phase);
    }

    @Override
    public void read(CheckpointIn in){
        home = in.i("home");
        enemy = in.i("enemy");
        phase = in.i("phase");
    }
}
