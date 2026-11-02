package com.xpdustry.shard.compat.v160;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.rules.Gamemode;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.BundledMaps;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: the world stream holds the live world's units, not the map's saved ones. Each default map with saved units is
 * loaded as {@code host} loads it in vanilla and Shard; then, the same way in both, a dagger, a flare and a mega are
 * spawned, the first saved unit moves, and a unit is removed: the second saved one, or the dagger on a map with one
 * (vanilla's unit group swaps its last unit into the gap). The units are in the same order in both worlds; the entities
 * a vanilla client decodes from Shard's stream (ids, classes, every field but AI timers) equal those it decodes from
 * vanilla's, and so does everything else in it.
 */
class LiveUnitsStreamOracleTest{
    static List<String> maps(){
        return List.of("maps/default/archipelago.msav", "maps/default/debrisField.msav", "maps/default/moltenLake.msav");
    }

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void streamHoldsTheLiveUnits(String name){
        // vanilla: OracleWorlds.vanillaLoaded, edited before the stream
        Vanilla.reset();
        mindustry.maps.Map map = Vanilla.findMap(name);
        SaveIO.load(map.file, world.filterContext(map));
        Vanilla.runPosted();
        state.map = map;
        state.rules = map.applyRules(mindustry.game.Gamemode.survival);
        var saved = new ArrayList<mindustry.gen.Unit>();
        Groups.unit.each(saved::add);
        assertFalse(saved.isEmpty(), name + " has saved units");
        boolean removeSaved = saved.size() >= 2;
        var spawned = new ArrayList<mindustry.gen.Unit>();
        for(Spawn spawn : SPAWNS){
            mindustry.gen.Unit unit = content.unit(spawn.type()).create(mindustry.game.Team.get(spawn.team()));
            unit.set(spawn.x(), spawn.y());
            unit.rotation = spawn.rotation();
            unit.add();
            spawned.add(unit);
        }
        int moved = saved.get(0).id;
        saved.get(0).set(saved.get(0).x + 40f, saved.get(0).y - 24f);
        mindustry.gen.Unit gone = removeSaved ? saved.get(1) : spawned.get(0);
        int removed = gone.id;
        gone.remove();
        var expectedOrder = new ArrayList<Integer>();
        Groups.unit.each(unit -> expectedOrder.add(unit.id));
        Player player = Player.create();
        player.name = "oracle";
        player.team(state.rules.defaultTeam);
        byte[] expected = Golden.worldStream(player).raw();

        // Shard: OracleWorlds.shardHost, edited the same way
        SaveFile save = READER.read(BundledMaps.read(name));
        Rules rules = LOADER.hostRules(save, Gamemode.SURVIVAL);
        MapLoader.Loaded loaded = LOADER.loadMap(save, Worlds.config(0), rules);
        World shard = loaded.world();
        var shardSaved = new ArrayList<Unit>();
        shard.units().each(shardSaved::add);
        var shardSpawned = new ArrayList<Unit>();
        for(Spawn spawn : SPAWNS){
            Unit unit = Unit.create(shard, shard.content().unit(spawn.type()), spawn.team());
            unit.set(spawn.x(), spawn.y());
            unit.rotation = spawn.rotation();
            unit.add();
            shardSpawned.add(unit);
        }
        Unit first = shardSaved.get(0);
        first.set(first.x + 40f, first.y - 24f);
        (removeSaved ? shardSaved.get(1) : shardSpawned.get(0)).remove();
        var actualOrder = new ArrayList<Integer>();
        shard.units().each(unit -> actualOrder.add(unit.id()));
        assertEquals(expectedOrder, actualOrder, name + ": units in order");
        PlayerState playerState = PlayerState.fresh(shard.ids().next(), "oracle", rules.defaultTeam);
        WorldStreamSource source = PROJECTION.capture(shard, loaded.state(), playerState);
        byte[] actual = WorldStreamWriter.write(source);

        Decoded vanilla = decode(expected), decoded = decode(actual);
        var ids = new ArrayList<Integer>();
        decoded.view().entities().forEach(entity -> ids.add(entity.id()));
        assertTrue(ids.contains(moved) && !ids.contains(removed) && ids.contains(spawned.getLast().id),
            name + ": spawned units in, the removed one out: " + ids);
        assertEquals(List.of(), differences(vanilla, decoded), name);
    }

    record Spawn(String type, int team, float x, float y, float rotation){}

    static final List<Spawn> SPAWNS = List.of(new Spawn("dagger", 1, 100f, 120f, 0f), new Spawn("flare", 2, 140f, 60f, 45f),
        new Spawn("mega", 1, 200f, 80f, 270f));
}
