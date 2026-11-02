package com.xpdustry.shard.server.host;

import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * The memory world streams may hold between their capture and their send: a capture of the map on the tick thread,
 * then, per joining player, the buffers of its encode on {@code io-cpu} (the raw stream, its deflated copy, the
 * deflater) until the stream is queued on the connection, whose own cap then bounds it. Each is reserved against a
 * global limit and a per-world one, with a cap on the encodes in flight, and released once queued, fenced or failed. A
 * load that doesn't fit waits for a later tick.
 *
 * <p>What a capture holds is known once it exists ({@link WorldSends.Footprint}), so a world reserves its last
 * capture's footprint, captures, and then {@link Capture#resize resizes} the reservation to the new footprint. If the
 * world grew past what fits, the capture is dropped unsent and the load waits; the next one reserves the new size up
 * front. No capture with its first stream may need more than {@link #captureLimit}, which is at most the per-world
 * limit, so every reservation stays within every limit, and one that fits alone always gets its turn.
 *
 * <p>Thread-safe: worlds reserve on their tick threads, encodes release on {@code io-cpu}.
 */
public final class CaptureBudget{
    private final long globalBytes, worldBytes, captureBytes;
    private final int encodes;
    /** Guarded by this. */
    private long reserved;
    private int inFlight;
    private final HashMap<Object, Long> byWorld = new HashMap<>();
    private final AtomicLong refused = new AtomicLong();

    /**
     * @param globalBytes bytes all worlds' captures may hold
     * @param worldBytes bytes one world's captures may hold
     * @param captureBytes bytes one capture with its first stream may hold; at most {@code worldBytes}
     * @param encodes streams being encoded at once, across worlds
     */
    public CaptureBudget(long globalBytes, long worldBytes, long captureBytes, int encodes){
        if(globalBytes <= 0 || worldBytes <= 0 || captureBytes <= 0 || encodes <= 0){
            throw new IllegalArgumentException("limits must be positive: " + globalBytes + ", " + worldBytes + ", " + captureBytes + ", " + encodes);
        }
        if(captureBytes > worldBytes || worldBytes > globalBytes){
            throw new IllegalArgumentException("a capture's limit is within a world's, and a world's within the global one: "
                + captureBytes + ", " + worldBytes + ", " + globalBytes);
        }
        this.globalBytes = globalBytes;
        this.worldBytes = worldBytes;
        this.captureBytes = captureBytes;
        this.encodes = encodes;
    }

    /** 256 MiB in all, 64 MiB per world and per capture, 32 encodes in flight. */
    public static CaptureBudget defaults(){
        return new CaptureBudget(256L << 20, 64L << 20, 64L << 20, 32);
    }

    /** The most one capture with its first stream may hold: a world that needs more can't be sent. */
    public long captureLimit(){
        return captureBytes;
    }

    /** Bytes reserved now. */
    public synchronized long reserved(){
        return reserved;
    }

    /** Bytes {@code world}'s captures hold now. */
    public synchronized long reserved(Object world){
        return byWorld.getOrDefault(world, 0L);
    }

    /** Encodes reserved and not yet released. */
    public synchronized int inFlight(){
        return inFlight;
    }

    /** Reservations refused so far: loads that waited a tick for memory. */
    public long refused(){
        return refused.get();
    }

    /**
     * A capture of {@code world}'s map, to share between the streams of one tick: {@code bytes} for the capture, then
     * {@link Capture#add} per stream.
     *
     * @return null, with nothing reserved, if it doesn't fit now
     */
    public Capture capture(Object world, long bytes){
        return reserve(world, bytes, 0) ? new Capture(world, bytes) : null;
    }

    private synchronized boolean reserve(Object world, long bytes, int encodes){
        long ours = byWorld.getOrDefault(world, 0L);
        if(reserved + bytes > globalBytes || ours + bytes > worldBytes || inFlight + encodes > this.encodes){
            refused.incrementAndGet();
            return false;
        }
        reserved += bytes;
        byWorld.put(world, ours + bytes);
        inFlight += encodes;
        return true;
    }

    private synchronized void release(Object world, long bytes, int encodes){
        reserved -= bytes;
        long left = byWorld.get(world) - bytes;
        if(left == 0) byWorld.remove(world);
        else byWorld.put(world, left);
        inFlight -= encodes;
    }

    /**
     * One tick's capture of a world and the streams made from it. The capture's bytes go back with its last stream,
     * or at {@link #close} if it has none.
     */
    public final class Capture implements AutoCloseable{
        private final Object world;
        /** Guarded by the budget. */
        private long bytes;
        private final List<Stream> streams = new ArrayList<>();
        /** Streams not yet released, plus one until {@link #close}. */
        private final AtomicInteger holders = new AtomicInteger(1);

        private Capture(Object world, long bytes){
            this.world = world;
            this.bytes = bytes;
        }

        /**
         * Reserves one stream's encode: {@code bytes} and a slot in flight.
         *
         * @return null, with nothing reserved, if it doesn't fit now
         */
        public Stream add(long bytes){
            if(!reserve(world, bytes, 1)) return null;
            holders.incrementAndGet();
            var stream = new Stream(this, bytes);
            synchronized(CaptureBudget.this){
                streams.add(stream);
            }
            return stream;
        }

        /**
         * Resizes the capture to {@code captureBytes} and each of its streams to {@code streamBytes}, at once: what
         * the capture turned out to hold. Before any stream is released.
         *
         * @return false, with nothing changed, if the new sizes don't fit now
         */
        public boolean resize(long captureBytes, long streamBytes){
            synchronized(CaptureBudget.this){
                long now = bytes, next = captureBytes;
                for(Stream stream : streams){
                    if(stream.released.get()) throw new IllegalStateException("a stream was released before the resize");
                    now += stream.bytes;
                    next += streamBytes;
                }
                long ours = byWorld.getOrDefault(world, 0L), delta = next - now;
                if(reserved + delta > globalBytes || ours + delta > worldBytes){
                    refused.incrementAndGet();
                    return false;
                }
                reserved += delta;
                byWorld.put(world, ours + delta);
                bytes = captureBytes;
                for(Stream stream : streams) stream.bytes = streamBytes;
                return true;
            }
        }

        /** No more streams: the capture's bytes go back with the last one. */
        @Override
        public void close(){
            drop();
        }

        private void drop(){
            if(holders.decrementAndGet() == 0){
                synchronized(CaptureBudget.this){
                    release(world, bytes, 0);
                }
            }
        }
    }

    /** One stream's reservation: {@link #release} it once its bytes are queued, fenced or failed. */
    public final class Stream{
        private final Capture capture;
        /** Guarded by the budget. */
        private long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Stream(Capture capture, long bytes){
            this.capture = capture;
            this.bytes = bytes;
        }

        /** Safe to call again. */
        public void release(){
            if(!released.compareAndSet(false, true)) return;
            synchronized(CaptureBudget.this){
                CaptureBudget.this.release(capture.world, bytes, 1);
            }
            capture.drop();
        }
    }
}
