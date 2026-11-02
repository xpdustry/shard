package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * {@code EmpBulletType}: on a hit that isn't absorbed, every building within {@code radius} feels it, in vanilla's
 * {@code indexer.allBuildings} order (active teams, then each team's quadtree): allied powered blocks are overdriven and
 * healed, enemy power producers (or the laser absorber in the way) slowed and damaged. Then enemy units take damage and
 * the status, unless an absorber stands between.
 */
@BulletKind("emp-bullet-type")
public final class EmpBulletType extends BasicBulletType{
    public float radius, timeIncrease, timeDuration, powerDamageScl, powerSclDecrease, unitDamageScl;
    public boolean hitUnits;

    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        radius = def.radius();
        timeIncrease = def.timeIncrease();
        timeDuration = def.timeDuration();
        powerDamageScl = def.powerDamageScl();
        powerSclDecrease = def.powerSclDecrease();
        hitUnits = def.hitUnits();
        unitDamageScl = def.unitDamageScl();
    }

    @Override
    public void hit(Bullet b, float x, float y, boolean createFrags){
        super.hit(b, x, y, createFrags);
        if(b.absorbed()) return;
        World world = b.world();
        Buildings buildings = world.buildings();
        IntSeq teams = buildings.activeTeams();
        var found = new ArrayList<Building>();
        for(int i = 0; i < teams.size; i++){
            BuildingTree tree = buildings.tree(teams.get(i));
            if(tree != null) tree.intersect(x - radius, y - radius, radius * 2f, radius * 2f, found::add);
        }
        for(Building building : found){
            if(!Mathf.within(building.x(), building.y(), x, y, radius + building.hitSize() / 2f)) continue;
            Building other = building;
            BlockDef block = other.block();
            if(other.team == b.team()){
                if(block.hasPower() && other.info().canOverdrive && other.timeScale < timeIncrease){
                    other.applyBoost(timeIncrease, timeDuration);
                }
                if(block.hasPower() && other.damaged()){
                    other.heal(healPercent / 100f * other.maxHealth + healAmount);
                }
            }else if(other.power != null){
                Building absorber = Lasers.findAbsorber(world, b.team(), x, y, other.x(), other.y());
                if(absorber != null) other = absorber;
                if(other.power != null && other.power.graph != null && other.power.graph.getLastPowerProduced() > 0f){
                    other.applySlowdown(powerSclDecrease, timeDuration);
                    other.damage(damage * powerDamageScl);
                }
            }
        }
        if(hitUnits){
            SpatialIndex index = SpatialIndex.of(world);
            if(index == null) return;
            index.nearbyEnemies(b.team(), x, y, radius, other -> {
                if(other.team != b.team() && other.hittable()){
                    if(Lasers.findAbsorber(world, b.team(), x, y, other.x, other.y) != null) return;
                    other.damage(damage * unitDamageScl);
                    if(Mathf.chance(world.rand(), statusChance)) StatusApply.of(world).apply(other, status, statusDuration);
                }
            });
        }
    }
}
