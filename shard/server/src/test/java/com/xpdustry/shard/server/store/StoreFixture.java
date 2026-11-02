package com.xpdustry.shard.server.store;

import com.xpdustry.shard.server.config.*;

import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.*;

/** Stores in a test directory, and a clock tests move by hand. */
public final class StoreFixture{
    public static Store.Settings settings(Path dir, int writerQueue, int readers){
        return new Store.Settings(dir.resolve("shard.db"), ConfigKeys.Synchronous.FULL, writerQueue, readers, Duration.ofSeconds(5), dir.resolve("backups"));
    }

    public static Store open(Path dir, InstantSource clock){
        return Store.open(settings(dir, 4096, 4), clock);
    }

    /** An {@link InstantSource} that only moves when told to. */
    public static final class ManualClock implements InstantSource{
        private final AtomicLong millis;

        public ManualClock(long millis){
            this.millis = new AtomicLong(millis);
        }

        public void advance(Duration duration){
            millis.addAndGet(duration.toMillis());
        }

        @Override
        public Instant instant(){
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis(){
            return millis.get();
        }
    }

    private StoreFixture(){}
}
