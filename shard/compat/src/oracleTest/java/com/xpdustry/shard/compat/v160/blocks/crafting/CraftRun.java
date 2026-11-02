package com.xpdustry.shard.compat.v160.blocks.crafting;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.crafting.*;
import com.xpdustry.shard.sim.blocks.heat.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.world.*;
import arc.math.*;
import arc.util.io.*;
import mindustry.game.*;
import mindustry.io.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One crafting or heat fixture in a vanilla world ({@link Sim}) and a Shard one, built and edited on the same frames
 * and compared after every frame: the random's state, the entity id counter, {@code Time.time}, {@code state.updateId},
 * and every building's base (team, enabled, in the update list, efficiencies, timers, items, liquids, power and its
 * graph's last solve) with its kind's fields by vanilla's names: crafters, attribute and heat crafters, heaters,
 * conductors (their {@code cameFrom} as a sorted set, their last update relative to the frame), separators,
 * incinerators, reactors, generators, conveyors and sources. Every {@link #snapshotEvery} frames, each synced
 * building's block snapshot entry ({@code NetServer.writeBlockSnapshots}' pos, block id and {@code writeSync}) against
 * {@link BlockSnapshots}. At the end, every building's save chunk; then vanilla's save of the world, loaded by both,
 * compared and run on for {@link #afterLoad} frames.
 */
final class CraftRun{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();
    static final MsavReader READER = new MsavReader(EPOCH);
    static final MapLoader LOADER = new MapLoader(EPOCH, CODECS);
    static final ContentHeader.IdMapping IDS = ContentHeader.of(EPOCH).mapping(EPOCH);

    final Sim sim;
    /** The Shard world's content: vanilla's, or a fixture's override of some block fields (vanilla's set alike). */
    final Epoch epoch;
    World shard;
    final Map<Integer, List<Runnable>> edits = new TreeMap<>();
    int frame;
    /** Frames to run on from vanilla's save, 0 to skip the save. */
    int afterLoad = 120;
    /** How often the block snapshots are compared. */
    int snapshotEvery = 30;
    /** How many synced buildings' snapshots matched, over the run. */
    int snapshots;

    CraftRun(int width, int height){
        this(width, height, EPOCH);
    }

    CraftRun(int width, int height, Epoch epoch){
        this.epoch = epoch;
        sim = Sim.empty(width, height);
        // Vanilla.reset seeds Mathf.rand with 0, and zeroes the clock and the id counter
        shard = new World(WorldConfig.of(epoch, width, height, 0).withKinds(KINDS));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, epoch.block("stone").id());
        shard.buildings().endLoad(false);
    }

    /** Sets a floor in both worlds, before anything stands on it. */
    CraftRun floor(int x, int y, String floor){
        world.tile(x, y).setFloor(content.block(floor).asFloor());
        shard.buildings().setFloor(x, y, epoch.block(floor), Cause.SERVER);
        return this;
    }

    CraftRun place(String block, int x, int y){
        return place(block, x, y, 0);
    }

    /** As a map editor places it: {@code tile.setBlock}. */
    CraftRun place(String block, int x, int y, int rotation){
        sim.place(content.block(block), x, y, rotation, Team.sharded);
        shard.buildings().setBlock(x, y, epoch.block(block), Team.sharded.id, rotation, Cause.SERVER);
        return this;
    }

    /** A line of {@code block} from (x, y), {@code length} long, each rotated {@code rotation}. */
    CraftRun line(String block, int x, int y, int rotation, int length){
        for(int i = 0; i < length; i++){
            place(block, x + com.xpdustry.shard.common.geom.Geometry.d4x(rotation) * i, y + com.xpdustry.shard.common.geom.Geometry.d4y(rotation) * i, rotation);
        }
        return this;
    }

    /** {@code tile.remove()}: the tile becomes air. */
    void remove(int x, int y){
        world.tile(x, y).remove();
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, epoch.block("air"), 0, 0, Cause.SERVER);
    }

    /** {@code build.kill()}: {@code Call.buildDestroyed}. */
    void kill(int x, int y){
        sim.build(x, y).kill();
        Vanilla.runPosted();
        shard.buildings().kill(shard.buildings().at(x, y));
    }

    /** {@code item-source} output. */
    CraftRun source(int x, int y, String item){
        sim.build(x, y).configureAny(content.item(item));
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(ContentRef.item(epoch.item(item).id()));
        return this;
    }

    /** {@code liquid-source} output; null clears it, so it empties itself and gives nothing. */
    CraftRun liquidSource(int x, int y, String liquid){
        sim.build(x, y).configureAny(liquid == null ? null : content.liquid(liquid));
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(liquid == null ? null : ContentRef.liquid(epoch.liquid(liquid).id()));
        return this;
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
        shard.buildings().at(x, y).items.add(epoch.item(item).id(), amount);
    }

    /** Adds liquid, up to the building's capacity. */
    void liquid(int x, int y, String liquid, float amount){
        var vanilla = sim.build(x, y);
        float room = vanilla.block.liquidCapacity - vanilla.liquids.get(content.liquid(liquid));
        vanilla.liquids.add(content.liquid(liquid), Math.min(amount, room));
        shard.buildings().at(x, y).liquids.add(epoch.liquid(liquid).id(), Math.min(amount, room));
    }

    /** Sets a building's power status: a battery's charge. */
    void status(int x, int y, float status){
        sim.build(x, y).power.status = status;
        shard.buildings().at(x, y).power.status = status;
    }

    /** Edits both worlds' rules. */
    void rules(Consumer<mindustry.game.Rules> vanilla, Consumer<com.xpdustry.shard.sim.rules.Rules> ours){
        vanilla.accept(state.rules);
        ours.accept(shard.part(com.xpdustry.shard.sim.rules.WorldRules.class).rules);
    }

    CraftRun at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
        return this;
    }

    /** {@link #at} every {@code step} frames from {@code from} to {@code to}, inclusive. */
    CraftRun every(int from, int to, int step, Runnable edit){
        for(int when = from; when <= to; when += step) at(when, edit);
        return this;
    }

    mindustry.gen.Building vanilla(int x, int y){
        return sim.build(x, y);
    }

    Building ours(int x, int y){
        return shard.buildings().at(x, y);
    }

    /** Runs {@code frames} frames, comparing after each, then the chunks, then from vanilla's save. */
    void run(int frames){
        compare("as built");
        for(frame = 1; frame <= frames; frame++){
            for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
            sim.step(1);
            shard.tick();
            compare("after frame " + frame);
            if(frame % snapshotEvery == 0) snapshots("frame " + frame);
        }
        chunks("save chunk");
        snapshots("the end");
        if(afterLoad > 0) fromSave();
    }

    /**
     * Vanilla's save of this world, loaded by both, as the server's {@code load} command on a fresh process:
     * {@code SaveIO.load} puts the game back in the menu, then it plays again. Both sides start the load from the same
     * random (seed 0) and entity id counter (0), and vanilla's hazard pools are emptied, as {@link HazardPools#empty()}
     * documents, without touching either. Nothing is copied from one side to the other: the random, the id counter and
     * every building are compared right after the load and every frame after, so a draw or id one side's load takes and
     * the other's doesn't fails at "loaded". {@code Time.time} keeps running across a vanilla load, which Shard's loader
     * reproduces.
     */
    void fromSave(){
        var file = new ByteArrayOutputStream();
        SaveIO.write(file, new SaveOptions());
        Mathf.rand.setSeed(0);
        setLastId(0);
        SaveIO.load(new ByteArrayInputStream(file.toByteArray()), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        HazardPools.empty();
        SaveFile save = READER.read(file.toByteArray());
        // Worlds.config(0): the same seed vanilla's random had before its load, and ids from 0
        shard = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save)).world();
        chunks("save chunk after loading vanilla's save");
        compare("loaded");
        for(int i = 1; i <= afterLoad; i++){
            sim.step(1);
            shard.tick();
            compare("after frame " + i + " from the save");
        }
        chunks("save chunk after running on from the save");
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

    /** Every building's save chunk, but walls and other unported blocks placed live, which have none. */
    void chunks(String what){
        for(mindustry.gen.Building expected : buildings()){
            Building actual = shard.buildings().at(expected.tile.x, expected.tile.y);
            if(actual instanceof PassthroughBuilding passthrough && !passthrough.hasBody()) continue;
            assertEquals(hex(chunk(expected)), hex(CODECS.chunk(actual, epoch, IDS)),
                expected.block.name + " at (" + expected.tile.x + ", " + expected.tile.y + "): " + what);
        }
    }

    /** Each synced building's block snapshot entry: vanilla's pos, block id and {@code writeSync}, against Shard's. */
    void snapshots(String when){
        var snapshots = new BlockSnapshots(epoch, CODECS);
        for(mindustry.gen.Building expected : buildings()){
            if(!expected.block.sync) continue;
            Building actual = shard.buildings().at(expected.tile.x, expected.tile.y);
            var bytes = new ByteArrayOutputStream();
            var out = new Writes(new DataOutputStream(bytes));
            out.i(expected.pos());
            out.s(expected.block.id);
            expected.writeSync(out);
            var ours = snapshots.one(actual);
            assertEquals(1, ours.amount());
            assertEquals(hex(bytes.toByteArray()), hex(ours.data()), expected.block.name + " at (" + expected.tile.x + ", " + expected.tile.y
                + "): block snapshot at " + when);
            this.snapshots++;
        }
    }

    List<mindustry.gen.Building> buildings(){
        var out = new ArrayList<mindustry.gen.Building>();
        for(var tile : world.tiles) if(tile.build != null && tile.isCenter()) out.add(tile.build);
        return out;
    }

    // vanilla's side

    List<String> vanillaState(){
        var out = new ArrayList<String>();
        out.add("rand " + Mathf.rand.seed0 + " " + Mathf.rand.seed1 + " next id " + lastId() + " time " + arc.util.Time.time + " update " + state.updateId);
        for(var build : buildings()) out.add(vanilla(build));
        return out;
    }

    static String vanilla(mindustry.gen.Building build){
        var line = new StringBuilder(build.block.name + " (" + build.tile.x + ", " + build.tile.y + ") id " + build.id + " rot " + build.rotation
            + " team " + build.team.id + " enabled " + build.enabled + " added " + build.isAdded() + " health " + build.health
            + " efficiency " + build.efficiency + " optional " + build.optionalEfficiency + " potential " + build.potentialEfficiency
            + " consumesPower " + build.shouldConsumePower + " timeScale " + build.timeScale() + " cdump " + build.cdump
            + " timers " + Arrays.toString(build.timer.getTimes()));
        if(build.items != null) line.append(" items ").append(items(id -> build.items.get(id)));
        if(build.liquids != null) line.append(" liquids ").append(liquids(id -> build.liquids.get(content.liquid(id))));
        if(build.power != null){
            var graph = build.power.graph;
            line.append(" status ").append(build.power.status).append(" satisfaction ").append(graph.getSatisfaction()).append(" produced ")
                .append(graph.getLastPowerProduced()).append(" needed ").append(graph.getLastPowerNeeded()).append(" stored ").append(graph.getLastPowerStored());
        }
        if(build instanceof mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild c){
            line.append(" progress ").append(c.progress).append(" total ").append(c.totalProgress).append(" warmup ").append(c.warmup)
                .append(" accumulator ").append(Arrays.toString(c.outputAccumulator));
        }
        switch(build){
            case mindustry.world.blocks.production.AttributeCrafter.AttributeCrafterBuild a -> line.append(" attrsum ").append(a.attrsum);
            case mindustry.world.blocks.production.HeatCrafter.HeatCrafterBuild h -> line.append(" heat ").append(h.heat)
                .append(" sideHeat ").append(Arrays.toString(h.sideHeat));
            case mindustry.world.blocks.heat.HeatProducer.HeatProducerBuild h -> line.append(" heat ").append(h.heat);
            case mindustry.world.blocks.heat.HeatConductor.HeatConductorBuild h -> {
                int[] keys = new arc.struct.IntSet.IntSetIterator(h.cameFrom).toArray().toArray();
                Arrays.sort(keys);
                line.append(" heat ").append(h.heat).append(" sideHeat ").append(Arrays.toString(h.sideHeat)).append(" cameFrom ")
                    .append(Arrays.toString(keys)).append(" updated ").append(h.lastHeatUpdate == -1 ? "never" : String.valueOf(state.updateId - h.lastHeatUpdate));
            }
            case mindustry.world.blocks.production.Separator.SeparatorBuild s -> line.append(" progress ").append(s.progress).append(" total ")
                .append(s.totalProgress).append(" warmup ").append(s.warmup).append(" seed ").append(s.seed);
            case mindustry.world.blocks.production.Incinerator.IncineratorBuild i -> line.append(" heat ").append(i.heat);
            case mindustry.world.blocks.power.VariableReactor.VariableReactorBuild r -> line.append(" heat ").append(r.heat).append(" sideHeat ")
                .append(Arrays.toString(r.sideHeat)).append(" instability ").append(r.instability).append(" total ").append(r.totalProgress)
                .append(" warmup ").append(r.warmup);
            case mindustry.world.blocks.power.HeaterGenerator.HeaterGeneratorBuild g -> line.append(" warmup ").append(g.warmup).append(" totalTime ")
                .append(g.totalTime).append(" multiplier ").append(g.efficiencyMultiplier).append(" heat ").append(g.heat);
            case mindustry.world.blocks.power.NuclearReactor.NuclearReactorBuild r -> line.append(" heat ").append(r.heat).append(" progress ")
                .append(r.heatProgress);
            case mindustry.world.blocks.distribution.Conveyor.ConveyorBuild c -> {
                line.append(" len ").append(c.len).append(" slots");
                for(int i = 0; i < c.len; i++) line.append(' ').append(c.ids[i] == null ? -1 : c.ids[i].id).append('@').append(c.xs[i]).append(',').append(c.ys[i]);
                line.append(" minitem ").append(c.minitem).append(" mid ").append(c.mid).append(" clogHeat ").append(c.clogHeat);
            }
            case mindustry.world.blocks.sandbox.ItemSource.ItemSourceBuild s -> line.append(" counter ").append(s.counter);
            case mindustry.world.blocks.sandbox.LiquidSource.LiquidSourceBuild s -> line.append(" source ").append(s.source == null ? -1 : s.source.id);
            default -> {
            }
        }
        if(build instanceof mindustry.world.blocks.power.PowerGenerator.GeneratorBuild g){
            line.append(" production ").append(g.productionEfficiency).append(" generateTime ").append(g.generateTime);
        }
        return line.toString();
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

    static int lastId(){
        try{
            var field = mindustry.entities.EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    // Shard's side

    List<String> shardState(){
        var out = new ArrayList<String>();
        out.add("rand " + shard.rand().seed0 + " " + shard.rand().seed1 + " next id " + shard.ids().peek() + " time " + shard.timers().time()
            + " update " + HeatConductor.updateId(shard));
        for(mindustry.gen.Building expected : buildings()){
            Building build = shard.buildings().at(expected.tile.x, expected.tile.y);
            if(build == null || build.tileX() != expected.tile.x || build.tileY() != expected.tile.y){
                out.add("no building at (" + expected.tile.x + ", " + expected.tile.y + ")");
                continue;
            }
            out.add(shard(build));
        }
        return out;
    }

    static String shard(Building build){
        var line = new StringBuilder(build.block().name() + " (" + build.tileX() + ", " + build.tileY() + ") id " + build.id() + " rot " + build.rotation
            + " team " + build.team + " enabled " + build.enabled + " added " + build.isAdded() + " health " + build.health
            + " efficiency " + build.efficiency + " optional " + build.optionalEfficiency + " potential " + build.potentialEfficiency
            + " consumesPower " + build.shouldConsumePower + " timeScale " + build.timeScale + " cdump " + build.cdump
            + " timers " + Arrays.toString(build.timer));
        if(build.items != null) line.append(" items ").append(items(id -> build.items.get(id)));
        if(build.liquids != null) line.append(" liquids ").append(liquids(id -> build.liquids.get(id)));
        if(build.power != null){
            var graph = build.power.graph;
            line.append(" status ").append(build.power.status).append(" satisfaction ").append(graph.getSatisfaction()).append(" produced ")
                .append(graph.getLastPowerProduced()).append(" needed ").append(graph.getLastPowerNeeded()).append(" stored ").append(graph.getLastPowerStored());
        }
        if(build instanceof GenericCrafterBuild c){
            line.append(" progress ").append(c.progress).append(" total ").append(c.totalProgress).append(" warmup ").append(c.warmup)
                .append(" accumulator ").append(accumulator(c));
        }
        switch(build){
            case AttributeCrafter a -> line.append(" attrsum ").append(a.attrsum);
            case HeatCrafter h -> line.append(" heat ").append(h.heat).append(" sideHeat ").append(Arrays.toString(h.sideHeat));
            case HeatProducer h -> line.append(" heat ").append(h.heat);
            case HeatConductor h -> {
                int[] keys = h.cameFrom.toSeq().toArray();
                Arrays.sort(keys);
                line.append(" heat ").append(h.heat).append(" sideHeat ").append(Arrays.toString(h.sideHeat)).append(" cameFrom ")
                    .append(Arrays.toString(keys)).append(" updated ")
                    .append(h.lastHeatUpdate == -1 ? "never" : String.valueOf(HeatConductor.updateId(build.world()) - h.lastHeatUpdate));
            }
            case Separator s -> line.append(" progress ").append(s.progress).append(" total ").append(s.totalProgress).append(" warmup ")
                .append(s.warmup).append(" seed ").append(s.seed);
            case Incinerator i -> line.append(" heat ").append(i.heat);
            case VariableReactor r -> line.append(" heat ").append(r.heat).append(" sideHeat ").append(Arrays.toString(r.sideHeat))
                .append(" instability ").append(r.instability).append(" total ").append(r.totalProgress).append(" warmup ").append(r.warmup);
            case HeaterGenerator g -> line.append(" warmup ").append(g.warmup).append(" totalTime ").append(g.totalTime).append(" multiplier ")
                .append(g.efficiencyMultiplier).append(" heat ").append(g.heat);
            case NuclearReactor r -> line.append(" heat ").append(r.heat).append(" progress ").append(r.heatProgress);
            case ConveyorBuild c -> {
                line.append(" len ").append(c.len).append(" slots");
                for(int i = 0; i < c.len; i++) line.append(' ').append(c.ids[i]).append('@').append(c.xs[i]).append(',').append(c.ys[i]);
                line.append(" minitem ").append(c.minitem).append(" mid ").append(c.mid).append(" clogHeat ").append(c.clogHeat);
            }
            case ItemSource s -> line.append(" counter ").append(s.counter);
            case LiquidSource s -> line.append(" source ").append(s.source);
            default -> {
            }
        }
        if(build instanceof Generator g) line.append(" production ").append(g.productionEfficiency).append(" generateTime ").append(g.generateTime);
        return line.toString();
    }

    /**
     * Vanilla's accumulator as {@code Arrays.toString} prints it: its field initializer makes one as soon as the block
     * has output items, where Shard makes it at the first craft; both are zeros until then.
     */
    static String accumulator(GenericCrafterBuild c){
        int[] outputs = c.crafter().outputItems();
        if(c.outputAccumulator != null) return Arrays.toString(c.outputAccumulator);
        return outputs == null || outputs.length == 0 ? "null" : Arrays.toString(new float[outputs.length]);
    }

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
