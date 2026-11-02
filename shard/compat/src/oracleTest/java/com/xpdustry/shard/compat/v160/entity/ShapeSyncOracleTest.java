package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.common.geom.Vec2;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.KindRegistry;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: the synced and saved state of every movement shape stays byte-exact while the units move (WP44). One unit of
 * each shape, and of the legacy classes with their own class ids, walks, turns, boosts and slides along a wall line
 * for {@value #FRAMES} frames, in vanilla and in Shard, and every frame each unit's {@code writeSync} and {@code write}
 * equal vanilla's byte for byte: position, rotation, velocity, elevation, and a mech's {@code baseRotation}, which its
 * legs turn as they walk. Legs, treads and segments are transient in vanilla, so no bytes carry them;
 * {@code ShapeTraceOracleTest} holds them to vanilla.
 */
class ShapeSyncOracleTest{
    static final int SIZE = 48, FRAMES = 300, LEG = 50;
    static final Epoch EPOCH = Epoch.vanilla();
    static final EntityProjector PROJECTOR = new EntityProjector(EPOCH, BuildingCodecs.builtIn());
    /** One type per class: MechUnit, MechUnitLegacyNova, LegsUnit, LegsUnitLegacyArkyid, TankUnit, CrawlUnit, UnitWaterMove, ElevationMoveUnit, UnitEntity. */
    static final List<String> TYPES = List.of("dagger", "fortress", "nova", "atrax", "arkyid", "stell", "latum", "risso", "elude", "flare");
    static int frame;

    @Test
    void movingShapesSyncAsVanilla(){
        Vanilla.emptyWorld(SIZE, SIZE);
        state.rules.disableUnitCap = true;
        state.rules.waves = false;
        World world = new World(WorldConfig.of(EPOCH, SIZE, SIZE, 1).withKinds(KindRegistry.builtIn().withPassthrough()));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, EPOCH.block("stone").id());
        world.buildings().endLoad(false);
        world.part(WorldRules.class).rules.disableUnitCap = true;
        for(int ty = 2; ty < SIZE - 2; ty++){
            mindustry.Vars.world.tile(24, ty).setBlock(content.block("stone-wall"), Team.derelict, 0);
            world.buildings().setBlock(24, ty, EPOCH.block("stone-wall"), 0, 0, Cause.SERVER);
        }

        var vanilla = new ArrayList<mindustry.gen.Unit>();
        var shard = new ArrayList<com.xpdustry.shard.sim.unit.Unit>();
        for(int i = 0; i < TYPES.size(); i++){
            String type = TYPES.get(i);
            // a column of units west of the wall line, far enough apart that physics never pushes them
            float x = 18 * 8f, y = (4 + i * 4) * 8f, rotation = i * 37f;
            mindustry.gen.Unit v = content.unit(type).spawn(Team.sharded, x, y, rotation);
            v.controller(new VanillaWalk(i));
            vanilla.add(v);
            var s = com.xpdustry.shard.sim.unit.Unit.create(world, EPOCH.unit(type), Team.sharded.id);
            s.id(v.id);
            s.rotation = rotation;
            s.set(x, y);
            s.add();
            s.controller(new ShardWalk(i));
            shard.add(s);
        }
        Vanilla.runPosted();

        float novaRise = 0f;
        for(frame = 0; frame < FRAMES; frame++){
            Vanilla.frame();
            world.tick();
            novaRise = Math.max(novaRise, shard.get(2).elevation);
            var context = WorldWireContext.capture(world);
            for(int i = 0; i < vanilla.size(); i++){
                var v = vanilla.get(i);
                var s = shard.get(i);
                String what = "frame " + frame + " " + v.type.name + " (" + v.getClass().getSimpleName() + ")";
                assertEquals(v.isAdded(), s.isAdded(), what + " added");
                if(!v.isAdded()) continue;
                int classId = v.classId() & 0xff;
                UnitProjection projection = PROJECTOR.unit(s);
                EntitySyncOracleTest.compare(what + " sync", Golden.bytes(v::writeSync), out -> EntityClasses.writeSync(out, classId, projection, context));
                EntitySyncOracleTest.compare(what + " save", Golden.bytes(v::write), out -> EntityClasses.writeSave(out, classId, projection, context));
            }
        }
        var mech = (MoveShape.Mech)shard.get(0).shape;
        assertNotEquals(0f, mech.baseRotation, "the dagger's legs turned");
        assertEquals(1f, novaRise, "the nova boosted all the way");
    }

    /** Toward the wall and back, alternating moveAt, rotateMove and approach; boosting types boost on every other leg. */
    static float[] target(int index){
        int leg = frame / LEG;
        float x = leg % 2 == 0 ? 30 * 8f : 10 * 8f;
        return new float[]{x, (4 + index * 4) * 8f + (leg % 3 - 1) * 12f};
    }

    static int mode(int index){
        return (frame / LEG + index) % 3;
    }

    static boolean boost(){
        return frame / LEG % 2 == 1;
    }

    static final class VanillaWalk implements mindustry.entities.units.UnitController{
        final int index;
        mindustry.gen.Unit unit;

        VanillaWalk(int index){
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
            float[] t = target(index);
            var vec = new arc.math.geom.Vec2(t[0], t[1]).sub(unit.x, unit.y).limit(unit.speed());
            switch(mode(index)){
                case 0 -> unit.moveAt(vec);
                case 1 -> unit.rotateMove(vec);
                default -> unit.approach(frame % LEG < LEG / 2 ? vec : arc.math.geom.Vec2.ZERO);
            }
            unit.lookAt(t[0], t[1]);
            if(unit.type.canBoost) unit.updateBoosting(boost());
        }
    }

    static final class ShardWalk implements UnitController{
        final int index;

        ShardWalk(int index){
            this.index = index;
        }

        @Override
        public void updateUnit(com.xpdustry.shard.sim.unit.Unit unit){
            float[] t = target(index);
            var vec = new Vec2(t[0], t[1]).sub(unit.x, unit.y).limit(unit.speed());
            switch(mode(index)){
                case 0 -> unit.moveAt(vec);
                case 1 -> unit.rotateMove(vec);
                default -> unit.approach(frame % LEG < LEG / 2 ? vec : new Vec2());
            }
            unit.lookAt(t[0], t[1]);
            if(unit.params().canBoost()) unit.updateBoosting(boost());
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
