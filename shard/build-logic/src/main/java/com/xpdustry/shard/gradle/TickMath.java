package com.xpdustry.shard.gradle;

import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.nio.file.*;
import java.util.*;

/**
 * What {@code checkTickMath} checks (docs/development.md "Determinism rules"): per-tick code computes transcendentals with
 * {@link StrictMath}, never {@link Math}'s. HotSpot replaces {@code Math.sin}, {@code pow} and the rest with
 * intrinsics whose last bit depends on the CPU and the JDK, so a world would hash differently on another machine.
 * {@code StrictMath} is fdlibm on every platform. It reads class files with the JDK's ClassFile API and runs in about
 * a second.
 */
public final class TickMath{
    /** A call to a forbidden {@code Math} function: where ({@code class.method}) and what. */
    public record Violation(String where, String what){
        @Override
        public String toString(){
            return where + ": " + what + " (call StrictMath: HotSpot's intrinsics for Math differ by CPU and JDK)";
        }
    }

    /** Math's functions that HotSpot may compute with intrinsics instead of fdlibm. {@code sqrt} is exact everywhere. */
    static final Set<String> INTRINSIC_MATH = Set.of("sin", "cos", "tan", "asin", "acos", "atan", "atan2", "exp", "log",
        "log10", "log1p", "expm1", "pow", "cbrt", "hypot", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
        "IEEEremainder");

    private TickMath(){}

    /** Every violation in the class files under {@code roots}, sorted and without duplicates. */
    public static List<Violation> scan(List<Path> roots) throws IOException{
        var out = new TreeSet<Violation>(Comparator.comparing(Violation::toString));
        for(Path root : roots){
            if(!Files.isDirectory(root)) continue;
            try(var files = Files.walk(root)){
                for(Path file : files.filter(f -> f.toString().endsWith(".class")).sorted().toList()){
                    scan(ClassFile.of().parse(Files.readAllBytes(file)), out);
                }
            }
        }
        return List.copyOf(out);
    }

    private static void scan(ClassModel model, Set<Violation> out){
        String self = model.thisClass().asInternalName().replace('/', '.');
        for(MethodModel method : model.methods()){
            String where = self + "." + method.methodName().stringValue();
            method.code().ifPresent(code -> {
                for(CodeElement element : code){
                    if(element instanceof InvokeInstruction invoke && invoke.owner().asInternalName().equals("java/lang/Math")
                        && INTRINSIC_MATH.contains(invoke.name().stringValue())){
                        out.add(new Violation(where, "java.lang.Math." + invoke.name().stringValue()));
                    }
                }
            });
        }
    }
}
