package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;

import java.util.*;

/**
 * Vanilla's {@code SpawnPathFilter}: from every tile of every core not of the wave team to every enemy spawn, an A*
 * path ({@code Astar.pathfind}, solid tiles cost 100, deep floors impassable) is cleared to {@code block} within
 * {@code radius}, sparing blocks someone built.
 */
public record SpawnPathFilter(int radius, BlockDef block) implements GenerateFilter{
    @Override
    public boolean post(){
        return true;
    }

    @Override
    public void apply(FilterGrid grid, GenerateInput in){
        var cores = new IntSeq();
        var spawns = new IntSeq();

        for(int pos = 0; pos < grid.size(); pos++){
            if(grid.overlay(pos) == grid.spawn){
                spawns.add(pos);
            }
            // Vanilla quirk: every tile a core covers is a start, not only its centre.
            if(grid.isCore(grid.block(pos)) && grid.team(pos) != grid.rules.waveTeam){
                cores.add(pos);
            }
        }

        for(int c = 0; c < cores.size; c++){
            for(int s = 0; s < spawns.size; s++){
                IntSeq path = pathfind(grid, cores.get(c), spawns.get(s));
                for(int p = 0; p < path.size; p++){
                    int tile = path.get(p);
                    for(int x = -radius; x <= radius; x++){
                        for(int y = -radius; y <= radius; y++){
                            int wx = grid.x(tile) + x, wy = grid.y(tile) + y;
                            if(wx >= 0 && wy >= 0 && wx < grid.width() && wy < grid.height() && Mathf.within(x, y, radius)){
                                int other = grid.pos(wx, wy);
                                if(!grid.synthetic(other)){
                                    grid.setBlock(other, block.id());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * {@code Astar.pathfind} with the Manhattan heuristic: the path from {@code start} (excluded) to {@code end}
     * (included), or empty when there is none. Tiles close when queued, not when expanded, as vanilla's do.
     */
    static IntSeq pathfind(FilterGrid grid, int start, int end){
        int width = grid.width(), height = grid.height();
        int endX = grid.x(end), endY = grid.y(end);
        float[] costs = new float[grid.size()];
        byte[] rotations = new byte[grid.size()];
        var closed = new BitSet(grid.size());
        var queue = new Heap(costs, grid, endX, endY);
        queue.add(start);

        boolean found = false;
        while(!queue.empty()){
            int next = queue.poll();
            float baseCost = costs[next];
            if(next == end){
                found = true;
                break;
            }
            closed.set(next);
            int nx = grid.x(next), ny = grid.y(next);
            for(int d = 0; d < 4; d++){
                int newx = nx + Geometry.d4x(d), newy = ny + Geometry.d4y(d);
                if(newx >= 0 && newy >= 0 && newx < width && newy < height){
                    int child = grid.pos(newx, newy);
                    if(!grid.isDeep(grid.floor(child))){
                        float newCost = (grid.solid(child) ? 100 : 1) + baseCost;
                        if(!closed.get(child)){
                            closed.set(child);
                            rotations[child] = relativeTo(newx, newy, nx, ny);
                            costs[child] = newCost;
                            queue.add(child);
                        }
                    }
                }
            }
        }

        var out = new IntSeq();
        if(!found) return out;

        int current = end;
        while(current != start){
            out.add(current);
            byte rotation = rotations[current];
            current = grid.pos(grid.x(current) + Geometry.d4x(rotation), grid.y(current) + Geometry.d4y(rotation));
        }
        out.reverse();
        return out;
    }

    /** {@code Tile.relativeTo(cx, cy)}. */
    private static byte relativeTo(int x, int y, int cx, int cy){
        if(x == cx && y == cy - 1) return 1;
        if(x == cx && y == cy + 1) return 3;
        if(x == cx - 1 && y == cy) return 0;
        if(x == cx + 1 && y == cy) return 2;
        return -1;
    }

    /** Arc's {@code PQueue} of tiles, ordered by cost plus the Manhattan distance to the end ({@code Structs.comparingFloat}). */
    private static final class Heap{
        private final float[] costs;
        private final FilterGrid grid;
        private final int endX, endY;
        private int[] queue = new int[200 * 200 / 4];
        private int size;

        Heap(float[] costs, FilterGrid grid, int endX, int endY){
            this.costs = costs;
            this.grid = grid;
            this.endX = endX;
            this.endY = endY;
        }

        boolean empty(){
            return size == 0;
        }

        void add(int e){
            int i = size;
            if(i >= queue.length) queue = Arrays.copyOf(queue, grow(queue.length, i + 1));
            size = i + 1;
            if(i == 0) queue[0] = e;
            else siftUp(i, e);
        }

        int poll(){
            int s = --size;
            int result = queue[0];
            int x = queue[s];
            if(s != 0) siftDown(0, x);
            return result;
        }

        private void siftUp(int k, int x){
            while(k > 0){
                int parent = (k - 1) >>> 1;
                int e = queue[parent];
                if(compare(x, e) >= 0) break;
                queue[k] = e;
                k = parent;
            }
            queue[k] = x;
        }

        private void siftDown(int k, int x){
            int half = size >>> 1;
            while(k < half){
                int child = (k << 1) + 1;
                int c = queue[child];
                int right = child + 1;
                if(right < size && compare(c, queue[right]) > 0) c = queue[child = right];
                if(compare(x, c) <= 0) break;
                queue[k] = c;
                k = child;
            }
            queue[k] = x;
        }

        private int compare(int a, int b){
            return Float.compare(key(a), key(b));
        }

        private float key(int tile){
            // Astar.manhattan, with Math.abs on ints, as a float
            return costs[tile] + (float)(Math.abs(grid.x(tile) - endX) + Math.abs(grid.y(tile) - endY));
        }

        /** {@code PQueue.growToSize}: double while small, then 1.5 times. */
        private static int grow(int old, int min){
            int capacity = (int)(old < 64 ? (old + 1) * 2f : old * 1.5f);
            return Math.max(capacity, min);
        }
    }
}
