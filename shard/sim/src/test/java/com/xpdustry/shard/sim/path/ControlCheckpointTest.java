package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forks and checkpoints of a world whose control pathfinder has paths cached (WP71): ground, legged, hovering and
 * naval units of two teams {@code pathfind} ({@link LogicAi}) across walled ground and a lake, while walls go up and
 * come down and goals move, both a little (inside the recompute window) and far (a new request). A fork, and a world
 * restored from the {@code control-paths} section alone or from a whole checkpoint, continue with the same hash every
 * tick. The boundaries fall on ticks with posted work waiting for the next pass, with fields still building (a
 * budget of one step), and with requests whose units are gone.
 */
class ControlCheckpointTest{
    static final String[] TYPES = {"dagger", "atrax", "elude", "risso"};

    static int block(String name){
        return VanillaWorlds.VANILLA.block(name).id();
    }

    /** Edits walls and moves goals from the world's {@code Rand}, after the units ({@link Phase#BUILDINGS}). */
    static final class Edits implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.BUILDINGS;
        }

        @Override
        public void run(World world){
            Rand rand = world.rand();
            TileGrid tiles = world.tiles();
            if(rand.chance(0.3)){
                int x = 8 + rand.random(tiles.width() - 17), y = 8 + rand.random(tiles.height() - 17), pos = tiles.pos(x, y);
                Building there = world.buildings().at(pos);
                if(there != null && there.block().name().equals("copper-wall")){
                    world.buildings().remove(there, Cause.SERVER);
                }else if(there == null && tiles.block(pos) == 0 && !tiles.dangerous(pos) && empty(world, x, y)){
                    world.buildings().create(VanillaWorlds.VANILLA.block("copper-wall"), rand.random(2), x, y, 0, Cause.SERVER);
                }
            }
            ActiveList<Unit> units = world.units();
            for(int i = 0; i < units.size(); i++){
                if(!(units.get(i).controller instanceof LogicAi ai)) continue;
                if(rand.chance(0.004)){
                    // far: a new request
                    ai.moveX = (6 + rand.random(tiles.width() - 13)) * 8f;
                    ai.moveY = (6 + rand.random(tiles.height() - 13)) * 8f;
                }else if(rand.chance(0.01)){
                    // near: the same request, inside the recompute window
                    ai.moveX += 8f;
                }
            }
            // a unit leaves now and then, its request still held
            if(rand.chance(0.003) && units.size() > 4) units.get(rand.random(units.size() - 1)).remove();
        }

        static boolean empty(World world, int x, int y){
            ActiveList<Unit> units = world.units();
            for(int i = 0; i < units.size(); i++) if(units.get(i).within(x * 8f, y * 8f, 16f)) return false;
            return true;
        }
    }

    static World walkers(long seed, int ticks){
        var handlers = new ArrayList<PhaseHandler>();
        Systems builtIn = Systems.builtIn();
        for(Phase phase : Phase.values()) handlers.addAll(builtIn.handlers(phase));
        handlers.add(new Edits());
        World world = VanillaWorlds.world(84, 72, seed, builtIn.withHandlers(handlers));
        TileGrid tiles = world.tiles();
        world.buildings().beginLoad();
        // a lake on the right, and derelict wall lines with gaps across the left
        for(int y = 10; y < 62; y++){
            for(int x = 56; x < 80; x++) tiles.floor(tiles.pos(x, y), block(x < 59 || x > 76 ? "shallow-water" : "deep-water"));
        }
        for(int line = 0; line < 3; line++){
            int x = 14 + line * 13;
            for(int y = 2; y < 70; y++){
                if((y + line * 9) % 23 < 3) continue;
                tiles.block(tiles.pos(x, y), block("stone-wall"));
            }
        }
        world.buildings().endLoad(false);
        world.part(PathService.class).invalidateAll();
        Building processor = world.buildings().create(VanillaWorlds.VANILLA.block("copper-wall"), 1, 2, 2, 0, Cause.SERVER);

        var rand = new Rand(seed);
        for(int i = 0; i < 24; i++){
            String type = TYPES[i % TYPES.length];
            boolean naval = type.equals("risso");
            float x = naval ? (60 + rand.random(14)) * 8f : (4 + rand.random(6)) * 8f, y = (12 + rand.random(48)) * 8f;
            Unit unit = Unit.spawn(world, world.content().unit(type), 1 + i % 2, x, y, 0f);
            var ai = new LogicAi(world);
            ai.control = LUnitControl.pathfind;
            ai.controller = processor;
            ai.controlTimer = 1e9f;
            ai.moveX = naval ? (60 + rand.random(14)) * 8f : (60 + rand.random(18)) * 8f;
            ai.moveY = (6 + rand.random(60)) * 8f;
            unit.controller(ai);
        }
        for(int i = 0; i < ticks; i++) world.tick();
        return world;
    }

    @Test
    void unitsFindTheirWayThroughTheWalls(){
        World world = walkers(41, 900);
        ControlPathfinder control = ControlPathfinder.of(world);
        assertFalse(control.fields().isEmpty(), "no field was built");
        int past = 0;
        ActiveList<Unit> units = world.units();
        for(int i = 0; i < units.size(); i++) if(units.get(i).x > 41 * 8f) past++;
        assertTrue(past >= 6, "only " + past + " units got past the walls");
    }

    @Test
    void aForkContinuesIdentically(){
        World source = walkers(42, 300);
        World fork = source.fork();
        hashes(source, fork, "at the fork");
        for(int i = 0; i < 400; i++){
            source.tick();
            fork.tick();
            hashes(source, fork, "tick " + source.ticks());
        }
    }

    @Test
    void theSectionAloneRestoresThePathfinderExactly(){
        World world = walkers(43, 200);
        ControlPathfinder control = ControlPathfinder.of(world);
        int queued = 0, building = 0, gone = 0;
        var rand = new Rand(43);
        for(int round = 0; round < 6; round++){
            // in half the rounds every unit heads somewhere new, and its field stays mid-build
            control.steps(round % 2 == 0 ? 1 : ControlPathfinder.MAX_STEPS);
            if(round % 2 == 0){
                ActiveList<Unit> units = world.units();
                for(int i = 0; i < units.size(); i++){
                    if(!(units.get(i).controller instanceof LogicAi ai)) continue;
                    ai.moveX = (60 + rand.random(18)) * 8f;
                    ai.moveY = (6 + rand.random(60)) * 8f;
                }
            }
            for(int i = 0; i < 31 + round; i++) world.tick();
            // a unit leaves right before the boundary, its request still held until the next clean-up
            if(round == 3){
                for(ControlPathfinder.Request request : control.unitRequests()){
                    if(request.added()){
                        request.unit.remove();
                        break;
                    }
                }
            }
            if(!control.queue().isEmpty()) queued++;
            for(ControlPathfinder.Field field : control.fields().values()) if(!field.frontier.isEmpty()) building++;
            for(ControlPathfinder.Request request : control.unitRequests()) if(!request.added()) gone++;

            var out = new Writes();
            control.write(world, out);
            World restored = world.fork();
            var reads = new Reads(out.toByteArray());
            restored.part(ControlPathfinder.class).read(restored, reads);
            assertEquals(0, reads.remaining(), "round " + round + ": unread bytes");
            String where = "round " + round;
            hashes(world, restored, where + " restored");
            World fork = world.fork();
            for(int i = 0; i < 70; i++){
                fork.tick();
                restored.tick();
                hashes(fork, restored, where + " +" + (i + 1));
            }
        }
        assertTrue(queued > 0, "no boundary had posted work");
        assertTrue(building > 0, "no boundary had a field mid-build");
        assertTrue(gone > 0, "no boundary held a request of a gone unit");
    }

    @Test
    void aWorldCheckpointCarriesTheSection(){
        World world = walkers(44, 251);
        Capture capture = Checkpoint.capture(world);
        assertTrue(capture.sectionSizes().containsKey("control-paths"), capture.sectionSizes().toString());
        World restored = Checkpoint.decode(capture.encode(Compression.FAST)).restore(world.config());
        hashes(world, restored, "restored");
        for(int i = 0; i < 200; i++){
            world.tick();
            restored.tick();
            hashes(world, restored, "+" + (i + 1));
        }
    }

    @Test
    void anInactivePartIsDerivedAndStoresNothing(){
        World world = new World(WorldConfig.of(com.xpdustry.shard.sim.toy.ToyContent.INSTANCE, 16, 16, 35)
            .withSystems(PathWorlds.systems(List.of(ControlPathfinder.class), new ControlFlush())));
        world.tick();
        ControlPathfinder control = world.part(ControlPathfinder.class);
        assertFalse(control.active());
        assertNull(ControlPathfinder.of(world));
        assertEquals(Persistence.DERIVED, control.persistence());
        Capture capture = Checkpoint.capture(world);
        assertFalse(capture.sectionSizes().containsKey("control-paths"), capture.sectionSizes().toString());
    }

    static void hashes(World a, World b, String where){
        WorldHash ha = a.hash(), hb = b.hash();
        assertEquals(ha, hb, where + ": " + ha.firstDifference(hb).orElse("?"));
    }
}
