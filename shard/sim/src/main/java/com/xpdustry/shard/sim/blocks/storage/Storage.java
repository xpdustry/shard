package com.xpdustry.shard.sim.blocks.storage;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code StorageBlock.StorageBuild} (StorageBlock.java): the container and vault, and Erekir's reinforced
 * ones. Next to a core of its team (and with {@code coreMerge}), it links to it: {@link Core#onProximityUpdate()} gives
 * it the core's {@link ItemModule} and sets {@link #linkedCore}, so it takes, holds and gives the core's items, and
 * vanilla's save writes the core's items for it. Unlinked, it is a plain item store of {@code itemCapacity}.
 *
 * <p>The shared module is one object, as vanilla's; forks and checkpoints keep the sharing ({@link SharedItems}).
 *
 * <p>Not ported: the campaign's sector item counts in {@code removeStack} and {@code itemTaken}.
 */
@Kind("storage-block")
public final class Storage extends Building implements Checkpointable{
    /** The core this one's items belong to, or null. */
    public Core linkedCore;
    /** The module this one shares with a core, or null: {@code items} is its module then. */
    SharedItems shared;
    @Derived("the block's coreMerge, read on first use")
    private int coreMerge = -1;

    /** {@code coreMerge}: whether this block links to an adjacent core. */
    public boolean coreMerge(){
        if(coreMerge == -1) coreMerge = !block().baked() || block().fields().b("coreMerge") ? 1 : 0;
        return coreMerge == 1;
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return linkedCore != null ? linkedCore.acceptItem(source, item) : items.get(item) < getMaximumAccepted(item);
    }

    @Override
    public void overwrote(java.util.List<Building> previous){
        overwrote(this, linkedCore, previous);
    }

    /**
     * {@code StorageBuild.overwrote(previous)} (StorageBlock.java:119), which cores inherit: unlinked, it adds the
     * items of the buildings it replaced, linked storage's excepted, and caps each at its own capacity.
     */
    static void overwrote(Building building, Core linkedCore, java.util.List<Building> previous){
        if(linkedCore != null) return;
        var items = building.items;
        for(Building other : previous){
            if(other.items != null && other.items != items && !(other instanceof Storage storage && storage.linkedCore != null)) items.add(other.items);
        }
        int capacity = building.info().itemCapacity;
        items.each((item, amount) -> items.set(item, Math.min(amount, capacity)));
    }

    @Override
    public boolean canUnload(){
        return linkedCore == null ? super.canUnload() : linkedCore.canUnload();
    }

    @Override
    public void handleItem(Building source, int item){
        if(linkedCore != null){
            if(linkedCore.items.get(item) >= linkedCore.storageCapacity) incinerateEffect(this, source);
            linkedCore.noEffect = true;
            linkedCore.handleItem(source, item);
        }else{
            super.handleItem(source, item);
        }
    }

    @Override
    public void itemTaken(int item){
        if(linkedCore != null) linkedCore.itemTaken(item);
    }

    @Override
    public int getMaximumAccepted(int item){
        return linkedCore != null ? linkedCore.getMaximumAccepted(item) : info().itemCapacity;
    }

    @Override
    public int explosionItemCap(){
        // linked to a core, containers and vaults are much less explosive
        return linkedCore != null ? Math.min(info().itemCapacity / 60, 6) : info().itemCapacity;
    }

    @Override
    public boolean allowDeposit(){
        return linkedCore != null || super.allowDeposit();
    }

    /**
     * {@code StorageBlock.incinerateEffect(self, source)}: the burn effect's chance draws from the world random on a
     * server, though nothing is shown there.
     */
    public static void incinerateEffect(Building self, Building source){
        Mathf.chance(self.world().rand(), 0.3);
    }

    /** Takes the core's module: the link of {@code CoreBuild.onProximityUpdate}. */
    void link(Core core){
        shared = SharedItems.of(core);
        items = shared.module;
        linkedCore = core;
    }

    /** {@code CoreBuild.onRemoved}'s unlink: a module of its own again, holding {@code own}. */
    void unlink(ItemModule own){
        linkedCore = null;
        shared = null;
        items = own;
    }

    @Override
    public void write(CheckpointOut out){
        out.ref("linkedCore", linkedCore).ref("shared", shared);
    }

    @Override
    public void read(CheckpointIn in){
        linkedCore = in.ref("linkedCore", Core.class);
        shared = in.ref("shared", SharedItems.class);
    }

    @Override
    public void restored(){
        if(shared != null) items = shared.module;
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Storage)into;
        out.linkedCore = context.map(linkedCore);
        out.shared = context.map(shared);
        if(out.shared != null) out.items = out.shared.module;
        out.coreMerge = coreMerge;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(linkedCore == null ? -1 : linkedCore.id()).bool(shared != null);
    }

    /** {@code canPickup()}: payload units may lift it unless it is linked to a core. */
    @Override
    public boolean canPickup(){
        return linkedCore == null;
    }
}
