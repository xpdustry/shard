package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.struct.*;

import java.util.*;

/**
 * One field: the exact shortest distance from every tile to the nearest target, for one (team, cost class, targets),
 * and a canonical parent per tile. {@link FieldEngine} builds and repairs it.
 *
 * <p>The parent of a tile is the first d4 direction, in {@code Geometry.d4} order, to a neighbour that lies on a
 * shortest path ({@code dist[v] + cost(u) == dist[u]}). It is a function of the distances and the costs alone, so a
 * repaired field and a rebuilt one are equal array for array, and a restored world rebuilds exactly the field it
 * stored. Repairs use the parents to find the tiles a cost increase can reach.
 */
final class Flowfield{
    static final int INF = Integer.MAX_VALUE;
    /** {@link #parent} of a target, and of a tile no target reaches. */
    static final byte ROOT = 4, NONE = 5;
    /** The {@link #target} of a wave-AI field: vanilla's {@code EnemyCoreField}. */
    static final int ENEMY_CORES = -1;

    enum State{
        /** Requested; the next flush builds it whole (wave-AI fields). */
        PENDING,
        /** Being built under the RTS pop budget; tiles below {@link #frontierKey} are final. */
        BUILDING,
        /** Complete, and repaired at every flush. */
        READY
    }

    final int team;
    final PathCost cls;
    /** {@link #ENEMY_CORES}, or the destination tile of an RTS field. */
    final int target;
    /** RTS fields use {@code ControlPathfinder}'s costs; wave-AI fields use {@code Pathfinder}'s. */
    final boolean rts;
    final int[] dist;
    final byte[] parent;
    /** Sorted, distinct. */
    IntSeq targets = new IntSeq();
    State state;
    /** RTS builds: the queue between budget steps, the pops spent so far (0 once ready), and the distance of the last pop. */
    LongHeap frontier;
    long pops;
    int frontierKey;
    /** Holders, and the flush tick at which the last one let go. */
    int refs;
    long released;
    /** Derived from the rules: whether the clearance and threat layers apply. */
    boolean large, threat;

    Flowfield(int team, PathCost cls, int target, int size){
        this.team = team;
        this.cls = cls;
        this.target = target;
        this.rts = target != ENEMY_CORES;
        dist = new int[size];
        parent = new byte[size];
        Arrays.fill(dist, INF);
        Arrays.fill(parent, NONE);
        state = rts ? State.BUILDING : State.PENDING;
        frontierKey = -1;
    }

    private Flowfield(Flowfield source){
        team = source.team;
        cls = source.cls;
        target = source.target;
        rts = source.rts;
        dist = source.dist.clone();
        parent = source.parent.clone();
        targets = new IntSeq(source.targets);
        state = source.state;
        frontier = source.frontier == null ? null : source.frontier.copy();
        pops = source.pops;
        frontierKey = source.frontierKey;
        refs = source.refs;
        released = source.released;
        large = source.large;
        threat = source.threat;
    }

    Flowfield copy(){
        return new Flowfield(this);
    }

    /** The lookup key: team, class, family and target. */
    static long key(int team, PathCost cls, int target){
        return (long)team << 40 | (long)cls.ordinal() << 32 | (target & 0xffffffffL);
    }

    long key(){
        return key(team, cls, target);
    }

    /** Whether {@code pos}'s distance is final. */
    boolean settled(int pos){
        return state == State.READY || (state == State.BUILDING && dist[pos] < frontierKey);
    }

    /** Vanilla's weight: the distance, or -1 for a tile no target reaches (or not reached yet). */
    int weight(int pos){
        int d = dist[pos];
        return d == INF || !settled(pos) ? -1 : d;
    }

    boolean isTarget(int pos){
        return parent[pos] == ROOT;
    }
}
