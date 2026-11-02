package com.xpdustry.shard.compat.v160.msav;

/** An msav file Shard cannot read: unsupported, truncated or inconsistent. The message names the region and offset. */
public final class MsavException extends RuntimeException{
    public MsavException(String message){
        super(message);
    }

    public MsavException(String message, Throwable cause){
        super(message, cause);
    }
}
