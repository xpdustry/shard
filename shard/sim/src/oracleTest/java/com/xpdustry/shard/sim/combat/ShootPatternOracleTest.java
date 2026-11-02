package com.xpdustry.shard.sim.combat;

import arc.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.oracle.*;
import mindustry.entities.pattern.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate for {@link ShootPattern}: the pattern of every vanilla weapon (mirrors flipped) and turret, fired at shot
 * counts 0 to {@value #COUNTS}, hands out vanilla's bullets: offset, rotation, delay, whether a mover rides along, and
 * barrel increments, in order, with the same random draws.
 */
class ShootPatternOracleTest{
    static final int COUNTS = 7;

    @Test
    void everyPatternShootsAsVanillas(){
        Vanilla.boot();
        int patterns = 0;
        for(UnitType type : content.units()){
            List<WeaponDef> defs = CombatOracle.VANILLA.unit(type.name).weapons();
            assertEquals(type.weapons.size, defs.size(), type.name);
            for(int i = 0; i < defs.size(); i++){
                same(type.name + "/" + i, type.weapons.get(i).shoot, defs.get(i).shoot());
                patterns++;
            }
        }
        for(Block block : content.blocks()){
            if(!(block instanceof Turret turret)) continue;
            Struct fields = CombatOracle.VANILLA.block(block.name).fields();
            same(block.name, turret.shoot, fields.struct("shoot"));
            patterns++;
        }
        assertTrue(patterns > 150, patterns + " patterns");
    }

    static void same(String at, mindustry.entities.pattern.ShootPattern vanilla, Struct fields){
        ShootPattern shard = ShootPattern.of(fields);
        assertEquals(vanilla.shots, shard.shots, at + " shots");
        assertEquals(vanilla.firstShotDelay, shard.firstShotDelay, at + " firstShotDelay");
        assertEquals(vanilla.shotDelay, shard.shotDelay, at + " shotDelay");
        for(int total = 0; total <= COUNTS; total++){
            Mathf.rand.setSeed(total * 31L + 7);
            var rand = new com.xpdustry.shard.common.math.Rand(0);
            rand.setState(Mathf.rand.seed0, Mathf.rand.seed1);
            var expected = new ArrayList<String>();
            vanilla.shoot(total, (x, y, rotation, delay, mover) -> expected.add(shot(x, y, rotation, delay, mover != null)),
                () -> expected.add("barrel"));
            var actual = new ArrayList<String>();
            shard.shoot(rand, total, (x, y, rotation, delay, mover) -> actual.add(shot(x, y, rotation, delay, mover != null)),
                () -> actual.add("barrel"));
            assertEquals(expected, actual, at + " at " + total + " shots (" + vanilla.getClass().getSimpleName() + ")");
            assertEquals(Mathf.rand.seed0, rand.getState(0), at + ": random draws");
        }
    }

    static String shot(float x, float y, float rotation, float delay, boolean mover){
        return Float.floatToIntBits(x) + "," + Float.floatToIntBits(y) + " r" + Float.floatToIntBits(rotation) + " d" + delay
            + (mover ? " mover" : "") + " (" + x + ", " + y + ", " + rotation + ")";
    }
}
