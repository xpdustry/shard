package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.common.registry.*;

/**
 * A subsystem's own world state (team data, wave state, power graphs, flowfields), registered with
 * {@code @Register} so that adding one edits no shared file. Every world gets a fresh instance of each, created with
 * the public no-argument constructor and then {@link #init(World)}; {@link World#part(Class)} finds it.
 *
 * <p>A part doesn't keep the {@code World} it was given in {@code init}. Objects inside it may reference world
 * objects; {@link #copy(CopyContext)} remaps them.
 */
@Registry
public interface WorldPart{
    /** Called once on a new world, after its tables exist. */
    default void init(World world){
    }

    /** A deep copy for {@code context.world()}, mapping references to world objects through the context. */
    WorldPart copy(CopyContext context);

    /**
     * Called on each of a fork's parts once every object of the fork is filled: for links between its objects that
     * {@link #copy} can't map, because the objects weren't filled yet.
     */
    default void forked(World fork){
    }

    /** Feeds the part's authoritative state to the world hash. Derived parts may feed nothing. */
    void hash(Hasher hasher);

    default Persistence persistence(){
        return Persistence.AUTHORITATIVE;
    }

    /** Rebuilds a {@link Persistence#DERIVED} part from the world's authoritative state on restore. */
    default void rebuild(World world){
        throw new UnsupportedOperationException(getClass().getName() + " is not derived");
    }
}
