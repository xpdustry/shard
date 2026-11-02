package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Scripted controllers for fixture enemies, the same on both sides: walk toward a point at the unit's own speed
 * ({@code movePref}), facing it, then stand. A unit whose goal is where it stands never moves.
 */
final class Walkers{
    private Walkers(){
    }

    static final class VanillaWalker implements mindustry.entities.units.UnitController{
        final float tx, ty;
        mindustry.gen.Unit unit;

        VanillaWalker(float tx, float ty){
            this.tx = tx;
            this.ty = ty;
        }

        @Override
        public void unit(mindustry.gen.Unit unit){
            this.unit = unit;
        }

        @Override
        public mindustry.gen.Unit unit(){
            return unit;
        }

        @Override
        public void updateUnit(){
            if(unit.within(tx, ty, 2f)) return;
            unit.movePref(new arc.math.geom.Vec2(tx - unit.x, ty - unit.y).limit(unit.speed()));
            unit.lookAt(tx, ty);
        }
    }

    static final class ShardWalker implements UnitController{
        final float tx, ty;

        ShardWalker(float tx, float ty){
            this.tx = tx;
            this.ty = ty;
        }

        @Override
        public void updateUnit(Unit unit){
            if(com.xpdustry.shard.common.math.Mathf.within(unit.x, unit.y, tx, ty, 2f)) return;
            unit.movePref(new com.xpdustry.shard.common.geom.Vec2(tx - unit.x, ty - unit.y).limit(unit.speed()));
            unit.lookAt(tx, ty);
        }

        @Override
        public UnitController copy(CopyContext context){
            return this;
        }

        @Override
        public void hash(Hasher hasher){
        }
    }
}
