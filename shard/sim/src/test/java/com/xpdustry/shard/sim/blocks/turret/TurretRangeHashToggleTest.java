package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.sim.fog.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The turret toggle proof review 43 asked for (WAVES.md "Golden hash"). W4.3 hashes {@code TurretBuild.lastRangeChange}
 * in every world, fog or not, and the lead ruled that intended: it is stored state. {@code FogHashToggleTest}'s toy
 * world has no turret, so it can't show what that does to turret worlds. This test uses {@link TurretRangeFixture}, a
 * duo that fires copper and then graphite (whose {@code rangeChange} is 16) at crux walls:
 * <ul>
 *   <li>without fog the field stays 0 through play, so it adds one constant word to each turret's hash;</li>
 *   <li>hashed without the field (this module's classes loaded again, with {@code TurretBuild.hashState}'s one
 *   {@code .f(lastRangeChange)} removed from its bytecode), the world hashes as it did at {@code 9ae67530}, before the
 *   field. So that constant is the hash's whole move for a turret world without fog;</li>
 *   <li>with the field only the buildings' section moves;</li>
 *   <li>under fog the ammo moves the field to 16, and changing only the field moves the hash.</li>
 * </ul>
 * The hash format moved on purpose, for turret worlds only: the golden's toy world has no turret, so
 * {@code HashTest.GOLDEN} didn't move for it.
 */
class TurretRangeHashToggleTest{
    /** {@link TurretRangeFixture#nonFog()}'s hash at {@code 9ae67530}, main before W4.3, run on that commit. */
    static final long BEFORE_THE_FIELD = 0x3aff6a38f39f443cL;
    static final String HASHER = "com/xpdustry/shard/sim/world/Hasher";

    @Test
    void withoutFogTheFieldStaysZero(){
        List<String> played = TurretRangeFixture.nonFog();
        assertEquals("0.0", played.get(4), "the largest |lastRangeChange| in play without fog");
        assertTrue(Integer.parseInt(played.get(2)) > 0, "the duo fired: " + played.get(2));
        assertEquals(Integer.toString(VanillaWorlds.VANILLA.item("graphite").id()), played.get(3), "the duo fires graphite");
    }

    @Test
    void withoutTheFieldTheHashIsMains() throws ReflectiveOperationException{
        var loader = new WithoutTheField(getClass().getClassLoader());
        List<String> played = loader.nonFog();
        assertEquals(1, loader.removed, "the rewrite removed exactly one hashed field");
        assertEquals(String.format("0x%016xL", BEFORE_THE_FIELD), played.get(0), played.get(1));
    }

    @Test
    void theFieldMovesOnlyTheBuildings() throws ReflectiveOperationException{
        List<String> with = TurretRangeFixture.nonFog(), without = new WithoutTheField(getClass().getClassLoader()).nonFog();
        assertNotEquals(with.get(0), without.get(0), "the field is hashed");
        List<String> withSections = sections(with.get(1)), withoutSections = sections(without.get(1));
        assertEquals(withoutSections.size(), withSections.size());
        var moved = new ArrayList<String>();
        for(int i = 0; i < withSections.size(); i++) if(!withSections.get(i).equals(withoutSections.get(i))) moved.add(withSections.get(i).split("=")[0]);
        assertEquals(List.of("buildings"), moved);
    }

