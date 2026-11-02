package com.xpdustry.shard.compat.v160;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.BundledMaps;
import mindustry.gen.*;
import mindustry.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.lang.reflect.*;
import java.util.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP37): loading a map gives vanilla's power graphs. Shard loads each map the stream gate loads; vanilla loads
 * the same file as {@code World.loadMap} does. Compared: the entity id counter, {@code Groups.powerGraph} in order with
 * each updater's id and its graph's members, producers, consumers and batteries in order, and every powered
 * building's graph, links and stored power.
 */
class PowerMapsOracleTest{
    static int graphs;

    static List<String> maps(){
        return WorldStreamOracleTest.loadedMaps();
    }

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @AfterAll
    static void someMapsHavePower(){
        assertTrue(graphs > 100, "the maps hold power graphs: " + graphs);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void graphsMatchVanillasAfterLoading(String name){
        SaveFile save = READER.read(BundledMaps.read(name));
        World shard = LOADER.loadMap(save, Worlds.config(0), LOADER.hostRules(save, com.xpdustry.shard.sim.rules.Gamemode.SURVIVAL)).world();
        List<String> actual = shardGraphs(shard);

        Vanilla.reset();
        mindustry.maps.Map map = Vanilla.findMap(name);
        SaveIO.load(map.file, world.filterContext(map));
        Vanilla.runPosted();
        List<String> expected = vanillaGraphs();
        assertEquals(String.join("\n", expected), String.join("\n", actual), name);
        graphs += Groups.powerGraph.size();
    }

    static List<String> vanillaGraphs(){
        var out = new ArrayList<String>();
        out.add("next id " + lastId());
        Groups.powerGraph.each(updater -> {
            var graph = updater.graph();
            out.add("graph " + updater.id() + " all " + positions(graph.all) + " producers " + positions(graph.producers)
                + " consumers " + positions(graph.consumers) + " batteries " + positions(graph.batteries));
        });
        for(var tile : world.tiles){
            var build = tile.build;
            if(build == null || !tile.isCenter() || build.power == null) continue;
            out.add(build.block.name + " " + tile.x + "," + tile.y + " id " + build.id + " graph " + updaterId(build.power.graph)
                + " links " + Arrays.toString(build.power.links.toArray()) + " status " + build.power.status);
        }
        return out;
    }

    static List<String> shardGraphs(World shard){
        var out = new ArrayList<String>();
        out.add("next id " + shard.ids().peek());
        var list = PowerGraphs.of(shard).list();
        for(int i = 0; i < list.size(); i++){
            PowerGraph graph = list.get(i);
            out.add("graph " + graph.id() + " all " + positions(graph.size(), graph::member) + " producers "
                + positions(graph.producerCount(), graph::producer) + " consumers " + positions(graph.consumerCount(), graph::consumer)
                + " batteries " + positions(graph.batteryCount(), graph::battery));
        }
        var tiles = shard.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            Building build = shard.buildings().at(pos);
            if(build == null || build.tile() != pos || build.power == null) continue;
            out.add(build.block().name() + " " + build.tileX() + "," + build.tileY() + " id " + build.id() + " graph "
                + build.power.graph.id() + " links " + Arrays.toString(build.power.links.toArray()) + " status " + build.power.status);
        }
        return out;
    }

    static String positions(arc.struct.Seq<mindustry.gen.Building> members){
        var out = new StringJoiner(" ", "[", "]");
        for(var build : members) out.add(build.tile.x + "," + build.tile.y);
        return out.toString();
    }

    static String positions(int count, java.util.function.IntFunction<Building> member){
        var out = new StringJoiner(" ", "[", "]");
        for(int i = 0; i < count; i++) out.add(member.apply(i).tileX() + "," + member.apply(i).tileY());
        return out.toString();
    }

    static int updaterId(mindustry.world.blocks.power.PowerGraph graph){
        try{
            Field field = mindustry.world.blocks.power.PowerGraph.class.getDeclaredField("entity");
            field.setAccessible(true);
            var entity = (PowerGraphUpdater)field.get(graph);
            return entity == null ? -1 : entity.id;
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    static int lastId(){
        try{
            Field field = mindustry.entities.EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }
}
