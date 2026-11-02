package com.xpdustry.shard.compat.v160.blocks.transport;

import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.KindKey;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import arc.func.*;
import arc.util.io.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.world.blocks.sandbox.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One fixture built in a vanilla world ({@link Sim}) and a Shard one, edited on the same frames and compared after
 * every frame: each building's state as {@link States} prints it, and every item a void takes (the frame, the void,
 * the item, where it came from). At the end, every building's save chunk. The transport and distribution oracles
 * share it; it is {@code RouterOracleTest}'s run, generalised over kinds.
 *
 * <p>Shard's voids are {@link RecordingVoid}; vanilla's are its own {@code ItemVoidBuild} with the same record,
 * through {@code Blocks.itemVoid.buildType}: call {@link #recordVoids()} and {@link #restoreVoids()} around a class.
 */
public final class BlockRun{
    public static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough().withKind(KindKey.of("item-void"), RecordingVoid.class, RecordingVoid::new);

    /** An item a void took: on which frame, which void, which item, from which building. */
    public record Arrival(int frame, int x, int y, String item, int fromX, int fromY){
    }

    static final List<Arrival> vanillaArrivals = new ArrayList<>(), shardArrivals = new ArrayList<>();
    static int frame;
    static Prov<mindustry.gen.Building> voidType;

    /** {@code ItemVoidBuild} in Shard, recording what it takes: {@code ItemVoid}'s two methods. */
    public static final class RecordingVoid extends Building{
        @Override
        public boolean acceptItem(Building source, int item){
            return enabled;
        }

        @Override
        public void handleItem(Building source, int item){
            var tiles = world().tiles();
            shardArrivals.add(new Arrival(frame, tiles.x(tile()), tiles.y(tile()), world().content().item(item).name(),
                tiles.x(source.tile()), tiles.y(source.tile())));
        }
    }

    public static void recordVoids(){
        Vanilla.boot();
        voidType = Blocks.itemVoid.buildType;
        Blocks.itemVoid.buildType = () -> ((ItemVoid)Blocks.itemVoid).new ItemVoidBuild(){
            @Override
            public void handleItem(mindustry.gen.Building source, mindustry.type.Item item){
                super.handleItem(source, item);
                vanillaArrivals.add(new Arrival(frame, tile.x, tile.y, item.name, source.tile.x, source.tile.y));
            }
        };
    }

    public static void restoreVoids(){
        Blocks.itemVoid.buildType = voidType;
    }

    public final Sim sim;
    /** Shard's world; {@link #continueFromSave} replaces it with the one loaded from vanilla's save. */
    public World shard;
    final Map<Integer, List<Runnable>> edits = new TreeMap<>();
    final List<mindustry.gen.Unit> vanillaUnits = new ArrayList<>();
    final List<com.xpdustry.shard.sim.unit.Unit> shardUnits = new ArrayList<>();
    /** Checks on the arrivals once the run ends; by default items flowed. */
    int minArrivals = 1;
    /** Whether the states include the absolute entity id counters: from {@link #continueFromSave}'s load on. */
    boolean compareIds;
    /** Run right after both worlds load vanilla's save ({@link #continueFromSave}). */
    final List<Runnable> afterLoad = new ArrayList<>();
    /** Extra state lines compared after every frame, vanilla's and Shard's ({@link #compareAlso}). */
    final List<Supplier<String>> vanillaExtras = new ArrayList<>(), shardExtras = new ArrayList<>();

    public BlockRun(int width, int height){
        vanillaArrivals.clear();
        shardArrivals.clear();
        frame = 0;
        sim = Sim.empty(width, height);
        // Unloader.ContainerStat's pool is static, and Shard's is per world: start both empty
        arc.util.pooling.Pools.get(mindustry.world.blocks.storage.Unloader.ContainerStat.class,
            mindustry.world.blocks.storage.Unloader.ContainerStat::new).clear();
        shard = new World(WorldConfig.of(EPOCH, width, height, 1).withKinds(KINDS));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, EPOCH.block("stone").id());
        shard.buildings().endLoad(false);
    }

    public BlockRun place(String block, int x, int y){
        return place(block, x, y, 0, Team.sharded);
    }

    public BlockRun place(String block, int x, int y, int rotation){
        return place(block, x, y, rotation, Team.sharded);
    }

    /** As a map editor places it: {@code tile.setBlock}. */
    public BlockRun place(String block, int x, int y, int rotation, Team team){
        sim.place(content.block(block), x, y, rotation, team);
        shard.buildings().setBlock(x, y, EPOCH.block(block), team.id, rotation, Cause.SERVER);
        return this;
    }

    /** A vanilla controller that does nothing, so only the blocks move the unit. */
    static final class Idle implements mindustry.entities.units.UnitController{
        mindustry.gen.Unit unit;

        @Override
        public void unit(mindustry.gen.Unit unit){
            this.unit = unit;
        }

        @Override
        public mindustry.gen.Unit unit(){
            return unit;
        }
    }

    /** Shard's controller that does nothing. */
    public static final class ShardIdle implements com.xpdustry.shard.sim.unit.UnitController{
        @Override
        public void updateUnit(com.xpdustry.shard.sim.unit.Unit unit){
        }

        @Override
        public com.xpdustry.shard.sim.unit.UnitController copy(CopyContext context){
            return new ShardIdle();
        }

        @Override
        public void hash(Hasher hasher){
        }
    }

    /** A unit of {@code type} at tile (x, y), facing +x, with a controller that does nothing, in both worlds. */
    public BlockRun spawn(String type, float x, float y){
        if(vanillaUnits.isEmpty()){
            // no cores, so a cap of 0: units would die as they spawn; then the same random from here on
            state.rules.disableUnitCap = true;
            shard.part(com.xpdustry.shard.sim.rules.WorldRules.class).rules.disableUnitCap = true;
            shard.rand().setState(arc.math.Mathf.rand.getState(0), arc.math.Mathf.rand.getState(1));
        }
        mindustry.gen.Unit vanilla = content.unit(type).spawn(Team.sharded, x * 8f, y * 8f, 0f);
        vanilla.controller(new Idle());
        Vanilla.runPosted();
        var ours = com.xpdustry.shard.sim.unit.Unit.spawn(shard, EPOCH.unit(type), Team.sharded.id, x * 8f, y * 8f, 0f);
        ours.controller(new ShardIdle());
        vanillaUnits.add(vanilla);
        shardUnits.add(ours);
        return this;
    }

    /** Adds items to the building at (x, y) in both worlds, as {@code items.add}. */
    public BlockRun stock(int x, int y, String item, int amount){
        sim.build(x, y).items.add(content.item(item), amount);
        shard.buildings().at(x, y).items.add(EPOCH.item(item).id(), amount);
        return this;
    }

    /** Removes the block at (x, y): {@code tile.setAir()}. */
    public BlockRun remove(int x, int y){
        world.tile(x, y).setAir();
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block("air"), 0, 0, Cause.SERVER);
        return this;
    }

    /** A line of {@code block} from (x, y), {@code length} long, each rotated {@code rotation}. */
    public BlockRun line(String block, int x, int y, int rotation, int length){
        for(int i = 0; i < length; i++){
            place(block, x + com.xpdustry.shard.common.geom.Geometry.d4x(rotation) * i,
                y + com.xpdustry.shard.common.geom.Geometry.d4y(rotation) * i, rotation);
        }
        return this;
    }

    /** {@code configureAny}: an item by name, or null to clear. */
    public BlockRun configure(int x, int y, String item){
        sim.build(x, y).configureAny(item == null ? null : content.item(item));
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(item == null ? null : ContentRef.item(EPOCH.item(item).id()));
        return this;
    }

    /**
     * A processor's {@code control enabled} on (x, y) from the processor at (px, py) (LExecutor.java:579): enabling
     * wakes the building, disabling records the processor as its {@code lastDisabler}, then the building's
     * {@code control}.
     */
    public void enabled(int x, int y, boolean enabled, int px, int py){
        mindustry.gen.Building vanilla = sim.build(x, y);
        Building ours = shard.buildings().at(x, y);
        if(enabled){
            vanilla.noSleep();
            ours.noSleep();
        }else{
            vanilla.lastDisabler = sim.build(px, py);
            ours.lastDisabler = shard.buildings().at(px, py);
        }
        vanilla.control(mindustry.logic.LAccess.enabled, enabled ? 1 : 0, 0, 0, 0);
        ours.control(com.xpdustry.shard.logic.lang.LAccess.enabled, enabled ? 1 : 0, 0, 0, 0);
    }

    /** As construction finishes it: {@code tile.setBlock}, then {@code placed()}, which autolinks power. */
    public BlockRun build(String block, int x, int y, int rotation){
        place(block, x, y, rotation);
        sim.build(x, y).placed();
        Vanilla.runPosted();
        shard.buildings().at(x, y).placed();
        return this;
    }

    /** {@code configureAny(value)} with a value per side: vanilla's object and Shard's. */
    public BlockRun configureAny(int x, int y, Object vanilla, Object ours){
        sim.build(x, y).configureAny(vanilla);
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(ours);
        return this;
    }

    /** {@code build.kill()}: {@code Call.buildDestroyed}. */
    public BlockRun kill(int x, int y){
        sim.build(x, y).kill();
        Vanilla.runPosted();
        shard.buildings().kill(shard.buildings().at(x, y));
        return this;
    }

    /** From here on, Shard's random is vanilla's, so draws can be compared ({@link #compareAlso}). */
    public BlockRun pinRandom(){
        shard.rand().setState(arc.math.Mathf.rand.getState(0), arc.math.Mathf.rand.getState(1));
        return this;
    }

    /** Also compares these lines after every frame: the random, bullets, whatever a fixture needs. */
    public BlockRun compareAlso(Supplier<String> vanilla, Supplier<String> ours){
        vanillaExtras.add(vanilla);
        shardExtras.add(ours);
        return this;
    }

    /** Runs {@code check} right after both worlds load vanilla's save, before they compare. */
    public BlockRun afterLoad(Runnable check){
        afterLoad.add(check);
        return this;
    }

    /**
     * After {@link #run}: runs on, compared as {@link #run} compares, until {@code condition} holds (checked before each
     * frame) or {@code max} frames pass. Returns the frames it ran.
     */
    public int runUntil(BooleanSupplier condition, int max){
        int ran = 0;
        while(!condition.getAsBoolean() && ran < max){
            frame++;
            ran++;
            sim.step(1);
            shard.tick();
            assertStates("after frame " + frame);
            assertEquals(vanillaArrivals, shardArrivals, "arrivals by frame " + frame);
        }
        return ran;
    }

    /**
     * After {@link #run}: vanilla saves its world and both load that save, as a server that loaded this save first
     * would: vanilla's random seeded 0 and its entity ids counted from 0 before its load, as Shard's loaded world
     * starts; then the pools a load leaves behind (a per-world history pin, ARCHITECTURE §1) are emptied, keeping the
     * random and the id counter. From the load on, the absolute id counters are compared too. The chunks and states
     * are compared, then both run on for {@code frames} frames, compared as before, and the chunks again.
     */
    public void continueFromSave(int frames){
        var file = new ByteArrayOutputStream();
        mindustry.io.SaveIO.write(file, new mindustry.io.SaveOptions());
        arc.math.Mathf.rand.setSeed(0);
        setLastId(0);
        mindustry.io.SaveIO.load(new ByteArrayInputStream(file.toByteArray()), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        HazardPools.empty();
        emptyBulletPool();
        var save = new com.xpdustry.shard.compat.v160.msav.MsavReader(EPOCH).read(file.toByteArray());
        var loader = new com.xpdustry.shard.compat.v160.msav.MapLoader(EPOCH, CODECS);
        shard = loader.load(save, WorldConfig.of(EPOCH, 1, 1, 0).withKinds(KINDS), loader.saveRules(save)).world();
        compareIds = true;
        for(Runnable check : afterLoad) check.run();
        assertChunksEqual();
        assertStates("loaded");
        int start = frame;
        for(int i = 1; i <= frames; i++){
            frame = start + i;
            sim.step(1);
            shard.tick();
            assertStates("after frame " + i + " from the save");
            assertEquals(vanillaArrivals, shardArrivals, "arrivals by frame " + i + " from the save");
        }
        assertChunksEqual();
    }

    /** Vanilla's entity id counter: the last id it gave out ({@code EntityGroup.lastId}). */
    public static int lastId(){
        try{
            var field = mindustry.entities.EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    static void setLastId(int id){
        try{
            var field = mindustry.entities.EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            field.setInt(null, id);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code Bullet}'s pool, which {@link HazardPools} leaves: a pooled bullet keeps the id its {@code reset()} drew
     * when freed, so after a load vanilla's first bolt would reuse an id from before the save. Emptied, as Shard's
     * loaded world starts with none, keeping the random and the id counter.
     */
    static void emptyBulletPool(){
        long seed0 = arc.math.Mathf.rand.seed0, seed1 = arc.math.Mathf.rand.seed1;
        try{
            var lastId = mindustry.entities.EntityGroup.class.getDeclaredField("lastId");
            lastId.setAccessible(true);
            int id = lastId.getInt(null);
            mindustry.gen.Bullet.create();
            arc.util.pooling.Pools.get(mindustry.gen.Bullet.class, () -> null).clear();
            lastId.setInt(null, id);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
        arc.math.Mathf.rand.setState(seed0, seed1);
    }

    public BlockRun at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
        return this;
    }

    /** Expects at least this many arrivals at the voids by the end (0: none needed). */
    public BlockRun expectArrivals(int count){
        minArrivals = count;
        return this;
    }

    /** Runs {@code frames} frames, comparing after each, then the save chunks. */
    public void run(int frames){
        assertStates("as placed");
        for(frame = 1; frame <= frames; frame++){
            for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
            sim.step(1);
            shard.tick();
            assertStates("after frame " + frame);
            assertEquals(vanillaArrivals, shardArrivals, "arrivals by frame " + frame);
        }
        frame = frames;
        assertTrue(vanillaArrivals.size() >= minArrivals, "items should flow: " + vanillaArrivals.size() + " arrivals");
        assertChunksEqual();
    }

    /** Each building's state, vanilla's against Shard's; a failure lists only the buildings that differ. */
    void assertStates(String when){
        List<String> expected = vanillaStates(), actual = shardStates();
        if(expected.equals(actual)) return;
        var differences = new StringBuilder(when + ":");
        for(int i = 0; i < Math.max(expected.size(), actual.size()); i++){
            String e = i < expected.size() ? expected.get(i) : "(none)", a = i < actual.size() ? actual.get(i) : "(none)";
            if(!e.equals(a)) differences.append("\n  vanilla ").append(e).append("\n  shard   ").append(a);
        }
        fail(differences.toString());
    }

    /** Every building's save chunk, vanilla's against Shard's. */
    public void assertChunksEqual(){
        var current = ContentHeader.of(EPOCH).mapping(EPOCH);
        for(mindustry.gen.Building expected : buildings()){
            Building actual = shard.buildings().at(expected.tile.x, expected.tile.y);
            assertEquals(hex(chunk(expected)), hex(CODECS.chunk(actual, EPOCH, current)),
                expected.block.name + " at (" + expected.tile.x + ", " + expected.tile.y + "): save chunk");
        }
    }

    /** The arrivals so far, vanilla's. */
    public List<Arrival> arrivals(){
        return List.copyOf(vanillaArrivals);
    }

    List<mindustry.gen.Building> buildings(){
        var out = new ArrayList<mindustry.gen.Building>();
        for(var tile : world.tiles) if(tile.build != null && tile.isCenter()) out.add(tile.build);
        return out;
    }

    List<String> vanillaStates(){
        var out = new ArrayList<String>();
        for(mindustry.gen.Building build : buildings()) out.add(States.vanilla(build, shard.tiles()));
        for(var unit : vanillaUnits) out.add(unit.type.name + " at " + unit.x + "," + unit.y + " vel " + unit.vel.x + "," + unit.vel.y);
        if(compareIds) out.add("next id " + lastId());
        for(var extra : vanillaExtras) out.add(extra.get());
        return out;
    }

    List<String> shardStates(){
        var out = new ArrayList<String>();
        for(mindustry.gen.Building expected : buildings()){
            Building build = shard.buildings().at(expected.tile.x, expected.tile.y);
            out.add(build == null ? "missing at (" + expected.tile.x + ", " + expected.tile.y + ")" : States.shard(build));
        }
        for(var unit : shardUnits) out.add(unit.type().name() + " at " + unit.x + "," + unit.y + " vel " + unit.vel.x + "," + unit.vel.y);
        if(compareIds) out.add("next id " + shard.ids().peek());
        for(var extra : shardExtras) out.add(extra.get());
        return out;
    }

    static String items(IntUnaryOperator amount){
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < EPOCH.items(); id++) if(amount.applyAsInt(id) != 0) out.add(EPOCH.item(id).name() + "=" + amount.applyAsInt(id));
        return out.toString();
    }

    /** What {@code writeMap} puts in a building's chunk: the revision, then {@code writeAll}. */
    public static byte[] chunk(mindustry.gen.Building building){
        var bytes = new ByteArrayOutputStream();
        var out = new Writes(new DataOutputStream(bytes));
        out.b(building.version());
        building.writeAll(out);
        return bytes.toByteArray();
    }

    static String hex(byte[] bytes){
        return HexFormat.of().formatHex(bytes);
    }
}
