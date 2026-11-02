package com.xpdustry.shard.compat.v160.blocks.production;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.world.*;
import arc.math.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.world.blocks.production.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One production fixture in a vanilla world ({@link Sim}) and a Shard one, built and edited in step: floors, ores and
 * walls under and in front of the blocks, placements as a map editor makes them ({@code tile.setBlock}), items and
 * liquids given by hand, logic {@code enabled}, removals. {@link #run} compares after every frame:
 * <ul>
 *   <li>the random generator's state, the entity id counter and {@code Time.time};</li>
 *   <li>every building: id, team, enabled, whether it updates, efficiency and optional efficiency, timers, items,
 *   liquids, power status, and the fields of the production kinds (drills, beam drills, wall crafters, pumps, solid
 *   pumps, frackers) and of conveyors (every item's id and position);</li>
 * </ul>
 * then every building's save chunk. It then has vanilla save the world, loads that save on both sides (vanilla's
 * {@code load} command, Shard's {@link MapLoader#load}), compares the chunks and states again and runs on for
 * {@link #AFTER_LOAD} more frames, compared the same way.
 *
 * <p>The random and both clocks start at 0 on both sides ({@code Time.setInternalTime(0)}: {@code Vanilla.reset}
 * leaves Arc's raw clock running).
 *
 * <p>{@link #load} starts from a bundled map's own save instead, loaded as the server's {@code load} command loads it
 * on both sides; {@link #frames} then runs it, compared the same way.
 */
final class ProductionRun{
    static final Epoch EPOCH = Worlds.EPOCH;
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();
    static final MsavReader READER = new MsavReader(EPOCH);
    static final MapLoader LOADER = new MapLoader(EPOCH, CODECS);
    /** Frames run after the save is loaded. */
    static final int AFTER_LOAD = 300;

    final Sim sim;
    World shard;
    final Map<Integer, List<Runnable>> edits = new TreeMap<>();
    int frame;

    ProductionRun(int width, int height){
        sim = Sim.empty(width, height);
        Time.setInternalTime(0);
        // Vanilla.reset seeds Mathf.rand with 0
        shard = new World(WorldConfig.of(EPOCH, width, height, 0).withKinds(KINDS));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, EPOCH.block("stone").id());
        shard.buildings().endLoad(false);
    }

    private ProductionRun(String map){
        this(com.xpdustry.shard.testkit.BundledMaps.read(map));
    }

    /** A save or map file, deflated as vanilla writes it, loaded on both sides as the server's {@code load} command loads it. */
    private ProductionRun(byte[] file){
        // an empty world to start from, which the save replaces
        sim = Sim.empty(1, 1);
        freshProcess();
        SaveIO.load(new java.util.zip.InflaterInputStream(new ByteArrayInputStream(file)), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        // the empty world's entities, freed by the load, are drawn for on the next frame: a fresh process has none
        HazardPools.empty();
        SaveFile save = READER.read(file);
        shard = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save)).world();
        // vanilla's clock keeps running across a load, as Shard models a server that played only this game
        Time.setInternalTime(shard.timers().time());
    }

    /** A bundled map's own save, loaded on both sides as the server's {@code load} command loads it. */
    static ProductionRun load(String map){
        return new ProductionRun(map);
    }

    /** {@link #load(String)} from a file's bytes (W5.4: a fixture with data patches, whose content each side patches). */
    static ProductionRun loadFile(byte[] file){
        return new ProductionRun(file);
    }

    // ---- building the fixture

    /** Sets a floor in both worlds. */
    void floor(int x, int y, String floor){
        world.tile(x, y).setFloor(content.block(floor).asFloor());
        shard.buildings().setFloor(x, y, EPOCH.block(floor), Cause.SERVER);
    }

    /** {@link #floor} over a rectangle. */
    void floors(int x, int y, int width, int height, String floor){
        for(int dx = 0; dx < width; dx++) for(int dy = 0; dy < height; dy++) floor(x + dx, y + dy, floor);
    }

    /** Sets an ore (or any overlay, {@code air} to clear) in both worlds: {@code tile.setOverlay}. */
    void ore(int x, int y, String overlay){
        world.tile(x, y).setOverlay(content.block(overlay));
        Vanilla.runPosted();
        shard.buildings().setOverlay(x, y, EPOCH.block(overlay), Cause.SERVER);
    }

    /** A static block (a wall) for no team: {@code tile.setBlock}. */
    void wall(int x, int y, String block){
        world.tile(x, y).setBlock(content.block(block));
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block(block), 0, 0, Cause.SERVER);
    }

    /** As a map editor places it: {@code tile.setBlock} for team sharded. */
    void place(String block, int x, int y){
        place(block, x, y, 0);
    }

    void place(String block, int x, int y, int rotation){
        sim.place(content.block(block), x, y, rotation, Team.sharded);
        shard.buildings().setBlock(x, y, EPOCH.block(block), Team.sharded.id, rotation, Cause.SERVER);
    }

    /** A line of {@code block} from (x, y), {@code length} long, each rotated {@code rotation}. */
    void line(String block, int x, int y, int rotation, int length){
        for(int i = 0; i < length; i++){
            place(block, x + com.xpdustry.shard.common.geom.Geometry.d4x(rotation) * i, y + com.xpdustry.shard.common.geom.Geometry.d4y(rotation) * i, rotation);
        }
    }

    /** {@code tile.setAir()}. */
    void remove(int x, int y){
        world.tile(x, y).setAir();
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block("air"), 0, 0, Cause.SERVER);
    }

    /** {@code item-source} output. */
    void source(int x, int y, String item){
        sim.build(x, y).configureAny(content.item(item));
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(ContentRef.item(EPOCH.item(item).id()));
    }

    /** What a processor's {@code control enabled} does to the building. */
    void enabled(int x, int y, boolean enabled){
        if(enabled){
            sim.build(x, y).noSleep();
            shard.buildings().at(x, y).noSleep();
        }
        sim.build(x, y).control(mindustry.logic.LAccess.enabled, enabled ? 1 : 0, 0, 0, 0);
        shard.buildings().at(x, y).control(com.xpdustry.shard.logic.lang.LAccess.enabled, enabled ? 1 : 0, 0, 0, 0);
    }

    void items(int x, int y, String item, int amount){
        sim.build(x, y).items.add(content.item(item), amount);
        shard.buildings().at(x, y).items.add(EPOCH.item(item).id(), amount);
    }

    void liquid(int x, int y, String liquid, float amount){
        sim.build(x, y).liquids.add(content.liquid(liquid), amount);
        shard.buildings().at(x, y).liquids.add(EPOCH.liquid(liquid).id(), amount);
    }

    /** Takes liquid out by hand, if the building holds at least {@code amount} (vanilla's, which Shard's equals). */
    void drain(int x, int y, String liquid, float amount){
        if(sim.build(x, y).liquids.get(content.liquid(liquid)) < amount) return;
        sim.build(x, y).liquids.remove(content.liquid(liquid), amount);
        shard.buildings().at(x, y).liquids.remove(EPOCH.liquid(liquid).id(), amount);
    }

    /** An overdrive's or a slowdown's {@code timeScale}: {@code applyBoost}. */
    void boost(int x, int y, float intensity, float duration){
        sim.build(x, y).applyBoost(intensity, duration);
        shard.buildings().at(x, y).applyBoost(intensity, duration);
    }

    void at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
    }

    /** {@link #at} every {@code step} frames from {@code from} to {@code to}, inclusive. */
    void every(int from, int to, int step, Runnable edit){
        for(int when = from; when <= to; when += step) at(when, edit);
    }

    // ---- running

    /** Runs {@code frames} frames compared after each, the save chunks, then the save loaded on both sides and run on. */
    void run(int frames){
        compare("as built");
        for(frame = 1; frame <= frames; frame++){
            for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
            if(edits.containsKey(frame)) compare("after the edits of frame " + frame);
            sim.step(1);
            shard.tick();
            compare("after frame " + frame);
        }
        chunks("save chunk");
        reload(new SaveOptions(), loaded -> {
        });
    }

    /**
     * Vanilla saves the world with {@code options} and both sides load the save, as the server's {@code load} command
     * does, which plays again; {@code check} sees Shard's load before the chunks and states are compared, and then
     * {@link #AFTER_LOAD} frames run, compared the same way.
     */
    void reload(SaveOptions options, java.util.function.Consumer<MapLoader.Loaded> check){
        var file = new ByteArrayOutputStream();
        SaveIO.write(file, options);
        freshProcess();
        SaveIO.load(new ByteArrayInputStream(file.toByteArray()), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        HazardPools.empty();
        SaveFile save = READER.read(file.toByteArray());
        MapLoader.Loaded loaded = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save));
        shard = loaded.world();
        check.accept(loaded);
        chunks("save chunk after loading vanilla's save");
        compare("loaded");
        for(int i = 1; i <= AFTER_LOAD; i++){
            sim.step(1);
            shard.tick();
            compare("after frame " + i + " from the save");
        }
    }

    /** Runs {@code frames} frames compared after each, then the save chunks; no save and load. */
    void frames(int frames){
        compare("loaded");
        for(frame = 1; frame <= frames; frame++){
            sim.step(1);
            shard.tick();
            compare("after frame " + frame);
        }
        chunks("save chunk");
    }

    void compare(String when){
        List<String> expected = vanillaState(), actual = shardState();
        if(expected.equals(actual)) return;
        var differences = new StringBuilder(when + ":");
        for(int i = 0; i < Math.max(expected.size(), actual.size()); i++){
            String e = i < expected.size() ? expected.get(i) : "(none)", a = i < actual.size() ? actual.get(i) : "(none)";
            if(!e.equals(a)) differences.append("\n  vanilla ").append(e).append("\n  shard   ").append(a);
        }
        fail(differences.toString());
    }

    /** Every building's save chunk, vanilla's against Shard's, but those of live-placed unported blocks, which have none. */
    void chunks(String what){
        var current = ContentHeader.of(EPOCH).mapping(EPOCH);
        for(mindustry.gen.Building expected : buildings()){
            Building actual = shard.buildings().at(expected.tile.x, expected.tile.y);
            if(actual instanceof PassthroughBuilding passthrough && !passthrough.hasBody()) continue;
            assertEquals(hex(chunk(expected)), hex(CODECS.chunk(actual, shard.content(), current)),
                expected.block.name + " at (" + expected.tile.x + ", " + expected.tile.y + "): " + what);
        }
    }

    mindustry.gen.Building vanilla(int x, int y){
        return sim.build(x, y);
    }

    Building ours(int x, int y){
        return shard.buildings().at(x, y);
    }

    List<mindustry.gen.Building> buildings(){
        var out = new ArrayList<mindustry.gen.Building>();
        for(var tile : world.tiles) if(tile.build != null && tile.isCenter()) out.add(tile.build);
        return out;
    }

    // ---- vanilla's side

    List<String> vanillaState(){
        var out = new ArrayList<String>();
        out.add("rand " + Mathf.rand.seed0 + " " + Mathf.rand.seed1 + " next id " + lastId() + " time " + Time.time);
        for(mindustry.gen.Building build : buildings()){
            var line = new StringBuilder(build.block.name + " (" + build.tile.x + ", " + build.tile.y + ") id " + build.id + " team " + build.team.id
                + " rotation " + build.rotation + " enabled " + build.enabled + " added " + build.isAdded() + " efficiency " + build.efficiency
                + " optional " + build.optionalEfficiency + " timeScale " + build.timeScale() + " timers " + Arrays.toString(build.timer.getTimes()));
            if(build.items != null) line.append(" items ").append(items(id -> build.items.get(id)));
            if(build.liquids != null) line.append(" liquids ").append(liquids(id -> build.liquids.get(content.liquid(id))));
            if(build.power != null) line.append(" power ").append(build.power.status);
            switch(build){
                case BurstDrill.BurstDrillBuild d -> line.append(drill(d)).append(" smooth ").append(d.smoothProgress).append(" invert ").append(d.invertTime);
                case Drill.DrillBuild d -> line.append(drill(d));
                case BeamDrill.BeamDrillBuild d -> {
                    line.append(" lastItem ").append(id(d.lastItem)).append(" time ").append(d.time).append(" warmup ").append(d.warmup)
                        .append(" boostWarmup ").append(d.boostWarmup).append(" lastDrillSpeed ").append(d.lastDrillSpeed)
                        .append(" facingAmount ").append(d.facingAmount).append(" facing");
                    for(var tile : d.facing) line.append(' ').append(tile == null ? "-" : tile.x + "," + tile.y);
                    line.append(" lasers");
                    for(var laser : d.lasers) line.append(' ').append(laser == null ? "-" : laser.x + "," + laser.y);
                }
                case WallCrafter.WallCrafterBuild c -> line.append(" time ").append(c.time).append(" warmup ").append(c.warmup)
                    .append(" totalTime ").append(c.totalTime).append(" lastEfficiency ").append(c.lastEfficiency);
                case Fracker.FrackerBuild f -> line.append(solidPump(f)).append(" accumulator ").append(f.accumulator);
                case SolidPump.SolidPumpBuild p -> line.append(solidPump(p));
                case Pump.PumpBuild p -> line.append(" warmup ").append(p.warmup).append(" totalProgress ").append(p.totalProgress)
                    .append(" consTimer ").append(p.consTimer).append(" amount ").append(p.amount).append(" liquidDrop ").append(liquidId(p.liquidDrop));
                case mindustry.world.blocks.distribution.Conveyor.ConveyorBuild c -> {
                    line.append(" len ").append(c.len).append(" slots");
                    for(int i = 0; i < c.len; i++) line.append(' ').append(id(c.ids[i])).append('@').append(c.xs[i]).append(',').append(c.ys[i]);
                    line.append(" minitem ").append(c.minitem).append(" mid ").append(c.mid);
                }
                default -> {
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    static String drill(Drill.DrillBuild d){
        return " progress " + d.progress + " warmup " + d.warmup + " timeDrilled " + d.timeDrilled + " lastDrillSpeed " + d.lastDrillSpeed
            + " dominant " + id(d.dominantItem) + "x" + d.dominantItems;
    }

    static String solidPump(SolidPump.SolidPumpBuild p){
        // the solid pump's own warmup; the pump's it hides stays 0
        return " warmup " + p.warmup + " pumpWarmup " + ((Pump.PumpBuild)p).warmup + " pumpTime " + p.pumpTime + " boost " + p.boost
            + " validTiles " + p.validTiles + " lastPump " + p.lastPump + " liquidDrop " + liquidId(p.liquidDrop);
    }

    static int id(mindustry.type.Item item){
        return item == null ? -1 : item.id;
    }

    static int liquidId(mindustry.type.Liquid liquid){
        return liquid == null ? -1 : liquid.id;
    }

    /**
     * Vanilla's random and entity id counter as a fresh process has them, before a load: Shard's loaded world starts
     * both at 0, and vanilla's {@code SaveIO.load} resets neither.
     */
    static void freshProcess(){
        Mathf.rand.setSeed(0);
        try{
            Field field = mindustry.entities.EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            field.setInt(null, 0);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    static int lastId(){
        try{
            Field field = mindustry.entities.EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    // ---- Shard's side

    List<String> shardState(){
        var out = new ArrayList<String>();
        out.add("rand " + shard.rand().seed0 + " " + shard.rand().seed1 + " next id " + shard.ids().peek() + " time " + shard.timers().time());
        var tiles = shard.tiles();
        for(mindustry.gen.Building expected : buildings()){
            Building build = shard.buildings().at(expected.tile.x, expected.tile.y);
            if(build == null || build.tile() != tiles.pos(expected.tile.x, expected.tile.y)){
                out.add("no building at (" + expected.tile.x + ", " + expected.tile.y + ")");
                continue;
            }
            var line = new StringBuilder(build.block().name() + " (" + build.tileX() + ", " + build.tileY() + ") id " + build.id() + " team "
                + build.team + " rotation " + build.rotation + " enabled " + build.enabled + " added " + build.isAdded() + " efficiency "
                + build.efficiency + " optional " + build.optionalEfficiency + " timeScale " + build.timeScale + " timers " + Arrays.toString(build.timer));
            if(build.items != null) line.append(" items ").append(items(id -> build.items.get(id)));
            if(build.liquids != null) line.append(" liquids ").append(liquids(id -> build.liquids.get(id)));
            if(build.power != null) line.append(" power ").append(build.power.status);
            switch(build){
                case com.xpdustry.shard.sim.blocks.production.BurstDrill d -> line.append(drill(d)).append(" smooth ").append(d.smoothProgress)
                    .append(" invert ").append(d.invertTime);
                case com.xpdustry.shard.sim.blocks.production.Drill d -> line.append(drill(d));
                case com.xpdustry.shard.sim.blocks.production.BeamDrill d -> {
                    line.append(" lastItem ").append(d.lastItem).append(" time ").append(d.time).append(" warmup ").append(d.warmup)
                        .append(" boostWarmup ").append(d.boostWarmup).append(" lastDrillSpeed ").append(d.lastDrillSpeed)
                        .append(" facingAmount ").append(d.facingAmount).append(" facing");
                    for(int tile : d.facing) line.append(' ').append(tile == -1 ? "-" : tiles.x(tile) + "," + tiles.y(tile));
                    line.append(" lasers");
                    for(int i = 0; i < d.block().size(); i++){
                        line.append(' ').append(d.lasers == null ? "-" : com.xpdustry.shard.common.geom.Point2.x(d.lasers[i]) + ","
                            + com.xpdustry.shard.common.geom.Point2.y(d.lasers[i]));
                    }
                }
                case com.xpdustry.shard.sim.blocks.production.WallCrafter c -> line.append(" time ").append(c.time).append(" warmup ").append(c.warmup)
                    .append(" totalTime ").append(c.totalTime).append(" lastEfficiency ").append(c.lastEfficiency);
                case com.xpdustry.shard.sim.blocks.production.Fracker f -> line.append(solidPump(f)).append(" accumulator ").append(f.accumulator);
                case com.xpdustry.shard.sim.blocks.production.SolidPump p -> line.append(solidPump(p));
                case com.xpdustry.shard.sim.blocks.production.Pump p -> line.append(" warmup ").append(p.warmup).append(" totalProgress ")
                    .append(p.totalProgress).append(" consTimer ").append(p.consTimer).append(" amount ").append(p.amount).append(" liquidDrop ")
                    .append(p.liquidDrop);
                case com.xpdustry.shard.sim.blocks.transport.ConveyorBuild c -> {
                    line.append(" len ").append(c.len).append(" slots");
                    for(int i = 0; i < c.len; i++) line.append(' ').append(c.ids[i]).append('@').append(c.xs[i]).append(',').append(c.ys[i]);
                    line.append(" minitem ").append(c.minitem).append(" mid ").append(c.mid);
                }
                default -> {
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    static String drill(com.xpdustry.shard.sim.blocks.production.Drill d){
        return " progress " + d.progress + " warmup " + d.warmup + " timeDrilled " + d.timeDrilled + " lastDrillSpeed " + d.lastDrillSpeed
            + " dominant " + d.dominantItem + "x" + d.dominantItems;
    }

    static String solidPump(com.xpdustry.shard.sim.blocks.production.SolidPump p){
        // one field in Shard: the pump's warmup that the solid pump's hides is always 0
        return " warmup " + p.warmup + " pumpWarmup 0.0 pumpTime " + p.pumpTime + " boost " + p.boost + " validTiles " + p.validTiles
            + " lastPump " + p.lastPump + " liquidDrop " + p.liquidDrop;
    }

    // ---- shared

    static String items(IntUnaryOperator amount){
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < EPOCH.items(); id++) if(amount.applyAsInt(id) != 0) out.add(EPOCH.item(id).name() + "=" + amount.applyAsInt(id));
        return out.toString();
    }

    static String liquids(IntFunction<Float> amount){
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < EPOCH.liquids(); id++) if(amount.apply(id) != 0f) out.add(EPOCH.liquid(id).name() + "=" + amount.apply(id));
        return out.toString();
    }

    /** What {@code writeMap} puts in a building's chunk: the revision, then {@code writeAll}. */
    static byte[] chunk(mindustry.gen.Building building){
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
