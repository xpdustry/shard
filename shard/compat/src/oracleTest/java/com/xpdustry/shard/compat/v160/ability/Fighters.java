package com.xpdustry.shard.compat.v160.ability;

import com.xpdustry.shard.common.geom.Vec2;
import com.xpdustry.shard.common.math.Mathf;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Scripted controllers for ability fixtures, the same on both sides: walk toward a point at the unit's own speed
 * ({@code movePref}), then stand; aim at the first living unit of the target team in the fixture's list and fire while
 * it is within range, else face the walk and hold fire.
 */
final class Fighters{
    private Fighters(){
    }

    static final class VanillaFighter implements mindustry.entities.units.UnitController{
        final List<mindustry.gen.Unit> units;
        final float tx, ty, range;
        final int target;
        mindustry.gen.Unit unit;

        VanillaFighter(List<mindustry.gen.Unit> units, float tx, float ty, int target, float range){
            this.units = units;
            this.tx = tx;
            this.ty = ty;
            this.target = target;
            this.range = range;
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
            if(!unit.within(tx, ty, 2f)){
                unit.movePref(new arc.math.geom.Vec2(tx - unit.x, ty - unit.y).limit(unit.speed()));
            }
            mindustry.gen.Unit enemy = null;
            for(var other : units){
                if(other != null && other.isAdded() && !other.dead && other.team.id == target){
                    enemy = other;
                    break;
                }
            }
            boolean shoot = enemy != null && unit.within(enemy, range);
            if(shoot){
                unit.lookAt(enemy.x, enemy.y);
                unit.aim(enemy.x, enemy.y);
            }else if(!unit.within(tx, ty, 2f)){
                unit.lookAt(tx, ty);
            }
            unit.controlWeapons(shoot, shoot);
        }
    }

    static final class ShardFighter implements UnitController{
        final List<Unit> units;
        final float tx, ty, range;
        final int target;

        ShardFighter(List<Unit> units, float tx, float ty, int target, float range){
            this.units = units;
            this.tx = tx;
            this.ty = ty;
            this.target = target;
            this.range = range;
        }

        @Override
        public void updateUnit(Unit unit){
            boolean there = Mathf.within(unit.x, unit.y, tx, ty, 2f);
            if(!there) unit.movePref(new Vec2(tx - unit.x, ty - unit.y).limit(unit.speed()));
            Unit enemy = null;
            for(Unit other : units){
                if(other != null && other.isAdded() && !other.dead && other.team == target){
                    enemy = other;
                    break;
                }
            }
            boolean shoot = enemy != null && unit.within(enemy.x, enemy.y, range);
            if(shoot){
                unit.lookAt(enemy.x, enemy.y);
                Weapons.aim(unit, enemy.x, enemy.y);
            }else if(!there){
                unit.lookAt(tx, ty);
            }
            Weapons.controlWeapons(unit, shoot, shoot);
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
