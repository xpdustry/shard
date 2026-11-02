package com.xpdustry.shard.tools.indexer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import javax.tools.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/** Compiles small source sets with the processor, then loads the index class it generates and records what it declares. */
class RegisterProcessorTest{
    static final String ANNOTATIONS = """
        package com.xpdustry.shard.common.registry;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) public @interface Registry{}
        """;
    static final String REGISTER = """
        package com.xpdustry.shard.common.registry;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) public @interface Register{}
        """;
    static final String KIND = """
        package spi;
        @com.xpdustry.shard.common.registry.Registry public abstract class Block{}
        """;
    static final String KIND_ANNOTATION = """
        package spi;
        import java.lang.annotation.*;
        @com.xpdustry.shard.common.registry.Register
        @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) public @interface Kind{ String value(); }
        """;
    static final String HANDLER = """
        package spi;
        @com.xpdustry.shard.common.registry.Registry public interface Handler{}
        """;
    static final String INDEX = """
        package com.xpdustry.shard.common.registry;
        public interface RegistryIndex{
            void declare(Declarations index);
            interface Declarations{
                void registry(String registry);
                <T> void type(String name, Class<T> type, java.util.function.Supplier<? extends T> constructor, String... registries);
                void key(Class<?> type, String annotation, String value);
            }
        }
        """;
    static final String GENERATED = "gen.TestRegistryIndex";

    @TempDir
    Path out;

    record Result(boolean success, String diagnostics, Path classes){
        /** The class named in META-INF/services, or null if none was written. */
        String service() throws IOException{
            Path file = classes.resolve(RegisterProcessor.SERVICES);
            return Files.exists(file) ? Files.readString(file).strip() : null;
        }

