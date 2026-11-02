package com.xpdustry.shard.sim.combat;

import arc.math.*;
import arc.util.pooling.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.KindRegistry;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.entities.*;
import mindustry.gen.*;
import mindustry.gen.Bullet;
import mindustry.world.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The combat gates' shared harness: a vanilla world and a Shard world that start alike (map, random state, entity id
 * counter, an empty bullet pool), a recorder of the bullets vanilla creates, and the per-frame comparisons.
 */
final class CombatOracle implements AutoCloseable{
    static final ContentEpoch VANILLA = VanillaWorlds.VANILLA;

    final World world;
    /** The entity id counters before anything was made. */
    final int vanillaBase, shardBase;
    /** The bullets vanilla obtained from its pool since the last {@link #vanillaCreated()}, in order. */
    private final List<Bullet> obtained = new ArrayList<>();
    private final IntSetLike shardSeen = new IntSetLike();
    private final Pool<Bullet> pool;

    /**
     * Fresh worlds of {@code size} tiles of {@code floor}; {@code kinds} binds the Shard world's building kinds. Call
     * before spawning anything: the random states and the id counters are aligned here.
     */
    CombatOracle(int size, String floor, KindRegistry kinds){
        Block vanillaFloor = content.block(floor);
        Vanilla.emptyWorld(size, size, vanillaFloor);
        state.rules.disableUnitCap = true;
        state.rules.waves = false;
        // Groups.clear queued frees only the next updatePooling runs; start from an empty bullet pool that records
        Groups.updatePooling();
        Bullet.create();
        pool = Pools.get(Bullet.class, () -> null);
        pool.clear();
        // and from empty fire and puddle pools: their objects keep the timers drawn when they were freed
        HazardPools.empty();
        Pools.set(Bullet.class, new Recorder(obtained));
        world = new World(WorldConfig.of(VANILLA, size, size, 1).withSystems(Systems.builtIn()).withKinds(kinds));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, VANILLA.block(floor).id());
        world.buildings().endLoad(false);
        UnitWorlds.uncapped(world);
        var rules = world.part(com.xpdustry.shard.sim.rules.WorldRules.class).rules;
        rules.waves = false;
        shardBase = world.ids().peek();
        world.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
        // the same absolute ids, which seed per-entity randoms (Mathf.randomSeed(id)), and the same lightning seeds:
        // vanilla's counter is one per JVM, Shard's one per world
        setStatic(EntityGroup.class, "lastId", shardBase);
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
        vanillaBase = lastId();
    }

    private static void setStatic(Class<?> owner, String name, int value){
        try{
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(null, value);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    CombatOracle(int size, String floor){
        this(size, floor, KindRegistry.builtIn().withPassthrough());
    }

    /** Gives vanilla its own bullet pool back. */
    @Override
    public void close(){
        Pools.set(Bullet.class, pool);
    }

    /** One frame of each. */
    void frame(){
        Vanilla.frame();
        world.tick();
    }

    /** The bullets vanilla made since the last call: (relative id, type, origin x and y bits). */
    List<String> vanillaCreated(){
        var out = new ArrayList<String>();
        for(Bullet b : obtained) out.add(describe(b.id - vanillaBase, b.type.id, b.originX, b.originY));
        obtained.clear();
        out.sort(null);
        return out;
    }

    /** The bullets Shard made since the last call, live or in the limbo, described as {@link #vanillaCreated()}. */
    List<String> shardCreated(){
        var out = new ArrayList<String>();
        for(Bullets table : List.of(world.bullets(), world.bullets().limbo())){
            for(int row = 0; row < table.size(); row++){
                if(!shardSeen.add(table.id[row])) continue;
                out.add(describe(table.id[row] - shardBase, table.type[row], table.originX[row], table.originY[row]));
            }
        }
        out.sort(null);
        return out;
    }

    private static String describe(int id, int type, float x, float y){
        return String.format("%08d type %d at %s,%s", id, type, Float.toString(x), Float.toString(y));
    }

    /** Fails with {@code at} unless both random generators are in the same state. */
    void sameRandom(String at){
        assertEquals(Mathf.rand.seed0, world.rand().getState(0), at + ": random state");
        assertEquals(Mathf.rand.seed1, world.rand().getState(1), at + ": random state");
    }

    /** Fails with {@code at} unless both id counters moved alike. */
    void sameIds(String at){
        assertEquals(lastId() - vanillaBase, world.ids().peek() - shardBase, at + ": the entity id counter");
    }

    static void same(String what, float vanilla, float shard){
        if(Float.floatToIntBits(vanilla) != Float.floatToIntBits(shard)){
            fail(what + ": " + shard + ", vanilla " + vanilla);
        }
    }

    /** Vanilla's live bullets in group order, as Shard's rows: id, type, team, position, velocity, time, damage. */
    List<String> vanillaBullets(){
        var out = new ArrayList<String>();
        for(Bullet b : Groups.bullet){
            out.add(bullet(b.id - vanillaBase, b.type.id, b.team.id, b.x, b.y, b.vel.x, b.vel.y, b.time, b.lifetime, b.damage,
                b.collided.size, b.fdata));
        }
        return out;
    }

    List<String> shardBullets(){
        var out = new ArrayList<String>();
        Bullets t = world.bullets();
        for(int r = 0; r < t.size(); r++){
            out.add(bullet(t.id[r] - shardBase, t.type[r], t.team[r], t.x[r], t.y[r], t.velX[r], t.velY[r], t.time[r], t.lifetime[r],
                t.damage[r], t.collidedSize[r], t.fdata[r]));
        }
        return out;
    }

    private static String bullet(int id, int type, int team, float x, float y, float vx, float vy, float time, float lifetime,
                                 float damage, int collided, float fdata){
        return id + " t" + type + " team" + team + " (" + x + ", " + y + ") v(" + vx + ", " + vy + ") time " + time + "/" + lifetime
            + " dmg " + damage + " hits " + collided + " f " + fdata;
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

    /** Vanilla's default bullet pool (capacity 4, max 5000), recording every object it hands out. */
    static final class Recorder extends Pool<Bullet>{
        private static final Constructor<Bullet> NEW;

        static{
            try{
                NEW = Bullet.class.getDeclaredConstructor();
                NEW.setAccessible(true);
            }catch(NoSuchMethodException e){
                throw new ExceptionInInitializerError(e);
            }
        }

        private final List<Bullet> obtained;

        Recorder(List<Bullet> obtained){
            super(4, 5000);
            this.obtained = obtained;
        }

        @Override
        protected Bullet newObject(){
            try{
                return NEW.newInstance();
            }catch(ReflectiveOperationException e){
                throw new AssertionError(e);
            }
        }

        @Override
        public Bullet obtain(){
            Bullet bullet = super.obtain();
            obtained.add(bullet);
            return bullet;
        }
    }

    /** A sorted int set, without hashing. */
    static final class IntSetLike{
        private final TreeSet<Integer> values = new TreeSet<>();

        boolean add(int value){
            return values.add(value);
        }
    }
}
