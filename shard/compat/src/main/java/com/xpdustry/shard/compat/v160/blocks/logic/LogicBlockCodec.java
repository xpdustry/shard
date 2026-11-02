package com.xpdustry.shard.compat.v160.blocks.logic;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.wire.*;

import java.util.*;

/**
 * {@code LogicBuild.write}/{@code read} (LogicBlock.java:780-950), version 5: the code and links compressed (links
 * absolute), every non-null exported variable by name with its {@code TypeIO} value ({@code @unit} first when set), an
 * empty legacy memory, the ipt, the world processor's tag and icon, every {@code wait}'s timer and the accumulator.
 * Older revisions: 0 stores the code as a string and old link positions, 1 compresses it, 2 adds a world processor's
 * ipt, 3 the tag and icon, 4 the wait timers and accumulator, 5 a normal processor's ipt.
 *
 * <p>A read applies what vanilla's read does at once ({@link Processor#load}), against the buildings read so far;
 * the variables and timers wait for the save's {@code afterReadAll} ({@link Processor#checkReadCode()}).
 */
@Register
public final class LogicBlockCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("logic-block");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 5;
    }

    @Override
    public boolean reads(int revision){
        return revision >= 0 && revision <= 5;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        write(((LogicBlock)building).processor().save(), out, ids);
    }

    /** {@link LogicSave} as {@code LogicBuild.write} writes it, after the base. */
    public static void write(LogicSave save, Writes out, ContentHeader.IdMapping ids){
        byte[] compressed = save.compressed();
        out.i(compressed.length);
        out.b(compressed);
        out.i(save.variables().size());
        for(LogicSave.Variable variable : save.variables()){
            out.str(variable.name());
            LogicValues.write(out, variable.value(), ids);
        }
        // no memory
        out.i(0);
        out.s(save.ipt());
        Codecs.writeString(out, save.tag());
        out.s(save.iconTag());
        out.s(save.waitIndices().length);
        for(int i = 0; i < save.waitIndices().length; i++){
            out.s(save.waitIndices()[i]);
            out.f(save.waitTimes()[i]);
        }
        out.f(save.accumulator());
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var processor = ((LogicBlock)building).processor();
        processor.load(read(in, revision, processor.privileged, ids, processor.world().table), revision);
    }

    /** {@code LogicBuild.read} of {@code revision} as a {@link LogicSave}; fields the revision lacks are defaults. */
    public static LogicSave read(Reads in, int revision, boolean privileged, ContentHeader.IdMapping ids, Globals table){
        String code;
        List<LogicSave.Link> links = new ArrayList<>();
        if(revision >= 1){
            byte[] compressed = in.b(in.i());
            LogicConfig.Decoded decoded = LogicConfig.decode(compressed);
            // data that fails before its links changes nothing: no links, the code as it was ("")
            code = decoded == null ? null : decoded.code();
            if(decoded != null) for(LogicConfig.Link link : decoded.links()) links.add(new LogicSave.Link(link.name(), link.x(), link.y()));
        }else{
            code = in.str();
            // old saves only kept link positions, which are dropped
            for(int i = in.s(); i > 0; i--) in.i();
        }
        int count = in.i();
        var variables = new ArrayList<LogicSave.Variable>();
        for(int i = 0; i < count; i++){
            String name = in.str();
            Object value = LogicValues.read(in, ids, table);
            // a null value sets a variable that already is null
            if(value != null) variables.add(new LogicSave.Variable(name, value));
        }
        // the legacy memory, skipped
        in.skip(in.i() * 8);
        short ipt = 0;
        if(privileged && revision >= 2 || !privileged && revision >= 5) ipt = in.s();
        String tag = null;
        char icon = 0;
        if(revision >= 3){
            tag = Codecs.readString(in);
            icon = (char)in.us();
        }
        int[] waits = new int[0];
        float[] times = new float[0];
        float accumulator = 0f;
        if(revision >= 4){
            int total = in.us();
            waits = new int[total];
            times = new float[total];
            for(int i = 0; i < total; i++){
                waits[i] = in.us();
                times[i] = in.f();
            }
            accumulator = in.f();
        }
        return new LogicSave(code, links, variables, ipt, tag, icon, waits, times, accumulator);
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision >= 1) in.b(in.i());
        else{
            in.str();
            for(int i = in.s(); i > 0; i--) in.i();
        }
        for(int i = in.i(); i > 0; i--){
            in.str();
            LogicValues.skip(in);
        }
        in.b(in.i() * 8);
        // a payload doesn't say whether it was privileged: only normal processors can be picked up, and none can
        if(revision >= 5) in.s();
        if(revision >= 3){
            if(in.b() != 0) in.str();
            in.us();
        }
        if(revision >= 4){
            int total = in.us();
            in.b(total * 6);
            in.f();
        }
    }
}
