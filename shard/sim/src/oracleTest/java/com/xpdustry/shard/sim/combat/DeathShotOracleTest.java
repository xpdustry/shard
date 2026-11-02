package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.game.*;
import mindustry.gen.CreateBulletCallPacket;
import mindustry.gen.UnitDeathCallPacket;
import mindustry.gen.UnitDestroyCallPacket;
import mindustry.gen.UnitSpawnCallPacket;
import mindustry.world.blocks.defense.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.List;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate for the unit core's destroyed hook: units whose weapons shoot on death (two crawlers, killed and hurt to
 * death, and two missiles that run out of time and fall) die next to walls and a dagger. Each frame, with vanilla's
 * net in server mode, the Calls it sends ({@code unitDeath}, {@code unitDestroy}, {@code unitSpawn},
 * {@code createBullet}) are the events Shard publishes, in order, and the bullets made, the live bullets, the walls'
 * damage in order, the units, the entity ids and the random state are equal: the death shots fire between the
 * explosion and the crash, in the same tick.
 */
class DeathShotOracleTest{
    static final int SIZE = 48, FRAMES = 360;
    /** type, x, y, team. */
    static final Object[][] UNITS = {
        {"crawler", 200f, 200f, Team.crux}, {"crawler", 232f, 200f, Team.crux}, {"quell-missile", 260f, 228f, Team.crux},
        {"disrupt-missile", 172f, 236f, Team.crux}, {"dagger", 214f, 214f, Team.sharded},
    };
    static final int[][] WALLS = {{24, 27}, {27, 27}, {29, 24}, {22, 23}};

    @Test
    void deathShotsFireInVanillasTick(){
        Vanilla.boot();
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
            com.xpdustry.shard.sim.world.Observer observer = world.observe(4096, List.of(UnitEvent.class, CombatEvent.class, BulletEvent.class), null);
            var vanillaWalls = new ArrayList<mindustry.gen.Building>();
            var shardWalls = new ArrayList<Building>();
            for(int[] at : WALLS){
                mindustry.Vars.world.tile(at[0], at[1]).setBlock(wall, Team.sharded, 0);
                vanillaWalls.add(mindustry.Vars.world.build(at[0], at[1]));
                shardWalls.add(world.buildings().create(CombatOracle.VANILLA.block("titanium-wall"), Team.sharded.id, at[0], at[1], 0, Cause.SERVER));
            }
            var vanilla = new ArrayList<mindustry.gen.Unit>();
            var shard = new ArrayList<Unit>();
            for(Object[] unit : UNITS){
                Team team = (Team)unit[3];
                mindustry.gen.Unit v = content.unit((String)unit[0]).spawn(team, (Float)unit[1], (Float)unit[2]);
                v.controller(new Idle.VanillaIdle());
                vanilla.add(v);
                Unit s = Unit.spawn(world, CombatOracle.VANILLA.unit((String)unit[0]), team.id, (Float)unit[1], (Float)unit[2], 0f);
                s.controller(new Idle.ShardIdle());
                shard.add(s);
            }
            observer.drain();
            int[] totals = new int[3];
            Vanilla.net(Vanilla.NetMode.server, () -> {
                Vanilla.captured().clear();
                int calls = 0;
                for(int frame = 0; frame < FRAMES; frame++){
                    String at = "frame " + frame;
                    if(frame == 3){
                        vanilla.get(0).kill();
                        shard.get(0).kill();
                    }else if(frame == 12){
                        vanilla.get(1).damage(1000f);
                        shard.get(1).damage(1000f);
                    }
                    o.frame();
                    var expected = new ArrayList<String>();
                    List<Vanilla.Capture.Sent> sent = Vanilla.captured();
                    for(; calls < sent.size(); calls++){
                        String call = call(sent.get(calls).packet(), o.vanillaBase);
                        if(call != null) expected.add(call);
                    }
                    var actual = new ArrayList<String>();
                    for(WorldEvent event : observer.drain()){
                        String call = call(event, o.shardBase);
                        if(call != null) actual.add(call);
                    }
                    assertEquals(expected, actual, at + ": calls, in order");
                    for(String call : expected){
                        if(call.startsWith("Death")) totals[0]++;
                        if(call.startsWith("CreateBullet")) totals[1]++;
                    }
                    o.sameRandom(at);
                    o.sameIds(at);
                    assertEquals(o.vanillaCreated(), o.shardCreated(), at + ": bullets made");
                    assertEquals(o.vanillaBullets(), o.shardBullets(), at + ": live bullets");
                    assertEquals(vanillaLog, RecordingWall.LOG, at + ": wall damage, in order");
                    totals[2] += vanillaLog.size();
                    vanillaLog.clear();
                    RecordingWall.LOG.clear();
                    for(int i = 0; i < vanillaWalls.size(); i++){
                        CombatOracle.same(at + " wall " + i, vanillaWalls.get(i).health, shardWalls.get(i).health);
                    }
                    for(int i = 0; i < vanilla.size(); i++){
                        var v = vanilla.get(i);
                        var s = shard.get(i);
                        String u = at + " " + v.type.name + " " + i;
                        assertEquals(v.isAdded(), s.isAdded(), u + " added");
                        assertEquals(v.dead, s.dead, u + " dead");
                        if(!v.isAdded()) continue;
                        CombatOracle.same(u + " x", v.x, s.x);
                        CombatOracle.same(u + " y", v.y, s.y);
                        CombatOracle.same(u + " health", v.health, s.health);
                        CombatOracle.same(u + " elevation", v.elevation, s.elevation);
                        for(int m = 0; m < v.mounts.length; m++){
                            assertEquals(v.mounts[m].totalShots, s.mounts[m].totalShots, u + " mount " + m + " shots");
                        }
                    }
                }
            });
            assertEquals(0, observer.dropped());
            for(int i = 0; i < 4; i++) assertFalse(vanilla.get(i).isAdded(), UNITS[i][0] + " " + i + " died");
            System.out.println("death shots: " + totals[0] + " deaths, " + totals[1] + " createBullet calls, " + totals[2] + " wall hits");
            assertFalse(vanilla.get(4).isAdded(), "the death shots killed the dagger");
            assertEquals(5, totals[0], "the four shooters and the dagger died, each by a Call");
            assertTrue(totals[2] > 0, "the death shots hit the walls");
        }finally{
            wall.buildType = build;
        }
    }

    /** A vanilla packet or a Shard event as "Call relative-id", or null for what isn't a combat or lifecycle call. */
    static String call(Object packet, int base){
        return switch(packet){
            case UnitDeathCallPacket p -> "Death " + (p.uid - base);
            case UnitDestroyCallPacket p -> "Destroy " + (p.uid - base);
            case UnitSpawnCallPacket p -> "Spawn " + (p.container.unit.id - base);
            case CreateBulletCallPacket p -> "CreateBullet " + p.type.id + " " + p.team.id + " " + p.x + "," + p.y + " " + p.angle;
            case UnitEvent.Death e -> "Death " + (e.unit() - base);
            case UnitEvent.Destroy e -> "Destroy " + (e.unit() - base);
            case CombatEvent.UnitSpawned e -> "Spawn " + (e.unit() - base);
            case BulletEvent.Created e -> "CreateBullet " + e.type() + " " + e.team() + " " + e.x() + "," + e.y() + " " + e.angle();
            default -> null;
        };
    }
}
