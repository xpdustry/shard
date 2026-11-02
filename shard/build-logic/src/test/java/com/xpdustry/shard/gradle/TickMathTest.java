package com.xpdustry.shard.gradle;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link TickMath} on small compiled classes: each planted call to Math's transcendentals is found. */
class TickMathTest{
    @TempDir
    Path out;

    List<String> scan(String source) throws IOException{
        Path file = out.resolve("src/p/Planted.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        var errors = new StringWriter();
        boolean ok = javac.getTask(errors, null, null, List.of("--release", "25", "-d", out.resolve("classes").toString()), null,
            javac.getStandardFileManager(null, null, null).getJavaFileObjects(file)).call();
        assertTrue(ok, errors.toString());
        return TickMath.scan(List.of(out.resolve("classes"))).stream().map(TickMath.Violation::toString).toList();
    }

    @Test
    void mathTranscendentalsAreFound() throws IOException{
        List<String> found = scan("""
            package p;
            public class Planted{
                double a(double x){ return Math.sin(x) + Math.pow(x, 2); }
                double b(double x, double y){ return Math.atan2(y, x) * Math.exp(x); }
            }
            """);
        assertEquals(List.of(
            "p.Planted.a: java.lang.Math.pow (call StrictMath: HotSpot's intrinsics for Math differ by CPU and JDK)",
            "p.Planted.a: java.lang.Math.sin (call StrictMath: HotSpot's intrinsics for Math differ by CPU and JDK)",
            "p.Planted.b: java.lang.Math.atan2 (call StrictMath: HotSpot's intrinsics for Math differ by CPU and JDK)",
            "p.Planted.b: java.lang.Math.exp (call StrictMath: HotSpot's intrinsics for Math differ by CPU and JDK)"), found);
    }

    @Test
    void strictMathAndExactMathPass() throws IOException{
        assertEquals(List.of(), scan("""
            package p;
            public class Planted{
                double a(double x){ return StrictMath.sin(x) + StrictMath.pow(x, 2) + Math.sqrt(x) + Math.abs(x)
                    + Math.floor(x) + Math.max(x, 1) + Math.fma(x, x, x); }
            }
            """));
    }
}
