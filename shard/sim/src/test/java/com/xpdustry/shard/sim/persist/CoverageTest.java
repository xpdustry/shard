package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The coverage gate (WAVES.md W1.4): every field of every entity, part, controller and checkpointable class on the
 * class path is stored or marked {@link Derived}. It scans {@code :sim}'s main classes, and the test packages whose
 * classes are checkpointed ({@code sim.toy}, {@code sim.persist}); other packages' test-only entities never are.
 */
class CoverageTest{
    /** Test fixtures that are uncovered on purpose, to show the check catches them. */
    static final Set<String> BROKEN_ON_PURPOSE = Set.of(CheckpointFormatTest.Wander.class.getName());
    /** Other owners' fixture keys that are never captured (their value classes aren't reachable from here). */
    static final Set<String> FOREIGN_TEST_KEYS = Set.of("test.attachment.tally");

    @Test
    void everyStoredClassOnTheClassPathIsCovered() throws Exception{
        List<Class<?>> stored = stored(scan());
        List<String> names = stored.stream().map(Class::getName).toList();
        for(Class<?> expected : List.of(Building.class, Unit.class, ItemModule.class, LiquidModule.class, PowerModule.class,
            Counter.class, Link.class, Wall.class, Follow.class, Chaos.class, CheckpointFormatTest.Gauge.class)){
            assertTrue(names.contains(expected.getName()), "the scan missed " + expected.getName() + "; it found " + names);
        }
        assertEquals(List.of(), Coverage.classes(stored));
    }

    @Test
    void theWorldsTablesAreCovered(){
        World world = CheckpointTest.run(Fixtures.churning(40), 60);
        assertEquals(List.of(), Coverage.tables(Checkpoint.capture(world)));
    }

    @Test
    void authoritativeAttachmentsHaveCodecs(){
        assertNotNull(Fixtures.HEAT);
        List<String> problems = new ArrayList<>(Coverage.attachments());
        problems.removeIf(problem -> FOREIGN_TEST_KEYS.stream().anyMatch(problem::contains));
        assertEquals(List.of(), problems);
    }

    /** Stores one field and forgets the other. */
    public static final class Leaky implements UnitController, Checkpointable{
        public int stored, forgotten;
        @Derived("nothing: a fixture")
        public int cache;

        @Override
        public void write(CheckpointOut out){
            out.i("stored", stored);
        }

        @Override
        public void read(CheckpointIn in){
            stored = in.i("stored");
        }

        @Override
        public void updateUnit(Unit unit){
        }

        @Override
        public UnitController copy(CopyContext context){
            return new Leaky();
        }

        @Override
        public void hash(Hasher hasher){
        }
    }

    /** Stores its field under a misspelt name. */
    public static final class Typo implements WorldPart, Checkpointable{
        public int stored;

        @Override
        public void write(CheckpointOut out){
            out.i("stord", stored);
        }

        @Override
        public void read(CheckpointIn in){
            stored = in.i("stord");
        }

        @Override
        public WorldPart copy(CopyContext context){
            return new Typo();
        }

        @Override
        public void hash(Hasher hasher){
        }
    }

    /** A kind with a field and no codec at all. */
    public static final class Bare extends Building{
        public int forgotten;
    }

    @Test
    void theCheckNamesWhatIsMissing(){
        List<String> problems = Coverage.classes(List.of(Leaky.class, Typo.class, Bare.class, CheckpointFormatTest.Wander.class));
        assertEquals(5, problems.size(), problems.toString());
        assertTrue(problems.contains(Leaky.class.getName() + ": field Leaky.forgotten is neither stored nor @Derived"), problems.toString());
        assertTrue(problems.contains(Typo.class.getName() + ": field Typo.stored is neither stored nor @Derived"), problems.toString());
        assertTrue(problems.contains(Typo.class.getName() + " writes stord, which is not one of its fields"), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith(Bare.class.getName()) && p.contains("Bare.forgotten")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith(CheckpointFormatTest.Wander.class.getName()) && p.contains("Wander.steps")), problems.toString());
    }

    /** The classes whose instances checkpoints store, minus the broken fixtures. */
    static List<Class<?>> stored(List<Class<?>> classes){
        var targets = new TreeSet<String>();
        for(ClassCodec<?> codec : Registries.instances(ClassCodec.class)) targets.add(codec.type().getName());
        return classes.stream()
            .filter(type -> !BROKEN_ON_PURPOSE.contains(type.getName()) && type.getEnclosingClass() != CoverageTest.class)
            .filter(type -> Entity.class.isAssignableFrom(type) || WorldPart.class.isAssignableFrom(type)
                || UnitController.class.isAssignableFrom(type) || Checkpointable.class.isAssignableFrom(type)
                || targets.contains(type.getName()) || type == ItemModule.class || type == LiquidModule.class || type == PowerModule.class)
            .toList();
    }

    /** {@code :sim}'s main classes and the checkpointed test packages, loaded without initializing them. */
    static List<Class<?>> scan() throws IOException, URISyntaxException{
        var names = new TreeSet<String>();
        for(String dir : System.getProperty("shard.sim.classes").split(File.pathSeparator)){
            names.addAll(classNames(Path.of(dir), ""));
        }
        Path tests = Path.of(Fixtures.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        names.addAll(classNames(tests, "com.xpdustry.shard.sim.toy."));
        names.addAll(classNames(tests, "com.xpdustry.shard.sim.persist."));
        var out = new ArrayList<Class<?>>();
        for(String name : names){
            try{
                out.add(Class.forName(name, false, CoverageTest.class.getClassLoader()));
            }catch(ClassNotFoundException | LinkageError e){
                fail("can't load " + name + ": " + e);
            }
        }
        return out;
    }

    private static List<String> classNames(Path root, String prefix) throws IOException{
        if(!Files.isDirectory(root)) return List.of();
        try(Stream<Path> files = Files.walk(root)){
            return files.filter(file -> file.toString().endsWith(".class") && !file.getFileName().toString().equals("module-info.class"))
                .map(file -> root.relativize(file).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", ""))
                .filter(name -> name.startsWith(prefix) && !name.endsWith("package-info"))
                .toList();
        }
    }
}
