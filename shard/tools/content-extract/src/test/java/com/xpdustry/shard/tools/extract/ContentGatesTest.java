package com.xpdustry.shard.tools.extract;

import arc.struct.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.ctype.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.mod.data.*;
import org.junit.jupiter.api.*;
import com.xpdustry.shard.content.ContentDef;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.content.patch.*;
import com.xpdustry.shard.content.yaml.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The content system's oracle gates (WAVES.md W1.1), against vanilla v160.7 booted in this JVM. They share one boot
 * and one extraction, so they run in order and the patch gate, which changes vanilla's content, runs last.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContentGatesTest{
    static Fidelity fidelity;
    static Epoch epoch;

    @BeforeAll
    static void boot(){
        fidelity = new Fidelity();
        epoch = Epoch.of(fidelity.baked);
    }

    /** content/src/main/resources/content/vanilla is what the extractor writes now. */
    @Test
    @Order(1)
    void committedPackIsCurrent() throws Exception{
        String dir = System.getProperty("com.xpdustry.shard.content.pack");
        Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "no committed pack");
        Path committed = Path.of(dir);
        List<String> names;
        try(Stream<Path> walk = Files.walk(committed)){
            names = walk.filter(p -> p.toString().endsWith(".yml")).map(p -> committed.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
        String hint = "; run ./gradlew :tools:content-extract:extract";
        assertEquals(List.copyOf(fidelity.files.keySet()), names, "file list" + hint);
        for(var e : fidelity.files.entrySet()){
            assertEquals(e.getValue(), Files.readString(committed.resolve(e.getKey())), e.getKey() + " is stale" + hint);
        }
    }

    /** Shard's wire ids are vanilla's: same names in the same order, and bullets of the same class at each id. */
    @Test
    @Order(2)
    void idsAreVanillas(){
        for(var type : com.xpdustry.shard.content.ContentType.values()){
            Seq<Content> vanilla = fidelity.extract.booted.getBy(Fidelity.vanillaType(type));
            assertEquals(vanilla.size, epoch.count(type), type + " count");
            for(int id = 0; id < vanilla.size; id++){
                Content c = vanilla.get(id);
                assertEquals(id, c.id);
                ContentDef def = epoch.get(type, id);
                if(c instanceof MappableContent m) assertEquals(m.name, def.name(), type + " " + id);
                assertEquals(fidelity.extract.kinds.kind(Names.concrete(c.getClass())).key, def.fields().kind().key(), type + " " + id + " class");
            }
        }
    }

    /** Every baked field of every content object equals what a booted vanilla holds. */
    @Test
    @Order(3)
    void bakeMatchesVanilla(){
        assertEquals(List.of(), fidelity.diagnostics, "bake errors");
        List<String> diffs = new ArrayList<>();
        for(var type : com.xpdustry.shard.content.ContentType.values()){
            for(int id = 0; id < fidelity.vanillaCount(type); id++){
                Fidelity.diff(type.yamlName() + " " + epoch.get(type, id).name(), fidelity.vanilla(type, id), fidelity.shard(type, id), diffs);
            }
        }
        assertEquals(List.of(), diffs.subList(0, Math.min(diffs.size(), 40)), diffs.size() + " differences");
    }

    /** A world's patch, applied by vanilla's own patcher, gives vanilla exactly the world's baked content. */
    @Test
    @Order(4)
    void patchesGiveVanillaTheWorldsContent() throws Exception{
        var diagnostics = new Diagnostics();
        Epoch world = Epoch.load(Pack.vanilla().with(List.of(new Pack.Source("world.yml", worldYaml))), diagnostics);
        assertEquals(List.of(), diagnostics.list());
        Patch patch = Patchgen.generate(epoch, world, diagnostics);
        assertEquals(List.of(), diagnostics.list());
        assertNotNull(patch);
        // derived fields are in: health and build time from requirements, bullet range, mirrored weapon reload
        assertTrue(patch.entries().containsAll(List.of("block.duo.health", "block.duo.buildTime", "block.conveyor.buildTime",
            "block.duo.ammoTypes.copper.range", "unit.dagger.weapons.1.reload", "block.graphite-press.consumes", "unit.flare.abilities")), patch.json());

        keepSoundNames();
        var asset = new PatchAsset(patch.json());
        Vars.state.data.load(Seq.with(asset));
        try{
            assertFalse(asset.error, asset.warnings.toString());
            assertEquals(List.of(), List.of(asset.warnings.toArray(String.class)));
            // the patch reached the objects the comparison reads
            var duo = (mindustry.world.blocks.defense.turrets.ItemTurret)Vars.content.block("duo");
            assertSame(fidelity.extract.booted.block("duo"), duo);
            assertEquals(400, duo.health);
            assertEquals(15f, duo.ammoTypes.get(Vars.content.item("copper")).damage);
            assertEquals(20f, Vars.content.unit("dagger").weapons.get(1).reload);
            assertTrue(Vars.content.block("graphite-press").hasPower);
            List<String> diffs = new ArrayList<>();
            for(var type : com.xpdustry.shard.content.ContentType.values()){
                for(int id = 0; id < world.count(type); id++){
                    ContentDef def = world.get(type, id);
                    Fidelity.diff(type.yamlName() + " " + def.name(), consumersSorted(fidelity.vanilla(type, id)),
                        consumersSorted(Fidelity.plain(def.fields())), diffs);
                }
            }
            assertEquals(List.of(), diffs.subList(0, Math.min(diffs.size(), 40)), diffs.size() + " differences\n" + patch.json());
        }finally{
            Vars.state.data.unload();
        }

        // and un-applying it gives vanilla back
        List<String> diffs = new ArrayList<>();
        for(var type : com.xpdustry.shard.content.ContentType.values()){
            for(int id = 0; id < epoch.count(type); id++){
                if(epoch.get(type, id).fields().equals(world.get(type, id).fields())) continue;
                Fidelity.diff(type.yamlName() + " " + epoch.get(type, id).name(), fidelity.vanilla(type, id), fidelity.shard(type, id), diffs);
            }
        }
        assertEquals(List.of(), diffs);
    }

    /**
     * A map's patches, applied by vanilla's own patcher on a dedicated server and by Shard's {@link
     * com.xpdustry.shard.content.patch.DataPatcher}, give the same content: every baked field of every content object,
     * with the paths maps use (dotted keys, nested objects, list indices, {@code +}, attributes, stacks), a unit's path
     * type derived again by {@code afterPatch}, an {@code @NoPatch} field skipped and a {@code requiredPlanets} patch
     * dropped off its planet.
     */
    @Test
    @Order(5)
    void mapPatchesApplyAsVanillas(){
        var shardPatches = List.of(
            new com.xpdustry.shard.content.patch.DataAsset.PatchAsset("patches/stats.json", mapPatch),
            new com.xpdustry.shard.content.patch.DataAsset.PatchAsset("patches/erekir.json", "{\"requiredPlanets\": [\"erekir\"], \"block.conveyor.speed\": 1}"));
        var applied = com.xpdustry.shard.content.patch.DataPatcher.apply(epoch, shardPatches, "serpulo");
        assertEquals(List.of("patches/stats.json"), applied.kept().stream().map(com.xpdustry.shard.content.patch.DataAsset::path).toList(), "requiredPlanets drops the second");
        assertEquals(1, applied.warnings().size(), "the @NoPatch size: " + applied.warnings());
        Epoch world = applied.epoch();
        assertTrue(world.isPatched());
        assertSame(epoch.hash(), world.base().hash());
        assertEquals(0.08f, world.block("conveyor").fields().f("speed"));
        assertEquals(epoch.hash(), Epoch.vanilla().hash(), "the gates' epoch is the vanilla pack's");

        boolean headless = Vars.headless;
        Vars.headless = true;
        Vars.state.rules.planet = mindustry.content.Planets.serpulo;
        var stats = new PatchAsset(mapPatch);
        var erekir = new PatchAsset("{\"requiredPlanets\": [\"erekir\"], \"block.conveyor.speed\": 1}");
        Vars.state.data.load(Seq.with(stats, erekir));
        try{
            assertFalse(stats.error, stats.warnings.toString());
            assertEquals(1, stats.warnings.size, stats.warnings.toString());
            assertEquals(1, Vars.state.data.getPatches().size, "vanilla drops the erekir patch too");
            assertEquals(0.08f, ((mindustry.world.blocks.distribution.Conveyor)Vars.content.block("conveyor")).speed);
            List<String> diffs = new ArrayList<>();
            for(var type : com.xpdustry.shard.content.ContentType.values()){
                for(int id = 0; id < world.count(type); id++){
                    ContentDef def = world.get(type, id);
                    Fidelity.diff(type.yamlName() + " " + def.name(), fidelity.vanilla(type, id), Fidelity.plain(def.fields()), diffs);
                }
            }
            assertEquals(List.of(), diffs.subList(0, Math.min(diffs.size(), 40)), diffs.size() + " differences");
        }finally{
            Vars.state.data.unload();
            Vars.headless = headless;
        }
    }

    /** The map patch {@link #mapPatchesApplyAsVanillas} applies, in HJSON as the editor's files may hold it. */
    static final String mapPatch = """
        # stats a map changes
        name: shard-stats
        block.conveyor.speed: 0.08
        block: {
          mechanical-drill: {drillTime: 300, tier: 3}
          graphite-press: {craftTime: 45}
        }
        "block.duo.health": 500
        block.titanium-conveyor.displayedSpeed: 12
        block.sand-floor.attributes.oil: 0.9
        block.conveyor.requirements: ["copper/3", "lead/1"]
        block.graphite-press.liquidOutputDirections.+: 2
        block.conveyor.size: 2
        block.router.buildCostMultiplier: 3.5
        unit.dagger.speed: 0.9
        unit.dagger.weapons.0.reload: 30
        unit.mono.naval: true
        item.copper.hardness: 2
        liquid.water.viscosity: 0.7
        status.wet.speedMultiplier: 0.75
        """;

    /** A patch appends the consumers it adds (Patchgen#consumers), so consumer order is compared as a set. */
    static Map<String, Object> consumersSorted(Map<String, Object> plain){
        if(plain.get("consumes") instanceof List<?> list){
            var sorted = new ArrayList<Object>(list);
            sorted.sort(Comparator.comparing(c -> String.valueOf(canonical(c))));
            plain.put("consumes", sorted);
        }
        return plain;
    }

    /** {@code value} with every map's keys sorted, so equal trees print alike. */
    static Object canonical(Object value){
        return switch(value){
            case Map<?, ?> m -> {
                var out = new TreeMap<String, Object>();
                m.forEach((k, v) -> out.put((String)k, canonical(v)));
                yield out;
            }
            case List<?> l -> l.stream().map(ContentGatesTest::canonical).toList();
            case null, default -> value;
        };
    }

    /** One world touching every kind of patch path: scalars, derived values, bullets, maps, attributes, consumers, sub-objects. */
    static final String worldYaml = """
        - type: block
          id: duo
          overlay: true
          health: 400
          requirements: {copper: 50, lead: 10}
          reload: 15
          shootSound: sound:shootArc
          ammoTypes: {copper: duo/ammoTypes.copper, graphite: duo/ammoTypes.graphite}
        - type: bullet
          id: duo/ammoTypes.copper
          overlay: true
          damage: 15
          speed: 3
          hitEffect: fx:hitBulletBig
          backColor: "#ff0000ff"
        - type: block
          id: silicon-smelter
          overlay: true
          craftTime: 30
          consumes:
            - kind: consume-items
              items: {coal: 1, sand: 3}
            - {kind: consume-power, usage: 0.6}
        - type: block
          id: graphite-press
          overlay: true
          consumes:
            - kind: consume-items
              items: {coal: 2}
            - {kind: consume-power, usage: 0.25}
        - type: block
          id: conveyor
          overlay: true
          speed: 0.05
          displayedSpeed: 7
        - type: block
          id: sand-floor
          overlay: true
          attributes: {oil: 1.0, water: 0.1}
        - type: unit
          id: dagger
          overlay: true
          speed: 0.8
          health: 200
          weapons:
            - kind: weapon
              bullet: dagger/weapons.0.bullet
              ejectEffect: fx:casing1
              name: large-weapon
              reload: 10.0
              top: false
              x: 4.0
              y: 2.0
        - type: bullet
          id: dagger/weapons.0.bullet
          overlay: true
          damage: 12
        - type: block
          id: hail
          overlay: true
          consumes:
            - {kind: consume-coolant, amount: 0.2}
          coolant: {kind: consume-coolant, amount: 0.2}
        - type: unit
          id: flare
          overlay: true
          abilities:
            - {kind: repair-field-ability, amount: 5.0, range: 50.0, reload: 480.0}
          weapons:
            - kind: weapon
              bullet: flare/weapons.0.bullet
              ejectEffect: fx:casing1
              minShootVelocity: 2.0
              mirror: false
              reload: 60.0
              shoot: {kind: shoot-alternate, shots: 2, spread: 2.0}
              shootCone: 10.0
              x: 0.0
              y: 1.0
        - type: item
          id: copper
          overlay: true
          cost: 0.7
        - type: liquid
          id: water
          overlay: true
          heatCapacity: 0.5
          effect: muddy
        - type: status
          id: wet
          overlay: true
          speedMultiplier: 0.8
          color: "#00ff00ff"
        - type: weather
          id: rain
          overlay: true
          status: muddy
          attrs: {water: 0.3, light: -0.2}
        """;

    /**
     * A headless vanilla reads every sound as {@code Sounds.none} (ContentParser.java:313), which would hide whether a
     * patch names the right one. The patcher's parser reads names as a client does for the duration of this JVM.
     */
    @SuppressWarnings("unchecked")
    static void keepSoundNames() throws Exception{
        Field parserField = mindustry.mod.DataPatcher.class.getDeclaredField("parser");
        parserField.setAccessible(true);
        Object parser = parserField.get(null);
        Field parsersField = ContentParser.class.getDeclaredField("classParsers");
        parsersField.setAccessible(true);
        var parsers = (ObjectMap<Class<?>, Object>)parsersField.get(parser);
        Class<?> fieldParser = Class.forName("mindustry.mod.ContentParser$FieldParser");
        Object headless = parsers.get(arc.audio.Sound.class);
        Method parse = fieldParser.getDeclaredMethods()[0];
        parse.setAccessible(true);
        parsers.put(arc.audio.Sound.class, Proxy.newProxyInstance(fieldParser.getClassLoader(), new Class<?>[]{fieldParser}, (proxy, method, args) -> {
            boolean isParse = method.getName().equals(parse.getName());
            if(isParse && args[1] instanceof JsonValue data && data.isString()) return Sounds.class.getField(data.asString()).get(null);
            try{
                return (isParse ? parse : method).invoke(headless, args);
            }catch(InvocationTargetException e){
                throw e.getCause();
            }
        }));
    }
}
