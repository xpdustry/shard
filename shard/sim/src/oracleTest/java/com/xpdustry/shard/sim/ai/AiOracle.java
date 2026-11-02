package com.xpdustry.shard.sim.ai;

import arc.math.*;
import arc.util.pooling.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.entity.Entity;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.world.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The AI gates' harness: a vanilla world and a Shard world that start alike (map, wave rules, random, entity ids, an
 * empty bullet pool), vanilla's pathfinder driven frame by frame ({@link VanillaPathfinder}), and the per-frame
 * comparisons: units in group order with their AI's target and their mounts, buildings, bullets, weather, random and
 * ids. {@link #hosted} wraps a hosted map and its mirror instead, for the wave gates on bundled maps.
 *
 * <p>One pin besides the pathfinder's: vanilla's {@code PhysicsWorld.rand}, which pushes exactly stacked bodies apart
 * (a wave's flyers spawn on one point), is seeded from the clock; before the first frame it takes the state of
 * Shard's layer {@code Rand}s ({@code UnitPhysics}' documented difference).
 */
public final class AiOracle implements AutoCloseable{
    static final ContentEpoch VANILLA = VanillaWorlds.VANILLA;
    private static final Field TARGET, PHYSICS, WORLD_RAND, LAYERS, LAYER_RAND;

    static{
        try{
            TARGET = open(AIController.class.getDeclaredField("target"));
            PHYSICS = open(mindustry.async.PhysicsProcess.class.getDeclaredField("physics"));
            WORLD_RAND = open(mindustry.async.PhysicsProcess.PhysicsWorld.class.getDeclaredField("rand"));
            LAYERS = open(com.xpdustry.shard.sim.spatial.UnitPhysics.class.getDeclaredField("layers"));
            LAYER_RAND = open(Class.forName("com.xpdustry.shard.sim.spatial.UnitPhysics$Layer").getDeclaredField("rand"));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Field open(Field field){
        field.setAccessible(true);
        return field;
    }

    public final World world;
    final int vanillaBase, shardBase;
    private final List<Bullet> obtained = new ArrayList<>();
    private final TreeSet<Integer> shardSeen = new TreeSet<>();
    /** Vanilla's own bullet pool while the recorder stands in, or null when bullets made aren't compared. */
    private final Pool<Bullet> pool;
    private boolean physicsPinned;
    /** Vanilla's packed path tiles and wave ground weights before the last frame, or null before any frame. */
    private int[] pathTiles, pathWeights;

    /**
     * Fresh {@code size}-tile worlds of {@code floor} in wave mode with the countdown off, so the wave team is an AI
     * team that walks to the enemy cores; no unit cap, no fire (fires are WP61's). Place buildings, then units.
     */
    AiOracle(int size, String floor){
        Block vanillaFloor = content.block(floor);
        Vanilla.emptyWorld(size, size, vanillaFloor);
        arc.util.Time.setInternalTime(0);
        state.rules.disableUnitCap = true;
        state.rules.waves = true;
        state.rules.waveTimer = false;
        state.rules.fire = false;
        VanillaPathfinder.take();
        VanillaPathfinder.freshRings();
        Groups.updatePooling();
        Bullet.create();
        pool = Pools.get(Bullet.class, () -> null);
        pool.clear();
        Pools.set(Bullet.class, new Recorder(obtained));
        world = new World(WorldConfig.of(VANILLA, size, size, 1).withSystems(Systems.builtIn()).withKinds(KindRegistry.builtIn().withPassthrough()));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, VANILLA.block(floor).id());
        world.buildings().endLoad(false);
        for(WorldPart part : world.parts()) if(part instanceof com.xpdustry.shard.sim.path.PathService paths) paths.invalidateAll();
        Rules rules = world.part(WorldRules.class).rules;
        rules.disableUnitCap = true;
        rules.waves = true;
        rules.waveTimer = false;
        rules.fire = false;
        shardBase = world.ids().peek();
        world.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
        setStatic(EntityGroup.class, "lastId", shardBase);
        freshProcess();
        vanillaBase = lastId();
    }

    private AiOracle(World world){
        this.world = world;
        vanillaBase = shardBase = 0;
        pool = null;
        freshProcess();
    }

    /**
     * Vanilla state that lives as long as the process, back to a fresh JVM's, as each Shard world starts with its own.
     * The bullet pool and {@code Groups}' free queue: a pooled {@code Bullet} draws an entity id when it is built and
     * again when it is freed ({@code Bullet.reset}, at the start of the next {@code Groups.update}), so the ids a frame
     * draws depend on what the pool and the queue hold, and a gate's second map drew more or fewer than Shard. Then the lightning seed, and {@code Damage}'s static hit map and set, whose capacity (so the
     * order of an explosion's hits) grows with every explosion before (W2.7's lesson).
     */
    @SuppressWarnings("unchecked")
    private static void freshProcess(){
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
        try{
            // bullets removed in the last map's last frame, which the next Groups.update would free (and reset)
            ((arc.struct.Seq<?>)open(Groups.class.getDeclaredField("freeQueue")).get(null)).clear();
            var bullets = ((arc.struct.ObjectMap<Class<?>, Pool<?>>)open(Pools.class.getDeclaredField("typePools")).get(null)).get(Bullet.class);
            if(bullets != null) bullets.clear();
            ((arc.struct.IntFloatMap)open(mindustry.entities.Damage.class.getDeclaredField("damages")).get(null)).clear(51);
            ((arc.struct.IntSet)open(mindustry.entities.Damage.class.getDeclaredField("collidedBlocks")).get(null)).clear(51);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    /**
     * The map vanilla hosts and {@code world}, its mirror with the same ids and random: the pathfinder thread stops
     * and is pinned as for the fixtures; Shard's waits for its first flush. Ids are absolute, and the bullets made
     * aren't recorded (the live ones are compared).
     */
    public static AiOracle hosted(World world){
        VanillaPathfinder.take();
        VanillaPathfinder.freshRings();
        // as compat's MapLoader does after a load
        world.part(com.xpdustry.shard.sim.path.PathService.class).invalidateAll();
        return new AiOracle(world);
    }

    @Override
    public void close(){
        if(pool != null) Pools.set(Bullet.class, pool);
    }

    /** {@code block} for {@code team} with its origin at (x, y), on both sides. */
    void place(String block, int team, int x, int y){
        mindustry.Vars.world.tile(x, y).setBlock(content.block(block), mindustry.game.Team.get(team), 0);
        world.buildings().create(VANILLA.block(block), team, x, y, 0, Cause.SERVER);
    }

    /**
     * The flowfields a wave map's load preloads ({@code Pathfinder}'s {@code WorldLoadEvent}), which Shard's
     * {@code WaveFields} takes at the first flush: the oracle's world loaded with waves off, so the fixture asks
     * vanilla for the wave team's ground field once its buildings stand.
     */
    void preloadPaths(){
        pathfinder.getField(state.rules.waveTeam, mindustry.ai.Pathfinder.costGround, mindustry.ai.Pathfinder.fieldCore);
        VanillaPathfinder.settle();
    }

    /** A unit of {@code type} for {@code team} at (x, y), with its type's controller, on both sides. */
    void spawn(String type, int team, float x, float y){
        content.unit(type).spawn(mindustry.game.Team.get(team), x, y);
        Unit.spawn(world, VANILLA.unit(type), team, x, y, 0f);
    }

    /** One frame of each; vanilla's pathfinder catches up right after its frame. */
    public void frame(){
        pinPhysics();
        // what Shard's flush at the start of this tick must reproduce: vanilla's paths as the last frame left them
        pathTiles = VanillaPathfinder.tiles().clone();
        var field = VanillaPathfinder.field(state.rules.waveTeam.id, mindustry.ai.Pathfinder.costGround);
        pathWeights = field == null || !field.hasCompleteWeights() ? null : field.completeWeights.clone();
        VanillaPathfinder.beforeFrame();
        Vanilla.frame();
        VanillaPathfinder.settle();
        world.tick();
    }

    /** Once vanilla has its physics worlds: their stacked-body {@code Rand}s take the state of Shard's layers'. */
    private void pinPhysics(){
        if(physicsPinned) return;
        try{
            Object[] vanilla = (Object[])PHYSICS.get(mindustry.Vars.unitPhysics);
            if(vanilla == null) return;
            Object[] shard = (Object[])LAYERS.get(world.part(com.xpdustry.shard.sim.spatial.UnitPhysics.class));
            for(int i = 0; i < vanilla.length; i++){
                var from = (com.xpdustry.shard.common.math.Rand)LAYER_RAND.get(shard[i]);
                ((Rand)WORLD_RAND.get(vanilla[i])).setState(from.getState(0), from.getState(1));
            }
            physicsPinned = true;
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    /** Fails with {@code at} unless both sides are in the same state, naming every part that differs. */
    public void same(String at){
        int vanillaNext = lastId() - vanillaBase, shardNext = world.ids().peek() - shardBase;
        List<String> vanillaMade = pool == null ? List.of() : vanillaCreated(), shardMade = pool == null ? List.of() : shardCreated();
        List<String> vanillaUnits = vanillaUnits(), shardUnits = shardUnits();
        List<String> vanillaBuildings = vanillaBuildings(), shardBuildings = shardBuildings();
        List<String> vanillaBullets = vanillaBullets(), shardBullets = shardBullets();
        List<String> vanillaWeather = vanillaWeather(), shardWeather = shardWeather();
        assertAll(at,
            () -> assertEquals(Mathf.rand.seed0, world.rand().getState(0), "random state"),
            () -> assertEquals(Mathf.rand.seed1, world.rand().getState(1), "random state"),
            () -> assertEquals(vanillaNext, shardNext, () -> "the entity id counter; vanilla's newest entities: " + newest(Math.min(vanillaNext, shardNext))),
            () -> assertEquals(vanillaMade, shardMade, "bullets made"),
            () -> samePaths(at),
            () -> assertEquals(vanillaUnits, shardUnits, "units"),
            () -> assertEquals(vanillaBuildings, shardBuildings, "buildings"),
            () -> assertEquals(vanillaBullets, shardBullets, "live bullets"),
            () -> assertEquals(vanillaWeather, shardWeather, "weather"));
    }

    /** Vanilla's entities with ids from {@code from} on (relative), any kind: what drew the ids Shard didn't. */
    private List<String> newest(int from){
        var out = new ArrayList<String>();
        for(var entity : Groups.all){
            if(entity.id() - vanillaBase >= from) out.add("#" + (entity.id() - vanillaBase) + " " + entity.getClass().getSimpleName() + " " + entity);
        }
        return out;
    }

    // paths

    /**
     * Fails with {@code at} unless Shard's path view and the wave team's ground field equal vanilla's packed tiles and
     * weights as they stood before the last frame, naming the first tiles that differ: Shard's flush at the start of a
     * tick takes the last tick's changes, which vanilla packed as they happened and refreshed after its frame.
     */
    void samePaths(String at){
        if(pathTiles == null) return;
        var paths = world.part(com.xpdustry.shard.sim.path.PathService.class);
        var diffs = new ArrayList<String>();
        int width = mindustry.Vars.world.width();
        for(int pos = 0; pos < pathTiles.length && diffs.size() < 8; pos++){
            int shard = paths.packed(pos) & com.xpdustry.shard.sim.path.PathTile.VANILLA_BITS;
            if(pathTiles[pos] != shard) diffs.add("tile " + pos % width + "," + pos / width + " " + Integer.toHexString(pathTiles[pos]) + " vs " + Integer.toHexString(shard));
        }
        assertEquals(List.of(), diffs, at + ": packed path tiles");
        if(pathWeights == null) return;
        int handle = paths.field(state.rules.waveTeam.id, com.xpdustry.shard.sim.path.PathCost.GROUND);
        try{
            if(!paths.ready(handle)) return;
            for(int pos = 0; pos < pathWeights.length && diffs.size() < 8; pos++){
                int v = pathWeights[pos], s = paths.distance(handle, pos);
                // vanilla's first build overwrites its targets' 0 (VanillaFieldsTest); a wave field's targets are cores
                if(v != s && s != 0) diffs.add("weight " + pos % width + "," + pos / width + " " + v + " vs " + s);
            }
        }finally{
            paths.release(handle);
        }
        assertEquals(List.of(), diffs, at + ": the wave team's ground field");
    }

    // units

    List<String> vanillaUnits(){
        var out = new ArrayList<String>();
        for(mindustry.gen.Unit u : Groups.unit){
            Object target = u.controller() instanceof AIController ai ? get(ai) : null;
            var mounts = new StringBuilder();
            for(mindustry.entities.units.WeaponMount m : u.mounts){
                mounts.append(" [").append(bits(m.reload)).append(' ').append(bits(m.rotation)).append(' ').append(m.totalShots).append(' ')
                    .append(bits(m.aimX)).append(',').append(bits(m.aimY)).append(' ').append(m.shoot).append(' ').append(name(m.target)).append(']');
            }
            out.add(unit(u.id - vanillaBase, u.type.name, u.team.id, u.x, u.y, u.rotation, u.vel.x, u.vel.y, u.health, u.elevation, u.dead,
                name(target)) + mounts);
        }
        return out;
    }

    List<String> shardUnits(){
        var out = new ArrayList<String>();
        for(int i = 0; i < world.units().size(); i++){
            Unit u = world.units().get(i);
            Entity target = u.controller instanceof AiController ai ? ai.target : null;
            var mounts = new StringBuilder();
            for(com.xpdustry.shard.sim.unit.WeaponMount m : u.mounts){
                mounts.append(" [").append(bits(m.reload)).append(' ').append(bits(m.rotation)).append(' ').append(m.totalShots).append(' ')
                    .append(bits(m.aimX)).append(',').append(bits(m.aimY)).append(' ').append(m.shoot).append(' ').append(name(m.target)).append(']');
            }
            out.add(unit(u.id() - shardBase, u.type().name(), u.team, u.x, u.y, u.rotation, u.vel.x, u.vel.y, u.health, u.elevation, u.dead,
                name(target)) + mounts);
        }
        return out;
    }

    private static String unit(int id, String type, int team, float x, float y, float rotation, float vx, float vy, float health,
                               float elevation, boolean dead, String target){
        return "#" + id + " " + type + " t" + team + " at " + bits(x) + "," + bits(y) + " rot " + bits(rotation) + " v " + bits(vx) + ","
            + bits(vy) + " hp " + bits(health) + " el " + bits(elevation) + (dead ? " dead" : "") + " target " + target;
    }

    private static Object get(AIController ai){
        try{
            return TARGET.get(ai);
        }catch(IllegalAccessException e){
            throw new AssertionError(e);
        }
    }

    /** A target's name: a unit by relative id, a building by tile. */
    String name(Object target){
        return switch(target){
            case null -> "-";
            case mindustry.gen.Unit u -> "unit#" + (u.id - vanillaBase);
            case mindustry.gen.Building b -> "build@" + b.tile.x + "," + b.tile.y;
            case Unit u -> "unit#" + (u.id() - shardBase);
            case Building b -> "build@" + b.tileX() + "," + b.tileY();
            default -> target.toString();
        };
    }

    // buildings

    List<String> vanillaBuildings(){
        var out = new ArrayList<String>();
        // every standing building, updating or not (walls aren't in Groups.build)
        for(Tile tile : mindustry.Vars.world.tiles){
            var b = tile.build;
            if(b != null && b.tile == tile) out.add(b.block.name + "@" + b.tile.x + "," + b.tile.y + " t" + b.team.id + " hp " + bits(b.health));
        }
        out.sort(null);
        return out;
    }

    List<String> shardBuildings(){
        var out = new ArrayList<String>();
        for(int i = 0; i < world.buildings().indexLimit(); i++){
            Building b = world.buildings().get(i);
            if(b == null || !b.isValid()) continue;
            out.add(b.block().name() + "@" + b.tileX() + "," + b.tileY() + " t" + b.team + " hp " + bits(b.health));
        }
        out.sort(null);
        return out;
    }

    // bullets

    List<String> vanillaCreated(){
        var out = new ArrayList<String>();
        for(Bullet b : obtained) out.add(String.format("%08d type %d at %s,%s", b.id - vanillaBase, b.type.id, b.originX, b.originY));
        obtained.clear();
        out.sort(null);
        return out;
    }

    List<String> shardCreated(){
        var out = new ArrayList<String>();
        for(Bullets table : List.of(world.bullets(), world.bullets().limbo())){
            for(int row = 0; row < table.size(); row++){
                if(!shardSeen.add(table.id[row])) continue;
                out.add(String.format("%08d type %d at %s,%s", table.id[row] - shardBase, table.type[row], table.originX[row], table.originY[row]));
            }
        }
        out.sort(null);
        return out;
    }

    List<String> vanillaBullets(){
        var out = new ArrayList<String>();
        for(Bullet b : Groups.bullet){
            out.add((b.id - vanillaBase) + " t" + b.type.id + " (" + bits(b.x) + ", " + bits(b.y) + ") v(" + bits(b.vel.x) + ", " + bits(b.vel.y)
                + ") time " + bits(b.time) + " dmg " + bits(b.damage));
        }
        return out;
    }

    List<String> shardBullets(){
        var out = new ArrayList<String>();
        Bullets t = world.bullets();
        for(int r = 0; r < t.size(); r++){
            out.add((t.id[r] - shardBase) + " t" + t.type[r] + " (" + bits(t.x[r]) + ", " + bits(t.y[r]) + ") v(" + bits(t.velX[r]) + ", "
                + bits(t.velY[r]) + ") time " + bits(t.time[r]) + " dmg " + bits(t.damage[r]));
        }
        return out;
    }

    // weather, whose wind pushes every unit

    List<String> vanillaWeather(){
        var out = new ArrayList<String>();
        for(mindustry.gen.WeatherState w : Groups.weather){
            out.add((w.id - vanillaBase) + " " + w.weather.id + " intensity " + bits(w.intensity) + " opacity " + bits(w.opacity) + " life " + bits(w.life)
                + " wind " + bits(w.windVector.x) + "," + bits(w.windVector.y));
        }
        return out;
    }

    List<String> shardWeather(){
        var out = new ArrayList<String>();
        var weathers = com.xpdustry.shard.sim.weather.Weathers.of(world);
        if(weathers == null) return out;
        for(int i = 0; i < weathers.list().size(); i++){
            var w = weathers.list().get(i);
            out.add((w.id() - shardBase) + " " + w.weather + " intensity " + bits(w.intensity) + " opacity " + bits(w.opacity) + " life " + bits(w.life)
                + " wind " + bits(w.windVector.x) + "," + bits(w.windVector.y));
        }
        return out;
    }

    // plumbing

    static String bits(float value){
        return value + "(" + Integer.toHexString(Float.floatToRawIntBits(value)) + ")";
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

    static void setStatic(Class<?> owner, String name, int value){
        try{
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(null, value);
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
}
