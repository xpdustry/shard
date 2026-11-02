package com.xpdustry.shard.sim.fire;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 40's measurement: wave turrets each holding a burning fire as their target, beside growing numbers of other
 * entities in {@code Groups.all} (puddles, which with fires and weather are all it holds). It prints the cost of a tick
 * and of one {@link Fires#objectPosition}, and asserts that a lookup doesn't grow with the entities: W2.16's scan of
 * every entity did, for every turret every tick.
 */
@Tag("slow")
class FireLookupBench{
    record Row(int turrets, int fires, int entities, double tickMicros, double lookupNanos){
    }

    static Row measure(int turrets, int others){
        World world = VanillaWorlds.world(160, 160, 7);
        world.part(WorldRules.class).rules.disableUnitCap = true;
        int water = VanillaWorlds.VANILLA.liquid("water").id(), coal = VanillaWorlds.VANILLA.item("coal").id();
        var waves = new ArrayList<TurretBuild>();
        var burning = new ArrayList<Integer>();
        for(int i = 0; i < turrets; i++){
            int x = 4 + (i % 16) * 9, y = 4 + (i / 16) * 9;
            waves.add((TurretBuild)world.buildings().setBlock(x, y, VanillaWorlds.VANILLA.block("wave"), 1, 0, Cause.SERVER));
            Building container = world.buildings().setBlock(x + 4, y, VanillaWorlds.VANILLA.block("container"), 1, 0, Cause.SERVER);
            container.items.add(coal, 300);
            burning.add(world.tiles().pos(x + 4, y));
        }
        var wet = new ArrayList<Integer>();
        for(int i = 0; i < others; i++) wet.add(world.tiles().pos(4 + i % 150, 90 + i / 150));
        Runnable feed = () -> {
            for(TurretBuild wave : waves) wave.liquids.add(water, 10f - wave.liquids.get(water));
            for(int tile : burning) Fires.create(world, tile);
            // puddles evaporate: topped up every tick
            for(int tile : wet) Puddles.deposit(world, tile, tile, water, 1f, false, false);
        };
        for(int tick = 0; tick < 120; tick++){
            feed.run();
            world.tick();
        }
        int held = 0;
        for(TurretBuild wave : waves) if(wave.targetFire != -1) held++;
        assertEquals(turrets, held, "every wave holds a fire");

        long elapsed = 0;
        for(int tick = 0; tick < 300; tick++){
            feed.run();
            long started = System.nanoTime();
            world.tick();
            elapsed += System.nanoTime() - started;
        }
        int[] objects = new int[turrets];
        for(int i = 0; i < turrets; i++) objects[i] = waves.get(i).targetFire;
        var at = new Vec2();
        float sum = 0f;
        int lookups = 400_000;
        long started = System.nanoTime();
        for(int i = 0; i < lookups; i++){
            Fires.objectPosition(world, objects[i % turrets], at);
            sum += at.x;
        }
        long looked = System.nanoTime() - started;
        assertTrue(sum > 0f);
        ActiveList<Entity> all = EntityGroups.of(world).all();
        int fires = 0;
        for(int i = 0; i < all.size(); i++) if(all.get(i) instanceof Fire) fires++;
        return new Row(turrets, fires, all.size(), elapsed / 300 / 1e3, (double)looked / lookups);
    }

    @Test
    void aLookupCostsTheSameHoweverManyEntities(){
        // warm-up rounds, so the measured ones are compiled
        for(int i = 0; i < 3; i++) measure(32, 1_000);
        var rows = new ArrayList<Row>();
        int[] puddles = {0, 1_000, 4_000};
        for(int turrets : new int[]{8, 32, 128}){
            for(int others : puddles) rows.add(measure(turrets, others));
        }
        System.out.println("turrets  fires  entities  tick (us)  lookup (ns)");
        for(Row row : rows){
            System.out.printf("%7d  %5d  %8d  %9.1f  %11.1f%n", row.turrets(), row.fires(), row.entities(), row.tickMicros(), row.lookupNanos());
        }
        // per turret count: among the most entities, a lookup costs about what it did among the fewest
        for(int i = 0; i < rows.size(); i += puddles.length){
            Row fewest = rows.get(i), most = rows.get(i + puddles.length - 1);
            assertTrue(most.lookupNanos() < 4 * fewest.lookupNanos() + 50, "a lookup among " + most.entities() + " entities took "
                + most.lookupNanos() + " ns, among " + fewest.entities() + " " + fewest.lookupNanos() + " ns");
        }
    }
}
