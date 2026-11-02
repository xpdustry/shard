package com.xpdustry.shard.rig;

/** A failed step or a bad script. The message is what the verdict shows. */
public final class RigException extends RuntimeException{
    private static final long serialVersionUID = 1L;

    public RigException(String message){
        super(message);
    }
}
