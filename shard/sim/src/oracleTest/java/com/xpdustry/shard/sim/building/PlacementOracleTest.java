package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.Tile;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: random {@code setBlock}s on an empty map give vanilla's tiles, linked tiles, proximity order, update order,
 * entity ids and power graphs. Vanilla runs {@code Tile.setBlock} itself, with the oracle's ordered proximity sets.
 */
class PlacementOracleTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final int SIZE = 40;
    /**
     * Sizes 1 to 4, buildings with and without updates, building-less blocks, and powered blocks, whose graphs (each an
     * entity with its own id) merge through contact and split when a member goes, also across teams.
     */
    static final String[] PLACED = {"air", "air", "copper-wall", "copper-wall-large", "titanium-wall-large",
        "thorium-wall-large", "scrap-wall-huge", "scrap-wall-gigantic", "router", "sorter", "junction", "conveyor",
        "titanium-conveyor", "overflow-gate", "container", "vault", "liquid-router", "liquid-tank", "mechanical-drill",
        "pneumatic-drill", "stone-wall", "boulder", "sand-boulder", "shrubs", "power-node", "power-node-large",
        "battery", "battery-large", "illuminator", "combustion-generator", "solar-panel", "steam-generator",
        "thorium-reactor", "impact-reactor", "diode", "plastanium-wall"};

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void randomPlacementsMatchVanilla(){
        Sim.empty(SIZE, SIZE);
        World shard = VanillaWorlds.of(SIZE, SIZE, "stone");
        var rand = new Rand(160_7);
        Integer idOffset = null;
        for(int step = 0; step < 1500; step++){
            String name = PLACED[rand.random(PLACED.length - 1)];
            int x = rand.random(SIZE - 1), y = rand.random(SIZE - 1), team = rand.random(1, 3), rotation = rand.random(3);
            // aim at an existing building's linked tile now and then
            if(rand.chance(0.2) && shard.buildings().count() > 0){
                Building target = shard.buildings().get(rand.random(shard.buildings().indexLimit() - 1));
                if(target != null){
                    int size = target.block().size(), offset = Footprint.offset(size);
                    x = shard.tiles().x(target.tile()) + offset + rand.random(size - 1);
                    y = shard.tiles().y(target.tile()) + offset + rand.random(size - 1);
                }
            }
            if(!shard.tiles().inBounds(x, y)) continue; // a footprint cut by the map edge
            String op = step + ": " + name + " at " + x + "," + y + " team " + team;

            world.tile(x, y).setBlock(content.block(name), Team.get(team), rotation);
            Vanilla.runPosted();
            shard.buildings().setBlock(x, y, BLOCKS.block(name), team, rotation, Cause.SERVER);

            compareTiles(shard, op);
            idOffset = compareBuildings(shard, op, idOffset);
        }
        assertTrue(shard.buildings().count() > 30, "the map should end up busy: " + shard.buildings().count());
    }

    static void compareTiles(World shard, String op){
        TileGrid tiles = shard.tiles();
        for(Tile tile : world.tiles){
            int pos = tiles.pos(tile.x, tile.y);
            String where = op + ", tile " + tile.x + "," + tile.y;
            assertEquals(tile.blockID(), tiles.block(pos), where);
            assertEquals(tile.team().id, tiles.team(pos) & 0xff, where);
            Building build = shard.buildings().at(pos);
            assertEquals(tile.build != null, build != null, where + ": building");
            if(build != null){
                assertEquals(tile.build.tile.pos(), tiles.toWire(build.tile()), where + ": linked to");
            }
            assertEquals(tile.solid(), tiles.solid(pos), where + ": solid");
        }
    }

    /** Proximity of every building, the update order and the entity ids, which must keep one offset from vanilla's. */
    static Integer compareBuildings(World shard, String op, Integer idOffset){
        TileGrid tiles = shard.tiles();
        for(Tile tile : world.tiles){
            if(tile.build == null || !tile.isCenter()) continue;
            Building build = shard.buildings().at(tiles.pos(tile.x, tile.y));
            var expected = new ArrayList<Integer>();
            for(var other : tile.build.proximity) expected.add(other.tile.pos());
            var actual = new ArrayList<Integer>();
            for(Building other : build.proximityList()) actual.add(tiles.toWire(other.tile()));
            assertEquals(expected, actual, op + ": proximity of " + tile.block().name + " at " + tile.x + "," + tile.y);
            int offset = tile.build.id - build.id();
            if(idOffset == null) idOffset = offset;
            assertEquals(idOffset, offset, op + ": entity id of " + tile.block().name + " at " + tile.x + "," + tile.y
                + " (vanilla " + tile.build.id + ", shard " + build.id() + ")");
        }
        var expected = new ArrayList<Integer>();
        Groups.build.each(b -> expected.add(b.tile.pos()));
        var actual = new ArrayList<Integer>();
        shard.builds().each(b -> actual.add(tiles.toWire(b.tile())));
        assertEquals(expected, actual, op + ": update order");
        comparePower(shard, op, idOffset == null ? 0 : idOffset);
        return idOffset;
    }

    /** {@code Groups.powerGraph}'s order and ids, and each graph's members in order. */
    static void comparePower(World shard, String op, int idOffset){
        var expected = new ArrayList<String>();
        Groups.powerGraph.each(updater -> expected.add((updater.id() - idOffset) + " " + members(updater.graph().all)));
        var actual = new ArrayList<String>();
        var graphs = com.xpdustry.shard.sim.blocks.power.PowerGraphs.of(shard).list();
        for(int i = 0; i < graphs.size(); i++){
            var graph = graphs.get(i);
            var members = new StringJoiner(" ", "[", "]");
            for(int m = 0; m < graph.size(); m++) members.add(String.valueOf(shard.tiles().toWire(graph.member(m).tile())));
            actual.add(graph.id() + " " + members);
        }
        assertEquals(expected, actual, op + ": power graphs");
    }

    static String members(arc.struct.Seq<mindustry.gen.Building> all){
        var out = new StringJoiner(" ", "[", "]");
        for(var build : all) out.add(String.valueOf(build.tile.pos()));
        return out.toString();
    }
}
