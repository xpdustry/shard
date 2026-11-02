package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.world.*;

/**
 * One bullet of a {@link Bullets} table, as {@link BulletType}'s hooks see it: vanilla's {@code Bullet} entity. A
 * handle names the bullet by entity id and finds its row again when rows move, so it stays valid while the bullet is
 * live and, once {@link #remove()}d, on the {@linkplain Bullets#limbo() limbo}'s copy until the pool frees it. Handles
 * are cheap and short-lived: make one per operation, never store one in world state (store the {@link #object()}, as
 * vanilla code that keeps a bullet keeps the pooled object).
 */
public final class Bullet{
    private final World world;
    private final Bullets main;
    private int id;
    private Bullets table;
    private int row;
    /** Set while the removal hooks run: vanilla's {@code added} is still true then. */
    private boolean removing;

    Bullet(World world, Bullets main, int row){
        this(world, main, main, row);
    }

    private Bullet(World world, Bullets main, Bullets table, int row){
        this.world = world;
        this.main = main;
        this.table = table;
        this.row = row;
        this.id = table.id[row];
    }

    /** A handle naming no bullet yet, for {@link #rebind}. */
    Bullet(World world, Bullets main){
        this.world = world;
        this.main = main;
        this.table = main;
        this.id = -1;
    }

    /**
     * This handle, moved to the live bullet at {@code row} of the world's table: what a new handle on it would be.
     * {@link BulletUpdate}'s passes move one handle along the table instead of making one per bullet; that is safe
     * because no hook keeps a handle past its call (see the class comment).
     */
    Bullet rebind(int row){
        this.table = main;
        this.row = row;
        this.id = main.id[row];
        this.removing = false;
        return this;
    }

    /** A handle on the live bullet at {@code row} of the world's table. */
    public static Bullet at(World world, int row){
        return new Bullet(world, world.bullets(), row);
    }

    /** A handle on the live bullet with entity id {@code id}, or null if none is live. */
    public static Bullet of(World world, int id){
        int row = world.bullets().rowOf(id);
        return row < 0 ? null : new Bullet(world, world.bullets(), row);
    }

    /**
     * A handle on what pooled object {@code object} holds now, as vanilla code keeping the object sees it: the live
     * bullet using it, else its removed bullet in the {@linkplain Bullets#limbo() limbo}, else null (the pool reset it).
     */
    public static Bullet ofObject(World world, int object){
        if(object < 0) return null;
        Bullets main = world.bullets();
        int row = main.rowOfObject(object);
        if(row >= 0) return new Bullet(world, main, row);
        Bullets limbo = main.limbo();
        row = limbo.rowOfObject(object);
        return row < 0 ? null : new Bullet(world, main, limbo, row);
    }

    public World world(){
        return world;
    }

    /** The table holding the bullet now: the world's while it is live, the limbo once removed. */
    public Bullets table(){
        resolve();
        return table;
    }

    /** Its row in {@link #table()}. */
    public int row(){
        resolve();
        return row;
    }

    private void resolve(){
        Bullets table = this.table;
        if(row < table.size() && table.id[row] == id) return;
        int found = main.rowOf(id);
        if(found >= 0){
            this.table = main;
            this.row = found;
            return;
        }
        Bullets limbo = main.limbo();
        found = limbo.rowOf(id);
        if(found < 0) throw new IllegalStateException("bullet " + id + " was freed: its handle outlived the tick it was removed in");
        this.table = limbo;
        this.row = found;
    }

    public int id(){
        return id;
    }

    /** The pooled object it is ({@link Bullets#object}), which outlives the id when the pool hands it out again. */
    public int object(){
        return table().object[row];
    }

    /** Vanilla's {@code isAdded()}: live, or still running its removal hooks. */
    public boolean isAdded(){
        resolve();
        return table == main || removing;
    }

    public BulletType type(){
        return main.types(world).get(table().type[row]);
    }

    public int team(){
        return table().team[row];
    }

    public void team(int team){
        table().team[row] = team;
    }

    public float x(){
        return table().x[row];
    }

    public float y(){
        return table().y[row];
    }

    public void set(float x, float y){
        Bullets t = table();
        t.x[row] = x;
        t.y[row] = y;
    }

    /** {@code trns(x, y)}: moves by (x, y). */
    public void trns(float x, float y){
        Bullets t = table();
        t.x[row] += x;
        t.y[row] += y;
    }

    public float velX(){
        return table().velX[row];
    }

    public float velY(){
        return table().velY[row];
    }

    public void vel(float x, float y){
        Bullets t = table();
        t.velX[row] = x;
        t.velY[row] = y;
    }

    /** {@code vel.len()}. */
    public float speed(){
        Bullets t = table();
        return Mathf.len(t.velX[row], t.velY[row]);
    }

    public float time(){
        return table().time[row];
    }

    public void time(float time){
        table().time[row] = time;
    }

    public float lifetime(){
        return table().lifetime[row];
    }

    public void lifetime(float lifetime){
        table().lifetime[row] = lifetime;
    }

    public float damage(){
        return table().damage[row];
    }

    public void damage(float damage){
        table().damage[row] = damage;
    }

    public Entity owner(){
        return table().owner[row];
    }

