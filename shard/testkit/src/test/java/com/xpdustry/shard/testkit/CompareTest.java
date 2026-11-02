package com.xpdustry.shard.testkit;

import com.xpdustry.shard.testkit.Projection.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The diff's rules on hand-made projections: pairing, missing leaves, tolerances, notes. */
class CompareTest{
    private static BuildingState building(int x, int y, String block, Map<String, Integer> items, Map<String, Object> fields){
        return new BuildingState(x, y, block, "k-" + block, "sharded", 0, 100f, true, items, null, null, fields);
    }

    private static Projection.Builder projection(String engine){
        return Projection.builder(engine, 5, 16, 16);
    }

    private static List<Difference> diff(Projection a, Projection b, Tolerances tolerances){
        return new Compare(tolerances).compare(a.flat(tolerances.units()), b.flat(tolerances.units()));
    }

    @Test
    void entitiesPairByPositionAndADifferentBlockIsOneDifference(){
        var a = projection("a").building(building(1, 1, "router", Map.of(), Map.of("time", 1f)))
            .building(building(2, 1, "sorter", Map.of(), Map.of())).build();
        var b = projection("b").building(building(1, 1, "conveyor", Map.of(), Map.of("len", 2)))
            .building(building(3, 1, "sorter", Map.of(), Map.of())).build();
        var d = diff(a, b, Tolerances.exact());
        assertEquals(List.of("building(1,1).block", "building(2,1)", "building(3,1)"), d.stream().map(Difference::where).toList());
        assertEquals("router", d.get(0).reference());
        assertEquals("conveyor", d.get(0).candidate());
        assertEquals("present (sorter)", d.get(1).reference());
        assertSame(Difference.ABSENT, d.get(1).candidate());
        assertTrue(diff(a, b, Tolerances.exact().ignore("building(2,1)").ignore("building(3,1)").ignore("building.block")).isEmpty());
    }

    @Test
    void missingItemsCountAsZero(){
        var a = projection("a").building(building(1, 1, "container", Map.of("copper", 3), Map.of())).core("sharded", "lead", 0).build();
        var b = projection("b").building(building(1, 1, "container", Map.of("copper", 3, "lead", 0), Map.of())).build();
        assertTrue(diff(a, b, Tolerances.exact()).isEmpty());
        var c = projection("c").building(building(1, 1, "container", Map.of("lead", 1), Map.of())).build();
        assertEquals(List.of("building(1,1).items.copper", "building(1,1).items.lead"), diff(a, c, Tolerances.exact()).stream().map(Difference::where).toList());
        assertEquals(0, diff(a, c, Tolerances.exact()).get(0).candidate());
    }

    @Test
    void kindFieldsOnlyTheReferenceHasAreNotedUnlessStrict(){
        var vanilla = projection("vanilla").building(building(1, 1, "conveyor", Map.of(), Map.of("len", 2, "blendbits", 3, "ys", List.of(0.5f, 1f)))).build();
        var shard = projection("shard").building(building(1, 1, "conveyor", Map.of(), Map.of("len", 2, "ys", List.of(0.5f, 1f)))).build();
        var compare = new Compare(Tolerances.exact());
        assertTrue(compare.compare(vanilla.flat(UnitMatch.ID), shard.flat(UnitMatch.ID)).isEmpty());
        assertTrue(compare.notes.lines("vanilla", "shard").contains("kind fields only vanilla projects (conveyor: blendbits)"),
            compare.notes.lines("vanilla", "shard").toString());
        assertEquals(List.of("building(1,1).fields.blendbits"),
            diff(vanilla, shard, Tolerances.exact().missingFields(Tolerances.MissingFields.FAIL)).stream().map(Difference::where).toList());

        // a field only the candidate has is a typo or a stray: it fails
        var typo = projection("shard").building(building(1, 1, "conveyor", Map.of(), Map.of("len", 2, "lenn", 2, "blendbits", 3, "ys", List.of(0.5f, 1f)))).build();
        assertEquals(List.of("building(1,1).fields.lenn"), diff(vanilla, typo, Tolerances.exact()).stream().map(Difference::where).toList());

        // within a field both have, a shorter list is a difference
        var short_ = projection("shard").building(building(1, 1, "conveyor", Map.of(), Map.of("len", 2, "ys", List.of(0.5f)))).build();
        assertEquals(List.of("building(1,1).fields.ys[1]"), diff(vanilla, short_, Tolerances.exact()).stream().map(Difference::where).toList());
    }

