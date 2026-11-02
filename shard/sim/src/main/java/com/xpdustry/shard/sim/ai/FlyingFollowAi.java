package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code FlyingFollowAI} (FlyingFollowAI.java), for wave support flyers: follows the toughest nearby unit of
 * its team that isn't a follower or of its own type, and fights what that unit meets. Outside the wave team, or in
 * PvP, it hands over to a {@link FlyingAi}, made (and its timers drawn) the first time.
 */
public class FlyingFollowAi extends FlyingAi{
    public static final String KIND = "flying-follow-ai";

    /** {@code following}: the unit (or building) it escorts, or null. */
    public Entity following;

    public FlyingFollowAi(World world){
        super(world, KIND);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public FlyingFollowAi(){
    }

    protected FlyingFollowAi(FlyingFollowAi source, CopyContext context){
        super(source, context);
        following = context.map(source.following);
    }

    @Override
    public void updateMovement(){
        unloadPayloads();
        AiType type = type();

        if(following != null){
            moveTo(Targets.x(following), Targets.y(following), Targets.hitSize(following) / 2f * 1.1f + unit.hitSize / 2f + 15f, 50f);
        }else if(target != null && type.hasWeapons){
            moveTo(Targets.x(target), Targets.y(target), 80f);
        }

        if(shouldFaceTarget()){
            unit.lookAt(Targets.x(target), Targets.y(target));
        }else if(following != null){
            unit.lookAt(Targets.x(following), Targets.y(following));
        }

        if(timerGet(TIMER_TARGET3, 30f)){
            SpatialIndex index = SpatialIndex.of(unit.world());
            Unit self = unit;
            following = index == null ? null : index.closest(unit.team, unit.x, unit.y, Math.max(type.typeRange, 400f),
                u -> !u.dead && !(u.controller instanceof FlyingFollowAi) && u.type() != self.type(),
                (u, tx, ty) -> -u.maxHealth + Mathf.dst2(u.x, u.y, tx, ty) / 6400f);
        }
    }

    /** {@code shouldFaceTarget()}. */
    public boolean shouldFaceTarget(){
        return target != null && (following == null || Targets.within(target, unit.x, unit.y, type().range));
    }

    @Override
    public void updateVisuals(){
        if(unit.isFlying()){
            if(type().wobble) wobble(unit);
            if(!shouldFaceTarget()) unit.lookAt(prefRotation(unit));
        }
    }

    @Override
    public AiController fallback(){
        return new FlyingAi(unit.world());
    }

    /** Only AI teams use this controller. */
    @Override
    public boolean useFallback(){
        Rules rules = UnitCalls.rules(unit.world());
        return rules.pvp || rules.waveTeam != unit.team;
    }

    @Override
    public UnitController copy(CopyContext context){
        return new FlyingFollowAi(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        super.hash(hasher);
        hasher.i(following == null ? -1 : following.id());
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.ref("following", following);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        following = in.ref("following", Entity.class);
    }
}
