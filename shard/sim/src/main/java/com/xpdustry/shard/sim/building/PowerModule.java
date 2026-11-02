package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A building's power state (vanilla {@code PowerModule}): its battery or satisfaction status, whether it joined its
 * graph, the {@link PowerGraph} it is in (shared by every member, which a fork remaps through the
 * {@link CopyContext}), and its laser links as packed positions. Allocated only for blocks with {@code hasPower}.
 *
 * <p>Vanilla's field initializer makes the graph, which draws its updater's entity id; here
 * {@link Building}'s creation does, so a throwaway module read from a save stays graphless.
 *
 * <p>Owner: WP37.
 */
public final class PowerModule{
    public float status;
    public boolean init;
    /** The graph this building is in; null only for a throwaway module. */
    public PowerGraph graph;
    public final IntSeq links;

    public PowerModule(){
        links = new IntSeq();
    }

    private PowerModule(PowerModule source, CopyContext context){
        status = source.status;
        init = source.init;
        graph = context.map(source.graph);
        links = source.links.copy();
    }

    public PowerModule copy(CopyContext context){
        return new PowerModule(this, context);
    }

    public void hash(Hasher hasher){
        hasher.f(status).bool(init).i(graph == null ? -1 : graph.id()).ints(links.items, 0, links.size);
    }
}
