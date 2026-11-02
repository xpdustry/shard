package com.xpdustry.shard.compat.v160.blocks.payload;

import arc.math.*;
import arc.struct.*;
import arc.util.io.*;
import arc.util.pooling.*;
import com.xpdustry.shard.common.math.Rand;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.CallFrames;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One payload fixture built in a vanilla world and a Shard one, edited on the same frames and compared after every
 * frame (vanilla's frame runs as a server, so its Calls are captured):
 * <ul>
 *   <li>every building, with its payload and the carried building's or unit's whole state, by vanilla's names;</li>
 *   <li>every unit in {@code Groups.unit} order, with what it carries, recursively;</li>
 *   <li>the clock, the random's state and the next entity id;</li>
 *   <li>the frame's payload, spawn, death and block snapshot Calls, byte for byte, as Shard's mappings send its events.</li>
 * </ul>
 * At the end, every building's save chunk. {@link #continueFromSave} has vanilla save, both load the bytes, and both
 * run on, compared as before.
 *
 * <p>Pins: vanilla's pathfinder threads are stopped (nothing walks a flowfield), its physics random takes Shard's layer
 * randoms' state, and the process-wide pools start empty, as a fresh server's. Units get controllers that do nothing:
 * {@link Inert} AIs (an {@code AIController}, so {@code isAI()}, which draws its two timers) on both sides.
 */
final class PayloadRun{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();
    static final MsavReader READER = new MsavReader(EPOCH);
    static final MapLoader LOADER = new MapLoader(EPOCH, CODECS);
    static final Set<Integer> CALLS = Set.of(Packets.PickedBuildPayload.ID, Packets.PickedUnitPayload.ID, Packets.PayloadDropped.ID,
        Packets.UnitSpawn.ID, Packets.BlockSnapshot.ID, Packets.UnitDeath.ID, Packets.UnitDestroy.ID, Packets.UnitCapDeath.ID,
        Packets.UnitEnvDeath.ID, Packets.UnitDespawn.ID);
    static final List<Class<? extends WorldEvent>> EVENTS = List.of(PayloadEvent.class, PayloadBlockEvent.class, CombatEvent.class,
        UnitEvent.class);
    private static final Field PHYSICS, WORLD_RAND, LAYERS, LAYER_RAND;

    static{
        try{
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

    /** A vanilla AI that does nothing: its constructor draws its timers, as every {@code AIController}'s. */
    static final class Inert extends mindustry.entities.units.AIController{
        @Override
        public void updateUnit(){
        }
    }

    /** Shard's AI that does nothing, drawing its timers as {@link Inert} does. */
    static final class ShardInert extends com.xpdustry.shard.sim.unit.AiController{
        ShardInert(World world){
            super(world, "inert");
        }

        private ShardInert(){
        }

        @Override
        public void updateUnit(Unit unit){
            this.unit = unit;
        }

        @Override
        public com.xpdustry.shard.sim.unit.UnitController copy(CopyContext context){
            var out = new ShardInert();
            System.arraycopy(timers, 0, out.timers, 0, timers.length);
            return out;
        }
    }

    World shard;
    private final Map<Integer, List<Runnable>> edits = new TreeMap<>();
    private com.xpdustry.shard.sim.world.Observer observer;
    int frame, saveAt = -1, calls, chunksEvery;
    /** What a fixture exercised, by name. */
    final TreeMap<String, Integer> seen = new TreeMap<>();
    private boolean physicsPinned;

    /** A {@code size × size} stone world in play on both sides, no unit cap, no waves; {@code overlays} by position. */
    PayloadRun(int size, Map<Integer, String> overlays){
        Vanilla.reset();
        state.rules = new mindustry.game.Rules();
        state.rules.canGameOver = false;
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
        state.rules.disableUnitCap = true;
        state.rules.waveTimer = false;
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

    /** {@code block} at (x, y) facing {@code rotation}, as a map editor places it. */
    PayloadRun place(String block, int x, int y, int rotation){
        world.tile(x, y).setBlock(content.block(block), Team.sharded, rotation);
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block(block), Team.sharded.id, rotation, Cause.SERVER);
        return this;
    }

    /** Both sides' rules on {@code planet} ({@code state.rules.planet}), as erekir's payload blocks need to build. */
    PayloadRun planet(String planet){
        state.rules.planet = content.planet(planet);
        String[] planets = {"sun", "erekir", "gier", "notva", "tantros", "serpulo", "verilus"};
        shard.part(WorldRules.class).rules.planet = List.of(planets).indexOf(planet);
        return this;
    }

    /** {@code configureAny} on both sides: vanilla's value, and Shard's. */
    PayloadRun configure(int x, int y, Object vanilla, Object ours){
        world.build(x, y).configureAny(vanilla);
        shard.buildings().at(x, y).configureAny(ours);
        return this;
    }

    /** {@code tile.setAir()}. */
    void remove(int x, int y){
        world.tile(x, y).setAir();
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block("air"), 0, 0, Cause.SERVER);
    }

    /** Items into the building at (x, y). */
    PayloadRun stock(int x, int y, String item, int amount){
        world.build(x, y).items.add(content.item(item), amount);
        shard.buildings().at(x, y).items.add(EPOCH.item(item).id(), amount);
        return this;
    }

    /** A unit of {@code type} at world (x, y), added, with an {@link Inert} AI. */
    PayloadRun spawn(String type, float x, float y){
        mindustry.gen.Unit vanilla = content.unit(type).spawn(Team.sharded, x, y, 0f);
        vanilla.controller(new Inert());
        Vanilla.runPosted();
        Unit ours = Unit.spawn(shard, EPOCH.unit(type), Team.sharded.id, x, y, 0f);
        ours.controller(new ShardInert(shard));
        return this;
    }

    /** The added unit of {@code type} with the lowest id, on each side. */
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

    /** Moves a unit of {@code type} to world (x, y) on both sides, as a client's position would. */
    void move(String type, float x, float y){
        vanillaUnit(type).set(x, y);
        shardUnit(type).set(x, y);
    }

    /** A new building of {@code block} put on the payload block at (x, y), as {@code new BuildPayload(block, team)}. */
    void feedBuilding(int x, int y, String block, Consumer<mindustry.gen.Building> vanillaSetup, Consumer<Building> shardSetup){
        mindustry.gen.Building target = world.build(x, y);
        if(target.getPayload() != null) return;
        var payload = new mindustry.world.blocks.payloads.BuildPayload(content.block(block), Team.sharded);
        vanillaSetup.accept(payload.build);
        target.handlePayload(target, payload);
        Building ours = shard.buildings().at(x, y);
        Building carried = shard.buildings().createPayload(EPOCH.block(block), Team.sharded.id);
        shardSetup.accept(carried);
        ours.handlePayload(ours, new Payload.BuildPayload(carried));
        count("fed " + block);
    }

    /** A new unit of {@code type} with an inert AI put on the payload block at (x, y), as {@code new UnitPayload(unit)}. */
    void feedUnit(int x, int y, String type){
        mindustry.gen.Building target = world.build(x, y);
        if(target.getPayload() != null) return;
        mindustry.gen.Unit unit = content.unit(type).create(Team.sharded);
        unit.controller(new Inert());
        unit.set(target.x, target.y);
        target.handlePayload(target, new mindustry.world.blocks.payloads.UnitPayload(unit));
        Building ours = shard.buildings().at(x, y);
        Unit carried = Unit.create(shard, EPOCH.unit(type), Team.sharded.id);
        carried.controller(new ShardInert(shard));
        carried.set(ours.x(), ours.y());
        ours.handlePayload(ours, new Payload.UnitPayload(carried));
        count("fed " + type);
    }

    /** {@code Call.pickedBuildPayload(unit, build, onGround)} as the server, for the carrier of {@code type}. */
    void pickBuilding(String carrier, int x, int y, boolean onGround){
        mindustry.gen.Unit unit = vanillaUnit(carrier);
        mindustry.gen.Building build = world.build(x, y);
        Call.pickedBuildPayload(unit, build, onGround);
        Payloads.pickedBuildPayload(shardUnit(carrier), shard.buildings().at(x, y), onGround, Cause.SERVER);
    }

    /** {@code Call.pickedUnitPayload(unit, target)} as the server: the carrier of {@code carrier} takes the unit of {@code type}. */
    void pickUnit(String carrier, String type){
        Call.pickedUnitPayload(vanillaUnit(carrier), vanillaUnit(type));
        Payloads.pickedUnitPayload(shardUnit(carrier), shardUnit(type));
    }

    /**
     * {@code requestDropPayload}'s Call, {@code Call.payloadDropped(unit, x, y)} as the server, at world (x, y) limited
     * to 4 tiles from the carrier, as {@code requestDropPayload} limits it.
     */
    void drop(String carrier, float x, float y){
        mindustry.gen.Unit unit = vanillaUnit(carrier);
        arc.math.geom.Vec2 v = new arc.math.geom.Vec2(x, y).sub(unit).limit(tilesize * 4f).add(unit);
        Call.payloadDropped(unit, v.x, v.y);
        Payloads.requestDropPayload(shardUnit(carrier), x, y, Cause.SERVER);
    }

    PayloadRun at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
        return this;
    }

    private boolean hazards;

    /** Compares the fires and puddles too ({@code HazardStates}), for a fixture that pours liquid. */
    PayloadRun hazards(){
        hazards = true;
        return this;
    }

    /** Compares every building's save chunk every {@code frames} frames too, not only at the end. */
    PayloadRun chunksEvery(int frames){
        chunksEvery = frames;
        return this;
    }

    /** Vanilla saves its world at the start of frame {@code frame}; both load it and run on. */
    PayloadRun saveAt(int frame){
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
        observer = shard.observe(4096, EVENTS, null);
        Vanilla.captured().clear();
        same("as built");
        for(frame = 1; frame <= frames; frame++){
            if(frame == saveAt) saveAndLoad();
            int before = Vanilla.captured().size();
            for(Runnable edit : edits.getOrDefault(frame, List.of())){
                Vanilla.net(Vanilla.NetMode.server, edit::run);
            }
            pinPhysics();
            Vanilla.net(Vanilla.NetMode.server, Vanilla::frame);
            shard.tick();
            var sent = new ArrayList<>(Vanilla.captured().subList(before, Vanilla.captured().size()));
            var events = observer.drain();
            for(var event : events) count(event.getClass().getSimpleName());
            List<String> expected = CallFrames.vanilla(sent, CALLS);
            calls += expected.size();
            CallFrames.same(expected, CallFrames.shard(shard, events), "Calls in frame " + frame);
            same("after frame " + frame);
            if(chunksEvery > 0 && frame % chunksEvery == 0) assertChunksEqual();
            noteExercised();
        }
        observer.close();
        assertChunksEqual();
    }

    private void noteExercised(){
        for(var unit : Groups.unit) if(unit instanceof Payloadc pay && !pay.payloads().isEmpty()) count("carrying");
        for(var entity : Groups.all) if(entity instanceof Puddle){
            count("puddles");
            break;
        }
        for(var tile : world.tiles){
            if(!tile.isCenter()) continue;
            if(tile.build instanceof mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild c && c.item != null) count("on " + tile.block().name);
            if(tile.build instanceof mindustry.world.blocks.payloads.PayloadBlock.PayloadBlockBuild<?> b && b.payload != null){
                count("in " + tile.block().name + " " + b.payload.content().name);
            }
            if(tile.build instanceof mindustry.world.blocks.payloads.PayloadDeconstructor.PayloadDeconstructorBuild d && d.deconstructing != null){
                count("deconstructing " + d.deconstructing.content().name);
            }
            if(tile.build instanceof mindustry.world.blocks.payloads.PayloadMassDriver.PayloadDriverBuild d && d.charging) count("charging");
        }
    }

    /** Vanilla's physics random takes Shard's layer randoms' state, once vanilla has its layers. */
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
        shard = LOADER.load(save, WorldConfig.of(EPOCH, 1, 1, 0).withSystems(Systems.builtIn()).withKinds(KINDS), LOADER.saveRules(save)).world();
        observer = shard.observe(4096, EVENTS, null);
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
        out.add("time " + arc.util.Time.time + " random " + Mathf.rand.seed0 + "," + Mathf.rand.seed1 + " next id " + lastId());
        for(mindustry.gen.Building build : buildings()) out.add(VanillaStates.building(build));
        for(var unit : Groups.unit) out.add(VanillaStates.unit(unit));
        if(hazards) out.addAll(com.xpdustry.shard.compat.v160.blocks.HazardStates.vanilla());
        return out;
    }

    List<String> shardStates(){
        var out = new ArrayList<String>();
        out.add("time " + shard.timers().time() + " random " + shard.rand().getState(0) + "," + shard.rand().getState(1) + " next id " + shard.ids().peek());
        for(mindustry.gen.Building expected : buildings()){
            Building build = shard.buildings().at(expected.tile.x, expected.tile.y);
            out.add(build == null ? "missing at (" + expected.tile.x + ", " + expected.tile.y + ")" : ShardStates.building(build));
        }
        for(int i = 0; i < shard.units().size(); i++) out.add(ShardStates.unit(shard.units().get(i)));
        if(hazards) out.addAll(com.xpdustry.shard.compat.v160.blocks.HazardStates.shard(shard));
        return out;
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

    static int lastId(){
        try{
            return open(EntityGroup.class.getDeclaredField("lastId")).getInt(null);
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

    /** Vanilla's side of a state line. Floats print exactly. */
    static final class VanillaStates{
        private VanillaStates(){
        }

        static String building(mindustry.gen.Building build){
            var out = new StringBuilder(build.block.name).append(" (").append(build.tile.x).append(", ").append(build.tile.y).append(")");
            base(out, build);
            return out.toString();
        }

        static void base(StringBuilder out, mindustry.gen.Building build){
            out.append(" id ").append(build.id).append(" team ").append(build.team.id).append(" rot ").append(build.rotation)
                .append(" at ").append(build.x).append(',').append(build.y).append(" health ").append(build.health)
                .append(" enabled ").append(build.enabled).append(" added ").append(build.isAdded()).append(" payload ").append(build.isPayload())
                .append(" efficiency ").append(build.efficiency).append(" items ").append(items(build));
            if(build.power != null){
                out.append(" power ").append(build.power.status).append(" links ").append(build.power.links)
                    .append(" graph ").append(build.power.graph == null ? "none" : build.power.graph.all.size + "/" + build.power.graph.getLastPowerProduced()
                        + "/" + build.power.graph.getLastPowerNeeded() + "/" + build.power.graph.getLastPowerStored());
            }
            switch(build){
                case mindustry.world.blocks.payloads.PayloadRouter.PayloadRouterBuild r -> {
                    conveyor(out, r);
                    out.append(" sorted ").append(r.sorted == null ? "none" : r.sorted.name).append(" recDir ").append(r.recDir)
                        .append(" matches ").append(r.matches).append(" controlTime ").append(r.controlTime);
                }
                case mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild c -> conveyor(out, c);
                case mindustry.world.blocks.payloads.PayloadBlock.PayloadBlockBuild<?> b -> payloadBlock(out, b);
                case Drill.DrillBuild d -> out.append(" progress ").append(d.progress).append(" warmup ").append(d.warmup)
                    .append(" timeDrilled ").append(d.timeDrilled).append(" lastDrillSpeed ").append(d.lastDrillSpeed)
                    .append(" dominant ").append(d.dominantItem == null ? -1 : d.dominantItem.id).append('x').append(d.dominantItems);
                default -> {}
            }
        }

        static void payloadBlock(StringBuilder out, mindustry.world.blocks.payloads.PayloadBlock.PayloadBlockBuild<?> b){
            out.append(" payVector ").append(b.payVector.x).append(',').append(b.payVector.y).append(" payRotation ").append(b.payRotation)
                .append(" carried ").append(b.carried).append(" timers ").append(timers(b)).append(" liquids ").append(liquids(b))
                .append(" holds ").append(payload(b.payload));
            switch(b){
                case mindustry.world.blocks.payloads.Constructor.ConstructorBuild c -> out.append(" recipe ").append(c.recipe == null ? "none" : c.recipe.name)
                    .append(" progress ").append(c.progress).append(" time ").append(c.time).append(" heat ").append(c.heat);
                case mindustry.world.blocks.payloads.PayloadDeconstructor.PayloadDeconstructorBuild d -> out.append(" progress ").append(d.progress)
                    .append(" accum ").append(d.accum == null ? "none" : Arrays.toString(d.accum)).append(" time ").append(d.time)
                    .append(" speedScl ").append(d.speedScl).append(" overlayTime ").append(d.overlayTime).append(" deconstructing ").append(payload(d.deconstructing));
                case mindustry.world.blocks.payloads.PayloadUnloader.PayloadUnloaderBuild u -> out.append(" exporting ").append(u.exporting)
                    .append(" lastOutputPower ").append(u.lastOutputPower);
                case mindustry.world.blocks.payloads.PayloadLoader.PayloadLoaderBuild l -> out.append(" exporting ").append(l.exporting);
                case mindustry.world.blocks.payloads.PayloadSource.PayloadSourceBuild src -> out.append(" unit ").append(src.unit == null ? "none" : src.unit.name)
                    .append(" block ").append(src.configBlock == null ? "none" : src.configBlock.name).append(" commandPos ").append(src.commandPos)
                    .append(" scl ").append(src.scl);
                case mindustry.world.blocks.payloads.PayloadMassDriver.PayloadDriverBuild d -> out.append(" link ").append(d.link)
                    .append(" turret ").append(d.turretRotation).append(" reload ").append(d.reloadCounter).append(" charge ").append(d.charge)
                    .append(" size ").append(d.targetSize).append('/').append(d.curSize).append(" payLength ").append(d.payLength)
                    .append(" effectDelay ").append(d.effectDelayTimer).append(" lastOther ").append(d.lastOther == null ? -1 : d.lastOther.id)
                    .append(" loaded ").append(d.loaded).append(" charging ").append(d.charging).append(" state ").append(d.state.ordinal())
                    .append(" waiting ").append(waiting(d.waitingShooters));
                default -> {}
            }
        }

        static String waiting(arc.struct.Queue<mindustry.gen.Building> queue){
            var out = new StringJoiner(",", "[", "]");
            for(int i = 0; i < queue.size; i++) out.add(String.valueOf(queue.get(i).id));
            return out.toString();
        }

        static String timers(mindustry.gen.Building build){
            try{
                var field = mindustry.gen.Building.class.getDeclaredField("timer");
                field.setAccessible(true);
                var interval = (arc.util.Interval)field.get(build);
                var times = arc.util.Interval.class.getDeclaredField("times");
                times.setAccessible(true);
                return Arrays.toString((float[])times.get(interval));
            }catch(ReflectiveOperationException e){
                throw new AssertionError(e);
            }
        }

        static String liquids(mindustry.gen.Building build){
            if(build.liquids == null) return "none";
            var out = new StringJoiner(",", "[", "]");
            for(var liquid : content.liquids()) if(build.liquids.get(liquid) != 0) out.add(liquid.name + "=" + build.liquids.get(liquid));
            return out.add("current=" + build.liquids.current().name).toString();
        }

        static void conveyor(StringBuilder out, mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild c){
            out.append(" progress ").append(c.progress).append(" itemRotation ").append(c.itemRotation).append(" animation ").append(c.animation)
                .append(" interp ").append(c.curInterp).append('/').append(c.lastInterp).append(" next ")
                .append(c.next == null ? "none" : c.next.tile.x + "," + c.next.tile.y).append(" blocked ").append(c.blocked)
                .append(" step ").append(c.step).append('/').append(c.stepAccepted).append(" item ").append(payload(c.item));
        }

        static String payload(mindustry.world.blocks.payloads.Payload payload){
            return switch(payload){
                case null -> "none";
                case mindustry.world.blocks.payloads.BuildPayload b -> {
                    var out = new StringBuilder("[build ").append(b.build.block.name);
                    base(out, b.build);
                    yield out.append(']').toString();
                }
                case mindustry.world.blocks.payloads.UnitPayload u -> "[" + unit(u.unit) + "]";
                default -> payload.getClass().getSimpleName();
            };
        }

        static String unit(mindustry.gen.Unit unit){
            var out = new StringBuilder("unit ").append(unit.type.name).append(" id ").append(unit.id).append(" team ").append(unit.team.id)
                .append(" at ").append(unit.x).append(',').append(unit.y).append(" rot ").append(unit.rotation).append(" vel ")
                .append(unit.vel.x).append(',').append(unit.vel.y).append(" health ").append(unit.health).append(" added ").append(unit.isAdded());
            if(unit instanceof Payloadc pay){
                out.append(" carries");
                for(var carried : pay.payloads()) out.append(' ').append(payload(carried));
            }
            return out.toString();
        }

        static String items(mindustry.gen.Building build){
            if(build.items == null) return "none";
            var out = new StringJoiner(",", "[", "]");
            for(var item : content.items()) if(build.items.get(item) != 0) out.add(item.name + "=" + build.items.get(item));
            return out.toString();
        }
    }

    /** Shard's side of a state line. */
    static final class ShardStates{
        private ShardStates(){
        }

        static String building(Building build){
            var out = new StringBuilder(build.block().name()).append(" (").append(build.tileX()).append(", ").append(build.tileY()).append(")");
            base(out, build);
            return out.toString();
        }

        static void base(StringBuilder out, Building build){
            out.append(" id ").append(build.id()).append(" team ").append(build.team).append(" rot ").append(build.rotation)
                .append(" at ").append(build.x()).append(',').append(build.y()).append(" health ").append(build.health)
                .append(" enabled ").append(build.enabled).append(" added ").append(build.isAdded()).append(" payload ").append(build.isPayload())
                .append(" efficiency ").append(build.efficiency).append(" items ").append(items(build));
            if(build.power != null){
                var graph = build.power.graph;
                out.append(" power ").append(build.power.status).append(" links ").append(links(build.power.links))
                    .append(" graph ").append(graph == null ? "none" : graph.size() + "/" + graph.getLastPowerProduced()
                        + "/" + graph.getLastPowerNeeded() + "/" + graph.getLastPowerStored());
            }
            switch(build){
                case PayloadRouter r -> {
                    conveyor(out, r);
                    out.append(" sorted ").append(r.sorted == null ? "none" : EPOCH.get(r.sorted.type(), r.sorted.id()).name()).append(" recDir ").append(r.recDir)
                        .append(" matches ").append(r.matches).append(" controlTime ").append(r.controlTime);
                }
                case PayloadConveyorBuild c -> conveyor(out, c);
                case PayloadBuild<?> b -> payloadBlock(out, b);
                case com.xpdustry.shard.sim.blocks.production.Drill d -> out.append(" progress ").append(d.progress).append(" warmup ").append(d.warmup)
                    .append(" timeDrilled ").append(d.timeDrilled).append(" lastDrillSpeed ").append(d.lastDrillSpeed)
                    .append(" dominant ").append(d.dominantItem).append('x').append(d.dominantItems);
                default -> {}
            }
        }

        static void payloadBlock(StringBuilder out, PayloadBuild<?> b){
            out.append(" payVector ").append(b.payVector.x).append(',').append(b.payVector.y).append(" payRotation ").append(b.payRotation)
                .append(" carried ").append(b.carried).append(" timers ").append(timers(b)).append(" liquids ").append(liquids(b))
                .append(" holds ").append(payload(b.payload));
            switch(b){
                case com.xpdustry.shard.sim.blocks.payload.Constructor c -> out.append(" recipe ").append(c.recipe == -1 ? "none" : EPOCH.block(c.recipe).name())
                    .append(" progress ").append(c.progress).append(" time ").append(c.time).append(" heat ").append(c.heat);
                case PayloadDeconstructor d -> out.append(" progress ").append(d.progress)
                    .append(" accum ").append(d.accum == null ? "none" : Arrays.toString(d.accum)).append(" time ").append(d.time)
                    .append(" speedScl ").append(d.speedScl).append(" overlayTime ").append(d.overlayTime).append(" deconstructing ").append(payload(d.deconstructing));
                case PayloadUnloader u -> out.append(" exporting ").append(u.exporting).append(" lastOutputPower ").append(u.lastOutputPower);
                case PayloadLoader l -> out.append(" exporting ").append(l.exporting);
                case PayloadSource src -> out.append(" unit ").append(src.unit == -1 ? "none" : EPOCH.unit(src.unit).name())
                    .append(" block ").append(src.configBlock == -1 ? "none" : EPOCH.block(src.configBlock).name())
                    .append(" commandPos ").append(src.commandPos == null ? "null" : "(" + src.commandPos.x + "," + src.commandPos.y + ")")
                    .append(" scl ").append(src.scl);
                case PayloadMassDriver d -> out.append(" link ").append(d.link)
                    .append(" turret ").append(d.turretRotation).append(" reload ").append(d.reloadCounter).append(" charge ").append(d.charge)
                    .append(" size ").append(d.targetSize).append('/').append(d.curSize).append(" payLength ").append(d.payLength)
                    .append(" effectDelay ").append(d.effectDelayTimer).append(" lastOther ").append(d.lastOther == null ? -1 : d.lastOther.id())
                    .append(" loaded ").append(d.loaded).append(" charging ").append(d.charging).append(" state ").append(d.state.ordinal())
                    .append(" waiting ").append(waiting(d.waitingShooters));
                default -> {}
            }
        }

        static String waiting(List<Building> queue){
            var out = new StringJoiner(",", "[", "]");
            for(Building b : queue) out.add(String.valueOf(b.id()));
            return out.toString();
        }

        static String timers(Building build){
            try{
                var field = Building.class.getDeclaredField("timer");
                field.setAccessible(true);
                return Arrays.toString((float[])field.get(build));
            }catch(ReflectiveOperationException e){
                throw new AssertionError(e);
            }
        }

        static String liquids(Building build){
            if(build.liquids == null) return "none";
            var out = new StringJoiner(",", "[", "]");
            for(int id = 0; id < EPOCH.liquids(); id++) if(build.liquids.get(id) != 0) out.add(EPOCH.liquid(id).name() + "=" + build.liquids.get(id));
            return out.add("current=" + EPOCH.liquid(build.liquids.current()).name()).toString();
        }

        static String links(com.xpdustry.shard.common.struct.IntSeq links){
            var out = new StringJoiner(", ", "[", "]");
            for(int i = 0; i < links.size; i++) out.add(String.valueOf(links.get(i)));
            return out.toString();
        }

        static void conveyor(StringBuilder out, PayloadConveyorBuild c){
            out.append(" progress ").append(c.progress).append(" itemRotation ").append(c.itemRotation).append(" animation ").append(c.animation)
                .append(" interp ").append(c.curInterp).append('/').append(c.lastInterp).append(" next ")
                .append(c.next == null ? "none" : c.next.tileX() + "," + c.next.tileY()).append(" blocked ").append(c.blocked)
                .append(" step ").append(c.step).append('/').append(c.stepAccepted).append(" item ").append(payload(c.item));
        }

        static String payload(Payload payload){
            return switch(payload){
                case null -> "none";
                case Payload.BuildPayload b -> {
                    var out = new StringBuilder("[build ").append(b.block().name());
                    base(out, b.building());
                    yield out.append(']').toString();
                }
                case Payload.UnitPayload u -> "[" + unit(u.unit()) + "]";
            };
        }

        static String unit(Unit unit){
            var out = new StringBuilder("unit ").append(unit.type().name()).append(" id ").append(unit.id()).append(" team ").append(unit.team)
                .append(" at ").append(unit.x).append(',').append(unit.y).append(" rot ").append(unit.rotation).append(" vel ")
                .append(unit.vel.x).append(',').append(unit.vel.y).append(" health ").append(unit.health).append(" added ").append(unit.isAdded());
            if(unit.payloads != null){
                out.append(" carries");
                for(int i = 0; i < unit.payloads.size(); i++) out.append(' ').append(payload(unit.payloads.get(i)));
            }
            return out.toString();
        }

        static String items(Building build){
            if(build.items == null) return "none";
            var out = new StringJoiner(",", "[", "]");
            for(int id = 0; id < EPOCH.items(); id++) if(build.items.get(id) != 0) out.add(EPOCH.item(id).name() + "=" + build.items.get(id));
            return out.toString();
        }
    }
}
