package com.xpdustry.shard.compat.v160.build;

import arc.math.*;
import arc.util.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.build.BuildEvent;
import com.xpdustry.shard.sim.build.Builder;
import com.xpdustry.shard.sim.build.Construction;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.sim.team.BlockPlan;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.BuildPlan;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;
import mindustry.ai.types.*;
import mindustry.entities.*;
import mindustry.game.Team;
import mindustry.io.*;
import mindustry.world.blocks.ConstructBlock.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One construction fixture in a vanilla world and a Shard one, stepped together: a core with items, builder units
 * with {@code NoAI} controllers given the same build and break plans, and edits (kills, items) on given frames.
 * {@link #run} compares after every frame:
 * <ul>
 *   <li>the random generator's state, the entity id counter, {@code Time.time} and the tile change counter;</li>
 *   <li>every building: block, position, entity id, team, rotation and health; construct sites also their progress,
 *   cost, blocks, previous buildings, last builder, flags, accumulators and their save chunk (the block snapshot's
 *   body);</li>
 *   <li>the core's items, each unit's position, build counter and plans, and the team's rebuild plans;</li>
 *   <li>the Calls of the frame ({@code beginPlace}, {@code beginBreak}, {@code constructFinish},
 *   {@code deconstructFinish}, {@code tileConfig}, {@code buildDestroyed}) byte for byte ({@link CallFrames});</li>
 * </ul>
 * and, at the end, every ported building's save chunk and the team plans as {@code writeTeamBlocks} writes them.
 *
 * <p>{@code constructFinish} plays its place sound on vanilla's wall clock: at most every 32 ms, and the pitch draws
 * from the random when the last one is 480 ms old. Shard keeps that clock in ticks at 60 per second; each frame runs
 * Shard first and sets vanilla's private clock so it plays and draws on the same frames.
 */
final class BuildRun{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();
    static final Set<Integer> CALLS = Set.of(Packets.BeginPlace.ID, Packets.BeginBreak.ID, Packets.ConstructFinish.ID,
        Packets.DeconstructFinish.ID, Packets.TileConfig.ID, Packets.BuildDestroyed.ID);

    final Sim sim;
    final World shard;
    final Observer events;
    final Map<Integer, List<Runnable>> edits = new TreeMap<>();
    final List<mindustry.gen.Unit> vanillaUnits = new ArrayList<>();
    final List<Unit> shardUnits = new ArrayList<>();
    /** Calls sent so far, by packet name. */
    final Map<String, Integer> calls = new TreeMap<>();
    /** Frames that ended with a construct site standing, and the most standing at once. */
    int siteFrames, mostSites;
    int frame;
    int coreX, coreY;

    BuildRun(int width, int height){
        sim = Sim.empty(width, height);
        state.rules.waves = false;
        // Vanilla.reset seeds Mathf.rand with 0
        shard = new World(WorldConfig.of(EPOCH, width, height, 0).withKinds(KINDS));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, EPOCH.block("stone").id());
        shard.buildings().endLoad(false);
        rules().waves = false;
        events = shard.observe(4096, List.of(BuildEvent.class, BuildingEvent.class), null);
    }

    Rules rules(){
        return shard.part(WorldRules.class).rules;
    }

    Construction construction(){
        return Construction.of(shard);
    }

    /** As a map editor places it: {@code tile.setBlock}, for team sharded. */
    void place(String block, int x, int y, int rotation){
        sim.place(content.block(block), x, y, rotation, Team.sharded);
        shard.buildings().setBlock(x, y, EPOCH.block(block), Team.sharded.id, rotation, Cause.SERVER);
    }

    /** Team sharded's core, whose items pay for the fixture. */
    void core(String block, int x, int y){
        place(block, x, y, 0);
        coreX = x;
        coreY = y;
    }

    /** Sets the core's stock of {@code item}. */
    void items(String item, int amount){
        sim.build(coreX, coreY).items.set(content.item(item), amount);
        shard.buildings().at(coreX, coreY).items.set(EPOCH.item(item).id(), amount);
    }

    /** A builder of team sharded at a world position, with a controller that does nothing. Returns its index. */
    int unit(String type, float x, float y){
        mindustry.gen.Unit vanilla = content.unit(type).create(Team.sharded);
        vanilla.controller(new NoAI());
        vanilla.set(x, y);
        vanilla.add();
        Vanilla.runPosted();
        Unit ours = Unit.create(shard, EPOCH.unit(type), Team.sharded.id);
        ours.controller(new NoAi());
        ours.set(x, y);
        ours.add();
        assertEquals(vanilla.id, ours.id(), "the unit's entity id");
        vanillaUnits.add(vanilla);
        shardUnits.add(ours);
        return vanillaUnits.size() - 1;
    }

    /** {@code unit.addBuild(new BuildPlan(x, y, rotation, block, config))}. */
    void build(int unit, String block, int x, int y, int rotation, Object vanillaConfig, Object shardConfig){
        vanillaUnits.get(unit).addBuild(new mindustry.entities.units.BuildPlan(x, y, rotation, content.block(block), vanillaConfig));
        Builder.addBuild(shardUnits.get(unit), BuildPlan.place(x, y, rotation, EPOCH.block(block), shardConfig), true);
    }

    void build(int unit, String block, int x, int y, int rotation){
        build(unit, block, x, y, rotation, null, null);
    }

    /** {@code unit.addBuild(new BuildPlan(x, y))}: break what covers the tile. */
    void breaking(int unit, int x, int y){
        vanillaUnits.get(unit).addBuild(new mindustry.entities.units.BuildPlan(x, y));
        Builder.addBuild(shardUnits.get(unit), BuildPlan.breaking(x, y), true);
    }

    /** {@code build.kill()}: {@code Call.buildDestroyed}, and a rebuild plan when the rules keep ghost blocks. */
    void kill(int x, int y){
        sim.build(x, y).kill();
        shard.buildings().kill(shard.buildings().at(x, y));
    }

    /** {@code configureAny}, given on each side. */
    void configure(int x, int y, Object vanillaValue, Object shardValue){
        sim.build(x, y).configureAny(vanillaValue);
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(shardValue);
    }

    /** A node's link toggle: {@code configureAny(Point2.pack(tx, ty))}. */
    void link(int x, int y, int tx, int ty){
        configure(x, y, arc.math.geom.Point2.pack(tx, ty), com.xpdustry.shard.common.geom.Point2.pack(tx, ty));
    }

    /** A building's power status: a battery's charge. */
    void status(int x, int y, float status){
        sim.build(x, y).power.status = status;
        shard.buildings().at(x, y).power.status = status;
    }

    /** {@code handleItem(itself, item)}: an item on a block, a belt's at its start. */
    void handleItem(int x, int y, String item){
        sim.build(x, y).handleItem(sim.build(x, y), content.item(item));
        shard.buildings().at(x, y).handleItem(shard.buildings().at(x, y), EPOCH.item(item).id());
    }

    void at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
    }

    mindustry.gen.Building vanilla(int x, int y){
        return sim.build(x, y);
    }

    Building ours(int x, int y){
        return shard.buildings().at(x, y);
    }

    /** Runs {@code frames} frames, comparing after each, then the save chunks and team plans. */
    void run(int frames){
        until(frames, () -> false);
        chunks();
    }

    /** {@link #run}'s frames without the end checks, until {@code done} holds after a frame. Returns the last frame. */
    int until(int frames, BooleanSupplier done){
        if(frame == 0){
            // the same generator from here on, whatever setting up drew
            shard.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
            compare("as set up");
        }
        for(frame++; frame <= frames; frame++){
            step();
            compare("after frame " + frame);
            if(done.getAsBoolean()) return frame;
        }
        return --frame;
    }

    private void step(){
        Construction sound = construction();
        var sent = new ArrayList<Vanilla.Capture.Sent>();
        Vanilla.net(Vanilla.NetMode.server, () -> {
            Vanilla.captured().clear();
            for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
            Vanilla.runPosted();
            long played = sound.lastPlayed(), pitched = sound.lastPitch();
            shard.tick();
            boolean play = sound.lastPlayed() != played;
            boolean draw = play && sound.lastPitch() != pitched && (pitched < 0 || (sound.lastPlayed() - pitched) * 1000.0 / 60.0 >= 16 * 30);
            setStatic("lastPlayed", play ? 0L : com.xpdustry.shard.oracle.Vanilla.NOT_DUE);
            setStatic("lastTime", draw ? 0L : com.xpdustry.shard.oracle.Vanilla.NOT_DUE);
            Vanilla.frame();
            sent.addAll(Vanilla.captured());
        });
        List<String> expected = CallFrames.vanilla(sent, CALLS);
        CallFrames.same(expected, CallFrames.shard(shard, events.drain()), "Calls of frame " + frame);
        for(String line : expected){
            String name = line.split(" ")[2];
            calls.merge(name, 1, Integer::sum);
        }
    }

    void compare(String when){
        List<String> vanilla = vanillaState(), ours = shardState();
        assertEquals(String.join("\n", vanilla), String.join("\n", ours), when);
        long sites = vanilla.stream().filter(line -> line.startsWith("site ")).count();
        if(sites > 0) siteFrames++;
        mostSites = (int)Math.max(mostSites, sites);
    }

    /** Every building's save chunk, then the team plans' {@code writeTeamBlocks} bytes. */
    void chunks(){
        var ids = ContentHeader.of(EPOCH).mapping(EPOCH);
        for(mindustry.gen.Building expected : buildings()){
            Building actual = ours(expected.tile.x, expected.tile.y);
            // a wall or other unported block placed live has no bytes to write
            if(actual instanceof PassthroughBuilding passthrough && !passthrough.hasBody()) continue;
            assertEquals(hex(chunk(expected)), hex(CODECS.chunk(actual, EPOCH, ids)),
                expected.block.name + " at (" + expected.tile.x + ", " + expected.tile.y + "): save chunk");
        }
        assertEquals(hex(vanillaTeamBlocks()), hex(shardTeamBlocks(ids)), "team plans");
    }

    // vanilla's side

    List<mindustry.gen.Building> buildings(){
        var out = new ArrayList<mindustry.gen.Building>();
        for(var tile : world.tiles) if(tile.build != null && tile.isCenter()) out.add(tile.build);
        return out;
    }

    List<String> vanillaState(){
        var out = new ArrayList<String>();
        out.add("rand " + Mathf.rand.seed0 + " " + Mathf.rand.seed1 + " next id " + lastId() + " time " + Time.time + " tile changes "
            + world.tileChanges);
        for(var build : buildings()){
            out.add(build.block.name + " (" + build.tile.x + ", " + build.tile.y + ") id " + build.id + " team " + build.team.id + " rotation "
                + build.rotation + " health " + build.health + "/" + build.maxHealth);
            if(build instanceof ConstructBuild site){
                out.add("site " + EPOCH.block(site.current.id).name() + " over " + EPOCH.block(site.previous.id).name() + " progress " + site.progress
                    + " cost " + site.buildCost + " prev " + (site.prevBuild == null ? "[]" : positions(site.prevBuild.list(), b -> b.tile.x, b -> b.tile.y))
                    + " builder " + (site.lastBuilder == null ? -1 : site.lastBuilder.id) + " constructing " + site.wasConstructing + " deconstructing "
                    + site.activeDeconstruct + " config " + (site.lastConfig != null) + " acc " + Arrays.toString((float[])field(site, "accumulator"))
                    + " total " + Arrays.toString((float[])field(site, "totalAccumulator")) + " left " + Arrays.toString((int[])field(site, "itemsLeft"))
                    + " chunk " + hex(chunk(site)));
            }
        }
        var core = sim.build(coreX, coreY);
        out.add("core items " + (core == null ? "-" : items(id -> core.items.get(id))));
        for(mindustry.gen.Unit unit : vanillaUnits){
            var plans = new StringJoiner(" ");
            for(var plan : unit.plans()){
                plans.add((plan.breaking ? "break " : plan.block.name + " r" + plan.rotation + " ") + plan.x + "," + plan.y + " progress " + plan.progress
                    + " init " + plan.initialized + " stuck " + plan.stuck + " config " + (plan.config != null));
            }
            out.add("unit " + unit.id + " at " + unit.x + "," + unit.y + " counter " + field(unit, "buildCounter") + " plans [" + plans + "]");
        }
        var rebuild = new StringJoiner(" ");
        for(mindustry.game.Teams.BlockPlan plan : state.teams.get(Team.sharded).plans){
            rebuild.add(plan.block.name + "@" + plan.x + "," + plan.y + " r" + plan.rotation + (plan.removed ? " removed" : "")
                + " config " + (plan.config != null));
        }
        out.add("team plans [" + rebuild + "]");
        return out;
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

    static void setStatic(String name, long value){
        try{
            Field field = mindustry.world.blocks.ConstructBlock.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(null, value);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    /** A field declared by the object's class or one of its superclasses. */
    static Object field(Object target, String name){
        for(Class<?> type = target.getClass(); type != null; type = type.getSuperclass()){
            try{
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            }catch(NoSuchFieldException e){
                // look further up
            }catch(ReflectiveOperationException e){
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("no field " + name + " in " + target.getClass());
    }

    static byte[] chunk(mindustry.gen.Building building){
        var bytes = new ByteArrayOutputStream();
        var out = new arc.util.io.Writes(new DataOutputStream(bytes));
        out.b(building.version());
        building.writeAll(out);
        return bytes.toByteArray();
    }

    static byte[] vanillaTeamBlocks(){
        try{
            var bytes = new ByteArrayOutputStream();
            SaveIO.getSaveWriter().writeTeamBlocks(new DataOutputStream(bytes));
            return bytes.toByteArray();
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    // Shard's side

    List<String> shardState(){
        var out = new ArrayList<String>();
        out.add("rand " + shard.rand().seed0 + " " + shard.rand().seed1 + " next id " + shard.ids().peek() + " time " + shard.timers().time()
            + " tile changes " + shard.buildings().tileChanges());
        var tiles = shard.tiles();
        var current = ContentHeader.of(EPOCH).mapping(EPOCH);
        for(int pos = 0; pos < tiles.size(); pos++){
            Building build = shard.buildings().at(tiles.x(pos), tiles.y(pos));
            if(build == null || build.tile() != pos) continue;
            out.add(build.block().name() + " (" + build.tileX() + ", " + build.tileY() + ") id " + build.id() + " team " + build.team + " rotation "
                + build.rotation + " health " + build.health + "/" + build.maxHealth);
            if(build instanceof com.xpdustry.shard.sim.build.ConstructBuild site){
                out.add("site " + EPOCH.block(site.current).name() + " over " + EPOCH.block(site.previous).name() + " progress " + site.progress
                    + " cost " + site.buildCost + " prev " + (site.prevBuild == null ? "[]" : positions(site.prevBuild, Building::tileX, Building::tileY))
                    + " builder " + (site.lastBuilder == null ? -1 : site.lastBuilder.id()) + " constructing " + site.wasConstructing + " deconstructing "
                    + site.activeDeconstruct + " config " + (site.lastConfig != null) + " acc " + Arrays.toString(site.accumulator)
                    + " total " + Arrays.toString(site.totalAccumulator) + " left " + Arrays.toString(site.itemsLeft)
                    + " chunk " + hex(CODECS.chunk(site, EPOCH, current)));
            }
        }
        Building core = shard.buildings().at(coreX, coreY);
        out.add("core items " + (core == null ? "-" : items(id -> core.items.get(id))));
        for(Unit unit : shardUnits){
            var plans = new StringJoiner(" ");
            for(int i = 0; i < unit.plans.size(); i++){
                BuildPlan plan = unit.plans.get(i);
                plans.add((plan.breaking ? "break " : EPOCH.block(plan.block).name() + " r" + plan.rotation + " ") + plan.x + "," + plan.y + " progress "
                    + plan.progress + " init " + plan.initialized + " stuck " + plan.stuck + " config " + (plan.config != null));
            }
            out.add("unit " + unit.id() + " at " + unit.x + "," + unit.y + " counter " + unit.buildCounter + " plans [" + plans + "]");
        }
        var rebuild = new StringJoiner(" ");
        for(BlockPlan plan : com.xpdustry.shard.sim.team.Teams.of(shard).get(Team.sharded.id).plans()){
            rebuild.add(EPOCH.block(plan.block).name() + "@" + plan.x + "," + plan.y + " r" + plan.rotation + (plan.removed ? " removed" : "")
                + " config " + (plan.config != null));
        }
        out.add("team plans [" + rebuild + "]");
        return out;
    }

    /** {@code writeTeamBlocks} from {@link TeamPlansCodec#project}, as the save and world stream writers lay it out. */
    byte[] shardTeamBlocks(ContentHeader.IdMapping ids){
        var out = new com.xpdustry.shard.common.io.Writes(64);
        List<MapState.TeamPlans> teams = TeamPlansCodec.project(shard, ids);
        out.i(teams.size());
        for(MapState.TeamPlans team : teams){
            out.i(team.team());
            out.i(team.plans().size());
            for(MapState.TeamPlan plan : team.plans()){
                out.s(plan.x());
                out.s(plan.y());
                out.s(plan.rotation());
                out.s(plan.block());
                out.b(plan.config());
            }
        }
        return out.toByteArray();
    }

    static <T> String positions(List<T> buildings, ToIntFunction<T> x, ToIntFunction<T> y){
        var out = new StringJoiner(" ", "[", "]");
        for(T build : buildings) out.add(x.applyAsInt(build) + "," + y.applyAsInt(build));
        return out.toString();
    }

    static String hex(byte[] bytes){
        return HexFormat.of().formatHex(bytes);
    }

    /** Non-zero stock by item name, in id order. */
    static String items(IntUnaryOperator amount){
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < EPOCH.items(); id++) if(amount.applyAsInt(id) != 0) out.add(EPOCH.item(id).name() + "=" + amount.applyAsInt(id));
        return out.toString();
    }
}
