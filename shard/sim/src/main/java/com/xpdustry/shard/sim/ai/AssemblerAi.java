package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code AssemblerAI} (AssemblerAI.java), the assembly drone's: it flies to the spot its unit assembler gives
 * it ({@link #targetPos}, set every assembler update) and, once within 5 units, faces {@link #targetAngle}. The
 * assembler counts a drone {@link #inPosition()} within 10 units and 15 degrees. Until an assembler sets a spot, the
 * target is the origin and the drone stays put.
 *
 * <p>The wire tells it apart from other AIs ({@code TypeIO.writeController} type 5), but sends none of its state; the
 * assembler sets both fields again on its next update.
 */
public class AssemblerAi extends AiController{
    public static final String KIND = AiController.ASSEMBLER;

    /** {@code targetPos}: where the drone holds, zero for nowhere. */
    public final Vec2 targetPos = new Vec2();
    /** {@code targetAngle}: the rotation it holds there. */
    public float targetAngle;

    public AssemblerAi(World world){
        super(world, KIND);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public AssemblerAi(){
    }

    protected AssemblerAi(AssemblerAi source, CopyContext context){
        super(source, context);
        targetPos.set(source.targetPos);
        targetAngle = source.targetAngle;
    }

    @Override
    public void updateMovement(){
        if(!targetPos.isZero()){
            moveTo(targetPos.x, targetPos.y, 1f, 3f);
        }

        if(unit.within(targetPos.x, targetPos.y, 5f)){
            unit.lookAt(targetAngle);
        }
    }

    /** {@code inPosition()}: near its spot and facing about the right way. */
    public boolean inPosition(){
        return unit.within(targetPos.x, targetPos.y, 10f) && Angles.within(unit.rotation, targetAngle, 15f);
    }

    // state

    @Override
    public UnitController copy(CopyContext context){
        return new AssemblerAi(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        super.hash(hasher);
        hasher.f(targetPos.x).f(targetPos.y).f(targetAngle);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.obj("targetPos", targetPos).f("targetAngle", targetAngle);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        targetPos.set(in.obj("targetPos", Vec2.class));
        targetAngle = in.f("targetAngle");
    }

    /** Makes {@code assembler-ai} controllers. */
    @Register
    public static final class Kinds implements AiKinds{
        @Override
        public AiController create(World world, String kind){
            return KIND.equals(kind) ? new AssemblerAi(world) : null;
        }
    }
}
