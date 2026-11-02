package com.xpdustry.shard.testkit;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.testkit.toy.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The Shard runner on toy kinds, checked against scripted expectations. */
class ShardRunnerTest{
    @Test
    void aToyScenarioMeetsItsScriptedExpectations(){
        Scenario scenario = Toys.pumps(200);
        Expect.of(scenario)
            .at(0, w -> w.building(2, 2).block("pump").liquid("water", 0f).field("strokes", 0)
                .and().building(3, 5).field("item", "copper").field("step", 2).field("total", 0)
                // ids: pump 0 and its power graph 1 (PowerModule.java:15), pump 2 and graph 3, counter 4, wall 5
                .and().unit(6).value("type", "flare").value("x", 40f).value("y", 44f).value("rotation", 90f).health(70f)
                .and().building(8, 5).block("big-wall").health(1280f))
            .at(60, w -> w.building(2, 2).liquid("water", 60 * Pump.RATE).field("strokes", 60).value("power.status", 0f)
                // step 2 from tick 0: sums 2, 4… 120 hit a multiple of 10 twelve times
                .and().building(3, 5).field("total", 120).items("copper", 12).items("lead", 7))
            .at(99, w -> w.building(5, 2).field("strokes", 99).value("power.status", 0.75f))
            .at(100, w -> w.absent(w.building(5, 2)).building(2, 2).field("strokes", 100))
            .at(200, w -> w.building(2, 2).liquid("water", 50f).and().building(3, 5).field("total", 400).items("copper", 40))
            .check(Toys.shard())
            .assertNoDiff();
    }

    @Test
    void aFailedExpectationNamesTheTickTheLeafAndBothValues(){
        DiffReport report = Expect.of(Toys.pumps(200))
            .at(10, w -> w.building(2, 2).field("strokes", 10))
            .at(20, w -> w.building(2, 2).field("strokes", 21).liquid("water", 5f).and().building(3, 5).field("nope", 1))
            .check(Toys.shard());
        assertFalse(report.ok());
        assertEquals(20, report.divergedAt());
        assertEquals(10, report.lastAgreed());
        assertEquals(List.of("building(2,2).fields.strokes", "building(3,5).fields.nope"),
            report.differences().stream().map(Difference::where).toList());
        String text = report.text();
        assertTrue(text.contains("first divergence at tick 20"), text);
        assertTrue(text.contains("21") && text.contains("20") && text.contains("(absent)"), text);
    }

    @Test
    void theProjectionCarriesBaseStateModulesAndAdapterFields(){
        Projection p = Toys.shard().run(Toys.pumps(120));
        assertEquals(Projection.SCHEMA, p.schema());
        assertEquals(120, p.tick());
        assertEquals(List.of("pump", "counter", "big-wall"), p.buildings().stream().map(Projection.BuildingState::block).toList(),
            "sorted by (y, x); the second pump is gone");
        var pump = p.building(2, 2);
        assertEquals("toy-pump", pump.kind());
        assertEquals("sharded", pump.team());
        assertEquals(Map.of("water", 30f), pump.liquids());
        assertNull(pump.items(), "no item module");
        assertNotNull(pump.power());
        var counter = p.building(3, 5);
        assertEquals(List.of("item", "step", "total", "target"), List.copyOf(counter.fields().keySet()), "the adapter's names, in its order");
        assertNull(counter.fields().get("target"));
        assertNull(p.cores(), "Shard has no cores yet");
        assertEquals(false, p.rules().get("canGameOver"), "the world's rules, by vanilla's names; an empty world never ends");
        assertFalse(p.rules().containsKey("defaultTeam"), "vanilla holds teams as objects, which it doesn't project");
        assertNull(p.waves());
        assertEquals("flare", p.unit(6).type(), "after the four buildings and the pumps' two power graphs");
    }

    @Test
    void kindsWithoutAnAdapterProjectTheirPublicFields(){
        Projection p = Toys.shard().run(Toys.pumps(100));
        assertEquals(Map.of("strokes", 100), p.building(2, 2).fields());
        assertEquals(Map.of(), p.building(8, 5).fields());
    }

    @Test
    void referencesBecomePositionsAndAdaptersCanBeOverridden(){
        Scenario scenario = Scenario.named("refs").world(8, 8)
            .place("pump", 1, 1)
            .place("counter", 3, 1, 0, Config.point(-2, 0))
            .build();
        assertEquals("@1,1", Toys.shard().run(scenario).building(3, 1).fields().get("target"));

        var plain = Toys.shard().adapter(new KindAdapter<Counter>(){
            @Override
            public com.xpdustry.shard.content.KindKey kind(){
                return com.xpdustry.shard.content.KindKey.of("toy-counter");
            }

            @Override
            public void configure(Counter counter, Config config, ShardNames names){
                counter.step = 5;
            }
        });
        var fields = plain.run(scenario).building(3, 1).fields();
        assertEquals(5, fields.get("step"), "the default fields(): public fields by Java name");
        assertEquals(-1, fields.get("itemId"));
    }

    @Test
    void unsupportedActionsFailWithTheTickTheActionAndWhatIsMissing(){
        var cores = Scenario.named("cores").world(8, 8).step(5).coreItems("copper", 300).build();
        var error = assertThrows(IllegalStateException.class, () -> Toys.shard().run(cores));
        assertTrue(error.getMessage().contains("tick 5") && error.getMessage().contains("no WorldAdapter handles core items"),
            error.getMessage());

        var wall = Scenario.named("wall-config").world(8, 8).place("copper-wall", 1, 1, 0, Config.of(3)).build();
        error = assertThrows(IllegalStateException.class, () -> Toys.shard().run(wall));
        assertTrue(error.getMessage().contains("no KindAdapter"), error.getMessage());

        var custom = Scenario.named("custom").world(8, 8).custom("flood", Map.of("amount", 3)).build();
        error = assertThrows(IllegalStateException.class, () -> Toys.shard().run(custom));
        assertTrue(error.getMessage().contains("ShardRunner.on(\"flood\""), error.getMessage());
        var flooded = Toys.shard().on("flood", (world, args, names) -> {
            Building pump = world.buildings().create(names.block("pump"), 1, 4, 4, 0, com.xpdustry.shard.sim.world.Cause.SERVER);
            pump.liquids.liquids[0] = ((Number)args.get("amount")).floatValue();
        }).run(custom);
        assertEquals(Map.of("water", 3f), flooded.building(4, 4).liquids());
    }

    @Test
    void worldAdaptersProvideCoresAndWavesAndRulesAreBuiltIn(){
        var cores = new int[22];
        var adapter = new WorldAdapter(){
            @Override
            public boolean coreItems(com.xpdustry.shard.sim.world.World world, int team, int item, int amount){
                cores[item] += amount;
                return true;
            }

            @Override
            public void project(com.xpdustry.shard.sim.world.World world, Projection.Builder out, ShardNames names){
                for(int i = 0; i < cores.length; i++) out.core("sharded", names.itemName(i), cores[i]);
                out.waves(new Projection.WaveState(1, 0f, 0));
            }
        };
        var scenario = Scenario.named("cores").world(8, 8).coreItems("copper", 300).rule("waveTimer", false).step(1).build();
        Projection p = Toys.shard().world(adapter).run(scenario);
        assertEquals(Map.of("sharded", Map.of("copper", 300)), p.cores());
        assertEquals(false, p.rules().get("waveTimer"), "RulesAdapter sets and projects the world's rules");
        assertEquals(1, p.waves().wave());
    }
}
