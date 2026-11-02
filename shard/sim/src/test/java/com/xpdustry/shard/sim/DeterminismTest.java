package com.xpdustry.shard.sim;

import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.constantpool.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reads every compiled class of {@code :sim}'s main source set and fails on anything that makes a world depend on
 * more than its inputs (ARCHITECTURE.md §2, §4): clocks, threads, unseeded randomness, transcendental
 * {@code java.lang.Math} (use {@code :common}'s {@code Mathf}, which goes through {@code StrictMath}), hash-ordered
 * collections, and the modules {@code :sim} must never see.
 */
class DeterminismTest{
    /** Whole classes, by internal-name prefix. */
    static final List<String> FORBIDDEN_TYPES = List.of(
        "java/util/HashMap", "java/util/HashSet", "java/util/concurrent/ConcurrentHashMap", "java/util/Random",
        "java/util/concurrent/ThreadLocalRandom", "java/lang/Thread", "java/util/concurrent/Executor",
        "java/util/concurrent/ForkJoin", "java/util/Timer", "java/time/", "java/net/", "java/nio/channels/",
        "java/security/SecureRandom", "com/xpdustry/shard/wire/", "com/xpdustry/shard/compat/",
        "com/xpdustry/shard/net/", "com/xpdustry/shard/server/");

    /** Methods, as owner.name. */
    static final Set<String> FORBIDDEN_METHODS = Set.of(
        "java/lang/Math.sin", "java/lang/Math.cos", "java/lang/Math.tan", "java/lang/Math.asin", "java/lang/Math.acos",
        "java/lang/Math.atan", "java/lang/Math.atan2", "java/lang/Math.exp", "java/lang/Math.log",
        "java/lang/Math.log10", "java/lang/Math.pow", "java/lang/Math.cbrt", "java/lang/Math.hypot",
        "java/lang/Math.expm1", "java/lang/Math.log1p", "java/lang/Math.sinh", "java/lang/Math.cosh",
        "java/lang/Math.tanh", "java/lang/Math.random", "java/lang/StrictMath.random",
        "java/lang/System.currentTimeMillis", "java/lang/System.nanoTime", "java/lang/System.identityHashCode",
        "java/util/Set.of", "java/util/Set.copyOf", "java/util/Map.of", "java/util/Map.ofEntries", "java/util/Map.copyOf",
        "java/util/stream/Collectors.toSet", "java/util/stream/Collectors.toUnmodifiableSet",
        "java/util/stream/Collectors.toMap", "java/util/stream/Collectors.toUnmodifiableMap",
        "java/util/stream/Collectors.groupingBy", "java/util/stream/Collectors.partitioningBy");

    @Test
    void simCodeReferencesNothingNondeterministic() throws IOException{
        var violations = new TreeSet<String>();
        List<Path> classes = classFiles();
        assertTrue(classes.size() > 30, "found only " + classes.size() + " classes; is shard.sim.classes right?");
        for(Path file : classes){
            ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
            String self = model.thisClass().asInternalName();
            for(PoolEntry entry : model.constantPool()){
                switch(entry){
                    case ClassEntry type -> check(self, type.asInternalName(), violations);
                    case MemberRefEntry member when member instanceof MethodRefEntry || member instanceof InterfaceMethodRefEntry -> {
                        String owner = member.owner().asInternalName();
                        if(FORBIDDEN_METHODS.contains(owner + "." + member.name().stringValue())){
                            violations.add(self + " calls " + owner + "." + member.name().stringValue());
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        assertEquals(Set.of(), violations, "nondeterminism in :sim");
    }

    @Test
    void theScanCatchesWhatItShould(){
        var violations = new TreeSet<String>();
        check("x/Y", "java/util/HashMap$Node", violations);
        check("x/Y", "java/lang/ThreadLocal", violations);
        check("x/Y", "com/xpdustry/shard/wire/Packet", violations);
        check("x/Y", "java/util/IdentityHashMap", violations);
        check("x/Y", "java/util/TreeMap", violations);
        assertEquals(3, violations.size(), violations.toString());
    }

    static void check(String self, String type, Set<String> violations){
        String name = type.startsWith("[") ? type.replaceAll("^\\[+L?|;$", "") : type;
        for(String forbidden : FORBIDDEN_TYPES){
            if(name.startsWith(forbidden)) violations.add(self + " uses " + name);
        }
    }

    static List<Path> classFiles() throws IOException{
        String dirs = System.getProperty("shard.sim.classes");
        assertNotNull(dirs, "the build passes shard.sim.classes");
        var out = new ArrayList<Path>();
        for(String dir : dirs.split(File.pathSeparator)){
            Path root = Path.of(dir);
            if(!Files.isDirectory(root)) continue;
            try(Stream<Path> walk = Files.walk(root)){
                walk.filter(p -> p.toString().endsWith(".class")).sorted().forEach(out::add);
            }
        }
        return out;
    }
}
