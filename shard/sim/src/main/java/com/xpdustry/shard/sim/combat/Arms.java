package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.unit.*;

import java.util.*;

/**
 * A unit type's weapons, as {@link Weapon}s in mount order, and the {@code UnitType} fields combat reads beside them.
 * A hand-built type (test content) has no weapons and a fresh {@code UnitType}'s values. Immutable; the world's
 * {@link Armory} keeps one per unit type.
 */
public final class Arms{
    private static final Arms NONE = new Arms();

    private final Weapon[] weapons;
    private final float knockbackMultiplier, aimDst;
    private final boolean targetAir, targetGround, targetUnderBlocks;

    private Arms(){
        weapons = new Weapon[0];
        knockbackMultiplier = 1f;
        aimDst = -1f;
        targetAir = targetGround = targetUnderBlocks = true;
    }

    Arms(UnitDef type, Weapon[] weapons){
        this.weapons = weapons;
        knockbackMultiplier = type.knockbackMultiplier();
        aimDst = type.aimDst();
        targetAir = type.targetAir();
        targetGround = type.targetGround();
        targetUnderBlocks = type.targetUnderBlocks();
    }

    static Arms none(){
        return NONE;
    }

    /** The arms of {@code unit}'s type. */
    public static Arms of(Unit unit){
        return Armory.of(unit.world()).arms(unit.type());
    }

    /** Weapon {@code index}, the one mount {@code index} holds. */
    public Weapon weapon(int index){
        return weapons[index];
    }

    public int size(){
        return weapons.length;
    }

    public List<Weapon> weapons(){
        return List.of(weapons);
    }

    public float knockbackMultiplier(){
        return knockbackMultiplier;
    }

    /** {@code aimDst}: the shortest aim, so weapons don't turn to aim at the unit itself. */
    public float aimDst(){
        return aimDst;
    }

    public boolean targetAir(){
        return targetAir;
    }

    public boolean targetGround(){
        return targetGround;
    }

    public boolean targetUnderBlocks(){
        return targetUnderBlocks;
    }
}
