package com.xpdustry.shard.sim.rules;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Checkpoints carry the world's rules whole: every field, team rules, attributes, and every collection in order. */
class RulesCheckpointTest{
    static World restore(World world){
        return Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
    }

    static Rules populated(){
        var rules = new Rules();
        rules.waves = true;
        rules.waveSpacing = 1234.5f;
        rules.winWave = 30;
        rules.sector = "groundZero";
        rules.modeName = null;
        rules.teams.get(3).cheat = true;
        rules.teams.get(3).rtsMaxSquad = 7;
        rules.teams.get(0);
        rules.attributes.set(Attributes.id("spores"), 0.5f);
        rules.ambientMusic = new ArrayList<>(List.of("game1", "game2"));
        rules.darkMusic = null;
        var group = new SpawnGroup(4);
        group.payloads = new ArrayList<>(List.of(1, 2));
        group.items = new ItemStack(3, 40);
        group.end = 10;
        rules.spawns.add(group);
        rules.spawns.add(new SpawnGroup());
        rules.loadout.add(new ItemStack(2, 300));
        var weather = new WeatherEntry();
        weather.always = true;
        weather.intensity = 0.3f;
        rules.weather.add(weather);
        rules.blockLimits.put(12, 4);
        rules.blockLimits.put(3, 1);
        rules.bannedBlocks.addAll(List.of(9, 2, 40));
        rules.bannedUnits.add(5);
        rules.revealedBlocks.add(77);
        rules.researched.addAll(List.of("duo", "conveyor"));
        rules.objectiveFlags.add("done");
        rules.tags.put("b", "2");
        rules.tags.put("a", "");
        rules.tags.put("c", null);
        rules.staticColor = new Color(0.2f, 0.4f, 0.6f, 1f);
        rules.limitMapArea = true;
        rules.limitX = 3;
        rules.backgroundOffsetY = 0.7f;
        return rules;
    }

    @Test
    void theRulesRestoreWhole(){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 16, 16, 3));
        Rules rules = populated();
        world.part(WorldRules.class).rules = rules;
        World restored = restore(world);
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        Rules copy = restored.part(WorldRules.class).rules;
        assertNotSame(rules, copy);
        var a = new Hasher();
        var b = new Hasher();
        rules.hash(a);
        copy.hash(b);
        assertEquals(a.value(), b.value());
        assertEquals(rules.ambientMusic, copy.ambientMusic);
        assertNull(copy.darkMusic, "a null list stays null");
        assertNull(copy.modeName);
        assertEquals(rules.spawns, copy.spawns);
        assertEquals(List.of(1, 2), copy.spawns.get(0).payloads);
        assertNull(copy.spawns.get(1).payloads);
        assertEquals(rules.loadout, copy.loadout);
        assertEquals(rules.blockLimits, copy.blockLimits);
        assertEquals(rules.bannedBlocks, copy.bannedBlocks);
        assertEquals(rules.researched, copy.researched);
        assertEquals(rules.tags, copy.tags);
        assertTrue(copy.teams.get(3).cheat);
        assertNotNull(copy.teams.peek(0), "a team rule made by get() stays made");
        assertNull(copy.teams.peek(4), "an untouched team stays null");
        assertEquals(0.5f, copy.attributes.get(Attributes.id("spores")));
        assertEquals(rules.staticColor, copy.staticColor);
        copy.spawns.add(new SpawnGroup());
        copy.bannedBlocks.add(1);
        assertEquals(2, rules.spawns.size(), "restored collections are the copy's own, and mutable");
    }
}
