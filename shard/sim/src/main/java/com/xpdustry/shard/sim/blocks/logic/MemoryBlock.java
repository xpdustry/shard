package com.xpdustry.shard.sim.blocks.logic;

import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code MemoryBlock.MemoryBuild} (MemoryBlock.java): memory cells and banks, and the world cell. Each slot
 * holds a number or, since v158, an object (vanilla's {@code objectMemory} with a sentinel for numbers). Reads out of
 * range give null; writes out of range do nothing. A world cell can't be damaged.
 *
 * <p>Objects are what the processors wrote: the logic table's content and teams, strings, buildings, units.
 * Buildings and units read from a save are {@link Boxed} until {@link #afterReadAll()}: then a building at the saved
 * position and a unit with the saved id, if they exist.
 *
 * <p>Not ported: bullet collisions of world cells (WP41).
 */
@Kind("memory-block")
public final class MemoryBlock extends Building implements Checkpointable, LogicReadable, LogicWritable{
    /** Vanilla's {@code sentinel}: this slot is a number, in {@link #numbers}. */
    public static final Object NUMBER = new Object(){
        @Override
        public String toString(){
            return "<number>";
        }
    };

    /** Per slot, {@link #NUMBER} or the object. */
    public Object[] objects = {};
    public double[] numbers = {};

    @Override
    public void created(){
        int capacity = block().baked() ? block().fields().i("memoryCapacity") : 32;
        objects = new Object[capacity];
        numbers = new double[capacity];
        // all memory slots contain 0 when first initialized
        Arrays.fill(objects, NUMBER);
    }

    /** {@code memoryCapacity}. */
    public int capacity(){
        return objects.length;
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.memoryCapacity) return capacity();
        return super.sense(sensor);
    }

    @Override
    public boolean readable(Processor exec){
        return isValid() && (exec.privileged || (team == exec.team() && !block().privileged()));
    }

    @Override
    public void read(Processor exec, int position, int output){
        int address = exec.numi(position);
        // null when out of bounds, instead of 0
        if(address < 0 || address >= objects.length){
            exec.setobj(output, null);
            return;
        }
        Object value = objects[address];
        if(value == NUMBER) exec.setnum(output, numbers[address]);
        else exec.setobj(output, value);
    }

    @Override
    public boolean writable(Processor exec){
        return readable(exec);
    }

    @Override
    public void write(Processor exec, int position, int value){
        int address = exec.numi(position);
        if(address < 0 || address >= objects.length) return;
        if(exec.isObj(value)){
            objects[address] = exec.obj(value);
        }else{
            objects[address] = NUMBER;
            numbers[address] = exec.num(value);
        }
    }

    /** A world cell can't be damaged. */
    @Override
    public void damage(float damage){
        if(block().privileged()) return;
        super.damage(damage);
    }

    /** {@code afterReadAll}: references a save named come back as what stands there now (MemoryBlock.java:190). */
    @Override
    public void afterReadAll(){
        for(int i = 0; i < objects.length; i++){
            if(objects[i] instanceof Boxed boxed) objects[i] = boxed.unbox(world());
        }
    }

    @Override
    public void write(CheckpointOut out){
        out.obj("objects", LogicData.of(state -> {
            state.i(objects.length);
            for(Object value : objects){
                state.bool(value == NUMBER);
                if(value != NUMBER) LogicState.writeValue(state, value);
            }
        })).doubles("numbers", numbers);
    }

    @Override
    public void read(CheckpointIn in){
        LogicData data = in.obj("objects", LogicData.class);
        numbers = in.doubles("numbers");
        if(data == null || numbers == null) throw new CheckpointException(this + " was stored without its memory");
        LogicState.In state = data.reader();
        objects = new Object[state.i()];
        Globals table = Globals.of(world().content());
        for(int i = 0; i < objects.length; i++) objects[i] = state.bool() ? NUMBER : LogicState.readValue(state, table);
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (MemoryBlock)into;
        var remap = LogicData.remap(context);
        out.objects = new Object[objects.length];
        for(int i = 0; i < objects.length; i++) out.objects[i] = objects[i] == NUMBER ? NUMBER : LogicState.copyValue(objects[i], remap);
        out.numbers = numbers.clone();
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        var out = new LogicPart.HashOut(hasher);
        out.i(objects.length);
        for(int i = 0; i < objects.length; i++){
            out.bool(objects[i] == NUMBER);
            if(objects[i] == NUMBER) out.d(numbers[i]);
            else LogicState.writeValue(out, objects[i]);
        }
    }

    /** {@code canPickup()}: payload units may lift it never. */
    @Override
    public boolean canPickup(){
        return false;
    }
}
