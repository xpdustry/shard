package com.xpdustry.shard.sim.fire;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 40: {@link Fires#objectPosition} looks fires up by object instead of scanning every entity. Through 900 ticks
 * of fires started, cooled, burning out and spreading, objects freed and reused, and forks and checkpoints taken along
 * the way, it answers for every object number exactly as the scan did, in the world and in each copy.
 */
class FireObjectsTest{
    /** The scan {@code objectPosition} made before (W2.16), as the reference. */
    static Vec2 scanned(World world, int object){
        var out = new Vec2();
        EntityGroups groups = EntityGroups.of(world);
        for(int i = 0; i < groups.all().size(); i++){
            if(groups.all().get(i) instanceof Fire fire && fire.object == object){
                out.x = fire.x;
                out.y = fire.y;
                return out;
            }
        }
        if(!HazardPool.of(world).removedPosition(object, out)) out.set(0f, 0f);
        return out;
    }

    static List<String> answers(World world, int objects){
        var out = new ArrayList<String>();
        for(int object = -1; object < objects; object++){
            var looked = new Vec2();
            Fires.objectPosition(world, object, looked);
            Vec2 expected = scanned(world, object);
            assertEquals(expected.x + "," + expected.y, looked.x + "," + looked.y, "object " + object + " at tick " + world.ticks());
            out.add(looked.x + "," + looked.y);
        }
        return out;
    }

    @Test
    void theLookupAnswersAsTheScanThroughEveryTransition(){
        World world = VanillaWorlds.world(32, 32, 5);
        // coal burns, so its fires last and spread
        for(int i = 0; i < 4; i++){
            Building container = world.buildings().setBlock(6 + i * 6, 16, VanillaWorlds.VANILLA.block("container"), 1, 0, Cause.SERVER);
            container.items.add(VanillaWorlds.VANILLA.item("coal").id(), 300);
        }
        var random = new SplittableRandom(40);
        // every copy gets the world's inputs, so it must answer as the world does
        var worlds = new ArrayList<World>(List.of(world));
        int forks = 0, restores = 0, reused = 0;
        for(int tick = 0; tick < 900; tick++){
            var started = new ArrayList<Integer>();
            int cooled = -1;
            if(tick < 600){
                for(int i = random.nextInt(3); i > 0; i--) started.add(world.tiles().pos(random.nextInt(32), random.nextInt(32)));
                if(random.nextInt(4) == 0) cooled = world.tiles().pos(random.nextInt(32), random.nextInt(32));
            }
            int free = HazardPool.of(world).freeFires();
            for(World each : worlds){
                for(int tile : started) Fires.create(each, tile);
                if(cooled != -1) Fires.extinguish(each, cooled, 400f);
                each.tick();
            }
            if(HazardPool.of(world).freeFires() < free) reused++;
            int objects = HazardPool.of(world).freeFires() + EntityGroups.of(world).all().size() + 8;
            List<String> answers = answers(world, objects);
            for(World copy : worlds) assertEquals(answers, answers(copy, objects), "a copy at tick " + world.ticks());
            if(tick % 97 == 13){
                worlds.add(world.fork());
                forks++;
            }else if(tick % 97 == 61){
                worlds.add(Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config()));
                restores++;
            }
            // a new copy answers at once, before anything asked it
            assertEquals(answers, answers(worlds.getLast(), objects));
        }
        assertTrue(forks >= 9 && restores >= 9, forks + " forks, " + restores + " restores");
        assertTrue(reused > 0, "a freed object was taken again");
    }
}
