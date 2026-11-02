package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code BufferedItemBridge.BufferedItemBridgeBuild} (BufferedItemBridge.java), the bridge conveyor: an
 * unpowered {@link ItemBridgeBuild} whose items cross through an {@code ItemBuffer} of {@code bufferCapacity}. Each
 * update a linked bridge moves one item into the buffer, and hands the buffer's head to its link once it has been
 * in for {@code speed / timeScale} ticks, at most once per {@code 4 / timeScale} ticks; unlinked, it dumps one item.
 *
 * <p>The buffer is vanilla's {@code ItemBuffer}: packed longs ({@code TimeItem}: a short of data, -1 here, in the low
 * 16 bits, the item id above it, the time's float bits in the high 32), saved whole, stale entries included.
 */
@Kind("buffered-item-bridge")
public final class BufferedItemBridge extends ItemBridgeBuild{
    /** {@code buffer.buffer}: {@code bufferCapacity} packed entries, the first {@link #bufferIndex} queued. */
    public long[] buffer = new long[0];
    /** {@code buffer.index}: how many entries are queued. */
    public int bufferIndex;
    @Derived("the block's parameters, read on first use")
    private float speed = Float.NaN;
    @Derived("the block's parameters, read on first use")
    private int timerAccept = -1;

    @Override
    public void created(){
        buffer = new long[block().baked() ? block().fields().i("bufferCapacity") : 50];
    }

    private float speed(){
        if(Float.isNaN(speed)) speed = block().baked() ? block().fields().f("speed") : 40f;
        return speed;
    }

    private int timerAccept(){
        if(timerAccept == -1) timerAccept = block().baked() ? block().fields().i("timerAccept") : 2;
        return timerAccept;
    }

    @Override
    protected void updateTransport(Building other){
        if(bufferIndex < buffer.length && items.total() > 0){
            buffer[bufferIndex++] = TimeItem.get((short)-1, (short)items.take(), world().timers().time());
        }
        int item = poll(speed() / timeScale);
        if(timer(timerAccept(), 4 / timeScale) && item != -1 && other.acceptItem(this, item)){
            moved = true;
            other.handleItem(this, item);
            System.arraycopy(buffer, 1, buffer, 0, bufferIndex - 1);
            bufferIndex--;
        }
    }

    /** {@code buffer.poll(speed)}: the head's item once it has waited {@code speed} ticks (or the clock went back), else -1. */
    private int poll(float speed){
        if(bufferIndex > 0){
            long l = buffer[0];
            float time = TimeItem.time(l);
            float now = world().timers().time();
            if(now >= time + speed || now < time){
                int item = TimeItem.item(l);
                // content.item(id): an id outside the content is null
                return item < 0 || item >= world().content().items() ? -1 : item;
            }
        }
        return -1;
    }

    @Override
    protected void doDump(){
        dump();
    }

    /** Vanilla's generated {@code TimeItem} struct: a short of data, a short item id and a float time in one long. */
    public static final class TimeItem{
        private TimeItem(){
        }

        public static long get(short data, short item, float time){
            return ((long)data & 0xFFFFL) | (((long)item << 16) & 0xFFFF0000L) | ((long)Float.floatToIntBits(time) << 32);
        }

        public static short item(long packed){
            return (short)((packed >>> 16) & 0xFFFFL);
        }

        public static float time(long packed){
            return Float.intBitsToFloat((int)((packed >>> 32) & 0xFFFFFFFFL));
        }
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.longs("buffer", buffer).i("bufferIndex", bufferIndex);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        buffer = in.longs("buffer");
        bufferIndex = in.i("bufferIndex");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (BufferedItemBridge)into;
        out.buffer = buffer.clone();
        out.bufferIndex = bufferIndex;
        out.speed = speed;
        out.timerAccept = timerAccept;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(bufferIndex);
        for(long l : buffer) hasher.l(l);
    }
}
