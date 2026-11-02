package com.xpdustry.shard.sim.entity;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Groups.sync} and {@code Groups.all}, in their order. The generated {@code add()} of each class
 * names its groups (javap of v160.7 {@code mindustry.gen}):
 * <ul>
 *   <li>units: {@code unit}, {@code sync}, {@code draw}. Units are not in {@code all}.</li>
 *   <li>players: {@code player}, {@code sync}, {@code draw}.</li>
 *   <li>fires, puddles and world labels: {@code all}, {@code sync}, {@code draw}; weather states add {@code weather}.</li>
 *   <li>buildings: {@code build} only.</li>
 * </ul>
 * {@code Groups.unit} and {@code Groups.build} are the world's own update lists, and {@code Groups.player} is
 * {@code sim.unit.Players}. Nothing draws on a server, so {@code draw} is not kept.
 *
 * <p>{@code sync} is the order snapshots write entities in (WP25), and {@link #byId} is its {@code getByID}.
 * {@code all} is the order {@code Groups.all.update()} runs them in ({@link MiscUpdate}).
 *
 * <p>{@link Persistence#AUTHORITATIVE}: both orders. The positions entities remember and the id index are rebuilt.
 */
@Register
public final class EntityGroups implements WorldPart, Checkpointable{
    private static final ActiveList.Slot<Entity> SYNC_SLOT = new ActiveList.Slot<>(){
        @Override
        public int get(Entity entity){
            return entity.syncIndex;
        }

        @Override
        public void set(Entity entity, int position){
            entity.syncIndex = position;
        }
    };
    private static final ActiveList.Slot<Entity> ALL_SLOT = new ActiveList.Slot<>(){
        @Override
        public int get(Entity entity){
            return entity.allIndex;
        }

        @Override
        public void set(Entity entity, int position){
            entity.allIndex = position;
        }
    };

    private final ActiveList<Entity> sync, all;
    /** {@code sync} positions by entity id; null until the first lookup after a copy or a restore. */
    @Derived("the sync order, on the first lookup")
    private IntIntMap ids;

    public EntityGroups(){
        sync = new ActiveList<>(SYNC_SLOT);
        all = new ActiveList<>(ALL_SLOT);
    }

    private EntityGroups(EntityGroups source, CopyContext context){
        sync = source.sync.copy(context);
        all = source.all.copy(context);
    }

    /** The world's groups, or null in a world built without them. */
    public static EntityGroups of(World world){
        return world.find(EntityGroups.class);
    }

    /** {@code Groups.sync}: every synced entity, in snapshot order. */
    public ActiveList<Entity> sync(){
        return sync;
    }

    /** {@code Groups.all}: fires, puddles, weather and labels, in update order. */
    public ActiveList<Entity> all(){
        return all;
    }

    /** Joins {@code Groups.sync}, as a generated {@code add()} does. */
    public void addSync(Entity entity){
        sync.add(entity);
        if(ids != null) ids.put(entity.id(), entity.syncIndex);
    }

    /** Leaves {@code Groups.sync}; the entity moved into its place keeps its id entry current. */
    public void removeSync(Entity entity){
        int position = entity.syncIndex;
        sync.remove(entity);
        if(ids == null) return;
        ids.remove(entity.id());
        if(position >= 0 && position < sync.size()) ids.put(sync.get(position).id(), position);
    }

    /** Joins {@code Groups.all}. */
    public void addAll(Entity entity){
        all.add(entity);
    }

    /** Leaves {@code Groups.all}. */
    public void removeAll(Entity entity){
        all.remove(entity);
    }

    /** {@code Groups.sync.getByID(id)}: the synced entity with this id, or null. */
    public Entity byId(int id){
        if(ids == null){
            ids = new IntIntMap(Math.max(16, sync.size() * 2));
            for(int i = 0; i < sync.size(); i++) ids.put(sync.get(i).id(), i);
        }
        int position = ids.get(id, -1);
        return position < 0 ? null : sync.get(position);
    }

    @Override
    public WorldPart copy(CopyContext context){
        return new EntityGroups(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(sync.size());
        for(int i = 0; i < sync.size(); i++) hasher.i(sync.get(i).id());
        hasher.i(all.size());
        for(int i = 0; i < all.size(); i++) hasher.i(all.get(i).id());
    }

    @Override
    public void write(CheckpointOut out){
        out.refs("sync", sync.toList().toArray(Entity[]::new), sync.size());
        out.refs("all", all.toList().toArray(Entity[]::new), all.size());
    }

    @Override
    public void read(CheckpointIn in){
        sync.restore(members(in.refs("sync", Entity.class), "sync"));
        all.restore(members(in.refs("all", Entity.class), "all"));
        ids = null;
    }

    private static List<Entity> members(Entity[] stored, String group){
        if(stored == null) throw new CheckpointException("Groups." + group + " is missing");
        for(Entity entity : stored){
            if(entity == null) throw new CheckpointException("Groups." + group + " holds a missing entity");
        }
        return Arrays.asList(stored);
    }
}