        /**
         * What the generated index declares, in order: {@code registry R}, {@code type N under R1, R2} (after checking
         * that its constructor reference makes an N) and {@code key N A=value}.
         */
        List<String> declarations() throws Exception{
            var out = new ArrayList<String>();
            try(var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())){
                Class<?> declarations = loader.loadClass(RegisterProcessor.INDEX_TYPE + "$Declarations");
                Object recorder = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{declarations}, (proxy, method, args) -> {
                    switch(method.getName()){
                        case "registry" -> out.add("registry " + args[0]);
                        case "type" -> {
                            Object made = ((Supplier<?>)args[2]).get();
                            assertSame(args[1], made.getClass(), "the constructor of " + args[0]);
                            assertEquals(args[0], ((Class<?>)args[1]).getName());
                            out.add("type " + args[0] + " under " + String.join(", ", (String[])args[3]));
                        }
                        case "key" -> out.add("key " + ((Class<?>)args[0]).getName() + " " + args[1] + "=" + args[2]);
                        default -> throw new AssertionError(method);
                    }
                    return null;
                });
                Object index = loader.loadClass(service()).getConstructor().newInstance();
                index.getClass().getMethod("declare", declarations).invoke(index, recorder);
            }
            return out;
        }

        /** The classes declared under {@code registry}, in order. */
        List<String> entries(String registry) throws Exception{
            if(service() == null) return List.of();
            var out = new ArrayList<String>();
            for(String line : declarations()){
                String[] parts = line.split(" ", 4);
                if(parts[0].equals("type") && List.of(parts[3].split(", ")).contains(registry)) out.add(parts[1]);
            }
            return out;
        }
    }

    Result compile(String... sources) throws IOException{
        return compile(List.of("-A" + RegisterProcessor.OPTION + "=" + GENERATED), sources);
    }

    Result compile(List<String> options, String... sources) throws IOException{
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var files = new ArrayList<JavaFileObject>();
        for(String source : List.of(ANNOTATIONS, REGISTER, INDEX, KIND, KIND_ANNOTATION, HANDLER)) files.add(source(source));
        for(String source : sources) files.add(source(source));
        try(var manager = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)){
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(out));
            Files.createDirectories(out.resolve("generated"));
            manager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(out.resolve("generated")));
            var arguments = new ArrayList<>(List.of("-proc:full", "-Xlint:all,-processing", "-Werror"));
            arguments.addAll(options);
            var task = javac.getTask(null, manager, diagnostics, arguments, null, files);
            task.setProcessors(List.of(new RegisterProcessor()));
            boolean success = task.call();
            var text = new StringBuilder();
            diagnostics.getDiagnostics().forEach(d -> text.append(d.getKind()).append(": ").append(d.getMessage(Locale.ROOT)).append('\n'));
            return new Result(success, text.toString(), out);
        }
    }

    static JavaFileObject source(String code){
        String pkg = code.lines().filter(l -> l.startsWith("package ")).findFirst().orElseThrow()
            .substring(8).replace(";", "").strip();
        String type = code.replaceAll("(?s).*?(class|interface|record|enum)\\s+(\\w+).*", "$2");
        URI uri = URI.create("string:///" + pkg.replace('.', '/') + "/" + type + ".java");
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE){
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors){
                return code;
            }
        };
    }

    @Test
    void classesInDifferentPackagesShareOneIndexWithoutASharedFile() throws Exception{
        Result result = compile("""
            package b.second;
            @com.xpdustry.shard.common.registry.Register public final class Conveyor extends spi.Block{}
            """, """
            package a.first;
            @com.xpdustry.shard.common.registry.Register public final class Router extends spi.Block{}
            """);
        assertTrue(result.success(), result.diagnostics());
        assertEquals(List.of("a.first.Router", "b.second.Conveyor"), result.entries("spi.Block"));
        assertEquals(GENERATED, result.service());
        assertEquals(List.of("registry spi.Block", "registry spi.Handler", "type a.first.Router under spi.Block",
            "type b.second.Conveyor under spi.Block"), result.declarations());
        String source = Files.readString(result.classes().resolve("generated/gen/TestRegistryIndex.java"));
        assertTrue(source.contains("@javax.annotation.processing.Generated"), source);
    }

    @Test
    void indirectSupertypesNestedClassesAndSeveralRegistries() throws Exception{
        Result result = compile("""
            package c;
            public abstract class Base extends spi.Block implements spi.Handler{}
            """, """
            package c;
            public final class Outer{
                @com.xpdustry.shard.common.registry.Register public static final class Both extends Base{}
                @com.xpdustry.shard.common.registry.Register public record Only() implements spi.Handler{}
            }
            """);
        assertTrue(result.success(), result.diagnostics());
        assertEquals(List.of("c.Outer$Both"), result.entries("spi.Block"));
        assertEquals(List.of("c.Outer$Both", "c.Outer$Only"), result.entries("spi.Handler"));
    }

    @Test
    void aRegistrationAnnotationRegistersWithoutAtRegister() throws Exception{
        Result result = compile("""
            package f;
            @spi.Kind("conveyor") public final class Conveyor extends spi.Block{}
            """, """
            package f;
            @com.xpdustry.shard.common.registry.Register public final class Router extends spi.Block{}
            """);
        assertTrue(result.success(), result.diagnostics());
        assertEquals(List.of("f.Conveyor", "f.Router"), result.entries("spi.Block"));
        assertEquals(List.of(), result.entries("spi.Kind"), "the annotation type itself is not registered");
        assertTrue(result.declarations().contains("key f.Conveyor spi.Kind=conveyor"), result.declarations().toString());
    }

    @Test
    void aRegistrationAnnotationIsCheckedLikeRegister() throws IOException{
        Result orphan = compile("""
            package g;
            @spi.Kind("orphan") public final class Orphan{}
            """);
        assertFalse(orphan.success());
        assertTrue(orphan.diagnostics().contains("a @Kind class must extend or implement a @Registry type"),
            orphan.diagnostics());

        Result source = compile("""
            package g;
            import java.lang.annotation.*;
            @com.xpdustry.shard.common.registry.Register @Retention(RetentionPolicy.SOURCE) public @interface Gone{}
            """, """
            package g;
            @Gone public final class Lost extends spi.Block{}
            """);
        assertFalse(source.success());
        assertTrue(source.diagnostics().contains("needs @Retention(RUNTIME)"), source.diagnostics());

        Result elements = compile("""
            package g;
            import java.lang.annotation.*;
            @com.xpdustry.shard.common.registry.Register @Retention(RetentionPolicy.RUNTIME) public @interface Tiered{ int tier(); }
            """, """
            package g;
            @Tiered(tier = 2) public final class Tier extends spi.Block{}
            """);
        assertFalse(elements.success());
        assertTrue(elements.diagnostics().contains("only element is String value()"), elements.diagnostics());
    }

    @Test
    void registriesWithNothingRegisteredAreDeclared() throws Exception{
        Result result = compile("""
            package d;
            public final class Plain{}
            """);
        assertTrue(result.success(), result.diagnostics());
        assertEquals(List.of("registry spi.Block", "registry spi.Handler"), result.declarations());
    }

    @Test
    void theIndexNeedsAClassName() throws IOException{
        Result result = compile(List.of(), """
            package d;
            @com.xpdustry.shard.common.registry.Register public final class Named extends spi.Block{}
            """);
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("-A" + RegisterProcessor.OPTION), result.diagnostics());
    }

    @Test
    void rejectsWhatCannotBeInstantiatedOrIndexed() throws IOException{
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("must extend or implement a @Registry type", """
            package e;
            @com.xpdustry.shard.common.registry.Register public final class Orphan{}
            """);
        cases.put("must be concrete", """
            package e;
            @com.xpdustry.shard.common.registry.Register public abstract class Abstract extends spi.Block{}
            """);
        cases.put("needs a public no-argument constructor", """
            package e;
            @com.xpdustry.shard.common.registry.Register public final class Arg extends spi.Block{ public Arg(int x){} }
            """);
        cases.put("must be public", """
            package e;
            @com.xpdustry.shard.common.registry.Register final class Hidden extends spi.Block{}
            """);
        cases.put("must be static", """
            package e;
            public final class Holder{ @com.xpdustry.shard.common.registry.Register public final class Inner extends spi.Block{} }
            """);
        cases.put("goes on a class", """
            package e;
            @com.xpdustry.shard.common.registry.Register public interface Marker extends spi.Handler{}
            """);
        for(var entry : cases.entrySet()){
            Result result = compile(entry.getValue());
            assertFalse(result.success(), entry.getKey());
            assertTrue(result.diagnostics().contains(entry.getKey()), entry.getKey() + ": " + result.diagnostics());
        }
    }
}
