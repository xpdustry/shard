package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.game.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate WP41: {@value #BULLETS} seeded bullets of the ported kinds, fired from a ring at a fixture of walls and units of
 * two teams, give vanilla's frames: every frame the live bullets (in group order: id, type, position, velocity, time,
 * damage, units hit), each wall's damage in call order (its health after each hit), the units, the random state and
 * the entity id counter are equal, and after the fight the next entity id is vanilla's.
 *
 * <p>The bullet types are the ones whose whole tree (frags, interval and lightning bullets) runs ported code and
 * needs nothing Shard doesn't have yet: no units spawned or heal suppression. Status effects apply; fire is on, so
 * incendiary bullets start fires, and liquid ones leave puddles.
 */
class BulletFixtureOracleTest{
    static final int SIZE = 64, BULLETS = 1000, FIRING = 400, FRAMES = 760;
    static final float CX = 256f, CY = 256f;
    /** Walls: block, tile x, tile y, team. */
    static final Object[][] WALLS = {
        {"copper-wall", 28, 26, Team.crux}, {"copper-wall", 29, 26, Team.crux}, {"copper-wall", 30, 26, Team.crux},
        {"copper-wall", 31, 26, Team.crux}, {"copper-wall", 32, 26, Team.crux}, {"copper-wall", 33, 26, Team.crux},
        {"copper-wall", 34, 26, Team.crux}, {"copper-wall", 35, 26, Team.crux}, {"copper-wall", 36, 26, Team.crux},
        {"titanium-wall-large", 26, 30, Team.crux}, {"titanium-wall-large", 38, 30, Team.crux},
        {"thorium-wall", 31, 37, Team.crux}, {"thorium-wall", 32, 37, Team.crux}, {"thorium-wall", 33, 37, Team.crux},
        {"copper-wall-large", 29, 32, Team.crux}, {"copper-wall-large", 35, 32, Team.crux},
        {"titanium-wall", 18, 18, Team.sharded}, {"titanium-wall", 46, 46, Team.sharded}, {"titanium-wall", 18, 46, Team.sharded},
        {"titanium-wall", 46, 18, Team.sharded}, {"thorium-wall-large", 24, 40, Team.sharded},
    };
    /** Units: type, x, y, team. */
    static final Object[][] UNITS = {
        {"dagger", 256f, 236f, Team.crux}, {"dagger", 300f, 270f, Team.crux}, {"mace", 220f, 262f, Team.crux},
        {"flare", 250f, 284f, Team.crux}, {"horizon", 206f, 226f, Team.crux}, {"dagger", 170f, 330f, Team.sharded},
        {"flare", 330f, 180f, Team.sharded},
    };

