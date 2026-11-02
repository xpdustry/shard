package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.unit.NoAi;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.unit.UnitEvent;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import arc.struct.*;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP47): the drop zone around a ground spawn against vanilla, frame by frame for 240 frames. Flares of the
 * player team, idle, sit at several distances from the spawn: those within {@code dropZoneRadius + hitSize / 2 + 1} are
 * pushed out every frame ({@code UnitComp.update}), so position and velocity are compared each frame. At frame 120 a
 * wave runs: its shockwave kills every enemy unit within the radius and destroys the enemy buildings in it. The unit
 * calls vanilla sends (deaths, then the wrecks' destroys) are Shard's {@link UnitEvent}s, and the same walls stand
 * afterwards. The shockwave's deaths are compared as a set: Shard's spatial grid visits the units in another order than
 * vanilla's quadtree (the {@code sim.spatial} deviation); every other call is in order.
 */
class DropZoneOracleTest{
    static final int SIZE = 80, SPAWN = 40, FRAMES = 240, WAVE = 120;

    @Test
    void theDropZonePushesAndClearsAsVanillas(){
        Vanilla.emptyWorld(SIZE, SIZE);
        state.rules.disableUnitCap = true;
        mindustry.Vars.world.tile(SPAWN, SPAWN).setOverlay(Blocks.spawn);
        state.rules.waves = true;
        state.rules.waveTimer = false;
        state.rules.dropZoneRadius = 120f;
        // a group that spawns nothing still makes the world have spawns
        state.rules.spawns = Seq.with(new SpawnGroup(UnitTypes.dagger){{
            max = 0;
        }});
        int[] walls = {SPAWN + 5, SPAWN + 14, SPAWN + 16, SPAWN + 30};
        for(int x : walls) mindustry.Vars.world.tile(x, SPAWN + 3).setBlock(Blocks.copperWall, Team.sharded, 0);
        World world = VanillaWaves.world();
        Observer observer = world.observe(4096, List.of(UnitEvent.class), null);

        var vanilla = new ArrayList<mindustry.gen.Unit>();
        var shard = new ArrayList<Unit>();
        flares(world, vanilla, shard, new float[][]{{3, 0}, {0, 8}, {-10, 4}, {12, -6}, {0, -14.5f}, {15.2f, 2}, {-17, -2}, {2, 25}});
        Vanilla.runPosted();
        VanillaWaves.sync(world);

        int pushed = 0;
        Vanilla.net(Vanilla.NetMode.server, () -> Vanilla.captured().clear());
        int calls = 0;
        for(int frame = 0; frame < FRAMES; frame++){
            String at = "frame " + frame;
            if(frame == WAVE - 1){
                // the first flares have been pushed out by now: these meet the shockwave
                flares(world, vanilla, shard, new float[][]{{1, 1}, {-4, 2}, {6, -6}, {-9, -9}});
                Vanilla.runPosted();
            }
            if(frame == WAVE){
                Vanilla.net(Vanilla.NetMode.server, () -> logic.runWave());
                Waves.runWave(world);
            }
            Vanilla.net(Vanilla.NetMode.server, Vanilla::frame);
            world.tick();
            for(int i = 0; i < vanilla.size(); i++){
                mindustry.gen.Unit v = vanilla.get(i);
                Unit s = shard.get(i);
                assertEquals(v.isAdded(), s.isAdded(), at + " flare " + i + " added");
                if(!v.isAdded()) continue;
                assertEquals(v.x, s.x, at + " flare " + i + " x");
                assertEquals(v.y, s.y, at + " flare " + i + " y");
                assertEquals(v.vel.x, s.vel.x, at + " flare " + i + " vel x");
                assertEquals(v.vel.y, s.vel.y, at + " flare " + i + " vel y");
                assertEquals(v.health, s.health, at + " flare " + i + " health");
                if(frame < WAVE - 1 && i < 6 && v.vel.len() > 0f) pushed++;
            }
            var expected = new ArrayList<String>();
            List<Vanilla.Capture.Sent> sent = Vanilla.captured();
            for(; calls < sent.size(); calls++){
                String call = call(sent.get(calls).packet(), vanilla);
                if(call != null) expected.add(call);
            }
            var actual = new ArrayList<String>();
            for(WorldEvent event : observer.drain()){
                var e = (UnitEvent)event;
                int index = -1;
                for(int i = 0; i < shard.size(); i++) if(shard.get(i).id() == e.unit()) index = i;
                actual.add(e.getClass().getSimpleName() + " " + index);
            }
            if(frame == WAVE){
                // the shockwave visits units in the spatial grid's order, not vanilla's quadtree order (sim.spatial)
                assertEquals(deaths(expected), deaths(actual), at + ": the shockwave's deaths");
                expected.removeIf(call -> call.startsWith("Death"));
                actual.removeIf(call -> call.startsWith("Death"));
            }
            assertEquals(expected, actual, at + ": unit calls");
            assertEquals(arc.math.Mathf.rand.seed0, world.rand().getState(0), at + ": random");
        }
        for(int x : walls){
            boolean stands = mindustry.Vars.world.build(x, SPAWN + 3) != null;
            assertEquals(stands, world.buildings().at(world.tiles().pos(x, SPAWN + 3)) != null, "the wall at " + x);
        }
        assertNull(mindustry.Vars.world.build(walls[0], SPAWN + 3), "the shockwave destroyed the wall in the zone");
        assertNotNull(mindustry.Vars.world.build(walls[3], SPAWN + 3), "the wall outside the zone stands");
        assertTrue(pushed > 300, "the zone pushed the flares: " + pushed);
        assertFalse(vanilla.get(8).isAdded(), "the shockwave killed the flare beside the spawn");
        assertTrue(vanilla.get(7).isAdded(), "the farthest flare lives");
    }

    static void flares(World world, List<mindustry.gen.Unit> vanilla, List<Unit> shard, float[][] offsets){
        for(float[] offset : offsets){
            float x = (SPAWN + offset[0]) * 8f, y = (SPAWN + offset[1]) * 8f;
            mindustry.gen.Unit v = UnitTypes.flare.spawn(Team.sharded, x, y);
            v.controller(new NoAI());
            vanilla.add(v);
            Unit s = Unit.spawn(world, world.content().unit("flare"), Team.sharded.id, x, y, 0f);
            s.controller(new NoAi());
            shard.add(s);
        }
    }

    static List<String> deaths(List<String> calls){
        return calls.stream().filter(call -> call.startsWith("Death")).sorted().toList();
    }

    static String call(Object packet, List<mindustry.gen.Unit> units){
        return switch(packet){
            case UnitDeathCallPacket p -> "Death " + index(units, p.uid);
            case UnitDestroyCallPacket p -> "Destroy " + index(units, p.uid);
            default -> null;
        };
    }

    static int index(List<mindustry.gen.Unit> units, int id){
        for(int i = 0; i < units.size(); i++) if(units.get(i).id == id) return i;
        return -1;
    }
}
