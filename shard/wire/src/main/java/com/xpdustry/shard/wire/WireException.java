package com.xpdustry.shard.wire;

import java.io.*;
import java.util.function.*;

/**
 * Malformed, truncated or out-of-limit input: a body vanilla would also reject. Decoders throw this, not vanilla's
 * mix of NPEs, array index and IO exceptions, so any other exception from a decoder is a Shard bug.
 */
public final class WireException extends RuntimeException{
    public WireException(String message){
        super(message);
    }

    public WireException(String message, Throwable cause){
        super(message, cause);
    }

    /**
     * Runs a decoder, turning the end of input and malformed modified UTF-8 ({@code :common}'s {@code Reads} throws
     * them as {@link UncheckedIOException}) into a {@link WireException} that names {@code what}.
     */
    public static <T> T decoding(String what, Supplier<T> decoder){
        try{
            return decoder.get();
        }catch(UncheckedIOException e){
            throw new WireException(what + ": " + (e.getCause() instanceof EOFException ? "ends early" : e.getCause().getMessage()), e);
        }catch(WireException e){
            throw new WireException(what + ": " + e.getMessage(), e);
        }
    }
}