    public void owner(Entity owner){
        table().owner[row] = owner;
    }

    public Entity shooter(){
        return table().shooter[row];
    }

    public float lastX(){
        return table().lastX[row];
    }

    public float lastY(){
        return table().lastY[row];
    }

    public float hitSize(){
        return table().hitSize[row];
    }

    public float fdata(){
        return table().fdata[row];
    }

    public void fdata(float value){
        table().fdata[row] = value;
    }

    public boolean absorbed(){
        return table().absorbed[row];
    }

    public boolean hit(){
        return table().hit[row];
    }

    public void hit(boolean hit){
        table().hit[row] = hit;
    }

    public void keepAlive(boolean keep){
        table().keepAlive[row] = keep;
    }

    /** {@code aimTile} as a tile position, or -1. */
    public int aimTile(){
        return table().aimTile[row];
    }

    public float aimX(){
        return table().aimX[row];
    }

    public float aimY(){
        return table().aimY[row];
    }

    public void aim(float x, float y){
        Bullets t = table();
        t.aimX[row] = x;
        t.aimY[row] = y;
    }

    public float originX(){
        return table().originX[row];
    }

    public float originY(){
        return table().originY[row];
    }

    public int frags(){
        return table().frags[row];
    }

    public void frags(int frags){
        table().frags[row] = frags;
    }

    public Mover mover(){
        return table().mover[row];
    }

    public Entity stickyTarget(){
        return table().stickyTarget[row];
    }

    /** {@code hasCollided(id)}. */
    public boolean hasCollided(int entity){
        return table().hasCollided(row, entity);
    }

    /** {@code collided.add(id)}. */
    public void addCollided(int entity){
        table().addCollided(row, entity);
    }

    /** {@code collided.size}. */
    public int collidedCount(){
        return table().collidedSize[row];
    }

    /** {@code timer(slot, time)}: the bullet's {@code Interval}, against the world's game time. */
    public boolean timer(int slot, float time){
        return table().timer(row, slot, time, world.timers().time());
    }

    /** {@code rotation()}: the velocity's angle, or the stored rotation while the velocity is about zero. */
    public float rotation(){
        Bullets t = table();
        float vx = t.velX[row], vy = t.velY[row];
        // Vec2.isZero(0.001f) is len2 < margin
        return vx * vx + vy * vy < 0.001f ? t.rotation[row] : new Vec2(vx, vy).angle();
    }

    /** {@code rotation(angle)}: {@code vel.setAngle(rotation = angle)}. */
    public void rotation(float angle){
        Bullets t = table();
        t.rotation[row] = angle;
        var vel = new Vec2(t.velX[row], t.velY[row]).setAngle(angle);
        t.velX[row] = vel.x;
        t.velY[row] = vel.y;
    }

    /** {@code initVel(angle, amount)}: {@code vel.trns(angle, amount)}, and the stored rotation. */
    public void initVel(float angle, float amount){
        Bullets t = table();
        var vel = new Vec2().trns(angle, amount);
        t.velX[row] = vel.x;
        t.velY[row] = vel.y;
        t.rotation[row] = angle;
    }

    /** The velocity as a new vector; write it back with {@link #vel(Vec2)}. */
    public Vec2 vel(){
        Bullets t = table();
        return new Vec2(t.velX[row], t.velY[row]);
    }

    public void vel(Vec2 vel){
        vel(vel.x, vel.y);
    }

    /** {@code fin()}: the fraction of its life gone. */
    public float fin(){
        Bullets t = table();
        return t.time[row] / t.lifetime[row];
    }

    /** {@code fout()}. */
    public float fout(){
        return 1f - fin();
    }

    /** {@code fslope()}: 0 at both ends of its life, 1 half way. */
    public float fslope(){
        return (0.5f - Math.abs(fin() - 0.5f)) * 2f;
    }

    /** {@code tileX()}: {@code World.toTile(x)}. */
    public int tileX(){
        return Math.round(x() / 8f);
    }

    public int tileY(){
        return Math.round(y() / 8f);
    }

    public float dst(float x, float y){
        return Mathf.dst(x(), y(), x, y);
    }

    public float angleTo(float x, float y){
        return Angles.angle(x(), y(), x, y);
    }

    /** {@code damageMultiplier()}: the type's, from the owner. */
    public float damageMultiplier(){
        return type().damageMultiplier(this);
    }

    /**
     * Vanilla's generated {@code remove()}: out of {@code Groups.bullet} first (the last row fills the hole), then
     * {@code BulletComp.remove}: {@code despawned} unless it {@link #hit()}, then {@code removed}, then the hit list is
     * cleared, all on the limbo's copy. Removing a removed bullet does nothing.
     */
    public void remove(){
        resolve();
        if(table != main) return;
        BulletType type = type();
        table = main.limbo();
        row = main.remove(row);
        removing = true;
        try{
            if(!table.hit[row]) type.despawned(this);
            type.removed(this);
            table().collidedSize[row()] = 0;
        }finally{
            removing = false;
        }
    }

    /** {@code absorb()}: absorbed by a shield, then removed. */
    public void absorb(){
        table().absorbed[row] = true;
        remove();
    }

    @Override
    public String toString(){
        return "Bullet#" + id;
    }
}
