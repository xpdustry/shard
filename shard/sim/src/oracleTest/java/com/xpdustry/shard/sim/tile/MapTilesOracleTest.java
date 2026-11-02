package com.xpdustry.shard.sim.tile;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.environment.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.world.*;
import mindustry.world.Tile;
import mindustry.world.meta.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: on every tile of the 18 default maps, Shard's tile predicates, darkness and proximity equal vanilla's
 * {@code Tile} after {@code host <map>}. The Shard world is loaded from vanilla's tiles by {@link VanillaMaps}, which
 * zeroes darkened tiles' {@code data} so Shard's {@code addDarkness} has to recompute it.
 */
class MapTilesOracleTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final int SHOWN = 12;
    static int proximityEntries, darkTiles;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @ParameterizedTest
    @ValueSource(strings = {"maze", "fortress", "labyrinth", "islands", "tendrils", "caldera", "wasteland", "shattered",
        "fork", "triad", "mudFlats", "moltenLake", "archipelago", "debrisField", "domain", "veins", "glacier", "passage"})
    void tilesMatchVanilla(String map){
        Vanilla.host(map);
        World shard = load();
        var mismatches = new Mismatches(map);
        TileGrid tiles = shard.tiles();
        Buildings buildings = shard.buildings();
        int centres = 0, neighbours = 0, dark = 0;
        for(Tile tile : world.tiles){
            int pos = tiles.pos(tile.x, tile.y);
            if(tile.staticDarkness() > 0) dark++;
            var at = new At(mismatches, tile);
            at.check("floor", tile.floorID(), tiles.floor(pos));
            at.check("overlay", tile.overlayID(), tiles.overlay(pos));
            at.check("block", tile.blockID(), tiles.block(pos));
            at.check("team", tile.team().id, tiles.team(pos) & 0xff);
            at.check("data", tile.data, tiles.data(pos));
            at.check("isDarkened", tile.block().isDarkened(tile), tiles.isDarkened(pos, accessible()));
            at.check("solid", tile.solid(), tiles.solid(pos));
            at.check("legSolid", tile.legSolid(), tiles.legSolid(pos));
            at.check("passable", tile.passable(), tiles.passable(pos));
            at.check("isDeep", tile.isDeep(), tiles.isDeep(pos));
            at.check("dangerous", tile.dangerous(), tiles.dangerous(pos));
            at.check("staticDarkness", tile.staticDarkness(), tiles.staticDarkness(pos));
            at.check("synthetic", tile.synthetic(), tiles.synthetic(pos));
            at.check("breakable", tile.breakable(), tiles.breakable(pos));
            at.check("isStatic", tile.block().isStatic(), tiles.isStatic(pos));
            at.check("isLiquid", tile.floor().isLiquid, tiles.isLiquid(pos));
            at.check("shouldSaveData", tile.shouldSaveData(), tiles.shouldSaveData(pos));
            at.check("drop", tile.drop() == null ? BlockTable.NONE : tile.drop().id, tiles.drop(pos));
            at.check("wallDrop", tile.wallDrop() == null ? BlockTable.NONE : tile.wallDrop().id, tiles.wallDrop(pos));
            at.check("drownTime", tile.floor().drownTime, tiles.drownTime(pos));
            at.check("speedMultiplier", tile.floor().speedMultiplier, tiles.speedMultiplier(pos));
            at.check("dragMultiplier", tile.floor().dragMultiplier, tiles.dragMultiplier(pos));
            at.check("status", tile.floor().status.id, tiles.status(pos));
            at.check("statusDuration", tile.floor().statusDuration, tiles.statusDuration(pos));
            at.check("damageTaken", tile.floor().damageTaken, tiles.damageTaken(pos));
            for(int a = 0; a < Attributes.COUNT; a++){
                Attribute attribute = Attribute.all[a];
                assertEquals(attribute.name, Attributes.name(a));
                at.check("attribute " + attribute.name, tile.floor().attributes.get(attribute), tiles.attribute(pos, a));
            }
            Building build = buildings.at(pos);
            at.check("build", tile.build != null, build != null);
            if(tile.build != null && build != null){
                at.check("isCenter", tile.isCenter(), build.tile() == pos);
                at.check("centre", tile.centerX() + "," + tile.centerY(), tiles.x(build.tile()) + "," + tiles.y(build.tile()));
                if(tile.isCenter()){
                    at.check("proximity", proximity(tile.build), proximity(tiles, build));
                    centres++;
                    neighbours += tile.build.proximity.size;
                }
            }
        }
        mismatches.assertNone();
        assertTrue(centres > 0, map + " has no building");
        proximityEntries += neighbours;
        darkTiles += dark;
    }

    /** Most default maps hold a lone core: proximity is checked on the few that have more, and by the placement gate. */
    @AfterAll
    static void theMapsCoverProximityAndDarkness(){
        assertTrue(proximityEntries > 0, "no map had a building next to another");
        assertTrue(darkTiles > 0, "no map had a dark tile");
    }

    static boolean accessible(){
        return VanillaMaps.accessible();
    }

    /** The current vanilla world, loaded into Shard as W1.7's msav loader will. */
    static World load(){
        return VanillaMaps.load(VanillaWorlds.of(BLOCKS, world.width(), world.height()));
    }

    static List<String> proximity(mindustry.gen.Building build){
        var out = new ArrayList<String>();
        for(var other : build.proximity) out.add(other.tile.x + "," + other.tile.y);
        return out;
    }

    static List<String> proximity(TileGrid tiles, Building build){
        var out = new ArrayList<String>();
        for(Building other : build.proximityList()) out.add(tiles.x(other.tile()) + "," + tiles.y(other.tile()));
        return out;
    }

    /** Mismatches per check, with the first few shown. */
    static final class Mismatches{
        final String scope;
        final Map<String, Integer> counts = new TreeMap<>();
        final List<String> shown = new ArrayList<>();

        Mismatches(String scope){
            this.scope = scope;
        }

        void add(String check, String detail){
            counts.merge(check, 1, Integer::sum);
            if(shown.size() < SHOWN) shown.add(detail);
        }

        void assertNone(){
            if(!counts.isEmpty()) fail(scope + ": mismatches " + counts + "\n  " + String.join("\n  ", shown));
        }
    }

    record At(Mismatches mismatches, Tile tile){
        void check(String name, Object vanilla, Object shard){
            if(!Objects.equals(normal(vanilla), normal(shard))){
                mismatches.add(name, name + " at " + tile.x + "," + tile.y + " (" + tile.floor().name + "/" + tile.overlay().name
                    + "/" + tile.block().name + "): vanilla " + vanilla + ", shard " + shard);
            }
        }

        static Object normal(Object value){
            return switch(value){
                case Byte b -> (int)b;
                case Short s -> (int)s;
                default -> value;
            };
        }
    }
}
