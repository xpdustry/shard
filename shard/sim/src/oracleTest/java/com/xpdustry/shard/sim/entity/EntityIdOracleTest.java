package com.xpdustry.shard.sim.entity;

import arc.math.*;
import arc.util.Interval;
import arc.util.Time;
import arc.util.pooling.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.entities.units.AIController;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Entity ids against vanilla: the same script of unit spawns, bullet creates and removes, and pool flushes
 * ({@code Groups.updatePooling}) gives every unit and bullet the same id, relative to the first, in vanilla and in
 * Shard. Units also draw the same {@code Mathf.rand} values at creation.
 */
class EntityIdOracleTest{
    static final List<String> TYPES = List.of("dagger", "flare", "mega", "latum", "atrax", "quell-missile", "arkyid",
        "toxopid", "dummy");

    @Test
    void unitsAndPooledBulletsGetVanillasIds(){
        for(long seed = 0; seed < 20; seed++){
            Vanilla.emptyWorld(32, 32);
            // a coreless team's cap is 0; cap deaths are TeamUnits' business, not this test's
            state.rules.disableUnitCap = true;
            // Groups.clear queues frees that only the next updatePooling runs; start from an empty bullet pool
            Groups.updatePooling();
            Bullet.create();
            Pools.get(Bullet.class, () -> null).clear();
            int vanillaBase = lastId();

            World world = UnitWorlds.world(32, 32, seed, Systems.builtIn());
            int shardBase = world.ids().peek();

            var random = new SplittableRandom(seed);
            var vanillaBullets = new ArrayList<Bullet>();
            var shardBullets = new ArrayList<Integer>();
            var vanillaUnits = new ArrayList<mindustry.gen.Unit>();
            var shardUnits = new ArrayList<com.xpdustry.shard.sim.unit.Unit>();
            for(int step = 0; step < 400; step++){
                String at = "seed " + seed + " step " + step;
                int roll = random.nextInt(20);
                if(roll < 3){
                    String name = TYPES.get(random.nextInt(TYPES.size()));
                    mindustry.gen.Unit unit = content.unit(name).spawn(Team.sharded, 80f, 80f);
                    var shard = com.xpdustry.shard.sim.unit.Unit.spawn(world, VanillaWorlds.VANILLA.unit(name), 1, 80f, 80f, 0f);
                    vanillaUnits.add(unit);
                    shardUnits.add(shard);
                    assertEquals(unit.id - vanillaBase, shard.id() - shardBase, at + ": " + name);
                }else if(roll < 4 && !vanillaUnits.isEmpty()){
                    int victim = random.nextInt(vanillaUnits.size());
                    vanillaUnits.remove(victim).remove();
                    shardUnits.remove(victim).remove();
                }else if(roll < 12){
                    Bullet bullet = UnitTypes.dagger.weapons.first().bullet.create(null, Team.sharded, 80f, 80f, 0f);
                    assertNotNull(bullet);
                    vanillaBullets.add(bullet);
                    int row = world.bullets().add(world.ids(), 0, 1, 80f, 80f, 0f, 0f, 60f, 9f, null);
                    shardBullets.add(world.bullets().id[row]);
                    assertEquals(bullet.id - vanillaBase, world.bullets().id[row] - shardBase, at + ": bullet");
                }else if(roll < 18 && !vanillaBullets.isEmpty()){
                    int victim = random.nextInt(vanillaBullets.size());
                    vanillaBullets.remove(victim).remove();
                    int id = shardBullets.remove(victim);
                    world.bullets().remove(rowOf(world, id));
                }else{
                    Groups.updatePooling();
                    world.ids().flushFrees();
                }
                assertEquals(lastId() - vanillaBase, world.ids().peek() - shardBase, at + ": the counter");
            }
        }
    }

    @Test
    void creationDrawsVanillasRandomValues(){
        Vanilla.emptyWorld(32, 32);
        state.rules.disableUnitCap = true;
        World world = UnitWorlds.world(32, 32, 1, Systems.builtIn());
        world.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
        // the AI timers are relative to Time.time, Shard's tick count here
        Time.time = 0f;
        for(int i = 0; i < 40; i++){
            String name = TYPES.get(i % TYPES.size());
            mindustry.gen.Unit unit = content.unit(name).spawn(Team.sharded, 80f + i, 80f);
            var shard = com.xpdustry.shard.sim.unit.Unit.spawn(world, VanillaWorlds.VANILLA.unit(name), 1, 80f + i, 80f, 0f);
            assertEquals(Float.floatToIntBits(field(unit, "resupplyTime")), Float.floatToIntBits(shard.resupplyTime), name + " " + i);
            if(unit instanceof Crawlc){
                assertEquals(field(unit, "crawlTime"), ((MoveShape.Crawl)shard.shape).crawlTime, name + " " + i);
            }
            if(unit instanceof Legsc legged){
                var legs = (MoveShape.Legs)shard.shape;
                assertEquals(field(unit, "totalLength"), legs.totalLength, name + " " + i + ": totalLength");
                assertEquals(legged.legs().length, legs.legs.length, name);
                for(int leg = 0; leg < legs.legs.length; leg++){
                    var v = legged.legs()[leg];
                    var s = legs.legs[leg];
                    assertArrayEquals(new float[]{v.joint.x, v.joint.y, v.base.x, v.base.y},
                        new float[]{s.joint.x, s.joint.y, s.base.x, s.base.y}, name + " leg " + leg);
                }
            }
            if(unit.controller() instanceof AIController ai){
                float[] timers = ((Interval)value(ai, AIController.class, "timer")).getTimes();
                assertArrayEquals(timers, ((AiController)shard.controller).timers, name + " " + i + ": AI timers at Time.time 0");
            }else{
                assertInstanceOf(NoAi.class, shard.controller, name + ": no AI, no timers");
            }
            assertEquals(unit.mounts.length, shard.mounts.length, name);
            assertEquals(unit.maxHealth, shard.maxHealth, name);
            assertEquals(unit.elevation, shard.elevation, name);
            assertEquals(unit.collisionLayer(), shard.collisionLayer(), name);
            assertEquals(unit.hasPhysicsRef, shard.hasPhysicsRef, name);
        }
        assertEquals(Mathf.rand.seed0, world.rand().getState(0));
    }

    /** A generated entity field that has no accessor. */
    static float field(Object entity, String name){
        return (Float)value(entity, entity.getClass(), name);
    }

    /** A field of {@code owner} or a superclass, read past its access modifier. */
    static Object value(Object object, Class<?> owner, String name){
        for(Class<?> type = owner; type != null; type = type.getSuperclass()){
            try{
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            }catch(NoSuchFieldException e){
                // a superclass may declare it
            }catch(IllegalAccessException e){
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("no field " + name + " in " + owner);
    }

    static int rowOf(World world, int id){
        for(int row = 0; row < world.bullets().size(); row++) if(world.bullets().id[row] == id) return row;
        throw new AssertionError("no bullet " + id);
    }

    static int lastId(){
        try{
            Field field = EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }
}
