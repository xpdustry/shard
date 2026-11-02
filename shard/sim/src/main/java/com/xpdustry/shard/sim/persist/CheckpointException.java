package com.xpdustry.shard.sim.persist;

/**
 * A checkpoint can't be written or read: corrupt or truncated bytes, another content epoch, a schema that no longer
 * matches the code, or state with no codec. The message says which section, class or field.
 */
public final class CheckpointException extends RuntimeException{
    public CheckpointException(String message){
        super(message);
    }

    public CheckpointException(String message, Throwable cause){
        super(message, cause);
    }
}
