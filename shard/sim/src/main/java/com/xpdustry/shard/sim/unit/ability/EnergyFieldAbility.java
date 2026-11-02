package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code EnergyFieldAbility} (EnergyFieldAbility.java): every {@code reload} ticks, from its orb, the closest
 * {@code maxTargets} of what is in range are struck: enemies take damage and its status, damaged allies are healed by
 * {@code healPercent} of their health. Units come first, every present team's in turn ({@code Units.nearby(null, …)}),
 * then buildings in {@code indexer.allBuildings} order; the stable sort by distance keeps that order among ties. A
 * plastanium wall across the line takes the strike instead. Striking anything draws the sound's pitch. Aegires carries
 * it.
 *
 * <p>{@code curStroke} and {@code anyNearby} only draw the field, so they are not kept.
 */
@AbilityKind("energy-field-ability")
public final class EnergyFieldAbility extends Ability{
    private static final int DERELICT = 0;

    private float damage, reload, range, statusDuration, x, y, healPercent, sameTypeHealMult;
    private StatusDef status;
    private boolean targetGround, targetAir, hitBuildings, hitUnits;
    private int maxTargets;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        damage = def.damage();
        reload = def.reload();
        range = def.range();
        status = def.status(content);
        statusDuration = def.statusDuration();
        x = def.x();
        y = def.y();
        targetGround = def.targetGround();
        targetAir = def.targetAir();
        hitBuildings = def.hitBuildings();
        hitUnits = def.hitUnits();
        maxTargets = def.maxTargets();
        healPercent = def.healPercent();
        sameTypeHealMult = def.sameTypeHealMult();
    }

    @Override
    public UnitAbility create(){
        return new Timed();
    }

    @Override
    public void update(Unit unit, UnitAbility ability){
        var state = (Timed)ability;
        if((state.timer += 1f) < reload) return;
        World world = unit.world();
        // Tmp.v1.trns(unit.rotation - 90, x, y).add(unit.x, unit.y)
        float rx = Angles.trnsx(unit.rotation - 90, x, y) + unit.x, ry = Angles.trnsy(unit.rotation - 90, x, y) + unit.y;
        boolean anyNearby = false;
        var all = new ArrayList<Entity>();

        SpatialIndex index = SpatialIndex.of(world);
        if(hitUnits && index != null){
            index.nearbyAnyTeam(rx - range, ry - range, range * 2f, range * 2f, other -> {
                if(other.within(rx, ry, range + other.hitSize / 2f) && other != unit && Targets.checkTarget(other, targetAir, targetGround)
                    && other.targetable(unit.team) && (other.team != unit.team || other.damaged())){
                    all.add(other);
                }
            });
        }
        Rules rules = UnitCalls.rules(world);
        if(hitBuildings && targetGround){
            var table = world.tiles().table();
            eachBuilding(world, rx, ry, range, b -> {
                if((b.team != DERELICT || rules.coreCapture) && ((b.team != unit.team && b.info().targetable) || b.damaged())
                    && !table.privileged(b.block().id())){
                    all.add(b);
                }
            });
        }

        all.sort((a, b) -> Float.compare(dst2(a, rx, ry), dst2(b, rx, ry)));
        int len = Math.min(all.size(), maxTargets);
        float scaledDamage = damage * rules.unitDamage(unit.team) * unit.statuses.damageMultiplier;
        StatusApply apply = StatusApply.of(world);

        for(int i = 0; i < len; i++){
            Entity other = all.get(i);
            // lightning gets absorbed by plastanium
            Building absorber = Lasers.findAbsorber(world, unit.team, rx, ry, Targets.x(other), Targets.y(other));
            if(absorber != null) other = absorber;

            if(Targets.team(other) == unit.team){
                if(other instanceof Unit u && u.damaged()){
                    anyNearby = true;
                    float healMult = u.type() == unit.type() ? sameTypeHealMult : 1f;
                    u.heal(healPercent / 100f * u.maxHealth * healMult);
                }else if(other instanceof Building b && b.damaged()){
                    anyNearby = true;
                    b.heal(healPercent / 100f * b.maxHealth);
                }
            }else{
                anyNearby = true;
                if(other instanceof Building b){
                    b.damage(unit.team, scaledDamage);
                }else if(other instanceof Unit u){
                    u.damage(scaledDamage);
                    apply.apply(u, status, statusDuration);
                }
            }
        }

        // shootSound.at(unit, 1f + Mathf.range(0.1f), 1f): the pitch draws even headless
        if(anyNearby) Mathf.range(world.rand(), 0.1f);
        state.timer = 0f;
    }

    /** {@code indexer.allBuildings(x, y, range, cons)} (BlockIndexer.java:435): every active team's tree, then the circle. */
    static void eachBuilding(World world, float x, float y, float range, java.util.function.Consumer<Building> action){
        Buildings buildings = world.buildings();
        var teams = buildings.activeTeams();
        var found = new ArrayList<Building>();
        for(int i = 0; i < teams.size; i++){
            BuildingTree tree = buildings.tree(teams.get(i));
            if(tree != null) tree.intersect(x - range, y - range, range * 2f, range * 2f, found::add);
        }
        for(Building building : found){
            if(Mathf.within(building.x(), building.y(), x, y, range + building.hitSize() / 2f)) action.accept(building);
        }
    }

    private static float dst2(Entity target, float x, float y){
        float dx = Targets.x(target) - x, dy = Targets.y(target) - y;
        return dx * dx + dy * dy;
    }
}
