package com.xpdustry.shard.oracle;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Oracle JVMs run without HotSpot's libm intrinsics (build-logic's {@code Oracle.jvmArgs}), so vanilla's {@code Math}
 * is the {@code StrictMath} Shard uses. A new JDK can add intrinsics (JDK 27 added {@code sinh}); this fails if the flag
 * stops covering one. Each function runs often enough for C2 to compile it.
 */
class LibmTest{
    record Function(String name, DoubleBinaryOperator math, DoubleBinaryOperator strict){}

    static final List<Function> functions = List.of(
        new Function("sin", (x, y) -> Math.sin(x), (x, y) -> StrictMath.sin(x)),
        new Function("cos", (x, y) -> Math.cos(x), (x, y) -> StrictMath.cos(x)),
        new Function("tan", (x, y) -> Math.tan(x), (x, y) -> StrictMath.tan(x)),
        new Function("asin", (x, y) -> Math.asin(x / 1000), (x, y) -> StrictMath.asin(x / 1000)),
        new Function("acos", (x, y) -> Math.acos(x / 1000), (x, y) -> StrictMath.acos(x / 1000)),
        new Function("atan", (x, y) -> Math.atan(x), (x, y) -> StrictMath.atan(x)),
        new Function("exp", (x, y) -> Math.exp(x / 2), (x, y) -> StrictMath.exp(x / 2)),
        new Function("log", (x, y) -> Math.log(Math.abs(x)), (x, y) -> StrictMath.log(Math.abs(x))),
        new Function("log10", (x, y) -> Math.log10(Math.abs(x)), (x, y) -> StrictMath.log10(Math.abs(x))),
        new Function("log1p", (x, y) -> Math.log1p(Math.abs(x)), (x, y) -> StrictMath.log1p(Math.abs(x))),
        new Function("expm1", (x, y) -> Math.expm1(x / 2), (x, y) -> StrictMath.expm1(x / 2)),
        new Function("sinh", (x, y) -> Math.sinh(x / 2), (x, y) -> StrictMath.sinh(x / 2)),
        new Function("cosh", (x, y) -> Math.cosh(x / 2), (x, y) -> StrictMath.cosh(x / 2)),
        new Function("tanh", (x, y) -> Math.tanh(x / 50), (x, y) -> StrictMath.tanh(x / 50)),
        new Function("cbrt", (x, y) -> Math.cbrt(x), (x, y) -> StrictMath.cbrt(x)),
        new Function("sqrt", (x, y) -> Math.sqrt(Math.abs(x)), (x, y) -> StrictMath.sqrt(Math.abs(x))),
        new Function("atan2", Math::atan2, StrictMath::atan2),
        new Function("hypot", Math::hypot, StrictMath::hypot),
        new Function("pow", (x, y) -> Math.pow(Math.abs(x) / 10, y / 100), (x, y) -> StrictMath.pow(Math.abs(x) / 10, y / 100)));

    @Test
    void mathIsStrictMath(){
        // without the flag, 2-20% of these inputs differ in sin, cos, tan, exp, log, log10, sinh, tanh, cbrt and pow
        int n = 1 << 16;
        double[] xs = new double[n], ys = new double[n];
        var random = new SplittableRandom(42);
        for(int i = 0; i < n; i++){
            xs[i] = (random.nextDouble() * 2 - 1) * (i % 3 == 0 ? 1000 : i % 3 == 1 ? 10 : 0.5);
            ys[i] = (random.nextDouble() * 2 - 1) * 1000;
        }
        var differing = new TreeMap<String, Integer>();
        for(Function function : functions){
            int bad = 0;
            for(int pass = 0; pass < 4; pass++){ // the last passes run compiled code
                bad = 0;
                for(int i = 0; i < n; i++){
                    double math = function.math.applyAsDouble(xs[i], ys[i]), strict = function.strict.applyAsDouble(xs[i], ys[i]);
                    if(Double.doubleToRawLongBits(math) != Double.doubleToRawLongBits(strict)) bad++;
                }
            }
            if(bad > 0) differing.put(function.name, bad);
        }
        assertEquals(Map.of(), differing, "inputs where Math differs from StrictMath, of " + n);
    }
}
