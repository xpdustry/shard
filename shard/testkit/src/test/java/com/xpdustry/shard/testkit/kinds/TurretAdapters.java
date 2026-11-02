package com.xpdustry.shard.testkit.kinds;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.fire.*;
import com.xpdustry.shard.testkit.*;

import java.util.*;

/**
 * {@link KindAdapter}s for the turret kinds (W2.16): their public fields as reflected, renamed to vanilla's where Shard
 * splits or renames them. The aim is vanilla's {@code rotation}, the aim point its {@code targetPos} ({@code [x, y]}),
 * the block unit its {@code unit} ({@code "#id"}, drawn at construction), and a fire target its {@code target}. The
 * ammo arrays stay out: vanilla's {@code ammo} is a {@code Seq} of entries, which projects only while empty.
 */
public final class TurretAdapters{
    private TurretAdapters(){
    }

    static void fields(TurretBuild b, Fields out){
        out.reflect(b)
            .remove("turretRotation").put("rotation", b.turretRotation)
            .remove("targetX").remove("targetY").put("targetPos", List.of(b.targetX, b.targetY))
            .remove("blockUnitId").remove("blockUnit").put("unit", Values.unit(b.blockUnitId))
            .remove("ammoIds").remove("ammoAmounts").remove("ammoSize")
            .remove("targetFire");
        if(b.targetFire != -1){
            // the live fire holding the kept object; vanilla's reset object has no Shard twin, so it stays null
            EntityGroups groups = EntityGroups.of(b.world());
            for(int i = 0; i < groups.all().size(); i++){
                if(groups.all().get(i) instanceof Fire fire && fire.object == b.targetFire) out.put("target", Values.unit(fire.id()));
            }
        }
    }

    @Register
    public static final class ItemTurretAdapter implements KindAdapter<ItemTurret>{
        @Override
        public KindKey kind(){
            return KindKey.of("item-turret");
        }

        @Override
        public void fields(ItemTurret building, Fields out){
            TurretAdapters.fields(building, out);
        }
    }

    @Register
    public static final class PowerTurretAdapter implements KindAdapter<PowerTurret>{
        @Override
        public KindKey kind(){
            return KindKey.of("power-turret");
        }

        @Override
        public void fields(PowerTurret building, Fields out){
            TurretAdapters.fields(building, out);
        }
    }

    @Register
    public static final class LiquidTurretAdapter implements KindAdapter<LiquidTurret>{
        @Override
        public KindKey kind(){
            return KindKey.of("liquid-turret");
        }

        @Override
        public void fields(LiquidTurret building, Fields out){
            TurretAdapters.fields(building, out);
        }
    }

    @Register
    public static final class LaserTurretAdapter implements KindAdapter<LaserTurret>{
        @Override
        public KindKey kind(){
            return KindKey.of("laser-turret");
        }

        @Override
        public void fields(LaserTurret building, Fields out){
            TurretAdapters.fields(building, out);
        }
    }

    @Register
    public static final class ContinuousTurretAdapter implements KindAdapter<ContinuousTurret>{
        @Override
        public KindKey kind(){
            return KindKey.of("continuous-turret");
        }

        @Override
        public void fields(ContinuousTurret building, Fields out){
            TurretAdapters.fields(building, out);
        }
    }

    @Register
    public static final class ContinuousLiquidTurretAdapter implements KindAdapter<ContinuousLiquidTurret>{
        @Override
        public KindKey kind(){
            return KindKey.of("continuous-liquid-turret");
        }

        @Override
        public void fields(ContinuousLiquidTurret building, Fields out){
            TurretAdapters.fields(building, out);
        }
    }
}
