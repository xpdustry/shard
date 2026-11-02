package com.xpdustry.shard.compat.v160.blocks;

import arc.func.*;
import arc.math.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.KindKey;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.liquid.DirectionLiquidBridge;
import com.xpdustry.shard.sim.blocks.sandbox.LiquidSource;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.world.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.sandbox.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One liquid fixture in a vanilla world ({@link Sim}) and a Shard one, built and edited in step: placements as a map
 * editor makes them, liquid configs, logic {@code enabled}, removals, kills, liquids poured by hand and puddles.
 * {@link #run} compares after every frame:
 * <ul>
 *   <li>the random generator's state, the entity id counter and the clock;</li>
 *   <li>the building update list, by position, in order (who sleeps, who woke when);</li>
 *   <li>every building: team, rotation, enabled, health, timers, every liquid it holds and its current one, and the
 *   source's liquid and the direction bridges' links;</li>
 *   <li>every fire and puddle with its state and the tile maps ({@link HazardStates});</li>
 *   <li>every amount a liquid void took, in order (the frame, the void, the liquid, the amount, where from).</li>
 * </ul>
 * Then every building's save chunk, and vanilla's save of the world, loaded by both sides as {@code HazardSaveOracleTest}
 * loads it, compared again and run on for {@link #AFTER_LOAD} frames.
 *
 * <p>Shard's voids are {@link RecordingVoid}, {@code LiquidVoid} with a record; vanilla's are its own
 * {@code LiquidVoidBuild} with the same record, through {@code Blocks.liquidVoid.buildType}: call
 * {@link #recordVoids()} and {@link #restoreVoids()} around a class.
 */
final class LiquidRun{
    static final Epoch EPOCH = Epoch.vanilla();
    static final int AFTER_LOAD = 120;
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough().withKind(KindKey.of("liquid-void"), RecordingVoid.class, RecordingVoid::new);
    static final MsavReader READER = new MsavReader(EPOCH);
    static final MapLoader LOADER = new MapLoader(EPOCH, CODECS);

    /** An amount a void took: on which frame, which void, which liquid, how much, from which building. */
    record Arrival(int frame, int x, int y, String liquid, float amount, int fromX, int fromY){
    }

    static final List<Arrival> vanillaArrivals = new ArrayList<>(), shardArrivals = new ArrayList<>();
    static int frame;
    static boolean recording;
    static Prov<mindustry.gen.Building> voidType;

    /** {@code LiquidVoidBuild} in Shard, recording what it takes: {@code LiquidVoid}'s three methods. */
    public static final class RecordingVoid extends Building{
        @Override
        public void placed(){
            super.placed();
            liquids.clear();
        }

        @Override
        public boolean acceptLiquid(Building source, int liquid){
            return enabled;
        }

        @Override
        public void handleLiquid(Building source, int liquid, float amount){
            if(recording) shardArrivals.add(new Arrival(frame, tileX(), tileY(), world().content().liquid(liquid).name(), amount, source.tileX(), source.tileY()));
        }
    }

    static void recordVoids(){
        Vanilla.boot();
        voidType = Blocks.liquidVoid.buildType;
        Blocks.liquidVoid.buildType = () -> ((LiquidVoid)Blocks.liquidVoid).new LiquidVoidBuild(){
            @Override
            public void handleLiquid(mindustry.gen.Building source, mindustry.type.Liquid liquid, float amount){
                super.handleLiquid(source, liquid, amount);
                if(recording) vanillaArrivals.add(new Arrival(frame, tile.x, tile.y, liquid.name, amount, source.tile.x, source.tile.y));
            }
        };
    }

    static void restoreVoids(){
        Blocks.liquidVoid.buildType = voidType;
    }

    final Sim sim;
    World shard;
    final Map<Integer, List<Runnable>> edits = new TreeMap<>();

    LiquidRun(int width, int height){
        vanillaArrivals.clear();
        shardArrivals.clear();
        frame = 0;
        recording = true;
        sim = Sim.empty(width, height);
        // Vanilla.reset seeds Mathf.rand with 0
        shard = new World(WorldConfig.of(EPOCH, width, height, 0).withKinds(KINDS));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, EPOCH.block("stone").id());
        shard.buildings().endLoad(false);
        HazardPools.empty();
    }

    LiquidRun place(String block, int x, int y){
        return place(block, x, y, 0);
    }

    /** As a map editor places it: {@code tile.setBlock}. */
    LiquidRun place(String block, int x, int y, int rotation){
        return place(block, x, y, rotation, Team.sharded);
    }

    LiquidRun place(String block, int x, int y, int rotation, Team team){
        sim.place(content.block(block), x, y, rotation, team);
        shard.buildings().setBlock(x, y, EPOCH.block(block), team.id, rotation, Cause.SERVER);
        return this;
    }

    /** A line of {@code block} from (x, y), {@code length} long, each facing {@code rotation}. */
    LiquidRun line(String block, int x, int y, int rotation, int length){
        for(int i = 0; i < length; i++){
            place(block, x + com.xpdustry.shard.common.geom.Geometry.d4x(rotation) * i, y + com.xpdustry.shard.common.geom.Geometry.d4y(rotation) * i, rotation);
        }
        return this;
    }

    /** {@code tile.setAir()}: the block is gone, as a deconstruction or an editor removes it. */
    LiquidRun remove(int x, int y){
        world.tile(x, y).setAir();
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block("air"), 0, 0, Cause.SERVER);
        return this;
    }

    /** {@code build.kill()}: destroyed, with its explosion and its liquids splashed. */
    LiquidRun kill(int x, int y){
        sim.build(x, y).kill();
        Vanilla.runPosted();
        shard.buildings().kill(shard.buildings().at(x, y));
        return this;
    }

    /** {@code configureAny}: a liquid source's liquid by name, or null to clear. */
    LiquidRun configure(int x, int y, String liquid){
        sim.build(x, y).configureAny(liquid == null ? null : content.liquid(liquid));
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(liquid == null ? null : ContentRef.liquid(EPOCH.liquid(liquid).id()));
        return this;
    }

    /** {@code configureAny(Integer)}: a bridge's link to the packed tile (tx, ty), or -1 for none (ItemBridge.java:68). */
    LiquidRun link(int x, int y, int tx, int ty){
        int packed = tx < 0 ? -1 : arc.math.geom.Point2.pack(tx, ty);
        sim.build(x, y).configureAny(packed);
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(packed);
        return this;
    }

    /** What a processor's {@code control enabled} does to the building; enabling also wakes it (LExecutor.java:579). */
    void enabled(int x, int y, boolean enabled){
        if(enabled){
            sim.build(x, y).noSleep();
            shard.buildings().at(x, y).noSleep();
        }
        sim.build(x, y).control(mindustry.logic.LAccess.enabled, enabled ? 1 : 0, 0, 0, 0);
        shard.buildings().at(x, y).control(com.xpdustry.shard.logic.lang.LAccess.enabled, enabled ? 1 : 0, 0, 0, 0);
    }

    /** Pours a liquid into a building by hand, as {@code liquids.add}, and wakes it. */
    void pour(int x, int y, String liquid, float amount){
        var vanilla = sim.build(x, y);
        vanilla.liquids.add(content.liquid(liquid), amount);
        vanilla.noSleep();
        Building ours = shard.buildings().at(x, y);
        ours.liquids.add(EPOCH.liquid(liquid).id(), amount);
        ours.noSleep();
    }

    LiquidRun at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
        return this;
    }

    /** {@link #at} every {@code step} frames from {@code from} to {@code to}, inclusive. */
    LiquidRun every(int from, int to, int step, Runnable edit){
        for(int when = from; when <= to; when += step) at(when, edit);
        return this;
    }

    /**
     * Applies each frame's edits, steps both worlds and compares them after every frame, then the save chunks; then
     * both load vanilla's save and run on, compared every frame.
     */
    void run(int frames){
        compare("as built");
        for(frame = 1; frame <= frames; frame++){
            List<Runnable> now = edits.getOrDefault(frame, List.of());
            for(Runnable edit : now) edit.run();
            if(!now.isEmpty()) compare("after the edits of frame " + frame);
            sim.step(1);
            shard.tick();
            compare("after frame " + frame);
        }
        chunks("save chunk");
        saveAndLoad();
        for(int i = 1; i <= AFTER_LOAD; i++){
            sim.step(1);
            shard.tick();
            compare("after frame " + i + " from the save");
        }
    }

    /** Vanilla's save of this world, loaded by both, as {@code HazardSaveOracleTest} does. */
    void saveAndLoad(){
        recording = false;
        var file = new ByteArrayOutputStream();
        SaveIO.write(file, new SaveOptions());
        Mathf.rand.setSeed(0);
        HazardStates.lastId(0);
        HazardPools.empty();
        SaveIO.load(new ByteArrayInputStream(file.toByteArray()), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        // the load's Groups.clear freed the old world's fires, puddles and bullets into vanilla's per-JVM pools
        HazardPools.empty();
        int ids = HazardStates.lastId();
        Bullet.create();
        arc.util.pooling.Pools.get(Bullet.class, () -> null).clear();
        HazardStates.lastId(ids);
        SaveFile save = READER.read(file.toByteArray());
        MapLoader.Loaded loaded = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save));
        assertNull(loaded.state().unknownDraws, "draws Shard doesn't count");
        shard = loaded.world();
        chunks("save chunk after loading vanilla's save");
        compare("loaded");
    }

    void compare(String when){
        assertEquals(String.join("\n", vanillaState()), String.join("\n", shardState()), () -> when + draws());
        assertEquals(vanillaArrivals, shardArrivals, "arrivals, " + when);
    }

    /** How many draws one side's generator is ahead of the other's, up to 64, or that they differ otherwise. */
    String draws(){
        long v0 = Mathf.rand.seed0, v1 = Mathf.rand.seed1;
        if(v0 == shard.rand().getState(0) && v1 == shard.rand().getState(1)) return "";
        var ahead = new com.xpdustry.shard.common.math.Rand(0L);
        ahead.setState(shard.rand().getState(0), shard.rand().getState(1));
        for(int i = 1; i <= 64; i++){
            ahead.nextLong();
            if(ahead.getState(0) == v0 && ahead.getState(1) == v1) return " (vanilla's random is " + i + " draws ahead)";
        }
        ahead.setState(v0, v1);
        for(int i = 1; i <= 64; i++){
            ahead.nextLong();
            if(ahead.getState(0) == shard.rand().getState(0) && ahead.getState(1) == shard.rand().getState(1)) return " (Shard's random is " + i + " draws ahead)";
        }
        return " (the randoms differ by more than 64 draws)";
    }

    /** Every building's chunk, vanilla's against Shard's. */
    void chunks(String what){
        var current = ContentHeader.of(EPOCH).mapping(EPOCH);
        for(mindustry.gen.Building expected : buildings()){
            Building actual = shard.buildings().at(expected.tile.x, expected.tile.y);
            if(actual instanceof PassthroughBuilding passthrough && !passthrough.hasBody()) continue;
            assertEquals(hex(chunk(expected)), hex(CODECS.chunk(actual, EPOCH, current)),
                expected.block.name + " at (" + expected.tile.x + ", " + expected.tile.y + "): " + what);
        }
    }

    List<mindustry.gen.Building> buildings(){
        var out = new ArrayList<mindustry.gen.Building>();
        for(var tile : world.tiles) if(tile.build != null && tile.isCenter()) out.add(tile.build);
        return out;
    }

    /** The vanilla building at a tile, for a fixture's own assertions. */
    mindustry.gen.Building vanilla(int x, int y){
        return sim.build(x, y);
    }

    // vanilla's side

    List<String> vanillaState(){
        var out = new ArrayList<String>();
        out.add(HazardStates.vanillaCounters() + " time " + arc.util.Time.time);
        var group = new StringJoiner(" ");
        Groups.build.each(build -> group.add(build.tile.x + "," + build.tile.y));
        out.add("updating " + group);
        for(var build : buildings()){
            var line = new StringBuilder(build.block.name + " (" + build.tile.x + ", " + build.tile.y + ") team " + build.team.id + " rotation "
                + build.rotation + " enabled " + build.enabled + " health " + build.health + " timers " + Arrays.toString(build.timer.getTimes()));
            if(build.liquids != null){
                line.append(" liquids ").append(liquids(id -> build.liquids.get(content.liquid(id)))).append(" current ")
                    .append(build.liquids.current().name);
            }
            switch(build){
                case mindustry.world.blocks.sandbox.LiquidSource.LiquidSourceBuild s -> line.append(" source ").append(s.source == null ? "none" : s.source.name);
                case DirectionBridge.DirectionBridgeBuild b -> {
                    line.append(" occupied");
                    for(var o : b.occupied) line.append(' ').append(o == null ? "-" : o.tile.x + "," + o.tile.y);
                    line.append(" last ").append(b.lastLink == null ? "-" : b.lastLink.tile.x + "," + b.lastLink.tile.y);
                }
                case ItemBridge.ItemBridgeBuild b -> line.append(bridge(b.link, b.incoming.toArray(), b.warmup, b.time, b.timeSpeed, b.wasMoved,
                    b.moved, b.hadValidLink, b.transportCounter, b.efficiency, b.power == null ? -1f : b.power.status));
                default -> {
                }
            }
            out.add(line.toString());
        }
        out.addAll(HazardStates.vanilla());
        return out;
    }

    // Shard's side

    List<String> shardState(){
        var out = new ArrayList<String>();
        out.add(HazardStates.shardCounters(shard) + " time " + shard.timers().time());
        var group = new StringJoiner(" ");
        var builds = shard.builds();
        for(int i = 0; i < builds.size(); i++) group.add(builds.get(i).tileX() + "," + builds.get(i).tileY());
        out.add("updating " + group);
        for(mindustry.gen.Building expected : buildings()){
            Building build = shard.buildings().at(expected.tile.x, expected.tile.y);
            if(build == null || build.tile() != shard.tiles().pos(expected.tile.x, expected.tile.y)){
                out.add("no building at (" + expected.tile.x + ", " + expected.tile.y + ")");
                continue;
            }
            var line = new StringBuilder(build.block().name() + " (" + build.tileX() + ", " + build.tileY() + ") team " + build.team + " rotation "
                + build.rotation + " enabled " + build.enabled + " health " + build.health + " timers " + Arrays.toString(build.timer));
            if(build.liquids != null){
                line.append(" liquids ").append(liquids(id -> build.liquids.get(id))).append(" current ")
                    .append(EPOCH.liquid(build.liquids.current()).name());
            }
            switch(build){
                case LiquidSource s -> line.append(" source ").append(s.source == -1 ? "none" : EPOCH.liquid(s.source).name());
                case DirectionLiquidBridge b -> {
                    line.append(" occupied");
                    for(var o : b.occupied) line.append(' ').append(o == null ? "-" : o.tileX() + "," + o.tileY());
                    line.append(" last ").append(b.lastLink == null ? "-" : b.lastLink.tileX() + "," + b.lastLink.tileY());
                }
                case com.xpdustry.shard.sim.blocks.distribution.ItemBridgeBuild b -> line.append(bridge(b.link, b.incoming.toArray(), b.warmup,
                    b.time, b.timeSpeed, b.wasMoved, b.moved, b.hadValidLink, b.transportCounter, b.efficiency, b.power == null ? -1f : b.power.status));
                default -> {
                }
            }
            out.add(line.toString());
        }
        out.addAll(HazardStates.shard(shard));
        return out;
    }

    static String bridge(int link, int[] incoming, float warmup, float time, float timeSpeed, boolean wasMoved, boolean moved,
                         boolean hadValidLink, float transportCounter, float efficiency, float power){
        return " link " + link + " incoming " + Arrays.toString(incoming) + " warmup " + warmup + " time " + time + " speed " + timeSpeed
            + " moved " + wasMoved + "/" + moved + " valid " + hadValidLink + " counter " + transportCounter + " efficiency " + efficiency
            + " power " + power;
    }

    static String liquids(IntFunction<Float> amount){
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < EPOCH.liquids(); id++) if(amount.apply(id) != 0f) out.add(EPOCH.liquid(id).name() + "=" + amount.apply(id));
        return out.toString();
    }

    static byte[] chunk(mindustry.gen.Building building){
        var bytes = new ByteArrayOutputStream();
        var out = new arc.util.io.Writes(new DataOutputStream(bytes));
        out.b(building.version());
        building.writeAll(out);
        return bytes.toByteArray();
    }

    static String hex(byte[] bytes){
        return HexFormat.of().formatHex(bytes);
    }
}
