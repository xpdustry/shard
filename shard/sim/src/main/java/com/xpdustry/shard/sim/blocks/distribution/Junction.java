package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Junction.JunctionBuild} (Junction.java): one queue per direction ({@code DirectionalItemBuffer}),
 * each holding up to {@code capacity} items with the game time they entered. An item leaves on the far side once
 * {@code speed / timeScale} ticks have passed (or the clock went back), when the building there takes it; a queue
 * whose head is refused waits. Disabled by logic, a junction doesn't update.
 *
 * <p>The queues are vanilla's packed longs ({@code BufferItem}: the item id in the low 16 bits, the time's float bits
 * above), saved as they are, stale entries past each queue's end included.
 */
@Kind("junction")
public final class Junction extends Building implements Checkpointable{
    /** Per direction, {@code capacity} packed entries: direction {@code d}'s entry {@code j} is at {@code d * capacity + j}. */
    public long[] buffers = new long[0];
    /** Per direction, how many entries are queued; vanilla allocates a fifth that is never used. */
    public int[] indexes = new int[5];
    @Derived("the block's capacity, read on creation")
    private int capacity;
    @Derived("the block's speed, read on first use")
    private float speed = Float.NaN;

    @Override
    public void created(){
        buffers = new long[4 * capacity()];
    }

    /** The block's {@code capacity}: queue length per direction. */
    public int capacity(){
        if(capacity == 0) capacity = block().baked() ? block().fields().i("capacity") : 6;
        return capacity;
    }

    private float speed(){
        if(Float.isNaN(speed)) speed = block().baked() ? block().fields().f("speed") : 26f;
        return speed;
    }

    @Override
    public int acceptStack(int item, int amount, int sourceTeam){
        return 0;
    }

    @Override
    public void updateTile(){
        int capacity = capacity();
        float now = world().timers().time();
        for(int i = 0; i < 4; i++){
            if(indexes[i] > 0){
                if(indexes[i] > capacity) indexes[i] = capacity;
                long l = buffers[i * capacity];
                float time = BufferItem.time(l);
                if(now >= time + speed() / timeScale || now < time){
                    int item = BufferItem.item(l);
                    // content.item(id) with no mapper: an id outside the content is null
                    if(item >= world().content().items()) item = -1;
                    Building dest = nearby(i);
                    // skip blocks that don't want the item, keep waiting until they do
                    if(item < 0 || dest == null || !dest.acceptItem(this, item) || dest.team != team) continue;
                    dest.handleItem(this, item);
                    System.arraycopy(buffers, i * capacity + 1, buffers, i * capacity, indexes[i] - 1);
                    indexes[i]--;
                }
            }
        }
    }

    @Override
    public void handleItem(Building source, int item){
        int relative = source.relativeTo(tileX(), tileY());
        // DirectionalItemBuffer.accept
        if(!accepts(relative)) return;
        buffers[relative * capacity() + indexes[relative]++] = BufferItem.get(item, world().timers().time());
    }

    @Override
    public boolean acceptItem(Building source, int item){
        int relative = source.relativeTo(tileX(), tileY());
        if(relative == -1 || !accepts(relative)) return false;
        Building to = nearby(relative);
        return to != null && to.team == team;
    }

    private boolean accepts(int direction){
        return indexes[direction] < capacity();
    }

    /** Vanilla's generated {@code BufferItem} struct: a short item id and a float time in one long. */
    public static final class BufferItem{
        private BufferItem(){
        }

        public static long get(int item, float time){
            return ((long)(short)item & 0xFFFFL) | ((long)Float.floatToIntBits(time) << 16);
        }

        public static short item(long packed){
            return (short)(packed & 0xFFFFL);
        }

        public static float time(long packed){
            return Float.intBitsToFloat((int)((packed >>> 16) & 0xFFFFFFFFL));
        }

        /** {@code BufferItemLegacy} to {@code BufferItem}: revision 0 junctions packed a byte id and the time above it. */
        public static long fromLegacy(long packed){
            return get((byte)(packed & 0xFFL), Float.intBitsToFloat((int)((packed >>> 8) & 0xFFFFFFFFL)));
        }
    }

    @Override
    public void write(CheckpointOut out){
        out.longs("buffers", buffers).ints("indexes", indexes);
    }

    @Override
    public void read(CheckpointIn in){
        buffers = in.longs("buffers");
        indexes = in.ints("indexes");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Junction)into;
        out.buffers = buffers.clone();
        out.indexes = indexes.clone();
        out.capacity = capacity;
        out.speed = speed;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.ints(indexes);
        for(long l : buffers) hasher.l(l);
    }
}
