package com.xpdustry.shard.sim.ai.command;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code InputHandler.queuedCommands} (InputHandler.java:52): the units each {@code commandUnits} call
 * ordered, by target point, until the call that ends the batch ({@code finalBatch}) forms them into groups. A client
 * splits a selection of more than 200 units into several calls to the same point.
 *
 * <p>Vanilla's map is a JVM static, shared by every player and never trimmed: units ordered to a point stay in it
 * until a final batch to the bit-same point comes, and a client that never sends one grows it without bound. Shard
 * keeps one per world, with at most {@value #MAX_POINTS} points (the oldest is dropped) and {@value #MAX_UNITS} units
 * a point (later ones aren't recorded), which play never reaches.
 */
@Register
public final class CommandBatches implements WorldPart, Checkpointable{
    static final int MAX_POINTS = 64, MAX_UNITS = 4096;

    /** The points, oldest first; {@link #units} holds each one's units. */
    private final ArrayList<Batch> batches = new ArrayList<>();

    /** The world's batches, or null in a world built without them. */
    public static CommandBatches of(World world){
        return world.find(CommandBatches.class);
    }

    /** {@code queuedCommands.get(target, Seq::new)}: the units ordered to (x, y) so far, made empty if none. */
    public Batch get(float x, float y){
        for(Batch batch : batches) if(batch.at(x, y)) return batch;
        if(batches.size() >= MAX_POINTS) batches.removeFirst();
        var batch = new Batch(x, y);
        batches.add(batch);
        return batch;
    }

    /** {@code queuedCommands.remove(target)}: the units ordered to (x, y), now forgotten, or null. */
    public Batch remove(float x, float y){
        for(int i = 0; i < batches.size(); i++){
            if(batches.get(i).at(x, y)) return batches.remove(i);
        }
        return null;
    }

    /** How many points have units waiting. */
    public int pending(){
        return batches.size();
    }

    /** The units ordered to one point, in order; a unit ordered twice is in it twice, as in vanilla. */
    public static final class Batch implements Checkpointable{
        public float x, y;
        public Unit[] units = new Unit[0];
        @Derived("the stored units' count")
        public int size;

        public Batch(){
        }

        Batch(float x, float y){
            this.x = x;
            this.y = y;
        }

        /** {@code Vec2.equals}: the same bits. */
        boolean at(float x, float y){
            return Float.floatToIntBits(this.x) == Float.floatToIntBits(x) && Float.floatToIntBits(this.y) == Float.floatToIntBits(y);
        }

        public void add(Unit unit){
            if(size >= MAX_UNITS) return;
            if(size == units.length) units = Arrays.copyOf(units, Math.max(8, size * 2));
            units[size++] = unit;
        }

        Batch copy(CopyContext context){
            var out = new Batch(x, y);
            out.units = new Unit[units.length];
            for(int i = 0; i < size; i++) out.units[i] = context.map(units[i]);
            out.size = size;
            return out;
        }

        @Override
        public void write(CheckpointOut out){
            out.f("x", x).f("y", y).refs("units", units, size);
        }

        @Override
        public void read(CheckpointIn in){
            x = in.f("x");
            y = in.f("y");
            Unit[] read = in.refs("units", Unit.class);
            if(read == null) throw new CheckpointException("a command batch without units");
            units = read;
            size = read.length;
        }
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new CommandBatches();
        for(Batch batch : batches) out.batches.add(batch.copy(context));
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(batches.size());
        for(Batch batch : batches){
            hasher.f(batch.x).f(batch.y).i(batch.size);
            for(int i = 0; i < batch.size; i++) hasher.i(batch.units[i].id());
        }
    }

    @Override
    public void write(CheckpointOut out){
        out.list("batches", batches);
    }

    @Override
    public void read(CheckpointIn in){
        List<Batch> read = in.list("batches", Batch.class);
        if(read == null || read.contains(null)) throw new CheckpointException("a command batch is missing");
        batches.clear();
        batches.addAll(read);
    }
}
