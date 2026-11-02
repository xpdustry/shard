package com.xpdustry.shard.compat.v160.blocks.production;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.Unit;
import arc.struct.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP35): a bundled Serpulo map's drills, run frame by frame from the map's own save for {@value #FRAMES} frames
 * ({@link ProductionRun#load}): Salt Flats' 213 mechanical and 21 pneumatic drills, its two water extractors, and the
 * conveyors and cores they feed, with every building and the random compared after every frame, then the save chunks.
 *
 * <p>Pins, so that the gate is about production: the map's units are removed on both sides (vanilla's physics process
 * starts over, as {@code WaveMapsOracleTest} does), the wave timer is off (waves and their AI are W2.11's and W2.17's
 * gates), and the map's buildings whose kinds Shard doesn't port yet and that update are removed on both sides, as
 * {@code M0Run} does; they are listed in the output.
 */
class ProductionMapOracleTest{
    static final String MAP = "serpulo/saltFlats";
    static final int FRAMES = 900;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void saltFlatsDrillsRunFromTheMapsOwnSave(){
        var run = ProductionRun.load(MAP);
        run.compare("loaded, as saved");

        // the map's units
        for(mindustry.gen.Unit unit : Groups.unit.copy(new Seq<>())) unit.remove();
        unitPhysics.reset();
        try{
            var pending = mindustry.async.PhysicsProcess.class.getDeclaredField("pendingAdds");
            pending.setAccessible(true);
            ((Seq<?>)pending.get(unitPhysics)).clear();
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
        unitPhysics.init();
        var units = new ArrayList<Unit>();
        for(int i = 0; i < run.shard.units().size(); i++) units.add(run.shard.units().get(i));
        for(Unit unit : units) unit.remove();

        // no waves
        state.rules.waveTimer = false;
        run.shard.part(WorldRules.class).rules.waveTimer = false;

        // unported buildings that update
        var removed = new TreeMap<String, Integer>();
        var tiles = run.shard.tiles();
        var at = new ArrayList<int[]>();
        for(int pos = 0; pos < tiles.size(); pos++){
            if(run.shard.buildings().at(pos) instanceof PassthroughBuilding b && b.tile() == pos && run.vanilla(b.tileX(), b.tileY()).block.update){
                at.add(new int[]{b.tileX(), b.tileY()});
            }
        }
        for(int[] xy : at){
            removed.merge(world.tile(xy[0], xy[1]).block().name, 1, Integer::sum);
            run.remove(xy[0], xy[1]);
        }
        System.out.println(MAP + ": removed " + units.size() + " units and the unported buildings that update: " + removed);

        // what the drills and the cores hold now
        int drills = 0, extractors = 0;
        int before = 0;
        for(var build : run.buildings()){
            if(build instanceof mindustry.world.blocks.production.Drill.DrillBuild d && d.dominantItem != null) drills++;
            if(build instanceof mindustry.world.blocks.production.SolidPump.SolidPumpBuild) extractors++;
            if(build.items != null) before += build.items.total();
        }
        assertTrue(drills >= 200, "the map's drills stand, on ore: " + drills);
        assertEquals(2, extractors, "the map's water extractors stand");

        run.frames(FRAMES);

        int offloaded = 0;
        for(var build : run.buildings()){
            if(build instanceof mindustry.world.blocks.production.Drill.DrillBuild d && d.progress > 0f) offloaded++;
        }
        int after = 0;
        for(var build : run.buildings()) if(build.items != null) after += build.items.total();
        System.out.println(MAP + ": " + FRAMES + " frames exact; " + offloaded + " drills drilling; items held " + before + " -> " + after);
        assertTrue(offloaded >= drills / 2, "most drills drilled: " + offloaded + " of " + drills);
    }
}
