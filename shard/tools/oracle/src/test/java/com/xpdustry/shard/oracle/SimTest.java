package com.xpdustry.shard.oracle;

import arc.util.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.world.blocks.distribution.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/** Short scenarios stepped headless at the server's time step. */
class SimTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void framesUseTheServerTimeStep(){
        Sim sim = Sim.empty(8, 8).step(60);
        assertEquals(60, sim.frames());
        assertEquals(60.0, state.tick, 1e-9);
        assertEquals(1f, Time.delta);
    }

    /**
     * item source -> 10 conveyors -> container: items delivered in 10 seconds after a 5 second warm-up.
     * A 2x2 block placed at (x, y) covers x..x+1, y..y+1.
     */
    static int conveyorThroughput(String conveyor){
        Sim sim = Sim.empty(16, 5);
        sim.place("item-source", 1, 2, 0).configure(Items.copper);
        for(int x = 2; x < 12; x++) sim.place(conveyor, x, 2, 0);
        Building container = sim.place("container", 12, 2, 0);
        sim.step(300);
        int before = container.items.get(Items.copper);
        sim.step(600);
        return container.items.get(Items.copper) - before;
    }

    @Test
    void conveyorThroughputMatchesVanillaStats(){
        int basic = conveyorThroughput("conveyor");
        int titanium = conveyorThroughput("titanium-conveyor");
        //the basic conveyor moves exactly its displayed 5 items/s
        assertEquals(((Conveyor)Blocks.conveyor).displayedSpeed * 10, basic);
        //the titanium conveyor's stat says 10/s, but it moves 11.3/s: stats are rounded, the simulation is the truth
        assertEquals(10f, ((Conveyor)Blocks.titaniumConveyor).displayedSpeed);
        assertEquals(113, titanium);
        assertEquals(basic, conveyorThroughput("conveyor"), "the same scenario gives the same count");
    }

    @Test
    void graphitePressCrafts(){
        Sim sim = Sim.empty(8, 8);
        sim.place("item-source", 2, 3, 0).configure(Items.coal);
        Building press = sim.place("graphite-press", 3, 3, 0);
        int frames = sim.until(() -> press.items.get(Items.graphite) > 0, 600);
        assertTrue(frames > 0, "no graphite after 600 frames");
        //the press needs 2 coal per graphite and crafts in 90 ticks
        assertEquals(90, ((mindustry.world.blocks.production.GenericCrafter)Blocks.graphitePress).craftTime);

        Sim.State state = sim.state();
        Oracle.BuildingView view = state.building(3, 3);
        assertEquals("graphite-press", view.block());
        assertTrue(view.items().containsKey("graphite"));
        assertNotNull(view.fields().get("progress"));
        assertTrue(state.json().contains("\"graphite-press\""));
    }

    @Test
    void hostedMapSteps(){
        Sim sim = Sim.host("fortress");
        double before = state.wavetime;
        sim.step(120);
        assertEquals(before - 120, state.wavetime, 1e-3);
        assertFalse(sim.state().coreItems().isEmpty());
    }
}
