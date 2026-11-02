package com.xpdustry.shard.compat.v160;

import arc.struct.*;
import com.xpdustry.shard.compat.v160.filters.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.testkit.BundledMaps;
import mindustry.type.*;
import mindustry.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MapFilters#read} against vanilla's {@code maps.readFilters} on every bundled map's {@code genfilters} tag and
 * on hand-made edge cases: the same filter classes in the same order, with the same option values. Where Shard
 * refuses the JSON, vanilla reads something its map load then fails on.
 */
class FilterJsonOracleTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    static Stream<String> tags(){
        var tags = new TreeSet<String>();
        for(String path : BundledMaps.all()){
            tags.add(READER.read(BundledMaps.read(path)).meta().getOrDefault("genfilters", ""));
        }
        tags.addAll(List.of(
            "", "{}", "[]", "garbage", "[", "{class:noise}", "{\"size\":0}", "{size:2}", "{ordered:false,x:1}",
            "[{class:noise}]",
            "[{\"class\":\"noise\",\"scl\":\"12f\",\"octaves\":\"5\",\"tilt\":-1.5}]",
            "[{class:io.anuke.mindustry.maps.filters.OreFilter,ore:ore-lead,target:sand}]",
            "[{class:mindustry.maps.filters.ScatterFilter,block:boulder,floor:not-a-block,flooronto:grass}]",
            "[{class:mirror,rotate:\"true\",angle:\"30\"},{class:mirror,rotate:yes}]",
            "[{class:clear,target:\"\"},{class:clear,target:null}]",
            "[{class:mindustry.maps.filters.RandomItemFilter,chance:0.9,drops:[{item:silicon,amount:5},{item:nope,amount:2}]}]",
            "[{class:logic,code:\"print 1\",loop:true}]",
            "[{class:spawnPath,radius:8,block:stone-wall},{class:coreSpawn,amount:2},{class:enemySpawn,amount:0}]",
            "[{class:terrain,unknownField:1},{class:distort,seed:12},{class:blend},{class:median},{class:oreMedian}]",
            "[{class:unknownFilter}]",
            "[{class:mindustry.maps.filters.Nope}]",
            "[{scl:1}]",
            "[1]",
            "null"
        ));
        return tags.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tags")
    void readsAsVanilla(String json){
        Seq<?> expected = maps.readFilters(json);
        boolean vanillaFails = expected == null || expected.contains(e -> !(e instanceof mindustry.maps.filters.GenerateFilter));
        List<GenerateFilter> actual;
        try{
            actual = MapFilters.read(json, Worlds.EPOCH);
        }catch(MsavException e){
            assertTrue(vanillaFails, json + ": Shard refuses (" + e.getMessage() + ") but vanilla reads " + expected);
            return;
        }
        assertFalse(vanillaFails, json + ": vanilla reads " + expected + ", which its load fails on");
        assertEquals(describe(expected), actual.stream().map(FilterJsonOracleTest::describe).toList(), json);
    }

    static List<String> describe(Seq<?> filters){
        var out = new ArrayList<String>();
        for(Object filter : filters) out.add(describe(filter, filter.getClass().getSimpleName()));
        return out;
    }

    /** A vanilla filter's fields, named as Shard's record components. */
    static String describe(Object filter, String name){
        var record = recordOf(name);
        var sb = new StringBuilder(name);
        for(RecordComponent component : record.getRecordComponents()){
            try{
                Field field = filter.getClass().getField(component.getName());
                sb.append(' ').append(component.getName()).append('=').append(value(field.get(filter)));
            }catch(ReflectiveOperationException e){
                throw new AssertionError(name + "." + component.getName(), e);
            }
        }
        return sb.toString();
    }

    static String describe(GenerateFilter filter){
        var sb = new StringBuilder(filter.getClass().getSimpleName());
        for(RecordComponent component : filter.getClass().getRecordComponents()){
            try{
                sb.append(' ').append(component.getName()).append('=').append(value(component.getAccessor().invoke(filter)));
            }catch(ReflectiveOperationException e){
                throw new AssertionError(e);
            }
        }
        return sb.toString();
    }

    static Class<?> recordOf(String name){
        try{
            return Class.forName("com.xpdustry.shard.compat.v160.filters." + name);
        }catch(ClassNotFoundException e){
            throw new AssertionError("no Shard filter " + name, e);
        }
    }

    static String value(Object value){
        return switch(value){
            case null -> "null";
            case Block block -> block.name;
            case ContentDef def -> def.name();
            case Seq<?> drops -> {
                var names = new ArrayList<String>();
                for(Object stack : drops) names.add(((ItemStack)stack).item.name + "x" + ((ItemStack)stack).amount);
                yield names.toString();
            }
            case List<?> drops -> drops.stream().map(d -> {
                var drop = (RandomItemFilter.Drop)d;
                return drop.item().name() + "x" + drop.amount();
            }).collect(Collectors.joining(", ", "[", "]"));
            default -> value.toString();
        };
    }
}
