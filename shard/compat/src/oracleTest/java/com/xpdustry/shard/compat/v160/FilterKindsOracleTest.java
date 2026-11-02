package com.xpdustry.shard.compat.v160;

import arc.func.*;
import arc.struct.*;
import com.xpdustry.shard.compat.v160.GenerationFiltersOracleTest.Grid;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.BundledMaps;
import mindustry.content.*;
import mindustry.io.*;
import mindustry.maps.MapException;
import mindustry.maps.filters.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;
import java.util.stream.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every filter kind, including those no bundled map uses: a map hosted with one filter, written to its
 * {@code genfilters} tag by vanilla's {@code JsonIO}, has the same grid on both engines (and, for random items, the same
 * storage contents).
 */
class FilterKindsOracleTest{
    /** A survival map with enemy spawns, and one with containers for {@link RandomItemFilter}. */
    static final List<String> MAPS = List.of("maze", "maps/serpulo/groundZero.msav");
    static final String STORAGE_MAP = "maps/serpulo/hidden/0.msav";

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /** Each filter type with its defaults, then with other options. */
    static Stream<Arguments> filters(){
        var cases = new LinkedHashMap<String, Prov<GenerateFilter>>();
        for(Prov<GenerateFilter> type : mindustry.maps.Maps.allFilterTypes){
            if(type.get() instanceof mindustry.maps.filters.LogicFilter) continue;
            cases.put(type.get().getClass().getSimpleName(), type);
        }
        cases.put("NoiseFilter on stone", () -> {
            var f = new mindustry.maps.filters.NoiseFilter();
            f.target = Blocks.stone;
            f.floor = Blocks.darksand;
            f.block = Blocks.air;
            f.tilt = 0.4f;
            f.octaves = 5;
            return f;
        });
        cases.put("TerrainFilter with a floor", () -> {
            var f = new mindustry.maps.filters.TerrainFilter();
            f.floor = Blocks.snow;
            f.threshold = 0.6f;
            f.circleScl = 0.5f;
            return f;
        });
        cases.put("DistortFilter strong", () -> {
            var f = new mindustry.maps.filters.DistortFilter();
            f.scl = 12;
            f.mag = 20;
            return f;
        });
        cases.put("RiverNoiseFilter wide", () -> {
            var f = new mindustry.maps.filters.RiverNoiseFilter();
            f.threshold = -0.2f;
            f.threshold2 = 0.3f;
            f.octaves = 3;
            return f;
        });
        cases.put("OreFilter titanium on sand", () -> {
            var f = new mindustry.maps.filters.OreFilter();
            f.ore = Blocks.oreTitanium;
            f.target = Blocks.sand;
            f.threshold = 0.6f;
            return f;
        });
        cases.put("MedianFilter wide", () -> {
            var f = new mindustry.maps.filters.MedianFilter();
            f.radius = 4;
            f.percentile = 0.2f;
            return f;
        });
        cases.put("OreMedianFilter wide", () -> {
            var f = new mindustry.maps.filters.OreMedianFilter();
            f.radius = 5;
            f.percentile = 0.7f;
            return f;
        });
        cases.put("BlendFilter wall", () -> {
            var f = new mindustry.maps.filters.BlendFilter();
            f.block = Blocks.stoneWall;
            f.floor = Blocks.basalt;
            f.ignore = Blocks.sand;
            f.radius = 3.5f;
            return f;
        });
        cases.put("MirrorFilter rotated", () -> {
            var f = new mindustry.maps.filters.MirrorFilter();
            f.angle = 135;
            f.rotate = true;
            f.axisX = 0.3f;
            return f;
        });
        cases.put("MirrorFilter vertical", () -> {
            var f = new mindustry.maps.filters.MirrorFilter();
            f.angle = 90;
            return f;
        });
        cases.put("ClearFilter walls", () -> {
            var f = new mindustry.maps.filters.ClearFilter();
            f.target = Blocks.stoneWall;
            f.replace = Blocks.boulder;
            return f;
        });
        cases.put("SpawnPathFilter with walls", () -> {
            var f = new mindustry.maps.filters.SpawnPathFilter();
            f.radius = 5;
            f.block = Blocks.copperWall;
            return f;
        });
        cases.put("LogicFilter without code", mindustry.maps.filters.LogicFilter::new);
        return MAPS.stream().flatMap(map -> cases.entrySet().stream().map(entry -> Arguments.of(map, entry.getKey(), entry.getValue())));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("filters")
    void gridEqualsVanillas(String map, String name, Prov<GenerateFilter> filter){
        String json = JsonIO.write(Seq.with(filter.get()));
        Grid expected = vanillaGrid(map, json);
        World shard = shardHost(map, json);
        assertEquals(List.of(), expected.differences(Grid.shard(shard)), map + " with " + json);
    }

    @Test
    void randomItemsAreVanillas(){
        var filter = new mindustry.maps.filters.RandomItemFilter();
        filter.drops = Seq.with(new mindustry.type.ItemStack(Items.copper, 900), new mindustry.type.ItemStack(Items.silicon, 40), new mindustry.type.ItemStack(Items.thorium, 7));
        filter.chance = 0.6f;
        String json = JsonIO.write(Seq.with(filter));

        // what the filter leaves, after endMapLoad links storage next to cores to their items
        Vanilla.reset();
        mindustry.maps.Map map = Vanilla.findMap(STORAGE_MAP);
        String old = map.tags.get("genfilters");
        map.tags.put("genfilters", json);
        var expected = new TreeMap<Integer, List<Integer>>();
        try{
            SaveIO.load(map.file, world.filterContext(map));
        }finally{
            if(old == null) map.tags.remove("genfilters");
            else map.tags.put("genfilters", old);
        }
        for(Tile tile : world.tiles){
            if(tile.build != null && tile.isCenter() && tile.block() instanceof StorageBlock && !(tile.block() instanceof CoreBlock)){
                var items = new ArrayList<Integer>();
                for(Item item : content.items()) items.add(tile.build.items.get(item));
                expected.put(tile.pos(), items);
            }
        }
        assertFalse(expected.isEmpty(), "the map has containers");

        World shard = shardHost(STORAGE_MAP, json);
        var actual = new TreeMap<Integer, List<Integer>>();
        for(int pos : expected.keySet()){
            Building building = shard.buildings().at(pos >>> 16, pos & 0xffff);
            var items = new ArrayList<Integer>();
            for(int amount : building.items.items) items.add(amount);
            actual.put(pos, items);
        }
        assertEquals(expected, actual);
    }

    /** Logic scripts: name, code, loop. */
    static Stream<Arguments> scripts(){
        var cases = new LinkedHashMap<String, String[]>();
        cases.put("one floor", new String[]{"setblock floor @metal-floor-damaged 1 1 @derelict 0", "false"});
        cases.put("an ore row", new String[]{"""
            set x 0
            row:
            setblock ore @ore-copper x 3 @derelict 0
            op add x x 1
            jump row lessThan x @mapw
            """, "false"});
        cases.put("stone and ores to darksand", new String[]{"""
            set y 0
            row:
            set x 0
            column:
            getblock floor f x y
            jump change equal f @stone
            getblock ore o x y
            jump skip equal o @air
            change:
            setblock floor @darksand x y @derelict 0
            skip:
            op add x x 1
            jump column lessThan x @mapw
            op add y y 1
            jump row lessThan y @maph
            """, "false"});
        cases.put("blocks and teams", new String[]{"""
            setblock block @copper-wall 10 10 @sharded 0
            setblock block @router 12 12 @crux 2
            setblock block @conveyor 13 12 @blue 7
            setblock block @thorium-wall-large 15 15 null 0
            setblock block @air 10 10 @derelict 0
            setblock block @stone 20 20 @sharded 0
            """, "false"});
        cases.put("every building removed", new String[]{"""
            set y 0
            row:
            set x 0
            column:
            getblock building b x y
            jump skip strictEqual b null
            setblock block @air x y @derelict 0
            skip:
            op add x x 1
            jump column lessThan x @mapw
            op add y y 1
            jump row lessThan y @maph
            """, "false"});
        cases.put("flags", new String[]{"""
            setflag "green" true
            getflag green "green"
            jump end notEqual green true
            setblock floor @grass 2 2 @derelict 0
            getflag red "red"
            jump end equal red true
            setblock ore @ore-lead 3 3 @derelict 0
            end:
            stop
            """, "false"});
        cases.put("a looping counter", new String[]{"""
            op add i i 1
            op mod x i @mapw
            setblock floor @ice x 4 @derelict 0
            """, "true"});
        cases.put("a script that ends itself", new String[]{"""
            setblock floor @snow 5 5 @derelict 0
            end
            setblock floor @snow 6 6 @derelict 0
            """, "true"});
        cases.put("the file's wave and clock", new String[]{"""
            op add x @waveNumber 2
            op mod y @waveTime 10
            op floor y y
            op mod z @tick 7
            op add z z 12
            setblock floor @metal-floor-damaged x y @derelict 0
            setblock floor @metal-floor-damaged z 12 @derelict 0
            """, "false"});
        cases.put("malformed code", new String[]{"setblock floor @sand 1 1 @derelict 0\nprint \"unclosed", "false"});
        return MAPS.stream().flatMap(map -> cases.entrySet().stream().map(entry -> Arguments.of(map, entry.getKey(), entry.getValue()[0],
            Boolean.parseBoolean(entry.getValue()[1]))));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("scripts")
    void logicScriptsAreVanillas(String map, String name, String code, boolean loop){
        var filter = new mindustry.maps.filters.LogicFilter();
        filter.code = code;
        filter.loop = loop;
        String json = JsonIO.write(Seq.with(filter));
        Grid expected = vanillaGrid(map, json);
        // the script did something, unless it can't be parsed
        Grid unchanged = vanillaGrid(map, "[]");
        assertEquals(name.equals("malformed code"), expected.differences(unchanged).isEmpty(), map + " with " + name);
        World shard = shardHost(map, json);
        assertEquals(List.of(), expected.differences(Grid.shard(shard)), map + " with " + name);
    }

    /** W5.7: the payload statements, refused at map load until they were ported, run and the map loads as vanilla's. */
    @Test
    void payloadStatementsRunAtMapLoad(){
        var filter = new mindustry.maps.filters.LogicFilter();
        filter.code = "spawn @mega 10 10 0 @sharded u\nsetprop @payloadType u @dagger\nucontrol payDrop 0 0 0 0 0\nsetblock block @router 3 3 @sharded 0";
        String json = JsonIO.write(Seq.with(filter));
        Grid expected = vanillaGrid("maze", json);
        World shard = shardHost("maze", json);
        assertEquals(List.of(), expected.differences(Grid.shard(shard)), "maze with a payload script");
    }

    /** {@code world.loadMap} of {@code map} with its {@code genfilters} tag set to {@code json}. */
    static Grid vanillaGrid(String path, String json){
        Vanilla.reset();
        mindustry.maps.Map map = Vanilla.findMap(path);
        String old = map.tags.get("genfilters");
        map.tags.put("genfilters", json);
        try{
            // World.loadMap logs a failed load and returns
            List<String> errors = Vanilla.logged(() -> {
                try{
                    world.loadMap(map);
                }catch(MapException ignored){
                    // a map without cores fails the check after loading; its tiles are loaded
                }
            });
            assertEquals(List.of(), errors, path + " with " + json);
        }finally{
            if(old == null) map.tags.remove("genfilters");
            else map.tags.put("genfilters", old);
        }
        Vanilla.runPosted();
        return Grid.vanilla();
    }

    static World shardHost(String path, String json){
        SaveFile save = READER.read(BundledMaps.read(path));
        save.meta().put("genfilters", json);
        return LOADER.loadMap(save, Worlds.config(0), LOADER.hostRules(save, Gamemode.SURVIVAL)).world();
    }
}
