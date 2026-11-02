package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A laser or continuous turret's {@code Seq<BulletEntry>}: the bullets it holds in its barrel, each kept as its pooled
 * object ({@link Bullet#object()}), as vanilla holds the {@code Bullet} object, with its offset, angle and life.
 */
public final class BulletEntries implements Checkpointable{
    int size;
    int[] objects = new int[0];
    float[] x = new float[0], y = new float[0], rotation = new float[0], life = new float[0];

    public int size(){
        return size;
    }

    public boolean any(){
        return size > 0;
    }

    /** {@code bullets.add(new BulletEntry(bullet, x, y, rotation, life))}. */
    void add(Bullet bullet, float x, float y, float rotation, float life){
        if(size == objects.length){
            int capacity = Math.max(4, size * 2);
            objects = Arrays.copyOf(objects, capacity);
            this.x = Arrays.copyOf(this.x, capacity);
            this.y = Arrays.copyOf(this.y, capacity);
            this.rotation = Arrays.copyOf(this.rotation, capacity);
            this.life = Arrays.copyOf(this.life, capacity);
        }
        objects[size] = bullet.object();
        this.x[size] = x;
        this.y[size] = y;
        this.rotation[size] = rotation;
        this.life[size] = life;
        size++;
    }

    /** The bullet of entry {@code i} as vanilla's held object sees it now, or null once the pool has reset it. */
    Bullet bullet(World world, int i){
        return Bullet.ofObject(world, objects[i]);
    }

    /**
     * {@code bullets.removeAll(b -> !b.bullet.isAdded() || b.bullet.type == null || b.bullet.owner != owner …)}: keeps
     * the order of the rest. With {@code checkLife}, an entry whose life ran out goes too ({@code LaserTurret}).
     */
    void removeDead(World world, Object owner, boolean checkLife){
        int kept = 0;
        for(int i = 0; i < size; i++){
            Bullet bullet = bullet(world, i);
            if(bullet == null || !bullet.isAdded() || bullet.owner() != owner || (checkLife && life[i] <= 0f)) continue;
            objects[kept] = objects[i];
            x[kept] = x[i];
            y[kept] = y[i];
            rotation[kept] = rotation[i];
            life[kept] = life[i];
            kept++;
        }
        size = kept;
    }

    BulletEntries copy(){
        var out = new BulletEntries();
        out.size = size;
        out.objects = objects.clone();
        out.x = x.clone();
        out.y = y.clone();
        out.rotation = rotation.clone();
        out.life = life.clone();
        return out;
    }

    void hash(Hasher hasher){
        hasher.i(size);
        for(int i = 0; i < size; i++) hasher.i(objects[i]).f(x[i]).f(y[i]).f(rotation[i]).f(life[i]);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("size", size).ints("objects", objects, size).floats("x", x, size).floats("y", y, size)
            .floats("rotation", rotation, size).floats("life", life, size);
    }

    @Override
    public void read(CheckpointIn in){
        size = in.i("size");
        objects = in.ints("objects");
        x = in.floats("x");
        y = in.floats("y");
        rotation = in.floats("rotation");
        life = in.floats("life");
        if(objects.length != size || x.length != size || y.length != size || rotation.length != size || life.length != size){
            throw new CheckpointException("turret bullet entries differ in length");
        }
    }
}
