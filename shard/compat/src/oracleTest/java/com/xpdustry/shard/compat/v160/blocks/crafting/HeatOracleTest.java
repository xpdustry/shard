package com.xpdustry.shard.compat.v160.blocks.crafting;

import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.world.blocks.heat.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP34): Erekir's heat as vanilla's, every frame ({@link CraftRun}): heaters, redirectors and routers (their
 * {@code cameFrom} sets and once-a-tick updates), heat crafters, the flux reactor and the heater generator, with their
 * save chunks, block snapshots and vanilla's save loaded by both.
 */
class HeatOracleTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /**
     * Two electric heaters into a redirector, into a heat router whose outputs feed a carbide crucible (with a third
     * heater of its own) and an atmospheric concentrator, next to a second router that makes a cycle with it. The
     * redirector is removed and put back, the router is destroyed and rebuilt, and a heater is disabled.
     */
    @Test
    void heatGoesThroughRoutersIntoCrafters(){
        var run = heatChain();
        run.at(290, () -> {
            var router = (HeatConductor.HeatConductorBuild)run.vanilla(10, 10);
            assertTrue(router.heat >= 5.999f, "the router carries both heaters' heat: " + router.heat);
            assertTrue(((HeatCrafter.HeatCrafterBuild)run.vanilla(10, 13)).heat > 1f, "the concentrator takes a third of it");
        });
        run.at(300, () -> run.remove(7, 10));
        run.at(420, () -> run.place("heat-redirector", 7, 10, 0));
        run.at(600, () -> run.kill(10, 10));
        run.at(700, () -> run.place("heat-router", 10, 10, 0));
        run.at(750, () -> run.enabled(4, 10, false));
        run.at(320, () -> assertEquals(0f, ((HeatConductor.HeatConductorBuild)run.vanilla(10, 10)).heat, 0.0001f,
            "the router got nothing from the gap the redirector left"));
        run.at(640, () -> assertEquals(0f, ((HeatCrafter.HeatCrafterBuild)run.vanilla(10, 13)).heat, "the concentrator lost its heat with the router"));
        run.run(900);

        var router = (HeatConductor.HeatConductorBuild)run.vanilla(10, 10);
        assertTrue(router.heat > 0f, "the rebuilt router carries heat:\n" + String.join("\n", run.vanillaState()));
        assertTrue(router.cameFrom.contains(run.vanilla(7, 10).id), "it tracked where its heat came from");
        assertTrue(run.vanilla(12, 12).items.get(Items.carbide) > 0, "the crucible made carbide");
        assertTrue(run.vanilla(10, 13).liquids.get(Liquids.nitrogen) > 0f, "the concentrator made nitrogen");
    }

    /**
     * The same chain saved mid-ramp, at frame 12, while the heaters still warm up: their heat is in the save, the
     * redirector's, routers' and crafters' isn't, so those load cold and catch up. Both sides load it from the same
     * random and id counter and run 300 frames on.
     */
    @Test
    void heatSavedMidRampLoadsAsVanillas(){
        var run = heatChain();
        run.afterLoad = 300;
        run.at(12, () -> {
            float heat = ((HeatProducer.HeatProducerBuild)run.vanilla(4, 10)).heat;
            assertTrue(heat > 0f && heat < 3f, "saved mid-ramp: " + heat);
        });
        run.run(12);
        assertEquals(3f, ((HeatProducer.HeatProducerBuild)run.vanilla(4, 10)).heat, 0.0001f, "warmed up after the load");
        assertTrue(((HeatConductor.HeatConductorBuild)run.vanilla(10, 10)).heat >= 5.999f, "the router carries heat after the load");
    }

    /** Heaters, a redirector, two routers, a carbide crucible and an atmospheric concentrator, all powered. */
    static CraftRun heatChain(){
        var run = new CraftRun(24, 20);
        run.place("electric-heater", 4, 10, 0).place("power-source", 4, 9);
        run.place("electric-heater", 6, 7, 1).place("power-source", 6, 6);
        run.place("heat-redirector", 7, 10, 0);
        run.place("heat-router", 10, 10, 0);
        run.place("heat-router", 10, 7, 0);
        run.place("carbide-crucible", 13, 10).place("item-source", 15, 10).source(15, 10, "tungsten")
            .place("item-source", 15, 11).source(15, 11, "graphite").place("power-source", 14, 12).place("container", 12, 12);
        run.place("electric-heater", 13, 7, 1).place("power-source", 15, 8);
        run.place("atmospheric-concentrator", 10, 13).place("power-source", 8, 13);
        return run;
    }

    /**
     * A flux reactor heated by an electric heater, then also by a heat source, which saturates it: cooled with cyanogen
     * from a liquid source, it makes power into a battery; when the source is cleared it grows unstable and destroys
     * itself, its explosion off by the rules.
     */
    @Test
    void fluxReactorsRunOnHeatAndCoolant(){
        var run = new CraftRun(24, 20);
        run.rules(r -> {
            r.reactorExplosions = false;
            r.damageExplosions = false;
            r.fire = false;
        }, r -> {
            r.reactorExplosions = false;
            r.damageExplosions = false;
            r.fire = false;
        });
        run.place("electric-heater", 6, 10, 0).place("power-source", 6, 9);
        run.place("flux-reactor", 10, 10).place("battery", 10, 13);
        run.at(150, () -> run.place("heat-source", 13, 10, 2));
        run.place("liquid-source", 10, 7).liquidSource(10, 7, "cyanogen");
        run.at(420, () -> run.liquidSource(10, 7, null));
        run.at(400, () -> assertTrue(((VariableReactor.VariableReactorBuild)run.vanilla(10, 10)).productionEfficiency > 0.5f, "full heat"));
        run.run(900);
        assertFalse(run.vanilla(10, 10) instanceof VariableReactor.VariableReactorBuild, "the reactor destroyed itself");
    }

    /**
     * A neoplasia reactor (heater generator) fed arkycite and water from liquid sources and phase fabric by hand, its heat
     * through a redirector into a carbide crucible; 200 frames and 30 from the save, before its neoplasm fills it.
     */
    @Test
    void heaterGeneratorsHeatCrafters(){
        var run = new CraftRun(24, 16);
        run.afterLoad = 30;
        run.place("neoplasia-reactor", 6, 6, 0).place("battery", 6, 9);
        run.items(6, 6, "phase-fabric", 10);
        run.place("liquid-source", 5, 3).liquidSource(5, 3, "arkycite").place("liquid-source", 7, 3).liquidSource(7, 3, "water");
        run.place("heat-redirector", 10, 6, 0);
        run.place("carbide-crucible", 13, 6).place("power-source", 13, 8).place("item-source", 15, 6).source(15, 6, "tungsten")
            .place("item-source", 15, 7).source(15, 7, "graphite");
        run.run(200);
        assertTrue(((HeaterGenerator.HeaterGeneratorBuild)run.vanilla(6, 6)).heat > 1f, "the reactor heated");
        assertTrue(((HeatCrafter.HeatCrafterBuild)run.vanilla(13, 6)).heat > 1f, "the crucible got its heat");
    }
}
