package com.xpdustry.shard.sim.blocks.storage;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * One {@link ItemModule} that several buildings hold, as vanilla's cores and linked storage hold the same object: a
 * team's cores share their first core's module, and {@link Storage} linked to a core shares the core's. Each sharer
 * keeps this holder beside its {@code items}, which is always the holder's {@link #module}, so a fork maps the holder
 * once for every sharer and a checkpoint stores it once, and both give the sharers one module again
 * ({@link Core#restored()}, {@link Storage#restored()}).
 *
 * <p>{@link #blank()} copies the module at once: sharers take the copy's module while the fork is still filling them.
 */
public final class SharedItems implements Copyable, Checkpointable{
    public ItemModule module;

    public SharedItems(){
    }

    SharedItems(ItemModule module){
        this.module = module;
    }

    /** {@code building}'s holder, made around its own module when it shares nothing yet. Cores and storage only. */
    static SharedItems of(Building building){
        return switch(building){
            case Core core -> core.shared != null ? core.shared : (core.shared = new SharedItems(core.items));
            case Storage storage -> storage.shared != null ? storage.shared : (storage.shared = new SharedItems(storage.items));
            default -> throw new IllegalArgumentException(building + " shares no items");
        };
    }

    @Override
    public Copyable blank(){
        return new SharedItems(module.copy());
    }

    @Override
    public void copyTo(Copyable into, CopyContext context){
        // blank() copied the module already
    }

    @Override
    public void write(CheckpointOut out){
        out.obj("module", module);
    }

    @Override
    public void read(CheckpointIn in){
        module = in.obj("module", ItemModule.class);
        if(module == null) throw new CheckpointException("shared items without a module");
    }
}
