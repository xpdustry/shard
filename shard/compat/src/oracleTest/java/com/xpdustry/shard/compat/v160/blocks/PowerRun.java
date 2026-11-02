package com.xpdustry.shard.compat.v160.blocks;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.power.Generator;
import com.xpdustry.shard.sim.blocks.power.LaserNode;
import com.xpdustry.shard.sim.blocks.power.PowerGraphs;
import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.sim.world.*;
import arc.math.*;
import arc.util.io.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.power.PowerGraph;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One power fixture in a vanilla world ({@link Sim}) and a Shard one, built and edited in step: placements as a map
 * editor makes them ({@code tile.setBlock}) or as construction finishes them ({@code placed()} after), configs, logic
 * {@code enabled}, team changes, removals, kills, and items, liquids or stored power set by hand. {@link #run} compares
 * after every frame:
 * <ul>
 *   <li>the random generator's state and the entity id counter;</li>
 *   <li>{@code Groups.powerGraph}, as updater ids in order;</li>
 *   <li>every graph a building is in (group order, then the others in tile order): its members, producers, consumers
 *   and batteries in order, and its last solve's statistics;</li>
 *   <li>every building: power status, links, graph, efficiency, items, liquids and the generator kinds' fields;</li>
 * </ul>
 * and every ported building's save chunk at the end. Graphs are named by their updater's entity id, the same number on both
 * sides when the ids agree. Static blocks (walls, boulders) are compared by tile; with {@link #timers} on, so are the
 * pending timer delays. {@link #withExplosions} puts an {@link ExplosionRecorder} in the Shard world.
 */
final class PowerRun{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();

    final Sim sim;
    final World shard;
    final Map<Integer, List<Runnable>> edits = new TreeMap<>();
    int frame;
    /** Whether {@link #compare} includes the pending timer delays. */
    boolean timers;

    PowerRun(int width, int height){
        this(width, height, Systems.builtIn());
    }

    private PowerRun(int width, int height, Systems systems){
        sim = Sim.empty(width, height);
        // Vanilla.reset seeds Mathf.rand with 0
        shard = new World(WorldConfig.of(EPOCH, width, height, 0).withKinds(KINDS).withSystems(systems));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, EPOCH.block("stone").id());
        shard.buildings().endLoad(false);
    }

    /** A run whose Shard world records generator explosions ({@link #recorder()}). */
    static PowerRun withExplosions(int width, int height){
        var handlers = new ArrayList<PhaseHandler>();
        for(Phase phase : Phase.values()) handlers.addAll(Systems.builtIn().handlers(phase));
        var parts = new ArrayList<>(Systems.builtIn().parts());
        return new PowerRun(width, height, Systems.of(handlers, parts).with(ExplosionRecorder.class, ExplosionRecorder::new));
    }

    ExplosionRecorder recorder(){
        return shard.part(ExplosionRecorder.class);
    }

    Rules rules(){
        return shard.part(WorldRules.class).rules;
    }

    /** Sets a floor in both worlds, before anything stands on it. */
    void floor(int x, int y, String floor){
        world.tile(x, y).setFloor(content.block(floor).asFloor());
        shard.buildings().setFloor(x, y, EPOCH.block(floor), Cause.SERVER);
    }

    /** As a map editor places it: {@code tile.setBlock}. */
    void place(String block, int x, int y){
        place(block, x, y, 0, Team.sharded);
    }

    void place(String block, int x, int y, int rotation, Team team){
        sim.place(content.block(block), x, y, rotation, team);
        shard.buildings().setBlock(x, y, EPOCH.block(block), team.id, rotation, Cause.SERVER);
    }

    /** As construction finishes it: {@code tile.setBlock}, then {@code placed()}, which autolinks. */
    void build(String block, int x, int y){
        build(block, x, y, 0, Team.sharded);
    }

    void build(String block, int x, int y, int rotation, Team team){
        place(block, x, y, rotation, team);
        sim.build(x, y).placed();
        Vanilla.runPosted();
        shard.buildings().at(x, y).placed();
    }

    /** {@code tile.remove()}: the tile becomes air. */
    void remove(int x, int y){
        world.tile(x, y).remove();
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block("air"), 0, 0, Cause.SERVER);
    }

    /** {@code build.kill()}: {@code Call.buildDestroyed}. */
    void kill(int x, int y){
        sim.build(x, y).kill();
        Vanilla.runPosted();
        shard.buildings().kill(shard.buildings().at(x, y));
    }

    void team(int x, int y, Team team){
        sim.build(x, y).changeTeam(team);
        Vanilla.runPosted();
        shard.buildings().changeTeam(shard.buildings().at(x, y), team.id, Cause.SERVER);
    }

    /** {@code configureAny(Point2.pack(tx, ty))}: a node's link toggle. */
    void link(int x, int y, int tx, int ty){
        sim.build(x, y).configureAny(arc.math.geom.Point2.pack(tx, ty));
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(Point2.pack(tx, ty));
    }

    /** {@code configureAny(Point2[])}: a node's whole link list, as offsets. */
    void links(int x, int y, int... offsets){
        var vanilla = new arc.math.geom.Point2[offsets.length / 2];
        var ours = new Point2[offsets.length / 2];
        for(int i = 0; i < vanilla.length; i++){
            vanilla[i] = new arc.math.geom.Point2(offsets[i * 2], offsets[i * 2 + 1]);
            ours[i] = new Point2(offsets[i * 2], offsets[i * 2 + 1]);
        }
        sim.build(x, y).configureAny(vanilla);
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(ours);
    }

    /** Any other config, given on each side. */
    void configure(int x, int y, Object vanilla, Object ours){
        sim.build(x, y).configureAny(vanilla);
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(ours);
    }

    /** {@code item-source} output. */
    void source(int x, int y, String item){
        configure(x, y, content.item(item), ContentRef.item(EPOCH.item(item).id()));
    }

    /** What a processor's {@code control enabled} does to the building. */
    void enabled(int x, int y, boolean enabled){
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

    /** Sets a building's power status: a battery's charge. */
    void status(int x, int y, float status){
        sim.build(x, y).power.status = status;
        shard.buildings().at(x, y).power.status = status;
    }

    /** Edits both worlds' rules. */
    void rules(Consumer<mindustry.game.Rules> vanilla, Consumer<Rules> ours){
        vanilla.accept(state.rules);
        ours.accept(rules());
    }

    void at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
    }

    /** {@link #at} every {@code step} frames from {@code from} to {@code to}, inclusive. */
    void every(int from, int to, int step, Runnable edit){
        for(int when = from; when <= to; when += step) at(when, edit);
    }

    /** Applies each frame's edits, steps both worlds, and compares them after every frame, then the save chunks. */
    void run(int frames){
        until(frames, () -> false);
        chunks();
    }

    /**
     * {@link #run}'s frames, without the save chunks, until {@code done} holds after a frame (which is compared) or
     * {@code frames} have run. Returns the last frame.
     */
    int until(int frames, BooleanSupplier done){
        if(frame == 0) compare("as built");
        for(frame++; frame <= frames; frame++){
            for(Runnable edit : edits.getOrDefault(frame, List.of())){
                edit.run();
                compare("after the edits of frame " + frame);
            }
            sim.step(1);
            shard.tick();
            compare("after frame " + frame);
            if(done.getAsBoolean()) return frame;
        }
        return --frame;
    }

    /** Steps both worlds once without comparing them: after vanilla does something Shard leaves to a seam. */
    void stepUnchecked(){
        frame++;
        sim.step(1);
        shard.tick();
    }

    /** Every ported building's save chunk. */
    void chunks(){
        var current = ContentHeader.of(EPOCH).mapping(EPOCH);
        for(mindustry.gen.Building expected : buildings()){
            Building actual = shard.buildings().at(expected.tile.x, expected.tile.y);
            // a wall or other unported block placed live has no bytes to write
            if(actual instanceof PassthroughBuilding passthrough && !passthrough.hasBody()) continue;
            assertEquals(hex(chunk(expected)), hex(CODECS.chunk(actual, EPOCH, current)),
                expected.block.name + " at (" + expected.tile.x + ", " + expected.tile.y + "): save chunk");
        }
    }

    void compare(String when){
        assertEquals(String.join("\n", vanillaState()), String.join("\n", shardState()), when);
    }

    /** The vanilla building at a tile, for a fixture's own assertions. */
    mindustry.gen.Building vanilla(int x, int y){
        return sim.build(x, y);
    }

    Building ours(int x, int y){
        return shard.buildings().at(x, y);
    }

    // vanilla's side

    List<mindustry.gen.Building> buildings(){
        var out = new ArrayList<mindustry.gen.Building>();
        for(var tile : world.tiles) if(tile.build != null && tile.isCenter()) out.add(tile.build);
        return out;
    }

    List<String> vanillaState(){
        var out = new ArrayList<String>();
        out.add("rand " + Mathf.rand.seed0 + " " + Mathf.rand.seed1 + " next id " + lastId() + " time " + arc.util.Time.time
            + " tile changes " + world.tileChanges);
        var group = new ArrayList<String>();
        var graphs = new ArrayList<PowerGraph>();
        Groups.powerGraph.each(updater -> {
            group.add(String.valueOf(updater.id()));
            graphs.add(updater.graph());
        });
        out.add("group " + group);
        var blocks = new StringJoiner(" ");
        for(var tile : world.tiles) if(tile.build == null && tile.block() != mindustry.content.Blocks.air) blocks.add(tile.block().name + "@" + tile.x + "," + tile.y);
        out.add("blocks " + blocks);
        if(timers){
            var delays = new StringJoiner(" ");
            for(var run : arc.util.Time.getRuns()) delays.add(String.valueOf(field(run, "delay")));
            out.add("timers " + delays);
        }
        List<mindustry.gen.Building> buildings = buildings();
        for(var build : buildings){
            if(build.power != null && !graphs.contains(build.power.graph)) graphs.add(build.power.graph);
        }
        for(PowerGraph graph : graphs){
            out.add("graph " + vanillaId(graph) + " added " + vanillaAdded(graph) + " all " + positions(graph.all) + " producers "
                + positions(graph.producers) + " consumers " + positions(graph.consumers) + " batteries " + positions(graph.batteries)
                + " needed " + graph.getLastPowerNeeded() + " produced " + graph.getLastPowerProduced() + " stored "
                + graph.getLastPowerStored() + " capacity " + graph.getLastCapacity() + " in " + graph.getLastScaledPowerIn()
                + " out " + graph.getLastScaledPowerOut() + " balance " + graph.getPowerBalance() + " delta " + field(graph, "energyDelta")
                + " satisfaction " + graph.getSatisfaction());
        }
        for(var build : buildings){
            var line = new StringBuilder(build.block.name + " (" + build.tile.x + ", " + build.tile.y + ") id " + build.id + " team "
                + build.team.id + " enabled " + build.enabled + " efficiency " + build.efficiency + " consumesPower " + build.shouldConsumePower
                + " timers " + Arrays.toString(build.timer.getTimes()));
            if(build.power != null){
                line.append(" status ").append(build.power.status).append(" init ").append(build.power.init).append(" links ")
                    .append(Arrays.toString(build.power.links.toArray())).append(" graph ").append(vanillaId(build.power.graph));
            }
            if(build.items != null) line.append(" items ").append(RouterOracleTest.items(id -> build.items.get(id)));
            if(build.liquids != null) line.append(" liquids ").append(liquids(id -> build.liquids.get(content.liquid(id))));
            switch(build){
                case HeaterGenerator.HeaterGeneratorBuild g -> line.append(" warmup ").append(g.warmup).append(" totalTime ").append(g.totalTime)
                    .append(" multiplier ").append(g.efficiencyMultiplier).append(" duration ").append(g.itemDurationMultiplier).append(" heat ").append(g.heat);
                case ConsumeGenerator.ConsumeGeneratorBuild g -> line.append(" warmup ").append(g.warmup).append(" totalTime ").append(g.totalTime)
                    .append(" multiplier ").append(g.efficiencyMultiplier).append(" duration ").append(g.itemDurationMultiplier);
                case NuclearReactor.NuclearReactorBuild r -> line.append(" heat ").append(r.heat).append(" last ").append(r.heatLastFrame)
                    .append(" progress ").append(r.heatProgress);
                case ImpactReactor.ImpactReactorBuild r -> line.append(" warmup ").append(r.warmup).append(" total ").append(r.totalProgress);
                case ThermalGenerator.ThermalGeneratorBuild t -> line.append(" sum ").append(t.sum);
                case LightBlock.LightBuild l -> line.append(" color ").append(l.color).append(" smooth ").append(l.smoothTime);
                case mindustry.world.blocks.sandbox.ItemSource.ItemSourceBuild s -> line.append(" counter ").append(s.counter);
                default -> {
                }
            }
            if(build instanceof PowerGenerator.GeneratorBuild g){
                line.append(" production ").append(g.productionEfficiency).append(" generateTime ").append(g.generateTime);
            }
            if(build instanceof PowerNode.PowerNodeBuild node) line.append(" config ").append(Arrays.toString(node.config()));
            if(build instanceof BeamNode.BeamNodeBuild beam){
                var beams = new StringJoiner(" ");
                for(int i = 0; i < 4; i++){
                    beams.add(beam.links[i] == null ? "-" : beam.links[i].tile.x + "," + beam.links[i].tile.y + "@"
                        + beam.dests[i].x + "," + beam.dests[i].y);
                }
                line.append(" beams ").append(beams).append(" last ").append(beam.lastChange);
            }
            out.add(line.toString());
        }
        return out;
    }

    static String positions(arc.struct.Seq<mindustry.gen.Building> members){
        var out = new StringJoiner(" ", "[", "]");
        for(var build : members) out.add(build.tile.x + "," + build.tile.y);
        return out.toString();
    }

    static int vanillaId(PowerGraph graph){
        Object entity = field(graph, "entity");
        return entity == null ? -1 : ((PowerGraphUpdater)entity).id;
    }

    static boolean vanillaAdded(PowerGraph graph){
        Object entity = field(graph, "entity");
        return entity != null && ((PowerGraphUpdater)entity).isAdded();
    }

    static Object field(Object target, String name){
        try{
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    static Object staticField(Class<?> type, String name){
        try{
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    static int lastId(){
        try{
            Field field = EntityGroup.class.getDeclaredField("lastId");
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
            + " tile changes " + shard.buildings().tileChanges());
        var group = new ArrayList<String>();
        var graphs = new ArrayList<com.xpdustry.shard.sim.blocks.power.PowerGraph>();
        var list = PowerGraphs.of(shard).list();
        for(int i = 0; i < list.size(); i++){
            group.add(String.valueOf(list.get(i).id()));
            graphs.add(list.get(i));
        }
        out.add("group " + group);
        var tiles = shard.tiles();
        var blocks = new StringJoiner(" ");
        for(int pos = 0; pos < tiles.size(); pos++){
            if(tiles.build(pos) == -1 && tiles.block(pos) != 0) blocks.add(EPOCH.block(tiles.block(pos)).name() + "@" + tiles.x(pos) + "," + tiles.y(pos));
        }
        out.add("blocks " + blocks);
        if(timers){
            var delays = new StringJoiner(" ");
            for(var run : shard.timers().pending()) delays.add(String.valueOf(run.delay()));
            out.add("timers " + delays);
        }
        var buildings = new ArrayList<Building>();
        for(mindustry.gen.Building expected : buildings()){
            Building build = shard.buildings().at(expected.tile.x, expected.tile.y);
            if(build == null || build.tile() != tiles.pos(expected.tile.x, expected.tile.y)){
                out.add("no building at (" + expected.tile.x + ", " + expected.tile.y + ")");
                continue;
            }
            buildings.add(build);
            if(build.power != null && !graphs.contains(build.power.graph)) graphs.add(build.power.graph);
        }
        for(var graph : graphs){
            out.add("graph " + graph.id() + " added " + graph.isAdded() + " all " + positions(graph, graph.size(), graph::member)
                + " producers " + positions(graph, graph.producerCount(), graph::producer) + " consumers "
                + positions(graph, graph.consumerCount(), graph::consumer) + " batteries " + positions(graph, graph.batteryCount(), graph::battery)
                + " needed " + graph.getLastPowerNeeded() + " produced " + graph.getLastPowerProduced() + " stored "
                + graph.getLastPowerStored() + " capacity " + graph.getLastCapacity() + " in " + graph.getLastScaledPowerIn()
                + " out " + graph.getLastScaledPowerOut() + " balance " + graph.getPowerBalance() + " delta " + graph.getEnergyDelta()
                + " satisfaction " + graph.getSatisfaction());
        }
        for(Building build : buildings){
            var line = new StringBuilder(build.block().name() + " (" + build.tileX() + ", " + build.tileY() + ") id " + build.id() + " team "
                + build.team + " enabled " + build.enabled + " efficiency " + build.efficiency + " consumesPower " + build.shouldConsumePower
                + " timers " + Arrays.toString(build.timer));
            if(build.power != null){
                line.append(" status ").append(build.power.status).append(" init ").append(build.power.init).append(" links ")
                    .append(Arrays.toString(build.power.links.toArray())).append(" graph ").append(build.power.graph.id());
            }
            if(build.items != null) line.append(" items ").append(RouterOracleTest.items(id -> build.items.get(id)));
            if(build.liquids != null) line.append(" liquids ").append(liquids(id -> build.liquids.get(id)));
            switch(build){
                case com.xpdustry.shard.sim.blocks.power.HeaterGenerator g -> line.append(" warmup ").append(g.warmup).append(" totalTime ")
                    .append(g.totalTime).append(" multiplier ").append(g.efficiencyMultiplier).append(" duration ").append(g.itemDurationMultiplier)
                    .append(" heat ").append(g.heat);
                case com.xpdustry.shard.sim.blocks.power.ConsumeGenerator g -> line.append(" warmup ").append(g.warmup).append(" totalTime ")
                    .append(g.totalTime).append(" multiplier ").append(g.efficiencyMultiplier).append(" duration ").append(g.itemDurationMultiplier);
                case com.xpdustry.shard.sim.blocks.power.NuclearReactor r -> line.append(" heat ").append(r.heat).append(" last ")
                    .append(r.heatLastFrame).append(" progress ").append(r.heatProgress);
                case com.xpdustry.shard.sim.blocks.power.ImpactReactor r -> line.append(" warmup ").append(r.warmup).append(" total ")
                    .append(r.totalProgress);
                case com.xpdustry.shard.sim.blocks.power.ThermalGenerator t -> line.append(" sum ").append(t.sum);
                case com.xpdustry.shard.sim.blocks.power.LightBlock l -> line.append(" color ").append(l.color).append(" smooth ").append(l.smoothTime);
                case ItemSource s -> line.append(" counter ").append(s.counter);
                default -> {
                }
            }
            if(build instanceof Generator g) line.append(" production ").append(g.productionEfficiency).append(" generateTime ").append(g.generateTime);
            if(build instanceof LaserNode node) line.append(" config ").append(Arrays.toString(points(node.config())));
            if(build instanceof com.xpdustry.shard.sim.blocks.power.BeamNode beam){
                var beams = new StringJoiner(" ");
                for(int i = 0; i < 4; i++){
                    beams.add(beam.links[i] == null ? "-" : beam.links[i].tileX() + "," + beam.links[i].tileY() + "@"
                        + tiles.x(beam.dests[i]) + "," + tiles.y(beam.dests[i]));
                }
                line.append(" beams ").append(beams).append(" last ").append(beam.lastChange);
            }
            out.add(line.toString());
        }
        return out;
    }

    static String positions(com.xpdustry.shard.sim.blocks.power.PowerGraph graph, int count, IntFunction<Building> member){
        var out = new StringJoiner(" ", "[", "]");
        for(int i = 0; i < count; i++) out.add(member.apply(i).tileX() + "," + member.apply(i).tileY());
        return out.toString();
    }

    /** Point2 records in Arc's {@code toString} form. */
    static String[] points(Point2[] points){
        var out = new String[points.length];
        for(int i = 0; i < points.length; i++) out[i] = "(" + points[i].x() + ", " + points[i].y() + ")";
        return out;
    }

    static String liquids(IntFunction<Float> amount){
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < EPOCH.liquids(); id++) if(amount.apply(id) != 0f) out.add(EPOCH.liquid(id).name() + "=" + amount.apply(id));
        return out.toString();
    }

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
