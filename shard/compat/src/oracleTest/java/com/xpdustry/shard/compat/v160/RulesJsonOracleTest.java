package com.xpdustry.shard.compat.v160;

import arc.graphics.*;
import arc.struct.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.rules.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.testkit.BundledMaps;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.meta.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;
import java.util.function.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Shard's rules JSON against vanilla's {@code JsonIO} on ten presets: vanilla's JSON read and written by Shard gives
 * the same JSON, and Shard's JSON read and written by vanilla does too. Arc's sets and maps are hash-ordered, so JSON
 * is compared after sorting keys and set values ({@link OracleWorlds#canonical}); presets without such collections
 * must match byte for byte.
 */
class RulesJsonOracleTest{
    static final RulesJson SHARD = new RulesJson(Worlds.EPOCH);

    record Preset(String name, boolean exact, Supplier<Rules> rules){
        @Override
        public String toString(){
            return name;
        }
    }

    static List<Preset> presets(){
        return List.of(
            new Preset("default", true, Rules::new),
            new Preset("survival", true, () -> Gamemode.survival.apply(new Rules())),
            new Preset("sandbox", true, () -> Gamemode.sandbox.apply(new Rules())),
            new Preset("attack", true, () -> Gamemode.attack.apply(new Rules())),
            new Preset("pvp", true, () -> Gamemode.pvp.apply(new Rules())),
            new Preset("editor", true, () -> Gamemode.editor.apply(new Rules())),
            new Preset("fortress host", false, () -> Vanilla.findMap("fortress").applyRules(Gamemode.survival)),
            new Preset("onset attack", false, () -> Vanilla.findMap("erekir/onset").applyRules(Gamemode.attack)),
            new Preset("groundZero survival", false, () -> Vanilla.findMap("serpulo/groundZero").applyRules(Gamemode.survival)),
            new Preset("every field type", false, RulesJsonOracleTest::everyFieldType));
    }

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /**
     * Vanilla's JSON read and written by Shard equals it read and written by vanilla (reading normalizes: a spawn
     * group built without an effect reads back with "none", a digit-only colour reads back transparent), and vanilla
     * reads Shard's JSON back to the same rules.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("presets")
    void roundTripsThroughBothSides(Preset preset){
        String written = JsonIO.write(preset.rules().get());
        String vanilla = JsonIO.write(JsonIO.read(Rules.class, written));
        String shard = SHARD.write(SHARD.read(written));
        if(preset.exact()) assertEquals(vanilla, shard, "Shard rewrites vanilla's JSON");
        assertCanonical(vanilla, shard, "Shard rewrites vanilla's JSON");
        String back = JsonIO.write(JsonIO.read(Rules.class, shard));
        assertCanonical(vanilla, back, "vanilla rewrites Shard's JSON");
    }

    static void assertCanonical(String expected, String actual, String message){
        var differences = new ArrayList<String>();
        mapDifferences(differences, "rules", canonical(Oracle.parseArcJson(expected)), canonical(Oracle.parseArcJson(actual)));
        assertEquals(List.of(), differences, message);
    }

    @Test
    void gamemodePresetsMatch(){
        for(var mode : com.xpdustry.shard.sim.rules.Gamemode.values()){
            Gamemode vanilla = Gamemode.valueOf(mode.name().toLowerCase(Locale.ROOT));
            assertEquals(JsonIO.write(vanilla.apply(new Rules())), SHARD.write(mode.apply(new com.xpdustry.shard.sim.rules.Rules())), mode.name());
        }
    }

    @Test
    void mapRulesMatchApplyRules(){
        var mapRules = new MapRules(SHARD);
        var reader = new MsavReader(Worlds.EPOCH);
        int checked = 0;
        for(String path : BundledMaps.all()){
            String internal = path.substring("maps/".length(), path.length() - ".msav".length());
            String tag = reader.meta(BundledMaps.read(path)).get("rules");
            for(var mode : List.of(com.xpdustry.shard.sim.rules.Gamemode.SURVIVAL, com.xpdustry.shard.sim.rules.Gamemode.PVP)){
                Gamemode vanillaMode = Gamemode.valueOf(mode.name().toLowerCase(Locale.ROOT));
                String vanilla = JsonIO.write(maps.loadInternalMap(internal).applyRules(vanillaMode));
                String shard = SHARD.write(mapRules.apply(tag, mode));
                // Shard keeps objectives as the map wrote them, where vanilla parses them into objects and writes
                // those; both read back to the same objectives
                assertCanonical(withoutObjectives(vanilla), withoutObjectives(shard), path + " " + mode);
                assertCanonical(JsonIO.write(JsonIO.read(Rules.class, vanilla)), JsonIO.write(JsonIO.read(Rules.class, shard)), path + " " + mode + " read by vanilla");
                checked++;
            }
        }
        assertEquals(2 * 114, checked);
    }

    @Test
    void teamIdsAndAttributesMatch(){
        assertEquals(Team.all.length, com.xpdustry.shard.sim.rules.VanillaIds.TEAMS);
        assertEquals(Team.derelict.id, com.xpdustry.shard.sim.rules.VanillaIds.DERELICT);
        assertEquals(Team.sharded.id, com.xpdustry.shard.sim.rules.VanillaIds.SHARDED);
        assertEquals(Team.crux.id, com.xpdustry.shard.sim.rules.VanillaIds.CRUX);
        var names = new ArrayList<String>();
        for(Attribute attribute : Attribute.all) names.add(attribute.name);
        assertEquals(names, com.xpdustry.shard.sim.rules.Attributes.NAMES);
    }

    @Test
    void defaultWavesMatch(){
        var rules = new Rules();
        rules.spawns = waves.get();
        var shard = new com.xpdustry.shard.sim.rules.Rules();
        shard.spawns = new MapRules(SHARD).defaultWaves();
        assertEquals(JsonIO.write(rules), SHARD.write(shard));
    }

    /** Every field's {@code setRule} payload, as vanilla's {@code NetClient.setRule} reads it, rebuilds the rules. */
    @Test
    void setRulePayloadsReadBackInVanilla(){
        String written = JsonIO.write(everyFieldType());
        var shard = SHARD.read(written);
        var defaults = new com.xpdustry.shard.sim.rules.Rules();
        state.rules = new Rules();
        var sent = new ArrayList<String>();
        var logged = Vanilla.logged(() -> {
            for(var field : Rules.class.getFields()){
                if(java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                String payload = SHARD.writeField(shard, field.getName());
                assertNotNull(payload, field.getName());
                // as a server would, only changed fields (vanilla cannot read a null sector back)
                if(payload.equals(SHARD.writeField(defaults, field.getName()))) continue;
                mindustry.core.NetClient.setRule(field.getName(), payload);
                sent.add(field.getName());
            }
        });
        assertEquals(List.of(), logged.stream().filter(line -> line.contains("Failed to read rule")).toList());
        assertTrue(sent.size() >= 25, "fields sent: " + sent);
        assertCanonical(JsonIO.write(JsonIO.read(Rules.class, written)), JsonIO.write(state.rules), "rules rebuilt by setRule");
    }

    static String withoutObjectives(String json){
        var value = new arc.util.serialization.JsonReader().parse(json);
        value.remove("objectives");
        return value.toJson(arc.util.serialization.JsonWriter.OutputType.minimal);
    }

    /** A value of every field type the rules have, including vanilla's digit-only colour quirk. */
    static Rules everyFieldType(){
        var rules = new Rules();
        rules.infiniteResources = true;
        rules.waveSpacing = 1234.5f;
        rules.dropZoneRadius = 0.1f;
        rules.winWave = 30;
        rules.unitCap = -4;
        rules.env = Env.scorching | Env.terrestrial;
        rules.planet = Planets.erekir;
        rules.bannedBlocks.addAll(Blocks.duo, Blocks.scatter, Blocks.mender);
        rules.bannedUnits.addAll(UnitTypes.flare, UnitTypes.mono);
        rules.revealedBlocks.add(Blocks.copperWall);
        rules.researched.addAll(Items.copper, Blocks.duo);
        rules.objectiveFlags.addAll("first", "second flag");
        rules.blockLimits.put(Blocks.mender, 3);
        rules.blockLimits.put(Blocks.duo, 7);
        rules.loadout = ItemStack.list(Items.lead, 50, Items.silicon, 20);
        rules.weather.add(new Weather.WeatherEntry(Weathers.rain, 1f, 2f, 3f, 4f), new Weather.WeatherEntry(Weathers.sandstorm));
        rules.teams.get(Team.crux).infiniteResources = true;
        rules.teams.get(Team.crux).rtsAi = true;
        rules.teams.get(Team.blue).blockDamageMultiplier = 1.5f;
        rules.tags.put("key", "value");
        rules.tags.put("other", "with, comma");
        rules.attributes.set(Attribute.heat, 0.5f);
        rules.lighting = true;
        rules.ambientLight = Color.valueOf("336699cc");
        rules.staticColor = Color.valueOf("12345678");
        rules.cloudColor = new Color(1f, 0f, 0f, 1f);
        rules.modeName = "Custom \"mode\"";
        rules.mission = "line one\nline two";
        rules.limitMapArea = true;
        rules.limitX = 5;
        rules.limitWidth = 40;
        rules.backgroundTexture = "sprites/space.png";
        var group = new SpawnGroup(UnitTypes.quasar);
        group.begin = 3;
        group.end = 50;
        group.spacing = 2;
        group.max = 12;
        group.unitScaling = 1.5f;
        group.shields = 20f;
        group.shieldScaling = 4f;
        group.unitAmount = 3;
        group.spawn = 1234;
        group.effect = StatusEffects.overclock;
        group.payloads = Seq.with(UnitTypes.dagger, UnitTypes.nova);
        group.items = new ItemStack(Items.thorium, 30);
        group.team = Team.blue;
        rules.spawns = Seq.with(group, new SpawnGroup(UnitTypes.flare));
        return rules;
    }
}
