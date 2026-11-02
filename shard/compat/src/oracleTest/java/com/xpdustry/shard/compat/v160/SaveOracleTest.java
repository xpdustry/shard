package com.xpdustry.shard.compat.v160;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.PlayerState;
import com.xpdustry.shard.sim.rules.Gamemode;
import com.xpdustry.shard.sim.unit.Payload;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.oracle.Oracle.*;
import com.xpdustry.shard.testkit.BundledMaps;
import mindustry.gen.*;
import mindustry.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A v13 save written by Shard (a hosted default map) loads in vanilla, to the same world as a save vanilla writes of
 * the same hosted map: tiles, buildings, entities, wave state and rules. So does a save of a world changed after it
 * loaded: units picked up, moved, removed and spawned, core items added.
 */
class SaveOracleTest{
    static List<String> maps(){
        return BundledMaps.DEFAULT;
    }

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /** The bundled maps with saved units. */
    static List<String> mapsWithUnits(){
        return SavedUnitsOracleTest.maps();
    }

    record Loaded(Tiles tiles, List<BuildingView> buildings, List<EntityView> entities, int wave, float wavetime, double tick, String rules){
        static Loaded current(){
            var entities = new ArrayList<EntityView>();
            for(Entityc entity : Groups.all){
                if(!(entity instanceof Building) && entity.serialize()) entities.add(Oracle.entity(entity));
            }
            return new Loaded(Tiles.current(), Oracle.buildings(), entities, state.wave, state.wavetime, state.tick, JsonIO.write(state.rules));
        }

        /** Each carrier's units, with the class each was loaded as. */
        static List<String> carried(){
            var out = new ArrayList<String>();
            for(mindustry.gen.Unit unit : Groups.unit){
                if(!(unit instanceof Payloadc carrier)) continue;
                for(mindustry.world.blocks.payloads.Payload payload : carrier.payloads()){
                    if(payload instanceof mindustry.world.blocks.payloads.UnitPayload carriedUnit){
                        out.add(unit.id + " carries " + carriedUnit.unit.id + " " + carriedUnit.unit.type.name + " as " + carriedUnit.unit.getClass().getSimpleName()
                            + " class " + carriedUnit.unit.classId() + " at " + carriedUnit.unit.x + "," + carriedUnit.unit.y);
                    }
                }
            }
            return out;
        }
    }

