package com.xpdustry.shard.server.store;

/** The store can't open, migrate or back up its database, or a transaction failed. */
public class StoreException extends RuntimeException{
    public StoreException(String message){
        super(message);
    }

    public StoreException(String message, Throwable cause){
        super(message, cause);
    }
}
