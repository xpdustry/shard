package com.xpdustry.shard.compat.v160.fog;

import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Scripted fighters, the same on both sides: each walks to one end of its beat and back, turning every {@code period}
 * frames ({@link #frame}), and every frame picks the closest enemy it may target ({@code Units.closestTarget}, which
 * leaves out units in fog to its team), aims at it and fires while it has one. A beat whose ends are one point stands.
 * The target each picked this frame is in {@link #VANILLA} and {@link #SHARD}, by unit id relative to the run's base.
 */
final class Patrols{
    /** The fixture's frame, which both sides' beats turn on. */
    static int frame;
    static int vanillaBase, shardBase;
    static final SortedMap<Integer, String> VANILLA = new TreeMap<>(), SHARD = new TreeMap<>();

    private Patrols(){
    }

    static String label(int id){
        return "unit " + id;
    }

    static final class VanillaPatrol implements mindustry.entities.units.UnitController{
        final float ax, ay, bx, by, range;
        final int period;
        mindustry.gen.Unit unit;

        VanillaPatrol(float ax, float ay, float bx, float by, int period, float range){
            this.ax = ax;
            this.ay = ay;
            this.bx = bx;
            this.by = by;
            this.period = period;
            this.range = range;
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
            boolean back = (frame / period) % 2 == 1;
            float tx = back ? ax : bx, ty = back ? ay : by;
            if(!unit.within(tx, ty, 2f)){
                unit.movePref(new arc.math.geom.Vec2(tx - unit.x, ty - unit.y).limit(unit.speed()));
                unit.lookAt(tx, ty);
            }
            mindustry.gen.Teamc target = mindustry.entities.Units.closestTarget(unit.team, unit.x, unit.y, range);
            VANILLA.put(unit.id - vanillaBase, switch(target){
                case null -> "-";
                case mindustry.gen.Unit u -> label(u.id - vanillaBase);
                case mindustry.gen.Building b -> "build " + b.tileX() + "," + b.tileY();
                default -> target.toString();
            });
            if(target != null) unit.aim(target.getX(), target.getY());
            unit.controlWeapons(true, target != null);
        }
    }

    /** A record, so a checkpoint of the world carries it. */
    record ShardPatrol(float ax, float ay, float bx, float by, int period, float range) implements UnitController{
        @Override
        public void updateUnit(Unit unit){
            boolean back = (frame / period) % 2 == 1;
            float tx = back ? ax : bx, ty = back ? ay : by;
            if(!com.xpdustry.shard.common.math.Mathf.within(unit.x, unit.y, tx, ty, 2f)){
                unit.movePref(new com.xpdustry.shard.common.geom.Vec2(tx - unit.x, ty - unit.y).limit(unit.speed()));
                unit.lookAt(tx, ty);
            }
            World world = unit.world();
            Entity target = SpatialIndex.of(world).closestTarget(unit.team, unit.x, unit.y, range, world.buildings().targets());
            SHARD.put(unit.id() - shardBase, switch(target){
                case null -> "-";
                case Unit u -> label(u.id() - shardBase);
                case Building b -> "build " + b.tileX() + "," + b.tileY();
                default -> target.toString();
            });
            if(target instanceof Unit u) Weapons.aim(unit, u.x, u.y);
            else if(target instanceof Building b) Weapons.aim(unit, b.x(), b.y());
            Weapons.controlWeapons(unit, true, target != null);
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
