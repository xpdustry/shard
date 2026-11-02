package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code DirectionalUnloader.DirectionalUnloaderBuild} (DirectionalUnloader.java:90), the duct unloader:
 * every {@code speed} ticks it moves one item from the building behind it to the one in front, both of its team. With
 * an item set it moves only that one; without, it tries every item from {@code offset} on and starts the next search
 * after the one it moved. A core, or storage linked to one, is never unloaded unless the block allows it.
 */
@Kind("directional-unloader")
public final class DirectionalUnloader extends Building implements Checkpointable{
    static final Configs<DirectionalUnloader> CONFIGS = Configs.of(DirectionalUnloader.class)
        .on(ContentType.ITEM, (unloader, item) -> unloader.unloadItem = item)
        .clear(unloader -> unloader.unloadItem = -1);

    public float unloadTimer;
    /** The item it unloads, or -1 for any. */
    public int unloadItem = -1;
    /** Where the next search over the items starts. */
    public int offset;

    private float speed(){
        return block().baked() ? block().fields().f("speed") : 1f;
    }

    private boolean allowCoreUnload(){
        return block().baked() && block().fields().b("allowCoreUnload");
    }

    @Override
    public void updateTile(){
        float speed = speed();
        if((unloadTimer += edelta()) < speed) return;
        Building front = front(), back = back();
        if(front != null && back != null && back.items != null && front.team == team && back.team == team && back.canUnload()
            && (allowCoreUnload() || !(back instanceof Core || (back instanceof Storage storage && storage.linkedCore != null)))){
            if(unloadItem == -1){
                int count = world().content().items();
                for(int i = 0; i < count; i++){
                    int item = (i + offset) % count;
                    if(back.items.has(item) && front.acceptItem(this, item)){
                        front.handleItem(this, item);
                        back.items.remove(item, 1);
                        back.itemTaken(item);
                        offset = item + 1;
                        break;
                    }
                }
            }else if(back.items.has(unloadItem) && front.acceptItem(this, unloadItem)){
                front.handleItem(this, unloadItem);
                back.items.remove(unloadItem, 1);
                back.itemTaken(unloadItem);
            }
        }
        unloadTimer %= speed;
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public Object config(){
        return unloadItem == -1 ? null : ContentRef.item(unloadItem);
    }

    @Override
    public void write(CheckpointOut out){
        out.f("unloadTimer", unloadTimer);
        out.i("unloadItem", unloadItem);
        out.i("offset", offset);
    }

    @Override
    public void read(CheckpointIn in){
        unloadTimer = in.f("unloadTimer");
        unloadItem = in.i("unloadItem");
        offset = in.i("offset");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (DirectionalUnloader)into;
        out.unloadTimer = unloadTimer;
        out.unloadItem = unloadItem;
        out.offset = offset;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(unloadTimer).i(unloadItem).i(offset);
    }
}
