package com.xpdustry.shard.logic.oracle;

import arc.util.io.*;
import com.xpdustry.shard.logic.*;
import mindustry.content.*;
import mindustry.io.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.logic.LogicBlock.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Tier A save body, {@code LogicBuild.write} version 5: Shard's {@link LogicSave}, written with vanilla's
 * {@code TypeIO}, is byte for byte what vanilla writes for the same processor, and each side reads the other's bytes
 * back to the same state. Programs leave the counter fractional or an object, sit in waits, and hold buildings.
 */
class SaveOracleTest{
    static final String[] programs = {
        // a fractional counter, an object in a variable, a link, a wait
        """
        set x cell1
        set s "text"
        op add n n 0.25
        set @counter 4.5
        noop
        wait 0.3
        jump 2 always
        """,
        // the counter holds an object while yielding
        """
        op add i i 1
        set @counter "s"
        """,
        // parks in a wait and keeps exported links
        """
        read v cell1 0
        op add v v 1
        set c cell2
        wait 0.5
        """,
        VanillaLogic.program("math"),
    };

    @Test
    void compressMatchesVanilla(){
        VanillaLogic.boot();
        Random random = new Random(3);
        for(int i = 0; i < 200; i++){
            String code = "print \"é→" + random.nextInt() + "\"\n".repeat(random.nextInt(4));
            arc.struct.Seq<LogicLink> vanilla = new arc.struct.Seq<>();
            List<LogicConfig.Link> shard = new ArrayList<>();
            for(int j = random.nextInt(5); j > 0; j--){
                String name = "cell" + random.nextInt(9);
                int x = random.nextInt(600) - 300, y = random.nextInt(600) - 300;
                vanilla.add(new LogicLink(x, y, name, true));
                shard.add(new LogicConfig.Link(name, x, y));
            }
            assertArrayEquals(LogicBlock.compress(code, vanilla), LogicConfig.compress(code, shard));
            LogicConfig.Decoded decoded = LogicConfig.decode(LogicConfig.compress(code, shard));
            assertEquals(code, decoded.code());
            assertEquals(shard, decoded.links());
        }
        assertNull(LogicConfig.decode(new byte[]{1, 2, 3}));
    }

    @Test
    void sameBytes() throws IOException{
        for(String code : programs){
            for(int ticks : new int[]{0, 1, 7, 40, 333}){
                VanillaLogic.world(30, 30);
                LogicBuild build = VanillaLogic.place(Blocks.microProcessor, 5, 5);
                for(int v = 0; v < 8; v++) VanillaLogic.link(build, Blocks.memoryCell, 7 + v, 5, "cell" + (v + 1));
                build.updateCode(code);
                VanillaHost host = new VanillaHost();
                LogicWorld world = VanillaLogic.logicWorld(host);
                // both run on the same building; the programs only change the cells in ways both sides see alike
                Processor p = VanillaLogic.processor(world, host, build, build);
                host.unregister(p); // its own reads go to vanilla's executor otherwise
                for(int t = 0; t < ticks; t++){
                    VanillaLogic.tick();
                    VanillaLogic.tick(world);
                    long s0 = mindustry.logic.GlobalVars.rand.seed0, s1 = mindustry.logic.GlobalVars.rand.seed1;
                    build.updateTile();
                    world.rand().seed0 = s0;
                    world.rand().seed1 = s1;
                    p.update(true, build.edelta());
                }
                byte[] vanilla = vanilla(build);
                String where = ticks + " ticks of\n" + code;
                assertArrayEquals(vanilla, write(p.save()), where);

                // Shard reads vanilla's bytes back to the same state
                Processor copy = VanillaLogic.processor(world, host, build, build);
                host.unregister(copy);
                copy.load(read(vanilla), 5);
                copy.checkReadCode();
                assertArrayEquals(vanilla, write(copy.save()), "Shard round trip, " + where);

                // and vanilla reads Shard's
                build.read(new Reads(new DataInputStream(new ByteArrayInputStream(write(p.save())))), (byte)5);
                build.afterReadAll();
                assertArrayEquals(vanilla, vanilla(build), "vanilla reading Shard's bytes, " + where);
            }
        }
    }

    private static byte[] vanilla(LogicBuild build){
        var bytes = new ByteArrayOutputStream();
        build.write(new Writes(new DataOutputStream(bytes)));
        return bytes.toByteArray();
    }

    /** What {@code :compat} will do: LogicSave to LogicBuild.write's bytes. */
    static byte[] write(LogicSave save){
        var bytes = new ByteArrayOutputStream();
        Writes write = new Writes(new DataOutputStream(bytes));
        byte[] compressed = save.compressed();
        write.i(compressed.length);
        write.b(compressed);
        write.i(save.variables().size());
        for(LogicSave.Variable variable : save.variables()){
            write.str(variable.name());
            TypeIO.writeObject(write, VanillaHost.unshard(variable.value()));
        }
        write.i(0);
        write.s(save.ipt());
        TypeIO.writeString(write, save.tag());
        write.s(save.iconTag());
        write.s(save.waitIndices().length);
        for(int i = 0; i < save.waitIndices().length; i++){
            write.s(save.waitIndices()[i]);
            write.f(save.waitTimes()[i]);
        }
        write.f(save.accumulator());
        return bytes.toByteArray();
    }

    /** LogicBuild.read's bytes to a LogicSave. */
    static LogicSave read(byte[] data){
        Reads read = new Reads(new DataInputStream(new ByteArrayInputStream(data)));
        byte[] compressed = new byte[read.i()];
        read.b(compressed);
        LogicConfig.Decoded decoded = LogicConfig.decode(compressed);
        List<LogicSave.Variable> variables = new ArrayList<>();
        int count = read.i();
        for(int i = 0; i < count; i++){
            String name = read.str();
            Object value = TypeIO.readObjectBoxed(read, true);
            if(value instanceof mindustry.io.TypeIO.Boxed<?> boxed) value = boxed.unbox();
            if(value != null) variables.add(new LogicSave.Variable(name, value));
        }
        read.skip(read.i() * 8);
        short ipt = read.s();
        String tag = TypeIO.readString(read);
        char icon = (char)read.us();
        int waits = read.us();
        int[] indices = new int[waits];
        float[] times = new float[waits];
        for(int i = 0; i < waits; i++){
            indices[i] = read.us();
            times[i] = read.f();
        }
        float accumulator = read.f();
        List<LogicSave.Link> links = decoded.links().stream().map(l -> new LogicSave.Link(l.name(), l.x(), l.y())).toList();
        return new LogicSave(decoded.code(), links, variables, ipt, tag, icon, indices, times, accumulator);
    }
}
