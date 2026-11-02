package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Per-team buckets of 8×8-tile chunks for building range queries (note 29 §5), in place of vanilla's per-team
 * {@code buildingTree} quadtrees. {@link Buildings} adds a building when its origin tile's change fires and removes it
 * before its tile changes, as vanilla's {@code BlockIndexer} does on its tile events.
 *
 * <p>A bucket keeps its buildings sorted by origin position, so the index depends only on which buildings stand, not
 * on the order they came in: it is {@link Persistence#DERIVED} and {@link #rebuild} gives exactly the live state.
 * Queries visit chunks row by row, then buildings by position. That is the tie order where vanilla's depends on the
 * quadtree's shape (note 29 accepts "differs on ties").
 */
final class BuildingChunks{
    /** Chunk edge, in tiles. */
    static final int CHUNK = 8;

    private final int width, height, chunksX, chunksY;
    private Team[] teams = new Team[0];
    /** Per registry index: the team it is indexed under, or -1. */
    private int[] indexedTeam = new int[0];

    BuildingChunks(int width, int height){
        this.width = width;
        this.height = height;
        chunksX = (width + CHUNK - 1) / CHUNK;
        chunksY = (height + CHUNK - 1) / CHUNK;
    }

    private BuildingChunks(BuildingChunks source){
        width = source.width;
        height = source.height;
        chunksX = source.chunksX;
        chunksY = source.chunksY;
        teams = new Team[source.teams.length];
        for(int i = 0; i < teams.length; i++) teams[i] = source.teams[i] == null ? null : source.teams[i].copy();
        indexedTeam = source.indexedTeam.clone();
    }

    BuildingChunks copy(){
        return new BuildingChunks(this);
    }

    /** Indexes {@code building} under its team; does nothing if it already is. */
    void add(Building building, int x, int y){
        add(building, building.team, x, y);
    }

    private void add(Building building, int filed, int x, int y){
        int index = building.index();
        if(index >= indexedTeam.length){
            int old = indexedTeam.length;
            indexedTeam = Arrays.copyOf(indexedTeam, Math.max(64, index * 2));
            Arrays.fill(indexedTeam, old, indexedTeam.length, -1);
        }
        if(indexedTeam[index] != -1) return;
        int team = filed & 0xff;
        if(team >= teams.length) teams = Arrays.copyOf(teams, team + 1);
        if(teams[team] == null) teams[team] = new Team(chunksX * chunksY);
        teams[team].add(chunk(x, y), index, building.tile(), building.block().size());
        indexedTeam[index] = team;
    }

    /** Drops {@code building} from the index; does nothing if it isn't indexed. */
    void remove(Building building, int x, int y){
        int index = building.index();
        if(index < 0 || index >= indexedTeam.length || indexedTeam[index] == -1) return;
        teams[indexedTeam[index]].remove(chunk(x, y), index, building.block().size());
        indexedTeam[index] = -1;
    }

    /** The team {@code building} is indexed under, or -1. */
    int indexedTeam(Building building){
        int index = building.index();
        return index < 0 || index >= indexedTeam.length ? -1 : indexedTeam[index];
    }

    /**
     * Re-indexes every live building of {@code buildings} under its team, or under the team {@code stale} pairs with
     * its registry index ({@code index, team, index, team…}): a dead building still standing after a team change.
     */
    void rebuild(Buildings buildings, IntSeq stale){
        teams = new Team[0];
        indexedTeam = new int[0];
        for(int i = 0; i < buildings.indexLimit(); i++){
            Building building = buildings.get(i);
            if(building == null) continue;
            int team = building.team;
            for(int j = 0; j < stale.size; j += 2){
                if(stale.get(j) == i) team = stale.get(j + 1);
            }
            add(building, team, buildings.world().tiles().x(building.tile()), buildings.world().tiles().y(building.tile()));
        }
    }

    private int chunk(int x, int y){
        return (y / CHUNK) * chunksX + x / CHUNK;
    }

    /** The largest block size indexed for {@code team}, 0 when it has none. */
    int maxSize(int team){
        return team < teams.length && teams[team] != null ? teams[team].maxSize() : 0;
    }

    /**
     * Visits the registry index of every building of {@code team} whose origin lies in a chunk that overlaps tiles
     * {@code x0..x1, y0..y1} (inclusive, clamped to the map): chunks row by row, buildings by position.
     */
    void each(int team, int x0, int y0, int x1, int y1, IndexVisitor visitor){
        if(team < 0 || team >= teams.length || teams[team] == null) return;
        Team data = teams[team];
        int cx0 = Math.max(0, x0) / CHUNK, cy0 = Math.max(0, y0) / CHUNK;
        int cx1 = Math.min(width - 1, x1) / CHUNK, cy1 = Math.min(height - 1, y1) / CHUNK;
        if(x1 < 0 || y1 < 0 || x0 >= width || y0 >= height) return;
        for(int cy = cy0; cy <= cy1; cy++){
            for(int cx = cx0; cx <= cx1; cx++){
                int c = cy * chunksX + cx, n = data.counts[c];
                int[] items = data.items[c];
                for(int i = 0; i < n; i++) visitor.visit(items[i]);
            }
        }
    }

    /** Hashes what a rebuild reproduces: the non-empty teams' buckets, not array capacities. */
    void hash(Hasher hasher){
        for(int t = 0; t < teams.length; t++){
            Team data = teams[t];
            if(data == null || data.total == 0) continue;
            hasher.i(t).i(data.total);
            for(int c = 0; c < data.counts.length; c++){
                if(data.counts[c] > 0) hasher.i(c).ints(data.items[c], 0, data.counts[c]);
            }
        }
    }

    interface IndexVisitor{
        void visit(int index);
    }

    /** One team's buckets: registry indexes sorted by origin position, and how many buildings of each size. */
    private static final class Team{
        final int[][] items, keys;
        final int[] counts;
        final int[] sizes = new int[128];
        int total;

        Team(int chunks){
            items = new int[chunks][];
            keys = new int[chunks][];
            counts = new int[chunks];
        }

        Team copy(){
            var out = new Team(counts.length);
            for(int c = 0; c < counts.length; c++){
                out.items[c] = items[c] == null ? null : items[c].clone();
                out.keys[c] = keys[c] == null ? null : keys[c].clone();
            }
            System.arraycopy(counts, 0, out.counts, 0, counts.length);
            System.arraycopy(sizes, 0, out.sizes, 0, sizes.length);
            out.total = total;
            return out;
        }

        void add(int c, int index, int pos, int size){
            if(items[c] == null){
                items[c] = new int[4];
                keys[c] = new int[4];
            }else if(counts[c] == items[c].length){
                items[c] = Arrays.copyOf(items[c], counts[c] * 2);
                keys[c] = Arrays.copyOf(keys[c], counts[c] * 2);
            }
            int at = counts[c];
            while(at > 0 && keys[c][at - 1] > pos) at--;
            System.arraycopy(items[c], at, items[c], at + 1, counts[c] - at);
            System.arraycopy(keys[c], at, keys[c], at + 1, counts[c] - at);
            items[c][at] = index;
            keys[c][at] = pos;
            counts[c]++;
            sizes[size]++;
            total++;
        }

        void remove(int c, int index, int size){
            for(int i = 0; i < counts[c]; i++){
                if(items[c][i] == index){
                    System.arraycopy(items[c], i + 1, items[c], i, counts[c] - i - 1);
                    System.arraycopy(keys[c], i + 1, keys[c], i, counts[c] - i - 1);
                    counts[c]--;
                    items[c][counts[c]] = 0;
                    keys[c][counts[c]] = 0;
                    sizes[size]--;
                    total--;
                    return;
                }
            }
            throw new IllegalStateException("building " + index + " is not in chunk " + c);
        }

        int maxSize(){
            for(int size = sizes.length - 1; size > 0; size--){
                if(sizes[size] > 0) return size;
            }
            return 0;
        }
    }
}
