package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
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
 * Gate M0: a dagger and a flare fire at each other and at a wall of the flare's team, frame by frame as in vanilla,
 * until both are dead or {@value #FRAMES} frames pass: the units (position, rotation, velocity, health, mounts), the
 * live bullets, the wall's damage in order, the random state and the entity id counter, and at the end the next
 * entity id. A script stands in for the AI (stage 2): each unit turns to its target, aims and holds the trigger,
 * switching between the enemy unit and the wall every {@value #SWITCH} frames. The flare shoots only while flying
 * ({@code minShootVelocity}), so it also circles the dagger {@value #ORBIT} world units out, as {@code circleTarget}
 * flies.
 */
class DuelOracleTest{
    static final int SIZE = 48, FRAMES = 1800, SWITCH = 240;
    static final float ORBIT = 60f;
    static final int WALL_X = 24, WALL_Y = 18;

    @Test
    void aDaggerAndAFlareFightAsInVanilla(){
        com.xpdustry.shard.oracle.Vanilla.boot();
        Wall wall = (Wall)content.block("titanium-wall");
        var build = wall.buildType;
        var vanillaLog = new ArrayList<String>();
        wall.buildType = () -> wall.new WallBuild(){
            @Override
            public void damage(Team source, float amount){
                super.damage(source, amount);
                vanillaLog.add(tile.x + "," + tile.y + " " + health);
            }
        };
        KindKey wallKind = CombatOracle.VANILLA.block("titanium-wall").buildKind();
        try(var o = new CombatOracle(SIZE, "stone", KindRegistry.builtIn().withPassthrough().withKind(wallKind, RecordingWall.class, RecordingWall::new))){
            RecordingWall.LOG.clear();
            World world = o.world;
            mindustry.Vars.world.tile(WALL_X, WALL_Y).setBlock(wall, Team.crux, 0);
            mindustry.gen.Building vWall = mindustry.Vars.world.build(WALL_X, WALL_Y);
            Building sWall = world.buildings().create(CombatOracle.VANILLA.block("titanium-wall"), Team.crux.id, WALL_X, WALL_Y, 0, Cause.SERVER);

            mindustry.gen.Unit vDagger = content.unit("dagger").spawn(Team.sharded, 150f, 170f);
            Unit sDagger = Unit.spawn(world, CombatOracle.VANILLA.unit("dagger"), Team.sharded.id, 150f, 170f, 0f);
            mindustry.gen.Unit vFlare = content.unit("flare").spawn(Team.crux, 236f, 210f);
            Unit sFlare = Unit.spawn(world, CombatOracle.VANILLA.unit("flare"), Team.crux.id, 236f, 210f, 0f);
            var atWall = new float[]{WALL_X * 8f, WALL_Y * 8f};
            vDagger.controller(new VanillaFighter(() -> vFlare, atWall));
            sDagger.controller(new ShardFighter(() -> sFlare, atWall));
            // the flare fires at the dagger, and at open ground when switching
            var past = new float[]{120f, 120f};
            vFlare.controller(new VanillaFighter(() -> vDagger, past).circling());
            sFlare.controller(new ShardFighter(() -> sDagger, past).circling());

            int frame = 0, wallHits = 0, flareShots = 0;
            for(; frame < FRAMES; frame++){
                VanillaFighter.frame = ShardFighter.frame = frame;
                String at = "frame " + frame;
                o.frame();
                o.sameRandom(at);
                o.sameIds(at);
                assertEquals(o.vanillaCreated(), o.shardCreated(), at + ": bullets made");
                assertEquals(o.vanillaBullets(), o.shardBullets(), at + ": live bullets");
                assertEquals(vanillaLog, RecordingWall.LOG, at + ": wall damage, in order");
                wallHits += vanillaLog.size();
                vanillaLog.clear();
                RecordingWall.LOG.clear();
                CombatOracle.same(at + " wall health", vWall.health, sWall.health);
                assertEquals(vWall.isAdded(), sWall.isAdded(), at + " wall added");
                same(at + " dagger", vDagger, sDagger);
                same(at + " flare", vFlare, sFlare);
                if(vFlare.isAdded()) flareShots = Arrays.stream(vFlare.mounts).mapToInt(m -> m.totalShots).sum();
                if(!vDagger.isAdded() && !vFlare.isAdded() && Groups.bulletCount() == 0) break;
            }
            System.out.println("duel: " + frame + " frames, " + wallHits + " wall hits, dagger " + (vDagger.isAdded() ? vDagger.health : "dead")
                + ", flare " + (vFlare.isAdded() ? vFlare.health : "dead") + " after " + flareShots + " shots, next id " + world.ids().peek());
            assertTrue(wallHits > 0, "the dagger hit the wall");
            assertTrue(flareShots > 0, "the flare fired");
            assertTrue(!vFlare.isAdded() || vFlare.health < vFlare.maxHealth, "the dagger hit the flare");
            assertTrue(!vDagger.isAdded() || vDagger.health < vDagger.maxHealth, "the flare hit the dagger");
            o.sameIds("after the fight");
            assertEquals(CombatOracle.lastId(), world.ids().peek(), "the next entity id");
        }finally{
            wall.buildType = build;
        }
    }

    static void same(String at, mindustry.gen.Unit v, Unit s){
        assertEquals(v.isAdded(), s.isAdded(), at + " added");
        if(!v.isAdded()) return;
        CombatOracle.same(at + " x", v.x, s.x);
        CombatOracle.same(at + " y", v.y, s.y);
        CombatOracle.same(at + " rotation", v.rotation, s.rotation);
        CombatOracle.same(at + " vel.x", v.vel.x, s.vel.x);
        CombatOracle.same(at + " vel.y", v.vel.y, s.vel.y);
        CombatOracle.same(at + " health", v.health, s.health);
        CombatOracle.same(at + " elevation", v.elevation, s.elevation);
        assertEquals(v.dead, s.dead, at + " dead");
        for(int i = 0; i < v.mounts.length; i++){
            CombatOracle.same(at + " mount " + i + " reload", v.mounts[i].reload, s.mounts[i].reload);
            CombatOracle.same(at + " mount " + i + " rotation", v.mounts[i].rotation, s.mounts[i].rotation);
            assertEquals(v.mounts[i].totalShots, s.mounts[i].totalShots, at + " mount " + i + " shots");
        }
    }

    /** {@code Groups.bullet.size()}, without importing vanilla's Groups over Shard's names. */
    static final class Groups{
        static int bulletCount(){
            return mindustry.gen.Groups.bullet.size();
        }
    }

    /** Turns to the enemy (or, every other {@value #SWITCH} frames, to the fallback point), aims and fires. */
    static final class VanillaFighter implements mindustry.entities.units.UnitController{
        static int frame;
        final java.util.function.Supplier<mindustry.gen.Unit> enemy;
        final float[] fallback;
        boolean circles;
        mindustry.gen.Unit unit;

        VanillaFighter(java.util.function.Supplier<mindustry.gen.Unit> enemy, float[] fallback){
            this.enemy = enemy;
            this.fallback = fallback;
        }

        VanillaFighter circling(){
            circles = true;
            return this;
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
            mindustry.gen.Unit target = enemy.get();
            boolean atUnit = (frame / SWITCH) % 2 == 0 && target.isAdded();
            float x = atUnit ? target.x : fallback[0], y = atUnit ? target.y : fallback[1];
            if(circles){
                float around = frame * 1.5f;
                var to = new arc.math.geom.Vec2(x + arc.math.Angles.trnsx(around, ORBIT), y + arc.math.Angles.trnsy(around, ORBIT));
                unit.movePref(to.sub(unit.x, unit.y).limit(unit.speed()));
            }
            unit.lookAt(x, y);
            unit.aim(x, y);
            unit.controlWeapons(true, true);
        }
    }

    static final class ShardFighter implements UnitController{
        static int frame;
        final java.util.function.Supplier<Unit> enemy;
        final float[] fallback;
        boolean circles;

        ShardFighter(java.util.function.Supplier<Unit> enemy, float[] fallback){
            this.enemy = enemy;
            this.fallback = fallback;
        }

        ShardFighter circling(){
            circles = true;
            return this;
        }

        @Override
        public void updateUnit(Unit unit){
            Unit target = enemy.get();
            boolean atUnit = (frame / SWITCH) % 2 == 0 && target.isAdded();
            float x = atUnit ? target.x : fallback[0], y = atUnit ? target.y : fallback[1];
            if(circles){
                float around = frame * 1.5f;
                var to = new com.xpdustry.shard.common.geom.Vec2(x + com.xpdustry.shard.common.math.Angles.trnsx(around, ORBIT),
                    y + com.xpdustry.shard.common.math.Angles.trnsy(around, ORBIT));
                unit.movePref(to.sub(unit.x, unit.y).limit(unit.speed()));
            }
            unit.lookAt(x, y);
            Weapons.aim(unit, x, y);
            Weapons.controlWeapons(unit, true, true);
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
