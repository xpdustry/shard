package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

import static com.xpdustry.shard.sim.path.Flowfield.*;
import static org.junit.jupiter.api.Assertions.*;

/** What the path service's state must equal: a fresh read of the world, and fields built from scratch. */
final class References{
    /**
     * The view equals the world (nothing pending) but for health, which the view samples only when a tile changes,
     * and everything derived equals a fresh derivation.
     */
    static void checkGrid(World world, PathService paths, String where){
        PathGrid grid = paths.grid();
        for(int pos = 0; pos < grid.size; pos++){
            if(((grid.view[pos] ^ grid.read(world, pos)) & ~PathGrid.HEALTH_MASK) != 0) fail(where + ": the view of tile " + pos + " is stale");
        }
        checkDerived(paths, where);
    }

    /** The packed tiles equal a full packing of the view. */
    static void checkDerived(PathService paths, String where){
        PathGrid grid = paths.grid(), fresh = new PathGrid(grid);
        fresh.packAll();
        assertArrayEquals(fresh.tiles, grid.tiles, where + ": packed tiles");
    }

    /**
     * A ready field equals a full build from its targets, and its distances and parents equal a textbook Dijkstra; a
     * building RTS field equals a replay of its pops from the start. Returns the number of tiles the field could enter
     * but no target reaches.
     */
    static int checkField(PathService paths, Flowfield field, String where){
        FieldEngine engine = paths.engine();
        var rebuilt = new Flowfield(field.team, field.cls, field.target, field.dist.length);
        rebuilt.targets = new IntSeq(field.targets);
        rebuilt.large = field.large;
        rebuilt.threat = field.threat;
        if(field.state == State.BUILDING){
            engine.start(rebuilt);
            engine.step(rebuilt, field.pops);
            assertEquals(State.BUILDING, rebuilt.state, where + ": a replay finished early");
            assertEquals(field.frontierKey, rebuilt.frontierKey, where + ": frontier");
        }else{
            engine.build(rebuilt);
        }
        assertArrayEquals(rebuilt.dist, field.dist, where + ": distances differ from a rebuild");
        assertArrayEquals(rebuilt.parent, field.parent, where + ": parents differ from a rebuild");
        if(field.state != State.READY) return 0;
        int[] dist = new int[field.dist.length];
        byte[] parent = new byte[field.dist.length];
        dijkstra(engine, field, dist, parent);
        assertArrayEquals(dist, field.dist, where + ": distances differ from Dijkstra");
        assertArrayEquals(parent, field.parent, where + ": parents differ from Dijkstra");
        int unreached = 0;
        for(int pos = 0; pos < dist.length; pos++){
            if(dist[pos] == INF && engine.cost(field, pos) != PathCost.IMPASSABLE) unreached++;
        }
        return unreached;
    }

    /** Plain Dijkstra with a priority queue, then the first-tight-direction parents, written independently. */
    static void dijkstra(FieldEngine engine, Flowfield field, int[] dist, byte[] parent){
        PathGrid grid = engine.grid;
        int width = grid.width, height = grid.height;
        int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
        Arrays.fill(dist, INF);
        var queue = new PriorityQueue<long[]>(Comparator.comparingLong((long[] e) -> e[0]));
        for(int i = 0; i < field.targets.size; i++){
            dist[field.targets.get(i)] = 0;
            queue.add(new long[]{0, field.targets.get(i)});
        }
        while(!queue.isEmpty()){
            long[] e = queue.poll();
            int u = (int)e[1];
            if(e[0] != dist[u]) continue;
            for(int d = 0; d < 4; d++){
                int x = u % width + dx[d], y = u / width + dy[d];
                if(x < 0 || y < 0 || x >= width || y >= height) continue;
                int v = x + y * width, c = engine.cost(field, v);
                if(c == PathCost.IMPASSABLE) continue;
                long nd = e[0] + c;
                if(nd < dist[v]){
                    dist[v] = (int)nd;
                    queue.add(new long[]{nd, v});
                }
            }
        }
        for(int u = 0; u < dist.length; u++){
            parent[u] = NONE;
            if(dist[u] == 0 && field.targets.contains(u)){
                parent[u] = ROOT;
                continue;
            }
            if(dist[u] == INF) continue;
            int c = engine.cost(field, u);
            for(int d = 0; d < 4; d++){
                int x = u % width + dx[d], y = u / width + dy[d];
                if(x < 0 || y < 0 || x >= width || y >= height) continue;
                int dv = dist[x + y * width];
                if(dv != INF && (long)dv + c == dist[u]){
                    parent[u] = (byte)d;
                    break;
                }
            }
        }
    }

    private References(){}
}
