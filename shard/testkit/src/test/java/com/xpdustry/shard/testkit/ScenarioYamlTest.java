package com.xpdustry.shard.testkit;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioYamlTest{
    /** Every action type, every config type, non-default teams and rotations, custom args. */
    static ScenarioFile everything(){
        Scenario scenario = Scenario.named("everything").world(30, 20).seed(-42)
            .place("item-source", 1, 2, 0, Config.item("copper"))
            .row("conveyor", 2, 6, 2, 0)
            .column("titanium-conveyor", 7, 2, 5, 1)
            .place("sorter", 8, 2, 0, Config.NONE)
            .place("liquid-source", 1, 8, 0, Config.liquid("water"))
            .place("unloader", 9, 2, 0, Config.block("router"))
            .place("ground-factory", 12, 12, 0, Config.unit("dagger"))
            .place("switch", 3, 9, 0, Config.of(true))
            .place("message", 4, 9, 0, Config.of("hi: [there] #1 'quoted' \"double\""))
            .place("bridge-conveyor", 5, 9, 2, Config.point(3, 0))
            .place("power-node", 6, 9, 0, Config.points(new Config.Point(1, 0), new Config.Point(-2, 3)))
            .place("thorium-reactor", 10, 9, 3, Config.of(17))
            .team("crux").place("duo", 20, 15).spawn("dagger", 160.5f, 120.25f, 33.3f).coreItems("lead", 50)
            .team("sharded").coreItems("copper", 1000)
            .rules(Map.of("waveSpacing", 120, "waveTimer", false))
            .step(10).items(8, 2, "copper", 5).liquid(1, 8, "water", 0.1f)
            .configure(8, 2, Config.item("lead")).configure(8, 2, Config.NONE)
            .rule("unitCapModifier", 0.5f)
            .step(5).custom("open-valve", Map.of("x", 3, "list", List.of(1, 2.5, "a"), "nested", Map.of("k", true)))
            .remove(3, 2)
            .step(100)
            .build();
        Tolerances tolerances = Tolerances.exact().units(UnitMatch.TYPE_ORDER).missingFields(Tolerances.MissingFields.FAIL)
            .abs("building[conveyor].fields.ys", 1e-5).rel("unit.health", 0.01).ignore("building.fields.blendbits")
            .exactly("building(3,4).health");
        return new ScenarioFile(scenario, tolerances);
    }

    @Test
    void theYamlFormRoundTrips(){
        ScenarioFile file = everything();
        String yaml = ScenarioYaml.write(file);
        ScenarioFile back = ScenarioYaml.read(yaml);
        assertEquals(file, back, yaml);
        assertEquals(yaml, ScenarioYaml.write(back), "the written form is canonical");
        assertTrue(yaml.contains("- {at: 0, do: place, block: item-source, x: 1, y: 2, config: {item: copper}}"), yaml);
        assertTrue(yaml.contains("- {at: 0, do: spawn, unit: dagger, team: crux, x: 160.5, y: 120.25, rotation: 33.3}"), yaml);
        assertTrue(yaml.contains("- {at: 15, do: open-valve, list: [1, 2.5, a], nested: {k: true}, x: 3}"), yaml);
    }

    @Test
    void aCommittedFixtureReadsAndWritesBackUnchanged() throws Exception{
        String text;
        try(var in = getClass().getResourceAsStream("/scenarios/toy-pumps.yaml")){
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        ScenarioFile file = ScenarioYaml.resource("/scenarios/toy-pumps.yaml");
        assertEquals(text, ScenarioYaml.write(file));
        assertEquals(Toys.pumps(400), file.scenario(), "the fixture is the Java scenario");
        assertEquals(Tolerances.exact().abs("building[pump].liquids", 1.5), file.tolerances());
        // its tolerances accept the broken pump
        Differential.of(file).reference(Toys.shard()).candidate(Toys.broken()).trace().run().assertNoDiff();
    }

    @Test
    void handWrittenYamlMayUseCommentsAndBlockStyle(@TempDir Path dir) throws Exception{
        Path file = dir.resolve("hand.yaml");
        Files.writeString(file, """
            # a scenario someone wrote by hand
            name: hand
            world:
              map: fortress
            ticks: 60
            actions:
              - do: place          # at defaults to 0
                block: router
                x: 4
                y: 5
                team: crux
              - at: 30
                do: rules
                set:
                  waveTimer: true
            """);
        Scenario s = ScenarioYaml.load(file).scenario();
        assertEquals(new MapSpec.Bundled("fortress"), s.map());
        assertEquals(0, s.seed());
        assertEquals(List.of(new Action.Timed(0, new Action.Place("router", 4, 5, 0, "crux", Config.NONE)),
            new Action.Timed(30, new Action.Rules(Map.of("waveTimer", true)))), s.actions());
    }

    @Test
    void mistakesAreReportedWithTheActionAndTheKey(){
        var typo = assertThrows(IllegalArgumentException.class, () -> ScenarioYaml.read("""
            name: t
            world: {width: 8, height: 8}
            ticks: 10
            actions:
            - {at: 0, do: place, block: router, x: 1, y: 1, rotaton: 1}
            """));
        assertTrue(typo.getMessage().contains("action 1") && typo.getMessage().contains("unknown key 'rotaton'"), typo.getMessage());
        var late = assertThrows(IllegalArgumentException.class, () -> ScenarioYaml.read("""
            name: t
            world: {width: 8, height: 8}
            ticks: 10
            actions:
            - {at: 11, do: remove, x: 1, y: 1}
            """));
        assertTrue(late.getMessage().contains("past the end"), late.getMessage());
        var schema = assertThrows(IllegalArgumentException.class, () -> ScenarioYaml.read("schema: 2\nname: t\nworld: {width: 8, height: 8}\nticks: 1\n"));
        assertTrue(schema.getMessage().contains("schema 2"), schema.getMessage());
        var config = assertThrows(IllegalArgumentException.class, () -> ScenarioYaml.read("""
            name: t
            world: {width: 8, height: 8}
            ticks: 1
            actions:
            - {do: configure, x: 1, y: 1, config: {colour: red}}
            """));
        assertTrue(config.getMessage().contains("unknown config type 'colour'"), config.getMessage());
    }

    @Test
    void sameTickActionsKeepTheirOrder(){
        Scenario s = Scenario.named("order").world(8, 8).step(5).place("a", 1, 1).at(5).remove(1, 1).at(9).place("b", 2, 2).build();
        assertEquals(List.of(5L, 5L, 9L), s.actions().stream().map(Action.Timed::at).toList());
        assertInstanceOf(Action.Place.class, s.actions().get(0).action());
        assertEquals(9, s.ticks());
        assertThrows(IllegalArgumentException.class, () -> Scenario.named("x").step(5).at(4));
    }
}
