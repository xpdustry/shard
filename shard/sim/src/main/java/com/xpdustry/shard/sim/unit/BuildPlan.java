package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.LAccess;
import com.xpdustry.shard.sim.building.ConfigValue;
import com.xpdustry.shard.sim.building.ContentRef;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * One entry of a builder's plan queue (vanilla {@code BuildPlan}): place {@link #block} at ({@link #x}, {@link #y}), or
 * break what is there. {@code TypeIO.writePlan} syncs the position, rotation, block and config; the rest is server
 * state. {@code animScale} is client-only. WP32 ports building.
 *
 * <p>Every field is {@link Persistence#AUTHORITATIVE}.
 */
public final class BuildPlan implements Checkpointable{
    public int x, y, rotation;
    /** The block's content id, or -1 (always -1 when breaking). */
    public int block = -1;
    public boolean breaking;
    /**
     * The block config to apply on completion, as {@code TypeIO.writeObject} reads it: null, a boxed primitive, a
     * {@link String}, a {@link Point2} or {@link Vec2}, content ({@link ContentRef}, or {@link ContentRef.Foreign} for a
     * type no epoch holds), an array of those, or a {@code byte[]}, {@code int[]} ({@code IntSeq}) or {@code boolean[]}.
     * Arrays, points and vectors are copied with the plan.
     */
    public Object config;
    public float progress;
    public boolean initialized, stuck, cachedValid;
    public boolean worldContext = true;

    /**
     * {@code new BuildPlan(x, y, rotation, block, config)}: a plan to place {@code block}, its rotation as
     * {@link #planRotation} keeps it.
     */
    public static BuildPlan place(int x, int y, int rotation, BlockDef block, Object config){
        return place(x, y, planRotation(block, rotation), block.id(), config);
    }

    /**
     * {@code block.planRotation(rotation)} (Block.java:1653): a block that doesn't rotate is planned at rotation 0,
     * unless it unlocks its rotation ({@code lockRotation = false}).
     */
    public static int planRotation(BlockDef block, int rotation){
        boolean rotate = block.baked() && block.rotate();
        boolean lockRotation = !block.baked() || !block.fields().has("lockRotation") || block.fields().b("lockRotation");
        return !rotate && lockRotation ? 0 : rotation;
    }

    /**
     * A plan to place the block of content id {@code block}, at {@code rotation} as given: for plans whose rotation
     * already went through {@link #planRotation} (a stock client's, a save's), or an unknown block.
     */
    public static BuildPlan place(int x, int y, int rotation, int block, Object config){
        var plan = new BuildPlan();
        plan.x = x;
        plan.y = y;
        plan.rotation = rotation;
        plan.block = block;
        plan.config = config;
        return plan;
    }

    /** A plan to break whatever covers ({@code x}, {@code y}). */
    public static BuildPlan breaking(int x, int y){
        var plan = new BuildPlan();
        plan.x = x;
        plan.y = y;
        plan.breaking = true;
        return plan;
    }

    BuildPlan copy(){
        var out = new BuildPlan();
        out.x = x;
        out.y = y;
        out.rotation = rotation;
        out.block = block;
        out.breaking = breaking;
        out.config = copyConfig(config);
        out.progress = progress;
        out.initialized = initialized;
        out.stuck = stuck;
        out.cachedValid = cachedValid;
        out.worldContext = worldContext;
        return out;
    }

    /** The config is stored as tagged bytes ({@link #writeConfig}), since its type varies. */
    @Override
    public void write(CheckpointOut out){
        var config = new Writes(16);
        writeConfig(this.config, config);
        out.i("x", x).i("y", y).i("rotation", rotation).i("block", block).bool("breaking", breaking)
            .bytes("config", config.buffer(), config.size()).f("progress", progress).bool("initialized", initialized)
            .bool("stuck", stuck).bool("cachedValid", cachedValid).bool("worldContext", worldContext);
    }

    @Override
    public void read(CheckpointIn in){
        x = in.i("x");
        y = in.i("y");
        rotation = in.i("rotation");
        block = in.i("block");
        breaking = in.bool("breaking");
        var config = new Reads(in.bytes("config"));
        try{
            this.config = readConfig(config);
        }catch(RuntimeException e){
            throw new CheckpointException("a build plan's config is corrupt", e);
        }
        if(config.remaining() != 0) throw new CheckpointException("a build plan's config has trailing bytes");
        progress = in.f("progress");
        initialized = in.bool("initialized");
        stuck = in.bool("stuck");
        cachedValid = in.bool("cachedValid");
        worldContext = in.bool("worldContext");
    }

    /**
     * A config as a tag and its value, for checkpoints; boxed types keep their class, so a restored config equals the
     * original. Anything else is a {@link CheckpointException}: a config type nothing makes yet.
     */
    public static void writeConfig(Object config, Writes out){
        switch(config){
            case null -> out.b(0);
            case Integer value -> {
                out.b(1);
                out.i(value);
            }
            case Long value -> {
                out.b(2);
                out.l(value);
            }
            case Short value -> {
                out.b(3);
                out.s(value);
            }
            case Byte value -> {
                out.b(4);
                out.b(value);
            }
            case Character value -> {
                out.b(5);
                out.s(value);
            }
            case Float value -> {
                out.b(6);
                out.f(value);
            }
            case Double value -> {
                out.b(7);
                out.d(value);
            }
            case Boolean value -> {
                out.b(8);
                out.bool(value);
            }
            case String value -> {
                out.b(9);
                out.str(value);
            }
            case Point2 point -> {
                out.b(10);
                out.i(point.x());
                out.i(point.y());
            }
            case Vec2 vec -> {
                out.b(11);
                out.f(vec.x);
                out.f(vec.y);
            }
            case byte[] bytes -> {
                out.b(12);
                out.i(bytes.length);
                out.b(bytes);
            }
            case int[] ints -> {
                out.b(13);
                out.i(ints.length);
                for(int value : ints) out.i(value);
            }
            case boolean[] bools -> {
                out.b(14);
                out.i(bools.length);
                for(boolean value : bools) out.bool(value);
            }
            case ContentRef ref -> {
                out.b(18);
                out.str(ref.type().name());
                out.i(ref.id());
            }
            case ContentRef.Foreign ref -> {
                out.b(19);
                out.i(ref.vanillaType());
                out.i(ref.id());
            }
            // the wire's other values (ConfigValue)
            case ConfigValue.Team team -> {
                out.b(20);
                out.i(team.id());
            }
            case ConfigValue.TechNode node -> {
                out.b(21);
                writeConfig(node.content(), out);
            }
            case ConfigValue.IntArray ints -> {
                out.b(22);
                writeConfig(ints.ints(), out);
            }
            case LAccess sensor -> {
                out.b(23);
                out.str(sensor.name());
            }
            case Object[] values -> {
                // Point2[] and Vec2[] keep their component type
                out.b(values instanceof Point2[] ? 15 : values instanceof Vec2[] ? 16 : 17);
                out.i(values.length);
                for(Object value : values) writeConfig(value, out);
            }
            default -> throw new CheckpointException("a build plan's config is a " + config.getClass().getName() + ", which checkpoints can't store");
        }
    }

    public static Object readConfig(Reads in){
        int tag = in.b();
        return switch(tag){
            case 0 -> null;
            case 1 -> in.i();
            case 2 -> in.l();
            case 3 -> in.s();
            case 4 -> in.b();
            case 5 -> (char)in.s();
            case 6 -> in.f();
            case 7 -> in.d();
            case 8 -> in.bool();
            case 9 -> in.str();
            case 10 -> new Point2(in.i(), in.i());
            case 11 -> new Vec2(in.f(), in.f());
            case 12 -> in.b(length(in));
            case 13 -> {
                var ints = new int[length(in)];
                for(int i = 0; i < ints.length; i++) ints[i] = in.i();
                yield ints;
            }
            case 14 -> {
                var bools = new boolean[length(in)];
                for(int i = 0; i < bools.length; i++) bools[i] = in.bool();
                yield bools;
            }
            case 15, 16, 17 -> {
                int length = length(in);
                Object[] values = tag == 15 ? new Point2[length] : tag == 16 ? new Vec2[length] : new Object[length];
                for(int i = 0; i < length; i++) values[i] = readConfig(in);
                yield values;
            }
            case 18 -> new ContentRef(ContentType.valueOf(in.str()), in.i());
            case 19 -> new ContentRef.Foreign(in.i(), in.i());
            case 20 -> new ConfigValue.Team(in.i());
            case 21 -> new ConfigValue.TechNode(readConfig(in));
            case 22 -> new ConfigValue.IntArray((int[])readConfig(in));
            case 23 -> LAccess.valueOf(in.str());
            default -> throw new IllegalStateException("unknown config tag " + tag);
        };
    }

    private static int length(Reads in){
        int length = in.i();
        if(length < 0 || length > in.remaining()) throw new IllegalStateException("bad config length " + length);
        return length;
    }

    void hash(Hasher hasher){
        hasher.i(x).i(y).i(rotation).i(block).bool(breaking);
        hashConfig(config, hasher);
        hasher.f(progress).bool(initialized).bool(stuck).bool(cachedValid).bool(worldContext);
    }

    public static Object copyConfig(Object config){
        return switch(config){
            // a fresh instance even for an immutable point: the copy shares nothing with its source
            case Point2 point -> new Point2(point.x(), point.y());
            case Point2[] points -> {
                var out = new Point2[points.length];
                for(int i = 0; i < points.length; i++) out[i] = points[i] == null ? null : new Point2(points[i].x(), points[i].y());
                yield out;
            }
            case byte[] bytes -> bytes.clone();
            case int[] ints -> ints.clone();
            case boolean[] bools -> bools.clone();
            case Vec2 vec -> new Vec2(vec);
            case Vec2[] vecs -> {
                var out = new Vec2[vecs.length];
                for(int i = 0; i < vecs.length; i++) out[i] = vecs[i] == null ? null : new Vec2(vecs[i]);
                yield out;
            }
            case Object[] values -> {
                var out = new Object[values.length];
                for(int i = 0; i < values.length; i++) out[i] = copyConfig(values[i]);
                yield out;
            }
            case null, default -> config;
        };
    }

    public static void hashConfig(Object config, Hasher hasher){
        switch(config){
            case null -> hasher.i(0);
            case Point2 point -> hasher.i(1).i(point.x()).i(point.y());
            case byte[] bytes -> hasher.i(2).bytes(bytes);
            case int[] ints -> hasher.i(10).i(ints.length).ints(ints);
            case boolean[] bools -> {
                hasher.i(11).i(bools.length);
                for(boolean value : bools) hasher.bool(value);
            }
            case Vec2 vec -> hasher.i(12).f(vec.x).f(vec.y);
            case ContentRef ref -> hasher.i(14).i(ref.type().ordinal()).i(ref.id());
            case ContentRef.Foreign ref -> hasher.i(15).i(ref.vanillaType()).i(ref.id());
            case Vec2[] vecs -> {
                hasher.i(13).i(vecs.length);
                for(Vec2 vec : vecs) hashConfig(vec, hasher);
            }
            case Object[] values -> {
                hasher.i(3).i(values.length);
                for(Object value : values) hashConfig(value, hasher);
            }
            case Float value -> hasher.i(4).f(value);
            case Double value -> hasher.i(5).d(value);
            case Number value -> hasher.i(6).l(value.longValue());
            case Boolean value -> hasher.i(7).bool(value);
            case String value -> hasher.i(8).str(value);
            default -> hasher.i(9).str(config.getClass().getName()).str(Objects.toString(config));
        }
    }
}
