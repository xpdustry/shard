package com.xpdustry.shard.testkit.kinds;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.defense.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.testkit.*;

/**
 * {@link KindAdapter}s for the projectors and support turrets (W3.6) whose public fields aren't vanilla's: the aim is
 * vanilla's {@code rotation}; a unit target is {@code "#id"}; the kept bullet object and the target lists, which
 * vanilla holds as objects, stay out.
 */
public final class SupportAdapters{
    private SupportAdapters(){
    }

    @Register
    public static final class PointDefenseAdapter implements KindAdapter<PointDefenseTurret>{
        @Override
        public KindKey kind(){
            return KindKey.of("point-defense-turret");
        }

        @Override
        public void fields(PointDefenseTurret b, Fields out){
            out.reflect(b).remove("turretRotation").put("rotation", b.turretRotation).remove("target");
        }
    }

    @Register
    public static final class TractorBeamAdapter implements KindAdapter<TractorBeamTurret>{
        @Override
        public KindKey kind(){
            return KindKey.of("tractor-beam-turret");
        }

        @Override
        public void fields(TractorBeamTurret b, Fields out){
            out.reflect(b).remove("turretRotation").put("rotation", b.turretRotation).remove("target");
            if(b.target != null) out.put("target", Values.unit(b.target.id()));
        }
    }

    @Register
    public static final class RepairTurretAdapter implements KindAdapter<RepairTurret>{
        @Override
        public KindKey kind(){
            return KindKey.of("repair-turret");
        }

        @Override
        public void fields(RepairTurret b, Fields out){
            out.reflect(b).remove("turretRotation").put("rotation", b.turretRotation).remove("target");
            if(b.target != null) out.put("target", Values.unit(b.target.id()));
        }
    }

    @Register
    public static final class RepairTowerAdapter implements KindAdapter<RepairTower>{
        @Override
        public KindKey kind(){
            return KindKey.of("repair-tower");
        }

        @Override
        public void fields(RepairTower b, Fields out){
            out.reflect(b).remove("targets");
        }
    }

    @Register
    public static final class RegenProjectorAdapter implements KindAdapter<RegenProjector>{
        @Override
        public KindKey kind(){
            return KindKey.of("regen-projector");
        }

        @Override
        public void fields(RegenProjector b, Fields out){
            out.reflect(b).remove("targets");
        }
    }
}
