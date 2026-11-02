package com.xpdustry.shard.sim.fire;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The objects of vanilla's {@code Fire} and {@code Puddle} pools, beside their ids in {@link EntityIds}. Arc's
 * {@code Pool.free} always calls {@code reset()}, and these two classes' generated {@code reset()} draw from the random:
 * a fire its animation frame and its damage, fireball and spread timers, a puddle its effect and ripple times. So each
 * free draws, in {@code Groups.updatePooling}'s order, and a fire obtained from the pool starts with the timers drawn
 * when it was freed.
 *
 * <p>Ids and the random are separate streams, and no other pooled class draws in {@code reset()} (javap of v160.7's
 * {@code Bullet} and {@code WeatherState}), so the draws can follow {@link EntityIds#flushFrees()} instead of
 * interleaving with it: this part queues the fire and puddle frees in removal order, and {@link Update} replays their
 * resets right after {@code Pooling}.
 *
 * <p>It also numbers the fire objects ({@link Fire#object}), as {@code Bullets} does bullets: code that keeps a fire
 * across ticks (a liquid turret's target) keeps vanilla's object, which reads where it burnt until the pool resets it,
 * then (0, 0), then wherever the next fire to take it burns ({@link Fires#objectPosition}).
 *
 * <p>{@link Persistence#AUTHORITATIVE}: the queue, the free fires' timers and objects, the removed fires' positions and
 * the object counter. A free puddle keeps nothing a server reads.
 */
@Register
public final class HazardPool implements WorldPart, Checkpointable{
    private static final int FIRE = 0, PUDDLE = 1;

    /** {@link #FIRE} or {@link #PUDDLE} per queued free, in removal order. */
    private IntSeq queue = new IntSeq();
    /** Per free fire, last freed last: its damage, fireball and spread timers. */
    private float[] fires = new float[0];
    private int freeFires;
    /** Per free fire, as {@link #fires}: its object number. */
    private int[] fireObjects = new int[0];
    /** The number the next new {@code Fire} object gets. */
    private int nextFireObject;
    /** Per queued fire free, in removal order: its object and its x and y as float bits, kept until its reset. */
    private IntSeq limbo = new IntSeq();

    public HazardPool(){
    }

    private HazardPool(HazardPool source){
        queue = source.queue.copy();
        fires = Arrays.copyOf(source.fires, source.freeFires * 3);
        freeFires = source.freeFires;
        fireObjects = Arrays.copyOf(source.fireObjects, source.freeFires);
        nextFireObject = source.nextFireObject;
        limbo = source.limbo.copy();
    }

    public static HazardPool of(World world){
        HazardPool pool = world.find(HazardPool.class);
        if(pool == null) throw new IllegalStateException("this world has no " + HazardPool.class.getName() + " part");
        return pool;
    }

    /** How many free fire objects the pool holds. */
    public int freeFires(){
        return freeFires;
    }

    /** {@code Groups.queueFree} of a puddle; {@link EntityIds#queueFree} records its id. Fires go through {@link #queueReset(Fire)}. */
    public void queueReset(Pooled pool){
        queue.add(switch(pool){
            case PUDDLE -> PUDDLE;
            case FIRE -> throw new IllegalArgumentException("a fire's free keeps its object: queueReset(Fire)");
            default -> throw new IllegalArgumentException(pool + " draws nothing when reset");
        });
    }

    /** {@code Groups.queueFree} of {@code fire}, whose object keeps its position until the reset. */
    void queueReset(Fire fire){
        queue.add(FIRE);
        limbo.add(fire.object, Float.floatToRawIntBits(fire.x), Float.floatToRawIntBits(fire.y));
    }

    /** Gives a fire obtained from the pool the object and the timers it was reset with. */
    void popFire(Fire fire){
        int at = --freeFires * 3;
        fire.damageTimer = fires[at];
        fire.fireballTimer = fires[at + 1];
        fire.spreadTimer = fires[at + 2];
        fire.object = fireObjects[freeFires];
    }

    /** A new {@code Fire} object's number. */
    int newFireObject(){
        return nextFireObject++;
    }

    /**
     * Where removed fire object {@code object} stands until its reset: sets {@code out} and returns true, or returns
     * false if no queued free holds it.
     */
    boolean removedPosition(int object, Vec2 out){
        for(int i = limbo.size - 3; i >= 0; i -= 3){
            if(limbo.items[i] == object){
                out.x = Float.intBitsToFloat(limbo.items[i + 1]);
                out.y = Float.intBitsToFloat(limbo.items[i + 2]);
                return true;
            }
        }
        return false;
    }

    /** {@code reset()} of every queued free, in order: each draws, and a fire is kept if the pool has room. */
    void flush(World world){
        Rand rand = world.rand();
        int removed = 0;
        for(int i = 0; i < queue.size; i++){
            if(queue.items[i] == FIRE){
                // animation, damageTimer, fireballTimer, (the id), spreadTimer
                Mathf.random(rand, Fire.FRAMES - 1);
                float damage = Mathf.random(rand, Fire.DAMAGE_DELAY);
                float fireball = Mathf.random(rand, Fire.FIREBALL_DELAY);
                float spread = Mathf.random(rand, Fire.SPREAD_DELAY);
                int object = limbo.items[removed++ * 3];
                if(freeFires < EntityIds.POOL_MAX){
                    if(fires.length < (freeFires + 1) * 3) fires = Arrays.copyOf(fires, Math.max(12, fires.length * 2));
                    if(fireObjects.length < freeFires + 1) fireObjects = Arrays.copyOf(fireObjects, Math.max(4, fireObjects.length * 2));
                    fires[freeFires * 3] = damage;
                    fires[freeFires * 3 + 1] = fireball;
                    fires[freeFires * 3 + 2] = spread;
                    fireObjects[freeFires] = object;
                    freeFires++;
                }
            }else{
                // effectTime, (the id), lastRipple = Time.time + Mathf.random(40f)
                Mathf.random(rand, 50f);
                Mathf.random(rand, 40f);
            }
        }
        queue.clear();
        limbo.clear();
    }

    @Override
    public WorldPart copy(CopyContext context){
        return new HazardPool(this);
    }

    @Override
    public void hash(Hasher hasher){
        hasher.ints(queue.items, 0, queue.size).i(freeFires).floats(fires, 0, freeFires * 3);
        // the objects only once a fire was made: a world without fires hashes as it did before they were numbered
        if(nextFireObject != 0) hasher.i(nextFireObject).ints(fireObjects, 0, freeFires).ints(limbo.items, 0, limbo.size);
    }

    @Override
    public void write(CheckpointOut out){
        out.intSeq("queue", queue).floats("fires", fires, freeFires * 3).i("freeFires", freeFires)
            .ints("fireObjects", fireObjects, freeFires).i("nextFireObject", nextFireObject).intSeq("limbo", limbo);
    }

    @Override
    public void read(CheckpointIn in){
        queue = in.intSeq("queue");
        fires = in.floats("fires");
        freeFires = in.i("freeFires");
        fireObjects = in.ints("fireObjects");
        nextFireObject = in.i("nextFireObject");
        limbo = in.intSeq("limbo");
        if(queue == null || fires == null || fires.length != freeFires * 3 || fireObjects == null || fireObjects.length != freeFires
            || limbo == null || limbo.size != queue.count(FIRE) * 3){
            throw new CheckpointException("the fire pool is corrupt");
        }
    }

    /** {@code Groups.updatePooling}'s resets of fires and puddles, right after {@code Pooling} frees their ids. */
    @Register
    public static final class Update implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.PHYSICS;
        }

        @Override
        public int order(){
            return -299;
        }

        @Override
        public void run(World world){
            for(WorldPart part : world.parts()){
                if(part instanceof HazardPool pool) pool.flush(world);
            }
        }
    }
}
