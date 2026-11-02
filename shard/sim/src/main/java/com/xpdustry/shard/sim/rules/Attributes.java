package com.xpdustry.shard.sim.rules;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/** Environment attribute values (vanilla {@code Attributes}), one float per {@code Attribute}, in vanilla's id order. */
public final class Attributes implements Checkpointable{
    /** {@code Attribute.all}'s names, by attribute id. */
    public static final List<String> NAMES = List.of("heat", "spores", "water", "oil", "light", "sand", "steam");

    private final float[] values = new float[NAMES.size()];

    public float get(int attribute){
        return values[attribute];
    }

    public void set(int attribute, float value){
        values[attribute] = value;
    }

    /** The attribute id of {@code name}, or -1. */
    public static int id(String name){
        return NAMES.indexOf(name);
    }

    public Attributes copy(){
        var out = new Attributes();
        System.arraycopy(values, 0, out.values, 0, values.length);
        return out;
    }

    public void hash(Hasher hasher){
        hasher.floats(values);
    }

    @Override
    public void write(CheckpointOut out){
        out.floats("values", values);
    }

    @Override
    public void read(CheckpointIn in){
        in.floats("values", values);
    }
}
