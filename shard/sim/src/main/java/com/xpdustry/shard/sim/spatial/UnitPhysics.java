package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Unit pushing: a port of vanilla's {@code PhysicsProcess}, with its quadtree ported too ({@link BodyTree}).
 *
 * <p>Each unit with {@code type.physics} has a body ({@code PhysicRef} and {@code PhysicsBody} in one object), in one of
 * four layers that never interact: ground, legs, flying, underwater ({@link Unit#collisionLayer()}). {@link #begin()} is
 * {@code PhysicsProcess.begin}, at the start of the tick:
 * <ol>
 *   <li>bodies added since the last begin join their layer;</li>
 *   <li>every unit moves by its body's displacement in the last step ({@link Unit#move}, so through tile
 *   collisions): pushes land one tick after they are computed;</li>
 *   <li>bodies of removed units are dropped (vanilla's unordered {@code refs.remove(i)}), bodies whose unit changed
 *   layer move layer, and every body takes its unit's position;</li>
 *   <li>each layer steps: one Gauss-Seidel pass over its bodies in list order, each overlapping pair pushed apart
 *   once, by {@code (rs - dst)} split by mass and softened by 1.25.</li>
 * </ol>
 * Vanilla runs step 4 on other threads until the next begin; nothing reads the bodies in between, so stepping at once
 * gives the same result, deterministically and on the tick thread.
 *
 * <p>A body's candidates come from the layer's quadtree, filled at the start of the step and queried at the body's
 * current box, in vanilla's order: a Gauss-Seidel pass depends on that order, so crowds are pushed exactly as vanilla
 * pushes them ({@code PhysicsTraceOracleTest}, bit for bit). The one difference: exactly stacked bodies push apart in
 * a direction drawn from the layer's own {@link Rand}, seeded per layer, where vanilla's {@code PhysicsWorld.rand} is
 * seeded from the clock (so no two vanilla runs agree there either).
 *
 * <p>{@link Persistence#AUTHORITATIVE}: the bodies and their order, the pending adds, the layer lists and their
 * {@code Rand}s. The bodies hold last step's result until the next begin applies it.
 */
@Register
public final class UnitPhysics implements WorldPart, Checkpointable{
    public static final int LAYERS = 4, LAYER_GROUND = 0, LAYER_LEGS = 1, LAYER_FLYING = 2, LAYER_UNDERWATER = 3;
    /** {@code PhysicsWorld.scl}: how much to soften pushes by. */
    static final float SOFTEN = 1.25f;
    /** {@code Vars.unitCollisionRadiusScale}. */
    static final float RADIUS_SCALE = 0.6f;
    /** The seed of layer {@code i}'s {@code Rand} is this plus {@code i}. */
    static final long LAYER_SEED = 0x5048_5953_4943_53L;

    /** The map's size; stored, since a restored part gets no {@code init}. */
    private int tilesWide, tilesHigh;
    /** {@code PhysicsProcess.refs}: an unordered {@code Seq}. */
    private Body[] refs;
    private int refCount;
    /** {@code PhysicsProcess.pendingAdds}. */
    private Body[] pending;
    private int pendingCount;
    private Layer[] layers;

    public UnitPhysics(){
        refs = new Body[20];
        pending = new Body[20];
    }

    private UnitPhysics(UnitPhysics source, CopyContext context){
        tilesWide = source.tilesWide;
        tilesHigh = source.tilesHigh;
        refs = map(source.refs, source.refCount, context);
        refCount = source.refCount;
        pending = map(source.pending, source.pendingCount, context);
        pendingCount = source.pendingCount;
        layers = new Layer[LAYERS];
        for(int i = 0; i < LAYERS; i++) layers[i] = source.layers[i].copy(context);
    }

    @Override
    public void init(World world){
        tilesWide = world.tiles().width();
        tilesHigh = world.tiles().height();
        layers = new Layer[LAYERS];
        for(int i = 0; i < LAYERS; i++) layers[i] = new Layer(new Rand(LAYER_SEED + i), tilesWide, tilesHigh);
    }

    /** The world's physics, or null in a world built without it. */
    public static UnitPhysics of(World world){
        return world.find(UnitPhysics.class);
    }

    /** {@code PhysicsProcess.add}: gives {@code unit} a body, which joins its layer at the next {@link #begin()}. */
    public void add(Unit unit){
        if(unit == null || unit.type() == null || !unit.params().physics() || unit.hasPhysicsRef) return;
        unit.hasPhysicsRef = true;
        var body = new Body(unit);
        refs = append(refs, refCount++, body);
        pending = append(pending, pendingCount++, body);
    }

    /** {@code PhysicsProcess.begin}: see the class comment. */
    public void begin(){
        for(int i = 0; i < pendingCount; i++){
            Body body = pending[i];
            if(body.lastLayer >= 0) layers[body.lastLayer].add(body);
        }
        Arrays.fill(pending, 0, pendingCount, null);
        pendingCount = 0;

        for(int i = 0; i < refCount; i++){
            Body body = refs[i];
            body.unit.move(body.x - body.startX, body.y - body.startY);
        }

        for(int i = 0; i < refCount; i++){
            Body body = refs[i];
            if(!body.unit.isAdded()){
                body.unit.hasPhysicsRef = false;
                // Seq.remove(index), unordered: the last ref fills the hole and is checked next
                refs[i] = refs[--refCount];
                refs[refCount] = null;
                i--;
                if(body.lastLayer >= 0) layers[body.lastLayer].remove(body);
            }else{
                int layer = body.unit.collisionLayer();
                if(layer != body.lastLayer){
                    if(body.lastLayer >= 0) layers[body.lastLayer].remove(body);
                    body.lastLayer = layer;
                    if(layer >= 0) layers[layer].add(body);
                }
                body.startX = body.x = body.unit.x;
                body.startY = body.y = body.unit.y;
            }
        }

        for(Layer layer : layers) layer.step();
    }

    /** How many units have a body. */
    public int bodies(){
        return refCount;
    }

    /** How many bodies layer {@code layer} holds. */
    public int bodies(int layer){
        return layers[layer].size;
    }

    /** How many bodies have joined a layer: those added before the last begin, until the begin after their removal. */
    public int layered(){
        int count = 0;
        for(Layer layer : layers) count += layer.size;
        return count;
    }

    @Override
    public WorldPart copy(CopyContext context){
        return new UnitPhysics(this, context);
    }

    /** The bodies by reference, in order; the arrays' spare capacity isn't state. */
    @Override
    public void write(CheckpointOut out){
        out.i("tilesWide", tilesWide).i("tilesHigh", tilesHigh).refs("refs", refs, refCount).i("refCount", refCount)
            .refs("pending", pending, pendingCount).i("pendingCount", pendingCount)
            .list("layers", layers == null ? null : Arrays.asList(layers));
    }

    @Override
    public void read(CheckpointIn in){
        tilesWide = in.i("tilesWide");
        tilesHigh = in.i("tilesHigh");
        refs = bodies(in.refs("refs", Body.class), refCount = in.i("refCount"), 20);
        pending = bodies(in.refs("pending", Body.class), pendingCount = in.i("pendingCount"), 20);
        List<Layer> stored = in.list("layers", Layer.class);
        if(stored == null || stored.size() != LAYERS || stored.contains(null)) throw new CheckpointException("the physics layers are missing");
        layers = stored.toArray(Layer[]::new);
        for(Layer layer : layers) layer.bind(tilesWide, tilesHigh);
    }

    private static Body[] bodies(Body[] stored, int count, int capacity){
        if(stored == null || stored.length != count) throw new CheckpointException("a physics body list is inconsistent");
        for(Body body : stored) if(body == null) throw new CheckpointException("a physics body list holds a null");
        return Arrays.copyOf(stored, Math.max(capacity, count));
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(refCount);
        for(int i = 0; i < refCount; i++) refs[i].hash(hasher);
        hasher.i(pendingCount);
        for(int i = 0; i < pendingCount; i++) hasher.i(pending[i].unit.id());
        for(Layer layer : layers) layer.hash(hasher);
    }

    private static Body[] append(Body[] items, int size, Body body){
        if(size == items.length) items = Arrays.copyOf(items, size * 7 / 4 + 1);
        items[size] = body;
        return items;
    }

    private static Body[] map(Body[] source, int size, CopyContext context){
        var out = new Body[source.length];
        for(int i = 0; i < size; i++) out[i] = context.map(source[i]);
        return out;
    }

    /**
     * {@code PhysicRef} and its {@code PhysicsBody}. Mass and radius are fixed when the body is made, as vanilla's are.
     * {@code local} is always true on a server and left out.
     */
    public static final class Body implements Copyable, Checkpointable{
        Unit unit;
        float x, y, radius, mass;
        boolean collided;
        /** The position at the last begin, so {@code x - startX} is the last step's push. */
        float startX, startY;
        int lastLayer;

        public Body(){
        }

        Body(Unit unit){
            this.unit = unit;
            x = unit.x;
            y = unit.y;
            mass = unit.mass();
            radius = unit.hitSize * RADIUS_SCALE;
            lastLayer = unit.collisionLayer();
            startX = unit.x;
            startY = unit.y;
        }

        @Override
        public Copyable blank(){
            return new Body();
        }

        @Override
        public void copyTo(Copyable into, CopyContext context){
            var out = (Body)into;
            out.unit = context.map(unit);
            out.x = x;
            out.y = y;
            out.radius = radius;
            out.mass = mass;
            out.collided = collided;
            out.startX = startX;
            out.startY = startY;
            out.lastLayer = lastLayer;
        }

        void hash(Hasher hasher){
            hasher.i(unit.id()).f(x).f(y).f(radius).f(mass).bool(collided).f(startX).f(startY).i(lastLayer);
        }

        @Override
        public void write(CheckpointOut out){
            out.ref("unit", unit).f("x", x).f("y", y).f("radius", radius).f("mass", mass).bool("collided", collided)
                .f("startX", startX).f("startY", startY).i("lastLayer", lastLayer);
        }

        @Override
        public void read(CheckpointIn in){
            unit = in.ref("unit", Unit.class);
            x = in.f("x");
            y = in.f("y");
            radius = in.f("radius");
            mass = in.f("mass");
            collided = in.bool("collided");
            startX = in.f("startX");
            startY = in.f("startY");
            lastLayer = in.i("lastLayer");
        }
    }

    /**
     * One {@code PhysicsWorld}: a layer's bodies in an unordered list, its {@code Rand}, and the tree of one step. The
     * bodies and the random are stored; the tree and the scratch arrays are refilled at every step.
     */
    public static final class Layer implements Checkpointable{
        Body[] bodies;
        int size;
        final Rand rand;
        @Derived("bind(): the map's quad bounds, and filled from the bodies at every step")
        private BodyTree tree;
        @Derived("step scratch, refilled from the bodies at every step")
        private float[] xs, ys, radii;
        @Derived("step scratch: the bodies in list order, for the fill")
        private int[] order;
        @Derived("step scratch, refilled at every step")
        private IntSeq candidates;

        /** A blank layer for a checkpoint to fill; the physics part binds it to the map. */
        public Layer(){
            this(new Rand(0), 0, 0);
        }

        Layer(Rand rand, int tilesWide, int tilesHigh){
            this.rand = rand;
            bodies = new Body[16];
            bind(tilesWide, tilesHigh);
            xs = new float[16];
            ys = new float[16];
            radii = new float[16];
            order = new int[16];
            candidates = new IntSeq(16);
        }

        void bind(int tilesWide, int tilesHigh){
            tree = BodyTree.of(tilesWide, tilesHigh);
        }

        @Override
        public void write(CheckpointOut out){
            out.refs("bodies", bodies, size).i("size", size).longs("rand", new long[]{rand.getState(0), rand.getState(1)});
        }

        @Override
        public void read(CheckpointIn in){
            bodies = UnitPhysics.bodies(in.refs("bodies", Body.class), size = in.i("size"), 16);
            long[] state = in.longs("rand");
            if(state == null || state.length != 2) throw new CheckpointException("a physics layer's random is corrupt");
            rand.setState(state[0], state[1]);
        }

        private Layer(Layer source, CopyContext context){
            rand = source.rand.copy();
            bodies = map(source.bodies, source.size, context);
            size = source.size;
            tree = source.tree.copy();
            xs = source.xs.clone();
            ys = source.ys.clone();
            radii = source.radii.clone();
            order = source.order.clone();
            candidates = source.candidates.copy();
        }

        Layer copy(CopyContext context){
            return new Layer(this, context);
        }

        void add(Body body){
            bodies = append(bodies, size++, body);
        }

        /** {@code Seq.remove(value)}: the first equal body, replaced by the last. */
        void remove(Body body){
            for(int i = 0; i < size; i++){
                if(bodies[i] == body){
                    bodies[i] = bodies[--size];
                    bodies[size] = null;
                    return;
                }
            }
        }

        /** {@code PhysicsWorld.update}, one iteration as on a server. */
        void step(){
            if(size == 0) return;
            if(xs.length < size){
                xs = new float[bodies.length];
                ys = new float[bodies.length];
                radii = new float[bodies.length];
                order = new int[bodies.length];
            }
            for(int i = 0; i < size; i++){
                Body body = bodies[i];
                xs[i] = body.x;
                ys[i] = body.y;
                radii[i] = body.radius;
                order[i] = i;
            }
            tree.fill(order, size, xs, ys, radii);
            for(int i = 0; i < size; i++) bodies[i].collided = false;
            var vec = new Vec2();

            for(int i = 0; i < size; i++){
                Body body = bodies[i];
                // tree.intersect(body.x - r, body.y - r, 2r, 2r, seq): every candidate is collected before any push
                candidates.size = 0;
                tree.intersect(body.x - body.radius, body.y - body.radius, body.radius * 2, body.radius * 2, bodies, candidates);
                int found = candidates.size;
                int[] items = candidates.items;

                for(int j = 0; j < found; j++){
                    Body other = bodies[items[j]];
                    if(other == body || other.collided) continue;

                    float rs = body.radius + other.radius;
                    float dx = body.x - other.x, dy = body.y - other.y;
                    float dst2 = dx * dx + dy * dy;
                    if(dst2 < rs * rs){
                        float dst = Mathf.sqrt(dst2);
                        vec.set(dx, dy);
                        if(vec.isZero()){
                            vec.trns(rand.random(360f), rs - dst);
                        }else{
                            vec.setLength(rs - dst);
                        }
                        float ms = body.mass + other.mass;
                        float m1 = other.mass / ms, m2 = body.mass / ms;
                        body.x += vec.x * m1 / SOFTEN;
                        body.y += vec.y * m1 / SOFTEN;
                        other.x -= vec.x * m2 / SOFTEN;
                        other.y -= vec.y * m2 / SOFTEN;
                    }
                }
                body.collided = true;
            }
        }

        void hash(Hasher hasher){
            hasher.i(size);
            for(int i = 0; i < size; i++) hasher.i(bodies[i].unit.id());
            hasher.l(rand.getState(0)).l(rand.getState(1));
        }
    }
}
