package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;

/**
 * {@code UnitComp.update}, in its order: bounds, drowning, the environment, the abilities, drag, the drop zones, the fall of a dead
 * unit, the building and floor underneath, solid ground, the controller, and core units left behind. A server runs
 * every branch ({@code !net.client()}); sounds, effects and trails are client-only, but where their arguments draw
 * from the random (a splash's pitch, a falling wreck's smoke) the draws are kept.
 *
 * <p>Not here yet: {@code type.update(unit)} (no native type overrides it), the instant death of AI units on captured
 * campaign sectors (Shard hosts no campaign).
 */
@Register
public final class UnitCoreComponent implements UnitComponent{
    /** {@code Vars.finalWorldBounds}: how far past the map edge a bounded unit dies. */
    public static final float FINAL_WORLD_BOUNDS = 250f;
    /** {@code UnitComp.warpDst}. */
    static final float WARP_DST = 8f;
    static final float TILESIZE = 8f;

    @Override
    public String name(){
        return "UnitComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return true;
    }

    @Override
    public void update(Unit unit){
        if(Float.isNaN(unit.x) || Float.isNaN(unit.y)){
            unit.remove();
            return;
        }
        UnitParams params = unit.params();
        Rules rules = UnitCalls.rules(unit.world());
        TileGrid tiles = unit.world().tiles();
        if(params.bounded()) bounds(unit, rules, tiles);

        if(Float.isNaN(unit.health)){
            unit.health = 0f;
            unit.kill();
        }

        int floor = unit.floorOn();
        int tile = unit.tileOn();
        BlockTable table = tiles.table();
        if(unit.isFlying() != unit.wasFlying) unit.wasFlying = unit.isFlying();

        if(!params.hovering() && unit.isGrounded() && params.emitWalkEffect()){
            if((unit.splashTimer += Mathf.dst(unit.deltaX, unit.deltaY)) >= (7f + unit.hitSize / 8f)){
                unit.splashTimer = 0f;
                // floor.walkSound.at(x, y, Mathf.random(pitchMin, pitchMax), volume): the pitch draws even headless
                if(params.emitWalkSound()) unit.world().rand().nextFloat();
            }
        }

        updateDrowning(unit, table);
        unit.wasHealed = false;

        if(!params.supportsEnv(rules.env) && !unit.dead){
            UnitCalls.envDeath(unit);
            TeamUnits counts = TeamUnits.of(unit.world());
            if(counts != null) counts.updateCount(unit.team, unit.type(), -1);
        }

        UnitHooks.of(unit.world()).updateAbilities(unit);

        unit.drag = params.drag() * (unit.isGrounded() ? table.dragMultiplier(floor) : 1f) * unit.statuses.dragMultiplier
            * rules.dragMultiplier;

        if(unit.team != rules.waveTeam && unit.hittable()) DropZones.of(unit.world()).push(unit);

        if(unit.dead || unit.health <= 0) fall(unit, params);

        Building build = tile == -1 ? null : unit.world().buildings().at(tile);
        if(build instanceof UnitContact contact) contact.unitOnAny(unit);
        if(tile != -1 && unit.isGrounded() && !params.hovering()){
            if(build instanceof UnitContact contact) contact.unitOn(unit);
            float damage = table.damageTaken(floor);
            if(damage > 0f) unit.damageContinuous(damage);
        }

        if(tile != -1 && !unit.canPassOn()){
            if(params.canBoost()) unit.elevation = 1f;
            else unit.kill();
        }

        if(!unit.dead) unit.controller.updateUnit(unit);
        if(!unit.controller.isValidController()) unit.resetController();

        if(unit.spawnedByCore && !unit.isPlayer() && !unit.dead) UnitCalls.despawn(unit);
    }

    /** Kept inside the map, or the rules' limited area for non-AI teams; killed far outside it. */
    static void bounds(Unit unit, Rules rules, TileGrid tiles){
        float bot = 0f, left = 0f, top = tiles.height() * TILESIZE, right = tiles.width() * TILESIZE;
        if(rules.limitMapArea && !UnitCalls.isAI(unit.world(), unit.team)){
            bot = rules.limitY * TILESIZE;
            left = rules.limitX * TILESIZE;
            top = rules.limitHeight * TILESIZE + bot;
            right = rules.limitWidth * TILESIZE + left;
        }
        float dx = 0f, dy = 0f;
        // repel out of bounds
        if(unit.x < left) dx += (-(unit.x - left) / WARP_DST);
        if(unit.y < bot) dy += (-(unit.y - bot) / WARP_DST);
        if(unit.x > right - TILESIZE) dx -= (unit.x - (right - TILESIZE)) / WARP_DST;
        if(unit.y > top - TILESIZE) dy -= (unit.y - (top - TILESIZE)) / WARP_DST;
        // 10 / max(delta, 1) at Δ = 1
        float maxMagnitude = 10f;
        unit.velAddNet(Mathf.clamp(dx, -maxMagnitude, maxMagnitude), Mathf.clamp(dy, -maxMagnitude, maxMagnitude));
        float margin = TILESIZE;
        unit.x = Mathf.clamp(unit.x, left - margin, right - TILESIZE + margin);
        unit.y = Mathf.clamp(unit.y, bot - margin, top - TILESIZE + margin);
        if(unit.isGrounded()){
            unit.x = Mathf.clamp(unit.x, left, right - TILESIZE);
            unit.y = Mathf.clamp(unit.y, bot, top - TILESIZE);
        }
        if(unit.x < -FINAL_WORLD_BOUNDS + left || unit.y < -FINAL_WORLD_BOUNDS + bot || unit.x >= right + FINAL_WORLD_BOUNDS
            || unit.y >= top + FINAL_WORLD_BOUNDS){
            unit.kill();
        }
    }

    /** {@code updateDrowning()}: a grounded unit on a drowning liquid fills its drown time, and dies when it is full. */
    static void updateDrowning(Unit unit, BlockTable table){
        int floor = unit.shape.drownFloor(unit);
        if(floor != -1 && table.isLiquid(floor) && table.drownTime(floor) > 0 && unit.canDrown()){
            unit.lastDrownFloor = floor;
            unit.drownTime += 1f / (unit.hitSize / 8f * unit.params().drownTimeMultiplier() * table.drownTime(floor));
            // the drown effect's chance draws even headless
            Mathf.chanceDelta(unit.world().rand(), 0.05f);
            if(unit.drownTime >= 0.999f) unit.kill();
        }else{
            unit.drownTime -= 1f / 50f;
        }
        unit.drownTime = Mathf.clamp(unit.drownTime);
    }

    /** A dead unit falls: little drag, smoke and engine sparks (random draws only), then destroyed on landing. */
    static void fall(Unit unit, UnitParams params){
        Rand rand = unit.world().rand();
        unit.drag = 0.01f;
        if(Mathf.chanceDelta(rand, 0.1)){
            // Tmp.v1.rnd(Mathf.range(hitSize)): the length, then the direction
            float length = Mathf.range(rand, unit.hitSize);
            new Vec2().setToRandomDirection(rand).scl(length);
        }
        if(Mathf.chanceDelta(rand, 0.2)){
            float range = Mathf.range(rand, params.engineSize());
            Mathf.range(rand, range);
            Mathf.range(rand, range);
            Mathf.random(rand);
        }
        unit.elevation -= params.fallSpeed();
        if(unit.isGrounded() || unit.health <= -unit.maxHealth * params.wreckHealthMultiplier()) UnitCalls.destroy(unit);
    }
}
