package com.xpdustry.shard.logic.oracle;

import arc.util.*;
import com.xpdustry.shard.logic.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.logic.LExecutor.*;
import mindustry.world.blocks.distribution.Sorter.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.logic.LogicBlock.*;
import mindustry.world.blocks.logic.MemoryBlock.*;
import mindustry.world.blocks.logic.MessageBlock.*;

import java.util.*;

/**
 * A vanilla processor and a Shard processor running the same code on two copies of a world region: vanilla's
 * {@code LogicBuild.updateTile} drives {@link #vanilla}, Shard's {@link Processor} runs on {@link #shard}'s building
 * through {@link VanillaHost}. {@link #compare()} checks everything a client or a save can observe.
 */
final class Pair{
    final LogicBuild vanilla, shard;
    final LogicWorld world;
    final VanillaHost host;
    Processor processor;

    Pair(LogicBuild vanilla, LogicBuild shard, LogicWorld world, VanillaHost host){
        this.vanilla = vanilla;
        this.shard = shard;
        this.world = world;
        this.host = host;
    }

    /** Loads {@code code} on both, as a fresh upload: ipt and accumulator back to the block's start. */
    void load(String code){
        LogicBlock block = (LogicBlock)vanilla.block;
        vanilla.ipt = block.instructionsPerTick;
        vanilla.accumulator = 0;
        vanilla.updateCode(code);
        if(processor != null){
            processor.dispose();
            host.unregister(processor);
        }
        processor = VanillaLogic.processor(world, host, shard, vanilla);
        processor.setCode(code);
    }

    /** One tick on both; vanilla's {@code op rand} state is handed to Shard first, so both draw the same numbers. */
    void tick(){
        VanillaLogic.tick();
        VanillaLogic.tick(world);
        long s0 = GlobalVars.rand.seed0, s1 = GlobalVars.rand.seed1;
        vanilla.updateTile();
        world.rand().seed0 = s0;
        world.rand().seed1 = s1;
        processor.team(shard.team.id);
        // the building's turn: LogicBuild.updateTile runs the code only while enabled
        processor.update(shard.enabled, vanilla.edelta());
        world.endTick();
    }

    /** null if both agree, else what differs. */
    String compare(){
        LExecutor ex = vanilla.executor;
        Processor p = processor;
        Program prog = p.program();
        if(ex.instructions.length != prog.length) return "instruction count " + ex.instructions.length + " vs " + prog.length;
        if(ex.vars.length != prog.variables()) return "variable count " + names(ex) + " vs " + names(prog);
        for(int i = 0; i < ex.vars.length; i++){
            if(!ex.vars[i].name.equals(prog.variableName(i))) return "variable " + i + ": " + names(ex) + " vs " + names(prog);
        }
        Object counter = ex.counter.isobj ? ex.counter.objval : (Object)ex.counter.numval;
        if(!same(counter, p.counterValue())) return "@counter " + counter + " vs " + p.counterValue();
        if(Float.floatToRawIntBits(vanilla.accumulator) != Float.floatToRawIntBits(p.accumulator())) return "accumulator " + vanilla.accumulator + " vs " + p.accumulator();
        if(!ex.textBuffer.toString().contentEquals(p.text())) return "text '" + ex.textBuffer + "' vs '" + p.text() + "'";
        if(GlobalVars.rand.seed0 != world.rand().seed0 || GlobalVars.rand.seed1 != world.rand().seed1) return "rand state";
        if(vanilla.ipt != p.ipt()) return "ipt " + vanilla.ipt + " vs " + p.ipt();
        for(int i = 0; i < ex.vars.length; i++){
            LVar v = ex.vars[i];
            Object value = v.isobj ? v.objval : (Object)v.numval;
            Object mine = p.variable(i);
            if(!same(value, mine)) return v.name + " = " + value + " vs " + mine;
        }
        for(int i = 0; i < ex.instructions.length; i++){
            if(ex.instructions[i] instanceof WaitI wait && Float.floatToRawIntBits(wait.curTime) != Float.floatToRawIntBits(p.waitTime(i))){
                return "wait " + i + " time " + wait.curTime + " vs " + p.waitTime(i);
            }
        }
        if(vanilla.links.size != p.links().size()) return "link count " + vanilla.links.size + " vs " + p.links().size();
        for(int i = 0; i < vanilla.links.size; i++){
            LogicLink a = vanilla.links.get(i);
            Processor.Link b = p.links().get(i);
            if(!a.name.equals(b.name()) || a.valid != b.valid() || a.x - vanilla.tileX() != b.x - shard.tileX() || a.y - vanilla.tileY() != b.y - shard.tileY()){
                return "link " + i + " " + a.name + (a.valid ? "" : " (invalid)") + " vs " + b;
            }
        }
        if(ex.links.length != p.linkCount()) return "valid links " + ex.links.length + " vs " + p.linkCount();
        for(int i = 0; i < ex.links.length; i++) if(!same(ex.links[i], p.link(i))) return "getlink " + i;
        return world();
    }

