package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.wire.*;

import java.util.function.*;

/**
 * A {@link Reads} that keeps every byte it reads, so a value whose length only its layout knows (a building's body in
 * a block payload) can be skipped and handed on as bytes. Its methods read like {@code Reads}'.
 */
public final class Tape{
    private final Reads in;
    private final WireContext context;
    private final Consumer<Object> nested;
    private final Writes kept = new Writes();

    public Tape(Reads in){
        this(in, null, null);
    }

    /**
     * A tape whose nested payloads are measured in {@code context} ({@link #measure}), null for none, and handed to
     * {@code nested} as they are decoded, in read order, if it isn't null.
     */
    public Tape(Reads in, WireContext context, Consumer<Object> nested){
        this.in = in;
        this.context = context;
        this.nested = nested;
    }

    /** Hands a decoded nested value (a carried payload block's own payload, or null for none) to the listener, if any. */
    public void nested(Object value){
        if(nested != null) nested.accept(value);
    }

    /** What nested layouts are read in (a carried building's own payload), or null when the reader gave none. */
    public WireContext context(){
        return context;
    }

    /**
     * Reads a value whose length only {@code reader} knows, ahead on a fork of the input, then keeps that many bytes:
     * a payload inside a carried payload block.
     */
    public void measure(Consumer<Reads> reader){
        Reads ahead = in.fork();
        int start = ahead.position();
        reader.accept(ahead);
        b(ahead.position() - start);
    }

    private Reads take(int count){
        byte[] bytes = in.b(count);
        kept.b(bytes);
        return new Reads(bytes);
    }

    public byte b(){
        return take(1).b();
    }

    public int ub(){
        return take(1).ub();
    }

    public boolean bool(){
        return take(1).bool();
    }

    public short s(){
        return take(2).s();
    }

    public int us(){
        return take(2).us();
    }

    public int i(){
        return take(4).i();
    }

    public float f(){
        return take(4).f();
    }

    public long l(){
        return take(8).l();
    }

    public double d(){
        return take(8).d();
    }

    /** Arc's {@code str()}: a 2-byte length, then that many modified UTF-8 bytes. */
    public String str(){
        byte[] length = in.b(2);
        int size = (length[0] & 0xff) << 8 | length[1] & 0xff;
        byte[] body = in.b(size);
        kept.b(length);
        kept.b(body);
        var whole = new byte[size + 2];
        System.arraycopy(length, 0, whole, 0, 2);
        System.arraycopy(body, 0, whole, 2, size);
        return new Reads(whole).str();
    }

    /** Reads {@code count} bytes, failing at the end as {@code Reads.b(count)} does. */
    public byte[] b(int count){
        byte[] bytes = in.b(count);
        kept.b(bytes);
        return bytes;
    }

    /** Everything read so far. */
    public byte[] bytes(){
        return kept.toByteArray();
    }
}
