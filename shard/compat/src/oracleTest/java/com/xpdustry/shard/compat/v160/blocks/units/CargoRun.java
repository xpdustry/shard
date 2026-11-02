package com.xpdustry.shard.compat.v160.blocks.units;

import arc.math.*;
import arc.struct.*;
import arc.util.io.*;
import arc.util.pooling.*;
import com.xpdustry.shard.common.geom.Point2;
import com.xpdustry.shard.common.math.Rand;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.CallFrames;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.sim.rules.WorldRules;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.BuildPlan;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.ai.types.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The cargo and prebuild fixtures (W4.7): a stone world built the same way in vanilla and in Shard, its units run by
 * their own AIs on both sides ({@code CargoAI}, {@code BuilderAI} handing over to {@code PrebuildAI}), edited on the
 * same frames and compared after every frame:
 * <ul>
 *   <li>the clock, the random's state and the next entity id;</li>
 *   <li>every building: id, team, health, efficiency, items, liquids, power, and the cargo loader's, unload point's
 *   and construct site's own fields;</li>
 *   <li>every unit in {@code Groups.unit} order: pose, health, stack, mining, flag, tether, plans, and its AI's timers
 *   and state (a fallback's too);</li>
 *   <li>the team's plans;</li>
 *   <li>the frame's Calls of {@link #CALLS}, byte for byte, as Shard's mappings send its events.</li>
 * </ul>
 * At the end, and after a load, every building's save chunk. {@link #saveAt} has vanilla save mid-run; both load it.
 *
 * <p>Pins, as {@link com.xpdustry.shard.compat.v160.blocks.payload} does: vanilla's pathfinder threads are stopped
 * (nothing here walks a flowfield), its physics random takes Shard's layer randoms' state, process-wide pools start
 * empty, and the place sound's wall clock follows Shard's tick clock ({@code SupportRun}'s pin: vanilla draws the
 * sound's pitch from the random only when enough milliseconds passed since the last).
 */
final class CargoRun{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();
    static final MsavReader READER = new MsavReader(EPOCH);
    static final MapLoader LOADER = new MapLoader(EPOCH, CODECS);
    static final Set<Integer> CALLS = Set.of(Packets.UnitTetherBlockSpawned.ID, Packets.TakeItems.ID, Packets.TransferItemTo.ID,
        Packets.TransferItemToUnit.ID, Packets.UnitSpawn.ID, Packets.UnitDeath.ID, Packets.UnitDestroy.ID, Packets.UnitCapDeath.ID,
        Packets.UnitEnvDeath.ID, Packets.UnitDespawn.ID, Packets.BeginPlace.ID, Packets.BeginBreak.ID, Packets.ConstructFinish.ID,
        Packets.DeconstructFinish.ID, Packets.TileConfig.ID, Packets.BuildDestroyed.ID, Packets.RemoveQueueBlock.ID);
    private static final Set<String> CALL_NAMES = new TreeSet<>();
    private static final Field PHYSICS, WORLD_RAND, LAYERS, LAYER_RAND;

    static{
        for(int id : CALLS) CALL_NAMES.add(Packets.TYPES.get(id).name());
        try{
            PHYSICS = open(mindustry.async.PhysicsProcess.class.getDeclaredField("physics"));
            WORLD_RAND = open(mindustry.async.PhysicsProcess.PhysicsWorld.class.getDeclaredField("rand"));
            LAYERS = open(com.xpdustry.shard.sim.spatial.UnitPhysics.class.getDeclaredField("layers"));
            LAYER_RAND = open(Class.forName("com.xpdustry.shard.sim.spatial.UnitPhysics$Layer").getDeclaredField("rand"));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    World shard;
    final int size;
    private final Map<Integer, List<Runnable>> edits = new TreeMap<>();
    private final List<BiConsumer<CargoRun, Integer>> watchers = new ArrayList<>();
    private com.xpdustry.shard.sim.world.Observer observer;
    int frame, saveAt = -1, calls;
    /** Calls sent, by packet name, and what a fixture saw happen, by name. */
    final TreeMap<String, Integer> seen = new TreeMap<>();
    private boolean physicsPinned;

    /** A {@code size × size} stone world in play on both sides, no unit cap; {@code rules} edits both sides' rules. */
    CargoRun(int size, Map<Integer, String> overlays, Consumer<mindustry.game.Rules> vanillaRules, Consumer<Rules> shardRules){
        this.size = size;
        Vanilla.reset();
        state.rules = new mindustry.game.Rules();
        state.rules.canGameOver = false;
        state.rules.disableUnitCap = true;
        state.rules.waveTimer = false;
        vanillaRules.accept(state.rules);
        state.map = new mindustry.maps.Map(StringMap.of("name", "oracle"));
        Block stone = content.block("stone");
        world.loadGenerator(size, size, tiles -> {
            for(int y = 0; y < size; y++){
                for(int x = 0; x < size; x++){
                    String overlay = overlays.get(y * size + x);
                    tiles.set(x, y, new Tile(x, y, stone, overlay == null ? mindustry.content.Blocks.air : content.block(overlay), mindustry.content.Blocks.air));
                }
            }
        });
        logic.play();
        Vanilla.runPosted();
        arc.util.Time.setInternalTime(0);
        HazardPools.empty();
        stopPathfinders();
        Groups.updatePooling();
        Bullet.create();
        Pools.get(Bullet.class, () -> null).clear();

        shard = new World(WorldConfig.of(EPOCH, size, size, 1).withSystems(Systems.builtIn()).withKinds(KINDS));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++){
            shard.tiles().floor(pos, EPOCH.block("stone").id());
            String overlay = overlays.get(pos);
            if(overlay != null) shard.tiles().overlay(pos, EPOCH.block(overlay).id());
        }
        shard.buildings().endLoad(false);
        shard.part(PathService.class).invalidateAll();
        WaveSpawner.of(shard).reset(shard);
        var rules = shard.part(WorldRules.class).rules;
        rules.disableUnitCap = true;
        rules.waveTimer = false;
        shardRules.accept(rules);
        WaveState.of(shard).play(rules);
        shard.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
        setStatic(EntityGroup.class, "lastId", shard.ids().peek());
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
    }

    private static void stopPathfinders(){
        for(Object finder : new Object[]{pathfinder, controlPath}){
            try{
                Field field = open(finder.getClass().getDeclaredField("thread"));
                var thread = (Thread)field.get(finder);
                if(thread != null){
                    thread.interrupt();
                    thread.join(10_000);
                    field.set(finder, null);
                }
            }catch(ReflectiveOperationException e){
                throw new IllegalStateException(e);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    // ---------------------------------------------------------------- building the fixture

    /** {@code block} at (x, y) for {@code team}, facing {@code rotation}, as a map editor places it. */
    CargoRun place(String block, int x, int y, int rotation, Team team){
        world.tile(x, y).setBlock(content.block(block), team, rotation);
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block(block), team.id, rotation, Cause.SERVER);
        return this;
    }

    CargoRun place(String block, int x, int y, int rotation){
        return place(block, x, y, rotation, Team.sharded);
    }

    /** {@code configure(item)} of the building at (x, y), on both sides, with its {@code tileConfig} Call; null clears it. */
    CargoRun configureItem(int x, int y, String item){
        if(item == null){
            world.build(x, y).configure(null);
            shard.buildings().at(x, y).configureAny(null);
        }else{
            world.build(x, y).configure(content.item(item));
            shard.buildings().at(x, y).configureAny(ContentRef.item(EPOCH.item(item).id()));
        }
        return this;
    }

    /** {@code configure(liquid)} of the building at (x, y), on both sides. */
    CargoRun configureLiquid(int x, int y, String liquid){
        world.build(x, y).configure(content.liquid(liquid));
        shard.buildings().at(x, y).configureAny(ContentRef.liquid(EPOCH.liquid(liquid).id()));
        return this;
    }

    /** {@code tile.setAir()}. */
    void remove(int x, int y){
        world.tile(x, y).setAir();
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block("air"), 0, 0, Cause.SERVER);
    }

    /** {@code build.kill()}: {@code Call.buildDestroyed}, on both sides. */
    void kill(int x, int y){
        world.build(x, y).kill();
        Vanilla.runPosted();
        shard.buildings().kill(shard.buildings().at(x, y));
    }

    /** Moves the unit of {@code type} with the lowest id to world (x, y) on both sides, as a client's position would. */
    void move(String type, float x, float y){
        vanillaUnit(type).set(x, y);
        shardUnit(type).set(x, y);
    }

    /** Items into (or, negative, out of) the building at (x, y). */
    CargoRun stock(int x, int y, String item, int amount){
        if(amount >= 0){
            world.build(x, y).items.add(content.item(item), amount);
            shard.buildings().at(x, y).items.add(EPOCH.item(item).id(), amount);
        }else{
            world.build(x, y).items.remove(content.item(item), -amount);
            shard.buildings().at(x, y).items.remove(EPOCH.item(item).id(), -amount);
        }
        return this;
    }

    /** {@code team}'s plan to build {@code block} at (x, y), appended on both sides. */
    CargoRun plan(Team team, String block, int x, int y, int rotation){
        team.data().plans.addLast(new mindustry.game.Teams.BlockPlan(x, y, (short)rotation, content.block(block), null));
        Teams.of(shard).get(team.id).plans().add(new BlockPlan(x, y, rotation, EPOCH.block(block).id(), null));
        return this;
    }

    /** {@code amount} of {@code item} into the stack of the unit of {@code type} with the lowest id, on both sides. */
    void giveItem(String type, String item, int amount){
        vanillaUnit(type).addItem(content.item(item), amount);
        shardUnit(type).addItem(EPOCH.item(item).id(), amount);
    }

    mindustry.gen.Unit vanillaUnit(String type){
        mindustry.gen.Unit found = null;
        for(var unit : Groups.unit) if(unit.type.name.equals(type) && (found == null || unit.id < found.id)) found = unit;
        return found;
    }

    Unit shardUnit(String type){
        Unit found = null;
        for(int i = 0; i < shard.units().size(); i++){
            Unit unit = shard.units().get(i);
            if(unit.type().name().equals(type) && (found == null || unit.id() < found.id())) found = unit;
        }
        return found;
    }

    CargoRun at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
        return this;
    }

    /** Runs after every compared frame, with the frame number: for a fixture to note what happened. */
    CargoRun watch(BiConsumer<CargoRun, Integer> watcher){
        watchers.add(watcher);
        return this;
    }

    /** Vanilla saves its world at the start of frame {@code frame}; both load it and run on. */
    CargoRun saveAt(int frame){
        saveAt = frame;
        return this;
    }

    void count(String what){
        seen.merge(what, 1, Integer::sum);
    }

    int seen(String what){
        return seen.getOrDefault(what, 0);
    }

    // ---------------------------------------------------------------- running

    void run(int frames){
        observer = shard.observe(1 << 14);
        Vanilla.captured().clear();
        same("as built");
        for(frame = 1; frame <= frames; frame++){
            if(frame == saveAt) saveAndLoad();
            int before = Vanilla.captured().size();
            for(Runnable edit : edits.getOrDefault(frame, List.of())){
                Vanilla.net(Vanilla.NetMode.server, edit::run);
            }
            pinPhysics();
            // vanilla's place sound draws its pitch by the wall clock (ConstructBlock.java:124-142): Shard ticks first,
            // and vanilla's clocks are set so that it plays, and draws, exactly when Shard's tick did (SupportRun's pin)
            Construction sound = Construction.of(shard);
            long played = sound.lastPlayed(), pitched = sound.lastPitch();
            shard.tick();
            boolean play = sound.lastPlayed() != played;
            boolean draw = play && sound.lastPitch() != pitched && (pitched < 0 || (sound.lastPlayed() - pitched) * 1000.0 / 60.0 >= 16 * 30);
            setConstructClock("lastPlayed", play ? 0L : com.xpdustry.shard.oracle.Vanilla.NOT_DUE);
            setConstructClock("lastTime", draw ? 0L : com.xpdustry.shard.oracle.Vanilla.NOT_DUE);
            Vanilla.net(Vanilla.NetMode.server, Vanilla::frame);
            var sent = new ArrayList<>(Vanilla.captured().subList(before, Vanilla.captured().size()));
            List<String> expected = new ArrayList<>();
            for(String line : CallFrames.vanilla(sent, CALLS)) expected.add(spawnPose(line));
            List<String> actual = new ArrayList<>();
            for(String line : CallFrames.shard(shard, observer.drain())) if(CALL_NAMES.contains(line.split(" ")[2])) actual.add(spawnPose(line));
            calls += expected.size();
            for(String line : expected) count("Call " + line.split(" ")[2]);
            CallFrames.same(expected, actual, "Calls in frame " + frame);
            same("after frame " + frame);
            for(var watcher : watchers) watcher.accept(this, frame);
        }
        observer.close();
        assertChunksEqual();
    }

    /**
     * A {@code unitSpawn} Call by its target and name only. Known deviation (docs/waves/units.md, W3.10's open items):
     * Shard's mapping writes the unit as it is after the tick, vanilla's Call as it was when spawned, so the pose
     * differs by the unit's first frame of movement. The unit's whole state is compared on every frame anyway.
     */
    private static String spawnPose(String line){
        String[] parts = line.split(" ");
        return parts[2].equals("UnitSpawn") ? parts[0] + " " + parts[1] + " UnitSpawn" : line;
    }

    private void pinPhysics(){
        if(physicsPinned) return;
        try{
            Object[] vanilla = (Object[])PHYSICS.get(unitPhysics);
            if(vanilla == null) return;
            Object[] layers = (Object[])LAYERS.get(shard.part(com.xpdustry.shard.sim.spatial.UnitPhysics.class));
            for(int i = 0; i < vanilla.length; i++){
                var from = (Rand)LAYER_RAND.get(layers[i]);
                ((arc.math.Rand)WORLD_RAND.get(vanilla[i])).setState(from.getState(0), from.getState(1));
            }
            physicsPinned = true;
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    /** Vanilla's {@code SaveIO.write}, loaded by both, as the server's {@code load} command does. */
    private void saveAndLoad(){
        same("before saving");
        observer.close();
        var file = new ByteArrayOutputStream();
        SaveIO.write(file, new SaveOptions());
        // both load from a generator seeded with 0 and ids from 0, as Shard's loaded world is
        Mathf.rand.setSeed(0);
        setStatic(EntityGroup.class, "lastId", 0);
        SaveIO.load(new ByteArrayInputStream(file.toByteArray()), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        HazardPools.empty();
        stopPathfinders();
        long seed0 = Mathf.rand.seed0, seed1 = Mathf.rand.seed1;
        int id = lastId();
        Groups.updatePooling();
        Bullet.create();
        Pools.get(Bullet.class, () -> null).clear();
        setStatic(EntityGroup.class, "lastId", id);
        Mathf.rand.setState(seed0, seed1);
        SaveFile save = READER.read(file.toByteArray());
        MapLoader.Loaded loaded = LOADER.load(save, WorldConfig.of(EPOCH, 1, 1, 0).withSystems(Systems.builtIn()).withKinds(KINDS), LOADER.saveRules(save));
        assertNull(loaded.state().unknownDraws, "draws Shard doesn't count");
        shard = loaded.world();
        observer = shard.observe(1 << 14);
        physicsPinned = false;
        count("loaded");
        assertChunksEqual();
        same("loaded");
    }

    /** Both sides' state lines; a failure lists only the lines that differ. */
    void same(String when){
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
    void assertChunksEqual(){
        var current = ContentHeader.of(EPOCH).mapping(EPOCH);
        for(mindustry.gen.Building expected : buildings()){
            Building actual = shard.buildings().at(expected.tile.x, expected.tile.y);
            assertNotNull(actual, expected.block.name + " at (" + expected.tile.x + ", " + expected.tile.y + ")");
            assertEquals(hex(chunk(expected)), hex(CODECS.chunk(actual, EPOCH, current)),
                expected.block.name + " at (" + expected.tile.x + ", " + expected.tile.y + "): save chunk");
        }
    }

    List<mindustry.gen.Building> buildings(){
        var out = new ArrayList<mindustry.gen.Building>();
        for(var tile : world.tiles) if(tile.build != null && tile.isCenter()) out.add(tile.build);
        return out;
    }

    List<String> vanillaStates(){
        var out = new ArrayList<String>();
        out.add("time " + bits(arc.util.Time.time) + " random " + Mathf.rand.seed0 + "," + Mathf.rand.seed1 + " next id " + lastId());
        for(mindustry.gen.Building build : buildings()) out.add(vanillaBuilding(build));
        for(Team team : new Team[]{Team.sharded, Team.crux}){
            var line = new StringBuilder("plans t" + team.id);
            for(var plan : team.data().plans){
                line.append(" [").append(plan.block.name).append(' ').append(plan.x).append(',').append(plan.y).append(" r").append(plan.rotation)
                    .append(plan.removed ? " removed" : "").append(']');
            }
            out.add(line.toString());
        }
        for(var unit : Groups.unit) out.add(vanillaUnit(unit));
        return out;
    }

    List<String> shardStates(){
        var out = new ArrayList<String>();
        out.add("time " + bits(shard.timers().time()) + " random " + shard.rand().getState(0) + "," + shard.rand().getState(1) + " next id " + shard.ids().peek());
        for(mindustry.gen.Building expected : buildings()){
            Building build = shard.buildings().at(expected.tile.x, expected.tile.y);
            out.add(build == null ? "missing at (" + expected.tile.x + ", " + expected.tile.y + ")" : shardBuilding(build));
        }
        for(Team team : new Team[]{Team.sharded, Team.crux}){
            var line = new StringBuilder("plans t" + team.id);
            for(BlockPlan plan : Teams.of(shard).get(team.id).plans()){
                line.append(" [").append(EPOCH.block(plan.block).name()).append(' ').append(plan.x).append(',').append(plan.y).append(" r").append(plan.rotation)
                    .append(plan.removed ? " removed" : "").append(']');
            }
            out.add(line.toString());
        }
        for(int i = 0; i < shard.units().size(); i++) out.add(shardUnit(shard.units().get(i)));
        return out;
    }

    // ---------------------------------------------------------------- state lines

    private static String vanillaBuilding(mindustry.gen.Building build){
        var out = new StringBuilder(build.block.name).append(" (").append(build.tile.x).append(", ").append(build.tile.y).append(") id ").append(build.id)
            .append(" team ").append(build.team.id).append(" rot ").append(build.rotation).append(" health ").append(bits(build.health))
            .append(" enabled ").append(build.enabled).append(" efficiency ").append(bits(build.efficiency)).append(" items ").append(vanillaItems(build));
        if(build.liquids != null){
            out.append(" liquids");
            for(var liquid : content.liquids()) if(build.liquids.get(liquid) != 0) out.append(' ').append(liquid.name).append('=').append(bits(build.liquids.get(liquid)));
        }
        if(build.power != null) out.append(" power ").append(bits(build.power.status));
        switch(build){
            case mindustry.world.blocks.units.UnitCargoLoader.UnitTransportSourceBuild l -> out.append(" progress ").append(bits(l.buildProgress)).append(" total ").append(bits(l.totalProgress))
                .append(" warmup ").append(bits(l.warmup)).append(" ready ").append(bits(l.readyness)).append(" unit ").append(l.unit == null ? -1 : l.unit.id)
                .append(" read ").append(l.readUnitId);
            case mindustry.world.blocks.units.UnitCargoUnloadPoint.UnitCargoUnloadPointBuild p -> out.append(" item ").append(p.item == null ? "-" : p.item.name)
                .append(" staleTimer ").append(bits(p.staleTimer)).append(" stale ").append(p.stale);
            case mindustry.world.blocks.ConstructBlock.ConstructBuild c -> out.append(" site ").append(c.current.name).append(" progress ").append(bits(c.progress));
            default -> {}
        }
        return out.toString();
    }

    private static String shardBuilding(Building build){
        var out = new StringBuilder(build.block().name()).append(" (").append(build.tileX()).append(", ").append(build.tileY()).append(") id ").append(build.id())
            .append(" team ").append(build.team).append(" rot ").append(build.rotation).append(" health ").append(bits(build.health))
            .append(" enabled ").append(build.enabled).append(" efficiency ").append(bits(build.efficiency)).append(" items ").append(shardItems(build));
        if(build.liquids != null){
            out.append(" liquids");
            for(int id = 0; id < EPOCH.liquids(); id++) if(build.liquids.get(id) != 0) out.append(' ').append(EPOCH.liquid(id).name()).append('=').append(bits(build.liquids.get(id)));
        }
        if(build.power != null) out.append(" power ").append(bits(build.power.status));
        switch(build){
            case UnitCargoLoader l -> out.append(" progress ").append(bits(l.buildProgress)).append(" total ").append(bits(l.totalProgress))
                .append(" warmup ").append(bits(l.warmup)).append(" ready ").append(bits(l.readyness)).append(" unit ").append(l.unit == null ? -1 : l.unit.id())
                .append(" read ").append(l.readUnitId);
            case UnitCargoUnloadPoint p -> out.append(" item ").append(p.item == -1 ? "-" : EPOCH.item(p.item).name())
                .append(" staleTimer ").append(bits(p.staleTimer)).append(" stale ").append(p.stale);
            case com.xpdustry.shard.sim.build.ConstructBuild c -> out.append(" site ").append(EPOCH.block(c.current).name()).append(" progress ").append(bits(c.progress));
            default -> {}
        }
        return out.toString();
    }

    private static String vanillaUnit(mindustry.gen.Unit u){
        var out = new StringBuilder("unit #").append(u.id).append(' ').append(u.type.name).append(" t").append(u.team.id).append(" at ").append(bits(u.x)).append(',')
            .append(bits(u.y)).append(" rot ").append(bits(u.rotation)).append(" v ").append(bits(u.vel.x)).append(',').append(bits(u.vel.y)).append(" hp ")
            .append(bits(u.health)).append(" el ").append(bits(u.elevation)).append(u.dead ? " dead" : "").append(" added ").append(u.isAdded())
            .append(" stack ").append(u.stack.item.name).append('x').append(u.stack.amount).append(" mine ")
            .append(u.mineTile == null ? "-" : u.mineTile.x + "," + u.mineTile.y).append(' ').append(bits(u.mineTimer)).append(" flag ").append(u.flag);
        if(u instanceof BuildingTetherc t) out.append(" tether ").append(t.building() == null ? "-" : t.building().tile.x + "," + t.building().tile.y);
        if(u.canBuild()){
            out.append(" updating ").append(u.updateBuilding).append(" plans");
            for(var plan : u.plans()){
                out.append(" [").append(plan.breaking ? "break" : plan.block.name + " r" + plan.rotation).append(' ').append(plan.x).append(',').append(plan.y)
                    .append(' ').append(bits(plan.progress)).append(plan.initialized ? " init" : "").append(']');
            }
        }
        out.append(" ai ").append(vanillaAi(u.controller()));
        return out.toString();
    }

    private static String vanillaAi(Object ai){
        if(!(ai instanceof AIController a)) return ai == null ? "-" : ai.getClass().getSimpleName();
        var out = new StringBuilder(ai.getClass().getSimpleName());
        for(float time : ((arc.util.Interval)field(a, "timer")).getTimes()) out.append(' ').append(bits(time));
        switch(ai){
            case CargoAI c -> out.append(" target ").append(c.unloadTarget == null ? "-" : c.unloadTarget.tile.x + "," + c.unloadTarget.tile.y).append(" item ")
                .append(c.itemTarget == null ? "-" : c.itemTarget.name).append(" noDest ").append(bits(c.noDestTimer)).append(" index ").append(c.targetIndex);
            case PrebuildAI p -> {
                var last = (mindustry.game.Teams.BlockPlan)field(p, "lastPlan");
                var collect = (Block)field(p, "collectBlock");
                var lastItem = (mindustry.type.Item)field(p, "lastTargetItem");
                var ore = (Tile)field(p, "ore");
                out.append(" last ").append(last == null ? "-" : last.x + "," + last.y + (last.removed ? " removed" : "")).append(" collect ")
                    .append(collect == null ? "-" : collect.name).append(" collecting ").append(field(p, "collectingItems")).append(" mining ").append(field(p, "mining"))
                    .append(" lastItem ").append(lastItem == null ? "-" : lastItem.name).append(" ore ").append(ore == null ? "-" : ore.x + "," + ore.y);
            }
            default -> {}
        }
        Object fallback = field(a, "fallback");
        if(fallback != null) out.append(" fallback (").append(vanillaAi(fallback)).append(')');
        return out.toString();
    }

    private static String shardUnit(Unit u){
        var out = new StringBuilder("unit #").append(u.id()).append(' ').append(u.type().name()).append(" t").append(u.team).append(" at ").append(bits(u.x)).append(',')
            .append(bits(u.y)).append(" rot ").append(bits(u.rotation)).append(" v ").append(bits(u.vel.x)).append(',').append(bits(u.vel.y)).append(" hp ")
            .append(bits(u.health)).append(" el ").append(bits(u.elevation)).append(u.dead ? " dead" : "").append(" added ").append(u.isAdded())
            .append(" stack ").append(EPOCH.item(u.stackItem).name()).append('x').append(u.stackAmount).append(" mine ")
            .append(u.mineTile == -1 ? "-" : Point2.x(u.mineTile) + "," + Point2.y(u.mineTile)).append(' ').append(bits(u.mineTimer)).append(" flag ").append(u.flag);
        if(u.params().tethered()) out.append(" tether ").append(u.tether == null ? "-" : u.tether.tileX() + "," + u.tether.tileY());
        if(Builder.canBuild(u)){
            out.append(" updating ").append(u.updateBuilding).append(" plans");
            for(int p = 0; p < u.plans.size(); p++){
                BuildPlan plan = u.plans.get(p);
                out.append(" [").append(plan.breaking ? "break" : EPOCH.block(plan.block).name() + " r" + plan.rotation).append(' ').append(plan.x).append(',')
                    .append(plan.y).append(' ').append(bits(plan.progress)).append(plan.initialized ? " init" : "").append(']');
            }
        }
        out.append(" ai ").append(shardAi(u.controller));
        return out.toString();
    }

    private static String shardAi(Object ai){
        if(!(ai instanceof AiController a)) return ai == null ? "-" : ai.getClass().getSimpleName();
        var out = new StringBuilder(switch(ai){
            case CargoAi ignored -> "CargoAI";
            case PrebuildAi ignored -> "PrebuildAI";
            case BuilderAi ignored -> "BuilderAI";
            default -> ai.getClass().getSimpleName();
        });
        for(float time : a.timers) out.append(' ').append(bits(time));
        switch(ai){
            case CargoAi c -> out.append(" target ").append(c.unloadTarget == null ? "-" : c.unloadTarget.tileX() + "," + c.unloadTarget.tileY()).append(" item ")
                .append(c.itemTarget == -1 ? "-" : EPOCH.item(c.itemTarget).name()).append(" noDest ").append(bits(c.noDestTimer)).append(" index ").append(c.targetIndex);
            case PrebuildAi p -> out.append(" last ").append(p.lastPlan == null ? "-" : p.lastPlan.x + "," + p.lastPlan.y + (p.lastPlan.removed ? " removed" : ""))
                .append(" collect ").append(p.collectBlock == -1 ? "-" : EPOCH.block(p.collectBlock).name()).append(" collecting ").append(p.collectingItems)
                .append(" mining ").append(p.mining).append(" lastItem ").append(p.lastTargetItem == -1 ? "-" : EPOCH.item(p.lastTargetItem).name())
                .append(" ore ").append(p.ore == -1 ? "-" : Point2.x(p.ore) + "," + Point2.y(p.ore));
            default -> {}
        }
        if(a.fallback != null) out.append(" fallback (").append(shardAi(a.fallback)).append(')');
        return out.toString();
    }

    private static String vanillaItems(mindustry.gen.Building build){
        if(build.items == null) return "none";
        var out = new StringJoiner(",", "[", "]");
        for(var item : content.items()) if(build.items.get(item) != 0) out.add(item.name + "=" + build.items.get(item));
        return out.toString();
    }

    private static String shardItems(Building build){
        if(build.items == null) return "none";
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < EPOCH.items(); id++) if(build.items.get(id) != 0) out.add(EPOCH.item(id).name() + "=" + build.items.get(id));
        return out.toString();
    }

    // ---------------------------------------------------------------- plumbing

    static String bits(float value){
        return value + "(" + Integer.toHexString(Float.floatToRawIntBits(value)) + ")";
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

    static int lastId(){
        try{
            return open(EntityGroup.class.getDeclaredField("lastId")).getInt(null);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    private static void setConstructClock(String name, long value){
        try{
            open(mindustry.world.blocks.ConstructBlock.class.getDeclaredField(name)).setLong(null, value);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    static void setStatic(Class<?> owner, String name, int value){
        try{
            open(owner.getDeclaredField(name)).setInt(null, value);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    static Object field(Object target, String name){
        for(Class<?> type = target.getClass(); type != null; type = type.getSuperclass()){
            try{
                return open(type.getDeclaredField(name)).get(target);
            }catch(NoSuchFieldException e){
                // look further up
            }catch(IllegalAccessException e){
                throw new AssertionError(e);
            }
        }
        throw new IllegalStateException("no field " + name + " in " + target.getClass());
    }

    private static Field open(Field field){
        field.setAccessible(true);
        return field;
    }
}
