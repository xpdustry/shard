package com.xpdustry.shard.logic;

import arc.graphics.*;
import arc.math.*;
import arc.util.*;
import arc.util.noise.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The mlog math and parsing helpers against Arc's own classes (from the vanilla jar), bit for bit. */
class MlogMathTest{
    @Test
    void parseDouble(){
        Random random = new Random(1);
        String[] fixed = {"0", "-0", "1", "-1", ".5", "5.", "+3", "1e3", "1E-3", "1.5e+2", "0.1", "0.30000000000000004",
        "9007199254740993", "1e308", "1e309", "-1e308", "abc", "", "-", "1e", "e5", "1.2.3", "0x10", "1_000", " 1", "NaN",
        "Infinity", "123456789012345678901234567890", "4.9e-324", "1e-400", "00012", "-.5e1"};
        for(String s : fixed) same(s);
        String chars = "0123456789.-+eE";
        for(int i = 0; i < 50_000; i++){
            StringBuilder sb = new StringBuilder();
            for(int j = random.nextInt(12); j >= 0; j--) sb.append(chars.charAt(random.nextInt(chars.length())));
            same(sb.toString());
        }
    }

    private static void same(String s){
        double arc = Strings.parseDouble(s, Double.MIN_VALUE), mine = MlogMath.parseDouble(s, Double.MIN_VALUE);
        assertEquals(Double.doubleToRawLongBits(arc), Double.doubleToRawLongBits(mine), "'" + s + "'");
        assertEquals(Strings.canParseInt(s), MlogMath.canParseInt(s), "'" + s + "'");
    }

    @Test
    void noiseAndAngles(){
        Random random = new Random(2);
        for(int i = 0; i < 20_000; i++){
            double x = (random.nextDouble() - 0.5) * 1e4, y = (random.nextDouble() - 0.5) * 1e4;
            assertEquals(Simplex.raw2d(0, x, y), MlogMath.noise(0, x, y), x + "," + y);
            assertEquals((double)Angles.angle((float)x, (float)y), MlogMath.angle(x, y));
            assertEquals((double)Angles.angleDist((float)x, (float)y), MlogMath.angleDiff(x, y));
            assertEquals((double)Mathf.dst((float)x, (float)y), MlogMath.len(x, y));
        }
    }

    @Test
    void colors(){
        Random random = new Random(3);
        for(int i = 0; i < 10_000; i++){
            float r = random.nextFloat(), g = random.nextFloat(), b = random.nextFloat(), a = random.nextFloat();
            assertEquals(Color.toDoubleBits(r, g, b, a), MlogMath.packColor(r, g, b, a));
        }
        Colors.getColors().each((name, color) -> assertEquals(color.toDoubleBits(), LogicColors.get(name), name));
    }
}
