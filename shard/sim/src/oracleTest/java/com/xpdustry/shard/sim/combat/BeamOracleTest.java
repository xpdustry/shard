package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate WP61: every bullet type of the WP61 kinds (laser, continuous, sap, rail, shrapnel, EMP, point laser, lightning)
 * fired by a damaged unit at enemy units and walls in its line, one type every {@value #EVERY} frames, gives vanilla's
 * frames: every frame the random, the ids, the bullets made and alive, the shooter's health (sap heals it), and each
 * target's health. Point lasers aim at the first target. Fire is on, so incendiary beams start fires.
 */
class BeamOracleTest{
    static final int SIZE = 64, EVERY = 30;
    static final float TOUGH = 100_000f;
    static final float X = 200f, Y = 256f;
    static final float[][] TARGETS = {{248f, 256f}, {272f, 260f}, {300f, 250f}};
    static final int[][] WALLS = {{40, 31}, {40, 32}, {41, 32}, {44, 32}};

    @Test
    void beamsHitTheirTargetsAsInVanilla(){
        com.xpdustry.shard.oracle.Vanilla.boot();
        try(var o = new CombatOracle(SIZE, "stone")){
            World world = o.world;
            mindustry.gen.Unit vShooter = spawnVanilla("dagger", Team.sharded, X, Y);
            Unit sShooter = spawnShard(world, "dagger", Team.sharded, X, Y);
            var vTargets = new ArrayList<mindustry.gen.Unit>();
            var sTargets = new ArrayList<Unit>();
            for(float[] at : TARGETS){
                vTargets.add(spawnVanilla("mace", Team.crux, at[0], at[1]));
                sTargets.add(spawnShard(world, "mace", Team.crux, at[0], at[1]));
                // tough enough to outlive every beam, so each type has something to hit
                vTargets.getLast().maxHealth = sTargets.getLast().maxHealth = TOUGH;
            }
            var vWalls = new ArrayList<mindustry.gen.Building>();
            var sWalls = new ArrayList<Building>();
            for(int[] at : WALLS){
                mindustry.Vars.world.tile(at[0], at[1]).setBlock(content.block("titanium-wall"), Team.crux, 0);
                vWalls.add(mindustry.Vars.world.build(at[0], at[1]));
                sWalls.add(world.buildings().create(CombatOracle.VANILLA.block("titanium-wall"), Team.crux.id, at[0], at[1], 0, Cause.SERVER));
                vWalls.getLast().maxHealth = sWalls.getLast().maxHealth = TOUGH;
            }
            List<Integer> types = types(world);
            assertTrue(types.size() >= 25, "the WP61 types: " + types);
            int frames = types.size() * EVERY, healed = 0;
            var hurt = new TreeSet<String>();
            for(int frame = 0; frame < frames; frame++){
                String at = "frame " + frame;
                if(frame % EVERY == 0){
                    int type = types.get(frame / EVERY);
                    at += " (" + CombatOracle.VANILLA.bullet(type).name() + ")";
                    for(var u : vTargets) u.health = TOUGH;
                    for(var u : sTargets) u.health = TOUGH;
                    for(var w : vWalls) w.health = TOUGH;
                    for(var w : sWalls) w.health = TOUGH;
                    vShooter.health = sShooter.health = vShooter.maxHealth / 2f;
                    float aimX = TARGETS[0][0], aimY = TARGETS[0][1];
                    content.bullet(type).create(vShooter, vShooter, Team.sharded, X + 4f, Y, 0f, -1f, 1f, 1f, null, null, aimX, aimY, null);
                    world.bullets().types(world).get(type).create(world, sShooter, sShooter, Team.sharded.id, X + 4f, Y, 0f, -1f, 1f, 1f, null, aimX, aimY, null);
                }
                o.frame();
                o.sameRandom(at);
                o.sameIds(at);
                assertEquals(o.vanillaCreated(), o.shardCreated(), at + ": bullets made");
                assertEquals(o.vanillaBullets(), o.shardBullets(), at + ": live bullets");
                CombatOracle.same(at + " shooter health", vShooter.health, sShooter.health);
                if(vShooter.health > vShooter.maxHealth / 2f) healed++;
                for(int i = 0; i < vTargets.size(); i++){
                    assertEquals(vTargets.get(i).isAdded(), sTargets.get(i).isAdded(), at + " target " + i + " added");
                    CombatOracle.same(at + " target " + i + " health", vTargets.get(i).health, sTargets.get(i).health);
                    if(vTargets.get(i).health < TOUGH) hurt.add("unit " + i);
                }
                for(int i = 0; i < vWalls.size(); i++){
                    assertEquals(vWalls.get(i).isAdded(), sWalls.get(i).isAdded(), at + " wall " + i + " added");
                    CombatOracle.same(at + " wall " + i + " health", vWalls.get(i).health, sWalls.get(i).health);
                    if(vWalls.get(i).health < TOUGH) hurt.add("wall " + i);
                }
            }
            System.out.println("beams: " + types.size() + " types, " + healed + " frames healed, hurt " + hurt);
            assertTrue(healed > 0, "a sap beam healed its shooter");
            assertTrue(hurt.size() >= 5, "the beams hit units and walls: " + hurt);
            for(var u : vTargets) assertTrue(u.isAdded(), "the targets outlived the beams");
        }
    }

    /** The bullet types of the WP61 kinds, by id. */
    static List<Integer> types(World world){
        BulletTypes types = world.bullets().types(world);
        var out = new ArrayList<Integer>();
        for(int id = 0; id < types.size(); id++){
            BulletType type = types.get(id);
            if(type instanceof LaserBulletType || type instanceof ContinuousBulletType || type instanceof SapBulletType || type instanceof RailBulletType
                || type instanceof ShrapnelBulletType || type instanceof EmpBulletType || type instanceof PointLaserBulletType
                || type instanceof LightningBulletType){
                out.add(id);
            }
        }
        return out;
    }

    static mindustry.gen.Unit spawnVanilla(String type, Team team, float x, float y){
        mindustry.gen.Unit unit = content.unit(type).spawn(team, x, y);
        unit.controller(new Idle.VanillaIdle());
        return unit;
    }

    static Unit spawnShard(World world, String type, Team team, float x, float y){
        Unit unit = Unit.spawn(world, CombatOracle.VANILLA.unit(type), team.id, x, y, 0f);
        unit.controller(new Idle.ShardIdle());
        return unit;
    }
}
