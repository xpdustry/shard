package com.xpdustry.shard.compat.v160;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.TileGrid;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.BundledMaps;
import mindustry.io.*;
import mindustry.maps.MapException;
import mindustry.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Shard hosting every map bundled in the vanilla jar ({@link MapLoader#loadMap}) against vanilla's {@code world.loadMap}
 * of the same file, both from a generator seeded with 0: after the generation filters, every tile has the same floor,
 * overlay, block, team and building rotation.
 */
class GenerationFiltersOracleTest{
    /**
     * Maps Shard refuses to host, by what the refusal says. Filters whose seeds come after draws the loader can't count
     * ({@link MapState#unknownDraws}) would be refused; no bundled map has one since {@code :wire} reads old payload
     * units. hidden/242 and littoralShipyard, refused for their data patches until W5.4, load with them now.
     */
    static final Map<String, String> REFUSED = Map.of();
    /**
     * Maps whose draws the loader can't count; only those with filters are refused. hidden/16, 30 and 191 left with the
     * old payload unit layouts {@code :wire} reads now; no bundled map's saved units carry anything.
     */
    static final Set<String> UNCOUNTED_DRAWS = Set.of();

    static List<String> maps(){
        return BundledMaps.all();
    }

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /** A loaded grid, per tile: floor, overlay and block ids, team id, and the building's rotation or 0. */
    record Grid(int width, int[] floor, int[] overlay, int[] block, int[] team, int[] rotation){
        static Grid vanilla(){
            int width = world.width(), size = width * world.height();
            var grid = new Grid(width, new int[size], new int[size], new int[size], new int[size], new int[size]);
            for(Tile tile : world.tiles){
                int i = tile.x + tile.y * width;
                grid.floor[i] = tile.floor().id;
                grid.overlay[i] = tile.overlay().id;
                grid.block[i] = tile.block().id;
                grid.team[i] = tile.team().id;
                grid.rotation[i] = tile.build == null ? 0 : tile.build.rotation;
            }
            return grid;
        }

        static Grid shard(World world){
            TileGrid tiles = world.tiles();
            int size = tiles.size();
            var grid = new Grid(tiles.width(), new int[size], new int[size], new int[size], new int[size], new int[size]);
            for(int i = 0; i < size; i++){
                grid.floor[i] = tiles.floor(i);
                grid.overlay[i] = tiles.overlay(i);
                grid.block[i] = tiles.block(i);
                grid.team[i] = tiles.team(i);
                Building building = world.buildings().at(i);
                grid.rotation[i] = building == null ? 0 : building.rotation;
            }
            return grid;
        }

        List<String> differences(Grid actual){
            var out = new ArrayList<String>();
            if(floor.length != actual.floor.length) return List.of("size " + floor.length + " vs " + actual.floor.length);
            for(int i = 0; i < floor.length; i++){
                if(floor[i] == actual.floor[i] && overlay[i] == actual.overlay[i] && block[i] == actual.block[i]
                    && team[i] == actual.team[i] && rotation[i] == actual.rotation[i]) continue;
                if(out.size() == 10){
                    out.add("...");
                    break;
                }
                out.add("(" + i % width + ", " + i / width + ") " + describe(i) + " vs " + actual.describe(i));
            }
            return out;
        }

        String describe(int i){
            return content.block(floor[i]).name + "/" + content.block(overlay[i]).name + "/" + content.block(block[i]).name
                + " team " + team[i] + " rotation " + rotation[i];
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void gridEqualsVanillasLoadMap(String path){
        Vanilla.reset();
        mindustry.maps.Map map = Vanilla.findMap(path);
        try{
            world.loadMap(map);
        }catch(MapException ignored){
            // a map without cores fails the check after loading; its tiles are loaded
        }
        Vanilla.runPosted();
        Grid expected = Grid.vanilla();

        SaveFile save = READER.read(BundledMaps.read(path));
        String refusal = REFUSED.get(path);
        if(refusal != null){
            var error = assertThrows(MsavException.class, () -> LOADER.loadMap(save, Worlds.config(0), LOADER.hostRules(save, Gamemode.SURVIVAL)));
            assertTrue(error.getMessage().contains(refusal), error.getMessage());
            return;
        }
        MapLoader.Loaded loaded = LOADER.loadMap(save, Worlds.config(0), LOADER.hostRules(save, Gamemode.SURVIVAL));
        assertEquals(List.of(), expected.differences(Grid.shard(loaded.world())), path);
    }

    /**
     * The loader's draws ({@link Building#create}, {@code ReadDraws}, the saved units): after vanilla's map load without
     * its filters, entities included, {@code Mathf.rand} is as many values past its seed as Shard's loaded world.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void loadDrawsMatchVanilla(String path){
        Vanilla.reset();
        mindustry.maps.Map map = Vanilla.findMap(path);
        SaveIO.load(map.file, world.new FilterContext(map){
            @Override
            public void applyFilters(){
                // the filters' own draws are GenerationFiltersOracleTest's grid's business
            }
        });
        int[] expected = {steps(arc.math.Mathf.rand.seed0, arc.math.Mathf.rand.seed1)};
        Vanilla.runPosted();

        SaveFile save = READER.read(BundledMaps.read(path));
        MapLoader.Loaded loaded;
        try{
            loaded = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save));
        }catch(MsavException e){
            assertTrue(REFUSED.containsKey(path) && e.getMessage().contains(REFUSED.get(path)), e.getMessage());
            return;
        }
        String unknown = loaded.state().unknownDraws;
        assertEquals(UNCOUNTED_DRAWS.contains(path), unknown != null, path + ": " + unknown);
        World shard = loaded.world();
        if(unknown == null) assertEquals(expected[0], steps(shard.rand().seed0, shard.rand().seed1), path);
    }

    /** How many values a generator seeded with 0 draws to reach this state. */
    static int steps(long seed0, long seed1){
        var rand = new Rand(0);
        for(int i = 0; i < 100_000; i++){
            if(rand.seed0 == seed0 && rand.seed1 == seed1) return i;
            rand.nextLong();
        }
        return -1;
    }
}
