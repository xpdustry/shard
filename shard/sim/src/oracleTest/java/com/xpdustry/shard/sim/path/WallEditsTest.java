package com.xpdustry.shard.sim.path;

import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.content.*;
import mindustry.world.*;
import mindustry.world.Tile;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static com.xpdustry.shard.sim.path.PathWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The darkness gate: vanilla computes a static wall's darkness ({@code Tile.data}) when the map loads, and
 * {@code Tile.setBlock} never recomputes it, so after a runtime wall edit {@code legSolid} reads the byte the map
 * loaded with. On three default maps hosted by the oracle, two rounds of edits run in both vanilla and Shard: first
 * interior static walls (darkness 2 or more) are removed and a 7×7 square of stone walls is built on open floor, then
 * the removed walls are put back and the square is cleared. After each round Shard's packed tiles equal vanilla's
 * {@code packTile} on every tile, and the wave team's four fields (legs, which {@code legSolid} prices, among them)
 * equal vanilla's after a refresh, distances and steps. The reference is a full repack, as on load: vanilla's own
 * {@code updateTile} repacks the edited tile alone and leaves its neighbours stale until then.
 */
class WallEditsTest{
    static final int REMOVED = 12, SQUARE = 7;

    static List<String> maps(){
        return List.of("maze", "fortress", "labyrinth");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void legSolidReadsTheDarknessTheMapLoadedWith(String map){
        Vanilla.host(map);
        VanillaPaths.repack();
        World shard = VanillaPaths.mirror();
        PathService paths = paths(shard);
        paths.rules(VanillaPaths.rules());
        paths.invalidateAll();
        shard.tick();
        int team = state.rules.waveTeam.id;
        var handles = new TreeMap<PathCost, Integer>();
        for(PathCost cls : VanillaPaths.COSTS.keySet()) handles.put(cls, paths.field(team, cls));
        shard.tick();

        int[] interior = interior(), square = openSquare();
        Block[] walls = new Block[interior.length];
        for(int i = 0; i < interior.length; i++){
            walls[i] = world.tiles.geti(interior[i]).block();
            set(shard, interior[i], Blocks.air);
        }
        for(int pos : square) set(shard, pos, Blocks.stoneWall);
        shard.tick();
        // a fresh darkness pass would give the square's centre 2: the edit keeps the byte it loaded with, 0
        assertFalse(world.tiles.geti(square[square.length / 2]).legSolid(), "the new square is dark");
        assertTrue(recomputed(shard) > 0, "no tile where recomputing the darkness changes legSolid: the edits test nothing");
        same(map + ", walls removed and a square built", shard, paths, team, handles);

        for(int i = 0; i < interior.length; i++) set(shard, interior[i], walls[i]);
        for(int pos : square) set(shard, pos, Blocks.air);
        shard.tick();
        for(int pos : interior) assertTrue(world.tiles.geti(pos).legSolid(), "a wall put back gets its old darkness");
        same(map + ", walls put back and the square cleared", shard, paths, team, handles);
    }

    /** How many tiles' {@code legSolid} a fresh darkness pass over Shard's edited grid would change. */
    static int recomputed(World shard){
        TileGrid live = shard.tiles(), fresh = live.copy();
        fresh.addDarkness(VanillaMaps.accessible());
        fresh.rebuildDerived();
        int changed = 0;
        for(int pos = 0; pos < live.size(); pos++){
            if(fresh.legSolid(pos) != live.legSolid(pos)) changed++;
        }
        return changed;
    }

    /** {@link #REMOVED} static walls of darkness 2 or more, spread over the map in tile order. */
    static int[] interior(){
        var candidates = new IntSeq();
        for(Tile tile : world.tiles){
            if(tile.build == null && tile.staticDarkness() >= 2) candidates.add(tile.array());
        }
        assertTrue(candidates.size >= REMOVED, "too few interior walls: " + candidates.size);
        int[] out = new int[REMOVED];
        for(int i = 0; i < REMOVED; i++) out[i] = candidates.get(i * candidates.size / REMOVED);
        return out;
    }

    /** The first {@link #SQUARE} × {@link #SQUARE} square of open, walkable floor, in tile order. */
    static int[] openSquare(){
        for(int y = 1; y + SQUARE < world.height(); y++){
            for(int x = 1; x + SQUARE < world.width(); x++){
                if(!open(x, y)) continue;
                int[] out = new int[SQUARE * SQUARE];
                for(int dy = 0; dy < SQUARE; dy++){
                    for(int dx = 0; dx < SQUARE; dx++) out[dx + dy * SQUARE] = world.tile(x + dx, y + dy).array();
                }
                return out;
            }
        }
        return fail("no open square on " + state.map.name());
    }

    static boolean open(int x0, int y0){
        for(int y = y0; y < y0 + SQUARE; y++){
            for(int x = x0; x < x0 + SQUARE; x++){
                Tile tile = world.tile(x, y);
                if(tile.block() != Blocks.air || tile.floor().solid || tile.floor().isDeep() || tile.overlay() == Blocks.spawn) return false;
            }
        }
        return true;
    }

    /** {@code Tile.setBlock} in vanilla and Shard's placement primitive on the same tile. */
    static void set(World shard, int pos, Block block){
        Tile tile = world.tiles.geti(pos);
        tile.setBlock(block);
        shard.buildings().setBlock(tile.x, tile.y, VANILLA.block(block.id), 0, 0, Cause.SERVER);
    }

    static void same(String where, World shard, PathService paths, int team, Map<PathCost, Integer> handles){
        int width = world.width();
        int[] vanilla = VanillaPaths.repack();
        for(int pos = 0; pos < vanilla.length; pos++){
            int mine = paths.packed(pos) & PathTile.VANILLA_BITS;
            if(mine != vanilla[pos]){
                fail(where + ": packed tile " + (pos % width) + "," + (pos / width) + " is " + Integer.toBinaryString(mine)
                    + ", vanilla " + Integer.toBinaryString(vanilla[pos]) + " (" + world.tiles.geti(pos) + ")");
            }
        }
        for(var entry : handles.entrySet()){
            String field = where + ": " + entry.getKey();
            int handle = entry.getValue();
            VanillaPaths.Built built = VanillaPaths.build(team, VanillaPaths.COSTS.get(entry.getKey()));
            assertArrayEquals(built.targets(), paths.get(handle).targets.toArray(), field + " targets");
            for(int pos = 0; pos < vanilla.length; pos++){
                int expected = built.refreshed()[pos], actual = paths.distance(handle, pos);
                if(expected != actual) fail(field + ": tile " + (pos % width) + "," + (pos / width) + " is " + actual + ", vanilla " + expected);
                Tile next = pathfinder.getTargetTile(world.tiles.geti(pos), built.field());
                if(next.array() != paths.next(handle, pos)){
                    fail(field + ": from " + (pos % width) + "," + (pos / width) + " vanilla steps to " + next.x + "," + next.y);
                }
            }
        }
    }
}