    @Test
    void underFogTheAmmoMovesTheFieldAndTheFieldMovesTheHash(){
        World world = TurretRangeFixture.world(Systems.builtIn(), true);
        TurretBuild duo = TurretRangeFixture.duo(world);
        FogControl fog = FogControl.of(world);
        int discoveredOnCopper = -1;
        for(int tick = 0; tick < 200; tick++){
            TurretRangeFixture.tick(world);
            if(tick < TurretRangeFixture.GRAPHITE_AT) assertEquals(0f, duo.lastRangeChange, "copper at tick " + tick);
            if(tick == TurretRangeFixture.GRAPHITE_AT - 1) discoveredOnCopper = BitSet.valueOf(fog.discovered(1)).cardinality();
        }
        assertEquals(16f, duo.peekAmmo().rangeChange(), "graphite's rangeChange");
        assertEquals(16f, duo.lastRangeChange, "the fog took the graphite's range");
        assertTrue(BitSet.valueOf(fog.discovered(1)).cardinality() > discoveredOnCopper, "the wider radius discovered more");

        World fork = world.fork();
        assertEquals(world.hash(), fork.hash());
        TurretRangeFixture.duo(fork).lastRangeChange = 0f;
        assertEquals("buildings", world.hash().firstDifference(fork.hash()).orElseThrow(), "only the field changed");
        World restored = Checkpoint.decode(Checkpoint.capture(fork).encode(Compression.FAST)).restore(fork.config());
        assertEquals(fork.hash(), restored.hash(), "a checkpoint keeps the field");
    }

    static List<String> sections(String hash){
        return List.of(hash.substring(hash.indexOf('[') + 1, hash.length() - 1).split(", "));
    }

    /**
     * Shard's classes again, child first, with {@code TurretBuild.hashState}'s {@code aload_0; getfield
     * lastRangeChange; invokevirtual Hasher.f} removed. The call returns the hasher it was given, so the chain goes on
     * as without the field. Other classes come from the parent.
     */
    static final class WithoutTheField extends ClassLoader{
        int removed;

        WithoutTheField(ClassLoader parent){
            super("without-lastRangeChange", parent);
        }

        @SuppressWarnings("unchecked")
        List<String> nonFog() throws ReflectiveOperationException{
            Thread thread = Thread.currentThread();
            ClassLoader context = thread.getContextClassLoader();
            // Registries reads the index through ServiceLoader, which loads providers through the context loader
            thread.setContextClassLoader(this);
            try{
                return (List<String>)loadClass(TurretRangeFixture.class.getName()).getMethod("nonFog").invoke(null);
            }finally{
                thread.setContextClassLoader(context);
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
            if(!name.startsWith("com.xpdustry.shard.")) return super.loadClass(name, resolve);
            synchronized(getClassLoadingLock(name)){
                Class<?> type = findLoadedClass(name);
                if(type == null){
                    byte[] bytes;
                    try(InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")){
                        if(in == null) throw new ClassNotFoundException(name);
                        bytes = in.readAllBytes();
                    }catch(IOException e){
                        throw new ClassNotFoundException(name, e);
                    }
                    if(name.equals(TurretBuild.class.getName())) bytes = strip(bytes);
                    type = defineClass(name, bytes, 0, bytes.length);
                }
                if(resolve) resolveClass(type);
                return type;
            }
        }

        byte[] strip(byte[] bytes){
            ClassFile files = ClassFile.of();
            return files.transformClass(files.parse(bytes), ClassTransform.transformingMethodBodies(
                method -> method.methodName().equalsString("hashState"), CodeTransform.ofStateful(Strip::new)));
        }

        final class Strip implements CodeTransform{
            CodeElement held;
            boolean dropCall;

            @Override
            public void accept(CodeBuilder code, CodeElement element){
                if(dropCall){
                    dropCall = false;
                    if(!(element instanceof InvokeInstruction call && call.owner().asInternalName().equals(HASHER) && call.name().equalsString("f"))){
                        throw new IllegalStateException("lastRangeChange is read but not hashed by Hasher.f: " + element);
                    }
                    removed++;
                    return;
                }
                if(held != null){
                    CodeElement load = held;
                    held = null;
                    if(element instanceof FieldInstruction field && field.opcode() == Opcode.GETFIELD && field.name().equalsString("lastRangeChange")){
                        dropCall = true;
                        return;
                    }
                    code.with(load);
                }
                if(element instanceof LoadInstruction load && load.slot() == 0 && load.typeKind() == TypeKind.REFERENCE) held = element;
                else code.with(element);
            }

            @Override
            public void atEnd(CodeBuilder code){
                if(held != null) code.with(held);
            }
        }
    }
}
