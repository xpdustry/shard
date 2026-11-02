package com.xpdustry.shard.compat.v160.rules;

/**
 * What Arc's {@code Json} writes through, text or binary: {@link ArcJson.Writer} (Arc's {@code JsonWriter}, minimal)
 * or {@link UbJson.Writer} ({@code UBJsonWriter}, the saves' markers). Values keep their boxed type, which the binary
 * form encodes: a {@code Byte} is {@code i}, an {@code Integer} {@code l}, a {@code Float} {@code d}.
 */
public interface JsonSink{
    JsonSink name(String name);

    JsonSink object();

    JsonSink array();

    JsonSink pop();

    /** A string, boxed number, boolean or null. */
    JsonSink value(Object value);
}
