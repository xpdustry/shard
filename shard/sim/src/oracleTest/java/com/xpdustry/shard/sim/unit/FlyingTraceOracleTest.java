package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.geom.Vec2;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate for the flying movement shape ({@link MoveShape.Flying}, the base every shape starts from): {@code alpha}
 * ({@code omniMovement}: {@code moveAt}) and {@code flare} (not omni: {@code rotateMove}) fly the same waypoint script
 * in vanilla and in Shard for {@value #FRAMES} frames. The script steers with {@code movePref} toward each waypoint at
 * {@code speed()}, looks at it with {@code lookAt}, and brakes with {@code approach}; two waypoints lie past the map
 * edge, so {@code UnitComp}'s bounds repel the units ({@code velAddNet}) and clamp them.
 *
 * <p>Tolerance: units are Tier C (bounded drift, corrected by snapshots), but a lone flying unit has nothing that drifts,
 * so this holds them to bit for bit: position, rotation, velocity, drag and elevation are equal every frame.
 */
class FlyingTraceOracleTest{
    static final int SIZE = 48, FRAMES = 900, LEG = 75;
    /** Waypoints in world units: across the map, past the left and top edges, and back. */
    static final float[][] WAYPOINTS = {{300f, 260f}, {60f, 80f}, {-120f, 200f}, {200f, 140f}, {200f, 520f}, {330f, 40f},
        {100f, 300f}, {250f, 250f}, {20f, 20f}, {370f, 370f}, {180f, 60f}, {200f, 200f}};

    @Test
    void alphaAndFlareFollowVanillaBitForBit(){
        Vanilla.emptyWorld(SIZE, SIZE);
        state.rules.disableUnitCap = true;
        state.rules.waves = false;
        World world = UnitWorlds.world(SIZE, SIZE, 3);
        var vanilla = new ArrayList<mindustry.gen.Unit>();
        var shard = new ArrayList<Unit>();
        for(String type : List.of("alpha", "flare")){
            for(int i = 0; i < 2; i++){
                float x = 120f + i * 100f, y = 150f + i * 30f, rotation = i * 45f;
                // far apart, so physics never pushes them
                mindustry.gen.Unit v = content.unit(type).spawn(Team.sharded, x + (type.equals("flare") ? 0f : 0.5f), y + (type.equals("flare") ? 100f : 0f));
                v.rotation = rotation;
                int index = vanilla.size();
                v.controller(new VanillaScript(index));
                vanilla.add(v);
                Unit s = Unit.spawn(world, com.xpdustry.shard.sim.tile.VanillaWorlds.VANILLA.unit(type), Team.sharded.id, v.x, v.y, rotation);
                s.controller(new ShardScript(index));
                shard.add(s);
            }
        }
        Vanilla.runPosted();
        for(int frame = 0; frame < FRAMES; frame++){
            VanillaScript.frame = ShardScript.frame = frame;
            Vanilla.frame();
            world.tick();
            for(int i = 0; i < vanilla.size(); i++){
                var v = vanilla.get(i);
                var s = shard.get(i);
                String at = "frame " + frame + " " + v.type.name + " #" + i;
                same(at + " x", v.x, s.x);
                same(at + " y", v.y, s.y);
                same(at + " rotation", v.rotation, s.rotation);
                same(at + " vel.x", v.vel.x, s.vel.x);
                same(at + " vel.y", v.vel.y, s.vel.y);
                same(at + " drag", v.drag, s.drag);
                same(at + " elevation", v.elevation, s.elevation);
                assertEquals(v.isAdded(), s.isAdded(), at + " added");
            }
        }
        assertTrue(shard.stream().allMatch(Unit::isAdded), "every unit flew the whole script");
    }

    static void same(String what, float vanilla, float shard){
        assertEquals(Float.floatToIntBits(vanilla), Float.floatToIntBits(shard), () -> what + ": " + shard + ", vanilla " + vanilla);
    }

    /** The waypoint of {@code frame} for unit {@code index}, and whether it brakes this frame. */
    static float[] waypoint(int frame, int index){
        return WAYPOINTS[(frame / LEG + index * 3) % WAYPOINTS.length];
    }

    static boolean braking(int frame){
        return frame % LEG >= LEG - 12;
    }

    /** The script on vanilla's API. */
    static final class VanillaScript implements mindustry.entities.units.UnitController{
        static int frame;
        final int index;
        mindustry.gen.Unit unit;

        VanillaScript(int index){
            this.index = index;
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
            float[] target = waypoint(frame, index);
            if(braking(frame)){
                unit.approach(arc.math.geom.Vec2.ZERO);
            }else{
                var vec = new arc.math.geom.Vec2(target[0], target[1]).sub(unit.x, unit.y).limit(unit.speed());
                unit.movePref(vec);
            }
            unit.lookAt(target[0], target[1]);
        }
    }

    /** The same script on Shard's API. */
    static final class ShardScript implements com.xpdustry.shard.sim.unit.UnitController{
        static int frame;
        final int index;

        ShardScript(int index){
            this.index = index;
        }

        @Override
        public void updateUnit(Unit unit){
            float[] target = waypoint(frame, index);
            if(braking(frame)){
                unit.approach(new Vec2());
            }else{
                var vec = new Vec2(target[0], target[1]).sub(unit.x, unit.y).limit(unit.speed());
                unit.movePref(vec);
            }
            unit.lookAt(target[0], target[1]);
        }

        @Override
        public com.xpdustry.shard.sim.unit.UnitController copy(CopyContext context){
            return this;
        }

        @Override
        public void hash(Hasher hasher){
        }
    }
}
