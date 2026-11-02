package com.xpdustry.shard.content.epoch;

import com.xpdustry.shard.content.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** The getters {@code Defs} reads once answer what the fields say, for every vanilla unit and weapon, twice over. */
class CachedGettersTest{
    @Test
    void cachedGettersReadTheFields(){
        Epoch vanilla = Epoch.vanilla();
        for(int round = 0; round < 2; round++){
            for(int i = 0; i < vanilla.units(); i++){
                UnitDef unit = vanilla.unit(i);
                String name = unit.name();
                assertEquals(Float.floatToRawIntBits(unit.fields().f("buildSpeed")), Float.floatToRawIntBits(unit.buildSpeed()), name);
                assertEquals(Float.floatToRawIntBits(unit.fields().f("aimDst")), Float.floatToRawIntBits(unit.aimDst()), name);
                assertEquals(unit.fields().b("isEnemy"), unit.isEnemy(), name);
                assertEquals(unit.fields().kind().schema().entity(unit.entity()), unit.entityClass(), name);
                for(WeaponDef weapon : unit.weapons()) assertEquals(weapon.fields().b("controllable"), weapon.controllable(), name + " " + weapon.name());
            }
        }
    }
}
