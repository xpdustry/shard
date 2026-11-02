package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.struct.*;

import java.util.*;

import static com.xpdustry.shard.sim.path.Flowfield.*;
import static com.xpdustry.shard.sim.path.PathCost.*;

/**
 * Builds and repairs {@link Flowfield}s over a {@link PathGrid} (note 22 §2.1, §2.2). Holds the scratch that one
 * world's tick reuses: buckets, a heap and a mark array. Scratch is never state; a fork gets fresh scratch.
 *
 * <ul>
 *   <li>Wave-AI fields build with Dial's algorithm: a circular bucket queue over {@value #BUCKETS} distances, which
 *   settles each tile once. Vanilla's FIFO label-correcting search ({@code Pathfinder.java:498}) gives the same
 *   distances with up to 58 pops per tile (note 22 §3.2).</li>
 *   <li>RTS fields cost up to {@code ControlPathfinder.wallImpassableCap} (10⁶) per tile, beyond any bucket array,
 *   so they build with a binary heap, in budgeted steps.</li>
 *   <li>A repair invalidates the shortest-path subtree under every tile whose cost went up (and under removed
 *   targets), seeds it and the tiles whose cost went down from their neighbours, and runs Dijkstra from the seeds.
 *   Its result equals a full build, distances and parents alike.</li>
 * </ul>
 */
final class FieldEngine{
    /** The largest tile cost a wave-AI field may have: Shard's layers are capped here so Dial's buckets suffice. */
    static final int MAX_COST = 8191;
    static final int BUCKETS = MAX_COST + 1;
    private static final int[] DX = PathGrid.DX, DY = PathGrid.DY;

    final PathGrid grid;
    Threat threat;
    private final int width, height;
    private int[][] buckets;
    private int[] bucketSize;
    /** One bit per non-empty bucket. */
    private long[] occupied;
    private final int[] mark;
    private int markGen;
    private final IntSeq affected = new IntSeq();
    private final LongHeap heap = new LongHeap();
    /** Work done since this engine was made, for metrics: full builds, repairs and the tiles they invalidated. */
    long builds, repairs, invalidated;

    FieldEngine(PathGrid grid, Threat threat){
        this.grid = grid;
        this.threat = threat;
        width = grid.width;
        height = grid.height;
        mark = new int[grid.size];
    }

    /** The family's vanilla cost of {@code pos}, without the map-area limit or Shard's layers. */
    int baseCost(Flowfield field, int pos){
        int tile = grid.tiles[pos];
        return field.rts ? control(field.cls, field.team, tile) : flow(field.cls, field.team, tile);
    }

    /** The cost the field pays to enter {@code pos}. */
    int cost(Flowfield field, int pos){
        int c = baseCost(field, pos);
        if(c == IMPASSABLE) return IMPASSABLE;
        PathRules rules = grid.rules;
        if(field.rts && rules.limitMapArea() && !rules.isAI(field.team) && outsideArea(pos, rules)) return IMPASSABLE;
        if(field.large && nearBlocked(field, pos)) c += CLEARANCE_COST;
        if(field.threat) c += threat.at(field.team, pos);
        return field.rts ? c : Math.min(c, MAX_COST);
    }

    /** The RTS cost of {@code pos} for any team and class, with the map-area limit: {@code ControlPathfinder.cost}. */
    int rtsCost(int team, PathCost cls, int pos){
        int c = control(cls, team, grid.tiles[pos]);
        if(c == IMPASSABLE) return IMPASSABLE;
        PathRules rules = grid.rules;
        return rules.limitMapArea() && !rules.isAI(team) && outsideArea(pos, rules) ? IMPASSABLE : c;
    }

    /** How far a tile change reaches into this field's costs, in tiles (Chebyshev). */
    int reach(Flowfield field){
        return field.large ? 1 : 0;
    }

    /** {@code ControlPathfinder.cost}'s map-area limit. */
    private boolean outsideArea(int pos, PathRules rules){
        int x = pos % width, y = pos / width;
        return x < rules.limitX() || y < rules.limitY() || x > rules.limitX() + rules.limitWidth() || y > rules.limitY() + rules.limitHeight();
    }

    /** Soft clearance: on the map's edge, or next to (8 neighbours) a tile this field can't enter. */
    private boolean nearBlocked(Flowfield field, int pos){
        int x = pos % width, y = pos / width;
        if(x == 0 || y == 0 || x == width - 1 || y == height - 1) return true;
        for(int d = 0; d < 8; d++){
            if(baseCost(field, x + DX[d] + (y + DY[d]) * width) == IMPASSABLE) return true;
        }
        return false;
    }

    // building

    /** Builds {@code field} whole from its targets. */
    void build(Flowfield field){
        builds++;
        if(field.rts){
            start(field);
            step(field, Long.MAX_VALUE);
        }else{
            dial(field);
        }
    }

