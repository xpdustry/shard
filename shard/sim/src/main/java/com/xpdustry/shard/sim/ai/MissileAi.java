package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code MissileAI} (MissileAI.java), the controller of missile units: flies forward forever, homes on its
 * {@link #shooter}'s aim after its type's {@code homingDelay}, and dies on contact with an enemy building. Its bullets
 * count the shooter as theirs ({@code Weapon.java:510}). It retargets every 4 frames and draws no timers.
 */
public class MissileAi extends AiController{
    /** {@code shooter}: the unit that launched it, or null. */
    public Unit shooter;

    public MissileAi(World world){
        super(world, AiController.MISSILE);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public MissileAi(){
    }

    protected MissileAi(MissileAi source, CopyContext context){
        super(source, context);
        shooter = context.map(source.shooter);
    }

    /** {@code resetTimers()}: one fixed timer, no draws. */
    @Override
    protected void resetTimers(World world){
        timerReset(world, TIMER_TARGET, 5f);
    }

    /** The shooter its bullets credit: the launching unit, else the missile itself. */
    public static Entity shooterOf(Unit unit){
        return unit.controller instanceof MissileAi ai ? ai.shooter : unit;
    }

    @Override
    public void updateMovement(){
        unloadPayloads();
        AiType type = type();

        float time = unit.params().timedKill() ? unit.time : 1000000f;

        if(time >= type.homingDelay && shooter != null && !shooter.dead){
            unit.lookAt(shooter.aimX, shooter.aimY);
        }

        // move forward forever
        unit.moveAt(new Vec2().trns(unit.rotation, type.missileAccelTime <= 0f ? unit.speed()
            : Mathf.pow(Math.min(time / type.missileAccelTime, 1f), 2f) * unit.speed()));

        Building build = buildOn();

        // kill instantly on enemy building contact
        if(build != null && type.targetGround && build.team != unit.team && (build == target || !underBullets(build))){
            unit.hasTarget = true;
            unit.kill();
        }
    }

    @Override
    public Entity target(float x, float y, float range, boolean air, boolean ground){
        Building aimed = shooter == null ? null : buildWorld(shooter.aimX, shooter.aimY);
        return Targets.closestTarget(unit.world(), unit.team, x, y, range, u -> Targets.checkTarget(u, air, ground) && !u.params().timedKill(),
            t -> ground && (!underBullets(t) || (shooter != null && t == aimed)));
    }

    private Building buildWorld(float x, float y){
        TileGrid tiles = unit.world().tiles();
        int tx = Math.round(x / TILESIZE), ty = Math.round(y / TILESIZE);
        return tiles.inBounds(tx, ty) ? unit.world().buildings().at(tiles.pos(tx, ty)) : null;
    }

    /** More frequent retargeting, due to high speed. */
    @Override
    public boolean retarget(){
        return timerGet(TIMER_TARGET, 4f);
    }

    @Override
    public UnitController copy(CopyContext context){
        return new MissileAi(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        super.hash(hasher);
        hasher.i(shooter == null ? -1 : shooter.id());
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.ref("shooter", shooter);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        shooter = in.ref("shooter", Unit.class);
    }
}