    /**
     * Both load the map as {@code World.loadMap} does, then change it alike: a new mega picks up the first saved unit
     * (keeping the class it was saved with), the second, if any, moves, the third, if any, is removed, a flare spawns
     * and the default team's first core gets items. Shard's save of that world, read by vanilla, equals vanilla's own save read back.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("mapsWithUnits")
    void aWorldChangedAfterLoadingSavesAsVanillas(String name) throws IOException{
        Vanilla.reset();
        mindustry.maps.Map map = Vanilla.findMap(name);
        SaveIO.load(map.file, world.filterContext(map));
        Vanilla.runPosted();
        state.map = map;
        state.rules = map.applyRules(mindustry.game.Gamemode.survival);
        SaveFile save = READER.read(BundledMaps.read(name));
        var rules = LOADER.hostRules(save, Gamemode.SURVIVAL);
        MapLoader.Loaded loaded = LOADER.loadMap(save, Worlds.config(0), rules);
        var shard = loaded.world();

        var vanillaUnits = Groups.unit.copy(new arc.struct.Seq<>()).sort(arc.util.Structs.comparingInt(mindustry.gen.Unit::id));
        var shardUnits = new ArrayList<com.xpdustry.shard.sim.unit.Unit>();
        for(int i = 0; i < shard.units().size(); i++) shardUnits.add(shard.units().get(i));
        shardUnits.sort(Comparator.comparingInt(com.xpdustry.shard.sim.unit.Unit::id));
        assertEquals(vanillaUnits.size, shardUnits.size(), name + ": saved units");
        for(int i = 0; i < vanillaUnits.size; i++) assertEquals(vanillaUnits.get(i).id, shardUnits.get(i).id(), name + ": unit " + i);

        // a mega picks up the first unit
        mindustry.gen.Unit picked = vanillaUnits.get(0);
        var mega = mindustry.content.UnitTypes.mega.create(picked.team);
        mega.set(picked.x + 8f, picked.y);
        mega.add();
        picked.remove();
        ((Payloadc)mega).addPayload(new mindustry.world.blocks.payloads.UnitPayload(picked));
        var shardPicked = shardUnits.get(0);
        var shardMega = com.xpdustry.shard.sim.unit.Unit.create(shard, shard.content().unit("mega"), shardPicked.team);
        assertEquals(mega.id, shardMega.id(), name + ": the mega's id");
        shardMega.set(shardPicked.x + 8f, shardPicked.y);
        shardMega.add();
        shardPicked.remove();
        shardMega.payloads.add(new Payload.UnitPayload(shardPicked));
        // the second moves, the third goes
        if(vanillaUnits.size > 1){
            vanillaUnits.get(1).set(vanillaUnits.get(1).x + 16f, vanillaUnits.get(1).y + 8f);
            shardUnits.get(1).set(shardUnits.get(1).x + 16f, shardUnits.get(1).y + 8f);
        }
        if(vanillaUnits.size > 2){
            vanillaUnits.get(2).remove();
            shardUnits.get(2).remove();
        }
        // a flare spawns
        var flare = mindustry.content.UnitTypes.flare.create(mindustry.game.Team.crux);
        flare.set(40f, 48f);
        flare.add();
        var shardFlare = com.xpdustry.shard.sim.unit.Unit.create(shard, shard.content().unit("flare"), mindustry.game.Team.crux.id);
        shardFlare.set(40f, 48f);
        shardFlare.add();
        // the first core gets items
        var core = state.rules.defaultTeam.core();
        if(core != null){
            core.items.add(mindustry.content.Items.copper, 123);
            shard.buildings().at(core.tile.x, core.tile.y).items.add(shard.content().item("copper").id(), 123);
        }
        Vanilla.runPosted();

        // Shard's save, read by vanilla
        var source = PROJECTION.capture(loaded.world(), loaded.state(), PlayerState.fresh(-1, "oracle", rules.defaultTeam));
        byte[] file = MsavWriter.write(MsavWriter.meta(source, loaded.state().tags, rules.defaultTeam,
            MsavWriter.noCores(shard, rules.defaultTeam), 0L), source);
        // vanilla's own save of its world, read back
        var vanillaSave = new ByteArrayOutputStream();
        SaveIO.write(vanillaSave, new SaveOptions());

        Vanilla.reset();
        SaveIO.load(new InflaterInputStream(new ByteArrayInputStream(file)), world.context);
        Vanilla.runPosted();
        Loaded actual = Loaded.current();
        List<String> actualCarried = Loaded.carried();
        Vanilla.reset();
        SaveIO.load(new ByteArrayInputStream(vanillaSave.toByteArray()), world.context);
        Vanilla.runPosted();
        Loaded expected = Loaded.current();
        List<String> expectedCarried = Loaded.carried();
        assertFalse(expectedCarried.isEmpty(), name + ": vanilla's mega carries the unit");

        var differences = new ArrayList<String>();
        differences.addAll(tileDifferences(expected.tiles(), actual.tiles(), i -> true));
        differences.addAll(listDifferences("building", expected.buildings(), actual.buildings()));
        differences.addAll(listDifferences("entity", expected.entities(), actual.entities()));
        check(differences, "carried", expectedCarried, actualCarried);
        check(differences, "tick", expected.tick(), actual.tick());
        differences.forEach(line -> System.out.println(name + ": " + line));
        assertEquals(List.of(), differences, name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void shardsV13SaveLoadsInVanilla(String name) throws IOException{
        Hosted shard = shardHost(name);
        byte[] file = MsavWriter.write(MsavWriter.meta(shard.source(), shard.loaded().state().tags, shard.rules().defaultTeam,
            MsavWriter.noCores(shard.loaded().world(), shard.rules().defaultTeam), 0L), shard.source());

        Vanilla.reset();
        assertTrue(SaveIO.isSaveValid(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(file)))), name);
        var header = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(file)));
        SaveIO.readHeader(header);
        assertEquals(13, header.readInt(), name);
        SaveMeta meta = SaveIO.getMeta(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(file))));
        assertEquals(160, meta.build, name);
        assertEquals(shard.loaded().state().tags.get("name"), meta.tags.get("mapname"), name);
        SaveIO.load(new InflaterInputStream(new ByteArrayInputStream(file)), world.context);
        Vanilla.runPosted();
        Loaded actual = Loaded.current();

        // vanilla's own save of the same world (the map loaded as World.loadMap does, with its host rules), loaded back
        Vanilla.reset();
        mindustry.maps.Map map = Vanilla.findMap(name);
        SaveIO.load(map.file, world.filterContext(map));
        Vanilla.runPosted();
        state.map = map;
        state.rules = map.applyRules(mindustry.game.Gamemode.survival);
        var vanillaSave = new ByteArrayOutputStream();
        SaveIO.write(vanillaSave, new SaveOptions());
        Vanilla.reset();
        SaveIO.load(new ByteArrayInputStream(vanillaSave.toByteArray()), world.context);
        Vanilla.runPosted();
        Loaded expected = Loaded.current();

        var differences = new ArrayList<String>();
        differences.addAll(tileDifferences(expected.tiles(), actual.tiles(), i -> true));
        differences.addAll(listDifferences("building", expected.buildings(), actual.buildings()));
        differences.addAll(listDifferences("entity", expected.entities(), actual.entities()));
        check(differences, "wave", expected.wave(), actual.wave());
        check(differences, "wavetime", expected.wavetime(), actual.wavetime());
        check(differences, "tick", expected.tick(), actual.tick());
        mapDifferences(differences, "rules", canonical(Oracle.parseArcJson(expected.rules())), canonical(Oracle.parseArcJson(actual.rules())));
        differences.forEach(line -> System.out.println(name + ": " + line));
        assertEquals(List.of(), differences, name);
    }
}
