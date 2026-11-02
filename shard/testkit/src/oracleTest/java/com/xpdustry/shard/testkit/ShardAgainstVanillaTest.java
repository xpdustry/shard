package com.xpdustry.shard.testkit;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.testkit.toy.*;
import mindustry.content.*;
import mindustry.type.*;
import mindustry.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shard against vanilla through the whole pipeline. No vanilla kind is ported yet, so the scenarios bind vanilla's
 * blocks to the toy wall kind, with vanilla's own baked health and modules: the layout, teams, health, items, unit ids
 * and positions must then match vanilla's exactly. Bundled maps load on the vanilla epoch, every kind passed through.
 */
class ShardAgainstVanillaTest{
    /** Hand-built blocks and units over vanilla's items and liquids, which the projections name. */
    abstract static class VanillaItems implements ContentEpoch{
        private static final ContentEpoch VANILLA = Epoch.vanilla();

        @Override
        public int items(){
            return VANILLA.items();
        }

        @Override
        public int liquids(){
            return VANILLA.liquids();
        }

        @Override
        public ContentDef get(ContentType type, int id){
            return switch(type){
                case ITEM, LIQUID -> VANILLA.get(type, id);
                default -> ContentEpoch.super.get(type, id);
            };
        }
    }

    static ContentEpoch wallsOf(Block... vanilla){
        var blocks = new ArrayList<BlockDef>();
        blocks.add(new ToyContent.Block(0, "air", KindKey.of("toy-wall"), 1, 0f, false, false, false, false, false));
        for(Block b : vanilla){
            blocks.add(new ToyContent.Block(blocks.size(), b.name, KindKey.of("toy-wall"), b.size, b.health, false, false,
                b.hasItems, b.hasLiquids, b.hasPower));
        }
        UnitType dagger = UnitTypes.dagger;
        var units = List.<UnitDef>of(new ToyContent.Unit(0, dagger.name, dagger.health));
        return new VanillaItems(){
            @Override
            public String hash(){
                return "vanilla-walls";
            }

            @Override
            public int blocks(){
                return blocks.size();
            }

            @Override
            public BlockDef block(int id){
                return blocks.get(id);
            }

            @Override
            public int units(){
                return 1;
            }

            @Override
            public UnitDef unit(int id){
                return units.get(id);
            }
        };
    }

    @Test
    void aStaticLayoutMatchesVanillaLeafForLeaf(){
        Vanilla.boot();
        var content = wallsOf(Blocks.copperWall, Blocks.titaniumWallLarge, Blocks.container, Blocks.liquidTank, Blocks.coreShard);
        Scenario scenario = Scenario.named("walls").world(20, 12)
            .row("copper-wall", 1, 6, 1, 0)
            .place("titanium-wall-large", 3, 4)
            .team("crux").place("copper-wall", 10, 10, 2).place("container", 12, 5)
            .items(12, 5, "copper", 50).items(12, 5, "silicon", 3)
            .place("liquid-tank", 16, 5).liquid(16, 5, "water", 12.5f)
            // sharded needs a core for a unit cap above 0, or vanilla kills the dagger on spawn (UnitComp.java:605)
            .team("sharded").place("core-shard", 16, 9)
            .spawn("dagger", 80f, 72f, 30f)
            .build();
        DiffReport report = Differential.of(scenario).vanilla().candidate(ShardRunner.of(content)).run();
        System.out.println(report.text());
        report.assertNoDiff();
        assertTrue(report.notes().contains("core, wave (not projected by shard)"), report.notes().toString());

        // the vanilla side projects a container's own fields, which the toy wall kind lacks: noted, not failed
        Projection vanilla = OracleRunner.create().run(scenario);
        assertEquals("storage-block", vanilla.building(12, 5).kind());
        assertEquals(Map.of("copper", 50, "silicon", 3), vanilla.building(12, 5).items());
        assertEquals(Map.of("water", 12.5f), vanilla.building(16, 5).liquids());
        assertNotNull(vanilla.unit(11), "buildings take entity ids too: the eleven placed first have 0..10, the dagger 11");
        assertEquals("core-block", vanilla.building(16, 9).kind());
    }

    /**
     * Every default map hosted in survival on both engines, Shard's through {@link ShardMaps} (the real msav loader,
     * generation filters included, unported kinds passed through), compared at tick 0: the layout, base state, modules
     * and rules are vanilla's.
     */
    @Test
    void bundledMapsHostAsOnVanilla(){
        Vanilla.boot();
        // Only what Shard's host doesn't do yet: logic.play() replaces core items with the loadout and heals cores, and
        // the core units a map saved in its entities region (moltenLake, archipelago, debrisField) stay bytes in
        // MapState on Shard instead of becoming units.
        Tolerances tolerances = Tolerances.exact()
            .ignore("building[kind=core-block].items")
            .ignore("building[kind=core-block].health")
            .ignore("unit[alpha]").ignore("unit[beta]").ignore("unit[gamma]");
        for(String map : BundledMaps.DEFAULT){
            Scenario scenario = Scenario.named(map).map(map).build();
            DiffReport report = Differential.of(scenario).vanilla().candidate(ShardRunner.of(Epoch.vanilla())).tolerances(tolerances).run();
            report.assertNoDiff();
            assertTrue(report.notes().contains("core, wave (not projected by shard)"), map + ": " + report.notes());
        }
    }

    @Test
    void aDifferenceAcrossEnginesIsReportedWithBothValues(){
        Vanilla.boot();
        // Shard's toy copper wall gets the wrong health
        var content = wallsOf(Blocks.titaniumWall);
        var wrong = new VanillaItems(){
            @Override
            public String hash(){
                return "wrong";
            }

            @Override
            public int blocks(){
                return 2;
            }

            @Override
            public BlockDef block(int id){
                return id == 0 ? content.block(0) : new ToyContent.Block(1, "titanium-wall", KindKey.of("toy-wall"), 1, 100f, false, false, false, false, false);
            }

            @Override
            public int units(){
                return 0;
            }

            @Override
            public UnitDef unit(int id){
                throw new IndexOutOfBoundsException(id);
            }
        };
        Scenario scenario = Scenario.named("wrong-health").world(8, 8).place("titanium-wall", 2, 2).step(30).build();
        DiffReport report = Differential.of(scenario).vanilla().candidate(ShardRunner.of(wrong)).trace().run();
        report.assertDivergesAt(0);
        assertEquals(List.of("building(2,2).health"), report.differences().stream().map(Difference::where).toList());
        assertEquals(Blocks.titaniumWall.health, ((Number)report.differences().getFirst().reference()).floatValue());
        assertEquals(100f, report.differences().getFirst().candidate());
        System.out.println(report.text());
    }
}