    /** Resets an RTS field to its targets, ready for {@link #step}. */
    void start(Flowfield field){
        Arrays.fill(field.dist, INF);
        Arrays.fill(field.parent, NONE);
        field.frontier = new LongHeap();
        field.pops = 0;
        field.frontierKey = -1;
        field.state = State.BUILDING;
        for(int i = 0; i < field.targets.size; i++){
            int t = field.targets.get(i);
            field.dist[t] = 0;
            field.parent[t] = ROOT;
            field.frontier.add(LongHeap.key(0, t));
        }
    }

    /** Spends up to {@code budget} pops on an RTS build; returns the pops spent. Every pop counts, stale ones too. */
    long step(Flowfield field, long budget){
        LongHeap queue = field.frontier;
        long used = 0;
        while(used < budget && !queue.isEmpty()){
            long entry = queue.poll();
            used++;
            int du = LongHeap.distance(entry), u = LongHeap.tile(entry);
            field.frontierKey = du;
            if(field.dist[u] != du) continue;
            settle(field, u, du, queue, false);
        }
        field.pops += used;
        if(queue.isEmpty()){
            field.state = State.READY;
            field.frontier = null;
            // what a finished build cost is history the world can't rebuild: repairs have moved on since
            field.pops = 0;
            field.frontierKey = -1;
        }
        return used;
    }

    /** Dial's algorithm over a circular bucket array; LIFO within a bucket. */
    private void dial(Flowfield field){
        if(buckets == null){
            buckets = new int[BUCKETS][];
            bucketSize = new int[BUCKETS];
            occupied = new long[BUCKETS / 64];
            for(int i = 0; i < BUCKETS; i++) buckets[i] = new int[8];
        }
        int[] dist = field.dist;
        byte[] parent = field.parent;
        Arrays.fill(dist, INF);
        Arrays.fill(parent, NONE);
        int queued = 0, cur = 0;
        for(int i = 0; i < field.targets.size; i++){
            int t = field.targets.get(i);
            dist[t] = 0;
            parent[t] = ROOT;
            push(t, 0);
            queued++;
        }
        while(queued > 0){
            int bucket = cur & MAX_COST;
            if(bucketSize[bucket] == 0){
                // skip the empty run a word at a time: naval costs put 6000 between the distances of land tiles
                cur += (nextOccupied(bucket) - bucket) & MAX_COST;
                continue;
            }
            int u = buckets[bucket][--bucketSize[bucket]];
            if(bucketSize[bucket] == 0) occupied[bucket >>> 6] &= ~(1L << bucket);
            queued--;
            if(dist[u] != cur) continue;
            if(parent[u] != ROOT) parent[u] = canonical(field, u, cur, cost(field, u));
            int ux = u % width, uy = u / width;
            for(int d = 0; d < 4; d++){
                int vx = ux + DX[d], vy = uy + DY[d];
                if(vx < 0 || vy < 0 || vx >= width || vy >= height) continue;
                int v = vx + vy * width, c = cost(field, v);
                if(c == IMPASSABLE) continue;
                int nd = cur + c;
                if(nd < cur) continue; // overflow: out of reach
                if(nd < dist[v]){
                    dist[v] = nd;
                    push(v, nd);
                    queued++;
                }
            }
        }
        field.state = State.READY;
        field.frontier = null;
    }

    private void push(int tile, int distance){
        int bucket = distance & MAX_COST;
        int[] items = buckets[bucket];
        if(bucketSize[bucket] == items.length) buckets[bucket] = items = Arrays.copyOf(items, items.length * 2);
        items[bucketSize[bucket]++] = tile;
        occupied[bucket >>> 6] |= 1L << bucket;
    }

    /**
     * The first non-empty bucket at or after {@code from}, circularly. Every queued distance lies within
     * {@value #MAX_COST} of the current one, so that bucket holds the smallest.
     */
    private int nextOccupied(int from){
        int words = occupied.length, word = from >>> 6;
        long bits = occupied[word] & (-1L << from);
        for(int i = 0; i <= words; i++){
            if(bits != 0) return (word << 6) + Long.numberOfTrailingZeros(bits);
            word = (word + 1) % words;
            bits = occupied[word];
        }
        throw new IllegalStateException("no queued bucket");
    }

    /**
     * Settles {@code u} at {@code du}: sets its canonical parent and relaxes its neighbours into {@code queue}. With
     * {@code fixTight}, a neighbour that {@code u} reaches at exactly its distance gets its parent recomputed, since a
     * repair may have just made {@code u} an earlier shortest-path direction for it.
     */
    private void settle(Flowfield field, int u, int du, LongHeap queue, boolean fixTight){
        int[] dist = field.dist;
        byte[] parent = field.parent;
        if(parent[u] != ROOT) parent[u] = canonical(field, u, du, cost(field, u));
        int ux = u % width, uy = u / width;
        for(int d = 0; d < 4; d++){
            int vx = ux + DX[d], vy = uy + DY[d];
            if(vx < 0 || vy < 0 || vx >= width || vy >= height) continue;
            int v = vx + vy * width, c = cost(field, v);
            if(c == IMPASSABLE) continue;
            int nd = du + c;
            if(nd < du) continue; // overflow: out of reach
            if(nd < dist[v]){
                dist[v] = nd;
                queue.add(LongHeap.key(nd, v));
            }else if(fixTight && nd == dist[v] && parent[v] != ROOT){
                parent[v] = canonical(field, v, nd, c);
            }
        }
    }

