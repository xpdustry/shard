package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code TankComp.update}, for tank classes ({@code TankUnit}, between {@code SyncComp} and {@code UnitComp}), in its
 * order:
 * <ol>
 *   <li>a {@code crushFragile} tank destroys the enemy {@code crushFragile} buildings on the eight tiles around it;</li>
 *   <li>over the square of tiles under it ({@code hitSize * 0.75} tiles from its centre), it counts the solid ones (and
 *   those outside the map) for its slowdown, notes the deep floor, which drowns it only if the whole square is deep, and,
 *   while it moves, crushes enemy buildings and breaks props on the square one tile smaller;</li>
 *   <li>after a controlled move, the treads advance by the last movement.</li>
 * </ol>
 * The tread dust and sound are client-only.
 */
@Register
public final class TankComponent implements UnitComponent{
    /** {@code other.damage(team, 999999999f)}. */
    static final float FRAGILE_DAMAGE = 999999999f;

    @Override
    public String name(){
        return "TankComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return params.locomotion() == Locomotion.TANK;
    }

    @Override
    public void update(Unit unit){
        var tank = (MoveShape.Tank)unit.shape;
        UnitParams params = unit.params();
        UnitParams.MoveParams move = params.move();
        var tiles = unit.world().tiles();
        var buildings = unit.world().buildings();
        var shapes = unit.world().unitTable().shapes();
        boolean disarmed = unit.statuses.disarmed;

        tank.lastDeepFloor = -1;
        boolean anyNonDeep = false;

        if(move.crushFragile() && !disarmed){
            for(Point2 offset : Geometry.d8){
                int pos = Crush.tileWorld(unit, unit.x + offset.x() * 8, unit.y + offset.y() * 8);
                Building other = pos == -1 ? null : buildings.at(pos);
                if(other != null && other.team != unit.team && shapes.crushFragile(tiles.block(pos))){
                    other.damage(unit.team, FRAGILE_DAMAGE);
                }
            }
        }

        // calculate overlapping tiles so it slows down when going "over" walls
        int r = Math.max((int)(unit.hitSize * 0.75f / 8), 0);
        int solids = 0, total = (r * 2 + 1) * (r * 2 + 1);
        float deltaLen = Mathf.len(unit.deltaX, unit.deltaY);
        for(int dx = -r; dx <= r; dx++){
            for(int dy = -r; dy <= r; dy++){
                int pos = Crush.tileWorld(unit, unit.x + dx * 8, unit.y + dy * 8);
                if(pos == -1 || tiles.solid(pos)) solids++;

                if(pos != -1 && tiles.table().isDeep(tiles.floor(pos))){
                    tank.lastDeepFloor = tiles.floor(pos);
                }else{
                    anyNonDeep = true;
                }

                // the damage square is one tile smaller, so passing a wall doesn't touch it
                if(move.crushDamage() > 0 && !disarmed && (tank.walked || deltaLen >= 0.01f) && pos != -1
                    && Math.max(Math.abs(dx), Math.abs(dy)) <= r - 1){
                    Building build = buildings.at(pos);
                    if(build != null && build.team != unit.team){
                        // crushDamage * Time.delta * ..., at Δ = 1
                        build.damage(unit.team, move.crushDamage() * shapes.crushDamageMultiplier(tiles.block(pos))
                            * UnitCalls.rules(unit.world()).unitDamage(unit.team) * ((unit.statuses.speedMultiplier - 1) / 5 + 1));
                    }else if(shapes.unitMoveBreakable(tiles.block(pos))){
                        Crush.breakProp(unit, pos);
                    }
                }
            }
        }

        if(anyNonDeep) tank.lastDeepFloor = -1;

        tank.lastSlowdown = Mathf.lerp(1f, move.crawlSlowdown(), Mathf.clamp((float)solids / total / move.crawlSlowdownFrac()));

        // trigger animation only when walking manually
        if(tank.walked){
            tank.treadTime += deltaLen;
            tank.walked = false;
        }
    }
}