    @Test
    void sectionsAnEngineDoesNotProjectAreNoted(){
        var vanilla = projection("vanilla").core("sharded", "copper", 10).rule("waves", true).waves(new WaveState(1, 60f, 0)).build();
        var shard = projection("shard").build();
        var compare = new Compare(Tolerances.exact());
        assertTrue(compare.compare(vanilla.flat(UnitMatch.ID), shard.flat(UnitMatch.ID)).isEmpty());
        assertEquals(List.of("core, rules, wave (not projected by shard)"), compare.notes.lines("vanilla", "shard"));
        // projected but different is a difference
        var other = projection("other").core("sharded", "copper", 9).rules().waves(new WaveState(1, 59f, 0)).build();
        assertEquals(List.of("core(sharded).copper", "rules.waves", "wave.wavetime"),
            diff(vanilla, other, Tolerances.exact()).stream().map(Difference::where).toList());
    }

    @Test
    void unitsPairByIdOrByTypeAndRank(){
        var a = projection("a").unit(new UnitState(4, "dagger", "crux", 8f, 8f, 0f, 130f)).unit(new UnitState(9, "dagger", "crux", 16f, 8f, 0f, 130f)).build();
        var b = projection("b").unit(new UnitState(5, "dagger", "crux", 8f, 8f, 0f, 130f)).unit(new UnitState(7, "dagger", "crux", 16f, 8.25f, 0f, 130f)).build();
        assertEquals(4, diff(a, b, Tolerances.exact()).size(), "ids 4 and 9 vs 5 and 7: four units on one side only");
        var byType = Tolerances.exact().units(UnitMatch.TYPE_ORDER);
        assertEquals(List.of("unit(dagger/crux #1).y"), diff(a, b, byType).stream().map(Difference::where).toList());
        assertTrue(diff(a, b, byType.abs("unit.y", 0.25)).isEmpty());
    }

    @Test
    void numbersCompareByValueAndTolerancesBoundThem(){
        var a = projection("a").building(building(1, 1, "x", null, Map.of("f", 1f, "i", 3, "d", 0.1, "n", Float.NaN))).build();
        var b = projection("b").building(building(1, 1, "x", null, Map.of("f", 1.0, "i", 3L, "d", 0.1f, "n", Double.NaN))).build();
        assertTrue(diff(a, b, Tolerances.exact()).isEmpty(), "Float 0.1 and Double 0.1 are the same float");
        var c = projection("c").building(building(1, 1, "x", null, Map.of("f", 1.01f, "i", 4, "d", 0.1, "n", Float.NaN))).build();
        assertEquals(2, diff(a, c, Tolerances.exact()).size());
        assertTrue(diff(a, c, Tolerances.exact().abs("building.fields.f", 0.011).rel("building.fields.i", 0.25)).isEmpty());
        assertEquals(1, diff(a, c, Tolerances.exact().rel("building.fields", 0.25).exactly("building.fields.i")).size(), "the last rule wins");
    }

    @Test
    void patternsSelectScopesBlocksKindsAndPositions(){
        var p = projection("p").building(building(1, 1, "router", null, Map.of("time", 1f)))
            .building(building(2, 1, "sorter", null, Map.of("time", 1f))).unit(new UnitState(3, "flare", "crux", 0f, 0f, 0f, 70f)).build();
        var q = projection("q").building(building(1, 1, "router", null, Map.of("time", 2f)))
            .building(building(2, 1, "sorter", null, Map.of("time", 2f))).unit(new UnitState(3, "flare", "crux", 0f, 0f, 0f, 69f)).build();
        assertEquals(3, diff(p, q, Tolerances.exact()).size());
        assertEquals(List.of("building(2,1).fields.time", "unit#3.health"), where(diff(p, q, Tolerances.exact().ignore("building[router]"))));
        assertEquals(List.of("building(1,1).fields.time", "unit#3.health"), where(diff(p, q, Tolerances.exact().ignore("building[kind=k-sorter].fields.*"))));
        assertEquals(List.of("building(2,1).fields.time"), where(diff(p, q, Tolerances.exact().ignore("*.health").ignore("building(1,1).fields"))));
        assertEquals(List.of("building(1,1).fields.time", "building(2,1).fields.time"), where(diff(p, q, Tolerances.exact().ignore("unit#3"))));
        assertThrows(IllegalArgumentException.class, () -> Tolerances.exact().ignore("buildings.health"));
        assertThrows(IllegalArgumentException.class, () -> Tolerances.exact().ignore("unit(1,2)"));
    }

    private static List<String> where(List<Difference> differences){
        return differences.stream().map(Difference::where).toList();
    }

    @Test
    void projectionsReadBackFromJson(){
        Projection p = Toys.shard().run(Toys.pumps(150));
        Projection back = Projection.fromJson(p.json());
        assertTrue(diff(p, back, Tolerances.exact()).isEmpty());
        assertEquals(p.json(), back.json());
        var vanillaStyle = projection("v").core("sharded", "copper", 3).rule("waves", true).waves(new WaveState(2, 1.5f, 3)).build();
        assertEquals(vanillaStyle.json(), Projection.fromJson(vanillaStyle.json()).json());
    }
}
