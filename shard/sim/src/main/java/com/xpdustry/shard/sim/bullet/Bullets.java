package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Every live bullet, as one dense struct-of-arrays table in vanilla's {@code Groups.bullet} order (ARCHITECTURE.md §3
 * "Tables"; note 21 measured 1.72 ms vs 2.47 ms for 200k bullets against objects). Bullets have no wire identity,
 * since clients simulate them, but each still draws a vanilla entity id, which keeps unit ids in step with vanilla.
 * The columns are {@code BulletComp}'s fields and those of the components it imports ({@code TimedComp},
 * {@code HitboxComp}, {@code TimerComp}…), by vanilla's names; {@link BulletType} holds what a bullet does, and
 * {@link Bullet} is the handle its hooks get.
 *
 * <p>Row order follows {@code EntityGroup}: {@link #remove(int)} moves the last row into the hole and steps the
 * cursor back, and rows added while iterating are visited in the same pass (frags included, note 21 §10).
 *
 * <p>Vanilla pools its {@code Bullet} objects, and the table replays the pool:
 * <ul>
 *   <li>ids ({@link EntityIds#obtain}, {@link EntityIds#queueFree}): a new bullet takes a free object's id, or draws
 *   one if the pool is empty, and a removed one draws a fresh id when the next tick frees it;</li>
 *   <li>the object itself ({@link #object}): code that keeps a bullet across ticks keeps the object, which may be
 *   handed out again;</li>
 *   <li>its {@code Interval} ({@link #timer}), which {@code reset()} leaves alone, so a reused bullet starts with the
 *   times its last user left;</li>
 *   <li>a removed bullet's state until {@code Groups.updatePooling} resets it: removed rows wait in the
 *   {@linkplain #limbo() limbo}, in removal order, which is the pool's free queue. Their hooks run there, and what
 *   still holds the object (a point-defense weapon's target) reads it there until {@link #flushPool} frees it.</li>
 * </ul>
 * The table draws from the {@link EntityIds} it was made with, the world's.
 *
 * <p>{@link #updatePhysics} is {@code Groups.bullet.updatePhysics()}: it fills the group's tree and records last
 * positions. The tree ({@link #intersectQuad}) is built on the first query after that, from the objects the fill saw;
 * rows added later are missing until the next fill, and removed rows stay in it from the limbo, as vanilla's tree
 * keeps them until the next fill. It is derived, dropped at the end of each tick ({@code IndexExpiry}).
 *
 * <p>Owners: WP12 (the table, ids, the grid), WP41 (the columns, update and collision). A new column needs a line in
 * {@link #allocate}, {@link #grow}, {@link #copyRow}, the copy constructor, {@link #obtain}, {@link #writeRows},
 * {@link #readRows} and {@link #hashRows}.
 */
public final class Bullets{
    /** {@code BulletType.hitSize}'s default. */
    public static final float DEFAULT_HIT_SIZE = 4f;
    /** {@code TimerComp}'s {@code Interval(6)}. */
    public static final int TIMERS = 6;

    private int size;
    private int index;

    public int[] id;
    /**
     * Which pooled {@code Bullet} object the row is: a number that, unlike the id, stays with the object when the pool
     * hands it out again. Vanilla code that keeps a bullet across ticks (a continuous weapon's mount, a flak shell's
     * timer, a point-defense target) keeps the object, so it keeps this.
     */
    public int[] object;
    /** The bullet type's content id. */
    public int[] type;
    public int[] team;
    public float[] x, y, velX, velY;
    /** {@code BulletComp.rotation}, the angle set at creation; {@link Bullet#rotation()} prefers the velocity's. */
    public float[] rotation;
    public float[] time, lifetime, damage;
    /** The owner and the shooter, or null; may outlive their targets, so readers check {@code isAdded()}. */
    public Entity[] owner, shooter;
    /** {@code HitboxComp}: the position at the last {@link #updatePhysics}, and the movement before it. */
    public float[] lastX, lastY, deltaX, deltaY;
    /** {@code type.hitSize}, the side of the square hitbox. */
    public float[] hitSize;
    /** Whether the last {@link #updatePhysics} saw the row: it is in {@code Groups.bullet}'s tree. */
    public boolean[] indexed;
    /** {@code fdata}: a float a type keeps per bullet (a flak shell's trigger). */
    public float[] fdata;
    public boolean[] keepAlive, justSpawned, absorbed, hit;
    /** {@code aimTile} as a tile position, or -1. */
    public int[] aimTile;
    public float[] aimX, aimY, originX, originY, buildingDamageMultiplier;
    /** {@code frags}: how many times it split. */
    public int[] frags;
    /** {@code mover}, or null: what a shoot pattern does to the bullet each update. */
    public Mover[] mover;
    /** {@code stickyTarget} (a unit or a building), or null, and where on it the bullet sits. */
    public Entity[] stickyTarget;
    public float[] stickyX, stickyY, stickyRotation, stickyRotationOffset;
    /** {@code timer}: {@link #TIMERS} times per row, row-major, in {@link Timers#time()}. */
    public float[] timer;
    /** {@code collided}: the entity ids a piercing bullet has hit, {@link #collidedSize} of each. */
    public int[][] collided;
    public int[] collidedSize;

    @Derived("the world's EntityIds, given to the constructor")
    private final EntityIds ids;
    /** The removed bullets awaiting {@link #flushPool}, in removal order; null in the limbo itself. Stored as limbo.*. */
    private final Bullets limbo;
    /**
     * The free {@code Bullet} objects' numbers and timers ({@link #TIMERS} each), in the pool's order, mirroring the
     * ids that {@link EntityIds#free(Pooled)} holds: {@code obtain} pops the last.
     */
    private int[] poolObjects;
    private float[] poolTimers;
    private int poolSize;
    /** The number the next new {@code Bullet} object gets. */
    private int nextObject;
    @Derived("rebuilt from the id column in read()")
    private IntIntMap rows;
    @Derived("rebuilt from the object column in read()")
    private IntIntMap objectRows;
    @Derived("BulletTypes.of(world) on first use")
    private BulletTypes types;
    /** The map's size at the last {@link #updatePhysics}, which the next tree build uses. */
    private int tilesWide, tilesHigh;
    /**
     * The objects {@link #updatePhysics} filled vanilla's tree with, in group order, or -1 entries once the fill is
     * stale; the tree a {@link #intersectQuad} query walks is built from them on first use.
     */
    @Derived("recorded by updatePhysics and dropped with the grid at the end of the tick; a query after that falls back to the indexed rows")
    private int[] fillObjects = new int[0];
    @Derived("as fillObjects")
    private int fillCount = -1;
    @Derived("built from fillObjects on the first intersectQuad after a fill")
    private BulletTree quad;
    @Derived("as quad")
    private boolean quadBuilt;

    /** An empty table whose bullets draw their ids from {@code ids}. */
    public Bullets(EntityIds ids){
        this(ids, true);
    }

    private Bullets(EntityIds ids, boolean main){
        this.ids = Objects.requireNonNull(ids, "ids");
        allocate(main ? 64 : 8);
        // the limbo never pools
        poolObjects = new int[main ? 8 : 0];
        poolTimers = new float[main ? TIMERS * 8 : 0];
        rows = new IntIntMap();
        objectRows = new IntIntMap();
        limbo = main ? new Bullets(ids, false) : null;
    }

    private Bullets(Bullets source, CopyContext context){
        size = source.size;
        index = source.index;
        int capacity = source.id.length;
        allocate(capacity);
        // the spare capacity too: a fork is the same arrays, stale slots included
        for(int i = 0; i < capacity; i++) copyRow(source, i, this, i);
        for(int i = 0; i < size; i++){
            owner[i] = context.map(owner[i]);
            shooter[i] = context.map(shooter[i]);
            stickyTarget[i] = context.map(stickyTarget[i]);
            indexed[i] = source.indexed[i];
        }
        ids = context.world().ids();
        limbo = source.limbo == null ? null : new Bullets(source.limbo, context);
        poolObjects = source.poolObjects.clone();
        poolTimers = source.poolTimers.clone();
        poolSize = source.poolSize;
        nextObject = source.nextObject;
        rows = source.rows.copy();
        objectRows = source.objectRows.copy();
        types = source.types;
        tilesWide = source.tilesWide;
        tilesHigh = source.tilesHigh;
        fillObjects = source.fillObjects.clone();
        fillCount = source.fillCount;
    }

    public Bullets copy(CopyContext context){
        return new Bullets(this, context);
    }

    public int size(){
        return size;
    }

    /** The bullet types of {@code world}'s content, the behaviour behind {@link #type}. */
    public BulletTypes types(World world){
        BulletTypes types = this.types;
        if(types == null || types.content() != world.content()) this.types = types = BulletTypes.of(world);
        return types;
    }

    /** The row of the live bullet with entity id {@code id}, or -1. */
    public int rowOf(int id){
        return rows.get(id, -1);
    }

    /** The row of the live bullet that is pooled object {@code object} ({@link #object}), or -1. */
    public int rowOfObject(int object){
        return objectRows.get(object, -1);
    }

    /** The bullets removed since the last {@link #flushPool}, in removal order, as vanilla's objects still hold them. */
    public Bullets limbo(){
        return limbo;
    }

    /** A replay accessor: the bullet type's content id at {@code row}, which is how a viewer picks its sprite. */
    public int style(int row){
        Objects.checkIndex(row, size);
        return type[row];
    }

    /**
     * {@code Pools.obtain(Bullet.class)} plus the reset fields vanilla's creation doesn't set: appends a row with a
     * pooled id from {@code ids}, the pooled object and its timers, and vanilla's defaults, at (x, y) with no velocity.
     * As {@code HitboxComp.add} does after {@code create} set the last position, the delta is 0. Returns the row.
     */
    int obtain(int type, int team, float x, float y){
        if(size == id.length) grow(Math.max(8, (int)(size * 1.75f)));
        syncPool();
        int row = size++;
        int bulletId = ids.obtain(Pooled.BULLET);
        id[row] = bulletId;
        rows.put(bulletId, row);
        if(poolSize > 0){
            poolSize--;
            object[row] = poolObjects[poolSize];
            System.arraycopy(poolTimers, poolSize * TIMERS, timer, row * TIMERS, TIMERS);
        }else{
            object[row] = nextObject++;
            Arrays.fill(timer, row * TIMERS, row * TIMERS + TIMERS, 0f);
        }
        objectRows.put(object[row], row);
        this.type[row] = type;
        this.team[row] = team;
        this.x[row] = x;
        this.y[row] = y;
        velX[row] = velY[row] = 0f;
        rotation[row] = 0f;
        time[row] = lifetime[row] = damage[row] = 0f;
        owner[row] = shooter[row] = stickyTarget[row] = null;
        lastX[row] = x;
        lastY[row] = y;
        deltaX[row] = deltaY[row] = 0f;
        hitSize[row] = DEFAULT_HIT_SIZE;
        indexed[row] = false;
        fdata[row] = 0f;
        keepAlive[row] = absorbed[row] = hit[row] = false;
        justSpawned[row] = true;
        aimTile[row] = -1;
        aimX[row] = aimY[row] = originX[row] = originY[row] = buildingDamageMultiplier[row] = 0f;
        frags[row] = 0;
        mover[row] = null;
        stickyX[row] = stickyY[row] = stickyRotation[row] = stickyRotationOffset[row] = 0f;
        collidedSize[row] = 0;
        return row;
    }

    /**
     * A raw row with a pooled id from {@code ids} and vanilla's default hit size, for tables filled by hand: no type
     * hooks run. As {@code HitboxComp.add} does, the last position becomes (x, y), and the delta is from a reset
     * bullet's (0, 0). Returns its row.
     */
    public int add(EntityIds ids, int type, int team, float x, float y, float velX, float velY, float lifetime, float damage, Entity owner){
        if(this.ids != ids || limbo == null) throw new IllegalArgumentException("these bullets draw ids from another counter");
        int row = obtain(type, team, x, y);
        this.velX[row] = velX;
        this.velY[row] = velY;
        this.lifetime[row] = lifetime;
        this.damage[row] = damage;
        this.owner[row] = this.shooter[row] = owner;
        this.deltaX[row] = x;
        this.deltaY[row] = y;
        return row;
    }

    /**
     * Removes {@code row} without running its type's hooks: the row joins the {@linkplain #limbo() limbo} (the pool's
     * free queue, {@code Groups.queueFree}) and the last row moves into the hole; the cursor steps back if it is at or
     * past {@code row}. Returns the bullet's row in the limbo.
     */
    public int remove(int row){
        Objects.checkIndex(row, size);
        if(limbo == null) throw new IllegalStateException("the limbo's rows are already removed");
        ids.queueFree(Pooled.BULLET);
        int at = limbo.append(this, row);
        rows.remove(id[row], -1);
        objectRows.remove(object[row], -1);
        int last = --size;
        if(row != last){
            copyRow(this, last, this, row);
            rows.put(id[row], row);
            objectRows.put(object[row], row);
        }
        collided[last] = null;
        owner[last] = shooter[last] = stickyTarget[last] = null;
        mover[last] = null;
        if(index >= row) index--;
        return at;
    }

    /** Appends a copy of {@code from}'s {@code row}; the limbo's rows keep their ids and objects for lookups. */
    private int append(Bullets from, int row){
        if(size == id.length) grow(Math.max(8, size * 2));
        int at = size++;
        copyRow(from, row, this, at);
        rows.put(id[at], at);
        objectRows.put(object[at], at);
        return at;
    }

    /**
     * {@code Groups.updatePooling()}'s frees, for the objects: each removed bullet, in removal order, joins the pool if
     * it has room, as {@link EntityIds#flushFrees()} does with its id, and its state is gone. Runs where vanilla's
     * pooling does, right after it.
     */
    public void flushPool(){
        for(int i = 0; i < limbo.size; i++){
            if(poolSize >= EntityIds.POOL_MAX) continue;
            reservePool(poolSize + 1);
            poolObjects[poolSize] = limbo.object[i];
            System.arraycopy(limbo.timer, i * TIMERS, poolTimers, poolSize * TIMERS, TIMERS);
            poolSize++;
        }
        limbo.clearRows();
    }

    private void clearRows(){
        for(int i = 0; i < size; i++){
            owner[i] = shooter[i] = stickyTarget[i] = null;
            mover[i] = null;
            collided[i] = null;
        }
        rows.clear();
        objectRows.clear();
        size = 0;
        index = 0;
    }

    /**
     * Keeps the pool in step with the ids when something flushed {@link EntityIds} without {@link #flushPool}
     * (tests): the removed objects join, and objects of unknown history start at zero.
     */
    private void syncPool(){
        int free = ids.free(Pooled.BULLET);
        if(poolSize == free) return;
        if(limbo.size > 0 && ids.queuedFrees() == 0) flushPool();
        while(poolSize > free) poolSize--;
        while(poolSize < free){
            reservePool(poolSize + 1);
            Arrays.fill(poolTimers, poolSize * TIMERS, poolSize * TIMERS + TIMERS, 0f);
            poolObjects[poolSize] = nextObject++;
            poolSize++;
        }
    }

    private void reservePool(int objects){
        if(poolObjects.length < objects){
            poolObjects = Arrays.copyOf(poolObjects, objects * 2);
            poolTimers = Arrays.copyOf(poolTimers, objects * 2 * TIMERS);
        }
    }

    /**
     * {@code Groups.bullet.updatePhysics()} on a map of {@code tilesWide × tilesHigh} tiles: every row joins the tree,
     * then records its last position. Vanilla's pooling ran just before, so the removed objects join the pool first.
     */
    public void updatePhysics(int tilesWide, int tilesHigh){
        flushPool();
        this.tilesWide = tilesWide;
        this.tilesHigh = tilesHigh;
        if(fillObjects.length < size) fillObjects = new int[Math.max(16, size * 2)];
        System.arraycopy(object, 0, fillObjects, 0, size);
        fillCount = size;
        quadBuilt = false;
        for(int row = 0; row < size; row++){
            indexed[row] = true;
            deltaX[row] = x[row] - lastX[row];
            deltaY[row] = y[row] - lastY[row];
            lastX[row] = x[row];
            lastY[row] = y[row];
        }
    }

    /**
     * {@code Groups.bullet.intersect(x, y, width, height, cons)} in vanilla's own order: Arc's quadtree as the last
     * {@link #updatePhysics} filled it ({@link BulletTree}), live rows and the limbo's alike, as {@link #intersectTree}.
     * For code whose result depends on the order of several bullets (a force field absorbing them, which removes
     * them and sums their damage). After the fill expires, the tree is filled from the indexed rows in row order.
     */
    public void intersectQuad(float x, float y, float width, float height, Member visitor){
        if(limbo == null) throw new IllegalStateException("the limbo has no tree");
        // don't waste time for empty groups: the limbo's rows aren't in the group
        if(size == 0) return;
        if(!quadBuilt){
            if(quad == null) quad = new BulletTree();
            if(fillCount < 0){
                var list = new int[size + limbo.size];
                int n = 0;
                for(int row = 0; row < size; row++) if(indexed[row]) list[n++] = object[row];
                for(int row = 0; row < limbo.size; row++) if(limbo.indexed[row]) list[n++] = limbo.object[row];
                quad.fill(this, list, n, tilesWide, tilesHigh);
            }else{
                quad.fill(this, fillObjects, fillCount, tilesWide, tilesHigh);
            }
            quadBuilt = true;
        }
        quad.intersect(this, x, y, width, height, visitor);
    }

    /** Drops the fill: the next {@link #intersectQuad} fills the tree from the {@link #indexed} rows. */
    public void expireGrid(){
        fillCount = -1;
        quadBuilt = false;
    }

    /** Visits every row in order with the cursor semantics above. */
    public void each(Row action){
        for(index = 0; index < size; index++){
            action.accept(index);
        }
    }

    // ---------------------------------------------------------------- collided (IntSeq)

    /** {@code hasCollided(id)}. */
    public boolean hasCollided(int row, int entity){
        int[] hits = collided[row];
        for(int i = 0, n = collidedSize[row]; i < n; i++) if(hits[i] == entity) return true;
        return false;
    }

    /** {@code collided.add(id)}. */
    public void addCollided(int row, int entity){
        int[] hits = collided[row];
        int n = collidedSize[row];
        if(hits == null) collided[row] = hits = new int[6];
        else if(n == hits.length) collided[row] = hits = Arrays.copyOf(hits, Math.max(8, (int)(n * 1.75f)));
        hits[n] = entity;
        collidedSize[row] = n + 1;
    }

    // ---------------------------------------------------------------- timers (Interval)

    /** {@code timer.get(slot, time)}: whether {@code time} ticks passed since the slot last fired, restarting it if so. */
    public boolean timer(int row, int slot, float time, float now){
        int at = row * TIMERS + slot;
        boolean got = now - timer[at] >= time || now < timer[at];
        if(got) timer[at] = now;
        return got;
    }

    // ---------------------------------------------------------------- checkpoints and hashes

    /** The rows, the cursor, the pool and the limbo; the columns' spare capacity isn't state. */
    public void write(CheckpointOut out){
        writeRows(out, "");
        out.i("index", index);
        out.i("poolSize", poolSize).ints("poolObjects", poolObjects, poolSize).floats("poolTimers", poolTimers, poolSize * TIMERS);
        out.i("nextObject", nextObject);
        out.implicit("limbo");
        limbo.writeRows(out, "limbo.");
        out.i("tilesWide", tilesWide).i("tilesHigh", tilesHigh);
    }

    private void writeRows(CheckpointOut out, String p){
        var indexed = new byte[size];
        var flags = new byte[size];
        for(int i = 0; i < size; i++){
            indexed[i] = (byte)(this.indexed[i] ? 1 : 0);
            flags[i] = (byte)((keepAlive[i] ? 1 : 0) | (justSpawned[i] ? 2 : 0) | (absorbed[i] ? 4 : 0) | (hit[i] ? 8 : 0));
        }
        var hits = new IntSeq();
        for(int i = 0; i < size; i++){
            hits.add(collidedSize[i]);
            if(collidedSize[i] > 0) hits.addAll(collided[i], 0, collidedSize[i]);
        }
        out.i(p + "size", size);
        out.ints(p + "id", id, size).ints(p + "object", object, size).ints(p + "type", type, size).ints(p + "team", team, size);
        out.floats(p + "x", x, size).floats(p + "y", y, size).floats(p + "velX", velX, size).floats(p + "velY", velY, size);
        out.floats(p + "rotation", rotation, size);
        out.floats(p + "time", time, size).floats(p + "lifetime", lifetime, size).floats(p + "damage", damage, size);
        out.refs(p + "owner", owner, size).refs(p + "shooter", shooter, size);
        out.floats(p + "lastX", lastX, size).floats(p + "lastY", lastY, size).floats(p + "deltaX", deltaX, size).floats(p + "deltaY", deltaY, size);
        out.floats(p + "hitSize", hitSize, size).bytes(p + "indexed", indexed).floats(p + "fdata", fdata, size);
        // keepAlive, justSpawned, absorbed and hit, one bit each
        out.bytes(p + "flags", flags).implicit(p + "keepAlive").implicit(p + "justSpawned").implicit(p + "absorbed").implicit(p + "hit");
        out.ints(p + "aimTile", aimTile, size).floats(p + "aimX", aimX, size).floats(p + "aimY", aimY, size);
        out.floats(p + "originX", originX, size).floats(p + "originY", originY, size);
        out.floats(p + "buildingDamageMultiplier", buildingDamageMultiplier, size);
        out.ints(p + "frags", frags, size).list(p + "mover", Arrays.asList(mover).subList(0, size)).refs(p + "stickyTarget", stickyTarget, size);
        out.floats(p + "stickyX", stickyX, size).floats(p + "stickyY", stickyY, size).floats(p + "stickyRotation", stickyRotation, size)
            .floats(p + "stickyRotationOffset", stickyRotationOffset, size);
        out.floats(p + "timer", timer, size * TIMERS);
        // each row's count, then its ids
        out.ints(p + "collided", hits.items, hits.size).implicit(p + "collidedSize");
    }

    /** Reads into this table, which must be empty: a restored world's. */
    public void read(CheckpointIn in){
        if(this.size != 0) throw new IllegalStateException("read into a table with rows");
        readRows(in, "");
        index = in.i("index");
        poolSize = in.i("poolSize");
        int[] objects = in.ints("poolObjects");
        float[] timers = in.floats("poolTimers");
        if(poolSize < 0 || objects == null || objects.length != poolSize || timers == null || timers.length != poolSize * TIMERS){
            throw new CheckpointException("the bullet pool is corrupt");
        }
        poolObjects = Arrays.copyOf(objects, Math.max(8, poolSize));
        poolTimers = Arrays.copyOf(timers, Math.max(8, poolSize) * TIMERS);
        nextObject = in.i("nextObject");
        in.implicit("limbo");
        limbo.readRows(in, "limbo.");
        tilesWide = in.i("tilesWide");
        tilesHigh = in.i("tilesHigh");
    }

    private void readRows(CheckpointIn in, String p){
        int size = in.i(p + "size");
        if(size < 0) throw new CheckpointException("the bullet table's size is negative");
        allocate(Math.max(limbo == null ? 8 : 64, size));
        this.size = size;
        column(in.ints(p + "id"), id);
        column(in.ints(p + "object"), object);
        column(in.ints(p + "type"), type);
        column(in.ints(p + "team"), team);
        column(in.floats(p + "x"), x);
        column(in.floats(p + "y"), y);
        column(in.floats(p + "velX"), velX);
        column(in.floats(p + "velY"), velY);
        column(in.floats(p + "rotation"), rotation);
        column(in.floats(p + "time"), time);
        column(in.floats(p + "lifetime"), lifetime);
        column(in.floats(p + "damage"), damage);
        column(in.refs(p + "owner", Entity.class), owner);
        column(in.refs(p + "shooter", Entity.class), shooter);
        column(in.floats(p + "lastX"), lastX);
        column(in.floats(p + "lastY"), lastY);
        column(in.floats(p + "deltaX"), deltaX);
        column(in.floats(p + "deltaY"), deltaY);
        column(in.floats(p + "hitSize"), hitSize);
        byte[] indexed = in.bytes(p + "indexed");
        check(indexed);
        for(int i = 0; i < size; i++) this.indexed[i] = indexed[i] != 0;
        column(in.floats(p + "fdata"), fdata);
        byte[] flags = in.bytes(p + "flags");
        check(flags);
        in.implicit(p + "keepAlive");
        in.implicit(p + "justSpawned");
        in.implicit(p + "absorbed");
        in.implicit(p + "hit");
        for(int i = 0; i < size; i++){
            keepAlive[i] = (flags[i] & 1) != 0;
            justSpawned[i] = (flags[i] & 2) != 0;
            absorbed[i] = (flags[i] & 4) != 0;
            hit[i] = (flags[i] & 8) != 0;
        }
        column(in.ints(p + "aimTile"), aimTile);
        column(in.floats(p + "aimX"), aimX);
        column(in.floats(p + "aimY"), aimY);
        column(in.floats(p + "originX"), originX);
        column(in.floats(p + "originY"), originY);
        column(in.floats(p + "buildingDamageMultiplier"), buildingDamageMultiplier);
        column(in.ints(p + "frags"), frags);
        List<Mover> movers = in.list(p + "mover", Mover.class);
        if(movers == null || movers.size() != size) throw new CheckpointException("the bullet movers differ in count from the rows");
        for(int i = 0; i < size; i++) mover[i] = movers.get(i);
        column(in.refs(p + "stickyTarget", Entity.class), stickyTarget);
        column(in.floats(p + "stickyX"), stickyX);
        column(in.floats(p + "stickyY"), stickyY);
        column(in.floats(p + "stickyRotation"), stickyRotation);
        column(in.floats(p + "stickyRotationOffset"), stickyRotationOffset);
        float[] timers = in.floats(p + "timer");
        if(timers == null || timers.length != size * TIMERS) throw new CheckpointException("the bullet timers differ in count from the rows");
        System.arraycopy(timers, 0, timer, 0, timers.length);
        int[] hits = in.ints(p + "collided");
        in.implicit(p + "collidedSize");
        int at = 0;
        for(int i = 0; i < size; i++){
            if(hits == null || at >= hits.length || hits[at] < 0 || hits[at] > hits.length - at - 1) throw new CheckpointException("the bullet hit lists are corrupt");
            int n = hits[at++];
            collidedSize[i] = n;
            collided[i] = n == 0 ? null : Arrays.copyOfRange(hits, at, at + n);
            at += n;
        }
        if(at != hits.length) throw new CheckpointException("the bullet hit lists have " + (hits.length - at) + " extra values");
        rows = new IntIntMap();
        objectRows = new IntIntMap();
        for(int i = 0; i < size; i++){
            rows.put(id[i], i);
            objectRows.put(object[i], i);
        }
    }

    private void column(Object stored, Object into){
        check(stored);
        System.arraycopy(stored, 0, into, 0, size);
    }

    private void check(Object stored){
        if(length(stored) != size) throw new CheckpointException("a bullet column differs in length from the table");
    }

    /** The length of a column array, or -1 for anything else. */
    private static int length(Object stored){
        return switch(stored){
            case int[] values -> values.length;
            case float[] values -> values.length;
            case boolean[] values -> values.length;
            case byte[] values -> values.length;
            case short[] values -> values.length;
            case long[] values -> values.length;
            case double[] values -> values.length;
            case Object[] values -> values.length;
            case null, default -> -1;
        };
    }

    public void hash(Hasher hasher){
        hashRows(hasher);
        hasher.i(poolSize).ints(poolObjects, 0, poolSize).floats(poolTimers, 0, poolSize * TIMERS).i(nextObject);
        if(limbo != null) limbo.hash(hasher);
    }

    private void hashRows(Hasher hasher){
        hasher.i(size).ints(id, 0, size).ints(object, 0, size).ints(type, 0, size).ints(team, 0, size);
        hasher.floats(x, 0, size).floats(y, 0, size).floats(velX, 0, size).floats(velY, 0, size).floats(rotation, 0, size);
        hasher.floats(time, 0, size).floats(lifetime, 0, size).floats(damage, 0, size);
        for(int i = 0; i < size; i++) hasher.i(owner[i] == null ? -1 : owner[i].id()).i(shooter[i] == null ? -1 : shooter[i].id());
        hasher.floats(lastX, 0, size).floats(lastY, 0, size).floats(deltaX, 0, size).floats(deltaY, 0, size);
        hasher.floats(hitSize, 0, size);
        for(int i = 0; i < size; i++) hasher.bool(indexed[i]).bool(keepAlive[i]).bool(justSpawned[i]).bool(absorbed[i]).bool(hit[i]);
        hasher.floats(fdata, 0, size).ints(aimTile, 0, size).floats(aimX, 0, size).floats(aimY, 0, size);
        hasher.floats(originX, 0, size).floats(originY, 0, size).floats(buildingDamageMultiplier, 0, size).ints(frags, 0, size);
        for(int i = 0; i < size; i++){
            hasher.bool(mover[i] != null);
            if(mover[i] != null) mover[i].hash(hasher);
            hasher.i(stickyTarget[i] == null ? -1 : stickyTarget[i].id());
        }
        hasher.floats(stickyX, 0, size).floats(stickyY, 0, size).floats(stickyRotation, 0, size).floats(stickyRotationOffset, 0, size);
        hasher.floats(timer, 0, size * TIMERS);
        for(int i = 0; i < size; i++){
            hasher.i(collidedSize[i]);
            if(collidedSize[i] > 0) hasher.ints(collided[i], 0, collidedSize[i]);
        }
    }

    // ---------------------------------------------------------------- columns

    private void allocate(int capacity){
        id = new int[capacity];
        object = new int[capacity];
        type = new int[capacity];
        team = new int[capacity];
        x = new float[capacity];
        y = new float[capacity];
        velX = new float[capacity];
        velY = new float[capacity];
        rotation = new float[capacity];
        time = new float[capacity];
        lifetime = new float[capacity];
        damage = new float[capacity];
        owner = new Entity[capacity];
        shooter = new Entity[capacity];
        lastX = new float[capacity];
        lastY = new float[capacity];
        deltaX = new float[capacity];
        deltaY = new float[capacity];
        hitSize = new float[capacity];
        indexed = new boolean[capacity];
        fdata = new float[capacity];
        keepAlive = new boolean[capacity];
        justSpawned = new boolean[capacity];
        absorbed = new boolean[capacity];
        hit = new boolean[capacity];
        aimTile = new int[capacity];
        aimX = new float[capacity];
        aimY = new float[capacity];
        originX = new float[capacity];
        originY = new float[capacity];
        buildingDamageMultiplier = new float[capacity];
        frags = new int[capacity];
        mover = new Mover[capacity];
        stickyTarget = new Entity[capacity];
        stickyX = new float[capacity];
        stickyY = new float[capacity];
        stickyRotation = new float[capacity];
        stickyRotationOffset = new float[capacity];
        timer = new float[capacity * TIMERS];
        collided = new int[capacity][];
        collidedSize = new int[capacity];
    }

    private void grow(int capacity){
        id = Arrays.copyOf(id, capacity);
        object = Arrays.copyOf(object, capacity);
        type = Arrays.copyOf(type, capacity);
        team = Arrays.copyOf(team, capacity);
        x = Arrays.copyOf(x, capacity);
        y = Arrays.copyOf(y, capacity);
        velX = Arrays.copyOf(velX, capacity);
        velY = Arrays.copyOf(velY, capacity);
        rotation = Arrays.copyOf(rotation, capacity);
        time = Arrays.copyOf(time, capacity);
        lifetime = Arrays.copyOf(lifetime, capacity);
        damage = Arrays.copyOf(damage, capacity);
        owner = Arrays.copyOf(owner, capacity);
        shooter = Arrays.copyOf(shooter, capacity);
        lastX = Arrays.copyOf(lastX, capacity);
        lastY = Arrays.copyOf(lastY, capacity);
        deltaX = Arrays.copyOf(deltaX, capacity);
        deltaY = Arrays.copyOf(deltaY, capacity);
        hitSize = Arrays.copyOf(hitSize, capacity);
        indexed = Arrays.copyOf(indexed, capacity);
        fdata = Arrays.copyOf(fdata, capacity);
        keepAlive = Arrays.copyOf(keepAlive, capacity);
        justSpawned = Arrays.copyOf(justSpawned, capacity);
        absorbed = Arrays.copyOf(absorbed, capacity);
        hit = Arrays.copyOf(hit, capacity);
        aimTile = Arrays.copyOf(aimTile, capacity);
        aimX = Arrays.copyOf(aimX, capacity);
        aimY = Arrays.copyOf(aimY, capacity);
        originX = Arrays.copyOf(originX, capacity);
        originY = Arrays.copyOf(originY, capacity);
        buildingDamageMultiplier = Arrays.copyOf(buildingDamageMultiplier, capacity);
        frags = Arrays.copyOf(frags, capacity);
        mover = Arrays.copyOf(mover, capacity);
        stickyTarget = Arrays.copyOf(stickyTarget, capacity);
        stickyX = Arrays.copyOf(stickyX, capacity);
        stickyY = Arrays.copyOf(stickyY, capacity);
        stickyRotation = Arrays.copyOf(stickyRotation, capacity);
        stickyRotationOffset = Arrays.copyOf(stickyRotationOffset, capacity);
        timer = Arrays.copyOf(timer, capacity * TIMERS);
        collided = Arrays.copyOf(collided, capacity);
        collidedSize = Arrays.copyOf(collidedSize, capacity);
    }

    /**
     * Copies every column of {@code from}'s {@code row} into {@code into}'s {@code at}; the grid isn't a column. The
     * hit list moves by reference: a row within a table hands it over, and a copy into another table clones it.
     */
    private static void copyRow(Bullets from, int row, Bullets into, int at){
        into.id[at] = from.id[row];
        into.object[at] = from.object[row];
        into.type[at] = from.type[row];
        into.team[at] = from.team[row];
        into.x[at] = from.x[row];
        into.y[at] = from.y[row];
        into.velX[at] = from.velX[row];
        into.velY[at] = from.velY[row];
        into.rotation[at] = from.rotation[row];
        into.time[at] = from.time[row];
        into.lifetime[at] = from.lifetime[row];
        into.damage[at] = from.damage[row];
        into.owner[at] = from.owner[row];
        into.shooter[at] = from.shooter[row];
        into.lastX[at] = from.lastX[row];
        into.lastY[at] = from.lastY[row];
        into.deltaX[at] = from.deltaX[row];
        into.deltaY[at] = from.deltaY[row];
        into.hitSize[at] = from.hitSize[row];
        into.indexed[at] = from.indexed[row];
        into.fdata[at] = from.fdata[row];
        into.keepAlive[at] = from.keepAlive[row];
        into.justSpawned[at] = from.justSpawned[row];
        into.absorbed[at] = from.absorbed[row];
        into.hit[at] = from.hit[row];
        into.aimTile[at] = from.aimTile[row];
        into.aimX[at] = from.aimX[row];
        into.aimY[at] = from.aimY[row];
        into.originX[at] = from.originX[row];
        into.originY[at] = from.originY[row];
        into.buildingDamageMultiplier[at] = from.buildingDamageMultiplier[row];
        into.frags[at] = from.frags[row];
        into.mover[at] = from.mover[row];
        into.stickyTarget[at] = from.stickyTarget[row];
        into.stickyX[at] = from.stickyX[row];
        into.stickyY[at] = from.stickyY[row];
        into.stickyRotation[at] = from.stickyRotation[row];
        into.stickyRotationOffset[at] = from.stickyRotationOffset[row];
        System.arraycopy(from.timer, row * TIMERS, into.timer, at * TIMERS, TIMERS);
        into.collided[at] = from == into ? from.collided[row] : from.collided[row] == null ? null : from.collided[row].clone();
        into.collidedSize[at] = from.collidedSize[row];
    }

    @FunctionalInterface
    public interface Row{
        void accept(int row);
    }

    /** A bullet of the tree: a row of the table or of its limbo. */
    @FunctionalInterface
    public interface Member{
        void accept(Bullets table, int row);
    }
}
