package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code CrawlComp.update}, for crawler classes ({@code CrawlUnit}, between {@code BuilderComp} and {@code HealthComp}).
 * While it moves, the segments turn toward the body, and over the tiles under it ({@code cx² + cy² <= radius}, a quirk:
 * vanilla compares with the radius, not its square) it counts solid tiles (and those outside the map) for its slowdown
 * and deep floors for drowning, crushes enemy buildings and draws the dust effect's chance. Then the segments are kept
 * within {@code segmentMaxRot} of the body, and the crawl advances by the last movement.
 */
@Register
public final class CrawlComponent implements UnitComponent{
    /** {@code Mathf.chanceDelta(0.025)} per tile, for {@code Fx.crawlDust}. */
    static final double DUST_CHANCE = 0.025;

    @Override
    public String name(){
        return "CrawlComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return params.locomotion() == Locomotion.CRAWL;
    }

    @Override
    public void update(Unit unit){
        var crawl = (MoveShape.Crawl)unit.shape;
        UnitParams.MoveParams move = unit.params().move();
        if(unit.moving()){
            var tiles = unit.world().tiles();
            var buildings = unit.world().buildings();
            Rand rand = unit.world().rand();
            // segmentRotSpeed * Time.delta
            crawl.segmentRot = Angles.moveToward(crawl.segmentRot, unit.rotation, move.segmentRotSpeed());

            int radius = (int)Math.max(0, unit.hitSize / 8 * 2f);
            int count = 0, solids = 0, deeps = 0;
            crawl.lastDeepFloor = -1;

            // calculate tiles under this unit, and apply slowdown + particle effects
            for(int cx = -radius; cx <= radius; cx++){
                for(int cy = -radius; cy <= radius; cy++){
                    if(cx * cx + cy * cy <= radius){
                        count++;
                        int pos = Crush.tileWorld(unit, unit.x + cx * 8, unit.y + cy * 8);
                        if(pos != -1){
                            if(tiles.solid(pos)) solids++;

                            int floor = tiles.floor(pos);
                            if(tiles.table().isDeep(floor)){
                                deeps++;
                                crawl.lastDeepFloor = floor;
                            }

                            Building build = buildings.at(pos);
                            if(build != null && build.team != unit.team){
                                // crushDamage * Time.delta * rules.unitDamage(team)
                                build.damage(unit.team, move.crushDamage() * UnitCalls.rules(unit.world()).unitDamage(unit.team));
                            }

                            // Fx.crawlDust's chance draws even headless
                            Mathf.chanceDelta(rand, DUST_CHANCE);
                        }else{
                            solids++;
                        }
                    }
                }
            }

            // when most blocks under this unit cannot be drowned in, do not drown
            if((float)deeps / count < 0.75f) crawl.lastDeepFloor = -1;

            crawl.lastCrawlSlowdown = Mathf.lerpDelta(1f, move.crawlSlowdown(), Mathf.clamp((float)solids / count / move.crawlSlowdownFrac()));
        }
        crawl.segmentRot = Angles.clampRange(crawl.segmentRot, unit.rotation, move.segmentMaxRot());

        crawl.crawlTime += Mathf.len(unit.deltaX, unit.deltaY);
    }
}
