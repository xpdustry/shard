package com.xpdustry.shard.compat.v160;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.BundledMaps;
import com.xpdustry.shard.wire.v160_7.*;
import mindustry.gen.*;
import mindustry.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: on every bundled map with saved units, and every vanilla-written old save of W2.5's samples
 * ({@code wire/src/oracleTest/resources/saves}: old revisions, carriers carrying units), loading it ({@code SaveIO.load}
 * with the map's filters, the file's own rules) brings the same units to life in the same order as vanilla's
 * {@code readWorldEntities}, through {@link UnitSink}: entity id, type, team, position, rotation, health, elevation,
 * whether a core spawned it and the units it carries (their ids and types); then the next entity id and the number of
 * values drawn are the same, and so is the save body each live unit writes ({@code write()}) at its class's current
 * revision, carried units included. A unit spawned after the load takes the same id and draws the same. Old saves load
 * whole, payload buildings with base versions 0 and 1 included; a unit carrying a building stays bytes (carried
 * buildings aren't ported) and is left out on both sides, its ids and draws still counted. Vanilla keeps
 * the class a unit was saved with (a legacy class such as {@code UnitEntityLegacyPoly} too), and so does Shard's
 * projector ({@code MapState.classes}), for carried units too.
 */
class SavedUnitsOracleTest{
    /** The bundled maps with unit chunks, read with Shard's reader; maps Shard refuses to host are left out. */
    static List<String> maps(){
        var out = new ArrayList<String>();
        for(String path : BundledMaps.all()){
            if(GenerationFiltersOracleTest.REFUSED.containsKey(path)) continue;
            if(hasUnits(READER.read(BundledMaps.read(path)))) out.add(path);
        }
        return out;
    }

    /**
     * The units vanilla keeps under logic control ({@code LogicAI}, which keeps its state through {@code afterRead}): Shard
     * doesn't model logic control (WP18) and gives them the type's controller, so their bodies are not compared.
     */
    static final Set<Integer> logicControlled = new TreeSet<>();

    /** W2.5's vanilla-written old saves with unit chunks. */
    static List<Path> saves() throws IOException{
        try(var files = Files.list(Path.of("../wire/src/oracleTest/resources/saves"))){
            return files.filter(file -> file.toString().endsWith(".msav")).sorted().filter(file -> {
                try{
                    return hasUnits(READER.read(Files.readAllBytes(file)));
                }catch(IOException e){
                    throw new UncheckedIOException(e);
                }
            }).toList();
        }
    }

    static boolean hasUnits(SaveFile save){
        return save.entities().stream().anyMatch(e -> EntityClasses.get(e.classId()) != null && EntityClasses.get(e.classId()).unit());
    }

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void someMapsHaveSavedUnits(){
        List<String> maps = maps();
        assertTrue(maps.contains("maps/default/archipelago.msav") && maps.size() >= 10, maps.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void savedUnitsComeAliveAsVanillas(String path){
        compare(path, READER.read(BundledMaps.read(path)), true, () -> {
            mindustry.maps.Map map = Vanilla.findMap(path);
            SaveIO.load(map.file, world.filterContext(map));
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("saves")
    void oldSavedUnitsComeAliveAsVanillas(Path file) throws IOException{
        compare(file.getFileName().toString(), READER.read(Files.readAllBytes(file)), false, () -> SaveIO.load(new arc.files.Fi(file.toFile())));
    }

    /**
     * Loads {@code save} in vanilla ({@code load}) and Shard, both from a generator seeded with 0, and compares: as a
     * hosted map ({@code map}: its filters) or a save.
     */
    static void compare(String path, SaveFile save, boolean map, Runnable load){
        Vanilla.reset();
        load.run();
        int expectedSteps = GenerationFiltersOracleTest.steps(arc.math.Mathf.rand.seed0, arc.math.Mathf.rand.seed1);
        Vanilla.runPosted();
        // payload blocks holding buildings of old base versions load too (saves v108-v135)
        MapLoader.Loaded loaded = map ? LOADER.loadMap(save, Worlds.config(0), LOADER.saveRules(save))
            : LOADER.load(save, Worlds.config(0), LOADER.saveRules(save));
        World shard = loaded.world();
        // a unit carrying a building stays bytes, as carried buildings aren't ported: it is left out on both sides
        var asBytes = new TreeSet<Integer>();
        for(var chunk : loaded.state().entities){
            if(!loaded.state().live.contains(chunk.id()) && EntityClasses.get(chunk.classId()) != null && EntityClasses.get(chunk.classId()).unit()) asBytes.add(chunk.id());
        }

        var expected = new ArrayList<String>();
        var expectedBodies = new ArrayList<String>();
        logicControlled.clear();
        for(mindustry.gen.Unit unit : Groups.unit){
            if(asBytes.contains(unit.id)){
                assertTrue(unit instanceof Payloadc carrier && carriesBuilding(carrier), path + ": unit " + unit.id + " stays bytes but carries no building");
                continue;
            }
            expected.add(unit.id + " " + unit.type.name + " team " + unit.team.id + " at " + unit.x + "," + unit.y + " rotation " + unit.rotation
                + " health " + unit.health + " elevation " + unit.elevation + " core " + unit.spawnedByCore);
            if(unit instanceof Payloadc carrier) carried(expected, carrier, "  ");
            if(unit.controller() instanceof mindustry.ai.types.LogicAI) logicControlled.add(unit.id);
            expectedBodies.add(unit.id + " " + body(unit));
        }
        expected.add("next id " + PlayOracleTest.CoreIds.lastId());
        var actual = new ArrayList<String>();
        var actualBodies = new ArrayList<String>();
        // a loaded unit keeps the entity class it was saved with, a legacy one included, carried or not
        var projector = new EntityProjector(shard.content(), CODECS, loaded.state().classes);
        WorldWireContext context = WorldWireContext.capture(shard);
        for(int i = 0; i < shard.units().size(); i++){
            Unit unit = shard.units().get(i);
            actual.add(unit.id() + " " + unit.type().name() + " team " + unit.team + " at " + unit.x + "," + unit.y + " rotation " + unit.rotation
                + " health " + unit.health + " elevation " + unit.elevation + " core " + unit.spawnedByCore);
            carried(actual, unit, "  ");
            var bytes = new Writes();
            EntityClasses.writeSave(bytes, projector.classOf(unit), projector.unit(unit), context);
            actualBodies.add(unit.id() + " " + HexFormat.of().formatHex(bytes.toByteArray()));
            if(logicControlled.contains(unit.id())) actualBodies.set(actualBodies.size() - 1, expectedBodies.get(actualBodies.size() - 1));
        }
        actual.add("next id " + shard.ids().peek());
        assertFalse(expected.size() == 1, "vanilla has units");
        assertEquals(String.join("\n", expected), String.join("\n", actual), path);
        assertEquals(String.join("\n", expectedBodies), String.join("\n", actualBodies), path + ": save bodies");
        String unknown = loaded.state().unknownDraws;
        assertNull(unknown, path + ": draws Shard doesn't count");
        assertEquals(expectedSteps, GenerationFiltersOracleTest.steps(shard.rand().getState(0), shard.rand().getState(1)), path + ": values drawn");

        // the world goes on from there: a new unit's id and draws
        mindustry.gen.Unit next = mindustry.content.UnitTypes.dagger.create(mindustry.game.Team.sharded);
        next.add();
        Unit spawned = Unit.create(shard, shard.content().unit("dagger"), mindustry.game.Team.sharded.id);
        spawned.add();
        assertEquals(List.of(next.id, arc.math.Mathf.rand.seed0, arc.math.Mathf.rand.seed1),
            List.of(spawned.id(), shard.rand().getState(0), shard.rand().getState(1)), path + ": a unit spawned after the load");
    }

    static boolean carriesBuilding(Payloadc carrier){
        for(mindustry.world.blocks.payloads.Payload payload : carrier.payloads()){
            if(payload instanceof mindustry.world.blocks.payloads.BuildPayload) return true;
            if(payload instanceof mindustry.world.blocks.payloads.UnitPayload carried && carried.unit instanceof Payloadc inner && carriesBuilding(inner)) return true;
        }
        return false;
    }

    /** The units {@code carrier} carries, theirs after each, one per line. */
    static void carried(List<String> out, Payloadc carrier, String indent){
        for(mindustry.world.blocks.payloads.Payload payload : carrier.payloads()){
            if(!(payload instanceof mindustry.world.blocks.payloads.UnitPayload carried)) continue;
            mindustry.gen.Unit unit = carried.unit;
            out.add(indent + "carries " + unit.id + " " + unit.type.name + " team " + unit.team.id + " health " + unit.health);
            if(unit instanceof Payloadc inner) carried(out, inner, indent + "  ");
        }
    }

    static void carried(List<String> out, Unit carrier, String indent){
        if(carrier.payloads == null) return;
        for(int i = 0; i < carrier.payloads.size(); i++){
            if(!(carrier.payloads.get(i) instanceof com.xpdustry.shard.sim.unit.Payload.UnitPayload(Unit unit))) continue;
            out.add(indent + "carries " + unit.id() + " " + unit.type().name() + " team " + unit.team + " health " + unit.health);
            carried(out, unit, indent + "  ");
        }
    }

    /** {@code unit.write()}: what a save or world stream holds after the class id and entity id. */
    static String body(mindustry.gen.Unit unit){
        var bytes = new ByteArrayOutputStream();
        unit.beforeWrite();
        unit.write(new arc.util.io.Writes(new DataOutputStream(bytes)));
        return HexFormat.of().formatHex(bytes.toByteArray());
    }
}
