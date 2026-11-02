package com.xpdustry.shard.compat.v160.rules;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RulesJsonTest{
    static final Epoch CONTENT = Worlds.EPOCH;
    static final RulesJson JSON = new RulesJson(CONTENT);

    @Test
    void defaultRulesWriteOnlyFieldsWithoutEquals(){
        assertEquals("{teams:{},attributes:{},objectives:[]}", JSON.write(new Rules()));
    }

    @Test
    void everyBundledMapsRulesRoundTrip(){
        var reader = new MsavReader(Worlds.EPOCH);
        int maps = 0;
        for(String path : BundledMaps.all()){
            String tag = reader.meta(BundledMaps.read(path)).get("rules");
            if(tag == null) continue;
            Rules first = JSON.read(tag);
            String written = JSON.write(first);
            Rules second = JSON.read(written);
            assertEquals(written, JSON.write(second), path);
            assertEquals(hash(first), hash(second), path);
            maps++;
        }
        assertTrue(maps > 100, "rules tags read: " + maps);
    }

    @Test
    void readsFieldsOverABase(){
        Rules base = Gamemode.PVP.apply(new Rules());
        Rules rules = JSON.read(base, "{waveSpacing:600,unknownField:3,bannedBlocks:{values:[duo,\"scatter\"]},loadout:[{item:lead,amount:5}]}");
        assertSame(base, rules);
        assertTrue(rules.pvp);
        assertEquals(600f, rules.waveSpacing);
        assertEquals(new TreeSet<>(List.of(CONTENT.id(ContentType.BLOCK, "duo"), CONTENT.id(ContentType.BLOCK, "scatter"))), rules.bannedBlocks);
        assertEquals(List.of(new ItemStack(CONTENT.id(ContentType.ITEM, "lead"), 5)), rules.loadout);
    }

    @Test
    void contentFallsBackAsVanilla(){
        // old block names go through SaveFileReader.fallback; unknown ones are air
        Rules rules = JSON.read("{bannedBlocks:[rocks,not-a-block],blockLimits:{duo:3}}");
        assertEquals(new TreeSet<>(List.of(0, CONTENT.id(ContentType.BLOCK, "stone-wall"))), rules.bannedBlocks);
        assertEquals(Map.of(CONTENT.id(ContentType.BLOCK, "duo"), 3), rules.blockLimits);
        // an unknown item is copper, an unknown planet serpulo
        rules = JSON.read("{loadout:[{item:unobtainium,amount:1}],planet:mars}");
        assertEquals(VanillaIds.COPPER, rules.loadout.getFirst().item());
        assertEquals(VanillaIds.SERPULO, rules.planet);
        assertEquals(-1, JSON.read("{planet:null}").planet);
    }

    @Test
    void spawnGroupsReadAsSpawnGroupRead(){
        List<SpawnGroup> groups = JSON.readSpawns("[{type:titan,effect:8},{type:block},{type:flare,effect:boss,payloads:[dagger,nope]},{}]");
        assertEquals(CONTENT.id(ContentType.UNIT, "mace"), groups.get(0).type);
        assertEquals(VanillaIds.STATUS_BOSS, groups.get(0).effect);
        assertEquals(VanillaIds.DAGGER, groups.get(1).type);
        assertEquals(VanillaIds.STATUS_NONE, groups.get(1).effect);
        assertEquals(List.of(VanillaIds.DAGGER), groups.get(2).payloads);
        assertEquals(VanillaIds.DAGGER, groups.get(3).type);
        assertEquals(SpawnGroup.NEVER, groups.get(3).end);
        assertEquals("[{type:mace,effect:boss},{type:dagger,effect:none},{type:flare,effect:boss,payloads:[dagger]},{type:dagger,effect:none}]",
            JSON.writeSpawns(groups));
    }

    @Test
    void teamRulesWriteByTeamId(){
        var rules = new Rules();
        rules.teams.get(VanillaIds.CRUX).infiniteResources = true;
        rules.teams.get(VanillaIds.CRUX).blockHealthMultiplier = 2f;
        String json = JSON.write(rules);
        assertEquals("{teams:{2:{infiniteResources:true,blockHealthMultiplier:2}},attributes:{},objectives:[]}", json);
        assertTrue(JSON.read(json).teams.peek(VanillaIds.CRUX).infiniteResources);
    }

    @Test
    void tagsAreWrappedWithTheirClass(){
        var rules = new Rules();
        rules.tags.put("note", "hello");
        String json = JSON.writeField(rules, "tags");
        assertEquals("{note:{class:java.lang.String,value:hello}}", json);
        var read = new Rules();
        assertTrue(JSON.readField(read, "tags", json));
        assertEquals(Map.of("note", "hello"), read.tags);
        assertTrue(JSON.readField(read, "tags", "{plain:text}"));
        assertEquals(Map.of("plain", "text"), read.tags);
    }

    @Test
    void writesSingleFields(){
        var rules = new Rules();
        assertEquals("7200", JSON.writeField(rules, "waveSpacing"));
        assertEquals("true", JSON.writeField(rules, "waveTimer"));
        assertEquals("000000ff", JSON.writeField(rules, "staticColor"));
        assertNull(JSON.writeField(rules, "noSuchRule"));
        assertFalse(JSON.readField(rules, "noSuchRule", "1"));
    }

    @Test
    void digitOnlyColoursReadBackTransparent(){
        // Vanilla quirk: 00000000 is written unquoted, so it reads back as a number
        Rules rules = JSON.read("{ambientLight:00000000,staticColor:\"11223344\",cloudColor:ff0000ff}");
        assertEquals(new Color(0f, 0f, 0f, 0f), rules.ambientLight);
        assertEquals(Color.valueOf("11223344"), rules.staticColor);
        assertEquals(Color.valueOf("ff0000ff"), rules.cloudColor);
    }

    @Test
    void numbersCoerceAsArc(){
        Rules rules = JSON.read("{waves:1,waveTimer:\"true\",winWave:\"12\",waveSpacing:\"3.5f\"}");
        assertFalse(rules.waves); // a number is not a boolean to Arc's reader
        assertTrue(rules.waveTimer);
        assertEquals(12, rules.winWave);
        assertEquals(3.5f, rules.waveSpacing);
    }

    @Test
    void mapRulesApplyThePresetAndFixups(){
        var mapRules = new MapRules(JSON);
        Rules rules = mapRules.apply("{waves:false,env:" + (Env.SCORCHING | Env.TERRESTRIAL) + "}", Gamemode.SURVIVAL);
        assertFalse(rules.waves);
        assertEquals(VanillaIds.EREKIR, rules.planet);
        assertEquals(mapRules.defaultWaves(), rules.spawns);
        assertEquals(28, rules.spawns.size());
        // a broken tag gives plain new Rules(), without the mode
        Rules broken = mapRules.apply("{waves:", Gamemode.PVP);
        assertFalse(broken.pvp);
        assertEquals(hash(new Rules()), hash(broken));
    }

    static long hash(Rules rules){
        var hasher = new Hasher();
        rules.hash(hasher);
        return hasher.value();
    }
}
