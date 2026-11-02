package com.xpdustry.shard.compat.v160.blocks.units;

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
import com.xpdustry.shard.sim.blocks.units.*;
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
 * One unit-block fixture built in a vanilla world and a Shard one, edited on the same frames and compared after every
 * frame (vanilla's frame runs as a server, so its Calls are captured): W3.9's {@code PayloadRun}, extended for unit
 * factories and reconstructors.
 * <ul>
 *   <li>every building: its items, liquids, power, payload, and a factory's or reconstructor's whole state by vanilla's
 *   names ({@code progress}, {@code time}, {@code speedScl}, {@code currentPlan}, {@code commandPos}, {@code command},
 *   {@code constructing}, {@code payVector}, {@code payRotation});</li>
 *   <li>every unit in {@code Groups.unit} order, with what it carries;</li>
 *   <li>the clock, the random's state and the next entity id;</li>
 *   <li>the frame's unit, payload and block snapshot Calls and {@code unitBlockSpawn}, byte for byte.</li>
 * </ul>
 * At the end, every building's save chunk. {@link #saveAt} has vanilla save, both load the bytes, and both run on.
 *
 * <p>Pins, as {@code PayloadRun}'s: vanilla's pathfinder threads are stopped, its physics random takes Shard's layer
 * randoms' state, and the process-wide pools start empty. Units keep the controllers their factories give them.
 */
final class UnitBlockRun{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();
    static final MsavReader READER = new MsavReader(EPOCH);
    static final MapLoader LOADER = new MapLoader(EPOCH, CODECS);
    static final Set<Integer> CALLS = Set.of(Packets.PickedBuildPayload.ID, Packets.PickedUnitPayload.ID, Packets.PayloadDropped.ID,
        Packets.UnitSpawn.ID, Packets.BlockSnapshot.ID, Packets.UnitDeath.ID, Packets.UnitDestroy.ID, Packets.UnitCapDeath.ID,
        Packets.UnitEnvDeath.ID, Packets.UnitDespawn.ID, Packets.UnitBlockSpawn.ID, Packets.TileConfig.ID, Packets.BuildDestroyed.ID,
        Packets.AssemblerDroneSpawned.ID, Packets.AssemblerUnitSpawned.ID);
    static final List<Class<? extends WorldEvent>> EVENTS = List.of(PayloadEvent.class, PayloadBlockEvent.class, CombatEvent.class,
        UnitEvent.class, UnitBlockEvent.class, BuildingEvent.class, AssemblerEvent.class);
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

    /**
     * Pins vanilla's {@code PayloadSeq} order: every unit assembler vanilla builds from now on (placed or loaded) gets a
     * map that iterates in first-added order, as Shard's {@link com.xpdustry.shard.sim.blocks.units.PayloadSeq} keeps.
     * Vanilla's own iterates in identity-hash order, which differs from one JVM to the next (a tier exception).
     */
    private static void pinPayloadSeqs(){
        for(var block : content.blocks()){
            if(!(block instanceof mindustry.world.blocks.units.UnitAssembler) || PINNED.contains(block)) continue;
            PINNED.add(block);
            arc.func.Prov<mindustry.gen.Building> original = block.buildType;
            block.buildType = () -> {
                mindustry.gen.Building build = original.get();
                try{
                    var blocks = ((mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild)build).blocks;
                    PAYLOADS.set(blocks, new OrderedIntMap<>());
                }catch(IllegalAccessException e){
                    throw new IllegalStateException(e);
                }
                return build;
            };
        }
    }

    private static final Set<Object> PINNED = Collections.newSetFromMap(new IdentityHashMap<>());
    static final Field PAYLOADS, READ_UNITS;

    static{
        try{
            PAYLOADS = open(mindustry.type.PayloadSeq.class.getDeclaredField("payloads"));
            READ_UNITS = open(mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild.class.getDeclaredField("readUnits"));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Arc's {@code ObjectIntMap}, iterated in first-added order; {@code clear} forgets the order. */
    static final class OrderedIntMap<K> extends ObjectIntMap<K>{
        private final Seq<K> order = new Seq<>();

        @Override
        public int increment(K key, int defaultValue, int increment){
            if(!containsKey(key)) order.add(key);
            return super.increment(key, defaultValue, increment);
        }

        @Override
        public void put(K key, int value){
            if(!containsKey(key)) order.add(key);
            super.put(key, value);
        }

        @Override
        public void clear(){
            order.clear();
            super.clear();
        }

        @Override
        public void clear(int maximumCapacity){
            order.clear();
            super.clear(maximumCapacity);
        }

        @Override
        public Entries<K> entries(){
            var map = this;
            return new Entries<>(this){
                int at;

                @Override
                public boolean hasNext(){
                    return at < order.size;
                }

                @Override
                public ObjectIntMap.Entry<K> next(){
                    var entry = new ObjectIntMap.Entry<K>();
                    entry.key = order.get(at++);
                    entry.value = map.get(entry.key);
                    return entry;
                }
            };
        }

        @Override
        public Entries<K> iterator(){
            return entries();
        }
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
    int frame, saveAt = -1, calls;
    /** What a fixture exercised, by name. */
    final TreeMap<String, Integer> seen = new TreeMap<>();
    private boolean physicsPinned;

    /** A {@code size × size} stone world in play on both sides, no unit cap, no waves; {@code overlays} by position. */
    UnitBlockRun(int size, Map<Integer, String> overlays){
        this(size, size, pos -> "stone", overlays);
    }

    /** A {@code width × height} world of {@code floors} by position in play on both sides, no unit cap, no waves. */
    UnitBlockRun(int width, int height, IntFunction<String> floors, Map<Integer, String> overlays){
        this(width, height, floors, overlays, null);
    }

    /**
     * {@link #UnitBlockRun(int, int, IntFunction, Map)} with a data patch ({@code patch}, vanilla's JSON, or null for
     * none) in both sides' content: vanilla's {@code state.data.load}, and a Shard world of {@code DataPatcher}'s epoch.
     * Vanilla's saves carry the patch, so a load patches both sides again.
     */
    UnitBlockRun(int width, int height, IntFunction<String> floors, Map<Integer, String> overlays, String patch){
        int size = width;
        Vanilla.reset();
        state.rules = new mindustry.game.Rules();
        state.rules.canGameOver = false;
        state.map = new mindustry.maps.Map(StringMap.of("name", "oracle"));
        world.loadGenerator(width, height, tiles -> {
            for(int y = 0; y < height; y++){
                for(int x = 0; x < width; x++){
                    String overlay = overlays.get(y * size + x);
                    tiles.set(x, y, new Tile(x, y, content.block(floors.apply(y * size + x)), overlay == null ? mindustry.content.Blocks.air : content.block(overlay), mindustry.content.Blocks.air));
                }
            }
        });
        logic.play();
        Vanilla.runPosted();
        Epoch epoch = EPOCH;
        if(patch != null){
            var asset = new mindustry.mod.data.PatchAsset(patch);
            state.data.load(Seq.with(asset));
            assertFalse(asset.error, "vanilla applied the patch: " + asset.warnings);
            var applied = com.xpdustry.shard.content.patch.DataPatcher.of(EPOCH,
                List.of(new com.xpdustry.shard.content.patch.DataAsset.PatchAsset("patch.json", patch)), "serpulo");
            assertEquals(List.of(), applied.warnings(), "Shard applied the patch");
            epoch = applied.epoch();
            assertTrue(epoch.isPatched(), "the patch changed Shard's content");
        }
        arc.util.Time.setInternalTime(0);
        HazardPools.empty();
        state.rules.disableUnitCap = true;
        state.rules.waveTimer = false;
        stopPathfinders();
        Groups.updatePooling();
        Bullet.create();
        Pools.get(Bullet.class, () -> null).clear();

        shard = new World(WorldConfig.of(epoch, width, height, 1).withSystems(Systems.builtIn()).withKinds(KINDS));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++){
            shard.tiles().floor(pos, EPOCH.block(floors.apply(pos)).id());
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
        pinPayloadSeqs();
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

    /** The team {@link #place}, {@link #feedUnit} and {@link #feedBuilding} build for: sharded unless {@link #team} moved it. */
    private Team team = Team.sharded;

    /** Builds for {@code team} from now on. */
    UnitBlockRun team(Team team){
        this.team = team;
        return this;
    }

    /** {@code block} at (x, y) facing {@code rotation}, as a map editor places it. */
    UnitBlockRun place(String block, int x, int y, int rotation){
        world.tile(x, y).setBlock(content.block(block), team, rotation);
        Vanilla.runPosted();
        // the world's own definition: a patched world's differs from EPOCH's
        shard.buildings().setBlock(x, y, ((Epoch)shard.content()).block(block), team.id, rotation, Cause.SERVER);
        return this;
    }

    /** {@code tile.setAir()}. */
    void remove(int x, int y){
        world.tile(x, y).setAir();
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block("air"), 0, 0, Cause.SERVER);
    }

    /** Items into the building at (x, y). */
    UnitBlockRun stock(int x, int y, String item, int amount){
        world.build(x, y).items.add(content.item(item), amount);
        shard.buildings().at(x, y).items.add(EPOCH.item(item).id(), amount);
        return this;
    }

    /** {@code configureAny(value)} on both sides, as the server configures. */
    UnitBlockRun configure(int x, int y, Object vanilla, Object shardValue){
        world.build(x, y).configureAny(vanilla);
        Vanilla.runPosted();
        shard.buildings().at(x, y).configureAny(shardValue);
        return this;
    }

    /** An item source of {@code item} at (x, y). */
    UnitBlockRun itemSource(int x, int y, String item){
        place("item-source", x, y, 0);
        return configure(x, y, content.item(item), ContentRef.item(EPOCH.item(item).id()));
    }

    /** A liquid source of {@code liquid} at (x, y). */
    UnitBlockRun liquidSource(int x, int y, String liquid){
        place("liquid-source", x, y, 0);
        return configure(x, y, content.liquid(liquid), ContentRef.liquid(EPOCH.liquid(liquid).id()));
    }

    /**
     * Feeds the block of size {@code size} centred at (x, y) from above: for each item, a source two tiles over its top
     * edge and a titanium conveyor down into it, from its left column on; and a power source under its bottom-left
     * corner, with a liquid source beside it for each liquid.
     */
    UnitBlockRun feed(int x, int y, int size, List<String> items, List<String> liquids){
        int left = x - (size - 1) / 2, top = y + size / 2, bottom = y - (size - 1) / 2;
        for(int i = 0; i < items.size(); i++){
            place("titanium-conveyor", left + i, top + 1, 3);
            itemSource(left + i, top + 2, items.get(i));
        }
        place("power-source", left, bottom - 1, 0);
        for(int i = 0; i < liquids.size(); i++) liquidSource(left + 1 + i, bottom - 1, liquids.get(i));
        return this;
    }

    /** Both sides' rules for unit blocks: build speed, unit cost, and a fixed cap ({@code -1} for none). */
    UnitBlockRun unitRules(float buildSpeed, float cost, int cap){
        state.rules.unitBuildSpeedMultiplier = buildSpeed;
        state.rules.unitCostMultiplier = cost;
        var rules = shard.part(WorldRules.class).rules;
        rules.unitBuildSpeedMultiplier = buildSpeed;
        rules.unitCostMultiplier = cost;
        if(cap >= 0){
            state.rules.disableUnitCap = rules.disableUnitCap = false;
            state.rules.unitCapVariable = rules.unitCapVariable = false;
            state.rules.unitCap = rules.unitCap = cap;
        }
        return this;
    }

    /** Bans units of {@code type} on both sides ({@code rules.bannedUnits}). */
    UnitBlockRun ban(String type){
        state.rules.bannedUnits.add(content.unit(type));
        shard.part(WorldRules.class).rules.bannedUnits.add(EPOCH.unit(type).id());
        return this;
    }

    /**
     * Plays the rest as the campaign on {@code sector} of {@code planet}: vanilla's rules take the sector and the
     * planet's rules ({@code Planet.applyRules}, as a save's {@code readRules} does), and Shard's take vanilla's rules
     * through their JSON. Content researched nowhere (a server's {@code unlocked} is false) is then unresearched.
     */
    UnitBlockRun campaign(String planet, int sector){
        var p = content.planet(planet);
        state.rules.sector = p.sectors.get(sector);
        p.applyRules(state.rules);
        new com.xpdustry.shard.compat.v160.rules.RulesJson(EPOCH).read(shard.part(WorldRules.class).rules, mindustry.io.JsonIO.write(state.rules));
        return this;
    }

    /** {@code unit.kill()} on both sides, for the added unit of {@code type} with the lowest id. */
    void kill(String type){
        vanillaUnit(type).kill();
        Vanilla.runPosted();
        shardUnit(type).kill();
    }

    /** A unit of {@code type} at world (x, y), added, with an {@link Inert} AI. */
    UnitBlockRun spawn(String type, float x, float y){
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
        var payload = new mindustry.world.blocks.payloads.BuildPayload(content.block(block), team);
        vanillaSetup.accept(payload.build);
        target.handlePayload(target, payload);
        Building ours = shard.buildings().at(x, y);
        Building carried = shard.buildings().createPayload(EPOCH.block(block), team.id);
        shardSetup.accept(carried);
        ours.handlePayload(ours, new Payload.BuildPayload(carried));
        count("fed " + block);
    }

    /** A new unit of {@code type} with an inert AI put on the payload block at (x, y), as {@code new UnitPayload(unit)}. */
    void feedUnit(int x, int y, String type){
        mindustry.gen.Building target = world.build(x, y);
        if(target.getPayload() != null) return;
        mindustry.gen.Unit unit = content.unit(type).create(target.team);
        unit.controller(new Inert());
        unit.set(target.x, target.y);
        target.handlePayload(target, new mindustry.world.blocks.payloads.UnitPayload(unit));
        Building ours = shard.buildings().at(x, y);
        Unit carried = Unit.create(shard, EPOCH.unit(type), ours.team);
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

    UnitBlockRun at(int when, Runnable edit){
        edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
        return this;
    }

    /** Vanilla saves its world at the start of frame {@code frame}; both load it and run on. */
    UnitBlockRun saveAt(int frame){
        saveAt = frame;
        return this;
    }

    void count(String what){
        seen.merge(what, 1, Integer::sum);
    }

    void count(String what, int amount){
        seen.merge(what, amount, Integer::sum);
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
            frameStart = unitsNow();
            Vanilla.net(Vanilla.NetMode.server, Vanilla::frame);
            shard.tick();
            var sent = new ArrayList<>(Vanilla.captured().subList(before, Vanilla.captured().size()));
            var events = observer.drain();
            for(var event : events) count(event.getClass().getSimpleName());
            List<String> expected = CallFrames.vanilla(sent, CALLS);
            calls += expected.size();
            try{
                CallFrames.same(expected, CallFrames.shard(shard, events), "Calls in frame " + frame);
            }catch(AssertionError e){
                throw new AssertionError(e.getMessage() + "\n" + dumpDiagnosis(), e);
            }
            same("after frame " + frame);
            noteExercised();
        }
        observer.close();
        assertChunksEqual();
    }

    private void noteExercised(){
        for(var unit : Groups.unit){
            if(unit instanceof Payloadc pay && !pay.payloads().isEmpty()) count("carrying");
            made(unit.type.name);
        }
        for(var tile : world.tiles){
            if(tile.build instanceof mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild c && tile.isCenter() && c.item != null) count("on " + tile.block().name);
            if(tile.build instanceof mindustry.world.blocks.units.Reconstructor.ReconstructorBuild r && tile.isCenter() && VanillaStates.constructing(r)) count("upgrading in " + tile.block().name);
            if(tile.build instanceof mindustry.world.blocks.units.UnitBlock.UnitBuild u && tile.isCenter() && u.payload != null) count("holding in " + tile.block().name);
            if(tile.build instanceof mindustry.world.blocks.units.UnitBlock.UnitBuild u && tile.isCenter() && u.payload != null) made(u.payload.unit.type.name);
            if(tile.build instanceof mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild c && tile.isCenter()
                && c.item instanceof mindustry.world.blocks.payloads.UnitPayload u) made(u.unit.type.name);
            if(tile.build instanceof mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild a && tile.isCenter()){
                String name = tile.block().name;
                if(a.progress > 0f) count("assembling in " + name);
                if(a.wasOccupied) count("occupied " + name);
                if(a.blocks.any()) count("payloads in " + name);
                if(a.currentTier > 0) count("tier " + a.currentTier + " " + name);
                int ready = 0;
                for(var unit : a.units) if(((mindustry.ai.types.AssemblerAI)unit.controller()).inPosition()) ready++;
                if(ready == ((mindustry.world.blocks.units.UnitAssembler)tile.block()).dronesCreated) count("drones ready in " + name);
                if(ready > 0 && ready < ((mindustry.world.blocks.units.UnitAssembler)tile.block()).dronesCreated) count("drones partly ready in " + name);
            }
            if(tile.build instanceof mindustry.world.blocks.units.UnitAssemblerModule.UnitAssemblerModuleBuild m && tile.isCenter()){
                if(m.link != null) count("linked " + tile.block().name);
                if(m.payload != null) count("holding in " + tile.block().name);
            }
        }
    }

    private String frameStart = "";
    /** Every unit's position and velocity on both sides. */
    private String unitsNow(){
        var out = new StringBuilder();
        for(var o : Groups.unit) out.append("\n   vanilla ").append(o.type.name).append(' ').append(o.id).append(" at ").append(o.x).append(',').append(o.y)
            .append(" vel ").append(o.vel.x).append(',').append(o.vel.y);
        for(int i = 0; i < shard.units().size(); i++){
            Unit o = shard.units().get(i);
            out.append("\n   shard ").append(o.type().name()).append(' ').append(o.id()).append(" at ").append(o.x).append(',').append(o.y)
                .append(" vel ").append(o.vel.x).append(',').append(o.vel.y);
        }
        return out.toString();
    }

    /** Why vanilla's held units didn't dump: its checks, and the units near each on both sides. */
    private String dumpDiagnosis(){
        var out = new StringBuilder("units at the frame's start:" + frameStart + "\ndump checks:");
        for(var tile : world.tiles){
            if(!(tile.build instanceof mindustry.world.blocks.units.UnitBlock.UnitBuild u) || !tile.isCenter() || u.payload == null) continue;
            mindustry.gen.Unit unit = u.payload.unit;
            float size = unit.physicSize() * 1.05f;
            out.append("\n vanilla ").append(tile.block().name).append(" holds ").append(unit.type.name).append(" at ").append(unit.x).append(',').append(unit.y)
                .append(" rot ").append(unit.rotation).append(" physicSize*1.05 ").append(size)
                .append(" payVector ").append(u.payVector.x).append(',').append(u.payVector.y).append(" payRotation ").append(u.payRotation)
                .append(" rotdeg ").append(u.rotdeg()).append(" front ").append(u.front() == null ? "none" : u.front().block.name + " solid " + u.front().tile.solid())
                .append(" canCreate ").append(mindustry.entities.Units.canCreate(unit.team, unit.type))
                .append(" count ").append(mindustry.entities.Units.count(unit.x, unit.y, size, o -> o.isGrounded() && o.type.allowLegStep == unit.type.allowLegStep));
            var solid = unit.solidity();
            if(solid != null){
                arc.math.geom.Vec2 v = new arc.math.geom.Vec2().trns(unit.rotation, 1f);
                int tx = mindustry.core.World.toTile(unit.x + v.x), ty = mindustry.core.World.toTile(unit.y + v.y);
                out.append(" solid(").append(tx).append(',').append(ty).append(") ").append(solid.solid(tx, ty));
            }
            mindustry.entities.Units.nearby(unit.x - 40, unit.y - 40, 80, 80, o -> out.append("\n   near ").append(o.type.name).append(" id ").append(o.id)
                .append(" at ").append(o.x).append(',').append(o.y).append(" hit ").append(o.hitSize).append(" grounded ").append(o.isGrounded()));
        }
        for(int i = 0; i < shard.units().size(); i++){
            Unit o = shard.units().get(i);
            out.append("\n shard unit ").append(o.type().name()).append(" id ").append(o.id()).append(" at ").append(o.x).append(',').append(o.y)
                .append(" hit ").append(o.hitSize).append(" grounded ").append(o.isGrounded());
        }
        return out.toString();
    }

    private void made(String type){
        if(seen("made " + type) == 0) count("made " + type);
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

    /**
     * {@code NetServer.writeBlockSnapshots}' bytes on both sides, chunks joined: every synced building of each present
     * team, as {@code i32 pos}, {@code i16} block id and {@code writeSync}. Shard's are {@code BlockSnapshots}'.
     */
    void sameBlockSnapshots(String when){
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        var writes = new Writes(out);
        int sent = 0;
        try{
            for(var team : state.teams.present){
                for(var build : indexer.getFlagged(team.team, mindustry.world.meta.BlockFlag.synced)){
                    sent++;
                    out.writeInt(build.pos());
                    out.writeShort(build.block.id);
                    build.writeSync(writes);
                }
            }
            out.flush();
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        var ours = new ByteArrayOutputStream();
        int amount = 0;
        for(var packet : new com.xpdustry.shard.compat.v160.sync.BlockSnapshots(EPOCH, CODECS).write(shard)){
            amount += packet.amount();
            ours.writeBytes(packet.data());
        }
        assertEquals(sent + " " + hex(bytes.toByteArray()), amount + " " + hex(ours.toByteArray()), when + ": block snapshots");
        count("block snapshots compared");
        count("synced buildings", sent);
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
                .append(" efficiency ").append(build.efficiency).append(" items ").append(items(build)).append(" liquids ").append(liquids(build));
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
                case mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild f -> {
                    unitBuild(out, f);
                    out.append(" currentPlan ").append(f.currentPlan).append(" commandPos ").append(vec(f.commandPos))
                        .append(" command ").append(f.command == null ? -1 : f.command.id);
                }
                case mindustry.world.blocks.units.Reconstructor.ReconstructorBuild r -> {
                    unitBuild(out, r);
                    out.append(" commandPos ").append(vec(r.commandPos)).append(" command ").append(r.command == null ? -1 : r.command.id)
                        .append(" constructing ").append(constructing(r));
                }
                case Drill.DrillBuild d -> out.append(" progress ").append(d.progress).append(" warmup ").append(d.warmup)
                    .append(" timeDrilled ").append(d.timeDrilled).append(" lastDrillSpeed ").append(d.lastDrillSpeed)
                    .append(" dominant ").append(d.dominantItem == null ? -1 : d.dominantItem.id).append('x').append(d.dominantItems);
                case mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild a -> {
                    payloadBlock(out, a);
                    out.append(" progress ").append(a.progress).append(" warmup ").append(a.warmup).append(" droneWarmup ").append(a.droneWarmup)
                        .append(" powerWarmup ").append(a.powerWarmup).append(" sameTypeWarmup ").append(a.sameTypeWarmup)
                        .append(" invalidWarmup ").append(a.invalidWarmup).append(" tier ").append(a.currentTier).append('/').append(a.lastTier)
                        .append(" wasOccupied ").append(a.wasOccupied).append(" droneProgress ").append(a.droneProgress).append('/').append(a.totalDroneProgress)
                        .append(" commandPos ").append(vec(a.commandPos)).append(" drones");
                    for(var unit : a.units) out.append(' ').append(unit.id);
                    out.append(" modules");
                    for(var module : a.modules) out.append(' ').append(module.tile.x).append(',').append(module.tile.y);
                    out.append(" blocks ").append(a.blocks.total()).append(seq(a.blocks)).append(" readUnits ").append(readUnits(a));
                }
                case mindustry.world.blocks.units.UnitAssemblerModule.UnitAssemblerModuleBuild m -> {
                    payloadBlock(out, m);
                    out.append(" link ").append(m.link == null ? "none" : m.link.tile.x + "," + m.link.tile.y).append(" lastChange ").append(m.lastChange);
                }
                case mindustry.world.blocks.payloads.PayloadSource.PayloadSourceBuild p -> {
                    payloadBlock(out, p);
                    out.append(" unit ").append(p.unit == null ? "none" : p.unit.name).append(" block ").append(p.configBlock == null ? "none" : p.configBlock.name)
                        .append(" scl ").append(p.scl);
                }
                case mindustry.world.blocks.payloads.PayloadVoid.PayloadVoidBuild v -> payloadBlock(out, v);
                default -> {}
            }
        }

        static void payloadBlock(StringBuilder out, mindustry.world.blocks.payloads.PayloadBlock.PayloadBlockBuild<?> p){
            out.append(" payVector ").append(p.payVector.x).append(',').append(p.payVector.y).append(" payRotation ").append(p.payRotation)
                .append(" payload ").append(payload(p.payload));
        }

        /** The seq's entries in its (pinned) order, zeros included. */
        static String seq(mindustry.type.PayloadSeq seq){
            try{
                var map = (ObjectIntMap<?>)PAYLOADS.get(seq);
                var out = new StringJoiner(",", "[", "]");
                for(var entry : map.entries()) out.add(((mindustry.ctype.UnlockableContent)entry.key).name + "=" + entry.value);
                return out.toString();
            }catch(IllegalAccessException e){
                throw new AssertionError(e);
            }
        }

        static String readUnits(mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild a){
            try{
                var ids = (IntSeq)READ_UNITS.get(a);
                var out = new StringJoiner(",", "[", "]");
                for(int i = 0; i < ids.size; i++) out.add(String.valueOf(ids.get(i)));
                return out.toString();
            }catch(IllegalAccessException e){
                throw new AssertionError(e);
            }
        }

        static void unitBuild(StringBuilder out, mindustry.world.blocks.units.UnitBlock.UnitBuild u){
            out.append(" progress ").append(u.progress).append(" time ").append(u.time).append(" speedScl ").append(u.speedScl)
                .append(" payVector ").append(u.payVector.x).append(',').append(u.payVector.y).append(" payRotation ").append(u.payRotation)
                .append(" payload ").append(payload(u.payload));
        }

        static String vec(arc.math.geom.Vec2 v){
            return v == null ? "none" : v.x + "," + v.y;
        }

        static boolean constructing(mindustry.world.blocks.units.Reconstructor.ReconstructorBuild r){
            try{
                return open(mindustry.world.blocks.units.Reconstructor.ReconstructorBuild.class.getDeclaredField("constructing")).getBoolean(r);
            }catch(ReflectiveOperationException e){
                throw new AssertionError(e);
            }
        }

        static String liquids(mindustry.gen.Building build){
            if(build.liquids == null) return "none";
            var out = new StringJoiner(",", "[", "]");
            for(var liquid : content.liquids()) if(build.liquids.get(liquid) != 0) out.add(liquid.name + "=" + build.liquids.get(liquid));
            return out.toString();
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
            if(unit.controller() instanceof mindustry.ai.types.AssemblerAI ai){
                out.append(" assembler-ai ").append(ai.targetPos.x).append(',').append(ai.targetPos.y).append(' ').append(ai.targetAngle)
                    .append(" in position ").append(ai.inPosition());
            }
            if(unit instanceof BuildingTetherc tether) out.append(" tether ").append(tether.building() == null ? "none" : tether.building().tile.x + "," + tether.building().tile.y);
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
                .append(" efficiency ").append(build.efficiency).append(" items ").append(items(build)).append(" liquids ").append(liquids(build));
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
                case UnitFactory f -> {
                    unitBuild(out, f);
                    out.append(" currentPlan ").append(f.currentPlan).append(" commandPos ").append(vec(f.commandPos))
                        .append(" command ").append(f.command);
                }
                case Reconstructor r -> {
                    unitBuild(out, r);
                    out.append(" commandPos ").append(vec(r.commandPos)).append(" command ").append(r.command)
                        .append(" constructing ").append(r.constructing);
                }
                case com.xpdustry.shard.sim.blocks.production.Drill d -> out.append(" progress ").append(d.progress).append(" warmup ").append(d.warmup)
                    .append(" timeDrilled ").append(d.timeDrilled).append(" lastDrillSpeed ").append(d.lastDrillSpeed)
                    .append(" dominant ").append(d.dominantItem).append('x').append(d.dominantItems);
                case UnitAssembler a -> {
                    payloadBlock(out, a);
                    out.append(" progress ").append(a.progress).append(" warmup ").append(a.warmup).append(" droneWarmup ").append(a.droneWarmup)
                        .append(" powerWarmup ").append(a.powerWarmup).append(" sameTypeWarmup ").append(a.sameTypeWarmup)
                        .append(" invalidWarmup ").append(a.invalidWarmup).append(" tier ").append(a.currentTier).append('/').append(a.lastTier)
                        .append(" wasOccupied ").append(a.wasOccupied).append(" droneProgress ").append(a.droneProgress).append('/').append(a.totalDroneProgress)
                        .append(" commandPos ").append(vec(a.commandPos)).append(" drones");
                    for(Unit unit : a.units) out.append(' ').append(unit.id());
                    out.append(" modules");
                    for(UnitAssemblerModule module : a.modules) out.append(' ').append(module.tileX()).append(',').append(module.tileY());
                    var seq = new StringJoiner(",", "[", "]");
                    for(int i = 0; i < a.blocks.entries(); i++) seq.add(EPOCH.get(a.blocks.type(i), a.blocks.id(i)).name() + "=" + a.blocks.amount(i));
                    var read = new StringJoiner(",", "[", "]");
                    for(int i = 0; i < a.readUnits.size; i++) read.add(String.valueOf(a.readUnits.get(i)));
                    out.append(" blocks ").append(a.blocks.total()).append(seq).append(" readUnits ").append(read);
                }
                case UnitAssemblerModule m -> {
                    payloadBlock(out, m);
                    out.append(" link ").append(m.link == null ? "none" : m.link.tileX() + "," + m.link.tileY()).append(" lastChange ").append(m.lastChange);
                }
                case PayloadSource p -> {
                    payloadBlock(out, p);
                    out.append(" unit ").append(p.unit == -1 ? "none" : EPOCH.unit(p.unit).name()).append(" block ")
                        .append(p.configBlock == -1 ? "none" : EPOCH.block(p.configBlock).name()).append(" scl ").append(p.scl);
                }
                case com.xpdustry.shard.sim.blocks.payload.PayloadVoid v -> payloadBlock(out, v);
                default -> {}
            }
        }

        static void payloadBlock(StringBuilder out, PayloadBuild<?> p){
            out.append(" payVector ").append(p.payVector.x).append(',').append(p.payVector.y).append(" payRotation ").append(p.payRotation)
                .append(" payload ").append(payload(p.payload));
        }

        static void unitBuild(StringBuilder out, UnitBuild u){
            out.append(" progress ").append(u.progress).append(" time ").append(u.time).append(" speedScl ").append(u.speedScl)
                .append(" payVector ").append(u.payVector.x).append(',').append(u.payVector.y).append(" payRotation ").append(u.payRotation)
                .append(" payload ").append(payload(u.payload));
        }

        static String vec(com.xpdustry.shard.common.geom.Vec2 v){
            return v == null ? "none" : v.x + "," + v.y;
        }

        static String liquids(Building build){
            if(build.liquids == null) return "none";
            var out = new StringJoiner(",", "[", "]");
            for(int id = 0; id < EPOCH.liquids(); id++) if(build.liquids.get(id) != 0) out.add(EPOCH.liquid(id).name() + "=" + build.liquids.get(id));
            return out.toString();
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
            if(unit.controller instanceof com.xpdustry.shard.sim.ai.AssemblerAi ai){
                out.append(" assembler-ai ").append(ai.targetPos.x).append(',').append(ai.targetPos.y).append(' ').append(ai.targetAngle)
                    .append(" in position ").append(ai.inPosition());
            }
            if(unit.params().tethered()) out.append(" tether ").append(unit.tether == null ? "none" : unit.tether.tileX() + "," + unit.tether.tileY());
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
