package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.path.*;

import java.util.*;

/**
 * The {@code UnitType} fields AI controllers read, for one unit type, read once from the content. Immutable; the
 * world's {@link UnitTable} keeps one per type. A hand-built type (test content) has a fresh {@code UnitType}'s values.
 */
public final class AiType{
    /** {@code maxRange}: {@code unit.range()}. */
    public final float range;
    /** {@code range}: the attack range some AIs read directly ({@code unit.type.range}). */
    public final float typeRange;
    public final float circleTargetRadius, homingDelay, missileAccelTime;
    public final boolean circleTarget, faceTarget, singleTarget, autoDropBombs, rotateMoveFirst, wobble, autoFindTarget, rotateToBuilding;
    /** {@code buildRange}. */
    public final float buildRange;
    public final boolean targetAir, targetGround, targetUnderBlocks, hasWeapons, isEnemy;
    /** {@code boostWhenMining}, {@code boostWhenBuilding}, {@code canAttack}: what the support AIs read. */
    public final boolean boostWhenMining, boostWhenBuilding, canAttack;
    /** {@code targetFlags}, in order; a null entry means "the closest target". */
    public final BlockFlag[] targetFlags;
    /** {@code flowfieldPathType}: the wave-AI field class ({@code UnitType.init}). */
    public final PathCost flowCost;
    /** {@code pathCost}: the RTS field class ({@code ControlPathfinder}). */
    public final PathCost pathCost;

    AiType(UnitDef type){
        boolean baked = type.baked();
        range = baked ? type.maxRange() : 0f;
        typeRange = baked ? type.range() : 0f;
        circleTargetRadius = baked ? type.circleTargetRadius() : 80f;
        homingDelay = baked ? type.homingDelay() : 10f;
        missileAccelTime = baked ? type.missileAccelTime() : 0f;
        circleTarget = baked && type.circleTarget();
        faceTarget = !baked || type.faceTarget();
        singleTarget = baked && type.singleTarget();
        autoDropBombs = baked && type.autoDropBombs();
        rotateMoveFirst = baked && type.rotateMoveFirst();
        wobble = !baked || type.wobble();
        autoFindTarget = !baked || type.autoFindTarget();
        rotateToBuilding = !baked || type.rotateToBuilding();
        buildRange = baked ? type.buildRange() : 220f;
        targetAir = !baked || type.targetAir();
        targetGround = !baked || type.targetGround();
        targetUnderBlocks = !baked || type.targetUnderBlocks();
        hasWeapons = baked && !type.weapons().isEmpty();
        isEnemy = !baked || type.isEnemy();
        boostWhenMining = !baked || type.boostWhenMining();
        boostWhenBuilding = !baked || type.boostWhenBuilding();
        canAttack = !baked || type.canAttack();
        if(baked){
            List<String> flags = type.targetFlags();
            targetFlags = new BlockFlag[flags.size()];
            for(int i = 0; i < targetFlags.length; i++) targetFlags[i] = flags.get(i) == null ? null : BlockFlag.named(flags.get(i));
        }else{
            targetFlags = new BlockFlag[]{null};
        }
        flowCost = baked ? PathCost.of(type.naval(), type.allowLegStep(), type.hovering()) : PathCost.GROUND;
        pathCost = flowCost;
    }
}
