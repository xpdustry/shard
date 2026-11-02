package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/** Controllers that do nothing, for fixture units on both sides. */
final class Idle{
    private Idle(){
    }

    static final class VanillaIdle implements mindustry.entities.units.UnitController{
        mindustry.gen.Unit unit;

        @Override
        public void unit(mindustry.gen.Unit unit){
            this.unit = unit;
        }

        @Override
        public mindustry.gen.Unit unit(){
            return unit;
        }
    }

    static final class ShardIdle implements UnitController{
        @Override
        public void updateUnit(Unit unit){
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