    @Test
    void seededBulletsHitAsInVanilla(){
        com.xpdustry.shard.oracle.Vanilla.boot();
        Map<String, Block> walls = new TreeMap<>();
        for(Object[] wall : WALLS) walls.put((String)wall[0], content.block((String)wall[0]));
        var builds = new HashMap<Block, arc.func.Prov<mindustry.gen.Building>>();
        var vanillaLog = new ArrayList<String>();
        for(Block block : walls.values()){
            Wall wall = (Wall)block;
            builds.put(block, wall.buildType);
            wall.buildType = () -> wall.new WallBuild(){
                @Override
                public void damage(Team source, float amount){
                    super.damage(source, amount);
                    vanillaLog.add(tile.x + "," + tile.y + " " + health);
                }
            };
        }
        KindKey wallKind = CombatOracle.VANILLA.block("copper-wall").buildKind();
        try(var o = new CombatOracle(SIZE, "stone", KindRegistry.builtIn().withPassthrough().withKind(wallKind, RecordingWall.class, RecordingWall::new))){
            RecordingWall.LOG.clear();
            World world = o.world;
            var vanillaWalls = new ArrayList<mindustry.gen.Building>();
            var shardWalls = new ArrayList<Building>();
            for(Object[] wall : WALLS){
                int x = (Integer)wall[1], y = (Integer)wall[2];
                Team team = (Team)wall[3];
                mindustry.Vars.world.tile(x, y).setBlock(content.block((String)wall[0]), team, 0);
                vanillaWalls.add(mindustry.Vars.world.build(x, y));
                shardWalls.add(world.buildings().create(CombatOracle.VANILLA.block((String)wall[0]), team.id, x, y, 0, Cause.SERVER));
                assertInstanceOf(RecordingWall.class, shardWalls.getLast());
            }
            var vanillaUnits = new ArrayList<mindustry.gen.Unit>();
            var shardUnits = new ArrayList<Unit>();
            for(Object[] unit : UNITS){
                Team team = (Team)unit[3];
                mindustry.gen.Unit v = content.unit((String)unit[0]).spawn(team, (Float)unit[1], (Float)unit[2]);
                v.controller(new Idle.VanillaIdle());
                vanillaUnits.add(v);
                Unit s = Unit.spawn(world, CombatOracle.VANILLA.unit((String)unit[0]), team.id, (Float)unit[1], (Float)unit[2], 0f);
                s.controller(new Idle.ShardIdle());
                shardUnits.add(s);
            }
            List<Integer> pool = types(world);
            assertTrue(pool.size() > 40, "only " + pool.size() + " bullet types: " + pool);
            var random = new SplittableRandom(41);
            int fired = 0, wallHits = 0, mostLive = 0;
            for(int frame = 0; frame < FRAMES; frame++){
                String at = "frame " + frame;
                int now = frame < FIRING ? (BULLETS * (frame + 1) / FIRING) - fired : 0;
                for(int i = 0; i < now; i++, fired++){
                    int type = pool.get(random.nextInt(pool.size()));
                    Team team = random.nextInt(100) < 85 ? Team.sharded : Team.crux;
                    float around = random.nextFloat() * 360f, radius = 120f + random.nextFloat() * 80f;
                    float x = CX + arc.math.Angles.trnsx(around, radius), y = CY + arc.math.Angles.trnsy(around, radius);
                    float angle = around + 180f + (random.nextFloat() - 0.5f) * 40f;
                    content.bullet(type).create(null, team, x, y, angle);
                    world.bullets().types(world).get(type).create(world, null, team.id, x, y, angle);
                }
                o.frame();
                o.sameRandom(at);
                o.sameIds(at);
                assertEquals(o.vanillaBullets(), o.shardBullets(), at + ": live bullets");
                assertEquals(vanillaLog, RecordingWall.LOG, at + ": wall damage, in order");
                wallHits += vanillaLog.size();
                mostLive = Math.max(mostLive, world.bullets().size());
                vanillaLog.clear();
                RecordingWall.LOG.clear();
                for(int i = 0; i < vanillaWalls.size(); i++){
                    CombatOracle.same(at + " wall " + i + " health", vanillaWalls.get(i).health, shardWalls.get(i).health);
                    assertEquals(vanillaWalls.get(i).isAdded(), shardWalls.get(i).isAdded(), at + " wall " + i + " added");
                }
                for(int i = 0; i < vanillaUnits.size(); i++){
                    var v = vanillaUnits.get(i);
                    var s = shardUnits.get(i);
                    String u = at + " unit " + i + " " + v.type.name;
                    assertEquals(v.isAdded(), s.isAdded(), u + " added");
                    if(!v.isAdded()) continue;
                    CombatOracle.same(u + " health", v.health, s.health);
                    CombatOracle.same(u + " x", v.x, s.x);
                    CombatOracle.same(u + " y", v.y, s.y);
                    CombatOracle.same(u + " vel.x", v.vel.x, s.vel.x);
                    CombatOracle.same(u + " vel.y", v.vel.y, s.vel.y);
                    assertEquals(v.dead, s.dead, u + " dead");
                }
            }
            assertEquals(BULLETS, fired);
            long wallsLost = vanillaWalls.stream().filter(b -> !b.isAdded()).count();
            long unitsHurt = vanillaUnits.stream().filter(u -> !u.isAdded() || u.health < u.maxHealth).count();
            System.out.println("fixture: " + pool.size() + " bullet types, " + wallHits + " wall hits, " + wallsLost + " walls destroyed, "
                + unitsHurt + " units hurt, at most " + mostLive + " bullets live, next id " + world.ids().peek());
            assertTrue(wallHits > 200 && wallsLost > 0 && unitsHurt >= 3, "the fight happened");
            assertEquals(0, world.bullets().size(), "every bullet ended");
            // the next entity id after the fight
            o.sameIds("after the fight");
            assertEquals(CombatOracle.lastId(), world.ids().peek(), "the next entity id");
        }finally{
            builds.forEach((block, type) -> block.buildType = type);
        }
    }

    /** The bullet types fired: every ported kind whose whole tree needs nothing Shard lacks, sorted by id. */
    static List<Integer> types(World world){
        BulletTypes types = world.bullets().types(world);
        var out = new ArrayList<Integer>();
        for(int id = 0; id < types.size(); id++){
            if(content.bullet(id).getClass().getSimpleName().equals("BulletType") && id < 3) continue;
            if(fits(types.get(id), 0)) out.add(id);
        }
        return out;
    }

    static boolean fits(BulletType type, int depth){
        if(type == null) return true;
        if(depth > 4 || !type.supported()) return false;
        if(type.spawnUnit != null || type.despawnUnit != null) return false;
        if(type.suppressionRange > 0f || type.healPercent > 0f || type.healAmount > 0f) return false;
        if(type.lightning > 0 && !fits(type.lightningType, depth + 1)) return false;
        for(BulletType spawn : type.spawnBullets) if(!fits(spawn, depth + 1)) return false;
        return fits(type.fragBullet, depth + 1) && fits(type.intervalBullet, depth + 1);
    }
}