    private static String names(LExecutor ex){
        return Arrays.toString(Arrays.stream(ex.vars).map(v -> v.name).toArray());
    }

    private static String names(Program prog){
        String[] names = new String[prog.variables()];
        for(int i = 0; i < names.length; i++) names[i] = prog.variableName(i);
        return Arrays.toString(names);
    }

    /**
     * Values are equal: numbers bit for bit, the same object (Shard's content, teams and {@code LAccess} as vanilla's),
     * or buildings of the same block at the same offset from their processor.
     */
    boolean same(Object a, Object b){
        if(a instanceof Double x) return b instanceof Double y && Double.doubleToRawLongBits(x) == Double.doubleToRawLongBits(y);
        if(b instanceof Double) return false;
        if(Structs.eq(a, VanillaHost.unshard(b))) return true;
        if(a instanceof Building x && b instanceof Building y){
            return x.block == y.block && x.tileX() - vanilla.tileX() == y.tileX() - shard.tileX() && x.tileY() - vanilla.tileY() == y.tileY() - shard.tileY();
        }
        if(a instanceof arc.struct.Seq<?> x && b instanceof List<?> y){
            if(x.size != y.size()) return false;
            for(int i = 0; i < x.size; i++) if(!same(x.get(i), y.get(i))) return false;
            return true;
        }
        return false;
    }

    /** The linked buildings' state: enabled, memory, message text, sorter item. */
    private String world(){
        if(shard == vanilla) return null;
        LVar pos = new LVar("p"), out1 = new LVar("o1"), out2 = new LVar("o2");
        for(LogicLink l : vanilla.links){
            Building a = Vars.world.build(l.x, l.y);
            Building b = Vars.world.build(l.x - vanilla.tileX() + shard.tileX(), l.y - vanilla.tileY() + shard.tileY());
            if(a == null && b == null) continue;
            if(a == null || b == null || a.block != b.block) return "link layout " + l.name;
            if(a.enabled != b.enabled) return l.name + ".enabled " + a.enabled + " vs " + b.enabled;
            if(a instanceof MessageBuild ma && b instanceof MessageBuild mb && !ma.message.toString().equals(mb.message.toString())){
                return l.name + " message '" + ma.message + "' vs '" + mb.message + "'";
            }
            if(a instanceof SorterBuild sa && b instanceof SorterBuild sb && sa.sortItem != sb.sortItem) return l.name + " item " + sa.sortItem + " vs " + sb.sortItem;
            if(a instanceof MemoryBuild ca && b instanceof MemoryBuild cb){
                for(int i = 0; i < ((MemoryBlock)ca.block).memoryCapacity; i++){
                    pos.isobj = false;
                    pos.numval = i;
                    ca.read(pos, out1);
                    cb.read(pos, out2);
                    if(out1.isobj != out2.isobj || (out1.isobj ? !same(out1.objval, out2.objval) : out1.numval != out2.numval)) return l.name + "[" + i + "] differs";
                }
            }
        }
        return null;
    }
}
