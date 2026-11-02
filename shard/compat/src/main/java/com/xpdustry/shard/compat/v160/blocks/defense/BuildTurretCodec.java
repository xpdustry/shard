package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.defense.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.*;

/**
 * {@code BuildTurretBuild.write}/{@code read}: the aim ({@code rotation}), then the block unit's plan queue as
 * {@code TypeIO.writePlans} writes it: a short count (-1 for none), and per plan a breaking byte, the packed position
 * and, for a placement, the block, the rotation byte, a has-config byte (always 1) and the config as a {@code TypeIO}
 * object. Reading turns the unit to the aim and replaces its queue.
 *
 * <p>Vanilla quirk: {@code TypeIO.readPlan} returns null for a position outside the map, and {@code read} adds that
 * null to the queue; Shard drops it. A config the sim can't hold (a building or unit reference) is read as null.
 */
@Register
public final class BuildTurretCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("build-turret");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 0;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        var turret = (BuildTurret)building;
        out.f(turret.turretRotation);
        PlanQueue plans = turret.unit.plans;
        WirePlans.ContentIds contentIds = WirePlans.ContentIds.of(ids);
        out.s(plans.size());
        for(int i = 0; i < plans.size(); i++){
            BuildPlan plan = plans.get(i);
            out.b(plan.breaking ? 1 : 0);
            out.i(Point2.pack(plan.x, plan.y));
            if(!plan.breaking){
                out.s(ids.requireStream(ContentType.BLOCK, plan.block));
                out.b((byte)plan.rotation);
                // always has config
                out.b(1);
                WireObjects.writeObject(out, WirePlans.config(plan.config, contentIds));
            }
        }
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var turret = (BuildTurret)building;
        turret.readRotation(in.f());
        PlanQueue plans = turret.unit.plans;
        plans.clear();
        int amount = in.s();
        if(amount == -1) return;
        var world = building.world();
        var context = new FileWireContext(world, BuildingCodecs.builtIn(), ids);
        WirePlans.ContentIds contentIds = WirePlans.ContentIds.of(ids);
        for(int i = 0; i < amount; i++){
            int type = in.b();
            int position = in.i();
            int x = Point2.x(position), y = Point2.y(position);
            BuildPlan plan;
            if(type == 1){
                plan = BuildPlan.breaking(x, y);
            }else{
                int block = Math.max(0, ids.toCurrent(ContentType.BLOCK, in.s()));
                int rotation = in.b();
                in.b();
                Object config = held(WirePlans.value(WireObjects.readObjectSafe(in, context), contentIds));
                plan = BuildPlan.place(x, y, rotation, block, config);
            }
            // vanilla's readPlan gives null off the map, which its read adds to the queue
            if(world.tiles().inBounds(x, y)) plans.addLast(plan);
        }
    }

    /** A config the sim holds, or null for a reference to something only a live world has. */
    private static Object held(Object value){
        try{
            BuildPlan.writeConfig(value, new Writes(8));
            return value;
        }catch(RuntimeException e){
            return null;
        }
    }

    @Override
    public void skip(Tape in, int revision){
        in.f();
        int amount = in.s();
        for(int i = 0; i < amount; i++){
            int type = in.b();
            in.i();
            if(type != 1){
                in.s();
                in.b();
                in.b();
                skipObject(in);
            }
        }
    }

    /** Skips one {@code TypeIO} value, as {@code LogicValues.skip}. */
    private static void skipObject(Tape in){
        int type = in.b();
        switch(type){
            case WireObjects.NULL -> {}
            case WireObjects.INT, WireObjects.FLOAT, WireObjects.BUILDING, WireObjects.UNIT -> in.i();
            case WireObjects.LONG, WireObjects.DOUBLE -> in.l();
            case WireObjects.STRING -> {
                if(in.b() != 0) in.str();
            }
            case WireObjects.CONTENT, WireObjects.TECH_NODE -> {
                in.b();
                in.s();
            }
            case WireObjects.INT_SEQ -> in.b(in.s() * 4);
            case WireObjects.POINT2, WireObjects.VEC2 -> in.l();
            case WireObjects.POINT2_ARRAY -> in.b(in.ub() * 4);
            case WireObjects.BOOL, WireObjects.LEGACY, WireObjects.TEAM -> in.b();
            case WireObjects.LACCESS, WireObjects.UNIT_COMMAND -> in.s();
            case WireObjects.BYTES, WireObjects.BOOL_ARRAY -> in.b(in.i());
            case WireObjects.VEC2_ARRAY -> in.b(in.s() * 8);
            case WireObjects.INT_ARRAY -> in.b(in.s() * 4);
            case WireObjects.OBJECT_ARRAY -> {
                for(int i = in.i(); i > 0; i--) skipObject(in);
            }
            default -> throw new IllegalArgumentException("Unknown object type: " + type);
        }
    }
}