    /** The first d4 direction to a neighbour on a shortest path to {@code u}, or {@link Flowfield#NONE}. */
    private byte canonical(Flowfield field, int u, int du, int cu){
        int[] dist = field.dist;
        int ux = u % width, uy = u / width;
        for(int d = 0; d < 4; d++){
            int vx = ux + DX[d], vy = uy + DY[d];
            if(vx < 0 || vy < 0 || vx >= width || vy >= height) continue;
            int dv = dist[vx + vy * width];
            if(dv != INF && dv < du && (long)dv + cu == du) return (byte)d;
        }
        return NONE;
    }

    // repair

    /**
     * Repairs a ready field after the costs of {@code changed} moved ({@code oldCosts[i]} is the cost of
     * {@code changed[i]} before) and its target set gained {@code added} and lost {@code removed} (the field's
     * {@code targets} already hold the new set). Returns the number of tiles invalidated.
     */
    int repair(Flowfield field, IntSeq changed, int[] oldCosts, IntSeq added, IntSeq removed){
        int[] dist = field.dist;
        byte[] parent = field.parent;
        if(++markGen == Integer.MAX_VALUE){
            Arrays.fill(mark, 0);
            markGen = 1;
        }
        int gen = markGen;
        repairs++;
        affected.clear();
        for(int i = 0; i < removed.size; i++){
            int t = removed.get(i);
            if(mark[t] != gen){
                mark[t] = gen;
                affected.add(t);
            }
        }
        // 1. the tiles whose cost went up, then their subtrees: exactly the tiles whose distance may rise
        for(int i = 0; i < changed.size; i++){
            int t = changed.get(i), now = cost(field, t), before = oldCosts[i];
            boolean up = now == IMPASSABLE ? before != IMPASSABLE : before != IMPASSABLE && now > before;
            if(up && !field.isTarget(t) && dist[t] != INF && mark[t] != gen){
                mark[t] = gen;
                affected.add(t);
            }
        }
        for(int i = 0; i < affected.size; i++){
            int u = affected.get(i), ux = u % width, uy = u / width;
            for(int d = 0; d < 4; d++){
                int vx = ux + DX[d], vy = uy + DY[d];
                if(vx < 0 || vy < 0 || vx >= width || vy >= height) continue;
                int v = vx + vy * width;
                // v hangs below u when its parent points back along d
                if(mark[v] != gen && parent[v] == (byte)((d + 2) & 3)){
                    mark[v] = gen;
                    affected.add(v);
                }
            }
        }
        for(int i = 0; i < affected.size; i++){
            int u = affected.get(i);
            dist[u] = INF;
            parent[u] = NONE;
        }
        // 2. seeds: new targets, invalidated tiles from their valid neighbours, cheaper tiles from all neighbours
        heap.clear();
        for(int i = 0; i < added.size; i++){
            int t = added.get(i);
            dist[t] = 0;
            parent[t] = ROOT;
            heap.add(LongHeap.key(0, t));
        }
        for(int i = 0; i < affected.size; i++) seed(field, affected.get(i));
        for(int i = 0; i < changed.size; i++){
            int t = changed.get(i);
            if(mark[t] != gen) seed(field, t);
        }
        // 3. Dijkstra from the seeds; they may span more than the bucket range, so a heap
        while(!heap.isEmpty()){
            long entry = heap.poll();
            int du = LongHeap.distance(entry), u = LongHeap.tile(entry);
            if(dist[u] != du) continue;
            settle(field, u, du, heap, true);
        }
        invalidated += affected.size;
        return affected.size;
    }

    private void seed(Flowfield field, int u){
        if(field.isTarget(u)) return;
        int c = cost(field, u);
        if(c == IMPASSABLE) return;
        int[] dist = field.dist;
        int ux = u % width, uy = u / width;
        long best = dist[u];
        for(int d = 0; d < 4; d++){
            int vx = ux + DX[d], vy = uy + DY[d];
            if(vx < 0 || vy < 0 || vx >= width || vy >= height) continue;
            int dv = dist[vx + vy * width];
            if(dv != INF) best = Math.min(best, (long)dv + c);
        }
        if(best < dist[u]){
            dist[u] = (int)best;
            heap.add(LongHeap.key((int)best, u));
        }
    }
}
